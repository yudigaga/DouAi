package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.view.View;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiIconFixtures;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import java.time.Duration;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassControllerTest {
    private static LocalOptions options(boolean on) {return options(on,false,"{}",false);}
    private static LocalOptions options(boolean on,boolean gravity,String icons,boolean hide) {
        Map<String,Object> values=new HashMap<>();values.put("theme_style",on?"liquid_glass":"original");
        values.put("theme_gravity_enabled",gravity);values.put(HomeUiCustomizationSettings.ICONS,icons);
        values.put(HomeUiCustomizationSettings.HIDE_LABELS,hide);return new LocalOptions(values);
    }
    @Test public void enablingDecoratesOnlyNavigationAndDisablingRestoresItsLayoutAndHitState() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        LiquidGlassController controller=new LiquidGlassController(a,options(false),null);
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,48);root.addView(navigation,params);
            Drawable original=new ColorDrawable(0xFF222222);navigation.setBackground(original);navigation.setPadding(3,4,5,6);
            navigation.setClickable(true);navigation.setAlpha(.65f);
            a.setContentView(root);layout(a.getWindow().getDecorView());controller.onActivityResumed(a);
            assertSame(original,navigation.getBackground());controller.setOptions(options(true));layout(a.getWindow().getDecorView());
            assertNotSame(original,navigation.getBackground());assertTrue(navigation.isClickable());
            assertEquals(.65f,navigation.getAlpha(),0);assertTrue(navigation.getHeight()>=64);
            assertTrue(navigation.getWidth()<root.getWidth());controller.setOptions(options(false));
            assertSame(original,navigation.getBackground());assertEquals(-1,params.width);assertEquals(48,params.height);
            assertEquals(3,navigation.getPaddingLeft());assertEquals(4,navigation.getPaddingTop());
            assertEquals(5,navigation.getPaddingRight());assertEquals(6,navigation.getPaddingBottom());
            assertEquals(View.VISIBLE,navigation.getVisibility());assertTrue(navigation.isClickable());
        } finally {controller.close();a.finish();}
    }
    @Test public void hostOwnedBackgroundAndLayoutReplacementSurvivePauseAndClose() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        LiquidGlassController controller=new LiquidGlassController(a,options(true),null);
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,48);root.addView(navigation,params);a.setContentView(root);
            layout(a.getWindow().getDecorView());controller.onActivityResumed(a);
            Drawable host=new ColorDrawable(0xFF123456);navigation.setBackground(host);
            params.height=100;params.rightMargin=9;navigation.setPadding(13,14,15,16);
            controller.onActivityPaused(a);controller.close();
            assertSame(host,navigation.getBackground());assertEquals(100,params.height);assertEquals(9,params.rightMargin);
            assertEquals(13,navigation.getPaddingLeft());assertEquals(14,navigation.getPaddingTop());
            assertEquals(15,navigation.getPaddingRight());assertEquals(16,navigation.getPaddingBottom());
        } finally {controller.close();a.finish();}
    }
    @Test public void knownNavigationInAnOpenWindowRestoresAndReattachesAfterPauseResume() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        LiquidGlassController controller=new LiquidGlassController(a,options(true),null);
        android.app.Dialog dialog=new android.app.Dialog(a);
        try {
            controller.onActivityResumed(a);FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            Drawable original=new ColorDrawable(0xFF222222);navigation.setBackground(original);
            root.addView(navigation,new FrameLayout.LayoutParams(-1,48));Button button=new Button(a);button.setText("分享");
            Drawable buttonBackground=button.getBackground();root.addView(button,new FrameLayout.LayoutParams(110,48));
            dialog.setContentView(root,new android.view.ViewGroup.LayoutParams(360,800));dialog.show();
            layout(dialog.getWindow().getDecorView());controller.onWindow(dialog.getWindow().getDecorView());
            assertNotSame(original,navigation.getBackground());assertSame(buttonBackground,button.getBackground());
            controller.onActivityPaused(a);assertSame(original,navigation.getBackground());
            controller.onActivityResumed(a);assertNotSame(original,navigation.getBackground());
            assertSame(buttonBackground,button.getBackground());
            controller.onWindowClosed(dialog.getWindow().getDecorView());assertSame(original,navigation.getBackground());
        } finally {controller.close();dialog.dismiss();a.finish();}
    }
    @Test @Config(sdk=35,qualifiers="w360dp-h800dp-mdpi")
    public void ordinaryControlsFollowNavigationWhileTheirMaterialAndTheInputStayNative() {
        ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup();Activity a=lifecycle.get();
        ShadowSensorManager sensors=Shadow.extract((SensorManager)RuntimeEnvironment.getApplication().getSystemService(android.content.Context.SENSOR_SERVICE));
        Sensor gravity=ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);sensors.addSensor(gravity);
        LiquidGlassController controller=new LiquidGlassController(a,options(true,true,"{}",false),null);
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            root.addView(navigation,new FrameLayout.LayoutParams(-1,48));
            FrameLayout like=new FrameLayout(a);like.setId(0x7f0a30de);
            TextView description=new TextView(a);description.setText("视频描述");
            ImageView image=new ImageView(a);image.setClickable(true);
            View[] controls={like,new Button(a),new EditText(a),description,image};
            Drawable[] originals=new Drawable[controls.length];
            for(int i=0;i<controls.length;i++) {
                View control=controls[i];originals[i]=new ColorDrawable(Color.DKGRAY);control.setBackground(originals[i]);
                control.setTranslationX(7);control.setTranslationY(-3);
                FrameLayout.LayoutParams placement=new FrameLayout.LayoutParams(110,48);
                placement.leftMargin=30;placement.topMargin=120+i*90;root.addView(control,placement);
            }
            a.setContentView(root);lifecycle.visible().windowFocusChanged(true);
            ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
            layout(a.getWindow().getDecorView());controller.onActivityResumed(a);
            SensorEvent sample=ShadowSensorManager.createSensorEvent(3);sample.sensor=gravity;sample.timestamp=100_000_000L;
            sample.values[2]=9.80665f;sensors.sendSensorEventToListeners(sample);
            sample=ShadowSensorManager.createSensorEvent(3);sample.sensor=gravity;sample.timestamp=200_000_000L;
            sample.values[0]=-9.80665f;sample.values[2]=9.80665f;sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertTrue("The navigation must actually move to exercise controller target selection",navigation.getTranslationX()>0);
            for(int i=0;i<controls.length;i++) {
                assertSame("Only navigation receives glass material",originals[i],controls[i].getBackground());
                if(controls[i] instanceof android.widget.EditText || controls[i]==like) {
                    assertEquals(7f,controls[i].getTranslationX(),0);assertEquals(-3f,controls[i].getTranslationY(),0);
                }else assertTrue("Visible native controls follow the same tilt: "+controls[i].getClass().getSimpleName(),
                        controls[i].getTranslationX()>7);
            }
            controller.setOptions(options(false));assertEquals(0f,navigation.getTranslationX(),0);
            for(View control:controls){assertEquals(7f,control.getTranslationX(),0);assertEquals(-3f,control.getTranslationY(),0);}
        } finally {controller.close();a.finish();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void hotIconAndLabelOptionsKeepBusinessClicksAndRestoreTheOriginalNavigationPresentation() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        TextView title=new TextView(a);title.setText("推荐");title.setTextSize(18);title.setIncludeFontPadding(true);
        float originalSize=title.getTextSize();AtomicInteger clicks=new AtomicInteger();title.setOnClickListener(v->clicks.incrementAndGet());
        Drawable originalIcon=new ColorDrawable(Color.RED);originalIcon.setBounds(0,0,4,4);
        title.setCompoundDrawables(null,originalIcon,null,null);title.setCompoundDrawablePadding(7);
        MainTitleBar navigation=new MainTitleBar(a);navigation.addView(new HomeTopTabItemViewNew(a,"homepage_hot",title));
        FrameLayout root=new FrameLayout(a);root.addView(navigation,new FrameLayout.LayoutParams(-1,48));
        ImageView feedLike=new ImageView(a);feedLike.setId(0x7f0a30ca);feedLike.setScaleType(ImageView.ScaleType.CENTER_CROP);
        Drawable originalFeed=new ColorDrawable(Color.MAGENTA);feedLike.setImageDrawable(originalFeed);
        FrameLayout.LayoutParams feedParams=new FrameLayout.LayoutParams(83,83,Gravity.BOTTOM|Gravity.RIGHT);
        root.addView(feedLike,feedParams);a.setContentView(root);
        Drawable background=new ColorDrawable(Color.BLACK);navigation.setBackground(background);layout(a.getWindow().getDecorView());
        String icons=HomeUiCustomizationSettings.putIcon("{}","nav_top:homepage_hot",HomeUiIconFixtures.webp(8,8,Color.BLUE));
        icons=HomeUiCustomizationSettings.putIcon(icons,"feed_like",HomeUiIconFixtures.webp(8,8,Color.GREEN));
        LiquidGlassController controller=new LiquidGlassController(a,options(false,false,icons,true),null);
        try {
            controller.onActivityResumed(a);assertSame(originalIcon,title.getCompoundDrawables()[1]);
            controller.setOptions(options(true,false,icons,true));layout(a.getWindow().getDecorView());
            Drawable imported=title.getCompoundDrawables()[1];assertNotSame(originalIcon,imported);
            Drawable feedImported=feedLike.getDrawable();assertNotSame(originalFeed,feedImported);
            assertEquals(24,feedImported.getIntrinsicWidth());assertEquals(ImageView.ScaleType.CENTER,feedLike.getScaleType());
            assertSame(feedParams,feedLike.getLayoutParams());assertEquals(83,feedParams.width);assertEquals(83,feedParams.height);
            assertEquals(0,title.getTextSize(),0);assertFalse(title.getIncludeFontPadding());
            assertEquals("推荐",title.getText().toString());assertTrue(title.performClick());assertEquals(1,clicks.get());
            controller.setOptions(options(true,false,icons,false));layout(a.getWindow().getDecorView());
            assertTrue(title.getTextSize()>0);assertTrue(title.getTextSize()<originalSize);
            assertSame(imported,title.getCompoundDrawables()[1]);
            controller.setOptions(options(false,false,icons,true));
            assertSame(background,navigation.getBackground());assertSame(originalIcon,title.getCompoundDrawables()[1]);
            assertSame(originalFeed,feedLike.getDrawable());assertEquals(ImageView.ScaleType.CENTER_CROP,feedLike.getScaleType());
            assertSame(feedParams,feedLike.getLayoutParams());assertEquals(83,feedParams.width);assertEquals(83,feedParams.height);
            assertEquals(originalSize,title.getTextSize(),0);assertEquals(7,title.getCompoundDrawablePadding());assertTrue(title.getIncludeFontPadding());
            controller.setOptions(options(true,false,icons,true));assertEquals(0,title.getTextSize(),0);
            controller.onActivityPaused(a);assertSame(originalIcon,title.getCompoundDrawables()[1]);assertEquals(originalSize,title.getTextSize(),0);
            assertSame(originalFeed,feedLike.getDrawable());assertEquals(ImageView.ScaleType.CENTER_CROP,feedLike.getScaleType());
            controller.onActivityResumed(a);assertEquals(0,title.getTextSize(),0);assertEquals("推荐",title.getText().toString());
            assertNotSame(originalFeed,feedLike.getDrawable());assertEquals(ImageView.ScaleType.CENTER,feedLike.getScaleType());
            controller.close();assertSame(originalIcon,title.getCompoundDrawables()[1]);assertEquals(originalSize,title.getTextSize(),0);
            assertSame(originalFeed,feedLike.getDrawable());assertEquals(ImageView.ScaleType.CENTER_CROP,feedLike.getScaleType());
            assertSame(feedParams,feedLike.getLayoutParams());
        } finally {controller.close();a.finish();}
    }
    @Test public void preDrawHostTextFailureReleasesTheNavigationWithoutEscapingIntoTheHost() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        X.ThrowingLabel title=new X.ThrowingLabel(a);title.setText("推荐");title.setTextSize(18);
        MainTitleBar navigation=new MainTitleBar(a);navigation.addView(new HomeTopTabItemViewNew(a,"homepage_hot",title));
        Drawable original=new ColorDrawable(Color.BLACK);navigation.setBackground(original);
        FrameLayout root=new FrameLayout(a);root.addView(navigation,new FrameLayout.LayoutParams(-1,48));a.setContentView(root);
        layout(a.getWindow().getDecorView());List<String> messages=new ArrayList<>();
        LiquidGlassController controller=new LiquidGlassController(a,options(true),messages::add);
        try {
            controller.onActivityResumed(a);assertNotSame(original,navigation.getBackground());
            title.hostSizeThenRejectFurtherStyle();
            a.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
            assertSame("A failing host setter must release the navigation material",original,navigation.getBackground());
            assertEquals(20f,title.getTextSize(),0);
            assertTrue(messages.stream().anyMatch(message->message.contains("window_disabled=IllegalStateException")));
        } finally {controller.close();a.finish();}
    }
    @Test @Config(sdk=35,qualifiers="w360dp-h320dp-mdpi")
    public void aShortViewportKeepsRejectedBottomGlassStableUntilTheViewportChanges() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root=new FrameLayout(a),bottom=bottom(a);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,48,Gravity.BOTTOM);root.addView(bottom,params);
        Drawable original=new ColorDrawable(Color.BLACK);bottom.setBackground(original);a.setContentView(root);
        View decor=a.getWindow().getDecorView();layout(decor,360,320);
        int originalWrites=(Integer)bottom.getClass().getMethod("getBackgroundWrites").invoke(bottom);
        List<String> observations=new ArrayList<>();
        LiquidGlassController controller=new LiquidGlassController(a,options(true),observations::add);
        try {
            controller.onActivityResumed(a);assertNotSame(original,bottom.getBackground());
            for(int i=0;i<8;i++) {
                layout(decor,360,320);decor.getViewTreeObserver().dispatchOnPreDraw();
                decor.getViewTreeObserver().dispatchOnGlobalLayout();ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));
                assertEquals("The real window keeps the short viewport throughout delayed scans",320,decor.getHeight());
            }
            layout(decor,360,320);decor.getViewTreeObserver().dispatchOnPreDraw();
            assertSame("A content-sized capsule that exceeds the viewport guard must fall back once",original,bottom.getBackground());
            assertEquals("Fallback restores the host LayoutParams height",48,params.height);
            layout(decor,360,320);
            assertEquals("The next layout measures the restored host height",48,bottom.getHeight());
            assertEquals("Repeated layout scans must not alternate apply/restore",originalWrites+2,
                    (int)(Integer)bottom.getClass().getMethod("getBackgroundWrites").invoke(bottom));
            RuntimeEnvironment.setQualifiers("w360dp-h800dp-mdpi");
            a.getWindow().setLayout(360,800);
            layout(decor,360,800);decor.getViewTreeObserver().dispatchOnGlobalLayout();
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));
            assertEquals("WindowManager completes the larger viewport before scanning: "+observations,800,decor.getHeight());
            layout(decor,360,800);decor.getViewTreeObserver().dispatchOnPreDraw();
            decor.getViewTreeObserver().dispatchOnGlobalLayout();ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));
            layout(decor,360,800);
            assertNotSame("A larger viewport may retry navigation glass: "+observations,original,bottom.getBackground());
            assertTrue(bottom.getHeight()>=72);
        } finally {controller.close();a.finish();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void bottomOwnerKeepsFiveClickableIconSlotsAndRestoresTheLatestHostSkin() throws Exception {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout root=new FrameLayout(a),bottom=bottom(a);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,48,Gravity.BOTTOM);root.addView(bottom,params);
        ImageView background=new ImageView(a);background.setId(0x7f0a1435);
        Drawable skin=new ColorDrawable(Color.DKGRAY),image=new ColorDrawable(Color.GRAY);
        background.setBackground(skin);background.setImageDrawable(image);bottom.addView(background,new FrameLayout.LayoutParams(-1,-1));
        LinearLayout row=new LinearLayout(a);row.setId(0x7f0a7983);row.setOrientation(LinearLayout.HORIZONTAL);
        bottom.addView(row,new FrameLayout.LayoutParams(-1,-1));AtomicInteger clicks=new AtomicInteger();
        String icons="{}";String[] ids={"homepage_home","homepage_familiar","preview_publish","homepage_notification","homepage_profile"};
        for(int i=0;i<5;i++) {
            BottomTab tab=new BottomTab(a,ids[i],new String[]{"首页","朋友","发布","消息","我"}[i]);
            tab.getTabTitle().setTextSize(16);tab.getTabTitle().setLayoutParams(new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
            tab.setOnClickListener(view->clicks.incrementAndGet());row.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
            icons=HomeUiCustomizationSettings.putIcon(icons,"nav_bottom:"+ids[i],HomeUiIconFixtures.webp(8,8,Color.BLUE));
        }
        a.setContentView(root);layout(a.getWindow().getDecorView());
        assertEquals(Boolean.TRUE,LiquidGlassTargets.find(root).get(bottom));
        LiquidGlassController controller=new LiquidGlassController(a,options(true,false,icons,false),null);
        try {
            controller.onActivityResumed(a);layout(a.getWindow().getDecorView());
            assertNotNull(bottom.getBackground());assertTrue(bottom.getWidth()<root.getWidth());assertTrue(bottom.getHeight()>=72);
            assertNotSame(skin,background.getBackground());assertSame(image,background.getDrawable());
            int width=row.getChildAt(0).getWidth();
            for(int i=0;i<5;i++) {
                BottomTab tab=(BottomTab)row.getChildAt(i);TextView title=tab.getTabTitle();
                assertTrue(Math.abs(width-tab.getWidth())<=1);assertTrue(tab.performClick());
                assertNotNull(title.getCompoundDrawables()[1]);assertTrue(title.getTextSize()<=11);
                Rect bounds=new Rect(0,0,title.getWidth(),title.getHeight());bottom.offsetDescendantRectToMyCoords(title,bounds);
                assertTrue(bounds.left>=0 && bounds.top>=0 && bounds.right<=bottom.getWidth() && bounds.bottom<=bottom.getHeight());
            }
            assertEquals(5,clicks.get());Drawable latest=new ColorDrawable(Color.MAGENTA);background.setBackground(latest);
            a.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();assertNotSame(latest,background.getBackground());
            controller.close();assertSame(latest,background.getBackground());assertSame(image,background.getDrawable());
            assertNull(bottom.getBackground());assertEquals(-1,params.width);assertEquals(48,params.height);
        } finally {controller.close();a.finish();}
    }
    @Test public void navigationSkinUsesBackgroundAndRespectsHostClearingIt() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            ImageView image=new ImageView(a);Drawable skin=new ColorDrawable(0xFF222222),content=new ColorDrawable(0xFF555555);
            image.setBackground(skin);image.setImageDrawable(content);
            LiquidGlassController.NavigationBackground state=new LiquidGlassController.NavigationBackground(image);
            assertNotSame(skin,image.getBackground());assertSame(content,image.getDrawable());
            image.setBackground(null);state.sync();state.close();assertNull(image.getBackground());
            image.setBackground(skin);state=new LiquidGlassController.NavigationBackground(image);state.close();assertSame(skin,image.getBackground());
        } finally {a.finish();}
    }
    private static void layout(View root) {
        layout(root,360,800);
    }
    private static void layout(View root,int width,int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));root.layout(0,0,width,height);
    }
    private static FrameLayout bottom(Activity a) throws Exception {
        return (FrameLayout)Class.forName("X.1JgG").getConstructor(android.content.Context.class).newInstance(a);
    }
}
