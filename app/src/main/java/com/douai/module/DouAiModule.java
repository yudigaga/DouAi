package com.douai.module;

import android.content.pm.ApplicationInfo;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.app.Application;
import android.app.Activity;
import android.app.Instrumentation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import io.github.libxposed.api.XposedModule;
import io.github.libxposed.api.XposedModuleInterface;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.douai.module.scan.HostRuleSet;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanCache;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostScanner;
import com.douai.module.scan.StartupHostCache;
import com.douai.module.scan.HostWorkQueue;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.ConfigurationChanges;
import com.douai.module.config.ConfigurationCatalog.Feature;
import com.douai.module.config.ConfigurationSnapshot;
import com.douai.module.config.ConfigImportTransaction;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.FrameworkConfigSync;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.feature.HostBehaviorHooks;
import com.douai.module.feature.PlaybackBehaviorHooks;
import com.douai.module.feature.ProfileSpoofHook;
import com.douai.module.feature.NativeMessagingHooks;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.feature.RecallHighlightHook;
import com.douai.module.feature.FeedGestureToolsHook;
import com.douai.module.feature.DirectMessageSpeechHook;
import com.douai.module.feature.HashLongPressVoiceHook;
import com.douai.module.feature.MediaModelHook;
import com.douai.module.feature.LocalAnalyticsHook;
import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.feature.CommentEnhancementsHook;
import com.douai.module.feature.CommentVoiceHook;
import com.douai.module.feature.NativeCommentSpeechHook;
import com.douai.module.config.SettingsRepository;
import com.douai.module.config.HostSettingsMigration;
import com.douai.module.diagnostics.ScanDiagnosticsChannel;
import com.douai.module.diagnostics.HostAdaptationDiagnostics;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DebugLogChannel;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.diagnostics.FeatureLogCatalog;
import com.douai.module.feature.DebugHookTrace;
import com.douai.module.feature.FeedAdFilterHook;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.VersionInfoHook;
import com.douai.module.feature.TopTabFilterHook;
import com.douai.module.feature.FeedUiHideHook;
import com.douai.module.feature.RefreshConfirmHook;
import com.douai.module.feature.AutoLikeHook;
import com.douai.module.feature.AutoFollowHook;
import com.douai.module.feature.AutoPlayHook;
import com.douai.module.feature.AutoCommentHook;
import com.douai.module.feature.AutomationDispatcher;
import com.douai.module.feature.CommentReplyHook;
import com.douai.module.feature.AutoMessageDraftHook;
import com.douai.module.feature.CommentToolsHook;
import com.douai.module.feature.ImmersiveModeController;
import com.douai.module.feature.HomeNavigationHideController;
import com.douai.module.ui.HostUiController;
import io.github.libxposed.api.XposedInterface;

public final class DouAiModule extends XposedModule {
    private static final String TARGET_PACKAGE = HostAdaptation.TARGET_PACKAGE;
    private static final String TAG = "DouAi";
    private volatile boolean isMainProcess;
    private volatile boolean scanScheduled;
    private volatile boolean configurationReady;
    private volatile BroadcastReceiver diagnosticsReceiver;
    private volatile BroadcastReceiver debugLogReceiver;
    private volatile XposedInterface tracedFramework;
    private volatile Bundle scanDiagnostics = new Bundle();
    private volatile Bundle hostVersionEvidence = new Bundle();
    private volatile HostAdaptation.Profile hostProfile;
    private volatile String hostRulesFingerprint;
    private final Object hostScanStateLock = new Object();
    private HostRuleSet packagedHostRules;
    private HostAdaptation.Profile packagedRulesProfile;
    private HostScanner.PreparedScan preparedHostScan;
    private final HostWorkQueue hostWorkQueue = new HostWorkQueue();
    private final Object hostWorkStateLock = new Object();
    private volatile SharedPreferences hostPreferences;
    private volatile Context targetContext;
    private volatile ApplicationInfo targetApplicationInfo;
    private volatile SharedPreferences.OnSharedPreferenceChangeListener settingsListener;
    private ConfigImportTransaction.Registration configurationPublicationRegistration;
    private volatile FeatureSettings currentSettings = FeatureSettings.defaults();
    private volatile ExtendedSettings extendedSettings;
    private volatile LocalOptions localOptions = new LocalOptions(java.util.Collections.emptyMap());
    private Map<String, ?> lastConfigurationValues;
    private boolean configurationUpdatePending;
    private volatile HostBehaviorHooks hostBehaviorHooks;
    private volatile PlaybackBehaviorHooks playbackBehaviorHooks;
    private volatile ProfileSpoofHook profileSpoofHook;
    private volatile NativeMessagingHooks nativeMessagingHooks;
    private volatile NativeFollowLists nativeFollowLists;
    private volatile AutomationDispatcher automationDispatcher;
    private volatile RecallHighlightHook recallHighlightHook;
    private volatile FeedGestureToolsHook feedGestureToolsHook;
    private volatile DirectMessageSpeechHook directMessageSpeechHook;
    private volatile HashLongPressVoiceHook hashLongPressVoiceHook;
    private volatile boolean additionalInitialized;
    private volatile MediaModelHook mediaModelHook;
    private volatile LocalAnalyticsHook localAnalyticsHook;
    private volatile String localAnalyticsHookFailure;
    private volatile CommentEnhancementsHook commentEnhancementsHook;
    private volatile CommentVoiceHook commentVoiceHook;
    private volatile NativeCommentSpeechHook nativeCommentSpeechHook;
    private volatile HostScanReport hostScanReport;
    private volatile ClassLoader hostClassLoader;
    private volatile FeedAdFilterHook feedAdFilterHook;
    private volatile DownloadActionHook downloadActionHook;
    private volatile VersionInfoHook versionInfoHook;
    private volatile TopTabFilterHook topTabFilterHook;
    private volatile FeedUiHideHook feedUiHideHook;
    private volatile RefreshConfirmHook refreshConfirmHook;
    private volatile AutoLikeHook autoLikeHook;
    private volatile AutoFollowHook autoFollowHook;
    private volatile AutoPlayHook autoPlayHook;
    private volatile AutoCommentHook autoCommentDraftHook;
    private volatile CommentReplyHook commentReplyHook;
    private volatile String commentReplyHookFailure;
    private volatile AutoMessageDraftHook autoMessageDraftHook;
    private volatile CommentToolsHook commentToolsHook;
    private volatile ImmersiveModeController immersiveModeController;
    private volatile HomeNavigationHideController homeNavigationHideController;
    private volatile HostUiController hostUiController;
    private volatile XposedInterface.HookHandle applicationCreateHandle;
    private volatile XposedInterface.HookHandle activityResumeHandle;
    private volatile String feedHookFailure;
    private volatile String downloadHookFailure;
    private volatile String versionHookFailure;
    private volatile String topTabHookFailure;
    private volatile String feedUiHookFailure;
    private volatile String refreshHookFailure;
    private volatile String autoLikeHookFailure;
    private volatile String autoFollowHookFailure;
    private volatile String autoPlayHookFailure;
    private volatile String autoCommentDraftHookFailure;
    private volatile String autoMessageDraftHookFailure;
    private volatile String commentToolsHookFailure;
    private volatile String nativeCommentSpeechHookFailure;
    private volatile String directMessageSpeechHookFailure;
    private volatile String hashLongPressVoiceHookFailure;
    private volatile String immersiveModeFailure;
    private volatile long hostScanMillis = -1;
    private volatile long hostHeapUsedBytes = -1;

    @Override
    public void onModuleLoaded(XposedModuleInterface.ModuleLoadedParam param) {
        DouAiLog.setFrameworkSink((priority, line) -> DouAiLog.writeFramework(
                (level, part) -> super.log(level, TAG, part), priority, line));
        DouAiLog.environment(BuildConfig.VERSION_NAME, Integer.toString(BuildConfig.VERSION_CODE),
                "unknown", param.getProcessName(), android.os.Process.myPid());
        isMainProcess = TARGET_PACKAGE.equals(param.getProcessName());
        DouAiLog.event("module_startup", "process_gate", isMainProcess ? "target_main_process" : "other_process",
                "module=" + BuildConfig.VERSION_NAME + ";api=" + getApiVersion());
        moduleLog(3, TAG, "DouAi " + BuildConfig.VERSION_NAME + " / API " + getApiVersion()
                + " loaded in " + param.getProcessName());
        if (isMainProcess) installHostApplicationHook();
    }

    @Override
    public void onPackageLoaded(XposedModuleInterface.PackageLoadedParam param) {
        if (!isMainProcess || !TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()
                || scanScheduled) {
            DouAiLog.event("host_scan", "scan_gate", "package_not_eligible",
                    "main_process=" + isMainProcess + ";target_package=" + TARGET_PACKAGE.equals(param.getPackageName())
                            + ";first_package=" + param.isFirstPackage() + ";already_scheduled=" + scanScheduled);
            return;
        }

        ApplicationInfo appInfo = param.getApplicationInfo();
        if (appInfo == null || !"com.ss.android.ugc.aweme".equals(appInfo.packageName)) {
            DouAiLog.event("host_scan", "scan_gate", "metadata_unavailable", "scan_started=false");
            moduleLog(6, TAG, "Target package metadata is unavailable");
            return;
        }

        scanScheduled = true;
        targetApplicationInfo = appInfo;
        installHostApplicationHook();
        List<File> archives = getApkArchives(appInfo);
        try {
            hostProfile = com.douai.module.scan.HostApkProfile.read(archives.get(0));
            DouAiLog.event("host_scan", "host_profile", hostProfile == null ? "unsupported" : "recognized",
                    "archive_count=" + archives.size() + ";business_readiness=not_implied");
            HostAdaptation.setCurrentProfile(hostProfile);
            if (hostProfile != null) {
                moduleLog(3, TAG, "Selected host profile " + hostProfile.versionName + " / " + hostProfile.versionCode
                        + (hostProfile == HostAdaptation.PROFILE_408
                        ? "; build=" + HostAdaptation.VERIFIED_UPDATE_VERSION_CODE : ""));
            } else {
                com.douai.module.scan.HostApkProfile.Identity actual =
                        com.douai.module.scan.HostApkProfile.readIdentity(archives.get(0));
                moduleLog(5, TAG, "Unsupported host APK " + actual.versionName + " / " + actual.versionCode
                        + "; build=" + actual.updateVersionCode);
            }
        } catch (IOException | RuntimeException failure) {
            hostProfile = null;
            HostAdaptation.setCurrentProfile(null);
            moduleLog(5, TAG, "Host profile unavailable: " + failure.getClass().getSimpleName());
        }
        Thread thread = new Thread(() -> scanHost(archives), "DouAi-DexScanner");
        thread.setDaemon(true);
        thread.start();
    }

    @Override
    public void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        if (!isMainProcess || !TARGET_PACKAGE.equals(param.getPackageName()) || !param.isFirstPackage()) {
            return;
        }
        hostClassLoader = param.getClassLoader();
        if (hostClassLoader == null) {
            feedHookFailure = "抖音 ClassLoader 不可用";
            downloadHookFailure = "抖音 ClassLoader 不可用";
            return;
        }
        installEarlyCachedHooks();
        installFeatureHooksIfReady();
    }

    private synchronized void installEarlyCachedHooks() {
        ApplicationInfo info = targetApplicationInfo;
        if (info == null || info.dataDir == null || hostBehaviorHooks != null) return;
        android.os.Trace.beginSection("DouAi.startupCacheAndGates");
        try {
            File cache = new File(info.dataDir, "no_backup/douai/host-scan-v2.cache");
            if (!cache.isFile()) return;
            List<File> archives = getApkArchives(info);
            HostRuleSet rules = loadRulesFromModuleApk();
            HostScanReport report = StartupHostCache.load(cache, prepareHostScan(archives, rules));
            if (report == null) return;
            hostScanReport = report;
            hostBehaviorHooks = HostBehaviorHooks.install(hookFramework(), report, hostClassLoader, null,
                    localOptions, message -> moduleLog(3, TAG, message));
            moduleLog(3, TAG, "Validated startup cache; basic host gates registered before Application.onCreate");
        } catch (Exception failure) {
            moduleLog(5, TAG, "Startup cache unavailable; asynchronous scan retained: " + failure.getClass().getSimpleName());
        } finally { android.os.Trace.endSection(); }
    }

    private void scanHost(List<File> archives) {
        DouAiLog.Operation operation = DouAiLog.begin("host_scan", "package_loaded");
        android.os.Trace.beginSection("DouAi.hostStartup");
        try {
            operation.stage("await_context", "archive_count=" + archives.size());
            Context hostContext = awaitHostContext();
            targetContext = hostContext;
            try {
                DebugLog.initialize(hostContext);
                DebugLog.setClearListener(() -> DebugHookTrace.reset(tracedFramework));
                if (debugLogReceiver == null) debugLogReceiver = DebugLogChannel.registerHost(hostContext);
            } catch (Throwable unavailable) {
                moduleLog(5, TAG, "Debug log initialization unavailable: " + unavailable.getClass().getSimpleName());
            }
            try {
                LocalAnalytics.initialize(hostContext);
            } catch (RuntimeException failure) {
                moduleLog(5, TAG, "Local analytics storage unavailable: " + failure.getClass().getSimpleName());
            }
            hostVersionEvidence = HostAdaptationDiagnostics.readInstalledVersion(hostContext);
            DouAiLog.environment(BuildConfig.VERSION_NAME, Integer.toString(BuildConfig.VERSION_CODE),
                    hostProfile == null ? "unknown" : hostProfile.versionName,
                    TARGET_PACKAGE, android.os.Process.myPid());
            HostUiController ui = ensureHostUiController(hostContext);
            registerDiagnosticsReceiver(hostContext);
            HostRuleSet rules = loadRulesFromModuleApk();
            File cacheFile = new File(new File(hostContext.getNoBackupFilesDir(), "douai"),
                    "host-scan-v2.cache");
            SharedPreferences modulePreferences = getRemotePreferences(FrameworkConfigSync.PREFERENCES_GROUP);
            SharedPreferences preferences = hostContext.getSharedPreferences(
                    SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
            if (HostSettingsMigration.copyOnce(modulePreferences, preferences)) {
                moduleLog(3, TAG, "Copied existing module settings to independent Douyin-local storage");
            }
            registerSettingsListener(preferences);
            operation.stage("configuration", "local_preferences_available=true");
            updateConfiguration(preferences);
            configurationReady = true;
            ensureImmersiveModeController(hostContext, extendedSettings == null
                    ? ExtendedSettings.readSystemBars(preferences) : extendedSettings);
            long scanStarted = android.os.SystemClock.elapsedRealtime();
            operation.stage("scan", "cache_checked=true;rules=" + rules.getRules().size());
            HostScanReport report = resolveHostScan(
                    prepareHostScan(archives, rules), new HostScanCache(cacheFile), new HostScanner.ProgressListener() {
                        @Override
                        public void onCacheHit(int dexCount) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onCacheHit(dexCount);
                        }

                        @Override
                        public void onCacheSaveFailed(Exception failure) {
                            moduleLog(5, TAG, "Host scan cache could not be saved: "
                                    + failure.getClass().getSimpleName() + ": " + failure.getMessage());
                        }

                        @Override
                        public void onScanStarted(int dexCount) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onScanStarted(dexCount);
                        }

                        @Override
                        public void onDexScanned(int completed, int total, String dexName) {
                            HostUiController controller = hostUiController;
                            if (controller != null) controller.onDexScanned(completed, total, dexName);
                        }
                    });
            hostScanMillis = Math.max(0, android.os.SystemClock.elapsedRealtime() - scanStarted);
            Runtime runtime = Runtime.getRuntime();
            hostHeapUsedBytes = runtime.totalMemory() - runtime.freeMemory();
            hostScanReport = report;
            operation.stage("install_features", "cache_hit=" + report.isCacheHit()
                    + ";scan_duration_ms=" + hostScanMillis + ";rule_count=" + report.getRuleResults().size());
            moduleLog(3, TAG, "Host scan complete: " + report.summary());
            // Only the scanner thread waits. Scan completion keeps its existing installation ordering.
            installFeatureHooksIfReady().get();
            publishDiagnostics(report, null);
            HostUiController completedUi = hostUiController;
            if (completedUi != null) completedUi.complete(report.isCacheHit(), report.summary(),
                    report.getHostIdentity());
            if (rules.getRules().isEmpty()) {
                moduleLog(5, TAG, "No verified host rules are packaged; no host methods were selected");
            } else if (!report.isCacheHit()) {
                for (com.douai.module.scan.RuleScanResult result : report.getRuleResults()) {
                    moduleLog(3, TAG, result.getRuleId() + "=" + result.getStatus()
                            + " candidates=" + result.getCandidates());
                }
            }
            operation.finish(DouAiLog.Outcome.SUCCESS, "scan_report_published",
                    "scan_report=true;feature_results=separate;cache_hit=" + report.isCacheHit());
        } catch (Exception exception) {
            operation.error("host_scan", exception);
            operation.finish(DouAiLog.Outcome.FAILED, "scan_failed", "scan_report_published=false");
            moduleLog(6, TAG, "Host scan failed", exception);
            publishDiagnostics(null, exception.getMessage());
            HostUiController ui = hostUiController;
            if (ui != null) ui.fail(exception.getMessage());
        } finally { android.os.Trace.endSection(); }
    }

    private synchronized void installHostApplicationHook() {
        if (applicationCreateHandle == null) {
            try {
                java.lang.reflect.Method onCreate = Instrumentation.class.getDeclaredMethod(
                        "callApplicationOnCreate", Application.class);
                applicationCreateHandle = hookFramework().hook(onCreate)
                        .setId("DouAi.Host.ApplicationUi")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object application = chain.getArg(0);
                            if (hostBehaviorHooks != null && application instanceof Application) {
                                try {
                                    SharedPreferences preferences = ((Application) application).getSharedPreferences(
                                            SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE);
                                    localOptions = LocalOptions.readFrom(preferences);
                                    hostBehaviorHooks.setOptions(localOptions);
                                } catch (RuntimeException failure) {
                                    moduleLog(5, TAG, "Could not read startup options: " + failure.getClass().getSimpleName());
                                }
                            }
                            Object result = chain.proceed();
                            if (application instanceof Application) {
                                try {
                                    ensureHostUiController((Application) application);
                                    ExtendedSettings startupSettings = extendedSettings;
                                    if (startupSettings == null) {
                                        startupSettings = ExtendedSettings.readSystemBars(
                                                ((Application) application).getSharedPreferences(
                                                        SettingsRepository.PREFERENCES_NAME, Context.MODE_PRIVATE));
                                    }
                                    ensureImmersiveModeController((Application) application, startupSettings);
                                } catch (Throwable failure) {
                                    moduleLog(5, TAG, "Could not initialize in-app controls: "
                                            + failure.getMessage());
                                }
                            }
                            return result;
                        });
                if (applicationCreateHandle == null) {
                    throw new IllegalStateException("Framework returned an empty Application hook handle");
                }
            } catch (Throwable failure) {
                applicationCreateHandle = null;
                moduleLog(5, TAG, "Could not hook target Application startup", failure);
            }
        }
        if (activityResumeHandle == null) {
            try {
                java.lang.reflect.Method onResume = Activity.class.getDeclaredMethod("onResume");
                activityResumeHandle = hookFramework().hook(onResume)
                        .setId("DouAi.Host.ActivityUi")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object activity = chain.getThisObject();
                            if (activity instanceof Activity) {
                                try {
                                    HostUiController controller = ensureHostUiController(
                                            ((Activity) activity).getApplication());
                                    if (controller != null) controller.onActivityResumed((Activity) activity);
                                } catch (Throwable failure) {
                                    moduleLog(5, TAG, "Could not attach controls to resumed Activity: "
                                            + failure.getMessage());
                                }
                            }
                            return result;
                        });
                if (activityResumeHandle == null) {
                    throw new IllegalStateException("Framework returned an empty Activity hook handle");
                }
            } catch (Throwable failure) {
                activityResumeHandle = null;
                moduleLog(5, TAG, "Could not hook Activity resume", failure);
            }
        }
    }

    private HostUiController ensureHostUiController(Context context) {
        if (hostUiController != null) return hostUiController;
        Context appContext = context == null ? null : context.getApplicationContext();
        if (!(appContext instanceof Application)
                || !TARGET_PACKAGE.equals(appContext.getPackageName())) return null;
        return ensureHostUiController((Application) appContext);
    }

    private HostUiController ensureHostUiController(Application application) {
        if (hostUiController != null) return hostUiController;
        if (application == null || !TARGET_PACKAGE.equals(application.getPackageName())) return null;
        synchronized (this) {
            if (hostUiController == null) {
                hostUiController = new HostUiController(application, message -> moduleLog(3, TAG, message));
            }
            return hostUiController;
        }
    }

    private synchronized void ensureImmersiveModeController(Context context, ExtendedSettings settings) {
        if (immersiveModeController != null || context == null) return;
        try {
            immersiveModeController = ImmersiveModeController.install(context, settings,
                    message -> moduleLog(3, TAG, message), () -> {
                        HostUiController ui = hostUiController;
                        return ui == null ? null : ui.getResumedActivity();
                    });
            immersiveModeFailure = null;
            moduleLog(3, TAG, "Reversible status-bar controller installed; enabled="
                    + (settings != null && settings.removeBlackEdges));
        } catch (Throwable failure) {
            immersiveModeFailure = failure.getMessage() == null
                    ? failure.getClass().getSimpleName() : failure.getMessage();
            moduleLog(6, TAG, "Status-bar controller installation failed", failure);
        }
    }

    private Context awaitHostContext() throws Exception {
        Class<?> activityThread = Class.forName("android.app.ActivityThread");
        java.lang.reflect.Method currentApplication = activityThread.getMethod("currentApplication");
        for (int attempt = 0; attempt < 60; attempt++) {
            Object application = currentApplication.invoke(null);
            if (application instanceof Context) {
                Context context = (Context) application;
                if (TARGET_PACKAGE.equals(context.getPackageName())) return context;
            }
            Thread.sleep(100L);
        }
        throw new IOException("Douyin Application context was not available");
    }

    private HostRuleSet loadRulesFromModuleApk() throws IOException {
        android.os.Trace.beginSection("DouAi.hostRules");
        try {
            synchronized (hostScanStateLock) {
                HostAdaptation.Profile profile = hostProfile;
                if (packagedHostRules != null && packagedRulesProfile == profile) return packagedHostRules;
                HostRuleSet rules = readRulesFromModuleApk();
                packagedRulesProfile = profile;
                packagedHostRules = rules;
                return rules;
            }
        } finally { android.os.Trace.endSection(); }
    }

    private HostRuleSet readRulesFromModuleApk() throws IOException {
        HostAdaptation.Profile profile = hostProfile;
        if (profile == null) throw new IOException("The installed host version has no verified profile");
        ApplicationInfo moduleInfo = getModuleApplicationInfo();
        if (moduleInfo == null || moduleInfo.sourceDir == null) {
            throw new IOException("Module APK path is unavailable");
        }
        try (ZipFile moduleApk = new ZipFile(moduleInfo.sourceDir)) {
            ZipEntry entry = moduleApk.getEntry("assets/" + profile.rulesAsset);
            if (entry == null) throw new IOException("Rule file is missing from module APK");
            try (InputStream input = moduleApk.getInputStream(entry)) {
                HostRuleSet rules = HostRuleSet.parse(input);
                if (!profile.matchesRules(rules.getFingerprint())) {
                    throw new IOException("The selected host rules do not match the verified profile");
                }
                hostRulesFingerprint = rules.getFingerprint();
                return rules;
            }
        }
    }

    private HostScanner.PreparedScan prepareHostScan(List<File> archives, HostRuleSet rules) throws IOException {
        android.os.Trace.beginSection("DouAi.prepareHostInventory");
        try {
            synchronized (hostScanStateLock) {
                if (preparedHostScan == null || !preparedHostScan.matches(archives, rules)) {
                    preparedHostScan = new HostScanner().prepare(archives, rules);
                }
                return preparedHostScan;
            }
        } finally { android.os.Trace.endSection(); }
    }

    private HostScanReport resolveHostScan(HostScanner.PreparedScan prepared, HostScanCache cache,
                                          HostScanner.ProgressListener listener) throws IOException {
        android.os.Trace.beginSection("DouAi.resolveHostScan");
        try { return new HostScanner().scanCached(prepared, cache, listener); }
        finally { android.os.Trace.endSection(); }
    }

    private void publishDiagnostics(HostScanReport report, String failure) {
        Bundle snapshot = new Bundle();
        snapshot.putBoolean("complete", report != null);
        snapshot.putLong("completed_at", System.currentTimeMillis());
        snapshot.putLong("host_scan_millis", hostScanMillis);
        snapshot.putLong("host_java_heap_used_bytes", hostHeapUsedBytes);
        if (failure != null) snapshot.putString("failure", failure);
        if (report != null) {
            snapshot.putString("identity", report.getHostIdentity());
            snapshot.putString("summary", report.summary());
            snapshot.putBoolean("cache_hit", report.isCacheHit());
            int unique = 0;
            int ambiguous = 0;
            int notFound = 0;
            for (com.douai.module.scan.RuleScanResult result : report.getRuleResults()) {
                String prefix = "rule." + result.getRuleId();
                snapshot.putString(prefix + ".status", result.getStatus().name());
                if (result.getStatus() == com.douai.module.scan.RuleScanResult.Status.UNIQUE) unique++;
                else if (result.getStatus() == com.douai.module.scan.RuleScanResult.Status.AMBIGUOUS) ambiguous++;
                else notFound++;
                StringBuilder candidates = new StringBuilder();
                for (com.douai.module.scan.HostCandidate candidate : result.getCandidates()) {
                    if (candidates.length() > 0) candidates.append('\n');
                    candidates.append(candidate);
                }
                snapshot.putString(prefix + ".candidates", candidates.toString());
            }
            snapshot.putInt("unique_count", unique);
            snapshot.putInt("ambiguous_count", ambiguous);
            snapshot.putInt("not_found_count", notFound);
            snapshot.putBoolean("all_rules_unique",
                    !report.getRuleResults().isEmpty() && unique == report.getRuleResults().size());
        }
        scanDiagnostics = snapshot;
        moduleLog(3, TAG, "Scan diagnostics snapshot updated: complete=" + (report != null));
    }

    private synchronized void registerDiagnosticsReceiver(Context hostContext) {
        if (diagnosticsReceiver != null) return;
        diagnosticsReceiver = ScanDiagnosticsChannel.registerHost(hostContext,
                this::diagnosticsSnapshot);
    }

    private synchronized void registerSettingsListener(SharedPreferences preferences) {
        if (hostPreferences == preferences && settingsListener != null) return;
        if (configurationPublicationRegistration != null) configurationPublicationRegistration.close();
        if (hostPreferences != null && settingsListener != null) {
            try { hostPreferences.unregisterOnSharedPreferenceChangeListener(settingsListener); }
            catch (RuntimeException ignored) { }
        }
        hostPreferences = preferences;
        configurationPublicationRegistration = ConfigImportTransaction.observe(
                preferences, this, this::scheduleConfigurationUpdate);
        SharedPreferences.OnSharedPreferenceChangeListener listener = (sharedPreferences, key) ->
                scheduleConfigurationUpdate();
        try {
            preferences.registerOnSharedPreferenceChangeListener(listener);
            settingsListener = listener;
        } catch (RuntimeException exception) {
            settingsListener = null;
            moduleLog(5, TAG, "Live configuration listener unavailable: " + exception.getMessage());
        }
    }

    private void scheduleConfigurationUpdate() {
        if (hostPreferences != null && ConfigImportTransaction.deferUntilSettled(
                hostPreferences, this, this::scheduleConfigurationUpdate)) return;
        synchronized (hostWorkStateLock) {
            if (configurationUpdatePending) return;
            configurationUpdatePending = true;
        }
        new Handler(Looper.getMainLooper()).post(() -> {
            hostWorkQueue.submit(() -> {
                synchronized (hostWorkStateLock) { configurationUpdatePending = false; }
                if (hostPreferences != null) updateConfiguration(hostPreferences);
            }).whenComplete((ignored, failure) -> {
                if (failure == null) return;
                synchronized (hostWorkStateLock) { configurationUpdatePending = false; }
                DouAiLog.failure("configuration", "scheduled_publication", failure);
            });
        });
    }

    private synchronized void updateConfiguration(SharedPreferences preferences) {
        android.os.Trace.beginSection("DouAi.publishConfiguration");
        try { updateConfigurationNow(preferences); }
        finally { android.os.Trace.endSection(); }
    }

    private void updateConfigurationNow(SharedPreferences preferences) {
        DouAiLog.Operation configurationOperation = null;
        try {
            ConfigurationSnapshot snapshot = ConfigurationSnapshot.readForPublication(
                    preferences, this, this::scheduleConfigurationUpdate);
            if (snapshot == null) return;
            Map<String, ?> values = snapshot.values;
            FeatureSettings settings = snapshot.features;
            ExtendedSettings extended = snapshot.extended;
            LocalOptions options = snapshot.options;

            boolean initial = lastConfigurationValues == null;
            ConfigurationChanges changes = ConfigurationChanges.between(lastConfigurationValues, values);
            if (!initial && changes.isEmpty()) return;
            DouAiLog.configRevision(DouAiLog.configurationRevision(values));
            configurationOperation = DouAiLog.begin("configuration", initial ? "initial_snapshot" : "preference_publication");
            configurationOperation.stage("read_committed_snapshot", "initial=" + initial + ";key_count=" + values.size());
            boolean wasDebug = DebugLog.isEnabled();
            DebugLog.setEnabled(options.enabled(LocalOptions.DEBUG_LOGGING));
            if (initial || changes.contains(LocalOptions.DEBUG_LOGGING) || wasDebug != DebugLog.isEnabled()) {
                DebugHookTrace.reset(tracedFramework);
                moduleLog(3, TAG, "Debug logging " + (DebugLog.isEnabled() ? "enabled; comment_json=automatic; message_json=automatic" : "disabled; mode=basic"));
            }

            currentSettings = settings;
            extendedSettings = extended;
            localOptions = options;
            DouAiLog.Operation selectedOperation = configurationOperation;
            selectedOperation.wrap(() -> logDebugConfiguration(values, changes)).run();
            lastConfigurationValues = values;
            configurationOperation.stage("dispatch_hot_updates", "snapshot_references_published=true");

            boolean feedFilterChanged = initial || changes.affects(Feature.FEED_FILTER);
            boolean downloadChanged = initial || changes.affects(Feature.DOWNLOAD);
            boolean replyChanged = initial || changes.affects(Feature.REPLY);
            boolean commentAutomationChanged = initial || changes.affects(Feature.COMMENT_AUTOMATION);
            boolean commentReplyChanged = initial || changes.affects(Feature.COMMENT_REPLY);
            boolean feedUiChanged = initial || changes.affects(Feature.FEED_UI);
            boolean playbackChanged = initial || changes.affects(Feature.PLAYBACK);
            boolean autoPlayChanged = initial || changes.affects(Feature.AUTO_PLAY);
            boolean gestureChanged = initial || changes.affects(Feature.GESTURE);
            boolean messageRecallChanged = initial || changes.affects(Feature.MESSAGE_RECALL);
            boolean spoofChanged = initial || changes.affects(Feature.SPOOF);

            if (nativeFollowLists != null && (initial || changes.affects(Feature.FOLLOW_LISTS))) {
                nativeFollowLists.setOptions(options);
            }
            if (automationDispatcher != null && (initial || changes.affects(Feature.AUTOMATION_DISPATCH))) {
                automationDispatcher.setOptions(options);
            }
            FeedAdFilterHook hook = feedAdFilterHook;
            if (hook != null && feedFilterChanged) {
                hook.setSettings(settings);
                hook.setOptions(options);
            }
            if (hostBehaviorHooks != null && (initial || changes.affects(Feature.HOST_BEHAVIOR))) hostBehaviorHooks.setOptions(options);
            if (feedGestureToolsHook != null && gestureChanged) feedGestureToolsHook.setOptions(options);
            if (playbackBehaviorHooks != null && playbackChanged) playbackBehaviorHooks.setOptions(options);
            if (profileSpoofHook != null && spoofChanged) profileSpoofHook.setOptions(options);
            if (nativeMessagingHooks != null && (replyChanged || messageRecallChanged)) nativeMessagingHooks.setOptions(options);
            if (recallHighlightHook != null && messageRecallChanged) recallHighlightHook.setOptions(options);
            if (directMessageSpeechHook != null && (initial || changes.affects(Feature.DIRECT_MESSAGE_SPEECH))) {
                directMessageSpeechHook.setOptions(options);
            }
            if (mediaModelHook != null && (initial || changes.affects(Feature.MEDIA_MODEL))) mediaModelHook.setOptions(options);
            if (commentEnhancementsHook != null && (initial || changes.affects(Feature.COMMENT_ENHANCEMENTS))) commentEnhancementsHook.setOptions(options);
            if (commentVoiceHook != null && (initial || changes.affects(Feature.COMMENT_VOICE))) {
                commentVoiceHook.setOptions(options);
            }
            if (nativeCommentSpeechHook != null && (initial || changes.affects(Feature.NATIVE_COMMENT_SPEECH))) nativeCommentSpeechHook.setOptions(options);
            if (hashLongPressVoiceHook != null && (initial || changes.contains(LocalOptions.HASH_LONG_PRESS_VOICE))) {
                hashLongPressVoiceHook.setOptions(options);
            }
            AutoLikeHook autoLike = autoLikeHook;
            if (autoLike != null && (initial || changes.affects(Feature.AUTO_LIKE))) {
                autoLike.setSettings(settings);
            }
            AutoFollowHook autoFollow = autoFollowHook;
            if (autoFollow != null && (initial || changes.affects(Feature.AUTO_FOLLOW))) {
                autoFollow.setSettings(settings);
            }
            AutoPlayHook autoPlay = autoPlayHook;
            if (autoPlay != null && autoPlayChanged) autoPlay.setSettings(extended, options);
            AutoCommentHook autoComment = autoCommentDraftHook;
            if (autoComment != null && commentAutomationChanged) autoComment.setOptions(options);
            if (commentReplyHook != null && commentReplyChanged) commentReplyHook.setOptions(options);
            AutoMessageDraftHook autoMessage = autoMessageDraftHook;
            if (autoMessage != null && (initial || changes.affects(Feature.AUTO_MESSAGE))) {
                autoMessage.setSettings(settings, extended);
            }
            CommentToolsHook commentHook = commentToolsHook;
            if (commentHook != null && (initial || changes.affects(Feature.COMMENT_TOOLS))) {
                commentHook.setSettings(extended);
            }
            ImmersiveModeController immersive = immersiveModeController;
            if (immersive != null && (initial || changes.affects(Feature.IMMERSIVE))) {
                immersive.setSettings(extended);
            }
            HomeNavigationHideController navigation = homeNavigationHideController;
            if (navigation != null && feedUiChanged) navigation.setSettings(extended);
            DownloadActionHook downloadHook = downloadActionHook;
            if (downloadHook != null && downloadChanged) {
                downloadHook.setSettings(settings);
                downloadHook.setOptions(options);
            }
            VersionInfoHook versionHook = versionInfoHook;
            if (versionHook != null && (initial || changes.affects(Feature.VERSION))) {
                versionHook.setSettings(extended);
            }
            TopTabFilterHook topTabHook = topTabFilterHook;
            if (topTabHook != null && (initial || changes.affects(Feature.TOP_TABS))) topTabHook.setSettings(extended);
            FeedUiHideHook uiHook = feedUiHideHook;
            if (uiHook != null && feedUiChanged) uiHook.setSettings(extended);
            RefreshConfirmHook refreshHook = refreshConfirmHook;
            if (refreshHook != null && (initial || changes.affects(Feature.REFRESH))) refreshHook.setSettings(extended);
            moduleLog(3, TAG, "Configuration updated: feedFilter=" + settings.feedFilterEnabled
                    + ", hideAds=" + settings.hideAds);
            selectedOperation.wrap(this::publishFeatureLogState).run();
            configurationOperation.finish(DouAiLog.Outcome.SUCCESS, "configuration_snapshot_published",
                    "snapshot_references_current=" + (currentSettings == settings && extendedSettings == extended && localOptions == options)
                            + ";result_scope=configuration_publication;feature_business_results=separate");
        } catch (RuntimeException exception) {
            if (configurationOperation != null) {
                configurationOperation.error("read_or_apply", exception);
                configurationOperation.finish(DouAiLog.Outcome.FAILED, "configuration_dispatch_failed",
                        "partial_hot_update_possible=true;feature_state_review_required=true");
            }
            DouAiLog.failure("configuration", "read_or_apply", exception);
            moduleLog(5, TAG, "Configuration read failed; previous settings remain active", exception);
        }
    }

    private java.util.concurrent.CompletableFuture<Void> installFeatureHooksIfReady() {
        // Package-ready and scan-ready can overlap. Keep both readiness checks in queue order;
        // coalescing a still-running "dependencies pending" check would lose the later ready event.
        java.util.concurrent.CompletableFuture<Void> installation =
                hostWorkQueue.submit(this::installFeatureHooksIfReadyOnWorker);
        installation.whenComplete((ignored, failure) -> {
            if (failure != null) DouAiLog.failure("module_startup", "scheduled_installation", failure);
        });
        return installation;
    }

    private void installFeatureHooksIfReadyOnWorker() {
        android.os.Trace.beginSection("DouAi.installFeatureHooks");
        try { installFeatureHooksIfReadyNow(); }
        finally { android.os.Trace.endSection(); }
    }

    private void installFeatureHooksIfReadyNow() {
        if (hostScanReport == null || hostClassLoader == null || targetContext == null || !configurationReady) {
            DouAiLog.event("module_startup", "feature_install_gate", "dependencies_pending",
                    "scan=" + (hostScanReport != null) + ";classloader=" + (hostClassLoader != null)
                            + ";context=" + (targetContext != null) + ";configuration=" + configurationReady);
            publishFeatureLogState();
            return;
        }
        if (localAnalyticsHook == null) {
            try {
                localAnalyticsHook = LocalAnalyticsHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, message -> moduleLog(3, TAG, message));
                localAnalyticsHookFailure = null;
                Activity resumed = hostUiController == null ? null : hostUiController.getResumedActivity();
                if (resumed != null) {
                    LocalAnalyticsHook installed = localAnalyticsHook;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (hostUiController != null && hostUiController.getResumedActivity() == resumed) {
                            installed.onActivityResumed(resumed);
                        }
                    });
                }
            } catch (Throwable failure) {
                localAnalyticsHookFailure = HostAdaptationDiagnostics.installationFailure(failure);
                DouAiLog.failure("local_analytics", "installation", failure);
                moduleLog(5, TAG, "Local analytics hook unavailable: " + localAnalyticsHookFailure);
            }
        }
        if (nativeFollowLists == null) {
            nativeFollowLists = NativeFollowLists.install(hostClassLoader, hostScanReport, localOptions,
                    message -> moduleLog(3, TAG, message));
        }
        if (automationDispatcher == null) {
            automationDispatcher = new AutomationDispatcher(targetContext, message -> moduleLog(3, TAG, message));
            automationDispatcher.setOptions(localOptions);
            Activity resumed = hostUiController == null ? null : hostUiController.getResumedActivity();
            if (resumed != null) {
                AutomationDispatcher dispatcher = automationDispatcher;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (hostUiController != null && hostUiController.getResumedActivity() == resumed) {
                        dispatcher.onActivityResumed(resumed);
                    }
                });
            }
        }
        HostUiController ui = hostUiController;
        if (ui != null) {
            try {
                ui.setSettingsPageTargets(resolveHookClass("host_settings_activity"),
                        resolveHookClass("host_settings_scroll"));
            } catch (Exception failure) {
                DouAiLog.failure("settings_entry", "installation", failure);
                moduleLog(5, TAG, "Native settings entry unavailable: " + failure.getMessage());
            }
        }
        if (feedAdFilterHook == null) {
            try {
                FeedAdFilterHook hook = FeedAdFilterHook.install(hookFramework(), hostScanReport,
                        hostClassLoader, currentSettings,
                        message -> moduleLog(3, TAG, message));
                feedAdFilterHook = hook;
                hook.setOptions(localOptions);
                feedHookFailure = null;
                moduleLog(3, TAG, "Feed response filter hook installed; enabled="
                        + hasActiveFeedFilters(currentSettings));
            } catch (Throwable failure) {
                feedHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("feed_filter", "installation", failure);
                moduleLog(6, TAG, "Feed response filter hook installation failed", failure);
            }
        }
        if (downloadActionHook == null) {
            try {
                java.lang.reflect.Method moduleName = resolveHookMethod("download_native_module_name");
                java.lang.reflect.Method viewFactory = resolveHookMethod("download_native_view_factory");
                java.lang.reflect.Method nativeClick = resolveHookMethod("download_native_click");
                downloadActionHook = DownloadActionHook.install(hookFramework(), moduleName, viewFactory,
                        nativeClick, currentSettings,
                        message -> moduleLog(3, TAG, message));
                downloadHookFailure = null;
                moduleLog(3, TAG, "Native download row extension installed; enabled="
                        + currentSettings.downloadEnabled);
            } catch (Throwable failure) {
                downloadHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("download", "installation", failure);
                moduleLog(6, TAG, "Native download row extension installation failed", failure);
            }
        }
        if (versionInfoHook == null) {
            try {
                versionInfoHook = VersionInfoHook.install(hookFramework(), hostClassLoader, extendedSettings,
                        message -> moduleLog(3, TAG, message));
                versionHookFailure = null;
                moduleLog(3, TAG, "PackageManager version hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.spoofVersion));
            } catch (Throwable failure) {
                versionHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("version", "installation", failure);
                moduleLog(6, TAG, "PackageManager version hook installation failed", failure);
            }
        }
        if (topTabFilterHook == null) {
            try {
                topTabFilterHook = TopTabFilterHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        extendedSettings, message -> moduleLog(3, TAG, message));
                topTabHookFailure = null;
                moduleLog(3, TAG, "Home tab filter hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.hideTopTabs));
            } catch (Throwable failure) {
                topTabHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("top_tabs", "installation", failure);
                moduleLog(6, TAG, "Home tab filter hook installation failed", failure);
            }
        }
        if (feedUiHideHook == null) {
            try {
                feedUiHideHook = FeedUiHideHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, extendedSettings, message -> moduleLog(3, TAG, message));
                feedUiHookFailure = null;
                moduleLog(3, TAG, "Feed UI visibility hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.hideInterface));
            } catch (Throwable failure) {
                feedUiHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("feed_ui", "installation", failure);
                moduleLog(6, TAG, "Feed UI visibility hook installation failed", failure);
            }
        }
        if (refreshConfirmHook == null) {
            try {
                refreshConfirmHook = RefreshConfirmHook.install(hookFramework(), hostScanReport,
                        hostClassLoader, extendedSettings, message -> moduleLog(3, TAG, message));
                refreshHookFailure = null;
                moduleLog(3, TAG, "Home refresh confirmation hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.confirmRefresh));
            } catch (Throwable failure) {
                refreshHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("refresh_confirm", "installation", failure);
                moduleLog(6, TAG, "Home refresh confirmation hook installation failed", failure);
            }
        }
        if (autoLikeHook == null) {
            try {
                autoLikeHook = AutoLikeHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        currentSettings, message -> moduleLog(3, TAG, message));
                autoLikeHookFailure = null;
                moduleLog(3, TAG, "Auto-like host action hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoLikeEnabled));
            } catch (Throwable failure) {
                autoLikeHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("auto_like", "installation", failure);
                moduleLog(6, TAG, "Auto-like host action hook installation failed", failure);
            }
        }
        if (autoFollowHook == null) {
            try {
                autoFollowHook = AutoFollowHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        currentSettings, message -> moduleLog(3, TAG, message));
                autoFollowHookFailure = null;
                moduleLog(3, TAG, "Auto-follow native action hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoFollowEnabled));
            } catch (Throwable failure) {
                autoFollowHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("auto_follow", "installation", failure);
                moduleLog(6, TAG, "Auto-follow native action hook installation failed", failure);
            }
        }
        if (autoPlayHook == null) {
            try {
                autoPlayHook = AutoPlayHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, extendedSettings, localOptions, message -> moduleLog(3, TAG, message));
                autoPlayHookFailure = null;
                moduleLog(3, TAG, "DouAi directional auto-slide hook installed; enabled="
                        + (extendedSettings != null && extendedSettings.autoPlay));
            } catch (Throwable failure) {
                autoPlayHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("auto_play", "installation", failure);
                moduleLog(6, TAG, "DouAi auto-slide hook installation failed", failure);
            }
        }
        if (autoCommentDraftHook == null) {
            try {
                autoCommentDraftHook = AutoCommentHook.install(hookFramework(), hostScanReport,
                        hostClassLoader, targetContext, localOptions, automationDispatcher,
                        message -> moduleLog(3, TAG, message));
                autoCommentDraftHookFailure = null;
                moduleLog(3, TAG, "Native automatic comment hook installed; enabled="
                        + localOptions.enabled("native_auto_comment"));
            } catch (Throwable failure) {
                autoCommentDraftHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("auto_comment", "installation", failure);
                moduleLog(6, TAG, "Native automatic comment hook installation failed", failure);
            }
        }
        if (commentReplyHook == null) {
            try {
                commentReplyHook = CommentReplyHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, automationDispatcher, message -> moduleLog(3, TAG, message));
                commentReplyHookFailure = null;
                Activity resumed = hostUiController == null ? null : hostUiController.getResumedActivity();
                if (resumed != null) {
                    CommentReplyHook installed = commentReplyHook;
                    new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                        if (hostUiController != null && hostUiController.getResumedActivity() == resumed) installed.onActivityResumed(resumed);
                    });
                }
            } catch (Throwable failure) {
                commentReplyHookFailure = HostAdaptationDiagnostics.installationFailure(failure);
                DouAiLog.failure("comment_reply", "installation", failure);
                moduleLog(6, TAG, "Comment reply hook installation failed: " + commentReplyHookFailure);
            }
        }
        if (autoMessageDraftHook == null) {
            try {
                autoMessageDraftHook = AutoMessageDraftHook.install(hookFramework(), hostScanReport,
                        hostClassLoader, currentSettings, extendedSettings,
                        message -> moduleLog(3, TAG, message));
                autoMessageDraftHookFailure = null;
                moduleLog(3, TAG, "Native single-chat draft hook installed; enabled="
                        + (currentSettings.automationEnabled && currentSettings.autoMessageEnabled));
            } catch (Throwable failure) {
                autoMessageDraftHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("auto_message", "installation", failure);
                moduleLog(6, TAG, "Native single-chat plain-text draft hook installation failed", failure);
            }
        }
        if (commentToolsHook == null) {
            try {
                commentToolsHook = CommentToolsHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        extendedSettings, message -> moduleLog(3, TAG, message));
                commentToolsHookFailure = null;
                moduleLog(3, TAG, "Comment long-press tools hook installed");
            } catch (Throwable failure) {
                commentToolsHookFailure = failure.getMessage() == null
                        ? failure.getClass().getSimpleName() : failure.getMessage();
                DouAiLog.failure("comment_tools", "installation", failure);
                moduleLog(6, TAG, "Comment long-press tools hook installation failed", failure);
            }
        }
        ensureImmersiveModeController(targetContext, extendedSettings);
        if (homeNavigationHideController == null && targetContext != null) {
            try {
                homeNavigationHideController = HomeNavigationHideController.install(targetContext,
                        extendedSettings, resolveHookClass("component_top_navigation"),
                        resolveHookClass("component_bottom_navigation"), message -> moduleLog(3, TAG, message));
                Activity activity = ui == null ? null : ui.getResumedActivity();
                if (activity != null) homeNavigationHideController.onActivityResumed(activity);
                FeedUiHideHook feedUi = feedUiHideHook;
                if (feedUi != null) {
                    HomeNavigationHideController navigation = homeNavigationHideController;
                    feedUi.setComponentVisibilityListener(navigation::setTemporarilyShown);
                }
            } catch (Throwable failure) {
                DouAiLog.failure("home_navigation", "installation", failure);
                moduleLog(6, TAG, "Home navigation visibility controller installation failed", failure);
            }
        }
        if (targetContext != null) installAdditionalHooks();
    }

    private void installAdditionalHooks() {
        if (downloadActionHook != null) downloadActionHook.setOptions(localOptions);
        // Startup gates can also be installed synchronously before Application.onCreate.
        synchronized (this) {
            if (hostBehaviorHooks == null) {
                try {
                    hostBehaviorHooks = HostBehaviorHooks.install(hookFramework(), hostScanReport, hostClassLoader,
                            targetContext, localOptions, message -> moduleLog(3, TAG, message));
                } catch (Throwable failure) {
                    DouAiLog.failure("host_behavior", "installation", failure);
                    moduleLog(6, TAG, "Host behavior hooks failed", failure);
                }
            }
        }
        if (playbackBehaviorHooks == null) {
            try {
                playbackBehaviorHooks = PlaybackBehaviorHooks.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> moduleLog(3, TAG, message));
            } catch (Throwable failure) {
                DouAiLog.failure("auto_speed", "installation", failure);
                moduleLog(6, TAG, "Playback behavior hooks failed", failure);
            }
        }
        if (profileSpoofHook == null) {
            try {
                profileSpoofHook = ProfileSpoofHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> moduleLog(3, TAG, message));
            } catch (Throwable failure) { moduleLog(6, TAG, "Profile display hook failed", failure); }
        }
        if (nativeMessagingHooks == null) {
            try {
                nativeMessagingHooks = NativeMessagingHooks.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> moduleLog(3, TAG, message), automationDispatcher);
            } catch (Throwable failure) {
                DouAiLog.failure("native_auto_reply", "installation", failure);
                moduleLog(6, TAG, "Native messaging hooks failed", failure);
            }
        }
        if (recallHighlightHook == null) {
            try {
                recallHighlightHook = RecallHighlightHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> moduleLog(3, TAG, message));
            } catch (Throwable failure) { moduleLog(6, TAG, "Recall background hook failed", failure); }
        }
        if (mediaModelHook == null) {
            try {
                mediaModelHook = MediaModelHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, message -> moduleLog(3, TAG, message));
            } catch (Throwable failure) { moduleLog(6, TAG, "Media presentation hooks failed", failure); }
        }
        if (commentEnhancementsHook == null) {
            try {
                commentEnhancementsHook = CommentEnhancementsHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, message -> moduleLog(3, TAG, message));
            } catch (Throwable failure) { moduleLog(6, TAG, "Comment publishing enhancement failed", failure); }
        }
        if (commentVoiceHook == null) {
            try {
                commentVoiceHook = CommentVoiceHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, message -> moduleLog(3, TAG, message));
                commentVoiceHook.setContext(targetContext);
            } catch (Throwable failure) { moduleLog(6, TAG, "Comment voice replacement failed", failure); }
        }
        if (nativeCommentSpeechHook == null) {
            NativeCommentSpeechHook candidate = null;
            try {
                candidate = NativeCommentSpeechHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, message -> moduleLog(3, TAG, message));
                candidate.setAutomaticPublishGuard(model -> autoCommentDraftHook != null && autoCommentDraftHook.ownsPublish(model)
                        || commentReplyHook != null && commentReplyHook.ownsPublish(model));
                candidate.setContext(targetContext);
                nativeCommentSpeechHook = candidate;
                nativeCommentSpeechHookFailure = null;
            } catch (Throwable failure) {
                if (candidate != null) candidate.close();
                nativeCommentSpeechHookFailure = HostAdaptationDiagnostics.installationFailure(failure);
                moduleLog(6, TAG, "Comment speech workflow failed: " + nativeCommentSpeechHookFailure);
            }
        }
        if (feedGestureToolsHook == null) {
            try {
                feedGestureToolsHook = FeedGestureToolsHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        targetContext, localOptions, message -> moduleLog(3, TAG, message));
                HostUiController ui = hostUiController;
                if (ui != null) feedGestureToolsHook.setModuleSettingsLauncher(ui::showSettingsFromGesture);
                Activity activity = ui == null ? null : ui.getResumedActivity();
                if (activity != null) feedGestureToolsHook.onActivityResumed(activity);
                AutoPlayHook pauseState = autoPlayHook;
                if (pauseState != null) feedGestureToolsHook.setPausedStateProvider(pauseState::pauseStateFor);
            } catch (Throwable failure) { moduleLog(6, TAG, "Feed gesture tools failed", failure); }
        }
        if (feedGestureToolsHook != null && feedUiHideHook != null) {
            feedGestureToolsHook.setComponentVisibility(feedUiHideHook::setComponentsTemporarilyShown,
                    feedUiHideHook::areComponentsTemporarilyShown);
        }
        if (directMessageSpeechHook == null) {
            DirectMessageSpeechHook candidate = null;
            try {
                candidate = DirectMessageSpeechHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, message -> moduleLog(3, TAG, message));
                candidate.setContext(targetContext);
                directMessageSpeechHook = candidate;
                directMessageSpeechHookFailure = null;
            } catch (Throwable failure) {
                if (candidate != null) candidate.close();
                directMessageSpeechHookFailure = HostAdaptationDiagnostics.installationFailure(failure);
                moduleLog(6, TAG, "Direct-message voice workflow failed: " + directMessageSpeechHookFailure);
            }
        }
        if (hashLongPressVoiceHook == null && directMessageSpeechHook != null && nativeCommentSpeechHook != null) {
            HashLongPressVoiceHook candidate = null;
            try {
                candidate = HashLongPressVoiceHook.install(hookFramework(), hostScanReport, hostClassLoader,
                        localOptions, directMessageSpeechHook, nativeCommentSpeechHook, message -> moduleLog(3, TAG, message));
                candidate.setContext(targetContext);
                hashLongPressVoiceHook = candidate;
                hashLongPressVoiceHookFailure = null;
            } catch (Throwable failure) {
                if (candidate != null) candidate.close();
                hashLongPressVoiceHookFailure = HostAdaptationDiagnostics.installationFailure(failure);
                DouAiLog.failure("hash_long_press_voice", "installation", failure);
                moduleLog(6, TAG, "Hash long-press voice workflow failed: " + hashLongPressVoiceHookFailure);
            }
        }
        if (!additionalInitialized) moduleLog(3, TAG, "Host features ready; installation failures are reported separately");
        additionalInitialized = true;
        publishFeatureLogState();
    }

    private void publishFeatureLogState() {
        try {
            Bundle snapshot = diagnosticsSnapshot();
            Map<String, Object> runtime = new java.util.HashMap<>();
            for (String key : snapshot.keySet()) runtime.put(key, snapshot.get(key));
            runtime.put("follow_lists_installed", nativeFollowLists != null);
            runtime.put("automation_dispatch_installed", automationDispatcher != null);
            runtime.put("automation_dispatch_enabled", localOptions.enabled("native_auto_reply")
                    || localOptions.enabled("native_auto_comment"));
            runtime.put("streak_active", localOptions.enabled("automation_enabled") && localOptions.enabled("streak_enabled"));
            runtime.put("diagnostics_installed", diagnosticsReceiver != null);
            runtime.put("local_analytics_enabled", targetContext != null && LocalAnalytics.get(targetContext).isEnabled());
            runtime.put("short_progress_installed", snapshot.getBoolean("short_progress_legacy_installed")
                    || snapshot.getBoolean("short_progress_pro_installed"));
            com.douai.module.config.ComponentHideSettings components = extendedSettings == null ? null : extendedSettings.components;
            boolean componentPolicyEnabled = components != null && components.configured && components.enabled;
            runtime.put("home_navigation_enabled", componentPolicyEnabled);
            runtime.put("short_progress_enabled", componentPolicyEnabled
                    && components.alpha(com.douai.module.config.ComponentHideSettings.Component.PROGRESS) > 0);
            runtime.put("dm_speech_enabled", localOptions.enabled("dm_tts") || localOptions.enabled("dm_voice_replace"));
            runtime.put("comment_enhancements_enabled", localOptions.enabled("comment_append_suffix") || localOptions.enabled("colored_comment"));
            runtime.put("comment_tools_enabled", extendedSettings != null && (extendedSettings.copyCommentText
                    || extendedSettings.commentMediaTools || extendedSettings.copySourceData));
            boolean gestures = localOptions.enabled("toolbar_enabled") || localOptions.interceptNativeLongPress();
            for (String prefix : new String[]{"long_press", "double_tap"}) {
                for (int region = 0; region < FeedGestureToolsHook.REGION_IDS.length; region++) {
                    gestures |= !"none".equals(FeedGestureToolsHook.regionAction(localOptions, prefix, region));
                }
            }
            runtime.put("gesture_enabled", gestures);
            FeatureLogCatalog.publish(lastConfigurationValues == null ? java.util.Collections.emptyMap()
                    : lastConfigurationValues, runtime, lastConfigurationValues != null);
        } catch (Throwable unavailable) { /* Observability cannot interrupt feature installation. */ }
    }

    private Bundle diagnosticsSnapshot() {
        Bundle snapshot = new Bundle(scanDiagnostics);
        snapshot.putString("module_version", BuildConfig.VERSION_NAME);
        snapshot.putBoolean("additional_initialized", additionalInitialized);
        snapshot.putBoolean("feature.local_analytics.installed", localAnalyticsHook != null);
        snapshot.putString("feature.local_analytics.failure", localAnalyticsHookFailure);
        for (String key : new String[]{"remove_splash_ads", "remove_update_dialog", "remove_teen_dialog",
                "hide_video_black_edges", "hide_camera_plus", "disable_double_tap_like",
                "prevent_skip_hidden", "prevent_insert_video", "block_long_press_speed"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    hostBehaviorHooks != null && hostBehaviorHooks.supports(key));
        }
        if (hostBehaviorHooks != null) {
            hostBehaviorHooks.appendHiddenSkipDiagnostics(snapshot);
            snapshot.putString("feature.prevent_skip_hidden.failure", snapshot.getString("hidden_skip_failure", ""));
        }
        for (String key : new String[]{"auto_speed"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    playbackBehaviorHooks != null && playbackBehaviorHooks.supports(key));
        }
        for (String key : new String[]{"pause_auto_on_comments", "smart_swipe"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    autoPlayHook != null && autoPlayHook.supports(key));
        }
        snapshot.putBoolean("feature.spoof_profile.installed", profileSpoofHook != null && profileSpoofHook.isActive());
        snapshot.putBoolean("feature.prevent_message_recall.installed", nativeMessagingHooks != null
                && nativeMessagingHooks.supports("prevent_message_recall"));
        snapshot.putBoolean("feature.native_auto_reply.installed", nativeMessagingHooks != null
                && nativeMessagingHooks.supports("native_reply"));
        snapshot.putString("feature.native_auto_reply.failure", nativeMessagingHooks == null ? ""
                : nativeMessagingHooks.installationFailure("native_reply"));
        snapshot.putBoolean("feature.media_model.installed", mediaModelHook != null);
        snapshot.putBoolean("feature.comment_enhancements.installed", commentEnhancementsHook != null);
        snapshot.putBoolean("feature.comment_voice.installed", commentVoiceHook != null);
        snapshot.putBoolean("feature.comment_tts.installed", nativeCommentSpeechHook != null);
        snapshot.putString("feature.comment_tts.failure", nativeCommentSpeechHookFailure);
        snapshot.putBoolean("feature.recall_background.installed", recallHighlightHook != null && recallHighlightHook.isActive());
        snapshot.putBoolean("feature.dm_speech.installed", directMessageSpeechHook != null);
        snapshot.putString("feature.dm_speech.failure", directMessageSpeechHookFailure);
        snapshot.putBoolean("feature.hash_long_press_voice.installed", hashLongPressVoiceHook != null);
        snapshot.putString("feature.hash_long_press_voice.failure", hashLongPressVoiceHookFailure);
        for (String key : new String[]{"toolbar_enabled", "long_press_action", "double_tap_action"}) {
            snapshot.putBoolean("feature." + key + ".installed",
                    feedGestureToolsHook != null && feedGestureToolsHook.supports(key));
        }
        FeedAdFilterHook hook = feedAdFilterHook;
        FeatureSettings settings = currentSettings;
        snapshot.putBoolean("feed_hook_installed", hook != null);
        snapshot.putBoolean("feed_filter_enabled", hasActiveFeedFilters(settings));
        snapshot.putInt("feed_filtered_pages", hook == null ? 0 : hook.getFilteredPages());
        if (feedHookFailure != null) snapshot.putString("feed_hook_failure", feedHookFailure);
        snapshot.putBoolean("download_hook_installed", downloadActionHook != null);
        snapshot.putBoolean("download_enabled", settings.downloadEnabled);
        if (downloadHookFailure != null) snapshot.putString("download_hook_failure", downloadHookFailure);
        snapshot.putBoolean("version_hook_installed", versionInfoHook != null);
        snapshot.putBoolean("version_spoof_enabled", extendedSettings != null && extendedSettings.spoofVersion);
        if (versionHookFailure != null) snapshot.putString("version_hook_failure", versionHookFailure);
        snapshot.putBoolean("top_tab_hook_installed", topTabFilterHook != null);
        snapshot.putBoolean("top_tab_filter_enabled", extendedSettings != null && extendedSettings.hideTopTabs);
        if (topTabHookFailure != null) snapshot.putString("top_tab_hook_failure", topTabHookFailure);
        snapshot.putBoolean("feed_ui_hook_installed", feedUiHideHook != null);
        snapshot.putBoolean("feed_ui_video_hook_installed", feedUiHideHook != null
                && feedUiHideHook.isVideoLifecycleInstalled());
        snapshot.putBoolean("feed_ui_image_hook_installed", feedUiHideHook != null
                && feedUiHideHook.isImageLifecycleInstalled());
        snapshot.putBoolean("feed_ui_hide_enabled", extendedSettings != null && extendedSettings.hideInterface);
        snapshot.putInt("feed_ui_alpha_percent",
                extendedSettings == null ? 100 : extendedSettings.videoRightAlpha);
        snapshot.putBoolean("feed_ui_active", extendedSettings != null
                && (extendedSettings.components.configured ? extendedSettings.components.enabled
                : extendedSettings.hideInterface || extendedSettings.videoRightAlpha < 100));
        snapshot.putBoolean("component_hide_configured", extendedSettings != null
                && extendedSettings.components.configured);
        snapshot.putBoolean("component_hide_enabled", extendedSettings != null
                && extendedSettings.components.enabled);
        snapshot.putBoolean("component_hide_temporarily_shown", feedUiHideHook != null
                && feedUiHideHook.areComponentsTemporarilyShown());
        snapshot.putBoolean("home_navigation_hook_installed", homeNavigationHideController != null);
        FeedUiHideHook feedUiHook = feedUiHideHook;
        snapshot.putInt("feed_ui_selected_callbacks",
                feedUiHook == null ? 0 : feedUiHook.getSelectedCallbacks());
        snapshot.putInt("feed_ui_card_scans", feedUiHook == null ? 0 : feedUiHook.getCardScans());
        snapshot.putInt("feed_ui_predraw_rechecks",
                feedUiHook == null ? 0 : feedUiHook.getPreDrawRechecks());
        snapshot.putInt("feed_ui_view_searches",
                feedUiHook == null ? 0 : feedUiHook.getViewSearches());
        snapshot.putInt("feed_ui_resource_mismatches",
                feedUiHook == null ? 0 : feedUiHook.getResourceMismatchCount());
        snapshot.putInt("feed_ui_reapplied_views",
                feedUiHook == null ? 0 : feedUiHook.getHostStateResets());
        snapshot.putInt("feed_ui_hidden_views", feedUiHook == null ? 0 : feedUiHook.getHiddenViews());
        if (feedUiHookFailure != null) snapshot.putString("feed_ui_hook_failure", feedUiHookFailure);
        snapshot.putBoolean("refresh_hook_installed", refreshConfirmHook != null);
        snapshot.putBoolean("refresh_confirm_enabled", extendedSettings != null && extendedSettings.confirmRefresh);
        if (refreshHookFailure != null) snapshot.putString("refresh_hook_failure", refreshHookFailure);
        snapshot.putBoolean("auto_like_hook_installed", autoLikeHook != null);
        snapshot.putBoolean("auto_like_enabled", settings.automationEnabled && settings.autoLikeEnabled);
        if (autoLikeHookFailure != null) snapshot.putString("auto_like_hook_failure", autoLikeHookFailure);
        snapshot.putBoolean("auto_follow_hook_installed", autoFollowHook != null);
        snapshot.putBoolean("auto_follow_enabled", settings.automationEnabled && settings.autoFollowEnabled);
        if (autoFollowHookFailure != null) snapshot.putString("auto_follow_hook_failure", autoFollowHookFailure);
        snapshot.putBoolean("auto_play_hook_installed", autoPlayHook != null && autoPlayHook.supports("auto_play"));
        if (autoPlayHook != null) snapshot.putString("auto_play_status", autoPlayHook.diagnostic());
        snapshot.putBoolean("auto_play_enabled", extendedSettings != null && extendedSettings.autoPlay);
        if (autoPlayHookFailure != null) snapshot.putString("auto_play_hook_failure", autoPlayHookFailure);
        snapshot.putBoolean("auto_comment_hook_installed", autoCommentDraftHook != null);
        snapshot.putBoolean("auto_comment_enabled",
                localOptions.enabled("native_auto_comment"));
        snapshot.putBoolean("auto_reply_enabled", localOptions.enabled("native_auto_reply"));
        snapshot.putBoolean("comment_reply_hook_installed", commentReplyHook != null);
        snapshot.putBoolean("comment_reply_enabled", localOptions.enabled(LocalOptions.COMMENT_REPLY_ENABLED)
                && localOptions.enabled("native_auto_reply"));
        if (commentReplyHook != null) snapshot.putString("comment_reply_status", commentReplyHook.diagnostic());
        if (commentReplyHookFailure != null) snapshot.putString("comment_reply_hook_failure", commentReplyHookFailure);
        snapshot.putBoolean("short_progress_legacy_installed", feedUiHideHook != null
                && feedUiHideHook.isShortProgressLegacyInstalled());
        snapshot.putBoolean("short_progress_pro_installed", feedUiHideHook != null
                && feedUiHideHook.isShortProgressProInstalled());
        snapshot.putInt("unknown_streak_count",
                nativeMessagingHooks == null ? 0 : nativeMessagingHooks.unknownStreakCount());
        if (autoCommentDraftHookFailure != null) {
            snapshot.putString("auto_comment_hook_failure", autoCommentDraftHookFailure);
        }
        snapshot.putBoolean("auto_message_hook_installed", autoMessageDraftHook != null);
        snapshot.putBoolean("auto_message_enabled",
                settings.automationEnabled && settings.autoMessageEnabled);
        if (autoMessageDraftHookFailure != null) {
            snapshot.putString("auto_message_hook_failure", autoMessageDraftHookFailure);
        }
        snapshot.putBoolean("comment_tools_hook_installed", commentToolsHook != null);
        if (commentToolsHookFailure != null) snapshot.putString("comment_tools_hook_failure", commentToolsHookFailure);
        snapshot.putBoolean("immersive_hook_installed", immersiveModeController != null);
        snapshot.putBoolean("immersive_status_bar_enabled",
                extendedSettings != null && extendedSettings.removeBlackEdges);
        snapshot.putBoolean("immersive_navigation_bar_enabled",
                extendedSettings != null && extendedSettings.hideNavigationBar);
        snapshot.putBoolean("immersive_enabled", extendedSettings != null
                && (extendedSettings.removeBlackEdges || extendedSettings.hideNavigationBar));
        if (immersiveModeFailure != null) snapshot.putString("immersive_hook_failure", immersiveModeFailure);
        HostAdaptationDiagnostics.append(snapshot, hostVersionEvidence, hostRulesFingerprint, hostScanReport);
        return snapshot;
    }

    private java.lang.reflect.Method resolveHookMethod(String ruleId) throws Exception {
        com.douai.module.scan.RuleScanResult result = hostScanReport.getResults().get(ruleId);
        if (result == null || result.getStatus() != com.douai.module.scan.RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        com.douai.module.scan.HostCandidate candidate = result.getCandidates().get(0);
        Class<?> owner = Class.forName(descriptorClassName(candidate.getClassDescriptor()),
                false, hostClassLoader);
        java.lang.reflect.Method match = null;
        for (java.lang.reflect.Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(candidate.getMethodName())
                    || !methodDescriptor(method).equals(candidate.getMethodDescriptor())) continue;
            if (match != null) throw new IllegalStateException("Reflection result is ambiguous: " + ruleId);
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
        return match;
    }

    private Class<?> resolveHookClass(String ruleId) throws ClassNotFoundException {
        com.douai.module.scan.RuleScanResult result = hostScanReport.getResults().get(ruleId);
        if (result == null || result.getStatus() != com.douai.module.scan.RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host class rule is not unique: " + ruleId);
        }
        com.douai.module.scan.HostCandidate candidate = result.getCandidates().get(0);
        return Class.forName(descriptorClassName(candidate.getClassDescriptor()), false, hostClassLoader);
    }

    private static String descriptorClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L'
                || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(java.lang.reflect.Method method) {
        StringBuilder result = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) result.append(typeDescriptor(parameter));
        return result.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }

    private boolean hasActiveFeedFilters(FeatureSettings settings) {
        return settings != null && settings.feedFilterEnabled
                && (settings.hideAds || settings.hideLives || settings.hideImagePosts
                || settings.hideRecommendationCards || settings.hideRecommendationMerchants
                || settings.keywordFilterEnabled
                || settings.minimumDurationEnabled || settings.minimumLikesEnabled
                || localOptions.enabled("hide_product_cards")
                || localOptions.enabled("hide_hotspot_rank_cards")
                || localOptions.enabled("hide_mini_games") || localOptions.enabled("hide_long_videos"));
    }

    private XposedInterface hookFramework() {
        XposedInterface current = tracedFramework;
        if (current != null) return current;
        synchronized (this) {
            if (tracedFramework == null) tracedFramework = DebugHookTrace.wrap(this,
                    DebugLog::isEnabled);
            return tracedFramework;
        }
    }

    private void moduleLog(int priority, String tag, String message) {
        DouAiLog.legacy(priority, tag, message, null);
    }

    private void moduleLog(int priority, String tag, String message, Throwable failure) {
        DouAiLog.legacy(priority, tag, message, failure);
    }

    private void logDebugConfiguration(Map<String, ?> values, ConfigurationChanges changes) {
        try {
            if (lastConfigurationValues != null) for (String key : new java.util.TreeSet<>(lastConfigurationValues.keySet())) {
                if (!values.containsKey(key)) DouAiLog.event("configuration", "config_removed", "key_removed",
                        "key=" + key + ";effective_value=owning_feature_default");
            }
            for (String key : new java.util.TreeSet<>(values.keySet())) {
                if (lastConfigurationValues != null && !changes.contains(key)) continue;
                Object value = values.get(key);
                com.douai.module.config.ConfigurationCatalog.Setting definition =
                        com.douai.module.config.ConfigurationCatalog.settings().get(key);
                DouAiLog.event("configuration", "config_value", "snapshot_applied",
                        "key=" + key + ";type=" + (value == null ? "null" : value.getClass().getSimpleName())
                                + ";value=" + (value instanceof Boolean ? value.toString()
                                : DouAiLog.reference(String.valueOf(value))) + ";consumers="
                                + (definition == null ? "unregistered" : definition.consumers.toString()));
            }
        } catch (Throwable unavailable) { }
    }

    private static List<File> getApkArchives(ApplicationInfo appInfo) {
        ArrayList<File> files = new ArrayList<>();
        if (appInfo.sourceDir != null) {
            files.add(new File(appInfo.sourceDir));
        }
        if (appInfo.splitSourceDirs != null) {
            for (String path : appInfo.splitSourceDirs) {
                if (path != null) {
                    files.add(new File(path));
                }
            }
        }
        return files;
    }

}
