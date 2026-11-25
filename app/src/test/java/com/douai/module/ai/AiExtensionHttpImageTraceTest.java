package com.douai.module.ai;

import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.net.ssl.HttpsURLConnection;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class AiExtensionHttpImageTraceTest {
    private static final String ENDPOINT = "https://image-trace.example.test/v1/images/generations";
    private static final String BEARER = "synthetic-image-credential";
    private static final String PROMPT = "unique synthetic image prompt";
    private static final String RESPONSE = "unique synthetic response";
    private static final String RAW_FAILURE = "raw synthetic failure with " + ENDPOINT + " " + BEARER;
    private static final byte[] REQUEST = ("{\"prompt\":\"" + PROMPT + "\"}").getBytes(StandardCharsets.UTF_8);

    private final List<String> records = new CopyOnWriteArrayList<>();
    private boolean debugBefore;

    @Before public void capture() {
        assertTrue(DouAiLog.awaitIdle(3000));
        debugBefore = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
    }

    @After public void restoreLogging() {
        DouAiLog.awaitIdle(3000);
        DouAiLog.setFrameworkSink(null);
        DebugLog.setEnabled(debugBefore);
    }

    @Test public void completedPostIdentifiesTheExactPhasesAcrossTheWorkerThread() throws Exception {
        Fixture fixture = new Fixture(Point.NONE, 200);
        DouAiLog.Operation trace = trace();
        assertEquals(RESPONSE, fixture.request(trace, 128));
        trace.finish(DouAiLog.Outcome.SUCCESS, "fixture_validated", "");
        assertEquals(1, fixture.opens.get());
        assertArrayEquals(REQUEST, fixture.connection.written.toByteArray());
        assertEquals(1, fixture.connection.bodyOpens.get());
        assertFalse(fixture.connection.getInstanceFollowRedirects());

        List<JSONObject> stages = stages(trace);
        assertEquals(List.of("image_http_dispatch", "image_http_open_connection", "image_http_open_output", "image_http_write_request",
                "image_http_wait_status", "image_http_status_received", "image_http_read_response"), names(stages));
        assertTrue(evidence(stages, "image_http_status_received").contains("http_status=200"));
        assertSafeEvidence(stages);
    }

    @Test public void openingTheConnectionFailsBeforeTheRequestMayHaveBeenSent() throws Exception {
        assertPhaseFailure(Point.OPEN_CONNECTION, 128, AiExtensionHttp.Kind.TRANSPORT, false, "open_connection");
    }

    @Test public void openingTheOutputStreamHasASeparateConservativeSentState() throws Exception {
        assertPhaseFailure(Point.OPEN_OUTPUT, 128, AiExtensionHttp.Kind.TRANSPORT, true, "open_output");
    }

    @Test public void writingTheRequestHasASeparateFailurePhaseAndIsNeverRetried() throws Exception {
        assertPhaseFailure(Point.WRITE_REQUEST, 128, AiExtensionHttp.Kind.TRANSPORT, true, "write_request");
    }

    @Test public void aProviderStatusTimeoutStaysInTheWaitingPhaseWithoutAnHttpStatus() throws Exception {
        List<JSONObject> stages = assertPhaseFailure(Point.WAIT_STATUS, 128, AiExtensionHttp.Kind.DEADLINE, true, "wait_status");
        assertFalse(names(stages).contains("image_http_status_received"));
        assertFalse(evidence(stages, "image_http_failure").contains("http_status="));
    }

    @Test public void anHttpErrorRecordsTheStatusWithoutOpeningItsResponseStream() throws Exception {
        Fixture fixture = new Fixture(Point.NONE, 503);
        DouAiLog.Operation trace = trace();
        assertEquals("", fixture.request(trace, 128));
        trace.finish(DouAiLog.Outcome.FAILED, "fixture_http_error", "");
        assertEquals(0, fixture.connection.bodyOpens.get());
        List<JSONObject> stages = stages(trace);
        assertTrue(evidence(stages, "image_http_status_received").contains("http_status=503"));
        assertFalse(names(stages).contains("image_http_read_response"));
        assertSafeEvidence(stages);
    }

    @Test public void generationTargetRecordsItsOwnMethodHostAndRedactedPathWithTheObservedStatus() throws Exception {
        String custom = "https://image-trace.example.test/private-provider-route/images/generations";
        Fixture fixture = new Fixture(Point.NONE, 405);
        DouAiLog.Operation trace = trace();
        assertEquals("", fixture.request(custom, trace, 128));
        trace.finish(DouAiLog.Outcome.FAILED, "fixture_http_error", "");

        assertEquals(1, fixture.opens.get());
        assertEquals(custom, fixture.connection.getURL().toString());
        assertEquals(0, fixture.connection.bodyOpens.get());
        List<JSONObject> stages = stages(trace);
        assertEquals("method=POST;host=image-trace.example.test;path=/<redacted>/images/generations",
                evidence(stages, "image_http_dispatch"));
        assertTrue(evidence(stages, "image_http_status_received").contains("http_status=405"));
        assertTrue(records.stream().noneMatch(line -> line.contains("private-provider-route")));
        assertSafeEvidence(stages);
    }

    @Test public void responseStreamFailureKeepsThePreviouslyReceivedStatus() throws Exception {
        List<JSONObject> stages = assertPhaseFailure(Point.READ_RESPONSE, 128, AiExtensionHttp.Kind.TRANSPORT, true,
                "read_response");
        assertTrue(evidence(stages, "image_http_status_received").contains("http_status=200"));
    }

    @Test public void theBoundedResponseReportsTheLimitWithoutLoggingPayload() throws Exception {
        assertPhaseFailure(Point.NONE, 4, AiExtensionHttp.Kind.RESPONSE_LIMIT, true, "read_response");
    }

    @Test public void aNullImageTraceDoesNotCaptureAnAmbientCatalogueOrMcpOperation() throws Exception {
        Fixture fixture = new Fixture(Point.NONE, 200);
        DouAiLog.Operation ambient = DouAiLog.begin("fixture_nonimage", "unit_test");
        ambient.wrap(() -> {
            try { assertEquals(RESPONSE, fixture.request(null, 128)); }
            catch (AiExtensionHttp.Failure failure) { throw new AssertionError(failure); }
        }).run();
        ambient.finish(DouAiLog.Outcome.SUCCESS, "fixture_validated", "");
        assertTrue(stages(ambient).isEmpty());
        assertEquals(1, fixture.opens.get());
    }

    private List<JSONObject> assertPhaseFailure(Point point, int limit, AiExtensionHttp.Kind kind,
                                                boolean mayHaveSent, String phase) throws Exception {
        Fixture fixture = new Fixture(point, 200);
        DouAiLog.Operation trace = trace();
        AiExtensionHttp.Failure failure = assertThrows(AiExtensionHttp.Failure.class,
                () -> fixture.request(trace, limit));
        trace.finish(DouAiLog.Outcome.FAILED, "fixture_failed", "");
        assertEquals(kind, failure.kind);
        assertEquals(mayHaveSent, failure.mayHaveSent);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains(RAW_FAILURE));
        assertEquals(1, fixture.opens.get());

        List<JSONObject> stages = stages(trace);
        String facts = evidence(stages, "image_http_failure");
        assertTrue(facts.contains("phase=" + phase));
        assertTrue(facts.contains("kind=" + kind.name().toLowerCase(Locale.ROOT)));
        assertSafeEvidence(stages);
        return stages;
    }

    private static DouAiLog.Operation trace() {
        return DouAiLog.begin("fixture_image_transport", "unit_test");
    }

    private List<JSONObject> stages(DouAiLog.Operation trace) throws Exception {
        assertTrue(DouAiLog.awaitIdle(5000));
        List<JSONObject> stages = new ArrayList<>();
        for (String line : records) {
            JSONObject record = new JSONObject(line);
            if (trace.traceId().equals(record.optString("trace_id")) && "stage".equals(record.optString("event"))
                    && record.optString("stage").startsWith("image_http_")) stages.add(record);
        }
        return stages;
    }

    private static List<String> names(List<JSONObject> stages) {
        List<String> names = new ArrayList<>();
        for (JSONObject record : stages) names.add(record.optString("stage"));
        return names;
    }

    private static String evidence(List<JSONObject> stages, String name) {
        for (JSONObject record : stages) if (name.equals(record.optString("stage"))) return record.optString("evidence");
        throw new AssertionError("Missing phase " + name);
    }

    private void assertSafeEvidence(List<JSONObject> stages) {
        long priorElapsed = -1;
        for (JSONObject stage : stages) {
            if ("image_http_dispatch".equals(stage.optString("stage"))) {
                assertTrue(stage.optString("evidence").matches(
                        "method=POST;host=image-trace\\.example\\.test;path=/(?:v1|<redacted>)/images/generations"));
                continue;
            }
            boolean hasElapsed = false;
            for (String field : stage.optString("evidence").split(";")) {
                assertTrue("unexpected telemetry field", field.matches(
                        "(?:phase|kind)=[a-z_]+|http_status=[1-5][0-9]{2}|elapsed_ms=[0-9]+"));
                if (field.startsWith("elapsed_ms=")) {
                    long elapsed = Long.parseLong(field.substring("elapsed_ms=".length()));
                    assertTrue(elapsed >= priorElapsed);
                    priorElapsed = elapsed;
                    hasElapsed = true;
                }
            }
            assertTrue(hasElapsed);
        }
        assertTrue(records.stream().noneMatch(r -> r.contains(ENDPOINT) || r.contains(BEARER)
                || r.contains(PROMPT) || r.contains(RESPONSE) || r.contains(RAW_FAILURE)));
    }

    private enum Point { NONE, OPEN_CONNECTION, OPEN_OUTPUT, WRITE_REQUEST, WAIT_STATUS, READ_RESPONSE }

    private static final class Fixture {
        final AtomicInteger opens = new AtomicInteger();
        final Point failAt;
        final int status;
        FixtureConnection connection;

        Fixture(Point failAt, int status) {
            this.failAt = failAt;
            this.status = status;
        }

        String request(DouAiLog.Operation trace, int limit) throws AiExtensionHttp.Failure {
            return request(ENDPOINT, trace, limit);
        }

        String request(String endpoint, DouAiLog.Operation trace, int limit) throws AiExtensionHttp.Failure {
            return AiExtensionHttp.request(endpoint, "POST", Map.of("Authorization", "Bearer " + BEARER), REQUEST,
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(8), limit,
                    (code, headers, body) -> AiExtensionHttp.readUtf8(body), trace, this::open);
        }

        private HttpsURLConnection open(URI endpoint) throws IOException {
            opens.incrementAndGet();
            if (failAt == Point.OPEN_CONNECTION) throw new IOException(RAW_FAILURE);
            connection = new FixtureConnection(endpoint.toURL(), failAt, status);
            return connection;
        }
    }

    private static final class FixtureConnection extends HttpsURLConnection {
        final ByteArrayOutputStream written = new ByteArrayOutputStream();
        final AtomicInteger bodyOpens = new AtomicInteger();
        private final Point failAt;
        private final int status;

        FixtureConnection(URL url, Point failAt, int status) {
            super(url);
            this.failAt = failAt;
            this.status = status;
        }

        @Override public OutputStream getOutputStream() throws IOException {
            if (failAt == Point.OPEN_OUTPUT) throw new IOException(RAW_FAILURE);
            return new OutputStream() {
                @Override public void write(int value) throws IOException {
                    if (failAt == Point.WRITE_REQUEST) throw new IOException(RAW_FAILURE);
                    written.write(value);
                }
            };
        }

        @Override public int getResponseCode() throws IOException {
            if (failAt == Point.WAIT_STATUS) throw new SocketTimeoutException(RAW_FAILURE);
            return status;
        }

        @Override public InputStream getInputStream() throws IOException {
            bodyOpens.incrementAndGet();
            if (failAt == Point.READ_RESPONSE) {
                return new InputStream() {
                    @Override public int read() throws IOException { throw new IOException(RAW_FAILURE); }
                };
            }
            return new ByteArrayInputStream(RESPONSE.getBytes(StandardCharsets.UTF_8));
        }

        @Override public void connect() { throw new AssertionError("The fixture never starts a socket"); }
        @Override public void disconnect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public String getCipherSuite() { return "fixture"; }
        @Override public Certificate[] getLocalCertificates() { return null; }
        @Override public Certificate[] getServerCertificates() { return null; }
    }
}
