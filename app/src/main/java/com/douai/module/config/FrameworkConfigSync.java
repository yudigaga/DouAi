package com.douai.module.config;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.service.XposedService;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.Map;
import java.util.Set;

/** Publishes local controls to the target process through libxposed remote preferences. */
public final class FrameworkConfigSync {
    public static final String PREFERENCES_GROUP = "douyin_configuration";

    private final Listener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService syncExecutor = Executors.newSingleThreadExecutor();
    private XposedService service;
    private volatile SettingsRepository settingsRepository;
    private String lastError;
    private long serviceGeneration;

    public FrameworkConfigSync(Listener listener) {
        this.listener = listener;
    }

    public synchronized void onServiceBound(XposedService service) {
        if (service == null) {
            onServiceError("LSPosed 返回了空服务");
            return;
        }
        this.service = service;
        serviceGeneration++;
        lastError = null;
        notifyStatus(true, "LSPosed 服务已连接。", null);
        if (settingsRepository != null) {
            publish(settingsRepository.load());
        }
    }

    public synchronized void onServiceUnbound() {
        service = null;
        serviceGeneration++;
        notifyStatus(false, "LSPosed 服务未连接。", null);
    }

    public synchronized void onServiceError(Throwable error) {
        service = null;
        String message = error == null || error.getMessage() == null
                ? "无法连接 LSPosed 服务"
                : "无法连接 LSPosed 服务：" + error.getMessage();
        onServiceError(message);
    }

    public synchronized void onServiceError(String message) {
        service = null;
        serviceGeneration++;
        lastError = message == null ? "无法连接 LSPosed 服务" : message;
        notifyStatus(false, "LSPosed 服务不可用。", lastError);
    }

    public synchronized boolean publish(FeatureSettings settings) {
        if (service == null) {
            notifyStatus(false, "设置已更新；等待 LSPosed 服务连接后同步。", lastError);
            return false;
        }
        XposedService boundService = service;
        long generation = serviceGeneration;
        syncExecutor.execute(() -> {
            String error = null;
            try {
                SettingsRepository repository = settingsRepository;
                FeatureSettings latestSettings;
                Map<String, ?> localValues;
                if (repository == null) {
                    latestSettings = settings;
                    localValues = java.util.Collections.emptyMap();
                } else synchronized (repository) {
                    // Bulk import/rollback holds this same lock; never mix two different snapshots.
                    latestSettings = repository.load();
                    localValues = repository.snapshot();
                }
                SharedPreferences remote = boundService.getRemotePreferences(PREFERENCES_GROUP);
                SharedPreferences.Editor editor = remote.edit();
                writeSettings(editor, latestSettings, localValues);
                if (!editor.commit()) {
                    throw new IllegalStateException("远程配置写入失败");
                }
            } catch (RuntimeException exception) {
                error = exception.getMessage() == null
                        ? "远程配置写入失败"
                        : "远程配置写入失败：" + exception.getMessage();
            }

            final String result = error;
            synchronized (FrameworkConfigSync.this) {
                if (generation != serviceGeneration || boundService != service) return;
                lastError = result;
            }
            mainHandler.post(() -> {
                synchronized (FrameworkConfigSync.this) {
                    if (generation != serviceGeneration || boundService != service) return;
                }
                if (result == null) {
                    notifyStatus(true, "设置已同步到 LSPosed；宿主应用情况请查看最近配置状态。", null, generation);
                } else {
                    notifyStatus(false, "LSPosed 服务操作失败。", result, generation);
                }
            });
        });
        return true;
    }

    public synchronized void close() {
        service = null;
        serviceGeneration++;
        syncExecutor.shutdownNow();
    }

    public synchronized boolean isConnected() {
        return service != null;
    }

    synchronized void setSettingsRepository(SettingsRepository repository) {
        settingsRepository = repository;
    }

    private void writeSettings(SharedPreferences.Editor editor, FeatureSettings settings,
                               Map<String, ?> localValues) {
        editor.clear(); // This group is a full snapshot: removed import keys must disappear remotely too.
        settings.writeTo(editor);
        for (Map.Entry<String, ?> entry : localValues.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Boolean) editor.putBoolean(entry.getKey(), (Boolean) value);
            else if (value instanceof String) editor.putString(entry.getKey(), (String) value);
            else if (value instanceof Integer) editor.putInt(entry.getKey(), (Integer) value);
            else if (value instanceof Long) editor.putLong(entry.getKey(), (Long) value);
            else if (value instanceof Float) editor.putFloat(entry.getKey(), (Float) value);
            else if (value instanceof Set<?>) {
                @SuppressWarnings("unchecked")
                Set<String> strings = (Set<String>) value;
                editor.putStringSet(entry.getKey(), strings);
            }
        }
        editor.putInt("config_schema_version", 1);
    }

    private void notifyStatus(boolean connected, String message, String error) {
        long generation;
        synchronized (this) { generation = serviceGeneration; }
        notifyStatus(connected, message, error, generation);
    }

    private void notifyStatus(boolean connected, String message, String error, long generation) {
        if (listener != null) {
            mainHandler.post(() -> {
                synchronized (FrameworkConfigSync.this) {
                    if (generation != serviceGeneration) return;
                }
                listener.onStatusChanged(connected, message, error);
            });
        }
    }

    public interface Listener {
        void onStatusChanged(boolean connected, String message, String error);
    }
}
