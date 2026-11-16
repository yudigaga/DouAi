package com.douai.module.feature;

import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/** Diagnostic matrix only: synthetic geometry is not a phone measurement. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public class ProgressSpacingInvestigationTest {
    @Test public void geometryDiagnosticReportsMeasuredFieldsWithoutCallingThemVisiblePixels() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            new LiquidGlassClearanceControllerTest.WindowProgress(scene);scene.forceInitialLayout();scene.layout();
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,v->new android.graphics.RectF(scene.bounds(v)))) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));
                String report=clearance.geometryDiagnostic(java.util.Collections.singletonList(scene.navigation));
                assertTrue(report,report.contains("padding="));assertTrue(report,report.contains("translation_y="));
                assertTrue(report,report.contains("touch_extra="));assertTrue(report,report.contains("drawable_bounds="));
                assertTrue(report,report.contains("navigation="));assertFalse(report.contains("visible_pixels="));
            }
        }
    }
    @Test public void navigationReappearanceStillReservesProgressWhenOnlyAnUnrelatedSiblingRequestsLayout() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            View unrelated=new View(scene.activity);scene.root.addView(unrelated,new FrameLayout.LayoutParams(1,1));
            scene.navigation.setVisibility(View.INVISIBLE);scene.forceInitialLayout();scene.layout();
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,v->new android.graphics.RectF(scene.bounds(v)))) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));clearance.sync(java.util.Collections.singletonList(scene.navigation));
                scene.navigation.setVisibility(View.VISIBLE);scene.layout();
                unrelated.requestLayout();
                assertTrue(scene.root.isLayoutRequested());assertFalse(track.bar.isLayoutRequested());assertFalse(scene.navigation.isLayoutRequested());
                clearance.sync(java.util.Collections.singletonList(scene.navigation));
                assertTrue("A video/overlay relayout must not starve progress clearance after navigation returns",track.params.bottomMargin>39);
            }
        }
    }    @Test public void gravityRetainsTheMotionEnvelopeAroundTheNativeHitbox() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            scene.forceInitialLayout();scene.layout();scene.enable(true);scene.settle();
            assertTrue("The navigation can move upward 10dp and must retain clearance",scene.bounds(track.bar).bottom<=scene.bounds(scene.navigation).top-14);
        }
    }    @Test public void recordsBothVisibleGapsAndNativeTouchBounds() throws Exception {
        for(int[] input:new int[][]{{118,50,50},{48,0,0},{18,0,0},{118,0,0}}) {
            try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
                com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout touchRoot=
                    new com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout(scene.activity);
                ((ViewGroup)scene.root.getParent()).removeView(scene.root);
                touchRoot.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(touchRoot);
                LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
                track.params.height=input[0];track.bar.setPadding(6,input[1],9,input[2]);
                track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
                scene.forceInitialLayout();scene.layout();
                Object nativeComponent=X.WindowProgressTouchFixtures.register(touchRoot,track.bar);
                scene.enable(false);scene.settle();
                Rect box=scene.bounds(track.bar),hit=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent);
                int upper=box.top+track.bar.getPaddingTop()-Math.max(scene.bounds(scene.metadata).bottom,scene.bounds(scene.muteHitbox).bottom);
                int lower=scene.bounds(scene.navigation).top-(box.bottom-track.bar.getPaddingBottom());
                System.out.println("SPACING_DIAGNOSTIC input="+java.util.Arrays.toString(input)+" finalHeight="+track.params.height+" contentUpperGap="+upper+" contentLowerGap="+lower+" view="+box+" hit="+hit+" nav="+scene.bounds(scene.navigation));
                assertTrue("Upper content must not overlap",upper>=0); assertTrue("Lower content must not overlap",lower>=0);
                assertTrue("Upper native touch must not overlap",hit.top>=Math.max(scene.bounds(scene.metadata).bottom,scene.bounds(scene.muteHitbox).bottom));
                if(input[1]==50) { assertTrue("Upper compact gap: "+upper,upper<=20); assertTrue("Lower compact gap: "+lower,lower<=20); }
                assertTrue("Keep native touch clear of navigation",hit.bottom<=scene.bounds(scene.navigation).top);
                int margin=track.params.bottomMargin;scene.settle();assertEquals(margin,track.params.bottomMargin);
            }
        }
    }
}