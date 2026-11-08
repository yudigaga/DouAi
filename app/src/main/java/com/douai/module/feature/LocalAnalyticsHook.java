package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewTreeObserver;

import com.douai.module.analytics.LocalAnalytics;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import io.github.libxposed.api.XposedInterface;

/** Local-only observations of selected, visible cards; bind/preload events are never hooked. */
public final class LocalAnalyticsHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private final Application application;
    private static final long HEARTBEAT_MILLIS = 750;
    private static final long MAX_OBSERVED_GAP_MILLIS = 1500;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<BehaviorHookGroup> groups = new ArrayList<>();
    private final Consumer<String> log;
    private final String itemParamsField;
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private volatile boolean closed;
    private volatile long lifecycleEpoch;
    private Visit currentVisit;
    // One weak selected-card candidate survives a covered window/background interval. No background polling.
    private Visit resumeCandidate;
    private WeakReference<View> candidateRoot = new WeakReference<>(null);
    private WeakReference<ViewTreeObserver> candidateObserver = new WeakReference<>(null);
    private final Runnable heartbeat = this::observe;
    private final ViewTreeObserver.OnWindowFocusChangeListener focusListener = focused -> {
        if (!focused) finish("window_covered", false);
        else scheduleCandidateResume();
    };
    private final ViewTreeObserver.OnGlobalLayoutListener layoutListener = this::scheduleCandidateResume;
    private final View.OnAttachStateChangeListener attachmentListener = new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) { }
        @Override public void onViewDetachedFromWindow(View view) {
            if (candidateRoot.get() != view) return;
            finish("detached", false);
            discardCandidate();
        }
    };

    private LocalAnalyticsHook(Application application, Consumer<String> log) {
        this.application = application;
        this.log = log;
        this.itemParamsField = HostResourceProfile.feedItemParamsField(HostAdaptation.currentProfile());
    }

    public static LocalAnalyticsHook install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, Context context,
                                             Consumer<String> log) throws Exception {
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalStateException("Host Application unavailable");
        LocalAnalyticsHook hook = new LocalAnalyticsHook((Application) app, log == null ? ignored -> { } : log);
        try {
            hook.installHolder(framework, report, loader, "feed_holder", "w");
            hook.installHolder(framework, report, loader, "image_holder", "f");
            if (hook.groups.isEmpty()) throw new IllegalStateException("No selected-card analytics target available");
            hook.application.registerActivityLifecycleCallbacks(hook);
            return hook;
        } catch (Exception failure) {
            hook.close();
            throw failure;
        }
    }

    private void installHolder(XposedInterface framework, HostScanReport report, ClassLoader loader,
                               String prefix, String rootName) {
        BehaviorHookGroup group = new BehaviorHookGroup("local_analytics_" + prefix, log);
        try {
            Method selected = HostTargets.method(report, prefix + "_page_selected", loader);
            Class<?> owner = selected.getDeclaringClass();
            if (HostTargets.type(report, prefix + "_main_root", loader) != owner
                    || selected.getParameterCount() != 0 || selected.getReturnType() != void.class) {
                throw new IllegalStateException("Selected-card analytics contract changed");
            }
            Field root = HostTargets.field(owner, rootName);
            Method aweme = owner.getMethod("getAweme");
            if (!View.class.isAssignableFrom(root.getType()) || aweme.getReturnType().isPrimitive()) {
                throw new IllegalStateException("Selected-card metadata contract changed");
            }
            aweme.setAccessible(true);
            List<Method> callbacks = new ArrayList<>();
            callbacks.add(selected);
            if ("feed_holder".equals(prefix)) {
                // Both genuine selection notifications can occur for one item; the store deduplicates them.
                Method holderSelected = HostTargets.method(report, "feed_holder_selected", loader);
                if (holderSelected.getDeclaringClass() != owner || holderSelected.getParameterCount() != 1
                        || holderSelected.getParameterTypes()[0] != int.class) {
                    throw new IllegalStateException("Selected-card callback relationship changed");
                }
                callbacks.add(holderSelected);
            }
            for (Method callback : callbacks) group.add(framework, callback, chain -> {
                Object result = chain.proceed();
                if (!closed) selected(chain.getThisObject(), root, aweme);
                return result;
            });
            groups.add(group);
        } catch (Exception failure) {
            group.close();
            try { log.accept("Local analytics " + prefix + " unavailable: " + failure.getClass().getSimpleName()); }
            catch (RuntimeException ignored) { }
        }
    }

    private void selected(Object holder, Field root, Method aweme) {
        try {
            LocalAnalytics analytics = LocalAnalytics.get(application);
            if (!analytics.isEnabled()) {
                DouAiLog.event("local_analytics", "decision", "recording_disabled", "visit_started=false");
                return;
            }
            long recordingGeneration = analytics.generation();
            // Snapshot the ID at callback time, before a posted callback can observe a recycled holder.
            Object work = aweme.invoke(holder);
            Object rawId = AnalyticsAwemeReader.value(work, "getAid", "aid", "aweme_id");
            if (!(rawId instanceof String) || ((String) rawId).isEmpty()) return;
            String expectedId = (String) rawId;
            long epoch = lifecycleEpoch;
            WeakReference<Object> selected = new WeakReference<>(holder);
            main.post(() -> recordSelected(selected.get(), root, aweme, expectedId, epoch, analytics, recordingGeneration, false));
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private void recordSelected(Object holder, Field rootField, Method aweme, String expectedId, long epoch,
                                LocalAnalytics analytics, long recordingGeneration, boolean resumedSegment) {
        if (closed || !analytics.isEnabled() || analytics.generation() != recordingGeneration
                || LocalAnalytics.get(application) != analytics || lifecycleEpoch != epoch || holder == null) return;
        try {
            View root = (View) rootField.get(holder);
            if (!visible(root, resumed.get())) return;
            // Video holders expose the current panel selection. Reject stale neighbor holders even if attached.
            Object params = HostTargets.read(holder, itemParamsField);
            Object panel = HostTargets.read(params, "mFeedContext");
            if (panel != null && HostTargets.invoke(panel, "getCurrentViewHolderP") != holder) return;
            Object work = aweme.invoke(holder);
            Object actualId = AnalyticsAwemeReader.value(work, "getAid", "aid", "aweme_id");
            if (!expectedId.equals(actualId)) return;
            Visit existing = currentVisit;
            // Some host paths fire both selection callbacks. They describe one uninterrupted visit.
            if (existing != null && existing.store == analytics && existing.generation == recordingGeneration
                    && existing.holder.get() == holder && existing.id.equals(expectedId)) return;
            AnalyticsAwemeReader.Metadata metadata = AnalyticsAwemeReader.read(work);
            if (metadata == null) return;
            finish("selection_changed", false);
            discardCandidate();
            String token = analytics.beginVisit(recordingGeneration, metadata.id, metadata.type, metadata.authorId,
                    metadata.authorName, metadata.topics, metadata.advertisement, metadata.duration,
                    resumedSegment ? "unknown" : LocalAnalytics.consumeAdvanceSource());
            if (token == null) return;
            currentVisit = new Visit(analytics, recordingGeneration, token, expectedId, holder, rootField, aweme);
            currentVisit.operation.stage("visible_visit", "work_ref=" + DouAiLog.reference(expectedId)
                    + ";recording_generation=" + recordingGeneration + ";visibility_validated=true");
            retainCandidate(currentVisit, root);
            main.postDelayed(heartbeat, HEARTBEAT_MILLIS);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Host model drift must never affect feed behavior or expose user metadata in logs.
        }
    }

    /** A validated visible interval is an observation, never a claim that video playback completed. */
    private void observe() {
        Visit visit = currentVisit;
        if (visit == null || closed) return;
        if (!visit.store.isEnabled() || visit.store.generation() != visit.generation
                || LocalAnalytics.get(application) != visit.store) {
            finish("recording_changed", false);
            discardCandidate();
            return;
        }
        if (!stillVisible(visit)) {
            finish("not_visible", false);
            if (!sameSelectedCard(visit)) discardCandidate();
            return;
        }
        addObservedInterval(visit);
        visit.store.pulseVisit(visit.token, visit.visibleMillis);
        main.postDelayed(heartbeat, HEARTBEAT_MILLIS);
    }

    private boolean stillVisible(Visit visit) {
        if (!sameSelectedCard(visit)) return false;
        Object holder = visit.holder.get();
        if (holder == null) return false;
        try {
            return visible((View) visit.root.get(holder), resumed.get());
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private boolean sameSelectedCard(Visit visit) {
        Object holder = visit.holder.get();
        if (holder == null) return false;
        try {
            View root = (View) visit.root.get(holder);
            if (root == null || !root.isAttachedToWindow() || candidateRoot.get() != root) return false;
            Object panel = HostTargets.read(HostTargets.read(holder, itemParamsField), "mFeedContext");
            if (panel != null && HostTargets.invoke(panel, "getCurrentViewHolderP") != holder) return false;
            return visit.id.equals(AnalyticsAwemeReader.value(visit.aweme.invoke(holder), "getAid", "aid", "aweme_id"));
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private void retainCandidate(Visit visit, View root) {
        resumeCandidate = visit;
        candidateRoot = new WeakReference<>(root);
        ViewTreeObserver observer = root.getViewTreeObserver();
        candidateObserver = new WeakReference<>(observer);
        observer.addOnWindowFocusChangeListener(focusListener);
        observer.addOnGlobalLayoutListener(layoutListener);
        root.addOnAttachStateChangeListener(attachmentListener);
    }

    private void discardCandidate() {
        resumeCandidate = null;
        View root = candidateRoot.get();
        candidateRoot.clear();
        if (root != null) root.removeOnAttachStateChangeListener(attachmentListener);
        ViewTreeObserver observer = candidateObserver.get();
        candidateObserver.clear();
        removeCandidateListeners(observer);
        // Android can merge/replace a tree observer during window detachment.
        if (root != null && root.getViewTreeObserver() != observer) removeCandidateListeners(root.getViewTreeObserver());
    }

    private void removeCandidateListeners(ViewTreeObserver observer) {
        if (observer != null && observer.isAlive()) {
            observer.removeOnWindowFocusChangeListener(focusListener);
            observer.removeOnGlobalLayoutListener(layoutListener);
        }
    }

    private void scheduleCandidateResume() {
        Visit candidate = resumeCandidate;
        if (closed || currentVisit != null || candidate == null || resumed.get() == null) return;
        long epoch = lifecycleEpoch;
        main.post(() -> {
            if (closed || currentVisit != null || resumeCandidate != candidate || lifecycleEpoch != epoch) return;
            if (!candidate.store.isEnabled() || candidate.store.generation() != candidate.generation
                    || LocalAnalytics.get(application) != candidate.store || !sameSelectedCard(candidate)) {
                discardCandidate();
                return;
            }
            recordSelected(candidate.holder.get(), candidate.root, candidate.aweme, candidate.id,
                    epoch, candidate.store, candidate.generation, true);
        });
    }

    static long observedInterval(long previous, long now) {
        long elapsed = now - previous;
        // Sleep or a stalled main loop cannot establish continuous visibility. Discard the gap.
        return elapsed > 0 && elapsed <= MAX_OBSERVED_GAP_MILLIS ? elapsed : 0;
    }

    private void addObservedInterval(Visit visit) {
        long now = SystemClock.elapsedRealtime();
        visit.visibleMillis += observedInterval(visit.lastObserved, now);
        visit.lastObserved = now;
    }

    private void finish(String reason, boolean includeLastInterval) {
        main.removeCallbacks(heartbeat);
        Visit visit = currentVisit;
        currentVisit = null;
        if (visit == null) return;
        if (includeLastInterval && stillVisible(visit)) addObservedInterval(visit);
        visit.store.finishVisit(visit.token, visit.visibleMillis, reason);
        visit.operation.finish(DouAiLog.Outcome.UNCONFIRMED, "visit_submitted_to_local_store",
                "end_reason=" + reason + ";visible_ms=" + visit.visibleMillis
                        + ";store_call_returned=true;durable_storage_readback=false;playback_completion=not_inferred");
    }

    private static final class Visit {
        final LocalAnalytics store;
        final long generation;
        final String token, id;
        final WeakReference<Object> holder;
        final Field root;
        final Method aweme;
        final DouAiLog.Operation operation = DouAiLog.begin("local_analytics", "visible_selection");
        long lastObserved = SystemClock.elapsedRealtime();
        long visibleMillis;

        Visit(LocalAnalytics store, long generation, String token, String id,
              Object holder, Field root, Method aweme) {
            this.store = store; this.generation = generation; this.token = token; this.id = id;
            this.holder = new WeakReference<>(holder); this.root = root; this.aweme = aweme;
        }
    }

    static boolean visible(View root, Activity foreground) {
        if (root == null || foreground == null || foreground.isFinishing() || foreground.isDestroyed()
                || activity(root.getContext()) != foreground || !root.isAttachedToWindow() || !root.isShown()
                || root.getWindowVisibility() != View.VISIBLE || !root.hasWindowFocus()) return false;
        Rect shown = new Rect();
        return root.getGlobalVisibleRect(shown) && shown.width() > 0 && shown.height() > 0;
    }

    private static Activity activity(Context context) {
        for (int depth = 0; context instanceof ContextWrapper && depth < 12; depth++) {
            if (context instanceof Activity) return (Activity) context;
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return null;
    }

    @Override public void onActivityResumed(Activity activity) {
        if (!closed) {
            if (resumed.get() != null && resumed.get() != activity) finish("activity_changed", false);
            resumed = new WeakReference<>(activity);
            scheduleCandidateResume();
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() == activity) {
            finish("background", true);
            resumed.clear(); lifecycleEpoch++;
        }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        onActivityPaused(activity);
        View root = candidateRoot.get();
        if (root != null && activity(root.getContext()) == activity) discardCandidate();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        finish("closed", true);
        discardCandidate();
        closed = true;
        lifecycleEpoch++;
        resumed.clear();
        main.removeCallbacksAndMessages(null);
        application.unregisterActivityLifecycleCallbacks(this);
        for (BehaviorHookGroup group : groups) group.close();
        groups.clear();
    }
}
