package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import com.douai.module.diagnostics.DouAiLog;
import java.util.Collections;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/** Account-scoped profile hydration shared by every module user picker. */
public final class NativeUserProfiles implements AutoCloseable {
    public interface Source {
        ProfileListEntry cached(String uid);
        ProfileListEntry fetch(String uid, BooleanSupplier current) throws Exception;
    }

    public static final class Update {
        public final Map<String, ProfileListEntry> profiles;
        public final boolean loading;
        public final String error;
        public final boolean accountChanged;
        public Update(Map<String, ProfileListEntry> profiles, boolean loading, String error, boolean accountChanged) {
            this.profiles = profiles;
            this.loading = loading;
            this.error = error;
            this.accountChanged = accountChanged;
        }
    }

    private static volatile NativeUserProfiles active;
    private static final long TTL = 15 * 60_000L;
    private static final long RETRY = 30_000L;
    private final Object lock = new Object();
    private final Source source;
    private final Supplier<FollowListSnapshot.State> accounts;
    private final LongSupplier clock;
    private final ExecutorService workers;
    private final Executor main;
    private final Map<String, Cached> cache = new LinkedHashMap<String, Cached>(128, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Cached> entry) { return size() > 1000; }
    };
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private final Set<Request> requests = new LinkedHashSet<>();
    private FollowListSnapshot.State session;
    private volatile boolean closed;
    private AutoCloseable accountListener;
    private int running;

    public static NativeUserProfiles install(Source source) {
        Handler main = new Handler(Looper.getMainLooper());
        NativeUserProfiles service = new NativeUserProfiles(source, () -> {
            NativeFollowLists.currentAccountUid();
            return FollowListSnapshot.current();
        }, SystemClock::elapsedRealtime, Executors.newFixedThreadPool(2), main::post);
        service.accountListener = NativeFollowLists.listen(() -> {
            if (!service.closed) service.syncSession(service.accounts.get());
        });
        return service;
    }

    NativeUserProfiles(Source source, Supplier<FollowListSnapshot.State> accounts,
                       LongSupplier clock, ExecutorService workers, Executor main) {
        this.workers = workers;
        this.source = source;
        this.accounts = accounts;
        this.clock = clock;
        this.main = main;
        NativeUserProfiles previous = active;
        active = this;
        if (previous != null) previous.close();
    }

    public static boolean isAvailable() { return active != null && !active.closed; }

    public static AutoCloseable load(List<ProfileListEntry> entries, boolean force, Consumer<Update> updated) {
        NativeUserProfiles service = active;
        if (service == null) {
            updated.accept(new Update(Collections.emptyMap(), false, "请在抖音内刷新用户资料", false));
            return () -> { };
        }
        return service.request(entries, force, updated);
    }

    AutoCloseable request(List<ProfileListEntry> entries, boolean force, Consumer<Update> updated) {
        FollowListSnapshot.State state = accounts.get();
        syncSession(state);
        Request request = new Request(state, updated, force);
        Map<String, ProfileListEntry> initial = new LinkedHashMap<>();
        synchronized (lock) {
            if (closed || active != this || !valid(state) || !same(session, state)) {
                request.finishTrace(DouAiLog.Outcome.FAILED, "profile_account_unavailable");
                main.execute(() -> { if (!request.cancelled) updated.accept(new Update(Collections.emptyMap(), false,
                        "尚未读取登录账号，请在抖音内重试", false)); });
                return request;
            }
            for (ProfileListEntry entry : entries) {
                if (entry.conversation || FollowListSnapshot.normalizeUid(entry.uid).isEmpty()) continue;
                Cached cached = cache.get(entry.uid);
                ProfileListEntry baseline = cached == null ? entry : cached.profile.withFallback(entry);
                request.latest.merge(entry.uid, baseline, (known, incoming) -> incoming.withFallback(known));
                if (cached != null) initial.put(entry.uid, baseline);
                if (!force && cached != null && complete(cached.profile) && clock.getAsLong() < cached.until) continue;
                Job job = jobs.get(entry.uid);
                if (job == null) {
                    job = new Job(entry.uid, state, baseline,
                            force || cached != null && complete(cached.profile) && clock.getAsLong() >= cached.until,
                            cached == null ? 0 : cached.until);
                    jobs.put(entry.uid, job);
                } else {
                    job.baseline = job.baseline.withFallback(baseline);
                    job.force |= force;
                }
                job.consumers.add(request);
                request.pending.add(entry.uid);
            }
            requests.add(request);
        }
        request.trace.stage("cache_then_fetch", "requested_count=" + request.latest.size() + ";force=" + force);
        deliver(request, initial, true);
        pump();
        return request;
    }

    @Override public void close() {
        List<Job> cancel = new ArrayList<>();
        List<Request> stopped;
        synchronized (lock) {
            if (closed) return;
            closed = true;
            if (active == this) active = null;
            stopped = new ArrayList<>(requests);
            for (Request request : requests) request.cancelled = true;
            for (Job job : jobs.values()) if (job.task != null) cancel.add(job);
            requests.clear(); jobs.clear(); cache.clear(); running = 0;
        }
        for (Request request : stopped) request.finishTrace(DouAiLog.Outcome.CANCELLED, "profile_service_closed");
        for (Job job : cancel) cancel(job);
        if (accountListener != null) {
            try { accountListener.close(); }
            catch (Exception ignored) { }
        }
        workers.shutdownNow();
    }

    private static boolean valid(FollowListSnapshot.State state) {
        return state != null && state.active && !state.accountUid.isEmpty();
    }
    private static boolean same(FollowListSnapshot.State first, FollowListSnapshot.State second) {
        return first != null && second != null && first.accountEpoch == second.accountEpoch
                && first.accountUid.equals(second.accountUid);
    }
    private static boolean complete(ProfileListEntry profile) {
        return profile != null && !profile.name.isEmpty() && !profile.avatarUrl.isEmpty() && !profile.douyinId.isEmpty();
    }
    private static ProfileListEntry checked(String uid, ProfileListEntry profile) {
        return profile != null && uid.equals(profile.uid) && !profile.conversation ? profile : null;
    }

    private void syncSession(FollowListSnapshot.State state) {
        List<Request> invalidated = new ArrayList<>();
        List<Job> cancel = new ArrayList<>();
        synchronized (lock) {
            if (session != null && state != null && state.accountEpoch < session.accountEpoch) return;
            if (same(session, state)) return;
            invalidated.addAll(requests);
            for (Job job : jobs.values()) if (job.task != null) cancel.add(job);
            jobs.clear(); requests.clear(); cache.clear(); running = 0;
            session = state;
        }
        for (Job job : cancel) cancel(job);
        for (Request request : invalidated) main.execute(() -> {
            if (!request.cancelled && !closed && active == this) {
                request.updated.accept(new Update(Collections.emptyMap(), false, "登录账号已变化，请重新打开列表", true));
                request.close();
            }
        });
    }

    private boolean current(FollowListSnapshot.State expected) {
        FollowListSnapshot.State now = accounts.get();
        syncSession(now);
        synchronized (lock) {
            return !closed && active == this && valid(now) && same(now, expected) && same(session, expected);
        }
    }
    private boolean current(Job job) {
        if (!current(job.session)) return false;
        synchronized (lock) { return jobs.get(job.uid) == job && !job.consumers.isEmpty(); }
    }

    private void deliver(Request request, Map<String, ProfileListEntry> patch, boolean initial) {
        Map<String, ProfileListEntry> immutable = Collections.unmodifiableMap(new LinkedHashMap<>(patch));
        main.execute(() -> {
            if (request.cancelled || !current(request.session)) return;
            Update update;
            boolean terminal;
            synchronized (lock) {
                if (request.cancelled || closed) return;
                Map<String, ProfileListEntry> delivered = immutable;
                if (initial && !immutable.isEmpty()) {
                    delivered = new LinkedHashMap<>(immutable);
                    for (String uid : request.completed) delivered.remove(uid);
                    delivered = Collections.unmodifiableMap(delivered);
                }
                terminal = request.pending.isEmpty();
                update = new Update(delivered, !terminal,
                        request.completed.isEmpty() && !terminal ? "" : new Summary(request).status(), false);
            }
            if (terminal) request.finishTrace(null, null);
            request.updated.accept(update);
        });
    }

    private void pump() {
        List<Job> start = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            for (Job job : jobs.values()) {
                if (running >= 2) break;
                if (job.task != null || job.consumers.isEmpty()) continue;
                job.task = new FutureTask<>(() -> {
                    job.runner = Thread.currentThread();
                    try { run(job); return null; }
                    finally { job.runner = null; }
                });
                running++;
                start.add(job);
            }
        }
        for (Job job : start) {
            try { workers.execute(job.task); }
            catch (RejectedExecutionException unavailable) { finish(job, null, Failure.STOPPED); }
        }
    }

    private void run(Job job) {
        if (!current(job)) return;
        ProfileListEntry result;
        synchronized (lock) { result = job.baseline; }
        Failure failure = Failure.NONE;
        try {
            ProfileListEntry local = checked(job.uid, source.cached(job.uid));
            if (!current(job)) return;
            if (local != null) result = local.withFallback(result);
            boolean force;
            synchronized (lock) { force = job.force; }
            if (force || !complete(result)) {
                if (!force && clock.getAsLong() < job.retryAt) failure = Failure.RETRY_WAIT;
                else {
                    job.networkAttempted = true;
                    ProfileListEntry fetched = checked(job.uid, source.fetch(job.uid, () -> current(job)));
                    if (!current(job)) return;
                    if (fetched == null) failure = Failure.UNRETURNED;
                    else result = fetched.withFallback(result);
                }
            }
        } catch (Exception | LinkageError unavailable) {
            failure = unavailable instanceof java.util.concurrent.TimeoutException ? Failure.TIMED_OUT : Failure.FAILED;
        }
        finish(job, result, failure);
    }

    private void finish(Job job, ProfileListEntry result, Failure failure) {
        if (!current(job)) return;
        List<Request> listeners;
        synchronized (lock) {
            if (closed || jobs.get(job.uid) != job) return;
            jobs.remove(job.uid);
            running--;
            if (result != null) {
                long now = clock.getAsLong();
                long until = !job.networkAttempted && failure != Failure.NONE && job.retryAt > now
                        ? job.retryAt : now + (failure == Failure.NONE && complete(result) ? TTL : RETRY);
                cache.put(job.uid, new Cached(result, until));
            }
            listeners = new ArrayList<>(job.consumers);
            for (Request request : listeners) {
                request.pending.remove(job.uid);
                request.completed.add(job.uid);
                if (result != null) request.latest.put(job.uid, result.withFallback(request.latest.get(job.uid)));
                if (failure == Failure.NONE) request.failures.remove(job.uid);
                else request.failures.put(job.uid, failure);
            }
        }
        Map<String, ProfileListEntry> patch = result == null ? Collections.emptyMap() : Collections.singletonMap(job.uid, result);
        for (Request request : listeners) deliver(request, patch, false);
        pump();
    }

    private void cancel(Job job) { job.task.cancel(job.runner != Thread.currentThread()); }

    private enum Failure { NONE, RETRY_WAIT, UNRETURNED, TIMED_OUT, FAILED, STOPPED }

    private static final class Summary {
        final int total, pending, complete, missingName, missingAvatar, missingId, failed, timedOut, unreturned;
        final boolean stopped;
        Summary(Request request) {
            total = request.latest.size(); pending = request.pending.size();
            int full = 0, names = 0, avatars = 0, ids = 0, failures = 0, timeouts = 0, absent = 0;
            boolean unavailable = false;
            for (ProfileListEntry entry : request.latest.values()) {
                if (complete(entry)) full++;
                if (entry.name.isEmpty()) names++;
                if (entry.avatarUrl.isEmpty()) avatars++;
                if (entry.douyinId.isEmpty()) ids++;
            }
            for (Failure failure : request.failures.values()) {
                if (failure == Failure.TIMED_OUT) timeouts++;
                else if (failure == Failure.UNRETURNED) absent++;
                else if (failure == Failure.FAILED || failure == Failure.STOPPED) failures++;
                unavailable |= failure == Failure.STOPPED;
            }
            complete = full; missingName = names; missingAvatar = avatars; missingId = ids;
            failed = failures; timedOut = timeouts; unreturned = absent; stopped = unavailable;
        }
        String status() {
            if (complete == total && failed == 0 && timedOut == 0 && unreturned == 0) return "";
            StringBuilder value = new StringBuilder("已完整 ").append(complete).append('/').append(total);
            add(value, "待补", total - complete);
            add(value, "缺昵称", missingName); add(value, "缺头像", missingAvatar); add(value, "缺抖音号", missingId);
            add(value, "超时", timedOut); add(value, "读取失败", failed); add(value, "未返回", unreturned);
            if (stopped) value.append(" · 加载已停止，请重新打开列表");
            else value.append(" · 点击刷新重试");
            return value.toString();
        }
        private static void add(StringBuilder value, String label, int count) {
            if (count > 0) value.append(" · ").append(label).append(' ').append(count).append('人');
        }
        DouAiLog.Outcome outcome() {
            if (failed > 0 || unreturned > 0) return DouAiLog.Outcome.FAILED;
            if (timedOut > 0) return DouAiLog.Outcome.TIMED_OUT;
            return complete == total ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED;
        }
        String evidence(boolean force) {
            return "requested_count=" + total + ";complete_count=" + complete + ";incomplete_count=" + (total - complete)
                    + ";pending_count=" + pending + ";missing_name_count=" + missingName + ";missing_avatar_count=" + missingAvatar
                    + ";missing_douyin_id_count=" + missingId + ";fetch_failed_count=" + failed + ";timeout_count=" + timedOut
                    + ";unreturned_count=" + unreturned + ";force=" + force;
        }
    }

    private final class Request implements AutoCloseable {
        final FollowListSnapshot.State session;
        final Consumer<Update> updated;
        final Set<String> pending = new LinkedHashSet<>();
        final Set<String> completed = new LinkedHashSet<>();
        final Map<String, ProfileListEntry> latest = new LinkedHashMap<>();
        final Map<String, Failure> failures = new LinkedHashMap<>();
        final DouAiLog.Operation trace;
        final boolean force;
        boolean traceFinished;
        volatile boolean cancelled;
        Request(FollowListSnapshot.State session, Consumer<Update> updated, boolean force) {
            this.session = session; this.updated = updated; this.force = force;
            trace = DouAiLog.begin("user_profiles", force ? "picker_refresh" : "picker_load");
        }
        void finishTrace(DouAiLog.Outcome outcome, String reason) {
            Summary summary;
            synchronized (lock) {
                if (traceFinished) return;
                traceFinished = true;
                summary = new Summary(this);
            }
            DouAiLog.Outcome result = outcome == null ? summary.outcome() : outcome;
            trace.finish(result, reason == null ? (result == DouAiLog.Outcome.SUCCESS ? "profiles_complete" : "profiles_pending_fields_or_read_failures") : reason,
                    summary.evidence(force));
        }
        @Override public void close() {
            List<Job> cancel = new ArrayList<>();
            synchronized (lock) {
                if (cancelled) return;
                cancelled = true;
                requests.remove(this);
                for (String uid : pending) {
                    Job job = jobs.get(uid);
                    if (job == null) continue;
                    job.consumers.remove(this);
                    if (job.consumers.isEmpty()) {
                        jobs.remove(uid);
                        if (job.task != null) { running--; cancel.add(job); }
                    }
                }
                pending.clear();
            }
            finishTrace(DouAiLog.Outcome.CANCELLED, "profile_request_closed");
            for (Job job : cancel) cancel(job);
            pump();
        }
    }
    private static final class Cached {
        final ProfileListEntry profile;
        final long until;
        Cached(ProfileListEntry profile, long until) { this.profile = profile; this.until = until; }
    }
    private final class Job {
        final String uid;
        final FollowListSnapshot.State session;
        final Set<Request> consumers = new LinkedHashSet<>();
        final long retryAt;
        ProfileListEntry baseline;
        boolean force;
        FutureTask<Void> task;
        volatile Thread runner;
        boolean networkAttempted;
        Job(String uid, FollowListSnapshot.State session, ProfileListEntry baseline, boolean force, long retryAt) {
            this.uid = uid; this.session = session; this.baseline = baseline; this.force = force; this.retryAt = retryAt;
        }
    }
}
