package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.List;
import java.util.Map;

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
        JSONObject http = SkillVideoFacts.parse(SUBMIT + POLLING).withMinimumPollSeconds(9).declaration();
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
        assertEquals(9, reused.minimumPollSeconds());
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

    @Test public void manualFormEditsAndExplicitClearsSurviveDocumentExtractionAndRestore() throws Exception {
        SkillVideoFacts initial = SkillVideoFacts.parse(SUBMIT + POLLING);
        assertEquals(SkillVideoFacts.Source.DOCUMENT, form(initial, "submitEndpoint").source());
        assertEquals(SkillVideoFacts.FieldType.HTTPS_URL, form(initial, "submitEndpoint").type());
        assertEquals(SkillVideoFacts.Source.DOCUMENT,
                form(initial.withForm(Map.of("submitEndpoint", form(initial, "submitEndpoint").value())), "submitEndpoint").source());
        SkillVideoFacts manual = initial.withForm(Map.of(
                "statusPath", "/adjusted/{id}/status",
                "cancelPath", "",
                "cancelMethod", "",
                "downloadPath", "",
                "resultUrlField", "data.output.video_url"));
        SkillVideoFacts restored = SkillVideoFacts.fromJson(manual.toJson()).fillMissingFrom(initial);
        assertTrue(restored.isComplete());
        assertEquals("/adjusted/{id}/status", form(restored, "statusPath").value());
        assertEquals(SkillVideoFacts.Source.MANUAL, form(restored, "statusPath").source());
        assertEquals(SkillVideoFacts.Source.MANUAL, form(restored, "cancelPath").source());
        assertEquals("", form(restored, "cancelPath").value());
        assertEquals(SkillVideoFacts.Source.DOCUMENT, form(restored, "promptField").source());
        JSONObject routes = restored.declaration().getJSONObject("video");
        assertFalse(routes.has("downloadPath"));
        assertFalse(routes.has("cancelPath"));
        assertEquals("data.output.video_url", routes.getString("resultUrlField"));
        assertThrows(IllegalArgumentException.class, () -> restored.withForm(Map.of("unknown", "value")));
    }

    @Test public void incompleteFormSupportsPartialCancelAndLegacyStoredProvenance() throws Exception {
        SkillVideoFacts partial = SkillVideoFacts.fromForm(Map.of("cancelMethod", "delete", "promptField", "prompt"));
        assertFalse(partial.isComplete());
        assertTrue(form(partial, "cancelPath").required());
        assertEquals("DELETE", form(partial, "cancelMethod").value());
        assertEquals(SkillVideoFacts.Source.MANUAL, form(partial, "cancelMethod").source());
        JSONObject serialized = SkillVideoFacts.parse(SUBMIT).toJson();
        serialized.remove("provenance");
        SkillVideoFacts legacy = SkillVideoFacts.fromJson(serialized);
        assertEquals(SkillVideoFacts.Source.STORED, form(legacy, "submitEndpoint").source());
        assertEquals(SkillVideoFacts.Source.EMPTY, form(legacy, "stateField").source());
        assertThrows(IllegalArgumentException.class,
                () -> legacy.fillMissingFrom(SkillVideoFacts.parse("提交: POST https://different.example.com/submit")));
    }

    @Test public void examplesExtractUniqueNamedFieldsWhileRoutesAndMappingsRemainExplicit() throws Exception {
        SkillVideoFacts examples = SkillVideoFacts.parse("""
                **提交**: POST `https://video.example.com:8443/v1/tasks`
                请求示例: {"prompt":"海边","model":"provider-model"}
                提交响应示例:
                ```json
                {
                  "data":{"task_id":"fixture"}
                }
                ```
                查询响应示例: {"data":{"task_id":"fixture","status":"done","video_url":"https://video.example.com:8443/v1/files/fixture"}}
                curl -X GET https://undocumented.example.com/v1/tasks/{id}
                """);
        assertEquals("prompt", form(examples, "promptField").value());
        assertEquals("data.task_id", form(examples, "submitIdField").value());
        assertEquals("data.task_id", form(examples, "statusIdField").value());
        assertEquals("data.status", form(examples, "stateField").value());
        assertEquals("data.video_url", form(examples, "resultUrlField").value());
        assertTrue(examples.missing().contains("查询 GET 路径（含 {id}）"));
        assertTrue(examples.missing().contains("状态映射中的完成状态"));
        assertFalse(examples.toJson().toString().contains("provider-model"));
        assertFalse(examples.toJson().toString().contains("files/fixture"));
        assertTrue(examples.withForm(Map.of("statusPath", "/v1/tasks/{id}", "states", "done=completed")).isComplete());
        SkillVideoFacts ambiguous = SkillVideoFacts.parse("""
                提交响应示例: {"data":{"id":"one","job_id":"two"}}
                查询响应示例: {"data":{"video_url":"not-an-address"}}
                """);
        assertEquals("", form(ambiguous, "submitIdField").value());
        assertEquals("", form(ambiguous, "resultUrlField").value());
    }

    @Test public void markdownAndCurlPrefillGroundedFieldsButKeepMultipartPending() throws Exception {
        SkillVideoFacts facts = SkillVideoFacts.parseReference("""
                ## 视频 API
                ### 1. 创建视频任务
                | 项目 | 说明 |
                | --- | --- |
                | 请求 | `POST <https://video.example.com/v1/videos|创建任务>` |
                | Content-Type | `multipart/form-data` |
                | 提示词字段 | `prompt` |
                | 响应任务 ID 字段 | `id` |

                | 参数 | 类型 | 示例值 |
                | --- | --- | --- |
                | `prompt` | string | `a-unique-example` |

                ```bash
                curl -X POST https://video.example.com/v1/videos -H "Authorization: Bearer $PROVIDER_API_KEY" -F "model=video-example" -F "prompt=a-unique-example"
                ```

                ### 2. 查询视频任务
                | 请求 | `GET [查询](https://video.example.com/v1/videos/{video_id})` |
                | 任务 ID 字段 | `id` |
                | 状态字段 | `status` |

                | 状态值 | 含义 |
                | --- | --- |
                | `queued` | 排队中 |
                | `in_progress` | 处理中 |
                | `completed` | 已完成 |
                | `failed` | 失败 |

                ### 3. 下载视频
                | 请求 | `GET <https://video.example.com/v1/videos/{video_id}/content|成片>` |
                """);
        assertEquals("https://video.example.com/v1/videos", form(facts, "submitEndpoint").value());
        assertEquals("prompt", form(facts, "promptField").value());
        assertEquals("id", form(facts, "submitIdField").value());
        assertEquals("/v1/videos/{id}", form(facts, "statusPath").value());
        assertEquals("id", form(facts, "statusIdField").value());
        assertEquals("status", form(facts, "stateField").value());
        assertEquals("/v1/videos/{id}/content", form(facts, "downloadPath").value());
        assertTrue(form(facts, "states").value().contains("in_progress=running"));
        assertEquals("multipart/form-data", facts.submitEncoding());
        assertFalse(facts.supportsSubmissionEncoding());
        assertFalse(facts.isComplete());
        assertEquals(1, facts.missing().size());
        assertTrue(facts.missing().get(0).contains("multipart"));
        assertThrows(IllegalArgumentException.class, facts::declaration);
        JSONObject stored = facts.toJson();
        assertFalse(stored.toString().contains("PROVIDER_API_KEY"));
        assertFalse(stored.toString().contains("a-unique-example"));
        assertFalse(stored.toString().contains("video-example"));
        assertEquals("multipart/form-data", SkillVideoFacts.fromJson(stored).submitEncoding());
    }

    @Test public void curlEvidenceRequiresStaticArgumentsAndScopedPollingTemplates() throws Exception {
        SkillVideoFacts facts = SkillVideoFacts.parseReference("""
                ### 创建视频
                curl -X POST --json '{"prompt":"测试示例"}' -H 'Authorization: Bearer ${VIDEO_API_KEY}' https://video.example.com/v1/tasks
                ### 查询视频
                curl https://video.example.com/v1/tasks/concrete-example
                """);
        assertEquals("application/json", facts.submitEncoding());
        assertEquals("prompt", form(facts, "promptField").value());
        assertEquals("", form(facts, "statusPath").value());
        assertFalse(facts.toJson().toString().contains("concrete-example"));
        assertFalse(facts.toJson().toString().contains("测试示例"));
        assertFalse(facts.toJson().toString().contains("VIDEO_API_KEY"));

        for (String curl : List.of(
                "curl -X POST -H 'Authorization: Bearer synthetic-secret-value' https://video.example.com/v1/tasks",
                "curl -X POST -H 'Authorization: Basic synthetic-secret-value' https://video.example.com/v1/tasks",
                "curl -X POST -H 'Authorization: Bearer ${VIDEO_API_KEY:-synthetic-secret-value}' https://video.example.com/v1/tasks",
                "curl -F 'prompt=$(read-untrusted)' https://video.example.com/v1/tasks",
                "curl 'https://video.example.com/v1/$VIDEO_API_KEY' -F 'prompt=example'",
                "curl https://video.example.com/v1/tasks | secondary-command",
                "### 创建视频\n| Authorization | Basic synthetic-secret-value |")) {
            var denied = assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.parse(curl));
            assertFalse(denied.getMessage().contains("synthetic-secret-value"));
        }
    }

    @Test public void undocumentedSubmitEncodingStaysPendingWhileLegacyLabelsStayCompatible() throws Exception {
        SkillVideoFacts bare = SkillVideoFacts.parseReference("""
                ### 创建视频
                | 方法 | POST |
                | URL | https://video.example.com/v1/tasks |
                """);
        assertEquals("https://video.example.com/v1/tasks", form(bare, "submitEndpoint").value());
        assertEquals("unknown", bare.submitEncoding());
        assertTrue(bare.missing().stream().anyMatch(message -> message.contains("application/json")));
        assertEquals("", SkillVideoFacts.parse(SUBMIT).submitEncoding());
        assertTrue(SkillVideoFacts.parse(SUBMIT + POLLING).isComplete());
    }

    @Test public void doublyQuotedSlackReferenceUsesTheDescribedIdAndLeavesUndocumentedStatesMissing() throws Exception {
        SkillVideoFacts facts = SkillVideoFacts.parseReference("""
                > > ## 视频 API 接口资料
                > > ### 1. 创建视频任务
                > > | 项目 | 说明 |
                > > | --- | --- |
                > > | 请求 | `POST <https://video.example.com/v1/videos|创建任务>` |
                > > | Content-Type | `multipart/form-data` |
                > > | 提示词字段 | `prompt` |
                > >
                > > ```bash
                > > curl -X POST https://video.example.com/v1/videos \\
                > >   -H "Authorization: Bearer $VIDEO_API_KEY" \\
                > >   -F "prompt=仅为示例" -F "model=example-model"
                > > ```
                > >
                > > 从返回结果中保存 `id`，作为后续轮询用的 `video_id`。模型名称、时长和尺寸以你的账号实际可用选项为准。
                > >
                > > ### 2. 查询视频任务
                > > | 请求 | `GET <https://video.example.com/v1/videos/{video_id}|查询>` |
                > > | 状态字段 | `status` |
                > > | 任务 ID 字段 | `id` |
                > >
                > > ### 3. 下载生成的视频
                > > | 请求 | `GET <https://video.example.com/v1/videos/{video_id}/content|成片>` |
                """);
        assertEquals("id", form(facts, "submitIdField").value());
        assertEquals("https://video.example.com/v1/videos", form(facts, "submitEndpoint").value());
        assertEquals("/v1/videos/{id}", form(facts, "statusPath").value());
        assertEquals("status", form(facts, "stateField").value());
        assertEquals("/v1/videos/{id}/content", form(facts, "downloadPath").value());
        assertEquals("", form(facts, "states").value());
        assertTrue(facts.missing().contains("状态映射中的完成状态"));
        assertTrue(facts.missing().stream().anyMatch(reason -> reason.contains("multipart")));
        assertFalse(facts.toJson().toString().contains("仅为示例"));
        assertFalse(facts.toJson().toString().contains("example-model"));
        assertFalse(facts.toJson().toString().contains("VIDEO_API_KEY"));
    }

    @Test public void chatReferencesIgnoreGenericFactsAndMalformedOrdinaryAnswers() {
        assertFalse(SkillVideoFacts.parse("名称：普通工具").isEmpty());
        assertTrue(SkillVideoFacts.parseReference("名称：普通工具").isEmpty());
        assertTrue(SkillVideoFacts.parseReference("curl https://ordinary.example.com/test | other-command").isEmpty());
        assertTrue(SkillVideoFacts.parseReference("普通内容".repeat(5_000)).isEmpty());
        assertTrue(SkillVideoFacts.parseReference(SUBMIT).isEmpty());
        SkillVideoFacts unconfirmed = SkillVideoFacts.parseReference(SUBMIT + POLLING);
        assertFalse(unconfirmed.isEmpty());
        assertFalse(unconfirmed.supportsSubmissionEncoding());
        SkillVideoFacts explicit = SkillVideoFacts.parseReference(
                "### 创建视频任务\n**Content-Type:** `multipart/form-data`\n" + SUBMIT + POLLING);
        assertEquals("multipart/form-data", explicit.submitEncoding());
        SkillVideoFacts headingless = SkillVideoFacts.parseReference(
                SUBMIT + "\nContent-Type: multipart/form-data\n" + POLLING + "\nContent-Type: video/mp4");
        assertEquals("multipart/form-data", headingless.submitEncoding());
    }

    @Test public void documentedRelativeSubmitRequiresExplicitJsonAndSameOriginApiBase() throws Exception {
        String notes = """
                提交: POST /v1/videos
                提示词字段: prompt
                提交响应ID: id
                查询: GET https://video.example.com:8443/v1/videos/{id}
                查询响应ID: id
                状态字段: status
                状态映射: done=completed
                下载: GET /v1/videos/{id}/content
                """;
        SkillVideoFacts unknown = SkillVideoFacts.parseReference(notes);
        assertEquals("unknown", unknown.submitEncoding());
        assertFalse(unknown.isComplete());
        SkillVideoFacts facts = SkillVideoFacts.parseReference(notes + "提交请求编码: application/json");
        assertTrue(facts.isComplete());
        assertTrue(facts.needsApiBase());
        assertEquals("/v1/videos", facts.submitPath());
        assertEquals("", form(facts, "submitEndpoint").value());
        assertFalse(form(facts, "submitEndpoint").required());
        assertFalse(facts.declaration().has("suggestedEndpoint"));
        assertEquals("/v1/videos/{id}", facts.declaration().getJSONObject("video").getString("statusPath"));
        SkillVideoFacts reopened = SkillVideoFacts.fromJson(facts.toJson());
        String base = "https://video.example.com:8443";
        assertEquals(base + "/v1/videos", reopened.resolveApiBase(base));
        assertEquals(base, reopened.apiBaseForEndpoint(base + "/v1/videos"));
        assertEquals("", reopened.apiBaseForEndpoint(base + "/custom/submit"));
        for (String bad : List.of("http://video.example.com:8443", "https://video.example.com",
                "https://different.example.com:8443", base + "/gateway", base + "?query=1")) {
            assertThrows(IllegalArgumentException.class, () -> reopened.resolveApiBase(bad));
        }
        for (String badRoute : List.of("//other.example/v1", "/v1/../videos", "/v1/%2Fvideos", "/v1/{id}")) {
            assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.parse("提交: POST " + badRoute));
        }
    }

    @Test public void minimumPollingIntervalIsAnExplicitLocalPreference() throws Exception {
        SkillVideoFacts source = SkillVideoFacts.parse(SUBMIT + POLLING + "minPollSeconds: 50");
        assertEquals(0, source.minimumPollSeconds());
        assertFalse(source.declaration().getJSONObject("video").has("minPollSeconds"));
        assertThrows(IllegalArgumentException.class, () -> source.withMinimumPollSeconds(0));
        assertThrows(IllegalArgumentException.class, () -> source.withMinimumPollSeconds(2));
        assertThrows(IllegalArgumentException.class, () -> source.withMinimumPollSeconds(61));
        assertThrows(IllegalArgumentException.class, () -> source.withForm(Map.of("minPollSeconds", "25")));
        SkillVideoFacts selected = source.withMinimumPollSeconds(15);
        JSONObject stored = selected.toJson();
        assertEquals(15, stored.get("minPollSeconds"));
        assertEquals("MANUAL", stored.getJSONObject("provenance").getString("minPollSeconds"));
        SkillVideoFacts reopened = SkillVideoFacts.fromJson(stored).fillMissingFrom(source);
        assertEquals(15, reopened.minimumPollSeconds());
        assertEquals(15, reopened.declaration().getJSONObject("video").get("minPollSeconds"));
        assertThrows(IllegalArgumentException.class, () -> SkillVideoFacts.fromJson(stored.put("minPollSeconds", "15")));
    }

    @Test public void explicitSupplementEnrichesOnlyInferredUnknownForTheSameContract() {
        SkillVideoFacts missingEncoding = SkillVideoFacts.parseSupplement(SUBMIT + POLLING);
        SkillVideoFacts json = SkillVideoFacts.parseSupplement("接口资料：\nContent-Type: application/json; charset=utf-8");
        SkillVideoFacts multipart = SkillVideoFacts.parseSupplement("Content-Type: multipart/form-data");
        assertEquals("unknown", missingEncoding.submitEncoding());
        assertFalse(missingEncoding.isComplete());
        assertTrue(missingEncoding.fillMissingFrom(json).isComplete());
        assertEquals("multipart/form-data", missingEncoding.fillMissingFrom(multipart).submitEncoding());
        assertFalse(missingEncoding.fillMissingFrom(multipart).fillMissingFrom(json).isComplete());
        SkillVideoFacts manual = SkillVideoFacts.parse(SUBMIT + POLLING).withForm(Map.of("submitEncoding", "unknown"));
        assertFalse(manual.fillMissingFrom(json).isComplete());
        assertThrows(IllegalArgumentException.class, () -> missingEncoding.fillMissingFrom(
                SkillVideoFacts.parseSupplement("提交: POST /v2/different\n提交请求编码: application/json")));
        assertFalse(missingEncoding.fillMissingFrom(SkillVideoFacts.parseSupplement(
                "状态字段: incompatible\n提交请求编码: application/json")).isComplete());
        assertEquals("data.id", form(SkillVideoFacts.parseSupplement(
                "提交响应示例: {\"data\":{\"id\":\"fixture\"}}"), "submitIdField").value());
    }

    @Test public void videoFactsKeepSubmitReturnedUrlAndPostIdModesSeparateFromFixedGet() throws Exception {
        String transitions = POLLING.replaceFirst("(?m)^Status: GET[^\\r\\n]*\\R", "");
        SkillVideoFacts dynamic = SkillVideoFacts.parse(SUBMIT + transitions + "\n查询地址字段: data.status_url");
        assertTrue(dynamic.isComplete());
        assertEquals("", form(dynamic, "statusPath").value());
        assertEquals("data.status_url", form(dynamic, "statusUrlField").value());
        assertEquals("data.status_url", dynamic.declaration().getJSONObject("video").getString("statusUrlField"));
        assertEquals(dynamic.declaration().toString(), SkillVideoFacts.fromJson(dynamic.toJson()).declaration().toString());

        SkillVideoFacts partial = SkillVideoFacts.parse(SUBMIT + transitions + "\n查询: POST /v1/jobs/progress");
        assertFalse(partial.isComplete());
        assertTrue(form(partial, "statusPostIdField").required());
        assertEquals("/v1/jobs/progress", form(partial, "statusPostPath").value());
        SkillVideoFacts ready = partial.fillMissingFrom(SkillVideoFacts.parseSupplement("查询请求ID字段: task_id"));
        assertTrue(ready.isComplete());
        JSONObject query = ready.declaration().getJSONObject("video");
        assertEquals("/v1/jobs/progress", query.getString("statusPostPath"));
        assertEquals("task_id", query.getString("statusPostIdField"));
        assertFalse(query.has("statusPath"));
        assertFalse(query.has("statusUrlField"));

        SkillVideoFacts incompatible = ready.withForm(Map.of("statusPath", "/v1/jobs/{id}"));
        assertFalse(incompatible.isComplete());
        assertThrows(IllegalArgumentException.class, incompatible::declaration);
        assertEquals(query.toString(), ready.fillMissingFrom(dynamic).declaration().getJSONObject("video").toString());
        assertThrows(IllegalArgumentException.class,
                () -> SkillVideoFacts.parse("查询: POST /v1/jobs/{id}"));
        assertThrows(IllegalArgumentException.class,
                () -> SkillVideoFacts.parseSupplement("查询请求ID字段: data.id"));
    }

    @Test public void postQueryDocumentationNeedsExplicitPathKeyAndJsonEvidence() throws Exception {
        SkillVideoFacts facts = SkillVideoFacts.parseReference("""
                ### 创建视频任务
                | 请求 | POST https://example.com/v1/tasks |
                | Content-Type | application/json |
                | 提示词字段 | prompt |
                | 响应任务 ID 字段 | id |

                ### 查询视频任务
                | 请求 | POST https://example.com/v1/tasks/progress |
                | 查询 POST 任务 ID 字段 | task_id |
                | 状态字段 | state |
                | 任务 ID 字段 | id |
                | 状态映射 | done=completed |

                ### 下载视频
                | 请求 | GET https://example.com/v1/tasks/{id}/content |
                """);
        assertTrue(facts.isComplete());
        assertEquals("task_id", facts.declaration().getJSONObject("video").getString("statusPostIdField"));
        assertEquals("", form(facts, "statusPath").value());

        SkillVideoFacts multiBody = SkillVideoFacts.parseReference("""
                ### 查询视频任务
                curl --json '{"task_id":"fixture"}' --data-raw extra https://example.com/v1/tasks/progress
                """);
        assertEquals("https://example.com/v1/tasks/progress", form(multiBody, "statusPostPath").value());
        assertEquals("", form(multiBody, "statusPostIdField").value());
    }

    @Test public void groundedFieldsPreserveUnmentionedValuesAndRejectKnownConflictsAtomically() throws Exception {
        SkillVideoFacts base = SkillVideoFacts.parse(SUBMIT + POLLING).withMinimumPollSeconds(11);
        SkillVideoFacts filled = base.applyGroundedPatch(Map.of("label", "视频服务"),
                Map.of("ready", "completed", "waiting", "queued"), null, java.util.Set.of());
        assertEquals("fixed_get", filled.queryMode());
        assertEquals(11, filled.minimumPollSeconds());
        assertEquals("/v1/tasks/{id}", form(filled, "statusPath").value());
        JSONObject states = filled.declaration().getJSONObject("video").getJSONObject("states");
        assertEquals("queued", states.getString("waiting"));
        assertEquals("completed", states.getString("done"));
        assertEquals("completed", states.getString("ready"));

        String before = filled.toJson().toString();
        assertThrows(IllegalArgumentException.class, () -> filled.applyGroundedPatch(
                Map.of("label", "另一个标签"), Map.of("waiting", "failed"), null));
        assertThrows(IllegalArgumentException.class, () -> filled.applyGroundedPatch(
                Map.of("statusIdField", "task.id"), Map.of(), "", java.util.Set.of()));
        assertThrows(IllegalArgumentException.class, () -> filled.applyGroundedPatch(
                Map.of("states", "done=failed"), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> filled.applyGroundedPatch(
                Map.of("submitEncoding", "application/json"), Map.of(), null));
        assertThrows(IllegalArgumentException.class, () -> filled.applyGroundedPatch(
                Map.of("label", ""), Map.of(), null));
        assertEquals(before, filled.toJson().toString());
        SkillVideoFacts revised = filled.applyGroundedPatch(Map.of("statusIdField", "task.id"),
                Map.of(), null, java.util.Set.of("statusIdField"));
        assertEquals("task.id", form(revised, "statusIdField").value());
        assertEquals(form(filled, "statusPath"), form(revised, "statusPath"));
    }

    @Test public void explicitlySelectedQueryModeClearsOnlyObsoleteSelectorsAndSurvivesRestore() throws Exception {
        SkillVideoFacts base = SkillVideoFacts.parse(SUBMIT + POLLING);
        assertThrows(IllegalArgumentException.class,
                () -> base.applyGroundedPatch(Map.of("statusPostPath", "/jobs/progress"), Map.of(), null));
        assertThrows(IllegalArgumentException.class,
                () -> base.applyGroundedPatch(Map.of("statusPath", "/jobs/{id}"), Map.of(), "json_post"));
        SkillVideoFacts incomplete = base.applyGroundedPatch(Map.of(), Map.of(), "json_post");
        assertEquals("json_post", incomplete.explicitQueryMode());
        assertFalse(incomplete.isComplete());
        assertEquals("", form(incomplete, "statusPath").value());
        assertEquals("data.task_id", form(incomplete, "statusIdField").value());
        assertEquals("data.status", form(incomplete, "stateField").value());
        assertEquals(form(base, "cancelPath"), form(incomplete, "cancelPath"));
        assertEquals(form(base, "downloadPath"), form(incomplete, "downloadPath"));
        assertTrue(incomplete.missing().stream().anyMatch(reason -> reason.contains("POST 路径")));
        assertTrue(form(incomplete, "statusPostIdField").required());

        SkillVideoFacts reopened = SkillVideoFacts.fromJson(incomplete.toJson()).fillMissingFrom(base);
        assertEquals("json_post", reopened.queryMode());
        assertEquals("", form(reopened, "statusPath").value());
        SkillVideoFacts post = reopened.applyGroundedPatch(
                Map.of("statusPostPath", "/jobs/progress", "statusPostIdField", "job_id"), Map.of(), null);
        assertTrue(post.isComplete());
        assertEquals("job_id", post.declaration().getJSONObject("video").getString("statusPostIdField"));
        java.util.Map<String, String> manual = new java.util.LinkedHashMap<>();
        for (SkillVideoFacts.FormField field : post.formFields()) {
            if (field.source() == SkillVideoFacts.Source.MANUAL) manual.put(field.id(), field.value());
        }
        assertEquals(post.declaration().toString(), base.withForm(manual).fillMissingFrom(post).declaration().toString());

        SkillVideoFacts dynamic = post.applyGroundedPatch(Map.of(), Map.of(), "response_url_get");
        assertEquals("", form(dynamic, "statusPostPath").value());
        assertEquals("", form(dynamic, "statusPostIdField").value());
        assertTrue(form(dynamic, "statusUrlField").required());
        assertFalse(dynamic.isComplete());
        assertTrue(dynamic.applyGroundedPatch(Map.of("statusUrlField", "data.status_url"), Map.of(), null).isComplete());
    }

    @Test public void assistantLocationIsVisibleButNeedsExplicitVerificationAndDoesNotBypassOtherChecks() throws Exception {
        String endpoint = "https://video.example.com:8443/v1/tasks";
        SkillVideoFacts full = SkillVideoFacts.parse(SUBMIT + POLLING);
        SkillVideoFacts suggested = SkillVideoFacts.fromJson(full.asChatSuggestion().toJson());
        assertEquals(endpoint, form(suggested, "submitEndpoint").value());
        assertEquals(SkillVideoFacts.Source.CHAT_SUGGESTION, form(suggested, "submitEndpoint").source());
        assertTrue(suggested.needsSubmissionVerification());
        assertFalse(suggested.isComplete());
        assertThrows(IllegalArgumentException.class, suggested::declaration);
        assertTrue(suggested.fillMissingFrom(full.asChatSuggestion()).needsSubmissionVerification());
        SkillVideoFacts typed = suggested.applyGroundedPatch(Map.of("submitEndpoint", endpoint), Map.of(), null);
        assertEquals(SkillVideoFacts.Source.MANUAL, form(typed, "submitEndpoint").source());
        assertFalse(typed.needsSubmissionVerification());
        assertTrue(typed.isComplete());
        SkillVideoFacts sameProof = SkillVideoFacts.parseSupplement(
                "提交: POST " + endpoint + "\n提交请求编码: application/json");
        SkillVideoFacts verified = suggested.fillMissingFrom(sameProof);
        assertEquals(SkillVideoFacts.Source.DOCUMENT, form(verified, "submitEndpoint").source());
        assertTrue(verified.isComplete());

        SkillVideoFacts noResponseId = SkillVideoFacts.parse((SUBMIT + POLLING).replace("Status ID: data.task_id", ""))
                .asChatSuggestion().withForm(Map.of("submitEndpoint", endpoint));
        assertFalse(noResponseId.needsSubmissionVerification());
        assertTrue(noResponseId.missing().contains("查询响应任务 ID 字段"));
        SkillVideoFacts relative = SkillVideoFacts.parseSupplement((SUBMIT + POLLING)
                        .replace("https://video.example.com:8443/v1/tasks", "/v1/tasks")
                        + "\n提交请求编码: multipart/form-data")
                .asChatSuggestion();
        assertTrue(relative.needsSubmissionVerification());
        SkillVideoFacts relativeTyped = relative.withForm(Map.of("submitPath", "/v1/tasks"));
        assertFalse(relativeTyped.needsSubmissionVerification());
        assertFalse(relativeTyped.isComplete());
    }

    private static SkillVideoFacts.FormField form(SkillVideoFacts facts, String id) {
        return facts.formFields().stream().filter(field -> field.id().equals(id)).findFirst().orElseThrow();
    }
}
