package com.douai.module.feature;

import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

/** Provider-aware text client for module-local model profiles. */
public final class ChatCompletionClient {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_REPLY_CHARS = 4000;
    private static final int MAX_STRUCTURED_REPLY_CHARS = MAX_REPLY_CHARS + 32;
    private static final ThreadPoolExecutor STREAM_IO = ioWorkers();
    private static final ThreadPoolExecutor STREAM_CLEANUP = cleanupWorkers();
    public static final int MAX_IMAGES_PER_MESSAGE = 4;
    private static final int MAX_HISTORY_IMAGE_BYTES = 12 * 1024 * 1024;
    private final Map<String, ArrayDeque<HistoryTurn>> history = new LinkedHashMap<>();
    private final ArrayDeque<HistoryTurn> historyOrder = new ArrayDeque<>();
    private final Map<HistoryTurn, HistoryImageBudget.Ticket> historyImageTickets = new IdentityHashMap<>();
    private final HistoryImageBudget historyImageBudget;
    private long historyImageBytes;
    private final Object requestLock = new Object();
    private ActiveRequest activeRequest;
    private long historyEpoch;
    private final TestChatMemory memory;
    private final Consumer<String> diagnostics;

    /** Safe transport metadata only; never retains provider text, URLs, headers or a raw cause. */
    public static final class ReplyFailure extends IllegalStateException {
        public enum Kind { HTTP, EMPTY, OVERLONG, FORMAT, INCOMPLETE }
        private final Kind kind;
        private final int httpStatus;
        private final int responseChars;
        private ReplyFailure(Kind kind, int httpStatus, int responseChars) {
            super(kind == Kind.HTTP ? "AI 服务 HTTP " + httpStatus + "；请检查地址、协议、密钥和模型权限"
                    : kind == Kind.EMPTY ? "AI 回复为空" : kind == Kind.OVERLONG ? "AI 回复过长"
                    : kind == Kind.INCOMPLETE ? "AI 回复未完整结束" : "AI 回复格式无法识别");
            this.kind = kind; this.httpStatus = httpStatus; this.responseChars = responseChars;
        }
        public Kind kind() { return kind; }
        public int httpStatus() { return httpStatus; }
        public int responseChars() { return responseChars; }
    }

    public ChatCompletionClient() { this(null, null); }

    public ChatCompletionClient(TestChatMemory memory, Consumer<String> diagnostics) {
        this(memory, diagnostics, HistoryImageBudget.SHARED);
    }

    ChatCompletionClient(TestChatMemory memory, Consumer<String> diagnostics, HistoryImageBudget historyImageBudget) {
        this.memory = memory;
        this.diagnostics = diagnostics == null ? ignored -> { } : diagnostics;
        this.historyImageBudget = java.util.Objects.requireNonNull(historyImageBudget);
    }

    private static ThreadPoolExecutor ioWorkers() {
        return new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, "DouAi-stream-io"); thread.setDaemon(true); return thread;
        });
    }

    private static ThreadPoolExecutor cleanupWorkers() {
        ThreadPoolExecutor executor = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(8), task -> {
            Thread thread = new Thread(task, "DouAi-stream-cleanup"); thread.setDaemon(true); return thread;
        });
        executor.allowCoreThreadTimeOut(true);
        return executor;
    }

    public List<TestChatMemory.Turn> savedTurns() {
        return memory == null ? Collections.emptyList() : memory.read();
    }

    public synchronized String reply(String conversation, String prompt, AiModelSettings settings) throws Exception {
        return reply(conversation, prompt, Collections.emptyList(), settings);
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings) throws Exception {
        return reply(conversation, prompt, images, settings, "");
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt) throws Exception {
        return reply(conversation, prompt, images, settings, secondaryPrompt, true);
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt, boolean remember) throws Exception {
        return reply(conversation, prompt, images, settings, secondaryPrompt, remember, "");
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt, boolean remember,
                                     String document) throws Exception {
        return reply(conversation, prompt, images, settings, secondaryPrompt, remember, document, ignored -> { });
    }

    public synchronized String reply(String conversation, String prompt, List<ChatImage> images,
                                     AiModelSettings settings, String secondaryPrompt, boolean remember,
                                     String document, IntConsumer requestSent) throws Exception {
        return replyWithLimit(conversation, prompt, images, settings, secondaryPrompt, remember, document,
                requestSent, MAX_REPLY_CHARS);
    }

    /** Cumulative visible text for manual chat; partials are provisional until the returned reply validates. */
    public synchronized String replyStreaming(String conversation, String prompt, List<ChatImage> images,
                                             AiModelSettings settings, String secondaryPrompt, boolean remember,
                                             String document, Consumer<String> onPartial) throws Exception {
        return replyWithLimit(conversation, prompt, images, settings, secondaryPrompt, remember, document,
                ignored -> { }, MAX_REPLY_CHARS, onPartial);
    }

    /** Only structured analysis may carry bounded fences/whitespace around its 4000-char canonical JSON. */
    public synchronized String replyStructuredAnalysis(String conversation, String prompt,
                                     AiModelSettings settings, String secondaryPrompt, String document) throws Exception {
        return replyWithLimit(conversation, prompt, Collections.emptyList(), settings, secondaryPrompt, false,
                document, ignored -> { }, MAX_STRUCTURED_REPLY_CHARS);
    }

    private String replyWithLimit(String conversation, String prompt, List<ChatImage> images,
                                  AiModelSettings settings, String secondaryPrompt, boolean remember,
                                   String document, IntConsumer requestSent, int maximumReplyChars) throws Exception {
        return replyWithLimit(conversation, prompt, images, settings, secondaryPrompt, remember, document,
                requestSent, maximumReplyChars, null);
    }

    private String replyWithLimit(String conversation, String prompt, List<ChatImage> images,
                                  AiModelSettings settings, String secondaryPrompt, boolean remember,
                                   String document, IntConsumer requestSent, int maximumReplyChars,
                                   Consumer<String> onPartial) throws Exception {
        AiModelConfig config = settings.selected();
        String secondary = secondaryPrompt == null ? "" : secondaryPrompt;
        List<ChatImage> attachments = images == null ? Collections.emptyList() : new ArrayList<>(images);
        if (attachments.size() > MAX_IMAGES_PER_MESSAGE) throw new IllegalArgumentException("每条消息最多 4 张图片");
        for (ChatImage image : attachments) {
            if (image == null) throw new IllegalArgumentException("图片内容为空");
        }
        String conversationValue = conversation == null ? "" : conversation;
        String promptValue = prompt == null ? "" : prompt;
        String documentValue = document == null ? "" : document;
        IntConsumer sent = requestSent == null ? ignored -> { } : requestSent;
        List<ChatImage> fixedAttachments = Collections.unmodifiableList(attachments);
        config.validate(true);
        if (config.streaming()) {
            return sendBoundedStream(config, conversationValue, promptValue, fixedAttachments, secondary,
                     remember, documentValue, sent, maximumReplyChars, onPartial);
        }
        ActiveRequest request = new ActiveRequest();
        request.onPartial = onPartial;
        return send(config, conversationValue, promptValue, fixedAttachments, secondary, remember, documentValue,
                sent, maximumReplyChars, request, null);
    }

    /** The caller's deadline never waits for a platform socket read or its potentially blocking close. */
    private String sendBoundedStream(AiModelConfig config, String conversation, String prompt, List<ChatImage> images,
                                       String secondary, boolean remember, String document, IntConsumer sent,
                                       int maximumReplyChars, Consumer<String> onPartial) throws Exception {
        ActiveRequest request = new ActiveRequest();
        request.onPartial = onPartial;
        request.asynchronous = true;
        RequestDeadline deadline = new RequestDeadline(System.nanoTime(), config.timeoutSeconds);
        FutureTask<String> io = new FutureTask<>(() -> send(config, conversation, prompt, images, secondary, remember,
                document, sent, maximumReplyChars, request, deadline));
        request.io = io;
        synchronized (requestLock) { ensureActive(request); activeRequest = request; }
        try {
            if (STREAM_CLEANUP.getQueue().remainingCapacity() == 0) throw new RejectedExecutionException();
            STREAM_IO.execute(io);
            String result = io.get(deadline.remainingNanos(), TimeUnit.NANOSECONDS);
            synchronized (requestLock) {
                ensureActive(request);
                deadline.remainingMillis();
                if (request.commitHistory != null) request.commitHistory.run();
            }
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.SUCCESS, "model_stream_validated", "chars=" + result.length() + ";deadline_and_cancel_checks_passed=true");
            return result;
        } catch (TimeoutException timeout) {
            request.timedOut = true;
            request.trace.stage("model_timeout", "deadline_seconds=" + config.timeoutSeconds + ";remote_result=unknown");
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.TIMED_OUT, "model_timeout", "remote_result=unknown");
            // Fence late results immediately; even interrupt/close work stays off the waiting caller.
            io.cancel(false);
            ensureActive(request);
            throw new java.net.SocketTimeoutException("AI request deadline exceeded");
        } catch (InterruptedException interrupted) {
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.CANCELLED, "model_wait_interrupted", "remote_result=unknown");
            request.cancelled = true;
            io.cancel(false);
            Thread.currentThread().interrupt();
            throw cancelled();
        } catch (ExecutionException failed) {
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.FAILED, "model_io_failed", "cause_type=" + (failed.getCause() == null ? "none" : failed.getCause().getClass().getSimpleName()));
            ensureActive(request);
            Throwable cause = failed.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("AI request I/O did not complete");
        } catch (CancellationException cancelled) {
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.CANCELLED, "model_cancelled", "remote_result=unknown");
            ensureActive(request);
            throw cancelled();
        } catch (RejectedExecutionException busy) {
            if (request.ownsTrace) request.trace.finish(DouAiLog.Outcome.FAILED, "model_executor_busy", "request_io_started=false");
            throw new IllegalStateException("AI 请求繁忙，请稍后重试");
        } finally {
            io.cancel(false);
            synchronized (requestLock) {
                request.commitHistory = null;
                if (activeRequest == request) activeRequest = null;
            }
            releaseAsync(request, false);
        }
    }

    /** Automatic replies enter model context only after the host acknowledges sending. */
    public void rememberReply(String conversation, String prompt, List<ChatImage> images,
                              AiModelSettings settings, String secondaryPrompt, String answer) throws Exception {
        AiModelConfig config = settings.selected();
        if (config.contextRounds == 0) return;
        synchronized (requestLock) {
            rememberTurn(new HistoryTurn(historyKey(conversation, config), prompt,
                    Collections.unmodifiableList(new ArrayList<>(images)), answer), config.contextRounds);
        }
    }

    /** Cancels without waiting for the serialized reply operation. */
    public void cancelCurrentRequest() {
        HttpURLConnection connection;
        InputStream input;
        ActiveRequest request;
        synchronized (requestLock) {
            if (activeRequest == null) return;
            request = activeRequest;
            request.cancelled = true;
            request.trace.stage("model_cancel_requested", "asynchronous=" + request.asynchronous + ";remote_result=unknown");
            connection = request.connection;
            input = request.input;
        }
        if (request.asynchronous) {
            if (request.io != null) request.io.cancel(false);
            releaseAsync(request, false);
        } else release(connection, input);
    }

    /** Pending replies cannot restore context after it has been cleared. */
    public void clearHistory() {
        synchronized (requestLock) {
            historyEpoch++;
            synchronized (historyImageBudget) {
                for (HistoryImageBudget.Ticket ticket : historyImageTickets.values()) historyImageBudget.release(ticket);
                historyImageTickets.clear();
                history.clear();
                historyOrder.clear();
                historyImageBytes = 0;
            }
            if (memory != null) memory.clear();
        }
    }

    public static List<String> fetchModels(AiModelConfig config) throws Exception {
        return new ModelCatalogRequest().fetch(config);
    }

    /** One catalog operation; cancellation also releases a blocked HTTP connection. */
    public static final class ModelCatalogRequest {
        private boolean cancelled;
        private boolean started;
        private HttpURLConnection connection;

        public List<String> fetch(AiModelConfig config) throws Exception {
            synchronized (this) {
                ensureActive();
                if (started) throw new IllegalStateException("模型列表请求已启动");
                started = true;
            }
            config.validate(false);
            String url = modelsEndpoint(config);
            HttpURLConnection opened = (HttpURLConnection) new URL(url).openConnection();
            try {
                synchronized (this) {
                    ensureActive();
                    connection = opened;
                }
                configure(opened, config);
                opened.setRequestMethod("GET");
                setAuthentication(opened, config, url);
                ensureActive();
                int status = opened.getResponseCode();
                String body = readResponse(opened, status);
                ensureActive();
                if (status < 200 || status >= 300) throw httpFailure(status);
                List<String> models = parseModelIds(config.protocol, body);
                ensureActive();
                return models;
            } catch (Exception failure) {
                ensureActive();
                throw failure;
            } finally {
                synchronized (this) {
                    if (connection == opened) connection = null;
                }
                opened.disconnect();
            }
        }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) {
                cancelled = true;
                active = connection;
            }
            if (active != null) active.disconnect();
        }

        private synchronized void ensureActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) throw cancelled();
        }
    }

    public static String requestEndpoint(AiModelConfig config, String model) {
        String base = config.apiEndpoint.replaceAll("/+$", "");
        switch (config.protocol) {
            case AiModelConfig.CHAT: return appendSuffix(base, "chat/completions");
            case AiModelConfig.RESPONSES: return appendSuffix(base, "responses");
            case AiModelConfig.ANTHROPIC: return appendSuffix(base, "messages");
            case AiModelConfig.GEMINI:
                String operation = config.streaming() ? ":streamGenerateContent" : ":generateContent";
                // A configured full Gemini operation must switch together with the response mode.
                if (base.matches(".*/models/[^/]+:(?:streamGenerateContent|generateContent)(?:\\?alt=sse)?$"))
                    base = stripOperation(base, config.protocol);
                String endpoint = appendSuffix(base, "models/" + encodePath(model) + operation);
                return config.streaming() ? endpoint + "?alt=sse" : endpoint;
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    public static String modelsEndpoint(AiModelConfig config) {
        if (!config.modelsEndpoint.isEmpty()) return config.modelsEndpoint;
        String base = stripOperation(config.apiEndpoint, config.protocol);
        switch (config.protocol) {
            case AiModelConfig.ANTHROPIC:
            case AiModelConfig.GEMINI:
                return appendSuffix(base, "models");
            case AiModelConfig.CHAT:
            case AiModelConfig.RESPONSES:
                return appendSuffix(base, "models");
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    private String send(AiModelConfig config, String conversation, String prompt,
                                     List<ChatImage> images, String secondaryPrompt, boolean remember,
                                     String document, IntConsumer requestSent, int maximumReplyChars,
                                      ActiveRequest request, RequestDeadline deadline) throws Exception {
        if (Thread.currentThread().isInterrupted()) throw cancelled();
        config.validate(true);
        if (prompt.trim().isEmpty() && images.isEmpty() && document.trim().isEmpty()) throw new IllegalArgumentException("消息不能为空");
        if (prompt.length() > 4000) throw new IllegalArgumentException("消息最多 4000 个字符");
        if (document.length() > 32768) throw new IllegalArgumentException("附件文本最多 32768 个字符");
        String url = requestEndpoint(config, config.modelName);
        List<HistoryTurn> prior;
        String historyKey = historyKey(conversation, config);
        int rounds = config.contextRounds;
        long epoch;
        long savedEpoch = memory == null ? 0 : memory.epoch();
        synchronized (requestLock) {
            ensureActive(request);
            epoch = historyEpoch;
            if (memory != null) {
                List<HistoryTurn> saved = new ArrayList<>();
                for (TestChatMemory.Turn turn : memory.read()) {
                    saved.add(new HistoryTurn(historyKey, turn.prompt, turn.document, turn.images, turn.answer));
                }
                prior = Collections.unmodifiableList(saved);
            } else {
                synchronized (historyImageBudget) {
                    ArrayDeque<HistoryTurn> previous = history.get(historyKey);
                    if (previous != null) {
                        while (previous.size() > rounds) discardTurn(previous.peekFirst());
                    }
                    // HistoryTurn payloads are immutable; eviction never mutates an admitted request.
                    prior = previous == null ? Collections.emptyList()
                            : Collections.unmodifiableList(new ArrayList<>(previous));
                }
            }
            activeRequest = request;
        }
        HttpURLConnection connection = null;
        request.trace.stage("model_transport_prepare", "protocol=" + config.protocol + ";streaming=" + config.streaming() + ";images=" + images.size() + ";history_rounds=" + prior.size() + ";prompt_chars=" + prompt.length() + ";document_chars=" + document.length());
        try {
            ensureActive(request);
            diagnostic(config, secondaryPrompt, prior.size(), "prepare", 0);
            JSONObject body = requestBody(config, prior, withDocument(prompt, document), images, secondaryPrompt);
            connection = (HttpURLConnection) new URL(url).openConnection();
            synchronized (requestLock) {
                request.connection = connection;
                ensureActive(request);
            }
            configure(connection, config);
            if (deadline != null) {
                connection.setConnectTimeout(deadline.remainingMillis());
                deadline.beforeIo(request, connection);
            }
            connection.setRequestMethod("POST");
            connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            if (config.streaming()) connection.setRequestProperty("Accept", "text/event-stream");
            setAuthentication(connection, config, url);
            long bodyBytes = JsonRequestBody.byteLength(body, () -> ensureActive(request));
            connection.setFixedLengthStreamingMode(bodyBytes);
            ensureActive(request);
            try (java.io.OutputStream output = connection.getOutputStream()) {
                JsonRequestBody.write(body, output, () -> ensureActive(request));
            }
            request.trace.stage("model_transport_sent", "body_bytes=" + bodyBytes + ";business_success=false");
            try { request.trace.wrap(() -> requestSent.accept(images.size())).run(); }
            catch (RuntimeException ignored) { }
            diagnostic(config, secondaryPrompt, prior.size(), "sent", 0);
            ensureActive(request);
            if (deadline != null) deadline.beforeIo(request, connection);
            int status = connection.getResponseCode();
            request.trace.stage("model_http_response", "http_status=" + status + ";business_success=false");
            diagnostic(config, secondaryPrompt, prior.size(), "response", status);
            if (status < 200 || status >= 300) throw httpFailure(status);
            String result;
            if (deadline != null) result = readStreamingResponse(config, connection, request, deadline, status, maximumReplyChars);
            else {
                String response = readResponse(connection, status);
                ensureActive(request);
                try {
                    validateCompletedJson(config.protocol, response, status);
                    result = parseReply(config.protocol, response);
                }
                catch (ReplyFailure failure) { throw failure; }
                catch (Exception invalid) { throw new ReplyFailure(ReplyFailure.Kind.FORMAT, status, -1); }
            }
            if (result.isEmpty()) throw new ReplyFailure(ReplyFailure.Kind.EMPTY, status, 0);
            if (result.length() > maximumReplyChars) throw new ReplyFailure(ReplyFailure.Kind.OVERLONG, status, result.length());
            publishPartial(request, deadline, result);
            synchronized (requestLock) {
                ensureActive(request);
                Runnable commit = () -> {
                    if (remember && rounds > 0 && epoch == historyEpoch) {
                        if (memory != null) memory.append(savedEpoch, new TestChatMemory.Turn(prompt, document, images, result));
                        else rememberTurn(new HistoryTurn(historyKey, prompt, document, images, result), rounds);
                    }
                };
                // Only the waiting caller can publish a streamed turn, after its deadline/cancel check.
                if (request.asynchronous) request.commitHistory = commit;
                else commit.run();
                if (!request.asynchronous && activeRequest == request) activeRequest = null;
            }
            request.trace.stage("model_output_validated", "chars=" + result.length() + ";protocol=" + config.protocol + ";delivery_confirmed=false");
            if (request.ownsTrace && !request.asynchronous) request.trace.finish(DouAiLog.Outcome.SUCCESS, "model_output_validated", "chars=" + result.length() + ";evidence=protocol_completion_and_output_bounds");
            return result;
        } catch (Exception error) {
            request.trace.error("model_transport", error);
            if (request.ownsTrace) request.trace.finish(error instanceof java.net.SocketTimeoutException ? DouAiLog.Outcome.TIMED_OUT : error instanceof CancellationException ? DouAiLog.Outcome.CANCELLED : DouAiLog.Outcome.FAILED, "model_request_failed", "type=" + error.getClass().getSimpleName());
            diagnostic(config, secondaryPrompt, prior.size(), "failed", 0);
            ensureActive(request);
            throw error;
        } finally {
            synchronized (requestLock) {
                if (!request.asynchronous && activeRequest == request) activeRequest = null;
            }
            if (request.asynchronous) releaseAsync(request, true);
            else release(connection, request.input);
        }
    }

    private String readStreamingResponse(AiModelConfig config, HttpURLConnection connection, ActiveRequest request, RequestDeadline deadline,
                                         int status, int maximumReplyChars) throws Exception {
        deadline.beforeIo(request, connection);
        InputStream input = connection.getInputStream();
        synchronized (requestLock) { request.input = input; }
        deadline.beforeIo(request, connection);
        String contentType = connection.getContentType();
        boolean sse = contentType != null && contentType.toLowerCase(java.util.Locale.ROOT).startsWith("text/event-stream");
        streamDiagnostic(request, deadline, "transport=" + (sse ? "stream" : "json") + " stage=stream_headers status=" + status);
        InputStream guarded = new FilterInputStream(input) {
            @Override public int read() throws IOException {
                deadline.beforeIo(request, connection); int value = in.read();
                deadline.beforeIo(request, connection); return value;
            }
            @Override public int read(byte[] buffer, int offset, int length) throws IOException {
                deadline.beforeIo(request, connection); int read = in.read(buffer, offset, length);
                deadline.beforeIo(request, connection); return read;
            }
        };
        if (sse) {
            try {
                return ProtocolResponseStream.read(config.protocol, guarded, maximumReplyChars,
                        () -> deadline.beforeIo(request, connection), value -> streamDiagnostic(request, deadline, value),
                        request.onPartial == null ? null : value -> publishPartial(request, deadline, value));
            } catch (ResponsesAnalysisStream.Failure failure) {
                ReplyFailure.Kind kind;
                switch (failure.kind) {
                    case INCOMPLETE: kind = ReplyFailure.Kind.INCOMPLETE; break;
                    case EMPTY: kind = ReplyFailure.Kind.EMPTY; break;
                    case OVERLONG: kind = ReplyFailure.Kind.OVERLONG; break;
                    default: kind = ReplyFailure.Kind.FORMAT;
                }
                throw new ReplyFailure(kind, status, failure.chars);
            }
        }
        String response = readBounded(guarded, MAX_RESPONSE_BYTES);
        try {
            validateCompletedJson(config.protocol, response, status);
            return parseReply(config.protocol, response);
        }
        catch (ReplyFailure failure) { throw failure; }
        catch (Exception invalid) { throw new ReplyFailure(ReplyFailure.Kind.FORMAT, status, -1); }
    }

    private static void publishPartial(ActiveRequest request, RequestDeadline deadline, String text)
            throws java.net.SocketTimeoutException {
        if (request.onPartial == null || text.equals(request.lastPartial)) return;
        ensureActive(request);
        if (deadline != null) deadline.remainingMillis();
        request.onPartial.accept(text);
        request.lastPartial = text;
        ensureActive(request);
        if (deadline != null) deadline.remainingMillis();
    }

    private void streamDiagnostic(ActiveRequest request, RequestDeadline deadline, String value) {
        request.trace.stage("model_stream_progress", value + ";elapsed_ms=" + deadline.elapsedMillis());
        try { diagnostics.accept(value + " elapsedMs=" + deadline.elapsedMillis()); }
        catch (RuntimeException ignored) { }
    }

    private void releaseAsync(ActiveRequest request, boolean ioWorker) {
        HttpURLConnection connection;
        InputStream input;
        synchronized (requestLock) {
            connection = request.connection;
            input = request.input;
            if (connection == null && input == null) return;
            if (request.cleanupConnection == connection && request.cleanupInput == input) return;
            try {
                STREAM_CLEANUP.execute(() -> release(connection, input));
                request.cleanupConnection = connection; request.cleanupInput = input;
                return;
            } catch (RejectedExecutionException busy) { /* The I/O worker can make a bounded fallback below. */ }
        }
        // Saturation may occupy a bounded I/O worker, never the user-facing waiting thread.
        if (ioWorker) release(connection, input);
    }

    private static void release(HttpURLConnection connection, InputStream input) {
        try { if (connection != null) connection.disconnect(); }
        catch (RuntimeException ignored) { }
        try { if (input != null) input.close(); }
        catch (Exception ignored) { }
    }

    private void diagnostic(AiModelConfig config, String secondary, int rounds, String stage, int status) {
        try { diagnostics.accept("protocol=" + config.protocol + " primaryChars=" + config.systemPrompt.length()
                + " secondaryChars=" + secondary.length() + " historyRounds=" + rounds
                + " stage=" + stage + " status=" + status); }
        catch (RuntimeException ignored) { }
    }

    private static String withDocument(String prompt, String document) {
        return document.isEmpty() ? prompt : prompt + "\n\n[附件文本]\n" + document;
    }

    private static String historyKey(String conversation, AiModelConfig config) throws Exception {
        String memory = java.util.Base64.getEncoder().encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                .digest(config.systemPrompt.getBytes(StandardCharsets.UTF_8)));
        return (conversation == null ? "" : conversation) + "|" + config.id + "|" + config.provider + "|"
                + config.protocol + "|" + config.apiEndpoint + "|" + config.modelName + "|"
                + memory;
    }

    private static void ensureActive(ActiveRequest request) throws java.net.SocketTimeoutException {
        if (request.cancelled || Thread.currentThread().isInterrupted()) throw cancelled();
        if (request.timedOut) throw new java.net.SocketTimeoutException("AI request deadline exceeded");
    }

    private static CancellationException cancelled() { return new CancellationException("请求已取消"); }

    private static final class ActiveRequest {
        final boolean ownsTrace = DouAiLog.current() == null;
        final DouAiLog.Operation trace = AutomationTrace.capture("ai_model", "request");
        volatile boolean cancelled;
        volatile boolean timedOut;
        boolean asynchronous;
        HttpURLConnection connection;
        InputStream input;
        FutureTask<String> io;
        HttpURLConnection cleanupConnection;
        InputStream cleanupInput;
        Runnable commitHistory;
        Consumer<String> onPartial;
        String lastPartial;
    }

    private static final class RequestDeadline {
        private final long started;
        private final long budget;
        RequestDeadline(long started, int seconds) { this.started = started; this.budget = TimeUnit.SECONDS.toNanos(seconds); }
        long remainingNanos() { return Math.max(0, budget - (System.nanoTime() - started)); }
        int remainingMillis() throws java.net.SocketTimeoutException {
            long remaining = remainingNanos();
            if (remaining == 0) throw new java.net.SocketTimeoutException("AI request deadline exceeded");
            return (int) Math.min(Integer.MAX_VALUE, (remaining + 999_999L) / 1_000_000L);
        }
        long elapsedMillis() { return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started)); }
        void beforeIo(ActiveRequest request, HttpURLConnection connection) throws IOException {
            ensureActive(request);
            connection.setReadTimeout(remainingMillis());
        }
    }

    private void rememberTurn(HistoryTurn turn, int rounds) {
        synchronized (historyImageBudget) {
            ArrayDeque<HistoryTurn> previous = history.computeIfAbsent(turn.historyKey, ignored -> new ArrayDeque<>());
            previous.addLast(turn);
            historyOrder.addLast(turn);
            historyImageBytes += turn.imageBytes;
            while (previous.size() > rounds) discardTurn(previous.peekFirst());
            while (history.size() > 100) {
                ArrayDeque<HistoryTurn> oldest = history.values().iterator().next();
                while (!oldest.isEmpty()) discardTurn(oldest.peekFirst());
            }
            // Enforce the original per-client raw-byte limit before requesting shared retention.
            while (historyImageBytes > MAX_HISTORY_IMAGE_BYTES) {
                HistoryTurn oldestImage = null;
                for (HistoryTurn candidate : historyOrder) {
                    if (candidate.imageBytes > 0) { oldestImage = candidate; break; }
                }
                discardTurn(oldestImage);
            }
            if (turn.imageBytes > 0 && historyOrder.contains(turn)) {
                long retainedBytes = 0;
                for (ChatImage image : turn.images) retainedBytes += (long) image.base64Data.length() * 2;
                HistoryImageBudget.Ticket ticket = historyImageBudget.retain(this, turn, retainedBytes);
                if (ticket != null) historyImageTickets.put(turn, ticket);
            }
        }
    }

    private void discardTurn(HistoryTurn turn) {
        if (turn == null || !historyOrder.remove(turn)) return;
        ArrayDeque<HistoryTurn> previous = history.get(turn.historyKey);
        if (previous != null) {
            previous.remove(turn);
            if (previous.isEmpty()) history.remove(turn.historyKey);
        }
        historyImageBytes -= turn.imageBytes;
        historyImageBudget.release(historyImageTickets.remove(turn));
    }

    /** The shared budget owns the history monitor, not another client's transport/request lock. */
    void discardBudgetedTurn(Object turn) {
        synchronized (historyImageBudget) {
            if (turn instanceof HistoryTurn) discardTurn((HistoryTurn) turn);
        }
    }

    private static final class HistoryTurn {
        final String historyKey;
        final String prompt;
        final List<ChatImage> images;
        final String answer;
        final String document;
        final int imageBytes;

        HistoryTurn(String historyKey, String prompt, List<ChatImage> images, String answer) {
            this(historyKey, prompt, "", images, answer);
        }

        HistoryTurn(String historyKey, String prompt, String document, List<ChatImage> images, String answer) {
            this.historyKey = historyKey;
            this.prompt = prompt;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.answer = answer;
            this.document = document;
            int bytes = 0;
            for (ChatImage image : images) bytes += image.sizeBytes();
            this.imageBytes = bytes;
        }
    }

    private static JSONObject requestBody(AiModelConfig config, List<HistoryTurn> prior, String prompt,
                                          List<ChatImage> images, String secondaryPrompt) throws Exception {
        String systemPrompt = config.systemPrompt;
        String currentPrompt = taskPrompt(prompt, secondaryPrompt);
        JSONObject body = new JSONObject().put("model", config.modelName);
        JSONObject parameters = config.parameters();
        java.util.Iterator<String> parameterKeys = parameters.keys();
        while (parameterKeys.hasNext()) {
            String key = parameterKeys.next();
            body.put(key, parameters.get(key));
        }
        switch (config.protocol) {
            case AiModelConfig.CHAT: {
                JSONArray messages = new JSONArray();
                if (!systemPrompt.isEmpty()) messages.put(new JSONObject()
                        .put("role", "system").put("content", systemPrompt));
                appendHistory(messages, prior, config.protocol);
                messages.put(userMessage(config.protocol, currentPrompt, images));
                body.put("messages", messages).put("stream", config.streaming());
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                if (config.maxTokensEnabled) body.put("max_tokens", config.maxTokens);
                break;
            }
            case AiModelConfig.RESPONSES: {
                JSONArray input = new JSONArray();
                appendHistory(input, prior, config.protocol);
                input.put(userMessage(config.protocol, currentPrompt, images));
                body.put("input", input).put("stream", config.streaming()).put("store", false);
                if (!systemPrompt.isEmpty()) body.put("instructions", systemPrompt);
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                if (config.maxTokensEnabled) body.put("max_output_tokens", config.maxTokens);
                break;
            }
            case AiModelConfig.ANTHROPIC: {
                JSONArray messages = new JSONArray();
                appendHistory(messages, prior, config.protocol);
                messages.put(userMessage(config.protocol, currentPrompt, images));
                body.put("messages", messages).put("max_tokens",
                        config.maxTokensEnabled ? config.maxTokens : 1024).put("stream", config.streaming());
                if (!systemPrompt.isEmpty()) body.put("system", systemPrompt);
                if (config.temperatureEnabled) body.put("temperature", config.temperature);
                if (config.topPEnabled) body.put("top_p", config.topP);
                break;
            }
            case AiModelConfig.GEMINI: {
                JSONArray contents = new JSONArray();
                appendHistory(contents, prior, config.protocol);
                contents.put(userMessage(config.protocol, currentPrompt, images));
                body.remove("model");
                body.put("contents", contents);
                if (!systemPrompt.isEmpty()) body.put("systemInstruction",
                        new JSONObject().put("parts", new JSONArray()
                                .put(new JSONObject().put("text", systemPrompt))));
                JSONObject generation = body.optJSONObject("generationConfig");
                if (generation == null) {
                    if (body.has("generationConfig") && (config.temperatureEnabled || config.topPEnabled
                            || config.maxTokensEnabled || config.hasReasoningEffort()))
                        throw new IllegalArgumentException("自定义参数 generationConfig 必须是 JSON 对象");
                    generation = new JSONObject();
                }
                if (config.temperatureEnabled) generation.put("temperature", config.temperature);
                if (config.topPEnabled) generation.put("topP", config.topP);
                if (config.maxTokensEnabled) generation.put("maxOutputTokens", config.maxTokens);
                if (generation.length() > 0) body.put("generationConfig", generation);
                break;
            }
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
        applyReasoningEffort(config, body);
        return body;
    }

    /** Send native effort values; model support is decided by its service, without probing or fallback. */
    private static void applyReasoningEffort(AiModelConfig config, JSONObject body) throws Exception {
        if (!config.hasReasoningEffort()) return;
        switch (config.protocol) {
            case AiModelConfig.CHAT:
                body.put("reasoning_effort", config.reasoningEffort);
                break;
            case AiModelConfig.RESPONSES:
                objectField(body, "reasoning").put("effort", config.reasoningEffort);
                break;
            case AiModelConfig.ANTHROPIC:
                objectField(body, "output_config").put("effort", config.reasoningEffort);
                break;
            case AiModelConfig.GEMINI:
                objectField(objectField(body, "generationConfig"), "thinkingConfig")
                        .put("thinkingLevel", config.reasoningEffort);
                break;
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    private static JSONObject objectField(JSONObject parent, String key) throws Exception {
        if (parent.has(key)) {
            JSONObject value = parent.optJSONObject(key);
            if (value == null) throw new IllegalArgumentException("自定义参数 " + key + " 必须是 JSON 对象");
            return value;
        }
        JSONObject value = new JSONObject();
        parent.put(key, value);
        return value;
    }

    private static String taskPrompt(String prompt, String secondary) {
        if (secondary.trim().isEmpty()) return prompt;
        return "【本次任务要求】\n" + secondary + "\n\n【本次输入】\n" + prompt;
    }

    private static void appendHistory(JSONArray target, List<HistoryTurn> history, String protocol) throws Exception {
        for (HistoryTurn turn : history) {
            target.put(userMessage(protocol, withDocument(turn.prompt, turn.document), turn.images));
            JSONObject answer = new JSONObject();
            if (AiModelConfig.GEMINI.equals(protocol)) {
                answer.put("role", "model").put("parts", new JSONArray()
                        .put(new JSONObject().put("text", turn.answer)));
            } else answer.put("role", "assistant").put("content", turn.answer);
            target.put(answer);
        }
    }

    private static JSONObject userMessage(String protocol, String prompt, List<ChatImage> images) throws Exception {
        JSONObject message = new JSONObject().put("role", "user");
        boolean gemini = AiModelConfig.GEMINI.equals(protocol);
        if (!gemini && images.isEmpty()) return message.put("content", prompt);
        JSONArray parts = new JSONArray();
        if (!prompt.trim().isEmpty() || images.isEmpty()) {
            JSONObject text = new JSONObject().put("text", prompt);
            if (!gemini) text.put("type", AiModelConfig.RESPONSES.equals(protocol) ? "input_text" : "text");
            parts.put(text);
        }
        for (ChatImage image : images) {
            switch (protocol) {
                case AiModelConfig.CHAT:
                    parts.put(new JSONObject().put("type", "image_url").put("image_url",
                            new JSONObject().put("url", image.dataUrl())));
                    break;
                case AiModelConfig.RESPONSES:
                    parts.put(new JSONObject().put("type", "input_image").put("image_url", image.dataUrl()));
                    break;
                case AiModelConfig.ANTHROPIC:
                    parts.put(new JSONObject().put("type", "image").put("source", new JSONObject()
                            .put("type", "base64").put("media_type", image.mimeType).put("data", image.base64Data)));
                    break;
                case AiModelConfig.GEMINI:
                    parts.put(new JSONObject().put("inline_data", new JSONObject()
                            .put("mime_type", image.mimeType).put("data", image.base64Data)));
                    break;
                default: throw new IllegalArgumentException("不支持的接口协议");
            }
        }
        return message.put(gemini ? "parts" : "content", parts);
    }

    private static String parseReply(String protocol, String json) throws Exception {
        JSONObject response = new JSONObject(json);
        switch (protocol) {
            case AiModelConfig.CHAT:
                return response.getJSONArray("choices").getJSONObject(0)
                        .getJSONObject("message").getString("content").trim();
            case AiModelConfig.RESPONSES:
                String direct = response.optString("output_text", "").trim();
                if (!direct.isEmpty()) return direct;
                StringBuilder output = new StringBuilder();
                JSONArray items = response.optJSONArray("output");
                if (items != null) for (int i = 0; i < items.length(); i++) {
                    JSONArray blocks = items.optJSONObject(i) == null ? null : items.optJSONObject(i).optJSONArray("content");
                    if (blocks == null) continue;
                    for (int j = 0; j < blocks.length(); j++) {
                        JSONObject block = blocks.optJSONObject(j);
                        if (block != null && ("output_text".equals(block.optString("type"))
                                || "text".equals(block.optString("type")))) output.append(block.optString("text"));
                    }
                }
                return output.toString().trim();
            case AiModelConfig.ANTHROPIC:
                JSONArray content = response.getJSONArray("content");
                StringBuilder text = new StringBuilder();
                for (int i = 0; i < content.length(); i++) {
                    JSONObject block = content.optJSONObject(i);
                    if (block != null && "text".equals(block.optString("type"))) text.append(block.optString("text"));
                }
                return text.toString().trim();
            case AiModelConfig.GEMINI:
                JSONArray parts = response.getJSONArray("candidates").getJSONObject(0).getJSONObject("content")
                        .getJSONArray("parts");
                StringBuilder answer = new StringBuilder();
                for (int index = 0; index < parts.length(); index++) {
                    JSONObject part = parts.optJSONObject(index);
                    if (part != null && !part.optBoolean("thought", false) && part.opt("text") instanceof String)
                        answer.append(part.getString("text"));
                }
                return answer.toString().trim();
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
    }

    /** A relay may answer a streaming request with JSON on that same connection, never a second POST. */
    private static void validateCompletedJson(String protocol, String json, int status) throws Exception {
        JSONObject envelope = new JSONObject(json);
        if (envelope.has("error") && !envelope.isNull("error"))
            throw new ReplyFailure(ReplyFailure.Kind.INCOMPLETE, status, -1);
        boolean incomplete = false;
        switch (protocol) {
            case AiModelConfig.RESPONSES:
                incomplete |= envelope.has("status") && !"completed".equals(envelope.opt("status"));
                break;
            case AiModelConfig.CHAT: {
                JSONObject choice = envelope.getJSONArray("choices").getJSONObject(0);
                incomplete |= choice.has("finish_reason") && !"stop".equals(choice.opt("finish_reason"));
                break;
            }
            case AiModelConfig.ANTHROPIC:
                incomplete |= envelope.has("stop_reason") && !"end_turn".equals(envelope.opt("stop_reason"))
                        && !"stop_sequence".equals(envelope.opt("stop_reason"));
                break;
            case AiModelConfig.GEMINI: {
                JSONObject feedback = envelope.optJSONObject("promptFeedback");
                incomplete |= feedback != null && feedback.has("blockReason") && !feedback.isNull("blockReason");
                JSONObject candidate = envelope.getJSONArray("candidates").getJSONObject(0);
                incomplete |= candidate.has("finishReason") && !"STOP".equals(candidate.opt("finishReason"));
                break;
            }
            default: throw new IllegalArgumentException("不支持的接口协议");
        }
        if (incomplete) throw new ReplyFailure(ReplyFailure.Kind.INCOMPLETE, status, -1);
    }

    private static List<String> parseModelIds(String protocol, String json) throws Exception {
        JSONObject response = new JSONObject(json);
        JSONArray items = AiModelConfig.GEMINI.equals(protocol)
                ? response.optJSONArray("models") : response.optJSONArray("data");
        if (items == null) items = response.optJSONArray("models");
        if (items == null) throw new IllegalStateException("模型列表格式无法识别");
        java.util.TreeSet<String> sorted = new java.util.TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) continue;
            if (AiModelConfig.GEMINI.equals(protocol)) {
                JSONArray supported = item.optJSONArray("supportedGenerationMethods");
                if (supported != null && !contains(supported, "generateContent")) continue;
            }
            String id = AiModelConfig.GEMINI.equals(protocol)
                    ? item.optString("name", "") : item.optString("id", item.optString("name", ""));
            if (id.startsWith("models/")) id = id.substring("models/".length());
            if (!id.isEmpty() && id.length() <= 256) sorted.add(id);
        }
        return Collections.unmodifiableList(new ArrayList<>(sorted));
    }

    private static boolean contains(JSONArray values, String expected) {
        for (int i = 0; i < values.length(); i++) if (expected.equals(values.optString(i))) return true;
        return false;
    }

    private static void configure(HttpURLConnection connection, AiModelConfig config) {
        int timeout = config.timeoutSeconds * 1000;
        connection.setConnectTimeout(timeout);
        connection.setReadTimeout(timeout);
        connection.setInstanceFollowRedirects(false);
    }

    private static void setAuthentication(HttpURLConnection connection, AiModelConfig config,
                                          String requestUrl) throws Exception {
        for (Map.Entry<String, String> header : config.headers().entrySet()) {
            connection.setRequestProperty(header.getKey(), header.getValue());
        }
        if (config.apiKey.isEmpty()) return;
        switch (config.protocol) {
            case AiModelConfig.ANTHROPIC:
                connection.setRequestProperty("x-api-key", config.apiKey);
                connection.setRequestProperty("anthropic-version", "2023-06-01");
                break;
            case AiModelConfig.GEMINI:
                connection.setRequestProperty("x-goog-api-key", config.apiKey);
                break;
            default:
                connection.setRequestProperty("Authorization", "Bearer " + config.apiKey);
        }
    }

    private static String readResponse(HttpURLConnection connection, int status) throws Exception {
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        if (stream == null) return "";
        try (InputStream input = stream) { return readBounded(input, MAX_RESPONSE_BYTES); }
    }

    private static ReplyFailure httpFailure(int status) {
        return new ReplyFailure(ReplyFailure.Kind.HTTP, status, -1);
    }

    public static String readBounded(InputStream input, int maximumBytes) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (output.size() + read > maximumBytes) throw new ReplyFailure(ReplyFailure.Kind.OVERLONG, 0, -1);
            output.write(buffer, 0, read);
        }
        return output.toString(StandardCharsets.UTF_8.name());
    }

    private static String appendSuffix(String base, String suffix) {
        String clean = base.replaceAll("/+$", "");
        return clean.endsWith("/" + suffix) || clean.equals(suffix) ? clean : clean + "/" + suffix;
    }

    private static String stripOperation(String endpoint, String protocol) {
        String base = endpoint.replaceAll("/+$", "");
        if (AiModelConfig.GEMINI.equals(protocol))
            base = base.replaceFirst("/models/[^/]+:(?:streamGenerateContent|generateContent)(?:\\?alt=sse)?$", "");
        String[] endings;
        switch (protocol) {
            case AiModelConfig.CHAT: endings = new String[]{"/chat/completions"}; break;
            case AiModelConfig.RESPONSES: endings = new String[]{"/responses"}; break;
            case AiModelConfig.ANTHROPIC: endings = new String[]{"/messages"}; break;
            case AiModelConfig.GEMINI: endings = new String[]{"/generateContent", "/models"}; break;
            default: endings = new String[0];
        }
        for (String ending : endings) {
            if (base.endsWith(ending)) return base.substring(0, base.length() - ending.length());
        }
        return base;
    }

    private static String encodePath(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
