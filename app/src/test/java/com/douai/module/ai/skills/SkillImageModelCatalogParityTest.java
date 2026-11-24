package com.douai.module.ai.skills;

import com.douai.module.config.AiModelConfig;
import com.douai.module.feature.ChatCompletionClient;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillImageModelCatalogParityTest {
    private static final String MODELS = "https://images.example.test/v1/models";
    private static final String BEARER = "synthetic-image-directory-key";

    @Test public void aValidJsonDirectoryDoesNotDependOnTheGatewaysMimeType() throws Exception {
        String json = "{\"data\":[{\"id\":\"image-v1\"}]}";
        assertEquals(List.of("image-v1"), primaryAiModels(json));
        assertEquals(List.of("image-v1"), imageModels(json, "text/plain; charset=utf-8"));
        assertEquals(List.of("image-v1"), imageModels(json, "application/octet-stream"));
    }

    @Test public void catalogueRowsUsingNameStillOfferTheProvidersExactModel() throws Exception {
        String json = "{\"models\":[{\"name\":\"Gateway/ImageV2\"}]}";
        assertEquals(List.of("Gateway/ImageV2"), primaryAiModels(json));
        assertEquals(List.of("Gateway/ImageV2"), imageModels(json, "application/json"));
    }

    @Test public void savedLegacyApiBaseCanListModelsOnTheSameOriginWithoutChangingGeneration() throws Exception {
        String legacy = "https://images.example.test/gateway/v1";
        String expected = "https://images.example.test/gateway/v1/models";
        assertEquals(expected, SkillImageModelCatalog.resolveEndpoint(legacy, ""));
        assertEquals(SkillImageModelCatalog.Code.CATALOG_REQUIRED,
                assertThrows(SkillImageModelCatalog.Failure.class, () -> SkillImageModelCatalog.resolveEndpoint(
                        "https://images.example.test/custom/render", "")).code());
    }

    @Test public void dzhRootUsesTheDocumentedCatalogueWithoutChangingTheGenerationAddress() throws Exception {
        String host = "https://dzhsmartai.com";
        String generation = SkillImageProviderAddress.resolve(host, false);
        assertEquals(host + "/images/generations", generation);
        assertEquals(host + "/v1/models", SkillImageModelCatalog.resolveEndpoint(generation, ""));
        assertEquals(host + "/v1/models", SkillImageModelCatalog.resolveEndpoint(host, ""));

        String legacy = "https://DzHsMaRtAi.CoM:443/";
        SkillImageProviderAddress.Form reopened = SkillImageProviderAddress.fromSaved(legacy);
        String original = SkillImageProviderAddress.resolve(reopened.address(), reopened.complete());
        assertEquals(legacy, original);
        assertEquals("https://DzHsMaRtAi.CoM:443/v1/models", SkillImageModelCatalog.resolveEndpoint(original, ""));
    }

    @Test public void dzhCatalogueDefaultPreservesExplicitOverridesAndOtherPrefixesOrOrigins() throws Exception {
        String host = "https://dzhsmartai.com";
        assertEquals(host + "/models", SkillImageModelCatalog.resolveEndpoint(host + "/images/generations", host + "/models"));
        assertEquals(host + "/gateway/models", SkillImageModelCatalog.resolveEndpoint(host + "/gateway/images/generations", ""));
        assertEquals(host + "/v2/models", SkillImageModelCatalog.resolveEndpoint(host + "/v2", ""));
        for (String other : List.of("https://api.dzhsmartai.com", host + ":9443", "https://dzhsmartai.com.example.test")) {
            assertEquals(other + "/models", SkillImageModelCatalog.resolveEndpoint(other + "/images/generations", ""));
        }
    }

    private static List<String> primaryAiModels(String json) {
        return ReflectionHelpers.callStaticMethod(ChatCompletionClient.class, "parseModelIds",
                ReflectionHelpers.ClassParameter.from(String.class, AiModelConfig.CHAT),
                ReflectionHelpers.ClassParameter.from(String.class, json));
    }

    private static List<String> imageModels(String json, String mime) throws Exception {
        AtomicInteger requests = new AtomicInteger();
        SkillImageModelCatalog directory = new SkillImageModelCatalog((url, method, headers, body, deadline, maximum, reader) -> {
            requests.incrementAndGet();
            assertEquals(MODELS, url);
            assertEquals("GET", method);
            assertNull(body);
            return reader.read(200, ignored -> mime,
                    new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        });
        List<String> result = directory.fetch(MODELS, BEARER);
        assertEquals(1, requests.get());
        return result;
    }
}
