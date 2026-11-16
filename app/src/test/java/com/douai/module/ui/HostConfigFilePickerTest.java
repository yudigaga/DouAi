package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.ScrollView;
import androidx.activity.result.ActivityResultRegistry;
import androidx.activity.result.contract.ActivityResultContract;
import androidx.core.app.ActivityOptionsCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class HostConfigFilePickerTest {
    @Test public void createsJsonAndOpensBackupDocumentsWithoutOptionalHostContracts() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        ClassLoader stripped = new ClassLoader(activity.getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (name.endsWith("$OpenDocument") || name.endsWith("$CreateDocument")) throw new ClassNotFoundException(name);
                return super.loadClass(name, resolve);
            }
        };
        try {
            HostConfigFilePicker picker = HostConfigFilePicker.launch(activity, true, "backup.json", selected::set, stripped);
            assertNotNull(picker);
            assertTrue(HostConfigFilePicker.isPending(activity));
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, activity.registry.intent.getAction());
            assertEquals("application/json", activity.registry.intent.getType());
            assertEquals("backup.json", activity.registry.intent.getStringExtra(Intent.EXTRA_TITLE));
            Uri uri = Uri.parse("content://backup/new.json");
            activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK, new Intent().setData(uri));
            assertEquals(uri, selected.get());
            assertFalse(HostConfigFilePicker.isPending(activity));
            picker = HostConfigFilePicker.launch(activity, false, "", selected::set, stripped);
            assertNotNull(picker);
            assertEquals(Intent.ACTION_OPEN_DOCUMENT, activity.registry.intent.getAction());
            assertTrue(activity.registry.intent.hasCategory(Intent.CATEGORY_OPENABLE));
            activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_CANCELED, null);
            assertNull(selected.get());
            assertFalse(HostConfigFilePicker.isPending(activity));
        } finally { activity.finish(); }
    }

    @Test public void closureRejectsLateResultsAndFileUris() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        try {
            HostConfigFilePicker picker = HostConfigFilePicker.launch(activity, false, "", selected::set);
            assertNotNull(picker); picker.close();
            activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK,
                    new Intent().setData(Uri.parse("content://backup/late.json")));
            assertNull(selected.get());
            picker = HostConfigFilePicker.launch(activity, false, "", selected::set);
            assertNotNull(picker);
            activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK,
                    new Intent().setData(Uri.parse("file:///private.json")));
            assertNull(selected.get());
            assertFalse(HostConfigFilePicker.isPending(activity));
        } finally { activity.finish(); }
    }

    @Test @Config(sdk = 28) public void reportPdfSelectsOneFileThroughTheSameHostOwnedRegistry() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected = new AtomicReference<>();
        try {
            HostConfigFilePicker picker = HostConfigFilePicker.launch(activity, ReportImageShare.destination(ReportImageExport.Format.PDF), selected::set);
            assertNotNull(picker); assertTrue(HostConfigFilePicker.isPending(activity));
            assertEquals(Intent.ACTION_CREATE_DOCUMENT, activity.registry.intent.getAction());
            assertEquals("application/pdf", activity.registry.intent.getType());
            assertNotEquals(0, activity.registry.intent.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION);
            assertNotEquals(0, activity.registry.intent.getFlags() & Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
            Uri tree = Uri.parse("content://report.docs/tree/report-root");
            activity.registry.dispatchResult(activity.registry.code, Activity.RESULT_OK, new Intent().setData(tree));
            assertEquals(tree, selected.get()); assertFalse(HostConfigFilePicker.isPending(activity));
        } finally { activity.finish(); }
    }

    @Test public void settingsRemainAliveWhileSystemDocumentPickerIsOpen() {
        PickerActivity activity = Robolectric.buildActivity(PickerActivity.class).setup().get();
        HostUiController controller = new HostUiController(RuntimeEnvironment.getApplication(), ignored -> { });
        HostConfigFilePicker picker = null;
        try {
            controller.setSettingsPageTargets(PickerActivity.class, ScrollView.class);
            controller.onActivityResumed(activity); ShadowLooper.idleMainLooper();
            android.view.View entry = activity.findViewById(android.R.id.content).findViewWithTag("DouAi.HostSettingsEntry");
            assertNotNull("The host fixture must expose the uniquely identified vertical settings list", entry);
            entry.performClick();
            Dialog settings = ShadowDialog.getLatestDialog();
            picker = HostConfigFilePicker.launch(activity, false, "", ignored -> { });
            assertNotNull(picker);
            controller.onActivityPaused(activity);
            assertTrue(settings.isShowing());
            picker.close();
            controller.onActivityResumed(activity); controller.onActivityPaused(activity);
            assertFalse(settings.isShowing());
        } finally { if (picker != null) picker.close(); controller.close(); activity.finish(); }
    }

    @Test public void abandonedSubtitlePickerDeliversLateUriOnlyToCleanup() {
        PickerActivity activity=Robolectric.buildActivity(PickerActivity.class).setup().get();
        AtomicReference<Uri> selected=new AtomicReference<>(),cleaned=new AtomicReference<>();
        HostConfigFilePicker picker=HostConfigFilePicker.launch(activity,new Intent(Intent.ACTION_CREATE_DOCUMENT).setType("text/plain"),selected::set);
        assertNotNull(picker);picker.abandon(cleaned::set);
        Uri late=Uri.parse("content://backup/abandoned.txt");
        activity.registry.dispatchResult(activity.registry.code,Activity.RESULT_OK,new Intent().setData(late));
        assertNull(selected.get());assertEquals(late,cleaned.get());assertFalse(HostConfigFilePicker.isPending(activity));activity.finish();
    }

    public static final class PickerActivity extends Activity {
        final Registry registry = new Registry();
        public ActivityResultRegistry getActivityResultRegistry() { return registry; }
        @Override protected void onCreate(android.os.Bundle state) {
            super.onCreate(state);
            ScrollView scroll = new ScrollView(this);
            android.widget.LinearLayout content = new android.widget.LinearLayout(this);
            content.setOrientation(android.widget.LinearLayout.VERTICAL);
            scroll.addView(content); setContentView(scroll);
        }
    }
    private static final class Registry extends ActivityResultRegistry {
        int code; Intent intent;
        @Override public <I, O> void onLaunch(int code, ActivityResultContract<I, O> contract, I input, ActivityOptionsCompat options) {
            this.code = code; intent = contract.createIntent(RuntimeEnvironment.getApplication(), input);
        }
    }
}
