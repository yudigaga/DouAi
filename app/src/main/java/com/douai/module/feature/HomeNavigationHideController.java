package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;

import com.douai.module.config.ComponentHideSettings;
import com.douai.module.config.ComponentHideSettings.Component;
import com.douai.module.config.ExtendedSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Only the home tab containers are eligible; feed/media roots are never navigation targets. */
public final class HomeNavigationHideController implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private static final String HOME_ACTIVITY = "com.ss.android.ugc.aweme.main.MainActivity";
    private static final String TOP_CLASS = "com.ss.android.ugc.aweme.homepage.tab.top.ui.titlebar.MainTitleBar";
    private final String bottomClassName;
    private final Application application;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, Monitor> activities = new WeakHashMap<>();
    private volatile ComponentHideSettings settings;
    private volatile boolean temporarilyShown;
    private volatile boolean closed;

    private HomeNavigationHideController(Application application, ExtendedSettings settings,
                                         Class<?> bottomClass, Consumer<String> log) {
        this.application = application;
        this.bottomClassName = bottomClass.getName();
        this.settings = settings == null ? null : settings.components;
        this.log = log == null ? ignored -> { } : log;
        application.registerActivityLifecycleCallbacks(this);
    }

    public static HomeNavigationHideController install(Context context, ExtendedSettings settings,
                                                       Class<?> topClass, Class<?> bottomClass,
                                                       Consumer<String> log) {
        if (!(context.getApplicationContext() instanceof Application)) {
            throw new IllegalStateException("Host Application unavailable");
        }
        if (topClass == null || bottomClass == null || !TOP_CLASS.equals(topClass.getName())
                || topClass == bottomClass || !View.class.isAssignableFrom(topClass)
                || !View.class.isAssignableFrom(bottomClass)) {
            throw new IllegalStateException("Scanned home navigation classes changed");
        }
        return new HomeNavigationHideController((Application) context.getApplicationContext(), settings,
                bottomClass, log);
    }

    public void setSettings(ExtendedSettings settings) {
        if (closed) return;
        this.settings = settings == null ? null : settings.components;
        runOnMain(() -> {
            if (closed) return;
            for (Monitor monitor : new ArrayList<>(activities.values())) monitor.refresh();
        });
    }

    public void setTemporarilyShown(boolean shown) {
        if (closed) return;
        temporarilyShown = shown;
        runOnMain(() -> {
            if (closed) return;
            for (Monitor monitor : new ArrayList<>(activities.values())) monitor.refresh();
        });
    }

    @Override public void onActivityResumed(Activity activity) {
        if (closed || activity == null || !isHome(activity)) return;
        runOnMain(() -> {
            if (closed || activity.isDestroyed()) return;
            Monitor monitor = activities.get(activity);
            if (monitor == null) {
                monitor = new Monitor(activity.getWindow().getDecorView());
                activities.put(activity, monitor);
            }
            monitor.attach();
            monitor.refresh();
        });
    }

    private static boolean isHome(Activity activity) {
        for (Class<?> type = activity.getClass(); type != null; type = type.getSuperclass()) {
            if (HOME_ACTIVITY.equals(type.getName())) return true;
        }
        return false;
    }

    @Override public void onActivityPaused(Activity activity) { remove(activity); }
    @Override public void onActivityDestroyed(Activity activity) { remove(activity); }

    private void remove(Activity activity) {
        runOnMain(() -> {
            Monitor monitor = activities.remove(activity);
            if (monitor != null) monitor.close();
        });
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        application.unregisterActivityLifecycleCallbacks(this);
        runOnMain(() -> {
            for (Monitor monitor : new ArrayList<>(activities.values())) monitor.close();
            activities.clear();
        });
    }

    private void runOnMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    static View uniqueTarget(View root, String className) {
        ArrayList<View> found = new ArrayList<>();
        find(root, className, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void find(View view, String className, ArrayList<View> found) {
        if (view == null) return;
        for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
            if (className.equals(type.getName())) {
                found.add(view);
                return;
            }
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), className, found);
        }
    }

    private final class Targets {
        View top;
        View bottom;
        int topCount;
        int bottomCount;
        void find(View view, boolean searchTop, boolean searchBottom) {
            boolean topMatch = false, bottomMatch = false;
            for (Class<?> type = view.getClass(); type != null; type = type.getSuperclass()) {
                topMatch |= searchTop && TOP_CLASS.equals(type.getName());
                bottomMatch |= searchBottom && bottomClassName.equals(type.getName());
            }
            if (topMatch) { top = ++topCount == 1 ? view : null; searchTop = false; }
            if (bottomMatch) { bottom = ++bottomCount == 1 ? view : null; searchBottom = false; }
            // Keep each target's original subtree boundary while discovering both in one walk.
            if ((searchTop || searchBottom) && view instanceof ViewGroup) {
                ViewGroup group = (ViewGroup) view;
                for (int i = 0; i < group.getChildCount(); i++) find(group.getChildAt(i), searchTop, searchBottom);
            }
        }
    }

    private final class Monitor implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<View> root;
        private ViewTreeObserver observer;
        private String lastDecision;
        private final Map<View, Original> originals = new WeakHashMap<>();
        Monitor(View root) { this.root = new WeakReference<>(root); }
        void attach() {
            View view = root.get();
            if (view == null) return;
            ViewTreeObserver next = view.getViewTreeObserver();
            if (next == observer || !next.isAlive()) return;
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = next;
            observer.addOnPreDrawListener(this);
        }
        @Override public boolean onPreDraw() {
            if (!closed) refresh();
            return true;
        }
        void refresh() {
            try {
                ComponentHideSettings current = settings;
                View view = root.get();
                if (view == null || current == null || !current.configured || !current.enabled
                        || temporarilyShown) {
                    detach();
                    decision(temporarilyShown ? "temporarily_shown" : "disabled_or_not_ready", "restore_requested=true");
                    restore();
                    return;
                }
                attach();
                Targets targets = new Targets();
                targets.find(view, true, true);
                View top = targets.top;
                View bottom = targets.bottom;
                if (top == null || bottom == null) decision("navigation_target_not_unique_or_missing",
                        "top_unique=" + (top != null) + ";bottom_unique=" + (bottom != null));
                else lastDecision = null;
                for (View old : new ArrayList<>(originals.keySet())) {
                    if (old != top && old != bottom) {
                        Original original = originals.remove(old);
                        if (old != null) original.restore(old);
                    }
                }
                apply(top, current.alpha(Component.TOP_NAV));
                apply(bottom, current.alpha(Component.BOTTOM_NAV));
            } catch (RuntimeException failure) {
                restore();
                try { log.accept("Home navigation opacity failed: " + failure.getMessage()); }
                catch (Throwable ignored) { }
            }
        }
        private void decision(String reason, String evidence) {
            String state = reason + ";" + evidence;
            if (state.equals(lastDecision)) return;
            lastDecision = state;
            DouAiLog.event("home_navigation", "decision", reason, evidence);
        }
        private void apply(View view, int alpha) {
            if (view == null) return;
            if (alpha == 255) {
                Original previous = originals.remove(view);
                if (previous != null) previous.restore(view);
                return;
            }
            Original original = originals.get(view);
            if (original == null) {
                original = new Original(view);
                originals.put(view, original);
            }
            original.apply(view, alpha);
        }
        void restore() {
            for (Map.Entry<View, Original> entry : originals.entrySet()) {
                if (entry.getKey() != null) entry.getValue().restore(entry.getKey());
            }
            originals.clear();
        }
        private void detach() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }
        void close() {
            detach();
            restore();
        }
    }

    static final class Original {
        private int visibility;
        private float alpha;
        private boolean clickable;
        private boolean focusable;
        private int appliedVisibility;
        private float appliedAlpha;
        private boolean appliedClickable;
        private boolean appliedFocusable;
        Original(View view) {
            visibility = view.getVisibility();
            alpha = view.getAlpha();
            clickable = view.isClickable();
            focusable = view.isFocusable();
            recordApplied(view);
        }
        void apply(View view, int value) {
            observeHostState(view);
            int nextVisibility = visibility == View.VISIBLE && value < 0
                    ? (value == -2 ? View.GONE : View.INVISIBLE) : visibility;
            float nextAlpha = value < 0 ? alpha : alpha * value / 255f;
            boolean nextClickable = value < 0 ? false : clickable;
            boolean nextFocusable = value < 0 ? false : focusable;
            boolean changed = view.getVisibility() != nextVisibility || Math.abs(view.getAlpha() - nextAlpha) > 0.001f
                    || view.isClickable() != nextClickable || view.isFocusable() != nextFocusable;
            if (view.getVisibility() != nextVisibility) view.setVisibility(nextVisibility);
            if (Math.abs(view.getAlpha() - nextAlpha) > 0.001f) view.setAlpha(nextAlpha);
            if (view.isClickable() != nextClickable) view.setClickable(nextClickable);
            if (view.isFocusable() != nextFocusable) view.setFocusable(nextFocusable);
            recordApplied(view);
            if (changed) observeApplied(view, nextVisibility, nextAlpha);
        }
        void restore(View view) {
            observeHostState(view);
            if (view.getVisibility() != visibility) view.setVisibility(visibility);
            if (Math.abs(view.getAlpha() - alpha) > 0.001f) view.setAlpha(alpha);
            if (view.isClickable() != clickable) view.setClickable(clickable);
            if (view.isFocusable() != focusable) view.setFocusable(focusable);
            recordApplied(view);
        }
        private void observeHostState(View view) {
            // Comments and screen recovery can change the host's baseline after our first draw.
            if (view.getVisibility() != appliedVisibility) visibility = view.getVisibility();
            if (Math.abs(view.getAlpha() - appliedAlpha) > 0.001f) alpha = view.getAlpha();
            if (view.isClickable() != appliedClickable) clickable = view.isClickable();
            if (view.isFocusable() != appliedFocusable) focusable = view.isFocusable();
        }
        private void recordApplied(View view) {
            appliedVisibility = view.getVisibility();
            appliedAlpha = view.getAlpha();
            appliedClickable = view.isClickable();
            appliedFocusable = view.isFocusable();
        }
        private static void observeApplied(View view, int visibility, float alpha) {
            try {
                DouAiLog.event("home_navigation", "view_state", "navigation_properties_readback",
                        "resource_id=" + view.getId() + ";expected_visibility=" + visibility
                                + ";actual_visibility=" + view.getVisibility() + ";expected_alpha=" + alpha
                                + ";actual_alpha=" + view.getAlpha() + ";result_scope=current_view");
            } catch (Throwable unavailable) { }
        }
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
