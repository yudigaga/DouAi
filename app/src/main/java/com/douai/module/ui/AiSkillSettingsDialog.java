package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.ai.skills.SkillAuthoring;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class AiSkillSettingsDialog implements AutoCloseable {
    private static final int MAX_JSON_IMPORT_BYTES = 16 * 1024;
    private final Context context;
    private final SettingsStore settings;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final SkillStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final AlertDialog dialog;
    private final EditText requirements;
    private final TextView status;
    private final Button generate;
    private final Button simulate;
    private final Button importJson;
    private final LinearLayout skillRows;
    private ChatCompletionClient.IsolatedTextRequest generation;
    private Future<?> generationTask;
    private HostConfigFilePicker importPicker;
    private Future<?> importTask;
    private boolean busy;
    private boolean closed;
    private AiMarkdownSkillSettingsDialog markdown;

    AiSkillSettingsDialog(Context context, SettingsStore settings, boolean miuix,
                          BiConsumer<Dialog, Runnable> presenter) {
        this(context, settings, miuix, presenter, Executors.newSingleThreadExecutor());
    }

    AiSkillSettingsDialog(Context context, SettingsStore settings, boolean miuix,
                          BiConsumer<Dialog, Runnable> presenter, ExecutorService worker) {
        this.context = context;
        this.settings = settings;
        this.miuix = miuix;
        this.presenter = presenter;
        this.worker = worker;
        store = SkillStore.forContext(context);

        LinearLayout content = form();
        CompoundButton enabled = miuix ? MiuixFormControls.checkbox(context, "启用 Skills", store.enabled())
                : new Switch(context);
        enabled.setText("启用 Skills");
        enabled.setTag("skills.enabled");
        enabled.setChecked(store.enabled());
        enabled.setOnCheckedChangeListener(this::changeEnabled);
        content.addView(MiuixFormControls.viewFor(enabled), matchWrap());

        section(content, "自定义技能");
        skillRows = column();
        content.addView(skillRows, matchWrap());
        importJson = button(content, "导入技能 JSON", "skills.import.json", this::chooseSkillJson);
        addSkillRow(content, "Markdown 技能", "导入并管理 SKILL.md", "skills.markdown.manage", this::showMarkdown);

        section(content, "生成或更新技能");
        requirements = input(content, "描述想要的技能，例如生图", "skills.requirements",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 4000);
        generate = button(content, "选择模型生成", "skills.generate.model", this::chooseModel);
        simulate = button(content, "离线模拟生成", "skills.generate.mock", () -> generate(null, null));
        status = text(content, "", "skills.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        try { refreshSkillRows(); }
        catch (RuntimeException failure) { status.setText("暂时无法读取技能设置"); }

        dialog = builder("AI Skills").setView(scroll(content)).setNegativeButton("关闭", null).create();
    }

    AlertDialog dialog() { return dialog; }

    private void chooseSkillJson() {
        if (!active() || busy || importPicker != null) return;
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES,
                        new String[]{"application/json", "text/plain", "application/octet-stream"})
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        setGenerating(true);
        status.setText("");
        importPicker = HostConfigFilePicker.launch(ownerActivity(), intent, uri -> {
            importPicker = null;
            if (!active()) return;
            if (uri == null) {
                setGenerating(false);
                return;
            }
            runImport("正在读取并校验…", "无法导入，请核对技能 JSON 的格式、编码和大小",
                    () -> SkillStore.validateImportedDraft(readSkillJson(uri)), this::reviewImportedDraft);
        });
        if (importPicker == null) {
            setGenerating(false);
            status.setText("无法打开系统文件选择器");
        }
    }

    private String readSkillJson(Uri uri) throws IOException {
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IOException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || count == 0
                        || count > MAX_JSON_IMPORT_BYTES - bytes.size()) {
                    throw new IOException();
                }
                bytes.write(buffer, 0, count);
            }
            return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
        }
    }

    private void reviewImportedDraft(SkillStore.DraftPreview preview) {
        String summary = "/" + preview.name() + "\n" + preview.description()
                + "\n输出 · " + outputLabel(preview.outputType());
        status.setText("");
        presenter.accept(builder("预览导入技能").setMessage(summary).setNegativeButton("取消", null)
                .setPositiveButton("先保存为草稿", (child, which) -> {
                    if (!active() || busy) return;
                    runImport("正在保存草稿…", "无法保存导入草稿，请核对技能名称或现有草稿",
                            () -> store.saveImportedDraft(preview),
                            pending -> refreshAfterChange("已导入草稿 /" + pending.name()));
                }).create(), null);
    }

    private <T> void runImport(String progress, String failure, Callable<T> action, Consumer<T> success) {
        setGenerating(true);
        status.setText(progress);
        try {
            importTask = worker.submit(() -> {
                T imported = null;
                try {
                    if (!Thread.currentThread().isInterrupted()) imported = action.call();
                } catch (Exception unavailable) {
                    // The UI shows a fixed message instead of file content or provider errors.
                }
                T result = imported;
                main.post(() -> {
                    if (!active()) return;
                    importTask = null;
                    setGenerating(false);
                    if (result == null) status.setText(failure);
                    else success.accept(result);
                });
            });
        } catch (RejectedExecutionException unavailable) {
            setGenerating(false);
            status.setText(failure);
        }
    }

    private Activity ownerActivity() {
        Activity owner = dialog.getOwnerActivity();
        if (owner != null) return owner;
        for (Context current = context; current instanceof ContextWrapper wrapper; current = wrapper.getBaseContext()) {
            if (current instanceof Activity activity) return activity;
            if (current == wrapper.getBaseContext()) break;
        }
        return null;
    }

    private void showMarkdown() {
        if (!active() || busy || markdown != null) return;
        AiMarkdownSkillSettingsDialog page = new AiMarkdownSkillSettingsDialog(context, miuix, presenter, worker);
        markdown = page;
        presenter.accept(page.dialog(), () -> {
            page.close();
            if (markdown == page) markdown = null;
        });
    }

    private void changeEnabled(CompoundButton control, boolean value) {
        try { store.setEnabled(value); }
        catch (RuntimeException failure) {
            control.setOnCheckedChangeListener(null);
            control.setChecked(!value);
            control.setOnCheckedChangeListener(this::changeEnabled);
            status.setText("暂时无法保存 Skills 开关");
            return;
        }
        try { refreshSkillRows(); }
        catch (RuntimeException failure) { status.setText("请重新打开设置查看最新技能状态"); }
    }

    private void chooseModel() {
        if (!active() || busy) return;
        String description = description();
        if (description.isEmpty()) return;
        if (SkillStore.requiresImage(description)) {
            generate(null, null);
            return;
        }
        AiModelSettings available;
        try { available = AiModelSettings.read(settings); }
        catch (RuntimeException invalid) {
            status.setText("无法读取模型配置");
            return;
        }
        if (available.configs.isEmpty()) {
            status.setText("请先保存一个模型配置，或使用离线模拟生成");
            return;
        }
        List<AiModelConfig> profiles = available.configs;
        String[] labels = profiles.stream()
                .map(profile -> profile.name + " · " + profile.modelName).toArray(String[]::new);
        presenter.accept(builder("选择接收需求说明的模型")
                .setItems(labels, (child, which) -> generate(available, profiles.get(which).id))
                .setNegativeButton("取消", null).create(), null);
    }

    private String description() {
        String value = requirements.getText().toString().trim();
        if (value.isEmpty()) status.setText("请输入技能需求");
        return value;
    }

    private void generate(AiModelSettings modelSettings, String modelId) {
        if (!active() || busy) return;
        String description = description();
        if (description.isEmpty()) return;
        boolean offline = modelId == null || SkillStore.requiresImage(description);
        ChatCompletionClient.IsolatedTextRequest request = offline ? null : new ChatCompletionClient.IsolatedTextRequest();
        prepareDraft(() -> {
            if (offline) return SkillStore.mockDraft(description);
            String raw = request.execute(modelSettings, modelId, SkillAuthoring.DESIGN_INSTRUCTION,
                    store.generationPrompt(description), System.nanoTime() + TimeUnit.SECONDS.toNanos(45), 2048);
            return SkillStore.validateAuthoredDraft(raw).definition();
        }, request);
    }

    private void prepareDraft(Callable<String> definition, ChatCompletionClient.IsolatedTextRequest request) {
        generation = request;
        setGenerating(true);
        status.setText("正在生成并校验…");
        generationTask = worker.submit(() -> {
            try {
                String draft = definition.call();
                SkillStore.Preview preview = store.previewGenerated(draft);
                main.post(() -> finishGeneration(request, draft, preview));
            } catch (Exception failure) {
                main.post(() -> finishGeneration(request, null, null));
            }
        });
    }

    private void finishGeneration(ChatCompletionClient.IsolatedTextRequest request, String draft, SkillStore.Preview preview) {
        if (!active() || generation != request) return;
        generation = null;
        generationTask = null;
        setGenerating(false);
        if (preview == null) {
            status.setText("生成或校验失败，已安装的技能版本保留");
        } else if (preview.pendingReason().isEmpty() && preview.needsConfirmation()) {
            reviewDraft(draft, preview);
        } else {
            save(draft, null);
        }
    }

    private void reviewDraft(String draft, SkillStore.Preview preview) {
        boolean updating = preview.previousVersion() > 0;
        String details = "技能 /" + preview.name();
        if (updating) details += "\n启用新版将替换当前版本 v" + preview.previousVersion() + "。";
        if (preview.serviceLabel() != null && !preview.serviceLabel().isEmpty()) {
            details += "\n" + target(preview.serviceId(), preview.serviceLabel(), preview.endpoint(),
                    preview.costUnits(), preview.outputType());
        } else {
            details += "\n输出 " + outputLabel(preview.outputType());
        }
        status.setText("");
        presenter.accept(builder(updating ? "确认更新技能" : "确认技能的服务权限")
                .setMessage(details).setNegativeButton("取消", null)
                .setPositiveButton(updating ? "启用新版" : "确认并启用", (child, which) -> save(draft, preview))
                .create(), null);
    }

    private void save(String draft, SkillStore.Preview confirmation) {
        if (!active()) return;
        SkillStore.SaveResult result;
        try {
            result = store.saveGenerated(draft, confirmation);
        } catch (Exception failure) {
            status.setText("技能试跑或保存失败，已安装的技能版本保留");
            return;
        }
        SkillStore.Pending pending = result.pending();
        if (pending != null) {
            refreshAfterChange("已保存草稿 /" + pending.name());
            return;
        }
        SkillStore.Installed installed = result.installed();
        String service = installed.serviceLabel();
        refreshAfterChange("已安装 /" + installed.name() + " · v" + installed.version()
                + (service == null || service.isEmpty() ? "" : " · " + service));
    }

    private void setGenerating(boolean busy) {
        this.busy = busy;
        generate.setEnabled(!busy);
        simulate.setEnabled(!busy);
        importJson.setEnabled(!busy);
        requirements.setEnabled(!busy);
    }

    private void refreshSkillRows() {
        SkillStore.SettingsSnapshot snapshot = store.settingsSnapshot();
        skillRows.removeAllViews();
        if (snapshot.rows().isEmpty()) text(skillRows, "暂无自定义技能", null);
        for (SkillStore.SettingsRow skill : snapshot.rows()) {
            SkillStore.Installed current = skill.current();
            String summary = current == null ? "尚未启用" : "当前 v" + current.version()
                    + " · " + outputLabel(current.outputType()) + " · " + installedState(current, snapshot.enabled());
            if (skill.draftState() != null) {
                summary += "\n草稿 · " + outputLabel(skill.draftOutputType()) + " · " + draftState(skill.draftState());
            }
            String name = skill.name();
            String title = "/" + name;
            if (!name.equals(skill.displayName())) {
                summary = title + "\n" + summary;
                title = skill.displayName();
            }
            addSkillRow(skillRows, title, summary, "skills.row." + name, () -> showSkillDetail(name));
        }
    }

    private void addSkillRow(LinearLayout parent, String title, String summary, String tag, Runnable action) {
        View row;
        if (miuix) {
            row = new MiuixSettingsViews.Option(context, title, summary, null, true, action).getView();
        } else {
            LinearLayout item = column();
            item.setPadding(dp(12), dp(10), dp(12), dp(10));
            item.setMinimumHeight(dp(48));
            TypedValue background = new TypedValue();
            if (context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, background, true)
                    && background.resourceId != 0) item.setBackgroundResource(background.resourceId);
            text(item, title, null).setTypeface(null, Typeface.BOLD);
            text(item, summary, null);
            item.setFocusable(true);
            item.setOnClickListener(view -> action.run());
            row = item;
        }
        row.setTag(tag);
        parent.addView(row, matchWrap());
    }

    static String installedState(SkillStore.Installed current, boolean enabled) {
        if (!current.configurationRequired().isEmpty()) return "待配置";
        return !current.enabled() ? "已停用" : enabled ? "已启用" : "总开关已关闭";
    }

    static String draftState(SkillStore.DraftState state) {
        return switch (state) {
            case NEEDS_CONFIGURATION -> "待配置";
            case NEEDS_AUTHORIZATION -> "待授权";
            case READY -> "待启用";
            case DRAFT -> "待检查";
        };
    }

    private void refreshAfterChange(String message) {
        status.setText(message);
        try { refreshSkillRows(); }
        catch (RuntimeException failure) {
            status.setText("已保存，请重新打开设置查看最新状态");
        }
    }

    private void showSkillDetail(String name) {
        if (!active()) return;
        AiSkillDetailDialog page = new AiSkillDetailDialog(context, miuix, name, () -> {
            if (active()) refreshAfterChange("");
        });
        presenter.accept(page.dialog(), page::close);
    }

    private static String target(String id, String label, String endpoint, int units, SkillResult.Type type) {
        String destination = SkillServices.MOCK_IMAGE.equals(id) ? "本地模拟" : "目标 " + endpoint;
        return label + "\n输出 " + outputLabel(type) + "\n" + destination + "\n每次调用最多 " + units + " 费用单位";
    }

    private static String outputLabel(SkillResult.Type type) { return type.label(); }

    private AlertDialog.Builder builder(String title) { return new MiuixDialogBuilder(context, miuix).setTitle(title); }

    private boolean active() { return !closed && dialog.isShowing(); }

    private LinearLayout column() {
        LinearLayout result = new LinearLayout(context);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }

    private LinearLayout form() {
        LinearLayout result = column();
        int padding = dp(16);
        result.setPadding(padding, 0, padding, padding);
        return result;
    }

    private ScrollView scroll(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(child);
        return scroll;
    }

    private void section(LinearLayout parent, String label) {
        TextView title = text(parent, label, null);
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        title.setPadding(0, dp(16), 0, dp(6));
    }

    private TextView text(LinearLayout parent, String value, String tag) {
        TextView result = new TextView(context);
        result.setText(value);
        result.setTag(tag);
        parent.addView(result, matchWrap());
        return result;
    }

    private Button button(LinearLayout parent, String label, String tag, Runnable action) {
        Button button = miuix ? MiuixFormControls.button(context) : new Button(context);
        button.setText(label);
        button.setTag(tag);
        button.setOnClickListener(view -> action.run());
        parent.addView(MiuixFormControls.viewFor(button), matchWrap());
        return button;
    }

    private EditText input(LinearLayout parent, String label, String tag, int type, int length) {
        EditText input = miuix ? MiuixFormControls.input(context, label, "", false) : new EditText(context);
        input.setContentDescription(label);
        input.setHint(label);
        input.setTag(tag);
        input.setInputType(type);
        input.setMaxLines((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ? 4 : 1);
        input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(length)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (markdown != null) {
            markdown.close();
            markdown.dialog().dismiss();
            markdown = null;
        }
        if (generation != null) generation.cancel();
        if (generationTask != null) generationTask.cancel(true);
        if (importPicker != null) importPicker.close();
        if (importTask != null) importTask.cancel(true);
        worker.shutdownNow();
    }
}
