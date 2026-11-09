package com.douai.module.ui;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.douai.module.config.HostSettingsStore;
import com.douai.module.config.SidebarSettings;
import com.douai.module.feature.AiMessageEntryHook;

import java.lang.ref.WeakReference;
import java.util.function.Consumer;

/** Injects the module entry into host settings and displays first-scan progress. */
public final class HostUiController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final int TEXT = Color.rgb(32, 40, 44);
    private static final int MUTED = Color.rgb(99, 110, 116);
    private static final int ACCENT = Color.rgb(0, 151, 136);
    private final Application application;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final HostSettingsStore settingsStore;
    private final AiMessageEntryHook aiMessageEntry;
    private final AdaptationAcknowledgment acknowledgment;
    private final HostAppRestarter appRestarter;
    private final Consumer<String> log;
    private volatile ScanState scanState = ScanState.idle();
    private WeakReference<Activity> resumedActivity = new WeakReference<>(null);
    private Dialog scanDialog;
    private Dialog settingsDialog;
    private ModuleSettingsScreen settingsScreen;
    private TextView scanTitle;
    private TextView scanDetail;
    private ProgressBar scanProgress;
    private TextView scanCount;
    private TextView scanAction;
    private final java.util.Map<Activity, WeakReference<View>> nativeSettingsEntries = new java.util.WeakHashMap<>();
    private final java.util.Map<Activity, SettingsLayoutMonitor> settingsLayoutMonitors = new java.util.WeakHashMap<>();
    private final java.util.Set<Activity> unavailableSettings = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Class<?> settingsActivityClass;
    private Class<?> settingsScrollClass;
    private boolean scanDismissed;
    private boolean restartInProgress;
    private String restartFailure;
    private String pendingRestartNotice;
    private boolean closed;

    public HostUiController(Application application, Consumer<String> log) {
        this(application, log, new HostAppRestarter(),
                new AdaptationAcknowledgment(application));
    }

    HostUiController(Application application, Consumer<String> log,
                     HostAppRestarter appRestarter,
                     AdaptationAcknowledgment acknowledgment) {
        if (application == null) throw new IllegalArgumentException("Application is required");
        if (appRestarter == null || acknowledgment == null) {
            throw new IllegalArgumentException("Restart and acknowledgment services are required");
        }
        this.application = application;
        this.log = log == null ? ignored -> { } : log;
        this.appRestarter = appRestarter;
        this.acknowledgment = acknowledgment;
        settingsStore = new HostSettingsStore(application);
        aiMessageEntry = AiMessageEntryHook.install(application, settingsStore,
                (activity, config) -> AiConversationDialog.show(activity, settingsStore, config), this.log);
        application.registerActivityLifecycleCallbacks(this);
    }

    public void beginScan() {
        scanDismissed = false;
        restartInProgress = false;
        restartFailure = null;
        pendingRestartNotice = null;
        update(ScanState.scanning("检查抖音版本和扫描缓存", 0, 0, false));
    }

    public void onCacheHit(int dexCount) {
        update(ScanState.scanning("读取适配缓存", dexCount, dexCount, true));
    }

    public void onScanStarted(int dexCount) {
        scanDismissed = false;
        restartInProgress = false;
        restartFailure = null;
        pendingRestartNotice = null;
        update(ScanState.scanning("查找抖音方法", 0, dexCount, false));
    }

    public void onDexScanned(int completed, int total, String dexName) {
        update(ScanState.scanning("已处理 " + dexName, completed, total, false));
    }

    public void complete(boolean cacheHit, String summary) {
        update(ScanState.completed(summary, cacheHit, "legacy:" + summary, !cacheHit));
    }

    public void complete(boolean cacheHit, String summary, String identity) {
        boolean needsAcknowledgment = !acknowledgment.isAcknowledged(identity);
        update(ScanState.completed(summary, cacheHit, identity, needsAcknowledgment));
    }

    public void fail(String reason) {
        update(ScanState.failed(reason));
    }

    public void setSettingsPageTargets(Class<?> activityClass, Class<?> scrollClass) {
        mainHandler.post(() -> {
            if (closed) return;
            settingsActivityClass = activityClass;
            settingsScrollClass = scrollClass;
            Activity activity = resumedActivity.get();
            if (activity != null) monitorSettingsPage(activity);
        });
    }

    public Activity getResumedActivity() {
        return resumedActivity.get();
    }

    private void attachNativeSettingsEntry(Activity activity) {
        if (closed || !isSettingsPage(activity)) return;
        WeakReference<View> reference = nativeSettingsEntries.get(activity);
        View existing = reference == null ? null : reference.get();
        nativeSettingsEntries.remove(activity);
        try {
            View entry = HostSettingsEntry.attach(activity, settingsScrollClass,
                    () -> showSettings(activity));
            if (existing != null && existing != entry && existing.getParent() instanceof ViewGroup) {
                ((ViewGroup) existing.getParent()).removeView(existing);
            }
            if (entry != null) {
                nativeSettingsEntries.put(activity, new WeakReference<>(entry));
                unavailableSettings.remove(activity);
                if (entry != existing) safeLog("Injected module settings entry into host settings list");
            } else if (unavailableSettings.add(activity)) {
                safeLog("Host settings container is unavailable or ambiguous; waiting for a layout update");
            }
        } catch (RuntimeException failure) {
            if (unavailableSettings.add(activity)) {
                safeLog("Could not insert module settings entry: " + failure.getMessage());
            }
        }
    }

    private boolean isSettingsPage(Activity activity) {
        return activity != null && settingsActivityClass != null && settingsScrollClass != null
                && settingsActivityClass.isInstance(activity);
    }

    private void monitorSettingsPage(Activity activity) {
        if (closed || !isSettingsPage(activity)) return;
        SettingsLayoutMonitor monitor = settingsLayoutMonitors.get(activity);
        if (monitor == null) {
            monitor = new SettingsLayoutMonitor(activity);
            settingsLayoutMonitors.put(activity, monitor);
        }
        monitor.attach();
        attachNativeSettingsEntry(activity);
    }

    private void stopSettingsMonitor(Activity activity) {
        SettingsLayoutMonitor monitor = settingsLayoutMonitors.remove(activity);
        if (monitor != null) monitor.remove();
    }

    private final class SettingsLayoutMonitor implements ViewTreeObserver.OnGlobalLayoutListener {
        private final WeakReference<Activity> owner;
        private ViewTreeObserver observer;

        SettingsLayoutMonitor(Activity activity) { owner = new WeakReference<>(activity); }

        void attach() {
            Activity activity = owner.get();
            if (activity == null || activity.getWindow() == null) return;
            ViewTreeObserver next = activity.getWindow().getDecorView().getViewTreeObserver();
            if (next == observer || !next.isAlive()) return;
            remove();
            observer = next;
            observer.addOnGlobalLayoutListener(this);
        }

        @Override public void onGlobalLayout() {
            Activity activity = owner.get();
            if (closed || activity == null || resumedActivity.get() != activity
                    || activity.isFinishing() || activity.isDestroyed()) return;
            attachNativeSettingsEntry(activity);
        }

        void remove() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(this);
            observer = null;
        }
    }

    private void update(ScanState next) {
        scanState = next;
        mainHandler.post(this::renderScanDialog);
    }

    private void showSettings(Activity activity) {
        showSettings(activity, true);
    }

    /** Opens the existing module settings surface from a gesture action on the active feed. */
    public void showSettingsFromGesture(Activity activity) {
        mainHandler.post(() -> showSettings(activity, false));
    }

    /** Uses the same owned settings branch for every allowlisted sidebar destination. */
    public void showSidebarEntry(Activity activity, String id) {
        if (SidebarSettings.find(id) == null) return;
        mainHandler.post(() -> showSettings(activity, false, id));
    }

    private void showSettings(Activity activity, boolean settingsPageOnly) {
        showSettings(activity, settingsPageOnly, null);
    }

    private void showSettings(Activity activity, boolean settingsPageOnly, String sidebarEntry) {
        if (closed || activity == null || settingsPageOnly && !isSettingsPage(activity) || resumedActivity.get() != activity
                || activity.isFinishing() || activity.isDestroyed()) return;
        if (settingsDialog != null && settingsDialog.isShowing()) {
            if (sidebarEntry != null && settingsDialog.getOwnerActivity() == activity && settingsScreen != null) {
                openSidebarEntry(activity, settingsScreen, sidebarEntry);
            }
            return;
        }
        Dialog dialog = new Dialog(activity, ModuleTheme.dialogTheme(activity));
        dialog.setOwnerActivity(activity);
        dialog.setCanceledOnTouchOutside(true);
        ModuleSettingsScreen screen = createSettingsScreen(activity, dialog::dismiss);
        dialog.setOnDismissListener(ignored -> {
            screen.close();
            if (settingsDialog == dialog) { settingsDialog = null; settingsScreen = null; }
        });
        try {
            dialog.setContentView(screen.createMiuixView());
        } catch (RuntimeException | LinkageError failure) {
            safeLog("Could not initialize Miuix settings: " + failure.getMessage());
            dialog.setContentView(screen.createView());
        }
        ScanState state = scanState;
        screen.setScanStatus(new ScanStatus(state.failed ? "方法查找失败"
                : state.complete ? "方法查找完成" : "正在查找方法",
                state.detail, state.complete && !state.failed, state.restartRequired));
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.38f;
            window.setAttributes(attributes);
        }
        settingsDialog = dialog;
        settingsScreen = screen;
        try {
            dialog.show();
            screen.bindMiuixBackNavigation(dialog);
            HostDialogBounds.install(activity, dialog, false);
            if (sidebarEntry != null) openSidebarEntry(activity, screen, sidebarEntry);
        } catch (RuntimeException failure) {
            screen.close();
            settingsDialog = null;
            settingsScreen = null;
            safeLog("Could not show in-host settings page: " + failure.getMessage());
        }
    }

    ModuleSettingsScreen createSettingsScreen(Activity activity, Runnable onClose) {
        return new ModuleSettingsScreen(activity, settingsStore, onClose, this::safeLog);
    }

    private void openSidebarEntry(Activity activity, ModuleSettingsScreen screen, String id) {
        try { screen.openSidebarEntry(id); }
        catch (RuntimeException | LinkageError unavailable) {
            safeLog("Could not open sidebar destination: " + unavailable.getClass().getSimpleName());
            Toast.makeText(activity, "入口暂不可用，请从模块设置中查看", Toast.LENGTH_SHORT).show();
        }
    }

    private void renderScanDialog() {
        if (closed) return;
        Activity activity = resumedActivity.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        boolean pendingConfirmation = scanState.complete && !scanState.failed
                && scanState.restartRequired;
        boolean quietCacheCheck = scanState.cacheHit && !pendingConfirmation;
        if (scanDismissed || quietCacheCheck || (!scanState.complete && scanState.detail.isEmpty())
                || (scanState.complete && !scanState.failed && !pendingConfirmation)) {
            dismissScanDialog();
            return;
        }
        if (scanDialog == null) createScanDialog(activity);
        if (scanDialog == null || scanDialog.getOwnerActivity() != activity) {
            dismissScanDialog();
            createScanDialog(activity);
        }
        if (scanDialog == null) return;

        scanTitle.setText(scanState.complete
                ? (scanState.failed ? "方法查找失败" : "方法查找完成")
                : "正在查找方法");
        String detail = scanState.detail;
        if (restartFailure != null) detail += "\n" + restartFailure;
        if (scanState.restartRequired) detail += "\n点击确定后将自动重启抖音。";
        scanDetail.setText(detail);
        scanProgress.setVisibility(scanState.complete ? View.GONE : View.VISIBLE);
        boolean determinate = scanState.total > 0 && !scanState.failed;
        scanProgress.setIndeterminate(!determinate);
        if (determinate) {
            scanProgress.setMax(scanState.total);
            scanProgress.setProgress(Math.min(scanState.completed, scanState.total));
            scanCount.setText(scanState.completed + " / " + scanState.total + " 个 DEX");
        } else {
            scanCount.setText(scanState.complete ? "" : "准备中");
        }
        scanAction.setVisibility(scanState.complete ? View.VISIBLE : View.GONE);
        scanAction.setText(scanState.failed ? "关闭" : "确定");
        scanDialog.setCancelable(scanState.complete);
        if (!scanDialog.isShowing()) {
            scanDialog.show();
            Window window = scanDialog.getWindow();
            if (window != null) window.setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.84f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
    }

    private void createScanDialog(Activity activity) {
        Dialog dialog = new Dialog(activity, ModuleTheme.alertTheme(activity));
        dialog.setOwnerActivity(activity);
        dialog.setCancelable(false);
        dialog.setCanceledOnTouchOutside(false);

        LinearLayout panel = new LinearLayout(ModuleTheme.themedContext(activity));
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 18));
        panel.setBackground(rounded(Color.WHITE, Color.rgb(232, 237, 237), dp(activity, 16)));

        scanTitle = text(activity, "正在查找方法", 22, TEXT, true);
        scanDetail = text(activity, "检查抖音版本和扫描缓存", 15, MUTED, false);
        scanDetail.setMinHeight(dp(activity, 48));
        scanDetail.setGravity(Gravity.CENTER_VERTICAL);
        scanProgress = new ProgressBar(ModuleTheme.themedContext(activity), null,
                android.R.attr.progressBarStyleHorizontal);
        scanCount = text(activity, "准备中", 13, MUTED, false);
        scanCount.setGravity(Gravity.END);
        scanAction = text(activity, "确定", 14, ACCENT, true);
        scanAction.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        scanAction.setPadding(dp(activity, 12), dp(activity, 12), 0, dp(activity, 4));
        scanAction.setClickable(true);
        scanAction.setOnClickListener(view -> {
            if (scanDialog != dialog || !dialog.isShowing()) return;
            if (scanState.failed) {
                scanDismissed = true;
                dismissScanDialog();
            } else {
                confirmAdaptation(activity);
            }
        });
        dialog.setOnCancelListener(ignored -> {
            if (scanDialog != dialog) return;
            if (scanState.complete && !scanState.failed && scanState.restartRequired) {
                boolean saved;
                try { saved = acknowledgment.acknowledge(scanState.identity); }
                catch (RuntimeException failure) { saved = false; }
                if (!saved) {
                    restartFailure = "无法保存适配确认，请重试。";
                    scanDismissed = false;
                    mainHandler.post(this::renderScanDialog);
                    return;
                }
                markScanConfirmed();
                safeLog("Scan completion dismissed; confirmation saved without restart");
            }
            scanDismissed = true;
        });

        panel.addView(scanTitle, matchWrap());
        LinearLayout.LayoutParams detailParams = matchWrap();
        detailParams.topMargin = dp(activity, 10);
        panel.addView(scanDetail, detailParams);
        LinearLayout.LayoutParams progressParams = matchWrap();
        progressParams.topMargin = dp(activity, 14);
        panel.addView(scanProgress, progressParams);
        LinearLayout.LayoutParams countParams = matchWrap();
        countParams.topMargin = dp(activity, 4);
        panel.addView(scanCount, countParams);
        LinearLayout.LayoutParams actionParams = matchWrap();
        actionParams.topMargin = dp(activity, 8);
        panel.addView(scanAction, actionParams);

        ModuleTheme.bind(panel);
        dialog.setContentView(panel);
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawableResource(android.R.color.transparent);
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            WindowManager.LayoutParams attributes = window.getAttributes();
            attributes.dimAmount = 0.55f;
            window.setAttributes(attributes);
            window.setLayout((int) (activity.getResources().getDisplayMetrics().widthPixels * 0.84f),
                    WindowManager.LayoutParams.WRAP_CONTENT);
        }
        scanDialog = dialog;
    }

    private void confirmAdaptation(Activity activity) {
        if (restartInProgress || scanState.failed || !scanState.complete
                || !scanState.restartRequired || activity == null
                || activity != resumedActivity.get() || activity.isFinishing()
                || activity.isDestroyed()) return;
        restartInProgress = true;
        String identity = scanState.identity;
        if (scanAction != null) scanAction.setEnabled(false);
        safeLog("Adaptation confirmation requested; scheduling host restart");
        HostAppRestarter.Result result = appRestarter.restart(activity, identity,
                new HostAppRestarter.AcknowledgmentWriter() {
                    @Override public boolean commit(String identity) {
                        return acknowledgment.acknowledge(identity);
                    }
                }, message -> {
                    if (closed || !identity.equals(scanState.identity)) return;
                    restartInProgress = false;
                    markScanConfirmed();
                    scanDismissed = true;
                    dismissScanDialog();
                    notifyManualRestart();
                    safeLog("Restart relay failed: " + message);
                });
        if (result.isConfirmationSaved()) {
            markScanConfirmed();
            scanDismissed = true;
            dismissScanDialog();
            if (!result.isSuccessful()) {
                restartInProgress = false;
                notifyManualRestart();
                safeLog("Could not restart host after adaptation confirmation: " + result.getMessage());
            }
            return;
        }
        restartInProgress = false;
        if (scanAction != null) scanAction.setEnabled(true);
        restartFailure = result.getMessage();
        renderScanDialog();
        safeLog("Could not save adaptation confirmation: " + result.getMessage());
    }

    private void markScanConfirmed() {
        ScanState current = scanState;
        scanState = ScanState.completed(current.detail, current.cacheHit, current.identity, false);
    }

    private void notifyManualRestart() {
        String message = "自动重启失败，查找结果已保存。请手动重启抖音。";
        Activity current = resumedActivity.get();
        if (current == null || current.isFinishing() || current.isDestroyed()) {
            pendingRestartNotice = message;
        } else {
            pendingRestartNotice = null;
            Toast.makeText(current, message, Toast.LENGTH_LONG).show();
        }
    }

    private void dismissScanDialog() {
        Dialog current = scanDialog;
        scanDialog = null;
        scanTitle = null;
        scanDetail = null;
        scanProgress = null;
        scanCount = null;
        scanAction = null;
        if (current != null && current.isShowing()) current.dismiss();
    }

    private void dismissSettingsDialog() {
        Dialog current = settingsDialog;
        settingsDialog = null;
        settingsScreen = null;
        if (current != null && current.isShowing()) current.dismiss();
    }

    private int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private GradientDrawable rounded(int fill, int stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(radius);
        drawable.setStroke(dp(application, 1), stroke);
        return drawable;
    }

    private TextView text(Context context, String value, float size, int color, boolean bold) {
        TextView view = new TextView(ModuleTheme.themedContext(context));
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return view;
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // UI failures must not affect host application lifecycle callbacks.
        }
    }

    @Override
    public void onActivityResumed(Activity activity) {
        if (closed || activity == null || !application.getPackageName().equals(activity.getPackageName())) return;
        resumedActivity = new WeakReference<>(activity);
        aiMessageEntry.onActivityResumed(activity);
        if (pendingRestartNotice != null) {
            Toast.makeText(activity, pendingRestartNotice, Toast.LENGTH_LONG).show();
            pendingRestartNotice = null;
        }
        monitorSettingsPage(activity);
        renderScanDialog();
    }

    @Override
    public void onActivityPaused(Activity activity) {
        stopSettingsMonitor(activity);
        if (resumedActivity.get() != activity) return;
        resumedActivity.clear();
        dismissScanDialog();
        if (settingsDialog != null && settingsDialog.getOwnerActivity() == activity
                && !HostImagePicker.isPending(activity) && !HostMediaPicker.isPending(activity)
                && !HostDownloadDirectoryPicker.isPending(activity)
                && !HostConfigFilePicker.isPending(activity)) {
            dismissSettingsDialog();
        }
    }

    @Override
    public void onActivityDestroyed(Activity activity) {
        stopSettingsMonitor(activity);
        nativeSettingsEntries.remove(activity);
        unavailableSettings.remove(activity);
        if (resumedActivity.get() == activity) resumedActivity.clear();
        if (scanDialog != null && scanDialog.getOwnerActivity() == activity) dismissScanDialog();
        if (settingsDialog != null && settingsDialog.getOwnerActivity() == activity) dismissSettingsDialog();
    }

    @Override
    public void close() {
        closed = true;
        aiMessageEntry.close();
        application.unregisterActivityLifecycleCallbacks(this);
        mainHandler.removeCallbacksAndMessages(null);
        for (SettingsLayoutMonitor monitor : settingsLayoutMonitors.values()) monitor.remove();
        settingsLayoutMonitors.clear();
        unavailableSettings.clear();
        dismissScanDialog();
        dismissSettingsDialog();
        for (WeakReference<View> reference : nativeSettingsEntries.values()) {
            View entry = reference.get();
            if (entry != null && entry.getParent() instanceof ViewGroup) {
                ((ViewGroup) entry.getParent()).removeView(entry);
            }
        }
        nativeSettingsEntries.clear();
        resumedActivity.clear();
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    private static final class ScanState {
        final String detail;
        final int completed;
        final int total;
        final boolean complete;
        final boolean cacheHit;
        final boolean failed;
        final boolean restartRequired;

        private ScanState(String detail, int completed, int total, boolean complete,
                          boolean cacheHit, boolean failed, boolean restartRequired) {
            this(detail, completed, total, complete, cacheHit, failed, restartRequired, null);
        }

        static ScanState idle() { return new ScanState("", 0, 0, false, false, false, false); }
        static ScanState scanning(String detail, int done, int total, boolean cacheHit) {
            return new ScanState(detail, done, total, false, cacheHit, false, false);
        }
        static ScanState failed(String detail) {
            return new ScanState(detail == null || detail.isEmpty() ? "未知错误" : detail,
                    0, 0, true, false, true, false);
        }

        static ScanState completed(String detail, boolean cacheHit, String identity,
                                  boolean restartRequired) {
            return new ScanState(detail == null ? "" : detail, 0, 0, true,
                    cacheHit, false, restartRequired, identity);
        }

        final String identity;

        private ScanState(String detail, int completed, int total, boolean complete,
                          boolean cacheHit, boolean failed, boolean restartRequired,
                          String identity) {
            this.detail = detail;
            this.completed = completed;
            this.total = total;
            this.complete = complete;
            this.cacheHit = cacheHit;
            this.failed = failed;
            this.restartRequired = restartRequired;
            this.identity = identity;
        }

    }
}
