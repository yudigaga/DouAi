package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.ConfigBackup;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.analytics.LocalPortraitContext;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.DebugLog;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigTransferDialogTest {
    private Activity activity;
    private MemoryStore store;
    private Files files;
    private Picker picker;
    private ManualExecutor executor;
    private ConfigTransferDialog page;
    private int changes;
    private final List<String> evidence = new java.util.concurrent.CopyOnWriteArrayList<>();

    @Before public void setUp() {
        assertTrue(DouAiLog.awaitIdle(5000));
        DouAiLog.clearPending();
        DouAiLog.setFrameworkSink((priority, line) -> evidence.add(line));
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore(); files = new Files(); picker = new Picker(); executor = new ManualExecutor();
        page = new ConfigTransferDialog(activity, store, false, () -> changes++, files, picker, executor);
        page.dialog().show(); ShadowLooper.idleMainLooper();
    }
    @After public void tearDown() {
        page.close(); page.dialog().dismiss(); activity.finish(); ShadowLooper.idleMainLooper();
        assertTrue(DouAiLog.awaitIdle(5000)); DouAiLog.setFrameworkSink(null);
    }

    @Test public void exportExcludesSecretsUnlessExplicitlySelectedAndRunsOffTheUiCallback() throws Exception {
        store.values.put("native_auto_reply", true);
        store.values.put("third_party_app_secret", "private-export-key");
        assertFalse(((CheckBox) view("secrets")).isChecked());
        view("export").performClick(); picker.select();
        assertEquals(0, files.writes);
        finish();
        assertEquals(1, files.writes);
        assertFalse(files.output.toString("UTF-8").contains("private-export-key"));
        assertEquals(0, store.applies);
        ((CheckBox) view("secrets")).setChecked(true);
        view("export").performClick(); picker.select(); finish();
        assertTrue(files.output.toString("UTF-8").contains("private-export-key"));
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-export-key"));
    }

    @Test public void dialogOwnSmallExportsRoundTripWithDefaultMiniMaxAndEveryBackupOption() throws Exception {
        LocalAnalytics analytics = LocalAnalytics.get(activity);
        try {
            for (boolean includeSecrets : new boolean[]{false, true}) {
                for (boolean includeAnalysis : new boolean[]{false, true}) {
                    for (boolean replaceCurrent : new boolean[]{false, true}) {
                    store.values.clear();
                    store.values.put("native_auto_reply", true);
                    store.values.put(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
                    store.values.put(SpeechSettings.MINIMAX_API_KEY, "fixture-round-trip-key");
                    store.values.put(SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.DEFAULT_MINIMAX_BASE_URL);
                    store.values.put(SpeechSettings.MINIMAX_MODEL, SpeechSettings.DEFAULT_MINIMAX_MODEL);
                    store.values.put(SpeechSettings.MINIMAX_VOICE_ID, SpeechSettings.DEFAULT_MINIMAX_VOICE_ID);
                    store.values.put(SpeechSettings.MINIMAX_SPEED, "1");
                    store.values.put(SpeechSettings.MINIMAX_VOLUME, "1");
                    store.values.put(SpeechSettings.MINIMAX_PITCH, "0");
                    for (String key : MiniMaxSpeechOptions.KEYS) store.values.put(key, MiniMaxSpeechOptions.defaultValue(key));
                    seedAnalytics(analytics, "round-trip-source", 3, "往返备份测试自述");
                    ((CheckBox) view("secrets")).setChecked(includeSecrets);
                    ((CheckBox) view("includeAnalytics")).setChecked(includeAnalysis);
                    ((CheckBox) view("replace")).setChecked(replaceCurrent);
                    view("export").performClick(); picker.select(); finishOffMain();
                    assertTrue(((TextView) view("status")).getText().toString().contains("已导出"));
                    byte[] original = files.output.toByteArray();
                    assertTrue("Fixture must be far below either byte limit", original.length < 64 * 1024);
                    files.input = original;
                    store.values.put("native_auto_reply", false);
                    seedAnalytics(analytics, "round-trip-current", 1, "导入前自述");
                    view("import").performClick(); picker.select(); finishOffMain();
                    assertTrue("Own export must produce a preview: secrets=" + includeSecrets + "; analysis=" + includeAnalysis
                                    + "; replace=" + replaceCurrent,
                            view("confirm").isEnabled());
                    assertEquals(1, snapshotViews(analytics));
                    if (includeAnalysis) ((CheckBox) view("restoreAnalytics")).setChecked(true);
                    view("confirm").performClick(); finishOffMain();
                    assertEquals(true, store.values.get("native_auto_reply"));
                    assertEquals("fixture-round-trip-key", store.values.get(SpeechSettings.MINIMAX_API_KEY));
                    assertEquals(includeAnalysis ? 3 : 1, snapshotViews(analytics));
                    }
                }
            }
        } finally { clearAnalytics(analytics); }
    }

    @Test public void smallInvalidBackupReportsValidationRatherThanAnUnrelatedSizeLimit() {
        files.input = "{\"formatVersion\":1}".getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        String status = ((TextView) view("status")).getText().toString();
        assertTrue(status, status.contains("格式") || status.contains("校验"));
        assertFalse(status, status.contains("2 MiB") || status.contains("32 MiB"));
        assertFalse(view("confirm").isEnabled());
        assertEquals(0, store.applies);
    }

    @Test public void providerReadFailureReportsReadingRatherThanAnUnrelatedSizeLimit() {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try {
            files.fail = true;
            view("import").performClick(); picker.select(); finish();
            String status = ((TextView) view("status")).getText().toString();
            assertTrue(status, status.contains("无法读取") || status.contains("读取失败"));
            assertFalse(status, status.contains("2 MiB") || status.contains("32 MiB"));
            assertFalse(status.contains("private-provider-key"));
            assertReadFailureEvidence("java.lang.IllegalStateException", "private-provider-key");
            assertEquals(0, store.applies);
        } finally { DouAiLog.awaitIdle(5000); DebugLog.setEnabled(previousDebug); }
    }

    @Test public void smallInvalidUtf8ReportsEncodingRatherThanAnUnrelatedSizeLimit() {
        files.input = new byte[]{(byte) 0xff};
        view("import").performClick(); picker.select(); finish();
        String status = ((TextView) view("status")).getText().toString();
        assertTrue(status, status.contains("UTF-8"));
        assertFalse(status, status.contains("2 MiB") || status.contains("32 MiB"));
        assertEquals(0, store.applies);
    }

    @Test public void linkageFailureDuringReadingCompletesWithASafeCompatibilityReasonInsteadOfStalling() {
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        try {
            files.linkageFailure = true;
            view("import").performClick(); picker.select(); finish();
            String status = ((TextView) view("status")).getText().toString();
            assertTrue("Linkage failure must leave the busy state with a compatibility reason", status.contains("兼容"));
            assertFalse(status.contains("private-linkage-key"));
            assertFalse(view("confirm").isEnabled());
            assertTrue(view("import").isEnabled());
            assertReadFailureEvidence("java.lang.NoSuchMethodError", "private-linkage-key");
            assertEquals(0, store.applies);
        } finally { DouAiLog.awaitIdle(5000); DebugLog.setEnabled(previousDebug); }
    }

    @Test public void successfulReadsLogOnlyByteCountAndSha256WithoutDocumentOrConfigurationContents() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("native_auto_reply", true);
        source.values.put(SpeechSettings.MINIMAX_API_KEY, "private-hash-fixture-key");
        files.input = ConfigTransfer.export(source, true).getBytes(StandardCharsets.UTF_8);
        byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(files.input);
        String expectedHash = String.format(java.util.Locale.ROOT, "%064X", new java.math.BigInteger(1, digest));
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        try { view("import").performClick(); picker.select(); finish(); }
        finally { DebugLog.setEnabled(previousDebug); }
        assertTrue(view("confirm").isEnabled());
        List<org.json.JSONObject> logs = evidenceEvents();
        org.json.JSONObject read = logs.stream().filter(item -> "config_transfer".equals(item.optString("feature_id"))
                && "stage".equals(item.optString("event")) && "file_read".equals(item.optString("stage"))).findFirst().orElseThrow();
        assertTrue(read.optString("evidence").contains("bytes=" + files.input.length));
        assertTrue(read.optString("evidence").contains("file_contents_omitted=true"));
        String trace = read.optString("trace_id");
        assertFalse(trace.isEmpty());
        assertTrue(logs.stream().anyMatch(item -> trace.equals(item.optString("trace_id"))
                && "operation_finished".equals(item.optString("event")) && "success".equals(item.optString("outcome"))
                && "preview_validated".equals(item.optString("reason_code"))));
        String privateDiagnostic = "ConfigTransfer read: bytes=" + files.input.length + "; sha256=" + expectedHash;
        assertTrue(logs.stream().anyMatch(item -> "legacy_diagnostic".equals(item.optString("event"))
                && item.optString("evidence").contains("ref=" + DouAiLog.reference(privateDiagnostic))));
        assertTrue(((TextView) view("status")).getText().toString().contains("SHA256=" + expectedHash.substring(0, 12)));
        for (String item : evidence) {
            assertFalse(item.contains("private-hash-fixture-key"));
            assertFalse(item.contains("content://"));
            assertFalse(item.contains("file.json"));
            assertFalse(item.contains("native_auto_reply"));
            assertFalse(item.contains("\"values\""));
            assertFalse("Full document digest stays inside the private diagnostic reference", item.contains(expectedHash));
        }
        assertEquals(0, store.applies);
    }

    @Test public void localAnalysisIsExcludedByDefaultAndIncludedOnlyForTheSelectedExport() throws Exception {
        LocalAnalytics analytics = LocalAnalytics.get(activity);
        try {
            seedAnalytics(analytics, "export-source", 4, "备份测试自述");
            assertFalse(((CheckBox) view("includeAnalytics")).isChecked());
            assertEquals(View.GONE, view("restoreAnalytics").getVisibility());
            view("export").performClick(); picker.select(); finishOffMain();
            String configOnly = files.output.toString("UTF-8");
            assertFalse(ConfigBackup.inspect(store, configOnly, ConfigTransfer.Mode.MERGE).includesAnalytics);
            assertFalse(configOnly.contains("备份测试自述"));

            ((CheckBox) view("includeAnalytics")).setChecked(true);
            view("export").performClick(); picker.select(); finishOffMain();
            String bundle = files.output.toString("UTF-8");
            ConfigBackup.Preview selected = ConfigBackup.inspect(store, bundle, ConfigTransfer.Mode.MERGE);
            assertTrue(selected.includesAnalytics);
            assertTrue(selected.analyticsRows > 0);
            assertTrue(bundle.contains("备份测试自述"));
            assertTrue(((TextView) view("status")).getText().toString().contains("本地分析数据已导出"));
            assertEquals(0, store.applies);
        } finally { clearAnalytics(analytics); }
    }

    @Test public void bundledAnalysisRequiresItsOwnOptInAndRestoresEvenWhenConfigurationIsUnchanged() throws Exception {
        LocalAnalytics analytics = LocalAnalytics.get(activity);
        try {
            seedAnalytics(analytics, "restore-source", 4, "备份中的测试自述");
            MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
            String bundle = runIo(() -> ConfigBackup.export(activity, source, false, true));
            seedAnalytics(analytics, "current-local", 2, "当前的测试自述");
            analytics.setEnabled(false);
            files.input = bundle.getBytes(StandardCharsets.UTF_8);
            view("import").performClick(); picker.select(); finishOffMain();
            CheckBox restore = (CheckBox) view("restoreAnalytics");
            assertEquals(View.VISIBLE, restore.getVisibility());
            assertFalse(restore.isChecked());
            assertTrue(((TextView) view("preview")).getText().toString().contains("默认不恢复本地分析数据"));
            assertTrue(view("confirm").isEnabled());
            view("confirm").performClick(); finishOffMain();
            assertEquals(2, snapshotViews(analytics));
            assertEquals("当前的测试自述", LocalPortraitContext.current(activity).purpose);
            assertFalse(analytics.isEnabled());

            view("import").performClick(); picker.select(); finishOffMain();
            assertFalse("The restore choice resets for every new file selection", restore.isChecked());
            assertFalse("Equal configuration alone has nothing to apply", view("confirm").isEnabled());
            restore.setChecked(true);
            assertTrue("Explicit analysis restoration is meaningful without configuration changes", view("confirm").isEnabled());
            ((CheckBox) view("replace")).setChecked(true); finishOffMain();
            assertTrue("Configuration mode does not reset the independent restore choice", restore.isChecked());
            String preview = ((TextView) view("preview")).getText().toString();
            assertTrue(preview.contains("将替换当前全部本地分析记录"));
            assertTrue(preview.contains("导入方式：覆盖"));
            view("confirm").performClick(); finishOffMain();
            assertEquals(4, snapshotViews(analytics));
            assertEquals("备份中的测试自述", LocalPortraitContext.current(activity).purpose);
            assertFalse("Restore must preserve the current recording switch", analytics.isEnabled());
            assertEquals(2, changes);
        } finally { clearAnalytics(analytics); }
    }

    @Test public void cancellingBundledPreviewNeverRestoresItsAnalysis() throws Exception {
        LocalAnalytics analytics = LocalAnalytics.get(activity);
        try {
            seedAnalytics(analytics, "cancel-source", 3, "不应恢复的自述");
            files.input = runIo(() -> ConfigBackup.export(activity, store, false, true)).getBytes(StandardCharsets.UTF_8);
            seedAnalytics(analytics, "cancel-current", 1, "保留的自述");
            view("import").performClick(); picker.select(); finishOffMain();
            ((CheckBox) view("restoreAnalytics")).setChecked(true);
            view("cancel").performClick();
            assertFalse(view("confirm").isEnabled());
            assertFalse(((CheckBox) view("restoreAnalytics")).isChecked());
            assertEquals(View.GONE, view("restoreAnalytics").getVisibility());
            assertEquals(1, snapshotViews(analytics));
            assertEquals("保留的自述", LocalPortraitContext.current(activity).purpose);
            assertEquals(0, store.applies);
        } finally { clearAnalytics(analytics); }
    }

    @Test public void previewIsReadOnlyAndExplicitConfirmationAppliesWithoutDisplayingValues() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("native_auto_reply", true);
        source.values.put("third_party_app_secret", "private-import-key");
        files.input = ConfigTransfer.export(source, true).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select();
        assertEquals(0, files.reads); finish();
        assertEquals(0, store.applies);
        assertTrue(view("confirm").isEnabled());
        String preview = ((TextView) view("preview")).getText().toString();
        assertTrue(preview.contains("格式版本"));
        assertTrue(preview.contains("native_auto_reply"));
        assertTrue(preview.contains("导入方式：合并"));
        assertEquals("Old configuration backups do not offer analysis restoration", View.GONE,
                view("restoreAnalytics").getVisibility());
        assertFalse(preview.contains("private-import-key"));
        view("confirm").performClick();
        assertEquals(0, store.applies); finish();
        assertEquals(1, store.applies);
        assertEquals(Boolean.TRUE, store.values.get("native_auto_reply"));
        assertEquals(1, changes);
    }

    @Test public void cancellingAndClosingRejectQueuedWorkLatePickerResultsAndOldConfirmation() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); view("cancel").performClick(); finish();
        assertEquals(0, files.reads); assertEquals(0, store.applies);
        view("import").performClick(); picker.select(); finish();
        View confirm = view("confirm");
        assertTrue(confirm.isEnabled());
        page.close(); confirm.performClick(); finish();
        assertEquals(0, store.applies);
        assertEquals(0, changes);
    }

    @Test public void closedFileChooserCannotStartAnImport() {
        view("import").performClick();
        Consumer<Uri> late = picker.callback;
        page.close();
        assertTrue(picker.closed);
        late.accept(Uri.parse("content://backup/late")); finish();
        assertEquals(0, files.reads); assertEquals(0, store.applies);
    }

    @Test public void cancelledAnalyticsExportRejectsLatePickerResultsWhileThePageStaysOpen() {
        ((CheckBox) view("includeAnalytics")).setChecked(true);
        view("export").performClick();
        Consumer<Uri> late = picker.callback;
        view("cancel").performClick();
        assertTrue(page.dialog().isShowing());
        assertTrue(picker.closed);
        late.accept(Uri.parse("content://backup/cancelled-analysis.json")); finish();
        assertEquals("A cancelled optional analysis export must not write any file", 0, files.writes);
        assertEquals(0, files.reads); assertEquals(0, store.applies);
        assertFalse(view("confirm").isEnabled());
        assertTrue(((TextView) view("status")).getText().toString().startsWith("已取消"));
    }

    @Test public void oldCancelledPickerCannotReplaceANewerSelectionAndDuplicateResultsAreIgnored() {
        view("import").performClick();
        Consumer<Uri> stale = picker.callback;
        view("cancel").performClick();
        view("export").performClick();
        Consumer<Uri> current = picker.callback;
        stale.accept(Uri.parse("content://backup/stale.json")); finish();
        assertFalse("The newer chooser must remain active", picker.closed);
        assertEquals(0, files.reads); assertEquals(0, files.writes);
        current.accept(Uri.parse("content://backup/current.json")); finish();
        assertEquals(1, files.writes);
        current.accept(Uri.parse("content://backup/duplicate.json")); finish();
        assertEquals("Each picker result is consumed once", 1, files.writes);
        assertEquals(0, files.reads); assertEquals(0, store.applies);
    }

    @Test public void synchronousPickerResultClosesItsReturnedHandleAndAllowsTheNextExport() {
        page.close(); page.dialog().dismiss();
        java.util.concurrent.atomic.AtomicInteger closedHandles = new java.util.concurrent.atomic.AtomicInteger();
        ConfigTransferDialog.Picker synchronous = (owner, export, name, callback) -> {
            callback.accept(Uri.parse("content://backup/synchronous.json"));
            return () -> closedHandles.incrementAndGet();
        };
        page = new ConfigTransferDialog(activity, store, false, () -> changes++, files, synchronous, executor);
        page.dialog().show(); ShadowLooper.idleMainLooper();
        view("export").performClick(); finish();
        assertEquals(1, files.writes); assertEquals(1, closedHandles.get());
        assertEquals("配置已导出。", ((TextView) view("status")).getText().toString());
        view("export").performClick(); finish();
        assertEquals(2, files.writes); assertEquals(2, closedHandles.get());
    }

    @Test public void cancellingBlockedReadClosesProviderOnBackgroundThreadAndIgnoresItsLateData() throws Exception {
        page.close(); page.dialog().dismiss();
        CountDownLatch reading = new CountDownLatch(1);
        CountDownLatch released = new CountDownLatch(1);
        CountDownLatch workerDone = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean uiClose = new java.util.concurrent.atomic.AtomicBoolean();
        InputStream blocked = new InputStream() {
            @Override public int read() {
                reading.countDown();
                while (released.getCount() != 0) {
                    try { released.await(); } catch (InterruptedException ignored) { }
                }
                return -1;
            }
            @Override public void close() {
                uiClose.set(android.os.Looper.myLooper() == android.os.Looper.getMainLooper());
                released.countDown();
            }
        };
        page = new ConfigTransferDialog(activity, store, false, () -> changes++, new ConfigTransferDialog.Files() {
            @Override public InputStream read(Uri uri) { return blocked; }
            @Override public OutputStream write(Uri uri) { throw new AssertionError(); }
        }, picker, task -> new Thread(() -> { try { task.run(); } finally { workerDone.countDown(); } }).start());
        page.dialog().show(); ShadowLooper.idleMainLooper();
        try {
            view("import").performClick(); picker.select();
            assertTrue(reading.await(3, TimeUnit.SECONDS));
            view("cancel").performClick();
            assertTrue(workerDone.await(3, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper();
            assertFalse(uiClose.get());
            assertFalse(view("confirm").isEnabled());
            assertEquals(0, store.applies);
            assertTrue(((TextView) view("status")).getText().toString().startsWith("已取消"));
        } finally { released.countDown(); }
    }

    @Test public void rejectsOversizedMalformedAndProviderErrorsWithoutLeakingTheirContent() throws Exception {
        files.input = new byte[ConfigTransfer.MAX_BYTES + 1];
        view("import").performClick(); picker.select(); finish();
        assertFalse(view("confirm").isEnabled()); assertEquals(0, store.applies);
        files.input = "{private-malformed-key".getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-malformed-key"));
        files.fail = true;
        view("import").performClick(); picker.select(); finish();
        assertFalse(allText(page.dialog().getWindow().getDecorView()).contains("private-provider-key"));
        assertEquals(0, store.applies);
    }

    @Test public void settingChangedAfterPreviewRequiresAnewPreview() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        store.values.put("reply_mode", "random");
        view("confirm").performClick(); finish();
        assertEquals(0, store.applies);
        assertEquals("random", store.values.get("reply_mode"));
        assertFalse(view("confirm").isEnabled());
    }

    @Test public void switchingToReplaceReinspectsTheOriginalBackupAndRequiresNewConfirmation() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        store.values.put("reply_mode", "random");
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        assertFalse(((CheckBox) view("replace")).isChecked());
        view("import").performClick(); picker.select(); finish();
        assertTrue(((TextView) view("preview")).getText().toString().contains("将移除：0 项"));
        ((CheckBox) view("replace")).setChecked(true);
        assertFalse(view("confirm").isEnabled());
        assertEquals("", ((TextView) view("preview")).getText().toString());
        assertEquals(0, store.applies);
        finish();
        assertEquals("Mode changes use the original JSON without another file read", 1, files.reads);
        assertTrue(((TextView) view("preview")).getText().toString().contains("将移除：1 项"));
        assertTrue(((TextView) view("preview")).getText().toString().contains("导入方式：覆盖"));
        view("confirm").performClick(); finish();
        assertFalse(store.values.containsKey("reply_mode"));
        assertEquals(Boolean.TRUE, store.values.get("native_auto_reply"));
        assertEquals(1, store.applies);
    }

    @Test public void closingDuringModeReinspectionCannotPublishOrApplyItsPreview() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("native_auto_reply", true);
        files.input = ConfigTransfer.export(source, false).getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.select(); finish();
        ((CheckBox) view("replace")).setChecked(true);
        View oldConfirm = view("confirm");
        page.close(); finish(); oldConfirm.performClick(); finish();
        assertEquals(0, store.applies); assertEquals(0, changes);
        assertEquals("", ((TextView) view("preview")).getText().toString());
    }

    @Test public void backupNavigationClosesWithSettingsAndLeavesStorageUntouched() {
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView(); activity.setContentView(home);
        home.findViewWithTag("settings.category.EXTENSION").performClick();
        Dialog extension = ShadowDialog.getLatestDialog();
        extension.getWindow().getDecorView().findViewWithTag("settings.option.配置备份").performClick();
        Dialog backup = ShadowDialog.getLatestDialog();
        assertTrue(backup.isShowing());
        assertNotNull(backup.getWindow().getDecorView().findViewWithTag("config.backup.export"));
        backup.dismiss(); ShadowLooper.idleMainLooper();
        assertTrue(extension.isShowing());
        extension.getWindow().getDecorView().findViewWithTag("settings.option.配置备份").performClick();
        Dialog reopenedBackup = ShadowDialog.getLatestDialog();
        assertTrue(reopenedBackup.isShowing());
        screen.dismissChildPages(); ShadowLooper.idleMainLooper();
        assertFalse(reopenedBackup.isShowing()); assertFalse(extension.isShowing());
        assertEquals(0, store.applies);
    }

    private List<org.json.JSONObject> evidenceEvents() {
        assertTrue(DouAiLog.awaitIdle(5000));
        List<org.json.JSONObject> events = new java.util.ArrayList<>();
        for (String line : evidence) {
            try { events.add(new org.json.JSONObject(line)); }
            catch (org.json.JSONException invalid) { throw new AssertionError("Structured log is not valid JSON", invalid); }
        }
        return events;
    }

    private void assertReadFailureEvidence(String exceptionClass, String privateMessage) {
        List<org.json.JSONObject> events = evidenceEvents();
        org.json.JSONObject exception = events.stream().filter(item -> "config_transfer".equals(item.optString("feature_id"))
                && "exception".equals(item.optString("event")) && "read_file".equals(item.optString("reason_code"))
                && exceptionClass.equals(item.optString("exception_class"))).findFirst().orElseThrow();
        assertEquals(DouAiLog.reference(privateMessage), exception.optString("exception_message_ref"));
        String trace = exception.optString("trace_id");
        assertFalse(trace.isEmpty());
        assertTrue(events.stream().anyMatch(item -> trace.equals(item.optString("trace_id"))
                && "operation_finished".equals(item.optString("event")) && "failed".equals(item.optString("outcome"))
                && "backup_action_failed".equals(item.optString("reason_code"))));
        assertTrue(evidence.stream().noneMatch(item -> item.contains(privateMessage)));
    }

    private View view(String tag) { return page.dialog().getWindow().getDecorView().findViewWithTag("config.backup." + tag); }
    private void finish() { executor.runAll(); ShadowLooper.idleMainLooper(); }
    private void finishOffMain() throws Exception {
        runIo(() -> { executor.runAll(); return null; }); ShadowLooper.idleMainLooper();
    }
    private static <T> T runIo(java.util.concurrent.Callable<T> action) throws Exception {
        java.util.concurrent.FutureTask<T> completed = new java.util.concurrent.FutureTask<>(action);
        new Thread(completed, "config-backup-ui-test").start();
        return completed.get(10, TimeUnit.SECONDS);
    }
    private static long snapshotViews(LocalAnalytics analytics) throws Exception {
        return runIo(() -> analytics.snapshot(7).totalViews);
    }
    private void seedAnalytics(LocalAnalytics analytics, String prefix, int count, String purpose) throws Exception {
        runIo(() -> {
            analytics.setEnabled(false); analytics.clear(); LocalPortraitContext.clear(activity); analytics.setEnabled(true);
            for (int index = 0; index < count; index++) analytics.recordView(prefix + index,
                    index % 2 == 0 ? "short_video" : "image", "test-author", "测试作者",
                    List.of("测试话题"), false, 20_000);
            assertEquals(count, analytics.snapshot(7).totalViews);
            LocalPortraitContext.setPurpose(activity, purpose); return null;
        });
    }
    private void clearAnalytics(LocalAnalytics analytics) throws Exception {
        runIo(() -> { analytics.setEnabled(false); analytics.clear(); LocalPortraitContext.clear(activity); return null; });
    }
    private static String allText(View view) {
        StringBuilder value = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            value.append(allText(((ViewGroup) view).getChildAt(index)));
        }
        return value.toString();
    }
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> queued = new ArrayDeque<>();
        @Override public void execute(Runnable task) { queued.add(task); }
        void runAll() { while (!queued.isEmpty()) queued.remove().run(); }
    }
    private static final class Files implements ConfigTransferDialog.Files {
        byte[] input = new byte[0]; ByteArrayOutputStream output; int reads, writes; boolean fail, linkageFailure;
        @Override public InputStream read(Uri uri) {
            if (linkageFailure) throw new NoSuchMethodError("private-linkage-key");
            reads++; if (fail) throw new IllegalStateException("private-provider-key");
            return new ByteArrayInputStream(input);
        }
        @Override public OutputStream write(Uri uri) { writes++; output = new ByteArrayOutputStream(); return output; }
    }
    private static final class Picker implements ConfigTransferDialog.Picker {
        Consumer<Uri> callback; boolean closed;
        @Override public AutoCloseable launch(Activity activity, boolean export, String name, Consumer<Uri> callback) {
            this.callback = callback; closed = false; return () -> closed = true;
        }
        void select() { callback.accept(Uri.parse("content://backup/file.json")); }
    }
    static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>(); int applies;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.get(key) instanceof Boolean ? (Boolean) values.get(key) : fallback; }
        @Override public String getString(String key, String fallback) { return values.get(key) instanceof String ? (String) values.get(key) : fallback; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public boolean isInHost() { return true; }
        @Override public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        @Override public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (!values.equals(expected)) throw new IllegalStateException("changed");
            values.clear(); values.putAll(replacement); applies++;
        }
    }
}
