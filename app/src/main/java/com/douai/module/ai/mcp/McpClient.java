package com.douai.module.ai.mcp;

import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.ai.AiExtensionHttp;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, explicitly versioned HTTPS Streamable HTTP client for admitted tool calls. */
public final class McpClient {
    public static final String LEGACY = "2025-11-25";
    public static final String MODERN = "2026-07-28";

    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_ARGUMENT_BYTES = 32 * 1024;
    private static final int MAX_TEXT = 16 * 1024;
    private static final int MAX_DEPTH = 16;
    private static final int MAX_PAGES = 8;
    private static final int MAX_TOOLS = 100;
    private static final long MAX_HEADER_INTEGER = 9_007_199_254_740_991L;
    private static final String VERSION_HEADER = "MCP-Protocol-Version";
    private static final String SESSION_HEADER = "MCP-Session-Id";
    private static final Pattern AUTH_TOKEN = Pattern.compile("[A-Za-z0-9._~+/=-]{1,8192}");
    private static final Pattern JSON_ESCAPES = Pattern.compile("\\\\(?:u([0-9a-fA-F]{4})|([\"\\\\/]))");
    private static final Pattern HEADER_NAME = Pattern.compile("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,64}");

    public record Tool(String name, String title, String description, String inputSchema, String fingerprint) { }

    @FunctionalInterface public interface Admission { void check() throws IOException; }

    public enum Kind { PROTOCOL, AUTHENTICATION, SCHEMA, TOOL_CHANGED, UNSAFE_RESPONSE, REMOTE_ERROR, TRANSPORT, DEADLINE, CANCELLED }

    public static final class Failure extends IOException {
        private final Kind kind;
        private final boolean resultUnknown;

        private Failure(Kind kind, boolean resultUnknown) {
            super(message(kind) + (resultUnknown ? "，远端调用结果未知，请先核实" : ""));
            this.kind = kind;
            this.resultUnknown = resultUnknown;
        }

        public Kind kind() { return kind; }
        public boolean resultUnknown() { return resultUnknown; }

        private static String message(Kind kind) {
            switch (kind) {
                case PROTOCOL: return "MCP 端点或回复与所选协议不兼容";
                case AUTHENTICATION: return "MCP 服务拒绝认证，请检查本地凭据与权限";
                case SCHEMA: return "MCP 工具参数无效或采用了未支持的参数结构";
                case TOOL_CHANGED: return "MCP 工具已变化或不可用，请重新发现并授权";
                case UNSAFE_RESPONSE: return "MCP 回复包含凭据或未授权的服务端交互";
                case REMOTE_ERROR: return "MCP 服务返回工具调用错误";
                case DEADLINE: return "MCP 请求超时";
                case CANCELLED: return "MCP 请求已取消";
                default: return "MCP 服务连接或读取失败";
            }
        }
    }

    interface Transport {
        Response request(String endpoint, String method, Map<String, String> headers, byte[] body,
                         long deadline, int maximum, AiExtensionHttp.ResponseReader<Response> reader) throws IOException;
    }

    record Response(JSONObject result, String session, Failure failure) { }

    private final Transport transport;

    public McpClient() { this(AiExtensionHttp::request); }

    McpClient(Transport transport) { this.transport = transport; }

    public List<Tool> discover(String endpoint, String protocol, String bearer, long deadlineNanos) throws IOException {
        try (Session session = new Session(endpoint, protocol, bearer, deadlineNanos)) {
            session.connect();
            return session.tools();
        }
    }

    public String call(String endpoint, String protocol, String bearer, Tool expected, JSONObject arguments,
                       long deadlineNanos, Admission admission) throws IOException {
        if (expected == null || admission == null) throw failure(Kind.TOOL_CHANGED, false);
        JSONObject args = copyArguments(arguments);
        if (containsSecret(args, bearer)) throw failure(Kind.SCHEMA, false);
        try (Session session = new Session(endpoint, protocol, bearer, deadlineNanos)) {
            session.connect();
            Tool fresh = null;
            for (Tool candidate : session.tools()) if (candidate.name().equals(expected.name())) fresh = candidate;
            if (fresh == null || !fresh.fingerprint().equals(expected.fingerprint())) throw failure(Kind.TOOL_CHANGED, false);
            JSONObject schema = parseObject(fresh.inputSchema(), Kind.SCHEMA);
            Map<String, String> parameterHeaders = new LinkedHashMap<>();
            validateSchema(schema, args, session.modern, parameterHeaders, new HashSet<>(), 0, false);
            if (containsSecret(args, session.sessionId)) throw failure(Kind.SCHEMA, false);
            session.checkActive();
            admission.check();
            JSONObject result = session.rpc("tools/call", json("name", fresh.name(), "arguments", args), parameterHeaders, true);
            return toolOutput(result);
        }
    }

    private final class Session implements AutoCloseable {
        private final String endpoint;
        private final String protocol;
        private final String bearer;
        private final long deadline;
        private final boolean modern;
        private String sessionId = "";

        Session(String endpoint, String protocol, String bearer, long deadline) throws Failure {
            try { AiExtensionHttp.exactEndpoint(endpoint); }
            catch (IllegalArgumentException invalid) { throw failure(Kind.PROTOCOL, false); }
            if (!LEGACY.equals(protocol) && !MODERN.equals(protocol)) throw failure(Kind.PROTOCOL, false);
            if (bearer != null && !bearer.isEmpty() && !AUTH_TOKEN.matcher(bearer).matches()) {
                throw failure(Kind.AUTHENTICATION, false);
            }
            this.endpoint = endpoint;
            this.protocol = protocol;
            this.bearer = bearer == null ? "" : bearer;
            this.deadline = deadline;
            this.modern = MODERN.equals(protocol);
        }

        void connect() throws IOException {
            JSONObject result;
            if (modern) {
                result = rpc("server/discover", new JSONObject());
                JSONArray versions = result.optJSONArray("supportedVersions");
                boolean pinned = false;
                if (versions != null) for (int i = 0; i < versions.length(); i++) pinned |= MODERN.equals(versions.opt(i));
                if (!pinned) throw failure(Kind.PROTOCOL, false);
            } else {
                result = rpc("initialize", json("protocolVersion", LEGACY, "capabilities", new JSONObject(), "clientInfo", clientInfo()));
                if (!LEGACY.equals(result.opt("protocolVersion"))) throw failure(Kind.PROTOCOL, false);
                JSONObject info = result.optJSONObject("serverInfo");
                if (info == null) throw failure(Kind.PROTOCOL, false);
                boundedString(info, "name", 256, false, Kind.PROTOCOL);
                boundedString(info, "version", 128, false, Kind.PROTOCOL);
            }
            JSONObject capabilities = result.optJSONObject("capabilities");
            if (capabilities == null || capabilities.optJSONObject("tools") == null) throw failure(Kind.PROTOCOL, false);
            if (!modern) {
                checkActive();
                exchange("POST", headers("notifications/initialized", null),
                        json("jsonrpc", "2.0", "method", "notifications/initialized"), null, false, false);
            }
        }

        List<Tool> tools() throws IOException {
            List<Tool> result = new ArrayList<>();
            Set<String> names = new HashSet<>();
            Set<String> cursors = new HashSet<>();
            String cursor = "";
            for (int page = 0; page < MAX_PAGES; page++) {
                JSONObject response = rpc("tools/list", cursor.isEmpty() ? new JSONObject() : json("cursor", cursor));
                JSONArray tools = response.optJSONArray("tools");
                if (tools == null || tools.length() > MAX_TOOLS - result.size()) throw failure(Kind.PROTOCOL, false);
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject definition = tools.optJSONObject(i);
                    if (definition == null) throw failure(Kind.PROTOCOL, false);
                    Tool tool = tool(definition);
                    if (!names.add(tool.name())) throw failure(Kind.PROTOCOL, false);
                    result.add(tool);
                }
                if (!response.has("nextCursor")) return Collections.unmodifiableList(result);
                cursor = boundedString(response, "nextCursor", 2048, false, Kind.PROTOCOL);
                if (!cursors.add(cursor)) throw failure(Kind.PROTOCOL, false);
            }
            throw failure(Kind.PROTOCOL, false);
        }

        JSONObject rpc(String method, JSONObject params) throws IOException {
            return rpc(method, params, Collections.emptyMap(), false);
        }

        JSONObject rpc(String method, JSONObject params, Map<String, String> parameterHeaders, boolean paid) throws IOException {
            checkActive();
            String id = UUID.randomUUID().toString();
            if (modern) put(params, "_meta", json("io.modelcontextprotocol/protocolVersion", MODERN,
                    "io.modelcontextprotocol/clientInfo", clientInfo(), "io.modelcontextprotocol/clientCapabilities", new JSONObject()));
            Map<String, String> headers = headers(method, paid ? params.optString("name") : null);
            headers.putAll(parameterHeaders);
            boolean initializing = "initialize".equals(method);
            Response response = exchange("POST", headers, json("jsonrpc", "2.0", "id", id, "method", method, "params", params),
                    id, paid, initializing);
            if (initializing) sessionId = response.session();
            JSONObject result = response.result();
            if (modern) {
                Object type = result.opt("resultType");
                if ("input_required".equals(type) || result.has("inputRequests") || result.has("requestState")) {
                    throw failure(Kind.UNSAFE_RESPONSE, paid);
                }
                if (!"complete".equals(type)) throw failure(Kind.PROTOCOL, paid);
            }
            return result;
        }

        private Map<String, String> headers(String method, String name) {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Accept", "application/json, text/event-stream");
            if (!"DELETE".equals(method)) headers.put("Content-Type", "application/json; charset=utf-8");
            if (!bearer.isEmpty()) headers.put("Authorization", "Bearer " + bearer);
            if (!"initialize".equals(method)) headers.put(VERSION_HEADER, protocol);
            if (!sessionId.isEmpty()) headers.put(SESSION_HEADER, sessionId);
            if (modern) {
                headers.put("Mcp-Method", method);
                if (name != null) headers.put("Mcp-Name", headerValue(name));
            }
            return headers;
        }

        private Response exchange(String verb, Map<String, String> headers, JSONObject request, String id,
                                  boolean paid, boolean initializing) throws IOException {
            byte[] bytes = request == null ? null : request.toString().getBytes(StandardCharsets.UTF_8);
            Response result;
            try {
                result = transport.request(endpoint, verb, Collections.unmodifiableMap(headers), bytes, deadline,
                        MAX_RESPONSE_BYTES, (status, responseHeaders, body) ->
                                read(status, responseHeaders, body, id, paid, initializing, bearer, sessionId));
            } catch (AiExtensionHttp.Failure error) {
                Kind kind = error.kind == AiExtensionHttp.Kind.DEADLINE ? Kind.DEADLINE
                        : error.kind == AiExtensionHttp.Kind.CANCELLED ? Kind.CANCELLED
                        : error.kind == AiExtensionHttp.Kind.RESPONSE_LIMIT ? Kind.PROTOCOL : Kind.TRANSPORT;
                throw failure(kind, paid && error.mayHaveSent);
            } catch (IOException error) {
                throw failure(Kind.TRANSPORT, paid);
            }
            if (result.failure() != null) throw result.failure();
            if (Thread.currentThread().isInterrupted()) throw failure(Kind.CANCELLED, paid);
            if (deadline - System.nanoTime() <= 0) throw failure(Kind.DEADLINE, paid);
            return result;
        }

        void checkActive() throws Failure {
            if (Thread.currentThread().isInterrupted()) throw failure(Kind.CANCELLED, false);
            if (deadline - System.nanoTime() <= 0) throw failure(Kind.DEADLINE, false);
        }

        @Override public void close() {
            if (sessionId.isEmpty() || Thread.currentThread().isInterrupted()) return;
            long now = System.nanoTime();
            long remaining = deadline - now;
            if (remaining <= 0) return;
            long closeDeadline = now + Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(200));
            try {
                transport.request(endpoint, "DELETE", headers("DELETE", null), null, closeDeadline,
                        1024, (status, headers, body) -> new Response(null, "", null));
            } catch (IOException ignored) { /* Session cleanup does not change the result of the operation. */ }
        }
    }

    private static Response read(int status, AiExtensionHttp.Headers headers, InputStream body, String id, boolean paid,
                                 boolean initializing, String bearer, String sessionId) throws IOException {
        try {
            if (status == 401 || status == 403) throw failure(Kind.AUTHENTICATION, paid);
            if (id == null) {
                if (status != 202 || body.read() != -1) throw failure(Kind.PROTOCOL, false);
                return new Response(null, "", null);
            }
            if (status != 200) throw failure(status >= 500 ? Kind.REMOTE_ERROR : Kind.PROTOCOL, paid);
            String type = headers.get("Content-Type");
            if (type == null) throw failure(Kind.PROTOCOL, paid);
            type = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            String minted = initializing ? headers.get(SESSION_HEADER) : "";
            if (minted == null) minted = "";
            if (minted.length() > 1024 || minted.chars().anyMatch(c -> c < 0x21 || c > 0x7e)) {
                throw failure(Kind.PROTOCOL, paid);
            }
            JSONObject result;
            if ("application/json".equals(type)) {
                result = responseMessage(parseObject(AiExtensionHttp.readUtf8(body), Kind.PROTOCOL), id, false);
            } else if ("text/event-stream".equals(type)) {
                result = readSse(body, id);
            } else {
                throw failure(Kind.PROTOCOL, paid);
            }
            if (containsSecret(result, bearer, sessionId, minted)) throw failure(Kind.UNSAFE_RESPONSE, paid);
            return new Response(result, minted, null);
        } catch (Failure error) {
            return new Response(null, "", failure(error.kind(), paid));
        } catch (CharacterCodingException invalid) {
            return new Response(null, "", failure(Kind.PROTOCOL, paid));
        }
    }

    private static JSONObject readSse(InputStream body, String id) throws IOException {
        BufferedReader reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()));
        StringBuilder data = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) {
                if (data.length() != 0) {
                    JSONObject result = responseMessage(parseObject(data.toString(), Kind.PROTOCOL), id, true);
                    if (result != null) return result;
                    data.setLength(0);
                }
            } else if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) value = value.substring(1);
                if (value.isEmpty() && data.length() == 0) continue;
                if (data.length() != 0) data.append('\n');
                data.append(value);
                if (data.length() > MAX_RESPONSE_BYTES) throw failure(Kind.PROTOCOL, false);
            } else if (line.startsWith("event:") && "endpoint".equals(line.substring(6).trim())) {
                throw failure(Kind.PROTOCOL, false);
            }
        }
        throw failure(Kind.PROTOCOL, false);
    }

    private static JSONObject responseMessage(JSONObject message, String id, boolean allowNotification) throws Failure {
        if (!"2.0".equals(message.opt("jsonrpc"))) throw failure(Kind.PROTOCOL, false);
        if (message.has("method")) {
            Object method = message.opt("method");
            if (allowNotification && !message.has("id") && method instanceof String
                    && ((String) method).startsWith("notifications/")) return null;
            throw failure(Kind.UNSAFE_RESPONSE, false);
        }
        if (!id.equals(message.opt("id")) || (message.has("result") == message.has("error"))) {
            throw failure(Kind.PROTOCOL, false);
        }
        if (message.has("error")) {
            JSONObject error = message.optJSONObject("error");
            Object code = error == null ? null : error.opt("code");
            int errorCode = code instanceof Number ? ((Number) code).intValue() : 0;
            throw failure(errorCode == -32022 || errorCode == -32020 || errorCode == -32601 ? Kind.PROTOCOL
                    : errorCode == -32602 ? Kind.SCHEMA : Kind.REMOTE_ERROR, false);
        }
        JSONObject result = message.optJSONObject("result");
        if (result == null) throw failure(Kind.PROTOCOL, false);
        return result;
    }

    private static Tool tool(JSONObject definition) throws Failure {
        String name = boundedString(definition, "name", 128, false, Kind.PROTOCOL);
        String title = boundedString(definition, "title", 256, true, Kind.PROTOCOL);
        String description = boundedString(definition, "description", 2048, true, Kind.PROTOCOL);
        if (name.chars().anyMatch(Character::isISOControl)) throw failure(Kind.PROTOCOL, false);
        JSONObject schema = definition.optJSONObject("inputSchema");
        if (schema == null || schema.toString().length() > MAX_ARGUMENT_BYTES) throw failure(Kind.SCHEMA, false);
        return new Tool(name, title, description, canonical(schema), fingerprint(canonical(definition)));
    }

    private static String toolOutput(JSONObject result) throws Failure {
        Object isError = result.opt("isError");
        if (Boolean.TRUE.equals(isError)) throw failure(Kind.REMOTE_ERROR, false);
        if (isError != null && !Boolean.FALSE.equals(isError)) throw failure(Kind.PROTOCOL, true);
        JSONArray content = result.optJSONArray("content");
        if (result.has("content") && content == null) throw failure(Kind.PROTOCOL, true);
        StringBuilder text = new StringBuilder();
        if (content != null) for (int i = 0; i < content.length(); i++) {
            JSONObject part = content.optJSONObject(i);
            if (part == null || !"text".equals(part.opt("type")) || !(part.opt("text") instanceof String)) {
                throw failure(Kind.PROTOCOL, true);
            }
            if (text.length() != 0) text.append('\n');
            text.append(part.optString("text"));
            if (text.length() > MAX_TEXT) throw failure(Kind.PROTOCOL, true);
        }
        if (text.length() == 0 && result.has("structuredContent")) text.append(canonical(result.opt("structuredContent")));
        if (text.length() == 0 || text.length() > MAX_TEXT) throw failure(Kind.PROTOCOL, true);
        return text.toString();
    }

    private static JSONObject copyArguments(JSONObject arguments) throws Failure {
        if (arguments == null) return new JSONObject();
        String serialized = arguments.toString();
        if (serialized == null || serialized.getBytes(StandardCharsets.UTF_8).length > MAX_ARGUMENT_BYTES) {
            throw failure(Kind.SCHEMA, false);
        }
        return parseObject(serialized, Kind.SCHEMA);
    }

    private static JSONObject parseObject(String text, Kind kind) throws Failure {
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            Object value = readValue(reader, 0);
            if (value instanceof JSONObject && reader.peek() == JsonToken.END_DOCUMENT) return (JSONObject) value;
        } catch (IOException | JSONException | IllegalStateException | NumberFormatException invalid) {
            // Parser errors can include untrusted response text.
        }
        throw failure(kind, false);
    }

    private static Object readValue(JsonReader reader, int depth) throws IOException, JSONException {
        if (depth > MAX_DEPTH) throw new IOException();
        switch (reader.peek()) {
            case BEGIN_OBJECT:
                JSONObject object = new JSONObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException();
                    object.put(key, readValue(reader, depth + 1));
                }
                reader.endObject();
                return object;
            case BEGIN_ARRAY:
                JSONArray array = new JSONArray();
                reader.beginArray();
                while (reader.hasNext()) array.put(readValue(reader, depth + 1));
                reader.endArray();
                return array;
            case STRING:
                return reader.nextString();
            case NUMBER:
                String number = reader.nextString();
                if (number.length() > 128) throw new IOException();
                return new BigDecimal(number);
            case BOOLEAN:
                return reader.nextBoolean();
            case NULL:
                reader.nextNull();
                return JSONObject.NULL;
            default:
                throw new IOException();
        }
    }

    static JSONObject verifiedInputSchema(String raw, boolean modern) throws Failure {
        JSONObject schema = parseObject(raw, Kind.SCHEMA);
        validateSchema(schema, null, modern, new LinkedHashMap<>(), new HashSet<>(), 0, false);
        return schema;
    }

    /** Validates the supported local schema subset before admission; never resolves references. */
    private static void validateSchema(JSONObject schema, Object value, boolean modern, Map<String, String> headers,
                                       Set<String> headerNames, int depth, boolean property) throws Failure {
        if (depth > MAX_DEPTH) throw failure(Kind.SCHEMA, false);
        Object declaredType = schema.opt("type");
        if (!(declaredType instanceof String)) throw failure(Kind.SCHEMA, false);
        String type = (String) declaredType;
        if (depth == 0 && !"object".equals(type)) throw failure(Kind.SCHEMA, false);
        for (String key : sortedKeys(schema)) switch (key) {
            case "type": case "title": case "description": case "default": case "examples": case "deprecated":
            case "$schema": case "enum": case "const": case "x-mcp-header":
                break;
            case "properties": case "required": case "additionalProperties":
                if (!"object".equals(type)) throw failure(Kind.SCHEMA, false);
                break;
            case "items": case "minItems": case "maxItems":
                if (!"array".equals(type)) throw failure(Kind.SCHEMA, false);
                break;
            case "minLength": case "maxLength":
                if (!"string".equals(type)) throw failure(Kind.SCHEMA, false);
                break;
            case "minimum": case "maximum":
                if (!"number".equals(type) && !"integer".equals(type)) throw failure(Kind.SCHEMA, false);
                break;
            default:
                throw failure(Kind.SCHEMA, false);
        }
        Object dialect = schema.opt("$schema");
        if (dialect != null && !"https://json-schema.org/draft/2020-12/schema".equals(dialect)
                && !"http://json-schema.org/draft-07/schema#".equals(dialect)) throw failure(Kind.SCHEMA, false);
        if (modern && schema.has("x-mcp-header")) {
            String name = boundedString(schema, "x-mcp-header", 64, false, Kind.SCHEMA);
            if (!property || !HEADER_NAME.matcher(name).matches() || !headerNames.add(name.toLowerCase(Locale.ROOT))
                    || !("string".equals(type) || "integer".equals(type) || "boolean".equals(type))) {
                throw failure(Kind.SCHEMA, false);
            }
            if (value != null && value != JSONObject.NULL) {
                verifyValueType(type, value);
                String formatted = "integer".equals(type) ? headerInteger((Number) value) : value.toString();
                String encoded = headerValue(formatted);
                if (encoded.length() > 2048 || headers.size() >= 16) throw failure(Kind.SCHEMA, false);
                headers.put("Mcp-Param-" + name, encoded);
            }
        }
        JSONArray options = schema.optJSONArray("enum");
        if (schema.has("enum") && (options == null || options.length() == 0)) throw failure(Kind.SCHEMA, false);
        if (value != null) {
            verifyValueType(type, value);
            if (options != null) {
                boolean allowed = false;
                String encoded = canonical(value);
                for (int i = 0; i < options.length(); i++) allowed |= canonical(options.opt(i)).equals(encoded);
                if (!allowed) throw failure(Kind.SCHEMA, false);
            }
            if (schema.has("const") && !canonical(schema.opt("const")).equals(canonical(value))) throw failure(Kind.SCHEMA, false);
        }
        switch (type) {
            case "object":
                JSONObject object = value instanceof JSONObject ? (JSONObject) value : new JSONObject();
                JSONObject properties = schema.optJSONObject("properties");
                if (properties == null && schema.has("properties")) throw failure(Kind.SCHEMA, false);
                if (properties == null) properties = new JSONObject();
                JSONArray required = schema.optJSONArray("required");
                if (required == null && schema.has("required")) throw failure(Kind.SCHEMA, false);
                Set<String> requiredFields = new HashSet<>();
                if (required != null) for (int i = 0; i < required.length(); i++) {
                    Object field = required.opt(i);
                    if (!(field instanceof String) || !requiredFields.add((String) field)
                            || (value != null && !object.has((String) field))) throw failure(Kind.SCHEMA, false);
                }
                Object additional = schema.opt("additionalProperties");
                if (additional != null && !(additional instanceof Boolean)) throw failure(Kind.SCHEMA, false);
                if (Boolean.FALSE.equals(additional)) {
                    for (String key : sortedKeys(object)) if (!properties.has(key)) throw failure(Kind.SCHEMA, false);
                }
                for (String key : sortedKeys(properties)) {
                    JSONObject child = properties.optJSONObject(key);
                    if (child == null) throw failure(Kind.SCHEMA, false);
                    validateSchema(child, object.opt(key), modern, headers, headerNames, depth + 1, depth == 0 || property);
                }
                break;
            case "array":
                JSONArray array = value instanceof JSONArray ? (JSONArray) value : null;
                JSONObject items = schema.optJSONObject("items");
                if (items == null && schema.has("items")) throw failure(Kind.SCHEMA, false);
                checkBounds(schema, "minItems", "maxItems", array == null ? null : array.length(), true);
                if (items != null) {
                    validateSchema(items, null, modern, headers, headerNames, depth + 1, false);
                    if (array != null) for (int i = 0; i < array.length(); i++) {
                        validateSchema(items, array.opt(i), modern, headers, headerNames, depth + 1, false);
                    }
                }
                break;
            case "string":
                String text = (String) value;
                checkBounds(schema, "minLength", "maxLength", text == null ? null : text.codePointCount(0, text.length()), true);
                break;
            case "integer": case "number":
                checkBounds(schema, "minimum", "maximum", (Number) value, false);
                break;
            case "boolean": case "null":
                break;
            default:
                throw failure(Kind.SCHEMA, false);
        }
    }

    private static void checkBounds(JSONObject schema, String minimum, String maximum, Number value, boolean nonNegativeInteger) throws Failure {
        BigDecimal low = bound(schema.opt(minimum), nonNegativeInteger);
        BigDecimal high = bound(schema.opt(maximum), nonNegativeInteger);
        BigDecimal actual = value == null ? null : decimal(value);
        if ((low != null && high != null && low.compareTo(high) > 0)
                || (actual != null && low != null && actual.compareTo(low) < 0)
                || (actual != null && high != null && actual.compareTo(high) > 0)) {
            throw failure(Kind.SCHEMA, false);
        }
    }

    private static BigDecimal bound(Object value, boolean nonNegativeInteger) throws Failure {
        if (value == null) return null;
        if (!(value instanceof Number)) throw failure(Kind.SCHEMA, false);
        BigDecimal bound = decimal((Number) value);
        if (nonNegativeInteger && (bound.signum() < 0 || bound.stripTrailingZeros().scale() > 0)) {
            throw failure(Kind.SCHEMA, false);
        }
        return bound;
    }

    private static BigDecimal decimal(Number value) { return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString()); }

    private static void verifyValueType(String type, Object value) throws Failure {
        boolean valid;
        switch (type) {
            case "object": valid = value instanceof JSONObject; break;
            case "array": valid = value instanceof JSONArray; break;
            case "string": valid = value instanceof String; break;
            case "integer": valid = value instanceof Number && decimal((Number) value).stripTrailingZeros().scale() <= 0; break;
            case "number": valid = value instanceof Number; break;
            case "boolean": valid = value instanceof Boolean; break;
            case "null": valid = value == JSONObject.NULL; break;
            default: valid = false;
        }
        if (!valid) throw failure(Kind.SCHEMA, false);
    }

    private static String headerInteger(Number value) throws Failure {
        try {
            long integer = decimal(value).longValueExact();
            if (integer >= -MAX_HEADER_INTEGER && integer <= MAX_HEADER_INTEGER) return Long.toString(integer);
        } catch (ArithmeticException | NumberFormatException invalid) { /* Range and integer shape checked here. */ }
        throw failure(Kind.SCHEMA, false);
    }

    private static String headerValue(String value) {
        if (value.equals(value.trim()) && value.chars().allMatch(c -> c == '\t' || (c >= 0x20 && c <= 0x7e))
                && !(value.startsWith("=?base64?") && value.endsWith("?="))) return value;
        return "=?base64?" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)) + "?=";
    }

    private static String boundedString(JSONObject object, String key, int maximum, boolean mayBeEmpty, Kind kind) throws Failure {
        Object value = object.opt(key);
        if (value == null && mayBeEmpty) return "";
        if (!(value instanceof String)) throw failure(kind, false);
        String text = (String) value;
        if (text.length() > maximum || (!mayBeEmpty && text.trim().isEmpty())) throw failure(kind, false);
        return text;
    }

    private static boolean containsSecret(Object value, String... secrets) {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            for (String key : sortedKeys(object)) if (containsSecret(key, secrets) || containsSecret(object.opt(key), secrets)) return true;
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            for (int i = 0; i < array.length(); i++) if (containsSecret(array.opt(i), secrets)) return true;
        } else if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            String text = value instanceof Number ? decimal((Number) value).stripTrailingZeros().toString() : value.toString();
            for (String secret : secrets) if (secret != null && !secret.isEmpty() && text.contains(secret)) return true;
            if (text.indexOf('\\') < 0) return false;
            Matcher matcher = JSON_ESCAPES.matcher(text);
            StringBuffer decoded = new StringBuffer(text.length());
            while (matcher.find()) {
                String hex = matcher.group(1);
                String replacement = hex == null ? matcher.group(2) : String.valueOf((char) Integer.parseInt(hex, 16));
                matcher.appendReplacement(decoded, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(decoded);
            for (String secret : secrets) if (secret != null && !secret.isEmpty() && decoded.indexOf(secret) >= 0) return true;
        }
        return false;
    }

    private static String canonical(Object value) throws Failure {
        if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value;
            StringBuilder json = new StringBuilder("{");
            for (String key : sortedKeys(object)) {
                if (json.length() > 1) json.append(',');
                json.append(JSONObject.quote(key)).append(':').append(canonical(object.opt(key)));
            }
            return json.append('}').toString();
        }
        if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value;
            StringBuilder json = new StringBuilder("[");
            for (int i = 0; i < array.length(); i++) {
                if (i > 0) json.append(',');
                json.append(canonical(array.opt(i)));
            }
            return json.append(']').toString();
        }
        if (value instanceof String) return JSONObject.quote((String) value);
        if (value instanceof Boolean) return value.toString();
        if (value instanceof Number) return decimal((Number) value).stripTrailingZeros().toString();
        return "null";
    }

    private static List<String> sortedKeys(JSONObject object) {
        List<String> keys = new ArrayList<>();
        object.keys().forEachRemaining(keys::add);
        Collections.sort(keys);
        return keys;
    }

    private static String fingerprint(String text) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(bytes.length * 2);
            for (byte value : bytes) {
                hex.append(Character.forDigit(value >> 4 & 0xf, 16));
                hex.append(Character.forDigit(value & 0xf, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static JSONObject clientInfo() { return json("name", "DouAi", "version", "1"); }

    private static JSONObject json(Object... pairs) {
        JSONObject object = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(object, (String) pairs[i], pairs[i + 1]);
        return object;
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException invalid) { throw new IllegalArgumentException("MCP JSON 数据无效"); }
    }

    private static Failure failure(Kind kind, boolean resultUnknown) { return new Failure(kind, resultUnknown); }
}
