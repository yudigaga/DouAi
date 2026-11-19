package com.douai.module.feature;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Matrix;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.provider.Settings;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew.NativeThemeTop;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import java.util.EnumMap;
import java.util.HashMap;
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
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk={28,35})
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeNavigationIndicatorIntegrationTest {
    private String oldVisible,oldScale;
    private float oldPlatformScale;
    @Before public void visibleWindowsAndAnimations() {
        oldVisible=System.getProperty("robolectric.areWindowsMarkedVisible");
        System.setProperty("robolectric.areWindowsMarkedVisible","true");
        oldScale=Settings.Global.getString(org.robolectric.RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE);
        oldPlatformScale=ReflectionHelpers.callStaticMethod(ValueAnimator.class,"getDurationScale");
        Settings.Global.putFloat(org.robolectric.RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,1f);
        ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,1f));
    }
    @After public void restorePreferences() {
        try {
            Settings.Global.putString(org.robolectric.RuntimeEnvironment.getApplication().getContentResolver(),Settings.Global.ANIMATOR_DURATION_SCALE,oldScale);
            ReflectionHelpers.callStaticMethod(ValueAnimator.class,"setDurationScale",ReflectionHelpers.ClassParameter.from(float.class,oldPlatformScale));
        } finally {
            if(oldVisible==null)System.clearProperty("robolectric.areWindowsMarkedVisible");else System.setProperty("robolectric.areWindowsMarkedVisible",oldVisible);
        }
    }

    @Test public void rawBottomTypesApplyIconsImmediatelyWhileOneSharedIndicatorCrossesItems() throws Exception {
        try(Scene s=new Scene()) {
            String json="{}";
            for(String id:Scene.IDS)json=HomeUiCustomizationSettings.putIcons(json,"nav_bottom:"+id,fourIcons());
            s.tabs[0].setSelected(true);
            try(HomeUiCustomization custom=new HomeUiCustomization(s.root,options(json),null)) {
                NavigationSelectionIndicator indicator=indicator(custom,s.bottom);
                assertEquals(1,groups(custom).size());
                assertEquals(Color.BLUE,icon(s.tabs[0]));assertEquals(Color.RED,icon(s.tabs[3]));
                RectF left=indicator.bounds();assertFalse(left.isEmpty());
                s.tabs[0].setSelected(false);s.tabs[3].setSelected(true);custom.sync();
                assertSame(indicator,indicator(custom,s.bottom));assertTrue(indicator.isRunning());
                assertEquals(Color.RED,icon(s.tabs[0]));assertEquals(Color.BLUE,icon(s.tabs[3]));
                assertEquals(left,indicator.bounds());
                ValueAnimator animation=ReflectionHelpers.getField(indicator,"animator");
                animation.setCurrentPlayTime(130);RectF middle=indicator.bounds();
                assertTrue(middle.centerX()>left.centerX());assertTrue(middle.centerX()<s.tabs[3].getRight());
                animation.end();RectF right=indicator.bounds();assertTrue(middle.centerX()<right.centerX());
                assertFalse(indicator.isRunning());
                for(int i=0;i<s.tabs.length;i++) {
                    assertSame(s.foregrounds[i],s.tabs[i].getForeground());
                    assertSame(s.tabs[i],s.foregrounds[i].getCallback());
                    assertEquals(i*120,s.tabs[i].getLeft());assertEquals(100,s.tabs[i].getWidth());
                }
            }
        }
    }

    @Test public void topAndBottomGroupsAreIndependentAndUnknownSingleOwnersKeepTheirFallback() throws Exception {
        try(Scene s=new Scene()) {
            MainTitleBar top=new MainTitleBar(s.activity);s.root.addView(top,new FrameLayout.LayoutParams(480,80));
            NativeThemeTop first=top(s.activity,"one"),second=top(s.activity,"two");
            add(top,first,0);add(top,second,160);first.r.selected=true;s.tabs[0].setSelected(true);s.layout();
            try(HomeUiCustomization custom=new HomeUiCustomization(s.root,options("{}"),null)) {
                assertEquals(2,groups(custom).size());
                NavigationSelectionIndicator upper=indicator(custom,top),lower=indicator(custom,s.bottom);
                RectF bottom=lower.bounds();first.r.selected=false;second.r.selected=true;custom.sync();
                assertTrue(upper.isRunning());assertFalse(lower.isRunning());assertEquals(bottom,lower.bounds());
                assertNull(first.getForeground());assertNull(second.getForeground());
            }
            FrameLayout isolatedRoot=new FrameLayout(s.activity);BottomTab isolated=new BottomTab(s.activity,"HOME","首页");
            isolatedRoot.addView(isolated,new FrameLayout.LayoutParams(120,80));s.activity.setContentView(isolatedRoot);
            isolatedRoot.measure(exact(480),exact(640));isolatedRoot.layout(0,0,480,640);
            try(HomeUiCustomization custom=new HomeUiCustomization(isolatedRoot,options("{}"),null)) {
                assertTrue(groups(custom).isEmpty());assertTrue(isolated.getForeground() instanceof android.graphics.drawable.LayerDrawable);
            }
        }
    }

    @Test public void oneNativeSnapshotFeedsTitleIconAndIndicatorDuringEachSync() throws Exception {
        try(Scene s=new Scene()) {
            MainTitleBar top=new MainTitleBar(s.activity);s.root.addView(top,new FrameLayout.LayoutParams(480,80));
            NativeThemeTop tab=top(s.activity,"推荐"),other=top(s.activity,"关注");add(top,tab,0);add(top,other,160);
            tab.r.selected=true;s.layout();
            String json=HomeUiCustomizationSettings.putIcons("{}","nav_top:homepage_hot",fourIcons());
            try(HomeUiCustomization custom=new HomeUiCustomization(s.root,options(json),null)) {
                tab.delegateReads=0;tab.r.selectionReads=0;tab.r.A.themeReads=0;
                custom.sync();
                assertEquals(1,tab.delegateReads);assertEquals(1,tab.r.selectionReads);assertEquals(1,tab.r.A.themeReads);
                assertEquals(Color.BLUE,draw(((TextView)tab.getContentView()).getCompoundDrawables()[1]));
                tab.r.A.dark=true;tab.r.selected=false;custom.sync();
                assertEquals(2,tab.delegateReads);assertEquals(2,tab.r.selectionReads);assertEquals(2,tab.r.A.themeReads);
                // Theme changes retain shared normal artwork; legacy night artwork is compatibility data only.
                assertEquals(Color.RED,draw(((TextView)tab.getContentView()).getCompoundDrawables()[1]));
            }
        }
    }

    @Test public void deferredControllerConfigurationDiscoversTheLiveNavigationOnce() throws Exception {
        try(Scene s=new Scene()) {
            MainTitleBar top=new MainTitleBar(s.activity);s.root.addView(top,new FrameLayout.LayoutParams(480,80));
            NativeThemeTop tab=top(s.activity,"推荐");add(top,tab,0);s.layout();
            LiquidGlassController controller=new LiquidGlassController(s.activity,options("{}"),null);
            try {
                controller.onActivityResumed(s.activity);tab.idReads=0;
                String json=HomeUiCustomizationSettings.putIcon("{}","nav_top:homepage_hot",webp(Color.CYAN));
                controller.setOptions(options(json));
                assertEquals("A configuration publication must not discover the same window twice",1,tab.idReads);
                assertEquals(Color.CYAN,draw(((TextView)tab.getContentView()).getCompoundDrawables()[1]));
            } finally {controller.close();}
        }
    }

    @Test public void removingTheHostGroupAndClosingCannotReattachItsIndicatorOrStealForeground() throws Exception {
        try(Scene s=new Scene()) {
            s.tabs[0].setSelected(true);HomeUiCustomization custom=new HomeUiCustomization(s.root,options("{}"),null);
            NavigationSelectionIndicator indicator=indicator(custom,s.bottom);
            s.tabs[0].setSelected(false);s.tabs[3].setSelected(true);custom.sync();
            ValueAnimator old=ReflectionHelpers.getField(indicator,"animator");assertNotNull(old);
            s.root.removeView(s.bottom);custom.sync();assertFalse(indicator.isRunning());
            assertTrue(indicator.bounds().isEmpty());
            custom.close();old.end();assertFalse(indicator.isRunning());assertTrue(indicator.bounds().isEmpty());
            for(int i=0;i<s.tabs.length;i++)assertSame(s.foregrounds[i],s.tabs[i].getForeground());
        }
    }

    @Test public void enablingTheThemeDoesNotDiscoverNewlyAttachedWindowsTwice() throws Exception {
        try(Scene s=new Scene()) {
            MainTitleBar top=new MainTitleBar(s.activity);s.root.addView(top,new FrameLayout.LayoutParams(480,80));
            NativeThemeTop tab=top(s.activity,"推荐");add(top,tab,0);s.layout();
            LiquidGlassController controller=new LiquidGlassController(s.activity,new LocalOptions(Map.of(LiquidGlassSettings.STYLE,LiquidGlassSettings.ORIGINAL)),null);
            try {
                controller.onActivityResumed(s.activity);tab.idReads=0;
                String json=HomeUiCustomizationSettings.putIcon("{}","nav_top:homepage_hot",webp(Color.CYAN));
                controller.setOptions(options(json));
                assertEquals("A newly attached window already consumed the enabling snapshot",1,tab.idReads);
                assertEquals(Color.CYAN,draw(((TextView)tab.getContentView()).getCompoundDrawables()[1]));
            } finally {controller.close();}
        }
    }

    @Test public void zeroOrMultipleNativeSelectionsHideTheSharedIndicatorUntilTheOwnerIsUnique() throws Exception {
        try(Scene s=new Scene();HomeUiCustomization custom=new HomeUiCustomization(s.root,options("{}"),null)) {
            NavigationSelectionIndicator indicator=indicator(custom,s.bottom);assertTrue(indicator.bounds().isEmpty());
            s.tabs[0].setSelected(true);s.tabs[1].setSelected(true);custom.sync();
            assertTrue(indicator.bounds().isEmpty());assertFalse(indicator.isRunning());
            s.tabs[1].setSelected(false);custom.sync();assertFalse(indicator.bounds().isEmpty());assertFalse(indicator.isRunning());
            s.tabs[1].setSelected(true);custom.sync();assertTrue(indicator.bounds().isEmpty());
            s.tabs[0].setSelected(false);custom.sync();
            assertFalse(indicator.bounds().isEmpty());assertTrue(indicator.bounds().centerX()>s.tabs[0].getRight());
            assertFalse(indicator.isRunning());
        }
    }

    @Test public void protectedAnimatedIconLeavesIndividualGravityTargetsWithoutCancellingTheSharedTravel() throws Exception {
        try(Scene s=new Scene()) {
            for(BottomTab tab:s.tabs)tab.setClickable(true);
            RunningIcon running=new RunningIcon();ImageView animation=new ImageView(s.activity);animation.setImageDrawable(running);
            s.tabs[0].addView(animation,new FrameLayout.LayoutParams(20,20));s.tabs[1].setSelected(true);s.layout();
            java.util.List<View> targets=LiquidGlassTargets.motionTargets(s.root);
            assertFalse(targets.contains(s.bottom));assertFalse(targets.contains(s.tabs[0]));assertFalse(targets.contains(animation));
            assertTrue("The actual selector must enter the individual-tab branch",targets.contains(s.tabs[3]));
            try(HomeUiCustomization custom=new HomeUiCustomization(s.root,options("{}"),null);
                LiquidGlassMotion motion=new LiquidGlassMotion(s.activity)) {
                motion.setTargets(targets,s.root,null);
                NavigationSelectionIndicator indicator=indicator(custom,s.bottom);
                s.tabs[1].setSelected(false);s.tabs[3].setSelected(true);custom.sync();
                ValueAnimator moving=ReflectionHelpers.getField(indicator,"animator");assertNotNull(moving);moving.setCurrentPlayTime(80);
                motion.applyFrame(new LiquidGlassMotion.Vector(.4f,.25f));
                assertTrue(Math.abs(s.tabs[3].getTranslationX())>0f || Math.abs(s.tabs[3].getTranslationY())>0f);
                custom.sync();assertSame(moving,ReflectionHelpers.getField(indicator,"animator"));assertTrue(indicator.isRunning());
                moving.setCurrentPlayTime(140);motion.applyFrame(new LiquidGlassMotion.Vector(-.3f,-.2f));
                custom.sync();assertSame(moving,ReflectionHelpers.getField(indicator,"animator"));assertTrue(indicator.isRunning());
                moving.end();assertFalse(indicator.isRunning());assertRect(mappedBounds(s.tabs[3],s.bottom),indicator.bounds());
                assertTrue(running.isRunning());assertSame(running,animation.getDrawable());
            }
            assertEquals(0f,s.tabs[3].getTranslationX(),0f);assertEquals(0f,s.tabs[3].getTranslationY(),0f);
            assertTrue(running.isRunning());
        }
    }

    private static Map<?,?> groups(HomeUiCustomization custom){return ReflectionHelpers.getField(custom,"navigationGroups");}
    private static NavigationSelectionIndicator indicator(HomeUiCustomization custom,View host) {
        Object group=groups(custom).get(host);assertNotNull(group);return ReflectionHelpers.getField(group,"indicator");
    }
    private static NativeThemeTop top(Activity activity,String text){TextView title=new TextView(activity);title.setText(text);return new NativeThemeTop(activity,text.equals("two") || text.equals("关注")?"homepage_follow":"homepage_hot",title);}
    private static void add(FrameLayout host,View child,int x){FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(100,70);p.leftMargin=x;host.addView(child,p);}
    private static int icon(BottomTab tab){return draw(tab.getTabTitle().getCompoundDrawables()[1]);}
    private static int draw(Drawable drawable){assertNotNull(drawable);Bitmap bitmap=Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888);android.graphics.Rect bounds=new android.graphics.Rect(drawable.getBounds());try{drawable.setBounds(0,0,8,8);drawable.draw(new Canvas(bitmap));return bitmap.getPixel(4,4);}finally{drawable.setBounds(bounds);bitmap.recycle();}}
    private static Map<IconState,byte[]> fourIcons(){Map<IconState,byte[]> result=new EnumMap<>(IconState.class);int[] colors={Color.RED,Color.BLUE,Color.GREEN,Color.YELLOW};for(IconState state:IconState.values())result.put(state,webp(colors[state.ordinal()]));return result;}
    /** Fixed lossless pixels keep SDK28 coverage independent of the API30 encoder enum. */
    private static byte[] webp(int color) {
        String encoded;
        switch(color) {
            case Color.RED:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChA/wvQ/wIAAA==";break;
            case Color.BLUE:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAChAAfrf/wIAAA==";break;
            case Color.GREEN:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh/AQrQ/wIAAA==";break;
            case Color.YELLOW:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh//wvQ/wIAAA==";break;
            case Color.CYAN:encoded="UklGRhoAAABXRUJQVlA4TA0AAAAvB8ABAOh/Afrf/wIAAA==";break;
            default:throw new AssertionError("No encoded fixture for color "+color);
        }
        return java.util.Base64.getDecoder().decode(encoded);
    }
    private static LocalOptions options(String json){Map<String,Object> map=new HashMap<>();map.put(LiquidGlassSettings.STYLE,LiquidGlassSettings.LIQUID_GLASS);map.put(LiquidGlassSettings.GRAVITY,false);map.put(HomeUiCustomizationSettings.ICONS,json);return new LocalOptions(map);}
    private static int exact(int n){return View.MeasureSpec.makeMeasureSpec(n,View.MeasureSpec.EXACTLY);}
    private static RectF mappedBounds(View target,View host) {
        RectF rect=new RectF(target.getWidth()*.04f,target.getHeight()*.08f,target.getWidth()*.96f,target.getHeight()*.92f);
        Matrix targetGlobal=new Matrix(),hostGlobal=new Matrix(),inverse=new Matrix();
        ReflectionHelpers.callInstanceMethod(target,"transformMatrixToGlobal",ReflectionHelpers.ClassParameter.from(Matrix.class,targetGlobal));
        ReflectionHelpers.callInstanceMethod(host,"transformMatrixToGlobal",ReflectionHelpers.ClassParameter.from(Matrix.class,hostGlobal));
        assertTrue(hostGlobal.invert(inverse));targetGlobal.postConcat(inverse);targetGlobal.mapRect(rect);return rect;
    }
    private static void assertRect(RectF expected,RectF actual){assertEquals(expected.left,actual.left,.05f);assertEquals(expected.top,actual.top,.05f);assertEquals(expected.right,actual.right,.05f);assertEquals(expected.bottom,actual.bottom,.05f);}
    private static final class RunningIcon extends Drawable implements Animatable {
        @Override public boolean isRunning(){return true;}
        @Override public void start(){}
        @Override public void stop(){throw new AssertionError("The protected animation must remain host-owned");}
        @Override public void draw(Canvas canvas){}
        @Override public void setAlpha(int alpha){}
        @Override public void setColorFilter(ColorFilter filter){}
        @Override public int getOpacity(){return PixelFormat.TRANSPARENT;}
    }
    private static final class Scene implements AutoCloseable {
        static final String[] IDS={"homepage_home","homepage_familiar","homepage_notification","homepage_profile"};
        static final String[] RAW={"HOME","FAMILIAR","NOTIFICATION","USER"};
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity),bottom;
        final BottomTab[] tabs=new BottomTab[4];final Drawable[] foregrounds=new Drawable[4];
        Scene() throws Exception {
            bottom=(FrameLayout)ReflectionHelpers.callConstructor(Class.forName("X.1JgG"),ReflectionHelpers.ClassParameter.from(Context.class,activity));
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(480,100);p.topMargin=500;root.addView(bottom,p);
            for(int i=0;i<tabs.length;i++){tabs[i]=new BottomTab(activity,RAW[i],IDS[i]);assertEquals(RAW[i],tabs[i].getTabType());foregrounds[i]=new ColorDrawable(Color.DKGRAY);tabs[i].setForeground(foregrounds[i]);add(bottom,tabs[i],i*120);}
            activity.setContentView(root);layout();assertTrue(bottom.isAttachedToWindow());assertEquals(View.VISIBLE,bottom.getWindowVisibility());
        }
        void layout(){root.measure(exact(500),exact(700));root.layout(0,0,500,700);}
        @Override public void close(){activity.finish();}
    }
}
