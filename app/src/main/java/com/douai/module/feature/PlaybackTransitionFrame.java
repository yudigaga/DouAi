package com.douai.module.feature;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageView;

import java.util.ArrayDeque;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;

/** Captures only the visible video surface to bridge its short detach/decode handoff. */
final class PlaybackTransitionFrame {
    private static final int MAX_SURFACE_NODES = 1024;
    private static final int MAX_CAPTURE_WIDTH = 900;
    private static final int MAX_CAPTURE_HEIGHT = 1600;
    static final int MAX_CAPTURE_PIXELS = 900 * 1600;
    private static final Map<View, Object> IN_FLIGHT = new WeakHashMap<>();
    private static final Map<View, SurfaceCache> SURFACES = new WeakHashMap<>();
    private static final ThreadPoolExecutor ALLOCATIONS = new ThreadPoolExecutor(0,1,10,TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(1),r->{Thread t=new Thread(r,"DouAi-transition-frame");t.setDaemon(true);return t;});

    interface Copier {
        void request(SurfaceView source, Bitmap target, PixelCopy.OnPixelCopyFinishedListener listener, Handler handler);
    }

    private PlaybackTransitionFrame() { }

    static void capture(View holderRoot, Handler main, Consumer<Overlay> callback) {
        capture(holderRoot, main, () -> true, callback);
    }

    static void capture(View holderRoot, Handler main, BooleanSupplier current, Consumer<Overlay> callback) {
        captureDeferred(holderRoot, main, current, callback, PixelCopy::request,ALLOCATIONS);
    }

    static void capture(View holderRoot, Handler main, BooleanSupplier current, Consumer<Overlay> callback, Copier copier) {
        captureDeferred(holderRoot,main,current,callback,copier,Runnable::run);
    }

    static void captureDeferred(View holderRoot, Handler main, BooleanSupplier current, Consumer<Overlay> callback,
                                Copier copier, Executor allocation) {
        captureSurface(holderRoot, holderRoot == null ? null : videoSurface(holderRoot), main, current, callback, copier,allocation);
    }

    /** Production uses the selected holder's own renderer, not a guessed largest child surface. */
    static void captureRenderer(View holderRoot, View renderer, Handler main, BooleanSupplier current, Consumer<Overlay> callback) {
        captureSurface(holderRoot, renderer instanceof SurfaceView ? (SurfaceView)renderer : null,
                main, current, callback, PixelCopy::request,ALLOCATIONS);
    }

    private static void captureSurface(View holderRoot, SurfaceView surface, Handler main, BooleanSupplier current,
                                       Consumer<Overlay> callback, Copier copier, Executor allocation) {
        if (callback == null) return;
        if (holderRoot == null || main == null || callback == null || !holderRoot.isAttachedToWindow()) {
            noFrame(callback);
            return;
        }
        Activity activity = activity(holderRoot.getContext());
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            noFrame(callback);
            return;
        }
        if (surface == null || !visibleInRoot(surface, holderRoot) || !untransformed(surface)) {
            noFrame(callback);
            return;
        }
        SurfaceHolder holder = surface.getHolder();
        if (holder == null || holder.getSurface() == null || !holder.getSurface().isValid()) {
            noFrame(callback);
            return;
        }
        int sourceWidth = surface.getWidth(), sourceHeight = surface.getHeight();
        if (sourceWidth <= 0 || sourceHeight <= 0) {
            noFrame(callback);
            return;
        }
        Rect bufferFrame = holder.getSurfaceFrame();
        Rect sourceBuffer = bufferFrame == null ? null : new Rect(bufferFrame);
        if (sourceBuffer == null || sourceBuffer.isEmpty()
                || !sameAspect(sourceWidth, sourceHeight, sourceBuffer.width(), sourceBuffer.height())) {
            noFrame(callback); return;
        }
        Rect visible = new Rect();
        if (!surface.getLocalVisibleRect(visible) || visible.isEmpty()) { noFrame(callback); return; }
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof FrameLayout)) {
            noFrame(callback);
            return;
        }
        Object window = holderRoot.getWindowToken();
        if(window==null){noFrame(callback);return;}
        Object flight = new Object();
        synchronized (IN_FLIGHT) {
            if (IN_FLIGHT.containsKey(decor)) {
                noFrame(callback);
                return;
            }
            IN_FLIGHT.put(decor, flight);
        }
        int[] size = captureSize(sourceBuffer.width(), sourceBuffer.height());
        int width = size[0], height = size[1];
        if (!sameAspect(sourceWidth, sourceHeight, width, height)) {
            releaseFlight(decor,flight); noFrame(callback); return;
        }
        MediaMemoryBudget.Reservation memory = MediaMemoryBudget.tryAcquire((long) width * height * 4);
        if (memory == null) {
            releaseFlight(decor, flight);
            noFrame(callback);
            return;
        }
        int[] surfaceLocation = new int[2], decorLocation = new int[2];
        surface.getLocationOnScreen(surfaceLocation);
        decor.getLocationOnScreen(decorLocation);
        Rect location = new Rect(surfaceLocation[0] - decorLocation[0],
                surfaceLocation[1] - decorLocation[1],
                surfaceLocation[0] - decorLocation[0] + sourceWidth,
                surfaceLocation[1] - decorLocation[1] + sourceHeight);
        Rect clipping = new Rect(visible);
        clipping.offset(surfaceLocation[0]-decorLocation[0], surfaceLocation[1]-decorLocation[1]);
        AtomicBoolean delivered = new AtomicBoolean();
        // Allocation is off-main in production; use an independent handler so owner cancellation
        // cannot strand its reservation. Native PixelCopy is not started for an expired owner.
        Handler results = new Handler(main.getLooper());
        allocate(allocation,results,width,height, bitmap -> {
        boolean allowed=false;
        try { allowed=bitmap!=null && surface.isAttachedToWindow() && holderRoot.getWindowToken()==window && current.getAsBoolean(); }
        catch(RuntimeException|LinkageError ignored){ }
        if(!allowed){
            releaseFlight(decor,flight);
            if(bitmap!=null)release(bitmap,memory);else memory.close();
            noFrame(callback);return;
        }
        try {
            copier.request(surface, bitmap, result -> {
                if (!delivered.compareAndSet(false, true)) return;
                releaseFlight(decor, flight);
                boolean valid = false;
                try {
                    int[] now=new int[2]; surface.getLocationOnScreen(now);
                    Rect shown=new Rect();
                    valid = result==PixelCopy.SUCCESS && !activity.isFinishing() && !activity.isDestroyed()
                            && surface.isAttachedToWindow() && holderRoot.getWindowToken()==window
                            && sourceWidth==surface.getWidth() && sourceHeight==surface.getHeight()
                            && sourceBuffer.equals(surface.getHolder().getSurfaceFrame())
                            && untransformed(surface) && surface.getLocalVisibleRect(shown) && visible.equals(shown)
                            && now[0]==surfaceLocation[0] && now[1]==surfaceLocation[1]
                            && current.getAsBoolean() && hasVisiblePixels(bitmap);
                } catch (RuntimeException | LinkageError ignored) { }
                if (!valid) {
                    release(bitmap, memory);
                    noFrame(callback);
                    return;
                }
                Overlay overlay;
                try { overlay = new Overlay((FrameLayout) decor, bitmap, location, clipping, memory); }
                catch (RuntimeException | LinkageError failure) {
                    release(bitmap, memory);
                    noFrame(callback);
                    return;
                }
                try { callback.accept(overlay); }
                catch (RuntimeException | LinkageError failure) { overlay.close(); }
            }, results);
        } catch (RuntimeException | LinkageError failure) {
            if (delivered.compareAndSet(false, true)) {
                releaseFlight(decor, flight);
                release(bitmap, memory);
                noFrame(callback);
            }
        }
        });
    }

    private static void allocate(Executor executor,Handler ui,int width,int height,Consumer<Bitmap> ready) {
        try { executor.execute(()->{
            Bitmap created;
            try { created=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); }
            catch(RuntimeException|OutOfMemoryError unavailable){created=null;}
            Bitmap result=created;
            if(Looper.myLooper()==ui.getLooper())ready.accept(result);else ui.post(()->ready.accept(result));
        }); }catch(java.util.concurrent.RejectedExecutionException busy){ready.accept(null);}
    }

    private static void noFrame(Consumer<Overlay> callback) {
        try { callback.accept(null); } catch(RuntimeException|LinkageError ignored){ }
    }

    static int[] captureSize(int sourceWidth, int sourceHeight) {
        if (sourceWidth <= 0 || sourceHeight <= 0) throw new IllegalArgumentException("Invalid surface size");
        double scale = Math.min(1d, Math.min(MAX_CAPTURE_WIDTH / (double) sourceWidth,
                MAX_CAPTURE_HEIGHT / (double) sourceHeight));
        scale = Math.min(scale, Math.sqrt(MAX_CAPTURE_PIXELS / ((double) sourceWidth * sourceHeight)));
        return new int[]{Math.max(1, (int) Math.floor(sourceWidth * scale)),
                Math.max(1, (int) Math.floor(sourceHeight * scale))};
    }

    private static boolean sameAspect(int w,int h,int otherW,int otherH) {
        if(w<=0 || h<=0 || otherW<=0 || otherH<=0)return false;
        return Math.abs(((double)w*otherH)/(h*(double)otherW)-1d) <= .01d;
    }
    private static boolean untransformed(View view) {
        for(View v=view;v!=null;v=v.getParent() instanceof View?(View)v.getParent():null) {
            if(Math.abs(v.getScaleX()-1f)>.001f || Math.abs(v.getScaleY()-1f)>.001f
                    || Math.abs(v.getRotation())>.001f || Math.abs(v.getRotationX())>.001f || Math.abs(v.getRotationY())>.001f
                    || v.getAnimation()!=null)return false;
        }
        return true;
    }
    // A black/empty bridge adds no continuity; retain the host's native transition instead.
    private static boolean hasVisiblePixels(Bitmap bitmap) {
        for(int y=0;y<5;y++)for(int x=0;x<5;x++) {
            int color=bitmap.getPixel(x*(bitmap.getWidth()-1)/4,y*(bitmap.getHeight()-1)/4);
            if((color>>>24)!=0 && (color&0xFFFFFF)!=0)return true;
        }
        return false;
    }

    private static void releaseFlight(View decor, Object flight) {
        synchronized (IN_FLIGHT) { if (IN_FLIGHT.get(decor) == flight) IN_FLIGHT.remove(decor); }
    }

    private static void release(Bitmap bitmap, MediaMemoryBudget.Reservation memory) {
        if (!bitmap.isRecycled()) bitmap.recycle();
        memory.close();
    }

    static void invalidateSurfaceCache(View root) {
        if (root == null) return;
        SurfaceCache previous = SURFACES.remove(root);
        if (previous != null) previous.remove();
    }

    static SurfaceView videoSurface(View root) {
        SurfaceCache previous = SURFACES.get(root);
        if (previous != null) {
            SurfaceView cached = previous.resolve(root);
            if (cached != null) return cached;
            invalidateSurfaceCache(root);
        }
        ArrayDeque<View> pending = new ArrayDeque<>();
        pending.add(root);
        SurfaceView best = null;
        long bestArea = 0;
        List<WeakReference<SurfaceView>> candidates = new ArrayList<>();
        List<WeakReference<View>> excluded = new ArrayList<>();
        List<WeakReference<ViewGroup>> groups = new ArrayList<>();
        List<Integer> childCounts = new ArrayList<>();
        int visited = 0;
        while (!pending.isEmpty() && visited++ < MAX_SURFACE_NODES) {
            View view = pending.removeFirst();
            if (!visibleInRoot(view, root)) {
                excluded.add(new WeakReference<>(view));
                continue;
            }
            if (view instanceof SurfaceView) {
                SurfaceView candidate = (SurfaceView) view;
                candidates.add(new WeakReference<>(candidate));
                SurfaceHolder holder = candidate.getHolder();
                if (visibleInRoot(candidate, root) && holder != null && holder.getSurface() != null
                        && holder.getSurface().isValid()) {
                    long area = (long) candidate.getWidth() * candidate.getHeight();
                    if (area > bestArea) { best = candidate; bestArea = area; }
                }
            }
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                groups.add(new WeakReference<>(group));
                childCounts.add(group.getChildCount());
                for (int index = 0; index < group.getChildCount(); index++) {
                    View child = group.getChildAt(index);
                    if (child != null) pending.addLast(child);
                }
            }
        }
        if (best != null && pending.isEmpty()) {
            SurfaceCache cache = new SurfaceCache(root, candidates, excluded, groups, childCounts);
            SURFACES.put(root, cache);
        }
        return best;
    }

    private static boolean visibleInRoot(View view, View root) {
        if (!view.isShown()) return false;
        View current = view;
        for (int depth = 0; depth < 64; depth++) {
            if (current.getAlpha() < 0.1f) return false;
            if (current == root) return true;
            if (!(current.getParent() instanceof View)) return false;
            current = (View) current.getParent();
        }
        return false;
    }

    private static final class SurfaceCache implements ViewTreeObserver.OnGlobalLayoutListener, View.OnAttachStateChangeListener {
        final WeakReference<View> root;
        final WeakReference<ViewTreeObserver> observer;
        final List<WeakReference<SurfaceView>> candidates;
        final List<WeakReference<View>> excluded;
        final List<WeakReference<ViewGroup>> groups;
        final List<Integer> childCounts;
        boolean dirty;

        SurfaceCache(View root, List<WeakReference<SurfaceView>> candidates, List<WeakReference<View>> excluded,
                     List<WeakReference<ViewGroup>> groups, List<Integer> childCounts) {
            this.root = new WeakReference<>(root);
            this.candidates = candidates;
            this.excluded = excluded;
            this.groups = groups;
            this.childCounts = childCounts;
            ViewTreeObserver tree = root.getViewTreeObserver();
            observer = new WeakReference<>(tree);
            if (tree.isAlive()) tree.addOnGlobalLayoutListener(this);
            root.addOnAttachStateChangeListener(this);
        }

        SurfaceView resolve(View expectedRoot) {
            if (dirty || root.get() != expectedRoot || expectedRoot.isLayoutRequested()) return null;
            for (WeakReference<View> reference : excluded) {
                View view = reference.get();
                if (view == null || visibleInRoot(view, expectedRoot)) return null;
            }
            for (int index = 0; index < groups.size(); index++) {
                ViewGroup group = groups.get(index).get();
                if (group == null || group.getChildCount() != childCounts.get(index)) return null;
            }
            SurfaceView best = null;
            long area = 0;
            for (WeakReference<SurfaceView> reference : candidates) {
                SurfaceView candidate = reference.get();
                if (candidate == null || !visibleInRoot(candidate, expectedRoot)) continue;
                SurfaceHolder holder = candidate.getHolder();
                long nextArea = (long) candidate.getWidth() * candidate.getHeight();
                if (nextArea > area && holder != null && holder.getSurface() != null && holder.getSurface().isValid()) {
                    best = candidate;
                    area = nextArea;
                }
            }
            return best;
        }

        @Override public void onGlobalLayout() { dirty = true; }
        @Override public void onViewAttachedToWindow(View view) { dirty = true; }
        @Override public void onViewDetachedFromWindow(View view) { invalidateSurfaceCache(view); }

        void remove() {
            View view = root.get();
            if (view != null) view.removeOnAttachStateChangeListener(this);
            ViewTreeObserver tree = observer.get();
            if (tree != null && tree.isAlive()) tree.removeOnGlobalLayoutListener(this);
        }
    }

    private static Activity activity(android.content.Context context) {
        while (context instanceof android.content.ContextWrapper && !(context instanceof Activity)) {
            android.content.Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) return null;
            context = base;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    static final class Overlay implements AutoCloseable {
        private final FrameLayout decor;
        private final Bitmap bitmap;
        private final MediaMemoryBudget.Reservation memory;
        private ImageView image;
        private FrameLayout clip;
        private boolean closed;

        Overlay(FrameLayout decor, Bitmap bitmap, Rect location, MediaMemoryBudget.Reservation memory) {
            this(decor,bitmap,location,location,memory);
        }

        @android.annotation.SuppressLint("RtlHardcoded") // Physical video coordinates must not mirror with UI language.
        Overlay(FrameLayout decor, Bitmap bitmap, Rect location, Rect clipping, MediaMemoryBudget.Reservation memory) {
            this.decor = decor;
            this.bitmap = bitmap;
            this.memory = memory;
            image = new ImageView(decor.getContext());
            image.setImageBitmap(bitmap);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            image.setClickable(false);
            image.setFocusable(false);
            image.setEnabled(false);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(location.width(), location.height());
            params.gravity=Gravity.TOP|Gravity.LEFT;
            params.leftMargin = location.left-clipping.left;
            params.topMargin = location.top-clipping.top;
            clip=new FrameLayout(decor.getContext());
            clip.setClipChildren(true);clip.setClickable(false);clip.setFocusable(false);
            clip.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
            clip.addView(image,params);
            FrameLayout.LayoutParams bounds=new FrameLayout.LayoutParams(clipping.width(),clipping.height());
            bounds.gravity=Gravity.TOP|Gravity.LEFT;
            bounds.leftMargin=clipping.left-decor.getPaddingLeft();bounds.topMargin=clipping.top-decor.getPaddingTop();
            decor.addView(clip,bounds);clip.bringToFront();
        }

        void fadeAndClose(long durationMillis) {
            if (closed || image == null) { close(); return; }
            ImageView current = image;
            current.animate().alpha(0f).setDuration(Math.max(0, durationMillis))
                    .withEndAction(this::close).start();
        }

        @Override public void close() {
            if (closed) return;
            closed = true;
            if (image != null) {
                image.animate().cancel();
                if (clip != null && clip.getParent() == decor) decor.removeView(clip);
                image.setImageDrawable(null);
                image = null;
                clip = null;
            }
            release(bitmap, memory);
        }
    }
}
