package com.douai.module.ai.skills;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillAuthoringTest {
    @Test public void directSkillRequestsDistinguishPreviewFromExplicitSave() {
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "英文润色", false),
                SkillAuthoring.match("帮我写一个英文润色 Skill", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "提炼会议要点", false),
                SkillAuthoring.match("请设计一个 Skill：提炼会议要点", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "海报生成", true),
                SkillAuthoring.match("帮我做一个海报生成 Skill，并内置。", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "周报", true),
                SkillAuthoring.match("创建并内置一个周报 Skill", false));
        assertEquals(SkillAuthoring.Mode.SAVE, SkillAuthoring.match("帮我内置", false).mode());
        assertEquals(SkillAuthoring.Mode.SAVE, SkillAuthoring.match("请帮我内置。", true).mode());
    }

    @Test public void revisionRequiresAnExistingDraftAndARequestToChangeIt() {
        assertNull(SkillAuthoring.match("把它改成正式语气", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.REVISE, "把它改成正式语气", false),
                SkillAuthoring.match("把它改成正式语气", true));
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("再简洁一点", true).mode());
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("名字改成会议纪要", true).mode());
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("把指令改成三点格式", true).mode());
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("再补充一个使用示例", true).mode());
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("修改这份草案：使用三个要点", true).mode());
        assertNull(SkillAuthoring.match("这个技能为什么这么设计？", true));
        assertNull(SkillAuthoring.match("修改是什么意思？", true));
    }

    @Test public void quotedAndOrdinaryDiscussionRemainChatEvenWithADraft() {
        for (String sentence : new String[]{"如何写一个周报 Skill？", "帮我写一个生图 Skill 的使用说明",
                "「帮我做一个生图 Skill」", "分析这段文字：帮我做一个 Skill", "这是引用\n帮我内置",
                "请解释怎么内置", "他说帮我内置", "/skill 做一个生图 Skill", "帮我做一个如何写 Skill 的 Skill",
                "补充讲讲这件事", "再补充昨晚比分", "改一下这个问题"}) {
            assertNull(sentence, SkillAuthoring.match(sentence, true));
        }
    }

    @Test public void isolatedRequestsContainOnlyTheUserInputAndValidatedDraft() throws Exception {
        JSONObject create = new JSONObject(SkillAuthoring.draftInput("生成会议纪要", null));
        assertEquals(1, create.length());
        assertEquals("生成会议纪要", create.getString("requirement"));
        JSONObject revision = new JSONObject(SkillAuthoring.draftInput("把语气改正式", "{\"name\":\"会议纪要\"}"));
        assertEquals(2, revision.length());
        assertEquals("会议纪要", revision.getJSONObject("previousDefinition").getString("name"));
        JSONObject execute = new JSONObject(SkillAuthoring.textInput("归纳为三条", "会议信息\n初步决定"));
        assertEquals(2, execute.length());
        assertEquals("归纳为三条", execute.getString("instruction"));
        assertEquals("会议信息\n初步决定", execute.getString("argument"));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.textInput("归纳", "x".repeat(2001)));
    }

    @Test public void strictJsonRejectsDuplicateKeysInvalidLiteralsEscapesAndTrailingInput() throws Exception {
        JSONObject valid = SkillAuthoring.readObject("{\"version\":3,\"fraction\":3.0,\"ready\":true}", 200);
        assertTrue(valid.get("version") instanceof Integer);
        assertFalse(valid.get("fraction") instanceof Integer);
        assertEquals(Boolean.TRUE, valid.get("ready"));
        for (String malformed : new String[]{"{\"x\":0,\"x\":1}", "{\"o\":{\"x\":1,\"x\":2}}", "{\"x\":TRUE}",
                "{\"x\":01}", "{'x':1}", "{\"x\":\"\\q\"}", "{} {}", "/*comment*/{}", "[]", "{\"x\":1,}"}) {
            assertThrows(malformed, IllegalArgumentException.class, () -> SkillAuthoring.readObject(malformed, 200));
        }
        String deep = "{\"x\":" + "[".repeat(20) + "0" + "]".repeat(20) + "}";
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.readObject(deep, 200));
    }

    @Test public void pastedCredentialTextFailsWithoutLeakingTheValue() {
        String token = "fixture-private-token";
        IllegalArgumentException labeled = assertThrows(IllegalArgumentException.class,
                () -> SkillAuthoring.draftInput("写技能。API Key: " + token, null));
        assertFalse(labeled.toString().contains(token));
        assertNull(labeled.getCause());
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.textInput("提炼", "Bearer " + token));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.readObject(
                "{\"documentation\":\"API Key: \\u0066ixture-private-token\"}", 200));
    }
}
