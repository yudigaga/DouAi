package com.douai.module.ai.skills;

import com.douai.module.ai.mcp.McpRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

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
}
