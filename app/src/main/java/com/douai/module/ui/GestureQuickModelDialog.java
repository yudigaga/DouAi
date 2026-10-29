package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.Toast;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AnalyticsAiSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.util.ArrayList;
import java.util.List;

/** Selects an existing model for one feature from the feed gesture menu. */
public final class GestureQuickModelDialog {
    private GestureQuickModelDialog() { }

    private enum Target {
        GLOBAL("全局默认", null),
        REPLY("自动回复（私聊、群聊、评论回复）", AiModelSettings.REPLY_MODEL_ID),
        COMMENT("自动评论", AiModelSettings.COMMENT_MODEL_ID),
        ANALYSIS("本地数据分析", AnalyticsAiSettings.MODEL_ID);

        final String label;
        final String key;
        Target(String label, String key) { this.label = label; this.key = key; }
    }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable openSettings) {
        try {
            AiModelSettings.read(store);
        } catch (RuntimeException invalid) {
            return new MiuixDialogBuilder(activity, true).setTitle("切换模型")
                    .setMessage("模型配置无法读取，请到模块设置中检查。")
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("模块设置", (dialog, which) -> openSettings.run()).create();
        }
        return new Picker(activity, store, openSettings).create();
    }

    private static final class Picker {
        final Activity activity;
        final SettingsStore store;
        final Runnable openSettings;
        AlertDialog dialog;
        Target target;

        Picker(Activity activity, SettingsStore store, Runnable openSettings) {
            this.activity = activity;
            this.store = store;
            this.openSettings = openSettings;
        }

        AlertDialog create() {
            dialog = new MiuixDialogBuilder(activity, true).setTitle("切换模型")
                    .setSingleChoiceItems(targetLabels(), -1, (parent, index) -> showModels(Target.values()[index]))
                    .setNegativeButton("关闭", null)
                    .setPositiveButton("模块设置", (parent, which) -> openSettings.run()).create();
            dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_NEGATIVE)
                    .setOnClickListener(view -> {
                        if (target == null) dialog.dismiss();
                        else showTargets();
                    }));
            return dialog;
        }

        boolean usable() {
            return dialog.isShowing() && !activity.isFinishing() && !activity.isDestroyed();
        }

        void showTargets() {
            if (!usable()) return;
            target = null;
            dialog.setTitle("切换模型");
            dialog.setMessage(null);
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("关闭");
            MiuixDialogBuilder.updateSingleChoiceItems(dialog, targetLabels(), -1,
                    (parent, index) -> showModels(Target.values()[index]));
        }

        void showModels(Target next) {
            if (!usable()) return;
            try {
                AiModelSettings settings = AiModelSettings.read(store);
                List<AiModelConfig> profiles = new ArrayList<>(settings.configs);
                int offset = next == Target.GLOBAL ? 0 : 1;
                String[] labels = new String[profiles.size() + offset];
                String selectedId = next == Target.GLOBAL ? settings.activeId : store.getString(next.key, "");
                int selected = offset == 1 && selectedId.isEmpty() ? 0 : -1;
                if (offset == 1) labels[0] = "跟随全局默认";
                for (int index = 0; index < profiles.size(); index++) {
                    AiModelConfig config = profiles.get(index);
                    labels[index + offset] = config.name + " · " + config.modelName
                            + (config.streaming() ? " · 流式" : " · 非流式");
                    if (config.id.equals(selectedId)) selected = index + offset;
                }
                target = next;
                dialog.setTitle("切换模型 · " + next.label);
                dialog.setMessage(profiles.isEmpty() ? "尚未添加 AI 模型配置，请到模块设置中保存配置。" : null);
                dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setText("返回");
                MiuixDialogBuilder.updateSingleChoiceItems(dialog, labels, selected, (parent, index) -> {
                    if (!usable()) return;
                    String modelId = offset == 1 && index == 0 ? "" : profiles.get(index - offset).id;
                    try {
                        select(store, next, modelId);
                        String feedback = modelId.isEmpty() ? next.label + "已跟随全局默认"
                                : next.label + "已选择 " + profiles.get(index - offset).name;
                        Toast.makeText(activity, feedback, Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    } catch (RuntimeException failure) {
                        failure(failure);
                        showModels(next);
                    }
                });
            } catch (RuntimeException failure) { failure(failure); }
        }

        void failure(RuntimeException failure) {
            Toast.makeText(activity, failure.getMessage() == null ? "模型切换失败" : failure.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }

    private static String[] targetLabels() {
        Target[] targets = Target.values();
        String[] labels = new String[targets.length];
        for (int index = 0; index < targets.length; index++) labels[index] = targets[index].label;
        return labels;
    }

    private static void select(SettingsStore store, Target target, String modelId) {
        AiModelSettings settings = AiModelSettings.read(store);
        if (target != Target.GLOBAL && modelId.isEmpty()) {
            store.putString(target.key, "");
            return;
        }
        AiModelConfig profile = null;
        for (AiModelConfig candidate : settings.configs) if (candidate.id.equals(modelId)) profile = candidate;
        if (profile == null) throw new IllegalStateException("所选模型配置已不存在，请重新选择");
        profile.validate(true);
        if (target == Target.GLOBAL) settings.activate(profile.id).save(store);
        else store.putString(target.key, profile.id);
    }

    static void activate(SettingsStore store, String modelId) {
        select(store, Target.GLOBAL, modelId);
    }
}
