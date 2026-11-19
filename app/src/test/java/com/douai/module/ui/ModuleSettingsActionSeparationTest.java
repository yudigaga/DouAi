package com.douai.module.ui;

import android.app.Activity;
import android.view.View;
import android.widget.EditText;
import android.widget.Switch;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SidebarSettings;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.scan.HostAdaptation;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,packageName="com.douai.module")
public final class ModuleSettingsActionSeparationTest {
    private Activity activity;
    private HostSettingsStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private HostAdaptation.Profile priorProfile;

    @Before public void openSettings() {
        priorProfile=HostAdaptation.currentProfile();
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        store=new HostSettingsStore(activity);
        store.save(FeatureSettings.defaults());
        store.putBoolean("feed_filter_enabled",true);
        screen=new ModuleSettingsScreen(activity,store);
        home=screen.createView();
        activity.setContentView(home);
    }

    @After public void closeSettings() {
        if(screen!=null){screen.dismissChildPages();screen.close();}
        if(activity!=null)activity.finish();
        HostAdaptation.setCurrentProfile(priorProfile);
    }

    @Test public void currentFeedActionsStayInMenusWhileSettingsKeepTheirControls() {
        View playback=open("AUTOPLAY");
        assertMissing(playback,"定时或看完后暂停");
        assertMissing(playback,"作者倍速规则");
        assertPresent(playback,"settings.toggle.作者独立倍速");
        screen.dismissChildPages();

        View filters=open("FILTER");
        assertMissing(filters,"临时过滤规则");
        assertPresent(filters,"settings.toggle.作者和配乐临时过滤");
        assertPresent(filters,"settings.option.清空已看记录");
        screen.dismissChildPages();

        View comments=open("COMMENT");
        assertMissing(comments,"查找当前作品评论");
        assertPresent(comments,"settings.toggle.查看和复制语音转写");
        assertPresent(comments,"settings.toggle.在已加载评论中查找");
        assertPresent(comments,"settings.option.长按操作");
        screen.dismissChildPages();

        View extensions=open("EXTENSION");
        assertMissing(extensions,"我的时间点书签");
        assertMissing(extensions,"评论收藏");
        assertMissing(extensions,"本地数据分析");
        assertPresent(extensions,"settings.option.AI 模型设置");
        assertPresent(extensions,"settings.option.配置备份");

        List<String> feedActions=Arrays.asList("stop_budget","author_speed_rules","temporary_feed_rules",
                "comment_search","bookmark_list","comment_bookmarks");
        for(String action:feedActions) {
            assertTrue("gesture catalog: "+action,Arrays.asList(FeedGestureToolsHook.ACTION_IDS).contains(action));
            assertNotNull("sidebar catalog: "+action,SidebarSettings.find(action));
        }
    }

    @Test public void sidebarBrowseDestinationsAreNotDuplicatedInSettingsPages() {
        View downloads=open("DOWNLOAD");
        assertMissing(downloads,"下载任务");
        assertPresent(downloads,"settings.option.下载方式");
        assertNotNull(SidebarSettings.find("download_tasks"));
        screen.dismissChildPages();

        View extensions=open("EXTENSION");
        assertMissing(extensions,"本地数据分析");
        assertPresent(extensions,"settings.option.配置备份");
        assertNotNull(SidebarSettings.find("analytics"));
        assertNotNull(SidebarSettings.find("ai_chat"));
        screen.dismissChildPages();

        View reply=open("AUTO_REPLY");
        option(reply,"发送与记录").performClick();
        View replyDelivery=ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertMissing(replyDelivery,"回复历史记录");
        assertPresent(replyDelivery,"settings.toggle.保存历史记录");
        screen.dismissChildPages();

        View comment=open("AUTO_COMMENT");
        option(comment,"发送与记录").performClick();
        View commentDelivery=ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertMissing(commentDelivery,"评论历史记录");
        assertPresent(commentDelivery,"settings.toggle.保存历史记录");
        assertNotNull(SidebarSettings.find("comment_history"));
        assertNotNull(SidebarSettings.find("reply_history"));
    }

    @Test public void settingsSearchDoesNotExposeSidebarBrowseLaunchers() {
        EditText search=home.findViewWithTag("settings.search");
        for(String[] item:new String[][]{{"下载任务","download-tasks"},{"本地数据分析","analytics"},
                {"AI 测试对话","ai-chat"}}) {
            search.setText(item[0]);
            assertNull("settings.search.result."+item[1],
                    home.findViewWithTag("settings.search.result."+item[1]));
        }
        search.setText("本地数据分析");
        assertEquals(View.GONE, home.findViewWithTag("settings.category.EXTENSION").getVisibility());
    }

    private View open(String category) {
        View row=home.findViewWithTag("settings.category."+category);
        assertNotNull(category,row);
        assertTrue(row.performClick());
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static View option(View page,String title) {
        View result=page.findViewWithTag("settings.option."+title);
        assertNotNull(title,result);
        return result;
    }

    private static void assertMissing(View page,String title) {
        assertNull(title,page.findViewWithTag("settings.option."+title));
    }

    private static void assertPresent(View page,String tag) {
        assertNotNull(tag,page.findViewWithTag(tag));
    }
}
