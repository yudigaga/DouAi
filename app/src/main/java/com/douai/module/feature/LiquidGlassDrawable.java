package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.ArrayDeque;
import java.util.ArrayList;

/** Draws the navigation background; host content, touch and accessibility stay on the anchor. */
public final class LiquidGlassDrawable extends Drawable implements AutoCloseable {
    private final View anchor;
    private final LiquidGlassBackdrop backdrop;
    private final float density;
    private final float originalOutlineAlpha;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final RectF shape = new RectF();
    private final RectF footprint = new RectF();
    private final Matrix anchorTransform = new Matrix();
    private final float[] matrixValues = new float[9];
    private final int[] offset = new int[2];
    private final Runnable resourcesChanged = this::clearSample;
    private Shader sampled;
    private Bitmap sampledBitmap;
    private boolean shaderUnavailable;
    private boolean closed;
    private float lightX, lightY;
    private int alpha = 255;
    private ColorFilter colorFilter;

    public LiquidGlassDrawable(View anchor, boolean ignoredFloating, LiquidGlassBackdrop backdrop) {
        this.anchor = java.util.Objects.requireNonNull(anchor);
        this.backdrop = java.util.Objects.requireNonNull(backdrop);
        density = anchor.getResources().getDisplayMetrics().density;
        Outline original = originalOutline(anchor.getBackground());
        originalOutlineAlpha = original.getAlpha();
        backdrop.addObserver(resourcesChanged);
    }

    public void setLight(float x, float y) {
        x = Float.isFinite(x) ? Math.max(-1, Math.min(1, x)) : 0;
        y = Float.isFinite(y) ? Math.max(-1, Math.min(1, y)) : 0;
        if (lightX == x && lightY == y) return;
        lightX = x; lightY = y;
        invalidateSelf();
    }

    @Override public void draw(Canvas canvas) {
        if (closed || getBounds().isEmpty() || alpha == 0) return;
        ArrayList<View> nodes = visibleNodes();
        shape.set(getBounds());
        float radius = Math.min(shape.width(), shape.height()) / 2f;
        boolean dark = darkMaterial(nodes);
        paint.setStyle(Paint.Style.FILL);
        paint.setColorFilter(colorFilter);
        paint.setAlpha(alpha);
        boolean refracted = false;
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && !shaderUnavailable) {
            Bitmap frame = backdrop.frame();
            if (frame != null && !frame.isRecycled()) {
                Rect source = backdrop.sourceBounds();
                Rect visible = backdrop.visibleBounds();
                backdrop.anchorOffset(anchor, offset);
                // Pixels outside the actual media must stay compatible, rather than stretching an edge.
                if (mapAnchor() && !source.isEmpty() && footprint.left >= visible.left && footprint.top >= visible.top
                        && footprint.right <= visible.right && footprint.bottom <= visible.bottom) {
                    try {
                        if (sampled == null) sampled = Api33.create(frame);
                        else if (sampledBitmap != frame) Api33.bind(sampled, frame);
                        sampledBitmap = frame;
                        Api33.update(sampled, shape, radius, source, matrixValues, density);
                        paint.setShader(sampled);
                        canvas.drawRoundRect(shape, radius, radius, paint);
                        refracted = true;
                    } catch (RuntimeException | LinkageError unavailable) {
                        shaderUnavailable = true; clearSample();
                    }
                }
            }
        }
        paint.setShader(null);
        int tint = refracted ? 48 : 96;
        paint.setColor(dark ? Color.argb(tint, 18, 22, 29) : Color.argb(tint, 247, 249, 252));
        paint.setAlpha(modulatedAlpha(Color.alpha(paint.getColor())));
        canvas.drawRoundRect(shape, radius, radius, paint);

        float dx = shape.width() * (.22f + .16f * lightX);
        float dy = shape.height() * (.15f + .18f * lightY);
        paint.setShader(new LinearGradient(shape.left + dx, shape.top + dy,
                shape.right - dx, shape.bottom - dy,
                new int[]{Color.argb(32, 255, 255, 255), Color.TRANSPARENT,
                        Color.argb(12, 255, 255, 255)}, new float[]{0, .48f, 1}, Shader.TileMode.CLAMP));
        paint.setAlpha(alpha);
        canvas.drawRoundRect(shape, radius, radius, paint);
        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(Math.max(1, .7f * density));
        paint.setColor(Color.argb(48, 255, 255, 255));
        paint.setAlpha(modulatedAlpha(Color.alpha(paint.getColor())));
        float inset = paint.getStrokeWidth() / 2;
        shape.inset(inset, inset);
        canvas.drawRoundRect(shape, Math.max(0, radius - inset), Math.max(0, radius - inset), paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColorFilter(null);
    }

    private ArrayList<View> visibleNodes() {
        ArrayList<View> nodes = new ArrayList<>();
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(anchor);
        int examined = 0;
        for (int depth = 0; depth <= 8 && !pending.isEmpty() && examined < 96; depth++) {
            int atDepth = pending.size();
            for (int i = 0; i < atDepth && examined < 96; i++) {
                View view = pending.removeFirst(); examined++;
                if (view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0) continue;
                nodes.add(view);
                if (view instanceof ViewGroup && depth < 8) {
                    ViewGroup group = (ViewGroup) view;
                    for (int child = 0; child < group.getChildCount() && pending.size() + examined < 96; child++) {
                        pending.addLast(group.getChildAt(child));
                    }
                }
            }
        }
        return nodes;
    }

    private boolean darkMaterial(ArrayList<View> nodes) {
        float foreground = 0, total = 0;
        for (View view : nodes) if (view instanceof TextView) {
            TextView text = (TextView) view;
            if (text.length() == 0) continue;
            int color = text.getCurrentTextColor();
            float width = text.getPaint().measureText(text.getText().toString());
            float height = text.getLineHeight() * Math.max(1, text.getLineCount());
            if (text.getWidth() > 0) width = Math.min(width,
                    Math.max(0, text.getWidth() - text.getCompoundPaddingLeft() - text.getCompoundPaddingRight()));
            if (text.getHeight() > 0) height = Math.min(height,
                    Math.max(0, text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom()));
            float opacity = 1;
            for (View current = view; current != anchor && current != null;
                    current = current.getParent() instanceof View ? (View) current.getParent() : null) opacity *= current.getAlpha();
            float weight = width * height * opacity * Color.alpha(color) / 255f;
            float brightness = Color.red(color) * .2126f + Color.green(color) * .7152f + Color.blue(color) * .0722f;
            foreground += (brightness > 160 ? 1 : -1) * weight;
            total += weight;
        }
        if (total > 0) return foreground > 0;
        return true;
    }

    private boolean mapAnchor() {
        if (Build.VERSION.SDK_INT < 33) return false;
        anchorTransform.reset();
        anchor.transformMatrixToGlobal(anchorTransform);
        anchorTransform.getValues(matrixValues);
        for (float value : matrixValues) if (!Float.isFinite(value)) return false;
        if (matrixValues[Matrix.MPERSP_2] <= .05f) return false;
        float globalX = matrixValues[Matrix.MTRANS_X] / matrixValues[Matrix.MPERSP_2];
        float globalY = matrixValues[Matrix.MTRANS_Y] / matrixValues[Matrix.MPERSP_2];
        // Left-multiply the full homography by the window offset; replacing tx/ty loses perspective.
        anchorTransform.postTranslate(offset[0] - globalX, offset[1] - globalY);
        anchorTransform.getValues(matrixValues);
        for (float value : matrixValues) if (!Float.isFinite(value)) return false;
        float margin = 3 * density;
        for (int corner = 0; corner < 4; corner++) {
            float x = (corner & 1) == 0 ? shape.left - margin : shape.right + margin;
            float y = (corner & 2) == 0 ? shape.top - margin : shape.bottom + margin;
            float w = matrixValues[6] * x + matrixValues[7] * y + matrixValues[8];
            if (!Float.isFinite(w) || w < .05f) return false;
        }
        anchorTransform.mapRect(footprint, shape);
        return Float.isFinite(footprint.left) && Float.isFinite(footprint.top)
                && Float.isFinite(footprint.right) && Float.isFinite(footprint.bottom) && !footprint.isEmpty();
    }

    private static Outline originalOutline(Drawable background) {
        Outline outline = new Outline();
        if (background != null) try { background.getOutline(outline); }
        catch (RuntimeException | LinkageError unavailable) { outline.setEmpty(); outline.setAlpha(0); }
        return outline;
    }

    private int modulatedAlpha(int original) { return (original * alpha + 127) / 255; }

    private void clearSample() {
        paint.setShader(null);
        if (sampled != null && Build.VERSION.SDK_INT >= 33) {
            try { Api33.detach(sampled); }
            catch (RuntimeException | LinkageError unavailable) { sampled = null; shaderUnavailable = true; }
        }
        sampledBitmap = null;
        invalidateSelf(); anchor.invalidate();
    }

    @Override public void setAlpha(int alpha) {
        this.alpha = Math.max(0, Math.min(255, alpha)); invalidateSelf();
    }
    @Override public int getAlpha() { return alpha; }
    @Override public void setColorFilter(ColorFilter filter) { colorFilter = filter; invalidateSelf(); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public void getOutline(Outline outline) {
        float half = Math.min(getBounds().width(), getBounds().height()) / 2f;
        outline.setRoundRect(getBounds(), half);
        outline.setAlpha(Math.min(originalOutlineAlpha, .45f));
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        backdrop.removeObserver(resourcesChanged);
        clearSample();
        sampled = null;
    }

    /** Isolated so older Android versions never resolve RuntimeShader while loading the drawable. */
    @androidx.annotation.RequiresApi(33)
    private static final class Api33 {
        private static final Shader EMPTY = new LinearGradient(0, 0, 1, 1,
                Color.TRANSPARENT, Color.TRANSPARENT, Shader.TileMode.CLAMP);
        private static final String PROGRAM = """
                uniform shader image;
                uniform float2 origin;
                uniform float2 size;
                uniform float radius;
                uniform float2 sourceOrigin;
                uniform float2 sourceScale;
                uniform float3 row0;
                uniform float3 row1;
                uniform float3 row2;
                uniform float blur;
                half4 main(float2 p) {
                    float2 q = p - origin - size * 0.5;
                    float2 d = abs(q) - (size * 0.5 - radius);
                    float distance = length(max(d, float2(0))) + min(max(d.x,d.y),0.0) - radius;
                    float edge = 1.0 - smoothstep(-12.0, -1.0, distance);
                    edge = 1.0 - edge;
                    float2 direction = q / max(length(q), 1.0);
                    float2 refracted = p + direction * edge * 2.5;
                    float3 local = float3(refracted, 1.0);
                    float w = dot(row2, local);
                    if (w < 0.0001) return half4(0);
                    float2 screen = float2(dot(row0, local), dot(row1, local)) / w;
                    float2 uv = (screen - sourceOrigin) * sourceScale;
                    float2 delta = float2(blur, blur) * sourceScale;
                    half4 c = image.eval(uv) * 0.28;
                    c += image.eval(uv + float2(delta.x,0)) * 0.12;
                    c += image.eval(uv - float2(delta.x,0)) * 0.12;
                    c += image.eval(uv + float2(0,delta.y)) * 0.12;
                    c += image.eval(uv - float2(0,delta.y)) * 0.12;
                    c += image.eval(uv + delta) * 0.055;
                    c += image.eval(uv - delta) * 0.055;
                    c += image.eval(uv + float2(delta.x,-delta.y)) * 0.055;
                    c += image.eval(uv + float2(-delta.x,delta.y)) * 0.055;
                    return c;
                }
                """;

        static Shader create(Bitmap bitmap) {
            RuntimeShader shader = new RuntimeShader(PROGRAM);
            bind(shader, bitmap);
            return shader;
        }

        static void bind(Shader shader, Bitmap bitmap) {
            ((RuntimeShader) shader).setInputShader("image", new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP));
        }

        static void detach(Shader shader) { ((RuntimeShader) shader).setInputShader("image", EMPTY); }

        static void update(Shader value, RectF shape, float radius, Rect source, float[] matrix, float density) {
            RuntimeShader shader = (RuntimeShader) value;
            shader.setFloatUniform("origin", shape.left, shape.top);
            shader.setFloatUniform("size", shape.width(), shape.height());
            shader.setFloatUniform("radius", radius);
            shader.setFloatUniform("sourceOrigin", (float) source.left, (float) source.top);
            // The sample retains the source aspect; size is bounded independently of its view dimensions.
            int[] bitmapSize = LiquidGlassBackdrop.captureSize(source.width(), source.height());
            shader.setFloatUniform("sourceScale", (float) bitmapSize[0] / source.width(), (float) bitmapSize[1] / source.height());
            shader.setFloatUniform("row0", matrix[0], matrix[1], matrix[2]);
            shader.setFloatUniform("row1", matrix[3], matrix[4], matrix[5]);
            shader.setFloatUniform("row2", matrix[6], matrix[7], matrix[8]);
            shader.setFloatUniform("blur", 3.5f * density);
        }
    }
}
