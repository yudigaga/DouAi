package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.Socket;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Authenticator;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Connection;
import okhttp3.ConnectionPool;
import okhttp3.CookieJar;
import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.BufferedSource;

/** One guarded HTTPS request. Callers own authorization and response interpretation. */
final class SkillHttpsTransport {
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_RESPONSE_BYTES = 6 * 1024 * 1024;
    static final int MAX_VIDEO_BYTES = 16 * 1024 * 1024;
    private static final int MAX_RESOLVED = 32;
    private static final long MAX_CALL_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final long MAX_IMAGE_CALL_NANOS = TimeUnit.SECONDS.toNanos(60);
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");

    // The platform DNS resolver can block despite interruption. Do not queue or grow unbounded.
    private static final ThreadPoolExecutor DNS_WORKERS = new ThreadPoolExecutor(0, 4, 30, TimeUnit.SECONDS,
            new SynchronousQueue<>(), task -> {
                Thread worker = new Thread(task, "DouAi-skill-dns");
                worker.setDaemon(true);
                return worker;
            });

    private static final OkHttpClient CLIENT = new OkHttpClient.Builder()
            .proxy(Proxy.NO_PROXY)
            .cookieJar(CookieJar.NO_COOKIES)
            .authenticator(Authenticator.NONE)
            .proxyAuthenticator(Authenticator.NONE)
            .followRedirects(false)
            .followSslRedirects(false)
            .retryOnConnectionFailure(false)
            .protocols(Collections.singletonList(Protocol.HTTP_1_1))
            .connectionPool(new ConnectionPool(0, 1, TimeUnit.SECONDS))
            .build();

    enum Kind { ENDPOINT, REQUEST, ADDRESS, DEADLINE, CANCELLED, ADMISSION, TRANSPORT, RESPONSE_LIMIT }
    enum Method { POST, GET, DELETE }
    private enum Format { JSON, MP4, IMAGE_RESULT, TEXT_RESULT, OPENAPI_DOCUMENT }
    private enum Dispatch { READY, SENT, CLOSED }

    static final class Failure extends IOException {
        private final Kind kind;
        private final boolean mayHaveSent;

        Failure(Kind kind, boolean mayHaveSent) {
            super("HTTPS " + kind.name());
            this.kind = kind;
            this.mayHaveSent = mayHaveSent;
        }

        Kind kind() { return kind; }
        boolean mayHaveSent() { return mayHaveSent; }
    }

    @FunctionalInterface interface Admission { void check() throws IOException; }

    record Response(int status, String contentType, byte[] body) {}

    private record Endpoint(HttpUrl url, InetAddress literal) {}

    private SkillHttpsTransport() {}

    static String validateEndpoint(String address) throws Failure { return endpoint(address).url().toString(); }

    static Response post(String exactEndpoint, String bearer, byte[] utf8Json, long deadlineNanos,
                         int maxResponseBytes, Admission admission) throws Failure {
        return post(exactEndpoint, bearer, utf8Json, deadlineNanos, maxResponseBytes, admission,
                Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    // Offline fixtures may use loopback and fixture trust. Production supplies no custom TLS trust.
    static Response post(String exactEndpoint, String bearer, byte[] utf8Json, long deadlineNanos,
                         int maxResponseBytes, Admission admission, Dns resolver, Predicate<InetAddress> policy,
                         SSLSocketFactory fixtureTls, X509TrustManager fixtureTrust) throws Failure {
        return exchange(Method.POST, false, exactEndpoint, bearer, utf8Json, deadlineNanos, maxResponseBytes,
                admission, resolver, policy, fixtureTls, fixtureTrust);
    }

    static Response get(String exactEndpoint, String bearer, long deadlineNanos, int maxResponseBytes,
                        Admission admission) throws Failure {
        return exchange(Method.GET, false, exactEndpoint, bearer, null, deadlineNanos, maxResponseBytes,
                admission, Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    static Response getDocumentation(String exactEndpoint, long deadlineNanos, int maxResponseBytes) throws Failure {
        return exchange(Method.GET, Format.OPENAPI_DOCUMENT, false, exactEndpoint, "", null, deadlineNanos,
                maxResponseBytes, () -> {}, Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    static Response delete(String exactEndpoint, String bearer, long deadlineNanos, int maxResponseBytes,
                           Admission admission) throws Failure {
        return exchange(Method.DELETE, false, exactEndpoint, bearer, null, deadlineNanos, maxResponseBytes,
                admission, Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    static Response download(String exactEndpoint, String bearer, long deadlineNanos, int maxResponseBytes,
                             Admission admission) throws Failure {
        return exchange(Method.GET, true, exactEndpoint, bearer, null, deadlineNanos, maxResponseBytes,
                admission, Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    static Response exchange(Method method, boolean media, String exactEndpoint, String bearer, byte[] utf8Json,
                             long deadlineNanos, int maxResponseBytes, Admission admission, Dns resolver,
                             Predicate<InetAddress> policy, SSLSocketFactory fixtureTls, X509TrustManager fixtureTrust)
            throws Failure {
        return exchange(method, media ? Format.MP4 : Format.JSON, false, exactEndpoint, bearer, utf8Json,
                deadlineNanos, maxResponseBytes, admission, resolver, policy, fixtureTls, fixtureTrust);
    }

    static Response postReviewed(String exactEndpoint, String bearer, byte[] utf8Json, long deadlineNanos,
                                 int maxResponseBytes, boolean image, boolean imageBearer, Admission admission)
            throws Failure {
        return postReviewed(exactEndpoint, bearer, utf8Json, deadlineNanos, maxResponseBytes, image, imageBearer,
                admission, Dns.SYSTEM, SkillHttpsTransport::isPublicAddress, null, null);
    }

    static Response postReviewed(String exactEndpoint, String bearer, byte[] utf8Json, long deadlineNanos,
                                 int maxResponseBytes, boolean image, boolean imageBearer, Admission admission,
                                 Dns resolver, Predicate<InetAddress> policy, SSLSocketFactory fixtureTls,
                                 X509TrustManager fixtureTrust) throws Failure {
        if (imageBearer && !image) throw new Failure(Kind.REQUEST, false);
        return exchange(Method.POST, image ? Format.IMAGE_RESULT : Format.TEXT_RESULT, imageBearer, exactEndpoint,
                bearer, utf8Json, deadlineNanos, maxResponseBytes, admission, resolver, policy, fixtureTls, fixtureTrust);
    }

    private static Response exchange(Method method, Format format, boolean imageBearer, String exactEndpoint,
                                     String bearer, byte[] utf8Json, long deadlineNanos, int maxResponseBytes,
                                     Admission admission, Dns resolver, Predicate<InetAddress> policy,
                                     SSLSocketFactory fixtureTls, X509TrustManager fixtureTrust) throws Failure {
        Endpoint target = endpoint(exactEndpoint);
        HttpUrl url = target.url();
        if (bearer == null || !bearer.isEmpty() && !(imageBearer
                ? AiCredentialVault.validImageBearer(bearer) : AiCredentialVault.validBearer(bearer))
                || method == null || format == Format.MP4 && method != Method.GET || method != Method.POST && utf8Json != null
                || method == Method.POST && (utf8Json == null || utf8Json.length == 0 || utf8Json.length > MAX_REQUEST_BYTES)
                || maxResponseBytes <= 0 || maxResponseBytes > (format == Format.MP4 ? MAX_VIDEO_BYTES : MAX_RESPONSE_BYTES)
                || admission == null
                || AiCredentialVault.imageEndpointContainsCredential(url.toString(), bearer)) {
            throw new Failure(Kind.REQUEST, false);
        }
        if (Thread.currentThread().isInterrupted()) throw new Failure(Kind.CANCELLED, false);
        long now = System.nanoTime();
        long allowance = Math.min(deadlineNanos - now, format == Format.IMAGE_RESULT ? MAX_IMAGE_CALL_NANOS : MAX_CALL_NANOS);
        if (allowance <= 0) throw new Failure(Kind.DEADLINE, false);
        long deadline = now + allowance;
        List<InetAddress> vetted = resolve(target, resolver, policy, deadline);

        Request.Builder builder = new Request.Builder().url(url)
                .method(method.name(), method == Method.POST ? oneShotJson(utf8Json.clone()) : null)
                .header("Accept", switch (format) {
                    case MP4 -> "video/mp4";
                    case IMAGE_RESULT -> "image/png, image/jpeg, image/webp, image/gif, application/json";
                    case TEXT_RESULT -> "application/json, text/plain";
                    case OPENAPI_DOCUMENT -> "application/json, application/vnd.oai.openapi+json, text/plain";
                    case JSON -> "application/json";
                })
                .header("Accept-Encoding", "identity")
                .header("Connection", "close");
        if (!bearer.isEmpty()) builder.header("Authorization", "Bearer " + bearer);
        Request request = builder.build();

        AtomicReference<Dispatch> dispatch = new AtomicReference<>(Dispatch.READY);
        OkHttpClient.Builder client = CLIENT.newBuilder()
                .dns(host -> {
                    if (!url.host().equals(host)) throw new UnknownHostException();
                    return vetted;
                })
                .callTimeout(Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining(deadline))), TimeUnit.MILLISECONDS)
                .addNetworkInterceptor(chain -> guardedDispatch(chain, request, vetted, policy,
                        admission, deadline, dispatch));
        if (fixtureTls != null) client.sslSocketFactory(fixtureTls, fixtureTrust);
        Call call = client.build().newCall(request);
        CompletableFuture<Response> pending = new CompletableFuture<>();
        call.enqueue(new Callback() {
            @Override public void onFailure(Call ignored, IOException failure) {
                pending.completeExceptionally(safeFailure(failure, dispatch));
            }

            @Override public void onResponse(Call ignored, okhttp3.Response response) {
                Response result;
                try (okhttp3.Response received = response) {
                    result = read(received, maxResponseBytes, format);
                } catch (IOException | RuntimeException failure) {
                    pending.completeExceptionally(safeFailure(failure, dispatch));
                    return;
                }
                pending.complete(result);
            }
        });
        try {
            return pending.get(remaining(deadline), TimeUnit.NANOSECONDS);
        } catch (TimeoutException failure) {
            throw cancel(call, dispatch, Kind.DEADLINE);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw cancel(call, dispatch, Kind.CANCELLED);
        } catch (ExecutionException failure) {
            throw safeFailure(failure.getCause(), dispatch);
        } catch (Failure expired) {
            throw cancel(call, dispatch, expired.kind());
        }
    }

    private static Endpoint endpoint(String address) throws Failure {
        try {
            URI exact = AiExtensionHttp.exactEndpoint(address);
            HttpUrl url = HttpUrl.get(exact.toASCIIString());
            String literalHost = exact.getHost();
            String path = URI.create(exact.toASCIIString()).getRawPath();
            if (!url.encodedPath().equals(path.isEmpty() ? "/" : path)) {
                throw new Failure(Kind.ENDPOINT, false);
            }

            InetAddress literal = null;
            if (literalHost.startsWith("[")) {
                if (literalHost.indexOf('%') >= 0) throw new Failure(Kind.ADDRESS, false);
                literal = InetAddress.getByName(literalHost);
                // InetAddress may flatten an IPv4-mapped literal to IPv4. Never accept that form.
                if (!(literal instanceof Inet6Address)) throw new Failure(Kind.ADDRESS, false);
            } else if (literalHost.matches("[0-9.]+")) {
                literal = ipv4Literal(literalHost);
            } else if (!literalHost.equalsIgnoreCase(url.host()) || !publicHostname(url.host())) {
                throw new Failure(Kind.ADDRESS, false);
            }
            if (literal != null && !isPublicAddress(literal)) throw new Failure(Kind.ADDRESS, false);
            return new Endpoint(url, literal);
        } catch (IllegalArgumentException | UnknownHostException invalid) {
            throw new Failure(Kind.ENDPOINT, false);
        }
    }

    private static InetAddress ipv4Literal(String host) throws UnknownHostException {
        String[] parts = host.split("\\.", -1);
        if (parts.length != 4) throw new UnknownHostException();
        byte[] bytes = new byte[4];
        for (int index = 0; index < parts.length; index++) {
            String part = parts[index];
            if (part.isEmpty() || part.length() > 3 || part.length() > 1 && part.charAt(0) == '0') {
                throw new UnknownHostException();
            }
            int octet = Integer.parseInt(part);
            if (octet > 255) throw new UnknownHostException();
            bytes[index] = (byte) octet;
        }
        return InetAddress.getByAddress(bytes);
    }

    private static boolean publicHostname(String host) {
        int lastDot = host.lastIndexOf('.');
        if (lastDot <= 0 || lastDot + 1 == host.length()) return false;
        char firstTld = host.charAt(lastDot + 1);
        return firstTld >= 'a' && firstTld <= 'z'
                && !host.endsWith(".localhost") && !host.endsWith(".local")
                && !host.endsWith(".internal") && !host.endsWith(".home.arpa");
    }

    private static List<InetAddress> resolve(Endpoint target, Dns resolver, Predicate<InetAddress> policy,
                                             long deadline) throws Failure {
        if (target.literal() != null) return Collections.singletonList(target.literal());
        long timeLeft = remaining(deadline);
        Future<List<InetAddress>> lookup;
        try {
            lookup = DNS_WORKERS.submit(() -> resolver.lookup(target.url().host()));
        } catch (RejectedExecutionException unavailable) {
            throw new Failure(Kind.ADDRESS, false);
        }
        try {
            List<InetAddress> answers = lookup.get(timeLeft, TimeUnit.NANOSECONDS);
            if (answers == null || answers.isEmpty() || answers.size() > MAX_RESOLVED) {
                throw new Failure(Kind.ADDRESS, false);
            }
            List<InetAddress> vetted = new ArrayList<>(answers.size());
            for (InetAddress address : answers) {
                if (address == null || !policy.test(address)) throw new Failure(Kind.ADDRESS, false);
                if (!vetted.contains(address)) vetted.add(address);
            }
            return Collections.unmodifiableList(vetted);
        } catch (TimeoutException expired) {
            throw new Failure(Kind.DEADLINE, false);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new Failure(Kind.CANCELLED, false);
        } catch (ExecutionException | RuntimeException unavailable) {
            throw new Failure(Kind.ADDRESS, false);
        } finally {
            lookup.cancel(true);
        }
    }

    private static okhttp3.Response guardedDispatch(Interceptor.Chain chain, Request original,
                                                    List<InetAddress> vetted, Predicate<InetAddress> policy,
                                                    Admission admission, long deadline,
                                                    AtomicReference<Dispatch> dispatch) throws IOException {
        if (dispatch.get() != Dispatch.READY) throw new Failure(Kind.TRANSPORT, dispatch.get() == Dispatch.SENT);
        remaining(deadline);
        Connection connection = chain.connection();
        Request request = chain.request();
        Socket socket = connection == null ? null : connection.socket();
        InetAddress peer = socket == null ? null : socket.getInetAddress();
        if (connection == null || connection.handshake() == null || connection.route().proxy().type() != Proxy.Type.DIRECT
                || peer == null || socket.getPort() != original.url().port() || !vetted.contains(peer)
                || !policy.test(peer) || !request.url().equals(original.url()) || !request.method().equals(original.method())) {
            throw new Failure(Kind.ADDRESS, false);
        }
        try {
            admission.check();
        } catch (IOException | RuntimeException refused) {
            throw new Failure(Kind.ADMISSION, false);
        }
        remaining(deadline);
        if (!dispatch.compareAndSet(Dispatch.READY, Dispatch.SENT)) {
            throw new Failure(Kind.TRANSPORT, dispatch.get() == Dispatch.SENT);
        }
        // From here header or body bytes may reach the server even if no reply arrives.
        return chain.proceed(request);
    }

    private static RequestBody oneShotJson(byte[] body) {
        return new RequestBody() {
            @Override public MediaType contentType() { return JSON; }
            @Override public long contentLength() { return body.length; }
            @Override public boolean isOneShot() { return true; }
            @Override public void writeTo(BufferedSink sink) throws IOException { sink.write(body); }
        };
    }

    private static Response read(okhttp3.Response response, int limit, Format format) throws IOException {
        int status = response.code();
        if (!response.isSuccessful()) return new Response(status, "", new byte[0]);
        List<String> typeHeaders = response.headers().values("Content-Type");
        String contentType = typeHeaders.size() == 1 ? responseType(typeHeaders.get(0), format) : "";
        if (format != Format.JSON) {
            List<String> encodings = response.headers().values("Content-Encoding");
            if (status == 206 || (format == Format.MP4 || format == Format.OPENAPI_DOCUMENT) && status != 200
                    || contentType.isEmpty()
                    || !encodings.isEmpty() && (encodings.size() != 1 || !encodings.get(0).equalsIgnoreCase("identity"))) {
                return new Response(status, "", new byte[0]);
            }
        }
        ResponseBody body = response.body();
        if (body == null) return new Response(status, contentType, new byte[0]);
        BufferedSource source = body.source();
        if (body.contentLength() > limit || source.request((long) limit + 1)) {
            throw new Failure(Kind.RESPONSE_LIMIT, true);
        }
        return new Response(status, contentType, source.readByteArray());
    }

    private static String responseType(String header, Format format) {
        if (header.length() > 256) return "";
        int parameters = header.indexOf(';');
        String bare = (parameters < 0 ? header : header.substring(0, parameters)).trim().toLowerCase(Locale.ROOT);
        if (format == Format.MP4) return bare.equals("video/mp4") ? bare : "";
        if ((format == Format.TEXT_RESULT || format == Format.OPENAPI_DOCUMENT) && bare.equals("text/plain")) return bare;
        if (format == Format.IMAGE_RESULT && (bare.equals("image/png") || bare.equals("image/jpeg")
                || bare.equals("image/webp") || bare.equals("image/gif"))) return bare;
        return bare.equals("application/json") || bare.matches("application/[a-z0-9._-]+\\+json")
                ? "application/json" : "";
    }

    private static long remaining(long deadline) throws Failure {
        long timeLeft = deadline - System.nanoTime();
        if (timeLeft <= 0) throw new Failure(Kind.DEADLINE, false);
        return timeLeft;
    }

    private static Failure cancel(Call call, AtomicReference<Dispatch> dispatch, Kind kind) {
        dispatch.compareAndSet(Dispatch.READY, Dispatch.CLOSED);
        call.cancel();
        return new Failure(kind, dispatch.get() == Dispatch.SENT);
    }

    private static Failure safeFailure(Throwable error, AtomicReference<Dispatch> dispatch) {
        boolean sent = dispatch.get() == Dispatch.SENT;
        if (error instanceof Failure failure) return new Failure(failure.kind(), sent || failure.mayHaveSent());
        return new Failure(error instanceof InterruptedIOException ? Kind.DEADLINE : Kind.TRANSPORT, sent);
    }

    // Conservative blocks for IANA special-purpose, multicast and reserved ranges.
    static boolean isPublicAddress(InetAddress address) {
        byte[] bytes = address.getAddress();
        int first = bytes[0] & 255;
        int second = bytes[1] & 255;
        int third = bytes[2] & 255;
        if (bytes.length == 4) {
            if (first == 0 || first == 10 || first == 127 || first >= 224) return false;
            if (first == 100 && second >= 64 && second <= 127) return false;
            if (first == 169 && second == 254) return false;
            if (first == 172 && second >= 16 && second <= 31) return false;
            if (first == 192 && (second == 168 || second == 0 && (third == 0 || third == 2)
                    || second == 31 && third == 196 || second == 52 && third == 193
                    || second == 88 && third == 99 || second == 175 && third == 48)) return false;
            if (first == 198 && (second == 18 || second == 19 || second == 51 && third == 100)) return false;
            return first != 203 || second != 0 || third != 113;
        }
        if (!(address instanceof Inet6Address ipv6) || ipv6.getScopeId() != 0 || ipv6.getScopedInterface() != null
                || first < 0x20 || first > 0x3f) return false;
        if (first == 0x20 && second == 1 && (third < 2
                || third == 0x0d && (bytes[3] & 255) == 0xb8)) return false;
        if (first == 0x20 && second == 2 || first == 0x3f && second == 0xff && third < 0x10) return false;
        return first != 0x26 || second != 0x20 || third != 0 || (bytes[3] & 255) != 0x4f
                || (bytes[4] & 255) != 0x80 || bytes[5] != 0;
    }
}
