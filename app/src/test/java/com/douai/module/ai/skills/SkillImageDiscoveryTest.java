package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SkillImageDiscoveryTest {
    private static final String NAME = "目录发现";
    private static final String IMAGE = "https://images.example.test/proxy/v1/images/generations";
    private static final String MODELS = "https://images.example.test/proxy/catalog";
    private static final String KEY = "fixture-image-credential-1234";
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void discoveryUsesPastedKeyWithoutSavingOrApprovingGeneration() throws Exception {
        Fixture fixture = new Fixture();
        var draft = fixture.pending();
        List<String> result = fixture.store.discoverImageModels(NAME, draft.draftId(), IMAGE, "",
                " \u00a0 bEaReR\t" + KEY + "\r\n ");

        assertEquals(List.of("image-fixture"), result);
        assertEquals(List.of("https://images.example.test/proxy/v1/models"), fixture.targets);
        assertEquals(List.of("Bearer " + KEY), fixture.authorizations);
        assertNull(fixture.store.detail(NAME).service());
        assertTrue(fixture.store.list().isEmpty());
        assertEquals(draft.draftId(), fixture.store.detail(NAME).draftId());
    }

    @Test public void savedKeyIsReusedOnlyOnMatchingDraftAndImageEndpoint() throws Exception {
        Fixture fixture = new Fixture();
        var draft = fixture.pending();
        var saved = fixture.store.saveConfiguration(NAME, draft.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "", 0, "  Bearer " + KEY + "\n");
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertEquals(MODELS, reopened.detail(NAME).service().modelsEndpoint());
        assertFalse(fixture.services.isAuthorized(saved.service()));
        assertEquals(List.of("image-fixture"), reopened.discoverImageModels(NAME, saved.draftId(), IMAGE, MODELS, ""));
        assertEquals(List.of(MODELS), fixture.targets);
        assertEquals(List.of("Bearer " + KEY), fixture.authorizations);

        assertThrows(Exception.class, () -> reopened.discoverImageModels(NAME, saved.draftId(),
                IMAGE + "-other", MODELS, ""));
        assertThrows(Exception.class, () -> reopened.discoverImageModels(NAME, saved.draftId(),
                IMAGE, "https://other.example.test/models", KEY));
        assertThrows(IllegalStateException.class, () ->
                reopened.discoverImageModels(NAME, draft.draftId(), IMAGE, MODELS, KEY));
        assertEquals(1, fixture.targets.size());
        assertTrue(reopened.list().isEmpty());
    }

    @Test public void visibleLateScopedBindingCanSupplyItsKeyWithoutChangingTheDraft() throws Exception {
        Fixture fixture = new Fixture();
        var pending = fixture.pending();
        var foreign = fixture.services.configureForSkill("", "别的技能", SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "image-fixture", 0, "fixture-foreign-image-key");
        fixture.services.authorize(foreign, 0);
        assertThrows(SkillServices.Failure.class, () ->
                fixture.store.discoverImageModels(NAME, pending.draftId(), IMAGE, MODELS, ""));
        assertTrue(fixture.targets.isEmpty());

        var owned = fixture.services.configureForSkill("", NAME, SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "image-fixture", 0, KEY);
        fixture.services.authorize(owned, 0);
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertEquals(owned, reopened.detail(NAME).service());
        assertThrows(SkillServices.Failure.class, () ->
                reopened.discoverImageModels(NAME, pending.draftId(), IMAGE + "-other", MODELS, ""));

        assertEquals(List.of("image-fixture"),
                reopened.discoverImageModels(NAME, pending.draftId(), IMAGE, MODELS, ""));
        assertEquals(List.of(MODELS), fixture.targets);
        assertEquals(List.of("Bearer " + KEY), fixture.authorizations);
        assertEquals(pending.draftId(), reopened.detail(NAME).draftId());
        assertTrue(reopened.list().isEmpty());
    }

    @Test public void retainedLegacyBaseLooksUpSiblingCatalogueWithoutChangingItsGenerationGrant() throws Exception {
        Fixture fixture = new Fixture();
        String base = "https://images.example.test/proxy/v1";
        var legacy = fixture.services.configureForSkill("", NAME, SkillServices.Adapter.OPENAI_IMAGES,
                base, "image-fixture", 0, KEY);
        fixture.services.authorize(legacy, 0);
        fixture.store.installGenerated(SkillStore.mockDraft("创建名为「" + NAME + "」的生图 Skill"));
        SkillStore.EditTarget editor = fixture.store.editTarget(fixture.store.detail(NAME).deleteTarget());
        var draft = fixture.store.saveEdit(editor, "新的说明", "请输入绘图要求。", "");
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);

        assertEquals(List.of("image-fixture"), reopened.discoverImageModels(NAME, draft.draftId(), base, "", ""));
        assertEquals(List.of(base + "/models"), fixture.targets);
        assertEquals(List.of("Bearer " + KEY), fixture.authorizations);
        assertEquals(legacy, fixture.services.scoped(legacy.id(), NAME));
        assertTrue(fixture.services.isAuthorized(legacy));
        assertEquals(1, reopened.list().get(0).version());
        assertEquals(draft.draftId(), reopened.detail(NAME).draftId());
    }

    @Test public void privateListOverrideMetadataDoesNotChangeExistingGenerationGrant() throws Exception {
        Fixture fixture = new Fixture();
        SkillServices.Service original = fixture.services.configureForSkill("", NAME, SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "image-fixture", 0, KEY);
        fixture.services.authorize(original, 0);
        SkillServices.Service updated = fixture.services.configureForSkill(original.id(), NAME, original.adapter(),
                IMAGE, MODELS + "-new", original.modelName(), original.costUnits(), "");
        assertTrue(fixture.services.isAuthorized(original));
        assertTrue(fixture.services.isAuthorized(updated));
        assertTrue(fixture.services.hasCredential(updated));
        assertEquals(MODELS + "-new", updated.modelsEndpoint());
        SkillServices.Service legacySave = fixture.services.configureForSkill(updated.id(), NAME, updated.adapter(),
                IMAGE, updated.modelName(), updated.costUnits(), "");
        assertEquals(updated.modelsEndpoint(), legacySave.modelsEndpoint());
        assertFalse(fixture.services.list().contains(updated));
        assertTrue(fixture.targets.isEmpty());
    }

    @Test public void retainedImageScopeKeepsItsCredentialAfterChangingOnlyTheCatalogueAddress() throws Exception {
        Fixture fixture = new Fixture();
        var draft = fixture.pending();
        var initial = fixture.store.saveConfiguration(NAME, draft.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "image-fixture", 0, KEY);
        var installed = fixture.store.authorizeConfiguration(NAME, initial.draftId(), initial.service());
        assertFalse(installed.pending());
        SkillServices.Service approved = installed.service();
        var editor = fixture.store.editTarget(installed.deleteTarget());
        var pending = fixture.store.saveEdit(editor, "更新图片说明", "输入绘图描述。", "");
        assertEquals(approved.id(), pending.service().id());

        String updatedCatalogue = MODELS + "-other";
        var saved = fixture.store.saveConfiguration(NAME, pending.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, updatedCatalogue, approved.modelName(), approved.costUnits(), "");
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        var after = reopened.detail(NAME);
        assertTrue(after.pending());
        assertEquals(1, after.previousVersion());
        assertEquals(saved.draftId(), after.draftId());
        assertNotEquals(pending.draftId(), after.draftId());
        assertEquals(approved.id(), after.service().id());
        assertEquals(IMAGE, after.service().endpoint());
        assertEquals(updatedCatalogue, after.service().modelsEndpoint());
        assertTrue(fixture.services.hasCredential(after.service()));
        assertTrue(fixture.services.isAuthorized(approved));
        assertTrue(fixture.services.isAuthorized(after.service()));
        assertTrue(fixture.targets.isEmpty());
        assertThrows(IllegalStateException.class, () ->
                reopened.discoverImageModels(NAME, pending.draftId(), IMAGE, updatedCatalogue, ""));

        assertEquals(List.of("image-fixture"),
                reopened.discoverImageModels(NAME, after.draftId(), IMAGE, updatedCatalogue, ""));
        assertEquals(List.of(updatedCatalogue), fixture.targets);
        assertEquals(List.of("Bearer " + KEY), fixture.authorizations);

        for (String rejected : List.of("https://other.example.test/models", MODELS + "/%66" + KEY.substring(1))) {
            assertThrows(SkillServices.Failure.class, () -> reopened.saveConfiguration(NAME, after.draftId(),
                    SkillServices.Adapter.OPENAI_IMAGES, IMAGE, rejected, approved.modelName(), approved.costUnits(), ""));
        }
        assertEquals(SkillServices.Code.CONFIRMATION_REQUIRED, assertThrows(SkillServices.Failure.class, () ->
                fixture.services.updateImageModelsEndpoint(approved, MODELS + "-stale")).code());
        assertEquals(after.draftId(), reopened.detail(NAME).draftId());
        assertEquals(updatedCatalogue, reopened.detail(NAME).service().modelsEndpoint());
        assertTrue(fixture.services.isAuthorized(approved));
        assertEquals(List.of(updatedCatalogue), fixture.targets);
    }

    @Test public void modelListAddressesCannotPersistAnEncodedFreshOrSavedKey() throws Exception {
        Fixture fixture = new Fixture();
        var draft = fixture.pending();
        String unsafe = MODELS + "/%66" + KEY.substring(1);
        assertThrows(SkillServices.Failure.class, () -> fixture.store.saveConfiguration(NAME, draft.draftId(),
                SkillServices.Adapter.OPENAI_IMAGES, IMAGE, unsafe, "", 0, KEY));
        assertNull(fixture.store.detail(NAME).service());

        var saved = fixture.store.saveConfiguration(NAME, draft.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                IMAGE, MODELS, "", 0, KEY);
        assertThrows(SkillServices.Failure.class, () -> fixture.store.saveConfiguration(NAME, saved.draftId(),
                SkillServices.Adapter.OPENAI_IMAGES, IMAGE, unsafe, "", 0, ""));
        assertEquals(MODELS, fixture.store.detail(NAME).service().modelsEndpoint());
        assertThrows(SkillServices.Failure.class, () -> fixture.store.discoverImageModels(NAME, saved.draftId(),
                IMAGE, unsafe, ""));
        assertTrue(fixture.targets.isEmpty());
    }

    private final class Fixture {
        final List<String> targets = new ArrayList<>();
        final List<String> authorizations = new ArrayList<>();
        final File directory = temp.newFolder();
        final SkillImageModelCatalog catalog = new SkillImageModelCatalog((url, method, headers, body, deadline, max, reader) -> {
            targets.add(url);
            authorizations.add(headers.get("Authorization"));
            assertEquals("GET", method);
            assertNull(body);
            byte[] json = "{\"data\":[{\"id\":\"image-fixture\"}]}".getBytes(StandardCharsets.UTF_8);
            return reader.read(200, ignored -> null, new ByteArrayInputStream(json));
        });
        final SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, type) -> {
                    throw new AssertionError("Model discovery must not generate an image");
                }, catalog);
        final SkillStore store = new SkillStore(directory, services);

        Fixture() throws Exception {}

        SkillStore.Pending pending() throws Exception {
            String draft = SkillStore.mockDraft("创建名为「" + NAME + "」的生图 Skill");
            return store.saveGenerated(draft, null).pending();
        }
    }
}
