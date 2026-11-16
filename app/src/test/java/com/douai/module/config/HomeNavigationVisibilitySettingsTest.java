package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Navigation visibility is separate from artwork and never includes existing Feed hiding. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class HomeNavigationVisibilitySettingsTest {
    @Test public void canonicalNavigationKeysRoundTripWithoutAcceptingFeedOrGuessingTopIds() {
        String hidden=HomeUiCustomizationSettings.setNavigationHidden("[]","nav_bottom:USER",true);
        hidden=HomeUiCustomizationSettings.setNavigationHidden(hidden,"nav_top:runtime_city",true);
        assertEquals(Set.of("nav_bottom:homepage_profile","nav_top:runtime_city"),HomeUiCustomizationSettings.readHiddenNavigation(hidden));
        assertEquals(hidden,HomeUiCustomizationSettings.setNavigationHidden(hidden,"nav_bottom:homepage_profile",true));
        assertEquals(Set.of("nav_top:runtime_city"),HomeUiCustomizationSettings.readHiddenNavigation(
                HomeUiCustomizationSettings.setNavigationHidden(hidden,"nav_bottom:USER",false)));
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.setNavigationHidden("[]","feed_like",true));
        assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(null).isEmpty());
        assertEquals("[]",HomeUiCustomizationSettings.normalizeHiddenNavigation(" \n\t "));
    }

    @Test public void hiddenSnapshotsAreImmutableAndDisplayingOneItemPreservesTheOthers() {
        String hidden="[\"nav_top:homepage_hot\",\"nav_bottom:HOME\"]";
        Set<String> first=HomeUiCustomizationSettings.readHiddenNavigation(hidden);
        assertThrows(UnsupportedOperationException.class,()->first.add("nav_top:other"));
        String shown=HomeUiCustomizationSettings.setNavigationHidden(hidden,"nav_top:homepage_hot",false);
        assertEquals(Set.of("nav_bottom:homepage_home"),HomeUiCustomizationSettings.readHiddenNavigation(shown));
        assertEquals(2,first.size());
    }

    @Test public void malformedTypesDuplicatesAndNonNavigationInputsAreRejected() {
        for(String input:new String[]{"{}","[false]","[null]","[] trailing","['nav_top:homepage_hot']",
                "[\"feed_comment\"]","[\"nav_top:\"]","[\"nav_top:bad space\"]",
                "[\"nav_bottom:HOME\",\"nav_bottom:homepage_home\"]"})
            assertThrows(input,IllegalArgumentException.class,()->HomeUiCustomizationSettings.readHiddenNavigation(input));
    }

    @Test public void boundsApplyBeforeWhitespaceAndTheThirtyThirdLocationIsRejected() {
        String hidden="[]";
        for(int i=0;i<32;i++)hidden=HomeUiCustomizationSettings.setNavigationHidden(hidden,"nav_top:channel_"+i,true);
        assertEquals(32,HomeUiCustomizationSettings.readHiddenNavigation(hidden).size());
        String full=hidden;
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.setNavigationHidden(full,"nav_top:overflow",true));
        assertThrows(IllegalArgumentException.class,()->HomeUiCustomizationSettings.readHiddenNavigation(" ".repeat(8193)));
        assertEquals(31,HomeUiCustomizationSettings.readHiddenNavigation(
                HomeUiCustomizationSettings.setNavigationHidden(full,"nav_top:channel_0",false)).size());
    }

    @Test public void typedBackupRestoresHiddenPositionsAndInvalidImportsCannotPublish() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        SharedPreferences preferences=context.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        preferences.edit().clear().commit();HostSettingsStore store=new HostSettingsStore(context);
        try {
            String hidden=HomeUiCustomizationSettings.setNavigationHidden("[]","nav_bottom:HOME",true);
            store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden);
            String backup=ConfigTransfer.export(store,false);
            preferences.edit().clear().commit();ConfigTransfer.apply(store,ConfigTransfer.inspect(store,backup));
            assertEquals(hidden,store.getString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[]"));
            assertEquals("string",ConfigurationCatalog.transferableTypes().get(HomeUiCustomizationSettings.HIDDEN_NAVIGATION));
            assertTrue(ConfigurationCatalog.settings().get(HomeUiCustomizationSettings.HIDDEN_NAVIGATION).consumers.contains(ConfigurationCatalog.Feature.THEME));
            JSONObject invalid=new JSONObject(backup);
            invalid.getJSONObject("values").getJSONObject(HomeUiCustomizationSettings.HIDDEN_NAVIGATION)
                    .put("value",new JSONArray().put("feed_like").toString());
            Map<String,?> before=store.snapshot();
            assertThrows(IllegalArgumentException.class,()->ConfigTransfer.inspect(store,invalid.toString()));
            assertEquals(before,store.snapshot());
        } finally {preferences.edit().clear().commit();}
    }
}
