package com.douai.module.feature;

import android.os.SystemClock;
import com.douai.module.config.LocalOptions;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import org.robolectric.shadows.ShadowLooper;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class VideoQualityHookTest {
    private static final Method FACTORY = method(Factory.class, "isMeetCondition", FeedParameter.class);
    private static final Method RESUME = method(Component.class, "resume", Lifecycle.class);
    private static final Method SELECTED = method(Component.class, "selected", int.class);
    private static final Method PAUSE = method(Component.class, "pause", Lifecycle.class);
    private static final Method PLAYER = method(NativeGates.class, "player");
    private static final Method MP4 = method(NativeGates.class, "mp4");
    private static final Method UNRELATED = method(NativeGates.class, "unrelated");
    private static final Set<Method> ALL_TARGETS = Set.of(FACTORY, RESUME, SELECTED, PAUSE, PLAYER, MP4);

    @Before public void resetNativeState() {
        NativeGates.answer = false;
        NativeGates.playerCalls = 0;
        NativeGates.mp4Calls = 0;
    }

    @Test public void aMissingOptionKeepsNativeFactoryAndBackendAnswersAndNeverRequestsAQuality() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Factory factory = new Factory();
        Component component = new Component("first");
        try (VideoQualityHook hook = early(harness, access, new LocalOptions(Map.of()))) {
            hook.installPlayer(harness.api);
            for (boolean nativeAnswer : new boolean[]{false, true}) {
                factory.answer = nativeAnswer;
                NativeGates.answer = nativeAnswer;
                assertEquals(nativeAnswer, harness.invoke(FACTORY, factory, new FeedParameter()));
                assertEquals(nativeAnswer, harness.invoke(PLAYER, null));
                assertEquals(nativeAnswer, harness.invoke(MP4, null));
            }
            harness.invoke(RESUME, component, new Lifecycle());
            idle(10_000);
            assertTrue(access.requests.isEmpty());
            assertEquals(1, component.resumeCalls);
            assertEquals(2, factory.calls);
            assertEquals(2, NativeGates.playerCalls);
            assertEquals(2, NativeGates.mp4Calls);
            assertEquals(ALL_TARGETS, harness.hooks.keySet());
        }
    }

    @Test public void resumeSelectsTheActualSupportedMaximumAndReadbackPreventsRepeatedRequests() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        access.commitRequests = true;
        Component component = new Component("first");
        component.levels = List.of(Level.AUTO, Level.LOW, Level.MEDIUM);
        try (VideoQualityHook hook = active(harness, access)) {
            harness.invoke(RESUME, component, new Lifecycle());
            idle(499);
            assertTrue(access.requests.isEmpty());
            idle(501);
            assertEquals(1, access.requests.size());
            assertEquals(Level.MEDIUM, access.requests.get(0).target);
            assertEquals("first", access.requests.get(0).source);
            assertEquals(Level.MEDIUM, component.current);
            idle(5_000);
            assertEquals(1, access.requests.size());
            component.current = Level.LOW;
            idle(1_000);
            assertEquals(2, access.requests.size());
            assertEquals(Level.MEDIUM, access.requests.get(1).target);
            assertEquals(1, component.resumeCalls);
            assertEquals(ALL_TARGETS, harness.hooks.keySet());
        }
    }

    @Test public void pageChangesResetTheRetryBudgetAndAChangedSourceCannotReceiveTheOldRequest() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Component component = new Component("first");
        try (VideoQualityHook hook = active(harness, access)) {
            harness.invoke(RESUME, component, new Lifecycle());
            idle(5_000);
            assertEquals(3, access.requests.size());
            component.source = "second";
            harness.invoke(SELECTED, component, 1);
            idle(1_000);
            assertEquals(4, access.requests.size());
            assertEquals("second", access.requests.get(3).source);

            component.source = "third";
            harness.invoke(SELECTED, component, 2);
            idle(0);
            access.onCurrentRead = () -> component.source = "fourth";
            idle(1_000);
            assertEquals(4, access.requests.size());
            idle(2_000);
            assertEquals(5, access.requests.size());
            assertEquals("fourth", access.requests.get(4).source);
            assertEquals(2, component.selectedCalls);
        }
    }

    @Test public void pauseFlagOffAndCloseCancelFutureCorrections() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        access.commitRequests = true;
        Component component = new Component("first");
        VideoQualityHook hook = active(harness, access);
        try {
            harness.invoke(RESUME, component, new Lifecycle());
            idle(1_000);
            assertEquals(1, access.requests.size());
            harness.invoke(PAUSE, component, new Lifecycle());
            component.current = Level.LOW;
            idle(10_000);
            assertEquals(1, access.requests.size());
            assertEquals(1, component.pauseCalls);

            harness.invoke(RESUME, component, new Lifecycle());
            idle(1_000);
            assertEquals(2, access.requests.size());
            hook.setOptions(options(false));
            component.current = Level.LOW;
            idle(10_000);
            assertEquals(2, access.requests.size());
            assertEquals(false, harness.invoke(PLAYER, null));
            assertEquals(false, harness.invoke(MP4, null));

            hook.setOptions(options(true));
            idle(1_000);
            assertEquals(3, access.requests.size());
            hook.close();
            component.current = Level.LOW;
            idle(10_000);
            assertEquals(3, access.requests.size());
            assertFalse(hook.isActive());
            assertTrue(harness.hooks.isEmpty());
            assertEquals(6, harness.revoked.size());
            hook.close();
            assertEquals(6, harness.revoked.size());
        } finally { hook.close(); }
    }

    @Test public void autoOnlyMissingReadbackAndInvisibleSourcesKeepTheirNativePlayback() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Component component = new Component("first");
        component.levels = List.of(Level.AUTO);
        try (VideoQualityHook hook = active(harness, access)) {
            harness.invoke(RESUME, component, new Lifecycle());
            idle(5_000);
            assertTrue(access.requests.isEmpty());
            component.levels = Collections.emptyList();
            idle(2_000);
            assertTrue(access.requests.isEmpty());
            component.levels = List.of(Level.LOW, Level.HIGH);
            component.current = null;
            idle(2_000);
            assertTrue(access.requests.isEmpty());
            component.visible = false;
            component.current = Level.LOW;
            idle(2_000);
            assertTrue(access.requests.isEmpty());
            component.visible = true;
            idle(5_000);
            assertTrue("A stopped binding needs another lifecycle activation", access.requests.isEmpty());
        }
    }

    @Test public void unconfirmedRequestsHaveACooldownAndStopAfterThreeAttemptsUntilReadbackConfirms() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Component component = new Component("first");
        try (VideoQualityHook hook = active(harness, access)) {
            harness.invoke(RESUME, component, new Lifecycle());
            idle(1_000);
            assertEquals(1, access.requests.size());
            idle(1_000);
            assertEquals(1, access.requests.size());
            idle(1_000);
            assertEquals(2, access.requests.size());
            idle(1_000);
            assertEquals(2, access.requests.size());
            idle(1_000);
            assertEquals(3, access.requests.size());
            for (int index = 1; index < access.requests.size(); index++) {
                assertTrue(access.requests.get(index).at - access.requests.get(index - 1).at >= 2_000);
            }
            idle(20_000);
            assertEquals(3, access.requests.size());
            component.current = Level.HIGH;
            idle(1_000);
            assertEquals(3, access.requests.size());
            component.current = Level.LOW;
            idle(1_000);
            assertEquals(4, access.requests.size());
        }
    }

    @Test public void earlyLifecycleHooksActivateOnlyAfterTheTwoBackendGatesAreInstalledOnce() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Component component = new Component("first");
        try (VideoQualityHook hook = early(harness, access, options(true))) {
            assertFalse(hook.isActive());
            assertEquals(Set.of(FACTORY, RESUME, SELECTED, PAUSE), harness.hooks.keySet());
            harness.invoke(RESUME, component, new Lifecycle());
            idle(2_000);
            assertTrue(access.requests.isEmpty());
            hook.installPlayer(harness.api);
            assertTrue(hook.isActive());
            assertEquals(ALL_TARGETS, harness.hooks.keySet());
            hook.installPlayer(harness.api);
            assertEquals(List.of(FACTORY, RESUME, SELECTED, PAUSE, PLAYER, MP4), harness.requested);
            idle(1_000);
            assertEquals(1, access.requests.size());
            assertEquals(1, component.resumeCalls);
            assertEquals(true, harness.invoke(PLAYER, null));
            assertEquals(true, harness.invoke(MP4, null));
        }
    }

    @Test public void theSixthRegistrationFailureRevokesFiveQualityHandlesAndPreservesAnotherFeature() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        XposedInterface.HookHandle unrelated = harness.api.hook(UNRELATED).intercept(chain -> Boolean.TRUE);
        VideoQualityHook hook = early(harness, access, options(true));
        Component component = new Component("first");
        harness.invoke(RESUME, component, new Lifecycle());
        harness.reject = MP4;
        try {
            try {
                hook.installPlayer(harness.api);
                fail("Expected the sixth quality registration to fail");
            } catch (IllegalStateException failure) {
                assertSame(harness.registrationFailure, failure);
            }
            assertFalse(hook.isActive());
            assertEquals(Set.of(UNRELATED), harness.hooks.keySet());
            assertEquals(List.of(PLAYER, PAUSE, SELECTED, RESUME, FACTORY), harness.revoked);
            assertEquals(true, harness.invoke(UNRELATED, null));
            idle(10_000);
            assertTrue(access.requests.isEmpty());
        } finally { hook.close(); unrelated.unhook(); }
    }

    @Test public void aNativeLifecycleExceptionStillPropagatesAndDoesNotStartACorrectionLoop() throws Throwable {
        Harness harness = new Harness();
        FakeAccess access = new FakeAccess();
        Component component = new Component("first");
        component.resumeFailure = new IllegalStateException("native resume failed");
        try (VideoQualityHook hook = active(harness, access)) {
            try {
                harness.invoke(RESUME, component, new Lifecycle());
                fail("Expected the original resume exception");
            } catch (IllegalStateException failure) {
                assertSame(component.resumeFailure, failure);
            }
            idle(10_000);
            assertEquals(1, component.resumeCalls);
            assertTrue(access.requests.isEmpty());
        }
    }

    private static VideoQualityHook early(Harness harness, FakeAccess access, LocalOptions options) throws Exception {
        return VideoQualityHook.installEarlyForTargets(harness.api, options, ignored -> { }, access,
                FACTORY, RESUME, SELECTED, PAUSE, PLAYER, MP4);
    }

    private static VideoQualityHook active(Harness harness, FakeAccess access) throws Exception {
        VideoQualityHook hook = early(harness, access, options(true));
        hook.installPlayer(harness.api);
        return hook;
    }

    private static LocalOptions options(boolean enabled) {
        return new LocalOptions(Map.of(LocalOptions.LOCK_HIGHEST_VIDEO_QUALITY, enabled));
    }

    private static void idle(long millis) { ShadowLooper.shadowMainLooper().idleFor(Duration.ofMillis(millis)); }

    private static Method method(Class<?> owner, String name, Class<?>... parameters) {
        try { return owner.getMethod(name, parameters); }
        catch (NoSuchMethodException failure) { throw new AssertionError(failure); }
    }

    private enum Level {
        AUTO(0), LOW(360), MEDIUM(720), HIGH(1080);
        final int pixels;
        Level(int pixels) { this.pixels = pixels; }
    }

    public static final class FeedParameter { }
    public static final class Lifecycle { }
    public static final class Factory {
        boolean answer;
        int calls;
        public boolean isMeetCondition(FeedParameter ignored) { calls++; return answer; }
    }
    public static final class Component {
        String source;
        boolean visible = true;
        List<?> levels = List.of(Level.LOW, Level.HIGH);
        Object current = Level.LOW;
        int resumeCalls, selectedCalls, pauseCalls;
        RuntimeException resumeFailure;
        Component(String source) { this.source = source; }
        public void resume(Lifecycle ignored) {
            resumeCalls++;
            if (resumeFailure != null) throw resumeFailure;
            visible = true;
        }
        public void selected(int position) { selectedCalls++; }
        public void pause(Lifecycle ignored) { pauseCalls++; visible = false; }
    }
    public static final class NativeGates {
        static boolean answer;
        static int playerCalls, mp4Calls;
        public static boolean player() { playerCalls++; return answer; }
        public static boolean mp4() { mp4Calls++; return answer; }
        public static boolean unrelated() { return false; }
    }

    private record Request(String source, Object target, long at) { }
    private static final class FakeAccess extends VideoQualityHook.Access {
        final List<Request> requests = new ArrayList<>();
        boolean commitRequests;
        Runnable onCurrentRead;
        @Override boolean visible(Object component) { return ((Component) component).visible; }
        @Override String source(Object component) { return ((Component) component).source; }
        @Override List<?> supported(Object component) { return ((Component) component).levels; }
        @Override Object highest(List<?> levels) {
            Level best = null;
            if (levels != null) for (Object value : levels) {
                if (value instanceof Level && value != Level.AUTO
                        && (best == null || ((Level) value).pixels > best.pixels)) best = (Level) value;
            }
            return best;
        }
        @Override Object current(Object component) {
            Runnable change = onCurrentRead;
            onCurrentRead = null;
            if (change != null) change.run();
            return ((Component) component).current;
        }
        @Override boolean apply(Object component, Object target) {
            Component owner = (Component) component;
            requests.add(new Request(owner.source, target, SystemClock.elapsedRealtime()));
            if (commitRequests) owner.current = target;
            return true;
        }
    }

    /** Runs the installed hook and reflective native callback, including the native failure path. */
    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> hooks = new LinkedHashMap<>();
        final List<Method> requested = new ArrayList<>();
        final List<Method> revoked = new ArrayList<>();
        final IllegalStateException registrationFailure = new IllegalStateException("fixture registration rejected");
        Method reject;
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, operation, arguments) -> {
                    if (!"hook".equals(operation.getName())) throw new AssertionError("Unexpected API call: " + operation);
                    Method target = (Method) arguments[0];
                    requested.add(target);
                    return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookBuilder.class},
                            (builder, action, values) -> {
                                if (!"intercept".equals(action.getName())) return builder;
                                if (target.equals(reject)) throw registrationFailure;
                                XposedInterface.Hooker hook = (XposedInterface.Hooker) values[0];
                                hooks.put(target, hook);
                                return Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{XposedInterface.HookHandle.class},
                                        (handle, call, ignored) -> {
                                            if ("unhook".equals(call.getName()) && hooks.get(target) == hook) {
                                                hooks.remove(target);
                                                revoked.add(target);
                                            }
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object receiver, Object... arguments) throws Throwable {
            XposedInterface.Hooker hook = hooks.get(target);
            if (hook == null) return original(target, receiver, arguments);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, operation, values) -> {
                        switch (operation.getName()) {
                            case "getThisObject": return receiver;
                            case "getExecutable": return target;
                            case "getArg": return arguments[(Integer) values[0]];
                            case "getArgs": return Arrays.asList(arguments);
                            case "proceed": return original(target, receiver,
                                    values == null || values.length == 0 ? arguments : (Object[]) values[0]);
                            default: throw new AssertionError("Unexpected chain call: " + operation);
                        }
                    });
            return hook.intercept(chain);
        }

        private Object original(Method target, Object receiver, Object[] arguments) throws Throwable {
            try { return target.invoke(receiver, arguments); }
            catch (InvocationTargetException failure) { throw failure.getCause(); }
        }
    }
}
