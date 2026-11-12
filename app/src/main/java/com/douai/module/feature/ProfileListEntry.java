package com.douai.module.feature;

/** Display identity captured from host models, with an optional native avatar URL. */
public final class ProfileListEntry {
    public final String uid;
    public final String name;
    public final String avatarUrl;
    public final String douyinId;
    public final String remarkName;
    public final boolean conversation;
    public final String selectionKey;
    final String senderName;

    public ProfileListEntry(String uid, String name, String avatarUrl) {
        this(uid, name, avatarUrl, "");
    }

    public ProfileListEntry(String uid, String name, String avatarUrl, String douyinId) {
        this(uid, name, avatarUrl, douyinId, "");
    }

    public ProfileListEntry(String uid, String name, String avatarUrl, String douyinId, String remarkName) {
        this(uid, name, avatarUrl, douyinId, uid, false, name, remarkName);
    }

    private ProfileListEntry(String uid, String name, String avatarUrl, String douyinId,
                             String key, boolean conversation, String senderName, String remarkName) {
        this.uid = uid == null ? "" : uid;
        this.name = name == null ? "" : name;
        this.avatarUrl = avatarUrl != null && AutomationImages.isMediaUrl(avatarUrl) ? avatarUrl : "";
        this.douyinId = publicId(douyinId == null ? "" : douyinId.trim());
        this.remarkName = remarkName == null ? "" : remarkName.trim();
        this.conversation = conversation;
        this.selectionKey = key == null ? "" : key;
        this.senderName = senderName == null ? "" : senderName;
    }

    public static ProfileListEntry named(String name) {
        return new ProfileListEntry("", name, "", "", name, false, name, "");
    }

    public static ProfileListEntry conversation(String id, String name, String avatarUrl) {
        return new ProfileListEntry(id, name, avatarUrl, "", id, true, name, "");
    }

    public ProfileListEntry withFallback(ProfileListEntry fallback) {
        if (fallback == null || !uid.equals(fallback.uid) || !selectionKey.equals(fallback.selectionKey)
                || conversation != fallback.conversation) return this;
        return new ProfileListEntry(uid, name.isEmpty() ? fallback.name : name,
                avatarUrl.isEmpty() ? fallback.avatarUrl : avatarUrl,
                douyinId.isEmpty() ? fallback.douyinId : douyinId, selectionKey, conversation,
                senderName.isEmpty() ? fallback.senderName : senderName,
                remarkName.isEmpty() ? fallback.remarkName : remarkName);
    }

    ProfileListEntry withSenderName(String value) {
        return new ProfileListEntry(uid, name, avatarUrl, douyinId, selectionKey, conversation, value, remarkName);
    }

    public ProfileListEntry withDisplayProfile(ProfileListEntry profile) {
        if (profile == null || uid.isEmpty() || conversation || profile.conversation || !uid.equals(profile.uid)) return this;
        return new ProfileListEntry(uid, profile.name.isEmpty() ? name : profile.name,
                profile.avatarUrl.isEmpty() ? avatarUrl : profile.avatarUrl,
                profile.douyinId.isEmpty() ? douyinId : profile.douyinId,
                selectionKey, conversation, senderName,
                profile.remarkName.isEmpty() ? remarkName : profile.remarkName);
    }

    static ProfileListEntry fromUser(String uid, Object user, ProfileListEntry fallback) {
        String name = nickname(user);
        String douyinId = publicId(text(user, "unique_id", new String[]{"uniqueId"}, "getUniqueId"));
        if (douyinId.isEmpty()) douyinId = publicId(text(user, "short_id", new String[]{"shortId"}, "getShortId"));
        return new ProfileListEntry(uid, name, avatar(user), douyinId, remarkName(user)).withFallback(fallback);
    }

    private static String remarkName(Object user) {
        if (user == null) return "";
        try {
            java.lang.reflect.Field field = DownloadActionHook.findFieldByJsonName(user.getClass(), "remark_name");
            if (field == null) {
                try { field = HostTargets.field(user.getClass(), "remarkName"); }
                catch (NoSuchFieldException absent) { }
            }
            if (field != null) {
                field.setAccessible(true);
                // A raw empty remark must not turn into a computed nickname through a display getter.
                return string(field.get(user));
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return ""; }
        return text(user, "remark_name", new String[0], "getRemarkName");
    }

    private static String nickname(Object user) {
        if (user == null) return "";
        try {
            java.lang.reflect.Field field = DownloadActionHook.findFieldByJsonName(user.getClass(), "nickname");
            if (field == null) {
                try { field = HostTargets.field(user.getClass(), "nickName"); }
                catch (NoSuchFieldException absent) { }
            }
            if (field != null) {
                field.setAccessible(true);
                // An empty raw nickname must not fall through to a host display-name override.
                return string(field.get(user));
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { return ""; }
        return text(user, "nickname", new String[0], "getNickname", "getNickName");
    }

    private static String text(Object user, String jsonName, String[] fields, String... getters) {
        String value = string(DownloadActionHook.jsonValue(user, jsonName));
        if (!value.isEmpty()) return value;
        for (String field : fields) {
            value = string(HostTargets.read(user, field));
            if (!value.isEmpty()) return value;
        }
        for (String getter : getters) {
            value = string(HostTargets.invoke(user, getter));
            if (!value.isEmpty()) return value;
        }
        return "";
    }

    private static String string(Object value) { return value instanceof String ? ((String) value).trim() : ""; }
    private static String publicId(String value) { return "0".equals(value) ? "" : value; }

    static String avatar(Object user) {
        String url = avatarUrl(DownloadActionHook.jsonValue(user, "avatar_thumb"));
        if (url.isEmpty()) url = avatarUrl(HostTargets.invoke(user, "getAvatarThumb"));
        if (url.isEmpty()) url = avatarUrl(HostTargets.invoke(user, "getDisplayAvatar"));
        return url;
    }

    private static String avatarUrl(Object avatar) {
        Object urls = avatar instanceof Iterable<?> ? avatar : DownloadActionHook.jsonValue(avatar, "url_list");
        String selected = firstAvatarUrl(urls);
        return selected.isEmpty() ? firstAvatarUrl(HostTargets.invoke(avatar, "getUrlList")) : selected;
    }

    private static String firstAvatarUrl(Object urls) {
        if (urls instanceof Iterable<?>) for (Object value : (Iterable<?>) urls) {
            if (value instanceof String && AutomationImages.isMediaUrl((String) value)) return (String) value;
        }
        return "";
    }
}
