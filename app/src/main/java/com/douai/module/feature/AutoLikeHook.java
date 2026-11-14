package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.SystemClock;
import android.widget.Toast;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.FeatureSettings;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Optional auto-like action routed through Douyin's permission- and login-aware DiggViewModel. */
public final class AutoLikeHook implements AutoCloseable {
    private final ClassLoader loader;
    private final Method likeMethod;
    private final Method activeBindMethod;
    private final Method presenterViewModelMethod;
    private final Field viewModelAwemeField;
    private final Set<String> attemptedAwemeIds = ConcurrentHashMap.newKeySet();
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Object settingsLock = new Object();
    private final AtomicLong settingsGeneration = new AtomicLong();
    private static final int MAX_MANUAL_BINDINGS = 16;
    private final ArrayDeque<WeakReference<Object>> recentPresenters = new ArrayDeque<>();
    private final LinkedHashMap<String,Long> lastLikeRequest = new LinkedHashMap<>();
    private final Consumer<String> log;
    private volatile FeatureSettings settings;
    private boolean closed;

    private AutoLikeHook(ClassLoader loader, Method likeMethod, Method activeBindMethod,
                         Method presenterViewModelMethod, Field viewModelAwemeField,
                         FeatureSettings settings, Consumer<String> log) {
        this.loader = loader;
        this.likeMethod = likeMethod;
        this.activeBindMethod = activeBindMethod;
        this.presenterViewModelMethod = presenterViewModelMethod;
        this.viewModelAwemeField = viewModelAwemeField;
        this.settings = settings;
        this.log = log;
    }

    public static AutoLikeHook install(XposedInterface framework, HostScanReport report,
                                       ClassLoader loader, FeatureSettings settings,
                                       Consumer<String> log) throws Exception {
        Method bind = resolveMethod(report, "digg_core_presenter_bind", loader);
        Method like = resolveMethod(report, "digg_view_model_like", loader);
        Class<?> viewModelClass = resolveClass(report, "digg_view_model_state", loader);
        String viewModelGetter = com.douai.module.scan.HostResourceProfile.diggViewModelGetter(
                com.douai.module.scan.HostAdaptation.currentProfile());
        Method viewModel = findNoArgMethod(bind.getDeclaringClass(), viewModelGetter);
        Field aweme = viewModelClass.getField("b");
        if (viewModel == null || aweme.getType() == null
                || !"com.ss.android.ugc.aweme.feed.model.Aweme".equals(aweme.getType().getName())
                || like.getReturnType() != void.class || like.getParameterCount() != 3
                || like.getParameterTypes()[0] != Activity.class
                || like.getParameterTypes()[1] != String.class
                || !"kotlin.jvm.functions.Function2".equals(like.getParameterTypes()[2].getName())) {
            throw new IllegalStateException("DiggViewModel action contract changed");
        }
        like.setAccessible(true);
        viewModel.setAccessible(true);
        aweme.setAccessible(true);

        AutoLikeHook hook = new AutoLikeHook(loader, like, bind, viewModel, aweme, settings,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(bind)
                    .setId("DouAi.Automation.AutoLike")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::afterBind);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            hook.unhookAll(failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setSettings(FeatureSettings settings) {
        FeatureSettings next = settings == null ? FeatureSettings.defaults() : settings;
        synchronized (settingsLock) {
            if (closed) return;
            FeatureSettings previous = this.settings;
            if (previous == null || previous.automationEnabled != next.automationEnabled
                    || previous.autoLikeEnabled != next.autoLikeEnabled) {
                settingsGeneration.incrementAndGet();
            }
            this.settings = next;
            if (!next.automationEnabled || !next.autoLikeEnabled) attemptedAwemeIds.clear();
        }
    }

    private Object afterBind(XposedInterface.Chain chain) throws Throwable {
        Object result = chain.proceed();
        Object presenter = chain.getThisObject();
        rememberPresenter(presenter);
        long generation;
        synchronized (settingsLock) {
            FeatureSettings current = settings;
            if (closed || current == null || !current.automationEnabled || !current.autoLikeEnabled) {
                DouAiLog.event("auto_like", "eligibility", "disabled_or_closed", "closed=" + closed);
                return result;
            }
            generation = settingsGeneration.get();
        }

        String reservedAwemeId = null;
        boolean likeQueued = false;
        DouAiLog.Operation trace = null;
        try {
            Object viewModel = presenterViewModelMethod.invoke(presenter);
            Object aweme = viewModelAwemeField.get(viewModel);
            if (aweme == null || Boolean.TRUE.equals(invokeBoolean(aweme, "getIsAdAweme"))) { DouAiLog.event("auto_like", "eligibility", "missing_work_or_ad", "eligible=false"); return result; }
            Object alreadyLiked = fieldValue(aweme, "userDigg");
            if (alreadyLiked instanceof Number && ((Number) alreadyLiked).intValue() != 0) { DouAiLog.event("auto_like", "eligibility", "already_liked", "user_digg_nonzero=true"); return result; }
            String id = stringValue(invokeNoArg(aweme, "getAid"));
            String rejected = id == null ? "target_missing" : !isCurrentCard(presenter, id) ? "inactive_card" : !isLoggedIn() ? "logged_out" : "";
            if (!rejected.isEmpty()) { DouAiLog.event("auto_like", "eligibility", rejected, "target_ref=" + DouAiLog.reference(id)); return result; }
            Activity activity = presenterActivity(presenter);
            if (activity == null || activity.isFinishing() || activity.isDestroyed()) { DouAiLog.event("auto_like", "eligibility", "activity_unavailable", "activity_present=" + (activity != null)); return result; }

            synchronized (settingsLock) {
                if (!enabledLocked(generation) || !isCurrentCard(presenter, id)) { DouAiLog.event("auto_like", "eligibility", "settings_or_card_changed", "target_ref=" + DouAiLog.reference(id)); return result; }
                Object currentViewModel = presenterViewModelMethod.invoke(presenter);
                Object currentAweme = viewModelAwemeField.get(currentViewModel);
                if (!id.equals(stringValue(invokeNoArg(currentAweme, "getAid")))
                        || Boolean.TRUE.equals(invokeBoolean(currentAweme, "getIsAdAweme"))) { DouAiLog.event("auto_like", "eligibility", "work_changed_or_ad", "target_ref=" + DouAiLog.reference(id)); return result; }
                Object currentLiked = fieldValue(currentAweme, "userDigg");
                if (currentLiked instanceof Number && ((Number) currentLiked).intValue() != 0) { DouAiLog.event("auto_like", "eligibility", "liked_before_invocation", "user_digg_nonzero=true"); return result; }
                if (attemptedAwemeIds.contains(id)) { DouAiLog.event("auto_like", "eligibility", "duplicate", "target_ref=" + DouAiLog.reference(id)); return result; }
                if (!reserveLikeRequest(id)) return result; // a manual tap has already dispatched this request
                attemptedAwemeIds.add(id);
                reservedAwemeId = id;
                trace = DouAiLog.begin("auto_like", "visible_card_bind");
                trace.stage("native_invoking", "target_ref=" + DouAiLog.reference(id) + ";logged_in=true;current_card=true;user_digg=0");
                likeMethod.invoke(currentViewModel, activity, "douai_auto", null);
                com.douai.module.analytics.LocalAnalytics.action(id, "auto_like", "自动点赞请求", "automatic", "request");
                trace.finish(DouAiLog.Outcome.UNCONFIRMED, "native_like_requested", "permission_checked_method_returned=true;server_ack_observed=false");
                likeQueued = true;
                reservedAwemeId = null;
            }
            safeLog("Auto-like routed through Douyin permission checks for one visible item");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (trace != null) { trace.error("native_like", failure); trace.finish(DouAiLog.Outcome.UNCONFIRMED, "native_invocation_threw", "remote_side_effect_unknown=true"); }
            else DouAiLog.failure("auto_like", "eligibility", failure);
            if (reservedAwemeId != null) attemptedAwemeIds.remove(reservedAwemeId);
            safeLog("Auto-like skipped: " + failure.getMessage());
        } finally {
            if (!likeQueued && reservedAwemeId != null) attemptedAwemeIds.remove(reservedAwemeId);
        }
        return result;
    }

    // Binding also happens on neighboring/preloaded views. Don't reflect over their models
    // until a user actually taps; retain neither an Activity nor a host work object here.
    private void rememberPresenter(Object presenter) {
        synchronized (settingsLock) {
            if (closed || presenter == null) return;
            recentPresenters.removeIf(ref -> ref.get() == null || ref.get() == presenter);
            if (recentPresenters.size() == MAX_MANUAL_BINDINGS) recentPresenters.removeLast();
            recentPresenters.addFirst(new WeakReference<>(presenter));
        }
    }

    /** Called by the gesture dispatcher on main. JD (and its profile aliases) only likes;
     *  native double tap and the separate un-digg path are never invoked. */
    public void requestManualLike(Activity owner, String aid) {
        if (owner == null || owner.isFinishing() || owner.isDestroyed()) return;
        String message = "当前作品暂时无法点赞";
        synchronized (settingsLock) {
            if (closed) return;
            if (aid != null && !aid.isEmpty()) for (WeakReference<Object> ref : recentPresenters) {
                Object presenter = ref.get();
                if (presenter == null || presenterActivity(presenter) != owner) continue;
                try {
                    Object vm = presenterViewModelMethod.invoke(presenter);
                    if (vm == null) continue;
                    Object work = viewModelAwemeField.get(vm);
                    if (!aid.equals(HostTargets.invoke(work, "getAid"))) continue;
                    // In 41 the original VideoItemParams lives on model. diggParams may
                    // instead be the separate component parameter used by the native widget.
                    Object panel = HostTargets.read(HostTargets.read(presenter,"model"),"mFeedContext");
                    if (panel == null) panel = HostTargets.read(HostTargets.read(presenter,"diggParams"),"mFeedContext");
                    Object holder = HostTargets.invoke(panel,"getCurrentViewHolderP");
                    if (work != HostTargets.invoke(holder,"getAweme")) continue;
                    Object liked = HostTargets.read(work,"userDigg");
                    if (Integer.valueOf(1).equals(liked)) { message = "这条作品已经赞过"; break; }
                    if (!Integer.valueOf(0).equals(liked)) break;
                    if (!reserveLikeRequest(aid)) { message = "刚刚已请求点赞"; break; }
                    likeMethod.invoke(vm, owner, "douai_gesture", null);
                    return; // the host owns permission/login prompts and visual confirmation
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    break; // invocation may have reached the host; don't try the next presenter
                }
            }
        }
        Toast.makeText(owner, message, Toast.LENGTH_SHORT).show();
    }

    /** Called under settingsLock. Share the short pending window with automatic requests. */
    private boolean reserveLikeRequest(String id) {
        long now = SystemClock.elapsedRealtime();
        Long previous = lastLikeRequest.get(id);
        if (previous != null && now - previous < 2000) return false;
        lastLikeRequest.remove(id);
        lastLikeRequest.put(id, now);
        if (lastLikeRequest.size() > MAX_MANUAL_BINDINGS)
            lastLikeRequest.remove(lastLikeRequest.keySet().iterator().next());
        return true;
    }

    private boolean enabledLocked(long generation) {
        FeatureSettings current = settings;
        return !closed && settingsGeneration.get() == generation
                && current != null && current.automationEnabled && current.autoLikeEnabled;
    }

    private boolean isCurrentCard(Object presenter, String boundAwemeId) {
        try {
            Object diggParams = fieldValue(presenter, "diggParams");
            Object feedContext = diggParams == null ? null : fieldValue(diggParams, "mFeedContext");
            if (feedContext == null) return false;
            Method currentHolderMethod = findNoArgMethod(feedContext.getClass(), "getCurrentViewHolderP");
            if (currentHolderMethod == null) return false;
            Object currentHolder = currentHolderMethod.invoke(feedContext);
            if (currentHolder == null) return false;
            Object currentAweme = invokeNoArg(currentHolder, "getAweme");
            String currentId = stringValue(invokeNoArg(currentAweme, "getAid"));
            return boundAwemeId.equals(currentId);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private Activity presenterActivity(Object presenter) {
        try {
            Method getQContext = findNoArgMethod(presenter.getClass(), "getQContext");
            Object qContext = getQContext == null ? null : getQContext.invoke(presenter);
            Method activity = qContext == null ? null : findNoArgMethod(qContext.getClass(), "activity");
            Object context = activity == null ? null : activity.invoke(qContext);
            while (context instanceof ContextWrapper && !(context instanceof Activity)) {
                context = ((ContextWrapper) context).getBaseContext();
            }
            return context instanceof Activity ? (Activity) context : null;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private boolean isLoggedIn() {
        try {
            Class<?> accountService = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService",
                    false, loader);
            Object userService = accountService.getMethod("userService").invoke(null);
            Object loggedIn = userService.getClass().getMethod("isLogin").invoke(userService);
            return Boolean.TRUE.equals(loggedIn);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return false;
        }
    }

    private static Boolean invokeBoolean(Object target, String methodName) {
        Object value = invokeNoArg(target, methodName);
        return value instanceof Boolean ? (Boolean) value : null;
    }

    private static Object invokeNoArg(Object target, String methodName) {
        if (target == null) return null;
        Method method = findNoArgMethod(target.getClass(), methodName);
        if (method == null) return null;
        try {
            method.setAccessible(true);
            return method.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private static Object fieldValue(Object target, String name) throws ReflectiveOperationException {
        if (target == null) return null;
        for (Class<?> type = target.getClass(); type != null; type = type.getSuperclass()) {
            try {
                Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException ignored) {
                // Search base presenter/context classes for state fields.
            }
        }
        return null;
    }

    private static Method findNoArgMethod(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterCount() == 0) return method;
            }
        }
        return null;
    }

    private static String stringValue(Object value) {
        return value instanceof String && !((String) value).isEmpty() ? (String) value : null;
    }

    private static Class<?> resolveClass(HostScanReport report, String ruleId,
                                         ClassLoader loader) throws ClassNotFoundException {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        if (!candidate.getMethodName().isEmpty()) throw new IllegalStateException("Expected class rule: " + ruleId);
        return Class.forName(className(candidate.getClassDescriptor()), false, loader);
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = Class.forName(className(candidate.getClassDescriptor()), false, loader);
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

    private static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
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

    @Override
    public void close() {
        synchronized (settingsLock) {
            closed = true;
            recentPresenters.clear();
            lastLikeRequest.clear();
            settingsGeneration.incrementAndGet();
        }
        unhookAll(null);
        attemptedAwemeIds.clear();
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
        if (originalFailure == null && failure != null) safeLog("Auto-like unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Diagnostics must not affect the native digg action.
        }
    }
}
