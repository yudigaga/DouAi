package com.douai.module.ai.skills;

import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatCompletionClient;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static com.douai.module.ai.skills.SkillImageProviderAddress.fromAiProvider;
import static org.junit.Assert.assertEquals;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiProviderImageAddressTest {
    private static final String ORIGIN = "https://provider.example.test:9443";
    private static final String PREFIX = ORIGIN + "/gateway/openai/v1";
    private static final String EXPLICIT_MODELS = "https://catalogue.example.test/tenant/models";

    @Test public void compatibleProtocolsReuseOnlyTheOriginalBasePathAndPort() throws Exception {
        AiModelConfig chat = profile(AiModelConfig.CHAT, "  " + PREFIX + "/chat/completions/  ");
        AiModelConfig responses = profile(AiModelConfig.RESPONSES, PREFIX + "/responses");
        assertEquals(PREFIX, fromAiProvider(chat));
        assertEquals(PREFIX, fromAiProvider(responses));
        assertEquals(PREFIX + "/images/generations", SkillImageProviderAddress.resolve(fromAiProvider(chat), false));
        assertEquals(EXPLICIT_MODELS, ChatCompletionClient.modelsEndpoint(chat));
    }

    @Test public void aBareProviderOriginDoesNotAcquireAnImplicitVersionSuffix() throws Exception {
        assertEquals(ORIGIN, fromAiProvider(profile(AiModelConfig.CHAT, ORIGIN + "/")));
        assertEquals(ORIGIN + "/images/generations",
                SkillImageProviderAddress.resolve(fromAiProvider(profile(AiModelConfig.RESPONSES, ORIGIN)), false));
    }

    @Test public void incompatibleProtocolsAndNonHttpsOrCredentialledUrlsOfferNoSource() throws Exception {
        assertEquals("", fromAiProvider(null));
        assertEquals("", fromAiProvider(profile(AiModelConfig.ANTHROPIC, PREFIX)));
        assertEquals("", fromAiProvider(profile(AiModelConfig.GEMINI, PREFIX)));
        for (String address : List.of("", "http://provider.example.test/v1",
                "https://account@provider.example.test/v1", PREFIX + "?token=do-not-copy",
                PREFIX + "#fragment", ORIGIN + "/gateway/../v1")) {
            assertEquals(address, "", fromAiProvider(profile(AiModelConfig.CHAT, address)));
        }
    }

    @Test public void aChatProfileCannotReuseAListImageOrDifferentChatOperationAsItsBase() throws Exception {
        for (String path : List.of("/models", "/models/example", "/responses", "/images/generations",
                "/%63hat/completions", "/%69mages/generations")) {
            assertEquals(path, "", fromAiProvider(profile(AiModelConfig.CHAT, PREFIX + path)));
        }
        assertEquals("", fromAiProvider(profile(AiModelConfig.RESPONSES, PREFIX + "/chat/completions")));
    }

    @Test public void sourceKeyAppearingInTheBasePathIsNotTransferred() throws Exception {
        assertEquals("", fromAiProvider(profile(AiModelConfig.CHAT, ORIGIN + "/fixture-provider-secret/v1")));
        assertEquals("", fromAiProvider(profile(AiModelConfig.CHAT, ORIGIN + "/%66ixture-provider-secret/v1")));
    }

    @Test public void reusedAddressesCannotContainCredentialHeaderValues() throws Exception {
        for (String header : List.of("aUtHoRiZaTiOn", "api-key", "x-api-key", "x-goog-api-key")) {
            String token = "fixture-own-header-secret";
            JSONObject source = profile(AiModelConfig.CHAT, ORIGIN + "/%66ixture-own-header-secret/v1").toJson();
            String value = header.equalsIgnoreCase("Authorization") ? "Bearer " + token : token;
            source.put("customHeaders", new JSONObject().put(header, value).toString());
            assertEquals(header, "", fromAiProvider(AiModelConfig.fromJson(source)));
        }
        JSONObject invalid = profile(AiModelConfig.CHAT, PREFIX).toJson().put("customHeaders", "{bad headers");
        assertEquals("", fromAiProvider(AiModelConfig.fromJson(invalid)));
    }

    private static AiModelConfig profile(String protocol, String endpoint) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", "sample-model").put("name", "fixture")
                .put("protocol", protocol).put("apiEndpoint", endpoint).put("apiKey", "fixture-provider-secret")
                .put("modelsEndpoint", EXPLICIT_MODELS).put("modelName", "fixture-custom-model"));
    }

}
