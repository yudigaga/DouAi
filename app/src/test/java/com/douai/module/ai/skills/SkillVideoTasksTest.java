package com.douai.module.ai.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

public class SkillVideoTasksTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    private static final SkillVideoTasks.Scope WORK =
            new SkillVideoTasks.Scope(AiSkills.Entry.WORK, "a".repeat(64));
    private static final SkillVideoTasks.Scope CONTACT =
            new SkillVideoTasks.Scope(AiSkills.Entry.CONTACT, "a".repeat(64));
    private static final byte[] FIXTURE = {0, 0, 0, 12, 'f', 't', 'y', 'p', 'm', 'p', '4', '2'};
    private final AtomicLong clock = new AtomicLong(1_000L);

    public static SkillVideoTasks chatFixture(File directory, LongSupplier clock) {
        return new SkillVideoTasks(directory, clock, new SkillVideoTasks.Timing(650, 2_600, 30_000),
                () -> new ByteArrayInputStream(FIXTURE));
    }

    @Test public void reopenRecoversSameOperationWithoutPersistingArgumentsOrCreatingAnother() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks first = tasks(files, 3_000L);
        UUID requestId = UUID.randomUUID();
        long generation = first.generation(WORK);
        SkillVideoTasks.Task started = first.submit(WORK, generation, requestId, "/模拟视频 api-key=secret");
        assertEquals(SkillVideoTasks.State.QUEUED, started.state());
        assertEquals("/模拟视频", started.prompt());
        assertNull(first.playableFile(WORK, requestId));

        clock.set(2_100L);
        SkillVideoTasks reopened = tasks(files, 3_000L);
        SkillVideoTasks.Task running = reopened.refresh(WORK, requestId);
        assertEquals(SkillVideoTasks.State.RUNNING, running.state());
        assertEquals(started.operationId(), running.operationId());
        assertEquals(running, reopened.submit(WORK, generation, requestId, "/模拟视频 another"));
        assertEquals(List.of(running), reopened.list(WORK));
        assertFalse(new String(Files.readAllBytes(files.toPath().resolve("work-" + "a".repeat(64) + ".bin")), StandardCharsets.UTF_8)
                .contains("secret"));

        clock.set(20_000L);
        SkillVideoTasks later = tasks(files, 3_000L);
        assertEquals(SkillVideoTasks.State.COMPLETED, later.refresh(WORK, requestId).state());
        assertEquals(started.operationId(), later.refresh(WORK, requestId).operationId());
        assertEquals(FIXTURE.length, later.playableFile(WORK, requestId).length());
    }

    @Test public void wrongConversationCannotSeeCancelPlayOrDeduplicateAnotherConversation() throws Exception {
        SkillVideoTasks tasks = tasks(temp.newFolder(), 3_000L);
        UUID id = UUID.randomUUID();
        SkillVideoTasks.Task work = tasks.submit(WORK, tasks.generation(WORK), id, "/模拟视频 蓝色");
        SkillVideoTasks.Scope otherProfile = new SkillVideoTasks.Scope(AiSkills.Entry.WORK, "b".repeat(64));
        assertEquals(List.of(), tasks.list(CONTACT));
        assertNull(tasks.refresh(otherProfile, id));
        assertNull(tasks.cancel(CONTACT, id));
        assertNull(tasks.playableFile(otherProfile, id));
        SkillVideoTasks.Task contact = tasks.submit(CONTACT, tasks.generation(CONTACT), id, "/模拟视频 灰色");
        assertNotEquals(work.operationId(), contact.operationId());
    }

    @Test public void cancellationAndTimeoutRemainTerminalAfterReopeningOrDuplicateSubmit() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks first = tasks(files, 30_000L);
        UUID cancelledId = UUID.randomUUID();
        UUID timeoutId = UUID.randomUUID();
        long generation = first.generation(WORK);
        first.submit(WORK, generation, cancelledId, "/模拟视频 一");
        first.submit(WORK, generation, timeoutId, "/模拟视频 二");
        assertEquals(SkillVideoTasks.State.CANCELLED, first.cancel(WORK, cancelledId).state());

        clock.set(40_000L);
        SkillVideoTasks reopened = tasks(files, 3_000L);
        assertEquals(SkillVideoTasks.State.CANCELLED, reopened.refresh(WORK, cancelledId).state());
        SkillVideoTasks.Task timeout = reopened.refresh(WORK, timeoutId);
        assertEquals(SkillVideoTasks.State.TIMED_OUT, timeout.state());
        assertEquals(timeout, reopened.cancel(WORK, timeoutId));
        assertEquals(timeout, reopened.submit(WORK, generation, timeoutId, "/模拟视频 三"));
        assertNull(reopened.playableFile(WORK, timeoutId));
    }

    @Test public void persistedClearFenceRejectsQueuedOldTurnAndOnlyClearsItsScope() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks first = tasks(files, 3_000L);
        UUID requestId = UUID.randomUUID();
        long generation = first.generation(WORK);
        first.submit(WORK, generation, requestId, "/模拟视频 一");
        SkillVideoTasks.Task contact = first.submit(CONTACT, first.generation(CONTACT), requestId, "/模拟视频 二");
        first.clear(WORK);

        SkillVideoTasks reopened = tasks(files, 3_000L);
        assertEquals(List.of(), reopened.list(WORK));
        assertNotEquals(generation, reopened.generation(WORK));
        assertThrows(IOException.class, () -> reopened.submit(WORK, generation, requestId, "/模拟视频 三"));
        assertEquals(contact, reopened.refresh(CONTACT, requestId));
    }

    @Test public void corruptStorageDoesNotSilentlyLoseDedupeAndRequiresExplicitClear() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks tasks = tasks(files, 3_000L);
        UUID requestId = UUID.randomUUID();
        long generation = tasks.generation(WORK);
        tasks.submit(WORK, generation, requestId, "/模拟视频 一");
        Files.write(files.toPath().resolve("work-" + "a".repeat(64) + ".bin"), new byte[] {1, 2, 3});
        assertThrows(IOException.class, () -> tasks.list(WORK));
        assertThrows(IOException.class, () -> tasks.submit(WORK, generation, requestId, "/模拟视频 一"));
        tasks.clear(WORK);
        assertEquals(List.of(), tasks.list(WORK));
        assertThrows(IOException.class, () -> tasks.submit(WORK, generation, requestId, "/模拟视频 一"));
    }

    @Test public void unavailableMediaFailsOnceAndDuplicateDoesNotReplaceTerminalOperation() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks first = new SkillVideoTasks(files, clock::get,
                new SkillVideoTasks.Timing(1_000L, 3_000L, 10_000L),
                () -> new ByteArrayInputStream(new byte[] {1, 2, 3}));
        UUID id = UUID.randomUUID();
        long generation = first.generation(WORK);
        SkillVideoTasks.Task submitted = first.submit(WORK, generation, id, "/模拟视频 一");
        clock.set(5_000L);
        SkillVideoTasks.Task failed = first.refresh(WORK, id);
        assertEquals(SkillVideoTasks.State.FAILED, failed.state());
        assertEquals(submitted.operationId(), failed.operationId());

        SkillVideoTasks reopened = tasks(files, 3_000L);
        assertEquals(failed, reopened.submit(WORK, generation, id, "/模拟视频 一"));
        assertNull(reopened.playableFile(WORK, id));
    }

    @Test public void linkingPreservesTaskAndSurvivesReopeningUntilItsScopeIsCleared() throws Exception {
        File files = temp.newFolder();
        SkillVideoTasks first = tasks(files, 3_000L);
        UUID id = UUID.randomUUID();
        SkillVideoTasks.Task submitted = first.submit(WORK, first.generation(WORK), id, "/模拟视频 一");
        assertFalse(submitted.linked());
        first.markLinked(CONTACT, id);
        assertFalse(first.refresh(WORK, id).linked());
        first.markLinked(WORK, id);
        first.markLinked(WORK, id);

        SkillVideoTasks reopened = tasks(files, 3_000L);
        SkillVideoTasks.Task linked = reopened.refresh(WORK, id);
        assertEquals(submitted.operationId(), linked.operationId());
        assertEquals(submitted.state(), linked.state());
        assertTrue(linked.linked());
        reopened.clear(WORK);
        first.markLinked(WORK, id);
        assertEquals(List.of(), reopened.list(WORK));
    }

    private SkillVideoTasks tasks(File directory, long completionMillis) {
        return new SkillVideoTasks(directory, clock::get,
                new SkillVideoTasks.Timing(1_000L, completionMillis, 10_000L),
                () -> new ByteArrayInputStream(FIXTURE));
    }
}
