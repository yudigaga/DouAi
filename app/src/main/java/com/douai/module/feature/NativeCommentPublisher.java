package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;

import android.os.Handler;
import android.os.Looper;

import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Uses the host's permission-checked publisher with a captured work context. */
final class NativeCommentPublisher implements AutoCloseable {
    private static final String API_SERVER_EXCEPTION = "com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException";
    private static final int MAX_HINT_CHARACTERS = 2048;
    private static final Pattern HINT_URL = Pattern.compile("https?://[^\\s]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern HINT_AUTHORIZATION = Pattern.compile("\\b(?:Bearer|Basic)\\s+\\S+", Pattern.CASE_INSENSITIVE);
    private static final String[] HINT_CATEGORIES = {"duration", "size", "format", "permission", "frequency", "parameter", "content"};
    private static final Pattern[] HINT_KEYWORDS = {
            Pattern.compile("时长|\\bduration\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("大小|体积|\\b(?:size|oversize)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("格式|编码|\\b(?:format|codec|encoding)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("权限|无权|\\b(?:permission|forbidden)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("频繁|频率|频次|\\brate[ _-]?limit(?:ed)?\\b|\\btoo many requests\\b|\\btoo frequent\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("参数|\\b(?:parameter|parameters|param)\\b", Pattern.CASE_INSENSITIVE),
            Pattern.compile("内容|违规|审核|\\b(?:content|moderation)\\b", Pattern.CASE_INSENSITIVE)
    };
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Constructor<?> requestConstructor;
    private final Constructor<?> modelConstructor;
    private final Method contextFactory;
    private final Method submit;
    private final Method resolveAweme;
    private final Field modelText;
    private final Field modelContext;
    private final Map<Object, Pending> requests = java.util.Collections.synchronizedMap(new IdentityHashMap<>());
    // Diagnostic correlation only. Never retains a host request/model or changes request lifetime.
    private final CallbackHistory callbackHistory = new CallbackHistory(256,
            java.util.concurrent.TimeUnit.MINUTES.toNanos(5), System::nanoTime);
    private final ThreadLocal<Pending> submitting = new ThreadLocal<>();
    private final Map<Object, String> ownedModels = java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    private final List<XposedInterface.HookHandle> handles = new ArrayList<>();
    private final Consumer<String> log;
    private final CommentAudioDiagnostics diagnostics;
    private final Field attachment;
    private final Constructor<?> attachmentConstructor;
    private final Constructor<?> audioConstructor;
    private final HostScanReport report;
    private final ClassLoader loader;
    private ReplyBinding replyBinding;
    private volatile boolean closed;

    private NativeCommentPublisher(HostScanReport report, ClassLoader loader, Consumer<String> log,
                                   String namespace) throws Exception {
        this.report = report;
        this.loader = loader;
        submit = HostTargets.method(report, "auto_comment_checked_request", loader);
        requestConstructor = submit.getDeclaringClass().getConstructor();
        modelConstructor = submit.getParameterTypes()[0].getConstructor();
        contextFactory = HostTargets.method(report, "auto_comment_work_context", loader);
        resolveAweme = HostTargets.method(report, "portrait_comment_aweme_resolver", loader);
        modelText = HostTargets.field(modelConstructor.getDeclaringClass(), "a");
        modelContext = HostTargets.field(modelConstructor.getDeclaringClass(), "y");
        attachment = HostTargets.field(modelConstructor.getDeclaringClass(), "A");
        attachmentConstructor = attachment.getType().getConstructor();
        audioConstructor = report.getResults().containsKey("comment_audio_model")
                ? HostTargets.type(report, "comment_audio_model", loader).getConstructor(java.io.File.class, List.class, Long.class)
                : null;
        this.log = log;
        diagnostics = new CommentAudioDiagnostics(log, namespace);
        if (submit.getReturnType() != boolean.class || contextFactory.getReturnType() != modelContext.getType()
                || resolveAweme.getParameterTypes()[0] != modelContext.getType()) {
            throw new IllegalStateException("Automatic comment publisher contract changed");
        }
    }

    static NativeCommentPublisher install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Consumer<String> log) throws Exception {
        return install(framework, report, loader, log, "DouAi.AutoComment");
    }

    static NativeCommentPublisher install(XposedInterface framework, HostScanReport report,
                                         ClassLoader loader, Consumer<String> log, String namespace) throws Exception {
        if (namespace == null || namespace.trim().isEmpty()) throw new IllegalArgumentException("Hook namespace is required");
        NativeCommentPublisher publisher = new NativeCommentPublisher(report, loader, log, namespace);
        try {
            publisher.diagnostics.install(framework, loader);
            Method upload = HostTargets.method(report, "auto_comment_audio_upload", loader);
            XposedInterface.HookHandle uploadHandle = framework.hook(upload).setId(namespace + ".AudioUpload")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        Pending pending = publisher.submitting.get();
                        Object callback = chain.getArg(2);
                        if (pending != null && pending.audioPath != null
                                && pending.model == HostTargets.read(callback, "l0")
                                && pending.owner == HostTargets.read(callback, "l1")
                                && pending.audioPath.equals(chain.getArg(1))) {
                            // The checked host method returns false after starting asynchronous audio upload.
                            pending.uploadStarted = true;
                            publisher.diagnostics.model(pending.sample, "upload_before", pending.model);
                            publisher.safeLog("Automatic comment native audio upload started for owned request");
                        }
                        return chain.proceed();
                    });
            if (uploadHandle == null) throw new IllegalStateException("Empty comment audio upload hook");
            publisher.handles.add(uploadHandle);
            // This is the already-scanned native method called after the uploader sets VID.
            // A missing diagnostic hook must never block the original publishing workflow.
            try {
                Method publish = HostTargets.method(report, "comment_publish_submit", loader);
                if (publish.getDeclaringClass() != publisher.submit.getDeclaringClass()
                        || publish.getReturnType() != void.class || Modifier.isStatic(publish.getModifiers())
                        || publish.getParameterTypes().length != 1
                        || publish.getParameterTypes()[0] != publisher.modelConstructor.getDeclaringClass()) {
                    throw new IllegalStateException("Comment audio publish diagnostic contract unavailable");
                }
                XposedInterface.HookHandle publishHandle = framework.hook(publish)
                        .setId(namespace + ".AudioPublishModel")
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Pending owned = publisher.requests.get(chain.getThisObject());
                            Object model = chain.getArg(0);
                            if (owned != null && owned.audioPath != null && owned.model == model) {
                                publisher.diagnostics.model(owned.sample, "publish_before", model);
                            }
                            return chain.proceed();
                        });
                if (publishHandle != null) publisher.handles.add(publishHandle);
                else publisher.safeLog("Comment audio publish diagnostic hook unavailable");
            } catch (Exception | LinkageError unavailable) {
                publisher.safeLog("Comment audio publish diagnostic hook unavailable: "
                        + unavailable.getClass().getSimpleName());
            }
            for (String rule : new String[]{"auto_comment_publish_success", "auto_comment_publish_failure"}) {
                Method target = HostTargets.method(report, rule, loader);
                boolean success = rule.endsWith("success");
                XposedInterface.HookHandle handle = framework.hook(target).setId(namespace + "." + rule)
                        .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                            Object owner = chain.getThisObject();
                            Object response = chain.getArg(0);
                            Pending pending = publisher.submitting.get();
                            if (pending != null && pending.owner == owner) pending.callbackSeen = true;
                            Pending owned = publisher.requests.get(owner);
                            if (owned != null && owned.audioPath != null) {
                                publisher.diagnostics.response(owned.sample,
                                        success ? "success_before" : "failure_before", response);
                            }
                            boolean returned = false;
                            try {
                                Object value = chain.proceed();
                                returned = true;
                                return value;
                            }
                            finally {
                                if (owned != null && owned.audioPath != null) {
                                    publisher.diagnostics.response(owned.sample,
                                            success ? (returned ? "success_after" : "success_threw")
                                                    : (returned ? "failure_after" : "failure_threw"), response);
                                }
                                publisher.main.post(() -> {
                                    Pending tracked = publisher.requests.get(owner);
                                    String status = !success ? "failed" : tracked == null ? "submitted"
                                            : responseStatus(response, tracked.aid, tracked.rootId, tracked.incomingId);
                                    String detail = callbackDiagnostic(response);
                                    if ("sent".equals(status) && tracked != null && tracked.audioPath != null) {
                                        detail += audioReceiptDiagnostic(response);
                                    } else if ("failed".equals(status) && tracked != null && tracked.audioPath != null) {
                                        detail += audioFailureHintDiagnostic(response) + tracked.audioWorkDiagnostic;
                                    }
                                    String publishedId = tracked == null ? "" : publishedCommentId(response,
                                            tracked.aid, tracked.rootId, tracked.incomingId);
                                    publisher.received(owner, status, detail, true, publishedId);
                                });
                            }
                        });
                if (handle == null) throw new IllegalStateException("Empty comment callback hook");
                publisher.handles.add(handle);
            }
            return publisher;
        } catch (Exception failure) { publisher.close(); throw failure; }
    }

    boolean ownsPublish(Object model) {
        String text = ownedModels.get(model);
        return text != null && text.equals(HostTargets.read(model, "a"));
    }

    void send(Object work, String aid, String text, BooleanSupplier valid, BooleanSupplier beforeNativeSend,
              Runnable acceptedRequest, Consumer<String> result) {
        send(work, aid, text, null, valid, beforeNativeSend, acceptedRequest, result);
    }

    void send(Object work, String aid, String text, SpeechPreparation.Audio audio, BooleanSupplier valid,
              BooleanSupplier beforeNativeSend, Runnable acceptedRequest, Consumer<String> result) {
        send(work, aid, text, audio, valid, beforeNativeSend, acceptedRequest, result, ignored -> { });
    }

    void send(Object work, String aid, String text, SpeechPreparation.Audio audio, BooleanSupplier valid,
              BooleanSupplier beforeNativeSend, Runnable acceptedRequest, Consumer<String> result,
              Consumer<String> publishedCommentId) {
        send(work, aid, text, audio, null, valid, beforeNativeSend, acceptedRequest, result, publishedCommentId);
    }

    void sendReply(CommentReplyEvent event, String text, BooleanSupplier valid, BooleanSupplier beforeNativeSend,
                   Runnable acceptedRequest, Consumer<String> result) {
        sendReply(event, text, null, valid, beforeNativeSend, acceptedRequest, result);
    }

    void sendReply(CommentReplyEvent event, String text, SpeechPreparation.Audio audio,
                   BooleanSupplier valid, BooleanSupplier beforeNativeSend,
                   Runnable acceptedRequest, Consumer<String> result) {
        sendReply(event, text, audio, valid, beforeNativeSend, acceptedRequest, result, ignored -> { });
    }

    void sendReply(CommentReplyEvent event, String text, SpeechPreparation.Audio audio,
                   BooleanSupplier valid, BooleanSupplier beforeNativeSend,
                   Runnable acceptedRequest, Consumer<String> result, Consumer<String> publishedCommentId) {
        if (event == null) { main.post(() -> result.accept("failed")); return; }
        send(event.work, event.aid, text, audio, event, valid, beforeNativeSend, acceptedRequest, result, publishedCommentId);
    }

    private void send(Object work, String aid, String text, SpeechPreparation.Audio audio, CommentReplyEvent reply,
                      BooleanSupplier valid, BooleanSupplier beforeNativeSend, Runnable acceptedRequest, Consumer<String> result,
                      Consumer<String> publishedCommentId) {
        DouAiLog.Operation trace = AutomationTrace.capture(reply == null ? "auto_comment" : "auto_comment_reply", "native_publish");
        trace.stage("native_prepare", "target_ref=" + DouAiLog.reference(aid) + ";media=" + (audio == null ? "text" : "audio"));
        main.post(trace.wrap(() -> {
            if (closed || !valid.getAsBoolean()) { trace.stage("native_cancelled", "before_invocation=true;closed=" + closed); result.accept("cancelled"); return; }
            Object request = null;
            Pending pending = null;
            boolean accepted = false;
            boolean nativeInvoked = false;
            try {
                Object state = contextFactory.invoke(null, work);
                Object origin = state == null ? null : resolveAweme.invoke(null, state);
                if (!aid.equals(HostTargets.invoke(origin, "getAid"))) throw new IllegalStateException("Origin work changed");
                Object model = modelConstructor.newInstance();
                ReplyBinding binding = reply == null ? null : replyBinding();
                if (reply != null) {
                    if ((audio == null && (text == null || text.trim().isEmpty())) || !validReplyTarget(reply, binding.target)) {
                        throw new IllegalStateException("Comment reply target unavailable or changed");
                    }
                    binding.target.set(model, reply.targetComment);
                    binding.root.set(model, reply.rootId);
                    binding.direct.set(model, reply.rootId.equals(reply.incomingId) ? "0" : reply.incomingId);
                }
                String outputText = audio == null ? text : "";
                modelText.set(model, outputText);
                modelContext.set(model, state);
                if (audio != null) {
                    if (audioConstructor == null) throw new IllegalStateException("当前宿主未适配语音评论");
                    if (!audio.file.isFile() || audio.file.length() == 0 || audio.duration <= 0) {
                        throw new IllegalArgumentException("评论语音文件无效");
                    }
                    Object media = attachmentConstructor.newInstance();
                    HostTargets.field(media.getClass(), "audio").set(media,
                            audioConstructor.newInstance(audio.file, CommentAudioWaveform.nativeFallback(), Long.valueOf(audio.duration)));
                    HostTargets.field(media.getClass(), "text").set(media, new android.text.SpannableStringBuilder());
                    attachment.set(model, media);
                    HostTargets.field(model.getClass(), "d").set(model, java.util.Collections.emptyList());
                }
                HostTargets.field(model.getClass(), "n").set(model, reply == null ? "douai_auto" : "douai_comment_reply");
                if (reply != null && !binding.matches(model, reply)) {
                    throw new IllegalStateException("Comment reply native target does not match");
                }
                ownedModels.put(model, outputText);
                CommentAudioDiagnostics.Sample sample = audio == null ? null
                        : diagnostics.begin(model, aid, reply != null);
                request = requestConstructor.newInstance();
                pending = new Pending(aid, request, model, audio == null ? null : audio.file.getAbsolutePath(),
                        reply == null ? null : reply.rootId, reply == null ? null : reply.incomingId,
                        audio == null ? "" : audioWorkDiagnostic(origin), sample, result, publishedCommentId);
                requests.put(request, pending);
                if (!valid.getAsBoolean() || !beforeNativeSend.getAsBoolean()) {
                    received(request, "cancelled");
                    return;
                }
                if (reply != null && !binding.matches(model, reply)) {
                    throw new IllegalStateException("Comment reply target changed before submission");
                }
                if (reply != null) safeLog("Comment reply native target verified: root_match=1; direct_match=1; nested="
                        + (reply.rootId.equals(reply.incomingId) ? 0 : 1));
                safeLog("Automatic comment native submission; media=" + (audio == null ? "text" : "audio")
                        + "; audioMillis=" + (audio == null ? 0 : audio.duration)
                        + "; audioBytes=" + (audio == null ? 0 : audio.file.length()));
                boolean directSubmission;
                submitting.set(pending);
                try {
                    nativeInvoked = true;
                    trace.stage("native_submit", "business_success=false;audio_upload_possible=" + (audio != null));
                    directSubmission = Boolean.TRUE.equals(submit.invoke(request, model));
                }
                finally { submitting.remove(); }
                accepted = directSubmission || pending.uploadStarted || pending.callbackSeen;
                if (!accepted) { received(request, "failed"); return; }
                trace.stage("native_submission_accepted", "direct=" + directSubmission + ";upload_started=" + pending.uploadStarted + ";callback_seen=" + pending.callbackSeen + ";business_success=false");
                acceptedRequest.run();
                safeLog("Automatic comment native request accepted for captured work; mode="
                        + (pending.uploadStarted ? "audio_upload" : "direct"));
                Object owner = request;
                Pending token = pending;
                main.postDelayed(() -> {
                    if (requests.get(owner) != token || !token.timedOut.compareAndSet(false, true)) return;
                    token.trace.stage("native_ack_timeout", "deadline_ms=30000;remote_outcome=unknown;retention_ms=120000");
                    token.result.accept("submitted");
                }, 30_000);
                main.postDelayed(() -> expireObservation(owner, token), 120_000);
            } catch (Exception failure) {
                trace.error("native_publish", failure);
                // An invoked host method can throw after it has already sent the request.
                if (request != null && pending != null
                        && (accepted || nativeInvoked && failure instanceof java.lang.reflect.InvocationTargetException)) {
                    Object owner = request;
                    Pending token = pending;
                    main.postDelayed(() -> expireObservation(owner, token), 120_000);
                    safeLog("Automatic comment native request outcome unknown: " + failure.getClass().getSimpleName());
                    result.accept("submitted");
                    return;
                }
                if (request != null && pending != null && requests.remove(request, pending)) {
                    callbackHistory.remember(request, pending.trace, "request_failed_locally", "failed");
                }
                safeLog("Automatic comment native request failed: " + failure.getClass().getSimpleName());
                result.accept("failed");
            }
        }));
    }

    /** Reply-only binding: ordinary work comments do not depend on these host helpers. */
    private ReplyBinding replyBinding() throws Exception {
        if (replyBinding == null) replyBinding = new ReplyBinding();
        return replyBinding;
    }

    private final class ReplyBinding {
        final Field target = HostTargets.field(modelConstructor.getDeclaringClass(), "p");
        final Field root = HostTargets.field(modelConstructor.getDeclaringClass(), "b");
        final Field direct = HostTargets.field(modelConstructor.getDeclaringClass(), "e");
        final Method rootResolver = HostTargets.method(report, "comment_reply_publish_root", loader);
        final Method directResolver = HostTargets.method(report, "comment_reply_publish_direct", loader);

        ReplyBinding() throws Exception {
            Class<?>[] parameters = {target.getType(), modelConstructor.getDeclaringClass()};
            if (root.getType() != String.class || direct.getType() != String.class
                    || Modifier.isStatic(target.getModifiers()) || Modifier.isStatic(root.getModifiers())
                    || Modifier.isStatic(direct.getModifiers())
                    || !Modifier.isStatic(rootResolver.getModifiers()) || !Modifier.isStatic(directResolver.getModifiers())
                    || rootResolver.getReturnType() != String.class || directResolver.getReturnType() != String.class
                    || !java.util.Arrays.equals(rootResolver.getParameterTypes(), parameters)
                    || !java.util.Arrays.equals(directResolver.getParameterTypes(), parameters)) {
                throw new IllegalStateException("Comment reply native target contract changed");
            }
        }

        boolean matches(Object model, CommentReplyEvent event) throws Exception {
            String expectedDirect = event.rootId.equals(event.incomingId) ? "0" : event.incomingId;
            if (target.get(model) != event.targetComment || !validReplyTarget(event, target)
                    || !event.rootId.equals(root.get(model)) || !expectedDirect.equals(direct.get(model))) return false;
            Object resolvedRoot = rootResolver.invoke(null, event.targetComment, model);
            Object resolvedDirect = directResolver.invoke(null, event.targetComment, model);
            return event.rootId.equals(resolvedRoot) && (event.rootId.equals(event.incomingId)
                    ? resolvedDirect == null || "".equals(resolvedDirect) || "0".equals(resolvedDirect)
                    : event.incomingId.equals(resolvedDirect));
        }
    }

    private void received(Object request, String status) {
        received(request, status, "", false);
    }

    private void received(Object request, String status, String detail) {
        received(request, status, detail, true);
    }

    private void received(Object request, String status, String detail, boolean hostCallback) {
        received(request, status, detail, hostCallback, "");
    }

    private void received(Object request, String status, String detail, boolean hostCallback, String publishedId) {
        Pending pending = requests.remove(request);
        if (pending == null || closed) {
            if (pending != null) callbackHistory.remember(request, pending.trace, "publisher_closed", "unknown");
            ignoredCallback(request, status);
            return;
        }
        callbackHistory.remember(request, pending.trace,
                hostCallback ? "callback_already_processed" : "request_completed_locally", status);
        String acknowledgement = "status=" + status + ";upload_started=" + pending.uploadStarted
                + ";timed_out=" + pending.timedOut.get() + ";evidence=" + detail;
        // A retained late callback can arrive after the parent has an unconfirmed terminal.
        // stage() deliberately rejects post-terminal stages, so keep the receipt as its own event.
        pending.trace.wrap(() -> DouAiLog.event("native_comment_publish",
                pending.timedOut.get() ? "late_native_ack" : "native_ack", "owned_callback_received", acknowledgement)).run();
        pending.trace.stage(pending.timedOut.get() ? "late_native_ack" : "native_ack", "status=" + status);
        safeLog("Automatic comment native callback: " + status + detail + audioPhase(pending)
                + "; kind=" + (pending.rootId == null ? "root" : "reply")
                + "; media=" + (pending.audioPath == null ? "text" : "voice"));
        if (hostCallback && "sent".equals(status) && publishedId != null && !publishedId.isEmpty()) {
            try { pending.publishedCommentId.accept(publishedId); }
            catch (RuntimeException ignored) { safeLog("Comment navigation receipt observer unavailable"); }
        }
        pending.trace.wrapConsumer(pending.result).accept(status);
    }

    private void expireObservation(Object owner, Pending pending) {
        if (!requests.remove(owner, pending)) return;
        callbackHistory.remember(owner, pending.trace, "retention_expired", "unknown");
        pending.trace.wrap(() -> DouAiLog.event("native_comment_publish", "callback_observation_ended", "retention_expired",
                "retention_ms=120000;remote_outcome=unknown;business_retry_changed=false")).run();
    }

    private void ignoredCallback(Object request, String status) {
        CallbackRecord record = callbackHistory.find(request);
        String reason = record == null ? closed ? "publisher_closed_ownership_unknown" : "ownership_unknown" : record.reason;
        String evidence = "closed=" + closed + ";received_status=" + status
                + ";ownership_identity_match=" + (record != null)
                + ";correlation=" + (record == null ? "unknown;history_absent_expired_or_evicted=true" : "verified;prior_status=" + record.status)
                + ";" + callbackHistory.diagnostic()
                + ";business_result_changed=false";
        Runnable emit = () -> DouAiLog.event("native_comment_publish", "callback_ignored", reason, evidence);
        if (record == null) emit.run(); else record.trace.wrap(emit).run();
    }

    /** Identity matches only; never associates a callback by aid/text/response similarity. */
    static final class CallbackHistory {
        private final int capacity;
        private final long retentionNanos;
        private final LongSupplier clock;
        private final ArrayDeque<CallbackRecord> records = new ArrayDeque<>();
        private long evicted, expired, collected;
        CallbackHistory(int capacity, long retentionNanos, LongSupplier clock) {
            if (capacity <= 0 || retentionNanos <= 0) throw new IllegalArgumentException("Invalid callback evidence bound");
            this.capacity = capacity; this.retentionNanos = retentionNanos; this.clock = clock;
        }
        synchronized void remember(Object owner, DouAiLog.Operation trace, String reason, String status) {
            if (owner == null || trace == null) return;
            long now = clock.getAsLong();
            purge(now);
            records.removeIf(record -> record.owner.get() == owner);
            while (records.size() >= capacity) { records.removeFirst(); evicted++; }
            records.addLast(new CallbackRecord(owner, trace, reason, status, now));
        }
        synchronized CallbackRecord find(Object owner) {
            long now = clock.getAsLong();
            purge(now);
            if (owner == null) return null;
            for (CallbackRecord record : records) if (record.owner.get() == owner) return record;
            return null;
        }
        synchronized int size() { return records.size(); }
        synchronized String diagnostic() {
            return "history_capacity=" + capacity + ";history_retention_ms=" + retentionNanos / 1_000_000L
                    + ";history_evicted=" + evicted + ";history_expired=" + expired + ";history_owner_collected=" + collected;
        }
        private void purge(long now) {
            java.util.Iterator<CallbackRecord> iterator = records.iterator();
            while (iterator.hasNext()) {
                CallbackRecord record = iterator.next();
                if (now - record.at >= retentionNanos) { iterator.remove(); expired++; }
                else if (record.owner.get() == null) { iterator.remove(); collected++; }
            }
        }
    }

    static final class CallbackRecord {
        final WeakReference<Object> owner;
        final DouAiLog.Operation trace;
        final String reason, status;
        final long at;
        CallbackRecord(Object owner, DouAiLog.Operation trace, String reason, String status, long at) {
            this.owner = new WeakReference<>(owner); this.trace = trace;
            this.reason = reason; this.status = status; this.at = at;
        }
    }

    private String audioPhase(Pending pending) {
        if (pending.audioPath == null) return "";
        String phase = "unknown";
        try {
            Object audio = HostTargets.read(attachment.get(pending.model), "audio");
            if (audioConstructor != null && audioConstructor.getDeclaringClass().isInstance(audio)) {
                // Direct DEX 40.6.0: X/0yHx.vid:String is set by the successful uploader
                // callback immediately before the native publisher starts its network request.
                Field vidField = HostTargets.field(audio.getClass(), "vid");
                if (vidField.getType() == String.class) {
                    String vid = (String) vidField.get(audio);
                    phase = vid == null || vid.trim().isEmpty() ? "upload_or_auth" : "publish";
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        return "; audio_phase=" + phase;
    }

    static String callbackDiagnostic(Object response) {
        if (response == null) return "; response=null";
        Object code = HostTargets.read(response, "status_code");
        if (!(code instanceof Number)) code = HostTargets.invoke(response, "getErrorCode");
        if (!(code instanceof Number)) code = HostTargets.read(response, "errorCode");
        return "; response=" + response.getClass().getSimpleName()
                + (code instanceof Number ? "; code=" + ((Number) code).longValue() : "");
    }

    private static String audioFailureHintDiagnostic(Object response) {
        String hint = "unknown";
        try {
            Class<?> api = response == null ? null : response.getClass();
            while (api != null && !API_SERVER_EXCEPTION.equals(api.getName())) api = api.getSuperclass();
            if (api != null) {
                // Direct DEX 40.6.0: final LIZLLL():String reads mPrompt; final LIZJ():String
                // reads mErrorMsg. The native error UI prefers a nonempty prompt over mErrorMsg.
                Method promptGetter = api.getDeclaredMethod("LIZLLL");
                Method errorGetter = api.getDeclaredMethod("LIZJ");
                if (verifiedHintGetter(promptGetter) && verifiedHintGetter(errorGetter)) {
                    String prompt = boundedHint((String) promptGetter.invoke(response));
                    String message = prompt.isEmpty() ? boundedHint((String) errorGetter.invoke(response)) : prompt;
                    if (message.isEmpty()) hint = "empty";
                    else {
                        // URLs and authentication tokens must not supply misleading keyword matches.
                        String inspected = HINT_AUTHORIZATION.matcher(HINT_URL.matcher(message).replaceAll(" ")).replaceAll(" ");
                        StringBuilder categories = new StringBuilder();
                        for (int index = 0; index < HINT_CATEGORIES.length; index++) {
                            if (!HINT_KEYWORDS[index].matcher(inspected).find()) continue;
                            if (categories.length() > 0) categories.append('+');
                            categories.append(HINT_CATEGORIES[index]);
                        }
                        hint = categories.length() == 0 ? "other" : categories.toString();
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { }
        return "; audio_hint=" + hint;
    }

    private static boolean verifiedHintGetter(Method method) {
        int modifiers = method.getModifiers();
        return method.getReturnType() == String.class && method.getParameterCount() == 0
                && Modifier.isPublic(modifiers) && Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers);
    }

    private static String boundedHint(String value) {
        return value == null ? "" : value.substring(0, Math.min(value.length(), MAX_HINT_CHARACTERS)).trim();
    }

    private static String audioWorkDiagnostic(Object work) {
        // Direct DEX 40.6.0 0yAP.LJI/LJIJI read these fields. They describe UI policy;
        // this snapshot is diagnostic only and never decides whether a request may be sent.
        Object config = diagnosticField(work, "feedCommentConfig", null);
        Object permission = diagnosticField(config, "audioCommentPermission", int.class);
        Object blockConfig = diagnosticField(config, "commentBlockConfig", null);
        Object blocked = diagnosticField(blockConfig, "blockAudio", boolean.class);
        return "; audio_permission=" + (permission instanceof Integer ? permission : "unknown")
                + "; audio_block=" + (blocked instanceof Boolean ? blocked : "unknown");
    }

    private static Object diagnosticField(Object owner, String name, Class<?> expected) {
        if (owner == null) return null;
        try {
            Field field = HostTargets.field(owner.getClass(), name);
            if (Modifier.isStatic(field.getModifiers()) || expected != null && field.getType() != expected) return null;
            return field.get(owner);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) { return null; }
    }

    static String audioReceiptDiagnostic(Object response) {
        String receipt = "unknown";
        try {
            if (response != null) {
                Field commentField = HostTargets.field(response.getClass(), "comment");
                Field audioField = HostTargets.field(commentField.getType(), "commentAudio");
                // Direct DEX 40.6.0 CommentAudioStruct: a:String is vid; e:Long is duration.
                // A structurally different host response cannot establish this media contract.
                Field vidField = HostTargets.field(audioField.getType(), "a");
                Field durationField = HostTargets.field(audioField.getType(), "e");
                if (vidField.getType() == String.class && durationField.getType() == Long.class) {
                    Object comment = commentField.get(response);
                    Object audio = comment == null ? null : audioField.get(comment);
                    if (audio == null) receipt = "missing";
                    else {
                        String vid = (String) vidField.get(audio);
                        Object duration = durationField.get(audio);
                        receipt = vid != null && !vid.trim().isEmpty() && duration instanceof Number
                                && ((Number) duration).longValue() > 0 ? "present" : "missing";
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) { }
        return "; audio_receipt=" + receipt;
    }

    static boolean validResponse(Object response, String aid) {
        Object status = HostTargets.read(response, "status_code");
        Object comment = HostTargets.read(response, "comment");
        Object cid = HostTargets.read(comment, "cid");
        Object origin = HostTargets.read(comment, "awemeId");
        return status instanceof Number && ((Number) status).intValue() == 0
                && cid instanceof String && !((String) cid).isEmpty()
                && (origin == null || "".equals(origin) || aid.equals(origin));
    }

    private static boolean validReplyTarget(CommentReplyEvent event, Field targetField) {
        if (event.targetComment == null || !targetField.getType().isInstance(event.targetComment)
                || event.incomingId == null || event.incomingId.isEmpty() || event.rootId == null || event.rootId.isEmpty()
                || event.aid == null || event.aid.isEmpty()
                || !event.incomingId.equals(HostTargets.read(event.targetComment, "cid"))
                || !event.aid.equals(HostTargets.read(event.work, "aid"))) return false;
        Object targetAid = HostTargets.read(event.targetComment, "awemeId");
        // The unified native notice binds its work separately from current_comment.
        // Only that verified binding may omit the repeated ID; a conflicting ID always fails.
        if (!event.aid.equals(targetAid)
                && !(event.nativeWorkBound && (targetAid == null || "".equals(targetAid)))) return false;
        Object parent = HostTargets.read(event.targetComment, "replyId");
        String root = parent instanceof String && !((String) parent).isEmpty() && !"0".equals(parent)
                ? (String) parent : event.incomingId;
        return event.rootId.equals(root);
    }

    static boolean validReplyResponse(Object response, String aid, String rootId, String incomingId) {
        if (!validResponse(response, aid) || rootId == null || rootId.isEmpty() || incomingId == null || incomingId.isEmpty()) return false;
        Object comment = HostTargets.read(response, "comment");
        return aid.equals(HostTargets.read(comment, "awemeId"))
                && rootId.equals(HostTargets.read(comment, "replyId"))
                && (rootId.equals(incomingId) || incomingId.equals(HostTargets.read(comment, "replyToReplyId")));
    }

    static String responseStatus(Object response, String aid, String rootId, String incomingId) {
        Object status = HostTargets.read(response, "status_code");
        if (status instanceof Number && ((Number) status).longValue() != 0) return "failed";
        return (rootId == null ? validResponse(response, aid) : validReplyResponse(response, aid, rootId, incomingId))
                ? "sent" : "submitted";
    }

    static String publishedCommentId(Object response, String aid, String rootId, String incomingId) {
        if (!"sent".equals(responseStatus(response, aid, rootId, incomingId))) return "";
        Object comment = HostTargets.read(response, "comment");
        if (aid == null || !aid.equals(HostTargets.read(comment, "awemeId"))) return "";
        Object cid = HostTargets.read(comment, "cid");
        return cid instanceof String ? (String) cid : "";
    }

    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }

    @Override public void close() {
        closed = true;
        try { diagnostics.close(); }
        catch (RuntimeException | LinkageError unavailable) {
            safeLog("Comment audio diagnostics close unavailable: " + unavailable.getClass().getSimpleName());
        }
        main.post(() -> {
            synchronized (requests) {
                for (Pending pending : requests.values()) {
                    callbackHistory.remember(pending.owner, pending.trace, "publisher_closed", "unknown");
                    pending.trace.stage("native_publisher_closed", "callback_observation_stopped=true;remote_outcome=unknown");
                }
                requests.clear();
            }
        });
        ownedModels.clear();
        for (XposedInterface.HookHandle handle : handles) {
            try { handle.unhook(); } catch (RuntimeException ignored) { }
        }
        handles.clear();
    }

    private static final class Pending {
        final String aid;
        final DouAiLog.Operation trace;
        final Object owner;
        final Object model;
        final String audioPath;
        final String rootId;
        final String incomingId;
        final String audioWorkDiagnostic;
        final CommentAudioDiagnostics.Sample sample;
        final Consumer<String> result;
        final Consumer<String> publishedCommentId;
        final AtomicBoolean timedOut = new AtomicBoolean();
        boolean uploadStarted;
        boolean callbackSeen;
        Pending(String aid, Object owner, Object model, String audioPath, String rootId, String incomingId,
                String audioWorkDiagnostic, CommentAudioDiagnostics.Sample sample, Consumer<String> result,
                Consumer<String> publishedCommentId) {
            this.trace = AutomationTrace.capture("native_comment_publish", "owned_request");
            this.aid = aid; this.owner = owner; this.model = model; this.audioPath = audioPath; this.result = result;
            this.rootId = rootId; this.incomingId = incomingId;
            this.audioWorkDiagnostic = audioWorkDiagnostic;
            this.sample = sample;
            this.publishedCommentId = publishedCommentId == null ? ignored -> { } : publishedCommentId;
        }
    }
}
