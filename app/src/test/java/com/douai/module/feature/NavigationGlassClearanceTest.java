package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.appcompat.widget.LinearLayoutCompat;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public class NavigationGlassClearanceTest {
    @Test public void transparentOversizedMetadataUsesItsVisibleContentForCapacityAndRetryInputs() {
        Fixture f=new Fixture();
        try {
            TextView content=(TextView)f.metadata.getChildAt(0);
            f.metadata.getLayoutParams().height=710;
            FrameLayout.LayoutParams contentParams=new FrameLayout.LayoutParams(-1,24,Gravity.BOTTOM);
            content.setLayoutParams(contentParams);f.metadata.requestLayout();f.layout();f.discover();
            assertTrue(f.metadata.willNotDraw());assertNull(f.metadata.getBackground());
            assertTrue("The wrapper has empty space above the real footer content",f.bounds(f.metadata).top<42);
            float contentTop=f.bounds(content).top;
            assertTrue(contentTop>600);
            assertTrue("Empty transparent wrapper space must not reject footer capacity",f.sync());
            f.layout();assertEquals(28,f.carrierParams.bottomMargin);assertEquals(contentTop-28,f.bounds(content).top,.01f);
            assertFalse(f.sync());long bottomContentRevision=f.state.inputRevision();
            contentParams.gravity=Gravity.TOP;content.setLayoutParams(contentParams);f.layout();
            assertTrue("Real content at the top still prevents shifting outside the viewport",f.sync());
            assertEquals(0,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().contains(f.nav));
            assertTrue("Changing child placement revises inputs even though the wrapper size is unchanged",f.state.inputRevision()>bottomContentRevision);
            f.layout();long topContentRevision=f.state.inputRevision();
            contentParams.gravity=Gravity.BOTTOM;content.setLayoutParams(contentParams);f.layout();
            assertTrue(f.sync());assertTrue(f.state.inputRevision()>topContentRevision);
            f.layout();assertEquals(28,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().isEmpty());
        }finally{f.close();}
    }

    @Test public void oversizedWrappersThatPaintOrReceiveClicksStillProtectTheirWholeBounds() {
        for(int ownContent=0;ownContent<4;ownContent++) {
            Fixture f=new Fixture();
            try {
                f.metadata.getLayoutParams().height=710;
                f.metadata.getChildAt(0).setLayoutParams(new FrameLayout.LayoutParams(-1,24,Gravity.BOTTOM));
                if(ownContent==0)f.metadata.setBackgroundColor(android.graphics.Color.BLACK);
                else if(ownContent==1)f.metadata.setForeground(new android.graphics.drawable.ColorDrawable(android.graphics.Color.WHITE));
                else if(ownContent==2)f.metadata.setClickable(true);
                else f.metadata.setWillNotDraw(false);
                f.metadata.requestLayout();f.layout();f.discover();
                assertFalse("A wrapper's own paint or hit area is not empty capacity",f.sync());
                assertEquals(0,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().contains(f.nav));
                assertTrue(f.state.fallbackDetail().contains("reason=capacity"));
            }finally{f.close();}
        }
    }

    @Test public void hiddenOpaquePlaceholdersInTheRealFullSizeAboveBranchDoNotDisableClearance() {
        for(boolean gone:new boolean[]{true,false}) {
            Fixture f=new Fixture();
            try {
                FrameLayout fullSize=new FrameLayout(f.activity);fullSize.setId(0x7f0a0441);
                RelativeLayout.LayoutParams slot=new RelativeLayout.LayoutParams(-1,-1);
                slot.addRule(RelativeLayout.ABOVE,0x7f0a15bb);f.feed.addView(fullSize,slot);
                OpaqueFixture opaque=new OpaqueFixture(f.activity);
                FrameLayout.LayoutParams size=new FrameLayout.LayoutParams(gone?10:0,gone?10:0);
                fullSize.addView(opaque,size);if(gone)opaque.setVisibility(View.GONE);
                f.layout();f.state.discover(LiquidGlassTargets.nodes(f.root));
                assertTrue("An unpainted opaque placeholder must not reject the real footer dock",f.sync());
                f.layout();assertEquals(28,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().isEmpty());
                size.width=10;size.height=10;opaque.setVisibility(View.VISIBLE);opaque.requestLayout();f.layout();
                assertEquals(10,opaque.getWidth());assertEquals(10,opaque.getHeight());
                assertTrue("A now-visible opaque child must restore the reservation before the next scan",f.sync());
                f.layout();assertEquals(0,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().contains(f.nav));
                opaque.setVisibility(View.GONE);f.layout();
                assertTrue("Hiding the same placeholder permits clearance again without changing its identity",f.sync());
                f.layout();assertEquals(28,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().isEmpty());
            }finally{f.close();}
        }
    }

    @Test public void oneCarrierMarginMovesProgressMetadataAndModernRightThroughTheirOriginalRules() {
        Fixture f=new Fixture();
        try {
            float videoTop=f.video.getTop(),videoBottom=f.video.getBottom();
            float metadata=f.bounds(f.metadata).bottom,right=f.bounds(f.right).bottom;
            assertEquals(742f,f.bounds(f.progress).bottom,0);
            assertTrue(f.sync());assertEquals(28,f.carrierParams.bottomMargin);f.layout();
            assertEquals(714f,f.bounds(f.progress).bottom,0);
            assertEquals(metadata-28,f.bounds(f.metadata).bottom,0);
            assertEquals(right-28,f.bounds(f.right).bottom,0);
            assertEquals(0,((RelativeLayout.LayoutParams)f.metadata.getLayoutParams()).bottomMargin);
            assertEquals(0,((RelativeLayout.LayoutParams)f.right.getLayoutParams()).bottomMargin);
            assertEquals(videoTop,f.video.getTop(),0);assertEquals(videoBottom,f.video.getBottom(),0);
            assertEquals(0,f.video.getTranslationY(),0);assertFalse(f.sync());
            f.state.close();f.layout();assertEquals(0,f.carrierParams.bottomMargin);
            assertEquals(742f,f.bounds(f.progress).bottom,0);
        } finally {f.close();}
    }

    @Test public void repeatedDiscoveryMotionAndAnEmptyNavSetDoNotFeedModuleOffsetsIntoTheInputRevision() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();assertFalse(f.sync());long revision=f.state.inputRevision();
            f.nav.setTranslationY(3);f.nav.setRotation(.35f);assertFalse(f.sync());
            assertEquals(revision,f.state.inputRevision());
            f.state.discover(nodes(f.root));assertFalse(f.sync());assertEquals(revision,f.state.inputRevision());
            assertTrue(f.state.sync(Collections.emptyList()));f.layout();
            assertFalse(f.state.sync(Collections.emptyList()));assertEquals(revision,f.state.inputRevision());
            f.nav.getLayoutParams().height=48;f.nav.requestLayout();f.layout();
            f.state.sync(Collections.emptyList());assertEquals(revision,f.state.inputRevision());
        } finally {f.close();}
    }

    @Test public void pendingLayoutDoesNotApplyTheSameBudgetTwice() {
        Fixture f=new Fixture();
        try {
            assertTrue(f.sync());int margin=f.carrierParams.bottomMargin;long revision=f.state.inputRevision();
            assertFalse(f.sync());assertEquals(margin,f.carrierParams.bottomMargin);
            assertEquals(revision,f.state.inputRevision());f.layout();assertFalse(f.sync());
        } finally {f.close();}
    }

    @Test public void visibilityAndAncestorAlphaChangesReleaseAndRecomputeTheReservation() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();f.sync();long revision=f.state.inputRevision();
            f.feed.setAlpha(0);assertTrue(f.sync());f.layout();assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.inputRevision()>revision);
            f.feed.setAlpha(1);assertTrue(f.sync());f.layout();assertEquals(28,f.carrierParams.bottomMargin);
        } finally {f.close();}
    }

    @Test public void goneProgressStillLetsTheCarrierProtectVisibleMetadata() {
        Fixture f=new Fixture();
        try {
            f.progress.setVisibility(View.GONE);f.above.setVisibility(View.GONE);f.right.setVisibility(View.GONE);f.layout();
            f.metadata.getLayoutParams().height=80;f.metadata.requestLayout();f.layout();
            f.discover();assertTrue(f.sync());f.layout();
            assertTrue(f.carrierParams.bottomMargin>0);
            assertTrue(f.bounds(f.metadata).bottom<=714);assertEquals(View.GONE,f.progress.getVisibility());
        } finally {f.close();}
    }

    @Test public void aNewMediaChildIsRejectedBeforeTheNextDiscoveryAndOwnedMarginsAreRestored() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();f.sync();long revision=f.state.inputRevision();
            SurfaceView injected=new SurfaceView(f.activity);f.progress.addView(injected,new LinearLayout.LayoutParams(10,10));
            assertTrue(f.sync());assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.blockedViews().contains(f.nav));f.layout();f.discover();f.sync();
            assertTrue(f.state.inputRevision()>revision);assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.blockedViews().contains(f.nav));
        } finally {f.close();}
    }

    @Test public void aFakeCarrierOrGenericTitleIsNeverMoved() {
        Fixture f=new Fixture();
        try {
            f.carrierParams.removeRule(RelativeLayout.ALIGN_PARENT_BOTTOM);f.carrier.requestLayout();f.layout();
            TextView generic=new TextView(f.activity);generic.setId(0x7f0a8f46);generic.setText("Other title");
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(100,40,Gravity.BOTTOM);f.root.addView(generic,p);f.layout();
            f.discover();f.sync();assertEquals(0,f.carrierParams.bottomMargin);
            assertEquals(0,p.bottomMargin);assertEquals(0,generic.getTranslationY(),0);
        } finally {f.close();}
    }

    @Test public void anUnrelatedMetadataAnchorDoesNotMakeAnEmptyCarrierMove() {
        Fixture f=new Fixture();
        try {
            f.progress.setVisibility(View.GONE);f.above.setVisibility(View.GONE);f.right.setVisibility(View.GONE);
            RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)f.metadata.getLayoutParams();
            p.removeRule(RelativeLayout.ABOVE);p.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);
            f.metadata.setLayoutParams(p);f.layout();f.discover();f.sync();
            assertEquals(0,f.carrierParams.bottomMargin);assertEquals(0,p.bottomMargin);
        } finally {f.close();}
    }

    @Test public void hostInPlaceWritesAndNewLayoutParamsKeepTheirOwnRestoreBaselines() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();f.carrierParams.bottomMargin=7;f.carrier.requestLayout();f.layout();
            assertTrue(f.sync());f.layout();f.state.close();f.layout();assertEquals(7,f.carrierParams.bottomMargin);
            f.state=new NavigationGlassClearance(f.root,f::resting);f.discover();f.sync();f.layout();
            RelativeLayout.LayoutParams replacement=new RelativeLayout.LayoutParams(f.carrierParams);
            replacement.bottomMargin=31;f.carrier.setLayoutParams(replacement);f.layout();f.discover();f.sync();
            f.state.close();assertSame(replacement,f.carrier.getLayoutParams());assertEquals(31,replacement.bottomMargin);
        } finally {f.close();}
    }

    @Test public void aScaledParentConvertsRootPixelClearanceBackToLocalMarginUnits() {
        Fixture f=new Fixture();
        try {
            f.feed.getLayoutParams().height=417;f.feed.setPivotY(0);f.feed.setScaleY(2);f.feed.requestLayout();f.layout();
            f.discover();assertTrue(f.sync());f.layout();
            assertTrue(f.bounds(f.progress).bottom<=714);
            int margin=f.carrierParams.bottomMargin;assertTrue(margin>0 && margin<28);assertFalse(f.sync());
        } finally {f.close();}
    }

    @Test public void legacyRightColumnUsesItsOwnMarginAndAWindowResizeRevisesHostInputs() {
        Fixture f=new Fixture();
        try {
            RelativeLayout.LayoutParams p=(RelativeLayout.LayoutParams)f.right.getLayoutParams();
            p.removeRule(RelativeLayout.ABOVE);p.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);p.bottomMargin=16;
            f.right.setLayoutParams(p);f.layout();f.discover();assertTrue(f.sync());f.layout();
            assertTrue(f.bounds(f.right).bottom<=714);assertTrue(p.bottomMargin>16);
            long revision=f.state.inputRevision();f.state.close();assertEquals(16,p.bottomMargin);
            f.state=new NavigationGlassClearance(f.root,f::resting);f.discover();f.sync();f.layout();
            long before=f.state.inputRevision();f.width=400;f.layout();f.sync();assertTrue(f.state.inputRevision()>before);
        } finally {f.close();}
    }

    @Test public void impossibleCapacityBlocksTheNavWithoutTouchingVideoOrGrowingAnUnboundedMargin() {
        Fixture f=new Fixture();
        try {
            f.navRect.top=20;assertFalse(f.sync());assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.blockedViews().contains(f.nav));long revision=f.state.inputRevision();
            assertFalse(f.sync());assertEquals(revision,f.state.inputRevision());assertEquals(0,f.video.getTranslationY(),0);
        } finally {f.close();}
    }

    @Test public void anUnsupportedParentTransformReleasesThePreviouslyOwnedBudget() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();assertTrue(f.carrierParams.bottomMargin>0);
            f.feed.setPivotY(394);f.feed.setScaleY(-1);f.layout();
            assertTrue(f.sync());assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.blockedViews().contains(f.nav));
        } finally {f.close();}
    }

    @Test public void hostNavigationVisibilityRemainsAnInputEvenWhenItsBindingHasBeenRejected() {
        Fixture f=new Fixture();
        try {
            f.sync();f.layout();f.state.sync(Collections.emptyList());f.layout();f.state.sync(Collections.emptyList());
            long revision=f.state.inputRevision();f.nav.setAlpha(0);f.state.sync(Collections.emptyList());
            assertTrue(f.state.inputRevision()>revision);revision=f.state.inputRevision();
            f.nav.setAlpha(1);f.state.sync(Collections.emptyList());assertTrue(f.state.inputRevision()>revision);
        } finally {f.close();}
    }

    @Test public void matchParentMetadataIsRejectedInsteadOfUsingItsViewportAsAnObstacle() {
        Fixture f=new Fixture();
        try {
            f.metadata.getLayoutParams().height=ViewGroup.LayoutParams.MATCH_PARENT;
            f.metadata.requestLayout();f.layout();f.discover();assertFalse(f.sync());
            assertEquals(0,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().contains(f.nav));
        } finally {f.close();}
    }

    @Test public void anUnmeasurableVisibleProgressBlocksUntilItsNaturalGeometryIsReliableAgain() {
        Fixture f=new Fixture();boolean[] unreliable={false};
        try {
            f.state.close();
            f.state=new NavigationGlassClearance(f.root,view->unreliable[0] && view==f.progress?null:f.resting(view));
            f.discover();assertTrue(f.sync());f.layout();assertFalse(f.sync());
            long reliableRevision=f.state.inputRevision();assertEquals(28,f.carrierParams.bottomMargin);
            unreliable[0]=true;
            assertTrue(f.sync());assertEquals(0,f.carrierParams.bottomMargin);
            assertTrue(f.state.blockedViews().contains(f.nav));
            long unknownRevision=f.state.inputRevision();assertTrue(unknownRevision>reliableRevision);
            f.layout();assertFalse(f.sync());assertEquals(unknownRevision,f.state.inputRevision());
            assertTrue(f.state.blockedViews().contains(f.nav));
            unreliable[0]=false;
            assertTrue(f.sync());assertTrue(f.state.inputRevision()>unknownRevision);
            assertTrue(f.state.blockedViews().isEmpty());f.layout();assertEquals(28,f.carrierParams.bottomMargin);
            assertFalse(f.sync());
        } finally {f.close();}
    }

    @Test public void hiddenUnmeasurableProgressAndAnEmptyAboveModuleDoNotBlockVisibleMetadata() {
        Fixture f=new Fixture();
        try {
            f.state.close();
            f.state=new NavigationGlassClearance(f.root,view->view==f.progress || view==f.above?null:f.resting(view));
            f.above.getLayoutParams().height=0;f.above.requestLayout();
            f.progress.setVisibility(View.GONE);f.layout();f.discover();
            assertTrue(f.sync());f.layout();assertTrue(f.state.blockedViews().isEmpty());
            assertTrue(f.carrierParams.bottomMargin>0);assertTrue(f.bounds(f.metadata).bottom<=714);
            assertFalse(f.sync());
            f.progress.setVisibility(View.VISIBLE);f.progress.setAlpha(0);f.layout();
            f.sync();f.layout();assertTrue(f.state.blockedViews().isEmpty());
            assertTrue(f.bounds(f.metadata).bottom<=714);assertFalse(f.sync());
            f.progress.setAlpha(1);assertTrue(f.sync());
            assertEquals(0,f.carrierParams.bottomMargin);assertTrue(f.state.blockedViews().contains(f.nav));
        } finally {f.close();}
    }

    @Test public void anEmptyModuleBecomingUnmeasurableRevisesTheRetryInputWithoutChangingItsLayoutParams() {
        Fixture f=new Fixture();
        try {
            f.state.close();
            f.state=new NavigationGlassClearance(f.root,view->view==f.above?null:f.resting(view));
            f.progress.setVisibility(View.GONE);f.metadata.setVisibility(View.GONE);f.right.setVisibility(View.GONE);
            f.above.getLayoutParams().height=ViewGroup.LayoutParams.WRAP_CONTENT;f.above.requestLayout();
            f.layout();f.discover();assertFalse(f.sync());assertTrue(f.state.blockedViews().isEmpty());
            long emptyRevision=f.state.inputRevision();assertEquals(0,f.above.getHeight());
            f.above.setMinimumHeight(10);f.layout();assertEquals(10,f.above.getHeight());
            assertFalse(f.sync());assertTrue(f.state.blockedViews().contains(f.nav));
            long unknownRevision=f.state.inputRevision();assertTrue(unknownRevision>emptyRevision);
            f.above.setMinimumHeight(0);f.layout();assertFalse(f.sync());
            assertTrue(f.state.blockedViews().isEmpty());assertTrue(f.state.inputRevision()>unknownRevision);
            assertEquals(0,f.carrierParams.bottomMargin);
        } finally {f.close();}
    }

    @Test public void motionOwnedCarrierAncestorsDoNotChangeTheBudgetOrHostInputRevision() {
        Fixture f=new Fixture();LiquidGlassMotion motion=new LiquidGlassMotion(f.activity);
        try {
            f.state.close();f.state=new NavigationGlassClearance(f.root,view->motion.restingBounds(view,f.root));f.discover();
            assertTrue(f.sync());f.layout();assertFalse(f.sync());
            int margin=f.carrierParams.bottomMargin;long revision=f.state.inputRevision();
            motion.setTargets(List.of(f.nav,f.carrier,f.metadata,f.right),null);
            for(LiquidGlassMotion.Vector sample:List.of(new LiquidGlassMotion.Vector(1,1),new LiquidGlassMotion.Vector(-1,-1))) {
                motion.applyFrame(sample);assertFalse(f.sync());
                assertEquals(margin,f.carrierParams.bottomMargin);assertEquals(revision,f.state.inputRevision());
                assertTrue(f.state.blockedViews().isEmpty());
            }
        } finally {motion.close();f.close();}
    }

    @Test public void theWindowRootOwnTransformDoesNotChangeRootLocalClearanceUnitsOrRevision() {
        Fixture f=new Fixture();LiquidGlassMotion motion=new LiquidGlassMotion(f.activity);
        try {
            f.state.close();f.state=new NavigationGlassClearance(f.root,view->motion.restingBounds(view,f.root));f.discover();
            f.sync();f.layout();assertFalse(f.sync());int margin=f.carrierParams.bottomMargin;long revision=f.state.inputRevision();
            for(float scale:new float[]{.7f,1.3f,.75f}) {
                f.root.setScaleX(1.1f);f.root.setScaleY(scale);f.root.setRotation(11);f.root.setTranslationY(50);
                assertFalse(f.sync());assertEquals(margin,f.carrierParams.bottomMargin);assertEquals(revision,f.state.inputRevision());
            }
        } finally {motion.close();f.close();}
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout root=new FrameLayout(activity);
        final View video=new SurfaceView(activity),nav=new FrameLayout(activity);
        final RelativeLayout feed=new RelativeLayout(activity);
        final LinearLayoutCompat carrier=new LinearLayoutCompat(activity);
        final RelativeLayout.LayoutParams carrierParams=new RelativeLayout.LayoutParams(-1,-2);
        final FrameLayout above=new FrameLayout(activity),metadata=new FrameLayout(activity);
        final RelativeLayout right=new RelativeLayout(activity);
        final LinearLayout progress=new LinearLayout(activity);
        final RectF navRect=new RectF(24,728,336,800);
        NavigationGlassClearance state;
        int width=360;
        Fixture() {
            root.addView(video,new FrameLayout.LayoutParams(-1,-1));feed.setId(0x7f0a01db);
            root.addView(feed,new FrameLayout.LayoutParams(-1,789));
            carrier.setId(0x7f0a15bb);carrier.setOrientation(LinearLayoutCompat.VERTICAL);
            carrierParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);feed.addView(carrier,carrierParams);
            above.setId(0x7f0a3ff1);carrier.addView(above,new LinearLayoutCompat.LayoutParams(-1,10));
            progress.setId(0x7f0a16b2);progress.setOrientation(LinearLayout.VERTICAL);
            LinearLayoutCompat.LayoutParams progressParams=new LinearLayoutCompat.LayoutParams(-1,1);progressParams.bottomMargin=47;
            carrier.addView(progress,progressParams);progress.addView(new View(activity),new LinearLayout.LayoutParams(-1,1));
            int[] chain={0x7f0a7303,0x7f0a2662,0x7f0a9065,0x7f0a40c4,0x7f0a4321,0x7f0a6b90,0x7f0a68ce};
            for(int i=0;i<chain.length;i++) {
                FrameLayout anchor=new FrameLayout(activity);anchor.setId(chain[i]);
                RelativeLayout.LayoutParams p=new RelativeLayout.LayoutParams(-1,0);
                p.addRule(RelativeLayout.ABOVE,i+1<chain.length?chain[i+1]:0x7f0a15bb);feed.addView(anchor,p);
                if(i==1 || i==4 || i==5)anchor.setVisibility(View.GONE);
            }
            metadata.setId(0x7f0a3ffc);RelativeLayout.LayoutParams m=new RelativeLayout.LayoutParams(-1,60);
            m.addRule(RelativeLayout.ABOVE,0x7f0a7303);feed.addView(metadata,m);
            TextView text=new TextView(activity);text.setText("Author and description");metadata.addView(text,new FrameLayout.LayoutParams(-1,-1));
            right.setId(0x7f0a4000);RelativeLayout.LayoutParams r=new RelativeLayout.LayoutParams(44,80);
            r.addRule(RelativeLayout.ALIGN_PARENT_RIGHT);r.addRule(RelativeLayout.ABOVE,0x7f0a15bb);feed.addView(right,r);
            root.addView(nav,new FrameLayout.LayoutParams(312,72,Gravity.BOTTOM|Gravity.CENTER_HORIZONTAL));
            activity.setContentView(root);layout();state=new NavigationGlassClearance(root,this::resting);discover();
        }
        RectF resting(View view){return view==nav?new RectF(navRect):bounds(view);}
        RectF bounds(View view) {
            Matrix matrix=new Matrix();view.transformMatrixToGlobal(matrix);Matrix inverse=new Matrix();
            Matrix rootMatrix=new Matrix();root.transformMatrixToGlobal(rootMatrix);rootMatrix.invert(inverse);matrix.postConcat(inverse);
            RectF result=new RectF(0,0,view.getWidth(),view.getHeight());matrix.mapRect(result);return result;
        }
        void discover(){state.discover(nodes(root));}
        boolean sync(){return state.sync(Collections.singleton(nav));}
        void layout(){root.measure(View.MeasureSpec.makeMeasureSpec(width,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(800,View.MeasureSpec.EXACTLY));root.layout(0,0,width,800);}
        @Override public void close(){state.close();activity.finish();}
    }
    /** Opaque by the module package boundary, with real native measurement and private child content. */
    private static final class OpaqueFixture extends FrameLayout {
        OpaqueFixture(Activity activity){super(activity);addView(new View(activity),new FrameLayout.LayoutParams(5,5));}
    }
    private static List<View> nodes(View root) {
        List<View> result=new ArrayList<>();result.add(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(nodes(((ViewGroup)root).getChildAt(i)));
        return result;
    }
}
