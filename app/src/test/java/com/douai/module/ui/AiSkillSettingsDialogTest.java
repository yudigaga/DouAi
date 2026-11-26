package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.skills.AiSkills;
import com.douai.module.ai.skills.SkillAuthoring;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.feature.ChatCompletionClient;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiSkillSettingsDialogTest {
    private Activity activity;
    private AiSkillSettingsDialog manager;
    private SkillStore store;
    private SkillServices services;

    @Before public void openManager() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = SkillStore.forContext(activity);
        services = SkillServices.forContext(activity);
        reopenManager();
    }

    private void reopenManager() {
        if (manager != null) {
            manager.close();
            manager.dialog().dismiss();
        }
        manager = new AiSkillSettingsDialog(activity, new HostSettingsStore(activity), false,
                this::present, new ImmediateExecutor());
        manager.dialog().show();
    }

    @After public void closeManager() {
        if (manager != null) {
            manager.close();
            manager.dialog().dismiss();
        }
        if (activity != null) activity.finish();
    }

    @Test public void skillsPageLoadsWithoutTheBottomServiceSectionOrActions() throws Exception {
        SkillServices.Service existing = new SkillServices.Service("fixture-existing", "存量图片服务",
                "https://images.example.test/generate", 0, SkillResult.Type.IMAGE);
        services.register(existing, "");
        services.authorize(existing, 0);
        reopenManager();

        assertTrue(manager.dialog().isShowing());
        assertNotNull(view("skills.generate.model"));
        assertNotNull(view("skills.status"));
        View root = manager.dialog().getWindow().getDecorView();
        assertFalse(visibleText(root).contains("技能服务"));
        assertNull(root.findViewWithTag("skills.service.register"));
        assertNull(root.findViewWithTag("skills.service.fixture-existing"));
        assertNull(root.findViewWithTag("skills.service." + SkillServices.MOCK_IMAGE + ".authorize"));
        assertNull(root.findViewWithTag("skills.generate.shared_services"));
        assertFalse(visibleText(root).contains("共享接口设置"));
        assertEquals(existing, services.find(existing.id()));
        assertTrue(services.isAuthorized(existing));
    }

    @Test public void oneWordImageRequirementStaysPendingUntilExplicitConnectAfterAuthorization() throws Exception {
        enterAndGenerate("生图", "skills.generate.model");
        assertEquals(1, store.pending().size());
        assertEquals(SkillResult.Type.IMAGE, store.pending().get(0).outputType());
        assertTrue(store.list().isEmpty());
        String name = store.pending().get(0).name();
        assertTrue(visibleText(view(skillRow(name))).contains("草稿 · 图片 · 待配置"));

        services.authorize(services.find(SkillServices.MOCK_IMAGE), 0);
        assertTrue(store.list().isEmpty());

        reopenManager();
        assertTrue(store.list().isEmpty());
        AlertDialog detail = skillDetail(skillRow(name));
        assertTrue(store.list().isEmpty());
        assertEquals(name, store.pending().get(0).name());
        assertTrue(dialogView(detail, "skills.detail.retry").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.pending().isEmpty());
        assertEquals(name, store.list().get(0).name());
        assertTrue(((TextView) dialogView(detail, "skills.detail.service")).getText().toString()
                .contains(services.find(SkillServices.MOCK_IMAGE).label()));
        detail.dismiss();
    }

    @Test public void directHttpImageOnlyOffersGenericSettingsAndCanEditTypedMappings() throws Exception {
        String name = "接口图片";
        String manifest = """
                {"schemaVersion":4,"name":"接口图片","description":"通过接口返回图片","documentation":"输入提示词。",
                 "instructions":"根据输入生成图片","capabilityId":"custom_service","configFields":[
                     {"key":"size","label":"尺寸","type":"integer","required":false,"min":1,"max":100}],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"image","trial":{"input":"样例","expectedType":"image"},
                 "http":{"version":1,"label":"图片接口","request":{"prompt":"argument"},"response":"image"}}
                """;
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(manifest)).pending());
        SkillStore.Detail initial = store.detail(name);
        assertThrows(IllegalStateException.class, () -> store.saveConfiguration(name, initial.draftId(),
                SkillServices.Adapter.MOCK_IMAGE, "", "", 0, ""));
        assertEquals(initial.deleteTarget(), store.detail(name).deleteTarget());
        reopenManager();
        AlertDialog detail = skillDetail(skillRow(name));
        assertNotNull(dialogView(detail, "skills.detail.custom.endpoint"));
        assertNull(dialogView(detail, "skills.detail.adapter"));
        assertNull(dialogView(detail, "skills.detail.retry"));
        assertTrue(dialogView(detail, "skills.detail.direct_contract.replace").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        ((EditText) dialogView(editor, "skills.detail.direct_contract.request.0")).setText("caption");
        assertTrue(dialogView(editor, "skills.detail.direct_contract.add").performClick());
        ((EditText) dialogView(editor, "skills.detail.direct_contract.request.1")).setText("width");
        ((Spinner) dialogView(editor, "skills.detail.direct_contract.source.1")).setSelection(1);
        ((EditText) dialogView(editor, "skills.detail.direct_contract.response")).setText("encoded");
        confirm(editor);

        SkillStore.Detail pending = store.detail(name);
        assertTrue(pending.pending());
        assertEquals(SkillResult.Type.IMAGE, pending.outputType());
        assertEquals(java.util.Map.of("caption", "argument", "width", "config.size"), pending.manifest().http().request());
        assertEquals("encoded", pending.manifest().http().responseField());
        assertNull(pending.customConfiguration());
        assertTrue(store.list().isEmpty());
    }

    @Test public void videoReplacementUsesTheNewFactsWhileCurrentBindingStaysActive() throws Exception {
        String name = "接口视频";
        String definition = """
                {"schemaVersion":4,"name":"接口视频","description":"通过接口生成视频","documentation":"输入提示词。",
                 "instructions":"使用原任务 ID 查询","capabilityId":"video_task","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"样例","expectedType":"video"}}
                """;
        String firstEndpoint = "https://first-video.example.com/v1/jobs";
        String revisedEndpoint = "https://second-video.example.com/v2/tasks";
        var prior = com.douai.module.ai.skills.SkillVideoFacts.parse(videoProviderNotes(firstEndpoint, "/v1/jobs/{id}"));
        store.saveAuthoredVideoDraft(SkillStore.prepareAuthoredVideoDraft(definition, prior), prior);
        SkillStore.Detail active = store.configureCustomService(store.detail(name), firstEndpoint, "");
        assertNotNull(active.current());
        reopenManager();
        AlertDialog detail = skillDetail(skillRow(name));
        assertTrue(dialogView(detail, "skills.detail.video_contract.replace").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(((EditText) dialogView(editor, "skills.detail.video_facts.field.submitEndpoint")).getText().toString().isEmpty());
        ((EditText) dialogView(editor, "skills.detail.video_facts.input")).setText(videoProviderNotes(revisedEndpoint, "/v2/tasks/{id}"));
        confirm(editor);

        SkillStore.Detail changed = store.detail(name);
        assertTrue(changed.pending());
        assertEquals(active.current(), changed.current());
        assertNull(changed.customConfiguration());
        assertEquals(revisedEndpoint, changed.manifest().http().suggestedEndpoint());
        assertEquals("/v2/tasks/{id}", changed.manifest().http().video().statusPath());
        var currentCall = AiSkills.match(AiSkills.Entry.WORK, "/接口视频 样例", store);
        assertEquals(firstEndpoint, store.previewCustomService(currentCall).endpoint());
    }

    private static String videoProviderNotes(String endpoint, String status) {
        return """
                提交: POST %s
                提交请求编码: application/json
                提示词字段: prompt
                提交响应ID: task_id
                查询: GET %s
                查询响应ID: id
                状态字段: status
                状态映射: finished=completed
                下载: GET %s/content
                """.formatted(endpoint, status, status);
    }

    @Test public void sameNameInstalledAndPendingSkillsShareOneTruthfulSettingsRow() throws Exception {
        String name = "统一技能入口";
        installTextSkill(name, "保持段落结构");
        String pending = new JSONObject(SkillStore.mockDraft("生图")).put("name", name).toString();
        assertNotNull(store.saveGenerated(pending, null).pending());
        reopenManager();

        List<View> actions = new ArrayList<>();
        skillLinks(manager.dialog().getWindow().getDecorView(), "/" + name, actions);
        assertEquals("Each command should have one settings row", 1, actions.size());
        View combined = actions.get(0);
        assertEquals(skillRow(name), combined.getTag());
        String summary = visibleText(combined);
        assertTrue(summary.contains("当前 v1 · 文本 · 已启用"));
        assertTrue(summary.contains("草稿 · 图片 · 待配置"));
        assertFalse(summary.contains("回退"));
        assertFalse(summary.contains("恢复"));

        assertTrue(combined.performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("/" + name, ((TextView) dialogView(detail, "skills.detail.name")).getText().toString());
        assertNotNull(dialogView(detail, "skills.detail.adapter"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.summary")).contains("v1 · 文本输出 · 已启用"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.draft_summary")).contains("图片"));
        assertEquals("保持段落结构", activeInstruction(name));
        assertTrue(store.detail(name).pending());
        detail.dismiss();
    }

    @Test public void pendingListOpensTheExactSkillConfiguration() {
        enterAndGenerate("生图", "skills.generate.mock");
        String name = store.pending().get(0).name();
        assertTrue(view(skillRow(name)).performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView scopedName = detail.getWindow().getDecorView().findViewWithTag("skills.detail.name");
        assertNotNull(scopedName);
        assertEquals("/" + name, scopedName.getText().toString());
        assertNull(detail.getWindow().getDecorView().findViewWithTag("skills.service.register"));
        assertTrue(store.list().isEmpty());
        detail.dismiss();
    }

    @Test public void configurationMasksKeyAndRequiresAuthorizationToConnectMock() {
        enterAndGenerate("生图", "skills.generate.mock");
        String name = store.pending().get(0).name();
        assertTrue(view(skillRow(name)).performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = detail.getWindow().getDecorView();
        EditText key = form.findViewWithTag("skills.detail.key");
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertFalse(key.isSaveEnabled());
        assertNull(form.findViewWithTag("skills.detail.authorize"));

        ((Spinner) form.findViewWithTag("skills.detail.adapter")).setSelection(1);
        assertTrue(form.findViewWithTag("skills.detail.save").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.list().isEmpty());
        assertTrue(form.findViewWithTag("skills.detail.authorize").performClick());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.list().isEmpty());

        assertTrue(form.findViewWithTag("skills.detail.authorize").performClick());
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals(name, store.list().get(0).name());
        assertTrue(store.pending().isEmpty());
        detail.dismiss();
    }

    @Test public void regeneratingTheSameSkillRequiresConfirmationBeforeReplacingVersion() {
        enterAndGenerate("提取摘要", "skills.generate.mock");
        assertEquals(1, store.list().get(0).version());

        enterAndGenerate("提取摘要", "skills.generate.mock");
        assertEquals(1, store.list().get(0).version());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = review.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains("v1"));
        assertFalse(message.getText().toString().contains("回退"));
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(1, store.list().get(0).version());

        enterAndGenerate("提取摘要", "skills.generate.mock");
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals(2, store.list().get(0).version());
    }

    @Test @Config(shadows = PolishingModelShadow.class)
    public void selectedModelAuthorsAPolishingSkillThatCanBeEditedEnabledAndRun() throws Exception {
        String requirement = "把口语句子润色成简洁的正式表达";
        var provider = AiModelConfig.fromJson(new JSONObject().put("id", "fixture-polishing")
                .put("name", "离线润色模型").put("modelName", "fixture-text")
                .put("apiEndpoint", "http://no-network.invalid/v1").put("allowInsecureHttp", true));
        new AiModelSettings(List.of(provider), provider.id).save(new HostSettingsStore(activity));
        PolishingModelShadow.instruction = null;
        PolishingModelShadow.input = null;
        PolishingModelShadow.selectedId = null;
        enterAndGenerate(requirement, "skills.generate.model");
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView choices = chooser.getListView();
        assertNotNull(choices);
        assertTrue(choices.performItemClick(choices, 0, choices.getItemIdAtPosition(0)));
        ShadowLooper.idleMainLooper();

        assertEquals(provider.id, PolishingModelShadow.selectedId);
        assertEquals(SkillAuthoring.DESIGN_INSTRUCTION, PolishingModelShadow.instruction);
        JSONObject modelInput = new JSONObject(PolishingModelShadow.input);
        assertEquals(1, modelInput.length());
        assertEquals(requirement, modelInput.getString("requirement"));
        assertFalse(modelInput.has("registeredServices"));
        assertEquals("句子润色", store.list().get(0).name());
        assertEquals("保留事实，提高表达流畅度。", activeInstruction("句子润色"));

        AlertDialog detail = skillDetail(skillRow("句子润色"));
        AlertDialog editor = requestEdit(detail);
        assertEquals("保留事实，提高表达流畅度。", editField(editor, "instruction").getText().toString());
        editField(editor, "instruction").setText("改写为一句正式通知。");
        confirm(editor);
        assertTrue(store.detail("句子润色").canReplace());
        assertEquals("保留事实，提高表达流畅度。", activeInstruction("句子润色"));

        assertTrue(dialogView(detail, "skills.detail.replace").performClick());
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        var call = AiSkills.match(AiSkills.Entry.WORK, "/句子润色 通知发出去了", store);
        assertNotNull(call);
        assertEquals(new SkillResult.Text("通知已发布。"), AiSkills.executeResult(call, store, (instruction, input) -> {
            assertEquals("改写为一句正式通知。", instruction);
            assertEquals("通知发出去了", input);
            return "通知已发布。";
        }));
        detail.dismiss();
    }

    @Test public void cancellingThenConfirmingDeleteCoversOnlyTheNamedSkillAndDraft() throws Exception {
        enterAndGenerate("提取摘要", "skills.generate.mock");
        String name = store.list().get(0).name();
        enterAndGenerate("提取摘要", "skills.generate.mock");
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        String image = SkillStore.mockDraft("生图");
        assertNotNull(store.saveGenerated(new JSONObject(image).put("name", name).toString(), null).pending());
        assertNotNull(store.saveGenerated(new JSONObject(image).put("name", "另一技能").toString(), null).pending());

        reopenManager();
        AlertDialog detail = skillDetail(skillRow(name));
        AlertDialog review = requestDeletion(detail);
        String description = message(review);
        assertTrue(description.contains("/" + name + " 的本机配置和当前草稿"));
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(2, store.list().get(0).version());
        assertEquals(2, store.pending().size());

        confirm(requestDeletion(detail));
        assertTrue(store.list().isEmpty());
        assertEquals(1, store.pending().size());
        assertEquals("另一技能", store.pending().get(0).name());
        assertNull(manager.dialog().getWindow().getDecorView().findViewWithTag(skillRow(name)));
        assertTrue(detailStatus(detail).contains("已删除 /" + name));
        assertNull(detail.getWindow().getDecorView().findViewWithTag("skills.detail.delete"));
        detail.dismiss();
    }

    @Test public void thePendingDraftCanBeDeletedWithoutAnInstalledVersion() {
        enterAndGenerate("生图", "skills.generate.mock");
        String name = store.pending().get(0).name();
        String tag = skillRow(name);
        AlertDialog detail = skillDetail(tag);
        AlertDialog review = requestDeletion(detail);
        assertTrue(message(review).contains("/" + name + " 的本机草稿"));
        assertFalse(message(review).contains("本机配置"));
        confirm(review);

        assertTrue(store.pending().isEmpty());
        assertNull(manager.dialog().getWindow().getDecorView().findViewWithTag(tag));
        assertTrue(detailStatus(detail).contains("已删除 /" + name));
        detail.dismiss();
    }

    @Test public void staleDeleteConfirmationReloadsTheNewCurrentVersionWithoutDeletingIt() throws Exception {
        enterAndGenerate("提取摘要", "skills.generate.mock");
        String name = store.list().get(0).name();
        AlertDialog detail = skillDetail(skillRow(name));
        AlertDialog review = requestDeletion(detail);
        assertTrue(message(review).contains("/" + name + " 的本机配置"));

        String next = SkillStore.mockDraft("提取摘要");
        assertEquals(2, store.saveGenerated(next, store.previewGenerated(next)).installed().version());
        confirm(review);

        assertEquals(2, store.list().get(0).version());
        assertTrue(detailStatus(detail).contains("删除未完成"));
        assertTrue(message(requestDeletion(detail)).contains("/" + name + " 的本机配置"));
        detail.dismiss();
    }

    @Test public void staleDetailRetryCannotActivateAnIdenticalDraftRecreatedAfterDeletion() throws Exception {
        enterAndGenerate("生图", "skills.generate.mock");
        SkillStore.Pending original = store.pending().get(0);
        String name = original.name();
        String definition = store.pendingDefinition(name);
        SkillServices.Service mock = services.find(SkillServices.MOCK_IMAGE);
        AlertDialog staleDetail = skillDetail(skillRow(name));

        store.delete(store.detail(name).deleteTarget());
        SkillStore.Pending recreated = store.saveGenerated(definition, null).pending();
        assertNotNull(recreated);
        assertNotEquals(original.draftId(), recreated.draftId());
        services.authorize(mock, 0);
        assertTrue(dialogView(staleDetail, "skills.detail.retry").performClick());
        ShadowLooper.idleMainLooper();

        assertTrue(store.list().isEmpty());
        assertEquals(recreated.draftId(), store.pending().get(0).draftId());
        assertTrue(detailStatus(staleDetail).contains("重试失败"));
        staleDetail.dismiss();
    }

    @Test public void cancellingSkillEditKeepsTheInstalledIdentityAndDoesNotCreateADraft() throws Exception {
        String name = "整理稿件";
        installTextSkill(name, "保持段落结构");
        SkillStore.DeleteTarget before = store.detail(name).deleteTarget();
        AlertDialog detail = directSkillDetail(name);
        AlertDialog editor = requestEdit(detail);
        TextView command = dialogView(editor, "skills.edit.name");
        assertEquals("/" + name, command.getText().toString());
        assertFalse(command instanceof EditText);
        editField(editor, "instruction").setText("将结果缩短为两段");
        assertTrue(editor.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();

        assertEquals(before, store.detail(name).deleteTarget());
        assertTrue(store.pending().isEmpty());
        assertEquals("保持段落结构", activeInstruction(name));
        detail.dismiss();
    }

    @Test public void savingSkillEditStagesTheNewRuleUntilConfirmationWithoutRestoreControls() throws Exception {
        String name = "整理稿件";
        installTextSkill(name, "保持段落结构");
        AlertDialog detail = directSkillDetail(name);
        AlertDialog editor = requestEdit(detail);
        editField(editor, "description").setText("改写成提纲");
        editField(editor, "documentation").setText("输入原稿。\n每段返回一个条目。");
        editField(editor, "instruction").setText("每段写一个条目");
        confirm(editor);

        assertTrue(store.detail(name).canReplace());
        assertEquals("保持段落结构", activeInstruction(name));
        JSONObject draft = new JSONObject(store.pendingDefinition(name));
        assertEquals("改写成提纲", draft.getString("description"));
        assertEquals("输入原稿。\n每段返回一个条目。", draft.getString("documentation"));
        assertTrue(dialogView(detail, "skills.detail.replace").performClick());
        AlertDialog cancelledReview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(message(cancelledReview).contains("v1"));
        assertFalse(message(cancelledReview).contains("回退"));
        assertTrue(cancelledReview.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals("保持段落结构", activeInstruction(name));
        assertTrue(store.detail(name).pending());

        assertTrue(dialogView(detail, "skills.detail.replace").performClick());
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals("每段写一个条目", activeInstruction(name));
        assertTrue(store.pending().isEmpty());
        assertEquals(1, store.detail(name).deleteTarget().versionCount());
        assertNoRestore(detail);
        detail.dismiss();
        reopenManager();
        assertTrue(visibleText(view(skillRow(name))).contains("当前 v2 · 文本 · 已启用"));
        assertNoRestore(manager.dialog());
        SkillStore reopened = SkillStore.forContext(activity);
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var call = AiSkills.match(entry, "/" + name + " 一段内容", reopened);
            assertNotNull(call);
            assertEquals(new SkillResult.Text("每段写一个条目"),
                    AiSkills.executeResult(call, reopened, (instruction, argument) -> instruction));
        }
    }

    @Test public void legacySnapshotsRemainUntouchedOnOpenAndOnlyActiveAndDraftAreShown() throws Exception {
        String name = "旧目录技能";
        File directory = new File(activity.getNoBackupFilesDir(), "douai-ai/skills");
        JSONObject first = legacyRevision(directory, name, 1, "第一版指令");
        JSONObject second = legacyRevision(directory, name, 2, "第二版指令");
        String draftId = UUID.randomUUID().toString();
        JSONObject pending = new JSONObject().put("definition", textSkillDefinition(name, "第三版指令"))
                .put("draftId", draftId).put("outputType", "text").put("reason", "离线校验通过，等待确认启用同名新版");
        JSONObject current = new JSONObject().put("versions", new JSONArray().put(first).put(second))
                .put("active", 1).put("enabled", true);
        String legacyCatalog = new JSONObject().put("schemaVersion", 1).put("enabled", true)
                .put("skills", new JSONObject().put(name, current))
                .put("drafts", new JSONObject().put(name, pending)).toString();
        var catalog = new File(directory, "catalog.json").toPath();
        Files.writeString(catalog, legacyCatalog);

        reopenManager();
        String summary = visibleText(view(skillRow(name)));
        assertTrue(summary.contains("当前 v2 · 文本 · 已启用"));
        assertTrue(summary.contains("草稿 · 文本 · 待启用"));
        assertFalse(summary.contains("v1"));
        assertNoRestore(manager.dialog());
        AlertDialog detail = skillDetail(skillRow(name));
        assertNoRestore(detail);
        assertEquals(legacyCatalog, Files.readString(catalog));
        assertTrue(new File(directory, "versions/" + first.getString("directory") + "/execution.json").isFile());
        assertTrue(new File(directory, "versions/" + second.getString("directory") + "/execution.json").isFile());
        assertEquals(draftId, store.detail(name).draftId());
        assertEquals(1, store.detail(name).deleteTarget().versionCount());
        assertEquals("第二版指令", activeInstruction(name));

        assertTrue(dialogView(detail, "skills.detail.replace").performClick());
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals(3, store.detail(name).previousVersion());
        assertFalse(store.detail(name).pending());
        assertEquals("第三版指令", activeInstruction(name));
        assertEquals(1, store.detail(name).deleteTarget().versionCount());
        assertTrue(new File(directory, "versions/" + first.getString("directory") + "/execution.json").isFile());
        assertTrue(new File(directory, "versions/" + second.getString("directory") + "/execution.json").isFile());
        assertNoRestore(detail);
        detail.dismiss();
    }

    @Test public void anEditedReadyImageSkillCanReopenItsMaskedServiceForm() throws Exception {
        SkillServices.Service mock = services.find(SkillServices.MOCK_IMAGE);
        services.authorize(mock, 0);
        String source = SkillStore.mockDraft("生图");
        String name = store.saveGenerated(source, store.previewGenerated(source)).installed().name();
        AlertDialog detail = directSkillDetail(name);
        AlertDialog editor = requestEdit(detail);
        assertNull(dialogView(editor, "skills.edit.instruction"));
        editField(editor, "documentation").setText("输入想要的画面描述。");
        confirm(editor);

        assertTrue(store.detail(name).canReplace());
        assertTrue(dialogView(detail, "skills.detail.configure").performClick());
        assertNotNull(dialogView(detail, "skills.detail.adapter"));
        EditText key = dialogView(detail, "skills.detail.key");
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertEquals(0, key.length());
        assertNotNull(dialogView(detail, "skills.detail.replace"));
        detail.dismiss();
    }

    @Test public void aStaleSkillEditorCannotOverwriteAConcurrentSavedDraft() throws Exception {
        String name = "整理稿件";
        installTextSkill(name, "保持段落结构");
        AlertDialog detail = directSkillDetail(name);
        AlertDialog editor = requestEdit(detail);
        SkillStore.EditTarget concurrent = store.editTarget(store.detail(name).deleteTarget());
        SkillStore.Detail newer = store.saveEdit(concurrent, "并发保存的草稿", "更新后的说明。", "根据标题整理");
        editField(editor, "description").setText("过期编辑内容");
        confirm(editor);

        assertEquals(newer.draftId(), store.detail(name).draftId());
        assertEquals("并发保存的草稿", new JSONObject(store.pendingDefinition(name)).getString("description"));
        assertTrue(detailStatus(detail).contains("编辑未保存"));
        assertEquals("保持段落结构", activeInstruction(name));
        detail.dismiss();
    }

    private void installTextSkill(String name, String instruction) throws Exception {
        assertNotNull(store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(textSkillDefinition(name, instruction))).installed());
    }

    private static String textSkillDefinition(String name, String instruction) throws Exception {
        return new JSONObject().put("schemaVersion", 3).put("name", name).put("description", "整理文本")
                .put("documentation", "输入待整理的内容。")
                .put("execution", new JSONObject().put("kind", "text_model").put("outputType", "text")
                        .put("maxInput", 2000).put("instruction", instruction))
                .put("trial", new JSONObject().put("input", "示例内容").put("expectedType", "text")).toString();
    }

    private static JSONObject legacyRevision(File directory, String name, int version, String instruction) throws Exception {
        String id = UUID.randomUUID().toString();
        var revision = Files.createDirectories(directory.toPath().resolve("versions").resolve(id));
        Files.writeString(revision.resolve("SKILL.md"), "---\nname: " + name + "\nversion: " + version
                + "\noutput: text\n---\n\n输入待整理的内容。\n");
        Files.writeString(revision.resolve("execution.json"), new JSONObject().put("schemaVersion", 3)
                .put("version", version).put("kind", "text_model").put("outputType", "text")
                .put("maxInput", 2000).put("instruction", instruction).toString());
        return new JSONObject().put("version", version).put("directory", id).put("description", "整理文本")
                .put("outputType", "text").put("kind", "text_model").put("serviceLabel", "");
    }

    private String activeInstruction(String name) throws Exception {
        AiSkills.Invocation call = AiSkills.match(AiSkills.Entry.WORK, "/" + name + " 一段内容", store);
        assertNotNull(call);
        return ((SkillResult.Text) AiSkills.executeResult(call, store, (instruction, argument) -> instruction)).text();
    }

    private AlertDialog directSkillDetail(String name) {
        AiSkillDetailDialog page = new AiSkillDetailDialog(activity, false, name, null);
        present(page.dialog(), page::close);
        return page.dialog();
    }

    private static AlertDialog requestEdit(AlertDialog detail) {
        assertTrue(dialogView(detail, "skills.detail.edit").performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void assertNoRestore(Dialog page) {
        View decor = page.getWindow().getDecorView();
        assertNull(decor.findViewWithTag("skills.detail.history"));
        assertNull(decor.findViewWithTag("skills.detail.rollback"));
        assertFalse(visibleText(decor).contains("版本与恢复"));
        assertFalse(visibleText(decor).contains("回退上一版本"));
    }

    private static EditText editField(AlertDialog editor, String field) {
        EditText input = dialogView(editor, "skills.edit." + field);
        assertNotNull(input);
        return input;
    }

    private static <T extends View> T dialogView(AlertDialog dialog, String tag) {
        return dialog.getWindow().getDecorView().findViewWithTag(tag);
    }

    private AlertDialog skillDetail(String rowTag) {
        assertTrue(view(rowTag).performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static AlertDialog requestDeletion(AlertDialog detail) {
        View remove = detail.getWindow().getDecorView().findViewWithTag("skills.detail.delete");
        assertNotNull(remove);
        assertTrue(remove.performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static String message(AlertDialog dialog) {
        TextView message = dialog.findViewById(android.R.id.message);
        assertNotNull(message);
        return message.getText().toString();
    }

    private static String detailStatus(AlertDialog detail) {
        TextView status = detail.getWindow().getDecorView().findViewWithTag("skills.detail.status");
        assertNotNull(status);
        return status.getText().toString();
    }

    private void enterAndGenerate(String requirements, String tag) {
        ((EditText) view("skills.requirements")).setText(requirements);
        assertTrue(view(tag).performClick());
        ShadowLooper.idleMainLooper();
    }

    private static String skillRow(String name) { return "skills.row." + name; }

    private static void skillLinks(View view, String command, List<View> matches) {
        if (view.getVisibility() != View.VISIBLE) return;
        if (skillRow(command.substring(1)).equals(view.getTag())
                || view instanceof TextView text && view.isClickable() && text.getText().toString().contains(command)) {
            matches.add(view);
            return;
        }
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) skillLinks(group.getChildAt(i), command, matches);
        }
    }

    private static String visibleText(View view) {
        if (view == null || view.getVisibility() != View.VISIBLE) return "";
        if (view instanceof TextView text) return text.getText().toString();
        StringBuilder summary = new StringBuilder();
        if (view instanceof ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                if (summary.length() > 0) summary.append('\n');
                summary.append(visibleText(group.getChildAt(i)));
            }
        }
        return summary.toString();
    }

    private <T extends View> T view(String tag) {
        T view = manager.dialog().getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private void present(Dialog dialog, Runnable cleanup) {
        if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
        dialog.show();
    }

    private static void confirm(AlertDialog dialog) {
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
    }

    private static final class ImmediateExecutor extends AbstractExecutorService {
        private boolean closed;

        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { shutdown(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
        @Override public void execute(Runnable task) { task.run(); }
    }

    @Implements(ChatCompletionClient.IsolatedTextRequest.class)
    public static final class PolishingModelShadow {
        static String instruction;
        static String input;
        static String selectedId;

        @Implementation protected String execute(AiModelSettings models, String modelId, String system, String prompt,
                                                 long deadline, int maxTokens) throws Exception {
            selectedId = modelId;
            instruction = system;
            input = prompt;
            return textSkillDefinition("句子润色", "保留事实，提高表达流畅度。");
        }
    }
}
