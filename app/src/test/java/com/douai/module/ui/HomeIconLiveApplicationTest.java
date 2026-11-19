package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.graphics.Color;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import com.douai.module.feature.LiquidGlassController;
import com.douai.module.feature.HomeUiCustomization;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** In-process publication/render integration, not a real LSPosed callback or device acceptance test. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeIconLiveApplicationTest {
    private Object metadataRegistry;
    private Map<String,Object> metadataBefore;
    @Before public void isolateNavigationMetadata() {
        metadataRegistry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        synchronized(metadataRegistry) {
            Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
            metadataBefore=new LinkedHashMap<>(observed);observed.clear();
        }
    }
    @After public void restoreNavigationMetadata() {
        if(metadataBefore!=null)synchronized(metadataRegistry) {
            Map<String,Object> observed=ReflectionHelpers.getField(metadataRegistry,"observed");
            observed.clear();observed.putAll(metadataBefore);
        }
    }
    @Test public void legacyRawHomeIsMarkedConfiguredPreviewedAndAppliedThroughItsCanonicalRow() throws Exception {
        byte[] bytes=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.RED);
        String legacy=new org.json.JSONObject().put("nav_bottom:HOME",java.util.Base64.getEncoder().encodeToString(bytes)).toString();
        try(Fixture fixture=new Fixture(legacy)) {
            LinearLayout row=fixture.page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target.nav_bottom:homepage_home");
            assertNotNull(row);assertTrue(((TextView)row.getChildAt(1)).getText().toString().contains("已配置"));
            assertEquals(Color.RED,((BitmapDrawable)fixture.tab.getTabTitle().getCompoundDrawables()[1]).getBitmap().getPixel(4,4));
            assertTrue(row.performClick());fixture.worker.runAll();ShadowLooper.idleMainLooper();
            for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
                ImageView preview=(ImageView)fixture.latest("home.icons.preview."+state.key);
                assertEquals(Color.RED,((BitmapDrawable)preview.getDrawable()).getBitmap().getPixel(4,4));
            }
            assertEquals("Opening a legacy preview does not rewrite its configuration",legacy,fixture.icons());
            assertEquals(0,fixture.publications.get());
        }
    }
    @Test public void theDefaultBuiltInButtonPublishesTwoStatesMatchingTheSavedPreviewAndBothHostThemes() throws Exception {
        try(Fixture fixture=new Fixture()) {
            assertNull(fixture.tab.getTabTitle().getCompoundDrawables()[1]);
            fixture.useBuiltIn();
            assertEquals("{}",fixture.icons());
            assertNull("Queued conversion must not change the navigation",fixture.tab.getTabTitle().getCompoundDrawables()[1]);
            fixture.worker.runAll();assertEquals("{}",fixture.icons());ShadowLooper.idleMainLooper();
            fixture.assertNoConfirmation();

            EnumMap<IconState,Pixels> expected=new EnumMap<>(IconState.class);
            for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
                ImageView preview=(ImageView)fixture.latest("home.icons.preview."+state.key);
                assertTrue(preview.getDrawable() instanceof BitmapDrawable);
                expected.put(state,new Pixels(((BitmapDrawable)preview.getDrawable()).getBitmap()));
            }
            assertFalse("The outline and filled previews are actually different resources",
                    java.util.Arrays.equals(expected.get(IconState.DAY_NORMAL).colors,expected.get(IconState.DAY_SELECTED).colors));

            assertEquals("One built-in save publishes the complete set once",1,fixture.publications.get());
            for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
                assertNotNull(HomeUiCustomizationSettings.readIcon(fixture.icons(),"nav_bottom:homepage_home",state));
                byte[] encoded=HomeUiCustomizationSettings.readIcon(fixture.icons(),"nav_bottom:homepage_home",state);
                Bitmap saved=android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.length);
                try {expected.get(state).assertMatches(saved);}finally {saved.recycle();}
            }
            assertNull(HomeUiCustomizationSettings.readIcon(fixture.icons(),"nav_bottom:homepage_home",IconState.NIGHT_NORMAL));
            assertNull(HomeUiCustomizationSettings.readIcon(fixture.icons(),"nav_bottom:homepage_home",IconState.NIGHT_SELECTED));
            for(IconState state:IconState.values()) {
                fixture.hostState(state);
                Drawable actual=fixture.tab.getTabTitle().getCompoundDrawables()[1];
                assertTrue("The controller has applied the configured icon for "+state,actual instanceof BitmapDrawable);
                expected.get(state.selected?IconState.DAY_SELECTED:IconState.DAY_NORMAL).assertMatches(((BitmapDrawable)actual).getBitmap());
            }
            assertTrue(fixture.tab.performClick());assertEquals(1,fixture.nativeClicks.get());

            fixture.store.putString(HomeUiCustomizationSettings.ICONS,"{}");ShadowLooper.idleMainLooper();
            assertNull("Clearing preferences restores the original navigation drawable",
                    fixture.tab.getTabTitle().getCompoundDrawables()[1]);
            assertTrue(fixture.tab.performClick());assertEquals(2,fixture.nativeClicks.get());
        }
    }

    @Test public void closingBeforeTheQueuedBuiltInCommitKeepsTheCurrentlyRenderedNavigation() throws Exception {
        String custom=HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_home",com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.RED));
        try(Fixture fixture=new Fixture(custom)) {
            String previous=fixture.icons();
            Drawable applied=fixture.tab.getTabTitle().getCompoundDrawables()[1];
            fixture.useBuiltIn();fixture.worker.runAll();fixture.page.close();ShadowLooper.idleMainLooper();
            assertEquals(previous,fixture.icons());assertEquals(0,fixture.publications.get());
            assertSame("A closed settings editor cannot replace the rendered icon",applied,
                    fixture.tab.getTabTitle().getCompoundDrawables()[1]);
        }
    }

    @Test public void navigationHideAndShowPublishVisibilityWithoutChangingStoredOrPreviewedArtwork() throws Exception {
        String custom=HomeUiCustomizationSettings.putIcon("{}","nav_bottom:homepage_home",com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.BLUE));
        try(Fixture fixture=new Fixture(custom)) {
            fixture.openEditor();fixture.worker.runAll();ShadowLooper.idleMainLooper();
            Bitmap preview=((BitmapDrawable)((ImageView)fixture.latest("home.icons.preview.day_normal")).getDrawable()).getBitmap();
            fixture.latest("home.icons.visibility").performClick();fixture.worker.runAll();ShadowLooper.idleMainLooper();
            assertEquals(View.GONE,fixture.tab.getVisibility());assertEquals(custom,fixture.icons());assertEquals(0,fixture.publications.get());
            assertEquals(1,fixture.visibilityPublications.get());assertFalse(preview.isRecycled());
            fixture.latest("home.icons.visibility").performClick();fixture.worker.runAll();ShadowLooper.idleMainLooper();
            assertEquals(View.VISIBLE,fixture.tab.getVisibility());assertEquals(custom,fixture.icons());assertEquals(2,fixture.visibilityPublications.get());
            assertTrue(fixture.tab.getTabTitle().getCompoundDrawables()[1] instanceof BitmapDrawable);
            assertTrue(fixture.tab.performClick());assertEquals(1,fixture.nativeClicks.get());
        }
    }

    @Test public void savedArtworkAndNativeDefaultsSurvivePassiveSettingsReopen() throws Exception {
        byte[][] old=oldBuiltInArtwork();
        byte[] customNormal=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.RED);
        byte[] customSelected=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.GREEN);
        byte[] night=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.BLUE);
        byte[] other=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.MAGENTA);
        byte[][][] scenarios={old,{customNormal,customSelected},{null,null}};
        for(byte[][] pair:scenarios) {
            String initial=pair[0]==null?"{}":savedArtwork(pair[0],pair[1],night,other);
            try(Fixture fixture=new Fixture(initial)) {
                View feed=fixture.page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target.feed_like");
                assertNotNull(feed);assertTrue(feed.performClick());fixture.flush();
                assertEquals(initial,fixture.icons());assertEquals(0,fixture.publications.get());
                fixture.openEditor();fixture.flush();
                fixture.assertArtwork(pair[0],pair[1]);
                assertEquals(initial,fixture.icons());assertEquals(0,fixture.publications.get());
                fixture.reopenPage();
                fixture.assertArtwork(pair[0],pair[1]);
                assertEquals("Passive close/open preserves the exact stored JSON",initial,fixture.icons());
                assertEquals(0,fixture.publications.get());
                if(pair[0]!=null)assertSavedArtwork(fixture.icons(),pair[0],pair[1],night,other);
            }
        }
    }

    @Test public void transparentBuiltinPairSurvivesReopenAndSingleThenWholeRestore() throws Exception {
        byte[][] old=oldBuiltInArtwork();
        byte[] normal=BuiltInNavigationIcons.symbolForPath("bottom/01-home",false);
        byte[] selected=BuiltInNavigationIcons.symbolForPath("bottom/01-home",true);
        byte[] night=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.BLUE);
        byte[] other=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.MAGENTA);
        String initial=savedArtwork(old[0],old[1],night,other);
        try(Fixture fixture=new Fixture(initial)) {
            fixture.useBuiltIn();
            assertEquals(initial,fixture.icons());assertEquals(0,fixture.publications.get());
            fixture.worker.runAll();
            assertEquals("Worker completion alone cannot publish",initial,fixture.icons());
            assertEquals(0,fixture.publications.get());ShadowLooper.idleMainLooper();
            assertSavedArtwork(fixture.icons(),normal,selected,night,other);
            fixture.assertArtwork(normal,selected);assertEquals(1,fixture.publications.get());
            String applied=fixture.icons();fixture.reopenPage();
            assertEquals(applied,fixture.icons());assertEquals(1,fixture.publications.get());
            assertSavedArtwork(fixture.icons(),normal,selected,night,other);fixture.assertArtwork(normal,selected);

            fixture.stateAction(IconState.DAY_NORMAL,1);fixture.flush();
            assertSavedArtwork(fixture.icons(),null,selected,night,other);
            fixture.assertArtwork(null,selected);assertEquals(2,fixture.publications.get());
            String oneRestored=fixture.icons();fixture.reopenPage();
            assertEquals(oneRestored,fixture.icons());assertEquals(2,fixture.publications.get());
            assertSavedArtwork(fixture.icons(),null,selected,night,other);fixture.assertArtwork(null,selected);

            assertTrue(fixture.latest("home.icons.restore.all").performClick());fixture.flush();
            org.json.JSONObject remaining=new org.json.JSONObject(fixture.icons());
            assertEquals(1,remaining.length());assertFalse(remaining.has("nav_bottom:homepage_home"));
            assertEquals(encoded(other),remaining.getString("feed_comment"));
            fixture.assertArtwork(null,null);assertEquals(3,fixture.publications.get());
            String allRestored=fixture.icons();fixture.reopenPage();
            assertEquals(allRestored,fixture.icons());assertEquals(3,fixture.publications.get());
            fixture.assertArtwork(null,null);
            assertTrue(fixture.tab.performClick());assertEquals(1,fixture.nativeClicks.get());
        }
    }

    @Test public void transparentBuiltinSingleStatePreservesOtherSavedArtworkAfterReopen() throws Exception {
        byte[][] old=oldBuiltInArtwork();
        byte[] selected=BuiltInNavigationIcons.symbolForPath("bottom/01-home",true);
        byte[] night=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.BLUE);
        byte[] other=com.douai.module.config.HomeUiIconFixtures.webp(8,8,Color.MAGENTA);
        String initial=savedArtwork(old[0],old[1],night,other);
        try(Fixture fixture=new Fixture(initial)) {
            fixture.openEditor();fixture.flush();fixture.stateAction(IconState.DAY_SELECTED,2);
            assertEquals(initial,fixture.icons());assertEquals(0,fixture.publications.get());
            fixture.worker.runAll();assertEquals(initial,fixture.icons());ShadowLooper.idleMainLooper();
            assertSavedArtwork(fixture.icons(),old[0],selected,night,other);
            fixture.assertArtwork(old[0],selected);assertEquals(1,fixture.publications.get());
            String applied=fixture.icons();fixture.reopenPage();
            assertEquals(applied,fixture.icons());assertEquals(1,fixture.publications.get());
            assertSavedArtwork(fixture.icons(),old[0],selected,night,other);
            fixture.assertArtwork(old[0],selected);
        }
    }

    private static byte[] fixtureBytes(String path) throws Exception {
        String resource="/META-INF/douai/"+path;
        try(java.io.InputStream stream=HomeIconLiveApplicationTest.class.getResourceAsStream(resource)) {
            assertNotNull("Missing independent fixture: "+resource,stream);return stream.readAllBytes();
        }
    }
    private static byte[][] oldBuiltInArtwork() throws Exception {
        // Convert frozen old source once before seeding; reopen never regenerates saved bytes.
        return new byte[][]{
                HomeIconImport.convert(fixtureBytes("legacy-built-in-fixtures/bottom/01-home.svg")),
                HomeIconImport.convert(fixtureBytes("legacy-built-in-fixtures/bottom/01-home-active.svg"))};
    }
    private static String encoded(byte[] bytes){return java.util.Base64.getEncoder().encodeToString(bytes);}
    private static String savedArtwork(byte[] normal,byte[] selected,byte[] night,byte[] other) throws Exception {
        org.json.JSONObject home=new org.json.JSONObject().put("day_normal",encoded(normal)).put("day_selected",encoded(selected))
                .put("night_normal",encoded(night)).put("night_selected",org.json.JSONObject.NULL);
        return new org.json.JSONObject().put("nav_bottom:homepage_home",home).put("feed_comment",encoded(other)).toString();
    }
    private static void assertSavedArtwork(String json,byte[] normal,byte[] selected,byte[] night,byte[] other) throws Exception {
        org.json.JSONObject root=new org.json.JSONObject(json),home=root.getJSONObject("nav_bottom:homepage_home");
        assertEquals(2,root.length());assertEquals(4,home.length());
        for(IconState state:IconState.values()) {
            byte[] expected=state==IconState.DAY_NORMAL?normal:state==IconState.DAY_SELECTED?selected:
                    state==IconState.NIGHT_NORMAL?night:null;
            assertTrue("Stored compatibility field remains explicit: "+state,home.has(state.key));
            if(expected==null)assertTrue("Native state stays explicit null: "+state,home.isNull(state.key));
            else assertArrayEquals("Saved bytes for "+state,expected,java.util.Base64.getDecoder().decode(home.getString(state.key)));
        }
        assertEquals(encoded(other),root.getString("feed_comment"));
    }
    private static Pixels pixels(byte[] encoded) {
        Bitmap bitmap=android.graphics.BitmapFactory.decodeByteArray(encoded,0,encoded.length);assertNotNull(bitmap);
        try{return new Pixels(bitmap);}finally{bitmap.recycle();}
    }

    private static final class Pixels {
        final int width,height;final int[] colors;
        Pixels(Bitmap bitmap) {
            width=bitmap.getWidth();height=bitmap.getHeight();colors=new int[width*height];
            bitmap.getPixels(colors,0,width,0,0,width,height);
        }
        void assertMatches(Bitmap bitmap) {
            assertEquals(width,bitmap.getWidth());assertEquals(height,bitmap.getHeight());
            int[] actual=new int[width*height];bitmap.getPixels(actual,0,width,0,0,width,height);
            assertArrayEquals("Preferences, the saved preview and navigation use the same resource pixels",colors,actual);
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final SharedPreferences preferences=activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        final HostSettingsStore store;
        final BottomTab tab=new BottomTab(activity,"HOME","首页");
        final AtomicInteger nativeClicks=new AtomicInteger(),publications=new AtomicInteger(),visibilityPublications=new AtomicInteger();
        final ManualExecutor worker=new ManualExecutor();
        final LiquidGlassController controller;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;
        HomeIconSettingsDialog page;
        Fixture(){this("{}");}
        Fixture(String initialIcons) {
            assertEquals("The real 41 factory exposes a raw type through getTabType", "HOME",tab.getTabType());
            preferences.edit().clear().putString(LiquidGlassSettings.STYLE,LiquidGlassSettings.LIQUID_GLASS)
                    .putBoolean(LiquidGlassSettings.GRAVITY,false).putString(HomeUiCustomizationSettings.ICONS,initialIcons).commit();
            preferences.edit().putString(HomeUiCustomizationSettings.HIDDEN_NAVIGATION,"[]").commit();
            FrameLayout root=new FrameLayout(activity);
            root.addView(tab,new FrameLayout.LayoutParams(240,160,Gravity.BOTTOM));
            tab.setOnClickListener(view->nativeClicks.incrementAndGet());activity.setContentView(root);
            View decor=activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));decor.layout(0,0,360,800);
            store=new HostSettingsStore(activity);
            controller=new LiquidGlassController(activity,LocalOptions.readFrom(preferences),ignored->{});
            controller.onActivityResumed(activity);
            // Stand in only for the module's preference callback; render/update code is the real controller.
            listener=(updated,key)->{
                if(HomeUiCustomizationSettings.ICONS.equals(key))publications.incrementAndGet();
                if(HomeUiCustomizationSettings.HIDDEN_NAVIGATION.equals(key))visibilityPublications.incrementAndGet();
                if(HomeUiCustomizationSettings.ICONS.equals(key) || HomeUiCustomizationSettings.HIDDEN_NAVIGATION.equals(key))
                    controller.setOptions(LocalOptions.readFrom(updated));
            };
            preferences.registerOnSharedPreferenceChangeListener(listener);
            page=createPage();
            page.dialog().show();ShadowLooper.idleMainLooper();
        }
        HomeIconSettingsDialog createPage() {
            return new HomeIconSettingsDialog(activity,store,false,(dialog,dismiss)->{
                if(dismiss!=null)dialog.setOnDismissListener(ignored->dismiss.run());dialog.show();
            },uri->{throw new AssertionError("Built-in selection must not read a document");},
                    (owner,intent,selected)->{throw new AssertionError("Built-in selection must not launch a picker");},worker);
        }
        void flush(){do{worker.runAll();ShadowLooper.idleMainLooper();}while(!worker.tasks.isEmpty());}
        void reopenPage() {
            page.close();page.dialog().dismiss();flush();
            page=createPage();page.dialog().show();ShadowLooper.idleMainLooper();openEditor();flush();
        }
        void stateAction(IconState state,int index) {
            assertTrue(latest("home.icons.state."+state.key).performClick());
            AlertDialog options=(AlertDialog)ShadowDialog.getLatestDialog();
            android.widget.ListView list=options.getListView();assertNotNull(list);
            assertTrue(list.performItemClick(list.getAdapter().getView(index,null,list),index,index));
        }
        void assertArtwork(byte[] normal,byte[] selected) {
            for(IconState state:new IconState[]{IconState.DAY_NORMAL,IconState.DAY_SELECTED}) {
                byte[] expected=state.selected?selected:normal;
                Drawable preview=((ImageView)latest("home.icons.preview."+state.key)).getDrawable();
                if(expected==null)assertNull("Native artwork has no invented preview: "+state,preview);
                else {assertTrue(preview instanceof BitmapDrawable);pixels(expected).assertMatches(((BitmapDrawable)preview).getBitmap());}
            }
            for(IconState state:IconState.values()) {
                hostState(state);byte[] expected=state.selected?selected:normal;
                Drawable actual=tab.getTabTitle().getCompoundDrawables()[1];
                if(expected==null)assertNull("Native compound drawable remains null: "+state,actual);
                else {assertTrue("Actual controller drawable for "+state,actual instanceof BitmapDrawable);
                    pixels(expected).assertMatches(((BitmapDrawable)actual).getBitmap());}
            }
            hostState(IconState.DAY_NORMAL);
        }
        String icons(){return store.getString(HomeUiCustomizationSettings.ICONS,"{}");}
        View latest(String tag) {
            java.util.List<android.app.Dialog> shown=ShadowDialog.getShownDialogs();
            for(int i=shown.size()-1;i>=0;i--)if(shown.get(i).isShowing() && shown.get(i).getWindow()!=null) {
                View view=shown.get(i).getWindow().getDecorView().findViewWithTag(tag);if(view!=null)return view;
            }
            fail("Visible view missing: "+tag);return null;
        }
        void openEditor() {
            View row=page.dialog().getWindow().getDecorView().findViewWithTag("home.icons.target.nav_bottom:homepage_home");
            assertNotNull(row);assertTrue(row.performClick());
        }
        void useBuiltIn() {
            openEditor();assertTrue(latest("home.icons.builtin.all").isEnabled());assertTrue(latest("home.icons.builtin.all").performClick());
            for(android.app.Dialog dialog:ShadowDialog.getShownDialogs())if(dialog.isShowing() && dialog instanceof AlertDialog)
                assertNull("The built-in action does not open a design chooser",((AlertDialog)dialog).getListView());
        }
        void assertNoConfirmation() {
            for(android.app.Dialog shown:ShadowDialog.getShownDialogs())if(shown.isShowing() && shown instanceof AlertDialog) {
                android.widget.Button button=((AlertDialog)shown).getButton(AlertDialog.BUTTON_POSITIVE);
                assertTrue(button==null || !"确认保存".contentEquals(button.getText()));
            }
        }
        void hostState(IconState state) {
            tab.setSelected(state.selected);
            Configuration configuration=new Configuration(activity.getResources().getConfiguration());
            configuration.uiMode=(configuration.uiMode&~Configuration.UI_MODE_NIGHT_MASK)
                    |(state.night?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
            activity.getResources().updateConfiguration(configuration,activity.getResources().getDisplayMetrics());
            activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
        }
        @Override public void close() {
            preferences.unregisterOnSharedPreferenceChangeListener(listener);page.close();controller.close();
            for(android.app.Dialog dialog:ShadowDialog.getShownDialogs())if(dialog.isShowing())dialog.dismiss();
            activity.finish();ShadowLooper.idleMainLooper();
        }
    }
    private static final class ManualExecutor implements Executor {
        final Queue<Runnable> tasks=new ArrayDeque<>();
        @Override public void execute(Runnable task){tasks.add(task);}
        void runAll(){while(!tasks.isEmpty())tasks.remove().run();}
    }
}
