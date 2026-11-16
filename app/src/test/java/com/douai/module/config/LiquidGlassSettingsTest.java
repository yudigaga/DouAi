package com.douai.module.config;

import org.junit.Test;

import java.lang.reflect.Modifier;
import java.util.Collections;
import java.util.EnumSet;
import java.util.Map;

import static org.junit.Assert.*;

public final class LiquidGlassSettingsTest {
    @Test public void absentThemeUsesOriginalAndRetainsTheDefaultGravityChoice() {
        LiquidGlassSettings settings = LiquidGlassSettings.read(new LocalOptions(Collections.emptyMap()));
        assertFalse(settings.enabled);
        assertTrue(settings.gravity);
        assertFalse(LiquidGlassSettings.read(null).enabled);
    }

    @Test public void onlyTheExactGlassStyleEnablesTheThemeAndGravityIsIndependent() throws Exception {
        LiquidGlassSettings enabled = read("liquid_glass", true);
        assertTrue(enabled.enabled);
        assertTrue(enabled.gravity);
        assertTrue(read("liquid_glass", false).enabled);
        assertFalse(read("liquid_glass", false).gravity);
        assertFalse(read("original", true).enabled);
        assertTrue(LiquidGlassSettings.read(new LocalOptions(Map.of("theme_style", "liquid_glass"))).gravity);
        for (String field : new String[]{"enabled", "gravity"}) {
            assertTrue(Modifier.isFinal(LiquidGlassSettings.class.getField(field).getModifiers()));
        }
    }

    @Test public void malformedStylesAndGravityTypesFailClosed() {
        for (Object style : new Object[]{true, 1, "glass", "LIQUID_GLASS", " liquid_glass ", ""}) {
            assertFalse(String.valueOf(style), read(style, true).enabled);
        }
        for (Object gravity : new Object[]{"true", 1, "", Collections.emptyList()}) {
            LiquidGlassSettings settings = read("liquid_glass", gravity);
            assertTrue(settings.enabled);
            assertFalse(String.valueOf(gravity), settings.gravity);
        }
    }

    @Test public void catalogTransfersThemeKeysAndRefreshesExactlyTheirThemeAndSidebarConsumers() {
        assertEquals("theme_style", LiquidGlassSettings.STYLE);
        assertEquals("theme_gravity_enabled", LiquidGlassSettings.GRAVITY);
        assertEquals("string", ConfigurationCatalog.transferableTypes().get(LiquidGlassSettings.STYLE));
        assertEquals("boolean", ConfigurationCatalog.transferableTypes().get(LiquidGlassSettings.GRAVITY));
        assertEquals("original", ConfigurationCatalog.settings().get(LiquidGlassSettings.STYLE).defaultValue);
        assertEquals(true, ConfigurationCatalog.settings().get(LiquidGlassSettings.GRAVITY).defaultValue);
        assertEquals("string", ConfigurationCatalog.transferableTypes().get(HomeUiCustomizationSettings.ICONS));
        assertEquals("boolean", ConfigurationCatalog.transferableTypes().get(HomeUiCustomizationSettings.HIDE_LABELS));
        assertEquals("{}", ConfigurationCatalog.settings().get(HomeUiCustomizationSettings.ICONS).defaultValue);
        assertEquals(false, ConfigurationCatalog.settings().get(HomeUiCustomizationSettings.HIDE_LABELS).defaultValue);
        for (String key : new String[]{LiquidGlassSettings.STYLE, LiquidGlassSettings.GRAVITY,
                HomeUiCustomizationSettings.ICONS, HomeUiCustomizationSettings.HIDE_LABELS}) {
            ConfigurationChanges changed = ConfigurationChanges.between(Collections.emptyMap(), Map.of(key, "changed"));
            EnumSet<ConfigurationCatalog.Feature> consumers = EnumSet.noneOf(ConfigurationCatalog.Feature.class);
            for (ConfigurationCatalog.Feature feature : ConfigurationCatalog.Feature.values()) {
                if (changed.affects(feature)) consumers.add(feature);
            }
            EnumSet<ConfigurationCatalog.Feature> expected = EnumSet.of(ConfigurationCatalog.Feature.THEME);
            if (LiquidGlassSettings.STYLE.equals(key) || LiquidGlassSettings.GRAVITY.equals(key)) {
                expected.add(ConfigurationCatalog.Feature.SIDEBAR);
            }
            assertEquals(key, expected, consumers);
            assertEquals(consumers, ConfigurationCatalog.settings().get(key).consumers);
        }
        ConfigKeys.validate(LiquidGlassSettings.STYLE, "original");
        ConfigKeys.validate(LiquidGlassSettings.STYLE, "liquid_glass");
        ConfigKeys.validate(LiquidGlassSettings.GRAVITY, true);
        ConfigKeys.validate(LiquidGlassSettings.GRAVITY, false);
        assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate(LiquidGlassSettings.STYLE, "unknown"));
        assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate(LiquidGlassSettings.STYLE, true));
        assertThrows(IllegalArgumentException.class, () -> ConfigKeys.validate(LiquidGlassSettings.GRAVITY, "true"));
        assertFalse(ConfigurationChanges.between(Map.of(LiquidGlassSettings.STYLE, "original"),
                Map.of(LiquidGlassSettings.STYLE, "original")).affects(ConfigurationCatalog.Feature.THEME));
    }

    private static LiquidGlassSettings read(Object style, Object gravity) {
        return LiquidGlassSettings.read(new LocalOptions(Map.of("theme_style", style, "theme_gravity_enabled", gravity)));
    }
}
