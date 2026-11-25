package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class IsolatedTextRequestTest {
    private static final String SYSTEM = "fixed system role";
    private static final String INPUT = "one user turn";
    private static final String KEY = "fixture-provider-secret";
    private static final String HEADER_KEY = "fixture-gateway-secret";
    private static final String HTTPS = "https://model.example.test";

    @Test public void isolatedReplyKeepsItsBoundariesOnlyWhenTheCallerOptsIn() throws Exception {
        String raw = "\n  {\"value\":1}\r\n\t ";
        String body = new JSONObject().put("output_text", raw).toString();
        AiModelSettings profile = settings(AiModelConfig.RESPONSES, HTTPS);
        assertEquals(raw.trim(), execute(new Fixture(body).request(), profile, INPUT));
        assertEquals(raw, execute(new Fixture(body).request(true), profile, INPUT));
    }

    @Test public void allProtocolsSendOnlyTheSelectedProfileAndOneTextTurn() throws Exception {
        String[][] formats = {
                {AiModelConfig.CHAT, "/chat/completions", "Authorization", "Bearer " + KEY,
                        "messages", "max_tokens", "{\"choices\":[{\"message\":{\"content\":\"done\"}}]}"},
                {AiModelConfig.RESPONSES, "/responses", "Authorization", "Bearer " + KEY,
                        "input", "max_output_tokens", "{\"output_text\":\"done\"}"},
                {AiModelConfig.ANTHROPIC, "/messages", "x-api-key", KEY,
                        "messages", "max_tokens", "{\"content\":[{\"type\":\"text\",\"text\":\"done\"}]}"},
                {AiModelConfig.GEMINI, "/models/selected-model:generateContent", "x-goog-api-key", KEY,
                        "contents", "maxOutputTokens", "{\"candidates\":[{\"content\":{\"parts\":[{\"text\":\"done\"}]}}]}"}
        };
        for (String[] format : formats) {
            Fixture fixture = new Fixture(format[6]);
            AiModelSettings settings = settings(format[0], HTTPS);
            assertEquals("done", execute(fixture.request(), settings, INPUT));
            RecordingConnection connection = fixture.connection;
            assertEquals(1, fixture.opens.get());
            assertEquals("POST", connection.getRequestMethod());
            assertEquals(HTTPS + format[1], connection.getURL().toString());
            assertFalse(connection.getInstanceFollowRedirects());
            assertEquals(format[3], connection.getRequestProperty(format[2]));
            assertEquals(HEADER_KEY, connection.getRequestProperty("api-key"));
            assertNull(connection.getRequestProperty("X-Legacy-Header"));
            String payload = connection.output.toString(StandardCharsets.UTF_8.name());
            JSONObject body = new JSONObject(payload);
            assertFalse(payload.contains(KEY));
            assertFalse(payload.contains(HEADER_KEY));
            assertFalse(payload.contains("legacy"));
            assertFalse(body.has("tools"));
            assertFalse(body.has("previous_response_id"));
            assertFalse(body.has("toolConfig"));
            JSONArray turns = body.getJSONArray(format[4]);
            String system;
            int userIndex = 0;
            JSONObject limits = body;
            switch (format[0]) {
                case AiModelConfig.CHAT:
                    assertEquals("system", turns.getJSONObject(0).getString("role"));
                    system = turns.getJSONObject(0).getString("content");
                    userIndex = 1;
                    break;
                case AiModelConfig.RESPONSES:
                    system = body.getString("instructions");
                    assertFalse(body.getBoolean("store"));
                    break;
                case AiModelConfig.ANTHROPIC:
                    system = body.getString("system");
                    assertEquals("2023-06-01", connection.getRequestProperty("anthropic-version"));
                    break;
                default:
                    system = body.getJSONObject("systemInstruction").getJSONArray("parts")
                            .getJSONObject(0).getString("text");
                    limits = body.getJSONObject("generationConfig");
            }
            assertEquals(SYSTEM, system);
            assertEquals(1 + userIndex, turns.length());
            JSONObject user = turns.getJSONObject(userIndex);
            assertEquals("user", user.getString("role"));
            if (AiModelConfig.GEMINI.equals(format[0])) {
                assertEquals(INPUT, user.getJSONArray("parts").getJSONObject(0).getString("text"));
            } else {
                assertEquals(INPUT, user.getString("content"));
                assertEquals("selected-model", body.getString("model"));
            }
            assertEquals(120, limits.getInt(format[5]));
            assertEquals("active", settings.activeId);
        }
    }

    @Test public void expiredDeadlineAndPreCancelledRequestNeverOpenConnection() throws Exception {
        Fixture fixture = new Fixture("{}");
        AiModelSettings settings = settings(AiModelConfig.CHAT, HTTPS);
        ChatCompletionClient.IsolatedTextRequest expired = fixture.request();
        assertThrows(SocketTimeoutException.class, () -> expired.execute(settings, "selected",
                SYSTEM, INPUT, System.nanoTime() - 1, 512));
        ChatCompletionClient.IsolatedTextRequest cancelled = fixture.request();
        cancelled.cancel();
        assertThrows(CancellationException.class, () -> execute(cancelled, settings, INPUT));
        assertEquals(0, fixture.opens.get());
    }

    @Test public void noFallbackForMissingModelOrInsecureEndpoint() throws Exception {
        Fixture fixture = new Fixture("{}");
        AiModelSettings settings = settings(AiModelConfig.CHAT, "http://model.example.test");
        assertThrows(IllegalArgumentException.class, () -> execute(fixture.request(), settings, INPUT));
        assertThrows(IllegalStateException.class, () -> fixture.request().execute(settings, "deleted",
                SYSTEM, INPUT, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 512));
        assertEquals(0, fixture.opens.get());
    }

    @Test public void blocksCredentialsBeforeSendingTextAndBeforeReturningProviderEcho() throws Exception {
        Fixture fixture = new Fixture("{\"output_text\":\"" + HEADER_KEY + "\"}");
        AiModelSettings settings = settings(AiModelConfig.RESPONSES, HTTPS);
        IllegalArgumentException input = assertThrows(IllegalArgumentException.class,
                () -> execute(fixture.request(), settings, "action with " + KEY));
        assertFalse(input.toString().contains(KEY));
        assertEquals(0, fixture.opens.get());
        IOException response = assertThrows(IOException.class, () -> execute(fixture.request(), settings, INPUT));
        assertFalse(response.toString().contains(HEADER_KEY));
        assertNull(response.getCause());
        assertEquals(1, fixture.opens.get());
    }

    @Test public void blocksSecretsDecodedByADownstreamJsonParser() throws Exception {
        String draft = "{\"instructions\":\"\\u0066" + HEADER_KEY.substring(1) + "\"}";
        assertEquals(HEADER_KEY, new JSONObject(draft).getString("instructions"));
        Fixture fixture = new Fixture(new JSONObject().put("output_text", draft).toString());
        assertThrows(IOException.class, () -> execute(fixture.request(), settings(AiModelConfig.RESPONSES, HTTPS), INPUT));
        assertEquals(1, fixture.opens.get());
    }

    @Test public void parentTimeBudgetFencesLatePaidResponseWithoutResending() throws Exception {
        Fixture fixture = new Fixture("{\"output_text\":\"late\"}");
        fixture.holdResponse = true;
        ChatCompletionClient.IsolatedTextRequest request = fixture.request();
        AiModelSettings settings = settings(AiModelConfig.RESPONSES, HTTPS);
        long started = System.nanoTime();
        try {
            assertThrows(SocketTimeoutException.class, () -> request.execute(settings,
                    "selected", SYSTEM, INPUT, started + TimeUnit.MILLISECONDS.toNanos(600), 512));
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 2200);
            assertEquals(1, fixture.opens.get());
            assertTrue(fixture.connection.output.size() > 0);
            assertTrue(fixture.connection.disconnected.await(3, TimeUnit.SECONDS));
            assertThrows(IllegalStateException.class, () -> execute(request, settings, INPUT));
            assertEquals(1, fixture.opens.get());
        } finally {
            fixture.responseGate.countDown();
        }
    }

    @Test public void activeCancellationFencesResponseAndReleasesConnection() throws Exception {
        Fixture fixture = new Fixture("{\"output_text\":\"late\"}");
        fixture.holdResponse = true;
        ChatCompletionClient.IsolatedTextRequest request = fixture.request();
        AiModelSettings settings = settings(AiModelConfig.RESPONSES, HTTPS);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<String> result = caller.submit(() -> execute(request, settings, INPUT));
            assertTrue(fixture.responsePending.await(2, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException stopped = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
            assertTrue(stopped.getCause() instanceof CancellationException);
            assertTrue(fixture.connection.disconnected.await(2, TimeUnit.SECONDS));
            assertEquals(1, fixture.opens.get());
        } finally {
            fixture.responseGate.countDown();
            caller.shutdownNow();
        }
    }

    private static String execute(ChatCompletionClient.IsolatedTextRequest request, AiModelSettings settings, String text) throws Exception {
        return request.execute(settings, "selected", SYSTEM, text, System.nanoTime() + TimeUnit.SECONDS.toNanos(5), 512);
    }

    private static AiModelSettings settings(String protocol, String endpoint) throws Exception {
        AiModelConfig selected = AiModelConfig.fromJson(new JSONObject()
                .put("id", "selected").put("name", "Selected profile").put("protocol", protocol)
                .put("apiEndpoint", endpoint).put("apiKey", KEY).put("modelName", "selected-model")
                .put("allowInsecureHttp", true).put("systemPrompt", "legacy system prompt")
                .put("maxTokensEnabled", true).put("maxTokens", 120).put("contextRounds", 40)
                .put("customParameters", "{\"tools\":[{}],\"previous_response_id\":\"legacy-context\",\"toolConfig\":{}}")
                .put("customHeaders", new JSONObject().put("api-key", HEADER_KEY)
                        .put("X-Legacy-Header", "legacy header").toString()));
        AiModelConfig active = AiModelConfig.fromJson(new JSONObject().put("id", "active")
                .put("name", "Active profile").put("apiEndpoint", "https://ignored.example.test"));
        return new AiModelSettings(List.of(active, selected), "active");
    }

    private static final class Fixture {
        final AtomicInteger opens = new AtomicInteger();
        final CountDownLatch responseGate = new CountDownLatch(1);
        final CountDownLatch responsePending = new CountDownLatch(1);
        final byte[] response;
        boolean holdResponse;
        volatile RecordingConnection connection;

        Fixture(String response) { this.response = response.getBytes(StandardCharsets.UTF_8); }

        ChatCompletionClient.IsolatedTextRequest request() {
            return new ChatCompletionClient.IsolatedTextRequest(this::open);
        }

        ChatCompletionClient.IsolatedTextRequest request(boolean preserveReply) {
            return new ChatCompletionClient.IsolatedTextRequest(this::open, preserveReply);
        }

        private HttpURLConnection open(URL url) {
            opens.incrementAndGet();
            connection = new RecordingConnection(url, response, responsePending, holdResponse ? responseGate : null);
            return connection;
        }
    }

    private static final class RecordingConnection extends HttpURLConnection {
        final ByteArrayOutputStream output = new ByteArrayOutputStream();
        final CountDownLatch disconnected = new CountDownLatch(1);
        final byte[] response;
        final CountDownLatch responsePending;
        final CountDownLatch responseGate;
        final java.util.Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        RecordingConnection(URL url, byte[] response, CountDownLatch responsePending, CountDownLatch responseGate) {
            super(url);
            this.response = response;
            this.responsePending = responsePending;
            this.responseGate = responseGate;
        }

        @Override public void connect() { }
        @Override public void disconnect() { disconnected.countDown(); }
        @Override public boolean usingProxy() { return false; }
        @Override public void setRequestProperty(String key, String value) { headers.put(key, value); }
        @Override public String getRequestProperty(String key) { return headers.get(key); }
        @Override public ByteArrayOutputStream getOutputStream() { return output; }
        @Override public String getContentType() { return "application/json"; }

        @Override public int getResponseCode() throws IOException {
            responsePending.countDown();
            try {
                if (responseGate != null) responseGate.await(4, TimeUnit.SECONDS);
                return 200;
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException("connection interrupted");
            }
        }

        @Override public InputStream getInputStream() { return new ByteArrayInputStream(response); }
    }
}
