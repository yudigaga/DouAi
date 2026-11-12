package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module",
        qualifiers = "w360dp-h720dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class NativeThemeSettingsTest {
    private ActivityController<LightHostActivity> controller;
    private Activity activity;
    private ModuleSettingsScreen screen;
    private Dialog editor;
    private ConfigTransferDialog backup;

    @Before public void setup() {
        RuntimeEnvironment.setQualifiers("+night");
        controller = Robolectric.buildActivity(LightHostActivity.class);
        activity = controller.get();
        activity.setTheme(android.R.style.Theme_Material_Light_NoActionBar);
        controller.setup();
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
    }

    @After public void close() {
        if (screen != null) screen.dismissChildPages();
        if (editor != null) editor.dismiss();
        if (backup != null) { backup.close(); backup.dialog().dismiss(); }
        controller.pause().stop().destroy();
        RuntimeEnvironment.setQualifiers("+notnight");
        ShadowLooper.idleMainLooper();
    }

    @Test public void nativeSettingsAndComponentPageFollowSystemDespiteHostLightTheme() throws Exception {
        screen = new ModuleSettingsScreen(activity, new MemoryStore());
        View home = screen.createView();
        activity.setContentView(home);
        layout(home);
        int canvas = ((ColorDrawable) home.getBackground()).getColor();
        assertTrue("Dark system must produce a dark settings canvas", Color.luminance(canvas) < 0.2f);
        assertReadable(findText(home, "DouAi"));
        screenshot(home, "native-home-dark.png");

        home.findViewWithTag("settings.category.COMPONENT_HIDE").performClick();
        Dialog component = ShadowDialog.getLatestDialog();
        View decor = component.getWindow().getDecorView();
        layout(decor);
        assertReadable(findText(decor, "隐藏总开关"));
        screenshot(decor, "native-components-dark.png");
        assertEquals("Only module windows should change their theme", Configuration.UI_MODE_NIGHT_NO,
                activity.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);
    }

    @Test public void configuredWhiteColorPreviewIsPreservedInDarkMessageSettings() {
        MemoryStore store = new MemoryStore();
        store.putString("recall_background", "#FFFFFFFF");
        screen = new ModuleSettingsScreen(activity, store);
        View home = screen.createView();
        activity.setContentView(home);
        home.findViewWithTag("settings.category.MESSAGE").performClick();
        View decor = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        layout(decor);
        LinearLayout row = decor.findViewWithTag("settings.option.撤回的背景色");
        LinearLayout labels = (LinearLayout) row.getChildAt(0);
        View swatch = labels.getChildAt(labels.getChildCount() - 1);
        assertEquals(Color.WHITE, ((GradientDrawable) swatch.getBackground()).getColor().getDefaultColor());
        assertReadable(findText(row, "撤回的背景色"));
        assertEquals("#FFFFFFFF", store.getString("recall_background", ""));
    }

    @Test public void nativePromptAndBackupFormsKeepInputsAndSecretDefaultWhileDark() throws Exception {
        editor = new PromptEditorDialog(activity, "提示词", "保留正在编辑的内容", ignored -> { }, false);
        editor.show();
        View prompt = editor.getWindow().getDecorView();
        layout(prompt);
        EditText input = prompt.findViewWithTag("settings.input.提示词");
        assertReadable(input);
        assertEquals("保留正在编辑的内容", input.getText().toString());
        View promptRoot = prompt.findViewWithTag("prompt.editor");
        assertTrue(Color.luminance(((ColorDrawable) promptRoot.getBackground()).getColor()) < 0.2f);
        screenshot(prompt, "native-prompt-dark.png");
        editor.dismiss();

        backup = new ConfigTransferDialog(activity, new MemoryStore(), false, () -> { });
        backup.dialog().show();
        View form = backup.dialog().getWindow().getDecorView();
        layout(form);
        CheckBox secrets = form.findViewWithTag("config.backup.secrets");
        CheckBox replace = form.findViewWithTag("config.backup.replace");
        assertFalse(secrets.isChecked());
        assertFalse(replace.isChecked());
        assertReadable(secrets);
        assertReadable(form.findViewWithTag("config.backup.status"));
        screenshot(form, "native-backup-dark.png");
    }

    private static void assertReadable(TextView text) {
        assertNotNull(text);
        assertTrue("Dark surfaces need light foreground text", Color.luminance(text.getCurrentTextColor()) > 0.35f);
    }

    private static TextView findText(View root, String value) {
        if (root instanceof TextView && value.contentEquals(((TextView) root).getText())) return (TextView) root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                TextView found = findText(group.getChildAt(index), value);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void layout(View view) {
        view.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, 360, 720);
        view.getViewTreeObserver().dispatchOnGlobalLayout();
    }

    private static void screenshot(View view, String name) throws Exception {
        String location = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (location == null) return;
        File folder = new File(location);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        try {
            view.draw(new Canvas(bitmap));
            try (FileOutputStream output = new FileOutputStream(new File(folder, name))) {
                assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            }
        } finally { bitmap.recycle(); }
    }

    private static final class MemoryStore implements SettingsStore {
        private final Map<String, Object> values = new HashMap<>();
        private FeatureSettings settings = FeatureSettings.defaults();
        public FeatureSettings load() { return settings; }
        public void save(FeatureSettings value) { settings = value; }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public boolean isInHost() { return true; }
    }

    public static final class LightHostActivity extends Activity {
        @Override protected void attachBaseContext(Context base) {
            super.attachBaseContext(base);
            Configuration light = new Configuration();
            light.uiMode = Configuration.UI_MODE_NIGHT_NO;
            applyOverrideConfiguration(light);
        }
    }
}
