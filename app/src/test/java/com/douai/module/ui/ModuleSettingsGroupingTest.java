package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.EditText;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.HostSettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ModuleSettingsGroupingTest {
    private static final String[][] GROUP_LABELS = {
            {"界面与内容", "基础弹窗、主题与手势、推荐筛选、组件显示"},
            {"播放与自动化", "播放规则、互动自动化、自动回复/评论、续火花"},
            {"评论与消息", "手动评论工具、消息展示与语音"},
            {"下载与工具", "下载、AI模型、文本转语音、分析与备份"},
            {"本地与帮助", "本地资料/版本显示、帮助、日志与项目信息"}
    };
    private static final List<String> CATEGORY_TAGS = Arrays.asList(
            "settings.category.BASIC", "settings.category.INTERFACE", "settings.category.FILTER",
            "settings.category.COMPONENT_HIDE", "settings.category.AUTOPLAY", "settings.category.AUTO_REPLY",
            "settings.category.AUTO_COMMENT", "settings.category.STREAK", "settings.category.COMMENT",
            "settings.category.MESSAGE", "settings.category.DOWNLOAD", "settings.category.EXTENSION",
            "settings.category.SPOOF", "settings.category.ABOUT");
    private static final List<String> HOME_ORDER = Arrays.asList(
            "settings.category.BASIC", "settings.category.INTERFACE", "settings.category.FILTER",
            "settings.category.COMPONENT_HIDE", "settings.category.AUTOPLAY", "settings.category.AUTO_REPLY",
            "settings.category.AUTO_COMMENT", "settings.category.STREAK", "settings.category.COMMENT",
            "settings.category.MESSAGE", "settings.category.DOWNLOAD", "settings.category.EXTENSION",
            "settings.category.SPOOF", "settings.category.ABOUT");

    private Activity activity;
    private HostSettingsStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private EditText search;
    private Map<String, ?> saved;

    @Before public void createScreen() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new HostSettingsStore(activity);
        store.putBoolean(ComponentHideSettings.ENABLED, true);
        store.putString(Component.AVATAR.key, "-2");
        store.putString(Component.TOP_NAV.key, "-1");
        store.putString(Component.PROGRESS.key, "70");
        saved = new HashMap<>(store.snapshot());
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
        search = home.findViewWithTag("settings.search");
    }

    @After public void closeScreen() {
        if (screen != null) screen.dismissChildPages();
        if (activity != null) activity.finish();
    }

    @Test public void nativeFlatHomeKeepsOriginalRoutesAndSearchOrder() {
        assertCurrentHome("");
        assertFlatHome();

        search.setText("相关");
        assertCurrentHome("相关");
        assertFlatSearchCategories("settings.category.INTERFACE", "settings.category.DOWNLOAD",
                "settings.category.COMMENT");
        search.setText("本地");
        assertCurrentHome("本地");
        assertFlatSearchCategories("settings.category.SPOOF");

        String[][] features = {
                {"API Key", "ai", "扩展功能 > AI 模型设置", "settings.option.添加模型配置"},
                {"minimax", "speech", "扩展功能 > 文本转语音设置", "settings.option.语音来源"}
        };
        for (String[] feature : features) {
            search.setText(feature[0]);
            assertCurrentHome(feature[0]);
            assertFlatSearchCategories();
            assertEquals(Collections.singletonList("settings.search.result." + feature[1]),
                    tags(visibleTaggedViews(home, "settings.search.result.")));
            View result = visibleTag(home, "settings.search.result." + feature[1]);
            assertEquals(feature[0] + " result must retain its original path", 1,
                    visibleTextCount(result, feature[2], true));
            assertTrue(feature[0], result.performClick());
            Dialog destination = ShadowDialog.getLatestDialog();
            assertNotNull(feature[0], destination);
            assertTrue(feature[0], destination.isShowing());
            visibleTag(destination.getWindow().getDecorView(), feature[3]);
            assertEquals(feature[0] + " navigation must not save settings", saved, store.snapshot());
            destination.dismiss();
            assertFalse(feature[0], destination.isShowing());
            assertCurrentHome(feature[0]);
            assertFlatSearchCategories();
            visibleTag(home, "settings.search.result." + feature[1]);
        }

        search.setText("");
        assertCurrentHome("");
        assertFlatHome();
        assertEquals(View.GONE, home.findViewWithTag("settings.search.results").getVisibility());
        search.setText("   ");
        assertCurrentHome("   ");
        assertFlatHome();
        assertEquals(View.GONE, home.findViewWithTag("settings.search.results").getVisibility());

        List<View> categories = visibleTaggedViews(home, "settings.category.");
        View componentRow = visibleTag(home, "settings.category.COMPONENT_HIDE");
        assertSame("The fourth visible category must retain the original component route", categories.get(3), componentRow);
        assertTrue(componentRow.performClick());
        Dialog componentPage = ShadowDialog.getLatestDialog();
        assertNotNull(componentPage);
        assertTrue(componentPage.isShowing());
        View page = componentPage.getWindow().getDecorView();
        assertTrue(((Switch) visibleTag(page, ComponentHideSettings.ENABLED)).isChecked());
        assertEquals(0, ((SeekBar) visibleTag(page, Component.AVATAR.key)).getProgress());
        assertEquals(1, ((SeekBar) visibleTag(page, Component.TOP_NAV.key)).getProgress());
        assertEquals(72, ((SeekBar) visibleTag(page, Component.PROGRESS.key)).getProgress());
        assertEquals("透明度 -2", ((TextView) visibleTag(page, Component.AVATAR.key + ".value")).getText().toString());
        assertEquals("透明度 -1", ((TextView) visibleTag(page, Component.TOP_NAV.key + ".value")).getText().toString());
        assertEquals("透明度 70", ((TextView) visibleTag(page, Component.PROGRESS.key + ".value")).getText().toString());
        assertNull("Component route must not open download settings", page.findViewWithTag("settings.option.下载方式"));
        assertEquals(saved, store.snapshot());
        assertTrue(visibleDescription(page, "返回根目录").performClick());
        assertFalse(componentPage.isShowing());
        assertCurrentHome("   ");
        assertFlatHome();
        assertEquals(saved, store.snapshot());
    }

    private void assertFlatHome() {
        assertEquals(Collections.emptyList(), tags(visibleTaggedViews(home, "settings.group.")));
        assertEquals(CATEGORY_TAGS, tags(visibleTaggedViews(home, "settings.category.")));
        assertEquals("Flat home must not retain hidden group headers", Collections.emptyList(),
                tags(allTaggedViews(home, "settings.group.")));
        assertEquals("All fourteen original category tags must occur exactly once", CATEGORY_TAGS,
                tags(allTaggedViews(home, "settings.category.")));
        List<String> projection = new ArrayList<>();
        for (View view : visibleViews(home)) {
            String tag = String.valueOf(view.getTag());
            if (tag.startsWith("settings.category.")) projection.add(tag);
        }
        assertEquals(HOME_ORDER, projection);
        for (String[] labels : GROUP_LABELS) {
            assertEquals(labels[0], 0, visibleTextCount(home, labels[0], false));
            assertEquals(labels[1], 0, visibleTextCount(home, labels[1], false));
        }
        assertEquals(saved, store.snapshot());
    }

    private void assertFlatSearchCategories(String... expected) {
        assertEquals(Collections.emptyList(), tags(visibleTaggedViews(home, "settings.group.")));
        for (String[] labels : GROUP_LABELS) {
            assertEquals(labels[0] + " title must stay absent during search", 0, visibleTextCount(home, labels[0], false));
            assertEquals(labels[1] + " summary must stay absent during search", 0, visibleTextCount(home, labels[1], false));
        }
        assertEquals(Arrays.asList(expected), tags(visibleTaggedViews(home, "settings.category.")));
        assertEquals(saved, store.snapshot());
    }

    private void assertCurrentHome(String query) {
        ViewGroup content = activity.findViewById(android.R.id.content);
        assertEquals(1, content.getChildCount());
        assertSame("Search and child-page return must retain the original home", home, content.getChildAt(0));
        assertSame(search, visibleTag(home, "settings.search"));
        assertEquals(query, search.getText().toString());
    }

    private static void assertNonInteractiveHeader(View header) {
        assertFalse(String.valueOf(header.getTag()), header.isClickable());
        assertFalse(String.valueOf(header.getTag()), header.isLongClickable());
        assertFalse(String.valueOf(header.getTag()), header.isFocusable());
        assertFalse(String.valueOf(header.getTag()), header.hasOnClickListeners());
        if (header instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) header;
            for (int index = 0; index < group.getChildCount(); index++) assertNonInteractiveHeader(group.getChildAt(index));
        }
    }

    private static View visibleTag(View scope, String tag) {
        List<View> matches = new ArrayList<>();
        for (View view : visibleViews(scope)) if (tag.equals(view.getTag())) matches.add(view);
        assertEquals("Exactly one visible node in the current page: " + tag, 1, matches.size());
        return matches.get(0);
    }

    private static View visibleDescription(View scope, String description) {
        List<View> matches = new ArrayList<>();
        for (View view : visibleViews(scope)) if (description.contentEquals(view.getContentDescription() == null
                ? "" : view.getContentDescription())) matches.add(view);
        assertEquals("Exactly one visible action: " + description, 1, matches.size());
        return matches.get(0);
    }

    private static int visibleTextCount(View scope, String expected, boolean contains) {
        int count = 0;
        for (View view : visibleViews(scope)) if (view instanceof TextView) {
            String text = ((TextView) view).getText().toString();
            if (contains ? text.contains(expected) : text.equals(expected)) count++;
        }
        return count;
    }

    private static List<View> visibleTaggedViews(View scope, String prefix) {
        List<View> matches = new ArrayList<>();
        for (View view : visibleViews(scope)) if (String.valueOf(view.getTag()).startsWith(prefix)) matches.add(view);
        return matches;
    }

    private static List<View> allTaggedViews(View scope, String prefix) {
        List<View> matches = new ArrayList<>();
        if (String.valueOf(scope.getTag()).startsWith(prefix)) matches.add(scope);
        if (scope instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) scope;
            for (int index = 0; index < group.getChildCount(); index++) matches.addAll(allTaggedViews(group.getChildAt(index), prefix));
        }
        return matches;
    }

    private static List<String> tags(List<View> views) {
        List<String> result = new ArrayList<>();
        for (View view : views) result.add(String.valueOf(view.getTag()));
        return result;
    }

    private static List<View> visibleViews(View scope) {
        List<View> result = new ArrayList<>();
        for (ViewParent parent = scope.getParent(); parent instanceof View; parent = parent.getParent()) {
            if (((View) parent).getVisibility() != View.VISIBLE) return result;
        }
        collectVisible(scope, result);
        return result;
    }

    private static void collectVisible(View view, List<View> result) {
        if (view.getVisibility() != View.VISIBLE) return;
        result.add(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collectVisible(group.getChildAt(index), result);
        }
    }
}
