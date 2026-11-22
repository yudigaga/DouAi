package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

final class AiSkillSettingsDialog implements AutoCloseable {
    private final Context context;
    private final SettingsStore settings;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final SkillStore store;
    private final SkillServices services;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final AlertDialog dialog;
    private final EditText requirements;
    private final TextView status;
    private final Button generate;
    private final Button simulate;
    private final LinearLayout pendingRows;
    private final LinearLayout installedRows;
    private final LinearLayout serviceRows;
    private ChatCompletionClient.IsolatedTextRequest generation;
    private Future<?> generationTask;
    private boolean busy;
    private boolean closed;

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
        services = SkillServices.forContext(context);

        LinearLayout content = form();
        CompoundButton enabled = miuix ? MiuixFormControls.checkbox(context, "启用 Skills", store.enabled())
                : new Switch(context);
        enabled.setText("启用 Skills");
        enabled.setTag("skills.enabled");
        enabled.setChecked(store.enabled());
        enabled.setOnCheckedChangeListener(this::changeEnabled);
        content.addView(MiuixFormControls.viewFor(enabled), matchWrap());

        section(content, "生成或更新技能");
        requirements = input(content, "描述想要的技能，例如生图", "skills.requirements",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE, 4000, false);
        generate = button(content, "选择模型生成", "skills.generate.model", this::chooseModel);
        simulate = button(content, "离线模拟生成", "skills.generate.mock", () -> generate(null, null));
        status = text(content, "", "skills.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        pendingRows = column();
        content.addView(pendingRows, matchWrap());

        section(content, "自定义技能");
        installedRows = column();
        content.addView(installedRows, matchWrap());

        section(content, "技能服务");
        serviceRows = column();
        content.addView(serviceRows, matchWrap());
        button(content, "登记或更新服务", "skills.service.register", this::showServiceEditor);
        try { refresh(); }
        catch (SkillServices.Failure failure) { status.setText("无法读取服务登记"); }

        dialog = builder("AI Skills").setView(scroll(content)).setNegativeButton("关闭", null).create();
    }

    AlertDialog dialog() { return dialog; }

    private void changeEnabled(CompoundButton control, boolean value) {
        try { store.setEnabled(value); }
        catch (RuntimeException failure) {
            control.setOnCheckedChangeListener(null);
            control.setChecked(!value);
            control.setOnCheckedChangeListener(this::changeEnabled);
            status.setText("暂时无法保存 Skills 开关");
        }
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
        prepareDraft(() -> offline ? SkillStore.mockDraft(description) : request.execute(modelSettings, modelId,
                SkillStore.GENERATION_INSTRUCTION, store.generationPrompt(description),
                System.nanoTime() + TimeUnit.SECONDS.toNanos(45), 2048), request, "正在生成并校验…", false);
    }

    private void retryPending(SkillStore.Pending pending) {
        if (!active() || busy) return;
        prepareDraft(() -> store.pendingDefinition(pending.name()), null, "正在检查技能服务…", true);
    }

    private void prepareDraft(Callable<String> definition, ChatCompletionClient.IsolatedTextRequest request, String message, boolean reconnect) {
        generation = request;
        setGenerating(true);
        status.setText(message);
        generationTask = worker.submit(() -> {
            try {
                String draft = definition.call();
                SkillStore.Preview preview = store.previewGenerated(draft);
                main.post(() -> finishGeneration(request, draft, preview, reconnect));
            } catch (Exception failure) {
                main.post(() -> finishGeneration(request, null, null, reconnect));
            }
        });
    }

    private void finishGeneration(ChatCompletionClient.IsolatedTextRequest request, String draft, SkillStore.Preview preview, boolean reconnect) {
        if (!active() || generation != request) return;
        generation = null;
        generationTask = null;
        setGenerating(false);
        if (preview == null) {
            status.setText("生成或校验失败，已安装的技能版本保留");
        } else if (preview.pendingReason().isEmpty() && preview.needsConfirmation()) {
            reviewDraft(draft, preview, reconnect);
        } else {
            save(draft, null, reconnect);
        }
    }

    private void reviewDraft(String draft, SkillStore.Preview preview, boolean reconnect) {
        boolean updating = preview.previousVersion() > 0;
        String details = "技能 /" + preview.name();
        if (updating) details += "\n当前版本 v" + preview.previousVersion() + "，启用新版后可回退。";
        if (preview.serviceLabel() != null && !preview.serviceLabel().isEmpty()) {
            details += "\n" + target(preview.serviceId(), preview.serviceLabel(), preview.endpoint(),
                    preview.costUnits(), preview.outputType());
        } else {
            details += "\n输出 " + outputLabel(preview.outputType());
        }
        status.setText("");
        presenter.accept(builder(updating ? "确认更新技能" : "确认技能的服务权限")
                .setMessage(details).setNegativeButton("取消", null)
                .setPositiveButton(updating ? "启用新版" : "确认并启用", (child, which) -> save(draft, preview, reconnect))
                .create(), null);
    }

    private void save(String draft, SkillStore.Preview confirmation, boolean reconnect) {
        if (!active()) return;
        SkillStore.SaveResult result;
        try {
            result = reconnect ? store.connectPending(draft, confirmation) : store.saveGenerated(draft, confirmation);
        } catch (Exception failure) {
            status.setText("技能试跑或保存失败，已安装的技能版本保留");
            return;
        }
        SkillStore.Pending pending = result.pending();
        if (pending != null) {
            refreshAfterChange("已保存待连接草稿 /" + pending.name() + "\n" + pending.reason());
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
        requirements.setEnabled(!busy);
    }

    private void refresh() throws SkillServices.Failure {
        installedRows.removeAllViews();
        List<SkillStore.Installed> skills = store.list();
        pendingRows.removeAllViews();
        List<SkillStore.Pending> pending = store.pending();
        if (!pending.isEmpty()) section(pendingRows, "待连接草稿");
        for (SkillStore.Pending draft : pending) {
            String tag = "skills.pending." + draft.name();
            String details = "/" + draft.name() + " · " + outputLabel(draft.outputType()) + "\n" + draft.reason();
            if (draft.outputType() == SkillResult.Type.IMAGE) details += "\n请先在技能服务中授权兼容的图片服务。";
            SkillStore.Installed current = skills.stream().filter(skill -> skill.name().equals(draft.name())).findFirst().orElse(null);
            if (current != null) details += "\n已安装 v" + current.version() + (current.enabled() ? " · 已启用" : " · 已停用");
            text(pendingRows, details, tag);
            button(pendingRows, "配置 /" + draft.name(), tag + ".configure", () -> showSkillDetail(draft.name()));
            button(pendingRows, "重试连接", tag + ".retry", () -> retryPending(draft));
        }
        if (skills.isEmpty()) text(installedRows, "暂无自定义技能", null);
        for (SkillStore.Installed skill : skills) {
            String label = "/" + skill.name() + " · " + outputLabel(skill.outputType()) + " · v" + skill.version() + " · "
                    + (skill.enabled() ? "已启用" : "已停用");
            button(installedRows, label, "skills.installed." + skill.name(), () -> showSkillDetail(skill.name()));
        }
        serviceRows.removeAllViews();
        for (SkillServices.Service service : services.list()) {
            String tag = "skills.service." + service.id();
            if (SkillServices.MOCK_ORDERS.equals(service.id())) {
                text(serviceRows, service.label() + "（本地模拟）", tag);
                continue;
            }
            boolean authorized = services.isAuthorized(service);
            boolean localImage = SkillServices.MOCK_IMAGE.equals(service.id());
            text(serviceRows, service.label() + " · " + outputLabel(service.outputType()) + "\n"
                    + (localImage ? "本地模拟" : service.id() + " · " + service.endpoint()), tag);
            if (localImage && authorized) {
                button(serviceRows, "撤销模拟服务授权", tag + ".revoke", () -> revoke(service));
            } else {
                button(serviceRows, authorized ? "已授权" : "授权此服务", tag + ".authorize",
                        () -> authorize(service)).setEnabled(!authorized);
            }
        }
    }

    private void refreshAfterChange(String message) {
        status.setText(message);
        try { refresh(); }
        catch (SkillServices.Failure | RuntimeException failure) {
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

    private void showServiceEditor() {
        if (!active()) return;
        LinearLayout fields = form();
        EditText id = input(fields, "服务标识", "skills.service.id", InputType.TYPE_CLASS_TEXT, 64, false);
        EditText label = input(fields, "显示名称", "skills.service.label", InputType.TYPE_CLASS_TEXT, 80, false);
        EditText endpoint = input(fields, "HTTPS 地址", "skills.service.endpoint",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        Spinner output = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        output.setTag("skills.service.output");
        output.setContentDescription("服务输出类型");
        ArrayAdapter<String> outputs = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{outputLabel(SkillResult.Type.TEXT), outputLabel(SkillResult.Type.IMAGE)});
        outputs.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        output.setAdapter(outputs);
        output.setSelection(0);
        if (!miuix) text(fields, "服务输出类型", null);
        fields.addView(MiuixFormControls.viewFor(output), matchWrap());
        EditText cost = input(fields, "每次调用费用单位", "skills.service.cost", InputType.TYPE_CLASS_NUMBER, 9, false);
        EditText token = input(fields, "Bearer 密钥（可选）", "skills.service.token",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 4096, true);
        token.setSaveEnabled(false);
        token.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        token.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        TextView error = text(fields, "", "skills.service.error");
        error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);

        AlertDialog editor = builder("登记或更新服务").setView(scroll(fields))
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        presenter.accept(editor, () -> token.setText(""));
        if (!editor.isShowing()) return;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!active() || !editor.isShowing()) return;
            try {
                int units = Integer.parseInt(cost.getText().toString().trim());
                SkillServices.Service service = new SkillServices.Service(id.getText().toString().trim(),
                        label.getText().toString().trim(), endpoint.getText().toString().trim(), units,
                        output.getSelectedItemPosition() == 1 ? SkillResult.Type.IMAGE : SkillResult.Type.TEXT);
                services.register(service, token.getText().toString());
            } catch (Exception failure) {
                token.setText("");
                error.setText("无法保存服务，请检查标识、HTTPS 地址和费用单位");
                return;
            }
            token.setText("");
            editor.dismiss();
            refreshAfterChange("服务已登记，请核对目标后授权");
        });
    }

    private void authorize(SkillServices.Service service) {
        if (!active()) return;
        presenter.accept(builder("授权技能服务")
                .setMessage(target(service.id(), service.label(), service.endpoint(), service.costUnits(), service.outputType()))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认授权", (child, which) -> {
                    if (!active()) return;
                    try {
                        services.authorize(service, service.costUnits());
                    } catch (Exception failure) {
                        status.setText("授权失败，请重新核对服务配置");
                        return;
                    }
                    refreshAfterChange("服务已授权");
                }).create(), null);
    }

    private void revoke(SkillServices.Service service) {
        if (!active()) return;
        try { services.revoke(service); }
        catch (SkillServices.Failure failure) {
            status.setText("撤销授权失败");
            return;
        }
        refreshAfterChange("已撤销模拟服务授权");
    }

    private static String target(String id, String label, String endpoint, int units, SkillResult.Type type) {
        String destination = SkillServices.MOCK_IMAGE.equals(id) ? "本地模拟" : "目标 " + endpoint;
        return label + "\n输出 " + outputLabel(type) + "\n" + destination + "\n每次调用最多 " + units + " 费用单位";
    }

    private static String outputLabel(SkillResult.Type type) { return type == SkillResult.Type.IMAGE ? "图片" : "文本"; }

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

    private EditText input(LinearLayout parent, String label, String tag, int type, int length, boolean secret) {
        EditText input = miuix && !secret ? MiuixFormControls.input(context, label, "", false) : new EditText(context);
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
        if (generation != null) generation.cancel();
        if (generationTask != null) generationTask.cancel(true);
        worker.shutdownNow();
    }
}
