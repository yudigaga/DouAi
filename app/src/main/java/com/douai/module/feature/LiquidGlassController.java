package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.ImageView;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.LocalOptions;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Window-scoped native surfaces; does not claim to restyle opaque renderer internals. */
public final class LiquidGlassController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private final Application app;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private final Map<View,Monitor> windows=new IdentityHashMap<>();
    private final Map<View,WeakReference<Activity>> known=new WeakHashMap<>();
    private final Set<Activity> resumed=Collections.newSetFromMap(new IdentityHashMap<>());
    private LiquidGlassSettings settings;
    private LocalOptions options;
    private volatile boolean closed;
    private boolean interacting;
    private final Runnable releaseTouch=()->motionInteracting(false);

    public LiquidGlassController(Context context,LocalOptions options,Consumer<String> log) {
        app=(Application)context.getApplicationContext();this.log=log==null?ignored->{}:log;
        this.options=options;settings=LiquidGlassSettings.read(options);
        app.registerActivityLifecycleCallbacks(this);
    }

    public void setOptions(LocalOptions options) {
        if(closed)return;
        LiquidGlassSettings next=LiquidGlassSettings.read(options);
        if(next.enabled && Looper.myLooper()!=Looper.getMainLooper()) {
            try {
                // Publication already runs on the host worker; new windows reuse this checked metadata.
                HomeUiCustomizationSettings.readEncodedIcons(options.text(HomeUiCustomizationSettings.ICONS,"{}"));
            } catch(RuntimeException|LinkageError invalidIcons) {
                // The UI consumer retains its last valid icons and reports the invalid snapshot as before.
            }
        }
        run(()->{
            if(closed)return;this.options=options;settings=next;
            ArrayList<Monitor> existing=new ArrayList<>(windows.values());
            if(!settings.enabled)clearWindows();
            else for(Map.Entry<View,WeakReference<Activity>> entry:new ArrayList<>(known.entrySet())) {
                Activity owner=entry.getValue().get();
                if(owner!=null && resumed.contains(owner) && windowPresent(entry.getKey()))attach(entry.getKey(),owner);
            }
            // New monitors already consumed this snapshot in attach(); refresh only existing windows.
            for(Monitor monitor:existing)if(windows.get(monitor.root)==monitor) {
                monitor.rejectedLayouts.clear();
                monitor.clearanceRejected.clear();
                monitor.customization.setOptionsDeferred(options);monitor.refresh();
            }
            updateMotion();
        });
    }

    public void onWindow(View root) {
        if(root==null)return;
        run(()->{
            if(closed)return;Activity owner=activity(root.getContext());
            if(owner==null && resumed.size()==1)owner=resumed.iterator().next();
            if(owner==null || owner.isFinishing() || owner.isDestroyed())return;
            known.put(root,new WeakReference<>(owner));
            if(settings.enabled && resumed.contains(owner))attach(root,owner);
        });
    }

    public void onWindowClosed(View root) {
        if(root==null)return;
        run(()->{Monitor monitor=windows.get(root);if(monitor!=null)monitor.close();known.remove(root);updateMotion();});
    }

    public void onTouch(MotionEvent event) {
        onTouch(event,null);
    }
    void onTouch(MotionEvent event,View dispatchRoot) {
        if(event==null || closed)return;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){main.removeCallbacks(releaseTouch);motionInteracting(true);
            Monitor monitor=windows.get(dispatchRoot);if(monitor!=null)monitor.clearance.onTouch(event);}
        else if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL)main.post(releaseTouch);
    }
    /** Called after native window dispatch so a protected DOWN remains protected for its entire gesture. */
    public void onTouchDispatched(MotionEvent event) {
        onTouchDispatched(event,null);
    }
    void onTouchDispatched(MotionEvent event,View dispatchRoot) {
        if(closed || event==null)return;
        Monitor monitor=windows.get(dispatchRoot);if(monitor!=null)monitor.clearance.onTouchDispatched(event);
    }
    private void motionInteracting(boolean value){
        if(closed)return;interacting=value;for(Monitor monitor:windows.values())monitor.motion.setInteracting(value);
    }

    private void attach(View root,Activity owner) {
        if(windows.containsKey(root))return;
        Monitor monitor=new Monitor(root,owner);windows.put(root,monitor);monitor.attach();
    }
    private void updateMotion() {
        for(Monitor monitor:windows.values()) {
            ArrayList<View> targets=new ArrayList<>();
            Set<View> blocked=monitor.clearance.blockedViews();
            if(monitor.root.isShown() && foregroundWindow(monitor)) {
                for(View candidate:monitor.motionCandidates) {
                    Binding binding=monitor.bindings.get(candidate);
                    if(!blocked.contains(candidate) && (binding==null || !binding.clearanceFallback))targets.add(candidate);
                }
            }
            monitor.motion.setTargets(targets,monitor.root,monitor.lightCallback);
            boolean requested=settings.enabled && settings.gravity && !targets.isEmpty();
            monitor.motion.setActive(requested);
            monitor.motion.setInteracting(interacting);
            String state="requested="+requested+";targets="+targets.size()+";blocked="+blocked.size();
            if(!state.equals(monitor.lastMotionState)) {
                monitor.lastMotionState=state;log.accept("liquid_glass: motion_"+state);
            }
        }
    }
    private boolean foregroundWindow(Monitor monitor) {
        if(!resumed.contains(monitor.owner))return false;
        for(Monitor peer:windows.values())if(peer.owner==monitor.owner && peer.root.hasWindowFocus())return true;
        return false;
    }
    private static boolean windowPresent(View root){return root.isAttachedToWindow() || root.getParent()!=null;}
    private void clearWindows() {
        for(Monitor monitor:new ArrayList<>(windows.values()))monitor.close();windows.clear();
    }
    @Override public void onActivityResumed(Activity activity) {
        run(()->{
            if(closed)return;resumed.add(activity);onWindow(activity.getWindow().getDecorView());
            if(settings.enabled)for(Map.Entry<View,WeakReference<Activity>> entry:new ArrayList<>(known.entrySet())) {
                if(entry.getValue().get()==activity && windowPresent(entry.getKey()))attach(entry.getKey(),activity);
            }
        });
    }
    @Override public void onActivityPaused(Activity activity) {
        run(()->{
            resumed.remove(activity);main.removeCallbacks(releaseTouch);motionInteracting(false);
            for(Monitor monitor:new ArrayList<>(windows.values()))if(monitor.owner==activity)monitor.close();
            updateMotion();
        });
    }
    @Override public void onActivityDestroyed(Activity activity) {
        onActivityPaused(activity);run(()->known.entrySet().removeIf(e->e.getValue().get()==activity));
    }
    @Override public void close() {
        run(()->{if(closed)return;closed=true;main.removeCallbacks(releaseTouch);clearWindows();
            known.clear();resumed.clear();app.unregisterActivityLifecycleCallbacks(this);});
    }
    private void run(Runnable runnable){if(Looper.myLooper()==main.getLooper())runnable.run();else main.post(runnable);}
    private static Activity activity(Context context) {
        for(int i=0;i<20 && context!=null;i++) {
            if(context instanceof Activity)return(Activity)context;
            if(!(context instanceof ContextWrapper))break;
            Context next=((ContextWrapper)context).getBaseContext();if(next==context)break;context=next;
        }return null;
    }

    private final class Monitor implements ViewTreeObserver.OnGlobalLayoutListener,
            ViewTreeObserver.OnScrollChangedListener,ViewTreeObserver.OnWindowFocusChangeListener,
            ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener,AutoCloseable {
        final View root;final Activity owner;final LiquidGlassBackdrop backdrop;final LiquidGlassMotion motion;
        final HomeUiCustomization customization;
        final NavigationGlassClearance clearance;
        final Map<View,Binding> bindings=new LinkedHashMap<>();
        final Set<View> rejectedLayouts=Collections.newSetFromMap(new IdentityHashMap<>());
        final Map<View,Long> clearanceRejected=new IdentityHashMap<>();
        final ArrayList<View> navigation=new ArrayList<>();
        final ArrayList<View> motionCandidates=new ArrayList<>();
        int viewportWidth=-1,viewportHeight=-1;
        final Set<String> loggedTargets=new java.util.HashSet<>();
        final java.util.function.BiConsumer<Float,Float> lightCallback=(x,y)->{
            for(Binding binding:bindings.values())binding.glass.setLight(x,y);
        };
        ViewTreeObserver observer;boolean disposed,loggedOpaque,loggedBudget,scanScheduled;View source;
        String lastSummary="",lastMotionState="",lastClearanceGeometry="",lastClearanceScope="";
        long lastClearanceGeometryAt;
        long cancelledDraws;int drawCancelStreak;
        boolean clearanceGeometryScheduled;
        final Runnable reportClearanceGeometry=this::emitClearanceGeometry;
        final Runnable scan=()->{scanScheduled=false;refresh();};
        final Runnable capture=new Runnable(){@Override public void run(){
            if(disposed || source==null)return;
            if(root.isShown() && foregroundWindow(Monitor.this))backdrop.refresh();
            main.postDelayed(this,80);
        }};
        Monitor(View root,Activity owner){this.root=root;this.owner=owner;backdrop=new LiquidGlassBackdrop(root,log);
            motion=new LiquidGlassMotion(root.getContext());customization=new HomeUiCustomization(root,options,log,true);
            clearance=new NavigationGlassClearance(root,view->motion.restingBounds(view,root));}
        void attach() {
            root.addOnAttachStateChangeListener(this);observer=root.getViewTreeObserver();
            observer.addOnGlobalLayoutListener(this);observer.addOnScrollChangedListener(this);
            observer.addOnWindowFocusChangeListener(this);observer.addOnPreDrawListener(this);refresh();
        }
        @Override public void onGlobalLayout(){schedule();}
        @Override public void onScrollChanged(){schedule();}
        @Override public boolean onPreDraw() {
            if(disposed)return true;
            try {
                syncViewport();
                customization.sync();
                boolean changed=false,layoutChanged=false;
                for(java.util.Iterator<Map.Entry<View,Binding>> iterator=bindings.entrySet().iterator();iterator.hasNext();) {
                    Map.Entry<View,Binding> entry=iterator.next();
                    if(!LiquidGlassTargets.geometrySafe(entry.getKey(),root,LiquidGlassTargets.floating(entry.getKey()),
                            root==owner.getWindow().getDecorView())) {
                        View view=entry.getKey();
                        if(root.isLaidOut() && view.isLaidOut() && view.getWidth()>0 && view.getHeight()>0
                                && rejectedLayouts.add(view))log.accept("liquid_glass: navigation_layout_fallback=viewport_bounds");
                        entry.getValue().close();iterator.remove();changed=true;layoutChanged=true;
                    } else {
                        layoutChanged|=entry.getValue().refreshLayout(customization.labels(entry.getKey()),motionMargin());
                        entry.getValue().syncNavigationBackground();
                    }
                }
                layoutChanged|=syncClearance();
                if(changed)updateMotion();
                if(layoutChanged){cancelledDraws++;drawCancelStreak++;}else drawCancelStreak=0;
                return !layoutChanged;
            } catch(RuntimeException|LinkageError failure) {
                log.accept("liquid_glass: window_disabled="+failure.getClass().getSimpleName());close();updateMotion();
            }
            return true;
        }
        @Override public void onWindowFocusChanged(boolean focused) {
            main.removeCallbacks(capture);if(focused && source!=null)capture.run();updateMotion();
        }
        void schedule(){if(disposed || scanScheduled)return;scanScheduled=true;main.postDelayed(scan,100);}
        int motionMargin(){return settings.gravity?Math.round(10*root.getResources().getDisplayMetrics().density):0;}
        void syncViewport() {
            if(viewportWidth!=root.getWidth() || viewportHeight!=root.getHeight()) {
                viewportWidth=root.getWidth();viewportHeight=root.getHeight();rejectedLayouts.clear();clearanceRejected.clear();
            }
        }
        boolean syncClearance() {
            clearance.setProgressMotionMargin(motionMargin());
            boolean changed=clearance.sync(navigation);
            Set<View> blocked=clearance.blockedViews();
            long revision=clearance.inputRevision();
            if(!changed && clearance.retryReady())for(java.util.Iterator<Map.Entry<View,Long>> iterator=clearanceRejected.entrySet().iterator();iterator.hasNext();) {
                Map.Entry<View,Long> rejected=iterator.next();
                if(rejected.getValue()!=revision && !blocked.contains(rejected.getKey())){iterator.remove();schedule();}
            }
            boolean restored=false;
            for(View view:blocked) {
                Binding binding=bindings.get(view);
                if(binding!=null && !binding.clearanceFallback) {
                    binding.layout.close();binding.clearanceFallback=true;
                    clearanceRejected.put(view,revision);restored=true;changed=true;
                    log.accept("liquid_glass: navigation_clearance_fallback=native_geometry;"+clearance.fallbackDetail());
                }
            }
            if(restored){changed|=clearance.sync(navigation);updateMotion();}
            scheduleClearanceGeometry();
            return changed;
        }
        void scheduleClearanceGeometry() {
            if(disposed || clearance.windowProgressCount()==0)return;
            long elapsed=android.os.SystemClock.uptimeMillis()-lastClearanceGeometryAt;
            if(elapsed>=1000) {
                main.removeCallbacks(reportClearanceGeometry);reportClearanceGeometry.run();
            } else if(!clearanceGeometryScheduled) {
                // Preserve the last sample even when drawing stops inside the one-second throttle interval.
                clearanceGeometryScheduled=true;main.postDelayed(reportClearanceGeometry,1000-elapsed);
            }
        }
        void emitClearanceGeometry() {
            clearanceGeometryScheduled=false;
            if(disposed || clearance.windowProgressCount()==0)return;
            lastClearanceGeometryAt=android.os.SystemClock.uptimeMillis();
            try {
                String geometry="draw_cancelled_total="+cancelledDraws+";draw_cancel_streak="+drawCancelStreak
                        +";"+clearance.geometrySummary(navigation);
                if(!geometry.equals(lastClearanceGeometry)) {
                    lastClearanceGeometry=geometry;log.accept("liquid_glass: progress_geometry;"+geometry);
                }
                String scope=clearance.scopeDiagnostic();
                if(!scope.equals(lastClearanceScope)) {
                    lastClearanceScope=scope;log.accept("liquid_glass: progress_layout_scope;"+scope);
                }
            } catch(RuntimeException|LinkageError ignored) { /* Diagnostics must not fail a rebuilding host window. */ }
        }
        void refresh() {
            if(disposed || !settings.enabled)return;
            try {
                syncViewport();
                customization.refresh();
                java.util.List<View> nodes=LiquidGlassTargets.nodes(root);
                motionCandidates.clear();motionCandidates.addAll(LiquidGlassTargets.motionTargets(root));
                navigation.clear();
                for(View node:nodes)if(node!=root && LiquidGlassTargets.floating(node))navigation.add(node);
                clearance.discover(nodes);syncClearance();
                LiquidGlassTargets.Selection selection=LiquidGlassTargets.select(root,root==owner.getWindow().getDecorView());
                Map<View,Boolean> targets=selection.targets;
                if(!loggedBudget && (selection.treeLimited || selection.targetLimited)) {
                    loggedBudget=true;log.accept("liquid_glass: coverage_limited="+(selection.treeLimited?"tree_budget":"surface_budget"));
                }
                for(View old:new ArrayList<>(bindings.keySet())) {
                    Binding binding=bindings.get(old);
                    if(!targets.containsKey(old) || !binding.owned()
                            || binding.clearanceFallback && !clearanceRejected.containsKey(old)) {
                        binding.close();bindings.remove(old);
                    }
                }
                for(Map.Entry<View,Boolean> target:targets.entrySet())if(!bindings.containsKey(target.getKey())
                        && !rejectedLayouts.contains(target.getKey()) && !clearanceRejected.containsKey(target.getKey())) {
                    bindings.put(target.getKey(),new Binding(target.getKey(),target.getValue(),backdrop));
                    View view=target.getKey();String observation="class="+view.getClass().getName()
                            +";id="+Integer.toHexString(view.getId())+";size="+view.getWidth()+"x"+view.getHeight()
                            +";floating="+target.getValue();
                    if(loggedTargets.size()<32 && loggedTargets.add(observation))log.accept(observation);
                }
                for(Binding binding:bindings.values())binding.refreshLayout(customization.labels(binding.view),motionMargin());
                syncClearance();
                View next=null;long area=0;
                android.graphics.Rect visible=new android.graphics.Rect();
                for(View node:bindings.isEmpty()?Collections.<View>emptyList():nodes) {
                    if(LiquidGlassTargets.opaque(node) && !loggedOpaque){
                        log.accept("liquid_glass: opaque_renderer_compatibility_only="+node.getClass().getName());loggedOpaque=true;
                    }
                    if(LiquidGlassTargets.isMedia(node) && node.isShown() && node.getGlobalVisibleRect(visible)) {
                        long candidate=(long)visible.width()*visible.height();
                        if(candidate>area){next=node;area=candidate;}
                    }
                }
                if(next==null && !bindings.isEmpty() && root!=owner.getWindow().getDecorView()) {
                    Monitor underlying=windows.get(owner.getWindow().getDecorView());
                    if(underlying!=null && !underlying.disposed)next=underlying.source;
                }
                if(next!=source){main.removeCallbacks(capture);source=next;backdrop.setSource(source);if(source!=null)capture.run();
                    if(root==owner.getWindow().getDecorView())for(Monitor peer:windows.values()) {
                        if(peer!=this && peer.owner==owner)peer.schedule();
                    }
                }
                String summary="window="+root.getClass().getSimpleName()+";size="+root.getWidth()+"x"+root.getHeight()
                        +";surfaces="+bindings.size()+";source="+(source==null?"none":source.getClass().getSimpleName())
                        +";page_progress="+clearance.windowProgressCount();
                if(!summary.equals(lastSummary)){lastSummary=summary;log.accept(summary);}
                updateMotion();
            } catch(RuntimeException|LinkageError failure){
                log.accept("liquid_glass: window_disabled="+failure.getClass().getSimpleName());close();updateMotion();
            }
        }
        @Override public void onViewAttachedToWindow(View view){observer=root.getViewTreeObserver();schedule();}
        @Override public void onViewDetachedFromWindow(View view){close();known.remove(root);updateMotion();}
        @Override public void close() {
            if(disposed)return;disposed=true;main.removeCallbacks(scan);main.removeCallbacks(capture);main.removeCallbacks(reportClearanceGeometry);
            scanScheduled=false;clearanceGeometryScheduled=false;
            root.removeOnAttachStateChangeListener(this);
            ViewTreeObserver live=observer!=null && observer.isAlive()?observer:root.getViewTreeObserver();
            if(live.isAlive()){live.removeOnGlobalLayoutListener(this);live.removeOnScrollChangedListener(this);
                live.removeOnWindowFocusChangeListener(this);live.removeOnPreDrawListener(this);}
            motion.close();clearance.close();for(Binding binding:bindings.values())binding.close();bindings.clear();
            navigation.clear();motionCandidates.clear();rejectedLayouts.clear();clearanceRejected.clear();customization.close();backdrop.close();windows.remove(root);
        }
    }

    static final class Binding implements AutoCloseable {
        final View view;final Drawable original,applied;final LiquidGlassDrawable glass;
        final int left,top,right,bottom;final NavigationBackground navigationBackground;
        final NavigationGlassLayout layout;
        boolean clearanceFallback;
        @android.annotation.SuppressLint("ResourceType") // ID verified in the original 41.0.0 APK, not module R.
        Binding(View view,boolean floating,LiquidGlassBackdrop backdrop) {
            this.view=view;original=view.getBackground();left=view.getPaddingLeft();top=view.getPaddingTop();
            right=view.getPaddingRight();bottom=view.getPaddingBottom();
            glass=new LiquidGlassDrawable(view,floating,backdrop);Drawable surface=glass;
            if(original instanceof RippleDrawable && original.getConstantState()!=null) {
                RippleDrawable ripple=(RippleDrawable)original.getConstantState().newDrawable(view.getResources()).mutate();
                boolean replaced=false;
                for(int i=0;i<ripple.getNumberOfLayers();i++)if(ripple.getId(i)!=android.R.id.mask) {
                    ripple.setDrawable(i,surface);replaced=true;break;
                }
                surface=replaced?ripple:new LayerDrawable(new Drawable[]{surface,original});
            } else if(original!=null && original.isStateful())surface=new LayerDrawable(new Drawable[]{surface,original});
            applied=surface;view.setBackground(applied);view.setPadding(left,top,right,bottom);
            layout=new NavigationGlassLayout(view,LiquidGlassTargets.hasClass(view,
                    "com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar"));
            View background=floating && LiquidGlassTargets.hasClass(view,"X.1JgG")?view.findViewById(0x7f0a1435):null;
            navigationBackground=background instanceof ImageView?new NavigationBackground(background):null;
            syncNavigationBackground();
        }
        boolean owned(){return view.getBackground()==applied;}
        boolean refreshLayout(java.util.Collection<android.widget.TextView> labels){return layout.refresh(labels);}
        boolean refreshLayout(java.util.Collection<android.widget.TextView> labels,int edge){return layout.refresh(labels,edge);}
        void syncNavigationBackground() {
            if(navigationBackground!=null && owned())navigationBackground.sync();
        }
        private static void setBackgroundPreservingPadding(View view,Drawable background) {
            int l=view.getPaddingLeft(),t=view.getPaddingTop(),r=view.getPaddingRight(),b=view.getPaddingBottom();
            view.setBackground(background);view.setPadding(l,t,r,b);
        }
        @Override public void close() {
            layout.close();
            if(owned()) {
                int l=view.getPaddingLeft(),t=view.getPaddingTop(),r=view.getPaddingRight(),b=view.getPaddingBottom();
                view.setBackground(original);view.setPadding(l,t,r,b);
            }
            if(navigationBackground!=null)navigationBackground.close();
            glass.close();
        }
    }
    static final class NavigationBackground implements AutoCloseable {
        final View view;final Drawable marker=new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT);
        Drawable original;
        NavigationBackground(View view){this.view=view;sync();}
        void sync(){if(view.getBackground()==marker)return;original=view.getBackground();Binding.setBackgroundPreservingPadding(view,marker);}
        @Override public void close(){if(view.getBackground()==marker)Binding.setBackgroundPreservingPadding(view,original);}
    }
    @Override public void onActivityCreated(Activity activity,Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state) { }
}
