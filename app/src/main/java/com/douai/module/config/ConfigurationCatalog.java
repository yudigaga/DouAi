package com.douai.module.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Shared transfer schema and hot-update dependencies. Runtime commands never become backup keys. */
public final class ConfigurationCatalog {
    public static final String PLAYBACK_SPEED = "playback_speed";
    public static final String PLAYBACK_SPEED_DEFAULT = PlaybackSpeedPresets.DEFAULT;
    public static final String DOWNLOAD_METHOD_DEFAULT = "official";
    public static final String DOWNLOAD_NAME_DEFAULT = "#author#_#aid#_#index#.#ext#";

    public enum Feature {
        FEED_FILTER, DOWNLOAD, REPLY, COMMENT_AUTOMATION, COMMENT_REPLY, FEED_UI,
        PLAYBACK, AUTO_PLAY, GESTURE, MESSAGE_RECALL, SPOOF, FOLLOW_LISTS, AUTOMATION_DISPATCH,
        HOST_BEHAVIOR, DIRECT_MESSAGE_SPEECH, MEDIA_MODEL, COMMENT_ENHANCEMENTS,
        COMMENT_VOICE, NATIVE_COMMENT_SPEECH, AUTO_LIKE, AUTO_FOLLOW, AUTO_MESSAGE,
        COMMENT_TOOLS, IMMERSIVE, VERSION, TOP_TABS, REFRESH, DIAGNOSTICS
    }

    /** A missing default means the owning domain still resolves a context-dependent/legacy default. */
    public static final class Setting {
        public final String key, type;
        public final Object defaultValue;
        public final Set<Feature> consumers;
        private Setting(String key, String type, Object defaultValue, Set<Feature> consumers) {
            this.key = key; this.type = type; this.defaultValue = defaultValue;
            this.consumers = Collections.unmodifiableSet(consumers);
        }
        public boolean transferable() { return type != null; }
    }

    private static final class Dependencies {
        final Set<String> keys;
        final String[] prefixes;
        Dependencies(String keys, String prefixes) {
            this.keys = keys.isEmpty() ? Collections.emptySet()
                    : Collections.unmodifiableSet(new java.util.LinkedHashSet<>(Arrays.asList(keys.split(" "))));
            this.prefixes = prefixes.isEmpty() ? new String[0] : prefixes.split(" ");
        }
        boolean includes(String key) {
            if (keys.contains(key)) return true;
            for (String prefix : prefixes) if (key.startsWith(prefix)) return true;
            return false;
        }
        boolean changed(ConfigurationChanges changes) {
            for (String key : keys) if (changes.contains(key)) return true;
            for (String prefix : prefixes) if (changes.hasPrefix(prefix)) return true;
            return false;
        }
    }

    private static final Map<String, String> TRANSFER_TYPES;
    private static final Map<String, Setting> SETTINGS;
    private static final Map<Feature, Dependencies> DEPENDENCIES = new EnumMap<>(Feature.class);
    static {
        Map<String, String> keys = new LinkedHashMap<>();
        add(keys, "boolean", "download_enabled remove_watermark feed_filter_enabled hide_ads hide_lives hide_image_posts "
                + "hide_recommendation_cards hide_recommendation_merchants keyword_filter_enabled minimum_duration_enabled "
                + "minimum_likes_enabled automation_enabled auto_like_enabled auto_follow_enabled auto_comment_enabled auto_message_enabled "
                + "hide_interface hide_top_tabs remove_black_edges hide_navigation_bar auto_play player_gestures copy_comment_text "
                + "comment_media_tools copy_source_data prevent_message_recall confirm_refresh spoof_version auto_speed "
                + "pause_auto_on_comments smart_swipe comment_append_suffix comment_voice_replace comment_tts colored_comment "
                + "streak_enabled streak_tip recall_self recall_other dm_voice_replace dm_tts spoof_profile download_custom_name "
                + "remove_download_limits filter_interrupt hide_mini_games hide_long_videos hide_product_cards "
                + "hide_hotspot_rank_cards native_auto_reply native_auto_comment "
                + "reply_voice comment_voice reply_private_chats reply_group_chats reply_global comment_recognize_images "
                + "comment_recognize_video reply_tip comment_tip reply_save_history comment_save_history "
                + "toolbar_enabled intercept_native_long_press disable_double_tap_like block_long_press_speed "
                + "hide_camera_plus hide_video_black_edges prevent_insert_video prevent_skip_hidden remove_splash_ads "
                + "remove_teen_dialog remove_update_dialog");
        add(keys, "boolean", LocalOptions.OFFICIAL_DOWNLOAD_DIRECT);
        add(keys, "boolean", LocalOptions.DEBUG_LOGGING);
        add(keys, "boolean", LocalOptions.HASH_LONG_PRESS_VOICE);
        add(keys, "boolean", LocalOptions.MODULE_ONLY_SPEED);
        add(keys, "boolean", LocalOptions.REPLY_RECOGNIZE_IMAGES + " " + LocalOptions.REPLY_RECOGNIZE_VIDEO);
        add(keys, "boolean", LocalOptions.COMMENT_REPLY_ENABLED
                + " comment_reply_tip_enabled comment_reply_failure_notify");
        add(keys, "boolean", ThirdPartyDownloadSettings.DIRECT);
        add(keys, "int", "minimum_duration_seconds minimum_like_count");
        add(keys, "string", "keyword video_right_alpha hidden_top_tabs fake_version_name fake_version_code auto_comment_text "
                + "auto_message_text playback_speed comment_suffix colored_comment_type comment_voice_path dm_voice_path "
                + "streak_content streak_content_api streak_start streak_end streak_targets recall_background "
                + "profile_like_count profile_following_count profile_follower_count profile_mutual_count download_name_template "
                + "download_directory_uri maximum_duration_seconds reply_mode reply_prompt reply_keywords reply_random "
                + "reply_targets reply_group_members reply_group_mode reply_group_scope_mode reply_group_targets "
                + "comment_mode comment_prompt comment_scope_users ai_model_settings_v2 "
                + "download_method third_party_get_template third_party_headers third_party_app_id third_party_app_secret "
                + "third_party_signature_mode third_party_signature_header third_party_download_fields third_party_naming_fields "
                + "tts_provider tts_engine tts_language tts_rate tts_pitch tts_get_template tts_headers tts_audio_field "
                + "tts_voice_get_template tts_voice_headers tts_voice_list_path tts_voice_id_path tts_voice_name_path "
                + "tts_selected_voice tts_selected_voice_name component_hide_custom_views "
                + "tts_minimax_api_key tts_minimax_base_url tts_minimax_model tts_minimax_voice_id tts_minimax_voice_name "
                + "tts_minimax_speed tts_minimax_volume tts_minimax_pitch");
        add(keys, "string", AiModelSettings.REPLY_MODEL_ID + " " + AiModelSettings.COMMENT_MODEL_ID);
        add(keys, "string", AnalyticsAiSettings.MODEL_ID + " " + AnalyticsAiSettings.SECONDARY_PROMPT);
        for (String key : MiniMaxSpeechOptions.KEYS) keys.put(key, "string");
        for (String key : BreezeSpeechSettings.KEYS) keys.put(key, "string");
        for (String key : FishSpeechSettings.KEYS) {
            if (!FishSpeechSettings.REFERENCE_SAMPLES.equals(key)) keys.put(key, "string");
        }
        for (String prefix : Arrays.asList("reply", "comment")) add(keys, "string", prefix + "_daily_limit "
                + prefix + "_user_cooldown_minutes " + prefix + "_delay_seconds " + prefix + "_notice_seconds "
                + prefix + "_scope_mode " + prefix + "_scope_relations " + prefix + "_scope_keywords "
                + prefix + "_scope_fields " + prefix + "_filter_mode " + prefix + "_filter_keywords " + prefix + "_filter_fields");
        // Preserve previous backups; comment replies now use the shared reply settings at runtime.
        add(keys, "string", "comment_reply_prompt comment_reply_daily_limit comment_reply_user_cooldown_minutes "
                + "comment_reply_thread_limit comment_reply_tip_seconds");
        add(keys, "boolean", "component_hide_enabled component_hide_uniform_enabled component_hide_on_switch "
                + "component_hide_open_comments_on_show component_hide_show_on_pause");
        add(keys, "string", "component_hide_uniform_alpha");
        for (ComponentHideSettings.Component component : ComponentHideSettings.Component.values()) keys.put(component.key, "string");
        for (String prefix : Arrays.asList("long_press", "double_tap")) {
            for (String region : com.douai.module.feature.FeedGestureToolsHook.REGION_IDS) keys.put(prefix + "_" + region, "string");
            for (String legacy : Arrays.asList("left", "center", "right")) keys.put(prefix + "_" + legacy, "string");
        }
        TRANSFER_TYPES = Collections.unmodifiableMap(keys);
        // These groups replace updateConfiguration's inline lists. Prefixes retain future/legacy keys.
        depend(Feature.FEED_FILTER, "feed_filter_enabled hide_ads hide_lives hide_image_posts hide_recommendation_cards hide_recommendation_merchants keyword_filter_enabled keyword minimum_duration_enabled minimum_duration_seconds minimum_likes_enabled minimum_like_count filter_interrupt hide_mini_games hide_long_videos hide_product_cards hide_hotspot_rank_cards maximum_duration_seconds", "");
        depend(Feature.DOWNLOAD, "download_enabled remove_watermark download_custom_name download_name_template remove_download_limits download_method download_directory_uri", "third_party_");
        depend(Feature.REPLY, "automation_enabled native_auto_reply reply_global reply_targets reply_mode reply_prompt reply_model_id reply_delay_seconds auto_message_text reply_daily_limit reply_user_cooldown_minutes reply_recognize_images reply_recognize_video reply_keywords reply_scope_mode reply_scope_relations reply_scope_fields reply_scope_keywords reply_scope_users reply_filter_mode reply_filter_keywords reply_filter_fields reply_private_chats reply_group_chats reply_group_scope_mode reply_group_targets reply_group_mode reply_group_members reply_random ai_model_settings_v2 streak_enabled streak_targets streak_content streak_content_api streak_start streak_end streak_tip streak_reset_token streak_retry_unknown_token reply_voice", "tts_");
        depend(Feature.COMMENT_AUTOMATION, "automation_enabled native_auto_comment comment_mode comment_prompt comment_model_id comment_delay_seconds auto_comment_text comment_daily_limit comment_user_cooldown_minutes comment_recognize_images comment_recognize_video comment_scope_mode comment_scope_relations comment_scope_fields comment_scope_keywords comment_scope_users comment_filter_mode comment_filter_keywords comment_filter_fields ai_model_settings_v2 comment_voice", "tts_");
        depend(Feature.COMMENT_REPLY, "native_comment_reply", "");
        depend(Feature.FEED_UI, "hide_interface video_right_alpha component_hide_enabled component_hide_uniform_enabled component_hide_uniform_alpha component_hide_on_switch component_hide_open_comments_on_show component_hide_show_on_pause component_hide_custom_views", "component_alpha_");
        depend(Feature.PLAYBACK, "auto_speed playback_speed " + LocalOptions.MODULE_ONLY_SPEED, "");
        depend(Feature.AUTO_PLAY, "auto_play smart_swipe pause_auto_on_comments", "");
        depend(Feature.GESTURE, "playback_speed toolbar_enabled long_press_action double_tap_action intercept_native_long_press remove_watermark download_custom_name download_name_template remove_download_limits download_method download_directory_uri " + LocalOptions.MODULE_ONLY_SPEED, "long_press_ double_tap_ toolbar_ third_party_");
        depend(Feature.MESSAGE_RECALL, "prevent_message_recall recall_self recall_other recall_background", "");
        depend(Feature.SPOOF, "spoof_version fake_version_name fake_version_code spoof_profile profile_like_count profile_following_count profile_follower_count profile_mutual_count", "");
        depend(Feature.FOLLOW_LISTS, "reply_scope_mode comment_scope_mode reply_private_chats reply_global reply_scope_relations reply_targets reply_group_targets reply_group_members comment_scope_relations comment_scope_users relation_lists_refresh_token", "");
        depend(Feature.AUTOMATION_DISPATCH, "reply_tip comment_tip reply_notice_seconds comment_notice_seconds streak_tip reply_save_history comment_save_history", "");
        depend(Feature.HOST_BEHAVIOR, "remove_splash_ads remove_update_dialog remove_teen_dialog hide_video_black_edges hide_camera_plus disable_double_tap_like prevent_skip_hidden prevent_insert_video block_long_press_speed " + LocalOptions.MODULE_ONLY_SPEED, "");
        depend(Feature.DIRECT_MESSAGE_SPEECH, LocalOptions.HASH_LONG_PRESS_VOICE, "dm_ tts_");
        depend(Feature.MEDIA_MODEL, "remove_download_limits", "");
        depend(Feature.COMMENT_ENHANCEMENTS, "comment_append_suffix comment_suffix colored_comment colored_comment_type", "");
        depend(Feature.COMMENT_VOICE, "comment_voice_replace comment_voice_path", "");
        depend(Feature.NATIVE_COMMENT_SPEECH, "comment_tts " + LocalOptions.HASH_LONG_PRESS_VOICE, "tts_");
        depend(Feature.AUTO_LIKE, "automation_enabled auto_like_enabled", "");
        depend(Feature.AUTO_FOLLOW, "automation_enabled auto_follow_enabled", "");
        depend(Feature.AUTO_MESSAGE, "automation_enabled auto_message_enabled native_auto_reply auto_message_text hide_interface", "");
        depend(Feature.COMMENT_TOOLS, "copy_comment_text comment_media_tools copy_source_data", "");
        depend(Feature.IMMERSIVE, "remove_black_edges hide_navigation_bar", "");
        depend(Feature.VERSION, "spoof_version fake_version_name fake_version_code", "");
        depend(Feature.TOP_TABS, "hide_top_tabs hidden_top_tabs", "");
        depend(Feature.REFRESH, "confirm_refresh", "");

        depend(Feature.DIAGNOSTICS, LocalOptions.DEBUG_LOGGING, "");
        Map<String, Object> defaults = new LinkedHashMap<>();
        defaults.put(LocalOptions.DEBUG_LOGGING, false);
        defaults.put(LocalOptions.HASH_LONG_PRESS_VOICE, false);
        defaults.put(LocalOptions.MODULE_ONLY_SPEED, false);
        defaults.put("hide_product_cards", false);
        defaults.put("hide_hotspot_rank_cards", false);
        defaults.put(PLAYBACK_SPEED, PLAYBACK_SPEED_DEFAULT);
        defaults.put("download_method", DOWNLOAD_METHOD_DEFAULT);
        defaults.put("download_name_template", DOWNLOAD_NAME_DEFAULT);
        defaults.put("download_directory_uri", "");
        defaults.put("minimum_duration_seconds", 0);
        defaults.put("minimum_like_count", 0);
        defaults.put(AiModelSettings.REPLY_MODEL_ID, "");
        defaults.put(AiModelSettings.COMMENT_MODEL_ID, "");
        defaults.put(AnalyticsAiSettings.MODEL_ID, "");
        defaults.put(AnalyticsAiSettings.SECONDARY_PROMPT, "");
        Map<String, Setting> definitions = new LinkedHashMap<>();
        for (String key : keys.keySet()) definitions.put(key, setting(key, keys.get(key), defaults.get(key)));
        for (Dependencies group : DEPENDENCIES.values()) {
            for (String key : group.keys) if (!key.isEmpty() && !definitions.containsKey(key)) {
                definitions.put(key, setting(key, null, null));
            }
        }
        SETTINGS = Collections.unmodifiableMap(definitions);
    }

    private static Setting setting(String key, String type, Object defaultValue) {
        Set<Feature> consumers = EnumSet.noneOf(Feature.class);
        for (Map.Entry<Feature, Dependencies> group : DEPENDENCIES.entrySet()) {
            if (group.getValue().includes(key)) consumers.add(group.getKey());
        }
        // Reply dispatch and comment replies deliberately share the existing reply contract.
        if (consumers.contains(Feature.REPLY)) consumers.add(Feature.COMMENT_REPLY);
        if (consumers.contains(Feature.REPLY) || consumers.contains(Feature.COMMENT_AUTOMATION)
                || consumers.contains(Feature.COMMENT_REPLY)) consumers.add(Feature.AUTOMATION_DISPATCH);
        return new Setting(key, type, defaultValue, consumers);
    }

    private static void depend(Feature feature, String keys, String prefixes) {
        DEPENDENCIES.put(feature, new Dependencies(keys, prefixes));
    }
    private static void add(Map<String, String> keys, String type, String names) {
        for (String key : names.split(" ")) keys.put(key, type);
    }

    public static Map<String, Setting> settings() { return SETTINGS; }
    public static Map<String, String> transferableTypes() { return TRANSFER_TYPES; }

    public static boolean affects(Feature feature, ConfigurationChanges changes) {
        if (feature == null || changes == null) throw new IllegalArgumentException("Feature and changes are required");
        if (DEPENDENCIES.get(feature).changed(changes)) return true;
        if (feature == Feature.COMMENT_REPLY) return affects(Feature.REPLY, changes);
        if (feature == Feature.AUTOMATION_DISPATCH) {
            return affects(Feature.REPLY, changes) || affects(Feature.COMMENT_AUTOMATION, changes)
                    || affects(Feature.COMMENT_REPLY, changes);
        }
        return false;
    }

    /** Transfer keeps its existing type/size checks so legacy range values remain importable. */
    static void validateTransfer(String key, Object value) {
        String expected = TRANSFER_TYPES.get(key);
        if (expected == null || !("boolean".equals(expected) && value instanceof Boolean
                || "string".equals(expected) && value instanceof String
                || "int".equals(expected) && value instanceof Integer)) throw ConfigJson.invalid();
        if (value instanceof String && ((String) value).length() > ConfigTransfer.MAX_BYTES) throw ConfigJson.invalid();
    }

    public static String validatePlaybackSpeed(String value) {
        final float speed;
        try { speed = Float.parseFloat(value == null ? "" : value.trim()); }
        catch (NumberFormatException failure) { throw new IllegalArgumentException("请选择支持的倍速档位"); }
        if (!Float.isFinite(speed) || speed != PlaybackSpeedPresets.normalize(speed)) {
            throw new IllegalArgumentException("请选择支持的倍速档位");
        }
        return PlaybackSpeedPresets.normalize(value);
    }

    public static String normalizedPlaybackSpeed(String value) {
        return PlaybackSpeedPresets.normalize(value);
    }

    /** Type validation stays separate so version-1 backups can migrate old custom strings. */
    static Object normalizedTransferValue(String key, Object value) {
        return PLAYBACK_SPEED.equals(key) && value instanceof String
                ? PlaybackSpeedPresets.normalize((String) value) : value;
    }
    private ConfigurationCatalog() { }
}
