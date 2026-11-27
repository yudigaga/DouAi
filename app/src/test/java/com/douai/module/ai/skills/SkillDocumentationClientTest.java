package com.douai.module.ai.skills;

import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public final class SkillDocumentationClientTest {
    private static final String DOCUMENT = "https://docs.example.com/spec/openapi.json";

    @Test public void acceptsDirectJsonDocumentsAndRejectsNonPublicOrCredentialBearingTargets() {
        String rawGithub = "https://raw.githubusercontent.com/owner/project/main/openapi.json";
        assertEquals(rawGithub, SkillDocumentationClient.normalize(" " + rawGithub + " "));
        for (String invalid : List.of(
                "http://docs.example.com/openapi.json",
                "https://docs.example.com/openapi.yaml",
                "https://docs.example.com/openapi.json?version=1",
                "https://docs.example.com/openapi.json#definitions",
                "https://account@docs.example.com/openapi.json",
                "https://docs.example.com/Bearer%20synthetic-private-token/openapi.json",
                "https://127.0.0.1/openapi.json",
                "https://docs.example.com/openapi.json/../spec.json",
                "https://github.com/owner/project/blob/main/openapi.json")) {
            assertThrows(IllegalArgumentException.class, () -> SkillDocumentationClient.normalize(invalid));
        }
    }

    @Test public void readsOnlyTheSelectedDocumentWithABoundedDeadlineAndNoCredentialArgument() throws Exception {
        String json = " { \"openapi\": \"3.0.3\" }\n";
        String result = SkillDocumentationClient.read(DOCUMENT, future(), (endpoint, deadline, limit) -> {
            assertEquals(DOCUMENT, endpoint);
            assertEquals(SkillDocumentationClient.MAX_DOCUMENT_BYTES, limit);
            assertTrue(deadline - System.nanoTime() <= TimeUnit.SECONDS.toNanos(10));
            return response(200, "text/plain", json.getBytes(StandardCharsets.UTF_8));
        });
        assertEquals(json, result);
    }

    @Test public void fetchedContentMustBeABoundedUtf8JsonObjectOnASuccessfulResponse() {
        byte[] object = "{}".getBytes(StandardCharsets.UTF_8);
        for (SkillHttpsTransport.Response invalid : List.of(
                response(302, "application/json", object),
                response(206, "application/json", object),
                response(200, "text/html", object),
                response(200, "text/plain", "[1]".getBytes(StandardCharsets.UTF_8)),
                response(200, "application/json", new byte[]{'{', (byte) 0xc3, '}' }),
                response(200, "application/json", new byte[SkillDocumentationClient.MAX_DOCUMENT_BYTES + 1]))) {
            assertThrows(IOException.class,
                    () -> SkillDocumentationClient.read(DOCUMENT, future(), (endpoint, deadline, limit) -> invalid));
        }
        assertThrows(IOException.class, () -> SkillDocumentationClient.read(DOCUMENT, System.nanoTime() - 1,
                (endpoint, deadline, limit) -> { throw new AssertionError("Expired request must not dispatch"); }));
    }

    private static SkillHttpsTransport.Response response(int status, String type, byte[] data) {
        return new SkillHttpsTransport.Response(status, type, data);
    }

    private static long future() { return System.nanoTime() + TimeUnit.MINUTES.toNanos(1); }
}
