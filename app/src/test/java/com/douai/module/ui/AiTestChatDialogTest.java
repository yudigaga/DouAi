package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Matrix;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
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
import com.douai.module.ai.skills.SkillManifest;
import com.douai.module.ai.skills.SkillCustomServiceTest;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillSelection;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.ai.skills.SkillVideoJobs;
import com.douai.module.ai.skills.SkillVideoJobsTest;
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
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

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

    @Test public void completedSkillHistoryRestoresAuthoringTextAndAssistantImageWithoutReplay() throws Exception {
        installMockImageSkill();
        FakeSession first = new FakeSession();
        first.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(first, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        String creation = "帮我做一个周报 Skill 并内置";
        String textCommand = "/周报润色 进度已更新";
        String imageCommand = "/生图 白猫";
        first.skillReplies.add(textSkillDraft("保留事实，使用分点格式。"));
        submit(creation);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("已创建技能 /周报润色"));

        first.skillReplies.add("一、已更新进度");
        submit(textCommand);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals("一、已更新进度", lastAnswer());

        submit(imageCommand);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(6, messageCount());
        assertNotNull(messageRow(5).findViewWithTag("ai.chat.image"));
        assertTrue(first.prompts.isEmpty());

        FakeSession reopened = new FakeSession();
        reopened.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(reopened, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(6, messageCount());
        assertEquals(creation, bubble(0, "ai.chat.user"));
        assertTrue(bubble(1, "ai.chat.answer").contains("已创建技能 /周报润色"));
        assertNotNull(messageRow(1).findViewWithTag("ai.chat.skill.settings.周报润色"));
        assertEquals(textCommand, bubble(2, "ai.chat.user"));
        assertEquals("一、已更新进度", bubble(3, "ai.chat.answer"));
        assertEquals(imageCommand, bubble(4, "ai.chat.user"));
        assertNull(messageRow(4).findViewWithTag("ai.chat.image"));
        ImageView assistantImage = messageRow(5).findViewWithTag("ai.chat.image");
        assertNotNull(assistantImage);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(assistantImage.getDrawable());
        assertTrue(reopened.prompts.isEmpty());
        assertTrue(reopened.skillInputs.isEmpty());

        assertTrue(root.findViewWithTag("ai.chat.clear").performClick());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertTrue(reopened.transcriptMemory.readTranscript().isEmpty());
    }

    @Test public void generatedImageIsNotShownUntilItsHistoryTurnIsWritten() throws Exception {
        FakeSession model = openMockImageHistory();

        String prompt = "/生图 白猫";
        submit(prompt);
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(2, messageCount());
        assertEquals(prompt, bubble(0, "ai.chat.user"));
        assertTrue(model.transcriptMemory.readTranscript().isEmpty());
        assertNull("An unwritten generated image must not be published", messageRow(1).findViewWithTag("ai.chat.image"));
        assertEquals("正在保存历史…", text("ai.chat.state").getText().toString());
        assertFalse(send.isEnabled());

        historyWorker.runAllOnThread();
        List<TestChatMemory.Turn> saved = model.transcriptMemory.readTranscript();
        assertEquals(1, saved.size());
        assertEquals(prompt, saved.get(0).prompt);
        assertTrue(saved.get(0).displayOnly);
        assertTrue(saved.get(0).images.isEmpty());
        assertEquals(1, saved.get(0).assistantImages.size());
        assertEquals("image/png", saved.get(0).assistantImages.get(0).mimeType);
        assertTrue(saved.get(0).assistantImages.get(0).sizeBytes() > 0);
        assertTrue(model.transcriptMemory.read().isEmpty());
        assertNull(messageRow(1).findViewWithTag("ai.chat.image"));

        ShadowLooper.idleMainLooper();
        ImageView image = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(image);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(image.getDrawable());
        assertEquals("发送", send.getContentDescription());
        assertEquals(1, model.transcriptMemory.readTranscript().size());
        assertTrue(model.prompts.isEmpty());
        assertTrue(model.skillInputs.isEmpty());
    }

    @Test public void workImageOpensALocalPreviewBeforeAndAfterReopeningWithoutReplay() throws Exception {
        FakeSession first = openMockImageHistory();
        String prompt = "/生图 白猫";
        submit(prompt);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, first.transcriptMemory.readTranscript().size());

        ImageView originalThumbnail = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(originalThumbnail);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(originalThumbnail.getDrawable() instanceof BitmapDrawable);
        Dialog firstPreview = openGeneratedImagePreview(originalThumbnail);
        ImageView originalPicture = previewContent(firstPreview).findViewWithTag("ai.chat.image_preview_image");
        assertDoubleTapZoom(originalPicture);
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        tapAndSettle(originalPicture, 180f, 16f);
        assertFalse(firstPreview.isShowing());
        assertTrue(first.prompts.isEmpty());
        assertTrue(first.skillInputs.isEmpty());
        assertEquals(1, first.transcriptMemory.readTranscript().size());

        FakeSession restored = new FakeSession();
        restored.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertEquals(prompt, bubble(0, "ai.chat.user"));
        ImageView restoredThumbnail = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(restoredThumbnail);
        assertTrue(restoredThumbnail.getDrawable() instanceof BitmapDrawable);
        Dialog restoredPreview = openGeneratedImagePreview(restoredThumbnail);
        assertNotSame(firstPreview, restoredPreview);
        restoredPreview.dismiss();
        assertTrue(restored.prompts.isEmpty());
        assertTrue(restored.skillInputs.isEmpty());
        assertEquals(1, restored.transcriptMemory.readTranscript().size());
    }

    @Test public void previewBlankTapClosesButImageTapAndPinchReleaseKeepItOpen() throws Exception {
        FakeSession model = openMockImageHistory();
        submit("/生图 白猫");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        ImageView thumbnail = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(thumbnail);
        Dialog preview = openGeneratedImagePreview(thumbnail);
        View content = previewContent(preview);
        assertNull("Preview closes from blank space instead of a close button",
                content.findViewWithTag("ai.chat.image_preview_close"));

        ImageView picture = content.findViewWithTag("ai.chat.image_preview_image");
        layoutImageViewport(picture);
        float fitted = imageScale(picture);
        tapAndSettle(picture, 180f, 240f);
        assertTrue("An ordinary tap on the image must not close the preview", preview.isShowing());

        tap(picture, SystemClock.uptimeMillis(), 101f, 240f);
        ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
        Pinch spreading = new Pinch(picture, 141f, 80f);
        assertEquals("A second contact starting a pinch must not toggle double-tap zoom",
                fitted, imageScale(picture), 0.01f);
        spreading.move(141f, 190f);
        spreading.move(141f, 232f);
        assertTrue("The Android detector must recognize the spreading fingers", imageScale(picture) > fitted);
        Matrix inverse = new Matrix();
        assertTrue(picture.getImageMatrix().invert(inverse));
        float[] focalPoint = {141f, 240f};
        inverse.mapPoints(focalPoint);
        spreading.move(173f, 285f);
        picture.getImageMatrix().mapPoints(focalPoint);
        assertEquals("The same point on the bitmap follows a moving pinch focus", 173f, focalPoint[0], 0.5f);
        spreading.liftInBlankSpace();
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        float enlarged = imageScale(picture);
        assertTrue("Spreading the fingers must enlarge the image", enlarged > fitted);
        assertTrue("Lifting the last finger after enlargement must not close the preview", preview.isShowing());

        // At fit, the 320 × 192 fixture begins at y=132. Enlargement now covers y=112.
        tapAndSettle(picture, 180f, 112f);
        assertTrue("Hit testing must follow the enlarged image bounds", preview.isShowing());

        Pinch shrinking = new Pinch(picture, 180f, 320f);
        shrinking.move(180f, 270f);
        shrinking.move(180f, 220f);
        shrinking.move(180f, 180f);
        shrinking.liftInBlankSpace();
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        assertTrue("Bringing the fingers together must reduce the image", imageScale(picture) < enlarged);
        assertTrue(imageScale(picture) >= fitted);
        assertTrue("Lifting a finger in the blank area after a pinch must not close the preview", preview.isShowing());

        long drag = SystemClock.uptimeMillis();
        dispatchPointers(picture, drag, drag, MotionEvent.ACTION_DOWN, 180f, 240f);
        dispatchPointers(picture, drag, drag + 20, MotionEvent.ACTION_MOVE, 180f, 16f);
        dispatchPointers(picture, drag, drag + 40, MotionEvent.ACTION_UP, 180f, 16f);
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
        assertTrue("A drag ending in the blank area must not close the preview", preview.isShowing());

        tapAndSettle(picture, 180f, 16f);
        assertFalse("A fresh tap in the blank area must close the preview", preview.isShowing());
        assertTrue(model.prompts.isEmpty());
        assertTrue(model.skillInputs.isEmpty());
    }

    @Test public void damagedSavedImageShowsALocalReadErrorWithoutReplay() throws Exception {
        FakeSession restored = new FakeSession();
        restored.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        String prompt = "/生图 损坏图片样本";
        ChatImage broken = new ChatImage("image/png", new byte[]{1, 2, 3});
        assertTrue(restored.transcriptMemory.append(restored.transcriptMemory.epoch(),
                TestChatMemory.Turn.displayedSkill(prompt, "", List.of(broken), null)));
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        ImageView thumbnail = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(thumbnail);
        assertNull(thumbnail.getDrawable());

        Dialog preview = tapGeneratedThumbnail(thumbnail);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        View content = previewContent(preview);
        TextView status = content.findViewWithTag("ai.chat.image_preview_status");
        assertNotNull(status);
        assertEquals("图片无法读取", status.getText().toString());
        ImageView invalid = content.findViewWithTag("ai.chat.image_preview_image");
        assertNotNull(invalid);
        assertNull(invalid.getDrawable());
        layoutImageViewport(invalid);
        tapAndSettle(invalid, 180f, 16f);
        assertFalse(preview.isShowing());
        assertEquals(prompt, bubble(0, "ai.chat.user"));
        assertEquals(1, restored.transcriptMemory.readTranscript().size());
        assertTrue(restored.prompts.isEmpty());
        assertTrue(restored.skillInputs.isEmpty());
    }

    @Test public void lateLocalDecodeCannotRenderIntoAClosedOrReplacedPreview() throws Exception {
        FakeSession model = openMockImageHistory();
        submit("/生图 白猫");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        ImageView thumbnail = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(thumbnail);

        Dialog first = tapGeneratedThumbnail(thumbnail);
        View oldContent = previewContent(first);
        ImageView oldImage = oldContent.findViewWithTag("ai.chat.image_preview_image");
        assertNotNull(oldImage);
        worker.runNext();
        first.onBackPressed();
        assertFalse(first.isShowing());
        Dialog next = tapGeneratedThumbnail(thumbnail);
        assertNotSame(first, next);
        ImageView currentImage = previewContent(next).findViewWithTag("ai.chat.image_preview_image");
        assertNotNull(currentImage);
        ShadowLooper.idleMainLooper();
        assertNull(oldImage.getDrawable());
        assertNull(currentImage.getDrawable());

        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(currentImage.getDrawable() instanceof BitmapDrawable);
        assertTrue(next.isShowing());
        next.dismiss();
        assertTrue(model.prompts.isEmpty());
        assertTrue(model.skillInputs.isEmpty());
        assertEquals(1, model.transcriptMemory.readTranscript().size());
    }

    private Dialog openGeneratedImagePreview(ImageView thumbnail) throws Exception {
        Dialog preview = tapGeneratedThumbnail(thumbnail);
        worker.runAllOnThread();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        ImageView image = previewContent(preview).findViewWithTag("ai.chat.image_preview_image");
        assertNotNull(image);
        assertTrue(image.getDrawable() instanceof BitmapDrawable);
        assertTrue(((BitmapDrawable) image.getDrawable()).getBitmap().getWidth() > 0);
        return preview;
    }

    private Dialog tapGeneratedThumbnail(ImageView thumbnail) {
        assertTrue("Generated thumbnails must open the local read-only preview", thumbnail.performClick());
        Dialog preview = ShadowDialog.getLatestDialog();
        assertNotSame(dialog, preview);
        assertTrue(preview.isShowing());
        return preview;
    }

    private static View previewContent(Dialog preview) {
        View content = preview.getWindow().getDecorView().findViewWithTag("ai.chat.image_preview");
        assertNotNull(content);
        return content;
    }

    private static void assertDoubleTapZoom(ImageView picture) {
        layoutImageViewport(picture);
        float fitted = imageScale(picture);
        assertTrue(fitted > 0f);
        long first = SystemClock.uptimeMillis();
        tap(picture, first);
        tap(picture, first + 100);
        assertEquals(fitted * 2.5f, imageScale(picture), 0.01f);
        ShadowLooper.idleMainLooper(500, TimeUnit.MILLISECONDS);
        long second = SystemClock.uptimeMillis();
        tap(picture, second);
        tap(picture, second + 100);
        assertEquals(fitted, imageScale(picture), 0.01f);
    }

    private static void layoutImageViewport(ImageView picture) {
        picture.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        picture.layout(0, 0, 360, 480);
    }

    private static float imageScale(ImageView picture) {
        float[] values = new float[9];
        picture.getImageMatrix().getValues(values);
        return values[Matrix.MSCALE_X];
    }

    private static void tap(ImageView picture, long time) {
        tap(picture, time, 180f, 240f);
    }

    private static void tapAndSettle(ImageView picture, float x, float y) {
        tap(picture, SystemClock.uptimeMillis(), x, y);
        ShadowLooper.idleMainLooper(400, TimeUnit.MILLISECONDS);
    }

    private static void tap(ImageView picture, long time, float x, float y) {
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0);
        MotionEvent up = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, x, y, 0);
        try {
            picture.dispatchTouchEvent(down);
            picture.dispatchTouchEvent(up);
        } finally {
            down.recycle();
            up.recycle();
        }
    }

    private static final class Pinch {
        private final ImageView picture;
        private final long down = SystemClock.uptimeMillis();
        private long time = down;
        private float focus;
        private float span;

        Pinch(ImageView picture, float focus, float span) {
            this.picture = picture;
            this.focus = focus;
            this.span = span;
            dispatchPointers(picture, down, time, MotionEvent.ACTION_DOWN, focus - span / 2, 240f);
            pair(MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
        }

        void move(float focus, float span) {
            this.focus = focus;
            this.span = span;
            pair(MotionEvent.ACTION_MOVE);
        }

        void liftInBlankSpace() {
            pair(MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT));
            dispatchPointers(picture, down, time += 16, MotionEvent.ACTION_MOVE, 180f, 16f);
            dispatchPointers(picture, down, time + 16, MotionEvent.ACTION_UP, 180f, 16f);
        }

        private void pair(int action) {
            float half = span / 2;
            dispatchPointers(picture, down, time += 16, action, focus - half, 240f, focus + half, 240f);
        }
    }

    private static void dispatchPointers(ImageView picture, long down, long time, int action, float... points) {
        int count = points.length / 2;
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count];
        MotionEvent.PointerCoords[] coordinates = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coordinates[i] = new MotionEvent.PointerCoords();
            coordinates[i].x = points[i * 2];
            coordinates[i].y = points[i * 2 + 1];
            coordinates[i].pressure = 1f;
            coordinates[i].size = 1f;
        }
        MotionEvent event = MotionEvent.obtain(down, time, action, count, properties, coordinates,
                0, 0, 1f, 1f, 0, 0, android.view.InputDevice.SOURCE_TOUCHSCREEN, 0);
        try { picture.dispatchTouchEvent(event); }
        finally { event.recycle(); }
    }

    @Test public void failedHistoryWriteKeepsTheOriginalImageInThisConversationWithoutRetry() throws Exception {
        File blockedDirectory = new File(activity.getFilesDir(), "douai_test_chat");
        Files.writeString(blockedDirectory.toPath(), "This fixture blocks creating the history directory.");
        FakeSession model = openMockImageHistory();
        submit("/生图 海洋");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertNull(messageRow(1).findViewWithTag("ai.chat.image"));
        assertFalse(send.isEnabled());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(2, messageCount());
        assertTrue(model.transcriptMemory.readTranscript().isEmpty());
        ImageView image = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(image);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(image.getDrawable());
        String visibleFeedback = text("ai.chat.state").getText() + "\n" + bubble(1, "ai.chat.answer");
        assertTrue(visibleFeedback.contains("技能已完成，但未能确认历史已保存。结果保留在当前会话。"));
        View retry = messageRow(1).findViewWithTag("ai.chat.retry");
        assertTrue(retry == null || retry.getVisibility() != View.VISIBLE);
        assertEquals("发送", send.getContentDescription());
        assertTrue(model.prompts.isEmpty());
        assertTrue(model.skillInputs.isEmpty());
    }

    @Test public void clearFencesAnUnwrittenImageButAnOrdinaryClosePreservesTheNextFinishingWrite() throws Exception {
        FakeSession model = openMockImageHistory();
        submit("/生图 将被清空");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNull(messageRow(1).findViewWithTag("ai.chat.image"));
        assertTrue(root.findViewWithTag("ai.chat.clear").performClick());
        assertEquals(0, messageCount());

        historyWorker.runNext();
        assertTrue(model.transcriptMemory.readTranscript().isEmpty());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertTrue(model.transcriptMemory.readTranscript().isEmpty());

        String kept = "/生图 关闭前生成";
        submit(kept);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNull(messageRow(1).findViewWithTag("ai.chat.image"));
        assertTrue(model.transcriptMemory.readTranscript().isEmpty());
        dialog.dismiss();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertFalse(dialog.isShowing());
        assertEquals(1, model.transcriptMemory.readTranscript().size());
        assertEquals(kept, model.transcriptMemory.readTranscript().get(0).prompt);

        FakeSession reopened = new FakeSession();
        reopened.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(reopened, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertEquals(kept, bubble(0, "ai.chat.user"));
        ImageView image = messageRow(1).findViewWithTag("ai.chat.image");
        assertNotNull(image);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(image.getDrawable());
        assertTrue(reopened.prompts.isEmpty());
        assertTrue(reopened.skillInputs.isEmpty());
    }

    private FakeSession openMockImageHistory() throws Exception {
        installMockImageSkill();
        FakeSession model = new FakeSession();
        model.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(model, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        return model;
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

    @Test public void namedWorkSkillSkipsRoutingAndContactCanSelectTheSameInstalledSkill() throws Exception {
        SkillStore store = SkillStore.forContext(activity);
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(textSkillDraft("保留事实，使用分点格式。")));
        FakeSession work = new FakeSession();
        work.skillReplies.add("第一期已上线。");
        reopen(work, ChatImageLoader::thumbnail, AiSkills.Entry.WORK, store);
        submit("请用周报润色处理：第一期上线");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("第一期已上线。", lastAnswer());
        assertEquals(List.of(SkillAuthoring.TEXT_INSTRUCTION), work.skillInstructions);
        assertEquals("第一期上线", new JSONObject(work.skillInputs.get(0)).getString("argument"));

        FakeSession contact = new FakeSession();
        contact.skillReplies.add("{\"name\":\"周报润色\",\"argument\":\"第二期上线\"}");
        contact.skillReplies.add("第二期已上线。");
        reopen(contact, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT, store);
        String natural = "帮我整理：第二期上线";
        submit(natural);
        assertEquals(0, messageCount());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(natural, bubble(0, "ai.chat.user"));
        assertEquals("第二期已上线。", lastAnswer());
        assertEquals(List.of(SkillSelection.INSTRUCTION, SkillAuthoring.TEXT_INSTRUCTION), contact.skillInstructions);
        assertEquals(natural, new JSONObject(contact.skillInputs.get(0)).getString("task"));
        assertEquals("第二期上线", new JSONObject(contact.skillInputs.get(1)).getString("argument"));
        assertTrue(contact.prompts.isEmpty());
    }

    @Test public void aRoutedSkillRemovedWhileSelectionWasQueuedNeverRunsOrFallsBackToChat() throws Exception {
        SkillStore store = SkillStore.forContext(activity);
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(textSkillDraft("保留事实，使用分点格式。")));
        FakeSession work = new FakeSession();
        work.skillReplies.add("{\"name\":\"周报润色\",\"argument\":\"一期上线\"}");
        reopen(work, ChatImageLoader::thumbnail, AiSkills.Entry.WORK, store);
        String natural = "帮我整理：一期上线";
        submit(natural);
        store.delete(store.detail("周报润色").deleteTarget());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertEquals(natural, input.getText().toString());
        assertTrue(text("ai.chat.state").getText().toString().contains("技能已变化"));
        assertTrue(worker.tasks.isEmpty());
        assertTrue(work.prompts.isEmpty());
    }

    @Test public void aModelSelectedExternalSkillWaitsForConsentWithoutDuplicatingTheUserTurn() throws Exception {
        String name = "接口整理";
        String endpoint = "https://skill-api.example.com/v1/process";
        AtomicInteger calls = new AtomicInteger();
        SkillStore store = SkillCustomServiceTest.chatFixture(activity, calls);
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft("""
                {"schemaVersion":4,"name":"接口整理","description":"整理本次输入","documentation":"输入待整理文本。",
                 "instructions":"整理本次输入","capabilityId":"custom_service","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """));
        store.configureCustomService(store.detail(name), endpoint, "");
        FakeSession work = new FakeSession();
        work.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        String plan = "{\"name\":\"接口整理\",\"argument\":\"样例\"}";
        work.skillReplies.add(plan);
        work.skillReplies.add(plan);
        reopen(work, ChatImageLoader::thumbnail, AiSkills.Entry.WORK, store);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        String natural = "帮我整理这个样例";
        submit(natural);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        AlertDialog firstReview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(0, calls.get());
        assertEquals(0, messageCount());
        assertFalse(work.skillInputs.get(0).contains(endpoint));
        assertTrue(firstReview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertTrue(work.transcriptMemory.readTranscript().isEmpty());

        submit(natural);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        AlertDialog secondReview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(firstReview, secondReview);
        assertEquals(0, calls.get());
        assertTrue(secondReview.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, calls.get());
        assertEquals(2, messageCount());
        assertEquals(natural, bubble(0, "ai.chat.user"));
        assertEquals("测试端点结果", lastAnswer());
        assertEquals(1, work.transcriptMemory.readTranscript().size());
        assertEquals(List.of(SkillSelection.INSTRUCTION, SkillSelection.INSTRUCTION), work.skillInstructions);
        assertTrue(work.prompts.isEmpty());
    }

    @Test public void customServiceSettingsRequireConfirmationForEachChatRequest() throws Exception {
        String name = "接口整理";
        String command = "/接口整理 样例";
        String endpoint = "https://skill-api.example.com/v1/process";
        AtomicInteger calls = new AtomicInteger();
        SkillStore store = SkillCustomServiceTest.chatFixture(activity, calls);
        FakeSession work = new FakeSession();
        work.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        work.skillReplies.add("""
                {"schemaVersion":4,"name":"接口整理","description":"使用已配置的接口整理输入","documentation":"输入需要整理的文本。",
                 "instructions":"整理本次输入","capabilityId":"custom_service","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """);
        reopen(work, ChatImageLoader::thumbnail, AiSkills.Entry.WORK, store);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("帮我创建一个自定义服务技能并内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals(List.of(name), store.pending().stream().map(SkillStore.Pending::name).toList());
        assertEquals(1, work.transcriptMemory.readTranscript().size());
        AlertDialog settings = openChatSkillSettings(name);
        EditText endpointInput = settings.getWindow().getDecorView().findViewWithTag("skills.detail.custom.endpoint");
        assertNotNull(endpointInput);
        assertTrue(endpointInput.getText().toString().isEmpty());
        endpointInput.setText(endpoint);
        View save = settings.getWindow().getDecorView().findViewWithTag("skills.detail.custom.save");
        assertNotNull(save);
        assertTrue(save.performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(store.detail(name).pending());
        assertEquals(endpoint, SkillStore.forContext(activity).detail(name).customConfiguration().endpoint());
        assertEquals(0, calls.get());
        settings.dismiss();

        int messagesBefore = messageCount();
        submit(command);
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        org.robolectric.shadows.ShadowAlertDialog shadow = org.robolectric.shadow.api.Shadow.extract(review);
        assertEquals("确认本次技能服务调用", String.valueOf(shadow.getTitle()));
        String disclosure = String.valueOf(shadow.getMessage());
        assertTrue(disclosure.contains(endpoint));
        assertTrue(disclosure.contains("POST"));
        assertTrue(disclosure.contains("input"));
        assertTrue(disclosure.contains("样例"));
        assertTrue(disclosure.contains("费用由服务商决定"));
        assertTrue(worker.tasks.isEmpty());
        assertEquals(messagesBefore, messageCount());
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(worker.tasks.isEmpty());
        assertEquals(messagesBefore, messageCount());
        assertEquals(1, work.transcriptMemory.readTranscript().size());
        assertEquals(0, calls.get());

        submit(command);
        AlertDialog staleReview = (AlertDialog) ShadowDialog.getLatestDialog();
        SkillStore.Detail replacement = store.configureCustomService(store.detail(name), endpoint, "");
        assertTrue(replacement.canReplace());
        store.activateReplacement(replacement);
        assertTrue(staleReview.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(messagesBefore, messageCount());
        assertTrue(worker.tasks.isEmpty());
        assertEquals(1, work.transcriptMemory.readTranscript().size());
        assertEquals(0, calls.get());

        submit(command);
        AlertDialog nextReview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(review, nextReview);
        assertTrue(worker.tasks.isEmpty());
        assertEquals("确认调用", nextReview.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(nextReview.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(1, worker.tasks.size());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        assertEquals("测试端点结果", lastAnswer());
        assertEquals(messagesBefore + 2, messageCount());
        List<TestChatMemory.Turn> saved = work.transcriptMemory.readTranscript();
        assertEquals(2, saved.size());
        assertEquals(command, saved.get(1).prompt);
        assertEquals("测试端点结果", saved.get(1).answer);
        assertEquals(1, calls.get());
        assertTrue(worker.tasks.isEmpty());
        assertEquals(List.of(SkillAuthoring.DESIGN_INSTRUCTION), work.skillInstructions);
        assertTrue(work.prompts.isEmpty());
    }

    @Test public void authoredVideoStaysPendingAndSlashCallDoesNotSendOrStartAJob() throws Exception {
        assertVideoAuthoringStaysPending(AiSkills.Entry.WORK);
    }

    @Test public void contactAuthorsTheSameVideoRequestWithoutSubmittingAJob() throws Exception {
        assertVideoAuthoringStaysPending(AiSkills.Entry.CONTACT);
    }

    private static final String VIDEO_AUTHORING_REQUEST = "帮我写一个生成视频的 skill，要求自动轮询自动发送并内置";

    private void assertVideoAuthoringStaysPending(AiSkills.Entry entry) throws Exception {
        FakeSession session = new FakeSession();
        session.transcriptMemory = entry == AiSkills.Entry.CONTACT
                ? TestChatMemory.messageConversation(activity, restoreConfiguration())
                : new TestChatMemory(activity, restoreConfiguration());
        AtomicInteger submits = new AtomicInteger();
        SkillVideoJobs jobs = SkillVideoJobsTest.chatFixture(activity, submits);
        session.skillReplies.add(videoDraftWithoutProvider());
        reopen(session, ChatImageLoader::thumbnail, entry, null, jobs);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit(VIDEO_AUTHORING_REQUEST);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        SkillStore store = SkillStore.forContext(activity);
        assertEquals(List.of(SkillAuthoring.DESIGN_INSTRUCTION), session.skillInstructions);
        JSONObject request = new JSONObject(session.skillInputs.get(0));
        assertEquals("video", request.getString("requiredOutputType"));
        String requirement = request.getString("requirement");
        assertTrue(requirement.contains("生成视频的"));
        assertTrue(requirement.contains("自动轮询自动发送"));
        assertEquals(List.of("电影片段"), store.pending().stream().map(SkillStore.Pending::name).toList());
        assertTrue(store.list().isEmpty());
        SkillStore.Detail detail = store.detail("电影片段");
        assertNotNull(detail);
        assertTrue(detail.pending());
        assertNull(detail.current());
        assertNull(detail.manifest().http());
        assertEquals(SkillResult.Type.VIDEO, detail.outputType());
        assertTrue(lastAnswer().contains("待配置草稿 /电影片段"));
        assertTrue(lastAnswer().contains(SkillStore.NEEDS_VIDEO_PROVIDER));
        assertEquals(2, messageCount());
        AlertDialog settings = openChatSkillSettings("电影片段");
        TextView heading = settings.getWindow().getDecorView().findViewWithTag("skills.detail.name");
        assertEquals("/电影片段", heading.getText().toString());
        settings.dismiss();

        submit("/电影片段 一只白猫");
        assertTrue(worker.tasks.isEmpty());
        assertTrue(session.prompts.isEmpty());
        assertEquals(1, session.skillInputs.size());
        assertEquals(2, messageCount());
        assertTrue(text("ai.chat.state").getText().toString().startsWith(SkillStore.NEEDS_VIDEO_PROVIDER));
        var scope = new SkillVideoJobs.Scope(entry, session.transcriptMemory.conversationKey());
        assertTrue(jobs.list(scope).isEmpty());
        assertEquals(0, submits.get());
        var transcript = session.transcriptMemory.readTranscript();
        assertEquals(1, transcript.size());
        assertEquals(VIDEO_AUTHORING_REQUEST, transcript.get(0).prompt);
        assertEquals(videoDraftWithoutProvider(), ((SkillResult.Declaration) transcript.get(0).artifact).json());
    }

    @Test public void workSlashSkillPreviewsAndRequiresAnExplicitInstall() throws Exception {
        assertSlashSkillPreviewsAndRequiresAnExplicitInstall(AiSkills.Entry.WORK);
    }

    @Test public void contactSlashSkillPreviewsAndRequiresAnExplicitInstall() throws Exception {
        assertSlashSkillPreviewsAndRequiresAnExplicitInstall(AiSkills.Entry.CONTACT);
    }

    private void assertSlashSkillPreviewsAndRequiresAnExplicitInstall(AiSkills.Entry entry) throws Exception {
        FakeSession session = new FakeSession();
        session.transcriptMemory = entry == AiSkills.Entry.CONTACT
                ? TestChatMemory.messageConversation(activity, restoreConfiguration())
                : new TestChatMemory(activity, restoreConfiguration());
        session.skillReplies.add(videoDraftWithoutProvider());
        AtomicInteger submits = new AtomicInteger();
        SkillVideoJobs jobs = SkillVideoJobsTest.chatFixture(activity, submits);
        reopen(session, ChatImageLoader::thumbnail, entry, null, jobs);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        submit("/技能");
        assertEquals("请在 /技能 后填写技能需求", text("ai.chat.state").getText().toString());
        assertTrue(worker.tasks.isEmpty());
        assertTrue(session.prompts.isEmpty());
        assertTrue(session.skillInputs.isEmpty());
        assertEquals(0, messageCount());

        String requirement = "生成一个自动轮询、完成后自动发送的视频技能";
        submit("/技能 " + requirement);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(List.of(SkillAuthoring.DESIGN_INSTRUCTION), session.skillInstructions);
        assertEquals(1, session.skillInputs.size());
        JSONObject request = new JSONObject(session.skillInputs.get(0));
        assertEquals(requirement, request.getString("requirement"));
        assertEquals("video", request.getString("requiredOutputType"));
        assertTrue(lastAnswer().contains("技能草案 /电影片段"));
        assertTrue(lastAnswer().contains("尚未保存"));
        SkillStore store = SkillStore.forContext(activity);
        assertTrue(store.pending().isEmpty());
        assertTrue(store.list().isEmpty());
        assertNull(findView("ai.chat.skill.settings.电影片段"));

        submit("帮我内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("待配置草稿 /电影片段"));
        assertTrue(lastAnswer().contains(SkillStore.NEEDS_VIDEO_PROVIDER));
        assertEquals(List.of("电影片段"), store.pending().stream().map(SkillStore.Pending::name).toList());
        assertTrue(store.list().isEmpty());
        SkillStore.Detail detail = store.detail("电影片段");
        assertTrue(detail.pending());
        assertEquals(SkillResult.Type.VIDEO, detail.outputType());
        assertNull(detail.manifest().http());
        assertNotNull(findView("ai.chat.skill.settings.电影片段"));
        assertEquals(1, session.skillInputs.size());
        assertTrue(session.prompts.isEmpty());
        assertEquals(0, submits.get());
        assertTrue(jobs.list(new SkillVideoJobs.Scope(entry, session.transcriptMemory.conversationKey())).isEmpty());
    }

    @Test public void slashSkillPrefixKeepsNamedExecutionAndQuotedOrCodeInputSeparate() throws Exception {
        SkillStore store = SkillStore.forContext(activity);
        String definition = textSkillDraft("保留事实，使用分点格式。").replace("\"周报润色\"", "\"技能润色\"");
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(definition));
        FakeSession session = new FakeSession();
        session.skillReplies.add("本周已完成第一阶段。");
        session.answer = "/技能 生成视频技能";
        reopen(session, ChatImageLoader::thumbnail, AiSkills.Entry.WORK, store);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        submit("/技能润色 第一阶段完成");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("本周已完成第一阶段。", lastAnswer());
        assertEquals(List.of(SkillAuthoring.TEXT_INSTRUCTION), session.skillInstructions);
        assertEquals("第一阶段完成", new JSONObject(session.skillInputs.get(0)).getString("argument"));

        List<String> quoted = List.of("「/技能 生成视频技能」", "`/技能 生成视频技能`");
        for (String prompt : quoted) {
            submit(prompt);
            worker.runNext();
            ShadowLooper.idleMainLooper();
            assertEquals(session.answer, lastAnswer());
        }
        assertEquals(quoted, session.prompts);
        assertEquals(1, session.skillInputs.size());
        assertTrue(store.pending().isEmpty());
        assertEquals(List.of("技能润色"), store.list().stream().map(SkillStore.Installed::name).toList());
    }

    private static String videoDraftWithoutProvider() {
        return """
                {"schemaVersion":4,"name":"电影片段","description":"根据提示生成视频","documentation":"输入视频提示",
                 "instructions":"按提示生成视频","capabilityId":"video_task","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"示例","expectedType":"video"}}
                """;
    }

    @Test public void quotedCodeAndForwardedVideoRequestsAndOrdinaryRepliesNeverInstallSkills() throws Exception {
        String request = "帮我写一个生成视频的 skill，要求自动轮询自动发送并内置";
        FakeSession model = new FakeSession();
        model.answer = request;
        reopen(model, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        List<String> ordinary = List.of("自动发送是怎么回事", "「" + request + "」", "`" + request + "`", "朋友转发：" + request);
        for (String message : ordinary) {
            submit(message);
            worker.runNext();
            ShadowLooper.idleMainLooper();
            assertEquals(request, lastAnswer());
        }
        assertEquals(ordinary, model.prompts);
        assertTrue(model.skillInputs.isEmpty());
        SkillStore store = SkillStore.forContext(activity);
        assertTrue(store.pending().isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test public void workCopiesCompleteMessageTextOnlyAfterChoosingCopy() throws Exception {
        FakeSession session = new FakeSession();
        session.answer = "```json\n  {\"text\": \"x  y\", \"flag\": false}\n```\n  保留原样";
        reopen(session, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "原有剪贴板"));

        String prompt = "第一行  \n  第二行 {\"key\": \":\"}\n保留缩进";
        submit(prompt);
        TextView placeholder = messageRow(1).findViewWithTag("ai.chat.answer");
        assertEquals("正在回复…", placeholder.getText().toString());
        assertFalse(placeholder.performLongClick());
        assertEquals("原有剪贴板", clipboardText(clipboard));
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(prompt, copyDisplayedMessage(0, "ai.chat.user", "复制消息", clipboard));
        assertEquals(session.answer, copyDisplayedMessage(1, "ai.chat.answer", "复制消息", clipboard));
    }

    @Test public void contactCopiesRestoredMultilineAnswerVerbatimWithoutReplay() throws Exception {
        FakeSession restored = new FakeSession();
        restored.transcriptMemory = TestChatMemory.messageConversation(activity, restoreConfiguration());
        String answer = "旧回复  \n\n```json\n  {\"a\": [\"b\", \"c\"]}\n```\n结束  ";
        assertTrue(restored.transcriptMemory.append(restored.transcriptMemory.epoch(),
                new TestChatMemory.Turn("历史问题", "", List.of(), answer)));
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "原有剪贴板"));

        assertEquals(answer, copyDisplayedMessage(1, "ai.chat.answer", "复制消息", clipboard));
        assertTrue(restored.prompts.isEmpty());
        assertTrue(restored.skillInputs.isEmpty());
    }

    @Test public void validatedPreviewCopiesExactDeclarationSeparatelyFromSummaryAfterRestore() throws Exception {
        FakeSession author = new FakeSession();
        author.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        JSONObject manifest = new JSONObject(videoDraftWithoutProvider());
        String rawDeclaration = " \n" + manifest.toString(2) + "\n  ";
        author.skillReplies.add(rawDeclaration);
        reopen(author, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        submit("/技能 生成一个视频技能");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        String summary = lastAnswer();
        assertTrue(summary.contains("技能草案 /电影片段"));
        assertTrue(summary.contains("尚未保存"));
        assertFalse(summary.contains("schemaVersion"));
        SkillStore store = SkillStore.forContext(activity);
        assertTrue(store.pending().isEmpty());
        assertTrue(store.list().isEmpty());
        ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "原有剪贴板"));
        assertEquals(rawDeclaration, copyDisplayedMessage(1, "ai.chat.answer", "复制技能声明", clipboard));

        FakeSession restored = new FakeSession();
        restored.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        String restoredSummary = lastAnswer();
        assertTrue(restoredSummary.contains("技能草案 /电影片段"));
        assertTrue(summary.startsWith(restoredSummary + "\n"));
        assertFalse(restoredSummary.contains("schemaVersion"));
        clipboard.setPrimaryClip(ClipData.newPlainText("test", "恢复后的剪贴板"));
        assertEquals(rawDeclaration, copyDisplayedMessage(1, "ai.chat.answer", "复制技能声明", clipboard));
        assertTrue(restored.prompts.isEmpty());
        assertTrue(restored.skillInputs.isEmpty());
        assertTrue(store.pending().isEmpty());
        assertTrue(store.list().isEmpty());
    }

    private String copyDisplayedMessage(int row, String tag, String action, ClipboardManager clipboard) {
        String before = clipboardText(clipboard);
        View message = messageRow(row).findViewWithTag(tag);
        assertNotNull(message);
        assertTrue("Long press should open the copy menu", message.performLongClick());
        ShadowLooper.idleMainLooper();
        assertEquals("Opening the menu must leave the clipboard unchanged", before, clipboardText(clipboard));
        AlertDialog menu = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(menu.isShowing());
        ListView items = menu.getListView();
        for (int index = 0; index < items.getCount(); index++) {
            if (action.equals(String.valueOf(items.getAdapter().getItem(index)))) {
                org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowAlertDialog>extract(menu).clickOnItem(index);
                ShadowLooper.idleMainLooper();
                return clipboardText(clipboard);
            }
        }
        throw new AssertionError("Missing menu option " + action);
    }

    private static String clipboardText(ClipboardManager clipboard) {
        return clipboard.getPrimaryClip().getItemAt(0).getText().toString();
    }

    @Test public void completeVideoNotesGenerateNestedDeclarationAndOpenConfiguration() throws Exception {
        FakeSession session = createVideoFromNotes(AiSkills.Entry.WORK, true);
        SkillStore store = SkillStore.forContext(activity);
        SkillStore.Detail pending = store.detail("电影片段");
        assertTrue(pending.pending());
        assertNotNull(pending.manifest().http());
        assertEquals("data.id", pending.manifest().http().responseField());
        assertEquals("data.status", pending.manifest().http().video().stateField());
        assertEquals(List.of("model"), pending.manifest().configFields().stream().map(SkillManifest.ConfigField::key).toList());
        assertNull(pending.customConfiguration());
        assertTrue(store.selectionCatalog().isEmpty());
        assertEquals(1, session.skillInputs.size());
        assertFalse(session.skillInputs.get(0).contains("video-api.example.com"));
        assertFalse(session.skillInputs.get(0).contains("data.status"));
        assertTrue(session.skillInputs.get(0).contains("自动轮询自动发送"));

        AlertDialog settings = openChatSkillSettings("电影片段");
        View body = settings.getWindow().getDecorView();
        EditText endpoint = body.findViewWithTag("skills.detail.custom.endpoint");
        assertNotNull(endpoint);
        assertEquals("https://video-api.example.com/v1/jobs", endpoint.getText().toString());
        assertNotNull(body.findViewWithTag("skills.detail.custom.credential"));
        EditText model = body.findViewWithTag("skills.detail.config.model");
        assertNotNull(model);
        model.setText("documented-demo-model");
        assertNull(body.findViewWithTag("skills.detail.video_declaration.input"));
        assertNotNull(body.findViewWithTag("skills.detail.video_facts.open"));
        assertTrue(body.findViewWithTag("skills.detail.custom.save").performClick());
        ShadowLooper.idleMainLooper();
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        settings = openChatSkillSettings("电影片段");
        assertEquals("https://video-api.example.com/v1/jobs",
                ((EditText) settings.getWindow().getDecorView().findViewWithTag("skills.detail.custom.endpoint")).getText().toString());
        assertEquals("data.id", SkillStore.forContext(activity).detail("电影片段").manifest().http().responseField());
        assertEquals("documented-demo-model", SkillStore.forContext(activity).detail("电影片段").manifestValues().get("model"));
        assertTrue(worker.tasks.isEmpty());
        assertTrue(SkillVideoJobs.forContext(activity).list(new SkillVideoJobs.Scope(AiSkills.Entry.WORK,
                session.transcriptMemory.conversationKey())).isEmpty());
    }

    @Test public void missingStatusStaysPendingAndSupplementRestoresSameVideoDraft() throws Exception {
        FakeSession session = createVideoFromNotes(AiSkills.Entry.CONTACT, false);
        SkillStore store = SkillStore.forContext(activity);
        SkillStore.Detail original = store.detail("电影片段");
        assertTrue(original.pending());
        assertNull(original.manifest().http());
        assertTrue(original.reason().contains("状态字段"));
        assertTrue(original.videoMissing().stream().anyMatch(name -> name.contains("状态字段")));
        assertTrue(store.selectionCatalog().isEmpty());

        AlertDialog settings = openChatSkillSettings("电影片段");
        View body = settings.getWindow().getDecorView();
        assertNull(body.findViewWithTag("skills.detail.custom.endpoint"));
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        settings = openChatSkillSettings("电影片段");
        assertEquals(original.draftId(), SkillStore.forContext(activity).detail("电影片段").draftId());

        body = settings.getWindow().getDecorView();
        assertTrue(body.findViewWithTag("skills.detail.video_facts.open").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText notes = editor.getWindow().getDecorView().findViewWithTag("skills.detail.video_facts.input");
        assertNotNull(notes);
        assertNull(editor.getWindow().getDecorView().findViewWithTag("skills.detail.video_declaration.input"));
        notes.setText("状态字段: data.status");
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        SkillStore.Detail ready = SkillStore.forContext(activity).detail("电影片段");
        assertEquals(original.name(), ready.name());
        assertTrue(ready.pending());
        assertNull(ready.customConfiguration());
        assertEquals("data.id", ready.manifest().http().responseField());
        assertEquals("data.status", ready.manifest().http().video().stateField());
        assertEquals(List.of("电影片段"), store.pending().stream().map(SkillStore.Pending::name).toList());

        EditText endpoint = body.findViewWithTag("skills.detail.custom.endpoint");
        assertNotNull(endpoint);
        assertEquals("https://video-api.example.com/v1/jobs", endpoint.getText().toString());
        EditText model = body.findViewWithTag("skills.detail.config.model");
        assertNotNull(model);
        model.setText("documented-demo-model");
        assertTrue(body.findViewWithTag("skills.detail.custom.save").performClick());
        ShadowLooper.idleMainLooper();
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        settings = openChatSkillSettings("电影片段");
        body = settings.getWindow().getDecorView();
        assertEquals("https://video-api.example.com/v1/jobs",
                ((EditText) body.findViewWithTag("skills.detail.custom.endpoint")).getText().toString());
        assertEquals("documented-demo-model",
                ((EditText) body.findViewWithTag("skills.detail.config.model")).getText().toString());
        assertEquals(1, session.skillInputs.size());
        settings.dismiss();
        ShadowLooper.idleMainLooper();
        submit(VIDEO_AUTHORING_REQUEST + "\n接口资料：\nAuthorization: Bearer synthetic-video-key");
        assertTrue(text("ai.chat.state").getText().toString().contains("移除密钥"));
        assertEquals(1, session.skillInputs.size());
        assertTrue(worker.tasks.isEmpty());
        assertTrue(SkillVideoJobs.forContext(activity).list(new SkillVideoJobs.Scope(AiSkills.Entry.CONTACT,
                session.transcriptMemory.conversationKey())).isEmpty());
    }

    @Test public void pollingVideoFieldFormKeepsEditsAndRecognizedFieldsAcrossReopen() throws Exception {
        FakeSession model = new FakeSession();
        model.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        model.skillReplies.add(new JSONObject(videoDraftWithoutProvider()).put("name", "轮询视频").toString());
        reopen(model, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("""
                /技能 生成一个轮询视频技能并内置
                接口资料：
                提交: POST https://video-api.example.com/v1/jobs
                提示词字段: prompt
                提交响应ID: {"data":{"id":"fixture-task"}}
                查询: GET /v1/jobs/{id}
                查询响应ID: data.id
                """.trim());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        SkillStore store = SkillStore.forContext(activity);
        assertTrue(store.detail("轮询视频").pending());
        assertTrue(store.selectionCatalog().isEmpty());

        AlertDialog settings = openChatSkillSettings("轮询视频");
        View detail = settings.getWindow().getDecorView();
        assertTrue(((TextView) detail.findViewWithTag("skills.detail.execution_mode")).getText().toString().contains("异步轮询"));
        assertTrue(detail.findViewWithTag("skills.detail.video_facts.open").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = editor.getWindow().getDecorView();
        EditText state = form.findViewWithTag("skills.detail.video_facts.field.stateField");
        assertNotNull(state);
        assertEquals("", state.getText().toString());
        state.setText("data.progress");
        ((EditText) form.findViewWithTag("skills.detail.video_facts.input"))
                .setText("状态字段: data.overridden\n查询结果字段: data.media");
        assertTrue(form.findViewWithTag("skills.detail.video_facts.prefill").performClick());
        ShadowLooper.idleMainLooper();
        assertEquals("data.progress", state.getText().toString());
        assertEquals("data.media",
                ((EditText) form.findViewWithTag("skills.detail.video_facts.field.resultUrlField")).getText().toString());
        assertTrue(((TextView) form.findViewWithTag("skills.detail.video_facts.source.resultUrlField"))
                .getText().toString().contains("文档"));
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertTrue(store.detail("轮询视频").pending());
        assertNull(store.detail("轮询视频").manifest().http());
        settings.dismiss();
        ShadowLooper.idleMainLooper();

        settings = openChatSkillSettings("轮询视频");
        detail = settings.getWindow().getDecorView();
        assertTrue(detail.findViewWithTag("skills.detail.video_facts.open").performClick());
        editor = (AlertDialog) ShadowDialog.getLatestDialog();
        form = editor.getWindow().getDecorView();
        assertEquals("data.progress",
                ((EditText) form.findViewWithTag("skills.detail.video_facts.field.stateField")).getText().toString());
        assertEquals("data.media",
                ((EditText) form.findViewWithTag("skills.detail.video_facts.field.resultUrlField")).getText().toString());
        ((EditText) form.findViewWithTag("skills.detail.video_facts.field.states")).setText("working=running,done=completed");
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        SkillStore.Detail prepared = store.detail("轮询视频");
        assertTrue(prepared.pending());
        assertEquals("data.media", prepared.manifest().http().video().resultUrlField());
        assertTrue(store.selectionCatalog().isEmpty());
        assertTrue(detail.findViewWithTag("skills.detail.custom.save").performClick());
        ShadowLooper.idleMainLooper();
        assertNotNull(AiSkills.match(AiSkills.Entry.WORK, "/轮询视频 海边", store));

        settings.dismiss();
        ShadowLooper.idleMainLooper();
        for (String prompt : List.of("/轮询视频 海边", "请调用 /轮询视频 海边", "帮我生成一段海边视频")) {
            if (prompt.startsWith("帮我")) model.skillReplies.add("{\"name\":\"轮询视频\",\"argument\":\"海边\"}");
            submit(prompt);
            if (prompt.startsWith("帮我")) {
                worker.runNext();
                ShadowLooper.idleMainLooper();
            }
            AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
            assertTrue(review.isShowing());
            var shadow = org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowAlertDialog>extract(review);
            String disclosure = String.valueOf(shadow.getMessage());
            assertTrue(disclosure.contains("POST https://video-api.example.com/v1/jobs"));
            assertTrue(disclosure.contains("成片链接字段 data.media"));
            assertFalse(disclosure.contains("取消任务时"));
            assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
            ShadowLooper.idleMainLooper();
            assertTrue(worker.tasks.isEmpty());
        }
        assertTrue(SkillVideoJobs.forContext(activity).list(new SkillVideoJobs.Scope(AiSkills.Entry.WORK,
                model.transcriptMemory.conversationKey())).isEmpty());
    }

    private FakeSession createVideoFromNotes(AiSkills.Entry entry, boolean status) throws Exception {
        FakeSession model = new FakeSession();
        model.transcriptMemory = entry == AiSkills.Entry.CONTACT
                ? TestChatMemory.messageConversation(activity, restoreConfiguration())
                : new TestChatMemory(activity, restoreConfiguration());
        JSONObject guessed = new JSONObject(videoDeclarationPayload());
        model.skillReplies.add(new JSONObject(videoDraftWithoutProvider()).put("http", guessed.getJSONObject("http"))
                .put("configFields", guessed.getJSONArray("configFields")).toString());
        reopen(model, ChatImageLoader::thumbnail, entry);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        String notes = """
                提交: POST https://video-api.example.com/v1/jobs
                提示词字段: prompt
                必填文本字段: model 模型名
                提交响应ID: data.id
                查询: GET /v1/jobs/{id}
                查询响应ID: data.id
                状态映射: waiting=queued,working=running,done=completed,error=failed,stop=cancelled
                取消: POST /v1/jobs/{id}/cancel
                下载: GET /v1/jobs/{id}/content
                """ + (status ? "状态字段: data.status" : "");
        submit(VIDEO_AUTHORING_REQUEST + "\n接口资料：\n" + notes);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(lastAnswer().contains("已保存待配置草稿"));
        return model;
    }

    private static String videoDeclarationPayload() {
        return """
                {"http":{"version":1,"label":"视频接口","request":{"prompt":"argument","seconds":"config.seconds"},
                 "response":"job_id","video":{"statusPath":"/v1/jobs/{id}","cancelPath":"/v1/jobs/{id}/cancel",
                 "cancelMethod":"POST","downloadPath":"/v1/jobs/{id}/content","idField":"id","stateField":"status",
                 "states":{"waiting":"queued","working":"running","done":"completed","error":"failed","stop":"cancelled"}}},
                 "configFields":[{"key":"seconds","label":"时长","type":"integer","required":true,"min":1,"max":10}]}
                """;
    }

    @Test public void declaredVideoRoutesRequireConfiguredFieldsAndConsentBeforeAnyJob() throws Exception {
        SkillStore store = SkillStore.forContext(activity);
        String endpoint = "https://video-api.example.com/v1/jobs";
        String sourceDefinition = """
                {"schemaVersion":4,"name":"视频来源","description":"合成原创视频","documentation":"填写画面提示",
                 "instructions":"用提供的契约提交视频","capabilityId":"video_task",
                 "configFields":[{"key":"seconds","label":"时长","type":"integer","required":true,"min":1,"max":10}],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"示例","expectedType":"video"},
                 "http":{"version":1,"label":"视频接口","suggestedEndpoint":"https://video-api.example.com/v1/jobs",
                 "request":{"prompt":"argument","seconds":"config.seconds"},"response":"job_id",
                 "video":{"statusPath":"/v1/jobs/{id}","cancelPath":"/v1/jobs/{id}/cancel","cancelMethod":"POST",
                 "downloadPath":"/v1/jobs/{id}/content","idField":"id","stateField":"status",
                 "states":{"waiting":"queued","working":"running","done":"completed","error":"failed","stop":"cancelled"}}}}
                """;
        store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(sourceDefinition));
        SkillStore.Detail source = store.configureCustomService(store.detail("视频来源"), endpoint, "", false,
                java.util.Map.of("seconds", "4"));
        assertFalse(source.pending());

        FakeSession contact = new FakeSession();
        contact.transcriptMemory = TestChatMemory.messageConversation(activity, restoreConfiguration());
        AtomicInteger submits = new AtomicInteger();
        SkillVideoJobs jobs = SkillVideoJobsTest.chatFixture(activity, submits);
        contact.skillReplies.add(new JSONObject(videoDraftWithoutProvider()).put("name", "视频创作").toString());
        reopen(contact, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT, null, jobs);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("帮我创建一个生成视频技能并内置\n沿用技能：视频来源");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(List.of("视频来源"), store.selectionCatalog().stream().map(SkillSelection.Candidate::name).toList());
        SkillStore.Detail target = store.detail("视频创作");
        assertTrue(target.pending());
        assertEquals("config.seconds", target.manifest().http().request().get("seconds"));
        assertNull(target.customConfiguration());
        AlertDialog settings = openChatSkillSettings("视频创作");
        assertNotNull(settings.getWindow().getDecorView().findViewWithTag("skills.detail.video_facts.open"));
        View settingsBody = settings.getWindow().getDecorView();
        EditText endpointInput = settingsBody.findViewWithTag("skills.detail.custom.endpoint");
        EditText seconds = settingsBody.findViewWithTag("skills.detail.config.seconds");
        assertNotNull(endpointInput);
        assertNotNull(seconds);
        endpointInput.setText(endpoint);
        seconds.setText("4");
        assertTrue(settingsBody.findViewWithTag("skills.detail.custom.save").performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(store.detail("视频创作").pending());
        settings.dismiss();
        submit("请调用「视频创作」：海上日出");
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        org.robolectric.shadows.ShadowAlertDialog shadow = org.robolectric.shadow.api.Shadow.extract(review);
        String disclosure = String.valueOf(shadow.getMessage());
        assertTrue(disclosure.contains("POST https://video-api.example.com/v1/jobs"));
        assertTrue(disclosure.contains("\"seconds\":4"));
        assertTrue(disclosure.contains("按同一任务 ID 查询"));
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(worker.tasks.isEmpty());
        assertEquals(1, contact.transcriptMemory.readTranscript().size());
        SkillVideoJobs.Scope scope = new SkillVideoJobs.Scope(AiSkills.Entry.CONTACT, contact.transcriptMemory.conversationKey());
        assertTrue(jobs.list(scope).isEmpty());
        assertEquals(0, submits.get());

        submit("请调用「视频创作」：海上日出");
        AlertDialog confirmed = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(confirmed.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        SkillResult.Video saved = (SkillResult.Video) contact.transcriptMemory.readTranscript().get(1).artifact;
        assertEquals(saved.requestId(), jobs.list(scope).get(0).localId());
        assertEquals(1, submits.get());

        FakeSession restored = new FakeSession();
        restored.transcriptMemory = TestChatMemory.messageConversation(activity, restoreConfiguration());
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT, null, jobs);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(saved, restored.transcriptMemory.readTranscript().get(1).artifact);
        assertNotNull(messageRow(3).findViewWithTag("ai.chat.skill.video.card"));
        assertEquals(1, jobs.list(scope).size());
        assertEquals(1, submits.get());
        assertTrue(restored.skillInputs.isEmpty());
    }

    @Test public void oldVideoArtifactIsSkippedWhileTextTableAndImageAreRestored() throws Exception {
        FakeSession restored = new FakeSession();
        TestChatMemory memory = new TestChatMemory(activity, restoreConfiguration());
        restored.transcriptMemory = memory;
        assertTrue(memory.append(memory.epoch(), new TestChatMemory.Turn("临时", "", List.of(), "临时")));
        File directory = new File(activity.getFilesDir(), "douai_test_chat");
        File[] histories = directory.listFiles((ignored, name) -> name.endsWith(".bin"));
        assertNotNull(histories);
        assertEquals(1, histories.length);
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(histories[0].toPath()))) {
            output.writeInt(3);
            output.writeInt(4);
            output.writeByte(0);
            writeHistoryText(output, "普通文字");
            writeHistoryText(output, "");
            writeHistoryText(output, "普通回复");
            output.writeInt(0);
            writeLegacyDisplayTurn(output, "/旧视频 测试", "模拟已完成", null,
                    "video", "01994611-ab0e-4cc9-857a-1962a65d2820");
            writeLegacyDisplayTurn(output, "/值班表 测试", "", null, "table",
                    "{\"columns\":[\"姓名\"],\"rows\":[[\"小周\"]]}");
            writeLegacyDisplayTurn(output, "/原图 测试", "", image(), "", "");
        }

        assertEquals(List.of("普通文字"), memory.read().stream().map(turn -> turn.prompt).toList());
        List<TestChatMemory.Turn> saved = memory.readTranscript();
        assertEquals(List.of("普通文字", "/值班表 测试", "/原图 测试"),
                saved.stream().map(turn -> turn.prompt).toList());
        assertTrue(saved.get(1).artifact instanceof SkillResult.Table);
        assertEquals(1, saved.get(2).assistantImages.size());

        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(6, messageCount());
        assertNotNull(messageRow(3).findViewWithTag("ai.chat.table"));
        assertNotNull(messageRow(5).findViewWithTag("ai.chat.image"));
        assertTrue(restored.skillInputs.isEmpty());
        assertTrue(restored.prompts.isEmpty());
    }

    private static void writeLegacyDisplayTurn(DataOutputStream output, String prompt, String answer,
                                               ChatImage image, String type, String artifact) throws IOException {
        output.writeByte(1);
        writeHistoryText(output, prompt);
        writeHistoryText(output, answer);
        writeHistoryText(output, "");
        output.writeInt(image == null ? 0 : 1);
        if (image != null) {
            writeHistoryText(output, image.mimeType);
            byte[] bytes = Base64.getDecoder().decode(image.base64Data);
            output.writeInt(bytes.length);
            output.write(bytes);
        }
        writeHistoryText(output, type);
        if (!type.isEmpty()) writeHistoryText(output, artifact);
    }

    private static void writeHistoryText(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    @Test public void tableSkillSavesBeforeShowingPreviewAndRestoresWithoutReplay() throws Exception {
        FakeSession work = new FakeSession();
        work.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        work.skillReplies.add("""
                {"schemaVersion":4,"name":"值班表","description":"提取排班","documentation":"输入排班安排",
                 "instructions":"提取值班人员","capabilityId":"table_model","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"table","trial":{"input":"示例","expectedType":"table"}}
                """);
        work.skillReplies.add("""
                {"columns":["日期","值班员"],"rows":[["周一","小周"],["周二","小林"],["周三","小李"],
                 ["周四","小袁"],["周五","小米"],["周六","小许"],["周日","小方"]]}
                """);
        reopen(work, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("帮我创建一个输出表格技能并内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals("table", new JSONObject(work.skillInputs.get(0)).getString("requiredOutputType"));

        submit("/值班表 本周值班安排");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(1, work.transcriptMemory.readTranscript().size());
        assertNull(messageRow(3).findViewWithTag("ai.chat.table.export"));
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(SkillAuthoring.TABLE_INSTRUCTION, work.skillInstructions.get(1));
        assertTrue(work.transcriptMemory.read().isEmpty());
        assertEquals(SkillResult.Type.TABLE, work.transcriptMemory.readTranscript().get(1).artifact.type());
        assertNotNull(messageRow(3).findViewWithTag("ai.chat.table.export"));
        ViewGroup preview = messageRow(3).findViewWithTag("ai.chat.table.preview");
        assertEquals(7, ((ViewGroup) preview.getChildAt(0)).getChildCount());

        FakeSession restored = new FakeSession();
        restored.transcriptMemory = new TestChatMemory(activity, restoreConfiguration());
        reopen(restored, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(messageRow(3).findViewWithTag("ai.chat.table.export"));
        assertTrue(restored.skillInputs.isEmpty());

        FakeSession contact = new FakeSession();
        contact.transcriptMemory = TestChatMemory.messageConversation(activity, restoreConfiguration());
        contact.skillReplies.add("{\"columns\":[\"日期\"],\"rows\":[[\"周五\"]]}");
        contact.skillReplies.add("{\"columns\":[\"日期\"],\"rows\":[[]]}");
        reopen(contact, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("/值班表 周五");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertNotNull(messageRow(1).findViewWithTag("ai.chat.table.export"));
        submit("/值班表 拒绝不等长行");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertNull(messageRow(3).findViewWithTag("ai.chat.table.export"));
        assertEquals(1, contact.transcriptMemory.readTranscript().size());
    }

    @Test public void workManifestWaitsForRequiredConfigurationAndRunsFromContact() throws Exception {
        String name = "值班通知";
        String instruction = "按已配置的语气和长度整理值班通知。";
        FakeSession author = new FakeSession();
        author.skillReplies.add("""
                {"schemaVersion":4,"name":"值班通知","description":"整理值班通知","documentation":"输入待整理的内容。",
                 "instructions":"按已配置的语气和长度整理值班通知。","capabilityId":"text_model",
                 "configFields":[{"key":"tone","label":"语气","type":"enum","required":true,"options":["正式","温和"]},
                   {"key":"length","label":"输出长度","type":"integer","required":true,"min":20,"max":200}],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """);
        reopen(author, ChatImageLoader::thumbnail, AiSkills.Entry.WORK);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        submit("帮我创建一个值班通知技能并内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        SkillStore store = SkillStore.forContext(activity);
        assertEquals("The authored manifest with missing required fields must be saved as a pending draft",
                List.of(name), store.pending().stream().map(SkillStore.Pending::name).toList());
        assertTrue(store.list().isEmpty());
        assertTrue(lastAnswer().contains("待配置草稿"));
        assertTrue(lastAnswer().contains("语气"));
        assertTrue(lastAnswer().contains("输出长度"));
        assertFalse(lastAnswer().contains("schemaVersion"));
        assertEquals(List.of(SkillAuthoring.DESIGN_INSTRUCTION), author.skillInstructions);
        assertTrue(author.prompts.isEmpty());
        input.setText("/值班");
        assertNull(root.findViewWithTag("ai.chat.skill." + name));

        AlertDialog settings = openChatSkillSettings(name);
        View detail = settings.getWindow().getDecorView();
        Spinner tone = detail.findViewWithTag("skills.detail.config.tone");
        assertNotNull(tone);
        int formal = -1;
        for (int index = 0; index < tone.getCount(); index++) {
            if ("正式".equals(tone.getItemAtPosition(index).toString())) formal = index;
        }
        assertTrue("The declared enum option must be available in settings", formal >= 0);
        tone.setSelection(formal);
        ShadowLooper.idleMainLooper();
        assertTrue(detail.findViewWithTag("skills.detail.config.save").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.detail(name).pending());
        assertTrue(store.list().isEmpty());
        assertNull(root.findViewWithTag("ai.chat.skill." + name));
        settings.dismiss();

        settings = openChatSkillSettings(name);
        detail = settings.getWindow().getDecorView();
        tone = detail.findViewWithTag("skills.detail.config.tone");
        assertEquals("The partially saved enum value survives reopening settings",
                "正式", String.valueOf(tone.getSelectedItem()));
        EditText length = detail.findViewWithTag("skills.detail.config.length");
        assertNotNull(length);
        length.setText("68");
        assertTrue(detail.findViewWithTag("skills.detail.config.save").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.pending().isEmpty());
        assertEquals(List.of(name), store.list().stream().filter(SkillStore.Installed::enabled)
                .map(SkillStore.Installed::name).toList());
        assertNotNull(root.findViewWithTag("ai.chat.skill." + name));
        settings.dismiss();

        author.skillReplies.add("周二由小周值班。");
        submit("/值班通知 周二小周值班");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("周二由小周值班。", lastAnswer());
        assertTrue(author.prompts.isEmpty());
        assertEquals(List.of(SkillAuthoring.DESIGN_INSTRUCTION, SkillAuthoring.TEXT_INSTRUCTION), author.skillInstructions);
        assertConfiguredNoticeRequest(author.skillInputs.get(1), instruction, "周二小周值班");

        FakeSession contact = new FakeSession();
        contact.skillReplies.add("周三由小林值班。");
        reopen(contact, ChatImageLoader::thumbnail, AiSkills.Entry.CONTACT);
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        input.setText("/值班");
        assertNotNull(root.findViewWithTag("ai.chat.skill." + name));
        submit("/值班通知 周三小林值班");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals("周三由小林值班。", lastAnswer());
        assertTrue(contact.prompts.isEmpty());
        assertEquals(List.of(SkillAuthoring.TEXT_INSTRUCTION), contact.skillInstructions);
        assertConfiguredNoticeRequest(contact.skillInputs.get(0), instruction, "周三小林值班");
    }

    private AlertDialog openChatSkillSettings(String name) {
        assertTrue(findView("ai.chat.skill.settings." + name).performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void assertConfiguredNoticeRequest(String input, String instruction, String argument) throws Exception {
        JSONObject request = new JSONObject(input);
        assertEquals(instruction, request.getString("instruction"));
        assertEquals(argument, request.getString("argument"));
        JSONObject configuration = request.getJSONObject("config");
        assertEquals(2, configuration.length());
        assertEquals("正式", configuration.getString("tone"));
        assertTrue(configuration.get("length") instanceof Number);
        assertEquals(68, configuration.getInt("length"));
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
        assertEquals("image", new JSONObject(sessions.get(0).skillInputs.get(0)).getString("requiredOutputType"));
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
        sessions.get(0).answer = "帮我写一个生图 skills 并内置";
        submit("如何写一个生图 skills 并内置？");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList("如何写一个生图 skills 并内置？"), sessions.get(0).prompts);
        assertEquals("帮我写一个生图 skills 并内置", text("ai.chat.answer").getText().toString());
        assertTrue(SkillStore.forContext(activity).pending().isEmpty());
        assertNull(findView("ai.chat.skill.settings.生图"));
    }

    @Test public void pluralImageCreationRejectsATextDraftWithoutSavingOrRetrying() throws Exception {
        dialog.enableSkills(AiSkills.Entry.WORK);
        FakeSession model = sessions.get(0);
        model.skillReplies.add(new JSONObject(textSkillDraft("保留事实，使用分点格式。"))
                .put("name", "模拟技能").toString());
        submit("帮我写一个生图 skills 并内置");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertTrue(lastAnswer().contains("图片"));
        assertTrue(lastAnswer().contains("文本"));
        assertTrue(lastAnswer().contains("未保存"));
        assertEquals(1, model.skillInputs.size());
        assertEquals("image", new JSONObject(model.skillInputs.get(0)).getString("requiredOutputType"));
        assertTrue(model.prompts.isEmpty());
        SkillStore skills = SkillStore.forContext(activity);
        assertTrue(skills.list().isEmpty());
        assertTrue(skills.pending().isEmpty());
        assertNull(findView("ai.chat.skill.settings.模拟技能"));
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
        reopen(saved, thumbnails, null);
    }

    private void reopen(FakeSession saved, AiTestChatDialog.ThumbnailLoader thumbnails, AiSkills.Entry skills)
            throws Exception {
        reopen(saved, thumbnails, skills, null);
    }

    private void reopen(FakeSession saved, AiTestChatDialog.ThumbnailLoader thumbnails, AiSkills.Entry skills,
                        SkillStore store) throws Exception {
        reopen(saved, thumbnails, skills, store, null);
    }

    private void reopen(FakeSession saved, AiTestChatDialog.ThumbnailLoader thumbnails, AiSkills.Entry skills,
                        SkillStore store, SkillVideoJobs videoJobs) throws Exception {
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        sessions = new ArrayList<>();
        sessions.add(saved);
        AiTestChatDialog.SessionFactory factory = () -> {
            if (sessions.isEmpty()) throw new AssertionError("Unexpected session creation");
            return sessions.get(0);
        };
        dialog = videoJobs == null ? new AiTestChatDialog(activity, restoreConfiguration(), factory, worker, historyWorker, false, thumbnails)
                : new AiTestChatDialog(activity, restoreConfiguration(), factory, worker, historyWorker, thumbnails, videoJobs);
        if (store != null) dialog.enableSkills(skills, null, store);
        else if (skills != null) dialog.enableSkills(skills);
        dialog.show();
        root = dialog.getWindow().getDecorView().findViewWithTag("ai.chat");
        input = root.findViewWithTag("ai.chat.input");
        send = root.findViewWithTag("ai.chat.send");
    }

    private static AiModelConfig restoreConfiguration() throws Exception {
        return AiModelConfig.fromJson(new JSONObject()
                .put("id", "restore-test").put("name", "恢复模型")
                .put("apiEndpoint", "https://example.com/v1").put("modelName", "restore-model"));
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
    private View messageRow(int index) { return messages().getAdapter().getView(index, null, messages()); }
    private String bubble(int index, String tag) {
        return ((TextView) messageRow(index).findViewWithTag(tag)).getText().toString();
    }

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
        TestChatMemory transcriptMemory;
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
            return transcriptMemory == null ? saved : transcriptMemory.read();
        }

        @Override public TestChatMemory transcriptMemory() { return transcriptMemory; }

        @Override public List<DouyinSearch.Result> searchResults() {
            List<DouyinSearch.Result> result = found;
            found = Collections.emptyList();
            return result;
        }

        @Override public void clear() {
            clearCalls++;
            clearOnMain = Looper.myLooper() == Looper.getMainLooper();
            saved = Collections.emptyList();
            if (transcriptMemory != null) transcriptMemory.clear();
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
