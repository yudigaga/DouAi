package com.douai.module.ai.mcp;

import android.content.Context;

import com.douai.module.ai.AiCredentialVault;

import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.IOException;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

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

    public static McpRegistry chatPackageRegistry(Context context, AtomicInteger discoveries, AtomicInteger calls)
            throws Exception {
        Context app = context.getApplicationContext();
        Context owner = app == null ? context : app;
        SecretKey key = KeyGenerator.getInstance("AES").generateKey();
        FakeRpc rpc = new FakeRpc();
        rpc.tool = new McpClient.Tool("inspect", "检查输入", "检查一段用户提供的文字", """
                {"type":"object","properties":{"input":{"type":"string"}},"required":["input"],"additionalProperties":false}
                """.trim(), "fixture-inspect-v1");
        rpc.duringDiscovery = discoveries::incrementAndGet;
        rpc.onCall = args -> {
            assertEquals(1, args.length());
            assertTrue(args.opt("input") instanceof String input && !input.trim().isEmpty());
            calls.incrementAndGet();
        };
        rpc.reply = "MCP 离线结果";
        var directory = Files.createTempDirectory(owner.getNoBackupFilesDir().toPath(), "mcp-chat-fixture-").toFile();
        McpRegistry registry = new McpRegistry(directory, new AiCredentialVault(() -> key), rpc);
        McpRegistry.Service fixture = new McpRegistry.Service("fixture", "测试 MCP 服务",
                "https://mcp-fixture.example.test/v1/mcp", McpClient.MODERN);
        registry.register(fixture, SECRET);
        registry.authorize(fixture);
        return registry;
    }

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

    @Test public void packageDirectoryIsLocalAndActivationOnlyDiscoversMetadata() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        assertTrue(fixture.registry.packageCatalog().isEmpty());
        fixture.registry.authorize(ORDERS);

        List<McpRegistry.PackageEntry> catalog = fixture.registry.packageCatalog();
        assertEquals(1, catalog.size());
        McpRegistry.PackageEntry entry = catalog.get(0);
        assertEquals("orders", entry.name());
        assertEquals("订单", entry.description());
        assertTrue(fixture.rpc.tokens.isEmpty());

        McpRegistry.PackageTool tool = fixture.registry.activatePackage(entry, deadline()).get(0);
        assertEquals("check", tool.name());
        assertEquals("订单状态", tool.title());
        assertEquals("查询订单状态", tool.description());
        assertEquals(List.of(SECRET), fixture.rpc.tokens);
        assertEquals(0, fixture.rpc.calls);
        assertTrue(fixture.registry.bindings().isEmpty());
    }

    @Test public void oldPackageEntryDoesNotAcquireAReplacementGrant() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        fixture.registry.authorize(ORDERS);
        McpRegistry.PackageEntry oldEntry = fixture.registry.packageCatalog().get(0);

        fixture.registry.revoke(ORDERS);
        assertTrue(fixture.registry.packageCatalog().isEmpty());
        fixture.registry.authorize(ORDERS);
        McpRegistry.Failure stale = assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.activatePackage(oldEntry, deadline()));
        assertEquals(McpRegistry.Code.CONFIRMATION_REQUIRED, stale.code());
        assertTrue(fixture.rpc.tokens.isEmpty());

        McpRegistry.PackageEntry current = fixture.registry.packageCatalog().get(0);
        fixture.rpc.duringDiscovery = () -> fixture.registry.revoke(ORDERS);
        McpRegistry.Failure revoked = assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.activatePackage(current, deadline()));
        assertEquals(McpRegistry.Code.CONFIRMATION_REQUIRED, revoked.code());
        assertEquals(0, fixture.rpc.calls);
        assertTrue(fixture.registry.packageCatalog().isEmpty());
    }

    @Test public void packageMetadataOmitsAddressesAndRejectsCredentialEcho() throws Exception {
        Fixture fixture = new Fixture();
        McpRegistry.Service labelled = new McpRegistry.Service(ORDERS.id(), "订单 " + ORDERS.endpoint(),
                ORDERS.endpoint(), ORDERS.protocol());
        fixture.registry.register(labelled, SECRET);
        fixture.registry.authorize(labelled);
        McpRegistry.PackageEntry entry = fixture.registry.packageCatalog().get(0);
        assertEquals("", entry.description());

        fixture.rpc.tool = new McpClient.Tool("check", "订单状态", "查询 " + ORDERS.endpoint(),
                "{\"type\":\"object\"}", "reviewed");
        McpRegistry.PackageTool metadata = fixture.registry.activatePackage(entry, deadline()).get(0);
        assertEquals("订单状态", metadata.title());
        assertEquals("", metadata.description());

        fixture.rpc.tool = new McpClient.Tool("check", "订单状态", SECRET, "{\"type\":\"object\"}", "reviewed");
        McpRegistry.Failure echoed = assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.activatePackage(entry, deadline()));
        assertEquals(McpRegistry.Code.CREDENTIAL, echoed.code());
        assertFalse(echoed.getMessage().contains(SECRET));
    }

    @Test public void explicitlySelectedPackageToolUsesCurrentScopeWithoutBindingAnAlias() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        fixture.registry.authorize(ORDERS);
        McpRegistry.PackageEntry orders = fixture.registry.packageCatalog().get(0);
        McpRegistry.PackageTool tool = fixture.registry.activatePackage(orders, deadline()).get(0);
        McpRegistry.Service otherService = new McpRegistry.Service("other", "另一个服务", ORDERS.endpoint() + "-other",
                ORDERS.protocol());
        fixture.registry.register(otherService, "");
        fixture.registry.authorize(otherService);
        McpRegistry.PackageEntry other = fixture.registry.packageCatalog().get(1);

        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(orders, null, "{}", deadline()));
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(null, tool, "{}", deadline()));
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(other, tool, "{}", deadline()));
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(orders, tool, "[]", deadline()));
        assertEquals(0, fixture.rpc.calls);
        assertEquals(List.of(SECRET), fixture.rpc.tokens);

        assertEquals("已发货", fixture.registry.executePackageTool(orders, tool, "{\"id\":1}", deadline()));
        assertEquals(1, fixture.rpc.calls);
        assertEquals(1, fixture.rpc.calledArguments.getInt("id"));
        assertEquals(List.of(SECRET, SECRET), fixture.rpc.tokens);
        assertTrue(fixture.registry.bindings().isEmpty());
    }

    @Test public void directPackageToolRechecksDefinitionAndAuthorizationBeforeDispatch() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        fixture.registry.authorize(ORDERS);
        McpRegistry.PackageEntry entry = fixture.registry.packageCatalog().get(0);
        McpRegistry.PackageTool stale = fixture.registry.activatePackage(entry, deadline()).get(0);
        fixture.rpc.tool = tool("changed");
        McpRegistry.PackageTool current = fixture.registry.activatePackage(entry, deadline()).get(0);
        McpRegistry.Failure changed = assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(entry, stale, "{\"id\":1}", deadline()));
        assertEquals(McpRegistry.Code.REVIEW_TOOL, changed.code());
        assertEquals(0, fixture.rpc.calls);

        fixture.rpc.beforeAdmission = () -> fixture.registry.revoke(ORDERS);
        McpRegistry.Failure revoked = assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(entry, current, "{\"id\":1}", deadline()));
        assertEquals(McpRegistry.Code.CONFIRMATION_REQUIRED, revoked.code());
        assertEquals(0, fixture.rpc.calls);
        assertEquals(List.of(SECRET, SECRET), fixture.rpc.tokens);
    }

    @Test public void modelParameterSummaryPublishesOnlyVerifiedNamesTypesAndRequiredFlags() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        fixture.registry.authorize(ORDERS);
        McpRegistry.PackageEntry entry = fixture.registry.packageCatalog().get(0);
        fixture.rpc.tool = new McpClient.Tool("check", "订单状态", "查询", """
                {"type":"object","properties":{
                  "input":{"type":"string","description":"内部说明","default":"内部默认值"},
                  "maxTokens":{"type":"integer"},
                  "options":{"type":"object","properties":{"privateKey":{"type":"string"}}},
                  "apiKey":{"type":"string","default":"fake-credential"},
                  "route":{"type":"string","x-mcp-header":"Control"}
                },"required":["input"]}
                """, "parameters-v1");
        String summary = fixture.registry.activatePackage(entry, deadline()).get(0).parameterSummary();
        JSONObject parameters = new JSONObject(summary);
        assertEquals(2, parameters.length());
        assertEquals("string", parameters.getJSONObject("input").getString("type"));
        assertTrue(parameters.getJSONObject("input").getBoolean("required"));
        assertEquals("integer", parameters.getJSONObject("maxTokens").getString("type"));
        assertFalse(parameters.getJSONObject("maxTokens").getBoolean("required"));
        for (String privateValue : List.of("apiKey", "options", "privateKey", "route", "Control", "内部说明", "内部默认值", "fake-credential")) {
            assertFalse(summary.contains(privateValue));
        }
        assertEquals(0, fixture.rpc.calls);
    }

    @Test public void modelParameterSummaryRejectsUnsupportedAndHiddenRequiredArguments() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, SECRET);
        fixture.registry.authorize(ORDERS);
        McpRegistry.PackageEntry entry = fixture.registry.packageCatalog().get(0);
        for (String definition : List.of(
                "{\"type\":\"object\",\"properties\":{\"input\":{\"$ref\":\"https://example.test/schema\"}}}",
                "{\"type\":\"object\",\"properties\":{\"accessToken\":{\"type\":\"string\"}},\"required\":[\"accessToken\"]}",
                "{\"type\":\"object\",\"properties\":{\"options\":{\"type\":\"object\"}},\"required\":[\"options\"]}",
                "{\"type\":\"object\",\"properties\":{\"route\":{\"type\":\"string\",\"x-mcp-header\":\"Routing\"}},\"required\":[\"route\"]}")) {
            fixture.rpc.tool = new McpClient.Tool("check", "订单状态", "查询", definition, "parameters-invalid");
            assertEquals("", fixture.registry.activatePackage(entry, deadline()).get(0).parameterSummary());
        }
        fixture.rpc.tool = tool("no-parameters");
        assertEquals("{}", fixture.registry.activatePackage(entry, deadline()).get(0).parameterSummary());
    }

    @Test public void deletingAuthorizedServiceIsLocalAndPreservesUnrelatedServiceAndBinding() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        var other = new McpRegistry.Service("other", "别的服务", "https://other.example.test/mcp", McpClient.LEGACY);
        var otherBinding = new McpRegistry.Binding("mcp-other", other.id(), CHECK.toolName());
        fixture.registry.register(other, "");
        fixture.registry.authorize(other);
        fixture.registry.bind(other, fixture.registry.discover(other, deadline()).get(0), otherBinding.alias());

        fixture.vaultAvailable.set(false);
        var target = fixture.registry.prepareDelete(ORDERS);
        fixture.registry.delete(target);
        assertEquals(List.of(other), fixture.registry.services());
        assertEquals(List.of(otherBinding), fixture.registry.bindings());
        assertEquals(List.of(otherBinding), fixture.registry.activeBindings());
        assertTrue(fixture.registry.isAuthorized(other));
        assertNull(fixture.registry.selectBinding(CHECK.alias()));
        assertEquals(List.of(SECRET, ""), fixture.rpc.tokens);
        assertEquals(0, fixture.rpc.calls);
        String stored = Files.readString(fixture.directory.toPath().resolve("mcp-registry.json"));
        assertFalse(stored.contains(ORDERS.endpoint()));
        assertFalse(stored.contains(CHECK.alias()));
        assertFalse(fixture.registry.exportJson().contains("registrationRevision"));
        assertEquals(McpRegistry.Code.CONFIRMATION_REQUIRED,
                assertThrows(McpRegistry.Failure.class, () -> fixture.registry.delete(target)).code());
    }

    @Test public void staleDeleteCannotRemoveRenamedIdenticallyResavedOrImportedService() throws Exception {
        Fixture fixture = new Fixture();
        fixture.registry.register(ORDERS, "");
        var beforeLabel = fixture.registry.prepareDelete(ORDERS);
        var renamed = new McpRegistry.Service(ORDERS.id(), "新名称", ORDERS.endpoint(), ORDERS.protocol());
        fixture.registry.register(renamed, "");
        assertEquals(McpRegistry.Code.CONFIRMATION_REQUIRED,
                assertThrows(McpRegistry.Failure.class, () -> fixture.registry.delete(beforeLabel)).code());
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.prepareDelete(ORDERS));

        var beforeIdenticalWrite = fixture.registry.prepareDelete(renamed);
        fixture.registry.register(renamed, "");
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.delete(beforeIdenticalWrite));
        var beforeImport = fixture.registry.prepareDelete(renamed);
        fixture.registry.applyImport(fixture.registry.previewImport(fixture.registry.exportJson()));
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.delete(beforeImport));
        assertEquals(List.of(renamed), fixture.registry.services());
        assertFalse(fixture.registry.isAuthorized(renamed));
        fixture.registry.delete(fixture.registry.prepareDelete(renamed));
        assertTrue(fixture.registry.services().isEmpty());
        assertTrue(fixture.rpc.tokens.isEmpty());
        assertEquals(0, fixture.rpc.calls);
    }

    @Test public void rebuildingSameServiceAndAliasDoesNotAuthorizeOldSelections() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        McpRegistry.BindingSelection manual = fixture.registry.selectBinding(CHECK.alias());
        assertNotNull(manual);
        assertEquals(CHECK, manual.binding());
        McpRegistry.PackageEntry entry = fixture.registry.packageCatalog().get(0);
        McpRegistry.PackageTool packageTool = fixture.registry.activatePackage(entry, deadline()).get(0);

        fixture.registry.delete(fixture.registry.prepareDelete(ORDERS));
        fixture.bind();
        int discoveryCount = fixture.rpc.tokens.size();
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.executeSelected(manual, "{}", deadline()));
        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.activatePackage(entry, deadline()));
        assertThrows(McpRegistry.Failure.class,
                () -> fixture.registry.executePackageTool(entry, packageTool, "{}", deadline()));
        assertEquals(discoveryCount, fixture.rpc.tokens.size());
        assertEquals(0, fixture.rpc.calls);
        assertEquals("已发货", fixture.registry.executeSelected(fixture.registry.selectBinding(CHECK.alias()), "{}", deadline()));
        assertEquals(1, fixture.rpc.calls);
    }

    @Test public void selectedBindingRechecksTheSameRegistrationAtToolAdmission() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        McpRegistry.BindingSelection selected = fixture.registry.selectBinding(CHECK.alias());
        fixture.rpc.beforeAdmission = () -> {
            fixture.registry.delete(fixture.registry.prepareDelete(ORDERS));
            try { fixture.bind(); } catch (Exception unavailable) { throw new IOException(unavailable); }
        };

        assertThrows(McpRegistry.Failure.class, () -> fixture.registry.executeSelected(selected, "{}", deadline()));
        assertEquals(List.of(CHECK), fixture.registry.activeBindings());
        assertEquals(0, fixture.rpc.calls);
    }

    @Test public void relabellingAServiceDoesNotChangeItsSelectedBindingGrant() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        McpRegistry.BindingSelection selected = fixture.registry.selectBinding(CHECK.alias());
        var renamed = new McpRegistry.Service(ORDERS.id(), "新名称", ORDERS.endpoint(), ORDERS.protocol());
        fixture.registry.register(renamed, "");

        assertEquals("已发货", fixture.registry.executeSelected(selected, "{}", deadline()));
        assertEquals(1, fixture.rpc.calls);
    }

    @Test public void legacyRecordWithoutRegistrationRevisionStillSupportsLocalDeletion() throws Exception {
        Fixture fixture = new Fixture();
        fixture.bind();
        var file = fixture.directory.toPath().resolve("mcp-registry.json");
        JSONObject document = new JSONObject(Files.readString(file));
        assertNotNull(document.getJSONArray("services").getJSONObject(0).remove("registrationRevision"));
        Files.writeString(file, document.toString());
        assertEquals(CHECK, fixture.registry.selectBinding(CHECK.alias()).binding());
        fixture.registry.delete(fixture.registry.prepareDelete(ORDERS));
        assertTrue(fixture.registry.services().isEmpty());
        assertTrue(fixture.registry.bindings().isEmpty());
        assertEquals(List.of(SECRET), fixture.rpc.tokens);
    }

    private static long deadline() { return System.nanoTime() + TimeUnit.SECONDS.toNanos(5); }

    private static McpClient.Tool tool(String fingerprint) {
        return new McpClient.Tool("check", "订单状态", "查询订单状态", "{\"type\":\"object\"}", fingerprint);
    }

    private final class Fixture {
        final FakeRpc rpc = new FakeRpc();
        final java.io.File directory;
        final McpRegistry registry;
        final AtomicBoolean vaultAvailable = new AtomicBoolean(true);

        Fixture() throws Exception {
            SecretKey key = KeyGenerator.getInstance("AES").generateKey();
            directory = files.newFolder();
            registry = new McpRegistry(directory, new AiCredentialVault(() -> {
                if (!vaultAvailable.get()) throw new AssertionError("No credential access expected");
                return key;
            }), rpc);
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
        McpClient.Admission duringDiscovery = () -> {};
        Consumer<JSONObject> onCall = ignored -> {};
        String reply = "已发货";
        int calls;
        JSONObject calledArguments;

        @Override public List<McpClient.Tool> discover(String endpoint, String protocol, String bearer, long deadline)
                throws IOException {
            tokens.add(bearer);
            duringDiscovery.check();
            return List.of(tool);
        }

        @Override public String call(String endpoint, String protocol, String bearer, McpClient.Tool expected,
                                     JSONObject args, long deadline, McpClient.Admission admission) throws IOException {
            beforeAdmission.check();
            admission.check();
            tokens.add(bearer);
            calls++;
            calledArguments = args;
            onCall.accept(args);
            return reply;
        }
    }
}
