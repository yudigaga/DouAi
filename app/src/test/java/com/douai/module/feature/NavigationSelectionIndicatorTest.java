package com.douai.module.feature;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk={28,35})
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationSelectionIndicatorTest {
    private static final String WINDOWS_VISIBLE="robolectric.areWindowsMarkedVisible";
    private String previousWindows,previousSetting;
    private float previousScale;

    @Before public void setup() {
        previousWindows=System.getProperty(WINDOWS_VISIBLE);
        previousSetting=Settings.Global.getString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE);
        previousScale=ReflectionHelpers.callStaticMethod(ValueAnimator.class,"getDurationScale");
        System.setProperty(WINDOWS_VISIBLE,"true");
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        scale(1f);
    }
    @After public void restore() {
        try {
            Settings.Global.putString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,previousSetting);
            scale(previousScale);
        } finally {
            if(previousWindows==null)System.clearProperty(WINDOWS_VISIBLE);
            else System.setProperty(WINDOWS_VISIBLE,previousWindows);
        }
    }

    @Test public void firstObservationSettlesAndLeftToRightActuallyCrossesTheGap() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.BLUE);
            assertFalse(s.indicator.isRunning());assertRect(expected(s.left,s.host),s.indicator.bounds());
            float from=s.indicator.bounds().centerX();
            s.indicator.update(s.right,Color.BLUE);ValueAnimator moving=animation(s.indicator);
            assertNotNull(moving);assertEquals(260,moving.getDuration());moving.setCurrentPlayTime(130);
            float middle=s.indicator.bounds().centerX();
            assertTrue(middle>from+30);assertTrue(middle<expected(s.right,s.host).centerX()-30);
            moving.end();assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test public void reversingStartsAtTheCurrentRectangleAndOldCompletionCannotWin() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);
            ValueAnimator old=animation(s.indicator);old.setCurrentPlayTime(100);RectF interrupted=s.indicator.bounds();
            s.indicator.update(s.left,Color.CYAN);ValueAnimator reverse=animation(s.indicator);
            assertNotSame(old,reverse);assertRect(interrupted,s.indicator.bounds());assertFalse(old.isStarted());
            old.end();assertSame(reverse,animation(s.indicator));assertRect(interrupted,s.indicator.bounds());
            reverse.end();assertRect(expected(s.left,s.host),s.indicator.bounds());
        }
    }
    @Test public void repeatedUpdatesKeepOneDrawableAndDoNotRestartAnActiveAnimation() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);Object drawable=ReflectionHelpers.getField(s.indicator,"drawable");
            s.indicator.update(s.right,Color.WHITE);ValueAnimator moving=animation(s.indicator);moving.setCurrentPlayTime(80);
            RectF middle=s.indicator.bounds();int requests=s.host.requests;
            for(int i=0;i<30;i++){s.indicator.update(s.right,Color.YELLOW);s.preDraw();}
            assertSame(drawable,ReflectionHelpers.getField(s.indicator,"drawable"));assertSame(moving,animation(s.indicator));
            assertRect(middle,s.indicator.bounds());assertEquals(requests,s.host.requests);
        }
    }
    @Test public void foregroundClicksAndAllViewGeometryRemainOwnedByTheHost() {
        try(Scene s=new Scene()) {
            Drawable foreground=new ColorDrawable(Color.RED);s.host.setForeground(foreground);
            int[] clicks={0};s.right.setOnClickListener(v->clicks[0]++);
            s.host.setTranslationX(7);s.host.setTranslationY(-3);s.host.setScaleX(.9f);s.host.setAlpha(.7f);
            Object params=s.right.getLayoutParams();RectF geometry=new RectF(s.right.getLeft(),s.right.getTop(),s.right.getRight(),s.right.getBottom());
            s.indicator.update(s.left,Color.WHITE);int requests=s.host.requests;
            s.indicator.update(s.right,Color.WHITE);animation(s.indicator).setCurrentPlayTime(130);
            assertSame(foreground,s.host.getForeground());assertSame(s.host,foreground.getCallback());
            assertTrue(s.right.performClick());assertEquals(1,clicks[0]);assertSame(params,s.right.getLayoutParams());
            assertRect(geometry,new RectF(s.right.getLeft(),s.right.getTop(),s.right.getRight(),s.right.getBottom()));
            assertEquals(7,s.host.getTranslationX(),0);assertEquals(-3,s.host.getTranslationY(),0);assertEquals(.9f,s.host.getScaleX(),0);assertEquals(.7f,s.host.getAlpha(),0);
            assertEquals(requests,s.host.requests);s.indicator.close();assertSame(foreground,s.host.getForeground());
        }
    }
    @Test public void anotherHostAndItsOverlayAreUnaffected() {
        try(Scene s=new Scene()) {
            FrameLayout other=new FrameLayout(s.activity);s.root.addView(other,new FrameLayout.LayoutParams(300,60));
            View child=new View(s.activity);other.addView(child,new FrameLayout.LayoutParams(60,40));s.layout();
            Drawable unrelated=new ColorDrawable(Color.RED);unrelated.setBounds(0,0,5,5);s.host.getOverlay().add(unrelated);
            try(NavigationSelectionIndicator second=new NavigationSelectionIndicator(other)) {
                s.indicator.update(s.left,Color.BLUE);second.update(child,Color.GREEN);RectF otherBounds=second.bounds();
                s.indicator.update(s.right,Color.BLUE);s.indicator.close();assertRect(otherBounds,second.bounds());assertFalse(second.isRunning());
                assertNotNull(unrelated.getCallback());s.host.getOverlay().remove(unrelated);
            }
        }
    }
    @Test public void parentMotionCancelsOutAndItemMatrixAndScrollMapIntoTheOverlay() {
        try(Scene s=new Scene()) {
            s.host.setTranslationX(17);s.host.setTranslationY(-9);s.host.setRotation(8);s.host.setScaleX(.8f);
            s.right.setPivotX(0);s.right.setPivotY(0);s.right.setRotation(20);s.right.setTranslationX(-4);s.host.scrollTo(6,2);
            s.indicator.update(s.right,Color.WHITE);assertRect(expected(s.right,s.host),s.indicator.bounds());
            RectF local=s.indicator.bounds();s.host.setTranslationX(29);s.preDraw();assertRect(local,s.indicator.bounds());assertFalse(s.indicator.isRunning());
            s.right.setTranslationX(11);s.preDraw();assertRect(expected(s.right,s.host),s.indicator.bounds());assertFalse(s.indicator.isRunning());
        }
    }
    @Test public void intermediateParentTransformIsAppliedInTheRightOrder() {
        try(Scene s=new Scene()) {
            FrameLayout nested=new FrameLayout(s.activity);FrameLayout.LayoutParams box=new FrameLayout.LayoutParams(100,50);box.leftMargin=80;box.topMargin=5;
            s.host.addView(nested,box);View target=new View(s.activity);FrameLayout.LayoutParams item=new FrameLayout.LayoutParams(40,30);item.leftMargin=8;item.topMargin=4;nested.addView(target,item);s.layout();
            nested.setPivotX(0);nested.setPivotY(0);nested.setScaleX(1.2f);nested.setRotation(20);nested.setTranslationX(3);nested.scrollTo(2,1);target.setTranslationY(2);target.setRotation(-15);
            s.indicator.update(target,Color.WHITE);assertRect(expected(target,s.host),s.indicator.bounds());
        }
    }
    @Test public void layoutChangeSafelySettlesWithoutRestartingAtEveryGeometryTick() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);ValueAnimator old=animation(s.indicator);old.setCurrentPlayTime(90);
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)s.right.getLayoutParams();params.leftMargin=180;s.right.setLayoutParams(params);s.layout();s.preDraw();
            assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
            old.end();assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test public void movingItemTransformsAndScrollRetargetTheLiveEndpointWithoutRestartingTheClock() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);RectF origin=s.indicator.bounds();
            s.indicator.update(s.right,Color.WHITE);ValueAnimator moving=animation(s.indicator);
            s.right.setPivotX(0);s.right.setPivotY(0);
            for(int i=1;i<=4;i++) {
                moving.setCurrentPlayTime(30L*i);
                s.right.setTranslationX(-3f*i);s.right.setRotation(2f*i);s.host.scrollTo(i,0);s.preDraw();
                assertSame(moving,animation(s.indicator));assertTrue(s.indicator.isRunning());
                RectF end=expected(s.right,s.host);assertRect(end,ReflectionHelpers.getField(s.indicator,"to"));
                float f=(float)moving.getAnimatedValue();
                assertRect(new RectF(origin.left+(end.left-origin.left)*f,origin.top+(end.top-origin.top)*f,
                        origin.right+(end.right-origin.right)*f,origin.bottom+(end.bottom-origin.bottom)*f),s.indicator.bounds());
            }
            moving.end();assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test public void zeroSystemScaleSettlesAndPositiveScaleIsNotMultipliedTwice() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);scale(2f);s.indicator.update(s.right,Color.WHITE);
            assertEquals(260,animation(s.indicator).getDuration());scale(0f);s.preDraw();
            assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
            s.indicator.update(s.left,Color.WHITE);assertFalse(s.indicator.isRunning());assertRect(expected(s.left,s.host),s.indicator.bounds());
        }
    }
    @Test public void aGlobalOnlyZeroPreferencePreventsANewMovementEvenBeforeThePlatformFlagUpdates() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);
            Settings.Global.putFloat(s.activity.getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0f);
            scale(1f);assertTrue(ValueAnimator.areAnimatorsEnabled());
            s.indicator.update(s.right,Color.WHITE);
            assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test public void anAnimationCallbackSuspendsHiddenOrDetachedTargetsWithoutWaitingForPreDraw() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);ValueAnimator hidden=animation(s.indicator);
            s.host.setVisibility(View.INVISIBLE);hidden.setCurrentPlayTime(100);
            assertFalse(s.indicator.isRunning());assertFalse(hidden.isStarted());assertTrue(s.indicator.bounds().isEmpty());
            s.host.setVisibility(View.VISIBLE);s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);
            ValueAnimator removed=animation(s.indicator);s.host.removeView(s.right);removed.setCurrentPlayTime(100);
            assertFalse(s.indicator.isRunning());assertFalse(removed.isStarted());assertTrue(s.indicator.bounds().isEmpty());
            assertNull(ReflectionHelpers.getField(s.indicator,"target"));
        }
    }
    @Test public void hiddenWindowDetachAndReentryRememberTheTargetWithoutAnEntranceAnimation() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);ValueAnimator old=animation(s.indicator);
            s.host.setVisibility(View.INVISIBLE);s.preDraw();assertTrue(s.indicator.bounds().isEmpty());assertFalse(s.indicator.isRunning());
            old.end();assertTrue(s.indicator.bounds().isEmpty());s.host.setVisibility(View.VISIBLE);s.preDraw();
            assertRect(expected(s.right,s.host),s.indicator.bounds());assertFalse(s.indicator.isRunning());
            s.root.removeView(s.host);assertTrue(s.indicator.bounds().isEmpty());s.root.addView(s.host,new FrameLayout.LayoutParams(300,60));s.layout();s.preDraw();
            assertRect(expected(s.right,s.host),s.indicator.bounds());assertFalse(s.indicator.isRunning());
        }
    }
    @Test public void nullForeignOrRemovedTargetSuspendAndCloseNeverReinstallsAnything() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(null,Color.WHITE);s.preDraw();assertTrue(s.indicator.bounds().isEmpty());
            s.indicator.update(s.right,Color.WHITE);s.host.removeView(s.right);s.preDraw();assertTrue(s.indicator.bounds().isEmpty());
            s.indicator.update(new View(s.activity),Color.WHITE);assertTrue(s.indicator.bounds().isEmpty());
            s.indicator.update(s.left,Color.WHITE);Drawable drawable=ReflectionHelpers.getField(s.indicator,"drawable");assertNotNull(drawable.getCallback());
            s.indicator.close();s.indicator.close();s.preDraw();s.indicator.update(s.left,Color.WHITE);
            assertTrue(s.indicator.bounds().isEmpty());assertNull(drawable.getCallback());assertNull(ReflectionHelpers.getField(s.indicator,"observer"));
        }
    }
    @Test public void closingDuringMovementReleasesTheOverlayAndRejectsItsCancelledCallback() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);
            ValueAnimator old=animation(s.indicator);old.setCurrentPlayTime(100);
            View.OnAttachStateChangeListener queuedAttach=ReflectionHelpers.getField(s.indicator,"attachListener");
            Drawable drawable=ReflectionHelpers.getField(s.indicator,"drawable");s.indicator.close();
            assertFalse(old.isStarted());assertNull(drawable.getCallback());assertTrue(s.indicator.bounds().isEmpty());
            old.end();queuedAttach.onViewAttachedToWindow(s.host);s.preDraw();s.indicator.update(s.left,Color.WHITE);
            assertFalse(s.indicator.isRunning());assertNull(drawable.getCallback());assertTrue(s.indicator.bounds().isEmpty());
            assertNull(ReflectionHelpers.getField(s.indicator,"observer"));
        }
    }
    @Test public void aHiddenWindowSuspendsAndItsVisibleReturnSettlesAtTheRememberedTarget() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);
            s.host.forcedWindowVisibility=View.GONE;s.preDraw();
            assertFalse(s.indicator.isRunning());assertTrue(s.indicator.bounds().isEmpty());
            s.host.forcedWindowVisibility=View.VISIBLE;s.preDraw();
            assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test public void hostOverlayClearCancelsAndDoesNotReinstallUntilTheHostReattaches() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);ValueAnimator old=animation(s.indicator);
            Drawable drawable=ReflectionHelpers.getField(s.indicator,"drawable");s.host.getOverlay().clear();s.preDraw();
            assertFalse(s.indicator.isRunning());assertFalse(old.isStarted());assertTrue(s.indicator.bounds().isEmpty());
            assertNull(drawable.getCallback());assertNull(ReflectionHelpers.getField(s.indicator,"target"));assertNull(ReflectionHelpers.getField(s.indicator,"observer"));
            old.end();for(int i=0;i<3;i++){s.indicator.update(s.right,Color.WHITE);s.preDraw();}
            assertNull(drawable.getCallback());assertTrue(s.indicator.bounds().isEmpty());
            s.root.removeView(s.host);s.root.addView(s.host,new FrameLayout.LayoutParams(300,60));s.layout();s.indicator.update(s.right,Color.WHITE);
            assertNotNull(drawable.getCallback());assertFalse(s.indicator.isRunning());assertRect(expected(s.right,s.host),s.indicator.bounds());
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void losingTheCallbackToAnotherOverlayDoesNotLetCloseStealItsNewOwner() {
        try(Scene s=new Scene()) {
            FrameLayout other=new FrameLayout(s.activity);s.root.addView(other,new FrameLayout.LayoutParams(300,60));s.layout();
            s.indicator.update(s.left,Color.WHITE);s.indicator.update(s.right,Color.WHITE);ValueAnimator old=animation(s.indicator);
            Drawable drawable=ReflectionHelpers.getField(s.indicator,"drawable");Drawable.Callback original=drawable.getCallback();
            other.getOverlay().add(drawable);Drawable.Callback transferred=drawable.getCallback();assertNotNull(transferred);assertNotSame(original,transferred);
            RectF stale=s.indicator.bounds();Bitmap foreignFrame=Bitmap.createBitmap(300,60,Bitmap.Config.ARGB_8888);
            try {drawable.draw(new Canvas(foreignFrame));assertEquals(0,Color.alpha(foreignFrame.getPixel((int)stale.centerX(),(int)stale.centerY())));}
            finally {foreignFrame.recycle();}
            s.preDraw();assertFalse(s.indicator.isRunning());assertTrue(s.indicator.bounds().isEmpty());assertSame(transferred,drawable.getCallback());
            s.root.removeView(s.host);s.root.addView(s.host,new FrameLayout.LayoutParams(300,60));s.layout();s.indicator.update(s.left,Color.WHITE);
            assertSame(transferred,drawable.getCallback());assertTrue(s.indicator.bounds().isEmpty());
            s.indicator.close();old.end();s.indicator.update(s.left,Color.WHITE);assertSame(transferred,drawable.getCallback());
            other.getOverlay().remove(drawable);
        }
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE) public void theSharedDrawableActuallyPaintsTheIntermediatePosition() {
        try(Scene s=new Scene()) {
            s.indicator.update(s.left,Color.BLUE);s.indicator.update(s.right,Color.BLUE);animation(s.indicator).setCurrentPlayTime(130);
            RectF middle=s.indicator.bounds();Bitmap bitmap=Bitmap.createBitmap(300,60,Bitmap.Config.ARGB_8888);
            try {s.host.draw(new Canvas(bitmap));assertEquals(28,Color.alpha(bitmap.getPixel((int)middle.centerX(),(int)middle.centerY())));assertEquals(255,Color.blue(bitmap.getPixel((int)middle.centerX(),(int)middle.centerY())));}
            finally {bitmap.recycle();}
        }
    }

    private static void scale(float value){ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,value));}
    private static ValueAnimator animation(NavigationSelectionIndicator indicator){return ReflectionHelpers.getField(indicator,"animator");}
    private static void assertRect(RectF expected,RectF actual){assertEquals(expected.left,actual.left,.02f);assertEquals(expected.top,actual.top,.02f);assertEquals(expected.right,actual.right,.02f);assertEquals(expected.bottom,actual.bottom,.02f);}
    private static RectF expected(View target,FrameLayout host) {
        RectF rect=new RectF(target.getWidth()*.04f,target.getHeight()*.08f,target.getWidth()*.96f,target.getHeight()*.92f);
        Matrix targetGlobal=new Matrix(),hostGlobal=new Matrix(),inverse=new Matrix();
        // Use the framework's independent global transform as the geometry oracle on both cached SDKs.
        ReflectionHelpers.callInstanceMethod(target,"transformMatrixToGlobal",ReflectionHelpers.ClassParameter.from(Matrix.class,targetGlobal));
        ReflectionHelpers.callInstanceMethod(host,"transformMatrixToGlobal",ReflectionHelpers.ClassParameter.from(Matrix.class,hostGlobal));
        assertTrue(hostGlobal.invert(inverse));targetGlobal.postConcat(inverse);targetGlobal.mapRect(rect);
        return rect;
    }
    private static final class Scene implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final CountingHost host=new CountingHost(activity);
        final View left=new View(activity),right=new View(activity);
        final NavigationSelectionIndicator indicator=new NavigationSelectionIndicator(host);
        Scene() {
            root.addView(host,new FrameLayout.LayoutParams(300,60));add(left,10);add(right,230);activity.setContentView(root);
            ShadowLooper.shadowMainLooper().idle();layout();
            assertTrue(host.isAttachedToWindow());assertTrue(host.isShown());assertEquals(View.VISIBLE,host.getWindowVisibility());
        }
        private void add(View child,int x){FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(60,40);params.leftMargin=x;params.topMargin=10;host.addView(child,params);}
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY));root.layout(0,0,400,300);}
        void preDraw(){host.getViewTreeObserver().dispatchOnPreDraw();}
        @Override public void close(){indicator.close();activity.finish();}
    }
    private static final class CountingHost extends FrameLayout {
        int requests,forcedWindowVisibility=-1;
        CountingHost(Context context){super(context);}
        @Override public void requestLayout(){requests++;super.requestLayout();}
        @Override public int getWindowVisibility(){return forcedWindowVisibility>=0?forcedWindowVisibility:super.getWindowVisibility();}
    }
}
