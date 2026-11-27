package com.douai.module.ai.skills;

import android.content.Context;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.regex.Pattern;

/** Private video task journal. Only the approved declaration and current authorization may create network activity. */
public final class SkillVideoJobs {
    private static final Object IO_LOCK = new Object();
    private static final Pattern SCOPE_KEY = Pattern.compile("[a-f0-9]{64}");
    private static final Pattern HASH = Pattern.compile("[A-Za-z0-9_-]{43}");
    static final String JOURNAL_DIRECTORY = "douai_real_video_jobs_v3";
    private static final int FORMAT_VERSION = 3;
    private static final int MAX_JOBS = 40;
    private static final int MAX_SCOPES = 128;
    private static final int MAX_POLL_VISITS = 120;
    private static final int MAX_DEVICE_ATTEMPTS_PER_MINUTE = 48;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final int MAX_SAVED_PATH = 2048;
    private static final int MAX_JSON_BYTES = 16 * 1024;
    private static final int MAX_MEDIA_BYTES = SkillHttpsTransport.MAX_VIDEO_BYTES;
    private static final long TOTAL_MEDIA_BYTES = 96L * 1024 * 1024;
    private static final long JOB_INTERVAL = TimeUnit.HOURS.toMillis(2);
    private static final long POLL_LEASE = 65_000;
    private static final byte[] EMPTY_JSON = {'{', '}'};

    public record Scope(AiSkills.Entry entry, String conversationKey) {
        public Scope {
            Objects.requireNonNull(entry);
            if (conversationKey == null || !SCOPE_KEY.matcher(conversationKey).matches()) {
                throw new IllegalArgumentException("对话范围无效");
            }
        }
        private String key() { return entry.name().toLowerCase(Locale.ROOT) + "-" + conversationKey; }
    }

    public record Binding(String skillId, String revisionId, String configRevision,
                          SkillHttpDeclaration declaration, String endpoint) {
        public Binding {
            if (!uuid(skillId) || !uuid(revisionId) || !uuid(configRevision)
                    || declaration == null || declaration.video() == null) {
                throw new IllegalArgumentException("视频供应商配置无效");
            }
            try { endpoint = SkillCustomService.validateEndpoint(endpoint); }
            catch (SkillCustomService.Failure invalid) { throw new IllegalArgumentException("视频供应商地址无效"); }
        }

        private String fingerprint() {
            MessageDigest digest = sha256();
            for (String part : List.of(skillId, revisionId, configRevision, endpoint, declaration.toJson().toString())) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0);
            }
            return hash(digest);
        }

        @Override public String toString() { return "SkillVideoJobs.Binding"; }
    }

    /** Immutable continuation contract and the existing endpoint-bound ciphertext. */
    public record Pinned(Binding binding, String encryptedBearer) {
        public Pinned {
            Objects.requireNonNull(binding);
            new SkillCustomService.Binding(binding.skillId(), binding.configRevision(), binding.endpoint(), encryptedBearer);
            SkillHttpDeclaration restored = SkillHttpDeclaration.parse(binding.declaration().toJson().toString(),
                    SkillResult.Type.VIDEO, List.of());
            if (!binding.declaration().equals(restored)) throw new IllegalArgumentException("视频任务的接口声明无法保存");
        }

        @Override public String toString() { return "SkillVideoJobs.Pinned"; }
    }

    @FunctionalInterface public interface Admission { void check() throws IOException; }
    public record Access(Binding binding, String bearer, Admission admission) {
        public Access {
            Objects.requireNonNull(binding);
            Objects.requireNonNull(admission);
            if (bearer == null || !bearer.isEmpty() && !AiCredentialVault.validBearer(bearer)) {
                throw new IllegalArgumentException("视频技能凭据不可用");
            }
        }
        @Override public String toString() { return "SkillVideoJobs.Access"; }
    }

    public enum State {
        SUBMIT_UNKNOWN, QUEUED, RUNNING, CANCEL_PENDING, DOWNLOAD_READY, COMPLETED, FAILED, CANCELLED, TIMED_OUT;

        public boolean terminal() {
            return this == SUBMIT_UNKNOWN || this == COMPLETED || this == FAILED || this == CANCELLED || this == TIMED_OUT;
        }
    }

    public record Job(UUID localId, String skillId, String revisionId, String configRevision, long createdAtMillis,
                      State state, boolean linked, boolean cancellationSupported) {
        public Job(UUID localId, String skillId, String revisionId, String configRevision, long createdAtMillis,
                   State state, boolean linked) {
            this(localId, skillId, revisionId, configRevision, createdAtMillis, state, linked, true);
        }

        public boolean canCancel() { return cancellationSupported && (state == State.QUEUED || state == State.RUNNING); }
    }

    public enum Code {
        STORAGE("无法读取或保存本机视频任务"),
        STALE("视频技能或供应商配置已变化，请重新核对"),
        CLEARED("对话历史已清空"),
        LIMIT("本机视频任务或视频存储已达上限"),
        RATE_LIMIT("视频供应商调用过于频繁，请稍后查看原任务"),
        INVALID_INPUT("视频技能请求无效"),
        RESPONSE("视频供应商响应格式不符合已确认的接口"),
        TRANSPORT("无法查询视频供应商，请核对网络或授权"),
        CANCEL_REFUSED("取消请求未获确认，仍可核实原任务");

        private final String message;
        Code(String message) { this.message = message; }
    }

    public static final class Failure extends IOException {
        private final Code code;
        Failure(Code code) { super(code.message); this.code = code; }
        public Code code() { return code; }
    }

    @FunctionalInterface interface Transport {
        SkillHttpsTransport.Response call(SkillHttpsTransport.Method method, boolean media, String endpoint,
                                          String bearer, byte[] body, long deadlineNanos,
                                          SkillHttpsTransport.Admission admission) throws IOException;
    }

    @FunctionalInterface private interface Transaction<T> { T run(Path file, Catalog catalog) throws IOException; }
    private record Catalog(long generation, Map<UUID, Entry> jobs) {}
    private record Claim(Entry entry, boolean execute) {}
    private record Cancellation(Entry entry, State previous) {}
    private record Routes(String statusPath, String resultPath) {
        static final Routes EMPTY = new Routes("", "");

        Routes {
            if (!isRelativeSavedPath(statusPath) || !isRelativeSavedPath(resultPath)) {
                throw new IllegalArgumentException("视频任务路径无效");
            }
        }
    }
    private record Entry(Job job, String fingerprint, String remoteId, long nextPollAt, UUID ticket, String mediaHash,
                         int pollVisits, Pinned pinned, Routes routes) {
        Entry change(State next, String id, long nextPoll, UUID nextTicket, String media) {
            return change(next, id, nextPoll, nextTicket, media, routes);
        }

        Entry change(State next, String id, long nextPoll, UUID nextTicket, String media, Routes savedRoutes) {
            return new Entry(new Job(job.localId(), job.skillId(), job.revisionId(), job.configRevision(),
                    job.createdAtMillis(), next, job.linked(), job.cancellationSupported()),
                    fingerprint, id, nextPoll, nextTicket, media, pollVisits,
                    next.terminal() ? null : pinned, next.terminal() ? Routes.EMPTY : savedRoutes);
        }

        Entry linked() {
            return new Entry(new Job(job.localId(), job.skillId(), job.revisionId(), job.configRevision(),
                    job.createdAtMillis(), job.state(), true, job.cancellationSupported()),
                    fingerprint, remoteId, nextPollAt, ticket, mediaHash, pollVisits, pinned, routes);
        }

        Entry claimPoll(long leasedUntil) {
            return new Entry(job, fingerprint, remoteId, leasedUntil, UUID.randomUUID(), mediaHash, pollVisits + 1, pinned, routes);
        }
    }

    private final Path directory;
    private final LongSupplier clock;
    private final Transport transport;

    public static SkillVideoJobs forContext(Context context) {
        return new SkillVideoJobs(new File(context.getFilesDir(), JOURNAL_DIRECTORY),
                System::currentTimeMillis, SkillVideoJobs::https);
    }

    SkillVideoJobs(File directory, LongSupplier clock, Transport transport) {
        this.directory = directory.toPath();
        this.clock = clock;
        this.transport = transport;
    }

    public long generation(Scope scope) throws Failure {
        return locked(scope, (file, catalog) -> catalog.generation());
    }

    public List<Job> list(Scope scope) throws Failure {
        return locked(scope, (file, catalog) -> {
            List<Job> jobs = new ArrayList<>(catalog.jobs().size());
            for (Entry entry : catalog.jobs().values()) jobs.add(entry.job());
            return Collections.unmodifiableList(jobs);
        });
    }

    public Pinned pinned(Scope scope, Job job) throws Failure {
        if (job == null || job.localId() == null) throw new Failure(Code.STALE);
        return locked(scope, (file, catalog) -> {
            Entry current = catalog.jobs().get(job.localId());
            if (current == null) throw new Failure(Code.CLEARED);
            Job stored = current.job();
            if (stored.state().terminal() || current.pinned() == null || !stored.skillId().equals(job.skillId())
                    || !stored.revisionId().equals(job.revisionId()) || !stored.configRevision().equals(job.configRevision())
                    || stored.createdAtMillis() != job.createdAtMillis()) throw new Failure(Code.STALE);
            return current.pinned();
        });
    }

    public Job submit(Scope scope, long expectedGeneration, UUID localId, Access access,
                      byte[] approvedJson, long deadlineNanos) throws Failure {
        return submit(scope, expectedGeneration, localId, access, approvedJson, deadlineNanos, null);
    }

    public Job submit(Scope scope, long expectedGeneration, UUID localId, Access access,
                      byte[] approvedJson, long deadlineNanos, Pinned pinned) throws Failure {
        Objects.requireNonNull(localId);
        if (access == null || approvedJson == null || approvedJson.length == 0 || approvedJson.length > 64 * 1024) {
            throw new Failure(Code.INVALID_INPUT);
        }
        if (pinned != null && (!pinned.binding().equals(access.binding())
                || pinned.encryptedBearer().isEmpty() != access.bearer().isEmpty())) throw new Failure(Code.INVALID_INPUT);
        byte[] payload = approvedJson.clone();
        if (AiCredentialVault.containsImageCredential(new String(payload, StandardCharsets.UTF_8), access.bearer())) {
            throw new Failure(Code.INVALID_INPUT);
        }
        Binding binding = access.binding();
        if (AiCredentialVault.imageEndpointContainsCredential(binding.endpoint(), access.bearer())
                || pinned != null && AiCredentialVault.containsImageCredential(
                binding.declaration().toJson().toString().replace("\\/", "/"), access.bearer())) {
            throw new Failure(Code.INVALID_INPUT);
        }
        Claim claim = locked(scope, (file, catalog) -> {
            if (catalog.generation() != expectedGeneration) throw new Failure(Code.CLEARED);
            Entry old = catalog.jobs().get(localId);
            if (old != null) {
                requireCurrent(old, access);
                return new Claim(old, false);
            }
            if (catalog.jobs().size() >= MAX_JOBS) throw new Failure(Code.LIMIT);
            // This record is already uncertain before the request can leave the device.
            Job job = new Job(localId, binding.skillId(), binding.revisionId(), binding.configRevision(),
                    clock.getAsLong(), State.SUBMIT_UNKNOWN, false, binding.declaration().video().canCancel());
            Entry intent = new Entry(job, binding.fingerprint(), "", 0, UUID.randomUUID(), "", 0, pinned, Routes.EMPTY);
            update(file, catalog, intent);
            return new Claim(intent, true);
        });
        Entry intent = claim.entry();
        if (!claim.execute()) return intent.job();
        SkillHttpsTransport.Response response;
        com.douai.module.diagnostics.DouAiLog.Operation trace = com.douai.module.diagnostics.DouAiLog.current();
        try {
            response = call(scope, intent, access, SkillHttpsTransport.Method.POST, false, binding.endpoint(),
                    payload, deadlineNanos);
        } catch (SkillHttpsTransport.Failure failure) {
            if (trace != null) trace.stage("video_submit", "transport=" + failure.kind().name()
                    + ";may_have_sent=" + failure.mayHaveSent());
            if (failure.mayHaveSent()) return finishUnknown(scope, intent);
            return finish(scope, intent, State.FAILED, "", 0, "").job();
        } catch (Failure rejectedLocally) {
            if (rejectedLocally.code() != Code.CLEARED) finish(scope, intent, State.FAILED, "", 0, "");
            throw rejectedLocally;
        } catch (IOException unavailable) {
            if (trace != null) trace.stage("video_submit", "transport=io_failure;result=unknown");
            return finishUnknown(scope, intent);
        }
        if (trace != null) trace.stage("video_submit", "http_status=" + response.status());
        if (definiteRefusal(response.status())) return finish(scope, intent, State.FAILED, "", 0, "").job();
        try {
            JSONObject submitted = json(response);
            String remoteId = binding.declaration().readSubmittedTaskId(submitted);
            if (AiCredentialVault.containsImageCredential(remoteId, access.bearer())) return finishUnknown(scope, intent);
            SkillHttpDeclaration.Video video = binding.declaration().video();
            String statusPath = video.readSubmittedStatusPath(submitted, binding.endpoint());
            requireRouteOmitsCredential(video.statusRequest(binding.endpoint(), remoteId, statusPath).endpoint(), access.bearer());
            int minPollSeconds = video.minPollSeconds();
            long firstPoll = minPollSeconds == 0 ? 0 : clock.getAsLong() + TimeUnit.SECONDS.toMillis(minPollSeconds);
            return finish(scope, intent, State.QUEUED, remoteId, firstPoll, "", new Routes(statusPath, "")).job();
        } catch (IllegalArgumentException | Failure invalid) {
            if (invalid instanceof Failure failure && failure.code() != Code.RESPONSE) throw failure;
            return finishUnknown(scope, intent);
        }
    }

    private Job finishUnknown(Scope scope, Entry intent) throws Failure {
        return intent.pinned() == null ? intent.job() : finish(scope, intent, State.SUBMIT_UNKNOWN, "", 0, "").job();
    }

    public Job refresh(Scope scope, UUID localId, Access access, long deadlineNanos) throws Failure {
        Claim claim = locked(scope, (file, catalog) -> {
            Entry old = catalog.jobs().get(localId);
            if (old == null || old.job().state().terminal()) return new Claim(old, false);
            requireCurrent(old, access);
            long now = clock.getAsLong();
            if (now - old.job().createdAtMillis() >= JOB_INTERVAL || old.pollVisits() >= MAX_POLL_VISITS) {
                Entry expired = old.change(State.TIMED_OUT, old.remoteId(), 0, UUID.randomUUID(), "");
                update(file, catalog, expired);
                return new Claim(expired, false);
            }
            if (now < old.nextPollAt()) return new Claim(old, false);
            Entry claimed = old.claimPoll(now + POLL_LEASE);
            update(file, catalog, claimed);
            return new Claim(claimed, true);
        });
        Entry claimed = claim.entry();
        if (!claim.execute()) return claimed == null ? null : claimed.job();
        SkillHttpDeclaration.Video video = access.binding().declaration().video();
        try {
            String endpoint = access.binding().endpoint();
            if (claimed.job().state() == State.DOWNLOAD_READY) {
                String downloadUrl = video.savedDownloadEndpoint(endpoint, claimed.remoteId(), claimed.routes().resultPath());
                return download(scope, claimed, access, downloadUrl, deadlineNanos);
            }
            String savedStatusPath = claimed.routes().statusPath();
            SkillHttpDeclaration.StatusRequest statusRequest = video.statusRequest(endpoint, claimed.remoteId(), savedStatusPath);
            SkillHttpsTransport.Method method = statusRequest.method() == SkillHttpDeclaration.StatusMethod.POST
                    ? SkillHttpsTransport.Method.POST : SkillHttpsTransport.Method.GET;
            SkillHttpsTransport.Response response = call(scope, claimed, access, method, false,
                    statusRequest.endpoint(), statusRequest.body(), deadlineNanos);
            JSONObject status = json(response);
            State state = switch (video.readStatus(status, claimed.remoteId())) {
                case QUEUED -> claimed.job().state() == State.RUNNING ? State.RUNNING : State.QUEUED;
                case RUNNING -> State.RUNNING;
                case COMPLETED -> State.DOWNLOAD_READY;
                case FAILED -> State.FAILED;
                case CANCELLED -> State.CANCELLED;
            };
            if (claimed.job().state() == State.CANCEL_PENDING && (state == State.QUEUED || state == State.RUNNING)) {
                state = State.CANCEL_PENDING;
            }
            if (state == State.DOWNLOAD_READY) {
                String resultPath = video.completedDownloadPath(status, endpoint, claimed.remoteId(), savedStatusPath);
                String downloadUrl = video.savedDownloadEndpoint(endpoint, claimed.remoteId(), resultPath);
                requireRouteOmitsCredential(downloadUrl, access.bearer());
                Entry ready = finish(scope, claimed, state, claimed.remoteId(), claimed.nextPollAt(), "",
                        new Routes(savedStatusPath, resultPath));
                if (ready.ticket().equals(claimed.ticket())) return download(scope, ready, access, downloadUrl, deadlineNanos);
                return ready.job();
            }
            return finish(scope, claimed, state, claimed.remoteId(), nextPoll(claimed, video), "").job();
        } catch (Failure failure) {
            releasePoll(scope, claimed, video);
            throw failure;
        } catch (IllegalArgumentException invalid) {
            releasePoll(scope, claimed, video);
            throw new Failure(Code.RESPONSE);
        } catch (IOException unavailable) {
            releasePoll(scope, claimed, video);
            throw new Failure(Code.TRANSPORT);
        }
    }

    public String cancelTarget(Scope scope, UUID localId, Access access) throws Failure {
        return locked(scope, (file, catalog) -> {
            Entry recorded = catalog.jobs().get(localId);
            if (recorded == null || !recorded.job().canCancel()) throw new Failure(Code.STALE);
            requireCurrent(recorded, access);
            String endpoint = access.binding().declaration().video().cancelEndpoint(access.binding().endpoint(), recorded.remoteId());
            requireRouteOmitsCredential(endpoint, access.bearer());
            return endpoint;
        });
    }

    public Job cancel(Scope scope, UUID localId, Access access, long deadlineNanos) throws Failure {
        Cancellation claim = locked(scope, (file, catalog) -> {
            Entry old = catalog.jobs().get(localId);
            if (old == null || !old.job().canCancel()) return new Cancellation(old, null);
            requireCurrent(old, access);
            // A proven pre-dispatch refusal may restore the previous state. An unknown write stays latched.
            Entry pending = old.change(State.CANCEL_PENDING, old.remoteId(), clock.getAsLong() + POLL_LEASE,
                    UUID.randomUUID(), "");
            update(file, catalog, pending);
            return new Cancellation(pending, old.job().state());
        });
        Entry pending = claim.entry();
        if (claim.previous() == null) return pending == null ? null : pending.job();
        SkillHttpDeclaration.Video video = access.binding().declaration().video();
        boolean rejected = false;
        try {
            SkillHttpsTransport.Method method = SkillHttpsTransport.Method.valueOf(video.cancelMethod());
            SkillHttpsTransport.Response reply = call(scope, pending, access, method, false,
                    video.cancelEndpoint(access.binding().endpoint(), pending.remoteId()),
                    method == SkillHttpsTransport.Method.POST ? EMPTY_JSON : null, deadlineNanos);
            rejected = definiteRefusal(reply.status());
        } catch (SkillHttpsTransport.Failure failure) {
            rejected = !failure.mayHaveSent();
        } catch (Failure rejectedLocally) {
            rejected = true;
        } catch (IllegalArgumentException invalid) {
            rejected = true;
        } catch (IOException uncertain) {
            // A remote task ID remains available; only status queries may follow.
        }
        Entry saved = finish(scope, pending, rejected ? claim.previous() : State.CANCEL_PENDING,
                pending.remoteId(), nextPoll(pending, video), "");
        if (rejected) throw new Failure(Code.CANCEL_REFUSED);
        return saved.job();
    }

    public void markLinked(Scope scope, UUID localId) throws Failure {
        locked(scope, (file, catalog) -> {
            Entry current = catalog.jobs().get(localId);
            if (current != null && !current.job().linked()) update(file, catalog, current.linked());
            return null;
        });
    }

    public void clear(Scope scope) throws Failure {
        locked(scope, false, (file, ignored) -> {
            write(file, new Catalog(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), Map.of()));
            try (DirectoryStream<Path> clips = Files.newDirectoryStream(directory, scope.key() + "-*.mp4")) {
                for (Path clip : clips) Files.deleteIfExists(clip);
            }
            return null;
        });
    }

    public File playableFile(Scope scope, UUID localId) throws Failure {
        return locked(scope, (file, catalog) -> {
            Entry entry = catalog.jobs().get(localId);
            if (entry == null || entry.job().state() != State.COMPLETED) return null;
            Path media = clip(scope, localId);
            return entry.mediaHash().equals(localHash(media)) ? media.toFile() : null;
        });
    }

    private Job reconcileDownloaded(Scope scope, Entry ready) throws Failure {
        return locked(scope, (file, catalog) -> {
            Entry current = sameClaim(ready, catalog);
            if (!current.ticket().equals(ready.ticket()) || current.job().state() != State.DOWNLOAD_READY) return current.job();
            Path media = clip(scope, ready.job().localId());
            String hash = current.mediaHash();
            if (hash.isEmpty() || !hash.equals(localHash(media))) {
                requireMediaSpace(MAX_MEDIA_BYTES);
                return null;
            }
            Entry complete = current.change(State.COMPLETED, current.remoteId(), 0, current.ticket(), hash);
            update(file, catalog, complete);
            return complete.job();
        });
    }

    private Job download(Scope scope, Entry ready, Access access, String downloadUrl, long deadlineNanos) throws Failure {
        SkillHttpDeclaration.Video video = access.binding().declaration().video();
        try {
            Job existing = reconcileDownloaded(scope, ready);
            if (existing != null) return existing;
            SkillHttpsTransport.Response response = call(scope, ready, access, SkillHttpsTransport.Method.GET, true,
                    downloadUrl, null, deadlineNanos);
            if (response.status() == 202 || response.status() == 408 || response.status() == 429 || response.status() >= 500) {
                throw new Failure(Code.TRANSPORT);
            }
            if (response.status() != 200 || !"video/mp4".equals(response.contentType()) || !validMp4(response.body())) {
                finish(scope, ready, State.FAILED, ready.remoteId(), 0, "");
                throw new Failure(Code.RESPONSE);
            }
            byte[] bytes = response.body();
            return locked(scope, (file, catalog) -> {
                Entry current = sameClaim(ready, catalog);
                if (!current.ticket().equals(ready.ticket()) || current.job().state() != State.DOWNLOAD_READY) return current.job();
                requireMediaSpace(bytes.length);
                Path media = clip(scope, ready.job().localId());
                MessageDigest digest = sha256();
                digest.update(bytes);
                String hash = hash(digest);
                Entry proven = current.change(State.DOWNLOAD_READY, current.remoteId(), current.nextPollAt(), current.ticket(), hash);
                update(file, catalog, proven);
                atomicWrite(media, stream -> stream.write(bytes));
                Entry complete = current.change(State.COMPLETED, current.remoteId(), 0, current.ticket(), hash);
                update(file, catalog, complete);
                return complete.job();
            });
        } catch (Failure failure) {
            releasePoll(scope, ready, video);
            throw failure;
        } catch (IllegalArgumentException invalid) {
            releasePoll(scope, ready, video);
            throw new Failure(Code.RESPONSE);
        } catch (SkillHttpsTransport.Failure unavailable) {
            if (unavailable.kind() == SkillHttpsTransport.Kind.RESPONSE_LIMIT) {
                finish(scope, ready, State.FAILED, ready.remoteId(), 0, "");
                throw new Failure(Code.RESPONSE);
            }
            releasePoll(scope, ready, video);
            throw new Failure(Code.TRANSPORT);
        } catch (IOException unavailable) {
            releasePoll(scope, ready, video);
            throw new Failure(Code.TRANSPORT);
        }
    }

    private SkillHttpsTransport.Response call(Scope scope, Entry entry, Access access, SkillHttpsTransport.Method method,
                                              boolean media, String endpoint, byte[] body, long deadline) throws IOException {
        if (transport == null) throw new AssertionError("Unexpected fixture request");
        if (!sameOrigin(access.binding().endpoint(), endpoint)) throw new Failure(Code.STALE);
        requireRouteOmitsCredential(endpoint, access.bearer());
        locked(scope, (file, catalog) -> {
            verifyClaim(entry, access, catalog);
            consumeNetworkBudget(clock.getAsLong());
            return null;
        });
        return transport.call(method, media, endpoint, access.bearer(), body, deadline, () -> {
            access.admission().check();
            locked(scope, (file, catalog) -> {
                verifyClaim(entry, access, catalog);
                return null;
            });
        });
    }

    private static void verifyClaim(Entry expected, Access access, Catalog catalog) throws Failure {
        Entry live = sameClaim(expected, catalog);
        if (!expected.ticket().equals(live.ticket()) || expected.job().state() != live.job().state()) {
            throw new Failure(Code.STALE);
        }
        requireCurrent(live, access);
    }

    private void consumeNetworkBudget(long now) throws IOException {
        Path budget = directory.resolve("network.budget");
        long window = now;
        int calls = 0;
        if (!Files.notExists(budget, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(budget, LinkOption.NOFOLLOW_LINKS) || Files.size(budget) != 12) {
                throw new Failure(Code.STORAGE);
            }
            try (DataInputStream input = new DataInputStream(Files.newInputStream(budget, LinkOption.NOFOLLOW_LINKS))) {
                window = input.readLong();
                calls = input.readInt();
            }
            if (window < 0 || calls < 0 || calls > MAX_DEVICE_ATTEMPTS_PER_MINUTE) throw new Failure(Code.STORAGE);
            if (now - window >= 60_000) {
                window = now;
                calls = 0;
            }
        }
        if (calls >= MAX_DEVICE_ATTEMPTS_PER_MINUTE) throw new Failure(Code.RATE_LIMIT);
        long currentWindow = window;
        int attempts = calls + 1;
        atomicWrite(budget, stream -> {
            var output = new DataOutputStream(stream);
            output.writeLong(currentWindow);
            output.writeInt(attempts);
            output.flush();
        });
    }

    private static SkillHttpsTransport.Response https(SkillHttpsTransport.Method method, boolean media, String endpoint,
                                                      String bearer, byte[] body, long deadline,
                                                      SkillHttpsTransport.Admission admission) throws IOException {
        return switch (method) {
            case POST -> SkillHttpsTransport.post(endpoint, bearer, body, deadline, MAX_JSON_BYTES, admission);
            case DELETE -> SkillHttpsTransport.delete(endpoint, bearer, deadline, MAX_JSON_BYTES, admission);
            case GET -> media ? SkillHttpsTransport.download(endpoint, bearer, deadline, MAX_MEDIA_BYTES, admission)
                    : SkillHttpsTransport.get(endpoint, bearer, deadline, MAX_JSON_BYTES, admission);
        };
    }

    private Entry finish(Scope scope, Entry expected, State state, String remoteId, long poll, String media) throws Failure {
        return finish(scope, expected, state, remoteId, poll, media, expected.routes());
    }

    private Entry finish(Scope scope, Entry expected, State state, String remoteId, long poll, String media, Routes routes)
            throws Failure {
        return locked(scope, (file, catalog) -> {
            Entry current = sameClaim(expected, catalog);
            if (!current.ticket().equals(expected.ticket()) || current.job().state() != expected.job().state()) return current;
            Entry next = current.change(state, remoteId, poll, current.ticket(), media, routes);
            update(file, catalog, next);
            return next;
        });
    }

    private void releasePoll(Scope scope, Entry expected, SkillHttpDeclaration.Video video) throws Failure {
        locked(scope, (file, catalog) -> {
            Entry current = sameClaim(expected, catalog);
            if (current.ticket().equals(expected.ticket()) && current.job().state() == expected.job().state()) {
                update(file, catalog, current.change(current.job().state(), current.remoteId(), nextPoll(current, video),
                        current.ticket(), current.mediaHash()));
            }
            return null;
        });
    }

    private long nextPoll(Entry entry, SkillHttpDeclaration.Video video) {
        int attempts = entry.pollVisits();
        long backoff = attempts <= 6 ? 3_000 : attempts <= 30 ? 12_000 : 30_000;
        // A proven saved result retries media directly, without making another status request.
        boolean mediaOnly = entry.job().state() == State.DOWNLOAD_READY;
        long floor = mediaOnly ? 0 : TimeUnit.SECONDS.toMillis(video.minPollSeconds());
        return clock.getAsLong() + Math.max(backoff, floor);
    }

    private static Entry sameClaim(Entry expected, Catalog catalog) throws Failure {
        Entry current = catalog.jobs().get(expected.job().localId());
        if (current == null) throw new Failure(Code.CLEARED);
        return current;
    }

    private static void requireCurrent(Entry entry, Access access) throws Failure {
        if (access == null || !entry.fingerprint().equals(access.binding().fingerprint())
                || entry.job().cancellationSupported() != access.binding().declaration().video().canCancel()) {
            throw new Failure(Code.STALE);
        }
    }

    private static boolean definiteRefusal(int status) {
        return status == 400 || status == 401 || status == 403 || status == 404 || status == 405
                || status == 413 || status == 415 || status == 422;
    }

    private static void requireRouteOmitsCredential(String endpoint, String bearer) throws Failure {
        if (AiCredentialVault.imageEndpointContainsCredential(endpoint, bearer)) throw new Failure(Code.RESPONSE);
    }

    private static boolean isRelativeSavedPath(String path) {
        if (path.isEmpty()) return true;
        if (path.length() > MAX_SAVED_PATH || !path.startsWith("/") || path.startsWith("//")) return false;
        URI value = URI.create(path);
        return path.equals(value.getRawPath()) && value.equals(value.normalize());
    }

    private static boolean sameOrigin(String approved, String actual) {
        URI from = URI.create(approved);
        URI to = URI.create(actual);
        return "https".equalsIgnoreCase(to.getScheme()) && from.getHost().equalsIgnoreCase(to.getHost())
                && httpsPort(from) == httpsPort(to) && to.getRawUserInfo() == null
                && to.getRawQuery() == null && to.getRawFragment() == null;
    }

    private static int httpsPort(URI endpoint) { return endpoint.getPort() < 0 ? 443 : endpoint.getPort(); }

    private static JSONObject json(SkillHttpsTransport.Response reply) throws Failure {
        if (reply.status() < 200 || reply.status() >= 300 || !"application/json".equals(reply.contentType())
                || reply.body().length == 0 || reply.body().length > MAX_JSON_BYTES) throw new Failure(Code.RESPONSE);
        try {
            String raw = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(reply.body())).toString();
            return SkillAuthoring.readObject(raw, MAX_JSON_BYTES, 512);
        } catch (IOException | IllegalArgumentException malformed) {
            throw new Failure(Code.RESPONSE);
        }
    }

    private <T> T locked(Scope scope, Transaction<T> action) throws Failure { return locked(scope, true, action); }

    private <T> T locked(Scope scope, boolean read, Transaction<T> action) throws Failure {
        synchronized (IO_LOCK) {
            try {
                Files.createDirectories(directory);
                if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.STORAGE);
                Path path = directory.resolve(scope.key() + ".bin");
                try (FileChannel channel = FileChannel.open(directory.resolve("registry.lock"), StandardOpenOption.CREATE,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                     FileLock ignored = channel.lock()) {
                    return action.run(path, read ? load(path) : null);
                }
            } catch (Failure failure) {
                throw failure;
            } catch (IOException | RuntimeException failure) {
                throw new Failure(Code.STORAGE);
            }
        }
    }

    private static Catalog load(Path file) throws IOException {
        Map<UUID, Entry> jobs = new LinkedHashMap<>();
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Catalog(0, jobs);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_METADATA_BYTES) {
            throw new Failure(Code.STORAGE);
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != FORMAT_VERSION) throw new Failure(Code.STORAGE);
            long generation = input.readLong();
            int count = input.readInt();
            if (generation < 0 || count < 0 || count > MAX_JOBS) throw new Failure(Code.STORAGE);
            for (int index = 0; index < count; index++) {
                UUID id = UUID.fromString(input.readUTF());
                Job job = new Job(id, input.readUTF(), input.readUTF(), input.readUTF(), input.readLong(),
                        State.valueOf(input.readUTF()), input.readBoolean(), input.readBoolean());
                String fingerprint = input.readUTF();
                String remoteId = input.readUTF();
                long nextPollAt = input.readLong();
                UUID ticket = UUID.fromString(input.readUTF());
                String mediaHash = input.readUTF();
                int pollVisits = input.readUnsignedByte();
                Routes routes = new Routes(input.readUTF(), input.readUTF());
                Pinned pinned = readPinned(input, job);
                Entry entry = new Entry(job, fingerprint, remoteId, nextPollAt, ticket, mediaHash, pollVisits, pinned, routes);
                if (!uuid(job.skillId()) || !uuid(job.revisionId()) || !uuid(job.configRevision()) || job.createdAtMillis() < 0
                        || entry.nextPollAt() < 0 || !HASH.matcher(entry.fingerprint()).matches()
                        || entry.pollVisits() > MAX_POLL_VISITS
                        || !entry.remoteId().isEmpty() && !entry.remoteId().equals(SkillHttpDeclaration.validateTaskId(entry.remoteId()))
                        || (job.state() == State.COMPLETED || !entry.mediaHash().isEmpty())
                                && !HASH.matcher(entry.mediaHash()).matches()
                        || job.state() != State.SUBMIT_UNKNOWN && job.state() != State.FAILED && entry.remoteId().isEmpty()
                        || (job.state() == State.DOWNLOAD_READY) == routes.resultPath().isEmpty()
                        || job.state().terminal() && !routes.statusPath().isEmpty()
                        || jobs.put(id, entry) != null) throw new Failure(Code.STORAGE);
                validatePinnedEntry(entry);
            }
            if (input.read() != -1) throw new Failure(Code.STORAGE);
            return new Catalog(generation, jobs);
        }
    }

    private static Pinned readPinned(DataInputStream input, Job job) throws IOException {
        if (!input.readBoolean()) return null;
        String endpoint = input.readUTF();
        String encryptedBearer = input.readUTF();
        SkillHttpDeclaration declaration = SkillHttpDeclaration.parse(input.readUTF(), SkillResult.Type.VIDEO, List.of());
        Binding binding = new Binding(job.skillId(), job.revisionId(), job.configRevision(), declaration, endpoint);
        return new Pinned(binding, encryptedBearer);
    }

    private static void validatePinnedEntry(Entry entry) throws Failure {
        if (entry.pinned() == null) return;
        Binding policy = entry.pinned().binding();
        SkillHttpDeclaration.Video video = policy.declaration().video();
        State state = entry.job().state();
        if (!entry.fingerprint().equals(policy.fingerprint()) || entry.job().cancellationSupported() != video.canCancel()
                || state.terminal() && state != State.SUBMIT_UNKNOWN) throw new Failure(Code.STORAGE);
        if (state == State.SUBMIT_UNKNOWN) return;
        video.statusRequest(policy.endpoint(), entry.remoteId(), entry.routes().statusPath());
        if (state == State.DOWNLOAD_READY) video.savedDownloadEndpoint(policy.endpoint(), entry.remoteId(), entry.routes().resultPath());
    }

    private void update(Path file, Catalog catalog, Entry entry) throws IOException {
        catalog.jobs().put(entry.job().localId(), entry);
        write(file, catalog);
    }

    private void write(Path file, Catalog catalog) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            int count = 0;
            try (DirectoryStream<Path> catalogs = Files.newDirectoryStream(directory, "*.bin")) {
                for (Path ignored : catalogs) if (++count >= MAX_SCOPES) throw new Failure(Code.LIMIT);
            }
        }
        atomicWrite(file, stream -> {
            var output = new DataOutputStream(stream);
            output.writeInt(FORMAT_VERSION);
            output.writeLong(catalog.generation());
            output.writeInt(catalog.jobs().size());
            for (Entry entry : catalog.jobs().values()) {
                Job job = entry.job();
                output.writeUTF(job.localId().toString());
                output.writeUTF(job.skillId());
                output.writeUTF(job.revisionId());
                output.writeUTF(job.configRevision());
                output.writeLong(job.createdAtMillis());
                output.writeUTF(job.state().name());
                output.writeBoolean(job.linked());
                output.writeBoolean(job.cancellationSupported());
                output.writeUTF(entry.fingerprint());
                output.writeUTF(entry.remoteId());
                output.writeLong(entry.nextPollAt());
                output.writeUTF(entry.ticket().toString());
                output.writeUTF(entry.mediaHash());
                output.writeByte(entry.pollVisits());
                output.writeUTF(entry.routes().statusPath());
                output.writeUTF(entry.routes().resultPath());
                Pinned pinned = entry.pinned();
                output.writeBoolean(pinned != null);
                if (pinned != null) {
                    output.writeUTF(pinned.binding().endpoint());
                    output.writeUTF(pinned.encryptedBearer());
                    output.writeUTF(pinned.binding().declaration().toJson().toString());
                }
            }
            output.flush();
        });
    }

    @FunctionalInterface private interface FileWriter { void write(FileOutputStream stream) throws IOException; }

    private void atomicWrite(Path file, FileWriter writer) throws IOException {
        Path staged = directory.resolve("write.staged");
        try {
            Files.deleteIfExists(staged);
            try (FileOutputStream stream = new FileOutputStream(staged.toFile())) {
                writer.write(stream);
                stream.getFD().sync();
            }
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private void requireMediaSpace(long incoming) throws IOException {
        long remaining = TOTAL_MEDIA_BYTES - incoming;
        try (DirectoryStream<Path> clips = Files.newDirectoryStream(directory, "*.mp4")) {
            for (Path clip : clips) {
                if (!Files.isRegularFile(clip, LinkOption.NOFOLLOW_LINKS)) throw new Failure(Code.STORAGE);
                remaining -= Files.size(clip);
                if (remaining < 0) throw new Failure(Code.LIMIT);
            }
        }
    }

    private Path clip(Scope scope, UUID id) { return directory.resolve(scope.key() + "-" + id + ".mp4"); }

    private static String localHash(Path file) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return "";
        long size = Files.size(file);
        if (size <= 0 || size > MAX_MEDIA_BYTES) return "";
        MessageDigest digest = sha256();
        try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] chunk = new byte[8192];
            int read;
            long limit = size;
            while ((read = input.read(chunk)) != -1) {
                limit -= read;
                if (limit < 0) return "";
                digest.update(chunk, 0, read);
            }
            return limit == 0 ? hash(digest) : "";
        }
    }

    private static String hash(MessageDigest digest) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException unsupported) { throw new IllegalStateException(unsupported); }
    }
    private static boolean uuid(String id) {
        try { return id != null && UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    /** Minimal ISO BMFF validation before writing bytes to a playable file. */
    static boolean validMp4(byte[] bytes) {
        if (bytes == null || bytes.length < 36 || bytes.length > MAX_MEDIA_BYTES) return false;
        try {
            Mp4 boxes = new Mp4(bytes);
            Box first = boxes.next(0, bytes.length);
            if (!first.is("ftyp") || first.size() < 8 || first.size() % 4 != 0) return false;
            boolean video = false;
            boolean movie = false;
            boolean data = false;
            for (int offset = first.end(); offset < bytes.length;) {
                Box box = boxes.next(offset, bytes.length);
                if (box.is("moov")) {
                    if (movie) return false;
                    movie = true;
                    video = boxes.movieHasVideo(box);
                }
                if (box.is("mdat") && box.size() > 0) data = true;
                offset = box.end();
            }
            return data && video;
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private record Box(String type, int content, int end) {
        boolean is(String expected) { return type.equals(expected); }
        int size() { return end - content; }
    }

    private static final class Mp4 {
        private final ByteBuffer data;
        private int remainingBoxes = 512;
        private Mp4(byte[] bytes) { data = ByteBuffer.wrap(bytes); }

        Box next(int offset, int end) {
            if (--remainingBoxes < 0 || end - offset < 8) throw new IllegalArgumentException();
            long size = data.getInt(offset) & 0xffffffffL;
            int header = 8;
            if (size == 1) {
                if (end - offset < 16) throw new IllegalArgumentException();
                size = data.getLong(offset + 8);
                header = 16;
            } else if (size == 0) size = end - offset;
            if (size < header || size > end - offset) throw new IllegalArgumentException();
            String type = new String(data.array(), offset + 4, 4, StandardCharsets.ISO_8859_1);
            return new Box(type, offset + header, offset + (int) size);
        }

        boolean movieHasVideo(Box movie) {
            boolean found = false;
            for (int offset = movie.content(); offset < movie.end();) {
                Box track = next(offset, movie.end());
                if (track.is("trak")) {
                    Box media = child(track, "mdia");
                    Box handler = child(media, "hdlr");
                    if (handler.size() < 12) throw new IllegalArgumentException();
                    if (data.getInt(handler.content() + 8) == 0x76696465) found = true; // vide
                    Box references = child(child(child(media, "minf"), "dinf"), "dref");
                    if (references.size() < 8) throw new IllegalArgumentException();
                    int count = data.getInt(references.content() + 4);
                    int cursor = references.content() + 8;
                    if (count < 1 || count > 16) throw new IllegalArgumentException();
                    for (int index = 0; index < count; index++) {
                        Box reference = next(cursor, references.end());
                        if (!reference.is("url ") || reference.size() != 4 || data.getInt(reference.content()) != 1) {
                            throw new IllegalArgumentException();
                        }
                        cursor = reference.end();
                    }
                    if (cursor != references.end()) throw new IllegalArgumentException();
                }
                offset = track.end();
            }
            return found;
        }

        private Box child(Box parent, String type) {
            Box found = null;
            for (int offset = parent.content(); offset < parent.end();) {
                Box child = next(offset, parent.end());
                if (child.is(type)) {
                    if (found != null) throw new IllegalArgumentException();
                    found = child;
                }
                offset = child.end();
            }
            if (found == null) throw new IllegalArgumentException();
            return found;
        }
    }
}
