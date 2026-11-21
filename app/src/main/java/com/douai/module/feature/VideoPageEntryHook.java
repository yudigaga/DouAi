package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.*;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** A bounded deferral of the native cover observer, never an overlay. */
public final class VideoPageEntryHook implements AutoCloseable, Application.ActivityLifecycleCallbacks {
    private final BehaviorHookGroup group;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Binding> bindings = new WeakHashMap<>();
    private final Map<Object, Load> loads = new WeakHashMap<>();
    private final Set<Application> applications = Collections.newSetFromMap(new WeakHashMap<>());
    private final Method current, aweme, hide, userService;
    private final Class<?> detail;
    private volatile FeedScope feed;
    private Binding pendingFeed;
    private volatile boolean enabled, closed;
    private boolean replaying;

    private VideoPageEntryHook(boolean enabled, Consumer<String> log, Method current,
                               Method aweme, Method hide, Method userService, Class<?> detail) {
        this.enabled=enabled; this.current=current; this.aweme=aweme; this.hide=hide;
        this.userService=userService; this.detail=detail;
        group=new BehaviorHookGroup("hide_video_black_edges.entry",log);
    }

    public static VideoPageEntryHook install(XposedInterface framework, HostScanReport report,
            ClassLoader loader, boolean enabled, Consumer<String> log) throws Exception {
        if(HostAdaptation.currentProfile()!=HostAdaptation.PROFILE_410)
            throw new IllegalStateException("Entry cover requires verified 41 profile");
        Class<?> presenter=loader.loadClass("com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter");
        Class<?> observer=loader.loadClass("com.ss.android.ugc.aweme.feed.quick.presenter.FeedCoverPresenter$observeVM$5");
        Class<?> listener=hostType(loader,"X.0HLd");
        Method bind=presenter.getDeclaredMethod("onBindSafe",loader.loadClass("com.ss.android.ugc.aweme.kiwi.model.QModel"));
        Method unbind=presenter.getDeclaredMethod("mustUnbind");
        Method hide=observer.getDeclaredMethod("onChanged",Object.class);
        Method selected=HostTargets.method(report,"feed_holder_page_selected",loader);
        Method resume=HostTargets.method(report,"feed_holder_resume",loader);
        Method pause=HostTargets.method(report,"feed_holder_pause",loader);
        Method current=HostTargets.method(report,"auto_slide_current_holder",loader);
        Method work=HostTargets.method(report,"feed_aweme_accessor",loader);
        Method frame=HostTargets.method(report,"component_player_playing",loader).getDeclaringClass()
                .getDeclaredMethod("onRenderFirstFrame",String.class,hostType(loader,"X.0Gf4"));
        Method complete=listener.getDeclaredMethod("onComplete",android.net.Uri.class,View.class,
                loader.loadClass("com.bytedance.lighten.core.ImageInfo"),android.graphics.drawable.Animatable.class);
        Method failed=listener.getDeclaredMethod("onFailed",android.net.Uri.class,View.class,Throwable.class);
        Method account=loader.loadClass("com.ss.android.ugc.aweme.account.AccountProxyService").getMethod("userService");
        // Resolve every contract before registering anything. A mismatch disables only this group.
        HostTargets.field(presenter,"mCoverView"); HostTargets.field(presenter,"mAweme");
        HostTargets.field(presenter,"videoItemParams"); HostTargets.field(observer,"a");
        HostTargets.field(selected.getDeclaringClass(),"B2"); HostTargets.field(selected.getDeclaringClass(),"w");
        VideoPageEntryHook h=new VideoPageEntryHook(enabled,log==null?s->{}:log,current,work,hide,account,
                loader.loadClass("com.ss.android.ugc.aweme.detail.panel.DetailFragmentPanel"));
        try {
            h.group.add(framework,bind,c->{
                Object p=c.getThisObject();
                if(onMain()) { h.cancel(h.bindings.remove(p)); if(h.enabled&&!h.closed)h.bindings.put(p,h.new Binding(p)); }
                Object result=c.proceed();
                if(onMain())h.capture(h.bindings.get(p));
                return result;
            });
            for(Method end:new Method[]{unbind,presenter.getDeclaredMethod("unBindSafe")})
                h.group.add(framework,end,c->{if(onMain())h.cancel(h.bindings.remove(c.getThisObject()));return c.proceed();});
            h.group.add(framework,listener.getDeclaredConstructor(presenter,WeakReference.class,hostType(loader,"X.0HLW")),c->{
                Object result=c.proceed();
                if(onMain()) {
                    Binding b=h.bindings.get(c.getArg(0)); h.capture(b);
                    Object ref=c.getArg(1);
                    if(b!=null&&h.same(b)&&ref instanceof WeakReference && ((WeakReference<?>)ref).get()==b.cover.get())
                        h.loads.put(c.getThisObject(),new Load(b));
                }
                return result;
            });
            h.group.add(framework,complete,c->{
                Load load=onMain()?h.loads.get(c.getThisObject()):null;
                Object result=c.proceed();
                if(load!=null&&h.same(load.binding)&&load.cover.get()==c.getArg(1)
                        &&load.cover.get()==load.binding.cover.get())
                    load.binding.loaded=!load.binding.feed||h.feed.nativeLoaded(load.binding);
                return result;
            });
            h.group.add(framework,failed,c->{
                Load load=onMain()?h.loads.remove(c.getThisObject()):null;
                if(load!=null){load.binding.loaded=false;h.release(load.binding,"load_failed");}
                return c.proceed();
            });
            h.group.add(framework,hide,c->{
                if(!onMain()||h.replaying)return c.proceed();
                Binding b=h.bindings.get(HostTargets.read(c.getThisObject(),"a"));
                if(playbackHideReason(c.getArg(0))&&h.canHold(b)) {
                    if(b.observer.get()==null) {
                        b.observer=new WeakReference<>(c.getThisObject()); b.reason=c.getArg(0);
                        b.leaseUntil=SystemClock.uptimeMillis()+600;
                        b.timeout=()->h.release(b,"timeout"); h.main.postDelayed(b.timeout,600);
                        if(b.feed)h.monitorFeedLease(b);
                        h.event(b,"deferred");
                    }
                    return null;
                }
                // This invocation supersedes any pending observer and must not be replayed again.
                if(b!=null)h.cancel(b);
                return c.proceed();
            });
            for(Method target:new Method[]{selected,resume})h.group.add(framework,target,c->{
                Object result=c.proceed();
                if(onMain())for(Binding b:h.snapshot()){
                    if(b.feed){
                        if(b==h.pendingFeed&&(!h.owns(b)||!h.feed.visible(b)))h.release(b,"selection_changed");
                    }else if(b.holder.get()!=null&&!h.owns(b))h.release(b,"selection_changed");
                }
                return result;
            });
            h.group.add(framework,pause,c->{
                if(onMain())for(Binding b:h.snapshot())if((!b.feed||b==h.pendingFeed)&&(b.holder.get()==c.getThisObject()
                        ||b.params.get()!=null&&b.params.get()==HostTargets.read(c.getThisObject(),"B2")))h.release(b,"pause");
                return c.proceed();
            });
            h.group.add(framework,frame,c->{
                List<Binding> before=onMain()?h.snapshot():new ArrayList<>();
                Object dispatcher=c.getThisObject(), aid=c.getArg(0);
                // A same-binding frame before selection conservatively prevents a later
                // lease. Only a binding owned before and after dispatch may actively
                // release a pending lease, including a hide emitted inside proceed().
                before.removeIf(b->!h.matchesFrame(b,aid,dispatcher));
                List<Binding> ownedBefore=new ArrayList<>();
                for(Binding b:before)if(h.owns(b)&&(!b.feed||h.feed.selected(b)))ownedBefore.add(b);
                Object result=c.proceed();
                for(Binding b:before)if(h.matchesFrame(b,aid,dispatcher)) {
                    b.rendered=true;
                    View cover=b.cover.get();
                    if(b.observer.get()!=null&&cover!=null&&b.animation==null
                            &&ownedBefore.contains(b)&&h.owns(b)&&(!b.feed||h.feed.selected(b))) {
                        // The first callback runs before drawing. Release on the following
                        // animation turn so the player has had one drawing opportunity.
                        b.animation=()->{
                            if(!h.same(b)||b.finished)return;
                            b.animation=()->h.release(b,b.feed&&!h.feed.visible(b)?"handoff_invalidated":"first_frame");
                            cover.postOnAnimation(b.animation);
                        };
                        cover.postOnAnimation(b.animation);
                    }
                }
                return result;
            });
            h.installFeed(framework,report,loader,selected,log==null?s->{}:log);
            return h;
        } catch(Exception|LinkageError failure){h.close();throw failure;}
    }

    // Obfuscated DEX names are valid at runtime but not Java PSI type names in Lint.
    private static Class<?> hostType(ClassLoader loader,String name) throws ClassNotFoundException {
        return loader.loadClass(name);
    }
    private static boolean onMain(){return Looper.myLooper()==Looper.getMainLooper();}
    private List<Binding> snapshot(){return new ArrayList<>(bindings.values());}
    private String account(){try{Object uid=HostTargets.invoke(userService.invoke(null),"getCurUserId");return uid instanceof String?(String)uid:null;}catch(Exception ignored){return null;}}

    private void installFeed(XposedInterface api,HostScanReport report,ClassLoader loader,Method selected,Consumer<String> log){
        BehaviorHookGroup optional=new BehaviorHookGroup("hide_video_black_edges.manual_swipe",log);
        try {
            Class<?> holder=selected.getDeclaringClass();
            Method bind=HostTargets.method(report,"feed_item_bind_aweme",loader);
            Method positioned=HostTargets.method(report,"feed_item_bind_aweme_position",loader);
            Method touch=HostTargets.method(report,"auto_slide_pager_touch",loader);
            Method scroll=HostTargets.method(report,"auto_slide_scroll_state",loader);
            Method comments=HostTargets.method(report,"auto_slide_comments_showing",loader);
            Field pager=HostTargets.field(current.getDeclaringClass(),"f");
            Field panelCover=HostTargets.field(current.getDeclaringClass(),"h");
            Field dialogs=HostTargets.field(current.getDeclaringClass(),"L0");
            Field paramsField=HostTargets.field(holder,"B2");
            Method event=paramsField.getType().getMethod("getEventType");
            Method holderType=paramsField.getType().getMethod("getViewHolderType");
            Method player=holder.getMethod("getFeedPlayerView"), render=player.getReturnType().getMethod("getVideoView");
            Class<?> cover=loader.loadClass("com.ss.android.ugc.aweme.feed.widget.CoverImageView");
            if(bind.getDeclaringClass()!=holder || positioned.getDeclaringClass()!=holder
                    || bind.getReturnType()!=void.class || positioned.getReturnType()!=void.class
                    || !Arrays.equals(bind.getParameterTypes(),new Class<?>[]{aweme.getReturnType()})
                    || !Arrays.equals(positioned.getParameterTypes(),new Class<?>[]{aweme.getReturnType(),int.class})
                    || touch.getDeclaringClass()!=pager.getType() || scroll.getDeclaringClass()!=pager.getType()
                    || !View.class.isAssignableFrom(pager.getType()) || !View.class.isAssignableFrom(panelCover.getType())
                    || touch.getReturnType()!=boolean.class
                    || !Arrays.equals(touch.getParameterTypes(),new Class<?>[]{MotionEvent.class})
                    || scroll.getReturnType()!=int.class || scroll.getParameterCount()!=0
                    || dialogs.getType()!=comments.getDeclaringClass() || comments.getReturnType()!=boolean.class
                    || !Arrays.equals(comments.getParameterTypes(),new Class<?>[]{boolean.class})
                    || HostTargets.field(paramsField.getType(),"isVideoCoverLoaded").getType()!=boolean.class
                    || event.getReturnType()!=String.class || holderType.getReturnType()!=int.class
                    || render.getReturnType()!=View.class
                    || !"com.ss.android.ugc.aweme.feed.adapter.IFeedPlayerView".equals(player.getReturnType().getName())
                    || !View.class.isAssignableFrom(cover))throw new IllegalStateException("Unexpected manual cover contract");
            FeedScope next=new FeedScope(optional,pager,panelCover,dialogs,event,holderType,scroll,comments,player,render,cover);
            for(Method method:new Method[]{bind,positioned})optional.add(api,method,c->{
                Object owner=c.getThisObject();
                if(onMain()&&enabled&&!closed)next.rebind(owner);
                Object result=c.proceed();
                if(onMain()&&enabled&&!closed)next.remember(owner);
                return result;
            });
            optional.add(api,touch,c->{
                if(!onMain()||!enabled||closed||!(c.getThisObject() instanceof View)||!(c.getArg(0) instanceof MotionEvent))return c.proceed();
                View view=(View)c.getThisObject();
                int action=((MotionEvent)c.getArg(0)).getActionMasked();
                next.beforeTouch(view,action);
                return next.dispatchTouch(c,view,action);
            });
            feed=next;
        }catch(Exception|LinkageError unavailable){optional.fail(unavailable);}
    }

    private void monitorFeedLease(Binding b) {
        pendingFeed=b;
        View cover=b.cover.get();
        if(cover==null)return;
        ViewTreeObserver tree=cover.getViewTreeObserver();
        if(!tree.isAlive())return;
        b.feedObserver=tree;
        b.feedDraw=()->{if(pendingFeed==b&&!feed.visible(b))release(b,"swipe_left");return true;};
        tree.addOnPreDrawListener(b.feedDraw);
        tree.addOnWindowFocusChangeListener(b);
    }

    private final class FeedScope {
        private static final int MAX_BOUND_CARDS=4;
        final BehaviorHookGroup hooks;
        final Field pager,panelCover,dialogs;
        final Method event,holderType,scroll,comments,player,render;
        final Class<?> cover;
        final ArrayDeque<WeakReference<Object>> recent=new ArrayDeque<>();
        final Rect visibleCard=new Rect(),visiblePager=new Rect(),visibleCover=new Rect();
        WeakReference<View> touchPager=new WeakReference<>(null);
        WeakReference<Object> source=new WeakReference<>(null),sourcePanel=new WeakReference<>(null);
        long swipeUntil;
        View movingPager;

        FeedScope(BehaviorHookGroup hooks,Field pager,Field panelCover,Field dialogs,Method event,Method holderType,
                  Method scroll,Method comments,Method player,Method render,Class<?> cover){
            this.hooks=hooks;this.pager=pager;this.panelCover=panelCover;this.dialogs=dialogs;this.event=event;
            this.holderType=holderType;this.scroll=scroll;this.comments=comments;this.player=player;this.render=render;this.cover=cover;
        }
        boolean accepts(Object params,Object panel,Object image) {
            if(params==null||!current.getDeclaringClass().isInstance(panel)||detail.isInstance(panel)||!cover.isInstance(image))return false;
            try{return "homepage_hot".equals(event.invoke(params))&&!Integer.valueOf(18).equals(holderType.invoke(params));}
            catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return false;}
        }
        void remember(Object holder) {
            recent.removeIf(ref->ref.get()==null||ref.get()==holder);
            recent.addFirst(new WeakReference<>(holder));
            if(recent.size()>MAX_BOUND_CARDS)recent.removeLast();
        }
        void rebind(Object holder) {
            Object params=HostTargets.read(holder,"B2");
            for(Binding b:snapshot())if(b.feed&&(b.holder.get()==holder||params!=null&&b.params.get()==params))cancel(b);
        }
        Object findHolder(Binding b) {
            try {
                for(WeakReference<Object> ref:recent) {
                    Object holder=ref.get();
                    if(holder!=null&&HostTargets.read(holder,"B2")==b.params.get()&&aweme.invoke(holder)==b.work.get())return holder;
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){/* Recycled card. */}
            return null;
        }
        void clear(){recent.clear();resetSwipe();}
        void resetSwipe(){touchPager.clear();source.clear();sourcePanel.clear();swipeUntil=0;}
        void beforeTouch(View view,int action) {
            if(action==MotionEvent.ACTION_DOWN || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN){
                if(action!=MotionEvent.ACTION_DOWN && touchPager.get()!=view)return;
                if(pendingFeed!=null)release(pendingFeed,"swipe_cancelled");
                resetSwipe();
                if(action==MotionEvent.ACTION_DOWN)captureSwipeSource(view);
            }else if(action==MotionEvent.ACTION_UP&&touchPager.get()==view&&swipeUntil!=0){
                swipeUntil=SystemClock.uptimeMillis()+600;
            }else if(action==MotionEvent.ACTION_UP&&touchPager.get()==view){
                resetSwipe();
            }
        }
        private void captureSwipeSource(View view) {
            try {
                Object owner=null;
                for(WeakReference<Object> ref:recent){
                    Object params=HostTargets.read(ref.get(),"B2"),panel=HostTargets.read(params,"mFeedContext");
                    if(params==null||!current.getDeclaringClass().isInstance(panel)||detail.isInstance(panel)
                            ||pager.get(panel)!=view||!"homepage_hot".equals(event.invoke(params)))continue;
                    if(owner!=null&&owner!=panel)return;
                    owner=panel;
                }
                if(owner==null)return;
                Object holder=current.invoke(owner);
                if(holder!=null){
                    touchPager=new WeakReference<>(view);source=new WeakReference<>(holder);
                    sourcePanel=new WeakReference<>(owner);
                }
            }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){resetSwipe();}
        }
        Object dispatchTouch(XposedInterface.Chain chain,View view,int action) throws Throwable {
            if(action!=MotionEvent.ACTION_MOVE||touchPager.get()!=view)return chain.proceed();
            View previousMove=movingPager;
            movingPager=view;
            try {
                Object result=chain.proceed();
                if(touchPager.get()==view&&source.get()!=null&&isDragging(view))swipeUntil=SystemClock.uptimeMillis()+600;
                return result;
            } finally {
                movingPager=previousMove;
            }
        }
        private boolean isDragging(View view) {
            try {
                return Integer.valueOf(1).equals(scroll.invoke(view));
            }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){
                return false;
            }
        }
        boolean selected(Binding b) {
            try{return b.holder.get()!=null&&current.invoke(b.panel.get())==b.holder.get();}
            catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return false;}
        }
        boolean nativeLoaded(Binding b){return Boolean.TRUE.equals(HostTargets.read(b.params.get(),"isVideoCoverLoaded"));}
        boolean sameHolder(Binding b) {
            Object holder=b.holder.get(),root=b.root.get(),panel=b.panel.get();
            try {
                return holder!=null&&root!=null&&panel!=null&&HostTargets.read(holder,"B2")==b.params.get()
                        &&HostTargets.read(holder,"w")==root&&HostTargets.read(b.params.get(),"mFeedContext")==panel
                        &&aweme.invoke(holder)==b.work.get();
            }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return false;}
        }
        boolean reject(Binding b,String reason) {
            if(!DebugLog.isEnabled()||b.gateReported||b==pendingFeed||b.observer.get()!=null)return false;
            View view=touchPager.get();
            Object origin=source.get();
            if(view==null||origin==null||b.holder.get()==origin||sourcePanel.get()!=b.panel.get())return false;
            long now=SystemClock.uptimeMillis();
            if((swipeUntil==0||now>swipeUntil)&&(movingPager!=view||!isDragging(view)))return false;
            try {
                if(HostTargets.read(origin,"B2")==b.params.get())return false;
                b.gateReported=true;
                DouAiLog.event("hide_video_black_edges","manual_swipe_cover_gate",reason,
                        "work="+DouAiLog.reference(b.aid)+";native_hide=true");
            }catch(RuntimeException|LinkageError ignored){/* Diagnostics must not affect native hide. */}
            return false;
        }
        boolean visible(Binding b) {
            View view=touchPager.get(),root=b.root.get(),image=b.cover.get();
            Object holder=b.holder.get(),origin=source.get(),panel=b.panel.get();
            long now=SystemClock.uptimeMillis();
            boolean firstMove=view!=null&&movingPager==view&&isDragging(view);
            if(view==null||root==null||image==null||holder==null||origin==null||origin==holder
                    ||panel==null||sourcePanel.get()!=panel||(!firstMove&&(swipeUntil==0||now>swipeUntil))
                    ||!root.isShown()||!root.hasWindowFocus()
                    ||!image.isShown()||image.getAlpha()<=0||root.getWindowToken()==null
                    ||root.getWindowToken()!=b.window.get())return reject(b,"swipe_or_window_ineligible");
            try {
                if(panel==null||pager.get(panel)!=view||!inside(root,view)||!inside(image,root)
                        || !view.getGlobalVisibleRect(visiblePager)||!root.getGlobalVisibleRect(visibleCard)
                        || !image.getGlobalVisibleRect(visibleCover)||!Rect.intersects(visiblePager,visibleCard)
                        || !Rect.intersects(visiblePager,visibleCover))return reject(b,"outside_pager_viewport");
                Object live=current.invoke(panel);
                if(live!=holder&&(live!=origin||Integer.valueOf(0).equals(scroll.invoke(view))))return reject(b,"selection_changed");
                Object dialog=dialogs.get(panel);
                View overlay=(View)panelCover.get(panel);
                // The same native observer can also remove a panel-level image. Leave that path native.
                if(overlay!=null&&overlay.getVisibility()!=View.GONE)return reject(b,"panel_overlay_active");
                if(dialog==null||!Boolean.FALSE.equals(comments.invoke(dialog,true)))return reject(b,"comments_or_unknown_dialog");
                if(!sameHolder(b))return reject(b,"binding_mismatch");
                if(!nativeLoaded(b))return reject(b,"native_cover_not_loaded");
                if(Boolean.TRUE.equals(HostTargets.read(b.params.get(),"forceDismissCover")))return reject(b,"forced_native_hide");
                Object owner=player.invoke(holder);
                View plane=owner==null?null:(View)render.invoke(owner);
                if(plane==null)return reject(b,"renderer_missing");
                boolean acquiring=b.observer.get()==null;
                if(!acquiring&&b.leasedRenderer.get()!=plane)return reject(b,"renderer_changed");
                if(!FeedCoverGeometry.matches(root,image,plane))return reject(b,"footprint_mismatch");
                if(acquiring)b.leasedRenderer=new WeakReference<>(plane);
                if(firstMove&&swipeUntil==0)swipeUntil=now+600;
                return true;
            }catch(ReflectiveOperationException|RuntimeException|LinkageError invalid){return reject(b,"host_read_unavailable");}
        }
    }

    private void capture(Binding b) {
        if(b==null||b.cover.get()!=null)return;
        Object p=b.presenter.get(),params=HostTargets.read(p,"videoItemParams"),work=HostTargets.read(p,"mAweme");
        Object view=HostTargets.read(p,"mCoverView"),panel=HostTargets.read(params,"mFeedContext");
        if(!(view instanceof View))return;
        boolean manual=!detail.isInstance(panel);
        if(manual&&(feed==null||!feed.accepts(params,panel,view)))return;
        Object aid=HostTargets.invoke(work,"getAid"); String uid=account();
        if(!(aid instanceof String)||((String)aid).isEmpty()||uid==null||uid.isEmpty())return;
        b.params=new WeakReference<>(params);b.cover=new WeakReference<>((View)view);
        b.work=new WeakReference<>(work);b.panel=new WeakReference<>(panel);b.aid=(String)aid;b.account=uid;b.feed=manual;
        ((View)view).addOnAttachStateChangeListener(b);
        if(!manual)((View)view).getViewTreeObserver().addOnWindowFocusChangeListener(b);
        resolveOwner(b);
    }
    private void resolveOwner(Binding b) {
        if(b==null||b.observer.get()!=null)return;
        try {Object holder=b.feed?feed.findHolder(b):current.invoke(b.panel.get());
            if(holder==null||HostTargets.read(holder,"B2")!=b.params.get())return;
            b.holder=new WeakReference<>(holder);Object root=HostTargets.read(holder,"w");
            if(!(root instanceof View))return;b.root=new WeakReference<>((View)root);
            Activity activity=activity(((View)root).getContext());if(activity==null)return;
            b.activity=new WeakReference<>(activity);b.window=new WeakReference<>(((View)root).getWindowToken());
            Application app=activity.getApplication();if(applications.add(app))app.registerActivityLifecycleCallbacks(this);
        }catch(Exception ignored) { }
    }
    private boolean same(Binding b) {
        if(b==null)return false;Object p=b.presenter.get();View cover=b.cover.get();
        return p!=null&&bindings.get(p)==b&&cover!=null&&HostTargets.read(p,"mCoverView")==cover
                &&b.params.get()!=null&&HostTargets.read(p,"videoItemParams")==b.params.get()
                &&b.work.get()!=null&&HostTargets.read(p,"mAweme")==b.work.get()
                &&Objects.equals(b.aid,HostTargets.invoke(b.work.get(),"getAid"))
                &&b.account!=null&&b.account.equals(account());
    }
    private boolean owns(Binding b) {
        if(!same(b))return false;
        if(b.holder.get()==null||b.window.get()==null)resolveOwner(b);
        Object panel=b.panel.get(),holder=b.holder.get();View root=b.root.get(),cover=b.cover.get();
        Activity activity=b.activity.get();
        try{return holder!=null&&panel!=null&&(b.feed?feed.accepts(b.params.get(),panel,cover):detail.isInstance(panel)&&current.invoke(panel)==holder)
                &&HostTargets.read(holder,"B2")==b.params.get()&&HostTargets.read(b.params.get(),"mFeedContext")==panel
                &&HostTargets.read(holder,"w")==root&&aweme.invoke(holder)==b.work.get()
                &&root!=null&&cover!=null&&inside(cover,root)&&root.isAttachedToWindow()&&cover.isAttachedToWindow()
                &&root.getWindowToken()!=null&&root.getWindowToken()==b.window.get()
                &&activity!=null&&activity(root.getContext())==activity&&!activity.isFinishing()&&!activity.isDestroyed();
        }catch(Exception ignored){return false;}
    }
    private boolean canHold(Binding b) {
        if(!enabled||closed||b==null)return false;
        if(b.finished)return b.feed&&feed.reject(b,"binding_finished");
        if(!b.loaded)return b.feed&&feed.reject(b,"cover_load_unobserved");
        if(!owns(b))return b.feed&&feed.reject(b,"binding_unavailable");
        if(b.feed){
            if(pendingFeed!=null&&pendingFeed!=b)return feed.reject(b,"other_cover_pending");
            if(!feed.visible(b))return false;
        }
        long now=SystemClock.uptimeMillis();
        if(b.observer.get()==null) {
            if(b.rendered)return b.feed&&feed.reject(b,"renderer_already_signalled");
            if(!b.feed&&now-b.boundAt>1500)return false;
        } else if(now>=b.leaseUntil)return false;
        View cover=b.cover.get(),root=b.root.get();Object work=b.work.get();
        return root.hasWindowFocus()&&root.isShown()&&root.getAlpha()>0&&cover.isShown()&&cover.getAlpha()>0
                &&cover.getWidth()>0&&cover.getHeight()>0
                &&cover.getVisibility()==View.VISIBLE&&!Boolean.TRUE.equals(HostTargets.read(b.params.get(),"forceDismissCover"))
                &&Integer.valueOf(0).equals(HostTargets.invoke(work,"getAwemeType"))
                &&Boolean.FALSE.equals(HostTargets.invoke(work,"isLive"))&&Boolean.FALSE.equals(HostTargets.invoke(work,"getAd"));
    }
    private static boolean playbackHideReason(Object reason) {
        return "HideCover when Playing".equals(reason)||"HideCover when PreRender".equals(reason)
                ||"HideCover when Progress".equals(reason);
    }
    private boolean matchesFrame(Binding b,Object aid,Object dispatcher) {
        return Objects.equals(b.aid,aid)&&same(b)&&(b.feed||detail.isInstance(b.panel.get()))
                &&HostTargets.read(b.params.get(),"mFeedContext")==b.panel.get()
                &&dispatcher!=null&&dispatcher(b)==dispatcher;
    }
    private Object dispatcher(Binding b){return HostTargets.read(HostTargets.read(b.panel.get(),"V"),"m");}
    private static boolean inside(View view,View root){for(View v=view;v!=null;){if(v==root)return true;ViewParent p=v.getParent();v=p instanceof View?(View)p:null;}return false;}
    private static Activity activity(Context context){for(int i=0;i<12&&context!=null;i++){if(context instanceof Activity)return(Activity)context;if(!(context instanceof ContextWrapper))return null;Context next=((ContextWrapper)context).getBaseContext();if(next==context)return null;context=next;}return null;}
    private void release(Binding b,String reason) {
        Object observer=b.observer.get(),argument=b.reason;
        boolean valid=observer!=null&&same(b)&&HostTargets.read(observer,"a")==b.presenter.get()
                &&(!b.feed||feed.sameHolder(b));
        cancel(b);
        if(valid)try { replaying=true; hide.invoke(observer,argument);event(b,reason); }
        catch(Exception ignored) { event(b,"replay_failed"); }
        finally { replaying=false; }
    }
    private void cancel(Binding b) {
        if(b==null)return;b.finished=true;
        if(b.timeout!=null)main.removeCallbacks(b.timeout);
        if(pendingFeed==b)pendingFeed=null;
        if(b.feedObserver!=null&&b.feedObserver.isAlive()){
            b.feedObserver.removeOnPreDrawListener(b.feedDraw);
            b.feedObserver.removeOnWindowFocusChangeListener(b);
        }
        b.feedDraw=null;b.feedObserver=null;
        b.leasedRenderer.clear();
        View cover=b.cover.get();if(cover!=null){if(b.animation!=null)cover.removeCallbacks(b.animation);cover.removeOnAttachStateChangeListener(b);
            if(cover.getViewTreeObserver().isAlive())cover.getViewTreeObserver().removeOnWindowFocusChangeListener(b);}
        b.timeout=null;b.animation=null;b.observer.clear();b.reason=null;
    }
    private void event(Binding b,String state){DouAiLog.event("hide_video_black_edges",b.feed?"manual_swipe_cover":"entry_cover",state,"work="+DouAiLog.reference(b.aid));}
    public void setEnabled(boolean value){enabled=value;if(!value)onMainRun(()->{for(Binding b:snapshot())release(b,"disabled");loads.clear();bindings.clear();if(feed!=null)feed.clear();});}
    private void onMainRun(Runnable r){if(onMain())r.run();else main.post(r);}
    @Override public void close(){if(closed)return;closed=true;enabled=false;onMainRun(()->{for(Binding b:snapshot())release(b,"closed");loads.clear();bindings.clear();for(Application a:applications)a.unregisterActivityLifecycleCallbacks(this);applications.clear();if(feed!=null){feed.clear();feed.hooks.close();}group.close();});}
    @Override public void onActivityPaused(Activity a){for(Binding b:snapshot())if(b.activity.get()==a)release(b,"background");}
    @Override public void onActivityDestroyed(Activity a){onActivityPaused(a);}
    @Override public void onActivityStopped(Activity a){onActivityPaused(a);}
    @Override public void onActivityCreated(Activity a,Bundle state){}
    @Override public void onActivityStarted(Activity a){}
    @Override public void onActivityResumed(Activity a){}
    @Override public void onActivitySaveInstanceState(Activity a,Bundle state){}

    private final class Binding implements View.OnAttachStateChangeListener, ViewTreeObserver.OnWindowFocusChangeListener {
        final WeakReference<Object> presenter;final long boundAt=SystemClock.uptimeMillis();
        WeakReference<Object> params=new WeakReference<>(null),work=new WeakReference<>(null),panel=new WeakReference<>(null),holder=new WeakReference<>(null),window=new WeakReference<>(null),observer=new WeakReference<>(null);
        WeakReference<View> cover=new WeakReference<>(null),root=new WeakReference<>(null),leasedRenderer=new WeakReference<>(null);
        WeakReference<Activity> activity=new WeakReference<>(null);
        String aid,account;Object reason;long leaseUntil;boolean loaded,rendered,finished,feed,gateReported;Runnable timeout,animation;
        ViewTreeObserver feedObserver;ViewTreeObserver.OnPreDrawListener feedDraw;
        Binding(Object p){presenter=new WeakReference<>(p);}
        @Override public void onViewAttachedToWindow(View v){}
        @Override public void onViewDetachedFromWindow(View v){release(this,"detached");}
        @Override public void onWindowFocusChanged(boolean focused){if(!focused)release(this,"window_focus_lost");}
    }
    private static final class Load {
        final Binding binding; final WeakReference<View> cover;
        Load(Binding b){binding=b;cover=new WeakReference<>(b.cover.get());}
    }
}

