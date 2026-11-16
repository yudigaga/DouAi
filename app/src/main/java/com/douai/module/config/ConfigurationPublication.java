package com.douai.module.config;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

/** Module-local desired/applied ledger. It owns no thread, business timeout, or persistent state. */
public final class ConfigurationPublication<T> implements AutoCloseable {
    @FunctionalInterface public interface Cancellable { void cancel(); }
    @FunctionalInterface public interface Scheduler {
        Cancellable schedule(Runnable runnable, long delayMillis);
    }

    public enum Summary { SUCCESS, PARTIAL, PENDING, FAILED }
    public enum Status {
        APPLIED, APPLYING, WAITING_NOT_INSTALLED, WAITING_DEPENDENCY, RETRY_SCHEDULED, FAILED, CLOSED
    }
    public enum Reason {
        NONE, NOT_INSTALLED, DEPENDENCY, DEPENDENCY_INVALIDATED, APPLYING, FACTORY_FAILURE, APPLY_FAILURE,
        ACK_TIMEOUT, SCHEDULER_FAILURE, PERMANENT_FAILURE, CLOSED
    }

    public static final class Binding<T> {
        public final String id;
        public final Object receiver;
        public final boolean affected;
        public final List<String> dependencyIds;
        public final Function<T, ConfigurationApplyAction> prepare;

        public Binding(String id, Object receiver, boolean affected, Collection<String> dependencyIds,
                Function<T, ConfigurationApplyAction> prepare) {
            this.id = Objects.requireNonNull(id, "id");
            if (id.isEmpty()) throw new IllegalArgumentException("Empty consumer id");
            this.receiver = receiver;
            this.affected = affected;
            this.dependencyIds = Collections.unmodifiableList(new ArrayList<>(
                    Objects.requireNonNull(dependencyIds, "dependencyIds")));
            for (String dependency : this.dependencyIds) Objects.requireNonNull(dependency, "dependency");
            this.prepare = Objects.requireNonNull(prepare, "prepare");
        }
    }

    /** Contains identifiers and bounded state only: no settings, payload, receiver, or error text. */
    public static final class NodeSnapshot {
        public final String id;
        public final boolean installed;
        public final long desiredRevision;
        public final long appliedRevision;
        public final Status status;
        public final Reason reason;
        public final int attempts;
        public final String failureClass;

        private NodeSnapshot(String id, boolean installed, long desiredRevision, long appliedRevision,
                Status status, Reason reason, int attempts, String failureClass) {
            this.id = id;
            this.installed = installed;
            this.desiredRevision = desiredRevision;
            this.appliedRevision = appliedRevision;
            this.status = status;
            this.reason = reason;
            this.attempts = attempts;
            this.failureClass = failureClass;
        }
    }

    public static final class Snapshot {
        public final long revision;
        public final boolean closed;
        public final Summary summary;
        public final Map<String, NodeSnapshot> nodes;

        private Snapshot(long revision, boolean closed, Summary summary, Map<String, NodeSnapshot> nodes) {
            this.revision = revision;
            this.closed = closed;
            this.summary = summary;
            this.nodes = Collections.unmodifiableMap(nodes);
        }
    }

    private static final int MAX_ATTEMPTS = 3;
    private static final long ACK_DEADLINE_MILLIS = 5000;
    private final Object lock = new Object();
    private final Scheduler scheduler;
    private final Consumer<Snapshot> observer;
    private final Map<String, Entry> entries = new LinkedHashMap<>();
    private Map<String, ?> values;
    private long revision;
    private boolean closed;

    public ConfigurationPublication(Scheduler scheduler) { this(scheduler, null); }

    public ConfigurationPublication(Scheduler scheduler, Consumer<Snapshot> observer) {
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.observer = observer;
    }

    /** The payload and values must already be immutable, complete configuration snapshots. */
    public void publish(Map<String, ?> immutableValues, T payload, List<Binding<T>> bindings) {
        Map<String, ?> nextValues = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(immutableValues, "immutableValues")));
        Map<String, Binding<T>> nextBindings = new LinkedHashMap<>();
        for (Binding<T> binding : Objects.requireNonNull(bindings, "bindings")) {
            if (nextBindings.put(binding.id, binding) != null) {
                throw new IllegalArgumentException("Duplicate consumer id");
            }
        }
        List<Runnable> work = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            boolean changed = values == null || !values.equals(nextValues);
            if (changed) {
                values = nextValues;
                revision++;
            }
            Map<String, Entry> nextEntries = new LinkedHashMap<>();
            for (Binding<T> binding : nextBindings.values()) {
                Entry previous = entries.get(binding.id);
                boolean replace = previous == null || previous.receiver != binding.receiver
                        || (changed && binding.affected);
                Entry entry;
                if (replace) {
                    if (previous != null) invalidateLocked(previous, work);
                    long applied = previous != null && previous.receiver == binding.receiver
                            ? previous.appliedRevision : 0;
                    entry = new Entry(binding, payload, revision, applied);
                } else {
                    entry = previous;
                    entry.dependencies = binding.dependencyIds;
                    if (changed && entry.status == Status.APPLIED) {
                        entry.desiredRevision = revision;
                        entry.appliedRevision = revision;
                    }
                }
                nextEntries.put(binding.id, entry);
            }
            for (Entry entry : entries.values()) {
                if (!nextEntries.containsKey(entry.id)) invalidateLocked(entry, work);
            }
            entries.clear();
            entries.putAll(nextEntries);
            invalidateBlockedAttemptsLocked(work);
            retryIncompleteLocked(work);
            dispatchReadyLocked(work);
        }
        run(work);
        notifyObserver();
    }

    /** Accelerates only safe incomplete work; it never resets a receiver/relevant-revision budget. */
    public void retry() {
        List<Runnable> work = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            retryIncompleteLocked(work);
            dispatchReadyLocked(work);
        }
        run(work);
        notifyObserver();
    }

    @Override public void close() {
        List<Runnable> work = new ArrayList<>();
        synchronized (lock) {
            if (closed) return;
            closed = true;
            for (Entry entry : entries.values()) {
                invalidateLocked(entry, work);
                if (entry.status != Status.APPLIED) {
                    entry.status = Status.CLOSED;
                    entry.reason = Reason.CLOSED;
                }
            }
        }
        run(work);
        notifyObserver();
    }

    public Snapshot snapshot() {
        synchronized (lock) {
            Map<String, NodeSnapshot> nodes = new LinkedHashMap<>();
            int applied = 0;
            boolean failed = false;
            for (Entry entry : entries.values()) {
                nodes.put(entry.id, new NodeSnapshot(entry.id, entry.receiver != null,
                        entry.desiredRevision, entry.appliedRevision, entry.status,
                        entry.reason, entry.attempts, entry.failureClass));
                if (entry.status == Status.APPLIED) applied++;
                if (entry.status == Status.FAILED || entry.status == Status.CLOSED) failed = true;
            }
            Summary summary = applied == entries.size() ? Summary.SUCCESS
                    : applied > 0 ? Summary.PARTIAL : failed ? Summary.FAILED : Summary.PENDING;
            return new Snapshot(revision, closed, summary, nodes);
        }
    }

    private void retryIncompleteLocked(List<Runnable> work) {
        for (Entry entry : entries.values()) {
            if (entry.attempt == null && !entry.terminal && entry.attempts < MAX_ATTEMPTS) {
                cancelTimerLocked(entry.retry, work);
                entry.retry = null;
            }
        }
    }

    private void invalidateBlockedAttemptsLocked(List<Runnable> work) {
        for (Entry entry : entries.values()) {
            if (entry.attempt == null || dependenciesAppliedLocked(entry)) continue;
            invalidateLocked(entry, work);
            entry.failureClass = null;
            entry.terminal = entry.attempts >= MAX_ATTEMPTS;
            entry.status = entry.terminal ? Status.FAILED : Status.WAITING_DEPENDENCY;
            entry.reason = entry.terminal ? Reason.DEPENDENCY_INVALIDATED : Reason.DEPENDENCY;
        }
    }

    private boolean dependenciesAppliedLocked(Entry entry) {
        for (String dependencyId : entry.dependencies) {
            Entry dependency = entries.get(dependencyId);
            if (dependency == null || dependency.status != Status.APPLIED) return false;
        }
        return true;
    }

    private void dispatchReadyLocked(List<Runnable> work) {
        for (Entry entry : entries.values()) {
            if (entry.status == Status.APPLIED || entry.attempt != null || entry.retry != null
                    || entry.terminal || entry.attempts >= MAX_ATTEMPTS) continue;
            if (entry.receiver == null) {
                entry.status = Status.WAITING_NOT_INSTALLED;
                entry.reason = Reason.NOT_INSTALLED;
                continue;
            }
            if (!dependenciesAppliedLocked(entry)) {
                entry.status = Status.WAITING_DEPENDENCY;
                entry.reason = Reason.DEPENDENCY;
                continue;
            }
            Attempt attempt = new Attempt();
            entry.attempt = attempt;
            entry.attempts++;
            entry.status = Status.APPLYING;
            entry.reason = Reason.APPLYING;
            work.add(() -> start(entry, attempt));
        }
    }

    private void start(Entry entry, Attempt attempt) {
        ConfigurationApplyAction action;
        synchronized (lock) {
            if (!currentLocked(entry, attempt) || attempt.started) return;
            action = entry.action;
            if (action == null) {
                if (entry.preparing) return;
                entry.preparing = true;
            } else {
                attempt.started = true;
            }
        }
        if (action == null) {
            ConfigurationApplyAction prepared = null;
            Throwable preparationFailure = null;
            try {
                prepared = Objects.requireNonNull(entry.prepare.apply(entry.payload), "apply action");
            } catch (Throwable failure) {
                preparationFailure = failure;
            }
            Attempt pending;
            synchronized (lock) {
                entry.preparing = false;
                if (!liveLocked(entry)) return;
                if (preparationFailure == null) entry.action = prepared;
                pending = entry.attempt;
                if (preparationFailure != null && pending != null) pending.started = true;
            }
            if (pending != null) {
                if (preparationFailure != null) complete(entry, pending, preparationFailure, Reason.FACTORY_FAILURE);
                else start(entry, pending);
            }
            return;
        }
        Timer deadline = new Timer();
        synchronized (lock) {
            if (!currentLocked(entry, attempt)) return;
            attempt.deadline = deadline;
        }
        schedule(deadline, () -> complete(entry, attempt, new TimeoutException(), Reason.ACK_TIMEOUT),
                ACK_DEADLINE_MILLIS, failure -> complete(entry, attempt, failure, Reason.SCHEDULER_FAILURE));
        if (!current(entry, attempt)) return;
        CompletionStage<Void> completion;
        try {
            completion = Objects.requireNonNull(action.apply(() -> current(entry, attempt)), "completion");
        } catch (Throwable failure) {
            complete(entry, attempt, failure, Reason.APPLY_FAILURE);
            return;
        }
        boolean accepted;
        synchronized (lock) {
            accepted = currentLocked(entry, attempt);
            if (accepted) attempt.completion = completion;
        }
        if (!accepted) {
            cancelCompletion(completion);
            return;
        }
        try {
            completion.whenComplete((ignored, failure) -> complete(entry, attempt, failure, Reason.APPLY_FAILURE));
        } catch (Throwable failure) {
            complete(entry, attempt, failure, Reason.APPLY_FAILURE);
        }
    }

    private void complete(Entry entry, Attempt attempt, Throwable failure, Reason reason) {
        List<Runnable> work = new ArrayList<>();
        synchronized (lock) {
            if (!currentLocked(entry, attempt)) return;
            entry.attempt = null;
            cancelTimerLocked(attempt.deadline, work);
            if (failure == null) {
                entry.appliedRevision = entry.desiredRevision;
                entry.status = Status.APPLIED;
                entry.reason = Reason.NONE;
                entry.failureClass = null;
            } else {
                Throwable actual = unwrap(failure);
                entry.failureClass = actual.getClass().getName();
                entry.reason = actual instanceof ConfigurationApplyAction.PermanentFailure
                        ? Reason.PERMANENT_FAILURE : reason;
                entry.terminal = actual instanceof ConfigurationApplyAction.PermanentFailure
                        || entry.attempts >= MAX_ATTEMPTS;
                entry.status = Status.FAILED;
                if (attempt.completion != null) work.add(() -> cancelCompletion(attempt.completion));
                if (!entry.terminal) {
                    Timer retry = new Timer();
                    entry.retry = retry;
                    entry.status = Status.RETRY_SCHEDULED;
                    long delay = entry.attempts == 1 ? 100 : 300;
                    work.add(() -> schedule(retry, () -> retryTimer(entry, retry), delay,
                            schedulingFailure -> retrySchedulingFailed(entry, retry, schedulingFailure)));
                }
            }
            dispatchReadyLocked(work);
        }
        run(work);
        notifyObserver();
    }

    private void retryTimer(Entry entry, Timer timer) {
        List<Runnable> work = new ArrayList<>();
        synchronized (lock) {
            if (!timer.active || !liveLocked(entry) || entry.retry != timer) return;
            timer.active = false;
            entry.retry = null;
            dispatchReadyLocked(work);
        }
        run(work);
        notifyObserver();
    }

    private void retrySchedulingFailed(Entry entry, Timer timer, Throwable failure) {
        synchronized (lock) {
            if (!timer.active || !liveLocked(entry) || entry.retry != timer) return;
            timer.active = false;
            entry.retry = null;
            entry.status = Status.FAILED;
            entry.reason = Reason.SCHEDULER_FAILURE;
            entry.failureClass = unwrap(failure).getClass().getName();
        }
        notifyObserver();
    }

    private void schedule(Timer timer, Runnable runnable, long delay, Consumer<Throwable> failed) {
        synchronized (lock) { if (!timer.active) return; }
        Cancellable handle;
        try {
            handle = scheduler.schedule(runnable, delay);
            if (handle == null) throw new RejectedExecutionException();
        } catch (Throwable failure) {
            failed.accept(failure);
            return;
        }
        boolean keep;
        synchronized (lock) {
            keep = timer.active;
            if (keep) timer.handle = handle;
        }
        if (!keep) cancelHandle(handle);
    }

    private boolean current(Entry entry, Attempt attempt) {
        synchronized (lock) { return currentLocked(entry, attempt); }
    }

    private boolean currentLocked(Entry entry, Attempt attempt) {
        return liveLocked(entry) && entry.attempt == attempt;
    }

    private boolean liveLocked(Entry entry) { return !closed && entries.get(entry.id) == entry; }

    private void invalidateLocked(Entry entry, List<Runnable> work) {
        cancelTimerLocked(entry.retry, work);
        entry.retry = null;
        Attempt attempt = entry.attempt;
        entry.attempt = null;
        if (attempt != null) {
            cancelTimerLocked(attempt.deadline, work);
            if (attempt.completion != null) work.add(() -> cancelCompletion(attempt.completion));
        }
    }

    private void cancelTimerLocked(Timer timer, List<Runnable> work) {
        if (timer == null) return;
        timer.active = false;
        if (timer.handle != null) work.add(() -> cancelHandle(timer.handle));
    }

    private static void cancelHandle(Cancellable handle) {
        try { handle.cancel(); } catch (Throwable ignored) { }
    }

    private static void cancelCompletion(CompletionStage<Void> completion) {
        try { completion.toCompletableFuture().cancel(false); } catch (Throwable ignored) { }
    }

    private static Throwable unwrap(Throwable failure) {
        while ((failure instanceof CompletionException || failure instanceof ExecutionException)
                && failure.getCause() != null && failure.getCause() != failure) failure = failure.getCause();
        return failure;
    }

    private static void run(List<Runnable> work) { for (Runnable task : work) task.run(); }

    private void notifyObserver() {
        if (observer == null) return;
        try { observer.accept(snapshot()); } catch (Throwable ignored) { }
    }

    private final class Entry {
        final String id;
        final Object receiver;
        final T payload;
        final Function<T, ConfigurationApplyAction> prepare;
        List<String> dependencies;
        long desiredRevision;
        long appliedRevision;
        Status status = Status.WAITING_DEPENDENCY;
        Reason reason = Reason.DEPENDENCY;
        int attempts;
        boolean terminal;
        String failureClass;
        ConfigurationApplyAction action;
        boolean preparing;
        Attempt attempt;
        Timer retry;

        Entry(Binding<T> binding, T payload, long desiredRevision, long appliedRevision) {
            id = binding.id;
            receiver = binding.receiver;
            prepare = binding.prepare;
            dependencies = binding.dependencyIds;
            this.payload = payload;
            this.desiredRevision = desiredRevision;
            this.appliedRevision = appliedRevision;
        }
    }

    private static final class Attempt {
        boolean started;
        Timer deadline;
        CompletionStage<Void> completion;
    }

    private static final class Timer {
        boolean active = true;
        Cancellable handle;
    }
}
