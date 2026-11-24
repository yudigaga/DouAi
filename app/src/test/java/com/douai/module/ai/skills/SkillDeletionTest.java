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
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class SkillDeletionTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();

    @Test public void removesCurrentConfigurationAndSameNameDraftButKeepsOtherSkillsAfterReopen() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("保留");
        Set<Path> other = revisions(fixture.directory);
        fixture.install("整理");
        fixture.install("整理");
        fixture.store.saveAuthoredDraft(draft("整理", "summary", "text"));
        var target = fixture.store.detail("整理").deleteTarget();
        assertEquals(1, target.versionCount());
        assertTrue(target.hasDraft());

        fixture.store.delete(target);

        SkillStore reopened = fixture.reopen();
        assertEquals(other, revisions(fixture.directory));
        assertEquals("保留", reopened.list().get(0).name());
        assertEquals(1, reopened.list().size());
        assertTrue(reopened.pending().isEmpty());
        assertThrows(IllegalStateException.class, () -> reopened.detail("整理"));
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertTrue(AiSkills.commands(entry, "/整", reopened).isEmpty());
            assertNull(AiSkills.match(entry, "/整理 待处理", reopened));
        }
    }

    @Test public void staleConfirmationCannotDeleteModifiedOrRecreatedEntries() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("整理");
        var stale = fixture.store.detail("整理").deleteTarget();
        fixture.store.setSkillEnabled("整理", false);
        assertThrows(IllegalStateException.class, () -> fixture.store.delete(stale));
        fixture.store.setSkillEnabled("整理", true);
        fixture.store.delete(fixture.store.detail("整理").deleteTarget());
        fixture.install("整理");

        assertThrows(IllegalStateException.class, () -> fixture.store.delete(stale));
        assertEquals(1, fixture.store.list().get(0).version());
        assertThrows(IllegalArgumentException.class,
                () -> fixture.store.delete(new SkillStore.DeleteTarget("摘要", 1, false, "not-custom")));
    }

    @Test public void pureDraftDeletionInvalidatesAnAsynchronousReconnectAfterIdenticalRecreation() throws Exception {
        Fixture fixture = new Fixture();
        var proposal = draft("绘图", "service", "image");
        var first = fixture.store.saveAuthoredDraft(proposal).pending();
        var target = fixture.store.detail("绘图").deleteTarget();
        assertEquals(0, target.versionCount());
        assertTrue(target.hasDraft());
        fixture.store.delete(target);
        assertTrue(fixture.reopen().pending().isEmpty());

        var second = fixture.store.saveAuthoredDraft(proposal).pending();
        assertNotEquals(first.draftId(), second.draftId());
        assertThrows(IllegalStateException.class,
                () -> fixture.store.connectPending(proposal.definition(), null, first.draftId()));
        assertEquals(second.draftId(), fixture.store.pending().get(0).draftId());
    }

    @Test public void queuedInvocationsCannotExecuteTheRecreatedVersionOneInEitherEntry() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("整理");
        var oldWork = AiSkills.match(AiSkills.Entry.WORK, "/整理 内容", fixture.store);
        var oldContact = AiSkills.match(AiSkills.Entry.CONTACT, "/整理 内容", fixture.store);
        fixture.store.delete(fixture.store.detail("整理").deleteTarget());
        fixture.install("整理");

        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(oldWork, fixture.reopen()));
        assertThrows(IllegalStateException.class, () -> AiSkills.executeResult(oldContact, fixture.reopen()));
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var current = AiSkills.match(entry, "/整理 内容", fixture.reopen());
            assertEquals(1, current.version());
            assertEquals(new SkillResult.Text("内容"), AiSkills.executeResult(current, fixture.reopen()));
        }
    }

    @Test public void deletingOneSkillDoesNotRevokeItsLocalServiceOrEraseTheCredential() throws Exception {
        Fixture fixture = new Fixture();
        fixture.store.saveAuthoredDraft(draft("绘图", "service", "image"));
        var pending = fixture.store.detail("绘图");
        var filled = fixture.store.saveConfiguration("绘图", pending.draftId(), SkillServices.Adapter.OPENAI_IMAGES,
                "https://images.example.test/v1/images/generations", "fixture", 0, "fixture-deletion-key-1234");
        var installed = fixture.store.authorizeConfiguration("绘图", filled.draftId(), filled.service());
        fixture.store.delete(installed.deleteTarget());

        var preserved = fixture.services.scoped(filled.service().id(), "绘图");
        assertNotNull(preserved);
        assertTrue(fixture.services.hasCredential(preserved));
        assertTrue(fixture.services.isAuthorized(preserved));
        assertTrue(fixture.reopen().list().isEmpty());
    }

    @Test public void listenersObserveCommittedStateAcrossSameDirectoryInstancesAndUnsubscribe() throws Exception {
        Fixture fixture = new Fixture();
        Fixture elsewhere = new Fixture();
        fixture.install("整理");
        int[] observed = {-1};
        AtomicInteger unrelated = new AtomicInteger();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try (AutoCloseable ignored = elsewhere.store.observeDeletions(unrelated::incrementAndGet)) {
            try (AutoCloseable subscription = fixture.reopen().observeDeletions(() -> {
                try {
                    observed[0] = reader.submit(() -> fixture.reopen().list().size()).get(2, TimeUnit.SECONDS);
                } catch (Exception unavailable) {
                    observed[0] = -2;
                }
            })) {
                fixture.store.delete(fixture.store.detail("整理").deleteTarget());
            }
            assertEquals(0, observed[0]);
            assertEquals(0, unrelated.get());
            fixture.install("整理");
            fixture.install("另外");
            fixture.store.delete(fixture.store.detail("整理").deleteTarget());
            assertEquals(0, observed[0]);
        } finally {
            reader.shutdownNow();
        }
    }

    @Test public void interruptedDirectoryCleanupPreservesUnrelatedFilesAndRetriesSafelyAfterReopen() throws Exception {
        Fixture fixture = new Fixture();
        fixture.install("整理");
        Path slot = revisions(fixture.directory).iterator().next();
        Path unknown = Files.writeString(slot.resolve("keep.txt"), "not a Skill document");
        fixture.store.delete(fixture.store.detail("整理").deleteTarget());

        assertTrue(Files.exists(unknown));
        assertFalse(Files.exists(slot.resolve("SKILL.md")));
        assertFalse(Files.exists(slot.resolve("execution.json")));
        assertTrue(fixture.reopen().list().isEmpty());
        Files.delete(unknown);
        assertTrue(fixture.reopen().list().isEmpty());
        assertFalse(Files.exists(slot));
    }

    private static SkillStore.DraftPreview draft(String name, String kind, String output) throws Exception {
        JSONObject execution = new JSONObject().put("kind", kind).put("outputType", output).put("maxInput", 100);
        JSONObject trial = new JSONObject().put("input", "种子文本").put("expectedType", output);
        return SkillStore.validateAuthoredDraft(new JSONObject().put("schemaVersion", 3).put("name", name)
                .put("description", "处理文本").put("documentation", "请提供文字。")
                .put("execution", execution).put("trial", trial).toString());
    }

    private static Set<Path> revisions(File directory) throws Exception {
        try (var files = Files.list(directory.toPath().resolve("versions"))) {
            return files.collect(Collectors.toSet());
        }
    }

    private final class Fixture {
        final File directory = temp.newFolder();
        final SkillServices services = new SkillServices(temp.newFolder(),
                new AiCredentialVault(() -> new SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, type) -> {
                    throw new AssertionError("Skill deletion tests must stay offline");
                });
        final SkillStore store = reopen();

        Fixture() throws Exception {}

        SkillStore reopen() { return new SkillStore(directory, services); }

        void install(String name) throws Exception {
            String definition = draft(name, "summary", "text").definition();
            store.installGenerated(definition, store.previewGenerated(definition));
        }
    }
}
