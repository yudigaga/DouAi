package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostCandidate;
import java.lang.annotation.Annotation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/** Reflects the reviewed native global user-search API. */
final class NativeUserSearchSource implements NativeUserSearch.Source {
    interface TypeResolver { Class<?> resolve(String name) throws ClassNotFoundException; }
    private static final String ROOT = "com.ss.android.ugc.aweme.";
    private static final String ENDPOINT = "/aweme/v1/discover/search/";
    private final TypeResolver types;
    private final HostScanReport report;
    private final long timeoutMillis;
    private volatile Api api;

    NativeUserSearchSource(ClassLoader loader, HostScanReport report) {
        this(name -> Class.forName(name, false, loader), report, 20_000);
    }

    NativeUserSearchSource(TypeResolver types, HostScanReport report, long timeoutMillis) {
        if (types == null || timeoutMillis < 1) throw new IllegalArgumentException("Invalid user search source");
        this.types = types; this.report = report; this.timeoutMillis = timeoutMillis;
    }

    @Override public NativeUserSearch.Page search(String query, long cursor, String searchId,
                                                 BooleanSupplier current) throws Exception {
        String keyword = query == null ? "" : query.trim();
        String continuation = searchId == null ? "" : searchId;
        if (keyword.isEmpty() || keyword.length() > 200 || cursor < 0 || cursor > Integer.MAX_VALUE
                || !safeSearchId(continuation) || cursor > 0 && continuation.isEmpty()) {
            throw new IllegalArgumentException("Invalid user search query or continuation");
        }
        checkCurrent(current);
        String stage = "resolve_api";
        try {
            stage(stage, cursor);
            Api nativeApi = api();
            checkCurrent(current);
            stage = "build_parameters";
            stage(stage, cursor);
            Object request = nativeApi.constructor.newInstance(keyword);
            nativeApi.setCount.invoke(request, 10);
            nativeApi.setSearchId.invoke(request, continuation);
            Map<String, String> extras = new LinkedHashMap<>();
            extras.put("source", "user_search");
            extras.put("cursor", Long.toString(cursor));
            extras.put("search_id", continuation);
            extras.put("search_scene", "douyin_search");
            nativeApi.extras.set(request, extras);
            Map<String, String> parameters = new LinkedHashMap<>();
            copyMap(parameters, nativeApi.values.invoke(request));
            parameters.put("token", "search");
            copyMap(parameters, nativeApi.dynamic.invoke(request));
            parameters.put("vlm_config", "1");
            if (!keyword.equals(parameters.get("keyword")) || !"user".equals(parameters.get("nice_search_type"))
                    || !"user_search".equals(parameters.get("source"))
                    || !Long.toString(cursor).equals(parameters.get("cursor"))
                    || !continuation.equals(parameters.get("search_id"))
                    || !"douyin_search".equals(parameters.get("search_scene"))) {
                throw new IllegalStateException("Native user search parameter contract changed");
            }
            checkCurrent(current);
            stage = "native_request";
            stage(stage, cursor);
            Object observable = nativeApi.search.invoke(nativeApi.service, parameters);
            if (!nativeApi.observable.isInstance(observable)) throw changed();
            Object bounded = nativeApi.timeout.invoke(observable, timeoutMillis, TimeUnit.MILLISECONDS);
            checkCurrent(current);
            Object response = nativeApi.blockingFirst.invoke(bounded);
            checkCurrent(current);
            stage = "project_response";
            stage(stage, cursor);
            NativeUserSearch.Page page = project(nativeApi, response, cursor);
            checkCurrent(current);
            return page;
        } catch (InvocationTargetException failed) {
            checkCurrent(current);
            Throwable cause = failed.getCause();
            failure(stage, cursor, cause == null ? failed : cause);
            if (cause instanceof Exception) throw (Exception) cause;
            if (cause instanceof LinkageError) throw (LinkageError) cause;
            throw new IllegalStateException("Native user search failed", cause);
        } catch (Exception | LinkageError failed) {
            failure(stage, cursor, failed);
            throw failed;
        }
    }

    private static void stage(String stage, long cursor) {
        DouAiLog.event("user_search", "native_stage", stage, "page=" + (cursor == 0 ? "first" : "more"));
    }

    private static void failure(String stage, long cursor, Throwable failure) {
        DouAiLog.event("user_search", "native_failed", stage, "page=" + (cursor == 0 ? "first" : "more")
                + ";error_class=" + failure.getClass().getSimpleName());
    }

    private synchronized Api api() throws Exception {
        if (api == null) api = new Api();
        return api;
    }

    private NativeUserSearch.Page project(Api nativeApi, Object response, long previousCursor) throws Exception {
        if (response == null || integer(read(response, "status_code", "status_code")) != 0
                || integer(read(response, "error_code", "error_code")) != 0) throw changed();
        int cursor = integer(read(response, "cursor", "cursor"));
        Object more = read(response, "has_more", "hasMore");
        if (cursor < 0 || !(more instanceof Boolean)) throw changed();
        boolean hasMore = (Boolean) more;
        Object log = read(response, "log_pb", "logPb");
        Object rawId = log == null ? null : read(log, "impr_id", "imprId");
        if (rawId != null && !(rawId instanceof String)) throw changed();
        String searchId = rawId == null ? "" : (String) rawId;
        if (!safeSearchId(searchId) || hasMore && (cursor <= previousCursor || searchId.isEmpty())) throw changed();
        Object users = read(response, "user_list", "userList");
        if (users != null && !(users instanceof List<?>) || users == null && hasMore) throw changed();
        Map<String, ProfileListEntry> entries = new LinkedHashMap<>();
        if (users != null) for (Object wrapper : (List<?>) users) {
            if (wrapper == null || !nativeApi.wrapper.isInstance(wrapper)) throw changed();
            Object user = read(wrapper, "user_info", "a");
            if (user == null) {
                Object patch = read(wrapper, "dynamic_patch", "n");
                Object raw = patch == null ? null : read(patch, "raw_data", "rawData");
                if (raw != null && !(raw instanceof String)) throw changed();
                if (raw instanceof String && !((String) raw).isEmpty()) {
                    Object parsed = nativeApi.fromJson.invoke(nativeApi.gson(), raw, nativeApi.wrapper);
                    if (parsed != null && !nativeApi.wrapper.isInstance(parsed)) throw changed();
                    if (parsed != null) user = read(parsed, "user_info", "a");
                }
            }
            if (user == null) continue;
            if (!nativeApi.user.isInstance(user)) throw changed();
            Object rawUid = read(user, "uid", "uid");
            if (!(rawUid instanceof String)) throw changed();
            String uid = FollowListSnapshot.normalizeUid((String) rawUid);
            if (uid.isEmpty()) throw changed();
            if (!entries.containsKey(uid)) entries.put(uid, ProfileListEntry.fromUser(uid, user, null));
        }
        return new NativeUserSearch.Page(new ArrayList<>(entries.values()), cursor, hasMore, searchId);
    }

    private static Object read(Object target, String jsonName, String stableName) throws Exception {
        Field field = DownloadActionHook.findFieldByJsonName(target.getClass(), jsonName);
        if (field == null) {
            try { field = HostTargets.field(target.getClass(), stableName); }
            catch (NoSuchFieldException absent) { return null; }
        }
        field.setAccessible(true);
        return field.get(target);
    }

    private static int integer(Object value) {
        if (!(value instanceof Integer)) throw changed();
        return (Integer) value;
    }

    private static void copyMap(Map<String, String> target, Object raw) {
        if (!(raw instanceof Map<?, ?>)) throw changed();
        for (Map.Entry<?, ?> entry : ((Map<?, ?>) raw).entrySet()) {
            if (!(entry.getKey() instanceof String) || !(entry.getValue() instanceof String)) throw changed();
            target.put((String) entry.getKey(), (String) entry.getValue());
        }
    }

    private static boolean safeSearchId(String value) {
        if (value.length() > 256) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
    }

    private static void checkCurrent(BooleanSupplier current) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("User search interrupted");
        if (!current.getAsBoolean()) throw new InterruptedException("User search changed account or closed");
    }

    private static IllegalStateException changed() { return new IllegalStateException("Native user search contract changed"); }

    private final class Api {
        final Constructor<?> constructor;
        final Class<?> observable, wrapper, user;
        final Method setCount, setSearchId, values, dynamic, search, timeout, blockingFirst, fromJson;
        final Field extras;
        final Object service;
        private Object gson;

        Api() throws Exception {
            Class<?> base = types.resolve(ROOT + "search.op.standard.AbstractSearchRequest");
            Class<?> request = types.resolve(ROOT + "search.op.standard.SearchUserListRequest");
            if (request.getSuperclass() != base) throw changed();
            constructor = request.getConstructor(String.class);
            setCount = method(base, "LJIILJJIL", void.class, int.class);
            setSearchId = method(base, "LJJII", void.class, String.class);
            values = method(base, "LJJIIZ", Map.class);
            dynamic = method(base, "LJ", Map.class);
            extras = base.getField("LJJII");
            if (extras.getType() != Map.class || Modifier.isStatic(extras.getModifiers())) throw changed();
            observable = types.resolve("io.reactivex.Observable");
            wrapper = types.resolve(ROOT + "discover.model.SearchUser");
            user = types.resolve(ROOT + "profile.model.User");
            Class<?> apiType = types.resolve(ROOT + "discover.api.SearchApi$RealApi");
            if (!apiType.isInterface()) throw changed();
            Class<?> post = types.resolve("retrofit2.http.POST");
            Class<?> fields = types.resolve("retrofit2.http.FieldMap");
            Class<?> form = types.resolve("retrofit2.http.FormUrlEncoded");
            Method found = null;
            for (Method candidate : apiType.getMethods()) {
                Annotation endpoint = annotation(candidate.getAnnotations(), post);
                if (endpoint == null || !ENDPOINT.equals(post.getMethod("value").invoke(endpoint))) continue;
                if (candidate.getReturnType() != observable || candidate.getParameterCount() != 1
                        || candidate.getParameterTypes()[0] != Map.class || Modifier.isStatic(candidate.getModifiers())
                        || annotation(candidate.getAnnotations(), form) == null
                        || annotation(candidate.getParameterAnnotations()[0], fields) == null) continue;
                if (found != null) throw new NoSuchMethodException("Ambiguous native user search API");
                found = candidate;
            }
            if (found == null) throw new NoSuchMethodException("Missing reviewed native user search API");
            search = found;
            timeout = method(observable, "timeout", observable, long.class, TimeUnit.class);
            blockingFirst = method(observable, "blockingFirst", Object.class);
            fromJson = method(types.resolve("com.google.gson.Gson"), "fromJson", Object.class, String.class, Class.class);
            HostCandidate candidate = HostTargets.candidate(report, "relation_retrofit_service");
            String descriptor = candidate.getClassDescriptor();
            Class<?> owner = types.resolve(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            Method factory = new HostReflectionCache().declaredMethod(owner, candidate.getMethodName(), candidate.getMethodDescriptor());
            Class<?> providerType = types.resolve(ROOT + "framework.services.IRetrofitService");
            Class<?> retrofitType = types.resolve(ROOT + "framework.services.IRetrofit");
            if (factory == null || !Modifier.isStatic(factory.getModifiers()) || factory.getParameterCount() != 0
                    || factory.getReturnType() != providerType) throw changed();
            Object provider = factory.invoke(null);
            Object prefix = types.resolve(ROOT + "app.api.Api").getField("API_URL_PREFIX_SI").get(null);
            if (!(prefix instanceof String) || ((String) prefix).isEmpty()) throw changed();
            Object retrofit = method(providerType, "createNewRetrofit", retrofitType, String.class).invoke(provider, prefix);
            service = method(retrofitType, "create", Object.class, Class.class).invoke(retrofit, apiType);
            if (!apiType.isInstance(service)) throw changed();
        }

        synchronized Object gson() throws Exception {
            if (gson == null) {
                Class<?> provider = types.resolve(ROOT + "utils.GsonProvider");
                Class<?> companion = types.resolve(ROOT + "utils.GsonProvider$Companion");
                Object instance = method(companion, "get", provider).invoke(provider.getField("Companion").get(null));
                gson = method(provider, "getGson", types.resolve("com.google.gson.Gson")).invoke(instance);
                if (gson == null) throw changed();
            }
            return gson;
        }
    }

    private static Annotation annotation(Annotation[] values, Class<?> type) {
        for (Annotation value : values) if (value.annotationType() == type) return value;
        return null;
    }

    private static Method method(Class<?> owner, String name, Class<?> returns, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = owner.getMethod(name, parameters);
        if (method.getReturnType() != returns || Modifier.isStatic(method.getModifiers())) throw new NoSuchMethodException(name);
        return method;
    }
}
