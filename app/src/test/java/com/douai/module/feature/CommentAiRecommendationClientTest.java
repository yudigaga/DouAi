package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.Dispatcher;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.SocketTimeoutException;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class CommentAiRecommendationClientTest {
    @Test public void boundedAnalysisRetainsEveryProtocolAndConfiguredResponseModeWithoutChangingProfile() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            for (boolean streaming : new boolean[]{false, true}) {
                try (MockWebServer server = new MockWebServer()) {
                    server.start();
                    AiModelSettings settings = settings(server, protocol, streaming, 60);
                    String original = settings.selected().toJson().toString();
                    server.enqueue(json(answer(protocol, "{\"questions\":[]}")));
                    server.enqueue(json(answer(protocol, "ordinary")));
                    ChatCompletionClient client = new ChatCompletionClient();

                    assertEquals("{\"questions\":[]}", client.replyStructuredAnalysisBounded(
                            "same-thread", "recommendations", settings, "", "source", 15));

                    RecordedRequest request = server.takeRequest(2, TimeUnit.SECONDS);
                    assertNotNull(request);
                    JSONObject body = new JSONObject(request.getBody().readUtf8());
                    if (AiModelConfig.GEMINI.equals(protocol)) {
                        assertTrue(request.getPath().contains(streaming ? ":streamGenerateContent" : ":generateContent"));
                    } else {
                        assertEquals(streaming, body.getBoolean("stream"));
                    }
                    if (streaming) assertEquals("text/event-stream", request.getHeader("Accept"));
                    else assertFalse("text/event-stream".equals(request.getHeader("Accept")));
                    assertEquals(original, settings.selected().toJson().toString());
                    assertEquals(60, settings.selected().timeoutSeconds);

                    assertEquals("ordinary", client.replyStructuredAnalysisBounded(
                            "same-thread", "next question", settings, "", "", 15));
                    String next = server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8();
                    assertFalse("Structured recommendation calls must not enter history", next.contains("recommendations"));
                    assertFalse("The previous document must not enter history", next.contains("source"));
                }
            }
        }
    }

    @Test public void synchronousTrickledResponseStillStopsAtTheOverallBudget() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            MockResponse slow = json(answer(AiModelConfig.CHAT, "late answer"))
                    .throttleBody(1, 1, TimeUnit.SECONDS);
            // QueueDispatcher.peek() would also throttle request-body reads.
            server.setDispatcher(new Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) { return slow; }
            });
            AiModelSettings settings = settings(server, AiModelConfig.CHAT, false, 60);
            ChatCompletionClient client = new ChatCompletionClient();
            long started = System.nanoTime();
            try {
                assertThrows(SocketTimeoutException.class, () -> client.replyStructuredAnalysisBounded(
                        "recommendations", "question", settings, "", "", 5));
            } finally { client.cancelCurrentRequest(); }
            long elapsed = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertTrue("A trickle must not extend the overall five-second budget", elapsed < 8_000);
            assertEquals(60, settings.selected().timeoutSeconds);
            assertEquals(AiModelConfig.RESPONSE_SYNC, settings.selected().responseMode);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void cancellationStopsABoundedSynchronousRecommendationWithoutRetry() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            AiModelSettings settings = settings(server, AiModelConfig.CHAT, false, 60);
            ChatCompletionClient client = new ChatCompletionClient();
            Future<String> pending = worker.submit(() -> client.replyStructuredAnalysisBounded(
                    "recommendations", "question", settings, "", "", 15));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            client.cancelCurrentRequest();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertEquals(1, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void invalidBudgetAndOversizedInputsNeverReachTheProvider() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            AiModelSettings settings = settings(server, AiModelConfig.CHAT, false, 60);
            ChatCompletionClient client = new ChatCompletionClient();
            for (int invalid : new int[]{-1, 0, 4, 121}) {
                assertThrows(IllegalArgumentException.class, () -> client.replyStructuredAnalysisBounded(
                        "recommendations", "question", settings, "", "", invalid));
            }
            assertThrows(IllegalArgumentException.class, () -> client.replyStructuredAnalysisBounded(
                    "recommendations", "q".repeat(4_001), settings, "", "", 15));
            assertThrows(IllegalArgumentException.class, () -> client.replyStructuredAnalysisBounded(
                    "recommendations", "question", settings, "", "d".repeat(32_769), 15));
            assertEquals(0, server.getRequestCount());
        }
    }

    private static AiModelSettings settings(MockWebServer server, String protocol, boolean streaming, int timeout) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "recommendations")
                .put("name", "Test").put("protocol", protocol).put("apiEndpoint", server.url("/v1").toString())
                .put("modelName", "test-model").put("allowInsecureHttp", true).put("timeoutSeconds", timeout)
                .put("contextRounds", 2).put("responseMode", streaming ? AiModelConfig.RESPONSE_STREAM : AiModelConfig.RESPONSE_SYNC));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }

    private static MockResponse json(JSONObject body) {
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString());
    }

    private static JSONObject answer(String protocol, String text) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) {
            return new JSONObject().put("status", "completed").put("output_text", text);
        }
        if (AiModelConfig.ANTHROPIC.equals(protocol)) {
            return new JSONObject().put("role", "assistant").put("stop_reason", "end_turn")
                    .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
        }
        if (AiModelConfig.GEMINI.equals(protocol)) {
            return new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", "STOP")
                    .put("content", new JSONObject().put("role", "model")
                            .put("parts", new JSONArray().put(new JSONObject().put("text", text))))));
        }
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("role", "assistant").put("content", text))));
    }
}
