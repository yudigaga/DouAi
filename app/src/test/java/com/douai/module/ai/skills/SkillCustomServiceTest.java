package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.List;
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
    private static final SkillCustomService.Preset MOCK = SkillCustomService.Preset.MOCK_TEXT;
    private final AiCredentialVault vault = new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES"));
    private final String skillId = UUID.randomUUID().toString();

    @Test public void reviewedInputAndExactMappingAreDispatchedOnlyOnce() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<String> sent = new AtomicReference<>();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
            calls.incrementAndGet();
            assertEquals("https://skill-mock.invalid/v1/text", endpoint);
            assertEquals("Bearer " + TOKEN, headers.get("Authorization"));
            sent.set(new String(body, StandardCharsets.UTF_8));
            return json(200, "{\"text\":\"已读\"}");
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, MOCK, TOKEN);
        assertFalse(binding.encryptedBearer().contains(TOKEN));
        assertFalse(binding.toString().contains(binding.encryptedBearer()));
        SkillCustomService.Preview preview = service.preview(binding, "周三\n值班");
        assertEquals(skillId, preview.skillId());
        assertEquals(binding.revision(), preview.revision());
        assertEquals("周三\n值班", preview.argument());
        assertEquals(MOCK, preview.preset());
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
        SkillCustomService.Binding initial = service.configure(skillId, MOCK, null);
        SkillCustomService.Preview first = service.preview(initial, "原稿");
        assertNotEquals(first.digest(), service.preview(initial, "另一份原稿").digest());
        SkillCustomService.Approval approval = service.approve(first);
        SkillCustomService.Binding changed = service.configure(skillId, MOCK, TOKEN);
        assertEquals(SkillCustomService.Code.STALE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(approval, changed, deadline())).code());
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.execute(approval, initial, deadline())).code());
        assertEquals(0, calls.get());
    }

    @Test public void encryptedBindingReopensOnlyForItsOriginalSkillAndRevision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService original = counter(calls);
        SkillCustomService.Binding saved = original.configure(skillId, MOCK, TOKEN);
        SkillCustomService.Preview oldPreview = original.preview(saved, "原稿");
        SkillCustomService reopened = counter(calls);
        assertEquals(SkillCustomService.Code.APPROVAL_REQUIRED,
                assertThrows(SkillCustomService.Failure.class, () -> reopened.approve(oldPreview)).code());
        SkillCustomService.Binding restored = new SkillCustomService.Binding(saved.skillId(), saved.revision(),
                SkillCustomService.Preset.fromId(saved.preset().id()), saved.encryptedBearer());
        assertEquals("ok", reopened.execute(reopened.approve(reopened.preview(restored, "原稿")),
                restored, deadline()).text());

        SkillCustomService.Binding copied = new SkillCustomService.Binding(UUID.randomUUID().toString(),
                saved.revision(), saved.preset(), saved.encryptedBearer());
        SkillCustomService.Binding rotatedWithoutResealing = new SkillCustomService.Binding(saved.skillId(),
                UUID.randomUUID().toString(), saved.preset(), saved.encryptedBearer());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> reopened.preview(copied, "原稿")).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> reopened.preview(rotatedWithoutResealing, "原稿")).code());
        assertEquals(1, calls.get());
    }

    @Test public void cancelledExpiredAndMissingApprovalDoNotDispatch() throws Exception {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
            calls.incrementAndGet();
            return json(200, "{\"text\":\"ok\"}");
        }, now::get);
        SkillCustomService.Binding binding = service.configure(skillId, MOCK, "");
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
        SkillCustomService service = new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
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
        SkillCustomService.Binding binding = service.configure(skillId, MOCK, "");
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

    @Test public void rejectsUnsupportedPresetCredentialEchoAndAmbiguousFailureWithoutRetry() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> SkillCustomService.Preset.fromId("https://other.test/"));
        AtomicInteger calls = new AtomicInteger();
        SkillCustomService service = new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
            if (calls.incrementAndGet() == 1) return json(200, "{\"text\":\"echo " + TOKEN + "\"}");
            throw new IOException("diagnostic from remote exposing " + TOKEN);
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, MOCK, TOKEN);
        assertEquals(SkillCustomService.Code.INVALID_INPUT,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.preview(binding, "Bearer " + TOKEN)).code());
        assertEquals(SkillCustomService.Code.INVALID_INPUT,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.preview(binding, "不应显示 " + TOKEN)).code());
        assertEquals(SkillCustomService.Code.CREDENTIAL_UNAVAILABLE,
                assertThrows(SkillCustomService.Failure.class,
                        () -> service.configure(skillId, MOCK, TOKEN + "\r\nX-Not-Allowed: anything")).code());
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
        SkillCustomService service = new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
            calls.incrementAndGet();
            return replies.removeFirst();
        }, System::nanoTime);
        SkillCustomService.Binding binding = service.configure(skillId, MOCK, "");
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

    @Test public void publicSimulationHandlesTheAppPresetWithoutAccountOrNetwork() throws Exception {
        SkillCustomService simulation = SkillCustomService.simulation();
        SkillCustomService.Binding binding = simulation.configure(skillId, MOCK, null);
        SkillCustomService.Approval approval = simulation.approve(simulation.preview(binding, "本地文本"));
        assertEquals("模拟服务：本地文本", simulation.execute(approval, binding, deadline()).text());
    }

    private SkillCustomService counter(AtomicInteger calls) {
        return new SkillCustomService(vault, (endpoint, headers, body, deadline) -> {
            calls.incrementAndGet();
            return json(200, "{\"text\":\"ok\"}");
        }, System::nanoTime);
    }

    private static SkillCustomService.Reply json(int status, String json) {
        return new SkillCustomService.Reply(status, "application/json", json.getBytes(StandardCharsets.UTF_8));
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(10); }
}
