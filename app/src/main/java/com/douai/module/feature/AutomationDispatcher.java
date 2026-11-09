package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Entry;
import com.douai.module.config.AutomationHistoryStore.Reason;
import com.douai.module.config.AutomationHistoryStore.Stage;
import com.douai.module.config.AutomationDiagnostics;
import com.douai.module.config.AutomationMessageSettings;
import com.douai.module.config.LocalOptions;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Optional previews and cancellation shared by automatic replies and comments. */
public final class AutomationDispatcher implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    public interface Action { void send(Consumer<String> result) throws Exception; }

    private static final int GENERATING = -1;
    private static final int WAITING = 0;
    private static final int DETAILS = 1;
    private static final int QUEUED = 2;
    private static final int SENDING = 3;
    private static final int UNCONFIRMED = 4;
    private static final int FINISHED = 5;
    private static final int MAX_PENDING = 16;
    private final Application application;
    private final AutomationHistoryStore history;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private final Map<String, Pending> pending = new LinkedHashMap<>();
    private final Map<String, Boolean> seen = new LinkedHashMap<>();
    private final List<Entry> failureNotices = new ArrayList<>();
    private volatile LocalOptions options = new LocalOptions(Collections.emptyMap());
    private volatile boolean closed;
    private volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private Dialog failureDetails;
    private final CommentRingOverlay commentRing;
    private final CommentRingOverlay replyRing;
    private Pending submittedReply;
    private long replyFailureAttentionUntil;
    private String replyPresentation = "";
    private final Runnable replyRefresh = this::renderReplyRing;
    private String commentHead = "";
    private int commentQueueCount = -1;
    private int commentRetries = 2;
    private String commentQueuePhase = "waiting";
    private long commentFailureAttentionUntil;
    private Runnable cancelLatestComment = () -> { };

    public AutomationDispatcher(Context context, Consumer<String> log) {
        this(context, log, Executors.newSingleThreadExecutor());
    }

    AutomationDispatcher(Context context, Consumer<String> log, ExecutorService worker) {
        if (context == null || worker == null) throw new IllegalArgumentException("Context and worker are required");
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalArgumentException("Application context is required");
        application = (Application) app;
        history = new AutomationHistoryStore(application);
        this.log = log == null ? ignored -> { } : log;
        this.worker = worker;
        commentRing = new CommentRingOverlay(application, this::openCommentDetails, this::cancelRingComment);
        replyRing = new CommentRingOverlay(application, this::openReplyDetails, this::cancelRingReply, true, this::safeLog);
        commentRing.avoid(replyRing);
        replyRing.avoid(commentRing);
        application.registerActivityLifecycleCallbacks(this);
    }

    public void setOptions(LocalOptions next) {
        if (next == null) throw new IllegalArgumentException("Options are required");
        options = next;
        onMain(() -> {
            if (closed) return;
            for (Pending item : new ArrayList<>(pending.values())) {
                if (!enabled(item.kind)) cancel(item);
                else if (!tipEnabled(item.kind) && item.state.get() == WAITING) dispatch(item);
            }
            failureNotices.removeIf(entry -> !failureEnabled(entry.kind));
            renderNotices();
        });
    }

    public boolean isForeground(Activity activity) { return !closed && activity != null && resumed.get() == activity; }

    /** Current presentation owner state also fences cancellation during a configuration handoff. */
    boolean isEnabled(String kind) { return !closed && enabled(kind); }

    public void trackComment(Entry entry, int count, int retries, String phase,
                             BooleanSupplier stillValid, Runnable cancelGeneration,
                             Runnable cancelLatest) {
        onMain(() -> {
            if (closed) return;
            cancelLatestComment = cancelLatest == null ? () -> { } : cancelLatest;
            String next = entry == null ? "" : entry.id;
            if (!commentHead.equals(next)) {
                Pending previous = pending.get(key(AutomationHistoryStore.COMMENT, commentHead));
                if (previous != null && previous.early && previous.state.get() < SENDING) {
                    previous.state.set(FINISHED);
                    pending.remove(key(previous.kind, previous.entry.id));
                    removeNotice(previous);
                }
            }
            commentHead = next;
            commentQueueCount = count;
            commentRetries = Math.max(0, retries);
            commentQueuePhase = "running".equals(phase) ? "generating" : phase;
            if (entry != null) {
                Pending item = earlyPending(entry, stillValid, cancelGeneration);
                if (!"running".equals(phase) || !item.ready) item.phase = "running".equals(phase) ? "generating" : phase;
            }
            renderNotices();
        });
    }

    /** Preparation is visible before AI/TTS; the same pending item later owns its normal preview. */
    public void trackReply(Entry entry, String phase, BooleanSupplier stillValid, Runnable cancelGeneration) {
        if (entry == null || !isReply(entry.kind)
                || stillValid == null || cancelGeneration == null) throw new IllegalArgumentException("Reply preparation is required");
        onMain(() -> {
            if (closed || !enabled(entry.kind)) return;
            String identity = key(entry.kind, entry.id);
            Pending existing = pending.get(identity);
            if (existing == null && (seen.containsKey(identity) || pending.size() >= MAX_PENDING + 1)) return;
            Pending item = earlyPending(entry, stillValid, cancelGeneration);
            if (item.ready || item.state.get() >= SENDING) return;
            item.entry = entry;
            item.phase = "media".equals(phase) || "speech".equals(phase) || "generating".equals(phase) ? phase : "waiting";
            if (!valid(item)) { cancel(item); return; }
            refreshDetails(item);
            renderReplyRing();
        });
    }

    private Pending earlyPending(Entry entry, BooleanSupplier stillValid, Runnable cancelGeneration) {
        String identity = key(entry.kind, entry.id);
        Pending item = pending.get(identity);
        if (item != null) return item;
        item = new Pending(entry, stillValid, result -> { }, status -> {
            if ("cancelled".equals(status) && cancelGeneration != null) cancelGeneration.run();
        });
        item.early = true;
        item.cancelFuture = cancelGeneration;
        item.ready = false;
        item.state.set(GENERATING);
        pending.put(identity, item);
        remember(entry.kind, entry.id);
        // A reply can still return before it produces content; persist at ready/failure/cancel.
        if (!isReply(entry.kind)) record(entry);
        return item;
    }

    private int admittedPendingCount() {
        int count = 0;
        for (Pending item : pending.values()) {
            if (!isReply(item.kind) || !item.early || item.ready) count++;
        }
        return count;
    }

    /** A failed/invalid generator must not leave a preparation ring after releasing its task. */
    public void discardReplyPreparation(String id) {
        discardReplyPreparation(AutomationHistoryStore.REPLY, id);
    }

    public void discardReplyPreparation(String kind, String id) {
        if (!isReply(kind)) throw new IllegalArgumentException("Reply preparation kind is required");
        onMain(() -> {
            Pending item = pending.get(key(kind, id));
            if (item == null || !item.early || item.ready || item.state.get() >= SENDING) return;
            item.state.set(FINISHED);
            pending.remove(key(item.kind, item.entry.id));
            removeNotice(item);
        });
    }

    public void commentStage(String id, String phase) {
        onMain(() -> {
            Pending item = pending.get(key(AutomationHistoryStore.COMMENT, id));
            if (item == null) return;
            item.phase = phase;
            refreshDetails(item);
            renderCommentRing();
        });
    }

    public void prepareComment(Entry entry, boolean alreadyApproved, BooleanSupplier stillValid,
                               Action action, Consumer<String> outcome) {
        onMain(() -> prepareEntry(entry, stillValid, action, outcome, alreadyApproved));
    }

    public void prepare(String kind, String id, String targetId, String targetName,
                        String input, String output, String modelName,
                        BooleanSupplier stillValid, Action action) {
        prepare(kind, id, targetId, targetName, input, output, modelName, stillValid, action, ignored -> { });
    }

    public void prepare(String kind, String id, String targetId, String targetName,
                        String input, String output, String modelName,
                        BooleanSupplier stillValid, Action action, Consumer<String> outcome) {
        if (stillValid == null || action == null || outcome == null) throw new IllegalArgumentException("Validity, action and outcome are required");
        long now = System.currentTimeMillis();
        Entry entry = new Entry(id, kind, targetId, targetName, input, output, modelName, "pending", now, now,
                AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? AutomationHistoryStore.DELIVERY_TEXT : AutomationHistoryStore.DELIVERY_UNKNOWN);
        onMain(() -> prepareEntry(entry, stillValid, action, outcome, false));
    }

    public void prepare(Entry entry, BooleanSupplier stillValid, Action action, Consumer<String> outcome) {
        if (entry == null || stillValid == null || action == null || outcome == null) throw new IllegalArgumentException("Entry and callbacks are required");
        onMain(() -> prepareEntry(entry, stillValid, action, outcome, false));
    }

    private void prepareEntry(Entry entry, BooleanSupplier stillValid, Action action, Consumer<String> outcome, boolean alreadyApproved) {
            if (closed) { outcome.accept("cancelled"); return; }
            String kind = entry.kind;
            Pending existing = pending.get(key(kind, entry.id));
            if (existing != null && existing.early && !existing.ready && existing.state.get() < SENDING) {
                existing.entry = entry;
                existing.action = action;
                existing.outcome = outcome;
                if (isReply(kind) && admittedPendingCount() >= MAX_PENDING) {
                    existing.entry = entry.withDiagnostics(Reason.QUEUE_FULL, Stage.QUEUE, entry.queueMillis,
                            entry.mediaMillis, entry.generationMillis, entry.sendMillis);
                    safeLog("Automatic action cancelled because the preview queue is full");
                    cancel(existing, AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? "deferred" : "cancelled");
                    return;
                }
                existing.ready = true;
                existing.phase = "preview";
                if (!enabled(kind) || !valid(existing)) { cancel(existing); return; }
                record(entry);
                if (existing.state.get() == DETAILS) {
                    refreshDetails(existing);
                    renderNotices();
                } else {
                    existing.state.set(WAITING);
                    if (alreadyApproved || !tipEnabled(kind)) dispatch(existing);
                    else startPreview(existing);
                }
                return;
            }
            if (closed || seen.containsKey(key(entry.kind, entry.id))) { outcome.accept("cancelled"); return; }
            remember(entry.kind, entry.id);
            if (admittedPendingCount() >= MAX_PENDING) {
                record(withStatus(entry.withDiagnostics(Reason.QUEUE_FULL, Stage.QUEUE, entry.queueMillis,
                        entry.mediaMillis, entry.generationMillis, entry.sendMillis), "cancelled"));
                safeLog("Automatic action cancelled because the preview queue is full");
                outcome.accept(AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? "deferred" : "cancelled");
                return;
            }
            Pending item = new Pending(entry, stillValid, action, outcome);
            pending.put(key(item.kind, item.entry.id), item);
            if (!enabled(kind) || !valid(item)) { cancel(item); return; }
            record(entry);
            if (tipEnabled(kind)) {
                startPreview(item);
            } else dispatch(item);
    }

    private void startPreview(Pending item) {
        item.trace.stage("preview_wait", "seconds=" + noticeSeconds(item.kind) + ";ready=" + item.ready);
        item.noticeDuration = noticeSeconds(item.kind) * 1000L;
        item.deadline = SystemClock.elapsedRealtime() + item.noticeDuration;
        renderNotices();
        tick(item);
    }

    public void reportFailure(String kind, String id, String targetId, String targetName,
                              String input, String modelName) {
        long now = System.currentTimeMillis();
        Entry entry = new Entry(id, kind, targetId, targetName, input, "", modelName, "failed", now, now);
        onMain(() -> {
            if (closed || seen.containsKey(key(kind, id))) return;
            remember(kind, id);
            record(entry);
            if (tipEnabled(kind)) {
                if (failureNotices.size() >= 8) failureNotices.remove(0);
                failureNotices.add(entry);
                renderNotices();
                main.postDelayed(() -> { failureNotices.remove(entry); renderNotices(); }, noticeSeconds(kind) * 1000L);
            }
        });
    }

    public void recordOutcome(Entry entry) { onMain(() -> record(entry)); }

    /** Amend a source-specific reason without replacing the dispatcher's final state or timings. */
    public void recordOutcomeReason(Entry source) {
        if (source == null || source.reason == Reason.NONE) return;
        onMain(() -> {
            try {
                worker.execute(() -> {
                    if (!saveHistory(source.kind)) return;
                    try {
                        for (Entry recorded : history.read(source.kind)) {
                            if (!source.kind.equals(recorded.kind) || !source.id.equals(recorded.id)
                                    || !source.status.equals(recorded.status)) continue;
                            history.upsert(recorded.withDiagnostics(source.reason, recorded.stage,
                                    recorded.queueMillis, recorded.mediaMillis, recorded.generationMillis, recorded.sendMillis));
                            return;
                        }
                    } catch (RuntimeException failure) { safeLog("Could not amend automatic action reason"); }
                });
            } catch (RejectedExecutionException ignored) { }
        });
    }

    public void commentRequestSent(String id, int imageCount) {
        onMain(() -> {
            if (closed || !options.enabled("comment_save_history")) return;
            try {
                worker.execute(() -> {
                    if (!options.enabled("comment_save_history")) return;
                    try { history.markModelRequestSent(AutomationHistoryStore.COMMENT, id, imageCount); }
                    catch (RuntimeException failure) { safeLog("Could not write comment image request history"); }
                });
            } catch (RejectedExecutionException ignored) { }
        });
    }

    public void reportFinalFailure(Entry entry) {
        onMain(() -> {
            if (closed) return;
            record(entry);
            if (!failureEnabled(entry.kind)) return;
            failureNotices.removeIf(previous -> previous.id.equals(entry.id) && previous.kind.equals(entry.kind));
            if (failureNotices.size() >= 8) failureNotices.remove(0);
            failureNotices.add(entry);
            if (AutomationHistoryStore.COMMENT.equals(entry.kind)) {
                commentFailureAttentionUntil = SystemClock.elapsedRealtime() + 1500;
                main.postDelayed(this::renderCommentRing, 1500);
            } else if (isReply(entry.kind)) {
                replyFailureAttentionUntil = SystemClock.elapsedRealtime() + 1500;
                main.postDelayed(this::renderReplyRing, 1500);
            }
            renderNotices();
            main.postDelayed(() -> { failureNotices.remove(entry); renderNotices(); }, noticeSeconds(entry.kind) * 1000L);
        });
    }

    public void cancelKind(String kind) {
        if (!AutomationHistoryStore.REPLY.equals(kind) && !AutomationHistoryStore.COMMENT.equals(kind)
                && !AutomationHistoryStore.COMMENT_REPLY.equals(kind)) {
            throw new IllegalArgumentException("Unknown automation kind");
        }
        onMain(() -> {
            for (Pending item : new ArrayList<>(pending.values())) if (kind.equals(item.kind)) cancel(item);
        });
    }

    private void remember(String kind, String id) {
        seen.put(key(kind, id), true);
        while (seen.size() > 512) seen.remove(seen.keySet().iterator().next());
    }

    private static String key(String kind, String id) { return kind + ':' + id; }

    private boolean enabled(String kind) {
        LocalOptions current = options;
        if (AutomationHistoryStore.COMMENT_REPLY.equals(kind)) {
            return current.enabled(LocalOptions.COMMENT_REPLY_ENABLED) && current.enabled("native_auto_reply");
        }
        return current.enabled(AutomationHistoryStore.REPLY.equals(kind)
                ? "native_auto_reply" : "native_auto_comment");
    }

    private boolean tipEnabled(String kind) {
        return options.enabled(settingsKind(kind) + "_tip");
    }

    private boolean failureEnabled(String kind) {
        return tipEnabled(kind);
    }

    private int noticeSeconds(String kind) {
        return Math.max(2, Math.min(120, options.integer(settingsKind(kind) + "_notice_seconds",
                AutomationMessageSettings.DEFAULT_NOTICE_SECONDS)));
    }

    private boolean valid(Pending item) {
        try { return item.stillValid.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }

    private void tick(Pending item) {
        if (closed || item.state.get() != WAITING) return;
        if (!enabled(item.kind) || !valid(item)) { cancel(item); return; }
        long remaining = item.deadline - SystemClock.elapsedRealtime();
        if (remaining <= 0) { dispatch(item); return; }
        if (AutomationHistoryStore.COMMENT.equals(item.kind)) renderCommentRing();
        else if (isReply(item.kind)) renderReplyRing();
        main.postDelayed(item.timer, Math.min(1000, Math.max(0, item.deadline - SystemClock.elapsedRealtime())));
    }

    private void dispatch(Pending item) {
        if (!item.ready) return;
        int state = item.state.get();
        if ((state != WAITING && state != DETAILS) || !item.state.compareAndSet(state, QUEUED)) return;
        item.queuedAt = SystemClock.elapsedRealtime();
        removeNotice(item);
        if (closed || !enabled(item.kind) || !valid(item)) { cancel(item); return; }
        try {
            worker.execute(item.trace.wrap(() -> {
                if (closed || !enabled(item.kind) || !valid(item)) { onMain(() -> cancel(item)); return; }
                if (!item.state.compareAndSet(QUEUED, SENDING)) return;
                item.sendStartedAt = SystemClock.elapsedRealtime();
                long queuedMillis = Math.max(0, item.sendStartedAt - item.queuedAt);
                item.entry = item.entry.withDiagnostics(item.entry.reason, Stage.SEND,
                        item.entry.queueMillis < 0 ? queuedMillis : item.entry.queueMillis + queuedMillis,
                        item.entry.mediaMillis, item.entry.generationMillis, item.entry.sendMillis);
                item.phase = "publishing";
                item.trace.stage("dispatch_send", "valid=true;enabled=true;queue_ms=" + queuedMillis + ";ack_deadline_ms=30000");
                onMain(this::renderNotices);
                record(withStatus(item.entry, "sending"));
                main.postDelayed(item.acknowledgmentTimeout, 30_000);
                try { item.action.send(item.trace.wrapConsumer(status -> finish(item, status))); }
                catch (Exception failure) {
                    item.trace.error("dispatch_send", failure);
                    safeLog("Automatic action failed: " + failure.getClass().getSimpleName());
                    finish(item, "failed");
                }
            }));
        } catch (RejectedExecutionException ignored) { item.trace.stage("dispatch_rejected", "worker_unavailable=true"); cancel(item); }
    }

    private void finish(Pending item, String status) {
        String result = "sent".equals(status) || "submitted".equals(status) || "cancelled".equals(status) ? status : "failed";
        if (!item.state.compareAndSet(SENDING, FINISHED) && !item.state.compareAndSet(UNCONFIRMED, FINISHED)) { item.trace.stage("dispatch_callback_ignored", "status=" + result + ";state=" + item.state.get()); return; }
        item.trace.stage("dispatch_result", "status=" + result + ";evidence=" + ("sent".equals(result) ? "native_sender_acknowledged" : "submitted".equals(result) ? "acknowledgment_missing" : "local_or_native_result"));
        onMain(() -> {
            item.entry = sendTiming(item);
            main.removeCallbacks(item.acknowledgmentTimeout);
            pending.remove(key(item.kind, item.entry.id));
            if (submittedReply == item) submittedReply = null;
            if (isReply(item.kind)) {
                if ("submitted".equals(result)) retainSubmittedReply(item);
                else if ("failed".equals(result)) addReplyFailure(withStatus(item.entry, result));
            }
            removeNotice(item);
            record(withStatus(item.entry, result));
            if ("sent".equals(result)) {
                if (AutomationHistoryStore.COMMENT.equals(item.kind)) {
                    com.douai.module.analytics.LocalAnalytics.feature("auto_comment_sent", "自动评论成功");
                } else if (isReply(item.kind)) {
                    com.douai.module.analytics.LocalAnalytics.feature("auto_reply_sent", "自动回复成功");
                }
            }
            safeLog("Automatic " + item.kind + " outcome: " + result);
            item.outcome.accept(result);
        });
    }

    private void unconfirmed(Pending item) {
        if (!item.state.compareAndSet(SENDING, UNCONFIRMED)) return;
        item.trace.stage("acknowledgment_timeout", "deadline_ms=30000;remote_outcome=unknown;retry_safe=false");
        item.entry = sendTiming(item);
        pending.remove(key(item.kind, item.entry.id));
        if (isReply(item.kind)) retainSubmittedReply(item);
        removeNotice(item);
        record(withStatus(item.entry, "submitted"));
        item.outcome.accept("submitted");
    }

    private void cancel(Pending item) {
        cancel(item, "cancelled");
    }

    private void cancel(Pending item, String outcome) {
        int state;
        do {
            state = item.state.get();
            if (state >= SENDING) return;
        } while (!item.state.compareAndSet(state, FINISHED));
        item.trace.stage("dispatch_cancelled", "previous_state=" + state + ";closed=" + closed + ";enabled=" + enabled(item.kind) + ";validity_rechecked=false");
        pending.remove(key(item.kind, item.entry.id));
        removeNotice(item);
        record(withStatus(item.entry, "cancelled"));
        item.outcome.accept(outcome);
    }

    private static Entry withStatus(Entry entry, String status) {
        Reason reason = entry.reason;
        Stage stage = entry.stage;
        if ("sent".equals(status)) {
            reason = Reason.NONE;
            stage = Stage.COMPLETE;
        } else if ("submitted".equals(status)) {
            if (reason == Reason.NONE) reason = Reason.SUBMITTED_UNCONFIRMED;
            stage = Stage.SEND;
        } else if ("failed".equals(status)) {
            if (reason == Reason.NONE) reason = Reason.SEND_FAILED;
        } else if ("cancelled".equals(status) && reason == Reason.NONE) {
            reason = Reason.CANCELLED;
        }
        return new Entry(entry.id, entry.kind, entry.targetId, entry.targetName,
                entry.input, entry.output, entry.modelName, status,
                entry.createdAt, System.currentTimeMillis(), entry.requestImageCount, entry.modelRequestSent,
                entry.deliveryMode).withLocation(entry.location).withDiagnostics(reason, stage, entry.queueMillis,
                entry.mediaMillis, entry.generationMillis, entry.sendMillis);
    }

    private static Entry sendTiming(Pending item) {
        Entry entry = item.entry;
        return entry.withDiagnostics(entry.reason, Stage.SEND, entry.queueMillis, entry.mediaMillis,
                entry.generationMillis, Math.max(0, SystemClock.elapsedRealtime() - item.sendStartedAt));
    }

    private void record(Entry entry) {
        if (!saveHistory(entry.kind)) return;
        try {
            worker.execute(() -> {
                if (!saveHistory(entry.kind)) return;
                try { history.upsert(entry); }
                catch (RuntimeException failure) { AutomationTrace.operation("auto_" + entry.kind, entry.id, "history").error("history_persistence", failure); safeLog("Could not write automatic action history"); }
            });
        } catch (RejectedExecutionException ignored) { }
    }

    private boolean saveHistory(String kind) {
        return options.enabled(settingsKind(kind) + "_save_history");
    }

    private static String settingsKind(String kind) { return AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? "reply" : kind; }

    private static boolean isReply(String kind) {
        return AutomationHistoryStore.REPLY.equals(kind) || AutomationHistoryStore.COMMENT_REPLY.equals(kind);
    }

    private void retainSubmittedReply(Pending item) {
        if (closed || !enabled(item.kind) || !tipEnabled(item.kind)) return;
        if (submittedReply != null && submittedReply != item && submittedReply.details != null) submittedReply.details.dismiss();
        submittedReply = item;
        item.phase = "submitted";
        main.postDelayed(() -> {
            if (submittedReply != item) return;
            submittedReply = null;
            removeNotice(item);
        }, noticeSeconds(item.kind) * 1000L);
    }

    private void addReplyFailure(Entry entry) {
        if (closed || !enabled(entry.kind) || !failureEnabled(entry.kind)) return;
        failureNotices.removeIf(previous -> previous.id.equals(entry.id) && previous.kind.equals(entry.kind));
        if (failureNotices.size() >= 8) failureNotices.remove(0);
        failureNotices.add(entry);
        replyFailureAttentionUntil = SystemClock.elapsedRealtime() + 1500;
        main.postDelayed(this::renderReplyRing, 1500);
        main.postDelayed(() -> { failureNotices.remove(entry); renderNotices(); }, noticeSeconds(entry.kind) * 1000L);
    }

    private void renderNotices() {
        // Both reply channels now share their own ring; retain no duplicate per-message banners.
        renderCommentRing();
        renderReplyRing();
    }
    private Pending currentComment() {
        if (commentQueueCount >= 0) return pending.get(key(AutomationHistoryStore.COMMENT, commentHead));
        for (Pending item : pending.values()) if (AutomationHistoryStore.COMMENT.equals(item.kind) && item.state.get() < FINISHED) return item;
        return null;
    }

    private Entry lastCommentFailure() {
        for (int index = failureNotices.size() - 1; index >= 0; index--) {
            Entry entry = failureNotices.get(index);
            if (AutomationHistoryStore.COMMENT.equals(entry.kind)) return entry;
        }
        return null;
    }

    private void renderCommentRing() {
        Activity activity = resumed.get();
        if (closed || !enabled(AutomationHistoryStore.COMMENT) || !tipEnabled(AutomationHistoryStore.COMMENT)
                || activity == null || activity.isFinishing() || activity.isDestroyed()) { commentRing.detach(); return; }
        Pending item = currentComment();
        int count = commentQueueCount;
        if (count < 0) {
            count = 0;
            for (Pending value : pending.values()) if (AutomationHistoryStore.COMMENT.equals(value.kind) && value.state.get() < FINISHED) count++;
        }
        Entry failure = lastCommentFailure();
        if (count == 0 && failure == null) { commentRing.detach(); return; }
        String phase = count == 0 ? "failed" : item == null ? commentQueuePhase : item.phase;
        float progress = -1;
        if (item != null) {
            int state = item.state.get();
            if (state == DETAILS) phase = "details";
            else if (state == WAITING && item.ready) {
                phase = "preview";
                progress = Math.max(0, Math.min(1, (item.deadline - SystemClock.elapsedRealtime()) / (float) Math.max(1, item.noticeDuration)));
            } else if (state >= QUEUED) phase = "publishing";
        }
        if (failure != null && !"details".equals(phase) && SystemClock.elapsedRealtime() < commentFailureAttentionUntil) {
            phase = "failed";
            progress = -1;
        }
        commentRing.show(activity, count, count == 0 ? 0 : commentRetries, phase, progress);
    }

    private void openCommentDetails() {
        Pending item = currentComment();
        if (item != null) { showDetails(item); return; }
        Entry failure = lastCommentFailure();
        Activity activity = resumed.get();
        if (failure == null || activity == null) return;
        showFailureDetails(activity, failure, "自动评论失败");
    }

    private void showFailureDetails(Activity activity, Entry failure, String title) {
        if (failureDetails != null) failureDetails.dismiss();
        TextView content = text(activity, target(failure) + "\n\n" + failure.input
                + (failure.output.isEmpty() ? "" : "\n\n" + failure.output), 15, Color.rgb(31, 39, 45));
        content.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        content.setTextIsSelectable(true);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(activity).setTitle(title).setView(scroll).setPositiveButton("关闭", null).create();
        failureDetails = dialog;
        dialog.setOnDismissListener(ignored -> { if (failureDetails == dialog) failureDetails = null; });
        dialog.setOwnerActivity(activity);
        dialog.show();
    }

    private void cancelRingComment() {
        if (commentQueueCount > 0) {
            cancelLatestComment.run();
            return;
        }
        Pending item = currentComment();
        if (item != null) {
            if (item.cancelFuture != null) item.cancelFuture.run();
            else cancel(item);
            return;
        }
        Entry failure = lastCommentFailure();
        if (failure != null) { failureNotices.remove(failure); renderCommentRing(); }
    }

    private Pending currentReply() {
        for (Pending item : pending.values()) {
            if (isReply(item.kind) && enabled(item.kind) && item.state.get() < FINISHED) return item;
        }
        return null;
    }

    private Entry lastReplyFailure() {
        for (int index = failureNotices.size() - 1; index >= 0; index--) {
            Entry entry = failureNotices.get(index);
            if (isReply(entry.kind) && enabled(entry.kind)) return entry;
        }
        return null;
    }

    private void renderReplyRing() {
        main.removeCallbacks(replyRefresh);
        Activity activity = resumed.get();
        String suppressed = closed ? "closed" : !enabled(AutomationHistoryStore.REPLY) ? "disabled"
                : !tipEnabled(AutomationHistoryStore.REPLY) ? "tip_off" : activity == null ? "no_activity"
                : activity.isFinishing() ? "finishing" : activity.isDestroyed() ? "destroyed" : "";
        if (!suppressed.isEmpty()) { replyRing.detach(); replyPresentation(activity, suppressed, "", 0); return; }
        Pending item = currentReply();
        int count = 0;
        for (Pending value : pending.values()) if (isReply(value.kind) && enabled(value.kind) && value.state.get() < FINISHED) count++;
        Pending unknown = submittedReply != null && enabled(submittedReply.kind) ? submittedReply : null;
        Entry failure = lastReplyFailure();
        if (item == null && unknown == null && failure == null) { replyRing.detach(); replyPresentation(activity, "empty", "", 0); return; }
        String phase = item == null ? unknown == null ? "failed" : "submitted" : item.phase;
        float progress = -1;
        int seconds = 0;
        if (item != null) {
            int state = item.state.get();
            if (state == DETAILS) phase = "details";
            else if (state == WAITING && item.ready) {
                phase = "preview";
                long remaining = Math.max(0, item.deadline - SystemClock.elapsedRealtime());
                seconds = (int) ((remaining + 999) / 1000);
                progress = Math.max(0, Math.min(1, remaining / (float) Math.max(1, item.noticeDuration)));
            } else if (state >= QUEUED) phase = "publishing";
        }
        if (failure != null && !"details".equals(phase) && SystemClock.elapsedRealtime() < replyFailureAttentionUntil) {
            phase = "failed";
            progress = -1;
        }
        try {
            replyRing.show(activity, count, seconds, phase, progress);
            replyPresentation(activity, "show", phase, count);
        } catch (RuntimeException unavailable) {
            replyPresentation(activity, "attach_failed", phase, count);
            safeLog("Reply overlay attach unavailable: " + unavailable.getClass().getSimpleName());
        }
        // Host relayouts can remove the ring while generation has no stage updates.
        main.postDelayed(replyRefresh, 1000);
    }

    private void replyPresentation(Activity activity, String state, String phase, int count) {
        String owner = activity == null ? "none" : activity.getClass().getSimpleName();
        Pending item = currentReply();
        if (item == null && submittedReply != null && enabled(submittedReply.kind)) item = submittedReply;
        Entry failure = item == null ? lastReplyFailure() : null;
        String kind = item != null ? item.kind : failure == null ? "none" : failure.kind;
        CommentRingView view = replyRing.view();
        boolean attached = view.isAttachedToWindow();
        boolean shown = view.isShown();
        boolean parentMatch = activity != null && activity.getWindow() != null
                && view.getParent() == activity.getWindow().getDecorView();
        String next = state + '|' + phase + '|' + count + '|' + owner + '|' + kind
                + '|' + attached + '|' + shown + '|' + parentMatch + '|' + tipEnabled(AutomationHistoryStore.REPLY)
                + '|' + (item == null ? "" : item.trace.traceId());
        if (next.equals(replyPresentation)) return;
        replyPresentation = next;
        safeLog("[DouAi.ReplyOverlay] state=" + state + " phase=" + phase + " count=" + count
                + " activity=" + owner + " tip=" + tipEnabled(AutomationHistoryStore.REPLY));
        String evidence = "kind=" + kind + ";phase=" + phase + ";count=" + count + ";activity=" + owner
                + ";tip=" + tipEnabled(AutomationHistoryStore.REPLY) + ";attached=" + attached
                + ";shown=" + shown + ";parent_match=" + parentMatch;
        // A request and observed View flags do not prove visibility above other windows.
        Runnable record = () -> DouAiLog.event("automation_dispatch", "reply_overlay_state",
                "show".equals(state) ? "show_requested" : state, evidence);
        try {
            if (item == null) record.run(); else item.trace.wrap(record).run();
        } catch (RuntimeException | LinkageError ignored) { /* Presentation diagnostics cannot affect delivery. */ }
    }

    private void openReplyDetails() {
        Pending item = currentReply();
        if (item != null) { showDetails(item); return; }
        if (submittedReply != null && enabled(submittedReply.kind)) { showDetails(submittedReply); return; }
        Entry failure = lastReplyFailure();
        Activity activity = resumed.get();
        if (failure != null && activity != null) showFailureDetails(activity, failure, "自动回复失败");
    }

    private void cancelRingReply() {
        Pending latest = null;
        for (Pending item : pending.values()) {
            if (isReply(item.kind) && item.state.get() < SENDING) latest = item;
        }
        if (latest != null) { cancel(latest); return; }
        Entry failure = lastReplyFailure();
        if (failure != null) { failureNotices.remove(failure); renderReplyRing(); }
    }

    private void showDetails(Pending item) {
        Activity activity = resumed.get();
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
        if (item.details != null) return;
        int state = item.state.get();
        boolean readOnly = state >= SENDING;
        if (!readOnly && (state != WAITING && state != GENERATING && !(isReply(item.kind) && state == QUEUED)
                || !item.state.compareAndSet(state, DETAILS))) return;
        if (!readOnly) main.removeCallbacks(item.timer);
        renderNotices();
        LinearLayout body = new LinearLayout(activity);
        body.setTag("automation.details");
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(activity, 20), dp(activity, 12), dp(activity, 20), dp(activity, 12));
        body.addView(text(activity, target(item.entry), 14, Color.rgb(105, 115, 122)));
        item.detailStatus = text(activity, phaseName(item) + "\n" + AutomationDiagnostics.details(item.entry), 13,
                isReply(item.kind) ? Color.rgb(53, 122, 204) : Color.rgb(0, 121, 107));
        body.addView(item.detailStatus);
        TextView inputTitle = text(activity, AutomationHistoryStore.COMMENT_REPLY.equals(item.kind) ? "收到的评论回复"
                : AutomationHistoryStore.REPLY.equals(item.kind) ? "收到的消息" : "视频内容", 13, Color.rgb(105, 115, 122));
        inputTitle.setPadding(0, dp(activity, 16), 0, dp(activity, 4));
        body.addView(inputTitle);
        TextView input = text(activity, item.entry.input, 15, Color.rgb(31, 39, 45));
        input.setTag("automation.details.input");
        input.setTextIsSelectable(true);
        body.addView(input);
        TextView outputTitle = text(activity, "生成的" + verb(item.kind), 13, Color.rgb(105, 115, 122));
        outputTitle.setPadding(0, dp(activity, 16), 0, dp(activity, 4));
        body.addView(outputTitle);
        TextView output = text(activity, item.entry.output, 15, Color.rgb(31, 39, 45));
        output.setTag("automation.details.output");
        output.setTextIsSelectable(true);
        item.detailOutput = output;
        body.addView(output);
        ScrollView scroll = new ScrollView(activity);
        scroll.addView(body);
        AlertDialog.Builder builder = new AlertDialog.Builder(activity).setTitle(kindName(item.kind)).setView(scroll);
        if (readOnly) builder.setPositiveButton("关闭", null);
        else builder.setPositiveButton(isReply(item.kind) ? "立即发送" : "立即" + verb(item.kind), (ignored, which) -> dispatch(item))
                .setNegativeButton(isReply(item.kind) ? "取消回复" : "取消" + verb(item.kind), (ignored, which) -> cancel(item));
        AlertDialog dialog = builder.create();
        dialog.setOwnerActivity(activity);
        item.details = dialog;
        dialog.setOnDismissListener(ignored -> {
            item.details = null;
            item.detailOutput = null;
            item.detailStatus = null;
            if (item.state.get() == DETAILS) cancel(item);
        });
        dialog.show();
        refreshDetails(item);
    }

    private void refreshDetails(Pending item) {
        if (item.detailOutput != null) item.detailOutput.setText(item.ready ? item.entry.output : "正在生成…");
        if (item.detailStatus != null) item.detailStatus.setText(phaseName(item) + "\n" + AutomationDiagnostics.details(item.entry));
        if (item.details instanceof AlertDialog && item.state.get() == DETAILS) {
            ((AlertDialog) item.details).getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(item.ready && valid(item));
        }
    }

    private static String phaseName(Pending item) {
        if ("submitted".equals(item.phase)) return "已提交，结果待确认";
        if (item.state.get() >= SENDING) return isReply(item.kind) ? "正在发送" : "正在发布";
        if (item.ready) return "等待确认";
        if ("media".equals(item.phase)) return "正在读取素材";
        if ("speech".equals(item.phase)) return "正在合成语音";
        if ("waiting".equals(item.phase)) return "等待生成";
        if ("retrying".equals(item.phase)) return "等待重试";
        return "正在生成";
    }

    private void removeNotice(Pending item) {
        main.removeCallbacks(item.timer);
        if (item.details != null) {
            Dialog dialog = item.details;
            item.details = null;
            dialog.dismiss();
        }
        renderNotices();
    }

    private static String target(Entry entry) {
        return entry.targetName.isEmpty() ? entry.targetId : entry.targetName;
    }

    private static String verb(String kind) { return AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? "评论回复"
            : AutomationHistoryStore.REPLY.equals(kind) ? "回复" : "评论"; }
    private static String kindName(String kind) { return "自动" + verb(kind); }

    private static TextView text(Context context, String value, int size, int color) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private void safeLog(String value) {
        try { log.accept(value); } catch (Throwable ignored) { }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    @Override public void onActivityResumed(Activity activity) {
        resumed = new WeakReference<>(activity);
        safeLog("[DouAi.ReplyOverlay] lifecycle=resumed activity=" + (activity == null ? "none" : activity.getClass().getSimpleName()));
        renderNotices();
    }

    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() != activity) return;
        safeLog("[DouAi.ReplyOverlay] lifecycle=paused activity=" + (activity == null ? "none" : activity.getClass().getSimpleName()));
        resumed.clear();
        commentRing.detach();
        main.removeCallbacks(replyRefresh);
        replyRing.detach();
        replyPresentation(null, "no_activity", "", 0);
        if (failureDetails != null) failureDetails.dismiss();
        if (submittedReply != null && submittedReply.details != null) submittedReply.details.dismiss();
        for (Pending item : new ArrayList<>(pending.values())) if (item.details != null) {
            if (item.state.get() >= SENDING) item.details.dismiss(); else cancel(item);
        }
    }

    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        onMain(() -> {
            for (Pending item : new ArrayList<>(pending.values())) { cancel(item); removeNotice(item); }
            commentRing.detach();
            main.removeCallbacks(replyRefresh);
            replyRing.detach();
            if (failureDetails != null) failureDetails.dismiss();
            if (submittedReply != null) { removeNotice(submittedReply); submittedReply = null; }
            failureNotices.clear();
            resumed.clear();
            replyPresentation(null, "closed", "", 0);
            application.unregisterActivityLifecycleCallbacks(this);
            worker.shutdown();
        });
    }

    private final class Pending {
        Entry entry;
        final String kind;
        final DouAiLog.Operation trace;
        final BooleanSupplier stillValid;
        Action action;
        Consumer<String> outcome;
        Runnable cancelFuture;
        final AtomicInteger state = new AtomicInteger(WAITING);
        final Runnable timer = () -> tick(this);
        final Runnable acknowledgmentTimeout = () -> unconfirmed(this);
        long deadline;
        long noticeDuration;
        long queuedAt;
        long sendStartedAt;
        boolean early;
        boolean ready = true;
        String phase = "preview";
        TextView detailOutput;
        TextView detailStatus;
        Dialog details;

        Pending(Entry entry, BooleanSupplier stillValid, Action action, Consumer<String> outcome) {
            this.entry = entry;
            this.kind = entry.kind;
            this.trace = AutomationTrace.operation("auto_" + entry.kind, entry.id, "preview");
            this.trace.stage("preview_admission", "history_ref=" + DouAiLog.reference(entry.id) + ";delivery=" + entry.deliveryMode);
            this.stillValid = stillValid;
            this.action = action;
            this.outcome = outcome;
        }
    }
}
