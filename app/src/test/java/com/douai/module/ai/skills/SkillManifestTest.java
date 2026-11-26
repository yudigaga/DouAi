package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillManifestTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void validatesDefaultsAndSeparatesRequiredValuesFromDeclarations() throws Exception {
        JSONObject proposal = textProposal(fields());
        proposal.getJSONArray("configFields").put(new JSONObject()
                .put("key", "heading").put("label", "包含标题").put("type", "boolean").put("required", true)
                .put("default", false));
        SkillManifest manifest = SkillManifest.parse(proposal.toString());
        assertEquals(List.of("tone", "length"), manifest.missing(manifest.readValues(null)));
        Map<String, Object> values = manifest.enteredValues(Map.of("tone", "温和", "length", "120"));
        assertEquals(Map.of("tone", "温和", "length", 120, "heading", false), values);
        assertTrue(manifest.missing(values).isEmpty());
        assertFalse(manifest.toJson().has("configValues"));
        assertThrows(IllegalArgumentException.class,
                () -> manifest.enteredValues(Map.of("tone", "未经声明的选项", "length", "120")));
        assertThrows(IllegalArgumentException.class, () -> manifest.enteredValues(Map.of("length", "2000")));
        assertThrows(IllegalArgumentException.class, () -> manifest.enteredValues(Map.of("secret", "ignored")));
    }

    @Test public void rejectsUnknownCapabilitiesCredentialsAndMalformedDeclarations() throws Exception {
        JSONObject unknown = textProposal(fields()).put("capabilityId", "arbitrary_http");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(unknown.toString()));
        JSONObject identity = textProposal(fields()).put("id", "fixture-provided-by-model");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(identity.toString()));
        JSONArray secret = new JSONArray().put(new JSONObject()
                .put("key", "tone").put("label", "请输入 API 密钥").put("type", "text").put("required", true));
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(textProposal(secret).toString()));
        secret.getJSONObject(0).put("label", "格式").put("type", "secretRef");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(textProposal(secret).toString()));
        JSONArray badDefault = fields();
        badDefault.getJSONObject(1).put("default", "120");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(textProposal(badDefault).toString()));
        String duplicate = textProposal(fields()).toString().replace("\"type\":\"enum\"", "\"type\":\"text\",\"type\":\"enum\"");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(duplicate));
        JSONObject image = textProposal(fields()).put("capabilityId", "image_generation").put("outputType", "image");
        image.getJSONObject("trial").put("expectedType", "image");
        assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(image.toString()));
    }

    @Test public void optionalServiceReferenceIsLogicalAndMustMatchTheDeclaredCapability() throws Exception {
        JSONObject text = textProposal(new JSONArray());
        SkillManifest legacy = SkillManifest.parse(text.toString());
        assertFalse(legacy.toJson().has("serviceRef"));
        assertEquals(SkillCapabilityRegistry.ServiceRef.SELECTED_MODEL, legacy.serviceRef());
        text.put("serviceRef", "selected_model");
        assertEquals("selected_model", SkillManifest.parse(text.toString()).toJson().getString("serviceRef"));
        JSONObject table = textProposal(new JSONArray()).put("capabilityId", "table_model")
                .put("outputType", "table").put("serviceRef", "selected_model");
        table.getJSONObject("trial").put("expectedType", "table");
        assertEquals("selected_model", SkillManifest.parse(table.toString()).toJson().getString("serviceRef"));
        JSONObject image = textProposal(new JSONArray()).put("capabilityId", "image_generation")
                .put("outputType", "image").put("serviceRef", "image_connection");
        image.getJSONObject("trial").put("expectedType", "image");
        assertEquals("image_connection", SkillManifest.parse(image.toString()).toJson().getString("serviceRef"));
        JSONObject video = textProposal(new JSONArray()).put("capabilityId", "video_task")
                .put("outputType", "video").put("serviceRef", "skill_https");
        video.getJSONObject("trial").put("expectedType", "video");
        assertEquals("skill_https", SkillManifest.parse(video.toString()).toJson().getString("serviceRef"));
        JSONObject direct = textProposal(new JSONArray()).put("capabilityId", "custom_service").put("serviceRef", "skill_https");
        assertEquals(SkillCapabilityRegistry.ServiceRef.SKILL_HTTPS, SkillManifest.parse(direct.toString()).serviceRef());
        for (Object invalid : List.of("skill_https", "https://service.example.test/v1", "profile-01", "Bearer fixture-secret", 1,
                JSONObject.NULL, new JSONObject().put("name", "selected_model"))) {
            text.put("serviceRef", invalid);
            assertThrows(IllegalArgumentException.class, () -> SkillManifest.parse(text.toString()));
        }
    }

    @Test public void changingFieldTypeKeepsCompatibleValuesAndRequiresConfirmationForReplacement() throws Exception {
        File directory = temporary.newFolder();
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        SkillStore store = new SkillStore(directory, services);
        String declaration = textProposal(fields()).toString();
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(declaration)).pending());
        SkillStore.Detail first = store.detail("润色");
        String id = record(directory, "drafts").getString("skillId");
        SkillStore.Detail installed = store.saveManifestValues(first, Map.of("tone", "正式", "length", "80"));
        assertFalse(installed.pending());
        assertEquals(id, record(directory, "skills").getString("skillId"));
        assertThrows(IllegalStateException.class, () -> store.saveManifestValues(first, Map.of("tone", "温和")));

        AiSkills.Invocation prior = AiSkills.match(AiSkills.Entry.WORK, "/润色 原文", store);
        assertNotNull(prior);
        SkillStore.EditTarget editor = store.editTarget(installed.deleteTarget());
        assertThrows(IllegalArgumentException.class, () -> store.saveEdit(editor, "调整篇幅", "输入待处理文字",
                "按填写的语气处理", "[],\"other\":true"));
        assertFalse(store.detail("润色").pending());
        JSONArray changed = fields();
        changed.getJSONObject(1).put("type", "decimal");
        SkillStore.Detail incomplete = store.saveEdit(editor, "调整篇幅", "输入待处理文字", "按填写的语气处理",
                changed.toString(), "写作助手");
        assertTrue(incomplete.pending());
        assertFalse(incomplete.canReplace());
        SkillStore reopened = new SkillStore(directory, services);
        assertEquals(Map.of("tone", "正式"), reopened.detail("润色").manifestValues());
        assertEquals(1, reopened.detail("润色").previousVersion());

        SkillStore.Detail ready = reopened.saveManifestValues(reopened.detail("润色"), Map.of("tone", "正式", "length", "70.5"));
        assertTrue(ready.canReplace());
        assertTrue(ready.pending());
        assertEquals(1, reopened.detail("润色").previousVersion());
        reopened.activateReplacement(ready);
        assertEquals(id, record(directory, "skills").getString("skillId"));
        assertEquals("写作助手", reopened.detail("润色").manifest().displayName());
        assertNotNull(AiSkills.match(AiSkills.Entry.CONTACT, "/润色 原文", reopened));
        assertEquals(Map.of("tone", "正式", "length", new BigDecimal("70.5")), reopened.detail("润色").manifestValues());
        JSONObject request = new JSONObject(SkillAuthoring.textInput("按规则处理", "本次原文", reopened.detail("润色").manifestValues()));
        assertTrue(request.getJSONObject("config").get("length") instanceof Number);
        assertThrows(IllegalStateException.class,
                () -> AiSkills.executeResult(prior, reopened, (instruction, input) -> failUnexpected()));
        assertThrows(IllegalStateException.class, () -> reopened.delete(ready.deleteTarget()));
    }

    @Test public void zeroSettingsAndLegacyImageAdapterRemainUsableThroughTheRegistry() throws Exception {
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        services.authorize(services.find(SkillServices.MOCK_IMAGE), 0);
        SkillStore store = new SkillStore(temporary.newFolder(), services);
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(textProposal(new JSONArray()).toString())).installed());
        assertTrue(store.detail("润色").fields().isEmpty());
        assertEquals(new SkillResult.Text("模拟回复"), AiSkills.executeResult(
                AiSkills.match(AiSkills.Entry.CONTACT, "/润色 原文", store), store, (instruction, input) -> "模拟回复"));
        JSONObject image = textProposal(new JSONArray()).put("name", "海报").put("outputType", "image")
                .put("capabilityId", "image_generation");
        image.getJSONObject("trial").put("expectedType", "image");
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(image.toString())).installed());
        assertTrue(AiSkills.executeResult(AiSkills.match(AiSkills.Entry.WORK, "/海报 白猫", store), store)
                instanceof SkillResult.Image);
    }

    @Test public void trialDoesNotEnableAnOversizedConfiguredModelRequest() throws Exception {
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        SkillStore store = new SkillStore(temporary.newFolder(), services);
        JSONArray settings = new JSONArray();
        for (String key : List.of("tone", "audience", "style")) {
            settings.put(new JSONObject().put("key", key).put("label", key)
                    .put("type", "multiline").put("required", true).put("default", "示".repeat(220)));
        }
        JSONObject proposal = textProposal(settings).put("instructions", "指".repeat(1600));
        proposal.getJSONObject("trial").put("input", "例".repeat(2000));

        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(proposal.toString())).pending());
        assertTrue(store.detail("润色").reason().contains("离线校验失败"));
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.WORK, "/润色 原文", store));

        SkillStore.Detail installed = store.saveManifestValues(store.detail("润色"),
                Map.of("tone", "温和", "audience", "同事", "style", "例".repeat(220)));
        assertFalse(installed.pending());
        assertNotNull(AiSkills.match(AiSkills.Entry.WORK, "/润色 原文", store));
    }

    private static String failUnexpected() {
        throw new AssertionError("A replaced revision must not invoke the model");
    }

    private static JSONObject record(File directory, String section) throws Exception {
        return new JSONObject(Files.readString(new File(directory, "catalog.json").toPath()))
                .getJSONObject(section).getJSONObject("润色");
    }

    private static JSONObject textProposal(JSONArray config) throws Exception {
        return new JSONObject().put("schemaVersion", 4).put("name", "润色").put("description", "润色文本")
                .put("documentation", "输入待润色的文本").put("instructions", "保持原意，改善表达")
                .put("capabilityId", "text_model").put("outputType", "text").put("configFields", config)
                .put("inputs", new JSONArray().put(new JSONObject().put("key", "argument")
                        .put("type", "text").put("required", true).put("maxLength", 2000)))
                .put("trial", new JSONObject().put("input", "样例").put("expectedType", "text"));
    }

    private static JSONArray fields() throws Exception {
        return new JSONArray().put(new JSONObject().put("key", "tone").put("label", "语气")
                        .put("type", "enum").put("required", true).put("options", new JSONArray(List.of("正式", "温和"))))
                .put(new JSONObject().put("key", "length").put("label", "长度").put("type", "integer")
                        .put("required", true).put("min", 20).put("max", 200));
    }
}
