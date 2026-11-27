package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;

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
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

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
        assertNotNull(view("skills.author_chat"));
        assertNull(manager.dialog().getWindow().getDecorView().findViewWithTag("skills.requirements"));
        assertNull(manager.dialog().getWindow().getDecorView().findViewWithTag("skills.generate.model"));
        assertNull(manager.dialog().getWindow().getDecorView().findViewWithTag("skills.generate.mock"));
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

    @Test public void markdownPasteRequiresPreviewBeforeImportAndCanReadToggleAndDelete() {
        assertTrue(view("skills.markdown.manage").performClick());
        AlertDialog page = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(page.isShowing());
        assertTrue(dialogView(page, "skills.markdown.paste").performClick());
        AlertDialog paste = (AlertDialog) ShadowDialog.getLatestDialog();
        String markdown = """
                ---
                name: field-notes
                description: Organize local notes.
                ---

                ## Steps
                Group related observations.
                """;
        ((EditText) dialogView(paste, "skills.markdown.paste.input")).setText(markdown);
        confirm(paste);
        AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(preview.isShowing());
        assertTrue(visibleText(dialogView(preview, "skills.markdown.preview.body")).contains("Group related observations."));
        assertNull(dialogView(page, "skills.markdown.row.field-notes"));
        confirm(preview);
        View row = dialogView(page, "skills.markdown.row.field-notes");
        assertNotNull(row);
        assertTrue(visibleText(row).contains("已停用"));
        assertTrue(row.performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        CompoundButton enabled = (CompoundButton) dialogView(detail, "skills.markdown.enabled");
        assertFalse(enabled.isChecked());
        enabled.performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(visibleText(dialogView(page, "skills.markdown.row.field-notes")).contains("已启用"));
        assertTrue(dialogView(detail, "skills.markdown.file.SKILL.md").performClick());
        ShadowLooper.idleMainLooper();
        AlertDialog file = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView body = (TextView) dialogView(file, "skills.markdown.preview.body");
        assertEquals(markdown, body.getText().toString());
        assertTrue(body.isTextSelectable());
        file.dismiss();
        ShadowLooper.idleMainLooper();
        assertTrue(dialogView(detail, "skills.markdown.delete").performClick());
        AlertDialog delete = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialogView(page, "skills.markdown.row.field-notes"));
        confirm(delete);
        assertNull(dialogView(page, "skills.markdown.row.field-notes"));
        page.dismiss();
    }

    @Test public void markdownDirectoryAndZipGoThroughTransientHostDocumentSelection() throws Exception {
        activity.finish();
        MarkdownPickerActivity host = Robolectric.buildActivity(MarkdownPickerActivity.class).setup().get();
        activity = host;
        reopenManager();
        assertTrue(view("skills.markdown.manage").performClick());
        AlertDialog page = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialogView(page, "skills.markdown.zip").performClick());
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, host.registry.intent.getAction());
        assertTrue(List.of(host.registry.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).contains("application/zip"));
        assertEquals(0, host.registry.intent.getFlags() & Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        Uri zip = Uri.parse("content://markdown-archive/skill.zip");
        ByteArrayOutputStream zipBytes = new ByteArrayOutputStream();
        try (ZipOutputStream stream = new ZipOutputStream(zipBytes)) {
            stream.putNextEntry(new ZipEntry("inside/SKILL.md"));
            stream.write(markdownFixture("from-archive"));
            stream.closeEntry();
            stream.putNextEntry(new ZipEntry("inside/references/notes.md"));
            stream.write("Archive reference".getBytes(StandardCharsets.UTF_8));
            stream.closeEntry();
        }
        var resolver = org.robolectric.shadow.api.Shadow
                .<org.robolectric.shadows.ShadowContentResolver>extract(host.getContentResolver());
        resolver.registerInputStream(zip, new ByteArrayInputStream(zipBytes.toByteArray()));
        host.registry.dispatchResult(host.registry.code, Activity.RESULT_OK, new Intent().setData(zip));
        ShadowLooper.idleMainLooper();
        AlertDialog archivePreview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(visibleText(dialogView(archivePreview, "skills.markdown.preview.files")).contains("references/notes.md"));
        assertNull(dialogView(page, "skills.markdown.row.from-archive"));
        confirm(archivePreview);
        assertNotNull(dialogView(page, "skills.markdown.row.from-archive"));

        Uri tree = DocumentsContract.buildTreeDocumentUri("markdown-tree", "root");
        ProviderInfo info = new ProviderInfo();
        info.authority = tree.getAuthority();
        MarkdownDirectoryFixture provider = new MarkdownDirectoryFixture();
        provider.attachInfo(host, info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(info.authority, provider);
        resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "skill"),
                new ByteArrayInputStream(markdownFixture("from-folder")));
        resolver.registerInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, "notes"),
                new ByteArrayInputStream("Folder reference".getBytes(StandardCharsets.UTF_8)));
        assertTrue(dialogView(page, "skills.markdown.directory").performClick());
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, host.registry.intent.getAction());
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                host.registry.intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, host.registry.intent.getFlags()
                & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));
        host.registry.dispatchResult(host.registry.code, Activity.RESULT_OK, new Intent().setData(tree));
        ShadowLooper.idleMainLooper();
        AlertDialog folderPreview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(visibleText(dialogView(folderPreview, "skills.markdown.preview.files")).contains("references/notes.md"));
        assertNull(dialogView(page, "skills.markdown.row.from-folder"));
        confirm(folderPreview);
        View folder = dialogView(page, "skills.markdown.row.from-folder");
        assertTrue(visibleText(folder).contains("已停用"));
        assertTrue(folder.performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(dialogView(detail, "skills.markdown.file.references/notes.md").performClick());
        ShadowLooper.idleMainLooper();
        AlertDialog notes = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("Folder reference", ((TextView) dialogView(notes, "skills.markdown.preview.body")).getText().toString());
        notes.dismiss();
        detail.dismiss();
        page.dismiss();
    }

    private static byte[] markdownFixture(String name) {
        return ("---\nname: " + name + "\ndescription: Organize notes.\n---\n\n## Steps\nRead the notes.\n")
                .getBytes(StandardCharsets.UTF_8);
    }

    public static final class MarkdownPickerActivity extends Activity {
        final MarkdownPickerRegistry registry = new MarkdownPickerRegistry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
    }

    private static final class MarkdownPickerRegistry extends ActivityResultRegistry {
        int code;
        Intent intent;

        @Override public <I, O> void onLaunch(int request, ActivityResultContract<I, O> contract, I input,
                                              ActivityOptionsCompat options) {
            code = request;
            intent = (Intent) input;
        }
    }

    public static final class MarkdownDirectoryFixture extends ContentProvider {
        @Override public boolean onCreate() { return true; }

        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] arguments, String order) {
            MatrixCursor rows = new MatrixCursor(projection);
            String parent = DocumentsContract.getDocumentId(uri);
            if ("root".equals(parent)) {
                rows.addRow(new String[]{"skill", "SKILL.md", "text/markdown"});
                rows.addRow(new String[]{"references", "references", DocumentsContract.Document.MIME_TYPE_DIR});
            } else if ("references".equals(parent)) {
                rows.addRow(new String[]{"notes", "notes.md", "text/markdown"});
            }
            return rows;
        }

        @Override public String getType(Uri uri) { return "text/markdown"; }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] arguments) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] arguments) {
            throw new UnsupportedOperationException();
        }
    }

    @Test public void executableJsonImportUsesHostPickerAndSavesOnlyAfterDraftConfirmation() throws Exception {
        activity.finish();
        MarkdownPickerActivity host = Robolectric.buildActivity(MarkdownPickerActivity.class).setup().get();
        activity = host;
        store = SkillStore.forContext(host);
        reopenManager();
        assertTrue(view("skills.import.json").performClick());
        Intent intent = host.registry.intent;
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, intent.getAction());
        assertTrue(intent.hasCategory(Intent.CATEGORY_OPENABLE));
        assertTrue(List.of(intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES)).contains("application/json"));
        assertFalse(intent.getBooleanExtra(Intent.EXTRA_ALLOW_MULTIPLE, false));
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION,
                intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        assertEquals(0, intent.getFlags()
                & (Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION));

        String definition = """
                {"schemaVersion":4,"name":"导入轮询","description":"按文档轮询视频服务","documentation":"输入画面说明",
                 "instructions":"只按原任务 ID 读取成片。","capabilityId":"video_task","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"示例","expectedType":"video"},
                 "http":{"version":1,"label":"视频接口","request":{"prompt":"argument"},"response":"job.id",
                  "video":{"statusPath":"/jobs/{id}","downloadPath":"/media/{id}","idField":"job.id",
                           "stateField":"job.status","states":{"done":"completed"}}}}
                """;
        Uri document = Uri.parse("content://skill-import/reviewed.json");
        var resolver = org.robolectric.shadow.api.Shadow
                .<org.robolectric.shadows.ShadowContentResolver>extract(host.getContentResolver());
        resolver.registerInputStream(document, new ByteArrayInputStream(definition.getBytes(StandardCharsets.UTF_8)));
        host.registry.dispatchResult(host.registry.code, Activity.RESULT_OK, new Intent().setData(document));
        ShadowLooper.idleMainLooper();
        AlertDialog preview = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(preview.isShowing());
        var summary = org.robolectric.shadow.api.Shadow.<org.robolectric.shadows.ShadowAlertDialog>extract(preview);
        String display = String.valueOf(summary.getMessage());
        assertTrue(display.contains("/导入轮询"));
        assertTrue(display.contains("按文档轮询视频服务"));
        assertTrue(display.contains("视频"));
        assertEquals("先保存为草稿", preview.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(store.pending().isEmpty());
        assertTrue(store.list().isEmpty());

        confirm(preview);
        assertEquals(List.of("导入轮询"), store.pending().stream().map(SkillStore.Pending::name).toList());
        assertTrue(store.list().isEmpty());
        SkillStore.Detail saved = store.detail("导入轮询");
        assertTrue(saved.pending());
        assertNull(saved.customConfiguration());
        assertEquals(SkillResult.Type.VIDEO, saved.outputType());
        assertEquals("/jobs/{id}", saved.manifest().http().video().statusPath());
        assertNotNull(view(skillRow("导入轮询")));
    }

    @Test public void oneWordImageRequirementStaysPendingUntilExplicitConnectAfterAuthorization() throws Exception {
        saveMockDraft("生图");
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

    @Test public void directHttpImageOffersOnlyItsSettingsAndReadOnlyMapping() throws Exception {
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
        assertNull(dialogView(detail, "skills.detail.direct_contract.replace"));
        assertNotNull(dialogView(detail, "skills.detail.config.size"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.custom.contract")).contains("prompt"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.edit_in_chat")).contains("/技能 编辑 /" + name));

        SkillStore.Detail pending = store.detail(name);
        assertEquals(initial.deleteTarget(), pending.deleteTarget());
        assertEquals(java.util.Map.of("prompt", "argument"), pending.manifest().http().request());
        assertEquals("image", pending.manifest().http().responseField());
        assertNull(pending.customConfiguration());
        assertTrue(store.list().isEmpty());
    }

    @Test public void documentedAbsoluteVideoCanConfigureOnlyItsExactSubmitAddress() throws Exception {
        String name = "接口视频";
        String definition = """
                {"schemaVersion":4,"name":"接口视频","description":"通过接口生成视频","documentation":"输入提示词。",
                 "instructions":"使用原任务 ID 查询","capabilityId":"video_task","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"video","trial":{"input":"样例","expectedType":"video"}}
                """;
        String firstEndpoint = "https://first-video.example.com/v1/jobs";
        var prior = com.douai.module.ai.skills.SkillVideoFacts.parse(videoProviderNotes(firstEndpoint, "/v1/jobs/{id}"));
        store.saveAuthoredVideoDraft(SkillStore.prepareAuthoredVideoDraft(definition, prior), prior);
        SkillStore.Detail pending = store.detail(name);
        assertTrue(pending.pending());
        reopenManager();
        AlertDialog detail = skillDetail(skillRow(name));
        assertNull(dialogView(detail, "skills.detail.video_contract.replace"));
        assertNull(dialogView(detail, "skills.detail.custom.save_complete_endpoint"));
        EditText endpoint = dialogView(detail, "skills.detail.custom.endpoint");
        assertNotNull(endpoint);
        assertTrue(endpoint.getHint().toString().contains("HTTPS API 地址"));
        assertEquals(firstEndpoint, endpoint.getText().toString());
        assertNotNull(dialogView(detail, "skills.detail.custom.credential"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.edit_in_chat")).contains("/技能 编辑 /" + name));
        assertTrue(dialogView(detail, "skills.detail.video_advanced").performClick());
        assertTrue(visibleText(dialogView(detail, "skills.detail.custom.video_routes")).contains("/v1/jobs/{id}"));

        endpoint.setText("https://another.example.com/v1/jobs");
        assertTrue(dialogView(detail, "skills.detail.custom.save").performClick());
        assertEquals(pending.deleteTarget(), store.detail(name).deleteTarget());

        endpoint.setText(firstEndpoint);
        assertTrue(dialogView(detail, "skills.detail.custom.save").performClick());
        SkillStore.Detail current = store.detail(name);
        assertFalse(current.pending());
        assertEquals(firstEndpoint, current.customConfiguration().endpoint());
        var currentCall = AiSkills.match(AiSkills.Entry.WORK, "/接口视频 样例", store);
        assertEquals(firstEndpoint, store.previewCustomService(currentCall).endpoint());
    }

    @Test public void completeRelativeVideoStartsWithBaseAndKeyAndPreservesHiddenDefaults() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        SkillStore local = com.douai.module.ai.skills.SkillCustomServiceTest.chatFixture(activity, calls);
        String name = saveRelativeVideoWithDefaults(local, false);
        var page = new AiSkillDetailDialog(activity, false, name, null, null, new ImmediateExecutor(), local);
        page.dialog().show();
        try {
            AlertDialog detail = page.dialog();
            View advanced = dialogView(detail, "skills.detail.video_advanced.content");
            assertNotNull(advanced);
            assertEquals(View.GONE, advanced.getVisibility());
            EditText base = (EditText) dialogView(detail, "skills.detail.custom.endpoint");
            assertTrue(base.getHint().toString().contains("API 基础地址"));
            assertFalse(inside(base, advanced));
            assertFalse(inside(dialogView(detail, "skills.detail.custom.credential"), advanced));
            assertFalse(inside(dialogView(detail, "skills.detail.custom.save"), advanced));
            assertFalse(inside(dialogView(detail, "skills.detail.config.model"), advanced));
            assertNotNull(dialogView(detail, "skills.detail.fetch_models"));
            assertNull(dialogView(detail, "skills.detail.video_contract.replace"));
            assertNull(dialogView(detail, "skills.detail.custom.save_complete_endpoint"));
            assertNull(dialogView(detail, "skills.detail.edit"));
            for (String tag : List.of("skills.detail.custom.contract", "skills.detail.custom.video_routes",
                    "skills.detail.video_facts.open", "skills.detail.config.duration", "skills.detail.config.resolution")) {
                assertTrue(tag, inside(dialogView(detail, tag), advanced));
            }
            View toggle = dialogView(detail, "skills.detail.video_advanced");
            assertTrue(toggle.performClick());
            assertEquals(View.VISIBLE, advanced.getVisibility());
            assertTrue(toggle.performClick());
            assertEquals(View.GONE, advanced.getVisibility());
            assertEquals(0, calls.get());

            base.setText("https://gateway.example.com");
            assertTrue(dialogView(detail, "skills.detail.custom.save").performClick());
            SkillStore.Detail enabled = local.detail(name);
            assertFalse(enabled.pending());
            assertEquals("https://gateway.example.com/v1/videos", enabled.customConfiguration().endpoint());
            assertEquals("grok-imagine-video-1.5-lite", enabled.manifestValues().get("model"));
            assertEquals(5, enabled.manifestValues().get("duration"));
            assertEquals("480p", enabled.manifestValues().get("resolution"));
            assertEquals(0, calls.get());
        } finally {
            page.dialog().dismiss();
            page.close();
        }
    }

    @Test public void genuinelyMissingRequiredVideoFieldRemainsOnTheMainForm() throws Exception {
        String name = saveRelativeVideoWithDefaults(store, true);
        reopenManager();
        AlertDialog detail = skillDetail(skillRow(name));
        View advanced = dialogView(detail, "skills.detail.video_advanced.content");
        EditText model = (EditText) dialogView(detail, "skills.detail.config.model");
        assertNotNull(advanced);
        assertNotNull(model);
        assertEquals(View.GONE, advanced.getVisibility());
        assertFalse(inside(model, advanced));
        assertTrue(model.getText().toString().isEmpty());
        assertTrue(inside(dialogView(detail, "skills.detail.config.duration"), advanced));
    }

    @Test public void explicitVideoModelLookupLetsUserSelectALoneUndocumentedModel() throws Exception {
        String name = saveRelativeVideoWithDefaults(store, true);
        SkillStore.Detail original = store.detail(name);
        var lookups = new java.util.concurrent.atomic.AtomicInteger();
        var page = new AiSkillDetailDialog(activity, false, name, null, null, new ImmediateExecutor(), store,
                (expected, base, bearer) -> {
                    lookups.incrementAndGet();
                    assertEquals(original.deleteTarget(), expected.deleteTarget());
                    assertEquals("https://gateway.example.com", base);
                    assertEquals("", bearer);
                    return List.of("fixture-video-model");
                });
        page.dialog().show();
        try {
            AlertDialog detail = page.dialog();
            EditText base = dialogView(detail, "skills.detail.custom.endpoint");
            EditText model = dialogView(detail, "skills.detail.config.model");
            assertEquals(0, lookups.get());
            assertTrue(model.getText().toString().isEmpty());
            base.setText("https://gateway.example.com");
            assertTrue(dialogView(detail, "skills.detail.fetch_models").performClick());
            ShadowLooper.idleMainLooper();
            assertEquals(1, lookups.get());
            assertTrue(model.getText().toString().isEmpty());
            AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
            assertNotNull(choices.getListView());
            choices.getListView().performItemClick(null, 0, 0);
            ShadowLooper.idleMainLooper();
            assertEquals("fixture-video-model", model.getText().toString());
            assertEquals(original.deleteTarget(), store.detail(name).deleteTarget());
            assertNull(store.detail(name).manifestValues().get("model"));

            assertTrue(dialogView(detail, "skills.detail.custom.save").performClick());
            assertFalse(store.detail(name).pending());
            assertEquals("fixture-video-model", store.detail(name).manifestValues().get("model"));
            assertEquals(1, lookups.get());
        } finally {
            page.dialog().dismiss();
            page.close();
        }
    }

    private static String saveRelativeVideoWithDefaults(SkillStore store, boolean missingModel) throws Exception {
        JSONObject definition;
        try (var input = AiSkillSettingsDialogTest.class.getResourceAsStream("/skill-import/new-api-text-to-video.json")) {
            assertNotNull(input);
            definition = new JSONObject(new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
        }
        if (missingModel) definition.getJSONArray("configFields").getJSONObject(0).remove("default");
        var source = com.douai.module.ai.skills.SkillManifest.parse(definition.toString());
        var facts = com.douai.module.ai.skills.SkillVideoFacts.fromDeclaration(source, "",
                com.douai.module.ai.skills.SkillVideoFacts.Source.STORED)
                .withForm(java.util.Map.of("submitPath", "/v1/videos"));
        var preview = SkillStore.prepareAuthoredVideoDraft(definition.toString(), facts);
        assertNotNull(store.saveConversationDraft(preview, facts, null).pending());
        return source.name();
    }

    private static boolean inside(View child, View ancestor) {
        if (child == null || ancestor == null) return false;
        for (android.view.ViewParent parent = child.getParent(); parent != null; parent = parent.getParent()) {
            if (parent == ancestor) return true;
        }
        return false;
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

    @Test public void pendingListOpensTheExactSkillConfiguration() throws Exception {
        saveMockDraft("生图");
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

    @Test public void configurationMasksKeyAndRequiresAuthorizationToConnectMock() throws Exception {
        saveMockDraft("生图");
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

    @Test public void cancellingThenConfirmingDeleteCoversOnlyTheNamedSkillAndDraft() throws Exception {
        saveMockDraft("提取摘要");
        String name = store.list().get(0).name();
        saveMockDraft("提取摘要");
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

    @Test public void thePendingDraftCanBeDeletedWithoutAnInstalledVersion() throws Exception {
        saveMockDraft("生图");
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
        saveMockDraft("提取摘要");
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
        saveMockDraft("生图");
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

    @Test public void settingsDirectsSkillEditsToChatWithoutWritingDrafts() throws Exception {
        String name = "整理稿件";
        installTextSkill(name, "保持段落结构");
        SkillStore.DeleteTarget before = store.detail(name).deleteTarget();
        AlertDialog detail = directSkillDetail(name);
        assertNull(dialogView(detail, "skills.detail.edit"));
        assertTrue(visibleText(dialogView(detail, "skills.detail.edit_in_chat")).contains("/技能 编辑 /" + name));

        assertEquals(before, store.detail(name).deleteTarget());
        assertTrue(store.pending().isEmpty());
        assertEquals("保持段落结构", activeInstruction(name));
        detail.dismiss();
    }

    @Test public void savingSkillEditStagesTheNewRuleUntilConfirmationWithoutRestoreControls() throws Exception {
        String name = "整理稿件";
        installTextSkill(name, "保持段落结构");
        SkillStore.EditTarget draftTarget = store.editTarget(store.detail(name).deleteTarget());
        store.saveEdit(draftTarget, "改写成提纲", "输入原稿。\n每段返回一个条目。", "每段写一个条目");
        AlertDialog detail = directSkillDetail(name);

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
        SkillStore.EditTarget target = store.editTarget(store.detail(name).deleteTarget());
        store.saveEdit(target, target.description(), "输入想要的画面描述。", null);
        AlertDialog detail = directSkillDetail(name);

        assertTrue(store.detail(name).canReplace());
        assertTrue(dialogView(detail, "skills.detail.configure").performClick());
        assertNotNull(dialogView(detail, "skills.detail.adapter"));
        EditText key = dialogView(detail, "skills.detail.key");
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertEquals(0, key.length());
        assertNotNull(dialogView(detail, "skills.detail.replace"));
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

    private static void assertNoRestore(Dialog page) {
        View decor = page.getWindow().getDecorView();
        assertNull(decor.findViewWithTag("skills.detail.history"));
        assertNull(decor.findViewWithTag("skills.detail.rollback"));
        assertFalse(visibleText(decor).contains("版本与恢复"));
        assertFalse(visibleText(decor).contains("回退上一版本"));
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

    private void saveMockDraft(String requirements) throws Exception {
        String draft = SkillStore.mockDraft(requirements);
        store.saveGenerated(draft, store.previewGenerated(draft));
        reopenManager();
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
