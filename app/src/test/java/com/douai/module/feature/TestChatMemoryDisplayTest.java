package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.stream.Collectors;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class TestChatMemoryDisplayTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void legacyOrdinaryHistorySurvivesAddingASeparateAssistantImageAndReopening() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(3);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        record(memory, ordinary("旧文本", "旧答案"));
        File[] files = directory.listFiles();
        assertNotNull(files);
        assertEquals(1, files.length);
        writeLegacy(files[0]);

        ChatImage output = new ChatImage("image/png", new byte[]{3, 4, 5});
        record(memory, TestChatMemory.Turn.displayedSkill("/海报 蓝色封面", "", List.of(output), "海报"));
        record(memory, ordinary("新问题", "新答案"));

        TestChatMemory reopened = new TestChatMemory(directory, profile);
        assertEquals(List.of("旧版问题", "新问题"), prompts(reopened.read()));
        List<TestChatMemory.Turn> rows = reopened.readTranscript();
        assertEquals(List.of("旧版问题", "/海报 蓝色封面", "新问题"), prompts(rows));
        assertEquals("旧版附件", rows.get(0).document);
        assertFalse(rows.get(0).displayOnly);
        assertTrue(rows.get(1).displayOnly);
        assertEquals("海报", rows.get(1).settingsSkillName);
        assertTrue(rows.get(1).images.isEmpty());
        List<ChatImage> images = rows.get(1).assistantImages;
        assertEquals(1, images.size());
        assertEquals(output.base64Data, images.get(0).base64Data);
        assertEquals("", rows.get(1).answer);
    }

    @Test public void ordinaryAndDisplayRoundsAreIndependentButTranscriptOrderIsShared() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(2);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        for (int index = 0; index < 3; index++) {
            record(memory, ordinary("普通" + index, "回答" + index));
            record(memory, TestChatMemory.Turn.displayedSkill("/摘要 " + index, "摘要结果" + index, List.of(), null));
        }

        TestChatMemory reopened = new TestChatMemory(directory, profile);
        assertEquals(List.of("普通1", "普通2"), prompts(reopened.read()));
        List<TestChatMemory.Turn> mixed = reopened.readTranscript();
        assertEquals(List.of("普通1", "/摘要 1", "普通2", "/摘要 2"), prompts(mixed));
        assertEquals("摘要结果2", mixed.get(3).answer);
        assertNull(mixed.get(3).settingsSkillName);
    }

    @Test public void eachMediaLaneCanUseItsBudgetWithoutDisplacingTheOther() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(2);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        ChatImage image = new ChatImage("image/png", new byte[ChatImage.MAX_BYTES]);
        List<ChatImage> fullBudget = List.of(image, image, image);
        record(memory, new TestChatMemory.Turn("普通图片", "", fullBudget, "普通回答"));
        record(memory, TestChatMemory.Turn.displayedSkill("/生图 三张", "", fullBudget, null));
        assertEquals(2, new TestChatMemory(directory, profile).readTranscript().size());

        record(memory, new TestChatMemory.Turn("普通新图", "", List.of(image), "普通回答"));
        TestChatMemory reopened = new TestChatMemory(directory, profile);
        assertEquals(List.of("普通新图"), prompts(reopened.read()));
        List<TestChatMemory.Turn> retained = reopened.readTranscript();
        assertEquals(List.of("/生图 三张", "普通新图"), prompts(retained));
        assertEquals(3, retained.get(0).assistantImages.size());
    }

    @Test public void clearingAnotherInstanceRejectsOldDisplayWritesAndZeroRoundsKeepNoTranscript() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(2);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        TestChatMemory.Turn shown = TestChatMemory.Turn.displayedSkill("/摘要 内容", "仅本地", List.of(), null);
        long epoch = memory.epoch();
        record(memory, shown);
        new TestChatMemory(directory, profile).clear();
        assertFalse(memory.append(epoch, shown));
        assertTrue(memory.readTranscript().isEmpty());

        record(memory, shown);
        TestChatMemory disabled = new TestChatMemory(directory, profile(0));
        assertFalse(disabled.append(disabled.epoch(), shown));
        assertTrue(disabled.readTranscript().isEmpty());
        assertTrue(new TestChatMemory(directory, profile).readTranscript().isEmpty());
    }

    private static void writeLegacy(File file) throws Exception {
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(file))) {
            output.writeInt(1);
            output.writeInt(1);
            writeString(output, "旧版问题");
            writeString(output, "旧版附件");
            writeString(output, "旧版回答");
            output.writeInt(0);
        }
    }

    private static void writeString(DataOutputStream output, String value) throws Exception {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static void record(TestChatMemory memory, TestChatMemory.Turn turn) {
        assertTrue(memory.append(memory.epoch(), turn));
    }

    private static TestChatMemory.Turn ordinary(String prompt, String answer) {
        return new TestChatMemory.Turn(prompt, "", List.of(), answer);
    }

    private static List<String> prompts(List<TestChatMemory.Turn> turns) {
        return turns.stream().map(turn -> turn.prompt).collect(Collectors.toList());
    }

    private static AiModelConfig profile(int rounds) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", "display-history").put("name", "测试模型")
                .put("apiEndpoint", "https://fixture.example.test/v1").put("modelName", "fixture")
                .put("contextRounds", rounds));
    }
}
