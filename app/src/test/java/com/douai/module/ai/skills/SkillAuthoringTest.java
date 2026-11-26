package com.douai.module.ai.skills;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

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

    @Test public void phonePhrasingAcceptsPluralSingularAndChineseSkillNames() {
        SkillAuthoring.Action createImageAndSave = new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "生图", true);
        for (String phrase : new String[]{"帮我写一个生图 skills 并内置", "帮我写一个生图 Skill 并内置",
                "帮我写一个生图 SKILLS 并内置", "帮我写一个生图技能并内置",
                "帮我写一个生图 skills，然后内置这个 SKILLS"}) {
            assertEquals(phrase, createImageAndSave, SkillAuthoring.match(phrase, false));
        }
        assertEquals(SkillAuthoring.Mode.SAVE, SkillAuthoring.match("帮我内置这个 skills", true).mode());
        assertEquals(SkillAuthoring.Mode.REVISE, SkillAuthoring.match("修改这个 skills：使用三个要点", true).mode());
    }

    @Test public void explicitVideoRequirementsPreservePollingAndDeliveryWhenInstalling() throws Exception {
        String phone = "帮我写一个生成视频的 skill，要求自动轮询自动发送并内置";
        SkillAuthoring.Action action = SkillAuthoring.match(phone, false);
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "生成视频的 自动轮询自动发送", true), action);
        SkillResult.Type output = SkillAuthoring.explicitOutput(action.request());
        assertEquals(SkillResult.Type.VIDEO, output);
        JSONObject request = new JSONObject(SkillAuthoring.draftInput(action.request(), null, output));
        assertEquals("生成视频的 自动轮询自动发送", request.getString("requirement"));
        assertEquals("video", request.getString("requiredOutputType"));

        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "生成视频的 自动轮询并回传", true),
                SkillAuthoring.match("请创建一个生成视频的技能，要求：自动轮询并回传，然后内置", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "视频生成 自动轮询并返回本会话", true),
                SkillAuthoring.match("帮我做一个视频生成 Skill, 需要自动轮询并返回本会话并内置。", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "视频生成 自动轮询和回传", false),
                SkillAuthoring.match("帮我制作一个视频生成 Skill，要求是自动轮询和回传", false));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "视频生成 任务完成后回传到本会话", true),
                SkillAuthoring.match("帮我制作一个视频生成 Skill， 请让任务完成后回传到本会话并内置", false));
    }

    @Test public void quotedReportedAndCodeRequirementsDoNotBecomeAuthoringActions() {
        String phone = "帮我写一个生成视频的 skill，要求自动轮询自动发送并内置";
        for (String text : new String[]{"「" + phone + "」", "我的同事转述：" + phone,
                "`" + phone + "`", "```text\n" + phone + "\n```", "别人发来的消息\n" + phone,
                "我在讨论生成视频的 Skill 是否需要自动轮询和自动发送。", "请解释“" + phone + "”是什么意思",
                "帮我写一个生成视频的 skill，要求引用原文“自动轮询并内置"}) {
            assertNull(text, SkillAuthoring.match(text, false));
            assertNull(text, SkillAuthoring.match(text, true));
        }
        assertFalse(SkillAuthoring.match("帮我写一个视频生成技能，要求自动轮询但不要再内置", false).saveAfter());
        assertTrue(SkillAuthoring.match("帮我写一个「视频生成」技能，要求回传本会话并内置", false).saveAfter());
    }

    @Test public void onlyExactSlashCreationTokenRoutesWholeRequirementsToDrafting() throws Exception {
        String requirement = "生成一个自动轮询、完成后自动发送的视频技能";
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, "", false),
                SkillAuthoring.match("/技能", true));
        SkillAuthoring.Action explicit = SkillAuthoring.match("/技能 " + requirement, false);
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, requirement, false), explicit);
        SkillResult.Type output = SkillAuthoring.explicitOutput(explicit.request());
        assertEquals(SkillResult.Type.VIDEO, output);
        JSONObject request = new JSONObject(SkillAuthoring.draftInput(explicit.request(), null, output));
        assertEquals(requirement, request.getString("requirement"));
        assertEquals("video", request.getString("requiredOutputType"));
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.DESIGN, requirement, true),
                SkillAuthoring.match("/技能　" + requirement + "并内置", false));
        for (String other : List.of("/技能润色 原稿", "/技能：" + requirement, "「/技能 " + requirement + "」",
                "`/技能 " + requirement + "`", "```\n/技能 " + requirement + "\n```", "同事发来 /技能 " + requirement)) {
            assertNull(other, SkillAuthoring.match(other, true));
        }
    }

    @Test public void explicitReferenceIsSeparateFromDesignAndIgnoresQuotingAndLookalikeCommands() {
        String command = "/技能 按上述帮我内置";
        assertTrue(SkillAuthoring.isReferencedVideoInstall(command));
        assertTrue(SkillAuthoring.isReferencedVideoInstall("/技能　根据上面的接口资料补齐并内置。"));
        assertNull(SkillAuthoring.match(command, false));
        assertNull(SkillAuthoring.match(command, true));
        for (String text : List.of("按上述帮我内置", "> " + command, "「" + command + "」",
                "```text\n" + command + "\n```", "/技能名 按上述帮我内置", command + "\n接口资料")) {
            assertFalse(text, SkillAuthoring.isReferencedVideoInstall(text));
        }
    }

    @Test public void editorRoutesNaturalCommandsAndOnlyTakesAnExplicitBoundedPollingPreference() {
        String change = "把轮询间隔调到 8 秒，失败时提示且不要重试";
        assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.REVISE, change, false), SkillAuthoring.continuation(change));
        assertEquals(8, SkillAuthoring.minimumPollSeconds(change));
        assertEquals(12, SkillAuthoring.minimumPollSeconds("每隔12秒查询状态"));
        assertEquals(0, SkillAuthoring.minimumPollSeconds("我只是解释轮询间隔为 8 秒"));
        assertEquals(0, SkillAuthoring.minimumPollSeconds("失败时显示“轮询间隔为 8 秒”"));
        assertEquals(0, SkillAuthoring.minimumPollSeconds("不要把轮询间隔设为 3 秒"));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.minimumPollSeconds("轮询间隔改成 1 秒"));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.minimumPollSeconds("轮询间隔改为 61 秒"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillAuthoring.minimumPollSeconds("轮询间隔改为 8 秒，轮询间隔改为 9 秒"));
        assertNull(SkillAuthoring.continuation("“" + change + "”"));
        assertNull(SkillAuthoring.continuation("我今天遇到了个问题，想聊聊改规则的事。"));
        assertTrue(SkillAuthoring.needsEditClarification("再改一下"));
        assertEquals(SkillAuthoring.Mode.PREVIEW, SkillAuthoring.continuation("预览草稿").mode());
        assertEquals(SkillAuthoring.Mode.CANCEL, SkillAuthoring.continuation("取消编写").mode());
        assertFalse(SkillAuthoring.canUsePastedVideoMaterial("不要采用这个反例：\n提交: POST /jobs"));
        assertFalse(SkillAuthoring.canUsePastedVideoMaterial("> ### 创建视频任务\n> | 接口 | POST /jobs"));
        assertTrue(SkillAuthoring.canUsePastedVideoMaterial("接口资料：\n> ### 创建视频任务"));
        SkillAuthoring.rejectEditorCredential("Authorization: Bearer $VIDEO_API_KEY");
        assertThrows(IllegalArgumentException.class,
                () -> SkillAuthoring.rejectEditorCredential("Authorization: Basic YWJjOmRlZg=="));
        assertThrows(IllegalArgumentException.class,
                () -> SkillAuthoring.rejectEditorCredential("Cookie: session=fixture-only"));
    }

    @Test public void newVideoRequestCanNameItsEvidenceInTheSameSentence() {
        String command = "/技能 帮我做个生成视频的技能";
        SkillAuthoring.VideoSource visible = SkillAuthoring.videoSource(command + "，按你发的接口资料填写");
        assertNotNull(visible);
        assertEquals("帮我做个生成视频的技能", visible.action().request());
        assertEquals("", visible.relatedSkill());
        SkillAuthoring.VideoSource configured = SkillAuthoring.videoSource(command + "，沿用 /已有视频 的服务，并内置");
        assertNotNull(configured);
        assertEquals("已有视频", configured.relatedSkill());
        assertEquals("帮我做个生成视频的技能", configured.action().request());
        assertTrue(configured.action().saveAfter());
        assertNull(SkillAuthoring.videoSource("「" + command + "，按你发的接口资料填写」"));
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

    @Test public void explicitAuthoringRoutesExistingDraftActionsEvenWithoutATarget() {
        String create = "帮我做个生成视频的技能";
        String revise = "帮我把某某改成某某";
        for (boolean hasDraft : new boolean[]{false, true}) {
            var design = SkillAuthoring.match("/技能 " + create, hasDraft);
            assertEquals(SkillAuthoring.Mode.DESIGN, design.mode());
            assertEquals(SkillResult.Type.VIDEO, SkillAuthoring.explicitOutput(design.request()));
            for (String spacing : List.of("", " ", "　")) {
                assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.REVISE, revise, false),
                        SkillAuthoring.match("/技能" + spacing + revise, hasDraft));
            }
            assertEquals(new SkillAuthoring.Action(SkillAuthoring.Mode.SAVE, "", false),
                    SkillAuthoring.match("/技能 帮我内置", hasDraft));
            assertEquals(SkillAuthoring.Mode.REVISE,
                    SkillAuthoring.match("/技能 把轮询间隔改成 8 秒", hasDraft).mode());
        }
        assertTrue(SkillAuthoring.isCreationCommand("/技能" + revise));
        assertFalse(SkillAuthoring.isCreationCommand("/技能助手 " + revise));
        assertNull(SkillAuthoring.match("/技能助手 " + revise, true));
        assertNull(SkillAuthoring.match("「/技能" + revise + "」", true));
        assertNull(SkillAuthoring.match("/技能 「" + revise + "」", false));
        assertNull(SkillAuthoring.match(revise, false));
    }

    @Test public void naturalVideoMaterialFollowupsAreReferenceOnlyAndRejectQuoting() {
        for (String phrase : List.of("接口需要帮我填好", "就按你发的填写")) {
            for (String prefix : List.of("", "/技能 ", "/技能　")) {
                String text = prefix + phrase + "。";
                assertTrue(text, SkillAuthoring.usesPreviousVideoMaterial(text));
                assertNull(text, SkillAuthoring.match(text, true));
            }
            for (String other : List.of("「" + phrase + "」", "> " + phrase, "有人说" + phrase,
                    "例子：" + phrase, "/技能名 " + phrase, "/技能 ‘" + phrase + "’")) {
                assertFalse(other, SkillAuthoring.usesPreviousVideoMaterial(other));
            }
        }
        assertTrue(SkillAuthoring.usesPreviousVideoMaterial("根据刚才的接口资料填写接口"));
        assertFalse(SkillAuthoring.usesPreviousVideoMaterial("接口需要怎么填？"));
        assertFalse(SkillAuthoring.usesPreviousVideoMaterial("就按你发的填写，然后执行"));
    }

    @Test public void explicitSingleFieldEditsExposeOnlyTheLabelAndValue() {
        String request = "帮我把查询路径改成 /jobs/{id}/status";
        var expected = new SkillAuthoring.FieldEdit("查询路径", "/jobs/{id}/status");
        for (String prefix : List.of("", "/技能", "/技能 ", "/技能　")) {
            assertEquals(expected, SkillAuthoring.fieldEdit(prefix + request));
        }
        assertEquals(new SkillAuthoring.FieldEdit("提交地址", "https://fixture.example.com/jobs"),
                SkillAuthoring.fieldEdit("/技能帮我把提交地址改为 https://fixture.example.com/jobs"));
        assertEquals(SkillAuthoring.Mode.REVISE,
                SkillAuthoring.match("/技能帮我把提交地址改为 https://fixture.example.com/jobs", false).mode());
        assertEquals(new SkillAuthoring.FieldEdit("API Key", "fixture-placeholder"),
                SkillAuthoring.fieldEdit("请将 API Key 改成 fixture-placeholder"));
        assertEquals(SkillAuthoring.Mode.REVISE,
                SkillAuthoring.match("/技能 帮我把标题改为「如何拍视频」", false).mode());
        for (String other : List.of("「" + request + "」", "比如" + request, "/技能助手 " + request,
                "帮我把查询路径改成 /a，再把状态字段改成 state",
                "帮我把查询路径改成 /a 和把状态字段改为 state", "查询路径应该填什么？")) {
            assertNull(other, SkillAuthoring.fieldEdit(other));
        }
    }

    @Test public void vagueOrMultipleScopedChangesNeverBecomeNewDesigns() {
        String multiple = "帮我把查询路径改成 x，再把状态字段改成 y";
        for (boolean hasDraft : new boolean[]{false, true}) {
            for (String text : List.of("/技能 帮我改一下", "/技能 " + multiple, "/技能" + multiple)) {
                SkillAuthoring.Action action = SkillAuthoring.match(text, hasDraft);
                assertNotNull(text, action);
                assertEquals(text, SkillAuthoring.Mode.REVISE, action.mode());
                assertFalse(action.saveAfter());
                assertNull(text, SkillAuthoring.fieldEdit(text));
            }
            assertNull(SkillAuthoring.match("/技能 你好。", hasDraft));
            assertNull(SkillAuthoring.match("/技能 这个技能为什么这么设计？", hasDraft));
            assertNull(SkillAuthoring.match("/技能助手 " + multiple, hasDraft));
            assertEquals(SkillAuthoring.Mode.DESIGN,
                    SkillAuthoring.match("/技能 生成一个教我如何拍视频的技能", hasDraft).mode());
        }
    }

    @Test public void quotedAndOrdinaryDiscussionRemainChatEvenWithADraft() {
        for (String sentence : new String[]{"如何写一个周报 Skill？", "帮我写一个生图 Skill 的使用说明",
                "如何写一个生图 skills 并内置？", "帮我写一个生图 skills 的介绍", "「帮我写一个生图 skills 并内置」",
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
        assertFalse(create.has("requiredOutputType"));
        JSONObject image = new JSONObject(SkillAuthoring.draftInput("生图", null, true));
        assertEquals(2, image.length());
        assertEquals("生图", image.getString("requirement"));
        assertEquals("image", image.getString("requiredOutputType"));
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
        IllegalArgumentException url = assertThrows(IllegalArgumentException.class, () -> SkillAuthoring.draftInput(
                "生成视频，使用https://fixture-user:" + token + "@api.example.com/jobs", null));
        assertFalse(url.toString().contains(token));
    }
}
