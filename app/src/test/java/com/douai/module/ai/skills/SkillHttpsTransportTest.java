package com.douai.module.ai.skills;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;

import okhttp3.Dns;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.Test;

public class SkillHttpsTransportTest {
    private static final String HOST = "skill-provider.example.com";
    private static final String BEARER = "local-fixture-token";
    private static final byte[] JSON = "{\"text\":\"测试\"}".getBytes(StandardCharsets.UTF_8);
    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final Dns LOCAL_DNS = ignored -> Collections.singletonList(LOOPBACK);
    // Synthetic offline test key, never used by a real service.
    private static final String FIXTURE_STORE = """
            MIIEQgIBAzCCA+wGCSqGSIb3DQEHAaCCA90EggPZMIID1TCCASwGCSqGSIb3DQEHAaCCAR0EggEZMIIBFTCCAREG
            CyqGSIb3DQEMCgECoIG9MIG6MGYGCSqGSIb3DQEFDTBZMDgGCSqGSIb3DQEFDDArBBRBR6gWVIhcEUixf0MzsDH8
            58Y8EwICJxACASAwDAYIKoZIhvcNAgkFADAdBglghkgBZQMEASoEEAo+VPpD+O4YbdBzV1k8xsoEUCP9HGy7eRcO
            /Y7+Pv7vuGk3o62KAVgNh4jB6cp6deySo3kQrQf6uMXMkhDUSNjvN6wwMpRdhuhNsrNxwmk4QTl83JetAHGqeSh0
            oRVdWD1TMUIwHQYJKoZIhvcNAQkUMRAeDgBmAGkAeAB0AHUAcgBlMCEGCSqGSIb3DQEJFTEUBBJUaW1lIDE3OTU1
            OTg0MzY1MjcwggKhBgkqhkiG9w0BBwagggKSMIICjgIBADCCAocGCSqGSIb3DQEHATBmBgkqhkiG9w0BBQ0wWTA4
            BgkqhkiG9w0BBQwwKwQUQnGK28143ISPgYpqxc8TXbdaqJoCAicQAgEgMAwGCCqGSIb3DQIJBQAwHQYJYIZIAWUD
            BAEqBBBwrpc3gK9+5X0OOzCfjNDJgIICEB2H7Wi7yQypMuT5HyrFLHnrmaH5FUoEr/HmTw2eefdhuPDRZ1NU8Bly
            +jN6ab5XjW3WiJ6vEmisVGAcrzF+yiujZ/pShEc28VoF69CCr1lt382NWhUg7JH44D0DtP3KUb0ygxF5kVzCJmyq
            YTKiUEXxgZqW3VrbcQlyWDf3Y/jBdyZwgi+noJovJO1tGjVdjkUTwnJ8w9gJj8YP4LkkVpBMiiBx9U8qYDL5W+y7
            8VVfceer6yS+jSezfPbNb3fgvniMGzrHPOkj3FZY8aoahTcUKs/Avsz4Myv3xfjqBrnjUbaZojL/V5N67xmbLzbN
            5LqGQiYKg19BPrKtXzHJ290NVPw2D78ToJtlh8ja7ScvArfdTuCpZTTuctfm5TFT5KoWglBW+Qv7Ym6018qWAGWJ
            qB2acRnZgHlUaQn9Boc5Usel3ON79GM27HL/mOU6L9usTy16BiRzqSlV2COrja2bWaFA1cC9m7m0ogbaxeSlRVbj
            AujkJ5DOyeUBiCPqc5u5pBwegBS0JT5yIZcx+1Wc8T+NXy8BjzZq5F/wTAy4b7v+8TtKR9tng0DTpndo5XQoE6vd
            /o8B44tMTD/3LSfdWbhYxTP2NuUViuKeeuJ/vSXIsA/+bZZchOyg1UE3+TIXQbAXq1lD6KlFRHTP1o1Vt+54ux09
            Ve2KvBVTTa7ZHCc1/8fSYBJ4p1B8mwySazBNMDEwDQYJYIZIAWUDBAIBBQAEIJjga13lqIvM0eGtbYIIW3LDChOK
            b8ucdeKwGSA5w5VABBRjMZJbm80CVlIw0WMPCrMqBPzicwICJxA=
            """;

    @Test public void endpointValidationIsPureAndRejectsUnsafeLiteralAndAmbiguousPaths() throws Exception {
        assertEquals("https://service.example.com:8443/api",
                SkillHttpsTransport.validateEndpoint("HTTPS://Service.Example.Com:8443/api"));
        assertEquals("https://8.8.8.8/", SkillHttpsTransport.validateEndpoint("https://8.8.8.8"));
        assertEquals("https://[2001:4860::8888]/",
                SkillHttpsTransport.validateEndpoint("https://[2001:4860::8888]/"));
        for (String invalid : List.of("http://service.example.com/api",
                "https://service.example.com/api?key=fake", "https://service.example.com/api#part",
                "https://name@service.example.com/api", "https://service.example.com/a/%2e%2e/b",
                "https://localhost/api", "https://service.localhost/api", "https://host.local/api",
                "https://127.0.0.1/api", "https://[::ffff:8.8.8.8]/api", "https://[fe80::1%25eth0]/api",
                "https://169.254.169.254/api", "https://service.example.com:0/api")) {
            SkillHttpsTransport.Failure failure = assertThrows(SkillHttpsTransport.Failure.class,
                    () -> SkillHttpsTransport.validateEndpoint(invalid));
            assertFalse(failure.mayHaveSent());
        }
    }

    @Test public void publicAddressPolicyRejectsRegisteredNonPublicAndTransitionRanges() throws Exception {
        for (String address : List.of("0.1.2.3", "10.1.2.3", "100.64.0.1", "127.1.2.3",
                "169.254.1.2", "172.31.255.255", "192.0.0.9", "192.0.2.1", "192.88.99.2",
                "192.168.0.1", "198.19.1.1", "198.51.100.2", "203.0.113.3", "224.0.0.1",
                "255.255.255.255", "::", "::1", "64:ff9b::808:808", "100::1",
                "2001::1", "2001:db8::1", "2002:808:808::1", "3fff:ff::1",
                "fc00::1", "fe80::1", "ff02::1")) {
            assertFalse(address, SkillHttpsTransport.isPublicAddress(InetAddress.getByName(address)));
        }
        byte[] mapped = new byte[16];
        mapped[10] = mapped[11] = (byte) 255;
        mapped[12] = mapped[13] = mapped[14] = mapped[15] = 8;
        assertFalse(SkillHttpsTransport.isPublicAddress(Inet6Address.getByAddress(null, mapped, -1)));
        assertTrue(SkillHttpsTransport.isPublicAddress(InetAddress.getByName("8.8.8.8")));
        assertTrue(SkillHttpsTransport.isPublicAddress(InetAddress.getByName("2001:4860::8888")));
    }

    @Test public void successfulTlsPostUsesOnePinnedResolutionAndAnExactRequest() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json; charset=utf-8")
                    .setBody("{\"ok\":true}"));
            AtomicInteger resolutions = new AtomicInteger();
            Dns lookup = hostname -> {
                assertEquals(HOST, hostname);
                assertEquals(0, resolutions.getAndIncrement());
                return Collections.singletonList(LOOPBACK);
            };
            AtomicInteger admissions = new AtomicInteger();
            SkillHttpsTransport.Response result = fixture.post(HOST, BEARER, 256,
                    () -> { assertEquals(0, fixture.server.getRequestCount()); admissions.incrementAndGet(); },
                    lookup, LOOPBACK::equals);
            assertEquals(200, result.status());
            assertEquals("application/json", result.contentType());
            assertEquals("{\"ok\":true}", new String(result.body(), StandardCharsets.UTF_8));
            RecordedRequest sent = fixture.server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("POST", sent.getMethod());
            assertEquals("/api", sent.getPath());
            assertEquals("Bearer " + BEARER, sent.getHeader("Authorization"));
            assertEquals("identity", sent.getHeader("Accept-Encoding"));
            assertArrayEquals(JSON, sent.getBody().readByteArray());
            assertEquals(1, resolutions.get());
            assertEquals(1, admissions.get());
        }
    }

    @Test public void redirectAndServerRetryInstructionEachYieldExactlyOnePost() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            fixture.server.enqueue(new MockResponse().setResponseCode(307).setHeader("Location", "/different"));
            assertEquals(307, fixture.post(HOST, "", 256, () -> {}, LOCAL_DNS, LOOPBACK::equals).status());
            assertEquals(1, fixture.server.getRequestCount());
            fixture.server.enqueue(new MockResponse().setResponseCode(503).setHeader("Retry-After", "0"));
            fixture.server.enqueue(new MockResponse().setBody("unexpected replay"));
            assertEquals(503, fixture.post(HOST, "", 256, () -> {}, LOCAL_DNS, LOOPBACK::equals).status());
            assertEquals(2, fixture.server.getRequestCount());
            assertEquals("/api", fixture.server.takeRequest(1, TimeUnit.SECONDS).getPath());
            RecordedRequest second = fixture.server.takeRequest(1, TimeUnit.SECONDS);
            assertEquals("/api", second.getPath());
            assertNull(second.getHeader("Authorization"));
        }
    }

    @Test public void rejectsWrongTlsHostnameAndUntrustedCertificateBeforeAnyHttpWrite() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            AtomicInteger admitted = new AtomicInteger();
            assertFailure(SkillHttpsTransport.Kind.TRANSPORT, false, () ->
                    fixture.post("wrong.example.com", BEARER, 256, admitted::incrementAndGet,
                            LOCAL_DNS, LOOPBACK::equals));
            assertFailure(SkillHttpsTransport.Kind.TRANSPORT, false, () ->
                    SkillHttpsTransport.post(fixture.url(HOST), BEARER, JSON, deadline(), 256,
                            admitted::incrementAndGet, LOCAL_DNS, LOOPBACK::equals, null, null));
            assertEquals(0, admitted.get());
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test public void rejectsMixedResolutionAndRechecksTheConnectedPeerBeforeWriting() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            InetAddress reserved = InetAddress.getByAddress(new byte[]{(byte) 192, 0, 2, 1});
            assertFailure(SkillHttpsTransport.Kind.ADDRESS, false, () -> fixture.post(HOST, BEARER, 256,
                    () -> {}, ignored -> List.of(LOOPBACK, reserved), LOOPBACK::equals));
            AtomicInteger vetted = new AtomicInteger();
            assertFailure(SkillHttpsTransport.Kind.ADDRESS, false, () -> fixture.post(HOST, BEARER, 256,
                    () -> {}, LOCAL_DNS, peer -> LOOPBACK.equals(peer) && vetted.getAndIncrement() == 0));
            assertEquals(2, vetted.get());
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test public void protectsTheRequestWithAdmissionAndClassifiesOversizedReplyAsPossiblySent() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            assertFailure(SkillHttpsTransport.Kind.ADMISSION, false, () -> fixture.post(HOST, BEARER, 256,
                    () -> { throw new IOException(BEARER); }, LOCAL_DNS, LOOPBACK::equals));
            assertEquals(0, fixture.server.getRequestCount());
            fixture.server.enqueue(new MockResponse().setChunkedBody("123456789", 2));
            assertFailure(SkillHttpsTransport.Kind.RESPONSE_LIMIT, true, () ->
                    fixture.post(HOST, BEARER, 8, () -> {}, LOCAL_DNS, LOOPBACK::equals));
            assertEquals(1, fixture.server.getRequestCount());
        }
    }

    @Test public void dnsTakesTheSameDeadlineAndCannotDispatchAfterReturning() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            CountDownLatch release = new CountDownLatch(1);
            try {
                assertFailure(SkillHttpsTransport.Kind.DEADLINE, false, () -> SkillHttpsTransport.post(
                        fixture.url(HOST), BEARER, JSON, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100),
                        256, () -> {}, ignored -> {
                            try { release.await(3, TimeUnit.SECONDS); }
                            catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); }
                            return Collections.singletonList(LOOPBACK);
                        }, LOOPBACK::equals, fixture.context.getSocketFactory(), fixture.trust));
            } finally {
                release.countDown();
            }
            assertEquals(0, fixture.server.getRequestCount());
        }
    }

    @Test public void cancellationWhileAdmissionIsWaitingSealsDispatchBeforeReturning() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            CountDownLatch admissionEntered = new CountDownLatch(1);
            CountDownLatch releaseAdmission = new CountDownLatch(1);
            AtomicReference<Throwable> observed = new AtomicReference<>();
            Thread worker = new Thread(() -> {
                try {
                    fixture.post(HOST, BEARER, 256, () -> {
                        admissionEntered.countDown();
                        try { releaseAdmission.await(3, TimeUnit.SECONDS); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                    }, LOCAL_DNS, LOOPBACK::equals);
                } catch (Throwable failure) {
                    observed.set(failure);
                }
            });
            worker.setDaemon(true);
            worker.start();
            try {
                assertTrue(admissionEntered.await(4, TimeUnit.SECONDS));
                worker.interrupt();
                worker.join(2_000);
                assertFalse(worker.isAlive());
                assertTrue(observed.get() instanceof SkillHttpsTransport.Failure);
                SkillHttpsTransport.Failure failure = (SkillHttpsTransport.Failure) observed.get();
                assertEquals(SkillHttpsTransport.Kind.CANCELLED, failure.kind());
                assertFalse(failure.mayHaveSent());
            } finally {
                releaseAdmission.countDown();
            }
            assertNull(fixture.server.takeRequest(250, TimeUnit.MILLISECONDS));
        }
    }

    @Test public void videoMethodsKeepTlsAdmissionAndBoundedIdentityResponses() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "application/json")
                    .setBody("{\"status\":\"queued\"}"));
            fixture.server.enqueue(new MockResponse().setResponseCode(302)
                    .setHeader("Location", "https://other.test/private"));
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "video/mp4")
                    .setBody("0123456789"));
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "video/mp4")
                    .setHeader("Content-Encoding", "gzip").setBody("ignored"));
            var status = fixture.exchange(SkillHttpsTransport.Method.GET, false, 64);
            assertEquals("application/json", status.contentType());
            var cancellation = fixture.exchange(SkillHttpsTransport.Method.DELETE, false, 64);
            assertEquals(302, cancellation.status());
            assertFailure(SkillHttpsTransport.Kind.RESPONSE_LIMIT, true,
                    () -> fixture.exchange(SkillHttpsTransport.Method.GET, true, 5));
            assertEquals("", fixture.exchange(SkillHttpsTransport.Method.GET, true, 64).contentType());
            assertEquals(4, fixture.server.getRequestCount());
            for (String method : new String[]{"GET", "DELETE", "GET", "GET"}) {
                var request = fixture.server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(request);
                assertEquals(method, request.getMethod());
                assertEquals("Bearer " + BEARER, request.getHeader("Authorization"));
                assertEquals("identity", request.getHeader("Accept-Encoding"));
            }
        }
    }

    @Test public void reviewedPostAcceptsLegacyMediaAndScopesTheLongImageBearer() throws Exception {
        try (TlsFixture fixture = tlsFixture()) {
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "image/png").setBody("raster"));
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "text/plain; charset=utf-8")
                    .setBody("plain"));
            fixture.server.enqueue(new MockResponse().setHeader("Content-Type", "application/provider+json")
                    .setBody("{\"text\":\"json\"}"));
            String imageBearer = "i".repeat(4000);
            assertEquals("image/png", fixture.reviewedPost(imageBearer, true, true).contentType());
            assertEquals("text/plain", fixture.reviewedPost(BEARER, false, false).contentType());
            assertEquals("application/json", fixture.reviewedPost(BEARER, false, false).contentType());
            assertFailure(SkillHttpsTransport.Kind.REQUEST, false,
                    () -> fixture.reviewedPost(imageBearer, false, false));
            assertEquals(3, fixture.server.getRequestCount());
            var image = fixture.server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(image);
            assertEquals("POST", image.getMethod());
            assertEquals("Bearer " + imageBearer, image.getHeader("Authorization"));
            assertTrue(image.getHeader("Accept").contains("image/png"));
        }
    }

    private static void assertFailure(SkillHttpsTransport.Kind kind, boolean mayHaveSent,
                                      org.junit.function.ThrowingRunnable operation) {
        SkillHttpsTransport.Failure failure = assertThrows(SkillHttpsTransport.Failure.class, operation);
        assertEquals(kind, failure.kind());
        assertEquals(mayHaveSent, failure.mayHaveSent());
        assertFalse(failure.getMessage().contains(BEARER));
        assertNull(failure.getCause());
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(8); }

    private record TlsFixture(MockWebServer server, SSLContext context, X509TrustManager trust)
            implements AutoCloseable {
        String url(String host) { return "https://" + host + ":" + server.getPort() + "/api"; }

        SkillHttpsTransport.Response post(String host, String bearer, int limit, SkillHttpsTransport.Admission gate,
                                          Dns dns, Predicate<InetAddress> addresses) throws IOException {
            return SkillHttpsTransport.post(url(host), bearer, JSON, deadline(), limit, gate, dns, addresses,
                    context.getSocketFactory(), trust);
        }

        SkillHttpsTransport.Response exchange(SkillHttpsTransport.Method method, boolean media, int limit)
                throws IOException {
            return SkillHttpsTransport.exchange(method, media, url(HOST), BEARER, null,
                    deadline(), limit, () -> {}, host -> List.of(LOOPBACK), LOOPBACK::equals,
                    context.getSocketFactory(), trust);
        }

        SkillHttpsTransport.Response reviewedPost(String bearer, boolean image, boolean imageBearer) throws IOException {
            return SkillHttpsTransport.postReviewed(url(HOST), bearer, JSON, deadline(), 64, image, imageBearer,
                    () -> {}, host -> List.of(LOOPBACK), LOOPBACK::equals, context.getSocketFactory(), trust);
        }

        @Override public void close() throws IOException { server.close(); }
    }

    private static TlsFixture tlsFixture() throws Exception {
        char[] password = "fixture-test".toCharArray();
        KeyStore keys = KeyStore.getInstance("PKCS12");
        keys.load(new ByteArrayInputStream(Base64.getMimeDecoder().decode(FIXTURE_STORE)), password);
        KeyManagerFactory manager = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        manager.init(keys, password);
        Certificate certificate = keys.getCertificate("fixture");
        KeyStore anchors = KeyStore.getInstance(KeyStore.getDefaultType());
        anchors.load(null, null);
        anchors.setCertificateEntry("fixture", certificate);
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(anchors);
        X509TrustManager pinned = (X509TrustManager) trust.getTrustManagers()[0];
        SSLContext tls = SSLContext.getInstance("TLS");
        tls.init(manager.getKeyManagers(), new X509TrustManager[]{pinned}, null);
        MockWebServer server = new MockWebServer();
        server.useHttps(tls.getSocketFactory(), false);
        server.start(LOOPBACK, 0);
        return new TlsFixture(server, tls, pinned);
    }
}
