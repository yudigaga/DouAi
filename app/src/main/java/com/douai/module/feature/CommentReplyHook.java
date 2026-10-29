package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.douai.module.diagnostics.DouAiLog;

import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.function.IntConsumer;

/** The comment inbox for automatic replies; modes and delivery settings belong to the parent. */
public final class CommentReplyHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    interface Source extends AutoCloseable {
        long baseline(long account, BooleanSupplier valid) throws Exception;
        default List<String> baselineIds() { return Collections.emptyList(); }
        default long scannedThrough() { return 0; }
        default long watermarkCeiling() { return Long.MAX_VALUE; }
        List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception;
        @Override default void close() { }
    }
    interface Sender extends AutoCloseable {
        void send(CommentReplyEvent event, String text, BooleanSupplier valid,
                  BooleanSupplier beforeSend, Runnable accepted, Consumer<String> result);
        default void send(CommentReplyEvent event, String text, SpeechPreparation.Audio audio, BooleanSupplier valid,
                          BooleanSupplier beforeSend, Runnable accepted, Consumer<String> result) {
            if (audio != null) throw new IllegalStateException("Comment voice sender unavailable");
            send(event, text, valid, beforeSend, accepted, result);
        }
        default boolean owns(Object model) { return false; }
        @Override default void close() { }
    }
    interface Generator extends AutoCloseable {
        Generated generate(long account, CommentReplyEvent event, LocalOptions options) throws Exception;
        default Generated generate(long account, CommentReplyEvent event, LocalOptions options,
                                   List<ChatImage> images, String prompt, IntConsumer requestSent) throws Exception {
            return generate(account, event, options);
        }
        default void acknowledged(long account, CommentReplyEvent event, LocalOptions options, String text) throws Exception { }
        default void acknowledged(long account, CommentReplyEvent event, LocalOptions options, String text,
                                  List<ChatImage> images, String prompt) throws Exception { acknowledged(account, event, options, text); }
        default void cancel() { }
        @Override default void close() { cancel(); }
    }
    static final class Generated {
        final String text, model;
        Generated(String text, String model) { this.text = text; this.model = model; }
    }
    private static final String KIND = "comment_reply";
    private static final long POLL_MILLIS = 60_000;
    private static final int MAX_PENDING = 12;
    private static final int MAX_THREAD_REPLIES = 3;
    private final Application application;
    private final AutomationDispatcher dispatcher;
    private final Source source;
    private final Sender sender;
    private final Generator generator;
    private final LongSupplier ownAccount;
    private final ExecutorService scanner, worker, ledgerWorker;
    private final CommentReplyLedger ledger;
    private final AutomationRateLimiter rateLimiter;
    private final Consumer<String> log;
    private SpeechPreparation.Preparer speechPreparer = SpeechPreparation::prepareComment;
    interface MediaLoader {
        List<ChatImage> load(Context context, CommentReplyEvent event, LocalOptions options, MediaReadCancellation cancellation) throws Exception;
    }
    private MediaLoader mediaLoader = AutomationReplyMedia::comment;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Object stateLock = new Object();
    private Task activeGeneratingTask;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean scanQueued = new AtomicBoolean();
    private final Map<String, Task> tasks = new ConcurrentHashMap<>();
    private volatile LocalOptions options;
    private volatile boolean foreground, closed;
    private boolean foregroundSession;
    private volatile long sessionAccount;
    private volatile WeakReference<Activity> resumed = new WeakReference<>(null);
    private volatile String diagnostic = "未启用";
    private final Runnable scanSignal = this::requestScan;
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (!enabled() || !foregroundSession) return;
            requestScan();
            main.postDelayed(this, account() > 0 ? POLL_MILLIS : 5_000);
        }
    };
    private final Runnable background = () -> {
        if (closed || foreground) return;
        foregroundSession = false;
        main.removeCallbacks(poll);
        main.removeCallbacks(scanSignal);
        invalidate();
    };

    CommentReplyHook(Context context, LocalOptions options, AutomationDispatcher dispatcher,
                     Source source, Sender sender, Generator generator, LongSupplier ownAccount,
                     ExecutorService scanner, ExecutorService worker, Consumer<String> log) {
        this(context, options, dispatcher, source, sender, generator, ownAccount, scanner, worker,
                newWorker("DouAi-comment-reply-ledger"), log);
    }

    CommentReplyHook(Context context, LocalOptions options, AutomationDispatcher dispatcher,
                     Source source, Sender sender, Generator generator, LongSupplier ownAccount,
                     ExecutorService scanner, ExecutorService worker, ExecutorService ledgerWorker, Consumer<String> log) {
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) throw new IllegalArgumentException("Application context required");
        this.application = (Application) app;
        this.options = options;
        this.dispatcher = dispatcher;
        this.source = source;
        this.sender = sender;
        this.generator = generator;
        this.ownAccount = ownAccount;
        this.scanner = scanner;
        this.worker = worker;
        this.ledgerWorker = ledgerWorker;
        this.log = log == null ? ignored -> { } : log;
        ledger = new CommentReplyLedger(app);
        rateLimiter = new AutomationRateLimiter(app);
        application.registerActivityLifecycleCallbacks(this);
    }

    public static CommentReplyHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, Context context, LocalOptions options,
                                           AutomationDispatcher dispatcher, Consumer<String> log) throws Exception {
        Method service = HostTargets.method(report, "media_account_service", loader);
        Method user = HostTargets.method(report, "media_current_user", loader);
        NativeCommentPublisher publisher = NativeCommentPublisher.install(framework, report, loader, log, "DouAi.CommentReply");
        NativeCommentReplyInbox inbox = null;
        CommentReplyHook[] installed = new CommentReplyHook[1];
        try {
            inbox = NativeCommentReplyInbox.install(framework, report, loader, log, () -> {
                CommentReplyHook hook = installed[0];
                if (hook != null) hook.signal();
            });
            NativeCommentReplyInbox captured = inbox;
            Source source = new Source() {
                @Override public long baseline(long account, BooleanSupplier valid) throws Exception { return captured.baseline(account, valid); }
                @Override public List<String> baselineIds() { return captured.baselineCommentIds(); }
                @Override public long scannedThrough() { return captured.scannedThrough(); }
                @Override public long watermarkCeiling() { return captured.scannedThrough(); }
                @Override public List<CommentReplyEvent> scan(long account, long since, BooleanSupplier valid) throws Exception { return captured.scan(account, since, valid); }
                @Override public void close() { captured.close(); }
            };
            Sender sender = new Sender() {
                @Override public void send(CommentReplyEvent event, String text, BooleanSupplier valid,
                                           BooleanSupplier before, Runnable accepted, Consumer<String> result) {
                    publisher.sendReply(event, text, valid, before, accepted, result);
                }
                @Override public void send(CommentReplyEvent event, String text, SpeechPreparation.Audio audio,
                                           BooleanSupplier valid, BooleanSupplier before, Runnable accepted, Consumer<String> result) {
                    publisher.sendReply(event, text, audio, valid, before, accepted, result);
                }
                @Override public boolean owns(Object model) { return publisher.ownsPublish(model); }
                @Override public void close() { publisher.close(); }
            };
            LongSupplier account = () -> {
                try {
                    Object current = user.invoke(service.invoke(null));
                    return Long.parseLong(String.valueOf(HostTargets.invoke(current, "getUid")));
                } catch (ReflectiveOperationException | RuntimeException failure) { return 0; }
            };
            installed[0] = new CommentReplyHook(context, options, dispatcher, source, sender, new SharedReplyGenerator(log),
                    account, newWorker("DouAi-comment-reply-scan"), newWorker("DouAi-comment-reply-generate"), log);
            return installed[0];
        } catch (Exception failure) {
            if (inbox != null) inbox.close();
            publisher.close();
            throw failure;
        }
    }

    public boolean ownsPublish(Object model) { return sender.owns(model); }
    public String diagnostic() { return diagnostic; }

    static ThreadPoolExecutor newWorker(String name) {
        return new ThreadPoolExecutor(0, 1, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), action -> {
            Thread thread = new Thread(action, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    public void setOptions(LocalOptions next) {
        boolean changed;
        synchronized (stateLock) {
            LocalOptions before = options;
            changed = before.enabled(LocalOptions.COMMENT_REPLY_ENABLED) != next.enabled(LocalOptions.COMMENT_REPLY_ENABLED)
                    || before.enabled("native_auto_reply") != next.enabled("native_auto_reply")
                    || !before.samePrefix(next, "reply_") || !before.samePrefix(next, "tts_")
                    || !before.text("auto_message_text", "").equals(next.text("auto_message_text", ""))
                    || !AiModelSettings.sameAutomationSelection(before, next, AutomationHistoryStore.REPLY);
            options = next;
            if (changed) invalidate();
        }
        if (changed) main.post(() -> {
            main.removeCallbacks(poll);
            main.removeCallbacks(scanSignal);
            if (enabled()) {
                if (foregroundSession) {
                    main.postDelayed(poll, POLL_MILLIS);
                    signal();
                }
            } else diagnostic = "未启用";
        });
    }

    private boolean enabled() {
        LocalOptions current = options;
        return !closed && current.enabled(LocalOptions.COMMENT_REPLY_ENABLED) && current.enabled("native_auto_reply");
    }
    private long account() { try { return ownAccount.getAsLong(); } catch (RuntimeException ignored) { return 0; } }
    private boolean valid(long account, long epoch) {
        synchronized (stateLock) {
            return enabled() && foreground && generation.get() == epoch && sessionAccount == account && account() == account;
        }
    }
    private boolean valid(Task task) {
        synchronized (stateLock) { return !task.finished.get() && valid(task.account, task.epoch); }
    }

    void signal() {
        if (!enabled() || !foreground) return;
        main.removeCallbacks(scanSignal);
        main.postDelayed(scanSignal, 1_000);
    }

    void requestScan() {
        if (!enabled() || !foreground) { DouAiLog.event("auto_comment_reply", "scan_eligibility", "disabled_or_background", "enabled=" + enabled() + ";foreground=" + foreground); return; }
        long account = account();
        if (account <= 0) { DouAiLog.event("auto_comment_reply", "scan_eligibility", "account_unavailable", "ready=false"); diagnostic = "等待登录"; return; }
        if (sessionAccount != account) {
            invalidate();
            sessionAccount = account;
        }
        if (!scanQueued.compareAndSet(false, true)) return;
        long epoch = generation.get();
        try {
            scanner.execute(() -> {
                DouAiLog.Operation scanTrace = DouAiLog.begin("comment_reply_inbox", "foreground_poll_or_push");
                String stage = "baseline";
                int admitted = 0;
                boolean completed = false;
                try {
                    if (!valid(account, epoch)) return;
                    if (!ledger.initialized(account)) {
                        long baseline = scanTrace.call(() -> source.baseline(account, () -> valid(account, epoch)));
                        if (!valid(account, epoch)) return;
                        if (!ledger.initialize(account, baseline, source.baselineIds())) {
                            throw new IllegalStateException("Could not persist comment inbox baseline");
                        }
                        diagnostic = "已建立起点，等待新回复";
                        completed = true;
                        scanTrace.finish(DouAiLog.Outcome.SUCCESS, "baseline_persisted", "historical_reply_execution=false;baseline_ids=" + source.baselineIds().size());
                        return;
                    }
                    stage = "model_migration";
                    long previousWatermark = ledger.watermark(account);
                    if (!ledger.ensureInboxModelVersion(account)) {
                        throw new NativeCommentReplyInbox.ScanFailure(NativeCommentReplyInbox.FailureReason.STATE_MIGRATION);
                    }
                    if (ledger.watermark(account) < previousWatermark) {
                        safeLog("Comment reply inbox replay: source=baseline; model_version="
                                + CommentReplyLedger.INBOX_MODEL_VERSION);
                    }
                    stage = "scan";
                    long since = Math.max(ledger.baseline(account), ledger.watermark(account) - 300);
                    scanTrace.stage("inbox_scan", "watermark_ref=" + DouAiLog.reference(Long.toString(since)));
                    final long scanSince = since;
                    List<CommentReplyEvent> events = scanTrace.call(() -> source.scan(account, scanSince, () -> valid(account, epoch)));
                    scanTrace.stage("inbox_scan_ready", "events=" + events.size());
                    if (!valid(account, epoch)) return;
                    long newest = Math.max(ledger.watermark(account), source.scannedThrough());
                    boolean deferred = false;
                    for (CommentReplyEvent event : events) {
                        if (!valid(account, epoch)) return;
                        if (event.createdAt < ledger.baseline(account)) { DouAiLog.event("auto_comment_reply", "eligibility", "before_baseline", "eligible=false"); continue; }
                        newest = Math.max(newest, event.createdAt);
                        if ((event.text == null || event.text.trim().isEmpty()) && (!"ai".equals(options.text("reply_mode", "fixed"))
                                || !options.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                                || !AutomationReplyMedia.hasCommentImages(event.targetComment))) continue;
                        String identity = account + ":" + event.incomingId;
                        if (tasks.containsKey(identity)) { deferred = true; continue; }
                        int threadLimit = MAX_THREAD_REPLIES;
                        if (ledger.threadExhausted(account, event.rootId, threadLimit)) { DouAiLog.event("auto_comment_reply", "eligibility", "thread_limit", "limit=" + threadLimit); continue; }
                        if (ledger.contains(account, event.incomingId) && !ledger.retryable(account, event.incomingId)) { DouAiLog.event("auto_comment_reply", "eligibility", "ledger_duplicate_or_not_retryable", "incoming_ref=" + DouAiLog.reference(event.incomingId)); continue; }
                        if (tasks.size() >= MAX_PENDING || !rateLimiter.canSend(KIND, account, event.senderUid, options)) {
                            deferred = true;
                            continue;
                        }
                        Task task = new Task(account, epoch, identity, event);
                        if (!ledger.claim(account, event.incomingId, event.rootId, threadLimit, task.id)) {
                            task.trace.finish(DouAiLog.Outcome.SKIPPED, "durable_claim_rejected", "native_invoked=false");
                            deferred = true;
                            continue;
                        }
                        tasks.put(identity, task);
                        preparation(task, "waiting");
                        admitted++;
                        deferred = true;
                        long delay = Math.max(2, Math.min(3600, options.integer("reply_delay_seconds", 2))) * 1000L;
                        main.postDelayed(() -> {
                            if (!valid(task)) { complete(task, "cancelled"); return; }
                            try { worker.execute(() -> generate(task)); }
                            catch (RejectedExecutionException failure) { complete(task, "failed"); }
                        }, delay);
                    }
                    if (!deferred && tasks.isEmpty()) ledger.advance(account, Math.min(newest, source.watermarkCeiling()));
                    CommentReplyLedger.Health health = ledger.health();
                    diagnostic = health.blocked() ? health.diagnostic() : deferred ? "等待处理或发送额度" : "已检查新回复";
                    completed = true;
                    scanTrace.finish(DouAiLog.Outcome.SUCCESS, "scan_evaluated", "events=" + events.size() + ";admitted=" + admitted + ";deferred=" + deferred + ";ledger_blocked=" + health.blocked() + ";business_delivery_confirmed=false");
                } catch (Exception failure) {
                    completed = true;
                    DouAiLog.Outcome outcome = scanFailureOutcome(failure);
                    if (outcome != DouAiLog.Outcome.CANCELLED) scanTrace.error(stage, failure);
                    scanTrace.finish(outcome, outcome == DouAiLog.Outcome.CANCELLED ? "scan_cancelled"
                                    : outcome == DouAiLog.Outcome.TIMED_OUT ? "scan_timed_out" : "scan_failed",
                            "stage=" + stage + ";admitted=" + admitted + ";evidence="
                                    + NativeCommentReplyInbox.failureDiagnostic(failure));
                    if (valid(account, epoch)) {
                        CommentReplyLedger.Health health = ledger.health();
                        diagnostic = health.blocked() ? health.diagnostic() : "扫描暂未完成，稍后重试";
                        safeLog("Comment reply scan " + (outcome == DouAiLog.Outcome.CANCELLED ? "cancelled" : "failed") + ": stage=" + stage + "; "
                                + NativeCommentReplyInbox.failureDiagnostic(failure));
                    }
                } finally {
                    if (!completed) scanTrace.finish(DouAiLog.Outcome.CANCELLED, "scan_invalidated", "stage=" + stage + ";admitted=" + admitted);
                    scanQueued.set(false);
                    if (generation.get() != epoch && enabled() && foreground) signal();
                }
            });
        } catch (RejectedExecutionException failure) { scanQueued.set(false); }
    }

    /** Log classification only: callers retain the existing retry, ledger and scheduling decisions. */
    static DouAiLog.Outcome scanFailureOutcome(Throwable failure) {
        for (int depth = 0; failure != null && depth < 16; depth++) {
            if (failure instanceof java.util.concurrent.CancellationException || failure instanceof InterruptedException) {
                return DouAiLog.Outcome.CANCELLED;
            }
            if (failure instanceof java.util.concurrent.TimeoutException) return DouAiLog.Outcome.TIMED_OUT;
            Throwable cause = failure.getCause();
            if (cause == failure) break;
            failure = cause;
        }
        return DouAiLog.Outcome.FAILED;
    }

    private void generate(Task task) {
        synchronized (stateLock) {
            if (!valid(task)) { complete(task, "cancelled"); return; }
            activeGeneratingTask = task;
        }
        try { task.trace.wrap(() -> generateTraced(task)).run(); }
        finally {
            synchronized (stateLock) { if (activeGeneratingTask == task) activeGeneratingTask = null; }
        }
    }

    private void preparation(Task task, String phase) {
        dispatcher.trackReply(entry(task, "pending"), phase, () -> valid(task), () -> cancelPreparation(task));
    }

    private void cancelPreparation(Task task) {
        synchronized (stateLock) {
            if (task.finished.get() || task.submitted) return;
            if (activeGeneratingTask == task) generator.cancel();
            complete(task, "cancelled");
        }
    }

    private void generateTraced(Task task) {
        task.trace.stage("eligibility_recheck", "account_ref=" + DouAiLog.reference(Long.toString(task.account)) + ";epoch_current=" + (task.epoch == generation.get()));
        if (!valid(task)) { complete(task, "cancelled"); return; }
        if (!rateLimiter.canSend(KIND, task.account, task.event.senderUid, options)) { complete(task, "deferred"); return; }
        Runnable timeout = () -> {
            synchronized (stateLock) {
                if (task.finished.get() || task.submitted) return;
                task.media.cancel();
                if (activeGeneratingTask == task) generator.cancel();
                task.timedOut = true;
                task.trace.stage("generation_timeout", "deadline_ms=120000;remote_model_outcome=unknown");
                fail(task, "生成超时");
            }
        };
        main.postDelayed(timeout, 120_000);
        try {
            synchronized (stateLock) {
                if (!valid(task)) { complete(task, "cancelled"); return; }
                task.settings = options;
                task.trace.configuration(task.settings.diagnosticRevision(), "live_at_generation");
            }
            if ("ai".equals(task.settings.text("reply_mode", "fixed"))) {
                task.stage = "media";
                preparation(task, "media");
            task.trace.stage("media", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
                task.images = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(
                        mediaLoader.load(application, task.event, task.settings, task.media)));
            }
            if (!valid(task)) { complete(task, "cancelled"); return; }
            if ((task.event.text == null || task.event.text.trim().isEmpty()) && task.images.isEmpty()) { complete(task, "cancelled"); return; }
            task.stage = "input";
            task.trace.stage("input", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
            task.input = input(task.event, task.images.isEmpty() ? "" : AutomationReplyMedia.commentSourceLabel(task.event, task.settings));
            task.stage = "model";
            preparation(task, "generating");
            task.trace.stage("model", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
            Generated generated = generator.generate(task.account, task.event, task.settings, task.images, task.input, count -> {
                task.requestImageCount = count;
                task.modelRequestSent = true;
                task.trace.stage("model_request_sent", "images=" + count + ";business_success=false");
                safeLog("Comment reply AI request sent: images=" + count);
            });
            if (!valid(task)) { complete(task, "cancelled"); return; }
            task.stage = "output";
            task.trace.stage("output", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
            task.generatedChars = generated == null || generated.text == null ? 0 : generated.text.length();
            if (generated == null || generated.text == null || generated.text.trim().isEmpty()) {
                complete(task, "cancelled");
                return;
            }
            task.generated = true;
            if (generated.text.length() > 1000) throw new IllegalArgumentException("Comment reply output is too long");
            task.text = generated.text.trim();
            task.model = generated.model == null ? "" : generated.model;
            safeLog("Comment reply generated: chars=" + task.generatedChars);
            if (task.settings.enabled(LocalOptions.REPLY_VOICE)) {
                task.stage = "speech";
                preparation(task, "speech");
            task.trace.stage("speech", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
                SpeechPreparation.Audio audio = speechPreparer.prepare(application, task.text, task.settings, Long.MAX_VALUE, () -> valid(task));
                task.audio = audio;
                if (!valid(task)) { audio.delete(); complete(task, "cancelled"); return; }
            }
            task.stage = "dispatch";
            task.trace.stage("dispatch", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
            dispatcher.prepare(entry(task, "pending"), () -> valid(task),
                    done -> task.trace.wrap(() -> send(task, done)).run(), task.trace.wrapConsumer(status -> complete(task, status)));
        } catch (Exception failure) {
            task.trace.error(task.stage, failure);
            if (!task.finished.get()) {
                if (valid(task)) fail(task, generationFailureDiagnostic(failure));
                else complete(task, "cancelled");
            }
        } finally { main.removeCallbacks(timeout); }
    }

    private void send(Task task, Consumer<String> done) {
        task.stage = "publish";
            task.trace.stage("publish", "submitted=" + task.submitted + ";generated_chars=" + task.generatedChars);
        // Persist both allowances and the submission barrier before the native publisher posts to main.
        submitLedger(() -> {
            if (!valid(task)) { task.deferCancellation = true; done.accept("cancelled"); return; }
            LocalOptions current = options;
            task.trace.configuration(current.diagnosticRevision(), "live_send_guard");
            task.reservation = rateLimiter.tryReserve(KIND, task.account, task.event.senderUid, current);
            if (task.reservation == null) { task.boundaryFailed = true; done.accept("cancelled"); return; }
            if (!valid(task) || !ledger.submitted(task.account, task.event.incomingId, task.id)) {
                rateLimiter.release(task.reservation);
                task.reservation = null;
                task.boundaryFailed = true;
                done.accept("cancelled");
                return;
            }
            task.persistedSubmission = true;
            task.trace.stage("submission_reserved", "durable=true;business_success=false");
            if (!valid(task)) { task.deferCancellation = true; done.accept("cancelled"); return; }
            sendReserved(task, done);
        }, () -> {
            task.boundaryFailed = true;
            done.accept(task.submitted ? "submitted" : "cancelled");
        });
    }

    private void sendReserved(Task task, Consumer<String> done) {
        sender.send(task.event, task.text, task.audio, () -> valid(task), () -> {
            synchronized (stateLock) {
                // No disk work or ledger lock on main. The worker already owns this durable attempt.
                if (!valid(task) || !task.persistedSubmission || task.reservation == null) return false;
                task.submitted = true;
                task.trace.stage("native_invoking", "reserved=true;business_success=false");
                if (task.audio != null) new Handler(Looper.getMainLooper()).postDelayed(task.audio::delete, 600_000);
                return true;
            }
        }, () -> {
            task.trace.stage("native_accepted", "awaiting_callback=true;business_success=false");
            safeLog("Comment reply native request accepted");
        }, status -> {
            task.trace.stage(task.finished.get() ? "late_native_callback" : "native_callback", "status=" + status + ";submitted=" + task.submitted + ";uncertain=" + task.uncertain);
            if (task.finished.get() && !task.submitted) return;
            if ("submitted".equals(status)) task.uncertain = true;
            if ("cancelled".equals(status) && shouldDeferCancellation(task)) task.deferCancellation = true;
            submitLedger(() -> {
                String outcome = task.uncertain && task.submitted && ("failed".equals(status) || "cancelled".equals(status)) ? "submitted" : status;
                if ("failed".equals(outcome) || "cancelled".equals(outcome)) rateLimiter.release(task.reservation);
                if (task.audio != null && ("sent".equals(outcome) || "failed".equals(outcome) || "cancelled".equals(outcome))) task.audio.delete();
                persistCompletion(task, outcome);
                synchronized (stateLock) {
                    if ("sent".equals(outcome) && valid(task.account, task.epoch) && task.remembered.compareAndSet(false, true)) {
                        try { generator.acknowledged(task.account, task.event, task.settings, task.text, task.images, task.input); }
                        catch (Exception failure) { safeLog("Could not retain acknowledged comment reply context"); }
                    }
                }
                if (task.finished.get() && ("sent".equals(outcome) || "failed".equals(outcome))) dispatcher.recordOutcome(entry(task, outcome));
                done.accept(outcome);
            }, () -> done.accept(task.submitted ? "submitted" : "cancelled"));
        });
    }

    private void submitLedger(Runnable action, Runnable rejected) {
        DouAiLog.Operation trace = DouAiLog.current();
        Runnable work = () -> {
            try { action.run(); }
            catch (RuntimeException failure) {
                diagnostic = "评论回复账本处理失败，保留发送占位，稍后检查";
                safeLog("Comment reply ledger task failed: " + failure.getClass().getSimpleName());
                rejected.run();
            }
        };
        try { ledgerWorker.execute(trace == null ? work : trace.wrap(work)); }
        catch (RejectedExecutionException failure) {
            diagnostic = "评论回复账本处理繁忙，保留发送占位，稍后检查";
            safeLog("Comment reply ledger worker unavailable; durable claims remain protected");
            rejected.run();
        }
    }

    private void persistCompletion(Task task, String status) {
        boolean defer = "deferred".equals(status) || !task.submitted && "submitted".equals(status)
                || "cancelled".equals(status) && task.deferCancellation;
        if (defer && task.persistedSubmission && !task.submitted) ledger.deferBeforeInvocation(task.account, task.event.incomingId, task.id);
        else if (defer) ledger.defer(task.account, task.event.incomingId, task.id);
        else ledger.finish(task.account, task.event.incomingId, "submitted".equals(status) ? "unknown" : status, task.id);
    }

    private boolean shouldDeferCancellation(Task task) {
        return task.boundaryFailed || !dispatcher.isEnabled(KIND) || !valid(task.account, task.epoch)
                || !dispatcher.isForeground(resumed.get());
    }

    private void fail(Task task, String reason) {
        safeLog("Comment reply generation failed: " + reason + "; stage=" + task.stage
                + "; ai_sent=" + (task.modelRequestSent ? 1 : 0) + "; generated=" + (task.generated ? 1 : 0)
                + "; submitted=" + (task.submitted ? 1 : 0) + "; chars=" + task.generatedChars);
        dispatcher.reportFinalFailure(entry(task, "failed"));
        complete(task, "failed");
    }

    /** Fixed reason codes and module symbols only; exception messages and causes may contain credentials. */
    static String generationFailureDiagnostic(Exception failure) {
        String detail = failure.getClass().getSimpleName() + "; reason=" + generationFailureReason(failure);
        if (failure instanceof ChatCompletionClient.ReplyFailure) {
            detail += "; http=" + ((ChatCompletionClient.ReplyFailure) failure).httpStatus();
        }
        if (failure instanceof SpeechPreparation.Failure) {
            detail += "; " + ((SpeechPreparation.Failure) failure).diagnostic()
                    .replace("stage=", "speech_stage=").replace("reason=", "speech_reason=")
                    .replace("error=", "speech_error=");
        }
        for (StackTraceElement frame : failure.getStackTrace()) {
            String owner = frame.getClassName(), method = frame.getMethodName();
            if (owner.startsWith("com.douai.module.") && owner.length() <= 180
                    && owner.matches("[A-Za-z0-9_.$]+") && method.length() <= 100
                    && method.matches("[A-Za-z0-9_$<>]+")) {
                detail += "; site=" + owner + "." + method + ":" + Math.max(-1, frame.getLineNumber());
                break;
            }
        }
        return detail;
    }

    private static String generationFailureReason(Exception failure) {
        if (failure instanceof ChatCompletionClient.ReplyFailure) {
            return "ai_" + ((ChatCompletionClient.ReplyFailure) failure).kind().name().toLowerCase(java.util.Locale.ROOT);
        }
        String message = failure.getMessage();
        if (message == null) return "unexpected";
        switch (message) {
            case "视频播放地址无法读取": return "video_source_unavailable";
            case "图文图片无法读取": return "work_image_unavailable";
            case "视频封面地址无法读取": return "work_cover_unavailable";
            case "对方评论附图没有可读取的地址": return "comment_image_missing";
            case "对方评论附图地址不受支持": return "comment_image_unsupported";
            case "图片地址不属于受支持的抖音媒体域名": return "image_host_unsupported";
            case "图片下载失败": return "image_download_failed";
            case "图片超过 16 MB": return "image_size_limit";
            case "Comment reply output is too long": return "output_limit";
            case "请先添加并启用模型配置":
            case "请先选择模型配置": return "model_unselected";
            default: return "unexpected";
        }
    }

    private void complete(Task task, String status) {
        synchronized (stateLock) {
            if (!task.finished.compareAndSet(false, true)) { task.trace.stage("completion_ignored", "duplicate_or_late=true;status=" + status); return; }
            if ("submitted".equals(status) && task.submitted) task.uncertain = true;
            // Capture why cancellation happened before a later resume can change the foreground state.
            if ("cancelled".equals(status) && shouldDeferCancellation(task)) task.deferCancellation = true;
            task.media.cancel();
            AutomationTrace.outcome(task.trace, status, task.timedOut ? "generation_timeout" : "comment_reply_" + status, "stage=" + task.stage + ";model_request_sent=" + task.modelRequestSent + ";generated=" + task.generated + ";submitted=" + task.submitted + ";durable_submission=" + task.persistedSubmission + ";acknowledged=" + "sent".equals(status));
        }
        dispatcher.discardReplyPreparation(KIND, task.id);
        submitLedger(() -> {
            // Keep this identity queued until its final durable transition; old callbacks carry its token.
            persistCompletion(task, status);
            if (!task.submitted) rateLimiter.release(task.reservation);
            tasks.remove(task.identity, task);
            if (task.audio != null && !task.submitted) task.audio.delete();
            if ("failed".equals(status) && task.submitted) dispatcher.reportFinalFailure(entry(task, "failed"));
            signal();
        }, () -> { tasks.remove(task.identity, task); signal(); });
    }

    private AutomationHistoryStore.Entry entry(Task task, String status) {
        long now = System.currentTimeMillis();
        return new AutomationHistoryStore.Entry(task.id, KIND, task.event.aid, task.event.senderName,
                task.input, task.text, task.model, status, task.created, now, task.requestImageCount, task.modelRequestSent,
                task.audio == null ? AutomationHistoryStore.DELIVERY_TEXT : AutomationHistoryStore.DELIVERY_VOICE);
    }

    static String input(CommentReplyEvent event) {
        return input(event, "");
    }
    static String input(CommentReplyEvent event, String mediaSource) {
        return "我的原评论：" + bounded(event.originalText, 1000)
                + "\n对方的回复：" + bounded(event.text, 2400)
                + (mediaSource == null || mediaSource.isEmpty() ? "" : "\n实际附带的视觉素材：" + bounded(mediaSource, 200));
    }
    private static String bounded(String value, int max) { return value == null ? "" : value.substring(0, Math.min(max, value.length())); }
    private void safeLog(String value) { try { log.accept(value); } catch (RuntimeException ignored) { } }

    private void invalidate() {
        synchronized (stateLock) {
            generation.incrementAndGet();
            for (Task task : tasks.values()) if (!task.submitted) task.media.cancel();
            generator.cancel();
            dispatcher.cancelKind(KIND);
            for (Task task : tasks.values()) if (!task.submitted) complete(task, "cancelled");
        }
    }

    @Override public void onActivityResumed(Activity activity) {
        if (closed) return;
        resumed = new WeakReference<>(activity);
        foreground = true;
        main.removeCallbacks(background);
        if (!foregroundSession) {
            foregroundSession = true;
            main.removeCallbacks(poll);
            if (enabled()) main.post(poll);
        }
    }
    @Override public void onActivityPaused(Activity activity) {
        if (resumed.get() != activity) return;
        resumed.clear();
        foreground = false;
        main.removeCallbacks(background);
        main.postDelayed(background, 600);
    }
    @Override public void onActivityDestroyed(Activity activity) { onActivityPaused(activity); }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        foreground = false;
        invalidate();
        main.removeCallbacksAndMessages(null);
        application.unregisterActivityLifecycleCallbacks(this);
        source.close();
        sender.close();
        generator.close();
        scanner.shutdownNow();
        worker.shutdownNow();
        // Final cancellation and publisher callbacks already queued above must be allowed to persist.
        ledgerWorker.shutdown();
    }

    private static final class Task {
        final DouAiLog.Operation trace;
        final long account, epoch, created = System.currentTimeMillis();
        final String identity, id = UUID.randomUUID().toString();
        final CommentReplyEvent event;
        final AtomicBoolean finished = new AtomicBoolean();
        final AtomicBoolean remembered = new AtomicBoolean();
        volatile String text = "", model = "";
        volatile boolean submitted, persistedSubmission, boundaryFailed, uncertain, deferCancellation;
        volatile AutomationRateLimiter.Reservation reservation;
        volatile SpeechPreparation.Audio audio;
        volatile LocalOptions settings;
        final MediaReadCancellation media = new MediaReadCancellation();
        volatile List<ChatImage> images = Collections.emptyList();
        volatile String input;
        volatile int requestImageCount = -1;
        volatile boolean modelRequestSent;
        volatile String stage = "queued";
        volatile boolean generated, timedOut;
        volatile int generatedChars;
        Task(long account, long epoch, String identity, CommentReplyEvent event) {
            this.account = account; this.epoch = epoch; this.identity = identity; this.event = event;
            trace = AutomationTrace.start("auto_comment_reply", id, "comment_inbox");
            trace.stage("queue_admitted", "target_ref=" + DouAiLog.reference(event.aid) + ";incoming_ref=" + DouAiLog.reference(event.incomingId));
            input = input(event);
        }
    }
    static final class SharedReplyGenerator implements Generator {
        private final ChatCompletionClient ai;
        private final java.util.Random random = new java.util.Random();
        SharedReplyGenerator() { this(null); }
        SharedReplyGenerator(Consumer<String> log) {
            ai = new ChatCompletionClient(null, value -> {
                if (log != null) log.accept("Comment reply AI " + value);
            });
        }
        @Override public Generated generate(long account, CommentReplyEvent event, LocalOptions options) throws Exception {
            return generate(account, event, options, Collections.emptyList(), input(event), ignored -> { });
        }
        @Override public Generated generate(long account, CommentReplyEvent event, LocalOptions options,
                                            List<ChatImage> images, String prompt, IntConsumer requestSent) throws Exception {
            if (!"ai".equals(options.text("reply_mode", "fixed"))) {
                return new Generated(ReplyPolicy.reply(event.text, options, random), "");
            }
            AiModelSettings models = AiModelSettings.readForAutomation(options, KIND);
            String model = models.selected().modelName;
            String text = ai.reply("comment_reply:" + account + ':' + event.aid + ':' + event.rootId, prompt,
                    images, models, options.text("reply_prompt", "自然简洁地回复对方，只输出回复正文。"), false, "", requestSent);
            return new Generated(text, model);
        }
        @Override public void acknowledged(long account, CommentReplyEvent event, LocalOptions options, String text) throws Exception {
            acknowledged(account, event, options, text, Collections.emptyList(), input(event));
        }
        @Override public void acknowledged(long account, CommentReplyEvent event, LocalOptions options, String text,
                                            List<ChatImage> images, String prompt) throws Exception {
            if (options == null || !"ai".equals(options.text("reply_mode", "fixed"))) return;
            ai.rememberReply("comment_reply:" + account + ':' + event.aid + ':' + event.rootId, prompt,
                    images, AiModelSettings.readForAutomation(options, KIND),
                    options.text("reply_prompt", "自然简洁地回复对方，只输出回复正文。"), text);
        }
        @Override public void cancel() { ai.cancelCurrentRequest(); ai.clearHistory(); }
    }
}
