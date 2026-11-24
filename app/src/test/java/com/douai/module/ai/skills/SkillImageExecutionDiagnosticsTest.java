package com.douai.module.ai.skills;

import android.graphics.Bitmap;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.diagnostics.DouAiLog;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillImageExecutionDiagnosticsTest {
    private static final String ENDPOINT = "https://private-image-fixture.example.test/v1/images/generations";
    private static final String KEY = "fixture-authorization-secret-2714";
    private static final String MODEL = "private-image-model-fixture";
    private static final String PROMPT = "private-prompt-fixture-text";
    private static final String BODY_MARKER = "private-provider-body-fixture";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void receivedImagesReportDecodeOutcomeAndNeverLogPayloads() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicReference<SkillServices.Response> reply = new AtomicReference<>();
        SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, bearer, request, deadline, type) -> {
                    attempts.incrementAndGet();
                    assertTrue(ENDPOINT.equals(endpoint));
                    assertTrue(KEY.equals(bearer));
                    assertTrue(request.contains(PROMPT));
                    assertTrue(request.contains(MODEL));
                    return reply.get();
                });
        SkillServices.Service profile = services.configureForSkill("", "诊断生图",
                SkillServices.Adapter.OPENAI_IMAGES, ENDPOINT, MODEL, 1, KEY);
        services.authorize(profile, 1);

        List<String> events = new CopyOnWriteArrayList<>();
        boolean detailed = DebugLog.isEnabled();
        assertTrue(DouAiLog.awaitIdle(3000));
        DebugLog.setEnabled(true);
        DouAiLog.setFrameworkSink((priority, json) -> events.add(json));
        try {
            String encoded = imageData();
            reply.set(jsonImage(encoded));
            DouAiLog.Operation success = DouAiLog.begin("image_execution_fixture", "explicit_skill");
            try {
                assertTrue(success.call(() -> services.executeResult(profile, PROMPT)) instanceof SkillResult.Image);
            } finally {
                success.finish(DouAiLog.Outcome.SUCCESS, "test_completed", "");
            }
            assertEquals(1, attempts.get());

            reply.set(jsonImage("invalid*base64"));
            DouAiLog.Operation invalid = DouAiLog.begin("image_execution_fixture", "explicit_skill");
            try {
                SkillServices.Failure failure = assertThrows(SkillServices.Failure.class, () ->
                        invalid.call(() -> services.executeResult(profile, PROMPT)));
                assertEquals(SkillServices.Code.IMAGE_INVALID, failure.code());
            } finally {
                invalid.finish(DouAiLog.Outcome.FAILED, "test_completed", "");
            }
            assertEquals(2, attempts.get());
            assertTrue(DouAiLog.awaitIdle(5000));

            assertTrue(stage(events, success.traceId(), "image_decode").contains("http_status=200"));
            String successfulDecode = stage(events, success.traceId(), "image_decode_result");
            assertTrue(successfulDecode.contains("http_status=200"));
            assertTrue(successfulDecode.contains("outcome=success"));
            assertTrue(successfulDecode.matches(".*elapsed_ms=[0-9]+(?:;.*)?"));

            assertTrue(stage(events, invalid.traceId(), "image_decode").contains("http_status=200"));
            String failedDecode = stage(events, invalid.traceId(), "image_decode_result");
            assertTrue(failedDecode.contains("http_status=200"));
            assertTrue(failedDecode.contains("outcome=failed"));
            assertTrue(failedDecode.contains("code=IMAGE_INVALID"));
            assertTrue(failedDecode.matches(".*elapsed_ms=[0-9]+(?:;.*)?"));
            for (String event : events) {
                for (String privateValue : List.of(ENDPOINT, "private-image-fixture.example.test", KEY, MODEL, PROMPT,
                        BODY_MARKER, encoded, "invalid*base64")) {
                    assertFalse("Image diagnostics exposed fixture data", event.contains(privateValue));
                }
            }
        } finally {
            DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(detailed);
        }
    }

    @Test public void paidHttpAndTransportFailuresKeepTheirOutcomeAndDoNotReachImageDecode() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger status = new AtomicInteger(408);
        AtomicReference<AiExtensionHttp.Failure> transportFailure = new AtomicReference<>();
        SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, bearer, request, deadline, type) -> {
                    attempts.incrementAndGet();
                    AiExtensionHttp.Failure failure = transportFailure.get();
                    if (failure != null) throw failure;
                    return new SkillServices.Response(status.get(), "text/plain",
                            BODY_MARKER.getBytes(StandardCharsets.UTF_8));
                });
        SkillServices.Service profile = services.configureForSkill("", "诊断生图",
                SkillServices.Adapter.OPENAI_IMAGES, ENDPOINT, MODEL, 1, KEY);
        services.authorize(profile, 1);

        List<String> events = new CopyOnWriteArrayList<>();
        List<String> traceIds = new ArrayList<>();
        boolean detailed = DebugLog.isEnabled();
        assertTrue(DouAiLog.awaitIdle(3000));
        DebugLog.setEnabled(true);
        DouAiLog.setFrameworkSink((priority, json) -> events.add(json));
        try {
            int expectedAttempts = 0;
            for (int responseStatus : new int[]{408, 503}) {
                status.set(responseStatus);
                traceIds.add(failureTrace(services, profile, SkillServices.Code.UNKNOWN_RESULT));
                assertEquals(++expectedAttempts, attempts.get());
            }
            for (AiExtensionHttp.Kind kind : List.of(AiExtensionHttp.Kind.DEADLINE, AiExtensionHttp.Kind.CANCELLED)) {
                transportFailure.set(new AiExtensionHttp.Failure(kind, true));
                traceIds.add(failureTrace(services, profile, SkillServices.Code.UNKNOWN_RESULT));
                assertEquals(++expectedAttempts, attempts.get());

                transportFailure.set(new AiExtensionHttp.Failure(kind, false));
                SkillServices.Code expected = kind == AiExtensionHttp.Kind.CANCELLED
                        ? SkillServices.Code.CANCELLED : SkillServices.Code.UNAVAILABLE;
                traceIds.add(failureTrace(services, profile, expected));
                assertEquals(++expectedAttempts, attempts.get());
            }
            assertTrue(DouAiLog.awaitIdle(5000));
            for (String line : events) {
                JSONObject event = new JSONObject(line);
                if (!traceIds.contains(event.optString("trace_id"))) continue;
                assertFalse("Unsuccessful transport admitted image decoding", event.optString("stage").startsWith("image_decode"));
                for (String value : List.of(ENDPOINT, KEY, MODEL, PROMPT, BODY_MARKER)) {
                    assertFalse("Image diagnostics exposed fixture data", line.contains(value));
                }
            }
        } finally {
            DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(detailed);
        }
    }

    private static String failureTrace(SkillServices services, SkillServices.Service profile, SkillServices.Code expected) {
        DouAiLog.Operation trace = DouAiLog.begin("image_execution_fixture", "explicit_skill");
        try {
            SkillServices.Failure failure = assertThrows(SkillServices.Failure.class, () ->
                    trace.call(() -> services.executeResult(profile, PROMPT)));
            assertEquals(expected, failure.code());
            if (expected == SkillServices.Code.UNKNOWN_RESULT) {
                assertEquals("请求结果未知，请先核实", failure.getMessage());
            }
        } finally {
            trace.finish(DouAiLog.Outcome.FAILED, "test_completed", "");
        }
        return trace.traceId();
    }

    private static SkillServices.Response jsonImage(String encoded) throws Exception {
        JSONObject json = new JSONObject().put("imageBase64", encoded).put("mimeType", "image/png")
                .put("fixture", BODY_MARKER);
        return new SkillServices.Response(200, "application/json", json.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static String imageData() {
        Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        try {
            bitmap.eraseColor(0xFF27819C);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes));
            return Base64.getEncoder().encodeToString(bytes.toByteArray());
        } finally {
            bitmap.recycle();
        }
    }

    private static String stage(List<String> events, String traceId, String stage) throws Exception {
        for (String line : events) {
            JSONObject event = new JSONObject(line);
            if (traceId.equals(event.optString("trace_id")) && "stage".equals(event.optString("event"))
                    && stage.equals(event.optString("stage"))) return event.optString("evidence");
        }
        throw new AssertionError("Missing trace stage: " + stage);
    }
}
