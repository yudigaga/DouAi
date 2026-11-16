package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.*;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.*;
import java.util.function.Consumer;

/** A bounded deferral of the native detail-card cover observer, never an overlay. */
public final class VideoPageEntryHook implements AutoCloseable, Application.ActivityLifecycleCallbacks {
    private final BehaviorHookGroup group;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Binding> bindings = new WeakHashMap<>();
    private final Map<Object, Load> loads = new WeakHashMap<>();
    private final Set<Application> applications = Collections.newSetFromMap(new WeakHashMap<>());
    private final Method current, aweme, hide, userService;
    private final Class<?> detail;
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
                        &&load.cover.get()==load.binding.cover.get())load.binding.loaded=true;
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
                        h.event(b,"deferred");
                    }
                    return null;
                }
                // This invocation supersedes any pending observer and must not be replayed again.
                if(b!=null)h.cancel(b);
                return c.proceed();
            });
            for(Method target:new Method[]{selected,resume})h.group.add(framework,target,c->{
                Object result=c.proceed();if(onMain())for(Binding b:h.snapshot())
                    if(b.holder.get()!=null&&!h.owns(b))h.release(b,"selection_changed");return result;
            });
            h.group.add(framework,pause,c->{
                if(onMain())for(Binding b:h.snapshot())if(b.holder.get()==c.getThisObject()
                        ||b.params.get()!=null&&b.params.get()==HostTargets.read(c.getThisObject(),"B2"))h.release(b,"pause");
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
                for(Binding b:before)if(h.owns(b))ownedBefore.add(b);
                Object result=c.proceed();
                for(Binding b:before)if(h.matchesFrame(b,aid,dispatcher)) {
                    b.rendered=true;
                    View cover=b.cover.get();
                    if(b.observer.get()!=null&&cover!=null&&b.animation==null
                            &&ownedBefore.contains(b)&&h.owns(b)) {
                        // The first callback runs before drawing. Release on the following
                        // animation turn so the player has had one drawing opportunity.
                        b.animation=()->{
                            if(!h.same(b)||b.finished)return;
                            b.animation=()->h.release(b,"first_frame");
                            cover.postOnAnimation(b.animation);
                        };
                        cover.postOnAnimation(b.animation);
                    }
                }
                return result;
            });
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
    private void capture(Binding b) {
        if(b==null||b.cover.get()!=null)return;
        Object p=b.presenter.get(),params=HostTargets.read(p,"videoItemParams"),work=HostTargets.read(p,"mAweme");
        Object view=HostTargets.read(p,"mCoverView"),panel=HostTargets.read(params,"mFeedContext");
        if(!(view instanceof View)||!detail.isInstance(panel))return;
        Object aid=HostTargets.invoke(work,"getAid"); String uid=account();
        if(!(aid instanceof String)||((String)aid).isEmpty()||uid==null||uid.isEmpty())return;
        b.params=new WeakReference<>(params);b.cover=new WeakReference<>((View)view);
        b.work=new WeakReference<>(work);b.panel=new WeakReference<>(panel);b.aid=(String)aid;b.account=uid;
        ((View)view).addOnAttachStateChangeListener(b);
        ((View)view).getViewTreeObserver().addOnWindowFocusChangeListener(b);
        resolveOwner(b);
    }
    private void resolveOwner(Binding b) {
        if(b==null||b.observer.get()!=null)return;
        try {Object holder=current.invoke(b.panel.get());
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
        try{return holder!=null&&panel!=null&&detail.isInstance(panel)&&current.invoke(panel)==holder
                &&HostTargets.read(holder,"B2")==b.params.get()&&HostTargets.read(b.params.get(),"mFeedContext")==panel
                &&HostTargets.read(holder,"w")==root&&aweme.invoke(holder)==b.work.get()
                &&root!=null&&cover!=null&&inside(cover,root)&&root.isAttachedToWindow()&&cover.isAttachedToWindow()
                &&root.getWindowToken()!=null&&root.getWindowToken()==b.window.get()
                &&activity!=null&&activity(root.getContext())==activity&&!activity.isFinishing()&&!activity.isDestroyed();
        }catch(Exception ignored){return false;}
    }
    private boolean canHold(Binding b) {
        if(!enabled||closed||b==null||b.finished||!b.loaded||!owns(b))return false;
        long now=SystemClock.uptimeMillis();
        if(b.observer.get()==null) {
            if(b.rendered||now-b.boundAt>1500)return false;
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
        return same(b)&&Objects.equals(b.aid,aid)&&detail.isInstance(b.panel.get())
                &&HostTargets.read(b.params.get(),"mFeedContext")==b.panel.get()
                &&dispatcher!=null&&dispatcher(b)==dispatcher;
    }
    private Object dispatcher(Binding b){return HostTargets.read(HostTargets.read(b.panel.get(),"V"),"m");}
    private static boolean inside(View view,View root){for(View v=view;v!=null;){if(v==root)return true;ViewParent p=v.getParent();v=p instanceof View?(View)p:null;}return false;}
    private static Activity activity(Context context){for(int i=0;i<12&&context!=null;i++){if(context instanceof Activity)return(Activity)context;if(!(context instanceof ContextWrapper))return null;Context next=((ContextWrapper)context).getBaseContext();if(next==context)return null;context=next;}return null;}
    private void release(Binding b,String reason) {
        Object observer=b.observer.get(),argument=b.reason;
        boolean valid=same(b)&&observer!=null&&HostTargets.read(observer,"a")==b.presenter.get();
        cancel(b);
        if(valid)try { replaying=true; hide.invoke(observer,argument);event(b,reason); }
        catch(Exception ignored) { event(b,"replay_failed"); }
        finally { replaying=false; }
    }
    private void cancel(Binding b) {
        if(b==null)return;b.finished=true;
        if(b.timeout!=null)main.removeCallbacks(b.timeout);
        View cover=b.cover.get();if(cover!=null){if(b.animation!=null)cover.removeCallbacks(b.animation);cover.removeOnAttachStateChangeListener(b);
            if(cover.getViewTreeObserver().isAlive())cover.getViewTreeObserver().removeOnWindowFocusChangeListener(b);}
        b.timeout=null;b.animation=null;b.observer.clear();b.reason=null;
    }
    private void event(Binding b,String state){DouAiLog.event("hide_video_black_edges","entry_cover",state,"work="+DouAiLog.reference(b.aid));}
    public void setEnabled(boolean value){enabled=value;if(!value)onMainRun(()->{for(Binding b:snapshot())release(b,"disabled");loads.clear();bindings.clear();});}
    private void onMainRun(Runnable r){if(onMain())r.run();else main.post(r);}
    @Override public void close(){if(closed)return;closed=true;enabled=false;onMainRun(()->{for(Binding b:snapshot())release(b,"closed");loads.clear();bindings.clear();for(Application a:applications)a.unregisterActivityLifecycleCallbacks(this);applications.clear();group.close();});}
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
        WeakReference<View> cover=new WeakReference<>(null),root=new WeakReference<>(null);
        WeakReference<Activity> activity=new WeakReference<>(null);
        String aid,account;Object reason;long leaseUntil;boolean loaded,rendered,finished;Runnable timeout,animation;
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

