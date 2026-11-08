package com.douai.module.feature;

import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewTreeObserver;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.ConcurrentModificationException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Verified host UI gates and owned view visibility, independent of the host's account state. */
public final class HostBehaviorHooks implements AutoCloseable {
    private static final String HIDDEN_SKIP = "prevent_skip_hidden";
    private static final int MAX_VIEW_UPDATES_PER_FRAME = 64;
    private static final long MAX_VIEW_UPDATE_NANOS_PER_FRAME = 4_000_000L;
    private final Map<String, BehaviorHookGroup> groups = new LinkedHashMap<>();
    private final Map<View, HiddenView> hidden = new WeakHashMap<>();
    private final Map<View, String> observed = new WeakHashMap<>();
    private final Map<String, Map<View, Boolean>> observedByKey = new LinkedHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Consumer<String> log;
    private final AtomicLong hiddenCurrentHits = new AtomicLong();
    private final AtomicLong hiddenCurrentBlocked = new AtomicLong();
    private final AtomicLong hiddenBackgroundHits = new AtomicLong();
    private final AtomicLong hiddenBackgroundBlocked = new AtomicLong();
    private final AtomicLong observedRefreshGeneration = new AtomicLong();
    private final AtomicBoolean hiddenCurrentLogged = new AtomicBoolean();
    private final AtomicBoolean hiddenBackgroundLogged = new AtomicBoolean();
    private volatile boolean hiddenSkipReady;
    private volatile LocalOptions options;
    private volatile boolean closed;

    private HostBehaviorHooks(LocalOptions options, Consumer<String> log) {
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
    }

    public static HostBehaviorHooks install(XposedInterface framework, HostScanReport report,
                                             ClassLoader loader, Context context,
                                             LocalOptions options, Consumer<String> log) {
        return installWithHiddenTaskContract(framework, report, loader, context, options, log, null);
    }

    enum HiddenSafetyTaskContract {
        HOST_406(0, null, null), HOST_408(0, null, null), HOST_410(2, 3, null);
        final int currentBranch;
        final Integer secondaryCurrentBranch;
        final Integer backgroundBranch;
        HiddenSafetyTaskContract(int currentBranch, Integer secondaryCurrentBranch, Integer backgroundBranch) {
            this.currentBranch = currentBranch;
            this.secondaryCurrentBranch = secondaryCurrentBranch;
            this.backgroundBranch = backgroundBranch;
        }
        boolean blocksCurrentBranch(int branch) {
            return branch == currentBranch || secondaryCurrentBranch != null && branch == secondaryCurrentBranch;
        }
        static HiddenSafetyTaskContract resolve(String current, String background) {
            if ("Lkotlin/jvm/internal/ALambdaS32S1500000_4;".equals(current) && "LX/06ht;".equals(background)) return HOST_406;
            if ("Lkotlin/jvm/internal/ALambdaS38S1500000_35;".equals(current)
                    && "LX/12bW;".equals(background)) return HOST_408;
            if ("Lkotlin/jvm/internal/ALambdaS43S1500000_47;".equals(current)
                    && "LX/1R3E;".equals(background)) return HOST_410;
            throw new IllegalStateException("Hidden video safety task profile combination changed");
        }
    }

    /** Package-local seam replays either proven contract with JVM fixture task classes. */
    static HostBehaviorHooks installWithHiddenTaskContract(XposedInterface framework, HostScanReport report,
                                                            ClassLoader loader, Context context,
                                                            LocalOptions options, Consumer<String> log,
                                                            HiddenSafetyTaskContract hiddenContract) {
        HostBehaviorHooks hooks = new HostBehaviorHooks(options, log);
        hooks.suppress(framework, report, loader, "remove_splash_ads", false,
                "adv_ui_splash_gate");
        hooks.suppress(framework, report, loader, "remove_teen_dialog", false,
                "adv_ui_teen_guide");
        hooks.installUpdates(framework, report, loader);
        hooks.suppress(framework, report, loader, "disable_double_tap_like", null,
                "adv_ui_double_tap", "adv_ui_big_digg");
        hooks.installHiddenSkip(framework, report, loader, hiddenContract);
        hooks.suppress(framework, report, loader, "prevent_insert_video", null,
                "adv_ui_insert_single", "adv_ui_insert_single_fallback",
                "adv_ui_insert_list", "adv_ui_insert_list_fallback");
        hooks.suppress(framework, report, loader, "block_long_press_speed", null,
                "adv_ui_long_press_speed");
        hooks.installBlackEdges(framework, report, loader);
        hooks.installPublishButton(framework, report, loader);
        return hooks;
    }

    public boolean supports(String key) {
        BehaviorHookGroup group = groups.get(key);
        return group != null && group.isActive() && (!HIDDEN_SKIP.equals(key) || hiddenSkipReady);
    }

    /** Aggregate route counters contain no work IDs, provider data, content, or URLs. */
    public void appendHiddenSkipDiagnostics(Bundle snapshot) {
        if (snapshot == null) return;
        LocalOptions current = options;
        BehaviorHookGroup group = groups.get(HIDDEN_SKIP);
        snapshot.putBoolean("hidden_skip_enabled", !closed && current != null && current.enabled(HIDDEN_SKIP));
        snapshot.putBoolean("hidden_skip_installed", supports(HIDDEN_SKIP));
        snapshot.putLong("hidden_skip_current_hits", hiddenCurrentHits.get());
        snapshot.putLong("hidden_skip_current_blocked", hiddenCurrentBlocked.get());
        snapshot.putLong("hidden_skip_background_hits", hiddenBackgroundHits.get());
        snapshot.putLong("hidden_skip_background_blocked", hiddenBackgroundBlocked.get());
        snapshot.putString("hidden_skip_failure", group == null ? "" : group.installationFailure());
    }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        LocalOptions previous = this.options;
        this.options = options;
        java.util.Set<String> changedKeys = new java.util.LinkedHashSet<>();
        for (String key : new String[]{"hide_video_black_edges", "hide_camera_plus"}) {
            if (previous == null || options == null || previous.enabled(key) != options.enabled(key)) {
                changedKeys.add(key);
            }
        }
        // Settings sync republishes full snapshots during startup. The LSPosed trace showed the
        // weak-map snapshot itself occupying the main thread when none of these gates changed.
        if (changedKeys.isEmpty()) return;
        long generation = observedRefreshGeneration.incrementAndGet();
        onMain(() -> refreshObservedViews(generation, changedKeys));
    }

    private void refreshObservedViews(long generation, java.util.Set<String> changedKeys) {
        if (closed || generation != observedRefreshGeneration.get()) return;
        for (String key : changedKeys) {
            Map<View, Boolean> views = observedByKey.get(key);
            if (views != null) refreshObservedBatch(views.entrySet().iterator(), key, generation, changedKeys);
        }
    }

    private void refreshObservedBatch(Iterator<Map.Entry<View, Boolean>> iterator, String key,
                                     long generation, java.util.Set<String> changedKeys) {
        if (closed || generation != observedRefreshGeneration.get()) return;
        long started = android.os.SystemClock.uptimeMillis();
        int count = 0;
        try {
            while (count < MAX_VIEW_UPDATES_PER_FRAME
                    && android.os.SystemClock.uptimeMillis() - started < MAX_VIEW_UPDATE_NANOS_PER_FRAME / 1_000_000L
                    && iterator.hasNext()) {
                Map.Entry<View, Boolean> entry = iterator.next();
                updateView(entry.getKey(), key);
                count++;
            }
            if (iterator.hasNext()) main.postDelayed(
                    () -> refreshObservedBatch(iterator, key, generation, changedKeys), 16L);
        } catch (ConcurrentModificationException changed) {
            main.postDelayed(() -> refreshObservedViews(generation, changedKeys), 16L);
        }
    }

    private boolean enabled(String key) {
        LocalOptions current = options;
        return !closed && current != null && current.enabled(key) && supports(key);
    }

    private void suppress(XposedInterface framework, HostScanReport report, ClassLoader loader,
                          String key, Object skippedResult, String... rules) {
        BehaviorHookGroup group = newGroup(key);
        try {
            ArrayList<Method> targets = new ArrayList<>();
            for (String rule : rules) targets.add(HostTargets.method(report, rule, loader));
            for (Method target : targets) {
                boolean returnsBoolean = target.getReturnType() == boolean.class;
                if ((returnsBoolean && !(skippedResult instanceof Boolean))
                        || (!returnsBoolean && target.getReturnType() != void.class)) {
                    throw new IllegalStateException("Suppression contract changed: " + target);
                }
                group.add(framework, target, chain -> {
                    boolean apply = enabled(key);
                    DouAiLog.event(key, "decision", apply ? "enabled_host_call_blocked" : "disabled_host_call_forwarded",
                            "host_invoked=" + !apply + ";result_scope=local_interception");
                    if (apply) {
                        DouAiLog.Operation operation = DouAiLog.begin(key, "host_gate");
                        operation.finish(DouAiLog.Outcome.SUCCESS, "host_call_suppressed",
                                "host_invoked=false;result_scope=local_interception;durable_effect=unobserved");
                        return skippedResult;
                    }
                    return chain.proceed();
                });
            }
            reportInstalled(key, targets.size());
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installHiddenSkip(XposedInterface framework, HostScanReport report, ClassLoader loader,
                                   HiddenSafetyTaskContract fixtureContract) {
        BehaviorHookGroup group = newGroup(HIDDEN_SKIP);
        try {
            Method baseDelete = HostTargets.method(report, "adv_ui_delete_aweme", loader);
            Method fullDelete = HostTargets.method(report, "adv_ui_delete_aweme_full_feed", loader);
            Method currentTask = HostTargets.method(report, "adv_ui_hidden_recall_task", loader);
            Method backgroundTask = HostTargets.method(report, "adv_ui_hidden_background_task", loader);
            HiddenSafetyTaskContract contract = fixtureContract != null ? fixtureContract
                    : HiddenSafetyTaskContract.resolve(
                            HostTargets.candidate(report, "adv_ui_hidden_recall_task").getClassDescriptor(),
                            HostTargets.candidate(report, "adv_ui_hidden_background_task").getClassDescriptor());
            for (Method target : new Method[]{baseDelete, fullDelete}) {
                Class<?>[] parameters = target.getParameterTypes();
                if (Modifier.isStatic(target.getModifiers()) || target.getReturnType() != void.class
                        || parameters.length != 2 || parameters[0] != int.class || parameters[1].isPrimitive()) {
                    throw new IllegalStateException("Hidden video legacy removal contract changed");
                }
            }
            for (Method target : new Method[]{currentTask, backgroundTask}) {
                if (Modifier.isStatic(target.getModifiers()) || target.getParameterCount() != 0
                        || target.getReturnType() != Object.class || !"invoke".equals(target.getName())) {
                    throw new IllegalStateException("Hidden video safety task contract changed");
                }
            }
            Field discriminator = currentTask.getDeclaringClass().getDeclaredField("$t");
            Field backgroundDiscriminator = null;
            if (contract.backgroundBranch != null) {
                backgroundDiscriminator = backgroundTask.getDeclaringClass().getDeclaredField("$t");
            } else {
                for (Field field : backgroundTask.getDeclaringClass().getDeclaredFields()) {
                    if ("$t".equals(field.getName())) throw new IllegalStateException("Dedicated safety task unexpectedly has a discriminator");
                }
            }
            for (Field field : new Field[]{discriminator, backgroundDiscriminator}) {
                if (field == null) continue;
                if (field.getType() != int.class || Modifier.isStatic(field.getModifiers())) {
                    throw new IllegalStateException("Hidden video safety task discriminator changed");
                }
                field.setAccessible(true);
            }
            final Field backgroundBranchField = backgroundDiscriminator;
            Class<?> unitType = Class.forName("kotlin.Unit", false, loader);
            Field instance = unitType.getField("INSTANCE");
            if (!Modifier.isStatic(instance.getModifiers()) || instance.getType() != unitType) {
                throw new IllegalStateException("Host Kotlin Unit contract changed");
            }
            Object hostUnit = instance.get(null);
            if (!unitType.isInstance(hostUnit)) throw new IllegalStateException("Host Kotlin Unit instance unavailable");

            // Ready remains false until all four handles exist, including during partial installation.
            group.add(framework, baseDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());
            group.add(framework, fullDelete, chain -> enabled(HIDDEN_SKIP) ? null : chain.proceed());
            group.add(framework, currentTask, chain -> {
                if (closed || !hiddenSkipReady || !group.isActive()) return chain.proceed();
                int branch;
                try { branch = discriminator.getInt(chain.getThisObject()); }
                catch (ReflectiveOperationException | RuntimeException failure) {
                    hiddenSkipReady = false;
                    group.fail(failure);
                    return chain.proceed();
                }
                if (!contract.blocksCurrentBranch(branch)) return chain.proceed();
                hiddenCurrentHits.incrementAndGet();
                if (!enabled(HIDDEN_SKIP)) return chain.proceed();
                hiddenCurrentBlocked.incrementAndGet();
                logHiddenSkipOnce(hiddenCurrentLogged, "current");
                return hostUnit;
            });
            group.add(framework, backgroundTask, chain -> {
                if (closed || !hiddenSkipReady || !group.isActive()) return chain.proceed();
                if (backgroundBranchField != null) {
                    int branch;
                    try { branch = backgroundBranchField.getInt(chain.getThisObject()); }
                    catch (ReflectiveOperationException | RuntimeException failure) {
                        hiddenSkipReady = false;
                        group.fail(failure);
                        return chain.proceed();
                    }
                    if (branch != contract.backgroundBranch) return chain.proceed();
                }
                hiddenBackgroundHits.incrementAndGet();
                if (!enabled(HIDDEN_SKIP)) return chain.proceed();
                hiddenBackgroundBlocked.incrementAndGet();
                logHiddenSkipOnce(hiddenBackgroundLogged, "background");
                return hostUnit;
            });
            hiddenSkipReady = true;
            reportInstalled(HIDDEN_SKIP, 4);
        } catch (Throwable failure) {
            hiddenSkipReady = false;
            group.fail(failure);
        }
    }

    private void logHiddenSkipOnce(AtomicBoolean logged, String route) {
        long count = "current".equals(route) ? hiddenCurrentBlocked.get() : hiddenBackgroundBlocked.get();
        if (count == 1 || (count & (count - 1)) == 0) DouAiLog.event(HIDDEN_SKIP, "decision", "safety_task_suppressed",
                "route=" + route + ";current_hits=" + hiddenCurrentHits.get()
                        + ";current_blocked=" + hiddenCurrentBlocked.get()
                        + ";background_hits=" + hiddenBackgroundHits.get()
                        + ";background_blocked=" + hiddenBackgroundBlocked.get() + ";result_scope=local_interception");
        if (!logged.compareAndSet(false, true)) return;
        try { log.accept("Hidden video auto-skip blocked: route=" + route); }
        catch (Throwable ignored) { }
    }

    private void installUpdates(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "remove_update_dialog";
        BehaviorHookGroup group = newGroup(key);
        try {
            Class<?> notification = HostTargets.type(report, "adv_ui_update_dialog", loader);
            Class<?> bottom = HostTargets.type(report, "adv_ui_update_bottom", loader);
            if (!Dialog.class.isAssignableFrom(notification)) {
                throw new IllegalStateException("Update notification is no longer a Dialog");
            }
            group.add(framework, Dialog.class.getDeclaredMethod("show"), chain -> {
                if (enabled(key) && notification.isInstance(chain.getThisObject())) {
                    DouAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=notification");
                    return null;
                }
                return chain.proceed();
            });
            Class<?> fragment = Class.forName("androidx.fragment.app.DialogFragment", false, loader);
            Class<?> manager = Class.forName("androidx.fragment.app.FragmentManager", false, loader);
            Class<?> transaction = Class.forName("androidx.fragment.app.FragmentTransaction", false, loader);
            if (!fragment.isAssignableFrom(bottom)) {
                throw new IllegalStateException("Update bottom sheet is no longer a DialogFragment");
            }
            for (String name : new String[] { "show", "showNow" }) {
                group.add(framework, fragment.getDeclaredMethod(name, manager, String.class), chain -> {
                    if (enabled(key) && bottom.isInstance(chain.getThisObject())) {
                        DouAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=bottom_sheet");
                        return null;
                    }
                    return chain.proceed();
                });
            }
            group.add(framework, fragment.getDeclaredMethod("show", transaction, String.class), chain -> {
                if (enabled(key) && bottom.isInstance(chain.getThisObject())) {
                    DouAiLog.event(key, "decision", "dialog_show_suppressed", "host_invoked=false;dialog_kind=transaction");
                    return -1;
                }
                return chain.proceed();
            });
            reportInstalled(key, 4);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installBlackEdges(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_video_black_edges";
        BehaviorHookGroup group = newGroup(key);
        try {
            Method method = HostTargets.method(report, "adv_ui_black_spaces", loader);
            Field top = HostTargets.field(method.getDeclaringClass(), "i");
            Field bottom = HostTargets.field(method.getDeclaringClass(), "j");
            if (!View.class.isAssignableFrom(top.getType())
                    || !View.class.isAssignableFrom(bottom.getType())) {
                throw new IllegalStateException("Feed black-space field types changed");
            }
            group.add(framework, method, chain -> {
                Object result = chain.proceed();
                if (closed || !group.isActive()) return result;
                try {
                    remember((View) top.get(chain.getThisObject()), key);
                    remember((View) bottom.get(chain.getThisObject()), key);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    rollbackViews(key);
                    group.fail(failure);
                }
                return result;
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private void installPublishButton(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        String key = "hide_camera_plus";
        BehaviorHookGroup group = newGroup(key);
        try {
            Method attach = HostTargets.method(report, "adv_ui_publish_attach", loader);
            if (!View.class.isAssignableFrom(attach.getDeclaringClass())) {
                throw new IllegalStateException("Publish button is no longer a View");
            }
            group.add(framework, attach, chain -> {
                Object result = chain.proceed();
                if (!closed && group.isActive()) {
                    try { remember((View) chain.getThisObject(), key); }
                    catch (RuntimeException failure) {
                        rollbackViews(key);
                        group.fail(failure);
                    }
                }
                return result;
            });
            reportInstalled(key, 1);
        } catch (Throwable failure) { group.fail(failure); }
    }

    private BehaviorHookGroup newGroup(String key) {
        BehaviorHookGroup group = new BehaviorHookGroup(key, log);
        groups.put(key, group);
        return group;
    }

    private void reportInstalled(String key, int count) {
        try { log.accept("Advanced feature registered: " + key + " targets=" + count); }
        catch (Throwable ignored) { }
    }

    private void remember(View view, String key) {
        if (view == null) return;
        onMain(() -> {
            if (closed) return;
            observed.put(view, key);
            observedByKey.computeIfAbsent(key, ignored -> new WeakHashMap<>()).put(view, Boolean.TRUE);
            updateView(view, key);
        });
    }

    private void updateView(View view, String key) {
        if (view == null) return;
        HiddenView state = hidden.get(view);
        if (!enabled(key)) {
            if (state != null) {
                hidden.remove(view);
                state.restore();
            }
            return;
        }
        if (state == null) {
            state = new HiddenView(view, key, "hide_camera_plus".equals(key) ? View.INVISIBLE : View.GONE);
            hidden.put(view, state);
        }
        state.apply();
    }

    private void rollbackViews(String key) {
        onMain(() -> {
            for (Map.Entry<View, String> entry : new ArrayList<>(observed.entrySet())) {
                if (!key.equals(entry.getValue())) continue;
                HiddenView state = hidden.remove(entry.getKey());
                if (state != null) state.restore();
                observed.remove(entry.getKey());
            }
            observedByKey.remove(key);
        });
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hiddenSkipReady = false;
        for (BehaviorHookGroup group : groups.values()) group.close();
        onMain(() -> {
            for (HiddenView state : new ArrayList<>(hidden.values())) state.restore();
            hidden.clear();
            observed.clear();
            observedByKey.clear();
        });
    }

    private final class HiddenView implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> view;
        private final String key;
        private final int applied;
        private int original;
        private ViewTreeObserver observer;

        HiddenView(View view, String key, int applied) {
            this.view = new WeakReference<>(view);
            this.key = key;
            this.applied = applied;
            original = view.getVisibility();
            observer = view.getViewTreeObserver();
            if (observer.isAlive()) observer.addOnPreDrawListener(this);
        }

        void apply() {
            View target = view.get();
            if (target == null) return;
            ViewTreeObserver next = target.getViewTreeObserver();
            if (next != observer && next.isAlive()) {
                if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
                observer = next;
                observer.addOnPreDrawListener(this);
            }
            if (target.getVisibility() != applied) {
                original = target.getVisibility();
                target.setVisibility(applied);
                DouAiLog.event(key, "view_state", "visibility_applied", "expected=" + applied
                        + ";actual=" + target.getVisibility() + ";result_scope=current_view");
            }
        }

        @Override public boolean onPreDraw() {
            try {
                if (!enabled(key)) {
                    View target = view.get();
                    if (target != null) hidden.remove(target);
                    restore();
                } else apply();
            } catch (RuntimeException failure) {
                BehaviorHookGroup group = groups.get(key);
                if (group != null) group.fail(failure);
                rollbackViews(key);
            }
            return true;
        }

        void restore() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
            View target = view.get();
            if (target != null && target.getVisibility() == applied) target.setVisibility(original);
            if (target != null) DouAiLog.event(key, "view_state", "restored",
                    "expected=" + original + ";actual=" + target.getVisibility() + ";result_scope=current_view");
        }
    }
}
