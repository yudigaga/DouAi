package com.douai.module.ui;

import android.app.Activity;
import android.net.Uri;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.TextView;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.BackupLimitException;
import com.douai.module.config.BackupValidationException;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;
import org.robolectric.shadows.ShadowLooper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import static org.junit.Assert.*;

/** The displayed diagnostic must identify a failed check without displaying document contents. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigTransferDiagnosticsTest {
    private static final String SECRET = "private-diagnostic-fixture-value";
    private static final String PRIVATE_KEY = "private-unregistered-fixture-key";
    private Activity activity;
    private Store store;
    private Files files;
    private Picker picker;
    private ManualExecutor worker;
    private ConfigTransferDialog page;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new Store(); files = new Files(); picker = new Picker(); worker = new ManualExecutor();
        page = new ConfigTransferDialog(activity, store, false, () -> fail("Rejected data must not be applied"),
                files, picker, worker);
        page.dialog().show(); ShadowLooper.idleMainLooper(); ShadowLog.clear();
    }

    @After public void close() {
        page.close(); page.dialog().dismiss(); activity.finish(); ShadowLooper.idleMainLooper();
    }

    @Test public void malformedJsonShowsBundleJsonReasonAndTheReadFileFingerprint() throws Exception {
        inspect("{\"" + PRIVATE_KEY + "\":\"" + SECRET + "\",");
        assertDiagnostic("BUNDLE_JSON", "INVALID_JSON");
        assertFingerprint();
        assertSafe();
    }

    @Test public void bundleVersionIdentifiesTheHeaderFieldRatherThanOnlyValidateData() throws Exception {
        JSONObject bundle = bundle(); bundle.put("backupFormatVersion", 2);
        inspect(bundle.toString());
        assertDiagnostic("BUNDLE_HEADER", "FORMAT_VERSION", "backupFormatVersion");
        assertFingerprint();
        assertSafe();
    }

    @Test public void configVersionIdentifiesTheConfigurationHeaderField() throws Exception {
        JSONObject config = config(); config.put("formatVersion", 2);
        inspect(config.toString());
        assertDiagnostic("CONFIG", "FORMAT_VERSION", "formatVersion");
        assertSafe();
    }

    @Test public void bothImportModesIdentifyTheRegisteredWrongTypeWithoutDisplayingItsValue() throws Exception {
        JSONObject config = config();
        config.getJSONObject("values").put("native_auto_reply", typed("boolean", SECRET));
        String original = config.toString();
        for (boolean replace : new boolean[]{false, true}) {
            ((CheckBox) view("replace")).setChecked(replace);
            inspect(original);
            assertDiagnostic("CONFIG", "TYPE_MISMATCH", "native_auto_reply");
            assertFingerprint();
            assertSafe();
        }
    }

    @Test public void malformedHeadersIdentifyOnlyTheRegisteredConfigurationKey() throws Exception {
        JSONObject config = config();
        JSONObject headers = new JSONObject().put("Authorization", SECRET).put(PRIVATE_KEY, 1);
        config.getJSONObject("values").put("third_party_headers", typed("string", headers.toString()));
        inspect(config.toString());
        assertDiagnostic("CONFIG", "STRUCTURED_HEADERS", "third_party_headers");
        assertSafe();
    }

    @Test public void unknownConfigurationKeysAreClassifiedWithoutEchoingTheirNames() throws Exception {
        JSONObject config = config();
        config.getJSONObject("values").put(PRIVATE_KEY, typed("string", SECRET));
        inspect(config.toString());
        assertDiagnostic("CONFIG", "UNKNOWN_KEY");
        assertSafe();
    }

    @Test public void analyticsVersionIdentifiesTheAnalyticsVersionField() throws Exception {
        JSONObject bundle = bundle(); bundle.getJSONObject("analytics").put("version", 4);
        inspect(bundle.toString());
        assertDiagnostic("ANALYTICS", "ANALYTICS_VERSION", "version");
        assertSafe();
    }

    @Test public void analyticsFutureCaptureIdentifiesTimestampRangeWithoutEchoingTheTimestamp() throws Exception {
        JSONObject bundle = bundle();
        long future = System.currentTimeMillis() + 24 * 60 * 60 * 1000L;
        bundle.getJSONObject("analytics").put("capturedAt", future);
        inspect(bundle.toString());
        assertDiagnostic("ANALYTICS", "CAPTURED_AT_RANGE", "capturedAt");
        assertFalse(status().contains(Long.toString(future)));
        assertSafe();
    }

    @Test public void currentStoreFailureIsSeparatedFromIncomingDataWithoutItsExceptionMessage() throws Exception {
        String original = config().toString();
        store.failSnapshot = true;
        inspect(original);
        assertDiagnostic("CONFIG", "CURRENT_STORE");
        assertSafe();
    }

    @Test public void controlledFailureKindsKeepNoOriginalMessageAndPreserveTypedDiagnosticsAndLimits() throws Exception {
        Throwable[] failures = {new org.json.JSONException(SECRET), new IllegalArgumentException(SECRET),
                new ClassCastException(SECRET), new SecurityException(SECRET), new UnsupportedOperationException(SECRET),
                new NoSuchMethodError(SECRET), new IllegalStateException(SECRET)};
        BackupValidationException.FailureKind[] kinds = {BackupValidationException.FailureKind.JSON,
                BackupValidationException.FailureKind.ARGUMENT, BackupValidationException.FailureKind.TYPE,
                BackupValidationException.FailureKind.SECURITY, BackupValidationException.FailureKind.UNSUPPORTED,
                BackupValidationException.FailureKind.LINKAGE, BackupValidationException.FailureKind.OTHER};
        for (int index = 0; index < failures.length; index++) {
            IllegalArgumentException wrapped = BackupValidationException.wrap(BackupValidationException.Section.CONFIG,
                    BackupValidationException.Reason.CURRENT_STORE, PRIVATE_KEY, failures[index]);
            assertTrue(wrapped instanceof BackupValidationException);
            BackupValidationException diagnostic = (BackupValidationException) wrapped;
            assertEquals(kinds[index], diagnostic.failureKind);
            assertEquals("", diagnostic.field);
            assertNull(diagnostic.getCause());
            assertFalse(diagnostic.toString().contains(SECRET));
            assertFalse(diagnostic.toString().contains(PRIVATE_KEY));
        }
        BackupValidationException explicit = new BackupValidationException(BackupValidationException.Section.ANALYTICS,
                BackupValidationException.Reason.ANALYTICS_CELL, "visits.visible_ms");
        assertEquals(BackupValidationException.FailureKind.EXPLICIT, explicit.failureKind);
        assertEquals("visits.visible_ms", explicit.field);
        assertSame(explicit, BackupValidationException.wrap(BackupValidationException.Section.CONFIG,
                BackupValidationException.Reason.UNEXPECTED, null, explicit));
        BackupLimitException limit = new BackupLimitException(BackupLimitException.Section.FILE);
        assertSame(limit, BackupValidationException.wrap(BackupValidationException.Section.BUNDLE_JSON,
                BackupValidationException.Reason.INVALID_JSON, null, limit));
    }

    private static JSONObject config() throws Exception {
        return new JSONObject(ConfigTransfer.export(new Store(), true));
    }

    private static JSONObject bundle() throws Exception {
        JSONObject tables = new JSONObject();
        for (String table : new String[]{"daily", "hours", "work_days", "ranks", "recent", "meta",
                "visits", "visit_topics", "actions", "collection_boundaries"}) tables.put(table, new JSONArray());
        JSONObject analytics = new JSONObject().put("version", 1).put("capturedAt", System.currentTimeMillis())
                .put("tables", tables).put("context", new JSONObject().put("purpose", "").put("feedback", ""));
        return new JSONObject().put("backupFormatVersion", 1).put("kind", "douai-local-backup")
                .put("config", config()).put("analytics", analytics);
    }

    private static JSONObject typed(String type, Object value) throws Exception {
        return new JSONObject().put("type", type).put("value", value);
    }

    private void inspect(String json) {
        files.input = json.getBytes(StandardCharsets.UTF_8);
        view("import").performClick(); picker.selected.accept(Uri.parse("content://private-fixture/secret-file.json"));
        worker.runAll(); ShadowLooper.idleMainLooper();
        assertFalse("Rejected backup must keep confirmation disabled", view("confirm").isEnabled());
        assertTrue("The import action must leave its busy state", view("import").isEnabled());
        assertEquals(0, store.applies);
    }

    private void assertDiagnostic(String... tokens) {
        String text = status();
        for (String token : tokens) assertTrue("Visible diagnostic must identify " + token, text.contains(token));
    }

    private void assertFingerprint() throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(files.input);
        String hash = String.format(Locale.ROOT, "%064X", new java.math.BigInteger(1, digest));
        assertTrue("The read bytes must be visible for same-file diagnosis", status().contains(Integer.toString(files.input.length)));
        assertTrue("A short SHA-256 must identify the exact read file", status().contains(hash.substring(0, 12)));
    }

    private void assertSafe() {
        StringBuilder displayed = new StringBuilder(allText(page.dialog().getWindow().getDecorView()));
        for (ShadowLog.LogItem log : ShadowLog.getLogsForTag("DouAi")) {
            displayed.append('\n').append(log.msg);
            assertNull("Diagnostics must not attach a throwable whose message may include document data", log.throwable);
        }
        String diagnostic = displayed.toString();
        assertFalse(diagnostic.contains(SECRET));
        assertFalse(diagnostic.contains(PRIVATE_KEY));
        assertFalse(diagnostic.contains("content://"));
        assertFalse(diagnostic.contains("secret-file.json"));
        assertEquals(0, store.applies);
    }

    private static String allText(View view) {
        StringBuilder result = new StringBuilder(view instanceof TextView ? ((TextView) view).getText() : "");
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            result.append('\n').append(allText(((ViewGroup) view).getChildAt(index)));
        }
        return result.toString();
    }

    private String status() { return ((TextView) view("status")).getText().toString(); }
    private View view(String tag) { return page.dialog().getWindow().getDecorView().findViewWithTag("config.backup." + tag); }

    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }

    private static final class Files implements ConfigTransferDialog.Files {
        byte[] input = new byte[0];
        @Override public InputStream read(Uri uri) { return new ByteArrayInputStream(input); }
        @Override public OutputStream write(Uri uri) { return new ByteArrayOutputStream(); }
    }

    private static final class Picker implements ConfigTransferDialog.Picker {
        Consumer<Uri> selected;
        @Override public AutoCloseable launch(Activity activity, boolean export, String name, Consumer<Uri> selected) {
            this.selected = selected; return () -> { };
        }
    }

    private static final class Store implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        boolean failSnapshot; int applies;
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public boolean getBoolean(String key, boolean fallback) { return values.get(key) instanceof Boolean ? (Boolean) values.get(key) : fallback; }
        @Override public String getString(String key, String fallback) { return values.get(key) instanceof String ? (String) values.get(key) : fallback; }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public boolean isInHost() { return true; }
        @Override public Map<String, ?> snapshot() {
            if (failSnapshot) throw new IllegalArgumentException(SECRET);
            return new LinkedHashMap<>(values);
        }
        @Override public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) { applies++; }
    }
}
