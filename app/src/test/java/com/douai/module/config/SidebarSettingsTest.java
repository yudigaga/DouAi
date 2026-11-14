package com.douai.module.config;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.EnumSet;
import com.douai.module.scan.HostAdaptation;
import static org.junit.Assert.*;

public final class SidebarSettingsTest {
    @Test public void absentValuesUseTheSixReviewableDefaultsWhileExplicitEmptyRemainsEmpty() {
        LocalOptions absent = new LocalOptions(Collections.emptyMap());
        assertFalse(absent.enabled(SidebarSettings.ENABLED));
        assertEquals(List.of("module", "comment_history", "reply_history", "ai_chat", "download_tasks", "analytics"),
                SidebarSettings.selected(absent).stream().map(item -> item.id).toList());
        assertTrue(SidebarSettings.selected(new LocalOptions(Map.of(SidebarSettings.ITEMS, ""))).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> SidebarSettings.ENTRIES.clear());
    }

    @Test public void entrySelectionOnlyKeepsKnownIdsInStableCatalogOrder() {
        assertEquals("module,comment_history,analytics", SidebarSettings.serialize(
                List.of("analytics", "unknown", "module", "comment_history", "analytics")));
        List<SidebarSettings.Entry> selected = SidebarSettings.parse("analytics, unknown, module，comment_history\nmodule");
        assertEquals(List.of("module", "comment_history", "analytics"), selected.stream().map(item -> item.id).toList());
        assertEquals(27, SidebarSettings.ENTRIES.size());
        assertEquals(27, SidebarSettings.ENTRIES.stream().map(item -> item.id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
    }

    @Test public void readingActionsAreIndependentAndOnlyVisibleOnTheVerified41Profile() {
        String items = "bookmark_list,unknown,bookmark_add,module,reading_search,comment_search,reading_search";
        List<String> all = List.of("module", "bookmark_add", "reading_search", "comment_search", "bookmark_list");
        HostAdaptation.Profile original = HostAdaptation.currentProfile();
        try {
            LocalOptions options = new LocalOptions(Map.of(SidebarSettings.ITEMS, items));
            for (HostAdaptation.Profile legacy : new HostAdaptation.Profile[]{null,HostAdaptation.PROFILE_406,HostAdaptation.PROFILE_408}) {
                HostAdaptation.setCurrentProfile(legacy);
                assertEquals(23,SidebarSettings.availableEntries().size());
                assertEquals(List.of("module"),SidebarSettings.selected(options).stream().map(e -> e.id).toList());
                assertEquals(String.join(",",all),ConfigurationCatalog.normalizedTransferValue(SidebarSettings.ITEMS,items));
                assertEquals("help,bookmark_add,reading_search,comment_search,bookmark_list",
                        SidebarSettings.serializeSelection(items, List.of("help","bad")));
            }
            HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
            assertEquals(all,SidebarSettings.selected(options).stream().map(e -> e.id).toList());
            assertEquals(all.subList(1,5), SidebarSettings.availableEntries().subList(23,27).stream().map(e -> e.id).toList());
            for(String id : all.subList(1,5)) {
                assertTrue(SidebarSettings.isFeedAction(id));
                assertEquals(id,SidebarSettings.serializeSelection(items,List.of(id)));
                assertFalse(SidebarSettings.DEFAULT_ITEMS.contains(id));
            }
            assertFalse(SidebarSettings.isFeedAction("help"));
            assertFalse(SidebarSettings.isFeedAction("like"));
        } finally { HostAdaptation.setCurrentProfile(original); }
    }

    @Test public void sidebarKeysTransferAndRefreshOnlyTheSidebarFeature() {
        assertEquals("boolean", ConfigurationCatalog.transferableTypes().get(SidebarSettings.ENABLED));
        assertEquals("string", ConfigurationCatalog.transferableTypes().get(SidebarSettings.ITEMS));
        assertEquals(false, ConfigurationCatalog.settings().get(SidebarSettings.ENABLED).defaultValue);
        assertEquals(SidebarSettings.DEFAULT_ITEMS, ConfigurationCatalog.settings().get(SidebarSettings.ITEMS).defaultValue);
        for (String key : List.of(SidebarSettings.ENABLED, SidebarSettings.ITEMS)) {
            ConfigurationChanges changes = ConfigurationChanges.between(Collections.emptyMap(), Map.of(key, "changed"));
            java.util.Set<ConfigurationCatalog.Feature> actual = EnumSet.noneOf(ConfigurationCatalog.Feature.class);
            for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) if (changes.affects(feature)) actual.add(feature);
            assertEquals(EnumSet.of(ConfigurationCatalog.Feature.SIDEBAR), actual);
        }
        assertEquals("module,help", ConfigurationCatalog.normalizedTransferValue(SidebarSettings.ITEMS, "help,unknown,module,help"));
        assertEquals("", ConfigurationCatalog.normalizedTransferValue(SidebarSettings.ITEMS, ""));
    }
}
