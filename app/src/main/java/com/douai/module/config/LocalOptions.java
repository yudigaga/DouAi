package com.douai.module.config;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** Immutable host-local snapshot for independently registered feature options. */
public final class LocalOptions {
    /** Reserves feed long presses for module actions and blocks all native long-press regions. */
    public static final String INTERCEPT_NATIVE_LONG_PRESS = "intercept_native_long_press";
    public static final String MODULE_ONLY_SPEED = "module_only_speed";
    public static final String DOWNLOAD_SAVE_TREE = "download_directory_uri";
    public static final String OFFICIAL_DOWNLOAD_DIRECT = "download_official_direct";
    public static final String REPLY_VOICE = "reply_voice";
    public static final String REPLY_RECOGNIZE_IMAGES = "reply_recognize_images";
    public static final String REPLY_RECOGNIZE_VIDEO = "reply_recognize_video";
    public static final String COMMENT_VOICE = "comment_voice";
    public static final String DEBUG_LOGGING = "debug_logging";
    public static final String HASH_LONG_PRESS_VOICE = "hash_long_press_voice";
    /** Receives comment replies as a channel of native_auto_reply, like private/group chat reception. */
    public static final String COMMENT_REPLY_ENABLED = "native_comment_reply";

    public String downloadSaveTree() { return text(DOWNLOAD_SAVE_TREE, "").trim(); }
    public boolean officialDownloadDirect() { return enabled(OFFICIAL_DOWNLOAD_DIRECT); }

    public boolean samePrefix(LocalOptions other, String prefix) {
        Map<String, Object> left = new HashMap<>();
        Map<String, Object> right = new HashMap<>();
        values.forEach((key, value) -> { if (key.startsWith(prefix)) left.put(key, value); });
        other.values.forEach((key, value) -> { if (key.startsWith(prefix)) right.put(key, value); });
        return left.equals(right);
    }

    private final Map<String, ?> values;
    private volatile String diagnosticRevision;

    public LocalOptions(Map<String, ?> values) {
        this.values = Collections.unmodifiableMap(new HashMap<>(values));
    }

    /** Revision of this immutable snapshot, independent of later preference publications. */
    public String diagnosticRevision() {
        String cached = diagnosticRevision;
        if (cached != null) return cached;
        synchronized (this) {
            if (diagnosticRevision == null) {
                diagnosticRevision = com.douai.module.diagnostics.DouAiLog.configurationRevision(values);
            }
            return diagnosticRevision;
        }
    }

    public static LocalOptions readFrom(SharedPreferences preferences) {
        return new LocalOptions(preferences.getAll());
    }

    public LocalOptions withDefault(String key, Object value) {
        if (values.containsKey(key)) return this;
        Map<String, Object> copy = new HashMap<>(values);
        copy.put(key, value);
        return new LocalOptions(copy);
    }

    public boolean enabled(String key) { return enabled(key, false); }

    public boolean enabled(String key, boolean fallback) {
        if ((INTERCEPT_NATIVE_LONG_PRESS.equals(key) || "block_long_press_speed".equals(key))
                && moduleOnlySpeedControl()) return true;
        Object value = values.get(key);
        return value instanceof Boolean ? (Boolean) value : fallback;
    }

    public boolean moduleOnlySpeedControl() {
        return Boolean.TRUE.equals(values.get(MODULE_ONLY_SPEED));
    }

    public boolean interceptNativeLongPress() {
        return enabled(INTERCEPT_NATIVE_LONG_PRESS);
    }

    public String text(String key, String fallback) {
        Object value = values.get(key);
        return value instanceof String ? (String) value : fallback;
    }

    public int integer(String key, int fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).intValue()
                    : value instanceof String ? Integer.parseInt(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public long longValue(String key, long fallback) {
        Object value = values.get(key);
        try {
            return value instanceof Number ? ((Number) value).longValue()
                    : value instanceof String ? Long.parseLong(((String) value).trim()) : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }

    public float decimal(String key, float fallback) {
        Object value = values.get(key);
        try {
            float parsed = value instanceof Number ? ((Number) value).floatValue()
                    : value instanceof String ? Float.parseFloat(((String) value).trim()) : fallback;
            return Float.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException ignored) { return fallback; }
    }
}
