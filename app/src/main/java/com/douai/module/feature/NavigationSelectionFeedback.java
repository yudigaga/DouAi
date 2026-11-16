package com.douai.module.feature;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.provider.Settings;
import android.view.View;
import android.view.ViewTreeObserver;

/** A reversible selection overlay; the host continues to own interaction and layout. */
final class NavigationSelectionFeedback {
    private static final long DURATION_MS=160;
    private final View owner;
    private final FeedbackDrawable feedback;
    private ViewTreeObserver observer;
    private Drawable original;
    private LayerDrawable wrapper;
    private ValueAnimator animator;
    private boolean closed,observed,initialized,selected;
    private int color;
    private float progress;
    private final View.OnAttachStateChangeListener attachListener=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {
            observeDrawing();
            if(observed)update(selected,color);
        }
        @Override public void onViewDetachedFromWindow(View view) {
            stopObservingDrawing();
            suspend();
        }
    };
    private final ViewTreeObserver.OnPreDrawListener drawListener=()-> {
        if(!renderable())suspend();
        else if(observed && !initialized)update(selected,color);
        return true;
    };
    NavigationSelectionFeedback(View owner) {
        this.owner=java.util.Objects.requireNonNull(owner);
        feedback=new FeedbackDrawable(owner);
        owner.addOnAttachStateChangeListener(attachListener);
        if(owner.isAttachedToWindow())observeDrawing();
    }

    void update(boolean selected,int color) {
        if(closed)return;
        boolean changed=initialized && this.selected!=selected;
        this.selected=selected;this.color=color;observed=true;
        if(!renderable()){suspend();return;}
        bindForeground();
        feedback.color(color);
        float target=selected?1f:0f;
        if(!initialized) {
            initialized=true;cancelAnimation();setProgress(target);return;
        }
        // Idle syncs must not poll global settings or restart the same transition.
        if(!changed && animator==null)return;
        if(!animationsEnabled()) {
            cancelAnimation();setProgress(target);return;
        }
        if(changed)animateTo(target);
    }

    void close() {
        if(closed)return;
        closed=true;
        owner.removeOnAttachStateChangeListener(attachListener);
        stopObservingDrawing();
        suspend();
    }

    float progress(){return progress;}
    boolean isRunning(){return animator!=null;}

    private boolean renderable() {
        return !closed && owner.isAttachedToWindow() && owner.getWindowVisibility()==View.VISIBLE && owner.isShown();
    }

    private boolean animationsEnabled() {
        if(!ValueAnimator.areAnimatorsEnabled())return false;
        try {
            return Settings.Global.getFloat(owner.getContext().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f)>0f;
        } catch(RuntimeException unavailable) {
            return false;
        }
    }

    private void animateTo(float target) {
        cancelAnimation();
        if(progress==target)return;
        ValueAnimator next=ValueAnimator.ofFloat(progress,target);
        next.setDuration(DURATION_MS);
        next.addUpdateListener(animation->{
            if(animator!=animation || closed)return;
            if(!renderable()){suspend();return;}
            setProgress((float)animation.getAnimatedValue());
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(animator!=animation)return;
                animator=null;
                setProgress(target);
            }
        });
        animator=next;
        next.start();
    }

    private void cancelAnimation() {
        ValueAnimator previous=animator;
        animator=null;
        if(previous!=null) {
            previous.removeAllUpdateListeners();
            previous.removeAllListeners();
            previous.cancel();
        }
    }

    private void setProgress(float value) {
        progress=Math.max(0f,Math.min(1f,value));
        feedback.progress(progress);
    }

    private void bindForeground() {
        if(wrapper!=null && owner.getForeground()==wrapper)return;
        cancelAnimation();
        releaseForeground();
        initialized=false;
        original=owner.getForeground();
        Drawable base=original==null?new ColorDrawable(Color.TRANSPARENT):original;
        ForegroundState state=new ForegroundState(base);
        LayerDrawable next=new FeedbackLayer(base);
        wrapper=next;
        owner.setForeground(next);
        // View clears the outgoing child's callback after LayerDrawable has adopted it.
        if(base.getCallback()==null)base.setCallback(next);
        state.restore(base);
    }

    private void suspend() {
        cancelAnimation();
        initialized=false;
        setProgress(0f);
        releaseForeground();
    }

    private void releaseForeground() {
        LayerDrawable previous=wrapper;
        if(previous==null)return;
        Drawable baseline=original;
        boolean owned=owner.getForeground()==previous;
        ForegroundState state=owned && baseline!=null?new ForegroundState(baseline):null;
        wrapper=null;original=null;
        releaseChild(previous,0);
        releaseChild(previous,1);
        if(owned) {
            owner.setForeground(baseline);
            if(state!=null)state.restore(baseline);
        }
    }

    private static void releaseChild(LayerDrawable layer,int index) {
        Drawable child=layer.getDrawable(index);
        if(child==null)return;
        Drawable.Callback callback=child.getCallback();
        layer.setDrawable(index,null);
        // The host may already have reused this drawable outside our old wrapper.
        if(callback!=null && callback!=layer && child.getCallback()==null)child.setCallback(callback);
    }

    private void observeDrawing() {
        if(closed)return;
        ViewTreeObserver current=owner.getViewTreeObserver();
        if(current==observer)return;
        stopObservingDrawing();
        if(current.isAlive()){observer=current;current.addOnPreDrawListener(drawListener);}
    }

    private void stopObservingDrawing() {
        if(observer!=null && observer.isAlive())observer.removeOnPreDrawListener(drawListener);
        observer=null;
    }

    private static final class ForegroundState {
        private final int[] state;
        private final boolean visible;
        ForegroundState(Drawable drawable){state=drawable.getState().clone();visible=drawable.isVisible();}
        void restore(Drawable drawable){drawable.setState(state);drawable.setVisible(visible,false);}
    }

    private final class FeedbackLayer extends LayerDrawable {
        private final Rect dirtyBounds=new Rect();
        FeedbackLayer(Drawable base){super(new Drawable[]{base,feedback});}
        @Override public Rect getDirtyBounds() {
            dirtyBounds.set(super.getDirtyBounds());
            // The overlay spans the owner even when foreground gravity centers a small native image.
            dirtyBounds.union(0,0,owner.getWidth(),owner.getHeight());
            return dirtyBounds;
        }
        @Override public boolean setVisible(boolean visible,boolean restart) {
            boolean changed=super.setVisible(visible,restart);
            // Aggregated view/window visibility reaches the foreground before the next draw.
            if(!visible && wrapper==this && !renderable())suspend();
            return changed;
        }
    }

    private static final class FeedbackDrawable extends Drawable {
        private final View owner;
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF shape=new RectF();
        private int color;
        private float progress;
        FeedbackDrawable(View owner){this.owner=owner;}
        void color(int value){if(color!=value){color=value;invalidateSelf();}}
        void progress(float value){if(progress!=value){progress=value;invalidateSelf();}}
        @Override public void draw(Canvas canvas) {
            if(progress<=0f)return;
            int width=owner.getWidth(),height=owner.getHeight();
            if(width<=0 || height<=0)return;
            float insetX=width*(.04f+.04f*(1f-progress));
            float insetY=height*(.08f+.08f*(1f-progress));
            shape.set(insetX,insetY,width-insetX,height-insetY);
            paint.setColor((color&0x00ffffff)|(Math.round(28f*progress)<<24));
            int save=canvas.save();
            canvas.clipRect(0,0,width,height);
            canvas.drawRoundRect(shape,height*.32f,height*.32f,paint);
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public void setTint(int tint){}
        @Override public void setTintList(ColorStateList tint){}
        @Override public void setTintMode(PorterDuff.Mode mode){}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
