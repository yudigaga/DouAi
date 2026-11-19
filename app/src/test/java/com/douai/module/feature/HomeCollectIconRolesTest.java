package com.douai.module.feature;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.airbnb.lottie.LottieAnimationView;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.ui.BuiltInNavigationIcons;
import com.douai.module.config.HomeUiCustomizationSettings.IconState;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsRepository;
import java.io.InputStream;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** The verified 204e/240b pair has artwork roles; its host owns flags and visibility. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeCollectIconRolesTest {
    private static final int ROOT=0x7f0a204e, FRAME=0x7f0a240b;
    private static final int SELECTED=0x7f0a2010, NORMAL=0x7f0a2439;
    private static final String NORMAL_FILE="feed/03-collect.webp";
    private static final String SELECTED_FILE="feed/03-collect-active.webp";

    @Test public void qualifiedCollectPairUsesFixedArtworkRolesWithoutSelectionFlags() throws Exception {
        try(Fixture f=new Fixture(true)) {
            RunningDrawable initialSelected=new RunningDrawable();initialSelected.start();
            f.selected.completeComposition(initialSelected);
            f.hostCollect(false);f.assertNoSelectionFlags();f.start(configuration(false));
            f.assertNoSelectionFlags();

            // RED 1: the old runtime never admits normal #2439.
            assertPixels(f.normal,NORMAL_FILE);
            assertPixels(f.selected,SELECTED_FILE);f.assertNoSelectionFlags();
            int glyph=Math.round(24*f.activity.getResources().getDisplayMetrics().density);
            assertEquals(glyph,f.normal.getDrawable().getBounds().width());
            assertEquals(glyph,f.selected.getDrawable().getBounds().width());
            int[] normalInk=drawnBounds(f.normal);
            f.hostCollect(true);f.custom.sync();
            assertArrayEquals("Native normal and selected slots draw one optical size",normalInk,drawnBounds(f.selected));
            f.hostCollect(false);f.custom.sync();
            assertFalse(initialSelected.isRunning());f.assertPresentation(View.VISIBLE,View.INVISIBLE);
            f.clickAndExpect(1);
            f.hostCollect(true);f.custom.sync();
            assertPixels(f.selected,SELECTED_FILE);assertPixels(f.normal,NORMAL_FILE);
            f.assertNoSelectionFlags();f.assertPresentation(View.INVISIBLE,View.VISIBLE);f.clickAndExpect(2);
            f.hostCollect(false);f.custom.sync();f.custom.sync();
            assertPixels(f.normal,NORMAL_FILE);assertPixels(f.selected,SELECTED_FILE);
            f.assertNoSelectionFlags();f.assertPresentation(View.VISIBLE,View.INVISIBLE);

            Drawable latestNormal=new ColorDrawable(Color.CYAN);
            RunningDrawable latestSelected=new RunningDrawable();latestSelected.start();
            f.normal.setImageDrawable(latestNormal);f.selected.completeComposition(latestSelected);
            Object latestComposition=f.selected.getComposition();f.custom.refresh();
            assertPixels(f.normal,NORMAL_FILE);assertPixels(f.selected,SELECTED_FILE);
            assertFalse(latestSelected.isRunning());assertSame(latestComposition,f.selected.getComposition());
            f.frame.setId(View.NO_ID);f.custom.sync();
            assertSame("An invalid full pair releases its overridden normal role on sync",latestNormal,f.normal.getDrawable());
            assertPixels(f.selected,NORMAL_FILE);assertFalse(latestSelected.isRunning());
            assertSame(latestComposition,f.selected.getComposition());
            f.assertNoSelectionFlags();f.assertPresentation(View.VISIBLE,View.INVISIBLE);f.clickAndExpect(3);
            f.frame.setId(FRAME);f.custom.refresh();
            assertPixels(f.normal,NORMAL_FILE);assertPixels(f.selected,SELECTED_FILE);
            assertFalse(latestSelected.isRunning());assertSame(latestComposition,f.selected.getComposition());
            f.assertNoSelectionFlags();f.assertPresentation(View.VISIBLE,View.INVISIBLE);
            f.configure("{}");
            assertSame(latestNormal,f.normal.getDrawable());assertSame(latestSelected,f.selected.getDrawable());
            assertTrue(latestSelected.isRunning());assertSame(latestComposition,f.selected.getComposition());
            f.assertNoSelectionFlags();f.assertPresentation(View.VISIBLE,View.INVISIBLE);

            f.configure(configuration(false));
            Drawable lastNormal=new ColorDrawable(Color.YELLOW);
            RunningDrawable lastSelected=new RunningDrawable();lastSelected.start();
            f.normal.setImageDrawable(lastNormal);f.selected.completeComposition(lastSelected);
            Object lastComposition=f.selected.getComposition();
            f.custom.close();
            assertSame("Close must respect an unsampled host write",lastNormal,f.normal.getDrawable());
            assertSame(lastSelected,f.selected.getDrawable());assertTrue(lastSelected.isRunning());
            assertSame(lastComposition,f.selected.getComposition());f.assertNoSelectionFlags();
            f.assertPresentation(View.VISIBLE,View.INVISIBLE);f.clickAndExpect(4);
        }
    }

    @Test public void selectedOnlyCollectRoleWaitsForCompositionAndReleasesWhenPairQualificationChanges() throws Exception {
        try(Fixture f=new Fixture(true)) {
            String selectedOnly=configuration(true);
            assertNull(HomeUiCustomizationSettings.readIcon(selectedOnly,"feed_collect",IconState.DAY_NORMAL));
            Drawable placeholder=new ColorDrawable(Color.GRAY);f.selected.setHostPlaceholder(placeholder);
            f.hostCollect(true);f.start(selectedOnly);f.custom.sync();
            assertSame(placeholder,f.selected.getDrawable());assertNull(f.selected.getComposition());
            assertSame(f.normalNative,f.normal.getDrawable());f.assertNoSelectionFlags();
            f.assertPresentation(View.INVISIBLE,View.VISIBLE);
            RunningDrawable loaded=new RunningDrawable();loaded.start();f.selected.completeComposition(loaded);
            Object composition=f.selected.getComposition();
            assertNotNull("Pending callback must survive customization",composition);f.custom.sync();
            f.assertNoSelectionFlags();

            // RED 2 is independent of #2439 admission: false flags must still use selected artwork.
            assertPixels(f.selected,SELECTED_FILE);
            assertFalse(loaded.isRunning());assertSame(composition,f.selected.getComposition());
            assertSame(f.normalNative,f.normal.getDrawable());f.assertNoSelectionFlags();

            f.frame.removeView(f.normal);f.outside.addView(f.normal,f.normalLayout);
            f.custom.sync();assertReleased(f,loaded,composition);
            f.outside.removeView(f.normal);f.frame.addView(f.normal,f.normalLayout);
            f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);assertFalse(loaded.isRunning());

            f.frame.setId(View.NO_ID);f.custom.sync();assertReleased(f,loaded,composition);
            f.frame.setId(FRAME);f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);
            f.collectRoot.setId(View.NO_ID);f.custom.sync();assertReleased(f,loaded,composition);
            f.collectRoot.setId(ROOT);f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);
            f.normal.setId(View.NO_ID);f.custom.sync();assertReleased(f,loaded,composition);
            f.normal.setId(NORMAL);f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);
            f.selected.setId(View.NO_ID);f.custom.sync();assertReleased(f,loaded,composition);
            f.selected.setId(SELECTED);f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);

            View duplicate=new View(f.activity);duplicate.setId(NORMAL);f.frame.addView(duplicate);
            f.custom.sync();assertReleased(f,loaded,composition);
            f.frame.removeView(duplicate);f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);
            View wrongType=new View(f.activity);wrongType.setId(NORMAL);
            f.frame.removeView(f.normal);f.frame.addView(wrongType);
            f.custom.sync();assertReleased(f,loaded,composition);
            assertNull(wrongType.getBackground());
            f.frame.removeView(wrongType);f.frame.addView(f.normal,f.normalLayout);
            f.custom.refresh();assertPixels(f.selected,SELECTED_FILE);

            RunningDrawable latest=new RunningDrawable();latest.start();f.selected.completeComposition(latest);
            Drawable latestNormal=new ColorDrawable(Color.MAGENTA);f.normal.setImageDrawable(latestNormal);
            Object latestComposition=f.selected.getComposition();f.custom.sync();
            assertPixels(f.selected,SELECTED_FILE);assertFalse(latest.isRunning());
            assertSame(latestNormal,f.normal.getDrawable());f.configure("{}");
            assertSame(latest,f.selected.getDrawable());assertTrue(latest.isRunning());
            assertSame(latestComposition,f.selected.getComposition());assertSame(latestNormal,f.normal.getDrawable());
            f.assertNoSelectionFlags();f.assertPresentation(View.INVISIBLE,View.VISIBLE);f.clickAndExpect(1);
        }
    }

    @Test public void standaloneAndUnknownCollectShapesRetainGenericFallback() throws Exception {
        // Selection flags here describe only the legacy standalone API, never the qualified pair.
        try(Fixture f=new Fixture(true)) {
            f.collectRoot.removeView(f.frame);
            ImageView standalone=new ImageView(f.activity);standalone.setId(SELECTED);
            Drawable nativeImage=new ColorDrawable(Color.RED);standalone.setImageDrawable(nativeImage);
            f.outside.addView(standalone);f.start(configuration(false));
            assertPixels(standalone,NORMAL_FILE);
            standalone.setSelected(true);f.custom.sync();assertPixels(standalone,SELECTED_FILE);
            standalone.setSelected(false);standalone.setActivated(true);f.custom.sync();
            assertPixels(standalone,SELECTED_FILE);
            standalone.setActivated(false);f.custom.sync();assertPixels(standalone,NORMAL_FILE);
            f.configure("{}");assertSame(nativeImage,standalone.getDrawable());
        }
        for(String shape:new String[]{"wrong-root-class","wrong-root-id","missing-buddy",
                "reparented-buddy","duplicate-buddy","plain-selected","wrong-type-buddy","wrong-type-selected"}) {
            try(Fixture f=new Fixture(!shape.equals("wrong-root-class"))) {
                ImageView generic=f.selected;
                f.selected.completeComposition(new ColorDrawable(Color.GREEN));
                if(shape.equals("wrong-root-id"))f.collectRoot.setId(View.NO_ID);
                if(shape.equals("missing-buddy"))f.frame.removeView(f.normal);
                if(shape.equals("reparented-buddy")) {
                    f.frame.removeView(f.normal);f.outside.addView(f.normal,f.normalLayout);
                }
                if(shape.equals("duplicate-buddy")) {
                    View duplicate=new View(f.activity);duplicate.setId(NORMAL);f.frame.addView(duplicate);
                }
                if(shape.equals("plain-selected")) {
                    f.frame.removeView(f.selected);generic=new ImageView(f.activity);generic.setId(SELECTED);
                    generic.setImageDrawable(new ColorDrawable(Color.BLUE));f.frame.addView(generic,f.selectedLayout);
                }
                View wrongType=null;
                if(shape.equals("wrong-type-buddy") || shape.equals("wrong-type-selected")) {
                    wrongType=new View(f.activity);wrongType.setId(shape.equals("wrong-type-buddy")?NORMAL:SELECTED);
                    wrongType.setBackground(new ColorDrawable(Color.YELLOW));
                    f.frame.removeView(shape.equals("wrong-type-buddy")?f.normal:f.selected);f.frame.addView(wrongType);
                    if(shape.equals("wrong-type-selected"))generic=null;
                }
                Drawable wrongBackground=wrongType==null?null:wrongType.getBackground();
                f.start(configuration(false));f.custom.sync();
                assertSame(shape+": unknown #2439 remains native",f.normalNative,f.normal.getDrawable());
                if(generic!=null) {
                    assertPixels(generic,NORMAL_FILE);assertNoSelectionFlags(generic);
                    generic.setPressed(true);f.custom.sync();assertPixels(generic,NORMAL_FILE);
                    assertNoSelectionFlags(generic);
                }
                if(wrongType!=null)assertSame(shape,wrongBackground,wrongType.getBackground());
                assertNull(f.normal.getBackground());assertFalse(f.frame.isSelected());assertFalse(f.frame.isActivated());
            }
        }
    }

    private static void assertReleased(Fixture f,RunningDrawable original,Object composition) {
        assertSame("A cached invalid pair releases on sync without rediscovery",original,f.selected.getDrawable());
        assertTrue(original.isRunning());assertSame(composition,f.selected.getComposition());
        assertSame(f.normalNative,f.normal.getDrawable());f.assertNoSelectionFlags();
        assertEquals(View.INVISIBLE,f.normal.getVisibility());assertEquals(View.VISIBLE,f.selected.getVisibility());
    }
    private static String configuration(boolean selectedOnly) throws Exception {
        Map<IconState,byte[]> variants=new EnumMap<>(IconState.class);
        variants.put(IconState.DAY_NORMAL,selectedOnly?null:fixtureBytes(NORMAL_FILE));
        variants.put(IconState.DAY_SELECTED,fixtureBytes(SELECTED_FILE));
        return HomeUiCustomizationSettings.putIcons("{}","feed_collect",variants);
    }
    private static byte[] fixtureBytes(String path) throws Exception {
        try(InputStream stream=HomeCollectIconRolesTest.class.getResourceAsStream("/META-INF/douai/supplied-icon-fixtures/"+path)) {
            assertNotNull("Independent supplied fixture: "+path,stream);return stream.readAllBytes();
        }
    }
    private static void assertPixels(ImageView image,String path) throws Exception {
        assertTrue("Expected bound BitmapDrawable for "+path,image.getDrawable() instanceof BitmapDrawable);
        String design=path.substring(0,path.length()-5);boolean selected=design.endsWith("-active");
        if(selected)design=design.substring(0,design.length()-7);
        byte[] bytes=BuiltInNavigationIcons.symbolForPath(design,selected);Bitmap expected=BitmapFactory.decodeByteArray(bytes,0,bytes.length);
        assertNotNull("Generated symbol decodes: "+path,expected);
        try {
            assertTrue("Rendered builtin matches its transparent vector: "+path,expected.sameAs(((BitmapDrawable)image.getDrawable()).getBitmap()));
            assertEquals(Math.round(24*image.getResources().getDisplayMetrics().density),image.getDrawable().getIntrinsicWidth());
            ImageView.ScaleType expectedScale=image instanceof LottieAnimationView
                    ?ImageView.ScaleType.CENTER_INSIDE:ImageView.ScaleType.CENTER;
            assertEquals("Preserve the native non-expanding mode",expectedScale,image.getScaleType());
            int[] drawn=drawnBounds(image);
            if(drawn!=null) {
                int cap=Math.min(image.getDrawable().getIntrinsicWidth(),image.getWidth());
                int capHeight=Math.min(image.getDrawable().getIntrinsicHeight(),image.getHeight());
                assertTrue("Rendered symbol cannot grow past its 24dp drawable or host bounds: "+path+
                                " size="+drawn[0]+"x"+drawn[1]+" cap="+cap+"x"+capHeight,
                        drawn[0]<=cap+1 && drawn[1]<=capHeight+1);
            }
        }
        finally {expected.recycle();}
    }
    private static int[] drawnBounds(ImageView image) {
        if(image.getWidth()<=0 || image.getHeight()<=0)return null;
        Bitmap rendered=Bitmap.createBitmap(image.getWidth(),image.getHeight(),Bitmap.Config.ARGB_8888);
        try {
            image.draw(new Canvas(rendered));int left=rendered.getWidth(),top=rendered.getHeight(),right=-1,bottom=-1;
            for(int y=0;y<rendered.getHeight();y++)for(int x=0;x<rendered.getWidth();x++)
                if(Color.alpha(rendered.getPixel(x,y))>0){left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x);bottom=Math.max(bottom,y);}
            return right<left || bottom<top?new int[]{0,0}:new int[]{right-left+1,bottom-top+1};
        }finally{rendered.recycle();}
    }
    private static void assertNoSelectionFlags(View view) {
        assertFalse(view.isSelected());assertFalse(view.isActivated());
        for(int state:view.getDrawableState())assertFalse(state==android.R.attr.state_selected || state==android.R.attr.state_activated);
        if(view instanceof ImageView && ((ImageView)view).getDrawable()!=null)
            for(int state:((ImageView)view).getDrawable().getState())
                assertFalse(state==android.R.attr.state_selected || state==android.R.attr.state_activated);
    }
    private static final class Attributes {
        final View view;final ViewGroup.LayoutParams layout;final float alpha;
        final int width,height;final Drawable background;
        Attributes(View view) {
            this.view=view;layout=view.getLayoutParams();width=layout.width;height=layout.height;
            alpha=view.getAlpha();background=view.getBackground();
        }
        void assertPreserved() {
            assertSame(layout,view.getLayoutParams());assertEquals(width,layout.width);assertEquals(height,layout.height);
            assertEquals(alpha,view.getAlpha(),0f);assertSame(background,view.getBackground());
        }
    }
    private static final class Fixture implements AutoCloseable {
        final ActivityController<Activity> controller=Robolectric.buildActivity(Activity.class).setup();
        final Activity activity=controller.get();
        final SharedPreferences preferences=activity.getSharedPreferences(SettingsRepository.PREFERENCES_NAME,0);
        final HostSettingsStore store;
        final FrameLayout root=new FrameLayout(activity),outside=new FrameLayout(activity),frame=new FrameLayout(activity);
        final ViewGroup collectRoot;
        final LottieAnimationView selected=new LottieAnimationView(activity);
        final ImageView normal=new ImageView(activity);
        final TextView count=new TextView(activity);
        final Drawable normalNative=new ColorDrawable(Color.RED);
        final FrameLayout.LayoutParams normalLayout=new FrameLayout.LayoutParams(-1,-1);
        final FrameLayout.LayoutParams selectedLayout;
        final Attributes rootAttributes,frameAttributes,normalAttributes,selectedAttributes,countAttributes;
        final ColorStateList tint=ColorStateList.valueOf(Color.MAGENTA);
        final AtomicInteger frameClicks=new AtomicInteger(),normalClicks=new AtomicInteger(),selectedClicks=new AtomicInteger();
        HomeUiCustomization custom;
        Fixture(boolean exactRootClass) {
            preferences.edit().clear().commit();store=new HostSettingsStore(activity);
            collectRoot=exactRootClass?new ConstraintLayout(activity):new FrameLayout(activity);
            collectRoot.setId(ROOT);frame.setId(FRAME);selected.setId(SELECTED);normal.setId(NORMAL);count.setId(0x7f0a201b);
            root.addView(collectRoot,new FrameLayout.LayoutParams(180,90));root.addView(outside,new FrameLayout.LayoutParams(60,60));
            ViewGroup.LayoutParams frameLayout=exactRootClass?new ConstraintLayout.LayoutParams(dp(24),dp(24)):
                    new FrameLayout.LayoutParams(dp(24),dp(24));
            collectRoot.addView(frame,frameLayout);
            selectedLayout=new FrameLayout.LayoutParams(dp(44),dp(44),Gravity.CENTER);
            selected.setScaleType(ImageView.ScaleType.CENTER_INSIDE);normal.setImageDrawable(normalNative);
            frame.addView(selected,selectedLayout);frame.addView(normal,normalLayout);
            count.setText("17");collectRoot.addView(count,new ViewGroup.LayoutParams(dp(40),dp(20)));
            frame.setClipChildren(false);frame.setClipToPadding(false);collectRoot.setClipChildren(false);
            collectRoot.setAlpha(.91f);frame.setAlpha(.73f);normal.setAlpha(.61f);selected.setAlpha(.42f);count.setAlpha(.84f);
            normal.setImageTintList(tint);selected.setImageTintList(tint);
            frame.setOnClickListener(ignored->frameClicks.incrementAndGet());
            normal.setOnClickListener(ignored->normalClicks.incrementAndGet());
            selected.setOnClickListener(ignored->selectedClicks.incrementAndGet());
            activity.setContentView(root);hostCollect(false);
            rootAttributes=new Attributes(collectRoot);frameAttributes=new Attributes(frame);
            normalAttributes=new Attributes(normal);selectedAttributes=new Attributes(selected);countAttributes=new Attributes(count);
        }
        int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
        void hostCollect(boolean value){normal.setVisibility(value?View.INVISIBLE:View.VISIBLE);selected.setVisibility(value?View.VISIBLE:View.INVISIBLE);}
        void start(String json) {
            store.putString(HomeUiCustomizationSettings.ICONS,json);
            View decor=activity.getWindow().getDecorView();
            decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));
            decor.layout(0,0,360,800);
            custom=new HomeUiCustomization(root,LocalOptions.readFrom(preferences),ignored->{});
        }
        void configure(String json) {
            store.putString(HomeUiCustomizationSettings.ICONS,json);custom.setOptions(LocalOptions.readFrom(preferences));custom.refresh();
        }
        void assertNoSelectionFlags() {
            for(View view:new View[]{root,collectRoot,frame,normal,selected,count})HomeCollectIconRolesTest.assertNoSelectionFlags(view);
        }
        void assertPresentation(int normalVisibility,int selectedVisibility) {
            rootAttributes.assertPreserved();frameAttributes.assertPreserved();normalAttributes.assertPreserved();
            selectedAttributes.assertPreserved();countAttributes.assertPreserved();
            assertSame(root,collectRoot.getParent());assertSame(collectRoot,frame.getParent());
            assertSame(frame,normal.getParent());assertSame(frame,selected.getParent());assertSame(collectRoot,count.getParent());
            assertEquals(2,frame.getChildCount());assertEquals(2,collectRoot.getChildCount());assertEquals("17",count.getText().toString());
            assertEquals(dp(24),frame.getLayoutParams().width);assertEquals(dp(24),frame.getLayoutParams().height);
            assertEquals(dp(44),selected.getLayoutParams().width);assertEquals(dp(44),selected.getLayoutParams().height);
            assertEquals(dp(24),frame.getWidth());assertEquals(dp(24),frame.getHeight());
            assertEquals(dp(24),normal.getWidth());assertEquals(dp(24),normal.getHeight());
            assertEquals(dp(44),selected.getWidth());assertEquals(dp(44),selected.getHeight());
            assertEquals(Gravity.CENTER,selectedLayout.gravity);
            assertEquals(ImageView.ScaleType.CENTER_INSIDE,selected.getScaleType());
            assertEquals(normalVisibility,normal.getVisibility());assertEquals(selectedVisibility,selected.getVisibility());
            assertEquals(View.VISIBLE,collectRoot.getVisibility());assertEquals(View.VISIBLE,frame.getVisibility());
            assertFalse(frame.getClipChildren());assertFalse(frame.getClipToPadding());assertFalse(collectRoot.getClipChildren());
            assertSame(tint,normal.getImageTintList());assertSame(tint,selected.getImageTintList());
        }
        void clickAndExpect(int clicks) {
            assertTrue(frame.performClick());assertTrue(normal.performClick());assertTrue(selected.performClick());
            assertEquals(clicks,frameClicks.get());assertEquals(clicks,normalClicks.get());assertEquals(clicks,selectedClicks.get());
        }
        @Override public void close() {
            try {if(custom!=null)custom.close();}
            finally {preferences.edit().clear().commit();controller.pause().stop().destroy();ShadowLooper.idleMainLooper();}
        }
    }
    private static final class RunningDrawable extends Drawable implements Animatable {
        private boolean running;
        @Override public void draw(Canvas canvas) { }
        @Override public void setAlpha(int alpha) { }
        @Override public void setColorFilter(ColorFilter filter) { }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
        @Override public void start(){running=true;}
        @Override public void stop(){running=false;}
        @Override public boolean isRunning(){return running;}
    }
}
