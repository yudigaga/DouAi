package com.douai.module.diagnostics;

import com.douai.module.config.ConfigurationCatalog;
import com.douai.module.config.ConfigurationCatalog.Feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/** Business capability inventory. A scan rule or installed hook is never a business result. */
public final class FeatureLogCatalog {
    public static final class Entry {
        public final String id, label, source, installedKey, enabledKey;
        public final Feature consumer;
        Entry(String id, String label, String source, Feature consumer, String installedKey, String enabledKey) {
            this.id = id; this.label = label; this.source = source; this.consumer = consumer;
            this.installedKey = installedKey; this.enabledKey = enabledKey;
        }
        public List<String> configurationKeys() {
            ArrayList<String> keys = new ArrayList<>();
            if (consumer != null) for (ConfigurationCatalog.Setting setting : ConfigurationCatalog.settings().values()) {
                if (setting.consumers.contains(consumer)) keys.add(setting.key);
            }
            return Collections.unmodifiableList(keys);
        }
    }

    private static final List<Entry> ENTRIES;
    static {
        ArrayList<Entry> entries = new ArrayList<>();
        add(entries, "feed_filter", "推荐流筛选", "FeedAdFilterHook", Feature.FEED_FILTER, "feed_hook_installed", "feed_filter_enabled");
        add(entries, "download", "媒体下载", "DownloadActionHook", Feature.DOWNLOAD, "download_hook_installed", "download_enabled");
        add(entries, "native_auto_reply", "私信及群聊自动回复", "NativeMessagingHooks", Feature.REPLY, "feature.native_auto_reply.installed", "auto_reply_enabled");
        add(entries, "streak", "定时续火花", "NativeMessagingHooks/StreakSendLedger", Feature.REPLY, "feature.native_auto_reply.installed", "streak_active");
        add(entries, "auto_comment", "自动评论", "AutoCommentHook", Feature.COMMENT_AUTOMATION, "auto_comment_hook_installed", "auto_comment_enabled");
        add(entries, "comment_reply", "评论回复", "CommentReplyHook", Feature.COMMENT_REPLY, "comment_reply_hook_installed", "comment_reply_enabled");
        add(entries, "feed_ui", "视频图文组件隐藏及透明度", "FeedUiHideHook", Feature.FEED_UI, "feed_ui_hook_installed", "feed_ui_active");
        add(entries, "auto_speed", "自动倍速", "PlaybackBehaviorHooks", Feature.PLAYBACK, "feature.auto_speed.installed", "auto_speed");
        add(entries, "auto_play", "连播及评论暂停", "AutoPlayHook", Feature.AUTO_PLAY, "auto_play_hook_installed", "auto_play_enabled");
        add(entries, "gesture", "九宫格手势及工具栏", "FeedGestureToolsHook", Feature.GESTURE, "feature.long_press_action.installed", "gesture_enabled");
        add(entries, "comment_ai", "评论 AI 解析", "CommentAiAnalysisHook", Feature.MANUAL_AI,
                "feature.comment_ai.installed", "comment_ai_takeover");
        add(entries, "manual_ai_search", "手动 AI 抖音搜索", "HostDouyinSearch", Feature.MANUAL_AI,
                "feature.manual_ai_search.installed", "manual_ai_douyin_search");
        add(entries, "prevent_message_recall", "消息防撤回", "NativeMessagingHooks", Feature.MESSAGE_RECALL, "feature.prevent_message_recall.installed", "prevent_message_recall");
        add(entries, "spoof_profile", "本地个人资料数量", "ProfileSpoofHook", Feature.SPOOF, "feature.spoof_profile.installed", "spoof_profile");
        add(entries, "follow_lists", "好友关注粉丝及群成员列表", "NativeFollowLists", Feature.FOLLOW_LISTS, "follow_lists_installed", "always");
        add(entries, "automation_dispatch", "自动化调度提示及历史", "AutomationDispatcher", Feature.AUTOMATION_DISPATCH, "automation_dispatch_installed", "automation_dispatch_enabled");
        for (String key : new String[]{"remove_splash_ads", "remove_update_dialog", "remove_teen_dialog", "hide_video_black_edges", "hide_camera_plus", "disable_double_tap_like", "prevent_skip_hidden", "prevent_insert_video", "block_long_press_speed"}) {
            add(entries, key, key, "HostBehaviorHooks", Feature.HOST_BEHAVIOR, "feature." + key + ".installed", key);
        }
        add(entries, "dm_speech", "私聊语音替换及文本转语音", "DirectMessageSpeechHook", Feature.DIRECT_MESSAGE_SPEECH, "feature.dm_speech.installed", "dm_speech_enabled");
        add(entries, "media_model", "下载限制及媒体模型", "MediaModelHook", Feature.MEDIA_MODEL, "feature.media_model.installed", "remove_download_limits");
        add(entries, "comment_enhancements", "评论后缀及颜色", "CommentEnhancementsHook", Feature.COMMENT_ENHANCEMENTS, "feature.comment_enhancements.installed", "comment_enhancements_enabled");
        add(entries, "comment_voice", "评论语音替换", "CommentVoiceHook", Feature.COMMENT_VOICE, "feature.comment_voice.installed", "comment_voice_replace");
        add(entries, "comment_tts", "评论文本转语音", "NativeCommentSpeechHook", Feature.NATIVE_COMMENT_SPEECH, "feature.comment_tts.installed", "comment_tts");
        add(entries, "auto_like", "自动点赞", "AutoLikeHook", Feature.AUTO_LIKE, "auto_like_hook_installed", "auto_like_enabled");
        add(entries, "auto_follow", "自动关注", "AutoFollowHook", Feature.AUTO_FOLLOW, "auto_follow_hook_installed", "auto_follow_enabled");
        add(entries, "auto_message", "私聊草稿", "AutoMessageDraftHook", Feature.AUTO_MESSAGE, "auto_message_hook_installed", "auto_message_enabled");
        add(entries, "comment_tools", "评论复制及媒体工具", "CommentToolsHook", Feature.COMMENT_TOOLS, "comment_tools_hook_installed", "comment_tools_enabled");
        add(entries, "immersive", "状态栏及导航栏沉浸", "ImmersiveModeController", Feature.IMMERSIVE, "immersive_hook_installed", "immersive_enabled");
        add(entries, "version", "版本显示伪装", "VersionInfoHook", Feature.VERSION, "version_hook_installed", "version_spoof_enabled");
        add(entries, "top_tabs", "顶部标签隐藏", "TopTabFilterHook", Feature.TOP_TABS, "top_tab_hook_installed", "top_tab_filter_enabled");
        add(entries, "refresh_confirm", "刷新确认", "RefreshConfirmHook", Feature.REFRESH, "refresh_hook_installed", "refresh_confirm_enabled");
        add(entries, "diagnostics", "诊断及日志管理", "DebugLogChannel/DebugLogDialog", Feature.DIAGNOSTICS, "diagnostics_installed", "always");
        add(entries, "local_analytics", "本地浏览及行为分析", "LocalAnalyticsHook/LocalAnalytics", null, "feature.local_analytics.installed", "local_analytics_enabled");
        add(entries, "recall_background", "撤回消息背景", "RecallHighlightHook", Feature.MESSAGE_RECALL, "feature.recall_background.installed", "prevent_message_recall");
        add(entries, "home_navigation", "首页导航组件隐藏", "HomeNavigationHideController", Feature.FEED_UI, "home_navigation_hook_installed", "home_navigation_enabled");
        add(entries, "short_progress", "短视频进度条", "ShortVideoProgressHook", Feature.FEED_UI, "short_progress_installed", "short_progress_enabled");
        add(entries, "hash_long_press_voice", "井号长按语音", "HashLongPressVoiceHook", null, "feature.hash_long_press_voice.installed", "hash_long_press_voice");
        // Explicit entries for module-owned UI actions; readiness is observed at their actual entry point.
        for (String[] item : new String[][]{
                {"ai_chat", "手动AI会话及图片语音", "AiConversationDialog/ChatCompletionClient"},
                {"ai_contacts", "消息页AI联系人", "AiMessageEntryHook"},
                {"module_startup", "模块进程及启动条件", "DouAiModule"},
                {"host_scan", "宿主扫描及安装准备", "DouAiModule/HostScanner"},
                {"model_settings", "AI模型及提示词配置", "AiModelEditor/PromptEditorDialog"},
                {"speech_settings", "语音配置及试听", "SpeechSettingsDialog"},
                {"third_party_settings", "第三方解析配置及测试", "ThirdPartyDownloadEditor"},
                {"configuration", "配置读取热更新及迁移", "DouAiModule/ConfigurationSnapshot"},
                {"config_transfer", "配置导入导出及恢复", "ConfigTransferDialog/ConfigTransfer"},
                {"portrait", "本地画像及AI报告", "LocalAnalyticsDialog/AnalyticsAiAnalysis"},
                {"report_export", "报告图片文件分享", "ReportImageExport/ReportImageShare"},
                {"history", "自动化历史及未知提交恢复", "AutomationHistoryDialog/SubmissionRecoveryDialog"},
                {"media_picker", "图片语音及目录选择", "HostImagePicker/HostAudioPicker/HostDownloadDirectoryPicker"},
                {"settings_entry", "宿主设置入口及主题", "HostSettingsEntry/HostUiController/ModuleTheme"},
                {"restart", "宿主重启", "HostAppRestarter"},
                {"launcher", "模块桌面图标", "LauncherVisibility"}}) {
            add(entries, item[0], item[1], item[2], "ai_chat".equals(item[0]) ? Feature.MANUAL_AI : null,
                    "", "always");
        }
        ENTRIES = Collections.unmodifiableList(entries);
    }

    private FeatureLogCatalog() { }
    private static void add(List<Entry> entries, String id, String label, String source,
                            Feature consumer, String installedKey, String enabledKey) {
        entries.add(new Entry(id, label, source, consumer, installedKey, enabledKey));
    }
    public static List<Entry> entries() { return ENTRIES; }

    /** No content/endpoint/path/token is included. Unobserved UI readiness stays unknown. */
    public static void publish(Map<String, ?> configuration, Map<String, ?> runtime, boolean configurationReady) {
        registerAliases();
        for (Entry entry : ENTRIES) {
            State state = state(entry, configuration, runtime, configurationReady);
            DouAiLog.featureState(entry.id, state.enabled, state.availability, state.hookState, "source=" + entry.source
                    + ";configuration_ready=" + configurationReady + ";installed_evidence=" + entry.installedKey
                    + ";installation_failure_ref=" + state.failureReference
                    + ";dependency_evidence=" + state.dependencyEvidence
                    + ";readiness_scope=runtime_observation_required"
                    + ";business_result=not_implied");
        }
    }

    private static boolean aliasesRegistered;
    private static synchronized void registerAliases() {
        if (aliasesRegistered) return;
        DouAiLog.registerFeatureAliases("native_auto_reply", "auto_reply");
        DouAiLog.registerFeatureAliases("comment_reply", "auto_comment_reply", "comment_reply_inbox", "comment_reply_thread_lookup");
        DouAiLog.registerFeatureAliases("comment_voice", "comment_voice_replace");
        DouAiLog.registerFeatureAliases("dm_speech", "dm_voice_replace", "dm_tts");
        DouAiLog.registerFeatureAliases("follow_lists", "relationship_lists");
        DouAiLog.registerFeatureAliases("download", "media_download", "download_menu", "official_download_selection",
                "official_download_save_attempt", "third_party_selection", "third_party_download");
        DouAiLog.registerFeatureAliases("local_analytics", "local_analytics_collection", "local_analytics_report",
                "local_analytics_write", "local_analytics_clear", "analytics_backup_export", "analytics_backup_preview", "analytics_backup_restore");
        DouAiLog.registerFeatureAliases("portrait", "portrait_context", "analytics_ai_analysis");
        DouAiLog.registerFeatureAliases("config_transfer", "config_import", "config_export", "config_import_preview");
        DouAiLog.registerFeatureAliases("configuration", "settings", "settings_replace");
        DouAiLog.registerFeatureAliases("hash_long_press_voice", "hash_long_press_voice_comment");
        DouAiLog.registerFeatureAliases("speech_settings", "speech_preview");
        DouAiLog.registerFeatureAliases("third_party_settings", "third_party_get_test", "third_party_response_sample");
        aliasesRegistered = true;
    }

    static final class State {
        final boolean enabled;
        final String availability, hookState, failureReference, dependencyEvidence;
        State(boolean enabled, String availability, String hookState, String failureReference, String dependencyEvidence) {
            this.enabled = enabled; this.availability = availability;
            this.hookState = hookState; this.failureReference = failureReference;
            this.dependencyEvidence = dependencyEvidence;
        }
    }

    /** Capability and user intent are independent axes: a disabled feature can also be unsupported. */
    static State state(Entry entry, Map<String, ?> configuration, Map<String, ?> runtime, boolean configurationReady) {
        boolean enabled = "always".equals(entry.enabledKey) || Boolean.TRUE.equals(runtime.get(entry.enabledKey))
                || !runtime.containsKey(entry.enabledKey) && Boolean.TRUE.equals(configuration.get(entry.enabledKey));
        boolean known = !entry.installedKey.isEmpty() && runtime.containsKey(entry.installedKey);
        boolean installed = known && Boolean.TRUE.equals(runtime.get(entry.installedKey));
        Object failure = entry.installedKey.isEmpty() ? null : runtime.get(entry.installedKey
                .replace(".installed", ".failure").replace("_installed", "_failure"));
        boolean failed = failure != null && !String.valueOf(failure).isEmpty();
        String dependencies = dependencyState(entry, runtime);
        String availability = failed ? "unavailable" : !configurationReady ? "pending"
                : "missing".equals(dependencies) ? "dependency_missing"
                : "pending".equals(dependencies) ? "pending"
                : known && !installed ? "unknown"
                : "partial".equals(dependencies) || installed && "unknown".equals(dependencies) ? "partial"
                : installed || entry.installedKey.isEmpty() && "ready".equals(dependencies) ? "supported" : "unknown";
        String hookState = entry.installedKey.isEmpty() ? "not_required"
                : installed ? "installed" : failed ? "installation_failed" : known ? "not_installed" : "not_observed";
        return new State(enabled, availability, hookState,
                DouAiLog.reference(failure == null ? "" : String.valueOf(failure)), dependencyEvidence(entry, runtime, dependencies));
    }

    /** Runtime evidence only: a configured key/model never proves account, permission or service readiness. */
    private static String dependencyState(Entry entry, Map<String, ?> runtime) {
        String prefix = "feature." + entry.id + ".dependency.";
        boolean observed = false;
        for (Map.Entry<String, ?> value : runtime.entrySet()) {
            if (!value.getKey().startsWith(prefix)) continue;
            observed = true;
            if (Boolean.FALSE.equals(value.getValue())) return "missing";
        }
        Object aggregate = runtime.get("feature." + entry.id + ".dependency_state");
        if (aggregate instanceof String && java.util.Arrays.asList("ready", "missing", "pending", "unknown", "partial").contains(aggregate)) {
            return (String) aggregate;
        }
        // Individual checks do not establish that every required dependency was checked.
        // Only the aggregate supplied by the actual feature may assert complete readiness.
        if (observed) return "partial";
        // Catalog presence/installation can prove local support, never remote execution readiness.
        switch (entry.id) {
            case "download": case "native_auto_reply": case "streak": case "auto_comment": case "comment_reply":
            case "follow_lists": case "auto_like": case "auto_follow": case "dm_speech": case "comment_voice":
            case "comment_tts": case "hash_long_press_voice": case "ai_chat": case "portrait":
            case "media_picker": case "report_export": case "speech_settings": case "third_party_settings":
                return "unknown";
            default: return entry.installedKey.isEmpty() ? "unknown" : "not_required";
        }
    }

    private static String dependencyEvidence(Entry entry, Map<String, ?> runtime, String aggregate) {
        String prefix = "feature." + entry.id + ".dependency.";
        ArrayList<String> checks = new ArrayList<>();
        for (Map.Entry<String, ?> item : runtime.entrySet()) {
            if (!item.getKey().startsWith(prefix)) continue;
            String name = item.getKey().substring(prefix.length());
            if (!name.matches("[a-z_]{1,48}")) continue;
            checks.add(name + ":" + (Boolean.TRUE.equals(item.getValue()) ? "observed_available"
                    : Boolean.FALSE.equals(item.getValue()) ? "missing" : "unknown"));
        }
        Collections.sort(checks);
        return aggregate + (checks.isEmpty() ? "" : ";dependency_checks=" + String.join(",", checks));
    }

    public static String forHook(String id) {
        if (id == null) return "unknown_hook";
        if (id.startsWith("DouAi.Advanced.")) {
            String name = id.substring("DouAi.Advanced.".length());
            int end = name.indexOf('.');
            name = end < 0 ? name : name.substring(0, end);
            if ("auto_slide".equals(name)) return "auto_play";
            if ("native_reply".equals(name)) return "native_auto_reply";
            if (name.startsWith("local_analytics_")) return "local_analytics";
            if (name.startsWith("short_progress_")) return "short_progress";
            if ("long_press_action".equals(name) || "double_tap_action".equals(name) || "toolbar_enabled".equals(name)
                    || "gesture_current_holder".equals(name) || "finder_pause_guard".equals(name)) return "gesture";
            return name;
        }
        String[][] prefixes = {{"DouAi.Automation.AutoLike", "auto_like"}, {"DouAi.Automation.AutoFollow", "auto_follow"},
                {"DouAi.Automation.Message", "auto_message"}, {"DouAi.AutoComment", "auto_comment"},
                {"DouAi.CommentReply", "comment_reply"}, {"DouAi.Feed.Response", "feed_filter"},
                {"DouAi.FeedUi", "feed_ui"}, {"DouAi.AutoPlay", "auto_play"}, {"DouAi.Download", "download"},
                {"DouAi.HomeTab.Filter", "top_tabs"}, {"DouAi.HomeTab.Refresh", "refresh_confirm"},
                {"DouAi.VersionInfo", "version"}, {"DouAi.HashLongPress", "hash_long_press_voice"},
                {"DouAi.Comment.Request", "comment_enhancements"}, {"DouAi.Comment.Tools", "comment_tools"},
                {"DouAi.CommentVoice", "comment_voice"}, {"DouAi.CommentSpeech", "comment_tts"},
                {"DouAi.DirectMessage", "dm_speech"}, {"DouAi.PrivateSpeech", "dm_speech"},
                {"DouAi.CommentAudio", "comment_voice"}, {"DouAi.MediaModel", "media_model"},
                {"DouAi.AutoAudio", "native_auto_reply"}, {"DouAi.Host", "settings_entry"}};
        for (String[] prefix : prefixes) if (id.startsWith(prefix[0])) return prefix[1];
        return "unmapped_hook";
    }
}
