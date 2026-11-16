package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.BitmapDrawable;
import android.widget.FrameLayout;
import android.widget.TextView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiIconFixtures;
import com.douai.module.config.LiquidGlassSettings;
import com.douai.module.config.LocalOptions;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.tabstrip.item.HomeTopTabItemViewNew.NativeThemeTop;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.Test;
import org.junit.Before;
import org.junit.After;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class HomeIconConfigurationReuseTest {
    private Object previousCache;
    private Object registry;
    private Map<Object,Object> previousNames;
    @Before public void beginWithColdMetadata(){
        previousCache=ReflectionHelpers.getStaticField(HomeUiCustomizationSettings.class,"lastValidatedIcons");
        ReflectionHelpers.setStaticField(HomeUiCustomizationSettings.class,"lastValidatedIcons",null);
        registry=ReflectionHelpers.getStaticField(HomeUiCustomization.class,"NAVIGATION_METADATA");
        Map<Object,Object> names=ReflectionHelpers.getField(registry,"observed");
        synchronized(registry){previousNames=new LinkedHashMap<>(names);names.clear();}
    }
    @After public void restorePriorMetadata(){
        try {ReflectionHelpers.setStaticField(HomeUiCustomizationSettings.class,"lastValidatedIcons",previousCache);}
        finally {if(registry!=null){Map<Object,Object> names=ReflectionHelpers.getField(registry,"observed");synchronized(registry){names.clear();if(previousNames!=null)names.putAll(previousNames);}}}
    }
    @Test public void workerPublicationPreparesTheExactMetadataBeforeANewWindowConsumesIt() throws Exception {
        try(Scene s=new Scene()) {
            String json=json(Color.CYAN);publishOnWorker(s.controller,options(json));
            try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
                assertNotNull(occupied);ShadowLooper.idleMainLooper();
                HomeUiCustomization consumer=s.consumer();
                assertEquals(json,ReflectionHelpers.getField(consumer,"rawJson"));
                assertSame(HomeUiCustomizationSettings.readEncodedIcons(json),ReflectionHelpers.getField(consumer,"icons"));
                assertEquals(1,s.tab.idReads);
            }
            s.controller.setOptions(options(json));
            assertEquals(Color.CYAN,((BitmapDrawable)s.title.getCompoundDrawables()[1]).getBitmap().getPixel(4,4));
        }
    }

    @Test public void queuedValidThenInvalidSnapshotsRetainTheLastAppliedIcons() throws Exception {
        try(Scene s=new Scene()) {
            String valid=json(Color.RED),invalid="{\"nav_top:homepage_hot\":\"invalid\"}";
            publishOnWorker(s.controller,options(valid));publishOnWorker(s.controller,options(invalid));
            try(MediaMemoryBudget.Reservation occupied=MediaMemoryBudget.tryAcquire(MediaMemoryBudget.CAPACITY_BYTES)) {
                assertNotNull(occupied);ShadowLooper.idleMainLooper();
                assertEquals(valid,ReflectionHelpers.getField(s.consumer(),"rawJson"));
                assertSame(HomeUiCustomizationSettings.readEncodedIcons(valid),ReflectionHelpers.getField(s.consumer(),"icons"));
                assertEquals("Both FIFO snapshots are consumed; invalid icons must not cancel the preceding valid update",2,s.tab.idReads);
            }
            s.controller.setOptions(options(invalid));
            assertEquals(Color.RED,((BitmapDrawable)s.title.getCompoundDrawables()[1]).getBitmap().getPixel(4,4));
        }
    }

    private static void publishOnWorker(LiquidGlassController controller,LocalOptions options) throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        Thread worker=new Thread(()->{try{controller.setOptions(options);}catch(Throwable failure){error.set(failure);}},"icon-publication-fixture");
        worker.setDaemon(true);worker.start();worker.join(5000);
        if(worker.isAlive()){worker.interrupt();worker.join(1000);}
        assertFalse("Publication worker did not finish",worker.isAlive());
        if(error.get()!=null)throw new AssertionError(error.get());
    }
    private static LocalOptions options(String json){return new LocalOptions(Map.of(LiquidGlassSettings.STYLE,LiquidGlassSettings.LIQUID_GLASS,LiquidGlassSettings.GRAVITY,false,HomeUiCustomizationSettings.ICONS,json));}
    private static String json(int color){return HomeUiCustomizationSettings.putIcon("{}","nav_top:homepage_hot",HomeUiIconFixtures.webp(8,8,color));}
    private static final class Scene implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final MainTitleBar bar=new MainTitleBar(activity);
        final TextView title=new TextView(activity);
        final NativeThemeTop tab;
        final LiquidGlassController controller;
        Scene() {
            title.setText("推荐");tab=new NativeThemeTop(activity,title);bar.addView(tab,new FrameLayout.LayoutParams(120,70));
            root.addView(bar,new FrameLayout.LayoutParams(420,80));activity.setContentView(root);
            root.measure(android.view.View.MeasureSpec.makeMeasureSpec(480,android.view.View.MeasureSpec.EXACTLY),android.view.View.MeasureSpec.makeMeasureSpec(800,android.view.View.MeasureSpec.EXACTLY));root.layout(0,0,480,800);
            controller=new LiquidGlassController(activity,new LocalOptions(Map.of(LiquidGlassSettings.STYLE,LiquidGlassSettings.ORIGINAL)),null);
            controller.onActivityResumed(activity);tab.idReads=0;
        }
        HomeUiCustomization consumer(){Map<?,?> windows=ReflectionHelpers.getField(controller,"windows");Object monitor=windows.get(activity.getWindow().getDecorView());assertNotNull(monitor);return ReflectionHelpers.getField(monitor,"customization");}
        @Override public void close(){controller.close();activity.finish();}
    }
}
