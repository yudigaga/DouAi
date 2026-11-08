package com.douai.module.feature;

import com.douai.module.scan.HostAdaptation;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Reflection entry point for the reviewed host search capability. */
public final class HostDouyinSearch implements DouyinSearch {
    private static final ExecutorService SEARCH_WORK = new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(8), task -> daemon(task, "DouAi-host-search"), new ThreadPoolExecutor.AbortPolicy());
    private static final ExecutorService DISPOSE_WORK = Executors.newSingleThreadExecutor(task -> daemon(task, "DouAi-search-cancel"));
    private final Constructor<?> requestConstructor;
    private final Method setCount, setSearchId, assemble, apiFactory, search, subscribe, dispose, normalize, summaryText;
    private final Field extras, token, status, error, id, title, author, nickname, shareInfo, shareInfoUrl, shareUrl, summary;
    private final Field cursor, hasMore, logPb, imprId;
    private final Class<?> observerType, disposableType, responseType, awemeType, userType, shareType, summaryType;

    private HostDouyinSearch(ClassLoader loader) throws ReflectiveOperationException {
        Class<?> baseRequest = load(loader, "com.ss.android.ugc.aweme.search.op.standard.AbstractSearchRequest");
        Class<?> requestType = load(loader, "com.ss.android.ugc.aweme.search.op.standard.SearchVideoListRequest");
        if (requestType.getSuperclass() != baseRequest) throw changed();
        requestConstructor = requestType.getConstructor(String.class);
        setCount = method(baseRequest, "LJIILJJIL", void.class, false, int.class);
        setSearchId = method(baseRequest, "LJJII", void.class, false, String.class);
        extras = field(baseRequest, "LJJII", Map.class, false);
        Class<?> tokenBase = load(loader, "X.0fOQ");
        Class<?> tokenType = load(loader, "X.0fPi");
        if (!tokenBase.isAssignableFrom(tokenType)) throw changed();
        token = field(tokenType, "LIZJ", tokenType, true);
        assemble = method(load(loader, "X.0exj"), "LIZIZ", Map.class, true,
                tokenBase, baseRequest, load(loader, "X.0exk"));
        Class<?> apiType = load(loader, "com.ss.android.ugc.aweme.discover.api.SearchApi$RealApi");
        Class<?> observableType = load(loader, "io.reactivex.Observable");
        observerType = load(loader, "io.reactivex.Observer");
        disposableType = load(loader, "io.reactivex.disposables.Disposable");
        if (!apiType.isInterface() || !observerType.isInterface() || !disposableType.isInterface()) throw changed();
        apiFactory = method(load(loader, "X.0Uvf"), "LIZ", apiType, true);
        search = method(apiType, "LJJIIZI", observableType, false, Map.class);
        subscribe = method(observableType, "subscribe", void.class, false, observerType);
        dispose = method(disposableType, "dispose", void.class, false);
        method(observerType, "onSubscribe", void.class, false, disposableType);
        method(observerType, "onNext", void.class, false, Object.class);
        method(observerType, "onError", void.class, false, Throwable.class);
        method(observerType, "onComplete", void.class, false);
        responseType = load(loader, "X.0lv8");
        normalize = method(responseType, "LJLZ", List.class, false);
        status = field(responseType, "status_code", int.class, false);
        error = field(responseType, "error_code", int.class, false);
        cursor = field(responseType, "cursor", int.class, false);
        hasMore = field(responseType, "hasMore", boolean.class, false);
        Class<?> logPbType = load(loader, "com.ss.android.ugc.aweme.feed.model.LogPbBean");
        logPb = field(responseType, "logPb", logPbType, false);
        imprId = field(logPbType, "imprId", String.class, false);
        awemeType = load(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
        userType = load(loader, "com.ss.android.ugc.aweme.profile.model.User");
        shareType = load(loader, "com.ss.android.ugc.aweme.base.share.ShareInfo");
        summaryType = load(loader, "com.ss.android.ugc.aweme.feed.model.search.VideoAbstract");
        id = field(awemeType, "aid", String.class, false);
        title = field(awemeType, "desc", String.class, false);
        author = field(awemeType, "author", userType, false);
        nickname = field(userType, "nickname", String.class, false);
        shareInfo = field(awemeType, "shareInfo", shareType, false);
        shareInfoUrl = field(shareType, "shareUrl", String.class, false);
        shareUrl = field(awemeType, "shareUrl", String.class, false);
        summary = field(awemeType, "videoAbstract", summaryType, false);
        summaryText = method(summaryType, "LIZ", String.class, false);
    }

    public static DouyinSearch create(ClassLoader loader, HostAdaptation.Profile profile)
            throws ReflectiveOperationException {
        return profile == HostAdaptation.PROFILE_410 ? new HostDouyinSearch(loader) : null;
    }

    @Override public Request prepare(String query, int limit) {
        return prepareRequest(query, limit, 0, "", false);
    }

    @Override public Request preparePage(String query, int limit, long offset, String searchId) {
        return prepareRequest(query, limit, offset, searchId, true);
    }

    private Request prepareRequest(String query, int limit, long offset, String searchId, boolean presentation) {
        String value = query == null ? "" : query.trim();
        if (value.isEmpty() || value.length() > 200) throw new IllegalArgumentException("Search query must contain 1 to 200 characters");
        String continuation = searchId == null ? "" : searchId;
        if (offset < 0 || offset > Integer.MAX_VALUE || !safeSearchId(continuation)
                || offset > 0 && continuation.isEmpty()) throw new IllegalArgumentException("Search continuation is invalid");
        int count = Math.max(1, Math.min(5, limit));
        return new SearchRequest(value, count, presentation ? 50 : count, (int) offset, continuation);
    }

    private final class SearchRequest implements Request {
        private final String query;
        private final int limit;
        private final int projectionLimit;
        private final int offset;
        private final String searchId;
        private final AtomicBoolean started = new AtomicBoolean(), cancelled = new AtomicBoolean();
        private final AtomicBoolean finished = new AtomicBoolean(), disposalQueued = new AtomicBoolean();
        private final AtomicReference<Object> subscription = new AtomicReference<>();
        private final CountDownLatch completed = new CountDownLatch(1);
        private volatile Result result;
        private volatile IOException failure;

        SearchRequest(String query, int limit, int projectionLimit, int offset, String searchId) {
            this.query = query; this.limit = limit; this.projectionLimit = projectionLimit;
            this.offset = offset; this.searchId = searchId;
        }

        @Override public Result run(long deadlineNanos) throws Exception {
            if (cancelled.get()) throw new CancellationException("Search cancelled");
            if (deadlineNanos - System.nanoTime() <= 0) throw new TimeoutException("Search timed out");
            if (!started.compareAndSet(false, true)) throw new IllegalStateException("Search request already used");
            try { SEARCH_WORK.execute(this::start); }
            catch (RejectedExecutionException saturated) {
                if (finished.compareAndSet(false, true)) completed.countDown();
                throw failed();
            }
            try {
                long remaining = deadlineNanos - System.nanoTime();
                if (remaining <= 0 || !completed.await(remaining, TimeUnit.NANOSECONDS)) {
                    cancel(); throw new TimeoutException("Search timed out");
                }
            } catch (InterruptedException interrupted) {
                cancel(); Thread.currentThread().interrupt(); throw interrupted;
            }
            if (cancelled.get()) throw new CancellationException("Search cancelled");
            if (deadlineNanos - System.nanoTime() <= 0) {
                cancel(); throw new TimeoutException("Host search timed out");
            }
            if (failure != null) throw failure;
            if (result == null) throw failed();
            return result;
        }

        private void start() {
            try {
                if (cancelled.get()) return;
                Object request = requestConstructor.newInstance(query);
                setCount.invoke(request, limit);
                setSearchId.invoke(request, searchId);
                Map<String, String> page = new LinkedHashMap<>();
                // The verified builder overlays extras after the delegated typed values.
                page.put("source", "video_search"); page.put("offset", Integer.toString(offset));
                page.put("need_filter_settings", "1"); page.put("search_id", searchId);
                extras.set(request, page);
                if (cancelled.get()) return;
                Object parameters = assemble.invoke(null, token.get(null), request, null);
                if (!(parameters instanceof Map)) { fail(); return; }
                Map<?, ?> fields = (Map<?, ?>) parameters;
                if (!Integer.toString(offset).equals(fields.get("offset")) || !searchId.equals(fields.get("search_id"))) {
                    fail(); return;
                }
                if (cancelled.get()) return;
                Object api = apiFactory.invoke(null);
                if (cancelled.get()) return;
                Object observable = search.invoke(api, parameters);
                if (cancelled.get()) return;
                Object observer = Proxy.newProxyInstance(observerType.getClassLoader(), new Class<?>[]{observerType},
                        (proxy, callback, arguments) -> {
                            switch (callback.getName()) {
                                case "onSubscribe":
                                    Object handle = arguments[0];
                                    if (!disposableType.isInstance(handle)) { fail(); break; }
                                    subscription.compareAndSet(null, handle);
                                    if (cancelled.get() || finished.get()) release();
                                    break;
                                case "onNext":
                                    if (!cancelled.get() && !finished.get()) {
                                        try { finish(project(arguments[0], query, projectionLimit, offset)); }
                                        catch (ReflectiveOperationException | RuntimeException | LinkageError invalid) { fail(); }
                                    }
                                    break;
                                case "onError": fail(); break;
                                case "onComplete": if (!finished.get()) fail(); break;
                                case "toString": return "DouAi search observer";
                                case "hashCode": return System.identityHashCode(proxy);
                                case "equals": return proxy == arguments[0];
                                default: break;
                            }
                            return null;
                        });
                if (!cancelled.get()) subscribe.invoke(observable, observer);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { fail(); }
        }

        private void finish(Result value) {
            if (finished.compareAndSet(false, true)) {
                result = value; completed.countDown(); release();
            }
        }

        private void fail() {
            if (finished.compareAndSet(false, true)) {
                failure = failed(); completed.countDown(); release();
            }
        }

        @Override public void cancel() {
            cancelled.set(true);
            if (finished.compareAndSet(false, true)) completed.countDown();
            release();
        }

        private void release() {
            Object handle = subscription.get();
            if (handle == null || !disposalQueued.compareAndSet(false, true)) return;
            DISPOSE_WORK.execute(() -> {
                try { dispose.invoke(handle); }
                catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) { }
            });
        }
    }

    private Result project(Object response, String query, int limit, int offset) throws ReflectiveOperationException {
        if (!responseType.isInstance(response) || status.getInt(response) != 0 || error.getInt(response) != 0) throw changed();
        Object normalized = normalize.invoke(response);
        if (!(normalized instanceof List)) throw changed();
        List<?> nativeItems = (List<?>) normalized;
        List<Item> items = new ArrayList<>();
        List<Object> nativeAwemes = new ArrayList<>();
        Set<String> selectedIds = new HashSet<>();
        for (int index = 0; index < Math.min(nativeItems.size(), 50) && items.size() < limit; index++) {
            Object aweme = nativeItems.get(index);
            if (!awemeType.isInstance(aweme)) continue;
            String videoId = text(id.get(aweme), 80);
            if (videoId.isEmpty() || !selectedIds.add(videoId)) continue;
            Object user = author.get(aweme), share = shareInfo.get(aweme), abstractText = summary.get(aweme);
            String url = shareType.isInstance(share) ? shareLink(shareInfoUrl.get(share)) : "";
            if (url.isEmpty()) url = shareLink(shareUrl.get(aweme));
            List<String> coverCandidates = covers(aweme);
            items.add(new Item(videoId, text(title.get(aweme), 600),
                    userType.isInstance(user) ? text(nickname.get(user), 100) : "", url,
                    summaryType.isInstance(abstractText) ? text(summaryText.invoke(abstractText), 2000) : "",
                    coverCandidates.isEmpty() ? "" : coverCandidates.get(0), coverCandidates));
            nativeAwemes.add(aweme);
        }
        int next = cursor.getInt(response);
        Object log = logPb.get(response);
        String nextSearchId = log == null ? "" : (String) imprId.get(log);
        if (!safeSearchId(nextSearchId)) nextSearchId = "";
        // Keep an oversized presentation page intact; never use its cursor to skip truncated works.
        boolean more = nativeItems.size() <= 50 && hasMore.getBoolean(response) && next > offset && !nextSearchId.isEmpty();
        return new Result(query, items, nativeAwemes, Math.max(0, next), more, nextSearchId);
    }

    static List<String> covers(Object aweme) {
        Object video = DownloadActionHook.jsonValue(aweme, "video");
        List<List<String>> variants = new ArrayList<>();
        for (String field : new String[]{"cover", "thumb_cover", "origin_cover", "medium_cover", "optimized_cover"}) {
            variants.add(coverUrls(DownloadActionHook.jsonValue(video, field)));
        }
        List<String> candidates = interleaveCoverVariants(variants);
        if (candidates.isEmpty()) {
            Object gallery = DownloadActionHook.jsonValue(aweme, "images");
            if (gallery instanceof Iterable<?>) {
                for (Object image : (Iterable<?>) gallery) {
                    candidates = coverUrls(image);
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            variants.clear();
            for (String field : new String[]{"dynamic_cover", "animated_cover"}) {
                variants.add(coverUrls(DownloadActionHook.jsonValue(video, field)));
            }
            candidates = interleaveCoverVariants(variants);
        }
        return Collections.unmodifiableList(new ArrayList<>(candidates));
    }

    private static List<String> interleaveCoverVariants(List<List<String>> variants) {
        List<String> result = new ArrayList<>();
        // Reserve a primary URL from each static variant before filling remaining CDN alternatives.
        for (int index = 0; index < 2; index++) {
            for (List<String> variant : variants) {
                if (index < variant.size() && !result.contains(variant.get(index))) result.add(variant.get(index));
                if (result.size() == 8) return result;
            }
        }
        return result;
    }

    private static List<String> coverUrls(Object source) {
        List<String> result = new ArrayList<>();
        for (String field : new String[]{"url_list", "download_url_list"}) {
            Object raw = DownloadActionHook.jsonValue(source, field);
            if (!(raw instanceof Iterable<?>)) continue;
            int inspected = 0;
            for (Object value : (Iterable<?>) raw) {
                if (++inspected > 16) break;
                if (!(value instanceof String)) continue;
                String url = (String) value;
                if (url.length() <= 2048 && AutomationImages.isMediaUrl(url) && !result.contains(url)) result.add(url);
                if (result.size() == 2) return result;
            }
        }
        return result;
    }

    private static boolean safeSearchId(String value) {
        if (value == null || value.length() > 256) return false;
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return false;
        return true;
    }

    private static String text(Object value, int limit) {
        if (!(value instanceof String)) return "";
        String text = (String) value;
        return text.length() <= limit ? text : text.substring(0, limit);
    }

    private static String shareLink(Object value) {
        String link = text(value, 2049).trim();
        if (link.isEmpty() || link.length() > 2048) return "";
        try {
            URI uri = new URI(link);
            String host = uri.getHost();
            if (!"https".equalsIgnoreCase(uri.getScheme()) || host == null || uri.getRawUserInfo() != null
                    || (uri.getPort() != -1 && uri.getPort() != 443)) return "";
            host = host.toLowerCase(Locale.ROOT);
            if (!(host.equals("douyin.com") || host.endsWith(".douyin.com")
                    || host.equals("iesdouyin.com") || host.endsWith(".iesdouyin.com"))) return "";
            String path = uri.getRawPath();
            return "https://" + host + (path == null || path.isEmpty() ? "/" : path);
        } catch (URISyntaxException invalid) { return ""; }
    }

    private static Class<?> load(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }

    private static Method method(Class<?> owner, String name, Class<?> result, boolean isStatic, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = owner.getMethod(name, arguments);
        if (method.getReturnType() != result || Modifier.isStatic(method.getModifiers()) != isStatic
                || !Modifier.isPublic(method.getModifiers())) throw changed();
        return method;
    }

    private static Field field(Class<?> owner, String name, Class<?> type, boolean isStatic)
            throws ReflectiveOperationException {
        Field field = owner.getField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers()) != isStatic
                || !Modifier.isPublic(field.getModifiers())) throw changed();
        return field;
    }

    private static ReflectiveOperationException changed() {
        return new ReflectiveOperationException("Host search contract changed");
    }

    private static IOException failed() { return new IOException("Host search unavailable"); }

    private static Thread daemon(Runnable task, String name) {
        Thread thread = new Thread(task, name); thread.setDaemon(true); return thread;
    }
}
