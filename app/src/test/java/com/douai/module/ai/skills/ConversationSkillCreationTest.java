package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class ConversationSkillCreationTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void explicitCreationSurvivesReopenWithExactMissingFieldsAndNoExecutableCommand() throws Exception {
        Fixture fixture = new Fixture();
        assertEquals("海报", saveImage(fixture, "海报").pending().name());

        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        SkillStore.Detail detail = reopened.detail("海报");
        assertTrue(detail.pending());
        assertEquals(SkillResult.Type.IMAGE, detail.outputType());
        assertEquals(SkillStore.FieldState.MISSING, field(detail, "endpoint").state());
        assertEquals(SkillStore.FieldState.MISSING, field(detail, "model").state());
        assertEquals(SkillStore.FieldState.MISSING, field(detail, "credential").state());
        assertEquals(SkillStore.FieldState.NEEDS_AUTHORIZATION, field(detail, "authorization").state());
        assertFalse(detail.canAuthorize());
        assertTrue(AiSkills.commands(AiSkills.Entry.WORK, "/海", reopened).isEmpty());
        assertEquals(0, fixture.transport.get());
    }

    @Test public void authoredTextSkillUsesThePinnedInstructionAndOnlyTheSuppliedExecutor() throws Exception {
        Fixture fixture = new Fixture();
        String instruction = "将输入改写成三条项目周报要点。";
        var authored = SkillStore.validateAuthoredDraft(definition("周报整理", "text_model", "text", instruction));
        assertEquals(instruction, authored.instruction());
        assertEquals("周报整理", fixture.store.saveAuthoredDraft(authored).installed().name());
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertFalse(reopened.detail("周报整理").pending());
        AtomicInteger calls = new AtomicInteger();
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var call = AiSkills.match(entry, "/周报整理 原始任务和进度", reopened);
            assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(call, reopened));
            assertEquals(new SkillResult.Text("改写后的周报"), AiSkills.executeResult(call, reopened, (rule, input) -> {
                assertEquals(instruction, rule);
                assertEquals("原始任务和进度", input);
                calls.incrementAndGet();
                return "改写后的周报";
            }));
        }
        var overlong = AiSkills.match(AiSkills.Entry.WORK, "/周报整理 " + "甲".repeat(2001), reopened);
        assertThrows(IllegalArgumentException.class,
                () -> AiSkills.executeResult(overlong, reopened, (rule, input) -> {
                    throw new AssertionError("Too-long input must never reach the model");
                }));
        var invocation = AiSkills.match(AiSkills.Entry.WORK, "/周报整理 原始任务和进度", reopened);
        IllegalArgumentException unsafe = assertThrows(IllegalArgumentException.class, () ->
                AiSkills.executeResult(invocation, reopened, (rule, input) -> "Bearer fixture-pasted-result-1234"));
        assertFalse(unsafe.getMessage().contains("fixture-pasted-result-1234"));
        assertEquals(2, calls.get());
        assertEquals(0, fixture.transport.get());
    }

    @Test public void onlyTheSelectedSkillGetsItsConfigurationAndBothEntriesRenderAnImageAfterApproval() throws Exception {
        Fixture fixture = new Fixture();
        saveImage(fixture, "生图");
        saveImage(fixture, "别的");
        SkillStore.Detail draft = fixture.store.detail("生图");
        var mock = fixture.store.saveConfiguration(draft.name(), draft.draftId(),
                SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        assertTrue(mock.pending());
        assertTrue(mock.canAuthorize());
        assertEquals(1, mock.fields().size());
        var enabled = fixture.store.authorizeConfiguration(mock.name(), mock.draftId(), mock.service());
        assertFalse(enabled.pending());
        assertTrue(fixture.store.detail("别的").pending());
        assertFalse(fixture.services.isAuthorized(fixture.services.find(SkillServices.MOCK_IMAGE)));

        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var invocation = AiSkills.match(entry, "/生图 橘猫", fixture.store);
            assertTrue(AiSkills.executeResult(invocation, fixture.store) instanceof SkillResult.Image);
        }
        assertEquals(0, fixture.transport.get());
    }

    @Test public void partialFieldsPersistCredentialsStayOutsideSkillFilesAndApprovalIsSeparate() throws Exception {
        Fixture fixture = new Fixture();
        saveImage(fixture, "生图");
        var draft = fixture.store.detail("生图");
        var partial = fixture.store.saveConfiguration(draft.name(), draft.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://images.example.test/v1/images/generations", "image-fixture", 1, "");
        assertEquals(SkillStore.FieldState.FILLED, field(partial, "endpoint").state());
        assertEquals(SkillStore.FieldState.FILLED, field(partial, "model").state());
        assertEquals(SkillStore.FieldState.MISSING, field(partial, "credential").state());
        assertFalse(partial.canAuthorize());

        String token = "fixture-local-secret-1234";
        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        assertEquals(partial.draftId(), reopened.detail("生图").draftId());
        var filled = reopened.saveConfiguration(partial.name(), partial.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                partial.service().endpoint(), partial.service().modelName(), partial.service().costUnits(), token);
        assertTrue(filled.canAuthorize());
        assertTrue(filled.pending());
        assertEquals(SkillStore.FieldState.FILLED, field(filled, "credential").state());
        assertFalse(filled.toString().contains(token));
        assertFalse(reopened.generationPrompt("生图").contains(token));
        assertFalse(reopened.generationPrompt("生图").contains("images.example.test"));

        var installed = reopened.authorizeConfiguration(filled.name(), filled.draftId(), filled.service());
        assertFalse(installed.pending());
        try (Stream<java.nio.file.Path> files = Files.walk(fixture.directory.toPath())) {
            for (var file : files.filter(Files::isRegularFile).toList()) {
                assertFalse(Files.readString(file).contains(token));
            }
        }
        assertEquals(0, fixture.transport.get());
    }

    @Test public void reusesAuthorizedServiceButSameNameStillRequiresExplicitReplacement() throws Exception {
        Fixture fixture = new Fixture();
        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        assertNotNull(saveImage(fixture, "生图").installed());
        var original = AiSkills.match(AiSkills.Entry.WORK, "/生图 橘猫", fixture.store);
        assertNotNull(saveImage(fixture, "生图").pending());
        var draft = fixture.store.detail("生图");
        assertTrue(draft.canReplace());
        assertEquals(1, fixture.store.list().get(0).version());
        assertTrue(AiSkills.executeResult(original, fixture.store) instanceof SkillResult.Image);
        fixture.store.activateReplacement(draft);
        assertEquals(2, fixture.store.list().get(0).version());
        assertEquals(1, fixture.store.detail("生图").deleteTarget().versionCount());
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(original, fixture.store));
        var replacement = AiSkills.match(AiSkills.Entry.WORK, "/生图 橘猫", fixture.store);
        assertTrue(AiSkills.executeResult(replacement, fixture.store) instanceof SkillResult.Image);
        assertEquals(0, fixture.transport.get());
    }

    @Test public void unrecognizedOperationAndChangedPreviewDoNotWriteAnInstalledOrPendingSkill() throws Exception {
        Fixture fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () ->
                SkillStore.validateAuthoredDraft(definition("剪辑", "shell", "text", null)));
        var valid = SkillStore.validateAuthoredDraft(definition("整理", "text_model", "text", "整理文本"));
        assertThrows(IllegalArgumentException.class, () -> fixture.store.saveAuthoredDraft(
                new SkillStore.DraftPreview("其他", valid.description(), valid.outputType(), valid.instruction(), valid.definition())));
        assertTrue(fixture.store.list().isEmpty());
        assertTrue(fixture.store.pending().isEmpty());
    }

    @Test public void textModelReplacementKeepsItsNewRulePendingUntilAccepted() throws Exception {
        Fixture fixture = new Fixture();
        var first = SkillStore.validateAuthoredDraft(definition("改写", "text_model", "text", "用简洁的中文改写"));
        var second = SkillStore.validateAuthoredDraft(definition("改写", "text_model", "text", "用清晰的英文改写"));
        assertNotNull(fixture.store.saveAuthoredDraft(first).installed());
        var original = AiSkills.match(AiSkills.Entry.WORK, "/改写 测试", fixture.store);
        assertNotNull(fixture.store.saveAuthoredDraft(second).pending());
        var pending = fixture.store.detail("改写");
        assertTrue(pending.canReplace());
        assertEquals(first.instruction(), executeInstruction(fixture.store, "改写"));
        fixture.store.activateReplacement(pending);
        assertEquals(second.instruction(), executeInstruction(fixture.store, "改写"));
        assertEquals(1, fixture.store.detail("改写").deleteTarget().versionCount());
        assertThrows(IllegalStateException.class,
                () -> AiSkills.executeResult(original, fixture.store, (instruction, ignored) -> instruction));
    }

    @Test public void staleAuthorizationCannotApproveAnEditedDraft() throws Exception {
        Fixture fixture = new Fixture();
        saveImage(fixture, "生图");
        var draft = fixture.store.detail("生图");
        var first = fixture.store.saveConfiguration("生图", draft.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        var second = fixture.store.saveConfiguration("生图", first.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        assertNotEquals(first.draftId(), second.draftId());
        assertThrows(IllegalStateException.class,
                () -> fixture.store.authorizeConfiguration("生图", first.draftId(), first.service()));
        assertTrue(fixture.store.detail("生图").pending());
        assertFalse(fixture.services.isAuthorized(second.service()));
    }

    @Test public void replacingPrivateConfigurationKeepsTheCurrentServiceUsableUntilActivation() throws Exception {
        Fixture fixture = new Fixture();
        saveImage(fixture, "生图");
        var first = fixture.store.detail("生图");
        var mock = fixture.store.saveConfiguration("生图", first.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        fixture.store.authorizeConfiguration("生图", mock.draftId(), mock.service());
        var original = AiSkills.match(AiSkills.Entry.CONTACT, "/生图 橘猫", fixture.store);
        saveImage(fixture, "生图");
        var next = fixture.store.detail("生图");
        assertEquals(mock.service(), next.service());
        var configured = fixture.store.saveConfiguration("生图", next.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://image.example.test/generate", "fixture", 1, "fixture-new-key-1234");
        assertNotEquals(mock.service().id(), configured.service().id());
        assertTrue(fixture.services.hasCredential(configured.service()));
        assertFalse(fixture.services.isAuthorized(configured.service()));
        var reviewed = fixture.store.authorizeConfiguration("生图", configured.draftId(), configured.service());
        assertTrue(reviewed.canReplace());
        assertEquals(mock.service(), reviewed.currentService());
        assertTrue(fixture.services.isAuthorized(mock.service()));
        assertTrue(AiSkills.executeResult(original, fixture.store) instanceof SkillResult.Image);
        fixture.store.activateReplacement(reviewed);

        SkillStore reopened = new SkillStore(fixture.directory, fixture.services);
        SkillStore.Detail current = reopened.detail("生图");
        assertEquals(configured.service(), current.currentService());
        assertEquals(1, current.deleteTarget().versionCount());
        assertFalse(current.pending());
        assertTrue(fixture.services.hasCredential(current.currentService()));
        assertTrue(fixture.services.isAuthorized(current.currentService()));
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(original, reopened));
        assertEquals(0, fixture.transport.get());
    }

    private static SkillStore.Field field(SkillStore.Detail detail, String id) {
        return detail.fields().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
    }

    private static String executeInstruction(SkillStore store, String name) throws Exception {
        var invocation = AiSkills.match(AiSkills.Entry.WORK, "/" + name + " 测试", store);
        return ((SkillResult.Text) AiSkills.executeResult(invocation, store, (instruction, ignored) -> instruction)).text();
    }

    private static SkillStore.SaveResult saveImage(Fixture fixture, String name) throws Exception {
        return fixture.store.saveAuthoredDraft(SkillStore.validateAuthoredDraft(definition(name, "service", "image", null)));
    }

    private static String definition(String name, String kind, String outputType, String instruction) throws Exception {
        JSONObject execution = new JSONObject().put("kind", kind).put("outputType", outputType)
                .put("maxInput", "text_model".equals(kind) ? 2000 : 4000);
        if (instruction != null) execution.put("instruction", instruction);
        return new JSONObject().put("schemaVersion", 3).put("name", name).put("description", "处理用户输入")
                .put("documentation", "输入任务文本。").put("execution", execution)
                .put("trial", new JSONObject().put("input", "试跑文本").put("expectedType", outputType)).toString();
    }

    private final class Fixture {
        final File directory = temp.newFolder();
        final AtomicInteger transport = new AtomicInteger();
        final SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, type) -> {
                    transport.incrementAndGet();
                    throw new AssertionError("Generation, setup and trials must stay local");
                });
        final SkillStore store = new SkillStore(directory, services);
        Fixture() throws Exception {}
    }
}
