package com.douai.module.ai.skills;

import com.douai.module.ai.AiCredentialVault;

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
        String prompt = "帮我做一个名为「海报」的生图 Skill";
        assertTrue(SkillStore.isExplicitCreation(prompt));
        assertEquals("海报", fixture.store.createFromConversation(prompt).pending().name());

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

    @Test public void ordinaryQuestionsQuotationsAndInstructionsFromExternalTextDoNotRequestCreation() throws Exception {
        for (String prompt : new String[]{
                "如何创建一个生图 Skill？", "解释怎么创建生图 Skill",
                "我提到了创建一个生图 Skill", "「帮我做一个生图 Skill」",
                "分析这段文字：帮我做一个生图 Skill", "帮我做一个生图 Skill 的使用说明",
                "请转述\n帮我做一个生图 Skill", "/创建 生图 Skill"}) {
            assertFalse(prompt, SkillStore.isExplicitCreation(prompt));
        }
        Fixture fixture = new Fixture();
        assertEquals("文本摘要", fixture.store.createFromConversation("创建一个文本摘要技能").installed().name());
        var call = AiSkills.match(AiSkills.Entry.CONTACT, "/文本摘要 要点。结论。附录。", fixture.store);
        assertEquals(new SkillResult.Text("要点。结论。"), AiSkills.executeResult(call, fixture.store));
        assertFalse(fixture.store.detail("文本摘要").pending());
    }

    @Test public void onlyTheSelectedSkillGetsItsConfigurationAndBothEntriesRenderAnImageAfterApproval() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.createFromConversation("帮我做一个生图 Skill");
        fixture.store.createFromConversation("创建一个名为「别的」的生图 Skill");
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
        fixture.store.createFromConversation("做一个生图 Skill");
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

    @Test public void reusesAuthorizedServiceButSameNameStillRequiresReplacementAndCanRollback() throws Exception {
        Fixture fixture = new Fixture();
        var mock = fixture.services.find(SkillServices.MOCK_IMAGE);
        fixture.services.authorize(mock, 0);
        assertNotNull(fixture.store.createFromConversation("创建一个生图技能").installed());
        assertNotNull(fixture.store.createFromConversation("创建一个生图技能").pending());
        var draft = fixture.store.detail("生图");
        assertTrue(draft.canReplace());
        assertEquals(1, fixture.store.list().get(0).version());
        fixture.store.activateReplacement(draft.name(), draft.draftId(), 1);
        assertEquals(2, fixture.store.list().get(0).version());
        assertEquals(1, fixture.store.rollback("生图").version());
        assertEquals(0, fixture.transport.get());
    }

    @Test public void unsupportedDescriptionRemainsHonestAndPending() throws Exception {
        Fixture fixture = new Fixture();
        var pending = fixture.store.createFromConversation("帮我做一个视频剪辑 Skill").pending();
        assertNotNull(pending);
        assertEquals(SkillStore.FieldState.MISSING, field(fixture.store.detail(pending.name()), "operation").state());
        assertTrue(fixture.store.list().isEmpty());
    }

    @Test public void staleAuthorizationCannotApproveAnEditedDraft() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.createFromConversation("创建一个生图技能");
        var draft = fixture.store.detail("生图");
        var first = fixture.store.saveConfiguration("生图", draft.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        var second = fixture.store.saveConfiguration("生图", first.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        assertNotEquals(first.draftId(), second.draftId());
        assertThrows(IllegalStateException.class,
                () -> fixture.store.authorizeConfiguration("生图", first.draftId(), first.service()));
        assertTrue(fixture.store.detail("生图").pending());
        assertFalse(fixture.services.isAuthorized(second.service()));
    }

    @Test public void replacingPrivateConfigurationKeepsThePreviousVersionUsableAfterRollback() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.createFromConversation("创建一个生图技能");
        var first = fixture.store.detail("生图");
        var mock = fixture.store.saveConfiguration("生图", first.draftId(), SkillServices.Adapter.MOCK_IMAGE, "", "", 0, "");
        fixture.store.authorizeConfiguration("生图", mock.draftId(), mock.service());
        fixture.store.createFromConversation("创建一个生图技能");
        var next = fixture.store.detail("生图");
        assertEquals(mock.service(), next.service());
        var configured = fixture.store.saveConfiguration("生图", next.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://image.example.test/generate", "fixture", 1, "fixture-new-key-1234");
        assertNotEquals(mock.service().id(), configured.service().id());
        var reviewed = fixture.store.authorizeConfiguration("生图", configured.draftId(), configured.service());
        assertTrue(reviewed.canReplace());
        fixture.store.activateReplacement("生图", reviewed.draftId(), 1);
        fixture.store.rollback("生图");
        assertEquals(mock.service(), fixture.store.detail("生图").service());
        var invocation = AiSkills.match(AiSkills.Entry.CONTACT, "/生图 橘猫", fixture.store);
        assertTrue(AiSkills.executeResult(invocation, fixture.store) instanceof SkillResult.Image);
        assertEquals(0, fixture.transport.get());
    }

    private static SkillStore.Field field(SkillStore.Detail detail, String id) {
        return detail.fields().stream().filter(f -> f.id().equals(id)).findFirst().orElseThrow();
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
