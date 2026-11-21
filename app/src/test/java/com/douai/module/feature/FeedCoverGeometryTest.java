package com.douai.module.feature;

import android.app.Activity;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.view.SurfaceView;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(manifest=Config.NONE,sdk=35,qualifiers="mdpi")
public class FeedCoverGeometryTest {
    @Test public void onlyThePaintedCoverMatchesAnAlreadyFramedRenderer() {
        try (Fixture f=new Fixture()) {
            assertFalse("Without framing the visible plane is shorter than the cover",
                    FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.render.setScaleX(1.25f);f.render.setScaleY(1.25f);
            assertTrue(FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.cover.hierarchy.bounds.set(0,360,120,600);
            assertFalse("A full-size CoverImageView may only paint a thumbnail in one corner",
                    FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.cover.hierarchy.bounds.set(0,0,300,600);
            f.cover.setImageDrawable(null);
            assertFalse("A detached image payload cannot count as ready",FeedCoverGeometry.matches(f.card,f.cover,f.render));
        }
    }

    @Test public void coversCanOnlyMatchTheRendererOfTheirOwnCardWithKnownGeometry() {
        try (Fixture f=new Fixture()) {
            f.cover.hierarchy.bounds.set(0,60,300,540);
            assertTrue(FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.cover.setRotation(4);
            assertFalse(FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.cover.setRotation(0);
            f.cover.hierarchy.bounds.set(Float.NaN,60,300,540);
            assertFalse(FeedCoverGeometry.matches(f.card,f.cover,f.render));
            f.cover.hierarchy.bounds.set(0,60,300,540);
            FrameLayout other=new FrameLayout(f.activity);
            f.card.removeView(f.render);other.addView(f.render);
            assertFalse(FeedCoverGeometry.matches(f.card,f.cover,f.render));
        }
    }

    public static final class Hierarchy {
        final RectF bounds=new RectF(0,0,300,600);
        public void getActualImageBounds(RectF out){out.set(bounds);}
    }

    public static final class Cover extends ImageView {
        final Hierarchy hierarchy=new Hierarchy();
        Cover(Activity a){super(a);setImageDrawable(new ColorDrawable(0xFFFF0000));}
        public Hierarchy getHierarchy(){return hierarchy;}
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        final FrameLayout card=new FrameLayout(activity);
        final Cover cover=new Cover(activity);
        final SurfaceView render=new SurfaceView(activity);
        Fixture(){
            card.addView(render,new FrameLayout.LayoutParams(300,480));
            card.addView(cover,new FrameLayout.LayoutParams(300,600));
            activity.setContentView(card);
            card.measure(View.MeasureSpec.makeMeasureSpec(300,View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY));
            card.layout(0,0,300,600);
            render.layout(0,60,300,540);render.setPivotX(150);render.setPivotY(240);
        }
        @Override public void close(){activity.finish();}
    }
}
