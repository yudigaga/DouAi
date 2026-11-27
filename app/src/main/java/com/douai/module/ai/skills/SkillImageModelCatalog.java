package com.douai.module.ai.skills;

import android.util.JsonReader;
import android.util.JsonToken;
import android.util.MalformedJsonException;

import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.AiExtensionHttp;
import com.douai.module.diagnostics.DouAiLog;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** One user-requested OpenAI-compatible model catalogue GET. */
public final class SkillImageModelCatalog {
    private static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final int MAX_ROWS = 4096;
    private static final int DEADLINE_SECONDS = 12;
    private static final Pattern LEGACY_VERSION_BASE = Pattern.compile(".*/v[0-9]+");

    public enum Code {
        INVALID_ENDPOINT, DIFFERENT_ORIGIN, CATALOG_REQUIRED, CREDENTIAL_REQUIRED, REDIRECT, AUTHENTICATION,
        UNSUPPORTED, EMPTY, INVALID_RESPONSE, UNSAFE_RESPONSE, DEADLINE, CANCELLED, TRANSPORT
    }

    public static final class Failure extends IOException {
        private final Code code;

        private Failure(Code code) {
            super(message(code));
            this.code = code;
        }

        public Code code() { return code; }

        private static String message(Code code) {
            switch (code) {
                case INVALID_ENDPOINT: return "请提供有效且独立的 HTTPS 模型列表地址";
                case DIFFERENT_ORIGIN: return "模型列表地址须与生图接口使用相同的 HTTPS 主机和端口";
                case CATALOG_REQUIRED: return "无法从此生图地址推导模型列表，请填写模型列表地址";
                case CREDENTIAL_REQUIRED: return "请填写此生图服务的有效密钥";
                case REDIRECT: return "模型列表返回重定向，请填写可直接访问的同源地址";
                case AUTHENTICATION: return "模型列表认证失败，请核对密钥和服务权限";
                case UNSUPPORTED: return "服务未提供可用的模型列表，可手动填写模型名称";
                case EMPTY: return "服务返回空模型列表，可手动填写模型名称";
                case INVALID_RESPONSE: return "无法识别模型列表，可手动填写模型名称";
                case UNSAFE_RESPONSE: return "模型列表包含凭据，已丢弃结果";
                case DEADLINE: return "获取模型列表超时，可重试或手动填写模型名称";
                case CANCELLED: return "已取消获取模型列表";
                default: return "无法获取模型列表，请核对服务后重试";
            }
        }
    }

    @FunctionalInterface interface Transport {
        Reply request(String endpoint, String method, Map<String, String> headers, byte[] body,
                      long deadline, int maximum, AiExtensionHttp.ResponseReader<Reply> reader) throws IOException;
    }

    record Reply(List<String> models, Code error) { }
    private record Catalog(List<String> models, int rows) { }

    private final Transport transport;

    public SkillImageModelCatalog() { this(AiExtensionHttp::request); }

    SkillImageModelCatalog(Transport transport) { this.transport = transport; }

    public static String validateOverride(String generationEndpoint, String override) throws Failure {
        String explicit = trimmed(override);
        if (explicit.isEmpty()) return "";
        URI generation = generationEndpoint(generationEndpoint);
        URI catalog = endpoint(explicit);
        if (!generation.getHost().equalsIgnoreCase(catalog.getHost()) || port(generation) != port(catalog)) {
            throw failure(Code.DIFFERENT_ORIGIN);
        }
        if (SkillImageProviderAddress.withoutTrailingSlash(generation.getPath()).equals(
                SkillImageProviderAddress.withoutTrailingSlash(catalog.getPath()))) throw failure(Code.INVALID_ENDPOINT);
        return explicit;
    }

    public static String resolveEndpoint(String generationEndpoint, String override) throws Failure {
        String explicit = validateOverride(generationEndpoint, override);
        if (!explicit.isEmpty()) return explicit;
        URI generation = generationEndpoint(generationEndpoint);
        String path = SkillImageProviderAddress.withoutTrailingSlash(generation.getRawPath());
        String prefix = path;
        if (path.endsWith(SkillImageProviderAddress.GENERATION_SUFFIX)) {
            prefix = path.substring(0, path.length() - SkillImageProviderAddress.GENERATION_SUFFIX.length());
        } else if (!path.isEmpty() && !LEGACY_VERSION_BASE.matcher(path).matches()) {
            throw failure(Code.CATALOG_REQUIRED);
        }
        if (prefix.isEmpty() && port(generation) == 443 && "dzhsmartai.com".equalsIgnoreCase(generation.getHost())) {
            // DZHSmartAI's documented catalogue is versioned even for a root image binding.
            prefix = "/v1";
        }
        // Older settings could store the API base as the exact generation binding. Only the lookup target is derived.
        return endpoint("https://" + generation.getRawAuthority() + prefix + "/models").toString();
    }

    /** The caller resolves the catalogue against the exact generation endpoint before choosing its credential. */
    public List<String> fetch(String resolvedModelsEndpoint, String bearer) throws Failure {
        URI catalog = endpoint(resolvedModelsEndpoint);
        if (!AiCredentialVault.validImageBearer(bearer)) throw failure(Code.CREDENTIAL_REQUIRED);
        return fetch(catalog, bearer);
    }

    /** The video caller already scoped the optional credential and resolved its same-origin catalogue. */
    List<String> fetchVideoCatalogue(String resolvedModelsEndpoint, String bearer) throws Failure {
        URI catalog = endpoint(resolvedModelsEndpoint);
        if (bearer == null || !bearer.isEmpty() && !AiCredentialVault.validBearer(bearer)) {
            throw failure(Code.CREDENTIAL_REQUIRED);
        }
        return fetch(catalog, bearer);
    }

    private List<String> fetch(URI catalog, String bearer) throws Failure {
        if (AiCredentialVault.imageEndpointContainsCredential(catalog.toString(), bearer)) throw failure(Code.INVALID_ENDPOINT);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json");
        if (!bearer.isEmpty()) headers.put("Authorization", "Bearer " + bearer);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(DEADLINE_SECONDS);
        DouAiLog.Operation trace = DouAiLog.current();
        Reply reply;
        try {
            stage(trace, "catalog_http_dispatch", AiExtensionHttp.imageRouteEvidence("GET", catalog)
                    + ";route=" + routeKind(catalog));
            reply = transport.request(catalog.toString(), "GET", Collections.unmodifiableMap(headers), null,
                    deadline, MAX_RESPONSE_BYTES, (status, ignored, body) -> {
                        if (status < 100 || status > 599) {
                            stage(trace, "catalog_transport_failure", "kind=TRANSPORT;phase=STATUS_UNAVAILABLE");
                            return error(Code.TRANSPORT);
                        }
                        stage(trace, "catalog_http_response", "status=" + status);
                        Reply parsed = readReply(status, body, bearer);
                        if (status == 200) stage(trace, "catalog_parse_outcome",
                                "outcome=" + (parsed.error() == null ? "ok" : parsed.error().name()));
                        return parsed;
                    });
        } catch (AiExtensionHttp.Failure failure) {
            String status = failure.httpStatus() == 0 ? "" : ";http_status=" + failure.httpStatus();
            stage(trace, "catalog_transport_failure", "kind=" + failure.kind.name()
                    + ";phase=" + failure.phase().name() + ";transport_reason=" + failure.transportReason().name() + status);
            switch (failure.kind) {
                case DEADLINE: throw failure(Code.DEADLINE);
                case CANCELLED: throw failure(Code.CANCELLED);
                case RESPONSE_LIMIT: throw failure(Code.INVALID_RESPONSE);
                default: throw failure(Code.TRANSPORT);
            }
        } catch (IOException unavailable) {
            stage(trace, "catalog_transport_failure", "kind=" + AiExtensionHttp.Kind.TRANSPORT.name());
            throw failure(Code.TRANSPORT);
        }
        if (reply.error() != null) throw failure(reply.error());
        return reply.models();
    }

    private static String routeKind(URI catalog) {
        String path = catalog.getRawPath();
        if ("/models".equals(path)) return "root_models";
        if ("/v1/models".equals(path)) return "v1_models";
        return path.endsWith("/models") ? "prefixed_models" : "custom";
    }

    private static Reply readReply(int status, InputStream body, String bearer) throws IOException {
        if (status >= 300 && status < 400) return error(Code.REDIRECT);
        if (status == 401 || status == 403) return error(Code.AUTHENTICATION);
        if (status == 204) return error(Code.EMPTY);
        if (status != 200) return error(Code.UNSUPPORTED);
        try {
            Catalog catalog = readCatalog(body, bearer);
            if (catalog == null) return error(Code.INVALID_RESPONSE);
            if (catalog.models().isEmpty()) return error(catalog.rows() == 0 ? Code.EMPTY : Code.INVALID_RESPONSE);
            return new Reply(catalog.models(), null);
        } catch (Failure invalid) {
            return error(invalid.code());
        } catch (MalformedJsonException | EOFException | CharacterCodingException | IllegalStateException invalid) {
            return error(Code.INVALID_RESPONSE);
        }
    }

    private static Catalog readCatalog(InputStream body, String bearer) throws IOException {
        try (JsonReader json = new JsonReader(new InputStreamReader(body, StandardCharsets.UTF_8.newDecoder()))) {
            json.setLenient(false);
            json.beginObject();
            Catalog data = null, models = null;
            boolean seenData = false, seenModels = false;
            while (json.hasNext()) {
                String name = json.nextName();
                if ("data".equals(name)) {
                    if (seenData) throw failure(Code.INVALID_RESPONSE);
                    seenData = true;
                    data = readEntries(json, bearer);
                } else if ("models".equals(name)) {
                    if (seenModels) throw failure(Code.INVALID_RESPONSE);
                    seenModels = true;
                    models = readEntries(json, bearer);
                } else {
                    json.skipValue();
                }
            }
            json.endObject();
            if (json.peek() != JsonToken.END_DOCUMENT) throw failure(Code.INVALID_RESPONSE);
            return data == null ? models : data;
        }
    }

    private static Catalog readEntries(JsonReader json, String bearer) throws IOException {
        if (json.peek() != JsonToken.BEGIN_ARRAY) {
            json.skipValue();
            return null;
        }
        Set<String> values = new LinkedHashSet<>();
        int rows = 0;
        json.beginArray();
        while (json.hasNext()) {
            if (++rows > MAX_ROWS) throw failure(Code.INVALID_RESPONSE);
            String id = readId(json);
            if (id == null) continue;
            if (AiCredentialVault.containsImageCredential(id, bearer)) throw failure(Code.UNSAFE_RESPONSE);
            if (usableId(id)) values.add(id);
        }
        json.endArray();
        return new Catalog(Collections.unmodifiableList(new ArrayList<>(values)), rows);
    }

    private static String readId(JsonReader json) throws IOException {
        if (json.peek() != JsonToken.BEGIN_OBJECT) {
            json.skipValue();
            return null;
        }
        String id = null, name = null;
        boolean seenId = false, nullId = false;
        int names = 0;
        json.beginObject();
        while (json.hasNext()) {
            switch (json.nextName()) {
                case "id":
                    if (seenId) throw failure(Code.INVALID_RESPONSE);
                    seenId = true;
                    JsonToken token = json.peek();
                    nullId = token == JsonToken.NULL;
                    if (token == JsonToken.STRING) id = json.nextString();
                    else json.skipValue();
                    break;
                case "name":
                    if (++names == 1 && json.peek() == JsonToken.STRING) name = json.nextString();
                    else json.skipValue();
                    break;
                default:
                    json.skipValue();
                    break;
            }
        }
        json.endObject();
        if (seenId && !nullId) return id;
        if (names > 1) throw failure(Code.INVALID_RESPONSE);
        return name;
    }

    private static boolean usableId(String id) {
        if (id.isEmpty() || id.length() > 256 || id.indexOf('\\') >= 0) return false;
        int first = id.codePointAt(0), last = id.codePointBefore(id.length());
        return !Character.isSpaceChar(first) && !Character.isSpaceChar(last) && id.codePoints().noneMatch(c ->
                Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                        || Character.getType(c) == Character.SURROGATE);
    }

    private static URI endpoint(String value) throws Failure {
        try { return AiExtensionHttp.exactEndpoint(trimmed(value)); }
        catch (IllegalArgumentException invalid) { throw failure(Code.INVALID_ENDPOINT); }
    }

    private static URI generationEndpoint(String value) throws Failure {
        URI verified = endpoint(value);
        if (SkillImageProviderAddress.isNonImageOperation(verified.toString())) throw failure(Code.INVALID_ENDPOINT);
        return verified;
    }

    private static int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }

    private static String trimmed(String value) { return value == null ? "" : value.trim(); }

    private static Reply error(Code code) { return new Reply(null, code); }

    private static Failure failure(Code code) { return new Failure(code); }

    private static void stage(DouAiLog.Operation trace, String name, String evidence) {
        if (trace != null) trace.stage(name, evidence);
    }
}
