package com.douai.module.config;

import android.content.SharedPreferences;
import com.douai.module.DouAiModule;
import com.douai.module.feature.DownloadActionHook;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.scan.HostWorkQueue;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Uses baseline APIs only, so the original sequential-dispatch defect can fail an actual assertion. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigurationPartialDispatchRegressionTest {
    @Test public void synchronousRelationshipFailureCannotSkipTheIndependentDownloadTail() throws Exception {
        DouAiModule module = new DouAiModule();
        module.attachFramework((XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, args) -> null), () -> { });
        Method relationships = ConfigurationDispatchTest.class.getDeclaredMethod("relationships");
        relationships.setAccessible(true);
        NativeFollowLists lists = (NativeFollowLists) relationships.invoke(null);
        Constructor<DownloadActionHook> constructor = DownloadActionHook.class.getDeclaredConstructor(
                Method.class, FeatureSettings.class, Consumer.class);
        constructor.setAccessible(true);
        DownloadActionHook download = constructor.newInstance(null, FeatureSettings.defaults(), (Consumer<String>) ignored -> { });
        Field active = NativeFollowLists.class.getDeclaredField("active");
        active.setAccessible(true);
        Object previousActive = active.get(null);
        AtomicInteger rejected = new AtomicInteger();
        ScheduledThreadPoolExecutor noHostWork = new ScheduledThreadPoolExecutor(1) {
            @Override public void execute(Runnable command) {
                rejected.incrementAndGet();
                throw new RejectedExecutionException("fixture local refresh enqueue failure");
            }
        };
        ((ScheduledThreadPoolExecutor) field(lists, "worker")).shutdownNow();
        set(lists, "worker", noHostWork);
        set(module, "nativeFollowLists", lists);
        set(module, "downloadActionHook", download);
        active.set(null, lists);
        try {
            Map<String,Object> values = new HashMap<>();
            values.put("download_enabled", true);
            values.put("download_name_template", "before");
            update(module, values);
            assertEquals("before", ((LocalOptions) field(download, "options")).text("download_name_template", ""));
            values.put("relation_lists_refresh_token", "force-local-failure");
            values.put("download_name_template", "after-independent-failure");
            update(module, values);
            assertEquals("Only a local enqueue was attempted; no account lookup or host fetch runs", 1, rejected.get());
            assertEquals("A preceding failure must not suppress an unrelated later consumer",
                    "after-independent-failure", ((LocalOptions) field(download, "options")).text("download_name_template", ""));
            assertSame(field(module, "localOptions"), field(download, "options"));
        } finally {
            try {
                // The baseline has no ledger close API; absence here is not a test skip or assertion substitute.
                try {
                    Method close = DouAiModule.class.getDeclaredMethod("closeConfigurationPublication");
                    close.setAccessible(true); close.invoke(module);
                } catch (NoSuchMethodException baseline) { }
                ((HostWorkQueue) field(module, "hostWorkQueue")).close();
                // This fixture never changed the prior account snapshot; do not let close() clear its owner.
                active.set(null, previousActive);
                download.close(); lists.close();
            } finally { noHostWork.shutdownNow(); active.set(null, previousActive); ShadowLooper.idleMainLooper(); }
        }
    }

    private static void update(DouAiModule module, Map<String,Object> values) throws Exception {
        Map<String,Object> captured = new HashMap<>(values);
        SharedPreferences preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    if ("getAll".equals(method.getName())) return captured;
                    throw new AssertionError("Unexpected live getter: " + method.getName());
                });
        Method update = DouAiModule.class.getDeclaredMethod("updateConfiguration", SharedPreferences.class);
        update.setAccessible(true); update.invoke(module, preferences);
        for (int pass=0; pass<3; pass++) {
            ((HostWorkQueue) field(module, "hostWorkQueue")).submit(() -> { }).get(5,TimeUnit.SECONDS);
            ShadowLooper.idleMainLooper();
        }
    }

    private static Object field(Object target,String name) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);return field.get(target);
    }
    private static void set(Object target,String name,Object value) throws Exception {
        Field field=target.getClass().getDeclaredField(name);field.setAccessible(true);field.set(target,value);
    }
}
