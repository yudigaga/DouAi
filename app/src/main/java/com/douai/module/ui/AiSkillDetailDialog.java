package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

/** Configuration for one exact skill, shared by manual chats and the Skills list. */
final class AiSkillDetailDialog implements AutoCloseable {
    private final Context context;
    private final boolean miuix;
    private final String name;
    private final Runnable onChanged;
    private final SkillStore store;
    private final LinearLayout body;
    private final AlertDialog dialog;
    private AlertDialog confirmation;
    private TextView status;
    private EditText keyInput;
    private boolean closed;

    AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged) {
        this.context = context;
        this.miuix = miuix;
        name = exactSkillName;
        this.onChanged = onChanged;
        store = SkillStore.forContext(context);
        body = column();
        body.setTag("skills.detail." + name);
        int padding = dp(16);
        body.setPadding(padding, 0, padding, padding);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        dialog = builder("技能配置").setView(scroll).setNegativeButton("关闭", null).create();
        reload("");
    }

    AlertDialog dialog() { return dialog; }

    private void reload(String feedback) {
        try {
            SkillStore.Detail detail = store.detail(name);
            if (detail.pending() && !detail.canReplace() && detail.service() != null
                    && detail.fields().stream().allMatch(field -> field.state() == SkillStore.FieldState.FILLED)
                    && !detail.fields().isEmpty()) {
                detail = store.retryConfiguration(name, detail.draftId());
                if (onChanged != null) onChanged.run();
            }
            render(detail, feedback);
        }
        catch (Exception failure) { render(null, "暂时无法读取此技能的配置"); }
    }

    private void render(SkillStore.Detail detail, String feedback) {
        clearKey();
        body.removeAllViews();
        TextView title = text(body, "/" + name, "skills.detail.name");
        title.setTypeface(null, Typeface.BOLD);
        title.setAccessibilityHeading(true);
        if (detail == null) {
            status = text(body, feedback.isEmpty() ? "此技能已不存在" : feedback, "skills.detail.status");
            return;
        }
        String summary = detail.outputType() == SkillResult.Type.IMAGE ? "图片输出" : "文本输出";
        if (detail.pending()) summary += " · 待连接";
        if (detail.previousVersion() > 0) summary += " · v" + detail.previousVersion()
                + (detail.installedEnabled() ? " 已启用" : " 已停用");
        text(body, summary, "skills.detail.summary");
        if (detail.reason() != null && !detail.reason().isEmpty()) text(body, detail.reason(), "skills.detail.reason");
        status = text(body, feedback, "skills.detail.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        if (detail.pending()) {
            for (SkillStore.Field field : detail.fields()) {
                text(body, field.label() + " · " + fieldState(field), "skills.detail.field." + field.id());
            }
            pendingActions(detail);
        } else if (detail.service() != null) {
            text(body, detail.service().label(), "skills.detail.service");
        }
        if (detail.previousVersion() > 0) installedActions(detail);
    }

    private void pendingActions(SkillStore.Detail detail) {
        if (detail.outputType() == SkillResult.Type.IMAGE && !detail.canReplace()) connectionForm(detail);
        if (detail.canReplace()) {
            button(body, "启用新版", "skills.detail.replace", () -> review("确认更新技能",
                    "/" + name + " 当前版本 v" + detail.previousVersion() + "。启用新版后可回退。"
                            + (detail.service() == null ? "" : "\n\n" + target(detail.service())),
                    "启用新版", () -> apply(() -> store.activateReplacement(name, detail.draftId(), detail.previousVersion()),
                            "技能已更新", "启用新版失败，请重新核对该技能的连接状态")));
        } else {
            button(body, "重试连接", "skills.detail.retry", () -> apply(
                    () -> store.retryConfiguration(name, detail.draftId()), "已检查连接状态", "重试失败，请核对配置后再试"));
        }
    }

    private void connectionForm(SkillStore.Detail detail) {
        SkillServices.Service saved = detail.service();
        boolean savedMock = saved != null && saved.adapter() == SkillServices.Adapter.MOCK_IMAGE;
        boolean savedOpenAi = saved != null && saved.adapter() == SkillServices.Adapter.OPENAI_IMAGES;
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        Spinner adapter = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        adapter.setTag("skills.detail.adapter");
        adapter.setContentDescription("图片服务");
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{"OpenAI Images 兼容服务", "本地模拟图片"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter.setAdapter(options);
        adapter.setSelection(savedMock ? 1 : 0);
        if (!miuix) text(body, "图片服务", null);
        body.addView(MiuixFormControls.viewFor(adapter), matchWrap());

        LinearLayout fields = column();
        fields.setTag("skills.detail.openai");
        body.addView(fields, matchWrap());
        EditText endpoint = input(fields, "HTTPS 生图接口", "skills.detail.endpoint",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        EditText model = input(fields, "模型名称", "skills.detail.model", InputType.TYPE_CLASS_TEXT, 256, false);
        EditText cost = input(fields, "每次调用费用单位", "skills.detail.cost", InputType.TYPE_CLASS_NUMBER, 7, false);
        EditText key = input(fields, "API 密钥", "skills.detail.key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 2048, true);
        key.setSaveEnabled(false);
        key.setSaveFromParentEnabled(false);
        key.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        keyInput = key;
        if (savedOpenAi) {
            endpoint.setText(saved.endpoint());
            model.setText(saved.modelName());
        }
        cost.setText(Integer.toString(savedOpenAi ? saved.costUnits() : 0));
        fields.setVisibility(savedMock ? View.GONE : View.VISIBLE);
        adapter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                fields.setVisibility(position == 1 ? View.GONE : View.VISIBLE);
                if (position == 1) key.getText().clear();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });

        button(body, "保存配置", "skills.detail.save", () -> {
            boolean mock = adapter.getSelectedItemPosition() == 1;
            SkillServices.Adapter selected = mock ? SkillServices.Adapter.MOCK_IMAGE : SkillServices.Adapter.OPENAI_IMAGES;
            int units;
            try { units = mock ? 0 : Integer.parseInt(cost.getText().toString().trim()); }
            catch (NumberFormatException invalid) { status.setText("请输入有效的每次调用费用单位"); return; }
            String bearer = mock ? "" : key.getText().toString();
            key.getText().clear();
            apply(() -> store.saveConfiguration(name, detail.draftId(), selected,
                            mock ? "" : endpoint.getText().toString().trim(), mock ? "" : model.getText().toString().trim(), units, bearer),
                    "配置已保存", "保存失败，请核对 HTTPS 地址、模型名称、密钥和费用单位");
        });
        if (detail.canAuthorize() && saved != null) {
            button(body, "授权已保存的服务", "skills.detail.authorize", () -> {
                boolean mockSelected = adapter.getSelectedItemPosition() == 1;
                boolean changed = (savedMock != mockSelected) || (savedOpenAi && (
                        !saved.endpoint().equals(endpoint.getText().toString().trim())
                                || !saved.modelName().equals(model.getText().toString().trim())
                                || !Integer.toString(saved.costUnits()).equals(cost.getText().toString().trim())
                                || key.length() != 0));
                if (changed) { status.setText("请先保存当前配置"); return; }
                review("授权此技能的服务", target(saved), "确认授权", () -> apply(
                        () -> store.authorizeConfiguration(name, detail.draftId(), saved), "授权已保存",
                        "授权或启用失败，请重新核对服务和费用"));
            });
        }
    }

    private void installedActions(SkillStore.Detail detail) {
        button(body, detail.installedEnabled() ? "停用已安装版本" : "启用已安装版本", "skills.detail.toggle", () -> apply(() -> {
            store.setSkillEnabled(name, !detail.installedEnabled());
            return store.detail(name);
        }, "状态已更新", "暂时无法更新技能状态"));
        if (detail.canRollback()) button(body, "回退上一版本", "skills.detail.rollback", () -> apply(() -> {
            store.rollback(name);
            return store.detail(name);
        }, "已回退上一版本", "回退失败，请重新打开此技能"));
    }

    @FunctionalInterface private interface Operation { SkillStore.Detail run() throws Exception; }

    private void apply(Operation operation, String feedback, String failure) {
        if (!active()) return;
        try {
            SkillStore.Detail updated = operation.run();
            render(updated, feedback);
        } catch (Exception unavailable) {
            status.setText(failure);
            return;
        }
        if (onChanged != null) onChanged.run();
    }

    private void review(String title, String message, String label, Runnable action) {
        if (!active()) return;
        if (confirmation != null) confirmation.dismiss();
        AlertDialog child = builder(title).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton(label, (ignored, which) -> { if (active()) action.run(); }).create();
        confirmation = child;
        if (dialog.getOwnerActivity() != null) child.setOwnerActivity(dialog.getOwnerActivity());
        child.setOnDismissListener(ignored -> { if (confirmation == child) confirmation = null; });
        child.show();
    }

    private boolean active() { return !closed && dialog.isShowing(); }

    private static String fieldState(SkillStore.Field field) {
        switch (field.state()) {
            case FILLED: return "已填写";
            case NEEDS_AUTHORIZATION: return "待授权";
            default: return field.required() ? "待填写" : "未填写";
        }
    }

    private static String target(SkillServices.Service service) {
        if (service.adapter() == SkillServices.Adapter.MOCK_IMAGE) return service.label() + "\n本地模拟 · 无费用";
        return service.label() + "\n目标 " + service.endpoint() + "\n模型 " + service.modelName()
                + "\n每次调用最多 " + service.costUnits() + " 费用单位";
    }

    private void clearKey() {
        if (keyInput != null) keyInput.getText().clear();
        keyInput = null;
    }

    private AlertDialog.Builder builder(String title) { return new MiuixDialogBuilder(context, miuix).setTitle(title); }

    private LinearLayout column() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    private TextView text(LinearLayout parent, String value, String tag) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTag(tag);
        view.setPadding(0, dp(4), 0, dp(4));
        parent.addView(view, matchWrap());
        return view;
    }

    private Button button(LinearLayout parent, String title, String tag, Runnable action) {
        Button view = miuix ? MiuixFormControls.button(context) : new Button(context);
        view.setText(title);
        view.setTag(tag);
        view.setOnClickListener(ignored -> { if (active()) action.run(); });
        parent.addView(MiuixFormControls.viewFor(view), matchWrap());
        return view;
    }

    private EditText input(LinearLayout parent, String label, String tag, int type, int limit, boolean secret) {
        EditText view = miuix && !secret ? MiuixFormControls.input(context, label, "", false) : new EditText(context);
        view.setContentDescription(label);
        view.setHint(label);
        view.setTag(tag);
        view.setInputType(type);
        view.setMaxLines(1);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        parent.addView(MiuixFormControls.viewFor(view), matchWrap());
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        clearKey();
        if (confirmation != null) confirmation.dismiss();
        confirmation = null;
    }
}
