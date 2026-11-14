package com.douai.module.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import com.douai.module.scan.HostAdaptation;

/** Opt-in home sidebar. Saved IDs stay portable; only supported entries are shown. */
public final class SidebarSettings {
    public static final String ENABLED = "home_sidebar_enabled";
    public static final String ITEMS = "home_sidebar_items";
    public static final String DEFAULT_ITEMS = "module,comment_history,reply_history,ai_chat,download_tasks,analytics";

    public static final class Entry {
        public final String id, title;
        private Entry(String id, String title) { this.id = id; this.title = title; }
    }

    private static final List<Entry> SETTINGS = List.of(
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
    );
    private static final List<Entry> FEED_ACTIONS = List.of(
            new Entry("bookmark_add", "记下这一刻"),
            new Entry("reading_search", "搜章节和字幕"),
            new Entry("comment_search", "查找评论"),
            new Entry("bookmark_list", "我的书签")
    );
    public static final List<Entry> ENTRIES;
    static {
        List<Entry> all = new ArrayList<>(SETTINGS);
        all.addAll(FEED_ACTIONS);
        ENTRIES = Collections.unmodifiableList(all);
    }

    private SidebarSettings() { }

    public static List<Entry> availableEntries() {
        return HostAdaptation.currentProfile() == HostAdaptation.PROFILE_410 ? ENTRIES : SETTINGS;
    }

    public static boolean isFeedAction(String id) {
        for (Entry entry : FEED_ACTIONS) if (entry.id.equals(id)) return true;
        return false;
    }

    public static Entry find(String id) {
        for (Entry entry : ENTRIES) if (entry.id.equals(id)) return entry;
        return null;
    }

    public static List<Entry> selected(LocalOptions options) {
        return parse(options == null ? DEFAULT_ITEMS : options.text(ITEMS, DEFAULT_ITEMS), availableEntries());
    }

    public static List<Entry> selected(SettingsStore store) {
        return parse(store == null ? DEFAULT_ITEMS : store.getString(ITEMS, DEFAULT_ITEMS), availableEntries());
    }

    public static List<Entry> parse(String source) {
        return parse(source, ENTRIES);
    }

    private static List<Entry> parse(String source, List<Entry> catalog) {
        if (source == null) source = DEFAULT_ITEMS;
        Set<String> requested = new HashSet<>();
        // Only the stable allowlist can become a shortcut; large imported strings cannot create views.
        for (String id : source.substring(0, Math.min(source.length(), 4096)).split("[,，\\n]+")) requested.add(id.trim());
        List<Entry> selected = new ArrayList<>();
        for (Entry entry : catalog) if (requested.contains(entry.id)) selected.add(entry);
        return Collections.unmodifiableList(selected);
    }

    public static String serializeSelection(SettingsStore store, Collection<String> checked) {
        return serializeSelection(store.getString(ITEMS, DEFAULT_ITEMS), checked);
    }

    static String serializeSelection(String prior, Collection<String> checked) {
        List<Entry> visible = availableEntries();
        Set<String> ids = new HashSet<>();
        for (Entry entry : visible) if (checked.contains(entry.id)) ids.add(entry.id);
        // Preserve selections the current host cannot expose in its editor.
        if (visible != ENTRIES) for (Entry entry : parse(prior)) if (!visible.contains(entry)) ids.add(entry.id);
        return serialize(ids);
    }

    public static String serialize(Collection<String> selected) {
        Set<String> requested = selected == null ? Collections.emptySet() : new HashSet<>(selected);
        List<String> ids = new ArrayList<>();
        for (Entry entry : ENTRIES) if (requested.contains(entry.id)) ids.add(entry.id);
        return String.join(",", ids);
    }
}
