package com.douai.module.ai.skills;

import android.content.Context;
import android.content.pm.PackageManager;

import com.douai.module.BuildConfig;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.LongSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Local simulated video operations. The caller is responsible for polling within its conversation. */
public final class SkillVideoTasks {
    private static final String ASSET = "douai_skill_video_fixture.mp4";
    private static final String LOCAL_ERROR = "无法读取或保存本机视频任务";
    private static final int FORMAT_VERSION = 1;
    private static final int MAX_TASKS = 40;
    private static final int MAX_SCOPES = 256;
    private static final int MAX_METADATA_BYTES = 16 * 1024;
    private static final int MAX_FIXTURE_BYTES = 128 * 1024;
    private static final Object PROCESS_LOCK = new Object();
    private static final Pattern CONVERSATION_KEY = Pattern.compile("[0-9a-f]{64}");
    private static final Pattern SLASH_COMMAND = Pattern.compile("^/[\\p{L}\\p{N}_-]{1,24}(?=\\s|$)");
    private static final Timing DEMO_TIMING = new Timing(650, 2_600, 30_000);

    public record Scope(AiSkills.Entry entry, String conversationKey) {
        public Scope {
            Objects.requireNonNull(entry, "entry");
            if (conversationKey == null || !CONVERSATION_KEY.matcher(conversationKey).matches()) {
                throw new IllegalArgumentException("会话标识无效");
            }
        }
    }

    public enum State {
        QUEUED, RUNNING, COMPLETED, CANCELLED, TIMED_OUT, FAILED;

        public boolean terminal() { return this != QUEUED && this != RUNNING; }
    }

    /** prompt is only the slash command label used when recovering a missing transcript row. */
    public record Task(UUID requestId, UUID operationId, String prompt, long createdAtMillis, State state,
                       boolean linked) {}

    record Timing(long runningMillis, long completionMillis, long timeoutMillis) {}
    @FunctionalInterface interface Fixture { InputStream open() throws IOException; }

    private record StoredTask(Task task, long runningAt, long completionAt, long deadline) {
        StoredTask withState(State state) {
            return new StoredTask(new Task(task.requestId(), task.operationId(), task.prompt(),
                    task.createdAtMillis(), state, task.linked()), runningAt, completionAt, deadline);
        }

        StoredTask linked() {
            return new StoredTask(new Task(task.requestId(), task.operationId(), task.prompt(),
                    task.createdAtMillis(), task.state(), true), runningAt, completionAt, deadline);
        }

        StoredTask advance(long now) {
            if (task.state().terminal()) return this;
            if (completionAt <= deadline && now >= completionAt) return withState(State.COMPLETED);
            if (now >= deadline) return withState(State.TIMED_OUT);
            if (task.state() == State.QUEUED && now >= runningAt) return withState(State.RUNNING);
            return this;
        }
    }

    private record Catalog(long generation, Map<UUID, StoredTask> tasks) {}
    @FunctionalInterface private interface Transaction<T> { T run(Path file) throws IOException; }

    private final Path directory;
    private final LongSupplier clock;
    private final Timing timing;
    private final Fixture fixture;

    public static SkillVideoTasks forContext(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return new SkillVideoTasks(new File(owner.getFilesDir(), "douai_skill_video_tasks"),
                System::currentTimeMillis, DEMO_TIMING, () -> {
                    try {
                        Context source = BuildConfig.APPLICATION_ID.equals(owner.getPackageName())
                                ? owner : owner.createPackageContext(BuildConfig.APPLICATION_ID,
                                        Context.CONTEXT_IGNORE_SECURITY);
                        return source.getAssets().open(ASSET);
                    } catch (PackageManager.NameNotFoundException unavailable) {
                        throw new IOException("本机模拟视频无法读取");
                    }
                });
    }

    SkillVideoTasks(File directory, LongSupplier clock, Timing timing, Fixture fixture) {
        this.directory = directory.toPath();
        this.clock = clock;
        this.timing = timing;
        this.fixture = fixture;
    }

    public long generation(Scope scope) throws IOException {
        return locked(scope, file -> read(file).generation());
    }

    public boolean hasCatalog(Scope scope) {
        return Files.isRegularFile(scopeFile(scope), LinkOption.NOFOLLOW_LINKS);
    }

    public Task submit(Scope scope, long expectedGeneration, UUID requestId, String prompt) throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        return locked(scope, file -> {
            Catalog catalog = read(file);
            if (catalog.generation() != expectedGeneration) throw new IOException("会话已清空，视频任务未提交");
            StoredTask existing = catalog.tasks().get(requestId);
            if (existing != null) return update(file, catalog, existing, false);
            if (catalog.tasks().size() >= MAX_TASKS) throw new IOException("此会话的视频任务已达上限，请清空历史后再试");
            long now = clock.getAsLong();
            Task task = new Task(requestId, UUID.randomUUID(), commandOnly(prompt), now, State.QUEUED, false);
            StoredTask stored = new StoredTask(task, now + timing.runningMillis(), now + timing.completionMillis(),
                    now + timing.timeoutMillis());
            catalog.tasks().put(requestId, stored);
            write(file, catalog);
            return task;
        });
    }

    public Task refresh(Scope scope, UUID requestId) throws IOException {
        return change(scope, requestId, false);
    }

    public Task cancel(Scope scope, UUID requestId) throws IOException {
        return change(scope, requestId, true);
    }

    /** Records that the transcript already accepted this operation, even if its row is later evicted. */
    public void markLinked(Scope scope, UUID requestId) throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        locked(scope, file -> {
            Catalog catalog = read(file);
            StoredTask current = catalog.tasks().get(requestId);
            if (current != null && !current.task().linked()) {
                catalog.tasks().put(requestId, current.linked());
                write(file, catalog);
            }
            return null;
        });
    }

    private Task change(Scope scope, UUID requestId, boolean cancel) throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        return locked(scope, file -> {
            Catalog catalog = read(file);
            return update(file, catalog, catalog.tasks().get(requestId), cancel);
        });
    }

    public List<Task> list(Scope scope) throws IOException {
        return locked(scope, file -> {
            Catalog catalog = read(file);
            long now = clock.getAsLong();
            boolean changed = false;
            List<Task> tasks = new ArrayList<>(catalog.tasks().size());
            for (Map.Entry<UUID, StoredTask> entry : catalog.tasks().entrySet()) {
                StoredTask next = advance(entry.getValue(), now);
                if (next != entry.getValue()) {
                    entry.setValue(next);
                    changed = true;
                }
                tasks.add(next.task());
            }
            if (changed) write(file, catalog);
            return Collections.unmodifiableList(tasks);
        });
    }

    /** Invalidates the admission generation even if a previous interrupted write was unreadable. */
    public void clear(Scope scope) throws IOException {
        locked(scope, file -> {
            write(file, new Catalog(ThreadLocalRandom.current().nextLong(1, Long.MAX_VALUE), Map.of()));
            return null;
        });
    }

    public File playableFile(Scope scope, UUID requestId) throws IOException {
        Objects.requireNonNull(requestId, "requestId");
        return locked(scope, file -> {
            Catalog catalog = read(file);
            Task task = update(file, catalog, catalog.tasks().get(requestId), false);
            return task != null && task.state() == State.COMPLETED ? localFixture() : null;
        });
    }

    private Task update(Path file, Catalog catalog, StoredTask old, boolean cancel) throws IOException {
        if (old == null) return null;
        StoredTask next = advance(old, clock.getAsLong());
        if (cancel && !next.task().state().terminal()) next = next.withState(State.CANCELLED);
        if (next != old) {
            catalog.tasks().put(old.task().requestId(), next);
            write(file, catalog);
        }
        return next.task();
    }

    private StoredTask advance(StoredTask old, long now) {
        StoredTask next = old.advance(now);
        if (next != old && next.task().state() == State.COMPLETED) {
            try { localFixture(); }
            catch (IOException unavailable) { return next.withState(State.FAILED); }
        }
        return next;
    }

    private Path scopeFile(Scope scope) {
        Objects.requireNonNull(scope, "scope");
        return directory.resolve(scope.entry().name().toLowerCase(Locale.ROOT)
                + "-" + scope.conversationKey() + ".bin");
    }

    private <T> T locked(Scope scope, Transaction<T> operation) throws IOException {
        Path file = scopeFile(scope);
        synchronized (PROCESS_LOCK) {
            Files.createDirectories(directory);
            if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException(LOCAL_ERROR);
            Path lockPath = directory.resolve("registry.lock");
            try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = channel.lock()) {
                return operation.run(file);
            }
        }
    }

    private static Catalog read(Path file) throws IOException {
        Map<UUID, StoredTask> tasks = new LinkedHashMap<>();
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) return new Catalog(0, tasks);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_METADATA_BYTES) {
            throw new IOException(LOCAL_ERROR);
        }
        try (DataInputStream input = new DataInputStream(Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS))) {
            if (input.readInt() != FORMAT_VERSION) throw new IOException(LOCAL_ERROR);
            long generation = input.readLong();
            int count = input.readInt();
            if (generation < 0 || count < 0 || count > MAX_TASKS) throw new IOException(LOCAL_ERROR);
            for (int index = 0; index < count; index++) {
                UUID requestId = readId(input);
                UUID operationId = readId(input);
                String prompt = input.readUTF();
                long createdAt = input.readLong();
                State state = State.valueOf(input.readUTF());
                boolean linked = input.readBoolean();
                long runningAt = input.readLong();
                long completionAt = input.readLong();
                long deadline = input.readLong();
                if (!SLASH_COMMAND.matcher(prompt).matches() || createdAt < 0 || runningAt < createdAt
                        || completionAt < runningAt || deadline < runningAt) throw new IOException(LOCAL_ERROR);
                StoredTask task = new StoredTask(new Task(requestId, operationId, prompt, createdAt, state, linked),
                        runningAt, completionAt, deadline);
                if (tasks.put(requestId, task) != null) throw new IOException(LOCAL_ERROR);
            }
            if (input.read() != -1) throw new IOException(LOCAL_ERROR);
            return new Catalog(generation, tasks);
        } catch (IllegalArgumentException | IOException corrupt) {
            throw new IOException(LOCAL_ERROR);
        }
    }

    private void write(Path file, Catalog catalog) throws IOException {
        if (Files.notExists(file, LinkOption.NOFOLLOW_LINKS)) {
            int matches = 0;
            try (DirectoryStream<Path> scopes = Files.newDirectoryStream(directory, "*.bin")) {
                for (Path unused : scopes) {
                    if (++matches >= MAX_SCOPES) throw new IOException("本机视频会话已达上限");
                }
            }
        }
        Path staged = directory.resolve("registry.staged");
        try {
            Files.deleteIfExists(staged);
            try (FileOutputStream stream = new FileOutputStream(staged.toFile());
                 DataOutputStream output = new DataOutputStream(stream)) {
                output.writeInt(FORMAT_VERSION);
                output.writeLong(catalog.generation());
                output.writeInt(catalog.tasks().size());
                for (StoredTask record : catalog.tasks().values()) {
                    Task task = record.task();
                    writeId(output, task.requestId());
                    writeId(output, task.operationId());
                    output.writeUTF(task.prompt());
                    output.writeLong(task.createdAtMillis());
                    output.writeUTF(task.state().name());
                    output.writeBoolean(task.linked());
                    output.writeLong(record.runningAt());
                    output.writeLong(record.completionAt());
                    output.writeLong(record.deadline());
                }
                output.flush();
                stream.getFD().sync();
            }
            if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("本机视频任务保存已停止");
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private File localFixture() throws IOException {
        Path file = directory.resolve(ASSET);
        if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) && Files.size(file) <= MAX_FIXTURE_BYTES) {
            try (InputStream input = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
                if (hasMp4Header(readUpTo(input, 12))) return file.toFile();
            }
        }
        byte[] data;
        try (InputStream input = fixture.open()) {
            data = readUpTo(input, MAX_FIXTURE_BYTES + 1);
        }
        if (data.length > MAX_FIXTURE_BYTES || !hasMp4Header(data)) throw new IOException("本机模拟视频文件无效");
        Path staged = directory.resolve("fixture.staged");
        try {
            Files.deleteIfExists(staged);
            Files.write(staged, data);
            Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            return file.toFile();
        } finally {
            Files.deleteIfExists(staged);
        }
    }

    private static boolean hasMp4Header(byte[] data) {
        return data.length >= 12 && data[4] == 'f' && data[5] == 't' && data[6] == 'y' && data[7] == 'p';
    }

    private static byte[] readUpTo(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[Math.min(4096, limit)];
        int read;
        while (bytes.size() < limit && (read = input.read(buffer, 0, Math.min(buffer.length, limit - bytes.size()))) != -1) {
            bytes.write(buffer, 0, read);
        }
        return bytes.toByteArray();
    }

    private static String commandOnly(String prompt) throws IOException {
        Matcher match = SLASH_COMMAND.matcher(prompt == null ? "" : prompt.trim());
        if (!match.find()) throw new IOException("视频任务需要明确的技能命令");
        return match.group();
    }

    private static UUID readId(DataInputStream input) throws IOException {
        return new UUID(input.readLong(), input.readLong());
    }

    private static void writeId(DataOutputStream output, UUID value) throws IOException {
        output.writeLong(value.getMostSignificantBits());
        output.writeLong(value.getLeastSignificantBits());
    }
}
