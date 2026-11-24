package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.skills.AiSkills;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.HostSettingsStore;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
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

    @Test public void oneWordImageRequirementStaysPendingUntilExplicitConnectAfterAuthorization() throws Exception {
        enterAndGenerate("生图", "skills.generate.model");
        assertEquals(1, store.pending().size());
        assertEquals(SkillResult.Type.IMAGE, store.pending().get(0).outputType());
        assertTrue(store.list().isEmpty());
        String name = store.pending().get(0).name();
        assertTrue(visibleText(view(skillRow(name))).contains("草稿 · 图片 · 待配置"));

        assertTrue(view("skills.service." + SkillServices.MOCK_IMAGE + ".authorize").performClick());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(((TextView) review.findViewById(android.R.id.message)).getText().toString().contains("本地模拟"));
        confirm(review);
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

    @Test public void choosingImageServiceRegistersWithoutGrantingAuthorization() throws Exception {
        assertTrue(view("skills.service.register").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = editor.getWindow().getDecorView();
        ((Spinner) form.findViewWithTag("skills.service.output")).setSelection(1);
        ((EditText) form.findViewWithTag("skills.service.id")).setText("fixture-image");
        ((EditText) form.findViewWithTag("skills.service.label")).setText("示例图片服务");
        ((EditText) form.findViewWithTag("skills.service.endpoint")).setText("https://images.example.test/generate");
        ((EditText) form.findViewWithTag("skills.service.cost")).setText("2");
        confirm(editor);

        SkillServices.Service service = services.find("fixture-image");
        assertEquals(SkillResult.Type.IMAGE, service.outputType());
        assertFalse(services.isAuthorized(service));
        assertTrue(((TextView) view("skills.service.fixture-image")).getText().toString().contains("图片"));
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
}
