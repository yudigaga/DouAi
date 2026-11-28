package com.douai.module.ai.skills;

import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.ai.mcp.McpRegistryTest;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class McpManualRoutingTest {
    @Test public void importedAliasCannotFallThroughToModelOrExecuteBeforeApproval() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var registry = McpRegistry.forContext(context);
        var preview = registry.previewImport("""
                {"version":1,"services":[{"id":"weather","label":"天气","endpoint":"https://imported.example/mcp",
                "protocol":"2026-07-28"}],"bindings":[{"alias":"mcp-weather","serviceId":"weather","toolName":"weather"}]}
                """);
        registry.applyImport(preview);
        SkillStore store = SkillStore.forContext(context);
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertTrue(AiSkills.commands(entry, "/mcp-", store).isEmpty());
            assertThrows(AiSkills.CommandUnavailable.class,
                    () -> AiSkills.match(entry, "/mcp-mistyped {}", store));
            var call = AiSkills.match(entry, "/mcp-weather {\"city\":\"深圳\"}", store);
            assertNotNull(call);
            assertThrows(McpRegistry.Failure.class, () -> AiSkills.execute(call, store));
        }
    }

    @Test public void deletingAndRecreatingAServiceDoesNotReviveSelectedSlashCommands() throws Exception {
        var context = RuntimeEnvironment.getApplication();
        var discoveries = new AtomicInteger();
        var calls = new AtomicInteger();
        var registry = McpRegistryTest.chatPackageRegistry(context, discoveries, calls);
        var store = SkillStoreTest.chatPackageStore(context, registry);
        var service = registry.services().get(0);
        String alias = "mcp-inspect";
        String command = "/" + alias + " {\"input\":\"样例\"}";
        var tool = registry.discover(service, deadline()).get(0);
        registry.bind(service, tool, alias);

        var selected = new ArrayList<AiSkills.Invocation>();
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertTrue(AiSkills.commands(entry, "/mcp-", store).stream().anyMatch(item -> alias.equals(item.name())));
            var invocation = AiSkills.match(entry, command, store);
            assertNotNull(invocation);
            selected.add(invocation);
        }

        registry.delete(registry.prepareDelete(service));
        for (AiSkills.Entry entry : AiSkills.Entry.values()) {
            assertTrue(AiSkills.commands(entry, "/mcp-", store).isEmpty());
            var missing = assertThrows(AiSkills.CommandUnavailable.class, () -> AiSkills.match(entry, command, store));
            assertEquals("MCP 命令未登记", missing.getMessage());
            assertTrue(AiSkills.commands(entry, "/摘", store).stream().anyMatch(item -> "摘要".equals(item.name())));
        }

        registry.register(service, "");
        registry.authorize(service);
        registry.bind(service, registry.discover(service, deadline()).get(0), alias);
        for (AiSkills.Invocation invocation : selected) {
            var stale = assertThrows(McpRegistry.Failure.class, () -> AiSkills.execute(invocation, store));
            assertFalse(stale.resultUnknown());
        }
        assertEquals(0, calls.get());
        assertEquals(2, discoveries.get());

        var replacement = AiSkills.match(AiSkills.Entry.WORK, command, store);
        assertNotNull(replacement);
        assertEquals("MCP 离线结果", AiSkills.execute(replacement, store));
        assertEquals(1, calls.get());
    }

    private static long deadline() {
        return System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    }
}
