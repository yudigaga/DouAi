package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.TextView;

import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationDispatcherTest {
    private Activity activity;
    private AutomationDispatcher dispatcher;
    private AutomationHistoryStore history;
    private ManualExecutor worker;
    private AtomicInteger sends;
    private final Map<String, String> preparedKinds = new HashMap<>();

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .edit().clear().commit();
        RuntimeEnvironment.getApplication().getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE)
                .edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        worker = new ManualExecutor();
        dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        dispatcher.onActivityResumed(activity);
        history = new AutomationHistoryStore(activity);
        sends = new AtomicInteger();
    }

    @After public void close() {
        dispatcher.close();
        worker.runAll();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void sourceReasonAmendmentPreservesDispatcherMetadataAndRejectsAStaleStatus() {
        dispatcher.setOptions(options(false, true));
        AutomationHistoryStore.Entry original = new AutomationHistoryStore.Entry("reason-patch", "reply", "u", "user",
                "input", "output", "model", "cancelled", 1, 2, 2, true,
                AutomationHistoryStore.DELIVERY_VOICE).withDiagnostics(AutomationHistoryStore.Reason.CANCELLED,
                AutomationHistoryStore.Stage.SEND, 123, 23, 34, 45);
        dispatcher.recordOutcome(original);
        AutomationHistoryStore.Entry source = new AutomationHistoryStore.Entry("reason-patch", "reply", "other", "other",
                "", "", "", "cancelled", 1, 3).withDiagnostics(AutomationHistoryStore.Reason.LEDGER_WRITE_FAILED,
                AutomationHistoryStore.Stage.QUEUE, -1, -1, -1, -1);
        dispatcher.recordOutcomeReason(source);
        worker.runAll();
        AutomationHistoryStore.Entry amended = history.read("reply").get(0);
        assertEquals(AutomationHistoryStore.Reason.LEDGER_WRITE_FAILED, amended.reason);
        assertEquals(AutomationHistoryStore.Stage.SEND, amended.stage);
        assertEquals(123, amended.queueMillis); assertEquals(23, amended.mediaMillis);
        assertEquals(34, amended.generationMillis); assertEquals(45, amended.sendMillis);
        assertEquals("output", amended.output); assertEquals(2, amended.requestImageCount);
        assertTrue(amended.modelRequestSent); assertEquals(AutomationHistoryStore.DELIVERY_VOICE, amended.deliveryMode);
        dispatcher.recordOutcome(new AutomationHistoryStore.Entry("reason-patch", "reply", "u", "user",
                "input", "output", "model", "sent", 1, 4).withDiagnostics(AutomationHistoryStore.Reason.NONE,
                AutomationHistoryStore.Stage.COMPLETE, 123, 23, 34, 55));
        dispatcher.recordOutcomeReason(source);
        worker.runAll();
        AutomationHistoryStore.Entry confirmed = history.read("reply").get(0);
        assertEquals("sent", confirmed.status);
        assertEquals(AutomationHistoryStore.Reason.NONE, confirmed.reason);
        assertEquals(AutomationHistoryStore.Stage.COMPLETE, confirmed.stage);
        assertEquals(55, confirmed.sendMillis);
    }

    @Test public void noNoticeDispatchesImmediatelyAndRecordsOneConfirmedOutcome() {
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "one", "u1", "用户甲", "收到消息", "生成回复", "model",
                () -> true, result -> {
                    sends.incrementAndGet();
                    result.accept("sent");
                    result.accept("failed");
                });
        worker.runAll();
        assertEquals(1, sends.get());
        assertNull(notice("one"));
        assertEquals(1, history.read("reply").size());
        assertEquals("sent", history.read("reply").get(0).status);
        assertEquals("收到消息", history.read("reply").get(0).input);
        assertEquals("生成回复", history.read("reply").get(0).output);
        prepare("reply", "one", () -> true);
        worker.runAll();
        assertEquals(1, sends.get());
    }

    @Test public void diagnosticsSurviveDispatcherTransitionsAndLateConfirmationDoesNotRetry() {
        dispatcher.setOptions(options(false, true));
        java.util.concurrent.atomic.AtomicReference<Consumer<String>> callback = new java.util.concurrent.atomic.AtomicReference<>();
        dispatcher.prepare(new AutomationHistoryStore.Entry("diagnostic", "reply", "u", "用户", "消息", "回复",
                "model", "pending", 1, 1).withDiagnostics(AutomationHistoryStore.Reason.NONE,
                AutomationHistoryStore.Stage.GENERATION, 11, 22, 33, -1)
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-1", "message-1")), () -> true, result -> {
            sends.incrementAndGet();
            callback.set(result);
        }, ignored -> { });
        worker.runAll();
        AutomationHistoryStore.Entry sending = history.read("reply").get(0);
        assertEquals("sending", sending.status);
        assertEquals(AutomationHistoryStore.Stage.SEND, sending.stage);
        assertEquals(22, sending.mediaMillis);
        assertEquals(33, sending.generationMillis);
        assertEquals("100", sending.location.accountId);
        assertEquals("conversation-1", sending.location.conversationId);
        assertTrue(sending.queueMillis >= 11);
        advance(30);
        worker.runAll();
        AutomationHistoryStore.Entry submitted = history.read("reply").get(0);
        assertEquals("submitted", submitted.status);
        assertEquals(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, submitted.reason);
        assertTrue(submitted.sendMillis >= 30000);
        assertEquals(1, sends.get());
        callback.get().accept("sent");
        worker.runAll();
        AutomationHistoryStore.Entry sent = history.read("reply").get(0);
        assertEquals("sent", sent.status);
        assertEquals(AutomationHistoryStore.Reason.NONE, sent.reason);
        assertEquals(AutomationHistoryStore.Stage.COMPLETE, sent.stage);
        assertEquals(22, sent.mediaMillis);
        assertEquals(33, sent.generationMillis);
        assertEquals("conversation-1", sent.location.conversationId);
        assertEquals("message-1", sent.location.messageId);
        assertTrue(sent.sendMillis >= submitted.sendMillis);
        assertEquals(1, sends.get());
    }

    @Test public void failedSendRecordsAControlledReasonWithoutCopyingExceptionText() {
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "diagnostic-failure", "u", "用户", "消息", "回复", "model",
                () -> true, result -> { throw new IllegalStateException("Bearer private-token https://secret.example"); });
        worker.runAll();
        AutomationHistoryStore.Entry failed = history.read("reply").get(0);
        assertEquals("failed", failed.status);
        assertEquals(AutomationHistoryStore.Reason.SEND_FAILED, failed.reason);
        assertEquals(AutomationHistoryStore.Stage.SEND, failed.stage);
        assertTrue(failed.sendMillis >= 0);
        String stored = activity.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "");
        assertFalse(stored.contains("private-token"));
        assertFalse(stored.contains("secret.example"));
    }

    @Test public void noticeTimesOutAndStillSubmitsWithoutAUserClick() {
        dispatcher.setOptions(options(true, true));
        prepare("comment", "comment1", () -> true);
        worker.runAll();
        assertNotNull(notice("comment1"));
        assertEquals(0, sends.get());
        assertEquals("pending", history.read("comment").get(0).status);
        advance(2);
        worker.runAll();
        assertEquals(0, sends.get());
        advance(1);
        worker.runAll();
        assertEquals(1, sends.get());
        assertNull(notice("comment1"));
        assertEquals("submitted", history.read("comment").get(0).status);
    }

    @Test public void missingNoticeDurationDefaultsToThreeSecondsAndExplicitEightRemainsUnchanged() {
        Map<String, Object> defaults = base(true, true);
        defaults.remove("reply_notice_seconds");
        defaults.remove("comment_notice_seconds");
        dispatcher.setOptions(new LocalOptions(defaults));
        prepare("reply", "default-cancel", () -> true);
        prepare("comment", "default-send", () -> true);
        worker.runAll();
        assertEquals(3, replyRing().getRetriesLeft());
        advance(2);
        worker.runAll();
        assertEquals(0, sends.get());
        assertTrue(notice("default-cancel").performLongClick());
        advance(1);
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("cancelled", history.read("reply").get(0).status);
        assertEquals("submitted", history.read("comment").get(0).status);

        Map<String, Object> saved = base(true, true);
        saved.put("reply_notice_seconds", "8");
        saved.put("comment_notice_seconds", "8");
        dispatcher.setOptions(new LocalOptions(saved));
        prepare("reply", "saved-eight", () -> true);
        assertEquals(8, replyRing().getRetriesLeft());
        advance(7);
        worker.runAll();
        assertEquals(1, sends.get());
        advance(1);
        worker.runAll();
        assertEquals(2, sends.get());
        assertEquals("sent", history.read("reply").get(0).status);
        assertEquals("8", saved.get("reply_notice_seconds"));
        assertEquals("8", saved.get("comment_notice_seconds"));
    }

    @Test public void longPressCancelsAndNeverSendsWhenTheOldTimeoutFires() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "cancel", () -> true);
        assertTrue(notice("cancel").performLongClick());
        advance(20);
        worker.runAll();
        assertEquals(0, sends.get());
        assertNull(notice("cancel"));
        assertEquals("cancelled", history.read("reply").get(0).status);
    }

    @Test public void openingDetailsPausesTimeoutAndExplicitSendRunsOnce() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "expand", () -> true);
        notice("expand").performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertEquals("原始内容", ((TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.input")).getText().toString());
        assertEquals("生成内容", ((TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.output")).getText().toString());
        advance(90);
        worker.runAll();
        assertEquals(0, sends.get());
        assertTrue(dialog.isShowing());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("sent", history.read("reply").get(0).status);
    }

    @Test public void dismissingDetailsCancelsInsteadOfSending() {
        dispatcher.setOptions(options(true, true));
        prepare("comment", "dismiss", () -> true);
        notice("dismiss").performClick();
        ShadowAlertDialog.getLatestAlertDialog().dismiss();
        ShadowLooper.idleMainLooper();
        advance(20);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    @Test public void replyDisabledBeforeQueuedSendCancelsWithoutCallingNativeAction() {
        dispatcher.setOptions(options(false, true));
        prepare("reply", "disabled", () -> true);
        Map<String, Object> disabled = base(false, true);
        disabled.put("native_auto_reply", false);
        dispatcher.setOptions(new LocalOptions(disabled));
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("reply").get(0).status);
    }

    @Test public void commentAndReplyDispatchWithInteractionAutomationOff() {
        Map<String, Object> independent = base(false, true);
        independent.put("automation_enabled", false);
        dispatcher.setOptions(new LocalOptions(independent));
        prepare("comment", "independent-comment", () -> true);
        prepare("reply", "independent-reply", () -> true);
        worker.runAll();
        assertEquals(2, sends.get());
    }

    @Test public void invalidTargetAtTimeoutCancelsAndDoesNotSend() {
        dispatcher.setOptions(options(true, true));
        AtomicBoolean current = new AtomicBoolean(true);
        prepare("comment", "moved", current::get);
        current.set(false);
        advance(3);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    @Test public void disablingHistoryStopsQueuedWritesAndKeepsOldRecordsAvailable() {
        dispatcher.setOptions(options(false, true));
        prepare("reply", "old", () -> true);
        worker.runAll();
        assertEquals(1, history.read("reply").size());
        prepare("reply", "new", () -> true);
        dispatcher.setOptions(options(false, false));
        worker.runAll();
        assertEquals(2, sends.get());
        assertEquals(1, history.read("reply").size());
        assertEquals("old", history.read("reply").get(0).id);
    }

    @Test public void historyIsDisabledByDefaultAndSendFailureIsNotRecordedAsSuccess() {
        Map<String, Object> defaults = new HashMap<>();
        defaults.put("automation_enabled", true);
        defaults.put("native_auto_reply", true);
        dispatcher.setOptions(new LocalOptions(defaults));
        prepare("reply", "private", () -> true);
        worker.runAll();
        assertEquals(1, sends.get());
        assertTrue(history.read("reply").isEmpty());
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "failure", "u", "用户", "内容", "生成内容", "model",
                () -> true, callback -> { throw new IllegalStateException("native failure"); });
        worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
    }

    @Test public void cancelKindOnlyCancelsTheSelectedFeature() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "reply", () -> true);
        prepare("comment", "comment", () -> true);
        dispatcher.cancelKind("reply");
        advance(3);
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("cancelled", history.read("reply").get(0).status);
        assertEquals("submitted", history.read("comment").get(0).status);
    }

    @Test public void pauseReleasesActivityViewsAndResumeShowsTheRemainingNotice() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "resume", () -> true);
        dispatcher.onActivityPaused(activity);
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.notices"));
        advance(1);
        dispatcher.onActivityResumed(activity);
        assertNotNull(notice("resume"));
        advance(2);
        worker.runAll();
        assertEquals(1, sends.get());
    }

    @Test public void closeCancelsPendingNoticeAndGenerationFailureCanBeReviewed() {
        dispatcher.setOptions(options(true, true));
        dispatcher.reportFailure("reply", "generation", "u", "用户", "输入", "model");
        prepare("comment", "close", () -> true);
        dispatcher.close();
        advance(3);
        worker.runAll();
        assertEquals(0, sends.get());
        assertEquals("failed", history.read("reply").get(0).status);
        assertEquals("cancelled", history.read("comment").get(0).status);
    }

    private void prepare(String kind, String id, java.util.function.BooleanSupplier valid) {
        preparedKinds.put(id, kind);
        dispatcher.prepare(kind, id, "target", "目标", "原始内容", "生成内容", "model", valid,
                callback -> { sends.incrementAndGet(); callback.accept("reply".equals(kind) ? "sent" : "submitted"); });
    }

    @Test public void silentNativeCallbackBecomesSubmittedAndDoesNotClaimSuccess() {
        dispatcher.setOptions(options(false, true));
        dispatcher.prepare("reply", "unknown", "u", "用户", "消息", "回复", "model", () -> true, result -> sends.incrementAndGet());
        worker.runAll();
        assertEquals("sending", history.read("reply").get(0).status);
        advance(30); worker.runAll();
        assertEquals("submitted", history.read("reply").get(0).status);
        assertEquals(1, sends.get());
    }

    @Test public void generationFailureShowsTimedNoticeOnlyWhenItsSwitchIsEnabled() {
        dispatcher.setOptions(options(true, true));
        dispatcher.reportFailure("reply", "failure", "target", "用户", "消息", "model");
        worker.runAll();
        assertEquals("failed", replyRing().getPhase());
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.failure.failure"));
        assertEquals("failed", history.read("reply").get(0).status);
        advance(3); worker.runAll();
        assertNull(replyRing());
        assertEquals(0, sends.get());
    }

    @Test public void lateNativeFailureReplacesUnconfirmedOutcomeAndFurtherCallbacksAreIgnored() {
        dispatcher.setOptions(options(false, true));
        java.util.concurrent.atomic.AtomicReference<Consumer<String>> callback = new java.util.concurrent.atomic.AtomicReference<>();
        dispatcher.prepare("reply", "late", "u", "用户", "消息", "回复", "model", () -> true, callback::set);
        worker.runAll(); advance(30); worker.runAll();
        assertEquals("submitted", history.read("reply").get(0).status);
        callback.get().accept("failed"); worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
        callback.get().accept("sent"); worker.runAll();
        assertEquals("failed", history.read("reply").get(0).status);
    }

    @Test @Config(qualifiers = "w296dp-h560dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void narrowNoticeHasBoundedTextAndCanRenderPreview() { layoutPreview(); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void landscapeNoticeRemainsBoundedAndScrollable() { layoutPreview(); }

    @Test public void replyPreparationIsVisibleAndReusesItsRingForTheOriginalPreview() {
        dispatcher.setOptions(options(true, false));
        AtomicInteger cancelled = new AtomicInteger();
        AutomationHistoryStore.Entry entry = replyPreparation("early");
        dispatcher.trackReply(entry, "media", () -> true, cancelled::incrementAndGet);
        CommentRingView preparing = replyRing();
        assertNotNull(preparing);
        assertEquals("media", preparing.getPhase());
        advance(10);
        worker.runAll();
        assertEquals(0, sends.get());
        dispatcher.trackReply(entry, "speech", () -> true, cancelled::incrementAndGet);
        assertSame(preparing, replyRing());
        assertEquals("speech", replyRing().getPhase());
        assertTrue(replyRing().getContentDescription().toString().contains("语音合成"));
        dispatcher.prepare(entry, () -> true, result -> {
            sends.incrementAndGet(); result.accept("sent");
        }, ignored -> { });
        assertSame(preparing, replyRing());
        assertEquals("preview", replyRing().getPhase());
        assertEquals(1, replyRing().getQueueCount());
        advance(2); worker.runAll(); assertEquals(0, sends.get());
        advance(1); worker.runAll(); assertEquals(1, sends.get());
        assertNull(replyRing());
        assertEquals(0, cancelled.get());
    }

    @Test public void commentReplyPreparationIsVisibleBeforeContentAndReusesTheReadyPreview() {
        Map<String, Object> values = base(true, true);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        AutomationHistoryStore.Entry entry = commentReplyPreparation("comment-early");
        dispatcher.trackReply(entry, "generating", () -> true, () -> { });
        CommentRingView preparing = replyRing();
        assertNotNull(preparing);
        assertEquals("generating", preparing.getPhase());
        worker.runAll();
        assertTrue("Unfinished reply content is not persisted as a ready preview", history.read("comment_reply").isEmpty());
        dispatcher.prepare(entry, () -> true, result -> {
            sends.incrementAndGet(); result.accept("sent");
        }, ignored -> { });
        assertSame(preparing, replyRing()); assertEquals("preview", replyRing().getPhase());
        advance(2); worker.runAll(); assertEquals(0, sends.get());
        advance(1); worker.runAll(); assertEquals(1, sends.get()); assertNull(replyRing());
    }

    @Test public void generatingReplyRecoversWithoutAStageChangeAndStopsRefreshingWhenHidden() {
        Map<String, Object> values = base(true, true);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        AutomationHistoryStore.Entry entry = commentReplyPreparation("generating-recovery");
        dispatcher.trackReply(entry, "generating", () -> true, () -> { });
        CommentRingView preparing = replyRing();
        assertNotNull(preparing);
        ((android.view.ViewGroup) preparing.getParent()).removeView(preparing);
        advance(1);
        assertSame(preparing, replyRing());
        assertTrue(preparing.isAttachedToWindow());
        assertTrue(preparing.isShown());
        assertEquals("generating", preparing.getPhase());
        assertEquals(0, sends.get());

        dispatcher.onActivityPaused(activity);
        advance(2);
        assertNull(preparing.getParent());
        dispatcher.onActivityResumed(activity);
        assertSame(preparing, replyRing());
        values.put("reply_tip", false);
        dispatcher.setOptions(new LocalOptions(values));
        advance(2);
        assertNull(preparing.getParent());
        values.put("reply_tip", true);
        dispatcher.setOptions(new LocalOptions(values));
        dispatcher.discardReplyPreparation(entry.kind, entry.id);
        advance(2);
        assertNull(preparing.getParent());
        dispatcher.trackReply(commentReplyPreparation("close-recovery"), "generating", () -> true, () -> { });
        assertSame(preparing, replyRing());
        dispatcher.close();
        advance(2);
        assertNull(preparing.getParent());
        assertEquals(0, sends.get());
    }

    @Test public void commentPreparationDoesNotTakeAReadySlotAndFullPreviewStillDefersItsClaim() {
        Map<String, Object> values = base(true, false);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        AutomationHistoryStore.Entry early = commentReplyPreparation("comment-capacity");
        dispatcher.trackReply(early, "generating", () -> true, () -> { });
        for (int i = 0; i < 16; i++) {
            dispatcher.prepare(replyPreparation("ready-capacity-" + i), () -> true,
                    result -> sends.incrementAndGet(), ignored -> { });
        }
        java.util.concurrent.atomic.AtomicReference<String> outcome = new java.util.concurrent.atomic.AtomicReference<>();
        dispatcher.prepare(early, () -> true, result -> sends.addAndGet(100), outcome::set);
        assertEquals("deferred", outcome.get());
        assertEquals(16, replyRing().getQueueCount()); assertEquals(0, sends.get());
    }

    @Test public void commentPreparationCleanupDoesNotRemoveAPrivatePreparationWithTheSameId() {
        Map<String, Object> values = base(true, false);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        dispatcher.trackReply(replyPreparation("shared-id"), "speech", () -> true, () -> { });
        dispatcher.trackReply(commentReplyPreparation("shared-id"), "generating", () -> true, () -> { });
        assertEquals(2, replyRing().getQueueCount());
        dispatcher.discardReplyPreparation("comment_reply", "shared-id");
        assertNotNull(replyRing()); assertEquals(1, replyRing().getQueueCount());
        assertEquals("speech", replyRing().getPhase());
    }

    @Test public void simpleLogsRetainSafeCommentOverlayDecisionsWithoutMessageOrTargetData() throws Exception {
        assertTrue(com.douai.module.diagnostics.DouAiLog.awaitIdle(5000));
        boolean previous = com.douai.module.diagnostics.DebugLog.isEnabled();
        List<String> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.douai.module.diagnostics.DebugLog.setEnabled(false);
        com.douai.module.diagnostics.DouAiLog.setFrameworkSink((priority, line) -> events.add(line));
        try {
            Map<String, Object> values = base(true, false); values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
            dispatcher.setOptions(new LocalOptions(values));
            dispatcher.trackReply(commentReplyPreparation("private-overlay-task"), "media", () -> true, () -> { });
            for (int i = 0; i < 10; i++) dispatcher.trackReply(commentReplyPreparation("private-overlay-task"), "media", () -> true, () -> { });
            dispatcher.onActivityPaused(activity);
            assertTrue(com.douai.module.diagnostics.DouAiLog.awaitIdle(5000));
            List<org.json.JSONObject> visibility = new ArrayList<>();
            for (String line : events) {
                org.json.JSONObject event = new org.json.JSONObject(line);
                if ("reply_overlay_state".equals(event.optString("event"))) visibility.add(event);
            }
            assertTrue(visibility.stream().anyMatch(event -> "show_requested".equals(event.optString("reason_code"))
                    && event.optString("evidence").contains("kind=comment_reply;phase=media")
                    && "simple".equals(event.optString("log_mode"))));
            assertTrue(visibility.stream().anyMatch(event -> "no_activity".equals(event.optString("reason_code"))));
            assertEquals(1, visibility.stream().filter(event -> "show_requested".equals(event.optString("reason_code"))).count());
            String joined = String.join("\n", events);
            for (String secret : List.of("private-overlay-task", "private-target", "private-input", "private-name")) assertFalse(joined.contains(secret));
        } finally {
            com.douai.module.diagnostics.DouAiLog.awaitIdle(5000);
            com.douai.module.diagnostics.DouAiLog.setFrameworkSink(null);
            com.douai.module.diagnostics.DebugLog.setEnabled(previous);
        }
    }

    private static AutomationHistoryStore.Entry commentReplyPreparation(String id) {
        long now = System.currentTimeMillis();
        return new AutomationHistoryStore.Entry(id, AutomationHistoryStore.COMMENT_REPLY, "private-target", "private-name",
                "private-input", "", "", "pending", now, now, AutomationHistoryStore.DELIVERY_TEXT);
    }

    @Test public void cancellingPreparationCancelsGenerationAndRejectsALateReadyCallback() {
        dispatcher.setOptions(options(true, false));
        AtomicInteger cancelled = new AtomicInteger();
        AutomationHistoryStore.Entry entry = replyPreparation("cancel-early");
        dispatcher.trackReply(entry, "generating", () -> true, cancelled::incrementAndGet);
        replyRing().performLongClick();
        assertEquals(1, cancelled.get());
        assertNull(replyRing());
        dispatcher.trackReply(entry, "speech", () -> true, cancelled::incrementAndGet);
        dispatcher.prepare(entry, () -> true, result -> sends.incrementAndGet(), ignored -> { });
        advance(10); worker.runAll();
        assertEquals(0, sends.get());
        assertEquals(1, cancelled.get());
        assertNull(replyRing());
    }

    @Test public void preparationCleanupDoesNotStickAndBackgroundPreviewStillSendsNormally() {
        dispatcher.setOptions(options(true, false));
        AutomationHistoryStore.Entry abandoned = replyPreparation("abandoned");
        dispatcher.trackReply(abandoned, "speech", () -> true, () -> { });
        assertNotNull(replyRing());
        dispatcher.discardReplyPreparation(abandoned.id);
        assertNull(replyRing());

        AutomationHistoryStore.Entry background = replyPreparation("background");
        dispatcher.trackReply(background, "generating", () -> true, () -> { });
        dispatcher.onActivityPaused(activity);
        assertNull(replyRing());
        dispatcher.prepare(background, () -> true, result -> {
            sends.incrementAndGet(); result.accept("sent");
        }, ignored -> { });
        advance(3); worker.runAll();
        assertEquals(1, sends.get());
        dispatcher.onActivityResumed(activity);
        assertNull(replyRing());
    }

    @Test public void preparationDiagnosticsExplainVisibilityWithoutMessageOrTargetData() {
        dispatcher.close(); worker.runAll();
        worker = new ManualExecutor();
        List<String> events = new ArrayList<>();
        dispatcher = new AutomationDispatcher(activity, events::add, worker);
        dispatcher.setOptions(options(true, false));
        dispatcher.onActivityResumed(activity);
        dispatcher.trackReply(replyPreparation("private-task-id"), "media", () -> true, () -> { });
        dispatcher.onActivityPaused(activity);
        String joined = String.join("\n", events);
        assertTrue(joined.contains("state=no_activity"));
        assertTrue(joined.contains("event=attach"));
        assertTrue(joined.contains("phase=media"));
        assertTrue(joined.contains("lifecycle=paused"));
        assertTrue(joined.contains("event=detach"));
        assertFalse(joined.contains("private-task-id"));
        assertFalse(joined.contains("private-target"));
        assertFalse(joined.contains("private-input"));
    }

    @Test public void preparationDoesNotReserveAReadyPreviewSlotOrBypassItsCapacity() {
        dispatcher.setOptions(options(true, false));
        AutomationHistoryStore.Entry early = replyPreparation("capacity-early");
        dispatcher.trackReply(early, "generating", () -> true, () -> { });
        for (int index = 0; index < 16; index++) {
            dispatcher.prepare(replyPreparation("ready-" + index), () -> true, result -> {
                sends.incrementAndGet(); result.accept("sent");
            }, ignored -> { });
        }
        java.util.concurrent.atomic.AtomicReference<String> earlyOutcome = new java.util.concurrent.atomic.AtomicReference<>();
        dispatcher.prepare(early, () -> true, result -> sends.addAndGet(100), earlyOutcome::set);
        assertEquals("cancelled", earlyOutcome.get());
        advance(3); worker.runAll();
        assertEquals(16, sends.get());
        assertNull(replyRing());
    }

    private static AutomationHistoryStore.Entry replyPreparation(String id) {
        return new AutomationHistoryStore.Entry(id, "reply", "private-target", "private-name",
                "private-input", "", "model", "pending", 1, 1);
    }

    @Test public void privateGroupAndCommentRepliesShareOneBlueRingWithoutDuplicatedBanners() {
        Map<String, Object> values = base(true, true);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        prepare("reply", "private", () -> true);
        prepare("reply", "group", () -> true);
        prepare("comment_reply", "nested", () -> true);
        prepare("comment", "comment", () -> true);
        layoutDecor();
        CommentRingView reply = replyRing();
        CommentRingView comment = (CommentRingView) activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring");
        assertNotNull(reply);
        assertNotNull(comment);
        assertEquals(3, reply.getQueueCount());
        assertEquals(1, comment.getQueueCount());
        assertEquals(3, reply.getRetriesLeft()); // Reply variant displays countdown seconds in its small counter.
        assertEquals(CommentRingView.REPLY_COLOR, reply.getPrimaryColor());
        assertEquals(CommentRingView.COMMENT_COLOR, comment.getPrimaryColor());
        assertEquals("回复", reply.getLabel());
        assertEquals("评论", comment.getLabel());
        assertFalse(overlap(reply, comment));
        assertTrue(reply.getContentDescription().toString().contains("自动回复"));
        assertFalse(reply.getContentDescription().toString().contains("生成内容"));
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.notice.private"));
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.notices"));
    }

    @Test public void replyRingCancelsLatestUnsubmittedChannelAndOpensTheOldestRemainingDetails() {
        Map<String, Object> values = base(true, true);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        dispatcher.setOptions(new LocalOptions(values));
        dispatcher.prepare("reply", "first", "u", "私聊", "最早收到的私聊", "准备的回复", "model", () -> true,
                callback -> { sends.incrementAndGet(); callback.accept("sent"); });
        prepare("comment_reply", "latest", () -> true);
        assertEquals(2, replyRing().getQueueCount());
        replyRing().performLongClick();
        worker.runAll();
        assertEquals(1, replyRing().getQueueCount());
        assertEquals("cancelled", history.read("comment_reply").get(0).status);
        replyRing().performClick();
        AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("最早收到的私聊", ((TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.input")).getText().toString());
        assertEquals("立即发送", dialog.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        advance(20);
        worker.runAll();
        assertEquals(0, sends.get());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        worker.runAll();
        assertEquals(1, sends.get());
    }

    @Test public void sendingAndUnknownReplyDetailsStayReadOnlyAndNeverOfferARepeatSend() {
        dispatcher.setOptions(options(true, true));
        dispatcher.prepare("reply", "unknown-ring", "u", "用户", "消息", "回复", "model", () -> true,
                callback -> sends.incrementAndGet());
        advance(3);
        worker.runAll();
        assertEquals("publishing", replyRing().getPhase());
        replyRing().performClick();
        AlertDialog sending = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("关闭", sending.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertNotShown(sending.getButton(AlertDialog.BUTTON_NEGATIVE));
        replyRing().performLongClick();
        sending.dismiss();
        advance(30);
        worker.runAll();
        assertEquals("submitted", replyRing().getPhase());
        replyRing().performClick();
        AlertDialog unknown = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("关闭", unknown.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertNotShown(unknown.getButton(AlertDialog.BUTTON_NEGATIVE));
        replyRing().performLongClick();
        advance(20);
        worker.runAll();
        assertEquals(1, sends.get());
        assertEquals("submitted", history.read("reply").get(0).status);
        assertNull(replyRing());
    }

    @Test public void replyFailureCanOnlyBeReviewedOrDismissedAndParentTipControlsOnlyItsOwnRing() {
        dispatcher.setOptions(options(true, true));
        dispatcher.reportFinalFailure(new AutomationHistoryStore.Entry("failed-reply", "reply", "u", "用户", "消息",
                "", "model", "failed", 1, 1));
        assertEquals("failed", replyRing().getPhase());
        replyRing().performClick();
        AlertDialog failure = ShadowAlertDialog.getLatestAlertDialog();
        assertEquals("关闭", failure.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertNotShown(failure.getButton(AlertDialog.BUTTON_NEGATIVE));
        failure.dismiss();
        prepare("comment", "comment", () -> true);
        Map<String, Object> withoutReplyTip = base(true, true);
        withoutReplyTip.put("reply_tip", false);
        dispatcher.setOptions(new LocalOptions(withoutReplyTip));
        assertNull(replyRing());
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        assertEquals(0, sends.get());
    }

    @Test public void pauseAndCloseDetachBothIndependentRingsAndReadOnlyDialogs() {
        dispatcher.setOptions(options(true, true));
        prepare("reply", "reply", () -> true);
        prepare("comment", "comment", () -> true);
        dispatcher.onActivityPaused(activity);
        assertNull(replyRing());
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        dispatcher.onActivityResumed(activity);
        assertNotNull(replyRing());
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        replyRing().performClick();
        AlertDialog details = ShadowAlertDialog.getLatestAlertDialog();
        dispatcher.close();
        assertFalse(details.isShowing());
        assertNull(replyRing());
        assertNull(activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
    }

    @Test @Config(qualifiers = "w432dp-h800dp-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void blueReplyAndTealCommentRingsRenderTogetherForVisualReview() {
        dispatcher.setOptions(options(true, false));
        prepare("reply", "blue", () -> true);
        prepare("comment", "teal", () -> true);
        layoutDecor();
        CommentRingView reply = replyRing();
        CommentRingView comment = (CommentRingView) activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring");
        assertFalse(overlap(reply, comment));
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        java.io.File folder = new java.io.File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(432, 800, android.graphics.Bitmap.Config.ARGB_8888);
        activity.getWindow().getDecorView().draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(folder, "automation-reply-comment-rings-432x800.png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private CommentRingView replyRing() { return (CommentRingView) activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring"); }
    private static void assertNotShown(View view) { assertTrue(view == null || view.getVisibility() != View.VISIBLE); }
    private static boolean overlap(View first, View second) {
        return first.getX() < second.getX() + second.getWidth() && first.getX() + first.getWidth() > second.getX()
                && first.getY() < second.getY() + second.getHeight() && first.getY() + first.getHeight() > second.getY();
    }
    private void layoutDecor() {
        View decor = activity.getWindow().getDecorView();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
    }

    private void layoutPreview() {
        dispatcher.setOptions(options(true, false));
        dispatcher.prepare("reply", "layout", "id", "较长的回复对象名称".repeat(5), "收到消息", "待发送的回复内容。".repeat(40),
                "model", () -> true, done -> { });
        View decor = activity.getWindow().getDecorView();
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(metrics.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, metrics.widthPixels, metrics.heightPixels);
        View banner = notice("layout");
        assertNotNull(banner);
        assertTrue(banner.getWidth() > 0 && banner.getWidth() <= metrics.widthPixels);
        assertTrue(banner.getHeight() <= metrics.heightPixels / 3 + 20);
        assertTrue(((android.view.ViewGroup) banner.getParent()).getWidth() <= metrics.widthPixels);
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        java.io.File folder = new java.io.File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(metrics.widthPixels, metrics.heightPixels, android.graphics.Bitmap.Config.ARGB_8888);
        decor.draw(new android.graphics.Canvas(bitmap));
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(new java.io.File(folder,
                "automation-" + metrics.widthPixels + "x" + metrics.heightPixels + ".png"))) {
            assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, stream));
        } catch (java.io.IOException failure) { throw new AssertionError(failure); }
        finally { bitmap.recycle(); }
    }

    private View notice(String id) {
        View reply = activity.getWindow().getDecorView().findViewWithTag("automation.notice." + id);
        return reply != null ? reply : activity.getWindow().getDecorView().findViewWithTag(
                "comment".equals(preparedKinds.get(id)) ? "automation.comment.ring" : "automation.reply.ring");
    }

    private static LocalOptions options(boolean tip, boolean history) { return new LocalOptions(base(tip, history)); }

    private static Map<String, Object> base(boolean tip, boolean history) {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true);
        values.put("native_auto_reply", true);
        values.put("native_auto_comment", true);
        values.put("reply_tip", tip);
        values.put("comment_tip", tip);
        values.put("reply_save_history", history);
        values.put("comment_save_history", history);
        return values;
    }

    private static void advance(int seconds) {
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(seconds));
        ShadowLooper.idleMainLooper();
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> remaining = new ArrayList<>(tasks);
            tasks.clear();
            return remaining;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        @Override public void execute(Runnable action) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(action);
        }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
    }
}
