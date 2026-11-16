package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import com.douai.module.BuildConfig;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.ui.HomeIconImport;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import static org.junit.Assert.*;

/** NativeCanvas rendering of a synthetic Android host tree, not a real-device screenshot. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NavigationGlassClearancePreviewTest {
    @Test public void rendersLongMetadataTimelineMuteAndGlassNavigationWithMeasuredClearance() throws Exception {
        Bitmap before=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);
        Bitmap after=Bitmap.createBitmap(360,800,Bitmap.Config.ARGB_8888);
        Bitmap comparison=Bitmap.createBitmap(720,840,Bitmap.Config.ARGB_8888);
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout touchRoot=
                    new com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout(scene.activity);
            ((android.view.ViewGroup)scene.root.getParent()).removeView(scene.root);
            touchRoot.addView(scene.root,new android.widget.FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(touchRoot);
            scene.timeline.setVisibility(android.view.View.GONE);
            LiquidGlassClearanceControllerTest.WindowProgress pageTrack=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            pageTrack.params.height=118;pageTrack.bar.setPadding(6,50,6,50);
            pageTrack.preview.setVisibility(android.view.View.GONE);
            scene.useTallTransparentMetadataOwner();
            // Reparenting an already laid-out fixture clears leaf layout flags while retaining cached sizes.
            forceLayoutTree(scene.root);scene.layout();
            Object nativeComponent=X.WindowProgressTouchFixtures.register(touchRoot,pageTrack.bar);
            scene.root.draw(new Canvas(before));
            String[] keys={"homepage_home","homepage_familiar","preview_publish","homepage_notification","homepage_profile"};
            String[] paths={"M3 11L12 3l9 8v10h-6v-7H9v7H3z",
                    "M4 21v-4a5 5 0 0 1 10 0v4zm12 0v-5a7 7 0 0 0-2-5h3a5 5 0 0 1 5 5v5zM9 2a4 4 0 1 1 0 8 4 4 0 0 1 0-8",
                    "M10 3h4v7h7v4h-7v7h-4v-7H3v-4h7z",
                    "M3 3h18v14H8l-5 5zm3 4v2h12V7zm0 5v2h8v-2z",
                    "M12 2a5 5 0 1 1 0 10 5 5 0 0 1 0-10M3 22v-3a9 7 0 0 1 18 0v3z"};
            String icons="{}";
            for(int i=0;i<keys.length;i++) {
                String svg="<svg viewBox='0 0 24 24'><path fill='white' d='"+paths[i]+"'/></svg>";
                icons=HomeUiCustomizationSettings.putIcon(icons,"nav_bottom:"+keys[i],HomeIconImport.convert(svg.getBytes(StandardCharsets.UTF_8)));
            }
            scene.enable(false);
            scene.controller.setOptions(new LocalOptions(Map.of("theme_style","liquid_glass","theme_gravity_enabled",false,
                    HomeUiCustomizationSettings.ICONS,icons)));
            scene.settle();scene.assertClearance(3);scene.root.draw(new Canvas(after));
            assertTrue("The independent page track and its native extended hotzone clear the capsule",
                    X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent).bottom<=scene.bounds(scene.navigation).top-3);
            assertEquals(48,pageTrack.bar.getHeight());
            assertTrue("Large transparent padding no longer pushes the painted line far above navigation",
                    scene.bounds(scene.navigation).top-(scene.bounds(pageTrack.bar).bottom-pageTrack.bar.getPaddingBottom())<=48);
            assertEquals("The full-frame scene is not resized or translated",before.getPixel(180,200),after.getPixel(180,200));
            assertTrue(scene.navigation.getHeight()>=72);
            assertTrue("The raised capsule has visible bottom breathing room",scene.navigationParams.bottomMargin>=16);
            File directory=new File(System.getProperty("java.io.tmpdir"),"DouAi-navigation-clearance-"+BuildConfig.VERSION_NAME);
            assertTrue(directory.isDirectory() || directory.mkdirs());
            save(before,new File(directory,"before-native.png"));save(after,new File(directory,"after-glass.png"));
            Canvas canvas=new Canvas(comparison);canvas.drawColor(0xff14202a);
            canvas.drawBitmap(before,0,40,null);canvas.drawBitmap(after,360,40,null);
            Paint heading=new Paint(Paint.ANTI_ALIAS_FLAG);heading.setColor(Color.WHITE);heading.setTextSize(14);
            canvas.drawText("Native layout (padded Pro)",12,25,heading);canvas.drawText("Glass + compact clearance",372,25,heading);
            File output=new File(directory,"comparison.png");save(comparison,output);
            System.out.println("Navigation clearance synthetic preview: "+output.getAbsolutePath());
        } finally {before.recycle();after.recycle();comparison.recycle();}
    }
    private static void save(Bitmap bitmap,File destination) throws Exception {
        try(FileOutputStream output=new FileOutputStream(destination)){assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG,100,output));}
    }
    private static void forceLayoutTree(android.view.View view) {
        view.forceLayout();
        if(view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group=(android.view.ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++)forceLayoutTree(group.getChildAt(i));
        }
    }
}
