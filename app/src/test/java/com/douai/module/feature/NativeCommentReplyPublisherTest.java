package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.nio.file.Files;
import java.time.Duration;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeCommentReplyPublisherTest {
    private Framework framework;
    private NativeCommentPublisher publisher;
    private final List<NativeCommentPublisher> publishers = new ArrayList<>();
    private final List<File> audioFiles = new ArrayList<>();
    private final List<String> diagnostics = new ArrayList<>();

    @Before public void install() throws Exception {
        framework = new Framework();
        Request.models.clear();
        Request.instances.clear();
        Request.framework = framework;
        Request.uploadAudio = false;
        Request.foreignUpload = false;
        Resolver.wrongRoot = false;
        Resolver.wrongDirect = false;
        Resolver.latestModel = null;
        publisher = NativeCommentPublisher.install(framework.api, report(), getClass().getClassLoader(),
                diagnostics::add, "DouAi.CommentReply");
        publishers.add(publisher);
    }

    @After public void close() {
        for (NativeCommentPublisher installed : publishers) installed.close();
        ShadowLooper.idleMainLooper();
        for (File audio : audioFiles) audio.delete();
    }

    @Test public void sendsTheCapturedReplyModelThroughTheHostAndConfirmsTheExactNestedTarget() throws Throwable {
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertTrue(outcomes.isEmpty());
        Model model = Request.models.get(0);
        assertSame(event.targetComment, model.p);
        assertSame(event.work, model.y.work);
        assertEquals("正文", model.a);
        assertEquals("douai_comment_reply", model.n);
        assertTrue(publisher.ownsPublish(model));
        assertWireTarget(0, "root", "incoming");
        complete(0, wireResponse(0));
        assertEquals(Collections.singletonList("sent"), outcomes);
        model.a = "已被更改";
        assertFalse(publisher.ownsPublish(model));
    }

    @Test public void ownedReplyReceiptPublishesItsIdBeforeTheUnchangedBusinessOutcome() throws Throwable {
        List<String> events = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", null, () -> true, () -> true, () -> { },
                status -> events.add("status:" + status), cid -> events.add("cid:" + cid));
        ShadowLooper.idleMainLooper();
        Response receipt = wireResponse(0);
        complete(0, receipt);
        assertEquals(List.of("cid:" + receipt.comment.cid, "status:sent"), events);
        complete(0, receipt);
        assertEquals(2, events.size());
    }

    @Test public void navigationObserverFailureCannotChangeANativeReplyOutcome() throws Throwable {
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", null, () -> true, () -> true, () -> { },
                outcomes::add, cid -> { throw new IllegalStateException("observer"); });
        ShadowLooper.idleMainLooper();
        complete(0, wireResponse(0));
        assertEquals(List.of("sent"), outcomes);
    }

    @Test public void duplicateAndExpiredCallbacksKeepVerifiedTraceWithoutChangingBusinessOutcomes() throws Throwable {
        DouAiLog.awaitIdle(3000);
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            DouAiLog.Operation first = DouAiLog.begin("auto_comment_reply", "callback_duplicate_fixture");
            List<String> outcomes = new ArrayList<>();
            first.wrap(() -> publisher.sendReply(event("incoming", "root"), "正文", () -> true,
                    () -> true, () -> { }, outcomes::add)).run();
            ShadowLooper.idleMainLooper();
            complete(0, wireResponse(0));
            first.finish(DouAiLog.Outcome.SUCCESS, "fixture_ack", "target_verified=true");
            complete(0, wireResponse(0));
            assertEquals(Collections.singletonList("sent"), outcomes);

            DouAiLog.Operation second = DouAiLog.begin("auto_comment_reply", "callback_retention_fixture");
            List<String> late = new ArrayList<>();
            second.wrap(() -> publisher.sendReply(event("incoming-2", "root"), "正文", () -> true,
                    () -> true, () -> { }, late::add)).run();
            ShadowLooper.idleMainLooper();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(120_001));
            assertEquals(Collections.singletonList("submitted"), late);
            second.finish(DouAiLog.Outcome.UNCONFIRMED, "fixture_timeout", "remote_outcome=unknown");
            complete(1, wireResponse(1));
            assertEquals(Collections.singletonList("submitted"), late);
            assertTrue(DouAiLog.awaitIdle(3000));
            assertTrue(records.toString(), records.stream().anyMatch(line -> line.contains(first.traceId())
                    && line.contains("\"event\":\"callback_ignored\"") && line.contains("callback_already_processed")
                    && line.contains("ownership_identity_match=true")));
            assertTrue(records.toString(), records.stream().anyMatch(line -> line.contains(second.traceId())
                    && line.contains("\"event\":\"callback_ignored\"") && line.contains("\"reason_code\":\"retention_expired\"")));
        } finally { DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(previousDebug); }
    }

    @Test public void aQueuedCallbackAfterCloseHasKnownOwnershipWhileForeignCallbackStaysUnknown() throws Throwable {
        DouAiLog.awaitIdle(3000);
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            framework.invoke(Request.class.getMethod("success", Response.class), new Request(), response("work", "root", "incoming"));
            ShadowLooper.idleMainLooper();
            DouAiLog.Operation trace = DouAiLog.begin("auto_comment_reply", "callback_close_fixture");
            List<String> outcomes = new ArrayList<>();
            trace.wrap(() -> publisher.sendReply(event("incoming", "root"), "正文", () -> true,
                    () -> true, () -> { }, outcomes::add)).run();
            ShadowLooper.idleMainLooper();
            framework.invoke(Request.class.getMethod("success", Response.class), Request.instances.get(0), wireResponse(0));
            publisher.close();
            ShadowLooper.idleMainLooper();
            assertTrue(outcomes.isEmpty());
            trace.finish(DouAiLog.Outcome.UNCONFIRMED, "fixture_closed", "remote_outcome=unknown");
            assertTrue(DouAiLog.awaitIdle(3000));
            assertTrue(records.toString(), records.stream().anyMatch(line -> line.contains("\"event\":\"callback_ignored\"")
                    && line.contains("\"reason_code\":\"ownership_unknown\"") && line.contains("ownership_identity_match=false")));
            assertTrue(records.toString(), records.stream().anyMatch(line -> line.contains(trace.traceId())
                    && line.contains("\"event\":\"callback_ignored\"") && line.contains("\"reason_code\":\"publisher_closed\"")
                    && line.contains("ownership_identity_match=true")));
        } finally { DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(previousDebug); }
    }

    @Test public void retainedLateReceiptKeepsItsActualStatusAfterTheRootWasMarkedUnconfirmed() throws Throwable {
        DouAiLog.awaitIdle(3000);
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        DouAiLog.Operation trace = DouAiLog.begin("auto_comment_reply", "retained_late_callback_fixture");
        try {
            List<String> outcomes = new ArrayList<>();
            trace.wrap(() -> publisher.sendReply(event("incoming", "root"), "正文", () -> true,
                    () -> true, () -> { }, outcomes::add)).run();
            ShadowLooper.idleMainLooper();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(30_001));
            assertEquals(Collections.singletonList("submitted"), outcomes);
            trace.finish(DouAiLog.Outcome.UNCONFIRMED, "fixture_ack_timeout", "remote_outcome=unknown");
            complete(0, wireResponse(0));
            assertEquals(Arrays.asList("submitted", "sent"), outcomes);
            assertTrue(DouAiLog.awaitIdle(3000));
            assertTrue(records.toString(), records.stream().anyMatch(line -> line.contains(trace.traceId())
                    && line.contains("\"event\":\"late_native_ack\"") && line.contains("status=sent")
                    && line.contains("timed_out=true")));
            assertEquals(1, records.stream().filter(line -> line.contains(trace.traceId())
                    && line.contains("\"event\":\"operation_finished\"")).count());
        } finally {
            trace.finish(DouAiLog.Outcome.UNCONFIRMED, "fixture_cleanup", "");
            DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void callbackEvidenceHistoryUsesIdentityAndExplicitlyForgetsExpiredAndEvictedOwners() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong();
        NativeCommentPublisher.CallbackHistory history = new NativeCommentPublisher.CallbackHistory(2, 100, now::get);
        DouAiLog.Operation trace = DouAiLog.begin("auto_comment_reply", "callback_history_fixture");
        Object first = new String("same-business-id"), differentIdentity = new String("same-business-id");
        Object second = new Object(), third = new Object();
        try {
            history.remember(first, trace, "retention_expired", "unknown");
            assertSame(trace, history.find(first).trace);
            assertNull("Equal business data cannot prove request identity", history.find(differentIdentity));
            history.remember(second, trace, "publisher_closed", "unknown");
            history.remember(third, trace, "callback_already_processed", "sent");
            assertEquals(2, history.size());
            assertNull(history.find(first));
            assertTrue(history.diagnostic().contains("history_evicted=1"));
            assertEquals("publisher_closed", history.find(second).reason);
            now.set(100);
            assertNull(history.find(second));
            assertNull(history.find(third));
            assertEquals(0, history.size());
            assertTrue(history.diagnostic().contains("history_expired=2"));
        } finally { trace.finish(DouAiLog.Outcome.CANCELLED, "fixture_done", ""); }
    }

    @Test public void legacyTargetsWithoutARepeatedWorkIdStillFailBeforeNativeSubmission() {
        for (String missingAid : new String[]{null, ""}) {
            CommentReplyEvent event = event("incoming", "root");
            assertFalse(event.nativeWorkBound);
            ((Comment) event.targetComment).awemeId = missingAid;
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true,
                    () -> { fail("A legacy target without its work ID cannot reserve a send"); return true; },
                    () -> fail("A legacy target without its work ID cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void nativeWorkBoundTargetsWithoutARepeatedWorkIdUseCapturedWorkAndRequireAnExactAck() throws Throwable {
        int index = 0;
        for (String missingAid : new String[]{null, ""}) {
            CommentReplyEvent event = nativeWorkBoundEvent("incoming-" + index, "root");
            ((Comment) event.targetComment).awemeId = missingAid;
            List<String> outcomes = new ArrayList<>();
            AtomicInteger accepted = new AtomicInteger();
            publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals("The verified native work binding must permit a missing repeated target work ID", 1, accepted.get());
            assertTrue(outcomes.isEmpty());
            Model model = Request.models.get(index);
            assertSame(event.targetComment, model.p);
            assertSame(event.work, model.y.work);
            assertEquals(missingAid, ((Comment) event.targetComment).awemeId);
            assertWireTarget(index, "root", event.incomingId);
            complete(index, wireResponse(index));
            assertEquals(Collections.singletonList("sent"), outcomes);
            index++;
        }
        CommentReplyEvent wrongAck = nativeWorkBoundEvent("incoming-wrong-ack", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(wrongAck, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertTrue(outcomes.isEmpty());
        Response mismatch = wireResponse(index);
        mismatch.comment.replyToReplyId = "different-incoming";
        complete(index, mismatch);
        assertEquals(Collections.singletonList("submitted"), outcomes);
    }

    @Test public void aStrictlyConvertedParentlessInboxReplyPublishesItsPrivateTargetAndChecksTheExactAck() throws Throwable {
        try (NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(NativeCommentReplyInboxTest.report(),
                getClass().getClassLoader(), ignored -> { })) {
            int index = 0;
            for (boolean exactAck : new boolean[]{true, false}) {
                String incomingId = "directed-incoming-" + index;
                NativeCommentReplyInboxTest.Notice notice = NativeCommentReplyInboxTest.parentlessDirectedNotice(
                        "directed-notice-" + index, incomingId, "200", "101", 210);
                notice.interactiveGeneralNoticeStruct.aweme = new Work();
                NativeCommentReplyInboxTest.ReplyDetail detail = notice.interactiveGeneralNoticeStruct.additionalInfo.comment;
                detail.d = false;
                detail.f = "unrelated-ui-metadata";
                NativeCommentReplyInboxTest.Comment raw = detail.a;
                raw.awemeId = null;

                CommentReplyEvent event = inbox.extract(notice, 100);

                assertNotNull(event);
                assertTrue(event.nativeWorkBound);
                assertEquals(incomingId, event.incomingId);
                assertEquals("101", event.rootId);
                assertNotSame(raw, event.targetComment);
                NativeCommentReplyInboxTest.Comment captured = (NativeCommentReplyInboxTest.Comment) event.targetComment;
                assertEquals(incomingId, captured.cid);
                assertEquals("101", captured.replyId);
                assertEquals("0", captured.replyToReplyId);
                assertNull(captured.awemeId);
                assertSame(raw, detail.a);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);

                List<String> outcomes = new ArrayList<>();
                AtomicInteger accepted = new AtomicInteger();
                publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
                ShadowLooper.idleMainLooper();

                assertEquals(1, accepted.get());
                assertTrue(outcomes.isEmpty());
                Model model = Request.models.get(index);
                assertSame(captured, model.p);
                assertSame(event.work, model.y.work);
                assertEquals("101", model.p.replyId);
                assertEquals("0", model.p.replyToReplyId);
                assertEquals(incomingId, model.p.cid);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                assertWireTarget(index, "101", incomingId);
                Response response = wireResponse(index);
                if (!exactAck) response.comment.replyToReplyId = "different-incoming";
                complete(index, response);
                assertEquals(Collections.singletonList(exactAck ? "sent" : "submitted"), outcomes);
                index++;
            }
            assertEquals(2, Request.models.size());
        }
    }

    @Test public void realThreadLookupRecoveredInboxEventsKeepTheServerTargetAndRequireExactPublishAcks() throws Throwable {
        NativeCommentReplyInboxTest.FakeGroupResolver.group = 44;
        NativeCommentReplyInboxTest.FakeGroupResolver.failure = null;
        NativeCommentReplyInboxTest.AccountFactory.service = new NativeCommentReplyInboxTest.Account();
        NativeCommentReplyInboxTest.AccountFactory.service.user = new NativeCommentReplyInboxTest.User("100");
        for (int index = 0; index < 2; index++) {
            String incomingId = "thread-publish-" + index;
            NativeCommentThreadLookupTest.LookupHarness host = NativeCommentThreadLookupTest.harness("101", incomingId, 900);
            host.incoming.replyToReplyId = "101";
            NativeCommentReplyInboxTest.Notice sparse = NativeCommentReplyInboxTest.sparseNotice(
                    "thread-notice-" + index, incomingId, "200", 2000);
            NativeCommentReplyInboxTest.Comment raw = sparse.interactiveGeneralNoticeStruct.additionalInfo.comment.a;
            raw.awemeId = null;
            NativeCommentReplyInboxTest.Api api = new NativeCommentReplyInboxTest.Api();
            NativeCommentReplyInboxTest.Provider.b = api;
            api.pages.add(NativeCommentReplyInboxTest.page(false, 0, 0, sparse));
            try (NativeCommentReplyInbox inbox = new NativeCommentReplyInbox(NativeCommentReplyInboxTest.report(),
                    getClass().getClassLoader(), ignored -> { }, host.lookup)) {
                List<CommentReplyEvent> events = inbox.scan(100, 1000, () -> true);
                assertEquals(1, events.size());
                CommentReplyEvent event = events.get(0);
                assertSame(host.incoming, event.targetComment);
                assertSame(host.work, event.work);
                assertTrue(event.nativeWorkBound);
                assertEquals("101", event.rootId);
                assertEquals(2000, event.createdAt);
                assertNotSame(raw, event.targetComment);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                assertNull(raw.awemeId);

                AtomicInteger accepted = new AtomicInteger();
                List<String> outcomes = new ArrayList<>();
                publisher.sendReply(event, "正文", () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
                ShadowLooper.idleMainLooper();

                assertEquals(1, accepted.get());
                assertTrue(outcomes.isEmpty());
                Model model = Request.models.get(index);
                assertSame("Publishing must use the complete raw server comment, without reconstructing it", host.incoming, model.p);
                assertSame(host.work, model.y.work);
                assertEquals(incomingId, model.p.cid);
                assertEquals("101", model.p.replyId);
                assertEquals("101", model.p.replyToReplyId);
                assertEquals("0", raw.replyId);
                assertEquals("", raw.replyToReplyId);
                assertNull(raw.awemeId);
                assertWireTarget(index, "101", incomingId);
                Response response = wireResponse(index);
                if (index != 0) response.comment.replyToReplyId = "unrelated-incoming";
                complete(index, response);
                assertEquals(Collections.singletonList(index == 0 ? "sent" : "submitted"), outcomes);
            }
        }
        assertEquals(2, Request.models.size());
    }

    @Test public void nativeWorkBindingCannotOverrideConflictingOrChangedWorkCommentAndRootIdentity() {
        List<CommentReplyEvent> events = new ArrayList<>();
        CommentReplyEvent conflictingAid = nativeWorkBoundEvent("incoming", "root");
        ((Comment) conflictingAid.targetComment).awemeId = "another-work";
        events.add(conflictingAid);
        CommentReplyEvent changedWork = nativeWorkBoundEvent("incoming", "root");
        ((Work) changedWork.work).aid = "another-work";
        events.add(changedWork);
        CommentReplyEvent changedComment = nativeWorkBoundEvent("incoming", "root");
        ((Comment) changedComment.targetComment).cid = "another-comment";
        events.add(changedComment);
        CommentReplyEvent changedRoot = nativeWorkBoundEvent("incoming", "root");
        ((Comment) changedRoot.targetComment).replyId = "another-root";
        events.add(changedRoot);
        events.add(new CommentReplyEvent("notice", "work", "incoming", "root", "2", "对方", "回复", "原评",
                1000, new Work(), new Object(), true));
        for (CommentReplyEvent event : events) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true,
                    () -> { fail("Changed identity cannot reserve a native send"); return true; },
                    () -> fail("Changed identity cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void nativeWorkBoundIdentityIsRecheckedAfterTheNativeReservation() {
        for (String change : new String[]{"work", "comment", "root"}) {
            CommentReplyEvent event = nativeWorkBoundEvent("incoming", "root");
            List<String> outcomes = new ArrayList<>();
            AtomicInteger reservations = new AtomicInteger();
            publisher.sendReply(event, "正文", () -> true, () -> {
                reservations.incrementAndGet();
                if ("work".equals(change)) ((Work) event.work).aid = "changed-work";
                if ("comment".equals(change)) ((Comment) event.targetComment).cid = "changed-comment";
                if ("root".equals(change)) ((Comment) event.targetComment).replyId = "changed-root";
                return true;
            }, () -> fail("Identity changed during reservation cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals("The initially verified native target should reach reservation", 1, reservations.get());
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void voiceAttachmentAndCapturedNestedReplyTargetCoexistAndCallbackStillChecksTheTarget() throws Throwable {
        SpeechPreparation.Audio audio = audio();
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.sendReply(event, "生成的语音正文", audio, () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        Model model = Request.models.get(0);
        assertSame(event.targetComment, model.p);
        assertSame(event.work, model.y.work);
        assertEquals("", model.a);
        assertNotNull(model.A);
        assertSame(audio.file, model.A.audio.localFile);
        assertEquals(audio.duration, model.A.audio.duration.longValue());
        assertFalse("KMP omits the whole audio slot when audio_wave is empty", model.A.audio.waveList.isEmpty());
        for (Object value : model.A.audio.waveList) {
            assertTrue("Wire waveform amplitudes must be normalized", value instanceof Float
                    && Float.isFinite((Float) value) && (Float) value >= 0 && (Float) value <= 1);
        }
        assertEquals("", model.A.text.toString());
        assertEquals(Collections.emptyList(), model.d);
        assertEquals("douai_comment_reply", model.n);
        assertTrue(publisher.ownsPublish(model));
        assertWireTarget(0, "root", "incoming");
        Response response = wireResponse(0);
        response.comment.replyId = "different-root";
        complete(0, response);
        assertEquals(Collections.singletonList("submitted"), outcomes);
    }

    @Test public void checkedFalseAfterOwnedVoiceUploadIsAcceptedAndStaysSubmittedUntilAnExactAck() throws Throwable {
        Request.uploadAudio = true;
        SpeechPreparation.Audio audio = audio();
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        // Empty text is valid for an audio-only host comment, while the reply target remains mandatory.
        publisher.sendReply(event("incoming", "root"), "", audio, () -> true, () -> true,
                accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        assertEquals(1, Request.models.size());
        assertTrue(outcomes.isEmpty());
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(31));
        assertEquals(Collections.singletonList("submitted"), outcomes);
        assertEquals(1, Request.models.size());
        assertWireTarget(0, "root", "incoming");
        complete(0, wireResponse(0));
        assertEquals(Arrays.asList("submitted", "sent"), outcomes);
    }

    @Test public void ownedAudioCallbackDistinguishesUploadFromPublishWithoutRevealingTheVid() throws Throwable {
        Request.uploadAudio = true;
        String[] vids = {null, "", "private-video-id", null};
        String[] phases = {"upload_or_auth", "upload_or_auth", "publish", "unknown"};
        for (int index = 0; index < vids.length; index++) {
            diagnostics.clear();
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming-" + index, "root"), "", audio(), () -> true,
                    () -> true, () -> { }, outcomes::add);
            ShadowLooper.idleMainLooper();
            Model model = Request.models.get(index);
            model.A.audio.vid = vids[index];
            if (index == 3) model.A = null;
            framework.invoke(Request.class.getMethod("failed", Exception.class), Request.instances.get(index),
                    new NativeCommentPublisherResponseTest.ApiServerFailure(12345));
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
            String phase = phases[index];
            assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("native callback: failed")
                    && value.contains("code=12345") && value.contains("audio_phase=" + phase)));
            assertFalse(diagnostics.toString().contains("private-video-id"));
            assertFalse(diagnostics.toString().contains("private-key"));
        }
    }

    @Test public void ownedAudioSuccessReportsWhetherTheCommentContainsCompleteAudioMetadata() throws Throwable {
        Request.uploadAudio = true;
        for (int index = 0; index < 5; index++) {
            diagnostics.clear();
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming-" + index, "root"), "", audio(), () -> true,
                    () -> true, () -> { }, outcomes::add);
            ShadowLooper.idleMainLooper();
            Response response = wireResponse(index);
            if (index != 1) {
                response.comment.commentAudio = new ReceiptAudio();
                response.comment.commentAudio.a = index == 2 ? "" : "private-receipt-vid";
                response.comment.commentAudio.e = index == 4 ? null : Long.valueOf(index == 3 ? 0L : 33_516L);
            }
            complete(index, response);
            assertEquals("Receipt diagnostics must not change the existing success decision",
                    Collections.singletonList("sent"), outcomes);
            String expected = index == 0 ? "present" : "missing";
            assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("native callback: sent")
                    && value.contains("audio_receipt=" + expected)));
            assertFalse(diagnostics.toString().contains("private-receipt-vid"));
        }
    }

    @Test public void ownedAudioFailuresClassifyVerifiedServerHintsWithoutPrintingThemOrInferringFromCodeFive() throws Throwable {
        Request.uploadAudio = true;
        Exception[] failures = {
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5,
                        "音频时长超出限制 private-hint https://private.invalid/format?key=private-key", "参数错误"),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "",
                        "音频格式不支持，内容审核，参数错误，请求过于频繁 private-hint"),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "文件体积过大，没有语音评论权限", ""),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "服务器暂时不可用 private-hint", ""),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "", ""),
                new UnverifiedHintFailure(),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5,
                        "https://private.invalid/format?duration=30&token=private-key", ""),
                new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "private-hint", "", true)
        };
        String[] hints = {"duration", "format+frequency+parameter+content", "size+permission", "other", "empty", "unknown", "other", "unknown"};
        for (int index = 0; index < failures.length; index++) {
            diagnostics.clear();
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming-" + index, "root"), "", audio(), () -> true, () -> true, () -> { }, outcomes::add);
            ShadowLooper.idleMainLooper();
            Request.models.get(index).A.audio.vid = "private-video-id";
            framework.invoke(Request.class.getMethod("failed", Exception.class), Request.instances.get(index), failures[index]);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
            String expected = "audio_hint=" + hints[index];
            assertTrue(expected + ": " + diagnostics, diagnostics.stream().anyMatch(value -> value.contains("native callback: failed")
                    && value.contains("code=5") && value.contains(expected) && value.contains("audio_phase=publish")));
            for (String secret : new String[]{"private-hint", "private-key", "private.invalid", "private-video-id", "private-throwable", "private-getter"}) {
                assertFalse(secret, diagnostics.toString().contains(secret));
            }
        }
    }

    @Test public void ownedAudioFailureReportsCapturedTypedPermissionsWithoutUsingThemAsASendGate() throws Throwable {
        Request.uploadAudio = true;
        Object[] settings = {new FeedConfig(0, false), new FeedConfig(1, true), new FeedConfig(7, false), null,
                new WrongFeedConfig(), new FeedConfig(0, false)};
        String[] permissions = {"0", "1", "7", "unknown", "unknown", "0"};
        String[] blocks = {"false", "true", "false", "unknown", "unknown", "false"};
        for (int index = 0; index < settings.length; index++) {
            diagnostics.clear();
            CommentReplyEvent event = event("incoming-" + index, "root");
            Work work = (Work) event.work;
            work.feedCommentConfig = settings[index];
            List<String> outcomes = new ArrayList<>();
            AtomicInteger accepted = new AtomicInteger();
            publisher.sendReply(event, "", audio(), () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals("Permission diagnostics must not become a new send gate", 1, accepted.get());
            if (index == 5) work.feedCommentConfig = new FeedConfig(1, true);
            framework.invoke(Request.class.getMethod("failed", Exception.class), Request.instances.get(index),
                    new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "", ""));
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
            String permission = "audio_permission=" + permissions[index], block = "audio_block=" + blocks[index];
            assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("native callback: failed")
                    && value.contains(permission) && value.contains(block)));
        }
    }

    @Test public void audioFailureHintAndPermissionDiagnosticsIgnoreForeignCallbacksAndOrdinaryTextFailures() throws Throwable {
        diagnostics.clear(); // Probe-registration messages precede this request-ownership check.
        Exception failure = new com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException(5, "时长 private-hint", "");
        framework.invoke(Request.class.getMethod("failed", Exception.class), new Request(), failure);
        ShadowLooper.idleMainLooper();
        assertTrue("A foreign callback must not produce owned diagnostics", diagnostics.isEmpty());

        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "普通文字", () -> true, () -> true, () -> { }, outcomes::add);
        ShadowLooper.idleMainLooper();
        framework.invoke(Request.class.getMethod("failed", Exception.class), Request.instances.get(0), failure);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        assertFalse(diagnostics.toString().contains("audio_hint="));
        assertFalse(diagnostics.toString().contains("audio_permission="));
        assertFalse(diagnostics.toString().contains("audio_block="));
        assertFalse(diagnostics.toString().contains("private-hint"));
    }

    @Test public void missingOptionalPermissionFieldTypeCannotPreventTheOwnedAudioSubmission() throws Throwable {
        Request.uploadAudio = true;
        Class<?> isolated = NativeCommentPublisherResponseTest.isolatedDiagnosticFixture(
                LinkageWork.class, MissingPermissionType.class.getName());
        Object work = isolated.getConstructor().newInstance();
        // This is field type resolution itself, not a getter throwing through InvocationTargetException.
        assertThrows(NoClassDefFoundError.class, () -> isolated.getDeclaredField("feedCommentConfig"));
        List<String> outcomes = new ArrayList<>();
        AtomicInteger accepted = new AtomicInteger();
        publisher.send(work, "work", "", audio(), () -> true, () -> true, accepted::incrementAndGet, outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(1, accepted.get());
        framework.invoke(Request.class.getMethod("failed", Exception.class), Request.instances.get(0),
                new NativeCommentPublisherResponseTest.ApiServerFailure(5));
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        assertTrue(diagnostics.toString(), diagnostics.stream().anyMatch(value -> value.contains("native callback: failed")
                && value.contains("audio_permission=unknown") && value.contains("audio_block=unknown")));
    }

    @Test public void foreignVoiceUploadDoesNotMakeARejectedReplyLookAccepted() throws Exception {
        Request.uploadAudio = true;
        Request.foreignUpload = true;
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", audio(), () -> true, () -> true,
                () -> fail("Foreign upload cannot accept this reply"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
    }

    @Test public void invalidVoiceAttachmentFailsBeforeReservationOrNativeReplySubmission() throws Exception {
        SpeechPreparation.Audio audio = audio();
        assertTrue(audio.file.delete());
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event("incoming", "root"), "正文", audio, () -> true,
                () -> { fail("Invalid audio cannot reserve a native send"); return true; },
                () -> fail("Invalid audio cannot be accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        assertTrue(Request.models.isEmpty());
    }

    @Test public void incompleteOrMisroutedCallbacksRemainUnknownWhileExplicitErrorsFail() throws Throwable {
        for (int index = 0; index < 4; index++) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming", "root"), "正文", () -> true, () -> true, () -> { }, outcomes::add);
            ShadowLooper.idleMainLooper();
            Response response = wireResponse(index);
            if (index == 0) response.comment.replyId = "other-root";
            if (index == 1) response.comment.replyToReplyId = null;
            if (index == 2) response.comment.awemeId = null;
            if (index == 3) response.status_code = 12;
            complete(index, response);
            assertEquals(Collections.singletonList(index == 3 ? "failed" : "submitted"), outcomes);
        }
    }

    @Test public void rejectsChangedWorkCommentRootAndForeignModelTypesBeforeTheHostSend() {
        List<CommentReplyEvent> events = new ArrayList<>();
        CommentReplyEvent changedComment = event("incoming", "root");
        ((Comment) changedComment.targetComment).cid = "another";
        events.add(changedComment);
        CommentReplyEvent changedAid = event("incoming", "root");
        ((Comment) changedAid.targetComment).awemeId = "another-work";
        events.add(changedAid);
        CommentReplyEvent changedRoot = event("incoming", "root");
        ((Comment) changedRoot.targetComment).replyId = "another-root";
        events.add(changedRoot);
        CommentReplyEvent changedWork = event("incoming", "root");
        ((Work) changedWork.work).aid = "another-work";
        events.add(changedWork);
        events.add(new CommentReplyEvent("notice", "work", "incoming", "root", "2", "对方", "回复", "原评",
                1000, new Work(), new Object()));
        for (CommentReplyEvent event : events) {
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event, "正文", () -> true, () -> true, () -> fail("Rejected reply was accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
        }
        assertTrue(Request.models.isEmpty());
    }

    @Test public void rechecksReplyIdentityAfterTheNativeReservationAndHonorsCancellation() {
        CommentReplyEvent event = event("incoming", "root");
        List<String> outcomes = new ArrayList<>();
        publisher.sendReply(event, "正文", () -> true, () -> {
            ((Comment) event.targetComment).cid = "changed-during-reservation";
            return true;
        }, () -> fail("Changed reply was accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList("failed"), outcomes);
        publisher.sendReply(event("incoming", "root"), "正文", () -> false, () -> true,
                () -> fail("Cancelled reply was accepted"), outcomes::add);
        ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList("failed", "cancelled"), outcomes);
        assertTrue(Request.models.isEmpty());
    }

    @Test public void rejectsNativeWireResolversThatDisagreeWithTheExactReplyTargetBeforeSubmission() {
        for (boolean wrongRoot : new boolean[]{true, false}) {
            Resolver.wrongRoot = wrongRoot;
            Resolver.wrongDirect = !wrongRoot;
            List<String> outcomes = new ArrayList<>();
            publisher.sendReply(event("incoming", "root"), "正文", () -> true, () -> true,
                    () -> fail("A mismatched native wire target cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(Collections.singletonList("failed"), outcomes);
            assertTrue("A resolver mismatch must fail before the checked native publisher runs", Request.models.isEmpty());
        }
    }

    @Test public void rechecksInheritedNativeReplyIdsAfterReservationBeforeTheHostCanSend() {
        for (boolean changeRoot : new boolean[]{true, false}) {
            List<String> outcomes = new ArrayList<>();
            AtomicInteger reservations = new AtomicInteger();
            publisher.sendReply(event("incoming", "root"), "正文", () -> true, () -> {
                reservations.incrementAndGet();
                assertNotNull("The initial native helper check must observe the prepared model", Resolver.latestModel);
                if (changeRoot) Resolver.latestModel.b = "changed-root";
                else Resolver.latestModel.e = "changed-incoming";
                return true;
            }, () -> fail("A reply whose wire target changed during reservation cannot be accepted"), outcomes::add);
            ShadowLooper.idleMainLooper();
            assertEquals(1, reservations.get());
            assertEquals(Collections.singletonList("failed"), outcomes);
            assertTrue("Changed inherited IDs must not reach the native publisher", Request.models.isEmpty());
            Resolver.latestModel = null;
        }
    }

    @Test public void independentNamespacesKeepOldWorkCommentsAndRepliesOwnedByTheirSenders() throws Throwable {
        NativeCommentPublisher old = NativeCommentPublisher.install(framework.api, report(), getClass().getClassLoader(), ignored -> { });
        publishers.add(old);
        assertEquals(6, framework.ids.size());
        assertTrue(framework.ids.contains("DouAi.AutoComment.auto_comment_publish_success"));
        assertTrue(framework.ids.contains("DouAi.CommentReply.auto_comment_publish_success"));
        List<String> oldResults = new ArrayList<>(), replyResults = new ArrayList<>();
        old.send(new Work(), "work", "作品评论", () -> true, () -> true, () -> { }, oldResults::add);
        publisher.sendReply(event("root", "root"), "评论回复", () -> true, () -> true, () -> { }, replyResults::add);
        ShadowLooper.idleMainLooper();
        assertNull(Request.models.get(0).p);
        assertEquals("douai_auto", Request.models.get(0).n);
        assertWireTarget(0, null, null);
        assertWireTarget(1, "root", "0");
        complete(1, wireResponse(1));
        assertTrue(oldResults.isEmpty());
        assertEquals(Collections.singletonList("sent"), replyResults);
        complete(0, wireResponse(0));
        assertEquals(Collections.singletonList("sent"), oldResults);
        assertEquals(Collections.singletonList("sent"), replyResults);
    }

    private void complete(int index, Response response) throws Throwable {
        framework.invoke(Request.class.getMethod("success", Response.class), Request.instances.get(index), response);
        ShadowLooper.idleMainLooper();
    }

    private static void assertWireTarget(int index, String root, String direct) {
        Request request = Request.instances.get(index);
        assertEquals("The native ordinary publish branch reads the inherited root field, not model.p", root, request.wireRoot);
        assertEquals("The native ordinary publish branch reads the inherited direct target field, not model.p", direct, request.wireDirect);
    }

    private static Response wireResponse(int index) {
        Request request = Request.instances.get(index);
        return response(request.wireAid, request.wireRoot, request.wireDirect);
    }

    private SpeechPreparation.Audio audio() throws Exception {
        File file = Files.createTempFile("douai-comment-reply-", ".wav").toFile();
        Files.write(file.toPath(), new byte[]{1, 2, 3, 4});
        audioFiles.add(file);
        return new SpeechPreparation.Audio(file, 1400);
    }

    private static CommentReplyEvent event(String incoming, String root) {
        Comment target = new Comment();
        target.cid = incoming;
        target.awemeId = "work";
        target.replyId = incoming.equals(root) ? "0" : root;
        return new CommentReplyEvent("notice-" + incoming, "work", incoming, root, "2", "对方", "收到回复", "我的原评论",
                1000, new Work(), target);
    }

    private static CommentReplyEvent nativeWorkBoundEvent(String incoming, String root) {
        Comment target = new Comment();
        target.cid = incoming;
        target.replyId = incoming.equals(root) ? "0" : root;
        return new CommentReplyEvent("notice-" + incoming, "work", incoming, root, "2", "对方", "收到回复", "我的原评论",
                1000, new Work(), target, true);
    }

    private static Response response(String aid, String root, String incoming) {
        Response response = new Response();
        response.comment.awemeId = aid;
        response.comment.replyId = root;
        response.comment.replyToReplyId = incoming;
        return response;
    }

    public static final class Work extends NativeCommentReplyInboxTest.Work {
        public Object feedCommentConfig;
        public String getAid() { return aid; }
    }
    public static final class LinkageWork extends NativeCommentReplyInboxTest.Work {
        public Object feedCommentConfig;
        public MissingPermissionType unrelatedOptionalField;
        public String getAid() { return aid; }
    }
    public static final class MissingPermissionType { }
    public static final class FeedConfig {
        public final int audioCommentPermission;
        public final BlockConfig commentBlockConfig;
        FeedConfig(int permission, boolean block) { audioCommentPermission = permission; commentBlockConfig = new BlockConfig(block); }
    }
    public static final class BlockConfig { public final boolean blockAudio; BlockConfig(boolean value) { blockAudio = value; } }
    public static final class WrongFeedConfig {
        public final Long audioCommentPermission = 1L;
        public final WrongBlockConfig commentBlockConfig = new WrongBlockConfig();
    }
    public static final class WrongBlockConfig { public final String blockAudio = "private-block"; }
    public static final class UnverifiedHintFailure extends Exception {
        public final int errorCode = 5;
        public String LIZLLL() { throw new AssertionError("Do not invoke an unverified same-name getter"); }
        public String LIZJ() { throw new AssertionError("Do not invoke an unverified same-name getter"); }
    }
    public static final class Comment extends NativeCommentReplyInboxTest.Comment {
        public ReceiptAudio commentAudio;
        public Comment() { cid = "published"; }
    }
    public static final class ReceiptAudio { public String a; public Long e; }
    public static final class Response { public int status_code; public Comment comment = new Comment(); }
    public static final class State { public NativeCommentReplyInboxTest.Work work; }
    public static final class Resolver {
        static boolean wrongRoot, wrongDirect;
        static Model latestModel;
        public static State context(NativeCommentReplyInboxTest.Work work) { State state = new State(); state.work = work; return state; }
        public static NativeCommentReplyInboxTest.Work work(State state) { return state.work; }
        public static String replyRoot(NativeCommentReplyInboxTest.Comment comment, Model model) {
            if (comment == null) throw new IllegalArgumentException("Only reply publishing requires native reply helpers");
            latestModel = model;
            return wrongRoot ? "different-root" : model.b;
        }
        public static String replyDirect(NativeCommentReplyInboxTest.Comment comment, Model model) {
            if (comment == null) throw new IllegalArgumentException("Only reply publishing requires native reply helpers");
            latestModel = model;
            return wrongDirect ? "different-direct" : model.e;
        }
    }
    public static class BaseModel {
        public String b, e;
    }
    public static final class Model extends BaseModel {
        public String a, n;
        public State y;
        public Attachment A;
        public NativeCommentReplyInboxTest.Comment p;
        public List<?> d;
    }
    public static final class Attachment { public Audio audio; public CharSequence text; }
    public static final class Audio {
        public final File localFile;
        public final List<?> waveList;
        public final Long duration;
        public String vid;
        public Audio(File file, List<?> waves, Long duration) { localFile = file; waveList = waves; this.duration = duration; }
    }
    public static final class UploadCallback {
        public final Object l0, l1;
        UploadCallback(Object model, Object owner) { l0 = model; l1 = owner; }
    }
    public static final class Uploader { public static void upload(Object state, String path, Object callback, Object listener) { } }
    public static final class Request {
        static final List<Model> models = new ArrayList<>();
        static final List<Request> instances = new ArrayList<>();
        static Framework framework;
        static boolean uploadAudio, foreignUpload;
        String wireAid, wireRoot, wireDirect;
        public boolean checked(Model model) throws Throwable {
            models.add(model);
            instances.add(this);
            // The ordinary host publisher obtains its wire IDs through 0y6L.LJ/LJFF.
            // Both read inherited request fields; assigning only the Comment at p has no effect.
            wireAid = model.y.work.aid;
            wireRoot = model.b;
            wireDirect = model.e;
            if (!uploadAudio) return true;
            framework.invoke(Uploader.class.getMethod("upload", Object.class, String.class, Object.class, Object.class), null,
                    model.y, model.A.audio.localFile.getAbsolutePath(),
                    new UploadCallback(foreignUpload ? new Model() : model, this), new Object());
            return false;
        }
        public void success(Response response) { }
        public void failed(Exception failure) { }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "portrait_comment_aweme_resolver", Resolver.class.getMethod("work", State.class));
        add(rules, "auto_comment_work_context", Resolver.class.getMethod("context", NativeCommentReplyInboxTest.Work.class));
        add(rules, "comment_reply_publish_root", Resolver.class.getMethod("replyRoot", NativeCommentReplyInboxTest.Comment.class, Model.class));
        add(rules, "comment_reply_publish_direct", Resolver.class.getMethod("replyDirect", NativeCommentReplyInboxTest.Comment.class, Model.class));
        add(rules, "auto_comment_checked_request", Request.class.getMethod("checked", Model.class));
        add(rules, "auto_comment_publish_success", Request.class.getMethod("success", Response.class));
        add(rules, "auto_comment_publish_failure", Request.class.getMethod("failed", Exception.class));
        add(rules, "auto_comment_audio_upload", Uploader.class.getMethod("upload", Object.class, String.class, Object.class, Object.class));
        rules.add(new RuleScanResult("comment_audio_model", "comment", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(Audio.class), "", ""))));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "comment", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    private static final class Framework {
        final Map<Executable, List<XposedInterface.Hooker>> hooks = new HashMap<>();
        final Set<String> ids = new HashSet<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Executable target = (Executable) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if ("setId".equals(call.getName())) {
                                    assertTrue("Duplicate hook ID: " + values[0], ids.add((String) values[0]));
                                }
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.computeIfAbsent(target, ignored -> new ArrayList<>()).add((XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> null);
                            });
                });

        Object invoke(Method target, Object receiver, Object... input) throws Throwable { return invokeAt(target, receiver, input, 0); }

        private Object invokeAt(Method target, Object receiver, Object[] input, int index) throws Throwable {
            List<XposedInterface.Hooker> entries = hooks.getOrDefault(target, Collections.emptyList());
            if (index >= entries.size()) return target.invoke(receiver, input);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed": return invokeAt(target, receiver,
                                    arguments == null || arguments.length == 0 ? input : (Object[]) arguments[0], index + 1);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return entries.get(index).intercept(chain);
        }
    }
}
