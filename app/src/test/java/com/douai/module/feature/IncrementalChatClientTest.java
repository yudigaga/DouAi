package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class IncrementalChatClientTest {
    @Test public void everyProtocolPublishesRealCumulativeTextBeforeReadingTheNextEvent() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            List<String> partials = new ArrayList<>();
            String first = prefix(protocol) + delta(protocol, "你");
            byte[] bytes = (first + delta(protocol, "好 😀") + finish(protocol, "你好 😀"))
                    .getBytes(StandardCharsets.UTF_8);
            int boundary = first.getBytes(StandardCharsets.UTF_8).length;
            ByteArrayInputStream source = new ByteArrayInputStream(bytes) {
                // Later frames are not yet available, as on a live SSE connection.
                @Override public synchronized int available() { return Math.max(0, boundary - pos); }
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    if (pos >= boundary) assertFalse("first event must be delivered before reading later bytes", partials.isEmpty());
                    return super.read(buffer, offset, Math.min(1, length));
                }
            };
            assertEquals("你好 😀", ProtocolResponseStream.read(protocol, source, 4000,
                    () -> { }, ignored -> { }, partials::add));
            assertEquals(protocol, Arrays.asList("你", "你好 😀"), partials);
        }
    }

    @Test public void modelClientPublishesOnItsStreamIoThreadAndNeverCallsProviderAgain() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse(prefix(protocol) + delta(protocol, "one") + delta(protocol, " two") + finish(protocol, "one two")));
            List<String> partials = new ArrayList<>();
            AtomicReference<String> thread = new AtomicReference<>();
            ChatCompletionClient client = new ChatCompletionClient();
            assertEquals("one two", client.replyStreaming("manual", "question", Collections.emptyList(),
                    settings(server, protocol, true), "", true, "", text -> {
                        thread.set(Thread.currentThread().getName()); partials.add(text);
                    }));
            assertEquals(Arrays.asList("one", "one two"), partials);
            assertTrue(thread.get(), thread.get().startsWith("DouAi-stream-io"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void jsonFallbackIsOneAtomicCallbackInBothModesForEveryProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) for (boolean streaming : new boolean[]{false, true})
            try (MockWebServer server = new MockWebServer()) {
                server.enqueue(json(protocol, "complete answer"));
                List<String> partials = new ArrayList<>();
                assertEquals("complete answer", new ChatCompletionClient().replyStreaming("manual", "question",
                        Collections.emptyList(), settings(server, protocol, streaming), "", true, "", partials::add));
                assertEquals(Collections.singletonList("complete answer"), partials);
                assertEquals(1, server.getRequestCount());
            }
    }

    @Test public void unfinishedStreamCanPreviewButNeverEntersTheNextModelContext() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse(prefix(protocol) + delta(protocol, "abandoned partial")));
            server.enqueue(json(protocol, "fresh answer"));
            ChatCompletionClient client = new ChatCompletionClient();
            AiModelSettings profile = settings(server, protocol, true);
            List<String> partials = new ArrayList<>();
            try {
                client.replyStreaming("manual", "abandoned question", Collections.emptyList(), profile, "", true, "", partials::add);
                fail("Missing completion must fail");
            } catch (ChatCompletionClient.ReplyFailure failure) {
                assertEquals(ChatCompletionClient.ReplyFailure.Kind.INCOMPLETE, failure.kind());
            }
            assertEquals(Collections.singletonList("abandoned partial"), partials);
            server.takeRequest();
            assertEquals("fresh answer", client.reply("manual", "fresh question", profile));
            String next = server.takeRequest().getBody().readUtf8();
            assertFalse(next.contains("abandoned partial"));
            assertFalse(next.contains("abandoned question"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void stoppingFromFirstPartialSuppressesLaterCallbacksAndHistoryInEveryProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse(prefix(protocol) + delta(protocol, "cancelled partial") + delta(protocol, " late")
                    + finish(protocol, "cancelled partial late")));
            server.enqueue(json(protocol, "next answer"));
            ChatCompletionClient client = new ChatCompletionClient();
            AiModelSettings profile = settings(server, protocol, true);
            List<String> partials = new ArrayList<>();
            try {
                client.replyStreaming("manual", "cancelled question", Collections.emptyList(), profile, "", true, "", text -> {
                    partials.add(text); client.cancelCurrentRequest();
                });
                fail("Cancel from partial must win");
            } catch (CancellationException expected) { }
            assertEquals(Collections.singletonList("cancelled partial"), partials);
            server.takeRequest();
            assertEquals("next answer", client.reply("manual", "next question", profile));
            String next = server.takeRequest().getBody().readUtf8();
            assertFalse(next.contains("cancelled partial"));
            assertFalse(next.contains("cancelled question"));
        }
    }

    @Test public void responsesNeverPreviewsReasoningAndCompletedSnapshotCanCorrectDeltas() throws Exception {
        String stream = frame(new JSONObject().put("type", "response.reasoning_text.delta").put("delta", "PRIVATE_REASONING"))
                + frame(new JSONObject().put("type", "response.function_call_arguments.delta").put("delta", "PRIVATE_TOOL"))
                + delta(AiModelConfig.RESPONSES, "draft") + finish(AiModelConfig.RESPONSES, "authoritative");
        List<String> partials = new ArrayList<>();
        assertEquals("authoritative", ProtocolResponseStream.read(AiModelConfig.RESPONSES, bytes(stream), 4000,
                () -> { }, ignored -> { }, partials::add));
        assertEquals(Arrays.asList("draft", "authoritative"), partials);
    }

    @Test public void reasoningNeverBecomesAnIncrementalVisibleSnapshotInAnyProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            String reasoning;
            if (AiModelConfig.RESPONSES.equals(protocol)) reasoning = frame(new JSONObject()
                    .put("type", "response.reasoning_text.delta").put("delta", "PRIVATE_REASONING"));
            else if (AiModelConfig.ANTHROPIC.equals(protocol)) reasoning = frame(new JSONObject().put("type", "content_block_start")
                    .put("index", 1).put("content_block", new JSONObject().put("type", "thinking").put("thinking", "PRIVATE_REASONING")))
                    + frame(new JSONObject().put("type", "content_block_delta").put("index", 1)
                    .put("delta", new JSONObject().put("type", "thinking_delta").put("thinking", "PRIVATE_REASONING")))
                    + frame(new JSONObject().put("type", "content_block_stop").put("index", 1));
            else if (AiModelConfig.GEMINI.equals(protocol)) reasoning = frame(new JSONObject().put("candidates", new JSONArray()
                    .put(new JSONObject().put("index", 0).put("content", new JSONObject().put("parts", new JSONArray()
                            .put(new JSONObject().put("text", "PRIVATE_REASONING").put("thought", true)))))));
            else reasoning = frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                    .put("delta", new JSONObject().put("reasoning_content", "PRIVATE_REASONING")))));
            List<String> partials = new ArrayList<>();
            assertEquals("visible", ProtocolResponseStream.read(protocol, bytes(prefix(protocol) + reasoning
                            + delta(protocol, "visible") + finish(protocol, "visible")), 4000,
                    () -> { }, ignored -> { }, partials::add));
            assertEquals(Collections.singletonList("visible"), partials);
        }
    }

    @Test public void expiredDeadlineAfterPartialEscapesParserAndPreventsLaterSnapshots() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            AtomicBoolean expired = new AtomicBoolean();
            List<String> partials = new ArrayList<>();
            try {
                ProtocolResponseStream.read(protocol, bytes(prefix(protocol) + delta(protocol, "first")
                                + delta(protocol, " late") + finish(protocol, "first late")), 4000,
                        () -> { if (expired.get()) throw new SocketTimeoutException("deadline"); }, ignored -> { }, text -> {
                            partials.add(text); expired.set(true);
                        });
                fail("Deadline must escape parser");
            } catch (SocketTimeoutException expected) { assertEquals("deadline", expected.getMessage()); }
            assertEquals(Collections.singletonList("first"), partials);
        }
    }

    @Test public void callbackGuardFencesCancellationBeforePublishingAnyMoreText() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) {
            AtomicBoolean cancelled = new AtomicBoolean();
            List<String> partials = new ArrayList<>();
            try {
                ProtocolResponseStream.read(protocol, bytes(prefix(protocol) + delta(protocol, "first")
                        + delta(protocol, " late") + finish(protocol, "first late")), 4000,
                        () -> { if (cancelled.get()) throw new CancellationException(); }, ignored -> { }, text -> {
                            partials.add(text); cancelled.set(true);
                        });
                fail("Cancellation must escape parser");
            } catch (CancellationException expected) { }
            assertEquals(Collections.singletonList("first"), partials);
        }
    }

    private static AiModelSettings settings(MockWebServer server, String protocol, boolean streaming) throws Exception {
        AiModelConfig model = AiModelConfig.fromJson(new JSONObject().put("id", "fixture").put("name", "Fixture")
                .put("protocol", protocol).put("apiEndpoint", server.url("/v1").toString()).put("modelName", "fixture-model")
                .put("apiKey", "fixture-key").put("allowInsecureHttp", true).put("contextRounds", 2)
                .put("timeoutSeconds", 5).put("responseMode", streaming ? "streaming" : "non_streaming"));
        return new AiModelSettings(Collections.singletonList(model), model.id);
    }

    private static String prefix(String protocol) throws Exception {
        if (!AiModelConfig.ANTHROPIC.equals(protocol)) return "";
        return frame(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("role", "assistant")))
                + frame(new JSONObject().put("type", "content_block_start").put("index", 0)
                .put("content_block", new JSONObject().put("type", "text").put("text", "")));
    }

    private static String delta(String protocol, String value) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) return frame(new JSONObject().put("type", "response.output_text.delta").put("delta", value));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return frame(new JSONObject().put("type", "content_block_delta").put("index", 0)
                .put("delta", new JSONObject().put("type", "text_delta").put("text", value)));
        if (AiModelConfig.GEMINI.equals(protocol)) return frame(new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("index", 0).put("content", new JSONObject().put("role", "model").put("parts", new JSONArray().put(new JSONObject().put("text", value)))))));
        return frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0)
                .put("delta", new JSONObject().put("content", value)))));
    }

    private static String finish(String protocol, String value) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) return frame(new JSONObject().put("type", "response.completed")
                .put("response", new JSONObject().put("status", "completed").put("output_text", value)));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return frame(new JSONObject().put("type", "content_block_stop").put("index", 0))
                + frame(new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", "end_turn")))
                + frame(new JSONObject().put("type", "message_stop"));
        if (AiModelConfig.GEMINI.equals(protocol)) return frame(new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("index", 0).put("finishReason", "STOP"))));
        return frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "stop")))) + "data: [DONE]\n\n";
    }

    private static MockResponse json(String protocol, String value) throws Exception {
        JSONObject body;
        if (AiModelConfig.RESPONSES.equals(protocol)) body = new JSONObject().put("status", "completed").put("output_text", value);
        else if (AiModelConfig.ANTHROPIC.equals(protocol)) body = new JSONObject().put("stop_reason", "end_turn")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", value)));
        else if (AiModelConfig.GEMINI.equals(protocol)) body = new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("finishReason", "STOP").put("content", new JSONObject().put("parts", new JSONArray().put(new JSONObject().put("text", value))))));
        else body = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("finish_reason", "stop")
                .put("message", new JSONObject().put("content", value))));
        return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString());
    }

    private static MockResponse sse(String body) { return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body); }
    private static String frame(JSONObject value) { return "data: " + value + "\n\n"; }
    private static ByteArrayInputStream bytes(String value) { return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)); }
}
