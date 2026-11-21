package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Spinner;

import com.douai.module.ai.skills.AiSubAgents;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;

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

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AiSubAgentSettingsDialogTest {
    private Activity activity;
    private HostSettingsStore store;
    private ModuleSettingsScreen screen;
    private View home;

    @Before public void openHostSettings() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new HostSettingsStore(activity);
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeHostSettings() {
        screen.close();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void savingAndReopeningUsesTheModelIdAndExplicitToolPermissions() throws Exception {
        AiModelConfig first = model("first"), second = model("second");
        AiModelSettings original = new AiModelSettings(List.of(first, second), first.id);
        original.save(store);
        AlertDialog dialog = openSubAgents();
        View form = dialog.getWindow().getDecorView();
        Spinner select = form.findViewWithTag("subagent.model");
        CompoundButton summary = form.findViewWithTag("subagent.summary");
        CompoundButton mockOrder = form.findViewWithTag("subagent.mockOrder");
        assertEquals(0, select.getSelectedItemPosition());
        assertFalse(summary.isChecked());
        assertFalse(mockOrder.isChecked());
        select.setSelection(2);
        summary.setChecked(true);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        assertEquals(new AiSubAgents.Selection("second", true, false), AiSubAgents.read(store));
        assertEquals(original.serialize(), AiModelSettings.read(store).serialize());

        new AiModelSettings(List.of(second, first), first.id).save(store);
        screen.dismissChildPages();
        dialog = openSubAgents();
        form = dialog.getWindow().getDecorView();
        select = form.findViewWithTag("subagent.model");
        assertEquals(1, select.getSelectedItemPosition());
        assertTrue(((CompoundButton) form.findViewWithTag("subagent.summary")).isChecked());
        select.setSelection(0);
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        assertFalse(AiSubAgents.read(store).enabled());
    }

    @Test public void configuredDelegationAppearsInWorkChatAndStaysOutOfModelTesting() throws Exception {
        AiModelConfig profile = model("delegate-model");
        new AiModelSettings(List.of(profile), profile.id).save(store);
        screen.openSidebarEntry("ai_chat");
        assertNull(delegationCommandInLatestChat());

        AiSubAgents.save(store, new AiSubAgents.Selection(profile.id, false, false));
        screen.openSidebarEntry("ai_chat");
        assertNotNull(delegationCommandInLatestChat());

        screen.openSidebarEntry("ai_models");
        View models = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(models.findViewWithTag("settings.option.测试当前配置").performClick());
        assertNull(delegationCommandInLatestChat());
    }

    private AlertDialog openSubAgents() {
        assertTrue(home.findViewWithTag("settings.category.EXTENSION").performClick());
        View extensions = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        assertTrue(extensions.findViewWithTag("settings.option.子代理").performClick());
        ShadowLooper.idleMainLooper();
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static View delegationCommandInLatestChat() {
        View chat = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        EditText composer = chat.findViewWithTag("ai.chat.input");
        assertNotNull(composer);
        composer.setText("/委");
        return chat.findViewWithTag("ai.chat.skill.委派");
    }

    private static AiModelConfig model(String id) throws Exception {
        return AiModelConfig.fromJson(new JSONObject().put("id", id).put("name", "模型 " + id)
                .put("modelName", "assistant").put("apiEndpoint", "https://example.test/v1"));
    }
}
