package com.douai.module.ai;

import com.douai.module.diagnostics.DouAiLog;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.net.ssl.HttpsURLConnection;

/** One bounded HTTPS exchange for explicitly authorized AI extensions. */
public final class AiExtensionHttp {
    public enum Kind { DEADLINE, TRANSPORT, RESPONSE_LIMIT, CANCELLED, BUSY }

    public static final class Failure extends IOException {
        public final Kind kind;
        public final boolean mayHaveSent;

        public Failure(Kind kind, boolean mayHaveSent) {
            super("HTTPS exchange " + kind.name());
            this.kind = kind;
            this.mayHaveSent = mayHaveSent;
        }
    }

    public interface Headers { String get(String name); }

    public interface ResponseReader<T> {
        T read(int status, Headers headers, InputStream body) throws IOException;
    }

    @FunctionalInterface interface ConnectionFactory {
        HttpsURLConnection open(URI endpoint) throws IOException;
    }

    private enum ImagePhase {
        QUEUED, OPEN_CONNECTION, OPEN_OUTPUT, WRITE_REQUEST, WAIT_STATUS,
        STATUS_RECEIVED, STATUS_UNAVAILABLE, READ_RESPONSE;

        private final String label = name().toLowerCase(Locale.ROOT);
    }

    private static final int MAX_REQUEST_BYTES = 1024 * 1024;
    private static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
    private static final ThreadPoolExecutor IO = pool(4, "DouAi-extension-http");
    private static final ThreadPoolExecutor CLEANUP = pool(2, "DouAi-extension-cleanup");

    private AiExtensionHttp() {}

    public static URI exactEndpoint(String value) {
        try {
            if (value == null || value.length() > 2048) throw new IllegalArgumentException();
            URI uri = new URI(value);
            if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                    || uri.getPort() == 0 || uri.getPort() > 65535 || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !uri.equals(uri.normalize())) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (Exception invalid) {
            throw new IllegalArgumentException("请输入固定的 HTTPS 目标地址，不得包含账户信息、查询参数或片段");
        }
    }

    public static <T> T request(String endpoint, String method, Map<String, String> headers, byte[] body,
                                long deadlineNanos, int maxResponseBytes, ResponseReader<T> reader) throws Failure {
        return request(endpoint, method, headers, body, deadlineNanos, maxResponseBytes, reader, null);
    }

    /** Image generation supplies its existing trace for the worker's local I/O phases. */
    public static <T> T request(String endpoint, String method, Map<String, String> headers, byte[] body,
                                long deadlineNanos, int maxResponseBytes, ResponseReader<T> reader,
                                DouAiLog.Operation imageTrace) throws Failure {
        return request(endpoint, method, headers, body, deadlineNanos, maxResponseBytes, reader, imageTrace,
                uri -> (HttpsURLConnection) uri.toURL().openConnection());
    }

    static <T> T request(String endpoint, String method, Map<String, String> headers, byte[] body,
                         long deadlineNanos, int maxResponseBytes, ResponseReader<T> reader,
                         DouAiLog.Operation imageTrace, ConnectionFactory factory) throws Failure {
        URI uri = exactEndpoint(endpoint);
        if (maxResponseBytes < 1 || maxResponseBytes > MAX_RESPONSE_BYTES
                || body != null && (body.length > MAX_REQUEST_BYTES || !"POST".equals(method))
                || !("GET".equals(method) || "POST".equals(method) || "DELETE".equals(method))) {
            throw new IllegalArgumentException("无效的 HTTP 请求设置");
        }
        Attempt attempt = new Attempt(imageTrace);
        try {
            return exchangeOnWorker(uri, method, headers, body, deadlineNanos, maxResponseBytes, reader, attempt, factory);
        } catch (Failure failure) {
            attempt.imageFailure(failure.kind);
            throw failure;
        }
    }

    private static <T> T exchangeOnWorker(URI uri, String method, Map<String, String> headers, byte[] body,
                                          long deadlineNanos, int maxResponseBytes, ResponseReader<T> reader,
                                          Attempt attempt, ConnectionFactory factory) throws Failure {
        attempt.remainingMillis(deadlineNanos);
        Future<T> future;
        try {
            future = IO.submit(() -> exchange(uri, method, headers, body, deadlineNanos,
                    maxResponseBytes, reader, attempt, factory));
        } catch (RejectedExecutionException busy) {
            throw new Failure(Kind.BUSY, false);
        }
        try {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) throw new TimeoutException();
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            attempt.cancel();
            future.cancel(true);
            throw attempt.failure(Kind.DEADLINE);
        } catch (InterruptedException cancelled) {
            attempt.cancel();
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw attempt.failure(Kind.CANCELLED);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Failure) throw (Failure) cause;
            throw attempt.failure(Kind.TRANSPORT);
        }
    }

    public static String readUtf8(InputStream body) throws IOException {
        return StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(readBytes(body))).toString();
    }

    /** Reads the bounded stream passed by request to a ResponseReader. */
    public static byte[] readBytes(InputStream body) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[2048];
        int count;
        while ((count = body.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static <T> T exchange(URI uri, String method, Map<String, String> headers, byte[] body,
                                  long deadline, int maxResponseBytes, ResponseReader<T> reader,
                                  Attempt attempt, ConnectionFactory factory) throws Failure {
        HttpsURLConnection connection = null;
        try {
            attempt.imagePhase(ImagePhase.OPEN_CONNECTION, 0);
            connection = factory.open(uri);
            attempt.attach(connection, deadline);
            connection.setRequestMethod(method);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);
            connection.setConnectTimeout(attempt.remainingMillis(deadline));
            connection.setReadTimeout(attempt.remainingMillis(deadline));
            for (Map.Entry<String, String> header : headers.entrySet()) {
                connection.setRequestProperty(header.getKey(), header.getValue());
            }
            connection.setRequestProperty("Accept-Encoding", "identity");
            connection.setRequestProperty("Connection", "close");
            if (body != null) {
                connection.setFixedLengthStreamingMode(body.length);
                connection.setDoOutput(true);
                // getOutputStream can perform DNS, TCP and TLS on a lazy HTTPS connection.
                attempt.imagePhase(ImagePhase.OPEN_OUTPUT, 0);
                attempt.beginSend(deadline);
                try (OutputStream stream = connection.getOutputStream()) {
                    attempt.imagePhase(ImagePhase.WRITE_REQUEST, 0);
                    attempt.remainingMillis(deadline);
                    stream.write(body);
                }
            } else {
                attempt.beginSend(deadline);
            }
            attempt.imagePhase(ImagePhase.WAIT_STATUS, 0);
            int status = connection.getResponseCode();
            if (status >= 100 && status <= 599) attempt.imagePhase(ImagePhase.STATUS_RECEIVED, status);
            else attempt.imagePhase(ImagePhase.STATUS_UNAVAILABLE, 0);
            attempt.remainingMillis(deadline);
            if (status < 200 || status >= 300) {
                return reader.read(status, connection::getHeaderField, new ByteArrayInputStream(new byte[0]));
            }
            // MCP can stop reading SSE after the target response without waiting for server EOF.
            attempt.imagePhase(ImagePhase.READ_RESPONSE, 0);
            try (InputStream raw = connection.getInputStream();
                 InputStream input = new BoundedInput(raw, maxResponseBytes, attempt, deadline)) {
                T result = reader.read(status, connection::getHeaderField, input);
                attempt.remainingMillis(deadline);
                return result;
            }
        } catch (Failure failure) {
            throw failure;
        } catch (SocketTimeoutException timeout) {
            throw attempt.failure(Kind.DEADLINE);
        } catch (IOException | RuntimeException failure) {
            throw attempt.failure(Kind.TRANSPORT);
        } finally {
            attempt.detach();
            if (connection != null) {
                try { connection.disconnect(); }
                catch (RuntimeException ignored) { }
            }
        }
    }

    private static ThreadPoolExecutor pool(int threads, String name) {
        return new ThreadPoolExecutor(0, threads, 20, TimeUnit.SECONDS, new SynchronousQueue<>(), task -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static final class Attempt {
        private final DouAiLog.Operation imageTrace;
        private final long startedNanos;
        private ImagePhase imagePhase = ImagePhase.QUEUED;
        private int imageStatus;
        private volatile boolean cancelled;
        private volatile boolean sent;
        private HttpsURLConnection connection;

        Attempt(DouAiLog.Operation imageTrace) {
            this.imageTrace = imageTrace;
            this.startedNanos = imageTrace == null ? 0 : System.nanoTime();
        }

        void imagePhase(ImagePhase phase, int status) {
            if (imageTrace == null) return;
            synchronized (this) {
                if (cancelled) return;
                imagePhase = phase;
                if (status != 0) imageStatus = status;
                imageTrace.stage("image_http_" + phase.label, imageEvidence());
            }
        }

        void imageFailure(Kind kind) {
            if (imageTrace == null) return;
            synchronized (this) {
                imageTrace.stage("image_http_failure", imageEvidence() + ";kind=" + kind.name().toLowerCase(Locale.ROOT));
            }
        }

        private String imageEvidence() {
            String status = imageStatus == 0 ? "" : ";http_status=" + imageStatus;
            return "phase=" + imagePhase.label + status + ";elapsed_ms="
                    + TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
        }

        synchronized void attach(HttpsURLConnection opened, long deadline) throws Failure {
            remainingMillis(deadline);
            connection = opened;
        }

        synchronized void detach() { connection = null; }

        synchronized void beginSend(long deadline) throws Failure {
            remainingMillis(deadline);
            sent = true;
        }

        void cancel() {
            HttpsURLConnection active;
            synchronized (this) {
                cancelled = true;
                active = connection;
            }
            if (active != null) {
                try { CLEANUP.execute(active::disconnect); }
                catch (RejectedExecutionException busy) { /* The I/O task owns final cleanup. */ }
            }
        }

        int remainingMillis(long deadline) throws Failure {
            if (cancelled || Thread.currentThread().isInterrupted()) throw failure(Kind.CANCELLED);
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw failure(Kind.DEADLINE);
            return (int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(remaining)));
        }

        Failure failure(Kind kind) { return new Failure(kind, sent); }
    }

    private static final class BoundedInput extends InputStream {
        private final InputStream input;
        private final Attempt attempt;
        private final long deadline;
        private int remaining;

        BoundedInput(InputStream input, int maximum, Attempt attempt, long deadline) {
            this.input = input;
            this.remaining = maximum;
            this.attempt = attempt;
            this.deadline = deadline;
        }

        @Override public int read() throws IOException {
            attempt.remainingMillis(deadline);
            int value = input.read();
            if (value >= 0) consume(1);
            return value;
        }

        @Override public int read(byte[] target, int offset, int length) throws IOException {
            attempt.remainingMillis(deadline);
            int count = input.read(target, offset, Math.min(length, remaining + 1));
            if (count > 0) consume(count);
            return count;
        }

        private void consume(int count) throws Failure {
            remaining -= count;
            if (remaining < 0) throw attempt.failure(Kind.RESPONSE_LIMIT);
        }
    }
}
