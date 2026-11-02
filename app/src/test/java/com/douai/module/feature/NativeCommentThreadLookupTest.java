package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.douai.module.feature.NativeCommentReplyInboxTest.*;
import static org.junit.Assert.*;

/** Exercises reflected host requests against independent own-comment and thread responses. */
public final class NativeCommentThreadLookupTest {
    private OwnApi own;
    private NativeCommentThreadLookup lookup;
    private final List<String> diagnostics = new ArrayList<>();

    @Before public void setUp() throws Exception {
        own = new OwnApi();
        OwnProvider.b = own;
        ThreadApi.pages.clear();
        ThreadApi.calls.clear();
        ThreadApi.duringFetch = null;
        ThreadApi.fallback = null;
        AccountFactory.service = new Account();
        AccountFactory.service.user = new User("100");
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add);
    }

    @Test public void sparseNoticeIsResolvedOnlyByItsExactReplyUnderAnOwnedOriginal() throws Exception {
        Comment original = original("root", "work");
        Comment incoming = incoming("reply", "root", "work");
        Comment sparse = incoming("reply", "0", "work");
        sparse.replyToReplyId = "0";
        OwnResponse first = ownPage(false, 0, payload(original));
        own.pages.add(first);
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming));
        NativeCommentThreadLookup.Hint hint = hint("notice", "work", "reply", "200", sparse);

        List<NativeCommentThreadLookup.Match> matches = lookup.resolve(100, Collections.singletonList(hint), () -> true);

        assertEquals(1, matches.size());
        NativeCommentThreadLookup.Match match = matches.get(0);
        assertSame(hint, match.hint);
        assertSame(incoming, match.incoming);
        assertSame(original, match.original);
        assertSame(first.items.get(0).aweme, match.work);
        assertEquals("0", sparse.replyId);
        assertEquals("0", sparse.replyToReplyId);
        Builder sent = ThreadApi.calls.get(0);
        assertEquals("root", sent.LJII);
        assertSame(original, sent.LJJII);
        assertEquals("100", sent.LJJIII);
        assertSame(match.work, sent.LIZ.work);
        assertTrue(sent.LJIIJJI > 0);
    }

    @Test public void repeatedHintReusesTheVerifiedOwnWindowButStillReadsFreshHeadAndThread() throws Exception {
        for (int round = 0; round < 2; round++) {
            own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
            for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
            ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        }
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(5, own.starts.size());
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals("A warm scan must validate the head without reading all five own pages again", 6, own.starts.size());
        assertEquals("A cached original must never replace the live reply-list request", 2, ThreadApi.calls.size());
    }

    @Test public void aNewIncomingReplyUnderTheVerifiedWorkAlsoReusesTheOwnWindow() throws Exception {
        own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("first", "root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("second", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("first"), () -> true).size());
        assertEquals(1, lookup.resolve(100, hints("second"), () -> true).size());
        assertEquals(6, own.starts.size());
        assertEquals(2, ThreadApi.calls.size());
    }

    @Test public void aHintForAnUnverifiedWorkRefreshesTheWindowImmediately() throws Exception {
        own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        for (int page = 0; page < 4; page++) own.pages.add(ownPage(true, 20 + page));
        own.pages.add(ownPage(true, 24, payload(original("new-root", "new-work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("first", "root", "work")));
        ThreadApi.enqueue("new-root", threadPage(false, 0, "", incoming("second", "new-root", "new-work")));
        assertEquals(1, lookup.resolve(100, hints("first"), () -> true).size());
        List<NativeCommentThreadLookup.Hint> newWork = Collections.singletonList(hint("notice-new", "new-work", "second", "200",
                incoming("second", "0", "new-work")));
        assertEquals(1, lookup.resolve(100, newWork, () -> true).size());
        assertEquals(10, own.starts.size());
        assertEquals(2, ThreadApi.calls.size());
    }

    @Test public void freshHeadDoesNotExtendTheOriginalWindowsSixtySecondLifetime() throws Exception {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong();
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add, clock::get);
        enqueueOwnWindow("root", "work");
        own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
        enqueueOwnWindow("root", "work");
        for (int i = 0; i < 3; i++) ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        clock.set(TimeUnit.SECONDS.toNanos(59));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(6, own.starts.size());
        clock.set(TimeUnit.SECONDS.toNanos(60));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(11, own.starts.size());
        assertTrue(diagnostics.stream().anyMatch(value -> value.contains("cache=fresh_head")));
    }

    @Test public void aPreviousConfigurationGuardCannotAuthorizeTheNextEpochsCacheHit() throws Exception {
        AtomicBoolean oldEpoch = new AtomicBoolean(true);
        enqueueOwnWindow("root", "work"); enqueueOwnWindow("root", "work");
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), oldEpoch::get).size());
        oldEpoch.set(false);
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(10, own.starts.size());
    }

    @Test public void stalledPaginationNeverMarksItsPartialOwnWindowFresh() throws Exception {
        own.fallback = ownPage(true, 0, payload(original("root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        int firstReads = own.starts.size();
        enqueueOwnWindow("root", "work");
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(firstReads + 5, own.starts.size());
    }

    @Test public void aFreshThreadMissRefreshesTheOwnWindowWithoutWaitingForCacheExpiry() throws Exception {
        enqueueOwnWindow("root", "work");
        own.pages.add(ownPage(true, 10, payload(original("root", "work"))));
        enqueueOwnWindow("new-root", "work");
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("first", "root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 0, ""));
        ThreadApi.enqueue("root", threadPage(false, 0, ""));
        ThreadApi.enqueue("new-root", threadPage(false, 0, "", incoming("second", "new-root", "work")));
        assertEquals(1, lookup.resolve(100, hints("first"), () -> true).size());
        List<NativeCommentThreadLookup.Match> matches = lookup.resolve(100, hints("second"), () -> true);
        assertEquals(1, matches.size());
        assertEquals("new-root", HostTargets.read(matches.get(0).original, "cid"));
        assertEquals(11, own.starts.size());
    }

    @Test public void freshHeadRevocationCannotBeUndoneByAnOlderWindowPage() throws Exception {
        enqueueOwnWindow("root", "work");
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        CommentNotice hidden = payload(original("root", "work")); hidden.commentUnvisible = 1;
        own.pages.add(ownPage(true, 10, hidden));
        own.pages.add(ownPage(true, 11, payload(original("root", "work"))));
        for (int page = 2; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        int reads = ThreadApi.calls.size();
        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        assertEquals(reads, ThreadApi.calls.size());
    }

    @Test public void freshHeadRevocationSurvivesTheSameResolvesCacheMissRefresh() throws Exception {
        own.pages.add(ownPage(true, 10, payload(original("root-a", "work")), payload(original("root-b", "work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        ThreadApi.enqueue("root-a", threadPage(false, 0, "", incoming("first", "root-a", "work")));
        assertEquals(1, lookup.resolve(100, hints("first"), () -> true).size());

        CommentNotice hidden = payload(original("root-a", "work")); hidden.commentUnvisible = 1;
        own.pages.add(ownPage(true, 10, hidden, payload(original("root-b", "work"))));
        // The miss refresh no longer includes A at its head, but an older page repeats A.
        own.pages.add(ownPage(true, 10, payload(original("root-b", "work"))));
        own.pages.add(ownPage(true, 11, payload(original("root-a", "work"))));
        for (int page = 2; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        ThreadApi.enqueue("root-b", threadPage(false, 0, ""));
        ThreadApi.enqueue("root-b", threadPage(false, 0, ""));
        ThreadApi.enqueue("root-a", threadPage(false, 0, "", incoming("second", "root-a", "work")));

        assertTrue("A full refresh within the same resolve must retain the fresh head's revocation",
                lookup.resolve(100, hints("second"), () -> true).isEmpty());
        assertEquals(1, ThreadApi.calls.stream().filter(call -> "root-a".equals(call.LJII)).count());
        own.pages.add(ownPage(false, 0, payload(original("root-a", "work"))));
        ThreadApi.enqueue("root-b", threadPage(false, 0, ""));
        assertEquals("A later independent resolve may accept a freshly visible original again", 1,
                lookup.resolve(100, hints("second"), () -> true).size());
    }

    private void enqueueOwnWindow(String root, String work) {
        own.pages.add(ownPage(true, 10, payload(original(root, work))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
    }

    @Test public void obfuscatedHostTaskCompletesAReplyReadWithoutStandardBoltsMethodNames() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        Task task = new Task(threadPage(false, 0, "", incoming("reply", "root", "work")));
        ThreadApi.enqueueTask("root", task);

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(1, task.waitCalls);
        assertTrue("Object.wait(0) would wait indefinitely", task.lastWaitMillis > 0);
        assertTrue("The host task must remain responsive to cancellation", task.lastWaitMillis <= 500);
    }

    @Test public void taskErrorRuleWithAnObjectReturnIsRejectedBeforeAnyRead() throws Exception {
        HostScanReport invalid = reportWithTaskMethod("comment_reply_task_error", Task.class.getMethod("getResult"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new NativeCommentThreadLookup(invalid, getClass().getClassLoader(), diagnostics::add));

        assertEquals("thread_lookup_contract", failure.getMessage());
        assertTrue(own.starts.isEmpty());
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void taskWaitRuleWithoutItsTimeoutArgumentsIsRejectedBeforeAnyRead() throws Exception {
        HostScanReport invalid = reportWithTaskMethod("comment_reply_task_wait", Task.class.getMethod("isCompleted"));

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> new NativeCommentThreadLookup(invalid, getClass().getClassLoader(), diagnostics::add));

        assertEquals("thread_lookup_contract", failure.getMessage());
        assertTrue(own.starts.isEmpty());
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void pendingHostTaskDoesNotReadItsResultBeforeCompletion() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        try (PendingLookup call = pendingLookup(valid)) {
            assertEquals(0, call.task.resultReads);
            assertNull(call.matches.get());

            call.task.finish();
            call.awaitEnd();

            assertNull(call.failure.get());
            assertEquals(1, call.matches.get().size());
            assertEquals(1, call.task.resultReads);
        }
    }

    @Test public void aHostTaskWakeupBeforeCompletionRetriesInsteadOfReadingAnUnfinishedResult() throws Exception {
        try (PendingLookup call = pendingLookup(new AtomicBoolean(true))) {
            call.task.wakeWithoutCompletion();
            assertTrue("The native wait can return false before completion",
                    call.task.twoWaits.await(5, TimeUnit.SECONDS));
            assertEquals(0, call.task.resultReads);

            call.task.finish();
            call.awaitEnd();

            assertNull(call.failure.get());
            assertEquals(1, call.matches.get().size());
            assertTrue(call.task.waitCalls >= 2);
        }
    }

    @Test public void configurationInvalidationWhileWaitingForAHostTaskAbortsWithoutReadingItsResult() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        try (PendingLookup call = pendingLookup(valid)) {
            valid.set(false);
            call.task.wakeWithoutCompletion();
            call.awaitEnd();

            assertTrue(call.failure.get() instanceof CancellationException);
            assertNull(call.matches.get());
            assertEquals(0, call.task.resultReads);
        }
    }

    @Test public void closingLookupWhileWaitingForAHostTaskAbortsWithoutReadingItsResult() throws Exception {
        try (PendingLookup call = pendingLookup(new AtomicBoolean(true))) {
            lookup.close();
            call.awaitEnd();

            assertTrue(call.failure.get() instanceof CancellationException);
            assertNull(call.matches.get());
            assertEquals(0, call.task.resultReads);
        }
    }

    @Test public void interruptingAHostTaskWaitRetainsTheWorkersInterruptFlag() throws Exception {
        try (PendingLookup call = pendingLookup(new AtomicBoolean(true))) {
            call.worker.interrupt();
            call.awaitEnd();

            Throwable failure = call.failure.get();
            assertNotNull(failure);
            while (failure.getCause() != null) failure = failure.getCause();
            assertTrue(failure instanceof InterruptedException);
            assertTrue(call.interruptedAfterFailure.get());
            assertNull(call.matches.get());
            assertEquals(0, call.task.resultReads);
        }
    }

    @Test public void configurationInvalidationWhileReadingACompletedTaskDiscardsItsResult() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        Task task = new Task(threadPage(false, 0, "", incoming("reply", "root", "work")));
        task.duringResult = () -> valid.set(false);
        ThreadApi.enqueueTask("root", task);

        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), valid::get));
        assertEquals(1, task.resultReads);
    }

    @Test public void aFaultedHostTaskUsesItsObfuscatedErrorAccessorAndPreservesTheFailure() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        Task task = new Task(threadPage(false, 0, "", incoming("reply", "root", "work")));
        task.error = new IllegalStateException("fixture native task failure");
        ThreadApi.enqueueTask("root", task);

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> lookup.resolve(100, hints("reply"), () -> true));

        assertSame(task.error, failure.getCause());
        assertTrue(task.errorObserved);
        assertEquals(0, task.resultReads);
    }

    @Test public void aCancelledHostTaskCannotExposeItsStoredResult() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        Task task = new Task(threadPage(false, 0, "", incoming("reply", "root", "work")));
        task.cancelled = true;
        ThreadApi.enqueueTask("root", task);

        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), () -> true));
        assertEquals(0, task.resultReads);
    }

    @Test public void effectiveAuthorGetterCanProveAnOriginalWhoseRawUserIsAbsent() throws Exception {
        Comment original = original("root", "work");
        original.commentUser = original.user;
        original.user = null;
        own.pages.add(ownPage(false, 0, payload(original)));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));

        List<NativeCommentThreadLookup.Match> matches = lookup.resolve(100, hints("reply"), () -> true);

        assertEquals(1, matches.size());
        assertSame(original, matches.get(0).original);
    }

    @Test public void aReplyToAnotherChildInTheSameOwnedRootIsNotADirectReplyToMe() throws Exception {
        Comment value = incoming("reply", "root", "work");
        value.replyToReplyId = "another-child";
        value.replyToUserId = "300";
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", value));

        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
    }

    @Test public void directRootReferenceMayProveTheRecipientWhenTheOptionalRecipientIsAbsent() throws Exception {
        Comment value = incoming("reply", "root", "work");
        value.replyToUserId = null;
        value.replyToReplyId = "0";
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", value));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
    }

    @Test public void cidSenderWorkAndExplicitRecipientMustAllAgreeWithTheHint() throws Exception {
        for (int mutation = 0; mutation < 5; mutation++) {
            own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
            Comment value = incoming("reply", "root", "work");
            switch (mutation) {
                case 0: value.cid = "unrelated-reply"; break;
                case 1: value.user = new User("201"); break;
                case 2: value.awemeId = "another-work"; break;
                case 3: value.replyToUserId = "300"; break;
                case 4: value.replyId = "another-root"; break;
                default: throw new AssertionError();
            }
            ThreadApi.enqueue("root", threadPage(false, 0, "", value));
            assertTrue("Mutation " + mutation + " must not be admitted",
                    lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        }
    }

    @Test public void hiddenForeignBlankAndNonRootOriginalsNeverAuthorizeAThreadRequest() throws Exception {
        for (int mutation = 0; mutation < 7; mutation++) {
            Comment original = original("root", "work");
            CommentNotice payload = payload(original);
            switch (mutation) {
                case 0: original.user = new User("300"); break;
                case 1: original.user = null; break;
                case 2: original.text = "  "; break;
                case 3: payload.commentUnvisible = 1; break;
                case 4: original.replyId = "parent"; break;
                case 5: original.replyToReplyId = "parent"; break;
                case 6: original.cid = "0"; break;
                default: throw new AssertionError();
            }
            own.pages.add(ownPage(false, 0, payload));
            int before = ThreadApi.calls.size();
            assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
            assertEquals("Unproven original must not reach native thread fetch", before, ThreadApi.calls.size());
        }
    }

    @Test public void matchingIdsCannotAuthorizeSelfMessagesOrMissingIncomingAuthors() throws Exception {
        for (int mutation = 0; mutation < 2; mutation++) {
            own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
            Comment value = incoming("reply", "root", "work");
            if (mutation == 0) value.user = new User("100");
            if (mutation == 1) value.user = null;
            ThreadApi.enqueue("root", threadPage(false, 0, "", value));
            assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        }
    }

    @Test public void conflictingOriginalAndPayloadWorksAreRejectedBeforeThreadFetch() throws Exception {
        Comment original = original("root", "another-work");
        CommentNotice payload = payload(original);
        payload.aweme.aid = "work";
        own.pages.add(ownPage(false, 0, payload));

        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void ownPaginationUsesTheReturnedMinTimeAsTheNextStartTime() throws Exception {
        own.pages.add(ownPage(true, 70, payload(original("irrelevant", "other-work"))));
        own.pages.add(ownPage(false, 40, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(2, own.starts.size());
        assertEquals(Long.valueOf(0), own.starts.get(0));
        assertEquals(Long.valueOf(70), own.starts.get(1));
        assertTrue(own.counts.get(0) > 0);
    }

    @Test public void historicalOwnCommentPagesResumeAcrossLookupRestartWithoutCrossingAccounts() throws Exception {
        Map<Long, Long> persisted = new HashMap<>();
        NativeCommentThreadLookup.OwnCursorStore cursorStore = new NativeCommentThreadLookup.OwnCursorStore() {
            @Override public long read(long account) { return persisted.getOrDefault(account, 0L); }
            @Override public boolean write(long account, long cursor) { persisted.put(account, cursor); return true; }
        };
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add, cursorStore);
        own.pages.add(ownPage(true, 10, payload(original("head", "other-work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));

        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        assertEquals(Long.valueOf(14), Long.valueOf(persisted.get(100L)));
        assertEquals(Arrays.asList(0L, 10L, 11L, 12L, 13L), own.starts);

        lookup.close();
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add, cursorStore);
        own.pages.add(ownPage(true, 10, payload(original("head", "other-work"))));
        own.pages.add(ownPage(true, 15));
        own.pages.add(ownPage(true, 16));
        own.pages.add(ownPage(true, 17));
        own.pages.add(ownPage(true, 18, payload(original("historical-root", "work"))));
        ThreadApi.enqueue("historical-root", threadPage(false, 0, "", incoming("reply", "historical-root", "work")));

        assertEquals("The old original becomes eligible when its notification work is reached", 1,
                lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(Arrays.asList(0L, 10L, 11L, 12L, 13L, 0L, 14L, 15L, 16L, 17L), own.starts);
        assertEquals(Long.valueOf(18), Long.valueOf(persisted.get(100L)));

        own.pages.add(ownPage(true, 10, payload(original("other-account-head", "other-work"))));
        for (int page = 1; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        Comment otherAccountTarget = incoming("other-account-reply", "0", "other-work");
        otherAccountTarget.replyToUserId = "101";
        assertTrue(lookup.resolve(101, Collections.singletonList(hint("other-account-notice", "other-work",
                "other-account-reply", "200", otherAccountTarget)), () -> true).isEmpty());
        assertEquals(Long.valueOf(14), Long.valueOf(persisted.get(101L)));
        assertEquals(Long.valueOf(18), Long.valueOf(persisted.get(100L)));
    }

    @Test public void stalledOwnPaginationDoesNotAdvanceTheDurableHistoryCursor() throws Exception {
        Map<Long, Long> persisted = new HashMap<>();
        persisted.put(100L, 15L);
        NativeCommentThreadLookup.OwnCursorStore cursorStore = new NativeCommentThreadLookup.OwnCursorStore() {
            @Override public long read(long account) { return persisted.getOrDefault(account, 0L); }
            @Override public boolean write(long account, long cursor) { persisted.put(account, cursor); return true; }
        };
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add, cursorStore);
        own.fallback = ownPage(true, 15, payload(original("root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals("A repeated min cursor cannot mark an incomplete historical range as complete", Long.valueOf(15),
                Long.valueOf(persisted.get(100L)));
    }

    @Test public void exhaustedHistoricalCursorWrapsToTheCurrentHeadForTheNextCycle() throws Exception {
        Map<Long, Long> persisted = new HashMap<>();
        persisted.put(100L, 20L);
        NativeCommentThreadLookup.OwnCursorStore cursorStore = new NativeCommentThreadLookup.OwnCursorStore() {
            @Override public long read(long account) { return persisted.getOrDefault(account, 0L); }
            @Override public boolean write(long account, long cursor) { persisted.put(account, cursor); return true; }
        };
        lookup = new NativeCommentThreadLookup(report(), getClass().getClassLoader(), diagnostics::add, cursorStore);
        own.pages.add(ownPage(true, 10, payload(original("head", "other-work"))));
        own.pages.add(ownPage(false, 0));

        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        assertEquals(Long.valueOf(0), Long.valueOf(persisted.get(100L)));
        assertEquals(Arrays.asList(0L, 20L), own.starts);

        for (int page = 0; page < 5; page++) own.pages.add(ownPage(true, 10 + page));
        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
        assertEquals(Arrays.asList(0L, 20L, 0L, 10L, 11L, 12L, 13L), own.starts);
        assertEquals(Long.valueOf(14), Long.valueOf(persisted.get(100L)));
    }

    @Test public void threadPaginationPreservesBothNativeCursorForms() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(true, 37, "native-merge", incoming("unrelated", "root", "work")));
        ThreadApi.enqueue("root", threadPage(false, 80, "", incoming("reply", "root", "work")));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(2, ThreadApi.calls.size());
        assertEquals(Long.valueOf(0), ThreadApi.calls.get(0).LJIIIZ);
        assertEquals(Long.valueOf(37), ThreadApi.calls.get(1).LJIIIZ);
        assertEquals("native-merge", ThreadApi.calls.get(1).LJIIJ);
    }

    @Test public void multipleHintsForTheSameOriginalShareOneThreadRead() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("first", "root", "work"), incoming("second", "root", "work")));

        List<NativeCommentThreadLookup.Match> matches = lookup.resolve(100, Arrays.asList(
                hint("n1", "work", "first", "200", incoming("first", "0", "work")),
                hint("n2", "work", "second", "200", incoming("second", "0", "work"))), () -> true);

        assertEquals(2, matches.size());
        assertEquals(1, ThreadApi.calls.size());
    }

    @Test public void separateOriginalsAreReadOnlyForWorksWithPendingHints() throws Exception {
        CommentNotice a = payload(original("a", "wa"));
        CommentNotice b = payload(original("b", "wb"));
        CommentNotice c = payload(original("c", "wc"));
        CommentNotice unrelated = payload(original("unrelated", "wd"));
        own.pages.add(ownPage(false, 0, unrelated, a, b, c));
        ThreadApi.enqueue("a", threadPage(false, 0, "", incoming("ra", "a", "wa")));
        ThreadApi.enqueue("b", threadPage(false, 0, "", incoming("rb", "b", "wb")));
        ThreadApi.enqueue("c", threadPage(false, 0, "", incoming("rc", "c", "wc")));

        List<NativeCommentThreadLookup.Match> matches = lookup.resolve(100, Arrays.asList(
                hint("na", "wa", "ra", "200", incoming("ra", "0", "wa")),
                hint("nb", "wb", "rb", "200", incoming("rb", "0", "wb")),
                hint("nc", "wc", "rc", "200", incoming("rc", "0", "wc"))), () -> true);

        assertEquals(3, matches.size());
        assertEquals(3, ThreadApi.calls.size());
        for (Builder sent : ThreadApi.calls) assertNotEquals("unrelated", sent.LJII);
    }

    @Test public void repeatedOwnCursorCannotLoopIndefinitely() throws Exception {
        own.fallback = ownPage(true, 0, payload(original("foreign-work", "other-work")));

        lookup.resolve(100, hints("reply"), () -> true);

        assertTrue("No-progress own pagination must remain bounded", own.starts.size() <= 6);
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void repeatedThreadCursorCannotLoopIndefinitely() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.fallback = threadPage(true, 0, "", incoming("unrelated", "root", "work"));

        assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());

        assertTrue("No-progress child pagination must remain bounded", ThreadApi.calls.size() <= 10);
    }

    @Test public void cancellationBeforeOrDuringOwnFetchNeverProducesAMatch() throws Exception {
        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), () -> false));
        assertTrue(own.starts.isEmpty());
        AtomicBoolean valid = new AtomicBoolean(true);
        own.duringFetch = () -> valid.set(false);
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));

        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), valid::get));
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void accountOrConfigurationInvalidationDuringThreadFetchDiscardsTheMatch() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        ThreadApi.duringFetch = () -> valid.set(false);

        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), valid::get));
    }

    @Test public void switchingAccountsCannotReuseAnEarlierAccountsOriginals() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        own.pages.add(ownPage(false, 0));
        int reads = ThreadApi.calls.size();

        assertTrue(lookup.resolve(101, hints("reply"), () -> true).isEmpty());
        assertEquals(reads, ThreadApi.calls.size());
    }

    @Test public void explicitHiddenForeignAuthorAndNonrootEvidenceInvalidateCachedAuthorization() throws Exception {
        for (int mutation = 0; mutation < 3; mutation++) {
            own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
            ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));
            assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
            CommentNotice invalid = payload(original("root", "work"));
            if (mutation == 0) invalid.commentUnvisible = 1;
            if (mutation == 1) invalid.comment.user = new User("300");
            if (mutation == 2) invalid.comment.replyId = "another-root";
            own.pages.add(ownPage(false, 0, invalid));
            int reads = ThreadApi.calls.size();

            assertTrue(lookup.resolve(100, hints("reply"), () -> true).isEmpty());
            assertEquals("Explicit fresh contrary evidence must invalidate cached authorization", reads, ThreadApi.calls.size());
        }
    }

    @Test public void emptyOwnPageWithAnAdvancingMinTimeStillReachesTheOriginal() throws Exception {
        own.pages.add(ownPage(true, 70));
        own.pages.add(ownPage(false, 40, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(false, 0, "", incoming("reply", "root", "work")));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(Arrays.asList(0L, 70L), own.starts);
    }

    @Test public void emptyThreadPageWithAdvancingNativeCursorsStillReachesTheReply() throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        ThreadApi.enqueue("root", threadPage(true, 37, "native-empty-merge"));
        ThreadApi.enqueue("root", threadPage(false, 80, "", incoming("reply", "root", "work")));

        assertEquals(1, lookup.resolve(100, hints("reply"), () -> true).size());
        assertEquals(2, ThreadApi.calls.size());
        assertEquals(Long.valueOf(37), ThreadApi.calls.get(1).LJIIIZ);
        assertEquals("native-empty-merge", ThreadApi.calls.get(1).LJIIJ);
    }

    @Test public void failedOrCancelledOwnFutureCannotBeTreatedAsAnEmptySuccessfulRead() throws Exception {
        own.nextFuture = CompletableFuture.failedFuture(new IllegalStateException("fixture own failure"));
        assertThrows(Exception.class, () -> lookup.resolve(100, hints("reply"), () -> true));
        CompletableFuture<OwnResponse> cancelled = new CompletableFuture<>();
        cancelled.cancel(true);
        own.nextFuture = cancelled;
        assertThrows(CancellationException.class, () -> lookup.resolve(100, hints("reply"), () -> true));
        assertTrue(ThreadApi.calls.isEmpty());
    }

    @Test public void faultedCancelledAndNonzeroStatusThreadResultsNeverReturnPartialMatches() throws Exception {
        for (int mutation = 0; mutation < 3; mutation++) {
            own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
            ThreadResponse response = threadPage(false, 0, "", incoming("reply", "root", "work"));
            Task task = new Task(response);
            if (mutation == 0) task.error = new IllegalStateException("fixture task failure");
            if (mutation == 1) task.cancelled = true;
            if (mutation == 2) response.status_code = 1;
            ThreadApi.enqueueTask("root", task);
            assertThrows(Exception.class, () -> lookup.resolve(100, hints("reply"), () -> true));
        }
    }

    private static List<NativeCommentThreadLookup.Hint> hints(String id) {
        return Collections.singletonList(hint("notice", "work", id, "200", incoming(id, "0", "work")));
    }

    private PendingLookup pendingLookup(AtomicBoolean valid) throws Exception {
        own.pages.add(ownPage(false, 0, payload(original("root", "work"))));
        Task task = new Task(threadPage(false, 0, "", incoming("reply", "root", "work")));
        task.completed = false;
        ThreadApi.enqueueTask("root", task);
        PendingLookup call = new PendingLookup(lookup, task, valid::get);
        if (!task.firstWait.await(5, TimeUnit.SECONDS)) {
            call.close();
            fail("The host task was not awaited: " + call.failure.get());
        }
        return call;
    }

    private static final class PendingLookup implements AutoCloseable {
        final Task task;
        final Thread worker;
        final AtomicReference<List<NativeCommentThreadLookup.Match>> matches = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final AtomicBoolean interruptedAfterFailure = new AtomicBoolean();
        PendingLookup(NativeCommentThreadLookup lookup, Task task, BooleanSupplier valid) {
            this.task = task;
            worker = new Thread(() -> {
                try { matches.set(lookup.resolve(100, hints("reply"), valid)); }
                catch (Throwable thrown) { failure.set(thrown); }
                finally { interruptedAfterFailure.set(Thread.currentThread().isInterrupted()); }
            }, "native-comment-thread-lookup-test");
            worker.setDaemon(true);
            worker.start();
        }
        void awaitEnd() throws InterruptedException {
            worker.join(5000);
            assertFalse("Lookup must stop after completion or cancellation", worker.isAlive());
        }
        @Override public void close() throws InterruptedException {
            task.finish();
            worker.interrupt();
            worker.join(5000);
            assertFalse("Fixture worker must not outlive its test", worker.isAlive());
        }
    }

    private static NativeCommentThreadLookup.Hint hint(String nid, String aid, String id, String sender, Comment target) {
        return new NativeCommentThreadLookup.Hint(nid, aid, id, sender, 210, target);
    }

    private static Comment original(String id, String aid) {
        Comment value = new Comment();
        value.cid = id; value.text = "my original"; value.user = new User("100");
        value.awemeId = aid; value.replyId = "0"; value.replyToReplyId = "0"; value.createTime = 100;
        return value;
    }

    private static Comment incoming(String id, String root, String aid) {
        Comment value = new Comment();
        value.cid = id; value.text = "incoming reply"; value.user = new User("200");
        value.awemeId = aid; value.replyId = root; value.replyToReplyId = "0";
        value.replyToUserId = "100"; value.createTime = 210;
        return value;
    }

    private static CommentNotice payload(Comment original) {
        CommentNotice value = new CommentNotice();
        value.comment = original; value.aweme = new Work(); value.aweme.aid = original.awemeId;
        return value;
    }

    private static OwnResponse ownPage(boolean more, long min, CommentNotice... items) {
        OwnResponse value = new OwnResponse(); value.items = Arrays.asList(items);
        value.hasMore = more; value.minTime = min; value.maxTime = 300; return value;
    }

    private static ThreadResponse threadPage(boolean more, long cursor, String merge, Comment... items) {
        ThreadResponse value = new ThreadResponse(); value.items = Arrays.asList(items);
        value.hasMore = more; value.cursor = cursor; value.mergeCursor = merge; return value;
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>(NativeCommentReplyInboxTest.report().getRuleResults());
        add(rules, "comment_reply_own_fetch", OwnFetch.class, OwnFetch.class.getMethod("getMineComment", long.class, int.class));
        add(rules, "comment_reply_own_provider", OwnProvider.class, null);
        add(rules, "comment_reply_own_response", OwnResponse.class, null);
        add(rules, "comment_reply_thread_fetch", ThreadApi.class, ThreadApi.class.getMethod("fetch", Request.class));
        add(rules, "comment_reply_task_wait", Task.class, Task.class.getMethod("LJIJJ", long.class, TimeUnit.class));
        add(rules, "comment_reply_task_result", Task.class, Task.class.getMethod("getResult"));
        add(rules, "comment_reply_task_faulted", Task.class, Task.class.getMethod("isFaulted"));
        add(rules, "comment_reply_task_error", Task.class, Task.class.getMethod("LJIIL"));
        add(rules, "comment_reply_task_cancelled", Task.class, Task.class.getMethod("isCancelled"));
        add(rules, "comment_reply_thread_builder", Builder.class, null);
        add(rules, "comment_reply_thread_request", Request.class, null);
        add(rules, "comment_reply_thread_response", ThreadResponse.class, null);
        add(rules, "auto_comment_work_context", ContextFactory.class, ContextFactory.class.getMethod("fromWork", Work.class));
        add(rules, "portrait_comment_aweme_resolver", ContextFactory.class, ContextFactory.class.getMethod("getWork", Context.class));
        add(rules, "comment_reply_comment_user", Comment.class, Comment.class.getMethod("getEffectiveUser"));
        return new HostScanReport("fake-thread-lookup", rules, false);
    }

    private static HostScanReport reportWithTaskMethod(String rule, Method method) throws Exception {
        List<RuleScanResult> rules = new ArrayList<>(report().getRuleResults());
        rules.removeIf(result -> rule.equals(result.getRuleId()));
        add(rules, rule, Task.class, method);
        return new HostScanReport("fake-thread-lookup-invalid-task", rules, false);
    }

    /** Supplies real lookup reflection to inbox/hook/publisher integration tests without test lifecycle calls. */
    static LookupHarness harness(String root, String incomingId, long originalTime) throws Exception {
        OwnApi own = new OwnApi();
        OwnProvider.b = own;
        ThreadApi.pages.clear();
        ThreadApi.calls.clear();
        ThreadApi.duringFetch = null;
        Comment original = original(root, "work");
        original.createTime = originalTime;
        Work work = new LookupWork();
        CommentNotice payload = payload(original);
        payload.aweme = work;
        own.fallback = ownPage(false, 0, payload);
        Comment incoming = incoming(incomingId, root, "work");
        ThreadApi.fallback = threadPage(false, 0, "", incoming);
        NativeCommentThreadLookup lookup = new NativeCommentThreadLookup(report(),
                NativeCommentThreadLookupTest.class.getClassLoader(), ignored -> { });
        return new LookupHarness(lookup, own, original, work, incoming);
    }

    static final class LookupHarness {
        final NativeCommentThreadLookup lookup;
        final OwnApi own;
        final Comment original, incoming;
        final Work work;
        LookupHarness(NativeCommentThreadLookup lookup, OwnApi own, Comment original, Work work, Comment incoming) {
            this.lookup = lookup; this.own = own; this.original = original; this.work = work; this.incoming = incoming;
        }
    }

    public static final class LookupWork extends Work {
        public String getAid() { return aid; }
    }

    private static void add(List<RuleScanResult> rules, String id, Class<?> type, Method method) {
        rules.add(new RuleScanResult(id, "comment_reply", Collections.singletonList(new HostCandidate(
                "fixture", "classes.dex", HostTargets.descriptor(type), method == null ? "" : method.getName(),
                method == null ? "" : HostTargets.descriptor(method)))));
    }

    public interface OwnFetch { Observable getMineComment(long start, int count); }
    public static final class OwnProvider { public static OwnFetch b; }
    public static final class Observable {
        private final Future<OwnResponse> future;
        Observable(Future<OwnResponse> future) { this.future = future; }
        public Future<OwnResponse> toFuture() { return future; }
    }
    public static final class OwnApi implements OwnFetch {
        final Queue<OwnResponse> pages = new ArrayDeque<>();
        final List<Long> starts = new ArrayList<>();
        final List<Integer> counts = new ArrayList<>();
        Future<OwnResponse> nextFuture;
        OwnResponse fallback;
        Runnable duringFetch;
        public Observable getMineComment(long start, int count) {
            starts.add(start); counts.add(count);
            Runnable callback = duringFetch; duringFetch = null;
            if (callback != null) callback.run();
            Future<OwnResponse> future = nextFuture; nextFuture = null;
            if (future != null) return new Observable(future);
            OwnResponse response = pages.isEmpty() ? fallback : pages.remove();
            if (response == null) throw new IllegalStateException("Unexpected own-comment read");
            return new Observable(CompletableFuture.completedFuture(response));
        }
    }
    public static final class OwnResponse extends BaseResponse {
        public List<CommentNotice> items;
        public boolean hasMore;
        public long minTime, maxTime;
    }
    public static final class Context { final Work work; Context(Work work) { this.work = work; } }
    public static final class ContextFactory {
        public static Context fromWork(Work work) { return new Context(work); }
        public static Work getWork(Context context) { return context.work; }
    }
    public static final class Builder {
        public Context LIZ;
        public String LJII, LJIIJ, LJJIII;
        public Long LJIIIZ;
        public Integer LJIIJJI;
        public Comment LJJII;
        public Builder() { }
    }
    public static final class Request {
        public final Builder builder;
        public Request(Builder builder) { this.builder = builder; }
    }
    public static final class ThreadResponse extends BaseResponse {
        public List<Comment> items;
        public boolean hasMore;
        public long cursor;
        public String mergeCursor;
    }
    public static final class ThreadApi {
        static final Map<String, Queue<Task>> pages = new LinkedHashMap<>();
        static final List<Builder> calls = new ArrayList<>();
        static Runnable duringFetch;
        static ThreadResponse fallback;
        static void enqueue(String root, ThreadResponse response) { enqueueTask(root, new Task(response)); }
        static void enqueueTask(String root, Task task) { pages.computeIfAbsent(root, ignored -> new ArrayDeque<>()).add(task); }
        public static Task fetch(Request request) {
            calls.add(request.builder);
            Runnable callback = duringFetch; duringFetch = null;
            if (callback != null) callback.run();
            Queue<Task> queued = pages.get(request.builder.LJII);
            if (queued != null && !queued.isEmpty()) return queued.remove();
            if (fallback != null) return new Task(fallback);
            throw new IllegalStateException("Unexpected child-thread read");
        }
    }
    public static final class Task {
        private final Object lock = new Object();
        private final CountDownLatch firstWait = new CountDownLatch(1);
        private final CountDownLatch twoWaits = new CountDownLatch(2);
        final ThreadResponse result;
        Exception error;
        boolean cancelled;
        boolean completed = true;
        boolean errorObserved;
        Runnable duringResult;
        volatile int waitCalls, resultReads;
        volatile long lastWaitMillis;
        Task(ThreadResponse result) { this.result = result; }
        /** The host waits once in milliseconds and can return false after a wakeup. */
        public boolean LJIJJ(long timeout, TimeUnit unit) throws InterruptedException {
            synchronized (lock) {
                waitCalls++;
                lastWaitMillis = unit.toMillis(timeout);
                assertTrue("Object.wait(0) would wait indefinitely", lastWaitMillis > 0);
                firstWait.countDown();
                twoWaits.countDown();
                if (!isCompleted()) lock.wait(lastWaitMillis);
                return isCompleted();
            }
        }
        public Object getResult() {
            synchronized (lock) {
                resultReads++;
                if (duringResult != null) duringResult.run();
                return result;
            }
        }
        public boolean isFaulted() { return LJIIL() != null; }
        public Exception LJIIL() { synchronized (lock) { errorObserved = true; return error; } }
        public boolean isCancelled() { synchronized (lock) { return cancelled; } }
        public boolean isCompleted() { synchronized (lock) { return completed; } }
        private void finish() { synchronized (lock) { completed = true; lock.notifyAll(); } }
        private void wakeWithoutCompletion() { synchronized (lock) { lock.notifyAll(); } }
    }
}
