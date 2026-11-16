package com.douai.module.feature;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import java.util.ArrayList;

/** One non-interactive overlay per real navigation container; host views never move. */
final class NavigationSelectionIndicator implements AutoCloseable {
    private static final long DURATION_MS=260;
    private final ViewGroup host;
    private final IndicatorDrawable drawable=new IndicatorDrawable();
    private final ArrayList<View> path=new ArrayList<>();
    private final RectF current=new RectF(),from=new RectF(),to=new RectF(),measured=new RectF(),hostBounds=new RectF();
    private final Matrix mapping=new Matrix();
    private View target;
    private ViewTreeObserver observer;
    private ValueAnimator animator;
    private Drawable.Callback installedCallback;
    private long generation;
    private boolean closed,installed,lost;
    private int layoutLeft,layoutTop,layoutWidth,layoutHeight;
    private float fraction;
    private final ViewTreeObserver.OnPreDrawListener drawListener=()->{reconcile(false);return true;};
    private final View.OnAttachStateChangeListener attachListener=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view){
            if(lost && drawable.getCallback()!=null)return;
            lost=false;observe();reconcile(false);
        }
        @Override public void onViewDetachedFromWindow(View view){stopObserving();suspend();}
    };

    NavigationSelectionIndicator(ViewGroup host) {
        this.host=java.util.Objects.requireNonNull(host);
        host.addOnAttachStateChangeListener(attachListener);
        if(host.isAttachedToWindow())observe();
    }

    void update(View selected,int color) {
        if(closed || lost || !ownershipIntact())return;
        boolean changed=target!=selected;
        target=selected;
        drawable.color(color);
        if(changed)path.clear();
        reconcile(changed);
    }

    boolean isRunning(){return animator!=null;}
    RectF bounds(){return installed?new RectF(current):new RectF();}

    private void reconcile(boolean changed) {
        if(closed || lost || !ownershipIntact())return;
        if(target==null){suspend();return;}
        boolean rebuilt=!validPath();
        if(rebuilt && !buildPath()) {
            // A removed/replaced item must not stay strongly retained by the old navigation group.
            target=null;path.clear();suspend();return;
        }
        if(!renderable()){suspend();return;}
        measureTarget();
        hostBounds.set(0,0,host.getWidth(),host.getHeight());
        if(!finite(measured) || measured.isEmpty() || !RectF.intersects(measured,hostBounds)) {
            suspend();return;
        }
        boolean resized=drawable.getBounds().width()!=host.getWidth() || drawable.getBounds().height()!=host.getHeight();
        boolean layoutChanged=layoutLeft!=target.getLeft() || layoutTop!=target.getTop()
                || layoutWidth!=target.getWidth() || layoutHeight!=target.getHeight();
        layoutLeft=target.getLeft();layoutTop=target.getTop();layoutWidth=target.getWidth();layoutHeight=target.getHeight();
        if(resized)drawable.setBounds(0,0,host.getWidth(),host.getHeight());
        if(!installed) {
            host.getOverlay().add(drawable);installedCallback=drawable.getCallback();installed=true;
            settle();return;
        }
        if(resized || !changed && (rebuilt || layoutChanged)){settle();return;}
        if(changed) {
            if(!transitionsEnabled() || current.equals(measured)){settle();return;}
            animate();
        } else if(animator!=null && !ValueAnimator.areAnimatorsEnabled()) {
            settle();
        } else if(!to.equals(measured)) {
            // Relative motion updates the live destination without resetting cross-item travel.
            to.set(measured);
            if(animator!=null)interpolate();else {from.set(to);current.set(to);drawable.invalidateSelf();}
        }
    }

    private boolean renderable() {
        return target!=null && host.isAttachedToWindow() && host.getWindowVisibility()==View.VISIBLE && host.isShown()
                && host.getWidth()>0 && host.getHeight()>0 && target.isAttachedToWindow()
                && target.getWindowVisibility()==View.VISIBLE && target.isShown()
                && target.getWidth()>0 && target.getHeight()>0;
    }

    private boolean transitionsEnabled() {
        if(!ValueAnimator.areAnimatorsEnabled())return false;
        try {
            return Settings.Global.getFloat(host.getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
        }catch(RuntimeException unavailable){return false;}
    }

    private boolean buildPath() {
        path.clear();View child=target;
        for(int depth=0;child!=host && depth<64;depth++) {
            if(child==null){path.clear();return false;}
            path.add(child);ViewParent parent=child.getParent();
            if(!(parent instanceof View)){path.clear();return false;}
            child=(View)parent;
        }
        if(child!=host || path.isEmpty()){path.clear();return false;}
        return true;
    }

    private boolean validPath() {
        if(path.isEmpty() || path.get(0)!=target)return false;
        for(int i=0;i<path.size();i++) {
            View expected=i+1<path.size()?path.get(i+1):host;
            if(path.get(i).getParent()!=expected)return false;
        }
        return true;
    }

    private void measureTarget() {
        measured.set(target.getWidth()*.04f,target.getHeight()*.08f,target.getWidth()*.96f,target.getHeight()*.92f);
        mapping.reset();
        for(int i=0;i<path.size();i++) {
            View child=path.get(i);View parent=i+1<path.size()?path.get(i+1):host;
            Matrix matrix=child.getMatrix();if(!matrix.isIdentity())mapping.postConcat(matrix);
            mapping.postTranslate(child.getLeft()-parent.getScrollX(),child.getTop()-parent.getScrollY());
        }
        // Map once: successive axis-aligned bounding boxes inflate nested rotations.
        mapping.mapRect(measured);
    }

    private void settle() {
        cancelAnimation();fraction=1f;to.set(measured);from.set(measured);current.set(measured);drawable.invalidateSelf();
    }

    private void animate() {
        cancelAnimation();fraction=0f;from.set(current);to.set(measured);
        final long ticket=generation;
        ValueAnimator next=ValueAnimator.ofFloat(0f,1f);next.setDuration(DURATION_MS);
        next.addUpdateListener(animation->{
            if(closed || animator!=animation || generation!=ticket)return;
            if(!ownershipIntact())return;
            if(!renderable()) {
                if(host.isAttachedToWindow() && target!=null && !target.isAttachedToWindow()){target=null;path.clear();}
                suspend();return;
            }
            if(!ValueAnimator.areAnimatorsEnabled()){settle();return;}
            fraction=(float)animation.getAnimatedValue();interpolate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(closed || animator!=animation || generation!=ticket)return;
                if(!ownershipIntact())return;
                animator=null;fraction=1f;current.set(to);drawable.invalidateSelf();
            }
        });
        animator=next;next.start();
    }

    private void cancelAnimation() {
        generation++;ValueAnimator previous=animator;animator=null;
        if(previous!=null){previous.removeAllUpdateListeners();previous.removeAllListeners();previous.cancel();}
    }

    private void suspend() {
        cancelAnimation();
        boolean owned=installed && drawable.getCallback()==installedCallback;
        if(installed && !owned){lost=true;target=null;path.clear();}
        installed=false;installedCallback=null;
        if(owned)host.getOverlay().remove(drawable);
        current.setEmpty();from.setEmpty();to.setEmpty();measured.setEmpty();hostBounds.setEmpty();mapping.reset();fraction=0f;
        layoutLeft=layoutTop=layoutWidth=layoutHeight=0;
    }

    private boolean ownershipIntact() {
        if(!installed || drawable.getCallback()==installedCallback)return true;
        lost=true;suspend();target=null;path.clear();stopObserving();return false;
    }

    private void interpolate() {
        current.set(lerp(from.left,to.left,fraction),lerp(from.top,to.top,fraction),
                lerp(from.right,to.right,fraction),lerp(from.bottom,to.bottom,fraction));
        drawable.invalidateSelf();
    }

    private void observe() {
        if(closed)return;
        ViewTreeObserver next=host.getViewTreeObserver();
        if(observer==next)return;stopObserving();
        if(next.isAlive()){observer=next;observer.addOnPreDrawListener(drawListener);}
    }

    private void stopObserving() {
        if(observer!=null && observer.isAlive())observer.removeOnPreDrawListener(drawListener);
        observer=null;
    }

    @Override public void close() {
        if(closed)return;closed=true;
        host.removeOnAttachStateChangeListener(attachListener);stopObserving();suspend();target=null;path.clear();
    }

    private static float lerp(float start,float end,float fraction){return start+(end-start)*fraction;}
    private static boolean finite(RectF rect){return Float.isFinite(rect.left) && Float.isFinite(rect.top) && Float.isFinite(rect.right) && Float.isFinite(rect.bottom);}

    private final class IndicatorDrawable extends Drawable {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        void color(int color){int next=(color&0x00ffffff)|(28<<24);if(paint.getColor()!=next){paint.setColor(next);invalidateSelf();}}
        @Override public void draw(Canvas canvas) {
            if(!installed || getCallback()!=installedCallback || current.isEmpty())return;
            int save=canvas.save();canvas.clipRect(getBounds());
            canvas.drawRoundRect(current,current.height()*.32f,current.height()*.32f,paint);canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
