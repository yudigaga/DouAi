package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SkillAuthoringPatchTest {
    @Test public void explicitFieldsAreFrozenAndValidatedAsTypedValues() throws Exception {
        SkillManifest manifest = manifest("text_model", "text", """
                [{"key":"tone","label":"语气","type":"enum","required":true,"options":["正式","温和"]},
                 {"key":"count","label":"次数","type":"integer","required":true,"min":1,"max":120},
                 {"key":"enabled","label":"保留符号","type":"boolean","required":false,"default":true}]
                """);
        String edit = "把语气改成正式，次数改为8，保留符号设为false";
        Set<String> touched = Set.of("config.tone", "config.count", "config.enabled");
        assertEquals(touched, SkillAuthoringPatch.targets(manifest, edit));
        var request = SkillAuthoringPatch.request(manifest, touched, edit, "", false);
        assertEquals(Map.of("config.tone", "正式", "config.count", 8, "config.enabled", false), request.read(patch(
                update("config.tone", "正式", "把语气改成正式"), update("config.count", 8, "次数改为8"),
                update("config.enabled", false, "保留符号设为false"))));
        assertFalse(request.input().contains("当前私有内容"));
        assertFalse(request.input().contains("default"));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.count", "8", "次数改为8"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.enabled", "false", "保留符号设为false"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("description", "新描述", edit))));
        assertEquals(Map.of(), request.read(patch()));
    }

    @Test public void literalMustBeCompleteBoundToSameFieldAndPresentInSelectedSource() throws Exception {
        SkillManifest manifest = manifest("text_model", "text", """
                [{"key":"count","label":"次数","type":"integer","required":true},
                 {"key":"limit","label":"上限","type":"integer","required":false},
                 {"key":"tone","label":"语气","type":"text","required":false}]
                """);
        var request = SkillAuthoringPatch.request(manifest, Set.of("config.count", "config.tone"),
                "按上面的资料帮我填写", "次数：128；上限：8；语气：正式一点", false);
        assertEquals(Map.of("config.count", 128), request.read(patch(update("config.count", 128, "次数：128"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.count", 8, "上限：8"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.tone", "正式", "语气：正式"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.count", 129, "次数：129"))));
        var conflict = SkillAuthoringPatch.request(manifest, Set.of("config.count"),
                "次数改为2", "次数：8", true);
        assertThrows(IllegalArgumentException.class, () -> conflict.read(patch(update("config.count", 8, "次数：8"))));
    }

    @Test public void serviceAddressAndModelRequireUserEvidence() throws Exception {
        SkillManifest manifest = manifest("image_generation", "image", "[]");
        assertTrue(SkillAuthoringPatch.allowedFields(manifest).containsAll(Set.of("service.endpoint", "service.model")));
        String endpoint = "https://images.example.com/v1/images/generations";
        String source = "API 地址：" + endpoint + "；模型名：Vision Pro 2";
        var patch = patch(update("service.endpoint", endpoint, "API 地址：" + endpoint),
                update("service.model", "Vision Pro 2", "模型名：Vision Pro 2"));
        Set<String> fields = Set.of("service.endpoint", "service.model");
        var assistantSource = SkillAuthoringPatch.request(manifest, fields, "按上面的资料帮我填写", source, false);
        assertFalse(assistantSource.input().contains(endpoint));
        assertThrows(IllegalArgumentException.class, () -> assistantSource.read(patch));
        var userSource = SkillAuthoringPatch.request(manifest, fields, "按上面的资料帮我填写", source, true);
        assertTrue(userSource.input().contains("images.example.com"));
        assertEquals(Map.of("service.endpoint", endpoint, "service.model", "Vision Pro 2"), userSource.read(patch));
        var otherRole = SkillAuthoringPatch.request(manifest, fields, "文档地址：" + endpoint, "", false);
        assertFalse(otherRole.input().contains(endpoint));
        assertThrows(IllegalArgumentException.class, () -> otherRole.read(patch(update("service.endpoint", endpoint, "文档地址：" + endpoint))));
    }

    @Test public void rejectsSecretsUnsafeUrlsDuplicateAndUnknownFields() throws Exception {
        SkillManifest manifest = manifest("image_generation", "image", "[]");
        Set<String> allowed = Set.of("service.endpoint", "description");
        String unsafe = "https://images.example.com/process?signature=private-value";
        var request = SkillAuthoringPatch.request(manifest, allowed, "描述改成更清晰，API 地址：" + unsafe, "", false);
        assertFalse(request.input().contains("private-value"));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("service.endpoint", unsafe, "API 地址：" + unsafe))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("description", "看 https://extra.example.com", "描述改成更清晰"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("service.credential", "secret", "描述改成更清晰"))));
        JSONObject description = update("description", "用更清晰的描述", "描述改成更清晰");
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(description, description)));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(description.put("enabled", true))));
        assertThrows(IllegalArgumentException.class, () -> request.read("{\"version\":1,\"version\":1,\"updates\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoringPatch.request(manifest, allowed,
                "描述改成更清晰\nAuthorization: Bearer abcdef123456", "", false));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoringPatch.request(manifest, allowed,
                "描述改成更清晰，顺便把密钥设为abc", "", false));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoringPatch.request(manifest,
                Set.of("service.api_key"), "", "", false));
    }

    @Test public void addressRedactionPreservesTextAfterUnicodeSeparators() throws Exception {
        for (String separator : List.of(" ", "\u00A0", "\u1680", "\u2007", "\u2028", "\u2029", "\u202F",
                "\u205F", "\u3000", "，")) {
            var request = SkillAuthoringPatch.request(null, Set.of("documentation"), "修改说明",
                    "参阅 HtTpS://example.com/reference" + separator + "说明：保留后续字段", false);
            assertEquals("参阅 [地址已省略]" + separator + "说明：保留后续字段",
                    new JSONObject(request.input()).getString("document"));
        }
    }

    @Test public void videoEndpointsRemainInValidatedVideoFactsPath() throws Exception {
        SkillManifest video = manifest("video_task", "video", "[]");
        SkillManifest direct = manifest("custom_service", "text", "[]");
        assertFalse(SkillAuthoringPatch.allowedFields(video).contains("service.endpoint"));
        assertTrue(SkillAuthoringPatch.allowedFields(direct).contains("service.endpoint"));
        String update = "把接口地址改成 https://video.example.com/v1/jobs";
        assertTrue(SkillAuthoringPatch.targets(video, update).isEmpty());
        assertEquals(Set.of("service.endpoint"), SkillAuthoringPatch.targets(direct, update));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoringPatch.request(video,
                Set.of("service.endpoint"), update, "", false));
    }

    @Test public void selectedMarkdownRequiresExactTwoCellRowsAndTypedBoolean() throws Exception {
        SkillManifest manifest = manifest("text_model", "text", """
                [{"key":"tone","label":"语气","type":"text","required":false},
                 {"key":"keep","label":"保留段落","type":"boolean","required":false}]
                """);
        String tone = "| 语气 | 清楚，直接 |";
        String keep = "| 保留段落 | 开启 |";
        String table = "| 字段 | 值 |\n| --- | --- |\n" + tone + "\n" + keep;
        var request = SkillAuthoringPatch.request(manifest, Set.of("config.tone", "config.keep"),
                "按上面的资料帮我填写", table, false);
        assertEquals(Map.of("config.tone", "清楚，直接", "config.keep", true), request.read(patch(
                update("config.tone", "清楚，直接", tone), update("config.keep", true, keep))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.keep", "开启", keep))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.keep", false, keep))));
        var malformed = SkillAuthoringPatch.request(manifest, Set.of("config.keep"), "按上面的资料帮我填写",
                "| 保留段落 | 停用 | 第三列 |", false);
        assertThrows(IllegalArgumentException.class, () -> malformed.read(patch(update("config.keep", false, "| 保留段落 | 停用 |"))));
    }

    @Test public void metadataCanBeSynthesizedForExplicitlyPermittedField() throws Exception {
        String user = "帮我把说明改为适合第一次打开技能的人阅读";
        assertEquals(Set.of("documentation"), SkillAuthoringPatch.targets(null, user));
        var request = SkillAuthoringPatch.request(null, Set.of("documentation"), user, "", false);
        assertEquals(Map.of("documentation", "输入待整理的内容，然后提交。"),
                request.read(patch(update("documentation", "输入待整理的内容，然后提交。", user))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("instructions", "附加要求", user))));
    }

    @Test public void longReferenceKeepsCompleteVisibleEvidenceWithoutSelectingConflicts() throws Exception {
        SkillManifest manifest = manifest("text_model", "text", """
                [{"key":"tone","label":"语气","type":"text","required":false},
                 {"key":"count","label":"次数","type":"integer","required":false}]
                """);
        String tone = "| 语气 | 直接明了 |";
        String filler = "这段与配置无关。".repeat(570);
        String reference = tone + "\n" + filler + "\n次数：8\n次数：9";
        var request = SkillAuthoringPatch.request(manifest, Set.of("config.tone", "config.count"),
                "按上面的资料帮我填写", reference, false);
        assertTrue(request.input().length() <= 4000);
        assertEquals(tone + "\n次数：8\n次数：9", new JSONObject(request.input()).getString("document"));
        assertEquals(Map.of("config.tone", "直接明了"), request.read(patch(update("config.tone", "直接明了", tone))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.tone", "直接明了",
                tone + "\n这段与配置无关。"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.tone", "直接明了",
                tone + "\n次数：8"))));
        assertThrows(IllegalArgumentException.class, () -> request.read(patch(update("config.count", 8, "次数：8"))));
    }

    @Test public void serializedBudgetDropsOversizedContextAndRefusesStillTooLongRequest() throws Exception {
        JSONObject definition = manifest("text_model", "text", "[]").toJson();
        SkillManifest manifest = SkillManifest.parse(definition.put("documentation", "旧说明。".repeat(1200)).toString());
        String user = "把说明改成操作步骤，描述改成更清楚";
        var request = SkillAuthoringPatch.request(manifest, Set.of("documentation", "description"), user, "", false);
        JSONObject modelInput = new JSONObject(request.input());
        assertTrue(request.input().length() <= 4000);
        assertEquals(user, modelInput.getString("request"));
        assertFalse(request.input().contains("旧说明。"));
        assertTrue(request.input().contains("当前私有内容"));
        assertThrows(IllegalArgumentException.class, () -> SkillAuthoringPatch.request(manifest, Set.of("description"),
                "描述：" + "\"".repeat(2100), "", false));
    }

    private static SkillManifest manifest(String capability, String output, String config) throws JSONException {
        return SkillManifest.parse(new JSONObject().put("schemaVersion", 4).put("name", "整理")
                .put("description", "当前私有内容").put("documentation", "输入内容").put("instructions", "保持原意")
                .put("capabilityId", capability).put("outputType", output).put("configFields", new JSONArray(config))
                .put("inputs", new JSONArray().put(new JSONObject().put("key", "argument").put("type", "text")
                        .put("required", true).put("maxLength", 200)))
                .put("trial", new JSONObject().put("input", "示例").put("expectedType", output)).toString());
    }

    private static JSONObject update(String field, Object value, String evidence) throws JSONException {
        return new JSONObject().put("field", field).put("value", value).put("evidence", evidence);
    }

    private static String patch(JSONObject... updates) throws JSONException {
        JSONArray entries = new JSONArray();
        for (JSONObject update : updates) entries.put(update);
        return new JSONObject().put("version", 1).put("updates", entries).toString();
    }
}
