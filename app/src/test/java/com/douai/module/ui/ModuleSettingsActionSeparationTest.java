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
import com.douai.module.ai.skills.SkillStore;
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

    @Test public void skillsManagerGuidesAuthoringToTheSingleChatEditor() {
        assertTrue(option(open("EXTENSION"), "AI Skills").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertPresent(manager, "skills.enabled");
        assertPresent(manager, "skills.author_chat");
        assertNull(manager.findViewWithTag("skills.requirements"));
        assertNull(manager.findViewWithTag("skills.generate.mock"));
        assertNull(manager.findViewWithTag("skills.generate.model"));
        assertNull(manager.findViewWithTag("skills.generate.shared_services"));
        assertNull(manager.findViewWithTag("skills.service.register"));
        assertNull(manager.findViewWithTag("skills.service.mock-orders"));
        assertNull(manager.findViewWithTag("skills.service." + SkillServices.MOCK_IMAGE + ".authorize"));
    }

    @Test public void legacyInstalledSkillKeepsItsServiceAndAuthorizationWithoutSharedSettings() throws Exception {
        String name = "旧版查单";
        SkillServices services = SkillServices.forContext(activity);
        SkillServices.Service existing = new SkillServices.Service("fixture-orders", "订单模拟目标",
                "https://orders.example.test/query", 2);
        services.register(existing, "");
        services.authorize(existing, existing.costUnits());
        SkillStore skills = SkillStore.forContext(activity);
        String definition = new JSONObject().put("schemaVersion", 1).put("name", name)
                .put("description", "读取订单状态").put("documentation", "输入需要查询的订单号。")
                .put("execution", new JSONObject().put("kind", "service").put("maxInput", 100)
                        .put("serviceId", existing.id()))
                .put("trial", new JSONObject().put("input", "A123").put("expected", "模拟服务响应"))
                .toString();
        skills.installGenerated(definition, skills.previewGenerated(definition));

        assertTrue(option(open("EXTENSION"), "AI Skills").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(manager.findViewWithTag("skills.generate.shared_services"));
        assertNull(manager.findViewWithTag("skills.service.register"));
        assertNull(manager.findViewWithTag("skills.service." + existing.id()));
        assertTrue(manager.findViewWithTag("skills.row." + name).performClick());
        View detail = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(detail.findViewWithTag("skills.generate.shared_services"));
        assertNull(detail.findViewWithTag("skills.service.register"));
        TextView service = detail.findViewWithTag("skills.detail.service");
        assertEquals(existing.label(), service.getText().toString());
        assertEquals(existing, skills.detail(name).currentService());
        assertTrue(services.isAuthorized(existing));

        assertTrue(detail.findViewWithTag("skills.detail.toggle").performClick());
        ShadowLooper.idleMainLooper();
        assertFalse(skills.detail(name).current().enabled());
        assertEquals(existing, services.find(existing.id()));
        assertTrue(services.isAuthorized(existing));
    }

    @Test public void skillsRowOpensItsOwnImageConfigurationAndCanAuthorizeAScopedMock() throws Exception {
        String name = "封面草稿";
        SkillStore skills = SkillStore.forContext(activity);
        String draft = new JSONObject(SkillStore.mockDraft("生图")).put("name", name).toString();
        assertNotNull(skills.saveGenerated(draft, null).pending());

        assertTrue(option(open("EXTENSION"), "AI Skills").performClick());
        View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertNull(manager.findViewWithTag("skills.generate.shared_services"));
        assertNull(manager.findViewWithTag("skills.service.register"));
        assertTrue(manager.findViewWithTag("skills.row." + name).performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = detail.getWindow().getDecorView();
        TextView skillName = form.findViewWithTag("skills.detail.name");
        assertEquals("/" + name, skillName.getText().toString());
        assertPresent(form, "skills.detail.endpoint");
        assertPresent(form, "skills.detail.fetch_models");
        EditText key = form.findViewWithTag("skills.detail.key");
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertEquals(0, key.length());
        assertNull(form.findViewWithTag("skills.generate.shared_services"));
        assertNull(form.findViewWithTag("skills.service.register"));

        ((Spinner) form.findViewWithTag("skills.detail.adapter")).setSelection(1);
        ShadowLooper.idleMainLooper();
        assertTrue(form.findViewWithTag("skills.detail.save").performClick());
        SkillStore.Detail saved = skills.detail(name);
        SkillServices services = SkillServices.forContext(activity);
        assertTrue(saved.pending());
        assertEquals(name, saved.service().skillName());
        assertEquals(SkillServices.Adapter.MOCK_IMAGE, saved.service().adapter());
        assertFalse(services.isAuthorized(saved.service()));
        assertTrue(form.findViewWithTag("skills.detail.authorize").performClick());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = review.findViewById(android.R.id.message);
        assertTrue(message.getText().toString().contains("本地模拟"));
        assertTrue(review.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();

        SkillStore.Detail enabled = skills.detail(name);
        assertFalse(enabled.pending());
        assertEquals(saved.service(), enabled.currentService());
        assertTrue(enabled.current().enabled());
        assertTrue(services.isAuthorized(enabled.currentService()));
        assertFalse(services.isAuthorized(services.find(SkillServices.MOCK_IMAGE)));
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

    @Test public void mcpDeleteIsSeparateFromRevokeAndNeedsExplicitConfirmation() throws Exception {
        McpRegistry registry = McpRegistry.forContext(activity);
        McpRegistry.Service authorized = new McpRegistry.Service("active-fixture", "已授权工具",
                "https://active.example.test/mcp", McpClient.MODERN);
        McpRegistry.Service draft = new McpRegistry.Service("draft-fixture", "待授权工具",
                "https://draft.example.test/mcp", McpClient.LEGACY);
        registry.register(authorized, "");
        registry.register(draft, "");
        registry.authorize(authorized);
        View manager = openMcpSettings();
        assertPresent(manager, "mcp.service.active-fixture.revoke");
        assertPresent(manager, "mcp.service.draft-fixture.authorize");

        AlertDialog cancel = requestMcpDeletion(manager, authorized.id());
        TextView message = cancel.findViewById(android.R.id.message);
        assertNotNull(message);
        for (String part : List.of(authorized.label(), authorized.endpoint(), "本机设置", "密钥", "工具目录",
                "关联斜杠命令", "不会向远端发送请求")) {
            assertTrue(part, message.getText().toString().contains(part));
        }
        assertTrue(cancel.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(registry.services().containsAll(List.of(authorized, draft)));
        assertTrue(registry.isAuthorized(authorized));

        confirmMcpDeletion(requestMcpDeletion(manager, draft.id()));
        assertEquals(List.of(authorized), registry.services());
        assertNull(manager.findViewWithTag("mcp.service.draft-fixture.delete"));
        assertPresent(manager, "mcp.service.active-fixture.revoke");

        confirmMcpDeletion(requestMcpDeletion(manager, authorized.id()));
        assertTrue(registry.services().isEmpty());
        assertNull(manager.findViewWithTag("mcp.service.active-fixture.revoke"));
        assertNull(manager.findViewWithTag("mcp.service.active-fixture.delete"));
    }

    @Test public void mcpStaleDeleteRefreshesTheNewServiceInsteadOfRemovingIt() throws Exception {
        McpRegistry registry = McpRegistry.forContext(activity);
        McpRegistry.Service original = new McpRegistry.Service("replace-fixture", "原服务",
                "https://original.example.test/mcp", McpClient.LEGACY);
        registry.register(original, "");
        View manager = openMcpSettings();
        AlertDialog stale = requestMcpDeletion(manager, original.id());

        McpRegistry.Service replacement = new McpRegistry.Service(original.id(), "已更新的服务",
                "https://replacement.example.test/mcp", McpClient.MODERN);
        registry.register(replacement, "");
        registry.authorize(replacement);
        confirmMcpDeletion(stale);

        assertEquals(List.of(replacement), registry.services());
        assertTrue(registry.isAuthorized(replacement));
        TextView status = manager.findViewWithTag("mcp.status");
        assertTrue(status.getText().toString().contains("变化"));
        assertPresent(manager, "mcp.service.replace-fixture.revoke");
        assertNull(manager.findViewWithTag("mcp.service.replace-fixture.authorize"));
        AlertDialog updated = requestMcpDeletion(manager, replacement.id());
        TextView message = updated.findViewById(android.R.id.message);
        assertTrue(message.getText().toString().contains(replacement.endpoint()));
    }

    private View openMcpSettings() {
        assertTrue(option(open("EXTENSION"), "在线 MCP").performClick());
        return ShadowDialog.getLatestDialog().getWindow().getDecorView();
    }

    private static AlertDialog requestMcpDeletion(View manager, String serviceId) {
        String tag = "mcp.service." + serviceId + ".delete";
        View delete = manager.findViewWithTag(tag);
        assertNotNull(tag, delete);
        assertTrue(delete.performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static void confirmMcpDeletion(AlertDialog confirmation) {
        assertEquals("确认删除", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
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
