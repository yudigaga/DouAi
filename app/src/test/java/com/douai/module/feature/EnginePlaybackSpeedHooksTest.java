package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@LooperMode(LooperMode.Mode.PAUSED)
public final class EnginePlaybackSpeedHooksTest {
    private static final Method FACADE_SET = method(FakeFacade.class, "setSpeed", float.class);
    private static final Method ENGINE_PARAMS = method(FakeEngine.class, "setPlaybackParams", FakeParams.class);
    private static final Method CACHED_ENGINE_PARAMS = method(FakeCachedEngine.class, "setPlaybackParams", FakeParams.class);
    private static final Method MEDIA_PARAMS = method(FakeMedia.class, "setPlaybackParams", FakeParams.class);
    private static final Method NATIVE_OPTION = method(FakeNative.class, "setFloatOption", int.class, float.class);

    public static final class FakeParams {
        float speed;
        float pitch = 1.125f;
        int audioFallbackMode = 3;
        FakeParams(float speed) { this.speed = speed; }
        public float getSpeed() { return speed; }
        public FakeParams setSpeed(float speed) { this.speed = speed; return this; }
    }

    public static final class FakeNative {
        float actualSpeed = 1.25f;
        final Map<Integer, Float> options = new HashMap<>();
        int calls;
        public int setFloatOption(int option, float value) {
            calls++;
            options.put(option, value);
            if (option == 60) actualSpeed = value;
            return 37;
        }
    }

    public static final class FakeMedia {
        final Harness harness;
        final FakeNative nativePlayer;
        FakeParams lastParams;
        FakeMedia(Harness harness, FakeNative nativePlayer) {
            this.harness = harness;
            this.nativePlayer = nativePlayer;
        }
        public void setPlaybackParams(FakeParams params) {
            lastParams = params;
            harness.callUnchecked(NATIVE_OPTION, nativePlayer, 60, params.getSpeed());
        }
    }

    public static class FakeEngine {
        final Handler main = new Handler(Looper.getMainLooper());
        final Harness harness;
        FakeMedia media;
        FakeParams lastParams;
        int paramsCalls;
        FakeEngine(Harness harness, FakeMedia media) { this.harness = harness; this.media = media; }
        public void setPlaybackParams(FakeParams params) {
            setPlaybackParamsBody(params);
        }
        void setPlaybackParamsBody(FakeParams params) {
            paramsCalls++;
            lastParams = params;
            // The APK caches params and can enqueue the same object before media/native dispatch.
            main.postDelayed(() -> harness.callUnchecked(MEDIA_PARAMS, media, params), 100);
        }
    }

    public static final class FakeCachedEngine extends FakeEngine {
        FakeParams cachedParams = new FakeParams(2f);
        int cachedCalls;
        FakeCachedEngine(Harness harness, FakeMedia media) { super(harness, media); }
        @Override public void setPlaybackParams(FakeParams params) {
            cachedCalls++;
            if (cachedParams != null && cachedParams.speed == params.speed
                    && cachedParams.pitch == params.pitch
                    && cachedParams.audioFallbackMode == params.audioFallbackMode) return;
            cachedParams = params;
            // This is the host's invoke-super path; the harness must run the base hook/body
            // explicitly instead of reflectively redispatching to this override.
            harness.callUnchecked(ENGINE_PARAMS, this, params);
        }
    }

    public static final class FakeFacade {
        final Harness harness;
        final FakeEngine engine;
        float cachedSpeed = 1.25f;
        FakeFacade(Harness harness, FakeEngine engine) { this.harness = harness; this.engine = engine; }
        public float getSpeed() { return cachedSpeed; }
        public void setSpeed(float speed) {
            cachedSpeed = speed;
            harness.callUnchecked(engine instanceof FakeCachedEngine ? CACHED_ENGINE_PARAMS : ENGINE_PARAMS,
                    engine, new FakeParams(speed));
        }
        public void resume() { }
        public String play() { return "video"; }
    }

    public static final class FakeManager {
        final Harness harness;
        final FakeFacade facade;
        FakeManager(Harness harness, FakeFacade facade) { this.harness = harness; this.facade = facade; }
        public float getSpeed() { return facade.getSpeed(); }
        public void setSpeed(float speed) { harness.callUnchecked(FACADE_SET, facade, speed); }
        public void resume() { harness.callUnchecked(method(FakeFacade.class, "resume"), facade); }
        public String play() {
            try { return (String) harness.invoke(method(FakeFacade.class, "play"), facade); }
            catch (Throwable failure) { throw new AssertionError(failure); }
        }
    }

    @Test public void lateNativeSpeedWriteCannotUndoAConfirmedGlobalChoice() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.manual.registerGlobal();
            f.manual.apply(2f, () -> f.harness.call(FACADE_SET, f.facade, 2f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals(2f, f.facade.cachedSpeed, 0f);
            assertEquals("The initial confirmation must observe the actual native engine", 2f,
                    f.nativePlayer.actualSpeed, 0f);

            // A single delayed SDK/native write: no new facade, resume, play, or touch callback.
            f.main.postDelayed(() -> f.harness.callUnchecked(NATIVE_OPTION, f.nativePlayer, 60, 1f), 3000);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3000));
            assertEquals("The user's toggle must remain enabled while the SDK writes", 2f,
                    GestureSpeedOverride.speedFor(f.facade), 0f);
            assertEquals(2f, f.facade.cachedSpeed, 0f);
            assertEquals("A delayed option 60 write must preserve the confirmed native speed", 2f,
                    f.nativePlayer.actualSpeed, 0f);
        }
    }

    @Test public void lateNewParamsFromTheBoundEngineKeepTheChoiceAndOtherParameters() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            FakeParams late = new FakeParams(1f);
            late.pitch = 0.875f;
            late.audioFallbackMode = 7;
            f.main.postDelayed(() -> f.harness.callUnchecked(ENGINE_PARAMS, f.engine, late), 3000);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3150));
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
            assertEquals(2f, f.facade.cachedSpeed, 0f);
            assertEquals(0.875f, late.pitch, 0f);
            assertEquals(7, late.audioFallbackMode);
            assertEquals(0.875f, f.media.lastParams.pitch, 0f);
            assertEquals(7, f.media.lastParams.audioFallbackMode);
            assertEquals("Each queued params write must reach the native body once", 2, f.nativePlayer.calls);
        }
    }

    @Test public void unrelatedMediaAndNativeKeepTheirOwnSpeedWhileTheGlobalChoiceIsEnabled() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            FakeNative unrelatedNative = new FakeNative();
            assertEquals(37, ((Number) f.harness.invoke(NATIVE_OPTION, unrelatedNative, 60, 1f)).intValue());
            assertEquals(1f, unrelatedNative.actualSpeed, 0f);

            FakeNative otherNative = new FakeNative();
            FakeMedia unrelatedMedia = new FakeMedia(f.harness, otherNative);
            f.harness.invoke(MEDIA_PARAMS, unrelatedMedia, new FakeParams(1f));
            assertEquals("A global feed choice must not adopt an unrelated media instance", 1f,
                    otherNative.actualSpeed, 0f);
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
        }
    }

    @Test public void nativeOptionRewritingPreservesOtherOptionsBodyCallsAndReturnCode() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            int before = f.nativePlayer.calls;
            assertEquals(37, ((Number) f.harness.invoke(NATIVE_OPTION, f.nativePlayer, 60, 1f)).intValue());
            assertEquals(2f, f.nativePlayer.options.get(60), 0f);
            assertEquals(37, ((Number) f.harness.invoke(NATIVE_OPTION, f.nativePlayer, 61, 0.375f)).intValue());
            assertEquals(0.375f, f.nativePlayer.options.get(61), 0f);
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
            assertEquals(before + 2, f.nativePlayer.calls);
        }
    }

    @Test public void manualNormalSpeedAlsoBlocksALateNativeAcceleration() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            f.manual.apply(1f, () -> f.harness.call(FACADE_SET, f.facade, 1f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals(1f, f.nativePlayer.actualSpeed, 0f);
            f.main.postDelayed(() -> f.harness.callUnchecked(NATIVE_OPTION, f.nativePlayer, 60, 2f), 3000);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(3000));
            assertEquals(1f, GestureSpeedOverride.speedFor(f.facade), 0f);
            assertEquals(1f, f.nativePlayer.actualSpeed, 0f);
        }
    }

    @Test public void closingTheOwnerReleasesPreviouslyBoundMediaAndNative() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            f.manual.close();
            assertNull(GestureSpeedOverride.speedFor(f.facade));
            assertEquals(37, ((Number) f.harness.invoke(NATIVE_OPTION, f.nativePlayer, 60, 3f)).intValue());
            assertEquals(3f, f.nativePlayer.actualSpeed, 0f);
            f.harness.invoke(MEDIA_PARAMS, f.media, new FakeParams(1.5f));
            assertEquals(1.5f, f.nativePlayer.actualSpeed, 0f);
        }
    }

    @Test public void restoringTheBaselineSurvivesTheQueuedParamsBoundary() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            f.manual.restore(() -> f.harness.call(FACADE_SET, f.facade, 1.25f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals(1.25f, f.nativePlayer.actualSpeed, 0f);
            assertEquals("A restoration must not silently change the active user choice", 2f,
                    GestureSpeedOverride.speedFor(f.facade), 0f);
            f.harness.invoke(NATIVE_OPTION, f.nativePlayer, 60, 1f);
            assertEquals("The restoration bypass must not leak into the next SDK write", 2f,
                    f.nativePlayer.actualSpeed, 0f);
        }
    }

    @Test public void aNewFacadeBindsItsEngineWithoutAdoptingUnrelatedMedia() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            FakeNative nextNative = new FakeNative();
            FakeMedia nextMedia = new FakeMedia(f.harness, nextNative);
            FakeEngine nextEngine = new FakeEngine(f.harness, nextMedia);
            FakeFacade nextFacade = new FakeFacade(f.harness, nextEngine);
            try {
                // This ordinary host write occurs after the original manual scope is gone.
                f.harness.invoke(FACADE_SET, nextFacade, 1f);
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
                assertEquals(2f, nextNative.actualSpeed, 0f);
                f.harness.invoke(NATIVE_OPTION, nextNative, 60, 1f);
                assertEquals(2f, nextNative.actualSpeed, 0f);
                FakeNative unrelated = new FakeNative();
                f.harness.invoke(MEDIA_PARAMS, new FakeMedia(f.harness, unrelated), new FakeParams(1f));
                assertEquals(1f, unrelated.actualSpeed, 0f);
            } finally { nextEngine.main.removeCallbacksAndMessages(null); }
        }
    }

    @Test public void ownedEngineKeepsTheChoiceWhenTheSdkReplacesItsMediaPlayer() throws Throwable {
        try (Fixture f = new Fixture()) {
            f.enable(2f);
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
            FakeNative replacementNative = new FakeNative();
            f.engine.media = new FakeMedia(f.harness, replacementNative);
            assertFalse("SDK replacement happens after the original manual call scope", GestureSpeedOverride.inScope());
            f.harness.invoke(ENGINE_PARAMS, f.engine, new FakeParams(1.7f));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals("The already owned engine must pass its choice to the replacement media", 2f,
                    replacementNative.actualSpeed, 0f);
            f.harness.invoke(NATIVE_OPTION, replacementNative, 60, 1f);
            assertEquals(2f, replacementNative.actualSpeed, 0f);

            FakeNative unrelatedNative = new FakeNative();
            FakeEngine unrelatedEngine = new FakeEngine(f.harness, new FakeMedia(f.harness, unrelatedNative));
            try {
                f.harness.invoke(ENGINE_PARAMS, unrelatedEngine, new FakeParams(1f));
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
                assertEquals("An independent engine must not inherit the global feed choice", 1f,
                        unrelatedNative.actualSpeed, 0f);
                assertEquals(2f, replacementNative.actualSpeed, 0f);
            } finally { unrelatedEngine.main.removeCallbacksAndMessages(null); }
        }
    }

    @Test public void matchingCachedParamsStillReachTheNativeEngineWhenItsActualSpeedDiffers() throws Throwable {
        try (Fixture f = new Fixture(true)) {
            f.facade.cachedSpeed = 2f;
            f.nativePlayer.actualSpeed = 1f;
            f.enable(2f);
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
            assertEquals(1, f.engine.paramsCalls);
            assertEquals(1, f.nativePlayer.calls);
            assertEquals("A skipped parent body must be invoked nonvirtually exactly once", 1,
                    f.harness.specialCalls);
        }
    }

    @Test public void cachedParamsThatEnterTheParentBodyAreNotSentTwice() throws Throwable {
        try (Fixture f = new Fixture(true)) {
            ((FakeCachedEngine) f.engine).cachedParams.setSpeed(1f);
            f.enable(2f);
            assertEquals(2f, f.nativePlayer.actualSpeed, 0f);
            assertEquals(1, f.engine.paramsCalls);
            assertEquals(1, f.nativePlayer.calls);
            assertEquals(0, f.harness.specialCalls);
        }
    }

    @Test public void cachedDeduplicationRemainsNativeWithoutAManualChoice() throws Throwable {
        try (Fixture f = new Fixture(true)) {
            f.nativePlayer.actualSpeed = 1f;
            f.harness.invoke(FACADE_SET, f.facade, 2f);
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
            assertEquals(1f, f.nativePlayer.actualSpeed, 0f);
            assertEquals(0, f.engine.paramsCalls);
            assertEquals(0, f.harness.specialCalls);
        }
    }

    @Test public void installationDeoptimizesOnlyTheFourReportedSdkCallers() throws Throwable {
        try (Fixture f = new Fixture()) {
            // This asserts the installation plan only; the JVM fixture does not simulate ART inlining.
            assertEquals(new HashSet<>(Arrays.asList(CACHED_ENGINE_PARAMS, FACADE_SET, MEDIA_PARAMS, ENGINE_PARAMS)),
                    new HashSet<>(f.harness.deoptimized));
            assertEquals(4, f.harness.deoptimized.size());
            assertEquals(Arrays.asList("deoptimize", "deoptimize", "deoptimize", "deoptimize"), f.harness.optimizationCalls);
            assertFalse(f.harness.deoptimized.contains(NATIVE_OPTION));
            assertFalse(f.harness.deoptimized.contains(method(FakeManager.class, "play")));
        }
    }

    private static final class Fixture implements AutoCloseable {
        final Handler main = new Handler(Looper.getMainLooper());
        final Harness harness = new Harness();
        final FakeNative nativePlayer = new FakeNative();
        final FakeMedia media = new FakeMedia(harness, nativePlayer);
        final FakeEngine engine;
        final FakeFacade facade;
        final GestureSpeedOverride manual = new GestureSpeedOverride();
        final PlaybackBehaviorHooks hooks;
        Fixture() { this(false); }
        Fixture(boolean cached) {
            engine = cached ? new FakeCachedEngine(harness, media) : new FakeEngine(harness, media);
            facade = new FakeFacade(harness, engine);
            hooks = PlaybackBehaviorHooks.install(harness.framework, report(), getClass().getClassLoader(), null,
                    new LocalOptions(Collections.emptyMap()), ignored -> { });
            assertTrue("The real playback installation entry must accept the facade contract",
                    hooks.supports("auto_speed"));
        }
        void enable(float speed) throws ReflectiveOperationException {
            manual.registerGlobal();
            manual.apply(speed, () -> harness.call(FACADE_SET, facade, speed));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofMillis(150));
        }
        @Override public void close() {
            main.removeCallbacksAndMessages(null);
            engine.main.removeCallbacksAndMessages(null);
            manual.close();
            hooks.close();
        }
    }

    private static HostScanReport report() {
        Map<String, Method> methods = new LinkedHashMap<>();
        methods.put("adv_player_set_speed", FACADE_SET);
        methods.put("adv_player_get_speed", method(FakeFacade.class, "getSpeed"));
        methods.put("adv_player_resume", method(FakeFacade.class, "resume"));
        methods.put("adv_player_play", method(FakeFacade.class, "play"));
        methods.put("adv_player_manager_set_speed", method(FakeManager.class, "setSpeed", float.class));
        methods.put("adv_player_manager_get_speed", method(FakeManager.class, "getSpeed"));
        methods.put("adv_player_manager_resume", method(FakeManager.class, "resume"));
        methods.put("adv_player_manager_play", method(FakeManager.class, "play"));
        methods.put("adv_player_engine_set_params", ENGINE_PARAMS);
        methods.put("adv_player_cached_engine_set_params", CACHED_ENGINE_PARAMS);
        methods.put("adv_player_media_set_params", MEDIA_PARAMS);
        methods.put("adv_player_native_float_option", NATIVE_OPTION);
        methods.put("adv_player_sdk_base_params_caller", CACHED_ENGINE_PARAMS);
        methods.put("adv_player_sdk_speed_params_caller", FACADE_SET);
        methods.put("adv_player_sdk_float_option_caller", MEDIA_PARAMS);
        methods.put("adv_player_sdk_media_params_caller", ENGINE_PARAMS);
        ArrayList<RuleScanResult> results = new ArrayList<>();
        methods.forEach((id, target) -> results.add(new RuleScanResult(id, "fixture", Collections.singletonList(
                new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(target.getDeclaringClass()),
                        target.getName(), HostTargets.descriptor(target))))));
        return new HostScanReport("fixture", results, false);
    }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try { return owner.getMethod(name, parameters); }
        catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final List<Method> deoptimized = new ArrayList<>();
        final List<String> optimizationCalls = new ArrayList<>();
        int specialCalls;
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, operation, arguments) -> {
                    if (operation.getName().startsWith("deoptimize")) {
                        optimizationCalls.add(operation.getName());
                        if (!operation.getName().equals("deoptimize")) return false;
                        assertEquals(1, arguments.length);
                        assertTrue(arguments[0] instanceof Method);
                        deoptimized.add((Method) arguments[0]);
                        return true;
                    }
                    if (operation.getName().equals("getInvoker")) {
                        Method target = (Method) arguments[0];
                        return Proxy.newProxyInstance(getClass().getClassLoader(),
                                new Class<?>[]{operation.getReturnType()}, (invoker, action, values) -> {
                                    if (action.getName().equals("setType")) {
                                        assertEquals(XposedInterface.Invoker.Type.ORIGIN, values[0]);
                                        return invoker;
                                    }
                                    if (action.getName().equals("invokeSpecial")) {
                                        assertEquals(ENGINE_PARAMS, target);
                                        specialCalls++;
                                        ((FakeEngine) values[0]).setPlaybackParamsBody(
                                                (FakeParams) ((Object[]) values[1])[0]);
                                        return null;
                                    }
                                    throw new AssertionError("Unexpected original invoker method: " + action);
                                });
                    }
                    if (!operation.getName().equals("hook")) return null;
                    Method target = (Method) arguments[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, action, values) -> {
                                if (!action.getName().equals("intercept")) return builder;
                                XposedInterface.Hooker hook = (XposedInterface.Hooker) values[0];
                                interceptors.put(target, hook);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, call, inputs) -> {
                                            if (call.getName().equals("unhook") && interceptors.get(target) == hook) {
                                                interceptors.remove(target);
                                            }
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArgs": return Arrays.asList(arguments);
                            case "getArg": return arguments[(Integer) values[0]];
                            case "proceed":
                                return original(target, owner, values == null ? arguments : (Object[]) values[0]);
                            default: return null;
                        }
                    });
            XposedInterface.Hooker hook = interceptors.get(target);
            if (hook != null) return hook.intercept(chain);
            return original(target, owner, arguments);
        }

        Object original(Method target, Object owner, Object[] arguments) throws Throwable {
            if (target.equals(ENGINE_PARAMS)) {
                ((FakeEngine) owner).setPlaybackParamsBody((FakeParams) arguments[0]);
                return null;
            }
            try { return target.invoke(owner, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }

        void call(Method target, Object owner, Object... arguments) throws ReflectiveOperationException {
            try { invoke(target, owner, arguments); }
            catch (ReflectiveOperationException failure) { throw failure; }
            catch (Throwable failure) { throw new InvocationTargetException(failure); }
        }

        void callUnchecked(Method target, Object owner, Object... arguments) {
            try { call(target, owner, arguments); }
            catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
        }
    }
}
