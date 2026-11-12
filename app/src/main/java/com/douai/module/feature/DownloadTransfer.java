package com.douai.module.feature;
import com.douai.module.diagnostics.DouAiLog;

import java.io.Closeable;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.LongSupplier;

/** Cancellation and elapsed-time budget belonging to one user download. */
final class DownloadTransfer {
    interface Connections { HttpURLConnection open(URI uri) throws Exception; }
    static final long TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);
    private final long timeoutNanos;
    private final LongSupplier clock;
    private volatile long deadline;
    private volatile boolean cancelled;
    private volatile boolean timedOut;
    private volatile boolean completed;
    private HttpURLConnection connection;
    private Runnable stopAction;
    private final java.util.Set<Closeable> resources = new java.util.LinkedHashSet<>();
    volatile String name = "";
    volatile String phase = "等待开始";
    volatile long bytes;
    volatile long totalBytes = -1;
    volatile int completedFiles;
    volatile int totalFiles = 1;
    Runnable changed = () -> { };
    private long lastProgress;
    volatile DouAiLog.Operation operation;
    volatile int savedEvidenceCount;
    volatile com.douai.module.analytics.LocalAnalytics.FlowOperation analytics;
    volatile int analyticsTask;
    volatile boolean transportTimedOut;
    private String loggedPhase;

    void evidence(String stage, String evidence) {
        DouAiLog.Operation current = operation;
        if (current != null) current.stage(stage, evidence);
    }
    void saved(String destination, String identifier, long count) {
        savedEvidenceCount++;
        if (analytics != null && !"download_manager_status_successful".equals(destination)) analytics.stage("output_closed", analyticsTask);
        evidence("output_committed", "destination=" + destination + "; output_ref=" + DouAiLog.reference(identifier)
                + "; bytes=" + count + "; streams_closed=true; read_back_verified=false");
    }

    DownloadTransfer() { this(TIMEOUT_MILLIS, System::nanoTime); }
    DownloadTransfer(long timeoutMillis, LongSupplier clock) {
        timeoutNanos = TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        this.clock = clock;
    }
    synchronized void start() throws Exception {
        check();
        if (deadline == 0) deadline = clock.getAsLong() + timeoutNanos;
    }
    void check() throws Exception {
        if (completed) return;
        if (timedOut) throw new TimeoutException("下载超过 30 分钟");
        if (cancelled || Thread.currentThread().isInterrupted()) throw new CancellationException("下载已取消");
        if (deadline != 0 && clock.getAsLong() - deadline >= 0) {
            requestCancel(true);
            throw new TimeoutException("下载超过 30 分钟");
        }
    }
    void attach(HttpURLConnection opened) throws Exception {
        synchronized (this) { connection = opened; }
        try { check(); }
        catch (Exception failure) { opened.disconnect(); throw failure; }
    }
    synchronized void detach(HttpURLConnection opened) { if (connection == opened) connection = null; }
    synchronized boolean requestCancel(boolean timeout) {
        if (completed) return false;
        timedOut |= timeout;
        cancelled = true;
        return true;
    }
    boolean cancel() {
        if (!requestCancel(false)) return false;
        disconnect();
        return true;
    }
    void disconnect() {
        HttpURLConnection active;
        Runnable stop;
        java.util.List<Closeable> opened;
        synchronized (this) { active = connection; stop = stopAction; opened = new java.util.ArrayList<>(resources); }
        if (active != null) try { active.disconnect(); } catch (RuntimeException ignored) { }
        if (stop != null) try { stop.run(); } catch (RuntimeException ignored) { }
        for (int index = opened.size() - 1; index >= 0; index--) {
            try { opened.get(index).close(); } catch (Exception ignored) { }
        }
    }
    void onStop(Runnable action) {
        boolean stopped;
        synchronized (this) { stopAction = action; stopped = cancelled; }
        if (stopped && action != null) action.run();
    }
    synchronized void complete() throws Exception {
        check();
        completed = true;
        stopAction = null;
    }
    boolean isCancelled() { return cancelled; }
    boolean isTimedOut() { return timedOut; }
    synchronized boolean hasResources() { return connection != null || stopAction != null || !resources.isEmpty(); }
    void phase(String value) {
        phase = value;
        if (!java.util.Objects.equals(value, loggedPhase)) {
            loggedPhase = value;
            evidence("transfer_phase", "phase=" + value + "; completedFiles=" + completedFiles + "; totalFiles=" + totalFiles);
        }
        changed.run();
    }
    void file(String value, int count) throws Exception {
        check();
        name = value; totalFiles = count; bytes = 0; totalBytes = -1;
        phase("正在下载");
    }
    void size(long value) { totalBytes = value; changed.run(); }
    void advance(long count) throws Exception {
        check();
        bytes += count;
        long now = clock.getAsLong();
        if (lastProgress == 0 || now - lastProgress >= TimeUnit.MILLISECONDS.toNanos(250)) {
            lastProgress = now; changed.run();
        }
    }
    void fileDone() throws Exception {
        check(); completedFiles++;
        evidence("file_closed", "completedFiles=" + completedFiles + "; totalFiles=" + totalFiles
                + "; bytes=" + bytes + "; expectedBytes=" + totalBytes);
        changed.run();
    }
    int timeout(int maximum) throws Exception {
        check();
        if (deadline == 0) return maximum;
        return (int) Math.min(maximum, Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - clock.getAsLong())));
    }
    Streams streams() { return new Streams(); }
    final class Streams implements AutoCloseable {
        private final java.util.List<Closeable> opened = new java.util.ArrayList<>();
        <T extends Closeable> T add(T resource) throws Exception {
            if (resource == null) throw new IllegalStateException("文件流不可用");
            synchronized (DownloadTransfer.this) { resources.add(resource); opened.add(resource); }
            check();
            return resource;
        }
        @Override public void close() throws Exception {
            Exception first = null;
            for (int index = opened.size() - 1; index >= 0; index--) {
                Closeable resource = opened.get(index);
                try { resource.close(); }
                catch (Exception failure) { if (first == null) first = failure; else first.addSuppressed(failure); }
                finally { synchronized (DownloadTransfer.this) { resources.remove(resource); } }
            }
            if (first != null) throw first;
        }
    }
}
