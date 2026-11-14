package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.view.View;
import android.annotation.SuppressLint;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.CustomViewLocator;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.EnumMap;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Applies feed overlay visibility and right-menu opacity without touching the media root. */
public final class FeedUiHideHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final int MAX_TREE_NODES = 4096;
    private static final int MAX_TREE_DEPTH = 64;
    // Layout changes request enough bounded drawing callbacks to finish one captured-tree sweep.
    private static final int MAX_STRUCTURE_CHECKS_PER_FRAME = 256;
    // Keep tiny trees responsive while a stable large tree completes a rolling sweep in 16 draws.
    private static final int MIN_STABLE_STRUCTURE_CHECKS = 64;
    private static final int STABLE_STRUCTURE_SWEEP_FRAMES = 16;
    private static final int MAX_CUSTOM_RETRIES = 8;
    private static final String VIEW_FINDER_TAG = "douai.view_finder";
    private static final Map<Component, int[]> COMPONENT_IDS = componentIds();
    private static final int WIDGET_CONTAINER_ID = 2131362267;
    private static final int RIGHT_MENU_ID = 2131402182;
    private static final String RIGHT_SCALE_CLASS = "com.ss.android.ugc.aweme.feed.ui.FeedRightScaleView";
    private static final String WIDGET_CLASS = "com.ss.android.ugc.aweme.feed.ui.PenetrateTouchRelativeLayout";
    private static final Set<Integer> HIDDEN_RESOURCE_IDS = Collections.unmodifiableSet(
            new HashSet<>(java.util.Arrays.asList(
                    2131374327, 2131374339, 2131374340, 2131374360,
                    2131371285, 2131371299, 2131371376, 2131371605,
                    2131401927, 2131405175, 2131405391,
                    2131415727, 2131415055, 2131415033, 2131379772, 2131379761,
                    2131370692, 2131386238, 2131380062, 2131380063,
                    2131398470, 2131401805, 2131410333)));
    private static final String[] HIDDEN_ID_PARTS = {
            "digg", "like", "comment", "share", "avatar", "author_name", "favorite", "collect"
    };
    private static final String[] HIDDEN_DESCRIPTION_PARTS = {
            "点赞", "评论", "收藏", "分享", "作者头像", "用户头像", "关注",
            "like", "comment", "favorite", "collect", "share", "follow", "avatar"
    };

    private final List<XposedInterface.HookHandle> handles;
    private final Map<View, ViewState> originalStates = new WeakHashMap<>();
    private final Map<View, HideMonitor> hideMonitors = new WeakHashMap<>();
    private final Map<View, WindowMonitor> windowMonitors = new WeakHashMap<>();
    private final Map<View, Map<View, TargetStyle>> fixedTargets = new WeakHashMap<>();
    private final Map<View, Map<View, TargetStyle>> windowProgressTargets = new WeakHashMap<>();
    private final Map<View, Map<View, Integer>> customTargets = new WeakHashMap<>();
    private final Map<View, String> customSignatures = new WeakHashMap<>();
    private final Map<View, Integer> customResolvedCounts = new WeakHashMap<>();
    private final Map<View, Integer> customRetryCounts = new WeakHashMap<>();
    private final Set<Integer> resourceMismatches = new HashSet<>();
    private final Set<View> activeCards = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> knownCards = Collections.newSetFromMap(new WeakHashMap<>());
    private final Set<View> windowRoots = Collections.newSetFromMap(new WeakHashMap<>());
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    Consumer<View> requestStructureFrame = View::postInvalidateOnAnimation;
    private final AtomicInteger selectedCallbacks = new AtomicInteger();
    private final AtomicInteger cardScans = new AtomicInteger();
    private final AtomicInteger preDrawRechecks = new AtomicInteger();
    private final AtomicInteger viewSearches = new AtomicInteger();
    private final AtomicLong structureNodeChecks = new AtomicLong();
    private final AtomicInteger hiddenViews = new AtomicInteger();
    private final AtomicInteger hostStateResets = new AtomicInteger();
    private final Consumer<String> log;
    private final boolean validateResourceNames;
    private volatile boolean enabled;
    private volatile int actionAlphaPercent;
    private volatile ComponentHideSettings components;
    private volatile boolean componentsShown;
    private Consumer<Boolean> visibilityListener = ignored -> { };
    private WeakReference<View> selectedRoot = new WeakReference<>(null);
    private WeakReference<Object> selectedHolder = new WeakReference<>(null);
    private String selectedAid;
    // A dispatcher notification can precede the detail holder's selection or first attached frame.
    // Keep only a short hand-off, never a playback history. Reconcile only against the actual card.
    private String pendingPlaybackAid;
    private boolean pendingPaused;
    private WeakReference<Object> pendingPlaybackHolder;
    private long pendingPlaybackUntil;
    private String previousAid;
    private long previousAidUntil;
    private Runnable progressDiagnosticTask;
    private int progressSelectionSnapshots;
    private int progressChangeSnapshots;
    private int progressSnapshotSequence;
    private Application application;
    private ShortVideoProgressHook shortProgress;
    private volatile boolean closed;
    private volatile boolean videoLifecycleInstalled;
    private volatile boolean imageLifecycleInstalled;

    private FeedUiHideHook(ExtendedSettings settings, Consumer<String> log, boolean validateResourceNames) {
        this.enabled = settings != null && settings.hideInterface;
        this.actionAlphaPercent = settings == null ? 100 : settings.videoRightAlpha;
        this.components = settings == null ? null : settings.components;
        this.log = log;
        this.validateResourceNames = validateResourceNames;
        this.handles = new ArrayList<>();
    }

    public static FeedUiHideHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, ExtendedSettings settings,
                                         Consumer<String> log) throws Exception {
        return install(framework, report, loader, null, settings, log);
    }

    public static FeedUiHideHook install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Context context, ExtendedSettings settings,
                                         Consumer<String> log) throws Exception {
        FeedUiHideHook hook = new FeedUiHideHook(settings, log == null ? ignored -> { } : log,
                context != null);
        try {
            hook.installHolder(framework, report, loader, "feed_holder", "feed_holder_main_root",
                    "w", "Video");
            hook.installHolder(framework, report, loader, "image_holder", "image_holder_main_root",
                    "f", "Image");
            if (hook.handles.isEmpty()) throw new IllegalStateException("No feed overlay lifecycle is available");
            hook.installPlaybackCallbacks(framework, report, loader);
            hook.shortProgress = ShortVideoProgressHook.install(framework, report, loader, settings, hook.log);
            if (context != null && context.getApplicationContext() instanceof Application) {
                hook.application = (Application) context.getApplicationContext();
                hook.application.registerActivityLifecycleCallbacks(hook);
            }
            if (context instanceof Activity) {
                hook.mainHandler.post(() -> hook.trackWindow(((Activity) context).getWindow().getDecorView()));
            }
            return hook;
        } catch (Throwable failure) {
            if (hook.application != null) hook.application.unregisterActivityLifecycleCallbacks(hook);
            if (hook.shortProgress != null) hook.shortProgress.close();
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        boolean next = settings != null && settings.hideInterface;
        int nextAlpha = settings == null ? 100 : settings.videoRightAlpha;
        enabled = next;
        actionAlphaPercent = nextAlpha;
        components = settings == null ? null : settings.components;
        if (shortProgress != null) shortProgress.setSettings(settings);
        mainHandler.post(() -> {
            if (closed) return;
            ComponentHideSettings current = components;
            if (!usesPlaybackPolicy(current)) clearPendingPlayback();
            if (componentsShown && (current == null || !current.enabled || !current.showOnPause)) {
                componentsShown = false;
                notifyVisibility();
            }
            if (hasAnyEffect()) {
                for (View root : new ArrayList<>(activeCards)) {
                    if (root.isAttachedToWindow()) {
                        hiddenViews.addAndGet(hideMatchingViews(root));
                    }
                    installHideMonitor(root);
                }
            } else {
                removeAllHideMonitors();
                fixedTargets.clear();
            }
            refreshWindows();
            reconcileTargets();
            scheduleProgressDiagnostics("settings");
        });
    }

    private boolean hasAnyEffect() {
        ComponentHideSettings current = components;
        if (current != null && current.configured) return current.enabled;
        return enabled || actionAlphaPercent < 100;
    }

    public boolean areComponentsTemporarilyShown() { return componentsShown; }

    public void setComponentsTemporarilyShown(boolean shown) {
        mainHandler.post(() -> {
            ComponentHideSettings current = components;
            if (closed || componentsShown == shown || current == null
                    || !current.configured || !current.enabled) return;
            componentsShown = shown;
            clearPendingPlayback();
            for (View root : new ArrayList<>(activeCards)) {
                if (root.isAttachedToWindow()) hiddenViews.addAndGet(hideMatchingViews(root));
            }
            notifyVisibility();
            scheduleProgressDiagnostics("temporary");
            refreshWindows();
            hiddenViews.addAndGet(reconcileTargets());
        });
    }

    public void setComponentVisibilityListener(Consumer<Boolean> listener) {
        mainHandler.post(() -> {
            if (closed) return;
            visibilityListener = listener == null ? ignored -> { } : listener;
            notifyVisibility();
        });
    }

    private void notifyVisibility() {
        try { visibilityListener.accept(componentsShown); }
        catch (RuntimeException failure) { safeLog("Component visibility observer failed: " + failure.getMessage()); }
    }

    private void installPlaybackCallbacks(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader) {
        int firstHandle = handles.size();
        try {
            for (String rule : new String[]{"component_player_pause", "component_player_playing"}) {
                Method callback = resolveMethod(report, rule, loader);
                if (callback.getReturnType() != void.class || callback.getParameterCount() != 1
                        || callback.getParameterTypes()[0] != String.class) {
                    throw new IllegalStateException("Player visibility callback contract changed: " + rule);
                }
                boolean paused = rule.endsWith("_pause");
                XposedInterface.HookHandle handle = framework.hook(callback)
                        .setId(paused ? "DouAi.FeedUi.PlayerPause" : "DouAi.FeedUi.PlayerPlaying")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                        .intercept(chain -> {
                            Object result = chain.proceed();
                            Object argument = chain.getArg(0);
                            if (!closed && argument instanceof String) {
                                String aid = (String) argument;
                                mainHandler.post(() -> onPlaybackVisibility(aid, paused));
                            }
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
                handles.add(handle);
            }
        } catch (Exception failure) {
            for (int index = handles.size() - 1; index >= firstHandle; index--) {
                try { handles.remove(index).unhook(); }
                catch (Throwable rollbackFailure) { failure.addSuppressed(rollbackFailure); }
            }
            safeLog("Component pause/show unavailable: " + failure.getMessage());
        }
    }

    private static boolean usesPlaybackPolicy(ComponentHideSettings current) {
        return current != null && current.configured && current.enabled && current.showOnPause;
    }

    /** A verified state read after positioning a bookmark; never change user preferences. */
    public void syncBookmarkPlayback(Object holder, Boolean paused) {
        if (closed || holder == null || paused == null || !usesPlaybackPolicy(components)) return;
        String aid = readAid(holder);
        if (aid == null || aid.isEmpty()) return;
        // Queue behind player events already emitted by seek. Guard holder recycling at delivery.
        mainHandler.post(() -> {
            if (!closed && aid.equals(readAid(holder))) receivePlayback(aid, paused, holder);
        });
    }

    private void onPlaybackVisibility(String aid, boolean paused) {
        receivePlayback(aid, paused, null);
    }

    private void receivePlayback(String aid, boolean paused, Object verifiedHolder) {
        ComponentHideSettings current = components;
        View root = selectedRoot.get();
        Object holder = selectedHolder.get();
        if (closed || !usesPlaybackPolicy(current) || aid == null || aid.isEmpty()) return;
        if (aid.equals(selectedAid) && (verifiedHolder == null || verifiedHolder == holder)
                && aid.equals(readAid(holder)) && root != null && activeCards.contains(root)
                && root.isAttachedToWindow() && isShownWithoutModuleHiding(root)) {
            if (aid.equals(pendingPlaybackAid)) clearPendingPlayback();
            applyPlayback(root, current, paused);
            return;
        }
        // Outgoing callbacks must not overwrite a new card's already buffered initial state.
        boolean recentlyDeparted = aid.equals(previousAid) && SystemClock.uptimeMillis() <= previousAidUntil;
        if (!aid.equals(selectedAid) && recentlyDeparted && verifiedHolder == null) return;
        if (verifiedHolder != null && holder != null && verifiedHolder != holder
                && recentlyDeparted) return;
        pendingPlaybackAid = aid;
        pendingPlaybackHolder = verifiedHolder == null ? null : new WeakReference<>(verifiedHolder);
        pendingPaused = paused;
        pendingPlaybackUntil = SystemClock.uptimeMillis() + 1500;
    }

    private void clearPendingPlayback() {
        pendingPlaybackAid = null;
        pendingPlaybackHolder = null;
    }

    private void consumePendingPlayback(View root) {
        if (pendingPlaybackAid == null) return;
        ComponentHideSettings current = components;
        if (!usesPlaybackPolicy(current) || SystemClock.uptimeMillis() > pendingPlaybackUntil) {
            clearPendingPlayback(); return;
        }
        Object holder = selectedHolder.get();
        if (root != selectedRoot.get() || !root.isAttachedToWindow() || !activeCards.contains(root)
                || (pendingPlaybackHolder != null && pendingPlaybackHolder.get() != holder)
                || !pendingPlaybackAid.equals(readAid(holder)) || !isShownWithoutModuleHiding(root)) return;
        // A just-selected holder can be present before its work model is bound.
        if (selectedAid == null || selectedAid.isEmpty()) selectedAid = pendingPlaybackAid;
        if (!pendingPlaybackAid.equals(selectedAid)) return;
        boolean paused = pendingPaused; clearPendingPlayback();
        applyPlayback(root,current,paused);
    }

    private void departed(String aid) {
        if (aid == null || aid.isEmpty()) return;
        previousAid = aid;
        previousAidUntil = SystemClock.uptimeMillis() + 1500;
    }

    private void applyPlayback(View root, ComponentHideSettings current, boolean paused) {
        if (componentsShown == paused) return;
        componentsShown = paused;
        hiddenViews.addAndGet(hideMatchingViews(root));
        notifyVisibility();
        scheduleProgressDiagnostics("playback");
        refreshWindows();
        hiddenViews.addAndGet(reconcileTargets());
        if (paused && current.openCommentsOnShow) openComments(root);
    }

    private boolean isShownWithoutModuleHiding(View root) {
        if (root.isShown()) return true;
        for (View view = root; view != null;
             view = view.getParent() instanceof View ? (View) view.getParent() : null) {
            if (view.getVisibility() == View.VISIBLE) continue;
            ViewState state = originalStates.get(view);
            if (state == null || !state.trackComponentVisibility || state.visibility != View.VISIBLE
                    || view.getVisibility() != state.appliedVisibility) return false;
        }
        return true;
    }

    private static String readAid(Object holder) {
        if (holder == null) return null;
        try {
            Object aweme = holder.getClass().getMethod("getAweme").invoke(holder);
            if (aweme == null) return null;
            Object aid = aweme.getClass().getMethod("getAid").invoke(aweme);
            return aid instanceof String ? (String) aid : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    @SuppressLint("ResourceType") // IDs belong to the verified host APK, not module resources.
    private void openComments(View root) {
        View container = findComponentView(root, 2131371414);
        if (container == null) {
            container = findComponentView(root, 2131371299);
        }
        if (container == null || !container.isShown()) {
            safeLog("Component show: current comment control unavailable");
            return;
        }
        List<View> clicks = new ArrayList<>();
        collectClickTargets(container, clicks);
        if (clicks.size() != 1) {
            safeLog("Component show: comment click target is not unique");
            return;
        }
        try { clicks.get(0).performClick(); }
        catch (RuntimeException failure) { safeLog("Could not open current comments: " + failure.getMessage()); }
    }

    private static void collectClickTargets(View view, List<View> result) {
        if (!view.isShown() || !view.isEnabled()) return;
        if (view.isClickable()) {
            result.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) collectClickTargets(group.getChildAt(index), result);
        }
    }

    public int getSelectedCallbacks() {
        return selectedCallbacks.get();
    }

    public int getCardScans() {
        return cardScans.get();
    }

    public int getHiddenViews() {
        return hiddenViews.get();
    }

    public int getPreDrawRechecks() { return preDrawRechecks.get(); }
    public int getViewSearches() { return viewSearches.get(); }
    public long getStructureNodeChecks() { return structureNodeChecks.get(); }
    public int getResourceMismatchCount() { return resourceMismatches.size(); }
    public int getHostStateResets() { return hostStateResets.get(); }
    public boolean isVideoLifecycleInstalled() { return !closed && videoLifecycleInstalled; }
    public boolean isImageLifecycleInstalled() { return !closed && imageLifecycleInstalled; }
    public boolean isShortProgressLegacyInstalled() { return !closed && shortProgress != null && shortProgress.isLegacyInstalled(); }
    public boolean isShortProgressProInstalled() { return !closed && shortProgress != null && shortProgress.isProInstalled(); }

    private void installHolder(XposedInterface framework, HostScanReport report, ClassLoader loader,
                               String rulePrefix, String rootRule, String rootFieldName,
                               String holderName) {
        int firstHandle = handles.size();
        try {
            installTarget(framework, report, loader, rulePrefix + "_page_selected", rootRule,
                    rootFieldName, "DouAi.FeedUi.Hide" + holderName + "Actions", false);
            installTarget(framework, report, loader, rulePrefix + "_resume", rootRule,
                    rootFieldName, "DouAi.FeedUi." + holderName + "Resume", false);
            installTarget(framework, report, loader, rulePrefix + "_pause", rootRule,
                    rootFieldName, "DouAi.FeedUi." + holderName + "Pause", true);
            if ("Video".equals(holderName)) videoLifecycleInstalled = true;
            else if ("Image".equals(holderName)) imageLifecycleInstalled = true;
        } catch (Exception failure) {
            for (int index = handles.size() - 1; index >= firstHandle; index--) {
                try {
                    handles.remove(index).unhook();
                } catch (Throwable rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            safeLog(holderName + " overlay lifecycle unavailable: " + failure.getMessage());
        }
    }

    private void installTarget(XposedInterface framework, HostScanReport report, ClassLoader loader,
                               String methodRule, String rootRule, String rootFieldName,
                               String hookId, boolean paused) throws Exception {
        Method pageSelected = resolveMethod(report, methodRule, loader);
        if (pageSelected.getParameterCount() != 0 || pageSelected.getReturnType() != void.class) {
            throw new IllegalStateException("Feed holder selection callback changed: " + methodRule);
        }
        HostCandidate rootCandidate = uniqueCandidate(report, rootRule);
        if (!rootCandidate.getClassDescriptor().equals(classDescriptor(pageSelected.getDeclaringClass()))) {
            throw new IllegalStateException("Feed holder root rule does not match callback owner: " + rootRule);
        }
        Field itemView = pageSelected.getDeclaringClass().getField(rootFieldName);
        if (!View.class.isAssignableFrom(itemView.getType())) {
            throw new IllegalStateException("Feed holder root field is not a View: " + rootRule);
        }
        itemView.setAccessible(true);
        XposedInterface.HookHandle handle = framework.hook(pageSelected)
                .setId(hookId)
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> afterLifecycle(chain, itemView, paused));
        if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
        handles.add(handle);
    }

    private Object afterLifecycle(XposedInterface.Chain chain, Field itemViewField, boolean paused) throws Throwable {
        Object result = chain.proceed();
        if (closed) return result;
        selectedCallbacks.incrementAndGet();
        Object holder = chain.getThisObject();
        try {
            Object itemView = itemViewField.get(holder);
            if (itemView instanceof View) {
                View root = (View) itemView;
                mainHandler.post(() -> {
                    if (closed) return;
                    if (paused) {
                        if (selectedRoot.get() == root && selectedHolder.get() != holder) return;
                        activeCards.remove(root);
                        HideMonitor monitor = hideMonitors.remove(root);
                        if (monitor != null) monitor.remove();
                        fixedTargets.remove(root);
                        refreshCustomTargets(root.getRootView());
                        refreshWindowProgressTargets(root.getRootView());
                        reconcileTargets();
                        if (selectedRoot.get() == root) {
                            cancelProgressDiagnostics();
                            selectedRoot.clear();
                            selectedHolder.clear();
                            departed(selectedAid);
                            selectedAid = null;
                        }
                        return;
                    }
                    String aid = readAid(holder);
                    boolean changedCard = selectedRoot.get() != root || selectedHolder.get() != holder
                            || (aid != null && !aid.equals(selectedAid));
                    if (changedCard && selectedAid != null && !selectedAid.equals(aid)) departed(selectedAid);
                    if (changedCard && pendingPlaybackAid != null && aid != null && !aid.isEmpty()
                            && !pendingPlaybackAid.equals(aid)) clearPendingPlayback();
                    selectedRoot = new WeakReference<>(root);
                    selectedHolder = new WeakReference<>(holder);
                    selectedAid = aid;
                    ComponentHideSettings current = components;
                    if (changedCard && current != null && current.configured && current.hideOnSwitch) {
                        componentsShown = false;
                        notifyVisibility();
                    }
                    activeCards.add(root);
                    knownCards.add(root);
                    trackWindow(root.getRootView());
                    cardScans.incrementAndGet();
                    if (hasAnyEffect()) {
                        hiddenViews.addAndGet(hideMatchingViews(root));
                        installHideMonitor(root);
                        consumePendingPlayback(root);
                    }
                    if (changedCard) scheduleProgressDiagnostics("selection");
                    safeLog("Feed UI card scan: callbacks=" + selectedCallbacks.get()
                            + ", scanned=" + cardScans.get() + ", matched=" + hiddenViews.get()
                            + ", reapplied=" + hostStateResets.get());
                });
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            safeLog("Could not inspect feed card views: " + failure.getMessage());
        }
        return result;
    }

    private void cancelProgressDiagnostics() {
        if (progressDiagnosticTask != null) mainHandler.removeCallbacks(progressDiagnosticTask);
        progressDiagnosticTask = null;
    }

    private void scheduleProgressDiagnostics(String reason) {
        cancelProgressDiagnostics();
        ComponentHideSettings current = components;
        View root = selectedRoot.get();
        if (closed || root == null || current == null || !current.configured
                || current.alpha(Component.PROGRESS) <= 0) return;
        boolean selection = "selection".equals(reason);
        // Reserve samples for same-video setting changes after the initial card samples are exhausted.
        if (selection ? progressSelectionSnapshots >= 18 : progressChangeSnapshots >= 18) return;
        WeakReference<View> reference = new WeakReference<>(root);
        progressDiagnosticTask = new Runnable() {
            private int sample;

            @Override public void run() {
                View card = reference.get();
                ComponentHideSettings settings = components;
                if (closed || card == null || card != selectedRoot.get() || !activeCards.contains(card)
                        || settings == null || !settings.configured || settings.alpha(Component.PROGRESS) <= 0
                        || (selection ? progressSelectionSnapshots >= 18 : progressChangeSnapshots >= 18)) {
                    if (progressDiagnosticTask == this) progressDiagnosticTask = null;
                    return;
                }
                if (card.isAttachedToWindow()) {
                    if (selection) progressSelectionSnapshots++;
                    else progressChangeSnapshots++;
                    int sequence = ++progressSnapshotSequence;
                    safeLog("Feed progress snapshot=" + sequence + " reason=" + reason + " sample=" + sample
                            + " enabled=" + settings.enabled + " temporary=" + componentsShown
                            + " progress=" + settings.alpha(Component.PROGRESS)
                            + " bottomTools=" + settings.alpha(Component.BOTTOM_TOOLS)
                            + " bottomNav=" + settings.alpha(Component.BOTTOM_NAV)
                            + " custom=" + settings.customViews.size());
                    for (String line : FeedProgressDiagnostics.capture(card.getRootView(), card,
                            FeedUiHideHook.this::progressDiagnosticStyle)) {
                        safeLog("Feed progress snapshot=" + sequence + " " + line);
                    }
                }
                if (++sample < 3) mainHandler.postDelayed(this, sample == 1 ? 750L : 2250L);
                else if (progressDiagnosticTask == this) progressDiagnosticTask = null;
            }
        };
        mainHandler.post(progressDiagnosticTask);
    }

    private String progressDiagnosticStyle(View view) {
        for (Map<View, Integer> custom : customTargets.values()) {
            Integer alpha = custom.get(view);
            if (alpha != null) return "custom alpha=" + alpha;
        }
        for (Map<View, TargetStyle> targets : fixedTargets.values()) {
            TargetStyle style = targets.get(view);
            if (style != null) return (style.component ? "component" : "legacy") + " alpha=" + style.alpha;
        }
        for (Map<View, TargetStyle> targets : windowProgressTargets.values()) {
            TargetStyle style = targets.get(view);
            if (style != null) return "page progress alpha=" + style.alpha;
        }
        return null;
    }

    private int hideMatchingViews(View root) {
        viewSearches.incrementAndGet();
        ComponentHideSettings current = components;
        if (current != null && current.configured) return applyComponents(root, current);
        Map<View, Integer> targets = new LinkedHashMap<>();
        List<View> widgetRegions = new ArrayList<>();
        List<View> rightRegions = new ArrayList<>();
        collectRegions(root, root, widgetRegions, rightRegions);
        if (enabled) {
            for (View region : widgetRegions) addTarget(targets, region, 100);
        }
        for (View region : rightRegions) addTarget(targets, region, actionAlphaPercent);
        collectFallbackTargets(root, root, targets, rightRegions.isEmpty());
        Map<View, TargetStyle> styles = new WeakHashMap<>();
        for (Map.Entry<View, Integer> target : targets.entrySet()) {
            styles.put(target.getKey(), new TargetStyle(target.getValue(), false, target.getKey()));
        }
        fixedTargets.put(root, styles);
        captureCardStructure(root);
        refreshCustomTargets(root.getRootView(), false);
        return reconcileTargets();
    }

    private int reapplyCachedTargets(View root) {
        if (root == null) return 0;
        int changed = 0;
        View windowRoot = root.getRootView();
        Map<View, Integer> custom = customTargets.get(windowRoot);
        boolean windowOwnsCustom = windowMonitors.containsKey(windowRoot);
        Map<View, TargetStyle> fixed = fixedTargets.get(root);
        if (fixed != null) {
            for (Map.Entry<View, TargetStyle> target : fixed.entrySet()) {
                if (target.getKey() == null || isFinderView(target.getKey())) continue;
                if (windowOwnsCustom && custom != null && custom.containsKey(target.getKey())) continue;
                TargetStyle style = target.getValue();
                changed += style.component ? applyComponentTarget(target.getKey(), style.alpha, style.trackHostAlpha)
                        : applyTarget(target.getKey(), style.alpha);
            }
        }
        // The window observer already owns these properties, including feed-scoped custom rules.
        if (!windowOwnsCustom && custom != null) {
            for (Map.Entry<View, Integer> target : custom.entrySet()) {
                View view = target.getKey();
                if (view != null && !isFinderView(view)) changed += applyComponentTarget(view, target.getValue(), true);
            }
        }
        return changed;
    }

    private int applyComponents(View root, ComponentHideSettings current) {
        Map<View, Integer> targets = new LinkedHashMap<>();
        Map<View, Component> targetOwners = new WeakHashMap<>();
        if (current.enabled && !componentsShown) {
            Map<View, Component> matches = new LinkedHashMap<>();
            collectComponents(root, root, matches);
            for (Map.Entry<View, Component> entry : matches.entrySet()) {
                int alpha = current.alpha(entry.getValue());
                if (alpha == 255) continue;
                addComponentTarget(entry.getKey(), entry.getValue(), alpha, current, matches, targets, targetOwners);
            }
        }
        Map<View, TargetStyle> styles = new WeakHashMap<>();
        for (Map.Entry<View, Integer> target : targets.entrySet()) {
            styles.put(target.getKey(), new TargetStyle(target.getValue(), true, target.getKey(),
                    targetOwners.get(target.getKey()) == Component.PROGRESS));
        }
        fixedTargets.put(root, styles);
        captureCardStructure(root);
        refreshCustomTargets(root.getRootView(), false);
        return reconcileTargets();
    }

    private boolean hasCustomEffect() {
        ComponentHideSettings current = components;
        return current != null && current.enabled && !componentsShown && !current.customViews.isEmpty();
    }

    private boolean hasWindowEffect() {
        ComponentHideSettings current = components;
        return hasCustomEffect() || current != null && current.configured && current.enabled
                && current.alpha(Component.PROGRESS) != 255;
    }

    private void refreshWindowProgressTargets(View windowRoot) {
        Map<View, TargetStyle> targets = new WeakHashMap<>();
        ComponentHideSettings current = components;
        View card = selectedRoot.get();
        if (current != null && current.configured && current.enabled && !componentsShown
                && current.alpha(Component.PROGRESS) != 255 && card != null && activeCards.contains(card)
                && card.isAttachedToWindow() && card.getRootView() == windowRoot) {
            collectWindowProgress(windowRoot, targets, current.alpha(Component.PROGRESS), new int[]{MAX_TREE_NODES}, 0);
        }
        windowProgressTargets.put(windowRoot, targets);
        WindowMonitor monitor = windowMonitors.get(windowRoot);
        if (monitor != null) monitor.structure.capture(windowRoot);
    }

    private void collectWindowProgress(View view, Map<View, TargetStyle> targets, int alpha, int[] remaining, int depth) {
        if (view == null || remaining[0]-- <= 0 || depth > MAX_TREE_DEPTH || knownCards.contains(view)
                || isFinderView(view)) return;
        if (hasClass(view, "com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar")
                || hasClass(view, "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView")) {
            targets.put(view, new TargetStyle(alpha, true, view, true));
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectWindowProgress(group.getChildAt(index), targets, alpha, remaining, depth + 1);
            }
        }
    }

    private void captureCardStructure(View root) {
        HideMonitor monitor = hideMonitors.get(root);
        if (monitor != null) monitor.captureStructure(root);
    }

    private void trackWindow(View root) {
        if (closed || root == null) return;
        windowRoots.add(root);
        if (!hasWindowEffect()) return;
        if (!windowMonitors.containsKey(root)) {
            WindowMonitor monitor = new WindowMonitor(root);
            windowMonitors.put(root, monitor);
            root.addOnAttachStateChangeListener(monitor);
            if (root.isAttachedToWindow()) monitor.attachObserver(root);
        }
        refreshCustomTargets(root, true);
        refreshWindowProgressTargets(root);
        hiddenViews.addAndGet(reconcileTargets());
    }

    private void refreshWindows() {
        for (View root : new ArrayList<>(windowRoots)) {
            if (hasWindowEffect()) trackWindow(root);
            else {
                WindowMonitor monitor = windowMonitors.remove(root);
                if (monitor != null) monitor.remove();
                customTargets.remove(root);
                customSignatures.remove(root);
                customResolvedCounts.remove(root);
                customRetryCounts.remove(root);
                windowProgressTargets.remove(root);
            }
        }
    }

    private void refreshCustomTargets(View windowRoot) {
        refreshCustomTargets(windowRoot, true);
    }

    private void refreshCustomTargets(View windowRoot, boolean force) {
        if (!hasCustomEffect() || windowRoot == null || !windowRoot.isAttachedToWindow()) {
            customTargets.remove(windowRoot);
            customSignatures.remove(windowRoot);
            customResolvedCounts.remove(windowRoot);
            customRetryCounts.remove(windowRoot);
            WindowMonitor monitor = windowMonitors.get(windowRoot);
            if (monitor != null) monitor.captureTargets(Collections.emptySet());
            return;
        }
        ComponentHideSettings current = components;
        StringBuilder signature = new StringBuilder();
        for (CustomViewLocator locator : current.customViews) {
            signature.append(locator.identityKey()).append('=').append(locator.alpha).append('\n');
        }
        String nextSignature = signature.toString();
        String previousSignature = customSignatures.get(windowRoot);
        Map<View, Integer> previous = customTargets.get(windowRoot);
        if (!force && nextSignature.equals(previousSignature)
                && previous != null
                && customResolvedCounts.getOrDefault(windowRoot, 0) == current.customViews.size()) {
            boolean attached = true;
            for (View target : previous.keySet()) attached &= target.isAttachedToWindow();
            if (attached) return;
        }
        if (!force && nextSignature.equals(previousSignature)
                && customResolvedCounts.getOrDefault(windowRoot, 0) < current.customViews.size()
                && customRetryCounts.getOrDefault(windowRoot, 0) >= MAX_CUSTOM_RETRIES) return;
        viewSearches.incrementAndGet();
        Map<View, Integer> targets = new WeakHashMap<>();
        int resolvedCount = 0;
        for (CustomViewLocator locator : current.customViews) {
            boolean resolved = false;
            if (locator.scope == CustomViewLocator.Scope.WINDOW) {
                View target = locator.resolve(windowRoot, null);
                if (target != null && target != windowRoot && !isFinderView(target)) {
                    targets.put(target, locator.alpha);
                    resolved = true;
                }
            } else {
                for (View feedRoot : new ArrayList<>(knownCards)) {
                    if (feedRoot.isAttachedToWindow() && feedRoot.getRootView() == windowRoot) {
                        View target = locator.resolve(windowRoot, feedRoot);
                        if (target != null && target != feedRoot && !isFinderView(target)) {
                            targets.put(target, locator.alpha);
                            resolved = true;
                        }
                    }
                }
            }
            if (resolved) resolvedCount++;
        }
        customTargets.put(windowRoot, targets);
        WindowMonitor monitor = windowMonitors.get(windowRoot);
        if (monitor != null) monitor.captureTargets(targets.keySet());
        customSignatures.put(windowRoot, nextSignature);
        customResolvedCounts.put(windowRoot, resolvedCount);
        if (force || !nextSignature.equals(previousSignature)) {
            customRetryCounts.put(windowRoot, 1);
        } else if (resolvedCount == current.customViews.size()) {
            customRetryCounts.remove(windowRoot);
        } else {
            customRetryCounts.put(windowRoot, customRetryCounts.getOrDefault(windowRoot, 0) + 1);
        }
    }

    private int reconcileTargets() {
        Map<View, TargetStyle> targets = new LinkedHashMap<>();
        for (Map<View, TargetStyle> values : fixedTargets.values()) targets.putAll(values);
        for (Map<View, TargetStyle> values : windowProgressTargets.values()) targets.putAll(values);
        for (Map<View, Integer> values : customTargets.values()) {
            for (Map.Entry<View, Integer> target : values.entrySet()) {
                targets.put(target.getKey(), new TargetStyle(target.getValue(), true, target.getKey(), true));
            }
        }
        for (View view : new ArrayList<>(originalStates.keySet())) {
            if (!targets.containsKey(view)) {
                ViewState original = originalStates.remove(view);
                if (view != null && original != null) restore(view, original);
            }
        }
        int changed = 0;
        for (Map.Entry<View, TargetStyle> target : targets.entrySet()) {
            if (target.getKey() == null || isFinderView(target.getKey())) continue;
            TargetStyle style = target.getValue();
            changed += style.component ? applyComponentTarget(target.getKey(), style.alpha, style.trackHostAlpha)
                    : applyTarget(target.getKey(), style.alpha);
        }
        return changed;
    }

    private static boolean isFinderView(View view) {
        for (View current = view; current != null; ) {
            Object tag = current.getTag();
            if (tag instanceof String && ((String) tag).startsWith(VIEW_FINDER_TAG)) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private void collectComponents(View view, View root, Map<View, Component> matches) {
        collectComponents(view, root, matches, new int[]{MAX_TREE_NODES}, 0);
    }

    @SuppressLint("ResourceType") // Resource IDs are verified against the host APK, not the module R class.
    private void collectComponents(View view, View root, Map<View, Component> matches,
                                   int[] remaining, int depth) {
        if (view == null || remaining[0]-- <= 0 || depth > MAX_TREE_DEPTH || isFinderView(view)) return;
        Component component = null;
        int canonicalId = canonicalViewId(view);
        for (Map.Entry<Component, int[]> entry : COMPONENT_IDS.entrySet()) {
            for (int id : entry.getValue()) {
                if (canonicalId == id && resourceMatches(view, id)) {
                    component = entry.getKey();
                    break;
                }
            }
            if (component != null) break;
        }
        if (component == null && hasClass(view, "com.ss.android.ugc.aweme.feed.desc.ui.FeedDescRootLayout")) {
            component = Component.DESCRIPTION;
        } else if (component == null && (hasClass(view, "com.ss.android.ugc.aweme.feed.ui.seekbar.CustomizedUISeekBar")
                || hasClass(view, "com.ss.android.ugc.aweme.feed.widget.LineProgressBar")
                || hasClass(view, "com.ss.android.ugc.aweme.feed.widget.ChapterOuterSeekBar")
                || hasClass(view, "com.ss.android.ugc.aweme.o.seekbar.views.seekbar.ProSeekBarView")
                || hasClass(view, "com.ss.android.ugc.aweme.shortvideo.ui.NewVideoPlayerProgressbar"))) {
            component = Component.PROGRESS;
        } else if (component == null && hasClass(view, "com.ss.android.ugc.aweme.base.ui.TagLayout")) {
            component = Component.FEEDBACK;
        } else if (component == null && (hasClass(view, "com.ss.android.ugc.aweme.base.ui.FeedAnchorLayout")
                || hasClass(view, "com.ss.android.ugc.aweme.feed.anchor.framework.base.MaxWidthFrameLayout"))) {
            component = Component.BOTTOM_TOOLS;
        }
        if (view != root && component != null) matches.put(view, component);
        if (canonicalId == 2131378332 && component == Component.FEEDBACK
                && view.getParent() instanceof android.widget.LinearLayout) {
            android.widget.LinearLayout wrapper = (android.widget.LinearLayout) view.getParent();
            if (wrapper != root && wrapper.getId() == View.NO_ID && wrapper.getChildCount() == 1
                    && wrapper.getBackground() != null) matches.put(wrapper, Component.FEEDBACK);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectComponents(group.getChildAt(index), root, matches, remaining, depth + 1);
            }
        }
    }

    private static void addComponentTarget(View view, Component owner, int alpha,
                                           ComponentHideSettings settings, Map<View, Component> matches,
                                           Map<View, Integer> targets, Map<View, Component> targetOwners) {
        Component nestedOwner = matches.get(view);
        if (nestedOwner != null && nestedOwner != owner && settings.alpha(nestedOwner) != alpha) return;
        boolean sharedContainer = false;
        for (Map.Entry<View, Component> entry : matches.entrySet()) {
            if (entry.getValue() != owner && settings.alpha(entry.getValue()) != alpha
                    && entry.getKey() != view && isDescendant(entry.getKey(), view)) {
                sharedContainer = true;
                break;
            }
        }
        if (!sharedContainer) {
            addTarget(targets, view, alpha);
            targetOwners.put(view, owner);
            return;
        }
        // Apply only the container's own branches so a nested component keeps its independent value.
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                addComponentTarget(group.getChildAt(index), owner, alpha, settings, matches, targets, targetOwners);
            }
        }
    }

    private int applyComponentTarget(View target, int alpha) {
        return applyComponentTarget(target, alpha, false);
    }

    private int applyComponentTarget(View target, int alpha, boolean trackHostAlpha) {
        ViewState original = originalStates.get(target);
        boolean first = original == null;
        if (first) {
            original = new ViewState(target);
            originalStates.put(target, original);
        }
        original.observeHostState(target);
        original.trackComponentVisibility = true;
        original.trackComponentAlpha = alpha < 0 || trackHostAlpha;
        // A host-hidden component is never made visible by an opacity setting.
        int visibility = original.visibility;
        if (visibility == View.VISIBLE && alpha < 0) visibility = alpha == -2 ? View.GONE : View.INVISIBLE;
        float value = alpha < 0 ? original.alpha : original.alpha * alpha / 255f;
        boolean clickable = alpha < 0 ? false : original.clickable;
        boolean focusable = alpha < 0 ? false : original.focusable;
        boolean reset = !first && (target.getVisibility() != visibility
                || Math.abs(target.getAlpha() - value) > 0.001f
                || target.isClickable() != clickable || target.isFocusable() != focusable);
        if (target.getVisibility() != visibility) target.setVisibility(visibility);
        if (Math.abs(target.getAlpha() - value) > 0.001f) target.setAlpha(value);
        if (target.isClickable() != clickable) target.setClickable(clickable);
        if (target.isFocusable() != focusable) target.setFocusable(focusable);
        original.recordApplied(target);
        if (first || reset) recordComponentState(target, visibility, value, clickable, focusable, reset);
        if (reset) hostStateResets.incrementAndGet();
        return first ? 1 : 0;
    }

    private static void recordComponentState(View view, int visibility, float alpha,
                                             boolean clickable, boolean focusable, boolean reset) {
        try {
            boolean matches = view.getVisibility() == visibility && Math.abs(view.getAlpha() - alpha) <= 0.001f
                    && view.isClickable() == clickable && view.isFocusable() == focusable;
            DouAiLog.Operation operation = DouAiLog.begin("feed_ui", reset ? "host_state_changed" : "component_matched");
            operation.finish(matches ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED,
                    matches ? "view_properties_readback" : "view_properties_differ",
                    "resource_id=" + view.getId() + ";expected_visibility=" + visibility
                            + ";actual_visibility=" + view.getVisibility() + ";expected_alpha=" + alpha
                            + ";actual_alpha=" + view.getAlpha() + ";result_scope=current_view;later_layout=unobserved");
        } catch (Throwable ignored) { }
    }

    private static Map<Component, int[]> componentIds() {
        EnumMap<Component, int[]> ids = new EnumMap<>(Component.class);
        ids.put(Component.AVATAR, new int[]{2131366240, 2131370692, 2131386238, 2131380062, 2131380063,
                2131415727, 2131415055, 2131415033, 2131379772, 2131379761, 2131410333});
        ids.put(Component.LIKE, new int[]{2131374347, 2131374327, 2131374339, 2131374340, 2131374360});
        ids.put(Component.COMMENT, new int[]{2131371414, 2131371285, 2131371299, 2131371376, 2131371605});
        ids.put(Component.COLLECT, new int[]{2131371073});
        ids.put(Component.SHARE, new int[]{2131400515, 2131401927, 2131405175, 2131405391});
        ids.put(Component.MUSIC_DISC, new int[]{2131416104, 2131394015, 2131394011, 2131394653});
        ids.put(Component.CARD, new int[]{2131388590});
        ids.put(Component.AUTHOR, new int[]{2131398470});
        ids.put(Component.CO_CREATION, new int[]{2131370902, 2131370904});
        ids.put(Component.MUSIC_INFO, new int[]{2131416105, 2131394215, 2131394220});
        ids.put(Component.FULLSCREEN, new int[]{2131393278, 2131393279});
        ids.put(Component.FEEDBACK, new int[]{2131371909, 2131378332});
        ids.put(Component.PROGRESS, new int[]{2131378139, 2131369833});
        ids.put(Component.BOTTOM_TOOLS, new int[]{2131367357, 2131378377, 2131378379});
        ids.put(Component.TIME_LOCATION, new int[]{2131415966, 2131378163});
        return Collections.unmodifiableMap(ids);
    }

    private void collectRegions(View view, View root, List<View> widgets, List<View> right) {
        collectRegions(view, root, widgets, right, new int[]{MAX_TREE_NODES}, 0);
    }

    private void collectRegions(View view, View root, List<View> widgets, List<View> right,
                                int[] remaining, int depth) {
        if (view == null || remaining[0]-- <= 0 || depth > MAX_TREE_DEPTH || isFinderView(view)) return;
        if (view != root && view instanceof ViewGroup
                && (resourceMatches(view, WIDGET_CONTAINER_ID)
                || hasClass(view, WIDGET_CLASS))) {
            widgets.add(view);
        }
        if (view != root && resourceMatches(view, RIGHT_MENU_ID)) right.add(view);
        if (view != root && hasClass(view, RIGHT_SCALE_CLASS)) {
            View target = view;
            if (view.getParent() instanceof ViewGroup) {
                View parent = (View) view.getParent();
                if (parent != root && !resourceMatches(parent, WIDGET_CONTAINER_ID)
                        && !hasClass(parent, WIDGET_CLASS)) target = parent;
            }
            right.add(target);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectRegions(group.getChildAt(index), root, widgets, right, remaining, depth + 1);
            }
        }
    }

    private static boolean hasClass(View view, String className) {
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (className.equals(type.getName())) return true;
        }
        return false;
    }

    private static void addTarget(Map<View, Integer> targets, View view, int alpha) {
        for (View ancestor = view; ancestor != null; ) {
            if (targets.containsKey(ancestor)) return;
            ancestor = ancestor.getParent() instanceof View ? (View) ancestor.getParent() : null;
        }
        targets.keySet().removeIf(existing -> isDescendant(existing, view));
        targets.put(view, alpha);
    }

    private static boolean isDescendant(View view, View ancestor) {
        for (View current = view; current != null; ) {
            if (current == ancestor) return true;
            current = current.getParent() instanceof View ? (View) current.getParent() : null;
        }
        return false;
    }

    private void collectFallbackTargets(View view, View root, Map<View, Integer> targets,
                                        boolean actionFallback) {
        collectFallbackTargets(view, root, targets, actionFallback, new int[]{MAX_TREE_NODES}, 0);
    }

    private void collectFallbackTargets(View view, View root, Map<View, Integer> targets,
                                        boolean actionFallback, int[] remaining, int depth) {
        if (view == null || remaining[0]-- <= 0 || depth > MAX_TREE_DEPTH || isFinderView(view)) return;
        boolean match = false;
        if (view.getId() != View.NO_ID) {
            int canonicalId = canonicalViewId(view);
            match = isHiddenResourceId(canonicalId)
                    && resourceMatches(view, canonicalId);
            try {
                if (!match) {
                    String name = view.getResources().getResourceEntryName(view.getId())
                            .toLowerCase(java.util.Locale.ROOT);
                    match = matches(name);
                }
            } catch (RuntimeException ignored) {
                // Resource-less generated views are not candidates.
            }
        }
        if (!match) match = matchesDescription(view.getContentDescription());
        if (!match && view instanceof TextView) match = matchesDescription(((TextView) view).getText());
        boolean author = isAuthor(view);
        if (view != root && match && (enabled || (actionFallback && !author))) {
            addTarget(targets, view, author ? 100 : actionAlphaPercent);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectFallbackTargets(group.getChildAt(index), root, targets, actionFallback,
                        remaining, depth + 1);
            }
        }
    }

    private boolean isAuthor(View view) {
        int id = canonicalViewId(view);
        if ((id == 2131398470 || id == 2131401805 || id == 2131410333)
                && resourceMatches(view, id)) return true;
        CharSequence description = view.getContentDescription();
        if (description != null) {
            String text = description.toString().toLowerCase(java.util.Locale.ROOT);
            if (text.contains("avatar") || text.contains("作者") || text.contains("用户头像")) return true;
        }
        try {
            String name = view.getResources().getResourceEntryName(view.getId()).toLowerCase(java.util.Locale.ROOT);
            return name.contains("author") || name.contains("avatar");
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private int applyTarget(View target, int alphaPercent) {
        boolean firstMatch;
        ViewState original;
        synchronized (originalStates) {
            original = originalStates.get(target);
            firstMatch = original == null;
            if (firstMatch) {
                original = new ViewState(target);
                originalStates.put(target, original);
            }
        }
        float targetAlpha = original.alpha * alphaPercent / 100f;
        boolean shouldHide = enabled;
        int targetVisibility = shouldHide && original.visibility == View.VISIBLE
                ? View.INVISIBLE : original.visibility;
        boolean clickable = shouldHide ? false : original.clickable;
        boolean focusable = shouldHide ? false : original.focusable;
        boolean hostMadeVisible = !firstMatch && (target.getVisibility() != targetVisibility
                || Math.abs(target.getAlpha() - targetAlpha) > 0.001f
                || target.isClickable() != clickable || target.isFocusable() != focusable);
        if (target.getVisibility() != targetVisibility) target.setVisibility(targetVisibility);
        if (Math.abs(target.getAlpha() - targetAlpha) > 0.001f) target.setAlpha(targetAlpha);
        if (target.isClickable() != clickable) target.setClickable(clickable);
        if (target.isFocusable() != focusable) target.setFocusable(focusable);
        original.recordApplied(target);
        if (hostMadeVisible) hostStateResets.incrementAndGet();
        return firstMatch ? 1 : 0;
    }

    static boolean isHiddenResourceId(int resourceId) {
        return HIDDEN_RESOURCE_IDS.contains(resourceId);
    }

    private int canonicalViewId(View view) {
        return validateResourceNames ? HostResourceCatalog.canonicalId(view.getId(), view.getResources()) : view.getId();
    }

    @SuppressLint("ResourceType")
    private View findComponentView(View root, int canonicalId) {
        return validateResourceNames ? HostResourceCatalog.findVerifiedView(root, canonicalId)
                : root.findViewById(canonicalId);
    }

    private boolean resourceMatches(View view, int canonicalId) {
        if (canonicalId == View.NO_ID) return false;
        if (canonicalViewId(view) == canonicalId) return true;
        if (validateResourceNames) resourceMismatches.add(canonicalId);
        return false;
    }

    static boolean matchesDescription(CharSequence value) {
        if (value == null || value.length() == 0) return false;
        String normalized = value.toString().toLowerCase(java.util.Locale.ROOT).trim();
        for (String part : HIDDEN_DESCRIPTION_PARTS) {
            if (normalized.equals(part) || normalized.startsWith(part + " ")
                    || normalized.startsWith(part + ",") || normalized.startsWith(part + "，")
                    || normalized.endsWith(" " + part)) return true;
        }
        return false;
    }

    private void installHideMonitor(View root) {
        if (closed || !hasAnyEffect()) return;
        synchronized (hideMonitors) {
            if (hideMonitors.containsKey(root)) return;
            HideMonitor monitor = new HideMonitor(root);
            hideMonitors.put(root, monitor);
            root.addOnAttachStateChangeListener(monitor);
            if (root.isAttachedToWindow()) monitor.attachObserver(root);
        }
    }

    private void removeAllHideMonitors() {
        synchronized (hideMonitors) {
            for (HideMonitor monitor : hideMonitors.values()) {
                monitor.remove();
            }
            hideMonitors.clear();
        }
    }

    private final class HideMonitor implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        private final java.lang.ref.WeakReference<View> rootReference;
        private ViewTreeObserver observer;
        private boolean targetsDirty;
        private final ViewStructure structure = new ViewStructure();

        HideMonitor(View root) {
            this.rootReference = new java.lang.ref.WeakReference<>(root);
            captureStructure(root);
        }

        void captureStructure(View root) {
            ComponentHideSettings current = components;
            structure.capture(root, current == null || !current.configured);
        }

        @Override
        public boolean onPreDraw() {
            View root = rootReference.get();
            if (root == null) {
                remove();
                return true;
            }
            if (hasAnyEffect() && !closed && activeCards.contains(root)) {
                if (pendingPlaybackAid != null) consumePendingPlayback(root);
                preDrawRechecks.incrementAndGet();
                if (!targetsDirty && !structure.matches(structureNodeChecks)) targetsDirty = true;
                Map<View, TargetStyle> targets = fixedTargets.get(root);
                if (!targetsDirty && targets != null) {
                    for (Map.Entry<View, TargetStyle> target : targets.entrySet()) {
                        if (!target.getValue().identity.matches(target.getKey())) { targetsDirty = true; break; }
                    }
                }
                if (targetsDirty) {
                    targetsDirty = false;
                    refreshCustomTargets(root.getRootView(), true);
                    hiddenViews.addAndGet(hideMatchingViews(root));
                } else hiddenViews.addAndGet(reapplyCachedTargets(root));
                if (structure.hasPendingSweep()) requestStructureFrame.accept(root);
            }
            return true;
        }

        @Override public void onGlobalLayout() {
            View root = rootReference.get();
            if (!closed && hasAnyEffect() && root != null && activeCards.contains(root)) structure.beginSweep();
        }

        void remove() {
            View root = rootReference.get();
            detachObserver();
            if (root != null) root.removeOnAttachStateChangeListener(this);
        }

        void attachObserver(View root) {
            ViewTreeObserver next = root.getViewTreeObserver();
            if (next == null || !next.isAlive() || next == observer) return;
            detachObserver();
            observer = next;
            observer.addOnPreDrawListener(this);
            observer.addOnGlobalLayoutListener(this);
            captureStructure(root);
        }

        void detachObserver() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
                observer.removeOnGlobalLayoutListener(this);
            }
            observer = null;
            targetsDirty = false;
            structure.clear();
        }

        @Override
        public void onViewAttachedToWindow(View view) {
            if (closed || !hasAnyEffect() || !activeCards.contains(view)) return;
            consumePendingPlayback(view);
            hiddenViews.addAndGet(hideMatchingViews(view));
            attachObserver(view);
            captureStructure(view);
        }

        @Override
        public void onViewDetachedFromWindow(View view) {
            detachObserver();
            fixedTargets.remove(view);
            refreshWindows();
            reconcileTargets();
        }
    }

    private final class WindowMonitor implements ViewTreeObserver.OnPreDrawListener, ViewTreeObserver.OnGlobalLayoutListener,
            View.OnAttachStateChangeListener {
        private final WeakReference<View> rootReference;
        private ViewTreeObserver observer;
        private final Map<View, TargetIdentity> identities = new WeakHashMap<>();
        private boolean targetsDirty;
        private final ViewStructure structure = new ViewStructure();

        WindowMonitor(View root) { rootReference = new WeakReference<>(root); }

        @Override
        public boolean onPreDraw() {
            View root = rootReference.get();
            if (root == null || closed) {
                remove();
                return true;
            }
            if (hasWindowEffect()) {
                if (!targetsDirty && !structure.matches(structureNodeChecks)) targetsDirty = true;
                if (!targetsDirty) {
                    for (Map.Entry<View, TargetIdentity> entry : identities.entrySet()) {
                        if (!entry.getValue().matches(entry.getKey())) { targetsDirty = true; break; }
                    }
                }
                boolean unresolved = customResolvedCounts.getOrDefault(root, 0) < components.customViews.size()
                        && customRetryCounts.getOrDefault(root, 0) < MAX_CUSTOM_RETRIES;
                if (targetsDirty || unresolved) {
                    boolean force = targetsDirty;
                    targetsDirty = false;
                    refreshCustomTargets(root, force);
                    refreshWindowProgressTargets(root);
                    hiddenViews.addAndGet(reconcileTargets());
                }
                Map<View, Integer> targets = customTargets.get(root);
                int changed = 0;
                if (targets != null) for (Map.Entry<View, Integer> target : targets.entrySet()) {
                    View view = target.getKey();
                    if (view != null && !isFinderView(view)) changed += applyComponentTarget(view, target.getValue(), true);
                }
                Map<View, TargetStyle> progress = windowProgressTargets.get(root);
                if (progress != null) for (Map.Entry<View, TargetStyle> entry : progress.entrySet()) {
                    if (targets != null && targets.containsKey(entry.getKey())) continue;
                    changed += applyComponentTarget(entry.getKey(), entry.getValue().alpha, true);
                }
                hiddenViews.addAndGet(changed);
                if (structure.hasPendingSweep()) requestStructureFrame.accept(root);
            }
            return true;
        }

        @Override public void onGlobalLayout() {
            if (!closed && hasWindowEffect()) structure.beginSweep();
        }

        void captureTargets(Set<View> targets) {
            identities.clear();
            for (View target : targets) identities.put(target, new TargetIdentity(target));
            View root = rootReference.get();
            if (root != null) structure.capture(root);
        }

        void attachObserver(View root) {
            ViewTreeObserver next = root.getViewTreeObserver();
            if (next == null || !next.isAlive() || next == observer) return;
            detachObserver();
            observer = next;
            observer.addOnPreDrawListener(this);
            observer.addOnGlobalLayoutListener(this);
            structure.capture(root);
        }

        void detachObserver() {
            if (observer != null && observer.isAlive()) {
                observer.removeOnPreDrawListener(this);
                observer.removeOnGlobalLayoutListener(this);
            }
            observer = null;
            targetsDirty = false;
            identities.clear();
            structure.clear();
        }

        void remove() {
            detachObserver();
            View root = rootReference.get();
            if (root != null) root.removeOnAttachStateChangeListener(this);
        }

        @Override public void onViewAttachedToWindow(View view) {
            if (closed || !hasWindowEffect()) return;
            attachObserver(view);
            refreshCustomTargets(view);
            refreshWindowProgressTargets(view);
            hiddenViews.addAndGet(reconcileTargets());
        }

        @Override public void onViewDetachedFromWindow(View view) {
            detachObserver();
            customTargets.remove(view);
            customSignatures.remove(view);
            customResolvedCounts.remove(view);
            customRetryCounts.remove(view);
            windowProgressTargets.remove(view);
            hiddenViews.addAndGet(reconcileTargets());
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        trackWindow(activity.getWindow().getDecorView());
    }
    @Override public void onActivityStarted(Activity activity) {
        trackWindow(activity.getWindow().getDecorView());
    }
    @Override public void onActivityResumed(Activity activity) {
        trackWindow(activity.getWindow().getDecorView());
    }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    @Override public void onActivityDestroyed(Activity activity) {
        View root = activity.getWindow().getDecorView();
        WindowMonitor monitor = windowMonitors.remove(root);
        if (monitor != null) monitor.remove();
        windowRoots.remove(root);
        customTargets.remove(root);
        customSignatures.remove(root);
        customResolvedCounts.remove(root);
        customRetryCounts.remove(root);
        windowProgressTargets.remove(root);
        for (View feedRoot : new ArrayList<>(knownCards)) {
            if (feedRoot.getRootView() != root) continue;
            knownCards.remove(feedRoot);
            activeCards.remove(feedRoot);
            fixedTargets.remove(feedRoot);
            HideMonitor feedMonitor = hideMonitors.remove(feedRoot);
            if (feedMonitor != null) feedMonitor.remove();
        }
        reconcileTargets();
    }

    private static boolean matches(String idName) {
        for (String part : HIDDEN_ID_PARTS) if (idName.contains(part)) return true;
        return false;
    }

    private void restoreAll() {
        synchronized (originalStates) {
            for (Map.Entry<View, ViewState> entry : originalStates.entrySet()) {
                View view = entry.getKey();
                ViewState state = entry.getValue();
                if (view != null) restore(view, state);
            }
            originalStates.clear();
        }
    }

    private static void restore(View view, ViewState state) {
        state.observeHostState(view);
        if (view.getVisibility() != state.visibility) view.setVisibility(state.visibility);
        if (Math.abs(view.getAlpha() - state.alpha) > 0.001f) view.setAlpha(state.alpha);
        if (view.isClickable() != state.clickable) view.setClickable(state.clickable);
        if (view.isFocusable() != state.focusable) view.setFocusable(state.focusable);
    }

    private static final class ViewState {
        int visibility;
        float alpha;
        final boolean clickable;
        final boolean focusable;
        int appliedVisibility;
        float appliedAlpha;
        boolean trackComponentVisibility;
        boolean trackComponentAlpha;

        ViewState(View view) {
            visibility = view.getVisibility();
            alpha = view.getAlpha();
            clickable = view.isClickable();
            focusable = view.isFocusable();
            recordApplied(view);
        }

        void observeHostState(View view) {
            if (trackComponentVisibility && view.getVisibility() != appliedVisibility) {
                visibility = view.getVisibility();
            }
            if (trackComponentAlpha && Math.abs(view.getAlpha() - appliedAlpha) > 0.001f) {
                alpha = view.getAlpha();
            }
        }

        void recordApplied(View view) {
            appliedVisibility = view.getVisibility();
            appliedAlpha = view.getAlpha();
        }
    }

    private static final class TargetStyle {
        final int alpha;
        final boolean component;
        final TargetIdentity identity;
        final boolean trackHostAlpha;

        TargetStyle(int alpha, boolean component, View view) {
            this(alpha, component, view, false);
        }

        TargetStyle(int alpha, boolean component, View view, boolean trackHostAlpha) {
            this.alpha = alpha;
            this.component = component;
            this.trackHostAlpha = trackHostAlpha;
            identity = new TargetIdentity(view);
        }
    }

    /** Rolling checks catch changes without requiring layout events or scanning the full tree each frame. */
    private static final class ViewStructure {
        private final List<Node> nodes = new ArrayList<>();
        private int nextCheck;
        private int pendingSweepChecks;

        void capture(View root) {
            capture(root, false);
        }

        void capture(View root, boolean inspectContent) {
            clear();
            capture(root, -1, 0, inspectContent);
        }

        private void capture(View view, int index, int depth, boolean inspectContent) {
            if (view == null || nodes.size() >= MAX_TREE_NODES || depth > MAX_TREE_DEPTH || isFinderView(view)) return;
            nodes.add(new Node(view, index, inspectContent));
            if (view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int child = 0; child < group.getChildCount(); child++) {
                    capture(group.getChildAt(child), child, depth + 1, inspectContent);
                }
            }
        }

        boolean matches(AtomicLong checks) {
            if (nodes.isEmpty()) return false;
            int checked = 0;
            int stableBudget = Math.max(MIN_STABLE_STRUCTURE_CHECKS,
                    (nodes.size() + STABLE_STRUCTURE_SWEEP_FRAMES - 1) / STABLE_STRUCTURE_SWEEP_FRAMES);
            int budget = Math.min(nodes.size(), Math.min(MAX_STRUCTURE_CHECKS_PER_FRAME,
                    pendingSweepChecks > 0 ? pendingSweepChecks : stableBudget));
            try {
                while (checked < budget) {
                    Node node = nodes.get(nextCheck);
                    nextCheck = (nextCheck + 1) % nodes.size();
                    checked++;
                    if (!node.matches()) return false;
                }
                return true;
            } finally {
                pendingSweepChecks = Math.max(0, pendingSweepChecks - checked);
                checks.addAndGet(checked);
            }
        }

        void beginSweep() { pendingSweepChecks = nodes.size(); }
        boolean hasPendingSweep() { return pendingSweepChecks > 0; }
        void clear() { nodes.clear(); nextCheck = 0; pendingSweepChecks = 0; }

        private static final class Node {
            final WeakReference<View> view;
            final WeakReference<Object> parent;
            final int id;
            final int index;
            final int childCount;
            final String description;
            final String text;
            final boolean trackBackground;
            final boolean hasBackground;

            Node(View view, int index, boolean inspectContent) {
                this.view = new WeakReference<>(view);
                parent = new WeakReference<>(view.getParent());
                id = view.getId();
                this.index = index;
                childCount = view instanceof ViewGroup ? ((ViewGroup) view).getChildCount() : -1;
                description = inspectContent ? TargetIdentity.value(view.getContentDescription()) : null;
                text = inspectContent && view instanceof TextView
                        ? TargetIdentity.value(((TextView) view).getText()) : null;
                // Declaration wrappers become targets when the host binds their background later.
                trackBackground = id == View.NO_ID && view instanceof android.widget.LinearLayout;
                hasBackground = trackBackground && view.getBackground() != null;
            }

            boolean matches() {
                View current = view.get();
                Object owner = parent.get();
                if (current == null || id != current.getId() || owner != current.getParent()) return false;
                if (description != null && !description.contentEquals(current.getContentDescription() == null
                        ? "" : current.getContentDescription())) return false;
                if (text != null && !text.contentEquals(((TextView) current).getText())) return false;
                if (trackBackground && hasBackground != (current.getBackground() != null)) return false;
                if (index >= 0 && (!(owner instanceof ViewGroup)
                        || ((ViewGroup) owner).getChildAt(index) != current)) return false;
                return childCount < 0 || ((ViewGroup) current).getChildCount() == childCount;
            }
        }
    }

    private static final class TargetIdentity {
        final int id;
        final String description;
        final String text;
        final WeakReference<Object> parent;

        TargetIdentity(View view) {
            id = view.getId();
            description = value(view.getContentDescription());
            text = view instanceof TextView ? value(((TextView) view).getText()) : "";
            parent = new WeakReference<>(view.getParent());
        }

        boolean matches(View view) {
            return view != null && view.isAttachedToWindow() && id == view.getId()
                    && parent.get() == view.getParent() && same(description, view.getContentDescription())
                    && same(text, view instanceof TextView ? ((TextView) view).getText() : "");
        }

        private static boolean same(String expected, CharSequence value) {
            return expected.contentEquals(value == null ? "" : value);
        }

        private static String value(CharSequence value) { return value == null ? "" : value.toString(); }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        enabled = false;
        if (shortProgress != null) shortProgress.close();
        if (application != null) application.unregisterActivityLifecycleCallbacks(this);
        mainHandler.post(() -> {
            cancelProgressDiagnostics();
            removeAllHideMonitors();
            for (WindowMonitor monitor : windowMonitors.values()) monitor.remove();
            windowMonitors.clear();
            fixedTargets.clear();
            windowProgressTargets.clear();
            customTargets.clear();
            customSignatures.clear();
            customResolvedCounts.clear();
            customRetryCounts.clear();
            restoreAll();
            activeCards.clear();
            knownCards.clear();
            windowRoots.clear();
            selectedRoot.clear();
            selectedHolder.clear();
            selectedAid = null;
            previousAid = null;
            clearPendingPlayback();
            visibilityListener = ignored -> { };
        });
        unhookAll(null);
    }

    private void unhookAll(Throwable originalFailure) {
        Throwable failure = originalFailure;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        handles.clear();
        if (originalFailure == null && failure != null) safeLog("Feed UI unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not affect the host view lifecycle.
        }
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        String className = candidate.getClassDescriptor().substring(1,
                candidate.getClassDescriptor().length() - 1).replace('/', '.');
        Class<?> owner = Class.forName(className, false, loader);
        for (Method method : owner.getDeclaredMethods()) {
            if (method.getName().equals(candidate.getMethodName())
                    && methodDescriptor(method).equals(candidate.getMethodDescriptor())) return method;
        }
        throw new NoSuchMethodException("Scanned host method is unavailable: " + ruleId);
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String classDescriptor(Class<?> type) {
        return "L" + type.getName().replace('.', '/') + ";";
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
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
}
