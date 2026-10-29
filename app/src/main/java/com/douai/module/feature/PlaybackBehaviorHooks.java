package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.PlaybackSpeedPresets;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Applies and restores module-owned player speeds. Auto-slide belongs to AutoPlayHook. */
public final class PlaybackBehaviorHooks implements AutoCloseable {
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<Object, SpeedState> speeds = new WeakHashMap<>();
    private final Map<Object, SpeedState> managerSpeeds = new WeakHashMap<>();
    private final Set<Object> seenPlayers = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<Object> seenManagers = Collections.newSetFromMap(new WeakHashMap<>());
    private final ThreadLocal<Boolean> ownSpeedCall = new ThreadLocal<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private Method setSpeed;
    private Method getSpeed;
    private Method managerSetSpeed;
    private Method managerGetSpeed;
    private HostPlaybackSpeedAccess speedReader;
    private HostPlaybackSpeedAccess managerSpeedReader;
    private EnginePlaybackSpeedHooks engineSpeedHooks;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private PlaybackBehaviorHooks(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static PlaybackBehaviorHooks install(XposedInterface framework, HostScanReport report,
                                                 ClassLoader loader, Context context,
                                                 LocalOptions options, Consumer<String> log) {
        PlaybackBehaviorHooks hooks = new PlaybackBehaviorHooks(options, log);
        hooks.installSpeed(framework, report, loader);
        if (report != null && report.getResults().containsKey("adv_player_engine_set_params")) {
            try {
                hooks.engineSpeedHooks = EnginePlaybackSpeedHooks.install(framework, report, loader, hooks.log);
            } catch (Exception | LinkageError failure) {
                DouAiLog.failure("auto_speed", "engine_speed_guard_install", failure);
                try { hooks.log.accept("Engine speed guard unavailable: " + failure.getClass().getSimpleName()); }
                catch (Throwable ignored) { }
            }
        }
        return hooks;
    }

    public boolean supports(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group != null && group.isActive();
    }

    private boolean enabled(String key) {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(key) && supports(key);
    }

    static float playbackSpeed(LocalOptions options) {
        return PlaybackSpeedPresets.normalize(options.decimal("playback_speed", PlaybackSpeedPresets.DEFAULT_SPEED));
    }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        onMain(() -> {
            if (closed) return;
            if (!enabled("auto_speed")) restoreSpeeds();
            applySeen(seenPlayers, this::applySpeed);
            applySeen(seenManagers, this::applyManagerSpeed);
        });
    }

    private void installSpeed(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "auto_speed";
        BehaviorHookGroup group = newGroup(key);
        try {
            setSpeed = HostTargets.method(report, "adv_player_set_speed", loader);
            getSpeed = HostTargets.method(report, "adv_player_get_speed", loader);
            Method resume = HostTargets.method(report, "adv_player_resume", loader);
            Method play = HostTargets.method(report, "adv_player_play", loader);
            managerSetSpeed = HostTargets.method(report, "adv_player_manager_set_speed", loader);
            managerGetSpeed = HostTargets.method(report, "adv_player_manager_get_speed", loader);
            speedReader = HostPlaybackSpeedAccess.resolve(getSpeed, report, loader);
            managerSpeedReader = HostPlaybackSpeedAccess.resolve(managerGetSpeed, report, loader);
            Method managerResume = HostTargets.method(report, "adv_player_manager_resume", loader);
            Method managerPlay = HostTargets.method(report, "adv_player_manager_play", loader);
            if (!setSpeed.getDeclaringClass().equals(getSpeed.getDeclaringClass())
                    || !setSpeed.getDeclaringClass().equals(resume.getDeclaringClass())
                    || !setSpeed.getDeclaringClass().equals(play.getDeclaringClass())
                    || setSpeed.getParameterCount() != 1 || setSpeed.getParameterTypes()[0] != float.class) {
                throw new IllegalStateException("Feed player speed relationship changed");
            }
            validateManagerSpeedMethods(managerSetSpeed, managerGetSpeed, managerResume, managerPlay);
            group.add(framework, setSpeed, chain -> interceptSpeedWrite(chain, group, key, speeds, seenPlayers));
            for (Method target : new Method[] { resume, play }) {
                group.add(framework, target, chain -> {
                    Object result = chain.proceed();
                    applySpeed(chain.getThisObject());
                    return result;
                });
            }
            group.add(framework, managerSetSpeed,
                    chain -> interceptSpeedWrite(chain, group, key, managerSpeeds, seenManagers));
            for (Method target : new Method[] { managerResume, managerPlay }) {
                group.add(framework, target, chain -> {
                    Object result = chain.proceed();
                    applyManagerSpeed(chain.getThisObject());
                    return result;
                });
            }
            reportInstalled(key);
        } catch (Throwable failure) { failSpeed(group, failure); }
    }

    private static void validateManagerSpeedMethods(Method setter, Method getter,
                                                   Method resume, Method play) {
        Class<?> manager = setter.getDeclaringClass();
        if (!manager.equals(getter.getDeclaringClass()) || !manager.equals(resume.getDeclaringClass())
                || !manager.equals(play.getDeclaringClass()) || setter.getReturnType() != void.class
                || setter.getParameterCount() != 1 || setter.getParameterTypes()[0] != float.class
                || resume.getReturnType() != void.class || resume.getParameterCount() != 0
                || play.getReturnType() != String.class) {
            throw new IllegalStateException("Panel PlayerManager speed relationship changed");
        }
    }

    private Object interceptSpeedWrite(XposedInterface.Chain chain, BehaviorHookGroup group,
                                      String key, Map<Object, SpeedState> states, Set<Object> seen) throws Throwable {
        Object player = chain.getThisObject();
        rememberSeen(seen, player);
        float requested;
        try { requested = ((Number) chain.getArg(0)).floatValue(); }
        catch (RuntimeException failure) {
            failSpeed(group, failure);
            return chain.proceed();
        }

        if (GestureSpeedOverride.inScope()) {
            DouAiLog.event("auto_speed", "decision", "manual_gesture_scope", "original_speed_write_forwarded=true");
            GestureSpeedOverride.bypassSpeedWrite(player);
            return chain.proceed();
        }
        Float manual = GestureSpeedOverride.speedFor(player);
        if (manual != null) {
            if (!Boolean.TRUE.equals(ownSpeedCall.get())) {
                GestureSpeedOverride.rememberOriginalFor(player, requested);
                updateOriginal(states, player, requested);
            }
            if (requested != manual) {
                DouAiLog.event("auto_speed", "speed_write", "persistent_manual_speed_rewritten",
                        "requested_speed=" + requested + ";configured_speed=" + manual
                                + ";readback=false;result_scope=current_player");
            }
            Object[] arguments = chain.getArgs().toArray();
            arguments[0] = manual;
            GestureSpeedOverride.ScopeToken scope = GestureSpeedOverride.captureBound(player);
            return scope == null ? chain.proceed(arguments) : scope.withScope(() -> chain.proceed(arguments));
        }
        if (Boolean.TRUE.equals(ownSpeedCall.get()) || !enabled(key)) {
            DouAiLog.event("auto_speed", "decision", Boolean.TRUE.equals(ownSpeedCall.get()) ? "owned_reentry" : "disabled",
                    "original_speed_write_forwarded=true");
            return chain.proceed();
        }

        Object[] arguments = chain.getArgs().toArray();
        try {
            synchronized (states) {
                SpeedState state = states.get(player);
                if (state == null) {
                    state = new SpeedState(requested);
                    states.put(player, state);
                } else state.original = requested;
                state.applied = playbackSpeed(options);
                arguments[0] = state.applied;
            }
        } catch (RuntimeException failure) {
            failSpeed(group, failure);
            return chain.proceed();
        }
        DouAiLog.Operation operation = DouAiLog.begin("auto_speed", "native_speed_write");
        operation.stage("rewrite_speed", "requested_speed=" + requested + ";configured_speed=" + arguments[0]);
        try {
            Object result = chain.proceed(arguments);
            operation.finish(DouAiLog.Outcome.UNCONFIRMED, "speed_write_forwarded", "setter_returned=true;readback=false");
            return result;
        } catch (Throwable failure) {
            operation.error("rewrite_speed", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "speed_setter_threw", "readback=false");
            throw failure;
        }
    }

    private static void updateOriginal(Map<Object, SpeedState> states, Object player, float requested) {
        synchronized (states) {
            SpeedState state = states.get(player);
            if (state != null) state.original = requested;
        }
    }

    private void applySpeed(Object player) {
        applySpeed(player, speeds, seenPlayers, speedReader, this::callSetSpeed);
    }

    private void applyManagerSpeed(Object player) {
        applySpeed(player, managerSpeeds, seenManagers, managerSpeedReader, this::callManagerSetSpeed);
    }

    private void applySpeed(Object player, Map<Object, SpeedState> states, Set<Object> seen, HostPlaybackSpeedAccess getter,
                            SpeedWriter writer) {
        if (player == null || closed || GestureSpeedOverride.restoring()) return;
        rememberSeen(seen, player);
        try {
            Float manual = GestureSpeedOverride.speedFor(player);
            if (manual != null) {
                boolean needsWrite = true;
                try {
                    float current = getter.read(player);
                    GestureSpeedOverride.rememberOriginalFor(player, current);
                    needsWrite = getter.readEffective(player) != manual;
                }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (needsWrite) GestureSpeedOverride.runBound(player, () -> writer.write(player, manual));
                return;
            }
            if (!enabled("auto_speed")) return;
            synchronized (states) {
                SpeedState state = states.get(player);
                float current = getter.read(player);
                if (state == null) {
                    state = new SpeedState(current);
                    states.put(player, state);
                } else if (current != state.applied) state.original = current;
                float next = playbackSpeed(options);
                if (current != next) {
                    writer.write(player, next);
                    DouAiLog.event("auto_speed", "speed_write", "configured_speed_submitted",
                            "baseline=" + current + ";requested=" + next + ";readback=false;business_result=unconfirmed");
                } else DouAiLog.event("auto_speed", "speed_observed", "configured_speed_already_observed",
                        "actual=" + current + ";requested=" + next + ";result_scope=current_getter");
                state.applied = next;
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            failSpeed(groups.get("auto_speed"), failure);
        }
    }

    private void callSetSpeed(Object player, float speed) throws ReflectiveOperationException {
        callSetSpeed(setSpeed, player, speed);
    }

    private void callManagerSetSpeed(Object player, float speed) throws ReflectiveOperationException {
        callSetSpeed(managerSetSpeed, player, speed);
    }

    private void callSetSpeed(Method setter, Object player, float speed) throws ReflectiveOperationException {
        Boolean previous = ownSpeedCall.get();
        ownSpeedCall.set(true);
        try { setter.invoke(player, speed); }
        finally {
            if (previous == null) ownSpeedCall.remove();
            else ownSpeedCall.set(previous);
        }
    }

    private void failSpeed(BehaviorHookGroup group, Throwable failure) {
        DouAiLog.failure("auto_speed", "apply_or_restore_speed", failure);
        if (group != null) group.fail(failure);
        onMain(this::restoreSpeeds);
    }

    private void restoreSpeeds() {
        restoreSpeeds(speeds, speedReader, setSpeed);
        restoreSpeeds(managerSpeeds, managerSpeedReader, managerSetSpeed);
    }

    private void restoreSpeeds(Map<Object, SpeedState> states, HostPlaybackSpeedAccess getter, Method setter) {
        synchronized (states) {
            for (Map.Entry<Object, SpeedState> entry : new ArrayList<>(states.entrySet())) {
                if (entry.getKey() == null || getter == null || setter == null) continue;
                try {
                    if (GestureSpeedOverride.deferRestoration(entry.getKey(), entry.getValue().original)) continue;
                    float actual = getter.read(entry.getKey());
                    if (actual == entry.getValue().applied) {
                        callSetSpeed(setter, entry.getKey(), entry.getValue().original);
                    }
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    try { log.accept("Could not restore playback speed: " + failure.getMessage()); }
                    catch (Throwable ignored) { }
                }
            }
            states.clear();
        }
    }

    private static void rememberSeen(Set<Object> seen, Object player) {
        if (player != null) synchronized (seen) { seen.add(player); }
    }

    private void applySeen(Set<Object> seen, SpeedApplier applier) {
        ArrayList<Object> players;
        synchronized (seen) { players = new ArrayList<>(seen); }
        for (Object player : players) if (player != null) applier.apply(player);
    }

    private BehaviorHookGroup newGroup(String key) {
        BehaviorHookGroup group = new BehaviorHookGroup(key, log);
        groups.put(key, group);
        return group;
    }

    private void reportInstalled(String key) {
        try { log.accept("Advanced feature registered: " + key); }
        catch (Throwable ignored) { }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (BehaviorHookGroup group : groups.values()) group.close();
        if (engineSpeedHooks != null) engineSpeedHooks.close();
        engineSpeedHooks = null;
        onMain(this::restoreSpeeds);
    }

    private static final class SpeedState {
        float original;
        float applied;
        SpeedState(float original) { this.original = original; this.applied = original; }
    }

    @FunctionalInterface private interface SpeedWriter {
        void write(Object player, float speed) throws ReflectiveOperationException;
    }

    @FunctionalInterface private interface SpeedApplier { void apply(Object player); }

}
