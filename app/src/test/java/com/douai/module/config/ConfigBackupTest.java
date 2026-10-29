package com.douai.module.config;

import android.content.Context;
import android.content.ContextWrapper;
import android.database.sqlite.SQLiteDatabase;
import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.analytics.LocalPortraitContext;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigBackupTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private Context sourceContext, targetContext;
    private final List<LocalAnalytics> opened = new ArrayList<>();
    private MemoryStore source, target;

    @Before public void setup() throws Exception {
        sourceContext = context("source"); targetContext = context("target");
        source = new MemoryStore(); target = new MemoryStore();
        source.values.put("auto_play", true);
    }
    @After public void cleanup() { for (LocalAnalytics analytics : opened) analytics.close(); }

    @Test public void defaultPathIsExactlyLegacyAndDoesNotNeedAnalyticsContext() throws Exception {
        String exported = ConfigBackup.export(null, source, false, false);
        assertEquals(1, new JSONObject(exported).getInt("formatVersion"));
        assertFalse(exported.contains("analytics"));
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, exported, ConfigTransfer.Mode.MERGE);
        assertFalse(preview.includesAnalytics); assertEquals(0, preview.analyticsRows);
        ConfigBackup.apply(null, target, preview, false);
        assertEquals(source.values, target.values);
        assertEquals(0, sourceContext.getNoBackupFilesDir().list().length);
        assertEquals(0, targetContext.getNoBackupFilesDir().list().length);
    }

    @Test public void plainBackupMigratesOldCustomSpeedAndTheModuleOnlySwitch() throws Exception {
        source.values.put(ConfigurationCatalog.PLAYBACK_SPEED, "3.5");
        source.values.put(LocalOptions.MODULE_ONLY_SPEED, true);
        JSONObject plain = new JSONObject(ConfigBackup.export(null, source, false, false));
        assertEquals("3", plain.getJSONObject("values").getJSONObject(ConfigurationCatalog.PLAYBACK_SPEED).getString("value"));
        assertEquals("3.5", source.values.get(ConfigurationCatalog.PLAYBACK_SPEED));
        plain.getJSONObject("values").getJSONObject(ConfigurationCatalog.PLAYBACK_SPEED).put("value", "2.75");
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, plain.toString(), ConfigTransfer.Mode.REPLACE);
        assertFalse(preview.includesAnalytics);
        assertTrue(target.values.isEmpty());
        ConfigBackup.apply(null, target, preview, false);
        assertEquals("3", target.values.get(ConfigurationCatalog.PLAYBACK_SPEED));
        assertEquals(true, target.values.get(LocalOptions.MODULE_ONLY_SPEED));
    }

    @Test public void analyticsBundleUsesTheSameLegacySpeedMigrationWithoutRestoringAnalytics() throws Exception {
        source.values.put(ConfigurationCatalog.PLAYBACK_SPEED, "3.5");
        source.values.put(LocalOptions.MODULE_ONLY_SPEED, true);
        JSONObject bundled = new JSONObject(bundle());
        JSONObject speed = bundled.getJSONObject("config").getJSONObject("values")
                .getJSONObject(ConfigurationCatalog.PLAYBACK_SPEED);
        assertEquals("3", speed.getString("value"));
        speed.put("value", "1.75");
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundled.toString(), ConfigTransfer.Mode.MERGE);
        assertTrue(preview.includesAnalytics);
        assertTrue(target.values.isEmpty());
        assertEquals(0, targetContext.getNoBackupFilesDir().list().length);
        ConfigBackup.apply(targetContext, target, preview, false);
        assertEquals("1.5", target.values.get(ConfigurationCatalog.PLAYBACK_SPEED));
        assertEquals(true, target.values.get(LocalOptions.MODULE_ONLY_SPEED));
        assertEquals(0, targetContext.getNoBackupFilesDir().list().length);
    }

    @Test public void previewAndUnselectedImportLeaveExistingLocalAnalysisUntouched() throws Exception {
        String bundle = bundle(); LocalAnalytics local = analytics(targetContext);
        seed(local, "target"); local.snapshot(0); local.setEnabled(false); local.snapshot(0);
        LocalPortraitContext.setPurpose(targetContext, "目标自述");
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle, ConfigTransfer.Mode.REPLACE);
        assertTrue(preview.includesAnalytics); assertTrue(preview.analyticsRows > 0);
        assertTrue(preview.analyticsSummary.contains("2 次浏览"));
        ConfigBackup.apply(targetContext, target, preview, false);
        assertEquals(1, local.snapshot(0).totalViews);
        assertEquals("目标自述", LocalPortraitContext.current(targetContext).purpose);
        assertFalse(local.isEnabled());
    }

    @Test public void selectedRestoreIncludesDetailsContextAndKeepsCurrentRecordingChoice() throws Exception {
        String bundle = bundle(); LocalAnalytics local = analytics(targetContext);
        seed(local, "old"); local.snapshot(0); local.setEnabled(false); local.snapshot(0);
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle, ConfigTransfer.Mode.MERGE);
        ConfigBackup.apply(targetContext, target, preview, true);
        AnalyticsSnapshot result = local.snapshot(0);
        assertEquals(2, result.totalViews); assertEquals(1, result.totalFeatureUses);
        assertEquals(2, result.portrait.capturedVisits); assertEquals(1, result.portrait.typeActions.size());
        assertEquals("备份自述", LocalPortraitContext.current(targetContext).purpose);
        assertEquals("部分符合", LocalPortraitContext.current(targetContext).feedback);
        assertFalse(local.isEnabled());
        // Restoring the same file remains useful even when configuration has no changes.
        local.setEnabled(true); seed(local, "new");
        ConfigBackup.Preview unchanged = ConfigBackup.inspect(target, bundle, ConfigTransfer.Mode.MERGE);
        assertEquals(0, unchanged.config.changedCount);
        ConfigBackup.apply(targetContext, target, unchanged, true);
        assertEquals(2, local.snapshot(0).totalViews); assertTrue(local.isEnabled());
        local.setEnabled(false); local.setEnabled(true); seed(local, "after-restore");
        local.recordAction(local.generation(), "after-restore", "save_media", "保存媒体", "manual", "success");
        assertEquals(3, local.snapshot(0).totalViews); assertEquals(2, local.snapshot(0).totalFeatureUses);
        assertTrue(local.isEnabled());
    }

    @Test public void staleSettingsPreviewRejectsBeforeOpeningTargetAnalytics() throws Exception {
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle(), ConfigTransfer.Mode.MERGE);
        target.values.put("keyword", "newer edit");
        assertThrows(IllegalStateException.class, () -> ConfigBackup.apply(targetContext, target, preview, true));
        assertEquals(0, targetContext.getNoBackupFilesDir().list().length);
        assertEquals("newer edit", target.values.get("keyword"));
    }

    @Test public void settingsFailureRollsBackAnalyticsAndContext() throws Exception {
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle(), ConfigTransfer.Mode.MERGE);
        LocalAnalytics local = analytics(targetContext); seed(local, "old"); local.snapshot(0);
        LocalPortraitContext.setPurpose(targetContext, "原有自述"); target.failCommit = true;
        assertThrows(IllegalStateException.class, () -> ConfigBackup.apply(targetContext, target, preview, true));
        assertEquals(1, local.snapshot(0).totalViews); assertTrue(local.isEnabled());
        assertEquals("原有自述", LocalPortraitContext.current(targetContext).purpose);
        assertTrue(target.values.isEmpty());
    }

    @Test public void contextWriteFailureRollsBackAnalyticsBeforeSettingsAreChanged() throws Exception {
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle(), ConfigTransfer.Mode.MERGE);
        LocalAnalytics local = analytics(targetContext); seed(local, "old"); local.snapshot(0);
        LocalPortraitContext.setPurpose(targetContext, "原有自述");
        File blocked = new File(targetContext.getNoBackupFilesDir(), "douai-analytics/portrait-context.json.pending");
        assertTrue(blocked.mkdir());
        assertThrows(IllegalStateException.class, () -> ConfigBackup.apply(targetContext, target, preview, true));
        assertEquals(1, local.snapshot(0).totalViews); assertTrue(target.values.isEmpty());
        assertEquals("原有自述", LocalPortraitContext.current(targetContext).purpose);
    }

    @Test public void databaseFailureAfterSettingsSaveCompensatesOtherStores() throws Exception {
        JSONObject bundle = new JSONObject(bundle());
        JSONArray boundaries = bundle.getJSONObject("analytics").getJSONObject("tables").getJSONArray("collection_boundaries");
        for (int index = 0; index < boundaries.length(); index++) boundaries.getJSONArray(index).put(1, 0);
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle.toString(), ConfigTransfer.Mode.MERGE);
        LocalAnalytics local = analytics(targetContext); seed(local, "old"); local.snapshot(0);
        LocalPortraitContext.setPurpose(targetContext, "原有自述");
        Field field = LocalAnalytics.class.getDeclaredField("database"); field.setAccessible(true);
        SQLiteDatabase database = (SQLiteDatabase) field.get(local);
        database.execSQL("CREATE TRIGGER block_restore_boundary BEFORE INSERT ON collection_boundaries WHEN NEW.ts > 0 BEGIN SELECT RAISE(ABORT, 'fixture failure'); END");
        assertThrows(RuntimeException.class, () -> ConfigBackup.apply(targetContext, target, preview, true));
        assertEquals(1, local.snapshot(0).totalViews); assertTrue(local.isEnabled());
        assertEquals("原有自述", LocalPortraitContext.current(targetContext).purpose);
        assertTrue(target.values.isEmpty()); assertEquals(2, target.commits);
    }

    @Test public void queuedAndDeferredObservationsFromBeforeRestoreCannotRepopulateSnapshot() throws Exception {
        ConfigBackup.Preview preview = ConfigBackup.inspect(target, bundle(), ConfigTransfer.Mode.MERGE);
        LocalAnalytics local = analytics(targetContext); seed(local, "old"); local.snapshot(0);
        CountDownLatch running = new CountDownLatch(1), release = new CountDownLatch(1);
        Field field = LocalAnalytics.class.getDeclaredField("writer"); field.setAccessible(true);
        ((ThreadPoolExecutor) field.get(local)).execute(() -> {
            running.countDown(); try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException failure) { Thread.currentThread().interrupt(); }
        });
        assertTrue(running.await(5, TimeUnit.SECONDS)); long generation = local.generation();
        seed(local, "queued-old");
        try { ConfigBackup.apply(targetContext, target, preview, true); }
        finally { release.countDown(); }
        local.recordView(generation, "deferred-old", "image", "author", "作者", List.of(), false, 0);
        assertEquals(2, local.snapshot(0).totalViews);
    }

    @Test public void invalidLogicalSnapshotsAreRejectedWithoutAnyLocalMutation() throws Exception {
        String bundle = bundle();
        for (String defect : List.of("negative", "float", "future", "duplicate", "table", "hash", "column", "purpose", "enabled", "hugeId")) {
            JSONObject root = new JSONObject(bundle), data = root.getJSONObject("analytics");
            JSONObject tables = data.getJSONObject("tables");
            switch (defect) {
                case "negative": tables.getJSONArray("daily").getJSONArray(0).put(1, -1); break;
                case "float": tables.getJSONArray("daily").getJSONArray(0).put(1, 1.5); break;
                case "future": tables.getJSONArray("daily").getJSONArray(0).put(0, "2999-12-31"); break;
                case "duplicate": tables.getJSONArray("daily").put(tables.getJSONArray("daily").getJSONArray(0)); break;
                case "table": tables.put("arbitrary_sql", new JSONArray()); break;
                case "hash": tables.getJSONArray("work_days").getJSONArray(0).put(1, "../../secret"); break;
                case "column": tables.getJSONArray("daily").getJSONArray(0).put(5, "extra"); break;
                case "purpose": data.getJSONObject("context").put("purpose", "x".repeat(241)); break;
                case "enabled": tables.getJSONArray("collection_boundaries").getJSONArray(0).put(2, 2); break;
                case "hugeId": tables.getJSONArray("collection_boundaries").getJSONArray(0).put(0, Long.MAX_VALUE - 1); break;
                default: fail();
            }
            assertThrows(defect, IllegalArgumentException.class,
                    () -> ConfigBackup.inspect(target, root.toString(), ConfigTransfer.Mode.MERGE));
        }
        assertTrue(target.values.isEmpty()); assertEquals(0, targetContext.getNoBackupFilesDir().list().length);
    }

    @Test public void bundleParserRejectsDuplicateFieldsAndTrailingContent() throws Exception {
        String valid = bundle();
        assertThrows(IllegalArgumentException.class, () -> ConfigBackup.inspect(target, valid + "{}", ConfigTransfer.Mode.MERGE));
        String duplicate = valid.replace("\"backupFormatVersion\":1", "\"backupFormatVersion\":1,\"backupFormatVersion\":1");
        assertNotEquals(valid, duplicate);
        assertThrows(IllegalArgumentException.class, () -> ConfigBackup.inspect(target, duplicate, ConfigTransfer.Mode.MERGE));
    }

    private String bundle() {
        LocalAnalytics local = analytics(sourceContext); local.setEnabled(true);
        seed(local, "a"); seed(local, "b");
        local.recordAction(local.generation(), "b", "save_media", "保存媒体", "manual", "success");
        LocalPortraitContext.setPurpose(sourceContext, "备份自述"); LocalPortraitContext.setFeedback(sourceContext, "部分符合");
        String encoded = ConfigBackup.export(sourceContext, source, false, true);
        assertFalse(encoded.contains("enabled.pending")); assertFalse(encoded.contains("usage.db"));
        return encoded;
    }
    private void seed(LocalAnalytics local, String work) {
        if (!local.isEnabled()) local.setEnabled(true);
        local.beginVisit(local.generation(), work, "short_video", "author", "作者", List.of("话题"), false, 30_000, "manual");
    }
    private LocalAnalytics analytics(Context context) {
        LocalAnalytics instance = LocalAnalytics.get(context); if (!opened.contains(instance)) opened.add(instance); return instance;
    }
    private Context context(String name) throws Exception {
        File root = temporary.newFolder(name);
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getNoBackupFilesDir() { return root; }
        };
    }
    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>(); boolean failCommit; int commits;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (failCommit || !values.equals(expected)) throw new IllegalStateException("fixture commit rejected");
            values.clear(); values.putAll(replacement); commits++;
        }
    }
}
