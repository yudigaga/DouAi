package com.douai.module.ai.mcp;

import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class McpClientTest {
    private static final String ENDPOINT = "https://tools.example.test/mcp";
    private static final String TOKEN = "test-mcp-secret";
    private static final String SESSION = "session-fixture";

    @Test public void legacyInitializesAndUsesSessionForPagedDiscoveryAndCleanup() throws Exception {
        Fixture fixture = new Fixture();
        List<McpClient.Tool> tools = fixture.client.discover(ENDPOINT, McpClient.LEGACY, TOKEN, deadline());
        assertEquals(List.of("lookup", "second"), tools.stream().map(McpClient.Tool::name).collect(java.util.stream.Collectors.toList()));
        assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list", "DELETE"), fixture.methods);
        assertFalse(fixture.headers.get(0).containsKey("MCP-Session-Id"));
        for (Map<String, String> headers : fixture.headers.subList(1, 5)) {
            assertEquals(SESSION, headers.get("MCP-Session-Id"));
            assertEquals(McpClient.LEGACY, headers.get("MCP-Protocol-Version"));
        }
        assertEquals("Bearer " + TOKEN, fixture.headers.get(2).get("Authorization"));
        assertEquals("next", fixture.paginationCursor);
    }

    @Test public void modernDiscoversAndMirrorsAnnotatedArgumentsWhenCallingAndReadsSse() throws Exception {
        Fixture fixture = new Fixture();
        McpClient.Tool expected = fixture.client.discover(ENDPOINT, McpClient.MODERN, "", deadline()).get(0);
        AtomicInteger admitted = new AtomicInteger();
        fixture.sse = true;
        String output = fixture.client.call(ENDPOINT, McpClient.MODERN, "", expected,
                new JSONObject().put("query", "杭州"), deadline(), () -> {
                    assertEquals("tools/list", fixture.methods.get(fixture.methods.size() - 1));
                    admitted.incrementAndGet();
                });
        assertEquals("answer", output);
        assertEquals(1, admitted.get());
        Map<String, String> headers = fixture.headers.get(fixture.headers.size() - 1);
        assertEquals("tools/call", headers.get("Mcp-Method"));
        assertEquals("lookup", headers.get("Mcp-Name"));
        assertEquals("=?base64?5p2t5bee?=", headers.get("Mcp-Param-Query"));
        assertFalse(headers.containsKey("Authorization"));
    }

    @Test public void changeToPermissionsOrSchemaStopsBeforeAdmissionOrToolPost() throws Exception {
        Fixture fixture = new Fixture();
        McpClient.Tool expected = fixture.client.discover(ENDPOINT, McpClient.MODERN, TOKEN, deadline()).get(0);
        fixture.changed = true;
        McpClient.Failure change = assertThrows(McpClient.Failure.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN,
                TOKEN, expected, new JSONObject().put("query", "ok"), deadline(), () -> fail("must not admit")));
        assertEquals(McpClient.Kind.TOOL_CHANGED, change.kind());
        assertFalse(change.resultUnknown());
        assertFalse(fixture.methods.contains("tools/call"));
    }

    @Test public void invalidArgumentsAndDeniedAdmissionDoNotDispatch() throws Exception {
        Fixture fixture = new Fixture();
        McpClient.Tool expected = fixture.client.discover(ENDPOINT, McpClient.MODERN, TOKEN, deadline()).get(0);
        McpClient.Failure invalid = assertThrows(McpClient.Failure.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN,
                TOKEN, expected, new JSONObject().put("query", 5), deadline(), () -> fail("must not admit")));
        assertEquals(McpClient.Kind.SCHEMA, invalid.kind());
        assertThrows(IOException.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN, TOKEN, expected,
                new JSONObject().put("query", "data"), deadline(), () -> { throw new IOException("denied"); }));
        assertFalse(fixture.methods.contains("tools/call"));
    }

    @Test public void paidTimeoutReportsUnknownAndNeverReplays() throws Exception {
        Fixture fixture = new Fixture();
        McpClient.Tool expected = fixture.client.discover(ENDPOINT, McpClient.MODERN, TOKEN, deadline()).get(0);
        fixture.timeout = true;
        McpClient.Failure timeout = assertThrows(McpClient.Failure.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN,
                TOKEN, expected, new JSONObject().put("query", "submit"), deadline(), () -> { }));
        assertEquals(McpClient.Kind.DEADLINE, timeout.kind());
        assertTrue(timeout.resultUnknown());
        assertEquals(1, fixture.methods.stream().filter("tools/call"::equals).count());
    }

    @Test public void escapedCredentialEchoAndServerInitiatedRequestAreRejected() throws Exception {
        Fixture fixture = new Fixture();
        McpClient.Tool expected = fixture.client.discover(ENDPOINT, McpClient.MODERN, TOKEN, deadline()).get(0);
        fixture.echo = true;
        McpClient.Failure echo = assertThrows(McpClient.Failure.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN,
                TOKEN, expected, new JSONObject().put("query", "ok"), deadline(), () -> { }));
        assertEquals(McpClient.Kind.UNSAFE_RESPONSE, echo.kind());
        assertFalse(echo.toString().contains(TOKEN));
        assertNull(echo.getCause());
        fixture.serverRequest = true;
        McpClient.Failure request = assertThrows(McpClient.Failure.class, () -> fixture.client.call(ENDPOINT, McpClient.MODERN,
                TOKEN, expected, new JSONObject().put("query", "ok"), deadline(), () -> { }));
        assertEquals(McpClient.Kind.UNSAFE_RESPONSE, request.kind());
        assertTrue(request.resultUnknown());
    }

    @Test public void pinnedVersionMismatchFailsWithoutFallingBackOrListingTools() throws Exception {
        Fixture fixture = new Fixture();
        fixture.incompatibleVersion = true;
        McpClient.Failure error = assertThrows(McpClient.Failure.class,
                () -> fixture.client.discover(ENDPOINT, McpClient.MODERN, "", deadline()));
        assertEquals(McpClient.Kind.PROTOCOL, error.kind());
        assertEquals(List.of("server/discover"), fixture.methods);
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    private static final class Fixture {
        final List<String> methods = new ArrayList<>();
        final List<Map<String, String>> headers = new ArrayList<>();
        final McpClient client = new McpClient(this::exchange);
        boolean sse, timeout, changed, echo, serverRequest, incompatibleVersion;
        String paginationCursor;

        private McpClient.Response exchange(String endpoint, String verb, Map<String, String> headers,
                                            byte[] bytes, long deadline, int limit,
                                            AiExtensionHttp.ResponseReader<McpClient.Response> reader) throws IOException {
            assertEquals(ENDPOINT, endpoint);
            this.headers.add(headers);
            if ("DELETE".equals(verb)) {
                methods.add(verb);
                return respond(reader, 204, "", "", "");
            }
            try {
                String payload = new String(bytes, StandardCharsets.UTF_8);
                assertFalse(payload.contains(TOKEN));
                JSONObject request = new JSONObject(payload);
                String method = request.getString("method");
                methods.add(method);
                JSONObject params = request.optJSONObject("params");
                boolean modern = McpClient.MODERN.equals(headers.get("MCP-Protocol-Version"));
                if (modern) {
                    assertEquals(method, headers.get("Mcp-Method"));
                    JSONObject metadata = params.getJSONObject("_meta");
                    assertEquals(McpClient.MODERN, metadata.getString("io.modelcontextprotocol/protocolVersion"));
                    assertTrue(metadata.getJSONObject("io.modelcontextprotocol/clientCapabilities").length() == 0);
                }
                JSONObject result;
                String session = "";
                switch (method) {
                    case "initialize":
                        assertEquals(McpClient.LEGACY, params.getString("protocolVersion"));
                        result = new JSONObject().put("protocolVersion", McpClient.LEGACY)
                                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                                .put("serverInfo", new JSONObject().put("name", "Fixture").put("version", "1"));
                        session = SESSION;
                        break;
                    case "notifications/initialized":
                        assertFalse(request.has("id"));
                        return respond(reader, 202, "", "", "");
                    case "server/discover":
                        result = new JSONObject().put("supportedVersions", new JSONArray().put(incompatibleVersion
                                ? McpClient.LEGACY : McpClient.MODERN)).put("resultType", "complete")
                                .put("capabilities", new JSONObject().put("tools", new JSONObject()));
                        break;
                    case "tools/list":
                        paginationCursor = params == null ? "" : params.optString("cursor");
                        JSONObject query = new JSONObject().put("type", "string");
                        if (modern) query.put("x-mcp-header", "Query");
                        JSONObject schema = new JSONObject().put("type", "object").put("required", new JSONArray().put("query"))
                                .put("additionalProperties", false).put("properties", new JSONObject().put("query", query));
                        JSONObject tool = new JSONObject().put("name", "next".equals(paginationCursor) ? "second" : "lookup")
                                .put("inputSchema", schema).put("annotations", new JSONObject().put("readOnlyHint", !changed));
                        result = new JSONObject().put("tools", new JSONArray().put(tool));
                        if (modern) result.put("resultType", "complete");
                        else if (paginationCursor.isEmpty()) result.put("nextCursor", "next");
                        break;
                    case "tools/call":
                        if (timeout) throw new AiExtensionHttp.Failure(AiExtensionHttp.Kind.DEADLINE, true);
                        if (serverRequest) return respond(reader, 200, "text/event-stream", "data: {\"jsonrpc\":\"2.0\",\"id\":\"server\",\"method\":\"sampling/createMessage\"}\n\n", "");
                        String answer = echo ? "{\"nested\":\"\\u0074" + TOKEN.substring(1) + "\"}" : "answer";
                        result = new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", answer)));
                        if (modern) result.put("resultType", "complete");
                        break;
                    default: throw new AssertionError(method);
                }
                String response = new JSONObject().put("jsonrpc", "2.0").put("id", request.getString("id")).put("result", result).toString();
                if (sse && "tools/call".equals(method)) return respond(reader, 200, "text/event-stream; charset=utf-8",
                        ": keepalive\r\n\r\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\"}\n\ndata: " + response + "\n\n", session);
                return respond(reader, 200, "application/json", response, session);
            } catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
        }

        private static McpClient.Response respond(AiExtensionHttp.ResponseReader<McpClient.Response> reader, int status,
                                                    String contentType, String body, String session) throws IOException {
            return reader.read(status, name -> "Content-Type".equalsIgnoreCase(name) ? contentType
                            : "MCP-Session-Id".equalsIgnoreCase(name) ? session : "",
                    new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
