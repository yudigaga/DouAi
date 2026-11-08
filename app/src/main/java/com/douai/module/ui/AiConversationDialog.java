package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.os.Bundle;
import android.widget.Toast;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.CommentAiSettings;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.feature.HostDouyinSearch;
import com.douai.module.scan.HostAdaptation;

/** Opens a module-owned conversation for a saved model on the host message page. */
public final class AiConversationDialog {
    private AiConversationDialog() { }

    public static Dialog show(Activity activity, SettingsStore store, AiModelConfig requested) {
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return null;
        AiModelConfig config = null;
        for (AiModelConfig saved : AiModelSettings.read(store).configs) {
            if (saved.id.equals(requested.id) && saved.messageContactEnabled) { config = saved; break; }
        }
        if (config == null) return null;
        AiModelConfig snapshot = config;
        HostNativeChatViews nativeViews;
        try { nativeViews = HostNativeChatViews.create(activity); }
        catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            DouAiLog.legacy(5, "DouAi.AiContact", "Native chat views unavailable", failure);
            Toast.makeText(activity, "当前抖音原生聊天界面未适配，请查看模块适配信息", Toast.LENGTH_LONG).show();
            return null;
        }
        AiTestChatDialog dialog = null;
        try {
            dialog = new AiTestChatDialog(activity, snapshot, true, true, nativeViews,
                    manualSearch(activity, store));
            dialog.setOwnerActivity(activity);
            AiTestChatDialog opened = dialog;
            dialog.setContactEditAction(() -> {
                opened.dismiss();
                edit(activity, store, snapshot);
            });
            dialog.show();
            return dialog;
        } catch (RuntimeException | LinkageError failure) {
            if (dialog != null) dialog.dismiss();
            DouAiLog.legacy(5, "DouAi.AiContact", "Native chat could not open", failure);
            Toast.makeText(activity, "原生聊天界面暂时无法打开，请重新进入消息页", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    static DouyinSearch manualSearch(android.content.Context context, SettingsStore store) {
        if (!store.getBoolean(CommentAiSettings.SEARCH, false)) return null;
        if (!store.isInHost()) {
            Toast.makeText(context, "抖音搜索需从抖音内的手动 AI 入口使用，当前继续普通对话", Toast.LENGTH_LONG).show();
            return null;
        }
        try {
            android.content.Context application = context.getApplicationContext();
            DouyinSearch bridge = HostDouyinSearch.create((application == null ? context : application).getClassLoader(),
                    HostAdaptation.currentProfile());
            if (bridge == null) Toast.makeText(context, "当前抖音版本暂不支持 AI 搜索，继续普通对话", Toast.LENGTH_LONG).show();
            return bridge;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            DouAiLog.event("manual_ai_search", "availability", "host_bridge_unavailable",
                    "error_type=" + unavailable.getClass().getSimpleName());
            Toast.makeText(context, "抖音搜索暂不可用，继续普通对话", Toast.LENGTH_LONG).show();
            return null;
        }
    }

    static AiModelEditor contactEditor(Activity activity, SettingsStore store, AiModelConfig config) {
        return new AiModelEditor(activity, store, config, true, () -> { }, false, () -> {
            AiModelSettings current = AiModelSettings.read(store);
            for (AiModelConfig candidate : current.configs) {
                if (candidate.id.equals(config.id) && !candidate.toJson().toString().equals(config.toJson().toString())) {
                    throw new IllegalStateException("模型配置已在其他位置修改，请关闭后重新打开联系人再删除");
                }
            }
            current.delete(config.id).save(store);
        });
    }

    private static void edit(Activity activity, SettingsStore store, AiModelConfig config) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        AiModelEditor editor = contactEditor(activity, store, config);
        Application application = activity.getApplication();
        Application.ActivityLifecycleCallbacks lifecycle = new Application.ActivityLifecycleCallbacks() {
            @Override public void onActivityPaused(Activity owner) {
                if (owner == activity && !HostImagePicker.isPending(owner)
                        && !HostMediaPicker.isPending(owner)) editor.close();
            }
            @Override public void onActivityDestroyed(Activity owner) { if (owner == activity) editor.close(); }
            @Override public void onActivityCreated(Activity owner, Bundle state) { }
            @Override public void onActivityStarted(Activity owner) { }
            @Override public void onActivityResumed(Activity owner) { }
            @Override public void onActivityStopped(Activity owner) { }
            @Override public void onActivitySaveInstanceState(Activity owner, Bundle state) { }
        };
        editor.show((child, close) -> {
            child.setOwnerActivity(activity);
            child.setOnDismissListener(ignored -> {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                close.run();
            });
            application.registerActivityLifecycleCallbacks(lifecycle);
            try {
                child.show();
                HostDialogBounds.install(activity, child, false);
            } catch (RuntimeException failure) {
                application.unregisterActivityLifecycleCallbacks(lifecycle);
                throw failure;
            }
        });
    }
}
