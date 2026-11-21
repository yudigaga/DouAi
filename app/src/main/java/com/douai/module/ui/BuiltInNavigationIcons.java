package com.douai.module.ui;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;

/** Module-owned symbols also load from the module class loader inside the host process. */
public final class BuiltInNavigationIcons {
    static final class Entry {
        final String title,path;
        private Entry(String title,String path){this.title=title;this.path=path;}
    }
    enum Style {
        NORMAL("未选中",false), SELECTED("已选中",true);
        final String title;final boolean selected;
        Style(String title,boolean selected){this.title=title;this.selected=selected;}
    }
    static final List<Entry> ENTRIES=List.of(
            new Entry("推荐","top/01-recommend"),new Entry("关注","top/02-following"),
            new Entry("精选","top/03-featured"),new Entry("热点","top/04-trending"),
            new Entry("同城","top/05-nearby"),new Entry("直播","top/06-live"),
            new Entry("商城","top/07-shop"),new Entry("短剧","top/08-drama"),
            new Entry("搜索","top/09-search"),new Entry("菜单","top/10-menu"),
            new Entry("首页","bottom/01-home"),new Entry("朋友","bottom/02-friends"),
            new Entry("发布","bottom/03-create"),new Entry("消息","bottom/04-messages"),
            new Entry("我","bottom/05-profile"),
            new Entry("点赞","feed/01-like"),new Entry("评论","feed/02-comment"),
            new Entry("收藏","feed/03-collect"),new Entry("分享","feed/04-share"),
            new Entry("关注按钮","feed/05-follow"),new Entry("静音","feed/06-mute"),
            new Entry("开启声音","feed/07-sound"));

    private static final Map<String,Glyph> GLYPHS=Map.ofEntries(
            Map.entry("top/01-recommend", new Glyph(
                    "M12 3c1.4 5.4 3.6 7.6 9 9-5.4 1.4-7.6 3.6-9 9-1.4-5.4-3.6-7.6-9-9 5.4-1.4 7.6-3.6 9-9Z",
                    "M12 2.25c1.6 5.85 3.9 8.15 9.75 9.75-5.85 1.6-8.15 3.9-9.75 9.75C10.4 15.9 8.1 13.6 2.25 12 8.1 10.4 10.4 8.1 12 2.25Z")),
            Map.entry("top/02-following", new Glyph(
                    "M9 10.5a3.5 3.5 0 1 0 0-7 3.5 3.5 0 0 0 0 7ZM3.5 20v-1.5a5.5 5.5 0 0 1 5.5-5.5h.5a5.5 5.5 0 0 1 5.5 5.5V20H3.5ZM18 10v6m-3-3h6",
                    "M9 11.1a4.15 4.15 0 1 0 0-8.3 4.15 4.15 0 1 0 0 8.3ZM2.6 18.5a6.4 6.4 0 0 1 6.4-6.4h.5a6.4 6.4 0 0 1 6.4 6.4v1.2a1.2 1.2 0 0 1-1.2 1.2H3.8a1.2 1.2 0 0 1-1.2-1.2ZM16.8 9.3h2.4v2.5h2.5v2.4h-2.5v2.5h-2.4v-2.5h-2.5v-2.4h2.5Z")),
            Map.entry("top/03-featured", new Glyph(
                    "M12 3l2.8 5.7 6.3.9-4.55 4.43 1.08 6.27L12 17.35l-5.63 2.95 1.08-6.27L2.9 9.6l6.3-.9Z",
                    "M11.2 2.65l-2.7 5.47-6.04.88a.9.9 0 0 0-.5 1.53l4.37 4.26-1.03 6.02a.9.9 0 0 0 1.31.95L12 18.92l5.39 2.84a.9.9 0 0 0 1.31-.95L17.67 14.8l4.37-4.27a.9.9 0 0 0-.5-1.53l-6.04-.88-2.7-5.47a.9.9 0 0 0-1.6 0Z")),
            Map.entry("top/04-trending", new Glyph(
                    "M12.2 3c.4 4-2 5.4-3.4 7.6-1.1-1-1.4-1.9-1.5-3.1C5 10.2 4.5 12 4.5 14.2A7.5 7.5 0 0 0 12 21.7a7.5 7.5 0 0 0 7.5-7.5c0-3-1.5-5.7-4-7.8.1 1.5-.2 2.5-1 3.2-.1-3-1-5-2.3-6.6Z",
                    "M11.4 2.2c.6 3.7-1.5 5.8-2.7 7.4-1-1.1-1-2-1-3.4C4.9 8.9 3.7 11.5 3.7 14.4a8.3 8.3 0 0 0 16.6 0c0-3.8-1.8-6.7-4.8-9 .1 1.6-.2 2.6-.8 3.3-.5-2.9-1.5-5.1-3.3-6.5ZM12 13.2c.8 1.6 2.5 2.7 2.5 4.3a2.5 2.5 0 0 1-5 0c0-1.6 1.6-2.7 2.5-4.3Z")),
            Map.entry("top/05-nearby", new Glyph(
                    "M19 10c0 5-7 11-7 11S5 15 5 10a7 7 0 1 1 14 0ZM14.4 10a2.4 2.4 0 1 0-4.8 0 2.4 2.4 0 0 0 4.8 0Z",
                    "M12 2.2a7.8 7.8 0 0 0-7.8 7.8c0 5.6 7 11.6 7.3 11.9a.8.8 0 0 0 1 0c.3-.3 7.3-6.3 7.3-11.9A7.8 7.8 0 0 0 12 2.2Zm0 4.8a3 3 0 1 1 0 6 3 3 0 1 1 0-6Z")),
            Map.entry("top/06-live", new Glyph(
                    "M5.6 5.7a8.9 8.9 0 0 0 0 12.6m12.8-12.6a8.9 8.9 0 0 1 0 12.6M8.4 8.6a4.8 4.8 0 0 0 0 6.8m7.2-6.8a4.8 4.8 0 0 1 0 6.8M12 13.5a1.5 1.5 0 1 0 0-3 1.5 1.5 0 0 0 0 3ZM12 14v6",
                    "M4.95 4.15 6.8 6a8.5 8.5 0 0 0 0 12l-1.85 1.85a11.1 11.1 0 0 1 0-15.7Zm14.1 0a11.1 11.1 0 0 1 0 15.7L17.2 18a8.5 8.5 0 0 0 0-12Zm-10.8 3.3 1.65 1.65a4.1 4.1 0 0 0 0 5.8l-1.65 1.65a6.45 6.45 0 0 1 0-9.1Zm7.5 0a6.45 6.45 0 0 1 0 9.1L14.1 14.9a4.1 4.1 0 0 0 0-5.8ZM12 9.5a2.5 2.5 0 0 0-1.1 4.74v5.5a1.1 1.1 0 1 0 2.2 0v-5.5A2.5 2.5 0 0 0 12 9.5Z")),
            Map.entry("top/07-shop", new Glyph(
                    "M5 8.5h14l1 10a2 2 0 0 1-2 2.5H6a2 2 0 0 1-2-2.5l1-10ZM8.5 11V6.5a3.5 3.5 0 0 1 7 0V11",
                    "M7.5 8V6.5a4.5 4.5 0 0 1 9 0V8h2.7a1.3 1.3 0 0 1 1.29 1.16l.9 9.31A3.2 3.2 0 0 1 18.2 22H5.8a3.2 3.2 0 0 1-3.18-3.53l.9-9.3A1.3 1.3 0 0 1 4.8 8Zm2.1 0h4.8V6.5a2.4 2.4 0 1 0-4.8 0Zm-.8 2.4a1.15 1.15 0 1 0 0 2.3 1.15 1.15 0 1 0 0-2.3Zm6.4 0a1.15 1.15 0 1 0 0 2.3 1.15 1.15 0 1 0 0-2.3Z")),
            Map.entry("top/08-drama", new Glyph(
                    "M4 9.5h16v8.8a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V9.5Zm0 0-1-3.8L18 2.3l1 3.8L4 9.5Zm4-5 2.2 3.4m3-4.6 2.2 3.4M10.2 12.5l4.8 2.4-4.8 2.6Z",
                    "M2.3 5.5l16-3.7c.6-.14 1.23.24 1.37.84l.6 2.6a1 1 0 0 1-.75 1.2L3.6 10.2a1.25 1.25 0 0 1-1.5-.96l-.58-2.47c-.14-.58.2-1.12.78-1.27Zm5.1-.3 1.7-.4L11 7.35l-1.7.4Zm5.1-1.2 1.7-.4 1.9 2.55-1.7.4ZM3.2 10.6h17.6v7.7a2.8 2.8 0 0 1-2.8 2.8H6a2.8 2.8 0 0 1-2.8-2.8Zm6.6 1.8v6.3l5.9-3.15Z")),
            Map.entry("top/09-search", new Glyph(
                    "M17 10.4a6.6 6.6 0 1 1-13.2 0 6.6 6.6 0 0 1 13.2 0Zm-1.9 4.7L21 21",
                    "M10.4 2.7a7.7 7.7 0 0 1 6.2 12.3l4.4 4.4a1.45 1.45 0 0 1-2.05 2.05l-4.45-4.45A7.7 7.7 0 1 1 10.4 2.7Zm0 3.3a4.4 4.4 0 1 0 0 8.8 4.4 4.4 0 1 0 0-8.8Z")),
            Map.entry("top/10-menu", new Glyph(
                    "M5 6h14M5 12h14M5 18h14",
                    "M5.05 4.15h13.9a1.85 1.85 0 1 1 0 3.7H5.05a1.85 1.85 0 1 1 0-3.7Zm0 6h13.9a1.85 1.85 0 1 1 0 3.7H5.05a1.85 1.85 0 1 1 0-3.7Zm0 6h13.9a1.85 1.85 0 1 1 0 3.7H5.05a1.85 1.85 0 1 1 0-3.7Z")),
            Map.entry("bottom/01-home", new Glyph(
                    "M3.5 10.5 12 3l8.5 7.5V19a2 2 0 0 1-2 2H15v-5.5a1 1 0 0 0-1-1h-4a1 1 0 0 0-1 1V21H5.5a2 2 0 0 1-2-2z",
                    "M2.5 10 12 1.8 21.5 10v9a3 3 0 0 1-3 3h-4.2v-6.4h-4.6V22H5.5a3 3 0 0 1-3-3z")),
            Map.entry("bottom/02-friends", new Glyph(
                    "M13 7.6a3.6 3.6 0 1 1-7.2 0 3.6 3.6 0 0 1 7.2 0z M2.8 20v-1.4c0-2.4 2.6-4.2 6.6-4.2s6.6 1.8 6.6 4.2V20z M16 4.8a3 3 0 0 1 0 6 M18 14.4c2.3.5 3.4 1.9 3.4 4V20",
                    "M14 7.5a4.6 4.6 0 1 1-9.2 0 4.6 4.6 0 0 1 9.2 0z M1.8 21v-2.3c0-3 3.1-5.3 7.6-5.3s7.6 2.3 7.6 5.3V21z M15.1 3.7a3.9 3.9 0 1 1 0 7.6 6.3 6.3 0 0 0 0-7.6z M17.3 13.4c3 .2 5 2.2 5 5V21h-3.5v-2.3c0-2-.6-3.9-1.5-5.3z")),
            Map.entry("bottom/03-create", new Glyph(
                    "M6 3.5h12a2.5 2.5 0 0 1 2.5 2.5v12a2.5 2.5 0 0 1-2.5 2.5H6A2.5 2.5 0 0 1 3.5 18V6A2.5 2.5 0 0 1 6 3.5z M12 8v8 M8 12h8",
                    "M6 2.5h12A3.5 3.5 0 0 1 21.5 6v12a3.5 3.5 0 0 1-3.5 3.5H6A3.5 3.5 0 0 1 2.5 18V6A3.5 3.5 0 0 1 6 2.5z M11 7.4h2V11h3.6v2H13v3.6h-2V13H7.4v-2H11z")),
            Map.entry("bottom/04-messages", new Glyph(
                    "M6 4.5h12a2.5 2.5 0 0 1 2.5 2.5v8a2.5 2.5 0 0 1-2.5 2.5h-7L6 21v-3.5A2.5 2.5 0 0 1 3.5 15V7A2.5 2.5 0 0 1 6 4.5z M8 9h8 M8 13h5",
                    "M6 3.5h12A3.5 3.5 0 0 1 21.5 7v8a3.5 3.5 0 0 1-3.5 3.5h-6.8L5 22v-3.6A3.5 3.5 0 0 1 2.5 15V7A3.5 3.5 0 0 1 6 3.5z M7.8 8.1a1 1 0 1 0 0 2h8.4a1 1 0 1 0 0-2z M7.8 12a1 1 0 1 0 0 2h5.4a1 1 0 1 0 0-2z")),
            Map.entry("bottom/05-profile", new Glyph(
                    "M16 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0z M4.5 21v-1.1c0-2.8 3.1-5.1 7.5-5.1s7.5 2.3 7.5 5.1V21z",
                    "M17 7a5 5 0 1 1-10 0 5 5 0 0 1 10 0z M3.5 22v-2.1c0-3.4 3.5-6.1 8.5-6.1s8.5 2.7 8.5 6.1V22z")),
            Map.entry("feed/01-like", new Glyph(
                    "M12 20.4 4.7 13.3a5.2 5.2 0 0 1 7.3-7.4 5.2 5.2 0 0 1 7.3 7.4z",
                    "M12 21.8 4 14a6.2 6.2 0 0 1 8-9.4A6.2 6.2 0 0 1 20 14z")),
            Map.entry("feed/02-comment", new Glyph(
                    "M20.5 11a8.5 7.1 0 0 1-8.5 7.1c-.8 0-1.6-.1-2.4-.3L5 21l.9-5A6.6 6.6 0 0 1 3.5 11 8.5 7.1 0 0 1 12 3.9a8.5 7.1 0 0 1 8.5 7.1z M8 11h.1 M12 11h.1 M16 11h.1",
                    "M21.5 11c0 4.5-4.2 8.1-9.5 8.1-.8 0-1.5-.1-2.2-.2l-6.2 3.4 1.1-6C3.3 14.9 2.5 13 2.5 11c0-4.5 4.2-8.1 9.5-8.1s9.5 3.6 9.5 8.1z M9.1 11a1.1 1.1 0 1 1-2.2 0 1.1 1.1 0 0 1 2.2 0z M13.1 11a1.1 1.1 0 1 1-2.2 0 1.1 1.1 0 0 1 2.2 0z M17.1 11a1.1 1.1 0 1 1-2.2 0 1.1 1.1 0 0 1 2.2 0z")),
            Map.entry("feed/03-collect", new Glyph(
                    "M7 3.4h10a2 2 0 0 1 2 2v15.4l-7-4.2-7 4.2V5.4a2 2 0 0 1 2-2z",
                    "M7 2.4h10a3 3 0 0 1 3 3v15.4a1 1 0 0 1-1.5.9L12 17.8l-6.5 3.9a1 1 0 0 1-1.5-.9V5.4a3 3 0 0 1 3-3z")),
            Map.entry("feed/04-share", new Glyph(
                    "M14 4.5 21 11l-7 6.5V14c-4.5 0-7.4 1.5-10.5 5 .4-6.6 4.2-10.4 10.5-11z",
                    "M13 3.6a.8.8 0 0 1 1.3-.6l7.4 7.3a1 1 0 0 1 0 1.4L14.3 19a.8.8 0 0 1-1.3-.6V15c-4.4.2-6.9 1.8-9.4 4.7A.9.9 0 0 1 2 19c.5-6.6 4.4-10.6 11-11.8z")),
            Map.entry("feed/05-follow", new Glyph(
                    "M13.4 7a3.7 3.7 0 1 1-7.4 0 3.7 3.7 0 0 1 7.4 0z M3.2 21v-1.7c0-2.6 2.7-4.7 6.5-4.7 1.4 0 2.7.3 3.7.8 M18.3 13.8v7.4 M14.6 17.5H21.6",
                    "M14.4 7a4.7 4.7 0 1 1-9.4 0 4.7 4.7 0 0 1 9.4 0z M2.2 21.8v-2.5c0-3.3 3.2-5.7 7.5-5.7 1.3 0 2.5.2 3.5.6v7.6z M17.1 12.8h2.4v3.5h3.1v2.4h-3.1v3.5h-2.4v-3.5h-3.1v-2.4h3.1z")),
            Map.entry("feed/06-mute", new Glyph(
                    "M3 9h4l5-4v14l-5-4H3z M15.6 9l5.2 6 M20.8 9l-5.2 6",
                    "M2 8h4.6l5.9-4.6a.9.9 0 0 1 1.5.7v15.8a.9.9 0 0 1-1.5.7L6.6 16H2z M17 8.2l2 2.2 2-2.2 1.6 1.5-2.1 2.3 2.1 2.3-1.6 1.5-2-2.2-2 2.2-1.6-1.5 2.1-2.3-2.1-2.3z")),
            Map.entry("feed/07-sound", new Glyph(
                    "M3 9h4l5-4v14l-5-4H3z M15.8 9a4.3 4.3 0 0 1 0 6 M18.9 6.3a7.9 7.9 0 0 1 0 11.4",
                    "M2 8h4.6l5.5-4.6a.9.9 0 0 1 1.5.7v15.8a.9.9 0 0 1-1.5.7L6.6 16H2z M15.1 8.3l1.4-1.4a7.1 7.1 0 0 1 0 10.2l-1.4-1.4a5.2 5.2 0 0 0 0-7.4z M18.1 5.6l1.4-1.4a10.8 10.8 0 0 1 0 15.6l-1.4-1.4a8.8 8.8 0 0 0 0-12.8z")));
    private static final String NAVIGATION_GRADIENT="<defs><linearGradient id='navigation-rainbow'"
            +" x1='0%' y1='0%' x2='100%' y2='100%'>"
            +"<stop offset='0%' stop-color='#FF697E'/><stop offset='25%' stop-color='#FFD674'/>"
            +"<stop offset='50%' stop-color='#75E7B5'/><stop offset='76%' stop-color='#73C7FF'/>"
            +"<stop offset='100%' stop-color='#C29AFF'/></linearGradient></defs>";
    private static final Map<String,byte[]> GENERATED=new ConcurrentHashMap<>();
    private static volatile Map<String,Legacy> legacyHashes;

    private BuiltInNavigationIcons() { }

    static Entry entryForPosition(String key) {
        if(key==null)return null;
        String path;
        switch(HomeUiCustomizationSettings.canonicalNavigationKey(key)) {
            case "nav_bottom:homepage_home":path="bottom/01-home";break;
            case "nav_bottom:homepage_familiar":case "nav_top:homepage_familiar":path="bottom/02-friends";break;
            case "nav_bottom:homepage_notification":path="bottom/04-messages";break;
            case "nav_bottom:homepage_profile":path="bottom/05-profile";break;
            case "nav_top:homepage_hot":case "nav_top:homepage_hot_container":path="top/01-recommend";break;
            case "nav_top:homepage_follow":case "nav_bottom:homepage_follow":case "nav_bottom:FOLLOW":path="top/02-following";break;
            case "nav_top:homepage_mediumvideo":path="top/03-featured";break;
            case "nav_top:homepage_pad_hot":path="top/04-trending";break;
            case "nav_top:homepage_nearby":case "nav_bottom:homepage_nearby":case "nav_bottom:NEARBY":path="top/05-nearby";break;
            case "nav_top:homepage_tablive":path="top/06-live";break;
            case "nav_top:homepage_mall":case "nav_bottom:homepage_mall":case "nav_bottom:MALL":path="top/07-shop";break;
            case "nav_bottom:homepage_publish":case "nav_bottom:PUBLISH":path="bottom/03-create";break;
            case "nav_top:homepage_playlet_stream":path="top/08-drama";break;
            case "nav_top:homepage_right_search":path="top/09-search";break;
            case "nav_top:homepage_left_sidebar":case "nav_top:homepage_left_sidebar_jx":path="top/10-menu";break;
            case "feed_like":path="feed/01-like";break;
            case "feed_comment":path="feed/02-comment";break;
            case "feed_collect":path="feed/03-collect";break;
            case "feed_share":path="feed/04-share";break;
            case "feed_follow":path="feed/05-follow";break;
            case "feed_mute":path="feed/06-mute";break;
            default:return null;
        }
        for(Entry entry:ENTRIES)if(entry.path.equals(path))return entry;
        return null;
    }

    static Style styleFor(IconState state) {
        if(state==null)throw new IllegalArgumentException("请选择图标状态");
        return state.selected?Style.SELECTED:Style.NORMAL;
    }

    static InputStream open(Entry entry,Style style) throws IOException {
        if(!ENTRIES.contains(entry) || style==null)throw new IllegalArgumentException("请选择内置图标及外观");
        byte[] bytes=symbol(entry,style);
        return new ByteArrayInputStream(bytes);
    }

    public static byte[] symbolForPath(String path,boolean selected) throws IOException {
        for(Entry entry:ENTRIES)if(entry.path.equals(path))return symbol(entry,selected?Style.SELECTED:Style.NORMAL).clone();
        throw new IllegalArgumentException("未知内置图标");
    }

    /** Converts an already-saved version of the old built-in artwork for display only. */
    public static byte[] replaceLegacyArtwork(byte[] source) {
        if(source==null)return null;
        Legacy legacy=legacyHashes().get(sha256(source));
        if(legacy==null)return source;
        try(InputStream stream=open(legacy.entry,legacy.style)){return readBytes(stream);}
        catch(IOException unavailable){return source;}
    }

    private static byte[] symbol(Entry entry,Style style) throws IOException {
        String key=entry.path+(style.selected?"-active":"");
        byte[] cached=GENERATED.get(key);if(cached!=null)return cached;
        Glyph glyph=GLYPHS.get(entry.path);
        if(glyph==null)throw new IOException("内置图标矢量缺失");
        boolean rainbow=style.selected && (entry.path.startsWith("top/") || entry.path.startsWith("bottom/"));
        String artwork=style.selected
                ?"<path d='"+glyph.selected+"' fill='"+(rainbow?"url(#navigation-rainbow)":selectedFeedColor(entry.path))
                        +"' fill-rule='evenodd'/>"
                :"<path d='"+glyph.normal+"' fill='none' stroke='#FFFFFF' stroke-width='2' stroke-linecap='round' stroke-linejoin='round'/>";
        String svg="<svg xmlns='http://www.w3.org/2000/svg' width='256' height='256' viewBox='0 0 24 24'>"
                +(rainbow?NAVIGATION_GRADIENT:"")+artwork+"</svg>";
        try {byte[] generated=HomeIconImport.convert(svg.getBytes(StandardCharsets.UTF_8));byte[] prior=GENERATED.putIfAbsent(key,generated);return prior==null?generated:prior;}
        catch(IllegalArgumentException invalid){throw new IOException("内置图标无法生成",invalid);}
    }

    private static String selectedFeedColor(String path) {
        switch(path) {
            case "feed/01-like":return "#FF687D";
            case "feed/03-collect":return "#FFD475";
            default:return "#FFFFFF";
        }
    }

    private static final class Glyph {
        final String normal,selected;
        Glyph(String normal,String selected){this.normal=normal;this.selected=selected;}
    }

    private static Map<String,Legacy> legacyHashes() {
        Map<String,Legacy> cached=legacyHashes;if(cached!=null)return cached;
        synchronized(BuiltInNavigationIcons.class) {
            if(legacyHashes!=null)return legacyHashes;
            Map<String,Legacy> result=new HashMap<>();
            for(Entry entry:ENTRIES)for(Style style:Style.values()) {
                String resource="/META-INF/douai/navigation-icons/"+entry.path+(style.selected?"-active":"")+".webp";
                try(InputStream stream=BuiltInNavigationIcons.class.getResourceAsStream(resource)) {
                    if(stream!=null)result.put(sha256(readBytes(stream)),new Legacy(entry,style));
                }catch(IOException ignored){ }
            }
            legacyHashes=result;return result;
        }
    }

    private static byte[] readBytes(InputStream stream) throws IOException {
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        byte[] buffer=new byte[8192];
        int count;
        while((count=stream.read(buffer))!=-1) {
            if(count==0) {
                int value=stream.read();
                if(value==-1)break;
                output.write(value);
            }else output.write(buffer,0,count);
        }
        return output.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);StringBuilder out=new StringBuilder(digest.length*2);
            for(byte value:digest)out.append(Character.forDigit((value>>>4)&15,16)).append(Character.forDigit(value&15,16));
            return out.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }

    private static final class Legacy {
        final Entry entry;final Style style;
        Legacy(Entry entry,Style style){this.entry=entry;this.style=style;}
    }
}
