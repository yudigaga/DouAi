package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;

/** A read-only view over the image already available to the local conversation. */
final class AiChatImagePreview extends Dialog {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ChatImage image;
    private final ExecutorService worker;
    private final ZoomImageView picture;
    private final TextView status;
    private Future<?> load;
    private boolean closed;

    AiChatImagePreview(Activity owner, ChatImage image, ExecutorService worker) {
        super(ModuleTheme.themedContext(owner), android.R.style.Theme_Material_NoActionBar);
        this.image = image;
        this.worker = worker;
        setOwnerActivity(owner);
        setCanceledOnTouchOutside(false);

        Context context = getContext();
        FrameLayout content = new FrameLayout(context);
        content.setTag("ai.chat.image_preview");
        content.setBackgroundColor(Color.BLACK);
        picture = new ZoomImageView(context);
        picture.setTag("ai.chat.image_preview_image");
        picture.setContentDescription("图片预览，双指缩放，双击放大或缩小，单击空白关闭");
        picture.setOnClickListener(ignored -> dismiss());
        content.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setTag("ai.chat.image_preview_status");
        status.setText("正在读取图片…");
        status.setTextColor(Color.WHITE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(status, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        setContentView(content);
        Window window = getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
    }

    @Override protected void onStart() {
        super.onStart();
        Window window = getWindow();
        if (window != null) window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        if (closed || load != null) return;
        try {
            load = worker.submit(() -> {
                Bitmap bitmap;
                try { bitmap = ChatImageLoader.localPreview(image); }
                catch (RuntimeException | OutOfMemoryError unavailable) { bitmap = null; }
                Bitmap decoded = bitmap;
                main.post(() -> display(decoded));
            });
        } catch (RejectedExecutionException unavailable) {
            main.post(() -> display(null));
        }
    }

    private void display(Bitmap bitmap) {
        if (closed || !isShowing()) {
            if (bitmap != null) bitmap.recycle();
        } else if (bitmap == null) {
            status.setText("图片无法读取");
        } else {
            picture.setImageBitmap(bitmap);
            picture.fit();
            status.setVisibility(View.GONE);
        }
    }

    @Override public void dismiss() {
        if (!closed) {
            closed = true;
            if (load != null) load.cancel(true);
            load = null;
            picture.setImageDrawable(null);
        }
        super.dismiss();
    }

    private static final class ZoomImageView extends ImageView {
        private static final float ENLARGEMENT = 2.5f;
        private static final float MAX_ZOOM = 4f;
        private final Matrix transform = new Matrix();
        private final RectF imageBounds = new RectF();
        private final GestureDetector gestures;
        private final ScaleGestureDetector scaleGestures;
        private final int touchSlopSquared;
        private float fitScale;
        private float zoom = 1f;
        private float left;
        private float top;
        private float downX;
        private float downY;
        private boolean downInBlank;
        private boolean moved;
        private boolean multiTouch;
        private long blankTapTime = -1;
        private BooleanSupplier pendingClick;

        ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            int touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
            touchSlopSquared = touchSlop * touchSlop;
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }

                @Override public boolean onSingleTapUp(MotionEvent event) {
                    if (pendingClick != null) {
                        blankTapTime = event.getDownTime();
                    }
                    return true;
                }

                @Override public boolean onSingleTapConfirmed(MotionEvent event) {
                    if (multiTouch || blankTapTime != event.getDownTime()) return false;
                    BooleanSupplier click = pendingClick;
                    clearPendingTap();
                    return click != null && click.getAsBoolean();
                }

                @Override public boolean onDoubleTap(MotionEvent event) { return true; }

                @Override public boolean onDoubleTapEvent(MotionEvent event) {
                    // The second tap can turn into a pinch. Wait for its single-finger release.
                    if (event.getActionMasked() == MotionEvent.ACTION_UP && !moved && canScale()) {
                        if (zoom > 1f) fit();
                        else zoomAround(ENLARGEMENT, downX, downY, downX, downY);
                    }
                    return true;
                }

                @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                    moved = true;
                    if (zoom == 1f) return false;
                    left -= dx;
                    top -= dy;
                    updateTransform();
                    return true;
                }
            });
            scaleGestures = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                private float previousX;
                private float previousY;

                @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                    if (!canScale()) return false;
                    previousX = detector.getFocusX();
                    previousY = detector.getFocusY();
                    return true;
                }

                @Override public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    if (!Float.isFinite(factor) || factor <= 0f) return false;
                    float focusX = detector.getFocusX();
                    float focusY = detector.getFocusY();
                    zoomAround(Math.max(1f, Math.min(MAX_ZOOM, zoom * factor)), previousX, previousY, focusX, focusY);
                    previousX = focusX;
                    previousY = focusY;
                    return true;
                }
            });
            scaleGestures.setQuickScaleEnabled(false);
            scaleGestures.setStylusScaleEnabled(false);
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            fit();
        }

        @Override public boolean performClick() {
            return super.performClick();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                multiTouch = false;
                moved = false;
                clearPendingTap();
                downX = event.getX();
                downY = event.getY();
                downInBlank = !imageContains(downX, downY);
            } else if (!multiTouch && event.getPointerCount() > 1) {
                multiTouch = true;
                clearPendingTap();
                MotionEvent cancel = MotionEvent.obtain(event);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                try { gestures.onTouchEvent(cancel); }
                finally { cancel.recycle(); }
            } else if (!multiTouch && (action == MotionEvent.ACTION_MOVE || action == MotionEvent.ACTION_UP)) {
                float dx = event.getX() - downX;
                float dy = event.getY() - downY;
                moved |= dx * dx + dy * dy > touchSlopSquared;
            } else if (action == MotionEvent.ACTION_CANCEL) {
                clearPendingTap();
            }
            scaleGestures.onTouchEvent(event);
            if (!multiTouch) {
                if (action == MotionEvent.ACTION_UP && downInBlank && !moved && !imageContains(event.getX(), event.getY())) {
                    // GestureDetector confirms this tap only after ruling out a double tap.
                    pendingClick = () -> performClick();
                }
                gestures.onTouchEvent(event);
            }
            return true;
        }

        private void clearPendingTap() {
            blankTapTime = -1;
            pendingClick = null;
        }

        private boolean canScale() { return getDrawable() != null && fitScale > 0f; }

        private boolean imageContains(float x, float y) {
            if (!canScale()) return false;
            imageBounds.set(getDrawable().getBounds());
            getImageMatrix().mapRect(imageBounds);
            return imageBounds.contains(x - getPaddingLeft(), y - getPaddingTop());
        }

        private void zoomAround(float next, float previousX, float previousY, float focusX, float focusY) {
            float ratio = next / zoom;
            left = focusX - (previousX - left) * ratio;
            top = focusY - (previousY - top) * ratio;
            zoom = next;
            updateTransform();
        }

        void fit() {
            Drawable drawable = getDrawable();
            if (drawable == null || getWidth() == 0 || getHeight() == 0) return;
            fitScale = Math.min((float) getWidth() / drawable.getIntrinsicWidth(),
                    (float) getHeight() / drawable.getIntrinsicHeight());
            zoom = 1f;
            left = 0f;
            top = 0f;
            updateTransform();
        }

        private void updateTransform() {
            Drawable drawable = getDrawable();
            if (drawable == null || fitScale == 0f) return;
            float scale = fitScale * zoom;
            left = constrainedOffset(left, drawable.getIntrinsicWidth() * scale, getWidth());
            top = constrainedOffset(top, drawable.getIntrinsicHeight() * scale, getHeight());
            transform.setScale(scale, scale);
            transform.postTranslate(left, top);
            setImageMatrix(transform);
        }

        private static float constrainedOffset(float offset, float content, int viewport) {
            return content <= viewport ? (viewport - content) / 2f : Math.min(0f, Math.max(viewport - content, offset));
        }
    }
}
