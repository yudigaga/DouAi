package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowInsets;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.skills.AiSkills;
import com.douai.module.ai.skills.SkillAuthoring;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.feature.TestChatMemory;

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
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;
import java.io.ByteArrayOutputStream;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class AiTestChatDialogTest {
    private Activity activity;
    private ManualExecutor worker;
    private ManualExecutor historyWorker;
    private List<FakeSession> sessions;
    private AiTestChatDialog dialog;
    private View root;
    private EditText input;
    private ImageButton send;

    @Before
    public void openDialog() throws Exception {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        sessions = new ArrayList<>();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject()
                .put("id", "test-dialog").put("name", "测试模型")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "test-model"));
        dialog = new AiTestChatDialog(activity, config, () -> {
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }, worker, historyWorker);
        dialog.show();
        root = dialog.getWindow().getDecorView().findViewWithTag("ai.chat");
        input = root.findViewWithTag("ai.chat.input");
        send = root.findViewWithTag("ai.chat.send");
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
    }

    @After
    public void closeDialog() {
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test
    public void sendingDisplaysRolesAndPreservesTheNextDraftWhileReplyArrives() {
        assertFalse(send.isEnabled());
        assertNotNull(root.findViewWithTag("ai.chat.clear"));
        submit("第一条消息");
        assertEquals("", input.getText().toString());
        assertEquals("停止请求", send.getContentDescription());
        assertEquals("第一条消息", text("ai.chat.user").getText().toString());
        assertEquals("正在回复…", text("ai.chat.answer").getText().toString());

        input.setText("下一条草稿");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Arrays.asList("第一条消息"), sessions.get(0).prompts);
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertEquals("下一条草稿", input.getText().toString());
        assertEquals("发送", send.getContentDescription());
        assertTrue(send.isEnabled());
        assertEquals("", text("ai.chat.state").getText().toString());
    }

    @Test public void workSlashMenuFillsCommandAndRendersItsResultWithoutCallingTheChatModel() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        input.setText("/");
        assertEquals(View.VISIBLE, root.findViewWithTag("ai.chat.skills").getVisibility());
        assertNotNull(root.findViewWithTag("ai.chat.skill.查询"));
        input.setText("/摘");
        assertNull(root.findViewWithTag("ai.chat.skill.查询"));
        assertTrue(root.findViewWithTag("ai.chat.skill.摘要").performClick());
        assertEquals("/摘要 ", input.getText().toString());
        assertEquals(input.length(), input.getSelectionStart());
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.skills").getVisibility());

        input.append("今天发布了测试版本。明天验证所有界面。");
        assertTrue(send.performClick());
        assertEquals("停止请求", send.getContentDescription());
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(text("ai.chat.answer").getText().toString().contains("今天发布了测试版本"));
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test public void committedDeletionRefreshesBothOpenMenusAndTheOldCommandCannotUseItsExecutor() throws Exception {
        SkillStore store = SkillStore.forContext(activity);
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(textSkillDraft("保留事实，使用分点格式。")));
        dialog.enableSkills(AiSkills.Entry.WORK);
        FakeSession contactSession = new FakeSession();
        ManualExecutor contactWorker = new ManualExecutor();
        ManualExecutor contactHistory = new ManualExecutor();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject()
                .put("id", "delete-skill-contact").put("name", "联系人")
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "test-model"));
        AiTestChatDialog contact = new AiTestChatDialog(activity, config, () -> contactSession,
                contactWorker, contactHistory, false, ChatImageLoader::thumbnail, true);
        try {
            contact.enableSkills(AiSkills.Entry.CONTACT);
            contact.show();
            contactHistory.runAllOnThread();
            ShadowLooper.idleMainLooper();
            View contactRoot = contact.getWindow().getDecorView().findViewWithTag("ai.chat");
            EditText contactInput = contactRoot.findViewWithTag("ai.chat.input");
            input.setText("/");
            contactInput.setText("/");
            String rowTag = "ai.chat.skill.周报润色";
            assertEquals(View.VISIBLE, root.findViewWithTag("ai.chat.skills").getVisibility());
            assertEquals(View.VISIBLE, contactRoot.findViewWithTag("ai.chat.skills").getVisibility());
            assertNotNull(root.findViewWithTag(rowTag));
            assertNotNull(contactRoot.findViewWithTag(rowTag));

            store.delete(store.detail("周报润色").deleteTarget());
            ShadowLooper.idleMainLooper();

            assertNull(root.findViewWithTag(rowTag));
            assertNull(contactRoot.findViewWithTag(rowTag));
            assertEquals("/", input.getText().toString());
            assertEquals("/", contactInput.getText().toString());
            assertNotNull(root.findViewWithTag("ai.chat.skill.摘要"));
            assertNotNull(contactRoot.findViewWithTag("ai.chat.skill.摘要"));

            submit("/周报润色 进度已更新");
            contactInput.setText("/周报润色 进度已更新");
            assertTrue(contactRoot.findViewWithTag("ai.chat.send").performClick());
            worker.runNext();
            contactWorker.runNext();
            ShadowLooper.idleMainLooper();
            assertTrue(sessions.get(0).skillInputs.isEmpty());
            assertTrue(contactSession.skillInputs.isEmpty());
        } finally {
            contact.dismiss();
            contactHistory.shutdownNow();
        }
    }

    @Test public void skillsDisabledPreservesSlashTextInTheOrdinaryChatSession() {
        submit("/摘要 普通消息");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList("/摘要 普通消息"), sessions.get(0).prompts);
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
    }

    @Test public void textSkillWithAnAttachmentKeepsTheDraftAndStartsNoRequest() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        dialog.addImage(image());
        submit("/摘要 一段文本");

        assertEquals("/摘要 一段文本", input.getText().toString());
        assertNotNull(findView("ai.chat.remove_image"));
        assertEquals(0, messageCount());
        assertEquals("发送", send.getContentDescription());
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test public void stoppingAQueuedSkillDoesNotRunTheModelOrReplaceTheNextDraft() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        submit("/查询 DA-1001");
        input.setText("下一条草稿");
        assertTrue(send.performClick());
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals("已停止", text("ai.chat.answer").getText().toString());
        assertEquals("下一条草稿", input.getText().toString());
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertEquals(0, sessions.get(0).cancelCount);
    }

    @Test public void stoppingBeforeTheUiReceivesACompletedSkillSuppressesItsResult() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        submit("/查询 DA-1001");
        worker.runNext();

        assertEquals("停止请求", send.getContentDescription());
        assertTrue(send.performClick());
        ShadowLooper.idleMainLooper();

        assertEquals("已停止", text("ai.chat.answer").getText().toString());
        assertEquals("/查询 DA-1001", input.getText().toString());
    }

    @Test public void imageSkillDisplaysAnAssistantBitmapAndReusesTheTranscriptImageBudget() throws Exception {
        installMockImageSkill();
        FakeSession model = new FakeSession();
        reopen(model, ChatImageLoader::thumbnail);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        dialog.enableSkills(AiSkills.Entry.WORK);
        ChatImage largeAttachment = new ChatImage("image/png", new byte[ChatImage.MAX_BYTES]);
        for (int index = 0; index < 4; index++) dialog.addImage(largeAttachment);
        submit("原有图片占满会话预算");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        submit("/生图 白猫");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(2, messageCount());
        ListView list = messages();
        View user = list.getAdapter().getView(0, null, list);
        View answer = list.getAdapter().getView(1, null, list);
        assertNull(user.findViewWithTag("ai.chat.image"));
        ImageView image = answer.findViewWithTag("ai.chat.image");
        assertNotNull(image);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(image.getDrawable());
        assertEquals(Collections.singletonList("原有图片占满会话预算"), model.prompts);
    }

    @Test public void anImageSkillRevokedWhileQueuedProducesNoImageOrRetry() throws Exception {
        SkillServices services = installMockImageSkill();
        dialog.enableSkills(AiSkills.Entry.WORK);
        submit("/生图 白猫");
        services.revoke(services.find(SkillServices.MOCK_IMAGE));
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(2, messageCount());
        ListView list = messages();
        View answer = list.getAdapter().getView(1, null, list);
        assertNull(answer.findViewWithTag("ai.chat.image"));
        assertEquals(View.GONE, answer.findViewWithTag("ai.chat.retry").getVisibility());
        assertEquals("", input.getText().toString());
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test public void clearingTheChatDropsAnImageResultQueuedForRendering() throws Exception {
        installMockImageSkill();
        dialog.enableSkills(AiSkills.Entry.WORK);
        submit("/生图 白猫");
        worker.runNext();
        assertTrue(root.findViewWithTag("ai.chat.clear").performClick());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(0, messageCount());
        assertNull(findView("ai.chat.image"));
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    private SkillServices installMockImageSkill() throws Exception {
        SkillServices services = SkillServices.forContext(activity);
        services.authorize(services.find(SkillServices.MOCK_IMAGE), 0);
        SkillStore.forContext(activity).installGenerated(SkillStore.mockDraft("生图"));
        return services;
    }

    @Test public void explicitImageCreationConfiguresItsOwnMockAndRendersFromPersistedSkill() throws Exception {
        dialog.enableSkills(AiSkills.Entry.WORK);
        sessions.get(0).skillReplies.add(imageSkillDraft());
        submit("帮我做一个名为「海报」的生图 Skill 并内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        SkillStore skills = SkillStore.forContext(activity);
        assertTrue(skills.list().isEmpty());
        SkillStore.Detail pending = skills.detail("海报");
        assertTrue(pending.pending());
        assertTrue(text("ai.chat.answer").getText().toString().contains("待配置草稿"));
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertEquals(Collections.singletonList(SkillAuthoring.DESIGN_INSTRUCTION), sessions.get(0).skillInstructions);
        assertTrue(findView("ai.chat.skill.settings.海报").performClick());
        AlertDialog settings = (AlertDialog) ShadowDialog.getLatestDialog();
        View details = settings.getWindow().getDecorView();
        TextView skillName = details.findViewWithTag("skills.detail.name");
        assertNotNull(skillName);
        assertEquals("/海报", skillName.getText().toString());

        Spinner service = details.findViewWithTag("skills.detail.adapter");
        service.setSelection(1);
        ShadowLooper.idleMainLooper();
        assertTrue(details.findViewWithTag("skills.detail.save").performClick());
        SkillStore.Detail saved = skills.detail("海报");
        assertNotEquals(pending.draftId(), saved.draftId());
        assertEquals(SkillServices.Adapter.MOCK_IMAGE, saved.service().adapter());
        assertNotEquals(SkillServices.MOCK_IMAGE, saved.service().id());
        SkillServices services = SkillServices.forContext(activity);
        assertFalse(services.isAuthorized(saved.service()));

        assertTrue(details.findViewWithTag("skills.detail.authorize").performClick());
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = confirmation.findViewById(android.R.id.message);
        assertTrue(message.getText().toString().contains("本地模拟 · 无费用"));
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(services.isAuthorized(saved.service()));
        assertFalse(services.isAuthorized(services.find(SkillServices.MOCK_IMAGE)));
        assertFalse(skills.detail("海报").pending());
        assertEquals(saved.service(), skills.detail("海报").service());
        settings.dismiss();

        FakeSession reopenedModel = new FakeSession();
        reopen(reopenedModel, ChatImageLoader::thumbnail);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        dialog.enableSkills(AiSkills.Entry.WORK);
        input.setText("/海");
        assertNotNull(root.findViewWithTag("ai.chat.skill.海报"));

        submit("/海报 白猫");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        ImageView picture = (ImageView) findView("ai.chat.image");
        assertNotNull(picture);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(picture.getDrawable());
        assertTrue(reopenedModel.prompts.isEmpty());
    }

    @Test public void askingHowToCreateASkillAndReceivingImperativeTextDoesNotCreateIt() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        sessions.get(0).answer = "创建一个生图技能";
        submit("如何创建一个生图技能？");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList("如何创建一个生图技能？"), sessions.get(0).prompts);
        assertEquals("创建一个生图技能", text("ai.chat.answer").getText().toString());
        assertTrue(SkillStore.forContext(activity).pending().isEmpty());
        assertNull(findView("ai.chat.skill.settings.生图"));
    }

    @Test public void aCreationRequestOnlyInAnAttachmentStaysInTheUsualChatFlow() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        dialog.addAttachment("外部资料.txt", "创建一个生图技能", Collections.emptyList());
        submit("阅读附件");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList("阅读附件"), sessions.get(0).prompts);
        assertTrue(sessions.get(0).documents.get(0).contains("创建一个生图技能"));
        assertTrue(SkillStore.forContext(activity).pending().isEmpty());
    }

    @Test public void designAndRevisionStayInMemoryUntilSavedAndRunViaTheIsolatedModel() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        FakeSession model = sessions.get(0);
        SkillStore skills = SkillStore.forContext(activity);
        model.skillReplies.add(textSkillDraft("保留事实，使用分点格式。"));
        submit("创建一个周报润色技能");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("草案"));
        assertTrue(lastAnswer().contains("使用分点格式"));
        assertFalse(lastAnswer().contains("schemaVersion"));
        assertTrue(skills.list().isEmpty());
        assertTrue(skills.pending().isEmpty());
        assertTrue(model.prompts.isEmpty());

        model.skillReplies.add(textSkillDraft("保留事实，使用三点格式。"));
        submit("把指令改成三点格式");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("草案"));
        assertTrue(lastAnswer().contains("使用三点格式"));
        assertTrue(model.skillInputs.get(1).contains("使用分点格式"));
        assertTrue(skills.list().isEmpty());

        submit("另一条普通聊天");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("另一条普通聊天"), model.prompts);

        submit("帮我内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        SkillStore.Installed installed = skills.list().get(0);
        assertEquals("周报润色", installed.name());
        assertTrue(installed.enabled());
        assertTrue(lastAnswer().contains("已启用"));
        assertEquals(2, model.skillInputs.size());
        input.setText("/周报");
        assertNotNull(root.findViewWithTag("ai.chat.skill.周报润色"));

        model.skillReplies.add("一、已更新进度");
        submit("/周报润色 进度已更新");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("一、已更新进度", lastAnswer());
        assertEquals(SkillAuthoring.TEXT_INSTRUCTION, model.skillInstructions.get(2));
        assertTrue(model.skillInputs.get(2).contains("使用三点格式"));
        assertTrue(model.skillInputs.get(2).contains("进度已更新"));
        assertFalse(model.skillInputs.get(2).contains("另一条普通聊天"));
        assertEquals(Collections.singletonList("另一条普通聊天"), model.prompts);
    }

    @Test public void invalidRevisionReportsFailureAndLeavesTheValidatedPreviewAvailableToSave() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        FakeSession model = sessions.get(0);
        model.skillReplies.add(textSkillDraft("保留事实，使用分点格式。"));
        submit("创建一个周报润色技能");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        model.skillReplies.add("{\"unexpected\":\"provider-only-content\"}");
        submit("把指令改成三点格式");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(lastAnswer().contains("失败"));
        assertFalse(lastAnswer().contains("provider-only-content"));
        assertTrue(SkillStore.forContext(activity).list().isEmpty());
        submit("帮我内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("周报润色", SkillStore.forContext(activity).list().get(0).name());
    }

    @Test public void credentialsInAnAuthoringRequestAreRejectedBeforeTranscriptOrModelAdmission() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        String typed = "创建一个 sk-abcdefghijklmnopqrstuvwx 技能";
        submit(typed);

        assertEquals(typed, input.getText().toString());
        assertEquals(0, messageCount());
        assertTrue(sessions.get(0).skillInputs.isEmpty());
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test public void clearingTheConversationDiscardsTheUnstoredAuthoringPreview() throws Exception {
        dialog.enableSkills(AiSkills.Entry.WORK);
        sessions.get(0).skillReplies.add(textSkillDraft("保留事实，使用分点格式。"));
        submit("创建一个周报润色技能");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("草案"));
        assertTrue(root.findViewWithTag("ai.chat.clear").performClick());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        submit("帮我内置");
        assertTrue(text("ai.chat.state").getText().toString().contains("没有可保存"));
        assertEquals(0, messageCount());
        assertTrue(SkillStore.forContext(activity).list().isEmpty());
        assertTrue(sessions.get(1).skillInputs.isEmpty());
    }

    @Test public void stoppingBeforeLocalCreationCommitsLeavesNoDraft() {
        dialog.enableSkills(AiSkills.Entry.WORK);
        sessions.get(0).skillReplies.add(imageSkillDraft());
        submit("帮我做一个名为「海报」的生图 Skill 并内置");
        worker.runNext();
        assertTrue(send.performClick());
        ShadowLooper.idleMainLooper();

        assertEquals("已停止", text("ai.chat.answer").getText().toString());
        assertTrue(SkillStore.forContext(activity).pending().isEmpty());
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test public void successfulAnswerDisplaysOnlyTheSearchResultsFromItsOwnTurn() throws Exception {
        FakeSession active = sessions.get(0);
        active.found = List.of(new DouyinSearch.Result("手势舞", List.of(new DouyinSearch.Item(
                "123", "手势舞教程", "作者甲", "https://www.douyin.com/video/123", ""))));
        submit("找手势舞教程");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNotNull(findView("ai.chat.video.card"));
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertTrue(active.found.isEmpty());
        root.findViewWithTag("ai.chat.clear").performClick();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        submit("不搜索的问题");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNull(findView("ai.chat.video.card"));
    }

    @Test public void failedAnswerNeverAttachesPartialSearchResults() {
        FakeSession active = sessions.get(0);
        active.found = List.of(new DouyinSearch.Result("手势舞", List.of(new DouyinSearch.Item(
                "123", "不应显示", "作者", "https://www.douyin.com/video/123", ""))));
        active.failure = new IllegalStateException("Provider unavailable");
        submit("找视频");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNull(findView("ai.chat.video.card"));
    }

    @Test public void streamedTextIsVisibleBeforeCompletionInOneReplyBubble() throws Exception {
        FakeSession active = sessions.get(0);
        Thread io = startStreaming(active, "边生成边显示");
        try {
            input.setText("保留下一条草稿");
            emitPartial(active, "第一段");
            assertEquals("正在回复…", text("ai.chat.answer").getText().toString());
            ShadowLooper.idleMainLooper();
            assertTrue("Provider has not completed", io.isAlive());
            assertEquals("第一段", text("ai.chat.answer").getText().toString());
            assertEquals(2, messageCount());
            assertEquals("停止请求", send.getContentDescription());
            emitPartial(active, "第一段，第二段");
            ShadowLooper.idleMainLooper();
            assertEquals("第一段，第二段", text("ai.chat.answer").getText().toString());
            assertEquals("保留下一条草稿", input.getText().toString());
            active.answer = "第一段，第二段，完成。";
        } finally {
            releaseStreaming(active, io);
        }
        assertEquals("第一段，第二段，完成。", text("ai.chat.answer").getText().toString());
        emitPartial(active, "不应覆盖完成文本");
        ShadowLooper.idleMainLooper();
        assertEquals("第一段，第二段，完成。", text("ai.chat.answer").getText().toString());
        assertEquals(2, messageCount());
    }

    @Test public void stoppingStreamRetainsPartialTextAndRejectsLateProgress() throws Exception {
        FakeSession active = sessions.get(0);
        Thread io = startStreaming(active, "中途停止");
        try {
            emitPartial(active, "已经生成的内容");
            ShadowLooper.idleMainLooper();
            assertTrue(send.performClick());
            emitPartial(active, "停止后的迟到内容");
        } finally {
            releaseStreaming(active, io);
        }
        String answer = text("ai.chat.answer").getText().toString();
        assertTrue(answer.startsWith("已经生成的内容"));
        assertTrue(answer.contains("已停止"));
        assertTrue(answer.contains("回复未完成"));
        assertFalse(answer.contains("迟到"));
        assertEquals("中途停止", input.getText().toString());
    }

    @Test public void clearingStreamRejectsQueuedProgressAndOldCompletion() throws Exception {
        FakeSession active = sessions.get(0);
        Thread io = startStreaming(active, "清空前的流式请求");
        try {
            emitPartial(active, "清空前排队的内容");
            root.findViewWithTag("ai.chat.clear").performClick();
            historyWorker.runAllOnThread();
        } finally {
            releaseStreaming(active, io);
        }
        assertEquals(0, messageCount());
        submit("新的请求");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        emitPartial(active, "旧请求的迟到内容");
        ShadowLooper.idleMainLooper();
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertEquals(2, messageCount());
    }

    @Test public void closingStreamRejectsQueuedProgressAndOldCompletion() throws Exception {
        FakeSession active = sessions.get(0);
        Thread io = startStreaming(active, "关闭前的流式请求");
        try {
            emitPartial(active, "尚未展示的内容");
            dialog.dismiss();
        } finally {
            releaseStreaming(active, io);
        }
        assertFalse(dialog.isShowing());
        assertEquals("正在回复…", text("ai.chat.answer").getText().toString());
    }

    @Test
    public void windowIsACenteredFloatingSurfaceWithMiuixLightStyling() {
        WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
        DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        assertTrue(bounds.width > 0 && bounds.width < metrics.widthPixels);
        assertTrue(bounds.height > 0 && bounds.height < metrics.heightPixels);
        assertEquals(Gravity.CENTER, bounds.gravity);
        assertTrue(bounds.dimAmount >= 0.4f);
        assertTrue(dialog.getWindow().getDecorView().findViewWithTag("ai.chat")
                .getBackground() instanceof GradientDrawable);
        GradientDrawable surface = (GradientDrawable) root.getBackground();
        assertEquals(20 * metrics.density, surface.getCornerRadius(), 0.5f);
        assertTrue(root.getElevation() > 0f);
    }

    @Test
    public void failureRestoresPromptAndRetryReusesTheOriginalMessagePair() {
        sessions.get(0).failure = new java.net.SocketTimeoutException();
        submit("重试这条消息");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals("重试这条消息", input.getText().toString());
        assertEquals("发送失败", text("ai.chat.state").getText().toString());
        assertTrue(text("ai.chat.answer").getText().toString().contains("超时"));
        assertEquals(2, messageCount());

        sessions.get(0).failure = null;
        assertTrue(findView("ai.chat.retry").performClick());
        assertEquals("", input.getText().toString());
        assertEquals(2, messageCount());
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Arrays.asList("重试这条消息", "重试这条消息"), sessions.get(0).prompts);
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertEquals(View.GONE, findView("ai.chat.retry").getVisibility());
    }

    @Test
    public void failureDoesNotReplaceAnAlreadyTypedNextDraft() {
        sessions.get(0).failure = new IllegalStateException("HTTP 503");
        submit("原消息");
        input.setText("新的草稿");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("新的草稿", input.getText().toString());
        assertNotNull(findView("ai.chat.retry"));
    }

    @Test
    public void clearCreatesFreshContextAndDiscardsAnAlreadyQueuedReply() throws Exception {
        submit("清空前的消息");
        worker.runNext();
        input.setText("保留的草稿");
        assertTrue(root.findViewWithTag("ai.chat.clear").performClick());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(2, sessions.size());
        assertEquals(1, sessions.get(0).cancelCount);
        assertEquals(0, messageCount());
        assertEquals("保留的草稿", input.getText().toString());
        assertEquals("", text("ai.chat.state").getText().toString());
        assertFalse(worker.isShutdown());

        submit("清空后的消息");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("清空前的消息"), sessions.get(0).prompts);
        assertEquals(Arrays.asList("清空后的消息"), sessions.get(1).prompts);
        assertEquals(2, messageCount());
    }

    @Test
    public void completedReplyWinsOverStopAndRemainsVisibleInConversation() {
        submit("停止的消息");
        worker.runNext();
        assertTrue(send.performClick());
        ShadowLooper.idleMainLooper();

        assertEquals(1, sessions.get(0).cancelCount);
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertEquals("", input.getText().toString());
        assertEquals(View.GONE, findView("ai.chat.retry").getVisibility());
    }

    @Test
    public void stopBeforeWorkerStartsSkipsProviderAndAllowsRetry() {
        submit("停止的消息");
        assertTrue(send.performClick());
        assertFalse(send.isEnabled());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertEquals("已停止", text("ai.chat.answer").getText().toString());
        assertEquals("停止的消息", input.getText().toString());
        assertFalse(worker.isShutdown());
        assertEquals(1, sessions.size());

        findView("ai.chat.retry").performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
        assertEquals(2, messageCount());
    }

    @Test
    public void imageOnlyMessageReachesSessionAndRemainsVisibleForFollowUp() {
        ChatImage image = image();
        dialog.addImage(image);
        assertTrue(send.isEnabled());
        assertEquals(View.VISIBLE, root.findViewWithTag("ai.chat.attachments").getVisibility());
        assertNotNull(findView("ai.chat.remove_image"));
        send.performClick();
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(""), sessions.get(0).prompts);
        assertEquals(image, sessions.get(0).images.get(0).get(0));
        assertNotNull(findView("ai.chat.image"));
        submit("图片中有什么？");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(1, sessions.size());
        assertEquals(0, sessions.get(0).images.get(1).size());
    }

    @Test public void selectingVideoKeepsOnePendingCardWithoutReadingFramesBeforeSend() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/7"));

        assertEquals("Selecting a video must not schedule decoder or file-reading work", 0, worker.tasks.size());
        assertTrue(media.uris.isEmpty());
        assertTrue("A selected video can be sent with an empty text draft", send.isEnabled());
        View card = root.findViewWithTag("ai.chat.pending_card");
        assertNotNull("The draft shows one pending video card", card);
        assertNull("Unsent video frames must not be expanded into the draft", card.findViewWithTag("ai.chat.image"));
        android.widget.HorizontalScrollView strip = root.findViewWithTag("ai.chat.attachments");
        assertEquals(View.VISIBLE, strip.getVisibility());
        assertEquals(1, ((LinearLayout) strip.getChildAt(0)).getChildCount());
    }

    @Test public void sendingVideoReadsOnlyAfterSendAndPreservesTheCapturedTextAndNextDraft() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        android.net.Uri selected = android.net.Uri.parse("content://media/external/video/media/8");
        selectVideo(selected);
        submit("分析这个视频");
        assertTrue("Enqueuing send still leaves media work on the worker", media.uris.isEmpty());
        assertEquals("", input.getText().toString());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
        input.setText("下一条独立草稿");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList(selected), media.uris);
        assertEquals(Collections.singletonList(AiTestChatDialog.AttachmentKind.VIDEO), media.kinds);
        assertEquals(Collections.singletonList(4), media.budgets);
        assertFalse("Video reading belongs on the worker", media.readOnMain);
        assertEquals(Collections.singletonList("分析这个视频"), sessions.get(0).prompts);
        assertEquals(media.result.images, sessions.get(0).images.get(0));
        assertTrue(sessions.get(0).documents.get(0).contains(media.result.text));
        assertEquals("下一条独立草稿", input.getText().toString());
        assertEquals(2, messageCount());
        View sent = messages().getAdapter().getView(0, null, messages());
        assertEquals("Prepared frames must update the sent message row",
                media.result.images.size(), ((LinearLayout) sent.findViewWithTag("ai.chat.message.images")).getChildCount());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
    }

    @Test public void sendingVideoWithoutWordsReadsAndSendsTheSelectedVideo() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/9"));
        assertTrue(send.performClick());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, media.uris.size());
        assertEquals(Collections.singletonList(""), sessions.get(0).prompts);
        assertEquals(media.result.images, sessions.get(0).images.get(0));
        assertTrue(sessions.get(0).documents.get(0).contains(media.result.text));
        assertEquals(2, messageCount());
    }

    @Test public void removingOrClearingPendingVideoNeverReadsItsContents() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/10"));
        View remove = root.findViewWithTag("ai.chat.remove_pending");
        assertNotNull(remove);
        assertTrue(remove.performClick());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
        assertFalse(send.isEnabled());
        assertEquals(0, worker.tasks.size());

        selectVideo(android.net.Uri.parse("content://media/external/video/media/11"));
        root.findViewWithTag("ai.chat.clear").performClick();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        worker.runAllOnThread();
        assertTrue(media.uris.isEmpty());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
    }

    @Test public void stoppingQueuedVideoSendSkipsReadingAndKeepsOnePendingCardForRetry() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/12"));
        submit("先别读这个视频");
        assertTrue(send.performClick());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(media.uris.isEmpty());
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertEquals("先别读这个视频", input.getText().toString());
        assertNotNull(root.findViewWithTag("ai.chat.pending_card"));
        assertEquals(2, messageCount());
    }

    @Test public void closingBeforeFailedMediaCompletionCannotRestoreThePendingVideo() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        media.failure = new java.io.IOException("Video read failed");
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/13"));
        submit("关闭前的内容");
        worker.runAllOnThread();
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals(1, media.uris.size());
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertFalse(dialog.isShowing());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
        assertEquals("", input.getText().toString());
        assertTrue(worker.isShutdown());
    }

    @Test public void failedVideoReadRestoresOneCardAndRetriesWithoutDuplicateMessageRows() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        media.failure = new java.io.IOException("Video read failed");
        reopenWithMediaLoader(media);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/14"));
        submit("重读视频");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, media.uris.size());
        assertTrue(sessions.get(0).prompts.isEmpty());
        assertNotNull(root.findViewWithTag("ai.chat.pending_card"));
        assertEquals(2, messageCount());
        media.failure = null;
        assertTrue(findView("ai.chat.retry").performClick());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(2, media.uris.size());
        assertEquals(Collections.singletonList("重读视频"), sessions.get(0).prompts);
        assertEquals(2, messageCount());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
    }

    @Test public void providerFailureRetriesPreparedVideoWithoutReadingItAgain() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        reopenWithMediaLoader(media);
        sessions.get(0).failure = new java.net.SocketTimeoutException();
        selectVideo(android.net.Uri.parse("content://media/external/video/media/15"));
        submit("沿用已经解析的画面");
        input.setText("保留下一条草稿");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, media.uris.size());
        assertNotNull(root.findViewWithTag("ai.chat.pending_card"));
        assertNull(root.findViewWithTag("ai.chat.remove_image"));
        assertEquals("保留下一条草稿", input.getText().toString());
        sessions.get(0).failure = null;
        assertTrue(findView("ai.chat.retry").performClick());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals("Provider retry reuses prepared video frames", 1, media.uris.size());
        assertEquals(Arrays.asList("沿用已经解析的画面", "沿用已经解析的画面"), sessions.get(0).prompts);
        assertEquals(media.result.images, sessions.get(0).images.get(1));
        assertTrue(sessions.get(0).documents.get(1).contains(media.result.text));
        assertEquals(2, messageCount());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
        assertEquals("保留下一条草稿", input.getText().toString());
    }

    @Test public void videoReservesOneImageSlotForAnOpaquePdfSelectedAfterIt() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        media.budgetSizedResults = true;
        reopenWithMediaLoader(media);
        PdfMimeProvider provider = new PdfMimeProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "opaque-pdf";
        provider.attachInfo(activity, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(info.authority, provider);
        selectVideo(android.net.Uri.parse("content://media/external/video/media/16"));
        selectMedia(android.net.Uri.parse("content://opaque-pdf/items/opaque-token"), AiTestChatDialog.AttachmentKind.FILE);
        assertTrue(media.uris.isEmpty());
        assertEquals("Selection must not query PDF metadata before Send", 0, provider.typeCalls);
        submit("同时分析视频和 PDF");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(Arrays.asList(3, 1), media.budgets);
        assertEquals(Arrays.asList(AiTestChatDialog.AttachmentKind.VIDEO, AiTestChatDialog.AttachmentKind.FILE), media.kinds);
        assertTrue(provider.typeCalls > 0);
        assertFalse(provider.typeOnMain);
        assertEquals(Collections.singletonList("同时分析视频和 PDF"), sessions.get(0).prompts);
        assertEquals(4, sessions.get(0).images.get(0).size());
        assertTrue(sessions.get(0).documents.get(0).contains("PDF 页面"));
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
    }

    @Test public void addingAnImageAfterProviderFailureReReadsVideoWithTheReducedBudget() throws Exception {
        FakeMediaLoader media = new FakeMediaLoader();
        media.budgetSizedResults = true;
        reopenWithMediaLoader(media);
        sessions.get(0).failure = new java.net.SocketTimeoutException();
        selectVideo(android.net.Uri.parse("content://media/external/video/media/17"));
        submit("视频画面与新图片一起分析");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(4), media.budgets);
        assertEquals(4, sessions.get(0).images.get(0).size());

        ChatImage photo = image();
        dialog.addImage(photo);
        sessions.get(0).failure = null;
        assertTrue(send.performClick());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(4, 3), media.budgets);
        assertEquals(2, media.uris.size());
        assertEquals(Arrays.asList("视频画面与新图片一起分析", "视频画面与新图片一起分析"), sessions.get(0).prompts);
        assertEquals(4, sessions.get(0).images.get(1).size());
        assertSame(photo, sessions.get(0).images.get(1).get(0));
        View latest = messages().getAdapter().getView(messageCount() - 1, null, messages());
        assertEquals("模型回复", ((TextView) latest.findViewWithTag("ai.chat.answer")).getText().toString());
        assertNull(root.findViewWithTag("ai.chat.pending_card"));
    }

    @Test
    public void failedImageTurnRestoresAttachmentsAndRetriesWithoutDuplicateBubbles() {
        ChatImage image = image();
        dialog.addImage(image);
        sessions.get(0).failure = new java.net.SocketTimeoutException();
        submit("识别图片");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("识别图片", input.getText().toString());
        assertEquals(View.VISIBLE, root.findViewWithTag("ai.chat.attachments").getVisibility());
        sessions.get(0).failure = null;
        findView("ai.chat.retry").performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertEquals(image, sessions.get(0).images.get(1).get(0));
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
    }

    @Test public void documentOnlyMessagesKeepFullTextSeparateFromInputAndRestoreItForRetry() {
        String document = "文档正文\n".repeat(1000);
        dialog.addDocument("偏好.txt", document);
        assertEquals("", input.getText().toString());
        assertNotNull(root.findViewWithTag("ai.chat.document_name"));
        assertTrue(send.isEnabled());
        sessions.get(0).failure = new java.net.SocketTimeoutException();
        send.performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("", sessions.get(0).prompts.get(0));
        assertTrue(sessions.get(0).documents.get(0).contains(document));
        assertNotNull(findView("ai.chat.remove_document"));
        sessions.get(0).failure = null;
        findView("ai.chat.retry").performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertTrue(sessions.get(0).documents.get(1).contains(document));
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
        assertNotNull(findView("ai.chat.document"));
    }

    @Test public void documentRemovalClearAndOversizedAttachmentKeepDraftStateConsistent() {
        dialog.addDocument("笔记.txt", "普通偏好");
        findView("ai.chat.remove_document").performClick();
        assertFalse(send.isEnabled());
        dialog.addDocument("笔记.txt", "普通偏好");
        try { dialog.addDocument("过长.txt", "x".repeat(32769)); fail("Oversized document must fail"); }
        catch (IllegalArgumentException expected) { }
        assertEquals("笔记.txt", text("ai.chat.document_name").getText().toString());
        root.findViewWithTag("ai.chat.clear").performClick();
        assertFalse(send.isEnabled());
        assertNull(findView("ai.chat.remove_document"));
    }

    @Test public void removingVisualFileAlsoRemovesItsPagesAndSamplingDescription() {
        ChatImage page = new ChatImage("image/png", new byte[]{1, 2, 3});
        dialog.addAttachment("视频.mp4", "仅提供抽样画面，不包含音频。", Collections.singletonList(page));
        assertTrue(send.isEnabled());
        findView("ai.chat.remove_document").performClick();
        assertFalse(send.isEnabled());
        assertNull(findView("ai.chat.remove_image"));

        dialog.addAttachment("报告.pdf", "第 1 页", Collections.singletonList(page));
        findView("ai.chat.remove_image").performClick();
        assertFalse(send.isEnabled());
        assertNull(findView("ai.chat.remove_document"));
    }

    @Test public void visualFileDescriptionTracksItsImagePositionsAfterRemovingEarlierAttachments() {
        ChatImage photo = image();
        ChatImage frame = image();
        dialog.addImage(photo);
        dialog.addAttachment("视频.mp4", "抽样画面，不包含音频。", Collections.singletonList(frame));
        findView("ai.chat.remove_image").performClick();
        send.performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(frame), sessions.get(0).images.get(0));
        assertTrue(sessions.get(0).documents.get(0).contains("从 1 开始）：1。"));
        assertTrue(sessions.get(0).documents.get(0).contains("抽样画面，不包含音频。"));
    }

    @Test public void messageContactUsesItsOwnIdentityAndExistingAttachmentComposer() throws Exception {
        dialog.dismiss();
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject()
                .put("id", "contact-dialog").put("name", "模型配置")
                .put("messageContactEnabled", true).put("messageContactName", "小助手")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "test-model"));
        dialog = new AiTestChatDialog(activity, config, () -> {
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }, worker, historyWorker, false, image -> null, true);
        dialog.show();
        root = dialog.getWindow().getDecorView().findViewWithTag("ai.chat");
        input = root.findViewWithTag("ai.chat.input");
        send = root.findViewWithTag("ai.chat.send");
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals("小助手", text("ai.chat.title").getText().toString());
        assertNotNull(root.findViewWithTag("ai.chat.contact_avatar"));
        assertNotNull(root.findViewWithTag("ai.chat.attach"));
        final boolean[] edited = {false};
        dialog.setContactEditAction(() -> edited[0] = true);
        root.findViewWithTag("ai.chat.contact_edit").performClick();
        assertTrue(edited[0]);
        submit("你好");
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
    }

    @Test
    public void imageLimitRemovalAndClearUpdateSendAvailability() {
        for (int i = 0; i < 5; i++) dialog.addImage(image());
        assertTrue(root.findViewWithTag("ai.chat.attach").isEnabled());
        findView("ai.chat.remove_image").performClick();
        assertTrue(root.findViewWithTag("ai.chat.attach").isEnabled());
        root.findViewWithTag("ai.chat.clear").performClick();
        assertFalse(send.isEnabled());
        assertEquals(View.GONE, root.findViewWithTag("ai.chat.attachments").getVisibility());
    }

    @Test
    public void transcriptPrunesOldTurnsAfterItsBound() {
        for (int i = 0; i < 105; i++) {
            submit("消息 " + i);
            worker.runNext();
            ShadowLooper.idleMainLooper();
        }
        assertEquals(200, messageCount());
        assertTrue(messages().getChildCount() < messageCount());
        assertEquals("消息 5", text("ai.chat.user").getText().toString());
    }

    @Test public void restoringSavedTurnsReadsAndDecodesOnWorkerBeforeRenderingTextAndAttachments() throws Exception {
        ChatImage savedImage = image();
        FakeSession saved = new FakeSession();
        saved.saved = Collections.singletonList(new TestChatMemory.Turn("历史问题", "附件文本",
                Collections.singletonList(savedImage), "历史回复"));
        List<Boolean> decodeThreads = new ArrayList<>();
        reopen(saved, image -> {
            decodeThreads.add(Looper.myLooper() == Looper.getMainLooper());
            return ChatImageLoader.thumbnail(image);
        });
        assertEquals(0, saved.savedCalls);
        assertEquals(0, messageCount());

        historyWorker.runAllOnThread();
        assertEquals(1, saved.savedCalls);
        assertFalse(saved.readOnMain);
        assertEquals(Collections.singletonList(false), decodeThreads);
        assertEquals(0, messageCount());
        ShadowLooper.idleMainLooper();

        assertEquals("历史问题", text("ai.chat.user").getText().toString());
        assertEquals("历史回复", text("ai.chat.answer").getText().toString());
        assertNotNull(findView("ai.chat.document"));
        assertNotNull(((ImageView) findView("ai.chat.image")).getDrawable());
    }

    @Test public void clearingSavedConversationDiscardsAlreadyPostedRestoreAndClearsOffMain() throws Exception {
        FakeSession saved = new FakeSession();
        saved.saved = Collections.singletonList(new TestChatMemory.Turn("已清空的问题", "",
                Collections.singletonList(image()), "旧回复"));
        reopen(saved, ChatImageLoader::thumbnail);
        historyWorker.runAllOnThread();
        root.findViewWithTag("ai.chat.clear").performClick();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertEquals(1, saved.clearCalls);
        assertFalse(saved.clearOnMain);
        assertFalse(send.isEnabled());
    }

    @Test public void closingSavedConversationDiscardsAlreadyPostedRestoreAndThumbnail() throws Exception {
        FakeSession saved = new FakeSession();
        saved.saved = Collections.singletonList(new TestChatMemory.Turn("关闭前的历史", "",
                Collections.singletonList(image()), "旧回复"));
        List<Bitmap> bitmaps = new ArrayList<>();
        reopen(saved, image -> {
            Bitmap bitmap = ChatImageLoader.thumbnail(image);
            bitmaps.add(bitmap);
            return bitmap;
        });
        historyWorker.runAllOnThread();
        dialog.dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertEquals(1, bitmaps.size());
        assertTrue(bitmaps.get(0).isRecycled());
    }

    @Test public void restoredConversationKeepsItsBoundAndTextWhenThumbnailFails() throws Exception {
        FakeSession saved = new FakeSession();
        saved.saved = new ArrayList<>();
        for (int index = 0; index < 105; index++) saved.saved.add(new TestChatMemory.Turn("历史 " + index,
                "", Collections.singletonList(image()), "回复 " + index));
        reopen(saved, image -> { throw new IllegalArgumentException("Bad historical image"); });
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(200, messageCount());
        assertEquals("历史 5", text("ai.chat.user").getText().toString());
        assertEquals("回复 5", text("ai.chat.answer").getText().toString());
    }

    @Test public void sendingDuringRestoreRejectsTheOlderHistoryCompletion() throws Exception {
        FakeSession saved = new FakeSession();
        saved.saved = Collections.singletonList(new TestChatMemory.Turn("旧历史", "",
                Collections.emptyList(), "旧回复"));
        reopen(saved, ChatImageLoader::thumbnail);
        historyWorker.runAllOnThread();
        submit("新消息");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertEquals("新消息", text("ai.chat.user").getText().toString());
        assertEquals("模型回复", text("ai.chat.answer").getText().toString());
    }

    @Test
    public void closingCancelsSessionShutsWorkerAndDiscardsLateReply() {
        submit("关闭前的消息");
        worker.runNext();
        TextView pending = text("ai.chat.answer");
        root.findViewWithTag("ai.chat.close").performClick();
        ShadowLooper.idleMainLooper();

        assertFalse(dialog.isShowing());
        assertTrue(worker.isShutdown());
        assertEquals(1, sessions.get(0).cancelCount);
        assertEquals("正在回复…", pending.getText().toString());
        dialog.dismiss();
        assertEquals(1, sessions.get(0).cancelCount);
    }

    @Test
    public void cancellingDialogAlsoDisposesQueuedWork() {
        submit("尚未发送到服务端");
        dialog.cancel();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertTrue(worker.isShutdown());
        assertEquals(1, sessions.get(0).cancelCount);
        assertTrue(sessions.get(0).prompts.isEmpty());
    }

    @Test
    @Config(qualifiers = "w296dp-h560dp-mdpi")
    public void narrowDialogKeepsComposerOutsideTheScrollingTranscript() {
        assertBoundedLayout();
    }

    @Test
    @Config(qualifiers = "w296dp-h560dp-mdpi")
    public void keyboardInsetsKeepTheFloatingChatInsideTheAvailableHostArea() {
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 20))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 240)).build();
        input.setText("键盘打开时保留草稿");
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(insets);
        WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
        assertEquals(264, bounds.height);
        assertEquals("键盘打开时保留草稿", input.getText().toString());
        assertTrue(bounds.width < activity.getResources().getDisplayMetrics().widthPixels);
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-mdpi")
    public void landscapeDialogKeepsComposerAndMessagesVisible() {
        assertBoundedLayout();
    }

    private void assertBoundedLayout() {
        submit("这是比较长的消息内容。".repeat(30));
        sessions.get(0).answer = "这是一段较长的回复，用于检查文字换行。".repeat(40);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        dialog.addImage(image());
        input.setText("尚未发送的多行草稿。\n".repeat(10));
        WindowManager.LayoutParams bounds = dialog.getWindow().getAttributes();
        root.measure(View.MeasureSpec.makeMeasureSpec(bounds.width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(bounds.height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, bounds.width, bounds.height);

        ListView scroll = messages();
        View composer = (View) input.getParent();
        assertTrue("scroll height=" + scroll.getHeight() + ", bounds=" + bounds.width + "x"
                + bounds.height + ", composer=" + composer.getHeight() + ", input="
                + input.getHeight(), scroll.getHeight() > 0);
        assertTrue(scroll.getBottom() <= composer.getTop());
        assertTrue(composer.getBottom() <= root.getHeight());
        assertTrue(input.getRight() <= send.getLeft());
        assertTrue(send.getRight() <= composer.getWidth());
        assertTrue(input.getWidth() >= 100);
        assertEquals(48, send.getWidth());
        assertEquals(48, send.getHeight());
        ListView list = messages();
        View row = list.getAdapter().getView(list.getAdapter().getCount() - 1, null, list);
        TextView answer = row.findViewWithTag("ai.chat.answer");
        answer.measure(View.MeasureSpec.makeMeasureSpec(list.getWidth(), View.MeasureSpec.AT_MOST),
                View.MeasureSpec.makeMeasureSpec(root.getHeight(), View.MeasureSpec.AT_MOST));
        answer.layout(0, 0, answer.getMeasuredWidth(), answer.getMeasuredHeight());
        assertNotNull(answer.getLayout());
        assertTrue(answer.getLayout().getLineCount() > 1);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE,
                bounds.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        renderPreview(bounds.width, bounds.height);
    }

    private void renderPreview(int width, int height) {
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        java.io.File folder = new java.io.File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        root.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(
                new java.io.File(folder, "chat-" + width + "x" + height + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private void submit(String prompt) {
        input.setText(prompt);
        assertTrue(send.isEnabled());
        assertTrue(send.performClick());
    }

    private Thread startStreaming(FakeSession active, String prompt) throws Exception {
        active.streamStarted = new CountDownLatch(1);
        active.streamContinue = new CountDownLatch(1);
        submit(prompt);
        Thread io = new Thread(worker::runNext, "test-chat-stream");
        io.start();
        assertTrue("Provider must enter streaming", active.streamStarted.await(5, TimeUnit.SECONDS));
        return io;
    }

    private static void emitPartial(FakeSession active, String text) throws Exception {
        Thread io = new Thread(() -> active.onPartial.accept(text), "test-chat-chunk");
        io.start();
        io.join(5000);
        assertFalse("Chunk delivery must finish", io.isAlive());
    }

    private static void releaseStreaming(FakeSession active, Thread io) throws Exception {
        active.streamContinue.countDown();
        io.join(5000);
        assertFalse("Provider must finish", io.isAlive());
        ShadowLooper.idleMainLooper();
    }

    private void selectVideo(android.net.Uri uri) throws Exception {
        selectMedia(uri, AiTestChatDialog.AttachmentKind.VIDEO);
    }

    private void selectMedia(android.net.Uri uri, AiTestChatDialog.AttachmentKind kind) throws Exception {
        java.lang.reflect.Method selection = AiTestChatDialog.class.getDeclaredMethod("loadMedia",
                android.net.Uri.class, AiTestChatDialog.AttachmentKind.class);
        selection.setAccessible(true);
        selection.invoke(dialog, uri, kind);
    }

    private void reopenWithMediaLoader(FakeMediaLoader media) throws Exception {
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        sessions = new ArrayList<>();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "pending-video")
                .put("name", "测试模型").put("apiEndpoint", "https://example.com/v1").put("modelName", "vision"));
        dialog = new AiTestChatDialog(activity, config, () -> {
            FakeSession session = new FakeSession();
            sessions.add(session);
            return session;
        }, worker, historyWorker, false, image -> null, false, null, media);
        dialog.show();
        root = dialog.getWindow().getDecorView().findViewWithTag("ai.chat");
        input = root.findViewWithTag("ai.chat.input");
        send = root.findViewWithTag("ai.chat.send");
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
    }

    private void reopen(FakeSession saved, AiTestChatDialog.ThumbnailLoader thumbnails) throws Exception {
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        sessions = new ArrayList<>();
        sessions.add(saved);
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject()
                .put("id", "restore-test").put("name", "恢复模型")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "restore-model"));
        dialog = new AiTestChatDialog(activity, config, () -> {
            if (sessions.isEmpty()) throw new AssertionError("Unexpected session creation");
            return sessions.get(0);
        }, worker, historyWorker, false, thumbnails);
        dialog.show();
        root = dialog.getWindow().getDecorView().findViewWithTag("ai.chat");
        input = root.findViewWithTag("ai.chat.input");
        send = root.findViewWithTag("ai.chat.send");
    }

    private TextView text(String tag) {
        View direct = findView(tag);
        TextView found = direct instanceof TextView ? (TextView) direct : null;
        if (found != null) return found;
        return null;
    }

    private View findView(String tag) {
        if (tag.startsWith("ai.chat.user") || tag.startsWith("ai.chat.answer")
                || tag.startsWith("ai.chat.retry") || tag.startsWith("ai.chat.document")
                || tag.startsWith("ai.chat.image")) {
            ListView list = messages();
            for (int index = 0; index < list.getAdapter().getCount(); index++) {
                View row = list.getAdapter().getView(index, null, list);
                View found = row.findViewWithTag(tag);
                if (found != null && found.getVisibility() != View.GONE) return found;
            }
        }
        View found = root.findViewWithTag(tag);
        if (found != null) return found;
        ListView list = messages();
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            View row = list.getAdapter().getView(index, null, list);
            found = row.findViewWithTag(tag);
            if (found != null) return found;
        }
        return null;
    }

    private ListView messages() { return root.findViewWithTag("ai.chat.messages"); }
    private int messageCount() { return messages().getAdapter().getCount(); }
    private String lastAnswer() {
        ListView list = messages();
        View row = list.getAdapter().getView(list.getCount() - 1, null, list);
        return ((TextView) row.findViewWithTag("ai.chat.answer")).getText().toString();
    }

    private static String imageSkillDraft() {
        return """
                {"schemaVersion":3,"name":"海报","description":"生成海报图","documentation":"使用已授权图片服务。",
                 "execution":{"kind":"service","outputType":"image","maxInput":4000},
                 "trial":{"input":"一只猫","expectedType":"image"}}
                """;
    }

    private static String textSkillDraft(String instruction) {
        return """
                {"schemaVersion":3,"name":"周报润色","description":"修订周报","documentation":"输入待修订文本。",
                 "execution":{"kind":"text_model","outputType":"text","maxInput":2000,"instruction":"%s"},
                 "trial":{"input":"进度一","expectedType":"text"}}
                """.formatted(instruction);
    }

    private static final class FakeSession implements AiTestChatDialog.Session {
        final List<String> prompts = new ArrayList<>();
        final List<String> skillInstructions = new ArrayList<>();
        final List<String> skillInputs = new ArrayList<>();
        final Queue<String> skillReplies = new ArrayDeque<>();
        final List<List<ChatImage>> images = new ArrayList<>();
        final List<String> documents = new ArrayList<>();
        String answer = "模型回复";
        Exception failure;
        int cancelCount;
        List<TestChatMemory.Turn> saved = Collections.emptyList();
        List<DouyinSearch.Result> found = Collections.emptyList();
        int savedCalls;
        int clearCalls;
        boolean readOnMain;
        boolean clearOnMain;
        Consumer<String> onPartial;
        CountDownLatch streamStarted;
        CountDownLatch streamContinue;

        @Override public String reply(String message, List<ChatImage> attachments) throws Exception {
            images.add(new ArrayList<>(attachments));
            prompts.add(message);
            if (failure != null) throw failure;
            return answer;
        }

        @Override public String skillModel(String system, String input) {
            skillInstructions.add(system);
            skillInputs.add(input);
            if (skillReplies.isEmpty()) throw new AssertionError("Unexpected isolated skill model call");
            return skillReplies.remove();
        }

        @Override public String reply(String message, List<ChatImage> attachments, String document) throws Exception {
            documents.add(document);
            return reply(message, attachments);
        }

        @Override public String replyStreaming(String message, List<ChatImage> attachments, String document,
                                               Consumer<String> progress) throws Exception {
            onPartial = progress;
            if (streamStarted != null) streamStarted.countDown();
            if (streamContinue != null && !streamContinue.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Streaming test was not released");
            }
            return reply(message, attachments, document);
        }

        @Override public void cancel() {
            cancelCount++;
            if (streamContinue != null) {
                failure = new java.util.concurrent.CancellationException();
                streamContinue.countDown();
            }
        }

        @Override public List<TestChatMemory.Turn> savedTurns() {
            savedCalls++;
            readOnMain = Looper.myLooper() == Looper.getMainLooper();
            return saved;
        }

        @Override public List<DouyinSearch.Result> searchResults() {
            List<DouyinSearch.Result> result = found;
            found = Collections.emptyList();
            return result;
        }

        @Override public void clear() {
            clearCalls++;
            clearOnMain = Looper.myLooper() == Looper.getMainLooper();
            saved = Collections.emptyList();
        }
    }

    private static ChatImage image() {
        Bitmap bitmap = Bitmap.createBitmap(96, 64, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.rgb(48, 155, 112));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
        bitmap.recycle();
        return new ChatImage("image/png", bytes.toByteArray());
    }

    private static final class FakeMediaLoader implements AiTestChatDialog.MediaLoader {
        final List<android.net.Uri> uris = new ArrayList<>();
        final List<AiTestChatDialog.AttachmentKind> kinds = new ArrayList<>();
        final List<Integer> budgets = new ArrayList<>();
        final AiTestChatDialog.LoadedMedia result = new AiTestChatDialog.LoadedMedia("clip.mp4",
                "仅提供视频抽样画面，不包含音频。", Arrays.asList(image(), image()));
        Exception failure;
        boolean readOnMain;
        boolean budgetSizedResults;

        @Override public AiTestChatDialog.LoadedMedia load(android.content.Context context, android.net.Uri uri,
                AiTestChatDialog.AttachmentKind kind, int imageBudget) throws Exception {
            uris.add(uri);
            kinds.add(kind);
            budgets.add(imageBudget);
            readOnMain = Looper.myLooper() == Looper.getMainLooper();
            if (failure != null) throw failure;
            if (budgetSizedResults) {
                List<ChatImage> frames = new ArrayList<>();
                int count = kind == AiTestChatDialog.AttachmentKind.VIDEO ? imageBudget : 1;
                for (int index = 0; index < count; index++) frames.add(image());
                return new AiTestChatDialog.LoadedMedia(kind == AiTestChatDialog.AttachmentKind.VIDEO ? "clip.mp4" : "opaque.pdf",
                        kind == AiTestChatDialog.AttachmentKind.VIDEO ? result.text : "PDF 页面", frames);
            }
            return result;
        }
    }

    private static final class PdfMimeProvider extends android.content.ContentProvider {
        int typeCalls;
        boolean typeOnMain;
        @Override public boolean onCreate() { return true; }
        @Override public String getType(android.net.Uri uri) {
            typeCalls++;
            typeOnMain = Looper.myLooper() == Looper.getMainLooper();
            return "application/pdf";
        }
        @Override public android.database.Cursor query(android.net.Uri uri, String[] projection, String selection,
                String[] selectionArgs, String sortOrder) { throw new AssertionError("Media body must only be read by the injected worker"); }
        @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(android.net.Uri uri, String where, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(android.net.Uri uri, android.content.ContentValues values, String where, String[] args) { throw new UnsupportedOperationException(); }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        void runNext() {
            Runnable task = tasks.poll();
            if (task != null) task.run();
        }

        void runAllOnThread() throws InterruptedException {
            Runnable task;
            while ((task = tasks.poll()) != null) {
                Runnable next = task;
                Thread thread = new Thread(next, "test-history-worker");
                thread.start();
                thread.join(5000);
                if (thread.isAlive()) throw new AssertionError("Worker task did not finish");
            }
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
