package com.douai.module.ai.skills;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.net.SocketTimeoutException;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class AiSubAgentsTest {
    @Test public void delegationCommandIsVisibleOnlyInConfiguredWorkChat() {
        assertEquals("委派", AiSkills.commands(AiSkills.Entry.WORK, "/委", null, true).get(0).name());
        assertTrue(AiSkills.match(AiSkills.Entry.WORK, "/委派 任务", null, true).delegated());
        assertEquals(0, AiSkills.commands(AiSkills.Entry.CONTACT, "/委", null, true).size());
        assertEquals(0, AiSkills.commands(AiSkills.Entry.WORK, "/委", null, false).size());
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.WORK, "/委派 任务", null, false));
        assertThrows(AiSkills.CommandUnavailable.class,
                () -> AiSkills.match(AiSkills.Entry.CONTACT, "/委派 任务", null, true));
    }

    @Test public void bothModelCallsUseSelectedIdAndOneDeadlineWithBoundedLocalTool() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        AtomicInteger calls = new AtomicInteger();
        String result = AiSubAgents.run(models(), new AiSubAgents.Selection("child", false, true),
                "核对订单", deadline, (settings, id, instruction, input, actualDeadline, tokens) -> {
                    assertEquals("parent", settings.activeId);
                    assertEquals("child", id);
                    assertEquals(deadline, actualDeadline);
                    assertEquals(600, tokens);
                    JSONObject envelope = new JSONObject(input);
                    assertEquals("核对订单", envelope.getString("task"));
                    assertFalse(input.contains("fixture-private-model-key"));
                    if (calls.incrementAndGet() == 1) {
                        assertEquals("[\"mock_orders\"]", envelope.getJSONArray("allow").toString());
                        return "{\"type\":\"tool\",\"name\":\"mock_orders\",\"argument\":\"DA-1001\"}";
                    }
                    assertEquals(0, envelope.getJSONArray("allow").length());
                    assertEquals("模拟查询：订单 DA-1001 已发货。",
                            envelope.getJSONObject("toolResult").getString("text"));
                    return "{\"type\":\"result\",\"text\":\"订单已发货\"}";
                });
        assertEquals("订单已发货", result);
        assertEquals(2, calls.get());
    }

    @Test public void deniedToolAndNestedDelegationCannotTriggerFollowup() throws Exception {
        for (String tool : List.of("mock_orders", "delegate", "mcp-weather")) {
            AtomicInteger calls = new AtomicInteger();
            assertThrows(AiSubAgents.Failure.class, () -> AiSubAgents.run(models(),
                    new AiSubAgents.Selection("child", true, false), "查订单", deadline(),
                    (settings, id, instruction, input, until, tokens) -> {
                        calls.incrementAndGet();
                        return new JSONObject().put("type", "tool").put("name", tool)
                                .put("argument", "DA-1001").toString();
                    }));
            assertEquals(1, calls.get());
        }
    }

    @Test public void aSecondToolAttemptDoesNotCauseAnAdditionalModelOrToolCall() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        assertThrows(AiSubAgents.Failure.class, () -> AiSubAgents.run(models(),
                new AiSubAgents.Selection("child", true, false), "摘要正文", deadline(),
                (settings, id, instruction, input, until, tokens) -> {
                    calls.incrementAndGet();
                    return "{\"type\":\"tool\",\"name\":\"summary\",\"argument\":\"正文\"}";
                }));
        assertEquals(2, calls.get());
    }

    @Test public void expiredCancelledAndDeletedModelSelectionsStartNoRequests() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AiSubAgents.Gateway gateway = (settings, id, instruction, input, until, tokens) -> {
            calls.incrementAndGet();
            return "{\"type\":\"result\",\"text\":\"unexpected\"}";
        };
        AiSubAgents.Selection selection = new AiSubAgents.Selection("child", false, false);
        assertThrows(SocketTimeoutException.class,
                () -> AiSubAgents.run(models(), selection, "任务", System.nanoTime() - 1, gateway));
        assertThrows(AiSubAgents.Failure.class, () -> AiSubAgents.run(models().delete("child"),
                selection, "任务", deadline(), gateway));
        AiModelSettings models = models();
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class,
                    () -> AiSubAgents.run(models, selection, "任务", deadline(), gateway));
        } finally {
            Thread.interrupted();
        }
        assertEquals(0, calls.get());
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    private static AiModelSettings models() throws Exception {
        return new AiModelSettings(List.of(
                AiModelConfig.fromJson(new JSONObject().put("id", "parent").put("name", "Parent")),
                AiModelConfig.fromJson(new JSONObject().put("id", "child").put("name", "Child")
                        .put("apiKey", "fixture-private-model-key"))), "parent");
    }
}
