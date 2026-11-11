package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import com.douai.module.config.SidebarSettings;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.ProfileListEntry;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ModuleSettingsDependencyTest {
    private Activity activity;
    private SharedPreferences preferences;
    private HostSettingsStore store;
    private ModuleSettingsScreen screen;
    private View home;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        preferences = activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
        preferences.edit().clear().commit();
        store = new HostSettingsStore(activity);
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void close() {
        screen.dismissChildPages();
        activity.finish();
    }

    @Test public void interfaceChildrenFollowOnlyTheirOwnSwitchAndKeepTheirSavedSelections() {
        store.putString("hidden_top_tabs", "saved-tab");
        store.putString(SidebarSettings.ITEMS, "module,reply_history");
        View page = open("INTERFACE");
        hidden(page, "settings.option.设置顶部Tab");
        hidden(page, "settings.option.侧边栏显示项");
        visible(page, "settings.option.长按事件");
        visible(page, "settings.option.双击事件");
        visible(page, "settings.option.沉浸式全屏");
        toggle(page, "隐藏顶部Tab开关", true);
        visible(page, "settings.option.设置顶部Tab");
        hidden(page, "settings.option.侧边栏显示项");
        toggle(page, "首页右滑打开侧边栏", true);
        visible(page, "settings.option.侧边栏显示项");
        toggle(page, "隐藏顶部Tab开关", false);
        toggle(page, "首页右滑打开侧边栏", false);
        hidden(page, "settings.option.设置顶部Tab");
        hidden(page, "settings.option.侧边栏显示项");
        assertEquals("saved-tab", store.getString("hidden_top_tabs", ""));
        assertEquals("module,reply_history", store.getString(SidebarSettings.ITEMS, ""));
    }

    @Test public void filterMasterAndDurationParentsHideOnlyTheirDependentInputs() {
        store.putString("maximum_duration_seconds", "95");
        preferences.edit().putInt("minimum_duration_seconds", 7).apply();
        View page = open("FILTER");
        hidden(page, "settings.toggle.去广告开关");
        hidden(page, "settings.option.最低点赞数（0不限制）");
        hidden(page, "settings.option.屏蔽关键词");
        toggle(page, "过滤总开关", true);
        visible(page, "settings.toggle.去广告开关");
        visible(page, "settings.option.最低点赞数（0不限制）");
        visible(page, "settings.option.屏蔽关键词");
        hidden(page, "settings.option.最长时长（秒）");
        hidden(page, "settings.option.最短时长（秒）");
        toggle(page, "去长视频开关", true);
        toggle(page, "过滤短视频", true);
        visible(page, "settings.option.最长时长（秒）");
        visible(page, "settings.option.最短时长（秒）");
        toggle(page, "去长视频开关", false);
        hidden(page, "settings.option.最长时长（秒）");
        toggle(page, "过滤总开关", false);
        hidden(page, "settings.toggle.过滤短视频");
        hidden(page, "settings.option.最短时长（秒）");
        assertTrue(store.load().minimumDurationEnabled);
        assertEquals(7, store.load().minimumDurationSeconds);
        assertEquals("95", store.getString("maximum_duration_seconds", ""));
        toggle(page, "过滤总开关", true);
        visible(page, "settings.option.最短时长（秒）");
        visible(page, "settings.option.最低点赞数（0不限制）");
        visible(page, "settings.option.屏蔽关键词");
    }

    @Test public void officialNameTemplateDependsOnCustomNamingButSharedDownloadSettingsStayAvailable() {
        store.putString("download_name_template", "saved-{id}");
        View page = open("DOWNLOAD");
        assertFalse(store.load().downloadEnabled);
        visible(page, "settings.option.下载方式");
        visible(page, "settings.option.保存位置");
        visible(page, "settings.option.下载任务");
        visible(page, "settings.toggle.直接保存全部媒体");
        hidden(page, "settings.option.文件名模板");
        toggle(page, "自定义文件名", true);
        visible(page, "settings.option.文件名模板");
        toggle(page, "启用下载菜单", false);
        visible(page, "settings.option.文件名模板");
        toggle(page, "自定义文件名", false);
        hidden(page, "settings.option.文件名模板");
        assertEquals("saved-{id}", store.getString("download_name_template", ""));
        choose(page, "下载方式", 1);
        visible(page, "settings.option.文件命名字段");
        hidden(page, "settings.option.文件名模板");
    }

    @Test public void autoAdvanceChildrenDoNotHideSharedSpeedQualityOrInteractionControls() {
        store.putBoolean("smart_swipe", false);
        store.putBoolean("pause_auto_on_comments", true);
        store.putString("playback_speed", "2");
        View page = open("AUTOPLAY");
        hidden(page, "settings.toggle.打开评论时,不滑动视频");
        hidden(page, "settings.toggle.滑动智能判断上下");
        visible(page, "settings.option.倍速数值");
        visible(page, "settings.toggle.锁定最高画质");
        visible(page, "settings.option.互动自动化");
        toggle(page, "自动滑动到下一个视频", true);
        visible(page, "settings.toggle.打开评论时,不滑动视频");
        visible(page, "settings.toggle.滑动智能判断上下");
        toggle(page, "自动滑动到下一个视频", false);
        hidden(page, "settings.toggle.滑动智能判断上下");
        assertTrue(store.getBoolean("pause_auto_on_comments", false));
        assertFalse(store.getBoolean("smart_swipe", true));
        toggle(page, "仅模块控制倍速", true);
        visible(page, "settings.option.倍速数值");
        assertFalse(((Switch) page.findViewWithTag("settings.toggle.自动倍速播放")).isEnabled());
        assertEquals("2", store.getString("playback_speed", ""));
    }

    @Test public void commentExecutionChildrenFollowTheirOwnFeaturesWhileManualToolsRemain() {
        store.putString("comment_suffix", "saved-suffix");
        store.putString("comment_voice_path", "/saved/comment.mp3");
        store.putString("colored_comment_type", "33");
        View page = open("COMMENT");
        hidden(page, "settings.option.后缀内容");
        hidden(page, "settings.option.评论语音文件");
        hidden(page, "settings.option.彩色评论类型");
        visible(page, "settings.option.评论转语音");
        visible(page, "settings.option.文本转语音设置");
        visible(page, "settings.toggle.#长按发送转语音（全局）");
        visible(page, "settings.option.长按操作");
        toggle(page, "评论自动添加后缀", true);
        toggle(page, "替换语音文件", true);
        toggle(page, "彩色评论", true);
        visible(page, "settings.option.后缀内容");
        visible(page, "settings.option.评论语音文件");
        visible(page, "settings.option.彩色评论类型");
        toggle(page, "评论自动添加后缀", false);
        toggle(page, "替换语音文件", false);
        toggle(page, "彩色评论", false);
        hidden(page, "settings.option.后缀内容");
        hidden(page, "settings.option.评论语音文件");
        hidden(page, "settings.option.彩色评论类型");
        assertEquals("saved-suffix", store.getString("comment_suffix", ""));
        assertEquals("/saved/comment.mp3", store.getString("comment_voice_path", ""));
        assertEquals("33", store.getString("colored_comment_type", ""));
    }

    @Test public void streakExecutionControlsFollowEnableAndApiSourceButRecordsRemainManageable() {
        store.putString("streak_content", "saved-streak");
        store.putString("streak_content_api", "https://example.invalid/content");
        store.putString("streak_targets", "101");
        View page = open("STREAK");
        hidden(page, "settings.option.选择用户");
        hidden(page, "settings.option.续火花内容");
        hidden(page, "settings.toggle.续火花成功弹出Tip");
        hidden(page, "settings.option.续火花限制时间");
        hidden(page, "settings.option.内容Api");
        visible(page, "settings.option.重试结果未知的续火花");
        visible(page, "settings.option.清空续火花记录");
        toggle(page, "自动续火花", true);
        visible(page, "settings.option.选择用户");
        visible(page, "settings.option.内容Api");
        hidden(page, "settings.option.续火花内容");
        edit(page, "内容Api", "");
        visible(page, "settings.option.续火花内容");
        assertEquals("saved-streak", store.getString("streak_content", ""));
        toggle(page, "自动续火花", false);
        hidden(page, "settings.option.选择用户");
        visible(page, "settings.option.重试结果未知的续火花");
        visible(page, "settings.option.清空续火花记录");
        assertEquals("101", store.getString("streak_targets", ""));
    }

    @Test public void streakTargetSummaryCountsInitialSelectionAndBothWaysOfSaving() throws Exception {
        java.lang.reflect.Field contacts = NativeMessagingHooks.class.getDeclaredField("contacts");
        java.lang.reflect.Field profiles = NativeMessagingHooks.class.getDeclaredField("contactProfiles");
        contacts.setAccessible(true);
        profiles.setAccessible(true);
        Object previousContacts = contacts.get(null), previousProfiles = profiles.get(null);
        try {
            java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
            names.put("101", "Alice"); names.put("102", "Bob");
            java.util.Map<String, ProfileListEntry> identities = new java.util.LinkedHashMap<>();
            identities.put("101", new ProfileListEntry("101", "Alice", "", "alice.dy"));
            identities.put("102", new ProfileListEntry("102", "Bob", "", "bob.dy"));
            contacts.set(null, names);
            profiles.set(null, identities);
            store.putBoolean("streak_enabled", true);
            store.putString("streak_targets", "101,101");
            View page = open("STREAK");
            TextView summary = page.findViewWithTag("settings.value.选择用户");
            assertEquals("已选择 1 人", summary.getText().toString());

            assertTrue(page.findViewWithTag("settings.option.选择用户").performClick());
            ShadowLooper.idleMainLooper();
            AlertDialog picker = (AlertDialog) ShadowDialog.getLatestDialog();
            ListView list = picker.getWindow().getDecorView().findViewWithTag("settings.followLists.members");
            assertNotNull(list);
            for (int index = 0; index < list.getAdapter().getCount(); index++) {
                if ("102".equals(((ProfileListEntry) list.getAdapter().getItem(index)).uid)) {
                    assertTrue(list.getAdapter().getView(index, null, list).performClick());
                    break;
                }
            }
            picker.getButton(Dialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(2, com.douai.module.feature.ReplyPolicy.targets(store.getString("streak_targets", "")).size());
            assertEquals("已选择 2 人", summary.getText().toString());

            assertTrue(page.findViewWithTag("settings.option.选择用户").performClick());
            ShadowLooper.idleMainLooper();
            picker = (AlertDialog) ShadowDialog.getLatestDialog();
            picker.getButton(Dialog.BUTTON_NEUTRAL).performClick();
            ShadowLooper.idleMainLooper();
            AlertDialog manual = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = findInput(manual.getWindow().getDecorView(), "用户 UID，以逗号分隔");
            assertNotNull(input);
            input.setText("303,404,505,303");
            manual.getButton(Dialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(3, com.douai.module.feature.ReplyPolicy.targets(store.getString("streak_targets", "")).size());
            assertEquals("已选择 3 人", summary.getText().toString());
        } finally {
            contacts.set(null, previousContacts);
            profiles.set(null, previousProfiles);
        }
    }

    @Test public void recallAndVoiceFileChildrenHideWithoutBlockingIndependentSpeechManagement() {
        store.putBoolean("recall_self", false);
        store.putString("recall_background", "#2051BA55");
        store.putString("dm_voice_path", "/saved/private.mp3");
        View page = open("MESSAGE");
        hidden(page, "settings.toggle.自己撤回的消息");
        hidden(page, "settings.toggle.对方撤回的消息");
        hidden(page, "settings.option.撤回的背景色");
        hidden(page, "settings.option.私聊语音文件");
        visible(page, "settings.toggle.自动文本转语音");
        visible(page, "settings.option.文本转语音设置");
        toggle(page, "消息防撤回", true);
        visible(page, "settings.toggle.自己撤回的消息");
        visible(page, "settings.option.撤回的背景色");
        toggle(page, "消息防撤回", false);
        hidden(page, "settings.option.撤回的背景色");
        toggle(page, "替换私聊语音", true);
        visible(page, "settings.option.私聊语音文件");
        toggle(page, "替换私聊语音", false);
        hidden(page, "settings.option.私聊语音文件");
        assertFalse(store.getBoolean("recall_self", true));
        assertEquals("/saved/private.mp3", store.getString("dm_voice_path", ""));
        assertEquals("#2051BA55", store.getString("recall_background", ""));
    }

    @Test public void profileCountsUseAPreciseMasterAndVersionSpoofHasItsOwnIndependentParent() {
        store.putString("profile_like_count", "18");
        store.putString("fake_version_name", "saved-version");
        View page = open("SPOOF");
        assertNull("The profile switch must not imply it controls version spoofing", page.findViewWithTag("settings.toggle.伪装总开关"));
        visible(page, "settings.toggle.个人资料数量伪装");
        hidden(page, "settings.option.获赞数量");
        hidden(page, "settings.option.关注数量");
        hidden(page, "settings.option.粉丝数量");
        hidden(page, "settings.option.互关数量");
        visible(page, "settings.option.版本信息");
        assertTrue(page.findViewWithTag("settings.option.版本信息").performClick());
        Dialog version = ShadowDialog.getLatestDialog();
        View versionPage = version.getWindow().getDecorView();
        hidden(versionPage, "settings.option.版本名称");
        hidden(versionPage, "settings.option.版本号");
        toggle(versionPage, "版本信息修改", true);
        visible(versionPage, "settings.option.版本名称");
        visible(versionPage, "settings.option.版本号");
        assertFalse(store.getBoolean("spoof_profile", false));
        toggle(versionPage, "版本信息修改", false);
        hidden(versionPage, "settings.option.版本名称");
        assertEquals("saved-version", store.getString("fake_version_name", ""));
        version.dismiss(); ShadowLooper.idleMainLooper();
        toggle(page, "个人资料数量伪装", true);
        visible(page, "settings.option.获赞数量");
        visible(page, "settings.option.关注数量");
        visible(page, "settings.option.粉丝数量");
        visible(page, "settings.option.互关数量");
        toggle(page, "个人资料数量伪装", false);
        hidden(page, "settings.option.获赞数量");
        visible(page, "settings.option.版本信息");
        assertEquals("18", store.getString("profile_like_count", ""));
    }

    @Test public void extensionManagementRemainsAvailableWithEveryExecutionFeatureDisabled() {
        View page = open("EXTENSION");
        visible(page, "settings.option.AI 模型设置");
        visible(page, "settings.option.文本转语音设置");
        visible(page, "settings.option.本地数据分析");
        visible(page, "settings.option.配置备份");
        assertFalse(store.getBoolean("native_auto_reply", false));
        assertFalse(store.getBoolean("native_auto_comment", false));
        assertFalse(store.getBoolean("streak_enabled", false));
        assertFalse(store.load().automationEnabled);
    }

    @Test public void homeCategoryRowsDoNotPromiseFixedVisibleItemCounts() {
        ViewGroup list = (ViewGroup) home.findViewWithTag("settings.category.BASIC").getParent();
        for (int index = 0; index < list.getChildCount(); index++) assertNoCountBadge(list.getChildAt(index));
    }

    private static void assertNoCountBadge(View root) {
        if (root instanceof TextView && hierarchyVisible(root)) {
            String text = ((TextView) root).getText().toString();
            assertFalse("A static badge must not claim the current visible row count: " + text, text.matches("[0-9]+项"));
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) assertNoCountBadge(group.getChildAt(index));
        }
    }

    private View open(String category) {
        screen.dismissChildPages();
        View row = home.findViewWithTag("settings.category." + category);
        assertNotNull(category, row);
        assertTrue(row.performClick());
        ShadowLooper.idleMainLooper();
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static void toggle(View page, String title, boolean checked) {
        Switch control = page.findViewWithTag("settings.toggle." + title);
        assertNotNull(title, control);
        assertTrue(title, hierarchyVisible(control));
        control.setChecked(checked);
        ShadowLooper.idleMainLooper();
    }

    private static void edit(View page, String title, String value) {
        assertTrue(page.findViewWithTag("settings.option." + title).performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input." + title);
        assertNotNull(title, input);
        input.setText(value);
        editor.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
    }

    private static void choose(View page, String title, int index) {
        assertTrue(page.findViewWithTag("settings.option." + title).performClick());
        AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = choice.getListView();
        list.performItemClick(list.getAdapter().getView(index, null, list), index, index);
        ShadowLooper.idleMainLooper();
    }

    private static void visible(View page, String tag) { assertTrue(tag, hierarchyVisible(page.findViewWithTag(tag))); }
    private static void hidden(View page, String tag) { assertFalse(tag, hierarchyVisible(page.findViewWithTag(tag))); }
    private static EditText findInput(View root, String description) {
        if (root instanceof EditText && description.contentEquals(root.getContentDescription())) return (EditText) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = findInput(group.getChildAt(index), description);
                if (input != null) return input;
            }
        }
        return null;
    }
    private static boolean hierarchyVisible(View view) {
        if (view == null) return false;
        for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (current.getVisibility() != View.VISIBLE) return false;
        }
        return true;
    }
}
