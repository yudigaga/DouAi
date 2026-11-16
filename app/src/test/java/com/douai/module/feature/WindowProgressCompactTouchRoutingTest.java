package com.douai.module.feature;

import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDisplay;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import X.WindowProgressTouchFixtures;
import static org.junit.Assert.*;

/** Prepared phone-sized regressions; the opt-in manager follows the verified 41 native DOWN priority. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w384dp-h854dp-600dpi")
public class WindowProgressCompactTouchRoutingTest {
    private static final int PHONE_WIDTH=1440,PHONE_HEIGHT=3200;

    @Before public void configureExactPhoneViewportBeforeActivitySetup() {
        // 854dp * 3.75 is truncated to 3202px by Robolectric. Set both app and physical
        // dimensions before Scene creates its Activity, so ViewRoot cannot restore 3202px.
        Resources resources=RuntimeEnvironment.getApplication().getResources();
        Configuration configuration=new Configuration(resources.getConfiguration());
        Object windowConfiguration=ReflectionHelpers.getField(configuration,"windowConfiguration");
        Rect viewport=new Rect(0,0,PHONE_WIDTH,PHONE_HEIGHT);
        // API35 DisplayInfo gives configuration appBounds priority over DisplayInfo.appHeight.
        for(String setter:new String[]{"setBounds","setAppBounds","setMaxBounds"})
            ReflectionHelpers.callInstanceMethod(windowConfiguration,setter,ClassParameter.from(Rect.class,viewport));
        DisplayMetrics metrics=new DisplayMetrics();metrics.setTo(resources.getDisplayMetrics());
        metrics.widthPixels=PHONE_WIDTH;metrics.heightPixels=PHONE_HEIGHT;
        ReflectionHelpers.setField(metrics,"noncompatWidthPixels",PHONE_WIDTH);
        ReflectionHelpers.setField(metrics,"noncompatHeightPixels",PHONE_HEIGHT);
        // Match Robolectric 4.14.1's own resource propagation without reapplying dp qualifiers:
        // update ResourcesManager, system and app resources before Activity.setup reads them.
        ReflectionHelpers.callStaticMethod(RuntimeEnvironment.class,"updateConfiguration",
                ClassParameter.from(Configuration.class,configuration),ClassParameter.from(DisplayMetrics.class,metrics));
        ShadowDisplay display=Shadow.extract(ShadowDisplay.getDefaultDisplay());
        display.setWidth(PHONE_WIDTH);display.setHeight(PHONE_HEIGHT);
        display.setRealWidth(PHONE_WIDTH);display.setRealHeight(PHONE_HEIGHT);
        ShadowLooper.shadowMainLooper().idle();
        assertDisplayViewport("before Activity setup");
    }

    private static void assertDisplayViewport(String phase) {
        Display display=ShadowDisplay.getDefaultDisplay();
        Rect app=new Rect();display.getRectSize(app);
        DisplayMetrics real=new DisplayMetrics();display.getRealMetrics(real);
        assertEquals(phase+" app display",new Rect(0,0,PHONE_WIDTH,PHONE_HEIGHT),app);
        assertEquals(phase+" physical width",PHONE_WIDTH,real.widthPixels);
        assertEquals(phase+" physical height",PHONE_HEIGHT,real.heightPixels);
        assertEquals(phase+" density",3.75f,real.density,.001f);
        for(Resources resources:new Resources[]{Resources.getSystem(),RuntimeEnvironment.getApplication().getResources()}) {
            DisplayMetrics metrics=resources.getDisplayMetrics();
            assertEquals(phase+" resource width",PHONE_WIDTH,metrics.widthPixels);
            assertEquals(phase+" resource height",PHONE_HEIGHT,metrics.heightPixels);
            assertEquals(phase+" resource density",3.75f,metrics.density,.001f);
        }
    }

    @Test public void fullNativePolicyReproducesTheLargeStaticGapWhenRoutingIsUnavailable() throws Exception {
        try(RoutedScene fixture=new RoutedScene(false)) {
            fixture.enable();fixture.assertFullNativeBudget();
            assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            // Negative control: compact visual geometry without a guard lets the native manager steal DOWN.
            fixture.scene.controller.close();fixture.track.params.bottomMargin=421;fixture.track.bar.requestLayout();fixture.layout();
            assertNotNull(WindowProgressTouchBridge.refresh(fixture.track.bar,fixture.decor()));
            View tab=fixture.tabs().getChildAt(0);float x=fixture.bounds(tab).exactCenterX(),y=fixture.tabTouchY(tab);
            assertTrue(fixture.rawHit().contains((int)x,(int)y));
            int starts=fixture.track.starts.get();fixture.tap(x,y);
            assertTrue(fixture.track.starts.get()>starts);assertEquals(0,fixture.scene.navigationClicks.get());
        }
    }

    @Test public void guardedCompactLayoutRoutesEveryProtectedButtonWithoutChangingNativePolicy() throws Exception {
        try(RoutedScene fixture=new RoutedScene(true)) {
            fixture.enable();fixture.assertCompactBudget();
            for(int i=0;i<5;i++) {
                View tab=fixture.tabs().getChildAt(i);
                fixture.protectedTap(fixture.bounds(tab).exactCenterX(),fixture.tabTouchY(tab));
            }
            assertEquals(5,fixture.scene.navigationClicks.get());
            fixture.protectedTap(fixture.bounds(fixture.like).exactCenterX(),fixture.bounds(fixture.like).exactCenterY());
            fixture.protectedTap(fixture.bounds(fixture.scene.muteHitbox).exactCenterX(),fixture.bounds(fixture.scene.muteHitbox).exactCenterY());
            assertEquals(1,fixture.likes.get());assertEquals(1,fixture.scene.muteClicks.get());
            assertEquals("Protected buttons must not start native seeking",0,fixture.track.starts.get());
            fixture.settle();
            List<String> geometry=fixture.scene.logs.stream().filter(line->line.contains("progress_geometry;")).toList();
            assertFalse("The real controller must emit its bounded core evidence",geometry.isEmpty());
            String navigation=";navigation="+Integer.toHexString(fixture.scene.navigation.getId())+":";
            for(String line:geometry) {
                assertTrue("Core evidence must fit the runtime 1024 character limit: "+line.length(),line.length()<=1024);
                assertTrue(line.contains("draw_cancelled_total="));assertTrue(line.contains("layout_mode="));
                assertTrue(line.contains(navigation));assertTrue(line.contains(",visibility=0"));assertTrue(line.contains(",reserved="));
            }
            assertTrue(geometry.stream().anyMatch(line->line.contains("layout_mode=view")));
        }
    }

    @Test public void blankNativeExtensionAndProStartedDragsRetainTheNativeGesture() throws Exception {
        try(RoutedScene fixture=new RoutedScene(true)) {
            fixture.enable();fixture.assertCompactBudget();
            Rect bar=fixture.bounds(fixture.track.bar);float x=bar.width()*.2f+bar.left,y=bar.bottom+20;
            assertTrue("This DOWN uses native extension outside the real View",fixture.rawHit().contains((int)x,(int)y));
            assertFalse(bar.contains((int)x,(int)y));assertTrue(y<fixture.bounds(fixture.scene.navigation).top);
            Rect raw=WindowProgressTouchFixtures.rawCachedBounds(fixture.component);
            int starts=fixture.track.starts.get(),stops=fixture.track.stops.get();
            fixture.dispatch(MotionEvent.ACTION_DOWN,x,y);
            assertEquals(raw,WindowProgressTouchFixtures.returnedBounds(fixture.component));
            fixture.dispatch(MotionEvent.ACTION_MOVE,bar.left+bar.width()*.8f,y);
            fixture.dispatch(MotionEvent.ACTION_UP,bar.left+bar.width()*.8f,y);
            assertTrue(fixture.track.starts.get()>starts);assertTrue(fixture.track.stops.get()>stops);

            // A native-started gesture stays native when it crosses a protected button, including its UP.
            starts=fixture.track.starts.get();stops=fixture.track.stops.get();
            fixture.dispatch(MotionEvent.ACTION_DOWN,x,bar.top+bar.height()/2f);
            View tab=fixture.tabs().getChildAt(2);float endX=fixture.bounds(tab).exactCenterX(),endY=fixture.tabTouchY(tab);
            fixture.dispatch(MotionEvent.ACTION_MOVE,endX,endY);
            assertEquals(raw,WindowProgressTouchFixtures.returnedBounds(fixture.component));
            fixture.dispatch(MotionEvent.ACTION_UP,endX,endY);
            assertTrue(fixture.track.starts.get()>starts);assertTrue(fixture.track.stops.get()>stops);
            assertEquals(0,fixture.scene.navigationClicks.get());assertEquals(0,fixture.likes.get());assertEquals(0,fixture.scene.muteClicks.get());

            // CANCEL releases protection after dispatch, so the next blank-region DOWN can seek again.
            tab=fixture.tabs().getChildAt(0);endX=fixture.bounds(tab).exactCenterX();endY=fixture.tabTouchY(tab);
            fixture.dispatch(MotionEvent.ACTION_DOWN,endX,endY);
            assertTrue(WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            fixture.dispatch(MotionEvent.ACTION_CANCEL,endX,endY);
            assertEquals(raw,WindowProgressTouchFixtures.returnedBounds(fixture.component));
            starts=fixture.track.starts.get();fixture.tap(x,y);assertTrue(fixture.track.starts.get()>starts);
        }
    }

    @Test public void disablingRoutingAndClosingTheThemeRestoreConservativeOwnership() throws Exception {
        try(RoutedScene fixture=new RoutedScene(true)) {
            fixture.enable();fixture.assertCompactBudget();
            WindowProgressTouchBridge.disableRouting();fixture.routing=false;fixture.settle();
            fixture.assertFullNativeBudget();assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertEquals(WindowProgressTouchFixtures.rawCachedBounds(fixture.component),WindowProgressTouchFixtures.returnedBounds(fixture.component));
            WindowProgressTouchBridge.enableRouting();fixture.routing=true;fixture.settle();fixture.assertCompactBudget();
            Rect nativeBeforeFailure=WindowProgressTouchFixtures.rawCachedBounds(fixture.component);
            Rect extendedBeforeFailure=WindowProgressTouchFixtures.rawExtendedBounds(fixture.component);
            WindowProgressTouchBridge.setGestureProtected(fixture.track.bar,fixture.decor(),true);
            WindowProgressTouchFixtures.setFailNativeRefresh(true);
            fixture.track.params.height=69;fixture.track.bar.requestLayout();fixture.settle();
            assertEquals("A failed native sample must reserve the conservative native footprint",601,fixture.track.params.bottomMargin);
            assertEquals(69,fixture.track.bar.getHeight());fixture.assertPadding();
            assertTrue(fixture.scene.logs.stream().filter(line->line.contains("progress_geometry;")).reduce((first,last)->last)
                    .orElseThrow().contains("layout_mode=native_hit"));
            assertTrue("A pure sampling exception must retain a valid scoped gesture guard",
                    WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertTrue(WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            assertEquals(nativeBeforeFailure,WindowProgressTouchFixtures.rawCachedBounds(fixture.component));
            assertEquals(extendedBeforeFailure,WindowProgressTouchFixtures.rawExtendedBounds(fixture.component));
            WindowProgressTouchBridge.clearGesture(fixture.track.bar);
            View tab=fixture.tabs().getChildAt(0);float x=fixture.bounds(tab).exactCenterX(),y=fixture.tabTouchY(tab);
            assertTrue("The stale native cache still overlaps this button while sampling fails",fixture.rawHit().contains((int)x,(int)y));
            fixture.dispatch(MotionEvent.ACTION_DOWN,x,y);
            assertTrue("A new protected DOWN must remain safe even in the conservative layout mode",
                    WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            assertEquals(nativeBeforeFailure,WindowProgressTouchFixtures.rawCachedBounds(fixture.component));
            WindowProgressTouchFixtures.setFailNativeRefresh(false);
            fixture.track.params.height=76;fixture.track.bar.requestLayout();fixture.settle();fixture.assertCompactBudget();
            assertTrue("Recovery must not change ownership of the already-protected gesture",
                    WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            fixture.dispatch(MotionEvent.ACTION_CANCEL,x,y);ShadowLooper.shadowMainLooper().idle();
            fixture.dispatch(MotionEvent.ACTION_DOWN,fixture.bounds(tab).exactCenterX(),fixture.tabTouchY(tab));
            assertTrue(WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            fixture.scene.controller.close();fixture.layout();fixture.decor().getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("Theme close restores the native Pro margin",146,fixture.track.params.bottomMargin);
            assertEquals(76,fixture.track.params.height);fixture.assertPadding();
            assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertEquals(WindowProgressTouchFixtures.rawCachedBounds(fixture.component),WindowProgressTouchFixtures.returnedBounds(fixture.component));
            fixture.dispatch(MotionEvent.ACTION_CANCEL,fixture.bounds(tab).exactCenterX(),fixture.tabTouchY(tab));
        }
    }

    @Test public void nativeHeightChangesAndALargeThumbPreserveTheCompleteViewBudget() throws Exception {
        try(RoutedScene fixture=new RoutedScene(true)) {
            GradientDrawable thumb=new GradientDrawable();thumb.setShape(GradientDrawable.OVAL);thumb.setColor(Color.WHITE);thumb.setSize(48,48);
            fixture.track.bar.setThumb(thumb);
            fixture.enable();fixture.assertCompactBudget();
            for(int nativeHeight:new int[]{69,76,69,76}) {
                // Replay a native state callback, without claiming that pause continuously writes on the phone.
                fixture.track.params.height=nativeHeight;fixture.track.bar.setPadding(38,23,38,38);fixture.track.bar.requestLayout();
                fixture.settle();fixture.assertCompactBudget();
                assertEquals(nativeHeight,fixture.track.bar.getHeight());assertSame(thumb,fixture.track.bar.getThumb());
                assertTrue("The complete View is reserved, including a thumb much taller than the painted line",
                        fixture.track.bar.getHeight()>=thumb.getIntrinsicHeight());
                int margin=fixture.track.params.bottomMargin,carrier=fixture.scene.carrierParams.bottomMargin;
                fixture.settle();assertEquals(margin,fixture.track.params.bottomMargin);assertEquals(carrier,fixture.scene.carrierParams.bottomMargin);
            }
        }
    }

    @Test public void unknownComponentsAndChangedLayoutOrWindowIdentitiesNeverRetainProtection() throws Exception {
        try(RoutedScene fixture=new RoutedScene(true)) {
            fixture.enable();fixture.assertCompactBudget();
            Rect original=WindowProgressTouchFixtures.rawCachedBounds(fixture.component);
            assertSame(original,WindowProgressTouchBridge.filterDelegateBounds(new Object(),original));
            View foreignRoot=new FrameLayout(fixture.scene.activity),tab=fixture.tabs().getChildAt(0);
            float x=fixture.bounds(tab).exactCenterX(),y=fixture.tabTouchY(tab);
            fixture.notifyWindowOnly(MotionEvent.ACTION_DOWN,foreignRoot,x,y);
            assertSame("Foreign-window DOWN cannot establish this window's protection",original,
                    WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original));
            fixture.dispatch(MotionEvent.ACTION_DOWN,x,y);
            assertTrue(WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            fixture.notifyWindowOnly(MotionEvent.ACTION_UP,foreignRoot,x,y);ShadowLooper.shadowMainLooper().idle();
            assertTrue("Foreign-window UP and its posted fallback cannot clear this window's active guard",
                    WindowProgressTouchFixtures.returnedBounds(fixture.component).isEmpty());
            fixture.dispatch(MotionEvent.ACTION_UP,x,y);
            assertSame(original,WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original));
            WindowProgressTouchBridge.setGestureProtected(fixture.track.bar,fixture.decor(),true);
            assertTrue(WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original).isEmpty());
            assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,new FrameLayout(fixture.scene.activity)));
            assertTrue("A foreign query must not destroy the legitimate window mapping",
                    WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertTrue(WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original).isEmpty());

            fixture.track.bar.setLayoutParams(new androidx.constraintlayout.widget.ConstraintLayout.LayoutParams(fixture.track.params));
            assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertSame("A host LP replacement revokes this component's gesture protection",original,
                    WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original));
            fixture.track.bar.setLayoutParams(fixture.track.params);fixture.settle();
            assertNotNull(WindowProgressTouchBridge.refresh(fixture.track.bar,fixture.decor(),true));
            assertTrue(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            WindowProgressTouchBridge.setGestureProtected(fixture.track.bar,fixture.decor(),true);
            assertTrue(WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original).isEmpty());
            fixture.scene.root.removeView(fixture.track.owner);
            new FrameLayout(fixture.scene.activity).addView(fixture.track.owner,new FrameLayout.LayoutParams(-1,-1));
            assertFalse(WindowProgressTouchBridge.canRoute(fixture.track.bar,fixture.decor()));
            assertSame("Moving out of the registered window must revoke protection",original,
                    WindowProgressTouchBridge.filterDelegateBounds(fixture.component,original));
        }
    }

    private static final class RoutedScene implements AutoCloseable {
        final LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false);
        final ProTouchDelegateFrameLayout root=new ProTouchDelegateFrameLayout(scene.activity);
        final LiquidGlassClearanceControllerTest.WindowProgress track;
        final Button like=new Button(scene.activity);
        final AtomicInteger likes=new AtomicInteger();
        final Object component;
        boolean routing;
        RoutedScene(boolean supported) throws Exception {
            ((ViewGroup)scene.root.getParent()).removeView(scene.root);
            root.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(root);
            // Model the host's immersive full-window fragment, independently of the test Activity's title/insets.
            ((ViewGroup)root.getParent()).removeView(root);((ViewGroup)decor()).addView(root,new FrameLayout.LayoutParams(-1,-1));
            root.setManagerFirstRouting(true);
            track=new LiquidGlassClearanceControllerTest.WindowProgress(scene);
            track.params.height=76;track.params.bottomMargin=146;track.bar.setPadding(38,23,38,38);
            track.preview.setVisibility(View.GONE);scene.timeline.setVisibility(View.GONE);
            scene.carrierParams.bottomMargin=210;
            scene.navigationParams.height=270;scene.navigationParams.bottomMargin=98;
            scene.rightParams.height=120;scene.right.requestLayout();
            like.setText("赞");like.setTextSize(10);like.setPadding(0,0,0,0);like.setMinWidth(0);like.setMinHeight(0);
            like.setOnClickListener(view->likes.incrementAndGet());
            RelativeLayout.LayoutParams likeParams=new RelativeLayout.LayoutParams(44,44);likeParams.addRule(RelativeLayout.CENTER_HORIZONTAL);
            scene.right.addView(like,likeParams);
            for(View node:LiquidGlassTargets.nodes(root))node.forceLayout();layout();
            assertEquals(3.75f,root.getResources().getDisplayMetrics().density,.001f);
            assertEquals(PHONE_WIDTH,root.getWidth());assertEquals(PHONE_HEIGHT,root.getHeight());
            component=WindowProgressTouchFixtures.register(root,track.bar,213,180);
            WindowProgressTouchFixtures.setBoundsFilter(WindowProgressTouchBridge::filterDelegateBounds);
            if(supported){WindowProgressTouchBridge.enableRouting();routing=true;}
        }
        void enable(){scene.enable(true);settle();}
        View decor(){return scene.activity.getWindow().getDecorView();}
        Rect bounds(View view){return scene.bounds(view);}
        ViewGroup tabs(){return scene.navigation.findViewById(0x7f0a7983);}
        float tabTouchY(View tab){return bounds(tab).top+12;}
        Rect rawHit(){return WindowProgressTouchFixtures.rawCachedBoundsOnScreen(component);}
        void layout() {
            measureAndLayout(decor());measureAndLayout(root);
        }
        void settle() {
            for(int i=0;i<10;i++) {
                layout();decor().getViewTreeObserver().dispatchOnGlobalLayout();
                assertViewport("iteration "+i+" before idle");
                ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));
                // Check before manual layout can conceal a different ViewRoot frame.
                assertViewport("iteration "+i+" after idle, before manual layout");
                layout();decor().getViewTreeObserver().dispatchOnPreDraw();
                assertViewport("iteration "+i+" after preDraw");
            }
            layout();assertViewport("final layout");
        }
        void assertViewport(String phase) {
            assertDisplayViewport(phase);
            String detail=phase+" "+geometry();
            assertEquals(detail+" decor width",PHONE_WIDTH,decor().getWidth());
            assertEquals(detail+" decor height",PHONE_HEIGHT,decor().getHeight());
            assertEquals(detail+" native root width",PHONE_WIDTH,root.getWidth());
            assertEquals(detail+" native root height",PHONE_HEIGHT,root.getHeight());
        }
        String geometry() {
            DisplayMetrics metrics=root.getResources().getDisplayMetrics();
            return "decor="+bounds(decor())+", nativeRoot="+bounds(root)+", bar="+bounds(track.bar)
                    +", rawHit="+rawHit()+", navigation="+bounds(scene.navigation)
                    +", resources="+metrics.widthPixels+"x"+metrics.heightPixels+"@"+metrics.density;
        }
        void assertPadding(){assertEquals(38,track.bar.getPaddingLeft());assertEquals(23,track.bar.getPaddingTop());assertEquals(38,track.bar.getPaddingRight());assertEquals(38,track.bar.getPaddingBottom());}
        void assertFullNativeBudget() {
            assertEquals(601,track.params.bottomMargin);assertEquals(271,bounds(scene.navigation).top-(bounds(track.bar).bottom-track.bar.getPaddingBottom()));
            assertEquals("Native cache must use the same viewport: "+geometry(),53,bounds(scene.navigation).top-rawHit().bottom);assertPadding();
        }
        void assertCompactBudget() {
            assertTrue("The exact managed component/root mapping must be available: "+geometry(),WindowProgressTouchBridge.canRoute(track.bar,decor()));
            assertEquals(421,track.params.bottomMargin);assertEquals(270,scene.navigation.getHeight());assertEquals(98,scene.navigationParams.bottomMargin);
            assertEquals(53,bounds(scene.navigation).top-bounds(track.bar).bottom);
            assertEquals(91,bounds(scene.navigation).top-(bounds(track.bar).bottom-track.bar.getPaddingBottom()));
            int upperBottom=Math.max(bounds(scene.metadata).bottom,Math.max(bounds(like).bottom,bounds(scene.muteHitbox).bottom));
            assertTrue("Actual upper controls clear the complete Pro View",upperBottom<=bounds(track.bar).top-53);
            assertEquals("Upper controls must also return close to the track, not retain the old 213px empty band",
                    53,bounds(track.bar).top-upperBottom);
            assertEquals("Compact layout must preserve the native upper interaction extension",213,bounds(track.bar).top-rawHit().top);
            assertEquals("Compact layout must preserve the native lower interaction extension",180,rawHit().bottom-bounds(track.bar).bottom);
            assertPadding();
        }
        void protectedTap(float x,float y) {
            assertTrue("The button must overlap native extension, exercising manager priority",rawHit().contains((int)x,(int)y));
            assertFalse(bounds(track.bar).contains((int)x,(int)y));
            Rect raw=WindowProgressTouchFixtures.rawCachedBounds(component),extended=WindowProgressTouchFixtures.rawExtendedBounds(component);
            Object providers=WindowProgressTouchFixtures.providers(component);int starts=track.starts.get();
            dispatch(MotionEvent.ACTION_DOWN,x,y);
            assertTrue("Only the getter return yields during this protected gesture",WindowProgressTouchFixtures.returnedBounds(component).isEmpty());
            assertFalse(WindowProgressTouchFixtures.rawCachedBounds(component).isEmpty());
            assertEquals(raw,WindowProgressTouchFixtures.rawCachedBounds(component));assertEquals(extended,WindowProgressTouchFixtures.rawExtendedBounds(component));
            assertSame(providers,WindowProgressTouchFixtures.providers(component));
            dispatch(MotionEvent.ACTION_UP,x,y);
            assertEquals(raw,WindowProgressTouchFixtures.returnedBounds(component));assertEquals(starts,track.starts.get());
            ShadowLooper.shadowMainLooper().idle();
        }
        void tap(float x,float y){dispatch(MotionEvent.ACTION_DOWN,x,y);dispatch(MotionEvent.ACTION_UP,x,y);ShadowLooper.shadowMainLooper().idle();}
        void dispatch(int action,float x,float y) {
            long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
            try {if(scene.controller!=null)scene.controller.onTouch(event,decor());decor().dispatchTouchEvent(event);}
            finally {if(scene.controller!=null)scene.controller.onTouchDispatched(event,decor());event.recycle();}
        }
        void notifyWindowOnly(int action,View dispatchRoot,float x,float y) {
            long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);
            try {scene.controller.onTouch(event,dispatchRoot);scene.controller.onTouchDispatched(event,dispatchRoot);}
            finally {event.recycle();}
        }
        @Override public void close() {
            try {scene.close();}
            finally {WindowProgressTouchFixtures.setBoundsFilter(null);WindowProgressTouchFixtures.setFailNativeRefresh(false);
                if(routing){WindowProgressTouchBridge.disableRouting();routing=false;}}
        }
        static void measureAndLayout(View view) {
            view.measure(View.MeasureSpec.makeMeasureSpec(PHONE_WIDTH,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(PHONE_HEIGHT,View.MeasureSpec.EXACTLY));
            view.layout(0,0,PHONE_WIDTH,PHONE_HEIGHT);
        }
    }
}
