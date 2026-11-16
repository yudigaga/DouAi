package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.function.BiConsumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassMotionTest {
    @Test public void transformPreservesBaselineAndClampsPhysicalMotion() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        view.setTranslationX(17); view.setTranslationY(-4); view.setRotationX(2); view.setRotationY(3);
        LiquidGlassMotion.Transform transform = new LiquidGlassMotion.Transform(view);
        transform.apply(new LiquidGlassMotion.Vector(1, 0), 2);
        assertEquals(37f, view.getTranslationX(), .00001f);
        assertEquals(-4f, view.getTranslationY(), .00001f);
        assertEquals(2f, view.getRotationX(), .00001f);
        assertEquals(4.2f, view.getRotationY(), .00001f);
        transform.restore();
        assertEquals(17f, view.getTranslationX(), 0);
        assertEquals(3f, view.getRotationY(), 0);
        activity.finish();
    }

    @Test public void hostAnimationTakesOwnershipWithoutSubtractingOldOffsets() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        view.setTranslationX(10); view.setTranslationY(20);
        LiquidGlassMotion.Transform transform = new LiquidGlassMotion.Transform(view);
        transform.apply(new LiquidGlassMotion.Vector(.5f, .5f), 1);
        view.setTranslationX(80);
        transform.apply(new LiquidGlassMotion.Vector(1, 0), 1);
        assertEquals(80f, view.getTranslationX(), 0);
        assertEquals(20f, view.getTranslationY(), 0);
        view.setTranslationX(90);
        transform.apply(new LiquidGlassMotion.Vector(-1, 0), 1);
        transform.restore();
        assertEquals(90f, view.getTranslationX(), 0);
        assertEquals(20f, view.getTranslationY(), 0);
        activity.finish();
    }

    @Test public void disableAndTargetReplacementRestoreOnlyOwnedProperties() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View first = new View(activity), second = new View(activity);
        first.setTranslationX(11); second.setTranslationX(22);
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        motion.setTargets(List.of(first), null);
        motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
        assertTrue(first.getTranslationX() > 11);
        motion.setTargets(List.of(second), null);
        assertEquals(11f, first.getTranslationX(), 0);
        motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
        second.setRotationY(12);
        motion.setActive(false);
        assertEquals(22f, second.getTranslationX(), 0);
        assertEquals(12f, second.getRotationY(), 0);
        motion.close(); motion.close();
        assertEquals(12f, second.getRotationY(), 0);
        activity.finish();
    }

    @Test public void unsupportedSensorsLeaveExistingViewAndTouchBehaviorAlone() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View view = new View(activity);
        view.setTranslationX(9); view.setAlpha(.4f); view.setVisibility(View.INVISIBLE);
        view.setOnTouchListener((v, event) -> true);
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        motion.setTargets(List.of(view), null);
        motion.setActive(true);
        motion.setInteracting(true); motion.setInteracting(false);
        ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
        motion.close();
        assertEquals(9f, view.getTranslationX(), 0);
        assertEquals(.4f, view.getAlpha(), 0);
        assertEquals(View.INVISIBLE, view.getVisibility());
        MotionEvent touch = MotionEvent.obtain(0, 1, MotionEvent.ACTION_DOWN, 0, 0, 0);
        try { assertTrue(view.dispatchTouchEvent(touch)); } finally { touch.recycle(); }
        activity.finish();
    }

    @Test public void allWindowsShareGravitySensorAndTheLastCloseUnregistersIt() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_ACCELEROMETER));
        Sensor gravity = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        sensors.addSensor(gravity);
        LiquidGlassMotion first = new LiquidGlassMotion(activity), second = new LiquidGlassMotion(activity);
        try {
            first.setTargets(List.of(new View(activity)), null);
            second.setTargets(List.of(new View(activity)), null);
            first.setActive(true); second.setActive(true);
            assertEquals(1, sensors.getListeners().size());
            assertTrue(sensors.hasListener(sensors.getListeners().get(0), gravity));
            first.close();
            assertEquals(1, sensors.getListeners().size());
            second.close();
            assertTrue(sensors.getListeners().isEmpty());
        } finally { first.close(); second.close(); activity.finish(); }
    }

    @Test public void animationScaleZeroRestoresAndUnregistersUntilEnabledAgain() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY));
        View view = new View(activity); view.setTranslationX(10);
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        try {
            motion.setTargets(List.of(view), null); motion.setActive(true);
            motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
            Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 0);
            ShadowLooper.shadowMainLooper().idle();
            assertEquals(10f, view.getTranslationX(), 0);
            assertTrue(sensors.getListeners().isEmpty());
            Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
            ShadowLooper.shadowMainLooper().idle();
            assertEquals(1, sensors.getListeners().size());
        } finally {
            motion.close();
            Settings.Global.putFloat(activity.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1);
            activity.finish();
        }
    }

    @Test public void detachingLastTargetRestoresAndStopsItsSensorSubscription() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        sensors.addSensor(ShadowSensor.newInstance(Sensor.TYPE_GRAVITY));
        FrameLayout root = new FrameLayout(activity); View view = new View(activity);
        root.addView(view); activity.setContentView(root);
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        try {
            motion.setTargets(List.of(view), null); motion.setActive(true);
            motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
            root.removeView(view);
            assertEquals(0f, view.getTranslationX(), 0);
            assertTrue(sensors.getListeners().isEmpty());
        } finally { motion.close(); activity.finish(); }
    }

    @Test public void existingAndNewHostRotationAnimationsKeepTheSurfaceStatic() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        View busy = new View(activity); busy.setHasTransientState(true);
        LiquidGlassMotion.Transform existing = new LiquidGlassMotion.Transform(busy);
        busy.setHasTransientState(false);
        existing.apply(new LiquidGlassMotion.Vector(1, 0), 1);
        assertEquals(0f, busy.getTranslationX(), 0);
        View rotated = new View(activity);
        LiquidGlassMotion.Transform changed = new LiquidGlassMotion.Transform(rotated);
        changed.apply(new LiquidGlassMotion.Vector(1, 0), 1);
        rotated.setRotation(15);
        changed.apply(new LiquidGlassMotion.Vector(-1, 0), 1);
        assertEquals(0f, rotated.getTranslationX(), 0);
        assertEquals(15f, rotated.getRotation(), 0);
        activity.finish();
    }

    @Test public void realSensorInputMovesThenFreezesAndDisableRestoresBaseline() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        Sensor gravity = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        sensors.addSensor(gravity);
        View view = new View(activity); view.setTranslationX(9);
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        try {
            motion.setTargets(List.of(view), null); motion.setActive(true);
            SensorEvent sample = ShadowSensorManager.createSensorEvent(3);
            sample.sensor = gravity; sample.timestamp = 100_000_000L;
            sample.values[2] = 9.80665f;sensors.sendSensorEventToListeners(sample);
            sample.timestamp = 200_000_000L;
            sample.values[0] = -9.80665f; sample.values[2] = 9.80665f;
            sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            float moving = view.getTranslationX();
            float maximum = 9 + 10 * activity.getResources().getDisplayMetrics().density;
            assertTrue(moving > 9 && moving <= maximum);
            motion.setInteracting(true);
            sample.timestamp = 300_000_000L; sample.values[0] = 9.80665f;
            sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(moving, view.getTranslationX(), 0);
            motion.setInteracting(false);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(50));
            assertTrue(view.getTranslationX() < moving);
            motion.setActive(false);
            assertEquals(9f, view.getTranslationX(), 0);
            assertTrue(sensors.getListeners().isEmpty());
        } finally { motion.close(); activity.finish(); }
    }

    @Test public void settledTiltReachesAddedTargetsAndReplacementLightWithoutAnotherSensorEvent() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        Sensor gravity = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
        sensors.addSensor(gravity);
        View first = new View(activity), added = new View(activity), addedWhileInteracting = new View(activity);
        added.setTranslationX(20); addedWhileInteracting.setTranslationX(30);
        float[] firstLight = {-1}, replacementLight = {-1};
        BiConsumer<Float, Float> replacement = (x, y) -> replacementLight[0] = x;
        LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        try {
            motion.setTargets(List.of(first), (x, y) -> firstLight[0] = x);
            motion.setActive(true);
            SensorEvent sample = ShadowSensorManager.createSensorEvent(3);
            sample.sensor = gravity; sample.timestamp = 100_000_000L;
            sample.values[2] = 9.80665f;sensors.sendSensorEventToListeners(sample);
            sample.timestamp = 200_000_000L;
            sample.values[0] = -9.80665f; sample.values[2] = 9.80665f;
            sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            float displacement = 10 * activity.getResources().getDisplayMetrics().density;
            assertEquals(displacement, first.getTranslationX(), .0001f);
            assertEquals(1f, firstLight[0], .0001f);

            motion.setTargets(List.of(first, added), replacement);
            assertEquals(20 + displacement, added.getTranslationX(), .0001f);
            assertEquals(displacement, first.getTranslationX(), .0001f);
            assertEquals(1f, replacementLight[0], .0001f);

            motion.setInteracting(true);
            motion.setTargets(List.of(first, added, addedWhileInteracting), replacement);
            assertEquals(30f, addedWhileInteracting.getTranslationX(), 0);
            motion.setInteracting(false);
            assertEquals(30 + displacement, addedWhileInteracting.getTranslationX(), .0001f);
        } finally { motion.close(); activity.finish(); }
    }

    @Test public void unavailableSensorsDoNotPoisonLaterWindowSubscriptions() {
        ShadowChoreographer.setPaused(true);
        ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ShadowSensorManager sensors = sensors();
        LiquidGlassMotion unavailable = new LiquidGlassMotion(activity), recovered = new LiquidGlassMotion(activity);
        View target = new View(activity);
        try {
            unavailable.setTargets(List.of(new View(activity)), null);
            unavailable.setActive(true);
            assertTrue(sensors.getListeners().isEmpty());
            unavailable.close();

            Sensor gravity = ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);
            sensors.addSensor(gravity);
            recovered.setTargets(List.of(target), null);
            recovered.setActive(true);
            assertEquals(1, sensors.getListeners().size());
            SensorEvent sample = ShadowSensorManager.createSensorEvent(3);
            sample.sensor = gravity; sample.timestamp = 100_000_000L;
            sample.values[2] = 9.80665f;sensors.sendSensorEventToListeners(sample);
            sample.timestamp = 200_000_000L;
            sample.values[0] = -9.80665f; sample.values[2] = 9.80665f;
            sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(10 * activity.getResources().getDisplayMetrics().density, target.getTranslationX(), .0001f);
        } finally { unavailable.close(); recovered.close(); activity.finish(); }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void restingBoundsStayExactlyStableAcrossRealSensorMotionWithoutWritingTheView() {
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        try(BoundsFixture f=new BoundsFixture()) {
            ShadowSensorManager sensors=sensors();Sensor gravity=ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);sensors.addSensor(gravity);
            f.view.setTranslationX(9);f.motion.setTargets(List.of(f.view),f.root,null);
            RectF before=restingBounds(f.motion,f.view,f.root);assertBounds(before,19,20,119,60);
            f.motion.setActive(true);SensorEvent sample=ShadowSensorManager.createSensorEvent(3);
            sample.sensor=gravity;sample.timestamp=100_000_000L;sample.values[2]=9.80665f;sensors.sendSensorEventToListeners(sample);
            sample.timestamp=200_000_000L;sample.values[0]=-9.80665f;sample.values[2]=9.80665f;
            sensors.sendSensorEventToListeners(sample);ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertTrue("Real sensor input must have moved and tilted the navigation",f.view.getTranslationX()>9 && f.view.getRotationY()>0);
            int writes=f.view.writes;float translation=f.view.getTranslationX(),rotation=f.view.getRotationY();
            assertEquals(before,restingBounds(f.motion,f.view,f.root));
            assertEquals("Measuring resting geometry never writes a View property",writes,f.view.writes);
            assertEquals(translation,f.view.getTranslationX(),0);assertEquals(rotation,f.view.getRotationY(),0);
        }
    }

    @Test public void restingBoundsPreserveHostTranslationScalePivotAndZRotation() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.setPivotX(0);f.view.setPivotY(0);f.view.setScaleX(2);f.view.setScaleY(3);
            f.view.setRotation(90);f.view.setTranslationX(7);f.view.setTranslationY(-4);
            assertBounds(restingBounds(f.motion,f.view,f.root),-103,16,17,216);
            f.motion.setTargets(List.of(f.view),null);f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));
            int writes=f.view.writes;
            assertBounds(restingBounds(f.motion,f.view,f.root),-103,16,17,216);
            assertEquals(writes,f.view.writes);
        }
    }

    @Test public void restingBoundsComposeAncestorScrollAndScaleInRootCoordinates() {
        try(BoundsFixture f=new BoundsFixture()) {
            FrameLayout parent=new FrameLayout(f.activity);f.root.removeView(f.view);f.root.addView(parent);
            parent.addView(f.view);parent.layout(30,40,230,240);f.view.layout(10,20,60,40);
            parent.setPivotX(0);parent.setPivotY(0);parent.setScaleX(2);parent.setScaleY(2);
            parent.setTranslationX(4);parent.setTranslationY(-3);parent.scrollTo(5,7);f.root.scrollTo(2,3);
            f.view.setTranslationX(3);f.view.setTranslationY(-2);f.motion.setTargets(List.of(f.view),null);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(0,1));
            assertBounds(restingBounds(f.motion,f.view,f.root),48,56,148,96);
        }
    }

    @Test public void restingBoundsUseLiveHostTakeoverBeforeAndAfterMotionYields() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.setTranslationX(7);f.motion.setTargets(List.of(f.view),null);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));f.view.setTranslationX(80);
            int writes=f.view.writes;
            assertBounds(restingBounds(f.motion,f.view,f.root),90,20,190,60);
            assertEquals(writes,f.view.writes);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(-1,0));
            assertEquals(80f,f.view.getTranslationX(),0);
            assertBounds(restingBounds(f.motion,f.view,f.root),90,20,190,60);
        }
    }

    @Test public void restingBoundsStopAtTheRootWithoutItsOwnTransformOrLayoutOffset() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.root.layout(100,200,460,1000);f.root.setPivotX(0);f.root.setPivotY(0);
            f.root.setScaleX(2);f.root.setScaleY(3);f.root.setRotation(30);
            f.root.setTranslationX(70);f.root.setTranslationY(90);
            assertBounds(restingBounds(f.motion,f.view,f.root),10,20,110,60);
        }
    }

    @Test public void restingBoundsRejectHostTiltInsteadOfFlatteningIt() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.setRotationX(2);f.motion.setTargets(List.of(f.view),null);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));
            assertNull(restingBounds(f.motion,f.view,f.root));
            f.motion.setTargets(List.of(),null);f.view.setRotationX(0);f.motion.setTargets(List.of(f.view),null);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));f.view.setRotationY(4);
            assertNull("A host takeover of tilt is not a reliable 2D rectangle",restingBounds(f.motion,f.view,f.root));
        }
    }

    @Test public void restingBoundsRejectUnrelatedUnmeasuredAndSingularGeometry() {
        try(BoundsFixture f=new BoundsFixture()) {
            FrameLayout other=new FrameLayout(f.activity);other.layout(0,0,360,800);
            assertNull(restingBounds(f.motion,f.view,other));
            assertNull(restingBounds(f.motion,new View(f.activity),f.root));
            f.view.setScaleX(0);assertNull(restingBounds(f.motion,f.view,f.root));
            f.view.setScaleX(1);f.view.setTranslationX(Float.NaN);assertNull(restingBounds(f.motion,f.view,f.root));
        }
    }

    @Test public void restingBoundsRejectAnimationsTransientStateAndUnreliableAncestors() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.setHasTransientState(true);assertNull(restingBounds(f.motion,f.view,f.root));f.view.setHasTransientState(false);
            AlphaAnimation animation=new AlphaAnimation(0,1);animation.setDuration(1000);f.view.setAnimation(animation);
            assertNull(restingBounds(f.motion,f.view,f.root));f.view.clearAnimation();
            FrameLayout parent=new FrameLayout(f.activity);f.root.removeView(f.view);f.root.addView(parent);parent.addView(f.view);
            parent.layout(0,0,200,100);f.view.layout(10,20,110,60);
            parent.setRotationY(1);assertNull(restingBounds(f.motion,f.view,f.root));
            parent.setRotationY(0);parent.setScaleY(0);assertNull(restingBounds(f.motion,f.view,f.root));
            parent.setScaleY(1);parent.setHasTransientState(true);assertNull(restingBounds(f.motion,f.view,f.root));
            parent.setHasTransientState(false);parent.setAnimation(new AlphaAnimation(0,1));
            assertNull(restingBounds(f.motion,f.view,f.root));
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void restingBoundsStripMotionOwnedAncestorsForObservedProgressDescendants() {
        try(BoundsFixture f=new BoundsFixture()) {
            FrameLayout carrier=new FrameLayout(f.activity);f.root.removeView(f.view);
            f.root.addView(carrier,new FrameLayout.LayoutParams(200,200));carrier.addView(f.view,new FrameLayout.LayoutParams(100,40));
            carrier.layout(50,80,250,280);f.view.layout(10,20,110,60);
            RectF before=f.motion.restingBounds(f.view,f.root);assertBounds(before,60,100,160,140);
            f.motion.setTargets(List.of(carrier),f.root,null);f.motion.applyFrame(new LiquidGlassMotion.Vector(1,1));
            assertTrue(carrier.getTranslationX()>0 && carrier.getRotationX()!=0);
            assertEquals(before,f.motion.restingBounds(f.view,f.root));
            f.motion.applyFrame(new LiquidGlassMotion.Vector(-1,-1));
            assertEquals(before,f.motion.restingBounds(f.view,f.root));
        }
    }

    @Test public void restingBoundsAndCarrierMotionIgnoreTransientStateInheritedFromAnUnrelatedChild() {
        try(BoundsFixture f=new BoundsFixture()) {
            FrameLayout carrier=new FrameLayout(f.activity);f.root.removeView(f.view);f.root.addView(carrier);
            carrier.addView(f.view);View thumb=new View(f.activity);carrier.addView(thumb);
            carrier.layout(50,80,250,280);f.view.layout(10,20,110,60);thumb.layout(150,150,160,160);
            thumb.setHasTransientState(true);assertTrue(carrier.hasTransientState());
            assertBounds(f.motion.restingBounds(f.view,f.root),60,100,160,140);
            f.motion.setTargets(List.of(carrier),null);f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));
            assertTrue("An unrelated thumb must not freeze its whole carrier",carrier.getTranslationX()>0);
            assertBounds(f.motion.restingBounds(f.view,f.root),60,100,160,140);
            thumb.setHasTransientState(false);
        }
    }

    @Test public void largerMotionCalibratesOnceAcrossRefreshFreezesOnTouchAndRecentersAfterReactivation() {
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();View view=new View(activity);
        LiquidGlassMotion motion=new LiquidGlassMotion(activity);ShadowSensorManager sensors=sensors();
        Sensor gravity=ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);sensors.addSensor(gravity);
        float g=9.80665f,s=.34202015f*g,c=.9396926f*g;
        try {
            motion.setTargets(List.of(view),null);motion.setActive(true);
            send(sensors,gravity,100_000_000L,0,g,0);send(sensors,gravity,200_000_000L,-s,c,0);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));assertEquals(10f,view.getTranslationX(),.001f);
            for(int i=0;i<4;i++){motion.setTargets(List.of(view),null);motion.setActive(true);}
            send(sensors,gravity,300_000_000L,-s,c,0);ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
            assertEquals("A repeated active refresh must retain the same reference",10f,view.getTranslationX(),.001f);
            motion.setInteracting(true);send(sensors,gravity,400_000_000L,s,c,0);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));assertEquals(10f,view.getTranslationX(),.001f);
            motion.setInteracting(false);assertEquals("UP starts from the frozen pose",10f,view.getTranslationX(),.001f);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(100));assertTrue(view.getTranslationX()<10 && view.getTranslationX()>-10);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));assertEquals(-10f,view.getTranslationX(),.001f);
            motion.setActive(false);assertEquals(0f,view.getTranslationX(),0);motion.setActive(true);
            send(sensors,gravity,500_000_000L,-s,c,0);ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(1));
            assertEquals("The new activation centers on the current holding pose",0f,view.getTranslationX(),.001f);
            send(sensors,gravity,600_000_000L,0,g,0);ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(-10f,view.getTranslationX(),.001f);
        }finally{motion.close();activity.finish();}
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void touchDefersTheLatestMembershipSnapshotAndItsLightUntilRelease() {
        ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
        try(BoundsFixture f=new BoundsFixture()) {
            View second=new View(f.activity);FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(100,40);
            params.leftMargin=200;params.topMargin=100;f.root.addView(second,params);
            second.layout(200,100,300,140);ShadowSensorManager sensors=sensors();
            Sensor gravity=ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);sensors.addSensor(gravity);
            float[] oldLight={0},newLight={-2};
            f.motion.setTargets(List.of(f.view),f.root,(x,y)->oldLight[0]=x);f.motion.setActive(true);
            send(sensors,gravity,100_000_000L,0,0,9.80665f);send(sensors,gravity,200_000_000L,-9.80665f,0,9.80665f);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));assertEquals(10f,f.view.getTranslationX(),.001f);
            f.motion.setInteracting(true);ArrayList<View> latest=new ArrayList<>(List.of(second));
            f.motion.setTargets(latest,f.root,(x,y)->newLight[0]=x);latest.clear();f.motion.setActive(true);
            assertEquals("Refreshing membership during DOWN cannot move the pressed target",10f,f.view.getTranslationX(),.001f);
            assertEquals(0f,second.getTranslationX(),0);assertEquals(1f,oldLight[0],.001f);assertEquals(-2f,newLight[0],0);
            f.motion.setInteracting(false);
            assertEquals(0f,f.view.getTranslationX(),0);assertEquals(10f,second.getTranslationX(),.001f);
            assertEquals("UP applies the saved latest callback",1f,newLight[0],.001f);
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void boundedMotionAllowsVisibleTenDpMovementInEveryDirectionForOrdinaryControls() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.layout(100,100,200,140);boundedTargets(f.motion,List.of(f.view),f.root);
            for(float[] direction:new float[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                f.motion.applyFrame(new LiquidGlassMotion.Vector(direction[0],direction[1]));
                assertEquals(direction[0]*10,f.view.getTranslationX(),.001f);assertEquals(direction[1]*10,f.view.getTranslationY(),.001f);
                assertTrue(Math.abs(f.view.getRotationX())<=1.2001f && Math.abs(f.view.getRotationY())<=1.2001f);
                assertInside(actualBounds(f.view,f.root),new RectF(0,0,360,800));
            }
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void boundedMotionKeepsAnEdgeIconAndAFullWidthProgressCarrierInsideTheWindow() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.layout(300,100,360,140);boundedTargets(f.motion,List.of(f.view),f.root);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));assertTrue(f.view.getTranslationX()<=0);
            assertInside(actualBounds(f.view,f.root),new RectF(0,0,360,800));
            f.motion.applyFrame(new LiquidGlassMotion.Vector(-1,0));assertEquals(-10f,f.view.getTranslationX(),.001f);
            f.motion.setTargets(List.of(),null);f.view.layout(0,700,360,712);boundedTargets(f.motion,List.of(f.view),f.root);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,1));
            assertEquals("A full-width carrier cannot move sideways",0f,f.view.getTranslationX(),.001f);
            assertTrue("The same carrier can still follow vertical movement",f.view.getTranslationY()>5 && f.view.getTranslationY()<=10);
            assertInside(actualBounds(f.view,f.root),new RectF(0,0,360,800));
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void boundedMotionPreservesScreenPhaseAcrossScaledParentsAndRespectsParentPadding() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.layout(100,100,200,140);FrameLayout parent=new FrameLayout(f.activity);f.root.addView(parent);
            parent.layout(20,20,220,220);parent.setPivotX(0);parent.setPivotY(0);parent.setScaleX(2);parent.setScaleY(2);
            View second=new View(f.activity);parent.addView(second);second.layout(20,20,70,40);
            boundedTargets(f.motion,List.of(f.view,second),f.root);f.motion.applyFrame(new LiquidGlassMotion.Vector(1,0));
            assertEquals(10f,f.view.getTranslationX(),.001f);assertEquals("Parent scale must not double the on-screen displacement",5f,second.getTranslationX(),.001f);
            f.motion.setTargets(List.of(),null);parent.setScaleX(1);parent.setScaleY(1);parent.layout(50,50,210,150);parent.setPadding(10,10,10,10);
            second.layout(10,10,110,50);boundedTargets(f.motion,List.of(second),f.root);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(-1,-1));assertInside(actualBounds(second,f.root),new RectF(60,60,200,140));
            assertEquals(0f,second.getTranslationX(),.001f);assertEquals(0f,second.getTranslationY(),.001f);
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void boundedMotionLimitsTheAdditionalTiltFootprintToOneDp() {
        try(BoundsFixture f=new BoundsFixture()) {
            f.view.layout(40,200,320,360);boundedTargets(f.motion,List.of(f.view),f.root);
            f.motion.applyFrame(new LiquidGlassMotion.Vector(1,1));
            assertTrue(f.view.getRotationX()!=0 && f.view.getRotationY()!=0);
            RectF tilted=actualBounds(f.view,f.root);tilted.offset(-f.view.getTranslationX(),-f.view.getTranslationY());
            assertInside(tilted,new RectF(39,199,321,361));
        }
    }

    private static void boundedTargets(LiquidGlassMotion motion,List<View> views,View root) {
        motion.setTargets(views,root,null);
    }
    private static RectF actualBounds(View view,View root) {
        Matrix matrix=new Matrix(),rootMatrix=new Matrix(),inverse=new Matrix();view.transformMatrixToGlobal(matrix);root.transformMatrixToGlobal(rootMatrix);
        assertTrue(rootMatrix.invert(inverse));matrix.postConcat(inverse);RectF bounds=new RectF(0,0,view.getWidth(),view.getHeight());matrix.mapRect(bounds);return bounds;
    }
    private static void assertInside(RectF inner,RectF outer) {
        assertTrue(inner+" must remain inside "+outer,inner.left>=outer.left-.02f && inner.top>=outer.top-.02f && inner.right<=outer.right+.02f && inner.bottom<=outer.bottom+.02f);
    }
    private static void send(ShadowSensorManager sensors,Sensor sensor,long time,float x,float y,float z) {
        SensorEvent event=ShadowSensorManager.createSensorEvent(3);event.sensor=sensor;event.timestamp=time;event.values[0]=x;event.values[1]=y;event.values[2]=z;sensors.sendSensorEventToListeners(event);
    }

    private static RectF restingBounds(LiquidGlassMotion motion,View view,View root) {
        return motion.restingBounds(view,root);
    }
    private static void assertBounds(RectF bounds,float left,float top,float right,float bottom) {
        assertNotNull(bounds);assertArrayEquals(new float[]{left,top,right,bottom},
                new float[]{bounds.left,bounds.top,bounds.right,bounds.bottom},.001f);
    }
    private static final class BoundsFixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final CountingView view=new CountingView(activity);
        final LiquidGlassMotion motion=new LiquidGlassMotion(activity);
        BoundsFixture(){
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(100,40);params.leftMargin=10;params.topMargin=20;
            root.addView(view,params);activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
            root.layout(0,0,360,800);
        }
        @Override public void close(){motion.close();activity.finish();}
    }
    private static final class CountingView extends View {
        int writes;
        CountingView(Activity activity){super(activity);}
        @Override public void setTranslationX(float value){writes++;super.setTranslationX(value);}
        @Override public void setTranslationY(float value){writes++;super.setTranslationY(value);}
        @Override public void setRotationX(float value){writes++;super.setRotationX(value);}
        @Override public void setRotationY(float value){writes++;super.setRotationY(value);}
        @Override public void setRotation(float value){writes++;super.setRotation(value);}
        @Override public void setScaleX(float value){writes++;super.setScaleX(value);}
        @Override public void setScaleY(float value){writes++;super.setScaleY(value);}
    }

    private static ShadowSensorManager sensors() {
        return Shadow.extract((SensorManager) RuntimeEnvironment.getApplication().getSystemService(Context.SENSOR_SERVICE));
    }
}
