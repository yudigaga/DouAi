package com.douai.module.config;

import android.content.SharedPreferences;

/** Local, version-independent controls for feature modules outside the feed data model. */
public final class ExtendedSettings {
    public final ComponentHideSettings components;
    public final boolean hideInterface;
    public final int videoRightAlpha;
    public final boolean removeBlackEdges;
    public final boolean hideNavigationBar;
    public final boolean autoPlay;
    public final boolean playerGestures;
    public final boolean copyCommentText;
    public final boolean commentMediaTools;
    public final boolean copySourceData;
    public final boolean preventMessageRecall;
    public final boolean confirmRefresh;
    public final boolean spoofVersion;
    public final String fakeVersionName;
    public final String fakeVersionCode;
    public final String autoCommentText;
    public final String autoMessageText;

    private ExtendedSettings(SharedPreferences preferences) {
        components = ComponentHideSettings.readFrom(preferences);
        hideInterface = preferences.getBoolean("hide_interface", false);
        videoRightAlpha = readPercentage(preferences, "video_right_alpha", 100);
        removeBlackEdges = preferences.getBoolean("remove_black_edges", false);
        hideNavigationBar = preferences.getBoolean("hide_navigation_bar", false);
        autoPlay = preferences.getBoolean("auto_play", false);
        playerGestures = preferences.getBoolean("player_gestures", false);
        copyCommentText = preferences.getBoolean("copy_comment_text", false);
        commentMediaTools = preferences.getBoolean("comment_media_tools", false);
        copySourceData = preferences.getBoolean("copy_source_data", false);
        preventMessageRecall = preferences.getBoolean("prevent_message_recall", false);
        confirmRefresh = preferences.getBoolean("confirm_refresh", false);
        spoofVersion = preferences.getBoolean("spoof_version", false);
        fakeVersionName = safeString(preferences, "fake_version_name");
        fakeVersionCode = safeString(preferences, "fake_version_code");
        autoCommentText = safeString(preferences, "auto_comment_text");
        autoMessageText = safeString(preferences, "auto_message_text");
    }

    private ExtendedSettings(boolean hideStatusBar, boolean hideNavigation) {
        components = null;
        hideInterface = false;
        videoRightAlpha = 100;
        removeBlackEdges = hideStatusBar;
        hideNavigationBar = hideNavigation;
        autoPlay = false;
        playerGestures = false;
        copyCommentText = false;
        commentMediaTools = false;
        copySourceData = false;
        preventMessageRecall = false;
        confirmRefresh = false;
        spoofVersion = false;
        fakeVersionName = "";
        fakeVersionCode = "";
        autoCommentText = "";
        autoMessageText = "";
    }

    public static ExtendedSettings readFrom(SharedPreferences preferences) {
        return new ExtendedSettings(preferences);
    }

    /** Startup-only system bar settings; unrelated feature preferences are not parsed. */
    public static ExtendedSettings readSystemBars(SharedPreferences preferences) {
        return new ExtendedSettings(preferences.getBoolean("remove_black_edges", false),
                preferences.getBoolean("hide_navigation_bar", false));
    }

    private static String safeString(SharedPreferences preferences, String key) {
        String value = preferences.getString(key, "");
        return value == null ? "" : value.trim();
    }

    private static int readPercentage(SharedPreferences preferences, String key, int fallback) {
        try {
            return Math.max(0, Math.min(100,
                    Integer.parseInt(preferences.getString(key, Integer.toString(fallback)))));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }
}
