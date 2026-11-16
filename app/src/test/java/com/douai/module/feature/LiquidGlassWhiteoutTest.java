package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Button;
import android.graphics.drawable.Drawable;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import java.util.Map;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="mdpi")
public class LiquidGlassWhiteoutTest {
    @Test public void fullScreenClickLayerImageTextAndWrongSemanticSlotNeverBecomeGlassSurfaces() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            for(int kind=0;kind<5;kind++) {
                FrameLayout root=new FrameLayout(a);root.addView(new SurfaceView(a),new FrameLayout.LayoutParams(360,800));
                View overlay;
                if(kind==0){overlay=new View(a);overlay.setClickable(true);}
                else if(kind==1){overlay=new ImageView(a);overlay.setClickable(true);}
                else if(kind==2){TextView text=new TextView(a);text.setText("large media overlay");overlay=text;}
                else if(kind==3){overlay=new FrameLayout(a);overlay.setId(0x7f0a30de);}
                else overlay=new MainTitleBar(a);
                root.addView(overlay,new FrameLayout.LayoutParams(360,800));a.setContentView(root);layout(root);
                assertFalse("Full-screen overlay type "+kind+" must leave video unobscured",LiquidGlassTargets.find(root).containsKey(overlay));
            }
        } finally {a.finish();}
    }
    @Test public void notYetMeasuredNavigationIsDeferredAndFeedControlsStayOutsideTheScopeAfterLayout() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a),container=new FrameLayout(a);container.setBackgroundColor(Color.BLACK);
            root.addView(container,new FrameLayout.LayoutParams(360,800));
            assertTrue("No zero-size container may be painted during attach",LiquidGlassTargets.find(root).isEmpty());
            a.setContentView(root);layout(root);assertFalse(LiquidGlassTargets.find(root).containsKey(container));
            FrameLayout like=new FrameLayout(a);like.setId(0x7f0a30de);
            MainTitleBar navigation=new MainTitleBar(a);
            root.addView(like,new FrameLayout.LayoutParams(61,90));root.addView(navigation,new FrameLayout.LayoutParams(320,56));
            assertFalse(LiquidGlassTargets.find(root).containsKey(navigation));layout(root);
            assertFalse("Feed actions keep their host material",LiquidGlassTargets.find(root).containsKey(like));
            assertTrue("Measured navigation remains themed",LiquidGlassTargets.find(root).containsKey(navigation));
        } finally {a.finish();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void drawingAllSelectedSurfacesLeavesTheMediaCentreExactlyUnchanged() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);View fullScreenTap=new View(a);fullScreenTap.setClickable(true);
            root.addView(fullScreenTap,new FrameLayout.LayoutParams(360,800));
            FrameLayout like=new FrameLayout(a);like.setId(0x7f0a30de);
            FrameLayout.LayoutParams position=new FrameLayout.LayoutParams(61,90);position.leftMargin=290;position.topMargin=450;
            root.addView(like,position);MainTitleBar navigation=new MainTitleBar(a);
            root.addView(navigation,new FrameLayout.LayoutParams(320,56));a.setContentView(root);layout(root);
            Bitmap image=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);image.eraseColor(0xFF124B7A);
            try(LiquidGlassBackdrop backdrop=new LiquidGlassBackdrop(root,null)) {
                Map<View,Boolean> selected=LiquidGlassTargets.find(root);
                assertEquals("The rendering check must exercise a real navigation target",1,selected.size());
                for(Map.Entry<View,Boolean> entry:selected.entrySet()) {
                    View target=entry.getKey();LiquidGlassDrawable glass=new LiquidGlassDrawable(target,entry.getValue(),backdrop);
                    glass.setBounds(0,0,target.getWidth(),target.getHeight());Canvas canvas=new Canvas(image);
                    canvas.save();canvas.translate(target.getLeft(),target.getTop());glass.draw(canvas);canvas.restore();glass.close();
                }
                assertEquals("Video pixels must not receive a full-screen veil",0xFF124B7A,image.getPixel(180,350));
                assertEquals("Feed actions must not receive material",0xFF124B7A,image.getPixel(315,480));
                assertNotEquals("Navigation still receives its glass material",0xFF124B7A,image.getPixel(160,28));
            } finally {image.recycle();}
        } finally {a.finish();}
    }
    @Test public void continuousLayoutEventsCannotKeepAnExpandedNavigationCoveredForever() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        LiquidGlassController controller=new LiquidGlassController(a,new LocalOptions(
                Map.of("theme_style","liquid_glass","theme_gravity_enabled",false)),null);
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar navigation=new MainTitleBar(a);
            Drawable original=new ColorDrawable(Color.BLACK);navigation.setBackground(original);
            root.addView(navigation,new FrameLayout.LayoutParams(-1,48));a.setContentView(root);
            View decor=a.getWindow().getDecorView();layout(decor);
            controller.onActivityResumed(a);assertNotSame(original,navigation.getBackground());
            navigation.setScaleY(20);layout(decor);
            for(int i=0;i<6;i++) {
                decor.getViewTreeObserver().dispatchOnGlobalLayout();
                ShadowLooper.shadowMainLooper().idleFor(java.time.Duration.ofMillis(50));
            }
            assertSame("Layout churn must not keep a navigation cover decorated",original,navigation.getBackground());
        } finally {controller.close();a.finish();}
    }
    @Test public void popupControlsStayOutsideGlassWhileAnExplicitNavigationUsesTheDisplayViewport() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout popup=new FrameLayout(a);Button row=new Button(a);row.setText("确定");MainTitleBar navigation=new MainTitleBar(a);
            popup.addView(row,new FrameLayout.LayoutParams(90,48));popup.addView(navigation,new FrameLayout.LayoutParams(90,48));
            popup.measure(View.MeasureSpec.makeMeasureSpec(100,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(80,View.MeasureSpec.EXACTLY));popup.layout(0,0,100,80);
            assertFalse("Popup controls retain their host material",LiquidGlassTargets.select(popup,false).targets.containsKey(row));
            assertTrue("Explicit navigation keeps the non-Activity viewport contract",LiquidGlassTargets.select(popup,false).targets.containsKey(navigation));
        } finally {a.finish();}
    }
    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void aSmallHitboxScaledAcrossTheVideoCannotBypassTheGeometryGuard() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout root=new FrameLayout(a);MainTitleBar overlay=new MainTitleBar(a);
            root.addView(overlay,new FrameLayout.LayoutParams(60,80));a.setContentView(root);layout(root);
            assertTrue("The unscaled navigation must first be eligible",LiquidGlassTargets.find(root).containsKey(overlay));
            overlay.setScaleX(6);overlay.setScaleY(10);
            assertFalse("Painted bounds, not just raw layout dimensions, must remain small",LiquidGlassTargets.find(root).containsKey(overlay));
        } finally {a.finish();}
    }
    @Test public void activityInAShortWindowCannotUseTheFullDisplayToAdmitAWindowSizeCover() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        android.util.DisplayMetrics metrics=a.getResources().getDisplayMetrics();int oldW=metrics.widthPixels,oldH=metrics.heightPixels;
        try {
            metrics.widthPixels=360;metrics.heightPixels=800;
            MainTitleBar overlay=new MainTitleBar(a);a.setContentView(overlay);
            View decor=a.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(200,View.MeasureSpec.EXACTLY));decor.layout(0,0,360,200);
            assertFalse("Activity geometry must use the actual window viewport",LiquidGlassTargets.geometrySafe(overlay,decor,true));
            try(LiquidGlassController controller=new LiquidGlassController(a,new LocalOptions(
                    Map.of("theme_style","liquid_glass","theme_gravity_enabled",false)),null)) {
                controller.onActivityResumed(a);
                assertNull("Window ownership must reach the live controller selection",overlay.getBackground());
            }
        } finally {metrics.widthPixels=oldW;metrics.heightPixels=oldH;a.finish();}
    }
    private static void layout(View root) {
        root.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,360,800);
    }
}
