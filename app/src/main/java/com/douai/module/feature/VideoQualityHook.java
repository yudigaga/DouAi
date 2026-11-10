package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import io.github.libxposed.api.XposedInterface;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Maintains the highest native-supported quality without adding a menu or inventing streams. */
public final class VideoQualityHook implements AutoCloseable {
    static final long POLL_MS = 1_000;
    static final long RETRY_MS = 2_000;
    static final int MAX_ATTEMPTS = 3;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final WeakHashMap<Object, Binding> bindings = new WeakHashMap<>();
    private final BehaviorHookGroup hooks;
    private final Access access;
    private final Method playerGate, mp4Gate;
    private volatile LocalOptions options;
    private volatile boolean closed, playerInstalled;

    static abstract class Access {
        abstract boolean visible(Object component) throws Exception;
        abstract String source(Object component) throws Exception;
        abstract List<?> supported(Object component) throws Exception;
        abstract Object highest(List<?> levels) throws Exception;
        abstract Object current(Object component) throws Exception;
        abstract boolean apply(Object component, Object target) throws Exception;
    }

    private VideoQualityHook(LocalOptions options, Access access, Method player, Method mp4, Consumer<String> log) {
        this.options = options;
        this.access = access;
        playerGate = player;
        mp4Gate = mp4;
        hooks = new BehaviorHookGroup("video_quality", log == null ? ignored -> { } : log);
    }

    public static VideoQualityHook installEarly(XposedInterface framework, ClassLoader loader,
                                                LocalOptions options, Consumer<String> log) throws Exception {
        NativeVideoQuality nativeAccess = NativeVideoQuality.resolve(loader);
        return installEarlyForTargets(framework, options, log, nativeAccess, nativeAccess.factoryMeet,
                nativeAccess.resume, nativeAccess.pageSelected, nativeAccess.pause,
                nativeAccess.playerGate, nativeAccess.mp4Gate);
    }

    static VideoQualityHook installEarlyForTargets(XposedInterface framework, LocalOptions options, Consumer<String> log,
            Access access, Method factory, Method resume, Method selected, Method pause, Method player, Method mp4) throws Exception {
        if (framework == null || access == null) throw new IllegalArgumentException("Quality bindings are required");
        validate(factory, boolean.class, false, 1);
        validate(resume, void.class, false, 1);
        validate(selected, void.class, false, 1);
        validate(pause, void.class, false, 1);
        if (selected.getParameterTypes()[0] != int.class
                || resume.getParameterTypes()[0] != pause.getParameterTypes()[0])
            throw new NoSuchMethodException("Quality lifecycle contract changed");
        validate(player, boolean.class, true, 0);
        validate(mp4, boolean.class, true, 0);
        VideoQualityHook feature = new VideoQualityHook(options, access, player, mp4, log);
        try {
            feature.hooks.add(framework, factory, chain -> {
                Object result = chain.proceed();
                return feature.enabled() ? Boolean.TRUE : result;
            });
            for (Method method : new Method[]{resume, selected}) {
                feature.hooks.add(framework, method, chain -> {
                    Object result = chain.proceed();
                    feature.activate(chain.getThisObject());
                    return result;
                });
            }
            feature.hooks.add(framework, pause, chain -> {
                Object result = chain.proceed();
                Object component = chain.getThisObject();
                feature.onMain(() -> {
                    Binding binding = feature.bindings.get(component);
                    if (binding != null) binding.stop();
                });
                return result;
            });
            return feature;
        } catch (Exception | LinkageError failure) {
            feature.close();
            throw failure;
        }
    }

    /** Player classes are hooked only after the host Application has finished initialization. */
    public synchronized void installPlayer(XposedInterface framework) throws Exception {
        if (closed) throw new IllegalStateException("Quality hook is closed");
        if (playerInstalled) return;
        try {
            for (Method method : new Method[]{playerGate, mp4Gate}) {
                hooks.add(framework, method, chain -> {
                    Object result = chain.proceed();
                    return enabled() ? Boolean.TRUE : result;
                });
            }
            playerInstalled = true;
            onMain(this::refreshBindings);
        } catch (Exception | LinkageError failure) {
            close();
            throw failure;
        }
    }

    private void activate(Object component) {
        onMain(() -> {
            if (closed || component == null) return;
            Binding binding = bindings.get(component);
            if (binding == null) {
                binding = new Binding(component);
                bindings.put(component, binding);
            }
            binding.active = true;
            binding.errors = 0;
            binding.attempts = 0;
            binding.schedule(0);
        });
    }

    public void setOptions(LocalOptions next) {
        if (closed) return;
        options = next;
        onMain(this::refreshBindings);
    }

    private void refreshBindings() {
        for (Binding binding : new ArrayList<>(bindings.values())) {
            main.removeCallbacks(binding.tick);
            if (binding.active && enabled() && playerInstalled) binding.schedule(0);
        }
    }

    private boolean enabled() {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(LocalOptions.LOCK_HIGHEST_VIDEO_QUALITY);
    }

    public boolean isActive() { return !closed && playerInstalled && hooks.isActive(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hooks.close();
        onMain(() -> {
            for (Binding binding : bindings.values()) binding.stop();
            bindings.clear();
        });
    }

    private final class Binding {
        final WeakReference<Object> component;
        final Runnable tick = this::run;
        boolean active;
        String source;
        Object target;
        int attempts, errors;
        long changedAt, attemptedAt;

        Binding(Object component) { this.component = new WeakReference<>(component); }

        void stop() { active = false; main.removeCallbacks(tick); }

        void schedule(long delay) {
            main.removeCallbacks(tick);
            if (active && enabled() && playerInstalled && component.get() != null) main.postDelayed(tick, delay);
        }

        void run() {
            Object owner = component.get();
            if (owner == null || !active || !enabled() || !playerInstalled) return;
            try {
                if (!access.visible(owner)) { stop(); return; }
                String nextSource = access.source(owner);
                long now = SystemClock.elapsedRealtime();
                if (!Objects.equals(source, nextSource)) {
                    source = nextSource;
                    target = null;
                    attempts = 0;
                    changedAt = now;
                }
                if (source == null) { errors = 0; schedule(POLL_MS); return; }
                List<?> levels = access.supported(owner);
                Object highest = access.highest(levels);
                if (!Objects.equals(highest, target)) { target = highest; attempts = 0; }
                if (highest == null) {
                    event("not_supported", "native_strategy_retained=true");
                } else {
                    Object current = access.current(owner);
                    Object observedBest = current == null ? null : access.highest(java.util.Arrays.asList(current, highest));
                    if (current != null && Objects.equals(observedBest, current)) {
                        attempts = 0;
                        event("highest_observed", "native_readback=at_least_target;physical_playback=unverified");
                    } else if (current != null && now - changedAt >= 500 && attempts < MAX_ATTEMPTS
                            && (attempts == 0 || now - attemptedAt >= RETRY_MS)
                            && enabled() && access.visible(owner) && source.equals(access.source(owner))) {
                        attemptedAt = now;
                        attempts++;
                        boolean requested = access.apply(owner, highest);
                        event(requested ? "highest_requested" : "player_unavailable",
                                "attempt=" + attempts + ";supported_count=" + (levels == null ? 0 : levels.size())
                                        + ";native_readback=pending");
                    } else if (attempts >= MAX_ATTEMPTS) {
                        event("retry_limit", "native_fallback_retained=true;readback=unconfirmed");
                    }
                }
                errors = 0;
            } catch (Exception | LinkageError unavailable) {
                errors++;
                event("source_unavailable", "error_type=" + unavailable.getClass().getSimpleName());
                if (errors >= MAX_ATTEMPTS) { stop(); return; }
            }
            schedule(POLL_MS);
        }
    }

    private void onMain(Runnable task) { if (Looper.myLooper() == Looper.getMainLooper()) task.run(); else main.post(task); }

    private static void validate(Method method, Class<?> result, boolean isStatic, int parameters) throws NoSuchMethodException {
        if (method == null || method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic
                || method.getParameterCount() != parameters) throw new NoSuchMethodException("Quality hook contract changed");
        method.setAccessible(true);
    }

    private static void event(String reason, String evidence) {
        try { DouAiLog.event("video_quality", "quality_lock", reason, evidence); }
        catch (RuntimeException ignored) { }
    }
}
