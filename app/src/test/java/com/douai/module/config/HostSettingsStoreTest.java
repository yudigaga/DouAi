package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.ss.android.ugc.aweme")
public final class HostSettingsStoreTest {
    @Test
    public void retiredTabPreferencesRemainReadableAlongsideActiveComponentSettings() {
        Context host = RuntimeEnvironment.getApplication();
        SharedPreferences preferences = host.getSharedPreferences(
                SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();

        HostSettingsStore store = new HostSettingsStore(host);
        assertTrue(store.isInHost());
        store.putBoolean("hide_top_tabs", true);
        store.putString("hidden_top_tabs", "商城,朋友");
        assertTrue(preferences.getBoolean("hide_top_tabs", false));
        assertEquals("商城,朋友", preferences.getString("hidden_top_tabs", ""));

        store.putBoolean(ComponentHideSettings.ENABLED, true);
        assertTrue(preferences.getBoolean(ComponentHideSettings.ENABLED, false));
    }
}
