package com.douai.module.ui;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

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
        LinearLayout content = new LinearLayout(context);
        content.setTag("ai.chat.image_preview");
        content.setOrientation(LinearLayout.VERTICAL);
        content.setBackgroundColor(Color.BLACK);

        Button close = new Button(context);
        close.setTag("ai.chat.image_preview_close");
        close.setText("关闭");
        close.setOnClickListener(ignored -> dismiss());
        LinearLayout.LayoutParams closeParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        closeParams.gravity = Gravity.END;
        content.addView(close, closeParams);

        FrameLayout viewport = new FrameLayout(context);
        content.addView(viewport, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        picture = new ZoomImageView(context);
        picture.setTag("ai.chat.image_preview_image");
        picture.setContentDescription("图片预览，双击放大或缩小");
        viewport.addView(picture, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        status = new TextView(context);
        status.setTag("ai.chat.image_preview_status");
        status.setText("正在读取图片…");
        status.setTextColor(Color.WHITE);
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        viewport.addView(status, new FrameLayout.LayoutParams(
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
        private final Matrix transform = new Matrix();
        private final GestureDetector gestures;
        private float fitScale;
        private float zoom = 1f;
        private float left;
        private float top;

        ZoomImageView(Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override public boolean onDown(MotionEvent event) { return true; }

                @Override public boolean onDoubleTap(MotionEvent event) {
                    if (zoom > 1f) fit();
                    else {
                        zoom = ENLARGEMENT;
                        left = event.getX() - (event.getX() - left) * zoom;
                        top = event.getY() - (event.getY() - top) * zoom;
                        updateTransform();
                    }
                    return true;
                }

                @Override public boolean onScroll(MotionEvent start, MotionEvent end, float dx, float dy) {
                    if (zoom == 1f) return false;
                    left -= dx;
                    top -= dy;
                    updateTransform();
                    return true;
                }
            });
        }

        @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
            super.onSizeChanged(width, height, oldWidth, oldHeight);
            fit();
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            return getDrawable() != null && gestures.onTouchEvent(event);
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
