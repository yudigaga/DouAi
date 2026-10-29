package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DebugLog;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;
import static com.douai.module.feature.NativeCommentReplyInboxTest.notice;
import static com.douai.module.feature.NativeCommentReplyInboxTest.nestedNotice;
import static com.douai.module.feature.NativeCommentReplyInboxTest.detailedNotice;
import static com.douai.module.feature.NativeCommentReplyInboxTest.parentlessDirectedNotice;
import static com.douai.module.feature.NativeCommentReplyInboxTest.page;
import static com.douai.module.feature.NativeCommentReplyInboxTest.report;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class CommentReplyHookTest {
    private Activity activity;
    private AutomationDispatcher dispatcher;
    private CommentReplyHook hook;
    private ManualExecutor scanner, generatorWorker, dispatchWorker, ledgerWorker;
    private FakeSource source;
    private FakeSender sender;
    private FakeGenerator generator;
    private final AtomicLong account = new AtomicLong(100);
    private final Map<String, Object> values = new HashMap<>();

    @Before public void setup() {
        NativeCommentReplyInboxTest.FakeGroupResolver.group = 44;
        NativeCommentReplyInboxTest.FakeGroupResolver.failure = null;
        NativeCommentReplyInboxTest.FakeGroupResolver.calls = 0;
        for (String file : new String[]{"douai_comment_reply_ledger", "douai_automation_limits", "douai_automation_history"}) {
            RuntimeEnvironment.getApplication().getSharedPreferences(file, Context.MODE_PRIVATE).edit().clear().commit();
        }
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        values.put("automation_enabled", true);
        values.put("native_auto_reply", true);
        values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        values.put("reply_daily_limit", "20");
        values.put("reply_user_cooldown_minutes", "0");
        values.put("reply_delay_seconds", "2");
        values.put("reply_mode", "ai");
        values.put("reply_tip", false);
        values.put("reply_save_history", true);
        source = new FakeSource();
        sender = new FakeSender();
        generator = new FakeGenerator();
        dispatchWorker = new ManualExecutor();
        dispatcher = new AutomationDispatcher(activity, ignored -> { }, dispatchWorker);
        dispatcher.setOptions(new LocalOptions(values));
        createHook();
    }

    @After public void close() {
        hook.close();
        ledgerWorker.runAll();
        dispatcher.close();
        dispatchWorker.runAll();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void firstOpenBaselinesExistingIdsWithoutReplyingAndStillAcceptsANewReplyInThatSecond() {
        source.baselineIds = List.of("old-in-same-second");
        source.events.put(100L, List.of(event("older", "root", "200", 999),
                event("old-in-same-second", "root", "200", 1000), event("new-in-same-second", "root", "200", 1000)));
        open();
        assertEquals(List.of(100L), source.baselines);
        assertEquals(0, source.scans.size());
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());
        assertEquals(1000, ledger().baseline(100));
        assertTrue(ledger().contains(100, "old-in-same-second"));

        hook.requestScan();
        hook.requestScan();
        processReplies();
        assertEquals(List.of("new-in-same-second"), generator.ids);
        assertEquals(List.of("new-in-same-second"), sender.sent);
        assertFalse(ledger().contains(100, "older"));
        hook.signal();
        hook.signal();
        advanceMillis(1000);
        assertEquals(List.of("new-in-same-second"), sender.sent);
        assertEquals(1, history().size());
    }

    @Test public void rawNativeNoticeCursorsEstablishBaselineAndDeliverTheNextReply() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        NativeCommentReplyInboxTest.Provider.b = nativeApi;
        NativeCommentReplyInboxTest.AccountFactory.service = new NativeCommentReplyInboxTest.Account();
        NativeCommentReplyInboxTest.AccountFactory.service.user = new NativeCommentReplyInboxTest.User("100");
        NativeCommentReplyInboxTest.Notice existingHead = notice("old-head", "existing-head", "200", "101", 1000);
        NativeCommentReplyInboxTest.Notice existingBoundary = notice("old-boundary", "existing-boundary", "201", "101", 1000);
        nativeApi.pages.add(page(true, 0, 1000, existingHead));
        nativeApi.pages.add(page(false, 0, 999, existingBoundary));
        NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), ignored -> { });
        hook.close();
        createHook(new CommentReplyHook.Source() {
            @Override public long baseline(long account, BooleanSupplier valid) throws Exception {
                return inbox.baseline(account, valid);
            }
            @Override public List<String> baselineIds() { return inbox.baselineCommentIds(); }
            @Override public long scannedThrough() { return inbox.scannedThrough(); }
            @Override public List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception {
                return inbox.scan(account, since, valid);
            }
            @Override public void close() { inbox.close(); }
        });

        open();
        assertEquals("The raw native cursors must allow the entire baseline second to be collected",
                1000, ledger().baseline(100));
        assertTrue(ledger().contains(100, "existing-head"));
        assertTrue(ledger().contains(100, "existing-boundary"));
        assertEquals(2, nativeApi.calls.size());
        assertEquals(0L, nativeApi.calls.get(1)[0]);
        assertEquals(1000L, nativeApi.calls.get(1)[1]);
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice nextReply = notice("next-notice", "next-reply", "202", "101", 1001);
        nativeApi.pages.add(page(true, 0, 1000, nextReply, existingHead));
        nativeApi.pages.add(page(false, 0, 999, existingBoundary));
        hook.requestScan();
        processReplies();

        assertEquals(4, nativeApi.calls.size());
        assertEquals(0L, nativeApi.calls.get(3)[0]);
        assertEquals(1000L, nativeApi.calls.get(3)[1]);
        assertEquals(List.of("next-reply"), generator.ids);
        assertEquals(List.of("next-reply"), sender.sent);
        assertEquals(1001, inbox.scannedThrough());
        assertEquals("sent", history().get(0).status);
    }

    @Test public void resolvedNativeReceivedGroupReachesAutomaticSendingExactlyOnce() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        nativeApi.expectedGroup = 44;
        NativeCommentReplyInboxTest.Notice existing = notice("existing-notice", "existing-id", "200", "101", 1000);
        nativeApi.pages.add(page(false, 0, 0, existing));
        NativeCommentReplyInbox inbox = useNativeInbox(nativeApi);

        open();

        assertTrue(ledger().initialized(100));
        assertEquals("The native received-comment route must establish the known server baseline",
                1000, ledger().baseline(100));
        assertEquals(List.of("existing-id"), inbox.baselineCommentIds());
        assertTrue(ledger().contains(100, "existing-id"));
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice fresh = notice("fresh-notice", "fresh-id", "201", "101", 1001);
        nativeApi.pages.add(page(false, 0, 0, existing, fresh));
        hook.requestScan();
        processReplies();

        assertEquals(List.of("fresh-id"), generator.ids);
        assertEquals(List.of("fresh-id"), sender.sent);
        assertEquals(1, history().size());
        assertEquals("sent", history().get(0).status);

        nativeApi.pages.add(page(false, 0, 0, existing, fresh));
        hook.requestScan();
        pump();
        assertTrue("An acknowledged reply must not queue another generation on rescan",
                ((Map<?, ?>) HostTargets.read(hook, "tasks")).isEmpty());
        assertEquals(List.of("fresh-id"), generator.ids);
        assertEquals(List.of("fresh-id"), sender.sent);
        assertEquals(1, history().size());
        assertEquals(3, nativeApi.calls.size());
        assertEquals(3, NativeCommentReplyInboxTest.FakeGroupResolver.calls);
        for (Object[] arguments : nativeApi.calls) {
            assertEquals(44, arguments[3]);
            assertEquals(0, arguments[5]);
        }
    }

    @Test public void sparseNoticeReplaysPastAnOldWatermarkThroughRealThreadLookupAndSendsOnlyOnce() throws Exception {
        values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, true);
        values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, true);
        CommentReplyLedger prior = ledger();
        assertTrue(prior.initialize(100, 1000, Collections.emptyList()));
        prior.advance(100, 5000);
        android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
        assertTrue(records.edit().putInt("inbox_model.100", 3).commit());

        NativeCommentThreadLookupTest.LookupHarness host = NativeCommentThreadLookupTest.harness("101", "thread-reply", 900);
        host.incoming.replyToReplyId = "101";
        host.incoming.createTime = 1500;
        NativeCommentReplyInboxTest.Notice sparse = NativeCommentReplyInboxTest.sparseNotice(
                "sparse-notice", "thread-reply", "200", 2000);
        NativeCommentReplyInboxTest.Comment raw = sparse.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        nativeApi.expectedGroup = 44;
        nativeApi.pages.add(page(false, 0, 0, sparse));
        useNativeInbox(nativeApi, host.lookup);

        open();

        assertEquals("A schema upgrade must replay from the original baseline before scanning the sparse notice",
                1000, ledger().watermark(100));
        assertEquals(1000, ledger().baseline(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, records.getInt("inbox_model.100", 0));
        processReplies();
        assertEquals(List.of("thread-reply"), generator.ids);
        assertEquals(List.of("thread-reply"), sender.sent);
        assertEquals(1, generator.generatedImages.size());
        assertTrue("The real default loader must not substitute work media for a text comment", generator.generatedImages.get(0).isEmpty());
        assertTrue(generator.prompts.get(0).contains("我的原评论：my original"));
        assertTrue(generator.prompts.get(0).contains("对方的回复：" + host.incoming.text));
        assertFalse(generator.prompts.get(0).contains("作品："));
        assertEquals(1, sender.submittedEvents.size());
        CommentReplyEvent event = sender.submittedEvents.get(0);
        assertSame("The server thread's complete target must survive generation and dispatch", host.incoming, event.targetComment);
        assertSame(host.work, event.work);
        assertEquals("101", event.rootId);
        assertEquals(2000, event.createdAt);
        assertEquals("my original", event.originalText);
        assertEquals(900, host.original.createTime);
        assertEquals("101", host.incoming.replyId);
        assertEquals("101", host.incoming.replyToReplyId);
        assertEquals("0", raw.replyId);
        assertEquals("", raw.replyToReplyId);
        assertEquals(1, history().size());
        assertEquals("sent", history().get(0).status);
        assertEquals("The submitted task defers progress until the next completed scan", 1000, ledger().watermark(100));

        nativeApi.pages.add(page(false, 0, 0, sparse));
        nativeApi.pages.add(page(false, 0, 0, sparse)); // Completion also schedules an automatic recheck.
        hook.requestScan();
        processReplies();

        assertEquals("Rescanning the same recovered notice must preserve the incoming CID claim", List.of("thread-reply"), sender.sent);
        assertEquals(List.of("thread-reply"), generator.ids);
        assertEquals(1, history().size());
        assertTrue(nativeApi.calls.size() >= 2);
        assertEquals("Every repeated notice scan must reach the real lookup", nativeApi.calls.size(), host.own.starts.size());
        assertEquals(2000, ledger().watermark(100));
    }

    @Test public void aLaterAlreadyHandledCanonicalReplyCannotLiftTheUnresolvedNativeWatermarkCeiling() throws Exception {
        CommentReplyLedger prior = ledger();
        assertTrue(prior.initialize(100, 100, List.of()));
        assertTrue(prior.claim(100, "already-sent", "102", 0));
        assertTrue(prior.submitted(100, "already-sent"));
        prior.finish(100, "already-sent", "sent");
        NativeCommentReplyInboxTest.Api api = new NativeCommentReplyInboxTest.Api();
        api.pages.add(page(false, 0, 0,
                NativeCommentReplyInboxTest.sparseNotice("unresolved", "pending", "200", 210),
                notice("later", "already-sent", "201", "102", 1000)));
        useNativeInbox(api, new NativeCommentReplyInboxTest.ThreadProbe());

        open();
        processReplies();

        assertEquals("The older sparse arrival must remain inside the next scan range", 209, ledger().watermark(100));
        assertTrue(sender.sent.isEmpty());
        assertTrue(prior.contains(100, "already-sent"));
    }

    @Test public void nestedAndDetailedNativeRepliesReachSendingOnceAfterTheirSharedBaseline() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        nativeApi.expectedGroup = 44;
        NativeCommentReplyInboxTest.Notice oldNested = nestedNotice("old-f-notice", "old-f-id", "200", "101", 1000);
        NativeCommentReplyInboxTest.Notice oldDetailed = detailedNotice("old-q-notice", "old-q-id", "201", "102", 1000);
        nativeApi.pages.add(page(false, 0, 0, oldNested, oldDetailed));
        NativeCommentReplyInbox inbox = useNativeInbox(nativeApi);

        open();

        assertEquals(1000, ledger().baseline(100));
        assertEquals(List.of("old-f-id", "old-q-id"), inbox.baselineCommentIds());
        assertTrue(ledger().contains(100, "old-f-id"));
        assertTrue(ledger().contains(100, "old-q-id"));
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice sameNested = nestedNotice("new-f-same-notice", "new-f-same", "202", "110", 1000);
        NativeCommentReplyInboxTest.Notice sameDetailed = detailedNotice("new-q-same-notice", "new-q-same", "203", "111", 1000);
        NativeCommentReplyInboxTest.Notice laterNested = nestedNotice("new-f-later-notice", "new-f-later", "204", "120", 1001);
        NativeCommentReplyInboxTest.Notice laterDetailed = detailedNotice("new-q-later-notice", "new-q-later", "205", "121", 1002);
        nativeApi.pages.add(page(false, 0, 0, oldNested, oldDetailed, sameNested, sameDetailed, laterNested, laterDetailed));
        hook.requestScan();
        processReplies();

        List<String> expected = List.of("new-f-same", "new-q-same", "new-f-later", "new-q-later");
        assertEquals(expected, generator.ids);
        assertEquals(expected, sender.sent);
        assertEquals(4, history().size());
        assertTrue(history().stream().allMatch(entry -> "sent".equals(entry.status)));

        nativeApi.pages.add(page(false, 0, 0, oldNested, oldDetailed, sameNested, sameDetailed, laterNested, laterDetailed));
        hook.requestScan();
        pump();
        assertTrue(((Map<?, ?>) HostTargets.read(hook, "tasks")).isEmpty());
        assertEquals(expected, generator.ids);
        assertEquals(expected, sender.sent);
        assertEquals(4, history().size());
        assertEquals(1002, ledger().watermark(100));
    }

    @Test public void directedParentlessNativeReplySendsItsNormalizedCloneOnceAndPreservesTheNotice() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        nativeApi.expectedGroup = 44;
        NativeCommentReplyInboxTest.Notice existing = nestedNotice("existing-notice", "existing-id", "201", "102", 1000);
        nativeApi.pages.add(page(false, 0, 0, existing));
        useNativeInbox(nativeApi);
        open();
        assertEquals(1000, ledger().baseline(100));
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice fresh = parentlessDirectedNotice("directed-notice", "402", "200", "101", 1001);
        NativeCommentReplyInboxTest.Comment original = fresh.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
        nativeApi.pages.add(page(false, 0, 0, existing, fresh));
        hook.requestScan();
        processReplies();

        assertEquals(List.of("402"), generator.ids);
        assertEquals(List.of("402"), sender.sent);
        assertEquals(1, sender.submittedEvents.size());
        CommentReplyEvent submitted = sender.submittedEvents.get(0);
        assertEquals("402", submitted.incomingId);
        assertEquals("101", submitted.rootId);
        assertEquals("own original", submitted.originalText);
        assertNotSame(original, submitted.targetComment);
        NativeCommentReplyInboxTest.Comment normalized = (NativeCommentReplyInboxTest.Comment) submitted.targetComment;
        assertEquals("402", normalized.cid);
        assertEquals("101", normalized.replyId);
        assertEquals("0", normalized.replyToReplyId);
        assertEquals("0", original.replyId);
        assertEquals("", original.replyToReplyId);
        assertSame(original, fresh.interactiveGeneralNoticeStruct.additionalInfo.comment.a);
        assertEquals(1, history().size());
        assertEquals("sent", history().get(0).status);

        nativeApi.pages.add(page(false, 0, 0, existing, fresh));
        hook.requestScan();
        pump();
        assertTrue(((Map<?, ?>) HostTargets.read(hook, "tasks")).isEmpty());
        assertEquals(List.of("402"), generator.ids);
        assertEquals(List.of("402"), sender.sent);
        assertEquals(1, sender.submittedEvents.size());
        assertEquals(1, history().size());
        assertEquals("0", original.replyId);
        assertEquals("", original.replyToReplyId);
        assertSame(original, fresh.interactiveGeneralNoticeStruct.additionalInfo.comment.a);
    }

    @Test public void legacyModelWatermarkIsReplayedOnceWithoutReleasingPriorNativeSendClaims() throws Exception {
        CommentReplyLedger prior = ledger();
        assertTrue(prior.initialize(100, 1000, List.of("old-f-id", "old-q-id")));
        android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
        Map<String, String> protectedClaims = new HashMap<>();
        for (String status : List.of("sent", "submitted", "unknown")) {
            String id = "preserved-" + status;
            assertTrue(prior.claim(100, id, "root-" + status, 0));
            assertTrue(prior.submitted(100, id));
            if (!"submitted".equals(status)) prior.finish(100, id, status);
            String key = "incoming.100." + id;
            protectedClaims.put(key, records.getString(key, null));
        }
        prior.advance(100, 10000);
        assertTrue(records.edit().remove("inbox_model.100").commit());
        assertEquals(10000, prior.watermark(100));

        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        nativeApi.expectedGroup = 44;
        NativeCommentReplyInboxTest.Notice missedNested = nestedNotice("missed-f-notice", "missed-f-id", "200", "110", 1600);
        NativeCommentReplyInboxTest.Notice missedDetailed = detailedNotice("missed-q-notice", "missed-q-id", "201", "111", 1700);
        NativeCommentReplyInboxTest.Notice beforeBaseline = nestedNotice("before-baseline", "older-id", "202", "112", 999);
        NativeCommentReplyInboxTest.Notice alreadySent = nestedNotice("sent-notice", "preserved-sent", "203", "root-sent", 1500);
        NativeCommentReplyInboxTest.Notice alreadySubmitted = detailedNotice("submitted-notice", "preserved-submitted", "204", "root-submitted", 1501);
        NativeCommentReplyInboxTest.Notice unknown = nestedNotice("unknown-notice", "preserved-unknown", "205", "root-unknown", 1502);
        nativeApi.pages.add(page(false, 0, 0, missedNested, missedDetailed, beforeBaseline, alreadySent, alreadySubmitted, unknown));
        useNativeInbox(nativeApi);

        open();

        assertEquals("The upgraded model must replay from the original server baseline", 1000, ledger().watermark(100));
        assertEquals(1000, ledger().baseline(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, records.getInt("inbox_model.100", 0));
        processReplies();
        assertEquals(List.of("missed-f-id", "missed-q-id"), generator.ids);
        assertEquals(List.of("missed-f-id", "missed-q-id"), sender.sent);
        assertFalse(ledger().contains(100, "older-id"));
        assertTrue(ledger().contains(100, "old-f-id"));
        assertTrue(ledger().contains(100, "old-q-id"));
        assertEquals(2, history().size());
        for (Map.Entry<String, String> entry : protectedClaims.entrySet()) {
            assertEquals(entry.getValue(), records.getString(entry.getKey(), null));
        }
        for (String status : List.of("sent", "submitted", "unknown")) {
            assertFalse(ledger().retryable(100, "preserved-" + status));
        }

        nativeApi.pages.add(page(false, 0, 0, missedNested, missedDetailed, beforeBaseline, alreadySent, alreadySubmitted, unknown));
        hook.requestScan();
        pump();
        assertTrue(((Map<?, ?>) HostTargets.read(hook, "tasks")).isEmpty());
        assertEquals(List.of("missed-f-id", "missed-q-id"), sender.sent);
        assertEquals(1700, ledger().watermark(100));

        nativeApi.pages.add(page(false, 0, 0));
        hook.requestScan();
        pump();
        assertEquals("The current schema must not rewind a later successful scan again", 1700, ledger().watermark(100));
        assertEquals(1000, ledger().baseline(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, records.getInt("inbox_model.100", 0));
        assertEquals(List.of("missed-f-id", "missed-q-id"), generator.ids);
        assertEquals(List.of("missed-f-id", "missed-q-id"), sender.sent);
        assertEquals(2, history().size());
    }

    @Test public void emptyAndZeroMinNativePagesEstablishBaselineWithoutLosingNewSameSecondReplies() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        NativeCommentReplyInboxTest.Notice oldHead = notice("old-head", "old-head-id", "200", "101", 1000);
        NativeCommentReplyInboxTest.Notice oldMiddle = notice("old-middle", "old-middle-id", "201", "101", 1000);
        NativeCommentReplyInboxTest.Notice oldTail = notice("old-tail", "old-tail-id", "202", "101", 1000);
        nativeApi.pages.add(page(true, 0, 0));
        nativeApi.pages.add(page(true, 230, 0, oldHead));
        nativeApi.pages.add(page(true, 230, 0, oldMiddle));
        nativeApi.pages.add(page(false, 230, 0, oldTail));
        NativeCommentReplyInbox inbox = useNativeInbox(nativeApi);

        open();

        assertTrue("Transient empty pages and an absent min cursor must not leave the account uninitialized",
                ledger().initialized(100));
        assertEquals(1000, ledger().baseline(100));
        assertEquals(List.of("old-head-id", "old-middle-id", "old-tail-id"), inbox.baselineCommentIds());
        for (String id : inbox.baselineCommentIds()) assertTrue(ledger().contains(100, id));
        assertEquals(4, nativeApi.calls.size());
        assertEquals(0L, nativeApi.calls.get(1)[0]);
        assertEquals(0L, nativeApi.calls.get(1)[1]);
        assertEquals(230L, nativeApi.calls.get(3)[0]);
        assertEquals(0L, nativeApi.calls.get(3)[1]);
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice sameSecond = notice("same-second-new", "same-second-new-id", "203", "101", 1000);
        NativeCommentReplyInboxTest.Notice later = notice("later-new", "later-new-id", "204", "101", 1001);
        nativeApi.pages.add(page(true, 0, 0));
        nativeApi.pages.add(page(true, 240, 0, sameSecond, later, oldHead));
        nativeApi.pages.add(page(true, 240, 0, oldMiddle));
        nativeApi.pages.add(page(false, 240, 0, oldTail));
        hook.requestScan();
        processReplies();

        assertEquals(List.of("same-second-new-id", "later-new-id"), generator.ids);
        assertEquals(List.of("same-second-new-id", "later-new-id"), sender.sent);
        assertEquals(1001, inbox.scannedThrough());
        assertEquals(2, history().size());
        assertTrue(history().stream().allMatch(entry -> "sent".equals(entry.status)));

        nativeApi.pages.add(page(false, 0, 0, oldHead, oldMiddle, oldTail, sameSecond, later));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("same-second-new-id", "later-new-id"), generator.ids);
        assertEquals(List.of("same-second-new-id", "later-new-id"), sender.sent);
        assertEquals(2, history().size());
    }

    @Test public void stalledNativeBaselineStaysUninitializedAndTheNextCompleteReadRecovers() throws Exception {
        NativeCommentReplyInboxTest.Api nativeApi = new NativeCommentReplyInboxTest.Api();
        for (int index = 0; index < 3; index++) nativeApi.pages.add(page(true, 0, 0));
        NativeCommentReplyInbox inbox = useNativeInbox(nativeApi);

        open();

        assertEquals("A stalled read must stop after its bounded retries", 3, nativeApi.calls.size());
        assertFalse(ledger().initialized(100));
        assertEquals(0, ledger().baseline(100));
        assertTrue(inbox.baselineCommentIds().isEmpty());
        assertEquals("扫描暂未完成，稍后重试", hook.diagnostic());
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());
        assertTrue(history().isEmpty());

        NativeCommentReplyInboxTest.Notice existing = notice("recovered-old", "recovered-old-id", "200", "101", 1000);
        nativeApi.pages.add(page(false, 0, 0, existing));
        hook.requestScan();
        pump();
        assertTrue(ledger().initialized(100));
        assertEquals(1000, ledger().baseline(100));
        assertTrue(ledger().contains(100, "recovered-old-id"));
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());

        NativeCommentReplyInboxTest.Notice fresh = notice("recovered-new", "recovered-new-id", "201", "101", 1001);
        nativeApi.pages.add(page(false, 0, 0, existing, fresh));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("recovered-new-id"), generator.ids);
        assertEquals(List.of("recovered-new-id"), sender.sent);
        assertEquals("sent", history().get(0).status);
    }

    @Test public void realtimeSignalsCoalesceAndInternalActivityTransitionsDoNotRescanTheInbox() {
        open();
        hook.signal();
        hook.signal();
        hook.signal();
        advanceMillis(999);
        assertEquals(0, source.scans.size());
        advanceMillis(1);
        assertEquals(1, source.scans.size());

        Activity internal = Robolectric.buildActivity(Activity.class).create().get();
        hook.onActivityPaused(activity);
        hook.onActivityResumed(internal);
        advanceMillis(600);
        assertEquals(1, source.scans.size());
        hook.onActivityPaused(internal);
        advanceMillis(600);
        hook.onActivityResumed(activity);
        pump();
        assertEquals(2, source.scans.size());
        assertEquals(1, source.baselines.size());
        internal.finish();
    }

    @Test public void disabledForegroundReplyDoesNotPollTheAccountAndReenableRestartsScanning() throws Exception {
        java.util.concurrent.atomic.AtomicInteger accountReads = new java.util.concurrent.atomic.AtomicInteger();
        replaceField("ownAccount", (java.util.function.LongSupplier) () -> {
            accountReads.incrementAndGet();
            return account.get();
        });
        configure(LocalOptions.COMMENT_REPLY_ENABLED, false);
        open();
        accountReads.set(0);
        advanceMillis(10 * 60_000);
        assertEquals("Disabled comment replies must not keep an account polling timer", 0, accountReads.get());
        assertTrue(source.baselines.isEmpty());

        configure(LocalOptions.COMMENT_REPLY_ENABLED, true);
        advanceMillis(1000);
        assertEquals(List.of(100L), source.baselines);
        configure(LocalOptions.COMMENT_REPLY_ENABLED, false);
        accountReads.set(0);
        hook.signal();
        advanceMillis(10 * 60_000);
        assertEquals(0, accountReads.get());
        assertTrue(source.scans.isEmpty());
    }

    @Test public void ownedWorkerExitsWhenIdleRestartsAndHookCloseStillDrainsLedgerCallbacks() throws Exception {
        java.util.concurrent.ThreadPoolExecutor io = CommentReplyHook.newWorker("comment-reply-idle-test");
        io.setKeepAliveTime(1, TimeUnit.MILLISECONDS);
        java.util.concurrent.atomic.AtomicReference<Thread> thread = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ThreadFactory factory = io.getThreadFactory();
        io.setThreadFactory(action -> { Thread created = factory.newThread(action); thread.set(created); return created; });
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        try {
            assertEquals("first", io.submit(() -> "first").get(2, TimeUnit.SECONDS));
            Thread first = thread.get();
            first.join(2_000);
            assertFalse("A completed feature must not keep its ordinary worker alive", first.isAlive());
            java.util.concurrent.CountDownLatch began = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicInteger persisted = new java.util.concurrent.atomic.AtomicInteger();
            io.execute(() -> {
                began.countDown();
                try { release.await(2, TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                persisted.incrementAndGet();
            });
            assertTrue(began.await(2, TimeUnit.SECONDS));
            assertNotSame("The lane must be reusable after idle thread reclamation", first, thread.get());
            io.execute(persisted::incrementAndGet);
            replaceField("ledgerWorker", io);
            hook.close();
            release.countDown();
            assertTrue(io.awaitTermination(2, TimeUnit.SECONDS));
            assertEquals("Closing the hook must preserve already queued durable outcome callbacks", 2, persisted.get());
        } finally { release.countDown(); io.shutdownNow(); }
    }

    @Test public void leavingTheForegroundDuringGenerationCancelsItAndReopeningProcessesNewReplies() {
        open();
        source.events.put(100L, List.of(event("before-background", "root", "200", 1001)));
        generator.duringGenerate = () -> {
            hook.onActivityPaused(activity);
            advanceMillis(600);
        };
        hook.requestScan();
        processReplies();
        assertEquals(List.of("before-background"), generator.ids);
        assertTrue(sender.sent.isEmpty());
        assertTrue(generator.cancels > 0);
        assertTrue(ledger().retryable(100, "before-background"));
        int scansBeforeBackgroundSignal = source.scans.size();
        hook.signal();
        advanceMillis(1000);
        assertEquals(scansBeforeBackgroundSignal, source.scans.size());

        generator.duringGenerate = null;
        source.events.put(100L, List.of(event("after-reopen", "root", "200", 1002)));
        hook.onActivityResumed(activity);
        processReplies();
        assertEquals(List.of("after-reopen"), sender.sent);
        assertEquals("sent", history().get(0).status);
    }

    @Test public void backgroundingBeforeTheDispatchWorkerRunsCannotSubmitAPreparedReply() {
        open();
        source.events.put(100L, List.of(event("queued", "root", "200", 1001)));
        hook.requestScan();
        scanner.runAll();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
        generatorWorker.runAll();
        ShadowLooper.idleMainLooper();
        hook.onActivityPaused(activity);
        advanceMillis(600);
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().retryable(100, "queued"));
        assertFalse(history().stream().anyMatch(entry -> "sent".equals(entry.status)));
    }

    @Test public void switchingAccountsWhileGeneratingNeverPublishesThePreviousAccountsReply() {
        open();
        source.events.put(100L, List.of(event("account-a", "root-a", "200", 1001)));
        source.events.put(101L, List.of(event("account-b", "root-b", "201", 1001)));
        generator.duringGenerate = () -> {
            account.set(101);
            hook.requestScan();
        };
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().retryable(100, "account-a"));
        assertTrue(ledger().initialized(101));
        generator.duringGenerate = null;
        hook.requestScan();
        processReplies();
        assertEquals(List.of("account-b"), sender.sent);
        assertEquals(List.of(100L, 101L), generator.accounts);
        assertFalse(ledger().contains(101, "account-a"));
        assertFalse(ledger().contains(100, "account-b"));
    }

    @Test public void aDailyLimitReachedByTheFirstQueuedReplyDefersTheOtherWithoutTerminalCancellation() {
        configure("reply_daily_limit", "1");
        open();
        source.events.put(100L, List.of(event("first", "root-a", "200", 1001), event("later", "root-b", "201", 1002)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("first"), sender.sent);
        assertTrue("An unsent reply must be available after the daily allowance resets", ledger().retryable(100, "later"));
        assertEquals(1000, ledger().watermark(100));
        configure("reply_daily_limit", "2");
        hook.requestScan();
        processReplies();
        assertEquals(List.of("first", "later"), sender.sent);
    }

    @Test public void sameUserCooldownAlsoDefersAnUnsentQueuedReply() {
        configure("reply_user_cooldown_minutes", "1");
        open();
        source.events.put(100L, List.of(event("first", "root-a", "200", 1001), event("later", "root-b", "200", 1002)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("first"), sender.sent);
        assertTrue(ledger().retryable(100, "later"));
        configure("reply_user_cooldown_minutes", "0");
        hook.requestScan();
        processReplies();
        assertEquals(List.of("first", "later"), sender.sent);
    }

    @Test public void durableUnknownResultsAndAThreadLimitSurviveHookRecreationWithoutResending() {
        configure("comment_reply_thread_limit", "1");
        sender.outcome = "submitted";
        open();
        source.events.put(100L, List.of(event("uncertain", "root", "200", 1001),
                event("second-in-thread", "root", "201", 1002), event("third-in-thread", "root", "202", 1003),
                event("over-thread-cap", "root", "203", 1004)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("uncertain", "second-in-thread", "third-in-thread"), sender.sent);
        assertTrue(ledger().threadExhausted(100, "root", 3));
        assertFalse(ledger().retryable(100, "uncertain"));
        hook.close();
        createHook();
        open();
        assertEquals(List.of("uncertain", "second-in-thread", "third-in-thread"), sender.sent);
        assertEquals(1, source.baselines.size());
        assertFalse(ledger().contains(100, "over-thread-cap"));
        assertEquals("submitted", history().get(0).status);
    }

    @Test public void aScanFailureDoesNotAdvanceTheCheckpointAndTheNextScanCanRecover() {
        open();
        source.through = 1400;
        source.failScan = true;
        hook.requestScan();
        pump();
        assertEquals(1000, ledger().watermark(100));
        assertEquals("扫描暂未完成，稍后重试", hook.diagnostic());
        source.failScan = false;
        hook.requestScan();
        pump();
        assertEquals(1400, ledger().watermark(100));
        assertEquals(List.of(1000L, 1000L), source.since);
    }

    @Test public void cancelledInboxScanHasACancelledTerminalWithoutChangingCheckpointOrRecovery() {
        com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
        List<String> records = java.util.Collections.synchronizedList(new ArrayList<>());
        com.douai.module.diagnostics.DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            open();
            source.through = 1400;
            source.scanFailure = new java.util.concurrent.CancellationException("private account detail");
            hook.requestScan();
            pump();
            assertEquals(1000, ledger().watermark(100));
            assertTrue(sender.sent.isEmpty());
            assertTrue(com.douai.module.diagnostics.DouAiLog.awaitIdle(3000));
            String terminal = records.stream().filter(line -> line.contains("\"feature_id\":\"comment_reply_inbox\"")
                    && line.contains("\"event\":\"operation_finished\"") && line.contains("scan_cancelled")).findFirst().orElseThrow();
            assertTrue(terminal, terminal.contains("\"outcome\":\"cancelled\""));
            assertFalse(terminal.contains("private account detail"));
            source.scanFailure = null;
            hook.requestScan();
            pump();
            assertEquals(1400, ledger().watermark(100));
            assertEquals(List.of(1000L, 1000L), source.since);
        } finally {
            com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
            com.douai.module.diagnostics.DouAiLog.setFrameworkSink(null);
        }
    }

    @Test public void scanFailureClassificationPreservesWrappedCancellationTimeoutAndActualFailure() {
        assertEquals(com.douai.module.diagnostics.DouAiLog.Outcome.CANCELLED,
                CommentReplyHook.scanFailureOutcome(new java.lang.reflect.InvocationTargetException(
                        new java.util.concurrent.CancellationException())));
        assertEquals(com.douai.module.diagnostics.DouAiLog.Outcome.CANCELLED,
                CommentReplyHook.scanFailureOutcome(new InterruptedException()));
        assertEquals(com.douai.module.diagnostics.DouAiLog.Outcome.TIMED_OUT,
                CommentReplyHook.scanFailureOutcome(new java.util.concurrent.ExecutionException(new java.util.concurrent.TimeoutException())));
        assertEquals(com.douai.module.diagnostics.DouAiLog.Outcome.FAILED,
                CommentReplyHook.scanFailureOutcome(new NativeCommentReplyInbox.ScanFailure(NativeCommentReplyInbox.FailureReason.RESPONSE_MODEL)));
    }

    @Test public void queuedReplyRecordsItsActualLiveGenerationAndSendGuardSnapshotsWithoutChangingReadTiming() throws Exception {
        com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        List<String> records = java.util.Collections.synchronizedList(new ArrayList<>());
        com.douai.module.diagnostics.DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            String admissionRevision = new LocalOptions(values).diagnosticRevision();
            com.douai.module.diagnostics.DouAiLog.configRevision(admissionRevision);
            open();
            source.events.put(100L, List.of(event("live-config", "root", "200", 1001)));
            hook.requestScan();
            pump(); // The delayed task exists, but has not captured generation settings yet.
            assertTrue(generator.ids.isEmpty());
            configure("download_enabled", true); // Unrelated settings do not invalidate this task.
            String generationRevision = new LocalOptions(values).diagnosticRevision();
            com.douai.module.diagnostics.DouAiLog.configRevision(generationRevision);
            Map<String, Object> sendValues = new HashMap<>(values);
            sendValues.put("download_name_template", "guard-fixture-name");
            String sendRevision = new LocalOptions(sendValues).diagnosticRevision();
            generator.duringGenerate = () -> {
                configure("download_name_template", "guard-fixture-name");
                com.douai.module.diagnostics.DouAiLog.configRevision(sendRevision);
            };
            processReplies();
            assertEquals(List.of("live-config"), generator.ids);
            assertEquals(List.of("live-config"), sender.sent);
            assertEquals("sent", history().get(0).status);
            assertNotEquals(admissionRevision, generationRevision);
            assertNotEquals(generationRevision, sendRevision);
            assertTrue(com.douai.module.diagnostics.DouAiLog.awaitIdle(3000));
            String started = records.stream().filter(line -> line.contains("\"feature_id\":\"auto_comment_reply\"")
                    && line.contains("\"event\":\"operation_started\"")).findFirst().orElseThrow();
            String trace = new org.json.JSONObject(started).getString("trace_id");
            Map<String, String> selected = new HashMap<>();
            for (String line : records) {
                org.json.JSONObject logged = new org.json.JSONObject(line);
                if (!trace.equals(logged.optString("trace_id"))) continue;
                assertEquals(admissionRevision, logged.getString("config_revision"));
                if ("configuration_selected".equals(logged.optString("event"))) {
                    selected.put(logged.getString("config_selection_policy"), logged.getString("effective_config_revision"));
                }
            }
            assertEquals(generationRevision, selected.get("live_at_generation"));
            assertEquals(sendRevision, selected.get("live_send_guard"));
        } finally {
            com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
            com.douai.module.diagnostics.DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void aScanFailureLogsASafeReasonWithoutHostPayload() {
        List<String> diagnostics = new ArrayList<>();
        hook.close();
        createHook(source, diagnostics::add);
        open();
        source.through = 1400;
        source.scanFailure = new NativeCommentReplyInbox.ScanFailure(
                NativeCommentReplyInbox.FailureReason.PAGINATION_NO_PROGRESS,
                new IllegalStateException("uid=998877; token=fixture-host-token; reply=private-comment-text"));

        hook.requestScan();
        pump();

        assertEquals(1000, ledger().watermark(100));
        assertEquals("扫描暂未完成，稍后重试", hook.diagnostic());
        assertEquals(1, diagnostics.size());
        String failure = diagnostics.get(0);
        assertTrue(failure.contains("Comment reply scan failed:"));
        assertTrue(failure.contains("reason=pagination_no_progress"));
        assertTrue(failure.contains("type=ScanFailure"));
        assertTrue(failure.contains("cause=IllegalStateException"));
        assertFalse(failure.contains("998877"));
        assertFalse(failure.contains("fixture-host-token"));
        assertFalse(failure.contains("private-comment-text"));
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());
    }

    @Test public void aLateSuccessResolvesUnknownHistoryWithoutAnotherNativeSubmission() {
        sender.outcome = "submitted";
        open();
        source.events.put(100L, List.of(event("late", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals("submitted", history().get(0).status);
        sender.callbacks.get(0).accept("sent");
        pump();
        assertEquals("sent", history().get(0).status);
        assertFalse(ledger().retryable(100, "late"));
        hook.requestScan();
        pump();
        assertEquals(List.of("late"), sender.sent);
        assertEquals(1, history().size());
    }

    @Test public void durableReservationAndReceiptCommitsRunOnlyOnTheLedgerWorker() throws Exception {
        List<Thread> commits = new ArrayList<>();
        android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
        replaceField("ledger", new CommentReplyLedger(records, java.time.Clock.systemUTC(), editor -> {
            commits.add(Thread.currentThread());
            return editor.commit();
        }));
        replaceField("rateLimiter", new AutomationRateLimiter(activity.getSharedPreferences("douai_automation_limits", Context.MODE_PRIVATE),
                java.time.Clock.systemUTC(), editor -> {
                    commits.add(Thread.currentThread());
                    return editor.commit();
                }));
        sender.delaySubmission = true;
        open();
        source.events.put(100L, List.of(event("async-ledger", "root", "200", 1001)));
        hook.requestScan();
        ledgerWorker.paused = true;
        processReplies();
        assertTrue(sender.delayed.isEmpty());
        assertEquals("pending", new org.json.JSONObject(records.getString("incoming.100.async-ledger", "")).getString("status"));

        commits.clear();
        ledgerWorker.paused = false;
        drainLedgerOffMain();
        assertEquals(1, sender.delayed.size());
        assertFalse(commits.isEmpty());
        assertTrue(commits.stream().noneMatch(thread -> thread == Thread.currentThread()));
        assertEquals("submitted", new org.json.JSONObject(records.getString("incoming.100.async-ledger", "")).getString("status"));

        commits.clear();
        sender.delayed.get(0).run();
        assertEquals(List.of("async-ledger"), sender.sent);
        assertTrue("The main-thread boundary and native callback must not write either preferences file", commits.isEmpty());
        assertEquals("submitted", new org.json.JSONObject(records.getString("incoming.100.async-ledger", "")).getString("status"));
        drainLedgerOffMain();
        assertFalse(commits.isEmpty());
        assertTrue(commits.stream().noneMatch(thread -> thread == Thread.currentThread()));
        pump();
        assertEquals("sent", history().get(0).status);
    }

    @Test public void accountChangeDuringDurableReservationCannotInvokeTheNativeSender() throws Exception {
        android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
        replaceField("ledger", new CommentReplyLedger(records, java.time.Clock.systemUTC(), editor -> {
            boolean committed = editor.commit();
            if (records.getString("incoming.100.account-race", "").contains("\"submitted\"")) account.set(101);
            return committed;
        }));
        open();
        source.events.put(100L, List.of(event("account-race", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        assertTrue(sender.delayed.isEmpty());
        assertTrue(ledger().retryable(100, "account-race"));
        assertTrue(activity.getSharedPreferences("douai_automation_limits", Context.MODE_PRIVATE).getAll().isEmpty());
    }

    @Test public void failedSubmissionPersistenceNeverCallsNativeAndReportsAnUnhealthyLedger() throws Exception {
        android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
        boolean[] failWrites = {false};
        replaceField("ledger", new CommentReplyLedger(records, java.time.Clock.systemUTC(), editor -> !failWrites[0] && editor.commit()));
        open();
        source.events.put(100L, List.of(event("disk-failure", "root", "200", 1001)));
        hook.requestScan();
        ledgerWorker.paused = true;
        processReplies();
        failWrites[0] = true;
        ledgerWorker.paused = false;
        pump();
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().health().persistenceFailed);
        hook.requestScan();
        pump();
        assertTrue(hook.diagnostic(), hook.diagnostic().contains("写入失败"));
    }

    @Test public void rejectedLedgerWorkLeavesTheClaimProtectedAndDoesNotInvokeNative() {
        open();
        source.events.put(100L, List.of(event("io-full", "root", "200", 1001)));
        hook.requestScan();
        ledgerWorker.stopped = true;
        processReplies();
        assertTrue(sender.sent.isEmpty());
        assertFalse(ledger().retryable(100, "io-full"));
        assertTrue(hook.diagnostic(), hook.diagnostic().contains("账本处理繁忙"));
    }

    @Test public void aLateFailureAfterAnUnconfirmedSendKeepsItsClaimAndAllowance() {
        sender.outcome = "submitted";
        open();
        source.events.put(100L, List.of(event("unknown-late-failure", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        int reserved = activity.getSharedPreferences("douai_automation_limits", Context.MODE_PRIVATE).getAll().size();
        assertEquals(1, reserved);
        sender.callbacks.get(0).accept("failed");
        pump();
        assertFalse(ledger().retryable(100, "unknown-late-failure"));
        assertEquals(reserved, activity.getSharedPreferences("douai_automation_limits", Context.MODE_PRIVATE).getAll().size());
        assertEquals("submitted", history().get(0).status);
        advanceMillis(65_000);
        hook.requestScan();
        pump();
        assertEquals(List.of("unknown-late-failure"), sender.sent);
    }

    @Test public void manuallyCancellingThePreviewIsTerminalAcrossRescansAndRecreation() {
        configure("reply_tip", true);
        open();
        source.events.put(100L, List.of(event("manual-cancel", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        AutomationHistoryStore.Entry pending = history().get(0);
        assertEquals("pending", pending.status);
        View notice = activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring");
        assertNotNull(notice);
        assertTrue(notice.performLongClick());
        pump();
        assertEquals("cancelled", history().get(0).status);
        assertFalse(ledger().retryable(100, "manual-cancel"));
        advanceMillis(65_000);
        assertTrue(sender.sent.isEmpty());
        hook.close();
        createHook();
        open();
        assertTrue(sender.sent.isEmpty());
        assertEquals(List.of("manual-cancel"), generator.ids);
    }

    @Test public void generatingCommentReplyShowsTheSharedReplyRingAndCanBeCancelledBeforeNativeSubmission() {
        configure("reply_tip", true);
        open();
        source.events.put(100L, List.of(event("generation-ring", "root", "200", 1001)));
        generator.duringGenerate = () -> {
            assertEquals(List.of("generation-ring"), generator.ids);
            assertTrue("The task must have a durable claim before its generation UI is inspected",
                    ledger().contains(100, "generation-ring"));
            assertFalse(ledger().retryable(100, "generation-ring"));
            View ring = activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring");
            assertNotNull("An actively generating comment reply with reply_tip enabled must expose its shared reply ring", ring);
            assertTrue("The generating reply must remain cancellable before native submission", ring.performLongClick());
        };
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().contains(100, "generation-ring"));
        assertFalse("An explicit user cancellation must not automatically replay the same claim",
                ledger().retryable(100, "generation-ring"));
        assertTrue(history().stream().noneMatch(entry -> "sent".equals(entry.status) || "submitted".equals(entry.status)));
    }

    @Test public void commentReplyPreparationKeepsOneRingAcrossWaitingMediaModelSpeechAndTheOriginalPreview() throws Exception {
        configure("reply_tip", true);
        configure("reply_notice_seconds", "3");
        configure(LocalOptions.REPLY_VOICE, true);
        List<String> phases = new ArrayList<>();
        CommentRingView[] preparing = new CommentRingView[1];
        useMediaLoader((context, event, options, cancellation) -> {
            assertSame(preparing[0], commentReplyRing());
            phases.add(commentReplyRing().getPhase());
            return Collections.emptyList();
        });
        generator.duringGenerate = () -> {
            assertSame(preparing[0], commentReplyRing());
            phases.add(commentReplyRing().getPhase());
        };
        java.io.File audioFile = java.io.File.createTempFile("comment-reply-preparation", ".m4a", activity.getCacheDir());
        java.nio.file.Files.write(audioFile.toPath(), new byte[]{1, 2, 3});
        SpeechPreparation.Audio audio = new SpeechPreparation.Audio(audioFile, 2000);
        HostTargets.field(CommentReplyHook.class, "speechPreparer").set(hook, (SpeechPreparation.Preparer)
                (context, text, options, maximum, valid) -> {
                    assertTrue(valid.getAsBoolean());
                    assertSame(preparing[0], commentReplyRing());
                    phases.add(commentReplyRing().getPhase());
                    return audio;
                });
        open();
        source.events.put(100L, List.of(event("phase-ring", "root", "200", 1001)));
        hook.requestScan();
        pump();
        preparing[0] = commentReplyRing();
        assertNotNull(preparing[0]);
        phases.add(preparing[0].getPhase());
        assertTrue(generator.ids.isEmpty());
        advanceMillis(2000);
        assertEquals(List.of("waiting", "media", "generating", "speech"), phases);
        assertSame(preparing[0], commentReplyRing());
        assertEquals("preview", commentReplyRing().getPhase());
        assertTrue(sender.sent.isEmpty());
        advanceMillis(2999);
        assertTrue("Preparation time must not shorten the existing ready preview", sender.sent.isEmpty());
        advanceMillis(1);
        assertEquals(List.of("phase-ring"), sender.sent);
        assertSame(audio, sender.audios.get(0));
        assertEquals("sent", history().get(0).status);
        assertFalse(audioFile.exists());
    }

    @Test public void cancellingTheQueuedPreparationDoesNotCancelAnotherCommentRepliesActiveGenerator() {
        configure("reply_tip", true);
        configure("reply_notice_seconds", "2");
        open();
        source.events.put(100L, List.of(event("active-ring", "root-active", "200", 1001),
                event("queued-ring", "root-queued", "201", 1002)));
        generator.duringGenerate = () -> {
            assertEquals(List.of("active-ring"), generator.ids);
            assertEquals("generating", commentReplyRing().getPhase());
            assertEquals(2, commentReplyRing().getQueueCount());
            int cancellations = generator.cancels;
            assertTrue(commentReplyRing().performLongClick());
            assertEquals("Cancelling the queued task must not disconnect the current task's model request",
                    cancellations, generator.cancels);
        };
        hook.requestScan();
        processReplies();
        generator.duringGenerate = null;
        assertEquals(List.of("active-ring"), generator.ids);
        assertTrue(ledger().contains(100, "queued-ring"));
        assertFalse(ledger().retryable(100, "queued-ring"));
        advanceMillis(2000);
        assertEquals(List.of("active-ring"), sender.sent);
        assertEquals(1, history().stream().filter(entry -> "sent".equals(entry.status)).count());
    }

    @Test public void disabledReplyTipDoesNotGateCommentGenerationOrSending() {
        configure("reply_tip", false);
        open();
        source.events.put(100L, List.of(event("no-tip", "root", "200", 1001)));
        generator.duringGenerate = () -> assertNull(commentReplyRing());
        hook.requestScan();
        processReplies();
        assertEquals(List.of("no-tip"), generator.ids);
        assertEquals(List.of("no-tip"), sender.sent);
        assertEquals("sent", history().get(0).status);
        assertNull(commentReplyRing());
    }

    @Test public void failedOrTimedOutGenerationLeavesAFailureNoticeInsteadOfAStalePreparation() throws Exception {
        configure("reply_tip", true);
        open();
        source.events.put(100L, List.of(event("failed-preparation", "root-failure", "200", 1001)));
        generator.failureAfterRequest = new java.io.IOException("fixture model failure");
        hook.requestScan();
        processReplies();
        assertEquals("failed", commentReplyRing().getPhase());
        assertTrue(sender.sent.isEmpty());
        assertNull(HostTargets.read(hook, "activeGeneratingTask"));

        generator.failureAfterRequest = null;
        source.events.put(100L, List.of(event("timeout-preparation", "root-timeout", "201", 1002)));
        generator.duringGenerate = () -> {
            generator.duringGenerate = null;
            source.events.clear();
            advanceMillis(120_000);
            assertEquals("failed", commentReplyRing().getPhase());
        };
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        assertNull(HostTargets.read(hook, "activeGeneratingTask"));
        assertEquals("failed", commentReplyRing().getPhase());
    }

    @Test
    @org.robolectric.annotation.LooperMode(org.robolectric.annotation.LooperMode.Mode.PAUSED)
    public void lateMainThreadPreparationUpdatesKeepTheWorkersFailureAsTheFinalHistoryOutcome() throws Exception {
        configure("reply_tip", true);
        configure("reply_notice_seconds", "2");
        open();
        int index = 0;
        for (Exception failure : List.of(new java.io.IOException("fixture model failure"),
                new java.net.SocketTimeoutException("fixture model transport timeout"))) {
            String incoming = "late-phase-failure-" + ++index;
            source.events.put(100L, List.of(event(incoming, "root-" + index, "200", 1000 + index)));
            generator.failureAfterRequest = failure;
            hook.requestScan();
            pump();
            @SuppressWarnings("unchecked") Map<String, Object> owned = (Map<String, Object>) HostTargets.read(hook, "tasks");
            Object task = owned.get("100:" + incoming);
            assertNotNull(task);
            String historyId = (String) HostTargets.read(task, "id");
            assertEquals("waiting", commentReplyRing().getPhase());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertEquals(1, generatorWorker.tasks.size());

            java.util.concurrent.atomic.AtomicReference<Throwable> workerFailure = new java.util.concurrent.atomic.AtomicReference<>();
            Thread worker = new Thread(() -> {
                try { generatorWorker.runAll(); } catch (Throwable error) { workerFailure.set(error); }
            }, "comment-reply-late-phase-test");
            worker.start(); worker.join(2_000);
            assertFalse("The real generation worker must finish while main remains paused", worker.isAlive());
            assertNull(workerFailure.get());
            assertTrue(((java.util.concurrent.atomic.AtomicBoolean) HostTargets.read(task, "finished")).get());
            assertEquals("Queued media/model updates must not have run on main yet", "waiting", commentReplyRing().getPhase());
            assertTrue(history().stream().noneMatch(entry -> historyId.equals(entry.id)));
            source.events.clear();

            ShadowLooper.idleMainLooper();
            dispatchWorker.runAll(); ledgerWorker.runAll();
            AutomationHistoryStore.Entry settled = history().stream().filter(entry -> historyId.equals(entry.id)).findFirst().orElseThrow();
            assertEquals("A late invalid preparation must not replace the completed model failure", "failed", settled.status);
            assertTrue(settled.modelRequestSent);
            assertTrue(sender.sent.isEmpty());
            assertEquals("failed", commentReplyRing().getPhase());
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
            pump();
            assertNull("Only the bounded failure notice may remain; preparation cannot survive its expiry", commentReplyRing());
            assertEquals("failed", history().stream().filter(entry -> historyId.equals(entry.id)).findFirst().orElseThrow().status);
            assertTrue(sender.sent.isEmpty());
        }
    }

    @Test public void queuedPreparationIsRemovedOnAccountChangeAndHookClose() {
        configure("reply_tip", true);
        for (String reason : List.of("account", "close")) {
            account.set(100);
            source.events.clear();
            open();
            String incoming = "preparation-" + reason;
            source.events.put(100L, List.of(event(incoming, "root-" + reason, "200", 1001)));
            hook.requestScan();
            pump();
            assertNotNull(commentReplyRing());
            assertEquals("waiting", commentReplyRing().getPhase());
            if (reason.equals("account")) { account.set(101); hook.requestScan(); pump(); }
            else { hook.close(); ledgerWorker.runAll(); ShadowLooper.idleMainLooper(); }
            assertNull(commentReplyRing());
            assertTrue(generator.ids.isEmpty());
            assertTrue(sender.sent.isEmpty());
            assertTrue(ledger().retryable(100, incoming));
            if (reason.equals("account")) { hook.close(); createHook(); }
        }
    }

    private CommentRingView commentReplyRing() {
        return (CommentRingView) activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring");
    }

    @Test public void anOldUnsubmittedSenderCancellationCannotOverwriteTheNextClaimsPendingRecord() {
        sender.delaySubmission = true;
        open();
        source.events.put(100L, List.of(event("same-incoming", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(1, sender.delayed.size());
        assertTrue(sender.sent.isEmpty());
        hook.onActivityPaused(activity);
        advanceMillis(600);
        assertTrue(ledger().retryable(100, "same-incoming"));
        hook.onActivityResumed(activity);
        processReplies();
        assertEquals(2, sender.delayed.size());
        assertFalse(ledger().retryable(100, "same-incoming"));

        sender.delayed.get(0).run();
        pump();
        assertTrue(sender.sent.isEmpty());
        assertFalse("The old cancellation must leave the new pending claim intact", ledger().retryable(100, "same-incoming"));
        sender.delayed.get(1).run();
        pump();
        assertEquals(List.of("same-incoming"), sender.sent);
        assertEquals(1, history().stream().filter(entry -> "sent".equals(entry.status)).count());
        hook.requestScan();
        pump();
        assertEquals(2, sender.delayed.size());
    }

    @Test public void aTimedOutGeneratorsLateReturnCannotCancelANewClaimForTheSameIncomingComment() {
        open();
        source.events.put(100L, List.of(event("retry-after-timeout", "root", "200", 1001)));
        generator.duringGenerate = () -> {
            generator.duringGenerate = null;
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(120));
            ledgerWorker.runAll();
            assertTrue(ledger().retryable(100, "retry-after-timeout"));
            android.content.SharedPreferences records = activity.getSharedPreferences("douai_comment_reply_ledger", Context.MODE_PRIVATE);
            String key = "incoming.100.retry-after-timeout";
            try {
                org.json.JSONObject record = new org.json.JSONObject(records.getString(key, ""));
                assertEquals("failed", record.getString("status"));
                // Only advance the failed attempt's clock; the second generation remains queued.
                record.put("at", System.currentTimeMillis() - CommentReplyLedger.RETRY_DELAY_MILLIS - 1);
                assertTrue(records.edit().putString(key, record.toString()).commit());
                hook.requestScan();
                scanner.runAll();
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
                assertEquals("pending", new org.json.JSONObject(records.getString(key, "")).getString("status"));
                assertEquals(1, generatorWorker.tasks.size());
                assertTrue(sender.sent.isEmpty());
            } catch (org.json.JSONException failure) { throw new AssertionError(failure); }
        };
        hook.requestScan();
        processReplies();
        assertEquals(List.of("retry-after-timeout", "retry-after-timeout"), generator.ids);
        assertEquals(List.of("retry-after-timeout"), sender.sent);
        assertEquals(1, history().stream().filter(entry -> "sent".equals(entry.status)).count());
        assertFalse(ledger().retryable(100, "retry-after-timeout"));
        hook.requestScan();
        pump();
        assertEquals(List.of("retry-after-timeout"), sender.sent);
    }

    @Test public void aScanReturningAfterBackgroundAndResumeAutomaticallyQueuesAFreshScan() {
        open();
        source.events.put(100L, List.of(event("stale-scan", "root", "200", 1001)));
        source.duringScan = () -> {
            hook.onActivityPaused(activity);
            advanceMillis(600);
            hook.onActivityResumed(activity);
            ShadowLooper.idleMainLooper();
        };
        hook.requestScan();
        pump();
        assertEquals(1, source.scans.size());
        assertTrue(sender.sent.isEmpty());
        assertFalse(ledger().contains(100, "stale-scan"));
        source.events.put(100L, List.of(event("fresh-scan", "root", "200", 1002)));
        advanceMillis(999);
        assertEquals(1, source.scans.size());
        advanceMillis(1);
        assertEquals(2, source.scans.size());
        advanceMillis(2000);
        assertEquals(List.of("fresh-scan"), sender.sent);
    }

    @Test public void aScanReturningAfterAccountSwitchAutomaticallyBaselinesTheNewAccount() {
        open();
        source.events.put(100L, List.of(event("old-account-scan", "root-a", "200", 1001)));
        source.events.put(101L, List.of(event("new-account-scan", "root-b", "201", 1002)));
        source.duringScan = () -> {
            account.set(101);
            hook.requestScan();
        };
        hook.requestScan();
        pump();
        assertEquals(List.of(100L), source.baselines);
        assertTrue(sender.sent.isEmpty());
        advanceMillis(999);
        assertFalse(ledger().initialized(101));
        advanceMillis(1);
        assertEquals(List.of(100L, 101L), source.baselines);
        assertTrue(ledger().initialized(101));
        hook.signal();
        advanceMillis(1000);
        advanceMillis(2000);
        assertEquals(List.of("new-account-scan"), sender.sent);
        assertFalse(ledger().contains(100, "old-account-scan"));
    }

    @Test public void generationHonorsTheParentDelayAndItsTwoSecondMinimum() {
        configure("reply_delay_seconds", "0");
        open();
        source.events.put(100L, List.of(event("minimum-delay", "root-a", "200", 1001)));
        hook.requestScan();
        pump();
        assertTrue(generator.ids.isEmpty());
        advanceMillis(1999);
        assertTrue(generator.ids.isEmpty());
        advanceMillis(1);
        assertEquals(List.of("minimum-delay"), sender.sent);

        configure("reply_delay_seconds", "5");
        source.events.put(100L, List.of(event("configured-delay", "root-b", "201", 1002)));
        hook.requestScan();
        pump();
        advanceMillis(4999);
        assertEquals(List.of("minimum-delay"), generator.ids);
        advanceMillis(1);
        assertEquals(List.of("minimum-delay", "configured-delay"), sender.sent);
    }

    @Test public void parentAndChildReplySwitchesMustBothBeEnabled() {
        configure("native_auto_reply", false);
        open();
        hook.signal();
        advanceMillis(3000);
        assertTrue(source.baselines.isEmpty());
        assertTrue(generator.ids.isEmpty());

        configure("native_auto_reply", true);
        configure(LocalOptions.COMMENT_REPLY_ENABLED, false);
        hook.requestScan();
        advanceMillis(3000);
        assertTrue(source.baselines.isEmpty());
        assertTrue(sender.sent.isEmpty());

        configure(LocalOptions.COMMENT_REPLY_ENABLED, true);
        hook.requestScan();
        pump();
        assertEquals(List.of(100L), source.baselines);
        source.events.put(100L, List.of(event("enabled", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("enabled"), sender.sent);
    }

    @Test public void interactionAutomationMasterDoesNotPauseAnEnabledCommentReply() {
        open();
        source.events.put(100L, List.of(event("parent-controls", "root", "200", 1001)));
        hook.requestScan();
        pump();
        int cancellations = generator.cancels;
        configure("automation_enabled", false);
        advanceMillis(2000);
        assertEquals(cancellations, generator.cancels);
        assertEquals(List.of("parent-controls"), sender.sent);
    }

    @Test public void disablingEitherReplySwitchBeforeGenerationDefersTheClaimUntilReenabled() {
        open();
        int completed = 0;
        for (String key : new String[]{"native_auto_reply", LocalOptions.COMMENT_REPLY_ENABLED}) {
            String incoming = "paused-" + key;
            source.events.put(100L, List.of(event(incoming, "root-" + key, "200", 1001 + completed)));
            hook.requestScan();
            pump();
            assertTrue(ledger().contains(100, incoming));
            assertFalse(ledger().retryable(100, incoming));
            configure(key, false);
            advanceMillis(2000);
            assertEquals(completed, sender.sent.size());
            assertTrue(ledger().retryable(100, incoming));

            configure(key, true);
            hook.requestScan();
            processReplies();
            assertEquals(++completed, sender.sent.size());
            assertEquals(incoming, sender.sent.get(completed - 1));
        }
    }

    @Test public void dispatcherFirstDisableDefersTheEarlyClaimEvenWhenItsLedgerCommitsBeforeTheHookUpdate() throws Exception {
        assertEarlyPreparationDisableOrder(true);
    }

    @Test public void hookFirstDisableDefersTheEarlyClaimEvenWhenItsLedgerCommitsBeforeTheDispatcherUpdate() throws Exception {
        assertEarlyPreparationDisableOrder(false);
    }

    private void assertEarlyPreparationDisableOrder(boolean dispatcherFirst) throws Exception {
        configure("reply_tip", true);
        open();
        String incoming = dispatcherFirst ? "dispatcher-first-disable" : "hook-first-disable";
        source.events.put(100L, List.of(event(incoming, "root", "200", 1001)));
        hook.requestScan();
        pump();
        assertNotNull(commentReplyRing());
        assertFalse(ledger().retryable(100, incoming));
        java.util.concurrent.ExecutorService independentLedger = java.util.concurrent.Executors.newSingleThreadExecutor(action -> {
            Thread thread = new Thread(action, "comment-reply-early-ledger-test"); thread.setDaemon(true); return thread;
        });
        replaceField("ledgerWorker", independentLedger);
        Map<String, Object> disabled = new HashMap<>(values);
        disabled.put("native_auto_reply", false);
        LocalOptions next = new LocalOptions(disabled);
        try {
            if (dispatcherFirst) dispatcher.setOptions(next); else hook.setOptions(next);
            ShadowLooper.idleMainLooper();
            // Settle the real writer before updating the other owner, so a later setter cannot repair the verdict.
            independentLedger.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertTrue("Configuration cancellation must remain retryable regardless of setter order or writer timing",
                    ledger().retryable(100, incoming));
            if (dispatcherFirst) hook.setOptions(next); else dispatcher.setOptions(next);
            ShadowLooper.idleMainLooper();
            independentLedger.submit(() -> { }).get(2, TimeUnit.SECONDS);
            assertTrue(ledger().retryable(100, incoming));
            assertTrue(generator.ids.isEmpty());
            assertTrue(sender.sent.isEmpty());
            assertNull(commentReplyRing());
        } finally {
            hook.close();
            independentLedger.shutdown();
            assertTrue(independentLedger.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void changingParentReplyModeCancelsOldGenerationAndUsesTheNewSnapshotOnRescan() {
        open();
        source.events.put(100L, List.of(event("mode-change", "root", "200", 1001)));
        generator.duringGenerate = () -> configure("reply_mode", "fixed");
        hook.requestScan();
        processReplies();
        assertEquals(List.of("ai"), generator.modes);
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().retryable(100, "mode-change"));

        generator.duringGenerate = null;
        hook.requestScan();
        processReplies();
        assertEquals(List.of("ai", "fixed"), generator.modes);
        assertEquals(List.of("mode-change"), sender.sent);
    }

    @Test public void changingGlobalModelDoesNotCancelAnIndependentlyBoundCommentReply() throws Exception {
        try (okhttp3.mockwebserver.MockWebServer global = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer replies = new okhttp3.mockwebserver.MockWebServer();
             okhttp3.mockwebserver.MockWebServer comments = new okhttp3.mockwebserver.MockWebServer()) {
            com.douai.module.config.AiModelSettings profiles = AutomationModelFixtures.profiles(global, replies, comments);
            values.put(com.douai.module.config.AiModelSettings.KEY, profiles.serialize());
            configure(com.douai.module.config.AiModelSettings.REPLY_MODEL_ID, "reply");
            open();
            source.events.put(100L, List.of(event("independent-reply", "root", "200", 1001)));
            int cancellations = generator.cancels;
            generator.duringGenerate = () -> configure(com.douai.module.config.AiModelSettings.KEY,
                    profiles.activate("comment").serialize());
            hook.requestScan();
            processReplies();
            assertEquals("An unrelated global choice must not disconnect the active reply generator", cancellations, generator.cancels);
            assertEquals(List.of("independent-reply"), generator.ids);
            assertEquals(List.of("independent-reply"), sender.sent);
            assertEquals("sent", history().get(0).status);
        }
    }

    @Test public void legacyIndependentReplyOptionsCannotOverrideParentConfiguration() {
        values.put("comment_reply_daily_limit", "1");
        values.put("comment_reply_user_cooldown_minutes", "100");
        values.put("comment_reply_thread_limit", "1");
        values.put("comment_reply_delay_seconds", "3600");
        values.put("comment_reply_tip_enabled", true);
        values.put("comment_reply_failure_notify", true);
        configure("comment_reply_prompt", "旧独立提示词");
        open();
        source.events.put(100L, List.of(event("one", "root", "200", 1001), event("two", "root", "200", 1002)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("one", "two"), sender.sent);
        assertEquals(2, history().size());
        assertTrue(history().stream().allMatch(entry -> "sent".equals(entry.status)));
    }

    @Test public void anExistingMessageReservationBlocksCommentRepliesFromTheSharedDailyQuota() {
        configure("reply_daily_limit", "1");
        open();
        AutomationRateLimiter limiter = (AutomationRateLimiter) HostTargets.read(hook, "rateLimiter");
        AutomationRateLimiter.Reservation message = limiter.tryReserve("reply", 100, "message-user", new LocalOptions(values));
        assertNotNull(message);
        source.events.put(100L, List.of(event("shared-quota", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());
        assertFalse(ledger().contains(100, "shared-quota"));
        assertEquals(1000, ledger().watermark(100));

        limiter.release(message);
        hook.requestScan();
        processReplies();
        assertEquals(List.of("shared-quota"), sender.sent);
    }

    @Test public void commentReplyRecordsFollowTheParentHistorySwitch() {
        configure("reply_save_history", false);
        open();
        source.events.put(100L, List.of(event("unrecorded", "root-a", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("unrecorded"), sender.sent);
        assertTrue(history().isEmpty());
        assertTrue(new AutomationHistoryStore(activity).read(AutomationHistoryStore.REPLY).isEmpty());

        configure("reply_save_history", true);
        source.events.put(100L, List.of(event("recorded", "root-b", "201", 1002)));
        hook.requestScan();
        processReplies();
        List<AutomationHistoryStore.Entry> parentHistory = new AutomationHistoryStore(activity).read(AutomationHistoryStore.REPLY);
        assertEquals(1, parentHistory.size());
        assertEquals(AutomationHistoryStore.COMMENT_REPLY, parentHistory.get(0).kind);
        assertTrue(parentHistory.get(0).input.contains("回复 recorded"));
        assertEquals("sent", parentHistory.get(0).status);
    }

    @Test public void backgroundingFromReplyDetailsDefersEvenWhenDispatcherPausesBeforeTheHook() {
        configure("reply_tip", true);
        open();
        source.events.put(100L, List.of(event("background-details", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        View ring = activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring");
        assertNotNull(ring);
        assertTrue(ring.performClick());
        // Production registers Dispatcher first, so its pause callback closes the dialog first.
        dispatcher.onActivityPaused(activity);
        ledgerWorker.runAll();
        assertTrue(ledger().retryable(100, "background-details"));
        hook.onActivityPaused(activity);
        advanceMillis(600);
        assertTrue(sender.sent.isEmpty());
        dispatcher.onActivityResumed(activity);
        hook.onActivityResumed(activity);
        processReplies();
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring"));
        advanceMillis(8000);
        processReplies();
        assertEquals(List.of("background-details"), sender.sent);
    }

    @Test public void resumingBeforeCancellationPersistsDoesNotTurnABackgroundDeferralIntoATerminalCancellation() {
        configure("reply_tip", true);
        open();
        source.events.put(100L, List.of(event("quick-resume", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        View ring = activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring");
        assertNotNull(ring);
        assertTrue(ring.performClick());
        dispatcher.onActivityPaused(activity);
        dispatcher.onActivityResumed(activity);
        ledgerWorker.runAll();
        assertTrue(ledger().retryable(100, "quick-resume"));
        assertTrue(sender.sent.isEmpty());
    }

    @Test public void commentRepliesUseTheParentPreviewAndCountdown() {
        configure("reply_tip", true);
        configure("reply_notice_seconds", "4");
        open();
        source.events.put(100L, List.of(event("preview", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        AutomationHistoryStore.Entry pending = history().get(0);
        assertEquals("pending", pending.status);
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.reply.ring"));
        advanceMillis(3000);
        assertTrue(sender.sent.isEmpty());
        advanceMillis(1000);
        assertEquals(List.of("preview"), sender.sent);
        assertEquals("sent", history().get(0).status);
    }

    @Test public void parentVoiceReplyPassesCompleteLongAudioWithoutAssumingARecorderUploadLimit() throws Exception {
        configure(LocalOptions.REPLY_VOICE, true);
        java.io.File audioFile = java.io.File.createTempFile("comment-reply", ".m4a", activity.getCacheDir());
        java.nio.file.Files.write(audioFile.toPath(), new byte[]{1, 2, 3});
        SpeechPreparation.Audio audio = new SpeechPreparation.Audio(audioFile, 33_516);
        HostTargets.field(CommentReplyHook.class, "speechPreparer").set(hook, (SpeechPreparation.Preparer)
                (context, text, options, maximum, valid) -> {
                    assertTrue(valid.getAsBoolean());
                    assertTrue(options.enabled(LocalOptions.REPLY_VOICE));
                    assertEquals("AI 正文 voice", text);
                    assertEquals(Long.MAX_VALUE, maximum);
                    return audio;
                });
        open();
        source.events.put(100L, List.of(event("voice", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("voice"), sender.sent);
        assertSame(audio, sender.audios.get(0));
        assertEquals(AutomationHistoryStore.DELIVERY_VOICE, history().get(0).deliveryMode);
        assertEquals("sent", history().get(0).status);
        assertFalse(audioFile.exists());
    }

    @Test public void failedParentVoicePreparationDoesNotSendATextFallback() throws Exception {
        configure(LocalOptions.REPLY_VOICE, true);
        HostTargets.field(CommentReplyHook.class, "speechPreparer").set(hook, (SpeechPreparation.Preparer)
                (context, text, options, maximum, valid) -> { throw new java.io.IOException("fixture synthesis failure"); });
        open();
        source.events.put(100L, List.of(event("voice-failure", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("voice-failure"), generator.ids);
        assertTrue(sender.sent.isEmpty());
        assertTrue(ledger().retryable(100, "voice-failure"));
        assertEquals("failed", history().get(0).status);
    }

    @Test public void voiceSubmissionWithoutAnAcceptedCallbackStillCleansUpAfterTenMinutesWithoutResending() throws Exception {
        configure(LocalOptions.REPLY_VOICE, true);
        sender.outcome = "submitted";
        sender.omitAccepted = true;
        java.io.File audioFile = java.io.File.createTempFile("comment-reply-uncertain", ".m4a", activity.getCacheDir());
        java.nio.file.Files.write(audioFile.toPath(), new byte[]{1, 2, 3});
        SpeechPreparation.Audio audio = new SpeechPreparation.Audio(audioFile, 1200);
        HostTargets.field(CommentReplyHook.class, "speechPreparer").set(hook, (SpeechPreparation.Preparer)
                (context, text, options, maximum, valid) -> audio);
        open();
        source.events.put(100L, List.of(event("voice-uncertain", "root", "200", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("voice-uncertain"), sender.sent);
        assertEquals("submitted", history().get(0).status);
        assertTrue(audioFile.exists());
        assertFalse(ledger().retryable(100, "voice-uncertain"));

        advanceMillis(9 * 60_000);
        assertTrue("An unconfirmed native upload retains its audio for nine minutes", audioFile.exists());
        assertEquals(List.of("voice-uncertain"), sender.sent);
        advanceMillis(60_000);
        assertFalse("The submitted-before-send boundary must schedule cleanup even without accepted", audioFile.exists());
        assertEquals(List.of("voice-uncertain"), generator.ids);
        assertEquals(List.of("voice-uncertain"), sender.sent);
        assertFalse(ledger().retryable(100, "voice-uncertain"));
    }

    @Test public void aiReceivesSelectedCommentImagesAndRemembersTheSameImagesAndPromptOnlyAfterSent() throws Exception {
        ChatImage image = testImage();
        java.util.concurrent.atomic.AtomicReference<MediaReadCancellation> media = new java.util.concurrent.atomic.AtomicReference<>();
        useMediaLoader((context, event, options, cancellation) -> {
            media.set(cancellation);
            return List.of(image);
        });
        sender.outcome = "submitted";
        open();
        source.events.put(100L, List.of(imageEvent("image-context", "对方补充文字", 1001)));
        hook.requestScan();
        processReplies();
        assertSame(image, generator.generatedImages.get(0).get(0));
        assertTrue(generator.prompts.get(0).contains("实际附带的视觉素材：对方评论附图"));
        assertTrue(generator.prompts.get(0).contains("对方补充文字"));
        assertTrue(generator.acknowledgedImages.isEmpty());
        assertTrue(history().get(0).modelRequestSent);
        assertEquals(1, history().get(0).requestImageCount);
        assertThrows(java.util.concurrent.CancellationException.class, () -> media.get().check());

        sender.callbacks.get(0).accept("sent");
        pump();
        assertEquals(1, generator.acknowledgedImages.size());
        assertSame(generator.generatedImages.get(0), generator.acknowledgedImages.get(0));
        assertEquals(generator.prompts.get(0), generator.acknowledgedPrompts.get(0));
        assertSame(image, generator.acknowledgedImages.get(0).get(0));
        sender.callbacks.get(0).accept("sent");
        pump();
        assertEquals(1, generator.acknowledgedImages.size());
        assertEquals("sent", history().get(0).status);
    }

    @Test public void disablingBothMaterialOptionsSendsOnlyTextToTheModel() {
        configure(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
        configure(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
        open();
        source.events.put(100L, List.of(imageEvent("text-only", "只处理这段文字", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(1, generator.generatedImages.size());
        assertTrue(generator.generatedImages.get(0).isEmpty());
        assertFalse(generator.prompts.get(0).contains("实际附带的视觉素材"));
        assertEquals(List.of("text-only"), sender.sent);
        assertTrue(history().get(0).modelRequestSent);
        assertEquals(0, history().get(0).requestImageCount);
    }

    @Test public void changingMaterialOptionsInvalidatesTheQueuedNativeBoundaryWithoutIdlingTheMainLoop() throws Exception {
        ChatImage image = testImage();
        java.util.concurrent.atomic.AtomicReference<MediaReadCancellation> media = new java.util.concurrent.atomic.AtomicReference<>();
        useMediaLoader((context, event, options, cancellation) -> {
            media.set(cancellation);
            return List.of(image);
        });
        sender.delaySubmission = true;
        open();
        source.events.put(100L, List.of(imageEvent("disable-before-native", "先前的图片回复", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(1, sender.delayed.size());
        assertTrue(sender.sent.isEmpty());
        long previousEpoch = ((AtomicLong) HostTargets.read(hook, "generation")).get();
        int previousCancels = generator.cancels;
        values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
        values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
        hook.setOptions(new LocalOptions(values));
        // The native sender may already be queued ahead of any main-thread UI cleanup.
        assertTrue(((AtomicLong) HostTargets.read(hook, "generation")).get() > previousEpoch);
        assertTrue(generator.cancels > previousCancels);
        assertThrows(java.util.concurrent.CancellationException.class, () -> media.get().check());
        assertFalse("Even a sender holding an earlier validity result must recheck the durable boundary",
                sender.boundaries.get(0).getAsBoolean());
        sender.delayed.get(0).run();
        assertTrue(sender.sent.isEmpty());
        ledgerWorker.runAll();
        assertTrue(ledger().retryable(100, "disable-before-native"));
        assertTrue(new AutomationRateLimiter(activity).canSend("comment_reply", 100, "200", new LocalOptions(values)));
    }

    @Test public void changingMaterialOptionsRejectsALateImageAcknowledgmentBeforeMainThreadCleanup() throws Exception {
        ChatImage image = testImage();
        useMediaLoader((context, event, options, cancellation) -> options.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                ? List.of(image) : Collections.emptyList());
        sender.outcome = "submitted";
        open();
        source.events.put(100L, List.of(imageEvent("late-image-ack", "先前的图片回复", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("late-image-ack"), sender.sent);
        assertTrue(generator.acknowledgedImages.isEmpty());
        values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
        values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
        hook.setOptions(new LocalOptions(values));
        sender.callbacks.get(0).accept("sent");
        assertTrue("Old pictures cannot re-enter context after the settings call returns", generator.acknowledgedImages.isEmpty());
        pump();
        assertEquals("sent", history().get(0).status);
        sender.outcome = "sent";
        source.events.put(100L, List.of(event("after-disable", "other-root", "201", 1002)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("late-image-ack", "after-disable"), sender.sent);
        assertTrue(generator.generatedImages.get(1).isEmpty());
        assertEquals(1, generator.acknowledgedImages.size());
        assertTrue(generator.acknowledgedImages.get(0).isEmpty());
    }

    @Test public void fixedAndKeywordRepliesNeverInvokeTheMediaLoader() throws Exception {
        useMediaLoader((context, event, options, cancellation) -> { throw new AssertionError("Only AI replies may load visual material"); });
        open();
        for (String mode : new String[]{"fixed", "keyword"}) {
            configure("reply_mode", mode);
            source.events.put(100L, List.of(imageEvent("mode-" + mode, "有文字的评论", 1001)));
            hook.requestScan();
            processReplies();
        }
        assertEquals(List.of("fixed", "keyword"), generator.modes);
        assertEquals(2, sender.sent.size());
        assertTrue(generator.generatedImages.stream().allMatch(List::isEmpty));
        assertTrue(history().stream().noneMatch(entry -> entry.modelRequestSent));
    }

    @Test public void imageOnlyCommentsRequireAiAndEnabledImagesBeforeClaimingOrGenerating() throws Exception {
        useMediaLoader((context, event, options, cancellation) -> List.of(testImage()));
        open();
        source.events.put(100L, List.of(imageEvent("image-only", "", 1001)));
        hook.requestScan();
        processReplies();
        assertEquals(List.of("image-only"), sender.sent);
        assertEquals(1, generator.generatedImages.get(0).size());
        assertTrue(generator.prompts.get(0).contains("对方评论附图"));

        int index = 0;
        for (String mode : new String[]{"fixed", "keyword", "random", "ai"}) {
            configure("reply_mode", mode);
            configure(LocalOptions.REPLY_RECOGNIZE_IMAGES, !"ai".equals(mode));
            String id = "ignored-image-" + ++index;
            source.events.put(100L, List.of(imageEvent(id, "", 1001 + index)));
            hook.requestScan();
            processReplies();
            assertFalse(ledger().contains(100, id));
        }
        assertEquals(List.of("image-only"), generator.ids);
        assertEquals(List.of("image-only"), sender.sent);
    }

    @Test public void imageOnlyCommentWithoutARealImageListNeverStartsAnAiTask() {
        open();
        CommentReplyEvent noImage = new CommentReplyEvent("notice-no-image", "work", "no-image", "root", "200", "对方",
                "", "我的原评论", 1001, new Work(), new Object());
        source.events.put(100L, List.of(noImage));
        hook.requestScan();
        processReplies();
        assertTrue(generator.ids.isEmpty());
        assertFalse(ledger().contains(100, "no-image"));
    }

    @Test public void aModelFailureAfterItsRequestWasSentPreservesTheActualImageCount() throws Exception {
        generator.failAfterRequest = true;
        useMediaLoader((context, event, options, cancellation) -> List.of(testImage(), testImage()));
        open();
        source.events.put(100L, List.of(imageEvent("request-failure", "说明", 1001)));
        hook.requestScan();
        processReplies();
        assertTrue(sender.sent.isEmpty());
        AutomationHistoryStore.Entry failed = history().get(0);
        assertEquals("failed", failed.status);
        assertTrue(failed.modelRequestSent);
        assertEquals(2, failed.requestImageCount);
    }

    @Test public void aMediaFailureRecordsItsStageBeforeAnyModelRequestWithoutLeakingPayload() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        hook.close();
        createHook(source, diagnostics::add);
        useMediaLoader((context, event, options, cancellation) -> {
            throw privateGenerationFailure();
        });
        open();
        source.events.put(100L, List.of(imageEvent("media-diagnostic", "说明", 1001)));

        hook.requestScan();
        processReplies();

        assertTrue(generator.ids.isEmpty());
        assertTrue(sender.sent.isEmpty());
        AutomationHistoryStore.Entry failed = history().get(0);
        assertEquals("failed", failed.status);
        assertFalse(failed.modelRequestSent);
        assertEquals("", failed.output);
        assertGenerationFailureDiagnostics(diagnostics, "media", false, false, 0);
    }

    @Test public void aModelFailureRecordsThatARequestWasSentButNoReplyWasGenerated() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        hook.close();
        createHook(source, diagnostics::add);
        generator.failureAfterRequest = privateGenerationFailure();
        useMediaLoader((context, event, options, cancellation) -> List.of(testImage()));
        open();
        source.events.put(100L, List.of(imageEvent("model-diagnostic", "说明", 1001)));

        hook.requestScan();
        processReplies();

        assertEquals(List.of("model-diagnostic"), generator.ids);
        assertTrue(sender.sent.isEmpty());
        AutomationHistoryStore.Entry failed = history().get(0);
        assertEquals("failed", failed.status);
        assertTrue(failed.modelRequestSent);
        assertEquals(1, failed.requestImageCount);
        assertEquals("", failed.output);
        assertGenerationFailureDiagnostics(diagnostics, "model", true, false, 0);
    }

    @Test public void aSpeechFailureRecordsGeneratedTextWithoutClaimingANativeSubmission() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        configure(LocalOptions.REPLY_VOICE, true);
        hook.close();
        createHook(source, diagnostics::add);
        HostTargets.field(CommentReplyHook.class, "speechPreparer").set(hook, (SpeechPreparation.Preparer)
                (context, text, options, maximum, valid) -> { throw privateGenerationFailure(); });
        open();
        source.events.put(100L, List.of(event("speech-diagnostic", "root", "200", 1001)));

        hook.requestScan();
        processReplies();

        assertEquals(List.of("speech-diagnostic"), generator.ids);
        assertTrue(sender.sent.isEmpty());
        AutomationHistoryStore.Entry failed = history().get(0);
        assertEquals("failed", failed.status);
        assertTrue(failed.modelRequestSent);
        assertEquals("AI 正文 speech-diagnostic", failed.output);
        assertEquals("model-fixture", failed.modelName);
        assertGenerationFailureDiagnostics(diagnostics, "speech", true, true, failed.output.length());
    }

    @Test public void incompleteWorkVideoMetadataNeverBlocksTextCommentGenerationOrSending() throws Exception {
        List<String> diagnostics = new ArrayList<>();
        values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, true);
        values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, true);
        hook.close();
        createHook(source, diagnostics::add);
        open();
        IncompleteVideoWork work = new IncompleteVideoWork();
        Object target = new Object();
        source.events.put(100L, List.of(new CommentReplyEvent("notice-sparse-video", "work", "sparse-video", "root",
                "200", "对方", "来评", "我的原评论", 1001, work, target)));

        hook.requestScan();
        processReplies();

        assertEquals(List.of("sparse-video"), generator.ids);
        assertEquals(List.of("sparse-video"), sender.sent);
        assertTrue(generator.generatedImages.get(0).isEmpty());
        assertTrue(generator.prompts.get(0).contains("我的原评论：我的原评论"));
        assertTrue(generator.prompts.get(0).contains("对方的回复：来评"));
        assertFalse(generator.prompts.get(0).contains("作品："));
        assertEquals("Reply generation must never inspect the original work description", 0, work.descriptionReads);
        assertEquals("Reply generation must never inspect the original work's media", 0, work.mediaReads);
        assertSame(work, sender.submittedEvents.get(0).work);
        assertSame(target, sender.submittedEvents.get(0).targetComment);
        assertEquals("sent", history().get(0).status);
        assertTrue(history().get(0).modelRequestSent);
        assertFalse(diagnostics.stream().anyMatch(value -> value.startsWith("Comment reply generation failed:")));
    }

    public static final class IncompleteVideoWork {
        int descriptionReads, mediaReads;
        public Object video = new Object();
        public String getDesc() { descriptionReads++; return "必须忽略的原作品描述"; }
        public Object getVideo() { mediaReads++; return video; }
        public List<Object> getImages() { mediaReads++; return Collections.emptyList(); }
    }

    private static IllegalArgumentException privateGenerationFailure() {
        return new IllegalArgumentException("uid=998877; token=fixture-generation-token; reply=private-generation-text",
                new IllegalStateException("https://private-generation.example/api?key=fixture-generation-key"));
    }

    private static void assertGenerationFailureDiagnostics(List<String> diagnostics, String stage,
                                                           boolean aiSent, boolean generated, int chars) {
        List<String> failures = diagnostics.stream()
                .filter(message -> message.startsWith("Comment reply generation failed:")).toList();
        assertEquals("Each failed generation must have one safe diagnostic", 1, failures.size());
        String failure = failures.get(0);
        assertTrue(failure, failure.contains("IllegalArgumentException"));
        assertTrue(failure, failure.contains("stage=" + stage));
        assertTrue(failure, failure.contains("ai_sent=" + (aiSent ? 1 : 0)));
        assertTrue(failure, failure.contains("generated=" + (generated ? 1 : 0)));
        assertTrue(failure, failure.contains("submitted=0"));
        assertTrue(failure, failure.contains("chars=" + chars));
        for (String secret : List.of("998877", "fixture-generation-token", "private-generation-text",
                "private-generation.example", "fixture-generation-key", "AI 正文")) {
            assertFalse("Diagnostics must not include payload: " + failure, failure.contains(secret));
        }
    }

    @Test public void changingTheImageFlagDisconnectsABlockedMediaRead() throws Exception { assertBlockedMediaCancelled("images"); }
    @Test public void changingTheVideoFlagDisconnectsABlockedMediaRead() throws Exception { assertBlockedMediaCancelled("video"); }
    @Test public void leavingTheForegroundDisconnectsABlockedMediaRead() throws Exception { assertBlockedMediaCancelled("background"); }
    @Test public void closingTheParentReplySwitchDisconnectsABlockedMediaRead() throws Exception { assertBlockedMediaCancelled("parent"); }
    @Test public void theOverallGenerationTimeoutDisconnectsABlockedMediaReadBeforeAi() throws Exception { assertBlockedMediaCancelled("timeout"); }

    private void assertBlockedMediaCancelled(String reason) throws Exception {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        useMediaLoader((context, event, options, cancellation) -> {
            cancellation.start();
            cancellation.attach(connection);
            connection.getResponseCode();
            cancellation.check();
            return Collections.emptyList();
        });
        open();
        source.events.put(100L, List.of(event("blocked-" + reason, "root", "200", 1001)));
        hook.requestScan();
        pump();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
        Runnable generate = generatorWorker.tasks.remove();
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread task = new Thread(generate, "comment-reply-media-test");
        task.setUncaughtExceptionHandler((thread, error) -> failure.set(error));
        try {
            task.start();
            assertTrue("The media transport must be blocked before cancellation", connection.blocked.await(2, TimeUnit.SECONDS));
            if ("images".equals(reason)) configure(LocalOptions.REPLY_RECOGNIZE_IMAGES, false);
            else if ("video".equals(reason)) configure(LocalOptions.REPLY_RECOGNIZE_VIDEO, true);
            else if ("parent".equals(reason)) configure("native_auto_reply", false);
            else if ("background".equals(reason)) { hook.onActivityPaused(activity); advanceMillis(600); }
            else advanceMillis(120_000);
            task.join(2000);
            assertFalse("Cancellation must release the blocked worker", task.isAlive());
            assertNull(failure.get());
            assertTrue(connection.disconnected);
            pump();
            assertTrue(generator.ids.isEmpty());
            assertTrue(sender.sent.isEmpty());
            if ("timeout".equals(reason)) {
                assertEquals("failed", history().get(0).status);
                assertFalse(history().get(0).modelRequestSent);
            } else assertTrue(ledger().retryable(100, "blocked-" + reason));
        } finally {
            connection.disconnect();
            task.join(2000);
        }
    }

    private void useMediaLoader(CommentReplyHook.MediaLoader loader) throws Exception {
        HostTargets.field(CommentReplyHook.class, "mediaLoader").set(hook, loader);
    }

    private static ChatImage testImage() {
        return new ChatImage("image/png", java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a2V0AAAAASUVORK5CYII="));
    }

    private static CommentReplyEvent imageEvent(String id, String text, long createdAt) {
        return new CommentReplyEvent("notice-" + id, "work", id, "root-" + id, "200", "对方", text,
                "我的原评论", createdAt, new Work(), new PictureComment());
    }

    public static final class PictureComment {
        @com.google.gson.annotations.SerializedName("image_list") public List<Picture> imageList = List.of(new Picture());
    }
    public static final class Picture {
        public Address origin_url = new Address();
    }
    public static final class Address {
        public List<String> url_list = List.of("https://p.byteimg.com/comment.png");
    }

    private NativeCommentReplyInbox useNativeInbox(NativeCommentReplyInboxTest.Api nativeApi) throws Exception {
        return useNativeInbox(nativeApi, null);
    }

    private NativeCommentReplyInbox useNativeInbox(NativeCommentReplyInboxTest.Api nativeApi,
                                                  NativeCommentReplyInbox.ThreadLookup threadLookup) throws Exception {
        NativeCommentReplyInboxTest.Provider.b = nativeApi;
        NativeCommentReplyInboxTest.AccountFactory.service = new NativeCommentReplyInboxTest.Account();
        NativeCommentReplyInboxTest.AccountFactory.service.user = new NativeCommentReplyInboxTest.User("100");
        NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(report(), getClass().getClassLoader(), ignored -> { }, threadLookup);
        hook.close();
        createHook(new CommentReplyHook.Source() {
            @Override public long baseline(long account, BooleanSupplier valid) throws Exception {
                return inbox.baseline(account, valid);
            }
            @Override public List<String> baselineIds() { return inbox.baselineCommentIds(); }
            @Override public long scannedThrough() { return inbox.scannedThrough(); }
            @Override public long watermarkCeiling() { return inbox.scannedThrough(); }
            @Override public List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception {
                return inbox.scan(account, since, valid);
            }
            @Override public void close() { inbox.close(); }
        });
        return inbox;
    }

    private void createHook() {
        createHook(source);
    }

    private void createHook(CommentReplyHook.Source replySource) {
        createHook(replySource, ignored -> { });
    }

    private void createHook(CommentReplyHook.Source replySource, Consumer<String> diagnostics) {
        if (ledgerWorker != null) ledgerWorker.runAll();
        scanner = new ManualExecutor();
        generatorWorker = new ManualExecutor();
        ledgerWorker = new ManualExecutor();
        hook = new CommentReplyHook(activity, new LocalOptions(values), dispatcher, replySource, sender, generator,
                account::get, scanner, generatorWorker, ledgerWorker, diagnostics);
    }

    private void configure(String key, Object value) {
        values.put(key, value);
        LocalOptions options = new LocalOptions(values);
        dispatcher.setOptions(options);
        hook.setOptions(options);
        ShadowLooper.idleMainLooper();
    }

    private void open() {
        dispatcher.onActivityResumed(activity);
        hook.onActivityResumed(activity);
        pump();
    }

    private void pump() {
        for (int i = 0; i < 6; i++) {
            ShadowLooper.idleMainLooper();
            scanner.runAll();
            generatorWorker.runAll();
            ShadowLooper.idleMainLooper();
            dispatchWorker.runAll();
            ledgerWorker.runAll();
        }
        ShadowLooper.idleMainLooper();
    }

    /** Dispatch delayed generation only after the inherited parent delay has elapsed. */
    private void processReplies() {
        pump();
        long delay = Math.max(2, Math.min(3600, new LocalOptions(values).integer("reply_delay_seconds", 2))) * 1000L;
        advanceMillis(delay);
    }

    private void advanceMillis(long millis) {
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(millis));
        pump();
    }

    private CommentReplyLedger ledger() { return (CommentReplyLedger) HostTargets.read(hook, "ledger"); }
    private List<AutomationHistoryStore.Entry> history() { return new AutomationHistoryStore(activity).read("comment_reply"); }

    private void replaceField(String name, Object value) throws Exception {
        java.lang.reflect.Field field = CommentReplyHook.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(hook, value);
    }

    private void drainLedgerOffMain() throws Exception {
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        Thread io = new Thread(() -> {
            try { ledgerWorker.runAll(); }
            catch (Throwable error) { failure.set(error); }
        }, "comment-reply-ledger-test");
        io.start();
        io.join(5_000);
        assertFalse("Ledger worker did not finish", io.isAlive());
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static CommentReplyEvent event(String id, String root, String sender, long createdAt) {
        return new CommentReplyEvent("notice-" + id, "work", id, root, sender, "对方", "回复 " + id,
                "我的原评论", createdAt, new Work(), new Object());
    }

    public static final class Work { public String getDesc() { return "作品描述"; } }

    private static final class FakeSource implements CommentReplyHook.Source {
        final List<Long> baselines = new ArrayList<>(), scans = new ArrayList<>(), since = new ArrayList<>();
        final Map<Long, List<CommentReplyEvent>> events = new HashMap<>();
        List<String> baselineIds = Collections.emptyList();
        long through;
        boolean failScan;
        Exception scanFailure;
        Runnable duringScan;
        @Override public long baseline(long account, BooleanSupplier valid) {
            assertTrue(valid.getAsBoolean());
            baselines.add(account);
            return 1000;
        }
        @Override public List<String> baselineIds() { return baselineIds; }
        @Override public long scannedThrough() { return through; }
        @Override public List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception {
            assertTrue(valid.getAsBoolean());
            scans.add(account);
            this.since.add(since);
            Runnable interruptedScan = duringScan;
            duringScan = null;
            if (interruptedScan != null) interruptedScan.run();
            if (scanFailure != null) throw scanFailure;
            if (failScan) throw new java.io.IOException("fixture failure");
            return new ArrayList<>(events.getOrDefault(account, Collections.emptyList()));
        }
    }

    private static final class FakeSender implements CommentReplyHook.Sender {
        final List<String> sent = new ArrayList<>();
        final List<CommentReplyEvent> submittedEvents = new ArrayList<>();
        final List<SpeechPreparation.Audio> audios = new ArrayList<>();
        final List<Consumer<String>> callbacks = new ArrayList<>();
        final List<BooleanSupplier> boundaries = new ArrayList<>();
        final List<Runnable> delayed = new ArrayList<>();
        boolean delaySubmission;
        boolean omitAccepted;
        String outcome = "sent";
        @Override public void send(CommentReplyEvent event, String text, BooleanSupplier valid,
                                   BooleanSupplier beforeSend, Runnable accepted, Consumer<String> result) {
            send(event, text, null, valid, beforeSend, accepted, result);
        }
        @Override public void send(CommentReplyEvent event, String text, SpeechPreparation.Audio audio, BooleanSupplier valid,
                                   BooleanSupplier beforeSend, Runnable accepted, Consumer<String> result) {
            boundaries.add(beforeSend);
            Runnable submit = () -> {
                if (!valid.getAsBoolean() || !beforeSend.getAsBoolean()) { result.accept("cancelled"); return; }
                sent.add(event.incomingId);
                submittedEvents.add(event);
                audios.add(audio);
                callbacks.add(result);
                if (!omitAccepted) accepted.run();
                if (outcome != null) result.accept(outcome);
            };
            if (delaySubmission) delayed.add(submit); else submit.run();
        }
    }

    private static final class FakeGenerator implements CommentReplyHook.Generator {
        final List<String> ids = new ArrayList<>();
        final List<String> modes = new ArrayList<>();
        final List<Long> accounts = new ArrayList<>();
        final List<List<ChatImage>> generatedImages = new ArrayList<>(), acknowledgedImages = new ArrayList<>();
        final List<String> prompts = new ArrayList<>(), acknowledgedPrompts = new ArrayList<>();
        Runnable duringGenerate;
        int cancels;
        boolean failAfterRequest;
        Exception failureAfterRequest;
        @Override public CommentReplyHook.Generated generate(long account, CommentReplyEvent event, LocalOptions options,
                List<ChatImage> images, String prompt, java.util.function.IntConsumer sent) throws Exception {
            generatedImages.add(images);
            prompts.add(prompt);
            if ("ai".equals(options.text("reply_mode", "fixed"))) sent.accept(images.size());
            CommentReplyHook.Generated result = generate(account, event, options);
            if (failureAfterRequest != null) throw failureAfterRequest;
            if (failAfterRequest) throw new java.io.IOException("fixture failure after request was sent");
            return result;
        }
        @Override public void acknowledged(long account, CommentReplyEvent event, LocalOptions options, String text,
                List<ChatImage> images, String prompt) {
            acknowledgedImages.add(images);
            acknowledgedPrompts.add(prompt);
        }
        @Override public CommentReplyHook.Generated generate(long account, CommentReplyEvent event, LocalOptions options) {
            ids.add(event.incomingId);
            modes.add(options.text("reply_mode", "fixed"));
            accounts.add(account);
            if (duringGenerate != null) duringGenerate.run();
            return new CommentReplyHook.Generated("AI 正文 " + event.incomingId, "model-fixture");
        }
        @Override public void cancel() { cancels++; }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>();
        boolean stopped, paused;
        @Override public void execute(Runnable task) {
            if (stopped) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(task);
        }
        void runAll() { if (!paused) while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; List<Runnable> remaining = new ArrayList<>(tasks); tasks.clear(); return remaining; }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return stopped; }
    }
}
