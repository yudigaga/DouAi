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
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SkillStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void videoDeclarationPromotesOnlyTheCurrentDraftToEndpointConfiguration() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        saveVideoDraft(store, videoDraft("镜头动画"));
        SkillStore.Detail initial = store.detail("镜头动画");
        assertEquals(SkillStore.NEEDS_VIDEO_PROVIDER, initial.reason());
        assertNull(initial.manifest().http());
        assertFalse(initial.videoMissing().isEmpty());

        JSONObject http = videoHttp();
        http.getJSONObject("request").put("duration", "config.duration");
        JSONObject input = new JSONObject().put("http", http).put("configFields", new JSONArray().put(
                new JSONObject().put("key", "duration").put("label", "时长").put("type", "integer")
                        .put("required", true).put("min", 1).put("max", 15)));
        SkillStore.Detail saved = store.saveVideoDeclaration(initial, input.toString());

        assertTrue(saved.pending());
        assertFalse(saved.canReplace());
        assertFalse(initial.draftId().equals(saved.draftId()));
        assertNull(saved.current());
        assertNull(saved.customConfiguration());
        assertEquals("镜头动画", saved.name());
        assertEquals("视频工作台", saved.manifest().displayName());
        assertEquals("按场景生成视频", saved.manifest().description());
        assertEquals("填写画面描述", saved.manifest().documentation());
        assertEquals("保留原始动作要求", saved.manifest().instructions());
        assertEquals("海边", saved.manifest().trialInput());
        assertEquals("config.duration", saved.manifest().http().request().get("duration"));
        assertEquals("https://suggested.example.com/v1/submit", saved.manifest().http().suggestedEndpoint());
        assertTrue(saved.fields().stream().anyMatch(field -> field.id().equals("duration")
                && field.state() == SkillStore.FieldState.MISSING));
        assertTrue(saved.reason().contains("公网 HTTPS"));
        assertTrue(store.list().isEmpty());
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.CONTACT, "/镜头动画 海边", store));
        SkillStore.Detail reopened = videoStore(root).detail("镜头动画");
        assertEquals(saved.deleteTarget(), reopened.deleteTarget());
        assertNull(reopened.customConfiguration());
        assertThrows(IllegalStateException.class, () -> store.saveVideoDeclaration(initial, videoHttp().toString()));
    }

    @Test public void videoDeclarationRejectsIncompleteUnsafeAndStaleInputWithoutWriting() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        JSONObject original = videoDraft("镜头动画");
        saveVideoDraft(store, original);
        SkillStore.Detail before = store.detail("镜头动画");
        var catalog = new File(root, "catalog.json").toPath();
        String untouched = Files.readString(catalog);
        JSONObject incomplete = videoHttp();
        incomplete.getJSONObject("video").remove("statusPath");
        incomplete.getJSONObject("video").remove("idField");
        var missing = assertThrows(IllegalArgumentException.class,
                () -> store.saveVideoDeclaration(before, incomplete.toString()));
        assertTrue(missing.getMessage().contains("statusPath"));
        assertTrue(missing.getMessage().contains("idField"));
        var missingHttp = assertThrows(IllegalArgumentException.class,
                () -> store.saveVideoDeclaration(before, original.toString()));
        assertTrue(missingHttp.getMessage().contains("http"));

        String unsafe = "synthetic-should-not-echo";
        JSONObject withHeader = videoHttp().put("headers", new JSONObject().put("Authorization", "Bearer " + unsafe));
        JSONObject injected = new JSONObject().put("http", videoHttp()).put("configFields", new JSONArray())
                .put("authorized", true);
        JSONObject runtime = videoDraft("其他").put("http", videoHttp()).put("configValues", new JSONObject());
        for (String input : java.util.List.of(withHeader.toString(), injected.toString(), runtime.toString(),
                videoHttp().toString().replaceFirst("\\{", "{\"version\":1,"),
                videoHttp().toString() + "{}", "x".repeat(16_001))) {
            var invalid = assertThrows(IllegalArgumentException.class, () -> store.saveVideoDeclaration(before, input));
            assertFalse(invalid.getMessage().contains(unsafe));
        }
        assertEquals(untouched, Files.readString(catalog));
        saveVideoDraft(store, original.put("description", "更新的内容"));
        SkillStore.Detail changed = store.detail("镜头动画");
        assertThrows(IllegalStateException.class, () -> store.saveVideoDeclaration(before, videoHttp().toString()));
        assertEquals(changed.deleteTarget(), store.detail("镜头动画").deleteTarget());
        assertNull(store.detail("镜头动画").manifest().http());
        assertNotNull(store.saveVideoDeclaration(changed, videoHttp().toString()).manifest().http());
    }

    @Test public void aSingleCopiedJsonFenceAcceptsEachSupportedVideoDeclarationShape() throws Exception {
        JSONObject wrapper = new JSONObject().put("http", videoHttp()).put("configFields", new JSONArray());
        JSONObject full = videoDraft("复制来的名称").put("http", videoHttp());
        for (String clipboard : java.util.List.of(
                "```json\n" + videoHttp().toString(2) + "\n```",
                "```\r\n" + wrapper.toString(2).replace("\n", "\r\n") + "\r\n```\r\n",
                "\n```json\n" + full.toString(2) + "\n```\n")) {
            File root = temp.newFolder();
            SkillStore store = videoStore(root);
            saveVideoDraft(store, videoDraft("镜头动画"));
            SkillStore.Detail saved = store.saveVideoDeclaration(store.detail("镜头动画"), clipboard);

            assertTrue(saved.pending());
            assertEquals("镜头动画", saved.name());
            assertEquals("/v1/jobs/{id}", saved.manifest().http().video().statusPath());
            assertNull(saved.customConfiguration());
            assertTrue(store.list().isEmpty());
            assertEquals(saved.deleteTarget(), videoStore(root).detail("镜头动画").deleteTarget());
        }
    }

    @Test public void fencedVideoDeclarationRejectsProseExtraBlocksAndUnsafeJsonWithoutWriting() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        saveVideoDraft(store, videoDraft("镜头动画"));
        SkillStore.Detail before = store.detail("镜头动画");
        var catalog = new File(root, "catalog.json").toPath();
        String untouched = Files.readString(catalog);
        String body = videoHttp().toString();
        String fenced = "```json\n" + body + "\n```";
        String secret = "synthetic-fenced-bearer-value";
        String withSecret = videoHttp().put("headers", new JSONObject().put("Authorization", "Bearer " + secret))
                .toString();
        for (String clipboard : java.util.List.of(
                "接口配置如下\n" + fenced,
                fenced + "\n请检查",
                fenced + "\n\n" + fenced,
                "```yaml\n" + body + "\n```",
                "```json\n" + body,
                body + "\n```",
                "```json\n" + body + "\n````",
                "```json\n" + body.replaceFirst("\\{", "{\"version\":1,") + "\n```",
                "```json\n" + withSecret + "\n```")) {
            var invalid = assertThrows(SkillManifest.Invalid.class, () -> store.saveVideoDeclaration(before, clipboard));
            assertEquals("视频接口声明或配置字段无效", invalid.getMessage());
            assertFalse(invalid.toString().contains(secret));
            assertNull(invalid.getCause());
        }
        assertEquals(untouched, Files.readString(catalog));
        assertEquals(before.deleteTarget(), store.detail("镜头动画").deleteTarget());
        assertNull(store.detail("镜头动画").manifest().http());
    }

    @Test public void fullVideoManifestCannotReplaceTheDraftIdentityOrInheritTheActiveBinding() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        saveVideoDraft(store, videoDraft("镜头动画").put("http", videoHttp()));
        String previousEndpoint = "https://saved.example.com/v1/submit";
        SkillStore.Detail active = store.configureCustomService(store.detail("镜头动画"), previousEndpoint,
                "synthetic-video-fixture");
        assertEquals(1, active.current().version());
        assertTrue(active.installedEnabled());
        assertTrue(active.customConfiguration().hasCredential());
        saveVideoDraft(store, videoDraft("镜头动画"));
        SkillStore.Detail before = store.detail("镜头动画");

        JSONObject copied = videoDraft("另一名称").put("displayName", "另一个标题").put("description", "不同的说明")
                .put("documentation", "其他用法").put("instructions", "完全不同的指令").put("http", videoHttp());
        copied.getJSONObject("trial").put("input", "其他样例");
        SkillStore.Detail staged = store.saveVideoDeclaration(before, copied.toString());
        assertTrue(staged.pending());
        assertFalse(staged.canReplace());
        assertEquals("镜头动画", staged.name());
        assertEquals("视频工作台", staged.manifest().displayName());
        assertEquals("按场景生成视频", staged.manifest().description());
        assertEquals("填写画面描述", staged.manifest().documentation());
        assertEquals("保留原始动作要求", staged.manifest().instructions());
        assertEquals("海边", staged.manifest().trialInput());
        assertEquals(active.current(), staged.current());
        assertNull(staged.customConfiguration());
        JSONObject catalog = new JSONObject(Files.readString(new File(root, "catalog.json").toPath()));
        JSONObject pending = catalog.getJSONObject("drafts").getJSONObject("镜头动画");
        assertTrue(pending.has("customBinding") && pending.isNull("customBinding"));
        assertFalse(catalog.getJSONObject("drafts").has("另一名称"));
        assertFalse(catalog.getJSONObject("skills").has("另一名称"));

        AiSkills.Invocation prior = AiSkills.match(AiSkills.Entry.WORK, "/镜头动画 海边", store);
        assertEquals(1, prior.version());
        assertEquals(previousEndpoint, store.previewCustomService(prior).endpoint());
        String nextEndpoint = "https://approved.example.com/v1/submit";
        SkillStore.Detail replacement = store.configureCustomService(staged, nextEndpoint, "");
        assertTrue(replacement.canReplace());
        assertEquals(1, AiSkills.match(AiSkills.Entry.CONTACT, "/镜头动画 海边", store).version());
        SkillStore.Detail published = store.activateReplacement(replacement);
        assertEquals(2, published.current().version());
        assertEquals(nextEndpoint, published.customConfiguration().endpoint());
        assertFalse(published.customConfiguration().hasCredential());
    }

    @Test public void ungroundedVideoContractIsRemovedBeforePreviewAndWhenPersisting() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        JSONObject plain = videoDraft("镜头动画");
        String original = "\n" + plain.toString(2) + "\n";
        assertEquals(original, SkillStore.prepareAuthoredVideoDraft(original, null).definition());

        JSONObject model = videoDraft("镜头动画").put("http", videoHttp());
        model.put("configFields", new JSONArray().put(new JSONObject().put("key", "quality").put("label", "画质")
                .put("type", "text").put("required", false).put("maxLength", 20)));
        String raw = model.toString();
        JSONObject preview = new JSONObject(SkillStore.prepareAuthoredVideoDraft(raw, null).definition());
        assertFalse(preview.has("http"));
        assertEquals(0, preview.getJSONArray("configFields").length());

        SkillStore.SaveResult result = store.saveAuthoredVideoDraft(SkillStore.validateAuthoredDraft(raw), null);
        assertNull(result.installed());
        assertNotNull(result.pending());
        SkillStore.Detail saved = videoStore(root).detail("镜头动画");
        assertTrue(saved.pending());
        assertNull(saved.manifest().http());
        assertNull(saved.customConfiguration());
        assertFalse(saved.videoMissing().isEmpty());
        assertTrue(store.list().isEmpty());
    }

    @Test public void explicitReusePreservesSchemaAndSameNameValuesWithoutInheritingApproval() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String endpoint = "https://saved.example.com/v1/submit";
        String secret = "synthetic-grounded-video-source";
        JSONObject http = videoHttp();
        http.getJSONObject("request").put("duration", "config.duration");
        JSONArray fields = new JSONArray().put(new JSONObject().put("key", "duration").put("label", "时长")
                .put("type", "integer").put("required", true).put("min", 1).put("max", 15));
        saveVideoDraft(store, videoDraft("镜头动画").put("http", http).put("configFields", fields));
        assertThrows(IllegalStateException.class, () -> store.videoFactsForSkill("镜头动画"));
        SkillStore.Detail valued = store.saveManifestValues(store.detail("镜头动画"), java.util.Map.of("duration", "8"));
        SkillStore.Detail active = store.configureCustomService(valued, endpoint, secret);

        assertThrows(IllegalStateException.class, () -> store.videoFactsForSkill("视频工作台"));
        SkillVideoFacts facts = store.videoFactsForSkill("镜头动画");
        assertTrue(facts.missing().isEmpty());
        assertFalse(facts.toJson().toString().contains(secret));
        String draft = videoDraft("镜头动画").toString();
        SkillStore.DraftPreview prepared = SkillStore.prepareAuthoredVideoDraft(draft, facts);
        assertEquals(endpoint, new JSONObject(prepared.definition()).getJSONObject("http").getString("suggestedEndpoint"));
        assertFalse(prepared.definition().contains(secret));
        store.saveAuthoredVideoDraft(prepared, facts);

        SkillStore.Detail pending = videoStore(root).detail("镜头动画");
        assertTrue(pending.pending());
        assertEquals(active.current(), pending.current());
        assertNull(pending.customConfiguration());
        assertEquals(8, pending.manifestValues().get("duration"));
        assertEquals("config.duration", pending.manifest().http().request().get("duration"));
        assertEquals(endpoint, pending.manifest().http().suggestedEndpoint());
        assertEquals(1, AiSkills.match(AiSkills.Entry.WORK, "/镜头动画 海边", store).version());
        assertFalse(Files.readString(new File(root, "catalog.json").toPath()).contains(secret));
    }

    @Test public void partialVideoFactsSurviveReopenAndOnlyTheExactDraftCanBeCompleted() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        SkillVideoFacts submit = SkillVideoFacts.parse("""
                提交: POST https://video.example.com/v1/jobs
                提示词字段: prompt
                提交响应ID: job_id
                """);
        SkillStore.DraftPreview preview = SkillStore.prepareAuthoredVideoDraft(videoDraft("镜头动画").toString(), submit);
        SkillStore.SaveResult saved = store.saveAuthoredVideoDraft(preview, submit);
        assertNotNull(saved.pending());
        assertTrue(saved.pending().reason().contains("状态字段"));
        SkillStore reopened = videoStore(root);
        SkillStore.Detail before = reopened.detail("镜头动画");
        assertTrue(before.videoMissing().stream().anyMatch(field -> field.contains("查询")));
        assertEquals("https://video.example.com/v1/jobs", before.videoFacts().toJson().getString("submitEndpoint"));
        assertNull(before.manifest().http());
        AiSkills.CommandUnavailable blocked = assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.WORK, "/镜头动画 海边", reopened));
        assertEquals(before.reason(), blocked.getMessage());

        SkillStore.Detail withQuery = reopened.addVideoFacts(before, SkillVideoFacts.parse("查询: GET /v1/jobs/{id}"));
        assertFalse(before.draftId().equals(withQuery.draftId()));
        SkillVideoFacts remainder = SkillVideoFacts.parse("""
                查询响应ID: id
                状态字段: status
                状态映射: waiting=queued,running=running,done=completed,failed=failed,canceled=cancelled
                取消: POST /v1/jobs/{id}/cancel
                下载: GET /v1/jobs/{id}/file
                """);
        assertThrows(IllegalStateException.class, () -> reopened.addVideoFacts(before, remainder));
        SkillStore.Detail complete = reopened.addVideoFacts(withQuery, remainder);
        assertTrue(complete.pending());
        assertEquals("镜头动画", complete.name());
        assertTrue(complete.videoMissing().isEmpty());
        assertEquals("argument", complete.manifest().http().request().get("prompt"));
        assertEquals("/v1/jobs/{id}", complete.manifest().http().video().statusPath());
        assertNull(complete.customConfiguration());
        assertTrue(complete.reason().contains("公网 HTTPS"));
        assertTrue(reopened.list().isEmpty());
    }

    private static SkillStore videoStore(File root) {
        var vault = new com.douai.module.ai.AiCredentialVault(
                () -> new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
        var services = new SkillServices(root, vault, (endpoint, bearer, body, deadline, output) -> {
            throw new AssertionError("Declaration and local configuration must not use the network");
        });
        var customService = new SkillCustomService(vault, (endpoint, bearer, body, deadline, limit, admission) -> {
            throw new AssertionError("Declaration and local configuration must not use the network");
        }, System::nanoTime);
        return new SkillStore(root, services, null, customService);
    }

    private static JSONObject videoDraft(String name) throws Exception {
        return new JSONObject("""
                {"schemaVersion":4,"name":"镜头动画","displayName":"视频工作台","description":"按场景生成视频",
                 "documentation":"填写画面描述","instructions":"保留原始动作要求","capabilityId":"video_task",
                 "configFields":[],"inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"海边","expectedType":"video"}}
                """).put("name", name);
    }

    private static JSONObject videoHttp() throws Exception {
        return new JSONObject("""
                {"version":1,"label":"视频服务","suggestedEndpoint":"https://suggested.example.com/v1/submit",
                 "request":{"prompt":"argument"},"response":"job_id",
                 "video":{"statusPath":"/v1/jobs/{id}","cancelPath":"/v1/jobs/{id}/cancel","cancelMethod":"POST",
                          "downloadPath":"/v1/jobs/{id}/content","idField":"id","stateField":"state",
                          "states":{"success":"completed","running":"running","failure":"failed"}}}
                """);
    }

    private static void saveVideoDraft(SkillStore store, JSONObject definition) throws Exception {
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(definition.toString())).pending());
    }

    @Test public void authoringAndInstallationCannotClaimTheSlashCreationName() throws Exception {
        SkillStore store = new SkillStore(temp.newFolder(), SkillServices.forContext(RuntimeEnvironment.getApplication()));
        String legacy = new JSONObject(draft("待检查", "测试")).put("name", "技能").toString();
        assertThrows(IllegalArgumentException.class, () -> store.installGenerated(legacy));
        String manifest = """
                {"schemaVersion":4,"name":"技能","description":"润色文字","documentation":"输入原稿",
                 "instructions":"改善表达","capabilityId":"text_model","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """;
        assertThrows(SkillManifest.Invalid.class, () -> SkillStore.validateAuthoredDraft(manifest));
        assertTrue(store.list().isEmpty());
        String neighboringName = new JSONObject(manifest).put("name", "技能润色").toString();
        assertEquals("技能润色", store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(neighboringName)).installed().name());
        assertNotNull(AiSkills.match(AiSkills.Entry.CONTACT, "/技能润色 示例", store));
        assertNull(AiSkills.match(AiSkills.Entry.CONTACT, "/技能 示例", store));
    }

    @Test public void onlyValidatedTrialPublishesAndReplacementKeepsOnlyTheCurrentDocuments() throws Exception {
        File root = temp.newFolder();
        SkillStore store = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        assertEquals(1, store.installGenerated(draft("第一版", "测试")).version());
        assertThrows(IllegalArgumentException.class, () -> store.installGenerated(draft("损坏版", "错误输出")));
        assertThrows(IllegalArgumentException.class, () ->
                store.installGenerated(draft("非法命令", "测试").replace("\"summary\"", "\"shell\"")));
        assertEquals(1, store.list().get(0).version());
        String second = draft("第二版", "测试");
        assertEquals(2, store.installGenerated(second, store.previewGenerated(second)).version());
        File[] revisions = new File(root, "versions").listFiles();
        assertNotNull(revisions);
        assertEquals(1, revisions.length);
        for (File revision : revisions) {
            assertTrue(Files.readString(new File(revision, "SKILL.md").toPath()).contains("name: 检查"));
            assertTrue(Files.readString(new File(revision, "execution.json").toPath()).contains("\"kind\":\"summary\""));
        }
        String third = draft("第三版", "测试");
        assertEquals(3, store.installGenerated(third, store.previewGenerated(third)).version());
        assertFalse(revisions[0].exists());
        assertEquals(1, new File(root, "versions").listFiles().length);
    }

    @Test public void legacyCatalogueKeepsItsActiveSkillAndLeavesDormantFilesAlone() throws Exception {
        File root = temp.newFolder();
        SkillStore store = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        store.installGenerated(draft("旧文字", "测试"));
        File revision = new File(root, "versions").listFiles()[0];
        var execution = new File(revision, "execution.json").toPath();
        var definition = new JSONObject(Files.readString(execution));
        definition.put("schemaVersion", 1).remove("outputType");
        Files.writeString(execution, definition.toString());

        var catalog = new File(root, "catalog.json").toPath();
        var index = new JSONObject(Files.readString(catalog));
        index.remove("drafts");
        var skill = index.getJSONObject("skills").getJSONObject("检查");
        var metadata = skill.getJSONObject("current");
        metadata.remove("outputType");
        metadata.remove("serviceLabel");
        String dormantId = UUID.randomUUID().toString();
        var dormant = new File(root, "versions/" + dormantId).toPath();
        Files.createDirectory(dormant);
        Files.writeString(dormant.resolve("execution.json"), "never read this dormant execution");
        var later = new JSONObject().put("version", 2).put("directory", dormantId)
                .put("description", "旧配置").put("outputType", "image");
        skill.remove("current");
        skill.put("active", 0).put("versions", new JSONArray().put(metadata).put(later));
        Files.writeString(catalog, index.toString());

        SkillStore reopened = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        assertEquals(1, reopened.settingsSnapshot().rows().get(0).current().version());
        assertEquals(SkillResult.Type.TEXT, reopened.detail("检查").current().outputType());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var invocation = AiSkills.match(entry, "/检查 旧版参数", reopened);
            assertEquals(SkillResult.Type.TEXT, invocation.command().outputType());
            assertEquals("旧版参数", AiSkills.execute(invocation, reopened));
        }
        assertEquals(index.toString(), Files.readString(catalog));

        String update = draft("新配置", "测试");
        assertEquals(3, reopened.installGenerated(update, reopened.previewGenerated(update)).version());
        assertEquals(4, reopened.installGenerated(update, reopened.previewGenerated(update)).version());
        var saved = new JSONObject(Files.readString(catalog)).getJSONObject("skills").getJSONObject("检查");
        assertEquals(4, saved.getJSONObject("current").getInt("version"));
        assertEquals(skill.getJSONArray("versions").toString(), saved.getJSONArray("versions").toString());
        assertEquals(3, new File(root, "versions").listFiles().length);
        assertEquals("never read this dormant execution", Files.readString(dormant.resolve("execution.json")));
        assertTrue(Files.exists(execution));
    }

    @Test public void aPreviewCannotReplaceADeletedSkillRecreatedAtTheSameVersion() throws Exception {
        SkillStore store = new SkillStore(temp.newFolder(), SkillServices.forContext(RuntimeEnvironment.getApplication()));
        String first = draft("初始配置", "测试");
        String update = draft("过期确认", "测试");
        store.installGenerated(first);
        SkillStore.Preview stale = store.previewGenerated(update);
        store.delete(store.detail("检查").deleteTarget());
        store.installGenerated(first);

        assertThrows(IllegalStateException.class, () -> store.installGenerated(update, stale));
        assertEquals(1, store.detail("检查").current().version());
        assertTrue(store.pending().isEmpty());
    }

    @Test public void retiredCustomServicePresetStaysBlockedUntilAnEndpointIsExplicitlySavedAndEnabled() throws Exception {
        File root = temp.newFolder();
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        SkillStore store = new SkillStore(root, services);
        var proposal = SkillStore.validateAuthoredDraft("""
                {"schemaVersion":4,"name":"检查接口","description":"提取内容","documentation":"输入待处理文字",
                 "instructions":"检查内容","capabilityId":"custom_service","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"样例","expectedType":"text"}}
                """);
        assertNotNull(store.saveAuthoredDraft(proposal).pending());
        String target = "https://skill-api.example.com/v1/process";
        store.configureCustomService(store.detail("检查接口"), target, "");

        var catalog = new File(root, "catalog.json").toPath();
        var index = new JSONObject(Files.readString(catalog));
        var record = index.getJSONObject("skills").getJSONObject("检查接口");
        record.put("customBinding", new JSONObject().put("preset", "mock_text_v1")
                .put("revision", UUID.randomUUID().toString()).put("sealed", "retired-fixture"));
        Files.writeString(catalog, index.toString());

        SkillStore reopened = new SkillStore(root, services);
        SkillStore.Detail blocked = reopened.detail("检查接口");
        assertFalse(blocked.installedEnabled());
        assertNull(blocked.customConfiguration());
        assertTrue(blocked.reason().contains("公网 HTTPS"));
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.WORK, "/检查接口 样例", reopened));
        assertThrows(IllegalStateException.class, () -> reopened.setSkillEnabled("检查接口", true));

        SkillStore.Detail replacement = reopened.configureCustomService(blocked, target, "");
        assertTrue(replacement.canReplace());
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.CONTACT, "/检查接口 样例", reopened));
        SkillStore.Detail ready = reopened.activateReplacement(replacement);
        assertEquals(target, ready.customConfiguration().endpoint());
        assertFalse(ready.customConfiguration().hasCredential());
        var call = AiSkills.match(AiSkills.Entry.CONTACT, "/检查接口 样例", reopened);
        assertTrue(call.command().requiresReview());
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(call, reopened));
    }

    @Test public void reloadedStateControlsBothEntriesAndDisablesQueuedVersion() throws Exception {
        File root = temp.newFolder();
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        SkillStore store = new SkillStore(root, services);
        store.installGenerated(draft("第一版", "测试"));
        SkillStore reopened = new SkillStore(root, services);
        AiSkills.Invocation invocation = AiSkills.match(AiSkills.Entry.CONTACT, "/检查 参数", reopened);
        assertEquals("参数", AiSkills.execute(invocation, store));
        assertNotNull(AiSkills.match(AiSkills.Entry.WORK, "/检查 参数", reopened));

        reopened.setSkillEnabled("检查", false);
        assertNull(AiSkills.match(AiSkills.Entry.WORK, "/检查 参数", store));
        assertThrows(IllegalStateException.class, () -> AiSkills.execute(invocation, store));
        reopened.setSkillEnabled("检查", true);
        reopened.setEnabled(false);
        assertFalse(store.enabled());
        assertTrue(AiSkills.commands(AiSkills.Entry.CONTACT, "/", store).isEmpty());
        assertNull(AiSkills.match(AiSkills.Entry.WORK, "/摘要 测试", store));
    }

    @Test public void mockFixtureCanInstallAndExecuteWithoutModelOrCredential() throws Exception {
        SkillStore store = new SkillStore(temp.newFolder(), SkillServices.forContext(RuntimeEnvironment.getApplication()));
        store.installGenerated(SkillStore.mockDraft("以模拟订单接口查询状态"));
        assertEquals("模拟查询：订单 DA-1001 已发货。",
                AiSkills.execute(AiSkills.match(AiSkills.Entry.WORK, "/模拟技能 DA-1001", store), store));
    }

    @Test public void generatedRemoteBindingNeedsConfirmationAndPinsExactEndpoint() throws Exception {
        File root = temp.newFolder();
        String secret = "fixture-service-key";
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SkillServices services = new SkillServices(root,
                new com.douai.module.ai.AiCredentialVault(() -> new javax.crypto.spec.SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, outputType) -> {
                    calls.incrementAndGet();
                    return new SkillServices.Response(200, "text/plain", "已完成".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
        SkillStore store = new SkillStore(new File(root, "skills"), services);
        SkillServices.Service old = new SkillServices.Service("lookup", "查询", "https://before.example/api", 1);
        services.register(old, secret);
        services.authorize(old, 1);

        String draft = new org.json.JSONObject()
                .put("schemaVersion", 1).put("name", "查状态").put("description", "查询")
                .put("documentation", "输入查询条件")
                .put("execution", new org.json.JSONObject().put("kind", "service")
                        .put("maxInput", 64).put("serviceId", "lookup"))
                .put("trial", new org.json.JSONObject().put("input", "测试").put("expected", "模拟服务响应"))
                .toString();
        assertFalse(store.generationPrompt("查状态").contains(secret));
        assertFalse(store.generationPrompt("查状态").contains(old.endpoint()));
        SkillStore.Preview preview = store.previewGenerated(draft);
        assertTrue(preview.needsConfirmation());
        assertThrows(IllegalStateException.class, () -> store.installGenerated(draft));
        store.installGenerated(draft, preview);
        assertEquals(0, calls.get());
        AiSkills.Invocation run = AiSkills.match(AiSkills.Entry.CONTACT, "/查状态 请求", store);
        assertEquals("已完成", AiSkills.execute(run, store));

        SkillServices.Service moved = new SkillServices.Service("lookup", "查询", "https://after.example/api", 1);
        services.register(moved, "");
        services.authorize(moved, 1);
        assertThrows(SkillServices.Failure.class, () -> AiSkills.execute(run, store));
        assertThrows(IllegalStateException.class, () -> store.installGenerated(draft, preview));
        assertEquals(1, calls.get());
        assertEquals(1, store.list().get(0).version());
    }
    private static String draft(String documentation, String expected) throws Exception {
        return new org.json.JSONObject()
                .put("schemaVersion", 1).put("name", "检查").put("description", "测试摘要")
                .put("documentation", documentation)
                .put("execution", new org.json.JSONObject().put("kind", "summary").put("maxInput", 40))
                .put("trial", new org.json.JSONObject().put("input", "测试").put("expected", expected))
                .toString();
    }
}
