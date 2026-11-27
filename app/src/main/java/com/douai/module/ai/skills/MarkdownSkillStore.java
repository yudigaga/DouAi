package com.douai.module.ai.skills;

import android.content.Context;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/** Private Markdown packages. Only the selected Markdown document is supplied for invocation. */
public final class MarkdownSkillStore {
    public static final int MAX_FILES = 128;
    public static final int MAX_INPUT_BYTES = 2 * 1024 * 1024;
    public static final int MAX_FILE_BYTES = 256 * 1024;
    public static final int MAX_MARKDOWN_BYTES = 64 * 1024;
    public static final int MAX_PATH_DEPTH = 8;
    private static final int MAX_PACKAGES = 50;
    private static final int MAX_INDEX_BYTES = 72 * 1024;
    private static final int MAX_ZIP_ENTRIES = 256;
    private static final int INDEX_VERSION = 1;
    private static final String DOCUMENT = "SKILL.md";
    private static final String META = ".douai-package";
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}_-]{0,47}");
    private static final Pattern SEGMENT = Pattern.compile("[\\p{L}\\p{N}_][\\p{L}\\p{N} _.()-]{0,79}");
    private static final Pattern WINDOWS_RESERVED = Pattern.compile("(?i)(?:con|prn|aux|nul|com[1-9]|lpt[1-9])(?:\\..*)?");
    private static final Pattern SECRET_NAME = Pattern.compile("(?i)(?:credentials?|id_rsa|id_ed25519|id_ecdsa)(?:\\..*)?|.*\\.(?:pem|key|pfx|p12|kdbx)");
    private static final Pattern METADATA = Pattern.compile("(?i)^(name|description)\\s*:\\s*(.*)$");
    private static final Pattern CREDENTIAL = Pattern.compile("(?i)-----BEGIN (?:[A-Z0-9 ]+ )?PRIVATE KEY-----"
            + "|\\b(?:sk-[A-Za-z0-9_-]{20,}|gh[pousr]_[A-Za-z0-9_]{20,}|xox[baprs]-[A-Za-z0-9-]{15,}|AKIA[A-Z0-9]{16})\\b");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?im)(?:^|[^\\p{L}\\p{N}_-])"
            + "(?:[A-Za-z0-9_]+_)?(?:api[ _-]*key|access[ _-]*token|client[ _-]*secret|password|cookie|"
            + "密钥|密码|访问令牌)[\"']?\\s*[:=：]\\s*[\"']?([^\\s\"'`,;}{]+)");
    private static final Pattern BEARER = Pattern.compile("(?i)\\bBearer\\h+([^\\s\"'`,;]+)");
    private static final Pattern BASIC = Pattern.compile("(?i)\\bauthorization[\"']?\\s*[:=]\\s*[\"']?Basic\\h+\\S");
    private static final Pattern PLACEHOLDER = Pattern.compile(
            "\\$(?:[A-Za-z_][A-Za-z0-9_]*|\\{[A-Za-z_][A-Za-z0-9_]*\\})|<[A-Z_][A-Z0-9_]*>");
    private static final Object PROCESS_LOCK = new Object();
    private final Path root;

    public record Description(String name, String description) {}
    public record Entry(String name, String description, boolean enabled, String revision, List<String> files) {
        public Entry { files = Collections.unmodifiableList(new ArrayList<>(files)); }
    }
    public record Selection(Entry entry, String markdown) {
        @Override public String toString() { return "MarkdownSkillStore.Selection"; }
    }

    public static final class Preview {
        private final Description description;
        private final Map<String, String> content;

        private Preview(Description description, Map<String, String> content) {
            this.description = description;
            this.content = Collections.unmodifiableMap(new LinkedHashMap<>(content));
        }

        public Description description() { return description; }
        public List<String> files() { return Collections.unmodifiableList(new ArrayList<>(content.keySet())); }
        public String markdown() { return content.get(DOCUMENT); }
        @Override public String toString() { return "MarkdownSkillStore.Preview"; }
    }

    private record Index(Entry entry, Map<String, String> hashes) {}
    @FunctionalInterface private interface Action<T> { T run() throws IOException; }

    public static MarkdownSkillStore forContext(Context context) {
        Context application = context.getApplicationContext();
        Context owner = application == null ? context : application;
        return new MarkdownSkillStore(new File(owner.getFilesDir(), "douai_markdown_skills"));
    }

    MarkdownSkillStore(File directory) { root = directory.toPath().toAbsolutePath().normalize(); }

    public static Description describe(String markdown) {
        checkedText(markdown, MAX_MARKDOWN_BYTES);
        String[] beginning = markdown.split("\\R", 42);
        int start = 0;
        int end = Math.min(40, beginning.length);
        if (beginning[0].replaceFirst("^\\uFEFF", "").trim().equals("---")) {
            start = 1;
            while (start < end && !beginning[start].trim().equals("---")) start++;
            if (start == end) throw invalid();
            end = start;
            start = 1;
        }
        String name = null;
        String description = null;
        for (int i = start; i < end; i++) {
            Matcher field = METADATA.matcher(beginning[i].trim());
            if (!field.matches()) continue;
            String value = metadataValue(field.group(2).trim());
            if ("name".equalsIgnoreCase(field.group(1))) {
                if (name != null) throw invalid();
                name = value;
            } else {
                if (description != null) throw invalid();
                description = value;
            }
        }
        requireName(name);
        if (description == null || description.isEmpty() || description.length() > 300
                || description.codePoints().anyMatch(Character::isISOControl)) throw invalid();
        checkedText(description, 1200);
        return new Description(name, description);
    }

    public Preview previewZip(InputStream zip) throws IOException {
        if (zip == null) throw invalid();
        byte[] compressed = readBounded(zip, MAX_INPUT_BYTES);
        Map<String, String> content = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        Map<String, Boolean> entries = new LinkedHashMap<>();
        int total = 0;
        try (ZipInputStream input = new ZipInputStream(new ByteArrayInputStream(compressed), StandardCharsets.UTF_8)) {
            for (ZipEntry item; (item = input.getNextEntry()) != null;) {
                boolean directory = item.isDirectory();
                String path = checkedPath(item.getName(), directory);
                if (entries.put(path, directory) != null || entries.size() > MAX_ZIP_ENTRIES) throw invalid();
                registerNodes(nodes, path, directory);
                byte[] payload = readBounded(input, directory ? 0 : Math.min(MAX_FILE_BYTES, MAX_INPUT_BYTES - total));
                if (!directory) {
                    total += payload.length;
                    if (content.size() >= MAX_FILES) throw invalid();
                    content.put(path, decoded(payload, MAX_FILE_BYTES));
                }
                input.closeEntry();
            }
        } catch (java.util.zip.ZipException malformed) { throw invalid(); }
        return makePreview(content);
    }

    public Preview previewDirectory(Map<String, byte[]> files) {
        if (files == null || files.isEmpty() || files.size() > MAX_FILES) throw invalid();
        Map<String, String> content = new LinkedHashMap<>();
        Map<String, String> nodes = new LinkedHashMap<>();
        int total = 0;
        for (Map.Entry<String, byte[]> item : files.entrySet()) {
            String path = checkedPath(item.getKey(), false);
            registerNodes(nodes, path, false);
            byte[] data = item.getValue();
            if (data == null || data.length > MAX_FILE_BYTES || data.length > MAX_INPUT_BYTES - total) throw invalid();
            total += data.length;
            content.put(path, decoded(data, MAX_FILE_BYTES));
        }
        return makePreview(content);
    }

    public Entry installMarkdown(String markdown) throws IOException {
        Description description = describe(markdown);
        return install(new Preview(description, Map.of(DOCUMENT, markdown)));
    }

    public Entry importZip(InputStream zip) throws IOException { return install(previewZip(zip)); }

    public Entry install(Preview preview) throws IOException {
        Objects.requireNonNull(preview);
        return locked(() -> {
            String name = preview.description().name();
            Path target = packagePath(name);
            int packages = 0;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path child : children) {
                    String childName = child.getFileName().toString();
                    if (key(childName).equals(key(name))) throw unavailable();
                    if (NAME.matcher(childName).matches() && ++packages >= MAX_PACKAGES) throw unavailable();
                }
            }
            Path temporary = Files.createTempDirectory(root, ".install-");
            try {
                Map<String, String> hashes = new LinkedHashMap<>();
                for (Map.Entry<String, String> item : preview.content.entrySet()) {
                    Path targetFile = contained(temporary, item.getKey());
                    Files.createDirectories(targetFile.getParent());
                    byte[] bytes = item.getValue().getBytes(StandardCharsets.UTF_8);
                    writeFile(targetFile, bytes);
                    hashes.put(item.getKey(), sha256(bytes));
                }
                Entry saved = new Entry(name, preview.description().description(), false, UUID.randomUUID().toString(), preview.files());
                writeIndex(temporary, new Index(saved, hashes));
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE);
                return saved;
            } finally {
                removeTree(temporary);
            }
        });
    }

    public List<Entry> list() throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return Collections.emptyList();
        return locked(() -> {
            List<Entry> result = new ArrayList<>();
            int inspected = 0;
            try (DirectoryStream<Path> children = Files.newDirectoryStream(root)) {
                for (Path path : children) {
                    String name = path.getFileName().toString();
                    if (!NAME.matcher(name).matches() || !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    if (++inspected > MAX_PACKAGES) throw unavailable();
                    try { result.add(readIndex(path, name).entry()); }
                    catch (IOException | IllegalArgumentException invalid) { /* Keep other approved skills visible. */ }
                }
            }
            result.sort(Comparator.comparing(Entry::name));
            return Collections.unmodifiableList(result);
        });
    }

    public Entry setEnabled(Entry expected, boolean enabled) throws IOException {
        return locked(() -> {
            Index current = requireCurrent(expected);
            Entry previous = current.entry();
            if (previous.enabled() == enabled) return previous;
            Entry changed = new Entry(previous.name(), previous.description(), enabled, UUID.randomUUID().toString(),
                    previous.files());
            writeIndex(packagePath(changed.name()), new Index(changed, current.hashes()));
            return changed;
        });
    }

    public Selection readSelected(Entry expected) throws IOException {
        return locked(() -> {
            Index current = requireCurrent(expected);
            if (!current.entry().enabled()) throw unavailable();
            return new Selection(current.entry(), readApproved(current, DOCUMENT));
        });
    }

    public String readApprovedFile(Entry expected, String path) throws IOException {
        return locked(() -> readApproved(requireCurrent(expected), path));
    }

    public void delete(Entry expected) throws IOException {
        locked(() -> {
            Entry current = requireCurrent(expected).entry();
            Path retired = contained(root, ".retired-" + UUID.randomUUID());
            Files.move(packagePath(current.name()), retired, StandardCopyOption.ATOMIC_MOVE);
            removeTree(retired);
            return null;
        });
    }

    private static Preview makePreview(Map<String, String> files) {
        String markdownPath = null;
        for (String candidate : files.keySet()) {
            String filename = candidate.substring(candidate.lastIndexOf('/') + 1);
            if (!filename.equals(DOCUMENT) && !filename.equals("skill.md")) continue;
            if (markdownPath != null) throw invalid();
            markdownPath = candidate;
        }
        if (markdownPath == null) throw invalid();
        int slash = markdownPath.lastIndexOf('/');
        String prefix = slash < 0 ? "" : markdownPath.substring(0, slash + 1);
        String document = files.get(markdownPath);
        Description description = describe(document);
        Map<String, String> local = new LinkedHashMap<>();
        local.put(DOCUMENT, document);
        for (Map.Entry<String, String> entry : files.entrySet()) {
            String path = entry.getKey();
            if (!path.equals(markdownPath) && path.startsWith(prefix)) {
                local.put(path.substring(prefix.length()), entry.getValue());
            }
        }
        return new Preview(description, local);
    }

    private String readApproved(Index current, String requested) throws IOException {
        String path;
        try { path = checkedPath(requested, false); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
        String expected = current.hashes().get(path);
        if (expected == null) throw unavailable();
        Path directory = packagePath(current.entry().name());
        Path target = contained(directory, path);
        Path parent = target.getParent();
        while (!parent.equals(directory)) {
            if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            parent = parent.getParent();
        }
        byte[] data;
        try (InputStream input = openRegular(target)) {
            data = readBounded(input, DOCUMENT.equals(path) ? MAX_MARKDOWN_BYTES : MAX_FILE_BYTES);
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
        if (!expected.equals(sha256(data))) throw unavailable();
        try { return decoded(data, DOCUMENT.equals(path) ? MAX_MARKDOWN_BYTES : MAX_FILE_BYTES); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
    }

    private Index requireCurrent(Entry expected) throws IOException {
        if (expected == null) throw unavailable();
        Index actual = readIndex(packagePath(expected.name()), expected.name());
        if (!actual.entry().equals(expected)) throw unavailable();
        return actual;
    }

    private Index readIndex(Path directory, String name) throws IOException {
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        try (InputStream file = openRegular(directory.resolve(META))) {
            try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(readBounded(file, MAX_INDEX_BYTES)))) {
                if (input.readInt() != INDEX_VERSION) throw unavailable();
                String indexedName = input.readUTF();
                String description = input.readUTF();
                String revision = input.readUTF();
                boolean enabled = input.readBoolean();
                int count = input.readInt();
                if (!name.equals(indexedName) || !UUID.fromString(revision).toString().equals(revision)
                        || description.isEmpty() || description.length() > 300
                        || description.codePoints().anyMatch(Character::isISOControl)
                        || count < 1 || count > MAX_FILES) throw unavailable();
                checkedText(description, 1200);
                Map<String, String> hashes = new LinkedHashMap<>();
                Map<String, String> nodes = new LinkedHashMap<>();
                for (int index = 0; index < count; index++) {
                    String path = checkedPath(input.readUTF(), false);
                    registerNodes(nodes, path, false);
                    String hash = input.readUTF();
                    if (!hash.matches("[a-f0-9]{64}") || hashes.put(path, hash) != null) throw unavailable();
                }
                if (!hashes.containsKey(DOCUMENT) || input.read() != -1) throw unavailable();
                return new Index(new Entry(name, description, enabled, revision, new ArrayList<>(hashes.keySet())), hashes);
            }
        } catch (IllegalArgumentException invalid) { throw unavailable(); }
    }

    private static void writeIndex(Path directory, Index index) throws IOException {
        Path staged = Files.createTempFile(directory, ".meta-", ".tmp");
        try {
            try (FileOutputStream file = new FileOutputStream(staged.toFile()); DataOutputStream data = new DataOutputStream(file)) {
                Entry entry = index.entry();
                data.writeInt(INDEX_VERSION);
                data.writeUTF(entry.name());
                data.writeUTF(entry.description());
                data.writeUTF(entry.revision());
                data.writeBoolean(entry.enabled());
                data.writeInt(index.hashes().size());
                for (Map.Entry<String, String> item : index.hashes().entrySet()) {
                    data.writeUTF(item.getKey());
                    data.writeUTF(item.getValue());
                }
                data.flush();
                file.getFD().sync();
            }
            Files.move(staged, directory.resolve(META), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(staged); }
    }

    private <T> T locked(Action<T> action) throws IOException {
        synchronized (PROCESS_LOCK) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
            try (FileChannel lockFile = FileChannel.open(root.resolve(".lock"), StandardOpenOption.CREATE,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                 FileLock ignored = lockFile.lock()) {
                return action.run();
            }
        }
    }

    private Path packagePath(String name) throws IOException {
        try { requireName(name); }
        catch (IllegalArgumentException invalid) { throw unavailable(); }
        return contained(root, name);
    }

    private static Path contained(Path directory, String relative) throws IOException {
        Path resolved = directory.resolve(relative).normalize();
        if (resolved.equals(directory) || !resolved.startsWith(directory)) throw unavailable();
        return resolved;
    }

    private static InputStream openRegular(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw unavailable();
        return Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }

    private static void writeFile(Path path, byte[] contents) throws IOException {
        Files.createFile(path);
        try (FileOutputStream stream = new FileOutputStream(path.toFile())) {
            stream.write(contents);
            stream.getFD().sync();
        }
    }

    private void removeTree(Path directory) throws IOException {
        if (!root.equals(directory.getParent()) || !directory.normalize().startsWith(root)) throw unavailable();
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attributes) throws IOException {
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }

            @Override public FileVisitResult postVisitDirectory(Path path, IOException error) throws IOException {
                if (error != null) throw error;
                Files.delete(path);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void registerNodes(Map<String, String> nodes, String path, boolean directory) {
        String[] parts = path.split("/");
        String prefix = "";
        for (int index = 0; index < parts.length; index++) {
            prefix = prefix.isEmpty() ? parts[index] : prefix + "/" + parts[index];
            boolean parent = directory || index < parts.length - 1;
            String identity = prefix + (parent ? "/" : "");
            String prior = nodes.putIfAbsent(key(prefix), identity);
            if (prior != null && (!prior.equals(identity) || !parent)) throw invalid();
        }
    }

    private static String checkedPath(String raw, boolean directory) {
        if (raw == null || raw.isEmpty() || raw.length() > 360 || raw.indexOf('\\') >= 0 || raw.startsWith("/")) throw invalid();
        validateText(raw);
        String path = directory && raw.endsWith("/") ? raw.substring(0, raw.length() - 1) : raw;
        String[] parts = path.split("/", -1);
        if (parts.length > MAX_PATH_DEPTH) throw invalid();
        for (String part : parts) {
            if (!SEGMENT.matcher(part).matches() || part.endsWith(" ") || part.endsWith(".")
                    || WINDOWS_RESERVED.matcher(part).matches() || SECRET_NAME.matcher(part).matches()) throw invalid();
        }
        return path;
    }

    private static void requireName(String name) {
        if (name == null || !NAME.matcher(name).matches() || WINDOWS_RESERVED.matcher(name).matches()
                || SECRET_NAME.matcher(name).matches() || !Normalizer.isNormalized(name, Normalizer.Form.NFC)) throw invalid();
    }

    private static String key(String name) {
        return Normalizer.normalize(name, Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    private static String metadataValue(String value) {
        if (value.length() > 1 && (value.charAt(0) == '"' || value.charAt(0) == '\'')
                && value.charAt(value.length() - 1) == value.charAt(0)) return value.substring(1, value.length() - 1);
        return value;
    }

    private static String decoded(byte[] bytes, int max) {
        if (bytes.length > max) throw invalid();
        try {
            String value = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            validateText(value);
            return value;
        } catch (CharacterCodingException malformed) { throw invalid(); }
    }

    private static void checkedText(String value, int max) {
        if (value == null || value.length() > max || !StandardCharsets.UTF_8.newEncoder().canEncode(value)
                || value.getBytes(StandardCharsets.UTF_8).length > max) throw invalid();
        validateText(value);
    }

    private static void validateText(String value) {
        if (value.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\r' && c != '\n' && c != '\t')) throw invalid();
        if (CREDENTIAL.matcher(value).find() || BASIC.matcher(value).find()
                || containsLiteral(SECRET_ASSIGNMENT.matcher(value)) || containsLiteral(BEARER.matcher(value))) {
            throw new IllegalArgumentException("技能目录不得包含凭据或密钥");
        }
    }

    private static boolean containsLiteral(Matcher matches) {
        while (matches.find()) if (!PLACEHOLDER.matcher(matches.group(1)).matches()) return true;
        return false;
    }

    private static byte[] readBounded(InputStream input, int limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        for (int size; (size = input.read(buffer)) != -1;) {
            if (size == 0 || size > limit - bytes.size()) throw invalid();
            bytes.write(buffer, 0, size);
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] contents) {
        try {
            StringBuilder hex = new StringBuilder(64);
            for (byte value : MessageDigest.getInstance("SHA-256").digest(contents)) {
                hex.append(Character.forDigit((value >>> 4) & 15, 16));
                hex.append(Character.forDigit(value & 15, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("技能目录或文件不符合导入格式"); }
    private static IOException unavailable() { return new IOException("技能已存在、已变化或无法读取"); }
}
