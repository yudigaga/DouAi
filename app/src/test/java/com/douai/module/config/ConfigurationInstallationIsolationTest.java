package com.douai.module.config;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;

import com.douai.module.DouAiModule;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.FollowListSnapshot;
import com.douai.module.feature.LiquidGlassController;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostWorkQueue;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, application = ConfigurationInstallationIsolationTest.InstallationApplication.class)
public final class ConfigurationInstallationIsolationTest {
    private static final String ACCOUNT_SERVICE = "com.ss.android.ugc.aweme.account.AccountProxyService";
    private HostAdaptation.Profile previousProfile;
    private DouAiModule module;

    @Before public void setup() {
        previousProfile = HostAdaptation.currentProfile();
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_408);
        FollowListSnapshot.reset();
    }

    @After public void cleanup() throws Exception {
        try {
            if (module != null) {
                ClassLoader borrowedLoader = (ClassLoader) field(module, "hostClassLoader");
                ((HostWorkQueue) field(module, "hostWorkQueue")).close();
                for (Field field : DouAiModule.class.getDeclaredFields()) {
                    // The host/sandbox loader is supplied by the caller; the fixture owns neither it nor its parent.
                    if ("hostWorkQueue".equals(field.getName()) || "hostClassLoader".equals(field.getName())) continue;
                    field.setAccessible(true);
                    Object value = field.get(module);
                    if (value instanceof AutoCloseable) ((AutoCloseable) value).close();
                }
                if (borrowedLoader instanceof BorrowedHostClassLoader) {
                    assertEquals("Cleanup must not close a borrowed host ClassLoader", 0,
                            ((BorrowedHostClassLoader) borrowedLoader).closeCalls);
                }
                ShadowLooper.idleMainLooper();
            }
        } finally {
            FollowListSnapshot.reset();
            HostAdaptation.setCurrentProfile(previousProfile);
        }
    }

    @Test public void failedFollowListInstallationContinuesIndependentHooksAndRetryDoesNotDuplicateThem() throws Exception {
        AtomicInteger failures = new AtomicInteger();
        ClassLoader loader = new BorrowedHostClassLoader(getClass().getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (ACCOUNT_SERVICE.equals(name)) {
                    failures.incrementAndGet();
                    throw new NoClassDefFoundError("fixture relationship installation failure");
                }
                return super.loadClass(name, resolve);
            }
        };
        RecordingFramework framework = new RecordingFramework();
        module = module(RuntimeEnvironment.getApplication(), loader, framework);

        install(module);

        assertEquals(1, failures.get());
        assertNull(field(module, "nativeFollowLists"));
        assertFalse("A failed factory must not leave a partial static service active", NativeFollowLists.isAvailable());
        assertNotNull(field(module, "automationDispatcher"));
        Object version = field(module, "versionInfoHook");
        assertNotNull("An unrelated hook after the failed installer must still install", version);
        assertEquals(Boolean.TRUE, field(module, "additionalInitialized"));
        int registered = framework.registrationCount("DouAi.VersionInfo.");
        assertTrue(registered > 0);

        install(module);

        assertEquals("Only the failed service is retried", 2, failures.get());
        assertSame(version, field(module, "versionInfoHook"));
        assertEquals("Successful hooks must not be registered twice", registered,
                framework.registrationCount("DouAi.VersionInfo."));
    }

    @Test public void dispatcherContextFailureSkipsItsConsumersButPreservesMessagingFallbackAndIndependentHooks() throws Exception {
        AtomicInteger applicationReads = new AtomicInteger();
        Context context = new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() {
                // The first read belongs to LocalAnalytics; fail only the shared dispatcher read.
                if (applicationReads.incrementAndGet() == 2) {
                    throw new IllegalStateException("fixture dispatcher application context unavailable");
                }
                return super.getApplicationContext();
            }
        };
        RecordingFramework framework = new RecordingFramework();
        module = module(context, getClass().getClassLoader(), framework);

        install(module);

        assertNull(field(module, "automationDispatcher"));
        assertNull(field(module, "autoCommentDraftHook"));
        assertNull(field(module, "commentReplyHook"));
        assertDispatcherDependency(field(module, "autoCommentDraftHookFailure"));
        assertDispatcherDependency(field(module, "commentReplyHookFailure"));
        Object messaging = field(module, "nativeMessagingHooks");
        assertNotNull("Native messaging must retain its existing null-dispatcher fallback", messaging);
        assertEquals(Boolean.TRUE, field(messaging, "ownsDispatcher"));
        assertNotNull(field(messaging, "dispatcher"));
        assertNotNull(field(module, "versionInfoHook"));
        assertTrue(framework.registrationCount("DouAi.VersionInfo.") > 0);
        assertEquals(Boolean.TRUE, field(module, "additionalInitialized"));
        Object version = field(module, "versionInfoHook");
        int registered = framework.registrationCount("DouAi.VersionInfo.");

        install(module);

        assertNotNull("A later readiness pass retries the failed shared dispatcher", field(module, "automationDispatcher"));
        assertSame("An existing messaging fallback keeps its own lifecycle", messaging, field(module, "nativeMessagingHooks"));
        assertEquals(Boolean.TRUE, field(messaging, "ownsDispatcher"));
        assertSame(version, field(module, "versionInfoHook"));
        assertEquals(registered, framework.registrationCount("DouAi.VersionInfo."));
        assertNotEquals("dispatcher_unavailable", field(module, "autoCommentDraftHookFailure"));
        assertNotEquals("dispatcher_unavailable", field(module, "commentReplyHookFailure"));
    }

    @Test public void downloadOptionsFailureDoesNotPreventLaterMessagingInstallationOrTailPublication() throws Exception {
        RecordingFramework framework = new RecordingFramework();
        module = module(RuntimeEnvironment.getApplication(), getClass().getClassLoader(), framework);
        Constructor<DownloadActionHook> constructor = DownloadActionHook.class.getDeclaredConstructor(
                Method.class, FeatureSettings.class, Consumer.class);
        constructor.setAccessible(true);
        DownloadActionHook download = constructor.newInstance(null, FeatureSettings.defaults(),
                (Consumer<String>) ignored -> { });
        set(download, "thirdPartyDownloadDialog", (AutoCloseable) () -> {
            throw new AssertionError("fixture download dialog cleanup failure");
        });
        set(module, "downloadActionHook", download);

        install(module);

        assertSame(download, field(module, "downloadActionHook"));
        assertNotNull(field(module, "downloadHookFailure"));
        assertNotNull("Messaging is installed after additional download configuration", field(module, "nativeMessagingHooks"));
        assertEquals(Boolean.TRUE, field(module, "additionalInitialized"));

        install(module);

        assertNull("A successful retry clears the installation-options failure", field(module, "downloadHookFailure"));
    }

    @Test public void glassHookAndCleanupFailuresCannotEscapeTheMainThreadInstallationHandoff() throws Exception {
        InstallationApplication app = (InstallationApplication) RuntimeEnvironment.getApplication();
        RecordingFramework framework = new RecordingFramework();
        framework.rejectedPrefix = "DouAi.Advanced.liquid_glass.";
        module = module(app, getClass().getClassLoader(), framework);
        set(module, "hostProfile", HostAdaptation.PROFILE_410);
        HostAdaptation.setCurrentProfile(HostAdaptation.PROFILE_410);
        app.failGlassUnregister = true;

        install(module);

        assertNotNull("The real controller was constructed before window-hook registration failed", app.lastGlass);
        assertEquals(Boolean.TRUE, field(app.lastGlass, "closed"));
        assertFalse(app.callbacks.contains(app.lastGlass));
        assertNull(field(module, "liquidGlassController"));
        assertNull(field(module, "liquidGlassWindowHooks"));
        assertNotNull(field(module, "versionInfoHook"));
        assertEquals(Boolean.TRUE, field(module, "additionalInitialized"));

        framework.rejectedPrefix = null;
        install(module);

        assertNotNull("The failed glass candidate can be retried", field(module, "liquidGlassController"));
        assertNotNull(field(module, "liquidGlassWindowHooks"));
    }

    private static void assertDispatcherDependency(Object failure) {
        assertNotNull("The unavailable shared dependency needs its own installation diagnosis", failure);
        assertTrue(String.valueOf(failure).toLowerCase(Locale.ROOT).contains("dispatcher"));
    }

    private static DouAiModule module(Context context, ClassLoader loader, RecordingFramework framework) throws Exception {
        DouAiModule module = new DouAiModule();
        module.attachFramework(framework.api, () -> { });
        set(module, "targetContext", context);
        set(module, "hostClassLoader", loader);
        set(module, "hostProfile", HostAdaptation.PROFILE_408);
        set(module, "hostScanReport", new HostScanReport("fixture", Collections.emptyList(), false));
        set(module, "extendedSettings", ExtendedSettings.readFrom(RuntimeEnvironment.getApplication()
                .getSharedPreferences("installation-isolation", Context.MODE_PRIVATE)));
        set(module, "configurationReady", true);
        return module;
    }

    private static void install(DouAiModule module) throws Exception {
        Method install = DouAiModule.class.getDeclaredMethod("installFeatureHooksIfReady");
        install.setAccessible(true);
        ((CompletableFuture<?>) install.invoke(module)).get(5, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
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

    private static class BorrowedHostClassLoader extends ClassLoader implements AutoCloseable {
        int closeCalls;
        BorrowedHostClassLoader(ClassLoader parent) { super(parent); }
        @Override public void close() { closeCalls++; }
    }

    private static final class RecordingFramework {
        private final Map<String, Integer> registrations = new HashMap<>();
        String rejectedPrefix;
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    String[] id = {""};
                    return Proxy.newProxyInstance(getClass().getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, action, values) -> {
                                if ("setId".equals(action.getName())) id[0] = (String) values[0];
                                if (!"intercept".equals(action.getName())) return builder;
                                if (rejectedPrefix != null && id[0].startsWith(rejectedPrefix)) {
                                    throw new IllegalStateException("fixture rejected hook registration: " + id[0]);
                                }
                                registrations.merge(id[0], 1, Integer::sum);
                                return Proxy.newProxyInstance(getClass().getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, call, args) -> null);
                            });
                });

        int registrationCount(String prefix) {
            int count = 0;
            for (Map.Entry<String, Integer> entry : registrations.entrySet()) {
                if (entry.getKey().startsWith(prefix)) count += entry.getValue();
            }
            return count;
        }
    }

    public static final class InstallationApplication extends Application {
        final Set<ActivityLifecycleCallbacks> callbacks = Collections.newSetFromMap(new IdentityHashMap<>());
        LiquidGlassController lastGlass;
        boolean failGlassUnregister;

        @Override public void registerActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            super.registerActivityLifecycleCallbacks(callback);
            callbacks.add(callback);
            if (callback instanceof LiquidGlassController) lastGlass = (LiquidGlassController) callback;
        }

        @Override public void unregisterActivityLifecycleCallbacks(ActivityLifecycleCallbacks callback) {
            super.unregisterActivityLifecycleCallbacks(callback);
            callbacks.remove(callback);
            if (callback instanceof LiquidGlassController && failGlassUnregister) {
                failGlassUnregister = false;
                throw new IllegalStateException("fixture glass lifecycle cleanup failure");
            }
        }
    }
}
