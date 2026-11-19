package com.douai.module.feature;

import android.app.Activity;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.Transformation;
import android.view.animation.TranslateAnimation;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import androidx.appcompat.widget.AppCompatImageView;
import com.bytedance.highperformanceview.hpview.HPFrameLayout;
import com.ss.android.ugc.aweme.feed.danmaku.view.DanmakuVisibilityFrameLayout;
import com.ss.android.ugc.aweme.feed.widget.DiggAnimationLayout;
import com.ss.android.ugc.aweme.feed.widget.DiggAnimationView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** Synthetic recovery contracts; these fixtures do not identify the animation in a device recording. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, qualifiers = "w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassNavigationRecoveryTest {
    private static final int CONTROL_ID = 0x7f0a2da7;
    private static final Duration STEP = Duration.ofMillis(120);

    @Test public void recycledLikeRendererKeepsTheBottomCapsuleAndTimelineClear() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            LikeAction like = new LikeAction(scene);
            MonitorState state = enable(scene, false);
            LiquidGlassController.Binding binding = state.binding();
            int width = scene.navigation.getWidth(), bottom = scene.bounds(scene.navigation).bottom;
            assertTrue(width < state.root.getWidth());
            for (int page = 1; page <= 3; page++) {
                // Swap the cached child as each page binds its own renderer.
                like.replaceImage();
                scene.forceInitialLayout();
                scene.layout();
                like.reportUnlaid();
                assertNotNull(state.motion.restingBounds(like.action, state.root));
                assertNull(state.motion.restingBounds(like.icon, state.root));
                state.clearance.discover(LiquidGlassTargets.nodes(state.root));
                pumpFrames(scene, state, 5);
                assertEquals(0, fallbackCount(scene));
                assertFalse(state.rejected().containsKey(scene.navigation));
                assertSame(binding, state.binding());
                assertEquals(width, scene.navigation.getWidth());
                assertEquals(bottom, scene.bounds(scene.navigation).bottom);
                assertEquals(72, scene.navigation.getHeight());
                assertTrue(scene.bounds(like.action).bottom <= scene.bounds(scene.navigation).top - 3);
                scene.assertClearance(3);
                scene.click(like.frame);
                assertEquals(page, like.clicks);
                assertFixedPoint(scene, state, binding, scene.navigation.getBackground());
            }
            assertBusinessRoutes(scene);
            scene.controller.setOptions(LiquidGlassClearanceControllerTest.Scene.options(false, false));
            scene.layout();scene.assertNativeMargins();
            assertEquals(58, scene.navigation.getHeight());
            assertEquals(state.root.getWidth(), scene.navigation.getWidth());
            assertSame(state.nativeBackground, scene.navigation.getBackground());
        }
    }

    @Test public void missingLikeClipKeepsFallbackUntilItsActualBoundaryReturns() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            LikeAction like = new LikeAction(scene);
            MonitorState state = enable(scene, false);
            like.reportUnlaid();
            like.clip.setClipChildren(false);
            Fallback fallback = driveToFallback(scene, state);
            assertFixedPoint(scene, state, fallback.binding, fallback.background);
            like.clip.setClipChildren(true);
            assertNull("Restoring the clip alone does not lay out the renderer",
                    state.motion.restingBounds(like.icon, state.root));
            recoverByOwnScan(scene, state, fallback);
            assertBusinessRoutes(scene);
        }
    }

    @Test public void unlaidCurrentPageDanmakuIconReservesTheClippedFrameWithoutRevertingTheCapsule() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            MonitorState state = enable(scene, false);
            LiquidGlassController.Binding binding = state.binding();
            int originalMargin = scene.carrierParams.bottomMargin;
            action.clip.setTranslationY(70);
            assertTrue(scene.bounds(action.frame).bottom > scene.bounds(scene.navigation).top);
            for (int pass = 0; pass < 5; pass++) {
                scene.layout();
                action.clearLayoutFlag();
                assertNull(state.motion.restingBounds(action.icon, state.root));
                state.preDraw();
            }
            scene.layout();
            assertEquals(scene.logs.toString(), 0, fallbackCount(scene));
            assertSame(binding, state.binding());
            assertEquals(72, scene.navigation.getHeight());
            assertTrue(scene.carrierParams.bottomMargin > originalMargin);
            assertTrue(scene.bounds(action.frame).bottom <= scene.bounds(scene.navigation).top - 14);
            scene.assertClearance(3);
            scene.click(action.frame);
            assertEquals(1, action.clicks);
        }
    }

    @Test public void unlaidDanmakuIconWithoutAClippingParentStillRevertsTheCapsule() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            MonitorState state = enable(scene, false);
            action.clip.setClipChildren(false);
            action.clearLayoutFlag();
            state.preDraw();scene.layout();
            captureFallback(scene, state);
        }
    }

    @Test public void recycledDanmakuSubtreeReservesItsFrameAfterAFeedRebind() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            MonitorState state = enable(scene, false);
            LiquidGlassController.Binding binding = state.binding();
            int initialMargin = scene.carrierParams.bottomMargin;
            action.clip.setTranslationY(70);
            assertTrue(scene.bounds(action.frame).bottom > scene.bounds(scene.navigation).top);

            for (int pass = 0; pass < 5; pass++) {
                scene.layout();
                action.clearSubtreeLayoutFlags();
                assertNull(state.motion.restingBounds(action.frame, state.root));
                assertNull(state.motion.restingBounds(action.icon, state.root));
                state.preDraw();
            }
            scene.layout();
            assertEquals(scene.logs.toString(), 0, fallbackCount(scene));
            assertSame(binding, state.binding());
            assertEquals(72, scene.navigation.getHeight());
            assertTrue(scene.carrierParams.bottomMargin > initialMargin);
            assertTrue(scene.bounds(action.frame).bottom <= scene.bounds(scene.navigation).top - 14);
            scene.assertClearance(3);
            scene.click(action.frame);
            assertEquals(1, action.clicks);
        }
    }

    @Test public void unlaidDanmakuClipRetainsItsOwnPaintAndTouchBoundary() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            action.clip.setOnClickListener(view -> action.clicks++);
            MonitorState state = enable(scene, false);
            LiquidGlassController.Binding binding = state.binding();
            int initialMargin = scene.carrierParams.bottomMargin;
            action.clip.setTranslationY(70);
            assertTrue(scene.bounds(action.clip).bottom > scene.bounds(scene.navigation).top);

            for (int pass = 0; pass < 5; pass++) {
                scene.layout();
                action.clearClipLayoutFlag();
                assertNull(state.motion.restingBounds(action.clip, state.root));
                state.preDraw();
            }
            scene.layout();

            assertEquals(scene.logs.toString(), 0, fallbackCount(scene));
            assertSame(binding, state.binding());
            assertEquals(72, scene.navigation.getHeight());
            assertTrue(scene.carrierParams.bottomMargin > initialMargin);
            assertTrue(scene.bounds(action.clip).bottom <= scene.bounds(scene.navigation).top - 14);
            scene.assertClearance(3);
            scene.click(action.clip);
            assertEquals(1, action.clicks);
        }
    }

    @Test public void recycledDanmakuSubtreeWithAnExpandedTouchBoundaryStillReverts() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            MonitorState state = enable(scene, false);
            action.clip.setTouchDelegate(new android.view.TouchDelegate(
                    new android.graphics.Rect(0, 0, 120, 120), action.frame));
            action.clearSubtreeLayoutFlags();
            state.preDraw();
            scene.layout();
            captureFallback(scene, state);
        }
    }

    @Test public void recycledDanmakuSubtreeWithASingularMountStillReverts() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            DanmakuAction action = new DanmakuAction(scene);
            MonitorState state = enable(scene, false);
            action.mount.setScaleX(0);
            action.clearSubtreeLayoutFlags();
            state.preDraw();
            scene.layout();
            captureFallback(scene, state);
        }
    }

    @Test public void clippedLikeActionCanRequireMoreAvoidanceThanItsUnclippedRightColumn() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            LikeAction like = new LikeAction(scene);
            scene.feed.setClipChildren(false);
            scene.right.setClipChildren(false);
            like.animation.setBackgroundColor(0x33000000);
            MonitorState state = enable(scene, false);
            int originalMargin = scene.carrierParams.bottomMargin;
            assertTrue(scene.bounds(scene.right).bottom <= scene.bounds(scene.navigation).top - 14);
            like.clip.setTranslationY(100);
            like.reportUnlaid();
            assertTrue(scene.bounds(like.action).bottom > scene.bounds(scene.navigation).top);
            pumpFrames(scene, state, 5);
            assertEquals(0, fallbackCount(scene));
            assertTrue(scene.carrierParams.bottomMargin > originalMargin);
            assertTrue(scene.bounds(like.action).bottom <= scene.bounds(scene.navigation).top - 14);
            scene.assertClearance(3);
        }
    }

    @Test public void rediscoveredValidRulesReleaseARejectionWithTheSameLiveFooterBounds() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            scene.progressParams.bottomMargin = 120;
            scene.forceInitialLayout();
            MonitorState state = enable(scene, false);
            assertEquals(0, scene.carrierParams.bottomMargin);
            View dormant = scene.feed.findViewById(0x7f0a6b90);
            assertEquals(View.GONE, dormant.getVisibility());
            RelativeLayout.LayoutParams rules = (RelativeLayout.LayoutParams) dormant.getLayoutParams();
            rules.addRule(RelativeLayout.ABOVE, scene.carrier.getId());
            state.preDraw();scene.layout();state.preDraw();
            LiquidGlassController.Binding binding = state.binding();
            assertTrue(binding.clearanceFallback);
            assertEquals(58, scene.navigation.getHeight());
            assertTrue(scene.logs.stream().anyMatch(line -> line.contains("navigation_clearance_fallback=")
                    && line.contains("reason=structure")));
            Fallback fallback = new Fallback(binding, scene.navigation.getBackground(), fallbackCount(scene),
                    state.rejected().get(scene.navigation));
            state.clearance.discover(LiquidGlassTargets.nodes(state.root));
            recoverByOwnScan(scene, state, fallback);
            assertBusinessRoutes(scene);
        }
    }

    @Test public void retainedAnimationStatesStayGuardedUntilExplicitlyCleared() throws Exception {
        for (boolean fillAfter : new boolean[]{false, true}) {
            for (boolean ended : new boolean[]{false, true}) {
                String scenario = "fillAfter=" + fillAfter + ";ended=" + ended;
                try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
                    AppCompatImageView control = addControl(scene);
                    MonitorState state = enable(scene, false);
                    assertEquals(72, scene.navigation.getHeight());
                    TranslateAnimation animation = new TranslateAnimation(0, 12, 0, 7);
                    animation.setDuration(100);
                    animation.setFillAfter(fillAfter);
                    control.setAnimation(animation);
                    animation.initialize(control.getWidth(), control.getHeight(),
                            scene.metadataOwner.getWidth(), scene.metadataOwner.getHeight());
                    animation.setStartTime(0);
                    Transformation transformation = new Transformation();
                    animation.getTransformation(0, transformation);
                    if (ended) animation.getTransformation(101, transformation);
                    assertSame("The retained-object precondition must be real: " + scenario,
                            animation, control.getAnimation());
                    assertEquals(scenario, ended, animation.hasEnded());
                    assertEquals(scenario, fillAfter, animation.getFillAfter());
                    assertNull("An attached animation is not a reliable resting rectangle: " + scenario,
                            state.motion.restingBounds(control, state.root));

                    // No drawing or main-loop idle may naturally clear the object before this boundary is checked.
                    Fallback fallback = driveToFallback(scene, state);
                    assertSame(scenario, animation, control.getAnimation());
                    assertEquals(scenario, ended, animation.hasEnded());
                    assertFixedPoint(scene, state, fallback.binding, fallback.background);
                    assertEquals(fallback.count, fallbackCount(scene));

                    control.clearAnimation();
                    assertNull("The counterfactual explicitly removes the retained object", control.getAnimation());
                    assertNotNull(state.motion.restingBounds(control, state.root));
                    recoverByOwnScan(scene, state, fallback);
                    assertBusinessRoutes(scene);
                }
            }
        }
    }

    @Test public void ownTransientStateAndAncestorStateRecoverWithoutBlockingAnUnrelatedChild() throws Exception {
        for (boolean ancestor : new boolean[]{false, true}) {
            try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
                AppCompatImageView control = addControl(scene);
                MonitorState state = enable(scene, false);
                View busy = ancestor ? scene.metadataOwner : control;
                busy.setHasTransientState(true);
                assertTrue(busy.hasTransientState());
                assertNull("The target path contains its own transient state",
                        state.motion.restingBounds(control, state.root));
                Fallback fallback = driveToFallback(scene, state);
                assertFixedPoint(scene, state, fallback.binding, fallback.background);
                busy.setHasTransientState(false);
                assertFalse(busy.hasTransientState());
                assertNotNull(state.motion.restingBounds(control, state.root));
                recoverByOwnScan(scene, state, fallback);
                assertBusinessRoutes(scene);
            }
        }
        try (BoundsRig rig = new BoundsRig()) {
            rig.other.setHasTransientState(true);
            assertTrue("The group inherits the unrelated child's flag", rig.carrier.hasTransientState());
            RectF before = rig.motion.restingBounds(rig.control, rig.root);
            assertBounds(before, 60, 100, 160, 140);
            rig.motion.setTargets(List.of(rig.carrier), rig.root, null);
            rig.motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
            assertTrue("The inherited flag must not freeze the carrier's own motion",
                    rig.carrier.getTranslationX() > 0);
            int writes = rig.control.writes + rig.carrier.writes;
            assertEquals(before, rig.motion.restingBounds(rig.control, rig.root));
            assertEquals("Resting measurement never mutates the views", writes,
                    rig.control.writes + rig.carrier.writes);
            rig.other.setHasTransientState(false);
        }
    }

    @Test public void singularHostScaleIsReportedAndRecoveryKeepsTheConservativeFallback() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            AppCompatImageView control = addControl(scene);
            control.setScaleX(0f);
            MonitorState state = enable(scene, false);
            Fallback fallback = captureFallback(scene, state);
            String diagnostic = scene.logs.stream()
                    .filter(line -> line.contains("navigation_clearance_fallback=")
                            && line.contains("reason=unmeasurable"))
                    .reduce((first, second) -> second).orElse("");
            assertTrue("The failed target scale must be included: " + diagnostic,
                    diagnostic.contains("scale_xy=0.0/1.0"));
            assertTrue("The diagnostic must include a bounded target-to-root path: " + diagnostic,
                    diagnostic.contains("state_path="));
            assertFixedPoint(scene, state, fallback.binding, fallback.background);
            control.setScaleX(1f);
            assertNotNull(state.motion.restingBounds(control, state.root));
            recoverByOwnScan(scene, state, fallback);
            assertBusinessRoutes(scene);
        }
    }

    @Test public void hostTiltStaysGuardedWhileModuleOwnedMotionIsStripped() throws Exception {
        try (BoundsRig rig = new BoundsRig()) {
            rig.control.setRotationX(8);
            assertNull(rig.motion.restingBounds(rig.control, rig.root));
            rig.control.setRotationX(0);
            rig.carrier.setRotationY(8);
            assertNull("A host-owned ancestor tilt is also unsafe",
                    rig.motion.restingBounds(rig.control, rig.root));
            rig.carrier.setRotationY(0);
            rig.carrier.setTranslationX(7);
            RectF before = rig.motion.restingBounds(rig.control, rig.root);
            assertBounds(before, 67, 100, 167, 140);
            rig.motion.setTargets(List.of(rig.carrier), rig.root, null);
            rig.motion.applyFrame(new LiquidGlassMotion.Vector(1, 1));
            assertTrue("Exercise a real module-owned translation and tilt",
                    rig.carrier.getTranslationX() > 7 && rig.carrier.getRotationX() != 0);
            int writes = rig.control.writes + rig.carrier.writes;
            assertEquals(before, rig.motion.restingBounds(rig.control, rig.root));
            assertEquals(writes, rig.control.writes + rig.carrier.writes);
            rig.motion.applyFrame(new LiquidGlassMotion.Vector(-1, -1));
            assertEquals(before, rig.motion.restingBounds(rig.control, rig.root));

            rig.carrier.setTranslationX(80);
            assertBounds(rig.motion.restingBounds(rig.control, rig.root), 140, 100, 240, 140);
            rig.motion.applyFrame(new LiquidGlassMotion.Vector(1, 0));
            assertEquals("Host takeover is not reduced by the module's previous offset",
                    80, rig.carrier.getTranslationX(), 0);
            rig.carrier.setRotationY(9);
            assertNull(rig.motion.restingBounds(rig.control, rig.root));
            rig.motion.applyFrame(new LiquidGlassMotion.Vector(-1, 0));
            assertEquals("Yielding must preserve the host's tilt", 9, rig.carrier.getRotationY(), 0);
        }
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            MonitorState state = enable(scene, true);
            long revision = state.clearance.inputRevision();
            int margin = scene.carrierParams.bottomMargin;
            state.motion.setTargets(List.of(scene.navigation, scene.carrier, scene.metadataOwner, scene.right),
                    state.root, null);
            for (LiquidGlassMotion.Vector pose : List.of(
                    new LiquidGlassMotion.Vector(1, 1), new LiquidGlassMotion.Vector(-1, -1))) {
                state.motion.applyFrame(pose);
                assertFalse("Owned motion must not create another clearance reservation",
                        state.clearance.sync(List.of(scene.navigation)));
                assertEquals(margin, scene.carrierParams.bottomMargin);
                assertEquals(revision, state.clearance.inputRevision());
                assertTrue(state.clearance.blockedViews().isEmpty());
            }
        }
    }

    @Test public void clearingTheActualFaultSchedulesAndRebindsWithoutAnExternalLayoutNotification() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            AppCompatImageView control = addControl(scene);
            control.setRotationX(8);
            MonitorState state = enable(scene, false);
            Fallback fallback = captureFallback(scene, state);
            assertFixedPoint(scene, state, fallback.binding, fallback.background);
            control.setRotationX(0);
            assertNotNull(state.motion.restingBounds(control, state.root));
            recoverByOwnScan(scene, state, fallback);
            assertBusinessRoutes(scene);
        }
    }

    @Test public void pendingLayoutAndAnUnclearedFaultCannotUnlockAnOldRejection() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            AppCompatImageView control = addControl(scene);
            control.setRotationX(8);
            MonitorState state = enable(scene, false);
            Fallback fallback = captureFallback(scene, state);
            long before = state.clearance.inputRevision();
            scene.metadata.setText("A changed host caption\nsecond line\nthird line\nfourth line");
            scene.metadata.setMinHeight(scene.metadata.getHeight() + 27);
            scene.layout();
            state.syncClearance();
            assertTrue("The unrelated host input really changed the revision",
                    state.clearance.inputRevision() > before);
            assertTrue(state.rejected().containsKey(scene.navigation));
            assertNotEquals(state.rejected().get(scene.navigation).longValue(), state.clearance.inputRevision());
            assertSame(fallback.binding, state.binding());
            assertSame(fallback.background, scene.navigation.getBackground());
            assertEquals(fallback.count, fallbackCount(scene));

            control.setRotationX(0);
            scene.navigationParams.height = 59;
            scene.navigation.requestLayout();
            assertEquals("The requested host size has not been measured yet", 58, scene.navigation.getHeight());
            state.syncClearance();
            assertFalse("An empty pending snapshot is not a recovery ACK", state.clearance.retryReady());
            assertTrue(state.rejected().containsKey(scene.navigation));
            assertSame(fallback.binding, state.binding());
            assertSame(fallback.background, scene.navigation.getBackground());
            assertEquals(fallback.count, fallbackCount(scene));
            recoverByOwnScan(scene, state, fallback);
            assertBusinessRoutes(scene);
        }
    }

    @Test public void visibilityAndReplacementReleaseOldOwnersAndBindOnlyTheLiveNavigation() throws Exception {
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            AppCompatImageView oldControl = addControl(scene);
            oldControl.setRotationX(8);
            MonitorState state = enable(scene, false);
            Fallback first = captureFallback(scene, state);
            oldControl.setVisibility(View.GONE);
            recoverByOwnScan(scene, state, first);
            oldControl.setVisibility(View.VISIBLE);
            Fallback second = driveToFallback(scene, state);
            assertEquals(first.count + 1, second.count);

            scene.metadataOwner.removeView(oldControl);
            AppCompatImageView replacement = addControl(scene);
            assertNotSame(oldControl, replacement);
            assertEquals(oldControl.getId(), replacement.getId());
            assertNull(oldControl.getParent());
            notifyHostStructureChanged(scene);
            pumpFrames(scene, state, 8);
            assertRecovered(scene, state, second);
            assertEquals("The retired host object is not rewritten", 8, oldControl.getRotationX(), 0);
            assertEquals(0, replacement.getRotationX(), 0);
            assertNotNull(state.motion.restingBounds(replacement, state.root));
            assertBusinessRoutes(scene);
        }
        try (LiquidGlassClearanceControllerTest.Scene scene = scene()) {
            MonitorState state = enable(scene, false);
            LiquidGlassController.Binding first = state.binding();
            assertNotNull(first);
            long visibleRevision = state.clearance.inputRevision();
            scene.navigation.setVisibility(View.GONE);
            notifyHostStructureChanged(scene);
            pumpFrames(scene, state, 8);
            assertEquals(View.GONE, scene.navigation.getVisibility());
            assertNull("A hidden navigation no longer owns a binding", state.binding());
            assertSame(state.nativeBackground, scene.navigation.getBackground());
            assertEquals(58, scene.navigationParams.height);
            assertTrue(state.clearance.inputRevision() > visibleRevision);
            long hiddenRevision = state.clearance.inputRevision();
            scene.navigation.setVisibility(View.VISIBLE);
            notifyHostStructureChanged(scene);
            pumpFrames(scene, state, 8);
            assertNotNull(state.binding());
            assertNotSame(first, state.binding());
            assertFalse(state.binding().clearanceFallback);
            assertFalse(layoutClosed(state.binding()));
            assertEquals(72, scene.navigation.getHeight());
            assertTrue(state.clearance.inputRevision() > hiddenRevision);
            assertFalse(state.rejected().containsKey(scene.navigation));
            scene.assertClearance(3);
            assertFixedPoint(scene, state, state.binding(), scene.navigation.getBackground());
            assertBusinessRoutes(scene);
        }
    }

    private static LiquidGlassClearanceControllerTest.Scene scene() throws Exception {
        return new LiquidGlassClearanceControllerTest.Scene(false, false);
    }

    private static AppCompatImageView addControl(LiquidGlassClearanceControllerTest.Scene scene) {
        AppCompatImageView control = new AppCompatImageView(scene.activity);
        control.setId(CONTROL_ID);
        scene.metadataOwner.addView(control,
                new FrameLayout.LayoutParams(18, 18, Gravity.TOP | Gravity.RIGHT));
        scene.forceInitialLayout();
        scene.layout();
        return control;
    }

    private static MonitorState enable(LiquidGlassClearanceControllerTest.Scene scene, boolean motion) throws Exception {
        Drawable nativeBackground = scene.navigation.getBackground();
        scene.enable(motion);
        scene.settle(); // Setup only. The recovery drivers below never call settle or global layout.
        MonitorState state = new MonitorState(scene, nativeBackground);
        for (int pass = 0; pass < 3 && state.scanScheduled(); pass++) {
            ShadowLooper.shadowMainLooper().idleFor(STEP);
            scene.layout();
            state.preDraw();
        }
        assertFalse("Setup must not lend an already queued scan to the recovery", state.scanScheduled());
        return state;
    }

    private static Fallback driveToFallback(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state) throws Exception {
        for (int pass = 0; pass < 6; pass++) {
            scene.layout();
            state.preDraw();
        }
        return captureFallback(scene, state);
    }

    private static Fallback captureFallback(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state) throws Exception {
        LiquidGlassController.Binding binding = state.binding();
        assertNotNull("The safe material binding remains while its geometry falls back", binding);
        assertTrue(binding.clearanceFallback);
        assertTrue(layoutClosed(binding));
        assertEquals("The fallback restores the literal host geometry", 58, scene.navigation.getHeight());
        assertNotSame("Geometry fallback retains the glass material", state.nativeBackground,
                scene.navigation.getBackground());
        assertTrue(state.rejected().containsKey(scene.navigation));
        assertTrue("Use a real unmeasurable path", scene.logs.stream().anyMatch(
                line -> line.contains("navigation_clearance_fallback=") && line.contains("reason=unmeasurable")));
        return new Fallback(binding, scene.navigation.getBackground(), fallbackCount(scene),
                state.rejected().get(scene.navigation));
    }

    private static void recoverByOwnScan(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state, Fallback fallback) throws Exception {
        assertFalse("No old layout notification may perform this recovery", state.scanScheduled());
        boolean queued = false;
        for (int pass = 0; pass < 8; pass++) {
            scene.layout();
            state.preDraw();
            if (!state.rejected().containsKey(scene.navigation)) {
                assertTrue("Stable reliable geometry is required before retry", state.clearance.retryReady());
                assertNotEquals("The actual fault recovery must revise inputs",
                        fallback.rejectedRevision, state.clearance.inputRevision());
                assertTrue("Pre-draw recovery must enqueue its own scan", state.scanScheduled());
                assertSame("The queued scan has not run on the paused looper yet", fallback.binding, state.binding());
                queued = true;
                break;
            }
            assertSame(fallback.binding, state.binding());
            assertSame(fallback.background, scene.navigation.getBackground());
        }
        assertTrue("Recovery failed before its own enqueue boundary: " + scene.logs, queued);
        ShadowLooper.shadowMainLooper().idleFor(STEP);
        pumpFrames(scene, state, 8);
        assertRecovered(scene, state, fallback);
    }

    private static void assertRecovered(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state, Fallback fallback) throws Exception {
        LiquidGlassController.Binding binding = state.binding();
        assertNotNull(binding);
        assertNotSame("A closed geometry owner must be replaced", fallback.binding, binding);
        assertNotSame(fallback.background, scene.navigation.getBackground());
        assertFalse(binding.clearanceFallback);
        assertFalse(layoutClosed(binding));
        assertFalse(state.rejected().containsKey(scene.navigation));
        assertTrue(state.clearance.retryReady());
        assertEquals(72, scene.navigation.getHeight());
        assertEquals("A successful recovery does not fall back again", fallback.count, fallbackCount(scene));
        scene.assertClearance(3);
        assertFixedPoint(scene, state, binding, scene.navigation.getBackground());
    }

    private static void pumpFrames(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state, int passes) throws Exception {
        for (int pass = 0; pass < passes; pass++) {
            scene.layout();
            state.preDraw();
            ShadowLooper.shadowMainLooper().idleFor(STEP);
        }
        scene.layout();
    }

    private static void assertFixedPoint(LiquidGlassClearanceControllerTest.Scene scene,
            MonitorState state, LiquidGlassController.Binding binding, Drawable background) throws Exception {
        int margin = scene.carrierParams.bottomMargin;
        long revision = state.clearance.inputRevision();
        for (int pass = 0; pass < 5; pass++) {
            scene.layout();
            assertTrue("A stable state must permit drawing on pass " + pass + ": " + scene.logs, state.preDraw());
            assertSame(binding, state.binding());
            assertSame(background, scene.navigation.getBackground());
            assertEquals("Stable drawing cannot add footer reservations", margin, scene.carrierParams.bottomMargin);
            assertEquals("Module layout is not a new host input", revision, state.clearance.inputRevision());
        }
    }

    private static void assertBusinessRoutes(LiquidGlassClearanceControllerTest.Scene scene) {
        ViewGroup row = scene.navigation.findViewById(0x7f0a7983);
        assertEquals(5, row.getChildCount());
        int clicks = scene.navigationClicks.get();
        for (int index = 0; index < row.getChildCount(); index++) scene.click(row.getChildAt(index));
        assertEquals("All five original host click routes remain usable", clicks + 5, scene.navigationClicks.get());
        int starts = scene.dragStarts.get(), stops = scene.dragStops.get();
        scene.dragTimeline();
        assertTrue("The real timeline still receives drag start", scene.dragStarts.get() > starts);
        assertTrue("The real timeline still receives drag stop", scene.dragStops.get() > stops);
    }

    private static void notifyHostStructureChanged(LiquidGlassClearanceControllerTest.Scene scene) {
        scene.forceInitialLayout();
        scene.layout();
        scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static long fallbackCount(LiquidGlassClearanceControllerTest.Scene scene) {
        return scene.logs.stream().filter(line -> line.contains("navigation_clearance_fallback=")).count();
    }

    private static boolean layoutClosed(LiquidGlassController.Binding binding) throws Exception {
        return (Boolean) field(binding.layout, "closed");
    }

    private static Object field(Object owner, String name) throws Exception {
        Field value = owner.getClass().getDeclaredField(name);
        value.setAccessible(true);
        return value.get(owner);
    }

    private static void assertBounds(RectF actual, float left, float top, float right, float bottom) {
        assertNotNull(actual);
        assertArrayEquals(new float[]{left, top, right, bottom},
                new float[]{actual.left, actual.top, actual.right, actual.bottom}, .001f);
    }

    private static final class Fallback {
        final LiquidGlassController.Binding binding;
        final Drawable background;
        final long count, rejectedRevision;
        Fallback(LiquidGlassController.Binding binding, Drawable background, long count, long rejectedRevision) {
            this.binding = binding;
            this.background = background;
            this.count = count;
            this.rejectedRevision = rejectedRevision;
        }
    }

    private static final class MonitorState {
        final LiquidGlassClearanceControllerTest.Scene scene;
        final Object monitor;
        final View root;
        final NavigationGlassClearance clearance;
        final LiquidGlassMotion motion;
        final Drawable nativeBackground;
        MonitorState(LiquidGlassClearanceControllerTest.Scene scene, Drawable nativeBackground) throws Exception {
            this.scene = scene;
            this.nativeBackground = nativeBackground;
            Map<?, ?> windows = (Map<?, ?>) field(scene.controller, "windows");
            monitor = windows.get(scene.activity.getWindow().getDecorView());
            assertNotNull("The activity needs a real attached monitor", monitor);
            root = (View) field(monitor, "root");
            clearance = (NavigationGlassClearance) field(monitor, "clearance");
            motion = (LiquidGlassMotion) field(monitor, "motion");
        }
        @SuppressWarnings("unchecked") Map<View, Long> rejected() throws Exception {
            return (Map<View, Long>) field(monitor, "clearanceRejected");
        }
        @SuppressWarnings("unchecked") LiquidGlassController.Binding binding() throws Exception {
            return ((Map<View, LiquidGlassController.Binding>) field(monitor, "bindings")).get(scene.navigation);
        }
        boolean scanScheduled() throws Exception { return (Boolean) field(monitor, "scanScheduled"); }
        boolean preDraw() throws Exception {
            Method method = monitor.getClass().getDeclaredMethod("onPreDraw");
            method.setAccessible(true);
            return (Boolean) method.invoke(monitor);
        }
        void syncClearance() throws Exception {
            Method method = monitor.getClass().getDeclaredMethod("syncClearance");
            method.setAccessible(true);
            method.invoke(monitor);
        }
    }

    private static final class DanmakuAction {
        final FrameLayout row, mount;
        final DanmakuVisibilityFrameLayout clip;
        final FrameLayout frame;
        final AppCompatImageView icon;
        int clicks;

        DanmakuAction(LiquidGlassClearanceControllerTest.Scene scene) {
            scene.progress.setVisibility(View.GONE);
            scene.lowerModule.getLayoutParams().height = 130;
            row = new FrameLayout(scene.activity);
            scene.lowerModule.addView(row, new FrameLayout.LayoutParams(-1, -1));
            mount = new FrameLayout(scene.activity);
            row.addView(mount, new FrameLayout.LayoutParams(-1, -1));
            clip = new DanmakuVisibilityFrameLayout(scene.activity);
            mount.addView(clip, new FrameLayout.LayoutParams(40, 40, Gravity.CENTER_HORIZONTAL));
            frame = new FrameLayout(scene.activity);
            frame.setId(0x7f0a2dab);
            frame.setBackgroundColor(0xff333333);
            frame.setOnClickListener(view -> clicks++);
            clip.addView(frame, new FrameLayout.LayoutParams(32, 32, Gravity.CENTER));
            icon = new AppCompatImageView(scene.activity);
            icon.setId(CONTROL_ID);
            icon.setImageDrawable(new ColorDrawable(0xffffffff));
            frame.addView(icon, new FrameLayout.LayoutParams(22, 22, Gravity.CENTER));
            scene.forceInitialLayout();
            scene.layout();
        }

        void clearLayoutFlag() {
            clearLayoutFlag(icon);
            assertEquals(22, icon.getWidth());
        }

        void clearClipLayoutFlag() {
            clearLayoutFlag(clip);
            assertEquals(40, clip.getWidth());
        }

        void clearSubtreeLayoutFlags() {
            for (View view : List.of(icon, frame, clip, mount, row)) clearLayoutFlag(view);
        }

        private static void clearLayoutFlag(View view) {
            // Model reattachment leaving the last size intact, with no pending layout request.
            int flags = ReflectionHelpers.getField(view, "mPrivateFlags3");
            ReflectionHelpers.setField(view, "mPrivateFlags3", flags & ~4);
            assertFalse(view.isLaidOut());
            assertFalse(view.isLayoutRequested());
            assertTrue(view.isAttachedToWindow());
        }
    }

    private static final class LikeAction {
        final HPFrameLayout clip;
        final LinearLayout action;
        final FrameLayout frame;
        final DiggAnimationLayout animation;
        DiggAnimationView icon;
        int clicks;
        LikeAction(LiquidGlassClearanceControllerTest.Scene scene) {
            clip = new HPFrameLayout(scene.activity);
            clip.setId(0x7f0a30de);
            scene.rightParams.height = 120;
            RelativeLayout.LayoutParams clipParams = new RelativeLayout.LayoutParams(60, 66);
            clipParams.addRule(RelativeLayout.ALIGN_PARENT_TOP);
            scene.right.addView(clip, clipParams);
            action = new LinearLayout(scene.activity);
            action.setId(0x7f0a30eb);
            action.setOrientation(LinearLayout.VERTICAL);
            action.setClipChildren(false);
            clip.addView(action, new FrameLayout.LayoutParams(60, 64));
            frame = new FrameLayout(scene.activity);
            frame.setId(0x7f0a30d6);
            frame.setOnClickListener(view -> clicks++);
            action.addView(frame, new LinearLayout.LayoutParams(60, 52));
            animation = new DiggAnimationLayout(scene.activity);
            animation.setId(0x7f0a30ca);
            frame.addView(animation, new FrameLayout.LayoutParams(60, 60, Gravity.CENTER));
            replaceImage();
            scene.forceInitialLayout();
            scene.layout();
        }
        void replaceImage() {
            animation.reportLayout = true;
            if (icon != null) animation.removeView(icon);
            icon = new DiggAnimationView(animation.getContext());
            icon.setImageDrawable(new ColorDrawable(0xffffffff));
            animation.addView(icon, new FrameLayout.LayoutParams(36, 36, Gravity.CENTER));
        }
        void reportUnlaid() {
            animation.reportLayout = false;
            icon.reportLayout = false;
            assertEquals(36, icon.getWidth());
            assertEquals(60, animation.getWidth());
            assertFalse(icon.isLayoutRequested());
            assertFalse(animation.isLayoutRequested());
        }
    }

    private static final class BoundsRig implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root = new FrameLayout(activity);
        final CountingFrame carrier = new CountingFrame(activity);
        final CountingView control = new CountingView(activity);
        final View other = new View(activity);
        final LiquidGlassMotion motion = new LiquidGlassMotion(activity);
        BoundsRig() {
            FrameLayout.LayoutParams parent = new FrameLayout.LayoutParams(200, 200);
            parent.leftMargin = 50;
            parent.topMargin = 80;
            root.addView(carrier, parent);
            FrameLayout.LayoutParams child = new FrameLayout.LayoutParams(100, 40);
            child.leftMargin = 10;
            child.topMargin = 20;
            carrier.addView(control, child);
            FrameLayout.LayoutParams sibling = new FrameLayout.LayoutParams(10, 10);
            sibling.leftMargin = 150;
            sibling.topMargin = 150;
            carrier.addView(other, sibling);
            activity.setContentView(root);
            root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
            root.layout(0, 0, 360, 800);
        }
        @Override public void close() { motion.close(); activity.finish(); }
    }

    private static final class CountingView extends View {
        int writes;
        CountingView(Activity activity) { super(activity); }
        @Override public void setTranslationX(float value) { writes++; super.setTranslationX(value); }
        @Override public void setTranslationY(float value) { writes++; super.setTranslationY(value); }
        @Override public void setRotationX(float value) { writes++; super.setRotationX(value); }
        @Override public void setRotationY(float value) { writes++; super.setRotationY(value); }
    }

    private static final class CountingFrame extends FrameLayout {
        int writes;
        CountingFrame(Activity activity) { super(activity); }
        @Override public void setTranslationX(float value) { writes++; super.setTranslationX(value); }
        @Override public void setTranslationY(float value) { writes++; super.setTranslationY(value); }
        @Override public void setRotationX(float value) { writes++; super.setRotationX(value); }
        @Override public void setRotationY(float value) { writes++; super.setRotationY(value); }
    }
}
