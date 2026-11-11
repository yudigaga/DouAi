package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;
import com.douai.module.diagnostics.DouAiLog;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Runs the host's global user search without borrowing its credentials. */
public final class NativeUserSearch implements AutoCloseable {
    public interface Source {
        Page search(String query, long cursor, String searchId, BooleanSupplier current) throws Exception;
    }
    public static final class Page {
        public final List<ProfileListEntry> entries;
        public final long cursor;
        public final boolean hasMore;
        public final String searchId;
        public Page(List<ProfileListEntry> entries, long cursor, boolean hasMore, String searchId) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.cursor = cursor;
            this.hasMore = hasMore;
            this.searchId = searchId == null ? "" : searchId;
        }
    }
    public static final class Update {
        public final Page page;
        public final String error;
        public final boolean accountChanged;
        public Update(Page page, String error, boolean accountChanged) {
            this.page = page; this.error = error; this.accountChanged = accountChanged;
        }
    }
    private static volatile NativeUserSearch active;
    private final Object lock = new Object();
    private final Source source;
    private final Supplier<FollowListSnapshot.State> accounts;
    private final ExecutorService workers;
    private final Executor main;
    private final Set<Request> requests = new LinkedHashSet<>();
    private final Map<Continuation, Boolean> continuations =
            new LinkedHashMap<Continuation, Boolean>(32, .75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<Continuation, Boolean> entry) {
                    return size() > 256;
                }
            };
    private FollowListSnapshot.State session;
    private AutoCloseable accountListener;
    private volatile boolean closed;
    public static NativeUserSearch install(Source source) {
        Handler main = new Handler(Looper.getMainLooper());
        NativeUserSearch service = new NativeUserSearch(source, () -> {
            NativeFollowLists.currentAccountUid();
            return FollowListSnapshot.current();
        }, new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(8)), main::post);
        service.accountListener = NativeFollowLists.listen(() -> {
            if (!service.closed) service.syncSession(service.accounts.get());
        });
        return service;
    }
    NativeUserSearch(Source source, Supplier<FollowListSnapshot.State> accounts, ExecutorService workers, Executor main) {
        this.workers = workers;
        this.source = source; this.accounts = accounts; this.main = main;
        NativeUserSearch previous = active;
        active = this;
        if (previous != null) previous.close();
    }
    public static boolean isAvailable() { return active != null && !active.closed; }
    public static AutoCloseable search(String query, long cursor, String searchId, Consumer<Update> updated) {
        NativeUserSearch service = active;
        if (service == null) {
            updated.accept(new Update(null, "请在抖音内搜索用户", false));
            return () -> { };
        }
        return service.request(query, cursor, searchId, updated);
    }
    AutoCloseable request(String query, long cursor, String searchId, Consumer<Update> updated) {
        String keyword = query == null ? "" : query.trim();
        String id = searchId == null ? "" : searchId;
        FollowListSnapshot.State state = accounts.get();
        syncSession(state);
        Request request = new Request(keyword, cursor, id, state, updated);
        String error = "";
        boolean accountChanged;
        if (keyword.isEmpty() || keyword.length() > 200) error = "请输入 1～200 字的昵称或抖音号";
        else if (cursor < 0 || cursor > Integer.MAX_VALUE || id.length() > 1024) error = "搜索页信息无效，请重新搜索";
        synchronized (lock) {
            accountChanged = valid(state) && !same(session, state);
            if (closed || active != this) error = "搜索服务已关闭，请重新打开列表";
            else if (!valid(state) || !same(session, state)) error = "尚未读取登录账号，请在抖音内重试";
            else if (error.isEmpty() && cursor > 0
                    && !Boolean.TRUE.equals(continuations.get(new Continuation(keyword, cursor, id)))) {
                error = "搜索结果已过期，请重新搜索";
            }
            requests.add(request);
        }
        if (!error.isEmpty()) {
            String message = accountChanged ? "登录账号已变化，请重新打开列表" : error;
            main.execute(() -> publish(request, new Update(null, message, accountChanged)));
            return request;
        }
        request.task = new FutureTask<>(() -> {
            request.runner = Thread.currentThread();
            try { run(request); return null; }
            finally { request.runner = null; }
        });
        try { workers.execute(request.task); }
        catch (RejectedExecutionException busy) { post(request, new Update(null, "搜索任务较多，请稍后重试", false)); }
        return request;
    }
    @Override public void close() {
        List<Request> pending;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (active == this) active = null;
            pending = new ArrayList<>(requests);
            requests.clear(); continuations.clear();
        }
        for (Request request : pending) request.close();
        if (accountListener != null) try { accountListener.close(); } catch (Exception ignored) { }
        workers.shutdownNow();
    }

    private static boolean valid(FollowListSnapshot.State state) {
        return state != null && state.active && !state.accountUid.isEmpty();
    }
    private static boolean same(FollowListSnapshot.State first, FollowListSnapshot.State second) {
        return first != null && second != null && first.accountEpoch == second.accountEpoch
                && first.accountUid.equals(second.accountUid);
    }
    private void syncSession(FollowListSnapshot.State now) {
        List<Request> stale;
        synchronized (lock) {
            if (closed || session != null && now != null && now.accountEpoch < session.accountEpoch || same(session, now)) return;
            session = now;
            continuations.clear();
            stale = new ArrayList<>(requests);
            requests.clear();
        }
        for (Request request : stale) {
            request.cancelTask();
            main.execute(() -> publish(request, new Update(null, "登录账号已变化，请重新打开列表", true)));
        }
    }
    private boolean current(Request request) {
        if (request.cancelled) return false;
        FollowListSnapshot.State now = accounts.get();
        syncSession(now);
        synchronized (lock) {
            return !closed && active == this && !request.cancelled && valid(now)
                    && same(now, request.session) && same(session, request.session);
        }
    }
    private void run(Request request) {
        DouAiLog.Operation trace = DouAiLog.begin("user_search", request.cursor == 0 ? "user_query" : "load_more");
        trace.wrap(() -> run(request, trace)).run();
    }
    private void run(Request request, DouAiLog.Operation trace) {
        try {
            if (!current(request)) {
                trace.finish(DouAiLog.Outcome.CANCELLED, "search_cancelled", "native_request_started=false");
                return;
            }
            trace.stage("native_query", "page=" + (request.cursor == 0 ? "first" : "more"));
            Page page = source.search(request.query, request.cursor, request.searchId, () -> current(request));
            if (!current(request)) {
                trace.finish(DouAiLog.Outcome.CANCELLED, "search_cancelled", "native_result_accepted=false");
                return;
            }
            if (page == null || page.cursor < 0 || page.cursor > Integer.MAX_VALUE
                    || page.searchId.length() > 1024
                    || page.hasMore && (page.cursor <= request.cursor || page.searchId.isEmpty())) {
                throw new IllegalStateException("Invalid native search continuation");
            }
            Map<String, ProfileListEntry> unique = new LinkedHashMap<>();
            for (ProfileListEntry entry : page.entries) {
                if (entry == null || entry.conversation || FollowListSnapshot.normalizeUid(entry.uid).isEmpty()) {
                    throw new IllegalStateException("Invalid native user identity");
                }
                unique.putIfAbsent(entry.uid, entry);
            }
            trace.finish(DouAiLog.Outcome.SUCCESS, "native_page_loaded", "result_count=" + unique.size()
                    + ";has_more=" + page.hasMore + ";ui_delivery=pending");
            post(request, new Update(new Page(new ArrayList<>(unique.values()), page.cursor, page.hasMore, page.searchId), "", false));
        } catch (Exception | LinkageError failure) {
            boolean cancelled = !current(request);
            trace.finish(cancelled ? DouAiLog.Outcome.CANCELLED
                    : failure instanceof java.util.concurrent.TimeoutException ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED,
                    cancelled ? "search_cancelled" : "search_failed", "error_class=" + failure.getClass().getSimpleName());
            post(request, new Update(null, failure instanceof java.util.concurrent.TimeoutException
                    ? "用户搜索超时，请重试" : "用户搜索失败，请重试", false));
        }
    }
    private void post(Request request, Update update) {
        main.execute(() -> {
            if (current(request)) publish(request, update);
        });
    }
    private void publish(Request request, Update update) {
        synchronized (lock) {
            if (closed || active != this || request.cancelled || request.delivered) return;
            if (!update.accountChanged && !same(session, request.session)) return;
            request.delivered = true;
            requests.remove(request);
            if (update.page != null && update.page.hasMore) {
                continuations.put(new Continuation(request.query, update.page.cursor, update.page.searchId), true);
            }
        }
        try { request.updated.accept(update); }
        finally { request.close(); }
    }
    private final class Request implements AutoCloseable {
        final String query, searchId;
        final long cursor;
        final FollowListSnapshot.State session;
        final Consumer<Update> updated;
        volatile boolean cancelled;
        boolean delivered;
        FutureTask<Void> task;
        volatile Thread runner;
        Request(String query, long cursor, String searchId, FollowListSnapshot.State session, Consumer<Update> updated) {
            this.query = query; this.cursor = cursor; this.searchId = searchId; this.session = session; this.updated = updated;
        }
        void cancelTask() {
            FutureTask<Void> pending = task;
            if (pending != null) {
                pending.cancel(runner != Thread.currentThread());
                if (workers instanceof ThreadPoolExecutor) ((ThreadPoolExecutor) workers).remove(pending);
            }
        }
        @Override public void close() {
            boolean cancel;
            synchronized (lock) {
                if (cancelled) return;
                cancelled = true;
                requests.remove(this);
                cancel = !delivered;
            }
            if (cancel) cancelTask();
        }
    }
    private static final class Continuation {
        final String query, id;
        final long cursor;
        Continuation(String query, long cursor, String id) { this.query = query; this.cursor = cursor; this.id = id; }
        @Override public int hashCode() { return Objects.hash(query, cursor, id); }
        @Override public boolean equals(Object other) {
            if (!(other instanceof Continuation)) return false;
            Continuation value = (Continuation) other;
            return cursor == value.cursor && query.equals(value.query) && id.equals(value.id);
        }
    }
}
