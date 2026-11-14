package com.douai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class FeedUiHideHookTest {
    public static final class FeedHolderFixture {
        public FrameLayout w;
        public FrameLayout f;
        public String aid = "current";
        public AwemeFixture getAweme() { return new AwemeFixture(aid); }

        public void onVideoPageSelected() { }
        public void onImagePageSelected() { }
        public void onVideoResume() { }
        public void onVideoPause() { }
        public void onImageResume() { }
        public void onImagePause() { }
    }

    public static final class AwemeFixture {
        private final String aid;
        AwemeFixture(String aid) { this.aid = aid; }
        public String getAid() { return aid; }
    }

    public static final class PlayerFixture {
        public void onPause(String aid) { }
        public void onPlaying(String aid) { }
    }

    @Test
    public void matchesThe40_6FeedActionAndAuthorViewIds() {
        int[] resourceIds = {
                2131374327, 2131374339, 2131374340, 2131374360,
                2131371285, 2131371299, 2131371376, 2131371605,
                2131401927, 2131405175, 2131405391,
                2131415727, 2131415055, 2131415033, 2131379772, 2131379761,
                2131370692, 2131386238, 2131380062, 2131380063,
                2131398470, 2131401805, 2131410333
        };

        for (int resourceId : resourceIds) {
            assertTrue("Expected feed control resource ID " + resourceId,
                    FeedUiHideHook.isHiddenResourceId(resourceId));
        }
    }

    @Test
    public void leavesUnrelatedFeedResourcesVisible() {
        assertFalse(FeedUiHideHook.isHiddenResourceId(0x7f0a9734));
        assertFalse(FeedUiHideHook.isHiddenResourceId(0));
    }

    @Test
    public void matchesAccessibleFeedActionsWithoutDependingOnResourceNames() {
        assertTrue(FeedUiHideHook.matchesDescription("点赞 2.4 万"));
        assertTrue(FeedUiHideHook.matchesDescription("评论, 149"));
        assertTrue(FeedUiHideHook.matchesDescription("收藏"));
        assertTrue(FeedUiHideHook.matchesDescription("分享"));
        assertFalse(FeedUiHideHook.matchesDescription("Demon 发布了新作品"));
        assertFalse(FeedUiHideHook.matchesDescription("视频播放"));
    }

    @Test
    public void hidesVideoAndImageActionsAgainAfterHostRebindAndRestoresOnClose() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374327);
        digg.setVisibility(View.VISIBLE);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), enabledSettings(), ignored -> { });

        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, digg.getVisibility());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertTrue(hook.getPreDrawRechecks() > 0);
        assertEquals(1, hook.getHostStateResets());

        digg.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, digg.getVisibility());
        assertEquals(2, hook.getHostStateResets());

        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, digg.getVisibility());
        activity.finish();
    }

    @Test
    public void appliesDyydsStyleRightActionOpacityAndRestoresAtFullOpacity() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView digg = new TextView(activity);
        digg.setId(2131374327);
        root.addView(digg, new FrameLayout.LayoutParams(120, 48));
        activity.setContentView(root);

        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings(false, 35), ignored -> { });
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();

        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.35f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 0));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, digg.getVisibility());
        assertEquals(0.0f, digg.getAlpha(), 0.001f);

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(1.0f, digg.getAlpha(), 0.001f);
        hook.close();
        activity.finish();
    }

    @Test
    public void hidesAnonymousWidgetChildrenAndAuthorButKeepsMediaVisible() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View media = new View(activity);
        root.addView(media);
        FrameLayout widgets = new FrameLayout(activity);
        widgets.setId(2131362267);
        widgets.setAlpha(0.8f);
        widgets.setClickable(true);
        widgets.setFocusable(false);
        root.addView(widgets);
        widgets.addView(new View(activity));
        widgets.addView(new TextView(activity));
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        widgets.addView(right);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        author.setClickable(false);
        author.setFocusable(true);
        root.addView(author);
        activity.setContentView(root);
        FeedHolderFixture holder = holder(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());

        intercept(hooks.get("DouAi.FeedUi.ImageResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, widgets.getVisibility());
        assertFalse(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.INVISIBLE, author.getVisibility());
        assertFalse(author.isFocusable());
        assertEquals(View.VISIBLE, media.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());

        hook.setSettings(settings(false, 35));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(0.35f, right.getAlpha(), 0.001f);
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertTrue(author.isFocusable());

        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, widgets.getVisibility());
        assertEquals(0.8f, widgets.getAlpha(), 0.001f);
        assertEquals(1f, right.getAlpha(), 0.001f);
        assertTrue(widgets.isClickable());
        assertFalse(widgets.isFocusable());
        assertEquals(View.VISIBLE, author.getVisibility());
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertFalse(author.isClickable());
        assertTrue(author.isFocusable());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void appliesOpacityOnceToRightRegionWithoutChangingAuthorOrChildAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        right.setAlpha(0.6f);
        root.addView(right);
        TextView star = new TextView(activity);
        star.setId(2131374340);
        star.setAlpha(0.8f);
        right.addView(star);
        right.addView(new View(activity));
        TextView author = new TextView(activity);
        author.setId(2131398470);
        author.setAlpha(0.7f);
        root.addView(author);
        root.setClickable(true);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        FeedHolderFixture holder = holder(root);

        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        assertEquals(0.7f, author.getAlpha(), 0.001f);
        assertEquals(1.0f, root.getAlpha(), 0.001f);
        assertEquals(1, hook.getHiddenViews());

        right.setAlpha(1f);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(0.21f, right.getAlpha(), 0.001f);
        assertEquals(0.8f, star.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(0.6f, right.getAlpha(), 0.001f);
        assertTrue(root.isClickable());
        activity.finish();
    }

    @Test
    public void preDrawReappliesCachedTargetsWithoutSearchingTheViewTreeAgain() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        root.addView(right);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        FeedHolderFixture holder = holder(root);

        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        int searchesAfterBind = hook.getViewSearches();
        assertEquals(1, searchesAfterBind);

        for (int frame = 0; frame < 12; frame++) {
            right.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.35f, right.getAlpha(), 0.001f);
        }

        assertEquals(searchesAfterBind, hook.getViewSearches());
        assertTrue(hook.getPreDrawRechecks() >= 12);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void aCustomHideRetainsPriorityOverTheSameCachedComponentOnStableFrames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View feedback = new View(activity);
        feedback.setId(2131371909);
        root.addView(feedback);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(root.getRootView(), root, feedback);
        assertNotNull(locator);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "128")
                .putString(ComponentHideSettings.CUSTOM_VIEWS,
                        CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 12; frame++) {
                feedback.setVisibility(View.VISIBLE);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("Custom hide must win even when the window observer runs before the card", View.GONE,
                        feedback.getVisibility());
            }
            preferences.edit().remove(ComponentHideSettings.CUSTOM_VIEWS).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, feedback.getVisibility());
            assertEquals(128 / 255f, feedback.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeStableTreeKeepsStructureWorkBoundedAcross300Frames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        root.addView(right);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 35));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int searches = hook.getViewSearches();
            long before = hook.getStructureNodeChecks();
            for (int frame = 0; frame < 300; frame++) {
                right.setAlpha(1f);
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals("Cached styles must still be maintained every frame", 0.35f, right.getAlpha(), 0.001f);
            }
            long checks = hook.getStructureNodeChecks() - before;
            System.out.println("Feed stable 300 frames: structureNodeChecks=" + checks
                    + ", discoveries=" + (hook.getViewSearches() - searches));
            assertEquals(searches, hook.getViewSearches());
            assertTrue("Stable large trees retain the 16-frame fallback with far fewer checks; actual=" + checks,
                    checks > 0 && checks <= 300L * 70);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void unchangedGlobalLayoutsDoNotRediscoverTargets() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View target = new View(activity);
        target.setId(2131374327);
        root.addView(target);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int searches = hook.getViewSearches();
            for (int frame = 0; frame < 30; frame++) {
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.INVISIBLE, target.getVisibility());
            }
            System.out.println("Feed unchanged 30 layouts: discoveries=" + (hook.getViewSearches() - searches));
            assertEquals("Layout notifications alone must not rediscover an unchanged tree", searches, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeWindowCustomTargetKeepsChecksAndLayoutRediscoveryBounded() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        for (int index = 0; index < 1024; index++) page.addView(new View(activity));
        TextView custom = new TextView(activity);
        custom.setId(android.R.id.text1);
        page.addView(custom);
        activity.setContentView(page);
        CustomViewLocator locator = CustomViewLocator.capture(page.getRootView(), card, custom);
        assertNotNull(locator);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            int searches = hook.getViewSearches();
            long before = hook.getStructureNodeChecks();
            for (int frame = 0; frame < 300; frame++) {
                custom.setVisibility(View.VISIBLE);
                page.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(View.GONE, custom.getVisibility());
            }
            long checks = hook.getStructureNodeChecks() - before;
            int stableDiscoveries = hook.getViewSearches() - searches;
            for (int frame = 0; frame < 30; frame++) {
                page.getViewTreeObserver().dispatchOnGlobalLayout();
                page.getViewTreeObserver().dispatchOnPreDraw();
            }
            int layoutDiscoveries = hook.getViewSearches() - searches - stableDiscoveries;
            System.out.println("Feed window 300 frames: structureNodeChecks=" + checks
                    + ", discoveries=" + stableDiscoveries + "; unchanged 30 layouts: discoveries=" + layoutDiscoveries);
            assertEquals(0, stableDiscoveries);
            assertEquals("Unchanged window layouts must not restart custom locator discovery", 0, layoutDiscoveries);
            assertTrue("Stable card and window checks must stay below their old per-frame budgets; actual=" + checks,
                    checks > 0 && checks <= 300L * 80);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void largeTreeFindsLateInsertionAndReusedTextWithin16FramesWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        TextView label = new TextView(activity);
        tail.addView(label);
        root.addView(tail);
        int[] hostHierarchyEvents = {0};
        tail.setOnHierarchyChangeListener(new android.view.ViewGroup.OnHierarchyChangeListener() {
            @Override public void onChildViewAdded(View parent, View child) { hostHierarchyEvents[0]++; }
            @Override public void onChildViewRemoved(View parent, View child) { hostHierarchyEvents[0]++; }
        });
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            View inserted = new View(activity);
            inserted.setId(2131374327);
            tail.addView(inserted);
            for (int frame = 0; frame < 16; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Insertion at the end of a large tree must be detected without a layout event", View.INVISIBLE, inserted.getVisibility());
            assertEquals("The host's hierarchy observer must remain installed", 1, hostHierarchyEvents[0]);
            label.setText("分享");
            for (int frame = 0; frame < 16; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.INVISIBLE, label.getVisibility());
            label.setText("正常文案");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Previously matched target identity changes must restore on the next frame", View.VISIBLE, label.getVisibility());
            hook.close();
            idleMainLooper();
            assertEquals(View.VISIBLE, inserted.getVisibility());
            long checks = hook.getStructureNodeChecks();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(checks, hook.getStructureNodeChecks());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void quietLargeTreeFinishesCheckingLateInsertionAfterOneLayoutAndDraw() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        root.addView(tail);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            requestedFrames.clear();
            View inserted = new View(activity);
            inserted.setId(2131374327);
            tail.addView(inserted);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            long checks = hook.getStructureNodeChecks();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(hook.getStructureNodeChecks() - checks <= 256);

            // The host is now idle. Only redraws explicitly requested by the hook may run.
            int extraFrames = 0;
            while (!requestedFrames.isEmpty()) {
                assertTrue("A layout sweep must stop after a bounded number of frames", extraFrames++ < 16);
                java.util.List<View> frames = new java.util.ArrayList<>(requestedFrames);
                requestedFrames.clear();
                for (View frame : frames) {
                    checks = hook.getStructureNodeChecks();
                    frame.getViewTreeObserver().dispatchOnPreDraw();
                    assertTrue("Each requested frame retains the structure budget",
                            hook.getStructureNodeChecks() - checks <= 256);
                }
            }
            assertEquals("An idle host must not leave a late feed control visible", View.INVISIBLE, inserted.getVisibility());
            assertTrue("The tail was outside the first frame's budget", extraFrames > 0);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void quietLargeWindowFindsLateCustomTargetAfterItsInitialRetriesAreExhausted() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        for (int index = 0; index < 1024; index++) page.addView(new View(activity));
        FrameLayout tail = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setId(android.R.id.text1);
        tail.addView(custom);
        page.addView(tail);
        activity.setContentView(page);
        CustomViewLocator locator = CustomViewLocator.capture(page.getRootView(), card, custom);
        assertNotNull(locator);
        tail.removeView(custom);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            for (int frame = 0; frame < 8; frame++) page.getViewTreeObserver().dispatchOnPreDraw();
            requestedFrames.clear();
            tail.addView(custom);
            page.getViewTreeObserver().dispatchOnGlobalLayout();
            page.getViewTreeObserver().dispatchOnPreDraw();
            drainRequestedStructureFrames(hook, requestedFrames, 512);
            assertEquals("An idle window must rediscover a custom view inserted after retries ran out",
                    View.GONE, custom.getVisibility());

            int searches = hook.getViewSearches();
            page.getViewTreeObserver().dispatchOnGlobalLayout();
            page.getViewTreeObserver().dispatchOnPreDraw();
            drainRequestedStructureFrames(hook, requestedFrames, 512);
            assertEquals("Finishing a stable layout sweep must not rediscover targets", searches, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void closingDuringALayoutSweepDoesNotContinueCheckingOrRequestMoreFrames() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        for (int index = 0; index < 1024; index++) root.addView(new View(activity));
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        java.util.Set<View> requestedFrames = new java.util.LinkedHashSet<>();
        hook.requestStructureFrame = view -> requestedFrames.add(view.getRootView());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            requestedFrames.clear();
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse("A large stable layout needs a bounded follow-up sweep", requestedFrames.isEmpty());
            hook.close();
            long checks = hook.getStructureNodeChecks();
            drainRequestedStructureFrames(hook, requestedFrames, 0);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(checks, hook.getStructureNodeChecks());
            assertTrue(requestedFrames.isEmpty());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    private static void drainRequestedStructureFrames(FeedUiHideHook hook, java.util.Set<View> requestedFrames,
                                                       int perFrameBudget) {
        int extraFrames = 0;
        while (!requestedFrames.isEmpty()) {
            assertTrue("A layout sweep must stop after a bounded number of frames", extraFrames++ < 16);
            java.util.List<View> frames = new java.util.ArrayList<>(requestedFrames);
            requestedFrames.clear();
            for (View frame : frames) {
                long checks = hook.getStructureNodeChecks();
                frame.getViewTreeObserver().dispatchOnPreDraw();
                assertTrue("Requested frames must retain the per-monitor structure budget",
                        hook.getStructureNodeChecks() - checks <= perFrameBudget);
            }
        }
    }

    @Test
    public void layoutInvalidatesCachedTargetsForLateInsertedControls() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout nested = new FrameLayout(activity);
        root.addView(nested);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        int initialSearches = hook.getViewSearches();

        View lateControl = new View(activity);
        lateControl.setId(2131374327);
        nested.addView(lateControl);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, lateControl.getVisibility());
        assertEquals(initialSearches + 1, hook.getViewSearches());
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(initialSearches + 1, hook.getViewSearches());

        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, lateControl.getVisibility());
        int searchesAfterClose = hook.getViewSearches();
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(searchesAfterClose, hook.getViewSearches());
        activity.finish();
    }

    @Test
    public void delayedProgressEscapesPreviouslyHiddenContainerWithoutAnotherLayoutEvent() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bottomTools = new FrameLayout(activity);
        bottomTools.setId(2131367357);
        root.addView(bottomTools);
        View tool = new View(activity);
        bottomTools.addView(tool);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.GONE, bottomTools.getVisibility());
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();

            View progress = new View(activity);
            progress.setId(2131378139);
            progress.setAlpha(0.7f);
            bottomTools.addView(progress);
            root.getViewTreeObserver().dispatchOnPreDraw();

            assertTrue("A kept progress bar must not inherit a cached container hide", progress.isShown());
            assertEquals(0.7f, progress.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedProgressPreservesHostRevealOfAnInitiallyGoneContainer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        tools.setVisibility(View.GONE);
        tools.setAlpha(0f);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
            tools.addView(progress);
            tools.setVisibility(View.VISIBLE);
            tools.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Restoring a shared container must preserve the host's later reveal", progress.isShown());
            assertEquals("The host's fade-in must not be replaced by the initial zero alpha", 1f,
                    tools.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(progress.isShown());
            assertEquals(View.VISIBLE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void switchingGoneToOpacityKeepsHostFadeInBeforeTheNextDraw() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        like.setAlpha(0f);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            like.setVisibility(View.VISIBLE);
            like.setAlpha(1f);
            preferences.edit().putString(Component.LIKE.key, "128").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(128 / 255f, like.getAlpha(), 0.001f);
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(1f, like.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void alternateHostProgressViewsKeepTheirBranchAndHostVisibility() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        View line = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        View alternate = new com.ss.android.ugc.aweme.shortvideo.ui.NewVideoPlayerProgressbar(activity);
        alternate.setVisibility(View.GONE);
        tools.addView(line);
        tools.addView(alternate);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertTrue("The line progress variant must escape bottom-tools hiding", line.isShown());
            assertEquals(View.GONE, alternate.getVisibility());
            assertEquals(View.GONE, tool.getVisibility());
            alternate.setVisibility(View.VISIBLE);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue("Host reveal of a kept alternate progress must remain visible", alternate.isShown());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressValueAppliesToProSeekbarOutsideSelectedCardAndRestoresOnPause() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout card = new FrameLayout(activity);
        page.addView(card);
        View progress = new com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView(activity);
        progress.setAlpha(1f);
        page.addView(progress);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(card));
            idleMainLooper();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            for (int frame = 0; frame < 12; frame++) page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(1f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            assertEquals(124 / 255f, progress.getAlpha(), 0.001f);
            preferences.edit().putString(Component.PROGRESS.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.GONE, progress.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "-1").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.INVISIBLE, progress.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, progress.getVisibility());
            assertEquals(1f, progress.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressAt124FollowsHostFadeInAndRestoresTheLatestHostAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View progress = new com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar(activity);
        progress.setVisibility(View.GONE);
        progress.setAlpha(0f);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        SharedPreferences.Editor editor = preferences.edit();
        for (Component component : Component.values()) editor.putString(component.key, "-2");
        editor.putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            progress.setVisibility(View.VISIBLE);
            progress.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(progress.isShown());
            assertEquals("Progress opacity must use the host's later fade-in", 124 / 255f,
                    progress.getAlpha(), 0.001f);
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Reapplying must not compound opacity each frame", 124 / 255f,
                    progress.getAlpha(), 0.001f);
            progress.setAlpha(0.5f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.5f * 124 / 255f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals("Pausing must restore the latest host alpha", 0.5f, progress.getAlpha(), 0.001f);
            hook.setComponentsTemporarilyShown(false);
            idleMainLooper();
            assertEquals(0.5f * 124 / 255f, progress.getAlpha(), 0.001f);
            progress.setVisibility(View.GONE);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("An opacity setting must not force a host-hidden progress visible",
                    View.GONE, progress.getVisibility());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.GONE, progress.getVisibility());
            assertEquals(0.5f, progress.getAlpha(), 0.001f);
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressValuesKeepOpacityInvisibilityAndLayoutRemovalDistinct() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.LinearLayout column = new android.widget.LinearLayout(activity);
        column.setOrientation(android.widget.LinearLayout.VERTICAL);
        root.addView(column);
        View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        column.addView(progress, new android.widget.LinearLayout.LayoutParams(100, 12));
        column.addView(new View(activity), new android.widget.LinearLayout.LayoutParams(100, 8));
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            int[] values = {-2, -1, 0, 64, 124, 255};
            int[] visibility = {View.GONE, View.INVISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE, View.VISIBLE};
            int[] heights = {8, 20, 20, 20, 20, 20};
            float[] alphas = {1f, 1f, 0f, 64 / 255f, 124 / 255f, 1f};
            for (int index = 0; index < values.length; index++) {
                preferences.edit().putString(Component.PROGRESS.key, Integer.toString(values[index])).commit();
                hook.setSettings(ExtendedSettings.readFrom(preferences));
                idleMainLooper();
                column.measure(View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.AT_MOST));
                assertEquals("visibility for " + values[index], visibility[index], progress.getVisibility());
                assertEquals("layout height for " + values[index], heights[index], column.getMeasuredHeight());
                assertEquals("alpha for " + values[index], alphas[index], progress.getAlpha(), 0.001f);
            }
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressMountAt124FollowsHostAlphaAndKeepsHiddenToolsSeparate() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        FrameLayout mount = new FrameLayout(activity);
        mount.setId(2131378139);
        mount.setAlpha(0f);
        tools.addView(mount);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            mount.setAlpha(1f);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(124 / 255f, mount.getAlpha(), 0.001f);
            assertTrue(mount.isShown());
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "255").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(1f, mount.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressDiagnosticsCaptureEnabledAndDisabledStatesAndStopOnClose() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        root.addView(new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity));
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "124").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        java.util.ArrayList<String> logs = new java.util.ArrayList<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), logs::add);
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            idleForMillis(3100L);
            assertEquals(3, logs.stream().filter(value -> value.contains(" reason=selection ")).count());
            assertTrue(logs.stream().anyMatch(value -> value.contains("LineProgressBar")));
            assertTrue(logs.stream().anyMatch(value -> value.contains("progress=124")));
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(logs.stream().anyMatch(value -> value.contains("reason=settings")
                    && value.contains("enabled=false")));
            hook.close();
            idleMainLooper();
            int afterClose = logs.size();
            idleForMillis(10000L);
            assertEquals("Close must cancel delayed diagnostic samples", afterClose, logs.size());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void progressDiagnosticSelectionBudgetReservesSamplesForSettingsChanges() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        java.util.ArrayList<String> logs = new java.util.ArrayList<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), ExtendedSettings.readFrom(preferences), logs::add);
        FeedHolderFixture holder = holder(root);
        try {
            for (int card = 0; card < 8; card++) {
                holder.aid = "card" + card;
                intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
                idleMainLooper();
                idleForMillis(3100L);
            }
            assertEquals(18, logs.stream().filter(value -> value.contains(" reason=selection ")).count());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            idleForMillis(3100L);
            assertEquals(3, logs.stream().filter(value -> value.contains(" reason=settings ")).count());
            assertFalse(logs.stream().anyMatch(value -> value.contains("aid=") || value.contains("card7")));
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void chapterProgressUsesItsIndependentSettingInsideSharedTools() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout tools = new FrameLayout(activity);
        tools.setId(2131367357);
        root.addView(tools);
        View tool = new View(activity);
        tools.addView(tool);
        View chapter = new com.ss.android.ugc.aweme.feed.widget.ChapterOuterSeekBar(activity);
        chapter.setAlpha(0.75f);
        tools.addView(chapter);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertTrue("Retained chapter progress must escape shared container hiding", chapter.isShown());
            assertEquals(0.75f, chapter.getAlpha(), 0.001f);
            assertEquals(View.GONE, tool.getVisibility());
            preferences.edit().putString(Component.PROGRESS.key, "-2").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertFalse(chapter.isShown());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertTrue(chapter.isShown());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedFeedbackInsertedIntoUntargetedBranchIsHiddenBeforeDrawing() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout nested = new FrameLayout(activity);
        root.addView(nested);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            View feedback = new View(activity);
            feedback.setId(2131371909);
            nested.addView(feedback);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Late feedback must join the active hide targets", View.GONE, feedback.getVisibility());
            int discovered = hook.getViewSearches();
            for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Stable frames must still reuse cached targets", discovered, hook.getViewSearches());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedRecommendationTagUsesFeedbackSettingWithoutMatchingCaptionText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.TextView caption = new android.widget.TextView(activity);
        caption.setText("这段视频获得了100人推荐");
        root.addView(caption);
        View progress = new View(activity);
        progress.setId(2131378139);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            com.ss.android.ugc.aweme.base.ui.TagLayout tag = new com.ss.android.ugc.aweme.base.ui.TagLayout(activity);
            android.widget.TextView count = new android.widget.TextView(activity);
            count.setText("100人推荐");
            tag.addView(count);
            root.addView(tag);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, tag.getVisibility());
            assertTrue(progress.isShown());
            assertTrue(caption.isShown());
            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, tag.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void delayedDeclarationAndAnchorStripFollowTheirOwnSettingsAndKeepProgress() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View progress = new com.ss.android.ugc.aweme.feed.widget.LineProgressBar(activity);
        root.addView(progress);
        TextView caption = new TextView(activity);
        caption.setText("作者声明和位置只是视频里的文案");
        root.addView(caption);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            View declaration = new View(activity);
            declaration.setId(2131378332);
            android.widget.LinearLayout declarationBackground = new android.widget.LinearLayout(activity);
            declarationBackground.setBackgroundColor(android.graphics.Color.BLACK);
            declarationBackground.addView(declaration);
            FrameLayout anchors = new FrameLayout(activity);
            anchors.setId(2131378377);
            TextView poi = new TextView(activity);
            poi.setText("泰州市·兴化市");
            anchors.addView(poi);
            FrameLayout anonymousAnchor = new com.ss.android.ugc.aweme.feed.anchor.framework.base.MaxWidthFrameLayout(activity);
            FrameLayout legacyAnchor = new com.ss.android.ugc.aweme.base.ui.FeedAnchorLayout(activity);
            root.addView(declarationBackground);
            root.addView(anchors);
            root.addView(anonymousAnchor);
            root.addView(legacyAnchor);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertFalse(declaration.isShown());
            assertEquals(View.GONE, declarationBackground.getVisibility());
            assertEquals(View.GONE, anchors.getVisibility());
            assertEquals(View.GONE, anonymousAnchor.getVisibility());
            assertEquals(View.GONE, legacyAnchor.getVisibility());
            assertTrue(progress.isShown());
            assertTrue(caption.isShown());
            preferences.edit().putString(Component.FEEDBACK.key, "255").commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, declaration.getVisibility());
            assertEquals(View.VISIBLE, declarationBackground.getVisibility());
            assertEquals(View.GONE, anchors.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void declarationBackgroundChangesInvalidateTargetsWithoutChangingTheTree() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        android.widget.LinearLayout wrapper = new android.widget.LinearLayout(activity);
        View declaration = new View(activity);
        declaration.setId(2131378332);
        wrapper.addView(declaration);
        root.addView(wrapper);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.GONE, declaration.getVisibility());
            assertEquals(View.VISIBLE, wrapper.getVisibility());
            wrapper.setBackgroundColor(android.graphics.Color.BLACK);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("A newly bound declaration background must also be hidden", View.GONE, wrapper.getVisibility());
            wrapper.setBackground(null);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("An anonymous wrapper without a background is no longer a hide target", View.VISIBLE, wrapper.getVisibility());
            assertEquals(View.GONE, declaration.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void lateTextBindingOnExistingViewInvalidatesLegacyMatching() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView label = new TextView(activity);
        root.addView(label);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            assertEquals(View.VISIBLE, label.getVisibility());
            label.setText("分享");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.INVISIBLE, label.getVisibility());
            label.setText("正常文案");
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, label.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void reusedUntargetedViewAndEqualSizedBranchReplacementInvalidateDiscovery() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout branch = new FrameLayout(activity);
        root.addView(branch);
        View reused = new View(activity);
        branch.addView(reused);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.FEEDBACK.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            reused.setId(2131371909);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, reused.getVisibility());
            FrameLayout replacement = new FrameLayout(activity);
            View feedback = new View(activity);
            feedback.setId(2131371909);
            replacement.addView(feedback);
            root.removeView(branch);
            root.addView(replacement);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, reused.getVisibility());
            assertEquals(View.GONE, feedback.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void customTargetInsertedAfterRetriesAreExhaustedIsDiscoveredWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout branch = new FrameLayout(activity);
        root.addView(branch);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        android.widget.TextView target = new android.widget.TextView(activity);
        target.setText("延迟插入的自定义控件");
        branch.addView(target);
        com.douai.module.config.CustomViewLocator locator = com.douai.module.config.CustomViewLocator.capture(
                root.getRootView(), root, target);
        branch.removeView(target);
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                com.douai.module.config.CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
            idleMainLooper();
            for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
            branch.addView(target);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.GONE, target.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void reusedTargetIdDescriptionAndTextAreRevalidatedWithoutLayout() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View byId = new View(activity);
        byId.setId(2131374327);
        View byDescription = new View(activity);
        byDescription.setContentDescription("like");
        TextView byText = new TextView(activity);
        byText.setText("comment");
        root.addView(byId);
        root.addView(byDescription);
        root.addView(byText);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.INVISIBLE, byId.getVisibility());
        assertEquals(View.INVISIBLE, byDescription.getVisibility());
        assertEquals(View.INVISIBLE, byText.getVisibility());
        int initialSearches = hook.getViewSearches();

        byId.setId(View.NO_ID);
        byDescription.setContentDescription("video playback");
        byText.setText("video playback");
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, byId.getVisibility());
        assertEquals(View.VISIBLE, byDescription.getVisibility());
        assertEquals(View.VISIBLE, byText.getVisibility());
        assertEquals(initialSearches + 1, hook.getViewSearches());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void unresolvedCustomLocatorRetriesThenStopsUntilLayoutChanges() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setId(android.R.id.text1);
        root.addView(target);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(activity.getWindow().getDecorView(), root, target);
        assertNotNull(locator);
        root.removeView(target);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        int initialSearches = hook.getViewSearches();

        for (int frame = 0; frame < 20; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        int afterRetries = hook.getViewSearches();
        assertTrue(afterRetries > initialSearches);
        assertTrue(afterRetries <= initialSearches + 7);
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(afterRetries, hook.getViewSearches());

        root.addView(target);
        root.getViewTreeObserver().dispatchOnGlobalLayout();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, target.getVisibility());
        int afterInsert = hook.getViewSearches();
        for (int frame = 0; frame < 12; frame++) root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(afterInsert, hook.getViewSearches());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, target.getVisibility());
        activity.finish();
    }

    @Test
    public void reusedCustomTargetResourceIdRestoresOldViewAndFindsReplacement() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setId(android.R.id.text1);
        root.addView(target);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(activity.getWindow().getDecorView(), root, target);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, target.getVisibility());

        target.setId(android.R.id.text2);
        TextView replacement = new TextView(activity);
        replacement.setId(android.R.id.text1);
        root.addView(replacement);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, target.getVisibility());
        assertEquals(View.GONE, replacement.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, replacement.getVisibility());
        activity.finish();
    }

    @Test
    public void pauseRestoresStateAndResumeStartsMonitoringAgain() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout right = new FrameLayout(activity);
        right.setId(2131402182);
        right.setAlpha(0.75f);
        right.setClickable(true);
        right.setFocusable(true);
        root.addView(right);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(true, 40));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertFalse(right.isClickable());

        right.setVisibility(View.VISIBLE);
        right.setAlpha(1f);
        right.setClickable(true);
        right.setFocusable(true);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.3f, right.getAlpha(), 0.001f);
        assertFalse(right.isClickable());
        assertFalse(right.isFocusable());

        intercept(hooks.get("DouAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());
        assertEquals(0.75f, right.getAlpha(), 0.001f);
        assertTrue(right.isClickable());
        assertTrue(right.isFocusable());
        int rechecks = hook.getPreDrawRechecks();
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());
        hook.setSettings(settings(true, 20));
        idleMainLooper();
        assertEquals(View.VISIBLE, right.getVisibility());

        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, right.getVisibility());
        assertEquals(0.15f, right.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void liveEnableWorksOnSelectedCardAndDisableRemovesDrawObserver() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374327);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, settings(false, 100));
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());

        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(View.INVISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        int rechecks = hook.getPreDrawRechecks();
        hook.setSettings(settings(false, 100));
        idleMainLooper();
        assertEquals(View.VISIBLE, button.getVisibility());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(rechecks, hook.getPreDrawRechecks());

        hook.setSettings(settings(false, 60));
        idleMainLooper();
        assertEquals(0.6f, button.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(1f, button.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void closedHookIgnoresQueuedAndLateLifecycleCallbacks() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View button = new View(activity);
        button.setId(2131374327);
        root.addView(button);
        activity.setContentView(root);
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, enabledSettings());
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        hook.close();
        idleMainLooper();
        assertEquals(0, hook.getCardScans());
        assertEquals(View.VISIBLE, button.getVisibility());
        int callbacks = hook.getSelectedCallbacks();
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        hook.setSettings(enabledSettings());
        idleMainLooper();
        assertEquals(callbacks, hook.getSelectedCallbacks());
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.VISIBLE, button.getVisibility());
        activity.finish();
    }

    @Test
    public void componentsHaveIndependentValuesAndDoNotHideArbitraryText() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View avatar = new View(activity);
        avatar.setId(2131366240);
        avatar.setAlpha(0.8f);
        root.addView(avatar);
        View like = new View(activity);
        like.setId(2131374347);
        like.setClickable(true);
        root.addView(like);
        TextView description = new TextView(activity);
        description.setText("点赞，评论和分享都是视频内容");
        root.addView(description);
        View author = new View(activity);
        author.setId(2131398470);
        author.setVisibility(View.GONE);
        root.addView(author);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.AVATAR.key, "128")
                .putString(Component.LIKE.key, "-2").putString(Component.AUTHOR.key, "128").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(0.8f * 128 / 255f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, like.getVisibility());
        assertFalse(like.isClickable());
        assertEquals(View.GONE, author.getVisibility());
        assertEquals(View.VISIBLE, description.getVisibility());
        assertEquals(View.VISIBLE, root.getVisibility());
        like.setVisibility(View.VISIBLE);
        root.getViewTreeObserver().dispatchOnPreDraw();
        assertEquals(View.GONE, like.getVisibility());

        preferences.edit().putString(Component.LIKE.key, "255")
                .putBoolean(ComponentHideSettings.ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        assertEquals(0.8f, avatar.getAlpha(), 0.001f);
        assertEquals(View.GONE, author.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void independentHidePreservesProgressInsideAnotherComponentContainer() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout bottomTools = new FrameLayout(activity);
        bottomTools.setId(2131367357);
        root.addView(bottomTools);
        View tool = new View(activity);
        bottomTools.addView(tool);
        View progress = new View(activity);
        progress.setId(2131378139);
        progress.setAlpha(0.7f);
        progress.setClickable(true);
        bottomTools.addView(progress);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        View comment = new View(activity);
        comment.setId(2131371414);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(Component.COMMENT.key, "-2")
                .putString(Component.BOTTOM_TOOLS.key, "-2")
                .putString(Component.PROGRESS.key, "255").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();

        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        assertTrue(progress.isClickable());
        preferences.edit().putString(Component.PROGRESS.key, "128").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertTrue(progress.isShown());
        assertEquals(0.7f * 128 / 255f, progress.getAlpha(), 0.001f);
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "-2").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, bottomTools.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false)
                .putString(Component.PROGRESS.key, "255").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, bottomTools.getVisibility());
        assertEquals(View.GONE, tool.getVisibility());
        assertTrue(progress.isShown());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, tool.getVisibility());
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(0.7f, progress.getAlpha(), 0.001f);
        activity.finish();
    }

    @Test
    public void allMatchingControlsAreHiddenWhenHostDuplicatesAResourceId() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View first = new View(activity);
        first.setId(2131374347);
        root.addView(first);
        View second = new View(activity);
        second.setId(2131374347);
        root.addView(second);
        View progress = new View(activity);
        progress.setId(2131378139);
        root.addView(progress);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, first.getVisibility());
        assertEquals(View.GONE, second.getVisibility());
        assertTrue(progress.isShown());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, first.getVisibility());
        assertEquals(View.VISIBLE, second.getVisibility());
        activity.finish();
    }

    @Test
    public void uniformOpacityOverridesIndividualsAndZeroRemainsClickable() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        like.setClickable(true);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "0").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(0f, like.getAlpha(), 0.001f);
        assertTrue(like.isClickable());
        preferences.edit().putString(ComponentHideSettings.UNIFORM_ALPHA, "-1").commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        assertFalse(like.isClickable());
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertTrue(like.isClickable());
        activity.finish();
    }

    @Test
    public void collectionSettingDoesNotAffectTheAvatarFollowButton() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View collect = new View(activity);
        collect.setId(2131371073);
        root.addView(collect);
        View follow = new View(activity);
        follow.setId(2131379772);
        root.addView(follow);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COLLECT.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, collect.getVisibility());
        assertEquals(View.VISIBLE, follow.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void playerPauseRequiresCurrentAidAndOpensCommentsOnlyOncePerShowTransition() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371414);
        int[] clicks = {0};
        comment.setOnClickListener(view -> clicks[0]++);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, true).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "old");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(0, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(1, clicks[0]);
        intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        holder.aid = "changed-before-queued-callback";
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        assertEquals(1, clicks[0]);
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void gestureComponentToggleWorksWithoutPausePolicy() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View comment = new View(activity);
        comment.setId(2131371414);
        root.addView(comment);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.COMMENT.key, "-2")
                .putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(true);
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, comment.getVisibility());
        hook.setComponentsTemporarilyShown(false);
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, comment.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void switchOptionControlsWhetherPauseRevealCarriesToTheNextCard() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-1")
                .putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, false).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(root);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        holder.aid = "next";
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, true).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        holder.aid = "third";
        intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
        idleMainLooper();
        assertEquals(View.INVISIBLE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test public void earlyPlayingRestoresConfiguredAlphaOnBookmarkDetailAndRejectsDepartedWork() throws Throwable {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout window=new FrameLayout(a), first=new FrameLayout(a), detail=new FrameLayout(a);
        View like=new View(a); like.setId(2131374347); detail.addView(like);
        window.addView(first); window.addView(detail); a.setContentView(window);
        SharedPreferences prefs=componentPreferences();
        prefs.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH,false).putString(Component.LIKE.key,"40").commit();
        Map<String,XposedInterface.Hooker> hooks=new HashMap<>();
        FeedUiHideHook hook=install(hooks,ExtendedSettings.readFrom(prefs));
        FeedHolderFixture h1=holder(first), h2=holder(detail); h2.aid="detail";
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"),h1);
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"),new PlayerFixture(),"current");
            idleMainLooper();assertTrue(hook.areComponentsTemporarilyShown());
            intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"),new PlayerFixture(),"detail");
            intercept(hooks.get("DouAi.FeedUi.VideoResume"),h2);
            idleMainLooper(); assertFalse(hook.areComponentsTemporarilyShown());
            assertEquals(40/255f,like.getAlpha(),0.001f);
            hook.syncBookmarkPlayback(h1,true);
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"),new PlayerFixture(),"current");
            idleMainLooper();assertFalse(hook.areComponentsTemporarilyShown());
            hook.syncBookmarkPlayback(h2,true);idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());assertEquals(1f,like.getAlpha(),0.001f);
            intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"),new PlayerFixture(),"detail");
            idleMainLooper();assertFalse(hook.areComponentsTemporarilyShown());
        }finally{hook.close();idleMainLooper();a.finish();}
    }

    @Test public void earlyPauseFollowsReusedRootAndDisabledPolicyDoesNotReveal() throws Throwable {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get(); FrameLayout root=new FrameLayout(a);
        View like=new View(a); like.setId(2131374347);root.addView(like);a.setContentView(root);
        SharedPreferences prefs=componentPreferences();
        prefs.edit().putString(Component.LIKE.key,"-2").commit();
        Map<String,XposedInterface.Hooker> hooks=new HashMap<>(); FeedUiHideHook hook=install(hooks,ExtendedSettings.readFrom(prefs));
        FeedHolderFixture holder=holder(root);
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"),holder);idleMainLooper();
            assertEquals(View.GONE,like.getVisibility());
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"),new PlayerFixture(),"target");idleMainLooper();
            assertFalse(hook.areComponentsTemporarilyShown());
            holder.aid="target";intercept(hooks.get("DouAi.FeedUi.HideVideoActions"),holder);idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());assertEquals(View.VISIBLE,like.getVisibility());
            prefs.edit().putBoolean(ComponentHideSettings.SHOW_ON_PAUSE,false).commit();
            hook.setSettings(ExtendedSettings.readFrom(prefs));idleMainLooper();
            hook.syncBookmarkPlayback(holder,true);idleMainLooper();
            assertFalse(hook.areComponentsTemporarilyShown());assertEquals(View.GONE,like.getVisibility());
        }finally{hook.close();idleMainLooper();a.finish();}
    }

    @Test public void lateWorkBindingConsumesInitialPauseAndMasterOffIgnoresBookmarkState() throws Throwable {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root=new FrameLayout(a);View like=new View(a);like.setId(2131374347);root.addView(like);
        a.setContentView(root);
        SharedPreferences prefs=componentPreferences();prefs.edit().putString(Component.LIKE.key,"-2").commit();
        Map<String,XposedInterface.Hooker> hooks=new HashMap<>();FeedUiHideHook hook=install(hooks,ExtendedSettings.readFrom(prefs));
        FeedHolderFixture h=holder(root); h.aid=null;
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"),h);
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"),new PlayerFixture(),"target");idleMainLooper();
            assertFalse(hook.areComponentsTemporarilyShown());
            h.aid="target";root.getViewTreeObserver().dispatchOnPreDraw();
            assertTrue(hook.areComponentsTemporarilyShown());assertEquals(View.VISIBLE,like.getVisibility());
            prefs.edit().putBoolean(ComponentHideSettings.ENABLED,false).commit();
            hook.setSettings(ExtendedSettings.readFrom(prefs));idleMainLooper();
            hook.syncBookmarkPlayback(h,false);hook.syncBookmarkPlayback(h,true);idleMainLooper();
            assertFalse(hook.areComponentsTemporarilyShown());assertEquals(View.VISIBLE,like.getVisibility());
        }finally{hook.close();idleMainLooper();a.finish();}
    }

    @Test
    public void disablingPauseRevealReappliesHidingImmediately() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        root.addView(like);
        activity.setContentView(root);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2").commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
        idleMainLooper();
        assertTrue(hook.areComponentsTemporarilyShown());
        assertEquals(View.VISIBLE, like.getVisibility());
        preferences.edit().putBoolean(ComponentHideSettings.SHOW_ON_PAUSE, false).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertFalse(hook.areComponentsTemporarilyShown());
        assertEquals(View.GONE, like.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void customOpacityIsIndependentAndCachedFramesDoNotCompoundHostAlpha() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        TextView feedControl = new TextView(activity);
        feedControl.setId(android.R.id.text1);
        feedControl.setAlpha(0.8f);
        feedControl.setClickable(true);
        feedControl.setFocusable(true);
        feed.addView(feedControl);
        TextView windowControl = new TextView(activity);
        windowControl.setId(android.R.id.text2);
        windowControl.setAlpha(0.6f);
        page.addView(feed);
        page.addView(windowControl);
        activity.setContentView(page);
        CustomViewLocator feedLocator = CustomViewLocator.capture(page.getRootView(), feed, feedControl);
        CustomViewLocator windowLocator = CustomViewLocator.capture(page.getRootView(), feed, windowControl);
        JSONObject rules = new JSONObject(CustomViewLocator.serialize(Arrays.asList(feedLocator, windowLocator)));
        rules.getJSONArray("items").getJSONObject(0).put("alpha", 128);
        rules.getJSONArray("items").getJSONObject(1).put("alpha", 64);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true)
                .putString(ComponentHideSettings.UNIFORM_ALPHA, "255")
                .putString(ComponentHideSettings.CUSTOM_VIEWS, rules.toString()).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(feed));
            idleMainLooper();
            assertEquals(View.VISIBLE, feedControl.getVisibility());
            assertEquals(0.4015686f, feedControl.getAlpha(), 0.001f);
            assertEquals(0.1505882f, windowControl.getAlpha(), 0.001f);
            assertTrue(feedControl.isClickable());
            for (int frame = 0; frame < 12; frame++) page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.4015686f, feedControl.getAlpha(), 0.001f);

            feedControl.setAlpha(0.5f);
            windowControl.setVisibility(View.INVISIBLE);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0.2509804f, feedControl.getAlpha(), 0.001f);
            assertEquals(View.INVISIBLE, windowControl.getVisibility());
            rules.getJSONArray("items").getJSONObject(0).put("alpha", -1);
            rules.getJSONArray("items").getJSONObject(1).put("alpha", 255);
            preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS, rules.toString()).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.INVISIBLE, feedControl.getVisibility());
            assertFalse(feedControl.isClickable());
            assertEquals(View.INVISIBLE, windowControl.getVisibility());
            assertEquals(0.6f, windowControl.getAlpha(), 0.001f);

            preferences.edit().putBoolean(ComponentHideSettings.ENABLED, false).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            assertEquals(View.VISIBLE, feedControl.getVisibility());
            assertEquals(0.5f, feedControl.getAlpha(), 0.001f);
            assertTrue(feedControl.isClickable());
            assertTrue(feedControl.isFocusable());
            assertEquals(View.INVISIBLE, windowControl.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void playbackPauseRevealsBothCustomScopesAndPlayingRestoresTheirRules() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        TextView feedControl = new TextView(activity);
        feedControl.setId(android.R.id.text1);
        feedControl.setAlpha(0.8f);
        feed.addView(feedControl);
        TextView windowControl = new TextView(activity);
        windowControl.setId(android.R.id.text2);
        page.addView(feed);
        page.addView(windowControl);
        activity.setContentView(page);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Arrays.asList(
                        CustomViewLocator.capture(page.getRootView(), feed, feedControl),
                        CustomViewLocator.capture(page.getRootView(), feed, windowControl)))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(feed));
            idleMainLooper();
            assertEquals(View.GONE, feedControl.getVisibility());
            assertEquals(View.GONE, windowControl.getVisibility());
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
            idleMainLooper();
            assertTrue(hook.areComponentsTemporarilyShown());
            assertEquals(View.VISIBLE, feedControl.getVisibility());
            assertEquals(View.VISIBLE, windowControl.getVisibility());
            assertEquals(0.8f, feedControl.getAlpha(), 0.001f);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, feedControl.getVisibility());
            assertEquals(View.VISIBLE, windowControl.getVisibility());
            intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals(View.GONE, feedControl.getVisibility());
            assertEquals(View.GONE, windowControl.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void pauseRevealsAModuleHiddenFeedAncestor() throws Throwable {
        assertPauseThroughCustomAncestor(View.VISIBLE, true);
    }

    @Test
    public void pauseIgnoresAHostHiddenFeedAncestor() throws Throwable {
        assertPauseThroughCustomAncestor(View.INVISIBLE, false);
        assertPauseThroughCustomAncestor(View.GONE, false);
    }

    private void assertPauseThroughCustomAncestor(int hostVisibility, boolean expectShown) throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(activity);
        page.setVisibility(hostVisibility);
        page.setAlpha(0.7f);
        FrameLayout feed = new FrameLayout(activity);
        page.addView(feed);
        activity.setContentView(page);
        CustomViewLocator locator = CustomViewLocator.capture(page.getRootView(), feed, page);
        assertNotNull(locator);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(feed));
            idleMainLooper();
            assertFalse(feed.isShown());
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals(expectShown, hook.areComponentsTemporarilyShown());
            assertEquals(hostVisibility, page.getVisibility());
            assertEquals(0.7f, page.getAlpha(), 0.001f);
            page.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(hostVisibility, page.getVisibility());
            if (expectShown) {
                intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
                idleMainLooper();
                assertEquals(View.GONE, page.getVisibility());
            }
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void playbackPauseRestoresCustomTargetsInEveryTrackedWindow() throws Throwable {
        Activity currentActivity = Robolectric.buildActivity(Activity.class).setup().get();
        Activity otherActivity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout feed = new FrameLayout(currentActivity);
        TextView currentControl = new TextView(currentActivity);
        currentControl.setId(android.R.id.text1);
        currentControl.setAlpha(0.8f);
        feed.addView(currentControl);
        currentActivity.setContentView(feed);
        FrameLayout otherPage = new FrameLayout(otherActivity);
        TextView otherControl = new TextView(otherActivity);
        otherControl.setId(android.R.id.text1);
        otherControl.setAlpha(0.6f);
        otherControl.setClickable(true);
        otherPage.addView(otherControl);
        otherActivity.setContentView(otherPage);
        CustomViewLocator locator = CustomViewLocator.capture(feed.getRootView(), currentControl);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(feed));
            hook.onActivityCreated(otherActivity, null);
            idleMainLooper();
            assertEquals(View.GONE, currentControl.getVisibility());
            assertEquals(View.GONE, otherControl.getVisibility());
            intercept(hooks.get("DouAi.FeedUi.PlayerPause"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals(View.VISIBLE, currentControl.getVisibility());
            assertEquals(View.VISIBLE, otherControl.getVisibility());
            assertEquals(0.8f, currentControl.getAlpha(), 0.001f);
            assertEquals(0.6f, otherControl.getAlpha(), 0.001f);
            assertTrue(otherControl.isClickable());
            otherPage.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(View.VISIBLE, otherControl.getVisibility());
            intercept(hooks.get("DouAi.FeedUi.PlayerPlaying"), new PlayerFixture(), "current");
            idleMainLooper();
            assertEquals(View.GONE, currentControl.getVisibility());
            assertEquals(View.GONE, otherControl.getVisibility());
        } finally {
            hook.close();
            idleMainLooper();
            currentActivity.finish();
            otherActivity.finish();
        }
    }

    @Test
    public void gestureRevealKeepsCustomViewsShownUntilConfiguredVideoSwitch() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout feed = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setAlpha(0.7f);
        feed.addView(custom);
        activity.setContentView(feed);
        SharedPreferences preferences = componentPreferences();
        preferences.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, false)
                .putString(ComponentHideSettings.CUSTOM_VIEWS, CustomViewLocator.serialize(Collections.singletonList(
                        CustomViewLocator.capture(feed.getRootView(), feed, custom)))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(feed);
        try {
            intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
            idleMainLooper();
            hook.setComponentsTemporarilyShown(true);
            idleMainLooper();
            assertEquals(View.VISIBLE, custom.getVisibility());
            holder.aid = "next";
            intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
            idleMainLooper();
            assertEquals(View.VISIBLE, custom.getVisibility());
            assertEquals(0.7f, custom.getAlpha(), 0.001f);
            preferences.edit().putBoolean(ComponentHideSettings.HIDE_ON_SWITCH, true).commit();
            hook.setSettings(ExtendedSettings.readFrom(preferences));
            idleMainLooper();
            holder.aid = "third";
            intercept(hooks.get("DouAi.FeedUi.HideVideoActions"), holder);
            idleMainLooper();
            assertEquals(View.GONE, custom.getVisibility());
        } finally { hook.close(); idleMainLooper(); activity.finish(); }
    }

    @Test
    public void customViewIsHiddenAndRestoredWhenItsPersistedRuleIsDeleted() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        TextView custom = new TextView(activity);
        custom.setText("custom control");
        custom.setClickable(true);
        custom.setFocusable(true);
        root.addView(custom);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), custom);
        assertNotNull(locator);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.GONE, custom.getVisibility());
        assertFalse(custom.isClickable());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.VISIBLE, custom.getVisibility());
        assertTrue(custom.isClickable());
        assertTrue(custom.isFocusable());

        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void windowCustomRuleSurvivesFeedPauseAndDoesNotReplaceFixedComponentRule() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout content = new FrameLayout(activity);
        FrameLayout feed = new FrameLayout(activity);
        View like = new View(activity);
        like.setId(2131374347);
        feed.addView(like);
        content.addView(feed);
        TextView windowControl = new TextView(activity);
        windowControl.setText("window control");
        content.addView(windowControl);
        activity.setContentView(content);

        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), feed, windowControl);
        assertNotNull(locator);
        assertEquals(CustomViewLocator.Scope.WINDOW, locator.scope);

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(Component.LIKE.key, "-2")
                .putString(ComponentHideSettings.CUSTOM_VIEWS,
                        CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        FeedHolderFixture holder = holder(feed);
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder);
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.emptyList())).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, like.getVisibility());
        assertEquals(View.VISIBLE, windowControl.getVisibility());

        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        hook.setSettings(ExtendedSettings.readFrom(preferences));
        idleMainLooper();
        assertEquals(View.GONE, windowControl.getVisibility());

        intercept(hooks.get("DouAi.FeedUi.VideoPause"), holder);
        idleMainLooper();
        assertEquals(View.VISIBLE, like.getVisibility());
        assertEquals(View.GONE, windowControl.getVisibility());
        hook.close();
        idleMainLooper();
        assertEquals(View.VISIBLE, windowControl.getVisibility());
        activity.finish();
    }

    @Test
    public void customRuleNeverHidesFinderOverlayOrItsChildren() throws Throwable {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        FrameLayout overlay = new FrameLayout(activity);
        View overlayButton = new View(activity);
        overlay.addView(overlayButton);
        root.addView(overlay);
        activity.setContentView(root);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), root, overlayButton);
        assertNotNull(locator);
        overlay.setTag("douai.view_finder");

        SharedPreferences preferences = componentPreferences();
        preferences.edit().putString(ComponentHideSettings.CUSTOM_VIEWS,
                CustomViewLocator.serialize(Collections.singletonList(locator))).commit();
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = install(hooks, ExtendedSettings.readFrom(preferences));
        intercept(hooks.get("DouAi.FeedUi.VideoResume"), holder(root));
        idleMainLooper();
        assertEquals(View.VISIBLE, overlay.getVisibility());
        assertEquals(View.VISIBLE, overlayButton.getVisibility());
        hook.close();
        idleMainLooper();
        activity.finish();
    }

    @Test
    public void diagnosticsKeepVideoAndImageInstallationSeparate() throws Exception {
        List<RuleScanResult> results = new java.util.ArrayList<>();
        for (RuleScanResult result : createReport().getRuleResults()) {
            if (!result.getRuleId().startsWith("image_holder")) results.add(result);
        }
        Map<String, XposedInterface.Hooker> hooks = new HashMap<>();
        FeedUiHideHook hook = FeedUiHideHook.install(createFramework(hooks),
                new HostScanReport("partial-host", results, false), getClass().getClassLoader(),
                enabledSettings(), ignored -> { });
        assertTrue(hook.isVideoLifecycleInstalled());
        assertFalse(hook.isImageLifecycleInstalled());
        hook.close();
        assertFalse(hook.isVideoLifecycleInstalled());
        assertFalse(hook.isImageLifecycleInstalled());
        idleMainLooper();
    }

    private static SharedPreferences componentPreferences() {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().clear().putBoolean(ComponentHideSettings.ENABLED, true).commit();
        return preferences;
    }

    private static FeedHolderFixture holder(FrameLayout root) {
        FeedHolderFixture holder = new FeedHolderFixture();
        holder.w = root;
        holder.f = root;
        return holder;
    }

    private FeedUiHideHook install(Map<String, XposedInterface.Hooker> hooks,
                                   ExtendedSettings settings) throws Exception {
        return FeedUiHideHook.install(createFramework(hooks), createReport(),
                getClass().getClassLoader(), settings, ignored -> { });
    }

    private static ExtendedSettings enabledSettings() {
        return settings(true, 100);
    }

    private static ExtendedSettings settings(boolean hideInterface, int actionAlpha) {
        SharedPreferences preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("feed_ui_test", 0);
        preferences.edit().putBoolean("hide_interface", hideInterface)
                .remove(ComponentHideSettings.ENABLED)
                .putString("video_right_alpha", Integer.toString(actionAlpha)).commit();
        return ExtendedSettings.readFrom(preferences);
    }

    private static void idleMainLooper() throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    private static void idleForMillis(long millis) throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idleFor", long.class, java.util.concurrent.TimeUnit.class)
                .invoke(shadow, millis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private static void intercept(XposedInterface.Hooker hook, Object holder, Object... arguments) throws Throwable {
        assertNotNull(hook);
        XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(
                XposedInterface.Chain.class.getClassLoader(), new Class<?>[]{XposedInterface.Chain.class},
                (proxy, method, args) -> {
                    if ("proceed".equals(method.getName())) return null;
                    if ("getThisObject".equals(method.getName())) return holder;
                    if ("getArg".equals(method.getName())) return arguments[(Integer) args[0]];
                    throw new UnsupportedOperationException(method.getName());
                });
        hook.intercept(chain);
    }

    private static HostScanReport createReport() throws Exception {
        String owner = descriptor(FeedHolderFixture.class);
        List<RuleScanResult> results = Arrays.asList(
                methodResult("feed_holder_page_selected", owner, "onVideoPageSelected"),
                methodResult("feed_holder_resume", owner, "onVideoResume"),
                methodResult("feed_holder_pause", owner, "onVideoPause"),
                classResult("feed_holder_main_root", owner),
                methodResult("image_holder_page_selected", owner, "onImagePageSelected"),
                methodResult("image_holder_resume", owner, "onImageResume"),
                methodResult("image_holder_pause", owner, "onImagePause"),
                classResult("image_holder_main_root", owner),
                new RuleScanResult("component_player_pause", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPause", "(Ljava/lang/String;)V"))),
                new RuleScanResult("component_player_playing", "method_scan", Collections.singletonList(
                        new HostCandidate("test.apk", "classes.dex", descriptor(PlayerFixture.class),
                                "onPlaying", "(Ljava/lang/String;)V"))));
        return new HostScanReport("test-host", results, false);
    }

    private static RuleScanResult methodResult(String id, String owner, String method) throws Exception {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, method, "()V")));
    }

    private static RuleScanResult classResult(String id, String owner) {
        return new RuleScanResult(id, "method_scan", Collections.singletonList(
                new HostCandidate("test.apk", "classes.dex", owner, "", "")));
    }

    private static String descriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static XposedInterface createFramework(Map<String, XposedInterface.Hooker> hooks) {
        return (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (frameworkProxy, frameworkMethod, frameworkArgs) -> {
                    if (!"hook".equals(frameworkMethod.getName())) return null;
                    String[] id = {""};
                    XposedInterface.HookBuilder[] builder = new XposedInterface.HookBuilder[1];
                    builder[0] = (XposedInterface.HookBuilder) Proxy.newProxyInstance(
                            XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builderProxy, builderMethod, builderArgs) -> {
                                if ("setId".equals(builderMethod.getName())) {
                                    id[0] = (String) builderArgs[0];
                                    return builder[0];
                                }
                                if ("intercept".equals(builderMethod.getName())) {
                                    hooks.put(id[0], (XposedInterface.Hooker) builderArgs[0]);
                                    return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                            new Class<?>[]{XposedInterface.HookHandle.class},
                                            (handleProxy, handleMethod, handleArgs) -> null);
                                }
                                return builder[0];
                            });
                    return builder[0];
                });
    }
}
