package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** Changes only the current account's displayed statistics, never the shared User model. */
public final class ProfileSpoofHook implements AutoCloseable {
    private static final String[] FIELDS = { "y", "w", "x", "F" };
    private static final String[] MODERN_FIELDS = { "x", "v", "w", "E" };
    private static final String[] KEYS = { "profile_like_count", "profile_following_count",
            "profile_follower_count", "profile_mutual_count" };
    private final BehaviorHookGroup registration;
    private final Method ownProfile;
    private final Method rootView;
    private final Field[] countFields;
    private final Map<Object, Monitor> monitors = new WeakHashMap<>();
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LocalOptions options;
    private volatile boolean closed;

    private ProfileSpoofHook(LocalOptions options, BehaviorHookGroup registration,
                             Method ownProfile, Method rootView, Field[] countFields) {
        this.options = options;
        this.registration = registration;
        this.ownProfile = ownProfile;
        this.rootView = rootView;
        this.countFields = countFields;
    }

    public static ProfileSpoofHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, Context context,
                                           LocalOptions options, Consumer<String> log) throws Exception {
        Consumer<String> sink = log == null ? ignored -> { } : log;
        BehaviorHookGroup registration = new BehaviorHookGroup("spoof_profile", sink);
        ProfileSpoofHook hook = null;
        try {
            Method bind = HostTargets.method(report, "adv_profile_render", loader);
            Method own = HostTargets.method(report, "adv_profile_own", loader);
            Method root = HostTargets.method(report, "adv_profile_root", loader);
            if (!own.getDeclaringClass().isAssignableFrom(bind.getDeclaringClass())
                    || !bind.getDeclaringClass().equals(root.getDeclaringClass())
                    || own.getReturnType() != boolean.class || own.getParameterCount() != 0
                    || !View.class.isAssignableFrom(root.getReturnType())) {
                throw new IllegalStateException("Profile display relationship changed");
            }
            String[] names = HostAdaptation.currentProfile() == HostAdaptation.PROFILE_410
                    ? MODERN_FIELDS : FIELDS;
            Field[] fields = new Field[names.length];
            for (int i = 0; i < fields.length; i++) {
                fields[i] = HostTargets.field(bind.getDeclaringClass(), names[i]);
                if (!TextView.class.isAssignableFrom(fields[i].getType())) {
                    throw new IllegalStateException("Profile statistic view changed: " + names[i]);
                }
            }
            hook = new ProfileSpoofHook(options, registration, own, root, fields);
            ProfileSpoofHook installed = hook;
            registration.add(framework, bind, chain -> {
                Object result = chain.proceed();
                if (!installed.closed && registration.isActive()) installed.observe(chain.getThisObject());
                return result;
            });
            try { sink.accept("Advanced feature registered: spoof_profile (display only)"); }
            catch (Throwable ignored) { }
            return hook;
        } catch (Throwable failure) {
            if (hook != null) hook.close();
            registration.fail(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public boolean isActive() { return !closed && registration.isActive(); }

    public void setOptions(LocalOptions options) {
        if (closed) return;
        this.options = options;
        onMain(() -> {
            if (closed) return;
            for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.refresh();
        });
    }

    private void observe(Object owner) {
        if (owner == null) return;
        onMain(() -> {
            if (closed || !registration.isActive()) return;
            Monitor monitor = monitors.get(owner);
            if (monitor == null) {
                monitor = new Monitor(owner);
                monitors.put(owner, monitor);
            }
            monitor.refresh();
        });
    }

    static String displayCount(LocalOptions options, String key) {
        return Long.toString(Math.max(0L, options.longValue(key, 0L)));
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        registration.close();
        onMain(() -> {
            for (Monitor monitor : new ArrayList<>(monitors.values())) monitor.restore();
            monitors.clear();
        });
    }

    private final class Monitor implements ViewTreeObserver.OnPreDrawListener {
        private final WeakReference<Object> owner;
        private final Map<TextView, Original> originals = new WeakHashMap<>();
        private ViewTreeObserver observer;

        Monitor(Object owner) { this.owner = new WeakReference<>(owner); }

        @Override public boolean onPreDraw() { refresh(); return true; }

        void refresh() {
            try {
                Object target = owner.get();
                LocalOptions current = options;
                if (closed || !registration.isActive() || target == null || current == null
                        || !current.enabled("spoof_profile")) {
                    DouAiLog.event("spoof_profile", "decision", "disabled_or_not_ready", "restore_requested=true");
                    restore();
                    return;
                }
                if (!Boolean.TRUE.equals(ownProfile.invoke(target))) {
                    DouAiLog.event("spoof_profile", "decision", "not_own_profile", "restore_requested=true");
                    restore();
                    return;
                }
                View root = (View) rootView.invoke(target);
                if (root == null || !root.isAttachedToWindow()) {
                    DouAiLog.event("spoof_profile", "decision", "profile_view_unavailable", "restore_requested=true");
                    restore(); return;
                }
                ViewTreeObserver next = root.getViewTreeObserver();
                if (next != observer && next.isAlive()) {
                    detach();
                    observer = next;
                    observer.addOnPreDrawListener(this);
                }
                Map<TextView, String> active = new HashMap<>();
                for (int i = 0; i < countFields.length; i++) {
                    TextView view = (TextView) countFields[i].get(target);
                    if (view != null) active.put(view, displayCount(current, KEYS[i]));
                }
                for (TextView view : new ArrayList<>(originals.keySet())) {
                    if (!active.containsKey(view)) {
                        Original original = originals.remove(view);
                        if (view != null) original.restore(view);
                    }
                }
                for (Map.Entry<TextView, String> entry : active.entrySet()) {
                    TextView view = entry.getKey();
                    Original original = originals.get(view);
                    if (original == null) {
                        original = new Original(view.getText());
                        originals.put(view, original);
                    }
                    original.apply(view, entry.getValue());
                }
            } catch (ReflectiveOperationException | RuntimeException failure) {
                restore();
                registration.fail(failure);
                ProfileSpoofHook.this.close();
            }
        }

        void detach() {
            if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(this);
            observer = null;
        }

        void restore() {
            detach();
            for (Map.Entry<TextView, Original> entry : originals.entrySet()) {
                if (entry.getKey() != null) entry.getValue().restore(entry.getKey());
            }
            originals.clear();
        }
    }

    private static final class Original {
        private CharSequence original;
        private CharSequence applied;
        Original(CharSequence original) { this.original = original; }
        void apply(TextView view, CharSequence next) {
            if (applied != null && !TextUtils.equals(view.getText(), applied)) original = view.getText();
            if (!TextUtils.equals(view.getText(), next)) {
                view.setText(next);
                observeApplied(view, next);
            }
            applied = next;
        }
        void restore(TextView view) {
            if (applied != null && TextUtils.equals(view.getText(), applied)) view.setText(original);
        }
        private static void observeApplied(TextView view, CharSequence next) {
            try {
                DouAiLog.event("spoof_profile", "view_state", TextUtils.equals(view.getText(), next)
                        ? "display_text_readback" : "display_text_unconfirmed", "resource_id=" + view.getId()
                        + ";configured_ref=" + DouAiLog.reference(String.valueOf(next)) + ";result_scope=current_text_view");
            } catch (Throwable unavailable) { }
        }
    }
}
