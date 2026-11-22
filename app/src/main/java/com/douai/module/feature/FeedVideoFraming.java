package com.douai.module.feature;

import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import com.douai.module.scan.HostScanReport;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface;

/** Frame already-laid-out video in card coordinates without resizing its buffer. */
final class FeedVideoFraming implements AutoCloseable, ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
    private static final int MAX_INCOMING=2;
    private static final int PAGER_DRAGGING=1;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final BehaviorHookGroup hooks,incomingHooks;
    private final FeedRenderScaleHooks scaleHooks;
    private final Method getWork,getPlayer,getSurface,getVideo,getCurrent,comments;
    private final Field params,panel,dialogs,rootField,width,height;
    private Geometry geometry=new Geometry();
    private final LeavingCards leaving=new LeavingCards();
    private final ArrayList<Incoming> incoming=new ArrayList<>(MAX_INCOMING);
    private final ChangeGate diagnostics=new ChangeGate();
    private final FeedFirstFrameTrace firstFrames=new FeedFirstFrameTrace((stage,aid,delta)->
            DouAiLog.event("hide_video_black_edges","first_frame_timing",stage,
                    "work_ref="+DouAiLog.reference(aid)+";since_selection_ms="+delta+";presentation=unverified"));
    private volatile boolean rendererTraceInstalled;
    private boolean unavailableTraceReported;
    private WeakReference<Object> holder=new WeakReference<>(null);
    private WeakReference<View> root=new WeakReference<>(null);
    private ViewTreeObserver observer;
    private ViewTreeObserver.OnPreDrawListener pendingFrame;
    private boolean ordinaryFrameRequested;
    private final AtomicLong layoutEpoch=new AtomicLong();
    private LayoutRecovery pendingLayoutRecovery;
    private boolean observingAttachment;
    private volatile boolean enabled,closed;

    static FeedVideoFraming install(XposedInterface api,HostScanReport report,ClassLoader loader,boolean enabled,Consumer<String> log) throws Exception {
        return new FeedVideoFraming(api,report,loader,enabled,log);
    }
    private FeedVideoFraming(XposedInterface api,HostScanReport report,ClassLoader loader,boolean enabled,Consumer<String> log) throws Exception {
        this.enabled=enabled;
        hooks=new BehaviorHookGroup("feed_video_framing",log);
        incomingHooks=new BehaviorHookGroup("feed_video_framing.incoming",log);
        Method select=HostTargets.method(report,"feed_holder_page_selected",loader), resume=HostTargets.method(report,"feed_holder_resume",loader),
                pause=HostTargets.method(report,"feed_holder_pause",loader);
        Class<?> type=select.getDeclaringClass();
        getWork=HostTargets.method(report,"feed_aweme_accessor",loader);
        getPlayer=type.getMethod("getFeedPlayerView"); getSurface=getPlayer.getReturnType().getMethod("getVideoView");
        rootField=HostTargets.field(type,"w");params=HostTargets.field(type,"B2");panel=HostTargets.field(params.getType(),"mFeedContext");
        getVideo=getWork.getReturnType().getMethod("getVideo");width=getVideo.getReturnType().getField("t");height=getVideo.getReturnType().getField("s");
        getCurrent=HostTargets.method(report,"auto_slide_current_holder",loader);
        dialogs=HostTargets.field(getCurrent.getDeclaringClass(),"L0");comments=HostTargets.method(report,"auto_slide_comments_showing",loader);
        Method screenChange=comments.getDeclaringClass().getDeclaredMethod("LJJIJIIJIL",String.class,boolean.class);
        if(!getPlayer.getReturnType().getName().equals("com.ss.android.ugc.aweme.feed.adapter.IFeedPlayerView")
                || getSurface.getReturnType()!=View.class || type!=resume.getDeclaringClass() || type!=pause.getDeclaringClass()
                || width.getType()!=int.class || height.getType()!=int.class || dialogs.getType()!=comments.getDeclaringClass()
                || !FrameLayout.class.isAssignableFrom(rootField.getType()) || screenChange.getReturnType()!=void.class)
            throw new IllegalStateException("Unexpected feed rendering contract");
        try {
            for(Method method:new Method[]{select,resume}) hooks.add(api,method,c->{
                Object result=c.proceed(), h=c.getThisObject();onMain(()->select(h));return result;
            });
            hooks.add(api,pause,c->{Object result=c.proceed(),h=c.getThisObject();onMain(()->{if(holder.get()==h)leaveCurrent();});return result;});
            hooks.add(api,screenChange,c->{Object result=c.proceed();
                if(enabled && !closed && "comment_panel".equals(c.getArg(0))) onMain(()->{invalidateLayoutRecovery();clearIncoming();leaving.clear();requestFrame(true);});
                return result;
            });
        } catch(Exception | LinkageError failed){hooks.close();throw failed;}
        installRendererTrace(api,report,loader);
        installIncoming(api,report,loader,type);
        scaleHooks=FeedRenderScaleHooks.install(api,loader,log,this::prepareLayoutRecovery);
    }

    private void installIncoming(XposedInterface api,HostScanReport report,ClassLoader loader,Class<?> holderType){
        try {
            Method bind=HostTargets.method(report,"feed_item_bind_aweme",loader);
            Method bindAt=HostTargets.method(report,"feed_item_bind_aweme_position",loader);
            Method touch=HostTargets.method(report,"auto_slide_pager_touch",loader);
            Method scrollState=HostTargets.method(report,"auto_slide_scroll_state",loader);
            Field pagerField=HostTargets.field(getCurrent.getDeclaringClass(),"f");
            if(bind.getDeclaringClass()!=holderType || bindAt.getDeclaringClass()!=holderType
                    || Modifier.isStatic(bind.getModifiers()) || Modifier.isStatic(bindAt.getModifiers())
                    || bind.getReturnType()!=void.class || bindAt.getReturnType()!=void.class
                    || bind.getParameterCount()!=1 || bindAt.getParameterCount()!=2
                    || bind.getParameterTypes()[0]!=getWork.getReturnType()
                    || bindAt.getParameterTypes()[0]!=getWork.getReturnType() || bindAt.getParameterTypes()[1]!=int.class
                    || Modifier.isStatic(pagerField.getModifiers()) || !ViewGroup.class.isAssignableFrom(touch.getDeclaringClass())
                    || !touch.getDeclaringClass().isAssignableFrom(pagerField.getType()) || touch.getReturnType()!=boolean.class
                    || touch.getParameterCount()!=1 || touch.getParameterTypes()[0]!=MotionEvent.class
                    || scrollState.getDeclaringClass()!=touch.getDeclaringClass() || Modifier.isStatic(scrollState.getModifiers())
                    || scrollState.getParameterCount()!=0 || scrollState.getReturnType()!=int.class)return;
            for(Method method:new Method[]{bind,bindAt})incomingHooks.add(api,method,c->aroundBind(c,pagerField,scrollState));
        }catch(Exception | LinkageError unavailable){incomingHooks.fail(unavailable);}
    }

    private Object aroundBind(XposedInterface.Chain chain,Field pagerField,Method scrollState) throws Throwable {
        if(!enabled || closed || !incomingHooks.isActive() || Looper.myLooper()!=ui.getLooper())return chain.proceed();
        Object h=chain.getThisObject(),work=chain.getArg(0);
        if(holder.get()==h)invalidateLayoutRecovery();
        try {
            View card=(View)rootField.get(h);
            Incoming old=findIncoming(card);
            if(old!=null)old.remove(true);
            leaving.restore(card);
            if(card!=null && card==root.get() && holder.get()==h){
                geometry.colours.clearTopBinding();
                if(getWork.invoke(h)!=work)geometry.restore();
            }
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){/* Preserve the host bind. */}
        Object result=chain.proceed();
        try {
            View card=(View)rootField.get(h);
            if(card!=null && card==root.get() && holder.get()==h){requestFrame(false);return result;}
            Object feed=feed(h);
            if(card==null || !enabled || closed || !getCurrent.getDeclaringClass().isInstance(feed)
                    || getCurrent.invoke(feed)==h || !ordinary(work) || getWork.invoke(h)!=work)return result;
            Object pager=pagerField.get(feed);
            if(!(pager instanceof ViewGroup) || findIncoming(card)!=null)return result;
            if(incoming.size()==MAX_INCOMING)incoming.get(0).remove(true);
            Incoming next=new Incoming(card,h,work,feed,(View)pager,pagerField,scrollState);
            incoming.add(next);card.addOnAttachStateChangeListener(next);
            if(card.isAttachedToWindow())next.onViewAttachedToWindow(card);
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){/* Use native geometry. */}
        return result;
    }

    private Object feed(Object h) throws IllegalAccessException {
        Object item=params.get(h);return item==null?null:panel.get(item);
    }
    private boolean commentsClosed(Object feed) throws ReflectiveOperationException {
        Object state=dialogs.get(feed);
        return state!=null && Boolean.FALSE.equals(comments.invoke(state,true));
    }
    private static boolean ordinary(Object work) {
        return Integer.valueOf(0).equals(HostTargets.invoke(work,"getAwemeType"))
                && Boolean.FALSE.equals(HostTargets.invoke(work,"isLive")) && Boolean.FALSE.equals(HostTargets.invoke(work,"getAd"));
    }
    private static boolean within(View view,View parent){
        if(parent==null)return false;
        for(int depth=0;view!=null && depth<32;depth++){
            if(view==parent)return true;
            view=view.getParent() instanceof View?(View)view.getParent():null;
        }
        return false;
    }
    private Incoming findIncoming(View card){
        if(card!=null)for(Incoming next:incoming)if(next.card.get()==card)return next;
        return null;
    }
    private void clearIncoming(){while(!incoming.isEmpty())incoming.get(incoming.size()-1).remove(true);}

    private Geometry takeIncoming(View card,Object h){
        Incoming next=findIncoming(card);
        if(next==null)return null;
        boolean take=false;
        try {take=next.matches(h);}
        catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){/* Restore only our properties. */}
        next.remove(!take);
        return take?next.geometry:null;
    }
    private void installRendererTrace(XposedInterface api,HostScanReport report,ClassLoader loader){
        try {
            Class<?> dispatcher=HostTargets.method(report,"component_player_playing",loader).getDeclaringClass();
            Class<?> info=hostRenderType(loader,"X.0Gf4");
            Method first=dispatcher.getDeclaredMethod("onRenderFirstFrame",String.class,info);
            if(first.isSynthetic() || Modifier.isStatic(first.getModifiers()) || first.getReturnType()!=void.class)return;
            hooks.add(api,first,chain->{
                boolean collect=tracing();
                long at=collect?SystemClock.elapsedRealtime():0;
                Object aid=collect?chain.getArg(0):null,owner=collect?chain.getThisObject():null;
                Object result=chain.proceed();
                if(aid instanceof String)onMain(()->{
                    if(!tracing())return;
                    try {firstFrames.rendered(owner,(String)aid,at);}
                    catch(RuntimeException | LinkageError unavailable){firstFrames.clear();}
                });
                return result;
            });
            rendererTraceInstalled=true;
        }catch(Exception | LinkageError unavailable){
            // Optional observation; host signature changes never disable framing.
        }
    }
    // Obfuscated DEX names are valid at runtime but are not Java PSI type names in Lint.
    private static Class<?> hostRenderType(ClassLoader loader,String name) throws ClassNotFoundException {
        return Class.forName(name,false,loader);
    }
    private boolean tracing(){return rendererTraceInstalled && enabled && !closed && DebugLog.isEnabled();}

    private void traceSelection(Object selected){
        try {
            if(!tracing()){
                firstFrames.clear();
                if(DebugLog.isEnabled() && !rendererTraceInstalled && !unavailableTraceReported){
                    unavailableTraceReported=true;
                    DouAiLog.event("hide_video_black_edges","first_frame_timing","callback_unavailable","presentation=unverified");
                }
                return;
            }
            if(selected==null)return;
            Object item=params.get(selected), feed=item==null?null:panel.get(item);
            if(getCurrent.getDeclaringClass().isInstance(feed) && getCurrent.invoke(feed)==selected)
                traceSelection(feed,getWork.invoke(selected));
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){firstFrames.deselected();}
    }
    private void traceSelection(Object feed,Object work){
        Object owner=dispatcher(feed);String aid=workAid(work);
        if(!firstFrames.matches(owner,aid))firstFrames.selected(owner,aid,SystemClock.elapsedRealtime());
    }
    private void traceDraw(Object feed,Object work){
        if(!tracing())return;
        try {
            traceSelection(feed,work);
            firstFrames.drawOpportunity(SystemClock.elapsedRealtime());
        }catch(RuntimeException | LinkageError unavailable){firstFrames.deselected();}
    }
    private static Object dispatcher(Object feed){return HostTargets.read(HostTargets.read(feed,"V"),"m");}
    private static String workAid(Object work){Object aid=HostTargets.invoke(work,"getAid");return aid instanceof String?(String)aid:null;}
    void setEnabled(boolean value) {
        if(enabled==value || closed)return;
        layoutEpoch.incrementAndGet();
        enabled=value;
        onMain(()->{if(closed)return;if(enabled){traceSelection(holder.get());observe();requestFrame(true);}else{stopObserving();geometry.restore();geometry.colours.clearTopBinding();clearIncoming();leaving.clear();firstFrames.clear();}});
    }
    private void select(Object h) {
        if(closed)return;
        try{
            View card=(View)rootField.get(h);
            if(card!=root.get() || holder.get()!=h){
                leaveCurrent();
                Geometry prepared=takeIncoming(card,h);
                geometry=prepared==null?leaving.take(card):prepared;
                holder=new WeakReference<>(h);root=new WeakReference<>(card);
            }
            if(enabled){traceSelection(h);observe();requestFrame(true);}
        }catch(ReflectiveOperationException | RuntimeException e){unbind();}
    }
    private void observe(){
        View v=root.get();if(v==null)return;
        if(!observingAttachment){v.addOnAttachStateChangeListener(this);observingAttachment=true;}
        if(!v.isAttachedToWindow())return;
        ViewTreeObserver tree=v.getViewTreeObserver();
        if(tree.isAlive() && observer!=tree){stopObservingDraws();observer=tree;tree.addOnGlobalLayoutListener(this);}
    }
    private void stopObserving(){
        stopObservingDraws();
        View v=root.get();if(v!=null && observingAttachment)v.removeOnAttachStateChangeListener(this);
        observingAttachment=false;
    }
    private void stopObservingDraws(){
        cancelFrame();
        if(observer!=null && observer.isAlive())observer.removeOnGlobalLayoutListener(this);observer=null;
    }
    @Override public void onViewAttachedToWindow(View v){if(v==root.get() && enabled && !closed){traceSelection(holder.get());observe();requestFrame(true);}}
    @Override public void onViewDetachedFromWindow(View v){if(v==root.get()){invalidateLayoutRecovery();stopObservingDraws();geometry.restore();geometry.colours.clearTopBinding();firstFrames.deselected();}}
    @Override public void onGlobalLayout(){requestFrame(false);}
    private void requestFrame(boolean invalidate){requestFrame(invalidate,true);}
    private void requestFrame(boolean invalidate,boolean ordinary){
        if(!enabled || closed || observer==null || !observer.isAlive())return;
        ordinaryFrameRequested|=ordinary;
        if(pendingFrame!=null)return;
        // Other global layout listeners can still move the host render view in this traversal.
        pendingFrame=new ViewTreeObserver.OnPreDrawListener(){
            @Override public boolean onPreDraw(){
                if(pendingFrame==this){
                    LayoutRecovery recovery=pendingLayoutRecovery;
                    boolean regular=ordinaryFrameRequested;
                    cancelFrame();
                    if(canRecoverLayout(recovery,true))apply(recovery,regular);
                    else if(regular)apply(null,true);
                }
                return true;
            }
        };
        observer.addOnPreDrawListener(pendingFrame);
        View v=root.get();if(invalidate && v!=null)v.postInvalidateOnAnimation();
    }
    private void cancelFrame(){
        if(pendingFrame!=null && observer!=null && observer.isAlive())observer.removeOnPreDrawListener(pendingFrame);
        pendingFrame=null;
        ordinaryFrameRequested=false;
        pendingLayoutRecovery=null;
    }

    Runnable prepareLayoutRecovery(View render,Object work,Object video){
        if(!enabled || closed || Looper.myLooper()!=ui.getLooper() || render==null
                || geometry.target.get()!=render || !incomingHooks.isActive())return null;
        Object h=holder.get();
        View card=root.get();
        if(h==null || card==null)return null;
        try {
            if(work==null || video==null || getWork.invoke(h)!=work || getVideo.invoke(work)!=video){
                // A later host layout can touch the renderer before its next work is bound.
                invalidateLayoutRecovery();
                return null;
            }
            if(!geometry.ownsFilledRenderer(render))return null;
            Object item=params.get(h), feed=feed(h);
            Object player=getPlayer.invoke(h);
            IBinder window=card.getWindowToken();
            List<View> ancestry=rendererAncestry(render,card);
            if(item==null || player==null || window==null || ancestry==null)return null;
            LayoutRecovery recovery=new LayoutRecovery(layoutEpoch.get(),geometry,geometry.scale,
                    h,item,feed,work,video,player,card,render,window,ancestry,card.getParent());
            if(!sameLayoutBinding(recovery))return null;
            return ()->{
                if(canRecoverLayout(recovery,false) && observer!=null && observer.isAlive()
                        && observer==recovery.card().getViewTreeObserver()){
                    pendingLayoutRecovery=recovery;
                    requestFrame(false,false);
                }
            };
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){return null;}
    }

    private boolean canRecoverLayout(LayoutRecovery recovery,boolean drawing){
        if(recovery==null || Looper.myLooper()!=ui.getLooper())return false;
        try {
            View render=recovery.render();
            return recovery.geometry().hasResetRenderer(render,recovery.ownedScale())
                    && (!drawing || (!recovery.card().isLayoutRequested() && !render.isLayoutRequested()))
                    && sameLayoutBinding(recovery);
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){return false;}
    }

    private boolean sameLayoutBinding(LayoutRecovery recovery) throws ReflectiveOperationException {
        View card=recovery.card(),render=recovery.render();
        Object h=recovery.holder(),feed=recovery.feed();
        if(!enabled || closed || layoutEpoch.get()!=recovery.epoch() || geometry!=recovery.geometry()
                || holder.get()!=h || root.get()!=card || rootField.get(h)!=card || params.get(h)!=recovery.item()
                || !card.isAttachedToWindow() || !render.isAttachedToWindow() || !card.isShown() || !render.isShown()
                || card.getWindowToken()!=recovery.window() || render.getWindowToken()!=recovery.window()
                || card.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT
                || card.getParent()!=recovery.cardParent() || !recovery.hasSameAncestry()
                || !getCurrent.getDeclaringClass().isInstance(feed) || feed(h)!=feed
                || getCurrent.invoke(feed)!=h || getWork.invoke(h)!=recovery.work()
                || getVideo.invoke(recovery.work())!=recovery.video() || !commentsClosed(feed))return false;
        Object player=getPlayer.invoke(h);
        return player==recovery.player() && getSurface.invoke(player)==render;
    }

    private static List<View> rendererAncestry(View render,View card){
        ArrayList<View> path=new ArrayList<>();
        for(View view=render;view!=null && path.size()<32;){
            path.add(view);
            if(view==card)return path;
            view=view.getParent() instanceof View parent?parent:null;
        }
        return null;
    }

    private void invalidateLayoutRecovery(){
        layoutEpoch.incrementAndGet();
        pendingLayoutRecovery=null;
    }

    private record LayoutRecovery(long epoch,Geometry geometry,float ownedScale,Object holder,Object item,
                                  Object feed,Object work,Object video,Object player,View card,View render,
                                  IBinder window,List<View> ancestry,ViewParent cardParent) {
        boolean hasSameAncestry(){
            for(int i=1;i<ancestry.size();i++)if(ancestry.get(i-1).getParent()!=ancestry.get(i))return false;
            return true;
        }
    }

    private void apply(LayoutRecovery recovery,boolean ordinary){
        if(!enabled || closed)return;
        try { if(!applyToCurrent(recovery) && ordinary) geometry.restore(); }
        catch(ReflectiveOperationException | RuntimeException | LinkageError ignored){if(ordinary)geometry.restore();}
    }
    private boolean applyToCurrent(LayoutRecovery recovery) throws ReflectiveOperationException {
        Object h=holder.get();View card=root.get();
        if(h==null || card==null || !card.isAttachedToWindow() || !card.isShown()
                || card.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return false;
        Object feed=feed(h);
        if(!getCurrent.getDeclaringClass().isInstance(feed))return false;
        if(getCurrent.invoke(feed)!=h){
            firstFrames.deselected();
            // The pager can change its current holder before either holder's lifecycle callback.
            if(geometry.changed()){leaving.leave(card,geometry);geometry=new Geometry();}
            return false;
        }
        if(!commentsClosed(feed))return false;
        Object work=getWork.invoke(h);
        traceDraw(feed,work);
        if(!ordinary(work))return false;
        Object video=getVideo.invoke(work), player=getPlayer.invoke(h);
        if(video==null || player==null)return false;
        View render=(View)getSurface.invoke(player);
        if(!geometry.changed() && !leaving.empty())geometry=leaving.take(card);
        int sw=width.getInt(video),sh=height.getInt(video);
        boolean recoverScale=recovery!=null && recovery.render()==render
                && recovery.geometry()==geometry && layoutEpoch.get()==recovery.epoch()
                && recovery.holder()==h && recovery.card()==card && recovery.feed()==feed
                && recovery.work()==work && recovery.video()==video && recovery.player()==player
                && geometry.hasResetRenderer(render,recovery.ownedScale());
        if(recovery!=null && !recoverScale)return false;
        boolean applied=geometry.frame(render,card,sw,sh,recoverScale);
        Geometry appliedGeometry=geometry;
        if(applied && geometry.syncColouring(card,render,h,feed,work,()->topCoverage(appliedGeometry,h,feed,work,card,render))
                && DebugLog.isEnabled())
            DouAiLog.event("hide_video_black_edges","free_space_colour_bands","redundant_after_fill",
                    "work_ref="+DouAiLog.reference(workAid(work))+";presentation=unverified");
        report(work,render,card,sw,sh,applied);
        return applied;
    }

    private boolean topCoverage(Geometry expected,Object h,Object feed,Object work,View card,View render) {
        if(!enabled || closed || geometry!=expected || holder.get()!=h || root.get()!=card
                || !expected.coversNativeVerticalGap)return false;
        try {
            Object player=getPlayer.invoke(h);
            return rootField.get(h)==card && feed(h)==feed && getCurrent.invoke(feed)==h && getWork.invoke(h)==work
                    && commentsClosed(feed) && player!=null && getSurface.invoke(player)==render
                    && expected.fullCoverage(card,render);
        }catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){
            return false;
        }
    }

    private void report(Object work,View render,View card,int sw,int sh,boolean applied){
        int cw=card.getWidth()-card.getPaddingLeft()-card.getPaddingRight();
        int ch=card.getHeight()-card.getPaddingTop()-card.getPaddingBottom();
        int rw=render==null?0:render.getWidth(),rh=render==null?0:render.getHeight();
        Geometry g=geometry;
        long shape=g.reason.hashCode()*31L+g.blockedLayer.hashCode();
        shape=shape*31+sw;shape=shape*31+sh;shape=shape*31+cw;shape=shape*31+ch;
        shape=shape*31+rw;shape=shape*31+rh;shape=shape*31+g.rawX;shape=shape*31+g.rawY;
        shape=shape*31+(applied?Math.round(g.scale*1000):0);
        shape=shape*31+(applied?Math.round(g.tx):0);shape=shape*31+(applied?Math.round(g.ty):0);
        if(!diagnostics.emit(work,shape,SystemClock.elapsedRealtime()))return;
        Object aid=HostTargets.invoke(work,"getAid");
        String layer=!applied?"none":g.target.get()==render?"render":"media_parent";
        DouAiLog.event("hide_video_black_edges","framing_geometry",g.reason,
                "work_ref="+DouAiLog.reference(aid instanceof String?(String)aid:null)+";source="+sw+"x"+sh+
                        ";render="+rw+"x"+rh+";card="+cw+"x"+ch+";render_in_card="+
                        (g.rawX==Integer.MIN_VALUE?"unknown":g.rawX+","+g.rawY)+";layer="+layer+
                        ";scale="+(applied?g.scale:1)+";translation="+(applied?g.tx:0)+","+(applied?g.ty:0)
                        +";blocked_layer="+g.blockedLayer);
    }
    static final class ChangeGate {
        private WeakReference<Object> previous=new WeakReference<>(null);private long signature,next;
        boolean emit(Object work,long value,long now){
            if(now<next || previous.get()==work && signature==value)return false;
            signature=value;next=now+500;previous=new WeakReference<>(work);return true;
        }
    }
    static int[] size(int sourceW,int sourceH,int areaW,int areaH) {
        if(sourceW<=0 || sourceH<=0 || areaW<=0 || areaH<=0 || sourceW>32768 || sourceH>32768 || areaW>32768 || areaH>32768)return null;
        double scale=scale(sourceW,sourceH,areaW,areaH);
        return new int[]{Math.max(1,(int)Math.round(sourceW*scale)),Math.max(1,(int)Math.round(sourceH*scale))};
    }
    private static double scale(int sw,int sh,int w,int h){
        double x=w/(double)sw,y=h/(double)sh,fit=Math.min(x,y),fill=Math.max(x,y);
        return fit/fill>=.75?fill:fit;
    }

    private final class Incoming implements ViewTreeObserver.OnGlobalLayoutListener, ViewTreeObserver.OnPreDrawListener,
            View.OnAttachStateChangeListener {
        final WeakReference<View> card,pager;
        final WeakReference<Object> holder,work,panel;
        final Field pagerField;
        final Method scrollState;
        final Geometry geometry=new Geometry();
        final Rect clip=new Rect(),viewport=new Rect();
        WeakReference<View> renderer=new WeakReference<>(null);
        ViewTreeObserver tree;
        boolean dirty=true,visible,manual,removed,reported;
        Incoming(View card,Object holder,Object work,Object panel,View pager,Field pagerField,Method scrollState){
            this.card=new WeakReference<>(card);this.holder=new WeakReference<>(holder);this.work=new WeakReference<>(work);
            this.panel=new WeakReference<>(panel);this.pager=new WeakReference<>(pager);
            this.pagerField=pagerField;this.scrollState=scrollState;
        }
        private boolean scoped(View card,View pager){
            return card!=null && pager!=null && card.isAttachedToWindow() && pager.isAttachedToWindow()
                    && within(card,pager) && card.getWindowVisibility()==View.VISIBLE
                    && card.getResources().getConfiguration().orientation==Configuration.ORIENTATION_PORTRAIT;
        }
        @Override public void onViewAttachedToWindow(View view){
            if(tree!=null || removed)return;
            ViewTreeObserver observer=view.getViewTreeObserver();
            if(!observer.isAlive())return;
            tree=observer;tree.addOnGlobalLayoutListener(this);tree.addOnPreDrawListener(this);
        }
        @Override public void onViewDetachedFromWindow(View view){remove(true);}
        @Override public void onGlobalLayout(){dirty=true;}
        @Override public boolean onPreDraw(){
            if(removed)return true;
            try {update();}
            catch(ReflectiveOperationException | RuntimeException | LinkageError unavailable){remove(true);}
            return true;
        }
        private void update() throws ReflectiveOperationException {
            View card=this.card.get(),pager=this.pager.get();
            if(!enabled || closed || !scoped(card,pager) || holder.get()==null || work.get()==null || panel.get()==null){
                remove(true);return;
            }
            boolean entering=card.isShown() && card.getGlobalVisibleRect(clip)
                    && pager.getGlobalVisibleRect(viewport) && Rect.intersects(clip,viewport);
            if(!entering){
                if(visible)geometry.restore();
                visible=false;manual=false;return;
            }
            boolean first=!visible;
            visible=true;
            if(first)manual=Integer.valueOf(PAGER_DRAGGING).equals(scrollState.invoke(pager));
            if(!manual)return;
            View target=geometry.target.get(),render=renderer.get();
            if(target!=null && (!geometry.clean(target) || !within(render,card) || !within(target,card))){
                geometry.restore();dirty=true;
            }
            if(dirty || first){dirty=false;prepare();}
        }
        private void prepare() throws ReflectiveOperationException {
            View card=this.card.get(),pager=this.pager.get();
            Object h=holder.get(),feed=panel.get(),work=this.work.get();
            if(h==null || feed==null || work==null || rootField.get(h)!=card || feed(h)!=feed
                    || pagerField.get(feed)!=pager || getWork.invoke(h)!=work || !commentsClosed(feed)){
                remove(true);return;
            }
            Object player=getPlayer.invoke(h),video=getVideo.invoke(work);
            View render=player==null?null:(View)getSurface.invoke(player);
            if(renderer.get()!=render)geometry.restore();
            renderer=new WeakReference<>(render);
            if(video==null || render==null){geometry.restore();return;}
            int sw=width.getInt(video),sh=height.getInt(video);
            if(!geometry.frame(render,card,sw,sh)){geometry.restore();return;}
            if(geometry.changed() && !reported && DebugLog.isEnabled()){
                reported=true;
                DouAiLog.event("hide_video_black_edges","incoming_framing",geometry.reason,
                        "work_ref="+DouAiLog.reference(workAid(work))+";render="+render.getWidth()+"x"+render.getHeight()
                                +";scale="+geometry.scale+";presentation=unverified");
            }
        }
        boolean matches(Object h) throws ReflectiveOperationException {
            View card=this.card.get(),pager=this.pager.get(),target=geometry.target.get(),render=renderer.get();
            Object feed=panel.get(),work=this.work.get();
            if(!visible || !geometry.changed() || !scoped(card,pager) || h!=holder.get()
                    || work==null || feed==null || rootField.get(h)!=card || feed(h)!=feed || pagerField.get(feed)!=pager
                    || getCurrent.invoke(feed)!=h || getWork.invoke(h)!=work || !commentsClosed(feed)
                    || !within(target,card) || !within(render,card) || !geometry.clean(target))return false;
            Object player=getPlayer.invoke(h);
            return player!=null && getSurface.invoke(player)==render;
        }
        void remove(boolean restore){
            if(removed)return;removed=true;
            View v=card.get();if(v!=null)v.removeOnAttachStateChangeListener(this);
            if(tree!=null && tree.isAlive()){tree.removeOnGlobalLayoutListener(this);tree.removeOnPreDrawListener(this);}
            tree=null;incoming.remove(this);
            if(restore)geometry.restore();
        }
    }

    /** Only borrow compositor properties, not layout params, native gravity, margins or surface size. */
    static final class Geometry {
        private WeakReference<View> target=new WeakReference<>(null);
        private final Matrix matrix=new Matrix();
        private final FeedColourBandLease colours=new FeedColourBandLease();
        private Rect coverageCard,coverageRenderer;
        private WeakReference<Object> colourHolder=new WeakReference<>(null),colourFeed=new WeakReference<>(null),
                colourWork=new WeakReference<>(null);
        private WeakReference<View> colourCard=new WeakReference<>(null),colourRender=new WeakReference<>(null);
        private float scale=1,tx,ty;
        private boolean coversNativeVerticalGap;
        int rawX,rawY;
        String reason="unmeasured",blockedLayer="none";
        boolean changed(){return target.get()!=null && (scale!=1 || tx!=0 || ty!=0);}
        boolean ownsFilledRenderer(View render){
            return render!=null && coversNativeVerticalGap && target.get()==render && scale>1f && clean(render);
        }
        boolean hasResetRenderer(View render,float ownedScale){
            return render!=null && coversNativeVerticalGap && target.get()==render && scale==ownedScale && scale>1f
                    && transformRejection(render,1f)==null;
        }
        boolean frame(View render,View card,int srcW,int srcH) {
            return frame(render,card,srcW,srcH,false);
        }
        private boolean frame(View render,View card,int srcW,int srcH,boolean recoverScale) {
            rawX=rawY=Integer.MIN_VALUE;reason="unsupported_surface_or_clip";blockedLayer="none";
            if(!recoverScale)coversNativeVerticalGap=false;
            if(!(render instanceof SurfaceView || render instanceof TextureView) || !(card instanceof ViewGroup)
                    || !render.isAttachedToWindow() || !((ViewGroup)card).getClipChildren()
                    || !((ViewGroup)card).getClipToPadding() || render.getWidth()<=0 || render.getHeight()<=0)return false;
            String rejected=transformRejection(card);
            String rejectedLayer="card";
            if(rejected==null && render instanceof TextureView && !((TextureView)render).getTransform(matrix).isIdentity()){
                rejected="texture_transform";rejectedLayer="render";
            }
            int w=card.getWidth()-card.getPaddingLeft()-card.getPaddingRight(), h=card.getHeight()-card.getPaddingTop()-card.getPaddingBottom();
            reason="unusable_size";
            if(w<=0 || w>h || srcW<=0 || srcH<=0 || srcW>32768 || srcH>32768 || w>32768 || h>32768)return false;
            int rw=render.getWidth(),rh=render.getHeight();
            // An unexplained letterbox inside a buffer cannot be safely removed by guessing its bounds.
            float s=(float)scale(rw,rh,w,h);
            float centerX=card.getPaddingLeft()+w*.5f, centerY=card.getPaddingTop()+h*.5f;
            float halfW=rw*s*.5f,halfH=rh*s*.5f;
            float x=0,y=0,layerX=0,layerY=0;
            View layer=render,v=render;
            // Map physical layout coordinates (excluding our old transforms) to card-local coordinates.
            int depth=0;
            while(v!=card && depth++<32){
                if(!(v.getParent() instanceof ViewGroup))return reject("renderer_outside_card","ancestry");
                // A clip in an unrelated window branch must not mask a wrong-card rejection.
                if(rejected==null){
                    rejected=recoverScale && v==render?transformRejection(v,1f):transformRejection(v);
                    if(rejected!=null)rejectedLayer=v==render?"render":"media_parent";
                }
                ViewGroup p=(ViewGroup)v.getParent();
                x+=v.getLeft()-p.getScrollX();y+=v.getTop()-p.getScrollY();v=p;
            }
            if(v!=card)return reject("renderer_outside_card","ancestry");
            if(rejected!=null)return reject(rejected,rejectedLayer);
            rawX=(int)x;rawY=(int)y;
            reason="surface_aspect_mismatch";
            if(Math.abs(rw*(double)srcH-rh*(double)srcW)>Math.max(srcW,srcH)*2.0)return false;
            layerX=x;layerY=y;
            boolean singleBranch=true;boolean constrained=false;
            for(v=render;v!=card;){
                ViewGroup p=(ViewGroup)v.getParent();
                float px=x-(v.getLeft()-p.getScrollX()),py=y-(v.getTop()-p.getScrollY());
                if(p!=card){
                    singleBranch &= p.getChildCount()==1 && p.getBackground()==null && p.getForeground()==null;
                    // If this parent clips the intended *visible* picture, scaling the child alone
                    // cannot fix the upper band. Move the video-only parent itself, never feed controls.
                    int pl=p.getClipToPadding()?p.getPaddingLeft():0,pt=p.getClipToPadding()?p.getPaddingTop():0;
                    int pr=p.getClipToPadding()?p.getPaddingRight():0,pb=p.getClipToPadding()?p.getPaddingBottom():0;
                    if(p.getClipChildren() && (px+pl>Math.max(card.getPaddingLeft(),centerX-halfW)+1
                            || py+pt>Math.max(card.getPaddingTop(),centerY-halfH)+1
                            || px+p.getWidth()-pr<Math.min(card.getWidth()-card.getPaddingRight(),centerX+halfW)-1
                            || py+p.getHeight()-pb<Math.min(card.getHeight()-card.getPaddingBottom(),centerY+halfH)-1)){
                        // Don't enlarge an ancestor that already clips part of the decoded picture.
                        if(singleBranch && layerX>=px+pl-1 && layerY>=py+pt-1
                                && layerX+layer.getWidth()<=px+p.getWidth()-pr+1 && layerY+layer.getHeight()<=py+p.getHeight()-pb+1){
                            layer=p;layerX=px;layerY=py;
                        } else {
                            // Its sibling may be a control. Don't move it or defeat native clipping.
                            // Use the largest symmetric frame possible without cutting more of the picture.
                            float limitX=Math.min(centerX-px-pl,px+p.getWidth()-pr-centerX);
                            float limitY=Math.min(centerY-py-pt,py+p.getHeight()-pb-centerY);
                            if(limitX<=0 || limitY<=0){reason="parent_clip";return false;}
                            s=Math.min(s,Math.min(2*limitX/rw,2*limitY/rh));
                            constrained=true;
                            halfW=rw*s*.5f;halfH=rh*s*.5f;
                        }
                    }
                }
                x=px;y=py;v=p;
            }
            // Raw surface center in card space, even if native gravity anchors the image at the bottom.
            float cx=layerX+rw*.5f,cy=layerY+rh*.5f;
            for(v=render;v!=layer;v=(View)v.getParent()){
                ViewGroup p=(ViewGroup)v.getParent();cx+=v.getLeft()-p.getScrollX();cy+=v.getTop()-p.getScrollY();
            }
            float dx=centerX-cx-(s-1)*(cx-layerX-layer.getPivotX());
            float dy=centerY-cy-(s-1)*(cy-layerY-layer.getPivotY());
            boolean fills=s>1f && rh<h && rw*s+.5f>=w && rh*s+.5f>=h;
            if(recoverScale && (layer!=render || !fills))return reject("layout_reset_not_fill","render");
            if(target.get()!=layer){restore();target=new WeakReference<>(layer);}
            if(layer.getScaleX()!=s)layer.setScaleX(s);
            if(layer.getScaleY()!=s)layer.setScaleY(s);
            if(layer.getTranslationX()!=dx)layer.setTranslationX(dx);
            if(layer.getTranslationY()!=dy)layer.setTranslationY(dy);
            scale=s;tx=dx;ty=dy;reason=constrained?"centered_in_clip":"centered";
            coversNativeVerticalGap=fills;
            return true;
        }
        boolean syncColouring(View card,View render){return colours.sync(card,render.getWidth(),coversNativeVerticalGap);}
        boolean syncColouring(View card,View render,Object holder,Object feed,Object work,BooleanSupplier fullCoverage){
            if(colourCard.get()!=card || colourRender.get()!=render || colourHolder.get()!=holder
                    || colourFeed.get()!=feed || colourWork.get()!=work){
                colours.clearTopBinding();
                colourCard=new WeakReference<>(card);
                colourRender=new WeakReference<>(render);
                colourHolder=new WeakReference<>(holder);
                colourFeed=new WeakReference<>(feed);
                colourWork=new WeakReference<>(work);
            }
            return colours.sync(card,render.getWidth(),coversNativeVerticalGap,fullCoverage);
        }
        boolean fullCoverage(View card,View render){
            View layer=target.get();
            if(layer==null || !card.isShown() || !render.isShown()
                    || card.getWindowToken()==null || render.getWindowToken()!=card.getWindowToken()
                    || card.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)
                return false;
            boolean ownsLayer=false;
            View view=render;
            for(int depth=0;view!=null && depth<32;depth++){
                if(!clean(view) || view.getAlpha()<=0f)return false;
                ownsLayer|=view==layer;
                if(view==card)break;
                view=view.getParent() instanceof View parent?parent:null;
            }
            if(view!=card || !ownsLayer)return false;
            if(coverageCard==null){coverageCard=new Rect();coverageRenderer=new Rect();}
            return card.getGlobalVisibleRect(coverageCard) && render.getGlobalVisibleRect(coverageRenderer)
                    && !coverageCard.isEmpty() && coverageCard.width()>=card.getWidth() && coverageCard.height()>=card.getHeight()
                    && coverageRenderer.contains(coverageCard);
        }
        private boolean reject(String code,String layer){reason=code;blockedLayer=layer;return false;}
        private boolean clean(View v) {return transformRejection(v)==null;}
        private String transformRejection(View v) {
            return transformRejection(v,v==target.get()?scale:1f);
        }
        private String transformRejection(View v,float expectedScale) {
            boolean ours=v==target.get();
            if(v.getRotation()!=0 || v.getRotationX()!=0 || v.getRotationY()!=0)return "native_rotation";
            if(v.getAnimation()!=null)return "native_animation";
            if(v.getClipBounds()!=null || v.getClipToOutline())return "native_clip";
            if(v.getScaleX()!=expectedScale || v.getScaleY()!=expectedScale)return "native_scale";
            if(v.getTranslationX()!=(ours?tx:0) || v.getTranslationY()!=(ours?ty:0))return "native_translation";
            return null;
        }
        void restore(){
            colours.suspend();
            coversNativeVerticalGap=false;
            View v=target.get();
            if(v!=null){
                if(scale!=1 && v.getScaleX()==scale)v.setScaleX(1);
                if(scale!=1 && v.getScaleY()==scale)v.setScaleY(1);
                if(tx!=0 && v.getTranslationX()==tx)v.setTranslationX(0);
                if(ty!=0 && v.getTranslationY()==ty)v.setTranslationY(0);
            }
            target.clear();scale=1;tx=0;ty=0;
        }
    }

    /** Only outgoing, actually painted card portions keep their transforms while a swipe settles. */
    static final class LeavingCards {
        private final ArrayList<Lease> list=new ArrayList<>(3);
        private final Rect clip=new Rect();
        boolean empty(){return list.isEmpty();}
        Geometry take(View card){
            Geometry result=null;
            for(int i=list.size()-1;i>=0;i--){
                Lease l=list.get(i);
                if(!l.visible())l.release(true);
                else if(card==l.card.get()){result=l.geometry;l.release(false);}
            }
            return result!=null?result:new Geometry();
        }
        void leave(View card,Geometry geometry){
            geometry.colours.releaseTranslatedTop();
            if(card==null || !geometry.changed()){geometry.restore();return;}
            Lease l=new Lease(card,geometry);
            if(!l.visible()){geometry.restore();return;}
            if(list.size()==3)list.get(0).release(true);
            list.add(l);
            card.addOnAttachStateChangeListener(l);
            ViewTreeObserver tree=card.getViewTreeObserver();l.tree=new WeakReference<>(tree);
            if(tree.isAlive())tree.addOnPreDrawListener(l);else l.release(true);
        }
        void restore(View card){
            if(card!=null)for(int i=list.size()-1;i>=0;i--)if(list.get(i).card.get()==card)list.get(i).release(true);
        }
        void clear(){while(!list.isEmpty())list.get(list.size()-1).release(true);}
        private final class Lease implements ViewTreeObserver.OnPreDrawListener,View.OnAttachStateChangeListener {
            final WeakReference<View> card;final Geometry geometry;
            WeakReference<ViewTreeObserver> tree;boolean released;
            Lease(View card,Geometry g){this.card=new WeakReference<>(card);geometry=g;}
            boolean visible(){
                View v=card.get(),t=geometry.target.get();
                if(v==null || !v.isAttachedToWindow() || !v.isShown() || v.getWindowVisibility()!=View.VISIBLE
                        || v.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT
                        || !v.getGlobalVisibleRect(clip) || clip.isEmpty() || t==null || !geometry.clean(t))return false;
                for(int n=0;t!=null && n<32;n++){
                    if(t==v)return true;
                    t=t.getParent() instanceof View?(View)t.getParent():null;
                }
                return false;
            }
            void release(boolean restore){
                if(released)return;released=true;
                View v=card.get();if(v!=null)v.removeOnAttachStateChangeListener(this);
                ViewTreeObserver o=tree==null?null:tree.get();if(o!=null && o.isAlive())o.removeOnPreDrawListener(this);
                list.remove(this);if(restore){geometry.restore();geometry.colours.clearTopBinding();}
            }
            public boolean onPreDraw(){if(!released&&!visible())release(true);return true;}
            public void onViewAttachedToWindow(View ignored){}
            public void onViewDetachedFromWindow(View ignored){release(true);}
        }
    }
    private void leaveCurrent(){
        invalidateLayoutRecovery();
        stopObserving();
        firstFrames.deselected();
        if(enabled&&!closed)leaving.leave(root.get(),geometry);
        else {geometry.restore();geometry.colours.clearTopBinding();}
        geometry=new Geometry();holder.clear();root.clear();
    }
    private void unbind(){invalidateLayoutRecovery();stopObserving();geometry.restore();geometry.colours.clearTopBinding();holder.clear();root.clear();firstFrames.clear();}
    private void onMain(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else ui.post(r);}
    @Override public void close(){closed=true;scaleHooks.close();incomingHooks.close();hooks.close();onMain(()->{unbind();clearIncoming();leaving.clear();});}
}
