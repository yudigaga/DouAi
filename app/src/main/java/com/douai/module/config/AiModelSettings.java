package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Versioned model-profile collection stored under a dedicated preference key. */
public final class AiModelSettings {
    public static final String KEY = "ai_model_settings_v2";
    public static final String REPLY_MODEL_ID = "reply_model_id";
    public static final String COMMENT_MODEL_ID = "comment_model_id";
    public final List<AiModelConfig> configs;
    public final String activeId;

    public AiModelSettings(List<AiModelConfig> configs, String activeId) {
        if (configs.size() > 50) throw new IllegalArgumentException("模型配置最多 50 项");
        HashSet<String> ids = new HashSet<>();
        for (AiModelConfig config : configs) {
            if (config.id.isEmpty() || !ids.add(config.id)) throw new IllegalArgumentException("模型配置标识重复或为空");
        }
        this.configs = Collections.unmodifiableList(new ArrayList<>(configs));
        this.activeId = activeId == null ? "" : activeId;
        if (!this.activeId.isEmpty() && !ids.contains(this.activeId)) {
            throw new IllegalArgumentException("当前模型配置不存在");
        }
    }

    public static AiModelSettings read(SettingsStore store) { return parse(store.getString(KEY, "")); }
    public static AiModelSettings read(LocalOptions options) { return parse(options.text(KEY, "")); }

    public static String automationModelKey(String kind) {
        if ("reply".equals(kind) || "comment_reply".equals(kind)) return REPLY_MODEL_ID;
        if ("comment".equals(kind)) return COMMENT_MODEL_ID;
        throw new IllegalArgumentException("未知自动化类型");
    }

    /** Each automation selects an existing profile without changing the global active profile. */
    public static AiModelSettings readForAutomation(SettingsStore store, String kind) {
        String key = automationModelKey(kind);
        return read(store).forAutomation(kind, store.getString(key, ""));
    }

    public static AiModelSettings readForAutomation(LocalOptions options, String kind) {
        String key = automationModelKey(kind);
        return read(options).forAutomation(kind, options.text(key, ""));
    }

    /** Compares this channel's binding policy and complete selected profile without logging its values. */
    public static boolean sameAutomationSelection(LocalOptions before, LocalOptions next, String kind) {
        String key = automationModelKey(kind);
        if (before == null || next == null) return before == next;
        String previousBinding = before.text(key, "");
        String nextBinding = next.text(key, "");
        if (!previousBinding.equals(nextBinding)) return false;
        try {
            String previousProfile = readForAutomation(before, kind).selected().toJson().toString();
            String nextProfile = readForAutomation(next, kind).selected().toJson().toString();
            return previousProfile.equals(nextProfile);
        } catch (RuntimeException invalid) {
            // Keep invalid/deleted selections visible to the normal request validation.
            // Without a valid selected profile, any document change must invalidate the old snapshot.
            return before.text(KEY, "").equals(next.text(KEY, ""));
        }
    }

    private AiModelSettings forAutomation(String kind, String modelId) {
        if (modelId == null || modelId.isEmpty()) return this;
        for (AiModelConfig config : configs) if (config.id.equals(modelId)) {
            return new AiModelSettings(configs, modelId);
        }
        throw new IllegalStateException(("comment".equals(kind) ? "自动评论" : "自动回复")
                + "所选模型配置已不存在，请重新选择");
    }

    public static AiModelSettings parse(String serialized) {
        if (serialized == null || serialized.trim().isEmpty()) return new AiModelSettings(Collections.emptyList(), "");
        try {
            JSONObject root = new JSONObject(serialized);
            if (root.getInt("version") != 2) throw new IllegalArgumentException("模型配置版本不支持");
            JSONArray entries = root.getJSONArray("configs");
            List<AiModelConfig> configs = new ArrayList<>();
            for (int i = 0; i < entries.length(); i++) configs.add(AiModelConfig.fromJson(entries.getJSONObject(i)));
            return new AiModelSettings(configs, root.optString("activeId", ""));
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("模型配置无法读取", failure); }
    }

    public String serialize() {
        try {
            JSONArray entries = new JSONArray();
            for (AiModelConfig config : configs) entries.put(config.toJson());
            String serialized = new JSONObject().put("version", 2).put("activeId", activeId)
                    .put("configs", entries).toString();
            return serialized;
        } catch (Exception failure) { throw new IllegalStateException(failure); }
    }

    public void save(SettingsStore store) { store.putString(KEY, serialize()); }

    public AiModelConfig selected() {
        for (AiModelConfig config : configs) if (config.id.equals(activeId)) {
            config.validate(true);
            return config;
        }
        throw new IllegalStateException("请先选择模型配置");
    }

    public AiModelSettings upsert(AiModelConfig config, boolean activate) {
        config.validate(true);
        List<AiModelConfig> next = new ArrayList<>();
        boolean replaced = false;
        for (AiModelConfig existing : configs) {
            if (existing.id.equals(config.id)) { next.add(config); replaced = true; }
            else next.add(existing);
        }
        if (!replaced) next.add(config);
        return new AiModelSettings(next, activate ? config.id : activeId);
    }

    public AiModelSettings activate(String id) {
        for (AiModelConfig config : configs) if (config.id.equals(id)) config.validate(true);
        return new AiModelSettings(configs, id);
    }

    public AiModelSettings delete(String id) {
        boolean exists = false;
        for (AiModelConfig config : configs) exists |= config.id.equals(id);
        if (!exists) return this;
        List<AiModelConfig> next = new ArrayList<>();
        for (AiModelConfig config : configs) if (!config.id.equals(id)) next.add(config);
        return new AiModelSettings(next, activeId.equals(id) ? "" : activeId);
    }
}
