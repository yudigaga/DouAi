package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Build;
import android.os.Looper;
import android.os.SystemClock;
import android.view.PixelCopy;
import android.view.SurfaceView;
import android.view.TextureView;
import android.view.View;

import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Main-thread, window-owned sampling of clean media only. Never captures a decorated view tree. */
public final class LiquidGlassBackdrop implements AutoCloseable {
    private static final Map<View, Object> IN_FLIGHT = new WeakHashMap<>();
    private static final long FRAME_INTERVAL_MS = 67;
    private final View windowRoot;
    private final Consumer<String> log;
    private final Copier copier;
    private final LongSupplier clock;
    // This handler deliberately survives owner close: PixelCopy's callback owns pending cleanup.
    private final Handler results = new Handler(Looper.getMainLooper());
    private final ArrayList<Runnable> observers = new ArrayList<>();
    private final View.OnAttachStateChangeListener sourceAttachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { if (source == view) setSource(null); }
    };
    private final View.OnAttachStateChangeListener rootAttachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) { close(); }
    };
    private final View.OnLayoutChangeListener sourceLayout = (view, l, t, r, b, ol, ot, or, ob) -> {
        if (l != ol || t != ot || r != or || b != ob) { this.generation++; clearFrame(); }
    };
    private View source;
    private Sample displayed;
    private Snapshot displayedGeometry;
    private long generation;
    private long lastAttempt = Long.MIN_VALUE;
    private int failures;
    private String lastReason;
    private boolean closed;

    interface Copier {
        void request(SurfaceView source, Bitmap target, PixelCopy.OnPixelCopyFinishedListener listener, Handler handler);
    }

    public LiquidGlassBackdrop(View windowRoot, Consumer<String> log) {
        this(windowRoot, log, PixelCopy::request, SystemClock::uptimeMillis);
    }

    LiquidGlassBackdrop(View windowRoot, Consumer<String> log, Copier copier, LongSupplier clock) {
        this.windowRoot = java.util.Objects.requireNonNull(windowRoot);
        this.log = log;
        this.copier = copier;
        this.clock = clock;
        windowRoot.addOnAttachStateChangeListener(rootAttachment);
    }

    /** Only render surfaces currently prove an independent content source; ordinary views fall back. */
    public void setSource(View next) {
        if (closed || source == next) return;
        if (source != null) {
            source.removeOnAttachStateChangeListener(sourceAttachment);
            source.removeOnLayoutChangeListener(sourceLayout);
        }
        source = next;
        generation++;
        failures = 0;
        lastAttempt = Long.MIN_VALUE;
        clearFrame();
        if (next != null) {
            next.addOnAttachStateChangeListener(sourceAttachment);
            next.addOnLayoutChangeListener(sourceLayout);
        }
    }

    public void refresh() {
        if (closed) return;
        if (Build.VERSION.SDK_INT < 33) { clearFrame(); report("compat_api"); return; }
        if (!windowRoot.isAttachedToWindow() || !windowRoot.isShown()) {
            generation++; clearFrame(); return;
        }
        Snapshot geometry = snapshot();
        if (geometry == null) { clearFrame(); return; }
        if (displayedGeometry != null && !displayedGeometry.same(geometry)) clearFrame();
        long now = clock.getAsLong();
        long interval = failures >= 3 ? 1000 : FRAME_INTERVAL_MS;
        if (lastAttempt != Long.MIN_VALUE && now - lastAttempt < interval) return;
        Object flight = new Object();
        synchronized (IN_FLIGHT) {
            if (IN_FLIGHT.containsKey(windowRoot)) return;
            IN_FLIGHT.put(windowRoot, flight);
        }
        lastAttempt = now;
        int[] size = captureSize(geometry.width, geometry.height);
        // Admit both the CPU pixels and their possible GPU upload before creating either.
        MediaMemoryBudget.Reservation memory = MediaMemoryBudget.tryAcquire((long) size[0] * size[1] * 8);
        if (memory == null) { releaseFlight(flight); failed("memory_budget"); return; }
        Bitmap bitmap;
        try { bitmap = Bitmap.createBitmap(size[0], size[1], Bitmap.Config.ARGB_8888); }
        catch (RuntimeException | OutOfMemoryError unavailable) {
            memory.close(); releaseFlight(flight); failed("allocation"); return;
        }
        Sample pending = new Sample(bitmap, memory);
        long requestGeneration = generation;
        AtomicBoolean delivered = new AtomicBoolean();
        PixelCopy.OnPixelCopyFinishedListener complete = result -> {
            if (!delivered.compareAndSet(false, true)) return;
            releaseFlight(flight);
            Snapshot current = closed ? null : snapshot();
            if (closed || generation != requestGeneration || !geometry.same(current)) {
                pending.close(); return;
            }
            if (result != PixelCopy.SUCCESS) { pending.close(); failed("capture_" + result); return; }
            Sample old = displayed;
            displayed = pending;
            displayedGeometry = geometry;
            failures = 0;
            report("media_sample");
            changed(); // Observers drop bitmap shaders before the previous bitmap is recycled.
            if (old != null) old.close();
        };
        try {
            if (source instanceof SurfaceView) {
                copier.request((SurfaceView) source, bitmap, complete, results);
            } else {
                Bitmap copied = ((TextureView) source).getBitmap(bitmap);
                complete.onPixelCopyFinished(copied == bitmap ? PixelCopy.SUCCESS : PixelCopy.ERROR_SOURCE_NO_DATA);
            }
        } catch (RuntimeException | LinkageError failure) {
            complete.onPixelCopyFinished(PixelCopy.ERROR_SOURCE_INVALID);
        }
    }

    /** Borrowed until the next resource notification; callers must neither mutate nor recycle it. */
    public Bitmap frame() {
        if (displayedGeometry != null && !displayedGeometry.same(snapshot())) clearFrame();
        return displayed == null ? null : displayed.bitmap;
    }

    /** Full source bounds in this window root's coordinates, including any offscreen portion. */
    public Rect sourceBounds() {
        return frame() == null ? new Rect() : new Rect(displayedGeometry.bounds);
    }

    Rect visibleBounds() {
        return frame() == null ? new Rect() : new Rect(displayedGeometry.visible);
    }

    void anchorOffset(View anchor, int[] out) {
        int[] root = new int[2];
        anchor.getLocationOnScreen(out);
        windowRoot.getLocationOnScreen(root);
        out[0] -= root[0]; out[1] -= root[1];
    }

    void addObserver(Runnable observer) { if (!closed) observers.add(observer); }
    void removeObserver(Runnable observer) { observers.remove(observer); }

    private Snapshot snapshot() {
        View view = source;
        if (closed || !(view instanceof SurfaceView || view instanceof TextureView)) {
            report("compat_no_independent_source"); return null;
        }
        if (!windowRoot.isAttachedToWindow() || !windowRoot.isShown()
                || !view.isAttachedToWindow() || !view.isShown() || view.getWidth() <= 0 || view.getHeight() <= 0) return null;
        // Rotated/scaled surface ancestry needs a host-specific crop transform; guessing smears other UI.
        if (!translationOnly(view) || !translationOnly(windowRoot)) { report("compat_transform"); return null; }
        Rect buffer = new Rect(0, 0, view.getWidth(), view.getHeight());
        try {
            if (view instanceof SurfaceView) {
                SurfaceView surface = (SurfaceView) view;
                if (surface.getHolder() == null || surface.getHolder().getSurface() == null
                        || !surface.getHolder().getSurface().isValid()) return null;
                Rect frame = surface.getHolder().getSurfaceFrame();
                if (frame == null || frame.isEmpty()) return null;
                buffer.set(frame);
                if (Math.abs((double) buffer.width() * view.getHeight() / (buffer.height() * (double) view.getWidth()) - 1) > .01) {
                    report("compat_surface_crop"); return null;
                }
            } else {
                TextureView texture = (TextureView) view;
                if (!texture.isAvailable() || !texture.getTransform(new Matrix()).isIdentity()) {
                    report("compat_texture_transform"); return null;
                }
            }
            int[] location = new int[2], root = new int[2];
            view.getLocationOnScreen(location); windowRoot.getLocationOnScreen(root);
            Rect visible = new Rect();
            if (!view.getLocalVisibleRect(visible) || visible.isEmpty()) return null;
            visible.offset(location[0] - root[0], location[1] - root[1]);
            Rect bounds = new Rect(location[0] - root[0], location[1] - root[1],
                    location[0] - root[0] + view.getWidth(), location[1] - root[1] + view.getHeight());
            return new Snapshot(view, view.getWindowToken(), windowRoot.getWindowToken(), bounds, visible, buffer,
                    root[0], root[1], view.getWidth(), view.getHeight());
        } catch (RuntimeException | LinkageError unavailable) { report("compat_source_unavailable"); return null; }
    }

    private static boolean translationOnly(View view) {
        for (View current = view; current != null; current = current.getParent() instanceof View ? (View) current.getParent() : null) {
            if (!Float.isFinite(current.getTranslationX()) || !Float.isFinite(current.getTranslationY())
                    || Math.abs(current.getScaleX() - 1) > .001f || Math.abs(current.getScaleY() - 1) > .001f
                    || Math.abs(current.getRotation()) > .001f || Math.abs(current.getRotationX()) > .001f
                    || Math.abs(current.getRotationY()) > .001f || current.getAnimation() != null) return false;
        }
        return true;
    }

    static int[] captureSize(int width, int height) {
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid media dimensions");
        double scale = Math.min(1, 512d / Math.max(width, height));
        return new int[]{Math.max(1, (int) Math.floor(width * scale)), Math.max(1, (int) Math.floor(height * scale))};
    }

    private void failed(String reason) { failures++; clearFrame(); report("compat_" + reason); }

    private void clearFrame() {
        Sample old = displayed;
        displayed = null; displayedGeometry = null;
        if (old != null) { changed(); old.close(); }
    }

    private void changed() {
        for (Runnable observer : new ArrayList<>(observers)) {
            try { observer.run(); } catch (RuntimeException | LinkageError ignored) { }
        }
    }

    private void report(String reason) {
        if (reason.equals(lastReason)) return;
        lastReason = reason;
        if (log != null) try { log.accept("liquid_glass backdrop=" + reason); } catch (RuntimeException ignored) { }
    }

    private void releaseFlight(Object flight) {
        synchronized (IN_FLIGHT) { if (IN_FLIGHT.get(windowRoot) == flight) IN_FLIGHT.remove(windowRoot); }
    }

    @Override public void close() {
        if (closed) return;
        setSource(null);
        closed = true;
        windowRoot.removeOnAttachStateChangeListener(rootAttachment);
        changed(); observers.clear();
        // An in-flight copy retains its reservation until its independent platform callback returns.
    }

    private static final class Sample implements AutoCloseable {
        final Bitmap bitmap;
        final MediaMemoryBudget.Reservation memory;
        Sample(Bitmap bitmap, MediaMemoryBudget.Reservation memory) { this.bitmap = bitmap; this.memory = memory; }
        @Override public void close() { if (!bitmap.isRecycled()) bitmap.recycle(); memory.close(); }
    }

    private static final class Snapshot {
        final View source;
        final Object sourceWindow, rootWindow;
        final Rect bounds, visible, buffer;
        final int rootX, rootY, width, height;
        Snapshot(View source, Object sourceWindow, Object rootWindow, Rect bounds, Rect visible, Rect buffer,
                 int rootX, int rootY, int width, int height) {
            this.source = source; this.sourceWindow = sourceWindow; this.rootWindow = rootWindow;
            this.bounds = bounds; this.visible = visible; this.buffer = buffer;
            this.rootX = rootX; this.rootY = rootY; this.width = width; this.height = height;
        }
        boolean same(Snapshot other) {
            return other != null && source == other.source && sourceWindow == other.sourceWindow
                    && rootWindow == other.rootWindow && rootX == other.rootX && rootY == other.rootY
                    && width == other.width && height == other.height && bounds.equals(other.bounds)
                    && visible.equals(other.visible) && buffer.equals(other.buffer);
        }
    }
}
