package com.douai.module.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeUserSearchTest {
    private final QueueExecutor workers = new QueueExecutor();
    private final ArrayDeque<Runnable> main = new ArrayDeque<>();
    private final FixtureSource source = new FixtureSource();
    private NativeUserSearch search;
    @Before public void setup() {
        FollowListSnapshot.reset(); FollowListSnapshot.activateAccount("42");
        search = new NativeUserSearch(source, FollowListSnapshot::current, workers, main::add);
    }
    @After public void cleanup() { search.close(); FollowListSnapshot.reset(); }
    @Test public void searchesUnfollowedUsersAndDeliversOnlyOnMainWithoutChangingTheRawKeyword() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        NativeUserSearch.search("  TestUser  ", 0, "", updates::add);
        assertTrue(source.calls.isEmpty());
        workers.runAll();
        assertTrue(updates.isEmpty());
        drain();
        assertEquals(Collections.singletonList("TestUser/0/"), source.calls);
        assertEquals("900", updates.get(0).page.entries.get(0).uid);
        assertEquals("search-1", updates.get(0).page.searchId);
    }
    @Test public void paginationPreservesTheQueryCursorAndSearchId() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        search.request("TestUser", 0, "", updates::add);
        workers.runAll(); drain();
        source.page = new NativeUserSearch.Page(Collections.emptyList(), 40, false, "search-2");
        search.request("TestUser", 20, "search-1", updates::add);
        workers.runAll(); drain();
        assertEquals(Arrays.asList("TestUser/0/", "TestUser/20/search-1"), source.calls);
        assertEquals(40, updates.get(1).page.cursor);
        assertFalse(updates.get(1).page.hasMore);
    }
    @Test public void previousAccountContinuationCannotBeUsedAfterSwitchingAccounts() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        search.request("TestUser", 0, "", updates::add);
        workers.runAll(); drain();
        FollowListSnapshot.activateAccount("43");
        search.request("TestUser", 20, "search-1", updates::add);
        workers.runAll(); drain();
        assertEquals(1, source.calls.size());
        assertNull(updates.get(1).page);
        assertFalse(updates.get(1).error.isEmpty());
    }
    @Test public void cancelledQueuedAndAlreadyFinishedRequestsNeverPublishLateResults() throws Exception {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        AutoCloseable queued = search.request("first", 0, "", updates::add);
        queued.close(); workers.runAll(); drain();
        assertTrue(source.calls.isEmpty());
        AutoCloseable finished = search.request("second", 0, "", updates::add);
        workers.runAll(); finished.close(); drain();
        assertTrue(updates.isEmpty());
    }
    @Test public void accountAbaDuringWorkAndBeforeMainDeliveryRejectsTheOldResults() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        source.before = NativeUserSearchTest::switchAwayAndBack;
        search.request("during", 0, "", updates::add);
        workers.runAll(); drain();
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).accountChanged);
        assertNull(updates.get(0).page);
        source.before = () -> { };
        updates.clear();
        search.request("before-main", 0, "", updates::add);
        workers.runAll(); switchAwayAndBack(); drain();
        assertEquals(1, updates.size());
        assertTrue(updates.get(0).accountChanged);
        assertNull(updates.get(0).page);
    }
    @Test public void nativeFailuresAndMissingHostClassesEndWithRetryableErrors() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        source.before = () -> { throw new NoClassDefFoundError("fixture"); };
        search.request("first", 0, "", updates::add);
        workers.runAll(); drain();
        assertFalse(updates.get(0).error.isEmpty());
        assertNull(updates.get(0).page);
        source.before = () -> { };
        search.request("retry", 0, "", updates::add);
        workers.runAll(); drain();
        assertNotNull(updates.get(1).page);
    }
    @Test public void invalidQueriesAndRepeatedCursorsCannotMakeUnboundedRequests() {
        List<NativeUserSearch.Update> updates = new ArrayList<>();
        search.request(" ", 0, "", updates::add);
        search.request("x", -1, "", updates::add);
        workers.runAll(); drain();
        assertTrue(source.calls.isEmpty());
        search.request("x", 0, "", updates::add);
        workers.runAll(); drain();
        source.page = new NativeUserSearch.Page(Collections.emptyList(), 20, true, "search-1");
        search.request("x", 20, "search-1", updates::add);
        workers.runAll(); drain();
        assertNull(updates.get(updates.size() - 1).page);
        assertFalse(updates.get(updates.size() - 1).error.isEmpty());
        assertEquals(2, source.calls.size());
    }
    @Test public void anOlderCapturedAccountCannotClearANewAccountSearch() {
        java.util.concurrent.atomic.AtomicBoolean hook = new java.util.concurrent.atomic.AtomicBoolean(true);
        QueueExecutor batch = new QueueExecutor();
        List<NativeUserSearch.Update> old = new ArrayList<>(), fresh = new ArrayList<>();
        search = new NativeUserSearch(source, () -> {
            FollowListSnapshot.State captured = FollowListSnapshot.current();
            if (hook.getAndSet(false)) {
                FollowListSnapshot.activateAccount("43");
                NativeUserSearch.search("fresh", 0, "", fresh::add);
            }
            return captured;
        }, batch, main::add);
        search.request("old", 0, "", old::add);
        batch.runAll(); drain();
        assertEquals(Collections.singletonList("fresh/0/"), source.calls);
        assertEquals(1, fresh.size());
        assertNotNull(fresh.get(0).page);
        assertFalse(fresh.get(0).accountChanged);
        assertTrue(old.get(0).accountChanged);
    }
    @Test public void replacingTheServiceRejectsResultsFromThePreviousInstance() {
        List<NativeUserSearch.Update> old = new ArrayList<>();
        search.request("old", 0, "", old::add);
        workers.runAll();
        NativeUserSearch replacement = new NativeUserSearch(new FixtureSource(), FollowListSnapshot::current,
                new QueueExecutor(), main::add);
        try { drain(); assertTrue(old.isEmpty()); }
        finally { replacement.close(); }
    }
    private void drain() { while (!main.isEmpty()) main.remove().run(); }
    private static void switchAwayAndBack() {
        FollowListSnapshot.activateAccount("43"); FollowListSnapshot.activateAccount("42");
    }
    private static final class FixtureSource implements NativeUserSearch.Source {
        final List<String> calls = new ArrayList<>();
        Runnable before = () -> { };
        NativeUserSearch.Page page = new NativeUserSearch.Page(Arrays.asList(
                new ProfileListEntry("900", "未关注的用户", "https://p3.douyinpic.com/a.jpg", "new.user")),
                20, true, "search-1");
        @Override public NativeUserSearch.Page search(String query, long cursor, String searchId, BooleanSupplier current) {
            calls.add(query + "/" + cursor + "/" + searchId);
            before.run(); return page;
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
