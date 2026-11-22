package com.douai.module.ai.skills;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SkillServicesTest {
    private static final String TOKEN_A = "synthetic-service-token-one";
    private static final String TOKEN_B = "synthetic-service-token-two";
    private static final SkillServices.Service ORDERS =
            new SkillServices.Service("shipping", "物流查询", "https://example.com/track", 2);
    private static final SkillServices.Service UPDATED =
            new SkillServices.Service("shipping", "物流查询", "https://example.com/track-v2", 2);
    private static final SkillServices.Service IMAGE = new SkillServices.Service("illustrate", "示例图片",
            "https://example.com/image", 2, SkillResult.Type.IMAGE);

    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void endpointEditDisarmsOldGrantAndDropsItsCredential() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(ORDERS, TOKEN_A);
        fixture.services.authorize(ORDERS, 2);
        assertEquals("已发货", fixture.services.execute(ORDERS, "DA-1001"));
        assertEquals(List.of(TOKEN_A), fixture.transport.tokens);

        fixture.services.register(UPDATED, "");
        assertFalse(fixture.services.isAuthorized(UPDATED));
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.authorize(ORDERS, 2)).code());
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.execute(ORDERS, "DA-1001")).code());
        fixture.services.authorize(UPDATED, 2);
        assertEquals("已发货", fixture.services.execute(UPDATED, "DA-1001"));
        assertEquals(List.of(ORDERS.endpoint(), UPDATED.endpoint()), fixture.transport.endpoints);
        assertEquals(List.of(TOKEN_A, ""), fixture.transport.tokens);
    }

    @Test public void credentialRotationAndCostChangeBothRequireNewApproval() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(ORDERS, TOKEN_A);
        fixture.services.authorize(ORDERS, 2);
        fixture.services.register(ORDERS, TOKEN_B);
        assertFalse(fixture.services.isAuthorized(ORDERS));
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.execute(ORDERS, "DA-1001")).code());

        SkillServices.Service higher = new SkillServices.Service(
                ORDERS.id(), ORDERS.label(), ORDERS.endpoint(), 3);
        fixture.services.register(higher, "");
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.authorize(higher, 2)).code());
        assertTrue(fixture.transport.endpoints.isEmpty());
        fixture.services.authorize(higher, 3);
        fixture.services.execute(higher, "DA-1001");
        assertEquals(List.of(TOKEN_B), fixture.transport.tokens);
    }

    @Test public void timedOutSentRequestIsNeverRetriedAndReportsUnknown() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(ORDERS, TOKEN_A);
        fixture.services.authorize(ORDERS, 2);
        fixture.transport.failure = new AiExtensionHttp.Failure(AiExtensionHttp.Kind.DEADLINE, true);

        SkillServices.Failure failure = failure(() -> fixture.services.execute(ORDERS, "DA-1001"));
        assertEquals(SkillServices.Code.UNKNOWN_RESULT, failure.code());
        assertEquals("请求结果未知，请先核实", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals(List.of(ORDERS.endpoint()), fixture.transport.endpoints);
    }

    @Test public void echoedCredentialNeverBecomesASkillResultOrAnExceptionDetail() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(ORDERS, TOKEN_A);
        fixture.services.authorize(ORDERS, 2);
        fixture.transport.reply = "{\"text\":\"Bearer " + TOKEN_A + "\"}";

        SkillServices.Failure failure = failure(() -> fixture.services.execute(ORDERS, "DA-1001"));
        assertEquals(SkillServices.Code.REJECTED, failure.code());
        assertFalse(failure.getMessage().contains(TOKEN_A));
        assertNull(failure.getCause());
    }

    @Test public void registrySurvivesReopenWithCiphertextAndNoCredentialInList() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(ORDERS, TOKEN_A);
        fixture.services.authorize(ORDERS, 2);
        String disk = Files.readString(new File(fixture.directory, "skill-services.json").toPath(),
                StandardCharsets.UTF_8);
        assertFalse(disk.contains(TOKEN_A));
        SkillServices reopened = new SkillServices(fixture.directory, fixture.vault, fixture.transport);
        assertEquals(ORDERS, reopened.find(ORDERS.id()));
        assertFalse(reopened.list().toString().contains(TOKEN_A));
        reopened.execute(ORDERS, "DA-1001");
        assertEquals(List.of(TOKEN_A), fixture.transport.tokens);
    }

    @Test public void mockServiceBypassesRegistryCredentialAndNetwork() throws Exception {
        Fixture fixture = new Fixture();
        SkillServices.Service mock = fixture.services.find(SkillServices.MOCK_ORDERS);
        assertTrue(fixture.services.list().contains(mock));
        assertTrue(fixture.services.isAuthorized(mock));
        assertTrue(fixture.services.execute(mock, "DA-1001").contains("已发货"));
        assertTrue(fixture.transport.endpoints.isEmpty());
        assertEquals(0, fixture.directory.list().length);
    }

    @Test public void endpointRejectsUnencryptedAndEmbeddedSecrets() throws Exception {
        Fixture fixture = new Fixture();
        for (String endpoint : List.of("http://example.com/track",
                "https://secret@example.com/track", "https://example.com/track?key=secret",
                "https://example.com/track#secret")) {
            SkillServices.Service invalid = new SkillServices.Service("shipping", "物流", endpoint, 0);
            SkillServices.Failure failure = failure(() -> fixture.services.register(invalid, ""));
            assertEquals(SkillServices.Code.INVALID_INPUT, failure.code());
            assertFalse(failure.getMessage().contains("secret"));
        }
        assertTrue(fixture.transport.endpoints.isEmpty());
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void imageServiceNormalizesRawImageToAnInlineChatImage() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(IMAGE, "");
        fixture.services.authorize(IMAGE, 2);
        fixture.transport.mediaType = "image/png";
        fixture.transport.body = png();

        SkillResult.Image result = (SkillResult.Image) fixture.services.executeResult(IMAGE, "DA-1001");
        assertEquals(SkillResult.Type.IMAGE, result.type());
        assertEquals("image/jpeg", result.image().mimeType);
        byte[] bytes = Base64.getDecoder().decode(result.image().base64Data);
        Bitmap decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
        assertNotNull(decoded);
        assertEquals(8, decoded.getWidth());
        decoded.recycle();
        assertEquals(List.of(SkillResult.Type.IMAGE), fixture.transport.requestedTypes);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void explicitAndCommonInlineBase64ResponsesProduceImages() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(IMAGE, "");
        fixture.services.authorize(IMAGE, 2);
        String encoded = Base64.getEncoder().encodeToString(png());
        fixture.transport.reply = "{\"mimeType\":\"image/png\",\"imageBase64\":\"" + encoded + "\"}";
        assertTrue(fixture.services.executeResult(IMAGE, "DA-1001") instanceof SkillResult.Image);
        fixture.transport.reply = "{\"data\":[{\"b64_json\":\"" + encoded + "\"}]}";
        assertTrue(fixture.services.executeResult(IMAGE, "DA-1001") instanceof SkillResult.Image);
        assertEquals(2, fixture.transport.endpoints.size());
    }

    @Test public void textUrlOnlyAndInvalidRepliesAreRejectedWithoutReplayingAnyRequest() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(IMAGE, "");
        fixture.services.authorize(IMAGE, 2);
        fixture.transport.mediaType = "text/plain";
        fixture.transport.reply = "只返回文字";
        assertEquals(SkillServices.Code.IMAGE_MISSING,
                failure(() -> fixture.services.executeResult(IMAGE, "DA-1001")).code());
        fixture.transport.mediaType = "application/json";
        fixture.transport.reply = "{\"data\":[{\"url\":\"https://images.example.test/file.png\"}]}";
        assertEquals(SkillServices.Code.IMAGE_MISSING,
                failure(() -> fixture.services.executeResult(IMAGE, "DA-1001")).code());
        fixture.transport.reply = "{\"mimeType\":\"image/png\",\"imageBase64\":\"invalid!\"}";
        assertEquals(SkillServices.Code.IMAGE_INVALID,
                failure(() -> fixture.services.executeResult(IMAGE, "DA-1001")).code());
        assertEquals(List.of(IMAGE.endpoint(), IMAGE.endpoint(), IMAGE.endpoint()), fixture.transport.endpoints);
    }

    @Test public void outputTypeChangeDisarmsExactSameAddressAndLegacyTextExecuteDoesNotPostImageRequest()
            throws Exception {
        Fixture fixture = new Fixture();
        SkillServices.Service formerlyText = new SkillServices.Service(
                IMAGE.id(), IMAGE.label(), IMAGE.endpoint(), IMAGE.costUnits());
        fixture.services.register(formerlyText, TOKEN_A);
        fixture.services.authorize(formerlyText, 2);
        fixture.services.register(IMAGE, "");

        assertFalse(fixture.services.isAuthorized(IMAGE));
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.authorize(formerlyText, 2)).code());
        fixture.services.authorize(IMAGE, 2);
        assertEquals(SkillServices.Code.INVALID_INPUT, failure(() -> fixture.services.execute(IMAGE, "DA-1001")).code());
        assertTrue(fixture.transport.endpoints.isEmpty());
    }

    @Test public void legacyRegistryWithoutAnOutputTypeKeepsTextApproval() throws Exception {
        Fixture fixture = new Fixture();
        Files.writeString(new File(fixture.directory, "skill-services.json").toPath(), """
                {"version":1,"services":[{"id":"shipping","label":"物流查询",
                "endpoint":"https://example.com/track","costUnits":2,"ciphertext":"","maximumCost":2}]}
                """, StandardCharsets.UTF_8);

        SkillServices.Service service = fixture.services.find(ORDERS.id());
        assertEquals(SkillResult.Type.TEXT, service.outputType());
        assertTrue(fixture.services.isAuthorized(service));
        assertEquals("已发货", fixture.services.execute(service, "DA-1001"));
        assertEquals(List.of(SkillResult.Type.TEXT), fixture.transport.requestedTypes);
    }

    @Test @GraphicsMode(GraphicsMode.Mode.NATIVE)
    public void mockImageNeedsPersistedOptInAndCanBeRevokedWithoutUsingNetwork() throws Exception {
        Fixture fixture = new Fixture();
        SkillServices.Service mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        assertTrue(fixture.services.list().contains(mock));
        assertFalse(fixture.services.isAuthorized(mock));
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                failure(() -> fixture.services.executeResult(mock, "DA-1001")).code());
        fixture.services.authorize(mock, 0);

        SkillServices reopened = new SkillServices(fixture.directory, fixture.vault, fixture.transport);
        assertTrue(reopened.executeResult(mock, "DA-1001") instanceof SkillResult.Image);
        reopened.revoke(mock);
        assertFalse(fixture.services.isAuthorized(mock));
        assertTrue(fixture.transport.endpoints.isEmpty());
    }

    @Test public void imageCredentialEchoAndUnauthorizedStatusUseSafeFailures() throws Exception {
        Fixture fixture = new Fixture();
        fixture.services.register(IMAGE, TOKEN_A);
        fixture.services.authorize(IMAGE, 2);
        fixture.transport.status = 401;
        assertEquals(SkillServices.Code.CREDENTIAL_REQUIRED,
                failure(() -> fixture.services.executeResult(IMAGE, "DA-1001")).code());
        fixture.transport.status = 200;
        String encoded = Base64.getEncoder().encodeToString(TOKEN_A.getBytes(StandardCharsets.UTF_8));
        fixture.transport.reply = "{\"mimeType\":\"image/png\",\"imageBase64\":\"" + encoded + "\"}";
        SkillServices.Failure guarded = failure(() -> fixture.services.executeResult(IMAGE, "DA-1001"));
        assertEquals(SkillServices.Code.REJECTED, guarded.code());
        assertFalse(guarded.getMessage().contains(TOKEN_A));
        assertNull(guarded.getCause());
    }

    private static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(8, 6, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xFF27819C);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
            return output.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private interface Operation { void run() throws Exception; }

    private static SkillServices.Failure failure(Operation operation) throws Exception {
        try {
            operation.run();
            throw new AssertionError("操作应被拒绝");
        } catch (SkillServices.Failure expected) {
            return expected;
        }
    }

    private final class Fixture {
        final File directory;
        final FakeTransport transport = new FakeTransport();
        final AiCredentialVault vault;
        final SkillServices services;

        Fixture() throws Exception {
            SecretKey key = KeyGenerator.getInstance("AES").generateKey();
            vault = new AiCredentialVault(() -> key);
            directory = files.newFolder();
            services = new SkillServices(directory, vault, transport);
        }
    }

    private static final class FakeTransport implements SkillServices.Transport {
        final List<String> endpoints = new ArrayList<>();
        final List<String> tokens = new ArrayList<>();
        final List<SkillResult.Type> requestedTypes = new ArrayList<>();
        String reply = "{\"text\":\"已发货\"}";
        byte[] body;
        String mediaType = "application/json";
        int status = 200;
        AiExtensionHttp.Failure failure;

        @Override public SkillServices.Response post(String endpoint, String token, String request,
                                                     long deadlineNanos, SkillResult.Type type)
                throws AiExtensionHttp.Failure {
            endpoints.add(endpoint);
            tokens.add(token);
            requestedTypes.add(type);
            assertEquals("{\"input\":\"DA-1001\"}", request);
            if (failure != null) throw failure;
            return new SkillServices.Response(status, mediaType, body == null ? reply.getBytes(StandardCharsets.UTF_8) : body);
        }
    }
}
