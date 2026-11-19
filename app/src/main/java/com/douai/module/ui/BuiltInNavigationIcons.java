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

    private static final Map<String,String> SYMBOL_PATHS=Map.ofEntries(
            Map.entry("top/01-recommend","M12 2 14.4 9.1 22 12l-7.6 2.9L12 22l-2.4-7.1L2 12l7.6-2.9z"),
            Map.entry("top/02-following","M12 12a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm-7 9c0-3.5 2.8-6 7-6 1.5 0 2.8.3 3.8.9a6 6 0 0 0-.8 3.1c0 .7.1 1.4.4 2H5zm12-9v3h-3v2h3v3h2v-3h3v-2h-3v-3z"),
            Map.entry("top/03-featured","M12 2.5 14.9 8.4l6.6 1-4.8 4.6 1.1 6.6-5.8-3.1-5.8 3.1 1.1-6.6-4.8-4.6 6.6-1z"),
            Map.entry("top/04-trending","M3 19h18v2H3zm1-3 5-5 3 3 7-8 1.5 1.4-8.4 9.6-3.1-3.1-3.6 3.6zM14 6h7v7h-2V9.4l-4.3 4.3-1.4-1.4L17.6 7H14z"),
            Map.entry("top/05-nearby","M12 2a8 8 0 0 0-8 8c0 5.5 8 12 8 12s8-6.5 8-12a8 8 0 0 0-8-8zm0 11a3 3 0 1 1 0-6 3 3 0 0 1 0 6z"),
            Map.entry("top/06-live","M12 4a8 8 0 0 0-5.7 13.7l1.4-1.4a6 6 0 1 1 8.6 0l1.4 1.4A8 8 0 0 0 12 4zm0 4a4 4 0 0 0-2.8 6.8l1.4-1.4a2 2 0 1 1 2.8 0l1.4 1.4A4 4 0 0 0 12 8zm-1 8h2v6h-2z"),
            Map.entry("top/07-shop","M4 8h16l-1 13H5L4 8zm4-1a4 4 0 0 1 8 0h-2a2 2 0 0 0-4 0zM8 11v2a1 1 0 0 0 2 0v-2zm6 0v2a1 1 0 0 0 2 0v-2z"),
            Map.entry("top/08-drama","M3 4h18v16H3zm2 2v12h14V6zm5 2 6 4-6 4zM5 2h3v2H5zm5 0h3v2h-3zm5 0h3v2h-3z"),
            Map.entry("top/09-search","M10.5 3a7.5 7.5 0 1 0 4.6 13.4l5.2 5.2 1.4-1.4-5.2-5.2A7.5 7.5 0 0 0 10.5 3zm0 2a5.5 5.5 0 1 1 0 11 5.5 5.5 0 0 1 0-11z"),
            Map.entry("top/10-menu","M3 5h18v2H3zm0 6h18v2H3zm0 6h18v2H3z"),
            Map.entry("bottom/01-home","M2 11 12 3l10 8-1.5 1.8L19 11.6V21h-5v-6h-4v6H5v-9.4l-1.5 1.2z"),
            Map.entry("bottom/02-friends","M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm7-1a3 3 0 1 0 0-6 3 3 0 0 0 0 6zM2 21v-2c0-3 2.8-5 7-5s7 2 7 5v2zm14-7c3.8 0 6 1.8 6 4v3h-4v-2c0-2-1-3.8-2.8-4.6z"),
            Map.entry("bottom/03-create","M11 3h2v8h8v2h-8v8h-2v-8H3v-2h8z"),
            Map.entry("bottom/04-messages","M4 4h16a2 2 0 0 1 2 2v11a2 2 0 0 1-2 2h-9l-6 3v-3H4a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zm2 5v2h12V9zm0 4v2h8v-2z"),
            Map.entry("bottom/05-profile","M12 12a5 5 0 1 0 0-10 5 5 0 0 0 0 10zm0 2c-5.2 0-9 2.6-9 6v2h18v-2c0-3.4-3.8-6-9-6z"),
            Map.entry("feed/01-like","M8 10 12 3c.5-1 2-.6 2 .6V8h5a2 2 0 0 1 2 2l-1 9a2 2 0 0 1-2 2H8zm-5 0h3v11H3z"),
            Map.entry("feed/02-comment","M4 3h16a2 2 0 0 1 2 2v12a2 2 0 0 1-2 2h-9l-6 3v-3H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2zm3 5v2h10V8zm0 4v2h7v-2z"),
            Map.entry("feed/03-collect","M6 3h12a2 2 0 0 1 2 2v17l-8-4-8 4V5a2 2 0 0 1 2-2zm1 3v11.8l5-2.5 5 2.5V6z"),
            Map.entry("feed/04-share","M14 3l8 7-8 7v-4c-4.8 0-7.5 1.6-10 5 0-6.3 3.3-10 10-10zm-9 17c3.1-2.7 6.1-3.8 10-4v2c-4 .2-6.7.8-10 2z"),
            Map.entry("feed/05-follow","M10 12a4.5 4.5 0 1 0 0-9 4.5 4.5 0 0 0 0 9zm-8 9c0-3.5 3.1-6 8-6 1.9 0 3.5.4 4.8 1.1a6 6 0 0 0-.8 2.9v2H2zm14-7h2v3h3v2h-3v3h-2v-3h-3v-2h3z"),
            Map.entry("feed/06-mute","M3 9h4l5-4v14l-5-4H3zm11.5-.5 1.4-1.4 2.1 2.6 2.1-2.6 1.4 1.4-2.1 2.6 2.1 2.6-1.4 1.4-2.1-2.6-2.1 2.6-1.4-1.4 2.1-2.6z"),
            Map.entry("feed/07-sound","M3 9h4l5-4v14l-5-4H3zm12-1a6 6 0 0 1 0 8l-1.5-1.4a4 4 0 0 0 0-5.2zm2.8-2.8a10 10 0 0 1 0 13.6l-1.4-1.4a8 8 0 0 0 0-10.8z"));
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
        String path=SYMBOL_PATHS.get(entry.path);
        if(path==null)throw new IOException("内置图标矢量缺失");
        String opacity=style.selected?"1":".72";
        String svg="<svg xmlns='http://www.w3.org/2000/svg' width='256' height='256' viewBox='0 0 24.5 24'>"
                +"<path d='"+path+"' fill='#FFFFFF' fill-opacity='"+opacity+"' fill-rule='evenodd'/></svg>";
        try {byte[] generated=HomeIconImport.convert(svg.getBytes(StandardCharsets.UTF_8));byte[] prior=GENERATED.putIfAbsent(key,generated);return prior==null?generated:prior;}
        catch(IllegalArgumentException invalid){throw new IOException("内置图标无法生成",invalid);}
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
