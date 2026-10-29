package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SubmissionLedger;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Queues original works and publishes through the host's permission checks. */
public final class AutoCommentHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    // Callback persistence is shared by hook generations and never blocks the host's main thread.
    private static final java.util.concurrent.ThreadPoolExecutor RESULTS_IO = new java.util.concurrent.ThreadPoolExecutor(
            1, 1, 0L, java.util.concurrent.TimeUnit.MILLISECONDS, new java.util.concurrent.ArrayBlockingQueue<>(64), action -> {
                Thread thread = new Thread(action, "DouAi-comment-ledger");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final Application application;
    private final ClassLoader loader;
    private final AutomationDispatcher dispatcher;
    private final Consumer<String> log;
    private final Supplier<MediaReadCancellation> mediaReads;
    private final Handler main = new Handler(Looper.getMainLooper());
    private static final long GENERATION_TIMEOUT_MILLIS = 120_000;
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final ChatCompletionClient ai = new ChatCompletionClient(null,
            message -> safeLog("Automatic comment AI " + message));
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Map<String, String> inFlight = new java.util.HashMap<>();
    private volatile GenerationAttempt activeGeneration;
    private final AtomicLong generation = new AtomicLong();
    private final AutoCommentQueue<Task> queue;
    private final NativeCommentPublisher publisher;
    private final AutomationRateLimiter rateLimiter;
    private final SubmissionLedger submissions;
    private final AutoCloseable followListUpdates;
    private final Field holderRoot;
    private final Method holderAweme;
    private final Field imageRoot;
    private final Method imageAweme;
    private volatile LocalOptions options;
    private SpeechPreparation.Preparer speechPreparer = SpeechPreparation::prepareComment;
    private volatile boolean closed;
    private volatile WeakReference<Object> holder = new WeakReference<>(null);
    private volatile WeakReference<View> root = new WeakReference<>(null);

    private AutoCommentHook(Context context, ClassLoader loader, LocalOptions options,
                            AutomationDispatcher dispatcher, Field holderRoot, Method holderAweme,
                            Field imageRoot, Method imageAweme, NativeCommentPublisher publisher, Consumer<String> log,
                            Supplier<MediaReadCancellation> mediaReads) {
        this.context = context.getApplicationContext();
        application = (Application) this.context;
        this.loader = loader;
        this.options = options;
        this.dispatcher = dispatcher;
        this.holderRoot = holderRoot;
        this.holderAweme = holderAweme;
        this.imageRoot = imageRoot;
        this.imageAweme = imageAweme;
        this.publisher = publisher;
        rateLimiter = new AutomationRateLimiter(this.context);
        submissions = new SubmissionLedger(this.context.getSharedPreferences(
                SubmissionLedger.COMMENT_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY);
        this.log = log;
        this.mediaReads = mediaReads;
        queue = new AutoCommentQueue<>(new AutoCommentQueue.Scheduler() {
            @Override public void post(Runnable action) {
                if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
            }
            @Override public void later(Runnable action, long delayMillis) { main.postDelayed(action, delayMillis); }
        }, this::attempt, this::finished, this::queueChanged, this::retry);
        worker.setRemoveOnCancelPolicy(true);
        application.registerActivityLifecycleCallbacks(this);
        followListUpdates = NativeFollowLists.listen(() -> {
            Object selected = holder.get();
            if (enabled() && selected != null) selected(selected);
        });
    }

    public static AutoCommentHook install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, Context context, LocalOptions options,
                                          AutomationDispatcher dispatcher, Consumer<String> log) throws Exception {
        return install(framework, report, loader, context, options, dispatcher, log, MediaReadCancellation::new);
    }

    static AutoCommentHook install(XposedInterface framework, HostScanReport report, ClassLoader loader,
                                    Context context, LocalOptions options, AutomationDispatcher dispatcher,
                                    Consumer<String> log, Supplier<MediaReadCancellation> mediaReads) throws Exception {
        Consumer<String> logger = log == null ? ignored -> { } : log;
        Method selected = HostTargets.method(report, "feed_holder_selected", loader);
        Method imageSelected = HostTargets.method(report, "image_holder_page_selected", loader);
        Field root = HostTargets.field(selected.getDeclaringClass(), "z");
        Field photoRoot = HostTargets.field(imageSelected.getDeclaringClass(), "f");
        Method aweme = selected.getDeclaringClass().getMethod("getAweme");
        Method photoAweme = imageSelected.getDeclaringClass().getMethod("getAweme");
        if (!View.class.isAssignableFrom(root.getType()) || !View.class.isAssignableFrom(photoRoot.getType())
                || aweme.getReturnType() != photoAweme.getReturnType()) throw new IllegalStateException("Comment holder contract changed");
        NativeCommentPublisher publisher = NativeCommentPublisher.install(framework, report, loader, logger);
        AutoCommentHook hook = new AutoCommentHook(context, loader, options, dispatcher, root, aweme,
                photoRoot, photoAweme, publisher, logger, mediaReads);
        try {
            for (Method target : new Method[]{selected, imageSelected}) {
                XposedInterface.HookHandle handle = framework.hook(target).setId("DouAi.AutoComment." + target.getName())
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object result = chain.proceed();
                            Object owner = chain.getThisObject();
                            if (hook.enabled()) hook.main.post(() -> hook.selected(owner));
                            else hook.rememberSelection(owner);
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Empty automatic comment hook");
                hook.handles.add(handle);
            }
            if (report.getResults().containsKey("component_player_playing")) {
                Method playing = HostTargets.method(report, "component_player_playing", loader);
                XposedInterface.HookHandle handle = framework.hook(playing).setId("DouAi.AutoComment.Playing")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object result = chain.proceed();
                            if (!hook.enabled()) return result;
                            Object argument = chain.getArg(0);
                            hook.main.post(() -> {
                                Object current = hook.holder.get();
                                if (current == null || !(argument instanceof String)) return;
                                try {
                                    boolean video = hook.holderAweme.getDeclaringClass().isInstance(current);
                                    Object work = (video ? hook.holderAweme : hook.imageAweme).invoke(current);
                                    if (argument.equals(HostTargets.invoke(work, "getAid"))) hook.selected(current);
                                } catch (ReflectiveOperationException ignored) { }
                            });
                            return result;
                        });
                if (handle == null) throw new IllegalStateException("Empty comment playback hook");
                hook.handles.add(handle);
            }
            return hook;
        } catch (Exception failure) { hook.close(); throw failure; }
    }

    public void setOptions(LocalOptions next) {
        LocalOptions before = options;
        options = next;
        boolean changed = !AiModelSettings.sameAutomationSelection(before, next, AutomationHistoryStore.COMMENT);
        for (String key : new String[]{"native_auto_comment", "comment_mode", "comment_prompt",
                "comment_delay_seconds", "comment_daily_limit", "comment_user_cooldown_minutes",
                "auto_comment_text", AutomationCommentMedia.RECOGNIZE_IMAGES,
                AutomationCommentMedia.RECOGNIZE_VIDEO, "comment_scope_mode", "comment_scope_relations",
                "comment_scope_fields", "comment_scope_keywords", "comment_scope_users", "comment_filter_mode",
                "comment_filter_keywords", "comment_filter_fields", AiModelSettings.COMMENT_MODEL_ID,
                LocalOptions.COMMENT_VOICE, "tts_"}) {
            boolean defaultEnabled = AutomationCommentMedia.RECOGNIZE_IMAGES.equals(key);
            if (("tts_".equals(key) && !before.samePrefix(next, "tts_"))
                    || before.enabled(key, defaultEnabled) != next.enabled(key, defaultEnabled)
                    || !before.text(key, "").equals(next.text(key, ""))
                    || "comment_delay_seconds".equals(key) && before.integer(key, 3) != next.integer(key, 3)
                    || ("comment_daily_limit".equals(key) || "comment_user_cooldown_minutes".equals(key))
                    && before.integer(key, 0) != next.integer(key, 0)) {
                changed = true;
                break;
            }
        }
        if (!changed) return;
        generation.incrementAndGet();
        GenerationAttempt active = activeGeneration;
        if (active != null) markCancelled(active.task);
        ai.cancelCurrentRequest();
        worker.getQueue().clear();
        queue.reset();
        dispatcher.cancelKind(AutomationHistoryStore.COMMENT);
        main.post(() -> { Object selected = holder.get(); if (!closed && selected != null) selected(selected); });
        safeLog("Automatic comment configuration changed; queue reset");
    }

    private boolean enabled() { return !closed && options.enabled("native_auto_comment"); }
    public boolean ownsPublish(Object model) { return publisher.ownsPublish(model); }

    /** Keep hot enable and incoming-message relation context without scheduling comment work. */
    private void rememberSelection(Object selected) {
        if (closed) return;
        try {
            boolean video = holderAweme.getDeclaringClass().isInstance(selected);
            Object work = (video ? holderAweme : imageAweme).invoke(selected);
            AutomationScopePolicy.rememberUser(modelValue(work, "author", "getAuthor"));
            holder = new WeakReference<>(selected);
            Object view = (video ? holderRoot : imageRoot).get(selected);
            root = new WeakReference<>(view instanceof View ? (View) view : null);
        } catch (ReflectiveOperationException | RuntimeException ignored) { }
    }

    private void selected(Object selected) {
        if (closed) return;
        if (!enabled()) { rememberSelection(selected); return; }
        DouAiLog.Operation selectionTrace = null;
        try {
            boolean video = holderAweme.getDeclaringClass().isInstance(selected);
            Object work = (video ? holderAweme : imageAweme).invoke(selected);
            Object author = modelValue(work, "author", "getAuthor");
            AutomationScopePolicy.rememberUser(author);
            String aid = string(HostTargets.invoke(work, "getAid"));
            holder = new WeakReference<>(selected);
            Object view = (video ? holderRoot : imageRoot).get(selected);
            View selectedRoot = view instanceof View ? (View) view : null;
            root = new WeakReference<>(selectedRoot);
            if (!enabled()) { DouAiLog.event("auto_comment", "eligibility", "disabled_or_closed", "enabled=" + options.enabled("native_auto_comment")); return; }
            long account = ownAccount();
            if (!NativeFollowLists.ensureAccount(Long.toString(account))) { DouAiLog.event("auto_comment", "eligibility", "account_not_ready", "account_present=" + (account > 0)); return; }
            Activity activity = activity(selectedRoot);
            String rejected = aid.isEmpty() ? "target_missing" : account <= 0 ? "account_unavailable"
                    : selectedRoot == null ? "view_missing" : !selectedRoot.isAttachedToWindow() ? "view_detached"
                    : !selectedRoot.isShown() ? "view_hidden" : activity == null ? "activity_missing"
                    : !dispatcher.isForeground(activity) ? "activity_background"
                    : Boolean.TRUE.equals(HostTargets.invoke(work, "getIsAdAweme")) ? "advertisement" : "";
            if (!rejected.isEmpty()) {
                DouAiLog.event("auto_comment", "eligibility", rejected, "target_present=" + !aid.isEmpty() + ";account_present=" + (account > 0) + ";view_present=" + (selectedRoot != null) + ";activity_present=" + (activity != null));
                safeLog("Automatic comment selection skipped: target/account/foreground unavailable"); return;
            }
            String identity = "comment:" + account + ':' + aid;
            if (context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).contains(identity)
                    || submissions.contains(SubmissionLedger.identity(identity))) { DouAiLog.event("auto_comment", "eligibility", "previous_submission", "target_ref=" + DouAiLog.reference(aid)); return; }
            Task task = new Task(generation.get(), account, aid, identity, work, workInput(work),
                    workAuthor(work), options);
            selectionTrace = task.trace;
            String authorId = string(modelValue(author, "uid", "getUid"));
            if (!rateLimiter.canSend(AutomationHistoryStore.COMMENT, account, authorId, options)) { task.trace.finish(DouAiLog.Outcome.SKIPPED, "allowance_or_cooldown", "daily_limit=" + options.integer("comment_daily_limit", 0) + ";cooldown_minutes=" + options.integer("comment_user_cooldown_minutes", 0)); return; }
            if (!ContentScopePolicy.allows("comment", options, authorId, task.name,
                    workDescription(work), workTitle(work), AutomationScopePolicy.fromUser(author))) {
                task.trace.finish(DouAiLog.Outcome.SKIPPED, "scope_rejected", "policy=ContentScopePolicy;target_ref=" + DouAiLog.reference(aid));
                safeLog("Automatic comment skipped by author scope");
                return;
            }
            task.trace.stage("queue_admitted", "target_ref=" + DouAiLog.reference(aid) + ";delay_seconds=" + options.integer("comment_delay_seconds", 3));
            task.trace.wrap(() -> queue.offer(identity, task, Math.max(2, Math.min(3600, options.integer("comment_delay_seconds", 3))) * 1000L)).run();
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (selectionTrace != null) { selectionTrace.error("selection", failure); selectionTrace.finish(DouAiLog.Outcome.FAILED, "selection_failed", "queue_admitted=false"); }
            else DouAiLog.failure("auto_comment", "selection", failure);
            safeLog("Automatic comment selection unavailable: " + failure.getClass().getSimpleName());
        }
    }

    private boolean valid(Task task) {
        long account = ownAccount();
        boolean accountCurrent = NativeFollowLists.ensureAccount(Long.toString(account));
        Object author = modelValue(task.work, "author", "getAuthor");
        String rejected = !accountCurrent ? "relationship_account_changed" : !enabled() ? "disabled_or_closed"
                : task.cancelled ? "task_cancelled" : task.epoch != generation.get() ? "configuration_changed"
                : task.account != account ? "account_changed"
                : !task.aid.equals(HostTargets.invoke(task.work, "getAid")) ? "work_changed"
                : !rateLimiter.canSend(AutomationHistoryStore.COMMENT, task.account, task.authorId, options) ? "allowance_or_cooldown"
                : !ContentScopePolicy.allows("comment", options,
                string(modelValue(author, "uid", "getUid")), workAuthor(task.work),
                workDescription(task.work), workTitle(task.work), AutomationScopePolicy.fromUser(author)) ? "scope_rejected" : "";
        if (!rejected.isEmpty()) {
            task.lastGateReason = rejected;
            task.trace.wrap(() -> DouAiLog.event("auto_comment", "eligibility_rejected", rejected, "attempt=" + task.attempt)).run();
        }
        return rejected.isEmpty();
    }

    private void attempt(Task task, int attempt, Consumer<String> result) {
        task.trace.attempt(attempt);
        task.trace.stage("eligibility_recheck", "epoch_current=" + (task.epoch == generation.get()) + ";cancelled=" + task.cancelled);
        if (!valid(task)) { result.accept("cancelled"); return; }
        if (task.queueMillis < 0) task.queueMillis = Math.max(0, android.os.SystemClock.elapsedRealtime() - task.createdElapsed);
        task.reason = AutomationHistoryStore.Reason.NONE;
        task.attempt = attempt;
        int modeAttempt = task.textFallback ? attempt - task.fallbackAfterAttempt
                : voice(task) && task.text != null ? task.voiceFailures + 1 : attempt;
        safeLog("Automatic comment attempt=" + modeAttempt + "/3 media=" + deliveryMode(task)
                + " stage=" + (task.approved ? "publish" : "generate"));
        if (task.approved) { prepare(task, result); return; }
        dispatcher.commentStage(task.id, "generating");
        GenerationAttempt operation = new GenerationAttempt(task, modeAttempt, result);
        task.generationAttempt = operation;
        activeGeneration = operation;
        main.postDelayed(operation.timeout, GENERATION_TIMEOUT_MILLIS);
        worker.execute(operation.future);
    }

    private boolean retry(Task task, int attempts) {
        if (task.textFallback) return attempts - task.fallbackAfterAttempt < 3;
        if (voice(task) && task.voiceAttempt == attempts) task.voiceFailures++;
        if (task.voiceFailures == 0) return attempts < 3;
        if (task.voiceFailures < 3) return valid(task);
        if (task.text == null || task.text.trim().isEmpty() || !valid(task)) return false;
        task.textFallback = true;
        task.fallbackAfterAttempt = attempts;
        if (task.audio != null) { task.audio.delete(); task.audio = null; }
        task.trace.stage("voice_fallback", "voice_failures=" + task.voiceFailures + ";selected_fallback=text");
        safeLog("Automatic comment voice attempts exhausted; retrying the same comment as text");
        return true;
    }

    private static boolean voice(Task task) {
        return task.options.enabled(LocalOptions.COMMENT_VOICE) && !task.textFallback;
    }

    private static String deliveryMode(Task task) {
        return voice(task) ? AutomationHistoryStore.DELIVERY_VOICE : AutomationHistoryStore.DELIVERY_TEXT;
    }

    private void generate(Task task, int attempt, Consumer<String> result, GenerationAttempt operation) {
            String stage = "work_content";
            long generationStarted = android.os.SystemClock.elapsedRealtime();
            long previousGenerationMillis = Math.max(0, task.generationMillis);
            int characters = task.text == null ? 0 : task.text.length();
            try {
                operation.check();
                if (!valid(task)) { result.accept("cancelled"); return; }
                String text = task.text;
                if (text == null) {
                    if ("ai".equals(task.options.text("comment_mode", "ai"))) {
                        AiModelSettings models = AiModelSettings.readForAutomation(task.options, AutomationHistoryStore.COMMENT);
                        task.model = models.selected().modelName;
                        task.stage = AutomationHistoryStore.Stage.MEDIA;
                        task.trace.stage("media", "recognize_images=" + task.options.enabled(AutomationCommentMedia.RECOGNIZE_IMAGES, true) + ";recognize_video=" + task.options.enabled(AutomationCommentMedia.RECOGNIZE_VIDEO));
                        long mediaStarted = android.os.SystemClock.elapsedRealtime();
                        List<ChatImage> images = AutomationCommentMedia.load(context, task.work, task.options,
                                operation.mediaCancellation);
                        task.mediaMillis = Math.max(0, android.os.SystemClock.elapsedRealtime() - mediaStarted);
                        task.trace.stage("media_ready", "images=" + images.size() + ";duration_ms=" + task.mediaMillis);
                        if (!valid(task)) { result.accept("cancelled"); return; }
                        if (!hasWorkContent(task.work) && images.isEmpty()) {
                            safeLog("Automatic comment skipped: captured work has no usable content");
                            task.reason = AutomationHistoryStore.Reason.INVALID_RESPONSE;
                            result.accept("cancelled");
                            return;
                        }
                        safeLog("Automatic comment model request started; images=" + images.size());
                        // Comments are independent tasks, even if the model has conversation rounds configured.
                        ai.clearHistory();
                        operation.check();
                        stage = "model_request";
                        task.stage = AutomationHistoryStore.Stage.GENERATION;
                        task.trace.stage("model_request", "images=" + images.size() + ";model_ref=" + DouAiLog.reference(task.model));
                        String output = ai.reply(task.identity, task.input, images, models,
                                CommentOutputPolicy.task(task.options.text("comment_prompt",
                                        "根据当前作品自然地写一条评论。")), false, "", imageCount -> {
                                    task.requestImageCount = imageCount;
                                    task.modelRequestSent = true;
                                    task.trace.stage("model_request_sent", "images=" + imageCount + ";business_success=false");
                                    safeLog("Automatic comment model request sent; images=" + imageCount + "; work=" + task.aid);
                                    dispatcher.commentRequestSent(task.id, imageCount);
                                });
                        operation.check();
                        stage = "model_output";
                        task.trace.stage("model_output", "protocol_reply_received=true;business_success=false");
                        characters = output == null ? 0 : output.length();
                        String comment = CommentOutputPolicy.parse(output, task.options.enabled(LocalOptions.COMMENT_VOICE));
                        if (comment.isEmpty()) {
                            safeLog("Automatic comment skipped: model returned an empty comment");
                            task.reason = AutomationHistoryStore.Reason.INVALID_RESPONSE;
                            result.accept("cancelled");
                            return;
                        }
                        text = comment;
                    } else text = task.options.text("auto_comment_text", "").trim();
                }
                if (!valid(task)) { result.accept("cancelled"); return; }
                stage = "comment_text";
                characters = text.length();
                if (text.isEmpty() || !voice(task) && !task.textFallback && text.length() > 1000) {
                    throw new IllegalArgumentException("Comment text is empty or too long");
                }
                if (voice(task) && task.audio == null) {
                    task.text = text;
                    task.voiceAttempt = task.attempt;
                    dispatcher.commentStage(task.id, "generating");
                    stage = "speech";
                    task.trace.stage("speech", "chars=" + text.length() + ";cached_audio=false");
                    task.stage = AutomationHistoryStore.Stage.SPEECH;
                    SpeechPreparation.Audio prepared = speechPreparer.prepare(context, text, task.options, Long.MAX_VALUE,
                            () -> !operation.cancelled && valid(task));
                    synchronized (operation) {
                        if (operation.cancelled || !valid(task)) {
                            prepared.delete();
                            throw new java.util.concurrent.CancellationException("评论语音已取消");
                        }
                        task.audio = prepared;
                    }
                }
                synchronized (operation) {
                    operation.check();
                    task.text = text;
                    operation.generated = true;
                    main.removeCallbacks(operation.timeout);
                    safeLog("Automatic comment generation completed; chars=" + characters
                            + "; audioMillis=" + (task.audio == null ? 0 : task.audio.duration));
                    task.generationMillis = previousGenerationMillis + Math.max(0, android.os.SystemClock.elapsedRealtime() - generationStarted);
                    prepare(task, result);
                }
            } catch (Exception failure) {
                if (task.reason != AutomationHistoryStore.Reason.GENERATION_TIMEOUT) {
                    task.reason = failure instanceof SpeechPreparation.Failure ? AutomationHistoryStore.Reason.SPEECH_FAILED
                            : task.stage == AutomationHistoryStore.Stage.MEDIA ? AutomationHistoryStore.Reason.MEDIA_FAILED
                            : AutomationHistoryStore.Reason.GENERATION_FAILED;
                }
                String http = "";
                java.util.regex.Matcher status = java.util.regex.Pattern.compile("HTTP ([0-9]{3})").matcher(String.valueOf(failure.getMessage()));
                if (status.find()) http = "; HTTP=" + status.group(1);
                if ("; HTTP=401".equals(http) || "; HTTP=403".equals(http)) task.reason = AutomationHistoryStore.Reason.AI_AUTH_FAILED;
                else if ("; HTTP=429".equals(http)) task.reason = AutomationHistoryStore.Reason.AI_RATE_LIMITED;
                else if (!http.isEmpty()) task.reason = AutomationHistoryStore.Reason.AI_HTTP_ERROR;
                task.trace.error(stage, failure);
                String detail = failure instanceof SpeechPreparation.Failure
                        ? ((SpeechPreparation.Failure) failure).diagnostic() : "stage=" + stage;
                safeLog("Automatic comment generation failed: " + failure.getClass().getSimpleName() + http
                        + "; " + detail + "; chars=" + characters + "; attempt=" + attempt + "/3");
                result.accept(valid(task) ? "failed" : "cancelled");
            } finally {
                task.generationMillis = previousGenerationMillis + Math.max(0, android.os.SystemClock.elapsedRealtime() - generationStarted);
                main.removeCallbacks(operation.timeout);
                operation.release();
            }
    }

    private void prepare(Task task, Consumer<String> result) {
        task.trace.stage("preview", "approved=" + task.approved + ";delivery=" + deliveryMode(task));
        task.trace.wrap(() -> dispatcher.prepareComment(entry(task, "pending"), task.approved, () -> valid(task),
                done -> { task.approved = true; task.trace.wrap(() -> publish(task, done)).run(); }, task.trace.wrapConsumer(result))).run();
    }

    private void queueChanged(Task current, int count, int attempts, String phase) {
        int modeAttempts = current == null ? attempts : current.textFallback
                ? Math.max(0, attempts - current.fallbackAfterAttempt)
                : voice(current) ? current.voiceFailures + ("running".equals(phase) ? 1 : 0) : attempts;
        int retries = Math.max(0, 3 - Math.max(1, modeAttempts + ("retrying".equals(phase) ? 1 : 0)));
        dispatcher.trackComment(current == null ? null : entry(current, "pending"), count, retries, phase,
                () -> current != null && valid(current),
                () -> cancelTask(current), this::cancelLatestTask);
    }

    private void cancelTask(Task task) {
        if (task == null || task.terminal) return;
        markCancelled(task);
        queue.cancel(task);
    }

    private void cancelLatestTask() {
        queue.cancelLatest(this::markCancelled);
    }

    private void markCancelled(Task task) {
        if (task == null || task.terminal) return;
        if (task.reason == AutomationHistoryStore.Reason.NONE) task.reason = AutomationHistoryStore.Reason.CANCELLED;
        task.cancelled = true;
        GenerationAttempt operation = task.generationAttempt;
        if (operation != null) operation.cancel();
    }

    private void publish(Task task, Consumer<String> result) {
        if (voice(task)) task.voiceAttempt = task.attempt;
        dispatcher.commentStage(task.id, "publishing");
        task.stage = AutomationHistoryStore.Stage.SEND;
        task.trace.stage("submission_barrier", "delivery=" + deliveryMode(task));
        AutomationRateLimiter.Reservation[] reservation = new AutomationRateLimiter.Reservation[1];
        if (voice(task) && task.audio == null || !voice(task) && (task.text == null
                || task.text.trim().isEmpty() || !task.textFallback && task.text.length() > 1000)) {
            result.accept("failed");
            return;
        }
        // Dispatcher calls actions on its worker. Persist before posting the native invocation to main.
        String identity = SubmissionLedger.identity(task.identity);
        String attempt = java.util.UUID.randomUUID().toString();
        if (!valid(task)) { result.accept("cancelled"); return; }
        SubmissionLedger.ReserveResult reserved = submissions.reserve(identity, attempt, System.currentTimeMillis(),
                SubmissionLedger.accountScope(Long.toString(task.account)));
        if (reserved != SubmissionLedger.ReserveResult.RESERVED) {
            task.reason = reserved == SubmissionLedger.ReserveResult.FULL ? AutomationHistoryStore.Reason.LEDGER_FULL
                    : reserved == SubmissionLedger.ReserveResult.DUPLICATE ? AutomationHistoryStore.Reason.DUPLICATE
                    : AutomationHistoryStore.Reason.PERSISTENCE_FAILED;
            task.trace.stage("submission_rejected", "ledger_result=" + reserved.name());
            safeLog("Automatic comment reservation rejected: " + reserved.name());
            result.accept("cancelled");
            return;
        }
        task.trace.stage("submission_reserved", "durable=true;invoked=false");
        task.uncertain = false;
        task.sendStarted = android.os.SystemClock.elapsedRealtime();
        long previousSendMillis = Math.max(0, task.sendMillis);
        task.queueMillis = Math.max(0, task.sendStarted - task.createdElapsed - Math.max(0, task.generationMillis) - previousSendMillis);
        java.util.concurrent.atomic.AtomicBoolean invoked = new java.util.concurrent.atomic.AtomicBoolean();
        publisher.send(task.work, task.aid, task.text, task.audio, () -> valid(task), () -> {
            if (!submissions.isReserved(identity, attempt)) return false;
            reservation[0] = rateLimiter.tryReserve(AutomationHistoryStore.COMMENT, task.account, task.authorId, options);
            if (reservation[0] == null) {
                task.reason = AutomationHistoryStore.Reason.ALLOWANCE_EXHAUSTED;
                return false;
            }
            invoked.set(true);
            task.nativeBoundaryPassed = true;
            task.trace.stage("native_invoking", "reservation=true;business_success=false");
            return true;
        }, () -> {
            task.trace.stage("native_accepted", "business_success=false;awaiting_callback=true");
            inFlight.put(task.identity, task.id);
            rememberSubmission(task);
            main.postDelayed(() -> inFlight.remove(task.identity, task.id), 120_000);
            SpeechPreparation.Audio submittedAudio = task.audio;
            if (submittedAudio != null) main.postDelayed(() -> {
                if (task.audio == submittedAudio) {
                    submittedAudio.delete();
                    task.audio = null;
                }
            }, 600_000);
        }, status -> {
            try {
                RESULTS_IO.execute(() -> {
                    task.trace.stage("native_callback", "status=" + status + ";invoked=" + invoked.get() + ";uncertain=" + task.uncertain);
                    String outcome = status;
                    task.sendMillis = previousSendMillis + Math.max(0, android.os.SystemClock.elapsedRealtime() - task.sendStarted);
                    if ("cancelled".equals(status) && !invoked.get()) submissions.abandon(identity, attempt);
                    else if ("sent".equals(status)) {
                        boolean persisted = submissions.complete(identity, attempt, "sent", System.currentTimeMillis());
                        task.trace.stage("submission_ledger_result", "native_ack=true;completion_persisted=" + persisted);
                    }
                    else if ("failed".equals(status) && !task.uncertain
                            && submissions.complete(identity, attempt, "failed", System.currentTimeMillis())) {
                        // Only a definite failure with a durably released reservation can retry.
                    } else {
                        submissions.complete(identity, attempt, "unknown", System.currentTimeMillis());
                        outcome = "submitted";
                    }
                    String durableOutcome = outcome;
                    main.post(() -> publishResult(task, durableOutcome, reservation[0], result));
                });
            } catch (java.util.concurrent.RejectedExecutionException full) {
                task.reason = AutomationHistoryStore.Reason.PERSISTENCE_FAILED;
                // The pre-send durable reservation remains. Never infer rejection from an IO backlog.
                main.post(() -> publishResult(task, invoked.get() ? "submitted" : "cancelled", reservation[0], result));
            }
        });
    }

    private void publishResult(Task task, String status, AutomationRateLimiter.Reservation reservation,
                               Consumer<String> result) {
        task.trace.stage(task.terminal ? "late_publish_result" : "publish_result", "status=" + status + ";reason=" + task.reason);
        if ("submitted".equals(status)) {
            task.uncertain = true;
            task.reason = AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED;
        } else if ("failed".equals(status)) { task.reason = AutomationHistoryStore.Reason.SEND_FAILED; if (!task.uncertain) task.nativeBoundaryPassed = false; }
        else if ("sent".equals(status)) { task.reason = AutomationHistoryStore.Reason.NONE; task.stage = AutomationHistoryStore.Stage.COMPLETE; }
        if ("failed".equals(status) || "cancelled".equals(status)) rateLimiter.release(reservation);
        if ("failed".equals(status) && inFlight.remove(task.identity, task.id)) {
            context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).edit().remove(task.identity).apply();
        } else if ("sent".equals(status)) inFlight.remove(task.identity, task.id);
        if (task.audio != null && ("sent".equals(status) || "cancelled".equals(status)
                || task.terminal && "failed".equals(status))) {
            task.audio.delete();
            task.audio = null;
        }
        if (task.terminal && ("sent".equals(status) || "failed".equals(status))) dispatcher.recordOutcome(entry(task, status));
        result.accept(status);
    }

    private void finished(Task task, String status, int attempts) {
        if ("cancelled".equals(status)) markCancelled(task);
        task.terminal = true;
        if ("cancelled".equals(status) && task.id.equals(inFlight.get(task.identity))) status = "submitted";
        if ("cancelled".equals(status)) task.cancelled = true;
        if ("submitted".equals(status)) {
            task.uncertain = true;
            task.reason = AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED;
        }
        if (task.audio != null && !"submitted".equals(status)) { task.audio.delete(); task.audio = null; }
        String loggedStatus = "cancelled".equals(status) && task.nativeBoundaryPassed ? "submitted" : status;
        AutomationTrace.outcome(task.trace, loggedStatus, task.reason.name().toLowerCase(java.util.Locale.ROOT), "business_status=" + status + ";native_boundary_passed=" + task.nativeBoundaryPassed + ";last_gate=" + task.lastGateReason + ";queue_terminal=true;attempts=" + attempts + ";native_evidence=" + ("sent".equals(status) ? "owned_native_success_callback;ledger_completion_attempted=true" : "submitted".equals(status) ? "no_definite_acknowledgment" : "local_state_or_definite_failure") + ";queue_ms=" + task.queueMillis + ";generation_ms=" + task.generationMillis + ";send_ms=" + task.sendMillis);
        safeLog("Automatic comment queue outcome=" + status + "; attempts=" + attempts);
        if ("sent".equals(status) || "submitted".equals(status)) rememberSubmission(task);
        AutomationHistoryStore.Entry entry = entry(task, status);
        if ("failed".equals(status)) dispatcher.reportFinalFailure(entry);
        else dispatcher.recordOutcome(entry);
    }

    private void rememberSubmission(Task task) {
            android.content.SharedPreferences records = context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor saved = records.edit();
            if (records.getAll().size() >= 1000 && !records.contains(task.identity)) {
                String oldest = records.getAll().entrySet().stream()
                        .min(java.util.Comparator.comparingLong(entry -> entry.getValue() instanceof Long ? (Long) entry.getValue() : 0L))
                        .map(Map.Entry::getKey).orElse(null);
                if (oldest != null) saved.remove(oldest);
            }
            saved.putLong(task.identity, System.currentTimeMillis()).apply();
    }

    private static AutomationHistoryStore.Entry entry(Task task, String status) {
        return new AutomationHistoryStore.Entry(task.id, AutomationHistoryStore.COMMENT, task.aid, task.name,
                task.input, task.text == null ? "" : task.text, task.model, status, task.createdAt,
                System.currentTimeMillis(), task.requestImageCount, task.modelRequestSent, deliveryMode(task))
                .withDiagnostics(task.reason, task.stage,
                        task.queueMillis, task.mediaMillis, task.generationMillis, task.sendMillis);
    }

    static String workInput(Object work) {
        String description = workDescription(work);
        String title = workTitle(work);
        if (title.trim().isEmpty()) title = description;
        return "作品作者：" + limited(workAuthor(work), 300)
                + "\n作品标题：" + limited(title, 1000)
                + "\n作品文案：" + limited(description, 2100);
    }

    private static boolean hasWorkContent(Object work) {
        return !workTitle(work).trim().isEmpty() || !workDescription(work).trim().isEmpty();
    }

    private static String workDescription(Object work) {
        return string(modelValue(work, "desc", "getDesc"));
    }

    private static String workTitle(Object work) {
        for (String[] property : new String[][]{{"title", "getTitle"},
                {"item_title", "getItemTitle"}, {"preview_title", "getPreviewTitle"}}) {
            String title = string(modelValue(work, property[0], property[1]));
            if (!title.trim().isEmpty()) return title;
        }
        return "";
    }

    private static String workAuthor(Object work) {
        Object author = modelValue(work, "author", "getAuthor");
        return string(modelValue(author, "nickname", "getNickname"));
    }

    private static Object modelValue(Object model, String field, String getter) {
        Object value = DownloadActionHook.jsonValue(model, field);
        return value == null ? HostTargets.invoke(model, getter) : value;
    }

    private static String limited(String value, int maximum) {
        int end = Math.min(maximum, value.length());
        if (end > 0 && end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
        return value.substring(0, end);
    }

    static List<String> workImages(Object work, LocalOptions options) {
        List<String> urls = new ArrayList<>();
        Object images = DownloadActionHook.jsonValue(work, "images");
        if (!(images instanceof Iterable<?>)) return urls;
        for (Object image : (Iterable<?>) images) {
            String source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "url_list"));
            if (source == null) source = DownloadActionHook.firstUrlValue(DownloadActionHook.jsonValue(image, "download_url_list"));
            if (source == null || !AutomationImages.isMediaUrl(source)) throw new IllegalArgumentException("图文图片无法读取");
            urls.add(source);
            if (urls.size() == ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) break;
        }
        return urls;
    }

    private long ownAccount() {
        try {
            Object service = Class.forName("com.ss.android.ugc.aweme.account.AccountProxyService", false, loader)
                    .getMethod("userService").invoke(null);
            if (!Boolean.TRUE.equals(HostTargets.invoke(service, "isLogin"))) return 0;
            return Long.parseLong(string(HostTargets.invoke(service, "getCurUserId")));
        } catch (ReflectiveOperationException | RuntimeException ignored) { return 0; }
    }

    private static Activity activity(View root) {
        Context context = root == null ? null : root.getContext();
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return null;
    }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private void safeLog(String message) { try { log.accept(message); } catch (Throwable ignored) { } }

    @Override public void onActivityResumed(Activity activity) {
        Object selected = holder.get();
        if (enabled() && selected != null && activity(root.get()) == activity) main.post(() -> selected(selected));
    }
    @Override public void onActivityPaused(Activity activity) { }
    @Override public void onActivityDestroyed(Activity activity) { }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }

    @Override public void close() {
        if (closed) return;
        closed = true;
        try { followListUpdates.close(); } catch (Exception ignored) { }
        generation.incrementAndGet();
        GenerationAttempt active = activeGeneration;
        if (active != null) markCancelled(active.task);
        ai.cancelCurrentRequest();
        queue.reset();
        queue.close();
        dispatcher.cancelKind(AutomationHistoryStore.COMMENT);
        publisher.close();
        worker.shutdownNow();
        application.unregisterActivityLifecycleCallbacks(this);
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (RuntimeException ignored) { }
        }
        handles.clear();
    }

    private static final class Task {
        final DouAiLog.Operation trace;
        volatile String lastGateReason = "";
        final long epoch;
        final long account;
        final String aid;
        final String identity;
        final String id;
        final Object work;
        final String input;
        final String name;
        final String authorId;
        final LocalOptions options;
        final long createdAt = System.currentTimeMillis();
        final long createdElapsed = android.os.SystemClock.elapsedRealtime();
        volatile long queueMillis = -1, mediaMillis = -1, generationMillis = -1, sendMillis = -1, sendStarted;
        volatile AutomationHistoryStore.Reason reason = AutomationHistoryStore.Reason.NONE;
        volatile AutomationHistoryStore.Stage stage = AutomationHistoryStore.Stage.QUEUE;
        volatile String text;
        volatile String model = "";
        volatile int requestImageCount;
        volatile boolean modelRequestSent;
        volatile boolean approved;
        volatile boolean textFallback;
        volatile int attempt;
        volatile int voiceAttempt;
        volatile int voiceFailures;
        volatile int fallbackAfterAttempt;
        volatile boolean terminal;
        volatile boolean uncertain;
        volatile boolean nativeBoundaryPassed;
        volatile boolean cancelled;
        volatile GenerationAttempt generationAttempt;
        volatile SpeechPreparation.Audio audio;
        Task(long epoch, long account, String aid, String identity, Object work, String input, String name, LocalOptions options) {
            this.epoch = epoch; this.account = account; this.aid = aid; this.identity = identity;
            this.id = identity + ':' + java.util.UUID.randomUUID(); this.work = work; this.input = input;
            this.name = name; this.options = options;
            trace = AutomationTrace.start("auto_comment", id, "feed_selection", options.diagnosticRevision());
            this.authorId = string(modelValue(modelValue(work, "author", "getAuthor"), "uid", "getUid"));
        }
    }

    private final class GenerationAttempt {
        final Task task;
        final MediaReadCancellation mediaCancellation = mediaReads.get();
        final FutureTask<Void> future;
        final Runnable timeout;
        volatile boolean cancelled;
        boolean generated;
        final java.util.concurrent.atomic.AtomicBoolean delivered = new java.util.concurrent.atomic.AtomicBoolean();

        GenerationAttempt(Task task, int attempt, Consumer<String> result) {
            this.task = task;
            Consumer<String> complete = status -> {
                if (delivered.compareAndSet(false, true)) result.accept(status);
                else task.trace.stage("generation_callback_ignored", "duplicate_or_late=true;status=" + status);
            };
            future = new FutureTask<>(() -> { task.trace.wrap(() -> generate(task, attempt, complete, this)).run(); return null; });
            timeout = () -> {
                synchronized (this) {
                    if (future.isDone() || generated || cancelled) return;
                    task.trace.stage("attempt_timeout", "stage=generation;attempt=" + attempt + ";remote_model_result_unknown=true");
                    safeLog("Automatic comment generation deadline reached; attempt=" + attempt + "/3");
                    task.reason = AutomationHistoryStore.Reason.GENERATION_TIMEOUT;
                    cancel();
                    complete.accept(valid(task) ? "failed" : "cancelled");
                }
            };
        }

        void check() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new java.util.concurrent.CancellationException("评论生成已取消");
            }
        }

        synchronized void cancel() {
            cancelled = true;
            mediaCancellation.cancel();
            if (activeGeneration == this) ai.cancelCurrentRequest();
            future.cancel(true);
            main.removeCallbacks(timeout);
            release();
        }

        void release() {
            main.post(() -> {
                if (task.generationAttempt == this) task.generationAttempt = null;
                if (activeGeneration == this) activeGeneration = null;
            });
        }
    }
}
