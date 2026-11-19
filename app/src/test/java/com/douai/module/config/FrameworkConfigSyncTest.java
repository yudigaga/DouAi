package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FrameworkConfigSyncTest {
    @Test public void completePublicationRemovesDeletedSettingsAndObsoleteRemoteCredentials() throws Exception {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences remote = context.getSharedPreferences("remote-transfer-snapshot", Context.MODE_PRIVATE);
        remote.edit().clear().putString("tts_headers", "{\"Authorization\":\"fixture-obsolete\"}")
                .putString("keyword", "old").putBoolean("download_enabled", true).commit();
        Method write = FrameworkConfigSync.class.getDeclaredMethod("writeSettings", SharedPreferences.Editor.class,
                FeatureSettings.class, Map.class);
        write.setAccessible(true);
        SharedPreferences.Editor transaction = remote.edit();
        write.invoke(new FrameworkConfigSync(null), transaction, FeatureSettings.defaults(), Map.of("keyword", "replacement", "auto_play", true));
        assertTrue(remote.contains("tts_headers")); // Preparing the snapshot does not mutate the live store.
        assertTrue(transaction.commit());
        assertFalse(remote.contains("tts_headers"));
        assertEquals("replacement", remote.getString("keyword", ""));
        assertTrue(remote.getBoolean("auto_play", false));
        assertEquals(1, remote.getInt("config_schema_version", 0));
    }

    @Test public void newerServiceStateReplacesAnOlderQueuedFailure() {
        java.util.List<String> statuses = new java.util.ArrayList<>();
        FrameworkConfigSync sync = new FrameworkConfigSync((connected, message, error) ->
                statuses.add(connected + "|" + message + "|" + error));
        try {
            sync.onServiceError("fixture-old-error");
            sync.onServiceUnbound();
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
            assertEquals(java.util.List.of("false|LSPosed 服务未连接。|null"), statuses);
        } finally { sync.close(); }
    }

    @Test public void closedSyncCannotDeliverAQueuedStatusToItsFormerScreen() {
        java.util.List<String> statuses = new java.util.ArrayList<>();
        FrameworkConfigSync sync = new FrameworkConfigSync((connected, message, error) -> statuses.add(message));
        sync.onServiceError("fixture-pending-error");
        sync.close();
        org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
        assertTrue(statuses.isEmpty());
    }

    @Test public void aLateResultKeepsItsOriginalServiceGenerationBeforeItIsQueued() throws Exception {
        java.util.List<String> statuses = new java.util.ArrayList<>();
        FrameworkConfigSync sync = new FrameworkConfigSync((connected, message, error) -> statuses.add(message));
        java.lang.reflect.Field generation = FrameworkConfigSync.class.getDeclaredField("serviceGeneration");
        generation.setAccessible(true);
        Method notify = FrameworkConfigSync.class.getDeclaredMethod("notifyStatus",
                boolean.class, String.class, String.class, long.class);
        notify.setAccessible(true);
        try {
            long beforeDisconnect = generation.getLong(sync);
            sync.onServiceUnbound();
            notify.invoke(sync, true, "stale-commit-result", null, beforeDisconnect);
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
            assertEquals(java.util.List.of("LSPosed 服务未连接。"), statuses);
            long beforeClose = generation.getLong(sync);
            sync.close();
            notify.invoke(sync, true, "stale-result-after-close", null, beforeClose);
            org.robolectric.shadows.ShadowLooper.shadowMainLooper().idle();
            assertEquals(java.util.List.of("LSPosed 服务未连接。"), statuses);
        } finally { sync.close(); }
    }
}
