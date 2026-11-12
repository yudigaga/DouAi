package com.douai.module.ui;

import android.app.Activity;
import android.view.View;
import android.widget.Switch;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AutomationSettingsDialogTest {
    private Activity activity;
    private MemoryStore store;
    private AutomationSettingsDialog page;
    private View root;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
    }
    @After public void closePage() { if (page != null) page.close(); }

    @Test public void masterPausesAndRestoresChoicesWithoutOverwritingNewSettings() {
        open();
        assertFalse(toggle("自动点赞").isEnabled());
        toggle("自动化总开关").setChecked(true);
        assertTrue(toggle("自动点赞").isEnabled());
        toggle("自动点赞").setChecked(true);
        toggle("自动关注").setChecked(true);
        toggle("自动化总开关").setChecked(false);
        assertTrue(toggle("自动点赞").isChecked());
        assertTrue(store.settings.autoLikeEnabled);
        assertTrue(store.settings.autoFollowEnabled);
        assertFalse(toggle("自动关注").isEnabled());

        store.settings = fixture(false, true, true, true, true);
        toggle("自动化总开关").setChecked(true);
        assertTrue(store.settings.downloadEnabled);
        assertTrue(store.settings.hideRecommendationMerchants);
        assertEquals("保留其他配置", store.settings.keyword);
        assertTrue(store.settings.autoCommentEnabled);
        assertTrue(store.settings.autoMessageEnabled);
        assertTrue(toggle("自动点赞").isChecked());
        assertTrue(toggle("自动关注").isEnabled());
        assertEquals(5, store.saves);
    }

    @Test public void masterAndChildSwitchesKeepCompactLabelsWithoutStaticDescriptions() {
        open();
        for (String title : new String[]{"自动化总开关", "自动点赞", "自动关注"}) {
            android.view.ViewGroup row = (android.view.ViewGroup) toggle(title).getParent();
            android.view.ViewGroup labels = (android.view.ViewGroup) row.getChildAt(0);
            int visible = 0;
            for (int index = 0; index < labels.getChildCount(); index++) {
                if (labels.getChildAt(index).getVisibility() == View.VISIBLE) visible++;
            }
            assertEquals(title, 1, visible);
        }
    }

    @Test public void stopClearsAllAutomationAndClosedPageCannotWriteAgain() {
        store.settings = fixture(true, true, true, true, true);
        for (String key : new String[]{"native_auto_reply", "native_auto_comment", "streak_enabled",
                LocalOptions.COMMENT_REPLY_ENABLED}) {
            store.putBoolean(key, true);
        }
        open();
        root.findViewWithTag("automation.stop").performClick();
        assertFalse(store.settings.automationEnabled);
        assertFalse(store.settings.autoLikeEnabled);
        assertFalse(store.settings.autoFollowEnabled);
        assertFalse(store.settings.autoCommentEnabled);
        assertFalse(store.settings.autoMessageEnabled);
        assertTrue(store.settings.downloadEnabled);
        for (String key : new String[]{"native_auto_reply", "native_auto_comment", "streak_enabled",
                LocalOptions.COMMENT_REPLY_ENABLED}) {
            assertFalse(store.getBoolean(key, true));
        }
        assertFalse(toggle("自动化总开关").isChecked());
        assertFalse(toggle("自动点赞").isChecked());
        assertFalse(toggle("自动关注").isEnabled());
        assertEquals(1, store.saves);
        page.close();
        assertFalse(page.dialog().isShowing());
        toggle("自动化总开关").setChecked(true);
        assertEquals(1, store.saves);
        assertFalse(store.settings.automationEnabled);
    }

    @Test public void masterPausePreservesCommentReplyChoiceButImmediateStopDisablesItsSwitch() {
        store.settings = fixture(true, false, false, false, false);
        store.putBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true);
        open();
        toggle("自动化总开关").setChecked(false);
        assertTrue(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, false));

        root.findViewWithTag("automation.stop").performClick();
        assertFalse(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true));
        toggle("自动化总开关").setChecked(true);
        assertTrue(store.settings.automationEnabled);
        assertFalse(store.getBoolean(LocalOptions.COMMENT_REPLY_ENABLED, true));
    }

    private void open() {
        page = new AutomationSettingsDialog(activity, store, false);
        page.dialog().setOwnerActivity(activity);
        page.dialog().show();
        root = page.dialog().getWindow().getDecorView();
    }
    private Switch toggle(String title) { return root.findViewWithTag("settings.toggle." + title); }
    private static FeatureSettings fixture(boolean master, boolean like, boolean follow, boolean comment, boolean message) {
        return new FeatureSettings(true, true, true, true, false, true, false, true, true, "保留其他配置",
                true, 12, true, 30, master, like, follow, comment, message);
    }
    private static final class MemoryStore implements SettingsStore {
        FeatureSettings settings = FeatureSettings.defaults();
        final Map<String, Boolean> values = new HashMap<>();
        int saves;
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; saves++; }
        public boolean getBoolean(String key, boolean fallback) { return values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return fallback; }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { }
        public boolean isInHost() { return true; }
    }
}
