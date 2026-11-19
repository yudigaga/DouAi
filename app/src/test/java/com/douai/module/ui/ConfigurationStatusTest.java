package com.douai.module.ui;

import android.os.Bundle;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigurationStatusTest {
    private static final String READ = "configuration.read_status";
    private static final String REVISION = "configuration.revision";
    private static final String CLOSED = "configuration.closed";
    private static final String SUMMARY = "configuration.summary";
    private static final String PRIVATE = "private-endpoint-secret-payload";

    @Test public void nullAndMissingReadStateAreUnavailableWithoutStartingPolling() {
        unavailable(ConfigurationStatus.fromDiagnostics(null));
        unavailable(ConfigurationStatus.fromDiagnostics(new Bundle()));
        unavailable(ConfigurationStatus.unavailable());
    }

    @Test public void readStateRequiresAKnownStringToken() {
        Bundle data = applied();
        data.putString(READ, "FUTURE_READY");
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data.putBoolean(READ, true);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void notReadAndDeferredDoNotInheritAnOlderSuccessfulSnapshot() {
        Bundle data = applied();
        data.putString(READ, "NOT_READ");
        status(data, "最近配置尚未读取", false, true);
        data.putString(READ, "DEFERRED");
        status(data, "最近配置等待导入完成", false, true);
    }

    @Test public void readFailureMasksOldSuccessAndKeepsFailureDetailsPrivate() {
        Bundle data = applied();
        data.putString(READ, "FAILED");
        data.putString("configuration.read_failure_class", PRIVATE);
        ConfigurationStatus result = status(data, "最近配置读取失败", false, false);
        assertTrue(result.detail.contains("上一次配置"));
        assertFalse(result.detail.contains(PRIVATE));
    }

    @Test public void closedPublicationCannotReuseAnOldSuccessOrKeepPolling() {
        Bundle data = applied();
        data.putBoolean(CLOSED, true);
        status(data, "最近配置应用已停止", false, false);
        data.putString(READ, "DEFERRED");
        status(data, "最近配置应用已停止", false, false);
    }

    @Test public void readyNeedsLedgerFieldsAndNonemptyAcknowledgedNodes() {
        Bundle empty = ledger("SUCCESS", 4);
        unavailable(ConfigurationStatus.fromDiagnostics(empty));
        for (String key : new String[]{REVISION, CLOSED, SUMMARY}) {
            Bundle data = applied();
            data.remove(key);
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
    }

    @Test public void everyLocalReceiverAcknowledgementIsRequiredForApplied() {
        Bundle data = applied();
        node(data, "feed_filter.options", true, 4, 4, "APPLIED", "NONE", 1, null);
        ConfigurationStatus result = status(data, "最近配置已完成本地应用", true, false);
        assertTrue(result.detail.contains("2/2 项本地应用"));
        assertTrue(result.detail.contains("第 4 次更新"));
        assertFalse(result.detail.contains("功能已生效"));
    }

    @Test public void aConsumerCanAcknowledgeItsEarlierRelevantRevision() {
        Bundle data = ledger("SUCCESS", 9);
        node(data, "download.options", true, 3, 3, "APPLIED", "NONE", 1, null);
        status(data, "最近配置已完成本地应用", true, false);
    }

    @Test public void unrelatedChangesDoNotInvalidateAnOlderPendingDesiredRevision() {
        Bundle data = ledger("PENDING", 9);
        node(data, "download.options", true, 3, 2, "APPLYING", "APPLYING", 1, null);
        status(data, "最近配置正在应用", false, true);
    }

    @Test public void installedAndMatchingRevisionsDoNotSubstituteForAnAppliedAck() {
        Bundle data = applied();
        data.remove(prefix("download.options") + "status");
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putBoolean(prefix("download.options") + "installed", false);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putString(prefix("download.options") + "reason", "APPLYING");
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putInt(prefix("download.options") + "attempts", 0);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void anUnregisteredReceiverKeepsPartialApplicationPending() {
        Bundle data = applied();
        data.putString(SUMMARY, "PARTIAL");
        node(data, "feed_ui", false, 4, 0, "WAITING_NOT_INSTALLED", "NOT_INSTALLED", 0, null);
        ConfigurationStatus result = status(data, "最近配置等待接收方", false, true);
        assertTrue(result.detail.contains("1/2 项本地应用"));
        assertTrue(result.detail.contains("等待接收方 1 项"));
        assertFalse(result.detail.contains("Hook 未安装"));
    }

    @Test public void dependencyWaitingHasItsOwnPendingPresentation() {
        Bundle data = ledger("PENDING", 4);
        node(data, "auto_comment", true, 4, 0, "WAITING_DEPENDENCY", "DEPENDENCY", 0, null);
        status(data, "最近配置等待依赖", false, true);
    }

    @Test public void dependencyWaitingCanRetainThePreviousRetryFailureClass() {
        Bundle data = ledger("PENDING", 7);
        node(data, "auto_comment", true, 4, 0, "WAITING_DEPENDENCY", "DEPENDENCY", 1, PRIVATE);
        status(data, "最近配置等待依赖", false, true);
    }

    @Test public void activeMutationAndScheduledRetryHaveDistinctPendingStates() {
        Bundle data = ledger("PENDING", 4);
        node(data, "download.options", true, 4, 1, "APPLYING", "APPLYING", 2, "java.lang.IllegalStateException");
        status(data, "最近配置正在应用", false, true);
        node(data, "download.options", true, 4, 1, "RETRY_SCHEDULED", "ACK_TIMEOUT", 1, "java.util.concurrent.TimeoutException");
        status(data, "最近配置正在重试", false, true);
    }

    @Test public void allFailureAndPartialFailureAreTerminalWithoutOtherPendingWork() {
        Bundle data = ledger("FAILED", 4);
        node(data, "download.options", true, 4, 1, "FAILED", "PERMANENT_FAILURE", 1, PRIVATE);
        status(data, "最近配置应用失败", false, false);
        node(data, "feed_ui", true, 4, 4, "APPLIED", "NONE", 1, null);
        data.putString(SUMMARY, "PARTIAL");
        status(data, "最近配置部分应用失败", false, false);
    }

    @Test public void mixedFailureAndUnfinishedWorkStillRequestsPendingRefresh() {
        Bundle data = ledger("FAILED", 4);
        node(data, "download.options", true, 4, 1, "FAILED", "APPLY_FAILURE", 3, PRIVATE);
        node(data, "feed_ui", true, 4, 0, "WAITING_DEPENDENCY", "DEPENDENCY", 0, null);
        status(data, "最近配置部分应用失败", false, true);
    }

    @Test public void dependencyInvalidationCanBeTerminalWithoutAnExceptionClass() {
        Bundle data = ledger("FAILED", 4);
        node(data, "auto_comment", true, 4, 0, "FAILED", "DEPENDENCY_INVALIDATED", 3, null);
        status(data, "最近配置应用失败", false, false);
    }

    @Test public void summaryMustAgreeWithTheObservedNodeStates() {
        for (String token : new String[]{"PENDING", "FAILED", "PARTIAL", "FUTURE_SUCCESS"}) {
            Bundle data = applied();
            data.putString(SUMMARY, token);
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
    }

    @Test public void unknownStateOrReasonDoesNotBecomeAnAppliedOrPendingClaim() {
        for (String field : new String[]{"status", "reason"}) {
            Bundle data = applied();
            data.putString(prefix("download.options") + field, "FUTURE_TOKEN");
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
    }

    @Test public void wrongTypesAndInvalidRevisionRelationsAreRejected() {
        Bundle data = applied();
        data.putInt(REVISION, 4);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putString(CLOSED, "false");
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putLong(prefix("download.options") + "attempts", 1);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        for (long revision : new long[]{-1, 0, 5}) {
            data = applied();
            data.putLong(prefix("download.options") + "desired_revision", revision);
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
        data = applied();
        data.putLong(prefix("download.options") + "applied_revision", 5);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void incompleteOrUnknownConsumerFieldsCannotBeSilentlyIgnored() {
        for (String field : new String[]{"installed", "desired_revision", "applied_revision", "reason", "attempts", "failure_class"}) {
            Bundle data = applied();
            data.remove(prefix("download.options") + field);
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
        Bundle data = applied();
        data.putBoolean(prefix("another_receiver") + "installed", true);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putString(prefix("download.options") + "future_status", "APPLIED");
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void dottedReceiverIdsWorkButMalformedIdsAreNotAccepted() {
        status(applied(), "最近配置已完成本地应用", true, false);
        for (String id : new String[]{"", ".receiver", "receiver..child", "receiver/child"}) {
            Bundle data = ledger("SUCCESS", 4);
            node(data, id, true, 4, 4, "APPLIED", "NONE", 1, null);
            unavailable(ConfigurationStatus.fromDiagnostics(data));
        }
    }

    @Test public void contradictoryWaitingOrFailedEvidenceRemainsUnknown() {
        Bundle data = ledger("PENDING", 4);
        node(data, "feed_ui", true, 4, 0, "WAITING_NOT_INSTALLED", "NOT_INSTALLED", 0, null);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        node(data, "feed_ui", false, 4, 0, "WAITING_DEPENDENCY", "DEPENDENCY", 0, null);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = ledger("FAILED", 4);
        node(data, "feed_ui", true, 4, 0, "FAILED", "NONE", 1, PRIVATE);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
        data = applied();
        data.putString(prefix("download.options") + "failure_class", PRIVATE);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void aClosedNodeCannotAppearInsideAnOpenSuccessfulPublication() {
        Bundle data = ledger("FAILED", 4);
        node(data, "download.options", true, 4, 0, "CLOSED", "CLOSED", 1, null);
        unavailable(ConfigurationStatus.fromDiagnostics(data));
    }

    @Test public void parsingDoesNotMutateTheSourceAndResultsDoNotTrackLaterChanges() {
        Bundle data = applied();
        data.putString("private.config.payload", PRIVATE);
        java.util.Set<String> keys = new java.util.HashSet<>(data.keySet());
        ConfigurationStatus result = ConfigurationStatus.fromDiagnostics(data);
        assertEquals(keys, data.keySet());
        assertEquals(PRIVATE, data.getString("private.config.payload"));
        assertEquals("SUCCESS", data.getString(SUMMARY));
        data.putString(READ, "FAILED");
        data.putLong(REVISION, 99);
        assertTrue(result.applied);
        assertTrue(result.detail.contains("第 4 次更新"));
        assertFalse(result.detail.contains(PRIVATE));
        assertFalse(result.title.contains(PRIVATE));
        assertTrue(result.detail.contains("不代表刚保存的选项已应用"));
        assertTrue(result.detail.contains("不代表具体功能结果"));
    }

    private static ConfigurationStatus status(Bundle data, String title, boolean applied, boolean pending) {
        ConfigurationStatus result = ConfigurationStatus.fromDiagnostics(data);
        assertEquals(title, result.title);
        assertEquals(applied, result.applied);
        assertEquals(pending, result.pending);
        assertNotNull(result.detail);
        assertFalse(result.detail.contains(PRIVATE));
        return result;
    }

    private static void unavailable(ConfigurationStatus result) {
        assertEquals("最近配置状态未获取", result.title);
        assertFalse(result.applied);
        assertFalse(result.pending);
    }

    private static Bundle ledger(String summary, long revision) {
        Bundle data = new Bundle();
        data.putString(READ, "READY");
        data.putLong(REVISION, revision);
        data.putBoolean(CLOSED, false);
        data.putString(SUMMARY, summary);
        return data;
    }

    private static Bundle applied() {
        Bundle data = ledger("SUCCESS", 4);
        node(data, "download.options", true, 4, 4, "APPLIED", "NONE", 1, null);
        return data;
    }

    private static String prefix(String id) { return "configuration.consumer." + id + "."; }

    private static void node(Bundle data, String id, boolean installed, long desired, long applied,
                             String status, String reason, int attempts, String failure) {
        String prefix = prefix(id);
        data.putBoolean(prefix + "installed", installed);
        data.putLong(prefix + "desired_revision", desired);
        data.putLong(prefix + "applied_revision", applied);
        data.putString(prefix + "status", status);
        data.putString(prefix + "reason", reason);
        data.putInt(prefix + "attempts", attempts);
        data.putString(prefix + "failure_class", failure);
    }
}
