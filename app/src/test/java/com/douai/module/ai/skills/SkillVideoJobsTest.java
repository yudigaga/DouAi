package com.douai.module.ai.skills;

import android.content.Context;

import com.douai.module.ai.AiCredentialVault;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class SkillVideoJobsTest {
    private static final String ENDPOINT = "https://video.example.com:9443/v1/submit";
    private static final String KEY = "synthetic-fixture-not-a-real-credential";
    private static final byte[] POST = bytes("{\"prompt\":\"synthetic-private-user-input\"}");
    private static final SkillVideoJobs.Scope WORK = new SkillVideoJobs.Scope(AiSkills.Entry.WORK, "a".repeat(64));
    private static final SkillVideoJobs.Scope CONTACT = new SkillVideoJobs.Scope(AiSkills.Entry.CONTACT, "a".repeat(64));
    @Rule public TemporaryFolder temporary = new TemporaryFolder();
    private final AtomicLong clock = new AtomicLong(1_000);

    public record ChatVideoContract(String submitEndpoint, String expectedPayload, String submitReply,
                                    String statusEndpoint, String statusReply, String downloadEndpoint) { }

    public static SkillVideoJobs chatFixture(Context context, ChatVideoContract contract, AtomicInteger submits,
                                           AtomicInteger statuses, AtomicInteger downloads) {
        return new SkillVideoJobs(new File(context.getFilesDir(), SkillVideoJobs.JOURNAL_DIRECTORY), System::currentTimeMillis,
                (method, media, endpoint, bearer, body, deadline, admission) -> {
                    admission.check();
                    assertEquals("", bearer);
                    if (method == SkillHttpsTransport.Method.POST) {
                        assertFalse(media);
                        assertEquals(contract.submitEndpoint(), endpoint);
                        assertEquals(contract.expectedPayload(), new String(body, StandardCharsets.UTF_8));
                        assertEquals(1, submits.incrementAndGet());
                        if (contract.submitReply() == null) {
                            throw new SkillHttpsTransport.Failure(SkillHttpsTransport.Kind.DEADLINE, true);
                        }
                        return json(202, contract.submitReply());
                    }
                    assertEquals(SkillHttpsTransport.Method.GET, method);
                    assertNull(body);
                    if (media) {
                        assertEquals(contract.downloadEndpoint(), endpoint);
                        assertEquals(1, downloads.incrementAndGet());
                        return new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
                    }
                    assertEquals(contract.statusEndpoint(), endpoint);
                    assertEquals(1, statuses.incrementAndGet());
                    return json(200, contract.statusReply());
                });
    }

    public static SkillVideoJobs chatFixture(Context context, AtomicInteger submits) {
        return new SkillVideoJobs(new File(context.getFilesDir(), SkillVideoJobs.JOURNAL_DIRECTORY), System::currentTimeMillis,
                (method, media, uri, bearer, body, deadline, admission) -> {
                    admission.check();
                    assertEquals("", bearer);
                    if (method == SkillHttpsTransport.Method.POST) {
                        assertFalse(media);
                        assertEquals("https://video-api.example.com/v1/jobs", uri);
                        var mapped = SkillAuthoring.readObject(new String(body, StandardCharsets.UTF_8), 500);
                        assertEquals("海上日出", mapped.optString("prompt"));
                        assertEquals(4, mapped.optInt("seconds"));
                        submits.incrementAndGet();
                        return json(202, "{\"job_id\":\"job_fixture\"}");
                    }
                    assertEquals(SkillHttpsTransport.Method.GET, method);
                    assertEquals("https://video-api.example.com/v1/jobs/job_fixture", uri);
                    return json(200, "{\"id\":\"job_fixture\",\"status\":\"working\"}");
                });
    }

    @Test public void persistsIntentBeforeUncertainSubmitAndNeverResubmitsTheSameLocalId() throws Exception {
        File directory = temporary.newFolder();
        List<SkillHttpsTransport.Method> sent = new ArrayList<>();
        UUID local = UUID.randomUUID();
        SkillVideoJobs jobs = new SkillVideoJobs(directory, clock::get, (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            sent.add(method);
            assertEquals(ENDPOINT, url);
            assertEquals(KEY, bearer);
            assertArrayEquals(POST, body);
            assertEquals(SkillVideoJobs.State.SUBMIT_UNKNOWN,
                    new SkillVideoJobs(directory, clock::get, null).list(WORK).get(0).state());
            throw new SkillHttpsTransport.Failure(SkillHttpsTransport.Kind.DEADLINE, true);
        });
        var access = access(binding(), () -> {});
        var unknown = jobs.submit(WORK, jobs.generation(WORK), local, access, POST, Long.MAX_VALUE);
        assertEquals(SkillVideoJobs.State.SUBMIT_UNKNOWN, unknown.state());
        var reopened = new SkillVideoJobs(directory, clock::get, null);
        assertEquals(unknown, reopened.submit(WORK, reopened.generation(WORK), local, access, POST, Long.MAX_VALUE));
        assertEquals(unknown, reopened.refresh(WORK, local, access, Long.MAX_VALUE));
        assertEquals(List.of(SkillHttpsTransport.Method.POST), sent);
        assertTrue(reopened.list(CONTACT).isEmpty());
        for (File file : directory.listFiles((parent, name) -> name.endsWith(".bin"))) {
            String data = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertFalse(data.contains(KEY));
            assertFalse(data.contains(ENDPOINT));
            assertFalse(data.contains("synthetic-private-user-input"));
        }
    }

    @Test public void resumesOriginalRemoteIdAndRejectsChangedBindingOrMismatchedReply() throws Exception {
        File directory = temporary.newFolder();
        List<String> sent = new ArrayList<>();
        var binding = binding();
        var access = access(binding, () -> {});
        UUID local = UUID.randomUUID();
        var jobs = new SkillVideoJobs(directory, clock::get, (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            assertEquals(KEY, bearer);
            sent.add(method.name() + " " + url);
            return switch (sent.size()) {
                case 1 -> json(202, "{\"job_id\":\"task_1\"}");
                case 2 -> json(200, "{\"id\":\"other_task\",\"state\":\"done\"}");
                case 3 -> json(200, "{\"id\":\"task_1\",\"state\":\"done\",\"url\":\"https://other.example/unsafe\"}");
                case 4 -> new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
                default -> throw new AssertionError("unexpected network");
            };
        });
        assertEquals(SkillVideoJobs.State.QUEUED,
                jobs.submit(WORK, jobs.generation(WORK), local, access, POST, Long.MAX_VALUE).state());
        var changed = new SkillVideoJobs.Binding(binding.skillId(), binding.revisionId(), UUID.randomUUID().toString(),
                binding.declaration(), ENDPOINT);
        assertThrows(SkillVideoJobs.Failure.class,
                () -> jobs.refresh(WORK, local, access(changed, () -> {}), Long.MAX_VALUE));
        assertThrows(SkillVideoJobs.Failure.class, () -> jobs.refresh(WORK, local, access, Long.MAX_VALUE));
        assertNull(jobs.playableFile(WORK, local));
        assertEquals(2, sent.size());
        clock.addAndGet(60_000);
        assertEquals(SkillVideoJobs.State.COMPLETED, jobs.refresh(WORK, local, access, Long.MAX_VALUE).state());
        jobs.markLinked(WORK, local);
        var reopened = new SkillVideoJobs(directory, clock::get, null);
        assertTrue(reopened.list(WORK).get(0).linked());
        assertNull(reopened.playableFile(CONTACT, local));
        assertArrayEquals(structuralMp4(), Files.readAllBytes(reopened.playableFile(WORK, local).toPath()));
        assertEquals(List.of("POST " + ENDPOINT, "GET https://video.example.com:9443/jobs/task_1",
                "GET https://video.example.com:9443/jobs/task_1",
                "GET https://video.example.com:9443/jobs/task_1/content"), sent);
    }

    @Test public void pinnedRouteReopensWithTheOriginalCredentialAndPostsOnlyTheOriginalIdForStatus() throws Exception {
        File directory = temporary.newFolder();
        var template = binding();
        JSONObject declaration = template.declaration().toJson();
        JSONObject video = declaration.getJSONObject("video");
        video.remove("statusPath");
        video.put("statusPostPath", "/jobs/query").put("statusPostIdField", "task_id");
        var vault = new AiCredentialVault(() -> new javax.crypto.spec.SecretKeySpec(new byte[32], "AES"));
        var credentials = new SkillCustomService(vault, (endpoint, bearer, body, deadline, limit, check) -> {
            throw new AssertionError("Local credential sealing must not call the transport");
        }, System::nanoTime);
        var sealed = credentials.configure(template.skillId(), ENDPOINT, KEY);
        var original = new SkillVideoJobs.Binding(template.skillId(), template.revisionId(), sealed.revision(),
                SkillHttpDeclaration.parse(declaration, SkillResult.Type.VIDEO, List.of()), ENDPOINT);
        var pin = new SkillVideoJobs.Pinned(original, sealed.encryptedBearer());
        var journal = directory.toPath().resolve("work-" + WORK.conversationKey() + ".bin");
        List<String> sent = new ArrayList<>();
        SkillVideoJobs.Transport transport = (method, media, endpoint, bearer, body, deadline, check) -> {
            check.check();
            assertEquals(KEY, bearer);
            sent.add(method + " " + endpoint);
            if (endpoint.equals(ENDPOINT)) {
                String before = new String(Files.readAllBytes(journal), StandardCharsets.UTF_8);
                assertTrue(before.contains(sealed.encryptedBearer()));
                assertFalse(before.contains(KEY));
                assertFalse(before.contains("synthetic-private-user-input"));
                assertArrayEquals(POST, body);
                return json(202, "{\"job_id\":\"pinned_1\"}");
            }
            if (endpoint.endsWith("/jobs/query")) {
                assertFalse(media);
                assertEquals(SkillHttpsTransport.Method.POST, method);
                assertEquals("{\"task_id\":\"pinned_1\"}", new String(body, StandardCharsets.UTF_8));
                return json(200, "{\"id\":\"pinned_1\",\"state\":\"done\"}");
            }
            assertTrue(media);
            assertNull(body);
            return new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
        };

        UUID local = UUID.randomUUID();
        var jobs = new SkillVideoJobs(directory, clock::get, transport);
        long generation = jobs.generation(WORK);
        var queued = jobs.submit(WORK, generation, local, access(original, () -> {}), POST, Long.MAX_VALUE, pin);
        assertEquals(SkillVideoJobs.State.QUEUED, queued.state());
        var reopened = new SkillVideoJobs(directory, clock::get, transport);
        assertThrows(SkillVideoJobs.Failure.class, () -> reopened.pinned(CONTACT, queued));
        var recovered = reopened.pinned(WORK, queued);
        assertEquals(pin, recovered);
        var changed = new SkillVideoJobs.Binding(original.skillId(), UUID.randomUUID().toString(), original.configRevision(),
                original.declaration(), "https://replacement.example.com/v2/create");
        assertEquals(SkillVideoJobs.Code.STALE, assertThrows(SkillVideoJobs.Failure.class,
                () -> reopened.refresh(WORK, local, access(changed, () -> {}), Long.MAX_VALUE)).code());
        String bearer = credentials.credential(new SkillCustomService.Binding(recovered.binding().skillId(),
                recovered.binding().configRevision(), recovered.binding().endpoint(), recovered.encryptedBearer()));
        var resumed = new SkillVideoJobs.Access(recovered.binding(), bearer, () -> {});
        assertEquals(SkillVideoJobs.State.COMPLETED, reopened.refresh(WORK, local, resumed, Long.MAX_VALUE).state());
        assertEquals(List.of("POST " + ENDPOINT, "POST https://video.example.com:9443/jobs/query",
                "GET https://video.example.com:9443/jobs/pinned_1/content"), sent);
        assertNotNull(reopened.playableFile(WORK, local));
        assertThrows(SkillVideoJobs.Failure.class, () -> reopened.pinned(WORK, queued));
        assertFalse(new String(Files.readAllBytes(journal), StandardCharsets.UTF_8).contains(sealed.encryptedBearer()));
        reopened.clear(WORK);
        assertNull(reopened.playableFile(WORK, local));
        assertEquals(SkillVideoJobs.Code.CLEARED, assertThrows(SkillVideoJobs.Failure.class,
                () -> reopened.submit(WORK, generation, local, resumed, POST, Long.MAX_VALUE, pin)).code());
        assertEquals(3, sent.size());
    }

    @Test public void persistsOnlyASafeSubmittedRelativeStatusPathAndRejectsCredentialEcho() throws Exception {
        File directory = temporary.newFolder();
        var normal = binding();
        JSONObject declaration = normal.declaration().toJson();
        JSONObject video = declaration.getJSONObject("video");
        video.remove("statusPath");
        video.put("statusUrlField", "status_url");
        var configured = new SkillVideoJobs.Binding(normal.skillId(), normal.revisionId(), normal.configRevision(),
                SkillHttpDeclaration.parse(declaration, SkillResult.Type.VIDEO, List.of()), ENDPOINT);
        var access = access(configured, () -> {});
        String status = "https://video.example.com:9443/progress/dynamic_1";
        List<String> sent = new ArrayList<>();
        SkillVideoJobs.Transport transport = (method, media, endpoint, bearer, body, deadline, check) -> {
            check.check();
            sent.add(method + " " + endpoint);
            return switch (sent.size()) {
                case 1 -> json(202, "{\"job_id\":\"hidden\",\"status_url\":\"/progress/" + KEY + "\"}");
                case 2 -> json(202, "{\"job_id\":\"dynamic_1\",\"status_url\":\"" + status + "\"}");
                case 3 -> json(200, "{\"id\":\"dynamic_1\",\"state\":\"running\"}");
                default -> throw new AssertionError("Unexpected network");
            };
        };
        var jobs = new SkillVideoJobs(directory, clock::get, transport);
        long generation = jobs.generation(WORK);
        UUID refused = UUID.randomUUID();
        assertEquals(SkillVideoJobs.State.SUBMIT_UNKNOWN,
                jobs.submit(WORK, generation, refused, access, POST, Long.MAX_VALUE).state());
        assertEquals(SkillVideoJobs.State.SUBMIT_UNKNOWN, jobs.refresh(WORK, refused, access, Long.MAX_VALUE).state());
        UUID local = UUID.randomUUID();
        assertEquals(SkillVideoJobs.State.QUEUED, jobs.submit(WORK, generation, local, access, POST, Long.MAX_VALUE).state());
        String journal = new String(Files.readAllBytes(directory.toPath().resolve("work-" + WORK.conversationKey() + ".bin")),
                StandardCharsets.UTF_8);
        assertTrue(journal.contains("/progress/dynamic_1"));
        assertFalse(journal.contains(status));
        assertFalse(journal.contains(KEY));
        var reopened = new SkillVideoJobs(directory, clock::get, transport);
        assertEquals(SkillVideoJobs.State.RUNNING, reopened.refresh(WORK, local, access, Long.MAX_VALUE).state());
        assertEquals(List.of("POST " + ENDPOINT, "POST " + ENDPOINT, "GET " + status), sent);
    }

    @Test public void configuredPollingFloorSurvivesReopenAndNeverShortensAdaptiveBackoff() throws Exception {
        File directory = temporary.newFolder();
        AtomicInteger submits = new AtomicInteger();
        List<Long> checks = new ArrayList<>();
        SkillVideoJobs.Transport transport = (method, media, url, bearer, body, deadline, admission) -> {
            admission.check();
            assertFalse(media);
            if (method == SkillHttpsTransport.Method.POST) {
                assertEquals(ENDPOINT, url);
                assertArrayEquals(POST, body);
                submits.incrementAndGet();
                return json(202, "{\"job_id\":\"same_task\"}");
            }
            assertEquals(SkillHttpsTransport.Method.GET, method);
            assertEquals("https://video.example.com:9443/jobs/same_task", url);
            assertNull(body);
            checks.add(clock.get());
            if (checks.size() == 2) throw new IOException("status fixture unavailable");
            return json(200, "{\"id\":\"same_task\",\"state\":\"running\"}");
        };
        var access = access(pollBinding(8), () -> {});
        UUID local = UUID.randomUUID();
        var first = new SkillVideoJobs(directory, clock::get, transport);
        assertEquals(SkillVideoJobs.State.QUEUED,
                first.submit(WORK, first.generation(WORK), local, access, POST, Long.MAX_VALUE).state());
        var reopened = new SkillVideoJobs(directory, clock::get, transport);
        long[] due = {9_000, 17_000, 25_000, 33_000, 41_000, 49_000, 57_000, 69_000};
        for (int index = 0; index < due.length; index++) {
            clock.set(due[index] - 1);
            assertEquals(local, reopened.refresh(WORK, local, access, Long.MAX_VALUE).localId());
            assertEquals(index, checks.size());
            clock.set(due[index]);
            if (index == 1) {
                var unavailable = assertThrows(SkillVideoJobs.Failure.class,
                        () -> reopened.refresh(WORK, local, access, Long.MAX_VALUE));
                assertEquals(SkillVideoJobs.Code.TRANSPORT, unavailable.code());
            } else {
                assertEquals(SkillVideoJobs.State.RUNNING, reopened.refresh(WORK, local, access, Long.MAX_VALUE).state());
            }
        }
        assertEquals(List.of(9_000L, 17_000L, 25_000L, 33_000L, 41_000L, 49_000L, 57_000L, 69_000L), checks);
        assertEquals(SkillVideoJobs.State.RUNNING,
                reopened.submit(WORK, reopened.generation(WORK), local, access, POST, Long.MAX_VALUE).state());
        assertEquals(1, submits.get());
    }

    @Test public void pollingFloorDoesNotSlowFixedPathDownloadRetries() throws Exception {
        List<String> requests = new ArrayList<>();
        var access = access(pollBinding(8), () -> {});
        var jobs = new SkillVideoJobs(temporary.newFolder(), clock::get, (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            requests.add(method + " " + url);
            return switch (requests.size()) {
                case 1 -> json(202, "{\"job_id\":\"download_task\"}");
                case 2 -> json(200, "{\"id\":\"download_task\",\"state\":\"done\"}");
                case 3 -> json(503, "{}");
                case 4 -> new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
                default -> throw new AssertionError("Unexpected request");
            };
        });
        UUID id = UUID.randomUUID();
        jobs.submit(WORK, jobs.generation(WORK), id, access, POST, Long.MAX_VALUE);
        clock.set(9_000);
        assertThrows(SkillVideoJobs.Failure.class, () -> jobs.refresh(WORK, id, access, Long.MAX_VALUE));
        assertEquals(3, requests.size());
        clock.set(11_999);
        assertEquals(SkillVideoJobs.State.DOWNLOAD_READY, jobs.refresh(WORK, id, access, Long.MAX_VALUE).state());
        assertEquals(3, requests.size());
        clock.set(12_000);
        assertEquals(SkillVideoJobs.State.COMPLETED, jobs.refresh(WORK, id, access, Long.MAX_VALUE).state());
        String status = "GET https://video.example.com:9443/jobs/download_task";
        assertEquals(List.of("POST " + ENDPOINT, status, status + "/content", status + "/content"), requests);
    }

    @Test public void nestedRepliesRequireTheDeclaredIdAndStateBeforeUsingTheOriginalDownloadRoute() throws Exception {
        List<String> sent = new ArrayList<>();
        var access = access(binding("data.task.id", "data.task.id", "data.task.lifecycle.state"), () -> {});
        var jobs = new SkillVideoJobs(temporary.newFolder(), clock::get, (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            sent.add(method.name() + " " + url);
            return switch (sent.size()) {
                case 1 -> json(202, "{\"data.task.id\":\"other_task\",\"data\":{\"task\":{}}}");
                case 2 -> json(202, "{\"data\":{\"task\":{\"id\":\"nested_01\"}}}");
                case 3 -> json(200, "{\"data\":{\"task\":{\"id\":\"other_task\",\"lifecycle\":{\"state\":\"done\"}}}}");
                case 4 -> json(200, "{\"data\":{\"task\":{\"id\":\"nested_01\",\"lifecycle\":{\"state\":\"running\"}}}}");
                case 5 -> json(200, "{\"data\":{\"task\":{\"id\":\"nested_01\",\"lifecycle\":{\"state\":\"done\"}}}}");
                case 6 -> new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
                default -> throw new AssertionError("unexpected network");
            };
        });
        long generation = jobs.generation(WORK);
        UUID missing = UUID.randomUUID();
        var unknown = jobs.submit(WORK, generation, missing, access, POST, Long.MAX_VALUE);
        assertEquals(SkillVideoJobs.State.SUBMIT_UNKNOWN, unknown.state());
        assertEquals(unknown, jobs.submit(WORK, generation, missing, access, POST, Long.MAX_VALUE));

        UUID local = UUID.randomUUID();
        assertEquals(SkillVideoJobs.State.QUEUED, jobs.submit(WORK, generation, local, access, POST, Long.MAX_VALUE).state());
        var wrong = assertThrows(SkillVideoJobs.Failure.class, () -> jobs.refresh(WORK, local, access, Long.MAX_VALUE));
        assertEquals(SkillVideoJobs.Code.RESPONSE, wrong.code());
        assertNull(jobs.playableFile(WORK, local));
        clock.addAndGet(60_000);
        assertEquals(SkillVideoJobs.State.RUNNING, jobs.refresh(WORK, local, access, Long.MAX_VALUE).state());
        clock.addAndGet(60_000);
        assertEquals(SkillVideoJobs.State.COMPLETED, jobs.refresh(WORK, local, access, Long.MAX_VALUE).state());
        assertArrayEquals(structuralMp4(), Files.readAllBytes(jobs.playableFile(WORK, local).toPath()));

        String status = "GET https://video.example.com:9443/jobs/nested_01";
        assertEquals(List.of("POST " + ENDPOINT, "POST " + ENDPOINT, status, status, status,
                status + "/content"), sent);
    }

    @Test public void statusResultVideoRetriesOnlyItsSavedRelativeMediaPathAndOffersNoCancellation() throws Exception {
        File directory = temporary.newFolder();
        List<String> sent = new ArrayList<>();
        SkillVideoJobs.Access access = access(resultUrlBinding(), () -> {});
        SkillVideoJobs.Transport transport = (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            assertEquals(KEY, bearer);
            sent.add(method.name() + " " + url);
            return switch (sent.size()) {
                case 1 -> json(202, "{\"job_id\":\"direct_1\"}");
                case 2 -> json(200, directStatus("direct_1", "/media/first_clip.mp4"));
                case 3 -> new SkillHttpsTransport.Response(503, "", new byte[0]);
                case 4 -> new SkillHttpsTransport.Response(200, "video/mp4", structuralMp4());
                default -> throw new AssertionError("unexpected network");
            };
        };
        var jobs = new SkillVideoJobs(directory, clock::get, transport);
        UUID local = UUID.randomUUID();
        var queued = jobs.submit(WORK, jobs.generation(WORK), local, access, POST, Long.MAX_VALUE);
        assertEquals(SkillVideoJobs.State.QUEUED, queued.state());
        assertFalse(queued.canCancel());
        var reopened = new SkillVideoJobs(directory, clock::get, transport);
        assertFalse(reopened.list(WORK).get(0).canCancel());
        assertThrows(SkillVideoJobs.Failure.class, () -> reopened.cancelTarget(WORK, local, access));
        assertEquals(queued, reopened.cancel(WORK, local, access, Long.MAX_VALUE));
        assertEquals(1, sent.size());
        assertThrows(SkillVideoJobs.Failure.class, () -> reopened.refresh(WORK, local, access, Long.MAX_VALUE));
        assertEquals(SkillVideoJobs.State.DOWNLOAD_READY, reopened.list(WORK).get(0).state());
        for (File file : directory.listFiles((parent, name) -> name.endsWith(".bin"))) {
            String registry = new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
            assertTrue(registry.contains("/media/first_clip.mp4"));
            assertFalse(registry.contains("https://video.example.com:9443/media/first_clip.mp4"));
            assertFalse(registry.contains(KEY));
        }

        clock.addAndGet(60_000);
        var restored = new SkillVideoJobs(directory, clock::get, transport);
        assertEquals(SkillVideoJobs.State.COMPLETED, restored.refresh(WORK, local, access, Long.MAX_VALUE).state());
        File artifact = restored.playableFile(WORK, local);
        assertArrayEquals(structuralMp4(), Files.readAllBytes(artifact.toPath()));
        Files.write(artifact.toPath(), bytes("tampered"));
        assertNull(restored.playableFile(WORK, local));
        String status = "GET https://video.example.com:9443/jobs/direct_1";
        assertEquals(List.of("POST " + ENDPOINT, status, "GET https://video.example.com:9443/media/first_clip.mp4",
                "GET https://video.example.com:9443/media/first_clip.mp4"), sent);
    }

    @Test public void directResultRefusesWrongJobPrivateForeignCredentialAndAmbiguousLinks() throws Exception {
        String[] replies = {
                directStatus("other", "/media/clip.mp4"),
                directStatus("direct_2", "https://other.example.com:9443/media/clip.mp4"),
                directStatus("direct_2", "https://video.example.com/media/clip.mp4"),
                directStatus("direct_2", "https://127.0.0.1:9443/media/clip.mp4"),
                directStatus("direct_2", "//other.example.com/media/clip.mp4"),
                directStatus("direct_2", "///other.example.com/media/clip.mp4"),
                directStatus("direct_2", "http://video.example.com:9443/media/clip.mp4"),
                directStatus("direct_2", "https://user@video.example.com:9443/media/clip.mp4"),
                directStatus("direct_2", "/media/clip.mp4?token=irrelevant"),
                directStatus("direct_2", "/media/clip.mp4#unsafe"),
                directStatus("direct_2", "/media/" + KEY + "/clip.mp4"),
                directStatus("direct_2", "/media/%2e%2e/clip.mp4"),
                directStatus("direct_2", "../media/clip.mp4")
        };
        var access = access(resultUrlBinding(), () -> {});
        var reads = new AtomicInteger();
        var jobs = new SkillVideoJobs(temporary.newFolder(), clock::get, (method, media, url, token, body, deadline, gate) -> {
            gate.check();
            assertFalse("An invalid result must never reach a download request", media);
            if (method == SkillHttpsTransport.Method.POST) return json(202, "{\"job_id\":\"direct_2\"}");
            int read = reads.getAndIncrement();
            if (read == 0) return json(200, directStatus("direct_2", "https://other.example.com/clip.mp4")
                    .replace("\"done\"", "\"running\""));
            return json(200, replies[read - 1]);
        });
        UUID local = UUID.randomUUID();
        jobs.submit(WORK, jobs.generation(WORK), local, access, POST, Long.MAX_VALUE);
        assertEquals(SkillVideoJobs.State.RUNNING, jobs.refresh(WORK, local, access, Long.MAX_VALUE).state());
        clock.addAndGet(60_000);
        for (String ignored : replies) {
            var failure = assertThrows(SkillVideoJobs.Failure.class,
                    () -> jobs.refresh(WORK, local, access, Long.MAX_VALUE));
            assertEquals(SkillVideoJobs.Code.RESPONSE, failure.code());
            assertNull(jobs.playableFile(WORK, local));
            clock.addAndGet(60_000);
        }
        assertEquals(replies.length + 1, reads.get());
    }

    @Test public void olderJobCatalogRemainsUnopenedWhileTheNewNamespaceStartsEmpty() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File directory = new File(context.getFilesDir(), "douai_real_video_jobs");
        assertTrue(directory.mkdirs());
        var legacy = directory.toPath().resolve("work-" + "a".repeat(64) + ".bin");
        UUID local = UUID.randomUUID();
        try (DataOutputStream output = new DataOutputStream(Files.newOutputStream(legacy))) {
            output.writeInt(1);
            output.writeLong(0);
            output.writeInt(1);
            output.writeUTF(local.toString());
            for (int index = 0; index < 3; index++) output.writeUTF(UUID.randomUUID().toString());
            output.writeLong(clock.get());
            output.writeUTF("QUEUED");
            output.writeBoolean(false);
            output.writeUTF("a".repeat(43));
            output.writeUTF("legacy_task");
            output.writeLong(0);
            output.writeUTF(UUID.randomUUID().toString());
            output.writeUTF("");
            output.writeByte(0);
        }
        byte[] untouched = Files.readAllBytes(legacy);
        var jobs = new SkillVideoJobs(directory, clock::get, null);
        assertEquals(SkillVideoJobs.Code.STORAGE, assertThrows(SkillVideoJobs.Failure.class, () -> jobs.list(WORK)).code());
        var current = SkillVideoJobs.forContext(context);
        assertTrue(current.list(WORK).isEmpty());
        current.clear(WORK);
        assertArrayEquals(untouched, Files.readAllBytes(legacy));
    }

    @Test public void cancellationSendsOnceUsesTheOriginalIdAndOnlyThenPolls() throws Exception {
        List<SkillHttpsTransport.Method> sent = new ArrayList<>();
        var jobs = new SkillVideoJobs(temporary.newFolder(), clock::get, (method, media, url, bearer, body, deadline, gate) -> {
            gate.check();
            sent.add(method);
            if (method == SkillHttpsTransport.Method.POST) return json(202, "{\"job_id\":\"task_2\"}");
            if (method == SkillHttpsTransport.Method.DELETE) {
                assertTrue(url.endsWith("/jobs/task_2/cancel"));
                throw new SkillHttpsTransport.Failure(SkillHttpsTransport.Kind.CANCELLED, true);
            }
            assertTrue(url.endsWith("/jobs/task_2"));
            return json(200, "{\"id\":\"task_2\",\"state\":\"cancelled\"}");
        });
        var access = access(binding(), () -> {});
        UUID local = UUID.randomUUID();
        jobs.submit(WORK, jobs.generation(WORK), local, access, POST, Long.MAX_VALUE);
        assertEquals(SkillVideoJobs.State.CANCEL_PENDING, jobs.cancel(WORK, local, access, Long.MAX_VALUE).state());
        assertEquals(SkillVideoJobs.State.CANCEL_PENDING, jobs.cancel(WORK, local, access, Long.MAX_VALUE).state());
        clock.addAndGet(60_000);
        assertEquals(SkillVideoJobs.State.CANCELLED, jobs.refresh(WORK, local, access, Long.MAX_VALUE).state());
        assertEquals(List.of(SkillHttpsTransport.Method.POST, SkillHttpsTransport.Method.DELETE,
                SkillHttpsTransport.Method.GET), sent);
    }

    @Test public void clearedScopeRejectsQueuedAdmissionAndDoesNotConflateOtherHistory() throws Exception {
        var jobs = new SkillVideoJobs(temporary.newFolder(), clock::get,
                (method, media, url, bearer, body, deadline, gate) -> json(202, "{\"job_id\":\"demo\"}"));
        var access = access(binding(), () -> {});
        UUID local = UUID.randomUUID();
        long previous = jobs.generation(WORK);
        jobs.submit(WORK, previous, local, access, POST, Long.MAX_VALUE);
        jobs.submit(CONTACT, jobs.generation(CONTACT), local, access, POST, Long.MAX_VALUE);
        jobs.clear(WORK);
        assertThrows(SkillVideoJobs.Failure.class, () -> jobs.submit(WORK, previous, local, access, POST, Long.MAX_VALUE));
        assertTrue(jobs.list(WORK).isEmpty());
        assertEquals(1, jobs.list(CONTACT).size());
    }

    @Test public void validatesMp4StructureAndRejectsExternalDataReference() throws Exception {
        assertTrue(SkillVideoJobs.validMp4(structuralMp4()));
        assertFalse(SkillVideoJobs.validMp4(box("ftyp", bytes("isom0000"))));
        assertFalse(SkillVideoJobs.validMp4(bytes("<html>provider error</html>")));
        assertFalse(SkillVideoJobs.validMp4(structuralMp4(new byte[]{0, 0, 0, 0})));
    }

    private static SkillVideoJobs.Binding binding() {
        return binding("job_id", "id", "state");
    }

    private static SkillVideoJobs.Binding pollBinding(int seconds) throws Exception {
        var ordinary = binding();
        JSONObject schema = ordinary.declaration().toJson();
        schema.getJSONObject("video").put("minPollSeconds", seconds);
        return new SkillVideoJobs.Binding(ordinary.skillId(), ordinary.revisionId(), ordinary.configRevision(),
                SkillHttpDeclaration.parse(schema, SkillResult.Type.VIDEO, List.of()), ENDPOINT);
    }

    private static SkillVideoJobs.Binding resultUrlBinding() throws Exception {
        var ordinary = binding();
        JSONObject schema = ordinary.declaration().toJson();
        JSONObject video = schema.getJSONObject("video");
        video.remove("cancelPath");
        video.remove("cancelMethod");
        video.remove("downloadPath");
        video.put("resultUrlField", "data.result.url");
        return new SkillVideoJobs.Binding(ordinary.skillId(), ordinary.revisionId(), ordinary.configRevision(),
                SkillHttpDeclaration.parse(schema, SkillResult.Type.VIDEO, List.of()), ENDPOINT);
    }

    private static String directStatus(String id, String url) {
        return "{\"id\":\"" + id + "\",\"state\":\"done\",\"data\":{\"result\":{\"url\":\"" + url + "\"}}}";
    }

    private static SkillVideoJobs.Binding binding(String response, String idField, String stateField) {
        var declaration = SkillHttpDeclaration.parse("""
                {"version":1,"label":"视频服务","request":{"prompt":"argument"},"response":"%s",
                 "video":{"statusPath":"/jobs/{id}","cancelPath":"/jobs/{id}/cancel","cancelMethod":"DELETE",
                 "downloadPath":"/jobs/{id}/content","idField":"%s","stateField":"%s",
                 "states":{"queued":"queued","running":"running","done":"completed","error":"failed",
                           "cancelled":"cancelled"}}}
                """.formatted(response, idField, stateField), SkillResult.Type.VIDEO, List.of());
        return new SkillVideoJobs.Binding(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), declaration, ENDPOINT);
    }

    private static SkillVideoJobs.Access access(SkillVideoJobs.Binding binding, SkillVideoJobs.Admission admission) {
        return new SkillVideoJobs.Access(binding, KEY, admission);
    }

    private static SkillHttpsTransport.Response json(int status, String data) {
        return new SkillHttpsTransport.Response(status, "application/json", bytes(data));
    }

    private static byte[] structuralMp4() throws IOException { return structuralMp4(new byte[]{0, 0, 0, 1}); }

    private static byte[] structuralMp4(byte[] referenceFlags) throws IOException {
        byte[] references = box("dref", new byte[]{0, 0, 0, 0, 0, 0, 0, 1}, box("url ", referenceFlags));
        byte[] info = box("minf", box("dinf", references));
        byte[] handler = box("hdlr", new byte[8], bytes("vide"));
        return concat(box("ftyp", bytes("isom0000isom")),
                box("moov", box("trak", box("mdia", handler, info))), box("mdat", new byte[]{0, 0, 0, 1}));
    }

    private static byte[] box(String type, byte[]... payloads) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var output = new DataOutputStream(bytes);
        byte[] payload = concat(payloads);
        output.writeInt(payload.length + 8);
        output.write(bytes(type));
        output.write(payload);
        return bytes.toByteArray();
    }

    private static byte[] concat(byte[]... chunks) throws IOException {
        var buffer = new ByteArrayOutputStream();
        for (byte[] chunk : chunks) buffer.write(chunk);
        return buffer.toByteArray();
    }

    private static byte[] bytes(String value) { return value.getBytes(StandardCharsets.UTF_8); }
}
