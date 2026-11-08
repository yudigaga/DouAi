package com.douai.module.config;

import org.junit.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

public final class ManualAiConfigurationTest {
    @Test public void manualAiPermissionsDefaultOffAndRoundTripAsBooleans() {
        for (String key : new String[]{"comment_ai_takeover", "manual_ai_douyin_search"}) {
            assertEquals(key, "boolean", ConfigurationCatalog.transferableTypes().get(key));
            ConfigurationCatalog.Setting setting = ConfigurationCatalog.settings().get(key);
            assertNotNull(setting);
            assertEquals(Boolean.FALSE, setting.defaultValue);
            ConfigurationCatalog.validateTransfer(key, true);
            ConfigurationCatalog.validateTransfer(key, false);
        }
    }

    @Test public void searchAndTakeoverChangesOnlyInvalidateManualAiConsumers() {
        for (String key : new String[]{"comment_ai_takeover", "manual_ai_douyin_search", "comment_ai_takeover_model_id"}) {
            ConfigurationCatalog.Setting setting = ConfigurationCatalog.settings().get(key);
            assertNotNull(key, setting);
            Set<String> consumers = setting.consumers.stream().map(Enum::name).collect(Collectors.toSet());
            assertEquals(key, Set.of("MANUAL_AI"), consumers);
        }
    }

    @Test public void commentAnalysisHasAnIndependentSavedModelBinding() {
        assertEquals("string", ConfigurationCatalog.transferableTypes().get("comment_ai_takeover_model_id"));
        assertEquals("", ConfigurationCatalog.settings().get("comment_ai_takeover_model_id").defaultValue);
        assertNotEquals(AiModelSettings.COMMENT_MODEL_ID, "comment_ai_takeover_model_id");
        assertNotEquals(AiModelSettings.REPLY_MODEL_ID, "comment_ai_takeover_model_id");
    }
}
