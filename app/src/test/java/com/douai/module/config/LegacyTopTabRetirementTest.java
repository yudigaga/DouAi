package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import com.douai.module.scan.HostFeatureCatalog;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Retired preferences remain typed backup data without reinstalling the old tab provider hook. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
public final class LegacyTopTabRetirementTest {
    private SharedPreferences preferences;
    private HostSettingsStore store;

    @Before public void openStore() {
        Context context=RuntimeEnvironment.getApplication();
        preferences=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,Context.MODE_PRIVATE);
        preferences.edit().clear().commit();store=new HostSettingsStore(context);
    }

    @After public void clearStore() { preferences.edit().clear().commit(); }

    @Test public void legacyTabBackupRoundTripsAsTypedDataWithoutRefreshingAnyConsumer() throws Exception {
        store.putBoolean("hide_top_tabs",true);
        store.putString("hidden_top_tabs","商城,homepage_hot");
        String backup=ConfigTransfer.export(store,false);
        JSONObject values=new JSONObject(backup).getJSONObject("values");
        assertEquals("boolean",values.getJSONObject("hide_top_tabs").getString("type"));
        assertEquals("string",values.getJSONObject("hidden_top_tabs").getString("type"));

        preferences.edit().clear().putString("keyword","keep-current").commit();
        ConfigTransfer.apply(store,ConfigTransfer.inspect(store,backup));
        assertTrue(store.getBoolean("hide_top_tabs",false));
        assertEquals("商城,homepage_hot",store.getString("hidden_top_tabs",""));
        assertEquals("keep-current",store.getString("keyword",""));
        ConfigurationChanges changed=ConfigurationChanges.between(Map.of(),Map.of(
                "hide_top_tabs",true,"hidden_top_tabs","商城,homepage_hot"));
        for(String key:new String[]{"hide_top_tabs","hidden_top_tabs"})
            assertTrue(ConfigurationCatalog.settings().get(key).consumers.isEmpty());
        for(ConfigurationCatalog.Feature feature:ConfigurationCatalog.Feature.values())
            assertFalse("Retired preferences must not refresh "+feature,changed.affects(feature));

        Map<String,?> before=store.snapshot();
        values.getJSONObject("hide_top_tabs").put("value","true");
        String invalid=new JSONObject(backup).put("values",values).toString();
        assertThrows(IllegalArgumentException.class,()->ConfigTransfer.inspect(store,invalid));
        assertEquals(before,store.snapshot());
    }

    @Test public void theRetiredProviderIsNotARequiredCapabilityWhileWholeNavigationRemainsSupported() {
        assertTrue(HostFeatureCatalog.getFeatures().stream().noneMatch(feature -> "top_tabs".equals(feature.id)));
        HostFeatureCatalog.Feature navigation=HostFeatureCatalog.getFeatures().stream()
                .filter(feature -> "home_navigation".equals(feature.id)).findFirst().orElseThrow();
        assertEquals("home_navigation_hook_installed",navigation.installedKey);
        assertTrue(navigation.rules.contains("component_top_navigation"));
        assertTrue(navigation.rules.contains("component_bottom_navigation"));
    }
}
