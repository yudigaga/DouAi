package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.drawable.BitmapDrawable;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.HomeUiIconFixtures;
import com.douai.module.feature.MediaMemoryBudget;
import com.douai.module.feature.HomeUiCustomization;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SettingsRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeIconSettingsDialogTest {
    private Activity activity;
    private HostSettingsStore store;
    private ManualExecutor worker;
    private Picker picker;
    private HomeIconSettingsDialog page;
    private byte[] input;
    private int reads;
    private Thread readThread;
    private Object metadataRegistry;
    private Map<String,Object> metadataBefore;

    @Before public void setup() {
        metadataRegistry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        synchronized(metadataRegistry) {
            Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
            metadataBefore=new LinkedHashMap<>(observed);observed.clear();
        }
        activity=Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0).edit().clear().commit();
        store=new HostSettingsStore(activity);worker=new ManualExecutor();picker=new Picker();
        input=HomeUiIconFixtures.webp(8,8,Color.RED);
        page=new HomeIconSettingsDialog(activity,store,false,(dialog,dismiss)->{
            if(dismiss!=null)dialog.setOnDismissListener(ignored->dismiss.run());dialog.show();
        },uri->{reads++;readThread=Thread.currentThread();return new ByteArrayInputStream(input);},picker,worker);
        page.dialog().show();ShadowLooper.idleMainLooper();
    }
    @After public void close() {
        try {
            if(page!=null)page.close();
            for(android.app.Dialog dialog:ShadowDialog.getShownDialogs())if(dialog.isShowing())dialog.dismiss();
            if(activity!=null)activity.finish();ShadowLooper.idleMainLooper();
        } finally {
            if(metadataBefore!=null)synchronized(metadataRegistry) {
                Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
                observed.clear();observed.putAll(metadataBefore);
            }
        }
    }

    @Test public void validImportReadsInTheWorkerAndCommitsOnMainWithoutReplacingOtherIcons() {
        byte[] old=HomeUiIconFixtures.webp(6,6,Color.BLUE);
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcon("{}","feed_comment",old));
        choose("feed_like",0);
        assertTrue(Arrays.asList("*/*","image/*").contains(picker.intent.getType()));
        assertTrue(Arrays.asList(picker.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
                .containsAll(Arrays.asList("image/webp","image/svg+xml","application/xml","text/xml","application/octet-stream")));
        picker.select();
        assertEquals(0,reads);worker.runAll();assertEquals(1,reads);
        assertNull(saved("feed_like"));
        ShadowLooper.idleMainLooper();
        assertArrayEquals(input,saved("feed_like"));
        assertArrayEquals(old,saved("feed_comment"));
        assertTrue(status().contains("已导入"));
        assertFalse(status().contains("已转换"));
    }

    @Test public void builtInNavigationIconLoadsTransparentWebpOffMainWithoutAFilePickerAndKeepsOtherOverrides() throws Exception {
        byte[] previous=HomeUiIconFixtures.webp(6,6,Color.BLUE);
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcon("{}","feed_comment",previous));
        openTarget("nav_bottom:homepage_home");chooseState(IconState.DAY_SELECTED,2);
        assertNoSelectionDialog();
        assertNull(picker.intent);assertEquals(0,reads);
        worker.runAllOffMain();assertNull(saved("nav_bottom:homepage_home"));
        ShadowLooper.idleMainLooper();
        assertNoConfirmation();
        byte[] encoded=HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.DAY_SELECTED);
        assertNull(saved("nav_bottom:homepage_home"));
        assertNotNull(encoded);assertTrue(encoded.length<=32768);
        assertArrayEquals(previous,saved("feed_comment"));
        assertSavedBuiltin("nav_bottom:homepage_home",IconState.DAY_SELECTED,"bottom/01-home");
        assertTrue(status().contains("已保存内置图标"));
    }

    @Test public void aQueuedBuiltInChoiceCannotPublishAfterThePageCloses() {
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_like",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        choose("nav_bottom:homepage_home",2);
        page.close();worker.runAll();ShadowLooper.idleMainLooper();
        assertEquals(previous,json());assertNull(picker.intent);assertEquals(0,reads);
    }

    @Test public void svgLargerThan32KiBConvertsInTheWorkerAndStoresOnlyDecodableBoundedWebp() throws Exception {
        input=svg("<!--"+"x".repeat(40000)+"--><rect x='10' y='10' width='80' height='30' fill='#ff0000'/>");
        assertTrue(input.length>32768);assertTrue(input.length<=262144);
        choose("feed_like",0);picker.select();assertEquals(0,reads);assertEquals("{}",json());
        worker.runAllOffMain();assertEquals(1,reads);
        assertNotSame(android.os.Looper.getMainLooper().getThread(),readThread);
        assertEquals("{}",json());ShadowLooper.idleMainLooper();
        byte[] encoded=saved("feed_like");
        assertNotNull(encoded);assertTrue(encoded.length<=32768);
        assertEquals("RIFF",new String(encoded,0,4,java.nio.charset.StandardCharsets.US_ASCII));
        assertEquals("WEBP",new String(encoded,8,4,java.nio.charset.StandardCharsets.US_ASCII));
        Bitmap bitmap=BitmapFactory.decodeByteArray(encoded,0,encoded.length);assertNotNull(bitmap);
        try {
            assertTrue(bitmap.getWidth()<=256 && bitmap.getHeight()<=256);
            assertEquals(0,Color.alpha(bitmap.getPixel(0,0)));
            assertTrue(Color.red(bitmap.getPixel(bitmap.getWidth()/2,bitmap.getHeight()/2))>200);
        }finally{bitmap.recycle();}
        assertTrue(status().contains("已转换并导入"));
    }

    @Test public void damagedAndExternalSvgInputsKeepTheExactPreviousConfiguration() {
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_like",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        for(byte[] invalid:new byte[][]{svg("<path d='M0 0'>"),
                svg("<image href='https://example.invalid/icon.png' width='100' height='50'/>"),
                svg("<!--"+"x".repeat(262144)+"-->")}) {
            input=invalid;choose("feed_like",0);picker.select();worker.runAll();ShadowLooper.idleMainLooper();
            assertEquals(previous,json());assertTrue(status().contains("保留"));
        }
    }

    @Test public void svgConversionCannotPublishAfterThePageCloses() {
        input=svg("<rect width='100' height='50' fill='#00ff00'/>");
        choose("feed_like",0);picker.select();worker.runAll();page.close();ShadowLooper.idleMainLooper();
        assertEquals(1,reads);assertEquals("{}",json());
    }

    @Test public void invalidOversizedAndAnimatedDocumentsKeepTheExactPreviousConfiguration() {
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_like",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        byte[] valid=input;
        for(byte[] invalid:new byte[][]{"{\"v\":\"lottie\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8),
                new byte[32769],HomeUiIconFixtures.chunk(valid,"ANIM",6)}) {
            input=invalid;choose("feed_like",0);picker.select();worker.runAll();ShadowLooper.idleMainLooper();
            assertEquals(previous,json());assertTrue(status().contains("保留"));
        }
    }

    @Test public void restoringOneTargetKeepsEveryOtherOverride() {
        String both=HomeUiCustomizationSettings.putIcon("{}","feed_like",input);
        both=HomeUiCustomizationSettings.putIcon(both,"feed_comment",input);
        store.putString(HomeUiCustomizationSettings.ICONS,both);
        openTarget("feed_like");worker.runAll();ShadowLooper.idleMainLooper();
        ImageView originalPreview=preview(IconState.DAY_NORMAL);
        assertEquals(View.VISIBLE,originalPreview.getVisibility());
        Bitmap previous=((BitmapDrawable)originalPreview.getDrawable()).getBitmap();
        assertEquals(Color.RED,previous.getPixel(4,4));
        chooseState(IconState.DAY_NORMAL,1);worker.runAll();ShadowLooper.idleMainLooper();
        assertNull(saved("feed_like"));
        assertArrayEquals(input,saved("feed_comment"));
        assertArrayEquals(input,HomeUiCustomizationSettings.readIcon(json(),"feed_like",IconState.NIGHT_NORMAL));
        assertTrue(status().contains("已恢复"));
        assertTrue(previous.isRecycled());
        worker.runAll();ShadowLooper.idleMainLooper();
        assertNull(preview(IconState.DAY_NORMAL).getDrawable());
        assertEquals(View.GONE,preview(IconState.DAY_NORMAL).getVisibility());
        TextView restored=(TextView)latestView("home.icons.preview.status.day_normal");
        assertTrue(restored.getText().toString().contains("无法读取"));
        assertPreviewColor(IconState.DAY_SELECTED,Color.RED);
        assertNoVisibleView("home.icons.preview.night_normal");
    }

    @Test public void closingCancelsThePickerAndRejectsItsLateResult() {
        choose("feed_like",0);page.close();assertTrue(picker.cancelled);
        picker.select();worker.runAll();ShadowLooper.idleMainLooper();
        assertEquals(0,reads);assertEquals("{}",json());
    }

    @Test public void closingAfterTheWorkerFinishesPreventsTheQueuedMainCommit() {
        choose("feed_like",0);picker.select();worker.runAll();page.close();ShadowLooper.idleMainLooper();
        assertEquals(1,reads);assertEquals("{}",json());
    }

    @Test public void anExternalChangeWhileReadingIsRetainedAndReportedInsteadOfOverwritten() {
        choose("feed_like",0);picker.select();worker.runAll();
        String host=HomeUiCustomizationSettings.putIcon("{}","feed_share",input);
        store.putString(HomeUiCustomizationSettings.ICONS,host);ShadowLooper.idleMainLooper();
        assertEquals(host,json());assertTrue(status().contains("重新"));
    }

    @Test public void savedDynamicNavigationTargetsRemainManageableWithoutALiveHost() {
        page.close();page.dialog().dismiss();
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcon("{}","nav_top:homepage_hot",input));
        page=new HomeIconSettingsDialog(activity,store,false,(dialog,dismiss)->dialog.show());
        page.dialog().show();
        assertNotNull(page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target.nav_top:homepage_hot"));
    }

    @Test public void twoStatePreviewUsesSavedPixelsAndKeepsLegacyNightArtworkWhenEdited() {
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,HomeUiIconFixtures.webp(8,8,Color.RED));
        variants.put(IconState.DAY_SELECTED,HomeUiIconFixtures.webp(8,8,Color.GREEN));
        variants.put(IconState.NIGHT_NORMAL,HomeUiIconFixtures.webp(8,8,Color.BLUE));
        variants.put(IconState.NIGHT_SELECTED,HomeUiIconFixtures.webp(8,8,Color.YELLOW));
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcons("{}","feed_like",variants));
        openTarget("feed_like");
        assertNull(preview(IconState.DAY_NORMAL).getDrawable());worker.runAll();ShadowLooper.idleMainLooper();
        assertPreviewColor(IconState.DAY_NORMAL,Color.RED);assertPreviewColor(IconState.DAY_SELECTED,Color.GREEN);
        assertEquals("未选中，编辑图标",latestView("home.icons.state.day_normal").getContentDescription().toString());
        assertEquals("已选中，编辑图标",latestView("home.icons.state.day_selected").getContentDescription().toString());
        assertNoVisibleView("home.icons.state.night_normal");assertNoVisibleView("home.icons.state.night_selected");
        chooseState(IconState.DAY_SELECTED,0);input=HomeUiIconFixtures.webp(8,8,Color.MAGENTA);picker.select();
        worker.runAll();ShadowLooper.idleMainLooper();
        assertArrayEquals(variants.get(IconState.DAY_NORMAL),HomeUiCustomizationSettings.readIcon(json(),"feed_like",IconState.DAY_NORMAL));
        assertArrayEquals(variants.get(IconState.NIGHT_NORMAL),HomeUiCustomizationSettings.readIcon(json(),"feed_like",IconState.NIGHT_NORMAL));
        assertArrayEquals(variants.get(IconState.NIGHT_SELECTED),HomeUiCustomizationSettings.readIcon(json(),"feed_like",IconState.NIGHT_SELECTED));
        assertArrayEquals(input,HomeUiCustomizationSettings.readIcon(json(),"feed_like",IconState.DAY_SELECTED));
        worker.runAll();ShadowLooper.idleMainLooper();assertPreviewColor(IconState.DAY_SELECTED,Color.MAGENTA);
    }

    @Test public void editingARawNavigationStatePreservesLegacyNightArtworkAndExplicitNull() throws Exception {
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,HomeUiIconFixtures.webp(8,8,Color.RED));
        variants.put(IconState.DAY_SELECTED,HomeUiIconFixtures.webp(8,8,Color.GREEN));
        variants.put(IconState.NIGHT_NORMAL,HomeUiIconFixtures.webp(8,8,Color.BLUE));
        variants.put(IconState.NIGHT_SELECTED,null);
        org.json.JSONObject raw=new org.json.JSONObject(HomeUiCustomizationSettings.putIcons("{}","nav_bottom:homepage_home",variants));
        raw.put("nav_bottom:HOME",raw.remove("nav_bottom:homepage_home"));
        String previous=HomeUiCustomizationSettings.putIcon(raw.toString(),"feed_comment",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        openTarget("nav_bottom:homepage_home");worker.runAll();ShadowLooper.idleMainLooper();
        assertEquals(previous,json());
        chooseState(IconState.DAY_SELECTED,0);input=HomeUiIconFixtures.webp(8,8,Color.MAGENTA);picker.select();
        worker.runAll();ShadowLooper.idleMainLooper();
        assertArrayEquals(variants.get(IconState.DAY_NORMAL),HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.DAY_NORMAL));
        assertArrayEquals(input,HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.DAY_SELECTED));
        assertArrayEquals(variants.get(IconState.NIGHT_NORMAL),HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
        org.json.JSONObject saved=new org.json.JSONObject(json());
        assertFalse(saved.has("nav_bottom:HOME"));
        org.json.JSONObject states=saved.getJSONObject("nav_bottom:homepage_home");
        assertTrue(states.has("night_selected"));assertTrue(states.isNull("night_selected"));
        assertArrayEquals(HomeUiCustomizationSettings.readIcon(previous,"feed_comment"),saved("feed_comment"));
        worker.runAll();ShadowLooper.idleMainLooper();
        assertPreviewColor(IconState.DAY_SELECTED,Color.MAGENTA);
    }

    @Test public void savingTwoBuiltInStatesPreservesExistingNightArtworkAndExplicitNull() throws Exception {
        byte[] legacyNight=HomeUiIconFixtures.webp(8,8,Color.BLUE);
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,input);variants.put(IconState.NIGHT_NORMAL,legacyNight);
        variants.put(IconState.NIGHT_SELECTED,null);
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcons("{}","nav_bottom:homepage_home",variants));
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();
        worker.runAll();ShadowLooper.idleMainLooper();
        assertArrayEquals(legacyNight,HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
        org.json.JSONObject states=new org.json.JSONObject(json()).getJSONObject("nav_bottom:homepage_home");
        assertTrue(states.has("night_selected"));assertTrue(states.isNull("night_selected"));
        assertSavedBuiltin("nav_bottom:homepage_home",IconState.DAY_SELECTED,"bottom/01-home");
        assertNoVisibleView("home.icons.state.night_normal");assertNoVisibleView("home.icons.state.night_selected");
    }

    @Test public void choosingABuiltInPublishesTwoTransparentVectorStatesAndTheirActualSavedPreviewWithoutConfirmation() throws Exception {
        openTarget("nav_bottom:homepage_home");
        View all=latestView("home.icons.builtin.all");assertEquals("使用内置图标并保存",((android.widget.Button)all).getText().toString());assertTrue(all.performClick());
        assertNoSelectionDialog();
        assertEquals("{}",json());worker.runAllOffMain();assertEquals("{}",json());ShadowLooper.idleMainLooper();
        assertNoConfirmation();
        ImageView selected=preview(IconState.DAY_SELECTED);
        assertSavedBuiltin("nav_bottom:homepage_home",IconState.DAY_NORMAL,"bottom/01-home");
        assertSavedBuiltin("nav_bottom:homepage_home",IconState.DAY_SELECTED,"bottom/01-home");
        for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
            byte[] bytes=HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",state);assertNotNull(bytes);
            Bitmap saved=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
            try {assertTrue(saved.sameAs(((BitmapDrawable)preview(state).getDrawable()).getBitmap()));}
            finally {saved.recycle();}
        }
        assertNull(HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
        assertNull(HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",IconState.NIGHT_SELECTED));
        assertNoVisibleView("home.icons.preview.night_normal");assertNoVisibleView("home.icons.preview.night_selected");
        assertTrue(status().contains("返回首页查看"));assertNull(picker.intent);
        Bitmap visible=((BitmapDrawable)selected.getDrawable()).getBitmap();page.close();assertTrue(visible.isRecycled());assertAllBudgetAvailable();
    }

    @Test public void oneStateUsesTransparentBuiltinSelectionWithoutAnAppearanceChooser() throws Exception {
        openTarget("nav_bottom:homepage_profile");chooseState(IconState.DAY_SELECTED,2);
        worker.runAll();ShadowLooper.idleMainLooper();assertNoConfirmation();
        for(IconState state:IconState.values()) {
            byte[] bytes=HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_profile",state);
            if(state==IconState.DAY_SELECTED)assertNotNull(bytes);else assertNull(bytes);
        }
        assertSavedBuiltin("nav_bottom:homepage_profile",IconState.DAY_SELECTED,"bottom/05-profile");
        for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown instanceof AlertDialog)
            assertNull("Only the editor remains; no style or confirmation chooser is opened",((AlertDialog)shown).getListView());
    }

    @Test public void aNewerSingleStateRequestRejectsAnOlderDecodedWholeSet() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();worker.runAll();
        chooseState(IconState.DAY_SELECTED,2);worker.runAll();ShadowLooper.idleMainLooper();
        for(IconState state:IconState.values())if(state==IconState.DAY_SELECTED)
            assertNotNull(HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",state));
        else assertNull(HomeUiCustomizationSettings.readIcon(json(),"nav_bottom:homepage_home",state));
        page.close();assertAllBudgetAvailable();
    }

    @Test public void failureOnTheSecondBuiltInSourceKeepsTheWholePreviousConfigurationAndReleasesBudget() {
        page.close();page.dialog().dismiss();int[] opened={0};
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_comment",input);store.putString(HomeUiCustomizationSettings.ICONS,previous);
        page=new HomeIconSettingsDialog(activity,store,false,(dialog,dismiss)->{
            if(dismiss!=null)dialog.setOnDismissListener(ignored->dismiss.run());dialog.show();
        },new HomeIconSettingsDialog.Files() {
            @Override public InputStream read(Uri uri){return new ByteArrayInputStream(input);}
            @Override public InputStream builtIn(BuiltInNavigationIcons.Entry entry,BuiltInNavigationIcons.Style style) throws Exception {
                if(++opened[0]==2)throw new java.io.IOException("Second built-in source unavailable");
                return BuiltInNavigationIcons.open(entry,style);
            }
        },picker,worker);
        page.dialog().show();openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();
        worker.runAll();ShadowLooper.idleMainLooper();assertEquals(2,opened[0]);assertEquals(previous,json());assertTrue(status().contains("保留"));
        page.close();assertAllBudgetAvailable();
    }

    @Test public void cancellingTheStateMenuKeepsTheExactConfigurationAndRejectsItsLateClick() {
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_comment",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        openTarget("nav_bottom:homepage_home");latestView("home.icons.state.day_normal").performClick();
        AlertDialog menu=(AlertDialog)ShadowDialog.getLatestDialog();ListView late=menu.getListView();
        menu.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();ShadowLooper.idleMainLooper();
        late.performItemClick(late.getAdapter().getView(2,null,late),2,2);
        worker.runAll();ShadowLooper.idleMainLooper();assertEquals(previous,json());
    }

    @Test public void anExternalEditBeforeTheQueuedDirectApplyCannotBeOverwritten() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();
        worker.runAll();
        String newer=HomeUiCustomizationSettings.putIcon("{}","feed_share",input);
        store.putString(HomeUiCustomizationSettings.ICONS,newer);ShadowLooper.idleMainLooper();
        assertEquals(newer,json());assertTrue(status().contains("重新"));
        page.close();assertAllBudgetAvailable();
    }

    @Test public void aDecodedBuiltInResultCannotPublishAfterThePageClosesAndReleasesItsBudget() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();
        worker.runAll();page.close();ShadowLooper.idleMainLooper();
        assertEquals("{}",json());
        for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown instanceof AlertDialog) {
            android.widget.Button positive=((AlertDialog)shown).getButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue(positive==null || !"确认保存".contentEquals(positive.getText()));
        }
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)){assertNotNull(all);}
    }

    @Test public void aBusyBudgetRejectsTheWholeBuiltInSetWithoutSavingAnyState() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
            assertNotNull(all);worker.runAll();ShadowLooper.idleMainLooper();assertEquals("{}",json());
        }
        assertTrue(status().contains("保留"));
    }

    @Test public void restoringTheWholePositionAlsoClearsItsLegacySelectedAlias() {
        String previous=HomeUiCustomizationSettings.putIcon("{}","feed_like",input);
        previous=HomeUiCustomizationSettings.putIcon(previous,"feed_like_selected",HomeUiIconFixtures.webp(8,8,Color.GREEN));
        previous=HomeUiCustomizationSettings.putIcon(previous,"feed_comment",input);
        store.putString(HomeUiCustomizationSettings.ICONS,previous);
        openTarget("feed_like");latestView("home.icons.restore.all").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        for(IconState state:IconState.values())assertNull(HomeUiCustomizationSettings.readIcon(json(),"feed_like",state));
        assertNull(HomeUiCustomizationSettings.readIcon(json(),"feed_like_selected"));
        assertArrayEquals(input,saved("feed_comment"));
    }

    @Test public void closingTheEditorRecyclesVisiblePreviewsAndRejectsQueuedPreviewCallbacks() {
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcon("{}","feed_like",input));
        openTarget("feed_like");worker.runAll();ShadowLooper.idleMainLooper();
        Bitmap visible=((BitmapDrawable)preview(IconState.DAY_NORMAL).getDrawable()).getBitmap();
        ((AlertDialog)ShadowDialog.getLatestDialog()).dismiss();ShadowLooper.idleMainLooper();assertTrue(visible.isRecycled());
        openTarget("feed_like");worker.runAll();page.close();ShadowLooper.idleMainLooper();
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)){assertNotNull(all);}
    }

    @Test public void anUnreadableHostOriginalIsExplicitInsteadOfUsingAFakePreview() {
        assertEquals("{}",json());
        for(IconState state:IconState.values())assertNull(HomeUiCustomizationSettings.readIcon(json(),"feed_like",state));
        openTarget("feed_like");worker.runAll();ShadowLooper.idleMainLooper();
        for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
            assertNull(preview(state).getDrawable());
            assertEquals(View.GONE,preview(state).getVisibility());
            TextView original=(TextView)latestView("home.icons.preview.status."+state.key);
            assertTrue(original.getText().toString().contains("宿主原图"));
            assertTrue(original.getText().toString().contains("无法读取"));
        }
    }

    @Test public void feedWithADefinedDefaultKeepsCustomImportRestoreAndHasNoNavigationHideAction() throws Exception {
        openTarget("feed_like");android.widget.Button builtIn=(android.widget.Button)latestView("home.icons.builtin.all");
        assertTrue(builtIn.isEnabled());assertNoVisibleView("home.icons.builtin.unavailable");
        assertNull(((AlertDialog)ShadowDialog.getLatestDialog()).getWindow().getDecorView().findViewWithTag("home.icons.visibility"));
        assertNotNull(latestView("home.icons.restore.all"));
        builtIn.performClick();worker.runAll();ShadowLooper.idleMainLooper();assertNull(picker.intent);
        assertSavedBuiltin("feed_like",IconState.DAY_NORMAL,"feed/01-like");
        assertSavedBuiltin("feed_like",IconState.DAY_SELECTED,"feed/01-like");
        latestView("home.icons.state.day_normal").performClick();ListView actions=((AlertDialog)ShadowDialog.getLatestDialog()).getListView();
        assertEquals(3,actions.getAdapter().getCount());assertEquals("导入 SVG / WebP",actions.getAdapter().getItem(0));
        assertEquals("恢复此状态原图",actions.getAdapter().getItem(1));
        assertEquals("使用内置图标并保存",actions.getAdapter().getItem(2));
    }
    @Test public void allSixFeedBuiltinsSaveTransparentVectorPairsAndPreviewsWithoutNavigationActions() throws Exception {
        String[][] entries={{"feed_like","feed/01-like"},{"feed_comment","feed/02-comment"},
                {"feed_collect","feed/03-collect"},{"feed_share","feed/04-share"},
                {"feed_follow","feed/05-follow"},{"feed_mute","feed/06-mute"}};
        for(int index=0;index<entries.length;index++) {
            String key=entries[index][0],path=entries[index][1];openTarget(key);
            worker.runAll();ShadowLooper.idleMainLooper();String previous=json();
            android.widget.Button builtIn=(android.widget.Button)latestView("home.icons.builtin.all");
            assertTrue(key,builtIn.isEnabled());assertNoVisibleView("home.icons.builtin.unavailable");
            assertNull(((AlertDialog)ShadowDialog.getLatestDialog()).getWindow().getDecorView().findViewWithTag("home.icons.visibility"));
            assertTrue(builtIn.performClick());assertEquals(previous,json());
            worker.runAll();assertEquals("Worker alone cannot publish a partial pair: "+key,previous,json());
            ShadowLooper.idleMainLooper();assertNull(picker.intent);assertNoConfirmation();assertNoSelectionDialog();
            assertSavedBuiltin(key,IconState.DAY_NORMAL,path);
            assertSavedBuiltin(key,IconState.DAY_SELECTED,path);
            org.json.JSONObject savedPair=new org.json.JSONObject(json()).getJSONObject(key);
            assertEquals("Only the two editable day fields are created: "+key,2,savedPair.length());
            assertFalse(savedPair.has(IconState.NIGHT_NORMAL.key));assertFalse(savedPair.has(IconState.NIGHT_SELECTED.key));
            for(int earlier=0;earlier<index;earlier++)for(IconState state:IconState.values())
                assertArrayEquals("Previously saved Feed artwork must survive: "+entries[earlier][0]+"/"+state,
                        HomeUiCustomizationSettings.readIcon(previous,entries[earlier][0],state),
                        HomeUiCustomizationSettings.readIcon(json(),entries[earlier][0],state));
            assertTrue(latestView("home.icons.state.day_normal").performClick());
            AlertDialog actions=(AlertDialog)ShadowDialog.getLatestDialog();ListView list=actions.getListView();
            assertEquals(3,list.getAdapter().getCount());assertEquals("导入 SVG / WebP",list.getAdapter().getItem(0));
            assertEquals("恢复此状态原图",list.getAdapter().getItem(1));
            assertEquals("使用内置图标并保存",list.getAdapter().getItem(2));
            assertTrue(actions.getButton(AlertDialog.BUTTON_NEGATIVE).performClick());ShadowLooper.idleMainLooper();
        }
    }
    @Test public void hidingAndShowingNavigationPreserveAllArtAndTheActualEditorPreview() {
        String icons=HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_home",input);store.putString(HomeUiCustomizationSettings.ICONS,icons);
        openTarget("nav_bottom:homepage_home");worker.runAll();ShadowLooper.idleMainLooper();
        Bitmap art=((BitmapDrawable)preview(IconState.DAY_NORMAL).getDrawable()).getBitmap();
        latestView("home.icons.visibility").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).contains("nav_bottom:homepage_home"));assertEquals(icons,json());
        assertSame(art,((BitmapDrawable)preview(IconState.DAY_NORMAL).getDrawable()).getBitmap());assertFalse(art.isRecycled());
        assertTrue(((TextView)latestView("home.icons.visibility.status")).getText().toString().contains("已隐藏"));
        assertEquals("显示此导航项",((android.widget.Button)latestView("home.icons.visibility")).getText().toString());
        View row=page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target.nav_bottom:homepage_home");assertNotNull(row);
        assertTrue(((TextView)((android.widget.LinearLayout)row).getChildAt(1)).getText().toString().contains("已隐藏"));
        latestView("home.icons.visibility").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).isEmpty());assertEquals(icons,json());assertFalse(art.isRecycled());
        assertEquals("隐藏此导航项",((android.widget.Button)latestView("home.icons.visibility")).getText().toString());
    }

    @Test public void restoringPositionArtworkDoesNotImplicitlyShowHiddenNavigation() {
        store.putString(HomeUiCustomizationSettings.ICONS,HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_home",input));
        String hidden=HomeUiCustomizationSettings.setNavigationHidden("[]","nav_bottom:homepage_home",true);store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden);
        openTarget("nav_bottom:homepage_home");assertEquals("恢复此位置原图",((android.widget.Button)latestView("home.icons.restore.all")).getText().toString());
        latestView("home.icons.restore.all").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        assertEquals("{}",json());assertEquals(hidden,hiddenJson());
        assertEquals("显示此导航项",((android.widget.Button)latestView("home.icons.visibility")).getText().toString());
    }

    @Test public void aHiddenOnlyUnknownNavigationRemainsManageableWithoutInventingAnIcon() {
        page.close();page.dialog().dismiss();
        store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[\"nav_top:unknown_hidden_channel\"]");
        page=new HomeIconSettingsDialog(activity,store,false,(dialog,dismiss)->{if(dismiss!=null)dialog.setOnDismissListener(ignored->dismiss.run());dialog.show();},
                uri->new ByteArrayInputStream(input),picker,worker);page.dialog().show();
        openTarget("nav_top:unknown_hidden_channel");worker.runAll();ShadowLooper.idleMainLooper();
        assertFalse(latestView("home.icons.builtin.all").isEnabled());
        assertEquals("显示此导航项",((android.widget.Button)latestView("home.icons.visibility")).getText().toString());
        for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED})assertNull(preview(state).getDrawable());
        latestView("home.icons.visibility").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).isEmpty());assertEquals("{}",json());
    }

    @Test public void aConcurrentHiddenNavigationEditCannotBeOverwrittenByAQueuedToggle() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.visibility").performClick();worker.runAll();
        String newer=HomeUiCustomizationSettings.setNavigationHidden("[]","nav_bottom:homepage_profile",true);store.putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,newer);
        ShadowLooper.idleMainLooper();assertEquals(newer,hiddenJson());assertEquals("{}",json());assertTrue(status().contains("重新"));
    }

    @Test public void closingAfterVisibilityWorkerFinishesRejectsItsQueuedCommit() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.visibility").performClick();worker.runAll();page.close();ShadowLooper.idleMainLooper();
        assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).isEmpty());assertEquals("{}",json());assertAllBudgetAvailable();
    }

    @Test public void aNavigationHideSupersedesAnOlderDecodedBuiltInWithoutWritingArtwork() {
        openTarget("nav_bottom:homepage_home");latestView("home.icons.builtin.all").performClick();worker.runAll();
        latestView("home.icons.visibility").performClick();worker.runAll();ShadowLooper.idleMainLooper();
        assertEquals("{}",json());assertTrue(HomeUiCustomizationSettings.readHiddenNavigation(hiddenJson()).contains("nav_bottom:homepage_home"));
        page.close();assertAllBudgetAvailable();
    }

    private void assertSavedBuiltin(String key,IconState state,String path) throws Exception {
        BuiltInNavigationIcons.Entry entry=BuiltInNavigationIcons.entryForPosition(key);
        assertNotNull("Mapped built-in remains available: "+key,entry);
        assertEquals(path,entry.path);
        byte[] expected;
        try(InputStream source=BuiltInNavigationIcons.open(entry,BuiltInNavigationIcons.styleFor(state))) {
            expected=source.readAllBytes();
        }
        assertArrayEquals("Selected built-in WebP bytes: "+key+"/"+state,expected,
                HomeUiCustomizationSettings.readIcon(json(),key,state));
        Bitmap expectedPixels=BitmapFactory.decodeByteArray(expected,0,expected.length);assertNotNull(expectedPixels);
        try {
            assertEquals(256,expectedPixels.getWidth());assertEquals(256,expectedPixels.getHeight());
            assertEquals(0,android.graphics.Color.alpha(expectedPixels.getPixel(0,0)));
            assertTrue("Preview must show generated transparent symbol pixels: "+key+"/"+state,
                    expectedPixels.sameAs(((BitmapDrawable)preview(state).getDrawable()).getBitmap()));
        }finally{expectedPixels.recycle();}
    }
    private void choose(String key,int index) {
        openTarget(key);chooseState(IconState.DAY_NORMAL,index);
    }
    private void openTarget(String key) {
        View row=page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target."+key);
        assertNotNull(row);assertTrue(row.performClick());
    }
    private void chooseState(IconState state,int index) {
        View cell=latestView("home.icons.state."+state.key);assertTrue(cell.performClick());
        AlertDialog options=(AlertDialog)ShadowDialog.getLatestDialog();ListView list=options.getListView();
        list.performItemClick(list.getAdapter().getView(index,null,list),index,index);
    }
    private View latestView(String tag) {
        java.util.List<android.app.Dialog> shown=ShadowDialog.getShownDialogs();
        for(int i=shown.size()-1;i>=0;i--) {
            android.app.Dialog dialog=shown.get(i);
            if(!dialog.isShowing() || dialog.getWindow()==null)continue;
            View view=dialog.getWindow().getDecorView().findViewWithTag(tag);if(view!=null)return view;
        }
        fail("Visible view missing: "+tag);return null;
    }
    private void assertNoVisibleView(String tag) {
        for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown.getWindow()!=null)
            assertNull(shown.getWindow().getDecorView().findViewWithTag(tag));
    }
    private ImageView preview(IconState state){return (ImageView)latestView("home.icons.preview."+state.key);}
    private void assertPreviewColor(IconState state,int color){
        Bitmap bitmap=((BitmapDrawable)preview(state).getDrawable()).getBitmap();assertEquals(color,bitmap.getPixel(4,4));
    }
    private void assertNoConfirmation(){
        for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown instanceof AlertDialog) {
            android.widget.Button positive=((AlertDialog)shown).getButton(AlertDialog.BUTTON_POSITIVE);
            assertTrue(positive==null || !"确认保存".contentEquals(positive.getText()));
        }
    }
    private void assertNoSelectionDialog(){
        for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown instanceof AlertDialog)
            assertNull("The built-in save must not open a design or appearance chooser",((AlertDialog)shown).getListView());
    }
    private void assertAllBudgetAvailable(){
        try(MediaMemoryBudget.Reservation all=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)){assertNotNull(all);}
    }
    private String json(){return store.getString(HomeUiCustomizationSettings.ICONS,"{}");}
    private String hiddenJson(){return store.getString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[]");}
    private byte[] saved(String key){return HomeUiCustomizationSettings.readIcon(json(),key,IconState.DAY_NORMAL);}
    private String status(){return ((TextView)page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.status")).getText().toString();}
    private static byte[] svg(String body){return ("<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 50'>"+body+"</svg>").getBytes(java.nio.charset.StandardCharsets.UTF_8);}
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks=new ArrayDeque<>();
        @Override public void execute(Runnable task){tasks.add(task);}
        void runAll(){while(!tasks.isEmpty())tasks.remove().run();}
        void runAllOffMain() throws Exception {
            Throwable[] failure={null};Thread thread=new Thread(()->{try{runAll();}catch(Throwable error){failure[0]=error;}},"icon-import-test");
            thread.start();thread.join(10000);assertFalse("Icon conversion should finish",thread.isAlive());
            if(failure[0]!=null)throw new AssertionError(failure[0]);
        }
    }
    private static final class Picker implements HomeIconSettingsDialog.Picker {
        Consumer<Uri> selected;Intent intent;boolean cancelled;
        @Override public AutoCloseable launch(Activity activity,Intent intent,Consumer<Uri> selected) {
            this.intent=intent;this.selected=selected;cancelled=false;return ()->cancelled=true;
        }
        void select(){selected.accept(Uri.parse("content://icons/static.webp"));}
    }
}
