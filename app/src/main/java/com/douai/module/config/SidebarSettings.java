package com.douai.module.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Opt-in home sidebar, with a bounded catalog of view/configuration shortcuts only. */
public final class SidebarSettings {
    public static final String ENABLED = "home_sidebar_enabled";
    public static final String ITEMS = "home_sidebar_items";
    public static final String DEFAULT_ITEMS = "module,comment_history,reply_history,ai_chat,download_tasks,analytics";

    public static final class Entry {
        public final String id, title;
        private Entry(String id, String title) { this.id = id; this.title = title; }
    }

    public static final List<Entry> ENTRIES = Collections.unmodifiableList(List.of(
            new Entry("module", "模块设置"),
            new Entry("comment_history", "评论历史"),
            new Entry("reply_history", "回复历史"),
            new Entry("ai_chat", "AI 对话"),
            new Entry("download_tasks", "下载任务"),
            new Entry("analytics", "本地数据分析"),
            new Entry("ai_models", "AI 模型设置"),
            new Entry("speech", "文本转语音设置"),
            new Entry("auto_comment", "自动评论设置"),
            new Entry("auto_reply", "自动回复设置"),
            new Entry("automation", "互动自动化设置"),
            new Entry("streak", "自动续火花设置"),
            new Entry("feed_filter", "视频筛选"),
            new Entry("component_hide", "组件隐藏"),
            new Entry("autoplay", "自动播放设置"),
            new Entry("gestures", "手势设置"),
            new Entry("comment_tools", "评论工具设置"),
            new Entry("message_management", "消息管理"),
            new Entry("download_settings", "下载设置"),
            new Entry("backup", "配置备份"),
            new Entry("debug_logs", "调试与日志"),
            new Entry("help", "使用帮助"),
            new Entry("about", "关于 DouAi")
    ));

    private SidebarSettings() { }

    public static Entry find(String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        return null;
    }

    public static List<Entry> selected(LocalOptions options) {
        return parse(options == null ? DEFAULT_ITEMS : options.text(ITEMS, DEFAULT_ITEMS));
    }

    public static List<Entry> selected(SettingsStore store) {
        return parse(store == null ? DEFAULT_ITEMS : store.getString(ITEMS, DEFAULT_ITEMS));
    }

    public static List<Entry> parse(String source) {
        if (source == null) source = DEFAULT_ITEMS;
        Set<String> requested = new HashSet<>();
        // Only the stable allowlist can become a shortcut; large imported strings cannot create views.
        for (String id : source.substring(0, Math.min(source.length(), 4096)).split("[,，\\n]+")) requested.add(id.trim());
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : ENTRIES) if (requested.contains(entry.id)) selected.add(entry);
        return Collections.unmodifiableList(selected);
    }

    public static String serialize(Collection<String> selected) {
        Set<String> requested = selected == null ? Collections.emptySet() : new HashSet<>(selected);
        List<String> ids = new ArrayList<>();
        for (Entry entry : ENTRIES) if (requested.contains(entry.id)) ids.add(entry.id);
        return String.join(",", ids);
    }
}
