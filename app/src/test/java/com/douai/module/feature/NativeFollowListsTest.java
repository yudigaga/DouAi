package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class NativeFollowListsTest {
    private final AtomicReference<NativeFollowLists.Account> account = new AtomicReference<>();
    private final List<FollowListPager.Kind> requests = new ArrayList<>();
    private NativeFollowLists service;

    @Before public void setup() {
        FollowListSnapshot.reset();
        account.set(new NativeFollowLists.Account("42", "sec42"));
    }

    @After public void cleanup() {
        if (service != null) service.close();
        FollowListSnapshot.reset();
    }

    private void service(LocalOptions options, FollowListPager.Fetcher source) throws Exception {
        service(options, source, new java.util.concurrent.ScheduledThreadPoolExecutor(1));
    }

    private void service(LocalOptions options, FollowListPager.Fetcher source,
                         java.util.concurrent.ScheduledThreadPoolExecutor timer) throws Exception {
        service = new NativeFollowLists(account::get, (kind, query) -> {
            requests.add(kind);
            return source.fetch(kind, query);
        }, ignored -> { }, timer);
        HostTargets.field(NativeFollowLists.class, "active").set(null, service);
        HostTargets.field(NativeFollowLists.class, "options").set(service, options);
    }

    @Test public void selectedFollowingLoadsActualUsersAndReusesCompletedList() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> new Page(new User("100", "Alice", 1, 0)));
        service.refreshNow(false);
        assertEquals(Collections.singletonList(FollowListPager.Kind.FOLLOWING), requests);
        assertEquals(Collections.singleton("100"), FollowListSnapshot.current().following);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertEquals("Alice", NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).get("100"));
        assertEquals("Alice", AutomationScopePolicy.cachedName("100"));
        assertTrue(AutomationScopePolicy.allows("comment", options("comment_scope_mode", "whitelist",
                "comment_scope_relations", "following"), "100", "", "", "", null));
        service.refreshNow(false);
        assertEquals(1, requests.size());
    }

    @Test public void strangersRequireBothListsAndManualRefreshAlsoLoadsBoth() throws Exception {
        service(options("reply_scope_mode", "blacklist", "reply_scope_relations", "strangers"),
                (kind, query) -> new Page(new User(kind == FollowListPager.Kind.FOLLOWING ? "100" : "200", "Name", 1, 1)));
        service.refreshNow(false);
        assertEquals(Arrays.asList(FollowListPager.Kind.FOLLOWING, FollowListPager.Kind.FOLLOWERS), requests);
        assertTrue(AutomationScopePolicy.cached("300").stranger);
        service.refreshNow(true);
        assertEquals(4, requests.size());
        assertTrue(NativeFollowLists.statusText().contains("1 人"));
    }

    @Test public void pickerRefreshForcesBothNativeListsEvenWhenTheirCompleteCachesAreFresh() throws Exception {
        ManualTimer timer = new ManualTimer();
        AtomicReference<String> user = new AtomicReference<>("100");
        service(new LocalOptions(Collections.emptyMap()),
                (kind, query) -> new Page(new User(user.get(), "Cached user", 1, 1)), timer);
        service.refreshNow(true);
        assertEquals(2, requests.size());
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        com.douai.module.ui.MemberListView list = new com.douai.module.ui.MemberListView(activity, false,
                new ArrayList<>(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING).values()),
                Collections.singleton("100"), true, true);
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            user.set("200");
            list.refreshUsers();
            timer.advance(0);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals("One refresh must bypass both fresh native relationship caches", 4, requests.size());
            assertEquals(Collections.singleton("200"), FollowListSnapshot.current().following);
            assertEquals(Collections.singleton("200"), FollowListSnapshot.current().followers);
            assertEquals(Collections.singleton("100"), list.selected());
        } finally { list.close(); activity.finish(); }
    }

    @Test public void pickerRefreshKeepsSuccessfulKindsButDoesNotTreatFailedCachedFollowingAsFresh() throws Exception {
        ManualTimer timer = new ManualTimer();
        java.util.concurrent.atomic.AtomicBoolean failing = new java.util.concurrent.atomic.AtomicBoolean();
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            if (failing.get() && kind == FollowListPager.Kind.FOLLOWING) throw new IllegalStateException("offline");
            return new Page(new User(failing.get() ? "200" : "100", "Listed user", 1, 1));
        }, timer);
        service.refreshNow(true);
        android.app.Activity activity = org.robolectric.Robolectric.buildActivity(android.app.Activity.class).setup().get();
        com.douai.module.ui.MemberListView list = new com.douai.module.ui.MemberListView(activity, false,
                Collections.singletonList(new ProfileListEntry("303", "Local user", "")),
                Collections.singleton("303"), true, true);
        try {
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            android.widget.Spinner filter = list.findViewWithTag("settings.followLists.scope");
            android.widget.ListView users = list.findViewWithTag("settings.followLists.members");
            filter.setSelection(1); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(1, users.getAdapter().getCount());
            failing.set(true);
            list.refreshUsers(); timer.advance(0);
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertTrue("Keep the last usable snapshot for existing consumers", FollowListSnapshot.current().followingKnown);
            assertEquals("A failed forced following refresh cannot prove absence using its previous cache", 0,
                    users.getAdapter().getCount());
            filter.setSelection(3); org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals("The successfully refreshed follower kind must still be merged", 1, users.getAdapter().getCount());
            assertEquals("200", ((ProfileListEntry) users.getAdapter().getItem(0)).uid);
            assertEquals(Collections.singleton("303"), list.selected());
        } finally { list.close(); activity.finish(); }
    }

    @Test public void findingOneUserCombinesIdentityFieldsFromBothRelationshipLists() throws Exception {
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            User user = new User("100", kind == FollowListPager.Kind.FOLLOWING ? "Alice" : "", 2, 1);
            user.unique_id = kind == FollowListPager.Kind.FOLLOWERS ? "alice.id" : "";
            return new Page(user);
        });
        service.refreshNow(true);
        ProfileListEntry profile = NativeFollowLists.findMember("100");
        assertEquals("Alice", profile.name);
        assertEquals("alice.id", profile.douyinId);
        assertEquals("100", profile.selectionKey);
    }

    @Test public void failedFollowerFetchDoesNotInvalidateSuccessfullyLoadedFollowing() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "strangers"),
                (kind, query) -> {
                    if (kind == FollowListPager.Kind.FOLLOWERS) throw new IllegalStateException("network");
                    return new Page(new User("100", "Alice", 1, 0));
                });
        service.refreshNow(false);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertFalse(AutomationScopePolicy.cached("300").stranger);
        assertTrue(NativeFollowLists.statusText().contains("拉取失败"));
        service.refreshNow(false);
        assertEquals(2, requests.size());
    }

    @Test public void switchedAccountDuringFetchCannotPublishOrReuseOldMembership() throws Exception {
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> {
                    account.set(new NativeFollowLists.Account("43", "sec43"));
                    NativeFollowLists.ensureAccount("43");
                    return new Page(new User("100", "Previous account", 1, 0));
                });
        service.refreshNow(false);
        assertEquals("43", FollowListSnapshot.current().accountUid);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
    }

    @Test public void logoutClearsLoadedNamesAndListsImmediately() throws Exception {
        service(options(), (kind, query) -> new Page(new User("100", "Alice", 1, 1)));
        service.refreshNow(true);
        account.set(new NativeFollowLists.Account("", ""));
        NativeFollowLists.ensureAccount("0");
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
        assertEquals("未登录抖音账号", NativeFollowLists.statusText());
    }

    @Test public void staleMessagingAccountCannotChangeWhichAccountOwnsTheLists() throws Exception {
        service(options(), (kind, query) -> new Page(new User("100", "Alice", 1, 1)));
        service.refreshNow(true);
        assertFalse(NativeFollowLists.ensureAccount("41"));
        assertEquals("42", FollowListSnapshot.current().accountUid);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.ensureAccount("42"));
    }

    @Test public void expiredListBecomesUnknownUntilRefetched() throws Exception {
        service(options("comment_scope_mode", "blacklist", "comment_scope_relations", "following"),
                (kind, query) -> new Page(new User("100", "Alice", 1, 0)));
        service.refreshNow(false);
        long expiry = FollowListSnapshot.current().followingUpdatedAt + NativeFollowLists.TTL_MS;
        java.lang.reflect.Method expire = NativeFollowLists.class.getDeclaredMethod("expire", long.class);
        expire.setAccessible(true);
        expire.invoke(service, expiry);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertFalse(AutomationScopePolicy.allows("comment", options("comment_scope_mode", "blacklist",
                "comment_scope_relations", "following"), "100", "", "", "", null));
        service.refreshNow(true);
        assertTrue(FollowListSnapshot.current().followingKnown);
    }

    @Test public void freshCompleteSnapshotIsPreservedOnManualRefreshFailure() throws Exception {
        AtomicReference<Boolean> fail = new AtomicReference<>(false);
        service(options(), (kind, query) -> {
            if (fail.get()) throw new IllegalStateException("network");
            return new Page(new User("100", "Alice", 1, 1));
        });
        service.refreshNow(true);
        fail.set(true);
        service.refreshNow(true);
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertEquals("Alice", NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).get("100"));
        assertTrue(NativeFollowLists.statusText().contains("拉取失败"));
    }

    @Test public void policyTimerUsesTheExactTtlAndHotDisableStopsAutomaticFetching() throws Exception {
        ManualTimer timer = new ManualTimer();
        LocalOptions enabled = options("comment_scope_mode", "whitelist", "comment_scope_relations", "following");
        service(enabled, (kind, query) -> new Page(new User("100", "Alice", 1, 0)), timer);
        service.start();
        timer.advance(0);
        assertEquals(1, requests.size());
        timer.advance(NativeFollowLists.TTL_MS - 1);
        assertEquals("A known snapshot must remain reusable until its own TTL", 1, requests.size());
        timer.advance(1);
        assertEquals(2, requests.size());

        service.setOptions(options("native_auto_comment", false, "native_auto_reply", false,
                "comment_scope_mode", "whitelist", "comment_scope_relations", "following"));
        timer.advance(2 * NativeFollowLists.TTL_MS);
        assertEquals("Disabled features must not refresh a retained relationship scope", 2, requests.size());
        service.setOptions(enabled);
        timer.advance(0);
        assertEquals(3, requests.size());
    }

    @Test public void failedFetchRetriesAtItsDeadlineAndAccountProbeClearsOldOwnership() throws Exception {
        ManualTimer timer = new ManualTimer();
        java.util.concurrent.atomic.AtomicBoolean fail = new java.util.concurrent.atomic.AtomicBoolean(true);
        service(options("comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> {
                    if (fail.get()) throw new IllegalStateException("network");
                    return new Page(new User("100", "Alice", 1, 0));
                }, timer);
        service.start();
        timer.advance(0);
        assertEquals(1, requests.size());
        timer.advance(59_999);
        assertEquals(1, requests.size());
        fail.set(false);
        timer.advance(1);
        assertEquals(2, requests.size());
        assertTrue(FollowListSnapshot.current().followingKnown);

        account.set(new NativeFollowLists.Account("43", "sec43"));
        timer.advance(30_000);
        assertEquals("43", FollowListSnapshot.current().accountUid);
        assertEquals("A lightweight account probe must start a fresh account's required list", 3, requests.size());
        account.set(new NativeFollowLists.Account("", ""));
        timer.advance(30_000);
        assertFalse(FollowListSnapshot.current().followingKnown);
        assertTrue(NativeFollowLists.members(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
        timer.advance(NativeFollowLists.TTL_MS);
        assertEquals(3, requests.size());
    }

    @Test public void disabledRelationshipPolicyStillSupportsExplicitManualRefresh() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(options("native_auto_comment", false, "native_auto_reply", false,
                        "comment_scope_mode", "whitelist", "comment_scope_relations", "following"),
                (kind, query) -> new Page(new User("100", "Alice", 1, 1)), timer);
        service.start();
        timer.advance(2 * NativeFollowLists.TTL_MS);
        assertTrue(requests.isEmpty());
        assertTrue(NativeFollowLists.refresh(null));
        timer.advance(0);
        assertEquals(Arrays.asList(FollowListPager.Kind.FOLLOWING, FollowListPager.Kind.FOLLOWERS), requests);
    }

    @Test public void pickerLoadsAll120FollowingUsersWithoutAutomationOrFetchingFollowersAndReusesItsTtl() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            assertEquals(FollowListPager.Kind.FOLLOWING, kind);
            int offset = Integer.parseInt(query.get("offset"));
            User[] users = new User[20];
            for (int index = 0; index < users.length; index++) {
                users[index] = new User(Integer.toString(1000 + offset + index), "User " + (offset + index), 1, 0);
            }
            Page page = new Page(users);
            page.hasMore = offset + users.length < 120;
            page.offset = offset + users.length;
            page.minTime = page.maxTime = page.offset;
            return page;
        }, timer);
        List<Boolean> completed = new ArrayList<>();
        NativeFollowLists.loadFollowing(completed::add);
        NativeFollowLists.loadFollowing(completed::add);
        assertTrue("Picker requests must stay on the background lane", completed.isEmpty());
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(true, true), completed);
        assertEquals(6, requests.size());
        assertEquals(120, NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING).size());
        assertFalse(FollowListSnapshot.current().followersKnown);

        NativeFollowLists.loadFollowing(completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(6, requests.size());
        timer.advance(NativeFollowLists.TTL_MS);
        NativeFollowLists.loadFollowing(completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(12, requests.size());
        assertEquals(Arrays.asList(true, true, true, true), completed);
    }

    @Test public void pickerLoadsAllFollowersOnDemandAndReusesItsTtlWithoutFetchingFollowing() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            assertEquals(FollowListPager.Kind.FOLLOWERS, kind);
            int offset = Integer.parseInt(query.get("offset"));
            Page page = new Page(new User(Integer.toString(2000 + offset), "Follower " + offset, 0, 1));
            page.hasMore = offset < 2;
            page.offset = offset + 1;
            page.minTime = page.maxTime = page.offset;
            return page;
        }, timer);
        assertTrue(requests.isEmpty());
        List<Boolean> completed = new ArrayList<>();
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
        assertTrue("Picker requests must stay on the background lane", completed.isEmpty());
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(true, true), completed);
        assertEquals(3, requests.size());
        assertEquals(3, NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS).size());
        assertFalse(FollowListSnapshot.current().followingKnown);
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(3, requests.size());
        timer.advance(NativeFollowLists.TTL_MS);
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(6, requests.size());
        assertEquals(Arrays.asList(true, true, true, true), completed);
    }

    @Test public void mixedPickerKindsCompleteFromTheirOwnSnapshotsWhenFollowersFail() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            if (kind == FollowListPager.Kind.FOLLOWERS) throw new IllegalStateException("Followers unavailable");
            return new Page(new User("100", "Following", 1, 0));
        }, timer);
        List<Boolean> following = new ArrayList<>(), followers = new ArrayList<>();
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, followers::add);
        NativeFollowLists.loadFollowing(following::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(true), following);
        assertEquals(Collections.singletonList(false), followers);
        assertEquals(2, requests.size());
        assertTrue(FollowListSnapshot.current().followingKnown);
        assertFalse(FollowListSnapshot.current().followersKnown);
    }

    @Test public void requestsForAnotherKindArrivingDuringFetchAreNotLostOrCompletedByTheFirstKind() throws Exception {
        ManualTimer timer = new ManualTimer();
        List<Boolean> following = new ArrayList<>(), followers = new ArrayList<>();
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            if (kind == FollowListPager.Kind.FOLLOWING) {
                NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, followers::add);
                assertTrue("The second kind cannot complete before its fetch", followers.isEmpty());
            }
            return new Page(new User(kind == FollowListPager.Kind.FOLLOWING ? "100" : "200", "User", 1, 1));
        }, timer);
        NativeFollowLists.loadFollowing(following::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Arrays.asList(FollowListPager.Kind.FOLLOWING, FollowListPager.Kind.FOLLOWERS), requests);
        assertEquals(Collections.singletonList(true), following);
        assertEquals(Collections.singletonList(true), followers);
        assertEquals(Collections.singleton("200"), FollowListSnapshot.current().followers);
    }

    @Test public void cancellingOneKindDoesNotFetchItOrCancelTheOtherKind() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(new LocalOptions(Collections.emptyMap()),
                (kind, query) -> new Page(new User("100", "Following", 1, 0)), timer);
        List<Boolean> following = new ArrayList<>(), followers = new ArrayList<>();
        NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, followers::add).close();
        NativeFollowLists.loadFollowing(following::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(FollowListPager.Kind.FOLLOWING), requests);
        assertEquals(Collections.singletonList(true), following);
        assertTrue(followers.isEmpty());
        assertFalse(FollowListSnapshot.current().followersKnown);
    }

    @Test public void cancelledFollowersConsumerStopsPagingWithoutPublishingAPartialList() throws Exception {
        ManualTimer timer = new ManualTimer();
        AutoCloseable[] request = new AutoCloseable[1];
        service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
            request[0].close();
            Page page = new Page(new User("200", "Follower", 0, 1));
            page.hasMore = true; page.offset = 1; page.minTime = 1;
            return page;
        }, timer);
        List<Boolean> completed = new ArrayList<>();
        request[0] = NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(FollowListPager.Kind.FOLLOWERS), requests);
        assertTrue(completed.isEmpty());
        assertFalse(FollowListSnapshot.current().followersKnown);
        assertTrue(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS).isEmpty());
    }

    @Test public void followersPickerRejectsAccountAbaBeforeWorkDuringFetchAndBeforeMainDelivery() throws Exception {
        for (String stage : Arrays.asList("queued", "running", "main_callback")) {
            if (service != null) service.close();
            account.set(new NativeFollowLists.Account("42", "sec42"));
            ManualTimer timer = new ManualTimer();
            Runnable switchAwayAndBack = () -> {
                account.set(new NativeFollowLists.Account("43", "sec43")); NativeFollowLists.ensureAccount("43");
                account.set(new NativeFollowLists.Account("42", "sec42")); NativeFollowLists.ensureAccount("42");
            };
            service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
                if (stage.equals("running")) switchAwayAndBack.run();
                return new Page(new User("200", "Old follower", 0, 1));
            }, timer);
            List<Boolean> completed = new ArrayList<>();
            NativeFollowLists.load(FollowListSnapshot.Kind.FOLLOWERS, completed::add);
            if (stage.equals("queued")) switchAwayAndBack.run();
            timer.advance(0);
            if (stage.equals("main_callback")) switchAwayAndBack.run();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(stage, Collections.singletonList(false), completed);
            assertTrue(stage, NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS).isEmpty());
        }
    }

    @Test public void cancelledFollowingConsumerDoesNotFetchOrReceiveWhileOtherConsumersStillComplete() throws Exception {
        ManualTimer timer = new ManualTimer();
        service(new LocalOptions(Collections.emptyMap()),
                (kind, query) -> new Page(new User("100", "Alice", 1, 0)), timer);
        List<Boolean> completed = new ArrayList<>();
        NativeFollowLists.loadFollowing(completed::add).close();
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertTrue(requests.isEmpty()); assertTrue(completed.isEmpty());

        NativeFollowLists.loadFollowing(completed::add).close();
        NativeFollowLists.loadFollowing(completed::add);
        timer.advance(0); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(Collections.singletonList(true), completed);
        assertEquals(Collections.singletonList(FollowListPager.Kind.FOLLOWING), requests);
    }

    @Test public void followingPickerRejectsAccountAbaBeforeWorkDuringFetchAndBeforeMainDelivery() throws Exception {
        for (String stage : Arrays.asList("queued", "running", "main_callback")) {
            if (service != null) service.close();
            account.set(new NativeFollowLists.Account("42", "sec42"));
            ManualTimer timer = new ManualTimer();
            Runnable switchAwayAndBack = () -> {
                account.set(new NativeFollowLists.Account("43", "sec43")); NativeFollowLists.ensureAccount("43");
                account.set(new NativeFollowLists.Account("42", "sec42")); NativeFollowLists.ensureAccount("42");
            };
            service(new LocalOptions(Collections.emptyMap()), (kind, query) -> {
                if (stage.equals("running")) switchAwayAndBack.run();
                return new Page(new User("100", "Old session", 1, 0));
            }, timer);
            List<Boolean> completed = new ArrayList<>();
            NativeFollowLists.loadFollowing(completed::add);
            if (stage.equals("queued")) switchAwayAndBack.run();
            timer.advance(0);
            if (stage.equals("main_callback")) switchAwayAndBack.run();
            org.robolectric.shadows.ShadowLooper.idleMainLooper();
            assertEquals(stage, Collections.singletonList(false), completed);
            assertTrue(stage, NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING).isEmpty());
        }
    }

    @Test public void closeCoordinatesWithAnInFlightScheduleAndLateAccountReadsCannotReactivateIt() throws Exception {
        java.util.concurrent.CountDownLatch scheduling = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closed = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.ScheduledThreadPoolExecutor timer = new java.util.concurrent.ScheduledThreadPoolExecutor(1) {
            @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable action, long delay, java.util.concurrent.TimeUnit unit) {
                scheduling.countDown();
                try { release.await(2, java.util.concurrent.TimeUnit.SECONDS); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                return super.schedule(action, delay, unit);
            }
        };
        LocalOptions disabled = options("native_auto_comment", false, "native_auto_reply", false,
                "comment_scope_mode", "whitelist", "comment_scope_relations", "following");
        LocalOptions enabled = options("native_auto_comment", true, "native_auto_reply", false,
                "comment_scope_mode", "whitelist", "comment_scope_relations", "following");
        service(disabled, (kind, query) -> new Page(new User("100", "Alice", 1, 0)), timer);
        service.start();
        Thread configure = new Thread(() -> {
            try { service.setOptions(enabled); } catch (Throwable error) { failure.set(error); }
        }, "relationship-configure-test");
        Thread closing = new Thread(() -> {
            try { service.close(); } catch (Throwable error) { failure.set(error); }
            finally { closed.countDown(); }
        }, "relationship-close-test");
        try {
            configure.start();
            assertTrue(scheduling.await(2, java.util.concurrent.TimeUnit.SECONDS));
            closing.start();
            assertFalse("Close must coordinate with scheduling before shutting down its executor",
                    closed.await(100, java.util.concurrent.TimeUnit.MILLISECONDS));
            release.countDown();
            configure.join(2_000); closing.join(2_000);
            assertFalse(configure.isAlive()); assertFalse(closing.isAlive());
            assertNull(failure.get());
            assertTrue(timer.awaitTermination(2, java.util.concurrent.TimeUnit.SECONDS));
            service.setOptions(disabled); service.setOptions(enabled);
            service.refreshNow(false);
            assertTrue(timer.getQueue().isEmpty());
            assertEquals("", FollowListSnapshot.current().accountUid);
        } finally { release.countDown(); service.close(); timer.shutdownNow(); }
    }

    private static LocalOptions options(Object... pairs) {
        Map<String, Object> values = new HashMap<>();
        values.put("native_auto_comment", true);
        values.put("native_auto_reply", true);
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return new LocalOptions(values);
    }

    /** Advances real production scheduling decisions without sleeping or starting worker threads. */
    static final class ManualTimer extends java.util.concurrent.ScheduledThreadPoolExecutor {
        private final java.util.PriorityQueue<TimedTask> tasks = new java.util.PriorityQueue<>();
        private long now;
        private long sequence;
        ManualTimer() { super(1); }
        @Override public java.util.concurrent.ScheduledFuture<?> schedule(Runnable action, long delay,
                                                                         java.util.concurrent.TimeUnit unit) {
            TimedTask task = new TimedTask(action, now + Math.max(0, unit.toMillis(delay)), 0, sequence++);
            tasks.add(task);
            return task;
        }
        @Override public java.util.concurrent.ScheduledFuture<?> scheduleWithFixedDelay(Runnable action, long delay,
                long period, java.util.concurrent.TimeUnit unit) {
            TimedTask task = new TimedTask(action, now + unit.toMillis(delay), unit.toMillis(period), sequence++);
            tasks.add(task);
            return task;
        }
        @Override public void execute(Runnable action) { schedule(action, 0, java.util.concurrent.TimeUnit.MILLISECONDS); }
        void advance(long millis) {
            long until = now + millis;
            int steps = 0;
            while (!tasks.isEmpty() && tasks.peek().at <= until) {
                if (++steps > 10_000) throw new AssertionError("Unbounded timer loop");
                TimedTask task = tasks.remove();
                org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(task.at - now));
                now = task.at;
                if (task.cancelled) continue;
                task.action.run();
                task.done = true;
                if (task.period > 0 && !task.cancelled) {
                    task.done = false;
                    task.at = now + task.period;
                    tasks.add(task);
                }
            }
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofMillis(until - now));
            now = until;
        }
        @Override public List<Runnable> shutdownNow() { tasks.clear(); return super.shutdownNow(); }
        private final class TimedTask implements java.util.concurrent.ScheduledFuture<Object> {
            final Runnable action;
            final long period, order;
            long at;
            boolean cancelled, done;
            TimedTask(Runnable action, long at, long period, long order) {
                this.action = action; this.at = at; this.period = period; this.order = order;
            }
            @Override public long getDelay(java.util.concurrent.TimeUnit unit) { return unit.convert(at - now, java.util.concurrent.TimeUnit.MILLISECONDS); }
            @Override public int compareTo(java.util.concurrent.Delayed other) {
                TimedTask task = (TimedTask) other;
                int time = Long.compare(at, task.at);
                return time != 0 ? time : Long.compare(order, task.order);
            }
            @Override public boolean cancel(boolean interrupt) { cancelled = true; tasks.remove(this); return true; }
            @Override public boolean isCancelled() { return cancelled; }
            @Override public boolean isDone() { return cancelled || done; }
            @Override public Object get() { throw new UnsupportedOperationException(); }
            @Override public Object get(long timeout, java.util.concurrent.TimeUnit unit) { throw new UnsupportedOperationException(); }
        }
    }

    static final class Page {
        public int status_code;
        public int error_code;
        public boolean hasMore;
        public long minTime, maxTime;
        public int offset;
        public List<User> items;
        Page(User... users) { items = Arrays.asList(users); }
    }

    static final class User {
        public String uid;
        public String nickname;
        public String unique_id;
        public int followStatus;
        public int followerStatus;
        User(String uid, String name, int follow, int follower) {
            this.uid = uid; nickname = name; followStatus = follow; followerStatus = follower;
        }
    }
}
