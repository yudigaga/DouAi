package com.douai.module.feature;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.Map;
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
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

/** In-process lifecycle/publication/render integration; not a real LSPosed callback or phone test. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class HomeNavigationMetadataLifecycleTest {
    private Object registry;
    private Map<String,Object> metadataBefore;
    private String oldWindows,oldScale;
    private float oldPlatformScale;
    @Before public void isolateProcessMetadataAndNativeWindowConditions() {
        registry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        Map<String,Object> observed=ReflectionHelpers.getField(registry,"observed");
        synchronized(registry){metadataBefore=new LinkedHashMap<>(observed);observed.clear();}
        oldWindows=System.getProperty("robolectric.areWindowsMarkedVisible");System.setProperty("robolectric.areWindowsMarkedVisible","true");
        oldScale=Settings.Global.getString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE);
        oldPlatformScale=ReflectionHelpers.callStaticMethod(ValueAnimator.class,"getDurationScale");
        Settings.Global.putFloat(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,0f);
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,0f));
    }
    @After public void restoreProcessMetadataAndPreferences() {
        try {
            Settings.Global.putString(RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,oldScale);
            ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,oldPlatformScale));
        } finally {
            if(oldWindows==null)System.clearProperty("robolectric.areWindowsMarkedVisible");else System.setProperty("robolectric.areWindowsMarkedVisible",oldWindows);
            if(registry!=null && metadataBefore!=null) {
                Map<String,Object> observed=ReflectionHelpers.getField(registry,"observed");
                synchronized(registry){observed.clear();observed.putAll(metadataBefore);}
            }
        }
    }

    @Test public void observedTopEntriesAndFriendlyNamesSurviveHomePauseSettingsPublicationAndReturn() throws Exception {
        try(Scene s=new Scene()) {
            for(int i=0;i<Scene.IDS.length;i++) {
                assertEquals(Scene.IDS[i],s.tabs[i].getCurrentTabId());
                assertEquals("上导航 · "+Scene.NAMES[i],target(s.icons(),"nav_top:"+Scene.IDS[i]).title);
                assertNull("Discovery alone must not apply an unconfigured top image",s.titles[i].getCompoundDrawables()[1]);
            }
            s.openSettings();
            assertFalse("The paused home monitor really released its view tree",windows(s.controller).containsKey(s.home.getWindow().getDecorView()));
            for(int i=0;i<Scene.IDS.length;i++)assertEquals("上导航 · "+Scene.NAMES[i],target(s.icons(),"nav_top:"+Scene.IDS[i]).title);
            String[] bottomIds={"home","familiar","notification","profile"},bottomNames={"首页","朋友","消息","我"};
            for(int i=0;i<bottomIds.length;i++)assertEquals("下导航 · "+bottomNames[i],target(s.icons(),"nav_bottom:homepage_"+bottomIds[i]).title);

            HomeUiCustomization.Target selected=target(s.icons(),"nav_top:homepage_nearby");
            String next=HomeUiCustomizationSettings.putIcons(s.icons(),selected.key,fourIcons());
            s.store.putString(HomeUiCustomizationSettings.ICONS,next);ShadowLooper.idleMainLooper();
            assertEquals("The chosen entry remains human-readable after replacement","上导航 · 城西镇",target(s.icons(),selected.key).title);
            assertNull("A settings publication does not repaint the released home tree",s.titles[1].getCompoundDrawables()[1]);

            s.returnHome();
            for(IconState state:IconState.values()) {
                for(HomeTopTabItemViewNew tab:s.tabs)tab.setSelected(false);s.tabs[1].setSelected(state.selected);
                Configuration config=new Configuration(s.home.getResources().getConfiguration());
                config.uiMode=(config.uiMode&~Configuration.UI_MODE_NIGHT_MASK)|(state.night?Configuration.UI_MODE_NIGHT_YES:Configuration.UI_MODE_NIGHT_NO);
                s.home.getResources().updateConfiguration(config,s.home.getResources().getDisplayMetrics());
                s.home.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();s.layoutHome();
                assertNotNull("Returning to the host applies the configured state",s.titles[1].getCompoundDrawables()[1]);
                assertTrue("The compound image actually paints the expected state pixels: "+state,paintedPixels(s.titles[1],stateColor(IconState.of(false,state.selected)))>0);
                assertEquals("城西镇",s.titles[1].getText().toString());
                assertEquals("上导航 · 城西镇",target(s.icons(),selected.key).title);
            }
        }
    }
    @Test public void imageContentCanContributeItsRealAccessibilityNameWithoutBecomingATextLabel() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(activity);
        ImageView image=new ImageView(activity);image.setContentDescription("直播");
        HomeTopTabItemViewNew tab=new HomeTopTabItemViewNew(activity,"runtime_live_image",image);root.addView(tab);
        try(HomeUiCustomization custom=new HomeUiCustomization(root,new LocalOptions(Map.of()),null)) {
            assertEquals("上导航 · 直播",target("{}","nav_top:runtime_live_image").title);
            assertTrue(custom.labels(tab).isEmpty());assertNull(image.getDrawable());assertSame(image,tab.getContentView());
        } finally {activity.finish();}
        assertEquals("上导航 · 直播",target("{}","nav_top:runtime_live_image").title);
    }
    @Test public void aHiddenTitleCannotDowngradeANameObservedWhileItsOwnerWasVisible() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();FrameLayout root=new FrameLayout(activity);
        TextView title=new TextView(activity);title.setText("城西镇");
        HomeTopTabItemViewNew tab=new HomeTopTabItemViewNew(activity,"homepage_nearby",title);
        root.addView(tab,new FrameLayout.LayoutParams(120,90));activity.setContentView(root);layout(activity);
        try(HomeUiCustomization custom=new HomeUiCustomization(root,new LocalOptions(Map.of()),null)) {
            assertTrue(tab.isShown());assertEquals(View.VISIBLE,tab.getWindowVisibility());
            assertEquals("上导航 · 城西镇",target("{}","nav_top:homepage_nearby").title);
            title.setVisibility(View.GONE);title.setText("同城预加载");custom.refresh();
            assertTrue("The owner remains visible while its fallback title is hidden",tab.isShown());
            assertEquals("上导航 · 城西镇",target("{}","nav_top:homepage_nearby").title);
            title.setVisibility(View.VISIBLE);title.setText("城东镇");custom.refresh();
            assertEquals("上导航 · 城东镇",target("{}","nav_top:homepage_nearby").title);
        } finally {activity.finish();}
    }

    private static HomeUiCustomization.Target target(String json,String key) {
        return HomeUiCustomization.availableTargets(json).stream().filter(value->value.key.equals(key)).findFirst().orElseThrow(()->new AssertionError(key));
    }
    private static Map<?,?> windows(LiquidGlassController controller){return ReflectionHelpers.getField(controller,"windows");}
    private static int paintedPixels(TextView title,int color) {
        assertTrue(title.getWidth()>0 && title.getHeight()>0);
        Bitmap bitmap=Bitmap.createBitmap(title.getWidth(),title.getHeight(),Bitmap.Config.ARGB_8888);
        try {title.draw(new Canvas(bitmap));int[] pixels=new int[bitmap.getWidth()*bitmap.getHeight()];bitmap.getPixels(pixels,0,bitmap.getWidth(),0,0,bitmap.getWidth(),bitmap.getHeight());int count=0;for(int pixel:pixels)if(pixel==color)count++;return count;}
        finally {bitmap.recycle();}
    }
    private static int stateColor(IconState state){return new int[]{Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW}[state.ordinal()];}
    private static Map<IconState,byte[]> fourIcons(){Map<IconState,byte[]> values=new EnumMap<>(IconState.class);for(IconState state:IconState.values())values.put(state,webp(stateColor(state)));return values;}
    /** The fixed lossless fixture avoids the API30 encoder enum on SDK28; source: round14/test-fixture-data. */
    private static byte[] webp(int color) {
        String encoded;
        switch(color) {
            case Color.RED:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChA/wvQ/wIAAA==";break;
            case Color.BLUE:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChAAfrf/wIAAA==";break;
            case Color.GREEN:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh/AQrQ/wIAAA==";break;
            case Color.YELLOW:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh//wvQ/wIAAA==";break;
            default:throw new AssertionError(color);
        }
        return Base64.getDecoder().decode(encoded);
    }
    private static void layout(Activity activity) {
        View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));decor.layout(0,0,600,400);
    }
    private static final class Scene implements AutoCloseable {
        // Sample observed labels, not a production mapping: city/channel display names may change.
        static final String[] IDS={"homepage_follow","homepage_nearby","homepage_hot","homepage_groupon","homepage_mall"};
        static final String[] NAMES={"关注","城西镇","精选","团购","商城"};
        final ActivityController<Activity> homeLifecycle=Robolectric.buildActivity(Activity.class).setup();
        final Activity home=homeLifecycle.get();
        final int originalMode=home.getResources().getConfiguration().uiMode;
        final SharedPreferences preferences=home.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        final HostSettingsStore store=new HostSettingsStore(home);
        final HomeTopTabItemViewNew[] tabs=new HomeTopTabItemViewNew[IDS.length];
        final TextView[] titles=new TextView[IDS.length];
        final LiquidGlassController controller;
        final SharedPreferences.OnSharedPreferenceChangeListener listener;
        ActivityController<Activity> settingsLifecycle;
        Scene() {
            String icons="{}";for(String id:new String[]{"home","familiar","notification","profile"})icons=HomeUiCustomizationSettings.putIcon(icons,"nav_bottom:homepage_"+id,webp(Color.BLUE));
            preferences.edit().clear().putString(LiquidGlassSettings.STYLE,LiquidGlassSettings.LIQUID_GLASS).putBoolean(LiquidGlassSettings.GRAVITY,false).putString(HomeUiCustomizationSettings.ICONS,icons).commit();
            FrameLayout root=new FrameLayout(home);MainTitleBar bar=new MainTitleBar(home);root.addView(bar,new FrameLayout.LayoutParams(560,110));
            for(int i=0;i<IDS.length;i++) {
                titles[i]=new TextView(home);titles[i].setText(NAMES[i]);tabs[i]=new HomeTopTabItemViewNew(home,IDS[i],titles[i]);
                FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(110,90);size.leftMargin=i*110;bar.addView(tabs[i],size);
            }
            home.setContentView(root);layoutHome();
            controller=new LiquidGlassController(home,LocalOptions.readFrom(preferences),null);controller.onActivityResumed(home);
            // Only the preference observer is simulated; publication, lifecycle and rendering use real code.
            listener=(updated,key)->{if(HomeUiCustomizationSettings.ICONS.equals(key))controller.setOptions(LocalOptions.readFrom(updated));};
            preferences.registerOnSharedPreferenceChangeListener(listener);
        }
        String icons(){return store.getString(HomeUiCustomizationSettings.ICONS,"{}");}
        void openSettings() {
            homeLifecycle.pause();controller.onActivityPaused(home);
            settingsLifecycle=Robolectric.buildActivity(Activity.class).setup();Activity settings=settingsLifecycle.get();settings.setContentView(new FrameLayout(settings));layout(settings);controller.onActivityResumed(settings);
            assertTrue(windows(controller).containsKey(settings.getWindow().getDecorView()));
        }
        void returnHome() {
            Activity settings=settingsLifecycle.get();settingsLifecycle.pause();controller.onActivityPaused(settings);
            homeLifecycle.resume();controller.onActivityResumed(home);layoutHome();
            assertEquals(View.VISIBLE,home.getWindow().getDecorView().getWindowVisibility());
        }
        void layoutHome(){layout(home);}
        @Override public void close() {
            preferences.unregisterOnSharedPreferenceChangeListener(listener);controller.close();
            Configuration config=new Configuration(home.getResources().getConfiguration());config.uiMode=originalMode;home.getResources().updateConfiguration(config,home.getResources().getDisplayMetrics());
            if(settingsLifecycle!=null)settingsLifecycle.get().finish();home.finish();ShadowLooper.idleMainLooper();
        }
    }
}
