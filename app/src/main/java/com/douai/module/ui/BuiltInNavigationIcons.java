package com.douai.module.ui;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;

/** Module-owned Java resources also load from the module class loader inside the host process. */
final class BuiltInNavigationIcons {
    static final class Entry {
        final String title,path;
        private Entry(String title,String path){this.title=title;this.path=path;}
    }
    enum Style {
        WHITE_OUTLINE("白色 · 线框","white",false),
        WHITE_FILLED("白色 · 填充","white",true),
        INK_OUTLINE("墨色 · 线框","ink",false),
        INK_FILLED("墨色 · 填充","ink",true);
        final String title,folder;final boolean filled;
        Style(String title,String folder,boolean filled){this.title=title;this.folder=folder;this.filled=filled;}
    }
    static final List<Entry> ENTRIES=List.of(
            new Entry("推荐","top/01-recommend"),new Entry("关注","top/02-following"),
            new Entry("精选","top/03-featured"),new Entry("热点","top/04-trending"),
            new Entry("同城","top/05-nearby"),new Entry("直播","top/06-live"),
            new Entry("商城","top/07-shop"),new Entry("短剧","top/08-drama"),
            new Entry("搜索","top/09-search"),new Entry("菜单","top/10-menu"),
            new Entry("首页","bottom/01-home"),new Entry("朋友","bottom/02-friends"),
            new Entry("发布","bottom/03-create"),new Entry("消息","bottom/04-messages"),
            new Entry("我","bottom/05-profile"));

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
            default:return null;
        }
        for(Entry entry:ENTRIES)if(entry.path.equals(path))return entry;
        return null;
    }

    static Style styleFor(IconState state) {
        if(state==null)throw new IllegalArgumentException("请选择图标状态");
        return state.night?(state.selected?Style.WHITE_FILLED:Style.WHITE_OUTLINE)
                :(state.selected?Style.INK_FILLED:Style.INK_OUTLINE);
    }

    static InputStream open(Entry entry,Style style) throws IOException {
        if(!ENTRIES.contains(entry) || style==null)throw new IllegalArgumentException("请选择内置图标及外观");
        String resource="/META-INF/douai/navigation-icons/"+style.folder+"/"+entry.path+(style.filled?"-active":"")+".svg";
        InputStream stream=BuiltInNavigationIcons.class.getResourceAsStream(resource);
        if(stream==null)throw new IOException("内置图标资源缺失");
        return stream;
    }
}
