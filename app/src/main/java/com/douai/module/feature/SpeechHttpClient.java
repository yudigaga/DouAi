package com.douai.module.feature;

import android.net.Uri;
import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.diagnostics.DouAiLog;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, cancellable transport shared by speech providers and voice lists. */
public final class SpeechHttpClient {
    public static final int MAX_AUDIO_BYTES = 20 * 1024 * 1024;
    public static final int MAX_TEXT_BYTES = 2 * 1024 * 1024;
    static final int MAX_REFERENCE_REQUEST_BYTES = 10 * 1024 * 1024;
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]+");
    private static final Pattern CHARSET = Pattern.compile(
            "(?:^|;)\\s*charset\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", Pattern.CASE_INSENSITIVE);
    private static final ScheduledThreadPoolExecutor DEADLINES = deadlineExecutor();

    private static ScheduledThreadPoolExecutor deadlineExecutor() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, work -> {
            Thread thread = new Thread(work, "douai-tts-deadline"); thread.setDaemon(true); return thread;
        });
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    static final class ProviderHttpFailure extends IOException {
        final int status, retryAfterSeconds;
        ProviderHttpFailure(String operation, int status, int retryAfterSeconds) {
            super(operation + " 返回 HTTP " + status);
            this.status = status; this.retryAfterSeconds = retryAfterSeconds;
        }
    }

    private SpeechHttpClient() { }

    public static String resolveTemplate(String template, String text, String voice) {
        ThirdPartyRequestTemplate.parse(template, "{text}", "{voice}");
        String resolved = template.trim().replace("{text}", Uri.encode(text == null ? "" : text))
                .replace("{voice}", Uri.encode(voice == null ? "" : voice));
        endpoint(resolved);
        return resolved;
    }

    public static final class Resource {
        public final byte[] bytes;
        public final String contentType;
        final java.io.File file;
        private final byte[] header, meaningful;
        private final long length;

        Resource(byte[] bytes, String contentType) {
            this.bytes = bytes;
            this.contentType = contentType == null ? "" : contentType;
            this.file = null;
            this.header = bytes;
            int start = 0; while (start < bytes.length && (bytes[start] & 255) <= 32) start++;
            this.meaningful = java.util.Arrays.copyOfRange(bytes, start, Math.min(bytes.length, start + 16));
            this.length = bytes.length;
        }

        private Resource(java.io.File file, Probe probe, long length, String contentType) {
            this.bytes = null; this.file = file; this.length = length;
            this.header = java.util.Arrays.copyOf(probe.first, probe.firstSize);
            this.meaningful = java.util.Arrays.copyOf(probe.meaningful, probe.meaningfulSize);
            this.contentType = contentType == null ? "" : contentType;
        }

        long sizeBytes() { return length; }

        byte[] readBytes() throws IOException {
            if (bytes != null) return bytes;
            if (file.length() > MAX_AUDIO_BYTES) throw tooLarge(MAX_AUDIO_BYTES);
            return java.nio.file.Files.readAllBytes(file.toPath());
        }

        public String text() {
            if (length > MAX_TEXT_BYTES) throw new IllegalArgumentException("第三方语音文本响应超过 2 MiB");
            byte[] textBytes;
            try { textBytes = readBytes(); }
            catch (IOException failure) { throw new IllegalArgumentException("第三方语音响应无法读取"); }
            Charset charset = StandardCharsets.UTF_8;
            Matcher declared = CHARSET.matcher(contentType);
            if (declared.find()) {
                try { charset = Charset.forName(declared.group(1) == null ? declared.group(2) : declared.group(1)); }
                catch (IllegalArgumentException ignored) {
                    throw new IllegalArgumentException("第三方语音响应使用了不支持的字符编码");
                }
            }
            try {
                String value = charset.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(textBytes)).toString();
                return value.startsWith("\uFEFF") ? value.substring(1) : value;
            } catch (CharacterCodingException ignored) {
                throw new IllegalArgumentException("第三方语音响应不是有效的 JSON/TXT");
            }
        }

        public boolean isAudio() {
            byte[] bytes = header;
            if (starts("RIFF") && bytes.length >= 12 && ascii(8, "WAVE")) return true;
            if (starts("ID3") || starts("OggS") || starts("fLaC")
                    || (bytes.length >= 8 && ascii(4, "ftyp"))
                    || (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224)) return true;
            String type = contentType.toLowerCase(Locale.ROOT);
            if (!type.startsWith("audio/") && !type.startsWith("application/octet-stream")) return false;
            if (meaningful.length == 0) return false;
            byte first = meaningful[0];
            return first != '{' && first != '[' && first != '"' && first != '<'
                    && !ascii(meaningful, 0, "http://") && !ascii(meaningful, 0, "https://");
        }

        public String audioExtension() {
            byte[] bytes = header;
            if (starts("RIFF") && bytes.length >= 12 && ascii(8, "WAVE")) return ".wav";
            if (starts("OggS")) return ".ogg";
            if (starts("fLaC")) return ".flac";
            if (bytes.length >= 8 && ascii(4, "ftyp")) return ".m4a";
            if (starts("ID3")) return ".mp3";
            if (bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224) {
                return (bytes[1] & 246) == 240 ? ".aac" : ".mp3";
            }
            String mime = contentType.toLowerCase(Locale.ROOT).split(";", 2)[0].trim();
            if (mime.equals("audio/wav") || mime.equals("audio/x-wav") || mime.equals("audio/wave")) return ".wav";
            if (mime.equals("audio/mpeg") || mime.equals("audio/mp3")) return ".mp3";
            if (mime.equals("audio/mp4") || mime.equals("audio/x-m4a")) return ".m4a";
            if (mime.equals("audio/aac")) return ".aac";
            if (mime.equals("audio/ogg")) return ".ogg";
            if (mime.equals("audio/flac")) return ".flac";
            return ".audio";
        }

        private boolean starts(String value) { return ascii(0, value); }
        private boolean ascii(int offset, String value) {
            return ascii(header, offset, value);
        }
        private static boolean ascii(byte[] bytes, int offset, String value) {
            if (offset + value.length() > bytes.length) return false;
            for (int i = 0; i < value.length(); i++) if (bytes[offset + i] != value.charAt(i)) return false;
            return true;
        }
    }

    private static final class Probe {
        final byte[] first = new byte[16], meaningful = new byte[16];
        int firstSize, meaningfulSize;
        boolean found;
        void accept(byte[] buffer, int count) {
            int copied = Math.min(count, first.length - firstSize);
            System.arraycopy(buffer, 0, first, firstSize, copied); firstSize += copied;
            if (meaningfulSize == meaningful.length) return;
            for (int i = 0; i < count && meaningfulSize < meaningful.length; i++) {
                if (!found && (buffer[i] & 255) <= 32) continue;
                found = true; meaningful[meaningfulSize++] = buffer[i];
            }
        }
    }

    public static final class Request implements AutoCloseable {
        private boolean cancelled;
        private boolean inProgress;
        private HttpURLConnection connection;
        private ScheduledFuture<?> deadlineTask;
        private long deadlineGeneration;
        private boolean deadlineExpired;
        private String deadlineMessage = "语音请求超时";

        void limitDeadline(long absoluteNanos, String provider) {
            synchronized (this) {
                ensureActive();
                clearDeadline();
                deadlineMessage = providerOperation(provider, "POST").replace(" POST", "") + "请求超时";
                long generation = ++deadlineGeneration;
                deadlineTask = DEADLINES.schedule(() -> expireDeadline(generation),
                        Math.max(0, absoluteNanos - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
        }

        private void expireDeadline(long generation) {
            HttpURLConnection active;
            synchronized (this) {
                if (generation != deadlineGeneration || cancelled) return;
                deadlineExpired = true; active = connection;
            }
            if (active != null) active.disconnect();
        }

        synchronized void clearDeadline() {
            deadlineGeneration++;
            if (deadlineTask != null) deadlineTask.cancel(false);
            deadlineTask = null; deadlineExpired = false;
        }

        public Resource fetchResource(String url, String headers) throws IOException {
            return fetch(url, headers, MAX_AUDIO_BYTES);
        }

        Resource fetchResource(String url, String headers, java.io.File destination) throws IOException {
            return fetch(url, headers, MAX_AUDIO_BYTES, null, destination);
        }

        public String fetchText(String url, String headers) throws IOException {
            String text = fetch(url, headers, MAX_TEXT_BYTES).text();
            ensureActive();
            return text;
        }

        public String postJson(String url, String headers, String body) throws IOException {
            String text = fetch(url, headers, MAX_TEXT_BYTES, body).text();
            ensureActive();
            return text;
        }

        Resource postAudioResource(String url, String headers, String body, java.io.File destination) throws IOException {
            return postAudioResource(url, headers, body, destination, "breeze");
        }

        Resource postAudioResource(String url, String headers, String body, java.io.File destination, String provider) throws IOException {
            String operation = providerOperation(provider, "POST");
            return fetch(url, headers, MAX_AUDIO_BYTES, body, destination, operation, true);
        }

        public String postProviderJson(String url, String headers, String body, String provider, int expectedStatus) throws IOException {
            if (expectedStatus != 200 && expectedStatus != 202) throw new IllegalArgumentException("语音响应状态设置无效");
            String operation = providerOperation(provider, "POST");
            String text = fetch(url, headers, MAX_TEXT_BYTES, encodeBody(body, operation), null,
                    operation, true, "application/json; charset=utf-8", expectedStatus, MAX_TEXT_BYTES).text();
            ensureActive();
            return text;
        }

        public String getProviderJson(String url, String headers, String provider) throws IOException {
            String text = fetch(url, headers, MAX_TEXT_BYTES, (byte[]) null, null, providerOperation(provider, "GET"),
                    true, null, 200, MAX_TEXT_BYTES).text();
            ensureActive();
            return text;
        }

        public Resource getProviderAudio(String url, String headers, java.io.File destination, String provider) throws IOException {
            return fetch(url, headers, MAX_AUDIO_BYTES, (byte[]) null, destination, providerOperation(provider, "GET"),
                    true, null, 200, MAX_TEXT_BYTES);
        }

        Resource postReferenceAudio(String url, String headers, byte[] body, java.io.File destination) throws IOException {
            return fetch(url, headers, MAX_AUDIO_BYTES, body, destination, providerOperation("fish", "POST"),
                    true, "application/msgpack", 200, MAX_REFERENCE_REQUEST_BYTES);
        }

        private static String providerOperation(String provider, String method) {
            if (!"breeze".equals(provider) && !"fish".equals(provider)) throw new IllegalArgumentException("语音来源无效");
            return ("fish".equals(provider) ? "Fish Audio" : "Breeze Blue") + " 语音 " + method;
        }

        private static byte[] encodeBody(String body, String operation) {
            if (body == null || body.length() > MAX_TEXT_BYTES) throw new IllegalArgumentException(operation + " 请求过大");
            return body.getBytes(StandardCharsets.UTF_8);
        }

        public List<VoiceCatalog.Entry> fetchVoices(SpeechSettings settings) throws IOException {
            return observeCatalog("third_party_voice_catalog", () -> {
                String url = resolveTemplate(settings.voiceGetTemplate, "", settings.selectedVoice);
                String body = fetchText(url, settings.voiceHeaders);
                ensureActive();
                List<VoiceCatalog.Entry> entries = VoiceCatalog.parse(body, settings);
                ensureActive();
                return entries;
            });
        }

        private Resource fetch(String url, String headersJson, int limit) throws IOException {
            return fetch(url, headersJson, limit, null);
        }

        private Resource fetch(String url, String headersJson, int limit, String body) throws IOException {
            return fetch(url, headersJson, limit, body, null);
        }

        private Resource fetch(String url, String headersJson, int limit, String body, java.io.File destination) throws IOException {
            return fetch(url, headersJson, limit, body, destination,
                    body == null ? "第三方语音 GET" : "MiniMax 语音 POST", false);
        }

        private Resource fetch(String url, String headersJson, int limit, String body, java.io.File destination,
                               String operation, boolean completeAudio) throws IOException {
            return fetch(url, headersJson, limit, body == null ? null : encodeBody(body, operation), destination,
                    operation, completeAudio, "application/json; charset=utf-8", completeAudio ? 200 : 0, MAX_TEXT_BYTES);
        }

        private Resource fetch(String url, String headersJson, int limit, byte[] body, java.io.File destination,
                               String operation, boolean completeAudio, String contentType, int expectedStatus,
                               int requestLimit) throws IOException {
            URI endpoint = endpoint(url);
            Map<String, String> headers = headers(headersJson);
            synchronized (this) {
                ensureActive();
                if (inProgress) throw new IllegalStateException("第三方语音请求正在进行");
                inProgress = true;
            }
            HttpURLConnection opened = null;
            transportEvent("request_started", "provider_request", "method=" + (body == null ? "GET" : "POST")
                    + "; endpoint_ref=" + DouAiLog.reference(url) + "; result=pending");
            long deadline = System.nanoTime() + 45_000_000_000L;
            boolean complete = false;
            try {
                opened = (HttpURLConnection) endpoint.toURL().openConnection();
                synchronized (this) { ensureActive(); connection = opened; }
                opened.setRequestMethod(body == null ? "GET" : "POST");
                opened.setInstanceFollowRedirects(false);
                opened.setConnectTimeout(15_000);
                opened.setReadTimeout(30_000);
                opened.setRequestProperty("Accept", "audio/*, application/json, text/plain, */*");
                for (Map.Entry<String, String> header : headers.entrySet()) {
                    opened.setRequestProperty(header.getKey(), header.getValue());
                }
                if (body != null) {
                    if (body.length > requestLimit) throw new IllegalArgumentException(
                            completeAudio ? operation.replace(" POST", "") + "请求过大" : "MiniMax 语音请求过大");
                    opened.setDoOutput(true);
                    opened.setFixedLengthStreamingMode(body.length);
                    opened.setRequestProperty("Content-Type", contentType);
                    try (OutputStream output = opened.getOutputStream()) {
                        ensureActive();
                        output.write(body);
                    }
                }
                int status = opened.getResponseCode();
                transportEvent("http_response", "transport_only", "status=" + status + "; result=validating");
                ensureActive();
                if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                if (status >= 300 && status < 400) {
                    throw new IllegalArgumentException(operation + " 不跟随重定向，请填写最终地址");
                }
                if (status < 200 || status >= 300 || expectedStatus != 0 && status != expectedStatus) {
                    if (expectedStatus != 0) throw new ProviderHttpFailure(operation, status, retryAfter(opened.getHeaderField("Retry-After")));
                    throw new IOException(operation + " 返回 HTTP " + status);
                }
                if (opened.getContentLengthLong() > limit) throw tooLarge(limit);
                ByteArrayOutputStream memory = destination == null ? new ByteArrayOutputStream() : null;
                Probe probe = new Probe();
                long total = 0;
                try (InputStream input = opened.getInputStream(); OutputStream output = destination == null
                        ? memory : new java.io.FileOutputStream(destination)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        ensureActive();
                        if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                        if (count > limit - total) throw tooLarge(limit);
                        probe.accept(buffer, count); total += count;
                        output.write(buffer, 0, count);
                    }
                }
                ensureActive();
                if (System.nanoTime() > deadline) throw new SocketTimeoutException();
                if (completeAudio && opened.getContentLengthLong() >= 0 && total != opened.getContentLengthLong()) {
                    throw new IllegalArgumentException(operation.replace(" POST", "").replace(" GET", "") + "响应不完整");
                }
                if (total == 0) throw new IllegalArgumentException("第三方语音响应为空");
                transportEvent("body_read", "bounded_response", "bytes=" + total + "; result=validating");
                Resource resource = destination == null ? new Resource(memory.toByteArray(), opened.getContentType())
                        : new Resource(destination, probe, total, opened.getContentType());
                complete = true;
                return resource;
            } catch (SocketTimeoutException ignored) {
                ensureActive();
                throw new IOException(operation + " 请求超时");
            } catch (IOException failure) {
                ensureActive();
                if (failure instanceof ProviderHttpFailure) throw failure;
                // Never retain URL-bearing transport exceptions or provider response bodies.
                String message = failure.getMessage();
                if (message != null && message.matches("(?:第三方语音 GET|MiniMax 语音 POST|(?:Breeze Blue|Fish Audio) 语音 (?:GET|POST)) 返回 HTTP [0-9]{3}")) {
                    throw new IOException(message);
                }
                throw new IOException(operation + " 请求失败");
            } catch (IllegalArgumentException failure) {
                ensureActive();
                if (failure.getMessage() != null && (failure.getMessage().startsWith("第三方语音")
                        || failure.getMessage().startsWith("MiniMax 语音")
                        || failure.getMessage().startsWith("Breeze Blue 语音")
                        || failure.getMessage().startsWith("Fish Audio 语音"))) throw failure;
                throw new IllegalArgumentException(operation + " 请求无效");
            } finally {
                synchronized (this) { connection = null; inProgress = false; }
                if (opened != null) opened.disconnect();
                if (!complete && destination != null) {
                    try { java.nio.file.Files.deleteIfExists(destination.toPath()); } catch (IOException ignored) { }
                }
            }
        }

        public void cancel() {
            HttpURLConnection active;
            synchronized (this) { cancelled = true; clearDeadline(); active = connection; }
            if (active != null) active.disconnect();
        }

        public synchronized void ensureActive() {
            if (cancelled || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("第三方语音请求已停止");
            }
            if (deadlineExpired) throw new IllegalArgumentException(deadlineMessage);
        }

        @Override public void close() { cancel(); }

        private static int retryAfter(String header) {
            try {
                if (header != null && header.matches("[0-9]+")) return (int) Math.min(3600, Long.parseLong(header));
            } catch (NumberFormatException ignored) { }
            return 1;
        }
    }

    interface CatalogWork<T> { List<T> run() throws IOException; }
    interface CatalogResultWork<T> { T run() throws IOException; }
    private static void transportEvent(String event, String reason, String evidence) {
        DouAiLog.Operation operation = DouAiLog.current();
        if (operation != null) operation.stage(event, "reason=" + reason + "; " + evidence);
        else DouAiLog.event("speech_transport", event, reason, evidence);
    }
    static <T> List<T> observeCatalog(String feature, CatalogWork<T> work) throws IOException {
        return observeCatalogResult(feature, work::run,
                result -> "entries=" + result.size() + "; selection_saved=false");
    }

    static <T> T observeCatalogResult(String feature, CatalogResultWork<T> work,
                                     java.util.function.Function<T, String> evidence) throws IOException {
        DouAiLog.Operation operation = DouAiLog.begin(feature, "load_catalog");
        java.util.concurrent.atomic.AtomicReference<T> result = new java.util.concurrent.atomic.AtomicReference<>();
        IOException[] checked = new IOException[1];
        try {
            operation.wrap(() -> {
                try { result.set(work.run()); }
                catch (IOException failure) { checked[0] = failure; }
            }).run();
            if (checked[0] != null) throw checked[0];
            operation.finish(DouAiLog.Outcome.SUCCESS, "catalog_parsed", evidence.apply(result.get()));
            return result.get();
        } catch (IOException | RuntimeException failure) {
            operation.error("catalog_fetch_or_parse", failure);
            operation.finish(failure instanceof CancellationException ? DouAiLog.Outcome.CANCELLED
                            : failure instanceof java.net.SocketTimeoutException || failure.getMessage() != null && failure.getMessage().contains("超时")
                            ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED,
                    failure instanceof CancellationException ? "owner_cancelled" : "catalog_fetch_or_parse_failed", "selection_saved=false");
            throw failure;
        }
    }

    public static String audioUrl(String body, String field) {
        if (body == null || body.length() > MAX_TEXT_BYTES || body.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("第三方语音响应为空或超过 2 MiB");
        }
        // Direct TXT/JSON string links have no named field; a JSON field selection does not apply.
        String direct = body.trim();
        if (direct.startsWith("\uFEFF")) direct = direct.substring(1).trim();
        if (direct.startsWith("\"")) {
            // Android's strict JsonReader requires an array/object root, so wrap the scalar.
            try (JsonReader reader = new JsonReader(new StringReader("[" + direct + "]"))) {
                reader.setLenient(false);
                reader.beginArray();
                if (reader.peek() != JsonToken.STRING) throw new IllegalArgumentException();
                String link = reader.nextString();
                reader.endArray();
                if (reader.peek() != JsonToken.END_DOCUMENT) throw new IllegalArgumentException();
                endpoint(link);
                return link;
            } catch (Exception ignored) {
                throw new IllegalArgumentException("第三方语音响应未包含有效音频链接");
            }
        } else {
            try { endpoint(direct); return direct; } catch (IllegalArgumentException ignored) { }
        }
        ThirdPartyResponse parsed = ThirdPartyResponse.parse(body);
        Map<String, List<String>> fields = parsed.downloadFields();
        if (field != null && !field.trim().isEmpty()) {
            List<String> selected = fields.get(field.trim());
            // A URL-only JSON array is flattened as one field; still allow an explicit item index.
            if (selected == null && field.trim().endsWith("]")) {
                String path = field.trim();
                int start = path.lastIndexOf('[');
                if (start > 0) {
                    List<String> array = fields.get(path.substring(0, start));
                    try {
                        int index = Integer.parseInt(path.substring(start + 1, path.length() - 1));
                        if (array != null && index >= 0 && index < array.size()) {
                            selected = java.util.Collections.singletonList(array.get(index));
                        }
                    } catch (NumberFormatException ignored) { }
                }
            }
            if (selected == null || selected.size() != 1) {
                throw new IllegalArgumentException("所选语音字段应包含一个音频链接");
            }
            endpoint(selected.get(0));
            return selected.get(0);
        }
        String[] preferred = {"audio", "audio_url", "voice", "voice_url", "file", "file_url", "url", "download_url"};
        for (String name : preferred) {
            String match = null;
            for (Map.Entry<String, List<String>> entry : fields.entrySet()) {
                String key = entry.getKey();
                if (!(key.equalsIgnoreCase(name) || key.toLowerCase(Locale.ROOT).endsWith("." + name))) continue;
                if (entry.getValue().size() != 1) continue;
                String candidate = entry.getValue().get(0);
                if (match != null && !match.equals(candidate)) {
                    throw new IllegalArgumentException("返回了多个语音链接，请选择音频字段");
                }
                match = candidate;
            }
            if (match != null) { endpoint(match); return match; }
        }
        String match = null;
        for (List<String> links : fields.values()) for (String link : links) {
            if (match != null && !match.equals(link)) {
                throw new IllegalArgumentException("返回了多个链接，请选择音频字段");
            }
            match = link;
        }
        if (match == null) throw new IllegalArgumentException("第三方语音响应未包含音频链接");
        endpoint(match);
        return match;
    }

    public static boolean sameOrigin(String first, String second) {
        URI a = endpoint(first), b = endpoint(second);
        int aPort = a.getPort() == -1 ? ("https".equalsIgnoreCase(a.getScheme()) ? 443 : 80) : a.getPort();
        int bPort = b.getPort() == -1 ? ("https".equalsIgnoreCase(b.getScheme()) ? 443 : 80) : b.getPort();
        return a.getScheme().equalsIgnoreCase(b.getScheme()) && a.getHost().equalsIgnoreCase(b.getHost()) && aPort == bPort;
    }

    private static URI endpoint(String value) {
        try {
            URI uri = new URI(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getHost().isEmpty() || uri.getRawUserInfo() != null
                    || uri.getRawFragment() != null) throw new IllegalArgumentException();
            return uri;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("第三方语音 GET 地址必须是有效的 HTTP(S) 地址");
        }
    }

    private static Map<String, String> headers(String json) {
        Map<String, String> values = new LinkedHashMap<>();
        if (json == null || json.trim().isEmpty()) return values;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> names = object.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = object.get(name);
                if (!HEADER_NAME.matcher(name).matches() || !(value instanceof String)
                        || "host".equalsIgnoreCase(name) || "connection".equalsIgnoreCase(name)
                        || "content-length".equalsIgnoreCase(name)) throw new IllegalArgumentException();
                String text = (String) value;
                for (int i = 0; i < text.length(); i++) {
                    char ch = text.charAt(i);
                    if ((ch < 32 && ch != '\t') || ch == 127) throw new IllegalArgumentException();
                }
                for (String existing : values.keySet()) if (existing.equalsIgnoreCase(name)) throw new IllegalArgumentException();
                values.put(name, text);
            }
            return values;
        } catch (Exception ignored) {
            throw new IllegalArgumentException("第三方语音请求头应为有效的 JSON 字符串键值对象");
        }
    }

    private static IllegalArgumentException tooLarge(int limit) {
        return new IllegalArgumentException(limit == MAX_TEXT_BYTES ? "第三方语音响应超过 2 MiB" : "第三方语音音频超过 20 MiB");
    }
}
