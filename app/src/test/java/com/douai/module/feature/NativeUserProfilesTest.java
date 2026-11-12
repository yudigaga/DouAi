package com.douai.module.feature;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeUserProfilesTest {
    private final QueueExecutor workers = new QueueExecutor();
    private final FixtureSource source = new FixtureSource();
    private final AtomicLong now = new AtomicLong(1000);
    private NativeUserProfiles profiles;

    @Before public void setup() {
        FollowListSnapshot.reset();
        FollowListSnapshot.activateAccount("42");
        profiles = new NativeUserProfiles(source, FollowListSnapshot::current, now::get, workers, Runnable::run);
    }
    @After public void cleanup() { profiles.close(); FollowListSnapshot.reset(); }

    @Test public void missingManualUidLoadsTheRealProfileAndNeverChangesItsUid() {
        source.remote.put("100", full("100", "真实昵称"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        NativeUserProfiles.load(Collections.singletonList(new ProfileListEntry("100", "", "")), false, updates::add);
        workers.runAll();
        assertEquals(Collections.singletonList("100"), source.calls);
        ProfileListEntry loaded = combined(updates).get("100");
        assertEquals("真实昵称", loaded.name);
        assertEquals("account.100", loaded.douyinId);
        assertEquals("100", loaded.selectionKey);
        assertFalse(updates.get(updates.size() - 1).loading);
    }

    @Test public void localCacheCanFillAnOldEmptyContactSnapshotWithoutNetwork() {
        source.local.put("100", full("100", "本地真实昵称"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, updates::add);
        workers.runAll();
        assertTrue(source.calls.isEmpty());
        assertNotNull(combined(updates).get("100"));
        assertEquals("本地真实昵称", combined(updates).get("100").name);
    }

    @Test public void sharedRequestsDeduplicateAndClosingOneConsumerKeepsTheOtherAlive() throws Exception {
        source.remote.put("100", full("100", "Alice"));
        List<NativeUserProfiles.Update> first = new ArrayList<>(), second = new ArrayList<>();
        AutoCloseable cancelled = profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, first::add);
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, second::add);
        cancelled.close();
        int before = first.size();
        workers.runAll();
        assertEquals(before, first.size());
        assertEquals(Collections.singletonList("100"), source.calls);
        assertEquals("Alice", combined(second).get("100").name);
    }

    @Test public void failureIsRetryableAndForceRefreshBypassesCachedSuccess() {
        source.fail = true;
        List<NativeUserProfiles.Update> failed = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, failed::add);
        workers.runAll();
        assertFalse(failed.get(failed.size() - 1).error.isEmpty());
        source.fail = false;
        source.remote.put("100", full("100", "恢复"));
        List<NativeUserProfiles.Update> retried = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), true, retried::add);
        workers.runAll();
        assertEquals("恢复", combined(retried).get("100").name);
        source.remote.put("100", full("100", "更新"));
        List<NativeUserProfiles.Update> refreshed = new ArrayList<>();
        profiles.request(Collections.singletonList(full("100", "旧快照")), true, refreshed::add);
        workers.runAll();
        assertEquals("更新", combined(refreshed).get("100").name);
        assertEquals(3, source.calls.size());
    }

    @Test public void wrongUidIsNeverAccepted() {
        source.remote.put("100", full("999", "错误用户"));
        List<NativeUserProfiles.Update> mismatch = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, mismatch::add);
        workers.runAll();
        assertFalse(combined(mismatch).containsKey("999"));
        assertFalse(mismatch.get(mismatch.size() - 1).error.isEmpty());
    }
    @Test public void accountAbaResultsCannotPublish() {
        source.remote.put("100", full("100", "旧账号结果"));
        source.beforeFetch = () -> {
            FollowListSnapshot.activateAccount("43");
            FollowListSnapshot.activateAccount("42");
        };
        List<NativeUserProfiles.Update> stale = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), true, stale::add);
        workers.runAll();
        assertTrue(combined(stale).isEmpty());
    }

    @Test public void groupsAndNicknameOnlyRulesAreNeverFetchedAsUsers() {
        profiles.request(Arrays.asList(ProfileListEntry.conversation("100", "群聊", ""),
                ProfileListEntry.named("群昵称")), true, ignored -> { });
        workers.runAll();
        assertTrue(source.calls.isEmpty());
    }

    @Test public void cancellingEveryConsumerPreventsQueuedNetworkRequests() throws Exception {
        AutoCloseable request = profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, ignored -> { });
        request.close();
        workers.runAll();
        assertTrue(source.calls.isEmpty());
    }

    @Test public void reopeningDuringCooldownDoesNotKeepExtendingTheRetryDeadline() {
        source.fail = true;
        List<ProfileListEntry> entries = Collections.singletonList(new ProfileListEntry("100", "", ""));
        profiles.request(entries, false, ignored -> { });
        workers.runAll();
        now.addAndGet(29_000);
        profiles.request(entries, false, ignored -> { });
        workers.runAll();
        assertEquals(1, source.calls.size());
        now.addAndGet(2_000);
        profiles.request(entries, false, ignored -> { });
        workers.runAll();
        assertEquals(2, source.calls.size());
    }

    @Test public void accountChangeBeforeQueuedMainResultFinishesTheOldLoadingState() {
        ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
        QueueExecutor batch = new QueueExecutor();
        profiles = new NativeUserProfiles(source, FollowListSnapshot::current, now::get, batch, mainQueue::add);
        source.remote.put("100", full("100", "Alice"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, updates::add);
        while (!mainQueue.isEmpty()) mainQueue.remove().run();
        assertTrue(updates.get(updates.size() - 1).loading);
        batch.runAll();
        FollowListSnapshot.activateAccount("43");
        while (!mainQueue.isEmpty()) mainQueue.remove().run();
        assertTrue(updates.get(updates.size() - 1).accountChanged);
        assertFalse(updates.get(updates.size() - 1).loading);
        assertTrue(combined(updates).isEmpty());
    }

    @Test public void expiredFullSuccessQueriesAgainInsteadOfExtendingStaleDataForever() {
        source.remote.put("100", full("100", "旧资料"));
        List<ProfileListEntry> entries = Collections.singletonList(new ProfileListEntry("100", "", ""));
        profiles.request(entries, false, ignored -> { });
        workers.runAll();
        now.addAndGet(16 * 60_000);
        source.remote.put("100", full("100", "新资料"));
        List<NativeUserProfiles.Update> updated = new ArrayList<>();
        profiles.request(entries, false, updated::add);
        workers.runAll();
        assertEquals(2, source.calls.size());
        assertEquals("新资料", combined(updated).get("100").name);
    }
    @Test public void missingHostClassesFailVisiblyAndDoNotBlockTheRemainingQueue() {
        source.beforeFetch = () -> { throw new NoClassDefFoundError("fixture_host_type"); };
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""),
                new ProfileListEntry("200", "", ""), new ProfileListEntry("300", "", "")), false, updates::add);
        workers.runAll();
        assertEquals(3, source.calls.size());
        assertFalse(updates.get(updates.size() - 1).loading);
        assertFalse(updates.get(updates.size() - 1).error.isEmpty());
    }

    @Test public void joiningRequestNeverLetsItsOldInitialCacheOverwriteAResultThatFinishedBeforeEnqueue() {
        QueueExecutor batch = new QueueExecutor();
        ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
        AtomicReference<Runnable> beforeEnqueue = new AtomicReference<>();
        profiles = new NativeUserProfiles(source, FollowListSnapshot::current, now::get, batch, action -> {
            Runnable hook = beforeEnqueue.getAndSet(null);
            if (hook != null) hook.run();
            mainQueue.add(action);
        });
        List<ProfileListEntry> entries = Collections.singletonList(new ProfileListEntry("100", "", ""));
        source.remote.put("100", full("100", "旧资料"));
        profiles.request(entries, false, ignored -> { });
        batch.runAll();
        while (!mainQueue.isEmpty()) mainQueue.remove().run();

        now.addAndGet(16 * 60_000);
        source.remote.put("100", full("100", "新资料"));
        profiles.request(entries, false, ignored -> { });
        List<NativeUserProfiles.Update> joining = new ArrayList<>();
        // Finish the shared job after this request captured its old cache, before that initial patch is queued.
        beforeEnqueue.set(batch::runAll);
        profiles.request(entries, false, joining::add);
        while (!mainQueue.isEmpty()) mainQueue.remove().run();

        assertEquals(2, source.calls.size());
        assertEquals("新资料", combined(joining).get("100").name);
        assertFalse(joining.get(joining.size() - 1).loading);
    }

    @Test public void anOlderCapturedAccountStateCannotInvalidateTheNewAccountRequestOrCancelItsJob() {
        QueueExecutor batch = new QueueExecutor();
        ArrayDeque<Runnable> mainQueue = new ArrayDeque<>();
        AtomicReference<Runnable> afterCapture = new AtomicReference<>();
        profiles = new NativeUserProfiles(source, () -> {
            FollowListSnapshot.State captured = FollowListSnapshot.current();
            Runnable hook = afterCapture.getAndSet(null);
            if (hook != null) hook.run();
            return captured;
        }, now::get, batch, mainQueue::add);
        List<NativeUserProfiles.Update> currentAccount = new ArrayList<>();
        source.remote.put("200", full("200", "新账号资料"));
        // Establish B while the outer A request still holds the older state returned by its account supplier.
        afterCapture.set(() -> {
            FollowListSnapshot.activateAccount("43");
            profiles.request(Collections.singletonList(new ProfileListEntry("200", "", "")), false, currentAccount::add);
        });
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, ignored -> { });
        batch.runAll();
        while (!mainQueue.isEmpty()) mainQueue.remove().run();

        assertFalse("An older A snapshot must not claim the still-current B account changed",
                currentAccount.stream().anyMatch(update -> update.accountChanged));
        assertEquals(Collections.singletonList("200"), source.calls);
        assertEquals("新账号资料", combined(currentAccount).get("200").name);
        assertFalse(currentAccount.get(currentAccount.size() - 1).loading);
    }

    @Test public void mixedPartialTimeoutFailureAndNoResultHaveSeparateCountsWithoutDiscardingKnownData() {
        source.remote.put("100", full("100", "完整用户"));
        source.remote.put("200", new ProfileListEntry("200", "", "https://p3.douyinpic.com/200.jpg", "account.200"));
        source.remote.put("300", new ProfileListEntry("300", "缺头像用户", "", "account.300"));
        source.remote.put("700", new ProfileListEntry("700", "缺抖音号用户", "https://p3.douyinpic.com/700.jpg", ""));
        source.failures.put("400", new java.util.concurrent.TimeoutException("private-timeout"));
        source.failures.put("500", new java.io.IOException("private-read-failure"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("200", "", ""),
                new ProfileListEntry("300", "", ""), full("400", "超时前资料"), full("500", "失败前资料"),
                full("600", "未返回前资料"), new ProfileListEntry("700", "", "")), true, updates::add);
        workers.runAll();
        NativeUserProfiles.Update end = last(updates);
        assertFalse(end.loading);
        assertComplete(end.error, 4, 7);
        assertCount(end.error, "待补(?:全)?", 3);
        assertCount(end.error, "昵称", 1); assertCount(end.error, "头像", 1); assertCount(end.error, "抖音号", 1);
        assertCount(end.error, "超时", 1); assertCount(end.error, "(?:读取|刷新)?失败", 1); assertCount(end.error, "未返回", 1);
        Map<String, ProfileListEntry> data = combined(updates);
        assertEquals("超时前资料", data.get("400").name);
        assertEquals("失败前资料", data.get("500").name);
        assertEquals("未返回前资料", data.get("600").name);
        assertEquals("", data.get("200").name);
        assertEquals("", data.get("300").avatarUrl);
        assertEquals("", data.get("700").douyinId);
    }

    @Test public void theLastCompletionDoesNotReplaceTheBatchSummaryOrChangeItsCounts() {
        source.remote.put("100", full("100", "完整用户"));
        source.remote.put("200", new ProfileListEntry("200", "缺头像", "", "account.200"));
        source.failures.put("300", new java.io.IOException("private-read-failure"));
        String previous = null;
        for (List<ProfileListEntry> rows : Arrays.asList(
                Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("200", "", ""), full("300", "既有资料")),
                Arrays.asList(full("300", "既有资料"), new ProfileListEntry("200", "", ""), new ProfileListEntry("100", "", "")))) {
            QueueExecutor batch = new QueueExecutor();
            profiles = new NativeUserProfiles(source, FollowListSnapshot::current, now::get, batch, Runnable::run);
            List<NativeUserProfiles.Update> updates = new ArrayList<>();
            profiles.request(rows, true, updates::add); batch.runAll();
            String status = last(updates).error;
            assertComplete(status, 2, 3); assertCount(status, "待补(?:全)?", 1);
            assertCount(status, "头像", 1); assertCount(status, "(?:读取|刷新)?失败", 1);
            if (previous != null) assertEquals("Final summary is independent of the finishing user's result", previous, status);
            previous = status;
        }
    }

    @Test public void aFailedForceRefreshKeepsFullCachedProfilesAndReportsFailureBesideOtherSuccess() {
        source.remote.put("100", full("100", "缓存姓名"));
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, ignored -> { });
        workers.runAll();
        source.failures.put("100", new java.io.IOException("private-failure"));
        source.remote.put("200", full("200", "本轮成功"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("200", "", "")), true, updates::add);
        workers.runAll();
        assertComplete(last(updates).error, 2, 2);
        assertCount(last(updates).error, "(?:读取|刷新)?失败", 1);
        assertEquals("缓存姓名", combined(updates).get("100").name);
        assertEquals("本轮成功", combined(updates).get("200").name);
    }

    @Test public void completeCacheHitsAndDuplicateRowsAreCountedOnceBesideAnIncompleteNewUser() {
        source.remote.put("100", full("100", "已缓存"));
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, ignored -> { });
        workers.runAll();
        source.remote.put("200", new ProfileListEntry("200", "缺抖音号", "https://p3.douyinpic.com/200.jpg", ""));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("100", "", ""),
                new ProfileListEntry("200", "", ""), ProfileListEntry.conversation("group", "群聊", "")), false, updates::add);
        workers.runAll();
        assertComplete(last(updates).error, 1, 2);
        assertCount(last(updates).error, "待补(?:全)?", 1); assertCount(last(updates).error, "抖音号", 1);
        assertEquals(Arrays.asList("100", "200"), source.calls);
    }

    @Test public void overlappingRequestsKeepTheirOwnIncompleteCountsWhileSharingTheSameLookup() {
        source.remote.put("100", new ProfileListEntry("100", "缺头像", "", "account.100"));
        source.remote.put("200", full("200", "完整用户"));
        source.remote.put("300", new ProfileListEntry("300", "缺抖音号", "https://p3.douyinpic.com/300.jpg", ""));
        List<NativeUserProfiles.Update> first = new ArrayList<>(), second = new ArrayList<>();
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("200", "", "")), false, first::add);
        profiles.request(Arrays.asList(new ProfileListEntry("100", "", ""), new ProfileListEntry("300", "", "")), false, second::add);
        workers.runAll();
        assertComplete(last(first).error, 1, 2); assertCount(last(first).error, "待补(?:全)?", 1); assertCount(last(first).error, "头像", 1);
        assertComplete(last(second).error, 0, 2); assertCount(last(second).error, "待补(?:全)?", 2);
        assertCount(last(second).error, "头像", 1); assertCount(last(second).error, "抖音号", 1);
        assertEquals(Arrays.asList("100", "200", "300"), source.calls);
    }

    @Test public void anOptionalEmptyRemarkDoesNotCreateAnErrorForACompleteUser() {
        source.remote.put("100", full("100", "无备注用户"));
        List<NativeUserProfiles.Update> updates = new ArrayList<>();
        profiles.request(Collections.singletonList(new ProfileListEntry("100", "", "")), false, updates::add);
        workers.runAll();
        assertEquals("", combined(updates).get("100").remarkName);
        assertEquals("", last(updates).error);
        assertFalse(last(updates).loading);
    }

    private static NativeUserProfiles.Update last(List<NativeUserProfiles.Update> updates) { return updates.get(updates.size() - 1); }
    private static void assertComplete(String text, int complete, int total) {
        assertTrue("Batch status must identify the actual complete count: " + text,
                java.util.regex.Pattern.compile("(?:已完整|资料完整)\\s*" + complete + "\\s*/\\s*" + total + "(?!\\d)").matcher(text).find());
    }
    private static void assertCount(String text, String label, int count) {
        assertTrue("Batch status must identify " + label + "=" + count + ": " + text,
                java.util.regex.Pattern.compile(label + "\\s*" + count + "(?!\\d)").matcher(text).find());
    }

    private static ProfileListEntry full(String uid, String name) {
        return new ProfileListEntry(uid, name, "https://p3.douyinpic.com/" + uid + ".jpg", "account." + uid);
    }
    private static Map<String, ProfileListEntry> combined(List<NativeUserProfiles.Update> updates) {
        Map<String, ProfileListEntry> result = new HashMap<>();
        for (NativeUserProfiles.Update update : updates) result.putAll(update.profiles);
        return result;
    }
    private static final class FixtureSource implements NativeUserProfiles.Source {
        final Map<String, ProfileListEntry> local = new HashMap<>(), remote = new HashMap<>();
        final Map<String, Exception> failures = new HashMap<>();
        final List<String> calls = new ArrayList<>();
        boolean fail;
        Runnable beforeFetch = () -> { };
        @Override public ProfileListEntry cached(String uid) { return local.get(uid); }
        @Override public ProfileListEntry fetch(String uid, BooleanSupplier current) throws Exception {
            calls.add(uid);
            beforeFetch.run();
            if (failures.containsKey(uid)) throw failures.get(uid);
            if (fail) throw new java.io.IOException("fixture");
            return remote.get(uid);
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
