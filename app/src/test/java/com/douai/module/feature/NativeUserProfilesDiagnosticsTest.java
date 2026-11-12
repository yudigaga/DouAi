package com.douai.module.feature;

import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class NativeUserProfilesDiagnosticsTest {
    @Test public void aMixedBatchHasOneCountedNonSuccessTerminalWithoutLoggingIdentityValues() throws Exception {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        assertTrue(DouAiLog.awaitIdle(3000));
        DebugLog.setEnabled(true);
        DouAiLog.setFrameworkSink((priority, json) -> captured.add(json));
        FollowListSnapshot.reset(); FollowListSnapshot.activateAccount("424242424242");
        QueueExecutor workers = new QueueExecutor();
        String first = "515151515151", second = "626262626262", failed = "737373737373";
        NativeUserProfiles service = new NativeUserProfiles(new NativeUserProfiles.Source() {
            @Override public ProfileListEntry cached(String uid) { return null; }
            @Override public ProfileListEntry fetch(String uid, java.util.function.BooleanSupplier current) throws Exception {
                if (uid.equals(failed)) throw new java.io.IOException("private-exception-body Cookie=private-cookie");
                return new ProfileListEntry(uid, "private-nickname", uid.equals(second) ? ""
                        : "https://p3.douyinpic.com/private-avatar.jpg", "private-douyin-id", "private-remark");
            }
        }, FollowListSnapshot::current, () -> 1000, workers, Runnable::run);
        try {
            List<NativeUserProfiles.Update> updates = new ArrayList<>();
            service.request(Arrays.asList(new ProfileListEntry(first, "", ""), new ProfileListEntry(second, "", ""),
                    new ProfileListEntry(failed, "private-cached-name", "https://p3.douyinpic.com/private-cached-avatar.jpg",
                            "private-cached-id", "private-cached-remark")), true, updates::add);
            workers.runAll();
            assertFalse(updates.get(updates.size() - 1).loading);
            assertFalse(updates.get(updates.size() - 1).error.isEmpty());
            assertTrue(DouAiLog.awaitIdle(3000));
            List<JSONObject> ends = new ArrayList<>();
            synchronized (captured) {
                for (String line : captured) {
                    JSONObject item = new JSONObject(line);
                    if ("user_profiles".equals(item.optString("feature_id"))
                            && "operation_finished".equals(item.optString("event"))) ends.add(item);
                }
            }
            assertEquals("One profile batch needs exactly one terminal outcome", 1, ends.size());
            JSONObject end = ends.get(0);
            assertNotEquals("A partial/failed batch cannot claim all profiles succeeded", "success", end.getString("outcome"));
            String counts = end.getString("evidence");
            for (String expected : new String[]{"requested_count=3", "complete_count=2", "incomplete_count=1",
                    "missing_name_count=0", "missing_avatar_count=1", "missing_douyin_id_count=0",
                    "fetch_failed_count=1", "timeout_count=0", "unreturned_count=0"}) {
                assertTrue(expected + ": " + counts, counts.contains(expected));
            }
            String log = String.join("", captured);
            for (String secret : new String[]{"424242424242", first, second, failed, "private-nickname", "private-remark",
                    "private-douyin-id", "private-avatar.jpg", "private-cached-name", "private-cached-id",
                    "private-cached-remark", "private-cached-avatar.jpg", "private-exception-body", "private-cookie"}) {
                assertFalse(secret, log.contains(secret));
            }
        } finally {
            service.close(); FollowListSnapshot.reset();
            DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(false);
        }
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> queue = new ArrayDeque<>();
        boolean closed;
        @Override public void execute(Runnable action) { queue.add(action); }
        void runAll() { while (!queue.isEmpty()) queue.remove().run(); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; queue.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed && queue.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
