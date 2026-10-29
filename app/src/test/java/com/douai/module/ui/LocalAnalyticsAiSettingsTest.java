package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;

import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AnalyticsAiSettings;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class LocalAnalyticsAiSettingsTest {
    private Activity activity;
    private MemoryStore store;
    private LocalAnalyticsDialog page;
    private ManualExecutor reader;
    private ManualExecutor worker;
    private AnalysisService analysis;
    private String savedProfiles;

    @Before public void showReport() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        new AiModelSettings(List.of(profile("first", false), profile("second", true), profile("global", false)),
                "global").save(store);
        savedProfiles = store.getString(AiModelSettings.KEY, "");
        store.putString(AiModelSettings.REPLY_MODEL_ID, "first");
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "second");
        store.putString("reply_prompt", "原回复提示"); store.putString("comment_prompt", "原评论提示");
        reader = new ManualExecutor(); worker = new ManualExecutor(); analysis = new AnalysisService();
        LocalAnalyticsDialog.Source source = new LocalAnalyticsDialog.Source() {
            @Override public boolean isEnabled() { return false; }
            @Override public void setEnabled(boolean enabled) { }
            @Override public AnalyticsSnapshot snapshot(int days) { return data(); }
            @Override public void clear() { }
        };
        page = new LocalAnalyticsDialog(activity, source, reader, analysis, worker, false, null, store);
        page.dialog().show(); ShadowLooper.idleMainLooper(); reader.runAll(); ShadowLooper.idleMainLooper();
    }

    @After public void closeReport() {
        page.close(); page.dialog().dismiss(); activity.finish(); ShadowLooper.idleMainLooper();
    }

    @Test public void choosingAnExistingSupplierIsIndependentAndDoesNotRequestAnalysis() {
        assertTrue(text("analytics.analysis.model").contains("跟随全局 · global供应商"));
        find("analytics.analysis.model").performClick();
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(4, chooser.getListView().getCount());
        assertEquals("跟随全局模型", chooser.getListView().getAdapter().getItem(0));
        assertTrue(chooser.getListView().getAdapter().getItem(2).toString().endsWith("流式"));
        chooser.getListView().performItemClick(null, 2, 2);
        assertEquals("second", store.getString(AnalyticsAiSettings.MODEL_ID, ""));
        assertTrue(text("analytics.analysis.model").contains("second供应商"));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
        assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
        assertTrue(analysis.requests.isEmpty());
        find("analytics.analysis.model").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 0, 0);
        assertEquals("", store.getString(AnalyticsAiSettings.MODEL_ID, "missing"));
        assertTrue(text("analytics.analysis.model").contains("跟随全局"));
    }

    @Test public void anUnselectedGlobalDefaultStillAllowsAnIndependentAnalysisModel() {
        new AiModelSettings(AiModelSettings.read(store).configs, "").save(store);
        find("analytics.refresh").performClick(); reader.runAll(); ShadowLooper.idleMainLooper();
        assertTrue(text("analytics.analysis.model").contains("跟随全局（未选择模型）"));
        assertFalse(find("analytics.analysis.request").isEnabled());
        find("analytics.analysis.model").performClick();
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        chooser.getListView().performItemClick(null, 2, 2);
        assertEquals("second", store.getString(AnalyticsAiSettings.MODEL_ID, ""));
        assertEquals("", AiModelSettings.read(store).activeId);
        assertTrue(find("analytics.analysis.request").isEnabled());
        assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
        assertTrue(analysis.requests.isEmpty());
    }

    @Test public void secondaryPromptSavesOnlyOnSaveAndCanBeRestoredToItsDefault() {
        String customPrompt = "只关注浏览时段的变化，保持简洁。".repeat(400);
        find("analytics.analysis.prompt").performClick();
        PromptEditorDialog editor = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input.本地数据分析二级提示词");
        assertEquals(AnalyticsAiSettings.DEFAULT_SECONDARY_PROMPT, input.getText().toString());
        input.setText(customPrompt);
        editor.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        assertFalse(store.values.containsKey(AnalyticsAiSettings.SECONDARY_PROMPT));
        find("analytics.analysis.prompt").performClick();
        editor = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        input = editor.getWindow().getDecorView().findViewWithTag("settings.input.本地数据分析二级提示词");
        input.setText(customPrompt);
        editor.getButton(Dialog.BUTTON_POSITIVE).performClick();
        assertEquals(customPrompt, store.getString(AnalyticsAiSettings.SECONDARY_PROMPT, ""));
        assertTrue(text("analytics.analysis.prompt").contains("已自定义"));
        assertTrue(analysis.requests.isEmpty());
        find("analytics.analysis.prompt.reset").performClick();
        assertEquals("", store.getString(AnalyticsAiSettings.SECONDARY_PROMPT, "missing"));
        assertTrue(text("analytics.analysis.prompt").endsWith("默认"));
        assertFalse(find("analytics.analysis.prompt.reset").isEnabled());
        assertEquals("原回复提示", store.getString("reply_prompt", ""));
        assertEquals("原评论提示", store.getString("comment_prompt", ""));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
    }

    @Test public void settingsStayDisabledDuringARequestAndChangesDiscardTheOldResult() {
        find("analytics.analysis.request").performClick();
        assertFalse(find("analytics.analysis.model").isEnabled());
        assertFalse(find("analytics.analysis.prompt").isEnabled());
        assertEquals(1, analysis.requests.size());
        worker.runAll(); ShadowLooper.idleMainLooper();
        assertNotNull(find("analytics.analysis.result"));
        find("analytics.analysis.model").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        assertNull(find("analytics.analysis.result"));
        assertEquals(1, analysis.requests.size());
        assertNotNull(find("analytics.analysis.request"));
        assertNull(find("analytics.analysis.text.overview"));
    }

    @Test public void aDeletedSelectionIsVisibleAndChoosingGlobalRepairsIt() {
        store.putString(AnalyticsAiSettings.MODEL_ID, "deleted");
        find("analytics.refresh").performClick(); reader.runAll(); ShadowLooper.idleMainLooper();
        assertTrue(text("analytics.analysis.model").contains("所选配置不可用，请重新选择"));
        assertFalse(find("analytics.analysis.request").isEnabled());
        find("analytics.analysis.model").performClick();
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(-1, chooser.getListView().getCheckedItemPosition());
        chooser.getListView().performItemClick(null, 0, 0);
        assertTrue(find("analytics.analysis.request").isEnabled());
        assertTrue(analysis.requests.isEmpty());
    }

    @Test public void closingTheReportRejectsLateModelAndPromptChanges() {
        find("analytics.analysis.model").performClick();
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        chooser.dismiss();
        ShadowLooper.idleMainLooper();
        find("analytics.analysis.prompt").performClick();
        PromptEditorDialog editor = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input.本地数据分析二级提示词");
        input.setText("页面关闭后不可保存");
        page.close();
        assertFalse(editor.isShowing());
        chooser.getListView().performItemClick(null, 1, 1);
        editor.getButton(Dialog.BUTTON_POSITIVE).performClick();
        assertFalse(store.values.containsKey(AnalyticsAiSettings.MODEL_ID));
        assertFalse(store.values.containsKey(AnalyticsAiSettings.SECONDARY_PROMPT));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
    }

    private <T extends View> T find(String tag) { return page.dialog().getWindow().getDecorView().findViewWithTag(tag); }
    private String text(String tag) { return ((TextView) find(tag)).getText().toString(); }

    private static AiModelConfig profile(String id, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id + "供应商")
                .put("apiEndpoint", "https://" + id + ".example.test/v1").put("modelName", id + "-model")
                .put("responseMode", streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
    }

    private static AnalyticsSnapshot data() {
        return new AnalyticsSnapshot(2, 1, 1, 0, 1, 1, 1,
                List.of(), List.of(), List.of(), List.of(), List.of(), new long[24], List.of());
    }

    private final class AnalysisService implements LocalAnalyticsDialog.AnalysisService {
        final List<Request> requests = new ArrayList<>();
        @Override public boolean isConfigured() {
            try { AnalyticsAiSettings.models(store).selected(); return true; }
            catch (RuntimeException ignored) { return false; }
        }
        @Override public String modelLabel() { return AnalyticsAiSettings.models(store).selected().name; }
        @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
            String label = modelLabel();
            Request request = new Request() {
                @Override public String modelLabel() { return label; }
                @Override public String run() throws Exception {
                    JSONObject response = new JSONObject();
                    for (String key : new String[]{"overview", "trend", "types", "authors", "topics", "features", "hours"})
                        response.put(key, "已完成图表分析");
                    return response.toString();
                }
                @Override public void cancel() { }
            };
            requests.add(request); return request;
        }
    }

    private static final class ManualExecutor implements Executor {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        @Override public void execute(Runnable command) { pending.add(command); }
        void runAll() { while (!pending.isEmpty()) pending.remove().run(); }
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        @Override public FeatureSettings load() { return FeatureSettings.defaults(); }
        @Override public void save(FeatureSettings settings) { }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
    }
}
