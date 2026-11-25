package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillVideoFactsTest {
    private static final String SUBMIT = """
            提交: POST https://video.example.com:8443/v1/tasks
            提示词字段: prompt
            提交响应ID: {"data":{"task_id":"fixture"}}
            """;
    private static final String POLLING = """
            Status: GET https://video.example.com:8443/v1/tasks/{id}
            Status ID: data.task_id
            State field: data.status
            States: waiting=queued; working=running; done=completed; fail=failed; aborted=cancelled
            Cancel: DELETE /v1/tasks/{id}
            Download: GET /v1/tasks/{id}/output
            """;

    @Test public void partialNotesRoundTripAndSupplementWithoutGuessingNestedFields() throws Exception {
        SkillVideoFacts initial = SkillVideoFacts.parse(SUBMIT + "需求是生成一段动画\n");
        assertFalse(initial.isEmpty());
        assertTrue(initial.missing().contains("查询 GET 路径（含 {id}）"));
        assertTrue(initial.missing().contains("状态映射中的完成状态"));
        assertFalse(initial.toJson().toString().contains("一段动画"));
        assertFalse(initial.toJson().toString().contains("fixture"));
        assertThrows(IllegalArgumentException.class, initial::declaration);
        SkillVideoFacts restored = SkillVideoFacts.fromJson(new JSONObject(initial.toJson().toString()));
        assertEquals(initial.missing(), restored.missing());

        SkillVideoFacts complete = restored.merge(SkillVideoFacts.parse(POLLING));
        assertTrue(complete.missing().isEmpty());
        JSONObject declaration = complete.declaration();
        assertEquals(1, declaration.getInt("version"));
        assertEquals("https://video.example.com:8443/v1/tasks", declaration.getString("suggestedEndpoint"));
        assertEquals("argument", declaration.getJSONObject("request").getString("prompt"));
        assertEquals("data.task_id", declaration.getString("response"));
        JSONObject video = declaration.getJSONObject("video");
        assertEquals("/v1/tasks/{id}", video.getString("statusPath"));
        assertEquals("data.status", video.getString("stateField"));
        assertEquals("DELETE", video.getString("cancelMethod"));
        assertEquals("completed", video.getJSONObject("states").getString("done"));
        assertEquals(declaration.toString(), SkillVideoFacts.fromJson(complete.toJson()).declaration().toString());
    }

    @Test public void refusesUnsupportedOperationsAmbiguityOrCrossOriginMerges() {
        for (String notes : List.of("提交: GET https://video.example.com/v1/submit",
                "提示词字段: api_key", "提交响应ID: {\"id\":\"first\",\"other\":\"second\"}",
                "提交响应ID: task.results[0].id", "状态字段: a.b.c.d.e",
                "查询响应ID: data\n状态字段: data.status",
                "查询: GET /jobs/%2f/{id}", "下载: POST /jobs/{id}/file",
                "取消: DELETE /jobs/{id}/../cancel", "取消: GET /jobs/{id}",
                "查询: GET /jobs/{id}/duplicate/{id}", "状态映射: unexpected=redirect",
                "状态映射: done=completed,done=failed", "提交: POST https://127.0.0.1/api")) {
            assertThrows(notes, IllegalArgumentException.class, () -> SkillVideoFacts.parse(notes));
        }
        SkillVideoFacts first = SkillVideoFacts.parse(SUBMIT);
        IllegalArgumentException origin = assertThrows(IllegalArgumentException.class, () -> first.merge(
                SkillVideoFacts.parse("下载: GET https://video.example.com:9443/v1/tasks/{id}/file")));
        assertTrue(origin.getMessage().contains("同源"));
        SkillVideoFacts changed = first.merge(SkillVideoFacts.parse("提交: POST https://another.example.com/task"));
        assertTrue(changed.missing().contains("提交响应任务 ID 字段"));
        assertTrue(changed.missing().contains("提示词字段"));
        assertTrue(SkillVideoFacts.parse("普通视频需求，仅包含描述").isEmpty());
    }

    @Test public void rejectsSecretsInIgnoredNotesAndRejectsUnknownPersistedProperties() throws Exception {
        String sentinel = "synthetic-private-fixture";
        for (String extra : List.of("Authorization: Bearer " + sentinel, "Authorization: Basic " + sentinel,
                "X-API-Key: " + sentinel, "接口说明 {\"password\":\"" + sentinel + "\"}",
                "相关页面 https://docs.example.com/help?access_token=" + sentinel, "密钥：" + sentinel)) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> SkillVideoFacts.parse(SUBMIT + extra));
            assertFalse(error.getMessage().contains(sentinel));
        }
        assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.parse(" ".repeat(16_001)));
        JSONObject stored = SkillVideoFacts.parse(SUBMIT).toJson().put("headers", new JSONObject());
        assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.fromJson(stored));
    }

    @Test public void approvedDeclarationReuseCopiesFieldsAndOnlySuggestsTheApprovedPublicAddress() throws Exception {
        JSONObject http = SkillVideoFacts.parse(SUBMIT + POLLING).declaration();
        http.getJSONObject("request").put("include_audio", "config.audio");
        JSONObject fields = new JSONObject()
                .put("key", "audio").put("label", "生成音频").put("type", "boolean").put("required", false);
        JSONObject authored = new JSONObject("""
                {"schemaVersion":4,"name":"已配置视频","description":"视频生成","documentation":"填写场景",
                 "instructions":"保留场景","capabilityId":"video_task",
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"海边","expectedType":"video"}}
                """).put("configFields", new JSONArray().put(fields)).put("http", http);
        SkillVideoFacts reused = SkillVideoFacts.fromDeclaration(SkillManifest.parse(authored.toString()),
                "https://approved.example.com:8443/v2/tasks");
        assertEquals("https://approved.example.com:8443/v2/tasks",
                reused.declaration().getString("suggestedEndpoint"));
        assertEquals("config.audio", reused.declaration().getJSONObject("request").getString("include_audio"));
        assertEquals("audio", reused.configFields().getJSONObject(0).getString("key"));
        assertFalse(reused.toJson().toString().contains("已配置视频"));
        assertFalse(reused.toJson().toString().contains("保留场景"));
        assertFalse(reused.toJson().has("configValues"));
        assertEquals(reused.declaration().toString(), SkillVideoFacts.fromJson(reused.toJson()).declaration().toString());
    }

    @Test public void documentedOrdinaryFieldsMergeAsTypedRequestSettings() throws Exception {
        SkillVideoFacts fields = SkillVideoFacts.parse("""
                必填文本字段: model 模型名
                选填整数字段: seconds 时长
                """);
        assertTrue(fields.missing().contains("提示词字段"));
        SkillVideoFacts supplemented = SkillVideoFacts.parse(SUBMIT).merge(fields)
                .merge(SkillVideoFacts.parse(POLLING));
        assertTrue(supplemented.missing().isEmpty());
        JSONObject request = supplemented.declaration().getJSONObject("request");
        assertEquals("argument", request.getString("prompt"));
        assertEquals("config.model", request.getString("model"));
        assertEquals("config.seconds", request.getString("seconds"));
        JSONArray config = SkillVideoFacts.fromJson(supplemented.toJson()).configFields();
        assertEquals("text", config.getJSONObject(0).getString("type"));
        assertTrue(config.getJSONObject(0).getBoolean("required"));
        assertEquals("integer", config.getJSONObject(1).getString("type"));
        assertFalse(config.getJSONObject(1).getBoolean("required"));
        assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.parse("必填文本字段: api_key 供应商密钥"));
    }
}
