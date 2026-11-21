package com.douai.module.ai.mcp;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class McpRegistryTest {
    private static final String SECRET = "synthetic-mcp-token";
    private static final McpRegistry.Service ORDERS = new McpRegistry.Service("orders", "订单",
            "https://mcp.example.test/orders", McpClient.MODERN);
    private static final McpRegistry.Binding CHECK =
            new McpRegistry.Binding("mcp-order", "orders", "check");

    @Rule public final TemporaryFolder files = new TemporaryFolder();

    @Test public void previewDoesNotChangeTheRegistryAndImportClearsSameEndpointGrantAndCredentials() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        String exported = fixture.registry.exportJson();
        JSONObject document = new JSONObject(exported);
        assertEquals(1, document.getInt("version"));
        assertFalse(exported.contains(SECRET));
        assertFalse(exported.contains("fingerprint"));
        assertFalse(exported.contains("authorized"));

        McpRegistry.ImportPreview preview = fixture.registry.previewImport(exported);
        assertEquals(List.of(ORDERS), preview.services());
        assertEquals(List.of(CHECK), preview.bindings());
        assertTrue(fixture.registry.isAuthorized(ORDERS));
        assertEquals(List.of(CHECK), fixture.registry.activeBindings());

        fixture.registry.applyImport(preview);
        assertFalse(fixture.registry.isAuthorized(ORDERS));
        assertEquals(List.of(CHECK), fixture.registry.bindings());
        assertTrue(fixture.registry.activeBindings().isEmpty());
        assertEquals(1, fixture.rpc.tokens.size());
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.execute(CHECK, "{\"id\":1}", deadline()));

        fixture.registry.authorize(ORDERS);
        McpClient.Tool verified = fixture.registry.discover(ORDERS, deadline()).get(0);
        assertTrue(fixture.registry.activeBindings().isEmpty());
        fixture.registry.bind(ORDERS, verified, CHECK.alias());
        assertEquals("已发货", fixture.registry.execute(CHECK, "{\"id\":1}", deadline()));
        assertEquals(List.of(SECRET, "", ""), fixture.rpc.tokens);
    }

    @Test public void importRejectsUnknownAndDuplicateFieldsWithoutNetworkOrStorageChanges() throws Exception {
        Fixture fixture = new Fixture();
        for (String json : List.of("{\"version\":1,\"services\":[],\"bindings\":[],\"authorized\":true}",
                "{\"version\":1,\"version\":1,\"services\":[],\"bindings\":[]}",
                "{'version':1,'services':[],'bindings':[]}",
                "{\"version\":\"1\",\"services\":[],\"bindings\":[]}")) {
            assertThrows(McpRegistry.Failure.class, () -> fixture.registry.previewImport(json));
        }
        assertTrue(fixture.registry.services().isEmpty());
        assertTrue(fixture.rpc.tokens.isEmpty());
    }

    @Test public void changedToolPermissionDisarmsOnlyItsBindingUntilReviewedAgain() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        fixture.rpc.tool = tool("changed");
        fixture.registry.discover(ORDERS, deadline());
        assertTrue(fixture.registry.isAuthorized(ORDERS));
        assertTrue(fixture.registry.activeBindings().isEmpty());
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.execute(CHECK, "{\"id\":1}", deadline()));
        assertEquals(0, fixture.rpc.calls);
    }

    @Test public void executingChecksGrantAgainAfterRemoteValidationBeforeSendingTheToolCall() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        fixture.rpc.beforeAdmission = () -> fixture.registry.revoke(ORDERS);

        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.execute(CHECK, "{\"id\":1}", deadline()));
        assertEquals(0, fixture.rpc.calls);
        assertTrue(fixture.registry.activeBindings().isEmpty());
    }

    @Test public void changedEndpointNeedsExplicitApprovalAndNeverReceivesOldCredential() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        McpRegistry.Service moved = new McpRegistry.Service(ORDERS.id(), ORDERS.label(),
                ORDERS.endpoint() + "-new", ORDERS.protocol());
        fixture.registry.register(moved, "");
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.authorize(ORDERS));
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.discover(moved, deadline()));
        assertEquals(List.of(SECRET), fixture.rpc.tokens);
        fixture.registry.authorize(moved);
        fixture.registry.discover(moved, deadline());
        assertEquals(List.of(SECRET, ""), fixture.rpc.tokens);
        assertTrue(fixture.registry.activeBindings().isEmpty());
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    private static McpClient.Tool tool(String fingerprint) {
        return new McpClient.Tool("check", "订单状态", "查询订单状态", "{\"type\":\"object\"}", fingerprint);
    }

    private final class Fixture {
        final FakeRpc rpc = new FakeRpc();
        final McpRegistry registry;

        Fixture() throws Exception {
            SecretKey key = KeyGenerator.getInstance("AES").generateKey();
            registry = new McpRegistry(files.newFolder(), new AiCredentialVault(() -> key), rpc);
        }

        void bind() throws Exception {
            registry.register(ORDERS, SECRET);
            registry.authorize(ORDERS);
            registry.bind(ORDERS, registry.discover(ORDERS, deadline()).get(0), CHECK.alias());
        }
    }

    private static final class FakeRpc implements McpRegistry.Rpc {
        final List<String> tokens = new ArrayList<>();
        McpClient.Tool tool = tool("original");
        McpClient.Admission beforeAdmission = () -> {};
        int calls;

        @Override public List<McpClient.Tool> discover(String endpoint, String protocol, String bearer, long deadline) {
            tokens.add(bearer);
            return List.of(tool);
        }

        @Override public String call(String endpoint, String protocol, String bearer, McpClient.Tool expected,
                                     JSONObject args, long deadline, McpClient.Admission admission) throws IOException {
            beforeAdmission.check();
            admission.check();
            tokens.add(bearer);
            calls++;
            return "已发货";
        }
    }
}
