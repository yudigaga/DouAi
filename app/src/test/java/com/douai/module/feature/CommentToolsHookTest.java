package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.widget.TextView;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.ui.ModuleTheme;
import com.google.gson.annotations.SerializedName;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowAlertDialog;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35)
public final class CommentToolsHookTest {
    @Test public void firstLongPressPanelOffersOneClickableEntryWithoutTakingNativeActions() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        android.widget.LinearLayout a = new android.widget.LinearLayout(activity);
        android.widget.LinearLayout b = new android.widget.LinearLayout(activity);
        a.setOrientation(android.widget.LinearLayout.VERTICAL);
        b.setOrientation(android.widget.LinearLayout.VERTICAL);
        android.widget.FrameLayout groupRoot=new android.widget.FrameLayout(activity);
        groupRoot.addView(a);
        // The host's blockView is a separate FrameLayout; the action group lives under getView().
        groupRoot.addView(new android.widget.FrameLayout(activity));
        assertNull(CommentToolsHook.actionList(groupRoot));
        a.setId(0x7f0ad256);
        assertSame(a,CommentToolsHook.actionList(groupRoot));
        android.view.View nativeRow = new android.view.View(activity);
        int[] calls={0,0};
        nativeRow.setOnClickListener(v->calls[0]++);
        nativeRow.setOnLongClickListener(v->{calls[1]++;return true;});
        a.addView(nativeRow);
        try (CommentToolsHook hook = hook(activity,new ArrayList<>())) {
            Object panel=new Object(); Comment value=new Comment();
            hook.bindToolsEntry(a, panel, value);
            hook.bindToolsEntry(b, panel, value);
            hook.bindToolsEntry(a, panel, value);
            assertEquals(2,a.getChildCount()); assertEquals(0,b.getChildCount());
            assertEquals("DouAi 评论工具",((TextView)a.getChildAt(0)).getText().toString());
            assertSame(nativeRow,a.getChildAt(1));
            assertTrue(nativeRow.performClick()); assertTrue(nativeRow.performLongClick());
            assertArrayEquals(new int[]{1,1},calls);
            assertTrue(a.getChildAt(0).performClick());
            AlertDialog tools=ShadowAlertDialog.getLatestAlertDialog();
            assertNotNull(tools.getWindow().getDecorView().findViewWithTag("miuix.dialog.root"));
            assertEquals("查看评论数据",tools.getListView().getItemAtPosition(0));tools.dismiss();
            android.view.View obsolete=a.getChildAt(0);
            hook.bindToolsEntry(a,new Object(),new Comment());
            assertEquals(2,a.getChildCount());assertSame(nativeRow,a.getChildAt(1));
            assertNotSame(obsolete,a.getChildAt(0));assertFalse(obsolete.hasOnClickListeners());
            activity.getPreferences(0).edit().clear().commit();
            hook.setSettings(ExtendedSettings.readFrom(activity.getPreferences(0)));
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(1,a.getChildCount()); assertSame(nativeRow,a.getChildAt(0));
        } finally {activity.finish();}
    }
    @Test public void transcriptReadsOnlyNonblankAnnotatedStringAndPreservesCopy() {
        Comment value = new Comment();
        value.audio.transcript = " 转写原文 ";
        assertEquals(" 转写原文 ", CommentTranscript.read(value));
        value.audio.transcript = " \t\n　";
        assertNull(CommentTranscript.read(value));
        assertNull(CommentTranscript.read(new Object() {
            @SerializedName("comment_audio") Object audio = new Object() {
                @SerializedName("asr_text") Integer wrong = 1;
                @SerializedName("timbre_text") String other = "不是转写";
            };
        }));
        value.audio = null;
        assertNull(CommentTranscript.read(value));
    }

    @Test public void viewingTranscriptKeepsClipboardUntilUserCopies() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        try (CommentToolsHook hook = hook(activity, new ArrayList<>())) {
            hook.setReadingOptions(true, false);
            assertTrue(menu(hook, activity));
            AlertDialog tools = ShadowAlertDialog.getLatestAlertDialog();
            int index = -1;
            for (int i=0; i<tools.getListView().getCount(); i++)
                if ("查看语音转写".equals(tools.getListView().getItemAtPosition(i))) index=i;
            assertTrue(index>=0);
            tools.getListView().performItemClick(null,index,index);
            AlertDialog viewer = ShadowAlertDialog.getLatestAlertDialog();
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertFalse(clipboard.hasPrimaryClip());
            viewer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(" 转写原文 ",clipboard.getPrimaryClip().getItemAt(0).getText().toString());
        } finally { activity.finish(); }
    }
    @Test public void summaryKeepsCommentStateSeparateFromEmbeddedPlaybackResponse() throws Exception {
        String source = source();
        String summary = CommentToolsHook.sourceAudioSummary(source);
        assertTrue(summary.contains("评论类型 / 状态：8 / 1"));
        assertTrue(summary.contains("音频：有  ·  VID：有"));
        assertTrue(summary.contains("时长：2000 ms  ·  波形：空"));
        assertTrue(summary.contains("折叠：是  ·  本地：否"));
        assertTrue(summary.contains("ASR 状态：2  ·  文字：有"));
        assertTrue(summary.contains("播放资源：m4a / aac / 44100 Hz"));
        assertFalse(summary.contains("失败"));
        assertFalse(summary.contains("8 / 10"));
        assertFalse(summary.contains("private-vid"));
        assertFalse(summary.contains("private-asr"));
        assertEquals(10, new JSONObject(new JSONObject(source).getJSONObject("comment_audio")
                .getString("content")).getInt("status"));
        JSONObject raw = new JSONObject().put("contentType", 8).put("status", 1)
                .put("commentAudio", new JSONObject().put("a", "private-vid").put("e", 27324)
                        .put("d", "").put("f", 2).put("g", "private-asr").put("isFolded", true)
                        .put("b", "{\"status\":10,\"message\":\"success\",\"play_list\":[{\"vtype\":\"m4a\",\"code_type\":\"aac\",\"sample_rate\":44100}]}"));
        String rawSummary = CommentToolsHook.sourceAudioSummary(raw.toString());
        assertTrue(rawSummary.contains("时长：27324 ms  ·  波形：空"));
        assertTrue(rawSummary.contains("播放资源：m4a / aac / 44100 Hz"));
        assertTrue(rawSummary.contains("ASR 状态：2  ·  文字：有"));
    }

    @Test public void absentAndMalformedMediaDoesNotHideTheOriginalJson() throws Exception {
        assertTrue(CommentToolsHook.sourceAudioSummary("{\"comment_audio\":null}").contains("音频：无"));
        assertTrue(CommentToolsHook.sourceAudioSummary("{\"commentAudio\":null,\"replyComments\":[{\"commentAudio\":{\"a\":\"reply-vid\"}}]}")
                .contains("音频：无"));
        JSONObject comment = new JSONObject().put("comment_audio", new JSONObject()
                .put("content", "https://private.invalid/not-json").put("wave", "0.2,0.4"));
        String summary = CommentToolsHook.sourceAudioSummary(comment.toString());
        assertTrue(summary.contains("波形：非空"));
        assertTrue(summary.contains("播放资源：非 JSON 或无法解析"));
        assertFalse(summary.contains("private.invalid"));
        assertEquals("broken-json", CommentToolsHook.prettySourceJson("broken-json"));
        assertTrue(CommentToolsHook.sourceAudioSummary("broken-json").contains("摘要暂不可用"));
    }

    @Test public void sourceViewerUsesTheExistingSwitchAndCopiesOnlyOnRequest() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        List<String> logs = new ArrayList<>();
        CommentToolsHook hook = hook(activity, logs);
        try {
            ClipboardManager clipboard = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            assertFalse(clipboard.hasPrimaryClip());
            assertTrue(menu(hook, activity));
            AlertDialog tools = ShadowAlertDialog.getLatestAlertDialog();
            assertEquals("查看评论数据", tools.getListView().getAdapter().getItem(0));
            assertEquals("复制评论源数据", tools.getListView().getAdapter().getItem(1));
            tools.getListView().performItemClick(null, 0, 0);
            AlertDialog viewer = ShadowAlertDialog.getLatestAlertDialog();
            assertNotSame(tools, viewer);
            assertNotNull(viewer.getWindow().getDecorView().findViewWithTag("miuix.dialog.root"));
            assertFalse("Opening data must not copy it automatically", clipboard.hasPrimaryClip());
            viewer.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            String copied = clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString();
            assertEquals("private-token", new JSONObject(copied).getString("commentToken"));
            assertTrue("Copy keeps the viewer open", viewer.isShowing());
            viewer.dismiss();
            assertTrue(menu(hook, activity));
            ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 1, 1);
            assertEquals("The original copy action still returns the full same snapshot", copied,
                    clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity).toString());
            activity.getPreferences(0).edit().putBoolean("copy_source_data", false).apply();
            hook.setSettings(ExtendedSettings.readFrom(activity.getPreferences(0)));
            assertFalse(menu(hook, activity));
            assertTrue("No payload or token is written to diagnostic logs", logs.isEmpty());
        } finally { hook.close(); activity.finish(); }
    }

    @Test @Config(qualifiers = "night")
    public void sourceViewerUsesTheExistingDarkPalette() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        CommentToolsHook hook = hook(activity, new ArrayList<>());
        try {
            menu(hook, activity);
            ShadowAlertDialog.getLatestAlertDialog().getListView().performItemClick(null, 0, 0);
            AlertDialog viewer = ShadowAlertDialog.getLatestAlertDialog();
            assertTrue(ModuleTheme.isDark(activity));
            assertNotNull(viewer.getWindow().getDecorView().findViewWithTag("miuix.dialog.root"));
            viewer.dismiss();
        } finally { hook.close(); activity.finish(); }
    }

    static boolean menu(CommentToolsHook hook, Activity activity) throws Exception {
        Method show = CommentToolsHook.class.getDeclaredMethod("showMenu", Context.class, Object.class, Object.class);
        show.setAccessible(true);
        return (Boolean) show.invoke(hook, activity, new Comment(), null);
    }

    static CommentToolsHook hook(Activity activity, List<String> logs) throws Exception {
        SharedPreferences preferences = activity.getPreferences(0);
        preferences.edit().clear().putBoolean("copy_source_data", true).commit();
        Constructor<CommentToolsHook> constructor = CommentToolsHook.class.getDeclaredConstructor(
                ClassLoader.class, Class.class, Class.class, Class.class, Field.class, Field.class,
                ExtendedSettings.class, Consumer.class);
        constructor.setAccessible(true);
        return constructor.newInstance(CommentToolsHookTest.class.getClassLoader(), Item.class, Comment.class,
                null, Item.class.getField("k"), Params.class.getField("LJIIIIZZ"),
                ExtendedSettings.readFrom(preferences), (Consumer<String>) logs::add);
    }

    private static String source() throws Exception {
        return new JSONObject().put("content_type", 8).put("status", 1)
                .put("comment_audio", new JSONObject().put("vid", "private-vid").put("duration", 2000)
                        .put("wave", "").put("isFolded", true).put("isLocal", false)
                        .put("asr_status", 2).put("asr_text", "private-asr")
                        .put("content", "{\"status\":10,\"message\":\"success\",\"play_list\":[{\"audio\":{\"container\":\"m4a\",\"codec_type\":\"aac\",\"sample_rate\":44100}}]}"))
                .toString();
    }

    public static final class Comment {
        public String commentToken = "private-token";
        public int contentType = 8;
        public int status = 1;
        @SerializedName("comment_audio") public Audio audio = new Audio();
    }
    public static final class Audio {
        @SerializedName("asr_text") public String transcript = " 转写原文 ";
        public String vid = "private-vid";
        public long duration = 2000;
        public boolean isFolded = true;
    }
    public static final class Params { public Comment LJIIIIZZ; }
    public static final class Item { public Params k; }
}
