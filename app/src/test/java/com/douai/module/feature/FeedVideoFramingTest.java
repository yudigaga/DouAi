package com.douai.module.feature;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.SurfaceView;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.widget.FrameLayout;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import com.ss.android.ugc.aweme.feed.adapter.IFeedPlayerView;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class FeedVideoFramingTest {
    @Test public void lateHostLayoutIsReconciledBeforeDrawWithoutPolling() throws Throwable {
        try (FramingFixture f=new FramingFixture()) {
            f.select();
            ViewTreeObserver tree=f.holder.w.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,f.surface.getScaleY(),0);
            float translation=f.surface.getTranslationY();
            assertEquals(0f,f.surface.getTop()+(1-f.surface.getScaleY())*f.surface.getPivotY()+translation,0);

            int reads=f.holder.workReads;
            tree.dispatchOnGlobalLayout();
            tree.dispatchOnGlobalLayout();
            f.surface.layout(0,120,270,600); // Host placement committed after global layout observers.
            assertEquals(120,f.surface.getTop());
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(translation-60f,f.surface.getTranslationY(),0);
            assertEquals(reads+1,f.holder.workReads);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(reads+1,f.holder.workReads);
        }
    }

    @Test public void disableCancelsQueuedFramingAndRestoresTheNativeSurface() throws Throwable {
        try (FramingFixture f=new FramingFixture()) {
            f.select();
            ViewTreeObserver tree=f.holder.w.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,f.surface.getScaleX(),0);
            tree.dispatchOnGlobalLayout();
            f.framing.setEnabled(false);
            int reads=f.holder.workReads;
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(reads,f.holder.workReads);
            assertEquals(1f,f.surface.getScaleX(),0);
        }
    }

    @Test public void verifiedHostLayoutResetReframesNewBoundsBeforeDraw() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            ViewTreeObserver tree=f.selectFramed();
            Runnable completion=f.prepareLayoutRecovery();
            assertNotNull(completion);

            f.holder.w.layout(0,0,270,570);
            f.surface.layout(0,80,270,560);
            f.surface.setScaleX(1f);
            f.surface.setScaleY(1f);
            completion.run();

            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.1875f,f.surface.getScaleX(),0f);
            assertEquals(1.1875f,f.surface.getScaleY(),0f);
            assertEquals(-35f,f.surface.getTranslationY(),0f);
        }
    }

    @Test public void layoutRecoveryRequiresTheExactRendererWorkAndVideoAndACompleteReset() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            ViewTreeObserver tree=f.selectFramed();
            Work work=f.holder.work;
            Video video=work.getVideo();
            SurfaceView other=new SurfaceView(f.activity);
            f.holder.w.addView(other,new FrameLayout.LayoutParams(270,480));
            other.layout(0,60,270,540);
            assertNull(f.framing.prepareLayoutRecovery(other,work,video));
            assertNull(f.framing.prepareLayoutRecovery(f.surface,new Work(),video));
            assertNull(f.framing.prepareLayoutRecovery(f.surface,work,new Video()));

            Runnable completion=f.prepareLayoutRecovery();
            assertNotNull(completion);
            f.surface.setScaleX(1f);
            completion.run();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1f,f.surface.getScaleX(),0f);
            assertEquals(1.25f,f.surface.getScaleY(),0f);
        }
    }

    @Test public void disablingFramingCancelsPendingLayoutRecovery() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            ViewTreeObserver tree=f.selectFramed();
            Runnable completion=f.prepareLayoutRecovery();
            assertNotNull(completion);
            f.surface.setScaleX(1f);
            f.surface.setScaleY(1f);
            completion.run();
            f.framing.setEnabled(false);

            assertNull(f.prepareLayoutRecovery());
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1f,f.surface.getScaleX(),0f);
            assertEquals(1f,f.surface.getScaleY(),0f);
        }
    }

    @Test public void layoutRecoveryLeavesNativeFitAndLateClipAlone() throws Throwable {
        for(boolean addClip:new boolean[]{false,true}){
            try(FramingFixture f=new FramingFixture(true)){
                ViewTreeObserver tree=f.selectFramed();
                Runnable completion=f.prepareLayoutRecovery();
                assertNotNull(completion);
                f.surface.setScaleX(1f);
                f.surface.setScaleY(1f);
                completion.run();
                Rect clip=new Rect(1,1,269,479);
                if(addClip)f.surface.setClipBounds(clip);
                else f.holder.w.layout(0,0,270,780);

                assertFalse(tree.dispatchOnPreDraw());
                assertEquals(1f,f.surface.getScaleX(),0f);
                assertEquals(1f,f.surface.getScaleY(),0f);
                if(addClip)assertEquals(clip,f.surface.getClipBounds());
            }
        }
    }

    @Test public void laterLayoutForDifferentWorkCancelsQueuedRecoveryOnTheSameView() throws Throwable {
        try(FramingFixture f=new FramingFixture(true)){
            ViewTreeObserver tree=f.selectFramed();
            Runnable completion=f.prepareLayoutRecovery();
            assertNotNull(completion);
            f.surface.setScaleX(1f);
            f.surface.setScaleY(1f);
            completion.run();
            Work next=new Work();
            assertNull(f.framing.prepareLayoutRecovery(f.surface,next,next.getVideo()));

            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1f,f.surface.getScaleX(),0f);
            assertEquals(1f,f.surface.getScaleY(),0f);
        }
    }

    @Test public void layoutRecoveryDoesNotSurviveAnInterveningSelectionOrSameWorkRebind() throws Throwable {
        for(boolean sameWorkRebind:new boolean[]{false,true}) {
            try (FramingFixture f=new FramingFixture(true)) {
                ViewTreeObserver tree=f.selectFramed();
                Runnable obsolete=f.prepareLayoutRecovery();
                assertNotNull(obsolete);
                if(sameWorkRebind) {
                    f.bind(f.holder,f.holder.work);
                } else {
                    Holder other=f.nextCard();
                    other.w.layout(0,0,270,600);
                    Panel panel=f.holder.B2.mFeedContext;
                    panel.current=other;
                    f.harness.invoke(Holder.class.getMethod("selected"),other);
                    panel.current=f.holder;
                    f.select();
                }
                // Consume the legitimate draw scheduled by the replacement selection or binding.
                assertFalse(tree.dispatchOnPreDraw());
                assertEquals(1.25f,f.surface.getScaleX(),0f);
                assertEquals(1.25f,f.surface.getScaleY(),0f);

                f.surface.setScaleX(1f);
                f.surface.setScaleY(1f);
                obsolete.run();
                assertFalse(tree.dispatchOnPreDraw());
                assertEquals(1f,f.surface.getScaleX(),0f);
                assertEquals(1f,f.surface.getScaleY(),0f);
            }
        }
    }

    @Test public void detachingClearsTheOldDrawAndReattachingPreparesOneNewDraw() throws Throwable {
        try (FramingFixture f=new FramingFixture()) {
            f.select();
            FrameLayout card=f.holder.w;
            ViewTreeObserver tree=card.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,f.surface.getScaleX(),0);
            tree.dispatchOnGlobalLayout();
            ViewGroup parent=(ViewGroup)card.getParent();
            parent.removeView(card);
            int reads=f.holder.workReads;
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(reads,f.holder.workReads);
            assertEquals(1f,f.surface.getScaleX(),0);

            parent.addView(card);
            card.layout(0,0,270,600);
            f.surface.layout(0,60,270,540);
            assertFalse(card.getViewTreeObserver().dispatchOnPreDraw());
            assertEquals(reads+1,f.holder.workReads);
            assertEquals(1.25f,f.surface.getScaleX(),0);
        }
    }

    @Test public void modestCropFillsWideMismatchFitsAndUnknownRemainsUntouched() {
        assertArrayEquals(new int[]{1350,2400}, FeedVideoFraming.size(1080,1920,1080,2400)); // 80% retained
        assertArrayEquals(new int[]{1080,608}, FeedVideoFraming.size(1920,1080,1080,2400));
        assertArrayEquals(new int[]{1000,1000}, FeedVideoFraming.size(400,400,750,1000)); // exactly 75%
        assertArrayEquals(new int[]{749,749}, FeedVideoFraming.size(400,400,749,1000));
        assertNull(FeedVideoFraming.size(0,1920,1080,2400));
        assertNull(FeedVideoFraming.size(1080,1920,0,0));
    }

    @Test public void insetMediaLayerIsCenteredInCardWithoutEverResizingTheSurfaceBuffer() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout card=new FrameLayout(a),media=new FrameLayout(a);
            SurfaceView surface=new SurfaceView(a);
            card.addView(media,new FrameLayout.LayoutParams(1080,1920,Gravity.BOTTOM));
            media.addView(surface,new FrameLayout.LayoutParams(1080,1920));a.setContentView(card);
            card.layout(0,0,1080,2400);media.layout(0,480,1080,2400);surface.layout(0,0,1080,1920);
            FeedVideoFraming.Geometry g=new FeedVideoFraming.Geometry();
            assertTrue(g.frame(surface,card,1080,1920));
            assertEquals(1080,surface.getLayoutParams().width);assertEquals(1920,surface.getLayoutParams().height);
            assertEquals(1f,surface.getScaleY(),0);assertEquals(1.25f,media.getScaleY(),.001f);
            // Pivot is host-owned; measure the actual transformed picture's top/bottom.
            assertEquals(0f,480f+media.getPivotY()*(1-1.25f)+media.getTranslationY(),.001f);
            assertEquals(1.25f,media.getScaleX(),.001f);
            float translation=media.getTranslationY(); assertTrue(g.frame(surface,card,1080,1920));
            assertEquals(translation,media.getTranslationY(),.001f);
            g.restore();assertEquals(0f,media.getTranslationY(),0);assertEquals(1f,media.getScaleY(),0);
            assertEquals(Gravity.BOTTOM,((FrameLayout.LayoutParams)media.getLayoutParams()).gravity);
            // A mixed subtree cannot be lifted. Fit the picture symmetrically inside its real clip.
            View sibling=new View(a);media.addView(sibling);assertTrue(g.frame(surface,card,1080,1920));
            assertEquals(1f,media.getScaleY(),0);assertEquals(0f,sibling.getTranslationY(),0);
            float top=480+(1-surface.getScaleY())*surface.getPivotY()+surface.getTranslationY();
            assertEquals(480f,top,.01f);
            assertEquals(top,2400-(top+1920*surface.getScaleY()),.01f);
        } finally {a.finish();}
    }

    @Test public void fittedWideVideoGetsSymmetricBarsAndHostAnimationOwnershipIsRespected() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try{
            FrameLayout card=new FrameLayout(a);SurfaceView surface=new SurfaceView(a);
            card.addView(surface,new FrameLayout.LayoutParams(1080,608));a.setContentView(card);
            card.layout(0,0,1080,2400);surface.layout(0,1792,1080,2400);
            FeedVideoFraming.Geometry g=new FeedVideoFraming.Geometry();
            assertTrue(g.frame(surface,card,1920,1080));
            assertEquals(-896f,surface.getTranslationY(),.1f);
            surface.setTranslationY(17f);
            assertFalse(g.frame(surface,card,1920,1080));g.restore();
            assertEquals(17f,surface.getTranslationY(),0);
        }finally{a.finish();}
    }

    @Test public void outgoingVisibleHalfKeepsScaleUntilOffscreenAndDisableRestoresAllCards() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FeedVideoFraming.LeavingCards leaves=new FeedVideoFraming.LeavingCards();
        try{
            // Robolectric's hostless activity may report a GONE window even after setup and layout.
            FrameLayout pager=new FrameLayout(a), first=visibleCard(a), second=visibleCard(a);
            SurfaceView one=new SurfaceView(a),two=new SurfaceView(a);
            first.addView(one);second.addView(two);pager.addView(first);pager.addView(second);a.setContentView(pager);
            View decor=a.getWindow().getDecorView();int ww=View.MeasureSpec.makeMeasureSpec(270,View.MeasureSpec.EXACTLY),hh=View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY);
            decor.measure(ww,hh);decor.layout(0,0,270,600);
            pager.layout(0,0,270,600);first.layout(0,-300,270,300);second.layout(0,300,270,900);
            one.layout(0,60,270,540);two.layout(0,60,270,540);
            android.graphics.Rect visible=new android.graphics.Rect();
            assertTrue("old card stays partially visible while settling",first.getGlobalVisibleRect(visible)&&visible.height()>0);
            FeedVideoFraming.Geometry old=new FeedVideoFraming.Geometry();assertTrue(old.frame(one,first,270,480));
            assertEquals("first framed",1.25f,one.getScaleX(),0);
            leaves.leave(first,old); // pause/selection before scrolling has settled
            assertFalse("partly visible card is retained",leaves.empty());
            FeedVideoFraming.Geometry next=leaves.take(second);assertTrue(next.frame(two,second,270,480));
            pager.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals("outgoing",1.25f,one.getScaleX(),0);assertEquals("incoming",1.25f,two.getScaleX(),0);
            first.layout(0,-600,270,0);pager.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f,one.getScaleX(),0);assertEquals(1.25f,two.getScaleX(),0);
            second.layout(0,0,270,600);leaves.leave(second,next);
            assertSame(next,leaves.take(second)); // swipe cancelled: no intermediate unscale
            leaves.leave(second,next);leaves.clear();assertEquals(1f,two.getScaleX(),0);
        }finally{leaves.clear();a.finish();}
    }

    @Test public void transferredRenderSurfaceIsReleasedBeforeBecomingOwnedByANewCard() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        FeedVideoFraming.LeavingCards leaves=new FeedVideoFraming.LeavingCards();
        try{
            FrameLayout pager=new FrameLayout(a),from=visibleCard(a),to=visibleCard(a);
            SurfaceView surface=new SurfaceView(a);from.addView(surface);pager.addView(from);pager.addView(to);a.setContentView(pager);
            pager.layout(0,0,270,600);from.layout(0,0,270,600);to.layout(0,0,270,600);
            surface.layout(0,60,270,540);
            FeedVideoFraming.Geometry old=new FeedVideoFraming.Geometry();assertTrue(old.frame(surface,from,270,480));leaves.leave(from,old);
            assertFalse(leaves.empty());
            from.removeView(surface);to.addView(surface);surface.layout(0,60,270,540);
            FeedVideoFraming.Geometry next=leaves.take(to);assertTrue(next.frame(surface,to,270,480));
            pager.getViewTreeObserver().dispatchOnPreDraw();leaves.clear();assertEquals(1.25f,surface.getScaleX(),0);
            next.restore();assertEquals(1f,surface.getScaleX(),0);
        }finally{leaves.clear();a.finish();}
    }
    private static FrameLayout visibleCard(Activity owner){
        return new FrameLayout(owner){@Override public int getWindowVisibility(){return View.VISIBLE;}};
    }
    @Test public void stableGeometryNeverRepeatsAndResizeBurstsAreRateLimitedEvenAcrossWorks(){
        FeedVideoFraming.ChangeGate g=new FeedVideoFraming.ChangeGate();Object a=new Object(),b=new Object();
        assertTrue(g.emit(a,123,0));assertFalse(g.emit(a,123,2000));
        assertTrue(g.emit(a,124,2001));assertFalse(g.emit(a,125,2050));
        assertFalse(g.emit(b,124,2150));assertTrue(g.emit(b,124,3000));
        assertFalse(g.emit(b,124,4000));
    }

    @Test public void anIncomingRendererIsFramedBeforeSelectionAndRestoredOnSwipeReversal() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            f.select();
            Holder next=f.nextCard();
            SurfaceView media=(SurfaceView)next.player.getVideoView();
            f.bind(next,next.work);
            ViewTreeObserver tree=f.pager.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("Offscreen preparation may not change the view",1f,media.getScaleX(),0);
            next.w.layout(0,100,270,700);
            assertSame(f.pager,next.w.getParent());
            assertTrue(next.w.isShown() && media.isAttachedToWindow());
            android.graphics.Rect clip=new android.graphics.Rect(), viewport=new android.graphics.Rect();
            assertTrue("Fixture card must enter the pager",next.w.getGlobalVisibleRect(clip)
                    && f.pager.getGlobalVisibleRect(viewport) && android.graphics.Rect.intersects(clip,viewport));
            assertTrue("Verified bind hook registration",f.harness.hooks.containsKey(Holder.class.getMethod("bind",Work.class)));
            assertSame(tree,next.w.getViewTreeObserver());
            assertEquals(270,media.getWidth());
            assertEquals(480,media.getHeight());
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("The verified renderer is already framed on its first visible traversal",1.25f,media.getScaleX(),0);
            assertSame(f.holder,f.holder.B2.mFeedContext.current);
            int reads=next.workReads;
            for (int i=0;i<5;i++) assertFalse(tree.dispatchOnPreDraw());
            assertEquals("Stable paint does not re-read the work or player",reads,next.workReads);
            next.w.layout(0,600,270,1200);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("An aborted swipe releases the incoming transform",1f,media.getScaleX(),0);
        }
    }

    @Test public void selectingTheSameIncomingOwnerKeepsTheFrameButRebindFirstRestoresIt() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            f.select();
            Holder next=f.nextCard();
            SurfaceView media=(SurfaceView)next.player.getVideoView();
            f.bind(next,next.work);
            next.w.layout(0,100,270,700);
            ViewTreeObserver tree=f.pager.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,media.getScaleX(),0);
            next.beforeBind=()->assertEquals("Release old view state before host reuse",1f,media.getScaleX(),0);
            f.harness.invoke(Holder.class.getMethod("bind",Work.class,int.class),next,new Work(),1);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,media.getScaleX(),0);
            f.holder.B2.mFeedContext.current=next;
            f.harness.invoke(Holder.class.getMethod("selected"),next);
            assertEquals("The same selected binding never flashes at its native size",1.25f,media.getScaleX(),0);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,media.getScaleX(),0);
            f.framing.setEnabled(false);
            assertEquals(1f,media.getScaleX(),0);
        }
    }

    @Test public void programmaticSettlingDoesNotStartAnIncomingTransform() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            f.select();
            Holder next=f.nextCard();
            SurfaceView media=(SurfaceView)next.player.getVideoView();
            f.bind(next,next.work);
            f.pager.state=2; // Programmatic settling, with no preceding user drag.
            next.w.layout(0,100,270,700);
            ViewTreeObserver tree=f.pager.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            tree.dispatchOnGlobalLayout();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1f,media.getScaleX(),0);
            next.w.layout(0,600,270,1200);
            assertFalse(tree.dispatchOnPreDraw());
            f.pager.state=1;
            next.w.layout(0,100,270,700);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,media.getScaleX(),0);
        }
    }

    @Test public void rendererMustBelongToCardAndLeaveRotatedPinchedOrUnclippedLayoutsAlone() {
        Activity a=Robolectric.buildActivity(Activity.class).setup().get();
        try {
            FrameLayout card=new FrameLayout(a),parent=new FrameLayout(a);
            card.addView(parent,new FrameLayout.LayoutParams(-1,-1));
            SurfaceView surface=new SurfaceView(a);parent.addView(surface,new FrameLayout.LayoutParams(-1,-1));a.setContentView(card);
            Matrix m=new Matrix();
            card.layout(0,0,1080,2400);parent.layout(0,0,1080,2400);surface.layout(0,240,1080,2160);
            FeedVideoFraming.Geometry g=new FeedVideoFraming.Geometry();
            assertTrue(g.frame(surface,card,1080,1920));g.restore();
            assertFalse(g.frame(parent,card,1080,1920)); // not a render surface
            assertFalse(g.frame(surface,new FrameLayout(a),1080,1920));
            surface.setRotation(90);assertFalse(g.frame(surface,card,1080,1920));surface.setRotation(0);
            parent.setScaleX(.7f);assertFalse(g.frame(surface,card,1080,1920));parent.setScaleX(1);
            card.setClipChildren(false);assertFalse(g.frame(surface,card,1080,1920));card.setClipChildren(true);
            assertFalse(g.frame(surface,card,1920,1080)); // host plane doesn't match supplied video's aspect
        } finally {a.finish();}
    }

    @Test public void framingDenialsDistinguishNativeLayersFromWrongAncestry() throws Exception {
        try (FramingFixture f=new FramingFixture()) {
            FeedVideoFraming.Geometry geometry=new FeedVideoFraming.Geometry();
            f.surface.setAnimation(new android.view.animation.AlphaAnimation(1f,1f));
            assertFalse(geometry.frame(f.surface,f.holder.w,270,480));
            assertEquals("native_animation",geometry.reason);
            f.surface.clearAnimation();
            f.surface.setClipBounds(new android.graphics.Rect(0,0,270,400));
            assertFalse(geometry.frame(f.surface,f.holder.w,270,480));
            assertEquals("native_clip",geometry.reason);
            f.surface.setClipBounds(null);
            f.surface.setTranslationY(10f);
            assertFalse(geometry.frame(f.surface,f.holder.w,270,480));
            assertEquals("native_translation",geometry.reason);
            f.surface.setTranslationY(0f);
            assertNull(f.surface.getClipBounds());
            f.holder.w.setClipBounds(new android.graphics.Rect(0,0,270,590));
            assertFalse(geometry.frame(f.surface,f.holder.w,270,480));
            assertEquals("native_clip",geometry.reason);
            assertEquals("card",geometry.blockedLayer);
            FrameLayout unrelated=new FrameLayout(f.activity);
            unrelated.layout(0,0,270,600);
            assertFalse(geometry.frame(f.surface,unrelated,270,480));
            assertEquals("renderer_outside_card",geometry.reason);
            assertEquals("ancestry",geometry.blockedLayer);
            assertEquals(1f,f.surface.getScaleX(),0f);
        }
    }

    @Test public void onlyVerifiedColourBandsAreSuppressedWhileVideoIsEnlargedToFill() throws Exception {
        try (FramingFixture f=new FramingFixture()) {
            FrameLayout card=f.holder.w;
            View overlay=new View(f.activity);
            card.addView(overlay,new FrameLayout.LayoutParams(270,90));
            com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout decoration=
                    new com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout(f.activity);
            decoration.setId(0x7f0a3ffa);
            FrameLayout colours=new FrameLayout(f.activity);
            colours.setId(0x7f0a3fac);
            decoration.addView(colours,new android.widget.RelativeLayout.LayoutParams(-1,-1));
            card.addView(decoration,new FrameLayout.LayoutParams(-1,-1));
            for (int gravity:new int[]{Gravity.TOP,Gravity.BOTTOM}) {
                android.widget.ImageView band=new android.widget.ImageView(f.activity);
                band.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
                band.setImageDrawable(new android.graphics.drawable.ColorDrawable(0xff75675f));
                colours.addView(band,new FrameLayout.LayoutParams(270,70,gravity|Gravity.CENTER_HORIZONTAL));
            }
            decoration.layout(0,0,270,600);
            colours.layout(0,0,270,600);
            FeedVideoFraming.Geometry geometry=new FeedVideoFraming.Geometry();
            assertTrue(geometry.frame(f.surface,card,270,480));
            geometry.syncColouring(card,f.surface);
            assertEquals(0f,colours.getTransitionAlpha(),0f);
            assertEquals(1f,overlay.getTransitionAlpha(),0f);
            assertEquals(View.VISIBLE,colours.getVisibility());
            assertEquals(1f,colours.getAlpha(),0f);

            f.surface.layout(0,225,270,375);
            assertTrue(geometry.frame(f.surface,card,270,150));
            geometry.syncColouring(card,f.surface);
            assertEquals("Horizontal fit still owns its native fill",1f,colours.getTransitionAlpha(),0f);

            f.surface.layout(0,60,270,540);
            assertTrue(geometry.frame(f.surface,card,270,480));
            colours.addView(new View(f.activity),new FrameLayout.LayoutParams(30,30));
            geometry.syncColouring(card,f.surface);
            assertEquals("Unexpected children preserve native behavior",1f,colours.getTransitionAlpha(),0f);
            colours.removeViewAt(2);
            geometry.syncColouring(card,f.surface);
            assertEquals(0f,colours.getTransitionAlpha(),0f);
            colours.setTransitionAlpha(.5f);
            geometry.restore();
            assertEquals("Restore must yield to a newer native value",.5f,colours.getTransitionAlpha(),0f);
        }
    }

    @Test @Config(qualifiers="w360dp-h800dp-port")
    public void translatedUpperBandTracksTheRealCurrentBindingAndReleasesBeforeSameWorkRebind() throws Throwable {
        try (FramingFixture f=new FramingFixture(true)) {
            FrameLayout card=f.holder.w;
            com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout overlay=
                    new com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout(f.activity);
            overlay.setId(0x7f0a3ffa);
            FrameLayout colours=new FrameLayout(f.activity);
            colours.setId(0x7f0a3fac);
            overlay.addView(colours,new android.widget.RelativeLayout.LayoutParams(-1,-1));
            card.addView(overlay,new FrameLayout.LayoutParams(-1,-1));

            android.widget.ImageView top=new android.widget.ImageView(f.activity);
            top.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
            android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(2,2,android.graphics.Bitmap.Config.ARGB_8888);
            top.setImageDrawable(new android.graphics.drawable.BitmapDrawable(f.activity.getResources(),bitmap));
            colours.addView(top,new FrameLayout.LayoutParams(270,70,Gravity.TOP|Gravity.CENTER_HORIZONTAL));
            overlay.layout(0,0,270,600);
            colours.layout(0,0,270,600);
            top.layout(0,0,270,70);

            f.select();
            ViewTreeObserver tree=card.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(0f,colours.getTransitionAlpha(),0f);

            android.graphics.Rect cardRect=new android.graphics.Rect(),videoRect=new android.graphics.Rect();
            assertSame(card.getWindowToken(),f.surface.getWindowToken());
            assertTrue(card.getGlobalVisibleRect(cardRect));
            assertTrue(f.surface.getGlobalVisibleRect(videoRect));
            assertEquals("Full card must be visible in the fixture: "+cardRect,card.getHeight(),cardRect.height());
            assertTrue("Renderer "+videoRect+" must fill card "+cardRect,videoRect.contains(cardRect));

            top.setTranslationY(1.5f);
            tree.dispatchOnGlobalLayout();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1f,colours.getTransitionAlpha(),0f);
            assertEquals("A currently bound, fully covering renderer can retain the translated top",0f,top.getTransitionAlpha(),0f);

            card.offsetTopAndBottom(2);
            assertTrue(card.getGlobalVisibleRect(cardRect) && cardRect.height()<card.getHeight());
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("A clipped card restores the translated band",1f,top.getTransitionAlpha(),0f);
            card.offsetTopAndBottom(-2);
            tree.dispatchOnGlobalLayout();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(0f,top.getTransitionAlpha(),0f);

            f.holder.B2.mFeedContext.current=null;
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("Losing the current holder revokes suppression without a layout event",1f,top.getTransitionAlpha(),0f);
            f.holder.B2.mFeedContext.current=f.holder;
            tree.dispatchOnGlobalLayout();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(0f,top.getTransitionAlpha(),0f);

            f.holder.beforeBind=()->assertEquals("The old lease is restored before the host writes a new binding",1f,top.getTransitionAlpha(),0f);
            f.bind(f.holder,f.holder.work);
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals("A rebind must never reuse the previous top proof",1f,top.getTransitionAlpha(),0f);
            assertEquals(1.5f,top.getTranslationY(),0f);

            top.setTranslationY(0f);
            tree.dispatchOnGlobalLayout();
            tree.dispatchOnPreDraw();
            assertEquals(0f,colours.getTransitionAlpha(),0f);

            f.holder.work=new Work(); // A replaced work can also be observed without a bind callback.
            top.setTranslationY(1.5f);
            tree.dispatchOnGlobalLayout();
            tree.dispatchOnPreDraw();
            assertEquals("The new work cannot inherit the previous bitmap proof",1f,top.getTransitionAlpha(),0f);
        }
    }

    private static final class FramingFixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().visible().get();
        final Holder holder=new Holder();
        final SurfaceView surface=new SurfaceView(activity);
        final Pager pager;
        final VideoPageEntryHookTest.Harness harness=new VideoPageEntryHookTest.Harness();
        final FeedVideoFraming framing;

        FramingFixture() throws Exception {
            this(false);
        }
        FramingFixture(boolean incoming) throws Exception {
            holder.w=visibleCard(activity);
            holder.B2.mFeedContext.current=holder;
            holder.player=()->surface;
            holder.w.addView(surface,new FrameLayout.LayoutParams(270,480));
            pager=incoming?new Pager(activity):null;
            if (pager==null) activity.setContentView(holder.w);
            else {
                holder.B2.mFeedContext.f=pager;
                pager.addView(holder.w,new FrameLayout.LayoutParams(270,600));
                activity.setContentView(pager);
                View decor=activity.getWindow().getDecorView();
                decor.measure(View.MeasureSpec.makeMeasureSpec(270,View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
                decor.layout(0,0,270,600);pager.layout(0,0,270,600);
            }
            holder.w.layout(0,0,270,600);
            surface.layout(0,60,270,540);
            surface.setPivotY(240f);
            List<RuleScanResult> results=new ArrayList<>();
            add(results,"feed_holder_page_selected",Holder.class,"selected");
            add(results,"feed_holder_resume",Holder.class,"resume");
            add(results,"feed_holder_pause",Holder.class,"pause");
            add(results,"feed_aweme_accessor",Holder.class,"getAweme");
            add(results,"auto_slide_current_holder",Panel.class,"currentHolder");
            add(results,"auto_slide_comments_showing",Dialog.class,"comments",boolean.class);
            if (incoming) {
                add(results,"feed_item_bind_aweme",Holder.class,"bind",Work.class);
                add(results,"feed_item_bind_aweme_position",Holder.class,"bind",Work.class,int.class);
                add(results,"auto_slide_pager_touch",Pager.class,"dispatchTouchEvent",MotionEvent.class);
                add(results,"auto_slide_scroll_state",Pager.class,"getScrollState");
            }
            framing=FeedVideoFraming.install(harness.framework,new HostScanReport("test",results,false),
                    getClass().getClassLoader(),true,s->{});
        }

        private static void add(List<RuleScanResult> results,String id,Class<?> owner,String name,Class<?>... args)
                throws NoSuchMethodException {
            VideoPageEntryHookTest.add(results,id,owner.getMethod(name,args));
        }

        void select() throws Throwable {harness.invoke(Holder.class.getMethod("selected"),holder);}
        ViewTreeObserver selectFramed() throws Throwable {
            select();
            ViewTreeObserver tree=holder.w.getViewTreeObserver();
            assertFalse(tree.dispatchOnPreDraw());
            assertEquals(1.25f,surface.getScaleX(),0f);
            assertEquals(1.25f,surface.getScaleY(),0f);
            return tree;
        }
        Runnable prepareLayoutRecovery() {
            return framing.prepareLayoutRecovery(surface,holder.work,holder.work.getVideo());
        }
        void bind(Holder target,Work work) throws Throwable {harness.invoke(Holder.class.getMethod("bind",Work.class),target,work);}
        Holder nextCard() {
            Holder next=new Holder();
            next.B2.mFeedContext=holder.B2.mFeedContext;
            next.w=visibleCard(activity);
            SurfaceView media=new SurfaceView(activity);
            next.player=()->media;
            next.w.addView(media,new FrameLayout.LayoutParams(270,480,Gravity.CENTER));
            pager.addView(next.w,new FrameLayout.LayoutParams(270,600));
            next.w.measure(View.MeasureSpec.makeMeasureSpec(270,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
            next.w.layout(0,600,270,1200);
            return next;
        }
        @Override public void close(){framing.close();activity.finish();}
    }

    public static class Holder {
        public FrameLayout w;
        public final Params B2=new Params();
        private Work work=new Work();
        Runnable beforeBind;
        IFeedPlayerView player;
        int workReads;

        public void selected(){}
        public void resume(){}
        public void pause(){}
        public void bind(Work bound){if(beforeBind!=null)beforeBind.run();work=bound;}
        public void bind(Work bound,int position){bind(bound);}
        public Work getAweme(){workReads++;return work;}
        public IFeedPlayerView getFeedPlayerView(){return player;}
    }

    public static class Pager extends FrameLayout {
        int state=1;
        Pager(Activity owner){super(owner);}
        @Override public boolean dispatchTouchEvent(MotionEvent event){return super.dispatchTouchEvent(event);}
        public int getScrollState(){return state;}
    }
    public static class Params {public Panel mFeedContext=new Panel();}
    public static class Panel {
        public final Dialog L0=new Dialog();
        public Pager f;
        Holder current;
        public Holder currentHolder(){return current;}
    }
    public static class Dialog {
        public boolean comments(boolean unused){return false;}
        public void LJJIJIIJIL(String name,boolean show){}
    }
    public static class Work {
        private final Video video=new Video();
        public int getAwemeType(){return 0;}
        public boolean isLive(){return false;}
        public boolean getAd(){return false;}
        public Video getVideo(){return video;}
    }
    public static class Video {public final int t=270,s=480;}
}
