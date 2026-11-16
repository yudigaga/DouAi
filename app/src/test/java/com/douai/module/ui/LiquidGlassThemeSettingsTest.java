package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.view.View;
import android.widget.ListView;
import android.widget.Switch;

import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.SettingsRepository;

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
public final class LiquidGlassThemeSettingsTest {
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
        screen.close();
        activity.finish();
    }

    @Test public void gravityIsAvailableOnlyForGlassAndItsChoiceSurvivesReturningToOriginal() {
        View page = openInterface();
        Switch gravity = page.findViewWithTag("settings.toggle.重力感应");
        assertNotNull(gravity);
        assertTrue(gravity.isChecked());
        assertFalse(gravity.isEnabled());
        choose(page, 1);
        assertEquals("liquid_glass", store.getString(LiquidGlassSettings.STYLE, ""));
        assertTrue(gravity.isEnabled());
        gravity.setChecked(false);
        choose(page, 0);
        assertFalse(gravity.isEnabled());
        assertFalse(gravity.isChecked());
        assertFalse(store.getBoolean(LiquidGlassSettings.GRAVITY, true));
        choose(page, 1);
        assertTrue(gravity.isEnabled());
        assertFalse(gravity.isChecked());
    }

    @Test public void malformedPreferenceTypesOpenSafelyWithAnInactiveThemeAndGravity() {
        preferences.edit().putBoolean(LiquidGlassSettings.STYLE, true)
                .putString(LiquidGlassSettings.GRAVITY, "true").commit();
        View page = openInterface();
        Switch gravity = page.findViewWithTag("settings.toggle.重力感应");
        assertFalse(gravity.isChecked());
        assertFalse(gravity.isEnabled());
        choose(page, 1);
        assertTrue(gravity.isEnabled());
        assertFalse(gravity.isChecked());
    }

    @Test public void navigationLabelsCanBeHiddenOnlyInGlassAndTheChoiceSurvivesThemeChanges() {
        View page=openInterface();
        Switch hide=page.findViewWithTag("settings.toggle.隐藏导航文字");
        assertNotNull(hide);assertFalse(hide.isEnabled());assertFalse(hide.isChecked());
        View icons=page.findViewWithTag("settings.option.首页图标");
        assertNotNull(icons);assertFalse(icons.isEnabled());
        choose(page,1);assertTrue(hide.isEnabled());assertTrue(icons.isEnabled());hide.setChecked(true);
        assertTrue(store.getBoolean(HomeUiCustomizationSettings.HIDE_LABELS,false));
        choose(page,0);assertFalse(hide.isEnabled());assertFalse(icons.isEnabled());assertTrue(hide.isChecked());
        choose(page,1);assertTrue(hide.isEnabled());assertTrue(hide.isChecked());
        assertTrue(page.findViewWithTag("settings.option.首页图标").performClick());
        ShadowLooper.idleMainLooper();
        assertNotNull(ShadowDialog.getLatestDialog().getWindow().getDecorView().findViewWithTag("home.icons.target.feed_like"));
    }

    @Test public void helpExplainsNavigationOnlyGlassAndBoundedStaticIconImport() {
        HelpContent.Section section = HelpContent.search("液态玻璃").stream()
                .filter(value -> "interface".equals(value.id)).findFirst().orElseThrow();
        String text = section.parts.stream().map(part -> part.text).collect(java.util.stream.Collectors.joining("\n"));
        for (String expected : new String[]{"界面主题", "原版", "重力感应", "41.0.0", "兼容材质", "上导航", "下导航", "图标在文字上方", "隐藏导航文字", "无图标", "静态 WebP", "SVG", "自动转", "256 KiB", "32 KiB", "恢复原图", "不加玻璃"}) {
            assertTrue(expected, text.contains(expected));
        }
        assertTrue(text.contains("真实背景"));
        assertTrue(text.contains("传感器"));
        assertTrue(text.contains("系统动画"));
    }

    private View openInterface() {
        assertTrue(home.findViewWithTag("settings.category.INTERFACE").performClick());
        ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        assertTrue(dialog.isShowing());
        return dialog.getWindow().getDecorView();
    }

    private void choose(View page, int index) {
        assertTrue(page.findViewWithTag("settings.option.界面主题").performClick());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        ListView list = dialog.getListView();
        list.performItemClick(list.getAdapter().getView(index, null, list), index, index);
        ShadowLooper.idleMainLooper();
    }
}
