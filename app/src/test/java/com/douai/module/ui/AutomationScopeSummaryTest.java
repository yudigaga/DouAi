package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.view.View;
import android.widget.TextView;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.FollowListSnapshot;
import java.util.HashMap;
import java.util.Map;
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
public final class AutomationScopeSummaryTest {
    private Activity activity;
    private ModuleSettingsScreen screen;
    private MemoryStore store;
    private View home;

    @Before public void createScreen() {
        FollowListSnapshot.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeScreen() {
        if (screen != null) screen.close();
        ShadowLooper.idleMainLooper();
        FollowListSnapshot.reset();
        if (activity != null) activity.finish();
    }

    @Test public void aGroupMemberNicknameContainingASpaceCountsAsOneInBothSummaries() {
        assertGroupMemberCount("Alice Smith", 1);
    }

    @Test public void groupMemberSummariesPreserveTheExistingCaseSensitiveEntryCount() {
        assertGroupMemberCount("Alice,alice", 2);
    }

    private void assertGroupMemberCount(String configured, int expected) {
        store.putBoolean("reply_group_chats", true);
        store.putString("reply_group_mode", "selected");
        store.putString("reply_group_members", configured);
        View category = home.findViewWithTag("settings.category.AUTO_REPLY");
        assertNotNull(category);
        assertTrue(category.performClick());
        Dialog reply = ShadowDialog.getLatestDialog();
        View replyRoot = reply.getWindow().getDecorView();
        TextView outer = replyRoot.findViewWithTag("settings.value.群聊范围");
        assertNotNull(outer);
        assertTrue(outer.getText().toString().contains("指定成员 " + expected + " 人"));
        assertTrue(replyRoot.findViewWithTag("settings.option.群聊范围").performClick());
        View groupRoot = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        TextView inner = groupRoot.findViewWithTag("settings.value.选择成员");
        assertNotNull(inner);
        assertEquals("已选择 " + expected + " 人", inner.getText().toString());
        assertEquals(configured, store.getString("reply_group_members", ""));
    }

    private static final class MemoryStore implements SettingsStore {
        private final Map<String, Object> values = new HashMap<>();
        private FeatureSettings settings = FeatureSettings.defaults();
        @Override public FeatureSettings load() { return settings; }
        @Override public void save(FeatureSettings value) { settings = value; }
        @Override public boolean getBoolean(String key, boolean fallback) {
            return values.containsKey(key) ? (Boolean) values.get(key) : fallback;
        }
        @Override public String getString(String key, String fallback) {
            return values.containsKey(key) ? (String) values.get(key) : fallback;
        }
        @Override public void putBoolean(String key, boolean value) { values.put(key, value); }
        @Override public void putString(String key, String value) { values.put(key, value); }
        @Override public boolean isInHost() { return true; }
    }
}
