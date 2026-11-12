package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.view.View;
import android.widget.CheckBox;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.compose.ui.test.ActionsKt;
import androidx.compose.ui.test.AssertionsKt;
import androidx.compose.ui.test.FiltersKt;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule;
import androidx.compose.ui.test.junit4.AndroidComposeTestRule_androidKt;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.analytics.LocalPortraitContext;
import com.douai.module.config.ConfigBackup;
import org.junit.After;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.robolectric.shadows.ShadowLooper;
import kotlin.Unit;
import kotlin.coroutines.EmptyCoroutineContext;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 28, qualifiers = "w296dp-h560dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ConfigTransferMiuixTest {
    @Rule public final AndroidComposeTestRule<ActivityScenarioRule<ComponentActivity>, ComponentActivity> compose =
            AndroidComposeTestRule_androidKt.createAndroidComposeRule(ComponentActivity.class, EmptyCoroutineContext.INSTANCE);
    private ConfigTransferDialog page;
    private boolean selected;

    @After public void close() {
        compose.runOnUiThread(() -> { if (page != null) { page.close(); page.dialog().dismiss(); } return Unit.INSTANCE; });
    }
    @Test public void narrowBackupPageCanScrollToItsExportActionAndClose() { verify(); }
    @Test @Config(sdk = 28, qualifiers = "w640dp-h360dp-land-mdpi")
    public void landscapeBackupPageCanScrollToItsExportActionAndClose() { verify(); }
    @Test @Config(sdk = 28, qualifiers = "w296dp-h560dp-night-mdpi")
    public void darkNarrowBackupPageCanScrollToItsExportActionAndClose() { verify(true); }

    @Test public void bundledAnalysisUsesAnIndependentMiuixRestoreSwitchThatDefaultsOff() throws Exception {
        ComponentActivity hostActivity = compose.getActivity();
        LocalAnalytics analytics = LocalAnalytics.get(hostActivity);
        ConfigTransferDialogTest.MemoryStore store = new ConfigTransferDialogTest.MemoryStore();
        ConfigTransferDialogTest.MemoryStore source = new ConfigTransferDialogTest.MemoryStore();
        source.values.put("native_auto_reply", true);
        Queue<Runnable> queued = new ArrayDeque<>();
        AtomicReference<Consumer<android.net.Uri>> selectedFile = new AtomicReference<>();
        try {
            String bundle = runOffMain(() -> {
                analytics.setEnabled(false); analytics.clear(); LocalPortraitContext.clear(hostActivity);
                analytics.setEnabled(true);
                analytics.recordView("backup-miuix-synthetic", "image", "synthetic", "测试作者",
                        List.of("测试话题"), false, 20_000);
                LocalPortraitContext.setPurpose(hostActivity, "测试备份自述");
                return ConfigBackup.export(hostActivity, source, false, true);
            });
            compose.runOnUiThread(() -> {
                page = new ConfigTransferDialog(hostActivity, store, true, () -> { }, new ConfigTransferDialog.Files() {
                    public java.io.InputStream read(android.net.Uri uri) {
                        return new java.io.ByteArrayInputStream(bundle.getBytes(StandardCharsets.UTF_8));
                    }
                    public java.io.OutputStream write(android.net.Uri uri) { throw new AssertionError(); }
                }, (activity, export, name, callback) -> { selectedFile.set(callback); return () -> { }; }, queued::add);
                page.dialog().show(); return Unit.INSTANCE;
            });
            compose.waitForIdle();
            scrollTo("config.backup.import");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.import"), false));
            compose.runOnUiThread(() -> { selectedFile.get().accept(android.net.Uri.parse("content://backup/synthetic.json")); return Unit.INSTANCE; });
            runOffMain(() -> { while (!queued.isEmpty()) queued.remove().run(); return null; });
            ShadowLooper.idleMainLooper(); compose.waitForIdle();
            compose.runOnUiThread(() -> {
                View decor = page.dialog().getWindow().getDecorView();
                assertEquals(View.VISIBLE, decor.findViewWithTag("config.backup.restoreAnalytics").getVisibility());
                String preview = ((TextView) decor.findViewWithTag("config.backup.preview")).getText().toString();
                assertTrue(preview.contains("本备份包含本地分析数据"));
                assertTrue(preview.contains("默认不恢复本地分析数据"));
                return Unit.INSTANCE;
            });
            scrollTo("config.backup.restoreAnalytics");
            AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            AssertionsKt.assertIsOff(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            AssertionsKt.assertIsOn(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            compose.runOnUiThread(() -> {
                String preview = ((TextView) page.dialog().getWindow().getDecorView()
                        .findViewWithTag("config.backup.preview")).getText().toString();
                assertTrue(preview.contains("将替换当前全部本地分析记录"));
                assertTrue(preview.contains("不改变当前采集开关"));
                return Unit.INSTANCE;
            });
            // The expanded warning changes the native scroll position; return to the actual switch.
            scrollTo("config.backup.restoreAnalytics");
            ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            AssertionsKt.assertIsOff(compose.onNode(FiltersKt.hasTestTag("config.backup.restoreAnalytics"), false));
            assertEquals(0, store.applies);
        } finally {
            compose.runOnUiThread(() -> { if (page != null) { page.close(); page.dialog().dismiss(); } return Unit.INSTANCE; });
            runOffMain(() -> { analytics.setEnabled(false); analytics.clear(); LocalPortraitContext.clear(hostActivity); return null; });
        }
    }

    private void verify() { verify(false); }

    private void verify(boolean night) {
        compose.runOnUiThread(() -> {
            page = new ConfigTransferDialog(compose.getActivity(), new ConfigTransferDialogTest.MemoryStore(), true,
                    () -> { }, new ConfigTransferDialog.Files() {
                        public java.io.InputStream read(android.net.Uri uri) { throw new AssertionError(); }
                        public java.io.OutputStream write(android.net.Uri uri) { throw new AssertionError(); }
                    }, (activity, export, name, callback) -> { selected = export; return () -> { }; }, Runnable::run);
            page.dialog().show();
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            assertEquals(night, ModuleTheme.isDark(decor.getContext()));
            TextView status = decor.findViewWithTag("config.backup.status");
            assertEquals("Backup text must remain legible in the system theme", night,
                    Color.luminance(status.getCurrentTextColor()) > 0.35f);
            CheckBox checkbox = decor.findViewWithTag("config.backup.secrets");
            assertFalse(checkbox.isChecked());
            CheckBox includeAnalytics = decor.findViewWithTag("config.backup.includeAnalytics");
            assertFalse("Analysis data requires an independent explicit export choice", includeAnalytics.isChecked());
            assertEquals(View.GONE, decor.findViewWithTag("config.backup.restoreAnalytics").getVisibility());
            ScrollView scroll = decor.findViewWithTag("config.backup.scroll");
            View analyticsControl = MiuixFormControls.viewFor(includeAnalytics);
            scroll.scrollTo(0, analyticsControl.getTop());
            assertTrue(scroll.getWidth() <= decor.getWidth());
            assertTrue(scroll.getHeight() > 0);
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        AssertionsKt.assertIsOff(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        AssertionsKt.assertIsOn(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        AssertionsKt.assertIsOff(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.includeAnalytics"), false));
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            assertTrue(((CheckBox) decor.findViewWithTag("config.backup.includeAnalytics")).isChecked());
            ScrollView scroll = decor.findViewWithTag("config.backup.scroll");
            View export = MiuixFormControls.viewFor(decor.findViewWithTag("config.backup.export"));
            scroll.scrollTo(0, export.getTop());
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        AssertionsKt.assertIsDisplayed(compose.onNode(FiltersKt.hasTestTag("config.backup.export"), false));
        ActionsKt.performClick(compose.onNode(FiltersKt.hasTestTag("config.backup.export"), false));
        assertTrue(selected);
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
            if (location != null && decor.getWidth() > 0 && decor.getHeight() > 0) {
                Bitmap bitmap = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
                try {
                    decor.draw(new Canvas(bitmap));
                    File folder = new File(location);
                    assertTrue(folder.isDirectory() || folder.mkdirs());
                    try (FileOutputStream out = new FileOutputStream(new File(folder,
                            "config-backup-" + (night ? "night-" : "light-")
                                    + decor.getWidth() + "x" + decor.getHeight() + ".png"))) {
                        assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
                    } catch (Exception failure) { throw new AssertionError(failure); }
                } finally { bitmap.recycle(); }
            }
            page.dialog().getButton(android.app.AlertDialog.BUTTON_NEGATIVE).performClick();
            return Unit.INSTANCE;
        });
        compose.waitForIdle();
        assertFalse(page.dialog().isShowing());
    }

    private void scrollTo(String tag) {
        compose.runOnUiThread(() -> {
            View decor = page.dialog().getWindow().getDecorView();
            ScrollView scroll = decor.findViewWithTag("config.backup.scroll");
            View control = MiuixFormControls.viewFor(decor.findViewWithTag(tag));
            scroll.scrollTo(0, control.getTop()); return Unit.INSTANCE;
        });
        compose.waitForIdle();
    }

    private static <T> T runOffMain(Callable<T> task) throws Exception {
        FutureTask<T> completed = new FutureTask<>(task);
        new Thread(completed, "config-backup-miuix-test").start();
        return completed.get(10, TimeUnit.SECONDS);
    }
}
