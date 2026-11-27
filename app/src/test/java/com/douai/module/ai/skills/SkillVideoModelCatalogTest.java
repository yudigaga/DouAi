package com.douai.module.ai.skills;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillVideoModelCatalogTest {
    private static final String SUBMIT = "https://gateway.example.com:9443/gateway/u/v1/videos";
    private static final String MODELS = "https://gateway.example.com:9443/gateway/u/v1/models";
    private static final String TOKEN = "synthetic-video-key";

    @Test public void derivesOnlyFromKnownVersionedSubmitRoutesOnTheSameOrigin() throws Exception {
        assertEquals(MODELS, SkillVideoModelCatalog.resolveEndpoint(SUBMIT));
        assertEquals(MODELS, SkillVideoModelCatalog.resolveEndpoint(SUBMIT + "/generations/"));
        assertEquals("https://gateway.example.com/v2/models",
                SkillVideoModelCatalog.resolveEndpoint("https://gateway.example.com/v2/videos"));
        for (String invalid : List.of(
                "https://gateway.example.com", "https://gateway.example.com/videos",
                "https://gateway.example.com/v1/specific-video-task",
                "https://gateway.example.com/v1/videos/custom",
                "https://gateway.example.com/v1/videos?next=/v1/models",
                "https://user@gateway.example.com/v1/videos",
                "http://gateway.example.com/v1/videos", "https://127.0.0.1/v1/videos")) {
            assertThrows(IOException.class, () -> SkillVideoModelCatalog.resolveEndpoint(invalid));
        }
    }

    @Test public void explicitLookupMakesOneGetAndReturnsOnlySafeUniqueIds() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        var models = SkillVideoModelCatalog.fetch(SUBMIT, TOKEN,
                (endpoint, method, headers, body, deadline, maximum, reader) -> {
                    assertEquals(1, requests.incrementAndGet());
                    assertEquals(MODELS, endpoint);
                    assertEquals("GET", method);
                    assertEquals(Map.of("Accept", "application/json", "Authorization", "Bearer " + TOKEN), headers);
                    assertNull(body);
                    assertEquals(256 * 1024, maximum);
                    return reader.read(200, ignored -> null, bytes("""
                            {"data":[{"id":"video-v1"},{"id":"text-v2"},{"id":"video-v1"},
                              {"id":" leading-space"},{"id":"nested\\\\value"},{"name":"another-model"}]}
                            """));
                });
        assertEquals(List.of("video-v1", "text-v2", "another-model"), models);
        assertEquals(1, requests.get());
    }

    @Test public void blankKeyDoesNotAddAuthorizationAndRedirectIsNeverRetried() {
        AtomicInteger requests = new AtomicInteger();
        IOException failure = assertThrows(IOException.class, () -> SkillVideoModelCatalog.fetch(SUBMIT, "",
                (endpoint, method, headers, body, deadline, maximum, reader) -> {
                    assertEquals(1, requests.incrementAndGet());
                    assertEquals(MODELS, endpoint);
                    assertEquals(Map.of("Accept", "application/json"), headers);
                    assertNull(body);
                    return reader.read(302, key -> "Location".equals(key) ? "https://elsewhere.example.com/v1/models" : null,
                            bytes("{}"));
                }));
        assertTrue(failure.getMessage().contains("重定向"));
        assertFalse(failure.getMessage().contains("elsewhere"));
        assertEquals(1, requests.get());
    }

    @Test public void rejectsKeyEchoAndUnsafeRequestBeforeOrAfterTheGetAsApplicable() {
        IOException failure = assertThrows(IOException.class, () -> SkillVideoModelCatalog.fetch(SUBMIT, TOKEN,
                (endpoint, method, headers, body, deadline, maximum, reader) ->
                        reader.read(200, ignored -> null, bytes("{\"data\":[{\"id\":\"" + TOKEN + "\"}]}"))));
        assertFalse(failure.getMessage().contains(TOKEN));
        assertThrows(IOException.class, () -> SkillVideoModelCatalog.fetch(SUBMIT, "bad key",
                (endpoint, method, headers, body, deadline, maximum, reader) -> { throw new AssertionError(); }));
        assertThrows(IOException.class, () -> SkillVideoModelCatalog.fetch(
                "https://gateway.example.com/" + TOKEN + "/v1/videos", TOKEN,
                (endpoint, method, headers, body, deadline, maximum, reader) -> { throw new AssertionError(); }));
    }

    private static ByteArrayInputStream bytes(String value) {
        return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
    }
}
