package com.douai.module.feature;

import android.app.Activity;
import android.content.ContentValues;
import android.view.View;

import com.bytedance.im.core.client.callback.IRequestListener;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class NativeAutoReplyIntegrationTest {
    @Test public void receivedMessageIsPreviewedThenSentThroughNativeSdkAndRecordedAfterAck() throws Throwable { flow(false); }
    @Test public void longPressCancelsReceivedMessageWithoutCallingNativeSender() throws Throwable { flow(true); }
    @Test public void refreshedWhitelistCancelsPreparedReplyBeforeNativeSend() throws Throwable { flow(false, true); }
    @Test public void receivedMessageRepliesWithInteractionMasterOff() throws Throwable { flow(false, false, false); }
    @Test public void interactionMasterChangeDoesNotCancelDelayedReply() throws Throwable { flow(false, false, true, true); }
    @Test public void disablingDefaultPrivateChatScopeCancelsPreparedReply() throws Throwable { flow(false, false, true, false, true); }

    @Test public void globalFreshMessageRepliesWithoutDatabaseInsertionOrAChatActivity() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            assertFalse(fixture.dispatcher.isForeground(fixture.activity));
            Message incoming = incomingMessage(200, "thread", 9101, System.currentTimeMillis());
            Runnable reply = fixture.enqueueGlobal(incoming);
            assertNotNull("A fresh global SDK receipt must schedule a reply without any database insert or opened chat", reply);
            assertEquals(0, fixture.database.insertCalls);
            fixture.runReply(reply); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("thread", fixture.database.imSdkContext.last.conversation.id());
            assertEquals("sent", fixture.history().get(0).status);
            assertEquals(AutomationHistoryStore.Stage.COMPLETE, fixture.history().get(0).stage);
            assertEquals("100", fixture.history().get(0).location.accountId);
            assertEquals("thread", fixture.history().get(0).location.conversationId);
            assertEquals("9101", fixture.history().get(0).location.messageId);
            assertEquals(0, fixture.history().get(0).location.conversationType);
            assertEquals("200", fixture.history().get(0).location.peerUid);
        }
    }

    @Test public void coldDatabaseConversationResolvesAsynchronouslyWithoutOpeningAChat() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            Sdk sdk = fixture.database.imSdkContext;
            sdk.localConversations.put("cold-thread", new Conversation("cold-thread"));
            assertNull(sdk.conversations().find("cold-thread"));
            assertFalse(fixture.dispatcher.isForeground(fixture.activity));
            Runnable reply = fixture.enqueue(200, "cold-thread", false);
            assertNotNull(reply);
            fixture.runReply(reply); fixture.flush();
            assertEquals("A locally stored cold conversation must not require opening its chat", 1, sdk.sends);
            assertEquals(1, sdk.conversationLoads);
            assertNull("Async success deliberately does not populate this fixture's hot cache", sdk.conversations().find("cold-thread"));
            assertEquals("cold-thread", sdk.last.conversation.id());
        }
    }

    @Test public void globalAndDatabaseReceiptsForTheSameServerIdReplyOnlyOnce() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            Message incoming = incomingMessage(200, "thread", 9102, System.currentTimeMillis());
            Runnable reply = fixture.enqueueGlobal(incoming);
            assertNotNull(reply);
            assertNull(fixture.enqueueValues(incomingValues(incoming)));
            fixture.runReply(reply); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals(1, fixture.history().size());
        }
    }

    @Test public void durableReplyReservationBlocksFreshReplayAfterHookRecreation() throws Throwable {
        Message incoming = incomingMessage(200, "thread", 9201, System.currentTimeMillis());
        try (LimitedReplies first = new LimitedReplies()) {
            first.database.imSdkContext.suppressCallbacks = true;
            Runnable reply = first.enqueueGlobal(incoming);
            assertNotNull(reply);
            first.runReply(reply); first.flush();
            assertEquals(1, first.database.imSdkContext.sends);
            android.content.SharedPreferences records = first.activity.getSharedPreferences("douai_reply_submissions", 0);
            assertTrue(new com.douai.module.config.SubmissionLedger(records, 1000)
                    .contains(com.douai.module.config.SubmissionLedger.identity("100:thread:9201")));
            assertFalse(records.getAll().toString().contains("100:thread:9201"));
        }
        try (LimitedReplies recreated = new LimitedReplies(true)) {
            assertNull("A restart within the fresh-message window must retain the native submission claim",
                    recreated.enqueueGlobal(incoming));
            recreated.flush();
            assertEquals(0, recreated.database.imSdkContext.sends);
        }
    }

    @Test public void unavailableDurableReservationForbidsNativeSendAndDoesNotSpendAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            android.content.SharedPreferences records = fixture.activity.getSharedPreferences("douai_reply_submissions", 0);
            com.douai.module.config.SubmissionLedger full = new com.douai.module.config.SubmissionLedger(records, 1);
            assertTrue(full.reserve(com.douai.module.config.SubmissionLedger.identity("other-unconfirmed"), "old", System.currentTimeMillis()));
            java.lang.reflect.Field field = NativeMessagingHooks.class.getDeclaredField("replyLedger");
            field.setAccessible(true); field.set(fixture.hook, full);
            fixture.receive(200, "thread"); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals(AutomationHistoryStore.Reason.LEDGER_FULL, fixture.history().get(0).reason);
            assertTrue(new AutomationRateLimiter(fixture.activity).canSend("reply", 100, "200", new LocalOptions(fixture.values)));
        }
    }

    @Test public void nativeAcknowledgmentPersistsOnTheSharedResultLane() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            java.util.concurrent.atomic.AtomicReference<String> commitThread = new java.util.concurrent.atomic.AtomicReference<>();
            android.content.SharedPreferences preferences = fixture.activity.getSharedPreferences("douai_reply_submissions", 0);
            android.content.SharedPreferences observed = (android.content.SharedPreferences) java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(), new Class<?>[]{android.content.SharedPreferences.class}, (proxy, method, arguments) -> {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == arguments[0];
                        if (!"edit".equals(method.getName())) return method.invoke(preferences, arguments);
                        android.content.SharedPreferences.Editor editor = preferences.edit();
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{android.content.SharedPreferences.Editor.class}, (editorProxy, edit, args) -> {
                                    if ("commit".equals(edit.getName())) commitThread.set(Thread.currentThread().getName());
                                    Object result = edit.invoke(editor, args);
                                    return result instanceof android.content.SharedPreferences.Editor ? editorProxy : result;
                                });
                    });
            java.lang.reflect.Field field = NativeMessagingHooks.class.getDeclaredField("replyLedger");
            field.setAccessible(true); field.set(fixture.hook, new com.douai.module.config.SubmissionLedger(observed, 1000));
            fixture.database.imSdkContext.suppressCallbacks = true;
            fixture.receive(200, "thread"); fixture.flush();
            commitThread.set(null);
            fixture.database.imSdkContext.pendingCallbacks.remove(0).run();
            fixture.flush();
            assertEquals("DouAi-reply-results", commitThread.get());
            assertEquals("sent", fixture.history().get(0).status);
        }
    }

    @Test public void fullResultLaneKeepsSubmittedOutcomeAndAllowanceWithoutBlockingTheCaller() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            awaitResults();
            java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.ThreadPoolExecutor results = resultExecutor();
            results.execute(() -> {
                blocked.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            try {
                for (int i = 0; i < 64; i++) results.execute(() -> { });
                fixture.receive(200, "thread");
                fixture.worker.runAll(); ShadowLooper.idleMainLooper(); fixture.worker.runAll();
                assertEquals(1, fixture.database.imSdkContext.sends);
                assertEquals("submitted", fixture.history().get(0).status);
                assertEquals(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, fixture.history().get(0).reason);
                assertFalse(new AutomationRateLimiter(fixture.activity).canSend("reply", 100, "200", new LocalOptions(fixture.values)));
            } finally { release.countDown(); }
            fixture.flush();
        }
    }

    @Test public void throwingNativeSendRemainsUnknownAndKeepsItsAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.database.imSdkContext.throwAfterSend = true;
            fixture.receive(200, "thread"); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            AutomationHistoryStore.Entry record = fixture.history().get(0);
            assertEquals("submitted", record.status);
            assertEquals(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, record.reason);
            fixture.receive(201, "other"); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
        }
    }

    @Test public void expiredQueuedReplyIsIdentifiedBeforeAnyGenerationOrNativeSend() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            Runnable reply = fixture.enqueue(200, "thread", false);
            assertNotNull(reply);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(121));
            reply.run(); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals(AutomationHistoryStore.Reason.QUEUE_EXPIRED, fixture.history().get(0).reason);
            assertEquals(AutomationHistoryStore.Stage.QUEUE, fixture.history().get(0).stage);
            assertFalse(fixture.history().get(0).modelRequestSent);
        }
    }

    @Test public void queueWaitingDoesNotSpendTheExecutionBudget() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            NativeAudioMessageSenderTest.Uploader.count = 0;
            NativeAudioMessageSenderTest.LocalInsert.fail = false;
            NativeAudioMessageSenderTest.LocalInsert.defer = false;
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(40));
                return NativeAudioMessageSenderTest.audio();
            };
            Runnable reply = fixture.enqueue(200, "thread", false);
            assertNotNull(reply);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(90));
            reply.run(); fixture.flush();
            assertEquals(1, NativeAudioMessageSenderTest.Uploader.count);
            assertEquals(0, fixture.history().stream().filter(entry -> "failed".equals(entry.status)).count());
        }
    }

    @Test public void fullReplyQueueRecordsAnExplicitReasonAndDoesNotGrow() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            for (int i = 0; i < 101; i++) fixture.enqueue(200, "thread", false);
            fixture.flush();
            ReplyScheduler<?> queue = (ReplyScheduler<?>) HostTargets.read(fixture.hook, "replyQueue");
            assertEquals(100, queue.size());
            assertEquals(AutomationHistoryStore.Reason.QUEUE_FULL, fixture.history().get(0).reason);
            assertEquals(0, fixture.database.imSdkContext.sends);
        }
    }

    @Test public void fullPreviewQueueKeepsItsReasonInsteadOfBecomingGenericCancellation() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            fixture.values.put("reply_tip", true);
            fixture.values.put("reply_notice_seconds", "120");
            fixture.applyOptions();
            for (int i = 0; i < 17; i++) fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals(1, fixture.history().stream().filter(entry -> "cancelled".equals(entry.status)
                    && entry.reason == AutomationHistoryStore.Reason.QUEUE_FULL).count());
        }
    }

    @Test public void globalHistoricalOwnAndAccountSwitchedMessagesNeverSend() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            Message deleted = incomingMessage(200, "thread", 9106, System.currentTimeMillis());
            deleted.deleted = 1;
            assertNull(fixture.enqueueGlobal(incomingMessage(200, "thread", 9103, System.currentTimeMillis() - 120_000),
                    incomingMessage(100, "thread", 9104, System.currentTimeMillis()), deleted));
            assertEquals(0, fixture.database.imSdkContext.sends);
            Runnable pending = fixture.enqueueGlobal(incomingMessage(200, "thread", 9105, System.currentTimeMillis()));
            assertNotNull(pending);
            fixture.database.imSdkContext.account = 101;
            fixture.runReply(pending); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
        }
    }

    @Test public void coldGlobalLookupCanRetryTheSameServerIdWhenItsConversationArrives() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            Message incoming = incomingMessage(200, "arriving-late", 9107, System.currentTimeMillis());
            Runnable first = fixture.enqueueGlobal(incoming);
            assertNotNull(first);
            fixture.runReply(first); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            fixture.database.imSdkContext.localConversations.put("arriving-late", new Conversation("arriving-late"));
            Runnable retried = fixture.enqueueGlobal(incoming);
            assertNotNull("A temporary cold-cache miss must release its seen reservation", retried);
            fixture.runReply(retried); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals(2, fixture.database.imSdkContext.conversationLoads);
        }
    }

    @Test public void coldResolutionRejectsADifferentConversationReturnedByTheSdk() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            fixture.database.imSdkContext.localConversations.put("cold-thread", new Conversation("other"));
            Runnable reply = fixture.enqueue(200, "cold-thread", false);
            assertNotNull(reply);
            fixture.runReply(reply); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.conversationLoads);
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertNull(fixture.database.imSdkContext.last);
        }
    }

    @Test public void globalGroupMentionModeUsesTheExactSdkMessageExtension() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_group_mode", "mentions")) {
            Message other = incomingMessage(200, "group", 9108, System.currentTimeMillis());
            other.ext.put("s:mentioned_users", "1000");
            assertNull(fixture.enqueueGlobal(other));
            Message mentioned = incomingMessage(200, "group", 9109, System.currentTimeMillis());
            mentioned.ext.put("s:mentioned_users", "200,100");
            Runnable reply = fixture.enqueueGlobal(mentioned);
            assertNotNull("The global-message adapter must retain the verified group mention extension", reply);
            fixture.runReply(reply); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("group", fixture.database.imSdkContext.last.conversation.id());
        }
    }

    @Test public void dailyLimitStopsASecondReplyPreparedBeforeTheFirstSend() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.receive(200, "thread");
            fixture.receive(201, "other");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            List<AutomationHistoryStore.Entry> records = fixture.history();
            assertEquals(2, records.size());
            assertEquals(1, records.stream().filter(entry -> "sent".equals(entry.status)).count());
            assertEquals(1, records.stream().filter(entry -> "cancelled".equals(entry.status)).count());
        }
    }

    @Test public void nativeSendBoundaryRechecksAllowanceConsumedAfterPreviewValidation() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.database.imSdkContext.beforeSender = () -> assertNotNull(new AutomationRateLimiter(fixture.activity)
                    .tryReserve(AutomationHistoryStore.REPLY, 100, "201", new LocalOptions(fixture.values)));
            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("cancelled", fixture.history().get(0).status);
        }
    }

    @Test public void sameSenderIntervalSpansPrivateAndGroupChatsButAllowsAnotherGroupMember() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_user_cooldown_minutes", "30")) {
            fixture.receive(200, "thread");
            fixture.receive(200, "group");
            fixture.receive(201, "group");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            fixture.receive(200, "group");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            assertEquals(3, fixture.history().size());
        }
    }

    @Test public void nativeFailureRestoresDailyAndSameSenderAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1",
                "reply_user_cooldown_minutes", "30")) {
            fixture.database.imSdkContext.failNext = true;
            fixture.receive(200, "thread");
            fixture.flush();
            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            assertEquals(1, fixture.history().stream().filter(entry -> "failed".equals(entry.status)).count());
            assertEquals(1, fixture.history().stream().filter(entry -> "sent".equals(entry.status)).count());
        }
    }

    @Test public void missingNativeAcknowledgmentKeepsAllowanceConsumedAfterTimeout() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_daily_limit", "1")) {
            fixture.database.imSdkContext.suppressCallbacks = true;
            fixture.receive(200, "thread");
            fixture.flush();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31));
            fixture.flush();
            fixture.receive(201, "other");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("submitted", fixture.history().get(0).status);
        }
    }

    @Test public void enablingLimitCountsEarlierUnlimitedSendsAndCancelsPreparedReply() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies()) {
            fixture.receive(200, "thread");
            fixture.flush();
            fixture.receive(201, "other");
            fixture.values.put("reply_daily_limit", "1");
            LocalOptions changed = new LocalOptions(fixture.values);
            fixture.hook.setOptions(changed);
            fixture.dispatcher.setOptions(changed);
            fixture.flush();
            fixture.receive(202, "third");
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals(2, fixture.history().size());
        }
    }

    @Test public void voiceReplyUsesAudioOnlyInPrivateAndGroupChatsAndRecordsAfterRemoteAck() throws Throwable {
        for (String conversation : Arrays.asList("thread", "group", "cold-thread")) {
            try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
                if ("cold-thread".equals(conversation))
                    fixture.database.imSdkContext.localConversations.put(conversation, new Conversation(conversation));
                NativeAudioMessageSenderTest.Uploader.count = 0;
                NativeAudioMessageSenderTest.Uploader.kind = 17;
                NativeAudioMessageSenderTest.LocalInsert.fail = false;
                NativeAudioMessageSenderTest.LocalInsert.defer = false;
                java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    assertEquals("reply", text); assertEquals(120_000, maximum); assertTrue(valid.getAsBoolean());
                    SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
                };
                if ("cold-thread".equals(conversation)) {
                    Runnable queued = fixture.enqueue(200, conversation, false);
                    assertNotNull(queued);
                    fixture.runReply(queued);
                    ShadowLooper.idleMainLooper();
                } else fixture.receive(200, conversation);
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, NativeAudioMessageSenderTest.Uploader.count);
                Message message = (Message) NativeAudioMessageSenderTest.Uploader.last;
                assertEquals(17, message.type);
                assertEquals(conversation, message.conversation.id());
                if ("cold-thread".equals(conversation)) {
                    assertEquals(1, fixture.database.imSdkContext.conversationLoads);
                    assertNull(fixture.database.imSdkContext.conversations().find(conversation));
                }
                assertEquals(100, message.sender);
                assertEquals("group".equals(conversation) ? 2 : 1, message.conversationType);
                assertEquals(501, message.orderIndex);
                org.json.JSONObject body = new org.json.JSONObject(message.content);
                assertEquals(1250, body.getLong("duration")); assertFalse(body.has("text"));
                assertEquals("sending", fixture.history().get(0).status);
                assertTrue(generated.get().file.exists());
                message.localExt.put("s:status_code", "0");
                fixture.framework.invoke(NativeAudioMessageSenderTest.Message.class.getMethod("setMsgStatus", int.class), message, 2);
                fixture.framework.invoke(NativeAudioMessageSenderTest.Message.class.getMethod("setMsgId", long.class), message, 900L);
                fixture.flush();
                assertEquals("sent", fixture.history().get(0).status);
                assertFalse(generated.get().file.exists());
            }
        }
    }

    @Test public void voiceSynthesisFailureNeverFallsBackToTextOrConsumesAllowance() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true", "reply_daily_limit", "1")) {
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> { throw new IllegalStateException("provider failed"); };
            fixture.receive(200, "thread"); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("failed", fixture.history().get(0).status);
            assertTrue(new AutomationRateLimiter(fixture.activity)
                    .canSend("reply", 100, "200", new LocalOptions(fixture.values)));
        }
    }

    @Test public void changingSpeechSettingsAfterPreparationCancelsVoiceAndDeletesPreparedFile() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
            };
            fixture.receive(200, "thread");
            fixture.values.put("tts_voice", "changed");
            LocalOptions changed = new LocalOptions(fixture.values);
            fixture.hook.setOptions(changed); fixture.dispatcher.setOptions(changed); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals("cancelled", fixture.history().get(0).status);
            assertFalse(generated.get().file.exists());
        }
    }

    @Test public void voiceReplyKeepsUnknownSendAllowanceAndFileAfterDispatcherTimeout() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true", "reply_daily_limit", "1")) {
            NativeAudioMessageSenderTest.LocalInsert.fail = false; NativeAudioMessageSenderTest.LocalInsert.defer = false;
            java.util.concurrent.atomic.AtomicReference<SpeechPreparation.Audio> generated = new java.util.concurrent.atomic.AtomicReference<>();
            fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio(); generated.set(audio); return audio;
            };
            fixture.receive(200, "thread"); fixture.flush();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31)); fixture.flush();
            assertEquals("submitted", fixture.history().get(0).status);
            assertTrue(generated.get().file.exists());
            assertFalse(new AutomationRateLimiter(fixture.activity)
                    .canSend("reply", 100, "201", new LocalOptions(fixture.values)));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMinutes(10));
            assertFalse(generated.get().file.exists());
        }
    }

    @Test public void disablingRepliesDisconnectsBlockedImageAndAllowsNextReply() throws Throwable {
        for (boolean headers : new boolean[]{true, false}) {
            BlockingMediaConnection connection = new BlockingMediaConnection(headers);
            try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
                try {
                    fixture.startBlockedImage(connection);
                    fixture.values.put("native_auto_reply", false);
                    fixture.applyOptions();
                    awaitDisconnected(connection);
                    fixture.awaitWorker();
                    fixture.flush();
                    assertEquals(0, fixture.database.imSdkContext.sends);
                    fixture.values.put("native_auto_reply", true);
                    fixture.values.put("reply_mode", "fixed");
                    fixture.applyOptions();
                    fixture.receive(201, "other"); fixture.flush();
                    assertEquals(1, fixture.database.imSdkContext.sends);
                    assertEquals("other", fixture.database.imSdkContext.last.conversation.id());
                } finally { connection.disconnect(); }
            }
        }
    }

    @Test public void disablingImageRecognitionRejectsImageOnlyMessagesBeforeDownloadingOrCallingAi() throws Throwable {
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai", LocalOptions.REPLY_RECOGNIZE_IMAGES, "false")) {
            java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> {
                downloads.incrementAndGet();
                throw new AssertionError("Disabled image recognition must never download the image");
            });
            assertNull(fixture.enqueue(200, "thread", true));
            fixture.flush();
            assertEquals(0, downloads.get());
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertTrue(fixture.history().isEmpty());
        }
    }

    @Test public void automaticReplyUsesItsBoundStreamingSupplierWithoutChangingTheGlobalModel() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer global = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer replies = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer comments = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            AiModelSettings models = AutomationModelFixtures.profiles(global, replies, comments);
            fixture.values.put(AiModelSettings.KEY, models.serialize());
            fixture.values.put(AiModelSettings.REPLY_MODEL_ID, "reply");
            fixture.values.put(AiModelSettings.COMMENT_MODEL_ID, "comment");
            fixture.applyOptions();
            replies.enqueue(AutomationModelFixtures.streamed("独立供应商回复"));
            Runnable queued = fixture.enqueue(200, "thread", false);
            assertNotNull(queued);
            queued.run(); fixture.flush();
            okhttp3.mockwebserver.RecordedRequest request = replies.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            org.json.JSONObject body = new org.json.JSONObject(request.getBody().readUtf8());
            assertEquals("reply-model", body.getString("model"));
            assertTrue(body.getBoolean("stream"));
            assertEquals("Bearer reply-key", request.getHeader("Authorization"));
            assertEquals("text/event-stream", request.getHeader("Accept"));
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("reply-model", fixture.history().get(0).modelName);
            assertEquals("sent", fixture.history().get(0).status);
            assertEquals(0, global.getRequestCount()); assertEquals(0, comments.getRequestCount());
            assertEquals("global", AiModelSettings.read(new LocalOptions(fixture.values)).activeId);
        }
    }

    @Test public void changingReplySupplierCancelsTheOldQueuedReplyAndRoutesTheNextOne() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer global = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer replies = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer comments = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            fixture.values.put(AiModelSettings.KEY, AutomationModelFixtures.profiles(global, replies, comments).serialize());
            fixture.applyOptions();
            Runnable old = fixture.enqueue(200, "thread", false);
            assertNotNull(old);
            fixture.values.put(AiModelSettings.REPLY_MODEL_ID, "reply"); fixture.applyOptions();
            old.run(); fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends); assertEquals(0, global.getRequestCount());
            replies.enqueue(AutomationModelFixtures.streamed("新供应商回复"));
            Runnable next = fixture.enqueue(200, "thread", false);
            assertNotNull(next); next.run(); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends); assertEquals(1, replies.getRequestCount());
            assertEquals(0, global.getRequestCount()); assertEquals(0, comments.getRequestCount());
        }
    }

    @Test public void changingCommentSupplierDoesNotCancelAPreparedPrivateReply() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer global = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer replies = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer comments = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            fixture.values.put(AiModelSettings.KEY, AutomationModelFixtures.profiles(global, replies, comments).serialize());
            fixture.values.put(AiModelSettings.REPLY_MODEL_ID, "reply"); fixture.applyOptions();
            Runnable queued = fixture.enqueue(200, "thread", false);
            assertNotNull(queued);
            fixture.values.put(AiModelSettings.COMMENT_MODEL_ID, "comment"); fixture.applyOptions();
            replies.enqueue(AutomationModelFixtures.streamed("私信继续回复"));
            queued.run(); fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends); assertEquals(1, replies.getRequestCount());
            assertEquals(0, global.getRequestCount()); assertEquals(0, comments.getRequestCount());
        }
    }

    @Test public void imageCaptionWithRecognitionDisabledUsesTextOnlyAiAndRecordsItsActualRequest() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai", LocalOptions.REPLY_RECOGNIZE_IMAGES, "false")) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"只回复真实文字\"}}]}"));
            configureAi(fixture, server);
            java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> {
                downloads.incrementAndGet();
                throw new AssertionError("Disabled images must not be fetched for a caption");
            });
            Runnable reply = fixture.enqueueContent(200, "thread", 27,
                    "{\"text\":\"只读取这段说明\",\"resource_url\":{\"url_list\":[\"https://p.byteimg.com/image.png\"]}}");
            assertNotNull(reply);
            reply.run();
            fixture.flush();
            assertEquals(0, downloads.get());
            assertEquals(1, fixture.database.imSdkContext.sends);
            okhttp3.mockwebserver.RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            org.json.JSONArray messages = new org.json.JSONObject(request.getBody().readUtf8()).getJSONArray("messages");
            String content = messages.getJSONObject(messages.length() - 1).getString("content");
            assertTrue(content.contains("只读取这段说明"));
            assertFalse(content.contains("请根据对方发来的图片"));
            AutomationHistoryStore.Entry history = fixture.history().get(0);
            assertTrue(history.modelRequestSent);
            assertEquals(0, history.requestImageCount);
            assertEquals("sent", history.status);
        }
    }

    @Test public void encryptedIncomingVideoDoesNotCreateReplyWorkOrConsumeAllowance() throws Throwable {
        for (String[] flags : new String[][]{{"true", "false"}, {"false", "false"}, {"true", "true"}}) {
            try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai", "reply_daily_limit", "1",
                    LocalOptions.REPLY_RECOGNIZE_IMAGES, flags[0], LocalOptions.REPLY_RECOGNIZE_VIDEO, flags[1])) {
                java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
                fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> {
                    downloads.incrementAndGet();
                    throw new AssertionError("Encrypted story video has no verified playable source");
                });
                assertNull(fixture.enqueueContent(200, "thread", 30,
                        "{\"video\":{\"vid\":\"opaque\",\"tkey\":\"token\",\"skey\":\"secret\",\"is_new_encrypt\":true}}"));
                fixture.flush();
                assertEquals(0, downloads.get());
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertTrue(new AutomationRateLimiter(fixture.activity)
                        .canSend(AutomationHistoryStore.REPLY, 100, "200", new LocalOptions(fixture.values)));
            }
        }
    }

    @Test public void enabledIncomingImageIsSentToAiAndRememberedOnlyAfterNativeAcknowledgment() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"图片回复\"}}]}"));
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"choices\":[{\"message\":{\"content\":\"后续回复\"}}]}"));
            configureAi(fixture, server);
            java.util.concurrent.atomic.AtomicInteger downloads = new java.util.concurrent.atomic.AtomicInteger();
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> {
                downloads.incrementAndGet();
                return new ImageMediaConnection();
            });
            Runnable imageReply = fixture.enqueue(200, "thread", true);
            assertNotNull(imageReply);
            imageReply.run();
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals(1, downloads.get());
            AutomationHistoryStore.Entry imageHistory = fixture.history().get(0);
            assertTrue(imageHistory.modelRequestSent);
            assertEquals(1, imageHistory.requestImageCount);
            assertEquals("sent", imageHistory.status);
            okhttp3.mockwebserver.RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first);
            org.json.JSONArray firstMessages = new org.json.JSONObject(first.getBody().readUtf8()).getJSONArray("messages");
            assertEquals(1, inlineImageUrls(firstMessages).size());
            assertTrue(inlineImageUrls(firstMessages).get(0).startsWith("data:image/jpeg;base64,"));

            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            assertEquals(1, downloads.get());
            okhttp3.mockwebserver.RecordedRequest second = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(second);
            org.json.JSONArray remembered = new org.json.JSONObject(second.getBody().readUtf8()).getJSONArray("messages");
            assertEquals(3, remembered.length());
            assertEquals(1, inlineImageUrls(remembered).size());
            assertTrue(inlineImageUrls(remembered).get(0).startsWith("data:image/jpeg;base64,"));
            assertEquals("图片回复", remembered.getJSONObject(1).getString("content"));
            assertTrue(remembered.getJSONObject(2).getString("content").contains("incoming"));
            AutomationHistoryStore.Entry textHistory = fixture.history().stream()
                    .filter(entry -> "后续回复".equals(entry.output)).findFirst().orElseThrow();
            assertTrue(textHistory.modelRequestSent);
            assertEquals(0, textHistory.requestImageCount);
        }
    }

    @Test public void disablingRecognitionClearsPreviouslyAcknowledgedImagesFromTheNextTextRequest() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            server.enqueue(aiResponse("已经回复图片"));
            server.enqueue(aiResponse("现在仅回复文字"));
            configureAi(fixture, server);
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> new ImageMediaConnection());
            Runnable first = fixture.enqueue(200, "thread", true);
            assertNotNull(first);
            first.run();
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("sent", fixture.history().get(0).status);
            okhttp3.mockwebserver.RecordedRequest imageRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(imageRequest);
            assertEquals(1, inlineImageUrls(new org.json.JSONObject(imageRequest.getBody().readUtf8()).getJSONArray("messages")).size());

            fixture.values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
            fixture.values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
            fixture.applyOptions();
            fixture.receive(200, "thread");
            fixture.flush();
            assertEquals(2, fixture.database.imSdkContext.sends);
            okhttp3.mockwebserver.RecordedRequest textRequest = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(textRequest);
            org.json.JSONArray messages = new org.json.JSONObject(textRequest.getBody().readUtf8()).getJSONArray("messages");
            assertTrue("Disabling recognition must also remove images from prior turns", inlineImageUrls(messages).isEmpty());
            assertEquals(1, messages.length());
            assertTrue(messages.getJSONObject(0).getString("content").contains("incoming"));
        }
    }

    @Test public void anOldSendsLateAcknowledgmentCannotRestoreImagesAfterRecognitionIsDisabled() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            server.enqueue(aiResponse("等待旧图片确认"));
            server.enqueue(aiResponse("新的纯文字回复"));
            configureAi(fixture, server);
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> new ImageMediaConnection());
            fixture.database.imSdkContext.suppressCallbacks = true;
            Runnable first = fixture.enqueue(200, "thread", true);
            assertNotNull(first);
            first.run();
            fixture.flush();
            assertEquals(1, fixture.database.imSdkContext.sends);
            assertEquals("sending", fixture.history().get(0).status);
            assertEquals(1, fixture.database.imSdkContext.pendingCallbacks.size());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));

            fixture.values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
            fixture.values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
            fixture.applyOptions();
            fixture.database.imSdkContext.suppressCallbacks = false;
            fixture.database.imSdkContext.pendingCallbacks.remove(0).run();
            fixture.flush();
            assertEquals("sent", fixture.history().get(0).status);
            fixture.receive(200, "thread");
            fixture.flush();
            okhttp3.mockwebserver.RecordedRequest afterLateAck = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(afterLateAck);
            org.json.JSONArray messages = new org.json.JSONObject(afterLateAck.getBody().readUtf8()).getJSONArray("messages");
            assertEquals(1, messages.length());
            assertTrue("A superseded send must not restore its old image turn", inlineImageUrls(messages).isEmpty());
            assertEquals(2, fixture.database.imSdkContext.sends);
        }
    }

    @Test public void modelHttpFailureRetainsTheActualSentImageCountInReplyHistory() throws Throwable {
        try (okhttp3.mockwebserver.MockWebServer server = new okhttp3.mockwebserver.MockWebServer();
             LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            server.enqueue(new okhttp3.mockwebserver.MockResponse().setResponseCode(500)
                    .setHeader("Content-Type", "application/json").setBody("{\"error\":\"fixture failure\"}"));
            configureAi(fixture, server);
            fixture.hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> new ImageMediaConnection());
            Runnable imageReply = fixture.enqueue(200, "thread", true);
            assertNotNull(imageReply);
            imageReply.run();
            fixture.flush();
            assertEquals(0, fixture.database.imSdkContext.sends);
            assertEquals(1, server.getRequestCount());
            AutomationHistoryStore.Entry history = fixture.history().get(0);
            assertEquals("failed", history.status);
            assertTrue(history.modelRequestSent);
            assertEquals(1, history.requestImageCount);
            assertEquals(AutomationHistoryStore.Reason.AI_HTTP_ERROR, history.reason);
        }
    }

    @Test public void changingEitherMediaOptionActivelyDisconnectsAnUnsubmittedImageRead() throws Throwable {
        for (String key : new String[]{LocalOptions.REPLY_RECOGNIZE_IMAGES, LocalOptions.REPLY_RECOGNIZE_VIDEO}) {
            BlockingMediaConnection connection = new BlockingMediaConnection(true);
            try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
                try {
                    fixture.startBlockedImage(connection);
                    fixture.values.put(key, LocalOptions.REPLY_RECOGNIZE_VIDEO.equals(key));
                    fixture.applyOptions();
                    awaitDisconnected(connection);
                    fixture.awaitWorker(); fixture.flush();
                    assertEquals(0, fixture.database.imSdkContext.sends);
                    fixture.values.put("reply_mode", "fixed");
                    fixture.applyOptions();
                    fixture.receive(201, "other"); fixture.flush();
                    assertEquals(1, fixture.database.imSdkContext.sends);
                } finally { connection.disconnect(); }
            }
        }
    }

    @Test public void accountSwitchWithoutAnotherIncomingMessageDisconnectsBlockedReply() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                fixture.database.imSdkContext.account = 101;
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
                awaitDisconnected(connection);
                fixture.awaitWorker(); fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void closingRepliesDisconnectsBlockedImageBeforeItsSocketTimeout() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(false);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                fixture.hook.close();
                awaitDisconnected(connection);
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void overallReplyDeadlineDisconnectsBlockedImageAndAllowsNextReply() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai")) {
            try {
                fixture.startBlockedImage(connection);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(121));
                awaitDisconnected(connection);
                fixture.awaitWorker(); fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals("failed", fixture.history().get(0).status);
                fixture.values.put("reply_mode", "fixed"); fixture.applyOptions();
                fixture.receive(201, "other"); fixture.flush();
                assertEquals(1, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void lateSpeechPreparationAfterReplyDeadlineDeletesAudioWithoutSending() throws Throwable {
        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio();
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            try {
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    preparing.countDown();
                    boolean ready = false;
                    while (!ready) {
                        try { ready = release.await(2, TimeUnit.SECONDS); }
                        catch (InterruptedException ignored) { }
                    }
                    return audio;
                };
                Runnable reply = fixture.enqueue(200, "thread", false);
                assertNotNull(reply);
                fixture.scheduled.execute(reply);
                assertTrue(preparing.await(2, TimeUnit.SECONDS));
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(121));
                fixture.flush();
                assertEquals("failed", fixture.history().get(0).status);
                release.countDown(); fixture.awaitWorker(); fixture.flush();
                assertFalse(audio.file.exists());
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, fixture.history().size());
                assertEquals("failed", fixture.history().get(0).status);
            } finally { release.countDown(); audio.delete(); }
        }
    }

    @Test public void installedStreakTickStillRunsWhileReplyImageIsBlocked() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        try (LimitedReplies fixture = new LimitedReplies("reply_mode", "ai", "streak_enabled", "true",
                "automation_enabled", "true", "streak_targets", "200")) {
            try {
                fixture.startBlockedImage(connection);
                ScheduledThreadPoolExecutor ticker = (ScheduledThreadPoolExecutor)
                        HostTargets.read(fixture.hook, "streakWorker");
                Runnable tick = ticker.getQueue().peek();
                assertNotNull(tick);
                ticker.getQueue().remove(tick);
                ticker.execute(tick);
                assertTrue("Installed streak timer was blocked by reply generation",
                        fixture.database.imSdkContext.enumerated.await(2, TimeUnit.SECONDS));
                assertFalse(connection.disconnected);
                assertEquals(0, fixture.database.imSdkContext.sends);
            } finally { connection.disconnect(); }
        }
    }

    @Test public void lateGenerationCannotHandoffWhenMainWatchdogHasNotRun() throws Throwable {
        java.util.concurrent.CountDownLatch preparing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        SpeechPreparation.Audio audio = NativeAudioMessageSenderTest.audio();
        try (LimitedReplies fixture = new LimitedReplies("reply_voice", "true")) {
            try {
                fixture.hook.speechPreparer = (context, text, options, maximum, valid) -> {
                    preparing.countDown();
                    assertTrue(release.await(2, TimeUnit.SECONDS));
                    return audio;
                };
                Runnable reply = fixture.enqueue(200, "thread", false);
                assertNotNull(reply);
                fixture.scheduled.execute(reply);
                assertTrue(preparing.await(2, TimeUnit.SECONDS));
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(121));
                release.countDown();
                fixture.awaitWorker();
                assertFalse("Late audio must be rejected before the delayed main watchdog runs", audio.file.exists());
                fixture.flush();
                assertEquals(0, fixture.database.imSdkContext.sends);
                assertEquals(1, fixture.history().size());
                assertEquals("failed", fixture.history().get(0).status);
            } finally { release.countDown(); audio.delete(); }
        }
    }

    private static void awaitDisconnected(BlockingMediaConnection connection) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!connection.disconnected && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Blocked reply media must be actively disconnected", connection.disconnected);
    }

    private void flow(boolean cancel) throws Throwable {
        flow(cancel, false);
    }

    private void flow(boolean cancel, boolean removedFromList) throws Throwable {
        flow(cancel, removedFromList, true);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster) throws Throwable {
        flow(cancel, removedFromList, interactionMaster, false);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster, boolean changeMaster) throws Throwable {
        flow(cancel, removedFromList, interactionMaster, changeMaster, false);
    }

    private void flow(boolean cancel, boolean removedFromList, boolean interactionMaster, boolean changeMaster,
                      boolean disablePrivate) throws Throwable {
        FollowListSnapshot.reset();
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("douai_automation_history", Activity.MODE_PRIVATE).edit().clear().commit();
        ManualExecutor worker = new ManualExecutor();
        AutomationDispatcher dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        dispatcher.onActivityResumed(activity);
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", interactionMaster); values.put("native_auto_reply", true); values.put("reply_global", true);
        values.put("reply_tip", true); values.put("reply_save_history", true); values.put("auto_message_text", "固定回复");
        if (removedFromList) {
            values.put("reply_scope_mode", "whitelist");
            values.put("reply_scope_relations", "following");
            FollowListSnapshot.activateAccount("100");
            FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                    Collections.singleton("200"));
        }
        LocalOptions options = new LocalOptions(values);
        dispatcher.setOptions(options);
        NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        List<String> diagnostics = new ArrayList<>();
        NativeMessagingHooks hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                activity, options, diagnostics::add, dispatcher);
        try {
            assertTrue(diagnostics.toString(), hook.supports("native_reply"));
            Database database = new Database();
            ContentValues incoming = new ContentValues();
            incoming.put("conversation_id", "thread"); incoming.put("conversation_type", 1);
            incoming.put("sender", 200L); incoming.put("msg_server_id", "7001");
            incoming.put("created_time", System.currentTimeMillis()); incoming.put("type", 7);
            incoming.put("content", "{\"text\":\"收到消息\"}");
            framework.invoke(Database.class.getMethod("insert", String.class, String.class, ContentValues.class),
                    database, "msg", null, incoming);
            ScheduledThreadPoolExecutor scheduled = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
            Runnable reply = scheduled.getQueue().peek();
            if (changeMaster) {
                values.put("automation_enabled", false);
                LocalOptions changed = new LocalOptions(values);
                hook.setOptions(changed);
                dispatcher.setOptions(changed);
            }
            assertNotNull(reply); scheduled.getQueue().remove(reply);
            org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
            reply.run();
            ShadowLooper.idleMainLooper();
            View notice = replyNotice(activity, "100:thread:7001");
            assertNotNull(notice);
            assertEquals(0, database.imSdkContext.sends);
            if (disablePrivate) {
                values.put("reply_private_chats", false);
                LocalOptions changed = new LocalOptions(values);
                hook.setOptions(changed);
                dispatcher.setOptions(changed);
            }
            if (removedFromList) {
                FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                        Collections.emptySet());
            }
            if (cancel) notice.performLongClick();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(9));
            worker.runAll(); awaitResults(); ShadowLooper.idleMainLooper(); awaitResults();
            ShadowLooper.idleMainLooper(); worker.runAll();
            List<AutomationHistoryStore.Entry> records = new AutomationHistoryStore(activity).read("reply");
            assertEquals(1, records.size());
            assertEquals(cancel || removedFromList || disablePrivate ? "cancelled" : "sent", records.get(0).status);
            assertEquals(cancel || removedFromList || disablePrivate ? 0 : 1, database.imSdkContext.sends);
            if (!cancel && !removedFromList && !disablePrivate) {
                assertEquals(7, database.imSdkContext.last.type);
                assertEquals("固定回复", new org.json.JSONObject(database.imSdkContext.last.content).getString("text"));
                assertEquals("thread", database.imSdkContext.last.conversation.id());
            }
        } finally {
            hook.close(); awaitResults(); dispatcher.close(); ShadowLooper.idleMainLooper(); worker.runAll(); activity.finish();
            FollowListSnapshot.reset();
        }
    }

    public static final class Sdk {
        int sends; Message last;
        volatile long account = 100;
        final java.util.concurrent.CountDownLatch enumerated = new java.util.concurrent.CountDownLatch(1);
        boolean failNext; boolean suppressCallbacks;
        final List<Runnable> pendingCallbacks = new ArrayList<>();
        Runnable beforeSender = () -> { };
        final Map<String, Conversation> localConversations = new HashMap<>();
        final Map<String, Conversation> cachedConversations = new HashMap<>();
        int conversationLoads;
        boolean fillConversationCache;
        boolean throwAfterSend;
        public long uid() { return account; }
        public Bean bean() { return new Bean(); }
        public Conversations conversations() { return new Conversations(this, enumerated::countDown); }
    }
    public static final class Database {
        public Sdk imSdkContext = new Sdk();
        int insertCalls;
        public long insert(String table, String nullColumn, ContentValues values) { insertCalls++; return 1; }
    }
    public static final class GlobalObserver {
        public final Sdk imSdkContext;
        GlobalObserver(Sdk sdk) { imSdkContext = sdk; }
        public void onGetMessage(List<Message> messages, int source, FakeExtra extra) { }
    }
    public static final class FakeExtra { }
    public static final class Bean { public Builder builder() { return new Builder(); } }
    public static final class Builder {
        Message message = new Message();
        public void type(int type) { message.type = type; }
        public void content(String content) { message.content = content; }
        public void conversation(Conversation conversation) {
            message.conversation = conversation;
            message.conversationType = conversation.single() ? 1 : 2;
            message.orderIndex = 501;
        }
        public Message build() { message.sender = 100; return message; }
    }
    public static final class Message extends NativeAudioMessageSenderTest.Message {
        int type; String content; Conversation conversation;
        long sender; int conversationType; long orderIndex; long createdAt; int deleted;
        public final java.util.concurrent.ConcurrentHashMap<String, String> ext = new java.util.concurrent.ConcurrentHashMap<>();
        public long getSender() { return sender; }
        public long getCreatedAt() { return createdAt; }
        public String getContent() { return content; }
        public int getMsgType() { return type; }
        public String getConversationId() { return conversation.id(); }
        public int getConversationType() { return conversationType; }
        public int getSvrStatus() { return 0; }
        public int getDeleted() { return deleted; }
        public boolean isSelf() { return sender == 100; }
        public java.util.concurrent.ConcurrentHashMap<String, String> getExt() { return ext; }
    }
    public static final class Conversations {
        private final Sdk sdk;
        private final Runnable enumerate;
        Conversations(Sdk sdk, Runnable enumerate) { this.sdk = sdk; this.enumerate = enumerate; }
        public Conversation find(String id) {
            if (sdk.cachedConversations.containsKey(id)) return sdk.cachedConversations.get(id);
            return Arrays.asList("thread", "other", "third", "group").contains(id) ? new Conversation(id) : null;
        }
        public void getConversationAsync(String id, IRequestListener listener) {
            sdk.conversationLoads++;
            Conversation result = sdk.localConversations.get(id);
            if (result == null) { listener.onFailure(new IllegalStateException("Conversation unavailable")); return; }
            if (sdk.fillConversationCache) sdk.cachedConversations.put(id, result);
            listener.onSuccess(result);
        }
        public List<Conversation> all() { enumerate.run(); return Collections.emptyList(); }
    }
    public static final class Conversation {
        final String id;
        public Conversation(String id) { this.id = id; }
        public String id() { return id; }
        public List<Long> members() { return Arrays.asList(100L, 200L); }
        public Core core() { return new Core(); }
        public boolean single() { return !"group".equals(id); }
    }
    public static final class Core { public String name() { return "目标用户"; } }
    public static final class Text { public String text; public static Text create(String value) { Text text = new Text(); text.text = value; return text; } }
    public static final class Sender {
        final Sdk sdk; final IRequestListener listener;
        public Sender(Sdk sdk, IRequestListener listener) {
            this.sdk = sdk; this.listener = listener;
            sdk.beforeSender.run();
        }
        public void send(Message message, Map<String, String> extras) {
            sdk.sends++; sdk.last = message;
            if (sdk.throwAfterSend) throw new IllegalStateException("Native send threw after invocation");
            if (sdk.suppressCallbacks) { sdk.pendingCallbacks.add(() -> listener.onSuccess(message)); return; }
            if (sdk.failNext) {
                sdk.failNext = false;
                listener.onFailure(new IllegalStateException("Rejected by native SDK"));
            } else listener.onSuccess(message);
        }
    }

    private final class LimitedReplies implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final ManualExecutor worker = new ManualExecutor();
        final AutomationDispatcher dispatcher = new AutomationDispatcher(activity, ignored -> { }, worker);
        final NativeMessagingHooksTest.Framework framework = new NativeMessagingHooksTest.Framework();
        final Map<String, Object> values = new HashMap<>();
        final Database database = new Database();
        final NativeMessagingHooks hook;
        final ScheduledThreadPoolExecutor scheduled;
        int serverId = 8000;

        LimitedReplies(String... limits) throws Exception { this(false, limits); }

        LimitedReplies(boolean preserveLedger, String... limits) throws Exception {
            if (!preserveLedger) activity.getSharedPreferences("douai_reply_submissions", 0).edit().clear().commit();
            FollowListSnapshot.reset();
            values.put("native_auto_reply", true);
            values.put("reply_global", true);
            values.put("reply_group_chats", true);
            values.put("reply_save_history", true);
            values.put("auto_message_text", "reply");
            for (int i = 0; i < limits.length; i += 2) values.put(limits[i],
                    "reply_voice".equals(limits[i]) || LocalOptions.REPLY_RECOGNIZE_IMAGES.equals(limits[i])
                            || LocalOptions.REPLY_RECOGNIZE_VIDEO.equals(limits[i])
                            || "streak_enabled".equals(limits[i]) || "automation_enabled".equals(limits[i])
                            ? Boolean.parseBoolean(limits[i + 1]) : limits[i + 1]);
            LocalOptions options = new LocalOptions(values);
            dispatcher.setOptions(options);
            List<String> diagnostics = new ArrayList<>();
            hook = NativeMessagingHooks.install(framework.api, report(), getClass().getClassLoader(),
                    activity, options, diagnostics::add, dispatcher);
            assertTrue(diagnostics.toString(), hook.supports("native_reply"));
            scheduled = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
            for (Runnable task : scheduled.getQueue()) ((java.util.concurrent.Future<?>) task).cancel(false);
            scheduled.getQueue().clear();
        }

        void receive(long sender, String conversation) throws Throwable {
            Runnable reply = enqueue(sender, conversation, false);
            if (reply != null) reply.run();
            ShadowLooper.idleMainLooper();
        }

        Runnable enqueue(long sender, String conversation, boolean image) throws Throwable {
            return enqueueContent(sender, conversation, image ? 27 : 7, image
                    ? "{\"resource_url\":{\"url_list\":[\"https://p.byteimg.com/image.png\"]}}"
                    : "{\"text\":\"incoming\"}");
        }

        Runnable enqueueContent(long sender, String conversation, int type, String content) throws Throwable {
            ContentValues incoming = new ContentValues();
            incoming.put("conversation_id", conversation);
            incoming.put("conversation_type", "group".equals(conversation) ? 2 : 1);
            incoming.put("sender", sender);
            incoming.put("msg_server_id", Integer.toString(++serverId));
            incoming.put("created_time", System.currentTimeMillis());
            incoming.put("type", type);
            incoming.put("content", content);
            return enqueueValues(incoming);
        }

        Runnable enqueueValues(ContentValues incoming) throws Throwable {
            framework.invoke(Database.class.getMethod("insert", String.class, String.class, ContentValues.class), database, "msg", null, incoming);
            return takeReply();
        }

        Runnable enqueueGlobal(Message... messages) throws Throwable {
            Method received = GlobalObserver.class.getMethod("onGetMessage", List.class, int.class, FakeExtra.class);
            GlobalObserver observer = new GlobalObserver(database.imSdkContext);
            if (framework.hooks.containsKey(received)) framework.invoke(received, observer, Arrays.asList(messages), 0, new FakeExtra());
            else received.invoke(observer, Arrays.asList(messages), 0, new FakeExtra());
            return takeReply();
        }

        private Runnable takeReply() {
            Runnable reply = scheduled.getQueue().peek();
            if (reply != null) {
                scheduled.getQueue().remove(reply);
                org.robolectric.shadows.ShadowSystemClock.advanceBy(Duration.ofSeconds(2));
            }
            return reply;
        }

        void startBlockedImage(BlockingMediaConnection connection) throws Throwable {
            hook.mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> connection);
            Runnable reply = enqueue(200, "thread", true);
            assertNotNull(reply);
            scheduled.execute(reply);
            assertTrue("Image request did not reach the blocked transport", connection.blocked.await(2, TimeUnit.SECONDS));
        }

        void applyOptions() {
            LocalOptions changed = new LocalOptions(values);
            hook.setOptions(changed); dispatcher.setOptions(changed);
        }

        void awaitWorker() throws Exception {
            scheduled.submit(() -> { }).get(2, TimeUnit.SECONDS);
        }

        void runReply(Runnable reply) throws Exception {
            scheduled.execute(reply);
            awaitWorker();
        }

        void flush() {
            // A background generation first posts prepare to main; prepare then queues the native
            // action. Drain each handoff before its FIFO barrier, including the final history write.
            for (int round = 0; round < 3; round++) {
                ShadowLooper.idleMainLooper();
                worker.runAll();
                awaitResults();
            }
            ShadowLooper.idleMainLooper();
            worker.runAll();
        }

        List<AutomationHistoryStore.Entry> history() { return new AutomationHistoryStore(activity).read("reply"); }

        @Override public void close() {
            hook.close(); dispatcher.close(); ShadowLooper.idleMainLooper(); worker.runAll(); activity.finish();
            FollowListSnapshot.reset();
        }
    }

    private static java.util.concurrent.ThreadPoolExecutor resultExecutor() throws Exception {
        java.lang.reflect.Field field = NativeMessagingHooks.class.getDeclaredField("RESULTS_IO");
        field.setAccessible(true);
        return (java.util.concurrent.ThreadPoolExecutor) field.get(null);
    }

    private static void awaitResults() {
        try {
            java.util.concurrent.ThreadPoolExecutor results = resultExecutor();
            results.prestartCoreThread();
            java.util.concurrent.FutureTask<Void> barrier = new java.util.concurrent.FutureTask<>(() -> null);
            assertTrue("Reply result queue did not free a barrier slot", results.getQueue().offer(barrier, 2, TimeUnit.SECONDS));
            barrier.get(2, TimeUnit.SECONDS);
        } catch (Exception failure) { throw new AssertionError("Reply result persistence did not finish", failure); }
    }

    private static void configureAi(LimitedReplies fixture, okhttp3.mockwebserver.MockWebServer server) throws Exception {
        org.json.JSONObject config = new org.json.JSONObject().put("id", "im-media").put("name", "IM media fixture")
                .put("protocol", AiModelConfig.CHAT).put("apiEndpoint", server.url("/v1").toString())
                .put("modelName", "media-model").put("allowInsecureHttp", true).put("contextRounds", 1);
        fixture.values.put(AiModelSettings.KEY, new AiModelSettings(
                Collections.singletonList(AiModelConfig.fromJson(config)), "im-media").serialize());
        fixture.applyOptions();
    }

    private static Message incomingMessage(long sender, String conversation, long id, long createdAt) {
        Message result = new Message();
        result.sender = sender; result.conversation = new Conversation(conversation); result.id = id;
        result.createdAt = createdAt; result.type = 7; result.conversationType = "group".equals(conversation) ? 2 : 1;
        result.content = "{\"text\":\"global incoming\"}";
        return result;
    }

    private static ContentValues incomingValues(Message message) {
        ContentValues values = new ContentValues();
        values.put("conversation_id", message.getConversationId()); values.put("conversation_type", message.getConversationType());
        values.put("sender", message.getSender()); values.put("msg_server_id", Long.toString(message.getMsgId()));
        values.put("created_time", message.getCreatedAt()); values.put("type", message.getMsgType()); values.put("content", message.getContent());
        return values;
    }

    private static okhttp3.mockwebserver.MockResponse aiResponse(String text) throws Exception {
        return new okhttp3.mockwebserver.MockResponse().setHeader("Content-Type", "application/json")
                .setBody(new org.json.JSONObject().put("choices", new org.json.JSONArray().put(
                        new org.json.JSONObject().put("message", new org.json.JSONObject().put("content", text)))).toString());
    }

    private static List<String> inlineImageUrls(org.json.JSONArray messages) throws Exception {
        List<String> urls = new ArrayList<>();
        for (int i = 0; i < messages.length(); i++) {
            org.json.JSONArray parts = messages.getJSONObject(i).optJSONArray("content");
            if (parts == null) continue;
            for (int j = 0; j < parts.length(); j++) {
                org.json.JSONObject part = parts.optJSONObject(j);
                if (part != null && "image_url".equals(part.optString("type"))) {
                    urls.add(part.getJSONObject("image_url").getString("url"));
                }
            }
        }
        return urls;
    }

    private static View replyNotice(Activity activity, String identity) {
        View decor = activity.getWindow().getDecorView();
        View notice = decor.findViewWithTag("automation.notice." + identity);
        return notice == null ? decor.findViewWithTag("automation.reply.ring") : notice;
    }

    private static final class ImageMediaConnection extends java.net.HttpURLConnection {
        private final byte[] png;
        ImageMediaConnection() throws Exception {
            super(new java.net.URL("https://p.byteimg.com/image.png"));
            android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
            try {
                java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
                assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output));
                png = output.toByteArray();
            } finally { bitmap.recycle(); }
        }
        @Override public int getResponseCode() { return 200; }
        @Override public long getContentLengthLong() { return png.length; }
        @Override public java.io.InputStream getInputStream() { return new java.io.ByteArrayInputStream(png); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "adv_im_uid", Sdk.class.getMethod("uid"));
        add(rules, "adv_im_bean", Sdk.class.getMethod("bean"));
        add(rules, "adv_im_conversations", Sdk.class.getMethod("conversations"));
        add(rules, "adv_im_builder_factory", Bean.class.getMethod("builder"));
        add(rules, "adv_im_builder_type", Builder.class.getMethod("type", int.class));
        add(rules, "adv_im_builder_content", Builder.class.getMethod("content", String.class));
        add(rules, "adv_im_builder_conversation", Builder.class.getMethod("conversation", Conversation.class));
        add(rules, "adv_im_builder_build", Builder.class.getMethod("build"));
        add(rules, "adv_im_conversation", Conversations.class.getMethod("find", String.class));
        add(rules, "adv_im_conversation_async", Conversations.class.getMethod("getConversationAsync", String.class, IRequestListener.class));
        add(rules, "adv_im_global_received", GlobalObserver.class.getMethod("onGetMessage", List.class, int.class, FakeExtra.class));
        add(rules, "adv_im_all_conversations", Conversations.class.getMethod("all"));
        add(rules, "adv_im_conversation_id", Conversation.class.getMethod("id"));
        add(rules, "adv_im_conversation_members", Conversation.class.getMethod("members"));
        add(rules, "adv_im_conversation_core", Conversation.class.getMethod("core"));
        add(rules, "adv_im_conversation_name", Core.class.getMethod("name"));
        add(rules, "adv_im_single_chat", Conversation.class.getMethod("single"));
        add(rules, "adv_im_text_content", Text.class.getMethod("create", String.class));
        add(rules, "adv_im_send", Sender.class.getMethod("send", Message.class, Map.class));
        add(rules, "adv_im_db_insert", Database.class.getMethod("insert", String.class, String.class, ContentValues.class));
        NativeAudioMessageSenderTest.addRules(rules);
        return new HostScanReport("fixture", rules, false);
    }
    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "reply", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>(); boolean stopped;
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return stopped; }
    }
}
