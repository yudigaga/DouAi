package com.douai.module.feature;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationSelectionFeedbackTest {
    private static final String WINDOWS_VISIBLE="robolectric.areWindowsMarkedVisible";
    private String originalScale;
    private String originalWindowsVisible;

    @Before public void enableAnimations() {
        originalWindowsVisible=System.getProperty(WINDOWS_VISIBLE);
        // Legacy graphics otherwise omits WindowManager's APP_VISIBLE flag in Robolectric 4.14.1.
        System.setProperty(WINDOWS_VISIBLE,"true");
        originalScale=Settings.Global.getString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE);
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        durationScale(1f);
    }

    @After public void restoreAnimations() {
        try {
            Settings.Global.putString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,originalScale);
            durationScale(1f);
        } finally {
            if(originalWindowsVisible==null)System.clearProperty(WINDOWS_VISIBLE);
            else System.setProperty(WINDOWS_VISIBLE,originalWindowsVisible);
        }
    }

    @Test public void firstObservationPreservesNativeForegroundStateAndClickGeometry() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();
            s.owner.setForegroundGravity(Gravity.CENTER);
            s.owner.setForeground(original);
            s.owner.setTranslationX(3f);s.owner.setTranslationY(-2f);
            s.owner.setScaleX(.96f);s.owner.setScaleY(.94f);s.owner.setAlpha(.7f);
            int[] clicks={0};s.owner.setOnClickListener(v->clicks[0]++);
            s.draw();Rect nativeBounds=new Rect(original.getBounds());
            assertArrayEquals(s.owner.getDrawableState(),original.getState());
            // Installing the click listener can focus the owner and refresh its native state.
            original.setState(new int[]{android.R.attr.state_pressed});
            assertArrayEquals(new int[]{android.R.attr.state_pressed},original.getState());
            assertSame(s.owner,original.getCallback());
            Object params=s.owner.getLayoutParams();
            s.feedback.update(true,Color.CYAN);
            assertEquals(1f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
            LayerDrawable layer=(LayerDrawable)s.owner.getForeground();
            assertSame(original,layer.getDrawable(0));
            assertArrayEquals(new int[]{android.R.attr.state_pressed},original.getState());
            assertSame(layer,original.getCallback());
            s.draw();assertEquals(nativeBounds,original.getBounds());
            assertArrayEquals(new int[]{android.R.attr.state_pressed},original.getState());
            assertTrue(s.owner.performClick());assertEquals(1,clicks[0]);
            assertSame(params,s.owner.getLayoutParams());
            assertEquals(120,s.owner.getWidth());assertEquals(60,s.owner.getHeight());
            assertEquals(3f,s.owner.getTranslationX(),0f);assertEquals(-2f,s.owner.getTranslationY(),0f);
            assertEquals(.96f,s.owner.getScaleX(),0f);assertEquals(.94f,s.owner.getScaleY(),0f);assertEquals(.7f,s.owner.getAlpha(),0f);
            s.feedback.close();
            assertSame(original,s.owner.getForeground());assertSame(s.owner,original.getCallback());
            assertArrayEquals(new int[]{android.R.attr.state_pressed},original.getState());
            assertNull(layer.getDrawable(0));assertNull(layer.getDrawable(1));
        }
    }

    @Test public void sameTargetKeepsItsAnimationAndReverseStartsAtCurrentProgress() {
        try(Scene s=new Scene()) {
            s.feedback.update(false,Color.WHITE);
            Drawable wrapper=s.owner.getForeground();
            s.feedback.update(true,Color.WHITE);
            ValueAnimator entering=animation(s.feedback);
            assertNotNull(entering);assertEquals(160,entering.getDuration());
            entering.setCurrentPlayTime(80);
            float middle=s.feedback.progress();assertTrue(middle>0f && middle<1f);
            s.feedback.update(true,Color.YELLOW);
            assertSame(entering,animation(s.feedback));assertEquals(middle,s.feedback.progress(),0f);
            s.feedback.update(false,Color.YELLOW);
            ValueAnimator leaving=animation(s.feedback);
            assertNotSame(entering,leaving);assertFalse(entering.isStarted());
            assertEquals(middle,s.feedback.progress(),.001f);
            // A cancelled animator's old completion must not finish its replacement.
            entering.end();assertSame(leaving,animation(s.feedback));
            leaving.end();assertEquals(0f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
            assertSame(wrapper,s.owner.getForeground());
        }
    }

    @Test public void staticSyncDoesNotReplaceForegroundOrCreateAnAnimator() {
        try(Scene s=new Scene()) {
            s.feedback.update(true,Color.WHITE);Drawable wrapper=s.owner.getForeground();
            for(int i=0;i<25;i++)s.feedback.update(true,Color.WHITE);
            assertSame(wrapper,s.owner.getForeground());assertNull(animation(s.feedback));
            assertEquals(1f,s.feedback.progress(),0f);
        }
    }

    @Test public void disabledSystemPreferenceFinishesRunningAnimationOnNextSync() {
        try(Scene s=new Scene()) {
            s.feedback.update(false,Color.WHITE);s.feedback.update(true,Color.WHITE);
            animation(s.feedback).setCurrentPlayTime(40);assertTrue(s.feedback.progress()<1f);
            Settings.Global.putFloat(s.activity.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0f);
            s.feedback.update(true,Color.WHITE);
            assertEquals(1f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
            s.feedback.update(false,Color.WHITE);
            assertEquals(0f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
        }
    }

    @Test public void disabledPlatformAnimatorAlsoUsesTheFinalState() {
        try(Scene s=new Scene()) {
            s.feedback.update(false,Color.WHITE);durationScale(0f);
            s.feedback.update(true,Color.WHITE);
            assertEquals(1f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
        }
    }

    @Test public void animationFramesOnlyInvalidateAndLeaveLayoutUntouched() {
        try(Scene s=new Scene()) {
            s.feedback.update(false,Color.WHITE);s.layout();
            s.owner.requests=0;s.owner.invalidations=0;
            s.feedback.update(true,Color.WHITE);
            ValueAnimator animator=animation(s.feedback);
            animator.setCurrentPlayTime(40);animator.setCurrentPlayTime(80);animator.end();
            assertEquals(0,s.owner.requests);
            assertTrue(s.owner.invalidations>0);
            assertEquals(120,s.owner.getWidth());assertEquals(60,s.owner.getHeight());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void centeredNativeForegroundKeepsItsBoundsAndClearsTheWholeOverlayDirtyArea() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();
            s.owner.setForegroundGravity(Gravity.CENTER);s.owner.setForeground(original);
            s.draw();Rect nativeBounds=new Rect(original.getBounds());
            s.feedback.update(true,Color.WHITE);
            Bitmap bitmap=s.draw();
            assertEquals(nativeBounds,original.getBounds());
            Drawable wrapper=s.owner.getForeground();
            assertTrue(wrapper.getDirtyBounds().contains(0,0,120,60));
            assertEquals(28,Color.alpha(bitmap.getPixel(20,20)));
            s.owner.lastDirty=null;
            s.feedback.update(false,Color.WHITE);animation(s.feedback).end();
            assertEquals(0f,s.feedback.progress(),0f);
            assertNotNull(s.owner.lastDirty);
            assertTrue(s.owner.lastDirty.contains(0,0,120,60));
            assertTrue(wrapper.getDirtyBounds().contains(0,0,120,60));
            Canvas redraw=new Canvas(bitmap);
            redraw.clipRect(s.owner.lastDirty);
            redraw.drawColor(Color.TRANSPARENT,PorterDuff.Mode.CLEAR);
            s.owner.draw(redraw);
            assertEquals(0,Color.alpha(bitmap.getPixel(20,20)));
            assertEquals(nativeBounds,original.getBounds());
            assertEquals(Gravity.CENTER,s.owner.getForegroundGravity());
        }
    }

    @Test public void detachStopsRestoresAndReattachBindsWithoutAnEntrance() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();s.owner.setForeground(original);
            s.feedback.update(false,Color.WHITE);s.feedback.update(true,Color.WHITE);
            LayerDrawable old=(LayerDrawable)s.owner.getForeground();
            s.root.removeView(s.owner);
            assertSame(original,s.owner.getForeground());assertFalse(s.feedback.isRunning());
            assertNull(old.getDrawable(0));assertNull(old.getDrawable(1));
            s.root.addView(s.owner,new FrameLayout.LayoutParams(120,60));s.layout();s.preDraw();
            assertTrue(s.owner.getForeground() instanceof LayerDrawable);
            assertEquals(1f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
        }
    }

    @Test public void hiddenOwnerRestoresAndShowingItUsesTheLastObservedTarget() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();s.owner.setForeground(original);
            s.feedback.update(false,Color.WHITE);s.feedback.update(true,Color.WHITE);
            s.owner.setVisibility(View.INVISIBLE);
            assertSame(original,s.owner.getForeground());assertFalse(s.feedback.isRunning());
            s.preDraw();assertSame(original,s.owner.getForeground());
            s.feedback.update(false,Color.CYAN);assertSame(original,s.owner.getForeground());
            s.owner.setVisibility(View.VISIBLE);s.preDraw();
            assertTrue(s.owner.getForeground() instanceof LayerDrawable);
            assertEquals(0f,s.feedback.progress(),0f);assertFalse(s.feedback.isRunning());
        }
    }

    @Test public void hostReplacementBecomesTheNewBaselineAndOldChildrenAreReleased() {
        try(Scene s=new Scene()) {
            NativeForeground first=new NativeForeground(),replacement=new NativeForeground();
            s.owner.setForeground(first);s.feedback.update(true,Color.WHITE);
            LayerDrawable old=(LayerDrawable)s.owner.getForeground();
            s.owner.setForeground(replacement);
            replacement.setState(new int[]{android.R.attr.state_activated});
            s.feedback.update(true,Color.WHITE);
            LayerDrawable current=(LayerDrawable)s.owner.getForeground();
            assertNotSame(old,current);assertSame(replacement,current.getDrawable(0));
            assertNull(old.getDrawable(0));assertNull(old.getDrawable(1));assertNull(first.getCallback());
            assertArrayEquals(new int[]{android.R.attr.state_activated},replacement.getState());
            s.feedback.close();assertSame(replacement,s.owner.getForeground());
            assertSame(s.owner,replacement.getCallback());
            assertArrayEquals(new int[]{android.R.attr.state_activated},replacement.getState());
        }
    }

    @Test public void closeLeavesAHostReusedNativeDrawableCallbackAndNeverRebinds() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();s.owner.setForeground(original);
            s.feedback.update(true,Color.WHITE);
            LayerDrawable old=(LayerDrawable)s.owner.getForeground();
            s.owner.setForeground(original);
            s.feedback.close();
            assertSame(original,s.owner.getForeground());assertSame(s.owner,original.getCallback());
            assertNull(old.getDrawable(0));assertNull(old.getDrawable(1));
            s.root.removeView(s.owner);s.root.addView(s.owner,new FrameLayout.LayoutParams(120,60));
            s.layout();s.preDraw();s.feedback.update(false,Color.WHITE);
            assertSame(original,s.owner.getForeground());assertFalse(s.feedback.isRunning());
        }
    }

    @Test public void closeDuringAnimationRestoresAnOriginallyEmptyForegroundPermanently() {
        try(Scene s=new Scene()) {
            assertNull(s.owner.getForeground());
            s.feedback.update(false,Color.WHITE);s.feedback.update(true,Color.WHITE);
            ValueAnimator pending=animation(s.feedback);
            LayerDrawable old=(LayerDrawable)s.owner.getForeground();
            assertNotNull(pending);
            s.feedback.close();
            assertNull(s.owner.getForeground());assertFalse(s.feedback.isRunning());
            assertFalse(pending.isStarted());
            assertNull(old.getDrawable(0));assertNull(old.getDrawable(1));
            pending.end();s.preDraw();s.feedback.update(true,Color.CYAN);
            assertNull(s.owner.getForeground());assertFalse(s.feedback.isRunning());
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void hostTintAndColorFilterDoNotRecolorTheOwnedHighlight() {
        try(Scene s=new Scene()) {
            NativeForeground original=new NativeForeground();s.owner.setForeground(original);
            ColorStateList tint=ColorStateList.valueOf(Color.RED);
            s.owner.setForegroundTintList(tint);s.owner.setForegroundTintMode(PorterDuff.Mode.SRC_IN);
            s.feedback.update(true,Color.BLUE);
            s.owner.getForeground().setColorFilter(Color.RED,PorterDuff.Mode.SRC_IN);
            Bitmap bitmap=s.draw();int center=bitmap.getPixel(60,30);
            assertEquals(0,Color.red(center));assertEquals(255,Color.blue(center));
            assertEquals(28,Color.alpha(center));
            assertSame(tint,s.owner.getForegroundTintList());assertEquals(PorterDuff.Mode.SRC_IN,s.owner.getForegroundTintMode());
        }
    }

    private static ValueAnimator animation(NavigationSelectionFeedback feedback) {
        return ReflectionHelpers.getField(feedback,"animator");
    }

    private static void durationScale(float value) {
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,value));
    }

    private static final class Scene implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final CountingOwner owner=new CountingOwner(activity);
        final NavigationSelectionFeedback feedback=new NavigationSelectionFeedback(owner);
        Scene() {
            root.addView(owner,new FrameLayout.LayoutParams(120,60));activity.setContentView(root);
            ShadowLooper.shadowMainLooper().idle();layout();
            assertTrue(owner.isAttachedToWindow());assertTrue(owner.isShown());
            View decor=activity.getWindow().getDecorView();
            String diagnostic="decor{"+visibility(decor)+"}, root{"+visibility(root)+"}, owner{"+visibility(owner)+"}";
            assertEquals(diagnostic,View.VISIBLE,decor.getWindowVisibility());
            assertEquals(diagnostic,View.VISIBLE,root.getWindowVisibility());
            assertEquals(diagnostic,View.VISIBLE,owner.getWindowVisibility());
        }
        private static String visibility(View view){return "attached="+view.isAttachedToWindow()+", shown="+view.isShown()+", visibility="+view.getVisibility()+", window="+view.getWindowVisibility();}
        void layout() {
            root.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));
            root.layout(0,0,300,300);
        }
        void preDraw(){owner.getViewTreeObserver().dispatchOnPreDraw();}
        Bitmap draw(){Bitmap bitmap=Bitmap.createBitmap(120,60,Bitmap.Config.ARGB_8888);owner.draw(new Canvas(bitmap));return bitmap;}
        @Override public void close(){feedback.close();activity.finish();}
    }

    private static final class CountingOwner extends FrameLayout {
        int requests,invalidations;
        Rect lastDirty;
        CountingOwner(Context context){super(context);}
        @Override public void requestLayout(){requests++;super.requestLayout();}
        @Override public void invalidateDrawable(Drawable drawable){invalidations++;lastDirty=new Rect(drawable.getDirtyBounds());super.invalidateDrawable(drawable);}
    }

    private static final class NativeForeground extends Drawable {
        @Override public boolean isStateful(){return true;}
        @Override public int getIntrinsicWidth(){return 18;}
        @Override public int getIntrinsicHeight(){return 12;}
        @Override public void draw(Canvas canvas){}
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter colorFilter){}
        @Override public int getOpacity(){return PixelFormat.TRANSPARENT;}
    }
}
