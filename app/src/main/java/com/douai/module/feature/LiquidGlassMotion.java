package com.douai.module.feature;

import android.animation.ValueAnimator;
import android.content.Context;
import android.database.ContentObserver;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.Choreographer;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Main-thread owner of motion for one window's non-nested, media-free surfaces. */
public final class LiquidGlassMotion implements AutoCloseable {
    private static final float GRAVITY = 9.80665f;
    private static final float TRANSLATION_DP = 10f, TILT_DEGREES = 1.2f;
    private static final long FRAME_NS = 33_333_334L;
    private static SensorHub sharedHub;

    private final Context context;
    private final Choreographer choreographer;
    private final Handler handler;
    private final IdentityHashMap<View, Transform> targets = new IdentityHashMap<>();
    private final MotionState state = new MotionState();
    private final OrientationReference reference = new OrientationReference();
    private final Choreographer.FrameCallback frameCallback = this::onFrame;
    private final ContentObserver animationObserver;
    private final View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {
            Transform removed = targets.remove(view);
            if (removed != null) removed.restore();
            view.removeOnAttachStateChangeListener(this);
            updateSubscription();
        }
    };
    private BiConsumer<Float, Float> light;
    private Runnable pendingTargets;
    private SensorHub hub;
    private View boundsRoot;
    private boolean active, closed, scheduled, observing;

    public LiquidGlassMotion(Context context) {
        requireMainThread();
        this.context = context;
        handler = new Handler(Looper.getMainLooper());
        choreographer = Choreographer.getInstance();
        animationObserver = new ContentObserver(handler) {
            @Override public void onChange(boolean selfChange) { updateSubscription(); }
        };
    }

    public void setTargets(Collection<View> views, BiConsumer<Float, Float> light) {
        setTargets(views, null, light);
    }

    public void setTargets(Collection<View> views, View root, BiConsumer<Float, Float> light) {
        requireMainThread();
        if (closed) return;
        if (state.interacting) {
            ArrayList<View> snapshot = views == null ? new ArrayList<>() : new ArrayList<>(views);
            pendingTargets = () -> setTargets(snapshot, root, light);
            return;
        }
        boundsRoot = root;
        IdentityHashMap<View, Boolean> desired = new IdentityHashMap<>();
        if (views != null) for (View view : views) if (view != null) desired.put(view, true);
        for (View view : new ArrayList<>(targets.keySet())) {
            if (!desired.containsKey(view)) {
                targets.remove(view).restore();
                view.removeOnAttachStateChangeListener(attachment);
            }
        }
        for (View view : desired.keySet()) if (!targets.containsKey(view)) {
            targets.put(view, new Transform(view));
            view.addOnAttachStateChangeListener(attachment);
        }
        if (this.light != light && this.light != null) this.light.accept(0f, 0f);
        this.light = light;
        updateSubscription();
        if (active && hub != null && !state.interacting) applyFrame(new Vector(state.x, state.y));
    }

    public void setActive(boolean active) {
        requireMainThread();
        if (closed) return;
        this.active = active;
        updateSubscription();
    }

    public void setInteracting(boolean interacting) {
        requireMainThread();
        if (closed) return;
        state.setInteracting(interacting, uptimeNanos());
        if (interacting) cancelFrame();
        else {
            Runnable pending = pendingTargets;
            pendingTargets = null;
            if (pending != null) pending.run();
            if (active && hub != null) applyFrame(new Vector(state.x, state.y));
            scheduleFrame();
        }
    }

    @Override public void close() {
        requireMainThread();
        if (closed) return;
        active = false;
        updateSubscription();
        closed = true;
        for (View view : targets.keySet()) view.removeOnAttachStateChangeListener(attachment);
        targets.clear();
        light = null;
    }

    private void updateSubscription() {
        boolean requested = active && !closed && !targets.isEmpty();
        if (requested && !observing) {
            try {
                context.getContentResolver().registerContentObserver(
                        Settings.Global.getUriFor(Settings.Global.ANIMATOR_DURATION_SCALE), false, animationObserver);
                observing = true;
            } catch (RuntimeException ignored) { /* Restricted host contexts still check each sensor event. */ }
        } else if (!requested && observing) {
            context.getContentResolver().unregisterContentObserver(animationObserver);
            observing = false;
        }
        if (requested && animationsEnabled()) {
            if (hub == null) {
                for (Map.Entry<View, Transform> entry : targets.entrySet()) {
                    if (!entry.getValue().yielded) entry.setValue(new Transform(entry.getKey()));
                }
                if (sharedHub == null) sharedHub = new SensorHub(context);
                if (sharedHub.add(this)) hub = sharedHub;
                else if (sharedHub.owners.isEmpty()) sharedHub = null;
            }
        } else {
            if (hub != null) { hub.remove(this); hub = null; }
            cancelFrame();
            state.reset();
            reference.reset();
            pendingTargets = null;
            for (Transform target : targets.values()) target.restore();
            if (light != null) light.accept(0f, 0f);
        }
    }

    private boolean animationsEnabled() {
        if (!ValueAnimator.areAnimatorsEnabled()) return false;
        try {
            return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0;
        } catch (RuntimeException ignored) { return true; }
    }

    private int rotation() {
        for (View view : targets.keySet()) {
            Display display = view.getDisplay();
            if (display != null) return display.getRotation();
        }
        WindowManager windows = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        Display display = windows != null ? windows.getDefaultDisplay() : null;
        return display != null ? display.getRotation() : 0;
    }

    private void receive(float x, float y, float z) {
        if (!active || closed) return;
        if (!animationsEnabled()) { updateSubscription(); return; }
        Vector relative = reference.accept(x, y, z, rotation());
        if (relative == null) return;
        if (!state.needsFrame()) state.lastFrame = uptimeNanos();
        state.accept(relative);
        scheduleFrame();
    }

    private void scheduleFrame() {
        if (scheduled || closed || hub == null || !state.needsFrame()) return;
        long now = uptimeNanos();
        if (state.lastFrame < 0) state.lastFrame = now;
        long delay = Math.max(0, (FRAME_NS - (now - state.lastFrame)) / 1_000_000L - 1);
        scheduled = true;
        choreographer.postFrameCallbackDelayed(frameCallback, delay);
    }

    private void cancelFrame() {
        if (scheduled) choreographer.removeFrameCallback(frameCallback);
        scheduled = false;
    }

    private void onFrame(long time) {
        scheduled = false;
        if (closed || hub == null) {
            return;
        }
        if (!animationsEnabled()) { updateSubscription(); return; }
        if (state.step(time)) applyFrame(new Vector(state.x, state.y));
        scheduleFrame();
    }

    void applyFrame(Vector tilt) {
        float density = context.getResources().getDisplayMetrics().density;
        if (!Float.isFinite(density) || density <= 0) density = 1;
        Vector bounded = Vector.bounded(tilt.x, tilt.y);
        for (Transform target : targets.values()) {
            if (target.yielded) continue;
            View root = boundsRoot == null ? target.view.getRootView() : boundsRoot;
            // Unpresented standalone views have no viewport yet; the explicit window API is always bounded.
            if (boundsRoot == null && (root == target.view || root.getWidth() <= 0 || root.getHeight() <= 0)) {
                target.apply(bounded, density);
            } else {
                float[] offsets = boundedOffsets(target, bounded, density, root);
                if (offsets == null) {
                    if (ownTransientState(target.view) || target.hasAnimation()) target.yielded = true;
                    target.restore();
                } else target.applyOffsets(offsets);
            }
        }
        if (light != null) light.accept(tilt.x, tilt.y);
    }

    /** Root-local drawing bounds without this owner's still-owned motion; null when geometry is unreliable. */
    RectF restingBounds(View view, View root) {
        return restingBounds(view, root, 0);
    }

    /** The caller validates retained layout for this many nodes starting at the target. */
    RectF restingBounds(View view, View root, int retainedDepth) {
        requireMainThread();
        if (closed) return null;
        try {
            Matrix transform = restingMatrixToRoot(view, root, retainedDepth);
            if (transform == null) return null;
            RectF bounds = new RectF(0, 0, view.getWidth(), view.getHeight());
            transform.mapRect(bounds);
            return !bounds.isEmpty() && Float.isFinite(bounds.left) && Float.isFinite(bounds.top)
                    && Float.isFinite(bounds.right) && Float.isFinite(bounds.bottom) ? bounds : null;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private Matrix restingMatrixToRoot(View view, View root) {
        return restingMatrixToRoot(view, root, 0);
    }

    private Matrix restingMatrixToRoot(View view, View root, int retainedDepth) {
        if (view == null || root == null || !root.isLaidOut()
                || view.getWidth() <= 0 || view.getHeight() <= 0 || root.getWidth() <= 0 || root.getHeight() <= 0) return null;
        Matrix transform = new Matrix(), inverse = new Matrix();float[] values = new float[9];
        View current = view;int depth = 0;
        while (current != root) {
            if (++depth > 40 || depth > retainedDepth && !current.isLaidOut()
                    || current.getWidth() <= 0 || current.getHeight() <= 0
                    || ownTransientState(current) || current.getAnimation() != null) return null;
            Matrix local = restingMatrix(current, targets.get(current));
            if (!invertible2D(local, inverse, values)) return null;
            transform.postConcat(local);
            if (!(current.getParent() instanceof View)) return null;
            View parent = (View) current.getParent();
            transform.postTranslate((float) current.getLeft() - parent.getScrollX(),
                    (float) current.getTop() - parent.getScrollY());
            current = parent;
        }
        return invertible2D(transform, inverse, values) ? transform : null;
    }

    private static boolean ownTransientState(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            if (group.getLayoutTransition() != null && group.getLayoutTransition().isRunning()) return true;
            if (!view.hasTransientState()) return false;
            // Child animations propagate this bit; they do not make the group's own matrix unreliable.
            for (int i = 0; i < group.getChildCount(); i++) if (group.getChildAt(i).hasTransientState()) return false;
        }
        return view.hasTransientState();
    }

    private static Matrix restingMatrix(View view, Transform target) {
        if (target == null) return actual2DMatrix(view);
        float[] pose = target.values();
        boolean stripped = false;
        for (int i = 0; i < pose.length; i++) if (target.owned[i] && Float.compare(pose[i], target.last[i]) == 0) {
            pose[i] = target.baseline[i]; stripped = true;
        }
        if (!stripped) return actual2DMatrix(view);
        if (pose[2] != 0 || pose[3] != 0) return null;
        float sx = view.getScaleX(), sy = view.getScaleY(), rotation = view.getRotation();
        float px = view.getPivotX(), py = view.getPivotY();
        if (!Float.isFinite(pose[0]) || !Float.isFinite(pose[1]) || !Float.isFinite(sx) || !Float.isFinite(sy)
                || !Float.isFinite(rotation) || !Float.isFinite(px) || !Float.isFinite(py)) return null;
        Matrix matrix = new Matrix();
        matrix.setTranslate(-px, -py);
        matrix.postScale(sx, sy);
        matrix.postRotate(rotation);
        matrix.postTranslate(px + pose[0], py + pose[1]);
        return matrix;
    }

    private static Matrix actual2DMatrix(View view) {
        return view.getRotationX() != 0 || view.getRotationY() != 0 ? null : view.getMatrix();
    }

    private static boolean invertible2D(Matrix matrix, Matrix inverse, float[] values) {
        if (matrix == null || !matrix.isAffine()) return false;
        matrix.getValues(values);
        for (float value : values) if (!Float.isFinite(value)) return false;
        if (!matrix.invert(inverse)) return false;
        inverse.getValues(values);
        for (float value : values) if (!Float.isFinite(value)) return false;
        return true;
    }

    private float[] boundedOffsets(Transform target, Vector tilt, float density, View root) {
        View view = target.view;
        if (view == root) return new float[4];
        try {
            Matrix toRoot = restingMatrixToRoot(view, root);
            if (toRoot == null || !(view.getParent() instanceof View)) return null;
            View parent = (View) view.getParent();
            Matrix parentToRoot = restingMatrixToRoot(parent, root), parentInverse = new Matrix();
            if (parentToRoot == null || !parentToRoot.invert(parentInverse)) return null;
            RectF localBounds = new RectF(0, 0, view.getWidth(), view.getHeight());
            ArrayList<MotionClip> clips = new ArrayList<>();
            View ancestor = parent;
            for (int depth = 0; ; depth++) {
                if (depth > 40) return null;
                boolean clip = ancestor == root || !(ancestor instanceof ViewGroup)
                        || ((ViewGroup) ancestor).getClipChildren() || ((ViewGroup) ancestor).getClipToPadding()
                        || ancestor.getClipBounds() != null;
                if (clip) {
                    MotionClip boundary = clip(ancestor, root, localBounds, toRoot, parentToRoot);
                    if (boundary == null || !boundary.fits(new float[2], 0, new float[2])) return null;
                    clips.add(boundary);
                }
                if (ancestor == root) break;
                if (!(ancestor.getParent() instanceof View)) return null;
                ancestor = (View) ancestor.getParent();
            }
            double expansion = tiltExpansion(view, tilt);
            float tiltScale = 1;
            if (!Double.isFinite(expansion)) { expansion = 0; tiltScale = 0; }
            if (expansion > 0) {
                float[] matrix = new float[9]; parentToRoot.getValues(matrix);
                double rootNorm = Math.max(Math.hypot(matrix[0], matrix[1]), Math.hypot(matrix[3], matrix[4]));
                tiltScale = (float) Math.min(tiltScale, density / (expansion * rootNorm));
                for (MotionClip boundary : clips) {
                    double x = Math.max(0, Math.min(boundary.box.left - boundary.area.left, boundary.area.right - boundary.box.right));
                    double y = Math.max(0, Math.min(boundary.box.top - boundary.area.top, boundary.area.bottom - boundary.box.bottom));
                    tiltScale = (float) Math.min(tiltScale, Math.min(x / (expansion * boundary.normX), y / (expansion * boundary.normY)));
                }
            }
            tiltScale = Math.max(0, Math.min(1, tiltScale));
            double padding = expansion * tiltScale;
            float[] delta = {tilt.x * TRANSLATION_DP * density, tilt.y * TRANSLATION_DP * density};
            float[] scratch = new float[2];
            // Axis-aligned parents converge in one pass; recheck rotated clips rather than guessing a safe pose.
            for (int pass = 0; pass < 4; pass++) for (MotionClip boundary : clips) boundary.limit(delta, padding, scratch);
            for (MotionClip boundary : clips) if (!boundary.fits(delta, padding, scratch)) {
                delta[0] = delta[1] = 0; tiltScale = 0; break;
            }
            parentInverse.mapVectors(delta);
            if (!Float.isFinite(delta[0]) || !Float.isFinite(delta[1])) return null;
            return new float[]{delta[0], delta[1], -tilt.y * TILT_DEGREES * tiltScale, tilt.x * TILT_DEGREES * tiltScale};
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private MotionClip clip(View ancestor, View root, RectF localBounds, Matrix toRoot, Matrix parentToRoot) {
        Matrix clipToRoot = restingMatrixToRoot(ancestor, root), fromRoot = new Matrix();
        if (clipToRoot == null || !clipToRoot.invert(fromRoot)) return null;
        RectF area = new RectF(0, 0, ancestor.getWidth(), ancestor.getHeight());
        if (ancestor instanceof ViewGroup && ((ViewGroup) ancestor).getClipToPadding()) {
            area.set(ancestor.getPaddingLeft(), ancestor.getPaddingTop(),
                    ancestor.getWidth() - ancestor.getPaddingRight(), ancestor.getHeight() - ancestor.getPaddingBottom());
        }
        Rect explicit = ancestor.getClipBounds();
        if (area.isEmpty() || explicit != null && !area.intersect(new RectF(explicit))) return null;
        Matrix local = new Matrix(toRoot); local.postConcat(fromRoot);
        RectF box = new RectF(localBounds); local.mapRect(box);
        Matrix expansion = new Matrix(parentToRoot); expansion.postConcat(fromRoot);
        float[] values = new float[9]; expansion.getValues(values);
        return new MotionClip(clipToRoot, fromRoot, area, box,
                Math.hypot(values[0], values[1]), Math.hypot(values[3], values[4]));
    }

    private static double tiltExpansion(View view, Vector tilt) {
        double angle = (Math.abs(tilt.x) + Math.abs(tilt.y)) * Math.toRadians(TILT_DEGREES);
        if (angle == 0) return 0;
        double x = Math.max(Math.abs(view.getPivotX()), Math.abs(view.getWidth() - view.getPivotX()));
        double y = Math.max(Math.abs(view.getPivotY()), Math.abs(view.getHeight() - view.getPivotY()));
        double radius = Math.hypot(x, y) * Math.max(1, Math.max(Math.abs(view.getScaleX()), Math.abs(view.getScaleY())));
        int dpi = view.getResources().getDisplayMetrics().densityDpi;
        // Use the smaller optical-unit distance to bound both software and hardware camera projections.
        double distance = Math.abs(view.getCameraDistance()) * Math.min(1, 72d / (dpi > 0 ? dpi : 160));
        double depth = radius * angle;
        if (!Double.isFinite(radius) || !Double.isFinite(distance) || distance <= depth) return Double.POSITIVE_INFINITY;
        return radius * angle * angle / 2 + radius * depth / (distance - depth);
    }

    private static final class MotionClip {
        final Matrix toRoot, fromRoot; final RectF area, box; final double normX, normY;
        MotionClip(Matrix toRoot, Matrix fromRoot, RectF area, RectF box, double normX, double normY) {
            this.toRoot = toRoot; this.fromRoot = fromRoot; this.area = area; this.box = box;
            this.normX = normX; this.normY = normY;
        }
        void limit(float[] delta, double expansion, float[] local) {
            fromRoot.mapVectors(local, 0, delta, 0, 1);
            float minX = (float) (area.left + expansion * normX - box.left), maxX = (float) (area.right - expansion * normX - box.right);
            float minY = (float) (area.top + expansion * normY - box.top), maxY = (float) (area.bottom - expansion * normY - box.bottom);
            local[0] = Math.max(minX, Math.min(maxX, local[0])); local[1] = Math.max(minY, Math.min(maxY, local[1]));
            toRoot.mapVectors(delta, 0, local, 0, 1);
        }
        boolean fits(float[] delta, double expansion, float[] local) {
            fromRoot.mapVectors(local, 0, delta, 0, 1);
            return Float.isFinite(local[0]) && Float.isFinite(local[1])
                    && box.left + local[0] - expansion * normX >= area.left - .001
                    && box.right + local[0] + expansion * normX <= area.right + .001
                    && box.top + local[1] - expansion * normY >= area.top - .001
                    && box.bottom + local[1] + expansion * normY <= area.bottom + .001;
        }
    }

    private static void requireMainThread() {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Glass motion requires the main thread");
    }

    private static long uptimeNanos() {
        // Choreographer and Handler use uptime, including when the window's clock is virtualized.
        return SystemClock.uptimeMillis() * 1_000_000L;
    }

    static Vector mapGravity(float x, float y, int rotation) {
        if (!validAxis(x) || !validAxis(y)) return new Vector(0, 0);
        return switch (rotation) {
            case 1 -> Vector.bounded(y / GRAVITY, x / GRAVITY);
            case 2 -> Vector.bounded(x / GRAVITY, -y / GRAVITY);
            case 3 -> Vector.bounded(-y / GRAVITY, -x / GRAVITY);
            default -> Vector.bounded(-x / GRAVITY, y / GRAVITY);
        };
    }

    private static boolean validAxis(float value) {
        return Float.isFinite(value) && Math.abs(value) <= 4 * GRAVITY;
    }

    static final class Vector {
        final float x, y;
        Vector(float x, float y) { this.x = x; this.y = y; }
        static Vector bounded(float x, float y) {
            if (!Float.isFinite(x) || !Float.isFinite(y)) return new Vector(0, 0);
            double length = Math.hypot(x, y);
            return length > 1 ? new Vector((float) (x / length), (float) (y / length)) : new Vector(x, y);
        }
    }

    static final class OrientationReference {
        private boolean calibrated;
        private int screenRotation;
        private double roll, pitch;
        void reset() { calibrated = false; }
        Vector accept(float x, float y, float z, int rotation) {
            if (!validAxis(x) || !validAxis(y) || !validAxis(z) || x * x + y * y + z * z < .01f) return null;
            float screenX, screenY;
            switch (rotation & 3) {
                case 1: screenX = -y; screenY = x; break;
                case 2: screenX = -x; screenY = -y; break;
                case 3: screenX = y; screenY = -x; break;
                default: screenX = x; screenY = y;
            }
            double nextRoll = Math.atan2(-screenX, Math.hypot(screenY, z)), nextPitch = Math.atan2(screenY, z);
            if (!calibrated || screenRotation != rotation) {
                calibrated = true; screenRotation = rotation; roll = nextRoll; pitch = nextPitch;
                return new Vector(0, 0);
            }
            double range = Math.toRadians(20);
            return Vector.bounded((float) (wrapped(nextRoll - roll) / range), (float) (wrapped(nextPitch - pitch) / range));
        }
        private static double wrapped(double angle) { return Math.atan2(Math.sin(angle), Math.cos(angle)); }
    }

    static final class MotionState {
        float x, y, targetX, targetY;
        long lastFrame = -1;
        boolean interacting;
        void accept(Vector target) {
            Vector bounded = Vector.bounded(target.x, target.y);
            if (Math.hypot(bounded.x - targetX, bounded.y - targetY) < .002) {
                return;
            }
            targetX = bounded.x; targetY = bounded.y;
        }
        boolean step(long time) {
            if (lastFrame < 0) { lastFrame = time; return false; }
            if (interacting || time - lastFrame < FRAME_NS || !needsFrame()) return false;
            double alpha = 1 - Math.exp(-(time - lastFrame) / 180_000_000.0);
            lastFrame = time;
            x += (targetX - x) * alpha; y += (targetY - y) * alpha;
            if (Math.hypot(targetX - x, targetY - y) < .0005) { x = targetX; y = targetY; }
            return true;
        }
        boolean needsFrame() { return !interacting && Math.hypot(targetX - x, targetY - y) >= .0005; }
        void setInteracting(boolean value, long time) { interacting = value; lastFrame = time; }
        void reset() { x = y = targetX = targetY = 0; lastFrame = -1; interacting = false; }
    }

    static final class GravityFilter {
        float x, y, z;
        long lastTime = -1;
        boolean accept(float x, float y, float z, long time, boolean fallback) {
            if (!validAxis(x) || !validAxis(y) || !validAxis(z) || x * x + y * y + z * z < .01f
                    || time < 0 || (lastTime >= 0 && time <= lastTime)) return false;
            double alpha = !fallback || lastTime < 0 ? 1 : 1 - Math.exp(-(time - lastTime) / 400_000_000.0);
            this.x += (x - this.x) * alpha; this.y += (y - this.y) * alpha; this.z += (z - this.z) * alpha;
            lastTime = time;
            return true;
        }
    }

    static final class Transform {
        final View view;
        final float[] baseline, last;
        final float rotation;
        final boolean[] owned = new boolean[4];
        boolean yielded;
        Transform(View view) {
            this.view = view;
            rotation = view.getRotation();
            baseline = values(); last = baseline.clone();
            yielded = ownTransientState(view) || hasAnimation();
            for (float value : baseline) if (!Float.isFinite(value)) yielded = true;
        }
        void apply(Vector tilt, float density) {
            Vector bounded = Vector.bounded(tilt.x, tilt.y);
            applyOffsets(new float[]{bounded.x * TRANSLATION_DP * density, bounded.y * TRANSLATION_DP * density,
                    -bounded.y * TILT_DEGREES, bounded.x * TILT_DEGREES});
        }
        void applyOffsets(float[] offsets) {
            if (yielded) return;
            float[] current = values();
            boolean changed = ownTransientState(view) || hasAnimation() || Float.compare(view.getRotation(), rotation) != 0;
            for (int i = 0; i < 4; i++) if (Float.compare(current[i], last[i]) != 0) changed = true;
            if (changed) { yielded = true; restore(); return; }
            for (int i = 0; i < 4; i++) {
                last[i] = baseline[i] + offsets[i];
                write(i, last[i]); owned[i] = true;
            }
        }
        void restore() {
            float[] current = values();
            for (int i = 0; i < 4; i++) {
                if (owned[i] && Float.compare(current[i], last[i]) == 0) write(i, baseline[i]);
                else if (owned[i]) yielded = true;
                owned[i] = false;
                last[i] = baseline[i];
            }
        }
        private boolean hasAnimation() { return view.getAnimation() != null && !view.getAnimation().hasEnded(); }
        private float[] values() { return new float[]{view.getTranslationX(), view.getTranslationY(), view.getRotationX(), view.getRotationY()}; }
        private void write(int property, float value) {
            switch (property) {
                case 0 -> view.setTranslationX(value);
                case 1 -> view.setTranslationY(value);
                case 2 -> view.setRotationX(value);
                case 3 -> view.setRotationY(value);
                default -> throw new IllegalArgumentException("Unknown transform property");
            }
        }
    }

    /** One hardware listener per process; each owner maps the sample for its own display. */
    private static final class SensorHub implements SensorEventListener {
        final SensorManager manager;
        final Sensor sensor;
        final boolean fallback;
        final IdentityHashMap<LiquidGlassMotion, Boolean> owners = new IdentityHashMap<>();
        final GravityFilter filter = new GravityFilter();
        boolean registered;
        SensorHub(Context context) {
            Context application = context.getApplicationContext();
            manager = (SensorManager) (application != null ? application : context).getSystemService(Context.SENSOR_SERVICE);
            Sensor gravity = manager != null ? manager.getDefaultSensor(Sensor.TYPE_GRAVITY) : null;
            fallback = gravity == null;
            sensor = gravity != null ? gravity : manager != null ? manager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) : null;
        }
        boolean add(LiquidGlassMotion owner) {
            if (manager == null || sensor == null) return false;
            if (!registered) {
                try { registered = manager.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME, owner.handler); }
                catch (RuntimeException ignored) { return false; }
            }
            if (registered) owners.put(owner, true);
            return registered;
        }
        void remove(LiquidGlassMotion owner) {
            owners.remove(owner);
            if (owners.isEmpty()) {
                if (registered) manager.unregisterListener(this);
                registered = false;
                if (sharedHub == this) sharedHub = null;
            }
        }
        @Override public void onSensorChanged(SensorEvent event) {
            if (!registered || event.values.length < 3 || !filter.accept(event.values[0], event.values[1], event.values[2], event.timestamp, fallback)) return;
            for (LiquidGlassMotion owner : new ArrayList<>(owners.keySet())) owner.receive(filter.x, filter.y, filter.z);
        }
        @Override public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    }
}
