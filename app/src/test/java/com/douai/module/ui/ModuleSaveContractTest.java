package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.SettingsStore;
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
public final class ModuleSaveContractTest {
    private Activity activity;
    private MemoryStore store;
    private ModuleSettingsScreen screen;
    private View home;
    private View commentTools;

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        store = new MemoryStore();
        screen = new ModuleSettingsScreen(activity, store);
        home = screen.createView();
        activity.setContentView(home);
    }

    @After public void closeScreen() {
        screen.dismissChildPages();
        activity.finish();
    }

    @Test public void cancelButtonDiscardsTheMultiChoiceDraftWithoutWritingAnySelection() {
        AlertDialog choice = openCommentTools();
        toggle(choice, 0);
        assertFalse("Editing a multi-choice draft must not persist it", store.getBoolean("copy_comment_text", false));
        assertEquals(0, store.selectionWrites);
        assertNotNull("Multi-choice editors need an explicit Cancel action", choice.getButton(Dialog.BUTTON_NEGATIVE));
        choice.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(choice.isShowing());
        assertFalse(store.getBoolean("copy_comment_text", false));
        assertEquals(0, store.selectionWrites);
    }

    @Test public void backDiscardsTheMultiChoiceDraftAndReopeningShowsOnlySavedChoices() {
        AlertDialog choice = openCommentTools();
        toggle(choice, 1);
        choice.onBackPressed();
        ShadowLooper.idleMainLooper();
        assertFalse(choice.isShowing());
        assertFalse("Back must not behave as Save", store.getBoolean("comment_media_tools", false));
        assertEquals(0, store.selectionWrites);
        AlertDialog reopened = openCommentTools();
        assertFalse(reopened.getListView().isItemChecked(1));
    }

    @Test public void saveCommitsTheMultiChoiceDraftAndReopeningReflectsThoseSavedChoices() {
        store.values.put("comment_media_tools", true);
        store.values.put("copy_source_data", true);
        AlertDialog choice = openCommentTools();
        assertTrue(choice.getListView().isItemChecked(1));
        assertTrue(choice.getListView().isItemChecked(2));
        toggle(choice, 0);
        toggle(choice, 1);
        assertFalse(store.getBoolean("copy_comment_text", false));
        assertTrue(store.getBoolean("comment_media_tools", false));
        assertEquals(0, store.selectionWrites);
        assertEquals("保存", choice.getButton(Dialog.BUTTON_POSITIVE).getText().toString());
        choice.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(choice.isShowing());
        assertTrue(store.getBoolean("copy_comment_text", false));
        assertFalse(store.getBoolean("comment_media_tools", true));
        assertTrue(store.getBoolean("copy_source_data", false));
        AlertDialog reopened = openCommentTools();
        assertTrue(reopened.getListView().isItemChecked(0));
        assertFalse(reopened.getListView().isItemChecked(1));
        assertTrue(reopened.getListView().isItemChecked(2));
    }

    @Test public void paletteSelectionIsADraftAndCancelKeepsTheStoredRecallColor() {
        String original = "#20F6C344";
        store.values.put("recall_background", original);
        AlertDialog editor = openRecallColor();
        View color = described(editor.getWindow().getDecorView(), "#2066A3FF");
        assertNotNull(color);
        assertTrue(color.performClick());
        assertEquals("Choosing a color inside an editor must not commit it", original,
                store.getString("recall_background", ""));
        EditText input = colorInput(editor);
        assertEquals("#2066A3FF", input.getText().toString());
        editor.getButton(Dialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(original, store.getString("recall_background", ""));
        assertEquals(0, store.colorWrites);
    }

    @Test public void savingAPaletteSelectionUsesTheSelectedColorInsteadOfTheOldInput() {
        store.values.put("recall_background", "#2051BA55");
        AlertDialog editor = openRecallColor();
        assertTrue(described(editor.getWindow().getDecorView(), "#20F06A7D").performClick());
        EditText input = colorInput(editor);
        assertEquals("The palette must update the same draft that Save reads", "#20F06A7D", input.getText().toString());
        assertEquals(0, store.colorWrites);
        editor.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(editor.isShowing());
        assertEquals("#20F06A7D", store.getString("recall_background", ""));
        assertEquals(1, store.colorWrites);
    }

    @Test public void invalidRecallColorStaysEditableAndDoesNotWrite() {
        store.values.put("recall_background", "#2051BA55");
        AlertDialog editor = openRecallColor();
        EditText input = colorInput(editor);
        input.setText("not-a-color");
        editor.getButton(Dialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertTrue("Validation failure must keep the draft open", editor.isShowing());
        assertNotNull("Validation errors must remain beside the draft", input.getError());
        assertEquals("#2051BA55", store.getString("recall_background", ""));
        assertEquals(0, store.colorWrites);
    }

    private AlertDialog openRecallColor() {
        store.values.put("prevent_message_recall", true);
        assertTrue(home.findViewWithTag("settings.category.MESSAGE").performClick());
        View page = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        View color = page.findViewWithTag("settings.option.撤回的背景色");
        assertNotNull(color);
        assertTrue(color.performClick());
        return (AlertDialog) ShadowDialog.getLatestDialog();
    }

    private static EditText colorInput(AlertDialog editor) {
        View input = described(editor.getWindow().getDecorView(), "颜色值");
        assertTrue("The color draft must be a real input", input instanceof EditText);
        return (EditText) input;
    }

    private static View described(View root, String description) {
        if (description.contentEquals(root.getContentDescription() == null ? "" : root.getContentDescription())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) {
                View found = described(group.getChildAt(index), description);
                if (found != null) return found;
            }
        }
        return null;
    }

    private AlertDialog openCommentTools() {
        if (commentTools == null) {
            View category = home.findViewWithTag("settings.category.COMMENT");
            assertNotNull(category);
            assertTrue(category.performClick());
            commentTools = ShadowDialog.getLatestDialog().getWindow().getDecorView().findViewWithTag("settings.option.长按操作");
        }
        assertNotNull(commentTools);
        assertTrue(commentTools.performClick());
        AlertDialog choice = (AlertDialog) ShadowDialog.getLatestDialog();
        ShadowLooper.idleMainLooper();
        ListView list = choice.getListView();
        list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.AT_MOST));
        list.layout(0, 0, list.getMeasuredWidth(), list.getMeasuredHeight());
        ShadowLooper.idleMainLooper();
        return choice;
    }

    private static void toggle(AlertDialog choice, int index) {
        ListView list = choice.getListView();
        assertTrue(list.performItemClick(list.getAdapter().getView(index, null, list), index, index));
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new HashMap<>();
        FeatureSettings settings = FeatureSettings.defaults();
        int selectionWrites;
        int colorWrites;
        @Override public FeatureSettings load() { return settings; }
        @Override public void save(FeatureSettings value) { settings = value; }
        @Override public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        @Override public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        @Override public void putString(String key, String value) {
            if (key.equals("recall_background")) colorWrites++;
            values.put(key, value);
        }
        @Override public void putBoolean(String key, boolean value) {
            if (key.equals("copy_comment_text") || key.equals("comment_media_tools") || key.equals("copy_source_data")) selectionWrites++;
            values.put(key, value);
        }
        @Override public boolean isInHost() { return true; }
    }
}
