package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.feature.FollowListPager;
import com.douai.module.feature.FollowListSnapshot;
import com.douai.module.feature.NativeFollowLists;

import org.junit.After;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ModuleSettingsReferenceTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private NativeFollowLists followLists;

    @Before
    public void createScreen() {
        FollowListSnapshot.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After
    public void closeScreen() {
        screen.dismissChildPages();
        if (followLists != null) followLists.close();
        FollowListSnapshot.reset();
        activity.finish();
    }

    @Test
    public void referenceSubpagesHaveExactItemOrderAndNoExtraInlineInputRows() {
        String[][] references = {
                {"BASIC", "去除启动和闪屏广告", "去除更新弹窗", "去除青少年弹窗"},
                {"INTERFACE", "悬浮条", "隐藏拍摄+号图标", "隐藏上下黑边", "禁用双击点赞",
                        "不允许系统跳过已隐藏视频", "不允许中途插入视频", "拦截长按倍数启用场景",
                        "拦截系统长按手势", "长按事件", "双击事件", "沉浸式全屏", "隐藏顶部Tab开关",
                        "设置顶部Tab", "首页刷新确认"},
                {"FILTER", "过滤总开关", "整页筛空时保留原列表", "去广告开关", "去直播开关", "去推荐卡片开关",
                        "去推荐商家开关", "去商品/购物卡片开关", "去热点榜单卡开关", "去小游戏开关", "去长视频开关", "最长时长（秒）",
                        "最低点赞数（0不限制）", "去图集开关", "屏蔽关键词", "过滤短视频", "最短时长（秒）"},
                {"DOWNLOAD", "启用下载菜单", "下载方式", "保存位置", "下载任务", "直接保存全部媒体", "自定义文件名", "文件名模板", "去除下载限制", "无水印下载图集或者视频"},
                {"AUTOPLAY", "自动滑动到下一个视频", "仅模块控制倍速", "自动倍速播放", "倍速数值", "打开评论时,不滑动视频",
                        "滑动智能判断上下", "互动自动化"},
                {"AUTO_REPLY", "开启自动回复", "回复模式", "回复内容", "素材识别", "语音回复", "接收私聊", "私聊范围", "接收群聊", "群聊范围",
                        "接收评论回复", "每日最大回复数", "同一用户回复间隔（分钟）", "发送与记录"},
                {"AUTO_COMMENT", "开启自动评论", "评论模式", "评论内容", "语音评论", "作品范围", "素材识别",
                        "每日最大评论数", "同一作者评论间隔（分钟）", "发送与记录"},
                {"COMMENT", "评论自动添加后缀", "后缀内容", "替换语音文件", "评论语音文件",
                        "评论转语音", "文本转语音设置", "#长按发送转语音（全局）", "长按操作", "彩色评论", "彩色评论类型"},
                {"STREAK", "自动续火花", "选择用户", "续火花内容", "续火花成功弹出Tip",
                        "重试结果未知的续火花", "清空续火花记录", "续火花限制时间", "内容Api"},
                {"MESSAGE", "消息防撤回", "自己撤回的消息", "对方撤回的消息", "撤回的背景色", "替换私聊语音",
                        "私聊语音文件", "自动文本转语音", "文本转语音设置"},
                {"SPOOF", "伪装总开关", "获赞数量", "关注数量", "粉丝数量", "互关数量", "版本信息"},
                {"EXTENSION", "AI 模型设置", "文本转语音设置", "本地数据分析", "配置备份"}
        };
        for (String[] reference : references) {
            View page = openCategory(reference[0]);
            assertEquals(reference[0], Arrays.asList(Arrays.copyOfRange(reference, 1, reference.length)),
                    settingTitles(page));
            assertEquals(reference[0] + " must open editors from a single row", 0,
                    collect(page, EditText.class).size());
            screen.dismissChildPages();
        }
    }

    @Test public void searchFindsMajorFeaturePagesAndOnlyNavigates() {
        Map<String, Object> saved = new HashMap<>(store.values);
        EditText search = home.findViewWithTag("settings.search");
        String[][] cases = {{"API Key", "ai", "扩展功能 > AI 模型设置", "settings.option.添加模型配置"},
                {"minimax", "speech", "扩展功能 > 文本转语音设置", "settings.option.语音来源"},
                {"保存目录", "download-directory", "下载相关 > 保存位置", "settings.option.保存位置"},
                {"去水印", "download-watermark", "下载相关 > 无水印下载图集或者视频", "settings.toggle.无水印下载图集或者视频"},
                {"自动评论", "auto-comment", "自动评论 > 评论内容与作品范围", "settings.toggle.开启自动评论"},
                {"私信自动回复", "auto-reply", "自动回复 > 私聊与群聊", "settings.toggle.开启自动回复"},
                {"评论回复", "comment-reply", "自动回复 > 接收评论回复", "settings.toggle.接收评论回复"},
                {"导入配置", "backup", "扩展功能 > 配置备份", "config.backup.import"},
                {"PDF", "analytics", "扩展功能 > 本地数据分析", "analytics.root"},
                {"调试", "debug-logs", "关于 > 调试与日志", "debug.logs.enabled"},
                {"#长按", "hash-long-press-voice", "评论相关 > #长按发送转语音（全局）", "settings.toggle.#长按发送转语音（全局）"},
                {"仅模块控制倍速", "module-only-speed", "自动播放 > 仅模块控制倍速", "settings.toggle.仅模块控制倍速"}};
        for (String[] item : cases) {
            search.setText(item[0]);
            View result = home.findViewWithTag("settings.search.result." + item[1]);
            assertNotNull(item[0], result);
            assertEquals(View.VISIBLE, result.getVisibility());
            assertTrue(containsText(result, item[2]));
            result.performClick();
            Dialog destination = ShadowDialog.getLatestDialog();
            assertTrue(destination.isShowing());
            assertNotNull(destination.getWindow().getDecorView().findViewWithTag(item[3]));
            assertEquals(saved, store.values);
            screen.dismissChildPages();
        }
        search.setText("");
        assertEquals(View.GONE, home.findViewWithTag("settings.search.results").getVisibility());
        assertEquals(saved, store.values);
    }

    @Test public void downloadSearchScrollsToEachSpecificRowAndRestoresItsHighlight() {
        EditText search = home.findViewWithTag("settings.search");
        String[][] targets = {{"download-mode", "settings.option.下载方式"},
                {"download-directory", "settings.option.保存位置"},
                {"download-tasks", "settings.option.下载任务"},
                {"download-name", "settings.option.文件名模板"},
                {"download-watermark", "settings.toggle.无水印下载图集或者视频"}};
        Map<String, Object> saved = new HashMap<>(store.values);
        for (String[] target : targets) {
            search.setText("下载");
            home.findViewWithTag("settings.search.result." + target[0]).performClick();
            Dialog dialog = ShadowDialog.getLatestDialog();
            View decor = dialog.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(260, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, 360, 260);
            ShadowLooper.idleMainLooper();
            View row = decor.findViewWithTag(target[1]);
            if (row instanceof Switch) row = (View) row.getParent();
            assertEquals("settings.target." + target[0], row.getTag(com.douai.module.R.id.settings_search_target));
            assertTrue(target[0], row.isActivated());
            assertNotNull(row.getForeground());
            if ("download-watermark".equals(target[0])) {
                assertTrue(collect(decor, android.widget.ScrollView.class).get(0).getScrollY() > 0);
            }
            ShadowLooper.idleMainLooper(2, TimeUnit.SECONDS);
            assertFalse(row.isActivated());
            assertNull(row.getForeground());
            assertEquals(saved, store.values);
            screen.dismissChildPages();
        }
    }

    @Test public void commentMainPageKeepsGlobalLongPressBesideTheExistingSpeechPage() {
        store.putBoolean("comment_tts", true);
        store.putBoolean("dm_tts", true);
        View comment = openCategory("COMMENT");
        assertNull(comment.findViewWithTag("settings.toggle.评论自动文本转语音"));
        Switch global = toggle(comment, "#长按发送转语音（全局）");
        assertFalse(global.isChecked());
        assertTrue(containsText(comment, "适用于私信、群聊和评论"));
        assertTrue(containsText(comment, "只发送语音，不发送文字"));
        global.setChecked(true);
        option(comment, "评论转语音").performClick();
        View page = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(toggle(page, "评论自动文本转语音").isChecked());
        assertNull(page.findViewWithTag("settings.toggle.#长按发送转语音（全局）"));
        assertTrue(store.getBoolean("hash_long_press_voice", false));
        assertTrue(store.getBoolean("comment_tts", false));
        assertTrue(store.getBoolean("dm_tts", false));
    }

    @Test public void unavailableOfficialSearchTargetsExplainTheSelectedModeWithoutChangingIt() {
        store.putString(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
        Map<String, Object> saved = new HashMap<>(store.values);
        EditText search = home.findViewWithTag("settings.search");
        search.setText("去水印");
        home.findViewWithTag("settings.search.result.download-watermark").performClick();
        View decor = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        View mode = decor.findViewWithTag("settings.option.下载方式");
        assertEquals("settings.target.download-mode", mode.getTag(com.douai.module.R.id.settings_search_target));
        assertNull(decor.findViewWithTag("settings.toggle.无水印下载图集或者视频"));
        assertTrue(org.robolectric.shadows.ShadowToast.getTextOfLatestToast().contains("当前使用第三方下载"));
        assertEquals(saved, store.values);
    }

    @Test public void closingTheScreenCancelsDeferredSearchFocusAndRejectsStaleNavigation() {
        EditText search = home.findViewWithTag("settings.search");
        search.setText("保存目录");
        View result = home.findViewWithTag("settings.search.result.download-directory");
        result.performClick();
        Dialog dialog = ShadowDialog.getLatestDialog();
        View row = dialog.getWindow().getDecorView().findViewWithTag("settings.option.保存位置");
        screen.close();
        ShadowLooper.idleMainLooper(3, TimeUnit.SECONDS);
        assertFalse(dialog.isShowing());
        assertFalse(row.isActivated());
        assertNull(row.getForeground());
        result.performClick();
        assertSame(dialog, ShadowDialog.getLatestDialog());
        assertFalse(dialog.isShowing());
    }

    @Test public void settingsSwitchRowsKeepCompactLabelsAndVoiceCommentExplainsItsContentPolicy() {
        store.putString("playback_speed", "1.75");
        for (String category : new String[]{"BASIC", "INTERFACE", "FILTER", "DOWNLOAD", "AUTOPLAY",
                "AUTO_REPLY", "AUTO_COMMENT", "COMMENT", "STREAK", "MESSAGE", "SPOOF"}) {
            View page = openCategory(category);
            for (Switch control : collect(page, Switch.class)) {
                ViewGroup row = (ViewGroup) control.getParent();
                ViewGroup labels = (ViewGroup) row.getChildAt(0);
                int visibleLabels = 0;
                for (int index = 0; index < labels.getChildCount(); index++) {
                    if (labels.getChildAt(index).getVisibility() == View.VISIBLE) visibleLabels++;
                }
                boolean voiceComment = "语音评论".contentEquals(control.getContentDescription());
                boolean globalHashVoice = "#长按发送转语音（全局）".contentEquals(control.getContentDescription());
                boolean privateMessageVoice = "MESSAGE".equals(category)
                        && "自动文本转语音".contentEquals(control.getContentDescription());
                boolean moduleOnlySpeed = "仅模块控制倍速".contentEquals(control.getContentDescription());
                assertEquals(category + ": " + control.getContentDescription(),
                        voiceComment || globalHashVoice || privateMessageVoice || moduleOnlySpeed ? 2 : 1, visibleLabels);
                if (globalHashVoice) {
                    assertTrue(containsText(labels, "适用于私信、群聊和评论"));
                    assertTrue(containsText(labels, "只发送语音，不发送文字"));
                }
                if (voiceComment) {
                    assertTrue(containsText(labels, "最终使用完整正文，不截断音频"));
                    assertTrue(containsText(labels, "实际上传失败仍遵循原回退与防重"));
                    assertFalse(containsText(labels, "30秒"));
                }
                if (privateMessageVoice) {
                    assertTrue(containsText(labels, "私信以 # 开头时，单击发送转为语音。"));
                }
            }
            if ("AUTOPLAY".equals(category)) {
                assertEquals("1.5x", ((TextView) page.findViewWithTag("settings.value.倍速数值")).getText().toString());
                assertFalse(containsText(page, "0.25 至 4"));
            }
            if ("AUTO_REPLY".equals(category)) {
                assertEquals("固定内容", ((TextView) page.findViewWithTag("settings.value.回复模式")).getText().toString());
            }
            screen.dismissChildPages();
        }
    }

    @Test public void thirdPartyDownloadsKeepDefaultBehaviorAboveTheirParserConfiguration() {
        store.putString(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
        store.putString(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
        View page = openCategory("DOWNLOAD");
        assertEquals(Arrays.asList("启用下载菜单", "下载方式", "保存位置", "下载任务", "直接保存全部媒体",
                "GET 请求", "请求参数", "签名方式", "请求头", "响应样本", "下载字段", "文件命名字段", "测试 GET"),
                settingTitles(page));
        Switch direct = toggle(page, "直接保存全部媒体");
        assertFalse(direct.isChecked());
        assertFalse(containsText((View) direct.getParent(), "开启后解析成功即保存所选下载字段，不显示下载选择。"));
        direct.setChecked(true);
        assertTrue(toggle(page, "启用下载菜单").isChecked());
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.getBoolean(ThirdPartyDownloadSettings.DIRECT, false));
        assertFalse(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        assertNull(page.findViewWithTag("settings.option.文件名模板"));
    }

    @Test
    public void automationLimitsAreSeparateTopLevelSettingsAndPersistIndependently() {
        View reply = openCategory("AUTO_REPLY");
        assertEquals("0", ((TextView) reply.findViewWithTag("settings.value.每日最大回复数")).getText().toString());
        assertEquals("0", ((TextView) reply.findViewWithTag("settings.value.同一用户回复间隔（分钟）")).getText().toString());
        assertNull(reply.findViewWithTag("settings.option.每日最大评论数"));
        assertNull(reply.findViewWithTag("settings.option.同一作者评论间隔（分钟）"));
        assertFalse(containsText(option(reply, "每日最大回复数"), "本地自然日"));
        assertFalse(containsText(option(reply, "同一用户回复间隔（分钟）"), "UID"));
        editOption(reply, "每日最大回复数", "120");
        editOption(reply, "同一用户回复间隔（分钟）", "15");
        assertFalse(store.values.containsKey("comment_daily_limit"));
        assertFalse(store.values.containsKey("comment_user_cooldown_minutes"));
        option(reply, "发送与记录").performClick();
        View replyDelivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(replyDelivery.findViewWithTag("settings.option.每日最大回复数"));
        assertNull(replyDelivery.findViewWithTag("settings.option.同一用户回复间隔（分钟）"));

        screen.dismissChildPages();
        View comment = openCategory("AUTO_COMMENT");
        assertEquals("0", ((TextView) comment.findViewWithTag("settings.value.每日最大评论数")).getText().toString());
        assertEquals("0", ((TextView) comment.findViewWithTag("settings.value.同一作者评论间隔（分钟）")).getText().toString());
        assertNull(comment.findViewWithTag("settings.option.每日最大回复数"));
        assertNull(comment.findViewWithTag("settings.option.同一用户回复间隔（分钟）"));
        editOption(comment, "每日最大评论数", "40");
        editOption(comment, "同一作者评论间隔（分钟）", "60");
        assertEquals("120", store.getString("reply_daily_limit", ""));
        assertEquals("15", store.getString("reply_user_cooldown_minutes", ""));
        assertEquals("40", store.getString("comment_daily_limit", ""));
        assertEquals("60", store.getString("comment_user_cooldown_minutes", ""));
        option(comment, "发送与记录").performClick();
        View commentDelivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(commentDelivery.findViewWithTag("settings.option.每日最大评论数"));
        assertNull(commentDelivery.findViewWithTag("settings.option.同一作者评论间隔（分钟）"));

        screen.dismissChildPages();
        reply = openCategory("AUTO_REPLY");
        assertEquals("120", ((TextView) reply.findViewWithTag("settings.value.每日最大回复数")).getText().toString());
        assertEquals("15", ((TextView) reply.findViewWithTag("settings.value.同一用户回复间隔（分钟）")).getText().toString());
        screen.dismissChildPages();
        comment = openCategory("AUTO_COMMENT");
        assertEquals("40", ((TextView) comment.findViewWithTag("settings.value.每日最大评论数")).getText().toString());
        assertEquals("60", ((TextView) comment.findViewWithTag("settings.value.同一作者评论间隔（分钟）")).getText().toString());
    }

    @Test public void automaticVoiceSwitchesDefaultOffAndPersistIndependently() {
        View reply = openCategory("AUTO_REPLY");
        assertFalse(toggle(reply, "语音回复").isChecked());
        toggle(reply, "语音回复").setChecked(true);
        assertTrue(store.getBoolean("reply_voice", false));
        assertFalse(store.getBoolean("comment_voice", false));
        screen.dismissChildPages();
        View comment = openCategory("AUTO_COMMENT");
        assertFalse(toggle(comment, "语音评论").isChecked());
        toggle(comment, "语音评论").setChecked(true);
        assertTrue(store.getBoolean("comment_voice", false));
        assertTrue(store.getBoolean("reply_voice", false));
    }

    @Test
    public void automationLimitsRejectInvalidValuesAndAcceptZeroAndUpperBounds() {
        String[][] fields = {
                {"AUTO_REPLY", "每日最大回复数", "reply_daily_limit", "1000000"},
                {"AUTO_REPLY", "同一用户回复间隔（分钟）", "reply_user_cooldown_minutes", "525600"},
                {"AUTO_COMMENT", "每日最大评论数", "comment_daily_limit", "1000000"},
                {"AUTO_COMMENT", "同一作者评论间隔（分钟）", "comment_user_cooldown_minutes", "525600"}
        };
        for (String[] field : fields) {
            View page = openCategory(field[0]);
            editOption(page, field[1], "7");
            option(page, field[1]).performClick();
            AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input." + field[1]);
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
            for (String invalid : Arrays.asList("", "-1", "+1", "1.5", "invalid", "2147483648",
                    Integer.toString(Integer.parseInt(field[3]) + 1))) {
                input.setText(invalid);
                editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                ShadowLooper.idleMainLooper();
                assertTrue(field[1] + ": " + invalid, editor.isShowing());
                assertEquals(field[1] + ": " + invalid, "7", store.getString(field[2], ""));
                assertEquals("7", ((TextView) page.findViewWithTag("settings.value." + field[1])).getText().toString());
            }
            editor.dismiss();
            ShadowLooper.idleMainLooper();
            editOption(page, field[1], field[3]);
            assertEquals(field[3], store.getString(field[2], ""));
            editOption(page, field[1], "0");
            assertEquals("0", store.getString(field[2], ""));
            screen.dismissChildPages();
        }
    }

    @Test
    public void filterMasterCanBeDisabledAndReenabledWithoutUnlockingUnavailableItems() {
        View page = openCategory("FILTER");
        Switch master = toggle(page, "过滤总开关");
        Switch ads = toggle(page, "去广告开关");
        View keyword = option(page, "屏蔽关键词");
        assertFalse(master.isChecked());
        assertTrue(master.isEnabled());
        assertFalse(ads.isEnabled());
        assertFalse(keyword.isEnabled());

        master.setChecked(true);
        assertTrue(store.load().feedFilterEnabled);
        assertTrue(ads.isEnabled());
        assertTrue(keyword.isEnabled());
        ads.setChecked(true);
        assertUnavailableFiltersRemainDisabled(page);

        master.setChecked(false);
        assertFalse(store.load().feedFilterEnabled);
        assertTrue(master.isEnabled());
        assertFalse(ads.isEnabled());
        assertFalse(keyword.isEnabled());
        assertTrue(ads.isChecked());
        master.setChecked(true);
        assertTrue(master.isEnabled());
        assertTrue(ads.isEnabled());
        assertTrue(keyword.isEnabled());
        assertTrue(store.load().hideAds);
        assertUnavailableFiltersRemainDisabled(page);
    }

    @Test
    public void recommendationCardsAndMerchantsSaveIndependentlyAcrossOtherEdits() {
        View page = openCategory("FILTER");
        toggle(page, "过滤总开关").setChecked(true);
        Switch cards = toggle(page, "去推荐卡片开关");
        Switch merchants = toggle(page, "去推荐商家开关");
        cards.setChecked(true);
        assertTrue(store.load().hideRecommendationCards);
        assertFalse(store.load().hideRecommendationMerchants);

        merchants.setChecked(true);
        cards.setChecked(false);
        assertFalse(store.load().hideRecommendationCards);
        assertTrue(store.load().hideRecommendationMerchants);
        toggle(page, "去图集开关").setChecked(true);
        editOption(page, "屏蔽关键词", "广告,测试");
        screen.dismissChildPages();
        View download = openCategory("DOWNLOAD");
        toggle(download, "启用下载菜单").setChecked(true);
        toggle(download, "无水印下载图集或者视频").setChecked(true);
        screen.dismissChildPages();

        assertFalse(store.load().hideRecommendationCards);
        assertTrue(store.load().hideRecommendationMerchants);
        assertTrue(store.load().hideImagePosts);
        assertEquals("广告,测试", store.load().keyword);
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.load().removeWatermark);
        View reopened = openCategory("FILTER");
        assertFalse(toggle(reopened, "去推荐卡片开关").isChecked());
        assertTrue(toggle(reopened, "去推荐商家开关").isChecked());
    }

    @Test
    public void productAndHotspotCardFiltersPersistIndependently() {
        View page = openCategory("FILTER");
        toggle(page, "过滤总开关").setChecked(true);
        Switch product = toggle(page, "去商品/购物卡片开关");
        Switch hotspot = toggle(page, "去热点榜单卡开关");
        product.setChecked(true);
        assertTrue(store.getBoolean("hide_product_cards", false));
        assertFalse(store.getBoolean("hide_hotspot_rank_cards", false));

        hotspot.setChecked(true);
        product.setChecked(false);
        screen.dismissChildPages();
        View reopened = openCategory("FILTER");
        assertFalse(toggle(reopened, "去商品/购物卡片开关").isChecked());
        assertTrue(toggle(reopened, "去热点榜单卡开关").isChecked());
        toggle(reopened, "过滤总开关").setChecked(false);
        assertTrue(store.getBoolean("hide_hotspot_rank_cards", false));
        toggle(reopened, "过滤总开关").setChecked(true);
        assertTrue(toggle(reopened, "去热点榜单卡开关").isChecked());
    }

    @Test
    public void playbackSpeedUsesSevenChoicesAndNormalizesTheLegacySelectionWithoutWritingOnRender() {
        store.putString("playback_speed", "2.75");
        View page = openCategory("AUTOPLAY");
        assertEquals("3x", ((TextView) page.findViewWithTag("settings.value.倍速数值")).getText().toString());
        assertEquals("2.75", store.getString("playback_speed", ""));

        option(page, "倍速数值").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(collect(editor.getWindow().getDecorView(), EditText.class).isEmpty());
        ListView list = editor.getListView();
        assertEquals(7, list.getCount());
        assertEquals(Arrays.asList("0.75x", "1x", "1.25x", "1.5x", "2x", "3x", "4x"),
                java.util.stream.IntStream.range(0, list.getCount()).mapToObj(index -> list.getItemAtPosition(index).toString())
                        .collect(java.util.stream.Collectors.toList()));
        assertTrue(list.isItemChecked(5));
        assertFalse(list.isItemChecked(0));
        assertEquals("2.75", store.getString("playback_speed", ""));
        list.performItemClick(null, 6, list.getAdapter().getItemId(6));
        assertFalse(editor.isShowing());
        assertEquals("4", store.getString("playback_speed", ""));
        assertEquals("4x", ((TextView) page.findViewWithTag("settings.value.倍速数值")).getText().toString());
    }

    @Test public void playbackSpeedDefaultsToTwoAndInvalidLegacyTextDoesNotSelectTheFirstChoice() {
        View page = openCategory("AUTOPLAY");
        assertEquals("2x", ((TextView) page.findViewWithTag("settings.value.倍速数值")).getText().toString());
        assertFalse(toggle(page, "仅模块控制倍速").isChecked());
        assertTrue(store.values.isEmpty());
        screen.dismissChildPages();
        store.putString("playback_speed", "not-a-speed");
        page = openCategory("AUTOPLAY");
        option(page, "倍速数值").performClick();
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(choices.getListView().isItemChecked(4));
        assertFalse(choices.getListView().isItemChecked(0));
        choices.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertEquals("not-a-speed", store.getString("playback_speed", ""));
    }

    @Test public void moduleOnlySpeedKeepsRawAutomaticAndLongPressChoicesWhileShowingEffectiveControls() {
        store.putBoolean("auto_speed", true);
        store.putBoolean("block_long_press_speed", false);
        store.putBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, true);
        View playback = openCategory("AUTOPLAY");
        Switch mode = toggle(playback, "仅模块控制倍速");
        assertFalse(mode.isChecked());
        assertTrue(toggle(playback, "自动倍速播放").isEnabled());
        mode.setChecked(true);
        assertTrue(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, false));
        Switch automatic = toggle(playback, "自动倍速播放");
        assertTrue(automatic.isChecked());
        assertFalse(automatic.isEnabled());
        assertTrue(containsText(playback, "手动切换优先"));
        assertTrue(store.getBoolean("auto_speed", false));
        screen.dismissChildPages();

        View gestures = openCategory("INTERFACE");
        for (String title : new String[]{"拦截长按倍数启用场景", "拦截系统长按手势"}) {
            assertTrue(toggle(gestures, title).isChecked());
            assertFalse(toggle(gestures, title).isEnabled());
        }
        assertTrue(containsText(gestures, "由仅模块控制倍速启用"));
        assertFalse(store.getBoolean("block_long_press_speed", true));
        assertTrue(store.getBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false));
        screen.dismissChildPages();

        playback = openCategory("AUTOPLAY");
        toggle(playback, "仅模块控制倍速").setChecked(false);
        assertFalse(store.getBoolean(LocalOptions.MODULE_ONLY_SPEED, true));
        assertTrue(toggle(playback, "自动倍速播放").isChecked());
        assertTrue(toggle(playback, "自动倍速播放").isEnabled());
        screen.dismissChildPages();
        gestures = openCategory("INTERFACE");
        assertFalse(toggle(gestures, "拦截长按倍数启用场景").isChecked());
        assertTrue(toggle(gestures, "拦截长按倍数启用场景").isEnabled());
        assertTrue(toggle(gestures, "拦截系统长按手势").isChecked());
        assertTrue(toggle(gestures, "拦截系统长按手势").isEnabled());
    }

    @Test
    public void thresholdAndKeywordRowsActivateOnlyForConfiguredValues() {
        View page = openCategory("FILTER");
        toggle(page, "过滤总开关").setChecked(true);
        editOption(page, "最低点赞数（0不限制）", "120");
        assertTrue(store.load().minimumLikesEnabled);
        assertEquals(120, store.load().minimumLikeCount);
        editOption(page, "屏蔽关键词", "  美食,广告  ");
        assertTrue(store.load().keywordFilterEnabled);
        assertEquals("美食,广告", store.load().keyword);
        assertEquals(120, store.load().minimumLikeCount);
        editOption(page, "最低点赞数（0不限制）", "0");
        assertFalse(store.load().minimumLikesEnabled);
        assertTrue(store.load().keywordFilterEnabled);
        editOption(page, "屏蔽关键词", "  ");
        assertFalse(store.load().keywordFilterEnabled);
        assertEquals("", store.load().keyword);
        assertTrue(store.load().feedFilterEnabled);
        assertFalse(store.load().minimumDurationEnabled);
    }

    @Test
    public void nativeReplyRemainsOffByDefaultAndDoesNotMigrateDraftEnablement() {
        View page = openCategory("AUTO_REPLY");
        Switch master = toggle(page, "开启自动回复");
        View preset = option(page, "回复内容");
        assertFalse(master.isChecked());
        assertFalse(store.load().automationEnabled);
        assertFalse(store.load().autoMessageEnabled);
        assertTrue(preset.isEnabled());
        assertFalse(store.getBoolean("native_auto_reply", false));

        master.setChecked(true);
        assertTrue(preset.isEnabled());
        assertFalse(store.load().autoMessageEnabled);
        assertTrue(store.getBoolean("native_auto_reply", false));
        assertTrue(store.load().automationEnabled);
        preset.performClick();
        Dialog replyContents = ShadowDialog.getLatestDialog();
        editOption(replyContents.getWindow().getDecorView(), "固定回复内容", "已收到，稍后回复");
        replyContents.dismiss();
        assertEquals("已收到，稍后回复", store.getString("auto_message_text", ""));
        assertTrue(option(page, "私聊范围").isEnabled());
        assertTrue(toggle(page, "接收私聊").isChecked());
        assertFalse(toggle(page, "接收群聊").isChecked());
        assertTrue(option(page, "群聊范围").isEnabled());
        assertTrue(option(page, "发送与记录").isEnabled());
        assertTrue(option(page, "回复模式").isEnabled());
        assertTrue(option(page, "回复模式").isEnabled());

        master.setChecked(false);
        assertTrue(master.isEnabled());
        assertTrue(preset.isEnabled());
        assertFalse(store.getBoolean("native_auto_reply", true));
        assertFalse(store.load().autoMessageEnabled);
        master.setChecked(true);
        assertTrue(preset.isEnabled());
        assertEquals("已收到，稍后回复", store.getString("auto_message_text", ""));
        assertFalse(store.load().autoCommentEnabled);
    }

    @Test public void automationScopeModeRelationsFieldsAndKeywordsPersistIndependently() {
        View reply = openCategory("AUTO_REPLY");
        assertEquals("", store.getString("reply_scope_mode", ""));
        assertEquals("", store.getString("reply_scope_relations", ""));
        assertFalse(store.getBoolean("reply_group_chats", false));
        assertTrue(store.getBoolean("reply_private_chats", true));
        store.putString("reply_scope_mode", "whitelist");
        store.putString("reply_scope_keywords", "旧关键词");
        store.putString("reply_scope_mode", "blacklist");
        store.putString("reply_scope_relations", "following,strangers");
        store.putString("reply_scope_fields", "author,title");
        option(reply, "私聊范围").performClick();
        View scope = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertEquals("exclude", store.getString("reply_filter_mode", ""));
        assertEquals("author,caption", store.getString("reply_filter_fields", ""));
        editOption(scope, "筛选关键词", "发送者词, 消息词");
        assertEquals("旧关键词", store.getString("reply_scope_keywords", ""));
        assertEquals("发送者词, 消息词", store.getString("reply_filter_keywords", ""));
        screen.dismissChildPages();
        View comment = openCategory("AUTO_COMMENT");
        assertEquals("all", store.getString("comment_scope_mode", "all"));
        assertEquals("", store.getString("comment_scope_users", ""));
        store.putString("comment_scope_mode", "whitelist");
        store.putString("comment_scope_relations", "followers");
        store.putString("comment_scope_fields", "caption");
        store.putString("comment_scope_keywords", "茶,咖啡");
        assertEquals("blacklist", store.getString("reply_scope_mode", ""));
        assertEquals("following,strangers", store.getString("reply_scope_relations", ""));
        assertEquals("author,title", store.getString("reply_scope_fields", ""));
        assertEquals("茶,咖啡", store.getString("comment_scope_keywords", ""));
        option(comment, "素材识别").performClick();
        assertTrue(toggle(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "识别图片").isChecked());
    }

    @Test public void groupSettingsPersistConversationScopeAndReplyModeIndependentlyOfPrivateScope() {
        store.putString("reply_scope_mode", "blacklist");
        store.putString("reply_targets", "123");
        store.putString("reply_scope_relations", "following");
        store.putString("reply_scope_keywords", "茶");
        View reply = openCategory("AUTO_REPLY");
        toggle(reply, "接收群聊").setChecked(true);
        option(reply, "群聊范围").performClick();
        Dialog groups = ShadowDialog.getLatestDialog();
        View page = groups.getWindow().getDecorView();
        assertEquals(Arrays.asList("群聊作用域", "触发条件"), settingTitles(page));

        option(page, "群聊作用域").performClick();
        AlertDialog scope = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(scope.getListView().isItemChecked(0));
        scope.getListView().performItemClick(null, 1, 1);
        assertEquals("whitelist", store.getString("reply_group_scope_mode", ""));
        assertNotNull(page.findViewWithTag("settings.option.选择群聊"));

        option(page, "触发条件").performClick();
        AlertDialog mode = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(mode.getListView().isItemChecked(0));
        mode.getListView().performItemClick(null, 1, 1);
        assertEquals("selected", store.getString("reply_group_mode", ""));
        option(page, "指定成员").performClick();
        AlertDialog members = (AlertDialog) ShadowDialog.getLatestDialog();
        members.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        AlertDialog memberIds = (AlertDialog) ShadowDialog.getLatestDialog();
        setEditorInput(memberIds, "成员 UID 或昵称", "222, 333,222");
        memberIds.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals("222,333", store.getString("reply_group_members", ""));

        option(page, "触发条件").performClick();
        mode = (AlertDialog) ShadowDialog.getLatestDialog();
        mode.getListView().performItemClick(null, 2, 2);
        assertEquals("mentions", store.getString("reply_group_mode", ""));
        assertNull(page.findViewWithTag("settings.option.指定成员"));
        assertEquals("blacklist", store.getString("reply_scope_mode", ""));
        assertEquals("123", store.getString("reply_targets", ""));
        assertEquals("following", store.getString("reply_scope_relations", ""));
        assertEquals("茶", store.getString("reply_scope_keywords", ""));
        assertEquals("222,333", store.getString("reply_group_members", ""));
        assertTrue(groups.isShowing());
    }

    @Test public void followingListSearchMatchesNicknameAndUidAndRetainsCompleteCount() throws Exception {
        installFollowNames();
        View reply = openCategory("AUTO_REPLY");
        option(reply, "私聊范围").performClick();
        reply = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        option(reply, "关注与粉丝名单").performClick();
        Dialog lists = ShadowDialog.getLatestDialog();
        View following = lists.getWindow().getDecorView().findViewWithTag("settings.followLists.following");
        assertNotNull(following);
        following.performClick();
        Dialog members = ShadowDialog.getLatestDialog();
        View page = members.getWindow().getDecorView();
        EditText search = page.findViewWithTag("settings.followLists.search");
        ListView list = page.findViewWithTag("settings.followLists.members");
        TextView count = page.findViewWithTag("settings.followLists.resultCount");
        assertNotNull(search);
        assertEquals(2, list.getAdapter().getCount());
        assertEquals("2 / 2 项", count.getText().toString());
        search.setText("ALICE");
        assertEquals(1, list.getAdapter().getCount());
        assertEquals("101", ((com.douai.module.feature.ProfileListEntry) list.getAdapter().getItem(0)).uid);
        assertEquals("Alice 茶", ((com.douai.module.feature.ProfileListEntry) list.getAdapter().getItem(0)).name);
        assertEquals("1 / 2 项", count.getText().toString());
        search.setText("202");
        assertEquals("202", ((com.douai.module.feature.ProfileListEntry) list.getAdapter().getItem(0)).uid);
        search.setText("无匹配昵称");
        assertEquals(0, list.getAdapter().getCount());
        assertEquals("无匹配结果 · 共 2 项", count.getText().toString());
        search.setText("");
        assertEquals(2, list.getAdapter().getCount());
        assertEquals(View.VISIBLE, list.getVisibility());
        assertTrue(lists.isShowing());
    }

    @Test public void groupSelectionCanRemoveSavedConversationWithoutCurrentGroupName() {
        store.putString("reply_group_targets", "group101,group202");
        store.putString("reply_targets", "202");
        store.putString("reply_group_scope_mode", "whitelist");
        store.putBoolean("reply_group_chats", true);
        option(openCategory("AUTO_REPLY"), "群聊范围").performClick();
        Dialog settings = ShadowDialog.getLatestDialog();
        View page = settings.getWindow().getDecorView();
        option(page, "选择群聊").performClick();
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = choices.getWindow().getDecorView().findViewWithTag("settings.followLists.members");
        int remove = -1;
        for (int index = 0; index < list.getAdapter().getCount(); index++) {
            if (((com.douai.module.feature.ProfileListEntry) list.getAdapter().getItem(index)).uid.equals("group101")) remove = index;
        }
        assertTrue(remove >= 0);
        View row = list.getAdapter().getView(remove, null, list);
        assertTrue(row.performClick());
        assertFalse(((MemberListView) list.getParent()).selected().contains("group101"));
        choices.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals("group202", store.getString("reply_group_targets", ""));
        assertEquals("202", store.getString("reply_targets", ""));
        assertTrue(settings.isShowing());
    }

    @SuppressWarnings("unchecked")
    private void installFollowNames() throws Exception {
        Class<?> accountClass = Class.forName(NativeFollowLists.class.getName() + "$Account");
        java.lang.reflect.Constructor<?> accountConstructor = accountClass.getDeclaredConstructor(String.class, String.class);
        accountConstructor.setAccessible(true);
        Object account = accountConstructor.newInstance("42", "sec42");
        Class<?> sourceClass = Class.forName(NativeFollowLists.class.getName() + "$AccountSource");
        Object source = java.lang.reflect.Proxy.newProxyInstance(sourceClass.getClassLoader(), new Class<?>[]{sourceClass},
                (proxy, method, arguments) -> account);
        java.lang.reflect.Constructor<NativeFollowLists> constructor = NativeFollowLists.class.getDeclaredConstructor(
                sourceClass, FollowListPager.Fetcher.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        followLists = constructor.newInstance(source, (FollowListPager.Fetcher) (kind, query) -> null,
                (java.util.function.Consumer<String>) ignored -> { });
        java.lang.reflect.Field active = NativeFollowLists.class.getDeclaredField("active");
        active.setAccessible(true);
        active.set(null, followLists);
        FollowListSnapshot.activateAccount("42");
        FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("42", FollowListSnapshot.Kind.FOLLOWING),
                Arrays.asList("101", "202"));
        java.lang.reflect.Field names = NativeFollowLists.class.getDeclaredField("names");
        names.setAccessible(true);
        Map<FollowListSnapshot.Kind, Map<String, String>> allNames =
                (Map<FollowListSnapshot.Kind, Map<String, String>>) names.get(followLists);
        Map<String, String> following = new java.util.LinkedHashMap<>();
        following.put("101", "Alice 茶");
        following.put("202", "Bob");
        allNames.put(FollowListSnapshot.Kind.FOLLOWING, following);
    }

    @Test
    public void nativeLongPressInterceptionIsOffByDefaultAndPersists() {
        View page = openCategory("INTERFACE");
        Switch interception = toggle(page, "拦截系统长按手势");
        assertFalse(interception.isChecked());
        assertFalse(store.getBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false));

        interception.setChecked(true);
        assertTrue(store.getBoolean(LocalOptions.INTERCEPT_NATIVE_LONG_PRESS, false));
        screen.dismissChildPages();

        View reopened = openCategory("INTERFACE");
        assertTrue(toggle(reopened, "拦截系统长按手势").isChecked());
    }

    @Test
    public void newlyAdaptedCategoriesStayOffUntilExplicitlyEnabled() {
        for (String category : Arrays.asList("BASIC", "STREAK", "MESSAGE", "SPOOF")) {
            View page = openCategory(category);
            for (Switch control : collect(page, Switch.class)) {
                assertTrue(category + " " + control.getContentDescription(), control.isEnabled());
                if (!"自己撤回的消息".contentEquals(control.getContentDescription())
                        && !"对方撤回的消息".contentEquals(control.getContentDescription())) {
                    assertFalse(control.isChecked());
                }
            }
            for (View row : optionRows(page)) assertTrue(category + " " + row.getTag(), row.isEnabled());
            screen.dismissChildPages();
        }
        assertTrue(store.values.isEmpty());
        assertFalse(store.load().automationEnabled);
    }

    @Test
    public void unknownStreakRetryRequiresExplicitConfirmation() {
        View page = openCategory("STREAK");
        option(page, "重试结果未知的续火花").performClick();
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(containsText(confirmation.getWindow().getDecorView(), "可能已经到达对方"));
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        assertNull(store.getString("streak_retry_unknown_token", null));

        option(page, "重试结果未知的续火花").performClick();
        confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(store.getString("streak_retry_unknown_token", "").isEmpty());
    }

    @Test
    public void extensionOpensVersionedAiProfilesAndDismissesItsChild() {
        View extension = openCategory("EXTENSION");
        Dialog category = ShadowDialog.getLatestDialog();
        store.putString("ai_models", "[{\"id\":\"retired\",\"model\":\"old-model\"}]");
        store.putString("ai_model_id", "retired");
        option(extension, "AI 模型设置").performClick();
        Dialog ai = ShadowDialog.getLatestDialog();
        assertTrue(ai.isShowing());
        View page = ai.getWindow().getDecorView();
        assertEquals(Arrays.asList("添加模型配置", "测试当前配置"),
                settingTitles(page));
        assertNotNull(page.findViewWithTag("settings.option.添加模型配置"));
        assertNotNull(page.findViewWithTag("settings.option.测试当前配置"));
        assertTrue(containsText(page, "尚未配置"));
        assertFalse(containsText(page, "old-model"));
        for (View row : optionRows(page)) assertTrue(row.isEnabled());
        category.dismiss();
        ShadowLooper.idleMainLooper();
        assertFalse(ai.isShowing());
    }

    @Test
    public void aiProfilePresetCanBeSavedAndDeletedWithoutChoosingADefaultOrReadingLegacySettings() {
        store.putString("ai_models", "[{\"id\":\"legacy\",\"model\":\"old-model\"}]");
        store.putString("ai_model_id", "legacy");
        View extension = openCategory("EXTENSION");
        option(extension, "AI 模型设置").performClick();
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        option(manager, "添加模型配置").performClick();
        AlertDialog presets = (AlertDialog) ShadowDialog.getLatestDialog();
        presets.getListView().performItemClick(null, 6, 6);
        ShadowLooper.idleMainLooper();

        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(editor.isShowing());
        View editorRoot = editor.getWindow().getDecorView();
        Spinner provider = editorRoot.findViewWithTag("ai.provider");
        Spinner responseMode = editorRoot.findViewWithTag("ai.response_mode");
        Spinner reasoningEffort = editorRoot.findViewWithTag("ai.reasoning_effort");
        assertNotNull(provider);
        assertNotNull(responseMode);
        assertNotNull(reasoningEffort);
        assertEquals("通义千问", provider.getSelectedItem());
        assertEquals("非流式", responseMode.getSelectedItem());
        assertEquals("模型默认", reasoningEffort.getSelectedItem());
        setEditorInput(editor, "配置名称", "测试模型");
        setEditorInput(editor, "模型名称", "qwen-turbo");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        AiModelSettings configured = AiModelSettings.read(store);
        assertEquals(1, configured.configs.size());
        assertEquals("", configured.activeId);
        assertEquals("qwen", configured.configs.get(0).provider);
        assertEquals("chat_completions", configured.configs.get(0).protocol);
        assertEquals("qwen-turbo", configured.configs.get(0).modelName);
        assertFalse(containsText(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "old-model"));

        View row = ShadowDialog.getLatestDialog().getWindow().getDecorView()
                .findViewWithTag("ai.edit." + configured.configs.get(0).id);
        assertNotNull(row);
        row.performClick();
        AlertDialog savedEditor = (AlertDialog) ShadowDialog.getLatestDialog();
        savedEditor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        assertTrue(AiModelSettings.read(store).configs.isEmpty());
        assertEquals("", AiModelSettings.read(store).activeId);
    }

    @Test
    public void currentProfileTestOpensConversationAndClosingCategoryClosesIt() throws Exception {
        AiModelConfig config = testAiProfile("active", "当前模型", "saved-model");
        new AiModelSettings(Arrays.asList(config), config.id).save(store);
        Map<String, Object> saved = new HashMap<>(store.values);
        View extension = openCategory("EXTENSION");
        Dialog category = ShadowDialog.getLatestDialog();
        option(extension, "AI 模型设置").performClick();
        Dialog manager = ShadowDialog.getLatestDialog();
        option(manager.getWindow().getDecorView(), "测试当前配置").performClick();

        Dialog chat = ShadowDialog.getLatestDialog();
        assertTrue(chat instanceof AiTestChatDialog);
        assertTrue(chat.isShowing());
        View page = chat.getWindow().getDecorView();
        assertEquals("当前模型 · saved-model",
                ((TextView) page.findViewWithTag("ai.chat.model")).getText().toString());
        assertNotNull(page.findViewWithTag("ai.chat.input"));
        assertEquals(saved, store.values);

        category.dismiss();
        ShadowLooper.idleMainLooper();
        assertFalse(manager.isShowing());
        assertFalse(chat.isShowing());
    }

    @Test
    public void editorTestUsesUnsavedDraftWithoutSavingOrActivatingIt() throws Exception {
        AiModelConfig active = testAiProfile("active", "当前模型", "active-model");
        AiModelConfig editing = testAiProfile("editing", "另一个模型", "saved-model");
        new AiModelSettings(Arrays.asList(active, editing), active.id).save(store);
        Map<String, Object> saved = new HashMap<>(store.values);
        View extension = openCategory("EXTENSION");
        option(extension, "AI 模型设置").performClick();
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        manager.findViewWithTag("ai.edit." + editing.id).performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        setEditorInput(editor, "配置名称", "未保存的名称");
        setEditorInput(editor, "模型名称", "unsaved-model");
        View test = editor.getWindow().getDecorView().findViewWithTag("ai.test_chat");
        assertNotNull(test);
        assertTrue(test.performClick());

        Dialog chat = ShadowDialog.getLatestDialog();
        assertTrue(chat instanceof AiTestChatDialog);
        assertEquals("未保存的名称 · unsaved-model", ((TextView) chat.getWindow()
                .getDecorView().findViewWithTag("ai.chat.model")).getText().toString());
        assertEquals(saved, store.values);
        assertEquals(active.id, AiModelSettings.read(store).activeId);
        assertEquals("saved-model", AiModelSettings.read(store).configs.get(1).modelName);
        chat.dismiss();
        ShadowLooper.idleMainLooper();
        assertTrue(editor.isShowing());
        assertEquals(saved, store.values);

        screen.dismissChildPages();
        assertFalse(editor.isShowing());
    }

    @Test public void closingModelEditorCancelsItsCatalogAndLetsNextEditorFetch() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"data\":[{\"id\":\"next-model\"}]}"));
            server.start();
            AiModelConfig config = AiModelConfig.fromJson(testAiProfile("catalog", "模型列表", "saved-model")
                    .toJson().put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("timeoutSeconds", 30));
            new AiModelSettings(Arrays.asList(config), config.id).save(store);
            option(openCategory("EXTENSION"), "AI 模型设置").performClick();
            View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            manager.findViewWithTag("ai.edit.catalog").performClick();
            Dialog oldEditor = ShadowDialog.getLatestDialog();
            View oldRoot = oldEditor.getWindow().getDecorView();
            assertTrue(oldRoot.findViewWithTag("ai.fetch_models").performClick());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            oldEditor.dismiss();
            ShadowLooper.idleMainLooper();

            manager.findViewWithTag("ai.edit.catalog").performClick();
            Dialog nextEditor = ShadowDialog.getLatestDialog();
            View nextRoot = nextEditor.getWindow().getDecorView();
            assertTrue(nextRoot.findViewWithTag("ai.fetch_models").performClick());
            assertNotNull("The cancelled catalog must release the shared worker",
                    server.takeRequest(2, TimeUnit.SECONDS));
            TextView nextStatus = nextRoot.findViewWithTag("ai.status");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (!nextStatus.getText().toString().startsWith("已获取") && System.nanoTime() < deadline) {
                ShadowLooper.idleMainLooper();
                LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
            }
            ShadowLooper.idleMainLooper();
            assertEquals("已获取 1 个模型", nextStatus.getText().toString());
            assertEquals("正在获取模型列表…", ((TextView) oldRoot.findViewWithTag("ai.status")).getText().toString());
            assertFalse(oldEditor.isShowing());
            assertTrue(nextEditor.isShowing());
            assertTrue(containsText(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "next-model"));
            assertEquals("saved-model", AiModelSettings.read(store).selected().modelName);
        }
    }

    @Test public void closingSettingsClosesEditorWithActiveCatalog() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            AiModelConfig config = AiModelConfig.fromJson(testAiProfile("catalog-close", "模型列表", "saved-model")
                    .toJson().put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("timeoutSeconds", 30));
            new AiModelSettings(Arrays.asList(config), config.id).save(store);
            option(openCategory("EXTENSION"), "AI 模型设置").performClick();
            ShadowDialog.getLatestDialog().getWindow().getDecorView()
                    .findViewWithTag("ai.edit.catalog-close").performClick();
            Dialog editor = ShadowDialog.getLatestDialog();
            assertTrue(editor.getWindow().getDecorView().findViewWithTag("ai.fetch_models").performClick());
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            screen.close();
            ShadowLooper.idleMainLooper();
            assertFalse(editor.isShowing());
        }
    }

    private static AiModelConfig testAiProfile(String id, String name, String model) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", name)
                .put("apiEndpoint", "https://example.com/v1").put("modelName", model));
    }

    @Test public void primaryPromptOpensLargeEditorAndPreservesFullDraftUntilModelSave() throws Exception {
        AiModelConfig original = AiModelConfig.fromJson(testAiProfile("memory", "记忆模型", "model").toJson()
                .put("systemPrompt", "原记忆"));
        new AiModelSettings(Arrays.asList(original), original.id).save(store);
        option(openCategory("EXTENSION"), "AI 模型设置").performClick();
        ShadowDialog.getLatestDialog().getWindow().getDecorView().findViewWithTag("ai.edit.memory").performClick();
        AlertDialog model = (AlertDialog) ShadowDialog.getLatestDialog();
        model.getWindow().getDecorView().findViewWithTag("ai.prompt.primary").performClick();
        PromptEditorDialog prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        String memory = " 记忆属性\n".repeat(50000);
        setEditorInput(prompt, "一级提示词", memory);
        prompt.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue(model.isShowing());
        assertEquals("原记忆", AiModelSettings.read(store).selected().systemPrompt);
        model.getWindow().getDecorView().findViewWithTag("ai.prompt.primary").performClick();
        prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        EditText input = prompt.getWindow().getDecorView().findViewWithTag("settings.input.一级提示词");
        assertEquals(memory, input.getText().toString());
        input.setText("不保存的草稿");
        prompt.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        model.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(memory, AiModelSettings.read(store).selected().systemPrompt);
    }

    @Test public void primaryPromptDraftReachesTestRequestsAcrossTurnsClearReopenAndModelSave() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String[] answers = {"第一轮回复", "第二轮回复", "清空后回复", "重新打开后回复", "保存模型后回复"};
            for (String answer : answers) {
                server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                        .setBody(new JSONObject().put("choices", new JSONArray().put(new JSONObject()
                                .put("message", new JSONObject().put("role", "assistant")
                                        .put("content", answer)))).toString()));
            }
            server.start();
            AiModelConfig original = AiModelConfig.fromJson(testAiProfile("memory-http", "偏好模型", "test-model")
                    .toJson().put("apiEndpoint", server.url("/v1").toString())
                    .put("allowInsecureHttp", true).put("contextRounds", 2)
                    .put("timeoutSeconds", 5).put("systemPrompt", "原来的偏好"));
            new AiModelSettings(Arrays.asList(original), original.id).save(store);
            option(openCategory("EXTENSION"), "AI 模型设置").performClick();
            ShadowDialog.getLatestDialog().getWindow().getDecorView()
                    .findViewWithTag("ai.edit.memory-http").performClick();
            AlertDialog model = (AlertDialog) ShadowDialog.getLatestDialog();
            model.getWindow().getDecorView().findViewWithTag("ai.prompt.primary").performClick();
            PromptEditorDialog prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
            String memory = " 固定偏好：用户喜欢乌龙茶，回复使用简体中文。\n保留该偏好作为每轮背景。 \n";
            setEditorInput(prompt, "一级提示词", memory);
            prompt.getButton(Dialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals("原来的偏好", AiModelSettings.read(store).selected().systemPrompt);

            View test = model.getWindow().getDecorView().findViewWithTag("ai.test_chat");
            assertTrue(test.performClick());
            AiTestChatDialog chat = (AiTestChatDialog) ShadowDialog.getLatestDialog();
            JSONArray first = sendTestMessage(chat, server, "我平时喜欢喝什么茶？", answers[0]);
            assertEquals(2, first.length());
            assertPrimaryMemory(first, memory);
            assertEquals("我平时喜欢喝什么茶？", first.getJSONObject(1).getString("content"));

            JSONArray second = sendTestMessage(chat, server, "你刚才提到的偏好是什么？", answers[1]);
            assertEquals(4, second.length());
            assertPrimaryMemory(second, memory);
            assertEquals("我平时喜欢喝什么茶？", second.getJSONObject(1).getString("content"));
            assertEquals("assistant", second.getJSONObject(2).getString("role"));
            assertEquals(answers[0], second.getJSONObject(2).getString("content"));
            assertEquals("你刚才提到的偏好是什么？", second.getJSONObject(3).getString("content"));

            assertTrue(chat.getWindow().getDecorView().findViewWithTag("ai.chat.clear").performClick());
            JSONArray cleared = sendTestMessage(chat, server, "清空后偏好还在吗？", answers[2]);
            assertEquals(2, cleared.length());
            assertPrimaryMemory(cleared, memory);
            chat.dismiss();
            ShadowLooper.idleMainLooper();
            assertTrue(test.performClick());
            chat = (AiTestChatDialog) ShadowDialog.getLatestDialog();
            JSONArray reopened = sendTestMessage(chat, server, "重新打开后偏好还在吗？", answers[3]);
            assertEquals(4, reopened.length());
            assertPrimaryMemory(reopened, memory);
            assertEquals("清空后偏好还在吗？", reopened.getJSONObject(1).getString("content"));
            assertEquals(answers[2], reopened.getJSONObject(2).getString("content"));
            assertEquals("原来的偏好", AiModelSettings.read(store).selected().systemPrompt);

            chat.dismiss();
            ShadowLooper.idleMainLooper();
            model.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertEquals(memory, AiModelSettings.read(store).selected().systemPrompt);
            option(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "测试当前配置").performClick();
            chat = (AiTestChatDialog) ShadowDialog.getLatestDialog();
            JSONArray saved = sendTestMessage(chat, server, "已保存配置中的偏好是什么？", answers[4]);
            assertEquals(6, saved.length());
            assertPrimaryMemory(saved, memory);
            chat.dismiss();
        }
    }

    private static JSONArray sendTestMessage(AiTestChatDialog chat, MockWebServer server,
                                              String question, String expectedAnswer) throws Exception {
        View root = chat.getWindow().getDecorView();
        EditText input = root.findViewWithTag("ai.chat.input");
        input.setText(question);
        assertTrue(root.findViewWithTag("ai.chat.send").performClick());
        RecordedRequest request = server.takeRequest(10, TimeUnit.SECONDS);
        assertNotNull("Test chat must issue an actual HTTP request", request);
        assertEquals("/v1/chat/completions", request.getPath());
        JSONObject body = new JSONObject(request.getBody().readUtf8());
        assertEquals("test-model", body.getString("model"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!containsText(root, expectedAnswer) && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        }
        ShadowLooper.idleMainLooper();
        assertTrue("Test chat must render the completed reply", containsText(root, expectedAnswer));
        return body.getJSONArray("messages");
    }

    private static void assertPrimaryMemory(JSONArray messages, String memory) throws Exception {
        assertEquals("system", messages.getJSONObject(0).getString("role"));
        assertEquals(memory, messages.getJSONObject(0).getString("content"));
        int systemMessages = 0;
        for (int index = 0; index < messages.length(); index++) {
            if ("system".equals(messages.getJSONObject(index).getString("role"))) systemMessages++;
        }
        assertEquals(1, systemMessages);
    }

    @Test public void cancellingPromptKeepsSettingsAndClosingCategoryClosesItsEditor() {
        store.putString("comment_prompt", "原提示词\n");
        View page = openCategory("AUTO_COMMENT");
        option(page, "评论内容").performClick();
        View contents = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        option(contents, "二级提示词").performClick();
        PromptEditorDialog prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        setEditorInput(prompt, "二级提示词", "修改\n".repeat(6000));
        prompt.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        assertEquals("原提示词\n", store.getString("comment_prompt", ""));
        option(contents, "二级提示词").performClick();
        prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        screen.dismissChildPages();
        assertFalse(prompt.isShowing());
    }

    @Test
    public void automationPromptsAndHistoryOptionsAreIndependentAndInvalidTimeoutStaysOpen() {
        store.putString("reply_mode", "ai");
        View reply = openCategory("AUTO_REPLY");
        option(reply, "回复内容").performClick();
        Dialog contents = ShadowDialog.getLatestDialog();
        option(contents.getWindow().getDecorView(), "二级提示词").performClick();
        PromptEditorDialog prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        setEditorInput(prompt, "二级提示词", "回复提示词\n".repeat(4000));
        prompt.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("回复提示词\n".repeat(4000), store.getString("reply_prompt", ""));
        contents.dismiss();
        ShadowLooper.idleMainLooper();
        option(reply, "发送与记录").performClick();
        View delivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        toggle(delivery, "保存历史记录").setChecked(true);
        assertNull(delivery.findViewWithTag("settings.option.提示超时（秒）"));
        toggle(delivery, "悬浮提示").setChecked(true);
        option(delivery, "提示超时（秒）").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("3", collect(editor.getWindow().getDecorView(), EditText.class).get(0).getText().toString());
        setEditorInput(editor, "提示超时（秒）", "999");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertTrue(editor.isShowing());
        assertFalse(store.values.containsKey("reply_notice_seconds"));
        editor.dismiss();
        screen.dismissChildPages();
        View comment = openCategory("AUTO_COMMENT");
        option(comment, "发送与记录").performClick();
        Dialog commentDelivery = ShadowDialog.getLatestDialog();
        View commentRecords = commentDelivery.getWindow().getDecorView();
        assertFalse(toggle(commentRecords, "保存历史记录").isChecked());
        assertFalse(toggle(commentRecords, "悬浮提示").isChecked());
        commentDelivery.dismiss();
        assertFalse(toggle(comment, "开启自动评论").isChecked());
        toggle(comment, "开启自动评论").setChecked(true);
        assertTrue(store.getBoolean("native_auto_comment", false));
        assertTrue(store.load().automationEnabled);
        option(comment, "评论内容").performClick();
        option(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "二级提示词").performClick();
        prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        setEditorInput(prompt, "二级提示词", "评论提示词");
        prompt.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("评论提示词", store.getString("comment_prompt", ""));
        assertEquals("回复提示词\n".repeat(4000), store.getString("reply_prompt", ""));
    }

    @Test public void noticeDurationEditorShowsThreeWithoutWritingAndPreservesSavedEightForBothKinds() {
        for (String[] feature : new String[][]{{"reply", "AUTO_REPLY"}, {"comment", "AUTO_COMMENT"}}) {
            String key = feature[0] + "_notice_seconds";
            store.putBoolean(feature[0] + "_tip", true);
            for (String saved : new String[]{null, "8"}) {
                if (saved == null) store.values.remove(key);
                else store.putString(key, saved);
                View category = openCategory(feature[1]);
                option(category, "发送与记录").performClick();
                View delivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
                TextView shown = delivery.findViewWithTag("settings.value.提示超时（秒）");
                assertEquals(saved == null ? "3" : "8", shown.getText().toString());
                option(delivery, "提示超时（秒）").performClick();
                Dialog editor = ShadowDialog.getLatestDialog();
                assertEquals(saved == null ? "3" : "8",
                        collect(editor.getWindow().getDecorView(), EditText.class).get(0).getText().toString());
                editor.dismiss();
                screen.dismissChildPages();
                if (saved == null) assertFalse(store.values.containsKey(key));
                else assertEquals("8", store.getString(key, ""));
            }
        }
    }

    @Test
    public void historyDetailsAreReadableAndClosingTheCategoryClosesTheirChildWindow() throws InterruptedException {
        com.douai.module.config.AutomationHistoryStore history = new com.douai.module.config.AutomationHistoryStore(activity);
        history.upsert(new com.douai.module.config.AutomationHistoryStore.Entry("ui-history", "reply", "target-id",
                "目标用户", "收到消息", "生成回复", "model", "sent", System.currentTimeMillis(), System.currentTimeMillis()));
        View reply = openCategory("AUTO_REPLY");
        option(reply, "发送与记录").performClick();
        option(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "回复历史记录").performClick();
        Dialog list = ShadowDialog.getLatestDialog();
        ListView historyRows = list.getWindow().getDecorView().findViewWithTag("automation.history");
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
        View row = null;
        while (row == null && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper();
            row = historyRows.getAdapter().getView(0, null, historyRows);
            if (!"automation.history.ui-history".equals(row.getTag())) {
                row = null;
                java.util.concurrent.locks.LockSupport.parkNanos(
                        java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(10));
            }
        }
        assertNotNull(row);
        row.performClick();
        Dialog detail = ShadowDialog.getLatestDialog();
        assertTrue("History detail window: " + detail.getClass().getName(),
                containsText(detail.getWindow().getDecorView(), "收到消息"));
        assertTrue(containsText(detail.getWindow().getDecorView(), "生成回复"));
        screen.dismissChildPages();
        assertFalse(list.isShowing());
        assertFalse(detail.isShowing());
    }

    @Test public void replyContentModeSwitchesKeepDraftsAndOnlyShowTheirOwnControls() {
        store.putString("auto_message_text", "固定草稿");
        store.putString("reply_prompt", "AI 提示词");
        store.putString("reply_random", "[\"随机草稿\"]");
        store.putString("reply_keywords", "[{\"keyword\":\"茶\",\"reply\":\"关键词草稿\"}]");
        View reply = openCategory("AUTO_REPLY");
        option(reply, "回复内容").performClick();
        Dialog contents = ShadowDialog.getLatestDialog();
        assertEquals(Arrays.asList("固定回复内容"), settingTitles(contents.getWindow().getDecorView()));
        contents.dismiss();
        option(reply, "回复模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        option(reply, "回复内容").performClick();
        contents = ShadowDialog.getLatestDialog();
        assertEquals(Arrays.asList("模型配置", "二级提示词"), settingTitles(contents.getWindow().getDecorView()));
        contents.dismiss();
        option(reply, "回复模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 3, 3);
        option(reply, "回复内容").performClick();
        assertTrue(containsText(ShadowDialog.getLatestDialog().getWindow().getDecorView(), "随机草稿"));
        ShadowDialog.getLatestDialog().dismiss();
        assertEquals("固定草稿", store.getString("auto_message_text", ""));
        assertEquals("AI 提示词", store.getString("reply_prompt", ""));
        assertEquals("[{\"keyword\":\"茶\",\"reply\":\"关键词草稿\"}]", store.getString("reply_keywords", ""));
    }

    @Test public void replyMediaRecognitionKeepsAiChoicesAcrossOtherModesWithoutChangingCommentRecognition() {
        store.putBoolean("comment_recognize_images", false);
        store.putBoolean("comment_recognize_video", true);
        View reply = openCategory("AUTO_REPLY");
        assertFalse(option(reply, "素材识别").isEnabled());
        assertEquals("未启用", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        option(reply, "回复模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        assertTrue(option(reply, "素材识别").isEnabled());
        assertEquals("图片", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        option(reply, "素材识别").performClick();
        Dialog recognition = ShadowDialog.getLatestDialog();
        View media = recognition.getWindow().getDecorView();
        assertEquals(Arrays.asList("识别图片", "识别视频"), settingTitles(media));
        assertTrue(toggle(media, "识别图片").isChecked());
        assertFalse(toggle(media, "识别视频").isChecked());
        assertFalse(containsText(media, "需模型支持图片输入"));
        assertFalse(containsText(media, "优先对方附图，无附图才读作品图片/封面"));
        assertFalse(containsText(media, "抽帧不含音频"));
        assertFalse(containsText(media, "私聊/群聊加密原视频暂不支持"));
        toggle(media, "识别图片").setChecked(false);
        assertEquals("不识别", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        toggle(media, "识别视频").setChecked(true);
        assertEquals("视频", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        recognition.dismiss();
        for (int mode : new int[]{0, 2, 3}) {
            option(reply, "回复模式").performClick();
            ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, mode, mode);
            assertFalse(option(reply, "素材识别").isEnabled());
            assertEquals("未启用", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
            assertFalse(store.getBoolean(LocalOptions.REPLY_RECOGNIZE_IMAGES, true));
            assertTrue(store.getBoolean(LocalOptions.REPLY_RECOGNIZE_VIDEO, false));
        }
        option(reply, "回复模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        assertTrue(option(reply, "素材识别").isEnabled());
        assertEquals("视频", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        option(reply, "素材识别").performClick();
        media = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertFalse(toggle(media, "识别图片").isChecked());
        assertTrue(toggle(media, "识别视频").isChecked());
        toggle(media, "识别图片").setChecked(true);
        assertEquals("图片、视频", ((TextView) reply.findViewWithTag("settings.value.素材识别")).getText().toString());
        assertFalse(store.getBoolean("comment_recognize_images", true));
        assertTrue(store.getBoolean("comment_recognize_video", false));
    }

    @Test public void interactionAutomationOffDoesNotMisreportTheAutoReplyMasterAsPaused() {
        store.putBoolean("native_auto_reply", true);
        View reply = openCategory("AUTO_REPLY");
        assertTrue(toggle(reply, "开启自动回复").isChecked());
        assertFalse(store.load().automationEnabled);
        assertFalse(containsText(reply, "已暂停：自动化总开关关闭"));
    }

    @Test public void commentReplyReceptionUsesTheSharedParentControlsWithoutEnablingTheMaster() {
        store.putString("comment_reply_prompt", "保留的旧独立提示词");
        store.putString("comment_reply_daily_limit", "20");
        View reply = openCategory("AUTO_REPLY");
        Switch master = toggle(reply, "开启自动回复");
        Switch comments = toggle(reply, "接收评论回复");
        assertFalse(master.isChecked());
        assertFalse(comments.isChecked());
        assertEquals("固定内容", ((TextView) reply.findViewWithTag("settings.value.回复模式")).getText().toString());
        assertFalse(containsText((View) comments.getParent(), "引用本页回复设置"));
        assertFalse(containsText((View) comments.getParent(), "已暂停"));
        assertNull(reply.findViewWithTag("settings.option.评论回复设置"));
        assertNull(reply.findViewWithTag("settings.toggle.开启评论回复"));
        comments.setChecked(true);
        assertTrue(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false));
        assertFalse(master.isChecked());
        assertFalse(store.getBoolean("native_auto_reply", false));
        assertFalse(store.load().automationEnabled);
        assertTrue(containsText((View) comments.getParent(), "已暂停：自动回复未开启"));
        master.setChecked(true);
        assertTrue(comments.isChecked());
        assertFalse(containsText((View) comments.getParent(), "已暂停"));
        toggle(reply, "接收私聊").setChecked(false);
        toggle(reply, "接收群聊").setChecked(false);
        assertFalse(store.getBoolean("reply_private_chats", true));
        assertFalse(store.getBoolean("reply_group_chats", false));
        assertTrue(comments.isChecked());
        option(reply, "回复内容").performClick();
        Dialog content = ShadowDialog.getLatestDialog();
        assertEquals(Arrays.asList("固定回复内容"), settingTitles(content.getWindow().getDecorView()));
        editOption(content.getWindow().getDecorView(), "固定回复内容", "所有已接收渠道共享固定正文");
        content.dismiss();
        toggle(reply, "语音回复").setChecked(true);
        editOption(reply, "每日最大回复数", "7");
        editOption(reply, "同一用户回复间隔（分钟）", "2");
        assertEquals("所有已接收渠道共享固定正文", store.getString("auto_message_text", ""));
        assertTrue(store.getBoolean(LocalOptions.REPLY_VOICE, false));
        assertEquals("7", store.getString("reply_daily_limit", ""));
        assertEquals("2", store.getString("reply_user_cooldown_minutes", ""));
        assertEquals("保留的旧独立提示词", store.getString("comment_reply_prompt", ""));
        assertEquals("20", store.getString("comment_reply_daily_limit", ""));
        assertFalse(store.values.containsKey("comment_reply_mode"));
        master.setChecked(false);
        assertTrue(comments.isChecked());
        assertTrue(containsText((View) comments.getParent(), "已暂停：自动回复未开启"));
        comments.setChecked(false);
        assertFalse(containsText((View) comments.getParent(), "已暂停"));
        assertFalse(master.isChecked());
    }

    @Test public void commentReplyReceptionKeepsOneAiPromptAndSharedDeliverySettings() {
        store.putString("reply_mode", "ai");
        store.putString("reply_prompt", "共享二级提示词");
        store.putString("comment_reply_prompt", "历史独立提示词");
        View reply = openCategory("AUTO_REPLY");
        toggle(reply, "接收评论回复").setChecked(true);
        toggle(reply, "开启自动回复").setChecked(true);
        option(reply, "回复内容").performClick();
        Dialog content = ShadowDialog.getLatestDialog();
        assertEquals(Arrays.asList("模型配置", "二级提示词"), settingTitles(content.getWindow().getDecorView()));
        option(content.getWindow().getDecorView(), "二级提示词").performClick();
        PromptEditorDialog prompt = (PromptEditorDialog) ShadowDialog.getLatestDialog();
        setEditorInput(prompt, "二级提示词", "更新后的共享二级提示词");
        prompt.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals("更新后的共享二级提示词", store.getString("reply_prompt", ""));
        assertEquals("历史独立提示词", store.getString("comment_reply_prompt", ""));
        content.dismiss();
        option(reply, "发送与记录").performClick();
        View delivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        toggle(delivery, "保存历史记录").setChecked(true);
        toggle(delivery, "悬浮提示").setChecked(true);
        editOption(delivery, "提示超时（秒）", "10");
        editOption(delivery, "延迟回复", "5");
        assertTrue(store.getBoolean("reply_save_history", false));
        assertTrue(store.getBoolean("reply_tip", false));
        assertEquals("10", store.getString("reply_notice_seconds", ""));
        assertEquals("5", store.getString("reply_delay_seconds", ""));
        assertNotNull(delivery.findViewWithTag("settings.option.回复历史记录"));
        assertNull(delivery.findViewWithTag("settings.option.评论回复记录"));
        assertFalse(store.values.containsKey("comment_reply_tip_enabled"));
        assertFalse(store.values.containsKey("comment_reply_tip_seconds"));
    }

    @Test public void commentReplySummaryTracksTheParentAndClosedCallbacksCannotWrite() {
        store.putBoolean("native_auto_reply", true);
        store.putBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true);
        View reply = openCategory("AUTO_REPLY");
        Switch master = toggle(reply, "开启自动回复");
        Switch comments = toggle(reply, "接收评论回复");
        assertTrue(comments.isChecked());
        assertFalse(store.load().automationEnabled);
        assertFalse(containsText((View) master.getParent(), "已暂停"));
        assertFalse(containsText((View) comments.getParent(), "已暂停"));
        master.setChecked(false);
        assertTrue(comments.isChecked());
        assertTrue(containsText((View) comments.getParent(), "已暂停：自动回复未开启"));
        master.setChecked(true);
        assertFalse(containsText((View) comments.getParent(), "已暂停"));
        screen.dismissChildPages();
        comments.setChecked(false);
        master.setChecked(false);
        assertTrue(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false));
        assertTrue(store.getBoolean("native_auto_reply", false));
    }

    @Test public void commentModesKeepDraftsAndOnlyAiModeEnablesMediaControls() {
        store.putString("auto_comment_text", "固定评论草稿");
        store.putString("comment_prompt", "AI 评论提示词");
        store.putBoolean("comment_recognize_video", true);
        View comment = openCategory("AUTO_COMMENT");
        assertTrue(option(comment, "素材识别").isEnabled());
        option(comment, "评论内容").performClick();
        Dialog contents = ShadowDialog.getLatestDialog();
        assertEquals(Arrays.asList("模型配置", "二级提示词"), settingTitles(contents.getWindow().getDecorView()));
        contents.dismiss();
        option(comment, "评论模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        assertFalse(option(comment, "素材识别").isEnabled());
        option(comment, "评论内容").performClick();
        assertEquals(Arrays.asList("固定评论内容"), settingTitles(ShadowDialog.getLatestDialog().getWindow().getDecorView()));
        ShadowDialog.getLatestDialog().dismiss();
        assertEquals("AI 评论提示词", store.getString("comment_prompt", ""));
        assertTrue(store.getBoolean("comment_recognize_video", false));
        assertEquals("固定评论草稿", store.getString("auto_comment_text", ""));
        option(comment, "评论模式").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 0, 0);
        assertTrue(option(comment, "素材识别").isEnabled());
    }

    @Test public void commentWorkFilterKeepsTitleAndMigratesLegacyKeywordOnlyWhitelist() {
        store.putString("comment_scope_mode", "whitelist");
        store.putString("comment_scope_keywords", "茶");
        store.putString("comment_scope_fields", "title");
        View comment = openCategory("AUTO_COMMENT");
        option(comment, "作品范围").performClick();
        View fields = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertEquals("all", store.getString("comment_scope_mode", ""));
        assertEquals("include", store.getString("comment_filter_mode", ""));
        assertEquals("title", store.getString("comment_filter_fields", ""));
        assertEquals("茶", store.getString("comment_filter_keywords", ""));
        assertNull(fields.findViewWithTag("settings.option.指定作者"));
        assertNotNull(fields.findViewWithTag("settings.option.筛选关键词"));
        option(fields, "作者范围").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 1, 1);
        assertNotNull(fields.findViewWithTag("settings.option.指定作者"));
        assertEquals("茶", store.getString("comment_scope_keywords", ""));
        assertEquals("title", store.getString("comment_scope_fields", ""));
    }

    @Test public void privateRangeAndDeliveryControlsHideInactiveConditionsWithoutDeletingValues() {
        store.putString("reply_scope_relations", "following");
        store.putString("reply_targets", "101");
        store.putString("reply_notice_seconds", "23");
        View reply = openCategory("AUTO_REPLY");
        toggle(reply, "接收私聊").setChecked(false);
        assertTrue(option(reply, "私聊范围").isEnabled());
        toggle(reply, "接收私聊").setChecked(true);
        assertTrue(option(reply, "私聊范围").isEnabled());
        option(reply, "私聊范围").performClick();
        Dialog scope = ShadowDialog.getLatestDialog();
        View fields = scope.getWindow().getDecorView();
        option(fields, "用户范围").performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).getListView().performItemClick(null, 0, 0);
        assertNull(fields.findViewWithTag("settings.option.关注关系"));
        assertNull(fields.findViewWithTag("settings.option.指定用户"));
        assertEquals("following", store.getString("reply_scope_relations", ""));
        assertEquals("101", store.getString("reply_targets", ""));
        scope.dismiss();
        option(reply, "发送与记录").performClick();
        View delivery = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(delivery.findViewWithTag("settings.option.提示超时（秒）"));
        toggle(delivery, "悬浮提示").setChecked(true);
        assertNotNull(delivery.findViewWithTag("settings.option.提示超时（秒）"));
        toggle(delivery, "悬浮提示").setChecked(false);
        assertNull(delivery.findViewWithTag("settings.option.提示超时（秒）"));
        assertEquals("23", store.getString("reply_notice_seconds", ""));
    }

    @Test
    public void categorizedAutomationCanStopExistingTasksWithoutAWrenchSettingsSurface() {
        View reply = openCategory("AUTO_REPLY");
        toggle(reply, "开启自动回复").setChecked(true);
        screen.dismissChildPages();
        assertNull(home.findViewWithTag("settings.runtime"));
        option(openCategory("AUTOPLAY"), "互动自动化").performClick();
        View runtime = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        toggle(runtime, "自动点赞").setChecked(true);
        toggle(runtime, "自动关注").setChecked(true);
        assertTrue(store.load().autoLikeEnabled);
        assertTrue(store.load().autoFollowEnabled);
        assertTrue(store.getBoolean("native_auto_reply", false));
        for (TextView control : collect(runtime, TextView.class)) {
            if ("立即停止".contentEquals(control.getText())) control.performClick();
        }
        assertFalse(store.load().automationEnabled);
        assertFalse(store.load().autoLikeEnabled);
        assertFalse(store.load().autoFollowEnabled);
        assertFalse(store.load().autoCommentEnabled);
        assertFalse(store.load().autoMessageEnabled);
        assertFalse(store.getBoolean("native_auto_reply", true));
        assertFalse(store.getBoolean("streak_enabled", true));
        assertFalse(toggle(runtime, "自动点赞").isEnabled());
        assertFalse(toggle(runtime, "自动关注").isEnabled());
    }

    @Test
    public void turningOffADraftDoesNotResumePausedLikes() {
        toggle(openCategory("AUTO_REPLY"), "开启自动回复").setChecked(true);
        screen.dismissChildPages();
        option(openCategory("AUTOPLAY"), "互动自动化").performClick();
        View runtime = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        toggle(runtime, "自动点赞").setChecked(true);
        toggle(runtime, "自动化总开关").setChecked(false);
        screen.dismissChildPages();
        toggle(openCategory("AUTO_REPLY"), "开启自动回复").setChecked(false);
        assertTrue(store.load().autoLikeEnabled);
        assertFalse(store.load().automationEnabled);
        assertFalse(store.load().autoMessageEnabled);
    }

    @Test public void closingAutoplayCategoryClosesItsAutomationDialog() {
        View page = openCategory("AUTOPLAY");
        Dialog category = ShadowDialog.getLatestDialog();
        option(page, "互动自动化").performClick();
        Dialog automation = ShadowDialog.getLatestDialog();
        assertTrue(automation.isShowing());
        category.dismiss();
        ShadowLooper.idleMainLooper();
        assertFalse(automation.isShowing());
    }

    @Test public void downloadTasksNavigationClosesWithItsParentAndDoesNotChangeSettings() {
        Map<String, Object> original = new HashMap<>(store.values);
        View page = openCategory("DOWNLOAD");
        Dialog category = ShadowDialog.getLatestDialog();
        option(page, "下载任务").performClick();
        Dialog tasks = ShadowDialog.getLatestDialog();
        assertTrue(tasks.isShowing());
        assertNotNull(tasks.getWindow().getDecorView().findViewWithTag("download.tasks"));
        assertTrue(containsText(tasks.getWindow().getDecorView(), "关闭此页不会停止下载"));
        category.dismiss();
        ShadowLooper.idleMainLooper();
        assertFalse(tasks.isShowing());
        assertEquals(original, store.values);

        option(openCategory("DOWNLOAD"), "下载任务").performClick();
        Dialog reopened = ShadowDialog.getLatestDialog();
        assertTrue(reopened.isShowing());
        screen.dismissChildPages();
        ShadowLooper.idleMainLooper();
        assertFalse(reopened.isShowing());
    }

    @Test public void closedReplyAndCommentPagesIgnoreTheirOldControlsAndEditors() {
        String[][] kinds = {{"AUTO_REPLY", "语音回复", "reply_voice", "回复内容", "固定回复内容", "auto_message_text"},
                {"AUTO_COMMENT", "语音评论", "comment_voice", "评论内容", "固定评论内容", "auto_comment_text"}};
        for (String[] kind : kinds) {
            store.putString("comment_mode", "fixed");
            store.putString(kind[5], "原内容");
            View page = openCategory(kind[0]);
            Switch oldVoice = toggle(page, kind[1]);
            option(page, kind[3]).performClick();
            View contentPage = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            option(contentPage, kind[4]).performClick();
            AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input." + kind[4]);
            input.setText("迟到编辑");
            screen.dismissChildPages();
            ShadowLooper.idleMainLooper();
            assertFalse(editor.isShowing());
            oldVoice.setChecked(true);
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertFalse(kind[0], store.getBoolean(kind[2], false));
            assertEquals(kind[0], "原内容", store.getString(kind[5], ""));
        }
    }

    @Test public void replyAndCommentActivationPreservesChangesMadeAfterOpeningTheirPage() {
        for (String category : new String[]{"AUTO_REPLY", "AUTO_COMMENT"}) {
            View page = openCategory(category);
            store.save(new FeatureSettings(true, true, true, true, false, false, false,
                    true, "新筛选词", true, 19, true, 37, false, true, true, false, false));
            toggle(page, "AUTO_REPLY".equals(category) ? "开启自动回复" : "开启自动评论").setChecked(true);
            FeatureSettings current = store.load();
            assertTrue(category, current.downloadEnabled);
            assertTrue(category, current.removeWatermark);
            assertEquals(category, "新筛选词", current.keyword);
            assertEquals(category, 19, current.minimumDurationSeconds);
            assertEquals(category, 37, current.minimumLikeCount);
            assertTrue(category, current.autoLikeEnabled);
            assertTrue(category, current.autoFollowEnabled);
            assertTrue(category, current.automationEnabled);
            screen.dismissChildPages();
            ShadowLooper.idleMainLooper();
        }
        assertTrue(store.getBoolean("native_auto_reply", false));
        assertTrue(store.getBoolean("native_auto_comment", false));
    }

    @Test public void automationDialogRefreshesParentSnapshotAndSurvivesOtherCategorySaves() throws Exception {
        View autoplay = openCategory("AUTOPLAY");
        option(autoplay, "互动自动化").performClick();
        Dialog automation = ShadowDialog.getLatestDialog();
        View controls = automation.getWindow().getDecorView();
        toggle(controls, "自动化总开关").setChecked(true);
        toggle(controls, "自动点赞").setChecked(true);
        java.lang.reflect.Field snapshot = ModuleSettingsScreen.class.getDeclaredField("settings");
        snapshot.setAccessible(true);
        FeatureSettings parent = (FeatureSettings) snapshot.get(screen);
        assertTrue(parent.automationEnabled);
        assertTrue(parent.autoLikeEnabled);
        automation.dismiss();
        ShadowLooper.idleMainLooper();
        toggle(autoplay, "自动滑动到下一个视频").setChecked(true);
        assertTrue(store.load().automationEnabled);
        assertTrue(store.load().autoLikeEnabled);
        screen.dismissChildPages();
        toggle(openCategory("DOWNLOAD"), "启用下载菜单").setChecked(true);
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.load().automationEnabled);
        assertTrue(store.load().autoLikeEnabled);

        screen.dismissChildPages();
        option(openCategory("AUTOPLAY"), "互动自动化").performClick();
        ShadowDialog.getLatestDialog().getWindow().getDecorView()
                .findViewWithTag("automation.stop").performClick();
        parent = (FeatureSettings) snapshot.get(screen);
        assertFalse(parent.automationEnabled);
        assertFalse(parent.autoLikeEnabled);
        assertTrue(parent.downloadEnabled);
    }

    @Test
    public void migratedDownloadVersionAndShortVideoControlsRemainAccessibleFromTheirCategories() {
        store.settings = new FeatureSettings(true, false, false, false, false, false, false,
                false, "", false, 0, false, 0, false, false, false, false, false);
        View download = openCategory("DOWNLOAD");
        assertTrue(toggle(download, "启用下载菜单").isChecked());
        toggle(download, "启用下载菜单").setChecked(false);
        assertFalse(store.load().downloadEnabled);
        toggle(download, "无水印下载图集或者视频").setChecked(true);
        assertTrue(store.load().removeWatermark);
        assertFalse(store.load().downloadEnabled);
        screen.dismissChildPages();
        option(openCategory("SPOOF"), "版本信息").performClick();
        Dialog parent = ShadowDialog.getLatestDialog();
        View runtime = parent.getWindow().getDecorView();
        toggle(runtime, "版本信息修改").setChecked(true);
        editOption(runtime, "版本名称", "40.6.1");
        assertTrue(parent.isShowing());
        assertEquals("40.6.1", store.getString("fake_version_name", ""));
        editOption(runtime, "版本号", "400602");
        assertTrue(parent.isShowing());
        assertTrue(store.getBoolean("spoof_version", false));
        assertEquals("400602", store.getString("fake_version_code", ""));
        screen.dismissChildPages();
        View filters = openCategory("FILTER");
        assertFalse(option(filters, "最短时长（秒）").isEnabled());
        toggle(filters, "过滤总开关").setChecked(true);
        toggle(filters, "过滤短视频").setChecked(true);
        editOption(filters, "最短时长（秒）", "10");
        assertTrue(store.load().minimumDurationEnabled);
        assertEquals(10, store.load().minimumDurationSeconds);
    }

    @Test
    public void immersiveBarControlsAndRefreshConfirmationUseOneCategorizedSurface() {
        View page = openCategory("INTERFACE");
        option(page, "沉浸式全屏").performClick();
        Dialog bars = ShadowDialog.getLatestDialog();
        View controls = bars.getWindow().getDecorView();
        assertEquals(Arrays.asList("隐藏状态栏", "隐藏导航栏"), settingTitles(controls));
        toggle(controls, "隐藏状态栏").setChecked(true);
        assertTrue(store.getBoolean("remove_black_edges", false));
        assertFalse(store.getBoolean("hide_navigation_bar", false));
        toggle(controls, "隐藏导航栏").setChecked(true);
        toggle(controls, "隐藏状态栏").setChecked(false);
        assertFalse(store.getBoolean("remove_black_edges", true));
        assertTrue(store.getBoolean("hide_navigation_bar", false));
        bars.dismiss();
        ShadowLooper.idleMainLooper();
        toggle(page, "首页刷新确认").setChecked(true);
        assertTrue(store.getBoolean("confirm_refresh", false));
        option(page, "沉浸式全屏").performClick();
        controls = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertFalse(toggle(controls, "隐藏状态栏").isChecked());
        assertTrue(toggle(controls, "隐藏导航栏").isChecked());
        assertNull(home.findViewWithTag("settings.runtime"));
    }

    @Test
    public void downloadRelatedEnablementKeepsItsSingleMenuSwitchInSync() {
        View page = openCategory("DOWNLOAD");
        Switch menu = toggle(page, "启用下载菜单");
        assertFalse(menu.isChecked());
        Switch direct = toggle(page, "直接保存全部媒体");
        assertFalse(direct.isChecked());
        assertFalse(containsText((View) direct.getParent(), "开启后手势直接保存视频或全部图片，不显示下载选择。"));
        direct.setChecked(true);
        assertTrue(menu.isChecked());
        assertTrue(store.load().downloadEnabled);
        assertTrue(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
        menu.setChecked(false);
        toggle(page, "自定义文件名").setChecked(true);
        assertTrue(menu.isChecked());
        assertTrue(store.load().downloadEnabled);
        menu.setChecked(false);
        toggle(page, "去除下载限制").setChecked(true);
        assertTrue(menu.isChecked());
        assertTrue(store.load().downloadEnabled);
        menu.setChecked(false);
        assertFalse(store.load().downloadEnabled);
        assertTrue(store.getBoolean("download_custom_name", false));
        assertTrue(store.getBoolean("remove_download_limits", false));
        assertTrue(store.getBoolean(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, false));
    }

    @Test
    public void dismissingCommentChoicesKeepsTheirSelectionAndUpdatesTheOptionSummary() {
        View page = openCategory("COMMENT");
        View tools = option(page, "长按操作");
        assertNull(page.findViewWithTag("settings.toggle.长按操作"));
        assertTrue(tools.performClick());
        AlertDialog choices = (AlertDialog) ShadowDialog.getLatestDialog();
        bindDialogChoices(choices);
        choices.getListView().performItemClick(null, 0, 0);
        choices.cancel();
        ShadowLooper.idleMainLooper();
        assertTrue(store.getBoolean("copy_comment_text", false));
        assertFalse(store.getBoolean("comment_media_tools", false));
        assertTrue(containsText(tools, "复制评论"));
        assertTrue(tools.performClick());
        choices = (AlertDialog) ShadowDialog.getLatestDialog();
        bindDialogChoices(choices);
        assertTrue(choices.getListView().isItemChecked(0));
        choices.getListView().performItemClick(null, 0, 0);
        choices.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(store.getBoolean("copy_comment_text", true));
        assertFalse(store.getBoolean("comment_media_tools", false));
        assertFalse(store.getBoolean("copy_source_data", false));
        assertFalse(containsText(tools, "复制评论"));
    }

    @Test
    public void gestureEditorsHaveNineIndependentSelectorsInThreeRows() {
        View page = openCategory("INTERFACE");
        String[] names = {"长按事件", "双击事件"};
        String[] prefixes = {"long_press", "double_tap"};
        for (int gesture = 0; gesture < names.length; gesture++) {
            option(page, names[gesture]).performClick();
            Dialog gridDialog = ShadowDialog.getLatestDialog();
            View gridPage = gridDialog.getWindow().getDecorView();
            ViewGroup grid = gridPage.findViewWithTag("settings.gesture.grid." + prefixes[gesture]);
            assertNotNull(names[gesture], grid);
            assertEquals(3, grid.getChildCount());
            for (int row = 0; row < 3; row++) {
                ViewGroup cells = (ViewGroup) grid.getChildAt(row);
                assertEquals(3, cells.getChildCount());
                for (int column = 0; column < 3; column++) {
                    int region = row * 3 + column;
                    String key = prefixes[gesture] + "_" +
                            com.douai.module.feature.FeedGestureToolsHook.REGION_IDS[region];
                    View cell = gridPage.findViewWithTag("settings.gesture." + key);
                    assertSame(cells.getChildAt(column), cell);
                    assertTrue(cell.performClick());
                    AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
                    assertTrue(chooser.getListView().isItemChecked(0));
                    chooser.getListView().performItemClick(null, 2, 2);
                    ShadowLooper.idleMainLooper();
                    assertEquals("copy_text", store.getString(key, "none"));
                    assertTrue(names[gesture] + " region " + region + " keeps grid open", gridDialog.isShowing());
                }
            }
            // Open the next sibling before the pending dismissal callback executes.
            gridDialog.dismiss();
        }
        assertEquals(18, store.values.size());
    }

    @Test
    public void queuedGridDismissalClosesItsOldChooserWithoutClosingTheNextGrid() {
        View page = openCategory("INTERFACE");
        option(page, "长按事件").performClick();
        Dialog firstGrid = ShadowDialog.getLatestDialog();
        firstGrid.getWindow().getDecorView().findViewWithTag("settings.gesture.long_press_top_left").performClick();
        Dialog oldChooser = ShadowDialog.getLatestDialog();
        assertTrue(oldChooser.isShowing());
        firstGrid.dismiss();
        option(page, "双击事件").performClick();
        Dialog nextGrid = ShadowDialog.getLatestDialog();
        assertNotSame(firstGrid, nextGrid);
        ShadowLooper.idleMainLooper();
        assertFalse(firstGrid.isShowing());
        assertFalse(oldChooser.isShowing());
        assertTrue(nextGrid.isShowing());
        assertNotNull(nextGrid.getWindow().getDecorView().findViewWithTag("settings.gesture.grid.double_tap"));
    }

    @Test
    public void gestureEditorInheritsLegacyColumnsAndChangesOnlyTheSelectedCell() {
        store.putString("long_press_left", "copy_text");
        store.putString("long_press_top_left", "none");
        View page = openCategory("INTERFACE");
        option(page, "长按事件").performClick();
        Dialog gridDialog = ShadowDialog.getLatestDialog();
        View gridPage = gridDialog.getWindow().getDecorView();
        String[] ids = {"top_left", "middle_left", "bottom_left"};
        for (int index = 0; index < ids.length; index++) {
            View cell = gridPage.findViewWithTag("settings.gesture.long_press_" + ids[index]);
            TextView preview = cell.findViewWithTag("settings.gesture.value.long_press_" + ids[index]);
            assertEquals(index == 0 ? "无" : "复制文案", preview.getText().toString());
        }
        assertTrue(gridPage.findViewWithTag("settings.gesture.long_press_middle_left").performClick());
        AlertDialog chooser = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(chooser.getListView().isItemChecked(2));
        chooser.getListView().performItemClick(null, 4, 4);
        ShadowLooper.idleMainLooper();
        assertEquals("open_comments", store.getString("long_press_middle_left", "none"));
        assertEquals("copy_text", store.getString("long_press_left", "none"));
        assertEquals("none", store.getString("long_press_top_left", "missing"));
        assertFalse(store.values.containsKey("long_press_bottom_left"));
        screen.dismissChildPages();
        assertFalse(gridDialog.isShowing());
    }

    @Test
    public void narrowGestureGridKeepsNineCellsAndWrappedActionLabelsWithinBounds() {
        View page = openCategory("INTERFACE");
        store.putString("double_tap_action", "toggle_components");
        option(page, "双击事件").performClick();
        View gridPage = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        ViewGroup grid = gridPage.findViewWithTag("settings.gesture.grid.double_tap");
        grid.measure(View.MeasureSpec.makeMeasureSpec(250, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.AT_MOST));
        grid.layout(0, 0, 250, grid.getMeasuredHeight());
        for (int row = 0; row < 3; row++) {
            ViewGroup cells = (ViewGroup) grid.getChildAt(row);
            int lastRight = 0;
            for (int column = 0; column < 3; column++) {
                View cell = cells.getChildAt(column);
                assertTrue(cell.getLeft() >= lastRight);
                assertTrue(cell.getRight() <= cells.getWidth());
                assertTrue(cell.getHeight() >= 84);
                for (TextView label : collect(cell, TextView.class)) {
                    assertNotNull(label.getLayout());
                    for (int line = 0; line < label.getLayout().getLineCount(); line++) {
                        assertEquals(0, label.getLayout().getEllipsisCount(line));
                    }
                }
                lastRight = cell.getRight();
            }
        }
    }

    @Test
    @Config(qualifiers = "w296dp-h560dp-mdpi")
    public void longCategoryHeadingFitsOnANarrowScreenWithLargerFonts() {
        android.content.res.Configuration configuration = new android.content.res.Configuration(
                activity.getResources().getConfiguration());
        configuration.fontScale = 1.3f;
        activity.getResources().updateConfiguration(configuration,
                activity.getResources().getDisplayMetrics());
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
        View page = openCategory("STREAK");
        ViewGroup container = page.findViewById(android.R.id.content);
        ViewGroup panel = (ViewGroup) container.getChildAt(0);
        panel.measure(View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY));
        panel.layout(0, 0, 296, 560);
        TextView heading = (TextView) ((ViewGroup) panel.getChildAt(0)).getChildAt(1);
        assertTrue(heading.getPaint().measureText(heading.getText().toString()) <= heading.getWidth());
    }

    @Test
    public void narrowSubpagesKeepEntireLabelsAndControlsWithinTheirRows() {
        for (String category : Arrays.asList("INTERFACE", "FILTER", "DOWNLOAD", "AUTO_REPLY", "AUTO_COMMENT", "COMMENT", "MESSAGE")) {
            View page = openCategory(category);
            ViewGroup container = page.findViewById(android.R.id.content);
            View panel = container.getChildAt(0);
            panel.measure(View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY));
            panel.layout(0, 0, 296, 560);
            for (ViewGroup row : settingRows(page)) {
                assertTrue(category + " label width", row.getChildAt(0).getWidth() > 0);
                View labels = row.getChildAt(0);
                TextView title = (TextView) ((ViewGroup) labels).getChildAt(0);
                Layout textLayout = title.getLayout();
                assertNotNull(category + " " + title.getText(), textLayout);
                for (int line = 0; line < textLayout.getLineCount(); line++) {
                    assertEquals(title.getText().toString(), 0, textLayout.getEllipsisCount(line));
                }
                if (row.getChildCount() > 1) {
                    View control = row.getChildAt(1);
                    assertTrue(title.getText().toString(), labels.getRight() <= control.getLeft());
                    assertTrue(title.getText().toString(), control.getRight() <= row.getWidth());
                }
            }
            assertEquals(0, collect(page, EditText.class).size());
            screen.dismissChildPages();
        }
    }

    private static void bindDialogChoices(AlertDialog dialog) {
        ShadowLooper.idleMainLooper();
        ListView choices = dialog.getListView();
        choices.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        choices.layout(0, 0, choices.getMeasuredWidth(), choices.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
    }

    private View openCategory(String category) {
        View row = home.findViewWithTag("settings.category." + category);
        assertNotNull(category, row);
        assertTrue(category, row.performClick());
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertNotNull(category, dialog);
        assertTrue(category, dialog.isShowing());
        return dialog.getWindow().getDecorView();
    }

    private static Switch toggle(View page, String title) {
        Switch toggle = page.findViewWithTag("settings.toggle." + title);
        assertNotNull(title, toggle);
        return toggle;
    }

    private static View option(View page, String title) {
        View row = page.findViewWithTag("settings.option." + title);
        assertNotNull(title, row);
        return row;
    }

    private static void editOption(View page, String title, String value) {
        View row = option(page, title);
        assertTrue(title, row.isEnabled());
        assertTrue(title, row.performClick());
        Dialog latest = ShadowDialog.getLatestDialog();
        assertTrue(title, latest instanceof AlertDialog);
        AlertDialog editor = (AlertDialog) latest;
        EditText input = editor.getWindow().getDecorView().findViewWithTag("settings.input." + title);
        assertNotNull(title, input);
        input.setText(value);
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(title, editor.isShowing());
    }

    private static void setEditorInput(Dialog dialog, String label, String value) {
        EditText input = null;
        for (EditText candidate : collect(dialog.getWindow().getDecorView(), EditText.class)) {
            if (label.equals(candidate.getContentDescription())) { input = candidate; break; }
        }
        assertNotNull(label, input);
        input.setText(value);
    }

    private static void assertUnavailableFiltersRemainDisabled(View page) {
        for (String title : Arrays.asList("整页筛空时保留原列表", "去小游戏开关", "去长视频开关")) {
            assertTrue(title, toggle(page, title).isEnabled());
        }
        assertTrue(option(page, "最长时长（秒）").isEnabled());
    }

    private static List<String> settingTitles(View root) {
        List<String> result = new ArrayList<>();
        for (ViewGroup row : settingRows(root)) {
            ViewGroup labels = (ViewGroup) row.getChildAt(0);
            result.add(((TextView) labels.getChildAt(0)).getText().toString());
        }
        return result;
    }

    private static List<ViewGroup> settingRows(View root) {
        List<ViewGroup> result = new ArrayList<>();
        collectSettingRows(root, result);
        return result;
    }

    private static void collectSettingRows(View root, List<ViewGroup> result) {
        if (root instanceof Switch && String.valueOf(root.getTag()).startsWith("settings.toggle.")) {
            result.add((ViewGroup) root.getParent());
            return;
        }
        if (String.valueOf(root.getTag()).startsWith("settings.option.")) {
            result.add((ViewGroup) root);
            return;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectSettingRows(group.getChildAt(index), result);
            }
        }
    }

    private static List<View> optionRows(View root) {
        List<View> result = new ArrayList<>();
        for (ViewGroup row : settingRows(root)) {
            if (String.valueOf(row.getTag()).startsWith("settings.option.")) result.add(row);
        }
        return result;
    }

    private static boolean containsText(View root, String fragment) {
        for (TextView text : collect(root, TextView.class)) {
            if (text.getText().toString().contains(fragment)) return true;
        }
        return false;
    }

    private static <T extends View> List<T> collect(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                result.addAll(collect(group.getChildAt(index), type));
            }
        }
        return result;
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings settings) { this.settings = settings; }
        public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }
}
