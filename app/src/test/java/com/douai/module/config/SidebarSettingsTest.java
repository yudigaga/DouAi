package com.douai.module.config;

import org.junit.Test;
import java.util.List;
import java.util.Map;
import java.util.Collections;
import java.util.EnumSet;
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
        assertEquals(23, SidebarSettings.ENTRIES.size());
        assertEquals(23, SidebarSettings.ENTRIES.stream().map(item -> item.id).distinct().count());
        assertThrows(UnsupportedOperationException.class, () -> selected.clear());
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
