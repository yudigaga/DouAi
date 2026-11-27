package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillOpenApiVideoTest {
    private static final String CREATE = "/render/jobs";
    private static final String STATUS = CREATE + "/{job_id}";
    private static final String MEDIA = STATUS + "/file";

    @Test public void extractsOnlyDocumentedRelativeJsonPollingAndMp4() throws Exception {
        SkillVideoFacts facts = SkillOpenApiVideo.read(openApi().toString());
        assertTrue(facts.isComplete());
        assertTrue(facts.needsApiBase());
        assertTrue(facts.formFields().stream().anyMatch(field -> "submitPath".equals(field.id())
                && field.source() == SkillVideoFacts.Source.DOCUMENT));
        assertEquals("https://service.example.com/render/jobs", facts.resolveApiBase("https://service.example.com"));

        JSONObject wire = facts.declaration();
        assertEquals("job_id", wire.getString("response"));
        JSONObject request = wire.getJSONObject("request");
        assertEquals("argument", request.getString("prompt"));
        assertEquals("config.model", request.getString("model"));
        assertEquals("config.duration", request.getString("duration"));
        assertEquals("config.quality", request.getString("quality"));
        assertFalse(request.has("image"));
        JSONObject video = wire.getJSONObject("video");
        assertEquals("/render/jobs/{id}", video.getString("statusPath"));
        assertEquals("/render/jobs/{id}/file", video.getString("downloadPath"));
        assertEquals("job_id", video.getString("idField"));
        assertEquals("state", video.getString("stateField"));
        assertEquals("running", video.getJSONObject("states").getString("processing"));
        assertEquals("completed", video.getJSONObject("states").getString("succeeded"));
        assertFalse(wire.has("suggestedEndpoint"));
        assertFalse(facts.toJson().toString().contains("example-service.test"));

        JSONArray fields = facts.configFields();
        assertEquals("documented-model", byKey(fields, "model").getString("default"));
        assertEquals(5, byKey(fields, "duration").getInt("default"));
        assertEquals("standard", byKey(fields, "quality").getString("default"));
    }

    @Test public void missingRuntimeEvidenceIsNotInferredFromSimilarRoutes() throws Exception {
        JSONObject noEnum = openApi();
        noEnum.getJSONObject("components").getJSONObject("schemas").getJSONObject("Status").getJSONObject("properties")
                .getJSONObject("state").remove("enum");
        assertTrue(SkillOpenApiVideo.read(noEnum.toString()).isEmpty());

        JSONObject differentId = openApi();
        JSONObject paths = differentId.getJSONObject("paths");
        paths.put(CREATE + "/{video_id}/file", paths.remove(MEDIA));
        assertTrue(SkillOpenApiVideo.read(differentId.toString()).isEmpty());

        JSONObject requiredMedia = openApi();
        requiredMedia.getJSONObject("components").getJSONObject("schemas").getJSONObject("Submission")
                .getJSONArray("required").put("image");
        assertTrue(SkillOpenApiVideo.read(requiredMedia.toString()).isEmpty());

        JSONObject external = openApi();
        external.getJSONObject("paths").getJSONObject(CREATE).getJSONObject("post").getJSONObject("requestBody")
                .put("$ref", "https://docs.example.test/schemas/body.json");
        assertTrue(SkillOpenApiVideo.read(external.toString()).isEmpty());
    }

    @Test public void refusesAmbiguousMediaAndRequiredHeaders() throws Exception {
        JSONObject ambiguous = openApi();
        JSONObject paths = ambiguous.getJSONObject("paths");
        paths.put(STATUS + "/export", paths.getJSONObject(MEDIA));
        assertTrue(SkillOpenApiVideo.read(ambiguous.toString()).isEmpty());

        JSONObject header = openApi();
        header.getJSONObject("paths").getJSONObject(STATUS).getJSONObject("get").put("parameters", new JSONArray("""
                [{"in":"header","name":"X-Service-Key","required":true,"schema":{"type":"string"}}]
                """));
        assertTrue(SkillOpenApiVideo.read(header.toString()).isEmpty());
    }

    @Test public void acceptsBearerAndRejectsAnUnimplementableAuthenticationScheme() throws Exception {
        JSONObject documented = openApi();
        documented.getJSONObject("components").put("securitySchemes", new JSONObject("""
                {"gateway":{"type":"http","scheme":"bearer"},
                 "keyHeader":{"type":"apiKey","in":"header","name":"X-Gateway-Key"}}
                """));
        documented.put("security", new JSONArray("[{\"gateway\":[]}]"));
        assertTrue(SkillOpenApiVideo.read(documented.toString()).isComplete());
        documented.put("security", new JSONArray("[{\"keyHeader\":[]}]"));
        assertTrue(SkillOpenApiVideo.read(documented.toString()).isEmpty());
    }

    private static JSONObject byKey(JSONArray fields, String key) throws Exception {
        for (int i = 0; i < fields.length(); i++) {
            JSONObject field = fields.getJSONObject(i);
            if (key.equals(field.getString("key"))) return field;
        }
        throw new AssertionError("Missing field " + key);
    }

    public static String documentedVideoFixture() throws Exception { return openApi().toString(); }

    private static JSONObject openApi() throws Exception {
        return new JSONObject("""
                {
                  "openapi":"3.1.0",
                  "servers":[{"url":"https://example-service.test/proxy"}],
                  "paths":{
                    "/render/jobs":{"post":{
                      "requestBody":{"$ref":"#/components/requestBodies/Job"},
                      "responses":{"202":{"$ref":"#/components/responses/Submitted"}}}},
                    "/render/jobs/{job_id}":{"get":{
                      "responses":{"200":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Status"}}}}}}},
                    "/render/jobs/{job_id}/file":{"get":{
                      "responses":{"200":{"content":{"video/mp4":{"schema":{"type":"string","format":"binary"}}}}}}}
                  },
                  "components":{
                    "requestBodies":{"Job":{"content":{"application/json":{
                      "schema":{"$ref":"#/components/schemas/Submission"},"example":{"model":"example-only","quality":"standard"}}}}},
                    "responses":{"Submitted":{"content":{"application/json":{"schema":{"$ref":"#/components/schemas/Acknowledged"}}}}},
                    "schemas":{
                      "Submission":{"type":"object","required":["prompt","model","quality"],"properties":{
                        "prompt":{"type":"string"},"model":{"type":"string","default":"documented-model"},
                        "duration":{"type":"integer","minimum":1,"maximum":10,"default":5},
                        "quality":{"type":"string","enum":["standard","high"]},"image":{"type":"string","format":"binary"}}},
                      "Acknowledged":{"type":"object","properties":{"job_id":{"type":"string"}}},
                      "Status":{"type":"object","properties":{"job_id":{"type":"string"},
                        "state":{"type":"string","enum":["waiting","processing","succeeded","failed"]}}}
                    }
                  }
                }
                """);
    }
}
