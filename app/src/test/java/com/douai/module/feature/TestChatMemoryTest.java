package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TestChatMemoryTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void atomicRoundTripPreservesTextDocumentsAndImageBytesWithinTheRoundBound() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig config = config("https://example.test", 2, "固定偏好");
        TestChatMemory memory = new TestChatMemory(directory, config);
        ChatImage image = new ChatImage("image/png", new byte[]{1, 2, 3});
        assertTrue(memory.append(memory.epoch(), turn("旧问题", "旧回答")));
        assertTrue(memory.append(memory.epoch(), new TestChatMemory.Turn("图片问题", " 文件内容\n",
                Collections.singletonList(image), "图片回答")));
        assertTrue(memory.append(memory.epoch(), turn("新问题", "新回答")));
        List<TestChatMemory.Turn> restored = new TestChatMemory(directory, config).read();
        assertEquals(2, restored.size());
        assertEquals("图片问题", restored.get(0).prompt);
        assertEquals(" 文件内容\n", restored.get(0).document);
        assertEquals(image.base64Data, restored.get(0).images.get(0).base64Data);
        assertEquals("新回答", restored.get(1).answer);
        assertEquals(1, directory.listFiles().length);
        assertFalse(directory.listFiles()[0].getName().contains("固定偏好"));
    }

    @Test public void messageConversationIsSeparateFromTestsAndSurvivesContactRename() throws Exception {
        android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
        AiModelConfig profile = AiModelConfig.fromJson(config("https://example.test", 3, "偏好").toJson()
                .put("id", java.util.UUID.randomUUID().toString()));
        TestChatMemory tests = new TestChatMemory(context, profile);
        TestChatMemory messages = TestChatMemory.messageConversation(context, profile);
        assertTrue(tests.append(tests.epoch(), turn("测试问题", "测试回答")));
        assertTrue(messages.read().isEmpty());
        assertTrue(messages.append(messages.epoch(), turn("聊天问题", "聊天回答")));
        AiModelConfig renamed = AiModelConfig.fromJson(profile.toJson().put("messageContactName", "新名字"));
        TestChatMemory reopened = TestChatMemory.messageConversation(context, renamed);
        assertEquals("聊天问题", reopened.read().get(0).prompt);
        reopened.clear();
        assertEquals("测试问题", tests.read().get(0).prompt);
        tests.clear();
    }

    @Test public void clearAcrossInstancesRejectsLateSaveAndZeroRoundsEraseExistingHistory() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig config = config("https://example.test", 2, "偏好");
        TestChatMemory old = new TestChatMemory(directory, config);
        long epoch = old.epoch();
        assertTrue(old.append(epoch, turn("问题", "回答")));
        new TestChatMemory(directory, config).clear();
        assertFalse(old.append(epoch, turn("晚到的问题", "晚到的回答")));
        assertTrue(old.read().isEmpty());
        assertTrue(old.append(old.epoch(), turn("新问题", "新回答")));
        TestChatMemory disabled = new TestChatMemory(directory,
                AiModelConfig.fromJson(config.toJson().put("contextRounds", 0)));
        assertTrue(disabled.read().isEmpty());
        assertFalse(disabled.append(disabled.epoch(), turn("零轮问题", "零轮回答")));
        assertTrue(old.read().isEmpty());
    }

    @Test public void profileAndPrimaryChangesIsolateHistoryAndImageStorageIsBounded() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig config = config("https://example.test", 50, "偏好 A");
        TestChatMemory memory = new TestChatMemory(directory, config);
        ChatImage image = new ChatImage("image/jpeg", new byte[ChatImage.MAX_BYTES]);
        for (int index = 0; index < 4; index++) {
            assertTrue(memory.append(memory.epoch(), new TestChatMemory.Turn("问题 " + index, "",
                    Collections.singletonList(image), "回答 " + index)));
        }
        assertEquals(3, memory.read().size());
        assertEquals("问题 1", memory.read().get(0).prompt);
        assertTrue(new TestChatMemory(directory, config("https://example.test", 50, "偏好 B")).read().isEmpty());
        assertTrue(new TestChatMemory(directory,
                AiModelConfig.fromJson(config.toJson().put("modelName", "other-model"))).read().isEmpty());
        TestChatMemory sameWithoutKeyIdentity = new TestChatMemory(directory,
                AiModelConfig.fromJson(config.toJson().put("apiKey", "changed-secret")));
        assertEquals(3, sameWithoutKeyIdentity.read().size());
    }

    @Test public void persistedClientRestoresHistoryWithoutExposingSecretsInDiagnostics() throws Exception {
        File directory = temporary.newFolder();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(reply("第一轮回答"));
            server.enqueue(reply("第二轮回答"));
            server.enqueue(reply("清空后回答"));
            server.start();
            AiModelConfig config = config(server.url("/v1").toString(), 2, "私有偏好内容");
            AiModelSettings models = new AiModelSettings(Collections.singletonList(config), config.id);
            List<String> diagnostics = new ArrayList<>();
            ChatCompletionClient first = new ChatCompletionClient(new TestChatMemory(directory, config), diagnostics::add);
            String document = "文档正文".repeat(1000);
            assertEquals("第一轮回答", first.reply("test", "第一轮问题", Collections.emptyList(), models, "", true, document));
            JSONArray sent = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages");
            assertTrue(sent.getJSONObject(1).getString("content").contains(document));
            ChatCompletionClient reopened = new ChatCompletionClient(new TestChatMemory(directory, config), diagnostics::add);
            assertEquals(1, reopened.savedTurns().size());
            assertEquals("第二轮回答", reopened.reply("test", "第二轮问题", models));
            JSONArray restored = new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages");
            assertEquals(4, restored.length());
            assertEquals("私有偏好内容", restored.getJSONObject(0).getString("content"));
            assertTrue(restored.getJSONObject(1).getString("content").contains(document));
            assertEquals("第一轮回答", restored.getJSONObject(2).getString("content"));
            reopened.clearHistory();
            assertTrue(first.savedTurns().isEmpty());
            assertEquals("清空后回答", reopened.reply("test", "新问题", models));
            assertEquals(2, new JSONObject(server.takeRequest().getBody().readUtf8()).getJSONArray("messages").length());
            String diagnosticText = String.join("\n", diagnostics);
            assertTrue(diagnosticText.contains("historyRounds=1"));
            assertTrue(diagnosticText.contains("status=200"));
            for (String secret : new String[]{config.apiEndpoint, config.apiKey, config.systemPrompt, document, "第一轮问题"}) {
                assertFalse(diagnosticText.contains(secret));
            }
        }
    }

    @Test public void displayedSkillHistoryNeverEntersTheNextProviderRequest() throws Exception {
        File directory = temporary.newFolder();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(reply("下一轮回答"));
            server.start();
            AiModelConfig config = config(server.url("/v1").toString(), 3, "普通模型偏好");
            AiModelSettings models = new AiModelSettings(List.of(config), config.id);
            TestChatMemory memory = new TestChatMemory(directory, config);
            assertTrue(memory.append(memory.epoch(), turn("普通历史问题", "普通历史回答")));
            ChatImage image = new ChatImage("image/png", new byte[]{19, 48, 59, 105, 83});
            assertTrue(memory.append(memory.epoch(), TestChatMemory.Turn.displayedSkill(
                    "本地技能专用输入", "本地技能专用输出", List.of(image), "本地技能")));

            ChatCompletionClient client = new ChatCompletionClient(new TestChatMemory(directory, config), null);
            assertEquals("下一轮回答", client.reply("test", "新问题", models));
            var request = server.takeRequest(5, TimeUnit.SECONDS);
            assertNotNull(request);
            String body = request.getBody().readUtf8();
            assertTrue(body.contains("普通历史问题"));
            assertTrue(body.contains("普通历史回答"));
            assertFalse(body.contains("本地技能"));
            assertFalse(body.contains(image.base64Data));
            assertEquals(1, server.getRequestCount());
            assertEquals(3, memory.readTranscript().size());
            assertEquals(2, memory.read().size());
        }
    }

    @Test public void anotherInstanceClearingDuringAResponseCannotBeUndoneByItsLateSuccess() throws Exception {
        File directory = temporary.newFolder();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(reply("晚到的成功回复").setBodyDelay(300, TimeUnit.MILLISECONDS));
            server.start();
            AiModelConfig config = config(server.url("/v1").toString(), 2, "偏好");
            AiModelSettings models = new AiModelSettings(Collections.singletonList(config), config.id);
            TestChatMemory memory = new TestChatMemory(directory, config);
            ChatCompletionClient client = new ChatCompletionClient(memory, null);
            java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
            try {
                java.util.concurrent.Future<String> pending = worker.submit(() -> client.reply("test", "问题", models));
                assertNotNull(server.takeRequest(5, TimeUnit.SECONDS));
                new TestChatMemory(directory, config).clear();
                assertEquals("晚到的成功回复", pending.get(5, TimeUnit.SECONDS));
                assertTrue(memory.read().isEmpty());
            } finally { client.cancelCurrentRequest(); worker.shutdownNow(); }
        }
    }

    private static TestChatMemory.Turn turn(String prompt, String answer) {
        return new TestChatMemory.Turn(prompt, "", Collections.emptyList(), answer);
    }

    private static AiModelConfig config(String endpoint, int rounds, String primary) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", "memory-profile").put("name", "模型")
                .put("apiEndpoint", endpoint).put("modelName", "model").put("apiKey", "private-api-key")
                .put("allowInsecureHttp", true).put("contextRounds", rounds).put("systemPrompt", primary));
    }

    private static MockResponse reply(String answer) throws Exception {
        return new MockResponse().setHeader("Content-Type", "application/json")
                .setBody(new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message",
                        new JSONObject().put("content", answer)))).toString());
    }
}
