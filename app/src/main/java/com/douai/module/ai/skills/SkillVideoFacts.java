package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, offline facts copied from explicit video API documentation. */
public final class SkillVideoFacts {
    private static final int MAX_NOTES = 16_000;
    private static final int MAX_STATES = 12;
    private static final int MAX_RESPONSE_DEPTH = 4;
    private static final Pattern LINE = Pattern.compile("^\\s*([-*]\\s+)?([^:：]{1,48})[:：]\\s*(.*?)\\s*$");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern STATE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private static final Pattern SECRET = Pattern.compile("(?i)(?:^|[\\s{,?&;])[\"']?"
            + "(?:authorization|proxy-authorization|cookie|set-cookie|x[-_ ]?auth[-_ ]?token|(?:x[-_ ]?)?api[-_ ]?key"
            + "|access[-_ ]?token|refresh[-_ ]?token|client[-_ ]?secret|secret|password|token|密钥|访问令牌|凭据|密码)"
            + "[\"']?\\s*[:=：]\\s*\\S");
    private static final String INVALID = "视频接口事实格式无效，请核对方法、路径和字段";
    private static final String SECRET_NOTES = "请移除接口说明中的密钥、凭据或授权头";

    private enum Fact {
        LABEL("label", ""),
        SUBMIT("submitEndpoint", "提交 POST 的公网 HTTPS 地址"),
        SUBMIT_ID("submitIdField", "提交响应任务 ID 字段"),
        STATUS("statusPath", "查询 GET 路径（含 {id}）"),
        STATUS_ID("statusIdField", "查询响应任务 ID 字段"),
        STATE("stateField", "状态字段"),
        CANCEL("cancelPath", "取消路径（含 {id}）"),
        CANCEL_METHOD("cancelMethod", "取消方式（POST 或 DELETE）"),
        DOWNLOAD("downloadPath", "下载 GET 路径（含 {id}）");

        final String key;
        final String missing;
        Fact(String key, String missing) { this.key = key; this.missing = missing; }
    }

    private final Map<Fact, String> facts;
    private final Map<String, String> request;
    private final Map<String, String> states;
    private final String declarations;
    private final List<SkillManifest.ConfigField> config;

    private SkillVideoFacts(Map<Fact, String> facts, Map<String, String> request, Map<String, String> states,
                           String declarations, List<SkillManifest.ConfigField> config) {
        this.facts = Collections.unmodifiableMap(new EnumMap<>(facts));
        this.request = Collections.unmodifiableMap(new LinkedHashMap<>(request));
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        this.declarations = declarations;
        this.config = config;
        checkOrigins();
        String id = facts.get(Fact.STATUS_ID);
        String state = facts.get(Fact.STATE);
        if (id != null && state != null && (id.equals(state) || id.startsWith(state + ".")
                || state.startsWith(id + "."))) throw invalid();
    }

    public static SkillVideoFacts empty() {
        return new SkillVideoFacts(new EnumMap<>(Fact.class), Collections.emptyMap(), Collections.emptyMap(),
                "[]", Collections.emptyList());
    }

    public static SkillVideoFacts parse(String notes) {
        if (notes == null) return empty();
        if (notes.length() > MAX_NOTES) throw invalid();
        if (notes.trim().isEmpty()) return empty();
        checkSecrets(notes);
        String[] lines = notes.split("\\R", -1);
        if (lines.length > 128) throw invalid();
        Map<Fact, String> facts = new EnumMap<>(Fact.class);
        Map<String, String> request = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, JSONObject> fields = new LinkedHashMap<>();
        for (String line : lines) {
            Matcher item = LINE.matcher(line);
            if (!item.matches()) continue;
            String label = item.group(2).toLowerCase(Locale.ROOT).replaceAll("[\\s_-]", "");
            String value = item.group(3).trim();
            if (value.isEmpty()) continue;
            switch (label) {
                case "名称", "服务名称", "label", "name" -> put(facts, Fact.LABEL, checked(Fact.LABEL, value));
                case "提交", "提交接口", "提交地址", "submit" -> put(facts, Fact.SUBMIT, endpoint(operation(value, "POST")));
                case "提示词字段", "提示词参数", "promptfield", "prompt" -> {
                    String key = field(value);
                    if (request.containsValue("argument") && !"argument".equals(request.get(key))) throw invalid();
                    put(request, key, "argument");
                }
                case "必填文本字段", "requiredtextfield" -> readConfig(value, "text", true, fields, request);
                case "选填文本字段", "optionaltextfield" -> readConfig(value, "text", false, fields, request);
                case "必填整数字段", "requiredintegerfield" -> readConfig(value, "integer", true, fields, request);
                case "选填整数字段", "optionalintegerfield" -> readConfig(value, "integer", false, fields, request);
                case "提交响应id", "提交任务id", "submitid", "submitidfield", "submitresponseid" ->
                        put(facts, Fact.SUBMIT_ID, responsePath(value));
                case "查询", "查询接口", "状态查询", "status", "statusroute" ->
                        put(facts, Fact.STATUS, route(operation(value, "GET")));
                case "查询响应id", "查询任务id", "statusid", "statusidfield", "statusresponseid" ->
                        put(facts, Fact.STATUS_ID, responsePath(value));
                case "状态字段", "statefield", "statusfield" -> put(facts, Fact.STATE, responsePath(value));
                case "状态映射", "states", "statemap", "statusmap" -> readStates(value, states);
                case "取消", "取消接口", "cancel" -> {
                    String method = value.regionMatches(true, 0, "DELETE", 0, 6) ? "DELETE" : "POST";
                    put(facts, Fact.CANCEL, route(operation(value, method)));
                    put(facts, Fact.CANCEL_METHOD, method);
                }
                case "下载", "下载接口", "download" -> put(facts, Fact.DOWNLOAD, route(operation(value, "GET")));
                default -> { /* Only recognized, verified facts are retained. */ }
            }
        }
        JSONArray declared = new JSONArray(fields.values());
        return new SkillVideoFacts(facts, request, states, declared.toString(), validateConfig(declared));
    }

    public static SkillVideoFacts fromJson(JSONObject stored) {
        if (stored == null || stored.length() == 0) return empty();
        JSONObject object = strict(stored.toString());
        if (!Integer.valueOf(1).equals(object.opt("version"))) throw invalid();
        Map<Fact, String> facts = new EnumMap<>(Fact.class);
        for (Fact fact : Fact.values()) {
            if (object.has(fact.key)) facts.put(fact, checked(fact, string(object.opt(fact.key))));
        }
        int expected = 1 + facts.size();
        Map<String, String> request = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        JSONArray fields = null;
        if (object.has("request")) {
            readRequest(object.optJSONObject("request"), request);
            expected++;
        }
        if (object.has("states")) {
            readStateObject(object.optJSONObject("states"), states);
            expected++;
        }
        if (object.has("configFields")) {
            fields = object.optJSONArray("configFields");
            if (fields == null) throw invalid();
            expected++;
        }
        if (object.length() != expected) throw invalid();
        List<SkillManifest.ConfigField> validated = validateConfig(fields);
        for (String source : request.values()) {
            if (!source.equals("argument") && validated.stream().noneMatch(field -> source.equals("config." + field.key()))) {
                throw invalid();
            }
        }
        return new SkillVideoFacts(facts, request, states, fields == null ? "[]" : fields.toString(), validated);
    }

    /** Copies the validated shape and suggests the separately approved endpoint. */
    public static SkillVideoFacts fromDeclaration(SkillManifest manifest, String configuredEndpoint) {
        if (manifest == null || manifest.outputType() != SkillResult.Type.VIDEO || manifest.http() == null) throw invalid();
        SkillHttpDeclaration http = manifest.http();
        SkillHttpDeclaration.Video video = http.video();
        if (video == null) throw invalid();
        JSONObject stored = new JSONObject();
        JSONObject storedStates = new JSONObject();
        video.states().forEach((raw, state) -> put(storedStates, raw, state.name().toLowerCase(Locale.ROOT)));
        put(stored, "version", 1);
        put(stored, Fact.LABEL.key, http.label());
        put(stored, Fact.SUBMIT.key, endpoint(configuredEndpoint));
        put(stored, Fact.SUBMIT_ID.key, http.responseField());
        put(stored, Fact.STATUS.key, video.statusPath());
        put(stored, Fact.STATUS_ID.key, video.idField());
        put(stored, Fact.STATE.key, video.stateField());
        put(stored, Fact.CANCEL.key, video.cancelPath());
        put(stored, Fact.CANCEL_METHOD.key, video.cancelMethod());
        put(stored, Fact.DOWNLOAD.key, video.downloadPath());
        put(stored, "states", storedStates);
        put(stored, "request", new JSONObject(http.request()));
        put(stored, "configFields", manifest.toJson().optJSONArray("configFields"));
        return fromJson(stored);
    }

    public JSONObject toJson() {
        JSONObject stored = new JSONObject();
        put(stored, "version", 1);
        facts.forEach((key, value) -> put(stored, key.key, value));
        if (!request.isEmpty()) put(stored, "request", new JSONObject(request));
        if (!states.isEmpty()) put(stored, "states", new JSONObject(states));
        if (!config.isEmpty()) put(stored, "configFields", configFields());
        return stored;
    }

    public SkillVideoFacts merge(SkillVideoFacts newer) {
        if (newer == null || newer.isEmpty()) return this;
        String priorEndpoint = facts.get(Fact.SUBMIT);
        String nextEndpoint = newer.facts.get(Fact.SUBMIT);
        if (priorEndpoint != null && nextEndpoint != null && !priorEndpoint.equals(nextEndpoint)) return newer;
        if (newer.missing().isEmpty()) return newer;
        Map<Fact, String> mergedFacts = new EnumMap<>(Fact.class);
        mergedFacts.putAll(facts);
        mergedFacts.putAll(newer.facts);
        Map<String, String> mergedStates = new LinkedHashMap<>(states);
        mergedStates.putAll(newer.states);
        if (mergedStates.size() > MAX_STATES) throw invalid();
        Map<String, String> mergedRequest = new LinkedHashMap<>(request);
        if (newer.request.containsValue("argument")) mergedRequest.values().removeIf("argument"::equals);
        mergedRequest.putAll(newer.request);
        if (mergedRequest.size() > 7) throw invalid();
        Map<String, JSONObject> mergedFields = new LinkedHashMap<>();
        indexConfig(configFields(), mergedFields);
        indexConfig(newer.configFields(), mergedFields);
        JSONArray declarations = new JSONArray(mergedFields.values());
        return new SkillVideoFacts(mergedFacts, mergedRequest, mergedStates, declarations.toString(),
                validateConfig(declarations));
    }

    public boolean isEmpty() { return facts.isEmpty() && request.isEmpty() && states.isEmpty(); }

    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        for (Fact fact : Fact.values()) {
            if (fact != Fact.LABEL && !facts.containsKey(fact)) missing.add(fact.missing);
        }
        if (!request.containsValue("argument")) missing.add("提示词字段");
        if (!states.containsValue("completed")) missing.add("状态映射中的完成状态");
        return Collections.unmodifiableList(missing);
    }

    public JSONArray configFields() {
        try { return new JSONArray(declarations); }
        catch (JSONException malformed) { throw invalid(); }
    }

    public JSONObject declaration() {
        List<String> missing = missing();
        if (!missing.isEmpty()) throw new IllegalArgumentException("视频接口事实缺少 " + String.join("、", missing));
        JSONObject video = new JSONObject();
        put(video, "statusPath", path(facts.get(Fact.STATUS)));
        put(video, "cancelPath", path(facts.get(Fact.CANCEL)));
        put(video, "cancelMethod", facts.get(Fact.CANCEL_METHOD));
        put(video, "downloadPath", path(facts.get(Fact.DOWNLOAD)));
        put(video, "idField", facts.get(Fact.STATUS_ID));
        put(video, "stateField", facts.get(Fact.STATE));
        put(video, "states", new JSONObject(states));
        JSONObject declaration = new JSONObject();
        put(declaration, "version", SkillHttpDeclaration.VERSION);
        put(declaration, "label", facts.getOrDefault(Fact.LABEL, "视频服务"));
        put(declaration, "suggestedEndpoint", facts.get(Fact.SUBMIT));
        put(declaration, "request", new JSONObject(request));
        put(declaration, "response", facts.get(Fact.SUBMIT_ID));
        put(declaration, "video", video);
        return SkillHttpDeclaration.parse(declaration, SkillResult.Type.VIDEO, config).toJson();
    }

    private void checkOrigins() {
        String endpoint = facts.get(Fact.SUBMIT);
        String origin = endpoint == null ? null : authority(endpoint);
        for (Fact operation : new Fact[]{Fact.STATUS, Fact.CANCEL, Fact.DOWNLOAD}) {
            String route = facts.get(operation);
            if (route == null || !route.startsWith("https://")) continue;
            String actual = route.substring(0, route.indexOf('/', 8));
            if (origin != null && !origin.equals(actual)) {
                throw new IllegalArgumentException("视频接口路径必须与提交地址同源并使用相同端口");
            }
            origin = actual;
        }
    }

    private static String checked(Fact fact, String value) {
        return switch (fact) {
            case LABEL -> {
                if (value.length() > 48 || value.contains("://") || value.codePoints()
                        .anyMatch(c -> Character.isISOControl(c) || Character.getType(c) == Character.FORMAT
                                || c >= 0xd800 && c <= 0xdfff)) throw invalid();
                yield value;
            }
            case SUBMIT -> endpoint(value);
            case SUBMIT_ID, STATUS_ID, STATE -> responsePath(value);
            case STATUS, CANCEL, DOWNLOAD -> route(value);
            case CANCEL_METHOD -> {
                if (!value.equals("POST") && !value.equals("DELETE")) throw invalid();
                yield value;
            }
        };
    }

    private static String operation(String value, String expectedMethod) {
        String[] words = value.split("\\s+", 2);
        if (words.length != 2 || !words[0].equalsIgnoreCase(expectedMethod) || words[1].isEmpty()) throw invalid();
        return words[1];
    }

    private static String endpoint(String value) {
        try { return SkillCustomService.validateEndpoint(value); }
        catch (SkillCustomService.Failure invalid) { throw invalid(); }
    }

    private static String authority(String endpoint) { return "https://" + URI.create(endpoint).getRawAuthority(); }

    private static String path(String route) { return route.startsWith("/") ? route : route.substring(route.indexOf('/', 8)); }

    private static String route(String value) {
        String origin = "";
        if (!value.startsWith("/")) {
            if (!value.regionMatches(true, 0, "https://", 0, 8)) throw invalid();
            int slash = value.indexOf('/', 8);
            if (slash < 0) throw invalid();
            origin = authority(endpoint(value.substring(0, slash)));
            value = value.substring(slash);
        }
        if (value.length() > 320 || value.startsWith("//")) throw invalid();
        int placeholders = 0;
        String[] parts = value.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("{id}")) placeholders++;
            else if (!(part.isEmpty() && i == parts.length - 1)
                    && (!SEGMENT.matcher(part).matches() || part.equals(".") || part.equals(".."))) throw invalid();
        }
        if (placeholders != 1) throw invalid();
        return origin + value;
    }

    private static String responsePath(String value) {
        if (value.startsWith("{")) {
            List<String> parts = new ArrayList<>();
            Object next = strict(value);
            while (next instanceof JSONObject object && object.length() == 1 && parts.size() < MAX_RESPONSE_DEPTH) {
                String field = object.keys().next();
                parts.add(field(field));
                next = object.opt(field);
            }
            if (!(next instanceof String)) throw invalid();
            value = String.join(".", parts);
        }
        String[] parts = value.split("\\.", -1);
        if (parts.length > MAX_RESPONSE_DEPTH) throw invalid();
        for (String part : parts) field(part);
        return value;
    }

    private static String field(String value) {
        String normalized = value.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (!FIELD.matcher(value).matches() || normalized.endsWith("apikey")
                || List.of("authorization", "cookie", "setcookie", "password", "credential", "secret",
                        "token", "accesstoken", "refreshtoken", "clientsecret", "bearer", "headers").contains(normalized)) {
            throw invalid();
        }
        return value;
    }

    private static void readRequest(JSONObject object, Map<String, String> request) {
        if (object == null || object.length() > 7) throw invalid();
        for (Iterator<String> names = object.keys(); names.hasNext();) {
            String name = field(names.next());
            String source = string(object.opt(name));
            if (!source.equals("argument")) {
                if (!source.startsWith("config.")) throw invalid();
                field(source.substring(7));
            }
            request.put(name, source);
        }
    }

    private static void readConfig(String value, String type, boolean required, Map<String, JSONObject> fields,
                                   Map<String, String> request) {
        String[] pieces = value.split("\\s+", 2);
        if (pieces.length != 2) throw invalid();
        String name = field(pieces[0]);
        JSONObject candidate = new JSONObject();
        put(candidate, "key", name);
        put(candidate, "label", pieces[1]);
        put(candidate, "type", type);
        put(candidate, "required", required);
        JSONObject prior = fields.put(name, candidate);
        if (prior != null && !prior.toString().equals(candidate.toString())) throw invalid();
        put(request, name, "config." + name);
    }

    private static void indexConfig(JSONArray declarations, Map<String, JSONObject> fields) {
        for (int index = 0; index < declarations.length(); index++) {
            JSONObject declaration = declarations.optJSONObject(index);
            fields.put(declaration.optString("key"), declaration);
        }
    }

    private static void readStates(String value, Map<String, String> states) {
        if (value.startsWith("{")) {
            readStateObject(strict(value), states);
            return;
        }
        for (String part : value.split("[,，;；]", -1)) {
            String[] pair = part.trim().split("\\s*(?:->|→|=|:|：)\\s*", -1);
            if (pair.length != 2) throw invalid();
            putState(states, pair[0], pair[1]);
        }
    }

    private static void readStateObject(JSONObject object, Map<String, String> states) {
        if (object == null) throw invalid();
        for (Iterator<String> names = object.keys(); names.hasNext();) {
            String raw = names.next();
            putState(states, raw, string(object.opt(raw)));
        }
    }

    private static void putState(Map<String, String> states, String raw, String mapped) {
        if (!STATE.matcher(raw).matches()) throw invalid();
        String state = switch (mapped.toLowerCase(Locale.ROOT)) {
            case "queued", "排队", "排队中" -> "queued";
            case "running", "运行", "运行中", "生成中" -> "running";
            case "completed", "完成", "已完成", "成功" -> "completed";
            case "failed", "失败" -> "failed";
            case "cancelled", "canceled", "取消", "已取消" -> "cancelled";
            default -> throw invalid();
        };
        put(states, raw, state);
        if (states.size() > MAX_STATES) throw invalid();
    }

    private static List<SkillManifest.ConfigField> validateConfig(JSONArray fields) {
        if (fields == null || fields.length() == 0) return Collections.emptyList();
        JSONObject shell = strict("""
                {"schemaVersion":4,"name":"配置校验","description":"配置校验","documentation":"配置校验",
                 "instructions":"配置校验","capabilityId":"text_model","configFields":[],
                 "inputs":[{"key":"argument","type":"text","required":true,"maxLength":8}],
                 "outputType":"text","trial":{"input":"示例","expectedType":"text"}}
                """);
        put(shell, "configFields", fields);
        return SkillManifest.parse(shell.toString()).configFields();
    }

    private static JSONObject strict(String value) {
        checkSecrets(value);
        try { return SkillAuthoring.readObject(value, MAX_NOTES, 192); }
        catch (IllegalArgumentException malformed) { throw invalid(); }
    }

    private static void checkSecrets(String value) {
        if (value == null || value.length() > MAX_NOTES) throw invalid();
        try {
            SkillAuthoring.rejectCredential(value);
            if (SECRET.matcher(value).find()) throw invalid();
        } catch (IllegalArgumentException secret) {
            throw new IllegalArgumentException(SECRET_NOTES);
        }
    }

    private static String string(Object value) {
        if (!(value instanceof String text) || text.trim().isEmpty()) throw invalid();
        return text;
    }

    private static <K> void put(Map<K, String> map, K key, String value) {
        String prior = map.put(key, value);
        if (prior != null && !prior.equals(value)) throw invalid();
    }

    private static void put(JSONObject object, String key, Object value) {
        try { object.put(key, value); }
        catch (JSONException malformed) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID); }
}
