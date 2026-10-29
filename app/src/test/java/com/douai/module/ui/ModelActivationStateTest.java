package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;

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
import org.robolectric.shadows.ShadowDialog;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class ModelActivationStateTest {
    @Test public void managementOffersEditingAndSavingWithoutActivationControls() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostSettingsStore store = new HostSettingsStore(activity);
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        try {
            AiModelConfig first = profile("first"), second = profile("second");
            new AiModelSettings(Arrays.asList(first, second), first.id).save(store);
            screen.createView();
            Method open = ModuleSettingsScreen.class.getDeclaredMethod("showAiModelSettings");
            open.setAccessible(true);
            open.invoke(screen);
            Dialog dialog = ShadowDialog.getLatestDialog();
            View manager = dialog.getWindow().getDecorView();
            assertNull(manager.findViewWithTag("ai.activate.first"));
            assertNull(manager.findViewWithTag("ai.activate.second"));
            assertTrue(manager.findViewWithTag("ai.edit.second").performClick());
            AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
            assertEquals("保存", editor.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
            editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertEquals("first", AiModelSettings.read(store).activeId);
        } finally {
            screen.close();
            activity.finish();
        }
    }

    @Test public void deletingAnActiveProfileDoesNotRewriteFeatureSelections() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        HostSettingsStore store = new HostSettingsStore(activity);
        AiModelConfig first = profile("first"), second = profile("second");
        new AiModelSettings(Arrays.asList(first, second), first.id).save(store);
        store.putString(AiModelSettings.REPLY_MODEL_ID, first.id);
        store.putString(AiModelSettings.COMMENT_MODEL_ID, second.id);
        store.putString(AnalyticsAiSettings.MODEL_ID, first.id);
        ModuleSettingsScreen screen = new ModuleSettingsScreen(activity, store);
        try {
            screen.createView();
            Method open = ModuleSettingsScreen.class.getDeclaredMethod("showAiModelSettings");
            open.setAccessible(true);
            open.invoke(screen);
            View manager = ShadowDialog.getLatestDialog().getWindow().getDecorView();
            assertTrue(manager.findViewWithTag("ai.edit.first").performClick());
            AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
            editor.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
            assertEquals("", AiModelSettings.read(store).activeId);
            assertEquals(1, AiModelSettings.read(store).configs.size());
            assertEquals(first.id, store.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals(second.id, store.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals(first.id, store.getString(AnalyticsAiSettings.MODEL_ID, ""));
        } finally {
            screen.close();
            activity.finish();
        }
    }

    private static AiModelConfig profile(String id) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", id)
                .put("apiEndpoint", "https://example.test/v1").put("modelName", "model"));
    }
}
