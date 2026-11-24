package com.douai.module.ai;

import com.douai.module.ai.AiExtensionHttp.Phase;
import com.douai.module.ai.AiExtensionHttp.TransportReason;

import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.security.cert.Certificate;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;
import javax.net.ssl.SSLHandshakeException;

import static org.junit.Assert.*;

public final class AiExtensionHttpCatalogueFailureTest {
    private static final String ENDPOINT = "https://catalogue-fixture.example.test/v1/models";
    private static final String KEY = "catalogue-fixture-key";
    private static final String RAW = "sensitive diagnostic " + ENDPOINT + " " + KEY;

    @Test public void dnsFailureBeforeHttpStatusRemainsOneGet() {
        assertWaitingFailure(new UnknownHostException(RAW), TransportReason.DNS, AiExtensionHttp.Kind.TRANSPORT);
    }

    @Test public void tlsFailureBeforeHttpStatusKeepsItsSafeClassification() {
        assertWaitingFailure(new SSLHandshakeException(RAW), TransportReason.TLS, AiExtensionHttp.Kind.TRANSPORT);
    }

    @Test public void connectFailureBeforeHttpStatusKeepsItsSafeClassification() {
        assertWaitingFailure(new ConnectException(RAW), TransportReason.CONNECT, AiExtensionHttp.Kind.TRANSPORT);
    }

    @Test public void waitingSocketTimeoutDoesNotClaimAProviderStatusOrRetry() {
        assertWaitingFailure(new SocketTimeoutException(RAW), TransportReason.SOCKET_TIMEOUT, AiExtensionHttp.Kind.DEADLINE);
    }

    @Test public void responseReadFailureRetainsTheObservedHttpCode() {
        Fixture fixture = new Fixture(200, null, new IOException(RAW));
        AiExtensionHttp.Failure failure = assertThrows(AiExtensionHttp.Failure.class,
                () -> fixture.request(deadline(), (status, headers, body) -> AiExtensionHttp.readUtf8(body)));
        assertFailure(failure, AiExtensionHttp.Kind.TRANSPORT, true, Phase.READ_RESPONSE, TransportReason.OTHER, 200);
        assertSingleGet(fixture);
        assertEquals(1, fixture.connection.bodyOpens.get());
    }

    @Test public void anUnavailableHttpStatusIsNeverReportedAsANumericCode() {
        Fixture fixture = new Fixture(-1, null, null);
        AiExtensionHttp.Failure failure = assertThrows(AiExtensionHttp.Failure.class,
                () -> fixture.request(deadline(), (status, headers, body) -> { throw new IOException(RAW); }));
        assertFailure(failure, AiExtensionHttp.Kind.TRANSPORT, true, Phase.STATUS_UNAVAILABLE, TransportReason.OTHER, 0);
        assertSingleGet(fixture);
        assertEquals(0, fixture.connection.bodyOpens.get());
    }

    @Test public void anExpiredOverallBudgetDoesNotOpenTheConnection() {
        Fixture fixture = new Fixture(200, null, new IOException(RAW));
        AiExtensionHttp.Failure failure = assertThrows(AiExtensionHttp.Failure.class,
                () -> fixture.request(System.nanoTime() - 1, (status, headers, body) -> null));
        assertFailure(failure, AiExtensionHttp.Kind.DEADLINE, false, Phase.QUEUED, TransportReason.BUDGET, 0);
        assertEquals(0, fixture.opens.get());
    }

    @Test public void aRedirectReturnsItsStatusWithoutOpeningABodyOrFollowingLocation() throws Exception {
        Fixture fixture = new Fixture(302, null, null);
        int result = fixture.request(deadline(), (status, headers, body) -> {
            assertEquals(-1, body.read());
            return status;
        });
        assertEquals(302, result);
        assertSingleGet(fixture);
        assertEquals(0, fixture.connection.bodyOpens.get());
    }

    private static void assertWaitingFailure(IOException raw, TransportReason reason, AiExtensionHttp.Kind kind) {
        Fixture fixture = new Fixture(200, raw, null);
        AiExtensionHttp.Failure failure = assertThrows(AiExtensionHttp.Failure.class,
                () -> fixture.request(deadline(), (status, headers, body) -> {
                    throw new AssertionError("No catalogue reader may run before HTTP status");
                }));
        assertFailure(failure, kind, true, Phase.WAIT_STATUS, reason, 0);
        assertSingleGet(fixture);
        assertEquals(0, fixture.connection.bodyOpens.get());
    }

    private static void assertFailure(AiExtensionHttp.Failure failure, AiExtensionHttp.Kind kind, boolean maybeSent,
                                      Phase phase, TransportReason reason, int status) {
        assertEquals(kind, failure.kind);
        assertEquals(maybeSent, failure.mayHaveSent);
        assertEquals(phase, failure.phase());
        assertEquals(reason, failure.transportReason());
        assertEquals(status, failure.httpStatus());
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(ENDPOINT));
        assertFalse(failure.toString().contains(KEY));
        assertFalse(failure.toString().contains(RAW));
    }

    private static void assertSingleGet(Fixture fixture) {
        assertEquals(1, fixture.opens.get());
        assertEquals(1, fixture.connection.statusReads.get());
        assertEquals("GET", fixture.connection.getRequestMethod());
        assertEquals("Bearer " + KEY, fixture.connection.getRequestProperty("Authorization"));
        assertFalse(fixture.connection.getDoOutput());
        assertFalse(fixture.connection.getInstanceFollowRedirects());
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(8); }

    private static final class Fixture {
        final AtomicInteger opens = new AtomicInteger();
        final int status;
        final IOException statusFailure;
        final IOException bodyFailure;
        FixtureConnection connection;

        Fixture(int status, IOException statusFailure, IOException bodyFailure) {
            this.status = status;
            this.statusFailure = statusFailure;
            this.bodyFailure = bodyFailure;
        }

        <T> T request(long deadline, AiExtensionHttp.ResponseReader<T> reader) throws AiExtensionHttp.Failure {
            return AiExtensionHttp.request(ENDPOINT, "GET", Map.of("Authorization", "Bearer " + KEY), null,
                    deadline, 256, reader, null, this::open);
        }

        private HttpsURLConnection open(URI endpoint) throws IOException {
            assertEquals(ENDPOINT, endpoint.toString());
            opens.incrementAndGet();
            connection = new FixtureConnection(endpoint.toURL(), status, statusFailure, bodyFailure);
            return connection;
        }
    }

    private static final class FixtureConnection extends HttpsURLConnection {
        final AtomicInteger statusReads = new AtomicInteger();
        final AtomicInteger bodyOpens = new AtomicInteger();
        private final int status;
        private final IOException statusFailure;
        private final IOException bodyFailure;

        FixtureConnection(URL url, int status, IOException statusFailure, IOException bodyFailure) {
            super(url);
            this.status = status;
            this.statusFailure = statusFailure;
            this.bodyFailure = bodyFailure;
        }

        @Override public int getResponseCode() throws IOException {
            statusReads.incrementAndGet();
            if (statusFailure != null) throw statusFailure;
            return status;
        }

        @Override public InputStream getInputStream() {
            bodyOpens.incrementAndGet();
            if (bodyFailure == null) throw new AssertionError("No body stream should open for this fixture");
            return new InputStream() {
                @Override public int read() throws IOException { throw bodyFailure; }
            };
        }

        @Override public OutputStream getOutputStream() { throw new AssertionError("Catalogue GET must have no body"); }
        @Override public void connect() { throw new AssertionError("No real network is allowed"); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
}
