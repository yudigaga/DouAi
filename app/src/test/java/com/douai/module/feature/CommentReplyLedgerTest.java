package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CommentReplyLedgerTest {
    private SharedPreferences preferences;
    private MutableClock clock;
    private CommentReplyLedger ledger;

    @Before public void reset() {
        preferences = RuntimeEnvironment.getApplication()
                .getSharedPreferences("comment_reply_ledger_test", Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        clock = new MutableClock();
        ledger = recreate();
    }

    @Test public void baselineAndMonotonicWatermarkSurviveRecreationAndStayAccountScoped() {
        assertFalse(ledger.initialized(100));
        ledger.initialize(100, 1000);
        ledger.advance(100, 2000);
        ledger.advance(100, 1500);
        ledger.initialize(100, 3000);

        CommentReplyLedger restarted = recreate();
        assertTrue(restarted.initialized(100));
        assertEquals(1000, restarted.baseline(100));
        assertEquals(2000, restarted.watermark(100));
        assertFalse(restarted.initialized(101));
        assertEquals(0, restarted.watermark(101));
    }

    @Test public void diagnosticStatusUsesOnlyKnownLabelsAndDoesNotExposeStoredValues() {
        assertTrue(ledger.initialize(100, 1000, List.of("ignored")));
        assertEquals("ignored", ledger.diagnosticStatus(100, "ignored"));
        assertEquals("missing", ledger.diagnosticStatus(100, "missing"));
        assertTrue(ledger.claim(100, "pending", "root-pending", 0));
        assertEquals("pending", ledger.diagnosticStatus(100, "pending"));
        assertTrue(ledger.submitted(100, "pending"));
        assertEquals("submitted", ledger.diagnosticStatus(100, "pending"));
        ledger.finish(100, "pending", "unknown");
        assertEquals("unknown", ledger.diagnosticStatus(100, "pending"));
        assertTrue(ledger.claim(100, "deferred", "root-deferred", 0));
        ledger.defer(100, "deferred");
        assertEquals("deferred", ledger.diagnosticStatus(100, "deferred"));
        assertTrue(ledger.claim(100, "failed", "root-failed", 0));
        ledger.finish(100, "failed", "failed");
        assertEquals("failed", ledger.diagnosticStatus(100, "failed"));
        assertTrue(ledger.claim(100, "cancelled", "root-cancelled", 0));
        ledger.finish(100, "cancelled", "cancelled");
        assertEquals("cancelled", ledger.diagnosticStatus(100, "cancelled"));
        assertTrue(preferences.edit().putString("incoming.100.corrupt", "private-comment-data").commit());
        assertEquals("corrupt", ledger.diagnosticStatus(100, "corrupt"));
        assertNotEquals("private-comment-data", ledger.diagnosticStatus(100, "corrupt"));
    }

    @Test public void legacyInboxModelRewindsOnlyWatermarkOnceAndPreservesEveryClaim() {
        assertTrue(ledger.initialize(100, 1000, List.of("ignored")));
        for (String status : List.of("sent", "submitted", "unknown", "deferred", "failed", "cancelled")) {
            assertTrue(ledger.claim(100, status, "root-" + status, 0));
            if (List.of("sent", "submitted", "unknown").contains(status)) {
                assertTrue(ledger.submitted(100, status));
            }
            if ("deferred".equals(status)) ledger.defer(100, status);
            else if (!"submitted".equals(status)) ledger.finish(100, status, status);
        }
        ledger.advance(100, 5000);
        ledger.initialize(101, 2000);
        ledger.advance(101, 9000);
        assertTrue(preferences.edit().remove("inbox_model.100").remove("inbox_model.101").commit());
        Map<String, Object> before = storedClaims();

        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(1000, ledger.baseline(100));
        assertEquals(1000, ledger.watermark(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, preferences.getInt("inbox_model.100", 0));
        assertEquals(before, storedClaims());
        assertEquals(2000, ledger.baseline(101));
        assertEquals(9000, ledger.watermark(101));
        assertFalse(preferences.contains("inbox_model.101"));
        for (String status : List.of("sent", "submitted", "unknown", "ignored", "cancelled")) {
            assertFalse(ledger.claim(100, status, "root-" + status, 0));
            assertFalse(ledger.retryable(100, status));
        }
        assertTrue(ledger.retryable(100, "deferred"));
        assertTrue(ledger.retryable(100, "failed"));

        ledger.advance(100, 6000);
        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(6000, ledger.watermark(100));
        CommentReplyLedger restarted = recreate();
        assertTrue(restarted.ensureInboxModelVersion(100));
        assertEquals(6000, restarted.watermark(100));
        assertEquals(1000, restarted.baseline(100));
        assertEquals(before, storedClaims());
        assertTrue(restarted.ensureInboxModelVersion(101));
        assertEquals(2000, restarted.watermark(101));
        assertEquals(6000, restarted.watermark(100));
    }

    @Test public void olderInboxModelVersionRequiresTheSameOneTimeRewind() {
        seedInboxModel(100, 1000, 5000, 1);
        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(1000, ledger.watermark(100));
        assertEquals(1000, ledger.baseline(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, preferences.getInt("inbox_model.100", 0));
    }

    @Test public void versionThreeReplaysTheOriginalRangeForThreadBackfillAndKeepsClaims() {
        assertTrue(ledger.initialize(100, 1000, List.of("baseline-existing")));
        assertTrue(ledger.claim(100, "already-submitted", "root", 0));
        assertTrue(ledger.submitted(100, "already-submitted"));
        ledger.advance(100, 5000);
        assertTrue(preferences.edit().putInt("inbox_model.100", 3).commit());
        Map<String, Object> claims = storedClaims();

        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(1000, ledger.watermark(100));
        assertEquals(1000, ledger.baseline(100));
        assertEquals(4, preferences.getInt("inbox_model.100", 0));
        assertEquals(claims, storedClaims());
        assertFalse(ledger.claim(100, "already-submitted", "root", 0));
        ledger.advance(100, 6000);
        assertTrue(recreate().ensureInboxModelVersion(100));
        assertEquals(6000, recreate().watermark(100));
    }

    @Test public void versionTwoInboxModelReplaysTheOriginalBaselineOnceWithoutReleasingSubmittedClaims() {
        assertTrue(ledger.initialize(100, 1000, List.of("ignored")));
        for (String status : List.of("submitted", "unknown", "sent")) {
            assertTrue(ledger.claim(100, status, "root-" + status, 0));
            assertTrue(ledger.submitted(100, status));
            if (!"submitted".equals(status)) ledger.finish(100, status, status);
        }
        ledger.advance(100, 5000);
        assertTrue(preferences.edit().putInt("inbox_model.100", 2).commit());
        Map<String, Object> before = storedClaims();

        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(1000, ledger.baseline(100));
        assertEquals(1000, ledger.watermark(100));
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, preferences.getInt("inbox_model.100", 0));
        assertEquals(before, storedClaims());
        for (String status : List.of("submitted", "unknown", "sent", "ignored")) {
            assertFalse(ledger.claim(100, status, "root-" + status, 0));
        }

        ledger.advance(100, 6000);
        assertTrue(ledger.ensureInboxModelVersion(100));
        assertEquals(6000, ledger.watermark(100));
        CommentReplyLedger restarted = recreate();
        assertTrue(restarted.ensureInboxModelVersion(100));
        assertEquals(6000, restarted.watermark(100));
        assertEquals(1000, restarted.baseline(100));
        assertEquals(before, storedClaims());
    }

    @Test public void currentAndFutureInboxModelVersionsNeverRewindOrCommit() {
        for (int version : List.of(CommentReplyLedger.INBOX_MODEL_VERSION, CommentReplyLedger.INBOX_MODEL_VERSION + 1)) {
            seedInboxModel(100, 1000, 5000, version);
            int[] commits = {0};
            CommentReplyLedger current = new CommentReplyLedger(preferences, clock, editor -> {
                commits[0]++;
                return editor.commit();
            });
            assertTrue(current.ensureInboxModelVersion(100));
            assertEquals(5000, current.watermark(100));
            assertEquals(version, preferences.getInt("inbox_model.100", 0));
            assertEquals(0, commits[0]);
        }
    }

    @Test public void freshInboxModelVersionIsCommittedWithInitializationAndDoesNotRewindLater() {
        int[] commits = {0};
        CommentReplyLedger fresh = new CommentReplyLedger(preferences, clock, editor -> {
            commits[0]++;
            return editor.commit();
        });
        assertTrue(fresh.initialize(100, 1000, List.of("ignored")));
        assertEquals(1, commits[0]);
        assertEquals(CommentReplyLedger.INBOX_MODEL_VERSION, preferences.getInt("inbox_model.100", 0));
        fresh.advance(100, 5000);
        assertTrue(fresh.ensureInboxModelVersion(100));
        assertEquals(5000, fresh.watermark(100));
        assertEquals(2, commits[0]);
        assertTrue(recreate().ensureInboxModelVersion(100));
        assertEquals(5000, recreate().watermark(100));
    }

    @Test public void inboxModelMigrationRejectsInvalidAndUninitializedAccountsWithoutWriting() {
        assertFalse(ledger.ensureInboxModelVersion(0));
        assertFalse(ledger.ensureInboxModelVersion(-1));
        assertFalse(ledger.ensureInboxModelVersion(100));
        assertTrue(preferences.getAll().isEmpty());
    }

    @Test public void failedInboxModelMigrationNeverAuthorizesScanningOrFurtherClaims() {
        seedInboxModel(100, 1000, 5000, 1);
        CommentReplyLedger failing = new CommentReplyLedger(preferences, clock, editor -> false);
        assertFalse(failing.ensureInboxModelVersion(100));
        assertEquals(1, preferences.getInt("inbox_model.100", 0));
        assertEquals(5000, preferences.getLong("watermark.100", 0));
        assertEquals(1000, preferences.getLong("baseline.100", 0));
        assertFalse(failing.initialized(100));
        assertFalse(failing.ensureInboxModelVersion(100));
        assertFalse(failing.claim(100, "new", "root", 0));
        assertTrue(recreate().ensureInboxModelVersion(100));
        assertEquals(1000, recreate().watermark(100));
    }

    @Test public void throwingInboxModelMigrationFailsClosedWithoutAcknowledgingTheSchema() {
        seedInboxModel(100, 1000, 5000, 1);
        CommentReplyLedger failing = new CommentReplyLedger(preferences, clock, editor -> {
            throw new IllegalStateException("disk unavailable");
        });
        assertFalse(failing.ensureInboxModelVersion(100));
        assertEquals(1, preferences.getInt("inbox_model.100", 0));
        assertEquals(5000, preferences.getLong("watermark.100", 0));
        assertFalse(failing.initialized(100));
        assertFalse(failing.claim(100, "new", "root", 0));
    }

    @Test public void corruptInboxModelVersionFailsClosedWithoutRewritingOrAuthorizingScanning() {
        seedInboxModel(100, 1000, 5000, 1);
        assertTrue(preferences.edit().putString("inbox_model.100", "broken").commit());
        assertFalse(ledger.ensureInboxModelVersion(100));
        assertEquals("broken", preferences.getString("inbox_model.100", ""));
        assertEquals(5000, preferences.getLong("watermark.100", 0));
        assertFalse(ledger.initialized(100));
        assertFalse(ledger.claim(100, "new", "root", 0));
    }

    @Test public void corruptInboxModelBoundsCannotAuthorizeAReadOrExpandItsOriginalBaseline() {
        seedInboxModel(100, 1000, 5000, 1);
        assertTrue(preferences.edit().putString("baseline.100", "broken").commit());
        assertFalse(ledger.ensureInboxModelVersion(100));
        assertEquals("broken", preferences.getString("baseline.100", ""));
        assertEquals(5000, preferences.getLong("watermark.100", 0));
        assertEquals(1, preferences.getInt("inbox_model.100", 0));
        assertFalse(ledger.claim(100, "new", "root", 0));
    }

    @Test public void pendingClaimsRecoverAsImmediatelyRetryableBecauseNothingWasSubmitted() {
        assertTrue(ledger.claim(100, "incoming", "root", 3));
        assertFalse(ledger.claim(100, "incoming", "root", 3));
        CommentReplyLedger restarted = recreate();
        assertTrue(restarted.contains(100, "incoming"));
        assertTrue(restarted.retryable(100, "incoming"));
        assertTrue(restarted.claim(100, "incoming", "root", 3));
    }

    @Test public void failedPendingRecoveryFailsClosedUntilTheLedgerIsRecreatedSuccessfully() {
        ledger.initialize(100, 1000);
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        CommentReplyLedger failing = new CommentReplyLedger(preferences, clock, editor -> false);
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertFalse(failing.initialized(100));
        assertFalse(failing.claim(100, "incoming", "root", 1));
        assertFalse(failing.claim(100, "new", "root", 1));
        assertFalse(failing.submitted(100, "incoming"));
        assertTrue(recreate().retryable(100, "incoming"));
    }

    @Test public void baselineIdsArePersistedAtomicallyAndDoNotBlockNewCommentsFromTheSameSecond() {
        assertTrue(ledger.initialize(100, 1000, List.of("old-a", "old-b", "old-a")));
        assertTrue(recreate().initialized(100));
        assertTrue(ledger.contains(100, "old-a"));
        assertFalse(ledger.claim(100, "old-a", "root", 1));
        assertFalse(ledger.retryable(100, "old-a"));
        assertFalse(ledger.threadExhausted(100, "root", 1));
        assertTrue(ledger.claim(100, "new-same-second", "root", 1));
        assertTrue(ledger.initialize(100, 2000, List.of("later-existing")));
        assertEquals(1000, ledger.baseline(100));
        assertFalse(ledger.contains(100, "later-existing"));
        assertFalse(ledger.contains(101, "old-a"));
    }

    @Test public void baselineCommitFailureDoesNotAuthorizeProcessingOrForgetExistingIds() {
        CommentReplyLedger failing = new CommentReplyLedger(preferences, clock, editor -> false);
        assertFalse(failing.initialize(100, 1000, List.of("old-a")));
        assertFalse(failing.initialized(100));
        assertFalse(failing.claim(100, "new-same-second", "root", 1));
        assertFalse(failing.contains(100, "old-a"));
        assertFalse(ledger.initialize(100, 1000, List.of("")));
        assertFalse(ledger.initialized(100));
    }

    @Test public void sameIncomingIdIsIndependentAcrossAccountsAndOtherCommentsStayDistinct() {
        assertTrue(ledger.claim(100, "incoming-a", "video-root", 3));
        assertTrue(ledger.claim(101, "incoming-a", "video-root", 3));
        assertTrue(ledger.claim(100, "incoming-b", "video-root", 3));
        assertFalse(ledger.contains(100, "never-seen"));
        assertFalse(ledger.contains(102, "incoming-a"));
    }

    @Test public void submittedUnknownAndLateSuccessNeverPermitAutomaticResending() {
        assertTrue(ledger.claim(100, "incoming", "root", 3));
        assertTrue(ledger.submitted(100, "incoming"));
        assertFalse(ledger.submitted(100, "incoming"));
        assertFalse(recreate().claim(100, "incoming", "root", 3));
        ledger.finish(100, "incoming", "unknown");
        ledger.finish(100, "incoming", "failed");
        assertFalse(ledger.retryable(100, "incoming"));
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS * 2);
        assertFalse(recreate().claim(100, "incoming", "root", 3));
        ledger.finish(100, "incoming", "sent");
        ledger.finish(100, "incoming", "failed");
        assertFalse(recreate().claim(100, "incoming", "root", 3));
    }

    @Test public void definiteFailuresReleaseThreadReservationAndRetryOnlyAfterBackoff() {
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        assertTrue(ledger.submitted(100, "incoming"));
        ledger.finish(100, "incoming", "failed");
        assertTrue(ledger.retryable(100, "incoming"));
        assertFalse(ledger.claim(100, "incoming", "root", 1));
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertTrue(recreate().claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertTrue(recreate().claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertFalse(ledger.retryable(100, "incoming"));
        assertFalse(recreate().claim(100, "incoming", "root", 1));
        assertTrue(ledger.claim(100, "another", "root", 1));
    }

    @Test public void pendingSubmittedUnknownAndSuccessfulRepliesAllConsumeThreadLimit() {
        assertTrue(ledger.claim(100, "a", "root", 3));
        assertTrue(ledger.claim(100, "b", "root", 3));
        assertTrue(ledger.submitted(100, "b"));
        ledger.finish(100, "b", "unknown");
        assertTrue(ledger.claim(100, "c", "root", 3));
        assertTrue(ledger.submitted(100, "c"));
        ledger.finish(100, "c", "sent");
        assertFalse(ledger.claim(100, "d", "root", 3));
        assertTrue(ledger.claim(100, "different-root", "other-root", 3));
        assertTrue(ledger.claim(101, "d", "root", 3));
    }

    @Test public void threadExhaustionDistinguishesPendingSlotsFromSubmittedQuota() {
        assertTrue(ledger.claim(100, "a", "root", 2));
        assertTrue(ledger.claim(100, "b", "root", 2));
        assertFalse(ledger.claim(100, "c", "root", 2));
        assertFalse(ledger.threadExhausted(100, "root", 2));
        assertTrue(ledger.submitted(100, "a"));
        ledger.finish(100, "a", "sent");
        assertFalse(ledger.threadExhausted(100, "root", 2));
        assertTrue(ledger.submitted(100, "b"));
        ledger.finish(100, "b", "unknown");
        assertTrue(recreate().threadExhausted(100, "root", 2));
        assertFalse(ledger.threadExhausted(101, "root", 2));
        assertFalse(ledger.threadExhausted(100, "other-root", 2));
        assertFalse(ledger.threadExhausted(100, "root", 0));
    }

    @Test public void manualCancellationIsTerminalWithoutConsumingThreadQuota() {
        assertTrue(ledger.claim(100, "cancelled", "root", 1));
        ledger.finish(100, "cancelled", "cancelled");
        assertTrue(recreate().contains(100, "cancelled"));
        assertFalse(ledger.retryable(100, "cancelled"));
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS * 10);
        assertFalse(recreate().claim(100, "cancelled", "root", 1));
        ledger.finish(100, "cancelled", "failed");
        assertFalse(ledger.retryable(100, "cancelled"));
        assertFalse(ledger.threadExhausted(100, "root", 1));
        assertTrue(ledger.claim(100, "new", "root", 1));
    }

    @Test public void temporaryDeferralsReleaseThreadSlotsAndPreserveAllActualFailureAttempts() {
        for (int index = 0; index < 10; index++) {
            assertTrue(ledger.claim(100, "incoming", "root", 1));
            ledger.defer(100, "incoming");
            assertTrue(ledger.retryable(100, "incoming"));
            assertFalse(ledger.threadExhausted(100, "root", 1));
        }
        for (int attempt = 1; attempt <= CommentReplyLedger.MAX_ATTEMPTS; attempt++) {
            assertTrue(ledger.claim(100, "incoming", "root", 1));
            ledger.finish(100, "incoming", "failed");
            clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
            if (attempt < CommentReplyLedger.MAX_ATTEMPTS) assertTrue(ledger.retryable(100, "incoming"));
        }
        assertFalse(ledger.retryable(100, "incoming"));
        assertFalse(ledger.claim(100, "incoming", "root", 1));
    }

    @Test public void deferralCannotReleaseSubmittedUnknownSentOrManuallyCancelledRecords() {
        assertTrue(ledger.claim(100, "submitted", "root", 4));
        assertTrue(ledger.submitted(100, "submitted"));
        ledger.defer(100, "submitted");
        assertFalse(ledger.retryable(100, "submitted"));
        ledger.finish(100, "submitted", "unknown");
        ledger.defer(100, "submitted");
        assertFalse(ledger.retryable(100, "submitted"));
        ledger.finish(100, "submitted", "sent");
        ledger.defer(100, "submitted");
        assertFalse(ledger.retryable(100, "submitted"));

        assertTrue(ledger.claim(100, "cancelled", "other-root", 1));
        ledger.finish(100, "cancelled", "cancelled");
        ledger.defer(100, "cancelled");
        assertFalse(ledger.retryable(100, "cancelled"));
    }

    @Test public void repeatedPendingProcessRecoveryDoesNotSpendActualFailureAttempts() {
        for (int restart = 0; restart < 10; restart++) {
            assertTrue(ledger.claim(100, "incoming", "root", 1));
            ledger = recreate();
            assertTrue(ledger.retryable(100, "incoming"));
        }
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertFalse(ledger.claim(100, "incoming", "root", 1));
    }

    @Test public void deferringTheLastAvailableAttemptStillAllowsThatSameAttemptToResume() {
        for (int attempt = 1; attempt < CommentReplyLedger.MAX_ATTEMPTS; attempt++) {
            assertTrue(ledger.claim(100, "incoming", "root", 1));
            ledger.finish(100, "incoming", "failed");
            clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        }
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        ledger.defer(100, "incoming");
        assertTrue(recreate().retryable(100, "incoming"));
        assertTrue(ledger.claim(100, "incoming", "root", 1));
        ledger.finish(100, "incoming", "failed");
        clock.advance(CommentReplyLedger.RETRY_DELAY_MILLIS);
        assertFalse(ledger.retryable(100, "incoming"));
        assertFalse(ledger.claim(100, "incoming", "root", 1));
    }

    @Test public void definitivelyRejectedSubmissionStopsConsumingPermanentThreadQuota() {
        assertTrue(ledger.claim(100, "a", "root", 1));
        assertTrue(ledger.submitted(100, "a"));
        assertTrue(ledger.threadExhausted(100, "root", 1));
        ledger.finish(100, "a", "failed");
        assertFalse(ledger.threadExhausted(100, "root", 1));
        assertTrue(ledger.retryable(100, "a"));
        assertTrue(ledger.claim(100, "b", "root", 1));
    }

    @Test public void sharedLockStopsTwoInstancesClaimingTheSameIncomingComment() throws Exception {
        CommentReplyLedger other = recreate();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> {
                start.await(); return ledger.claim(100, "incoming", "root", 3);
            });
            Future<Boolean> second = executor.submit(() -> {
                start.await(); return other.claim(100, "incoming", "root", 3);
            });
            start.countDown();
            assertEquals(1, (first.get() ? 1 : 0) + (second.get() ? 1 : 0));
        } finally { executor.shutdownNow(); }
    }

    @Test public void failedOrThrowingPersistenceNeverAuthorizesASend() {
        CommentReplyLedger failing = new CommentReplyLedger(preferences, clock, editor -> false);
        failing.initialize(100, 1000);
        assertFalse(failing.initialized(100));
        assertFalse(failing.claim(100, "incoming", "root", 3));
        assertFalse(failing.contains(100, "incoming"));
        assertTrue(ledger.claim(100, "incoming", "root", 3));
        assertFalse(failing.submitted(100, "incoming"));

        CommentReplyLedger throwing = new CommentReplyLedger(preferences, clock, editor -> {
            throw new IllegalStateException("disk unavailable");
        });
        assertFalse(throwing.claim(100, "new", "root", 3));
        assertFalse(throwing.submitted(100, "incoming"));
    }

    @Test public void corruptRecordsBlockClaimsInsteadOfSilentlyForgettingUnknownSends() {
        preferences.edit().putString("incoming.100.corrupt", "invalid").commit();
        assertTrue(ledger.contains(100, "corrupt"));
        assertFalse(ledger.claim(100, "corrupt", "root", 3));
        assertFalse(ledger.claim(100, "other", "root", 3));
    }

    @Test public void capacityRefusesNewClaimsAndPreservesEveryUnknownRecord() {
        SharedPreferences.Editor editor = preferences.edit();
        String unknown = "{\"root\":\"root\",\"status\":\"unknown\",\"attempts\":1,\"at\":1000}";
        for (int index = 0; index < CommentReplyLedger.MAX_ENTRIES; index++) {
            editor.putString("incoming.100.old-" + index, unknown);
        }
        assertTrue(editor.commit());

        assertFalse(ledger.claim(100, "new", "new-root", 0));
        assertEquals(CommentReplyLedger.MAX_ENTRIES, preferences.getAll().size());
        assertTrue(recreate().contains(100, "old-0"));
        assertFalse(recreate().claim(100, "old-0", "root", 0));
        CommentReplyLedger.Health health = ledger.health();
        assertEquals(CommentReplyLedger.MAX_ENTRIES, health.total);
        assertEquals(CommentReplyLedger.MAX_ENTRIES, health.unknown);
        assertTrue(health.blocked());
        assertTrue(health.diagnostic().contains("容量已满"));
    }

    @Test public void onlyTheCurrentAttemptCanReturnANeverInvokedSubmissionOrChangeItsOutcome() {
        assertTrue(ledger.claim(100, "incoming", "root", 1, "first"));
        assertFalse(ledger.submitted(100, "incoming", "other"));
        assertTrue(ledger.submitted(100, "incoming", "first"));
        assertTrue(ledger.deferBeforeInvocation(100, "incoming", "first"));
        assertTrue(ledger.retryable(100, "incoming"));

        assertTrue(ledger.claim(100, "incoming", "root", 1, "second"));
        assertFalse(ledger.deferBeforeInvocation(100, "incoming", "first"));
        ledger.finish(100, "incoming", "sent", "first");
        ledger.defer(100, "incoming", "first");
        assertTrue(ledger.submitted(100, "incoming", "second"));
        ledger.finish(100, "incoming", "unknown", "second");
        assertFalse(ledger.deferBeforeInvocation(100, "incoming", "second"));
        assertFalse(recreate().claim(100, "incoming", "root", 1, "third"));
    }

    @Test public void healthSeparatesKnownUnknownCorruptAndRetryableRecordsWithoutChangingThem() {
        assertTrue(ledger.initialize(100, 1000, List.of("ignored")));
        for (String status : List.of("sent", "submitted", "unknown", "cancelled", "failed", "deferred", "pending")) {
            assertTrue(ledger.claim(100, status, "root-" + status, 0));
            if (List.of("sent", "submitted", "unknown").contains(status)) assertTrue(ledger.submitted(100, status));
            if ("deferred".equals(status)) ledger.defer(100, status);
            else if (!"submitted".equals(status) && !"pending".equals(status)) ledger.finish(100, status, status);
        }
        assertTrue(preferences.edit().putString("incoming.101.corrupt", "broken").commit());
        Map<String, ?> before = preferences.getAll();
        CommentReplyLedger.Health health = ledger.health();
        assertEquals(9, health.total);
        assertEquals(1, health.submitted);
        assertEquals(1, health.unknown);
        assertEquals(1, health.corrupt);
        assertEquals(1, health.pending);
        assertEquals(2, health.retryable);
        assertEquals(3, health.confirmed);
        assertTrue(health.blocked());
        assertFalse(health.persistenceFailed);
        assertTrue(health.diagnostic().contains("记录损坏"));
        assertEquals(before, preferences.getAll());
    }

    @Test public void fullConfirmedLedgerReportsCapacityWithoutDroppingReplayOrThreadHistory() {
        SharedPreferences.Editor editor = preferences.edit();
        String sent = "{\"root\":\"root\",\"status\":\"sent\",\"attempts\":1,\"at\":1000}";
        for (int index = 0; index < CommentReplyLedger.MAX_ENTRIES; index++) editor.putString("incoming.100.sent-" + index, sent);
        assertTrue(editor.commit());
        assertFalse(ledger.claim(100, "new", "new-root", 0));
        CommentReplyLedger.Health health = ledger.health();
        assertEquals(CommentReplyLedger.MAX_ENTRIES, health.confirmed);
        assertEquals(0, health.unknown);
        assertTrue(health.blocked());
        assertTrue(health.diagnostic().contains("清空发送历史不会释放账本"));
        assertTrue(ledger.threadExhausted(100, "root", 3));
        assertTrue(ledger.contains(100, "sent-0"));
    }

    @Test public void invalidIdentifiersAndUnclaimedSubmissionAreRejected() {
        assertFalse(ledger.claim(0, "a", "root", 3));
        assertFalse(ledger.claim(100, "", "root", 3));
        assertFalse(ledger.claim(100, "a", "", 3));
        assertFalse(ledger.claim(100, "a".repeat(513), "root", 3));
        assertFalse(ledger.submitted(100, "unclaimed"));
    }

    private CommentReplyLedger recreate() {
        return new CommentReplyLedger(preferences, clock, SharedPreferences.Editor::commit);
    }

    private void seedInboxModel(long account, long baseline, long watermark, int version) {
        assertTrue(preferences.edit().putLong("baseline." + account, baseline)
                .putLong("watermark." + account, watermark).putInt("inbox_model." + account, version).commit());
    }

    private Map<String, Object> storedClaims() {
        Map<String, Object> claims = new HashMap<>();
        for (Map.Entry<String, ?> entry : preferences.getAll().entrySet()) {
            if (entry.getKey().startsWith("incoming.")) claims.put(entry.getKey(), entry.getValue());
        }
        return claims;
    }

    private static final class MutableClock extends Clock {
        private Instant now = Instant.ofEpochMilli(1_000_000);
        void advance(long millis) { now = now.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(now, zone); }
        @Override public Instant instant() { return now; }
    }
}
