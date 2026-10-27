package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.ViewGroup;
import android.widget.LinearLayout;

import com.douai.module.config.AutomationMessageSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.feature.VoiceCatalog;
import com.douai.module.feature.VoiceNameTranslationCache;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixSettingsViews;

import java.util.Map;
import java.util.function.Consumer;

/** Compact gesture panel for frequently used comment, reply, and speech settings. */
public final class GestureQuickFeaturesDialog {
    private GestureQuickFeaturesDialog() { }

    public static AlertDialog create(Activity activity, SettingsStore store, Runnable switchVoice,
                                     Consumer<String> onError) {
        AutomationMessageSettings updates = new AutomationMessageSettings(store, null);
        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(activity, 18), dp(activity, 8), dp(activity, 18), dp(activity, 8));
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);

        MiuixSettingsViews.Toggle autoComment = new MiuixSettingsViews.Toggle(activity, "自动评论",
                "对匹配的推荐内容自动评论。", store.getBoolean("native_auto_comment", false), true,
                enabled -> setEnabled(updates, "comment", enabled, onError));
        content.addView(autoComment.getView(), row);
        MiuixSettingsViews.Toggle autoReply = new MiuixSettingsViews.Toggle(activity, "自动回复",
                "对符合当前接收范围的私信自动回复。", store.getBoolean("native_auto_reply", false), true,
                enabled -> setEnabled(updates, "reply", enabled, onError));
        content.addView(autoReply.getView(), row);

        SpeechSettings speech = speechSettings(store);
        String voice = speech.fish ? fishVoiceSummary(speech.fishSettings)
                : speech.breeze ? speech.breezeSettings.voiceName.isEmpty() ? speech.breezeSettings.voiceId
                : speech.breezeSettings.voiceName + " · " + speech.breezeSettings.voiceId
                : speech.minimax ? speech.minimaxVoiceName.isEmpty() ? speech.minimaxVoiceId
                : speech.minimaxVoiceName + " · " + speech.minimaxVoiceId
                : speech.thirdParty ? speech.selectedVoiceName.isEmpty() ? speech.selectedVoice
                : speech.selectedVoiceName + " · " + speech.selectedVoice : "系统语音引擎";
        if (speech.breeze || speech.fish && "single".equals(speech.fishSettings.voiceMode)) {
            String id = speech.breeze ? speech.breezeSettings.voiceId : speech.fishSettings.referenceId;
            String original = speech.breeze ? speech.breezeSettings.voiceName : speech.fishSettings.referenceName;
            if (!id.isEmpty() && !original.isEmpty()) {
                try {
                    VoiceCatalog.Entry entry = new VoiceCatalog.Entry(id, original);
                    voice = VoiceNameTranslationCache.read(store).displayName(VoiceNameTranslationCache.scope(speech), entry)
                            + " · " + id;
                } catch (IllegalArgumentException ignored) { /* Keep the existing summary for an unfinished draft. */ }
            }
        }
        MiuixSettingsViews.Option voiceOption = new MiuixSettingsViews.Option(activity, "切换音色",
                speech.fish ? "Fish Audio" : speech.breeze ? "Breeze Blue" : speech.minimax ? "MiniMax" : speech.thirdParty ? "第三方语音" : "系统语音引擎",
                voice, true, switchVoice);
        content.addView(voiceOption.getView(), row);

        AlertDialog dialog = new MiuixDialogBuilder(activity, true).setTitle("功能快捷")
                .setView(content).setNegativeButton("关闭", null).create();
        dialog.setOnDismissListener(ignored -> updates.close());
        return dialog;
    }

    private static void setEnabled(AutomationMessageSettings settings, String kind,
                                   boolean enabled, Consumer<String> onError) {
        try {
            settings.setEnabled(kind, enabled);
        } catch (RuntimeException failure) {
            if (onError != null) onError.accept(failure.getMessage() == null ? "设置保存失败" : failure.getMessage());
        }
    }

    private static String fishVoiceSummary(FishSpeechSettings fish) {
        if ("multi".equals(fish.voiceMode)) return "多说话人 · " + fish.referenceIds.size() + " 位";
        if ("references".equals(fish.voiceMode)) {
            try { return "参考音频 · " + fish.referenceGroups().size() + " 个分组"; }
            catch (RuntimeException invalid) { return "参考音频 · 待重新配置"; }
        }
        return fish.referenceId.isEmpty() ? "接口默认音色" : fish.referenceName.isEmpty()
                ? fish.referenceId : fish.referenceName + " · " + fish.referenceId;
    }

    private static SpeechSettings speechSettings(SettingsStore store) {
        try {
            Map<String, ?> snapshot = store.snapshot();
            return new SpeechSettings(new LocalOptions(snapshot));
        } catch (RuntimeException unavailable) {
            return new SpeechSettings(new LocalOptions(java.util.Collections.emptyMap()));
        }
    }

    private static int dp(Activity activity, int value) {
        return Math.round(value * activity.getResources().getDisplayMetrics().density);
    }
}
