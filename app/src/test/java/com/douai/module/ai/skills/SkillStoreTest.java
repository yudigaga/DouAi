package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.file.Files;
import java.util.UUID;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SkillStoreTest {
    @Rule public TemporaryFolder temp = new TemporaryFolder();

    @Test public void onlyValidatedTrialPublishesAndReplacementKeepsOnlyTheCurrentDocuments() throws Exception {
        File root = temp.newFolder();
        SkillStore store = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        assertEquals(1, store.installGenerated(draft("第一版", "测试")).version());
        assertThrows(IllegalArgumentException.class, () -> store.installGenerated(draft("损坏版", "错误输出")));
        assertThrows(IllegalArgumentException.class, () ->
                store.installGenerated(draft("非法命令", "测试").replace("\"summary\"", "\"shell\"")));
        assertEquals(1, store.list().get(0).version());
        String second = draft("第二版", "测试");
        assertEquals(2, store.installGenerated(second, store.previewGenerated(second)).version());
        File[] revisions = new File(root, "versions").listFiles();
        assertNotNull(revisions);
        assertEquals(1, revisions.length);
        for (File revision : revisions) {
            assertTrue(Files.readString(new File(revision, "SKILL.md").toPath()).contains("name: 检查"));
            assertTrue(Files.readString(new File(revision, "execution.json").toPath()).contains("\"kind\":\"summary\""));
        }
        String third = draft("第三版", "测试");
        assertEquals(3, store.installGenerated(third, store.previewGenerated(third)).version());
        assertFalse(revisions[0].exists());
        assertEquals(1, new File(root, "versions").listFiles().length);
    }

    @Test public void legacyCatalogueKeepsItsActiveSkillAndLeavesDormantFilesAlone() throws Exception {
        File root = temp.newFolder();
        SkillStore store = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        store.installGenerated(draft("旧文字", "测试"));
        File revision = new File(root, "versions").listFiles()[0];
        var execution = new File(revision, "execution.json").toPath();
        var definition = new JSONObject(Files.readString(execution));
        definition.put("schemaVersion", 1).remove("outputType");
        Files.writeString(execution, definition.toString());

        var catalog = new File(root, "catalog.json").toPath();
        var index = new JSONObject(Files.readString(catalog));
        index.remove("drafts");
        var skill = index.getJSONObject("skills").getJSONObject("检查");
        var metadata = skill.getJSONObject("current");
        metadata.remove("outputType");
        metadata.remove("serviceLabel");
        String dormantId = UUID.randomUUID().toString();
        var dormant = new File(root, "versions/" + dormantId).toPath();
        Files.createDirectory(dormant);
        Files.writeString(dormant.resolve("execution.json"), "never read this dormant execution");
        var later = new JSONObject().put("version", 2).put("directory", dormantId)
                .put("description", "旧配置").put("outputType", "image");
        skill.remove("current");
        skill.put("active", 0).put("versions", new JSONArray().put(metadata).put(later));
        Files.writeString(catalog, index.toString());

        SkillStore reopened = new SkillStore(root, SkillServices.forContext(RuntimeEnvironment.getApplication()));
        assertEquals(1, reopened.settingsSnapshot().rows().get(0).current().version());
        assertEquals(SkillResult.Type.TEXT, reopened.detail("检查").current().outputType());
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            var invocation = AiSkills.match(entry, "/检查 旧版参数", reopened);
            assertEquals(SkillResult.Type.TEXT, invocation.command().outputType());
            assertEquals("旧版参数", AiSkills.execute(invocation, reopened));
        }
        assertEquals(index.toString(), Files.readString(catalog));

        String update = draft("新配置", "测试");
        assertEquals(3, reopened.installGenerated(update, reopened.previewGenerated(update)).version());
        assertEquals(4, reopened.installGenerated(update, reopened.previewGenerated(update)).version());
        var saved = new JSONObject(Files.readString(catalog)).getJSONObject("skills").getJSONObject("检查");
        assertEquals(4, saved.getJSONObject("current").getInt("version"));
        assertEquals(skill.getJSONArray("versions").toString(), saved.getJSONArray("versions").toString());
        assertEquals(3, new File(root, "versions").listFiles().length);
        assertEquals("never read this dormant execution", Files.readString(dormant.resolve("execution.json")));
        assertTrue(Files.exists(execution));
    }

    @Test public void aPreviewCannotReplaceADeletedSkillRecreatedAtTheSameVersion() throws Exception {
        SkillStore store = new SkillStore(temp.newFolder(), SkillServices.forContext(RuntimeEnvironment.getApplication()));
        String first = draft("初始配置", "测试");
        String update = draft("过期确认", "测试");
        store.installGenerated(first);
        SkillStore.Preview stale = store.previewGenerated(update);
        store.delete(store.detail("检查").deleteTarget());
        store.installGenerated(first);

        assertThrows(IllegalStateException.class, () -> store.installGenerated(update, stale));
        assertEquals(1, store.detail("检查").current().version());
        assertTrue(store.pending().isEmpty());
    }

    @Test public void reloadedStateControlsBothEntriesAndDisablesQueuedVersion() throws Exception {
        File root = temp.newFolder();
        SkillServices services = SkillServices.forContext(RuntimeEnvironment.getApplication());
        SkillStore store = new SkillStore(root, services);
        store.installGenerated(draft("第一版", "测试"));
        SkillStore reopened = new SkillStore(root, services);
        AiSkills.Invocation invocation = AiSkills.match(AiSkills.Entry.CONTACT, "/检查 参数", reopened);
        assertEquals("参数", AiSkills.execute(invocation, store));
        assertNotNull(AiSkills.match(AiSkills.Entry.WORK, "/检查 参数", reopened));

        reopened.setSkillEnabled("检查", false);
        assertNull(AiSkills.match(AiSkills.Entry.WORK, "/检查 参数", store));
        assertThrows(IllegalStateException.class, () -> AiSkills.execute(invocation, store));
        reopened.setSkillEnabled("检查", true);
        reopened.setEnabled(false);
        assertFalse(store.enabled());
        assertTrue(AiSkills.commands(AiSkills.Entry.CONTACT, "/", store).isEmpty());
        assertNull(AiSkills.match(AiSkills.Entry.WORK, "/摘要 测试", store));
    }

    @Test public void mockFixtureCanInstallAndExecuteWithoutModelOrCredential() throws Exception {
        SkillStore store = new SkillStore(temp.newFolder(), SkillServices.forContext(RuntimeEnvironment.getApplication()));
        store.installGenerated(SkillStore.mockDraft("以模拟订单接口查询状态"));
        assertEquals("模拟查询：订单 DA-1001 已发货。",
                AiSkills.execute(AiSkills.match(AiSkills.Entry.WORK, "/模拟技能 DA-1001", store), store));
    }

    @Test public void generatedRemoteBindingNeedsConfirmationAndPinsExactEndpoint() throws Exception {
        File root = temp.newFolder();
        String secret = "fixture-service-key";
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        SkillServices services = new SkillServices(root,
                new com.douai.module.ai.AiCredentialVault(() -> new javax.crypto.spec.SecretKeySpec(new byte[32], "AES")),
                (endpoint, token, request, deadline, outputType) -> {
                    calls.incrementAndGet();
                    return new SkillServices.Response(200, "text/plain", "已完成".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                });
        SkillStore store = new SkillStore(new File(root, "skills"), services);
        SkillServices.Service old = new SkillServices.Service("lookup", "查询", "https://before.example/api", 1);
        services.register(old, secret);
        services.authorize(old, 1);

        String draft = new org.json.JSONObject()
                .put("schemaVersion", 1).put("name", "查状态").put("description", "查询")
                .put("documentation", "输入查询条件")
                .put("execution", new org.json.JSONObject().put("kind", "service")
                        .put("maxInput", 64).put("serviceId", "lookup"))
                .put("trial", new org.json.JSONObject().put("input", "测试").put("expected", "模拟服务响应"))
                .toString();
        assertFalse(store.generationPrompt("查状态").contains(secret));
        assertFalse(store.generationPrompt("查状态").contains(old.endpoint()));
        SkillStore.Preview preview = store.previewGenerated(draft);
        assertTrue(preview.needsConfirmation());
        assertThrows(IllegalStateException.class, () -> store.installGenerated(draft));
        store.installGenerated(draft, preview);
        assertEquals(0, calls.get());
        AiSkills.Invocation run = AiSkills.match(AiSkills.Entry.CONTACT, "/查状态 请求", store);
        assertEquals("已完成", AiSkills.execute(run, store));

        SkillServices.Service moved = new SkillServices.Service("lookup", "查询", "https://after.example/api", 1);
        services.register(moved, "");
        services.authorize(moved, 1);
        assertThrows(SkillServices.Failure.class, () -> AiSkills.execute(run, store));
        assertThrows(IllegalStateException.class, () -> store.installGenerated(draft, preview));
        assertEquals(1, calls.get());
        assertEquals(1, store.list().get(0).version());
    }
    private static String draft(String documentation, String expected) throws Exception {
        return new org.json.JSONObject()
                .put("schemaVersion", 1).put("name", "检查").put("description", "测试摘要")
                .put("documentation", documentation)
                .put("execution", new org.json.JSONObject().put("kind", "summary").put("maxInput", 40))
                .put("trial", new org.json.JSONObject().put("input", "测试").put("expected", expected))
                .toString();
    }
}
