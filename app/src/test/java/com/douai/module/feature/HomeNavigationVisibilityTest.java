package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.view.View;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.LiquidGlassSettings;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew.NativeThemeTop;
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
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE) @LooperMode(LooperMode.Mode.PAUSED)
public class HomeNavigationVisibilityTest {
    private Object registry;private Map<String,Object> namesBefore;private String windowsBefore;
    @Before public void isolateProcessMetadataAndNativeWindows() {
        registry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        Map<String,Object> names=ReflectionHelpers.getField(registry,"observed");
        synchronized(registry){namesBefore=new LinkedHashMap<>(names);names.clear();}
        windowsBefore=System.getProperty("robolectric.areWindowsMarkedVisible");System.setProperty("robolectric.areWindowsMarkedVisible","true");
    }
    @After public void restoreProcessMetadataAndNativeWindows() {
        if(windowsBefore==null)System.clearProperty("robolectric.areWindowsMarkedVisible");else System.setProperty("robolectric.areWindowsMarkedVisible",windowsBefore);
        if(registry!=null && namesBefore!=null){Map<String,Object> names=ReflectionHelpers.getField(registry,"observed");synchronized(registry){names.clear();names.putAll(namesBefore);}}
    }

    @Test public void hideReleasesModuleArtworkAndShowingUsesTheUnchangedFourNativeStates() {
        try(Scene s=new Scene()) {
            Drawable visible=s.title.getCompoundDrawables()[1];assertTrue(visible instanceof BitmapDrawable);
            s.custom.setOptions(options(s.art,"[\"nav_top:homepage_hot\"]"));
            assertEquals(View.GONE,s.first.getVisibility());assertFalse(s.first.isEnabled());assertNull(s.title.getCompoundDrawables()[1]);
            assertTrue(((BitmapDrawable)visible).getBitmap().isRecycled());
            assertTrue(((Map<?,?>)ReflectionHelpers.getField(s.custom,"assets")).isEmpty());
            assertTrue(s.custom.labels(s.first).isEmpty());assertEquals("推荐",s.title.getText().toString());
            s.custom.setOptions(options(s.art,"[]"));
            assertEquals(View.VISIBLE,s.first.getVisibility());assertTrue(s.first.isEnabled());
            for(IconState state:IconState.values()) {
                s.first.r.selected=state.selected;s.first.r.A.dark=state.night;s.custom.sync();
                BitmapDrawable shown=(BitmapDrawable)s.title.getCompoundDrawables()[1];
                assertEquals(color(IconState.of(false,state.selected)),shown.getBitmap().getPixel(4,4));
                assertEquals(s.art,ReflectionHelpers.getField(s.custom,"rawJson"));
            }
        }
    }

    @Test public void hidingTheNativeSelectedOwnerDoesNotSelectAnotherTabOrLeaveAnIndicator() {
        try(Scene s=new Scene()) {
            s.first.r.selected=true;s.second.r.selected=false;s.custom.sync();
            Map<?,?> groups=ReflectionHelpers.getField(s.custom,"navigationGroups");assertEquals(1,groups.size());
            Object indicator=ReflectionHelpers.getField(groups.values().iterator().next(),"indicator");
            assertSame(s.first,ReflectionHelpers.getField(indicator,"target"));
            s.custom.setOptions(options(s.art,"[\"nav_top:homepage_hot\"]"));
            assertTrue(s.first.r.selected);assertFalse(s.second.r.selected);
            assertNull(ReflectionHelpers.getField(indicator,"target"));
            assertEquals(View.VISIBLE,s.second.getVisibility());assertTrue(s.second.isEnabled());
            s.first.setVisibility(View.VISIBLE);s.custom.sync();
            assertEquals("A native reshow cannot reopen the configured hidden click slot",View.GONE,s.first.getVisibility());
            s.custom.setOptions(options(s.art,"[]"));
            assertTrue(s.first.r.selected);assertFalse(s.second.r.selected);
        }
    }

    @Test public void invalidHiddenEditsRetainVisibilityAndAnInvalidArtworkEditCannotPreventShowing() {
        try(Scene s=new Scene()) {
            s.custom.setOptions(options(s.art,"[\"nav_top:homepage_hot\"]"));
            s.custom.setOptions(options(s.art,"[\"feed_like\"]"));
            assertEquals(View.GONE,s.first.getVisibility());
            s.custom.setOptions(options("invalid artwork","[]"));
            assertEquals(View.VISIBLE,s.first.getVisibility());assertTrue(s.first.isEnabled());
            assertEquals(s.art,ReflectionHelpers.getField(s.custom,"rawJson"));
            assertNotNull(s.title.getCompoundDrawables()[1]);
        }
    }

    @Test public void hiddenOnlySavedPositionsRemainEditableAndWindowCloseRestoresNativeVisibility() {
        assertTrue(HomeUiCustomization.availableTargets("{}","[\"nav_top:saved_dynamic\",\"nav_bottom:USER\"]").stream()
                .anyMatch(target->target.key.equals("nav_top:saved_dynamic") && target.title.contains("saved_dynamic")));
        assertTrue(HomeUiCustomization.availableTargets("{}","[\"nav_bottom:USER\"]").stream()
                .anyMatch(target->target.key.equals("nav_bottom:homepage_profile") && target.title.equals("下导航 · 我")));
        try(Scene s=new Scene()) {
            s.custom.setOptions(options(s.art,"[\"nav_top:homepage_hot\"]"));
            s.bar.removeView(s.first);s.custom.refresh();
            assertEquals(View.VISIBLE,s.first.getVisibility());assertTrue(s.first.isEnabled());
            s.bar.addView(s.first,new FrameLayout.LayoutParams(160,100));s.custom.refresh();assertEquals(View.GONE,s.first.getVisibility());
            s.custom.close();assertEquals(View.VISIBLE,s.first.getVisibility());assertTrue(s.first.isEnabled());
        }
    }

    @Test public void controllerThemeAndActivityTransitionsRestoreAndReapplyOnlyTheConfiguredItem() {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();MainTitleBar bar=new MainTitleBar(activity);
        TextView title=new TextView(activity);title.setText("推荐");NativeThemeTop item=new NativeThemeTop(activity,"homepage_hot",title);
        item.setClickable(true);bar.addView(item,new FrameLayout.LayoutParams(160,100));activity.setContentView(bar);
        View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(400,View.MeasureSpec.EXACTLY));decor.layout(0,0,600,400);
        String hidden="[\"nav_top:homepage_hot\"]";
        LocalOptions glass=new LocalOptions(Map.of(LiquidGlassSettings.STYLE,LiquidGlassSettings.LIQUID_GLASS,LiquidGlassSettings.GRAVITY,false,HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden));
        LiquidGlassController controller=new LiquidGlassController(activity,glass,null);
        try {
            controller.onActivityResumed(activity);assertEquals(View.GONE,item.getVisibility());assertFalse(item.isEnabled());
            controller.setOptions(new LocalOptions(Map.of(LiquidGlassSettings.STYLE,LiquidGlassSettings.ORIGINAL,HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden)));
            assertEquals(View.VISIBLE,item.getVisibility());assertTrue(item.isEnabled());assertTrue(item.isClickable());
            controller.setOptions(glass);assertEquals(View.GONE,item.getVisibility());
            controller.onActivityPaused(activity);assertEquals(View.VISIBLE,item.getVisibility());
            controller.onActivityResumed(activity);assertEquals(View.GONE,item.getVisibility());
            controller.close();assertEquals(View.VISIBLE,item.getVisibility());assertTrue(item.isClickable());
        } finally {controller.close();activity.finish();ShadowLooper.idleMainLooper();}
    }

    private static LocalOptions options(String art,String hidden){return new LocalOptions(Map.of(HomeUiCustomizationSettings.ICONS,art,HomeUiCustomizationSettings.HIDDEN_NAVIGATION,hidden));}
    private static int color(IconState state){return new int[]{Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW}[state.ordinal()];}
    private static String artwork() {
        String[] encoded={"UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChA/wvQ/wIAAA==","UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChAAfrf/wIAAA==",
                "UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh/AQrQ/wIAAA==","UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh//wvQ/wIAAA=="};
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);for(IconState state:IconState.values())variants.put(state,Base64.getDecoder().decode(encoded[state.ordinal()]));
        return HomeUiCustomizationSettings.putIcons("{}","nav_top:homepage_hot",variants);
    }
    private static final class Scene implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final MainTitleBar bar=new MainTitleBar(activity);final TextView title=new TextView(activity);
        final NativeThemeTop first,second;final String art=artwork();final HomeUiCustomization custom;
        Scene() {
            title.setText("推荐");first=new NativeThemeTop(activity,"homepage_hot",title);first.setClickable(true);
            TextView other=new TextView(activity);other.setText("关注");second=new NativeThemeTop(activity,"homepage_follow",other);second.setClickable(true);
            bar.addView(first,new FrameLayout.LayoutParams(160,100));FrameLayout.LayoutParams right=new FrameLayout.LayoutParams(160,100);right.leftMargin=180;bar.addView(second,right);
            activity.setContentView(bar);bar.measure(View.MeasureSpec.makeMeasureSpec(380,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(120,View.MeasureSpec.EXACTLY));bar.layout(0,0,380,120);
            custom=new HomeUiCustomization(bar,options(art,"[]"),null);
        }
        @Override public void close(){custom.close();activity.finish();ShadowLooper.idleMainLooper();}
    }
}
