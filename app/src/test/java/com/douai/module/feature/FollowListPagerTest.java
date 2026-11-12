package com.douai.module.feature;

import com.google.gson.annotations.SerializedName;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

public final class FollowListPagerTest {
    @Test public void followingLoadsAllPagesAndCarriesNativePaginationFields() throws Exception {
        List<Map<String, String>> queries = new ArrayList<>();
        Page first = page(true, 100, 200, 20, user("10", "", 1, 0), user("99", "recommended", 0, 0));
        first.storePage = "page-token";
        first.vcdCount = 3;
        Page second = page(true, 90, 210, 40, user("10", "renamed", 1, 0), user("20", "mutual", 2, 1));
        second.vcdCount = 5;
        Page last = page(false, 0, 0, 40, user("30", "following", 1, 0));
        List<Page> pages = Arrays.asList(first, second, last);
        Map<String, String> result = load(FollowListPager.Kind.FOLLOWING, (kind, query) -> {
            assertEquals(FollowListPager.Kind.FOLLOWING, kind);
            queries.add(query);
            return pages.get(queries.size() - 1);
        });
        assertEquals(3, result.size());
        assertEquals("renamed", result.get("10"));
        assertEquals("mutual", result.get("20"));
        assertFalse(result.containsKey("99"));
        assertEquals("42", queries.get(0).get("user_id"));
        assertEquals("sec-42", queries.get(0).get("sec_user_id"));
        assertEquals("20", queries.get(0).get("count"));
        assertEquals("1", queries.get(0).get("source_type"));
        assertEquals("0", queries.get(0).get("max_time"));
        assertEquals("0", queries.get(0).get("min_time"));
        for (String field : Arrays.asList("filter_type", "relation_filter_type", "scene",
                "address_book_access", "gps_access", "vcd_auth_first_time")) {
            assertEquals("0", queries.get(0).get(field));
        }
        assertEquals("100", queries.get(1).get("max_time"));
        assertEquals("0", queries.get(1).get("min_time"));
        assertEquals("20", queries.get(1).get("offset"));
        assertEquals("page-token", queries.get(1).get("store_page"));
        assertEquals("3", queries.get(1).get("vcd_count"));
        assertEquals("90", queries.get(2).get("max_time"));
        assertEquals("0", queries.get(2).get("min_time"));
        assertEquals("40", queries.get(2).get("offset"));
        assertEquals("page-token", queries.get(2).get("store_page"));
        assertEquals("5", queries.get(2).get("vcd_count"));
        assertThrows(UnsupportedOperationException.class, () -> result.put("50", "new"));
        assertThrows(UnsupportedOperationException.class, () -> queries.get(0).put("count", "100"));
    }

    @Test public void sourceTypeOneLoadsOneHundredTwentyFollowingWithoutAReverseTimeWindow() throws Exception {
        List<Map<String, String>> queries = new ArrayList<>();
        Map<String, String> result = load(FollowListPager.Kind.FOLLOWING, (kind, query) -> {
            queries.add(query);
            int offset = Integer.parseInt(query.get("offset"));
            // Source type 1 advances max_time only; a reverse boundary closes this fixture's window.
            if (!"0".equals(query.get("min_time"))) return page(false, 0, 0, offset);
            List<User> users = new ArrayList<>();
            for (int index = offset + 1; index <= offset + 20; index++) {
                users.add(user(Integer.toString(index), "Following " + index, 1, 0));
            }
            return new Page(offset < 100, 1000 - offset, 2000 + offset, offset + 20, users);
        });
        assertEquals(120, result.size());
        assertEquals("Following 120", result.get("120"));
        assertEquals(6, queries.size());
        assertEquals("1000", queries.get(1).get("max_time"));
        assertEquals("920", queries.get(5).get("max_time"));
        for (Map<String, String> query : queries) {
            assertEquals("1", query.get("source_type"));
            assertEquals("0", query.get("min_time"));
        }
    }

    @Test public void sourceTypeOneDoesNotRequireTheUnusedResponseMaxTime() throws Exception {
        Page first = page(true, 100, 200, 20, user("10", "First", 1, 0));
        first.maxTime = null;
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> result = load(FollowListPager.Kind.FOLLOWING, (kind, query) ->
                calls.getAndIncrement() == 0 ? first : page(false, 0, 0, 20, user("20", "Last", 1, 0)));
        assertEquals(2, result.size());
        assertEquals(2, calls.get());
    }

    @Test public void pageTraceDistinguishesRawAcceptedAndUniqueCountsWithoutProfileData() throws Exception {
        List<String> trace = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        FollowListPager.Fetcher fetcher = (kind, query) -> calls.getAndIncrement() == 0
                ? page(true, 100, 200, 20, user("10", "private-name", 1, 0),
                        user("10", "private-name", 1, 0), user("99", "recommended-name", 0, 0))
                : page(false, 0, 0, 20, user("20", "other-name", 2, 1));
        Map<String, ProfileListEntry> result = FollowListPager.loadDetails(FollowListPager.Kind.FOLLOWING,
                "4242", "private-token", fetcher, () -> true, trace::add);
        assertEquals(2, result.size());
        assertEquals(Arrays.asList(
                "kind=FOLLOWING;page=1;raw_rows=3;accepted_rows=2;unique_total=1;has_more=true",
                "kind=FOLLOWING;page=2;raw_rows=1;accepted_rows=1;unique_total=2;has_more=false"), trace);
    }

    @Test public void followersIncludeFollowerAndMutualButExcludeFollowingOnlyAndRecommendations() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        Map<String, String> result = load(FollowListPager.Kind.FOLLOWERS, (kind, query) -> {
            assertFalse(query.containsKey("min_time"));
            assertFalse(query.containsKey("filter_type"));
            if (calls.getAndIncrement() == 0) {
                return page(true, 90, 200, 20, user("10", "follower", 0, 1),
                        user("20", "mutual", 2, null), user("30", "following only", 1, 0));
            }
            assertEquals("90", query.get("max_time"));
            assertEquals("20", query.get("offset"));
            return page(false, 0, 0, 20, user("40", "recommended", 0, 0));
        });
        assertEquals(2, result.size());
        assertEquals("follower", result.get("10"));
        assertEquals("mutual", result.get("20"));
    }

    @Test public void annotatedFieldsAndPublicGettersBothDecode() throws Exception {
        Map<String, String> annotated = load(FollowListPager.Kind.FOLLOWING,
                (kind, query) -> new AnnotatedResponse());
        assertEquals(Collections.singletonMap("10", "annotated"), annotated);
        Map<String, String> getters = load(FollowListPager.Kind.FOLLOWERS,
                (kind, query) -> new GetterResponse());
        assertEquals(Collections.singletonMap("20", "getter"), getters);
    }

    @Test public void relationshipDetailsKeepRealNicknameAvatarAndPublicDouyinId() throws Exception {
        User alice = user("10", "真实昵称", 1, 0);
        alice.remarkName = "我的备注";
        alice.uniqueId = " alice.id ";
        alice.shortId = "11111";
        alice.avatarThumb = new ProfileListEntryTest.Avatar();
        User bob = user("20", "Bob", 1, 0);
        bob.shortId = "22222";
        User unknown = user("30", "Unknown", 1, 0);
        Map<String, ProfileListEntry> result = FollowListPager.loadDetails(FollowListPager.Kind.FOLLOWING,
                "42", "sec-42", (kind, query) -> page(false, 0, 0, 0, alice, bob, unknown), () -> true);
        assertEquals("真实昵称", result.get("10").name);
        assertEquals("我的备注", ProfileListEntryTest.remark(result.get("10")));
        assertEquals("https://p3.douyinpic.com/avatar.jpg", result.get("10").avatarUrl);
        assertEquals("alice.id", result.get("10").douyinId);
        assertEquals("22222", result.get("20").douyinId);
        assertEquals("", result.get("30").douyinId);
        assertEquals("30", result.get("30").selectionKey);
    }

    @Test public void duplicateRowsFillMissingProfileFieldsWithoutLosingEarlierDetails() throws Exception {
        User first = user("10", "Alice", 1, 0);
        User later = user("10", "", 1, 0);
        later.uniqueId = "alice.id";
        later.avatarThumb = new ProfileListEntryTest.Avatar();
        AtomicInteger calls = new AtomicInteger();
        Map<String, ProfileListEntry> result = FollowListPager.loadDetails(FollowListPager.Kind.FOLLOWING,
                "42", "sec-42", (kind, query) -> calls.getAndIncrement() == 0
                        ? page(true, 100, 200, 20, first) : page(false, 0, 0, 20, later), () -> true);
        assertEquals("Alice", result.get("10").name);
        assertEquals("alice.id", result.get("10").douyinId);
        assertEquals("https://p3.douyinpic.com/avatar.jpg", result.get("10").avatarUrl);
    }

    @Test public void successRequiresExplicitStatusAndHasMore() {
        Page missingStatus = page(false, 0, 0, 0);
        missingStatus.statusCode = null;
        assertFails(missingStatus);
        Page missingHasMore = page(false, 0, 0, 0);
        missingHasMore.hasMore = null;
        assertFails(missingHasMore);
        Page invalidHasMore = page(false, 0, 0, 0);
        invalidHasMore.hasMore = 2;
        assertFails(invalidHasMore);
        Page invalidStatus = page(false, 0, 0, 0);
        invalidStatus.statusCode = 0.0;
        assertFails(invalidStatus);
    }

    @Test public void errorCodeMustSucceedEvenWhenStatusCodeIsZero() {
        Page error = page(false, 0, 0, 0, user("10", "member", 1, 0));
        error.errorCode = 403;
        assertFails(error);
        error.errorCode = null;
        error.statusCode = 1;
        assertFails(error);
    }

    @Test public void acceptsIntegralHasMoreAndErrorCodeOnlyResponse() throws Exception {
        Page page = page(false, 0, 0, 0);
        page.statusCode = null;
        page.errorCode = 0L;
        page.hasMore = 0;
        assertTrue(load(FollowListPager.Kind.FOLLOWERS, (kind, query) -> page).isEmpty());
    }

    @Test public void missingResponseOrUserCollectionFailsAndCompletedEmptyListSucceeds() throws Exception {
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING, (kind, query) -> null));
        Page missingUsers = page(false, 0, 0, 0);
        missingUsers.items = null;
        assertFails(missingUsers);
        assertTrue(load(FollowListPager.Kind.FOLLOWING, (kind, query) -> page(false, 0, 0, 0)).isEmpty());
    }

    @Test public void incompleteEmptyPageAndRepeatedCursorNeverReturnPartialUsers() {
        assertFails(page(true, 100, 200, 20));
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING,
                (kind, query) -> {
                    calls.incrementAndGet();
                    return page(true, 100, 200, 20, user("10", "member", 1, 0));
                }));
        assertEquals(2, calls.get());
        assertFails(page(true, 0, 0, 0, user("10", "member", 1, 0)));
    }

    @Test public void longerCursorCycleFails() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING,
                (kind, query) -> {
                    int step = calls.getAndIncrement();
                    return page(true, step == 1 ? 90 : 100, 0, step == 1 ? 40 : 20,
                            user("10", "member", 1, 0));
                }));
        assertEquals(3, calls.get());
    }

    @Test public void changingCrossPlatformCountDoesNotHideARepeatedCursor() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING,
                (kind, query) -> {
                    Page page = page(true, 100, 200, 20, user("10", "member", 1, 0));
                    page.vcdCount = calls.incrementAndGet();
                    return page;
                }));
        assertEquals(2, calls.get());
    }

    @Test public void incompleteCursorFieldsAndInvalidOffsetsFail() {
        for (String field : Arrays.asList("minTime", "offset")) {
            Page page = page(true, 100, 200, 20, user("10", "member", 1, 0));
            if ("minTime".equals(field)) page.minTime = null;
            if ("offset".equals(field)) page.offset = null;
            assertFails(page);
        }
        Page negative = page(true, -1, 0, 20, user("10", "member", 1, 0));
        assertFails(negative);
        Page overflow = page(true, 100, 200, 20, user("10", "member", 1, 0));
        overflow.offset = (long) Integer.MAX_VALUE + 1;
        assertFails(overflow);
    }

    @Test public void malformedMembersAndTraversalFailuresCannotPublishPartialList() {
        assertFails(page(false, 0, 0, 0, user("", "member", 1, 0)));
        assertFails(page(false, 0, 0, 0, user("not-a-uid", "member", 1, 0)));
        assertFails(page(false, 0, 0, 0, (User) null));
        Page page = page(false, 0, 0, 0);
        page.items = () -> new Iterator<Object>() {
            private boolean emitted;
            @Override public boolean hasNext() {
                if (emitted) throw new IllegalStateException("Iterator failed");
                return true;
            }
            @Override public Object next() { emitted = true; return user("10", "member", 1, 0); }
        };
        assertFails(page);
    }

    @Test public void missingMembershipFieldsCannotTurnRealMembersIntoACompleteEmptyList() {
        assertFails(page(false, 0, 0, 0, user("10", "unknown", null, null)));
        assertFails(page(false, 0, 0, 0, user("10", "unknown", 5, 0)));
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWERS,
                (kind, query) -> page(false, 0, 0, 0, user("10", "unknown", 0, null))));
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWERS,
                (kind, query) -> page(false, 0, 0, 0, user("10", "unknown", 0, 3))));
    }

    @Test public void fetchFailureAfterValidPagePropagatesWithoutResult() {
        AtomicInteger calls = new AtomicInteger();
        IOException cause = new IOException("network failed");
        IOException failure = assertThrows(IOException.class, () -> load(FollowListPager.Kind.FOLLOWERS,
                (kind, query) -> {
                    if (calls.getAndIncrement() == 0) return page(true, 100, 0, 20, user("10", "member", 0, 1));
                    throw cause;
                }));
        assertSame(cause, failure);
    }

    @Test public void accountMustRemainCurrentBeforeAfterAndDuringPageTraversal() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWING,
                "42", "sec-42", (kind, query) -> { calls.incrementAndGet(); return page(false, 0, 0, 0); },
                () -> false));
        assertEquals(0, calls.get());
        AtomicBoolean valid = new AtomicBoolean(true);
        assertThrows(IllegalStateException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWING,
                "42", "sec-42", (kind, query) -> { valid.set(false); return page(false, 0, 0, 0); }, valid::get));
        AtomicInteger checks = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWING,
                "42", "sec-42", (kind, query) -> page(false, 0, 0, 0, user("10", "member", 1, 0)),
                () -> checks.incrementAndGet() < 3));
    }

    @Test public void invalidAccountFailsBeforeRequest() {
        AtomicInteger calls = new AtomicInteger();
        FollowListPager.Fetcher fetcher = (kind, query) -> { calls.incrementAndGet(); return page(false, 0, 0, 0); };
        assertThrows(IllegalArgumentException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWING,
                "invalid", "sec-42", fetcher, () -> true));
        assertThrows(IllegalArgumentException.class, () -> FollowListPager.load(FollowListPager.Kind.FOLLOWING,
                "42", "", fetcher, () -> true));
        assertEquals(0, calls.get());
    }

    @Test public void pageLimitStopsAdvancingButUnfinishedList() {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING,
                (kind, query) -> {
                    int index = calls.incrementAndGet();
                    return page(true, index, 0, index, user("10", "member", 1, 0));
                }));
        assertEquals(FollowListPager.MAX_PAGES, calls.get());
    }

    @Test public void userLimitStopsAnOversizedResponse() {
        Page page = page(false, 0, 0, 0);
        page.items = () -> new Iterator<Object>() {
            int index;
            @Override public boolean hasNext() { return index <= FollowListPager.MAX_USERS; }
            @Override public Object next() { return user(Integer.toString(++index), "member", 1, 0); }
        };
        assertFails(page);
    }

    private static Map<String, String> load(FollowListPager.Kind kind, FollowListPager.Fetcher fetcher) throws Exception {
        return FollowListPager.load(kind, "42", "sec-42", fetcher, () -> true);
    }

    private static void assertFails(Page page) {
        assertThrows(IllegalStateException.class, () -> load(FollowListPager.Kind.FOLLOWING, (kind, query) -> page));
    }

    private static Page page(boolean more, long minTime, long maxTime, int offset, User... users) {
        return new Page(more, minTime, maxTime, offset, Arrays.asList(users));
    }

    private static User user(String uid, String name, Integer follow, Integer follower) {
        return new User(uid, name, follow, follower);
    }

    static final class Page {
        Object statusCode = 0;
        Object errorCode;
        Object hasMore;
        Object minTime;
        Object maxTime;
        Object offset;
        Object storePage;
        Object vcdCount;
        Iterable<?> items;
        Page(boolean more, long minTime, long maxTime, int offset, Iterable<?> items) {
            this.hasMore = more; this.minTime = minTime; this.maxTime = maxTime;
            this.offset = offset; this.items = items;
        }
    }

    static final class User {
        final String uid;
        final String nickname;
        final Integer followStatus;
        final Integer followerStatus;
        String remarkName = "";
        String uniqueId = "";
        String shortId = "";
        @SerializedName("avatar_thumb") Object avatarThumb;
        User(String uid, String nickname, Integer follow, Integer follower) {
            this.uid = uid; this.nickname = nickname; this.followStatus = follow; this.followerStatus = follower;
        }
    }

    static final class AnnotatedResponse {
        @SerializedName("status_code") final int a = 0;
        @SerializedName("has_more") final boolean b = false;
        @SerializedName("followings") final List<Object> c = Collections.singletonList(new AnnotatedUser());
    }

    static final class AnnotatedUser {
        @SerializedName("uid") final String a = "10";
        @SerializedName("nickname") final String b = "annotated";
        @SerializedName("follow_status") final int c = 1;
    }

    public static final class GetterResponse {
        public int getStatusCode() { return 0; }
        public boolean getHasMore() { return false; }
        public List<Object> getItems() { return Collections.singletonList(new GetterUser()); }
    }

    public static final class GetterUser {
        public String getUid() { return "20"; }
        public String getNickname() { return "getter"; }
        public int getFollowerStatus() { return 1; }
    }
}
