package com.douai.module.feature;

import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;
import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;
import org.junit.Before;
import org.junit.Test;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

public final class NativeUserSearchSourceTest {
    private static Map<String, String> sent;
    private static Response response;
    private static Runnable duringFetch;
    private static int calls, factories;

    @Before public void reset() {
        sent = null; calls = factories = 0; duringFetch = () -> { };
        response = new Response(new SearchUser(new User("123", "服务端模糊结果")));
    }

    @Test public void nativeTypedAndDynamicMapsKeepKeywordAndContinuationAndServerMatches() throws Exception {
        NativeUserSearch.Page page = source().search(" Alice ", 17, "search-previous", () -> true);
        assertEquals("Alice", sent.get("keyword"));
        assertEquals("user", sent.get("nice_search_type"));
        assertFalse(sent.containsKey("search_channel"));
        assertEquals("1", sent.get("type"));
        assertEquals("10", sent.get("count"));
        assertEquals("17", sent.get("cursor"));
        assertEquals("user_search", sent.get("source"));
        assertEquals("douyin_search", sent.get("search_scene"));
        assertEquals("search", sent.get("token"));
        assertEquals("1", sent.get("vlm_config"));
        assertEquals("search-previous", sent.get("search_id"));
        assertEquals("dynamic", sent.get("location_access"));
        assertEquals("123", page.entries.get(0).uid);
        assertEquals("服务端模糊结果", page.entries.get(0).name);
        assertEquals("搜索备注", ProfileListEntryTest.remark(page.entries.get(0)));
        assertEquals("public.id", page.entries.get(0).douyinId);
        assertEquals("https://p3.douyinpic.com/avatar.jpg", page.entries.get(0).avatarUrl);
        assertEquals(30, page.cursor);
        assertTrue(page.hasMore);
        assertEquals("search-next", page.searchId);
    }

    @Test public void nativeRetrofitIsCachedAndNativeRxHasABoundedWait() throws Exception {
        NativeUserSearchSource source = source();
        source.search("Alice", 0, "", () -> true);
        source.search("Bob", 0, "", () -> true);
        assertEquals(2, calls);
        assertEquals(1, factories);
    }

    @Test public void nativeUserTypeRequestsUnfollowedResultsWithoutInventingASearchChannel() throws Exception {
        Map<String, String> defaults = new Request("unfollowed.user").LJJIIZ();
        assertEquals("user", defaults.get("nice_search_type"));
        assertFalse("The native constructor does not initialize search_channel", defaults.containsKey("search_channel"));
        response = new Response(new SearchUser(new User("456", "未关注的真实用户")));

        NativeUserSearch.Page page = source().search("unfollowed.user", 0, "", () -> true);

        assertEquals(1, calls);
        assertEquals("user", sent.get("nice_search_type"));
        assertFalse("Keep the optional native channel unset", sent.containsKey("search_channel"));
        assertEquals("456", page.entries.get(0).uid);
    }

    @Test public void dynamicPatchUsesTheHostGsonToRecoverTheActualUser() throws Exception {
        SearchUser wrapper = new SearchUser(null);
        wrapper.patch = new Patch();
        wrapper.patch.raw = "{\"user_info\":{\"uid\":\"456\",\"nickname\":\"动态用户\",\"remark_name\":\"动态备注\",\"short_id\":\"short456\"}}";
        response = new Response(wrapper);
        NativeUserSearch.Page page = source().search("anything", 0, "", () -> true);
        assertEquals("456", page.entries.get(0).uid);
        assertEquals("动态用户", page.entries.get(0).name);
        assertEquals("动态备注", ProfileListEntryTest.remark(page.entries.get(0)));
        assertEquals("short456", page.entries.get(0).douyinId);
    }

    @Test public void nonUserCardsAreSkippedAndDuplicateUidsKeepTheFirstRawProfile() throws Exception {
        response = new Response(new SearchUser(null), new SearchUser(new User("123", "first")),
                new SearchUser(new User("123", "duplicate")));
        NativeUserSearch.Page page = source().search("anything", 0, "", () -> true);
        assertEquals(1, page.entries.size());
        assertEquals("first", page.entries.get(0).name);
    }

    @Test public void failedOrMalformedNativePagesAreRejected() throws Exception {
        response.status_code = 1;
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.status_code = 0; response.error_code = 2;
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.error_code = 0; response.users.get(0).user.uid = 123L;
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.users.get(0).user.uid = "group:123";
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.users.get(0).user.uid = "123"; response.cursor = -1;
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
    }

    @Test public void staleWorkIsRejectedBeforeRequestAndAfterTheNativeWait() throws Exception {
        assertThrows(InterruptedException.class, () -> source().search("q", 0, "", () -> false));
        assertEquals(0, calls);
        AtomicBoolean current = new AtomicBoolean(true);
        duringFetch = () -> current.set(false);
        assertThrows(InterruptedException.class, () -> source().search("q", 0, "", current::get));
    }

    @Test public void onlyARealContinuationCanOfferMoreAndNullTerminalListsMeanNoResults() throws Exception {
        response.log.id = "";
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.log.id = "valid"; response.cursor = 0;
        assertThrows(IllegalStateException.class, () -> source().search("q", 0, "", () -> true));
        response.more = false; response.users = null; response.log = null;
        NativeUserSearch.Page page = source().search("q", 0, "", () -> true);
        assertTrue(page.entries.isEmpty());
        assertFalse(page.hasMore);
    }

    @Test public void invalidInputAndUnreviewedApiShapesNeverSendRequests() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> source().search(" ", 0, "", () -> true));
        assertThrows(IllegalArgumentException.class, () -> source().search("q", -1, "", () -> true));
        assertThrows(IllegalArgumentException.class, () -> source().search("q", 1, "", () -> true));
        NativeUserSearchSource bad = source(BadApi.class);
        assertThrows(ReflectiveOperationException.class, () -> bad.search("q", 0, "", () -> true));
        assertEquals(0, calls);
    }

    private static NativeUserSearchSource source() throws Exception { return source(Api.class); }
    private static NativeUserSearchSource source(Class<?> api) throws Exception {
        Method provider = Factory.class.getMethod("provider");
        HostCandidate target = new HostCandidate("fixture.apk", "classes.dex", HostTargets.descriptor(Factory.class),
                provider.getName(), HostTargets.descriptor(provider));
        HostScanReport report = new HostScanReport("fixture", Collections.singletonList(new RuleScanResult(
                "relation_retrofit_service", "search", Collections.singletonList(target))), false);
        return new NativeUserSearchSource(name -> {
            switch (name) {
                case "com.ss.android.ugc.aweme.search.op.standard.AbstractSearchRequest": return BaseRequest.class;
                case "com.ss.android.ugc.aweme.search.op.standard.SearchUserListRequest": return Request.class;
                case "com.ss.android.ugc.aweme.discover.api.SearchApi$RealApi": return api;
                case "com.ss.android.ugc.aweme.discover.model.SearchUser": return SearchUser.class;
                case "com.ss.android.ugc.aweme.profile.model.User": return User.class;
                case "com.ss.android.ugc.aweme.app.api.Api": return Prefix.class;
                case "com.ss.android.ugc.aweme.framework.services.IRetrofitService": return Provider.class;
                case "com.ss.android.ugc.aweme.framework.services.IRetrofit": return Retrofit.class;
                case "io.reactivex.Observable": return Observable.class;
                case "retrofit2.http.POST": return Post.class;
                case "retrofit2.http.FieldMap": return Fields.class;
                case "retrofit2.http.FormUrlEncoded": return Form.class;
                case "com.ss.android.ugc.aweme.utils.GsonProvider": return GsonProvider.class;
                case "com.ss.android.ugc.aweme.utils.GsonProvider$Companion": return Companion.class;
                case "com.google.gson.Gson": return Gson.class;
                default: return Class.forName(name);
            }
        }, report, 20_000);
    }

    @Retention(RetentionPolicy.RUNTIME) public @interface Post { String value(); }
    @Retention(RetentionPolicy.RUNTIME) public @interface Fields { }
    @Retention(RetentionPolicy.RUNTIME) public @interface Form { }
    public interface Api {
        @Form @Post("/aweme/v1/discover/search/") Observable search(@Fields Map<String, String> values);
    }
    public interface BadApi { Observable search(Map<String, String> values); }
    public static class BaseRequest {
        public Map<String, String> LJJII;
        String query, searchId = "", niceType = "", channel; int count;
        public BaseRequest(String query) { this.query = query; }
        public void LJIILJJIL(int count) { this.count = count; }
        public void LJJII(String id) { searchId = id; }
        public Map<String, String> LJJIIZ() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("keyword", query); values.put("count", Integer.toString(count));
            values.put("nice_search_type", niceType); values.put("type", "1");
            if (channel != null) values.put("search_channel", channel);
            values.put("cursor", "0"); values.put("search_id", searchId);
            if (LJJII != null) values.putAll(LJJII);
            return values;
        }
        public Map<String, String> LJ() { return Collections.singletonMap("location_access", "dynamic"); }
    }
    public static final class Request extends BaseRequest {
        public Request(String query) { super(query); niceType = "user"; }
    }
    public static final class Prefix { public static final String API_URL_PREFIX_SI = "https://native.invalid/"; }
    public interface Provider { Retrofit createNewRetrofit(String prefix); }
    public interface Retrofit { Object create(Class<?> type); }
    public static final class Factory {
        public static Provider provider() {
            factories++;
            return prefix -> {
                assertEquals(Prefix.API_URL_PREFIX_SI, prefix);
                return type -> (Api) values -> { sent = values; calls++; return new Observable(); };
            };
        }
    }
    public static final class Observable {
        public Observable timeout(long amount, TimeUnit unit) {
            assertEquals(20_000, unit.toMillis(amount)); return this;
        }
        public Object blockingFirst() { duringFetch.run(); return response; }
    }
    public static final class Response {
        public int status_code, error_code;
        @SerializedName("cursor") public int cursor = 30;
        @SerializedName("has_more") public boolean more = true;
        @SerializedName("user_list") public List<SearchUser> users;
        @SerializedName("log_pb") public LogPb log = new LogPb();
        Response(SearchUser... users) { this.users = Arrays.asList(users); }
    }
    public static final class LogPb { @SerializedName("impr_id") public String id = "search-next"; }
    public static final class SearchUser {
        @SerializedName("user_info") public User user;
        @SerializedName("dynamic_patch") public Patch patch;
        SearchUser(User user) { this.user = user; }
    }
    public static final class Patch { @SerializedName("raw_data") public String raw; }
    public static final class User {
        @SerializedName("uid") public Object uid;
        @SerializedName("nickname") public String raw;
        @SerializedName("remark_name") public String remark = "搜索备注";
        @SerializedName("unique_id") public String id;
        @SerializedName("short_id") public String shortId;
        User(String uid, String raw) { this.uid = uid; this.raw = raw; id = "public.id"; }
        public String getNickname() { return "备注覆盖"; }
        public Avatar getAvatarThumb() { return new Avatar(); }
    }
    public static final class Avatar {
        public List<String> getUrlList() { return Collections.singletonList("https://p3.douyinpic.com/avatar.jpg"); }
    }
    public interface GsonProvider {
        Companion Companion = new Companion();
        Gson getGson();
    }
    public static final class Companion { public GsonProvider get() { return Gson::new; } }
}
