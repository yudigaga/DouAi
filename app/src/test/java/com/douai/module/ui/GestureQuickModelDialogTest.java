package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AnalyticsAiSettings;
import com.douai.module.config.HostSettingsStore;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowToast;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class GestureQuickModelDialogTest {
    @Test public void quickSwitchChoosesAFeatureBeforeShowingItsSavedProfiles() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostSettingsStore store = new HostSettingsStore(activity);
        AiModelConfig first = profile("first"), second = profile("second");
        new AiModelSettings(Arrays.asList(first, second), first.id).save(store);
        AlertDialog dialog = GestureQuickModelDialog.create(activity, store, () -> { });
        try {
            dialog.show();
            assertEquals("The first step must choose the feature, independently of profile count", 4,
                    dialog.getListView().getCount());
            assertEquals("全局默认", dialog.getListView().getAdapter().getItem(0));
            assertEquals("自动回复（私聊、群聊、评论回复）", dialog.getListView().getAdapter().getItem(1));
            assertEquals("自动评论", dialog.getListView().getAdapter().getItem(2));
            assertEquals("本地数据分析", dialog.getListView().getAdapter().getItem(3));
            assertEquals(first.id, AiModelSettings.read(store).activeId);
        } finally {
            dialog.dismiss();
            activity.finish();
        }
    }

    @Test public void eachFeatureChangesOnlyItsOwnSelection() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.putString(AiModelSettings.REPLY_MODEL_ID, "first");
            f.store.putString(AiModelSettings.COMMENT_MODEL_ID, "second");
            f.store.putString(AnalyticsAiSettings.MODEL_ID, "second");
            String catalog = f.store.getString(AiModelSettings.KEY, "");
            f.openTarget(1); f.choose(2);
            assertEquals("second", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals("second", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("second", f.store.getString(AnalyticsAiSettings.MODEL_ID, ""));
            f.openTarget(2); f.choose(3);
            assertEquals("third", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("second", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            f.openTarget(3); f.choose(1);
            assertEquals("first", f.store.getString(AnalyticsAiSettings.MODEL_ID, ""));
            assertEquals("second", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals("third", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("first", AiModelSettings.read(f.store).activeId);
            assertEquals(catalog, f.store.getString(AiModelSettings.KEY, ""));
        }
    }

    @Test public void eachFeatureCanFollowGlobalWithoutChangingTheOtherSelections() throws Exception {
        try (Fixture f = new Fixture()) {
            String[] keys = {AiModelSettings.REPLY_MODEL_ID, AiModelSettings.COMMENT_MODEL_ID, AnalyticsAiSettings.MODEL_ID};
            for (String key : keys) f.store.putString(key, "second");
            String catalog = f.store.getString(AiModelSettings.KEY, "");
            for (int target = 0; target < keys.length; target++) {
                String[] before = new String[keys.length];
                for (int i = 0; i < keys.length; i++) before[i] = f.store.getString(keys[i], "");
                f.openTarget(target + 1);
                assertEquals("跟随全局默认", f.dialog.getListView().getAdapter().getItem(0));
                f.choose(0);
                for (int i = 0; i < keys.length; i++) assertEquals(i == target ? "" : before[i],
                        f.store.getString(keys[i], ""));
                assertEquals("first", AiModelSettings.read(f.store).activeId);
                assertEquals(catalog, f.store.getString(AiModelSettings.KEY, ""));
            }
        }
    }

    @Test public void independentProfilesCanBeChosenWithoutAGlobalDefault() throws Exception {
        try (Fixture f = new Fixture()) {
            new AiModelSettings(AiModelSettings.read(f.store).configs, "").save(f.store);
            String[] keys = {AiModelSettings.REPLY_MODEL_ID, AiModelSettings.COMMENT_MODEL_ID, AnalyticsAiSettings.MODEL_ID};
            for (int i = 0; i < keys.length; i++) {
                f.openTarget(i + 1); f.choose(2);
                assertEquals("second", f.store.getString(keys[i], ""));
                assertEquals("", AiModelSettings.read(f.store).activeId);
            }
            f.openTarget(2); f.choose(0);
            assertEquals("", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, "missing"));
            assertEquals("", AiModelSettings.read(f.store).activeId);
        }
    }

    @Test public void selectingGlobalOnlyChangesTheDefault() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.putString(AiModelSettings.REPLY_MODEL_ID, "first");
            f.store.putString(AiModelSettings.COMMENT_MODEL_ID, "third");
            f.store.putString(AnalyticsAiSettings.MODEL_ID, "second");
            f.openTarget(0);
            assertEquals(3, f.dialog.getListView().getCount());
            f.choose(1);
            assertEquals("second", AiModelSettings.read(f.store).activeId);
            assertEquals("first", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals("third", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("second", f.store.getString(AnalyticsAiSettings.MODEL_ID, ""));
            assertEquals(3, AiModelSettings.read(f.store).configs.size());
        }
    }

    @Test public void modelBackReturnsToTheFeatureStepInTheSameDialog() throws Exception {
        try (Fixture f = new Fixture()) {
            f.openTarget(1);
            AlertDialog original = f.dialog;
            assertEquals("返回", f.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
            f.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            ShadowLooper.idleMainLooper();
            assertSame(original, f.dialog);
            assertTrue(f.dialog.isShowing());
            assertEquals(4, f.dialog.getListView().getCount());
            assertEquals("全局默认", f.dialog.getListView().getAdapter().getItem(0));
            f.choose(3);
            assertEquals("跟随全局默认", f.dialog.getListView().getAdapter().getItem(0));
            f.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            f.dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            assertFalse(f.dialog.isShowing());
            assertEquals("first", AiModelSettings.read(f.store).activeId);
        }
    }

    @Test public void deletingAListedProfileRejectsTheStaleChoiceAndRefreshesWithoutFallback() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.putString(AiModelSettings.REPLY_MODEL_ID, "first");
            f.store.putString(AiModelSettings.COMMENT_MODEL_ID, "second");
            f.store.putString(AnalyticsAiSettings.MODEL_ID, "first");
            f.openTarget(1);
            AiModelSettings.read(f.store).delete("second").save(f.store);
            String deletedCatalog = f.store.getString(AiModelSettings.KEY, "");
            f.choose(2);
            assertTrue(f.dialog.isShowing());
            assertEquals(3, f.dialog.getListView().getCount());
            assertTrue(ShadowToast.getTextOfLatestToast().contains("不存在"));
            assertEquals("first", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals("second", f.store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("first", f.store.getString(AnalyticsAiSettings.MODEL_ID, ""));
            assertEquals("first", AiModelSettings.read(f.store).activeId);
            assertEquals(deletedCatalog, f.store.getString(AiModelSettings.KEY, ""));
        }
    }

    @Test public void aMissingSelectionIsShownAsUnselectedUntilTheUserChooses() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.putString(AiModelSettings.REPLY_MODEL_ID, "deleted");
            f.openTarget(1);
            assertEquals(-1, f.dialog.getListView().getCheckedItemPosition());
            assertEquals("deleted", f.store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals("first", AiModelSettings.read(f.store).activeId);
        }
    }

    @Test public void aDismissedPickerCannotApplyALateProfileChoice() throws Exception {
        try (Fixture f = new Fixture()) {
            f.store.putString(AnalyticsAiSettings.MODEL_ID, "first");
            f.openTarget(3);
            String catalog = f.store.getString(AiModelSettings.KEY, "");
            f.dialog.dismiss();
            f.choose(2);
            assertEquals("first", f.store.getString(AnalyticsAiSettings.MODEL_ID, ""));
            assertEquals(catalog, f.store.getString(AiModelSettings.KEY, ""));
        }
    }

    @Test public void anEmptyCatalogCanFollowGlobalAndStillOpenModelManagement() throws Exception {
        try (Fixture f = new Fixture()) {
            new AiModelSettings(Collections.emptyList(), "").save(f.store);
            f.openTarget(1);
            assertEquals(1, f.dialog.getListView().getCount());
            f.choose(0);
            assertEquals("", f.store.getString(AiModelSettings.REPLY_MODEL_ID, "missing"));
            assertEquals("", AiModelSettings.read(f.store).activeId);
            f.openTarget(0);
            assertEquals(0, f.dialog.getListView().getCount());
            f.dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals(1, f.managementOpens);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        final HostSettingsStore store = new HostSettingsStore(activity);
        AlertDialog dialog;
        int managementOpens;
        Fixture() throws Exception {
            new AiModelSettings(Arrays.asList(profile("first"), profile("second"), profile("third")), "first").save(store);
        }
        void openTarget(int target) {
            if (dialog != null) dialog.dismiss();
            dialog = GestureQuickModelDialog.create(activity, store, () -> managementOpens++);
            dialog.show();
            ShadowLooper.idleMainLooper();
            choose(target);
        }
        void choose(int position) {
            dialog.getListView().performItemClick(null, position, position);
            ShadowLooper.idleMainLooper();
        }
        @Override public void close() {
            if (dialog != null) dialog.dismiss();
            activity.finish();
            ShadowLooper.idleMainLooper();
        }
    }

    private static AiModelConfig profile(String id) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id)
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "model"));
    }
}
