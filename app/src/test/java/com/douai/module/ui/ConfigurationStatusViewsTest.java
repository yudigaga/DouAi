package com.douai.module.ui;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ConfigurationStatusViewsTest {
    @Test public void nativeSettingsHomeOmitsConfigurationStatusWithoutChangingSettings() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostSettingsStore store = new HostSettingsStore(activity);
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        java.util.Map<String, ?> before = store.snapshot();
        try {
            View root = screen.createView();
            assertNull(root.findViewWithTag("settings.status.configuration"));
            assertNotNull(root.findViewWithTag("settings.search"));
            assertNotNull(root.findViewWithTag("settings.category.EXTENSION"));
            assertEquals(before, store.snapshot());
        } finally {
            screen.close();
            activity.finish();
        }
    }

    @Test public void nativeHostSettingsHomeOmitsConfigurationStatus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, new HostSettingsStore(activity));
        try {
            View root = screen.createView();
            assertNull(root.findViewWithTag("settings.status.configuration"));
            assertNotNull(root.findViewWithTag("settings.search"));
            assertNotNull(root.findViewWithTag("settings.category.EXTENSION"));
        } finally {
            screen.close();
            activity.finish();
        }
    }

    @Test public void nativeFrameworkStatusPreservesItsMessageAlongsideTheError() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, new SettingsRepository(activity));
        try {
            View root = screen.createView();
            screen.setFrameworkSyncStatus(false, "设置已更新，等待同步", "服务暂不可用");
            assertTrue(containsText(root, "设置已更新，等待同步\n服务暂不可用"));
            screen.setFrameworkSyncStatus(true, "设置已同步，等待抖音读取", null);
            assertTrue(containsText(root, "设置已同步，等待抖音读取"));
            assertFalse(containsText(root, "设置已更新，等待同步\n服务暂不可用"));
            screen.setFrameworkSyncStatus(true, "服务已连接", "远程写入失败");
            assertTrue(containsText(root, "服务已连接\n远程写入失败"));
            java.lang.reflect.Field marker = ModuleSettingsScreen.class.getDeclaredField("syncMark");
            marker.setAccessible(true);
            GradientDrawable background = (GradientDrawable) ((View) marker.get(screen)).getBackground();
            assertNotEquals(Color.rgb(0, 146, 117), background.getColor().getDefaultColor());
        } finally {
            screen.close();
            activity.finish();
        }
    }

    private static boolean containsText(View root, String expected) {
        if (root instanceof TextView && expected.contentEquals(((TextView) root).getText())) return true;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) if (containsText(group.getChildAt(index), expected)) return true;
        }
        return false;
    }
}
