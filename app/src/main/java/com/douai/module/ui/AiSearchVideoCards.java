package com.douai.module.ui;

import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import com.douai.module.feature.AutomationImages;
import com.douai.module.feature.VideoCoverLoader;
import com.douai.module.feature.DouyinSearch;
import com.douai.module.diagnostics.DouAiLog;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/** Ephemeral video results alongside a manual answer; pagination never calls a model or writes chat memory. */
final class AiSearchVideoCards implements AutoCloseable {
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService pages, images;
    private final List<Group> groups = new ArrayList<>();
    private final Map<String, Bitmap> thumbnails = new LinkedHashMap<>(8, .75f, true);
    private final Map<String, ImageRead> reads = new LinkedHashMap<>();
    private final Map<ImageView, CoverTarget> targets = new WeakHashMap<>();
    private final Set<ImageRead> runningCovers = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private final Map<String, Integer> coverFailures = new LinkedHashMap<>();
    private final Map<String, Long> coverRetryAt = new LinkedHashMap<>();
    private final Supplier<VideoCoverLoader.Request> coverRequests;
    private boolean retryScheduled;
    private final Runnable retryCovers = () -> { retryScheduled = false; drainCovers(); };
    private volatile int epoch;
    private volatile boolean closed;

    AiSearchVideoCards(Context context) {
        this(context, Executors.newSingleThreadExecutor(task -> daemon(task, "DouAi-chat-video-pages")),
                new ThreadPoolExecutor(2, 2, 20, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8),
                        task -> daemon(task, "DouAi-chat-video-covers"), new ThreadPoolExecutor.AbortPolicy()));
    }
    AiSearchVideoCards(Context context, ExecutorService pages, ExecutorService images) {
        this(context, pages, images, VideoCoverLoader.Request::new);
    }
    AiSearchVideoCards(Context context, ExecutorService pages, ExecutorService images, Supplier<VideoCoverLoader.Request> coverRequests) {
        this.context = context; this.pages = pages; this.images = images; this.coverRequests = coverRequests;
    }

    static List<DouyinSearch.Result> snapshot(List<DouyinSearch.Result> results) {
        List<DouyinSearch.Result> copied = new ArrayList<>();
        if (results == null) return Collections.emptyList();
        for (DouyinSearch.Result result : results) {
            if (result == null) continue;
            List<DouyinSearch.Item> items = new ArrayList<>();
            for (DouyinSearch.Item item : result.items) {
                if (item == null) continue;
                List<String> covers = safeCovers(item);
                String cover = covers.isEmpty() ? "" : covers.get(0);
                items.add(new DouyinSearch.Item(bounded(item.awemeId, 80), bounded(item.title, 600),
                        bounded(item.authorName, 100), share(item.shareUrl), "", cover, covers));
                if (items.size() == 50) break;
            }
            copied.add(new DouyinSearch.Result(bounded(result.query, 200), items, result.nextOffset, result.hasMore,
                    bounded(result.searchId, 256)));
            if (copied.size() == 2) break;
        }
        return Collections.unmodifiableList(copied);
    }

    Group group(List<DouyinSearch.Result> results, DouyinSearch bridge) {
        return group(results,bridge,com.douai.module.analytics.LocalAnalytics.get(context).beginOperation(null,"ai"));
    }
    Group group(List<DouyinSearch.Result> results, DouyinSearch bridge, com.douai.module.analytics.LocalAnalytics.FlowOperation analytics) {
        if (closed) return null;
        Group group = new Group(snapshot(results), bridge, analytics);
        if (group.items.isEmpty()) return null;
        groups.add(group);
        return group;
    }

    void bind(LinearLayout container, Group group) {
        container.removeAllViews();
        container.setVisibility(group == null || group.closed || group.items.isEmpty() ? View.GONE : View.VISIBLE);
        if (group == null || group.closed) return;
        flow(group.analytics,"dispatched");
        for (int index = 0; index < Math.min(2, group.items.size()); index++) container.addView(card(group.items.get(index), group.analytics));
        TextView more = text("更多视频", 14, 0xFF00776C);
        more.setTag("ai.chat.videos.more");
        more.setMinHeight(dp(44));
        more.setGravity(Gravity.CENTER_VERTICAL);
        more.setOnClickListener(view -> { if(closed || group.closed)return; if(group.analytics!=null)group.analytics.click("more_click"); group.show(); });
        container.addView(more);
    }

    final class Group implements AutoCloseable {
        private final List<DouyinSearch.Item> items = new ArrayList<>();
        private final com.douai.module.analytics.LocalAnalytics.FlowOperation analytics;
        private final List<Page> sources = new ArrayList<>();
        private final Set<String> seen = new HashSet<>();
        private final DouyinSearch bridge;
        private Dialog dialog;
        private TextView footer;
        private BaseAdapter adapter;
        private Future<?> pending;
        private final AtomicReference<DouyinSearch.Request> operation = new AtomicReference<>();
        private boolean loading;
        private volatile boolean closed;
        private volatile int generation;
        Group(List<DouyinSearch.Result> results, DouyinSearch bridge, com.douai.module.analytics.LocalAnalytics.FlowOperation frozenAnalytics) {
            this.bridge = bridge;
            analytics = frozenAnalytics;
            // Group creation can restore cached results, so it is not generation evidence.
            for (DouyinSearch.Result result : results) {
                sources.add(new Page(result));
                add(result.items);
            }
        }
        int itemCount() { return items.size(); }
        private int add(List<DouyinSearch.Item> candidates) {
            int before = items.size();
            for (DouyinSearch.Item item : candidates) {
                if (!item.awemeId.isEmpty() && seen.add(item.awemeId)) items.add(item);
            }
            return items.size() - before;
        }
        private Page next() {
            if (bridge != null) for (Page page : sources) if (page.more) return page;
            return null;
        }
        private void show() {
            if (closed || AiSearchVideoCards.this.closed) return;
            if (dialog != null && dialog.isShowing()) return;
            dialog = new Dialog(ModuleTheme.themedContext(context), ModuleTheme.dialogTheme(context));
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            LinearLayout root = new LinearLayout(dialog.getContext());
            root.setOrientation(LinearLayout.VERTICAL);
            root.setPadding(dp(16), dp(12), dp(16), dp(8));
            root.setBackgroundColor(ModuleTheme.color(context, 0xFFFFFFFF));
            TextView title = text("相关视频", 18, 0xFF1F272D);
            title.setPadding(0, 0, 0, dp(12));
            root.addView(title);
            ListView list = new ListView(dialog.getContext());
            list.setDividerHeight(dp(8));
            footer = text("", 14, 0xFF69737A);
            footer.setGravity(Gravity.CENTER);
            footer.setMinHeight(dp(48));
            footer.setOnClickListener(view -> { if(closed || AiSearchVideoCards.this.closed)return; if(analytics!=null)analytics.click("more_click"); loadMore(); });
            list.addFooterView(footer);
            adapter = new BaseAdapter() {
                @Override public int getCount() { return items.size(); }
                @Override public Object getItem(int position) { return items.get(position); }
                @Override public long getItemId(int position) { return position; }
                @Override public View getView(int position, View recycled, ViewGroup parent) { return card(items.get(position), analytics); }
            };
            list.setAdapter(adapter);
            list.setOnScrollListener(new AbsListView.OnScrollListener() {
                private boolean canRequest;
                @Override public void onScrollStateChanged(AbsListView view, int state) {
                    if (state == SCROLL_STATE_TOUCH_SCROLL) canRequest = true;
                }
                @Override public void onScroll(AbsListView view, int first, int visible, int total) {
                    if (canRequest && !loading && visible > 0 && first + visible >= total) {
                        canRequest = false;
                        loadMore();
                    }
                }
            });
            root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
            dialog.setContentView(root);
            dialog.setOnDismissListener(ignored -> { cancel(); dialog = null; footer = null; adapter = null; });
            dialog.show();
            Window window = dialog.getWindow();
            if (window != null) window.setLayout(Math.round(context.getResources().getDisplayMetrics().widthPixels * .92f),
                    Math.round(context.getResources().getDisplayMetrics().heightPixels * .72f));
            updateFooter(null);
        }
        void loadMore() {
            Page page = next();
            if (closed || AiSearchVideoCards.this.closed || loading || page == null) return;
            loading = true;
            updateFooter("正在加载…");
            int ticket = ++generation;
            int ownerEpoch = epoch;
            long offset = page.offset;
            String searchId = page.searchId;
            try {
                pending = pages.submit(() -> {
                    DouyinSearch.Result loaded = null;
                    Exception error = null;
                    DouyinSearch.Request request = null;
                    try {
                        if (Thread.currentThread().isInterrupted() || closed || ownerEpoch != epoch) throw new CancellationException();
                        request = bridge.preparePage(page.query, 5, offset, searchId);
                        operation.set(request);
                        if (Thread.currentThread().isInterrupted() || closed || ticket != generation || ownerEpoch != epoch) throw new CancellationException();
                        loaded = request.run(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
                    } catch (Exception failure) { error = failure; }
                    finally {
                        if (request != null) try { request.cancel(); } catch (RuntimeException ignored) { }
                        operation.compareAndSet(request, null);
                    }
                    DouyinSearch.Result result = loaded;
                    Exception failure = error;
                    main.post(() -> {
                        if (closed || AiSearchVideoCards.this.closed || ticket != generation || ownerEpoch != epoch) return;
                        loading = false; pending = null;
                        if (failure != null || result == null) { updateFooter("加载失败，点此重试"); return; }
                        DouyinSearch.Result safe = snapshot(Collections.singletonList(result)).get(0);
                        if (!page.query.equals(safe.query)) {
                            updateFooter("加载失败，点此重试");
                            return;
                        }
                        int added = add(safe.items);
                        page.emptyPages = added == 0 ? page.emptyPages + 1 : 0;
                        page.more = safe.hasMore && safe.nextOffset > offset && !safe.searchId.isEmpty() && page.emptyPages < 2;
                        page.offset = safe.nextOffset;
                        page.searchId = safe.searchId;
                        if (adapter != null) adapter.notifyDataSetChanged();
                        updateFooter(null);
                    });
                });
            } catch (RuntimeException busy) { loading = false; updateFooter("加载失败，点此重试"); }
        }
        private void updateFooter(String value) {
            if (footer == null) return;
            footer.setText(value != null ? value : next() == null ? "没有更多视频" : "继续下滑或点此加载更多");
            footer.setEnabled(!loading && next() != null);
        }
        private void cancel() {
            generation++; loading = false;
            if (pending != null) pending.cancel(true);
            pending = null;
            DouyinSearch.Request active = operation.getAndSet(null);
            if (active != null) try { active.cancel(); } catch (RuntimeException ignored) { }
            updateFooter(null);
        }
        @Override public void close() {
            if (closed) return;
            closed = true; cancel();
            if (dialog != null) dialog.dismiss();
            items.clear(); sources.clear(); seen.clear();
            groups.remove(this);
        }
    }

    private static final class Page {
        final String query;
        long offset;
        String searchId;
        boolean more;
        int emptyPages;
        Page(DouyinSearch.Result result) {
            query = result.query; offset = result.nextOffset; searchId = result.searchId;
            more = result.hasMore && offset > 0 && !searchId.isEmpty();
        }
    }

    private View card(DouyinSearch.Item item, com.douai.module.analytics.LocalAnalytics.FlowOperation analytics) {
        LinearLayout row = new LinearLayout(context);
        row.setTag("ai.chat.video.card");
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(6), 0, dp(6));
        row.setMinimumHeight(dp(96));
        ImageView cover = new ImageView(context);
        cover.setContentDescription(item.title + "封面");
        cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        cover.setBackgroundColor(ModuleTheme.color(context, 0xFFEEEFF3));
        row.addView(cover, new LinearLayout.LayoutParams(dp(80), dp(96)));
        thumbnail(cover, safeCovers(item));
        LinearLayout detail = new LinearLayout(context);
        detail.setOrientation(LinearLayout.VERTICAL);
        TextView title = text(item.title.isEmpty() ? "查看视频" : item.title, 14, 0xFF1F272D);
        title.setMaxLines(3);
        detail.addView(title);
        if (!item.authorName.isEmpty()) {
            TextView author = text(item.authorName, 12, 0xFF69737A);
            author.setPadding(0, dp(6), 0, 0); detail.addView(author);
        }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        params.setMarginStart(dp(10));
        row.addView(detail, params);
        if (numericId(item.awemeId) || !share(item.shareUrl).isEmpty()) {
            row.setOnClickListener(view -> { if(closed)return; open(item,analytics==null?null:analytics.click("video_click")); });
            row.setFocusable(true);
            row.setContentDescription((item.title.isEmpty() ? "视频" : item.title) + "，点击播放");
        }
        return row;
    }

    private static void flow(com.douai.module.analytics.LocalAnalytics.FlowOperation operation,String stage) { if(operation!=null)operation.stage(stage); }
    private void open(DouyinSearch.Item item, com.douai.module.analytics.LocalAnalytics.Click click) {
        if (closed) return;
        try {
            if (numericId(item.awemeId)) {
                try {
                    Context application = context.getApplicationContext();
                    ClassLoader loader = application == null ? context.getClassLoader() : application.getClassLoader();
                    Class<?> router = Class.forName("com.bytedance.router.SmartRouter", false, loader);
                    Object route = router.getMethod("buildRoute", Context.class, String.class)
                            .invoke(null, context, "aweme://aweme/detail/" + item.awemeId);
                    route.getClass().getMethod("open").invoke(route);
                    if (click != null) click.dispatched();
                    return;
                } catch (ReflectiveOperationException | LinkageError unavailable) { }
            }
            String url = share(item.shareUrl);
            if (url.isEmpty()) throw new IllegalStateException("Video link unavailable");
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
            if (click != null) click.dispatched();
        } catch (RuntimeException failure) {
            Toast.makeText(context, "暂时无法打开视频，请稍后重试", Toast.LENGTH_SHORT).show();
        }
    }

    private static List<String> safeCovers(DouyinSearch.Item item) {
        List<String> result = new ArrayList<>();
        for (String source : item.coverUrls) {
            if (source != null && source.length() <= 2048 && AutomationImages.isMediaUrl(source) && !result.contains(source)) result.add(source);
            if (result.size() == 8) break;
        }
        if (result.isEmpty() && item.coverUrl.length() <= 2048 && AutomationImages.isMediaUrl(item.coverUrl)) result.add(item.coverUrl);
        return Collections.unmodifiableList(result);
    }

    private void thumbnail(ImageView target, List<String> urls) {
        if (closed || urls.isEmpty()) {
            target.setContentDescription("封面暂不可用");
            if (!closed) try { DouAiLog.event("manual_ai_search", "video_cover", "source_unavailable", "candidates=0"); }
            catch (RuntimeException ignored) { }
            return;
        }
        CoverTarget binding = new CoverTarget(urls);
        targets.put(target, binding);
        Bitmap cached = thumbnails.get(binding.key);
        if (cached != null) { target.setImageBitmap(cached); binding.satisfied = true; }
        else if (coverFailures.getOrDefault(binding.key, 0) >= 2) showCoverRetry(target, binding);
        target.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { drainCovers(); }
            @Override public void onViewDetachedFromWindow(View view) { }
        });
        drainCovers();
    }

    private void drainCovers() {
        if (closed) return;
        long now = android.os.SystemClock.elapsedRealtime();
        long nextRetry = Long.MAX_VALUE;
        for (CoverTarget binding : new ArrayList<>(targets.values())) {
            if (binding.satisfied || reads.containsKey(binding.key) || coverFailures.getOrDefault(binding.key, 0) >= 2
                    || reads.size() >= 64) continue;
            long retryAt = coverRetryAt.getOrDefault(binding.key, 0L);
            if (now < retryAt) { nextRetry = Math.min(nextRetry, retryAt); continue; }
            reads.put(binding.key, new ImageRead(binding, epoch));
        }
        if (nextRetry != Long.MAX_VALUE) scheduleCovers(Math.max(1, nextRetry - now));
        for (ImageRead read : new ArrayList<>(reads.values())) {
            if (runningCovers.size() >= 2) break;
            if (read.submitted || read.cancelled) continue;
            read.submitted = true;
            FutureTask<Void> task = new FutureTask<>(read, null) {
                @Override protected void done() { main.post(() -> finishCover(read)); }
            };
            read.future = task;
            runningCovers.add(read);
            try { images.execute(task); }
            catch (RuntimeException busy) {
                runningCovers.remove(read);
                read.submitted = false;
                read.future = null;
                coverLog("queue_busy", read, busy.getClass().getSimpleName());
                if (!images.isShutdown()) scheduleCovers(250);
                break;
            }
        }
    }

    private void scheduleCovers(long delay) {
        if (closed || retryScheduled) return;
        retryScheduled = true;
        main.postDelayed(retryCovers, delay);
    }

    private void finishCover(ImageRead read) {
        runningCovers.remove(read);
        boolean current = reads.get(read.key) == read;
        if (current) reads.remove(read.key);
        if (read.cancelled || closed || read.ticket != epoch || !current) {
            if (read.bitmap != null) read.bitmap.recycle();
            drainCovers();
            return;
        }
        Bitmap ready = read.bitmap;
        if (ready != null) {
            coverFailures.remove(read.key); coverRetryAt.remove(read.key);
            thumbnails.put(read.key, ready);
            while (thumbnails.size() > 8) thumbnails.remove(thumbnails.keySet().iterator().next());
            for (Map.Entry<ImageView, CoverTarget> target : new ArrayList<>(targets.entrySet())) {
                ImageView view = target.getKey();
                CoverTarget binding = target.getValue();
                if (view == null || !read.key.equals(binding.key)) continue;
                view.setImageBitmap(ready);
                view.setContentDescription("视频封面");
                view.setOnClickListener(null);
                view.setClickable(false);
                binding.satisfied = true;
            }
            coverLog("loaded", read, "");
        } else {
            int attempts = coverFailures.getOrDefault(read.key, 0) + 1;
            coverFailures.put(read.key, attempts);
            coverLog("read_failed", read, read.failureType);
            if (attempts < 2) {
                coverRetryAt.put(read.key, android.os.SystemClock.elapsedRealtime() + 750);
                scheduleCovers(800);
            } else for (Map.Entry<ImageView, CoverTarget> target : new ArrayList<>(targets.entrySet())) {
                ImageView view = target.getKey();
                CoverTarget binding = target.getValue();
                if (view == null || !read.key.equals(binding.key)) continue;
                showCoverRetry(view, binding);
            }
        }
        drainCovers();
    }

    private void showCoverRetry(ImageView view, CoverTarget binding) {
        view.setContentDescription("封面加载失败，点按重试");
        view.setImageResource(android.R.drawable.ic_popup_sync);
        view.setOnClickListener(ignored -> {
            coverFailures.remove(binding.key); coverRetryAt.remove(binding.key);
            view.setImageDrawable(null);
            view.setContentDescription("封面加载中");
            view.setOnClickListener(null); view.setClickable(false);
            drainCovers();
        });
    }

    private static void coverLog(String reason, ImageRead read, String errorType) {
        try { DouAiLog.event("manual_ai_search", "video_cover", reason,
                "stage=" + read.reader.stage() + "; http_status=" + read.reader.httpStatus()
                + "; candidates=" + read.reader.attemptedSources() + "; error_type=" + errorType); }
        catch (RuntimeException ignored) { }
    }

    private static final class CoverTarget {
        final String key;
        final List<String> urls;
        boolean satisfied;
        CoverTarget(List<String> urls) { this.key = urls.get(0); this.urls = urls; }
    }

    private final class ImageRead implements Runnable {
        final String key;
        final List<String> urls;
        final int ticket;
        final VideoCoverLoader.Request reader;
        volatile Future<?> future;
        volatile boolean cancelled;
        boolean submitted;
        volatile Bitmap bitmap;
        volatile String failureType = "";
        ImageRead(CoverTarget target, int ticket) {
            this.key = target.key; this.urls = target.urls; this.ticket = ticket;
            this.reader = coverRequests.get();
        }
        @Override public void run() {
            try {
                if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException();
                Bitmap loaded = reader.load(urls);
                if (cancelled || closed || ticket != epoch || Thread.currentThread().isInterrupted()) {
                    if (loaded != null) loaded.recycle();
                } else bitmap = loaded;
            } catch (Exception | OutOfMemoryError unavailable) {
                failureType = unavailable.getClass().getSimpleName();
            }
        }
        void cancel() {
            cancelled = true;
            if (future != null) future.cancel(true);
            COVER_CLEANUP.execute(() -> { try { reader.cancel(); } catch (RuntimeException ignored) { } });
        }
    }

    void cancelPages() { for (Group group : new ArrayList<>(groups)) group.cancel(); }

    void cancelPending() {
        epoch++;
        cancelPages();
        main.removeCallbacks(retryCovers); retryScheduled = false;
        for (ImageRead read : reads.values()) read.cancel();
        reads.clear();
        coverFailures.clear(); coverRetryAt.clear();
    }
    void clear() {
        cancelPending();
        for (Group group : new ArrayList<>(groups)) group.close();
        for (ImageView target : targets.keySet()) if (target != null) target.setImageDrawable(null);
        targets.clear(); thumbnails.clear();
    }
    @Override public void close() {
        if (closed) return;
        closed = true; clear(); pages.shutdownNow(); images.shutdownNow();
    }

    private TextView text(String value, int size, int color) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size);
        view.setTextColor(ModuleTheme.color(context, color)); return view;
    }
    private int dp(int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
    private static boolean numericId(String id) { return id != null && id.matches("[0-9]{1,80}"); }
    private static String bounded(String value, int limit) { return value == null ? "" : value.substring(0, Math.min(value.length(), limit)); }
    private static String share(String value) {
        try {
            if (value == null || value.length() > 2048) return "";
            URI uri = URI.create(value);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getUserInfo() != null
                    || uri.getPort() != -1 && uri.getPort() != 443) return "";
            host = host.toLowerCase(java.util.Locale.ROOT);
            return host.equals("douyin.com") || host.endsWith(".douyin.com") || host.equals("iesdouyin.com")
                    || host.endsWith(".iesdouyin.com") ? value : "";
        } catch (RuntimeException invalid) { return ""; }
    }
    private static Thread daemon(Runnable task, String name) { Thread thread = new Thread(task, name); thread.setDaemon(true); return thread; }
    private static final ExecutorService COVER_CLEANUP = Executors.newSingleThreadExecutor(task -> daemon(task, "DouAi-chat-cover-cancel"));
}
