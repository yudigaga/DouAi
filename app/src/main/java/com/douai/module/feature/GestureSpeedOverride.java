package com.douai.module.feature;

import java.lang.ref.WeakReference;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/** Keeps a gesture's speed choice attached to the real engine behind player facades. */
public final class GestureSpeedOverride implements AutoCloseable {
    @FunctionalInterface
    public interface ReflectiveCall {
        void run() throws ReflectiveOperationException;
    }

    private static final WeakHashMap<Object, GestureSpeedOverride> BINDINGS = new WeakHashMap<>();
    private static WeakReference<GestureSpeedOverride> globalOwner = new WeakReference<>(null);
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ThreadLocal<ScopeToken> FORWARDED = new ThreadLocal<>();
    private final Map<Object, Float> originalSpeeds = new WeakHashMap<>();
    private volatile float desired = 1f;
    private volatile Float deferredRestoration;
    private volatile boolean closed;

    public GestureSpeedOverride() { }

    /** Opts this session into future players; ordinary apply calls remain local. */
    public void registerGlobal() {
        synchronized (BINDINGS) {
            if (closed) throw new IllegalStateException("Gesture speed session is closed");
            globalOwner = new WeakReference<>(this);
        }
    }

    public void unregisterGlobal() {
        synchronized (BINDINGS) {
            if (globalOwner.get() == this) globalOwner.clear();
        }
    }

    /** Releases old facade bindings without ending a registered cross-video choice. */
    public void releaseBindings() {
        synchronized (BINDINGS) { BINDINGS.entrySet().removeIf(entry -> entry.getValue() == this); }
    }

    public void rememberOriginal(Object player, float original) {
        if (player == null || !validOriginal(original)) return;
        synchronized (BINDINGS) {
            if (!closed) originalSpeeds.putIfAbsent(player, original);
        }
    }

    /** Replaces a baseline only after the caller observed an independent external change. */
    public void updateOriginal(Object player, float original) {
        if (player == null || !validOriginal(original)) return;
        synchronized (BINDINGS) {
            if (!closed) originalSpeeds.put(player, original);
        }
    }

    public float restorationSpeed(Object player, float fallback) {
        synchronized (BINDINGS) {
            Float original = originalSpeeds.get(player);
            return original == null ? fallback : original;
        }
    }

    private static boolean validOriginal(float speed) {
        return Float.isFinite(speed) && speed >= 0.25f && speed <= 4f;
    }

    public void apply(float speed, ReflectiveCall action) throws ReflectiveOperationException {
        if (closed) throw new IllegalStateException("Gesture speed session is closed");
        if (!Float.isFinite(speed) || speed <= 0f) throw new IllegalArgumentException("Invalid playback speed");
        Set<Object> previousBindings = new HashSet<>();
        synchronized (BINDINGS) {
            BINDINGS.forEach((engine, owner) -> { if (owner == this) previousBindings.add(engine); });
        }
        float previous = desired;
        desired = speed;
        try { runScoped(action, false); }
        catch (ReflectiveOperationException | RuntimeException | Error failure) {
            desired = previous;
            synchronized (BINDINGS) {
                BINDINGS.entrySet().removeIf(entry -> entry.getValue() == this
                        && !previousBindings.contains(entry.getKey()));
            }
            throw failure;
        }
    }

    public void restore(ReflectiveCall action) throws ReflectiveOperationException {
        runScoped(action, true);
    }

    public float restorationSpeed(float fallback) {
        Float original = deferredRestoration;
        return original == null ? fallback : original;
    }

    private void runScoped(ReflectiveCall action, boolean restoring) throws ReflectiveOperationException {
        Scope previous = CURRENT.get();
        ScopeToken previousForwarded = FORWARDED.get();
        FORWARDED.remove();
        CURRENT.set(new Scope(this, restoring));
        try { action.run(); }
        finally {
            if (previous == null) CURRENT.remove();
            else CURRENT.set(previous);
            if (previousForwarded == null) FORWARDED.remove();
            else FORWARDED.set(previousForwarded);
        }
    }

    @FunctionalInterface
    interface ThrowingCall { Object run() throws Throwable; }

    /** Captures only an explicit module call; it never adopts the global facade fallback. */
    static ScopeToken captureScope() {
        ScopeToken forwarded = FORWARDED.get();
        if (forwarded != null) return forwarded;
        Scope scope = CURRENT.get();
        return scope == null ? null : new ScopeToken(scope.owner, scope.restoring);
    }

    static ScopeToken captureBound(Object facade) {
        synchronized (BINDINGS) {
            GestureSpeedOverride owner = BINDINGS.get(facade);
            return owner == null || owner.closed ? null : new ScopeToken(owner, false);
        }
    }

    static void runBound(Object facade, ReflectiveCall action) throws ReflectiveOperationException {
        GestureSpeedOverride owner;
        synchronized (BINDINGS) { owner = BINDINGS.get(facade); }
        if (owner == null || owner.closed) action.run();
        else owner.runScoped(action, false);
    }

    /** A queued SDK request can retain provenance without keeping its session alive. */
    static final class ScopeToken {
        private final WeakReference<GestureSpeedOverride> owner;
        private final boolean restoring;

        private ScopeToken(GestureSpeedOverride owner, boolean restoring) {
            this.owner = new WeakReference<>(owner);
            this.restoring = restoring;
        }

        Float desired() {
            GestureSpeedOverride current = owner.get();
            return current == null || current.closed ? null : current.desired;
        }

        boolean restoring() { return restoring; }

        Object withScope(ThrowingCall action) throws Throwable {
            Scope previous = CURRENT.get();
            ScopeToken previousForwarded = FORWARDED.get();
            GestureSpeedOverride current = owner.get();
            if (current == null) CURRENT.remove();
            else CURRENT.set(new Scope(current, restoring));
            // Even a closed/collected request retains its pass-through provenance, so
            // a different bound owner cannot rewrite a delayed restoration packet.
            FORWARDED.set(this);
            try { return action.run(); }
            finally {
                if (previous == null) CURRENT.remove();
                else CURRENT.set(previous);
                if (previousForwarded == null) FORWARDED.remove();
                else FORWARDED.set(previousForwarded);
            }
        }
    }

    static boolean inScope() { return CURRENT.get() != null; }

    static boolean restoring() {
        Scope scope = CURRENT.get();
        return scope != null && scope.restoring;
    }

    static boolean bypassSpeedWrite(Object engine) {
        Scope scope = CURRENT.get();
        synchronized (BINDINGS) {
            if (scope != null) {
                if (!scope.restoring && !scope.owner.closed && engine != null) {
                    BINDINGS.put(engine, scope.owner);
                }
                return true;
            }
            GestureSpeedOverride owner = BINDINGS.get(engine);
            return owner != null && !owner.closed;
        }
    }

    static Float speedFor(Object engine) {
        Scope scope = CURRENT.get();
        if (scope != null && scope.restoring) return null;
        synchronized (BINDINGS) {
            if (scope != null && !scope.owner.closed && engine != null) {
                BINDINGS.put(engine, scope.owner);
            }
            GestureSpeedOverride owner = scope == null ? globalOwner.get() : null;
            if (owner != null && !owner.closed && engine != null) BINDINGS.put(engine, owner);
            else owner = BINDINGS.get(engine);
            return owner == null || owner.closed ? null : owner.desired;
        }
    }

    static void rememberOriginalFor(Object engine, float original) {
        synchronized (BINDINGS) {
            GestureSpeedOverride owner = BINDINGS.get(engine);
            if (owner != null && !owner.closed) owner.rememberOriginal(engine, original);
        }
    }

    static boolean deferRestoration(Object engine, float original) {
        synchronized (BINDINGS) {
            GestureSpeedOverride owner = globalOwner.get();
            if (owner != null && !owner.closed && engine != null) BINDINGS.put(engine, owner);
            else owner = BINDINGS.get(engine);
            if (owner == null || owner.closed) return false;
            if (validOriginal(original)) {
                // The automatic layer knows the native baseline from before its own write.
                // It is more authoritative than a getter already showing the module speed.
                if (engine != null) owner.originalSpeeds.put(engine, original);
                owner.deferredRestoration = original;
            }
            return true;
        }
    }

    @Override public void close() {
        closed = true;
        synchronized (BINDINGS) {
            if (globalOwner.get() == this) globalOwner.clear();
            BINDINGS.entrySet().removeIf(entry -> entry.getValue() == this);
            originalSpeeds.clear();
        }
    }

    private static final class Scope {
        final GestureSpeedOverride owner;
        final boolean restoring;
        Scope(GestureSpeedOverride owner, boolean restoring) {
            this.owner = owner;
            this.restoring = restoring;
        }
    }
}
