package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SkillEditingTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void installedTextEditsWaitForExplicitActivationAndInvalidateTheOldInvocation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(authored("周报", "text_model", "text", "用中文", 12));
        var original = AiSkills.match(AiSkills.Entry.CONTACT, "/周报 任务进度", fixture.store);
        var current = fixture.store.detail("周报");
        var editor = fixture.store.editTarget(current.deleteTarget());
        assertEquals("初始说明", editor.documentation());
        assertEquals("用中文", editor.instruction());
        assertTrue(editor.instructionEditable());

        var pending = fixture.store.saveEdit(editor, "新的说明标题", "新的使用说明", "用英文");
        assertTrue(pending.canReplace());
        assertEquals(1, fixture.store.list().get(0).version());
        assertEquals("用中文", runText(fixture.store, "周报", "任务进度"));
        JSONObject edited = new JSONObject(fixture.store.pendingDefinition("周报"));
        assertEquals(12, edited.getJSONObject("execution").getInt("maxInput"));
        assertEquals("新的使用说明", edited.getString("documentation"));

        fixture.store.activateReplacement(pending);
        var active = fixture.store.editTarget(fixture.store.detail("周报").deleteTarget());
        assertEquals("新的说明标题", active.description());
        assertEquals("用英文", runText(fixture.store, "周报", "任务进度"));
        assertEquals(1, fixture.store.detail("周报").deleteTarget().versionCount());
        assertThrows(IllegalStateException.class,
                () -> AiSkills.executeResult(original, fixture.store, (instruction, ignored) -> instruction));
    }

    @Test public void editingCurrentPendingDraftPreservesItsFieldsAndFencesStaleSave() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(authored("周报", "text_model", "text", "当前版本", 12));
        fixture.store.saveAuthoredDraft(authored("周报", "text_model", "text", "待启用版本", 18));
        var pending = fixture.store.detail("周报");
        var editor = fixture.store.editTarget(pending.deleteTarget());
        assertEquals("待启用版本", editor.instruction());

        var edited = fixture.store.saveEdit(editor, "新版标题", "新说明", "新的待启用版本");
        assertNotEquals(pending.draftId(), edited.draftId());
        assertThrows(IllegalStateException.class, () ->
                fixture.store.saveEdit(editor, "旧编辑", "旧说明", "不应覆盖"));
        assertEquals(18, new JSONObject(fixture.store.pendingDefinition("周报"))
                .getJSONObject("execution").getInt("maxInput"));
        assertEquals("当前版本", runText(fixture.store, "周报", "进度"));
        assertThrows(IllegalStateException.class, () -> fixture.store.editTarget(pending.deleteTarget()));
    }

    @Test public void legacyV1SummaryCanEditProseWithoutChangingHowItRuns() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.installGenerated(SkillStore.mockDraft("摘要"));
        File folder = new File(fixture.directory, "versions").listFiles()[0];
        var execution = new File(folder, "execution.json").toPath();
        var v1 = new JSONObject(Files.readString(execution)).put("schemaVersion", 1);
        v1.remove("outputType");
        Files.writeString(execution, v1.toString());
        var editor = fixture.store.editTarget(fixture.store.detail("模拟技能").deleteTarget());
        assertFalse(editor.instructionEditable());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.store.saveEdit(editor, "摘要", "摘要说明", "不应有模型指令"));
        var pending = fixture.store.saveEdit(editor, "摘要", "摘要说明", "");
        assertEquals(1, new JSONObject(fixture.store.pendingDefinition("模拟技能")).getInt("schemaVersion"));
        fixture.store.activateReplacement(pending);
        var call = AiSkills.match(AiSkills.Entry.WORK, "/模拟技能 头句。次句。其余。", fixture.store);
        assertEquals("头句。次句。", AiSkills.execute(call, fixture.store));
    }

    @Test public void pureDraftEditsPreserveItsOwnPartialImageConfiguration() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(authored("生图", "service", "image", null, 100));
        var first = fixture.store.detail("生图");
        var configured = fixture.store.saveConfiguration("生图", first.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://images.example.test/generate", "fixture", 0, "");
        var editor = fixture.store.editTarget(configured.deleteTarget());
        var saved = fixture.store.saveEdit(editor, "仅编辑说明", "新的说明正文", "");
        assertTrue(saved.pending());
        assertTrue(fixture.store.list().isEmpty());
        assertEquals(configured.service(), saved.service());
        assertNotEquals(configured.draftId(), saved.draftId());
        assertThrows(IllegalStateException.class, () ->
                fixture.store.retryConfiguration("生图", configured.draftId()));
    }

    @Test public void pureDraftSaveDoesNotPublishEvenIfAServiceBecomesReadyDuringEditing() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(authored("生图", "service", "image", null, 100));
        var editor = fixture.store.editTarget(fixture.store.detail("生图").deleteTarget());
        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);

        var saved = fixture.store.saveEdit(editor, "描述", "说明", "");
        assertTrue(saved.pending());
        assertTrue(fixture.store.list().isEmpty());
        assertFalse(fixture.store.retryConfiguration("生图", saved.draftId()).pending());
    }

    @Test public void editingInstalledImageRetainsItsBindingUntilASeparateConfigurationIsActivated() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(authored("生图", "service", "image", null, 100));
        var draft = fixture.store.detail("生图");
        var initial = fixture.store.saveConfiguration("生图", draft.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://images.example.test/initial", "image-model-v1", 0, "fixture-first-image-key");
        var installed = fixture.store.authorizeConfiguration("生图", initial.draftId(), initial.service());
        var original = AiSkills.match(AiSkills.Entry.CONTACT, "/生图 橘猫", fixture.store);
        var editor = fixture.store.editTarget(installed.deleteTarget());

        var pending = fixture.store.saveEdit(editor, "更改简介", "画图说明", null);
        assertEquals(initial.service(), pending.service());
        assertTrue(pending.canReplace());
        var unchanged = fixture.store.saveConfiguration("生图", pending.draftId(), initial.service().adapter(),
                initial.service().endpoint(), initial.service().modelName(), initial.service().costUnits(), "");
        assertEquals(initial.service(), unchanged.service());
        assertTrue(unchanged.canReplace());
        var newConfig = fixture.store.saveConfiguration("生图", unchanged.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://images.example.test/replacement", "image-model-v2", 0, "fixture-second-image-key");
        assertNotEquals(initial.service().id(), newConfig.service().id());
        assertTrue(fixture.services.hasCredential(initial.service()));
        assertTrue(fixture.services.isAuthorized(initial.service()));
        var replacement = fixture.store.authorizeConfiguration("生图", newConfig.draftId(), newConfig.service());
        assertTrue(replacement.canReplace());
        assertEquals(initial.service(), replacement.currentService());
        fixture.store.activateReplacement(replacement);
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        SkillStore.Detail current = reopened.detail("生图");
        assertEquals(newConfig.service(), current.currentService());
        assertTrue(fixture.services.hasCredential(current.currentService()));
        assertTrue(fixture.services.isAuthorized(current.currentService()));
        assertEquals(1, current.deleteTarget().versionCount());
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(original, reopened));
    }

    @Test public void revocationAfterReviewDoesNotReportAnEditedImageSkillAsActivated() throws Exception {
        Fixture fixture = new Fixture();
        var service = new SkillServices.Service("retained-image", "图片服务",
                "https://images.example.test/initial", 0, SkillResult.Type.IMAGE);
        fixture.services.register(service, "");
        fixture.services.authorize(service, 0);
        fixture.store.saveAuthoredDraft(authored("生图", "service", "image", null, 100));
        var editor = fixture.store.editTarget(fixture.store.detail("生图").deleteTarget());
        var confirmed = fixture.store.saveEdit(editor, "新简介", "新说明", null);
        assertTrue(confirmed.canReplace());
        assertEquals(service, confirmed.service());

        fixture.services.revoke(service);
        assertThrows(IllegalStateException.class, () -> fixture.store.activateReplacement(confirmed));
        var detail = fixture.store.detail("生图");
        assertEquals(1, detail.current().version());
        assertEquals(service, detail.currentService());
        assertEquals(confirmed.draftId(), detail.draftId());
        assertFalse(detail.canReplace());
    }

    @Test public void editWithRevokedImageBindingDoesNotSwitchToAnAuthorizedAlternative() throws Exception {
        Fixture fixture = new Fixture();
        var old = new SkillServices.Service("art-old", "原始", "https://images.example.test/initial", 0,
                SkillResult.Type.IMAGE);
        fixture.services.register(old, "");
        fixture.services.authorize(old, 0);
        fixture.store.saveAuthoredDraft(authored("生图", "service", "image", null, 100));
        var editor = fixture.store.editTarget(fixture.store.detail("生图").deleteTarget());
        fixture.services.revoke(old);
        var replacement = new SkillServices.Service("art-new", "备用", "https://images.example.test/replacement", 0,
                SkillResult.Type.IMAGE);
        fixture.services.register(replacement, "");
        fixture.services.authorize(replacement, 0);

        var pending = fixture.store.saveEdit(editor, "更改简介", "画图说明", "");
        assertEquals(old, pending.service());
        assertFalse(pending.canReplace());
        assertEquals(1, fixture.store.list().get(0).version());
        String definition = fixture.store.pendingDefinition("生图");
        var managerPreview = fixture.store.previewGenerated(definition);
        assertEquals(old.id(), managerPreview.serviceId());
        assertNotNull(fixture.store.connectPending(definition, managerPreview, pending.draftId()).pending());
        assertFalse(fixture.store.retryConfiguration("生图", pending.draftId()).canReplace());
    }

    private static String runText(SkillStore store, String name, String input) throws Exception {
        var call = AiSkills.match(AiSkills.Entry.CONTACT, "/" + name + " " + input, store);
        return ((SkillResult.Text) AiSkills.executeResult(call, store, (instruction, ignored) -> instruction)).text();
    }

    private static SkillStore.DraftPreview authored(String name, String kind, String output, String instruction,
                                                     int maxInput) throws Exception {
        JSONObject run = new JSONObject().put("kind", kind).put("outputType", output).put("maxInput", maxInput);
        if (instruction != null) run.put("instruction", instruction);
        return SkillStore.validateAuthoredDraft(new JSONObject().put("schemaVersion", 3).put("name", name)
                .put("description", "技能简介").put("documentation", "初始说明").put("execution", run)
                .put("trial", new JSONObject().put("input", "示例").put("expectedType", output)).toString());
    }

    private final class Fixture {
        final File directory = temp.newFolder();
        final SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, type) -> {
                    throw new AssertionError("Skill edit, trial, and activation must stay offline");
                });
        final SkillStore store = new SkillStore(directory, services);

        Fixture() throws Exception {}
    }
}
