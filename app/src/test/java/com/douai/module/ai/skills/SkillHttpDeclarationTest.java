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

    private static SkillHttpDeclaration parse(String json, SkillResult.Type type) {
        return SkillHttpDeclaration.parse(json, type, List.of());
    }

    private static SkillManifest.ConfigField field(String key, SkillManifest.FieldType type,
                                                  boolean required, Object defaultValue) {
        return new SkillManifest.ConfigField(key, key, "", type, required, List.of(), 100,
                BigDecimal.ZERO, BigDecimal.valueOf(1000), defaultValue);
    }
}
