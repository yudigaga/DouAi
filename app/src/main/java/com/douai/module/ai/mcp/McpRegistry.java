package com.douai.module.ai.mcp;

import android.content.Context;
import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Device-local MCP authorization, discovery, binding, and credential-free transfer. */
public final class McpRegistry {
    public record Service(String id, String label, String endpoint, String protocol) {}
    public record Binding(String alias, String serviceId, String toolName) {}

    public static final class DeleteTarget {
        private final Path registry;
        private final Service service;
        private final String revision;
        private final String grant;

        private DeleteTarget(Path registry, Registered current) {
            this.registry = registry;
            service = current.service();
            revision = current.revision();
            grant = current.grant();
        }
    }

    public static final class BindingSelection {
        private final Path registry;
        private final Bound bound;
        private final String grant;

        private BindingSelection(Path registry, Bound bound, Registered current) {
            this.registry = registry;
            this.bound = bound;
            grant = current.grant();
        }

        public Binding binding() { return bound.binding(); }
    }

    /** Public directory fields with a private proof of the locally selected authorization. */
    public static final class PackageEntry {
        private final String name;
        private final String description;
        private final String grant;

        private PackageEntry(Registered registered) {
            name = registered.service().id();
            description = packageText(registered.service().label());
            grant = registered.grant();
        }

        public String name() { return name; }
        public String description() { return description; }
    }

    /** The tool definition returned by a particular authorized package activation. */
    public static final class PackageTool {
        private final String serviceId;
        private final String grant;
        private final McpClient.Tool tool;
        private final boolean modern;

        private PackageTool(Registered registered, McpClient.Tool tool) {
            serviceId = registered.service().id();
            grant = registered.grant();
            this.tool = tool;
            modern = McpClient.MODERN.equals(registered.service().protocol());
        }

        public String name() { return tool.name(); }
        public String title() { return packageText(tool.title()); }
        public String description() { return packageText(tool.description()); }

        /** An empty string means the schema cannot safely be offered to the model. */
        public String parameterSummary() { return summarizeParameters(tool.inputSchema(), modern); }
    }

    public record ImportPreview(List<Service> services, List<Binding> bindings) {
        public ImportPreview {
            services = Collections.unmodifiableList(new ArrayList<>(services));
            bindings = Collections.unmodifiableList(new ArrayList<>(bindings));
        }
    }

    public enum Code { INVALID, STORAGE, CREDENTIAL, CONFIRMATION_REQUIRED, REVIEW_TOOL, PROTOCOL, NETWORK, CANCELLED }

    public static final class Failure extends IOException {
        private final Code code;
        private final boolean resultUnknown;
        private Failure(Code code, String message, boolean resultUnknown) {
            super(message);
            this.code = code;
            this.resultUnknown = resultUnknown;
        }
        public Code code() { return code; }
        public boolean resultUnknown() { return resultUnknown; }
    }

    interface Rpc {
        List<McpClient.Tool> discover(String endpoint, String protocol, String bearer, long deadline) throws IOException;
        String call(String endpoint, String protocol, String bearer, McpClient.Tool tool, JSONObject arguments,
                    long deadline, McpClient.Admission admission) throws IOException;
    }

    private record Registered(Service service, String ciphertext, String grant, Map<String, McpClient.Tool> tools,
                              String revision) {}
    private record Bound(Binding binding, String fingerprint) {}
    private record Execution(Registered registered, Bound bound, McpClient.Tool tool) {}
    private record State(LinkedHashMap<String, Registered> services, LinkedHashMap<String, Bound> bindings) {
        State() { this(new LinkedHashMap<>(), new LinkedHashMap<>()); }
    }
    private interface Edit { void apply(State state) throws Exception; }

    private static final Object WRITE_LOCK = new Object();
    private static final String REGISTRATION_REVISION = "registrationRevision";
    private static final AiCredentialVault.Scope SCOPE = AiCredentialVault.Scope.MCP_SERVER;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,47}");
    private static final Pattern ALIAS = Pattern.compile("mcp-[a-z0-9][a-z0-9_-]{0,23}");
    private static final Pattern TOOL = Pattern.compile("[A-Za-z0-9_.-]{1,128}");
    private static final Pattern PRIVATE_METADATA = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://|"
            + "(?:authorization|api[ _-]*key|[ _-]*token|password|密钥|凭据)\\s*[:=：]|\\bbearer\\s+");
    private static final Pattern PARAMETER_NAME = Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_.-]{0,63}");
    private static final Pattern PRIVATE_PARAMETER = Pattern.compile("(?i)api[._-]*key|access[._-]*key|private[._-]*key"
            + "|client[._-]*key|signing[._-]*key|session[._-]*id|password|passwd|passphrase|secret|credential"
            + "|authorization|^(?:auth|pin)$|bearer|cookie|token$|密钥|凭据|令牌|密码|口令|授权");
    private static final int MAX_PARAMETER_SUMMARY = 1200;
    private static final int VERSION = 1;
    private static final int MAX_SERVICES = 30;
    private static final int MAX_BINDINGS = 100;
    private static final int MAX_TOOLS = 100;
    private static final int MAX_JSON = 128 * 1024;
    private static final int MAX_STORE = 2 * 1024 * 1024;
    private static final int MAX_ARGUMENT = 16 * 1024;
    private static final int MAX_RESULT = 256 * 1024;

    private final Path file;
    private final AiCredentialVault credentials;
    private final Rpc rpc;

    public static McpRegistry forContext(Context context) {
        Context app = context.getApplicationContext();
        McpClient client = new McpClient();
        return new McpRegistry(new File((app == null ? context : app).getNoBackupFilesDir(), "douai-ai"),
                AiCredentialVault.forDevice(), new Rpc() {
            @Override public List<McpClient.Tool> discover(String endpoint, String protocol, String bearer, long deadline)
                    throws IOException {
                return client.discover(endpoint, protocol, bearer, deadline);
            }
            @Override public String call(String endpoint, String protocol, String bearer, McpClient.Tool tool,
                                         JSONObject arguments, long deadline, McpClient.Admission admission)
                    throws IOException {
                return client.call(endpoint, protocol, bearer, tool, arguments, deadline, admission);
            }
        });
    }

    McpRegistry(File directory, AiCredentialVault credentials, Rpc rpc) {
        file = directory.toPath().resolve("mcp-registry.json").toAbsolutePath().normalize();
        this.credentials = credentials;
        this.rpc = rpc;
    }

    public List<Service> services() throws Failure {
        List<Service> result = new ArrayList<>();
        for (Registered item : read().services().values()) result.add(item.service());
        return result;
    }

    public List<Binding> bindings() throws Failure {
        List<Binding> result = new ArrayList<>();
        for (Bound item : read().bindings().values()) result.add(item.binding());
        return result;
    }

    public List<Binding> activeBindings() throws Failure {
        State state = read();
        List<Binding> result = new ArrayList<>();
        for (Bound item : state.bindings().values()) {
            if (available(state, item)) result.add(item.binding());
        }
        return result;
    }

    /** Takes a stable snapshot even for a currently disarmed alias. */
    public BindingSelection selectBinding(String alias) throws Failure {
        if (alias == null) return null;
        State state = read();
        Bound bound = state.bindings().get(alias);
        return bound == null ? null : new BindingSelection(file, bound, state.services().get(bound.binding().serviceId()));
    }

    public DeleteTarget prepareDelete(Service expected) throws Failure {
        Registered current = expected == null ? null : read().services().get(expected.id());
        if (current == null || !current.service().equals(expected)) throw deleteChanged();
        return new DeleteTarget(file, current);
    }

    /** Removes the exact confirmed registration and its aliases in one local edit. */
    public void delete(DeleteTarget expected) throws Failure {
        if (expected == null || !file.equals(expected.registry)) throw deleteChanged();
        edit(state -> {
            String id = expected.service.id();
            Registered current = state.services().get(id);
            if (current == null || !current.service().equals(expected.service) || !current.revision().equals(expected.revision)
                    || !current.grant().equals(expected.grant)) throw deleteChanged();
            state.services().remove(id);
            state.bindings().values().removeIf(bound -> id.equals(bound.binding().serviceId()));
        });
    }

    /** Reads only the locally authorized directory. No discover or credential access occurs here. */
    public List<PackageEntry> packageCatalog() throws Failure {
        List<PackageEntry> result = new ArrayList<>();
        for (Registered registered : read().services().values()) {
            if (!registered.grant().isEmpty()) result.add(new PackageEntry(registered));
        }
        return Collections.unmodifiableList(result);
    }

    /** Discovers tool metadata for the particular authorization selected from the directory. */
    public List<PackageTool> activatePackage(PackageEntry expected, long deadlineNanos) throws Failure {
        ensureTime(deadlineNanos);
        Registered snapshot = admittedPackage(expected);
        List<McpClient.Tool> discovered = discover(snapshot, deadlineNanos);
        List<PackageTool> result = new ArrayList<>();
        for (McpClient.Tool tool : discovered) result.add(new PackageTool(snapshot, tool));
        return Collections.unmodifiableList(result);
    }

    public boolean isAuthorized(Service expected) throws Failure {
        Registered current = expected == null ? null : read().services().get(expected.id());
        return current != null && sameTarget(expected, current.service()) && !current.grant().isEmpty();
    }

    public void register(Service service, String bearer) throws Failure {
        validate(service);
        String token = bearer == null ? "" : bearer;
        if (!token.isEmpty() && !AiCredentialVault.validBearer(token)) {
            throw error(Code.INVALID, "Bearer 密钥应为 8–2048 位有效字符");
        }
        edit(state -> {
            Registered before = state.services().get(service.id());
            boolean sameEndpoint = before != null && before.service().endpoint().equals(service.endpoint());
            String ciphertext = sameEndpoint ? before.ciphertext() : "";
            if (!token.isEmpty()) {
                rejectSecret(token, service.id(), service.label(), service.endpoint());
                ciphertext = encrypt(service, token);
            } else if (!ciphertext.isEmpty() && !before.service().label().equals(service.label())) {
                rejectSecret(decrypt(before), service.label());
            }
            String revision = UUID.randomUUID().toString();
            if (before != null && sameTarget(service, before.service()) && token.isEmpty()) {
                state.services().put(service.id(), new Registered(service, ciphertext, before.grant(), before.tools(), revision));
            } else {
                storeDisarmed(state, service, ciphertext, revision);
            }
        });
    }

    public void authorize(Service expected) throws Failure {
        edit(state -> {
            Registered service = current(state, expected);
            if (!service.grant().isEmpty()) return;
            state.services().put(expected.id(), new Registered(service.service(), service.ciphertext(),
                    UUID.randomUUID().toString(), Collections.emptyMap(), service.revision()));
        });
    }

    public void revoke(Service expected) throws Failure {
        edit(state -> {
            Registered service = current(state, expected);
            storeDisarmed(state, service.service(), service.ciphertext(), service.revision());
        });
    }

    public List<McpClient.Tool> discover(Service expected, long deadlineNanos) throws Failure {
        ensureTime(deadlineNanos);
        return discover(authorized(read(), expected), deadlineNanos);
    }

    private List<McpClient.Tool> discover(Registered snapshot, long deadlineNanos) throws Failure {
        Service expected = snapshot.service();
        String token = decrypt(snapshot);
        ensureTime(deadlineNanos);
        requireSameGrant(snapshot, authorized(read(), expected));
        List<McpClient.Tool> discovered;
        try {
            discovered = rpc.discover(snapshot.service().endpoint(), snapshot.service().protocol(), token, deadlineNanos);
        } catch (IOException | RuntimeException failure) {
            throw remote(failure);
        }
        ensureTime(deadlineNanos);
        Map<String, McpClient.Tool> verified = verifiedTools(discovered, token);
        edit(state -> {
            Registered current = authorized(state, expected);
            requireSameGrant(snapshot, current);
            state.services().put(expected.id(), new Registered(current.service(), current.ciphertext(),
                    current.grant(), verified, current.revision()));
            state.bindings().replaceAll((alias, bound) -> {
                if (!expected.id().equals(bound.binding().serviceId())) return bound;
                McpClient.Tool tool = verified.get(bound.binding().toolName());
                return tool == null || !tool.fingerprint().equals(bound.fingerprint())
                        ? new Bound(bound.binding(), "") : bound;
            });
        });
        return Collections.unmodifiableList(new ArrayList<>(verified.values()));
    }

    private static String packageText(String value) {
        return PRIVATE_METADATA.matcher(value).find() ? "" : value;
    }

    private static String summarizeParameters(String raw, boolean modern) {
        try {
            JSONObject schema = McpClient.verifiedInputSchema(raw, modern);
            if (schema.has("x-mcp-header")) return "";
            Set<String> required = new HashSet<>();
            JSONArray declaredRequired = schema.optJSONArray("required");
            if (declaredRequired != null) {
                for (int i = 0; i < declaredRequired.length(); i++) required.add(declaredRequired.getString(i));
            }
            JSONObject summary = new JSONObject();
            JSONObject properties = schema.optJSONObject("properties");
            if (properties != null) {
                for (Iterator<String> keys = properties.keys(); keys.hasNext();) {
                    String name = keys.next();
                    JSONObject field = properties.getJSONObject(name);
                    String type = field.getString("type");
                    if (!scalarParameter(type) || field.has("x-mcp-header")
                            || !PARAMETER_NAME.matcher(name).matches() || PRIVATE_PARAMETER.matcher(name).find()) continue;
                    summary.put(name, new JSONObject().put("type", type).put("required", required.remove(name)));
                }
            }
            String result = summary.toString();
            return required.isEmpty() && !tooLarge(result, MAX_PARAMETER_SUMMARY) ? result : "";
        } catch (McpClient.Failure | org.json.JSONException invalid) {
            return "";
        }
    }

    private static boolean scalarParameter(String type) {
        return switch (type) {
            case "string", "boolean", "number", "integer" -> true;
            default -> false;
        };
    }

    public void bind(Service expected, McpClient.Tool tool, String alias) throws Failure {
        if (expected == null || tool == null) throw error(Code.INVALID, "请选择已发现的 MCP 工具");
        Binding binding = new Binding(alias, expected.id(), tool.name());
        validate(binding);
        edit(state -> {
            Registered current = authorized(state, expected);
            if (!tool.equals(current.tools().get(tool.name()))) {
                throw error(Code.REVIEW_TOOL, "工具定义已变化，请重新发现并确认");
            }
            Bound prior = state.bindings().get(alias);
            if (prior != null && !prior.binding().serviceId().equals(binding.serviceId())) {
                throw error(Code.INVALID, "该命令已绑定其他 MCP 服务");
            }
            state.bindings().put(alias, new Bound(binding, tool.fingerprint()));
        });
    }

    public String exportJson() throws Failure {
        State state = read();
        try {
            JSONArray services = new JSONArray();
            for (Registered item : state.services().values()) services.put(serviceJson(item.service()));
            JSONArray bindings = new JSONArray();
            for (Bound item : state.bindings().values()) bindings.put(bindingJson(item.binding()));
            String exported = document(services, bindings).toString();
            if (tooLarge(exported, MAX_JSON)) throw error(Code.INVALID, "导出文件超过 128 KiB");
            return exported;
        } catch (org.json.JSONException failed) {
            throw error(Code.STORAGE, "MCP 配置无法导出");
        }
    }

    public ImportPreview previewImport(String json) throws Failure {
        try {
            JSONObject root = strictObject(json, MAX_JSON);
            fields(root, "version", "services", "bindings");
            version(root);
            JSONArray services = root.getJSONArray("services");
            JSONArray bindings = root.getJSONArray("bindings");
            if (services.length() > MAX_SERVICES || bindings.length() > MAX_BINDINGS) throw new IOException();
            List<Service> importedServices = new ArrayList<>();
            for (int i = 0; i < services.length(); i++) {
                JSONObject item = services.getJSONObject(i);
                fields(item, "id", "label", "endpoint", "protocol");
                importedServices.add(serviceFrom(item));
            }
            List<Binding> importedBindings = new ArrayList<>();
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject item = bindings.getJSONObject(i);
                fields(item, "alias", "serviceId", "toolName");
                importedBindings.add(bindingFrom(item));
            }
            ImportPreview preview = new ImportPreview(importedServices, importedBindings);
            verifyPreview(preview);
            return preview;
        } catch (Failure failure) {
            throw failure;
        } catch (Exception invalid) {
            throw error(Code.INVALID, "MCP 导入文件格式无效或包含不支持的字段");
        }
    }

    public void applyImport(ImportPreview expected) throws Failure {
        verifyPreview(expected);
        edit(state -> {
            for (Service service : expected.services()) storeDisarmed(state, service, "", UUID.randomUUID().toString());
            for (Binding binding : expected.bindings()) {
                Bound prior = state.bindings().get(binding.alias());
                if (prior != null && !prior.binding().serviceId().equals(binding.serviceId())) {
                    throw error(Code.INVALID, "导入命令与现有服务的命令冲突");
                }
                state.bindings().put(binding.alias(), new Bound(binding, ""));
            }
        });
    }

    public String execute(Binding expected, String arguments, long deadlineNanos) throws Failure {
        validate(expected);
        JSONObject input = toolArguments(arguments);
        ensureTime(deadlineNanos);
        Execution snapshot = admitted(read(), expected);
        Registered registered = snapshot.registered();
        return call(registered, snapshot.tool(), arguments, input, deadlineNanos, () -> {
            Execution next = admitted(read(), expected);
            requireSameGrant(registered, next.registered());
            if (!next.tool().equals(snapshot.tool())) throw reviewTool();
        });
    }

    /** Runs a manual command only against the binding and grant selected in that turn. */
    public String executeSelected(BindingSelection expected, String arguments, long deadlineNanos) throws Failure {
        if (expected == null || !file.equals(expected.registry)) throw reviewTool();
        JSONObject input = toolArguments(arguments);
        ensureTime(deadlineNanos);
        Execution snapshot = admittedSelection(read(), expected);
        return call(snapshot.registered(), snapshot.tool(), arguments, input, deadlineNanos,
                () -> admittedSelection(read(), expected));
    }

    /** Executes the exact tool selected in the current package after per-call user confirmation. */
    public String executePackageTool(PackageEntry entry, PackageTool selected, String arguments, long deadlineNanos)
            throws Failure {
        JSONObject input = toolArguments(arguments);
        ensureTime(deadlineNanos);
        Registered snapshot = admittedPackage(entry);
        McpClient.Tool tool = admittedPackageTool(snapshot, selected);
        return call(snapshot, tool, arguments, input, deadlineNanos, () -> {
            Registered current = admittedPackage(entry);
            requireSameGrant(snapshot, current);
            admittedPackageTool(current, selected);
        });
    }

    private String call(Registered registered, McpClient.Tool tool, String arguments, JSONObject input,
                        long deadlineNanos, McpClient.Admission recheck) throws Failure {
        Service service = registered.service();
        String token = decrypt(registered);
        rejectSecret(token, arguments, input.toString());
        McpClient.Admission admission = () -> {
            ensureTime(deadlineNanos);
            recheck.check();
        };
        String result;
        try {
            admission.check();
            result = rpc.call(service.endpoint(), service.protocol(), token, tool, input, deadlineNanos, admission);
        } catch (McpClient.Failure failure) {
            if (failure.kind() == McpClient.Kind.TOOL_CHANGED) disarmChangedTool(registered, tool);
            throw remote(failure);
        } catch (IOException | RuntimeException failure) {
            throw remote(failure);
        }
        rejectSecret(token, result);
        if (tooLarge(result, MAX_RESULT)) throw error(Code.NETWORK, "MCP 工具回复过大");
        return result;
    }

    private Registered admittedPackage(PackageEntry expected) throws Failure {
        Registered snapshot = expected == null ? null : read().services().get(expected.name());
        if (snapshot == null || !snapshot.grant().equals(expected.grant)) {
            throw error(Code.CONFIRMATION_REQUIRED, "MCP 服务授权已变化，请重新选择");
        }
        return snapshot;
    }

    private static McpClient.Tool admittedPackageTool(Registered registered, PackageTool selected) throws Failure {
        if (selected == null || !registered.service().id().equals(selected.serviceId)
                || !registered.grant().equals(selected.grant)
                || !selected.tool.equals(registered.tools().get(selected.name()))) throw reviewTool();
        return selected.tool;
    }

    private static JSONObject toolArguments(String arguments) throws Failure {
        try { return strictObject(arguments, MAX_ARGUMENT); }
        catch (Exception invalid) { throw error(Code.INVALID, "工具参数必须是 16 KiB 以内的 JSON 对象"); }
    }

    private static Failure reviewTool() {
        return error(Code.REVIEW_TOOL, "工具定义已变化，请重新发现并确认");
    }

    private void disarmChangedTool(Registered snapshot, McpClient.Tool tool) throws Failure {
        Service expected = snapshot.service();
        edit(state -> {
            Registered current = state.services().get(expected.id());
            if (current == null || !sameTarget(expected, current.service())
                    || !current.grant().equals(snapshot.grant())
                    || !tool.equals(current.tools().get(tool.name()))) return;
            Map<String, McpClient.Tool> tools = new LinkedHashMap<>(current.tools());
            tools.remove(tool.name());
            state.services().put(expected.id(), new Registered(current.service(), current.ciphertext(), current.grant(), tools,
                    current.revision()));
            state.bindings().replaceAll((alias, bound) ->
                    bound.binding().serviceId().equals(expected.id()) && bound.binding().toolName().equals(tool.name())
                            ? new Bound(bound.binding(), "") : bound);
        });
    }

    private static void storeDisarmed(State state, Service service, String ciphertext, String revision) {
        state.services().put(service.id(), new Registered(service, ciphertext, "", Collections.emptyMap(), revision));
        state.bindings().replaceAll((alias, bound) ->
                service.id().equals(bound.binding().serviceId()) ? new Bound(bound.binding(), "") : bound);
    }

    private static boolean available(State state, Bound bound) {
        if (bound.fingerprint().isEmpty()) return false;
        Registered service = state.services().get(bound.binding().serviceId());
        if (service == null || service.grant().isEmpty()) return false;
        McpClient.Tool tool = service.tools().get(bound.binding().toolName());
        return tool != null && tool.fingerprint().equals(bound.fingerprint());
    }

    private static Execution admitted(State state, Binding expected) throws Failure {
        Bound bound = state.bindings().get(expected.alias());
        if (bound == null || !bound.binding().equals(expected) || !available(state, bound)) {
            throw error(Code.REVIEW_TOOL, "MCP 工具未授权或已变更，请重新发现并确认");
        }
        Registered service = state.services().get(expected.serviceId());
        return new Execution(service, bound, service.tools().get(expected.toolName()));
    }

    private static Execution admittedSelection(State state, BindingSelection expected) throws Failure {
        Execution current = admitted(state, expected.binding());
        if (!current.bound().equals(expected.bound) || !current.registered().grant().equals(expected.grant)) {
            throw reviewTool();
        }
        return current;
    }

    private static Failure deleteChanged() {
        return error(Code.CONFIRMATION_REQUIRED, "服务配置或状态已变化，请重新核对后再删除");
    }

    private static Registered current(State state, Service expected) throws Failure {
        Registered current = expected == null ? null : state.services().get(expected.id());
        if (current == null || !sameTarget(expected, current.service())) {
            throw error(Code.CONFIRMATION_REQUIRED, "MCP 服务地址或协议已改变，请重新确认");
        }
        return current;
    }

    private static Registered authorized(State state, Service expected) throws Failure {
        Registered current = current(state, expected);
        if (current.grant().isEmpty()) throw error(Code.CONFIRMATION_REQUIRED, "请先确认 MCP 服务地址和权限");
        return current;
    }

    private static void requireSameGrant(Registered expected, Registered current) throws Failure {
        if (!sameTarget(expected.service(), current.service()) || !expected.grant().equals(current.grant())) {
            throw error(Code.CONFIRMATION_REQUIRED, "MCP 服务授权已变化，请重新确认");
        }
    }

    private static boolean sameTarget(Service a, Service b) {
        return a.id().equals(b.id()) && a.endpoint().equals(b.endpoint()) && a.protocol().equals(b.protocol());
    }

    private static void validate(Service service) throws Failure {
        if (service == null || service.id() == null || !ID.matcher(service.id()).matches()
                || service.label() == null || service.label().trim().isEmpty() || service.label().length() > 80
                || service.label().chars().anyMatch(Character::isISOControl)) {
            throw error(Code.INVALID, "填写有效的 MCP 服务标识和名称");
        }
        if (!McpClient.LEGACY.equals(service.protocol()) && !McpClient.MODERN.equals(service.protocol())) {
            throw error(Code.PROTOCOL, "MCP 协议版本不受支持");
        }
        try { AiExtensionHttp.exactEndpoint(service.endpoint()); }
        catch (IllegalArgumentException invalid) { throw error(Code.INVALID, invalid.getMessage()); }
    }

    private static void validate(Binding binding) throws Failure {
        if (binding == null || binding.alias() == null || !ALIAS.matcher(binding.alias()).matches()
                || binding.serviceId() == null || !ID.matcher(binding.serviceId()).matches()
                || binding.toolName() == null || !TOOL.matcher(binding.toolName()).matches()) {
            throw error(Code.INVALID, "填写有效的 MCP 命令、服务标识与工具名");
        }
    }

    private static void verifyPreview(ImportPreview preview) throws Failure {
        if (preview == null || preview.services().size() > MAX_SERVICES || preview.bindings().size() > MAX_BINDINGS) {
            throw error(Code.INVALID, "MCP 导入条目过多或格式无效");
        }
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (Service service : preview.services()) {
            validate(service);
            if (!ids.add(service.id())) throw error(Code.INVALID, "MCP 服务标识重复");
        }
        java.util.Set<String> aliases = new java.util.HashSet<>();
        for (Binding binding : preview.bindings()) {
            validate(binding);
            if (!ids.contains(binding.serviceId()) || !aliases.add(binding.alias())) {
                throw error(Code.INVALID, "MCP 命令重复或引用了缺失的服务");
            }
        }
    }

    private static Map<String, McpClient.Tool> verifiedTools(List<McpClient.Tool> discovered, String token) throws Failure {
        if (discovered == null || discovered.size() > MAX_TOOLS) throw error(Code.PROTOCOL, "MCP 工具目录过大");
        Map<String, McpClient.Tool> tools = new LinkedHashMap<>();
        for (McpClient.Tool tool : discovered) {
            if (tool == null || tool.name() == null || !TOOL.matcher(tool.name()).matches()
                    || tooLarge(tool.title(), 256) || tooLarge(tool.description(), 4096)
                    || tooLarge(tool.inputSchema(), MAX_ARGUMENT) || tooLarge(tool.fingerprint(), 128)
                    || tool.fingerprint().isEmpty() || tools.put(tool.name(), tool) != null) {
                throw error(Code.PROTOCOL, "MCP 工具目录格式无效");
            }
            rejectSecret(token, tool.name(), tool.title(), tool.description(), tool.inputSchema(), tool.fingerprint());
        }
        return tools;
    }

    private static void rejectSecret(String token, String... values) throws Failure {
        if (token.isEmpty()) return;
        for (String value : values) {
            if (value != null && value.contains(token)) throw error(Code.CREDENTIAL, "MCP 数据包含凭据，已拦截");
        }
    }

    private String encrypt(Service service, String token) throws Failure {
        try { return credentials.seal(SCOPE, service.id(), service.endpoint(), token); }
        catch (GeneralSecurityException failed) { throw error(Code.CREDENTIAL, "本机暂时无法保存 MCP 凭据"); }
    }

    private String decrypt(Registered registered) throws Failure {
        Service service = registered.service();
        try { return credentials.open(SCOPE, service.id(), service.endpoint(), registered.ciphertext()); }
        catch (GeneralSecurityException failed) { throw error(Code.CREDENTIAL, "无法读取 MCP 凭据，请重新登记"); }
    }

    private State read() throws Failure {
        try (InputStream input = Files.newInputStream(file)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int n;
            while ((n = input.read(buffer)) != -1) {
                if (n > MAX_STORE - bytes.size()) throw new IOException();
                bytes.write(buffer, 0, n);
            }
            JSONObject document = strictObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8), MAX_STORE);
            version(document);
            State state = new State();
            JSONArray services = document.getJSONArray("services");
            JSONArray bindings = document.getJSONArray("bindings");
            if (services.length() > MAX_SERVICES || bindings.length() > MAX_BINDINGS) throw new IOException();
            for (int i = 0; i < services.length(); i++) {
                JSONObject item = services.getJSONObject(i);
                Service service = serviceFrom(item);
                validate(service);
                String ciphertext = text(item, "ciphertext");
                String grant = text(item, "grant");
                if (ciphertext.length() > 4096 || grant.length() > 36) throw new IOException();
                String revision = item.has(REGISTRATION_REVISION) ? text(item, REGISTRATION_REVISION) : "";
                if (item.has(REGISTRATION_REVISION) && !UUID.fromString(revision).toString().equals(revision)) throw new IOException();
                JSONArray raw = item.getJSONArray("tools");
                if (raw.length() > MAX_TOOLS) throw new IOException();
                List<McpClient.Tool> tools = new ArrayList<>();
                for (int j = 0; j < raw.length(); j++) tools.add(toolFrom(raw.getJSONObject(j)));
                if (state.services().put(service.id(), new Registered(service, ciphertext, grant, verifiedTools(tools, ""),
                        revision)) != null) {
                    throw new IOException();
                }
            }
            for (int i = 0; i < bindings.length(); i++) {
                JSONObject item = bindings.getJSONObject(i);
                Binding binding = bindingFrom(item);
                validate(binding);
                String fingerprint = text(item, "fingerprint");
                if (fingerprint.length() > 128 || !state.services().containsKey(binding.serviceId())
                        || state.bindings().put(binding.alias(), new Bound(binding, fingerprint)) != null) throw new IOException();
            }
            return state;
        } catch (NoSuchFileException absent) {
            return new State();
        } catch (Exception failure) {
            throw error(Code.STORAGE, "MCP 配置无法读取");
        }
    }

    private void edit(Edit edit) throws Failure {
        synchronized (WRITE_LOCK) {
            Path directory = file.getParent();
            Path staged = null;
            try {
                Files.createDirectories(directory);
                try (FileChannel channel = FileChannel.open(directory.resolve("mcp-registry.lock"),
                        StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
                    State state = read();
                    edit.apply(state);
                    if (state.services().size() > MAX_SERVICES || state.bindings().size() > MAX_BINDINGS) {
                        throw error(Code.INVALID, "MCP 服务或工具绑定超过数量上限");
                    }
                    byte[] bytes = stored(state).toString().getBytes(StandardCharsets.UTF_8);
                    if (bytes.length > MAX_STORE) throw error(Code.STORAGE, "MCP 配置超过本地容量上限");
                    staged = Files.createTempFile(directory, "mcp-registry-", ".tmp");
                    try (FileOutputStream stream = new FileOutputStream(staged.toFile())) {
                        stream.write(bytes);
                        stream.getFD().sync();
                    }
                    Files.move(staged, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Failure failure) {
                throw failure;
            } catch (Exception failed) {
                throw error(Code.STORAGE, "MCP 配置无法保存");
            } finally {
                if (staged != null) {
                    try { Files.deleteIfExists(staged); }
                    catch (IOException ignored) { }
                }
            }
        }
    }

    private static JSONObject stored(State state) throws org.json.JSONException {
        JSONArray services = new JSONArray();
        for (Registered item : state.services().values()) {
            JSONArray tools = new JSONArray();
            for (McpClient.Tool tool : item.tools().values()) {
                tools.put(new JSONObject().put("name", tool.name()).put("title", tool.title())
                        .put("description", tool.description()).put("schema", tool.inputSchema()).put("fingerprint", tool.fingerprint()));
            }
            JSONObject row = serviceJson(item.service()).put("ciphertext", item.ciphertext())
                    .put("grant", item.grant()).put("tools", tools);
            if (!item.revision().isEmpty()) row.put(REGISTRATION_REVISION, item.revision());
            services.put(row);
        }
        JSONArray bindings = new JSONArray();
        for (Bound item : state.bindings().values()) bindings.put(bindingJson(item.binding()).put("fingerprint", item.fingerprint()));
        return document(services, bindings);
    }

    private static JSONObject document(JSONArray services, JSONArray bindings) throws org.json.JSONException {
        return new JSONObject().put("version", VERSION).put("services", services).put("bindings", bindings);
    }

    private static JSONObject serviceJson(Service service) throws org.json.JSONException {
        return new JSONObject().put("id", service.id()).put("label", service.label())
                .put("endpoint", service.endpoint()).put("protocol", service.protocol());
    }

    private static Service serviceFrom(JSONObject json) throws Exception {
        return new Service(text(json, "id"), text(json, "label"), text(json, "endpoint"), text(json, "protocol"));
    }

    private static JSONObject bindingJson(Binding binding) throws org.json.JSONException {
        return new JSONObject().put("alias", binding.alias()).put("serviceId", binding.serviceId()).put("toolName", binding.toolName());
    }

    private static Binding bindingFrom(JSONObject json) throws Exception {
        return new Binding(text(json, "alias"), text(json, "serviceId"), text(json, "toolName"));
    }

    private static McpClient.Tool toolFrom(JSONObject json) throws Exception {
        return new McpClient.Tool(text(json, "name"), text(json, "title"), text(json, "description"),
                text(json, "schema"), text(json, "fingerprint"));
    }

    private static String text(JSONObject json, String name) throws Exception {
        Object value = json.get(name);
        if (!(value instanceof String)) throw new IOException();
        return (String) value;
    }

    private static void fields(JSONObject json, String... allowed) throws IOException {
        if (json.length() != allowed.length) throw new IOException();
        for (String name : allowed) if (!json.has(name)) throw new IOException();
    }

    private static void version(JSONObject json) throws Exception {
        Object number = json.get("version");
        if (!(number instanceof Long) || ((Long) number) != VERSION) throw new IOException();
    }

    private static JSONObject strictObject(String json, int limit) throws Exception {
        if (tooLarge(json, limit)) throw new IOException();
        try (JsonReader reader = new JsonReader(new StringReader(json))) {
            reader.setLenient(false);
            Object value = strictValue(reader, 0);
            if (!(value instanceof JSONObject) || reader.peek() != JsonToken.END_DOCUMENT) throw new IOException();
            return (JSONObject) value;
        }
    }

    private static Object strictValue(JsonReader reader, int depth) throws Exception {
        if (depth > 32) throw new IOException();
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = reader.nextName();
                    if (object.has(name) || object.length() >= 4096) throw new IOException();
                    object.put(name, strictValue(reader, depth + 1));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) {
                    if (array.length() >= 4096) throw new IOException();
                    array.put(strictValue(reader, depth + 1));
                }
                reader.endArray();
                yield array;
            }
            case STRING -> reader.nextString();
            case BOOLEAN -> reader.nextBoolean();
            case NULL -> { reader.nextNull(); yield JSONObject.NULL; }
            case NUMBER -> readNumber(reader.nextString());
            default -> throw new IOException();
        };
    }

    private static Number readNumber(String text) throws IOException {
        if (!text.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw new IOException();
        if (text.indexOf('.') < 0 && text.indexOf('e') < 0 && text.indexOf('E') < 0) return Long.valueOf(text);
        double value = Double.parseDouble(text);
        if (!Double.isFinite(value)) throw new IOException();
        return value;
    }

    private static boolean tooLarge(String text, int limit) {
        return text == null || text.length() > limit || text.getBytes(StandardCharsets.UTF_8).length > limit;
    }

    private static void ensureTime(long deadline) throws Failure {
        if (Thread.currentThread().isInterrupted()) throw error(Code.CANCELLED, "MCP 请求已停止");
        if (deadline - System.nanoTime() <= 0) throw error(Code.NETWORK, "MCP 请求超时");
    }

    private static Failure remote(Throwable failure) {
        if (failure instanceof Failure known) return known;
        if (failure instanceof McpClient.Failure known) {
            if (known.resultUnknown()) return new Failure(Code.NETWORK, "MCP 请求结果未知，请先核实", true);
            return switch (known.kind()) {
                case PROTOCOL -> error(Code.PROTOCOL, "MCP 服务与所选 Streamable HTTP 协议版本不兼容");
                case AUTHENTICATION -> error(Code.CREDENTIAL, "MCP 服务拒绝当前凭据");
                case SCHEMA -> error(Code.INVALID, "工具参数不符合已核对的 MCP 规则");
                case TOOL_CHANGED -> error(Code.REVIEW_TOOL, "MCP 工具定义已变化，请重新发现并确认");
                case UNSAFE_RESPONSE -> error(Code.CREDENTIAL, "MCP 回复包含凭据或不支持的内容，已拦截");
                case CANCELLED -> error(Code.CANCELLED, "MCP 请求已停止");
                case DEADLINE -> error(Code.NETWORK, "MCP 请求超时");
                default -> error(Code.NETWORK, "MCP 服务暂时无法完成请求");
            };
        }
        return error(Code.NETWORK, "MCP 服务暂时无法完成请求");
    }

    private static Failure error(Code code, String message) { return new Failure(code, message, false); }
}
