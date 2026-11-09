package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.SharedPreferences;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import java.lang.reflect.Proxy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class HostUiControllerTest {
    @Test public void sidebarShortcutsReuseOneOwnedSettingsScreenAndCloseItsChildrenOnPause() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        try {
            controller.onActivityResumed(activity);
            controller.showSidebarEntry(activity, "module");
            idleMainLooper();
            Dialog settings = ShadowDialog.getLatestDialog();
            assertTrue(settings.isShowing());
            controller.showSidebarEntry(activity, "comment_history");
            idleMainLooper();
            Dialog history = ShadowDialog.getLatestDialog();
            assertNotSame(settings, history);
            assertTrue(history.isShowing());
            assertTrue(settings.isShowing());
            controller.showSidebarEntry(activity, "module");
            idleMainLooper();
            assertFalse(history.isShowing());
            assertTrue(settings.isShowing());
            controller.onActivityPaused(activity);
            assertFalse(settings.isShowing());
            controller.showSidebarEntry(activity, "reply_history");
            idleMainLooper();
            assertFalse(ShadowDialog.getLatestDialog().isShowing());
        } finally { controller.close(); activity.finish(); }
    }

    private static final String ENTRY_TAG = "DouAi.HostSettingsEntry";

    @Before public void resetAcknowledgment() {
        RuntimeEnvironment.getApplication().getSharedPreferences(
                AdaptationAcknowledgment.PREFERENCES, Activity.MODE_PRIVATE).edit().clear().commit();
    }

    @Test public void initialLaunchAndConfirmedCacheDoNotFlashScanningDialogs() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        acknowledgment.acknowledge("host-module-1");
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertNull(ShadowDialog.getLatestDialog());
            controller.onCacheHit(55);
            idleMainLooper();
            assertNull(ShadowDialog.getLatestDialog());
            controller.complete(true, "rules=159", "host-module-1");
            idleMainLooper();
            assertNull(ShadowDialog.getLatestDialog());
            assertEquals(0, fixture.prepareCount);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void confirmationSchedulesThenPersistsThenRestartsExactlyOnce() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.onActivityResumed(activity);
            controller.onScanStarted(55);
            controller.complete(false, "rules=159", "host-module-1");
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            assertTrue(scan.isShowing());
            assertEquals(1, countText(scan.getWindow().getDecorView(), "确定"));
            assertEquals(0, countText(scan.getWindow().getDecorView(), "知道了"));
            View confirm = findText(scan.getWindow().getDecorView(), "确定");
            assertFalse(acknowledgment.isAcknowledged("host-module-1"));
            confirm.performClick();
            confirm.performClick();
            assertEquals(1, fixture.prepareCount);
            assertEquals(1, fixture.terminateCount);
            assertEquals(0, fixture.cancelCount);
            assertTrue(acknowledgment.isAcknowledged("host-module-1"));
            assertFalse(scan.isShowing());
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void dismissingCachedCompletionPersistsConfirmationWithoutRestart() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(true, "rules=159; cacheHit=true", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            assertTrue(scan.isShowing());
            scan.cancel();
            idleMainLooper();
            assertEquals(0, fixture.prepareCount);
            assertTrue(acknowledgment.isAcknowledged("host-module-1"));
            controller.close();
            controller = controller(fixture, acknowledgment);
            controller.complete(true, "rules=159; cacheHit=true", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertFalse(ShadowDialog.getLatestDialog().isShowing());
            assertEquals(0, fixture.terminateCount);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void newHostOrModuleIdentityRequiresNewConfirmation() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        acknowledgment.acknowledge("host-module-1");
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(true, "rules=159", "host-module-2");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            assertTrue(scan.isShowing());
            assertEquals(1, countText(scan.getWindow().getDecorView(), "确定"));
            assertEquals(0, fixture.prepareCount);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void restartSchedulingFailureKeepsConfirmationAndDoesNotPromptOnNextLaunch() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        fixture.scheduleFailure = true;
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(false, "rules=159", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            View confirm = findText(scan.getWindow().getDecorView(), "确定");
            confirm.performClick();
            assertFalse(scan.isShowing());
            assertTrue(acknowledgment.isAcknowledged("host-module-1"));
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("手动重启"));
            assertEquals(0, fixture.terminateCount);
            controller.close();
            controller = controller(fixture, acknowledgment);
            controller.complete(true, "rules=159; cacheHit=true", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertFalse(ShadowDialog.getLatestDialog().isShowing());
            assertEquals(1, fixture.prepareCount);
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void activitySwitchNeverConfirmsOrRestartsFromStaleDialog() throws Exception {
        Activity first = Robolectric.buildActivity(Activity.class).setup().get();
        Activity second = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(false, "rules=159", "host-module-1");
            controller.onActivityResumed(first);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            View staleConfirm = findText(scan.getWindow().getDecorView(), "确定");
            controller.onActivityPaused(first);
            controller.onActivityResumed(second);
            idleMainLooper();
            assertFalse(scan.isShowing());
            assertSame(second, ShadowDialog.getLatestDialog().getOwnerActivity());
            staleConfirm.performClick();
            assertEquals(0, fixture.prepareCount);
            assertFalse(acknowledgment.isAcknowledged("host-module-1"));
        } finally {
            controller.close();
            first.finish();
            second.finish();
        }
    }

    @Test public void relayFailureWhilePausedKeepsConfirmationAndShowsManualNoticeOnResume() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(true, "rules=182; cacheHit=true", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            findText(scan.getWindow().getDecorView(), "确定").performClick();
            controller.onActivityPaused(activity);
            fixture.failureListener.accept("relay unavailable");
            assertTrue(acknowledgment.isAcknowledged("host-module-1"));
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertFalse(scan.isShowing());
            assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("手动重启"));
            controller.close();
            controller = controller(fixture, acknowledgment);
            controller.complete(true, "rules=182; cacheHit=true", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertFalse(ShadowDialog.getLatestDialog().isShowing());
            assertEquals(1, fixture.prepareCount);
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void failedConfirmationWriteKeepsPromptAndNeverSchedulesRestart() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        SharedPreferences delegate = RuntimeEnvironment.getApplication().getSharedPreferences(
                AdaptationAcknowledgment.PREFERENCES, Activity.MODE_PRIVATE);
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if (!"edit".equals(method.getName())) return method.invoke(delegate, arguments);
                    SharedPreferences.Editor editor = delegate.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(),
                            new Class<?>[]{SharedPreferences.Editor.class}, (editorProxy, call, values) -> {
                                Object result = call.invoke(editor, values);
                                if ("commit".equals(call.getName())) return false;
                                return result instanceof SharedPreferences.Editor ? editorProxy : result;
                            });
                });
        AdaptationAcknowledgment acknowledgment = new AdaptationAcknowledgment(failing);
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.complete(true, "rules=182", "host-module-1");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            findText(scan.getWindow().getDecorView(), "确定").performClick();
            assertTrue(scan.isShowing());
            assertFalse(acknowledgment.isAcknowledged("host-module-1"));
            assertEquals(0, fixture.prepareCount);
            scan.cancel();
            idleMainLooper();
            assertTrue(scan.isShowing());
            assertFalse(acknowledgment.isAcknowledged("host-module-1"));
        } finally { controller.close(); activity.finish(); }
    }

    @Test public void failedScanCloseDoesNotScheduleRestartOrAcknowledge() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        AdaptationAcknowledgment acknowledgment = acknowledgment();
        RestartFixture fixture = new RestartFixture(acknowledgment);
        HostUiController controller = controller(fixture, acknowledgment);
        try {
            controller.fail("rule not found");
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            findText(scan.getWindow().getDecorView(), "关闭").performClick();
            assertFalse(scan.isShowing());
            assertEquals(0, fixture.prepareCount);
            assertFalse(acknowledgment.isAcknowledged("host-module-1"));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void ordinaryActivitiesNeverReceiveAFloatingOrSettingsEntry() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        LinearLayout settingsLikeContainer = setSettingsContent(activity);
        HostUiController controller = controller();
        try {
            controller.onActivityResumed(activity);
            idleMainLooper();
            View root = activity.findViewById(android.R.id.content);
            assertEquals(0, countTagged(root, ENTRY_TAG));
            assertNull(findDescription(root, "DouAi 抖音模块设置"));
            assertEquals(2, settingsLikeContainer.getChildCount());
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void settingsPageReceivesOneNativeEntryAcrossResumeAndLayoutCallbacks() throws Exception {
        SettingsFixtureActivity activity = Robolectric.buildActivity(SettingsFixtureActivity.class)
                .setup().get();
        LinearLayout container = setSettingsContent(activity);
        HostUiController controller = controller();
        try {
            controller.onActivityResumed(activity);
            idleMainLooper();
            View root = activity.findViewById(android.R.id.content);
            assertEquals(1, countTagged(root, ENTRY_TAG));
            assertEquals(ENTRY_TAG, container.getChildAt(1).getTag());
            assertNull(findDescription(root, "DouAi 抖音模块设置"));
            for (int i = 0; i < 3; i++) {
                root.getViewTreeObserver().dispatchOnGlobalLayout();
                idleMainLooper();
                controller.onActivityResumed(activity);
            }
            assertEquals(1, countTagged(root, ENTRY_TAG));
            assertEquals(3, container.getChildCount());
            container.getChildAt(1).performClick();
            Dialog settings = ShadowDialog.getLatestDialog();
            assertTrue(settings.isShowing());
            assertSame(activity, settings.getOwnerActivity());
            assertNotNull(settings.getWindow().getDecorView().findViewWithTag("douai.miuix.home"));
            assertNotEquals(WindowManager.LayoutParams.MATCH_PARENT, settings.getWindow().getAttributes().width);
            assertNotEquals(WindowManager.LayoutParams.MATCH_PARENT, settings.getWindow().getAttributes().height);
            assertTrue(settings.getWindow().getAttributes().width > 0);
            assertTrue(settings.getWindow().getAttributes().height > 0);
            controller.close();
            assertFalse(settings.isShowing());
            assertEquals(0, countTagged(root, ENTRY_TAG));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test
    public void delayedContentAndRebuiltSettingsListsAreReinjectedWithoutDuplicates() throws Exception {
        SettingsFixtureActivity activity = Robolectric.buildActivity(SettingsFixtureActivity.class)
                .setup().get();
        activity.setContentView(new FrameLayout(activity));
        HostUiController controller = controller();
        try {
            controller.onActivityResumed(activity);
            idleMainLooper();
            View root = activity.findViewById(android.R.id.content);
            assertEquals(0, countTagged(root, ENTRY_TAG));
            LinearLayout first = setSettingsContent(activity);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            idleMainLooper();
            assertEquals(1, countTagged(root, ENTRY_TAG));
            assertEquals(ENTRY_TAG, first.getChildAt(1).getTag());
            LinearLayout replacement = setSettingsContent(activity);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            idleMainLooper();
            assertEquals(1, countTagged(root, ENTRY_TAG));
            assertEquals(ENTRY_TAG, replacement.getChildAt(1).getTag());

            controller.onActivityPaused(activity);
            LinearLayout pausedReplacement = setSettingsContent(activity);
            root.getViewTreeObserver().dispatchOnGlobalLayout();
            idleMainLooper();
            assertEquals(0, countTagged(root, ENTRY_TAG));
            assertEquals(2, pausedReplacement.getChildCount());
            controller.onActivityResumed(activity);
            idleMainLooper();
            assertEquals(1, countTagged(root, ENTRY_TAG));
        } finally {
            controller.close();
            activity.finish();
        }
    }

    @Test public void hostSettingsFactoryForwardsAnalyticsDiagnosticsToTheHostLogger() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        java.util.List<String> logs = new java.util.ArrayList<>();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), logs::add);
        ModuleSettingsScreen screen = controller.createSettingsScreen(activity, () -> { });
        try {
            View settings = screen.createView(); activity.setContentView(settings);
            settings.findViewWithTag("settings.category.EXTENSION").performClick();
            Dialog extension = ShadowDialog.getLatestDialog();
            extension.getWindow().getDecorView().findViewWithTag("settings.option.本地数据分析").performClick();
            idleMainLooper();
            Dialog analytics = ShadowDialog.getLatestDialog();
            assertTrue(analytics.isShowing());
            assertNotNull(analytics.getWindow().getDecorView().findViewWithTag("analytics.root"));
            assertTrue("The same logger used by the LSPosed host entry must receive analysis diagnostics",
                    logs.contains("[analytics] stage=ui_open kind=NONE status=0 chars=0"));
            for (String value : logs) if (value.startsWith("[analytics]")) {
                assertFalse(value.contains("http://")); assertFalse(value.contains("https://"));
                assertFalse(value.contains("apiKey")); assertFalse(value.contains("request-body"));
            }
            screen.close(); assertFalse(analytics.isShowing());
        } finally {
            screen.close(); controller.close(); activity.finish(); idleMainLooper();
        }
    }

    @Test
    public void firstScanDialogStillAppearsWithoutAnySettingsOverlay() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.setContentView(new FrameLayout(activity));
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(),
                ignored -> { });
        try {
            controller.beginScan();
            controller.onScanStarted(55);
            controller.onActivityResumed(activity);
            idleMainLooper();
            Dialog scan = ShadowDialog.getLatestDialog();
            assertNotNull(scan);
            assertTrue(scan.isShowing());
            assertSame(activity, scan.getOwnerActivity());
            assertEquals(1, countText(scan.getWindow().getDecorView(), "正在查找方法"));
            assertEquals(1, countText(scan.getWindow().getDecorView(), "0 / 55 个 DEX"));
            assertNull(findDescription(activity.findViewById(android.R.id.content),
                    "DouAi 抖音模块设置"));
            controller.onActivityPaused(activity);
            assertFalse(scan.isShowing());
        } finally {
            controller.close();
            activity.finish();
        }
    }

    private static HostUiController controller() throws Exception {
        HostUiController result = new HostUiController(RuntimeEnvironment.getApplication(),
                ignored -> { });
        result.complete(true, "rules=61");
        result.setSettingsPageTargets(SettingsFixtureActivity.class, ScrollView.class);
        idleMainLooper();
        return result;
    }

    private static HostUiController controller(RestartFixture fixture,
                                               AdaptationAcknowledgment acknowledgment) {
        return new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { },
                new HostAppRestarter(fixture), acknowledgment);
    }

    private static AdaptationAcknowledgment acknowledgment() {
        return new AdaptationAcknowledgment(RuntimeEnvironment.getApplication());
    }

    private static final class RestartFixture implements HostAppRestarter.Scheduler {
        final AdaptationAcknowledgment acknowledgment;
        int prepareCount;
        int terminateCount;
        int cancelCount;
        boolean scheduleFailure;
        java.util.function.Consumer<String> failureListener;

        RestartFixture(AdaptationAcknowledgment acknowledgment) {
            this.acknowledgment = acknowledgment;
        }

        @Override public HostAppRestarter.PreparedRestart prepare(Activity activity) {
            prepareCount++;
            if (scheduleFailure) throw new IllegalStateException("test scheduler unavailable");
            assertTrue(acknowledgment.isAcknowledged("host-module-1"));
            return new HostAppRestarter.PreparedRestart() {
                @Override public void setFailureListener(java.util.function.Consumer<String> listener) {
                    failureListener = listener;
                }
                @Override public void terminate() {
                    assertTrue(acknowledgment.isAcknowledged("host-module-1"));
                    terminateCount++;
                }

                @Override public void cancel() { cancelCount++; }
            };
        }
    }

    private static LinearLayout setSettingsContent(Activity activity) {
        ScrollView scroll = new ScrollView(activity);
        LinearLayout container = new LinearLayout(activity);
        container.setOrientation(LinearLayout.VERTICAL);
        container.addView(new View(activity));
        container.addView(new View(activity));
        scroll.addView(container);
        activity.setContentView(scroll);
        return container;
    }

    private static int countTagged(View root, String tag) {
        int count = tag.equals(root.getTag()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countTagged(group.getChildAt(i), tag);
        }
        return count;
    }

    private static int countText(View root, String text) {
        int count = root instanceof TextView && text.contentEquals(((TextView) root).getText()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), text);
        }
        return count;
    }

    private static View findText(View root, String text) {
        if (root instanceof TextView && text.contentEquals(((TextView) root).getText())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findText(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findTextContaining(View root, String text) {
        if (root instanceof TextView && ((TextView) root).getText().toString().contains(text)) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findTextContaining(group.getChildAt(i), text);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findDescription(group.getChildAt(i), description);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void idleMainLooper() throws ReflectiveOperationException {
        Object shadow = Class.forName("org.robolectric.Shadows")
                .getMethod("shadowOf", Looper.class).invoke(null, Looper.getMainLooper());
        shadow.getClass().getMethod("idle").invoke(shadow);
    }

    public static final class SettingsFixtureActivity extends Activity { }
}
