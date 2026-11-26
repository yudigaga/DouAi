package com.douai.module.feature;

import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillTable;
import com.douai.module.config.AiModelConfig;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.DataOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
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

    @Test public void skillDraftSidecarKeepsWorkAndContactSeparateWithoutAddingModelContext() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        AiModelConfig profile = profile(3);
        TestChatMemory work = new TestChatMemory(context, profile);
        TestChatMemory contact = TestChatMemory.messageConversation(context, profile);
        AiModelConfig otherProfile = AiModelConfig.fromJson(profile.toJson().put("id", "another-draft-profile"));
        assertEquals(work.conversationKey(), contact.conversationKey());
        TestChatMemory.SkillDraft workDraft = new TestChatMemory.SkillDraft("\r\n" + declarationJson() + "\t", "", "", "");
        TestChatMemory.SkillDraft contactDraft = new TestChatMemory.SkillDraft("", "", "写作草稿", UUID.randomUUID().toString());
        record(work, ordinary("工作普通消息", "普通回答"));
        assertTrue(work.writeSkillDraft(work.epoch(), null, workDraft));
        assertTrue(contact.writeSkillDraft(contact.epoch(), null, contactDraft));

        assertEquals(workDraft, new TestChatMemory(context, profile).readSkillDraft());
        assertEquals(contactDraft, TestChatMemory.messageConversation(context, profile).readSkillDraft());
        assertNull(new TestChatMemory(context, otherProfile).readSkillDraft());
        assertNull(TestChatMemory.messageConversation(context, otherProfile).readSkillDraft());
        assertEquals(List.of("工作普通消息"), prompts(work.read()));
        assertEquals(List.of("工作普通消息"), prompts(work.readTranscript()));
        assertTrue(contact.readTranscript().isEmpty());
        contact.clear();
        assertNull(contact.readSkillDraft());
        assertEquals(workDraft, work.readSkillDraft());
    }

    @Test public void skillDraftCasChecksTheStoredSnapshotAndClearEpochAcrossInstances() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(2);
        TestChatMemory first = new TestChatMemory(directory, profile);
        TestChatMemory second = new TestChatMemory(directory, profile);
        long epoch = first.epoch();
        var initial = new TestChatMemory.SkillDraft(declarationJson(), "", "", "");
        var revised = new TestChatMemory.SkillDraft(declarationJson().replace("改善表达", "保留事实"), "", "", "");
        assertTrue(first.writeSkillDraft(epoch, null, initial));
        assertFalse(second.writeSkillDraft(epoch, null, revised));
        assertTrue(second.writeSkillDraft(epoch, initial, revised));
        assertFalse(first.writeSkillDraft(epoch, initial, null));
        assertEquals(revised, first.readSkillDraft());

        second.clear();
        assertNull(first.readSkillDraft());
        assertFalse(first.writeSkillDraft(epoch, null, initial));
        assertTrue(first.writeSkillDraft(first.epoch(), null, revised));
        TestChatMemory disabled = new TestChatMemory(directory, profile(0));
        assertNull(disabled.readSkillDraft());
        assertFalse(disabled.writeSkillDraft(disabled.epoch(), null, initial));
        assertNull(new TestChatMemory(directory, profile).readSkillDraft());
        assertTrue(first.writeSkillDraft(first.epoch(), null, initial));
        assertTrue(second.writeSkillDraft(second.epoch(), initial, null));
        assertNull(first.readSkillDraft());
    }

    @Test public void skillDraftRejectsOversizeMalformedTextAndUnpairedPendingIdentity() throws Exception {
        File directory = temporary.newFolder();
        TestChatMemory memory = new TestChatMemory(directory, profile(2));
        var prior = new TestChatMemory.SkillDraft(declarationJson(), "", "", "");
        assertTrue(memory.writeSkillDraft(memory.epoch(), null, prior));
        for (var bad : List.of(
                new TestChatMemory.SkillDraft("字".repeat(48 * 1024), "", "", ""),
                new TestChatMemory.SkillDraft(declarationJson(), "x".repeat(48 * 1024 + 1), "", ""),
                new TestChatMemory.SkillDraft(declarationJson() + "\uD800", "", "", ""),
                new TestChatMemory.SkillDraft(declarationJson(), "", "写作草稿", ""),
                new TestChatMemory.SkillDraft(declarationJson(), "", "写作草稿", "invalid-pending-id"))) {
            assertFalse(memory.writeSkillDraft(memory.epoch(), prior, bad));
            assertEquals(prior, memory.readSkillDraft());
        }
        File[] sidecars = directory.listFiles();
        assertNotNull(sidecars);
        assertEquals(1, sidecars.length);
        Files.write(sidecars[0].toPath(), new byte[]{1, 2, 3});
        assertNull(memory.readSkillDraft());
        assertTrue(memory.writeSkillDraft(memory.epoch(), null, prior));
        assertEquals(prior, memory.readSkillDraft());
    }

    @Test public void evictingAnOldConversationAlsoFencesAndRemovesItsDraft() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig original = profile(2);
        TestChatMemory old = new TestChatMemory(directory, original);
        var draft = new TestChatMemory.SkillDraft(declarationJson(), "", "", "");
        assertTrue(old.writeSkillDraft(old.epoch(), null, draft));
        long beforeEviction = old.epoch();
        File oversized = historyFile(directory, old);
        try (var sparse = new java.io.RandomAccessFile(oversized, "rw")) {
            sparse.setLength(48L * 1024 * 1024);
        }
        AiModelConfig replacement = AiModelConfig.fromJson(original.toJson().put("id", "other-scope"));
        TestChatMemory current = new TestChatMemory(directory, replacement);
        assertTrue(current.writeSkillDraft(current.epoch(), null, draft));
        assertFalse(oversized.exists());
        assertNull(old.readSkillDraft());
        assertFalse(old.writeSkillDraft(beforeEviction, null, draft));
        assertEquals(draft, current.readSkillDraft());
    }

    @Test public void authoredDeclarationKeepsExactRawJsonAcrossWritesAndOnlyEntersTheVisibleTranscript() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(6);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        String raw = " \r\n" + declarationJson().replace("\n", "\r\n") + "\t";
        var declaration = new SkillResult.Declaration(raw);
        var table = new SkillResult.Table(SkillTable.parse("{\"columns\":[\"任务\"],\"rows\":[[\"示例\"]]}"));
        var video = new SkillResult.Video(UUID.randomUUID());
        assertEquals(SkillResult.Type.TEXT, declaration.type());
        record(memory, ordinary("普通先前问题", "普通先前回答"));
        record(memory, TestChatMemory.Turn.displayedSkill("/技能 试写", "草拟完成", List.of(), null, declaration));
        record(memory, TestChatMemory.Turn.displayedSkill("/表格 内容", "", List.of(), null, table));
        record(memory, TestChatMemory.Turn.displayedSkill("/视频 内容", "", List.of(), null, video));
        record(memory, ordinary("普通新问题", "普通新回答"));

        TestChatMemory reopened = new TestChatMemory(directory, profile);
        assertEquals(List.of("普通先前问题", "普通新问题"), prompts(reopened.read()));
        List<TestChatMemory.Turn> transcript = reopened.readTranscript();
        assertEquals(List.of("普通先前问题", "/技能 试写", "/表格 内容", "/视频 内容", "普通新问题"), prompts(transcript));
        assertEquals(raw, ((SkillResult.Declaration) transcript.get(1).artifact).json());
        assertEquals("草拟完成", transcript.get(1).answer);
        assertTrue(transcript.get(1).displayOnly);
        assertEquals(table, transcript.get(2).artifact);
        assertEquals(video, transcript.get(3).artifact);
        try (DataInputStream input = new DataInputStream(Files.newInputStream(historyFile(directory, memory).toPath()))) {
            assertEquals(3, input.readInt());
        }
    }

    @Test public void invalidPersistedDeclarationsAreNotCopyableAndDoNotEraseSurroundingHistory() throws Exception {
        File directory = temporary.newFolder();
        AiModelConfig profile = profile(10);
        TestChatMemory memory = new TestChatMemory(directory, profile);
        byte[] malformedUtf8 = declarationJson().replace("保留原有用途", "\uFFFD").getBytes(StandardCharsets.UTF_8);
        for (int index = 0; index < malformedUtf8.length; index++) {
            if (malformedUtf8[index] == (byte) 0xef) {
                malformedUtf8[index] = (byte) 0xff;
                break;
            }
        }
        String bearingKey = declarationJson().replace("保留原有用途", "Bearer synthetic-fixture-key");
        assertThrows(IllegalArgumentException.class, () -> new SkillResult.Declaration(bearingKey));
        assertThrows(IllegalArgumentException.class,
                () -> new SkillResult.Declaration(declarationJson().replace("保留原有用途", "\uD800")));
        try (DataOutputStream output = new DataOutputStream(new FileOutputStream(historyFile(directory, memory)))) {
            output.writeInt(3);
            output.writeInt(7);
            writeModel(output, "之前");
            writeDeclaration(output, "结构损坏", "{\"schemaVersion\":4}".getBytes(StandardCharsets.UTF_8));
            writeDeclaration(output, "含凭据", bearingKey.getBytes(StandardCharsets.UTF_8));
            writeDeclaration(output, "编码错误", malformedUtf8);
            writeDeclaration(output, "超出限制", new byte[48 * 1024 + 1]);
            writeDeclaration(output, "可恢复", declarationJson().getBytes(StandardCharsets.UTF_8));
            writeModel(output, "之后");
        }

        List<String> expectedDisplay = List.of("之前", "结构损坏", "含凭据", "编码错误", "超出限制", "可恢复", "之后");
        TestChatMemory reopened = new TestChatMemory(directory, profile);
        assertEquals(List.of("之前", "之后"), prompts(reopened.read()));
        List<TestChatMemory.Turn> rows = reopened.readTranscript();
        assertEquals(expectedDisplay, prompts(rows));
        for (int index = 1; index <= 4; index++) {
            assertNull(rows.get(index).artifact);
            assertEquals("本地显示内容", rows.get(index).answer);
        }
        assertEquals(declarationJson(), ((SkillResult.Declaration) rows.get(5).artifact).json());
        record(reopened, ordinary("再问", "再答"));
        assertEquals(List.of("之前", "之后", "再问"), prompts(reopened.read()));
        assertEquals(8, reopened.readTranscript().size());
    }

    private static String declarationJson() {
        return """
                {"schemaVersion":4,"name":"写作草稿","description":"完善原稿 👋","documentation":"保留原有用途",
                 "instructions":"改善表达","capabilityId":"text_model","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """;
    }

    private static File historyFile(File directory, TestChatMemory memory) {
        return new File(directory, memory.conversationKey() + ".bin");
    }

    private static void writeModel(DataOutputStream output, String prompt) throws Exception {
        output.writeByte(0);
        writeString(output, prompt);
        writeString(output, "");
        writeString(output, "普通回答");
        output.writeInt(0);
    }

    private static void writeDeclaration(DataOutputStream output, String prompt, byte[] payload) throws Exception {
        output.writeByte(1);
        writeString(output, prompt);
        writeString(output, "本地显示内容");
        writeString(output, "");
        output.writeInt(0);
        writeString(output, "skill_json");
        output.writeInt(payload.length);
        output.write(payload);
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
