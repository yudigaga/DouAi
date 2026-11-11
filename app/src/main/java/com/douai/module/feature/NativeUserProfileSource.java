package com.douai.module.feature;

import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostCandidate;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;

/** Calls the host's own profile repository; its requests expose no cancellation handle. */
final class NativeUserProfileSource implements NativeUserProfiles.Source {
    private static final String ROOT = "com.ss.android.ugc.aweme.im.sdk.core.";
    interface TypeResolver { Class<?> resolve(String name) throws ClassNotFoundException; }
    private final TypeResolver types;
    private final HostScanReport report;
    private final long timeoutNanos;
    private final HostReflectionCache accessors = new HostReflectionCache();
    private volatile Api api;

    NativeUserProfileSource(ClassLoader loader, HostScanReport report) {
        this(name -> Class.forName(name, false, loader), report, 20_000);
    }

    NativeUserProfileSource(TypeResolver types, HostScanReport report, long timeoutMillis) {
        if (types == null || timeoutMillis < 1) throw new IllegalArgumentException("Invalid profile source");
        this.types = types;
        this.report = report;
        this.timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
    }

    @Override public ProfileListEntry cached(String uid) {
        String selected = FollowListSnapshot.normalizeUid(uid);
        if (selected.isEmpty()) return null;
        try {
            HostCandidate target = HostTargets.candidate(report, "relation_im_user_cache");
            String descriptor = target.getClassDescriptor();
            Class<?> owner = types.resolve(descriptor.substring(1, descriptor.length() - 1).replace('/', '.'));
            Method cache = accessors.declaredMethod(owner, target.getMethodName(), target.getMethodDescriptor());
            if (cache == null || !Modifier.isStatic(cache.getModifiers())) return null;
            Object instance = cache.invoke(null);
            Object user = cache.getReturnType().getMethod("get", Object.class).invoke(instance, selected);
            return profile(selected, user);
        } catch (Exception | LinkageError unavailable) { return null; }
    }

    @Override public ProfileListEntry fetch(String uid, BooleanSupplier current) throws Exception {
        String selected = FollowListSnapshot.normalizeUid(uid);
        if (selected.isEmpty()) throw new IllegalArgumentException("Invalid profile UID");
        checkCurrent(current);
        Api nativeApi = api();
        Object builder = nativeApi.builder.getConstructor().newInstance();
        nativeApi.builder.getMethod("setUid", String.class).invoke(builder, selected);
        nativeApi.builder.getMethod("setSource", String.class).invoke(builder, "douai_user_profile");
        nativeApi.builder.getMethod("setScene", nativeApi.scene.getClass()).invoke(builder, nativeApi.scene);
        Object param = nativeApi.builder.getMethod("build").invoke(builder);
        Completion completion = new Completion();
        Object callback = Proxy.newProxyInstance(nativeApi.function.getClassLoader(),
                new Class<?>[]{nativeApi.function}, (proxy, method, arguments) -> {
                    switch (method.getName()) {
                        case "invoke":
                            completion.complete(arguments == null || arguments.length == 0 ? null : arguments[0]);
                            return nativeApi.unit;
                        case "equals": return arguments != null && arguments.length == 1 && proxy == arguments[0];
                        case "hashCode": return System.identityHashCode(proxy);
                        case "toString": return "DouAiUserProfileCallback";
                        default: return null;
                    }
                });
        try {
            checkCurrent(current);
            long deadline = System.nanoTime() + timeoutNanos;
            Object immediate;
            try { immediate = nativeApi.getUser.invoke(null, param, callback); }
            catch (InvocationTargetException failed) {
                Throwable cause = failed.getCause();
                if (cause instanceof Exception) throw (Exception) cause;
                throw new IllegalStateException("Native profile request failed", cause);
            }
            if (immediate != null) completion.complete(immediate);
            while (completion.done.getCount() != 0) {
                checkCurrent(current);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Native profile request timed out");
                try { completion.done.await(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS); }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw interrupted;
                }
            }
            checkCurrent(current);
            ProfileListEntry result = profile(selected, completion.value);
            if (result == null) throw new IllegalStateException("Native profile result is empty or has another UID");
            return result;
        } finally {
            // The host does not return its Job: detach results without claiming to cancel its network request.
            completion.detach();
        }
    }

    private synchronized Api api() throws Exception {
        if (api == null) api = new Api(types);
        return api;
    }

    private static ProfileListEntry profile(String uid, Object user) {
        if (user == null || !uid.equals(HostTargets.invoke(user, "getUid"))) return null;
        return ProfileListEntry.fromUser(uid, user, null);
    }

    private static void checkCurrent(BooleanSupplier current) throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Profile lookup interrupted");
        if (!current.getAsBoolean()) throw new InterruptedException("Profile lookup account changed or closed");
    }

    private static final class Api {
        final Class<?> builder, function;
        final Object scene, unit;
        final Method getUser;

        Api(TypeResolver types) throws Exception {
            builder = types.resolve(ROOT + "UserRequestParam$Builder");
            function = types.resolve("kotlin.jvm.functions.Function1");
            Class<?> param = types.resolve(ROOT + "UserRequestParam");
            Class<?> user = types.resolve("com.ss.android.ugc.aweme.im.service.model.IMUser");
            scene = types.resolve(ROOT + "UserRequestParam$Scene").getField("NET").get(null);
            unit = types.resolve("kotlin.Unit").getField("INSTANCE").get(null);
            getUser = types.resolve(ROOT + "IMUserRepository").getMethod("getUser", param, function);
            if (!function.isInterface() || !Modifier.isStatic(getUser.getModifiers()) || getUser.getReturnType() != user) {
                throw new NoSuchMethodException("Native IMUserRepository.getUser contract changed");
            }
        }
    }

    private static final class Completion {
        final CountDownLatch done = new CountDownLatch(1);
        volatile Object value;
        private boolean accepting = true;

        synchronized void complete(Object user) {
            if (!accepting) return;
            accepting = false;
            value = user;
            done.countDown();
        }

        synchronized void detach() {
            accepting = false;
            value = null;
        }
    }
}
