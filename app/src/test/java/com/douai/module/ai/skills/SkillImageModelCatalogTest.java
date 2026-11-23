package com.douai.module.ai.skills;

import com.douai.module.ai.AiExtensionHttp;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillImageModelCatalogTest {
    private static final String IMAGE = "https://images.example.test/gateway/v1/images/generations";
    private static final String MODELS = "https://images.example.test/gateway/v1/models";
    private static final String BEARER = "fixture-image-secret";

    @Test public void modelPathUsesTheExactGenerationPrefixOrAnExplicitSameOriginOverride() throws Exception {
        assertEquals("", SkillImageModelCatalog.validateOverride("", ""));
        assertEquals(MODELS, SkillImageModelCatalog.resolveEndpoint(IMAGE, ""));
        String custom = "https://IMAGES.example.test:443/available";
        assertEquals(custom, SkillImageModelCatalog.validateOverride(IMAGE, " " + custom + " "));
        assertEquals(custom, SkillImageModelCatalog.resolveEndpoint("https://images.example.test/custom/generate", custom));
        assertEquals(SkillImageModelCatalog.Code.CATALOG_REQUIRED, assertThrows(SkillImageModelCatalog.Failure.class,
                () -> SkillImageModelCatalog.resolveEndpoint("https://images.example.test/custom/generate", "")).code());
    }

    @Test public void differentPortHostAndGenerationPathAreNeverAcceptedAsTheCatalogTarget() {
        for (String endpoint : List.of("https://images.example.test:444/models", "https://other.example.test/models")) {
            assertEquals(SkillImageModelCatalog.Code.DIFFERENT_ORIGIN, assertThrows(SkillImageModelCatalog.Failure.class,
                    () -> SkillImageModelCatalog.resolveEndpoint(IMAGE, endpoint)).code());
        }
        for (String endpoint : List.of("http://images.example.test/models", "https://user@images.example.test/models",
                MODELS + "?key=fixture", "https://images.example.test:443/gateway/v1/images/generations")) {
            assertEquals(SkillImageModelCatalog.Code.INVALID_ENDPOINT, assertThrows(SkillImageModelCatalog.Failure.class,
                    () -> SkillImageModelCatalog.resolveEndpoint(IMAGE, endpoint)).code());
        }
    }

    @Test public void discoveryUsesOneGetAndReturnsOnlyExactStringIds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        SkillImageModelCatalog catalog = new SkillImageModelCatalog((endpoint, method, headers, body, deadline, limit, reader) -> {
            requests.incrementAndGet();
            assertEquals(MODELS, endpoint);
            assertEquals("GET", method);
            assertNull(body);
            assertEquals("Bearer " + BEARER, headers.get("Authorization"));
            assertEquals(2, headers.size());
            assertTrue(deadline > System.nanoTime());
            assertTrue(limit <= 256 * 1024);
            return reader.read(200, name -> "application/json; charset=utf-8", json("""
                    {"data":[{"id":"Photo-A"},{"id":"photo-a"},{"id":"models/Custom"},
                             {"id":"Photo-A"},{"id":9},{"name":"name-only"},{"id":" padded "}]}
                    """));
        });
        assertEquals(List.of("Photo-A", "photo-a", "models/Custom"), catalog.fetch(MODELS, BEARER));
        assertEquals(1, requests.get());
        assertEquals(List.of("fallback"), catalog(200, "{\"models\":[{\"id\":\"fallback\"}]}").fetch(MODELS, BEARER));
    }

    @Test public void validLargeModelListsKeepEveryChoiceWithinTheExistingBodyAndRowLimits() throws Exception {
        org.json.JSONArray rows = new org.json.JSONArray();
        for (int i = 0; i < 240; i++) rows.put(new org.json.JSONObject().put("id", "Image Model " + i));
        String body = new org.json.JSONObject().put("data", rows).toString();
        List<String> models = catalog(200, body).fetch(MODELS, BEARER);
        assertEquals(240, models.size());
        assertEquals("Image Model 239", models.get(239));
    }

    @Test public void credentialEchoOrAddressContainsNoUsableResponse() {
        SkillImageModelCatalog.Failure echo = assertThrows(SkillImageModelCatalog.Failure.class,
                () -> catalog(200, "{\"data\":[{\"id\":\"\\u0066ixture-image-secret\"}]}").fetch(MODELS, BEARER));
        assertEquals(SkillImageModelCatalog.Code.UNSAFE_RESPONSE, echo.code());
        assertFalse(echo.toString().contains(BEARER));
        assertNull(echo.getCause());

        AtomicInteger requests = new AtomicInteger();
        SkillImageModelCatalog catalog = new SkillImageModelCatalog((endpoint, method, headers, body, deadline, limit, reader) -> {
            requests.incrementAndGet();
            throw new AssertionError("Credential in address must fail before transport");
        });
        for (String path : List.of(BEARER, "%66ixture-image-secret")) {
            SkillImageModelCatalog.Failure unsafe = assertThrows(SkillImageModelCatalog.Failure.class,
                    () -> catalog.fetch("https://images.example.test/" + path, BEARER));
            assertEquals(SkillImageModelCatalog.Code.INVALID_ENDPOINT, unsafe.code());
            assertFalse(unsafe.toString().contains(path));
        }
        assertEquals(0, requests.get());
    }

    @Test public void redirectAccessAndFormatFailuresExposeOnlyFixedMessages() {
        check(302, BEARER, SkillImageModelCatalog.Code.REDIRECT);
        check(401, BEARER, SkillImageModelCatalog.Code.AUTHENTICATION);
        check(404, BEARER, SkillImageModelCatalog.Code.UNSUPPORTED);
        check(200, "{\"data\":[]}", SkillImageModelCatalog.Code.EMPTY);
        check(200, "{\"data\":[{\"id\":1}]}",
                SkillImageModelCatalog.Code.INVALID_RESPONSE);
        check(200, "{\"data\":[{\"id\":\"a\",\"id\":\"b\"}]}",
                SkillImageModelCatalog.Code.INVALID_RESPONSE);
        check(200, "{\"data\":[{\"id\":\"a\"}]} {}",
                SkillImageModelCatalog.Code.INVALID_RESPONSE);
    }

    @Test public void deadlineFailureDoesNotProbeAgain() {
        AtomicInteger requests = new AtomicInteger();
        SkillImageModelCatalog catalog = new SkillImageModelCatalog((endpoint, method, headers, body, deadline, limit, reader) -> {
            requests.incrementAndGet();
            throw new AiExtensionHttp.Failure(AiExtensionHttp.Kind.DEADLINE, true);
        });
        assertEquals(SkillImageModelCatalog.Code.DEADLINE,
                assertThrows(SkillImageModelCatalog.Failure.class, () -> catalog.fetch(MODELS, BEARER)).code());
        assertEquals(1, requests.get());
    }

    private static void check(int status, String body, SkillImageModelCatalog.Code code) {
        SkillImageModelCatalog.Failure failure = assertThrows(SkillImageModelCatalog.Failure.class,
                () -> catalog(status, body).fetch(MODELS, BEARER));
        assertEquals(code, failure.code());
        assertFalse(failure.toString().contains(BEARER));
        assertNull(failure.getCause());
    }

    private static SkillImageModelCatalog catalog(int status, String body) {
        return new SkillImageModelCatalog((endpoint, method, headers, request, deadline, limit, reader) ->
                reader.read(status, name -> "application/json", json(body)));
    }

    private static ByteArrayInputStream json(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }
}
