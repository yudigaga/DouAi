package com.douai.module.ai.skills;

import android.content.Context;

import com.douai.module.ai.mcp.McpRegistry;

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
import java.nio.charset.StandardCharsets;
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

    public static SkillStore chatPackageStore(Context context, McpRegistry registry) {
        Context app = context.getApplicationContext();
        Context owner = app == null ? context : app;
        return new SkillStore(new File(owner.getNoBackupFilesDir(), "douai-ai/skills"),
                SkillServices.forContext(owner), registry, SkillCustomService.forDevice());
    }

    @Test public void portableMediaFilesBecomePendingAndCanBeConfiguredThroughExistingFlows() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        var image = SkillStore.validateImportedDraft(importFixture("text-to-image.json"));
        var video = SkillStore.validateImportedDraft(importFixture("text-to-video.json"));
        assertEquals(SkillResult.Type.IMAGE, image.outputType());
        assertEquals(SkillResult.Type.VIDEO, video.outputType());
        assertTrue(store.settingsSnapshot().rows().isEmpty());
        var imageDraft = store.saveImportedDraft(image);
        var videoDraft = store.saveImportedDraft(video);

        SkillStore reopened = videoStore(root);
        assertEquals(imageDraft.draftId(), reopened.detail(image.name()).draftId());
        assertEquals(videoDraft.draftId(), reopened.detail(video.name()).draftId());
        assertEquals(SkillStore.NEEDS_VIDEO_PROVIDER, reopened.detail(video.name()).reason());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertThrows(AiSkills.CommandUnavailable.class, () -> AiSkills.match(entry, "/" + image.name() + " 暖光", reopened));
            assertThrows(AiSkills.CommandUnavailable.class, () -> AiSkills.match(entry, "/" + video.name() + " 镜头前移", reopened));
        }
        assertThrows(IllegalStateException.class, () -> reopened.saveImportedDraft(image));
        assertEquals(imageDraft.draftId(), reopened.detail(image.name()).draftId());
        assertTrue(reopened.list().isEmpty());

        SkillStore.Detail savedImage = reopened.saveConfiguration(image.name(), imageDraft.draftId(),
                SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        reopened.authorizeConfiguration(image.name(), savedImage.draftId(), savedImage.service());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var call = AiSkills.match(entry, "/" + image.name() + " 暖光", reopened);
            assertEquals(SkillResult.Type.IMAGE, call.command().outputType());
            assertTrue(AiSkills.executeResult(call, reopened) instanceof SkillResult.Image);
        }
    }

    @Test public void importedMediaRejectsBoundServicesAndRespectsExistingInstalledSkill() throws Exception {
        SkillStore store = videoStore(temp.newFolder());
        String name = "提示词生视频";
        JSONObject manifest = new JSONObject(importFixture("text-to-video.json"));
        JSONObject http = videoHttp();
        manifest.put("http", http);
        assertThrows(IllegalArgumentException.class, () -> SkillStore.validateImportedDraft(manifest.toString()));
        http.remove("suggestedEndpoint");
        manifest.put("servicePrefill", new JSONObject().put("endpoint", "https://video.example.com/v1/jobs"));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.validateImportedDraft(manifest.toString()));
        manifest.remove("servicePrefill");
        manifest.put("draftValues", new JSONObject());
        assertThrows(IllegalArgumentException.class, () -> SkillStore.validateImportedDraft(manifest.toString()));
        manifest.remove("draftValues");
        manifest.put("documentation", "Authorization: Bearer fixture-video-secret");
        assertThrows(IllegalArgumentException.class, () -> SkillStore.validateImportedDraft(manifest.toString()));

        manifest.put("documentation", "输入镜头提示词");
        var imported = SkillStore.validateImportedDraft(manifest.toString());
        store.installGenerated(new JSONObject(draft("当前版本", "测试")).put("name", name).toString());
        assertThrows(IllegalStateException.class, () -> store.saveImportedDraft(imported));
        assertEquals(SkillResult.Type.TEXT, store.detail(name).current().outputType());
        assertFalse(store.detail(name).pending());

        manifest.put("name", "相对路径视频");
        var accepted = SkillStore.validateImportedDraft(manifest.toString());
        store.saveImportedDraft(accepted);
        var pending = store.detail(accepted.name());
        assertEquals("/v1/jobs/{id}", pending.manifest().http().video().statusPath());
        assertNull(pending.customConfiguration());
    }

    @Test public void newApiVideoPresetUsesTheBoundGatewayAndSameTaskIdAfterConfiguration() throws Exception {
        var store = videoStore(temp.newFolder());
        var preview = SkillStore.validateImportedDraft(importFixture("new-api-text-to-video.json"));
        store.saveImportedDraft(preview);
        var draft = store.detail(preview.name());
        assertNull(draft.customConfiguration());
        String endpoint = "https://gateway.example.com/v1/videos";
        var enabled = store.configureCustomService(draft, endpoint, "fixture-gateway-token");
        assertFalse(enabled.pending());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var call = AiSkills.match(entry, "/" + preview.name() + " 雨后街景", store);
            assertEquals(SkillResult.Type.VIDEO, call.command().outputType());
        }
        var declaration = enabled.manifest().http();
        var request = new JSONObject(new String(declaration.requestBody("雨后街景", enabled.manifestValues()),
                StandardCharsets.UTF_8));
        assertEquals("雨后街景", request.getString("prompt"));
        assertEquals("grok-imagine-video-1.5-lite", request.getString("model"));
        assertEquals(5, request.getInt("duration"));
        assertEquals("480p", request.getString("resolution"));
        assertEquals("id", declaration.responseField());
        var state = new JSONObject().put("id", "task_123").put("status", "completed");
        assertEquals("https://gateway.example.com/v1/videos/task_123",
                declaration.video().statusEndpoint(endpoint, "task_123"));
        assertEquals(SkillHttpDeclaration.VideoState.COMPLETED, declaration.video().readStatus(state, "task_123"));
        assertEquals("https://gateway.example.com/v1/videos/task_123/content",
                declaration.video().completedDownloadEndpoint(state, endpoint, "task_123"));
    }

    private static String importFixture(String name) throws Exception {
        try (var input = SkillStoreTest.class.getResourceAsStream("/skill-import/" + name)) {
            assertNotNull(input);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

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

    @Test public void pastedVideoDeclarationCanUseAResultFieldWithoutCancellation() throws Exception {
        SkillStore store = videoStore(temp.newFolder());
        saveVideoDraft(store, videoDraft("镜头动画"));
        JSONObject http = videoHttp();
        JSONObject video = http.getJSONObject("video");
        video.remove("cancelPath");
        video.remove("cancelMethod");
        video.remove("downloadPath");
        video.put("resultUrlField", "data.result.url");
        SkillStore.Detail saved = store.saveVideoDeclaration(store.detail("镜头动画"), http.toString());
        assertTrue(saved.pending());
        assertTrue(saved.manifest().http().video().cancelPath().isEmpty());
        assertTrue(saved.manifest().http().video().downloadPath().isEmpty());
        assertEquals("data.result.url", saved.manifest().http().video().resultUrlField());
        assertNull(saved.customConfiguration());
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

    @Test public void videoFormMergesOnlyMissingFactsAndReopensWithTheExactManualEdits() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        SkillVideoFacts original = SkillVideoFacts.parse("""
                提交: POST https://video.example.com/v1/jobs
                提示词字段: prompt
                提交响应ID: job_id
                """);
        store.saveAuthoredVideoDraft(SkillStore.prepareAuthoredVideoDraft(videoDraft("镜头动画").toString(), original), original);
        SkillStore.Detail initial = store.detail("镜头动画");
        SkillVideoFacts supplement = SkillVideoFacts.parse("""
                提示词字段: document_prompt
                查询: GET /v1/jobs/{id}
                """);
        SkillStore.Detail edited = store.saveVideoFactsForm(initial, supplement,
                java.util.Map.of("promptField", "manual_prompt", "statusPath", ""));
        assertTrue(edited.pending());
        assertNull(edited.manifest().http());
        assertFalse(initial.draftId().equals(edited.draftId()));
        assertThrows(IllegalStateException.class, () -> store.saveVideoFactsForm(initial, supplement, java.util.Map.of()));

        SkillStore reopened = videoStore(root);
        SkillStore.Detail saved = reopened.detail("镜头动画");
        assertEquals(edited.deleteTarget(), saved.deleteTarget());
        assertEquals("manual_prompt", videoFormField(saved, "promptField").value());
        assertEquals(SkillVideoFacts.Source.MANUAL, videoFormField(saved, "promptField").source());
        assertEquals("", videoFormField(saved, "statusPath").value());
        SkillStore.Detail withMoreDocs = reopened.saveVideoFactsForm(saved, supplement, java.util.Map.of());
        assertEquals("manual_prompt", videoFormField(withMoreDocs, "promptField").value());
        assertEquals("", videoFormField(withMoreDocs, "statusPath").value());
        assertTrue(reopened.list().isEmpty());
    }

    @Test public void priorVideoReferenceUsesAnExactPendingDraftAndOnlyFillsUnsetFacts() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        saveVideoDraft(store, videoDraft("镜头动画"));
        assertNotNull(store.saveGenerated(SkillStore.mockDraft("生成图片"), null).pending());
        SkillStore.Detail initial = store.detail("镜头动画");
        SkillVideoFacts earlier = SkillVideoFacts.parse("查询: GET /older/jobs/{id}");
        store.saveVideoFactsForm(initial, earlier, java.util.Map.of("promptField", "my_prompt", "statusPath", ""));
        java.util.List<SkillStore.Detail> candidates = store.pendingVideoDrafts();
        assertEquals(1, candidates.size());
        SkillStore.Detail chosen = candidates.get(0);
        assertEquals("镜头动画", chosen.name());
        assertEquals(SkillResult.Type.VIDEO, chosen.outputType());

        SkillVideoFacts reference = SkillVideoFacts.parse("""
                提交: POST https://video.example.com/v1/jobs
                提示词字段: documented_prompt
                查询: GET /v1/jobs/{id}
                提交响应ID: job_id
                """);
        SkillStore.Detail applied = store.applyVideoReference(chosen, reference);
        assertNull(applied.customConfiguration());
        assertEquals("my_prompt", videoFormField(applied, "promptField").value());
        assertEquals("", videoFormField(applied, "statusPath").value());
        assertEquals("job_id", videoFormField(applied, "submitIdField").value());
        assertTrue(store.list().isEmpty());
        assertEquals(applied.deleteTarget(), videoStore(root).pendingVideoDrafts().get(0).deleteTarget());
        assertThrows(IllegalStateException.class, () -> store.applyVideoReference(chosen, reference));
    }

    @Test public void referencedUnsavedVideoPreviewCannotReplaceADraftSavedAfterTheChoice() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        SkillStore.DraftPreview unsaved = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        SkillVideoFacts reference = SkillVideoFacts.parse("提示词字段: prompt");
        assertTrue(store.pendingVideoDrafts().isEmpty());
        saveVideoDraft(store, videoDraft(name).put("description", "较新的本机草稿"));
        var catalog = new File(root, "catalog.json").toPath();
        String original = Files.readString(catalog);
        assertThrows(IllegalStateException.class, () -> store.saveReferencedVideoDraft(unsaved, reference));
        assertEquals(original, Files.readString(catalog));
        assertEquals("较新的本机草稿", store.detail(name).manifest().description());

        SkillStore.DraftPreview distinct = SkillStore.validateAuthoredDraft(videoDraft("空白动画").toString());
        SkillStore.SaveResult result = store.saveReferencedVideoDraft(distinct, reference);
        assertNull(result.installed());
        assertEquals("空白动画", result.pending().name());
        assertEquals("prompt", videoFormField(store.detail("空白动画"), "promptField").value());
        assertTrue(store.list().isEmpty());
    }

    @Test public void unsupportedSubmissionEvidenceRemainsPendingUntilAnExplicitJsonCorrection() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        saveVideoDraft(store, videoDraft(name));
        SkillVideoFacts complete = SkillVideoFacts.parse("""
                提交: POST https://video.example.com/v1/jobs
                提示词字段: prompt
                提交响应ID: job_id
                查询: GET /v1/jobs/{id}
                查询响应ID: id
                状态字段: status
                状态映射: done=completed
                下载: GET /v1/jobs/{id}/file
                """);
        SkillVideoFacts unsupported = complete.withForm(java.util.Map.of("submitEncoding", "multipart/form-data"));
        SkillStore.Detail blocked = store.applyVideoReference(store.detail(name), unsupported);
        assertTrue(blocked.pending());
        assertNull(blocked.manifest().http());
        assertTrue(blocked.reason().contains("application/json"));
        assertEquals("multipart/form-data", blocked.videoFacts().submitEncoding());
        assertNull(blocked.customConfiguration());
        assertTrue(store.list().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.saveVideoDeclaration(blocked, videoHttp().toString()));
        assertThrows(IllegalArgumentException.class, () -> store.saveVideoFactsForm(blocked, null,
                java.util.Map.of("submitEncoding", "")));

        SkillStore reopened = videoStore(root);
        SkillStore.Detail exact = reopened.detail(name);
        assertEquals(blocked.deleteTarget(), exact.deleteTarget());
        assertThrows(IllegalArgumentException.class, () -> reopened.saveAuthoredVideoDraft(
                SkillStore.prepareAuthoredVideoDraft(videoDraft(name).toString(), complete), complete));
        SkillStore.Detail corrected = reopened.saveVideoFactsForm(exact, null, java.util.Map.of("submitEncoding", "application/json"));
        assertTrue(corrected.pending());
        assertNotNull(corrected.manifest().http());
        assertNull(corrected.customConfiguration());
        assertTrue(reopened.list().isEmpty());
    }

    @Test public void videoFormKeepsValuesOnlyUntilTheContractChanges() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        String endpoint = "https://saved.example.com/v1/submit";
        JSONObject http = videoHttp();
        http.getJSONObject("request").put("duration", "config.duration");
        JSONArray fields = new JSONArray().put(new JSONObject().put("key", "duration").put("label", "时长")
                .put("type", "integer").put("required", true).put("min", 1).put("max", 15));
        saveVideoDraft(store, videoDraft(name).put("http", http).put("configFields", fields));
        SkillStore.Detail valued = store.saveManifestValues(store.detail(name), java.util.Map.of("duration", "6"));
        SkillStore.Detail current = store.configureCustomService(valued, endpoint, "synthetic-video-source-key");

        SkillVideoFacts facts = store.videoFactsForSkill(name);
        store.saveAuthoredVideoDraft(SkillStore.prepareAuthoredVideoDraft(videoDraft(name).toString(), facts), facts);
        String pendingKey = "synthetic-video-pending-key";
        SkillStore.Detail bound = store.configureCustomService(store.detail(name), endpoint, pendingKey, false,
                java.util.Map.of("duration", "8"));
        SkillStore.Detail relabelled = store.saveVideoFactsForm(bound, null, java.util.Map.of("label", "新服务名称"));
        assertEquals(bound.customConfiguration(), relabelled.customConfiguration());
        assertEquals(current.current(), relabelled.current());
        assertTrue(relabelled.pending());
        SkillVideoFacts incompatible = SkillVideoFacts.fromForm(java.util.Map.of("submitEncoding", "multipart/form-data"));
        var catalog = new File(root, "catalog.json").toPath();
        String beforeReference = Files.readString(catalog);
        assertThrows(IllegalArgumentException.class, () -> store.applyVideoReference(relabelled, incompatible));
        assertEquals(beforeReference, Files.readString(catalog));
        assertEquals(relabelled.customConfiguration(), store.detail(name).customConfiguration());
        assertEquals(8, store.detail(name).manifestValues().get("duration"));
        assertThrows(SkillCustomService.Failure.class, () -> store.saveVideoFactsForm(relabelled, null,
                java.util.Map.of("submitEndpoint", "https://other.example.com/v2/" + pendingKey)));
        assertEquals(relabelled.deleteTarget(), store.detail(name).deleteTarget());

        SkillStore.Detail changed = store.saveVideoFactsForm(relabelled, null,
                java.util.Map.of("submitEndpoint", "https://other.example.com/v2/submit", "statusPath", "/v2/jobs/{id}"));
        assertTrue(changed.pending());
        assertEquals("/v2/jobs/{id}", changed.manifest().http().video().statusPath());
        assertEquals("https://other.example.com/v2/submit", changed.manifest().http().suggestedEndpoint());
        assertNull(changed.customConfiguration());
        assertNull(changed.manifestValues().get("duration"));
        SkillStore.Detail partial = store.saveVideoFactsForm(changed, null, java.util.Map.of("statusPath", ""));
        assertNull(partial.manifest().http());
        assertTrue(partial.videoMissing().stream().anyMatch(field -> field.contains("查询 GET")));

        SkillStore reopened = videoStore(root);
        SkillStore.Detail completed = reopened.saveVideoFactsForm(reopened.detail(name), null,
                java.util.Map.of("statusPath", "/v2/jobs/{id}"));
        assertTrue(completed.pending());
        assertNull(completed.customConfiguration());
        assertEquals(current.current(), completed.current());
        assertNull(completed.manifestValues().get("duration"));
        assertFalse(Files.readString(new File(root, "catalog.json").toPath()).contains(pendingKey));
    }

    private static SkillVideoFacts.FormField videoFormField(SkillStore.Detail detail, String id) {
        return detail.videoFacts().formFields().stream().filter(field -> id.equals(field.id())).findFirst().orElseThrow();
    }

    @Test public void conversationVideoSaveRequiresExactPendingIdentityAndRetainsCompatibleConfiguration() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        SkillVideoFacts facts = SkillVideoFacts.parse("""
                提交: POST https://video.example.com/v1/videos
                提示词字段: prompt
                提交响应ID: id
                必填整数字段: duration 时长
                查询: GET /v1/videos/{id}
                查询响应ID: id
                状态字段: state
                状态映射: done=completed
                下载: GET /v1/videos/{id}/content
                """);
        String name = "镜头动画";
        String endpoint = "https://video.example.com/v1/videos";
        SkillStore.DraftPreview initial = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        assertNotNull(store.saveConversationDraft(initial, facts, null).pending());
        SkillStore.Detail active = store.configureCustomService(store.detail(name), endpoint, "fixture-active-video-key",
                false, java.util.Map.of("duration", "6"));
        assertEquals(1, active.current().version());
        var pending = store.saveConversationDraft(initial, facts, "").pending();
        assertNotNull(pending);
        SkillStore.Detail configured = store.configureCustomService(store.detail(name), endpoint, "fixture-pending-video-key",
                false, java.util.Map.of("duration", "8"));
        assertTrue(configured.canReplace());
        JSONObject changed = videoDraft(name).put("description", "改写草稿说明");
        var revised = SkillStore.validateAuthoredDraft(changed.toString());
        var catalog = new File(root, "catalog.json").toPath();
        String before = Files.readString(catalog);
        assertThrows(IllegalStateException.class, () -> store.saveConversationDraft(revised, facts, null));
        assertThrows(IllegalStateException.class, () -> store.saveConversationDraft(revised, facts, pending.draftId()));
        assertEquals(before, Files.readString(catalog));

        var result = store.saveConversationDraft(revised, null, configured.draftId());
        assertNotNull(result.pending());
        SkillStore.Detail ready = store.detail(name);
        assertEquals("改写草稿说明", ready.manifest().description());
        assertEquals(configured.customConfiguration(), ready.customConfiguration());
        assertEquals(8, ready.manifestValues().get("duration"));
        assertEquals(active.current(), ready.current());
        assertThrows(IllegalStateException.class, () -> store.saveConversationDraft(revised, facts, configured.draftId()));

        var scheduled = store.saveConversationDraft(revised, facts.withMinimumPollSeconds(15), ready.draftId());
        assertNotNull(scheduled.pending());
        SkillStore.Detail scheduledDetail = store.detail(name);
        assertEquals(15, scheduledDetail.manifest().http().video().minPollSeconds());
        assertEquals(15, scheduledDetail.videoFacts().minimumPollSeconds());
        var rescheduled = store.saveConversationDraft(revised, facts.withMinimumPollSeconds(12), scheduledDetail.draftId());
        assertNotNull(rescheduled.pending());
        assertNotNull(store.saveConversationDraft(revised, null, rescheduled.pending().draftId()).pending());
        SkillStore.Detail savedSchedule = store.detail(name);
        assertEquals(12, savedSchedule.videoFacts().minimumPollSeconds());
        assertEquals(ready.customConfiguration(), savedSchedule.customConfiguration());
        assertEquals(8, savedSchedule.manifestValues().get("duration"));

        SkillVideoFacts changedWire = facts.withForm(java.util.Map.of("statusPath", "/v2/videos/{id}"));
        var revisedWire = store.saveConversationDraft(revised, changedWire, savedSchedule.draftId());
        assertNotNull(revisedWire.pending());
        SkillStore.Detail unbound = store.detail(name);
        assertEquals("/v2/videos/{id}", unbound.manifest().http().video().statusPath());
        assertNull(unbound.customConfiguration());
        assertEquals(8, unbound.manifestValues().get("duration"));
        assertEquals(12, unbound.videoFacts().minimumPollSeconds());
        assertEquals(active.current(), unbound.current());
    }

    @Test public void relativeVideoApiBaseBindsOnlyTheValidatedFullSameOriginEndpoint() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        SkillVideoFacts facts = SkillVideoFacts.parse("""
                提交: POST /v1/videos
                提交请求编码: application/json
                提示词字段: prompt
                提交响应ID: id
                查询: GET https://video.example.com:8443/v1/videos/{id}
                查询响应ID: id
                状态字段: state
                状态映射: done=completed
                下载: GET /v1/videos/{id}/content
                """);
        String name = "镜头动画";
        var proposal = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        assertNotNull(store.saveConversationDraft(proposal, facts, null).pending());
        SkillStore.Detail pending = store.detail(name);
        assertTrue(pending.reason().contains("API 基础地址"));
        assertEquals("", pending.manifest().http().suggestedEndpoint());
        assertNull(pending.customConfiguration());
        assertThrows(IllegalArgumentException.class, () -> store.configureCustomServiceBase(
                pending, "https://video.example.com", "fixture-relative-video-key", false, null));
        assertThrows(IllegalArgumentException.class, () -> store.configureCustomService(
                pending, "https://different.example.com:8443/v1/videos", ""));
        assertThrows(IllegalArgumentException.class, () -> store.configureCustomService(
                pending, "https://video.example.com:8443/v1/legacy-submit", ""));
        assertEquals(pending.deleteTarget(), store.detail(name).deleteTarget());

        String base = "https://video.example.com:8443";
        SkillStore.Detail installed = store.configureCustomServiceBase(pending, base, "fixture-relative-video-key", false, null);
        assertFalse(installed.pending());
        assertEquals(base + "/v1/videos", installed.customConfiguration().endpoint());
        assertTrue(installed.customConfiguration().hasCredential());
        SkillStore reopened = videoStore(root);
        SkillStore.Detail active = reopened.detail(name);
        assertTrue(active.videoFacts().needsApiBase());
        assertEquals("/v1/videos", active.videoFacts().submitPath());
        assertEquals(base, active.videoFacts().apiBaseForEndpoint(active.customConfiguration().endpoint()));
        SkillStore.ConversationDraft open = reopened.openConversationDraft(name);
        assertEquals(name, open.preview().name());
        assertEquals("", open.pendingId());
        assertEquals(active.deleteTarget(), open.identity());
        assertTrue(open.facts().needsApiBase());
        assertFalse(open.preview().definition().contains("fixture-relative-video-key"));
        AiSkills.Invocation call = AiSkills.match(AiSkills.Entry.CONTACT, "/镜头动画 海边", store);
        assertEquals(base + "/v1/videos", store.previewCustomService(call).endpoint());

        var edited = SkillStore.validateAuthoredDraft(new JSONObject(open.preview().definition())
                .put("description", "沿用触发逻辑的新说明").toString());
        var staged = reopened.saveConversationDraft(edited, open.facts(), "", open.identity());
        assertNotNull(staged.pending());
        assertTrue(reopened.detail(name).canReplace());
        assertEquals(active.customConfiguration(), reopened.detail(name).customConfiguration());
        assertThrows(IllegalStateException.class, () -> reopened.saveConversationDraft(edited, open.facts(), "", open.identity()));

        SkillStore.ConversationDraft sameSkill = reopened.openConversationDraft(name);
        SkillVideoFacts switched = sameSkill.facts().withForm(java.util.Map.of("statusPath", "/v2/videos/{id}"));
        assertThrows(IllegalStateException.class, () -> reopened.saveConversationDraft(sameSkill.preview(), switched,
                UUID.randomUUID().toString(), sameSkill.identity(), true));
        var replacement = reopened.saveConversationDraft(sameSkill.preview(), switched, sameSkill.pendingId(),
                sameSkill.identity(), true);
        assertNotNull(replacement.pending());
        SkillStore.Detail migrated = reopened.detail(name);
        assertNull(migrated.customConfiguration());
        assertEquals("/v2/videos/{id}", migrated.manifest().http().video().statusPath());
        assertEquals(active.current(), migrated.current());
        assertEquals(base + "/v1/videos", reopened.previewCustomService(call).endpoint());
    }

    @Test public void newlyAuthoredVideoContractDoesNotInheritEitherOldBindingOrModel() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        String oldEndpoint = "https://legacy.example.com/v1/submit";
        String oldKey = "fixture-old-video-authority";
        SkillStore.DraftPreview seed = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        SkillVideoFacts old = switchVideoFacts("legacy.example.com", "prompt", "视频模型");
        store.saveAuthoredVideoDraft(seed, old);
        store.configureCustomService(store.detail(name), oldEndpoint, oldKey, false,
                java.util.Map.of("model", "legacy-active-model", "duration", "6"));
        store.saveAuthoredVideoDraft(seed, old);
        store.configureCustomService(store.detail(name), oldEndpoint, "fixture-old-pending-authority", false,
                java.util.Map.of("model", "legacy-pending-model", "duration", "8"));

        JSONObject document = switchVideoFacts("new.example.com", "scene", "视频模型").toJson();
        document.remove("submitEndpoint");
        document.remove("provenance");
        document.put("submitPath", "/v1/submit").put("submitEncoding", "application/json");
        document.getJSONArray("configFields").getJSONObject(0).put("default", "verified-new-model");
        SkillVideoFacts verified = SkillVideoFacts.fromDocument(document);
        assertNotNull(store.saveAuthoredVideoDraft(seed, verified).pending());

        SkillStore reopened = videoStore(root);
        SkillStore.Detail next = reopened.detail(name);
        assertEquals("verified-new-model", next.manifestValues().get("model"));
        assertFalse(next.manifestValues().containsKey("duration"));
        assertNull(next.customConfiguration());
        assertEquals("argument", next.manifest().http().request().get("scene"));
        assertEquals(oldEndpoint, reopened.previewCustomService(AiSkills.match(AiSkills.Entry.WORK,
                "/镜头动画 山谷", reopened)).endpoint());
        SkillStore.ConversationDraft opened = reopened.openConversationDraft(name);
        assertEquals(next.draftId(), opened.pendingId());
        assertEquals("verified-new-model", opened.preview().publicFields().get("config.model"));
        assertFalse(opened.preview().definition().contains(oldEndpoint));
        assertFalse(opened.preview().definition().contains(oldKey));
    }

    @Test public void explicitVideoModelLookupUsesOnlyTheExactSelectedCredentialAndDoesNotSave() throws Exception {
        File root = temp.newFolder();
        var endpoints = new java.util.ArrayList<String>();
        var tokens = new java.util.ArrayList<String>();
        SkillStore store = videoStore(root, (endpoint, bearer) -> {
            endpoints.add(endpoint);
            tokens.add(bearer);
            return java.util.List.of("listed-model");
        });
        String name = "镜头动画";
        JSONObject document = switchVideoFacts("unused.example.com", "prompt", "视频模型").toJson();
        document.remove("submitEndpoint");
        document.remove("provenance");
        document.put("submitPath", "/v1/videos").put("submitEncoding", "application/json");
        var seed = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        store.saveAuthoredVideoDraft(seed, SkillVideoFacts.fromDocument(document));
        SkillStore.Detail pending = store.detail(name);
        var path = new File(root, "catalog.json").toPath();
        String draft = Files.readString(path);
        String base = "https://video.example.com";
        assertEquals(java.util.List.of("listed-model"), store.discoverVideoModels(pending, base, "fixture-typed-key"));
        assertEquals(draft, Files.readString(path));
        SkillStore.Detail active = store.configureCustomServiceBase(pending, base, "fixture-installed-key", false,
                java.util.Map.of("model", "listed-model", "duration", "5"));
        String installed = Files.readString(path);
        assertThrows(IllegalStateException.class, () -> store.discoverVideoModels(pending, base, ""));
        assertThrows(IllegalArgumentException.class, () -> store.discoverVideoModels(active, base + "/v1/old", ""));
        assertEquals(java.util.List.of("listed-model"), store.discoverVideoModels(active, base + "/v1/videos", ""));
        assertEquals(java.util.List.of("listed-model"), store.discoverVideoModels(active, "https://different.example.com", ""));
        assertEquals(java.util.List.of(base + "/v1/videos", base + "/v1/videos",
                "https://different.example.com/v1/videos"), endpoints);
        assertEquals(java.util.List.of("fixture-typed-key", "fixture-installed-key", ""), tokens);
        assertEquals(installed, Files.readString(path));
    }

    @Test public void conversationTextSaveKeepsNewInstallationAndExplicitReplacement() throws Exception {
        SkillStore store = videoStore(temp.newFolder());
        JSONObject text = new JSONObject("""
                {"schemaVersion":4,"name":"文案草稿","description":"润色文字","documentation":"输入原稿",
                 "instructions":"改善表达","capabilityId":"text_model","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """);
        SkillStore.DraftPreview initial = SkillStore.validateAuthoredDraft(text.toString());
        assertEquals(1, store.saveConversationDraft(initial, null, null).installed().version());
        var revised = SkillStore.validateAuthoredDraft(text.put("description", "保持格式").toString());
        var selected = store.saveConversationDraft(revised, null, null).pending();
        assertNotNull(selected);
        assertThrows(IllegalStateException.class, () -> store.saveConversationDraft(revised, null, null));
        var staged = store.saveConversationDraft(revised, null, selected.draftId()).pending();
        assertNotNull(staged);
        assertEquals(1, store.detail("文案草稿").current().version());
    }

    @Test public void selectedConversationDraftCanEnrichOnlyDocumentedUnknownEncoding() throws Exception {
        SkillStore store = videoStore(temp.newFolder());
        SkillVideoFacts incomplete = SkillVideoFacts.parseSupplement("""
                提交: POST /v1/videos
                提示词字段: prompt
                提交响应ID: id
                查询: GET /v1/videos/{id}
                查询响应ID: id
                状态字段: status
                状态映射: done=completed
                下载: GET /v1/videos/{id}/content
                """);
        var pending = SkillStore.validateAuthoredDraft(videoDraft("镜头动画").toString());
        var blocked = store.saveConversationDraft(pending, incomplete, null).pending();
        assertNull(store.detail("镜头动画").manifest().http());
        SkillVideoFacts json = SkillVideoFacts.parseSupplement("Content-Type: application/json");
        var enriched = store.saveConversationDraft(pending, json, blocked.draftId()).pending();
        assertNotNull(enriched);
        assertNotNull(store.detail("镜头动画").manifest().http());
        assertNull(store.detail("镜头动画").customConfiguration());

        var multipart = SkillStore.validateAuthoredDraft(videoDraft("表单动画").toString());
        SkillVideoFacts form = incomplete.fillMissingFrom(SkillVideoFacts.parseSupplement("Content-Type: multipart/form-data"));
        var unsupported = store.saveConversationDraft(multipart, form, null).pending();
        assertThrows(IllegalArgumentException.class,
                () -> store.saveConversationDraft(multipart, json, unsupported.draftId()));
        assertNull(store.detail("表单动画").manifest().http());
        assertEquals("multipart/form-data", store.detail("表单动画").videoFacts().submitEncoding());
    }

    @Test public void explicitVideoReplacementIsAtomicAndNeverInheritsEitherOldBinding() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        var draft = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        SkillVideoFacts first = switchVideoFacts("first.example.com", "prompt", "原供应商模型");
        SkillVideoFacts next = switchVideoFacts("second.example.com", "scene", "新供应商型号")
                .withForm(java.util.Map.of("statusPath", "https://second.example.com/v1/jobs/{id}"));
        String firstEndpoint = "https://first.example.com/v1/submit";
        String secondEndpoint = "https://second.example.com/v1/submit";
        String priorKey = "fixture-first-active-key";
        String pendingKey = "fixture-first-pending-key";
        assertNotNull(store.saveConversationDraft(draft, first, null).pending());
        SkillStore.Detail active = store.configureCustomService(store.detail(name), firstEndpoint, priorKey, false,
                java.util.Map.of("model", "first-model", "duration", "6"));
        assertEquals(java.util.List.of(name), store.configuredVideoSourceNames());
        AiSkills.Invocation before = AiSkills.match(AiSkills.Entry.CONTACT, "/镜头动画 海边", store);
        var openReview = store.previewCustomService(before);
        var approved = store.approveCustomService(store.previewCustomService(before));
        assertNotNull(store.saveConversationDraft(draft, first, null).pending());
        SkillStore.Detail pending = store.configureCustomService(store.detail(name), firstEndpoint, pendingKey, false,
                java.util.Map.of("model", "pending-model", "duration", "8"));
        assertTrue(pending.canReplace());
        var catalog = new File(root, "catalog.json").toPath();
        String beforeSwap = Files.readString(catalog);
        assertThrows(IllegalStateException.class, () -> store.replaceVideoContract(active, next));
        assertThrows(IllegalArgumentException.class, () -> store.replaceVideoContract(pending, SkillVideoFacts.empty()));
        var leakedUrl = assertThrows(SkillCustomService.Failure.class, () -> store.replaceVideoContract(pending,
                next.withForm(java.util.Map.of("submitEndpoint", secondEndpoint + "/" + priorKey))));
        assertFalse(leakedUrl.getMessage().contains(priorKey));
        var leakedLabel = assertThrows(IllegalArgumentException.class, () -> store.replaceVideoContract(pending,
                next.withForm(java.util.Map.of("label", pendingKey))));
        assertFalse(leakedLabel.getMessage().contains(pendingKey));
        assertEquals(beforeSwap, Files.readString(catalog));

        SkillStore.Detail candidate = store.replaceVideoContract(pending, next);
        assertTrue(candidate.pending());
        assertFalse(candidate.canReplace());
        assertEquals(active.current(), candidate.current());
        assertEquals(name, candidate.name());
        assertEquals(pending.manifest().instructions(), candidate.manifest().instructions());
        assertFalse(candidate.manifestValues().containsKey("duration"));
        assertFalse(candidate.manifestValues().containsKey("model"));
        assertNull(candidate.customConfiguration());
        assertEquals(secondEndpoint, candidate.manifest().http().suggestedEndpoint());
        assertEquals(firstEndpoint, store.previewCustomService(before).endpoint());
        var currentCatalog = new JSONObject(Files.readString(catalog));
        assertTrue(currentCatalog.getJSONObject("drafts").getJSONObject(name).isNull("customBinding"));
        assertEquals(new JSONObject(beforeSwap).getJSONObject("skills").getJSONObject(name).toString(),
                currentCatalog.getJSONObject("skills").getJSONObject(name).toString());
        assertThrows(IllegalStateException.class, () -> store.activateReplacement(pending));

        SkillStore reopened = videoStore(root);
        assertThrows(IllegalArgumentException.class, () -> reopened.configureCustomService(reopened.detail(name),
                "https://third.example.com/v1/submit", ""));
        assertEquals(candidate.deleteTarget(), reopened.detail(name).deleteTarget());
        SkillStore.Detail configured = reopened.configureCustomService(reopened.detail(name), secondEndpoint, "", false,
                java.util.Map.of("model", "second-model", "duration", "8"));
        assertFalse(configured.customConfiguration().hasCredential());
        assertTrue(configured.canReplace());
        assertEquals(firstEndpoint, reopened.previewCustomService(before).endpoint());
        SkillStore.Detail published = reopened.activateReplacement(configured);
        assertEquals(2, published.current().version());
        assertEquals(secondEndpoint, published.customConfiguration().endpoint());
        assertThrows(IllegalStateException.class, () -> reopened.approveCustomService(openReview));
        assertThrows(IllegalStateException.class,
                () -> reopened.executeCustomService(before, approved, Long.MAX_VALUE, null));
        AiSkills.Invocation call = AiSkills.match(AiSkills.Entry.WORK, "/镜头动画 海边", reopened);
        var review = reopened.previewCustomService(call);
        assertEquals(secondEndpoint, review.endpoint());
        assertEquals("海边", new JSONObject(review.payload()).getString("scene"));
        assertEquals("second-model", new JSONObject(review.payload()).getString("model"));
        assertEquals(java.util.List.of(name), reopened.configuredVideoSourceNames());
    }

    @Test public void replacingDirectMappingRequiresASeparateBindingAndConfirmation() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        JSONObject manifest = new JSONObject("""
                {"schemaVersion":4,"name":"检查接口","description":"提取文字","documentation":"输入原稿",
                 "instructions":"检查格式","capabilityId":"custom_service",
                 "configFields":[{"key":"style","label":"风格","type":"text","required":true}],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"},
                 "http":{"version":1,"label":"文本服务","suggestedEndpoint":"https://first.example.com/v1/text",
                         "request":{"text":"argument","style":"config.style"},"response":"text"}}
                """);
        String name = "检查接口";
        var authored = SkillStore.validateAuthoredDraft(manifest.toString());
        assertNotNull(store.saveConversationDraft(authored, null, null).pending());
        String endpoint = "https://first.example.com/v1/text";
        SkillStore.Detail installed = store.configureCustomService(store.detail(name), endpoint, "fixture-first-text-key",
                false, java.util.Map.of("style", "简洁"));
        AiSkills.Invocation captured = AiSkills.match(AiSkills.Entry.WORK, "/检查接口 原稿", store);
        var oldApproval = store.approveCustomService(store.previewCustomService(captured));
        var catalog = new File(root, "catalog.json").toPath();
        String before = Files.readString(catalog);
        assertThrows(IllegalArgumentException.class, () -> store.replaceDirectContract(installed,
                java.util.Map.of("prompt", "config.absent"), "reply"));
        assertEquals(before, Files.readString(catalog));

        SkillStore.Detail candidate = store.replaceDirectContract(installed,
                java.util.Map.of("prompt", "argument", "tone", "config.style"), "reply");
        assertTrue(candidate.pending());
        assertFalse(candidate.canReplace());
        assertEquals("", candidate.manifest().http().suggestedEndpoint());
        assertEquals("简洁", candidate.manifestValues().get("style"));
        assertNull(candidate.customConfiguration());
        assertEquals(endpoint, store.previewCustomService(captured).endpoint());
        assertThrows(IllegalStateException.class, () -> store.replaceDirectContract(installed,
                java.util.Map.of("prompt", "argument"), "reply"));
        SkillStore.Detail ready = store.configureCustomService(candidate, endpoint, "");
        assertTrue(ready.canReplace());
        assertFalse(ready.customConfiguration().hasCredential());
        store.activateReplacement(ready);
        assertThrows(IllegalStateException.class,
                () -> store.executeCustomService(captured, oldApproval, Long.MAX_VALUE, null));
        var replacement = store.previewCustomService(AiSkills.match(AiSkills.Entry.CONTACT, "/检查接口 新稿", store));
        assertEquals("新稿", new JSONObject(replacement.payload()).getString("prompt"));
        assertEquals("简洁", new JSONObject(replacement.payload()).getString("tone"));
        assertTrue(store.configuredVideoSourceNames().isEmpty());
    }

    @Test public void unsupportedVideoReplacementCannotDisplaceAnInstalledSource() throws Exception {
        SkillStore store = videoStore(temp.newFolder());
        String name = "镜头动画";
        var draft = SkillStore.validateAuthoredDraft(videoDraft(name).toString());
        SkillVideoFacts first = switchVideoFacts("first.example.com", "prompt", "旧型号");
        assertNotNull(store.saveConversationDraft(draft, first, null).pending());
        SkillStore.Detail installed = store.configureCustomService(store.detail(name), "https://first.example.com/v1/submit",
                "", false, java.util.Map.of("model", "first", "duration", "8"));
        SkillVideoFacts unsupported = switchVideoFacts("second.example.com", "scene", "新型号")
                .withForm(java.util.Map.of("submitEncoding", "multipart/form-data"));
        SkillStore.Detail staged = store.replaceVideoContract(installed, unsupported);
        assertTrue(staged.pending());
        assertNull(staged.manifest().http());
        assertNull(staged.customConfiguration());
        assertTrue(staged.videoMissing().stream().anyMatch(reason -> reason.contains("multipart")));
        assertEquals(installed.current(), staged.current());
        assertEquals(java.util.List.of(name), store.configuredVideoSourceNames());
        store.setSkillEnabled(name, false);
        assertTrue(store.configuredVideoSourceNames().isEmpty());
        assertThrows(IllegalStateException.class, () -> store.videoFactsForSkill(name));
    }

    private static SkillVideoFacts switchVideoFacts(String host, String prompt, String modelLabel) {
        return SkillVideoFacts.parse("""
                提交: POST https://%s/v1/submit
                提示词字段: %s
                提交响应ID: id
                必填文本字段: model %s
                必填整数字段: duration 时长
                查询: GET /v1/jobs/{id}
                查询响应ID: id
                状态字段: status
                状态映射: done=completed
                下载: GET /v1/jobs/{id}/video
                """.formatted(host, prompt, modelLabel));
    }

    @Test public void typedConversationPatchOverridesOnlyTargetedSavedValuesAfterExplicitSave() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        JSONObject raw = new JSONObject("""
                {"schemaVersion":4,"name":"文章润色","description":"润色文字","documentation":"输入文字",
                 "instructions":"改善表达","capabilityId":"text_model",
                 "configFields":[
                   {"key":"tone","label":"风格","type":"enum","options":["正式","温和"],"required":true,"default":"正式"},
                   {"key":"limit","label":"字数","type":"integer","required":true,"default":80,"min":10,"max":160}
                 ],"inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """);
        SkillStore.DraftPreview proposal = SkillStore.prepareModelAuthoredDraft(raw.toString());
        assertEquals(1, store.saveConversationDraft(proposal, null, null).installed().version());
        assertNotNull(store.saveConversationDraft(proposal, null, null).pending());
        SkillStore.Detail entered = store.saveManifestValues(store.detail("文章润色"),
                java.util.Map.of("tone", "温和", "limit", "120"));
        assertEquals("温和", entered.manifestValues().get("tone"));
        SkillStore.DraftPreview patched = SkillStore.applyConversationFields(proposal,
                java.util.Map.of("config.tone", "正式", "description", "调整后的说明", "displayName", "文案润色"));
        assertEquals("正式", patched.publicFields().get("config.tone"));
        assertEquals("调整后的说明", patched.description());
        assertEquals("温和", store.detail("文章润色").manifestValues().get("tone"));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.applyConversationFields(proposal,
                java.util.Map.of("config.limit", "90")));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.applyConversationFields(proposal,
                java.util.Map.of("config.limit", 200)));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.applyConversationFields(proposal,
                java.util.Map.of("config.unknown", "x")));
        SkillStore.SaveResult staged = store.saveConversationDraft(patched, null, entered.draftId());
        assertNotNull(staged.pending());
        SkillStore.Detail completed = videoStore(root).detail("文章润色");
        assertEquals("正式", completed.manifestValues().get("tone"));
        assertEquals(120, completed.manifestValues().get("limit"));
        assertEquals("文案润色", completed.manifest().displayName());
        assertEquals(1, completed.current().version());
    }

    @Test public void verifiedHttpAndImagePrefillAreVisibleWithoutConfiguringAnyBinding() throws Exception {
        File root = temp.newFolder();
        SkillStore store = videoStore(root);
        JSONObject direct = new JSONObject("""
                {"schemaVersion":4,"name":"文字接口","description":"提取文字","documentation":"输入文字",
                 "instructions":"检查格式","capabilityId":"custom_service","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"},
                 "http":{"version":1,"label":"文本接口","suggestedEndpoint":"https://guessed.example.com/v1/text",
                         "request":{"input":"argument"},"response":"text"},
                 "servicePrefill":{"endpoint":"https://guessed.example.com/v1/text"},"draftValues":{"invented":"x"}}
                """);
        SkillStore.DraftPreview safe = SkillStore.prepareModelAuthoredDraft(direct.toString());
        assertTrue(safe.publicFields().isEmpty());
        assertFalse(safe.definition().contains("guessed.example.com"));
        String endpoint = "https://verified.example.com/v1/text";
        var typed = SkillStore.applyConversationFields(safe, java.util.Map.of("service.endpoint", endpoint));
        assertEquals(endpoint, typed.publicFields().get("service.endpoint"));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.applyConversationFields(safe,
                java.util.Map.of("service.model", "fake-model")));
        assertThrows(IllegalArgumentException.class, () -> SkillStore.applyConversationFields(safe,
                java.util.Map.of("service.endpoint", "https://verified.example.com/v1/text?key=private")));
        assertNotNull(store.saveConversationDraft(typed, null, null).pending());
        SkillStore.Detail pending = videoStore(root).detail("文字接口");
        assertEquals(endpoint, pending.manifest().servicePrefill().endpoint());
        assertNull(pending.customConfiguration());
        assertThrows(AiSkills.CommandUnavailable.class, () -> AiSkills.match(AiSkills.Entry.WORK, "/文字接口 原稿", store));

        JSONObject image = new JSONObject("""
                {"schemaVersion":4,"name":"封面图片","description":"生成封面","documentation":"输入场景",
                 "instructions":"生成图片","capabilityId":"image_generation","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"image","trial":{"input":"海边","expectedType":"image"}}
                """);
        String imageEndpoint = "https://images.example.com/v1/images/generations";
        var imageFields = SkillStore.applyConversationFields(SkillStore.prepareModelAuthoredDraft(image.toString()),
                java.util.Map.of("service.endpoint", imageEndpoint, "service.model", "image-model-1"));
        assertEquals("image-model-1", imageFields.publicFields().get("service.model"));
        assertNotNull(store.saveConversationDraft(imageFields, null, null).pending());
        SkillStore.Detail imagePending = videoStore(root).detail("封面图片");
        assertEquals(imageEndpoint, imagePending.manifest().servicePrefill().endpoint());
        assertEquals("image-model-1", imagePending.manifest().servicePrefill().model());
        assertNull(imagePending.service());
        assertNull(imagePending.current());
    }

    private static SkillStore videoStore(File root) {
        return videoStore(root, (endpoint, bearer) -> {
            throw new AssertionError("Model listing must be explicitly requested");
        });
    }

    private static SkillStore videoStore(File root, SkillStore.VideoModelQuery videoModels) {
        return observedStore(root, videoModels, () -> {
            throw new AssertionError("Declaration and local configuration must not use the network");
        });
    }

    public static SkillStore authoringStore(File root, java.util.concurrent.atomic.AtomicInteger serviceCalls) {
        return observedStore(root, (endpoint, bearer) -> {
            serviceCalls.incrementAndGet();
            throw new AssertionError("Skill authoring must not query models");
        }, () -> serviceCalls.incrementAndGet());
    }

    private static SkillStore observedStore(File root, SkillStore.VideoModelQuery videoModels, Runnable onServiceCall) {
        var vault = new com.douai.module.ai.AiCredentialVault(
                () -> new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
        var services = new SkillServices(root, vault, (endpoint, bearer, body, deadline, output) -> {
            onServiceCall.run();
            throw new AssertionError("Unexpected image or text service call");
        });
        var customService = new SkillCustomService(vault, (endpoint, bearer, body, deadline, limit, admission) -> {
            onServiceCall.run();
            throw new AssertionError("Unexpected custom service call");
        }, System::nanoTime);
        return new SkillStore(root, services, null, customService, videoModels);
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

    @Test public void queuedVideoKeepsOnlyItsOwnSealedCredentialAcrossAConfigurationChange() throws Exception {
        File root = temp.newFolder();
        File journal = temp.newFolder();
        SkillStore store = videoStore(root);
        String name = "镜头动画";
        saveVideoDraft(store, videoDraft(name));
        store.saveVideoDeclaration(store.detail(name), videoHttp().toString());
        String firstKey = "fixture-old-video-credential";
        String secondKey = "fixture-new-video-credential";
        String firstOrigin = "https://first.example.com";
        String secondOrigin = "https://second.example.com";
        store.configureCustomService(store.detail(name), firstOrigin + "/v1/submit", firstKey);
        var sent = new java.util.ArrayList<String>();
        SkillVideoJobs.Transport transport = (method, media, endpoint, bearer, body, deadline, admission) -> {
            admission.check();
            boolean first = endpoint.startsWith(firstOrigin + "/");
            String id = first ? "job_a" : "job_b";
            assertEquals(first ? firstKey : secondKey, bearer);
            sent.add(method.name() + " " + endpoint);
            byte[] response;
            if (endpoint.endsWith("/v1/submit")) {
                assertEquals(SkillHttpsTransport.Method.POST, method);
                response = ("{\"job_id\":\"" + id + "\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            } else {
                if (first) {
                    assertEquals(SkillHttpsTransport.Method.GET, method);
                    assertNull(body);
                } else {
                    assertEquals(SkillHttpsTransport.Method.POST, method);
                    assertEquals("{\"job\":\"job_b\"}", new String(body, java.nio.charset.StandardCharsets.UTF_8));
                }
                response = ("{\"id\":\"" + id + "\",\"state\":\"running\"}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            }
            return new SkillHttpsTransport.Response(200, "application/json", response);
        };
        var clock = new java.util.concurrent.atomic.AtomicLong(1000);
        SkillVideoJobs jobs = new SkillVideoJobs(journal, clock::get, transport);
        SkillVideoJobs.Scope a = new SkillVideoJobs.Scope(AiSkills.Entry.WORK, "a".repeat(64));
        SkillVideoJobs.Scope b = new SkillVideoJobs.Scope(AiSkills.Entry.CONTACT, "a".repeat(64));
        var callA = AiSkills.match(AiSkills.Entry.WORK, "/镜头动画 海边", store);
        UUID idA = UUID.randomUUID();
        store.executeCustomService(callA, store.approveCustomService(store.previewCustomService(callA)), Long.MAX_VALUE,
                new SkillStore.VideoTurn(jobs, a, jobs.generation(a), idA));
        SkillVideoFacts secondContract = SkillVideoFacts.parseSupplement("""
                提交: POST https://second.example.com/v1/submit
                提交请求编码: application/json
                提示词字段: prompt
                提交响应ID: job_id
                查询: POST /v1/jobs/status
                查询请求ID字段: job
                查询响应ID: id
                状态字段: state
                状态映射: success=completed,running=running
                下载: GET /v1/jobs/{id}/content
                """);
        SkillStore.Detail pending = store.replaceVideoContract(store.detail(name), secondContract);
        SkillStore.Detail next = store.configureCustomService(pending, secondOrigin + "/v1/submit", secondKey);
        store.activateReplacement(next);

        SkillStore reopened = videoStore(root);
        SkillVideoJobs restoredJobs = new SkillVideoJobs(journal, clock::get, transport);
        SkillVideoJobs.Job running = restoredJobs.list(a).get(0);
        var callB = AiSkills.match(AiSkills.Entry.CONTACT, "/镜头动画 山谷", reopened);
        UUID idB = UUID.randomUUID();
        reopened.executeCustomService(callB, reopened.approveCustomService(reopened.previewCustomService(callB)), Long.MAX_VALUE,
                new SkillStore.VideoTurn(restoredJobs, b, restoredJobs.generation(b), idB));
        assertThrows(SkillVideoJobs.Failure.class, () -> reopened.openVideoAccess(restoredJobs, b, running));
        SkillVideoJobs.Job newer = restoredJobs.list(b).get(0);
        assertEquals(SkillVideoJobs.State.RUNNING, restoredJobs.refresh(a, idA,
                reopened.openVideoAccess(restoredJobs, a, running), Long.MAX_VALUE).state());
        assertEquals(SkillVideoJobs.State.RUNNING, restoredJobs.refresh(b, idB,
                reopened.openVideoAccess(restoredJobs, b, newer), Long.MAX_VALUE).state());
        assertEquals(java.util.List.of("POST " + firstOrigin + "/v1/submit", "POST " + secondOrigin + "/v1/submit",
                "GET " + firstOrigin + "/v1/jobs/job_a", "POST " + secondOrigin + "/v1/jobs/status"), sent);
        try (var files = Files.list(journal.toPath())) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                String bytes = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.ISO_8859_1);
                assertFalse(bytes.contains(firstKey) || bytes.contains(secondKey));
            }
        }
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
