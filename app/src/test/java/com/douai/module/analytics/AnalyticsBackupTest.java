package com.douai.module.analytics;

import com.douai.module.config.BackupLimitException;
import com.douai.module.config.BackupValidationException;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AnalyticsBackupTest {
    @Test public void versionAndCaptureFailuresIdentifyOnlyTheirFixedHeaderFields() throws Exception {
        JSONObject version = payload(); version.put("version", 4);
        assertDiagnostic(version, BackupValidationException.Reason.ANALYTICS_VERSION, "version");

        JSONObject future = payload();
        future.put("capturedAt", System.currentTimeMillis() + 24 * 60 * 60 * 1000L);
        assertDiagnostic(future, BackupValidationException.Reason.CAPTURED_AT_RANGE, "capturedAt");
    }

    @Test public void invalidDateReportsTheColumnWithoutRetainingTheParseMessage() throws Exception {
        JSONObject value = payload();
        value.getJSONObject("tables").getJSONArray("daily")
                .put(new JSONArray().put("private-backup-cell").put(1).put(0).put(0).put(0));
        BackupValidationException failure = assertDiagnostic(value, BackupValidationException.Reason.ANALYTICS_CELL, "daily.day");
        assertFalse(failure.toString().contains("private-backup-cell"));
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test public void cellRangesAndCrossColumnRulesKeepTheirExistingBounds() throws Exception {
        JSONObject hour = payload();
        hour.getJSONObject("tables").getJSONArray("hours")
                .put(new JSONArray().put("1970-01-01").put(24).put(1));
        assertDiagnostic(hour, BackupValidationException.Reason.ANALYTICS_CELL, "hours.hour");

        JSONObject ads = payload();
        ads.getJSONObject("tables").getJSONArray("daily")
                .put(new JSONArray().put("1970-01-01").put(1).put(0).put(2).put(0));
        assertDiagnostic(ads, BackupValidationException.Reason.ANALYTICS_CELL, "daily.ads");
    }

    @Test public void malformedRowsAndDuplicateKeysUseOnlyTheFixedTableName() throws Exception {
        JSONObject malformed = payload();
        malformed.getJSONObject("tables").getJSONArray("recent")
                .put(new JSONObject().put("private-input-key", "private-input-value"));
        BackupValidationException failure = assertDiagnostic(malformed, BackupValidationException.Reason.ANALYTICS_CELL, "recent");
        assertFalse(failure.toString().contains("private-input"));

        JSONObject duplicate = payload();
        JSONArray rows = duplicate.getJSONObject("tables").getJSONArray("collection_boundaries");
        rows.put(new JSONArray().put(1).put(0).put(1));
        rows.put(new JSONArray().put(1).put(0).put(0));
        assertDiagnostic(duplicate, BackupValidationException.Reason.ANALYTICS_CELL, "collection_boundaries");
    }

    @Test public void existingIntegerTypesAndFiveMinuteToleranceRemainAccepted() throws Exception {
        JSONObject value = payload();
        value.put("capturedAt", System.currentTimeMillis() + 299_000);
        value.getJSONObject("tables").getJSONArray("collection_boundaries")
                .put(new JSONArray().put(1).put(0L).put(1));
        assertEquals(1, AnalyticsBackup.inspect(value).rowCount);
    }

    @Test public void existingSizeLimitFailureIsNotWrappedAsAValidationFailure() {
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class, () -> AnalyticsBackup.inspect(null));
        assertTrue(failure instanceof BackupLimitException);
        assertFalse(failure instanceof BackupValidationException);
    }

    private static JSONObject payload() throws Exception {
        return AnalyticsBackup.capture(null, System.currentTimeMillis(), 0)
                .put("context", new JSONObject().put("purpose", "").put("feedback", ""));
    }

    private static BackupValidationException assertDiagnostic(JSONObject value, BackupValidationException.Reason reason, String field) {
        BackupValidationException failure = assertThrows(BackupValidationException.class, () -> AnalyticsBackup.inspect(value));
        assertEquals(BackupValidationException.Section.ANALYTICS, failure.section);
        assertEquals(reason, failure.reason);
        assertEquals(field, failure.field);
        return failure;
    }
}
