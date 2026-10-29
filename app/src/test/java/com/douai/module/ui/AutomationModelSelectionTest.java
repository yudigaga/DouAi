package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
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
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationModelSelectionTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private String savedProfiles;

    @Before public void createScreen() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        store.putString("reply_mode", "ai");
        store.putString("comment_mode", "ai");
        new AiModelSettings(List.of(profile("first", "供应商一", false),
                profile("second", "供应商二", true), profile("global", "供应商三", true)), "global").save(store);
        savedProfiles = store.getString(AiModelSettings.KEY, "");
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeScreen() {
        screen.close();
        activity.finish();
    }

    @Test public void replyAndCommentChooseDifferentExistingSuppliersWithoutChangingTheGlobalProfile() {
        View reply = openContent("reply");
        assertTrue(modelPreview(reply).startsWith("跟随全局 · 供应商三"));
        AlertDialog replyChoices = openChoices(reply);
        assertEquals(4, replyChoices.getListView().getCount());
        assertEquals("跟随全局模型", replyChoices.getListView().getAdapter().getItem(0));
        assertTrue(replyChoices.getListView().getAdapter().getItem(1).toString().contains("非流式"));
        assertTrue(replyChoices.getListView().getAdapter().getItem(2).toString().contains("流式"));
        replyChoices.getListView().performItemClick(null, 1, 1);
        assertEquals("first", store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertTrue(modelPreview(reply).startsWith("供应商一"));
        screen.dismissChildPages();

        View comment = openContent("comment");
        AlertDialog commentChoices = openChoices(comment);
        commentChoices.getListView().performItemClick(null, 2, 2);
        assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
        assertTrue(modelPreview(comment).startsWith("供应商二"));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
        assertEquals("global", AiModelSettings.read(store).activeId);
        screen.dismissChildPages();

        View reopened = openContent("reply");
        assertTrue(modelPreview(reopened).startsWith("供应商一"));
        assertEquals(1, openChoices(reopened).getListView().getCheckedItemPosition());
    }

    @Test public void followingGlobalIsTheDefaultAndCanBeRestoredWithoutChangingTheOtherFeature() {
        View reply = openContent("reply");
        AlertDialog choices = openChoices(reply);
        assertEquals(0, choices.getListView().getCheckedItemPosition());
        assertFalse(store.values.containsKey(AiModelSettings.REPLY_MODEL_ID));
        choices.getListView().performItemClick(null, 1, 1);
        store.putString(AiModelSettings.COMMENT_MODEL_ID, "second");
        openChoices(reply).getListView().performItemClick(null, 0, 0);
        assertEquals("", store.getString(AiModelSettings.REPLY_MODEL_ID, "missing"));
        assertEquals("second", store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
        assertTrue(modelPreview(reply).startsWith("跟随全局 · 供应商三"));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
    }

    @Test public void deletedExplicitSelectionRemainsVisibleAndDoesNotSilentlySelectGlobal() {
        store.putString(AiModelSettings.REPLY_MODEL_ID, "deleted");
        View reply = openContent("reply");
        assertEquals("所选配置不可用，请重新选择", modelPreview(reply));
        AlertDialog choices = openChoices(reply);
        assertEquals(-1, choices.getListView().getCheckedItemPosition());
        assertEquals("deleted", store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
        choices.getListView().performItemClick(null, 2, 2);
        assertEquals("second", store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertTrue(modelPreview(reply).startsWith("供应商二"));
    }

    @Test public void modelManagementRemainsAvailableWithoutAConfiguredProfile() {
        new AiModelSettings(List.of(), "").save(store);
        View comment = openContent("comment");
        assertEquals("跟随全局（未选择模型）", modelPreview(comment));
        AlertDialog choices = openChoices(comment);
        assertEquals(1, choices.getListView().getCount());
        choices.getButton(Dialog.BUTTON_NEUTRAL).performClick();
        ShadowLooper.idleMainLooper();
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNotNull(manager.findViewWithTag("settings.option.添加模型配置"));
        assertFalse(store.values.containsKey(AiModelSettings.COMMENT_MODEL_ID));
    }

    @Test public void aClosedContentPageCannotApplyALateSupplierChoice() {
        AlertDialog choices = openChoices(openContent("reply"));
        screen.dismissChildPages();
        ShadowLooper.idleMainLooper();
        choices.getListView().performItemClick(null, 1, 1);
        assertFalse(store.values.containsKey(AiModelSettings.REPLY_MODEL_ID));
        assertEquals(savedProfiles, store.getString(AiModelSettings.KEY, ""));
    }

    private View openContent(String kind) {
        View category = home.findViewWithTag("settings.category." + ("reply".equals(kind) ? "AUTO_REPLY" : "AUTO_COMMENT"));
        assertNotNull(category);
        assertTrue(category.performClick());
        View controls = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        View content = controls.findViewWithTag("settings.option." + ("reply".equals(kind) ? "回复内容" : "评论内容"));
        assertNotNull(content);
        assertTrue(content.performClick());
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static AlertDialog openChoices(View content) {
        View row = content.findViewWithTag("settings.option.模型配置");
        assertNotNull(row);
        assertTrue(row.performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static String modelPreview(View content) {
        TextView preview = content.findViewWithTag("settings.value.模型配置");
        assertNotNull(preview);
        return preview.getText().toString();
    }

    private static AiModelConfig profile(String id, String name, boolean streaming) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", name)
                .put("apiEndpoint", "https://" + id + ".example.test/v1").put("apiKey", "fixture-key")
                .put("modelName", id + "-model").put("responseMode", streaming
                        ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }
}
