package com.douai.module.feature;

import android.content.res.Configuration;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import com.douai.module.scan.HostScanReport;
import com.douai.module.diagnostics.DouAiLog;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.function.Consumer;
import io.github.libxposed.api.XposedInterface;

/** Compose the selected video's already-laid-out image into card coordinates without resizing its buffer. */
final class FeedVideoFraming implements AutoCloseable, ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final BehaviorHookGroup hooks;
    private final Method getWork,getPlayer,getSurface,getVideo,getCurrent,comments;
    private final Field params,panel,dialogs,rootField,width,height;
    private Geometry geometry=new Geometry();
    private final LeavingCards leaving=new LeavingCards();
    private final ChangeGate diagnostics=new ChangeGate();
    private WeakReference<Object> holder=new WeakReference<>(null);
    private WeakReference<View> root=new WeakReference<>(null);
    private ViewTreeObserver observer;
    private volatile boolean enabled,closed;

    static FeedVideoFraming install(XposedInterface api,HostScanReport report,ClassLoader loader,boolean enabled,Consumer<String> log) throws Exception {
        return new FeedVideoFraming(api,report,loader,enabled,log);
    }
    private FeedVideoFraming(XposedInterface api,HostScanReport report,ClassLoader loader,boolean enabled,Consumer<String> log) throws Exception {
        this.enabled=enabled;
        hooks=new BehaviorHookGroup("feed_video_framing",log);
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
                if(enabled && !closed && "comment_panel".equals(c.getArg(0))) onMain(()->{leaving.clear();apply();});
                return result;
            });
        } catch(Exception | LinkageError failed){hooks.close();throw failed;}
    }
    void setEnabled(boolean value) {
        if(enabled==value || closed)return;enabled=value;
        onMain(()->{if(closed)return;if(enabled){observe();apply();}else{stopObserving();geometry.restore();leaving.clear();}});
    }
    private void select(Object h) {
        if(closed)return;
        try{
            View card=(View)rootField.get(h);
            if(card!=root.get() || holder.get()!=h){leaveCurrent();geometry=leaving.take(card);holder=new WeakReference<>(h);root=new WeakReference<>(card);}
            if(enabled){observe();apply();}
        }catch(ReflectiveOperationException | RuntimeException e){unbind();}
    }
    private void observe(){
        View v=root.get();if(v==null)return;
        ViewTreeObserver tree=v.getViewTreeObserver();
        if(tree.isAlive() && observer!=tree){stopObserving();observer=tree;tree.addOnGlobalLayoutListener(this);v.addOnAttachStateChangeListener(this);}
    }
    private void stopObserving(){
        if(observer!=null && observer.isAlive())observer.removeOnGlobalLayoutListener(this);observer=null;
        View v=root.get();if(v!=null)v.removeOnAttachStateChangeListener(this);
    }
    @Override public void onViewAttachedToWindow(View v){if(enabled && !closed){observe();apply();}}
    @Override public void onViewDetachedFromWindow(View v){geometry.restore();}
    // Global layout is delivered before drawing. Transform properties don't request a second layout.
    @Override public void onGlobalLayout(){apply();}
    private void apply(){
        if(!enabled || closed)return;
        try { if(!applyToCurrent()) geometry.restore(); }
        catch(ReflectiveOperationException | RuntimeException | LinkageError ignored){geometry.restore();}
    }
    private boolean applyToCurrent() throws ReflectiveOperationException {
        Object h=holder.get();View card=root.get();
        if(h==null || card==null || !card.isAttachedToWindow() || !card.isShown()
                || card.getResources().getConfiguration().orientation!=Configuration.ORIENTATION_PORTRAIT)return false;
        Object item=params.get(h);Object feed=item==null?null:panel.get(item);
        if(!getCurrent.getDeclaringClass().isInstance(feed))return false;
        if(getCurrent.invoke(feed)!=h){
            // The pager can change its current holder before either holder's lifecycle callback.
            if(geometry.changed()){leaving.leave(card,geometry);geometry=new Geometry();}
            return false;
        }
        Object dialogState=dialogs.get(feed);
        if(dialogState==null || !Boolean.FALSE.equals(comments.invoke(dialogState,true)))return false;
        Object work=getWork.invoke(h);
        if(!Integer.valueOf(0).equals(HostTargets.invoke(work,"getAwemeType")) || !Boolean.FALSE.equals(HostTargets.invoke(work,"isLive"))
                || !Boolean.FALSE.equals(HostTargets.invoke(work,"getAd")))return false;
        Object video=getVideo.invoke(work), player=getPlayer.invoke(h);
        if(video==null || player==null)return false;
        View render=(View)getSurface.invoke(player);
        if(!geometry.changed() && !leaving.empty())geometry=leaving.take(card);
        int sw=width.getInt(video),sh=height.getInt(video);
        boolean applied=geometry.frame(render,card,sw,sh);
        report(work,render,card,sw,sh,applied);
        return applied;
    }
    private void report(Object work,View render,View card,int sw,int sh,boolean applied){
        int cw=card.getWidth()-card.getPaddingLeft()-card.getPaddingRight();
        int ch=card.getHeight()-card.getPaddingTop()-card.getPaddingBottom();
        int rw=render==null?0:render.getWidth(),rh=render==null?0:render.getHeight();
        Geometry g=geometry;
        long shape=g.reason.hashCode();
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
                        ";scale="+(applied?g.scale:1)+";translation="+(applied?g.tx:0)+","+(applied?g.ty:0));
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

    /** Only borrow compositor properties, not layout params, native gravity, margins or surface size. */
    static final class Geometry {
        private WeakReference<View> target=new WeakReference<>(null);
        private final Matrix matrix=new Matrix();
        private float scale=1,tx,ty;
        int rawX,rawY;
        String reason="unmeasured";
        boolean changed(){return target.get()!=null && (scale!=1 || tx!=0 || ty!=0);}
        boolean frame(View render,View card,int srcW,int srcH) {
            rawX=rawY=Integer.MIN_VALUE;reason="unsupported_surface_or_clip";
            if(!(render instanceof SurfaceView || render instanceof TextureView) || !(card instanceof ViewGroup)
                    || !render.isAttachedToWindow() || !((ViewGroup)card).getClipChildren()
                    || !((ViewGroup)card).getClipToPadding() || render.getWidth()<=0 || render.getHeight()<=0)return false;
            reason="native_transform";
            if(!clean(card) || render instanceof TextureView && !((TextureView)render).getTransform(matrix).isIdentity())return false;
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
            int depth=0;reason="native_transform_or_wrong_card";
            while(v!=card && depth++<32){
                if(!clean(v) || !(v.getParent() instanceof ViewGroup))return false;
                ViewGroup p=(ViewGroup)v.getParent();
                x+=v.getLeft()-p.getScrollX();y+=v.getTop()-p.getScrollY();v=p;
            }
            if(v!=card)return false;
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
            if(target.get()!=layer){restore();target=new WeakReference<>(layer);}
            if(layer.getScaleX()!=s)layer.setScaleX(s);
            if(layer.getScaleY()!=s)layer.setScaleY(s);
            if(layer.getTranslationX()!=dx)layer.setTranslationX(dx);
            if(layer.getTranslationY()!=dy)layer.setTranslationY(dy);
            scale=s;tx=dx;ty=dy;reason=constrained?"centered_in_clip":"centered";return true;
        }
        private boolean clean(View v) {
            boolean ours=v==target.get();
            return v.getRotation()==0 && v.getRotationX()==0 && v.getRotationY()==0 && v.getAnimation()==null
                    && v.getClipBounds()==null && !v.getClipToOutline()
                    && v.getScaleX()==(ours?scale:1) && v.getScaleY()==(ours?scale:1)
                    && v.getTranslationX()==(ours?tx:0) && v.getTranslationY()==(ours?ty:0);
        }
        void restore(){
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
            if(card==null || !geometry.changed()){geometry.restore();return;}
            Lease l=new Lease(card,geometry);
            if(!l.visible()){geometry.restore();return;}
            if(list.size()==3)list.get(0).release(true);
            list.add(l);
            card.addOnAttachStateChangeListener(l);
            ViewTreeObserver tree=card.getViewTreeObserver();l.tree=new WeakReference<>(tree);
            if(tree.isAlive())tree.addOnPreDrawListener(l);else l.release(true);
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
                list.remove(this);if(restore)geometry.restore();
            }
            public boolean onPreDraw(){if(!released&&!visible())release(true);return true;}
            public void onViewAttachedToWindow(View ignored){}
            public void onViewDetachedFromWindow(View ignored){release(true);}
        }
    }
    private void leaveCurrent(){
        stopObserving();
        if(enabled&&!closed)leaving.leave(root.get(),geometry);else geometry.restore();
        geometry=new Geometry();holder.clear();root.clear();
    }
    private void unbind(){stopObserving();geometry.restore();holder.clear();root.clear();}
    private void onMain(Runnable r){if(Looper.myLooper()==Looper.getMainLooper())r.run();else ui.post(r);}
    @Override public void close(){closed=true;hooks.close();onMain(()->{unbind();leaving.clear();});}
}
