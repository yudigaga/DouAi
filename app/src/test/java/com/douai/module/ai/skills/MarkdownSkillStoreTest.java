package com.douai.module.ai.skills;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public final class MarkdownSkillStoreTest {
    @Rule public final TemporaryFolder temp = new TemporaryFolder();
    private static final String SKILL = """
            ---
            name: "SandboxPackage_DEV"
            description: '供用户查看的普通技能说明'
            ---

            # 操作说明

            仅处理用户选择的文字。
            """;

    @Test public void detectsOperitFrontmatterAndOpensOnlyTheExplicitlyEnabledDocument() throws Exception {
        File root = temp.newFolder();
        MarkdownSkillStore store = new MarkdownSkillStore(root);
        byte[] zip = zip(Map.of("repo-main/demo/SKILL.md", bytes(SKILL),
                "repo-main/demo/scripts/example.js", bytes("const neverRuns = true;"),
                "repo-main/demo/references/usage.md", bytes("# 示例帮助")));
        MarkdownSkillStore.Preview preview = store.previewZip(new ByteArrayInputStream(zip));
        assertEquals("SandboxPackage_DEV", preview.description().name());
        assertEquals("供用户查看的普通技能说明", preview.description().description());
        assertEquals(SKILL, preview.markdown());
        assertTrue(preview.files().contains("scripts/example.js"));
        assertTrue(store.list().isEmpty());

        MarkdownSkillStore.Entry installed = store.install(preview);
        assertFalse(installed.enabled());
        assertEquals(List.of(installed), new MarkdownSkillStore(root).list());
        assertThrows(java.io.IOException.class, () -> store.readSelected(installed));
        assertEquals("const neverRuns = true;", store.readApprovedFile(installed, "scripts/example.js"));
        assertThrows(java.io.IOException.class, () -> store.readApprovedFile(installed, "../secret.txt"));
        var enabled = store.setEnabled(installed, true);
        assertEquals(SKILL, new MarkdownSkillStore(root).readSelected(enabled).markdown());
        assertThrows(java.io.IOException.class, () -> store.readSelected(installed));
        assertThrows(java.io.IOException.class, () -> store.install(preview));
        assertThrows(java.io.IOException.class, () -> store.delete(installed));
        store.delete(enabled);
        assertTrue(store.list().isEmpty());
    }

    @Test public void unsafeArchiveNeverPublishesOrWritesOutsideTheInstalledTree() throws Exception {
        File parent = temp.newFolder();
        File root = new File(parent, "skills");
        var store = new MarkdownSkillStore(root);
        for (String unsafePath : List.of("../escape/SKILL.md", "/absolute/SKILL.md", "C:/temp/SKILL.md",
                "demo\\..\\SKILL.md", "demo/../SKILL.md", "demo//SKILL.md", "demo/./SKILL.md",
                "demo/.env", "demo/credential.pem", "demo/sk-fixture-only-12345678901234567890.txt")) {
            Map<String, byte[]> entries = new LinkedHashMap<>();
            entries.put("safe/SKILL.md", bytes(SKILL));
            entries.put(unsafePath, bytes("inert fixture contents"));
            assertThrows(unsafePath, IllegalArgumentException.class,
                    () -> store.previewZip(new ByteArrayInputStream(zip(entries))));
            assertTrue(store.list().isEmpty());
        }
        assertFalse(new File(parent, "escape").exists());
        assertThrows(IllegalArgumentException.class, () -> store.previewZip(new ByteArrayInputStream(zip(Map.of(
                "s/SKILL.md", bytes(SKILL), "s/skill.md", bytes(SKILL))))));
        assertThrows(IllegalArgumentException.class, () -> store.previewZip(new ByteArrayInputStream(zip(Map.of(
                "s/SKILL.md", bytes(SKILL), "t/SKILL.md", bytes(SKILL))))));
        assertThrows(IllegalArgumentException.class, () -> store.previewZip(new ByteArrayInputStream(zip(Map.of(
                "s/SKILL.md", bytes(SKILL), "s/ref", bytes("# file"), "s/REF/doc.md", bytes("# child"))))));
    }

    @Test public void archiveAndDirectoryRejectCredentialMaterialWithoutExposingIt() throws Exception {
        var store = new MarkdownSkillStore(temp.newFolder());
        String fixture = "fake-sensitive-bearer";
        assertThrows(IllegalArgumentException.class, () -> MarkdownSkillStore.describe(SKILL + "\nBearer " + fixture));
        var error = assertThrows(IllegalArgumentException.class, () -> store.previewDirectory(Map.of(
                "SKILL.md", bytes(SKILL), "scripts/example.js", bytes("const api_key = \"" + fixture + "\";"))));
        assertFalse(error.getMessage().contains(fixture));
        assertThrows(IllegalArgumentException.class, () -> store.previewDirectory(Map.of(
                "SKILL.md", bytes(SKILL), "secret.txt", bytes("-----BEGIN PRIVATE KEY-----\nunit-test-only"))));
        assertThrows(IllegalArgumentException.class, () -> store.previewDirectory(Map.of("SKILL.md", new byte[]{(byte) 0xff})));
        assertThrows(IllegalArgumentException.class, () -> store.previewDirectory(Map.of(
                "SKILL.md", bytes(SKILL), "big.txt", new byte[MarkdownSkillStore.MAX_FILE_BYTES + 1])));
        assertTrue(store.list().isEmpty());
    }

    @Test public void approvedReadsNeverFollowAChangedFileOrLink() throws Exception {
        File root = temp.newFolder();
        var store = new MarkdownSkillStore(root);
        var candidate = store.installMarkdown(SKILL);
        var enabled = store.setEnabled(candidate, true);
        Path document = root.toPath().resolve(enabled.name()).resolve("SKILL.md");
        Files.writeString(document, SKILL + "\n# unrelated change\n");
        assertThrows(java.io.IOException.class, () -> store.readSelected(enabled));
        assertEquals(enabled, store.list().get(0));

        Path outside = temp.newFile("outside.txt").toPath();
        Files.delete(document);
        try {
            Files.createSymbolicLink(document, outside);
        } catch (UnsupportedOperationException | java.io.IOException unavailable) {
            // The changed-content assertion above still applies on hosts that cannot create links.
            return;
        }
        assertThrows(java.io.IOException.class, () -> store.readSelected(enabled));
        assertTrue(Files.exists(outside));
    }

    @Test public void namesAndMetadataRejectDuplicatesWhileMatchingOperitFallback() throws Exception {
        assertEquals("copy", MarkdownSkillStore.describe("name: copy\ndescription: fallback description\n").name());
        assertThrows(IllegalArgumentException.class, () -> MarkdownSkillStore.describe(
                "---\nname: first\nname: second\ndescription: changed\n---\n"));
        assertThrows(IllegalArgumentException.class, () -> MarkdownSkillStore.describe(
                "---\nname: ../outside\ndescription: invalid\n---\n"));
        var store = new MarkdownSkillStore(temp.newFolder());
        store.installMarkdown(SKILL);
        assertThrows(java.io.IOException.class,
                () -> store.installMarkdown(SKILL.replace("SandboxPackage_DEV", "sandboxpackage_dev")));
    }

    private static byte[] bytes(String text) { return text.getBytes(StandardCharsets.UTF_8); }

    private static byte[] zip(Map<String, byte[]> entries) throws Exception {
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(data)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return data.toByteArray();
    }
}
