package com.douai.module.feature;

import android.os.Handler;
import com.douai.module.config.ConfigurationApplyAction;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;

/** One captured configuration tail; attempts own only their next queued main callback. */
abstract class RetainedConfigurationAction implements ConfigurationApplyAction {
    private final Handler main;
    private final BooleanSupplier open;
    private final Owner owner;
    private volatile boolean complete;
    private volatile PermanentFailure permanent;
    private Attempt active;

    RetainedConfigurationAction(Handler main, BooleanSupplier open, Owner owner) {
        this.main = main;
        this.open = open;
        this.owner = owner;
    }

    @Override public synchronized CompletionStage<Void> apply(BooleanSupplier current) {
        if (active != null && !active.isDone()) active.cancel(false);
        Attempt attempt = new Attempt(current);
        active = attempt;
        owner.begin(attempt);
        if (permanent == null) permanent = owner.failure();
        if (permanent != null) attempt.completeExceptionally(permanent);
        else if (complete) {
            try { attempt.check(); attempt.complete(null); }
            catch (Throwable failure) { attempt.fail(failure); }
        } else if (inlineOnMainThread() && android.os.Looper.myLooper() == main.getLooper()) {
            try { attempt.check(); advance(attempt); }
            catch (Throwable failure) { attempt.fail(failure); }
        } else attempt.later(0L);
        return attempt;
    }

    abstract void advance(Attempt attempt) throws Exception;
    boolean inlineOnMainThread() { return false; }
    void release() { }

    static final class Owner {
        private CompletableFuture<Void> active;
        private PermanentFailure permanent;
        synchronized PermanentFailure failure() { return permanent; }
        synchronized void fail(PermanentFailure failure) { if (permanent == null) permanent = failure; }
        synchronized void begin(CompletableFuture<Void> next) {
            if (active != null && !active.isDone()) active.cancel(false);
            active = next;
        }
        synchronized void cancel() {
            if (active != null) active.cancel(false);
            active = null;
        }
    }

    final void once(Attempt attempt, Runnable mutation) {
        attempt.check();
        try { mutation.run(); }
        catch (Throwable failure) {
            permanent = new PermanentFailure("Configuration invalidation has an unknown effect", failure);
            owner.fail(permanent);
            throw permanent;
        }
    }

    final class Attempt extends CompletableFuture<Void> {
        private final BooleanSupplier current;
        private volatile Runnable queued;
        Attempt(BooleanSupplier current) { this.current = current; }

        void check() {
            if (isDone() || !open.getAsBoolean() || !current.getAsBoolean()) throw new CancellationException();
            PermanentFailure stopped = owner.failure();
            if (stopped != null) throw stopped;
        }

        void later(long delay) {
            try {
                check();
                Runnable callback = () -> {
                    queued = null;
                    try { check(); advance(this); }
                    catch (Throwable failure) { fail(failure); }
                };
                queued = callback;
                boolean posted = delay == 0L ? main.post(callback) : main.postDelayed(callback, delay);
                if (!posted) { queued = null; fail(new IllegalStateException("Main configuration callback rejected")); }
                else if (isDone()) main.removeCallbacks(callback);
            } catch (Throwable failure) { fail(failure); }
        }

        void success() {
            check();
            release();
            complete = true;
            complete(null);
        }

        void fail(Throwable failure) {
            Runnable callback = queued;
            queued = null;
            if (callback != null) main.removeCallbacks(callback);
            if (failure instanceof CancellationException) cancel(false);
            else completeExceptionally(failure);
        }

        @Override public boolean cancel(boolean interrupt) {
            boolean cancelled = super.cancel(interrupt);
            Runnable callback = queued;
            queued = null;
            if (callback != null) main.removeCallbacks(callback);
            return cancelled;
        }
    }
}
