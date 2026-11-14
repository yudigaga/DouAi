package com.douai.module.feature;

import android.app.Activity;
import android.view.Gravity;
import android.view.View;
import android.view.SurfaceView;
import android.graphics.Matrix;
import android.widget.FrameLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35)
public class FeedVideoFramingTest {
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
}
