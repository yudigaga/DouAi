package com.douai.module.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.res.Resources;
import android.graphics.Insets;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ImageView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;
import com.douai.module.feature.ChatImage;
import com.douai.module.feature.TestChatMemory;
import com.ss.android.ugc.aweme.im.business.chat.feature.title.component.ChatTitleUI;
import com.ss.android.ugc.aweme.im.business.chat.msgcell.common.content.root.text.TextOnlyCellUI;
import com.ss.android.ugc.aweme.im.business.chat.msgcell.vh.textd.IMMsgTextView;
import com.ss.android.ugc.aweme.im.widget.SearchableEditText;
import com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.model.MediaChooseResult;
import com.ss.android.ugc.aweme.im.service.model.MediaModel;

import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowPackageManager;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.CountDownLatch;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeAiConversationTest {
    private HostActivity activity;
    private HostNativeChatViews nativeViews;
    private AiTestChatDialog dialog;
    private ManualExecutor worker;
    private ManualExecutor historyWorker;
    private FakeSession session;
    private View send;
    private ListView messages;

    @Before public void prepareHost() throws Exception {
        HostAdaptation.setCurrentProfile(null);
        NativeInputFixture.reset();
        ChatTitleUI.createCalls = 0;
        TextOnlyCellUI.createCalls = 0;
        org.robolectric.android.controller.ActivityController<HostActivity> controller =
                Robolectric.buildActivity(HostActivity.class);
        activity = controller.get();
        installHostVersion("40.8.0", 400801);
        controller.setup();
        open(new FakeSession());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
    }

    @After public void close() {
        if (dialog != null) dialog.dismiss();
        if (worker != null) worker.shutdownNow();
        if (historyWorker != null) historyWorker.shutdownNow();
        NativeInputFixture.setOmitInput(false);
        if (activity != null) activity.finish();
        HostAdaptation.setCurrentProfile(null);
        ShadowLooper.idleMainLooper();
    }

    @Test public void legacyOriginalProfileKeepsNativeChatAndAlbumUsableAfterVersionSpoofing() throws Exception {
        dialog.dismiss();
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_406);
        installHostVersion("99.0.0", 990001);
        open(new FakeSession());
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0x7f0a203b, nativeViews.root.getId());
        assertEquals(0x7f0a7d32, nativeViews.input.getId());
        assertNotNull(nativeViews.createProfileHeader(nativeViews.messageContainer).avatar);
        assertNotNull(nativeViews.createQuickAction(nativeViews.messageContainer, "legacy action"));
        HostMediaPicker legacy = HostMediaPicker.launch(activity, false, ignored -> { });
        assertNotNull(legacy);
        assertEquals("X.131D", activity.registry.intent.getSerializableExtra("extra_choose_parameters").getClass().getName());
        legacy.close();
    }

    @Test public void hostFactoriesSupplyTheVisibleInputAndBothMessageDirections() {
        assertEquals(1, activity.hostInflations);
        assertEquals(1, NativeInputFixture.createCalls());
        assertEquals(1, ChatTitleUI.createCalls);
        assertTrue(nativeViews.input instanceof SearchableEditText);
        assertSame(nativeViews.input, nativeViews.root.findViewWithTag("ai.chat.input"));
        assertSame(nativeViews.root, dialog.getWindow().getDecorView().findViewWithTag("ai.chat"));
        assertEquals("小助手", nativeViews.title.getText().toString());

        submit("本地 AI 消息");
        nativeViews.input.setText("下一条草稿");
        worker.runNext();
        ShadowLooper.idleMainLooper();

        assertEquals(Collections.singletonList("本地 AI 消息"), session.prompts);
        assertEquals("本地 AI 消息", renderedText(0));
        assertEquals("模型回复", renderedText(1));
        assertEquals("下一条草稿", nativeViews.input.getText().toString());
        assertTrue(TextOnlyCellUI.createCalls >= 2);
    }

    @Test public void nativeBubbleShowsStreamBeforeCompletionWithoutReplacingHostWidgets() throws Exception {
        session.streamStarted = new CountDownLatch(1);
        session.streamContinue = new CountDownLatch(1);
        submit("原生流式回复");
        View originalRow = row(1);
        View originalText = originalRow.findViewById(0x7f0a1dbf);
        View originalComposer = nativeViews.input;
        Thread io = new Thread(worker::runNext, "native-stream-provider");
        io.start();
        try {
            assertTrue(session.streamStarted.await(5, TimeUnit.SECONDS));
            Thread firstChunk = new Thread(() -> session.onPartial.accept("第一段"), "native-stream-chunk");
            firstChunk.start();
            firstChunk.join(5000);
            assertFalse(firstChunk.isAlive());
            ShadowLooper.idleMainLooper();
            assertTrue("Native bubble updates while provider is still running", io.isAlive());
            View rebound = messages.getAdapter().getView(1 + messages.getHeaderViewsCount(), originalRow, messages);
            assertSame(originalRow, rebound);
            assertSame(originalText, rebound.findViewById(0x7f0a1dbf));
            assertEquals("第一段", ((IMMsgTextView) originalText).getText().toString());
            assertSame(originalComposer, nativeViews.input);
            assertEquals(2, messageCount());
            session.answer = "第一段，最终完成";
        } finally {
            session.streamContinue.countDown();
            io.join(5000);
        }
        assertFalse(io.isAlive());
        ShadowLooper.idleMainLooper();
        assertEquals("第一段，最终完成", renderedText(1));
        assertEquals(2, messageCount());
    }

    @Test public void nativeSendStopsBeforeProviderAndClearRejectsAnAlreadyPostedReply() throws Exception {
        submit("停止这条");
        assertTrue(send.getContentDescription().toString().startsWith("停止"));
        send.performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(session.prompts.isEmpty());
        assertEquals("已停止", renderedText(1));
        assertEquals("停止这条", nativeViews.input.getText().toString());

        submit("清空前");
        worker.runNext();
        nativeViews.input.setText("保留草稿");
        clickMoreItem("清空聊天记录");
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(0, messageCount());
        assertEquals("保留草稿", nativeViews.input.getText().toString());
        assertTrue(session.cancelCalls >= 1);
        assertFalse(worker.isShutdown());
    }

    @Test public void nativeHistoryRestoresTextImagesAndDocumentsOffMainBeforeFollowUp() throws Exception {
        ChatImage image = new ChatImage("image/png", new byte[]{1, 2, 3});
        FakeSession saved = new FakeSession();
        saved.saved = Collections.singletonList(new TestChatMemory.Turn("历史问题", "历史文档正文",
                Collections.singletonList(image), "历史回复"));
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        open(saved);
        assertEquals(0, messageCount());
        historyWorker.runAllOnThread();
        assertFalse(saved.historyOnMain);
        ShadowLooper.idleMainLooper();

        assertEquals("历史问题", renderedText(0));
        assertEquals("历史回复", renderedText(1));
        View restored = row(0);
        assertEquals(View.VISIBLE, restored.findViewWithTag("ai.chat.document").getVisibility());
        assertNotNull(restored.findViewWithTag("ai.chat.image"));
        submit("接着聊");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("接着聊"), saved.prompts);
        assertEquals(4, messageCount());
        assertEquals("历史回复", renderedText(1));
    }

    @Test public void nativeImageAndDocumentFailureRetainsAttachmentsForTheSameRetryTurn() {
        ChatImage image = new ChatImage("image/png", new byte[]{1, 2, 3});
        dialog.addImage(image);
        dialog.addDocument("偏好.txt", "附件完整正文");
        session.failure = new java.net.SocketTimeoutException();
        submit("识别附件");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals("识别附件", nativeViews.input.getText().toString());
        assertEquals(View.VISIBLE, nativeViews.root.findViewWithTag("ai.chat.attachments").getVisibility());
        session.failure = null;
        row(1).findViewWithTag("ai.chat.retry").performClick();
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(2, messageCount());
        assertEquals(Collections.singletonList(image), session.images.get(1));
        assertTrue(session.documents.get(1).contains("附件完整正文"));
        assertEquals("模型回复", renderedText(1));
    }

    @Test public void nativeWindowReservesTheGrowingComposerAndAppliesImeInsetsOnce() {
        WindowManager.LayoutParams window = dialog.getWindow().getAttributes();
        assertEquals(Gravity.FILL, window.gravity);
        assertEquals(0f, window.dimAmount, 0f);
        assertEquals(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING,
                window.softInputMode & WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST);
        int width = 420;
        int height = 900;
        measureRoot(width, height);
        int shortComposer = nativeViews.inputContainer.getHeight();
        nativeViews.input.setText("多行草稿\n".repeat(5));
        measureRoot(width, height);
        assertTrue("Native composer must grow for a multiline draft",
                nativeViews.inputContainer.getHeight() > shortComposer);
        assertTrue(bounds(messages).bottom <= bounds(nativeViews.inputContainer).top);

        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(0, 24, 0, 30))
                .setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 280))
                .setVisible(WindowInsets.Type.ime(), true).build();
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(insets);
        measureRoot(width, height);
        assertEquals(24, nativeViews.root.getPaddingTop());
        assertEquals(280, nativeViews.root.getPaddingBottom());
        assertTrue(bounds(nativeViews.inputContainer).bottom <= height - 280);
        assertTrue(bounds(messages).bottom <= bounds(nativeViews.inputContainer).top);
        dialog.getWindow().getDecorView().dispatchApplyWindowInsets(insets);
        assertEquals(280, nativeViews.root.getPaddingBottom());
    }

    @Test public void incompatibleVersionsAndMissingRequiredInputRejectTheNativeContract() throws Exception {
        installHostVersion("40.7.0", 400701);
        assertRejected();
        installHostVersion("40.8.0", 400801);
        NativeInputFixture.setOmitInput(true);
        assertRejected();
    }

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    public void nativeVisualSelfTextUsesOfficialWhiteAndRowsHaveCircularAvatars() {
        submit("你好");
        worker.runNext();
        ShadowLooper.idleMainLooper();
        renderNativePreview();
        IMMsgTextView sent = row(0).findViewById(0x7f0a1dbf);
        assertEquals("Official self-message text must stay white on the native blue bubble",
                Color.WHITE, sent.getRealTextView().getCurrentTextColor());
        for (int index = 0; index < 2; index++) {
            View message = row(index);
            ImageView avatar = message.findViewWithTag("ai.chat.message_avatar");
            assertNotNull("Every native message direction must retain its sender avatar", avatar);
            assertTrue("Native message avatars must be circular", avatar.getClipToOutline());
            measureMessage(message);
            IMMsgTextView text = message.findViewById(0x7f0a1dbf);
            assertTrue(index == 0 ? boundsIn((ViewGroup) message, avatar).left > boundsIn((ViewGroup) message, text).right
                    : boundsIn((ViewGroup) message, avatar).right < boundsIn((ViewGroup) message, text).left);
            GradientDrawable bubble = (GradientDrawable) ((View) text.getParent()).getBackground();
            assertEquals("Native bubble keeps the host's 12dp radius",
                    12f * activity.getResources().getDisplayMetrics().density, bubble.getCornerRadius(), 0f);
            View nativeBubble = (View) text.getParent();
            assertEquals(Math.round(12 * activity.getResources().getDisplayMetrics().density), nativeBubble.getPaddingLeft());
            assertEquals(Math.round(8 * activity.getResources().getDisplayMetrics().density), nativeBubble.getPaddingTop());
            assertEquals("The native inset is applied once around the text", 0, text.getPaddingLeft());
            assertEquals("The native inset is applied once around the text", 0, text.getPaddingTop());
            View moduleWrapper = (View) text.getParent().getParent();
            assertEquals("The module must not add a second bubble padding", 0, moduleWrapper.getPaddingLeft());
            assertNull("The native drawable owns the bubble silhouette", moduleWrapper.getBackground());
        }
    }

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    public void nativeVisualHeaderUsesMoreAndComposerRetainsOfficialEntrances() {
        dialog.setContactEditAction(() -> { });
        View more = nativeViews.root.findViewWithTag("ai.chat.more");
        assertNotNull("The native title bar exposes one more action", more);
        assertEquals(View.VISIBLE, more.getVisibility());
        int visibleHeaderActions = 0;
        for (int index = 0; index < nativeViews.actions.getChildCount(); index++)
            if (nativeViews.actions.getChildAt(index).getVisibility() == View.VISIBLE) visibleHeaderActions++;
        assertEquals("Only the native more icon belongs in the title action slot", 1, visibleHeaderActions);
        assertFalse("Edit must live inside the more menu", visibleInside(nativeViews.actions, "ai.chat.contact_edit"));
        assertFalse("Clear must live inside the more menu", visibleInside(nativeViews.actions, "ai.chat.clear"));
        assertTrue("Header avatar must be circular", nativeViews.avatar.getClipToOutline());
        assertSame("Keep the original send widget in its original native body", nativeViews.send,
                nativeViews.inputContainer.findViewById(0x7f0a4058));
        for (ImageView entrance : new ImageView[]{nativeViews.tools, nativeViews.voice, nativeViews.emoji, nativeViews.plus}) {
            assertEquals("Native composer entrances remain visible while idle", View.VISIBLE, entrance.getVisibility());
            assertNotNull(entrance.getDrawable());
        }
        assertEquals(0x7f0844b1, ((HostIconDrawable) nativeViews.plus.getDrawable()).resource);
        assertTrue(nativeViews.plus.performClick());
        android.app.Dialog menu = ShadowDialog.getLatestDialog();
        assertNotSame(dialog, menu);
        assertTrue(menu.isShowing());
        assertNotNull(findText(menu.getWindow().getDecorView(), "添加附件"));
        assertTrue(menu instanceof android.app.AlertDialog);
        ListView attachmentChoices = ((android.app.AlertDialog) menu).getListView();
        assertEquals(3, attachmentChoices.getAdapter().getCount());
        int optionIndex = 0;
        for (String option : new String[]{"图片", "视频", "文件"})
            assertEquals(option, attachmentChoices.getAdapter().getItem(optionIndex++).toString());
        menu.dismiss();
        measureRoot(432, 960);
        View quick = nativeViews.root.findViewWithTag("ai.chat.quick.0");
        assertNotNull("Native shortcut cards remain visible above the composer", quick);
        assertEquals("Keep the native shortcut card's 32dp height",
                Math.round(32 * activity.getResources().getDisplayMetrics().density), quick.getHeight());
        assertTrue(quick.performClick());
        assertFalse(nativeViews.input.getText().toString().isEmpty());
        assertEquals(View.VISIBLE, nativeViews.send.getVisibility());
        assertEquals(View.GONE, nativeViews.plus.getVisibility());
        assertEquals(View.GONE, nativeViews.voice.getVisibility());
        assertEquals(View.VISIBLE, nativeViews.emoji.getVisibility());
    }

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    public void nativeVisualLongMessagesWrapWithinAvatarMargins() {
        assertEquals("Wide ordinary layout reserves the explicit local outer inset budget", 276,
                nativeViews.messageTextMaxWidth(420));
        assertEquals("A narrow local bubble cannot overflow its outer inset budget", 156,
                nativeViews.messageTextMaxWidth(180));
        NativeInputFixture.setReverseColor(true);
        try {
            assertEquals("Reverse-color layout follows the host screen-minus-136dp text budget", 296,
                    nativeViews.messageTextMaxWidth(420));
            assertEquals(156, nativeViews.messageTextMaxWidth(180));
        } finally { NativeInputFixture.setReverseColor(false); }
        session.answer = "你好，可以直接描述需求。我们会结合上下文分析问题，并保留完整的对话内容。".repeat(5);
        submit("请帮我检查这段较长的需求，确认消息会正常换行，而且不会压住两侧的头像。".repeat(5));
        worker.runNext();
        ShadowLooper.idleMainLooper();
        renderNativePreview("long");
        for (int index = 0; index < 2; index++) {
            View message = row(index);
            measureMessage(message);
            ImageView avatar = message.findViewWithTag("ai.chat.message_avatar");
            IMMsgTextView text = message.findViewById(0x7f0a1dbf);
            assertNotNull(text.getRealTextView().getLayout());
            assertTrue("Long native text must wrap across real measured lines",
                    text.getRealTextView().getLayout().getLineCount() > 1);
            Rect bubbleBounds = boundsIn((ViewGroup) message, (View) text.getParent());
            Rect avatarBounds = boundsIn((ViewGroup) message, avatar);
            assertTrue(bubbleBounds.left >= 0 && bubbleBounds.right <= message.getWidth());
            assertTrue(avatarBounds.left >= 0 && avatarBounds.right <= message.getWidth());
            int gap = Math.round(8 * activity.getResources().getDisplayMetrics().density);
            assertTrue("Long bubbles must reserve the official avatar gap", index == 0
                    ? avatarBounds.left - bubbleBounds.right >= gap
                    : bubbleBounds.left - avatarBounds.right >= gap);
        }
    }

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    public void nativeSelectedVideoKeepsOneCompactPendingCardUntilSend() throws Exception {
        java.lang.reflect.Method selection = AiTestChatDialog.class.getDeclaredMethod("loadMedia",
                android.net.Uri.class, AiTestChatDialog.AttachmentKind.class);
        selection.setAccessible(true);
        selection.invoke(dialog, android.net.Uri.parse("content://media/external/video/media/20"),
                AiTestChatDialog.AttachmentKind.VIDEO);
        assertEquals("Selection must not enqueue video parsing", 0, worker.tasks.size());
        View card = nativeViews.root.findViewWithTag("ai.chat.pending_card");
        assertNotNull(card);
        assertNull(card.findViewWithTag("ai.chat.image"));
        android.widget.HorizontalScrollView strip = nativeViews.root.findViewWithTag("ai.chat.attachments");
        assertEquals(1, ((ViewGroup) strip.getChildAt(0)).getChildCount());
        measureRoot(432, 960);
        assertTrue("The selected video stays in one compact footer card", card.getHeight() <= 64);
        View remove = card.findViewWithTag("ai.chat.remove_pending");
        assertNotNull(remove);
        if (remove instanceof ImageView) {
            assertTrue(remove.getWidth() - remove.getPaddingLeft() - remove.getPaddingRight() <= 24);
            assertTrue(remove.getHeight() - remove.getPaddingTop() - remove.getPaddingBottom() <= 24);
        } else if (remove instanceof android.widget.TextView) {
            assertTrue(((android.widget.TextView) remove).getTextSize() <= 20);
        }
        renderNativePreview("video-pending");
        assertTrue(remove.performClick());
        assertNull(nativeViews.root.findViewWithTag("ai.chat.pending_card"));
        assertEquals(0, worker.tasks.size());
    }

    @Test @Config(qualifiers = "w432dp-h960dp-mdpi")
    public void nativeDialogRoutesOfficialVideoResultsIntoDeferredLocalSendAndIgnoresCancellation() throws Exception {
        dialog.dismiss();
        worker.shutdownNow();
        historyWorker.shutdownNow();
        List<android.net.Uri> reads = new ArrayList<>();
        ChatImage frame = new ChatImage("image/png", new byte[]{1, 2, 3});
        AiTestChatDialog.LoadedMedia prepared = new AiTestChatDialog.LoadedMedia("选中的视频.mp4",
                "仅提供抽样画面，不包含音频。", Collections.singletonList(frame));
        FakeSession selectedSession = new FakeSession();
        open(selectedSession, (context, uri, kind, imageBudget) -> {
            reads.add(uri);
            assertEquals(AiTestChatDialog.AttachmentKind.VIDEO, kind);
            assertEquals(4, imageBudget);
            return prepared;
        });
        historyWorker.runAllOnThread();
        ShadowLooper.idleMainLooper();

        chooseVideoFromNativeComposer();
        assertTrue(HostMediaPicker.isPending(activity));
        assertEquals("com.ss.android.ugc.aweme.im.business.mediaselectpage.choose.MediaChooseActivity",
                activity.registry.intent.getComponent().getClassName());
        Object parameters = activity.registry.intent.getSerializableExtra("extra_choose_parameters");
        assertEquals(0x10, parameters.getClass().getField("a").getInt(parameters));
        activity.registry.dispatchResult(activity.registry.requestCode, 2011, new Intent());
        assertFalse(HostMediaPicker.isPending(activity));
        assertNull(nativeViews.root.findViewWithTag("ai.chat.pending_card"));
        assertTrue(reads.isEmpty());
        assertEquals(0, worker.tasks.size());

        chooseVideoFromNativeComposer();
        MediaModel model = new MediaModel();
        model.type = 1;
        model.filePath = "/future-host-cache/选中的视频.mp4";
        model.relativePath = "content://media/external/video/media/30";
        model.mimeType = "video/mp4";
        activity.registry.dispatchResult(activity.registry.requestCode, 2012,
                new Intent().putExtra("extra_choose_result",
                        new MediaChooseResult(new ArrayList<>(Collections.singletonList(model)))));
        assertFalse(HostMediaPicker.isPending(activity));
        assertEquals(0, worker.tasks.size());
        assertTrue(reads.isEmpty());
        View card = nativeViews.root.findViewWithTag("ai.chat.pending_card");
        assertNotNull(card);
        assertEquals("选中的视频.mp4", ((android.widget.TextView) card.findViewWithTag("ai.chat.pending_name")).getText().toString());
        assertNull(card.findViewWithTag("ai.chat.image"));
        renderNativePreview("official-video-pending");
        submit("只分析选中的视频");
        nativeViews.input.setText("下一条独立草稿");
        assertTrue(reads.isEmpty());
        worker.runAllOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(android.net.Uri.parse(model.relativePath)), reads);
        assertEquals(Collections.singletonList("只分析选中的视频"), selectedSession.prompts);
        assertEquals(prepared.images, selectedSession.images.get(0));
        assertTrue(selectedSession.documents.get(0).contains(prepared.text));
        assertEquals("下一条独立草稿", nativeViews.input.getText().toString());
        assertEquals(2, messageCount());
    }

    private void chooseVideoFromNativeComposer() {
        assertTrue(nativeViews.plus.performClick());
        android.app.AlertDialog menu = (android.app.AlertDialog) ShadowDialog.getLatestDialog();
        ListView choices = menu.getListView();
        assertEquals("视频", choices.getAdapter().getItem(1));
        choices.performItemClick(null, 1, choices.getAdapter().getItemId(1));
    }

    private void clickMoreItem(String label) {
        nativeViews.more.performClick();
        android.app.Dialog menu = ShadowDialog.getLatestDialog();
        assertNotSame(dialog, menu);
        if (menu instanceof android.app.AlertDialog) {
            ListView choices = ((android.app.AlertDialog) menu).getListView();
            for (int index = 0; index < choices.getAdapter().getCount(); index++) {
                if (label.equals(choices.getAdapter().getItem(index).toString())) {
                    choices.performItemClick(null, index, choices.getAdapter().getItemId(index));
                    return;
                }
            }
        }
        View action = findText(menu.getWindow().getDecorView(), label);
        assertNotNull("More menu must expose " + label, action);
        assertTrue(action.performClick());
    }

    private static View findText(View root, String text) {
        if (root instanceof android.widget.TextView && text.contentEquals(((android.widget.TextView) root).getText())) return root;
        if (root instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) root).getChildCount(); index++) {
            View found = findText(((ViewGroup) root).getChildAt(index), text);
            if (found != null) return found;
        }
        return null;
    }

    private static void measureMessage(View message) {
        message.measure(View.MeasureSpec.makeMeasureSpec(432, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(960, View.MeasureSpec.AT_MOST));
        message.layout(0, 0, message.getMeasuredWidth(), message.getMeasuredHeight());
    }

    private static Rect boundsIn(ViewGroup root, View child) {
        Rect result = new Rect(0, 0, child.getWidth(), child.getHeight());
        root.offsetDescendantRectToMyCoords(child, result);
        return result;
    }

    private static boolean visibleInside(ViewGroup parent, String tag) {
        View found = parent.findViewWithTag(tag);
        return found != null && found.getVisibility() == View.VISIBLE;
    }

    /** A layout preview using test-owned host resources, never real-device evidence. */
    private void renderNativePreview() { renderNativePreview(""); }

    private void renderNativePreview(String variant) {
        String output = System.getenv("DOUAI_NATIVE_CHAT_SCREENSHOTS");
        if (output == null || output.isEmpty()) return;
        java.io.File folder = new java.io.File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        int width = 432;
        int height = 960;
        measureRoot(width, height);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        nativeViews.root.draw(new Canvas(bitmap));
        String label = System.getenv("DOUAI_NATIVE_CHAT_PREVIEW_LABEL");
        if (label == null || label.isEmpty()) label = "current";
        if (!variant.isEmpty()) label += "-" + variant;
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(
                new java.io.File(folder, "local-native-host-fixture-" + label + ".png"))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private void open(FakeSession selected) throws Exception { open(selected, null); }

    private void open(FakeSession selected, AiTestChatDialog.MediaLoader mediaLoader) throws Exception {
        session = selected;
        nativeViews = HostNativeChatViews.create(activity);
        worker = new ManualExecutor();
        historyWorker = new ManualExecutor();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "native-contact")
                .put("name", "模型配置").put("messageContactEnabled", true)
                .put("messageContactName", "小助手").put("apiEndpoint", "https://example.com/v1")
                .put("modelName", "test-model"));
        dialog = mediaLoader == null ? new AiTestChatDialog(activity, config, () -> selected, worker, historyWorker,
                false, image -> null, true, nativeViews)
                : new AiTestChatDialog(activity, config, () -> selected, worker, historyWorker,
                false, image -> null, true, nativeViews, mediaLoader);
        dialog.setOwnerActivity(activity);
        dialog.show();
        send = nativeViews.root.findViewWithTag("ai.chat.send");
        messages = nativeViews.root.findViewWithTag("ai.chat.messages");
    }

    private void installHostVersion(String version, long code) {
        PackageInfo info = new PackageInfo();
        info.packageName = activity.getPackageName();
        info.applicationInfo = activity.getApplicationInfo();
        info.versionName = version;
        info.setLongVersionCode(code);
        HostProfileFixture.attachOriginalApk(info);
        ((ShadowPackageManager) Shadow.extract(activity.getPackageManager())).installPackage(info);
    }

    private void assertRejected() {
        try {
            HostNativeChatViews.create(activity);
            fail("Unsupported host contracts must fail before opening a native conversation");
        } catch (ReflectiveOperationException expected) { }
    }

    private void submit(String prompt) {
        nativeViews.input.setText(prompt);
        assertTrue(send.isEnabled());
        assertTrue(send.performClick());
    }

    private View row(int index) {
        return messages.getAdapter().getView(index + messages.getHeaderViewsCount(), null, messages);
    }

    private int messageCount() {
        return messages.getAdapter().getCount() - messages.getHeaderViewsCount() - messages.getFooterViewsCount();
    }

    private String renderedText(int index) {
        IMMsgTextView text = row(index).findViewById(0x7f0a1dbf);
        assertNotNull("The row must contain the host IM renderer", text);
        return text.getText().toString();
    }

    private void measureRoot(int width, int height) {
        nativeViews.root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        nativeViews.root.layout(0, 0, width, height);
    }

    private Rect bounds(View child) {
        Rect result = new Rect(0, 0, child.getWidth(), child.getHeight());
        nativeViews.root.offsetDescendantRectToMyCoords(child, result);
        return result;
    }

    public static final class HostActivity extends Activity {
        int hostInflations;
        final MediaRegistry registry = new MediaRegistry();
        private LayoutInflater hostInflater;
        private Resources hostResources;

        @Override public String getPackageName() { return "com.ss.android.ugc.aweme"; }
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }

        @Override public Resources getResources() {
            if (hostResources == null) hostResources = new HostResources(this, super.getResources());
            return hostResources;
        }

        @Override public Object getSystemService(String name) {
            if (!Context.LAYOUT_INFLATER_SERVICE.equals(name)) return super.getSystemService(name);
            if (hostInflater == null) hostInflater = new HostLayoutInflater(this,
                    (LayoutInflater) super.getSystemService(name));
            return hostInflater;
        }
    }

    private static final class MediaRegistry extends ActivityResultRegistry {
        int requestCode;
        Intent intent;
        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract,
                I input, ActivityOptionsCompat options) {
            requestCode = code;
            intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }

    /** Keep the strict recovered drawable IDs while supplying test-owned pixels. */
    private static final class HostResources extends Resources {
        private final Context context;
        HostResources(Context context, Resources base) {
            super(base.getAssets(), base.getDisplayMetrics(), base.getConfiguration());
            this.context = context;
        }
        @Override public int getColor(int id, Theme theme) {
            if (id == 0x7f060094) return Color.WHITE;
            if (id == 0x7f060182) return 0xff161823;
            return super.getColor(id, theme);
        }
        @Override public Drawable getDrawable(int id, Theme theme) {
            if ((id >>> 24) != 0x7f) return super.getDrawable(id, theme);
            id = HostResourceProfile.canonicalId(HostProfileFixture.profile(context), id);
            if (id == 0x7f083b0a || id == 0x7f083b0d) {
                GradientDrawable shape = new GradientDrawable();
                shape.setColor(id == 0x7f083b0d ? 0xff6b90f5 : 0xffdddddd);
                shape.setCornerRadius(12f * getDisplayMetrics().density);
                return shape;
            }
            switch (id) {
                case 0x7f08430a: case 0x7f084486: case 0x7f0844a9: case 0x7f0844b1:
                case 0x7f0845cd: case 0x7f0845d2: case 0x7f08439b: case 0x7f08415e:
                    return new HostIconDrawable(id);
            }
            return super.getDrawable(id, theme);
        }
    }

    /** Test-owned icon strokes for readable local previews; the APK supplies production pixels. */
    private static final class HostIconDrawable extends Drawable {
        final int resource;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        HostIconDrawable(int resource) { this.resource = resource; }
        @Override public int getIntrinsicWidth() { return 32; }
        @Override public int getIntrinsicHeight() { return 32; }
        @Override public void draw(Canvas canvas) {
            int checkpoint = canvas.save();
            canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 32f, getBounds().height() / 32f);
            paint.setColor(0xff161823); paint.setStrokeWidth(2f); paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
            if (resource == 0x7f0844b1 || resource == 0x7f0844a9 || resource == 0x7f084486) {
                canvas.drawCircle(16, 16, 13, paint);
                if (resource == 0x7f0844b1) { canvas.drawLine(9, 16, 23, 16, paint); canvas.drawLine(16, 9, 16, 23, paint); }
                else if (resource == 0x7f0844a9) {
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(11, 12, 1.8f, paint); canvas.drawCircle(21, 12, 1.8f, paint);
                    paint.setStyle(Paint.Style.STROKE); canvas.drawArc(new RectF(9, 12, 23, 24), 15, 150, false, paint);
                } else {
                    paint.setStyle(Paint.Style.FILL); canvas.drawCircle(11, 16, 1.5f, paint); paint.setStyle(Paint.Style.STROKE);
                    canvas.drawArc(new RectF(9, 10, 20, 22), -65, 130, false, paint);
                    canvas.drawArc(new RectF(8, 6, 26, 26), -65, 130, false, paint);
                }
            } else if (resource == 0x7f08430a) {
                canvas.drawRoundRect(3, 3, 12, 12, 2, 2, paint); canvas.drawRoundRect(3, 19, 12, 28, 2, 2, paint);
                canvas.drawRoundRect(19, 19, 28, 28, 2, 2, paint); canvas.drawRoundRect(19, 3, 28, 12, 2, 2, paint);
            } else if (resource == 0x7f0845d2) {
                paint.setStyle(Paint.Style.FILL); for (int x : new int[]{6, 16, 26}) canvas.drawCircle(x, 16, 2.2f, paint);
            } else if (resource == 0x7f0845cd) {
                canvas.drawLine(21, 5, 10, 16, paint); canvas.drawLine(10, 16, 21, 27, paint);
            } else if (resource == 0x7f08439b) {
                canvas.drawLine(9, 9, 23, 23, paint); canvas.drawLine(23, 9, 9, 23, paint);
            } else {
                paint.setColor(0xff547cff); Path path = new Path(); path.moveTo(4, 4); path.lineTo(29, 16);
                path.lineTo(4, 28); path.lineTo(8, 17); path.lineTo(22, 16); path.lineTo(8, 15); path.close();
                paint.setStyle(Paint.Style.FILL); canvas.drawPath(path, paint);
            }
            canvas.restoreToCount(checkpoint);
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }

    /** The APK XML is represented by its recovered native slots, without packaging host resources. */
    private static final class HostLayoutInflater extends LayoutInflater {
        private final LayoutInflater platform;
        HostLayoutInflater(Context context, LayoutInflater platform) { super(context); this.platform = platform; }
        @Override public LayoutInflater cloneInContext(Context context) {
            return new HostLayoutInflater(context, platform.cloneInContext(context));
        }

        @Override public View inflate(int resource, ViewGroup parent, boolean attach) {
            if (resource != 0x7f0d194a && resource != 0x7f0d194b && resource != 0x7f0d194c
                    && resource != 0x7f0d1954 && resource != 0x7f0d1955 && resource != 0x7f0d1956) {
                return platform.inflate(resource, parent, attach);
            }
            resource = HostResourceProfile.canonicalId(HostProfileFixture.profile(getContext()), resource);
            if (resource != 0x7f0d194a && resource != 0x7f0d194b && resource != 0x7f0d194c)
                return platform.inflate(resource, parent, attach);
            ((HostActivity) getContext()).hostInflations++;
            FrameLayout root = new FrameLayout(getContext());
            root.setId(HostProfileFixture.id(getContext(), 0x7f0a2042));
            LinearLayout column = new LinearLayout(getContext());
            column.setId(HostProfileFixture.id(getContext(), 0x7f0a2063));
            column.setOrientation(LinearLayout.VERTICAL);
            root.addView(column, new FrameLayout.LayoutParams(-1, -1));
            for (int id : new int[]{0x7f0a4258, 0x7f0a202d, 0x7f0a6444}) {
                FrameLayout slot = new FrameLayout(getContext());
                slot.setId(HostProfileFixture.id(getContext(), id));
                column.addView(slot, new LinearLayout.LayoutParams(-1, -2));
            }
            View divider = new View(getContext());
            divider.setId(HostProfileFixture.id(getContext(), 0x7f0a205b));
            divider.setBackgroundColor(0x0d161823);
            root.addView(divider, new FrameLayout.LayoutParams(-1, 1));
            if (attach && parent != null) parent.addView(root);
            return root;
        }
    }

    private static final class FakeSession implements AiTestChatDialog.Session {
        final List<String> prompts = new ArrayList<>();
        final List<List<ChatImage>> images = new ArrayList<>();
        final List<String> documents = new ArrayList<>();
        List<TestChatMemory.Turn> saved = Collections.emptyList();
        Exception failure;
        String answer = "模型回复";
        int cancelCalls;
        boolean historyOnMain;
        Consumer<String> onPartial;
        CountDownLatch streamStarted;
        CountDownLatch streamContinue;

        @Override public String reply(String prompt, List<ChatImage> attachments) throws Exception {
            prompts.add(prompt);
            images.add(new ArrayList<>(attachments));
            if (failure != null) throw failure;
            return answer;
        }

        @Override public String reply(String prompt, List<ChatImage> attachments, String document) throws Exception {
            documents.add(document);
            return reply(prompt, attachments);
        }
        @Override public String replyStreaming(String prompt, List<ChatImage> attachments, String document,
                                               Consumer<String> progress) throws Exception {
            onPartial = progress;
            if (streamStarted != null) streamStarted.countDown();
            if (streamContinue != null && !streamContinue.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Native streaming test was not released");
            }
            return reply(prompt, attachments, document);
        }
        @Override public List<TestChatMemory.Turn> savedTurns() {
            historyOnMain = Looper.myLooper() == Looper.getMainLooper();
            return saved;
        }
        @Override public void clear() { saved = Collections.emptyList(); }
        @Override public void cancel() { cancelCalls++; }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runNext() { Runnable next = tasks.poll(); if (next != null) next.run(); }
        void runAllOnThread() throws InterruptedException {
            Runnable next;
            while ((next = tasks.poll()) != null) {
                Thread thread = new Thread(next, "native-chat-test-history");
                thread.start();
                thread.join(5000);
                assertFalse("History worker must finish", thread.isAlive());
            }
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; tasks.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
