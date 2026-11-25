package com.douai.module.ai.skills;

import android.content.Context;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SkillCustomServiceTest {
    private static final String TOKEN = "synthetic-douai-custom-secret-42";
    private static final String ENDPOINT = "https://hooks.example.com/v1/tidy";
    private static final String OTHER_ENDPOINT = "https://hooks.example.com/v1/other";
    private final AiCredentialVault vault = new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES"));
    private final String skillId = UUID.randomUUID().toString();

    public static SkillStore chatFixture(Context context, AtomicInteger calls) {
        SkillCustomService service = new SkillCustomService(AiCredentialVault.forDevice(),
                (endpoint, bearer, body, deadline, responseLimit, admission) -> {
                    assertEquals("https://skill-api.example.com/v1/process", endpoint);
                    assertEquals("", bearer);
                    assertEquals("{\"input\":\"样例\"}", new String(body, StandardCharsets.UTF_8));
                    admission.check();
                    calls.incrementAndGet();
                    return json(200, "{\"text\":\"测试端点结果\"}");
                }, System::nanoTime);
        File directory = new File(context.getApplicationContext().getNoBackupFilesDir(), "douai-ai/skills");
        return new SkillStore(directory, SkillServices.forContext(context), null, service);
    }

    @Test public void declaredSettingsProduceTheExactReviewedTypedRequest() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        String endpoint = "https://skill-api.example.com/v1/process";
        SkillCustomService service = new SkillCustomService(vault, (address, bearer, body, deadline, limit, preflight) -> {
            assertEquals(endpoint, address);
            assertEquals("", bearer);
            preflight.check();
            calls.incrementAndGet();
            JSONObject request = SkillAuthoring.readObject(new String(body, StandardCharsets.UTF_8), 500);
            assertEquals("原稿", request.optString("source"));
            assertEquals(4, request.optInt("limit"));
            return json(200, "{\"result\":{\"columns\":[\"条目\"],\"rows\":[[\"内容\"]]}}");
        }, System::nanoTime);
        Context context = org.robolectric.RuntimeEnvironment.getApplication();
        File root = Files.createTempDirectory(context.getCacheDir().toPath(), "skill-map-").toFile();
        SkillStore store = new SkillStore(root, SkillServices.forContext(context), null, service);
        SkillStore.DraftPreview proposal = SkillStore.validateAuthoredDraft("""
                {"schemaVersion":4,"name":"结构整理","description":"将资料整理为结构化表格","documentation":"输入原稿",
                 "instructions":"结构化整理","capabilityId":"custom_service",
                 "configFields":[{"key":"count","label":"最多行数","type":"integer","required":true,"min":1,"max":10}],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":2000}],
                 "outputType":"table","trial":{"input":"示例","expectedType":"table"},
                 "http":{"version":1,"label":"结构服务","suggestedEndpoint":"https://example.org/draft",
                 "request":{"source":"argument","limit":"config.count"},"response":"result"}}
                """);
        assertNotNull(store.saveAuthoredDraft(proposal).pending());
        assertTrue(store.selectionCatalog().isEmpty());
        assertNull(store.detail("结构整理").customConfiguration());
        SkillStore.Detail partial = store.configureCustomService(store.detail("结构整理"), endpoint, "");
        assertTrue(partial.pending());
        assertTrue(partial.fields().stream().anyMatch(field -> field.id().equals("count") && field.state() == SkillStore.FieldState.MISSING));
        SkillStore.Detail enabled = store.configureCustomService(partial, endpoint, "", false, Map.of("count", "4"));
        assertFalse(enabled.pending());
        assertEquals(List.of("最多行数"), store.selectionCatalog().get(0).parameterLabels());
        AiSkills.Invocation call = AiSkills.match(AiSkills.Entry.CONTACT, "/结构整理 原稿", store);
        assertTrue(call.command().requiresReview());
        SkillStore.CustomReview review = store.previewCustomService(call);
        assertEquals(endpoint, review.endpoint());
        assertEquals(4, new JSONObject(review.payload()).getInt("limit"));
        assertEquals(0, calls.get());
        SkillStore.CustomCall old = store.approveCustomService(review);
        store.setSkillEnabled("结构整理", false);
        assertTrue(store.selectionCatalog().isEmpty());
        assertThrows(IllegalStateException.class, () -> store.executeCustomService(call, old, deadline(), null));
        store.setSkillEnabled("结构整理", true);
        assertThrows(Exception.class, () -> store.executeCustomService(call, old, deadline(), null));
        assertEquals(0, calls.get());
        SkillStore.CustomCall approved = store.approveCustomService(store.previewCustomService(call));
        SkillResult.Table result = (SkillResult.Table) store.executeCustomService(call, approved, deadline(), null);
        assertEquals(List.of(List.of("内容")), result.table().rows());
        assertEquals(1, calls.get());
    }

    @Test public void reviewedInputAndExactMappingAreDispatchedOnlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            calls.incrementAndGet();
            assertEquals(ENDPOINT, endpoint);
            assertEquals(TOKEN, bearer);
            sent.set(new String(body, StandardCharsets.UTF_8));
            return json(200, "{\"text\":\"已读\"}");
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, TOKEN);
        assertFalse(binding.encryptedBearer().contains(TOKEN));
        assertFalse(binding.toString().contains(binding.encryptedBearer()));
        SkillCustomService.Preview preview = service.preview(binding, "周三\n值班");
        assertEquals(skillId, preview.skillId());
        assertEquals(binding.revision(), preview.revision());
        assertEquals("周三\n值班", preview.argument());
        assertEquals(ENDPOINT, preview.endpoint());
        assertTrue(preview.matches(binding));
        assertEquals(0, calls.get());
        SkillCustomService.Approval approval = service.approve(preview);
        assertEquals(0, calls.get());
        assertFalse(preview.toString().contains(TOKEN));
        assertFalse(approval.toString().contains(TOKEN));

        assertEquals("已读", service.execute(approval, binding, deadline()).text());
        JSONObject request = new JSONObject(sent.get());
        assertEquals(1, request.length());
        assertEquals("周三\n值班", request.getString("input"));
        assertFalse(sent.get().contains(TOKEN));
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(approval, binding, deadline())).code());
        assertEquals(1, calls.get());
    }

    @Test public void differentInputChangesDigestAndConfigurationChangesBurnOldApproval() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = counter(calls);
        SkillCustomService.Binding initial = service.configure(skillId, ENDPOINT, null);
        SkillCustomService.Preview first = service.preview(initial, "原稿");
        assertNotEquals(first.digest(), service.preview(initial, "另一份原稿").digest());
        SkillCustomService.Approval approval = service.approve(first);
        SkillCustomService.Binding changed = service.configure(skillId, OTHER_ENDPOINT, TOKEN);
        assertNotEquals(first.digest(), service.preview(changed, "原稿").digest());
        assertFalse(first.matches(changed));
        assertEquals(SkillCustomService.Code.STALE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(approval, changed, deadline())).code());
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(approval, initial, deadline())).code());
        assertEquals(0, calls.get());
    }

    @Test public void encryptedBindingReopensOnlyForItsOriginalSkillRevisionAndExactEndpoint() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService original = counter(calls);
        SkillCustomService.Binding saved = original.configure(skillId, ENDPOINT, TOKEN);
        SkillCustomService.Preview oldPreview = original.preview(saved, "原稿");
        SkillCustomService reopened = counter(calls);
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class, () -> reopened.approve(oldPreview)).code());
        SkillCustomService.Binding restored = new SkillCustomService.Binding(saved.skillId(), saved.revision(),
                saved.endpoint(), saved.encryptedBearer());
        assertEquals("ok", reopened.execute(reopened.approve(reopened.preview(restored, "原稿")),
                restored, deadline()).text());

        SkillCustomService.Binding copied = new SkillCustomService.Binding(UUID.randomUUID().toString(),
                saved.revision(), saved.endpoint(), saved.encryptedBearer());
        SkillCustomService.Binding rotatedWithoutResealing = new SkillCustomService.Binding(saved.skillId(),
                UUID.randomUUID().toString(), saved.endpoint(), saved.encryptedBearer());
        SkillCustomService.Binding moved = new SkillCustomService.Binding(saved.skillId(), saved.revision(),
                OTHER_ENDPOINT, saved.encryptedBearer());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> reopened.preview(copied, "原稿")).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> reopened.preview(rotatedWithoutResealing, "原稿")).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> reopened.preview(moved, "原稿")).code());
        assertEquals(1, calls.get());
    }

    @Test public void cancelledExpiredAndMissingApprovalDoNotDispatch() throws Exception {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            calls.incrementAndGet();
            return json(200, "{\"text\":\"ok\"}");
        }, now::get);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, "");
        SkillCustomService.Preview cancelled = service.preview(binding, "文本");
        service.cancel(cancelled);
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class, () -> service.approve(cancelled)).code());
        SkillCustomService.Preview expired = service.preview(binding, "文本");
        now.addAndGet(TimeUnit.HOURS.toNanos(1));
        assertEquals(SkillCustomService.Code.DEADLINE,
                assertThrows(SkillCustomService.Failure.class, () -> service.approve(expired)).code());
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(null, binding, deadline())).code());
        assertEquals(0, calls.get());
    }

    @Test public void repeatedInvocationDuringAnExchangeDoesNotDispatchAnotherAttempt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            calls.incrementAndGet();
            entered.countDown();
            try {
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException();
            }
            return json(200, "{\"text\":\"ok\"}");
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, "");
        SkillCustomService.Approval approval = service.approve(service.preview(binding, "文本"));
        var executor = Executors.newSingleThreadExecutor();
        try {
            Future<SkillResult.Text> started = executor.submit(() -> service.execute(approval, binding, deadline()));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                    assertThrows(SkillCustomService.Failure.class,
                            () -> service.execute(approval, binding, deadline())).code());
            release.countDown();
            assertEquals("ok", started.get(5, TimeUnit.SECONDS).text());
            assertEquals(1, calls.get());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test public void rejectsCredentialEchoAndAmbiguousFailureWithoutRetry() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            if (calls.incrementAndGet() == 1) return json(200, "{\"text\":\"echo " + TOKEN + "\"}");
            throw new IOException("diagnostic from remote exposing " + TOKEN);
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, TOKEN);
        assertEquals(SkillCustomService.Code.INVALID_INPUT,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.preview(binding, "Bearer " + TOKEN)).code());
        assertEquals(SkillCustomService.Code.INVALID_INPUT,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.preview(binding, "不应显示 " + TOKEN)).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.configure(skillId, ENDPOINT, TOKEN + "\r\nX-Not-Allowed: anything")).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.configure(skillId, ENDPOINT + "/" + TOKEN, TOKEN)).code());
        assertEquals(SkillCustomService.Code.BAD_RESPONSE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(service.approve(service.preview(binding, "文本")), binding, deadline())).code());
        SkillCustomService.Failure unknown = assertThrows(SkillCustomService.Failure.class,
                () -> service.execute(service.approve(service.preview(binding, "文本")), binding, deadline()));
        assertEquals(SkillCustomService.Code.UNKNOWN_RESULT, unknown.code());
        assertNull(unknown.getCause());
        assertFalse(unknown.toString().contains(TOKEN));
        assertEquals(2, calls.get());
    }

    @Test public void redirectsAndExtraResponseFieldsAreRejectedAndServerFailureIsAmbiguous() throws Exception {
        ArrayDeque<SkillCustomService.Reply> replies = new ArrayDeque<>(List.of(
                json(302, "{\"text\":\"redirect\"}"),
                json(503, "{\"text\":\"busy\"}"),
                json(200, "{\"text\":\"ok\",\"next\":\"https://other.test\"}"),
                json(200, "{\"text\":\"first\",\"text\":\"second\"}"),
                json(200, "{\"text\":\"\\ud800\"}")));
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            calls.incrementAndGet();
            return replies.removeFirst();
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, "");
        assertEquals(SkillCustomService.Code.INVALID_INPUT,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.preview(binding, "甲".repeat(2001))).code());
        for (SkillCustomService.Code expected : List.of(SkillCustomService.Code.REMOTE_REFUSED,
                SkillCustomService.Code.UNKNOWN_RESULT, SkillCustomService.Code.BAD_RESPONSE,
                SkillCustomService.Code.BAD_RESPONSE, SkillCustomService.Code.BAD_RESPONSE)) {
            SkillCustomService.Approval approval = service.approve(service.preview(binding, "文本"));
            assertEquals(expected, assertThrows(SkillCustomService.Failure.class,
                    () -> service.execute(approval, binding, deadline())).code());
        }
        assertEquals(5, calls.get());
        assertTrue(replies.isEmpty());
    }

    @Test public void endpointValidationAndPreviewNeverDispatchAndOldSimulationIsRejected() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = counter(calls);
        for (String endpoint : List.of("https://skill-mock.invalid/v1/text", "http://hooks.example.com/v1/tidy",
                "https://user@hooks.example.com/", "https://hooks.example.com/?q=x", "https://hooks.example.com/#part",
                "https://127.0.0.1/v1/tidy", "https://localhost/v1/tidy")) {
            assertEquals(SkillCustomService.Code.ENDPOINT_INVALID,
                    assertThrows(SkillCustomService.Failure.class,
                            () -> service.configure(skillId, endpoint, "")).code());
        }
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, "");
        assertEquals(ENDPOINT, service.preview(binding, "拟发送文本").endpoint());
        assertEquals(0, calls.get());
    }

    @Test public void refusesCopyingTheSavedKeyIntoAChangedEndpointButCanClearWithoutOpeningIt() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = counter(calls);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, TOKEN);
        SkillCustomService.Failure echoed = assertThrows(SkillCustomService.Failure.class,
                () -> service.verifyEndpointOmitsCredential(binding, OTHER_ENDPOINT + "/" + TOKEN));
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE, echoed.code());
        assertFalse(echoed.toString().contains(TOKEN));
        service.verifyEndpointOmitsCredential(binding, OTHER_ENDPOINT);

        SkillCustomService lostKey = new SkillCustomService(new AiCredentialVault(() -> { throw new IOException(); }),
                (endpoint, bearer, body, deadline, responseLimit, admission) -> {
                    throw new AssertionError("Review and clear must not open a connection");
                }, System::nanoTime);
        lostKey.verifyEndpointOmitsCredential(binding, ENDPOINT);
        assertTrue(lostKey.configure(skillId, ENDPOINT, "").encryptedBearer().isEmpty());
        assertEquals(0, calls.get());
    }

    @Test public void changesAndExpiryDuringConnectionBlockTheActualDispatch() throws Exception {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger connections = new AtomicInteger();
        AtomicInteger sends = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            if (connections.incrementAndGet() == 2) now.addAndGet(TimeUnit.MINUTES.toNanos(3));
            admission.check();
            sends.incrementAndGet();
            return json(200, "{\"text\":\"ok\"}");
        }, now::get);
        SkillCustomService.Binding binding = service.configure(skillId, ENDPOINT, "");
        SkillCustomService.Approval approval = service.approve(service.preview(binding, "文本"));
        SkillCustomService.Failure stale = assertThrows(SkillCustomService.Failure.class,
                () -> service.execute(approval, binding, TimeUnit.MINUTES.toNanos(5), () -> {
                    throw new IOException("must not be shown: " + TOKEN);
                }));
        assertEquals(SkillCustomService.Code.STALE, stale.code());
        assertFalse(stale.toString().contains(TOKEN));
        assertEquals(SkillCustomService.Code.DEADLINE,
                assertThrows(SkillCustomService.Failure.class, () -> service.execute(
                        service.approve(service.preview(binding, "文本")), binding, TimeUnit.MINUTES.toNanos(5))).code());
        assertEquals(2, connections.get());
        assertEquals(0, sends.get());
    }

    @Test public void sharedRateGateBlocksBeforeTransportAndDoesNotRestoreAnApproval() throws Exception {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger connections = new AtomicInteger();
        SkillCustomService.Transport transport = (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            connections.incrementAndGet();
            admission.check();
            return json(200, "{\"text\":\"ok\"}");
        };
        SkillCustomService.RateGate gate = new SkillCustomService.RateGate();
        SkillCustomService first = new SkillCustomService(vault, transport, now::get, gate);
        SkillCustomService second = new SkillCustomService(vault, transport, now::get, gate);
        SkillCustomService.Binding binding = first.configure(skillId, ENDPOINT, "");
        for (int call = 0; call < 6; call++) {
            assertEquals("ok", first.execute(first.approve(first.preview(binding, "文本")), binding,
                    now.get() + TimeUnit.SECONDS.toNanos(5)).text());
        }
        SkillCustomService.Approval limited = second.approve(second.preview(binding, "文本"));
        assertEquals(SkillCustomService.Code.RATE_LIMITED,
                assertThrows(SkillCustomService.Failure.class, () -> second.execute(limited, binding,
                        now.get() + TimeUnit.SECONDS.toNanos(5))).code());
        assertEquals(6, connections.get());
        now.addAndGet(TimeUnit.MINUTES.toNanos(1));
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class, () -> second.execute(limited, binding,
                        now.get() + TimeUnit.SECONDS.toNanos(5))).code());
        assertEquals("ok", second.execute(second.approve(second.preview(binding, "文本")), binding,
                now.get() + TimeUnit.SECONDS.toNanos(5)).text());
        assertEquals(7, connections.get());
    }

    private SkillCustomService counter(AtomicInteger calls) {
        return new SkillCustomService(vault, (endpoint, bearer, body, deadline, responseLimit, admission) -> {
            admission.check();
            calls.incrementAndGet();
            return json(200, "{\"text\":\"ok\"}");
        }, System::nanoTime);
    }

    private static SkillCustomService.Reply json(int status, String json) {
        return new SkillCustomService.Reply(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(10); }
}
