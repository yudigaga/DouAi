package com.douai.module.feature;

import android.content.Context;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillTable;
import com.douai.module.config.AiModelConfig;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/** Bounded private test-chat history. Epochs prevent an older request from undoing Clear. */
public final class TestChatMemory {
    public static final int MAX_IMAGE_BYTES = 12 * 1024 * 1024;
    private static final int LEGACY_VERSION = 1;
    private static final int MIXED_VERSION = 2;
    private static final int ARTIFACT_VERSION = 3;
    private static final int MODEL_TURN = 0;
    private static final int DISPLAY_TURN = 1;
    private static final int MAX_ROUNDS = 50;
    private static final int MAX_SETTINGS_NAME = 24;
    private static final int MAX_LEGACY_FILE_BYTES = 20 * 1024 * 1024;
    private static final int MAX_MIXED_FILE_BYTES = 32 * 1024 * 1024;
    private static final int MAX_DIRECTORY_BYTES = 48 * 1024 * 1024;
    private static final int MAX_ARTIFACT_PAYLOAD = 48 * 1024;
    private static final int MAX_ARTIFACT_BYTES = 1024 * 1024;
    private static final ConcurrentHashMap<String, State> STATES = new ConcurrentHashMap<>();
    private static final Object IO_LOCK = new Object();
    private final File file;
    private final State state;
    private final int rounds;

    public TestChatMemory(Context context, AiModelConfig config) {
        this(new File(context.getFilesDir(), "douai_test_chat"), config);
    }

    /** Message-page conversations have their own history; test requests never enter it. */
    public static TestChatMemory messageConversation(Context context, AiModelConfig config) {
        return new TestChatMemory(new File(context.getFilesDir(), "douai_message_chat"), config);
    }

    TestChatMemory(File directory, AiModelConfig config) {
        rounds = Math.max(0, Math.min(MAX_ROUNDS, config.contextRounds));
        file = new File(directory, key(config) + ".bin");
        state = STATES.computeIfAbsent(file.getAbsolutePath(), ignored -> new State());
        if (rounds == 0) clear();
    }

    public long epoch() { synchronized (IO_LOCK) { return state.epoch; } }

    public boolean historyEnabled() { return rounds > 0; }

    /** Ordinary model context, excluding all local-only Skill turns. */
    public List<Turn> read() { return readStored(false); }

    /** Visible local transcript, including Skill turns in their original order. */
    public List<Turn> readTranscript() { return readStored(true); }

    private List<Turn> readStored(boolean includeDisplay) {
        synchronized (IO_LOCK) {
            if (rounds == 0) return Collections.emptyList();
            try {
                long fileBytes = file.length();
                if (fileBytes > MAX_MIXED_FILE_BYTES) throw new IOException("History size");
                try (DataInputStream input = new DataInputStream(new java.io.FileInputStream(file))) {
                    int version = input.readInt();
                    boolean artifacts = version == ARTIFACT_VERSION;
                    boolean mixed = version == MIXED_VERSION || artifacts;
                    if (!mixed && version != LEGACY_VERSION) throw new IOException("History version");
                    if (!mixed && fileBytes > MAX_LEGACY_FILE_BYTES) throw new IOException("History size");
                    int count = input.readInt();
                    if (count < 0 || count > (mixed ? 2 * MAX_ROUNDS : MAX_ROUNDS)) throw new IOException("History count");
                    List<Turn> result = new ArrayList<>();
                    int[] bytesByKind = new int[2];
                    int[] turnsByKind = new int[2];
                    int artifactBytes = 0;
                    for (int index = 0; index < count; index++) {
                        int kind = mixed ? input.readUnsignedByte() : MODEL_TURN;
                        if ((kind != MODEL_TURN && kind != DISPLAY_TURN) || ++turnsByKind[kind] > MAX_ROUNDS) {
                            throw new IOException("History turn");
                        }
                        String prompt = text(input, 16000);
                        String document = kind == MODEL_TURN ? text(input, 131072) : "";
                        String answer = text(input, 16000);
                        String settings = kind == DISPLAY_TURN ? text(input, MAX_SETTINGS_NAME * 4) : null;
                        boolean keep = kind == MODEL_TURN || includeDisplay;
                        List<ChatImage> attachments = readImages(input, bytesByKind, kind, keep);
                        SkillResult artifact = null;
                        if (artifacts && kind == DISPLAY_TURN) {
                            String type = text(input, 12);
                            if (!type.isEmpty()) {
                                String payload = text(input, MAX_ARTIFACT_PAYLOAD);
                                artifactBytes += utf8Length(payload);
                                if (artifactBytes > MAX_ARTIFACT_BYTES) throw new IOException("History artifact size");
                                if (includeDisplay) artifact = readArtifact(type, payload);
                            }
                        }
                        if (kind == MODEL_TURN) result.add(new Turn(prompt, document, attachments, answer));
                        else if (includeDisplay) result.add(Turn.displayedSkill(prompt, answer, attachments, settings, artifact));
                    }
                    if (mixed && input.read() != -1) throw new IOException("History trailing bytes");
                    return bounded(result);
                }
            } catch (java.io.FileNotFoundException ignored) { return Collections.emptyList(); }
            catch (IOException | RuntimeException failure) {
                file.delete(); return Collections.emptyList();
            }
        }
    }

    private static List<ChatImage> readImages(DataInputStream input, int[] bytesByKind, int kind, boolean keep)
            throws IOException {
        int count = input.readInt();
        if (count < 0 || count > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) throw new IOException("Images");
        List<ChatImage> images = keep ? new ArrayList<>() : Collections.emptyList();
        byte[] scratch = null;
        for (int index = 0; index < count; index++) {
            String mime = text(input, 100);
            int size = input.readInt();
            if (size <= 0 || size > ChatImage.MAX_BYTES || bytesByKind[kind] + size > MAX_IMAGE_BYTES) {
                throw new IOException("Image size");
            }
            bytesByKind[kind] += size;
            if (keep) {
                byte[] data = new byte[size];
                input.readFully(data);
                images.add(new ChatImage(mime, data));
            } else {
                if (scratch == null) scratch = new byte[8192];
                // Read through the record without allocating or decoding its assistant image.
                for (int remaining = size; remaining > 0;) {
                    int chunk = Math.min(remaining, scratch.length);
                    input.readFully(scratch, 0, chunk);
                    remaining -= chunk;
                }
            }
        }
        return images;
    }

    public boolean append(long expectedEpoch, Turn turn) {
        synchronized (IO_LOCK) {
            if (rounds == 0 || expectedEpoch != state.epoch) return false;
            List<Turn> turns = new ArrayList<>(readTranscript());
            turns.add(turn);
            turns = bounded(turns);
            if (turn.displayOnly && !turns.contains(turn)) return false;
            boolean mixed = turns.stream().anyMatch(saved -> saved.displayOnly);
            boolean artifacts = turns.stream().anyMatch(saved -> saved.artifact != null);
            File directory = file.getParentFile();
            if (!directory.isDirectory() && !directory.mkdirs()) return false;
            File temporary = null;
            try {
                temporary = File.createTempFile("memory-", ".tmp", directory);
                try (FileOutputStream stream = new FileOutputStream(temporary);
                     DataOutputStream output = new DataOutputStream(stream)) {
                    output.writeInt(artifacts ? ARTIFACT_VERSION : mixed ? MIXED_VERSION : LEGACY_VERSION);
                    output.writeInt(turns.size());
                    for (Turn saved : turns) {
                        if (mixed) output.writeByte(saved.displayOnly ? DISPLAY_TURN : MODEL_TURN);
                        text(output, saved.prompt);
                        if (!saved.displayOnly) text(output, saved.document);
                        text(output, saved.answer);
                        if (saved.displayOnly) text(output, saved.settingsSkillName == null ? "" : saved.settingsSkillName);
                        writeImages(output, saved.displayOnly ? saved.assistantImages : saved.images);
                        if (artifacts && saved.displayOnly) {
                            text(output, saved.artifact == null ? "" : saved.artifact.type().wireName());
                            if (saved.artifact != null) text(output, artifactPayload(saved.artifact));
                        }
                    }
                    output.flush();
                    stream.getFD().sync();
                }
                try {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE,
                            StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException unsupported) {
                    Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }
                evictOtherFiles(directory);
                return true;
            } catch (IOException | RuntimeException failure) {
                return false;
            } finally { if (temporary != null) temporary.delete(); }
        }
    }

    private static void writeImages(DataOutputStream output, List<ChatImage> images) throws IOException {
        output.writeInt(images.size());
        for (ChatImage image : images) {
            text(output, image.mimeType);
            byte[] data = Base64.getDecoder().decode(image.base64Data);
            output.writeInt(data.length);
            output.write(data);
        }
    }

    public void clear() {
        synchronized (IO_LOCK) { state.epoch++; file.delete(); }
    }

    private List<Turn> bounded(List<Turn> turns) {
        int[] bytesByKind = new int[2];
        int[] turnsByKind = new int[2];
        List<Turn> retained = new ArrayList<>();
        int artifactBytes = 0;
        for (int index = turns.size() - 1; index >= 0 && retained.size() < rounds * 2; index--) {
            Turn turn = turns.get(index);
            int kind = turn.displayOnly ? DISPLAY_TURN : MODEL_TURN;
            if (turnsByKind[kind] >= rounds) continue;
            List<ChatImage> images = turn.displayOnly ? turn.assistantImages : turn.images;
            if (images.size() > ChatCompletionClient.MAX_IMAGES_PER_MESSAGE) continue;
            int imageBytes = 0;
            for (ChatImage image : images) imageBytes += image.sizeBytes();
            if (bytesByKind[kind] + imageBytes > MAX_IMAGE_BYTES) continue;
            if (turn.prompt.length() > 4000 || turn.document.length() > 32768 || turn.answer.length() > 4000) continue;
            if (turn.settingsSkillName != null && turn.settingsSkillName.length() > MAX_SETTINGS_NAME) continue;
            if (turn.artifact != null) {
                int size = utf8Length(artifactPayload(turn.artifact));
                if (size > MAX_ARTIFACT_PAYLOAD || artifactBytes + size > MAX_ARTIFACT_BYTES) continue;
                artifactBytes += size;
            }
            turnsByKind[kind]++;
            bytesByKind[kind] += imageBytes;
            retained.add(0, turn);
        }
        return Collections.unmodifiableList(retained);
    }

    private static SkillResult readArtifact(String type, String payload) {
        if (SkillResult.Type.TABLE.wireName().equals(type)) {
            try { return new SkillResult.Table(SkillTable.parse(payload)); }
            catch (IllegalArgumentException invalid) { return null; }
        }
        return null;
    }

    private static String artifactPayload(SkillResult artifact) {
        if (artifact instanceof SkillResult.Table table) return table.table().toJson();
        throw new IllegalArgumentException("Unsupported history artifact");
    }

    private static int utf8Length(String text) { return text.getBytes(StandardCharsets.UTF_8).length; }

    private void evictOtherFiles(File directory) {
        File[] files = directory.listFiles((parent, name) -> name.endsWith(".bin"));
        if (files == null) return;
        java.util.Arrays.sort(files, java.util.Comparator.comparingLong(File::lastModified));
        long bytes = 0;
        for (File candidate : files) bytes += candidate.length();
        for (File candidate : files) {
            if (bytes <= MAX_DIRECTORY_BYTES) break;
            if (candidate.equals(file)) continue;
            STATES.computeIfAbsent(candidate.getAbsolutePath(), ignored -> new State()).epoch++;
            bytes -= candidate.length();
            candidate.delete();
        }
    }

    private static String key(AiModelConfig config) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : new String[]{config.id, config.protocol, config.apiEndpoint, config.modelName, config.systemPrompt}) {
                byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array());
                digest.update(bytes);
            }
            StringBuilder key = new StringBuilder();
            for (byte value : digest.digest()) key.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
            return key.toString();
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    private static String text(DataInputStream input, int maximum) throws IOException {
        int size = input.readInt();
        if (size < 0 || size > maximum) throw new IOException("Text size");
        byte[] bytes = new byte[size];
        input.readFully(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static void text(DataOutputStream output, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        output.writeInt(bytes.length);
        output.write(bytes);
    }

    private static final class State { long epoch; }

    public static final class Turn {
        public final String prompt;
        public final String document;
        public final List<ChatImage> images;
        public final String answer;
        public final boolean displayOnly;
        public final List<ChatImage> assistantImages;
        public final String settingsSkillName;
        public final SkillResult artifact;

        public Turn(String prompt, String document, List<ChatImage> images, String answer) {
            this(prompt, document, images, answer, false, Collections.emptyList(), null, null);
        }

        public static Turn displayedSkill(String prompt, String answer, List<ChatImage> assistantImages,
                                          String settingsSkillName) {
            return displayedSkill(prompt, answer, assistantImages, settingsSkillName, null);
        }

        public static Turn displayedSkill(String prompt, String answer, List<ChatImage> assistantImages,
                                          String settingsSkillName, SkillResult artifact) {
            return new Turn(prompt, "", Collections.emptyList(), answer, true, assistantImages, settingsSkillName, artifact);
        }

        private Turn(String prompt, String document, List<ChatImage> images, String answer, boolean displayOnly,
                     List<ChatImage> assistantImages, String settingsSkillName, SkillResult artifact) {
            this.prompt = prompt;
            this.document = document;
            this.images = Collections.unmodifiableList(new ArrayList<>(images));
            this.answer = answer;
            this.displayOnly = displayOnly;
            this.assistantImages = Collections.unmodifiableList(new ArrayList<>(assistantImages));
            this.settingsSkillName = settingsSkillName == null || settingsSkillName.isEmpty() ? null : settingsSkillName;
            this.artifact = artifact;
        }
    }
}
