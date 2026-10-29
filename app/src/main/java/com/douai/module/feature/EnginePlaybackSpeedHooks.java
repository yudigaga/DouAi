package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.ReferenceQueue;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** Carries proven gesture ownership through SDK queues to the exact native player. */
final class EnginePlaybackSpeedHooks implements AutoCloseable {
    private static final int PLAYBACK_SPEED_OPTION = 60;
    private final Method engineSetter;
    private final Method getSpeed;
    private final Method setSpeed;
    private final OriginSetter originSetter;
    private final Consumer<String> log;
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final WeakIdentityTokens engineTokens = new WeakIdentityTokens();
    private final WeakIdentityTokens paramsTokens = new WeakIdentityTokens();
    private final WeakIdentityTokens mediaTokens = new WeakIdentityTokens();
    private final WeakIdentityTokens nativeTokens = new WeakIdentityTokens();
    private final ThreadLocal<CachedFrame> cachedFrames = new ThreadLocal<>();
    private volatile boolean closed;

    @FunctionalInterface
    private interface OriginSetter { Object invoke(Object player, Object params) throws Throwable; }

    private EnginePlaybackSpeedHooks(Method engine, Method getter, Method setter, OriginSetter origin,
                                     Consumer<String> log) {
        engineSetter = engine;
        getSpeed = getter;
        setSpeed = setter;
        originSetter = origin;
        this.log = log == null ? ignored -> { } : log;
    }

    static EnginePlaybackSpeedHooks install(XposedInterface framework, HostScanReport report,
                                            ClassLoader loader, Consumer<String> log) throws Exception {
        Method engine = HostTargets.method(report, "adv_player_engine_set_params", loader);
        Method cached = HostTargets.method(report, "adv_player_cached_engine_set_params", loader);
        Method media = HostTargets.method(report, "adv_player_media_set_params", loader);
        Method nativeTarget = HostTargets.method(report, "adv_player_native_float_option", loader);
        validateSetter(engine);
        validateSetter(cached);
        validateSetter(media);
        Class<?> params = engine.getParameterTypes()[0];
        if (cached.getParameterTypes()[0] != params || media.getParameterTypes()[0] != params
                || cached.getDeclaringClass() == engine.getDeclaringClass()
                || !engine.getDeclaringClass().isAssignableFrom(cached.getDeclaringClass())
                || Modifier.isStatic(nativeTarget.getModifiers())
                || Modifier.isAbstract(nativeTarget.getModifiers())
                || nativeTarget.getReturnType() != int.class || nativeTarget.getParameterCount() != 2
                || nativeTarget.getParameterTypes()[0] != int.class
                || nativeTarget.getParameterTypes()[1] != float.class) {
            throw new IllegalStateException("Playback engine setter relationship changed");
        }
        Method getter = params.getMethod("getSpeed");
        Method setter = params.getMethod("setSpeed", float.class);
        if (Modifier.isStatic(getter.getModifiers()) || getter.getReturnType() != float.class
                || getter.getParameterCount() != 0 || Modifier.isStatic(setter.getModifiers())
                || setter.getReturnType() != params) {
            throw new IllegalStateException("Playback parameter relationship changed");
        }
        var invoker = framework.getInvoker(engine).setType(XposedInterface.Invoker.Type.ORIGIN);
        EnginePlaybackSpeedHooks hooks = new EnginePlaybackSpeedHooks(engine,
                getter, setter, (player, value) -> invoker.invokeSpecial(player, value), log);
        try {
            hooks.add(framework, engine, "engine_params", hooks::interceptEngine);
            hooks.add(framework, cached, "cached_params", hooks::interceptCached);
            hooks.add(framework, media, "media_params", hooks::interceptMedia);
            hooks.add(framework, nativeTarget, "native_option", hooks::interceptNative);
            hooks.deoptimizeSdkCallers(framework, report, loader);
            hooks.record("installed", "all", Float.NaN, null, false, false, null);
            return hooks;
        } catch (Exception | Error failure) {
            hooks.close();
            throw failure;
        }
    }

    private static void validateSetter(Method target) {
        if (Modifier.isStatic(target.getModifiers()) || Modifier.isAbstract(target.getModifiers())
                || target.getReturnType() != void.class || target.getParameterCount() != 1
                || target.getParameterTypes()[0].isPrimitive()) {
            throw new IllegalStateException("Playback parameter setter relationship changed");
        }
    }

    private void deoptimizeSdkCallers(XposedInterface framework, HostScanReport report,
                                      ClassLoader loader) throws Exception {
        String[] ids = {"adv_player_sdk_base_params_caller", "adv_player_sdk_speed_params_caller",
                "adv_player_sdk_float_option_caller", "adv_player_sdk_media_params_caller"};
        boolean present = false;
        for (String id : ids) present |= report.getResults().containsKey(id);
        if (!present) return; // Older local fixtures do not describe the SDK caller plan.
        List<Method> callers = new ArrayList<>();
        for (String id : ids) {
            Method caller = HostTargets.method(report, id, loader);
            int modifiers = caller.getModifiers();
            if (Modifier.isAbstract(modifiers) || Modifier.isNative(modifiers) || Modifier.isStatic(modifiers)) {
                throw new IllegalStateException("Playback SDK caller relationship changed");
            }
            callers.add(caller);
        }
        for (int index = 0; index < ids.length; index++) {
            boolean deoptimized = framework.deoptimize(callers.get(index));
            String stage = deoptimized ? "caller_deoptimized" : "caller_deopt_unconfirmed";
            String evidence = "caller=" + ids[index] + ";deoptimized=" + deoptimized
                    + ";readback=false;result_scope=local_hook_coverage;business_result=unconfirmed";
            try { DouAiLog.event("auto_speed", "engine_speed_install", stage, evidence); }
            catch (Throwable ignored) { }
            try { log.accept("Playback SDK " + stage + ";" + evidence); }
            catch (Throwable ignored) { }
        }
    }

    private void add(XposedInterface framework, Method target, String id,
                     XposedInterface.Hooker hooker) {
        XposedInterface.HookHandle handle = framework.hook(target)
                .setId("DouAi.Advanced.auto_speed." + id)
                .setPriority(XposedInterface.PRIORITY_HIGHEST)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(hooker);
        if (handle == null) throw new IllegalStateException("Playback engine hook unavailable");
        handles.add(handle);
    }

    private Object interceptEngine(XposedInterface.Chain chain) throws Throwable {
        if (closed) return chain.proceed();
        Object params = chain.getArg(0);
        CachedFrame frame = cachedFrames.get();
        if (frame != null && frame.player == chain.getThisObject() && frame.params == params) {
            frame.parentSeen = true;
        }
        GestureSpeedOverride.ScopeToken token = GestureSpeedOverride.captureScope();
        if (token == null) token = engineTokens.get(chain.getThisObject());
        if (token == null || !parameter(params)) return chain.proceed();
        float requested = readSpeed(params);
        Float configured = token.desired();
        if (!token.restoring() && configured != null) engineTokens.put(chain.getThisObject(), token);
        paramsTokens.put(params, token);
        Object result = token.withScope(chain::proceed);
        record("engine_speed_write_forwarded", "engine", requested, configured,
                token.restoring(), true, null);
        return result;
    }

    private Object interceptCached(XposedInterface.Chain chain) throws Throwable {
        if (closed) return chain.proceed();
        Object params = chain.getArg(0);
        GestureSpeedOverride.ScopeToken token = GestureSpeedOverride.captureScope();
        if (token == null) token = engineTokens.get(chain.getThisObject());
        if (token == null || !parameter(params)) return chain.proceed();
        float requested = readSpeed(params);
        if (!token.restoring() && token.desired() != null) engineTokens.put(chain.getThisObject(), token);
        paramsTokens.put(params, token);
        if (!token.restoring() && token.desired() == null) return token.withScope(chain::proceed);
        GestureSpeedOverride.ScopeToken currentToken = token;
        CachedFrame previous = cachedFrames.get();
        CachedFrame frame = new CachedFrame(chain.getThisObject(), params);
        cachedFrames.set(frame);
        try {
            return currentToken.withScope(() -> {
                Object result = chain.proceed();
                if (!frame.parentSeen && !closed) {
                    originSetter.invoke(frame.player, params);
                    record("cached_speed_resubmitted", "cached", requested, currentToken.desired(),
                            currentToken.restoring(), true, null);
                }
                return result;
            });
        } finally {
            if (previous == null) cachedFrames.remove();
            else cachedFrames.set(previous);
        }
    }

    private Object interceptMedia(XposedInterface.Chain chain) throws Throwable {
        if (closed) return chain.proceed();
        Object params = chain.getArg(0);
        if (!parameter(params)) return chain.proceed();
        GestureSpeedOverride.ScopeToken token = GestureSpeedOverride.captureScope();
        if (token == null) token = paramsTokens.get(params);
        if (token == null) token = mediaTokens.get(chain.getThisObject());
        if (token == null) return chain.proceed();
        float requested = readSpeed(params);
        Float desired = token.desired();
        if (!token.restoring() && desired != null) {
            try { setSpeed.invoke(params, desired); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                record("parameter_rewrite_unavailable", "media", requested, desired, false, true, null);
            }
            mediaTokens.put(chain.getThisObject(), token);
        }
        paramsTokens.put(params, token);
        Object result = token.withScope(chain::proceed);
        record("media_speed_write_forwarded", "media", requested, desired, token.restoring(), true, null);
        return result;
    }

    private Object interceptNative(XposedInterface.Chain chain) throws Throwable {
        if (closed || !(chain.getArg(0) instanceof Integer)
                || ((Integer) chain.getArg(0)) != PLAYBACK_SPEED_OPTION) return chain.proceed();
        GestureSpeedOverride.ScopeToken token = GestureSpeedOverride.captureScope();
        if (token == null) token = nativeTokens.get(chain.getThisObject());
        if (token == null || !(chain.getArg(1) instanceof Number)) return chain.proceed();
        float requested = ((Number) chain.getArg(1)).floatValue();
        Float desired = token.desired();
        Object[] arguments = chain.getArgs().toArray();
        if (!token.restoring() && desired != null) {
            arguments[1] = desired;
            nativeTokens.put(chain.getThisObject(), token);
        }
        Object result = token.withScope(() -> chain.proceed(arguments));
        record("native_speed_option_forwarded", "native", requested, desired, token.restoring(), true,
                result instanceof Number ? ((Number) result).intValue() : null);
        return result;
    }

    private boolean parameter(Object value) {
        return value != null && engineSetter.getParameterTypes()[0].isInstance(value);
    }

    private float readSpeed(Object params) {
        try { return ((Number) getSpeed.invoke(params)).floatValue(); }
        catch (ReflectiveOperationException | RuntimeException failure) { return Float.NaN; }
    }

    private void record(String stage, String layer, float requested, Float configured,
                        boolean restoring, boolean bound, Integer resultCode) {
        String evidence = "layer=" + layer + ";requested_speed=" + number(requested)
                + ";configured_speed=" + (configured == null ? "unavailable" : number(configured))
                + ";restoring=" + restoring + ";bound=" + bound + ";readback=false"
                + ";owner_active=" + (configured != null)
                + ";result_scope=local_speed_write;business_result=unconfirmed"
                + ("native".equals(layer) ? ";forwarded_speed="
                    + number(!restoring && configured != null ? configured : requested) : "")
                + (resultCode == null ? "" : ";return_code=" + resultCode);
        try { DouAiLog.event("auto_speed", "engine_speed_write", stage, evidence); }
        catch (Throwable ignored) { }
        try { log.accept("Playback engine speed " + stage + ";" + evidence); }
        catch (Throwable ignored) { }
    }

    private static String number(float value) { return Float.isFinite(value) ? Float.toString(value) : "unavailable"; }

    @Override public void close() {
        if (closed) return;
        closed = true;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try { handles.get(index).unhook(); }
            catch (Throwable ignored) { }
        }
        handles.clear();
        engineTokens.clear();
        paramsTokens.clear();
        mediaTokens.clear();
        nativeTokens.clear();
        cachedFrames.remove();
    }

    private static final class CachedFrame {
        final Object player;
        final Object params;
        boolean parentSeen;
        CachedFrame(Object player, Object params) { this.player = player; this.params = params; }
    }

    private static final class WeakIdentityTokens {
        private final ReferenceQueue<Object> collected = new ReferenceQueue<>();
        private final Map<IdentityKey, GestureSpeedOverride.ScopeToken> values = new HashMap<>();
        synchronized GestureSpeedOverride.ScopeToken get(Object key) {
            prune();
            return key == null ? null : values.get(new IdentityKey(key, null));
        }
        synchronized void put(Object key, GestureSpeedOverride.ScopeToken value) {
            if (key == null) return;
            prune();
            values.put(new IdentityKey(key, collected), value);
        }
        synchronized void clear() { values.clear(); while (collected.poll() != null) { } }
        private void prune() { for (Object key; (key = collected.poll()) != null; ) values.remove(key); }
    }

    private static final class IdentityKey extends WeakReference<Object> {
        private final int hash;
        IdentityKey(Object key, ReferenceQueue<Object> queue) { super(key, queue); hash = System.identityHashCode(key); }
        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            Object value = get();
            return value != null && other instanceof IdentityKey && value == ((IdentityKey) other).get();
        }
    }
}
