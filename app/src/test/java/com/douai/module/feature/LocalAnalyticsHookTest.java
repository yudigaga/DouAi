package com.douai.module.feature;

import android.app.Activity;
import android.view.View;
import android.widget.FrameLayout;

import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.time.Duration;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class LocalAnalyticsHookTest {
    @org.junit.Before public void selectOriginalProfile() {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_408);
    }
    @org.junit.After public void resetOriginalProfile() { HostAdaptation.setCurrentProfile(null); }
    public static class Holder {
        public FrameLayout w;
        public Params A2 = new Params();
        public String aid = "one";
        public Object work;
        public Object getAweme() { return work == null ? Map.of("aid", aid, "duration", 1000) : work; }
        public void pageSelected() { }
        public void selected(int index) { }
        public void bind() { }
    }
    public static final class ModernHolder extends Holder {
        public Object A2 = new Object();
        public Params B2 = new Params();
        @Override public Object getAweme() { return super.getAweme(); }
        @Override public void pageSelected() { }
        @Override public void selected(int index) { }
    }

    @Test public void modernHolderUsesB2ToRejectStaleSelectionAndEndStaleVisits() throws Throwable {
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
        try (Fixture f = new Fixture(true)) {
            ModernHolder holder = (ModernHolder) f.holder;
            holder.B2.mFeedContext.current = new Holder();
            f.select();
            assertEquals(0, f.count());
            holder.B2.mFeedContext.current = holder;
            f.select();
            assertEquals(1, f.count());
            holder.B2.mFeedContext.current = new Holder();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertNull(activeVisit(f.hook));
        }
    }
    public static final class Params { public Panel mFeedContext = new Panel(); }
    public static final class Panel {
        public Holder current;
        public Object getCurrentViewHolderP() { return current; }
    }
    public static final class ImageHolder {
        public FrameLayout f;
        public Object getAweme() { return Map.of("aid", "photo", "is_live_photo", 1); }
        public void pageSelected() { }
    }
    private static final class FeedRoot extends FrameLayout {
        // Robolectric 4.14 does not dispatch a visible window on API 35; model that window signal
        // explicitly while retaining the actual attached, laid-out view tree and visibility checks.
        int windowVisibility = View.VISIBLE;
        boolean focused = true;
        FeedRoot(Activity activity) { super(activity); }
        @Override public int getWindowVisibility() { return windowVisibility; }
        @Override public boolean hasWindowFocus() { return focused; }
    }

    @Test public void installsOnlySelectionHooksAndDeduplicatesBothVideoCallbacks() throws Throwable {
        try (Fixture f = new Fixture()) {
            assertEquals(3, f.harness.interceptors.size());
            assertFalse(f.harness.interceptors.containsKey(Holder.class.getMethod("bind")));
            f.select();
            f.harness.invoke(Holder.class.getMethod("selected", int.class), f.holder, 1);
            assertEquals(1, f.count());
            f.holder.aid = "two"; f.select(); assertEquals(2, f.count());
            f.holder.aid = "one"; f.select(); assertEquals(3, f.count());
        }
    }

    @Test public void duplicateSelectionDoesNotReparseMetadataButRecycledIdentityDoes() throws Throwable {
        try (Fixture f = new Fixture()) {
            CountingWork work = new CountingWork();
            f.holder.work = work;
            f.select(); assertEquals(1, f.count());
            assertEquals(1, work.authorReads);
            for (int i = 0; i < 100; i++) {
                f.harness.invoke(Holder.class.getMethod("selected", int.class), f.holder, i);
            }
            assertEquals(1, f.count());
            assertEquals("metadata is decoded once for one uninterrupted visit", 1, work.authorReads);
            work.aid = "next";
            f.select(); assertEquals(2, f.count());
            assertEquals(2, work.authorReads);
        }
    }

    public static final class CountingWork {
        String aid = "counted";
        int authorReads;
        public String getAid() { return aid; }
        public long getDuration() { return 1000; }
        public Object getAuthor() { authorReads++; return Map.of("uid", "author"); }
    }

    @Test public void collectsLivePhotoThroughImageSelection() throws Throwable {
        try (Fixture f = new Fixture()) {
            ImageHolder image = new ImageHolder(); image.f = f.root;
            f.harness.invoke(ImageHolder.class.getMethod("pageSelected"), image);
            assertEquals(1, f.count());
            assertEquals("实况图集", f.store.snapshot(0).types.get(0).label);
        }
    }

    @Test public void rejectsHiddenDetachedAndBackgroundRoots() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.root.setVisibility(View.GONE); f.select(); assertEquals(0, f.count());
            f.root.setVisibility(View.VISIBLE);
            f.root.windowVisibility = View.GONE; f.select(); assertEquals(0, f.count());
            f.root.windowVisibility = View.VISIBLE;
            f.hook.onActivityPaused(f.activity); f.select(); assertEquals(0, f.count());
            f.hook.onActivityResumed(f.activity);
            f.activity.setContentView(new FrameLayout(f.activity)); f.select(); assertEquals(0, f.count());
        }
    }

    @Test public void rejectsStaleNeighborAndRecycledHolder() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.holder.A2.mFeedContext.current = new Holder(); f.select(); assertEquals(0, f.count());
            f.holder.A2.mFeedContext.current = f.holder;
            f.select();
            f.holder.aid = "recycled-before-main-callback";
            assertEquals(0, f.count());
            f.select(); assertEquals(1, f.count());
        }
    }

    @Test public void pauseInvalidatesQueuedSelectionEvenAfterResume() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select();
            f.hook.onActivityPaused(f.activity);
            f.hook.onActivityResumed(f.activity);
            assertEquals(0, f.count());
        }
    }

    @Test public void disabledCollectionAndClearInvalidateQueuedSelections() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.store.setEnabled(false); f.select(); f.store.setEnabled(true); assertEquals(0, f.count());
            f.select(); f.store.setEnabled(false); f.store.setEnabled(true); assertEquals(0, f.count());
            f.select(); f.store.clear(); assertEquals(0, f.count());
            f.select(); assertEquals(1, f.count());
        }
    }

    @Test public void closeRevokesHooksAndQueuedSelection() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); f.hook.close();
            assertEquals(0, f.count());
            assertTrue(f.harness.interceptors.isEmpty());
        }
    }

    @Test public void focusLossEndsVisitAndCoveredWindowCannotStartOne() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); assertEquals(1, f.count());
            Object visit = activeVisit(f.hook);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertEquals(750, visibleMillis(visit));
            f.root.focused = false;
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertNull(activeVisit(f.hook));
            assertEquals(750, visibleMillis(visit));
            f.holder.aid = "covered";
            f.select(); assertEquals(1, f.count());
        }
    }

    @Test public void repeatedSelectionDoesNotRestartVisibleVisit() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); assertEquals(1, f.count());
            Object first = activeVisit(f.hook);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            f.select(); f.count();
            assertSame(first, activeVisit(f.hook));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertEquals(1500, visibleMillis(first));
        }
    }

    @Test public void focusRegainStartsNewSegmentWithoutRepeatingWorkCount() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); assertEquals(1, f.count());
            Object first = activeVisit(f.hook);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            f.focus(false);
            assertNull(activeVisit(f.hook));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(10));
            assertNull(activeVisit(f.hook));
            f.focus(true);
            assertEquals(1, f.count());
            Object second = activeVisit(f.hook);
            assertNotNull(second);
            assertNotSame(first, second);
            assertEquals(0, visibleMillis(second));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertEquals(750, visibleMillis(first));
            assertEquals(750, visibleMillis(second));
            assertEquals(2, f.store.snapshot(0).portrait.capturedVisits);
        }
    }

    @Test public void foregroundResumeDoesNotCreditBackgroundOrRequireAnotherSelection() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); f.count();
            Object first = activeVisit(f.hook);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            f.hook.onActivityPaused(f.activity);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(30));
            assertNull(activeVisit(f.hook));
            assertEquals(750, visibleMillis(first));
            f.hook.onActivityResumed(f.activity);
            assertEquals(1, f.count());
            Object resumed = activeVisit(f.hook);
            assertNotNull(resumed);
            assertEquals(0, visibleMillis(resumed));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertEquals(750, visibleMillis(resumed));
            assertEquals(2, f.store.snapshot(0).portrait.capturedVisits);
        }
    }

    @Test public void recycledOrClearedCandidateCannotResumeAndCloseRemovesListeners() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); f.count();
            f.focus(false);
            f.holder.aid = "recycled";
            f.focus(true);
            assertEquals(1, f.count());
            assertNull(activeVisit(f.hook));
            f.select(); f.count();
            f.focus(false);
            f.store.clear();
            f.focus(true);
            assertEquals(0, f.count());
            assertNull(activeVisit(f.hook));
            f.select(); f.count();
            f.hook.close();
            f.focus(false); f.focus(true);
            f.count();
            assertNull(activeVisit(f.hook));
        }
    }

    @Test public void backgroundStaleCardAndRecordingGenerationCloseVisits() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.select(); f.count();
            f.hook.onActivityPaused(f.activity);
            assertNull(activeVisit(f.hook));
            f.hook.onActivityResumed(f.activity);
            f.holder.aid = "second"; f.select(); f.count();
            f.holder.A2.mFeedContext.current = new Holder();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertNull(activeVisit(f.hook));
            f.holder.A2.mFeedContext.current = f.holder;
            f.holder.aid = "third"; f.select(); f.count();
            f.store.clear();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(750));
            assertNull(activeVisit(f.hook));
            assertEquals(0, f.count());
        }
    }

    @Test public void sleepingOrStalledIntervalsAreNeverCreditedAsVisibleTime() {
        assertEquals(750, LocalAnalyticsHook.observedInterval(1000, 1750));
        assertEquals(1500, LocalAnalyticsHook.observedInterval(1000, 2500));
        assertEquals(0, LocalAnalyticsHook.observedInterval(1000, 2501));
        assertEquals(0, LocalAnalyticsHook.observedInterval(1000, 3_601_000));
        assertEquals(0, LocalAnalyticsHook.observedInterval(1000, 999));
    }

    private static Object activeVisit(LocalAnalyticsHook hook) throws Exception {
        Field field = LocalAnalyticsHook.class.getDeclaredField("currentVisit");
        field.setAccessible(true);
        return field.get(hook);
    }

    private static long visibleMillis(Object visit) throws Exception {
        assertNotNull(visit);
        Field field = visit.getClass().getDeclaredField("visibleMillis");
        field.setAccessible(true);
        return field.getLong(visit);
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final FeedRoot root = new FeedRoot(activity);
        final Holder holder;
        final Harness harness = new Harness();
        final LocalAnalytics store = LocalAnalytics.get(activity);
        final LocalAnalyticsHook hook;

        Fixture() throws Exception {
            this(false);
        }
        Fixture(boolean modern) throws Exception {
            holder = modern ? new ModernHolder() : new Holder();
            store.clear(); store.setEnabled(true);
            activity.setContentView(root);
            View decor = activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(500, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 500, 1000);
            holder.w = root; holder.A2.mFeedContext.current = holder;
            if (modern) ((ModernHolder) holder).B2.mFeedContext.current = holder;
            hook = LocalAnalyticsHook.install(harness.framework, report(holder.getClass()), getClass().getClassLoader(),
                    activity, ignored -> { });
            hook.onActivityResumed(activity);
            assertTrue("fixture root attached", root.isAttachedToWindow());
            assertTrue("fixture root shown", root.isShown());
            assertEquals("fixture window visible", View.VISIBLE, root.getWindowVisibility());
            assertTrue("fixture root has screen area: " + root.getWidth() + "x" + root.getHeight(),
                    LocalAnalyticsHook.visible(root, activity));
        }
        void select() throws Throwable { harness.invoke(holder.getClass().getMethod("pageSelected"), holder); }
        void focus(boolean focused) throws Exception {
            root.focused = focused;
            Method dispatch = android.view.ViewTreeObserver.class.getDeclaredMethod("dispatchOnWindowFocusChange", boolean.class);
            dispatch.setAccessible(true);
            dispatch.invoke(root.getViewTreeObserver(), focused);
        }
        long count() { ShadowLooper.idleMainLooper(); return store.snapshot(0).totalViews; }
        @Override public void close() {
            hook.close(); store.setEnabled(false); store.clear(); store.close(); activity.finish();
        }
    }

    private static HostScanReport report(Class<? extends Holder> holderType) throws Exception {
        List<RuleScanResult> results = new ArrayList<>();
        add(results, "feed_holder_page_selected", holderType.getMethod("pageSelected"));
        add(results, "feed_holder_selected", holderType.getMethod("selected", int.class));
        addType(results, "feed_holder_main_root", holderType);
        add(results, "image_holder_page_selected", ImageHolder.class.getMethod("pageSelected"));
        addType(results, "image_holder_main_root", ImageHolder.class);
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
            assertNotNull(hook);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return owner;
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
