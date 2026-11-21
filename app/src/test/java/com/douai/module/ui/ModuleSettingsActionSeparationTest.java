package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.ai.mcp.McpClient;
import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SidebarSettings;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.scan.HostAdaptation;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

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

    @Test public void sidebarChatOffersSkillsWhileTheModelConfigurationTestStaysUnchanged() throws Exception {
        AiModelConfig model = AiModelConfig.fromJson(new JSONObject().put("id", "work-model")
                .put("name", "工作模型").put("apiEndpoint", "https://example.test/v1")
                .put("modelName", "model"));
        new AiModelSettings(List.of(model), model.id).save(store);

        screen.openSidebarEntry("ai_chat");
        View workChat = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        EditText workInput = workChat.findViewWithTag("ai.chat.input");
        workInput.setText("/");
        assertPresent(workChat, "ai.chat.skill.摘要");
        assertPresent(workChat, "ai.chat.skill.查询");

        screen.openSidebarEntry("ai_models");
        View models = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(option(models, "测试当前配置").performClick());
        View testChat = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        EditText testInput = testChat.findViewWithTag("ai.chat.input");
        testInput.setText("/");
        assertNull(testChat.findViewWithTag("ai.chat.skill.摘要"));
        assertNull(testChat.findViewWithTag("ai.chat.skill.查询"));
    }

    @Test public void skillsManagerOffersSimulationWithoutAModelConfiguration() {
        assertTrue(option(open("EXTENSION"), "AI Skills").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertPresent(manager, "skills.enabled");
        assertPresent(manager, "skills.requirements");
        assertTrue(manager.findViewWithTag("skills.generate.mock").isEnabled());
        assertNotNull(manager.findViewWithTag("skills.service.mock-orders"));
    }

    @Test public void registeringAServiceRequiresSeparateConfirmationOfItsEndpointAndBudget() throws Exception {
        assertTrue(option(open("EXTENSION"), "AI Skills").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(manager.findViewWithTag("skills.service.register").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = editor.getWindow().getDecorView();
        String endpoint = "https://orders.example.test/query";
        ((EditText) form.findViewWithTag("skills.service.id")).setText("fixture-orders");
        ((EditText) form.findViewWithTag("skills.service.label")).setText("订单模拟目标");
        ((EditText) form.findViewWithTag("skills.service.endpoint")).setText(endpoint);
        ((EditText) form.findViewWithTag("skills.service.cost")).setText("2");
        EditText token = form.findViewWithTag("skills.service.token");
        assertTrue(token.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();

        SkillServices services = SkillServices.forContext(activity);
        SkillServices.Service registered = services.list().stream()
                .filter(service -> service.id().equals("fixture-orders")).findFirst().orElseThrow();
        assertFalse(services.isAuthorized(registered));
        assertTrue(manager.findViewWithTag("skills.service.fixture-orders.authorize").performClick());
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = confirmation.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains(endpoint));
        assertTrue(message.getText().toString().contains("每次调用最多 2 费用单位"));
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(services.isAuthorized(registered));
    }

    @Test public void mcpSettingsSaveAnInertEndpointUntilItsProtocolAndAddressAreApproved() throws Exception {
        assertTrue(option(open("EXTENSION"), "在线 MCP").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertPresent(manager, "mcp.import");
        assertPresent(manager, "mcp.export");
        assertTrue(manager.findViewWithTag("mcp.service.add").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = editor.getWindow().getDecorView();
        String endpoint = "https://tools.example.test/mcp";
        ((EditText) form.findViewWithTag("mcp.service.id")).setText("mcp-fixture");
        ((EditText) form.findViewWithTag("mcp.service.label")).setText("测试工具");
        ((EditText) form.findViewWithTag("mcp.service.endpoint")).setText(endpoint);
        ((Spinner) form.findViewWithTag("mcp.service.protocol")).setSelection(1);
        EditText token = form.findViewWithTag("mcp.service.token");
        assertTrue(token.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertTrue(editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();

        McpRegistry registry = McpRegistry.forContext(activity);
        McpRegistry.Service service = registry.services().stream()
                .filter(item -> item.id().equals("mcp-fixture")).findFirst().orElseThrow();
        assertEquals(McpClient.LEGACY, service.protocol());
        assertFalse(registry.isAuthorized(service));
        assertTrue(manager.findViewWithTag("mcp.service.mcp-fixture.authorize").performClick());
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = confirmation.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains(endpoint));
        assertTrue(message.getText().toString().contains(McpClient.LEGACY));
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(registry.isAuthorized(service));
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
