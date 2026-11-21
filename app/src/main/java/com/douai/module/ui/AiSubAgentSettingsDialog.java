package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.ai.skills.AiSubAgents;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.util.ArrayList;
import java.util.List;

final class AiSubAgentSettingsDialog {
    private final AlertDialog dialog;

    AiSubAgentSettingsDialog(Context context, SettingsStore store, boolean miuix) {
        AiSubAgents.Selection current = AiSubAgents.read(store);
        List<AiModelConfig> models = AiModelSettings.read(store).configs;
        LinearLayout body = new LinearLayout(context);
        body.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(16 * context.getResources().getDisplayMetrics().density);
        body.setPadding(padding, 0, padding, padding);
        text(body, "在侧栏的 AI 对话输入 /委派 任务。");
        text(body, "委派模型");
        Spinner model = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        model.setTag("subagent.model");
        model.setContentDescription("委派模型");
        List<String> labels = new ArrayList<>();
        labels.add("关闭子代理");
        int selected = 0;
        for (AiModelConfig profile : models) {
            labels.add(profile.name + " · " + profile.modelName);
            if (profile.id.equals(current.modelId())) selected = labels.size() - 1;
        }
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        model.setAdapter(adapter);
        model.setSelection(selected);
        body.addView(MiuixFormControls.viewFor(model));

        TextView allowed = text(body, "允许使用的本地工具");
        allowed.setAccessibilityHeading(true);
        allowed.setPadding(0, padding, 0, 0);
        CompoundButton summary = tool(body, miuix, "摘要", "subagent.summary",
                selected != 0 && current.summaryAllowed());
        CompoundButton mockOrder = tool(body, miuix, "查询模拟订单", "subagent.mockOrder",
                selected != 0 && current.mockOrderAllowed());
        TextView status = text(body, "");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        status.setVisibility(View.GONE);
        if (current.enabled() && selected == 0) showStatus(status, "原先选择的模型已不存在，请重新选择。");

        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        dialog = new MiuixDialogBuilder(context, miuix).setTitle("子代理").setView(scroll)
                .setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!dialog.isShowing()) return;
            int index = model.getSelectedItemPosition();
            String id = index <= 0 ? "" : models.get(index - 1).id;
            try {
                if (!id.isEmpty() && AiModelSettings.read(store).configs.stream().noneMatch(profile -> profile.id.equals(id))) {
                    showStatus(status, "所选模型已不存在，请关闭后重新打开设置。");
                    return;
                }
                AiSubAgents.save(store, new AiSubAgents.Selection(id, summary.isChecked(), mockOrder.isChecked()));
            } catch (RuntimeException failure) {
                showStatus(status, "无法保存子代理设置。");
                return;
            }
            dialog.dismiss();
        }));
    }

    AlertDialog dialog() { return dialog; }

    private static void showStatus(TextView status, String message) {
        status.setText(message);
        status.setVisibility(View.VISIBLE);
    }

    private static TextView text(LinearLayout parent, String value) {
        TextView text = new TextView(parent.getContext());
        text.setText(value);
        parent.addView(text);
        return text;
    }

    private static CompoundButton tool(LinearLayout parent, boolean miuix, String label, String tag, boolean checked) {
        Context context = parent.getContext();
        CompoundButton button = miuix ? MiuixFormControls.checkbox(context, label, checked) : new Switch(context);
        button.setText(label);
        button.setTag(tag);
        button.setChecked(checked);
        parent.addView(MiuixFormControls.viewFor(button));
        return button;
    }
}
