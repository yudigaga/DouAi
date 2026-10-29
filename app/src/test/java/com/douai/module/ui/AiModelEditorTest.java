package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.EditText;
import android.widget.CheckBox;
import android.widget.Spinner;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiModelEditorTest {
    private Activity activity;
    private MemoryStore store;
    private AiModelEditor editor;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
    }

    @After public void closeEditor() { if (editor != null) editor.close(); }

    @Test public void savingANewProfileDoesNotActivateItOrChangeAnyFeatureSelection() throws Exception {
        AiModelConfig draft = profile();
        AiModelConfig global = AiModelConfig.fromJson(draft.toJson().put("id", "global").put("name", "全局配置"));
        new AiModelSettings(Collections.singletonList(global), global.id).save(store);
        store.putString(AiModelSettings.REPLY_MODEL_ID, global.id);
        store.putString(AiModelSettings.COMMENT_MODEL_ID, global.id);
        store.putString(com.douai.module.config.AnalyticsAiSettings.MODEL_ID, global.id);
        editor = new AiModelEditor(activity, store, draft, false, () -> { });
        show(editor);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelSettings saved = AiModelSettings.read(store);
        assertEquals("Saving a profile must leave the global default unchanged", global.id, saved.activeId);
        assertEquals(2, saved.configs.size());
        assertEquals(global.id, store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
        assertEquals(global.id, store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
        assertEquals(global.id, store.getString(com.douai.module.config.AnalyticsAiSettings.MODEL_ID, ""));
        assertEquals("保存", editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
    }

    @Test public void savingTheFirstProfileLeavesTheGlobalDefaultUnselected() throws Exception {
        editor = new AiModelEditor(activity, store, profile(), false, () -> { });
        show(editor);
        assertEquals("保存", editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelSettings saved = AiModelSettings.read(store);
        assertEquals(1, saved.configs.size());
        assertEquals("", saved.activeId);
    }

    @Test public void editingAnExistingNondefaultProfileOnlySavesItsDraft() throws Exception {
        AiModelConfig editing = profile();
        AiModelConfig global = AiModelConfig.fromJson(editing.toJson().put("id", "global"));
        new AiModelSettings(java.util.Arrays.asList(global, editing), global.id).save(store);
        editor = new AiModelEditor(activity, store, editing, false, () -> { });
        show(editor);
        input(editor.dialog(), "配置名称").setText("修改后的配置");
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelSettings saved = AiModelSettings.read(store);
        assertEquals(global.id, saved.activeId);
        assertEquals("修改后的配置", saved.configs.get(1).name);
    }

    @Test public void changingResponseModeSavesAndReopensInNativeEditor() throws Exception {
        AiModelConfig original = profile();
        editor = new AiModelEditor(activity, store, original, false, () -> { });
        show(editor);
        Spinner mode = editor.dialog().getWindow().getDecorView().findViewWithTag("ai.response_mode");
        assertEquals("响应方式", mode.getContentDescription().toString());
        assertEquals("非流式", mode.getSelectedItem());
        mode.setSelection(1);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelConfig saved = AiModelSettings.read(store).configs.get(0);
        assertTrue(saved.streaming());

        editor = new AiModelEditor(activity, store, saved, false, () -> { });
        show(editor);
        mode = editor.dialog().getWindow().getDecorView().findViewWithTag("ai.response_mode");
        assertEquals("流式", mode.getSelectedItem());
        mode.setSelection(0);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(AiModelSettings.read(store).configs.get(0).streaming());
    }

    @Test public void testChatUsesUnsavedResponseModeWithoutChangingSavedProfile() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setHeader("Content-Type", "text/event-stream")
                    .setBody("data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"ok\"}}]}\n\n"
                            + "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                            + "data: [DONE]\n\n"));
            server.start();
            AiModelConfig original = AiModelConfig.fromJson(profile().toJson()
                    .put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("contextRounds", 0));
            new AiModelSettings(Collections.singletonList(original), original.id).save(store);
            editor = new AiModelEditor(activity, store, original, false, () -> { });
            show(editor);
            View root = editor.dialog().getWindow().getDecorView();
            ((Spinner) root.findViewWithTag("ai.response_mode")).setSelection(1);
            selectEffort(root, "high");
            root.findViewWithTag("ai.test_chat").performClick();
            Dialog test = ShadowDialog.getLatestDialog();
            assertTrue(test instanceof AiTestChatDialog);
            View testRoot = test.getWindow().getDecorView();
            ((EditText) testRoot.findViewWithTag("ai.chat.input")).setText("fixture");
            testRoot.findViewWithTag("ai.chat.send").performClick();
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(3, TimeUnit.SECONDS);
            assertNotNull(request);
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertTrue(body.getBoolean("stream"));
            assertEquals("high", body.getString("reasoning_effort"));
            assertFalse(AiModelSettings.read(store).configs.get(0).streaming());
            assertFalse(AiModelSettings.read(store).configs.get(0).hasReasoningEffort());
            editor.close();
        }
    }

    @Test public void reasoningEffortSavesAndReopensInNativeEditor() throws Exception {
        editor = new AiModelEditor(activity, store, profile(), false, () -> { });
        show(editor);
        Spinner effort = editor.dialog().getWindow().getDecorView().findViewWithTag("ai.reasoning_effort");
        assertEquals("推理强度", effort.getContentDescription().toString());
        assertEquals("模型默认", effort.getSelectedItem());
        selectEffort(editor.dialog().getWindow().getDecorView(), "max");
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelConfig saved = AiModelSettings.read(store).configs.get(0);
        assertEquals("max", saved.reasoningEffort);

        editor = new AiModelEditor(activity, store, saved, false, () -> { });
        show(editor);
        effort = editor.dialog().getWindow().getDecorView().findViewWithTag("ai.reasoning_effort");
        assertEquals("max", effort.getSelectedItem());
        effort.setSelection(0);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(AiModelSettings.read(store).configs.get(0).hasReasoningEffort());
    }

    @Test public void explicitReasoningConflictKeepsEditorOpenAndSavedProfileUnchanged() throws Exception {
        AiModelConfig original = profile();
        new AiModelSettings(Collections.singletonList(original), original.id).save(store);
        editor = new AiModelEditor(activity, store, original, false, () -> { });
        show(editor);
        View root = editor.dialog().getWindow().getDecorView();
        selectEffort(root, "high");
        input(editor.dialog(), "自定义参数 JSON").setText("{\"reasoning_effort\":\"medium\"}");
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.dialog().isShowing());
        assertFalse(AiModelSettings.read(store).configs.get(0).hasReasoningEffort());
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("冲突"));
        ((Spinner) root.findViewWithTag("ai.reasoning_effort")).setSelection(0);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.dialog().isShowing());
        assertEquals("medium", AiModelSettings.read(store).configs.get(0).parameters().getString("reasoning_effort"));
    }

    @Test public void invalidDraftAndFailedPersistenceStayOpenUntilSuccessfulSave() throws Exception {
        AiModelConfig original = profile();
        new AiModelSettings(Collections.singletonList(original), original.id).save(store);
        int[] refreshes = {0};
        editor = new AiModelEditor(activity, store, original, false, () -> refreshes[0]++);
        show(editor);
        AlertDialog dialog = editor.dialog();
        input(dialog, "模型名称").setText("changed-model");
        input(dialog, "超时秒数（5–120）").setText("4");
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("saved-model", AiModelSettings.read(store).configs.get(0).modelName);
        assertEquals(0, refreshes[0]);

        input(dialog, "超时秒数（5–120）").setText("30");
        store.failWrite = true;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(dialog.isShowing());
        assertEquals("saved-model", AiModelSettings.read(store).configs.get(0).modelName);
        assertEquals(0, refreshes[0]);
        store.failWrite = false;
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(dialog.isShowing());
        assertEquals("changed-model", AiModelSettings.read(store).configs.get(0).modelName);
        assertEquals(1, refreshes[0]);
    }

    @Test public void deletingFromTheContactEditorRemovesOnlyThatModelAndDoesNotReactivateIt() throws Exception {
        AiModelConfig contact = AiModelConfig.fromJson(profile().toJson()
                .put("id", "contact").put("messageContactEnabled", true).put("messageContactName", "消息助手"));
        AiModelConfig automation = AiModelConfig.fromJson(profile().toJson().put("id", "automation"));
        new AiModelSettings(java.util.Arrays.asList(contact, automation), automation.id).save(store);
        editor = AiConversationDialog.contactEditor(activity, store, contact);
        show(editor);
        editor.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        AiModelSettings remaining = AiModelSettings.read(store);
        assertEquals(1, remaining.configs.size());
        assertEquals("automation", remaining.activeId);
        assertFalse(editor.dialog().isShowing());
    }

    @Test public void staleContactDeleteCannotRemoveATemplateEditedElsewhere() throws Exception {
        AiModelConfig contact = AiModelConfig.fromJson(profile().toJson()
                .put("id", "contact").put("messageContactEnabled", true));
        new AiModelSettings(Collections.singletonList(contact), contact.id).save(store);
        editor = AiConversationDialog.contactEditor(activity, store, contact);
        show(editor);
        AiModelConfig edited = AiModelConfig.fromJson(contact.toJson().put("name", "新模板名称"));
        new AiModelSettings(Collections.singletonList(edited), edited.id).save(store);
        editor.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertTrue(editor.dialog().isShowing());
        assertEquals("新模板名称", AiModelSettings.read(store).configs.get(0).name);
    }


    @Test public void editingAContactDoesNotChangeTheActiveAutomationModel() throws Exception {
        AiModelConfig original = profile();
        AiModelConfig automation = AiModelConfig.fromJson(original.toJson().put("id", "automation"));
        new AiModelSettings(java.util.Arrays.asList(original, automation), automation.id).save(store);
        editor = new AiModelEditor(activity, store, original, false, () -> { }, false);
        show(editor);
        input(editor.dialog(), "联系人名字（留空使用配置名称）").setText("改名后的助手");
        assertEquals("保存", editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("automation", AiModelSettings.read(store).activeId);
        assertEquals("改名后的助手", AiModelSettings.read(store).configs.get(0).messageContactName);
    }

    @Test public void closingEditorClosesPromptAndCancelsBlockedCatalog() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            AiModelConfig original = AiModelConfig.fromJson(profile().toJson()
                    .put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("timeoutSeconds", 30));
            editor = new AiModelEditor(activity, store, original, false, () -> { }, worker);
            show(editor);
            View root = editor.dialog().getWindow().getDecorView();
            assertTrue(root.findViewWithTag("ai.fetch_models").performClick());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            root.findViewWithTag("ai.prompt.primary").performClick();
            Dialog prompt = ShadowDialog.getLatestDialog();
            assertTrue(prompt instanceof PromptEditorDialog);
            assertTrue(prompt.isShowing());
            editor.close();
            assertTrue("Editor close must release its blocked worker", worker.awaitTermination(2, TimeUnit.SECONDS));
            ShadowLooper.idleMainLooper();
            assertFalse(editor.dialog().isShowing());
            assertFalse(prompt.isShowing());
            assertSame(prompt, ShadowDialog.getLatestDialog());
        } finally { worker.shutdownNow(); }
    }

    @Test public void contactToggleAndNameSaveWhileDisabledContactRetainsAvatar() throws Exception {
        String avatar = com.douai.module.config.AiModelContactConfigTest.AVATAR;
        AiModelConfig original = AiModelConfig.fromJson(profile().toJson().put("messageContactAvatar", avatar));
        editor = new AiModelEditor(activity, store, original, false, () -> { });
        show(editor);
        View root = editor.dialog().getWindow().getDecorView();
        ((CheckBox) root.findViewWithTag("ai.message_contact_enabled")).setChecked(true);
        input(editor.dialog(), "联系人名字（留空使用配置名称）").setText("专属 AI");
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        AiModelConfig saved = AiModelSettings.read(store).configs.get(0);
        assertTrue(saved.messageContactEnabled);
        assertEquals("专属 AI", saved.messageContactDisplayName());
        assertEquals(avatar, saved.messageContactAvatar);

        editor = new AiModelEditor(activity, store, saved, false, () -> { });
        show(editor);
        root = editor.dialog().getWindow().getDecorView();
        ((CheckBox) root.findViewWithTag("ai.message_contact_enabled")).setChecked(false);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        saved = AiModelSettings.read(store).configs.get(0);
        assertFalse(saved.messageContactEnabled);
        assertEquals("专属 AI", saved.messageContactDisplayName());
        assertEquals(avatar, saved.messageContactAvatar);
    }

    @Test public void avatarIsCenteredAtTheTopAndItsMenuResetsTheDraftWhileContactToggleIsLast() throws Exception {
        AiModelConfig original = AiModelConfig.fromJson(profile().toJson().put("messageContactAvatar",
                com.douai.module.config.AiModelContactConfigTest.AVATAR));
        editor = new AiModelEditor(activity, store, original, false, () -> { });
        show(editor);
        View root = editor.dialog().getWindow().getDecorView();
        ImageView avatar = root.findViewWithTag("ai.message_contact_avatar_preview");
        LinearLayout form = (LinearLayout) avatar.getParent();
        assertSame(avatar, form.getChildAt(0));
        assertEquals(Gravity.CENTER_HORIZONTAL, ((LinearLayout.LayoutParams) avatar.getLayoutParams()).gravity);
        assertTrue(avatar.getClipToOutline());
        View toggle = root.findViewWithTag("ai.message_contact_enabled");
        assertSame(toggle, form.getChildAt(form.getChildCount() - 1));
        assertNull(root.findViewWithTag("ai.message_contact_avatar"));
        assertNull(root.findViewWithTag("ai.message_contact_avatar_reset"));
        avatar.performClick();
        AlertDialog actions = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("选择图片", actions.getListView().getAdapter().getItem(0));
        assertEquals("恢复默认", actions.getListView().getAdapter().getItem(1));
        actions.getListView().performItemClick(null, 1, 1);
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals("", AiModelSettings.read(store).configs.get(0).messageContactAvatar);
    }

    @Test public void optionalParameterSwitchesControlTheirInputs() throws Exception {
        editor = new AiModelEditor(activity, store, profile(), false, () -> { });
        show(editor);
        String[][] fields = {{"设置温度", "温度"}, {"限制最大输出 Token", "最大输出 Token"}, {"设置 Top P", "Top P"}};
        for (String[] field : fields) {
            CheckBox toggle = checkbox(editor.dialog().getWindow().getDecorView(), field[0]);
            EditText value = input(editor.dialog(), field[1]);
            assertFalse(field[1], value.isEnabled());
            toggle.setChecked(true);
            assertTrue(field[1], value.isEnabled());
            toggle.setChecked(false);
            assertFalse(field[1], value.isEnabled());
        }
    }

    @Test public void disabledOptionalParametersDoNotBlockSavingAndKeepSavedValues() throws Exception {
        AiModelConfig original = profile();
        editor = new AiModelEditor(activity, store, original, false, () -> { });
        show(editor);
        String[][] fields = {{"设置温度", "温度"}, {"限制最大输出 Token", "最大输出 Token"}, {"设置 Top P", "Top P"}};
        for (String[] field : fields) {
            CheckBox toggle = checkbox(editor.dialog().getWindow().getDecorView(), field[0]);
            toggle.setChecked(true);
            input(editor.dialog(), field[1]).setText("");
            toggle.setChecked(false);
        }
        editor.dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertFalse(editor.dialog().isShowing());
        AiModelConfig saved = AiModelSettings.read(store).configs.get(0);
        assertEquals(original.temperature, saved.temperature, 0);
        assertEquals(original.maxTokens, saved.maxTokens);
        assertEquals(original.topP, saved.topP, 0);
        assertFalse(saved.temperatureEnabled);
        assertFalse(saved.maxTokensEnabled);
        assertFalse(saved.topPEnabled);
    }

    private static CheckBox checkbox(View root, String label) {
        if (root instanceof CheckBox && label.contentEquals(((CheckBox) root).getText())) return (CheckBox) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                CheckBox found = checkbox(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void show(AiModelEditor value) {
        value.show((dialog, onDismiss) -> {
            dialog.setOwnerActivity(activity);
            dialog.setOnDismissListener(ignored -> onDismiss.run());
            dialog.show();
        });
    }

    private static AiModelConfig profile() throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", "editor").put("name", "配置")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "saved-model"));
    }

    private static EditText input(Dialog dialog, String label) {
        EditText found = findInput(dialog.getWindow().getDecorView(), label);
        assertNotNull(label, found);
        return found;
    }

    private static void selectEffort(View root, String value) {
        Spinner effort = root.findViewWithTag("ai.reasoning_effort");
        for (int index = 0; index < effort.getCount(); index++) {
            if (value.equals(effort.getItemAtPosition(index))) { effort.setSelection(index); return; }
        }
        fail("Missing reasoning effort option: " + value);
    }

    private static EditText findInput(View view, String label) {
        if (view instanceof EditText && label.contentEquals(view.getContentDescription())) return (EditText) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText found = findInput(group.getChildAt(index), label);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        boolean failWrite;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) {
            if (failWrite) throw new IllegalStateException("Storage unavailable");
            values.put(key, value);
        }
        public boolean isInHost() { return true; }
    }
}
