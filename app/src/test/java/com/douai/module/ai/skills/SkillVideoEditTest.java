package com.douai.module.ai.skills;

import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public final class SkillVideoEditTest {
    @Test public void bareTaskIdMeansSubmissionAndExplicitStatusIdRemainsSeparate() {
        var submit = SkillVideoEdit.parse("任务 ID 是 data.id");
        assertEquals(SkillVideoEdit.Mode.FILL, submit.mode());
        assertEquals(Map.of("submitIdField", "data.id"), submit.fields());
        assertTrue(submit.replacements().isEmpty());

        var status = SkillVideoEdit.parse("请把查询响应的任务 ID 字段改为 `result.id`。");
        assertEquals(SkillVideoEdit.Mode.REPLACE, status.mode());
        assertEquals(Map.of("statusIdField", "result.id"), status.fields());
        assertEquals(Set.of("statusIdField"), status.replacements());
    }

    @Test public void multipleKnownClausesPreserveIndependentReplacementIntentAndTypedQueryMode() {
        var edit = SkillVideoEdit.parse("把查询方式改成 POST，查询 POST 路径是 /v2/status，查询请求 ID 字段用 task_id；状态字段是 data.status");
        assertEquals(SkillVideoEdit.Mode.REPLACE, edit.mode());
        assertEquals("json_post", edit.queryMode());
        assertTrue(edit.replaceQueryMode());
        assertEquals(Map.of("statusPostPath", "/v2/status", "statusPostIdField", "task_id", "stateField", "data.status"), edit.fields());
        assertTrue(edit.replacements().isEmpty());

        var fill = SkillVideoEdit.parse("查询方式是返回地址 GET，同时成片地址字段是 data.video.url");
        assertEquals("response_url_get", fill.queryMode());
        assertFalse(fill.replaceQueryMode());
        assertEquals(Map.of("resultUrlField", "data.video.url"), fill.fields());
        assertEquals("fixed_get", SkillVideoEdit.parse("把轮询方法换成固定 GET").queryMode());
    }

    @Test public void statePhrasesUseCanonicalMappingsWithoutEatingAdjacentClauses() {
        var edit = SkillVideoEdit.parse("状态映射是 waiting=queued，处理中=running；done 表示完成；把提示词字段改为 `text_prompt`");
        assertEquals(SkillVideoEdit.Mode.CLARIFY, edit.mode());
        assertTrue(edit.fields().isEmpty());

        var supported = SkillVideoEdit.parse("状态映射是 waiting=queued, processing=处理中；done 表示完成；把提示词字段改为 `text_prompt`");
        assertEquals(Map.of("waiting", "queued", "processing", "running", "done", "completed"), supported.stateMappings());
        assertEquals(Map.of("promptField", "text_prompt"), supported.fields());
        assertEquals(Set.of("promptField"), supported.replacements());
        assertEquals(Map.of("ready", "completed"), SkillVideoEdit.parse("完成状态是 ready").stateMappings());
    }

    @Test public void explicitNaturalRouteStatementsKeepTheMethodAndRelativePathDistinct() {
        var docs = SkillVideoEdit.parse("提交接口是 POST /v1/videos。查询接口是 GET /jobs/{id}。查询响应任务 ID 是 job.id。");
        assertEquals(Map.of("submitPath", "/v1/videos", "statusPath", "/jobs/{id}", "statusIdField", "job.id"), docs.fields());
        assertEquals("fixed_get", docs.queryMode());
        assertFalse(docs.replaceQueryMode());
        var post = SkillVideoEdit.parse("查询 POST 路径是 /jobs/status，POST 查询请求任务 ID 字段是 task_id");
        assertEquals(Map.of("statusPostPath", "/jobs/status", "statusPostIdField", "task_id"), post.fields());
    }

    @Test public void encodingStatementsAreClassifiedWithoutConvertingThemIntoEditableFieldValues() {
        var multipart = SkillVideoEdit.parse("提交请求编码是 multipart/form-data");
        assertEquals(SkillVideoEdit.Mode.UNSUPPORTED_ENCODING, multipart.mode());
        assertEquals("multipart/form-data", multipart.submissionEncoding());
        assertTrue(multipart.fields().isEmpty());

        var json = SkillVideoEdit.parse("把提交格式设为 JSON");
        assertEquals(SkillVideoEdit.Mode.NEEDS_ENCODING_EVIDENCE, json.mode());
        assertEquals("application/json", json.submissionEncoding());
        assertTrue(json.fields().isEmpty());
    }

    @Test public void encodingClassificationKeepsOtherFactsForAnExplicitlyReferencedDocument() {
        String document = "提交接口是 POST /v1/videos。提交请求编码是 application/json。完成状态是 done。查询接口是 GET /jobs/{id}。";
        var json = SkillVideoEdit.parse(document);
        assertEquals(SkillVideoEdit.Mode.NEEDS_ENCODING_EVIDENCE, json.mode());
        assertEquals(Map.of("submitPath", "/v1/videos", "statusPath", "/jobs/{id}"), json.fields());
        assertEquals(Map.of("done", "completed"), json.stateMappings());
        assertEquals("fixed_get", json.queryMode());
        var multipart = SkillVideoEdit.parse(document.replace("application/json", "multipart/form-data"));
        assertEquals(SkillVideoEdit.Mode.UNSUPPORTED_ENCODING, multipart.mode());
        assertEquals(json.fields(), multipart.fields());
        assertEquals(json.stateMappings(), multipart.stateMappings());
        assertEquals(json.queryMode(), multipart.queryMode());
    }

    @Test public void quotedReportedOrdinaryAndNegativeTextAreNeverActions() {
        for (String text : List.of("‘状态字段是 data.status’", "`任务 ID 是 data.id`", "朋友说任务 ID 是 data.id",
                "不要把查询方式改成 POST", "状态字段是 data.status 吗？", "如何把查询路径改成 /jobs/{id}",
                "今天的天气是晴天", "请帮我写一个视频技能", "> 任务 ID 是 data.id", "/技能 生成视频")) {
            assertNull(text, SkillVideoEdit.parse(text));
        }
    }

    @Test public void conflictingOrIncompleteStatementsClarifyWithoutReturningAnyPartialPatch() {
        for (String text : List.of("状态字段是 data.status，状态字段是 data.state", "把查询方式改成 PATCH",
                "任务 ID 是 data.id；然后再发起新任务", "把查询路径改成", "把提交地址改为 “https://api.example.com/v1",
                "把状态字段改成 " + "a".repeat(450))) {
            var edit = SkillVideoEdit.parse(text);
            assertEquals(text, SkillVideoEdit.Mode.CLARIFY, edit.mode());
            assertTrue(edit.fields().isEmpty());
            assertTrue(edit.stateMappings().isEmpty());
            assertTrue(edit.queryMode().isEmpty());
        }
    }
}
