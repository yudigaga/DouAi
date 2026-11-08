package com.douai.module.config;

/** Opt-in settings for user-initiated AI features; automation keeps its own policy. */
public final class CommentAiSettings {
    public static final String TAKEOVER = "comment_ai_takeover";
    public static final String SEARCH = "manual_ai_douyin_search";
    public static final String MODEL_ID = "comment_ai_takeover_model_id";

    private CommentAiSettings() { }
}
