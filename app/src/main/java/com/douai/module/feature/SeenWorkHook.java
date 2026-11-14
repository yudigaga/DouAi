package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import java.io.File;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Douyin 41. A request-owned recommendation fragment proves the response source;
 *  current activity/window state is used only for observing a card actually on screen. */
public final class SeenWorkHook implements AutoCloseable {
    private static final String PREFIX = "com.ss.android.ugc.aweme.feed.";
    private static SeenWorkStore shared;
    private final SeenWorkStore store;
    private final Supplier<Activity> resumed;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BehaviorHookGroup group;
    private final Class<?> recommendFragment;
    private final Field panelField, requestModel, requestFragment, items, aid;
    private volatile boolean enabled;
    private volatile boolean allowEmpty;
    private volatile boolean closed;
    private SeenWorkProbe pending;

    private static synchronized SeenWorkStore store(Context context) {
        if (shared == null) shared = new SeenWorkStore(new File(context.getNoBackupFilesDir(), "douai-seen"),
                Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r,"DouAi-seen"); t.setDaemon(true); return t; }),
                System::currentTimeMillis);
        return shared;
    }

    public static void clearCurrentAccount(Context context, Consumer<Boolean> complete) {
        Handler ui = new Handler(Looper.getMainLooper());
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_410 || context == null) {
            ui.post(() -> complete.accept(false)); return;
        }
        store(context).clear(uid(), ok -> ui.post(() -> complete.accept(ok)));
    }

    public static SeenWorkHook install(XposedInterface framework, HostScanReport report,
                                       ClassLoader loader, Context context, Supplier<Activity> resumedActivity,
                                       Consumer<String> log) throws Exception {
        if (HostAdaptation.currentProfile() != HostAdaptation.PROFILE_410)
            throw new IllegalStateException("Seen-work contract requires Douyin 41");
        Class<?> request = dexType(loader, "X.1R33");
        Class<?> model = Class.forName(PREFIX+"presenter.RecommendFeedFetchModelV2", false, loader);
        Class<?> fragment = Class.forName(PREFIX+"ui.FeedRecommendFragment", false, loader);
        Class<?> list = HostTargets.type(report, "main_feed_response_model", loader);
        Class<?> work = HostTargets.type(report, "aweme_feed_attributes", loader);
        Method call = request.getDeclaredMethod("call");
        Field owner = request.getDeclaredField("h"), source = model.getDeclaredField("q");
        Field panel = fragment.getDeclaredField("u"), items = list.getField("items"), id = work.getField("aid");
        if (!Callable.class.isAssignableFrom(request) || call.getReturnType() != Object.class
                || Modifier.isStatic(call.getModifiers()) || owner.getType() != model
                || !Modifier.isFinal(owner.getModifiers()) || !Modifier.isFinal(source.getModifiers())
                || !source.getType().isAssignableFrom(fragment) || !List.class.isAssignableFrom(items.getType())
                || !panel.getType().getName().equals(PREFIX+"panel.FullFeedFragmentPanel") || id.getType() != String.class)
            throw new IllegalStateException("Seen-work recommendation request contract changed");
        SeenWorkHook hook = new SeenWorkHook(store(context), resumedActivity, log, fragment, panel, owner, source, items, id);
        try {
            hook.group.add(framework, call, hook::request);
            hook.holder(framework, report, loader, "feed_holder", "w", "B2");
            hook.holder(framework, report, loader, "image_holder", "f", "D");
            return hook;
        } catch (Exception failure) { hook.close(); throw failure; }
    }

    // The leading digit in a valid host DEX class name cannot be parsed as a Java source type.
    private static Class<?> dexType(ClassLoader loader, String binaryName) throws ClassNotFoundException {
        return loader.loadClass(binaryName);
    }

    private SeenWorkHook(SeenWorkStore store, Supplier<Activity> resumed, Consumer<String> log, Class<?> fragment,
                         Field panel, Field owner, Field source, Field items, Field id) {
        this.store = store; this.resumed = resumed;
        group = new BehaviorHookGroup("seen_works", log == null ? ignored -> { } : log);
        recommendFragment = fragment; panelField = panel; requestModel = owner; requestFragment = source;
        this.items = items; aid = id;
    }

    public synchronized void setOptions(boolean feedFilterEnabled, LocalOptions options) {
        if (closed) return;
        boolean on = feedFilterEnabled && options != null && options.enabled("hide_seen_works");
        enabled = false;
        allowEmpty = options != null && !options.enabled("filter_interrupt", true);
        store.configure(on, options == null ? 3 : options.integer("seen_work_days", 3));
        enabled = on;
        if (!on) cancelPending();
    }

    private static String uid() {
        try { return NativeFollowLists.currentAccountUid(); }
        catch (RuntimeException unavailable) { return ""; }
    }

    private long requestToken(Object request) {
        if (!enabled || closed) return 0;
        try {
            Object model = requestModel.get(request);
            Object fragment = model == null ? null : requestFragment.get(model);
            if (!recommendFragment.isInstance(fragment)
                    || !"homepage_hot".equals(HostTargets.invoke(panelField.get(fragment),"getEventType"))) return 0;
            return store.token(uid());
        } catch (IllegalAccessException | RuntimeException unavailable) { return 0; }
    }

    private Object request(XposedInterface.Chain chain) throws Throwable {
        long requestToken = requestToken(chain.getThisObject());
        Object page = chain.proceed();
        if (requestToken == 0 || !enabled || closed || !items.getDeclaringClass().isInstance(page)) return page;
        try {
            if (requestToken != store.token(uid())) return page;
            @SuppressWarnings("unchecked") List<Object> before = (List<Object>) items.get(page);
            List<Object> after = store.filter(requestToken, before, this::id, allowEmpty);
            if (before != after && enabled && !closed && requestToken == store.token(uid())
                    && items.get(page) == before) items.set(page, after);
        } catch (IllegalAccessException | RuntimeException unavailable) { /* retain native response */ }
        return page;
    }
    private String id(Object work) {
        if (!aid.getDeclaringClass().isInstance(work)) return "";
        try { return (String) aid.get(work); } catch (IllegalAccessException e) { return ""; }
    }

    private void holder(XposedInterface framework, HostScanReport report, ClassLoader loader,
                        String prefix, String rootField, String paramsField) throws Exception {
        Method selected = HostTargets.method(report,prefix+"_page_selected",loader);
        Class<?> holder = selected.getDeclaringClass();
        Field root = HostTargets.field(holder,rootField), params = HostTargets.field(holder,paramsField);
        if (holder != HostTargets.type(report,prefix+"_main_root",loader) || selected.getParameterCount() != 0
                || selected.getReturnType() != void.class || !View.class.isAssignableFrom(root.getType())
                || !params.getType().getName().equals(PREFIX+"model.VideoItemParams"))
            throw new IllegalStateException("Seen-work holder contract changed");
        XposedInterface.Hooker callback = chain -> {
            Object result = chain.proceed();
            if (enabled && !closed) queueSelected(chain.getThisObject(),root,params);
            return result;
        };
        group.add(framework,selected,callback);
        if (prefix.equals("feed_holder")) {
            Method second = HostTargets.method(report,"feed_holder_selected",loader);
            if (second.getDeclaringClass() != holder || second.getParameterCount() != 1
                    || second.getParameterTypes()[0] != int.class || second.getReturnType() != void.class)
                throw new IllegalStateException("Seen-work selection contract changed");
            group.add(framework,second,callback);
        }
    }

    private synchronized void cancelPending() {
        if (pending != null) { pending.cancel(); main.removeCallbacks(pending); pending = null; }
    }

    private synchronized void queueSelected(Object holder, Field root, Field params) {
        cancelPending();
        if (!enabled || closed) return;
        try {
            Object p = params.get(holder);
            if (!"homepage_hot".equals(HostTargets.invoke(p,"getEventType"))) return;
            Object fragment = HostTargets.read(p,"fragment");
            if (!recommendFragment.isInstance(fragment)) return;
            String expected = id(HostTargets.invoke(holder,"getAweme"));
            if (expected == null || expected.isEmpty()) return;
            long token = store.token(uid()); if (token == 0) return;
            WeakReference<Object> ref = new WeakReference<>(holder);
            pending = new SeenWorkProbe(() -> {
                if (!enabled || closed || token != store.token(uid())) return true;
                try {
                    Object h = ref.get(); if (h == null) return true;
                    Object current = params.get(h);
                    Object f = HostTargets.read(current,"fragment");
                    if (!recommendFragment.isInstance(f) || !"homepage_hot".equals(HostTargets.invoke(current,"getEventType"))
                            || !expected.equals(id(HostTargets.invoke(h,"getAweme")))) return true;
                    Object selected = HostTargets.invoke(panelField.get(f),"getCurrentViewHolderP");
                    if (selected == null) return false; // selection/first layout can settle a frame later
                    if (selected != h) return true;
                    if (!LocalAnalyticsHook.visible((View)root.get(h),resumed.get())) return false;
                    store.visible(token,expected);
                } catch (IllegalAccessException | RuntimeException ignored) { }
                return true;
            }, main::postDelayed);
            main.post(pending);
        } catch (IllegalAccessException | RuntimeException ignored) { }
    }
    @Override public synchronized void close() {
        closed = true; enabled = false; cancelPending(); store.configure(false,3); main.removeCallbacksAndMessages(null); group.close();
    }
}
