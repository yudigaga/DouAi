package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ManualAiSearchClientTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void jsonToolsInEveryProtocolExecuteAndReplayNativeRecordsBeforeFinalText() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(protocol, "call-1", "PRIVATE_QUERY")));
            server.enqueue(json(answer(protocol, "verified answer")));
            List<String> partials = new ArrayList<>();
            AtomicInteger searches = new AtomicInteger();
            String result = reply(new ChatCompletionClient(), settings(server, protocol, false), search(searches), partials::add);
            assertEquals(protocol, "verified answer", result);
            assertEquals(protocol, 1, searches.get());
            JSONObject first = new JSONObject(server.takeRequest().getBody().readUtf8());
            assertTrue(protocol, first.has("tools"));
            String followup = server.takeRequest().getBody().readUtf8();
            assertTrue(protocol, followup.contains("call-1") || AiModelConfig.GEMINI.equals(protocol));
            assertTrue(protocol, followup.contains("source title"));
            assertTrue(protocol, followup.contains("source summary"));
            assertTrue(protocol, followup.contains("REPLAY_ONLY"));
            assertFalse(protocol, String.join("", partials).contains("PRIVATE_QUERY"));
            assertFalse(protocol, String.join("", partials).contains("REPLAY_ONLY"));
            assertEquals(protocol, 2, server.getRequestCount());
        }
    }

    @Test public void streamedArgumentsStayPrivateAndNativeSignaturesSurviveEveryProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(sse(toolStream(protocol)));
            server.enqueue(sse(answerStream(protocol)));
            AtomicInteger searches = new AtomicInteger();
            List<String> partials = new ArrayList<>();
            assertEquals(protocol, "verified answer", reply(new ChatCompletionClient(), settings(server, protocol, true), search(searches), partials::add));
            assertEquals(protocol, 1, searches.get());
            server.takeRequest();
            String followup = server.takeRequest().getBody().readUtf8();
            assertTrue(protocol, followup.contains("REPLAY_ONLY"));
            assertTrue(protocol, followup.contains("source summary"));
            assertTrue(protocol, partials.contains("verified"));
            assertEquals(protocol, "verified answer", partials.get(partials.size() - 1));
            assertFalse(protocol, String.join("", partials).contains("PRIVATE_QUERY"));
            assertFalse(protocol, String.join("", partials).contains("REPLAY_ONLY"));
        }
    }

    @Test public void onlyTheFinalAnswerEntersPersistentManualHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AiModelSettings profile = settings(server, AiModelConfig.CHAT, false);
            TestChatMemory memory = new TestChatMemory(temporary.newFolder(), profile.selected());
            ChatCompletionClient client = new ChatCompletionClient(memory, ignored -> { });
            JSONObject tool = tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY");
            tool.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("content", "provisional search answer");
            server.enqueue(json(tool));
            server.enqueue(json(answer(AiModelConfig.CHAT, "final answer")));
            assertEquals("final answer", reply(client, profile, search(new AtomicInteger()), ignored -> { }));
            assertEquals(1, memory.read().size());
            assertEquals("question", memory.read().get(0).prompt);
            assertEquals("final answer", memory.read().get(0).answer);
            assertFalse(memory.read().get(0).answer.contains("provisional"));
        }
    }

    @Test public void toolsFromAUserRoleNeverReachTheHostInAnyProtocol() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            JSONObject invalid = tool(protocol, "one", "PRIVATE_QUERY");
            if (AiModelConfig.CHAT.equals(protocol)) invalid.getJSONArray("choices").getJSONObject(0).getJSONObject("message").put("role", "user");
            else if (AiModelConfig.RESPONSES.equals(protocol)) invalid.getJSONArray("output").getJSONObject(1).put("role", "user");
            else if (AiModelConfig.ANTHROPIC.equals(protocol)) invalid.put("role", "user");
            else invalid.getJSONArray("candidates").getJSONObject(0).getJSONObject("content").put("role", "user");
            server.enqueue(json(invalid)); server.enqueue(json(answer(protocol, "must not succeed")));
            AtomicInteger searches = new AtomicInteger();
            try {
                reply(new ChatCompletionClient(), settings(server, protocol, false), search(searches), ignored -> { });
                fail(protocol + ": a user-role tool record was executed");
            } catch (ChatCompletionClient.ReplyFailure expected) { assertEquals(protocol, ChatCompletionClient.ReplyFailure.Kind.FORMAT, expected.kind()); }
            assertEquals(protocol, 0, searches.get());
            assertEquals(protocol, 1, server.getRequestCount());
        }
    }

    @Test public void clearDuringSearchFencesPersistentFinalHistory() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AiModelSettings profile = settings(server, AiModelConfig.CHAT, false);
            TestChatMemory memory = new TestChatMemory(temporary.newFolder(), profile.selected());
            ChatCompletionClient client = new ChatCompletionClient(memory, ignored -> { });
            server.enqueue(json(tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY")));
            server.enqueue(json(answer(AiModelConfig.CHAT, "final answer")));
            DouyinSearch host = (query, limit) -> new DouyinSearch.Request() {
                @Override public DouyinSearch.Result run(long deadline) { client.clearHistory(); return result(query); }
                @Override public void cancel() { }
            };
            assertEquals("final answer", reply(client, profile, host, ignored -> { }));
            assertTrue(memory.read().isEmpty());
        }
    }

    @Test public void cancellationBetweenHostPrepareAndRegistrationCancelsPreparedRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY")));
            ChatCompletionClient client = new ChatCompletionClient();
            AtomicInteger cancelCalls = new AtomicInteger(), runCalls = new AtomicInteger();
            CountDownLatch cancellationObserved = new CountDownLatch(1);
            DouyinSearch host = (query, limit) -> {
                client.cancelCurrentRequest();
                return new DouyinSearch.Request() {
                    @Override public DouyinSearch.Result run(long deadline) { runCalls.incrementAndGet(); return result(query); }
                    @Override public void cancel() { cancelCalls.incrementAndGet(); cancellationObserved.countDown(); }
                };
            };
            try {
                reply(client, settings(server, AiModelConfig.CHAT, false), host, ignored -> { });
                fail("Cancellation during prepare must abort the operation");
            } catch (CancellationException expected) { }
            assertTrue("The prepared request is cancelled after prepare returns", cancellationObserved.await(2, TimeUnit.SECONDS));
            assertEquals(0, runCalls.get());
            assertEquals(1, cancelCalls.get());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void geminiFunctionCallIsExecutedAndItsFullThoughtPartIsReplayed() throws Exception {
        for (boolean streaming : new boolean[]{false, true}) try (MockWebServer server = new MockWebServer()) {
            JSONObject call = tool(AiModelConfig.GEMINI, "call-1", "PRIVATE_QUERY");
            JSONObject part = call.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                    .getJSONArray("parts").getJSONObject(0).put("thought", true);
            part.put("thoughtSignature", "SIGNED_THOUGHT");
            part.getJSONObject("functionCall").put("id", "call-1");
            server.enqueue(streaming ? sse(geminiToolStream(call)) : json(call));
            server.enqueue(streaming ? sse(answerStream(AiModelConfig.GEMINI)) : json(answer(AiModelConfig.GEMINI, "verified answer")));
            AtomicInteger searches = new AtomicInteger();
            assertEquals("verified answer", reply(new ChatCompletionClient(), settings(server, AiModelConfig.GEMINI, streaming),
                    search(searches), ignored -> { }));
            assertEquals(1, searches.get());
            server.takeRequest();
            JSONObject followup = new JSONObject(server.takeRequest().getBody().readUtf8());
            JSONObject historyPart = followup.getJSONArray("contents")
                    .getJSONObject(1).getJSONArray("parts").getJSONObject(0);
            assertTrue(historyPart.getBoolean("thought"));
            assertEquals("SIGNED_THOUGHT", historyPart.getString("thoughtSignature"));
            assertEquals("call-1", followup.getJSONArray("contents").getJSONObject(2).getJSONArray("parts")
                    .getJSONObject(0).getJSONObject("functionResponse").getString("id"));
        }
    }

    @Test public void missingHostCapabilityUsesTheExistingTextTransportWithoutTools() throws Exception {
        for (String protocol : AiModelConfig.PROTOCOLS) try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(answer(protocol, "ordinary answer")));
            assertEquals("ordinary answer", reply(new ChatCompletionClient(), settings(server, protocol, false), null, ignored -> { }));
            assertFalse(new JSONObject(server.takeRequest().getBody().readUtf8()).has("tools"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void geminiParametersStayWithinItsSchemaRatherThanRawJsonSchema() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(answer(AiModelConfig.GEMINI, "ordinary answer")));
            assertEquals("ordinary answer", reply(new ChatCompletionClient(), settings(server, AiModelConfig.GEMINI, false), search(new AtomicInteger()), ignored -> { }));
            JSONObject parameters = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("tools").getJSONObject(0)
                    .getJSONArray("functionDeclarations").getJSONObject(0).getJSONObject("parameters");
            assertFalse("Gemini Schema has no additionalProperties field", parameters.has("additionalProperties"));
            assertEquals("string", parameters.getJSONObject("properties").getJSONObject("query").getString("type"));
        }
    }

    @Test public void repeatedQueryUsesOneSearchButEachCallGetsItsOwnResult() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY")));
            server.enqueue(json(tool(AiModelConfig.CHAT, "two", "PRIVATE_QUERY")));
            server.enqueue(json(answer(AiModelConfig.CHAT, "final answer")));
            AtomicInteger searches = new AtomicInteger();
            assertEquals("final answer", reply(new ChatCompletionClient(), settings(server, AiModelConfig.CHAT, false), search(searches), ignored -> { }));
            assertEquals(1, searches.get());
            server.takeRequest(); server.takeRequest();
            String third = server.takeRequest().getBody().readUtf8();
            assertTrue(third.contains("\"tool_call_id\":\"two\""));
            assertTrue(third.contains("source title"));
            assertTrue(third.contains("\"tool_choice\":\"none\""));
        }
    }

    @Test public void thirdModelToolRequestCannotStartAThirdSearchOrAFourthModelRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int i = 1; i <= 3; i++) server.enqueue(json(tool(AiModelConfig.CHAT, "call-" + i, "query-" + i)));
            AtomicInteger searches = new AtomicInteger();
            try {
                reply(new ChatCompletionClient(), settings(server, AiModelConfig.CHAT, false), search(searches), ignored -> { });
                fail("A third tool round must fail");
            } catch (ChatCompletionClient.ReplyFailure expected) {
                assertEquals(ChatCompletionClient.ReplyFailure.Kind.INCOMPLETE, expected.kind());
            }
            assertEquals(2, searches.get());
            assertEquals(3, server.getRequestCount());
            server.takeRequest(); server.takeRequest();
            assertEquals("none", new JSONObject(server.takeRequest().getBody().readUtf8()).getString("tool_choice"));
        }
    }

    @Test public void reusedCallIdWithDifferentArgumentsNeverExecutesAgain() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(AiModelConfig.CHAT, "same", "first query")));
            server.enqueue(json(tool(AiModelConfig.CHAT, "same", "different query")));
            AtomicInteger searches = new AtomicInteger();
            try {
                reply(new ChatCompletionClient(), settings(server, AiModelConfig.CHAT, false), search(searches), ignored -> { });
                fail("Conflicting correlation ID must fail");
            } catch (ChatCompletionClient.ReplyFailure expected) {
                assertEquals(ChatCompletionClient.ReplyFailure.Kind.FORMAT, expected.kind());
            }
            assertEquals(1, searches.get());
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void cancellationDuringHostSearchCancelsItsHandleAndPreventsNextRound() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY")));
            ChatCompletionClient client = new ChatCompletionClient();
            CountDownLatch started = new CountDownLatch(1), released = new CountDownLatch(1);
            AtomicInteger cancels = new AtomicInteger();
            DouyinSearch host = (query, limit) -> new DouyinSearch.Request() {
                @Override public DouyinSearch.Result run(long deadline) throws Exception { started.countDown(); released.await(); return result(query); }
                @Override public void cancel() { cancels.incrementAndGet(); released.countDown(); }
            };
            ExecutorService worker = Executors.newSingleThreadExecutor();
            try {
                Future<?> pending = worker.submit(() -> {
                    try { reply(client, settings(server, AiModelConfig.CHAT, false), host, ignored -> { }); fail("Cancelled reply returned"); }
                    catch (CancellationException expected) { }
                    catch (Exception failure) { throw new AssertionError(failure); }
                });
                assertTrue(started.await(3, TimeUnit.SECONDS));
                client.cancelCurrentRequest();
                pending.get(3, TimeUnit.SECONDS);
                assertTrue(cancels.get() >= 1);
                assertEquals(1, server.getRequestCount());
            } finally { released.countDown(); worker.shutdownNow(); }
        }
    }

    @Test public void modelAndSearchShareOneDeadlineEvenWhenConfiguredNonStreaming() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(json(tool(AiModelConfig.CHAT, "one", "PRIVATE_QUERY")).setBodyDelay(2600, TimeUnit.MILLISECONDS));
            server.enqueue(json(answer(AiModelConfig.CHAT, "must not finish")));
            DouyinSearch slow = (query, limit) -> new DouyinSearch.Request() {
                @Override public DouyinSearch.Result run(long deadline) throws Exception { Thread.sleep(2700); return result(query); }
                @Override public void cancel() { }
            };
            long started = System.nanoTime();
            try {
                reply(new ChatCompletionClient(), settings(server, AiModelConfig.CHAT, false), slow, ignored -> { });
                fail("Each round must not receive a fresh timeout");
            } catch (SocketTimeoutException expected) { }
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 6500);
            assertEquals(1, server.getRequestCount());
        }
    }

    private static String reply(ChatCompletionClient client, AiModelSettings settings, DouyinSearch search,
                                Consumer<String> partial) throws Exception {
        Method method;
        try { method = ChatCompletionClient.class.getMethod("replyWithSearch", String.class, String.class, List.class,
                AiModelSettings.class, String.class, boolean.class, String.class, DouyinSearch.class, Consumer.class); }
        catch (NoSuchMethodException absent) { fail("replyWithSearch is missing: tool requests cannot execute"); return ""; }
        try { return (String) method.invoke(client, "manual", "question", Collections.emptyList(), settings,
                "analyze", true, "document", search, partial); }
        catch (InvocationTargetException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof Error) throw (Error) cause;
            throw new AssertionError(cause);
        }
    }

    private static DouyinSearch search(AtomicInteger count) {
        return (query, limit) -> new DouyinSearch.Request() {
            @Override public DouyinSearch.Result run(long deadline) { assertEquals(5, limit); count.incrementAndGet(); return result(query); }
            @Override public void cancel() { }
        };
    }

    private static DouyinSearch.Result result(String query) {
        return new DouyinSearch.Result(query, Collections.singletonList(new DouyinSearch.Item(
                "123", "source title", "source author", "https://www.douyin.com/video/123", "source summary")));
    }

    private static AiModelSettings settings(MockWebServer server, String protocol, boolean streaming) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "manual-search").put("name", "Test")
                .put("protocol", protocol).put("apiEndpoint", server.url("/v1").toString()).put("modelName", "test-model")
                .put("allowInsecureHttp", true).put("contextRounds", 2).put("timeoutSeconds", 5)
                .put("responseMode", streaming ? "streaming" : "non_streaming"));
        return new AiModelSettings(Collections.singletonList(config), config.id);
    }

    private static JSONObject tool(String protocol, String id, String query) throws Exception {
        JSONObject args = new JSONObject().put("query", query);
        if (AiModelConfig.RESPONSES.equals(protocol)) return new JSONObject().put("status", "completed").put("output", new JSONArray()
                .put(new JSONObject().put("type", "reasoning").put("id", "rs-test").put("encrypted_content", "REPLAY_ONLY"))
                .put(new JSONObject().put("type", "function_call").put("id", "fc-" + id).put("call_id", id)
                        .put("name", "douyin_search").put("arguments", args.toString()).put("status", "completed")));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return new JSONObject().put("role", "assistant").put("stop_reason", "tool_use")
                .put("content", new JSONArray().put(new JSONObject().put("type", "thinking").put("thinking", "private thought").put("signature", "REPLAY_ONLY"))
                        .put(new JSONObject().put("type", "tool_use").put("id", id).put("name", "douyin_search").put("input", args)));
        if (AiModelConfig.GEMINI.equals(protocol)) return new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("index", 0).put("finishReason", "STOP").put("content", new JSONObject().put("role", "model").put("parts", new JSONArray()
                        .put(new JSONObject().put("thoughtSignature", "REPLAY_ONLY").put("functionCall", new JSONObject()
                                .put("name", "douyin_search").put("args", args)))))));
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "tool_calls")
                .put("message", new JSONObject().put("role", "assistant").put("content", JSONObject.NULL).put("reasoning_content", "REPLAY_ONLY")
                        .put("tool_calls", new JSONArray().put(new JSONObject().put("id", id).put("type", "function")
                                .put("function", new JSONObject().put("name", "douyin_search").put("arguments", args.toString())))))));
    }

    private static JSONObject answer(String protocol, String text) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) return new JSONObject().put("status", "completed").put("output_text", text)
                .put("output", new JSONArray().put(new JSONObject().put("type", "message").put("role", "assistant")
                        .put("content", new JSONArray().put(new JSONObject().put("type", "output_text").put("text", text)))));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return new JSONObject().put("role", "assistant").put("stop_reason", "end_turn")
                .put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", text)));
        if (AiModelConfig.GEMINI.equals(protocol)) return new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("index", 0)
                .put("finishReason", "STOP").put("content", new JSONObject().put("role", "model")
                        .put("parts", new JSONArray().put(new JSONObject().put("text", text))))));
        return new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "stop")
                .put("message", new JSONObject().put("role", "assistant").put("content", text))));
    }

    private static String toolStream(String protocol) throws Exception {
        JSONObject body = tool(protocol, "call-1", "PRIVATE_QUERY");
        if (AiModelConfig.RESPONSES.equals(protocol)) return frame(new JSONObject().put("type", "response.function_call_arguments.delta")
                .put("output_index", 1).put("delta", "PRIVATE_QUERY")) + frame(new JSONObject().put("type", "response.completed").put("response", body));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return frame(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("role", "assistant")))
                + frame(new JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", body.getJSONArray("content").getJSONObject(0)))
                + frame(new JSONObject().put("type", "content_block_stop").put("index", 0))
                + frame(new JSONObject().put("type", "content_block_start").put("index", 1).put("content_block", new JSONObject().put("type", "tool_use")
                        .put("id", "call-1").put("name", "douyin_search").put("input", new JSONObject())))
                + frame(new JSONObject().put("type", "content_block_delta").put("index", 1).put("delta", new JSONObject().put("type", "input_json_delta").put("partial_json", "{\"query\":\"PRIVATE_")))
                + frame(new JSONObject().put("type", "content_block_delta").put("index", 1).put("delta", new JSONObject().put("type", "input_json_delta").put("partial_json", "QUERY\"}")))
                + frame(new JSONObject().put("type", "content_block_stop").put("index", 1))
                + frame(new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", "tool_use")))
                + frame(new JSONObject().put("type", "message_stop"));
        if (AiModelConfig.GEMINI.equals(protocol)) return frame(body);
        JSONObject call = body.getJSONArray("choices").getJSONObject(0).getJSONObject("message").getJSONArray("tool_calls").getJSONObject(0);
        call.getJSONObject("function").put("arguments", "{\"query\":\"PRIVATE_"); call.put("index", 0);
        return chatDelta(new JSONObject().put("reasoning_content", "REPLAY_ONLY").put("tool_calls", new JSONArray().put(call)))
                + chatDelta(new JSONObject().put("tool_calls", new JSONArray().put(new JSONObject().put("index", 0)
                        .put("function", new JSONObject().put("arguments", "QUERY\"}")))))
                + frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "tool_calls")))) + "data: [DONE]\n\n";
    }

    private static String answerStream(String protocol) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) return frame(new JSONObject().put("type", "response.output_text.delta").put("delta", "verified"))
                + frame(new JSONObject().put("type", "response.output_text.delta").put("delta", " answer"))
                + frame(new JSONObject().put("type", "response.completed").put("response", answer(protocol, "verified answer")));
        if (AiModelConfig.ANTHROPIC.equals(protocol)) return frame(new JSONObject().put("type", "message_start").put("message", new JSONObject().put("role", "assistant")))
                + frame(new JSONObject().put("type", "content_block_start").put("index", 0).put("content_block", new JSONObject().put("type", "text").put("text", "")))
                + frame(new JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", new JSONObject().put("type", "text_delta").put("text", "verified")))
                + frame(new JSONObject().put("type", "content_block_delta").put("index", 0).put("delta", new JSONObject().put("type", "text_delta").put("text", " answer")))
                + frame(new JSONObject().put("type", "content_block_stop").put("index", 0))
                + frame(new JSONObject().put("type", "message_delta").put("delta", new JSONObject().put("stop_reason", "end_turn")))
                + frame(new JSONObject().put("type", "message_stop"));
        if (AiModelConfig.GEMINI.equals(protocol)) {
            JSONObject first = answer(protocol, "verified");
            first.getJSONArray("candidates").getJSONObject(0).remove("finishReason");
            return frame(first) + frame(answer(protocol, " answer"));
        }
        return chatDelta(new JSONObject().put("content", "verified")) + chatDelta(new JSONObject().put("content", " answer"))
                + frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("finish_reason", "stop")))) + "data: [DONE]\n\n";
    }

    private static String geminiToolStream(JSONObject response) throws Exception {
        JSONObject source = response.getJSONArray("candidates").getJSONObject(0);
        JSONObject content = source.getJSONObject("content");
        JSONObject contentFrame = new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("index", source.optInt("index", 0)).put("content", content)));
        JSONObject finishFrame = new JSONObject().put("candidates", new JSONArray().put(new JSONObject()
                .put("index", source.optInt("index", 0)).put("finishReason", source.optString("finishReason", "STOP"))));
        return frame(contentFrame) + frame(finishFrame);
    }

    private static String chatDelta(JSONObject delta) throws Exception { return frame(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("index", 0).put("delta", delta)))); }
    private static String frame(JSONObject event) { return "data: " + event + "\n\n"; }
    private static MockResponse json(JSONObject body) { return new MockResponse().setHeader("Content-Type", "application/json").setBody(body.toString()); }
    private static MockResponse sse(String body) { return new MockResponse().setHeader("Content-Type", "text/event-stream").setBody(body); }
}
