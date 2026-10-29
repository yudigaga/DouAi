package com.douai.module.feature;

import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.function.Consumer;

/** Holds only the native detectors belonging to one selected child's touch stream. */
final class SpeedGestureNativeGate implements AutoCloseable {
    private final Method nativeTouch;
    private final Method[] detectorAccessors;
    private final Consumer<String> log;
    // Weak identity bindings: neither a lease nor a discarded tail retains a host View.
    private final List<Binding> bindings = new ArrayList<>();
    private XposedInterface.HookHandle handle;
    private boolean closed;

    private SpeedGestureNativeGate(Method nativeTouch, Method[] detectorAccessors, Consumer<String> log) {
        this.nativeTouch = nativeTouch;
        this.detectorAccessors = detectorAccessors;
        this.log = log == null ? ignored -> { } : log;
    }

    static SpeedGestureNativeGate install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, Consumer<String> log) throws Exception {
        Method touch = HostTargets.method(report, "adv_gesture_native_touch_detector", loader);
        Method video = HostTargets.method(report, "adv_gesture_video_container_detector", loader);
        Method legacy = HostTargets.method(report, "adv_gesture_legacy_container_detector", loader);
        if (Modifier.isStatic(touch.getModifiers()) || Modifier.isAbstract(touch.getModifiers())
                || touch.getReturnType() != void.class || touch.getParameterCount() != 1
                || touch.getParameterTypes()[0] != MotionEvent.class) {
            throw new IllegalStateException("Native speed touch detector relationship changed");
        }
        for (Method accessor : new Method[]{video, legacy}) {
            if (Modifier.isStatic(accessor.getModifiers()) || accessor.getParameterCount() != 0
                    || !"getDetector".equals(accessor.getName())
                    || accessor.getReturnType() != touch.getDeclaringClass()
                    || !View.class.isAssignableFrom(accessor.getDeclaringClass())) {
                throw new IllegalStateException("Native speed detector container relationship changed");
            }
        }
        SpeedGestureNativeGate gate = new SpeedGestureNativeGate(touch, new Method[]{video, legacy}, log);
        try {
            gate.handle = framework.hook(touch)
                    .setPriority(XposedInterface.PRIORITY_HIGHEST)
                    .setId("DouAi.Advanced.long_press_action_native_speed.0")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(gate::intercept);
            if (gate.handle == null) throw new IllegalStateException("Native speed touch hook unavailable");
            gate.record("installed", 1);
            return gate;
        } catch (Exception | Error failure) {
            gate.close();
            throw failure;
        }
    }

    Lease hold(View touch, MotionEvent down) {
        if (touch == null || down == null || down.getActionMasked() != MotionEvent.ACTION_DOWN) {
            return Lease.EMPTY;
        }
        synchronized (this) { if (closed) return Lease.EMPTY; }
        List<Object> detectors = new ArrayList<>();
        int failures = 0;
        for (View ancestor = touch; ancestor != null; ) {
            for (Method accessor : detectorAccessors) {
                if (!accessor.getDeclaringClass().isInstance(ancestor)) continue;
                try {
                    Object detector = accessor.invoke(ancestor);
                    if (nativeTouch.getDeclaringClass().isInstance(detector)
                            && !containsIdentity(detectors, detector)) detectors.add(detector);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    failures++;
                }
            }
            ViewParent parent = ancestor.getParent();
            ancestor = parent instanceof View && parent != ancestor ? (View) parent : null;
        }
        if (failures > 0) record("accessor_unavailable", failures);
        if (detectors.isEmpty()) {
            record("no_ancestor_detector", 0);
            return Lease.EMPTY;
        }
        List<Binding> held = new ArrayList<>();
        synchronized (this) {
            if (closed) return Lease.EMPTY;
            pruneCollected();
            for (Object detector : detectors) {
                Binding previous = find(detector);
                if (previous != null) remove(previous);
                Binding binding = new Binding(detector, down.getDownTime());
                bindings.add(binding);
                held.add(binding);
            }
        }
        record("held", held.size());
        return new Lease(this, held);
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        Object argument = chain.getArg(0);
        if (!(argument instanceof MotionEvent)) return chain.proceed();
        MotionEvent event = (MotionEvent) argument;
        String stage = null;
        boolean suppress = false;
        synchronized (this) {
            if (!closed) {
                pruneCollected();
                Binding binding = find(chain.getThisObject());
                if (binding != null) {
                    int action = event.getActionMasked();
                    if (binding.discarded && action == MotionEvent.ACTION_DOWN) {
                        remove(binding);
                        stage = "fresh_down_released";
                    } else if (event.getDownTime() != binding.downTime) {
                        // A lost terminal event must not capture the next touch stream.
                        if (action == MotionEvent.ACTION_DOWN) {
                            remove(binding);
                            stage = "fresh_down_released";
                        }
                    } else {
                        suppress = true;
                        if (!binding.discarded && binding.down == null && action == MotionEvent.ACTION_DOWN) {
                            // Capture the detector's own coordinates, never the child's DOWN.
                            binding.down = MotionEvent.obtain(event);
                            stage = "native_down_held";
                        }
                        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                            binding.terminalSeen = true;
                            if (binding.discarded) {
                                // Nested containers may share a detector and feed the same
                                // terminal event twice. Keep the weak tail until a fresh DOWN.
                                stage = "discarded_tail_finished";
                            }
                        }
                    }
                }
            }
        }
        if (stage != null) record(stage, 1);
        return suppress ? null : chain.proceed();
    }

    private void finish(List<Binding> held, boolean replay) {
        List<Replay> pending = new ArrayList<>();
        int discarded = 0;
        synchronized (this) {
            for (Binding binding : held) {
                // An old lease can never remove a replacement made by a newer hold.
                if (!bindings.contains(binding)) continue;
                if (replay) {
                    Object detector = binding.detector.get();
                    MotionEvent down = binding.down;
                    binding.down = null;
                    remove(binding);
                    if (detector != null && down != null) {
                        pending.add(new Replay(detector, down, binding.downTime, binding.terminalSeen));
                    }
                    else if (down != null) down.recycle();
                } else {
                    binding.discarded = true;
                    binding.recycleDown();
                    discarded++;
                }
            }
        }
        // All bindings of this lease are detached before any native body can call another detector.
        int replayed = 0;
        int failures = 0;
        int failedTails = 0;
        try {
            for (Replay entry : pending) {
                try {
                    synchronized (this) {
                        if (closed || find(entry.detector) != null) continue;
                    }
                    nativeTouch.invoke(entry.detector, entry.down);
                    replayed++;
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    failures++;
                    if (discardFailedTail(entry)) failedTails++;
                } finally {
                    entry.recycleDown();
                }
            }
        } finally {
            for (Replay entry : pending) entry.recycleDown();
        }
        record(replay ? "replayed" : "discarded", replay ? replayed : discarded);
        if (failures > 0) record("replay_failed", failures);
        if (failedTails > 0) record("replay_failed_tail_discarded", failedTails);
    }

    private synchronized boolean discardFailedTail(Replay failed) {
        // Never overwrite a newer hold, even if it belongs to the same detector.
        if (closed || failed.terminalSeen || find(failed.detector) != null) return false;
        Binding tail = new Binding(failed.detector, failed.downTime);
        tail.discarded = true;
        bindings.add(tail);
        return true;
    }

    private Binding find(Object detector) {
        for (Binding binding : bindings) if (binding.detector.get() == detector) return binding;
        return null;
    }

    private void remove(Binding binding) {
        bindings.remove(binding);
        binding.recycleDown();
        binding.detector.clear();
    }

    private void pruneCollected() {
        for (Iterator<Binding> iterator = bindings.iterator(); iterator.hasNext(); ) {
            Binding binding = iterator.next();
            if (binding.detector.get() != null) continue;
            iterator.remove();
            binding.recycleDown();
        }
    }

    private static boolean containsIdentity(List<Object> values, Object expected) {
        for (Object value : values) if (value == expected) return true;
        return false;
    }

    private void record(String stage, int count) {
        try { DouAiLog.event("gesture", "native_touch_gate", stage, "detectors=" + count); }
        catch (Throwable ignored) { }
        try { log.accept("Native speed touch gate " + stage + ";detectors=" + count); }
        catch (Throwable ignored) { }
    }

    @Override public void close() {
        XposedInterface.HookHandle installed;
        synchronized (this) {
            if (closed) return;
            closed = true;
            for (Binding binding : bindings) {
                binding.recycleDown();
                binding.detector.clear();
            }
            bindings.clear();
            installed = handle;
            handle = null;
        }
        if (installed != null) {
            try { installed.unhook(); }
            catch (Throwable failure) { record("unhook_failed", 1); }
        }
        record("closed", 0);
    }

    static final class Lease {
        private static final Lease EMPTY = new Lease(null, Collections.emptyList());
        private final WeakReference<SpeedGestureNativeGate> owner;
        private final List<Binding> held;
        private boolean finished;

        private Lease(SpeedGestureNativeGate owner, List<Binding> held) {
            this.owner = new WeakReference<>(owner);
            this.held = new ArrayList<>(held);
        }

        synchronized boolean isEmpty() { return held.isEmpty(); }

        void replayDown() { finish(true); }
        void discard() { finish(false); }

        private void finish(boolean replay) {
            List<Binding> current;
            synchronized (this) {
                if (finished) return;
                finished = true;
                current = new ArrayList<>(held);
                held.clear();
            }
            SpeedGestureNativeGate gate = owner.get();
            owner.clear();
            if (gate != null) gate.finish(current, replay);
        }
    }

    private static final class Binding {
        final WeakReference<Object> detector;
        final long downTime;
        MotionEvent down;
        boolean discarded;
        boolean terminalSeen;

        Binding(Object detector, long downTime) {
            this.detector = new WeakReference<>(detector);
            this.downTime = downTime;
        }

        void recycleDown() {
            if (down != null) {
                down.recycle();
                down = null;
            }
        }
    }

    private static final class Replay {
        final Object detector;
        MotionEvent down;
        final long downTime;
        final boolean terminalSeen;

        Replay(Object detector, MotionEvent down, long downTime, boolean terminalSeen) {
            this.detector = detector;
            this.down = down;
            this.downTime = downTime;
            this.terminalSeen = terminalSeen;
        }

        void recycleDown() {
            if (down != null) {
                down.recycle();
                down = null;
            }
        }
    }
}
