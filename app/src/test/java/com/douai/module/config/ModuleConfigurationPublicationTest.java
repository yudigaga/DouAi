package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;

import com.douai.module.DouAiModule;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.AutomationDispatcher;
import com.douai.module.feature.FeedAdFilterHook;
import com.douai.module.feature.FeedAdFilterHookTest;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostWorkQueue;
import com.douai.module.ui.ConfigurationStatus;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ModuleConfigurationPublicationTest {
    private DouAiModule module;
    private boolean previousDebug;
    private final List<AutoCloseable> receivers = new ArrayList<>();
    private final List<HostWorkQueue> queues = new ArrayList<>();

    @Test public void moduleStatusUsesTheSameReadFailureRecoveryAndCloseSnapshotAsDiagnostics() throws Exception {
        SharedPreferences accepted = preferences(values("status-view"));
        update(accepted);
        flushWorker();
        ShadowLooper.idleMainLooper();
        ConfigurationStatus before = configurationStatus();
        assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, before.title);
        Object desired = field(module, "desiredConfigurationSnapshot");

        SharedPreferences broken = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) throw new IllegalStateException("fixture read failure");
                    throw new AssertionError("Unexpected preference operation: " + method.getName());
                });
        update(broken);
        ShadowLooper.idleMainLooper();
        ConfigurationStatus failed = configurationStatus();
        assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, failed.title);
        assertFalse(failed.applied);
        assertFalse(failed.pending);
        assertSame("The status surface cannot mutate the retained desired configuration", desired,
                field(module, "desiredConfigurationSnapshot"));

        update(accepted);
        flushWorker();
        ShadowLooper.idleMainLooper();
        assertEquals("READY", configurationDiagnostics().getString("configuration.read_status"));
        assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, configurationStatus().title);
        assertNotEquals(failed.title, configurationStatus().title);

        invoke("closeConfigurationPublication");
        ShadowLooper.idleMainLooper();
        assertTrue(configurationDiagnostics().getBoolean("configuration.closed"));
        assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, configurationStatus().title);
        assertFalse(configurationStatus().applied);
        assertFalse(configurationStatus().pending);
    }

    @Test public void moduleStatusShowsDeferredImportWithoutPublishingItsIntermediateValues() throws Exception {
        SharedPreferences preferences = RuntimeEnvironment.getApplication().getSharedPreferences(
                "publication-status-import", Context.MODE_PRIVATE);
        write(preferences, "before-import");
        update(preferences);
        flushWorker();
        ShadowLooper.idleMainLooper();
        Object desired = field(module, "desiredConfigurationSnapshot");
        long revision = state().revision;
        try (ConfigImportTransaction transaction = ConfigImportTransaction.begin(preferences, preferences.getAll())) {
            preferences.edit().putString("download_name_template", "intermediate").commit();
            update(preferences);
            ShadowLooper.idleMainLooper();
            assertEquals("DEFERRED", configurationDiagnostics().getString("configuration.read_status"));
            assertTrue(configurationStatus().pending);
            assertFalse(configurationStatus().applied);
            assertSame(desired, field(module, "desiredConfigurationSnapshot"));
            assertEquals(revision, state().revision);
            assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, configurationStatus().title);
        }
        update(preferences);
        flushWorker();
        ShadowLooper.idleMainLooper();
        assertEquals("READY", configurationDiagnostics().getString("configuration.read_status"));
        assertEquals(ConfigurationStatus.fromDiagnostics(configurationDiagnostics()).title, configurationStatus().title);
    }

    private Bundle configurationDiagnostics() throws Exception {
        Method method = DouAiModule.class.getDeclaredMethod("diagnosticsSnapshot");
        method.setAccessible(true);
        return (Bundle) method.invoke(module);
    }

    private ConfigurationStatus configurationStatus() throws Exception {
        return ConfigurationStatus.fromDiagnostics(configurationDiagnostics());
    }

    @Before public void setup() throws Exception {
        previousDebug = DebugLog.isEnabled();
        module = new DouAiModule();
        XposedInterface framework = (XposedInterface) Proxy.newProxyInstance(
                XposedInterface.class.getClassLoader(), new Class<?>[]{XposedInterface.class},
                (proxy, method, arguments) -> null);
        module.attachFramework(framework, () -> { });
        queues.add(queue());
    }

    @After public void cleanup() throws Exception {
        try {
            invoke("closeConfigurationPublication");
            Object registration = field(module, "configurationPublicationRegistration");
            if (registration instanceof AutoCloseable) ((AutoCloseable) registration).close();
            SharedPreferences preferences = (SharedPreferences) field(module, "hostPreferences");
            Object listener = field(module, "settingsListener");
            if (preferences != null && listener instanceof SharedPreferences.OnSharedPreferenceChangeListener) {
                preferences.unregisterOnSharedPreferenceChangeListener((SharedPreferences.OnSharedPreferenceChangeListener) listener);
            }
            for (AutoCloseable receiver : receivers) {
                if (receiver instanceof DownloadActionHook) set(receiver, "thirdPartyDownloadDialog", null);
                receiver.close();
            }
        } finally {
            for (HostWorkQueue queue : queues) queue.close();
            ShadowLooper.idleMainLooper();
            DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void rejectedWorkerConsumersDoNotBlockMainConsumersAndSameValuesRetryOnlyIncompleteNodes() throws Exception {
        FeedAdFilterHook feed = feedFilter();
        DownloadActionHook download = download();
        set(module, "feedAdFilterHook", feed);
        set(module, "downloadActionHook", download);
        LocalOptions feedBefore = options(feed);
        queue().close();
        Map<String, Object> values = values("queue-recovery");
        values.put("feed_filter_enabled", true);
        values.put("hide_product_cards", true);

        update(preferences(values));
        ShadowLooper.idleMainLooper();

        LocalOptions desired = (LocalOptions) field(module, "localOptions");
        ConfigurationPublication.NodeSnapshot failed = node("feed_filter.options");
        assertTrue(failed.status == ConfigurationPublication.Status.RETRY_SCHEDULED
                || failed.status == ConfigurationPublication.Status.FAILED);
        assertEquals(1, failed.attempts);
        assertEquals("java.util.concurrent.RejectedExecutionException", failed.failureClass);
        assertSame(feedBefore, options(feed));
        assertSame("An independent main-thread consumer still receives the desired snapshot", desired, options(download));
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
        assertEquals(1, node("download.options").attempts);
        long revision = state().revision;

        replaceQueue(new HostWorkQueue());
        update(preferences(values));
        flushWorker();
        ShadowLooper.idleMainLooper();

        assertEquals(revision, state().revision);
        assertSame("Retry resumes the original desired payload", desired, options(feed));
        assertTrue(((FeatureSettings) field(feed, "settings")).feedFilterEnabled);
        assertEquals(ConfigurationPublication.Status.APPLIED, node("feed_filter.settings").status);
        assertEquals(ConfigurationPublication.Status.APPLIED, node("feed_filter.options").status);
        assertEquals(2, node("feed_filter.settings").attempts);
        assertEquals(2, node("feed_filter.options").attempts);
        assertSame("Same-value recovery must not replay a successful receiver with a new object", desired, options(download));
        assertEquals(1, node("download.options").attempts);
    }

    @Test public void queuedDownloadConfigurationIsApplyingUntilTheActualMainMutationReturns() throws Exception {
        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        LocalOptions before = options(download);

        updateOnWorker(preferences(values("main-ack")));

        assertSame(before, options(download));
        assertEquals(ConfigurationPublication.Status.APPLYING, node("download.options").status);
        assertEquals(0, node("download.options").appliedRevision);
        assertEquals(1, node("download.options").attempts);

        ShadowLooper.idleMainLooper();

        assertSame(field(module, "localOptions"), options(download));
        assertEquals("main-ack", options(download).text("download_name_template", ""));
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
        assertEquals(state().revision, node("download.options").appliedRevision);
    }

    @Test public void newerRelevantRevisionCancelsTheQueuedOldDownloadMutation() throws Exception {
        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        AtomicInteger mutations = observeDownloadOptions(download);
        LocalOptions before = options(download);

        updateOnWorker(preferences(values("superseded")));
        updateOnWorker(preferences(values("latest")));

        LocalOptions latest = (LocalOptions) field(module, "localOptions");
        assertEquals(2, state().revision);
        assertSame(before, options(download));
        assertEquals(0, mutations.get());
        assertEquals(ConfigurationPublication.Status.APPLYING, node("download.options").status);

        ShadowLooper.idleMainLooper();

        assertSame(latest, options(download));
        assertEquals("latest", options(download).text("download_name_template", ""));
        assertEquals("Only the current revision may execute the real option invalidation", 1, mutations.get());
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
        assertEquals(2, node("download.options").appliedRevision);
        assertEquals(1, node("download.options").attempts);
    }

    @Test public void receiverInstalledLaterBindsTheExistingDesiredRevisionWithoutReadingPreferencesAgain() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        update(preferences(values("late-receiver"), reads));
        flushWorker();
        LocalOptions desired = (LocalOptions) field(module, "localOptions");
        long revision = state().revision;
        assertEquals(ConfigurationPublication.Status.WAITING_NOT_INSTALLED, node("download.options").status);
        assertEquals(0, node("download.options").attempts);

        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        invoke("refreshConfigurationBindings");
        ShadowLooper.idleMainLooper();

        assertEquals(1, reads.get());
        assertEquals(revision, state().revision);
        assertSame(desired, options(download));
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
        assertEquals(revision, node("download.options").appliedRevision);
        assertEquals(1, node("download.options").attempts);
    }

    @Test public void readFailurePreservesTheLastDesiredReferencesAndValidSameValuesRestoreReadiness() throws Exception {
        SharedPreferences broken = (SharedPreferences) Proxy.newProxyInstance(
                SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) throw new IllegalStateException("fixture preferences read failure");
                    throw new AssertionError("Unexpected preference operation: " + method.getName());
                });
        assertReadFailureRetainsDesired(broken, IllegalStateException.class);
    }

    @Test public void typedSnapshotFailurePreservesTheLastDesiredReferencesAndRevision() throws Exception {
        Map<String, Object> malformed = values("malformed");
        malformed.put("download_enabled", "not-a-boolean");
        assertReadFailureRetainsDesired(preferences(malformed), ClassCastException.class);
    }

    @Test public void switchingPreferenceSourceClosesTheOldLedgerBeforeQueuedUiWorkCanMutate() throws Exception {
        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        AtomicInteger mutations = observeDownloadOptions(download);
        SharedPreferences first = RuntimeEnvironment.getApplication().getSharedPreferences("publication-source-first", Context.MODE_PRIVATE);
        SharedPreferences second = RuntimeEnvironment.getApplication().getSharedPreferences("publication-source-second", Context.MODE_PRIVATE);
        write(first, "old-source");
        write(second, "new-source");
        register(first);
        updateOnWorker(first);
        ConfigurationPublication<?> old = (ConfigurationPublication<?>) field(module, "configurationPublication");
        assertEquals(ConfigurationPublication.Status.APPLYING, old.snapshot().nodes.get("download.options").status);

        register(second);
        assertTrue("The previous source's pending publication is invalidated immediately", old.snapshot().closed);
        updateOnWorker(second);
        Object current = field(module, "configurationPublication");
        assertNotSame(old, current);

        ShadowLooper.idleMainLooper();

        assertSame(field(module, "localOptions"), options(download));
        assertEquals("new-source", options(download).text("download_name_template", ""));
        assertEquals("The retired source's queued setter must not run", 1, mutations.get());
        assertEquals(ConfigurationPublication.Status.CLOSED, old.snapshot().nodes.get("download.options").status);
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
    }

    private void assertReadFailureRetainsDesired(SharedPreferences broken, Class<?> failureClass) throws Exception {
        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        Map<String, Object> accepted = values("accepted");
        update(preferences(accepted));
        flushWorker();
        ShadowLooper.idleMainLooper();
        Object settings = field(module, "currentSettings"), extended = field(module, "extendedSettings");
        Object options = field(module, "localOptions"), desired = field(module, "desiredConfigurationSnapshot");
        Object values = field(module, "lastConfigurationValues");
        long revision = state().revision;
        int attempts = node("download.options").attempts;

        update(broken);

        assertEquals("FAILED", String.valueOf(field(module, "configurationReadStatus")));
        assertEquals(failureClass.getName(), field(module, "configurationReadFailureClass"));
        assertSame(settings, field(module, "currentSettings"));
        assertSame(extended, field(module, "extendedSettings"));
        assertSame(options, field(module, "localOptions"));
        assertSame(desired, field(module, "desiredConfigurationSnapshot"));
        assertSame(values, field(module, "lastConfigurationValues"));
        assertSame(options, options(download));
        assertEquals(revision, state().revision);

        update(preferences(accepted));
        flushWorker();
        ShadowLooper.idleMainLooper();

        assertEquals("READY", String.valueOf(field(module, "configurationReadStatus")));
        assertNull(field(module, "configurationReadFailureClass"));
        assertSame(options, field(module, "localOptions"));
        assertSame(options, options(download));
        assertEquals(revision, state().revision);
        assertEquals(attempts, node("download.options").attempts);
    }

    @Test public void successfulLocalBodyIsRetainedWhenItsAckBecomesObsoleteBeforeCompletion() throws Exception {
        Method prepare = DouAiModule.class.getDeclaredMethod("configurationAction", boolean.class, boolean.class, Runnable.class);
        prepare.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean current = new java.util.concurrent.atomic.AtomicBoolean(true);
        AtomicInteger calls = new AtomicInteger();
        ConfigurationApplyAction action = (ConfigurationApplyAction) prepare.invoke(module, true, false, (Runnable) () -> {
            calls.incrementAndGet();
            current.set(false);
        });
        assertTrue(action.apply(current::get).toCompletableFuture().isCancelled());
        assertEquals(1, calls.get());
        current.set(true);
        java.util.concurrent.CompletableFuture<Void> retried = action.apply(current::get).toCompletableFuture();
        assertTrue(retried.isDone());
        assertFalse(retried.isCompletedExceptionally());
        assertEquals("An actual successful local body must not be replayed merely to recover its ACK", 1, calls.get());
    }

    @Test public void lateMessagingConstructionCannotOverwriteAnAcknowledgedSharedDispatcherSnapshot() throws Exception {
        Map<String, Object> oldValues = values("captured-by-late-installer");
        oldValues.put("reply_daily_limit", "1");
        LocalOptions captured = new LocalOptions(oldValues);
        AutomationDispatcher dispatcher = new AutomationDispatcher(RuntimeEnvironment.getApplication(), ignored -> { });
        receivers.add(dispatcher);
        dispatcher.setOptions(captured);
        set(module, "automationDispatcher", dispatcher);
        Map<String, Object> latestValues = values("accepted-by-owner");
        latestValues.put("reply_daily_limit", "7");
        update(preferences(latestValues));
        flushWorker();
        ShadowLooper.idleMainLooper();
        LocalOptions acknowledged = (LocalOptions) field(module, "localOptions");
        assertSame(acknowledged, options(dispatcher));
        assertEquals(ConfigurationPublication.Status.APPLIED, node("automation_dispatcher").status);
        long revision = state().revision;
        int dispatcherAttempts = node("automation_dispatcher").attempts;

        Constructor<NativeMessagingHooks> constructor = NativeMessagingHooks.class.getDeclaredConstructor(
                Context.class, LocalOptions.class, Consumer.class, AutomationDispatcher.class);
        constructor.setAccessible(true);
        NativeMessagingHooks late = constructor.newInstance(RuntimeEnvironment.getApplication(), captured,
                (Consumer<String>) ignored -> { }, dispatcher);
        receivers.add(late);
        assertEquals(Boolean.FALSE, field(late, "ownsDispatcher"));
        assertSame("A child constructor must leave the shared owner's newer accepted options intact", acknowledged, options(dispatcher));
        set(module, "nativeMessagingHooks", late);
        invoke("refreshConfigurationBindings");
        ShadowLooper.idleMainLooper();

        assertEquals(revision, state().revision);
        assertSame(acknowledged, options(dispatcher));
        assertEquals("7", options(dispatcher).text("reply_daily_limit", ""));
        assertEquals(dispatcherAttempts, node("automation_dispatcher").attempts);
        assertEquals(ConfigurationPublication.Status.APPLIED, node("automation_dispatcher").status);
        assertSame("The late receiver itself binds the same latest desired revision", acknowledged, options(late));
        assertEquals(ConfigurationPublication.Status.APPLIED, node("native_messaging").status);
        assertEquals(revision, node("native_messaging").appliedRevision);
    }

    @Test @Config(sdk = {28, 35})
    public void unknownConsumerEffectRemainsFailedAcrossRelevantRevisionsUntilReceiverReplacement() throws Exception {
        DownloadActionHook download = download();
        set(module, "downloadActionHook", download);
        AtomicInteger cleanups = new AtomicInteger();
        set(download, "thirdPartyDownloadDialog", (AutoCloseable) () -> {
            cleanups.incrementAndGet();
            throw new AssertionError("cleanup outcome unknown after detaching the owned reference");
        });
        update(preferences(values("unknown-first")));
        flushWorker(); ShadowLooper.idleMainLooper();
        assertEquals(ConfigurationPublication.Reason.PERMANENT_FAILURE, node("download.options").reason);
        assertEquals(0, node("download.options").appliedRevision);
        update(preferences(values("unknown-successor")));
        flushWorker(); ShadowLooper.idleMainLooper();
        assertEquals(1, cleanups.get());
        assertEquals("Changing another related value must not erase an unresolved local effect",
                ConfigurationPublication.Reason.PERMANENT_FAILURE, node("download.options").reason);
        assertEquals(0, node("download.options").appliedRevision);
        DownloadActionHook replacement = download();
        set(module, "downloadActionHook", replacement);
        invoke("refreshConfigurationBindings");
        ShadowLooper.idleMainLooper();
        assertEquals(ConfigurationPublication.Status.APPLIED, node("download.options").status);
        assertSame(field(module, "localOptions"), options(replacement));
        assertEquals(state().revision, node("download.options").appliedRevision);
    }

    private DownloadActionHook download() throws Exception {
        Constructor<DownloadActionHook> constructor = DownloadActionHook.class.getDeclaredConstructor(
                Method.class, FeatureSettings.class, Consumer.class);
        constructor.setAccessible(true);
        DownloadActionHook download = constructor.newInstance(null, FeatureSettings.defaults(), (Consumer<String>) ignored -> { });
        receivers.add(download);
        return download;
    }

    private FeedAdFilterHook feedFilter() throws Exception {
        Method framework = FeedAdFilterHookTest.class.getDeclaredMethod("createFramework", AtomicReference.class);
        Method report = FeedAdFilterHookTest.class.getDeclaredMethod("createReport");
        framework.setAccessible(true);
        report.setAccessible(true);
        FeedAdFilterHook feed = FeedAdFilterHook.install((XposedInterface) framework.invoke(null, new AtomicReference<XposedInterface.Hooker>()),
                (HostScanReport) report.invoke(null), FeedAdFilterHookTest.class.getClassLoader(), FeatureSettings.defaults(), ignored -> { });
        receivers.add(feed);
        return feed;
    }

    /** Keep the owned dialog installed so every real setOptions invalidation is observable. */
    private static AtomicInteger observeDownloadOptions(DownloadActionHook download) throws Exception {
        AtomicInteger mutations = new AtomicInteger();
        AtomicReference<AutoCloseable> observer = new AtomicReference<>();
        observer.set(() -> {
            mutations.incrementAndGet();
            set(download, "thirdPartyDownloadDialog", observer.get());
        });
        set(download, "thirdPartyDownloadDialog", observer.get());
        return mutations;
    }

    private static Map<String, Object> values(String name) {
        Map<String, Object> values = new HashMap<>();
        values.put("download_enabled", true);
        values.put("download_custom_name", true);
        values.put("download_name_template", name);
        return values;
    }

    private static SharedPreferences preferences(Map<String, Object> values) {
        return preferences(values, new AtomicInteger());
    }

    private static SharedPreferences preferences(Map<String, Object> values, AtomicInteger reads) {
        Map<String, Object> captured = Collections.unmodifiableMap(new HashMap<>(values));
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, arguments) -> {
                    if ("getAll".equals(method.getName())) { reads.incrementAndGet(); return captured; }
                    throw new AssertionError("Live getter used outside the captured snapshot: " + method.getName());
                });
    }

    private static void write(SharedPreferences preferences, String name) {
        preferences.edit().clear().putBoolean("download_enabled", true).putBoolean("download_custom_name", true)
                .putString("download_name_template", name).commit();
    }

    private void update(SharedPreferences preferences) throws Exception {
        Method update = DouAiModule.class.getDeclaredMethod("updateConfiguration", SharedPreferences.class);
        update.setAccessible(true);
        update.invoke(module, preferences);
    }

    private void updateOnWorker(SharedPreferences preferences) throws Exception {
        queue().submit(() -> {
            try { update(preferences); }
            catch (Exception failure) { throw new AssertionError(failure); }
        }).get(5, TimeUnit.SECONDS);
    }

    private void register(SharedPreferences preferences) throws Exception {
        Method register = DouAiModule.class.getDeclaredMethod("registerSettingsListener", SharedPreferences.class);
        register.setAccessible(true);
        register.invoke(module, preferences);
    }

    private void invoke(String name) throws Exception {
        Method method = DouAiModule.class.getDeclaredMethod(name);
        method.setAccessible(true);
        method.invoke(module);
    }

    private void flushWorker() throws Exception { queue().submit(() -> { }).get(5, TimeUnit.SECONDS); }

    private HostWorkQueue queue() throws Exception { return (HostWorkQueue) field(module, "hostWorkQueue"); }

    private void replaceQueue(HostWorkQueue replacement) throws Exception {
        queues.add(replacement);
        set(module, "hostWorkQueue", replacement);
    }

    private ConfigurationPublication.Snapshot state() throws Exception {
        ConfigurationPublication.Snapshot state = (ConfigurationPublication.Snapshot) field(module, "configurationPublicationState");
        assertNotNull(state);
        return state;
    }

    private ConfigurationPublication.NodeSnapshot node(String id) throws Exception {
        ConfigurationPublication.NodeSnapshot node = state().nodes.get(id);
        assertNotNull(id, node);
        return node;
    }

    private static LocalOptions options(Object receiver) throws Exception { return (LocalOptions) field(receiver, "options"); }

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
