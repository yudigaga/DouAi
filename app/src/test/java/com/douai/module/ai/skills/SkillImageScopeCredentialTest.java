package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SkillImageScopeCredentialTest {
    private static final String IMAGE = "https://images.example.test/v1/images/generations";
    private static final String CHAT = "https://images.example.test/v1/chat/completions";
    private static final String NAME = "测试图片";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void shortAndFullLengthKeysReopenWithoutGrantingGeneration() throws Exception {
        Fixture fixture = new Fixture();
        List<String> keys = List.of("x", "a".repeat(4000));
        for (String key : keys) {
            var profile = fixture.services.configureForSkill("", NAME, SkillServices.Adapter.OPENAI_IMAGES,
                    IMAGE, "fixture", 0, " \u00a0bEaReR\t" + key + "\r\n");
            SkillServices reopened = fixture.withVault(fixture.vault);
            assertEquals(profile, reopened.scoped(profile.id(), NAME));
            assertTrue(reopened.hasCredential(profile));
            assertFalse(reopened.isAuthorized(profile));
            assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED,
                    assertThrows(SkillServices.Failure.class, () -> reopened.executeResult(profile, "画花")).code());
            reopened.authorize(profile, 0);
            assertEquals(SkillServices.Code.CREDENTIAL_REQUIRED,
                    assertThrows(SkillServices.Failure.class, () -> reopened.executeResult(profile, "画花")).code());
        }
        assertEquals(keys, fixture.sentKeys);
    }

    @Test public void rejectsEmbeddedLineBreaksAndKeepsTheGenericKeyPolicy() throws Exception {
        Fixture fixture = new Fixture();
        for (String invalid : List.of("x\r\nX-Injection: y", "Bearer\t\r\nx", "Bearer Bearer x", "Bearer  ",
                "a".repeat(4001))) {
            assertEquals(SkillServices.Code.INVALID_INPUT,
                    assertThrows(SkillServices.Failure.class, () -> fixture.services.configureForSkill("", NAME,
                            SkillServices.Adapter.OPENAI_IMAGES, IMAGE, "fixture", 0, invalid)).code());
        }
        var ordinary = new SkillServices.Service("ordinary", "普通服务", IMAGE, 0);
        assertEquals(SkillServices.Code.INVALID_INPUT,
                assertThrows(SkillServices.Failure.class, () -> fixture.services.register(ordinary, "x")).code());
        assertEquals(2, fixture.services.listForSkill(NAME).size());
        assertTrue(fixture.sentKeys.isEmpty());
    }

    @Test public void oldExactChatBindingOpensOldCiphertextButCannotSendAnImagePost() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals(SkillServices.Code.INVALID_INPUT,
                assertThrows(SkillServices.Failure.class, () -> fixture.services.configureForSkill("", NAME,
                        SkillServices.Adapter.OPENAI_IMAGES, CHAT, "fixture", 0, "legacy-fixture-key")).code());

        String id = "skill-ea6402f6-3cd3-401b-8fe5-7c5a8e22c1c8";
        String ciphertext = fixture.vault.seal(AiCredentialVault.Scope.SKILL_SERVICE, id, CHAT, "legacy-fixture-key");
        JSONObject row = new JSONObject().put("id", id).put("label", NAME).put("endpoint", CHAT).put("costUnits", 0)
                .put("outputType", "image").put("adapter", "OPENAI_IMAGES").put("modelName", "fixture")
                .put("skillName", NAME).put("ciphertext", ciphertext).put("maximumCost", 0);
        Files.writeString(new File(fixture.directory, "skill-services.json").toPath(),
                new JSONObject().put("version", 1).put("services", new JSONArray().put(row)).toString());
        var legacy = fixture.services.scoped(id, NAME);
        assertEquals(CHAT, legacy.endpoint());
        assertTrue(fixture.services.hasCredential(legacy));
        assertTrue(fixture.services.isAuthorized(legacy));
        assertEquals(SkillServices.Code.INVALID_INPUT,
                assertThrows(SkillServices.Failure.class, () -> fixture.services.executeResult(legacy, "画花")).code());
        assertTrue(fixture.sentKeys.isEmpty());
    }

    @Test public void failedFreshKeyRoundTripDoesNotReplaceExistingScopeOrGrant() throws Exception {
        Fixture fixture = new Fixture();
        var previous = fixture.services.configureForSkill("", NAME, SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, "fixture", 0, "existing-fixture-key");
        fixture.services.authorize(previous, 0);
        String changedKey = "changed-fixture-key";
        AtomicInteger reads = new AtomicInteger();
        SkillServices unstable = fixture.withVault(new AiCredentialVault(() -> key(reads.incrementAndGet() == 1 ? 0 : 1)));

        SkillServices.Failure failure = assertThrows(SkillServices.Failure.class, () ->
                unstable.configureForSkill(previous.id(), NAME, previous.adapter(),
                        IMAGE, "changed-model", 5, changedKey));

        assertEquals(SkillServices.Code.CREDENTIAL_REQUIRED, failure.code());
        assertEquals("密钥保存校验失败，未写入配置", failure.getMessage());
        assertFalse(failure.getMessage().contains(changedKey));
        assertNull(failure.getCause());
        assertEquals(2, reads.get());
        assertEquals(previous, fixture.services.scoped(previous.id(), NAME));
        assertTrue(fixture.services.hasCredential(previous));
        assertTrue(fixture.services.isAuthorized(previous));
        assertTrue(fixture.sentKeys.isEmpty());
    }

    private static SecretKeySpec key(int marker) {
        byte[] bytes = new byte[32];
        bytes[0] = (byte) marker;
        return new SecretKeySpec(bytes, "AES");
    }

    private final class Fixture {
        final File directory = temp.newFolder();
        final List<String> sentKeys = new ArrayList<>();
        final AiCredentialVault vault = new AiCredentialVault(() -> key(0));
        final SkillServices services = withVault(vault);

        Fixture() throws Exception {}

        SkillServices withVault(AiCredentialVault source) {
            return new SkillServices(directory, source, (endpoint, bearer, request, deadline, output) -> {
                assertEquals(IMAGE, endpoint);
                sentKeys.add(bearer);
                return new SkillServices.Response(403, "application/json", new byte[0]);
            });
        }
    }
}
