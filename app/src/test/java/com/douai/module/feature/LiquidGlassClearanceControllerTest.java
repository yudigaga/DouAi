package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorManager;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import androidx.constraintlayout.widget.ConstraintLayout;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.HomeUiCustomizationSettings;
import com.douai.module.config.HomeUiIconFixtures;
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout;
import com.ss.android.ugc.aweme.kiwi.widget.QLayout;
import com.ss.android.ugc.aweme.homepage.tab.ui.bottom.BottomTab;
import com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar;
import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildFrameLayout;
import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowChoreographer;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowSensor;
import org.robolectric.shadows.ShadowSensorManager;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/** Real Android layout/touch integration; synthetic class contracts are not a real host screenshot. */
@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public class LiquidGlassClearanceControllerTest {
    @Test public void largeNativePaddingKeepsThePaintedTrackNearNavigationAndRestoresItsNativeGeometry() throws Exception {
        for(int[] padding:new int[][]{{50,50},{20,80}})try(Scene scene=new Scene(false,false)) {
            com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout touchRoot=
                    new com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout(scene.activity);
            ((ViewGroup)scene.root.getParent()).removeView(scene.root);
            touchRoot.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(touchRoot);
            WindowProgress track=new WindowProgress(scene);track.params.height=118;
            track.bar.setPadding(6,padding[0],9,padding[1]);track.preview.setVisibility(View.GONE);
            scene.timeline.setVisibility(View.GONE);scene.forceInitialLayout();scene.layout();
            Rect owner=scene.bounds(track.owner),media=scene.bounds(scene.media);
            Object nativeComponent=X.WindowProgressTouchFixtures.register(touchRoot,track.bar);
            scene.enable(false);scene.settle();
            assertEquals("Transparent native padding must not become a huge clearance band",48,track.params.height);
            assertEquals("The original 18dp track content must not shrink",18,
                    track.bar.getHeight()-track.bar.getPaddingTop()-track.bar.getPaddingBottom());
            assertEquals(6,track.bar.getPaddingLeft());assertEquals(9,track.bar.getPaddingRight());
            Rect content=scene.bounds(track.bar);content.top+=track.bar.getPaddingTop();content.bottom-=track.bar.getPaddingBottom();
            int visualGap=scene.bounds(scene.navigation).top-content.bottom;
            assertTrue("The painted track stays close to the capsule: "+visualGap,visualGap>=3 && visualGap<=48);
            Rect hit=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent);
            assertTrue(hit.bottom<=scene.bounds(scene.navigation).top-3);assertEquals(48,hit.height());
            X.WindowProgressTouchFixtures.clearCache(nativeComponent);
            assertEquals("Native cache rebuild uses the compact real geometry",hit,
                    X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent));
            int margin=track.params.bottomMargin;scene.settle();assertEquals(margin,track.params.bottomMargin);
            assertEquals(owner,scene.bounds(track.owner));assertEquals(media,scene.bounds(scene.media));
            scene.dragTimeline(track.bar);assertTrue(track.starts.get()>0);assertTrue(track.stops.get()>0);
            ViewGroup row=scene.navigation.findViewById(0x7f0a7983);
            for(int i=0;i<row.getChildCount();i++)scene.click(row.getChildAt(i));
            assertEquals(5,scene.navigationClicks.get());
            scene.controller.close();scene.settle();assertEquals(118,track.params.height);
            assertEquals(padding[0],track.bar.getPaddingTop());assertEquals(padding[1],track.bar.getPaddingBottom());
            assertEquals(39,track.params.bottomMargin);
            assertEquals(X.WindowProgressTouchFixtures.currentBoundsOnScreen(touchRoot,track.bar),
                    X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent));
        }
    }

    @Test public void anEmptyLegacyProgressWrapperDoesNotPushTheFooterAwayFromTheIndependentTrack() throws Exception {
        for(boolean clickable:new boolean[]{false,true})try(Scene scene=new Scene(false,false)) {
            WindowProgress track=new WindowProgress(scene);track.preview.setVisibility(View.GONE);
            scene.timeline.setVisibility(View.GONE);scene.progress.setClickable(clickable);scene.layout();
            scene.enable(false);scene.settle();
            int footerBottom=Math.max(scene.bounds(scene.metadata).bottom,scene.bounds(scene.muteHitbox).bottom);
            int gap=scene.bounds(track.bar).top-footerBottom;
            if(!clickable)assertTrue("An empty wrapper must not add another reservation: "+gap,gap>=4 && gap<=8);
            else assertTrue("A clickable native wrapper still protects its entire hit area",
                    scene.bounds(scene.progress).bottom<=scene.bounds(track.bar).top-4);
            scene.controller.close();scene.settle();scene.assertNativeMargins();
        }
    }

    @Test public void cachedExtendedWindowSeekbarHotzoneClearsNavigationAndRestoresWithoutBusinessActions() throws Exception {
        try(Scene scene=new Scene(false,false)) {
            com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout touchRoot=
                    new com.ss.android.ugc.aweme.o.seekbar.views.touch.ProTouchDelegateFrameLayout(scene.activity);
            ((ViewGroup)scene.root.getParent()).removeView(scene.root);
            touchRoot.addView(scene.root,new FrameLayout.LayoutParams(-1,-1));scene.activity.setContentView(touchRoot);
            WindowProgress global=new WindowProgress(scene);scene.forceInitialLayout();scene.layout();
            Object nativeComponent=X.WindowProgressTouchFixtures.register(touchRoot,global.bar);
            Rect originalCache=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent);
            assertEquals(X.WindowProgressTouchFixtures.currentBoundsOnScreen(touchRoot,global.bar),originalCache);
            scene.enable(false);scene.settle();
            Rect refreshed=X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent);
            assertEquals("Moving a View also invalidates and rebuilds its native cached hit rectangle",
                    X.WindowProgressTouchFixtures.currentBoundsOnScreen(touchRoot,global.bar),refreshed);
            assertTrue("The whole native hotzone, including its extra 22dp bottom area, clears navigation: "+refreshed+" / "+scene.bounds(scene.navigation),
                    refreshed.bottom<=scene.bounds(scene.navigation).top-3);
            assertTrue("Metadata also clears the independent progress hotzone: metadata="+scene.bounds(scene.metadata)
                    +" hot="+refreshed+" carrierMargin="+scene.carrierParams.bottomMargin+" barMargin="+global.params.bottomMargin+" logs="+scene.logs,
                    scene.bounds(scene.metadata).bottom<=refreshed.top-3);
            assertTrue("The mute button clears the independent progress hotzone",scene.bounds(scene.muteHitbox).bottom<=refreshed.top-3);
            assertTrue("The native cache must actually follow the raised track",refreshed.bottom<originalCache.bottom);
            assertEquals(0,global.starts.get());assertEquals(0,global.stops.get());assertEquals(0,scene.navigationClicks.get());
            scene.dragTimeline(global.bar);assertTrue(global.starts.get()>0);assertTrue(global.stops.get()>0);
            int starts=global.starts.get(),stops=global.stops.get();
            scene.controller.close();scene.settle();assertEquals(39,global.params.bottomMargin);
            assertEquals("Release refreshes the native cache after restoring its original layout",
                    X.WindowProgressTouchFixtures.currentBoundsOnScreen(touchRoot,global.bar),
                    X.WindowProgressTouchFixtures.cachedBoundsOnScreen(nativeComponent));
            assertEquals(starts,global.starts.get());assertEquals(stops,global.stops.get());assertEquals(0,scene.navigationClicks.get());
        }
    }

    @Test public void windowLevelProSeekbarAndItsPreviewClearTheRaisedNavigationAndKeepDragging() throws Exception {
        int nativeCarrierMargin;
        try(Scene reference=new Scene(false,false)) {
            reference.enable(false);reference.settle();nativeCarrierMargin=reference.carrierParams.bottomMargin;
        }
        for(boolean motion:new boolean[]{false,true})try(Scene scene=new Scene(false,false)) {
            WindowProgress global=new WindowProgress(scene);
            scene.layout();Rect owner=scene.bounds(global.owner),media=scene.bounds(scene.media);
            scene.enable(motion);scene.settle();
            assertTrue("The window seekbar hitbox must clear the raised capsule: "+scene.bounds(global.bar)+" / "+scene.bounds(scene.navigation),
                    scene.bounds(global.bar).bottom<=scene.bounds(scene.navigation).top-3);
            assertTrue("A sibling preview follows the native bottom-to-top constraint",
                    scene.bounds(global.preview).bottom<=scene.bounds(global.bar).top-4);
            assertEquals("The full-window owner is not resized or translated",owner,scene.bounds(global.owner));
            assertEquals(media,scene.bounds(scene.media));
            assertTrue("The card dock also reserves space for the independent window track",
                    scene.carrierParams.bottomMargin>=nativeCarrierMargin+(motion?10:0));
            int reserved=scene.carrierParams.bottomMargin;scene.settle();
            assertEquals("Repeated sync does not accumulate the independent track reservation",reserved,scene.carrierParams.bottomMargin);
            scene.assertClearance(3);
            scene.dragTimeline(global.bar);assertTrue(global.starts.get()>0);assertTrue(global.stops.get()>0);
            assertEquals("Dragging the separate track does not activate navigation",0,scene.navigationClicks.get());
            scene.controller.close();scene.settle();assertEquals(39,global.params.bottomMargin);
            assertEquals(owner,scene.bounds(global.owner));scene.assertNativeMargins();
        }
    }

    @Test public void aCardOwnedProSeekbarIsLiftedOnceAndAnUnrecognizedWindowSeekbarIsUntouched() throws Exception {
        try(Scene scene=new Scene(false,false)) {
            scene.progress.removeView(scene.timeline);
            WindowProgress cardTrack=new WindowProgress(scene,true);
            SeekBar other=new SeekBar(scene.activity);FrameLayout.LayoutParams otherParams=new FrameLayout.LayoutParams(-1,18,Gravity.BOTTOM);
            otherParams.bottomMargin=39;scene.root.addView(other,otherParams);scene.layout();
            Rect cardBefore=scene.bounds(cardTrack.bar),metadataBefore=scene.bounds(scene.metadata),otherBefore=scene.bounds(other);
            scene.enable(false);scene.settle();
            assertEquals("A track already inside the card dock has no second owned offset",39,cardTrack.params.bottomMargin);
            assertEquals(cardBefore.bottom-scene.bounds(cardTrack.bar).bottom,metadataBefore.bottom-scene.bounds(scene.metadata).bottom);
            assertEquals("Ordinary window seekbars are outside the host-specific correction",otherBefore,scene.bounds(other));
            assertEquals(39,otherParams.bottomMargin);
            scene.controller.close();scene.settle();scene.assertNativeMargins();assertEquals(39,cardTrack.params.bottomMargin);
        }
    }

    @Test public void windowSeekbarAdoptsHostMarginUpdatesAndRestoresOnlyTheOwnedValue() throws Exception {
        for(boolean replace:new boolean[]{false,true})try(Scene scene=new Scene(false,false)) {
            WindowProgress global=new WindowProgress(scene);scene.layout();
            scene.enable(false);scene.settle();assertTrue(global.params.bottomMargin>39);
            global.params.bottomMargin=54;global.bar.requestLayout();scene.settle();
            assertTrue(global.params.bottomMargin>54);
            scene.controller.setOptions(Scene.options(false,false));scene.settle();assertEquals(54,global.params.bottomMargin);
            scene.controller.setOptions(Scene.options(true,false));scene.settle();assertTrue(global.params.bottomMargin>54);
            ConstraintLayout.LayoutParams live=replace?new ConstraintLayout.LayoutParams(global.params):global.params;
            live.bottomMargin=151;global.bar.setLayoutParams(live);scene.controller.close();scene.settle();
            assertSame(live,global.bar.getLayoutParams());
            assertEquals("A host write or replacement after the last sync survives release",151,live.bottomMargin);
            assertEquals(ConstraintLayout.LayoutParams.PARENT_ID,live.bottomToBottom);
        }
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void raisedIconNavigationKeepsTheWholeFooterClearDespiteEmptyMetadataCapacity() throws Exception {
        for(boolean motion:new boolean[]{false,true})try(Scene scene=new Scene(false,false)) {
            scene.useTallTransparentMetadataOwner();scene.layout();
            Rect media=scene.bounds(scene.media),metadata=scene.bounds(scene.metadata),progress=scene.bounds(scene.progress);
            Rect mute=scene.bounds(scene.muteHitbox);
            assertTrue("This native wrapper has a large empty upper area",scene.bounds(scene.metadataOwner).top<80);
            String icons="{}";
            for(String key:new String[]{"homepage_home","homepage_familiar","preview_publish","homepage_notification","homepage_profile"})
                icons=HomeUiCustomizationSettings.putIcon(icons,"nav_bottom:"+key,HomeUiIconFixtures.webp(8,8,Color.WHITE));
            scene.enable(motion);scene.controller.setOptions(new LocalOptions(Map.of("theme_style","liquid_glass",
                    "theme_gravity_enabled",motion,HomeUiCustomizationSettings.ICONS,icons)));
            scene.settle();
            assertEquals("Icon and label capacity must not fall back to the native short bar",72,scene.navigation.getHeight());
            assertTrue("The capsule leaves a visible bottom inset",scene.navigationParams.bottomMargin>=(motion?26:16));
            assertFalse("Empty transparent wrapper capacity must not reject the footer: "+scene.logs,
                    scene.logs.stream().anyMatch(line->line.contains("navigation_clearance_fallback=")));
            scene.assertClearance(3);
            int moved=progress.bottom-scene.bounds(scene.progress).bottom;
            assertTrue("The footer moves up as one dependency group",moved>0);
            assertEquals(moved,metadata.bottom-scene.bounds(scene.metadata).bottom);
            assertEquals(moved,mute.bottom-scene.bounds(scene.muteHitbox).bottom);
            assertEquals(media,scene.bounds(scene.media));
            ViewGroup row=scene.navigation.findViewById(0x7f0a7983);
            for(int i=0;i<row.getChildCount();i++) {
                BottomTab item=(BottomTab)row.getChildAt(i);TextView title=item.getTabTitle();
                assertNotNull(title.getCompoundDrawables()[1]);
                Rect box=new Rect(0,0,title.getWidth(),title.getHeight());scene.navigation.offsetDescendantRectToMyCoords(title,box);
                assertTrue("Icons and their labels fit the raised capsule",box.top>=0 && box.bottom<=scene.navigation.getHeight());
                scene.click(item);
            }
            assertEquals(5,scene.navigationClicks.get());
            scene.click(scene.muteHitbox);assertEquals(1,scene.muteClicks.get());
            scene.dragTimeline();assertTrue(scene.dragStarts.get()>0);assertTrue(scene.dragStops.get()>0);
            scene.controller.close();scene.settle();scene.assertNativeMargins();assertEquals(58,scene.navigation.getHeight());
            assertEquals(0,scene.navigationParams.bottomMargin);
        }
    }
    @Test public void unsafeCarrierKeepsNativeSizedBottomGlassAndRetriesOnlyAfterHostInputsChange() throws Exception {
        try(Scene scene=new Scene(false,false)) {
            SurfaceView protectedChild=new SurfaceView(scene.activity);
            scene.progress.addView(protectedChild,new LinearLayout.LayoutParams(1,1));
            Drawable nativeBackground=scene.navigation.getBackground();
            scene.enable(false);scene.settle();
            assertNotSame("Clearance fallback retains the bottom material",nativeBackground,scene.navigation.getBackground());
            assertEquals("Unsafe content keeps the original navigation geometry",58,scene.navigation.getHeight());
            Drawable fallback=scene.navigation.getBackground();
            for(int i=0;i<4;i++){scene.settle();assertSame("Stable input does not rebind the bottom",fallback,scene.navigation.getBackground());}
            scene.click(((ViewGroup)scene.navigation.findViewById(0x7f0a7983)).getChildAt(0));
            assertEquals("The compact glass preserves the original business click",1,scene.navigationClicks.get());
            scene.progress.removeView(protectedChild);scene.settle();scene.settle();
            assertEquals("Changed host content can retry expanded glass",72,scene.navigation.getHeight());
            scene.assertClearance(3);
            scene.controller.close();scene.settle();assertSame(nativeBackground,scene.navigation.getBackground());
            assertEquals(58,scene.navigation.getHeight());
        }
    }
    @Test public void aBlockedBottomDoesNotStopTopNavigationOrUnrelatedNativeControlMotion() throws Exception {
        try(Scene scene=new Scene(false,false)) {
            scene.progress.addView(new SurfaceView(scene.activity),new LinearLayout.LayoutParams(1,1));
            MainTitleBar top=new MainTitleBar(scene.activity);
            Drawable topBackground=new ColorDrawable(Color.DKGRAY);top.setBackground(topBackground);
            scene.root.addView(top,new FrameLayout.LayoutParams(-1,48));
            Button button=new Button(scene.activity);button.setText("独立操作");
            Drawable buttonBackground=button.getBackground();
            FrameLayout.LayoutParams buttonParams=new FrameLayout.LayoutParams(110,48);
            buttonParams.leftMargin=60;buttonParams.topMargin=240;scene.root.addView(button,buttonParams);
            ShadowSensorManager sensors=Shadow.extract((SensorManager)RuntimeEnvironment.getApplication()
                    .getSystemService(android.content.Context.SENSOR_SERVICE));
            Sensor gravity=ShadowSensor.newInstance(Sensor.TYPE_GRAVITY);sensors.addSensor(gravity);
            scene.lifecycle.visible().windowFocusChanged(true);
            ShadowChoreographer.setPaused(true);ShadowChoreographer.setFrameDelay(Duration.ofMillis(16));
            scene.enable(true);scene.settle();
            assertTrue("This scene must exercise the rejected bottom path: "+scene.logs,
                    scene.logs.stream().anyMatch(line->line.contains("navigation_clearance_fallback=")));
            assertNotSame("An unrelated top surface keeps its glass",topBackground,top.getBackground());
            SensorEvent sample=ShadowSensorManager.createSensorEvent(3);sample.sensor=gravity;
            sample.timestamp=100_000_000L;sample.values[2]=9.80665f;sensors.sendSensorEventToListeners(sample);
            sample=ShadowSensorManager.createSensorEvent(3);sample.sensor=gravity;sample.timestamp=200_000_000L;
            sample.values[0]=-9.80665f;sample.values[2]=9.80665f;sensors.sendSensorEventToListeners(sample);
            ShadowLooper.shadowMainLooper().idleFor(Duration.ofSeconds(2));
            assertTrue("Bottom clearance must not disable the top navigation's gravity",top.getTranslationX()>0);
            assertTrue("An unrelated native button keeps following gravity",button.getTranslationX()>0);
            assertSame("Following gravity does not restyle native controls",buttonBackground,button.getBackground());
            scene.controller.close();assertEquals(0f,top.getTranslationX(),0);assertEquals(0f,button.getTranslationX(),0);
        }
    }
    @Test public void modernRelativeDependenciesClearTheCapsuleAndKeepTimelineAndMuteTouchable() throws Exception {
        try(Scene scene=new Scene(false,false)) {
            Rect media=scene.bounds(scene.media);Drawable mediaBackground=scene.media.getBackground();
            Rect beforeMetadata=scene.bounds(scene.metadata),beforeProgress=scene.bounds(scene.progress);
            scene.enable(false);scene.settle();
            assertEquals(72,scene.navigation.getHeight());scene.assertClearance(3);
            int metadataShift=beforeMetadata.bottom-scene.bounds(scene.metadata).bottom;
            int progressShift=beforeProgress.bottom-scene.bounds(scene.progress).bottom;
            assertEquals("A single carrier reservation shifts the dependency tree once",progressShift,metadataShift);
            assertTrue(progressShift>0);assertEquals(media,scene.bounds(scene.media));assertSame(mediaBackground,scene.media.getBackground());
            scene.click(scene.muteHitbox);assertEquals(1,scene.muteClicks.get());
            scene.dragTimeline();assertTrue("The shifted real SeekBar remains reachable through its parents",scene.dragStarts.get()>0);
            assertTrue(scene.dragStops.get()>0);assertEquals(0,scene.navigationClicks.get());
            assertNull(scene.metadata.getBackground());assertNull(scene.progress.getBackground());
            scene.controller.close();scene.settle();scene.assertNativeMargins();assertEquals(58,scene.navigationParams.height);
        }
    }
    @Test public void aHiddenTimelineStillReservesMetadataAndTheModernRightBranchWithMotionClearance() throws Exception {
        try(Scene scene=new Scene(false,true)) {
            scene.enable(true);scene.settle();
            assertEquals(View.GONE,scene.progress.getVisibility());scene.assertClearance(7);
            scene.click(scene.muteHitbox);assertEquals(1,scene.muteClicks.get());
            assertTrue(scene.metadata.getText().length()>30);
        }
    }
    @Test public void legacyMuteUsesItsOwnBottomBranchWhileHostSizeChangesAndPauseRestoreOwnedGeometry() throws Exception {
        try(Scene scene=new Scene(true,false)) {
            scene.enable(false);scene.settle();scene.assertClearance(3);
            scene.navigationParams.height=96;scene.navigation.requestLayout();scene.settle();
            assertEquals(96,scene.navigation.getHeight());scene.assertClearance(3);
            scene.click(scene.muteHitbox);assertEquals(1,scene.muteClicks.get());
            scene.controller.onActivityPaused(scene.activity);scene.settle();
            assertEquals("Latest host navigation height survives release",96,scene.navigationParams.height);
            scene.assertNativeMargins();
            scene.controller.onActivityResumed(scene.activity);scene.settle();scene.assertClearance(3);
            scene.controller.setOptions(Scene.options(false,false));scene.settle();scene.assertNativeMargins();
        }
    }
    @Test public void mediaAndUnknownCarriersKeepTheirOwnLayoutAndFeedBackgrounds() throws Exception {
        for(boolean mediaBranch:new boolean[]{false,true})try(Scene scene=new Scene(false,false)) {
            if(mediaBranch)scene.progress.addView(new SurfaceView(scene.activity),new LinearLayout.LayoutParams(1,1));
            else scene.carrier.setId(View.generateViewId());
            int carrierMargin=scene.carrierParams.bottomMargin,progressMargin=scene.progressParams.bottomMargin;
            Drawable original=scene.metadata.getBackground();scene.enable(false);scene.settle();
            assertEquals(carrierMargin,scene.carrierParams.bottomMargin);assertEquals(progressMargin,scene.progressParams.bottomMargin);
            assertSame(original,scene.metadata.getBackground());
        }
    }

    static final class Scene implements AutoCloseable {
        final ActivityController<Activity> lifecycle=Robolectric.buildActivity(Activity.class).setup();
        final Activity activity=lifecycle.get();
        final FrameLayout root=new FrameLayout(activity);
        final View media=new View(activity);
        final PenetrateTouchRelativeLayout feed=new PenetrateTouchRelativeLayout(activity);
        final LinearLayoutCompat carrier=new LinearLayoutCompat(activity);
        final QLayout lowerModule=new QLayout(activity);
        final LinearLayout progress=new LinearLayout(activity);
        final SeekBar timeline=new SeekBar(activity);
        final MeasureOnceCheckDrawChildFrameLayout metadataOwner=new MeasureOnceCheckDrawChildFrameLayout(activity);
        final TextView metadata=new TextView(activity);
        final RelativeLayout right;
        final FrameLayout muteOwner=new FrameLayout(activity),muteHitbox=new FrameLayout(activity);
        final ImageView muteIcon=new ImageView(activity);
        final FrameLayout navigation;
        final FrameLayout.LayoutParams navigationParams=new FrameLayout.LayoutParams(-1,58,Gravity.BOTTOM);
        final RelativeLayout.LayoutParams carrierParams=new RelativeLayout.LayoutParams(-1,-2);
        final ViewGroup.MarginLayoutParams progressParams;
        final RelativeLayout.LayoutParams rightParams=new RelativeLayout.LayoutParams(61,52);
        final AtomicInteger muteClicks=new AtomicInteger(),navigationClicks=new AtomicInteger(),dragStarts=new AtomicInteger(),dragStops=new AtomicInteger();
        final ArrayList<String> logs=new ArrayList<>();
        final boolean legacy;
        LiquidGlassController controller;
        Scene(boolean legacy,boolean hideTimeline) throws Exception {
            this.legacy=legacy;
            navigation=(FrameLayout)Class.forName("X.1JgG").getConstructor(android.content.Context.class).newInstance(activity);
            right=legacy?new RelativeLayout(activity):new MeasureOnceCheckDrawChildRelativeLayout(activity);
            root.setBackgroundColor(Color.BLACK);
            media.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xff244757,0xff806550,0xff193347}));
            root.addView(media,new FrameLayout.LayoutParams(-1,-1));feed.setId(0x7f0a01db);
            root.addView(feed,new FrameLayout.LayoutParams(-1,-1));
            carrier.setId(0x7f0a15bb);carrier.setOrientation(LinearLayoutCompat.VERTICAL);carrierParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            feed.addView(carrier,carrierParams);
            lowerModule.setId(0x7f0a3ff1);carrier.addView(lowerModule,new LinearLayoutCompat.LayoutParams(-1,0));
            progress.setId(0x7f0a16b2);progress.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams progressPosition=new LinearLayout.LayoutParams(-1,18);progressPosition.bottomMargin=47;
            carrier.addView(progress,progressPosition);progressParams=(ViewGroup.MarginLayoutParams)progress.getLayoutParams();
            timeline.setMax(100);timeline.setProgress(37);
            timeline.setPadding(12,0,12,0);progress.addView(timeline,new LinearLayout.LayoutParams(-1,18));
            timeline.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onProgressChanged(SeekBar view,int value,boolean user) { }
                @Override public void onStartTrackingTouch(SeekBar view){dragStarts.incrementAndGet();}
                @Override public void onStopTrackingTouch(SeekBar view){dragStops.incrementAndGet();}
            });
            if(hideTimeline)progress.setVisibility(View.GONE);
            int[] chain={0x7f0a68ce,0x7f0a6b90,0x7f0a4321,0x7f0a40c4,0x7f0a9065,0x7f0a2662,0x7f0a7303};
            int anchor=carrier.getId();
            for(int id:chain) {
                FrameLayout branch=new FrameLayout(activity);branch.setId(id);
                RelativeLayout.LayoutParams p=new RelativeLayout.LayoutParams(-1,0);p.addRule(RelativeLayout.ABOVE,anchor);
                if(id==0x7f0a2662 || id==0x7f0a4321 || id==0x7f0a6b90)branch.setVisibility(View.GONE);
                feed.addView(branch,p);anchor=id;
            }
            metadataOwner.setId(0x7f0a3ffc);RelativeLayout.LayoutParams metaParams=new RelativeLayout.LayoutParams(-1,-2);
            metaParams.addRule(RelativeLayout.ABOVE,0x7f0a7303);metaParams.rightMargin=72;feed.addView(metadataOwner,metaParams);
            metadata.setId(0x7f0a1f1a);metadata.setText("@城市漫游  今天沿河散步，记录那些容易错过的小风景。\n长文案继续显示，时间轴和静音按钮都要保留可点击范围。");
            metadata.setTextSize(15);metadata.setTextColor(Color.WHITE);metadata.setPadding(12,4,6,4);
            metadataOwner.addView(metadata,new FrameLayout.LayoutParams(-1,-2));
            right.setId(0x7f0a4000);rightParams.addRule(RelativeLayout.ALIGN_PARENT_END);
            if(legacy){rightParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);rightParams.bottomMargin=16;}
            else rightParams.addRule(RelativeLayout.ABOVE,carrier.getId());
            feed.addView(right,rightParams);
            muteOwner.setId(0x7f0ad42a);RelativeLayout.LayoutParams muteParams=new RelativeLayout.LayoutParams(-1,52);
            muteParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);right.addView(muteOwner,muteParams);
            muteHitbox.setId(0x7f0a7ea7);FrameLayout.LayoutParams hit=new FrameLayout.LayoutParams(44,44,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL);hit.bottomMargin=6;
            muteHitbox.setOnClickListener(view->muteClicks.incrementAndGet());muteOwner.addView(muteHitbox,hit);
            GradientDrawable disc=new GradientDrawable();disc.setShape(GradientDrawable.OVAL);disc.setColor(0x99505050);muteHitbox.setBackground(disc);
            muteIcon.setId(0x7f0a7ea4);muteIcon.setImageResource(android.R.drawable.ic_lock_silent_mode);
            muteIcon.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            muteHitbox.addView(muteIcon,new FrameLayout.LayoutParams(20,20,Gravity.CENTER));
            navigation.setBackgroundColor(0xff15171d);root.addView(navigation,navigationParams);
            LinearLayout row=new LinearLayout(activity);row.setId(0x7f0a7983);navigation.addView(row,new FrameLayout.LayoutParams(-1,-1));
            String[] keys={"homepage_home","homepage_familiar","preview_publish","homepage_notification","homepage_profile"};
            String[] titles={"首页","朋友","发布","消息","我"};
            for(int i=0;i<keys.length;i++) {
                BottomTab tab=new BottomTab(activity,keys[i],titles[i]);tab.getTabTitle().setTextColor(Color.WHITE);tab.getTabTitle().setTextSize(16);
                tab.getTabTitle().setLayoutParams(new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
                tab.setOnClickListener(view->navigationClicks.incrementAndGet());row.addView(tab,new LinearLayout.LayoutParams(0,-1,1));
            }
            activity.setContentView(root);layout();
        }
        static LocalOptions options(boolean enabled,boolean motion) {return new LocalOptions(Map.of("theme_style",enabled?"liquid_glass":"original","theme_gravity_enabled",motion));}
        void useTallTransparentMetadataOwner() {
            metadataOwner.getLayoutParams().height=700;metadataOwner.requestLayout();
            metadata.setLayoutParams(new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        }
        void enable(boolean motion){controller=new LiquidGlassController(activity,options(true,motion),logs::add);controller.onActivityResumed(activity);}
        void forceInitialLayout(){for(View node:LiquidGlassTargets.nodes(root))node.forceLayout();root.requestLayout();}
        void layout(){View decor=activity.getWindow().getDecorView();decor.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));decor.layout(0,0,360,800);}
        void settle(){for(int i=0;i<6;i++){layout();View decor=activity.getWindow().getDecorView();decor.getViewTreeObserver().dispatchOnGlobalLayout();ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(120));layout();decor.getViewTreeObserver().dispatchOnPreDraw();}layout();}
        Rect bounds(View view){int[] position=new int[2];view.getLocationOnScreen(position);return new Rect(position[0],position[1],position[0]+view.getWidth(),position[1]+view.getHeight());}
        void assertClearance(int gap){int limit=bounds(navigation).top-gap;
            if(progress.getVisibility()==View.VISIBLE)assertTrue("Progress hitbox must clear navigation: "+bounds(progress)+" / "+bounds(navigation)+" logs="+logs,bounds(progress).bottom<=limit);
            assertTrue("Metadata must clear navigation: "+bounds(metadata)+" / "+bounds(navigation)+" logs="+logs,bounds(metadata).bottom<=limit);
            assertTrue("Mute hitbox must clear navigation: "+bounds(muteHitbox)+" / "+bounds(navigation)+" logs="+logs,bounds(muteHitbox).bottom<=limit);
        }
        void click(View target){Rect b=bounds(target);dispatch(MotionEvent.ACTION_DOWN,b.exactCenterX(),b.exactCenterY());dispatch(MotionEvent.ACTION_UP,b.exactCenterX(),b.exactCenterY());ShadowLooper.shadowMainLooper().idle();}
        void dragTimeline(){dragTimeline(timeline);}
        void dragTimeline(SeekBar track){Rect b=bounds(track);float y=b.exactCenterY();dispatch(MotionEvent.ACTION_DOWN,b.left+b.width()*.2f,y);dispatch(MotionEvent.ACTION_MOVE,b.left+b.width()*.8f,y);dispatch(MotionEvent.ACTION_UP,b.left+b.width()*.8f,y);}
        private void dispatch(int action,float x,float y){long now=SystemClock.uptimeMillis();MotionEvent event=MotionEvent.obtain(now,now,action,x,y,0);try{if(controller!=null)controller.onTouch(event);activity.getWindow().getDecorView().dispatchTouchEvent(event);}finally{event.recycle();}}
        void assertNativeMargins(){assertEquals(0,carrierParams.bottomMargin);assertEquals(47,progressParams.bottomMargin);assertEquals(legacy?16:0,rightParams.bottomMargin);}
        @Override public void close(){if(controller!=null)controller.close();activity.finish();}
    }

    /** The host adds this full-window ConstraintLayout directly to the fragment root. */
    static final class WindowProgress {
        final com.ss.android.ugc.aweme.o.seekbar.DProSeekBar owner;
        final com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView bar;
        final TextView preview;
        final ConstraintLayout.LayoutParams params=new ConstraintLayout.LayoutParams(-1,18);
        final AtomicInteger starts=new AtomicInteger(),stops=new AtomicInteger();
        WindowProgress(Scene scene) {this(scene,false);}
        WindowProgress(Scene scene,boolean insideCard) {
            owner=new com.ss.android.ugc.aweme.o.seekbar.DProSeekBar(scene.activity);
            if(insideCard)scene.progress.addView(owner,new LinearLayout.LayoutParams(-1,57));
            else scene.root.addView(owner,new FrameLayout.LayoutParams(-1,-1));
            bar=new com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView(scene.activity);bar.setId(0x7f0a93b7);
            params.leftToLeft=ConstraintLayout.LayoutParams.PARENT_ID;params.rightToRight=ConstraintLayout.LayoutParams.PARENT_ID;
            params.bottomToBottom=ConstraintLayout.LayoutParams.PARENT_ID;params.bottomMargin=39;
            owner.addView(bar,params);bar.setMax(100);bar.setProgress(30);
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
                @Override public void onProgressChanged(SeekBar view,int value,boolean user) { }
                @Override public void onStartTrackingTouch(SeekBar view){starts.incrementAndGet();}
                @Override public void onStopTrackingTouch(SeekBar view){stops.incrementAndGet();}
            });
            preview=new TextView(scene.activity);preview.setText("01:23");
            ConstraintLayout.LayoutParams previewParams=new ConstraintLayout.LayoutParams(80,24);
            previewParams.leftToLeft=ConstraintLayout.LayoutParams.PARENT_ID;previewParams.bottomToTop=bar.getId();previewParams.bottomMargin=4;
            owner.addView(preview,previewParams);
        }
    }
}
