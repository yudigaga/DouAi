package com.douai.module;

import android.os.Bundle;
import android.os.Handler;
import androidx.compose.runtime.State;
import com.douai.module.config.FrameworkConfigSync;
import com.douai.module.config.ModuleConfigRuntime;
import com.douai.module.config.SettingsRepository;
import com.douai.module.ui.ConfigurationStatus;
import com.douai.module.ui.ModuleStatusScreen;
import io.github.libxposed.service.XposedService;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.CopyOnWriteArraySet;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
@LooperMode(LooperMode.Mode.PAUSED)
public final class MainActivityConfigurationStatusTest {
    private ActivityController<MainActivity> controller;
    private MainActivity activity;
    private ModuleStatusScreen screen;
    private ModuleConfigRuntime runtime;
    private XposedService service;
    private Object previousRuntime;
    private FrameworkConfigSync ownedSync;

    @Before public void setup() throws Exception {
        Field singleton = ModuleConfigRuntime.class.getDeclaredField("instance");
        singleton.setAccessible(true);
        previousRuntime = singleton.get(null);
        runtime = allocate(ModuleConfigRuntime.class);
        ownedSync = new FrameworkConfigSync(null);
        set(runtime, "repository", new SettingsRepository(RuntimeEnvironment.getApplication()));
        set(runtime, "sync", ownedSync);
        set(runtime, "statusListeners", new CopyOnWriteArraySet<>());
        set(runtime, "serviceListeners", new CopyOnWriteArraySet<>());
        singleton.set(null, runtime);
        controller = Robolectric.buildActivity(MainActivity.class).create().start().resume();
        activity = controller.get();
        screen = (ModuleStatusScreen) field(activity, "settingsScreen");
        runtime = (ModuleConfigRuntime) field(activity, "configRuntime");
        service = identityOnlyService();
        set(runtime, "service", service);
        set(runtime.sync(), "service", service);
        set(activity, "activeService", service);
    }

    @After public void cleanup() throws Exception {
        try {
            if (runtime != null) {
                set(runtime, "service", null);
                set(runtime.sync(), "service", null);
            }
            if (controller != null) controller.pause().stop().destroy();
        } finally {
            if (ownedSync != null) ownedSync.close();
            Field singleton = ModuleConfigRuntime.class.getDeclaredField("instance");
            singleton.setAccessible(true);
            singleton.set(null, previousRuntime);
        }
    }

    @Test public void pendingConfigurationContinuesTheExistingPollWhenScanningNeedsNoMoreWork() throws Exception {
        accept(configuration(1, "APPLYING"));
        assertTrue(configurationModel().pending);
        assertTrue(handler().hasCallbacks(poll()));
    }

    @Test public void nullDiagnosticsClearsPriorAppliedStateAndKeepsTheExistingRetry() throws Exception {
        accept(configuration(1, "APPLIED"));
        assertTrue(configurationModel().applied);
        accept(null);
        assertFalse(configurationModel().applied);
        assertEquals(ConfigurationStatus.unavailable().title, configurationModel().title);
        assertTrue(handler().hasCallbacks(poll()));
    }

    @Test public void olderRequestCannotReplaceTheLatestConfigurationAndPauseInvalidatesIt() throws Exception {
        long older = accept(configuration(1, "APPLYING"));
        accept(configuration(2, "APPLIED"));
        ConfigurationStatus latest = configurationModel();
        deliver(older, configuration(1, "APPLYING"));
        assertSame(latest, configurationModel());
        activity.onPause();
        assertFalse(configurationModel().applied);
        assertFalse(handler().hasCallbacks(poll()));
        deliver(requests().get(), configuration(3, "APPLIED"));
        assertFalse("A response while paused cannot restore the old success", configurationModel().applied);
        activity.onResume();
        assertEquals(ConfigurationStatus.unavailable().title, configurationModel().title);
    }

    @Test public void disconnectAndRebindClearSuccessAndRejectThePreviousServiceResponse() throws Exception {
        long oldRequest = accept(configuration(1, "APPLIED"));
        invoke("handleServiceDied", new Class<?>[]{XposedService.class}, service);
        assertFalse(configurationModel().applied);
        assertFalse(handler().hasCallbacks(poll()));
        deliver(oldRequest, configuration(1, "APPLIED"));
        assertFalse(configurationModel().applied);
        XposedService replacement = identityOnlyService();
        set(runtime, "service", replacement);
        set(runtime.sync(), "service", replacement);
        invoke("handleServiceBound", new Class<?>[]{XposedService.class}, replacement);
        assertEquals(ConfigurationStatus.unavailable().title, configurationModel().title);
        deliver(oldRequest, configuration(1, "APPLIED"));
        assertFalse(configurationModel().applied);
    }

    @Test public void frameworkWriteFailureDoesNotInventAServiceDisconnect() throws Exception {
        FrameworkConfigSync.Listener listener = (FrameworkConfigSync.Listener) field(activity, "configStatusListener");
        listener.onStatusChanged(false, "远程配置同步失败", "请稍后重试");
        assertEquals(Boolean.TRUE, stateValue("frameworkConnected"));
        assertEquals("远程配置同步失败", stateValue("frameworkMessage"));
        assertEquals("请稍后重试", stateValue("frameworkError"));
    }

    @Test public void destroyingTheActivityClosesItsScreenAndRejectsLateConfigurationResponses() throws Exception {
        long oldRequest = accept(configuration(1, "APPLIED"));
        controller.pause().stop().destroy();
        controller = null;
        ConfigurationStatus closed = configurationModel();
        assertFalse(closed.applied);
        assertNull(field(activity, "settingsScreen"));
        deliver(oldRequest, configuration(2, "APPLIED"));
        assertSame(closed, configurationModel());
        screen.setConfigurationStatus(ConfigurationStatus.fromDiagnostics(configuration(3, "APPLIED")));
        assertSame("Destroy must close the screen, not merely clear the activity reference", closed, configurationModel());
    }

    @Test public void nullRuntimeDeathClearsAppliedStateStopsPollingAndRejectsItsOldResponse() throws Exception {
        accept(configuration(1, "APPLYING"));
        long oldRequest = accept(configuration(1, "APPLIED"));
        assertTrue(configurationModel().applied);
        assertTrue(handler().hasCallbacks(poll()));
        set(runtime, "service", null);
        set(runtime.sync(), "service", null);

        invoke("handleServiceDied", new Class<?>[]{XposedService.class}, (Object) null);

        assertNull(field(activity, "activeService"));
        assertEquals(ConfigurationStatus.unavailable().title, configurationModel().title);
        assertFalse(configurationModel().applied);
        assertFalse(handler().hasCallbacks(poll()));
        assertTrue(requests().get() > oldRequest);
        deliver(oldRequest, configuration(1, "APPLIED"));
        assertFalse("A null death must invalidate the prior service's result", configurationModel().applied);
    }

    @Test public void lateNullDeathCannotClearStateAfterTheRuntimeHasBoundAReplacement() throws Exception {
        XposedService replacement = identityOnlyService();
        set(runtime, "service", replacement);
        set(runtime.sync(), "service", replacement);
        invoke("handleServiceBound", new Class<?>[]{XposedService.class}, replacement);
        accept(configuration(2, "APPLYING"));
        long currentRequest = accept(configuration(2, "APPLIED"));
        ConfigurationStatus current = configurationModel();
        assertTrue(current.applied);
        assertTrue(handler().hasCallbacks(poll()));

        invoke("handleServiceDied", new Class<?>[]{XposedService.class}, (Object) null);

        assertSame(replacement, field(activity, "activeService"));
        assertSame("The runtime's newer non-null service makes this null death stale", current, configurationModel());
        assertEquals(currentRequest, requests().get());
        assertTrue(handler().hasCallbacks(poll()));
    }

    private long accept(Bundle value) throws Exception {
        long token = requests().incrementAndGet();
        deliver(token, value);
        return token;
    }

    private void deliver(long token, Bundle value) throws Exception {
        invoke("acceptScanDiagnostics", new Class<?>[]{long.class, Bundle.class}, token, value);
    }

    private void invoke(String name, Class<?>[] types, Object... values) throws Exception {
        Method method = MainActivity.class.getDeclaredMethod(name, types);
        method.setAccessible(true);
        method.invoke(activity, values);
    }

    private ConfigurationStatus configurationModel() throws Exception {
        for (Field field : ModuleStatusScreen.class.getDeclaredFields()) {
            if (!State.class.isAssignableFrom(field.getType())) continue;
            field.setAccessible(true);
            Object value = ((State<?>) field.get(screen)).getValue();
            if (value instanceof ConfigurationStatus) return (ConfigurationStatus) value;
        }
        throw new AssertionError("Module status screen must retain its configuration model");
    }

    private Object stateValue(String property) throws Exception {
        return ((State<?>) field(screen, property + "$delegate")).getValue();
    }

    private Handler handler() throws Exception { return (Handler) field(activity, "mainHandler"); }
    private Runnable poll() throws Exception { return (Runnable) field(activity, "diagnosticsPoll"); }
    private AtomicLong requests() throws Exception { return (AtomicLong) field(activity, "diagnosticsRequestId"); }

    private static Bundle configuration(long revision, String status) {
        Bundle value = new Bundle();
        value.putBoolean("complete", true);
        value.putString("summary", "rules=0,");
        value.putString("configuration.read_status", "READY");
        value.putLong("configuration.revision", revision);
        value.putBoolean("configuration.closed", false);
        value.putString("configuration.summary", "APPLIED".equals(status) ? "SUCCESS" : "PENDING");
        String prefix = "configuration.consumer.fixture.";
        value.putBoolean(prefix + "installed", true);
        value.putLong(prefix + "desired_revision", revision);
        value.putLong(prefix + "applied_revision", "APPLIED".equals(status) ? revision : 0);
        value.putString(prefix + "status", status);
        value.putString(prefix + "reason", "APPLIED".equals(status) ? "NONE" : "APPLYING");
        value.putInt(prefix + "attempts", 1);
        value.putString(prefix + "failure_class", null);
        return value;
    }

    private static XposedService identityOnlyService() throws Exception {
        // The activity observes identity/availability only; this fixture never performs a Binder operation.
        return allocate(XposedService.class);
    }

    private static <T> T allocate(Class<T> type) throws Exception {
        // Isolate the activity from the process-global LSPosed listener; all fields it uses are explicit below.
        Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
        Field singleton = unsafeClass.getDeclaredField("theUnsafe");
        singleton.setAccessible(true);
        return type.cast(unsafeClass.getMethod("allocateInstance", Class.class)
                .invoke(singleton.get(null), type));
    }

    private static Object field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }

    private static void set(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(owner, value);
    }
}
