package com.douai.module.ui;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.BitmapDrawable;
import android.widget.ImageView;
import android.widget.LinearLayout;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.feature.VideoCoverLoader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiSearchVideoCardsTest {
    @Test public void explicitNullAttributionNeverCapturesTheCurrentGeneration() throws Exception {
        Activity activity=Robolectric.buildActivity(Activity.class).setup().get();
        com.douai.module.analytics.LocalAnalytics store=com.douai.module.analytics.LocalAnalytics.get(activity);
        boolean before=store.isEnabled();store.setEnabled(true);
        ManualExecutor worker=new ManualExecutor();AiSearchVideoCards cards=new AiSearchVideoCards(activity,worker,worker);
        try {
            AiSearchVideoCards.Group group=cards.group(List.of(new DouyinSearch.Result("q",List.of(item("1","video")))),null,null);
            java.lang.reflect.Field attribution=group.getClass().getDeclaredField("analytics");attribution.setAccessible(true);
            assertNull(attribution.get(group));
        } finally {cards.close();store.setEnabled(before);activity.finish();}
    }    @Test public void aReboundFailedCoverStillOffersManualRetryAndCanRecover() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        boolean[] available = {false};
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker, () -> new VideoCoverLoader.Request() {
            @Override public Bitmap load(List<String> urls) {
                if (!available[0]) throw new IllegalStateException("cover unavailable");
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            }
        });
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        try {
            DouyinSearch.Item item = new DouyinSearch.Item("1", "视频", "作者", "", "", "https://p.byteimg.com/a.jpg");
            AiSearchVideoCards.Group group = cards.group(List.of(new DouyinSearch.Result("q", List.of(item))), null);
            cards.bind(row, group);
            for (int attempt = 0; attempt < 2; attempt++) {
                worker.runNext(); ShadowLooper.idleMainLooper();
                ShadowLooper.idleMainLooper(1, TimeUnit.SECONDS);
            }
            cards.bind(row, group);
            ImageView rebound = (ImageView) ((LinearLayout) row.getChildAt(0)).getChildAt(0);
            assertTrue(rebound.hasOnClickListeners());
            assertEquals("封面加载失败，点按重试", rebound.getContentDescription());
            available[0] = true;
            assertTrue(rebound.performClick());
            worker.runNext(); ShadowLooper.idleMainLooper();
            assertTrue(rebound.getDrawable() instanceof BitmapDrawable);
            assertFalse(rebound.hasOnClickListeners());
            assertEquals("视频封面", rebound.getContentDescription());
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void staggeredCoverFailuresKeepASeparateWakeupForTheLaterRetry() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        java.util.Map<String, Integer> attempts = new java.util.HashMap<>();
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker, () -> new VideoCoverLoader.Request() {
            @Override public Bitmap load(List<String> urls) {
                int count = attempts.merge(urls.get(0), 1, Integer::sum);
                if (count == 1) throw new IllegalStateException("first attempt");
                return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
            }
        });
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        try {
            List<DouyinSearch.Item> items = new ArrayList<>();
            for (int index = 0; index < 2; index++) items.add(new DouyinSearch.Item("" + index, "视频", "作者", "", "",
                    "https://p.byteimg.com/" + index + ".jpg"));
            cards.bind(row, cards.group(List.of(new DouyinSearch.Result("q", items)), null));
            worker.runNext(); ShadowLooper.idleMainLooper();
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
            worker.runNext(); ShadowLooper.idleMainLooper();
            ShadowLooper.idleMainLooper(700, TimeUnit.MILLISECONDS);
            worker.runNext(); ShadowLooper.idleMainLooper();
            ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS);
            worker.runNext(); ShadowLooper.idleMainLooper();
            assertEquals(2, attempts.get("https://p.byteimg.com/0.jpg").intValue());
            assertEquals(2, attempts.get("https://p.byteimg.com/1.jpg").intValue());
            for (int index = 0; index < 2; index++) {
                ImageView target = (ImageView) ((LinearLayout) row.getChildAt(index)).getChildAt(0);
                assertTrue(target.getDrawable() instanceof BitmapDrawable);
            }
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void aBusyCoverExecutorRetainsDemandAndDrainsEveryVisibleRequest() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor pages = new ManualExecutor();
        ManualExecutor covers = new ManualExecutor();
        int[] loads = {0};
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, pages, covers, () -> new VideoCoverLoader.Request() {
            @Override public Bitmap load(List<String> urls) { loads[0]++; return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); }
        });
        LinearLayout host = new LinearLayout(activity);
        host.setOrientation(LinearLayout.VERTICAL);
        activity.setContentView(host);
        try {
            covers.rejectNext = true;
            List<LinearLayout> rows = new ArrayList<>();
            for (int index = 0; index < 8; index++) {
                LinearLayout row = new LinearLayout(activity);
                host.addView(row);
                rows.add(row);
                DouyinSearch.Item item = new DouyinSearch.Item(Integer.toString(index + 1), "视频", "作者", "", "",
                        "https://p.byteimg.com/" + index + ".jpg");
                cards.bind(row, cards.group(List.of(new DouyinSearch.Result("q", List.of(item))), null));
            }
            for (int round = 0; round < 30; round++) {
                covers.runNext();
                ShadowLooper.idleMainLooper();
                ShadowLooper.idleMainLooper(300, TimeUnit.MILLISECONDS);
            }
            assertEquals(8, loads[0]);
            for (LinearLayout row : rows) {
                ImageView cover = (ImageView) ((LinearLayout) row.getChildAt(0)).getChildAt(0);
                assertTrue(cover.getDrawable() instanceof BitmapDrawable);
            }
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void stoppingPageRequestsKeepsOldCoversAndClearingRejectsTheirLateResults() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        int[] loads = {0};
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker, () -> new VideoCoverLoader.Request() {
            @Override public Bitmap load(List<String> urls) { loads[0]++; return Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888); }
        });
        LinearLayout row = new LinearLayout(activity);
        activity.setContentView(row);
        try {
            DouyinSearch.Item item = new DouyinSearch.Item("1", "视频", "作者", "", "", "https://p.byteimg.com/a.jpg");
            cards.bind(row, cards.group(List.of(new DouyinSearch.Result("q", List.of(item))), null));
            ImageView target = (ImageView) ((LinearLayout) row.getChildAt(0)).getChildAt(0);
            cards.cancelPages();
            worker.runNext(); ShadowLooper.idleMainLooper();
            assertEquals(1, loads[0]);
            assertTrue(target.getDrawable() instanceof BitmapDrawable);
            cards.clear();
            assertNull(target.getDrawable());
        } finally { cards.close(); activity.finish(); }
    }
    @Test public void resultsRenderAfterTheAnswerAndOnlySafeVideoLinksAreClickable() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker);
        try {
            AiSearchVideoCards.Group group = cards.group(List.of(new DouyinSearch.Result("舞蹈", List.of(
                    item("123", "真实视频"), new DouyinSearch.Item("not-an-id", "坏地址", "作者", "javascript:alert(1)", "")))), null);
            LinearLayout row = new LinearLayout(activity);
            cards.bind(row, group);
            assertEquals(3, row.getChildCount());
            assertTrue(row.getChildAt(0).isClickable());
            assertFalse(row.getChildAt(1).isClickable());
            assertNotNull(row.findViewWithTag("ai.chat.videos.more"));
            assertEquals(0, worker.tasks.size());
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void nextPageUsesItsReturnedCursorAndCancellationRejectsLateResults() throws Exception {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker);
        List<Long> offsets = new ArrayList<>();
        DouyinSearch bridge = new DouyinSearch() {
            @Override public Request prepare(String query, int limit) { throw new AssertionError("Use pagination"); }
            @Override public Request preparePage(String query, int limit, long offset, String searchId) {
                offsets.add(offset);
                assertEquals("sid", searchId);
                return new Request() {
                    @Override public Result run(long deadline) { return new Result("舞蹈", List.of(item("2", "下一页")), 20, true, "sid"); }
                    @Override public void cancel() { }
                };
            }
        };
        try {
            AiSearchVideoCards.Group group = cards.group(List.of(new DouyinSearch.Result("舞蹈", List.of(item("1", "首屏")), 10, true, "sid")), bridge);
            group.loadMore();
            group.loadMore();
            assertEquals(1, worker.tasks.size());
            worker.runNext();
            ShadowLooper.idleMainLooper();
            assertEquals(List.of(10L), offsets);
            assertEquals(2, group.itemCount());
            group.loadMore();
            cards.cancelPending();
            worker.runNext();
            ShadowLooper.idleMainLooper();
            assertEquals(2, group.itemCount());
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void snapshotDoesNotKeepUnboundedResultsOrUnsafeCovers() {
        DouyinSearch.Item unsafe = new DouyinSearch.Item("1", "标题", "作者", "https://www.douyin.com/video/1", "", "http://localhost/private");
        List<DouyinSearch.Result> source = new ArrayList<>();
        for (int index = 0; index < 3; index++) source.add(new DouyinSearch.Result("query" + index, List.of(unsafe)));
        List<DouyinSearch.Result> copied = AiSearchVideoCards.snapshot(source);
        assertEquals(2, copied.size());
        assertEquals("", copied.get(0).items.get(0).coverUrl);
        source.clear();
        assertEquals(2, copied.size());
        assertThrows(UnsupportedOperationException.class, () -> copied.clear());
    }

    @Test public void aServerPageKeepsAllReturnedItemsBeforeAdvancingItsCursor() {
        List<DouyinSearch.Item> items = new ArrayList<>();
        for (int index = 0; index < 17; index++) items.add(item(Integer.toString(index + 1), "视频" + index));
        DouyinSearch.Result page = new DouyinSearch.Result("query", items, 17, true, "sid");
        DouyinSearch.Result copy = AiSearchVideoCards.snapshot(List.of(page)).get(0);
        assertEquals(17, copy.items.size());
        assertEquals(17, copy.nextOffset);
        assertEquals("sid", copy.searchId);
        assertTrue(copy.hasMore);
        assertNotSame(page.items, copy.items);
    }

    @Test public void preservesTheFullSearchIdAndStopsRepeatedEmptyPages() {
        String searchId = "s".repeat(256);
        assertEquals(searchId, AiSearchVideoCards.snapshot(List.of(new DouyinSearch.Result(
                "query", List.of(item("1", "首屏")), 5, true, searchId))).get(0).searchId);
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker);
        int[] requests = {0};
        DouyinSearch bridge = new DouyinSearch() {
            @Override public Request prepare(String query, int limit) { throw new AssertionError("Use pagination"); }
            @Override public Request preparePage(String query, int limit, long offset, String sid) {
                requests[0]++;
                return new Request() {
                    @Override public Result run(long deadline) {
                        return new Result(query, List.of(item("1", "重复首屏")), offset + 5, true, sid);
                    }
                    @Override public void cancel() { }
                };
            }
        };
        try {
            AiSearchVideoCards.Group group = cards.group(List.of(new DouyinSearch.Result(
                    "query", List.of(item("1", "首屏")), 5, true, searchId)), bridge);
            for (int index = 0; index < 3; index++) {
                group.loadMore(); worker.runNext(); ShadowLooper.idleMainLooper();
            }
            assertEquals(2, requests[0]);
            assertEquals(1, group.itemCount());
            AiSearchVideoCards.Group unavailable = cards.group(List.of(new DouyinSearch.Result(
                    "query", List.of(item("2", "没有续页ID")), 5, true, "")), bridge);
            unavailable.loadMore();
            assertEquals(0, worker.tasks.size());
        } finally { cards.close(); activity.finish(); }
    }

    @Test public void aMismatchedResponseDoesNotAdvanceTheCurrentQueryCursor() {
        Activity activity = Robolectric.buildActivity(Activity.class).setup().get();
        ManualExecutor worker = new ManualExecutor();
        AiSearchVideoCards cards = new AiSearchVideoCards(activity, worker, worker);
        List<Long> offsets = new ArrayList<>();
        DouyinSearch bridge = new DouyinSearch() {
            @Override public Request prepare(String query, int limit) { throw new AssertionError("Use pagination"); }
            @Override public Request preparePage(String query, int limit, long offset, String sid) {
                offsets.add(offset);
                return new Request() {
                    @Override public Result run(long deadline) { return new Result("unrelated", List.of(item("2", "错误查询")), 99, true, sid); }
                    @Override public void cancel() { }
                };
            }
        };
        try {
            AiSearchVideoCards.Group group = cards.group(List.of(new DouyinSearch.Result(
                    "query", List.of(item("1", "首屏")), 5, true, "sid")), bridge);
            for (int index = 0; index < 2; index++) {
                group.loadMore(); worker.runNext(); ShadowLooper.idleMainLooper();
            }
            assertEquals(List.of(5L, 5L), offsets);
            assertEquals(1, group.itemCount());
        } finally { cards.close(); activity.finish(); }
    }

    private static DouyinSearch.Item item(String id, String title) {
        return new DouyinSearch.Item(id, title, "作者", "https://www.douyin.com/video/" + id, "");
    }
    private static final class ManualExecutor extends AbstractExecutorService {
        final List<Runnable> tasks = new ArrayList<>();
        boolean shutdown;
        boolean rejectNext;
        @Override public void execute(Runnable task) {
            if (rejectNext) { rejectNext = false; throw new RejectedExecutionException(); }
            tasks.add(task);
        }
        void runNext() { if (!tasks.isEmpty()) tasks.remove(0).run(); }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() { shutdown = true; return List.of(); }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
