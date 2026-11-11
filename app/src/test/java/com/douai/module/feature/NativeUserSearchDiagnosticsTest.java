package com.douai.module.feature;

import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 28)
public final class NativeUserSearchDiagnosticsTest {
    @Test public void failedAndSuccessfulNativePagesHaveTraceableOutcomesWithoutPrivateSearchData() throws Exception {
        List<String> captured = Collections.synchronizedList(new ArrayList<>());
        assertTrue(DouAiLog.awaitIdle(3000));
        DebugLog.setEnabled(true);
        DouAiLog.setFrameworkSink((priority, json) -> captured.add(json));
        FollowListSnapshot.reset(); FollowListSnapshot.activateAccount("42");
        QueueExecutor workers = new QueueExecutor();
        ArrayDeque<Runnable> main = new ArrayDeque<>();
        AtomicInteger calls = new AtomicInteger();
        NativeUserSearch service = new NativeUserSearch((query, cursor, id, current) -> {
            int attempt = calls.incrementAndGet();
            if (attempt == 1) throw new IllegalStateException("private-exception-body Cookie=private-cookie");
            if (attempt == 2) throw new NoClassDefFoundError("private-class-message");
            return new NativeUserSearch.Page(Collections.singletonList(
                    new ProfileListEntry("818181818181", "private-result-name", "", "private-douyin-id")),
                    7, true, "private-search-token");
        }, FollowListSnapshot::current, workers, main::add);
        try {
            List<NativeUserSearch.Update> updates = new ArrayList<>();
            for (int attempt = 0; attempt < 3; attempt++) {
                service.request("private-keyword", 0, "", updates::add);
                workers.runAll();
                while (!main.isEmpty()) main.remove().run();
            }
            assertEquals(3, updates.size());
            assertNull(updates.get(0).page); assertNull(updates.get(1).page);
            assertNotNull(updates.get(2).page);
            assertTrue(DouAiLog.awaitIdle(3000));
            List<JSONObject> ends = new ArrayList<>();
            synchronized (captured) {
                for (String line : captured) {
                    JSONObject event = new JSONObject(line);
                    if ("user_search".equals(event.optString("feature_id"))
                            && "operation_finished".equals(event.optString("event"))) ends.add(event);
                }
            }
            assertEquals("Every actual native attempt needs an outcome for log triage", 3, ends.size());
            assertEquals("failed", ends.get(0).getString("outcome"));
            assertTrue(ends.get(0).getString("evidence").contains("IllegalStateException"));
            assertEquals("failed", ends.get(1).getString("outcome"));
            assertTrue(ends.get(1).getString("evidence").contains("NoClassDefFoundError"));
            assertEquals("success", ends.get(2).getString("outcome"));
            assertTrue(ends.get(2).getString("evidence").contains("result_count=1"));
            assertTrue(ends.get(2).getString("evidence").contains("has_more=true"));
            String log = String.join("", captured);
            for (String secret : new String[]{"private-keyword", "private-exception-body", "private-cookie",
                    "private-class-message", "818181818181", "private-result-name", "private-douyin-id", "private-search-token"}) {
                assertFalse(secret, log.contains(secret));
            }
        } finally {
            service.close(); FollowListSnapshot.reset();
            DouAiLog.awaitIdle(3000); DouAiLog.setFrameworkSink(null); DebugLog.setEnabled(false);
        }
    }

    private static final class QueueExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> pending = new ArrayDeque<>();
        boolean closed;
        @Override public void execute(Runnable action) { pending.add(action); }
        void runAll() { while (!pending.isEmpty()) pending.remove().run(); }
        @Override public void shutdown() { closed = true; }
        @Override public List<Runnable> shutdownNow() { closed = true; pending.clear(); return Collections.emptyList(); }
        @Override public boolean isShutdown() { return closed; }
        @Override public boolean isTerminated() { return closed && pending.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
    }
}
