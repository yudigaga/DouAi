package com.douai.module.ui;

import android.app.Activity;
import android.view.View;

import com.douai.module.config.SettingsRepository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class ConfigurationStatusHomeTest {
    @Test public void settingsHomeDoesNotShowRecentConfigurationStatus() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, new SettingsRepository(activity));
        try {
            View root = screen.createView();
            assertNull(root.findViewWithTag("settings.status.configuration"));
        } finally {
            screen.close();
            activity.finish();
        }
    }
}
