package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import com.google.gson.annotations.SerializedName;

import org.junit.Before;
import org.junit.Test;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

public final class NativeUserProfileSourceTest {
    private static final String ROOT = "com.ss.android.ugc.aweme.im.sdk.core.";

    @Before public void reset() {
        Repository.calls = 0;
        Repository.action = (param, callback) -> null;
        Repository.cached = null;
    }

    @Test public void asynchronousNativeResultUsesNetSceneAndHostUnitAndRawIdentity() throws Exception {
        AtomicReference<Object> unit = new AtomicReference<>();
        CountDownLatch delivered = new CountDownLatch(1);
        Repository.action = (param, callback) -> {
            assertEquals("100", param.uid);
            assertEquals(Scene.NET, param.scene);
            assertFalse(param.source.isEmpty());
            new Thread(() -> {
                unit.set(callback.invoke(new User("100", "真实昵称")));
                delivered.countDown();
            }).start();
            return null;
        };
        ProfileListEntry result = source(1000).fetch("100", () -> true);
        assertTrue(delivered.await(1, TimeUnit.SECONDS));
        assertSame(Unit.INSTANCE, unit.get());
        assertEquals("真实昵称", result.name);
        assertEquals("账户备注", ProfileListEntryTest.remark(result));
        assertEquals("public.id", result.douyinId);
        assertEquals("https://p3.douyinpic.com/avatar.jpg", result.avatarUrl);
    }

    @Test public void synchronousReturnAndInlineCallbackConvergeOnlyOnce() throws Exception {
        Repository.action = (param, callback) -> {
            assertSame(Unit.INSTANCE, callback.invoke(new User("100", "first")));
            callback.invoke(new User("999", "duplicate"));
            return new User("999", "synchronous duplicate");
        };
        assertEquals("first", source(1000).fetch("100", () -> true).name);

        Repository.action = (param, callback) -> new User("100", "return only");
        assertEquals("return only", source(1000).fetch("100", () -> true).name);
    }

    @Test public void mismatchedImmediateNativeResultsRemainFailures() throws Exception {
        Repository.action = (param, callback) -> new User("999", "wrong user");
        assertThrows(IllegalStateException.class, () -> source(1000).fetch("100", () -> true));
    }

    @Test public void anExplicitNullCallbackIsANoResultButAWrongUidCallbackStillFails() throws Exception {
        Repository.action = (param, callback) -> { callback.invoke(null); return null; };
        assertNull("An explicit empty response must reach the existing unavailable-result branch",
                source(1000).fetch("100", () -> true));
        Repository.action = (param, callback) -> { callback.invoke(new User("999", "wrong user")); return null; };
        assertThrows(IllegalStateException.class, () -> source(1000).fetch("100", () -> true));
        assertEquals(2, Repository.calls);
    }

    @Test public void timeoutDetachesCallbackAndTheNextRequestIsIndependent() throws Exception {
        AtomicReference<Function1> old = new AtomicReference<>();
        Repository.action = (param, callback) -> { old.set(callback); return null; };
        NativeUserProfileSource source = source(20);
        assertThrows(TimeoutException.class, () -> source.fetch("100", () -> true));
        assertSame(Unit.INSTANCE, old.get().invoke(new User("100", "late")));
        Repository.action = (param, callback) -> new User("100", "next");
        assertEquals("next", source.fetch("100", () -> true).name);
    }

    @Test public void changedAccountRejectsWorkBeforeTheNativeCallAndWhileWaiting() throws Exception {
        NativeUserProfileSource source = source(2000);
        assertThrows(InterruptedException.class, () -> source.fetch("100", () -> false));
        assertEquals(0, Repository.calls);

        AtomicBoolean current = new AtomicBoolean(true);
        CountDownLatch started = new CountDownLatch(1);
        AtomicReference<Function1> old = new AtomicReference<>();
        Repository.action = (param, callback) -> {
            old.set(callback);
            current.set(false);
            started.countDown();
            return null;
        };
        assertThrows(InterruptedException.class, () -> source.fetch("100", current::get));
        assertTrue(started.await(1, TimeUnit.SECONDS));
        assertSame(Unit.INSTANCE, old.get().invoke(new User("100", "late")));
    }

    @Test public void interruptStopsWaitingAndRestoresTheInterruptFlag() throws Exception {
        CountDownLatch called = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Repository.action = (param, callback) -> { called.countDown(); return null; };
        NativeUserProfileSource source = source(2000);
        Thread worker = new Thread(() -> {
            try { source.fetch("100", () -> true); }
            catch (Throwable thrown) {
                failure.set(thrown);
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        worker.start();
        assertTrue(called.await(1, TimeUnit.SECONDS));
        worker.interrupt();
        worker.join(1000);
        assertFalse(worker.isAlive());
        assertTrue(failure.get() instanceof InterruptedException);
        assertTrue(interrupted.get());
    }

    @Test public void cacheReadsOnlyTheScanProvenCacheAndVerifiesTheReturnedUid() throws Exception {
        NativeUserProfileSource source = source(1000);
        assertNull(source.cached("100"));
        Repository.cached = new User("999", "wrong");
        assertNull(source.cached("100"));
        Repository.cached = new User("100", "cached");
        assertEquals("cached", source.cached("100").name);
        assertEquals("账户备注", ProfileListEntryTest.remark(source.cached("100")));
        assertEquals(0, Repository.calls);
    }

    @Test public void invalidUidAndMissingNativeContractNeverBecomeSuccessfulFallbacks() throws Exception {
        NativeUserProfileSource source = source(1000);
        assertThrows(IllegalArgumentException.class, () -> source.fetch("group:100", () -> true));
        assertEquals(0, Repository.calls);
        NativeUserProfileSource absent = new NativeUserProfileSource(name -> {
            throw new ClassNotFoundException(name);
        }, report(), 20);
        assertThrows(ClassNotFoundException.class, () -> absent.fetch("100", () -> true));
        assertNull(absent.cached("100"));
    }

    private static NativeUserProfileSource source(long timeoutMillis) throws Exception {
        return new NativeUserProfileSource(name -> {
            switch (name) {
                case ROOT + "UserRequestParam": return Param.class;
                case ROOT + "UserRequestParam$Builder": return Builder.class;
                case ROOT + "UserRequestParam$Scene": return Scene.class;
                case ROOT + "IMUserRepository": return Repository.class;
                case "com.ss.android.ugc.aweme.im.service.model.IMUser": return User.class;
                case "kotlin.jvm.functions.Function1": return Function1.class;
                case "kotlin.Unit": return Unit.class;
                default: return Class.forName(name);
            }
        }, report(), timeoutMillis);
    }

    private static HostScanReport report() throws Exception {
        Method method = Repository.class.getMethod("cache");
        HostCandidate candidate = new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(Repository.class), method.getName(), HostTargets.descriptor(method));
        return new HostScanReport("fixture", Collections.singletonList(new RuleScanResult(
                "relation_im_user_cache", "profile", Collections.singletonList(candidate))), false);
    }

    public interface Function1 { Object invoke(Object user); }
    public static final class Unit { public static final Unit INSTANCE = new Unit(); }
    public enum Scene { CACHE_DB_NET, NET }
    public static final class Param { String uid, source; Scene scene = Scene.CACHE_DB_NET; }
    public static final class Builder {
        private final Param param = new Param();
        public Builder setUid(String uid) { param.uid = uid; return this; }
        public Builder setSource(String source) { param.source = source; return this; }
        public Builder setScene(Scene scene) { param.scene = scene; return this; }
        public Param build() { return param; }
    }
    interface Action { User get(Param param, Function1 callback); }
    public static final class Repository {
        static int calls;
        static Action action;
        static User cached;
        public static Cache cache() { return new Cache(); }
        public static User getUser(Param param, Function1 callback) {
            calls++;
            return action.get(param, callback);
        }
    }
    public static final class Cache { public Object get(Object uid) { return Repository.cached; } }
    public static final class User {
        final String uid;
        @SerializedName("nickname") final String raw;
        @SerializedName("remark_name") final String remark = "账户备注";
        @SerializedName("unique_id") final String publicId = "public.id";
        User(String uid, String raw) { this.uid = uid; this.raw = raw; }
        public String getUid() { return uid; }
        public String getNickname() { return "备注覆盖"; }
        public Avatar getAvatarThumb() { return new Avatar(); }
    }
    public static final class Avatar {
        public java.util.List<String> getUrlList() {
            return Collections.singletonList("https://p3.douyinpic.com/avatar.jpg");
        }
    }
}
