package com.douai.module.ai.skills;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillHttpDeclarationTest {
    private static final String INVOKE = """
            {"version":1,"label":"服务商","request":{"prompt":"argument"},"response":"output"}
            """;
    private static final String VIDEO = """
            {"version":1,"label":"视频服务","request":{"prompt":"argument"},"response":"job_id",
             "video":{"statusPath":"/v1/jobs/{id}","cancelPath":"/v1/jobs/{id}/cancel","cancelMethod":"POST",
                      "downloadPath":"/v1/jobs/{id}/content","idField":"id","stateField":"state",
                      "states":{"queued":"queued","processing":"running","done":"completed","error":"failed",
                                "cancelled":"cancelled"}}}
            """;

    @Test public void pinsVersionAndOutputTypeButSuggestionIsOnlyDisplayMetadata() throws Exception {
        for (SkillResult.Type type : List.of(SkillResult.Type.TEXT, SkillResult.Type.TABLE, SkillResult.Type.IMAGE)) {
            SkillHttpDeclaration declaration = parse(INVOKE, type);
            assertEquals(type, declaration.outputType());
            assertEquals("output", declaration.responseField());
            assertEquals("", declaration.suggestedEndpoint());
            assertNull(declaration.video());
            assertEquals(declaration, parse(declaration.toJson().toString(), type));
        }
        JSONObject data = new JSONObject(INVOKE).put("suggestedEndpoint", "HTTPS://Api.Example.Com:443/v1/text");
        SkillHttpDeclaration declaration = SkillHttpDeclaration.parse(data, SkillResult.Type.TEXT, List.of());
        assertEquals("https://api.example.com/v1/text", declaration.suggestedEndpoint());
        assertEquals("https://api.example.com/v1/text", declaration.toJson().getString("suggestedEndpoint"));
        assertThrows(SkillHttpDeclaration.Invalid.class, () ->
                parse(new JSONObject(INVOKE).put("suggestedEndpoint", "https://127.0.0.1/private").toString(),
                        SkillResult.Type.TEXT));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> parse(INVOKE, SkillResult.Type.VIDEO));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> parse(VIDEO, SkillResult.Type.TEXT));
    }

    @Test public void requestMappingPreservesScalarTypesAndOmitsMissingOptionalFields() throws Exception {
        JSONObject data = new JSONObject(INVOKE);
        JSONObject request = data.getJSONObject("request");
        request.put("draftMode", "config.draft").put("length", "config.length").put("weight", "config.weight")
                .put("style", "config.style").put("ignoredIfMissing", "config.notes");
        List<SkillManifest.ConfigField> fields = List.of(
                field("draft", SkillManifest.FieldType.BOOLEAN, true, null),
                field("length", SkillManifest.FieldType.INTEGER, true, null),
                field("weight", SkillManifest.FieldType.DECIMAL, false, null),
                field("style", SkillManifest.FieldType.TEXT, false, "简短"),
                field("notes", SkillManifest.FieldType.MULTILINE, false, null));
        SkillHttpDeclaration declaration = SkillHttpDeclaration.parse(data, SkillResult.Type.TEXT, fields);
        byte[] bytes = declaration.requestBody("用户原文", Map.of("draft", false, "length", 12,
                "weight", new BigDecimal("2.5"), "unused", "ignored"));
        JSONObject body = new JSONObject(new String(bytes, StandardCharsets.UTF_8));
        assertEquals("用户原文", body.get("prompt"));
        assertEquals(Boolean.FALSE, body.get("draftMode"));
        assertEquals(12, body.get("length"));
        assertEquals(2.5, body.getDouble("weight"), 0.01);
        assertEquals("简短", body.get("style"));
        assertFalse(body.has("ignoredIfMissing"));
        assertFalse(body.has("unused"));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> declaration.requestBody("示例", Map.of("length", 12)));
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> declaration.requestBody("示例", Map.of("draft", "false", "length", 12)));
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> declaration.requestBody("示例", Map.of("draft", false, "length", 1_001)));
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> declaration.requestBody("Bearer synthetic-test-key", Map.of("draft", false, "length", 12)));
    }

    @Test public void rejectsUnknownClaimsFieldsTemplatesAndNonStrictJson() throws Exception {
        for (String key : List.of("method", "endpoint", "authorized", "headers", "query", "script", "responseUrl")) {
            JSONObject extra = new JSONObject(INVOKE).put(key, "synthetic");
            assertThrows(SkillHttpDeclaration.Invalid.class, () ->
                    SkillHttpDeclaration.parse(extra, SkillResult.Type.TEXT, List.of()));
        }
        for (String raw : List.of(INVOKE + "{}", INVOKE.replace("\"version\":1", "\"version\":1,\"version\":1"),
                INVOKE.replace("\"version\":1", "\"version\":1.0"), INVOKE.replace("\"version\":1", "\"version\":2"),
                INVOKE.replace("\"prompt\"", "\"apiKey\""), INVOKE.replace("\"argument\"", "\"config.absent\""),
                INVOKE.replace("\"prompt\"", "\"x-api-key\""),
                INVOKE.replace("\"argument\"", "\"literal\""), INVOKE.replace("\"argument\"", "\"config.a + argument\""),
                INVOKE.replace("\"argument\"", "true"), INVOKE.replace("\"output\"", "{\"url\":\"link\"}"))) {
            assertThrows(SkillHttpDeclaration.Invalid.class, () -> parse(raw, SkillResult.Type.TEXT));
        }
    }

    @Test public void videoRoutesUseTheSavedOriginAndValidatedSameTaskId() throws Exception {
        JSONObject raw = new JSONObject(VIDEO).put("suggestedEndpoint", "https://suggested.example.com/v1/create");
        SkillHttpDeclaration declaration = SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of());
        assertEquals("job_id", declaration.responseField());
        SkillHttpDeclaration.Video video = declaration.video();
        String actual = "https://configured.example.com:9443/other/submit";
        assertEquals("https://configured.example.com:9443/v1/jobs/Ab9-2_x", video.statusEndpoint(actual, "Ab9-2_x"));
        assertEquals("https://configured.example.com:9443/v1/jobs/Ab9-2_x/cancel", video.cancelEndpoint(actual, "Ab9-2_x"));
        assertEquals("https://configured.example.com:9443/v1/jobs/Ab9-2_x/content", video.downloadEndpoint(actual, "Ab9-2_x"));
        assertEquals("POST", video.cancelMethod());
        assertEquals("id", video.idField());
        assertEquals("state", video.stateField());
        assertEquals(SkillHttpDeclaration.VideoState.RUNNING, video.state("processing"));
        assertEquals(SkillHttpDeclaration.VideoState.COMPLETED, video.state("done"));
        JSONObject status = new JSONObject().put("id", "Ab9-2_x").put("state", "done");
        assertEquals(SkillHttpDeclaration.VideoState.COMPLETED, video.readStatus(status, "Ab9-2_x"));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.readStatus(status, "different"));
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> video.readStatus(new JSONObject().put("id", "Ab9-2_x").put("state", 1), "Ab9-2_x"));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.state("unknown"));
        for (String badId : List.of("", ".", "..", "../new", "with/slash", "%2e%2e", "a?b", "X".repeat(129))) {
            assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.downloadEndpoint(actual, badId));
        }

        raw.getJSONObject("video").put("cancelMethod", "DELETE");
        assertEquals("DELETE", SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()).video().cancelMethod());
    }

    @Test public void videoMinimumPollIntervalIsAnOptionalBoundedInteger() throws Exception {
        SkillHttpDeclaration legacy = parse(VIDEO, SkillResult.Type.VIDEO);
        assertEquals(0, legacy.video().minPollSeconds());
        assertFalse(legacy.toJson().getJSONObject("video").has("minPollSeconds"));
        for (int seconds : new int[]{3, 8, 60}) {
            JSONObject raw = new JSONObject(VIDEO);
            raw.getJSONObject("video").put("minPollSeconds", seconds);
            SkillHttpDeclaration configured = parse(raw.toString(), SkillResult.Type.VIDEO);
            assertEquals(seconds, configured.video().minPollSeconds());
            assertEquals(seconds, configured.toJson().getJSONObject("video").getInt("minPollSeconds"));
            assertEquals(configured, parse(configured.toJson().toString(), SkillResult.Type.VIDEO));
        }
        for (String encoded : List.of("0", "-1", "2", "61", "8.0", "8e0", "\"8\"", "null", "true")) {
            String invalid = VIDEO.replace("\"states\":", "\"minPollSeconds\":" + encoded + ",\"states\":");
            assertThrows(encoded, SkillHttpDeclaration.Invalid.class, () -> parse(invalid, SkillResult.Type.VIDEO));
        }
    }

    @Test public void videoResponsePathsReadOnlyExactStringLeavesInsideObjects() throws Exception {
        JSONObject raw = new JSONObject(VIDEO).put("response", "data.task.id");
        raw.getJSONObject("video").put("idField", "data.task.id").put("stateField", "data.task.lifecycle.state");
        SkillHttpDeclaration declaration = SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of());
        assertEquals("data.task.id", declaration.responseField());
        assertEquals("data.task.id", declaration.video().idField());
        assertEquals("data.task.lifecycle.state", declaration.video().stateField());
        assertEquals(declaration, parse(declaration.toJson().toString(), SkillResult.Type.VIDEO));
        var video = declaration.video();
        assertEquals(SkillHttpDeclaration.VideoState.RUNNING,
                video.readStatus(new JSONObject("{\"data\":{\"task\":{\"id\":\"task_01\","
                        + "\"lifecycle\":{\"state\":\"processing\"}}}}"), "task_01"));
        for (String invalid : List.of(
                "{\"data.task.id\":\"task_01\",\"data.task.lifecycle.state\":\"done\"}",
                "{\"data\":{\"task\":[{\"id\":\"task_01\",\"lifecycle\":{\"state\":\"done\"}}]}}",
                "{\"data\":{\"task\":{\"id\":1,\"lifecycle\":{\"state\":\"done\"}}}}",
                "{\"data\":{\"task\":{\"id\":\"TASK_01\",\"lifecycle\":{\"state\":\"done\"}}}}",
                "{\"data\":{\"task\":{\"id\":\"task_01\",\"lifecycle\":{\"state\":true}}}}",
                "{\"data\":{\"task\":{\"id\":\"task_01\",\"lifecycle\":{\"state\":\"DONE\"}}}}",
                "{\"data\":{\"task\":{\"id\":\"task_01\"}}}")) {
            assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.readStatus(new JSONObject(invalid), "task_01"));
        }
    }

    @Test public void rejectsUnsafeVideoResponsePathsAndKeepsNonVideoResponseFieldsFlat() throws Exception {
        for (String path : List.of(".id", "data.", "data..id", "data.0.id", "data[0].id", "data.*.id",
                "../id", "/data/id", "$.data.id", "data.任务.id", "data._id", "data.api_key.id",
                "data.authorization", "data.password.value", "a.b.c.d.e", "a." + "b".repeat(33))) {
            JSONObject raw = new JSONObject(VIDEO).put("response", path);
            assertThrows(path, SkillHttpDeclaration.Invalid.class,
                    () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
            for (String key : List.of("idField", "stateField")) {
                JSONObject status = new JSONObject(VIDEO);
                status.getJSONObject("video").put(key, path);
                assertThrows(path, SkillHttpDeclaration.Invalid.class,
                        () -> SkillHttpDeclaration.parse(status, SkillResult.Type.VIDEO, List.of()));
            }
        }
        for (SkillResult.Type output : List.of(SkillResult.Type.TEXT, SkillResult.Type.IMAGE, SkillResult.Type.TABLE)) {
            JSONObject raw = new JSONObject(INVOKE).put("response", "data.output");
            assertThrows(SkillHttpDeclaration.Invalid.class,
                    () -> SkillHttpDeclaration.parse(raw, output, List.of()));
        }
    }

    @Test public void videoAllowsEitherDocumentedResultTypeAndOnlyCompleteCancellation() throws Exception {
        JSONObject raw = new JSONObject(VIDEO);
        JSONObject options = raw.getJSONObject("video");
        options.remove("cancelPath");
        options.remove("cancelMethod");
        options.remove("downloadPath");
        options.put("resultUrlField", "data.result.url");
        var parsed = SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of());
        JSONObject serialized = parsed.toJson().getJSONObject("video");
        assertEquals("data.result.url", serialized.getString("resultUrlField"));
        assertFalse(serialized.has("downloadPath"));
        assertFalse(serialized.has("cancelPath"));
        assertFalse(serialized.has("cancelMethod"));
        assertEquals(parsed, parse(parsed.toJson().toString(), SkillResult.Type.VIDEO));

        options.put("downloadPath", "/jobs/{id}/content");
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
        options.remove("downloadPath");
        options.remove("resultUrlField");
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
        options.put("resultUrlField", "data.api_key.url");
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
        options.put("resultUrlField", "data.result.url").put("cancelMethod", "DELETE");
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
        options.remove("cancelMethod");
        options.put("cancelPath", "/jobs/{id}/cancel");
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> SkillHttpDeclaration.parse(raw, SkillResult.Type.VIDEO, List.of()));
    }

    @Test public void rejectsVideoRouteEscapesAmbiguousStatesAndInvalidOperationFields() throws Exception {
        for (String path : List.of("https://other.example.com/jobs/{id}", "//other.example.com/{id}", "/jobs",
                "/jobs/{id}/next/{id}", "/jobs/prefix-{id}", "/jobs/{id}.json", "/jobs/{id}/../content",
                "/jobs/%2f/{id}", "/jobs/{id}?x=1", "/jobs/{id}#part", "/jobs\\{id}", "/jobs/{name}")) {
            JSONObject invalid = new JSONObject(VIDEO);
            invalid.getJSONObject("video").put("downloadPath", path);
            assertThrows(SkillHttpDeclaration.Invalid.class, () ->
                    SkillHttpDeclaration.parse(invalid, SkillResult.Type.VIDEO, List.of()));
        }
        for (String[] alteration : List.of(new String[]{"cancelMethod", "GET"}, new String[]{"idField", "state"},
                new String[]{"statusMethod", "POST"}, new String[]{"downloadUrlField", "url"})) {
            JSONObject invalid = new JSONObject(VIDEO);
            invalid.getJSONObject("video").put(alteration[0], alteration[1]);
            assertThrows(SkillHttpDeclaration.Invalid.class, () ->
                    SkillHttpDeclaration.parse(invalid, SkillResult.Type.VIDEO, List.of()));
        }
        JSONObject unknownState = new JSONObject(VIDEO);
        unknownState.getJSONObject("video").getJSONObject("states").put("done", "redirect");
        assertThrows(SkillHttpDeclaration.Invalid.class, () ->
                SkillHttpDeclaration.parse(unknownState, SkillResult.Type.VIDEO, List.of()));
    }

    @Test public void videoStatusCanUseOnlyTheSubmittedSameOriginUrlAndPersistedPath() throws Exception {
        JSONObject raw = new JSONObject(VIDEO);
        JSONObject routes = raw.getJSONObject("video");
        routes.remove("statusPath");
        routes.remove("downloadPath");
        routes.put("statusUrlField", "data.status_url").put("resultUrlField", "data.download_url");
        SkillHttpDeclaration declaration = parse(raw.toString(), SkillResult.Type.VIDEO);
        assertEquals(declaration, parse(declaration.toJson().toString(), SkillResult.Type.VIDEO));
        var video = declaration.video();
        String submit = "https://video.example.com:8443/v2/submit";
        String statusPath = video.readSubmittedStatusPath(new JSONObject("""
                {"data":{"status_url":"jobs/task_1/status"}}
                """), submit);
        assertEquals("/v2/jobs/task_1/status", statusPath);
        var request = video.statusRequest(submit, "task_1", statusPath);
        assertEquals(SkillHttpDeclaration.StatusMethod.GET, request.method());
        assertEquals("https://video.example.com:8443/v2/jobs/task_1/status", request.endpoint());
        assertNull(request.body());
        JSONObject completed = new JSONObject("""
                {"id":"task_1","state":"done","data":{"download_url":"clip.mp4"}}
                """);
        String savedResult = video.completedDownloadPath(completed, submit, "task_1", statusPath);
        assertEquals("/v2/jobs/task_1/clip.mp4", savedResult);
        assertEquals("https://video.example.com:8443" + savedResult,
                video.savedDownloadEndpoint(submit, "task_1", savedResult));
        assertThrows(SkillHttpDeclaration.Invalid.class,
                () -> video.completedDownloadPath(completed, submit, "task_2", statusPath));
        for (String unsafe : List.of("https://other.example.com:8443/jobs/task_1",
                "https://video.example.com/jobs/task_1", "//video.example.com:8443/jobs/task_1",
                "https://name@video.example.com:8443/jobs/task_1", "/jobs/task_1?signature=fixture",
                "/jobs/task_1#part", "/jobs/%2e%2e/task_1", "/jobs/%252e%252e/task_1",
                "/jobs/%2Ftask_1", "/jobs/%5ctask_1", "/jobs/%00task_1")) {
            JSONObject reply = new JSONObject().put("data", new JSONObject().put("status_url", unsafe));
            assertThrows(unsafe, SkillHttpDeclaration.Invalid.class, () -> video.readSubmittedStatusPath(reply, submit));
        }
        for (String unsafe : List.of("", "relative/task_1", "//other.example.com/task_1",
                "https://video.example.com:8443/task_1", "/task_1?sign=fixture")) {
            assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.statusRequest(submit, "task_1", unsafe));
            assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.savedDownloadEndpoint(submit, "task_1", unsafe));
        }
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.readSubmittedStatusPath(
                new JSONObject().put("data", new JSONObject().put("status_url", 12)), submit));
    }

    @Test public void postStatusUsesOnlyAnExplicitFlatOriginalIdBodyAndStaticPath() throws Exception {
        JSONObject raw = new JSONObject(VIDEO);
        JSONObject routes = raw.getJSONObject("video");
        routes.remove("statusPath");
        routes.put("statusPostPath", "/v2/task/progress").put("statusPostIdField", "task_id");
        SkillHttpDeclaration declaration = parse(raw.toString(), SkillResult.Type.VIDEO);
        assertEquals(declaration, parse(declaration.toJson().toString(), SkillResult.Type.VIDEO));
        assertFalse(declaration.toJson().getJSONObject("video").has("statusPath"));
        var video = declaration.video();
        String submit = "https://video.example.com/v2/submit";
        assertEquals("", video.readSubmittedStatusPath(new JSONObject().put("status_url", "/ignored"), submit));
        var request = video.statusRequest(submit, "task_1", "");
        assertEquals(SkillHttpDeclaration.StatusMethod.POST, request.method());
        assertEquals("https://video.example.com/v2/task/progress", request.endpoint());
        byte[] bytes = request.body();
        assertEquals("{\"task_id\":\"task_1\"}", new String(bytes, StandardCharsets.UTF_8));
        bytes[0] = 'x';
        assertEquals('{', request.body()[0]);
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.statusRequest(submit, "other/id", ""));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.statusRequest(submit, "task_1", "/tampered"));
        assertEquals("/v1/jobs/task_1/content", video.completedDownloadPath(
                new JSONObject().put("id", "task_1").put("state", "done"), submit, "task_1", ""));
        assertThrows(SkillHttpDeclaration.Invalid.class, () -> video.savedDownloadEndpoint(submit, "task_1", "/wrong"));
    }

    @Test public void statusModesAreMutuallyExclusiveAndCannotSmuggleAnUntypedPostOrUrl() throws Exception {
        for (String properties : List.of(
                "{}",
                "{\"statusPath\":\"/jobs/{id}\",\"statusUrlField\":\"data.status_url\"}",
                "{\"statusPath\":\"/jobs/{id}\",\"statusPostPath\":\"/progress\",\"statusPostIdField\":\"id\"}",
                "{\"statusUrlField\":\"data.status_url\",\"statusPostPath\":\"/progress\",\"statusPostIdField\":\"id\"}",
                "{\"statusUrlField\":\"job_id\"}",
                "{\"statusUrlField\":\"job_id.link\"}",
                "{\"statusUrlField\":\"data.api_key\"}",
                "{\"statusPostPath\":\"/progress\"}",
                "{\"statusPostIdField\":\"id\"}",
                "{\"statusPostPath\":\"/jobs/{id}\",\"statusPostIdField\":\"id\"}",
                "{\"statusPostPath\":\"/progress?query=1\",\"statusPostIdField\":\"id\"}",
                "{\"statusPostPath\":\"/progress\",\"statusPostIdField\":\"data.id\"}",
                "{\"statusPostPath\":\"/progress\",\"statusPostIdField\":\"authorization\"}")) {
            JSONObject raw = new JSONObject(VIDEO);
            JSONObject configured = raw.getJSONObject("video");
            configured.remove("statusPath");
            JSONObject fields = new JSONObject(properties);
            for (java.util.Iterator<String> names = fields.keys(); names.hasNext();) {
                String name = names.next();
                configured.put(name, fields.get(name));
            }
            assertThrows(properties, SkillHttpDeclaration.Invalid.class, () -> parse(raw.toString(), SkillResult.Type.VIDEO));
        }
    }

    private static SkillHttpDeclaration parse(String json, SkillResult.Type type) {
        return SkillHttpDeclaration.parse(json, type, List.of());
    }

    private static SkillManifest.ConfigField field(String key, SkillManifest.FieldType type,
                                                  boolean required, Object defaultValue) {
        return new SkillManifest.ConfigField(key, key, "", type, required, List.of(), 100,
                BigDecimal.ZERO, BigDecimal.valueOf(1000), defaultValue);
    }
}
