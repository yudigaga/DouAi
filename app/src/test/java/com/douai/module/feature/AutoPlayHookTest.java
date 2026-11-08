package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class AutoPlayHookTest {
    @org.junit.Before public void selectOriginalProfile() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_408);
    }
    @org.junit.After public void resetOriginalProfile() { HostAdaptation.setCurrentProfile(null); }
    public static final class Aweme {
        private final String aid;
        Aweme(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class ItemParams {
        public FeedPanel mFeedContext;
    }

    public static class Holder {
        public FrameLayout w;
        public ItemParams A2;
        public String aid;
        public Aweme getAweme() { return new Aweme(aid); }
        public void selected() { }
        public void resume() { }
        public void pause() { }
    }
    public static final class ModernHolder extends Holder {
        public Object A2 = new Object();
        public ItemParams B2;
        @Override public Aweme getAweme() { return super.getAweme(); }
        @Override public void selected() { }
        @Override public void resume() { }
        @Override public void pause() { }
    }

    @Test public void modernHolderUsesB2ToAdvanceAfterCompletion() throws Throwable {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
        try (Fixture f = new Fixture(Collections.emptyMap(), false, true)) {
            f.swipe(600, 100);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
        }
    }

    public static final class PagerContext {
        int nextCalls;
        int previousCalls;
        boolean canMove = true;
        String source;
        boolean smooth;
        public boolean next(String source, boolean smooth) {
            this.source = source;
            this.smooth = smooth;
            nextCalls++;
            return canMove;
        }
        public boolean previous(String source, boolean smooth) {
            this.source = source;
            this.smooth = smooth;
            previousCalls++;
            return canMove;
        }
    }

    public static final class FeedPager extends View {
        int state;
        int touchCalls;
        FeedPager(Context context) { super(context); }
        public boolean touch(MotionEvent event) { touchCalls++; return true; }
        public int scrollState() { return state; }
    }

    public static final class DialogState {
        boolean comments;
        public boolean commentsShowing(boolean includeTransition) { return comments; }
    }

    public static final class FeedPanel {
        public PagerContext B3 = new PagerContext();
        public FeedPager f;
        public DialogState L0 = new DialogState();
        public Holder current;
        public PagerContext pagerContext() { return B3; }
        public Holder currentHolder() { return current; }
    }

    public static final class Dispatcher {
        Runnable completionListener;
        public void completed(String aid) {
            if (completionListener != null) completionListener.run();
        }
        public void playing(String aid) { }
        public void paused(String aid) { }
    }

    private static final class FeedRoot extends FrameLayout {
        boolean focused = true;
        FeedRoot(Context context) { super(context); }
        @Override public boolean hasWindowFocus() { return focused; }
    }

    @Test
    public void upwardManualTouchDrivesNextAndPersistsAcrossAutomaticSelections() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.swipe(600, 100);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
            assertEquals("DouAiAutoSlide", f.panel.B3.source);
            assertFalse("Automatic advance skips the blank SurfaceView smooth-scroll transition",
                    f.panel.B3.smooth);
            f.select(f.holder("two"));
            f.completed("two");
            assertEquals(2, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
            assertEquals(2, f.panel.f.touchCalls);
        }
    }

    @Test
    public void closingWhileAFrameFadesStillReleasesItsBitmapAndSharedReservation() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(32, 64,
                    android.graphics.Bitmap.Config.ARGB_8888);
            MediaMemoryBudget.Reservation memory = MediaMemoryBudget.tryAcquire(32L * 64 * 4);
            assertNotNull(memory);
            PlaybackTransitionFrame.Overlay frame = new PlaybackTransitionFrame.Overlay(
                    (FrameLayout) f.activity.getWindow().getDecorView(), bitmap,
                    new android.graphics.Rect(0, 0, 32, 64), memory);
            java.lang.reflect.Field owned = AutoPlayHook.class.getDeclaredField("transitionFrame");
            owned.setAccessible(true);
            owned.set(f.hook, frame);
            Method dismiss = AutoPlayHook.class.getDeclaredMethod("dismissTransitionFrame", boolean.class);
            dismiss.setAccessible(true);
            dismiss.invoke(f.hook, true);
            assertFalse("The effect retains memory until the fade actually finishes", bitmap.isRecycled());
            f.hook.close();
            idle();
            assertTrue("Owner close must also close its detached fading frame", bitmap.isRecycled());
            MediaMemoryBudget.Reservation all = MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES);
            assertNotNull(all);
            all.close();
        }
    }

    @Test
    public void defaultDirectionFollowsManualSwipe() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.swipe(100, 600);
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            assertEquals(1, f.panel.B3.previousCalls);
            assertFalse("Automatic playback advance does not animate a blank SurfaceView handoff",
                    f.panel.B3.smooth);
            f.select(f.holder("two"));
            f.completed("two");
            assertEquals(0, f.panel.B3.nextCalls);
            assertEquals(2, f.panel.B3.previousCalls);
        }
    }

    @Test
    public void directionToggleCanRestoreUpwardOnlyMode() throws Throwable {
        try (Fixture f = new Fixture(Map.of("smart_swipe", false))) {
            f.swipe(100, 600);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
            f.select(f.holder("two"));
            f.swipe(600, 100);
            f.completed("two");
            assertEquals(2, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
        }
    }

    @Test
    public void latestManualDirectionWinsAndHorizontalTouchDoesNotReplaceIt() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.swipe(100, 600);
            f.touch(MotionEvent.ACTION_DOWN, 10, 100);
            f.touch(MotionEvent.ACTION_UP, 600, 105);
            f.completed("one");
            assertEquals(1, f.panel.B3.previousCalls);
            f.select(f.holder("two"));
            f.swipe(600, 100);
            f.completed("two");
            assertEquals(1, f.panel.B3.nextCalls);
            assertEquals(1, f.panel.B3.previousCalls);
        }
    }

    @Test
    public void installsWithoutNativeAutoNextRulesAndNeverHooksNavigationItself() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            assertTrue(f.hook.supports("auto_play"));
            assertTrue(f.hook.supports("smart_swipe"));
            assertTrue(f.hook.supports("pause_auto_on_comments"));
            assertEquals(9, f.harness.interceptors.size());
            for (String key : f.report.getResults().keySet()) assertFalse(key.startsWith("auto_next_vm_"));
            assertFalse(f.harness.interceptors.containsKey(PagerContext.class.getMethod("next", String.class, boolean.class)));
            assertFalse(f.harness.interceptors.containsKey(PagerContext.class.getMethod("previous", String.class, boolean.class)));
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void lateInstallationRecoversAlreadySelectedHolderFromHostCurrentHolderRead() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap(), true)) {
            f.harness.invoke(FeedPanel.class.getMethod("currentHolder"), f.panel);
            idle();
            f.completed("one");

            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void lateInstallationRecoversAlreadySelectedHolderWhenHostReadsItsAweme() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap(), true)) {
            f.harness.invoke(Holder.class.getMethod("getAweme"), f.selected);
            idle();
            f.completed("one");

            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void lateHolderReadDoesNotReviveAnActivityAfterPause() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap(), true)) {
            f.hook.onActivityPaused(f.activity);
            f.harness.invoke(FeedPanel.class.getMethod("currentHolder"), f.panel);
            idle();
            f.completed("one");

            assertEquals(0, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void duplicateCompletionDispatchesOnlyOnceUntilAnotherVideoIsSelected() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.queueCompletion("one");
            f.queueCompletion("one");
            idle();
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            f.select(f.holder("two"));
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            f.completed("two");
            assertEquals(2, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void staleAidChangedHolderAndHostSelectionDuringDispatchAreIgnored() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.completed("wrong");
            f.selected.aid = "host-rebound";
            f.completed("one");
            f.selected.aid = "one";
            f.panel.current = f.holder("foreign");
            f.completed("one");
            f.panel.current = f.selected;
            f.queueCompletion("one");
            f.select(f.holder("two"));
            idle();
            assertEquals(0, f.panel.B3.nextCalls);
            f.dispatcher.completionListener = () -> {
                try { f.select(f.holder("three")); }
                catch (Throwable failure) { throw new AssertionError(failure); }
            };
            f.completed("two");
            assertEquals(0, f.panel.B3.nextCalls);
            f.dispatcher.completionListener = null;
            f.completed("three");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void playbackPauseBackgroundAndLostWindowFocusPreventAdvancing() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.harness.invoke(Dispatcher.class.getMethod("paused", String.class), f.dispatcher, "one");
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.harness.invoke(Dispatcher.class.getMethod("playing", String.class), f.dispatcher, "one");
            f.hook.onActivityPaused(f.activity);
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.hook.onActivityResumed(f.activity);
            f.root.focused = false;
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.root.focused = true;
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void repeatedSelectionDoesNotClearCurrentVideoPauseState() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.harness.invoke(Dispatcher.class.getMethod("paused", String.class), f.dispatcher, "one");
            idle();
            assertEquals(Boolean.TRUE, f.hook.pauseStateFor("one"));

            f.select(f.selected);
            assertEquals("Repeated selection is not a playback resume", Boolean.TRUE,
                    f.hook.pauseStateFor("one"));
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);

            f.harness.invoke(Dispatcher.class.getMethod("playing", String.class), f.dispatcher, "one");
            idle();
            assertEquals(Boolean.FALSE, f.hook.pauseStateFor("one"));
        }
    }

    @Test
    public void disableOrCloseBeforePendingCompletionCancelsNavigation() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.queueCompletion("one");
            f.hook.setSettings(f.settings(false), f.options);
            idle();
            assertEquals(0, f.panel.B3.nextCalls);
            f.hook.setSettings(f.settings(true), f.options);
            f.queueCompletion("one");
            f.hook.close();
            idle();
            assertEquals(0, f.panel.B3.nextCalls);
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test
    public void holderPauseInvalidatesQueuedCompletionAndResumeStartsAnotherSession() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.queueCompletion("one");
            f.harness.invoke(Holder.class.getMethod("pause"), f.selected);
            idle();
            assertEquals(0, f.panel.B3.nextCalls);
            f.harness.invoke(Holder.class.getMethod("resume"), f.selected);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void cancelledAndMultiTouchGesturesDoNotReplaceLastManualDirection() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.swipe(100, 600);
            f.touch(MotionEvent.ACTION_DOWN, 100, 600);
            f.touch(MotionEvent.ACTION_CANCEL, 100, 100);
            f.completed("one");
            assertEquals(1, f.panel.B3.previousCalls);
            f.select(f.holder("two"));
            f.touch(MotionEvent.ACTION_DOWN, 100, 600);
            f.multiTouch();
            f.touch(MotionEvent.ACTION_UP, 100, 100);
            f.completed("two");
            assertEquals(2, f.panel.B3.previousCalls);
            assertEquals(0, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void touchingOrScrollingBlocksCompletionAndCommentsFollowPauseSetting() throws Throwable {
        try (Fixture f = new Fixture(Map.of("pause_auto_on_comments", true))) {
            f.touch(MotionEvent.ACTION_DOWN, 100, 600);
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.touch(MotionEvent.ACTION_CANCEL, 100, 600);
            f.panel.f.state = 1;
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.panel.f.state = 0;
            f.panel.L0.comments = true;
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);
            f.hook.setSettings(f.settings(true), new LocalOptions(Collections.emptyMap()));
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void completionDuringTapIsDroppedAndLaterCompletionAdvancesOnlyOnce() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.touch(MotionEvent.ACTION_DOWN, 100, 300);
            f.completed("one");
            assertEquals(0, f.panel.B3.nextCalls);

            f.touch(MotionEvent.ACTION_UP, 100, 300);
            idle();
            assertEquals(0, f.panel.B3.nextCalls);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
            f.completed("one");
            assertEquals(1, f.panel.B3.nextCalls);
        }
    }

    @Test
    public void completionDuringManualSwipeDoesNotReplayAfterRelease() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.touch(MotionEvent.ACTION_DOWN, 100, 600);
            f.completed("one");
            f.touch(MotionEvent.ACTION_UP, 100, 100);
            idle();

            assertEquals(0, f.panel.B3.nextCalls);
            assertEquals(0, f.panel.B3.previousCalls);
        }
    }

    @Test
    public void pagerBoundaryIsAttemptedOnceWithoutChangingDirectionOrLooping() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            f.panel.B3.canMove = false;
            f.swipe(100, 600);
            f.completed("one");
            f.completed("one");
            assertEquals(1, f.panel.B3.previousCalls);
            assertEquals(0, f.panel.B3.nextCalls);
            f.select(f.holder("two"));
            f.panel.B3.canMove = true;
            f.completed("two");
            assertEquals(2, f.panel.B3.previousCalls);
        }
    }

    private static void idle() { ShadowLooper.idleMainLooper(); }

    @Test
    public void analyticsCountsAcceptedAutoSlideOnceAndSkipsFailedOrDisabledActions() throws Throwable {
        try (Fixture f = new Fixture(Collections.emptyMap())) {
            com.douai.module.analytics.LocalAnalytics store = com.douai.module.analytics.LocalAnalytics.get(f.activity);
            com.douai.module.analytics.LocalAnalytics.initialize(f.activity);
            try {
                store.clear();
                store.setEnabled(true);
                f.completed("one");
                f.completed("one");
                assertEquals("duplicate completion must not count twice", 1, store.snapshot(0).totalFeatureUses);
                assertEquals("自动翻页", store.snapshot(0).features.get(0).label);
                f.select(f.holder("blocked-at-list-boundary"));
                f.panel.B3.canMove = false;
                f.completed("blocked-at-list-boundary");
                assertEquals("rejected host move is not usage", 1, store.snapshot(0).totalFeatureUses);
                store.setEnabled(false);
                f.panel.B3.canMove = true;
                f.select(f.holder("disabled-analytics"));
                f.completed("disabled-analytics");
                assertEquals("feature still works while collection is disabled", 3, f.panel.B3.nextCalls);
                assertEquals("disabled analytics stores no event", 1, store.snapshot(0).totalFeatureUses);
            } finally {
                store.setEnabled(false);
                store.clear();
                store.close();
            }
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final FeedRoot root = new FeedRoot(activity);
        final FeedPanel panel = new FeedPanel();
        final Dispatcher dispatcher = new Dispatcher();
        final Harness harness = new Harness();
        final LocalOptions options;
        final HostScanReport report;
        final AutoPlayHook hook;
        final boolean modern;
        Holder selected;

        Fixture(Map<String, ?> values) throws Throwable {
            this(values, false);
        }

        Fixture(Map<String, ?> values, boolean installLate) throws Throwable {
            this(values, installLate, false);
        }

        Fixture(Map<String, ?> values, boolean installLate, boolean modern) throws Throwable {
            this.modern = modern;
            options = new LocalOptions(values);
            activity.setContentView(root);
            panel.f = new FeedPager(activity);
            report = report(modern ? ModernHolder.class : Holder.class);
            assertTrue(root.isAttachedToWindow());
            assertTrue(root.isShown());
            if (installLate) {
                selected = holder("one");
                panel.current = selected;
                selected.selected();
                idle();
                hook = installHook();
            } else {
                hook = installHook();
                hook.onActivityResumed(activity);
                select(holder("one"));
            }
        }

        AutoPlayHook installHook() throws Exception {
            return AutoPlayHook.install(harness.framework, report, getClass().getClassLoader(),
                    activity, settings(true), options, ignored -> { });
        }

        ExtendedSettings settings(boolean enabled) {
            SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences("auto-slide-test", 0);
            preferences.edit().clear().putBoolean("auto_play", enabled).commit();
            return ExtendedSettings.readFrom(preferences);
        }

        Holder holder(String aid) {
            Holder value = modern ? new ModernHolder() : new Holder();
            value.aid = aid;
            value.w = root;
            value.A2 = new ItemParams();
            value.A2.mFeedContext = panel;
            if (modern) ((ModernHolder) value).B2 = value.A2;
            return value;
        }

        void select(Holder value) throws Throwable {
            selected = value;
            panel.current = value;
            harness.invoke(value.getClass().getMethod("selected"), value);
            idle();
        }

        void queueCompletion(String aid) throws Throwable {
            harness.invoke(Dispatcher.class.getMethod("completed", String.class), dispatcher, aid);
        }

        void completed(String aid) throws Throwable { queueCompletion(aid); idle(); }

        void swipe(float start, float end) throws Throwable {
            touch(MotionEvent.ACTION_DOWN, 100, start);
            touch(MotionEvent.ACTION_UP, 100, end);
        }

        void touch(int action, float x, float y) throws Throwable {
            MotionEvent event = MotionEvent.obtain(0, 100, action, x, y, 0);
            try { harness.invoke(FeedPager.class.getMethod("touch", MotionEvent.class), panel.f, event); }
            finally { event.recycle(); }
        }

        void multiTouch() throws Throwable {
            MotionEvent.PointerProperties[] properties = { new MotionEvent.PointerProperties(), new MotionEvent.PointerProperties() };
            MotionEvent.PointerCoords[] coordinates = { new MotionEvent.PointerCoords(), new MotionEvent.PointerCoords() };
            for (int i = 0; i < 2; i++) {
                properties[i].id = i;
                properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                coordinates[i].x = 100 + i * 50;
                coordinates[i].y = 500;
            }
            MotionEvent event = MotionEvent.obtain(0, 100,
                    MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    2, properties, coordinates, 0, 0, 1f, 1f, 0, 0, 0, 0);
            try { harness.invoke(FeedPager.class.getMethod("touch", MotionEvent.class), panel.f, event); }
            finally { event.recycle(); }
        }

        @Override public void close() {
            hook.close();
            idle();
            activity.finish();
        }
    }

    private static HostScanReport report(Class<? extends Holder> holderType) throws Exception {
        List<RuleScanResult> results = new ArrayList<>();
        add(results, "feed_holder_page_selected", holderType.getMethod("selected"));
        add(results, "feed_holder_resume", holderType.getMethod("resume"));
        add(results, "feed_holder_pause", holderType.getMethod("pause"));
        add(results, "feed_aweme_accessor", holderType.getMethod("getAweme"));
        addType(results, "feed_holder_main_root", holderType);
        addType(results, "adv_gesture_item_params", ItemParams.class);
        add(results, "auto_slide_panel_context", FeedPanel.class.getMethod("pagerContext"));
        add(results, "auto_slide_current_holder", FeedPanel.class.getMethod("currentHolder"));
        add(results, "adv_player_move_next", PagerContext.class.getMethod("next", String.class, boolean.class));
        add(results, "adv_player_move_previous", PagerContext.class.getMethod("previous", String.class, boolean.class));
        add(results, "auto_slide_pager_touch", FeedPager.class.getMethod("touch", MotionEvent.class));
        add(results, "auto_slide_scroll_state", FeedPager.class.getMethod("scrollState"));
        add(results, "auto_slide_comments_showing", DialogState.class.getMethod("commentsShowing", boolean.class));
        add(results, "auto_slide_completed", Dispatcher.class.getMethod("completed", String.class));
        add(results, "component_player_playing", Dispatcher.class.getMethod("playing", String.class));
        add(results, "component_player_pause", Dispatcher.class.getMethod("paused", String.class));
        return new HostScanReport("fixture", results, false);
    }

    private static void add(List<RuleScanResult> results, String id, Method target) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(target.getDeclaringClass()),
                target.getName(), HostTargets.descriptor(target)))));
    }

    private static void addType(List<RuleScanResult> results, String id, Class<?> type) {
        results.add(new RuleScanResult(id, "fixture", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(type), "", ""))));
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, operation, values) -> {
                    if (!operation.getName().equals("hook")) return null;
                    Method target = (Method) values[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, action, arguments) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) arguments[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, call, args) -> {
                                            if (call.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Hooker hook = interceptors.get(target);
            assertNotNull("Expected installed hook for " + target, hook);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArg": return arguments[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "proceed":
                                try { return target.invoke(owner, values == null ? arguments : (Object[]) values[0]); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            default: return null;
                        }
                    });
            return hook.intercept(chain);
        }
    }
}
