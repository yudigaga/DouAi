package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Insets;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.widget.FrameLayout;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module", qualifiers = "w296dp-h560dp-mdpi")
public final class CommentRingOverlayTest {
    private Activity activity;
    private CommentRingOverlay overlay;
    private AtomicInteger clicks;
    private AtomicInteger cancellations;

    @Before public void setup() {
        RuntimeEnvironment.getApplication().getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE)
                .edit().clear().commit();
        activity = Robolectric.buildActivity(Activity.class).setup().windowFocusChanged(true).get();
        clicks = new AtomicInteger();
        cancellations = new AtomicInteger();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
    }

    @After public void cleanup() {
        overlay.detach();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void ringAppearsImmediatelyAndStateUpdatesDoNotResizeIt() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        assertSame(ring, activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        assertEquals(3, ring.getQueueCount());
        assertEquals(2, ring.getRetriesLeft());
        assertEquals("generating", ring.getPhase());
        overlay.show(activity, 10000, 0, "publishing", -1);
        layout();
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        assertFalse(ring.getContentDescription().toString().contains("评论内容"));
        assertTrue(ring.getContentDescription().toString().contains("发布中"));
    }

    @Test public void repeatedShowRestoresAReplyRingRemovedByHostFromTheSameDecor() {
        CommentRingOverlay reply = new CommentRingOverlay(activity, clicks::incrementAndGet,
                cancellations::incrementAndGet, true);
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            reply.show(activity, 1, 0, "generating", -1);
            CommentRingView ring = reply.view();
            assertSame(decor, ring.getParent());
            assertTrue(ring.isAttachedToWindow());
            assertTrue(ring.isShown());

            decor.removeView(ring);
            assertNull(ring.getParent());
            assertFalse(ring.isAttachedToWindow());

            reply.show(activity, 1, 0, "generating", -1);

            assertSame("The same live Activity must regain its reply ring after host removal", decor, ring.getParent());
            assertTrue(ring.isAttachedToWindow());
            assertTrue(ring.isShown());
            assertEquals("generating", ring.getPhase());
            assertTrue(ring.performClick());
            assertEquals(1, clicks.get());
        } finally { reply.detach(); }
    }

    @Test public void repeatedShowReclaimsAReplyRingReparentedOutsideTheWindow() {
        CommentRingOverlay reply = new CommentRingOverlay(activity, clicks::incrementAndGet,
                cancellations::incrementAndGet, true);
        try {
            ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
            reply.show(activity, 1, 0, "generating", -1);
            CommentRingView ring = reply.view();
            assertSame(decor, ring.getParent());
            decor.removeView(ring);
            FrameLayout detachedHostContainer = new FrameLayout(activity);
            detachedHostContainer.addView(ring);
            assertSame(detachedHostContainer, ring.getParent());
            assertFalse(ring.isAttachedToWindow());

            reply.show(activity, 1, 0, "generating", -1);

            assertSame("A host container outside the window must not retain the reply ring", decor, ring.getParent());
            assertEquals(0, detachedHostContainer.getChildCount());
            assertTrue(ring.isAttachedToWindow());
            assertTrue(ring.isShown());
            assertTrue(ring.performLongClick());
            assertEquals(1, cancellations.get());
        } finally { reply.detach(); }
    }

    @Test public void independentReplyPositionAndBlueStyleDoNotChangeTheSavedCommentPosition() {
        CommentRingOverlay reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
        overlay.avoid(reply);
        reply.avoid(overlay);
        try {
            show("preview", .8f);
            reply.show(activity, 2, 8, "preview", .8f);
            layout();
            float commentX = overlay.view().getX(), commentY = overlay.view().getY();
            CommentRingView ring = reply.view();
            assertEquals("automation.reply.ring", ring.getTag());
            assertEquals(CommentRingView.REPLY_COLOR, ring.getPrimaryColor());
            assertEquals(CommentRingView.COMMENT_COLOR, overlay.view().getPrimaryColor());
            assertEquals("回复", ring.getLabel());
            assertEquals("评论", overlay.view().getLabel());
            long now = SystemClock.uptimeMillis();
            MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 16, 16, 0);
            MotionEvent move = MotionEvent.obtain(now, now + 50, MotionEvent.ACTION_MOVE, 86, 66, 0);
            MotionEvent up = MotionEvent.obtain(now, now + 100, MotionEvent.ACTION_UP, 86, 66, 0);
            try { ring.dispatchTouchEvent(down); ring.dispatchTouchEvent(move); ring.dispatchTouchEvent(up); }
            finally { down.recycle(); move.recycle(); up.recycle(); }
            float savedX = ring.getX(), savedY = ring.getY();
            assertEquals(commentX, overlay.view().getX(), .1f);
            assertEquals(commentY, overlay.view().getY(), .1f);
            assertTrue(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("reply_ring_x"));
            assertFalse(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("comment_ring_x"));
            reply.detach();
            reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
            overlay.avoid(reply);
            reply.avoid(overlay);
            reply.show(activity, 1, 0, "publishing", -1);
            layout();
            assertEquals(savedX, reply.view().getX(), .1f);
            assertEquals(savedY, reply.view().getY(), .1f);
        } finally { reply.detach(); }
    }

    @Test public void ringsAvoidOverlapEvenWhenTheirSavedPositionsAreIdentical() {
        overlay.detach();
        activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).edit()
                .putFloat("comment_ring_x", .2f).putFloat("comment_ring_y", .35f)
                .putFloat("reply_ring_x", .2f).putFloat("reply_ring_y", .35f).commit();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        CommentRingOverlay reply = new CommentRingOverlay(activity, () -> { }, () -> { }, true);
        overlay.avoid(reply);
        reply.avoid(overlay);
        try {
            show("preview", .8f);
            reply.show(activity, 1, 8, "preview", .8f);
            layout();
            CommentRingView first = overlay.view(), second = reply.view();
            assertTrue(Math.abs(first.getX() - second.getX()) >= first.getWidth()
                    || Math.abs(first.getY() - second.getY()) >= first.getHeight());
        } finally { reply.detach(); }
    }

    @Test public void tapOpensDetailsAndTouchDoesNotReachHost() {
        show("preview", .8f);
        AtomicInteger hostTouches = new AtomicInteger();
        View host = new View(activity);
        host.setOnTouchListener((view, event) -> { hostTouches.incrementAndGet(); return true; });
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(host);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(1, clicks.get());
        assertEquals(0, cancellations.get());
        assertEquals(0, hostTouches.get());
    }

    @Test public void longPressCancelsOnceAndReleaseDoesNotOpenDetails() {
        show("generating", -1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        assertEquals("Long-press is committed on release so a late drag can still start", 0, cancellations.get());
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(1, cancellations.get());
        assertEquals(0, clicks.get());
    }

    @Test public void movementAfterHoldDelayContinuesIntoDragInsteadOfCancelling() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        float oldX = ring.getX();
        float oldY = ring.getY();
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_MOVE, 76, 54);
        gesture(MotionEvent.ACTION_UP, 76, 54);
        assertEquals(oldX + 60, ring.getX(), .1f);
        assertEquals(oldY + 38, ring.getY(), .1f);
        assertEquals(0, cancellations.get());
        assertEquals(0, clicks.get());
    }

    @Test public void transparentTouchPaddingIsSmallAndDoesNotBlockTheRestOfTheVideo() {
        AtomicInteger hostTouches = new AtomicInteger();
        View host = new View(activity);
        host.setOnTouchListener((view, event) -> { hostTouches.incrementAndGet(); return true; });
        ((FrameLayout) activity.findViewById(android.R.id.content)).addView(host,
                new FrameLayout.LayoutParams(-1, -1));
        show("preview", .8f);
        View decor = activity.getWindow().getDecorView();
        float left = overlay.view().getX();
        float top = overlay.view().getY();
        parentGesture(decor, MotionEvent.ACTION_DOWN, left + 2, top + 16);
        parentGesture(decor, MotionEvent.ACTION_UP, left + 2, top + 16);
        assertEquals(1, clicks.get());
        assertEquals(0, hostTouches.get());
        parentGesture(decor, MotionEvent.ACTION_DOWN, left + overlay.view().getWidth() + 2, top + 16);
        parentGesture(decor, MotionEvent.ACTION_UP, left + overlay.view().getWidth() + 2, top + 16);
        assertEquals(1, clicks.get());
        assertEquals(2, hostTouches.get());
    }

    @Test public void draggingMovesFreelySuppressesActionsAndRestoresPosition() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        float oldX = ring.getX();
        float oldY = ring.getY();
        gesture(MotionEvent.ACTION_DOWN, 20, 20);
        gesture(MotionEvent.ACTION_MOVE, 102, 64);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_UP, 102, 64);
        assertEquals(oldX + 82, ring.getX(), .1f);
        assertEquals(oldY + 44, ring.getY(), .1f);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
        float savedX = ring.getX();
        float savedY = ring.getY();
        overlay.detach();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        show("retrying", -1);
        assertEquals(savedX, overlay.view().getX(), .1f);
        assertEquals(savedY, overlay.view().getY(), .1f);
    }

    @Test public void decorDispatchedDragMovesRingAndStillSuppressesClickAndHold() {
        show("generating", -1);
        View decor = activity.getWindow().getDecorView();
        float oldX = overlay.view().getX();
        float oldY = overlay.view().getY();
        parentGesture(decor, MotionEvent.ACTION_DOWN, oldX + 16, oldY + 16);
        parentGesture(decor, MotionEvent.ACTION_MOVE, oldX + 96, oldY + 64);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        parentGesture(decor, MotionEvent.ACTION_UP, oldX + 96, oldY + 64);
        assertEquals(oldX + 80, overlay.view().getX(), .1f);
        assertEquals(oldY + 48, overlay.view().getY(), .1f);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test public void savedPositionIsNormalizedAcrossRotationAndClampedInsideInsets() {
        show("waiting", 1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_MOVE, 1000, 1000);
        gesture(MotionEvent.ACTION_UP, 1000, 1000);
        overlay.detach();
        layout(640, 360);
        overlay.show(activity, 1, 0, "failed", 0);
        layout(640, 360);
        WindowInsets insets = new WindowInsets.Builder()
                .setInsets(WindowInsets.Type.systemBars(), Insets.of(28, 18, 32, 24)).build();
        overlay.view().dispatchApplyWindowInsets(insets);
        assertEquals(640 - 32 - 12 - 32, overlay.view().getX(), .1f);
        assertEquals(360 - 24 - 12 - 32, overlay.view().getY(), .1f);
        assertTrue(overlay.view().getX() >= 28 + 12);
        assertTrue(overlay.view().getY() >= 18 + 12);
    }

    @Test public void invalidStoredCoordinatesCannotPutRingOutsideTheScreen() {
        overlay.detach();
        activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).edit()
                .putFloat("comment_ring_x", Float.NaN).putFloat("comment_ring_y", 20).commit();
        overlay = new CommentRingOverlay(activity, clicks::incrementAndGet, cancellations::incrementAndGet);
        show("waiting", 1);
        assertEquals(12, overlay.view().getX(), .1f);
        assertEquals(560 - 12 - 32, overlay.view().getY(), .1f);
    }

    @Test public void detachingDuringPressRemovesPendingCancellationAndListeners() {
        show("generating", -1);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        CommentRingView ring = overlay.view();
        overlay.detach();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(2));
        assertNull(ring.getParent());
        assertFalse(ring.performClick());
        assertFalse(ring.performLongClick());
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test public void cancelledDragAndMultitouchDoNotActivateActions() {
        show("generating", -1);
        float before = overlay.view().getX();
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_MOVE, 90, 70);
        gesture(MotionEvent.ACTION_CANCEL, 90, 70);
        assertEquals(before, overlay.view().getX(), .1f);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_POINTER_DOWN, 16, 16);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(ViewConfiguration.getLongPressTimeout() + 1));
        gesture(MotionEvent.ACTION_UP, 16, 16);
        assertEquals(0, clicks.get());
        assertEquals(0, cancellations.get());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeNarrowPreviewHasLabelledCompactBoundsAndReadableContrast() { renderPreview(); }

    @Test @Config(qualifiers = "w640dp-h360dp-land-mdpi") @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void nativeLandscapePreviewKeepsTheBadgeWithinScreen() { renderPreview(); }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void originalSizeReplyAndCommentUseDifferentShapesIconsAndColours() {
        Bitmap[] images = new Bitmap[2];
        CommentRingView[] badges = {new CommentRingView(activity, "reply", "自动回复", CommentRingView.REPLY_COLOR, true),
                new CommentRingView(activity)};
        for (int i = 0; i < badges.length; i++) {
            badges[i].setState(2, 3, "preview", .7f);
            badges[i].measure(View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
            badges[i].layout(0, 0, 32, 32);
            images[i] = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
            badges[i].draw(new Canvas(images[i]));
        }
        assertTrue("Comment has a rounded-square corner; reply remains circular",
                Color.alpha(images[1].getPixel(9, 9)) > Color.alpha(images[0].getPixel(9, 9)));
        int blueStroke = 0, amberStroke = 0;
        // Tiny diagonal strokes are antialiased; inspect their interior region instead of one pixel.
        for (int y = 9; y <= 15; y++) for (int x = 10; x <= 16; x++) {
            int bubble = images[0].getPixel(x, y), pencil = images[1].getPixel(x, y);
            if (Color.blue(bubble) > Color.red(bubble) + 20) blueStroke++;
            if (Color.red(pencil) > Color.blue(pencil) + 40) amberStroke++;
        }
        assertTrue("Reply bubble uses its cool blue outline", blueStroke > 3);
        assertTrue("Comment pencil uses its warm amber stroke", amberStroke > 3);
        assertTrue(badges[0].getContentDescription().toString().contains("自动回复"));
        assertTrue(badges[1].getContentDescription().toString().contains("自动评论"));
        images[0].recycle(); images[1].recycle();
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void largeFontAndLargeQueueKeepTextInsideTheFixedBadgeBounds() {
        android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 2;
        CommentRingView ring = new CommentRingView(activity.createConfigurationContext(configuration));
        ring.setState(10000, 100, "preview", 1);
        ring.measure(View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
        ring.layout(0, 0, 32, 32);
        assertEquals(32, ring.getWidth());
        assertEquals(32, ring.getHeight());
        Bitmap bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(bitmap));
        String previewFolder = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (previewFolder != null) save(bitmap, new File(previewFolder, "comment-ring-large-font.png"));
        int textPixels = 0;
        for (int y = 13; y <= 21; y++) for (int x = 12; x < 23; x++) {
            int color = bitmap.getPixel(x, y);
            if (Color.alpha(color) > 180 && Color.red(color) < 150 && Color.green(color) < 150) textPixels++;
        }
        assertTrue("Readable queue count", textPixels > 5);
        assertEquals(0, Color.alpha(bitmap.getPixel(0, 0)));
        assertEquals(0, Color.alpha(bitmap.getPixel(31, 31)));
        bitmap.recycle();
    }

    @Test public void idleFadeAndMicroShiftNeverRewriteTheUserPosition() {
        show("generating", -1);
        CommentRingView ring = overlay.view();
        assertTrue(ring.hasWindowFocus());
        float x = ring.getX(), y = ring.getY();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(CommentRingOverlay.IDLE_FADE_MILLIS + 1));
        assertEquals(CommentRingOverlay.IDLE_ALPHA, ring.getAlpha(), .01f);
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(CommentRingOverlay.PIXEL_SHIFT_MILLIS));
        assertTrue(Math.abs(ring.getX() - x) <= 2.1f);
        assertTrue(Math.abs(ring.getY() - y) <= 2.1f);
        assertTrue("A stable badge moves a few pixels", Math.abs(ring.getX() - x) + Math.abs(ring.getY() - y) > .5f);
        assertFalse(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("comment_ring_x"));
        assertFalse(activity.getSharedPreferences("douai_automation_ui", Context.MODE_PRIVATE).contains("comment_ring_y"));
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        assertEquals(1, ring.getAlpha(), .01f);
        float touchedX = ring.getX(), touchedY = ring.getY();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(CommentRingOverlay.PIXEL_SHIFT_MILLIS + 1000));
        assertEquals(touchedX, ring.getX(), .01f);
        assertEquals(touchedY, ring.getY(), .01f);
        assertEquals(1, ring.getAlpha(), .01f);
        gesture(MotionEvent.ACTION_CANCEL, 16, 16);
    }

    @Test public void detailsPausePassiveEffectsAndImeBoundsKeepTheBadgeAboveTheKeyboard() {
        show("details", -1);
        float x = overlay.view().getX(), y = overlay.view().getY();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(CommentRingOverlay.PIXEL_SHIFT_MILLIS + 1000));
        assertEquals(x, overlay.view().getX(), .01f);
        assertEquals(y, overlay.view().getY(), .01f);
        assertEquals(1, overlay.view().getAlpha(), .01f);
        gesture(MotionEvent.ACTION_DOWN, 16, 16);
        gesture(MotionEvent.ACTION_MOVE, 1000, 1000);
        gesture(MotionEvent.ACTION_UP, 1000, 1000);
        WindowInsets insets = new WindowInsets.Builder().setInsets(WindowInsets.Type.ime(), Insets.of(0, 0, 0, 200))
                .setVisible(WindowInsets.Type.ime(), true).build();
        overlay.view().dispatchApplyWindowInsets(insets);
        assertTrue(overlay.view().getY() + overlay.view().getHeight() <= 560 - 200 - 12);
        overlay.detach();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(CommentRingOverlay.PIXEL_SHIFT_MILLIS + 1000));
        assertNull(overlay.view().getParent());
        assertEquals(1, overlay.view().getAlpha(), .01f);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void exportsActualCanvasLightAndDarkBadgePreviews() {
        String output = System.getenv("DOUAI_OVERLAY_PREVIEW_DIR");
        for (boolean dark : new boolean[]{false, true}) {
            RuntimeEnvironment.setQualifiers(dark ? "+night" : "+notnight");
            android.content.res.Configuration configuration = new android.content.res.Configuration(activity.getResources().getConfiguration());
            configuration.uiMode = (configuration.uiMode & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                    | (dark ? android.content.res.Configuration.UI_MODE_NIGHT_YES : android.content.res.Configuration.UI_MODE_NIGHT_NO);
            Context themed = activity.createConfigurationContext(configuration);
            Bitmap board = Bitmap.createBitmap(720, 480, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(board);
            canvas.drawColor(dark ? Color.rgb(18, 22, 28) : Color.rgb(242, 244, 247));
            android.graphics.Paint heading = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            heading.setColor(dark ? Color.rgb(222, 228, 235) : Color.rgb(44, 53, 66));
            heading.setTextSize(18);
            canvas.drawText("原尺寸 1× · 18dp 主体 / 32×32dp 触摸区", 28, 30, heading);
            drawPreviewBadge(canvas, themed, true, false, 40, 55, 1);
            drawPreviewBadge(canvas, themed, false, false, 380, 55, 1);
            canvas.drawText("回复", 88, 78, heading);
            canvas.drawText("评论", 428, 78, heading);
            canvas.drawText("4× 放大（仅便于查看，实际不会放大）", 28, 132, heading);
            drawPreviewBadge(canvas, themed, true, false, 40, 150, 4);
            drawPreviewBadge(canvas, themed, false, false, 380, 150, 4);
            canvas.drawText("4× 放大 · 闲置降亮，微移不改变拖动位置", 28, 307, heading);
            drawPreviewBadge(canvas, themed, true, true, 40, 325, 4);
            drawPreviewBadge(canvas, themed, false, true, 380, 325, 4);
            assertNotEquals("Original-size icon paints above the page", board.getPixel(0, 0), board.getPixel(56, 71));
            assertNotEquals("Active icon paints above the page", board.getPixel(0, 0), board.getPixel(104, 214));
            assertNotEquals("Idle icon remains visible", board.getPixel(0, 0), board.getPixel(104, 389));
            if (output != null && !output.trim().isEmpty()) {
                save(board, new File(output, "progress-icons-original-size-" + (dark ? "dark" : "light") + ".png"));
            }
            board.recycle();
        }
        RuntimeEnvironment.setQualifiers("+notnight");
    }

    private static void drawPreviewBadge(Canvas canvas, Context context, boolean reply, boolean idle, int x, int y, int scale) {
        CommentRingView badge = reply ? new CommentRingView(context, "reply", "自动回复", CommentRingView.REPLY_COLOR, true)
                : new CommentRingView(context);
        badge.setState(reply ? 2 : 3, 3, idle ? "speech" : "preview", idle ? -1 : .7f);
        badge.setAmbient(idle);
        badge.measure(View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(32, View.MeasureSpec.EXACTLY));
        badge.layout(0, 0, 32, 32);
        canvas.save(); canvas.translate(x, y); canvas.scale(scale, scale);
        if (idle) canvas.saveLayerAlpha(0, 0, 32, 32, Math.round(255 * CommentRingOverlay.IDLE_ALPHA));
        badge.draw(canvas);
        if (idle) canvas.restore();
        canvas.restore();
    }

    private void renderPreview() {
        View decor = activity.getWindow().getDecorView();
        decor.setBackgroundColor(Color.rgb(45, 54, 57));
        show("preview", .72f);
        CommentRingView ring = overlay.view();
        Bitmap isolated = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888);
        ring.draw(new Canvas(isolated));
        assertEquals(0, Color.alpha(isolated.getPixel(0, 0)));
        assertTrue(Color.alpha(isolated.getPixel(16, 16)) > 220);
        int minPainted = 32;
        int maxPainted = -1;
        int textPixels = 0;
        int accentPixels = 0;
        for (int y = 0; y < 32; y++) for (int x = 0; x < 32; x++) {
            int color = isolated.getPixel(x, y);
            if (Color.alpha(color) != 0) { minPainted = Math.min(minPainted, x); maxPainted = Math.max(maxPainted, x); }
            if (Color.alpha(color) > 180 && Color.red(color) < 150 && Color.green(color) < 150) textPixels++;
            if (Color.red(color) > Color.blue(color) + 50 && Color.red(color) > Color.green(color) + 25) accentPixels++;
        }
        assertTrue("Original 18dp visible bounds", maxPainted - minPainted + 1 >= 16 && maxPainted - minPainted + 1 <= 18);
        assertTrue("Readable task count", textPixels > 5);
        assertTrue("Warm comment accent should render", accentPixels > 8);
        isolated.recycle();
        assertTrue(ring.getX() >= 0 && ring.getY() >= 0);
        assertTrue(ring.getX() + ring.getWidth() <= decor.getWidth());
        assertTrue(ring.getY() + ring.getHeight() <= decor.getHeight());
        String output = System.getenv("DOUAI_CHAT_SCREENSHOTS");
        if (output == null) return;
        File folder = new File(output);
        assertTrue(folder.isDirectory() || folder.mkdirs());
        Bitmap preview = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        decor.draw(new Canvas(preview));
        save(preview, new File(folder, "comment-ring-" + decor.getWidth() + "x" + decor.getHeight() + ".png"));
        preview.recycle();
        Bitmap strip = Bitmap.createBitmap(192, 48, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(strip);
        canvas.drawColor(Color.rgb(45, 54, 57));
        String[] phases = {"waiting", "generating", "preview", "retrying", "failed"};
        for (int i = 0; i < phases.length; i++) {
            ring.setState(3, Math.max(0, 2 - i / 2), phases[i], i == 2 ? .72f : -1);
            canvas.save();
            canvas.translate(i * 36 + 8, 8);
            ring.draw(canvas);
            canvas.restore();
        }
        save(strip, new File(folder, "comment-ring-states.png"));
        strip.recycle();
    }

    private static void save(Bitmap bitmap, File file) {
        File parent = file.getParentFile();
        assertTrue(parent.isDirectory() || parent.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(file)) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        } catch (IOException failure) { throw new AssertionError(failure); }
    }

    private void show(String phase, float progress) {
        layout();
        overlay.show(activity, 3, 2, phase, progress);
        layout();
        ShadowLooper.idleMainLooper();
        layout();
    }

    private void layout() {
        android.util.DisplayMetrics metrics = activity.getResources().getDisplayMetrics();
        layout(metrics.widthPixels, metrics.heightPixels);
    }

    private void layout(int width, int height) {
        View decor = activity.getWindow().getDecorView();
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, width, height);
    }

    private void gesture(int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        assertTrue(overlay.view().dispatchTouchEvent(event));
        event.recycle();
    }

    private static void parentGesture(View parent, int action, float x, float y) {
        long now = SystemClock.uptimeMillis();
        MotionEvent event = MotionEvent.obtain(now, now, action, x, y, 0);
        assertTrue(parent.dispatchTouchEvent(event));
        event.recycle();
    }
}
