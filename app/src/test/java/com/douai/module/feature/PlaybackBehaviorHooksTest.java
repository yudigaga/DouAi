package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import io.github.libxposed.api.XposedInterface;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class PlaybackBehaviorHooksTest {
    public static final class Player {
        public float speed = 1f;
        public boolean resetOnResume;
        public boolean unreadableSpeed;
        public void setSpeed(float next) { speed = next; }
        public float getSpeed() {
            if (unreadableSpeed) throw new IllegalStateException("Native speed unavailable");
            return speed;
        }
        public void resume() { if (resetOnResume) speed = 1f; }
        public String play() { return "video"; }
    }

    public static final class PlayerDelegate {
        public float speed = 1f;
        public boolean resetOnResume;
        public boolean resetOnPlay;
        public void setSpeed(float next) { speed = next; }
        public float getSpeed() { return speed; }
        public void resume() { if (resetOnResume) speed = 1f; }
        public String play() {
            if (resetOnPlay) speed = 1f;
            return "video";
        }
    }

    public static final class PlayerManager {
        final Harness harness;
        public final PlayerDelegate LIZ;
        PlayerManager(Harness harness, PlayerDelegate delegate) {
            this.harness = harness;
            this.LIZ = delegate;
        }
        public void setSpeed(float next) { dispatch("setSpeed", next); }
        public float getSpeed() { return ((Number) dispatch("getSpeed")).floatValue(); }
        public void resume() { dispatch("resume"); }
        public String play() { return (String) dispatch("play"); }
        private Object dispatch(String name, Object... arguments) {
            try {
                Method method = name.equals("setSpeed")
                        ? PlayerDelegate.class.getMethod(name, float.class)
                        : PlayerDelegate.class.getMethod(name);
                return harness.invoke(method, LIZ, arguments);
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }
    }

    public static final class CommentGate {
        boolean showing;
        boolean canMove = true;
        public boolean check() { return canMove; }
        public boolean comments() { return showing; }
    }

    public static final class Pager {
        int forwards;
        int backwards;
        public boolean next(String source, boolean smooth) { forwards++; return true; }
        public boolean previous(String source, boolean smooth) { backwards++; return true; }
    }

    @Test public void runtimePlaybackSpeedUsesOnlyTheSharedSevenPresets() {
        String[][] cases = {{"3.5", "3"}, {"2.75", "3"}, {"1.75", "1.5"}, {"0.25", "0.75"},
                {"2.5", "2"}, {"4", "4"}, {"4.1", "4"}, {"NaN", "2"}, {"Infinity", "2"}, {"text", "2"}};
        for (String[] sample : cases) assertEquals(sample[0], Float.parseFloat(sample[1]),
                PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Map.of("playback_speed", sample[0]))), 0f);
        assertEquals(2f, PlaybackBehaviorHooks.playbackSpeed(new LocalOptions(Collections.emptyMap())), 0f);
    }

    @Test public void speedRestoresTheLatestHostRequestedValue() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true, "playback_speed", "2"));
        Player player = new Player();
        harness.invoke(methods.get("adv_player_resume"), player);
        assertEquals(2f, player.speed, 0f);
        harness.invoke(methods.get("adv_player_set_speed"), player, 1.5f);
        assertEquals(2f, player.speed, 0f);
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(1.5f, player.speed, 0f);
        hooks.close();
    }

    @Test public void speedRollbackDoesNotOverwriteExternalChanges() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true));
        Player player = new Player();
        harness.invoke(methods.get("adv_player_resume"), player);
        player.speed = 3f;
        hooks.close();
        assertEquals(3f, player.speed, 0f);
    }

    @Test public void manualSpeedWorksWithAutomaticSpeedDisabledAndSurvivesResume() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Collections.emptyMap());
        Player player = new Player();
        player.resetOnResume = true;
        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), player, 3f));
        assertEquals(3f, player.speed, 0f);
        harness.invoke(methods.get("adv_player_resume"), player);
        assertEquals(3f, player.speed, 0f);
        manual.close();
        hooks.close();
    }

    @Test public void manualResumeWritesEvenWhenNativeSpeedGetterIsUnavailable() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Collections.emptyMap());
        Player player = new Player();
        player.resetOnResume = true;
        player.unreadableSpeed = true;
        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), player, 3f));
        harness.invoke(methods.get("adv_player_resume"), player);
        assertEquals(3f, player.speed, 0f);
        assertTrue(hooks.supports("auto_speed"));
        manual.close();
        hooks.close();
    }

    @Test public void globalManualSpeedReachesNewPlayersBeforeAnyHolderSelection() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Collections.emptyMap());
        GestureSpeedOverride manual = new GestureSpeedOverride();
        try {
            Player first = new Player();
            first.speed = 1.25f;
            manual.rememberOriginal(first, first.speed);
            manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), first, 3f));
            manual.registerGlobal();

            Player resumed = new Player();
            resumed.resetOnResume = true;
            harness.invoke(methods.get("adv_player_resume"), resumed);
            assertEquals(3f, resumed.speed, 0f);
            Player played = new Player();
            played.speed = 1.5f;
            harness.invoke(methods.get("adv_player_play"), played);
            assertEquals(3f, played.speed, 0f);
            Player writtenAfterTheOriginalScope = new Player();
            harness.invoke(methods.get("adv_player_set_speed"), writtenAfterTheOriginalScope, 1.75f);
            assertEquals(3f, writtenAfterTheOriginalScope.speed, 0f);

            PlayerDelegate delegate = new PlayerDelegate();
            delegate.speed = 0.75f;
            PlayerManager manager = new PlayerManager(harness, delegate);
            harness.invoke(methods.get("adv_player_manager_play"), manager);
            assertEquals(3f, delegate.speed, 0f);
            delegate.resetOnResume = true;
            harness.invoke(methods.get("adv_player_manager_resume"), manager);
            assertEquals(3f, delegate.speed, 0f);
            harness.invoke(methods.get("adv_player_manager_set_speed"), manager, 2f);
            assertEquals(3f, delegate.speed, 0f);

            assertEquals(1.25f, manual.restorationSpeed(first, 2f), 0f);
            assertEquals(1f, manual.restorationSpeed(resumed, 2f), 0f);
            assertEquals(1.5f, manual.restorationSpeed(played, 2f), 0f);
            assertEquals(1.75f, manual.restorationSpeed(writtenAfterTheOriginalScope, 2f), 0f);
            assertEquals(0.75f, manual.restorationSpeed(manager, 2f), 0f);
        } finally {
            manual.close();
            hooks.close();
        }
    }

    @Test public void globalNormalSpeedPersistsAcrossNewPlayersAndOverridesAutomaticSpeed() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods,
                Map.of("auto_speed", true, "playback_speed", "4"));
        GestureSpeedOverride manual = new GestureSpeedOverride();
        try {
            Player first = new Player();
            manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), first, 3f));
            manual.registerGlobal();
            manual.apply(1f, () -> harness.call(methods.get("adv_player_set_speed"), first, 1f));

            Player next = new Player();
            harness.invoke(methods.get("adv_player_resume"), next);
            harness.invoke(methods.get("adv_player_set_speed"), next, 2f);
            assertEquals(1f, next.speed, 0f);
            PlayerDelegate delegate = new PlayerDelegate();
            delegate.resetOnPlay = true;
            delegate.resetOnResume = true;
            PlayerManager manager = new PlayerManager(harness, delegate);
            harness.invoke(methods.get("adv_player_manager_play"), manager);
            harness.invoke(methods.get("adv_player_manager_resume"), manager);
            harness.invoke(methods.get("adv_player_manager_set_speed"), manager, 2f);
            assertEquals(1f, delegate.speed, 0f);
            hooks.setOptions(new LocalOptions(Map.of("auto_speed", true, "playback_speed", "3")));
            assertEquals(1f, first.speed, 0f);
            assertEquals(1f, next.speed, 0f);
            assertEquals(1f, delegate.speed, 0f);

            manual.unregisterGlobal();
            Player independent = new Player();
            harness.invoke(methods.get("adv_player_resume"), independent);
            assertEquals(3f, independent.speed, 0f);
            harness.invoke(methods.get("adv_player_resume"), next);
            assertEquals(1f, next.speed, 0f);
            manual.releaseBindings();
            harness.invoke(methods.get("adv_player_resume"), next);
            assertEquals(3f, next.speed, 0f);
        } finally {
            manual.close();
            hooks.close();
        }
    }

    @Test public void configurationRefreshReappliesTheGlobalChoiceToAllSeenPlayersWithAutoDisabled() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Collections.emptyMap());
        GestureSpeedOverride manual = new GestureSpeedOverride();
        try {
            Player first = new Player();
            manual.apply(2f, () -> harness.call(methods.get("adv_player_set_speed"), first, 2f));
            manual.registerGlobal();
            Player second = new Player();
            harness.invoke(methods.get("adv_player_resume"), second);
            PlayerManager manager = new PlayerManager(harness, new PlayerDelegate());
            harness.invoke(methods.get("adv_player_manager_resume"), manager);

            // Feed updates the shared session before publishing playback's options snapshot.
            manual.apply(3f, () -> { });
            hooks.setOptions(new LocalOptions(Map.of("playback_speed", "3")));
            assertEquals(3f, first.speed, 0f);
            assertEquals(3f, second.speed, 0f);
            assertEquals(3f, manager.LIZ.speed, 0f);
            manual.releaseBindings();
            harness.invoke(methods.get("adv_player_resume"), second);
            assertEquals(3f, second.speed, 0f);
        } finally {
            manual.close();
            hooks.close();
        }
    }

    @Test public void globalRestorationUsesEachPlayersNativeBaselineAndRejectsMetrics() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods,
                Map.of("auto_speed", true, "playback_speed", "2"));
        GestureSpeedOverride manual = new GestureSpeedOverride();
        try {
            Player first = new Player();
            first.speed = 1.25f;
            Player second = new Player();
            second.speed = 1.75f;
            harness.invoke(methods.get("adv_player_resume"), first);
            harness.invoke(methods.get("adv_player_resume"), second);
            manual.rememberOriginal(first, first.speed);
            manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), first, 3f));
            manual.registerGlobal();
            harness.invoke(methods.get("adv_player_resume"), second);
            hooks.setOptions(new LocalOptions(Collections.emptyMap()));
            assertEquals(3f, first.speed, 0f);
            assertEquals(3f, second.speed, 0f);
            assertEquals(1.25f, manual.restorationSpeed(first, 2f), 0f);
            assertEquals(1.75f, manual.restorationSpeed(second, 2f), 0f);
            manual.rememberOriginal(first, 2f);
            manual.rememberOriginal(second, 30f);
            assertEquals(1.25f, manual.restorationSpeed(first, 2f), 0f);
            assertEquals(1.75f, manual.restorationSpeed(second, 2f), 0f);
            Player unseen = new Player();
            manual.rememberOriginal(unseen, Float.NaN);
            assertEquals(2.5f, manual.restorationSpeed(unseen, 2.5f), 0f);
        } finally {
            manual.close();
            hooks.close();
        }
    }

    @Test public void manualNormalSpeedSuppressesAutomaticUpdatesOnlyForItsEngine() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true));
        Player player = new Player();
        Player unrelated = new Player();
        harness.invoke(methods.get("adv_player_resume"), player);
        harness.invoke(methods.get("adv_player_resume"), unrelated);
        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), player, 3f));
        manual.apply(1f, () -> harness.call(methods.get("adv_player_set_speed"), player, 1f));
        hooks.setOptions(new LocalOptions(Map.of("auto_speed", true, "playback_speed", "4")));
        assertEquals(1f, player.speed, 0f);
        assertEquals(4f, unrelated.speed, 0f);
        harness.invoke(methods.get("adv_player_play"), player);
        assertEquals(1f, player.speed, 0f);
        manual.close();
        harness.invoke(methods.get("adv_player_resume"), player);
        assertEquals(4f, player.speed, 0f);
        hooks.close();
        assertEquals(1f, player.speed, 0f);
        assertEquals(1f, unrelated.speed, 0f);
    }

    @Test public void manualFacadeWritesBindTheActualEngineWithoutChangingAutomaticBaseline() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods,
                Map.of("auto_speed", true, "playback_speed", "2"));
        Player engine = new Player();
        engine.speed = 1.25f;
        harness.invoke(methods.get("adv_player_resume"), engine);
        GestureSpeedOverride manual = new GestureSpeedOverride();
        GestureSpeedOverride.ReflectiveCall facadeWrite =
                () -> harness.call(methods.get("adv_player_set_speed"), engine, 3f);
        manual.apply(3f, facadeWrite);
        assertEquals(3f, engine.speed, 0f);
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(3f, engine.speed, 0f);
        assertEquals(1.25f, manual.restorationSpeed(2f), 0f);
        manual.restore(() -> harness.call(methods.get("adv_player_set_speed"), engine,
                manual.restorationSpeed(2f)));
        manual.close();
        hooks.close();
        assertEquals(1.25f, engine.speed, 0f);
    }

    @Test public void nativeSpeedWritesRemainVisibleUntilResumeAndRestoreAsNativeBaseline() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true));
        Player player = new Player();
        harness.invoke(methods.get("adv_player_resume"), player);
        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), player, 3f));
        harness.invoke(methods.get("adv_player_set_speed"), player, 1.5f);
        assertEquals(3f, player.speed, 0f);
        harness.invoke(methods.get("adv_player_resume"), player);
        assertEquals(3f, player.speed, 0f);
        hooks.close();
        assertEquals(3f, player.speed, 0f);
        assertEquals(1.5f, manual.restorationSpeed(2f), 0f);
        manual.restore(() -> harness.call(methods.get("adv_player_set_speed"), player,
                manual.restorationSpeed(2f)));
        manual.close();
        assertEquals(1.5f, player.speed, 0f);
    }

    @Test public void manualRestoreWhileAutomaticEnabledDoesNotReplaceNativeBaseline() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true));
        Player player = new Player();
        harness.invoke(methods.get("adv_player_resume"), player);
        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_set_speed"), player, 3f));
        manual.restore(() -> harness.call(methods.get("adv_player_set_speed"), player, 2f));
        manual.close();
        hooks.close();
        assertEquals(1f, player.speed, 0f);
    }

    @Test public void playerManagerFacadeKeepsPerVideoManualSpeedAcrossAutoSpeedWritesAndPlayback() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlayerDelegate delegate = new PlayerDelegate();
        delegate.resetOnResume = true;
        delegate.resetOnPlay = true;
        PlayerManager manager = new PlayerManager(harness, delegate);
        PlaybackBehaviorHooks hooks = install(harness, methods,
                Map.of("auto_speed", true, "playback_speed", "2"));
        Player unrelated = new Player();
        harness.invoke(methods.get("adv_player_resume"), unrelated);
        harness.invoke(methods.get("adv_player_manager_resume"), manager);
        assertEquals(2f, delegate.speed, 0f);

        GestureSpeedOverride manual = new GestureSpeedOverride();
        manual.apply(3f, () -> harness.call(methods.get("adv_player_manager_set_speed"), manager, 3f));
        harness.invoke(methods.get("adv_player_manager_set_speed"), manager, 2f);
        assertEquals(3f, delegate.speed, 0f);
        harness.invoke(methods.get("adv_player_manager_resume"), manager);
        assertEquals(3f, delegate.speed, 0f);
        assertEquals("video", harness.invoke(methods.get("adv_player_manager_play"), manager));
        assertEquals(3f, delegate.speed, 0f);

        hooks.setOptions(new LocalOptions(Map.of("auto_speed", true, "playback_speed", "4")));
        assertEquals(3f, delegate.speed, 0f);
        assertEquals(4f, unrelated.speed, 0f);
        hooks.setOptions(new LocalOptions(Collections.emptyMap()));
        assertEquals(3f, delegate.speed, 0f);
        assertEquals(1f, unrelated.speed, 0f);
        harness.invoke(methods.get("adv_player_manager_resume"), manager);
        assertEquals(3f, delegate.speed, 0f);
        assertEquals("video", harness.invoke(methods.get("adv_player_manager_play"), manager));
        assertEquals(3f, delegate.speed, 0f);

        manual.restore(() -> harness.call(methods.get("adv_player_manager_set_speed"), manager,
                manual.restorationSpeed(2f)));
        manual.close();
        hooks.setOptions(new LocalOptions(Map.of("auto_speed", true, "playback_speed", "2")));
        harness.invoke(methods.get("adv_player_manager_resume"), manager);
        assertEquals(2f, delegate.speed, 0f);
        hooks.close();
        assertEquals(1f, delegate.speed, 0f);
        assertEquals(1f, unrelated.speed, 0f);
    }

    @Test public void failedManualWriteCleansItsScopeAndNewEngineBinding() throws Throwable {
        Harness harness = new Harness();
        Map<String, Method> methods = speedMethods();
        PlaybackBehaviorHooks hooks = install(harness, methods, Map.of("auto_speed", true));
        Player player = new Player();
        GestureSpeedOverride manual = new GestureSpeedOverride();
        boolean failed = false;
        try {
            manual.apply(3f, () -> {
                harness.call(methods.get("adv_player_set_speed"), player, 3f);
                throw new ReflectiveOperationException("fixture failure");
            });
        } catch (ReflectiveOperationException expected) { failed = true; }
        assertTrue(failed);
        harness.invoke(methods.get("adv_player_set_speed"), player, 1f);
        assertEquals(2f, player.speed, 0f);
        manual.close();
        hooks.close();
        assertEquals(1f, player.speed, 0f);
    }

    @Test public void speedLayerDoesNotRegisterOrClaimNativeAutoNextFeatures() throws Throwable {
        Harness harness = new Harness();
        Method check = CommentGate.class.getMethod("check");
        Map<String, Method> methods = new HashMap<>(speedMethods());
        Method next = Pager.class.getMethod("next", String.class, boolean.class);
        Method previous = Pager.class.getMethod("previous", String.class, boolean.class);
        methods.put("adv_player_can_auto_next", check);
        methods.put("adv_player_comments_showing", CommentGate.class.getMethod("comments"));
        methods.put("adv_player_move_next", next);
        methods.put("adv_player_move_previous", previous);
        PlaybackBehaviorHooks hooks = install(harness, methods,
                Map.of("auto_speed", true, "pause_auto_on_comments", true, "smart_swipe", true));
        assertTrue(hooks.supports("auto_speed"));
        assertFalse(hooks.supports("pause_auto_on_comments"));
        assertFalse(hooks.supports("smart_swipe"));
        assertFalse(harness.interceptors.containsKey(check));
        assertFalse(harness.interceptors.containsKey(next));
        assertFalse(harness.interceptors.containsKey(previous));
        assertEquals(6, harness.interceptors.size());
        hooks.close();
    }

    private static Map<String, Method> speedMethods() throws Exception {
        Map<String, Method> methods = new HashMap<>();
        methods.put("adv_player_set_speed", Player.class.getMethod("setSpeed", float.class));
        methods.put("adv_player_get_speed", Player.class.getMethod("getSpeed"));
        methods.put("adv_player_resume", Player.class.getMethod("resume"));
        methods.put("adv_player_play", Player.class.getMethod("play"));
        methods.put("adv_player_manager_set_speed", PlayerManager.class.getMethod("setSpeed", float.class));
        methods.put("adv_player_manager_get_speed", PlayerManager.class.getMethod("getSpeed"));
        methods.put("adv_player_manager_resume", PlayerManager.class.getMethod("resume"));
        methods.put("adv_player_manager_play", PlayerManager.class.getMethod("play"));
        return methods;
    }

    private PlaybackBehaviorHooks install(Harness harness, Map<String, Method> methods,
                                            Map<String, ?> options) {
        ArrayList<RuleScanResult> results = new ArrayList<>();
        for (Map.Entry<String, Method> entry : methods.entrySet()) {
            Method method = entry.getValue();
            results.add(new RuleScanResult(entry.getKey(), "fixture", Collections.singletonList(
                    new HostCandidate("fixture", "classes.dex", HostTargets.descriptor(method.getDeclaringClass()),
                            method.getName(), HostTargets.descriptor(method)))));
        }
        return PlaybackBehaviorHooks.install(harness.framework, new HostScanReport("fixture", results, false),
                getClass().getClassLoader(), null, new LocalOptions(options), ignored -> { });
    }

    private static final class Harness {
        final Map<Method, XposedInterface.Hooker> interceptors = new HashMap<>();
        final XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] { XposedInterface.class }, (proxy, method, args) -> {
                    if (!method.getName().equals("hook")) return null;
                    Method target = (Method) args[0];
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[] { XposedInterface.HookBuilder.class }, (builder, operation, values) -> {
                                if (!operation.getName().equals("intercept")) return builder;
                                interceptors.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[] { XposedInterface.HookHandle.class }, (handle, action, inputs) -> {
                                            if (action.getName().equals("unhook")) interceptors.remove(target);
                                            return null;
                                        });
                            });
                });

        Object invoke(Method target, Object owner, Object... arguments) throws Throwable {
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[] { XposedInterface.Chain.class }, (proxy, method, args) -> {
                        switch (method.getName()) {
                            case "getThisObject": return owner;
                            case "getExecutable": return target;
                            case "getArgs": return Arrays.asList(arguments);
                            case "getArg": return arguments[(Integer) args[0]];
                            case "proceed":
                                try { return target.invoke(owner, args == null ? arguments : (Object[]) args[0]); }
                                catch (InvocationTargetException failure) { throw failure.getCause(); }
                            default: return null;
                        }
                    });
            XposedInterface.Hooker interceptor = interceptors.get(target);
            return interceptor == null ? target.invoke(owner, arguments) : interceptor.intercept(chain);
        }

        void call(Method target, Object owner, Object... arguments) throws ReflectiveOperationException {
            try { invoke(target, owner, arguments); }
            catch (ReflectiveOperationException failure) { throw failure; }
            catch (RuntimeException | Error failure) { throw failure; }
            catch (Throwable failure) { throw new InvocationTargetException(failure); }
        }
    }
}
