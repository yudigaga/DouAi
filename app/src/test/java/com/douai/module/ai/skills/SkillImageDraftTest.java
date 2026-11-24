package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SkillImageDraftTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void distinguishesRequestedImagesFromTextAboutImages() {
        assertTrue(SkillStore.requiresImage("生图"));
        assertTrue(SkillStore.requiresImage("做一个返回一张图片的技能"));
        assertFalse(SkillStore.requiresImage("请帮我总结生图教程"));
        assertFalse(SkillStore.requiresImage("生成图片提示词"));
    }

    @Test public void shortImageRequestStaysPendingAcrossReopenAndNeverRegistersACommand() throws Exception {
        Fixture fixture = new Fixture();
        String generated = SkillStore.mockDraft("生图");
        SkillStore.Preview preview = fixture.store.previewGenerated(generated);
        assertEquals(SkillResult.Type.IMAGE, preview.outputType());
        assertTrue(preview.pendingReason().contains("授权"));
        assertNotNull(fixture.store.saveGenerated(generated, null).pending());

        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertEquals(1, reopened.pending().size());
        assertEquals("生图", reopened.pending().get(0).name());
        assertTrue(reopened.list().isEmpty());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertTrue(AiSkills.commands(entry, "/生", reopened).isEmpty());
            assertThrows(AiSkills.CommandUnavailable.class,
                    () -> AiSkills.match(entry, "/生图 一只猫", reopened));
        }
        assertEquals(0, fixture.network.get());
    }

    @Test public void aPreviouslyPendingDraftBindsAuthorizedImageServiceAndItsRevocationFails() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveGenerated(SkillStore.mockDraft("生图"), null);
        SkillServices.Service mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        String pending = fixture.store.pendingDefinition("生图");
        SkillStore.Preview ready = fixture.store.previewGenerated(pending);
        assertEquals(mock.id(), ready.serviceId());
        assertFalse(ready.needsConfirmation());
        assertNotNull(fixture.store.connectPending(pending, null).installed());
        assertTrue(fixture.store.pending().isEmpty());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var invocation = AiSkills.match(entry, "/生图 画一只猫", fixture.store);
            assertEquals(SkillResult.Type.IMAGE, invocation.command().outputType());
            assertTrue(AiSkills.executeResult(invocation, fixture.store) instanceof SkillResult.Image);
        }
        fixture.services.revoke(mock);
        var revoked = AiSkills.match(AiSkills.Entry.WORK, "/生图 画一只猫", fixture.store);
        assertThrows(SkillServices.Failure.class, () -> AiSkills.executeResult(revoked, fixture.store));
        fixture.store.setSkillEnabled("生图", false);
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.WORK, "/生图 画一只猫", fixture.store));
        assertEquals(0, fixture.network.get());
    }

    @Test public void imageDraftDoesNotOverwriteSameNameTextEvenAfterServiceIsAuthorized() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.installGenerated(textDraft("生图"));
        String generated = SkillStore.mockDraft("生图");
        assertNotNull(fixture.store.saveGenerated(generated, null).pending());
        var sameName = AiSkills.match(AiSkills.Entry.WORK, "/生图 文本", fixture.store);
        assertEquals(new SkillResult.Text("文本"), AiSkills.executeResult(sameName, fixture.store));
        assertEquals(1, fixture.store.list().get(0).version());

        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        var confirmation = fixture.store.previewGenerated(generated);
        assertTrue(confirmation.needsConfirmation());
        assertEquals(1, confirmation.previousVersion());
        assertThrows(IllegalStateException.class, () -> fixture.store.saveGenerated(generated, null));
        assertEquals(new SkillResult.Text("文本"), AiSkills.executeResult(sameName, fixture.store));
        assertEquals(2, fixture.store.saveGenerated(generated, confirmation).installed().version());
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertEquals(1, reopened.detail("生图").deleteTarget().versionCount());
        assertTrue(reopened.pending().isEmpty());
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(sameName, reopened));
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var replacement = AiSkills.match(entry, "/生图 画一只猫", reopened);
            assertEquals(SkillResult.Type.IMAGE, replacement.command().outputType());
            assertTrue(AiSkills.executeResult(replacement, reopened) instanceof SkillResult.Image);
        }
        assertEquals(0, fixture.network.get());
    }

    @Test public void replacementCannotUseADifferentImageServiceThanTheOneShownForConfirmation() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.installGenerated(textDraft("生图"));
        var pending = fixture.store.saveGenerated(SkillStore.mockDraft("生图"), null).pending();
        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        var reviewed = fixture.store.retryConfiguration("生图", pending.draftId());
        assertTrue(reviewed.canReplace());
        assertEquals(mock, reviewed.service());

        var available = new SkillServices.Service("other-image", "另一个图片服务",
                "https://alternate.example.test/generate", 1, SkillResult.Type.IMAGE);
        fixture.services.register(available, "");
        fixture.services.authorize(available, 1);
        fixture.services.revoke(mock);
        assertEquals(available, fixture.store.detail("生图").service());

        assertThrows(IllegalStateException.class, () -> fixture.store.activateReplacement(reviewed));
        assertEquals(1, fixture.store.detail("生图").current().version());
        assertEquals(SkillResult.Type.TEXT, fixture.store.detail("生图").current().outputType());
        assertEquals(reviewed.draftId(), fixture.store.detail("生图").draftId());
        assertEquals(0, fixture.network.get());
    }

    @Test public void preflightForRealAuthorizedImageServiceUsesOnlyTheLocalImageFixture() throws Exception {
        Fixture fixture = new Fixture();
        var imageService = new SkillServices.Service("art", "图片服务", "https://images.example.test/image",
                5, SkillResult.Type.IMAGE);
        var textService = new SkillServices.Service("text", "文字服务", "https://text.example.test/api", 0);
        fixture.services.register(textService, "");
        fixture.services.authorize(textService, 0);
        assertNotNull(fixture.store.saveGenerated(SkillStore.mockDraft("生图"), null).pending());

        fixture.services.register(imageService, "");
        fixture.services.authorize(imageService, 5);
        String generated = fixture.store.pendingDefinition("生图");
        assertEquals("art", fixture.store.previewGenerated(generated).serviceId());
        var installed = fixture.store.saveGenerated(generated, null).installed();
        assertNotNull(installed);
        assertEquals(0, fixture.network.get());

        var invocation = AiSkills.match(AiSkills.Entry.CONTACT, "/生图 一只猫", fixture.store);
        assertThrows(IllegalStateException.class, () -> AiSkills.execute(invocation, fixture.store));
        assertEquals(0, fixture.network.get());
        SkillServices.Failure failure = assertThrows(SkillServices.Failure.class,
                () -> AiSkills.executeResult(invocation, fixture.store));
        assertEquals(SkillServices.Code.UNKNOWN_RESULT, failure.code());
        assertEquals(1, fixture.network.get());
    }

    @Test public void outputTypeAlsoWorksForAnArbitrarilyNamedImageSkill() throws Exception {
        Fixture fixture = new Fixture();
        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        String generated = SkillStore.mockDraft("请做个名为「封面设计」的生图技能");
        fixture.store.installGenerated(generated);
        var invocation = AiSkills.match(AiSkills.Entry.WORK, "/封面设计 勤奋小猫", fixture.store);
        assertEquals(SkillResult.Type.IMAGE, AiSkills.executeResult(invocation, fixture.store).type());
    }

    @Test public void reconnectCannotPublishAnOlderDefinitionAfterPendingDraftWasReplaced() throws Exception {
        Fixture fixture = new Fixture();
        String old = SkillStore.mockDraft("生图");
        fixture.store.saveGenerated(old, null);
        String updated = new JSONObject(old).put("description", "新草稿").toString();
        fixture.store.saveGenerated(updated, null);
        fixture.services.authorize(fixture.services.find(SkillServices.MOCK_IMAGE), 0);
        assertThrows(IllegalStateException.class, () -> fixture.store.connectPending(old, null));
        assertEquals(updated, fixture.store.pendingDefinition("生图"));
        assertTrue(fixture.store.list().isEmpty());
        assertNotNull(fixture.store.connectPending(updated, null).installed());
    }

    private static String textDraft(String name) throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("name", name).put("description", "旧文字技能")
                .put("documentation", "返回纯文字。")
                .put("execution", new JSONObject().put("kind", "summary").put("maxInput", 40))
                .put("trial", new JSONObject().put("input", "测试").put("expected", "测试")).toString();
    }

    private final class Fixture {
        final File directory = temp.newFolder();
        final AtomicInteger network = new AtomicInteger();
        final SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, outputType) -> {
                    network.incrementAndGet();
                    throw new AiExtensionHttp.Failure(AiExtensionHttp.Kind.DEADLINE, true);
                });
        final SkillStore store = new SkillStore(directory, services);

        Fixture() throws Exception {}
    }
}
