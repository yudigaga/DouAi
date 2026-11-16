package com.douai.module.feature;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Real SeekBar measurement in the synthetic Android host tree; not a phone/Hook-runtime check. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public final class WindowProgressCompactGeometryTest {
    @Test public void unavailableClearanceKeepsCompactGeometryStableUntilRelease() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress progress=
                    new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            progress.params.height=118;progress.bar.setLayoutParams(progress.params);
            progress.bar.setPadding(9,50,11,50);
            MainTitleBar top=new MainTitleBar(scene.activity);
            // This verified top-navigation owner leaves no vertical capacity above the bottom navigation.
            scene.root.addView(top,new FrameLayout.LayoutParams(-1,760,Gravity.TOP));
            scene.forceInitialLayout();scene.layout();
            List<View> navigation=Arrays.asList(scene.navigation,top);
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,view->{
                Rect box=scene.bounds(view),origin=scene.bounds(scene.root);
                RectF local=new RectF(box);local.offset(-origin.left,-origin.top);return local;
            })) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));
                assertTrue("The first pass applies the validated compact geometry",clearance.sync(navigation));
                scene.layout();assertEquals(48,progress.bar.getLayoutParams().height);
                for(int pass=0;pass<6;pass++) {
                    scene.layout();clearance.sync(navigation);scene.layout();
                }
                assertFalse("Capacity rejection must settle without alternating compact and restored layouts",
                        clearance.sync(navigation));
                assertEquals("Unavailable position clearance does not undo validated padding compaction",
                        48,progress.bar.getLayoutParams().height);
                assertEquals(18,progress.bar.getHeight()-progress.bar.getPaddingTop()-progress.bar.getPaddingBottom());
                assertEquals("The rejected offset stays native",39,progress.params.bottomMargin);
            }
            scene.layout();assertGeometry(progress,118,9,50,11,50);
        }
    }

    @Test public void compactWindowProgressKeepsItsContentAndSmallOrUnknownGeometryNative() throws Exception {
        int[] heights={118,18,ViewGroup.LayoutParams.WRAP_CONTENT,70};
        int[] verticalPadding={50,0,50,50};
        for(int index=0;index<heights.length;index++)try(LiquidGlassClearanceControllerTest.Scene scene=
                new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress progress=
                    new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            progress.params.height=heights[index];
            progress.bar.setLayoutParams(progress.params);
            progress.bar.setPadding(9,verticalPadding[index],11,verticalPadding[index]);
            scene.forceInitialLayout();scene.layout();

            scene.enable(false);scene.settle();
            assertEquals("Horizontal padding belongs to the native slider",9,progress.bar.getPaddingLeft());
            assertEquals(11,progress.bar.getPaddingRight());
            if(index==0) {
                assertEquals("The large transparent target becomes a compact target",48,progress.bar.getLayoutParams().height);
                assertEquals(48,progress.bar.getHeight());
                assertEquals("The native 18dp content is preserved",18,progress.bar.getHeight()
                        -progress.bar.getPaddingTop()-progress.bar.getPaddingBottom());
                assertEquals("Only surplus vertical padding is removed",30,
                        progress.bar.getPaddingTop()+progress.bar.getPaddingBottom());
            } else {
                assertEquals("A small, wrap-content or invalid-content target keeps its native height",
                        heights[index],progress.bar.getLayoutParams().height);
                assertEquals(verticalPadding[index],progress.bar.getPaddingTop());
                assertEquals(verticalPadding[index],progress.bar.getPaddingBottom());
            }

            scene.controller.close();scene.settle();
            assertGeometry(progress,heights[index],9,verticalPadding[index],11,verticalPadding[index]);
        }
    }

    @Test public void hostPaddingHeightAndReplacementWritesSurviveThemeDisableAndFinalClose() throws Exception {
        for(int kind=0;kind<3;kind++)try(LiquidGlassClearanceControllerTest.Scene scene=
                new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress progress=
                    new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            progress.params.height=118;progress.bar.setLayoutParams(progress.params);
            progress.bar.setPadding(9,50,11,50);
            scene.forceInitialLayout();scene.layout();scene.enable(false);scene.settle();
            assertEquals(48,progress.bar.getLayoutParams().height);

            ViewGroup.LayoutParams firstHostParams=writeHostGeometry(progress,kind,false);
            scene.settle();
            scene.controller.setOptions(LiquidGlassClearanceControllerTest.Scene.options(false,false));
            scene.settle();
            assertSame("Adopting a host update must retain its layout-parameter object",
                    firstHostParams,progress.bar.getLayoutParams());
            if(kind==0)assertGeometry(progress,118,13,6,17,8);
            else assertGeometry(progress,138,9,50,11,50);

            scene.controller.setOptions(LiquidGlassClearanceControllerTest.Scene.options(true,false));
            scene.settle();
            ViewGroup.LayoutParams lastHostParams=writeHostGeometry(progress,kind,true);
            // Deliberately no sync: a host write after the last owned frame must survive release too.
            scene.controller.close();scene.settle();
            assertSame("Release cannot replace the host's final layout parameters",
                    lastHostParams,progress.bar.getLayoutParams());
            if(kind==0)assertGeometry(progress,118,19,7,23,9);
            else assertGeometry(progress,146,9,50,11,50);
        }
    }

    private static ViewGroup.LayoutParams writeHostGeometry(
            LiquidGlassClearanceControllerTest.WindowProgress progress,int kind,boolean last) {
        if(kind==0) {
            progress.bar.setPadding(last?19:13,last?7:6,last?23:17,last?9:8);
        } else {
            ConstraintLayout.LayoutParams params=(ConstraintLayout.LayoutParams)progress.bar.getLayoutParams();
            if(kind==2)params=new ConstraintLayout.LayoutParams(params);
            params.height=last?146:138;
            progress.bar.setLayoutParams(params);
        }
        return progress.bar.getLayoutParams();
    }

    private static void assertGeometry(LiquidGlassClearanceControllerTest.WindowProgress progress,
            int height,int left,int top,int right,int bottom) {
        assertEquals("Only the still-owned height may be restored",height,progress.bar.getLayoutParams().height);
        assertEquals(left,progress.bar.getPaddingLeft());assertEquals(top,progress.bar.getPaddingTop());
        assertEquals(right,progress.bar.getPaddingRight());assertEquals(bottom,progress.bar.getPaddingBottom());
    }
}
