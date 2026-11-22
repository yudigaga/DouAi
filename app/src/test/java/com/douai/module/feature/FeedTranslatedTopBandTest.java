package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.animation.AlphaAnimation;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.RelativeLayout;
import android.widget.TextView;

import com.ss.android.ugc.aweme.view.MeasureOnceCheckDrawChildRelativeLayout;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FeedTranslatedTopBandTest {
    @Test public void onlyAnAlreadyVerifiedTranslatedTopIsHiddenWithTheContainerAndCaptionsVisible() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.band(Gravity.TOP);
            ImageView bottom = f.band(Gravity.BOTTOM);
            top.setTranslationY(1.5f);
            f.layout();
            f.sync();
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
            assertEquals(1f, top.getTransitionAlpha(), 0f);

            top.setTranslationY(0f);
            f.sync();
            assertEquals("A verified static layout retains the normal container lease", 0f,
                    f.colours.getTransitionAlpha(), 0f);
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            TextView caption = new TextView(f.activity);
            caption.setText("示例字幕");
            f.colours.addView(caption, new FrameLayout.LayoutParams(270, 30, Gravity.CENTER));
            View progress = new View(f.activity);
            f.overlay.addView(progress, new RelativeLayout.LayoutParams(270, 12));
            ImageView otherBitmap = f.band(Gravity.TOP);
            otherBitmap.setId(1234);
            otherBitmap.setTranslationY(2f);
            top.setTranslationY(1.5f);
            f.layout();
            f.sync();

            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
            assertEquals(0f, top.getTransitionAlpha(), 0f);
            assertEquals(1f, bottom.getTransitionAlpha(), 0f);
            assertEquals(1f, otherBitmap.getTransitionAlpha(), 0f);
            assertEquals(2f, otherBitmap.getTranslationY(), 0f);
            assertEquals(1.5f, top.getTranslationY(), 0f);
            assertEquals(View.VISIBLE, top.getVisibility());
            assertEquals(1f, caption.getTransitionAlpha(), 0f);
            assertEquals("示例字幕", caption.getText().toString());
            assertEquals(View.VISIBLE, caption.getVisibility());
            assertEquals(1f, progress.getTransitionAlpha(), 0f);
        }
    }

    @Test public void realPreDrawRestoresWhenCoverageIsLostAndSameBindingCanReacquire() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            f.covered = false;
            assertFalse(f.card.getViewTreeObserver().dispatchOnPreDraw());
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);

            f.covered = true;
            f.sync();
            assertEquals(0f, top.getTransitionAlpha(), 0f);
            f.lease.suspend();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            f.sync();
            assertEquals(0f, top.getTransitionAlpha(), 0f);
        }
    }

    @Test public void duplicateNativeBitmapTopRestoresBothCandidates() {
        try (Fixture f = new Fixture()) {
            ImageView first = f.leaseTranslatedTop();
            ImageView second = f.band(Gravity.TOP);
            f.layout();
            f.card.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, first.getTransitionAlpha(), 0f);
            assertEquals(1f, second.getTransitionAlpha(), 0f);
            f.sync();
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
            assertEquals(1f, first.getTransitionAlpha(), 0f);
        }
    }

    @Test public void drawableReplacementInvalidatesOnlyThePriorPaintLease() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            BitmapDrawable replacement = new BitmapDrawable(f.activity.getResources(), f.bitmap);
            top.setImageDrawable(replacement);
            f.card.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            assertSame(replacement, top.getDrawable());
            f.sync();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
        }
    }

    @Test public void horizontalShiftAndLegacyAnimationRestoreAndRemainHostOwned() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            top.setTranslationX(2f);
            f.card.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            assertEquals(2f, top.getTranslationX(), 0f);

            top.setTranslationX(0f);
            f.sync();
            assertEquals(0f, top.getTransitionAlpha(), 0f);
            AlphaAnimation animation = new AlphaAnimation(1f, .8f);
            top.setAnimation(animation);
            f.card.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            assertSame(animation, top.getAnimation());
        }
    }

    @Test public void detachingOrClearingBindingRequiresNewUntranslatedProof() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            FrameLayout.LayoutParams layout = (FrameLayout.LayoutParams) top.getLayoutParams();
            f.colours.removeView(top);
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            f.colours.addView(top, layout);
            f.layout();
            f.sync();
            assertEquals(1f, top.getTransitionAlpha(), 0f);

            top.setTranslationY(0f);
            f.sync();
            top.setTranslationY(1.5f);
            f.sync();
            assertEquals(0f, top.getTransitionAlpha(), 0f);
            f.lease.clearTopBinding();
            f.sync();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
        }
    }

    @Test public void releasingTranslatedTopPreservesNewerHostOpacity() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            top.setTransitionAlpha(.55f);
            f.lease.releaseTranslatedTop();
            assertEquals(.55f, top.getTransitionAlpha(), 0f);
            f.lease.restore();
            assertEquals(.55f, top.getTransitionAlpha(), 0f);
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
        }
    }

    @Test public void outgoingReleaseRestoresLocalizedPaintBeforeTheCardLeavesScreen() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.leaseTranslatedTop();
            f.lease.releaseTranslatedTop();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
            f.card.getViewTreeObserver().dispatchOnPreDraw();
            assertEquals(1f, top.getTransitionAlpha(), 0f);
        }
    }

    @Test public void otherDrawableRetainsNormalContainerHandlingButCannotProvideTranslatedProof() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.band(Gravity.TOP);
            top.setImageDrawable(new ColorDrawable(0xff123456));
            f.layout();
            f.sync();
            assertEquals(0f, f.colours.getTransitionAlpha(), 0f);
            top.setTranslationY(1.5f);
            f.sync();
            assertEquals(1f, f.colours.getTransitionAlpha(), 0f);
            assertEquals(1f, top.getTransitionAlpha(), 0f);
        }
    }

    @Test @Config(sdk = 28) public void api28DoesNotEnterTheCompositorLease() {
        try (Fixture f = new Fixture()) {
            ImageView top = f.band(Gravity.TOP);
            top.setAlpha(.8f);
            f.layout();
            assertFalse(f.lease.sync(f.card, 270, true, () -> {
                fail("API 28 must not consult translated coverage");
                return true;
            }));
            f.lease.suspend();
            f.lease.clearTopBinding();
            f.lease.releaseTranslatedTop();
            f.lease.restore();
            assertEquals(.8f, top.getAlpha(), 0f);
            assertEquals(1f, f.colours.getAlpha(), 0f);
            assertEquals(View.VISIBLE, f.colours.getVisibility());
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Activity activity = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final FrameLayout card = new FrameLayout(activity);
        final MeasureOnceCheckDrawChildRelativeLayout overlay = new MeasureOnceCheckDrawChildRelativeLayout(activity);
        final FrameLayout colours = new FrameLayout(activity);
        final Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        final FeedColourBandLease lease = new FeedColourBandLease();
        boolean covered = true;

        Fixture() {
            overlay.setId(0x7f0a3ffa);
            colours.setId(0x7f0a3fac);
            overlay.addView(colours, new RelativeLayout.LayoutParams(270, 600));
            card.addView(overlay, new FrameLayout.LayoutParams(270, 600));
            activity.setContentView(card);
        }

        ImageView band(int edge) {
            ImageView band = new ImageView(activity);
            band.setImageDrawable(new BitmapDrawable(activity.getResources(), bitmap));
            band.setScaleType(ImageView.ScaleType.CENTER_CROP);
            colours.addView(band, new FrameLayout.LayoutParams(270, 70, edge | Gravity.CENTER_HORIZONTAL));
            return band;
        }

        ImageView leaseTranslatedTop() {
            ImageView top = band(Gravity.TOP);
            layout();
            sync();
            top.setTranslationY(1.5f);
            sync();
            assertEquals(1f, colours.getTransitionAlpha(), 0f);
            assertEquals(0f, top.getTransitionAlpha(), 0f);
            return top;
        }

        void layout() {
            card.measure(View.MeasureSpec.makeMeasureSpec(270, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
            card.layout(0, 0, 270, 600);
        }

        void sync() { lease.sync(card, 270, true, () -> covered); }

        @Override public void close() {
            lease.restore();
            activity.finish();
        }
    }
}
