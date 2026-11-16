package com.douai.module.feature;

import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import androidx.appcompat.widget.AppCompatImageView;
import androidx.appcompat.widget.LinearLayoutCompat;
import com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout;
import com.ss.android.ugc.aweme.kiwi.widget.QLayout;
import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildFrameLayout;
import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

/** Two measured host Feed pages in one Activity; this does not establish real-phone behavior. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=35,qualifiers="w360dp-h800dp-mdpi")
public final class NavigationGlassClearancePageScopeTest {
    private enum Fault { STRUCTURE, UNKNOWN_IMAGE, PENDING_SIZE }

    @Test public void offscreenNeighborChangesDoNotBlockOrReviseTheActivePage() throws Exception {
        for(int direction:new int[]{-1,1})for(Fault fault:Fault.values())try(Pages pages=new Pages(direction)) {
            int margin=pages.scene.carrierParams.bottomMargin;
            long revision=pages.clearance.inputRevision();
            pages.damageNeighbor(fault);
            if(fault!=Fault.PENDING_SIZE)pages.layout();
            else pages.assertNeighborMetadataPending();
            pages.assertActivePageUnchanged(margin,revision);
            pages.layout();pages.assertActivePageUnchanged(margin,revision);
            // Discovery of a changed preloaded subtree must not change the participating-page signature either.
            pages.discover();pages.layout();pages.assertActivePageUnchanged(margin,revision);
        }
    }

    @Test public void pendingNeighborMetadataWaitsForLayoutWhenItsPageEntersTheViewport() throws Exception {
        try(Pages pages=new Pages(1)) {
            int margin=pages.scene.carrierParams.bottomMargin;
            long revision=pages.clearance.inputRevision();
            pages.damageNeighbor(Fault.PENDING_SIZE);pages.assertNeighborMetadataPending();
            pages.neighbor.feed.setTranslationY(0);
            assertFalse("An unapplied observed size must wait instead of writing a new budget",pages.sync());
            assertFalse("An active page cannot bypass its pending metadata layout",pages.clearance.retryReady());
            String pending=pages.clearance.geometryDiagnostic(List.of(pages.scene.navigation));
            assertTrue(pending,pending.contains("sync_state=pending_target_layout"));
            assertTrue("The observed metadata must be the reason for waiting: "+pending,
                    pending.contains("last_pending_target="+pages.neighbor.metadata.getClass().getName()+":7f0a3ffc"));
            assertTrue("Pending layout is not a safety rejection",pages.clearance.blockedViews().isEmpty());
            assertEquals(margin,pages.scene.carrierParams.bottomMargin);
            assertEquals(0,pages.neighbor.carrierParams.bottomMargin);
            assertEquals("Do not commit new page inputs before its size is applied",revision,pages.clearance.inputRevision());
            for(int pass=0;pass<3;pass++){pages.layout();pages.sync();}
            pages.layout();assertFalse(pages.sync());
            assertEquals(pages.neighbor.metadataParams.height,pages.neighbor.metadata.getHeight());
            assertTrue(pages.clearance.retryReady());assertTrue(pages.clearance.blockedViews().isEmpty());
            assertTrue(pages.clearance.inputRevision()>revision);
            assertEquals(margin,pages.scene.carrierParams.bottomMargin);
            assertTrue("The now-laid-out page can participate in clearance",pages.neighbor.carrierParams.bottomMargin>0);
        }
    }

    @Test public void enteringTheViewportReenablesStructuralAndUnknownGeometryProtection() throws Exception {
        for(Fault fault:new Fault[]{Fault.STRUCTURE,Fault.UNKNOWN_IMAGE})try(Pages pages=new Pages(1)) {
            pages.damageNeighbor(fault);pages.layout();
            long offscreenRevision=pages.clearance.inputRevision();
            assertFalse(pages.sync());assertTrue(pages.clearance.blockedViews().isEmpty());
            pages.neighbor.feed.setTranslationY(0);
            pages.assertActiveNeighborIsRejected(offscreenRevision);
        }
    }

    @Test public void hiddenNeighborPagesAreExcludedUntilTheirParentIsShown() throws Exception {
        for(boolean gone:new boolean[]{false,true})try(Pages pages=new Pages(1)) {
            int margin=pages.scene.carrierParams.bottomMargin;
            long revision=pages.clearance.inputRevision();
            pages.unknownImage=true;
            pages.neighbor.feed.setTranslationY(0);
            if(gone)pages.neighbor.feed.setVisibility(View.GONE);else pages.neighbor.feed.setAlpha(0);
            pages.layout();pages.assertActivePageUnchanged(margin,revision);
            if(gone)pages.neighbor.feed.setVisibility(View.VISIBLE);else pages.neighbor.feed.setAlpha(1);
            pages.layout();pages.assertActiveNeighborIsRejected(revision);
        }
    }

    @Test public void anUnknownParentFrameCannotBeAssumedToRemainOffscreen() throws Exception {
        try(Pages pages=new Pages(1)) {
            long revision=pages.clearance.inputRevision();
            // Last measured location is offscreen, but a failed current page transform cannot prove that.
            pages.unknownParent=true;pages.unknownImage=true;
            pages.assertActiveNeighborIsRejected(revision);
        }
    }

    @Test public void bothHalfVisiblePagesRetainConservativeSafetyDuringATransition() throws Exception {
        try(Pages pages=new Pages(1)) {
            long revision=pages.clearance.inputRevision();
            int half=pages.scene.root.getHeight()/2;
            pages.scene.feed.setTranslationY(half);
            pages.neighbor.feed.setTranslationY(-half);
            pages.unknownImage=true;
            pages.assertActiveNeighborIsRejected(revision);
        }
    }

    @Test public void leavingTheViewportRestoresOwnedMarginWithoutReenteringFromThatRestore() throws Exception {
        try(Pages pages=new Pages(1)) {
            int activeMargin=pages.scene.carrierParams.bottomMargin;
            long activeRevision=pages.clearance.inputRevision();
            pages.scene.feed.setTranslationY(-pages.scene.root.getHeight());
            assertTrue("The departing page returns its owned offset",pages.sync());
            assertEquals(0,pages.scene.carrierParams.bottomMargin);
            pages.layout();long inactiveRevision=pages.clearance.inputRevision();
            assertTrue(inactiveRevision>activeRevision);
            for(int pass=0;pass<4;pass++) {
                assertFalse("Restoring the footer cannot make its page active again",pages.sync());
                pages.layout();assertEquals(0,pages.scene.carrierParams.bottomMargin);
                assertEquals(inactiveRevision,pages.clearance.inputRevision());
            }
            pages.scene.feed.setTranslationY(0);assertTrue(pages.sync());pages.layout();
            assertEquals(activeMargin,pages.scene.carrierParams.bottomMargin);
            assertTrue(pages.clearance.inputRevision()>inactiveRevision);
            assertFalse(pages.sync());assertTrue(pages.clearance.blockedViews().isEmpty());
        }
    }

    private static final class Pages implements AutoCloseable {
        final LiquidGlassClearanceControllerTest.Scene scene;
        final Page neighbor;
        final NavigationGlassClearance clearance;
        boolean unknownImage,unknownParent;
        Pages(int direction) throws Exception {
            scene=new LiquidGlassClearanceControllerTest.Scene(false,false);
            neighbor=new Page(scene);
            scene.forceInitialLayout();layout();
            neighbor.feed.setTranslationY(direction*scene.root.getHeight());
            clearance=new NavigationGlassClearance(scene.root,this::bounds);
            discover();
            for(int pass=0;pass<3;pass++){sync();layout();}
            assertFalse("The active page reaches a layout fixed point before the fault",sync());
            assertTrue(scene.carrierParams.bottomMargin>0);
            assertEquals(0,neighbor.carrierParams.bottomMargin);
            assertTrue(clearance.blockedViews().isEmpty());assertTrue(clearance.retryReady());
        }
        RectF bounds(View view) {
            if(unknownParent && view==neighbor.feed || unknownImage && view==neighbor.image)return null;
            Rect result=scene.bounds(view),origin=scene.bounds(scene.root);
            RectF local=new RectF(result);local.offset(-origin.left,-origin.top);return local;
        }
        void damageNeighbor(Fault fault) {
            if(fault==Fault.STRUCTURE)neighbor.above.addView(new View(scene.activity),new FrameLayout.LayoutParams(8,8));
            else if(fault==Fault.UNKNOWN_IMAGE)unknownImage=true;
            else {neighbor.metadataParams.height++;neighbor.metadata.requestLayout();}
        }
        void assertNeighborMetadataPending() {
            assertSame("Metadata and carrier are siblings under the verified Feed parent",neighbor.feed,neighbor.metadata.getParent());
            assertSame(neighbor.feed,neighbor.carrier.getParent());
            assertTrue("The observed metadata itself has an unapplied layout request",neighbor.metadata.isLayoutRequested());
            assertTrue("The request reaches the actual Feed ancestor",neighbor.feed.isLayoutRequested());
            assertEquals("The pending fixed height is exactly one pixel ahead of the measured view",
                    neighbor.metadata.getHeight()+1,neighbor.metadataParams.height);
        }
        void assertActivePageUnchanged(int margin,long revision) {
            assertFalse("An excluded page must not request active-page writes",sync());
            assertTrue("The budget must run, rather than silently wait on an offscreen request",clearance.retryReady());
            assertTrue(clearance.blockedViews().isEmpty());
            assertEquals(margin,scene.carrierParams.bottomMargin);assertEquals(revision,clearance.inputRevision());
            assertEquals(0,neighbor.carrierParams.bottomMargin);
            scene.assertClearance(3);
        }
        void assertActiveNeighborIsRejected(long oldRevision) {
            sync();
            assertTrue("A participating unsafe page must protect the common navigation",
                    clearance.blockedViews().contains(scene.navigation));
            assertFalse(clearance.retryReady());assertTrue(clearance.inputRevision()>oldRevision);
            assertEquals("Failure restores the active page's still-owned offset",0,scene.carrierParams.bottomMargin);
        }
        void discover(){clearance.discover(LiquidGlassTargets.nodes(scene.root));}
        boolean sync(){return clearance.sync(List.of(scene.navigation));}
        void layout(){scene.layout();}
        @Override public void close(){try{clearance.close();}finally{scene.close();}}
    }

    private static final class Page {
        final PenetrateTouchRelativeLayout feed;
        final LinearLayoutCompat carrier;
        final RelativeLayout.LayoutParams carrierParams=new RelativeLayout.LayoutParams(-1,-2);
        final QLayout above;
        final MeasureOnceCheckDrawChildFrameLayout metadata;
        final RelativeLayout.LayoutParams metadataParams=new RelativeLayout.LayoutParams(240,60);
        final AppCompatImageView image;
        Page(LiquidGlassClearanceControllerTest.Scene scene) {
            feed=new PenetrateTouchRelativeLayout(scene.activity);feed.setId(0x7f0a01db);
            scene.root.addView(feed,new FrameLayout.LayoutParams(-1,-1));
            carrier=new LinearLayoutCompat(scene.activity);carrier.setId(0x7f0a15bb);carrier.setOrientation(LinearLayout.VERTICAL);
            carrierParams.addRule(RelativeLayout.ALIGN_PARENT_BOTTOM);feed.addView(carrier,carrierParams);
            above=new QLayout(scene.activity);above.setId(0x7f0a3ff1);
            carrier.addView(above,new LinearLayoutCompat.LayoutParams(-1,0));
            LinearLayout progress=new LinearLayout(scene.activity);progress.setId(0x7f0a16b2);progress.setOrientation(LinearLayout.VERTICAL);
            LinearLayoutCompat.LayoutParams progressParams=new LinearLayoutCompat.LayoutParams(-1,18);progressParams.bottomMargin=47;
            carrier.addView(progress,progressParams);
            image=new AppCompatImageView(scene.activity);image.setId(0x7f0a2da7);
            progress.addView(image,new LinearLayout.LayoutParams(18,18));
            metadata=new MeasureOnceCheckDrawChildFrameLayout(scene.activity);metadata.setId(0x7f0a3ffc);
            metadataParams.addRule(RelativeLayout.ABOVE,carrier.getId());feed.addView(metadata,metadataParams);
            TextView text=new TextView(scene.activity);text.setText("Neighbour author and description");
            metadata.addView(text,new FrameLayout.LayoutParams(-1,-1));
            MeasureOnceCheckDrawChildRelativeLayout right=new MeasureOnceCheckDrawChildRelativeLayout(scene.activity);right.setId(0x7f0a4000);
            RelativeLayout.LayoutParams rightParams=new RelativeLayout.LayoutParams(61,52);
            rightParams.addRule(RelativeLayout.ALIGN_PARENT_END);rightParams.addRule(RelativeLayout.ABOVE,carrier.getId());
            feed.addView(right,rightParams);View action=new View(scene.activity);action.setClickable(true);
            right.addView(action,new RelativeLayout.LayoutParams(44,44));
        }
    }
}
