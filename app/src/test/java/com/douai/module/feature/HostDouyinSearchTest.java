package com.douai.module.feature;

import com.douai.module.scan.HostAdaptation;

import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

/** Runs the real reflection bridge against isolated host-shaped classes, without a host or network. */
public final class HostDouyinSearchTest {
    private static Map<String, byte[]> definitions;
    private FixtureLoader loader;
    private Class<?> state;
    private ExecutorService workers;

    @BeforeClass public static void loadIsolatedHostDefinitions() throws Exception {
        Map<String, String> names = new LinkedHashMap<>();
        names.put(HostState.class.getName(), "fixture.State");
        names.put(HostDisposable.class.getName(), "io.reactivex.disposables.Disposable");
        names.put(HostObserver.class.getName(), "io.reactivex.Observer");
        names.put(HostObservable.class.getName(), "io.reactivex.Observable");
        names.put(HostObservable.class.getName() + "$1", "io.reactivex.Observable$FixtureDisposable");
        names.put(HostAbstractRequest.class.getName(), "com.ss.android.ugc.aweme.search.op.standard.AbstractSearchRequest");
        names.put(HostVideoRequest.class.getName(), "com.ss.android.ugc.aweme.search.op.standard.SearchVideoListRequest");
        names.put(HostToken.class.getName(), "X.0fOQ");
        names.put(HostSearchToken.class.getName(), "X.0fPi");
        names.put(HostInterceptor.class.getName(), "X.0exk");
        names.put(HostAssembler.class.getName(), "X.0exj");
        names.put(HostProvider.class.getName(), "X.0Uvf");
        names.put(HostApi.class.getName(), "com.ss.android.ugc.aweme.discover.api.SearchApi$RealApi");
        names.put(HostResponse.class.getName(), "X.0lv8");
        names.put(HostFeed.class.getName(), "com.ss.android.ugc.aweme.discover.model.SearchAwemeFeed");
        names.put(HostAweme.class.getName(), "com.ss.android.ugc.aweme.feed.model.Aweme");
        names.put(HostUser.class.getName(), "com.ss.android.ugc.aweme.profile.model.User");
        names.put(HostShare.class.getName(), "com.ss.android.ugc.aweme.base.share.ShareInfo");
        names.put(HostSummary.class.getName(), "com.ss.android.ugc.aweme.feed.model.search.VideoAbstract");
        names.put(HostLogPb.class.getName(), "com.ss.android.ugc.aweme.feed.model.LogPbBean");
        definitions = new HashMap<>();
        for (Map.Entry<String, String> name : names.entrySet()) {
            String resource = "/" + name.getKey().replace('.', '/') + ".class";
            try (java.io.InputStream input = HostDouyinSearchTest.class.getResourceAsStream(resource)) {
                assertNotNull("Missing compiled host fixture " + resource, input);
                definitions.put(name.getValue(), renameClassConstants(input.readAllBytes(), names));
            }
        }
    }

    @Before public void isolateHostState() throws Exception {
        loader = new FixtureLoader(Collections.emptyList());
        state = Class.forName("fixture.State", true, loader);
        workers = Executors.newFixedThreadPool(3, task -> {
            Thread thread = new Thread(task, "host-search-test"); thread.setDaemon(true); return thread;
        });
    }

    @After public void releasePendingFixtureCallbacks() throws Exception {
        latch("releaseSubscribe").countDown();
        latch("releaseDispose").countDown();
        latch("releaseNormalize").countDown();
        workers.shutdownNow();
        assertTrue(workers.awaitTermination(3, TimeUnit.SECONDS));
        awaitHostWork();
    }

    private static void awaitHostWork() throws Exception {
        ThreadPoolExecutor nativeWorkers = (ThreadPoolExecutor) hostExecutor("SEARCH_WORK");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        // Cancellation can finish the caller before its work leaves this shared executor.
        while (nativeWorkers.getActiveCount() != 0 || !nativeWorkers.getQueue().isEmpty()) {
            assertTrue("Host search workers remained active after fixture cleanup",
                    System.nanoTime() < deadline);
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        long remaining = deadline - System.nanoTime();
        assertTrue("Host search cleanup exceeded its deadline", remaining > 0);
        hostExecutor("DISPOSE_WORK").submit(() -> { }).get(remaining, TimeUnit.NANOSECONDS);
    }

    @Test public void cleanupWaitsForNativeWorkBeforeTheNextSearch() throws Exception {
        ThreadPoolExecutor nativeWorkers = (ThreadPoolExecutor) hostExecutor("SEARCH_WORK");
        CountDownLatch occupied = new CountDownLatch(nativeWorkers.getCorePoolSize());
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService cleanupWorker = Executors.newSingleThreadExecutor();
        try {
            for (int index = 0; index < nativeWorkers.getCorePoolSize(); index++) {
                nativeWorkers.execute(() -> {
                    occupied.countDown();
                    try { release.await(); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                });
            }
            assertTrue(occupied.await(2, TimeUnit.SECONDS));
            int slots = nativeWorkers.getQueue().remainingCapacity();
            for (int index = 0; index < slots; index++) nativeWorkers.execute(() -> { });
            CountDownLatch cleanupStarted = new CountDownLatch(1);
            Future<?> cleanup = cleanupWorker.submit(() -> {
                cleanupStarted.countDown();
                releasePendingFixtureCallbacks();
                return null;
            });
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS));
            try {
                assertThrows(TimeoutException.class, () -> cleanup.get(100, TimeUnit.MILLISECONDS));
            } finally { release.countDown(); }
            cleanup.get(3, TimeUnit.SECONDS);
            invalidAndMissingVideoObjectsNeverBecomeInventedVideoResults();
        } finally {
            release.countDown();
            cleanupWorker.shutdownNow();
            assertTrue(cleanupWorker.awaitTermination(3, TimeUnit.SECONDS));
        }
    }

    private static ExecutorService hostExecutor(String name) throws Exception {
        java.lang.reflect.Field field = HostDouyinSearch.class.getDeclaredField(name);
        field.setAccessible(true);
        return (ExecutorService) field.get(null);
    }

    @Test public void oldAndUnknownProfilesDoNotEvenInspectHostClasses() throws Exception {
        ClassLoader forbidden = new ClassLoader(null) {
            @Override public Class<?> loadClass(String name) { throw new AssertionError("Unexpected host lookup"); }
        };
        assertNull(HostDouyinSearch.create(forbidden, HostAdaptation.PROFILE_406));
        assertNull(HostDouyinSearch.create(forbidden, HostAdaptation.PROFILE_408));
        assertNull(HostDouyinSearch.create(forbidden, null));
    }

    @Test public void creationAndPreparationOnlyResolveSignaturesWithoutInitializingHostNetwork() throws Exception {
        DouyinSearch search = bridge();
        assertNotNull(search.prepare("query words", 3));
        assertEquals(0, count("initialized"));
        assertEquals(0, count("requestConstructors"));
        assertEquals(0, count("providerCalls"));
        assertEquals(0, count("apiCalls"));
    }

    @Test public void queryLengthIsValidatedBeforeAnyHostWork() throws Exception {
        DouyinSearch search = bridge();
        char[] maximum = new char[200]; Arrays.fill(maximum, 'q');
        assertNotNull(search.prepare(new String(maximum), 1));
        char[] excessive = new char[201]; Arrays.fill(excessive, 'q');
        assertThrows(IllegalArgumentException.class, () -> search.prepare(new String(excessive), 1));
        assertThrows(IllegalArgumentException.class, () -> search.prepare("   ", 1));
        assertThrows(IllegalArgumentException.class, () -> search.prepare(null, 1));
        assertEquals(0, count("requestConstructors"));
        assertEquals(0, count("apiCalls"));
    }

    @Test public void missingReviewedHostTypesFailBeforeAnyProviderOrRequestIsInvoked() {
        FixtureLoader incomplete = new FixtureLoader(Collections.singletonList("X.0Uvf"));
        assertThrows(ReflectiveOperationException.class,
                () -> HostDouyinSearch.create(incomplete, HostAdaptation.PROFILE_410));
        assertEquals(0, count("apiCalls"));
        assertEquals(0, count("providerCalls"));
    }

    @Test public void firstPageUsesNativeParameterBuilderAndReturnsAtMostFiveSmallVideoItems() throws Exception {
        List<Object> awemes = new ArrayList<>();
        for (int i = 0; i < 8; i++) awemes.add(aweme("" + (100 + i), "title " + i,
                "author " + i, "https://www.douyin.com/video/" + (100 + i), "summary " + i));
        response(awemes, false);
        DouyinSearch.Result result = bridge().prepare("query words", 100).run(deadline());
        assertEquals("query words", result.query);
        assertEquals(5, result.items.size());
        assertEquals(5, result.nativeAwemes.size());
        for (int i = 0; i < 5; i++) assertSame(awemes.get(i), result.nativeAwemes.get(i));
        DouyinSearch.Item item = result.items.get(0);
        assertEquals("100", item.awemeId);
        assertEquals("title 0", item.title);
        assertEquals("author 0", item.authorName);
        assertEquals("https://www.douyin.com/video/100", item.shareUrl);
        assertEquals("summary 0", item.summary);
        assertThrows(UnsupportedOperationException.class, () -> result.items.clear());
        assertThrows(UnsupportedOperationException.class, () -> result.nativeAwemes.clear());
        Map<?, ?> parameters = (Map<?, ?>) get("parameters");
        assertEquals("query words", parameters.get("keyword"));
        assertEquals("5", parameters.get("count"));
        assertEquals("0", parameters.get("offset"));
        assertEquals("video_search", parameters.get("source"));
        assertEquals("1", parameters.get("need_filter_settings"));
        assertEquals("video", parameters.get("nice_search_type"));
        assertEquals("search", parameters.get("token"));
        assertEquals("douyin_search", parameters.get("search_scene"));
        assertEquals("1", parameters.get("vlm_config"));
        assertEquals("host-privacy-setting", parameters.get("no_trace_search_switch"));
        assertEquals(1, count("mapBuildCalls"));
        assertEquals(1, count("apiCalls"));
    }

    @Test public void nextPageUsesActualCursorAndSearchIdAndReturnsOnlyTheCurrentNativePage() throws Exception {
        Object work = aweme("201", "next page", "author", null, null);
        response(Collections.singletonList(work), false);
        pagination(17, true, "server-next");

        DouyinSearch.Result result = bridge().preparePage("query", 100, 5, "server-first").run(deadline());

        Map<?, ?> parameters = (Map<?, ?>) get("parameters");
        assertEquals("5", parameters.get("offset"));
        assertEquals("server-first", parameters.get("search_id"));
        assertEquals("5", parameters.get("count"));
        assertEquals(17, result.nextOffset);
        assertTrue(result.hasMore);
        assertEquals("server-next", result.searchId);
        assertEquals(1, result.nativeAwemes.size());
        assertSame(work, result.nativeAwemes.get(0));
        assertEquals(1, count("apiCalls"));
    }

    @Test public void presentationPreservesAnOversizedHostPageBeforeAdvancingItsCursor() throws Exception {
        List<Object> works = new ArrayList<>();
        for (int index = 0; index < 8; index++) works.add(aweme("20" + index, "caption " + index, null, null, null));
        response(works, false);
        pagination(17, true, "server-next");

        DouyinSearch.Result page = bridge().preparePage("query", 5, 0, "").run(deadline());

        assertEquals("5", ((Map<?, ?>) get("parameters")).get("count"));
        assertEquals(8, page.items.size());
        assertEquals(8, page.nativeAwemes.size());
        for (int index = 0; index < works.size(); index++) assertSame(works.get(index), page.nativeAwemes.get(index));
        assertEquals(17, page.nextOffset);
        assertTrue(page.hasMore);

        while (works.size() < 51) works.add(aweme("work-" + works.size(), "caption", null, null, null));
        response(works, false);
        pagination(68, true, "server-overflow");
        DouyinSearch.Result overflow = bridge().preparePage("query", 5, 17, "server-next").run(deadline());
        assertEquals(50, overflow.items.size());
        assertFalse("A truncated oversized page cannot safely skip unseen works with the server cursor", overflow.hasMore);
    }

    @Test public void paginationStopsWhenCursorDoesNotAdvanceOrHostOmitsContinuationData() throws Exception {
        DouyinSearch search = bridge();
        for (int cursor : new int[]{-1, 0, 5}) {
            response(Collections.singletonList(aweme("201", "caption", null, null, null)), false);
            pagination(cursor, true, "search-id");
            assertFalse(search.preparePage("query", 5, 5, "search-id").run(deadline()).hasMore);
        }
        response(Collections.singletonList(aweme("201", "caption", null, null, null)), false);
        pagination(10, false, "search-id");
        assertFalse(search.preparePage("query", 5, 5, "search-id").run(deadline()).hasMore);
        response(Collections.singletonList(aweme("201", "caption", null, null, null)), false);
        pagination(10, true, null);
        assertFalse(search.prepare("query", 5).run(deadline()).hasMore);
        assertFalse(new DouyinSearch.Result("query", Collections.emptyList()).hasMore);
    }

    @Test public void paginatedRequestsValidateBeforeStartingAndCanBeCancelledBeforeRun() throws Exception {
        DouyinSearch search = bridge();
        for (long offset : new long[]{-1, (long) Integer.MAX_VALUE + 1}) {
            assertThrows(IllegalArgumentException.class, () -> search.preparePage("query", 5, offset, "search"));
        }
        for (String searchId : Arrays.asList("", "private\nheader", "s".repeat(257))) {
            assertThrows(IllegalArgumentException.class, () -> search.preparePage("query", 5, 5, searchId));
        }
        DouyinSearch.Request pending = search.preparePage("query", 5, 5, "search");
        pending.cancel();
        assertThrows(CancellationException.class, () -> pending.run(deadline()));
        assertEquals(0, count("apiCalls"));
        assertEquals(0, count("requestConstructors"));
    }

    @Test public void presentationCoverUsesOnlySupportedHostMediaAndStaysOutOfTheOriginalItemConstructor() throws Exception {
        Object work = aweme("201", "caption", null, null, null);
        HostVideo video = new HostVideo();
        video.cover = new HostAddress();
        video.cover.url_list = Collections.singletonList("https://p.byteimg.com/cover.jpg");
        put(work, "video", video);
        response(Collections.singletonList(work), false);
        assertEquals("https://p.byteimg.com/cover.jpg", bridge().prepare("query", 5).run(deadline()).items.get(0).coverUrl);
        video.cover.url_list = Collections.singletonList("https://unrelated.test/cover.jpg");
        response(Collections.singletonList(work), false);
        assertEquals("", bridge().prepare("query", 5).run(deadline()).items.get(0).coverUrl);
        assertEquals("", new DouyinSearch.Item("1", "title", "author", "", "").coverUrl);
    }

    @Test public void coverCandidatesSkipAnInvalidFirstUrlAndPreserveSafeAlternatives() {
        HostVideo video = new HostVideo();
        video.cover = address("http://p.byteimg.com/insecure.jpg", "https://unrelated.test/no.jpg",
                "https://p.byteimg.com/primary.jpg", "https://p.byteimg.com/backup.jpg");
        assertEquals(List.of("https://p.byteimg.com/primary.jpg", "https://p.byteimg.com/backup.jpg"),
                HostDouyinSearch.covers(new CoverWork(video)));
    }

    @Test public void staticVariantsKeepPriorityBeforeDynamicSourcesAndDoNotCrowdOutBackups() {
        HostVideo video = new HostVideo();
        video.cover = address("https://p.byteimg.com/primary.jpg", "https://p.byteimg.com/primary2.jpg");
        video.thumb_cover = address("https://p.byteimg.com/thumb.jpg", "https://p.byteimg.com/thumb2.jpg");
        video.origin_cover = address("https://p.byteimg.com/origin.jpg", "https://p.byteimg.com/origin2.jpg");
        video.medium_cover = address("https://p.byteimg.com/medium.jpg", "https://p.byteimg.com/medium2.jpg");
        video.optimized_cover = address("https://p.byteimg.com/optimized.jpg", "https://p.byteimg.com/optimized2.jpg");
        video.dynamic_cover = address("https://p.byteimg.com/dynamic.webp");
        List<String> candidates = HostDouyinSearch.covers(new CoverWork(video));
        assertEquals(8, candidates.size());
        assertEquals(List.of("https://p.byteimg.com/primary.jpg", "https://p.byteimg.com/thumb.jpg",
                "https://p.byteimg.com/origin.jpg", "https://p.byteimg.com/medium.jpg",
                "https://p.byteimg.com/optimized.jpg"), candidates.subList(0, 5));
        assertFalse(candidates.contains("https://p.byteimg.com/dynamic.webp"));
        assertThrows(UnsupportedOperationException.class, candidates::clear);
    }

    @Test public void galleryFirstImageIsAValidFallbackWithoutUsingAuthorOrPlaybackMedia() {
        CoverWork work = new CoverWork(null);
        work.images = List.of(address("https://p.douyinpic.com/first.jpg", "https://p.douyinpic.com/second-cdn.jpg"),
                address("https://p.douyinpic.com/not-first-image.jpg"));
        assertEquals(List.of("https://p.douyinpic.com/first.jpg", "https://p.douyinpic.com/second-cdn.jpg"),
                HostDouyinSearch.covers(work));
        work.video = new HostVideo();
        work.video.dynamic_cover = address("https://p.byteimg.com/dynamic.webp");
        assertFalse(HostDouyinSearch.covers(work).contains("https://p.byteimg.com/dynamic.webp"));
        work.images = Collections.emptyList();
        assertEquals(List.of("https://p.byteimg.com/dynamic.webp"), HostDouyinSearch.covers(work));
        assertTrue(HostDouyinSearch.covers(new Object()).isEmpty());
    }

    @Test public void presentationCoverCandidatesAreDefensivelyCopiedAndLegacyConstructorsRemainValid() {
        List<String> candidates = new ArrayList<>(List.of("https://p.byteimg.com/a.jpg", "https://p.byteimg.com/b.jpg"));
        DouyinSearch.Item item = new DouyinSearch.Item("1", "title", "author", "", "", candidates.get(0), candidates);
        candidates.clear();
        assertEquals(2, item.coverUrls.size());
        assertThrows(UnsupportedOperationException.class, item.coverUrls::clear);
        assertEquals(List.of("https://p.byteimg.com/a.jpg"),
                new DouyinSearch.Item("1", "title", "author", "", "", "https://p.byteimg.com/a.jpg").coverUrls);
        assertTrue(new DouyinSearch.Item("1", "title", "author", "", "").coverUrls.isEmpty());
    }

    private static HostAddress address(String... urls) {
        HostAddress value = new HostAddress();
        value.url_list = Arrays.asList(urls);
        return value;
    }

    @Test public void nativeVideoResultsKeepTheFirstUniqueWorkAlignedWithTextItems() throws Exception {
        Object first = aweme("101", "first caption", "first author", null, null);
        Object repeated = aweme("101", "repeated caption", "other author", null, null);
        Object second = aweme("102", "second caption", null, null, null);
        response(Arrays.asList(null, aweme("", "missing ID", null, null, null),
                first, repeated, second), true);

        DouyinSearch.Result result = bridge().prepare("query", 5).run(deadline());

        assertEquals(2, result.items.size());
        assertEquals(2, result.nativeAwemes.size());
        assertEquals("101", result.items.get(0).awemeId);
        assertEquals("first caption", result.items.get(0).title);
        assertSame(first, result.nativeAwemes.get(0));
        assertEquals("102", result.items.get(1).awemeId);
        assertSame(second, result.nativeAwemes.get(1));
    }

    @Test public void presentationPayloadIsAnImmutableCopyAndExistingTextOnlyResultsRemainEmpty() {
        Object nativeAweme = new Object();
        List<Object> hostResults = new ArrayList<>(Collections.singletonList(nativeAweme));
        List<DouyinSearch.Item> textResults = new ArrayList<>(Collections.singletonList(
                new DouyinSearch.Item("101", "caption", "author", "", "")));

        DouyinSearch.Result result = new DouyinSearch.Result("query", textResults, hostResults);
        hostResults.clear();
        textResults.clear();

        assertEquals(1, result.items.size());
        assertEquals(1, result.nativeAwemes.size());
        assertSame(nativeAweme, result.nativeAwemes.get(0));
        assertThrows(UnsupportedOperationException.class, () -> result.nativeAwemes.clear());
        assertTrue(new DouyinSearch.Result("query", result.items).nativeAwemes.isEmpty());
    }

    @Test public void nativeDataWrapperNormalizationPreservesMissingSummaryAndTheRequestedLimit() throws Exception {
        Object first = aweme("101", "caption", "nickname", "https://v.douyin.com/abc/", null);
        response(Arrays.asList(first, aweme("102", "second", "other", null, null)), true);
        DouyinSearch.Result result = bridge().prepare("query", 1).run(deadline());
        assertEquals(1, result.items.size());
        assertEquals("101", result.items.get(0).awemeId);
        assertEquals("", result.items.get(0).summary);
        assertEquals("https://v.douyin.com/abc/", result.items.get(0).shareUrl);
        assertSame(first, result.nativeAwemes.get(0));
    }

    @Test public void invalidAndMissingVideoObjectsNeverBecomeInventedVideoResults() throws Exception {
        response(Arrays.asList(null, aweme("", "missing ID", null, null, null),
                aweme("103", null, null, null, null)), false);
        DouyinSearch.Result result = bridge().prepare("query", 5).run(deadline());
        assertEquals(1, result.items.size());
        assertEquals("103", result.items.get(0).awemeId);
        assertEquals("", result.items.get(0).title);
        assertEquals("", result.items.get(0).authorName);
        assertEquals("", result.items.get(0).shareUrl);
    }

    @Test public void shareLinksRequireHttpsAndAnActualDouyinDomainWithoutUserInfo() throws Exception {
        DouyinSearch search = bridge();
        for (String url : Arrays.asList("http://www.douyin.com/video/1", "javascript:alert(1)",
                "https://douyin.com.evil.test/video/1", "https://evildouyin.com/video/1",
                "https://user:password@www.douyin.com/video/1", "https://www.douyin.com\\@evil.test/1")) {
            response(Collections.singletonList(aweme("104", "caption", null, url, null)), false);
            assertEquals("Unsafe host URL: " + url, "", search.prepare("query", 1).run(deadline()).items.get(0).shareUrl);
        }
        response(Collections.singletonList(aweme("105", "caption", null,
                "https://www.iesdouyin.com/share/video/105/", null)), false);
        assertEquals("https://www.iesdouyin.com/share/video/105/",
                search.prepare("query", 1).run(deadline()).items.get(0).shareUrl);
        response(Collections.singletonList(aweme("106", "caption", null,
                "https://www.douyin.com/video/106?token=PRIVATE_AUTH&from=share#fragment", null)), false);
        String sanitized = search.prepare("private query", 1).run(deadline()).items.get(0).shareUrl;
        assertEquals("https://www.douyin.com/video/106", sanitized);
        assertFalse(sanitized.contains("PRIVATE_AUTH"));
    }

    @Test public void cancelledOrExpiredRequestsCannotStartHostNetworkWork() throws Exception {
        DouyinSearch search = bridge();
        DouyinSearch.Request cancelled = search.prepare("query", 2);
        cancelled.cancel(); cancelled.cancel();
        assertThrows(CancellationException.class, () -> cancelled.run(deadline()));
        assertThrows(TimeoutException.class,
                () -> search.prepare("query", 2).run(System.nanoTime() - 1));
        assertEquals(0, count("apiCalls"));
    }

    @Test public void pendingRequestTimesOutAndDisposesTheHostSubscription() throws Exception {
        set("pending", true);
        DouyinSearch.Request request = bridge().prepare("query", 2);
        assertThrows(TimeoutException.class,
                () -> request.run(System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)));
        assertTrue(latch("disposeEntered").await(2, TimeUnit.SECONDS));
        assertEquals(1, count("disposeCalls"));
    }

    @Test public void completionAfterItsDeadlineIsStillReportedAsTimeout() throws Exception {
        set("blockNormalize", true);
        response(Collections.singletonList(aweme("101", "caption", "author", null, null)), false);
        DouyinSearch.Request request = bridge().prepare("private query", 1);
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(300);
        Future<DouyinSearch.Result> running = workers.submit(() -> request.run(deadline));
        assertTrue(latch("normalizeEntered").await(2, TimeUnit.SECONDS));
        long remaining = deadline - System.nanoTime();
        if (remaining > 0) assertFalse(latch("releaseNormalize").await(remaining, TimeUnit.NANOSECONDS));
        latch("releaseNormalize").countDown();
        ExecutionException failure = assertThrows(ExecutionException.class, () -> running.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof TimeoutException);
        assertEquals(TimeoutException.class, failure.getCause().getClass());
    }

    @Test public void saturatedHostWorkRejectsNewQueriesWithoutExposingExecutorDetails() throws Exception {
        set("pending", true); set("waitBeforeSubscribe", true);
        DouyinSearch search = bridge();
        List<DouyinSearch.Request> requests = new ArrayList<>();
        List<Future<?>> calls = new ArrayList<>();
        CountDownLatch ready = new CountDownLatch(24), start = new CountDownLatch(1), rejected = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<java.io.IOException> rejection = new java.util.concurrent.atomic.AtomicReference<>();
        ExecutorService callers = Executors.newFixedThreadPool(24, task -> {
            Thread thread = new Thread(task, "host-search-admission-test"); thread.setDaemon(true); return thread;
        });
        try {
            for (int index = 0; index < 24; index++) {
                DouyinSearch.Request request = search.prepare("query " + index, 1);
                requests.add(request);
                calls.add(callers.submit(() -> {
                    ready.countDown();
                    if (!start.await(2, TimeUnit.SECONDS)) throw new AssertionError("Admission start gate timed out");
                    try { request.run(System.nanoTime() + TimeUnit.SECONDS.toNanos(4)); }
                    catch (java.io.IOException unavailable) {
                        rejection.compareAndSet(null, unavailable); rejected.countDown();
                    } catch (CancellationException | TimeoutException expected) { }
                    return null;
                }));
            }
            assertTrue("All independent callers must be ready before filling the host queue",
                    ready.await(2, TimeUnit.SECONDS));
            start.countDown();
            assertTrue("Both host workers must be held inside native subscribe", latch("apiEntered").await(2, TimeUnit.SECONDS));
            assertTrue("A full bounded queue must reject admission promptly", rejected.await(1500, TimeUnit.MILLISECONDS));
            assertEquals("Both host workers must be held inside native subscribe", 2, count("apiCalls"));
            java.io.IOException failure = rejection.get();
            assertNotNull(failure);
            assertEquals("Host search unavailable", failure.getMessage());
            assertFalse(String.valueOf(failure.getMessage()).contains("RejectedExecution"));
            assertFalse(String.valueOf(failure.getMessage()).contains("ThreadPoolExecutor"));
            for (String sensitive : new String[]{"query 23", "overflow", "https://", "PRIVATE_AUTH", "Cookie", "token="})
                assertFalse("Failure must not expose " + sensitive, failure.getMessage().contains(sensitive));
            assertNull(failure.getCause());
        } finally {
            start.countDown();
            for (DouyinSearch.Request request : requests) request.cancel();
            latch("releaseSubscribe").countDown();
            latch("releaseNormalize").countDown();
            for (Future<?> call : calls) {
                try { call.get(2, TimeUnit.SECONDS); }
                catch (ExecutionException | CancellationException | TimeoutException expected) { }
            }
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(2, TimeUnit.SECONDS));
        }
    }

    @Test public void cancellationReturnsWithoutWaitingForHostDisposeAndDisposesExactlyOnce() throws Exception {
        set("pending", true); set("waitInDispose", true);
        DouyinSearch.Request request = bridge().prepare("query", 2);
        Future<DouyinSearch.Result> running = workers.submit(() -> request.run(deadline()));
        assertTrue(latch("subscribed").await(2, TimeUnit.SECONDS));
        workers.submit(request::cancel).get(500, TimeUnit.MILLISECONDS);
        assertTrue(latch("disposeEntered").await(2, TimeUnit.SECONDS));
        request.cancel();
        assertCancelled(running);
        assertEquals(1, count("disposeCalls"));
        latch("releaseDispose").countDown();
    }

    @Test public void cancellationBeforeOnSubscribeStillDisposesTheLateHostHandle() throws Exception {
        set("pending", true); set("waitBeforeSubscribe", true);
        DouyinSearch.Request request = bridge().prepare("query", 2);
        Future<DouyinSearch.Result> running = workers.submit(() -> request.run(deadline()));
        assertTrue(latch("subscribed").await(2, TimeUnit.SECONDS));
        request.cancel();
        assertCancelled(running);
        latch("releaseSubscribe").countDown();
        assertTrue(latch("disposeEntered").await(2, TimeUnit.SECONDS));
        assertEquals(1, count("disposeCalls"));
    }

    @Test public void hostErrorsExposeNoOriginalNetworkPayload() throws Exception {
        set("failure", new IllegalStateException("PRIVATE_COOKIE PRIVATE_SIGNATURE PRIVATE_RESPONSE"));
        Exception failure = assertThrows(Exception.class, () -> bridge().prepare("query", 2).run(deadline()));
        assertFalse(String.valueOf(failure.getMessage()).contains("PRIVATE_"));
        assertEquals(1, count("apiCalls"));
    }

    @Test public void serverRejectionDoesNotBecomeASuccessfulEmptySearch() throws Exception {
        response(Collections.emptyList(), false);
        put(get("response"), "status_code", 42);
        java.io.IOException failure = assertThrows(java.io.IOException.class,
                () -> bridge().prepare("private query", 2).run(deadline()));
        assertEquals("Host search unavailable", failure.getMessage());
        assertNull(failure.getCause());
        for (String sensitive : new String[]{"private query", "https://", "PRIVATE_AUTH", "Cookie", "token="})
            assertFalse("Failure must not expose " + sensitive, failure.getMessage().contains(sensitive));
        assertEquals(1, count("apiCalls"));
    }

    private DouyinSearch bridge() throws Exception { return HostDouyinSearch.create(loader, HostAdaptation.PROFILE_410); }
    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(3); }
    private Object get(String name) throws Exception { return state.getField(name).get(null); }
    private void set(String name, Object value) throws Exception { state.getField(name).set(null, value); }
    private int count(String name) {
        try { return ((AtomicInteger) get(name)).get(); }
        catch (Exception failure) { throw new AssertionError(failure); }
    }
    private CountDownLatch latch(String name) throws Exception { return (CountDownLatch) get(name); }
    private Object instance(String name) throws Exception { return loader.loadClass(name).getConstructor().newInstance(); }
    private static void put(Object owner, String name, Object value) throws Exception { owner.getClass().getField(name).set(owner, value); }

    private Object aweme(String id, String title, String author, String url, String summary) throws Exception {
        Object item = instance("com.ss.android.ugc.aweme.feed.model.Aweme");
        put(item, "aid", id); put(item, "desc", title);
        if (author != null) {
            Object user = instance("com.ss.android.ugc.aweme.profile.model.User");
            put(user, "nickname", author); put(item, "author", user);
        }
        if (url != null) {
            Object share = instance("com.ss.android.ugc.aweme.base.share.ShareInfo");
            put(share, "shareUrl", url); put(item, "shareInfo", share);
        }
        if (summary != null) {
            Object abstractText = instance("com.ss.android.ugc.aweme.feed.model.search.VideoAbstract");
            put(abstractText, "d", summary); put(item, "videoAbstract", abstractText);
        }
        return item;
    }

    private void response(List<Object> awemes, boolean wrapped) throws Exception {
        Object value = instance("X.0lv8");
        if (wrapped) {
            List<Object> feeds = new ArrayList<>();
            for (Object aweme : awemes) {
                Object feed = instance("com.ss.android.ugc.aweme.discover.model.SearchAwemeFeed");
                put(feed, "aweme", aweme); feeds.add(feed);
            }
            put(value, "awemeFeeds", feeds);
        } else put(value, "awemeList", awemes);
        set("response", value);
    }

    private void pagination(int cursor, boolean hasMore, String searchId) throws Exception {
        Object value = get("response");
        put(value, "cursor", cursor);
        put(value, "hasMore", hasMore);
        if (searchId != null) {
            Object logPb = instance("com.ss.android.ugc.aweme.feed.model.LogPbBean");
            put(logPb, "imprId", searchId);
            put(value, "logPb", logPb);
        }
    }

    private static void assertCancelled(Future<?> task) throws Exception {
        ExecutionException failure = assertThrows(ExecutionException.class, () -> task.get(1, TimeUnit.SECONDS));
        assertTrue(failure.getCause() instanceof CancellationException);
    }

    private static final class FixtureLoader extends ClassLoader {
        private final List<String> missing;
        FixtureLoader(List<String> missing) { super(HostDouyinSearchTest.class.getClassLoader()); this.missing = missing; }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if (missing.contains(name)) throw new ClassNotFoundException("Missing reviewed host type");
            synchronized (this) {
                Class<?> result = findLoadedClass(name);
                if (result == null && definitions.containsKey(name)) {
                    byte[] bytes = definitions.get(name); result = defineClass(name, bytes, 0, bytes.length);
                }
                if (result == null) result = super.loadClass(name, false);
                if (resolve) resolveClass(result);
                return result;
            }
        }
    }

    private static byte[] renameClassConstants(byte[] bytes, Map<String, String> names) throws Exception {
        List<Map.Entry<String, String>> replacements = new ArrayList<>(names.entrySet());
        replacements.sort((left, right) -> Integer.compare(right.getKey().length(), left.getKey().length()));
        ByteArrayOutputStream output = new ByteArrayOutputStream(bytes.length);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
             DataOutputStream target = new DataOutputStream(output)) {
            target.writeInt(input.readInt());
            target.writeShort(input.readUnsignedShort()); target.writeShort(input.readUnsignedShort());
            int constants = input.readUnsignedShort(); target.writeShort(constants);
            for (int index = 1; index < constants; index++) {
                int tag = input.readUnsignedByte(); target.writeByte(tag);
                switch (tag) {
                    case 1:
                        String value = input.readUTF();
                        for (Map.Entry<String, String> name : replacements) {
                            value = value.replace(name.getKey().replace('.', '/'), name.getValue().replace('.', '/'));
                        }
                        target.writeUTF(value); break;
                    case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                        target.writeInt(input.readInt()); break;
                    case 5: case 6:
                        target.writeLong(input.readLong()); index++; break;
                    case 7: case 8: case 16: case 19: case 20:
                        target.writeShort(input.readUnsignedShort()); break;
                    case 15:
                        target.writeByte(input.readUnsignedByte()); target.writeShort(input.readUnsignedShort()); break;
                    default: throw new AssertionError("Unexpected fixture constant-pool tag " + tag);
                }
            }
            target.write(input.readAllBytes());
        }
        return output.toByteArray();
    }

    public interface HostDisposable { void dispose(); boolean isDisposed(); }
    public interface HostObserver<T> {
        void onSubscribe(HostDisposable disposable);
        void onNext(T value);
        void onError(Throwable failure);
        void onComplete();
    }
    public interface HostApi { HostObservable<HostResponse> LJJIIZI(Map<String, String> parameters); }
    public interface HostInterceptor { Map<String, String> LIZ(HostAbstractRequest request); }

    public static final class HostState {
        public static final AtomicInteger initialized = new AtomicInteger(), requestConstructors = new AtomicInteger(),
                providerCalls = new AtomicInteger(), apiCalls = new AtomicInteger(), mapBuildCalls = new AtomicInteger(),
                disposeCalls = new AtomicInteger();
        public static final CountDownLatch subscribed = new CountDownLatch(1), apiEntered = new CountDownLatch(2),
                releaseSubscribe = new CountDownLatch(1), disposeEntered = new CountDownLatch(1),
                releaseDispose = new CountDownLatch(1), normalizeEntered = new CountDownLatch(1),
                releaseNormalize = new CountDownLatch(1);
        public static volatile Map<String, String> parameters;
        public static volatile Object response;
        public static volatile Throwable failure;
        public static volatile boolean pending, waitBeforeSubscribe, waitInDispose, blockNormalize;
        public static final HostApi API = parameters -> {
            apiCalls.incrementAndGet(); apiEntered.countDown(); HostState.parameters = new LinkedHashMap<>(parameters);
            return new HostObservable<>();
        };
    }

    public static class HostObservable<T> {
        @SuppressWarnings("unchecked") public final void subscribe(HostObserver<? super T> observer) {
            HostState.subscribed.countDown();
            if (HostState.waitBeforeSubscribe) await(HostState.releaseSubscribe);
            observer.onSubscribe(new HostDisposable() {
                public volatile boolean disposed;
                @Override public void dispose() {
                    disposed = true; HostState.disposeCalls.incrementAndGet(); HostState.disposeEntered.countDown();
                    if (HostState.waitInDispose) HostObservable.await(HostState.releaseDispose);
                }
                @Override public boolean isDisposed() { return disposed; }
            });
            if (HostState.pending) return;
            if (HostState.failure != null) { observer.onError(HostState.failure); return; }
            observer.onNext((T) HostState.response); observer.onComplete();
        }
        public static void await(CountDownLatch latch) {
            try { latch.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        }
    }

    public static class HostAbstractRequest {
        public transient Map<String, String> LJJII;
        public boolean LJJIII = true;
        public final String keyword;
        public int count = 10;
        public String searchId = "";
        public HostAbstractRequest(String keyword) { this.keyword = keyword == null ? "" : keyword; }
        public final void LJIILJJIL(int count) { this.count = count; }
        public final void LJJII(String searchId) { this.searchId = searchId; }
        public final Map<String, String> LJJIIZ() {
            Map<String, String> values = new LinkedHashMap<>();
            values.put("keyword", keyword); values.put("count", Integer.toString(count));
            values.put("nice_search_type", "video"); values.put("offset", "0");
            values.put("search_id", searchId);
            if (LJJII != null) values.putAll(LJJII);
            return values;
        }
        public final Map<String, String> LJ() {
            return Collections.singletonMap("no_trace_search_switch", "host-privacy-setting");
        }
    }
    public static final class HostVideoRequest extends HostAbstractRequest {
        static { HostState.initialized.incrementAndGet(); }
        public HostVideoRequest(String keyword) { super(keyword); HostState.requestConstructors.incrementAndGet(); }
    }
    public static abstract class HostToken {
        public final String LIZ;
        protected HostToken(String value) { LIZ = value; }
    }
    public static final class HostSearchToken extends HostToken {
        public static final HostSearchToken LIZJ = new HostSearchToken();
        public HostSearchToken() { super("search"); }
    }
    public static final class HostAssembler {
        public static Map<String, String> LIZIZ(HostToken token, HostAbstractRequest request, HostInterceptor interceptor) {
            HostState.mapBuildCalls.incrementAndGet();
            Map<String, String> values = request.LJJIIZ(); values.putAll(request.LJ());
            values.put("token", token.LIZ); values.put("search_scene", "douyin_search"); values.put("vlm_config", "1");
            if (interceptor != null) values.putAll(interceptor.LIZ(request));
            return values;
        }
    }
    public static final class HostProvider {
        static { HostState.initialized.incrementAndGet(); }
        public static HostApi LIZ() { HostState.providerCalls.incrementAndGet(); return HostState.API; }
    }
    public static final class HostResponse {
        public int status_code, error_code;
        public int cursor;
        public boolean hasMore;
        public HostLogPb logPb;
        public List<HostAweme> awemeList;
        public List<HostFeed> awemeFeeds;
        public final List<HostAweme> LJLZ() {
            if (HostState.blockNormalize) {
                HostState.normalizeEntered.countDown(); HostObservable.await(HostState.releaseNormalize);
            }
            if (awemeList != null) return awemeList;
            List<HostAweme> result = new ArrayList<>();
            if (awemeFeeds != null) for (HostFeed feed : awemeFeeds)
                if (feed != null && feed.getAweme() != null) result.add(feed.getAweme());
            return result;
        }
    }
    public static final class HostFeed {
        public HostAweme aweme;
        public HostAweme getAweme() { return aweme; }
    }
    public static class HostAweme {
        public String aid, desc, shareUrl;
        public Object video;
        public HostUser author;
        public HostShare shareInfo;
        public HostSummary videoAbstract;
        public String getAid() { return aid; }
    }
    public static class HostUser {
        public String nickname;
        public String getNickname() { return nickname; }
    }
    public static class HostShare { public String shareUrl; }
    public static final class HostSummary {
        public String d;
        public String LIZ() { return d; }
    }
    public static final class HostLogPb { public String imprId; }
    public static final class HostVideo {
        public HostAddress cover, thumb_cover, origin_cover, medium_cover, optimized_cover, dynamic_cover, animated_cover;
    }
    public static final class HostAddress { public List<String> url_list, download_url_list; }
    private static final class CoverWork {
        HostVideo video;
        List<HostAddress> images;
        CoverWork(HostVideo video) { this.video = video; }
    }
}
