package com.douai.module.feature;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout;
import java.time.Duration;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Proposed regressions for the tester; synthetic geometry does not establish phone behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public class ProgressSpacingLifecycleTest {
    @Test @Config(qualifiers="w384dp-h854dp-600dpi")
    public void logged69PixelTrackRetainsItsTouchAreaWhenNavigationAppearsLater() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            ProTouchDelegateFrameLayout root=new ProTouchDelegateFrameLayout(scene.activity);
            ((ViewGroup)scene.root.getParent()).removeView(scene.root);
            root.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(root);
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.params.height=69;track.params.bottomMargin=146;track.bar.setPadding(38,23,38,38);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            scene.carrierParams.bottomMargin=210;scene.navigationParams.height=184;scene.navigation.setVisibility(View.INVISIBLE);
            for(View view:LiquidGlassTargets.nodes(root))view.forceLayout();layout(root,1440,3200);
            assertEquals(3.75f,root.getResources().getDisplayMetrics().density,.001f);
            assertEquals(new RectF(0,2985,1440,3054),relativeBounds(track.bar,root));
            Object component=X.WindowProgressTouchFixtures.register(root,track.bar);
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(root,v->relativeBounds(v,root))) {
                clearance.setProgressMotionMargin(38);clearance.discover(LiquidGlassTargets.nodes(root));
                clearance.sync(List.of(scene.navigation));assertEquals(146,track.params.bottomMargin);
                scene.navigation.setVisibility(View.VISIBLE);scene.navigationParams.height=270;scene.navigationParams.bottomMargin=98;
                scene.navigation.requestLayout();
                for(int i=0;i<8;i++) {
                    layout(root,1440,3200);clearance.sync(List.of(scene.navigation));layout(root,1440,3200);
                    root.getViewTreeObserver().dispatchOnPreDraw();
                }
                assertEquals(69,track.params.height);assertEquals(23,track.bar.getPaddingTop());assertEquals(38,track.bar.getPaddingBottom());
                Rect hit=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(component);
                assertTrue("The full learned native hit area stays above the capsule",hit.bottom<=scene.bounds(scene.navigation).top-52);
                assertTrue("Upper controls stay above the full learned native hit area",
                        Math.max(scene.bounds(scene.metadata).bottom,scene.bounds(scene.muteHitbox).bottom)<=hit.top-52);
                int margin=track.params.bottomMargin;
                for(int i=0;i<3;i++){layout(root,1440,3200);clearance.sync(List.of(scene.navigation));}
                assertEquals(margin,track.params.bottomMargin);
                clearance.close();layout(root,1440,3200);root.getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(146,track.params.bottomMargin);assertEquals(69,track.params.height);
            }
        }
    }

    @Test public void evenAOnePixelOwnedMarginWaitsForLayoutBeforeItsNextCompensation() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            scene.forceInitialLayout();scene.layout();
            RectF navigation=relativeBounds(scene.navigation,scene.root);
            navigation.top=relativeBounds(track.bar,scene.root).bottom+3;navigation.bottom=navigation.top+58;
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,
                    v->v==scene.navigation?new RectF(navigation):relativeBounds(v,scene.root))) {
                clearance.discover(List.of(scene.root,scene.navigation,track.owner,track.bar));
                assertTrue(clearance.sync(List.of(scene.navigation)));assertEquals(40,track.params.bottomMargin);
                assertFalse("A one-pixel unapplied write must not be compensated for a second time",
                        clearance.sync(List.of(scene.navigation)));
                assertEquals(40,track.params.bottomMargin);scene.layout();
                assertFalse(clearance.sync(List.of(scene.navigation)));assertEquals(40,track.params.bottomMargin);
                clearance.close();assertEquals(39,track.params.bottomMargin);
            }
        }
    }

    @Test public void anEmptyRequestedFooterChildCannotStarveMeasuredProgressAndFooterGeometry() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            View placeholder=new View(scene.activity);scene.lowerModule.addView(placeholder,new FrameLayout.LayoutParams(0,0));
            scene.forceInitialLayout();scene.layout();
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,v->relativeBounds(v,scene.root))) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));placeholder.requestLayout();
                assertTrue(scene.carrier.isLayoutRequested());assertEquals(0,placeholder.getHeight());
                assertTrue(clearance.sync(List.of(scene.navigation)));
                assertTrue("A request inherited from an empty child is not an unapplied slider margin",track.params.bottomMargin>39);
                scene.layout();int margin=track.params.bottomMargin,footerMargin=scene.carrierParams.bottomMargin;
                placeholder.requestLayout();assertFalse(clearance.sync(List.of(scene.navigation)));
                assertEquals(margin,track.params.bottomMargin);assertEquals(footerMargin,scene.carrierParams.bottomMargin);
                assertTrue(scene.bounds(track.bar).bottom<=scene.bounds(scene.navigation).top-3);
                assertTrue(Math.max(scene.bounds(scene.metadata).bottom,scene.bounds(scene.muteHitbox).bottom)
                        <=scene.bounds(track.bar).top-3);
            }
        }
    }

    @Test public void anUnmeasuredNewWindowProgressStillWaitsForItsFirstLayout() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,v->relativeBounds(v,scene.root))) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));
                assertFalse(track.bar.isLaidOut());assertFalse(clearance.sync(List.of(scene.navigation)));
                assertEquals(39,track.params.bottomMargin);
                assertTrue(clearance.geometryDiagnostic(List.of(scene.navigation)).contains("sync_state=pending_target_layout"));
                scene.forceInitialLayout();scene.layout();assertTrue(clearance.sync(List.of(scene.navigation)));
                assertTrue(track.params.bottomMargin>39);
            }
        }
    }

    @Test public void nativeTouchRefreshAndReleaseIgnoreAnUnrelatedPendingRootLayout() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            ProTouchDelegateFrameLayout root=new ProTouchDelegateFrameLayout(scene.activity);
            ((ViewGroup)scene.root.getParent()).removeView(scene.root);
            root.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(root);
            LiquidGlassClearanceControllerTest.WindowProgress track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            View unrelated=new View(scene.activity);scene.root.addView(unrelated,new FrameLayout.LayoutParams(1,1));
            scene.navigation.setVisibility(View.INVISIBLE);scene.forceInitialLayout();scene.layout();
            Object component=X.WindowProgressTouchFixtures.register(root,track.bar);
            Rect original=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(component);
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(root,v->relativeBounds(v,root))) {
                clearance.discover(LiquidGlassTargets.nodes(root));clearance.sync(List.of(scene.navigation));
                scene.navigation.setVisibility(View.VISIBLE);scene.layout();unrelated.requestLayout();
                clearance.sync(List.of(scene.navigation));assertTrue(track.params.bottomMargin>39);
                scene.layout();unrelated.requestLayout();
                assertTrue(root.isLayoutRequested());assertFalse(track.bar.isLayoutRequested());
                scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                Rect expected=X.WindowProgressTouchFixtures.currentBoundsOnScreen(root,track.bar);
                assertTrue("The real slider has moved",expected.bottom<original.bottom);
                assertEquals("An unrelated pending root cannot keep the native hitbox at its old position",
                        expected,X.WindowProgressTouchFixtures.cachedBoundsOnScreen(component));
                int visualMargin=track.params.bottomMargin;
                int nativeBottomExtra=expected.bottom-scene.bounds(track.bar).bottom;
                assertTrue("This fixture must exercise extra native touch space: "+expected,nativeBottomExtra>0);
                // The callback learns native padding; a direct Clearance fixture must drive the next frame itself.
                assertTrue("The learned hitbox needs its own budget: "+clearance.geometryDiagnostic(List.of(scene.navigation)),
                        clearance.sync(List.of(scene.navigation)));
                assertEquals("The second budget adds only the learned bottom extension",
                        visualMargin+nativeBottomExtra,track.params.bottomMargin);
                scene.layout();unrelated.requestLayout();
                assertTrue(root.isLayoutRequested());assertFalse(track.bar.isLayoutRequested());
                scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                expected=X.WindowProgressTouchFixtures.currentBoundsOnScreen(root,track.bar);
                assertEquals("The second layout also refreshes the native cache despite the unrelated request",
                        expected,X.WindowProgressTouchFixtures.cachedBoundsOnScreen(component));
                assertTrue("The complete native hitbox clears navigation: "+expected+" / "+scene.bounds(scene.navigation),
                        expected.bottom<=scene.bounds(scene.navigation).top-3);
                int settledMargin=track.params.bottomMargin;
                assertFalse("A learned stable hitbox must not keep accumulating margin",
                        clearance.sync(List.of(scene.navigation)));
                assertEquals(settledMargin,track.params.bottomMargin);
                scene.dragTimeline(track.bar);assertTrue(track.starts.get()>0);assertTrue(track.stops.get()>0);
                ViewGroup row=scene.navigation.findViewById(0x7f0a7983);
                for(int i=0;i<row.getChildCount();i++)scene.click(row.getChildAt(i));
                assertEquals(5,scene.navigationClicks.get());
                clearance.close();scene.layout();unrelated.requestLayout();
                scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();
                assertEquals(39,track.params.bottomMargin);
                assertEquals("Release also refreshes native geometry despite an unrelated relayout",
                        X.WindowProgressTouchFixtures.currentBoundsOnScreen(root,track.bar),
                        X.WindowProgressTouchFixtures.cachedBoundsOnScreen(component));
            }
        }
    }

    @Test public void onlyChangingTheTopNavigationCeilingRevisesACapacityRejection() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            MainTitleBar top=new MainTitleBar(scene.activity);scene.root.addView(top,new FrameLayout.LayoutParams(-1,48));
            scene.forceInitialLayout();scene.layout();
            top.setTranslationY(scene.bounds(scene.navigation).top-scene.bounds(scene.root).top-48);
            try(NavigationGlassClearance clearance=new NavigationGlassClearance(scene.root,v->relativeBounds(v,scene.root))) {
                clearance.discover(LiquidGlassTargets.nodes(scene.root));clearance.sync(List.of(top,scene.navigation));
                assertTrue(clearance.blockedViews().contains(scene.navigation));
                long rejected=clearance.inputRevision();
                top.setTranslationY(0);clearance.sync(List.of(top,scene.navigation));
                assertTrue("Host movement of the same top bar must release an old retry rejection",
                        clearance.inputRevision()>rejected);
                assertTrue(clearance.blockedViews().isEmpty());assertTrue(scene.carrierParams.bottomMargin>0);
            }
        }
    }

    @Test public void aTrailingGeometrySampleCapturesNavigationAfterItsReappearance() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            new LiquidGlassClearanceControllerTest.WindowProgress(scene);scene.forceInitialLayout();scene.layout();
            scene.navigation.setVisibility(View.INVISIBLE);scene.layout();
            long[] sampledAt={-1};
            scene.controller=new LiquidGlassController(scene.activity,
                    LiquidGlassClearanceControllerTest.Scene.options(true,false),line->{
                        scene.logs.add(line);
                        if(line.contains("progress_geometry;"))sampledAt[0]=android.os.SystemClock.uptimeMillis();
                    });
            scene.controller.onActivityResumed(scene.activity);scene.settle();
            for(int i=0;i<100 && sampledAt[0]<0;i++)
                ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(10));
            String baseline=scene.logs.stream().filter(line->line.contains("progress_geometry;"))
                    .reduce((first,last)->last).orElse("");
            assertTrue("Record a different hidden baseline before clearing consumer logs: "+scene.logs,
                    baseline.contains("reserved=null") && baseline.contains(",visibility=4"));
            long hiddenSampleAt=sampledAt[0];scene.logs.clear();
            scene.navigation.setVisibility(View.VISIBLE);scene.layout();
            scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();scene.layout();
            scene.activity.getWindow().getDecorView().getViewTreeObserver().dispatchOnPreDraw();scene.layout();
            long remaining=1000-(android.os.SystemClock.uptimeMillis()-hiddenSampleAt);
            assertTrue("Navigation must return inside the existing throttle interval",remaining>0);
            assertFalse(scene.logs.stream().anyMatch(line->line.contains("progress_geometry;")));
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(remaining-1));
            assertFalse("Changed geometry must not bypass the one-second limit: "+scene.logs,
                    scene.logs.stream().anyMatch(line->line.contains("progress_geometry;")));
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(1));
            assertTrue("The last changed geometry must be sampled after drawing stops: "+scene.logs,
                    scene.logs.stream().anyMatch(line->line.contains("progress_geometry;")
                            && line.contains("reserved=RectF") && line.contains(",visibility=0")));
            assertEquals(1,scene.logs.stream().filter(line->line.contains("progress_geometry;")).count());
        }
    }

    @Test public void aClosedMonitorDoesNotEmitItsQueuedGeometrySample() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            new LiquidGlassClearanceControllerTest.WindowProgress(scene);scene.forceInitialLayout();scene.layout();
            scene.enable(false);scene.settle();scene.controller.close();scene.logs.clear();
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertFalse(scene.logs.stream().anyMatch(line->line.contains("progress_geometry;")));
        }
    }

    private static RectF relativeBounds(View view,View root) {
        int[] origin=new int[2],position=new int[2];root.getLocationOnScreen(origin);view.getLocationOnScreen(position);
        return new RectF(position[0]-origin[0],position[1]-origin[1],
                position[0]-origin[0]+view.getWidth(),position[1]-origin[1]+view.getHeight());
    }
    private static void layout(View root,int width,int height) {
        root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(height,View.MeasureSpec.EXACTLY));
        root.layout(0,0,width,height);
    }
}
