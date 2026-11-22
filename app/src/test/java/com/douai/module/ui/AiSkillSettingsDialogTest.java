package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.config.HostSettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiSkillSettingsDialogTest {
    private Activity activity;
    private AiSkillSettingsDialog manager;
    private SkillStore store;
    private SkillServices services;

    @Before public void openManager() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = SkillStore.forContext(activity);
        services = SkillServices.forContext(activity);
        manager = new AiSkillSettingsDialog(activity, new HostSettingsStore(activity), false,
                this::present, new ImmediateExecutor());
        manager.dialog().show();
    }

    @After public void closeManager() {
        if (manager != null) {
            manager.close();
            manager.dialog().dismiss();
        }
        if (activity != null) activity.finish();
    }

    @Test public void oneWordImageRequirementStaysPendingUntilExplicitConnectAfterAuthorization() throws Exception {
        enterAndGenerate("生图", "skills.generate.model");
        assertEquals(1, store.pending().size());
        assertEquals(SkillResult.Type.IMAGE, store.pending().get(0).outputType());
        assertTrue(store.list().isEmpty());
        String name = store.pending().get(0).name();
        TextView reason = view("skills.pending." + name);
        assertTrue(reason.getText().toString().contains(store.pending().get(0).reason()));

        assertTrue(view("skills.service." + SkillServices.MOCK_IMAGE + ".authorize").performClick());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(((TextView) review.findViewById(android.R.id.message)).getText().toString().contains("本地模拟"));
        confirm(review);
        assertTrue(store.list().isEmpty());

        assertTrue(view("skills.pending." + name + ".retry").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.pending().isEmpty());
        assertEquals(name, store.list().get(0).name());
        assertTrue(((TextView) view("skills.status")).getText().toString()
                .contains(services.find(SkillServices.MOCK_IMAGE).label()));
    }

    @Test public void choosingImageServiceRegistersWithoutGrantingAuthorization() throws Exception {
        assertTrue(view("skills.service.register").performClick());
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = editor.getWindow().getDecorView();
        ((Spinner) form.findViewWithTag("skills.service.output")).setSelection(1);
        ((EditText) form.findViewWithTag("skills.service.id")).setText("fixture-image");
        ((EditText) form.findViewWithTag("skills.service.label")).setText("示例图片服务");
        ((EditText) form.findViewWithTag("skills.service.endpoint")).setText("https://images.example.test/generate");
        ((EditText) form.findViewWithTag("skills.service.cost")).setText("2");
        confirm(editor);

        SkillServices.Service service = services.find("fixture-image");
        assertEquals(SkillResult.Type.IMAGE, service.outputType());
        assertFalse(services.isAuthorized(service));
        assertTrue(((TextView) view("skills.service.fixture-image")).getText().toString().contains("图片"));
    }

    @Test public void pendingListOpensTheExactSkillConfiguration() {
        enterAndGenerate("生图", "skills.generate.mock");
        String name = store.pending().get(0).name();
        assertTrue(view("skills.pending." + name + ".configure").performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView scopedName = detail.getWindow().getDecorView().findViewWithTag("skills.detail.name");
        assertNotNull(scopedName);
        assertEquals("/" + name, scopedName.getText().toString());
        assertNull(detail.getWindow().getDecorView().findViewWithTag("skills.service.register"));
        assertTrue(store.list().isEmpty());
        detail.dismiss();
    }

    @Test public void configurationMasksKeyAndRequiresAuthorizationToConnectMock() {
        enterAndGenerate("生图", "skills.generate.mock");
        String name = store.pending().get(0).name();
        assertTrue(view("skills.pending." + name + ".configure").performClick());
        AlertDialog detail = (AlertDialog) ShadowDialog.getLatestDialog();
        View form = detail.getWindow().getDecorView();
        EditText key = form.findViewWithTag("skills.detail.key");
        assertTrue(key.getTransformationMethod() instanceof PasswordTransformationMethod);
        assertFalse(key.isSaveEnabled());
        assertNull(form.findViewWithTag("skills.detail.authorize"));

        ((Spinner) form.findViewWithTag("skills.detail.adapter")).setSelection(1);
        assertTrue(form.findViewWithTag("skills.detail.save").performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.list().isEmpty());
        assertTrue(form.findViewWithTag("skills.detail.authorize").performClick());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertTrue(store.list().isEmpty());

        assertTrue(form.findViewWithTag("skills.detail.authorize").performClick());
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals(name, store.list().get(0).name());
        assertTrue(store.pending().isEmpty());
        detail.dismiss();
    }

    @Test public void regeneratingTheSameSkillRequiresConfirmationBeforeReplacingVersion() {
        enterAndGenerate("提取摘要", "skills.generate.mock");
        assertEquals(1, store.list().get(0).version());

        enterAndGenerate("提取摘要", "skills.generate.mock");
        assertEquals(1, store.list().get(0).version());
        AlertDialog review = (AlertDialog) ShadowDialog.getLatestDialog();
        TextView message = review.findViewById(android.R.id.message);
        assertNotNull(message);
        assertTrue(message.getText().toString().contains("v1"));
        assertTrue(review.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());
        ShadowLooper.idleMainLooper();
        assertEquals(1, store.list().get(0).version());

        enterAndGenerate("提取摘要", "skills.generate.mock");
        confirm((AlertDialog) ShadowDialog.getLatestDialog());
        assertEquals(2, store.list().get(0).version());
    }

    private void enterAndGenerate(String requirements, String tag) {
        ((EditText) view("skills.requirements")).setText(requirements);
        assertTrue(view(tag).performClick());
        ShadowLooper.idleMainLooper();
    }

    private <T extends View> T view(String tag) {
        T view = manager.dialog().getWindow().getDecorView().findViewWithTag(tag);
        assertNotNull(tag, view);
        return view;
    }

    private void present(Dialog dialog, Runnable cleanup) {
        if (cleanup != null) dialog.setOnDismissListener(ignored -> cleanup.run());
        dialog.show();
    }

    private static void confirm(AlertDialog dialog) {
        assertTrue(dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick());
        ShadowLooper.idleMainLooper();
    }

    private static final class ImmediateExecutor extends AbstractExecutorService {
        private boolean closed;

        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { shutdown(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return closed; }
        @Override public void execute(Runnable task) { task.run(); }
    }
}
