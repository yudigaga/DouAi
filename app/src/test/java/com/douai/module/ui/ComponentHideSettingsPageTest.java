package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;

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

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ComponentHideSettingsPageTest {
    @Test
    public void homepageGroupsControlsAndReturnKeepsItsStatusAndSettings() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView();
        activity.setContentView(home);
        screen.setScanStatus(new ScanStatus("适配状态保留", "已完成扫描", true, false));
        home.measure(View.MeasureSpec.makeMeasureSpec(320, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        home.layout(0, 0, 320, 600);
        assertEquals(1, countText(home, "组件隐藏"));
        assertEquals(0, countText(home, "精简视频界面"));
        assertEquals(0, countText(home, "右侧操作区透明度"));
        assertEquals(0, countText(home, "隐藏状态栏"));
        assertEquals(1, countText(home, "自动回复"));
        assertEquals(1, countText(home, "自动评论"));
        assertEquals(1, countText(home, "本地伪装"));
        assertEquals(Arrays.asList(
                "settings.category.BASIC", "settings.category.INTERFACE", "settings.category.FILTER",
                "settings.category.DOWNLOAD", "settings.category.AUTOPLAY", "settings.category.AUTO_REPLY",
                "settings.category.AUTO_COMMENT", "settings.category.COMMENT", "settings.category.STREAK",
                "settings.category.COMPONENT_HIDE", "settings.category.MESSAGE", "settings.category.SPOOF",
                "settings.category.EXTENSION", "settings.category.ABOUT"), categoryTags(home));
        home.scrollTo(0, 200);

        findDescription(home, "界面相关").performClick();
        Dialog interfaceDialog = ShadowDialog.getLatestDialog();
        assertTrue(interfaceDialog.isShowing());
        View interfaceRoot = interfaceDialog.getWindow().getDecorView();
        assertEquals(1, countText(interfaceRoot, "沉浸式全屏"));
        interfaceRoot.findViewWithTag("settings.option.沉浸式全屏").performClick();
        Dialog barsDialog = ShadowDialog.getLatestDialog();
        View barsRoot = barsDialog.getWindow().getDecorView();
        ((Switch) barsRoot.findViewWithTag("settings.toggle.隐藏状态栏")).setChecked(true);
        ((Switch) barsRoot.findViewWithTag("settings.toggle.隐藏导航栏")).setChecked(true);
        assertTrue(store.getBoolean("remove_black_edges", false));
        assertTrue(store.getBoolean("hide_navigation_bar", false));
        barsDialog.dismiss();
        ((Switch) interfaceRoot.findViewWithTag("settings.toggle.隐藏顶部Tab开关")).setChecked(true);
        assertTrue(store.getBoolean("hide_top_tabs", false));
        findDescription(interfaceRoot, "返回设置分类").performClick();
        assertFalse(interfaceDialog.isShowing());

        findDescription(home, "组件隐藏").performClick();
        Dialog child = ShadowDialog.getLatestDialog();
        assertTrue(child.isShowing());
        View childRoot = child.getWindow().getDecorView();
        ((Switch) childRoot.findViewWithTag(ComponentHideSettings.ENABLED)).setChecked(true);
        assertTrue(store.getBoolean(ComponentHideSettings.ENABLED, false));
        findDescription(childRoot, "返回根目录").performClick();
        assertFalse(child.isShowing());
        assertEquals(1, countText(home, "适配状态保留"));
        assertEquals(200, home.getScrollY());
        findDescription(home, "组件隐藏").performClick();
        Dialog reopened = ShadowDialog.getLatestDialog();
        assertTrue(reopened.isShowing());
        screen.dismissChildPages();
        assertFalse(reopened.isShowing());
        activity.finish();
    }

    @Test
    public void categorySearchFiltersTheReferenceGroupsAndDownloadSettingsRemainEditable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView();
        activity.setContentView(home);

        EditText search = home.findViewWithTag("settings.search");
        assertNotNull(search);
        for (String query : Arrays.asList("本地数据分析", "配置备份", "数据备份")) {
            search.setText(query);
            assertEquals(query, View.VISIBLE, findDescription(home, "扩展功能").getVisibility());
            assertEquals(query, View.GONE, findDescription(home, "基本功能").getVisibility());
        }
        search.setText("下载");
        assertEquals(View.VISIBLE, findDescription(home, "下载相关").getVisibility());
        assertEquals(View.GONE, findDescription(home, "基本功能").getVisibility());
        findDescription(home, "下载相关").performClick();

        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        View detail = dialog.getWindow().getDecorView();
        Switch download = (Switch) detail.findViewWithTag("settings.toggle.无水印下载图集或者视频");
        assertNotNull(download);
        download.setChecked(true);
        assertTrue(store.load().removeWatermark);
        Switch menu = (Switch) detail.findViewWithTag("settings.toggle.启用下载菜单");
        assertNotNull(menu);
        menu.setChecked(true);
        assertTrue(store.load().downloadEnabled);
        screen.dismissChildPages();
        assertFalse(dialog.isShowing());
        activity.finish();
    }

    @Test
    public void homepageUsesReferenceCategoryNamesAndCounts() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, new MemoryStore());
        View root = screen.createView();
        String[][] expected = {
                {"BASIC", "基本功能", "3项"},
                {"INTERFACE", "界面相关", "13项"},
                {"FILTER", "视频筛选", "16项"},
                {"DOWNLOAD", "下载相关", "14项"},
                {"AUTOPLAY", "自动播放", "7项"},
                {"AUTO_REPLY", "自动回复", "13项"},
                {"AUTO_COMMENT", "自动评论", "8项"},
                {"COMMENT", "评论相关", "11项"},
                {"STREAK", "自动续火花", "7项"},
                {"COMPONENT_HIDE", "组件隐藏", "24项"},
                {"MESSAGE", "消息管理", "8项"},
                {"SPOOF", "本地伪装", "6项"},
                {"EXTENSION", "扩展功能", "4项"},
                {"ABOUT", "关于", ""}
        };
        for (String[] item : expected) {
            View row = root.findViewWithTag("settings.category." + item[0]);
            assertNotNull(item[0], row);
            assertEquals(item[1], ((TextView) ((ViewGroup) row).getChildAt(0)).getText().toString());
            assertEquals(item[2], ((TextView) ((ViewGroup) row).getChildAt(1)).getText().toString());
        }
        activity.finish();
    }

    @Test
    public void newReferenceCategoriesRemainOffBeforeUserEnablement() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView();
        activity.setContentView(home);

        findDescription(home, "自动续火花").performClick();
        Dialog streakDialog = ShadowDialog.getLatestDialog();
        assertTrue(streakDialog.isShowing());
        assertTrue(((Switch) streakDialog.getWindow().getDecorView()
                .findViewWithTag("settings.toggle.自动续火花")).isEnabled());
        assertFalse(((Switch) streakDialog.getWindow().getDecorView()
                .findViewWithTag("settings.toggle.自动续火花")).isChecked());
        streakDialog.dismiss();

        findDescription(home, "消息管理").performClick();
        Dialog messageDialog = ShadowDialog.getLatestDialog();
        Switch recall = (Switch) messageDialog.getWindow().getDecorView()
                .findViewWithTag("settings.toggle.消息防撤回");
        assertNotNull(recall);
        assertTrue(recall.isEnabled());
        assertFalse(recall.isChecked());
        messageDialog.dismiss();

        findDescription(home, "扩展功能").performClick();
        Dialog extensionDialog = ShadowDialog.getLatestDialog();
        assertEquals(1, countText(extensionDialog.getWindow().getDecorView(), "AI 模型设置"));
        assertEquals(1, countText(extensionDialog.getWindow().getDecorView(), "文本转语音设置"));
        View extensionRoot = extensionDialog.getWindow().getDecorView();
        assertNotNull(extensionRoot.findViewWithTag("settings.option.配置备份"));
        extensionRoot.findViewWithTag("settings.option.本地数据分析").performClick();
        Dialog analyticsDialog = ShadowDialog.getLatestDialog();
        assertTrue(analyticsDialog.isShowing());
        assertNotNull(analyticsDialog.getWindow().getDecorView().findViewWithTag("analytics.root"));
        analyticsDialog.dismiss();
        assertTrue(extensionDialog.isShowing());
        extensionRoot.findViewWithTag("settings.option.本地数据分析").performClick();
        Dialog reopenedAnalytics = ShadowDialog.getLatestDialog();
        assertTrue(reopenedAnalytics.isShowing());
        screen.dismissChildPages();
        assertFalse(reopenedAnalytics.isShowing());
        assertFalse(extensionDialog.isShowing());
        activity.finish();
    }

    @Test
    public void screenshotControlsAppearInOrderAndRestoreSavedValues() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        store.putBoolean(ComponentHideSettings.ENABLED, true);
        store.putString(Component.AVATAR.key, "-2");
        store.putString(Component.TOP_NAV.key, "-1");
        store.putString(Component.PROGRESS.key, "70");
        View root = new ComponentHideSettingsPage(activity, store).createView();
        List<String> expected = new ArrayList<>(Arrays.asList(
                ComponentHideSettings.ENABLED, ComponentHideSettings.UNIFORM_ENABLED,
                ComponentHideSettings.UNIFORM_ALPHA, ComponentHideSettings.HIDE_ON_SWITCH,
                ComponentHideSettings.OPEN_COMMENTS_ON_SHOW, ComponentHideSettings.SHOW_ON_PAUSE));
        for (Component component : Component.values()) expected.add(component.key);
        List<String> actual = new ArrayList<>();
        collectControlKeys(root, actual);
        assertEquals(expected, actual);
        assertEquals(18, Component.values().length);
        assertEquals(19, collect(root, SeekBar.class).size());
        assertEquals(5, collect(root, Switch.class).size());
        assertEquals(0, ((SeekBar) root.findViewWithTag(Component.AVATAR.key)).getProgress());
        assertEquals(1, ((SeekBar) root.findViewWithTag(Component.TOP_NAV.key)).getProgress());
        assertEquals(72, ((SeekBar) root.findViewWithTag(Component.PROGRESS.key)).getProgress());
        assertEquals(0, countText(root, "作者昵称头顶的"));
        activity.finish();
    }

    @Test
    public void touchSlidersSaveAtReleaseAndKeyboardSavesWithoutEnablingMaster() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        View root = new ComponentHideSettingsPage(activity, store).createView();
        SeekBar slider = root.findViewWithTag(Component.LIKE.key);
        assertEquals(257, slider.getMax());
        slider.setProgress(0);
        slider.measure(View.MeasureSpec.makeMeasureSpec(300, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(48, View.MeasureSpec.EXACTLY));
        slider.layout(0, 0, 300, 48);
        sendTouch(slider, MotionEvent.ACTION_DOWN, 16);
        sendTouch(slider, MotionEvent.ACTION_MOVE, 299);
        assertFalse(store.values.containsKey(Component.LIKE.key));
        sendTouch(slider, MotionEvent.ACTION_UP, 299);
        assertEquals("255", store.getString(Component.LIKE.key, ""));
        assertTrue(store.values.containsKey(ComponentHideSettings.ENABLED));
        assertFalse(store.getBoolean(ComponentHideSettings.ENABLED, true));
        slider.setProgress(0);
        assertTrue(slider.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));
        assertEquals(slider.getProgress() - 2,
                Integer.parseInt(store.getString(Component.LIKE.key, "")));
        assertFalse(store.getBoolean(ComponentHideSettings.ENABLED, true));
        ((Switch) root.findViewWithTag(ComponentHideSettings.UNIFORM_ENABLED)).setChecked(true);
        assertTrue(store.getBoolean(ComponentHideSettings.UNIFORM_ENABLED, false));
        activity.finish();
    }

    @Test
    public void firstEditPreservesLegacyMasterAndSmallWidthDoesNotOverlapControls() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        store.putBoolean("hide_interface", true);
        ComponentHideSettingsPage page = new ComponentHideSettingsPage(activity, store);
        View root = page.createView();
        root.measure(View.MeasureSpec.makeMeasureSpec(296, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(560, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        ((Switch) root.findViewWithTag(ComponentHideSettings.SHOW_ON_PAUSE)).setChecked(false);
        assertTrue(store.getBoolean(ComponentHideSettings.ENABLED, false));
        for (SeekBar slider : collect(root, SeekBar.class)) {
            assertTrue(slider.getWidth() > 0);
            assertTrue(slider.getRight() <= ((View) slider.getParent()).getWidth());
            slider.setProgress(0);
            assertEquals(0, slider.getProgress());
            slider.setProgress(257);
            assertEquals(257, slider.getProgress());
        }
        for (Switch toggle : collect(root, Switch.class)) {
            ViewGroup row = (ViewGroup) toggle.getParent();
            View label = row.getChildAt(0);
            assertTrue(label.getRight() <= toggle.getLeft());
            assertTrue(toggle.getRight() <= row.getWidth());
        }
        ViewGroup panel = (ViewGroup) root;
        assertTrue(panel.getChildAt(1) instanceof ScrollView);
        assertTrue(panel.getChildAt(1).getTop() >= panel.getChildAt(0).getBottom());
        activity.finish();
    }

    @Test
    public void customViewListPersistsAndDeleteRemovesTheStoredLocator() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout host = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setText("custom target");
        host.addView(target);
        activity.setContentView(host);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), target);
        MemoryStore store = new MemoryStore();
        store.putBoolean(ComponentHideSettings.ENABLED, true);
        store.putString(CustomViewLocator.SETTINGS_KEY,
                CustomViewLocator.serialize(java.util.Collections.singletonList(locator)));

        ComponentHideSettingsPage page = new ComponentHideSettingsPage(activity, store);
        View root = page.createView();
        assertEquals(1, countText(root, locator.label));
        assertEquals("1 个", ((TextView) root.findViewWithTag("component.custom.count")).getText().toString());
        assertEquals(locator.identityKey(), ComponentHideSettings.readFrom(store)
                .customViews.get(0).identityKey());
        ImageButton remove = (ImageButton) root.findViewWithTag(
                "component.custom.delete." + locator.identityKey());
        assertNotNull(remove);
        remove.performClick();
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("删除", confirmation.getButton(AlertDialog.BUTTON_POSITIVE).getText());
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();

        assertEquals(0, ComponentHideSettings.readFrom(store).customViews.size());
        assertEquals(1, countText(root, "暂无自定义视图"));
        assertEquals("0 个", ((TextView) root.findViewWithTag("component.custom.count")).getText().toString());
        activity.finish();
    }

    @Test
    public void customViewDetailsShowScopeAndPathAndCopyWithoutEditingSettings() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout host = new FrameLayout(activity);
        TextView target = new TextView(activity);
        target.setId(android.R.id.title);
        target.setText("private dynamic host text");
        target.setContentDescription("private dynamic description");
        host.addView(target);
        activity.setContentView(host);
        CustomViewLocator locator = CustomViewLocator.capture(
                activity.getWindow().getDecorView(), host, target);
        MemoryStore store = new MemoryStore();
        store.putBoolean(ComponentHideSettings.ENABLED, true);
        store.putString(CustomViewLocator.SETTINGS_KEY,
                CustomViewLocator.serialize(java.util.Collections.singletonList(locator)));
        Map<String, Object> saved = new HashMap<>(store.values);

        ComponentHideSettingsPage page = new ComponentHideSettingsPage(activity, store);
        View root = page.createView();
        assertEquals(1, countText(root, locator.scopeDescription() + " · " + locator.matchingDescription()));
        View row = root.findViewWithTag("component.custom." + locator.identityKey());
        row.performClick();
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView information = detail.getWindow().getDecorView().findViewWithTag("component.custom.details");
        assertNotNull(information);
        String displayed = information.getText().toString();
        assertTrue(displayed.contains(locator.scopeDescription()));
        assertTrue(displayed.contains(locator.matchingDescription()));
        assertTrue(displayed.contains(locator.viewClassName));
        assertTrue(displayed.contains(locator.pathDescription()));
        assertFalse(displayed.contains("private dynamic host text"));
        assertFalse(displayed.contains("private dynamic description"));
        assertEquals(saved, store.values);

        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
        assertNotNull(clipboard.getPrimaryClip());
        assertEquals(displayed, clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        assertEquals(saved, store.values);
        row.performClick();
        Dialog reopened = ShadowDialog.getLatestDialog();
        assertTrue(reopened.isShowing());
        page.dismiss();
        assertFalse(reopened.isShowing());
        activity.finish();
    }

    @Test
    public void individualEditLeavesUniformModeAndPreservesOtherChoices() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        MemoryStore store = new MemoryStore();
        store.putBoolean(ComponentHideSettings.ENABLED, true);
        store.putBoolean(ComponentHideSettings.UNIFORM_ENABLED, true);
        store.putString(ComponentHideSettings.UNIFORM_ALPHA, "-2");
        store.putString(Component.COMMENT.key, "-2");
        store.putString(Component.LIKE.key, "-2");
        View root = new ComponentHideSettingsPage(activity, store).createView();
        SeekBar progress = root.findViewWithTag(Component.PROGRESS.key);
        progress.setProgress(256);
        assertTrue(progress.onKeyDown(KeyEvent.KEYCODE_DPAD_RIGHT,
                new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)));

        ComponentHideSettings settings = ComponentHideSettings.readFrom(store);
        assertFalse(settings.uniformEnabled);
        assertFalse(((Switch) root.findViewWithTag(ComponentHideSettings.UNIFORM_ENABLED)).isChecked());
        assertEquals(-2, settings.alpha(Component.COMMENT));
        assertEquals(-2, settings.alpha(Component.LIKE));
        assertEquals(255, settings.alpha(Component.PROGRESS));
        assertEquals("-2", store.getString(ComponentHideSettings.UNIFORM_ALPHA, ""));
        assertTrue(settings.enabled);
        activity.finish();
    }

    private static void sendTouch(SeekBar slider, int action, float x) {
        MotionEvent event = MotionEvent.obtain(0, 0, action, x, 24, 0);
        slider.dispatchTouchEvent(event);
        event.recycle();
    }

    private static int countText(View root, String text) {
        int count = root instanceof TextView && text.contentEquals(((TextView) root).getText()) ? 1 : 0;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) count += countText(group.getChildAt(i), text);
        }
        return count;
    }

    private static List<String> categoryTags(View root) {
        List<String> tags = new ArrayList<>();
        collectCategoryTags(root, tags);
        return tags;
    }

    private static void collectCategoryTags(View root, List<String> result) {
        Object tag = root.getTag();
        if (tag instanceof String && ((String) tag).startsWith("settings.category.")) {
            result.add((String) tag);
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectCategoryTags(group.getChildAt(i), result);
            }
        }
    }

    private static View findDescription(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) {
            return root;
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View result = findDescription(group.getChildAt(i), description);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void collectControlKeys(View root, List<String> result) {
        if (root instanceof Switch || root instanceof SeekBar) result.add((String) root.getTag());
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) collectControlKeys(group.getChildAt(i), result);
        }
    }

    private static <T extends View> List<T> collect(View root, Class<T> type) {
        List<T> result = new ArrayList<>();
        if (type.isInstance(root)) result.add(type.cast(root));
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) result.addAll(collect(group.getChildAt(i), type));
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
