package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Bounded, offline facts copied from explicit video API documentation. */
public final class SkillVideoFacts {
    private static final int MAX_NOTES = 16_000;
    private static final int MAX_STATES = 12;
    private static final int MAX_RESPONSE_DEPTH = 4;
    private static final Pattern LINE = Pattern.compile("^\\s*(?:[-*+]|#{1,6})?\\s*([^:：]{1,48})[:：]\\s*(.*?)\\s*$");
    private static final Pattern FIELD = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern CONTENT_TYPE_LINE = Pattern.compile("(?i)^Content-Type\\s*[:：]\\s*([^\\r\\n]+)$");
    private static final Pattern STATE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private static final Pattern SECRET = Pattern.compile("(?i)(?:^|[\\s{,?&;])[\"']?"
            + "(?:authorization|proxy-authorization|cookie|set-cookie|x[-_ ]?auth[-_ ]?token|(?:x[-_ ]?)?api[-_ ]?key"
            + "|access[-_ ]?token|refresh[-_ ]?token|client[-_ ]?secret|secret|password|token|密钥|访问令牌|凭据|密码)"
            + "[\"']?\\s*[:=：]\\s*\\S");
    private static final Pattern SECRET_ASSIGNMENT = Pattern.compile("(?i)(?:^|[\\s;])"
            + "[A-Za-z_][A-Za-z0-9_]*(?:API_KEY|ACCESS_KEY|TOKEN|SECRET|PASSWORD)\\s*=\\s*\\S");
    private static final String INVALID = "视频接口事实格式无效，请核对方法、路径和字段";
    private static final String SECRET_NOTES = "请移除接口说明中的密钥、凭据或授权头";
    private static final String PROMPT = "promptField";
    private static final String STATES = "states";
    private static final String PROVENANCE = "provenance";
    private static final String FIXED_GET = "fixed_get";
    private static final String RESPONSE_URL_GET = "response_url_get";
    private static final String JSON_POST = "json_post";
    private static final Set<String> STATE_NAMES = Set.of("queued", "running", "completed", "failed", "cancelled");
    static final String JSON_ENCODING = "application/json";
    static final String MULTIPART_ENCODING = "multipart/form-data";
    static final String URLENCODED_ENCODING = "application/x-www-form-urlencoded";
    static final String UNKNOWN_ENCODING = "unknown";
    private static final Set<String> ID_FIELDS = Set.of("id", "taskid", "jobid");
    private static final Set<String> STATUS_FIELDS = Set.of("state", "status");
    private static final Set<String> URL_FIELDS = Set.of("videourl", "resulturl", "downloadurl");

    private enum Fact {
        LABEL("label", ""),
        SUBMIT("submitEndpoint", "提交 POST 的公网 HTTPS 地址"),
        SUBMIT_PATH("submitPath", "提交 POST 路径"),
        ENCODING("submitEncoding", "提交请求编码"),
        MIN_POLL("minPollSeconds", ""),
        SUBMIT_ID("submitIdField", "提交响应任务 ID 字段"),
        QUERY_MODE("queryMode", "查询方式"),
        STATUS("statusPath", "查询 GET 路径（含 {id}）"),
        STATUS_URL("statusUrlField", "提交响应的查询地址字段"),
        STATUS_POST("statusPostPath", "查询 POST 路径"),
        STATUS_POST_ID("statusPostIdField", "查询 POST 任务 ID 字段"),
        STATUS_ID("statusIdField", "查询响应任务 ID 字段"),
        STATE("stateField", "状态字段"),
        CANCEL("cancelPath", "取消路径（含 {id}）"),
        CANCEL_METHOD("cancelMethod", "取消方式（POST 或 DELETE）"),
        DOWNLOAD("downloadPath", "下载 GET 路径（含 {id}）"),
        RESULT_URL("resultUrlField", "查询响应成品 HTTPS 地址字段");

        final String key;
        final String missing;
        Fact(String key, String missing) { this.key = key; this.missing = missing; }
    }

    public enum FieldType { TEXT, HTTPS_URL, JSON_FIELD, JSON_PATH, TASK_PATH, STATE_MAP, CANCEL_METHOD }

    public enum Source {
        EMPTY("未填写"), DOCUMENT("文档提取"), REUSED_SKILL("已有技能"), MANUAL("手动填写"), STORED("已保存"),
        CHAT_SUGGESTION("聊天资料 · 待核对");
        private final String label;
        Source(String label) { this.label = label; }
        public String label() { return label; }
    }

    public record FormField(String id, String label, FieldType type, boolean required, String value, Source source) {}

    private record FormSpec(Fact fact, String id, String label, FieldType type, boolean required) {
        FormSpec(Fact fact, String label, FieldType type, boolean required) {
            this(fact, fact.key, label, type, required);
        }
    }

    private static final List<FormSpec> FORM = List.of(
            new FormSpec(Fact.LABEL, "服务名称", FieldType.TEXT, false),
            new FormSpec(Fact.SUBMIT, Fact.SUBMIT.missing, FieldType.HTTPS_URL, true),
            new FormSpec(Fact.SUBMIT_PATH, Fact.SUBMIT_PATH.missing, FieldType.TEXT, false),
            new FormSpec(Fact.ENCODING, Fact.ENCODING.missing, FieldType.TEXT, false),
            new FormSpec(null, PROMPT, "提示词字段", FieldType.JSON_FIELD, true),
            new FormSpec(Fact.SUBMIT_ID, Fact.SUBMIT_ID.missing, FieldType.JSON_PATH, true),
            new FormSpec(Fact.QUERY_MODE, Fact.QUERY_MODE.missing, FieldType.TEXT, false),
            new FormSpec(Fact.STATUS, Fact.STATUS.missing, FieldType.TASK_PATH, true),
            new FormSpec(Fact.STATUS_URL, Fact.STATUS_URL.missing, FieldType.JSON_PATH, false),
            new FormSpec(Fact.STATUS_POST, Fact.STATUS_POST.missing, FieldType.TASK_PATH, false),
            new FormSpec(Fact.STATUS_POST_ID, Fact.STATUS_POST_ID.missing, FieldType.JSON_FIELD, false),
            new FormSpec(Fact.STATUS_ID, Fact.STATUS_ID.missing, FieldType.JSON_PATH, true),
            new FormSpec(Fact.STATE, Fact.STATE.missing, FieldType.JSON_PATH, true),
            new FormSpec(null, STATES, "状态映射", FieldType.STATE_MAP, true),
            new FormSpec(Fact.CANCEL_METHOD, Fact.CANCEL_METHOD.missing, FieldType.CANCEL_METHOD, false),
            new FormSpec(Fact.CANCEL, Fact.CANCEL.missing, FieldType.TASK_PATH, false),
            new FormSpec(Fact.DOWNLOAD, Fact.DOWNLOAD.missing, FieldType.TASK_PATH, false),
            new FormSpec(Fact.RESULT_URL, Fact.RESULT_URL.missing, FieldType.JSON_PATH, false));

    private final Map<Fact, String> facts;
    private final Map<String, String> request;
    private final Map<String, String> states;
    private final String declarations;
    private final List<SkillManifest.ConfigField> config;
    private final Map<String, Source> provenance;
    private static final List<Fact> QUERY_FIELDS = List.of(Fact.STATUS, Fact.STATUS_URL, Fact.STATUS_POST, Fact.STATUS_POST_ID);

    private SkillVideoFacts(Map<Fact, String> facts, Map<String, String> request, Map<String, String> states,
                           String declarations, List<SkillManifest.ConfigField> config,
                           Map<String, Source> provenance, Source defaultSource) {
        this.facts = Collections.unmodifiableMap(new EnumMap<>(facts));
        this.request = Collections.unmodifiableMap(new LinkedHashMap<>(request));
        this.states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        this.declarations = declarations;
        this.config = config;
        Map<String, Source> sources = new LinkedHashMap<>();
        for (FormSpec field : FORM) {
            Source source = provenance.get(field.id());
            if (!formValue(field).isEmpty()) sources.put(field.id(), source == null ? defaultSource : source);
            else if (source == Source.MANUAL) sources.put(field.id(), source);
        }
        if (facts.containsKey(Fact.MIN_POLL)) {
            Source source = provenance.get(Fact.MIN_POLL.key);
            if (source != null && source != Source.MANUAL) throw invalid();
            sources.put(Fact.MIN_POLL.key, Source.MANUAL);
        }
        if (!sources.keySet().containsAll(provenance.keySet())) throw invalid();
        if (facts.containsKey(Fact.QUERY_MODE) && sources.get(Fact.QUERY_MODE.key) != Source.MANUAL) throw invalid();
        this.provenance = Collections.unmodifiableMap(sources);
        checkOrigins();
        String endpoint = facts.get(Fact.SUBMIT);
        String submitPath = facts.get(Fact.SUBMIT_PATH);
        if (endpoint != null && submitPath != null && !submitPath.equals(URI.create(endpoint).getRawPath())) {
            throw new IllegalArgumentException("提交接口地址与已填写的提交路径不同");
        }
        String id = facts.get(Fact.STATUS_ID);
        String state = facts.get(Fact.STATE);
        if (id != null && state != null && (id.equals(state) || id.startsWith(state + ".")
                || state.startsWith(id + "."))) throw invalid();
    }

    public static SkillVideoFacts empty() {
        return new SkillVideoFacts(new EnumMap<>(Fact.class), Collections.emptyMap(), Collections.emptyMap(),
                "[]", Collections.emptyList(), Collections.emptyMap(), Source.EMPTY);
    }

    public static SkillVideoFacts parse(String notes) { return parseNotes(notes, false); }

    public static SkillVideoFacts parseReference(String notes) {
        if (notes == null || !SkillVideoReference.recognizable(notes.substring(0, Math.min(notes.length(), MAX_NOTES)))) {
            return empty();
        }
        return parseNotes(notes, true);
    }

    public static SkillVideoFacts parseSupplement(String notes) {
        if (notes != null && notes.length() <= MAX_NOTES) {
            Matcher header = CONTENT_TYPE_LINE.matcher(notes.trim().replaceFirst("^接口资料\\s*[:：]\\s*", ""));
            if (header.matches()) notes = "提交请求编码: " + header.group(1);
        }
        return parseNotes(notes, true);
    }

    private static SkillVideoFacts parseNotes(String notes, boolean requireEncoding) {
        if (notes == null) return empty();
        if (notes.length() > MAX_NOTES) throw invalid();
        if (notes.trim().isEmpty()) return empty();
        SkillVideoReference.Notes reference = SkillVideoReference.normalize(notes);
        notes = reference.text();
        checkSecrets(notes);
        String[] lines = notes.split("\\R", -1);
        if (lines.length > 128) throw invalid();
        Map<Fact, String> facts = new EnumMap<>(Fact.class);
        Map<String, String> request = new LinkedHashMap<>();
        Map<String, String> states = new LinkedHashMap<>();
        Map<String, JSONObject> fields = new LinkedHashMap<>();
        Map<Fact, String> responseExamples = new EnumMap<>(Fact.class);
        Set<Fact> ambiguousExamples = EnumSet.noneOf(Fact.class);
        String promptExample = "";
        boolean conflictingPrompts = false;
        for (int index = 0; index < lines.length; index++) {
            Matcher item = LINE.matcher(lines[index]);
            if (!item.matches()) continue;
            String label = item.group(1).toLowerCase(Locale.ROOT).replaceAll("[\\s_*`-]", "");
            String value = unquote(item.group(2).trim());
            Example example = Example.find(label);
            if (example != null) {
                if (value.isEmpty() && index + 1 < lines.length && lines[index + 1].trim().matches("```(?i:json)?")) {
                    StringBuilder body = new StringBuilder();
                    index += 2;
                    while (index < lines.length && !lines[index].trim().equals("```")) {
                        body.append(lines[index++]).append('\n');
                    }
                    if (index == lines.length) throw invalid();
                    value = body.toString();
                }
                if (value.isEmpty()) continue;
                JSONObject sample = strict(value);
                if (example == Example.REQUEST) {
                    String found = samplePrompt(sample);
                    if (!found.isEmpty()) {
                        if (!promptExample.isEmpty() && !promptExample.equals(found)) conflictingPrompts = true;
                        promptExample = found;
                    }
                } else {
                    sampleField(responseExamples, ambiguousExamples,
                            example == Example.SUBMIT ? Fact.SUBMIT_ID : Fact.STATUS_ID, sample, ID_FIELDS, null);
                    if (example == Example.STATUS) {
                        sampleField(responseExamples, ambiguousExamples, Fact.STATE, sample, STATUS_FIELDS, null);
                        sampleField(responseExamples, ambiguousExamples, Fact.RESULT_URL, sample, URL_FIELDS,
                                facts.get(Fact.SUBMIT));
                    }
                }
                continue;
            }
            if (value.isEmpty()) continue;
            switch (label) {
                case "名称", "服务名称", "label", "name" -> put(facts, Fact.LABEL, checked(Fact.LABEL, value));
                case "提交", "提交接口", "提交地址", "submit" -> {
                    String address = operation(value, "POST");
                    if (address.startsWith("/")) put(facts, Fact.SUBMIT_PATH, submitPath(address));
                    else put(facts, Fact.SUBMIT, endpoint(address));
                }
                case "提交请求编码", "提交编码", "submitencoding" -> facts.merge(Fact.ENCODING,
                        encoding(value), SkillVideoFacts::mergeEncoding);
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
                case "查询", "查询接口", "状态查询", "status", "statusroute" -> {
                    if (value.regionMatches(true, 0, "POST", 0, 4)) {
                        put(facts, Fact.STATUS_POST, route(operation(value, "POST"), false));
                    } else put(facts, Fact.STATUS, route(operation(value, "GET")));
                }
                case "查询响应地址字段", "查询地址字段", "提交响应查询地址字段", "提交响应的查询地址字段", "statusurlfield" ->
                        put(facts, Fact.STATUS_URL, responsePath(value));
                case "查询post路径", "statuspostpath" -> put(facts, Fact.STATUS_POST, route(value, false));
                case "查询请求id字段", "查询postid字段", "查询post任务id字段", "statuspostidfield" ->
                        put(facts, Fact.STATUS_POST_ID, field(value));
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
                case "查询结果字段", "状态结果字段", "查询结果地址字段", "成品地址字段", "resulturlfield", "statusresultfield" ->
                        put(facts, Fact.RESULT_URL, responsePath(value));
                default -> { /* Only recognized, verified facts are retained. */ }
            }
        }
        if ((requireEncoding || reference.documentedSubmit() || facts.containsKey(Fact.SUBMIT_PATH))
                && (facts.containsKey(Fact.SUBMIT) || facts.containsKey(Fact.SUBMIT_PATH))) {
            facts.putIfAbsent(Fact.ENCODING, UNKNOWN_ENCODING);
        }
        if (!conflictingPrompts && !promptExample.isEmpty() && !request.containsValue("argument")
                && !request.containsKey(promptExample)) request.put(promptExample, "argument");
        if (facts.containsKey(Fact.DOWNLOAD)) responseExamples.remove(Fact.RESULT_URL);
        responseExamples.forEach(facts::putIfAbsent);
        JSONArray declared = new JSONArray(fields.values());
        return new SkillVideoFacts(facts, request, states, declared.toString(), validateConfig(declared),
                Collections.emptyMap(), Source.DOCUMENT);
    }

    public static SkillVideoFacts fromJson(JSONObject stored) {
        return decode(stored, Source.STORED);
    }

    static SkillVideoFacts fromDocument(JSONObject documented) {
        return decode(documented, Source.DOCUMENT);
    }

    public String label() { return facts.getOrDefault(Fact.LABEL, ""); }

    private static SkillVideoFacts decode(JSONObject stored, Source defaultSource) {
        if (stored == null || stored.length() == 0) return empty();
        JSONObject object = strict(stored.toString());
        if (!Integer.valueOf(1).equals(object.opt("version"))) throw invalid();
        Map<Fact, String> facts = new EnumMap<>(Fact.class);
        for (Fact fact : Fact.values()) {
            if (!object.has(fact.key)) continue;
            Object value = object.opt(fact.key);
            if (fact == Fact.MIN_POLL) {
                if (!(value instanceof Integer seconds)) throw invalid();
                facts.put(fact, checkedPollSeconds(seconds));
            } else facts.put(fact, checked(fact, string(value)));
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
        Map<String, Source> sources = Collections.emptyMap();
        if (object.has(PROVENANCE)) {
            sources = readProvenance(object.optJSONObject(PROVENANCE));
            expected++;
        }
        if (object.length() != expected) throw invalid();
        List<SkillManifest.ConfigField> validated = validateConfig(fields);
        for (String source : request.values()) {
            if (!source.equals("argument") && validated.stream().noneMatch(field -> source.equals("config." + field.key()))) {
                throw invalid();
            }
        }
        return new SkillVideoFacts(facts, request, states, fields == null ? "[]" : fields.toString(), validated,
                sources, defaultSource);
    }

    /** Copies the validated shape and suggests the separately approved endpoint. */
    public static SkillVideoFacts fromDeclaration(SkillManifest manifest, String configuredEndpoint) {
        return fromDeclaration(manifest, configuredEndpoint, Source.REUSED_SKILL);
    }

    public static SkillVideoFacts fromDeclaration(SkillManifest manifest, String configuredEndpoint, Source origin) {
        if (manifest == null || manifest.outputType() != SkillResult.Type.VIDEO || manifest.http() == null) throw invalid();
        if (origin != Source.REUSED_SKILL && origin != Source.STORED) throw invalid();
        SkillHttpDeclaration http = manifest.http();
        SkillHttpDeclaration.Video video = http.video();
        if (video == null) throw invalid();
        JSONObject stored = new JSONObject();
        JSONObject storedStates = new JSONObject();
        video.states().forEach((raw, state) -> put(storedStates, raw, state.name().toLowerCase(Locale.ROOT)));
        put(stored, "version", 1);
        put(stored, Fact.LABEL.key, http.label());
        if (origin == Source.REUSED_SKILL || configuredEndpoint != null && !configuredEndpoint.isEmpty()) {
            put(stored, Fact.SUBMIT.key, endpoint(configuredEndpoint));
        }
        put(stored, Fact.ENCODING.key, JSON_ENCODING);
        if (video.minPollSeconds() != 0) put(stored, Fact.MIN_POLL.key, video.minPollSeconds());
        put(stored, Fact.SUBMIT_ID.key, http.responseField());
        if (!video.statusPath().isEmpty()) put(stored, Fact.STATUS.key, video.statusPath());
        if (!video.statusUrlField().isEmpty()) put(stored, Fact.STATUS_URL.key, video.statusUrlField());
        if (!video.statusPostPath().isEmpty()) {
            put(stored, Fact.STATUS_POST.key, video.statusPostPath());
            put(stored, Fact.STATUS_POST_ID.key, video.statusPostIdField());
        }
        put(stored, Fact.STATUS_ID.key, video.idField());
        put(stored, Fact.STATE.key, video.stateField());
        if (video.canCancel()) {
            put(stored, Fact.CANCEL.key, video.cancelPath());
            put(stored, Fact.CANCEL_METHOD.key, video.cancelMethod());
        }
        if (!video.downloadPath().isEmpty()) put(stored, Fact.DOWNLOAD.key, video.downloadPath());
        if (!video.resultUrlField().isEmpty()) put(stored, Fact.RESULT_URL.key, video.resultUrlField());
        put(stored, "states", storedStates);
        put(stored, "request", new JSONObject(http.request()));
        put(stored, "configFields", manifest.toJson().optJSONArray("configFields"));
        return decode(stored, origin);
    }

    public JSONObject toJson() {
        JSONObject stored = new JSONObject();
        put(stored, "version", 1);
        facts.forEach((key, value) -> put(stored, key.key,
                key == Fact.MIN_POLL ? Integer.valueOf(value) : value));
        if (!request.isEmpty()) put(stored, "request", new JSONObject(request));
        if (!states.isEmpty()) put(stored, "states", new JSONObject(states));
        if (!config.isEmpty()) put(stored, "configFields", configFields());
        if (!provenance.isEmpty()) {
            JSONObject sources = new JSONObject();
            provenance.forEach((field, source) -> put(sources, field, source.name()));
            put(stored, PROVENANCE, sources);
        }
        return stored;
    }

    public static SkillVideoFacts fromForm(Map<String, String> values) { return empty().withForm(values); }

    public SkillVideoFacts asChatSuggestion() {
        Map<String, Source> suggested = new LinkedHashMap<>(provenance);
        for (FormSpec field : FORM) {
            if (field.fact() != Fact.QUERY_MODE && !formValue(field).isEmpty()) {
                suggested.put(field.id(), Source.CHAT_SUGGESTION);
            }
        }
        return new SkillVideoFacts(facts, request, states, declarations, config, suggested, Source.STORED);
    }

    public boolean needsSubmissionVerification() {
        Fact effective = facts.containsKey(Fact.SUBMIT) ? Fact.SUBMIT : Fact.SUBMIT_PATH;
        return facts.containsKey(effective) && provenance.get(effective.key) == Source.CHAT_SUGGESTION;
    }

    public SkillVideoFacts applyGroundedPatch(Map<String, String> fields, Map<String, String> stateMappings, String queryMode) {
        return applyGroundedPatch(fields, stateMappings, queryMode, fields == null ? Collections.emptySet() : fields.keySet());
    }

    /** Applies already grounded literals, with existing fields replaceable only where explicitly allowed. */
    public SkillVideoFacts applyGroundedPatch(Map<String, String> fields, Map<String, String> stateMappings,
                                              String queryMode, Set<String> replacements) {
        Map<String, String> edits = fields == null ? Collections.emptyMap() : fields;
        Map<String, String> mappings = stateMappings == null ? Collections.emptyMap() : stateMappings;
        Set<String> overrides = replacements == null ? Collections.emptySet() : replacements;
        if (edits.size() > FORM.size() || mappings.size() > MAX_STATES || !edits.keySet().containsAll(overrides)) throw invalid();
        String selected = queryMode == null || queryMode.isEmpty() ? "" : checkedQueryMode(queryMode);
        String intended = selected.isEmpty() ? queryMode() : selected;
        Map<String, String> changes = new LinkedHashMap<>();
        if (!selected.isEmpty()) changes.put(Fact.QUERY_MODE.key, selected);
        int length = 0;
        for (Map.Entry<String, String> item : edits.entrySet()) {
            FormSpec spec = formSpec(item.getKey());
            if (spec.fact() == Fact.ENCODING || spec.fact() == Fact.QUERY_MODE || STATES.equals(spec.id())) throw invalid();
            String raw = item.getValue();
            if (raw == null || raw.length() > MAX_NOTES - length || raw.trim().isEmpty()) throw invalid();
            length += raw.length();
            checkSecrets(raw);
            String value = spec.fact() == null ? field(raw.trim()) : checked(spec.fact(), raw.trim());
            String routeMode = queryModeFor(spec.fact());
            if (!routeMode.isEmpty()) {
                if (!intended.isEmpty() && !intended.equals(routeMode)) {
                    throw new IllegalArgumentException("请明确选择要修改的查询方式");
                }
                intended = routeMode;
            }
            String prior = formValue(spec);
            if (!prior.isEmpty() && !prior.equals(value)
                    && (spec.fact() == null || !value.equals(facts.get(spec.fact())))
                    && !overrides.contains(spec.id())) {
                throw new IllegalArgumentException("已有接口字段需要明确指定修改");
            }
            changes.put(spec.id(), value);
        }
        Map<String, String> merged = new LinkedHashMap<>(states);
        for (Map.Entry<String, String> mapping : mappings.entrySet()) {
            String raw = mapping.getKey();
            String normalized = mapping.getValue();
            if (raw == null || normalized == null || !STATE_NAMES.contains(normalized)) throw invalid();
            checkSecrets(raw);
            String previous = merged.get(raw);
            if (previous != null && !previous.equals(normalized)) {
                throw new IllegalArgumentException("已有状态映射与新资料冲突，请核对该状态");
            }
            putState(merged, raw, normalized);
        }
        if (!merged.equals(states)) changes.put(STATES, stateLines(merged));
        return withForm(changes);
    }

    public String explicitQueryMode() { return facts.getOrDefault(Fact.QUERY_MODE, ""); }

    public String queryMode() {
        if (!explicitQueryMode().isEmpty()) return explicitQueryMode();
        String inferred = "";
        for (Fact field : QUERY_FIELDS) {
            if (!facts.containsKey(field)) continue;
            String mode = queryModeFor(field);
            if (!inferred.isEmpty() && !inferred.equals(mode)) return "";
            inferred = mode;
        }
        return inferred;
    }

    public SkillVideoFacts withMinimumPollSeconds(int seconds) {
        String value = checkedPollSeconds(seconds);
        if (value.equals(facts.get(Fact.MIN_POLL))) return this;
        Map<Fact, String> changed = new EnumMap<>(Fact.class);
        changed.putAll(facts);
        changed.put(Fact.MIN_POLL, value);
        return new SkillVideoFacts(changed, request, states, declarations, config, provenance, Source.MANUAL);
    }

    public int minimumPollSeconds() {
        return Integer.parseInt(facts.getOrDefault(Fact.MIN_POLL, "0"));
    }

    public SkillVideoFacts withForm(Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) return this;
        if (changes.size() > FORM.size()) throw invalid();
        Map<Fact, String> updatedFacts = new EnumMap<>(Fact.class);
        updatedFacts.putAll(facts);
        Map<String, String> updatedRequest = new LinkedHashMap<>(request);
        Map<String, String> updatedStates = new LinkedHashMap<>(states);
        Map<String, Source> updatedSources = new LinkedHashMap<>(provenance);
        String mode = changes.get(Fact.QUERY_MODE.key);
        if (mode != null && mode.length() > MAX_NOTES) throw invalid();
        if (mode != null && !mode.trim().isEmpty() && !mode.trim().equals(explicitQueryMode())) {
            selectQueryMode(updatedFacts, updatedSources, checkedQueryMode(mode.trim()));
        }
        int length = 0;
        for (Map.Entry<String, String> entry : changes.entrySet()) {
            FormSpec spec = formSpec(entry.getKey());
            String raw = entry.getValue();
            if (raw == null || raw.length() > MAX_NOTES - length) throw invalid();
            length += raw.length();
            checkSecrets(raw);
            String value = raw.trim();
            String prior = formValue(spec);
            if (spec.fact() != null) {
                if (!value.isEmpty()) value = checked(spec.fact(), value);
                if (prior.equals(value)) {
                    if (!value.isEmpty() && submitLocation(spec.fact())
                            && updatedSources.get(spec.id()) == Source.CHAT_SUGGESTION) {
                        updatedSources.put(spec.id(), Source.MANUAL);
                    }
                    continue;
                }
                if (value.isEmpty()) updatedFacts.remove(spec.fact());
                else updatedFacts.put(spec.fact(), value);
            } else if (PROMPT.equals(spec.id())) {
                if (!value.isEmpty()) value = field(value);
                if (prior.equals(value)) continue;
                updatedRequest.values().removeIf("argument"::equals);
                if (!value.isEmpty()) put(updatedRequest, value, "argument");
            } else {
                Map<String, String> replacement = new LinkedHashMap<>();
                if (!value.isEmpty()) readStates(value, replacement);
                if (states.equals(replacement)) continue;
                updatedStates = replacement;
            }
            updatedSources.put(spec.id(), Source.MANUAL);
        }
        if (updatedRequest.size() > 7) throw invalid();
        return new SkillVideoFacts(updatedFacts, updatedRequest, updatedStates, declarations, config,
                updatedSources, Source.MANUAL);
    }

    public List<FormField> formFields() {
        List<FormField> fields = new ArrayList<>();
        for (FormSpec spec : FORM) {
            boolean required = spec.required()
                    || spec.fact() == Fact.CANCEL && facts.containsKey(Fact.CANCEL_METHOD)
                    || spec.fact() == Fact.CANCEL_METHOD && facts.containsKey(Fact.CANCEL)
                    || spec.fact() == Fact.STATUS_POST && facts.containsKey(Fact.STATUS_POST_ID)
                    || spec.fact() == Fact.STATUS_POST_ID && facts.containsKey(Fact.STATUS_POST);
            if (spec.fact() == Fact.SUBMIT && facts.containsKey(Fact.SUBMIT_PATH)) required = false;
            if (spec.fact() == Fact.STATUS && otherStatusMode(Fact.STATUS)) required = false;
            if (!explicitQueryMode().isEmpty() && explicitQueryMode().equals(queryModeFor(spec.fact()))) required = true;
            fields.add(new FormField(spec.id(), spec.label(), spec.type(), required, formValue(spec),
                    provenance.getOrDefault(spec.id(), Source.EMPTY)));
        }
        return Collections.unmodifiableList(fields);
    }

    /** Names accepted for a direct chat edit. Encoding and authorization are evidence or settings only. */
    public static String chatEditableField(String label) {
        for (FormSpec spec : FORM) {
            if (spec.fact() != Fact.ENCODING && spec.fact() != Fact.QUERY_MODE && spec.id().equalsIgnoreCase(label)) {
                return spec.id();
            }
        }
        String normalized = label.replaceAll("[\\p{Z}\\s]", "").toLowerCase(Locale.ROOT)
                .replaceFirst("^(?:当前的?|现有的?|已填写的|这个技能的|视频的|接口的)", "");
        return switch (normalized) {
            case "服务名称", "服务名" -> "label";
            case "提交地址", "提交接口地址", "提交post地址" -> "submitEndpoint";
            case "提交路径", "提交接口路径", "提交post路径" -> "submitPath";
            case "提示词字段", "提示词参数" -> "promptField";
            case "提交响应id", "提交响应id字段", "提交响应任务id字段", "提交任务id字段" -> "submitIdField";
            case "查询路径", "查询接口路径", "查询get路径", "查询路径（含{id}）" -> "statusPath";
            case "查询响应地址字段", "查询地址字段", "提交响应查询地址字段", "提交响应的查询地址字段" -> "statusUrlField";
            case "查询post路径" -> "statusPostPath";
            case "查询请求id字段", "查询postid字段", "查询post任务id字段" -> "statusPostIdField";
            case "查询响应id", "查询响应id字段", "查询响应任务id字段", "查询任务id字段" -> "statusIdField";
            case "状态字段", "查询状态字段" -> "stateField";
            case "状态映射" -> "states";
            case "取消方式", "取消方法" -> "cancelMethod";
            case "取消路径", "取消接口路径" -> "cancelPath";
            case "下载路径", "下载接口路径", "下载get路径" -> "downloadPath";
            case "成片地址字段", "查询成片地址字段", "查询响应成品地址字段" -> "resultUrlField";
            default -> "";
        };
    }

    private String formValue(FormSpec spec) {
        if (spec.fact() != null) {
            String value = facts.getOrDefault(spec.fact(), "");
            return facts.containsKey(Fact.SUBMIT) && value.startsWith("https://")
                    && routeFact(spec.fact())
                    ? path(value) : value;
        }
        if (PROMPT.equals(spec.id())) {
            for (Map.Entry<String, String> item : request.entrySet()) {
                if ("argument".equals(item.getValue())) return item.getKey();
            }
            return "";
        }
        return stateLines(states);
    }

    private static String stateLines(Map<String, String> mappings) {
        List<String> lines = new ArrayList<>();
        mappings.forEach((raw, normalized) -> lines.add(raw + "=" + normalized));
        return String.join("\n", lines);
    }

    private static FormSpec formSpec(String id) {
        for (FormSpec spec : FORM) if (spec.id().equals(id)) return spec;
        throw invalid();
    }

    private static Map<String, Source> readProvenance(JSONObject stored) {
        if (stored == null || stored.length() > FORM.size() + 1) throw invalid();
        Map<String, Source> used = new LinkedHashMap<>();
        try {
            for (Iterator<String> ids = stored.keys(); ids.hasNext();) {
                String id = ids.next();
                if (!Fact.MIN_POLL.key.equals(id)) formSpec(id);
                Source source = Source.valueOf(string(stored.opt(id)));
                if (source == Source.EMPTY) throw invalid();
                used.put(id, source);
            }
        } catch (IllegalArgumentException malformed) { throw invalid(); }
        return used;
    }

    public SkillVideoFacts merge(SkillVideoFacts newer) {
        if (newer == null || newer.isEmpty()) return this;
        if (provenance.containsValue(Source.MANUAL)) return fillMissingFrom(newer);
        String priorEndpoint = facts.get(Fact.SUBMIT);
        String nextEndpoint = newer.facts.get(Fact.SUBMIT);
        if (priorEndpoint != null && nextEndpoint != null && !priorEndpoint.equals(nextEndpoint)) return newer;
        if (!supportsSubmissionEncoding()) return fillMissingFrom(newer);
        if (newer.isComplete()) return newer;
        return combine(newer, false);
    }

    /** Fills unset fields from extracted documentation or an approved Skill shape. */
    public SkillVideoFacts fillMissingFrom(SkillVideoFacts incoming) {
        if (incoming == null || incoming.isEmpty()) return this;
        if (!compatibleSubmission(incoming)) {
            throw new IllegalArgumentException("提交地址或路径与已填写的接口资料不同，请核对地址");
        }
        return combine(incoming, true);
    }

    private boolean compatibleSubmission(SkillVideoFacts incoming) {
        String current = facts.get(Fact.SUBMIT);
        String offered = incoming.facts.get(Fact.SUBMIT);
        if (current != null && offered != null && !current.equals(offered)) return false;
        String currentPath = current == null ? submitPath() : URI.create(current).getRawPath();
        String offeredPath = offered == null ? incoming.submitPath() : URI.create(offered).getRawPath();
        return currentPath.isEmpty() || offeredPath.isEmpty() || currentPath.equals(offeredPath);
    }

    boolean canEnrichSubmissionEncoding(SkillVideoFacts incoming) {
        if (incoming == null || !UNKNOWN_ENCODING.equals(submitEncoding())
                || provenance.get(Fact.ENCODING.key) == Source.MANUAL || incoming.submitEncoding().isEmpty()
                || UNKNOWN_ENCODING.equals(incoming.submitEncoding()) || !compatibleSubmission(incoming)) return false;
        for (Fact field : Fact.values()) {
            if (field == Fact.LABEL || field == Fact.ENCODING || field == Fact.MIN_POLL
                    || field == Fact.SUBMIT || field == Fact.SUBMIT_PATH) continue;
            String before = facts.get(field);
            String after = incoming.facts.get(field);
            if (after != null && otherStatusMode(field)) return false;
            if (before != null && after != null && !before.equals(after)) {
                if (!routeFact(field) || before.startsWith("https://") && after.startsWith("https://")
                        || !path(before).equals(path(after))) return false;
            }
        }
        for (Map.Entry<String, String> field : request.entrySet()) {
            if (incoming.request.containsKey(field.getKey())
                    && !field.getValue().equals(incoming.request.get(field.getKey()))) return false;
        }
        for (Map.Entry<String, String> state : states.entrySet()) {
            if (incoming.states.containsKey(state.getKey())
                    && !state.getValue().equals(incoming.states.get(state.getKey()))) return false;
        }
        String prompt = formValue(formSpec(PROMPT));
        String nextPrompt = incoming.formValue(formSpec(PROMPT));
        return prompt.isEmpty() || nextPrompt.isEmpty() || prompt.equals(nextPrompt);
    }

    private SkillVideoFacts combine(SkillVideoFacts newer, boolean fillOnly) {
        Map<Fact, String> mergedFacts = new EnumMap<>(Fact.class);
        mergedFacts.putAll(facts);
        Map<String, Source> mergedSources = new LinkedHashMap<>(provenance);
        for (Map.Entry<Fact, String> item : newer.facts.entrySet()) {
            Fact field = item.getKey();
            if (fillOnly && field == Fact.QUERY_MODE) continue;
            Source offeredSource = newer.provenance.get(field.key);
            if (fillOnly && submitLocation(field) && provenance.get(field.key) == Source.CHAT_SUGGESTION
                    && item.getValue().equals(facts.get(field)) && verifiesSuggestion(offeredSource)) {
                mergedSources.put(field.key, offeredSource);
                continue;
            }
            boolean enrichEncoding = field == Fact.ENCODING && canEnrichSubmissionEncoding(newer);
            if (fillOnly && (provenance.containsKey(field.key)
                    || field == Fact.RESULT_URL && facts.containsKey(Fact.DOWNLOAD)
                    || field == Fact.DOWNLOAD && facts.containsKey(Fact.RESULT_URL)
                    || otherStatusMode(field)) && !enrichEncoding) continue;
            mergedFacts.put(field, item.getValue());
            mergedSources.put(field.key, offeredSource);
        }
        Map<String, String> mergedStates = new LinkedHashMap<>(states);
        if (!fillOnly || provenance.get(STATES) != Source.MANUAL) {
            newer.states.forEach((key, value) -> {
                if (fillOnly) mergedStates.putIfAbsent(key, value);
                else mergedStates.put(key, value);
            });
            if (!newer.states.isEmpty() && (!fillOnly || states.isEmpty())) {
                mergedSources.put(STATES, newer.provenance.get(STATES));
            }
        }
        if (mergedStates.size() > MAX_STATES) throw invalid();
        Map<String, String> mergedRequest = new LinkedHashMap<>(request);
        boolean addPrompt = newer.request.containsValue("argument") && (!fillOnly || !provenance.containsKey(PROMPT));
        if (addPrompt) {
            mergedRequest.values().removeIf("argument"::equals);
            mergedSources.put(PROMPT, newer.provenance.get(PROMPT));
        }
        newer.request.forEach((key, value) -> {
            if ("argument".equals(value) && !addPrompt) return;
            if (fillOnly) mergedRequest.putIfAbsent(key, value);
            else mergedRequest.put(key, value);
        });
        if (mergedRequest.size() > 7) throw invalid();
        Map<String, JSONObject> mergedFields = new LinkedHashMap<>();
        indexConfig(configFields(), mergedFields, false);
        indexConfig(newer.configFields(), mergedFields, fillOnly);
        JSONArray declarations = new JSONArray(mergedFields.values());
        return new SkillVideoFacts(mergedFacts, mergedRequest, mergedStates, declarations.toString(),
                validateConfig(declarations), mergedSources, Source.STORED);
    }

    public boolean isEmpty() { return facts.isEmpty() && request.isEmpty() && states.isEmpty() && provenance.isEmpty(); }

    public boolean isComplete() { return missing().isEmpty(); }

    public String submitEncoding() { return facts.getOrDefault(Fact.ENCODING, ""); }

    public String submitPath() { return facts.getOrDefault(Fact.SUBMIT_PATH, ""); }

    public boolean needsApiBase() { return !submitPath().isEmpty() && !facts.containsKey(Fact.SUBMIT); }

    public String resolveApiBase(String base) {
        if (!needsApiBase()) throw invalid();
        String canonical = endpoint(base);
        if (!"/".equals(URI.create(canonical).getRawPath())) {
            throw new IllegalArgumentException("API 基础地址只包含 HTTPS 主机和可选端口");
        }
        String target = endpoint(authority(canonical) + submitPath());
        checkResolvedEndpoint(target);
        return target;
    }

    public String apiBaseForEndpoint(String configured) {
        if (!needsApiBase() || configured == null || configured.isEmpty()) return "";
        try {
            String base = authority(endpoint(configured));
            return resolveApiBase(base).equals(configured) ? base : "";
        } catch (IllegalArgumentException invalid) { return ""; }
    }

    void checkResolvedEndpoint(String configured) {
        if (!needsApiBase()) return;
        if (!submitPath().equals(URI.create(configured).getRawPath())) {
            throw new IllegalArgumentException("完整接口地址必须匹配已核对的提交 POST 路径");
        }
        checkRouteOrigins(authority(configured));
    }

    boolean verifiedDocumentContract() {
        Fact submit = needsApiBase() ? Fact.SUBMIT_PATH : Fact.SUBMIT;
        return isComplete() && provenance.get(submit.key) == Source.DOCUMENT;
    }

    public boolean supportsSubmissionEncoding() {
        return JSON_ENCODING.equals(submitEncoding()) || submitEncoding().isEmpty() && submitPath().isEmpty();
    }

    public List<String> missing() {
        List<String> missing = new ArrayList<>();
        for (FormSpec spec : FORM) {
            if (spec.fact() == Fact.SUBMIT && facts.containsKey(Fact.SUBMIT_PATH)) continue;
            if (spec.fact() == Fact.STATUS && otherStatusMode(Fact.STATUS) && !FIXED_GET.equals(explicitQueryMode())) continue;
            if (spec.required() && spec.fact() != null && !facts.containsKey(spec.fact())) missing.add(spec.label());
        }
        if (needsSubmissionVerification()) {
            missing.add(facts.containsKey(Fact.SUBMIT) ? "核对并确认提交 POST 地址" : "核对并确认提交 POST 路径");
        }
        if (!request.containsValue("argument")) missing.add("提示词字段");
        if (!states.containsValue("completed")) missing.add("状态映射中的完成状态");
        if (RESPONSE_URL_GET.equals(explicitQueryMode()) && !facts.containsKey(Fact.STATUS_URL)) {
            missing.add(Fact.STATUS_URL.missing);
        }
        if (JSON_POST.equals(explicitQueryMode()) || facts.containsKey(Fact.STATUS_POST) || facts.containsKey(Fact.STATUS_POST_ID)) {
            if (!facts.containsKey(Fact.STATUS_POST)) missing.add(Fact.STATUS_POST.missing);
            if (!facts.containsKey(Fact.STATUS_POST_ID)) missing.add(Fact.STATUS_POST_ID.missing);
        }
        if (QUERY_FIELDS.stream().anyMatch(field -> facts.containsKey(field) && otherStatusMode(field))) {
            missing.add("查询 GET 路径、提交响应地址字段和查询 POST 只能选择一种");
        }
        if (facts.containsKey(Fact.CANCEL) && !facts.containsKey(Fact.CANCEL_METHOD)) missing.add(Fact.CANCEL_METHOD.missing);
        if (!facts.containsKey(Fact.CANCEL) && facts.containsKey(Fact.CANCEL_METHOD)) missing.add(Fact.CANCEL.missing);
        boolean download = facts.containsKey(Fact.DOWNLOAD);
        boolean result = facts.containsKey(Fact.RESULT_URL);
        if (!download && !result) missing.add("下载 GET 路径（含 {id}），或查询响应成品 HTTPS 地址字段");
        if (download && result) missing.add("下载 GET 路径与查询响应成品地址字段只能填写一种");
        if (!supportsSubmissionEncoding()) {
            missing.add(switch (submitEncoding()) {
                case MULTIPART_ENCODING -> "当前视频技能只支持 JSON 提交，供应商使用 multipart/form-data";
                case URLENCODED_ENCODING -> "当前视频技能只支持 JSON 提交，供应商使用表单编码";
                default -> "请确认提交请求编码，当前视频技能只支持 application/json";
            });
        }
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
        if (facts.containsKey(Fact.STATUS)) put(video, "statusPath", path(facts.get(Fact.STATUS)));
        else if (facts.containsKey(Fact.STATUS_URL)) put(video, "statusUrlField", facts.get(Fact.STATUS_URL));
        else {
            put(video, "statusPostPath", path(facts.get(Fact.STATUS_POST)));
            put(video, "statusPostIdField", facts.get(Fact.STATUS_POST_ID));
        }
        if (facts.containsKey(Fact.CANCEL)) {
            put(video, "cancelPath", path(facts.get(Fact.CANCEL)));
            put(video, "cancelMethod", facts.get(Fact.CANCEL_METHOD));
        }
        if (facts.containsKey(Fact.DOWNLOAD)) put(video, "downloadPath", path(facts.get(Fact.DOWNLOAD)));
        else put(video, "resultUrlField", facts.get(Fact.RESULT_URL));
        put(video, "idField", facts.get(Fact.STATUS_ID));
        put(video, "stateField", facts.get(Fact.STATE));
        put(video, "states", new JSONObject(states));
        if (minimumPollSeconds() != 0) put(video, Fact.MIN_POLL.key, minimumPollSeconds());
        JSONObject declaration = new JSONObject();
        put(declaration, "version", SkillHttpDeclaration.VERSION);
        put(declaration, "label", facts.getOrDefault(Fact.LABEL, "视频服务"));
        if (facts.containsKey(Fact.SUBMIT)) put(declaration, "suggestedEndpoint", facts.get(Fact.SUBMIT));
        put(declaration, "request", new JSONObject(request));
        put(declaration, "response", facts.get(Fact.SUBMIT_ID));
        put(declaration, "video", video);
        return SkillHttpDeclaration.parse(declaration, SkillResult.Type.VIDEO, config).toJson();
    }

    private void checkOrigins() {
        String endpoint = facts.get(Fact.SUBMIT);
        checkRouteOrigins(endpoint == null ? null : authority(endpoint));
    }

    private void checkRouteOrigins(String origin) {
        for (Fact operation : new Fact[]{Fact.STATUS, Fact.STATUS_POST, Fact.CANCEL, Fact.DOWNLOAD}) {
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
            case SUBMIT_PATH -> submitPath(value);
            case ENCODING -> encoding(value);
            case QUERY_MODE -> checkedQueryMode(value);
            case MIN_POLL -> throw invalid();
            case SUBMIT_ID, STATUS_ID, STATUS_URL, STATE, RESULT_URL -> responsePath(value);
            case STATUS_POST -> route(value, false);
            case STATUS_POST_ID -> field(value);
            case STATUS, CANCEL, DOWNLOAD -> route(value);
            case CANCEL_METHOD -> {
                if (!value.equalsIgnoreCase("POST") && !value.equalsIgnoreCase("DELETE")) throw invalid();
                yield value.toUpperCase(Locale.ROOT);
            }
        };
    }

    private static String checkedPollSeconds(int seconds) {
        if (seconds < 3 || seconds > 60) throw new IllegalArgumentException("最短轮询间隔应为 3 到 60 秒");
        return Integer.toString(seconds);
    }

    private static String operation(String value, String expectedMethod) {
        String[] words = value.split("\\s+", 2);
        if (words.length != 2 || !words[0].equalsIgnoreCase(expectedMethod) || words[1].isEmpty()) throw invalid();
        return unquote(words[1]);
    }

    static String encoding(String value) {
        String bare = value.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
        return switch (bare) {
            case JSON_ENCODING, "json" -> JSON_ENCODING;
            case MULTIPART_ENCODING, "multipart", "form-data" -> MULTIPART_ENCODING;
            case URLENCODED_ENCODING, "urlencoded", "form-urlencoded" -> URLENCODED_ENCODING;
            default -> UNKNOWN_ENCODING;
        };
    }

    static String mergeEncoding(String first, String second) {
        if (first.equals(second)) return first;
        if (first.equals(MULTIPART_ENCODING) || second.equals(MULTIPART_ENCODING)) return MULTIPART_ENCODING;
        return UNKNOWN_ENCODING;
    }

    private static String unquote(String value) {
        return value.length() > 1 && value.charAt(0) == '`' && value.charAt(value.length() - 1) == '`'
                ? value.substring(1, value.length() - 1).trim() : value;
    }

    private enum Example {
        REQUEST, SUBMIT, STATUS;

        static Example find(String label) {
            return switch (label) {
                case "请求示例", "提交请求示例", "requestexample", "submitrequestexample" -> REQUEST;
                case "提交响应", "提交响应示例", "submitresponse", "submitresponseexample" -> SUBMIT;
                case "查询响应", "查询响应示例", "状态响应示例", "statusresponse", "statusresponseexample" -> STATUS;
                default -> null;
            };
        }
    }

    private static String samplePrompt(JSONObject request) {
        boolean prompt = request.opt("prompt") instanceof String;
        boolean textPrompt = request.opt("text_prompt") instanceof String;
        if (prompt == textPrompt) return "";
        return prompt ? "prompt" : "text_prompt";
    }

    private static void sampleField(Map<Fact, String> results, Set<Fact> ambiguous, Fact fact,
                                    JSONObject object, Set<String> knownNames, String submit) {
        if (ambiguous.contains(fact)) return;
        List<String> paths = new ArrayList<>();
        samplePaths(object, knownNames, "", 0, paths, submit);
        if (paths.size() == 1) {
            String route = paths.get(0);
            String previous = results.putIfAbsent(fact, route);
            if (previous == null || previous.equals(route)) return;
        } else if (paths.isEmpty()) return;
        results.remove(fact);
        ambiguous.add(fact);
    }

    private static void samplePaths(JSONObject object, Set<String> knownNames, String prefix, int depth,
                                    List<String> paths, String submit) {
        if (depth >= MAX_RESPONSE_DEPTH || paths.size() > 1) return;
        for (Iterator<String> names = object.keys(); names.hasNext() && paths.size() <= 1;) {
            String key = names.next();
            try { field(key); }
            catch (IllegalArgumentException unsupported) { continue; }
            Object value = object.opt(key);
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            if (value instanceof JSONObject nested) {
                samplePaths(nested, knownNames, path, depth + 1, paths, submit);
            } else if (value instanceof String example && knownNames.contains(
                    key.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""))
                    && (knownNames != URL_FIELDS || validUrlExample(example, submit))) {
                paths.add(path);
            }
        }
    }

    private static boolean validUrlExample(String example, String submit) {
        try {
            String origin = authority(endpoint(example));
            return submit == null || origin.equals(authority(submit));
        } catch (IllegalArgumentException invalid) { return false; }
    }

    private static String endpoint(String value) {
        if (value == null || value.contains("$(") || value.contains("${")) throw invalid();
        try { return SkillCustomService.validateEndpoint(value); }
        catch (SkillCustomService.Failure invalid) { throw invalid(); }
    }

    private static String authority(String endpoint) { return "https://" + URI.create(endpoint).getRawAuthority(); }

    private static String path(String route) { return route.startsWith("/") ? route : route.substring(route.indexOf('/', 8)); }

    private static String submitPath(String value) {
        checkPath(value, false);
        return value;
    }

    private static String route(String value) {
        return route(value, true);
    }

    private static String route(String value, boolean taskTemplate) {
        String origin = "";
        if (!value.startsWith("/")) {
            if (!value.regionMatches(true, 0, "https://", 0, 8)) throw invalid();
            int slash = value.indexOf('/', 8);
            if (slash < 0) throw invalid();
            origin = authority(endpoint(value.substring(0, slash)));
            value = value.substring(slash);
        }
        checkPath(value, taskTemplate);
        return origin + value;
    }

    private boolean otherStatusMode(Fact field) {
        String mode = queryModeFor(field);
        String selected = explicitQueryMode();
        if (!mode.isEmpty() && !selected.isEmpty() && !mode.equals(selected)) return true;
        boolean post = facts.containsKey(Fact.STATUS_POST) || facts.containsKey(Fact.STATUS_POST_ID);
        return switch (field) {
            case STATUS -> facts.containsKey(Fact.STATUS_URL) || post;
            case STATUS_URL -> facts.containsKey(Fact.STATUS) || post;
            case STATUS_POST, STATUS_POST_ID -> facts.containsKey(Fact.STATUS) || facts.containsKey(Fact.STATUS_URL);
            default -> false;
        };
    }

    private static String queryModeFor(Fact field) {
        if (field == Fact.STATUS) return FIXED_GET;
        if (field == Fact.STATUS_URL) return RESPONSE_URL_GET;
        if (field == Fact.STATUS_POST || field == Fact.STATUS_POST_ID) return JSON_POST;
        return "";
    }

    private static String checkedQueryMode(String mode) {
        if (!FIXED_GET.equals(mode) && !RESPONSE_URL_GET.equals(mode) && !JSON_POST.equals(mode)) throw invalid();
        return mode;
    }

    private static void selectQueryMode(Map<Fact, String> fields, Map<String, Source> sources, String mode) {
        for (Fact choice : QUERY_FIELDS) {
            if (mode.equals(queryModeFor(choice))) {
                if (!fields.containsKey(choice)) sources.remove(choice.key);
            } else if (fields.remove(choice) != null) {
                sources.put(choice.key, Source.MANUAL);
            }
        }
    }

    private static boolean routeFact(Fact field) {
        return field == Fact.STATUS || field == Fact.STATUS_POST || field == Fact.CANCEL || field == Fact.DOWNLOAD;
    }

    private static boolean submitLocation(Fact field) {
        return field == Fact.SUBMIT || field == Fact.SUBMIT_PATH;
    }

    private static boolean verifiesSuggestion(Source source) {
        return source == Source.DOCUMENT || source == Source.MANUAL || source == Source.REUSED_SKILL;
    }

    private static void checkPath(String value, boolean taskTemplate) {
        if (!value.startsWith("/") || value.length() > 320 || value.startsWith("//")) throw invalid();
        int placeholders = 0;
        String[] parts = value.substring(1).split("/", -1);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            if (part.equals("{id}")) placeholders++;
            else if (!(part.isEmpty() && i == parts.length - 1)
                    && (!SEGMENT.matcher(part).matches() || part.equals(".") || part.equals(".."))) throw invalid();
        }
        if (placeholders != (taskTemplate ? 1 : 0)) throw invalid();
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

    private static void indexConfig(JSONArray declarations, Map<String, JSONObject> fields, boolean fillOnly) {
        for (int index = 0; index < declarations.length(); index++) {
            JSONObject declaration = declarations.optJSONObject(index);
            if (fillOnly) fields.putIfAbsent(declaration.optString("key"), declaration);
            else fields.put(declaration.optString("key"), declaration);
        }
    }

    private static void readStates(String value, Map<String, String> states) {
        if (value.startsWith("{")) {
            readStateObject(strict(value), states);
            return;
        }
        for (String part : value.split("[,，;；\\r\\n]+", -1)) {
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

    static void checkSecrets(String value) {
        if (value == null || value.length() > MAX_NOTES) throw invalid();
        try {
            SkillAuthoring.rejectCredential(value);
            if (SECRET.matcher(value).find() || SECRET_ASSIGNMENT.matcher(value).find()) throw invalid();
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
