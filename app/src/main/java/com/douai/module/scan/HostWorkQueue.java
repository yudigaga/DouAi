package com.douai.module.scan;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Serializes host feature installation and configuration publication without blocking the UI thread. */
public final class HostWorkQueue implements AutoCloseable {
    private volatile Thread workerThread;
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(0, 1, 15L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(32), action -> {
                Thread thread = new Thread(action, "DouAi-host-work");
                thread.setDaemon(true);
                workerThread = thread;
                return thread;
            });

    /** Allows local configuration tails to reuse this lane without enqueueing behind themselves. */
    public boolean isWorkerThread() { return Thread.currentThread() == workerThread; }

    public CompletableFuture<Void> submit(Runnable action) {
        CompletableFuture<Void> completed = new CompletableFuture<>();
        try {
            worker.execute(() -> {
                try { action.run(); completed.complete(null); }
                catch (Throwable failure) { completed.completeExceptionally(failure); }
            });
        } catch (RuntimeException unavailable) { completed.completeExceptionally(unavailable); }
        return completed;
    }

    @Override public void close() { worker.shutdownNow(); }
}
