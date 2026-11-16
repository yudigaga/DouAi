package com.douai.module.feature;

import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import androidx.appcompat.widget.AppCompatImageView;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

/** Synthetic host transitions; ordinary View routing does not establish real ProTouchManager behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class LiquidGlassClearanceRetryTest {
    @Test public void changedHostInputsDoNotRebindWhileTheSameActiveControlIsStillUnmeasurable() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            AppCompatImageView unknown=addUnmeasurableControl(scene);
            scene.enable(false);scene.settle();
            assertEquals("Unknown active content must retain the native bottom geometry: "+scene.logs,
                    58,scene.navigation.getHeight());
            assertTrue(scene.logs.toString(),scene.logs.stream().anyMatch(line->line.contains("reason=unmeasurable")
                    && line.contains("id=7f0a2da7")));
            Drawable fallback=scene.navigation.getBackground();long fallbackCount=fallbackCount(scene);
            Object monitor=monitor(scene);NavigationGlassClearance clearance=clearance(monitor);
            long revision=clearance.inputRevision();
            for(int i=0;i<4;i++) {
                scene.metadata.setText("@宿主更新 "+i+"\n原生文案增加行数\n仍有未恢复的控件"+"\n内容".repeat(i));
                scene.metadata.setTextSize(15+i);scene.settle();
                assertTrue("The host mutation must reach clearance input revision: "+scene.logs,
                        clearance.inputRevision()>revision);
                revision=clearance.inputRevision();
                assertSame("Still-blocked host changes must not replace the fallback background: "+scene.logs,
                        fallback,scene.navigation.getBackground());
                assertEquals(58,scene.navigation.getHeight());
                assertEquals("The same failure must not repeatedly expand and fall back: "+scene.logs,
                        fallbackCount,fallbackCount(scene));
            }
            assertDrawingFixedPoint(scene,monitor,fallback);
            assertEquals(fallbackCount,fallbackCount(scene));
            unknown.setRotationX(0);scene.settle();scene.settle();
            assertEquals("The actual offending control recovered, so expanded navigation can retry: "+scene.logs,
                    72,scene.navigation.getHeight());
            assertNotSame(fallback,scene.navigation.getBackground());scene.assertClearance(3);
            Drawable recovered=scene.navigation.getBackground();int margin=scene.carrierParams.bottomMargin;
            scene.settle();
            assertSame("Recovered input must converge rather than rebind again",recovered,scene.navigation.getBackground());
            assertEquals(margin,scene.carrierParams.bottomMargin);
            assertDrawingFixedPoint(scene,monitor,recovered);
            assertEquals(fallbackCount,fallbackCount(scene));
        }
    }

    @Test public void anUnappliedNavigationLayoutCannotUnlockAnOlderRejection() throws Exception {
        try(LiquidGlassClearanceControllerTest.Scene scene=new LiquidGlassClearanceControllerTest.Scene(false,false)) {
            AppCompatImageView unknown=addUnmeasurableControl(scene);
            scene.enable(false);scene.settle();
            Object monitor=monitor(scene);NavigationGlassClearance clearance=clearance(monitor);
            Map<View,Long> rejected=rejected(monitor);
            assertTrue(scene.logs.toString(),rejected.containsKey(scene.navigation));
            Drawable fallback=scene.navigation.getBackground();long fallbackCount=fallbackCount(scene);
            scene.metadata.setText("@宿主变化\n第二行\n第三行\n第四行");
            scene.metadata.setMinHeight(scene.metadata.getHeight()+27);scene.layout();
            sync(monitor);
            assertTrue("A changed revision cannot clear an ongoing failure: "+scene.logs,
                    rejected.containsKey(scene.navigation));
            assertNotEquals("Exercise an old rejection whose input revision has changed",
                    rejected.get(scene.navigation).longValue(),clearance.inputRevision());
            unknown.setRotationX(0);
            scene.navigationParams.height=59;scene.navigation.requestLayout();
            assertEquals(58,scene.navigation.getHeight());
            sync(monitor);
            assertFalse("Pending layout is not evidence of recovery: "+clearance.geometryDiagnostic(java.util.List.of(scene.navigation)),
                    clearance.retryReady());
            assertTrue("An empty transient blocked snapshot must not unlock retry: "+scene.logs,
                    rejected.containsKey(scene.navigation));
            assertSame(fallback,scene.navigation.getBackground());assertEquals(fallbackCount,fallbackCount(scene));
        }
    }

    private static AppCompatImageView addUnmeasurableControl(LiquidGlassClearanceControllerTest.Scene scene) {
        AppCompatImageView image=new AppCompatImageView(scene.activity);image.setId(0x7f0a2da7);image.setRotationX(8);
        scene.metadataOwner.addView(image,new FrameLayout.LayoutParams(18,18,Gravity.TOP|Gravity.RIGHT));
        scene.forceInitialLayout();scene.layout();return image;
    }
    private static long fallbackCount(LiquidGlassClearanceControllerTest.Scene scene) {
        return scene.logs.stream().filter(line->line.contains("navigation_clearance_fallback=")).count();
    }
    private static void assertDrawingFixedPoint(LiquidGlassClearanceControllerTest.Scene scene,Object monitor,
            Drawable background) throws Exception {
        int margin=scene.carrierParams.bottomMargin;
        Method preDraw=monitor.getClass().getDeclaredMethod("onPreDraw");preDraw.setAccessible(true);
        for(int i=0;i<5;i++) {
            scene.layout();
            assertTrue("Settled layout must permit drawing on iteration "+i+": "+scene.logs,
                    (Boolean)preDraw.invoke(monitor));
            assertSame("A stable draw must not rebind navigation",background,scene.navigation.getBackground());
            assertEquals("A stable draw must not add another footer reservation",margin,scene.carrierParams.bottomMargin);
        }
    }
    private static Object monitor(LiquidGlassClearanceControllerTest.Scene scene) throws Exception {
        Map<?,?> windows=(Map<?,?>)field(scene.controller,"windows");
        Object monitor=windows.get(scene.activity.getWindow().getDecorView());
        assertNotNull("The activity must have an attached monitor",monitor);return monitor;
    }
    private static NavigationGlassClearance clearance(Object monitor) throws Exception {
        return (NavigationGlassClearance)field(monitor,"clearance");
    }
    @SuppressWarnings("unchecked") private static Map<View,Long> rejected(Object monitor) throws Exception {
        return (Map<View,Long>)field(monitor,"clearanceRejected");
    }
    private static Object field(Object owner,String name) throws Exception {
        Field field=owner.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(owner);
    }
    private static void sync(Object monitor) throws Exception {
        Method method=monitor.getClass().getDeclaredMethod("syncClearance");method.setAccessible(true);method.invoke(monitor);
    }
}
