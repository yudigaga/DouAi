package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.ai.skills.MarkdownSkillStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

import static com.douai.module.ai.skills.MarkdownSkillStore.MAX_FILES;
import static com.douai.module.ai.skills.MarkdownSkillStore.MAX_FILE_BYTES;
import static com.douai.module.ai.skills.MarkdownSkillStore.MAX_INPUT_BYTES;
import static com.douai.module.ai.skills.MarkdownSkillStore.MAX_MARKDOWN_BYTES;
import static com.douai.module.ai.skills.MarkdownSkillStore.MAX_PATH_DEPTH;

/** Imports reviewed, inert local Markdown Skill files via the host's document picker. */
final class AiMarkdownSkillSettingsDialog implements AutoCloseable {
    private static final int MAX_DIRECTORY_ENTRIES = 512;
    private static final String ROOT_FILE = "SKILL.md";
    private static final String[] CHILD_COLUMNS = {
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE
    };

    private final Context context;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final ExecutorService worker;
    private final MarkdownSkillStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AlertDialog dialog;
    private final LinearLayout rows;
    private final TextView status;
    private HostConfigFilePicker picker;
    private Future<?> task;
    private boolean busy;
    private boolean closed;

    AiMarkdownSkillSettingsDialog(Context context, boolean miuix, BiConsumer<Dialog, Runnable> presenter,
                                  ExecutorService worker) {
        this.context = context;
        this.miuix = miuix;
        this.presenter = presenter;
        this.worker = worker;
        store = MarkdownSkillStore.forContext(context);

        LinearLayout content = form();
        button(content, "导入技能目录", "skills.markdown.directory", () -> selectDocument(true));
        button(content, "导入技能 ZIP", "skills.markdown.zip", () -> selectDocument(false));
        button(content, "粘贴 SKILL.md", "skills.markdown.paste", this::pasteMarkdown);
        status = text(content, "", "skills.markdown.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        rows = new LinearLayout(context);
        rows.setOrientation(LinearLayout.VERTICAL);
        content.addView(rows, matchWrap());
        dialog = builder("Markdown 技能").setView(scroll(content)).setNegativeButton("关闭", null).create();
        refresh("暂时无法读取 Markdown 技能");
    }

    AlertDialog dialog() { return dialog; }

    private void selectDocument(boolean folder) {
        if (!active() || busy || picker != null) return;
        Intent intent = new Intent(folder ? Intent.ACTION_OPEN_DOCUMENT_TREE : Intent.ACTION_OPEN_DOCUMENT)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (folder) intent.addFlags(Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        else intent.addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
        picker = HostConfigFilePicker.launch(owner(), intent, uri -> {
            picker = null;
            if (uri == null || !active()) return;
            perform("正在读取技能文件…", "无法导入所选文件，请核对 SKILL.md、文件路径和大小",
                    () -> folder ? store.previewDirectory(readDirectory(context.getContentResolver(), uri))
                            : previewZip(uri), this::reviewImport, null);
        });
        if (picker == null) status.setText("无法打开系统文件选择器");
    }

    private MarkdownSkillStore.Preview previewZip(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException();
            return store.previewZip(input);
        }
    }

    private void pasteMarkdown() {
        if (!active() || busy) return;
        LinearLayout form = form();
        EditText input = miuix ? MiuixFormControls.input(context, "SKILL.md 内容", "", false) : new EditText(context);
        input.setHint("SKILL.md 内容");
        input.setTag("skills.markdown.paste.input");
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        input.setMinLines(6);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(MAX_MARKDOWN_BYTES + 1)});
        input.setSaveEnabled(false);
        View control = MiuixFormControls.viewFor(input);
        control.setSaveFromParentEnabled(false);
        form.addView(control, matchWrap());
        presenter.accept(builder("粘贴 SKILL.md").setView(scroll(form)).setNegativeButton("取消", null)
                .setPositiveButton("预览", (child, which) -> {
                    String markdown = input.getText().toString();
                    perform("正在读取技能…", "无法导入，请核对 SKILL.md 的格式和大小",
                            () -> store.previewDirectory(Map.of(ROOT_FILE, markdown.getBytes(StandardCharsets.UTF_8))),
                            this::reviewImport, null);
                }).create(), null);
    }

    private void reviewImport(MarkdownSkillStore.Preview preview) {
        status.setText("");
        LinearLayout review = form();
        text(review, preview.description().description(), null);
        text(review, "文件 " + preview.files().size() + " 个 · 导入后先保持停用", null);
        text(review, String.join("\n", preview.files()), "skills.markdown.preview.files");
        markdownText(review, preview.markdown());
        presenter.accept(builder("预览 /" + preview.description().name()).setView(scroll(review))
                .setNegativeButton("取消", null).setPositiveButton("导入", (child, which) ->
                        perform("正在导入…", "技能导入失败，请核对名称是否已存在",
                                () -> store.install(preview), ignored -> refreshAfter("技能已导入"), null))
                .create(), null);
    }

    private void refreshAfter(String message) {
        status.setText(message);
        refresh("请重开设置查看当前 Markdown 技能");
    }

    private void refresh(String failure) {
        try {
            var entries = store.list();
            rows.removeAllViews();
            if (entries.isEmpty()) text(rows, "暂无 Markdown 技能", null);
            for (MarkdownSkillStore.Entry entry : entries) {
                String summary = entry.description() + "\n" + (entry.enabled() ? "已启用" : "已停用");
                String tag = "skills.markdown.row." + entry.name();
                if (miuix) {
                    View row = new MiuixSettingsViews.Option(context, "/" + entry.name(), summary,
                            null, true, () -> openSkill(entry)).getView();
                    row.setTag(tag);
                    rows.addView(row, matchWrap());
                } else button(rows, "/" + entry.name() + "\n" + summary, tag, () -> openSkill(entry));
            }
        } catch (IOException | RuntimeException unavailable) { status.setText(failure); }
    }

    private void openSkill(MarkdownSkillStore.Entry entry) {
        if (!active() || busy) return;
        LinearLayout detail = form();
        text(detail, entry.description(), null);
        CompoundButton enabled = miuix ? MiuixFormControls.checkbox(context, "启用", entry.enabled()) : new Switch(context);
        enabled.setTag("skills.markdown.enabled");
        enabled.setText("启用");
        enabled.setChecked(entry.enabled());
        detail.addView(MiuixFormControls.viewFor(enabled), matchWrap());
        MarkdownSkillStore.Entry[] current = {entry};
        AlertDialog page = builder("/" + entry.name()).setView(scroll(detail)).setNegativeButton("关闭", null).create();
        enabled.setOnCheckedChangeListener((control, value) -> {
            if (value == current[0].enabled()) return;
            if (!active() || busy) {
                control.setChecked(current[0].enabled());
                return;
            }
            control.setEnabled(false);
            MarkdownSkillStore.Entry expected = current[0];
            perform("正在保存…", "技能状态已变化，请重开详情核对", () -> store.setEnabled(expected, value),
                    saved -> {
                        current[0] = saved;
                        control.setEnabled(true);
                        refreshAfter("技能状态已保存");
                    }, () -> {
                        control.setEnabled(true);
                        control.setChecked(current[0].enabled());
                    });
        });
        for (String path : entry.files()) {
            button(detail, path, "skills.markdown.file." + path, () -> {
                MarkdownSkillStore.Entry expected = current[0];
                perform("正在读取文件…", "无法读取文件，请重开技能详情",
                        () -> store.readApprovedFile(expected, path), body -> {
                            status.setText("");
                            if (page.isShowing()) showFile(path, body);
                        }, null);
            });
        }
        button(detail, "删除技能", "skills.markdown.delete", () -> {
            if (busy || !active()) return;
            MarkdownSkillStore.Entry expected = current[0];
            presenter.accept(builder("删除 Markdown 技能")
                    .setMessage("将从本机删除 /" + expected.name() + " 及其导入文件。")
                    .setNegativeButton("取消", null).setPositiveButton("确认删除", (child, which) -> {
                        if (!page.isShowing()) return;
                        perform("正在删除…", "删除未完成，请重开详情核对",
                                () -> { store.delete(expected); return null; }, ignored -> {
                                    page.dismiss();
                                    refreshAfter("技能已删除");
                                }, null);
                    }).create(), null);
        });
        presenter.accept(page, null);
    }

    private void showFile(String path, String markdown) {
        LinearLayout content = form();
        markdownText(content, markdown);
        presenter.accept(builder(path).setView(scroll(content)).setNegativeButton("关闭", null).create(), null);
    }

    private <T> void perform(String progress, String failure, Callable<T> action, Consumer<T> success, Runnable onFailure) {
        if (!active() || busy) return;
        busy = true;
        status.setText(progress);
        try {
            task = worker.submit(() -> {
                try {
                    T result = action.call();
                    main.post(() -> {
                        if (!active()) return;
                        busy = false;
                        task = null;
                        success.accept(result);
                    });
                } catch (Exception unavailable) {
                    main.post(() -> failed(failure, onFailure));
                }
            });
        } catch (RejectedExecutionException unavailable) { failed(failure, onFailure); }
    }

    private void failed(String message, Runnable onFailure) {
        if (!active()) return;
        busy = false;
        task = null;
        status.setText(message);
        if (onFailure != null) onFailure.run();
    }

    private static Map<String, byte[]> readDirectory(ContentResolver resolver, Uri tree) throws IOException {
        if (!DocumentsContract.isTreeUri(tree)) throw new IOException();
        String root = DocumentsContract.getTreeDocumentId(tree);
        ArrayDeque<Directory> pending = new ArrayDeque<>();
        pending.add(new Directory(root, "", 0));
        Set<String> visited = new HashSet<>();
        visited.add(root);
        Map<String, byte[]> files = new LinkedHashMap<>();
        int totalBytes = 0;
        while (!pending.isEmpty()) {
            Directory folder = pending.removeFirst();
            Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, folder.id());
            try (Cursor cursor = resolver.query(listing, CHILD_COLUMNS, null, null, null)) {
                if (cursor == null) throw new IOException();
                while (cursor.moveToNext()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException();
                    String id = cursor.getString(0);
                    String name = cursor.getString(1);
                    if (name == null || name.isBlank() || name.equals(".") || name.equals("..")
                            || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0) throw new IOException();
                    if (id == null || !visited.add(id) || visited.size() > MAX_DIRECTORY_ENTRIES) throw new IOException();
                    String path = folder.prefix() + name;
                    if (DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2))) {
                        if (folder.depth() + 1 >= MAX_PATH_DEPTH) throw new IOException();
                        pending.addLast(new Directory(id, path + "/", folder.depth() + 1));
                        continue;
                    }
                    if (files.size() >= MAX_FILES || files.containsKey(path)) throw new IOException();
                    Uri document = DocumentsContract.buildDocumentUriUsingTree(tree, id);
                    try (InputStream input = resolver.openInputStream(document)) {
                        byte[] bytes = readFile(input, Math.min(MAX_INPUT_BYTES - totalBytes,
                                ROOT_FILE.equalsIgnoreCase(name) ? MAX_MARKDOWN_BYTES : MAX_FILE_BYTES));
                        totalBytes += bytes.length;
                        files.put(path, bytes);
                    }
                }
            }
        }
        return files;
    }

    private record Directory(String id, String prefix, int depth) {}

    private static byte[] readFile(InputStream input, int limit) throws IOException {
        if (input == null) throw new IOException();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (Thread.currentThread().isInterrupted() || read == 0 || output.size() + read > limit) {
                throw new IOException();
            }
            output.write(buffer, 0, read);
        }
        return output.toByteArray();
    }

    private Activity owner() {
        Activity owner = dialog.getOwnerActivity();
        if (owner != null) return owner;
        for (Context current = context; current instanceof ContextWrapper wrapper; current = wrapper.getBaseContext()) {
            if (current instanceof Activity activity) return activity;
            if (current == wrapper.getBaseContext()) break;
        }
        return null;
    }

    private boolean active() { return !closed && dialog.isShowing(); }

    private AlertDialog.Builder builder(String title) { return new MiuixDialogBuilder(context, miuix).setTitle(title); }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        form.setPadding(padding, 0, padding, padding);
        return form;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private TextView text(LinearLayout parent, String value, String tag) {
        TextView text = new TextView(context);
        text.setText(value);
        text.setTag(tag);
        parent.addView(text, matchWrap());
        return text;
    }

    private void markdownText(LinearLayout parent, String markdown) {
        TextView body = text(parent, markdown, "skills.markdown.preview.body");
        body.setTextIsSelectable(true);
        body.setTypeface(Typeface.MONOSPACE);
    }

    private void button(LinearLayout parent, String label, String tag, Runnable action) {
        Button button = miuix ? MiuixFormControls.button(context) : new Button(context);
        button.setAllCaps(false);
        button.setText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> { if (active()) action.run(); });
        parent.addView(MiuixFormControls.viewFor(button), matchWrap());
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    @Override public void close() {
        closed = true;
        if (picker != null) picker.close();
        if (task != null) task.cancel(true);
    }
}
