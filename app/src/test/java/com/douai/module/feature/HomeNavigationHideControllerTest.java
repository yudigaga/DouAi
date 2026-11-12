package com.douai.module.feature;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class HomeNavigationHideControllerTest {
    @Test public void stableTreeUsesOneWalkPerFrameAndInactiveFramesDoNoRepeatedWork() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = app.getSharedPreferences("navigation-workload", 0);
        prefs.edit().clear().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true)
                .putString("component_alpha_bottom_nav", "128").commit();
        com.ss.android.ugc.aweme.main.MainActivity activity = Robolectric
                .buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).setup().get();
        java.util.concurrent.atomic.AtomicInteger visits = new java.util.concurrent.atomic.AtomicInteger();
        CountingGroup root = new CountingGroup(activity, visits);
        for (int i = 0; i < 20; i++) {
            CountingGroup branch = new CountingGroup(activity, visits);
            for (int j = 0; j < 20; j++) branch.addView(new View(activity));
            root.addView(branch);
        }
        RenamedBottomBar bottom = new RenamedBottomBar(activity);
        root.addView(bottom);
        root.addView(new com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar(activity));
        activity.setContentView(root);
        HomeNavigationHideController controller = HomeNavigationHideController.install(app,
                com.douai.module.config.ExtendedSettings.readFrom(prefs),
                com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar.class,
                RenamedBottomBar.class, null);
        try {
            controller.onActivityResumed(activity);
            visits.set(0);
            for (int i = 0; i < 300; i++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Each edge is inspected once per frame for both targets", 126600, visits.get());
            controller.setTemporarilyShown(true);
            assertEquals(1f, bottom.getAlpha(), 0.001f);
            long before = suppressedEvents();
            visits.set(0);
            for (int i = 0; i < 300; i++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(0, visits.get());
            assertEquals("Inactive frames must not submit repeated diagnostics", before, suppressedEvents());
            controller.setTemporarilyShown(false);
            assertEquals(128f / 255f, bottom.getAlpha(), 0.001f);
            prefs.edit().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, false).commit();
            controller.setSettings(com.douai.module.config.ExtendedSettings.readFrom(prefs));
            assertEquals(1f, bottom.getAlpha(), 0.001f);
            before = suppressedEvents();
            for (int i = 0; i < 300; i++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(before, suppressedEvents());
            prefs.edit().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true).commit();
            controller.setSettings(com.douai.module.config.ExtendedSettings.readFrom(prefs));
            assertEquals(128f / 255f, bottom.getAlpha(), 0.001f);
            RenamedBottomBar duplicate = new RenamedBottomBar(activity);
            root.addView(duplicate); // No layout dispatch: pre-draw must still notice ambiguity.
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, bottom.getAlpha(), 0.001f);
            assertEquals(1f, duplicate.getAlpha(), 0.001f);
            root.removeView(bottom);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(128f / 255f, duplicate.getAlpha(), 0.001f);
            controller.onActivityPaused(activity);
            assertEquals(1f, duplicate.getAlpha(), 0.001f);
            controller.onActivityResumed(activity);
            assertEquals(128f / 255f, duplicate.getAlpha(), 0.001f);
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void missingTargetsDoNotSubmitSameDecisionFor300Frames() throws Exception {
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = app.getSharedPreferences("navigation-missing-workload", 0);
        prefs.edit().clear().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true).commit();
        com.ss.android.ugc.aweme.main.MainActivity activity = Robolectric
                .buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        activity.setContentView(root);
        HomeNavigationHideController controller = HomeNavigationHideController.install(app,
                com.douai.module.config.ExtendedSettings.readFrom(prefs),
                com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar.class,
                RenamedBottomBar.class, null);
        try {
            controller.onActivityResumed(activity);
            long before = suppressedEvents();
            for (int i = 0; i < 300; i++) root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(before, suppressedEvents());
        } finally { controller.close(); activity.finish(); }
    }

    private static long suppressedEvents() throws Exception {
        java.lang.reflect.Field field = com.douai.module.diagnostics.DouAiLog.class.getDeclaredField("suppressed");
        field.setAccessible(true);
        return field.getLong(null);
    }

    private static final class CountingGroup extends FrameLayout {
        final java.util.concurrent.atomic.AtomicInteger visits;
        CountingGroup(android.content.Context context, java.util.concurrent.atomic.AtomicInteger visits) {
            super(context); this.visits = visits;
        }
        @Override public View getChildAt(int index) { visits.incrementAndGet(); return super.getChildAt(index); }
    }

    public static final class RenamedBottomBar extends FrameLayout {
        public RenamedBottomBar(android.content.Context context) { super(context); }
    }

    @Test public void scannedBottomBarSurvivesAnObfuscatedClassRenameAndRestoresOnClose() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = app.getSharedPreferences("navigation-rename", 0);
        prefs.edit().clear().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true)
                .putString("component_alpha_bottom_nav", "-2").commit();
        com.ss.android.ugc.aweme.main.MainActivity activity = Robolectric
                .buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        RenamedBottomBar bottom = new RenamedBottomBar(activity);
        root.addView(bottom);
        activity.setContentView(root);
        HomeNavigationHideController controller = HomeNavigationHideController.install(app,
                com.douai.module.config.ExtendedSettings.readFrom(prefs),
                com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar.class,
                RenamedBottomBar.class, null);
        try {
            controller.onActivityResumed(activity);
            assertEquals(View.GONE, bottom.getVisibility());
        } finally { controller.close(); }
        assertEquals(View.VISIBLE, bottom.getVisibility());
    }
    @Test
    public void uniqueNavigationTargetRefusesAnAmbiguousLayout() {
        FrameLayout root = new FrameLayout(RuntimeEnvironment.getApplication());
        TextView first = new TextView(root.getContext());
        root.addView(first);
        assertSame(first, HomeNavigationHideController.uniqueTarget(root, TextView.class.getName()));
        root.addView(new TextView(root.getContext()));
        assertNull(HomeNavigationHideController.uniqueTarget(root, TextView.class.getName()));
        assertNull(HomeNavigationHideController.uniqueTarget(root, "missing.Navigation"));
    }

    @Test
    public void goneInvisibleAndAlphaRestoreOriginalInteractionAndVisibility() {
        View view = new View(RuntimeEnvironment.getApplication());
        view.setAlpha(0.8f);
        view.setClickable(true);
        view.setFocusable(false);
        HomeNavigationHideController.Original original = new HomeNavigationHideController.Original(view);
        original.apply(view, -2);
        assertEquals(View.GONE, view.getVisibility());
        assertFalse(view.isClickable());
        original.apply(view, -1);
        assertEquals(View.INVISIBLE, view.getVisibility());
        original.apply(view, 70);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertEquals(0.8f * 70 / 255f, view.getAlpha(), 0.001f);
        assertTrue(view.isClickable());
        original.apply(view, 0);
        assertEquals(View.VISIBLE, view.getVisibility());
        assertTrue(view.isClickable());
        original.restore(view);
        assertEquals(0.8f, view.getAlpha(), 0.001f);
        assertFalse(view.isFocusable());
    }

    @Test public void commentHiddenNavigationCanRecoverAfterLockAndResumeThenPause() {
        android.app.Application app = RuntimeEnvironment.getApplication();
        android.content.SharedPreferences prefs = app.getSharedPreferences("navigation-comment-resume", 0);
        prefs.edit().clear().putBoolean(com.douai.module.config.ComponentHideSettings.ENABLED, true)
                .putString("component_alpha_bottom_nav", "128").commit();
        com.ss.android.ugc.aweme.main.MainActivity activity = Robolectric
                .buildActivity(com.ss.android.ugc.aweme.main.MainActivity.class).setup().get();
        FrameLayout root = new FrameLayout(activity);
        RenamedBottomBar bottom = new RenamedBottomBar(activity);
        root.addView(bottom);
        activity.setContentView(root);
        HomeNavigationHideController controller = HomeNavigationHideController.install(app,
                com.douai.module.config.ExtendedSettings.readFrom(prefs),
                com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar.class,
                RenamedBottomBar.class, null);
        try {
            controller.onActivityResumed(activity);
            controller.onActivityPaused(activity);
            // The comment panel owns this temporary state when the screen returns.
            bottom.setVisibility(View.GONE);
            bottom.setAlpha(0f);
            bottom.setClickable(false);
            bottom.setFocusable(false);
            controller.onActivityResumed(activity);
            assertEquals(View.GONE, bottom.getVisibility());
            bottom.setVisibility(View.VISIBLE);
            bottom.setAlpha(1f);
            bottom.setClickable(true);
            bottom.setFocusable(true);
            root.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("The module must accept the host's restored navigation", View.VISIBLE,
                    bottom.getVisibility());
            assertEquals(128f / 255f, bottom.getAlpha(), 0.001f);
            controller.setTemporarilyShown(true);
            assertEquals(View.VISIBLE, bottom.getVisibility());
            assertEquals(1f, bottom.getAlpha(), 0.001f);
            assertTrue(bottom.isClickable());
            assertTrue(bottom.isFocusable());
            controller.setTemporarilyShown(false);
            root.getViewTreeObserver().dispatchOnPreDraw();
            controller.setTemporarilyShown(true);
            assertEquals("Pausing the next video must still restore navigation", View.VISIBLE,
                    bottom.getVisibility());
            assertEquals(1f, bottom.getAlpha(), 0.001f);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void commentFadeInAfterResumeDoesNotStayTransparentOrCompoundModuleOpacity() {
        View bottom = new View(RuntimeEnvironment.getApplication());
        bottom.setAlpha(0f);
        HomeNavigationHideController.Original original = new HomeNavigationHideController.Original(bottom);
        original.apply(bottom, 128);
        bottom.setAlpha(1f);
        original.apply(bottom, 128);
        assertEquals(128f / 255f, bottom.getAlpha(), 0.001f);
        original.apply(bottom, 128);
        assertEquals("Repeated draws must not multiply opacity again", 128f / 255f,
                bottom.getAlpha(), 0.001f);
        original.restore(bottom);
        assertEquals(1f, bottom.getAlpha(), 0.001f);
    }

    @Test public void restoringNavigationPreservesChangesMadeByTheHostDuringComments() {
        View bottom = new View(RuntimeEnvironment.getApplication());
        bottom.setClickable(true);
        bottom.setFocusable(true);
        HomeNavigationHideController.Original original = new HomeNavigationHideController.Original(bottom);
        original.apply(bottom, 128);
        bottom.setVisibility(View.GONE);
        bottom.setAlpha(0.4f);
        bottom.setClickable(false);
        bottom.setFocusable(false);
        original.restore(bottom);
        assertEquals(View.GONE, bottom.getVisibility());
        assertEquals(0.4f, bottom.getAlpha(), 0.001f);
        assertFalse(bottom.isClickable());
        assertFalse(bottom.isFocusable());
    }
}
