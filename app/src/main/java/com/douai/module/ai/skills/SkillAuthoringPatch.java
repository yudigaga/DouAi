package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Validates an isolated model's proposed edits against the current Skill and selected sources. */
public final class SkillAuthoringPatch {
    public static final String INSTRUCTION = """
            你整理用户已经给出的技能编辑信息。只输出一个 JSON 对象：
            {"version":1,"updates":[{"field":"允许的字段名","value":"对应 JSON 类型的值","evidence":"原文完整语句"}]}。
            只编辑 fields 给出的字段。每项 evidence 必须逐字引用 request 或 document 中明确对应此字段的完整语句。
            配置和服务值必须是明确写出的原值，不猜测；布尔字段的开启/启用与关闭/停用对应 true 与 false。
            可引用明确对应字段的完整两列 Markdown 表格行。服务值只可引用用户写的内容，documentAuthor 为 user 才可用于服务值。
            描述、说明、指令和显示名称可根据该字段的明确要求组织文字，保留 current 中本轮未要求修改的内容。
            request 的明确设置优先于 document。
            不输出密钥、身份凭据、调用参数外的网络配置或授权。无可靠修改时返回 {"version":1,"updates":[]}。
            """;

    private static final int MAX_MODEL_INPUT = 4000;
    private static final int MAX_DOCUMENT = 8000;
    private static final int MAX_ENDPOINT = 1024;
    private static final int MAX_MODEL = 120;
    private static final int MAX_EVIDENCE = 1200;
    // Spell out Unicode White_Space so the JVM and Android agree without an unsupported flag.
    private static final Pattern ADDRESS = Pattern.compile("(?i)(?:[a-z][a-z0-9+.-]*://|(?:https?|file|content|data|javascript)\\h*:)"
            + "[^\\p{Z}\\x09-\\x0D\\x85|<>\\\"'`，。；！？、）】」』”’]+", Pattern.UNICODE_CASE);
    private static final Pattern ADDRESS_MARKER = Pattern.compile("(?i)[a-z][a-z0-9+.-]*://|(?:https?|file|content|data|javascript)\\h*:");
    private static final Pattern NUMBER = Pattern.compile("[+-]?(?:0|[1-9][0-9]{0,8})(?:\\.[0-9]{1,6})?");
    private static final Pattern MODEL = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N} ._/@:+()-]{0,119}");
    private static final Pattern CREDENTIAL_ASSIGNMENT = Pattern.compile("(?i)(?:密钥|密码|凭据|令牌|口令|"
            + "api[ _-]*key|access[ _-]*token|authorization|password|cookie|bearer)"
            + "(?:字段)?\\h*[`\"']?\\h*(?:[:：=]|改[成为]|设(?:置)?[成为]|换[成为]|填写为|填为)\\h*\\S");
    private static final String PREFIX = "(?:(?:[-*]\\h+)|(?:(?:请|麻烦|帮我|给我|把|将|再|请你|可以|记得|用|设置|修改|更新|更改|更换|切换)\\h*))*";
    private static final String ASSIGN = "(?:改成|改为|改到|调整为|调整成|设置为|设置成|设为|设成|设定为|变成|"
            + "更新为|更新成|修改为|修改成|更改为|更换为|换成|换为|切换到|切换为|填写为|填写|填为|使用|用|是|为|[:：=])";
    private static final String ENDPOINT = "service.endpoint";
    private static final String SERVICE_MODEL = "service.model";

    private SkillAuthoringPatch() { }

    public static Set<String> allowedFields(SkillManifest manifest) {
        return Collections.unmodifiableSet(fields(manifest).keySet());
    }

    /** Identifies explicitly named edit fields in the user's current request. */
    public static Set<String> targets(SkillManifest manifest, String userRequest) {
        if (userRequest == null || userRequest.length() > MAX_MODEL_INPUT) return Set.of();
        Set<String> found = new LinkedHashSet<>();
        for (Field field : fields(manifest).values()) {
            if (!assignments(field, userRequest).isEmpty() || field.metadata() && !metadataEvidence(field, userRequest).isEmpty()) {
                found.add(field.name());
            }
        }
        return Collections.unmodifiableSet(found);
    }

    public static Request request(SkillManifest manifest, Set<String> permittedFields, String userRequest,
                                  String selectedDocument, boolean trustedDocument) {
        if (permittedFields == null) throw invalid();
        Map<String, Field> allowed = fields(manifest);
        if (!allowed.keySet().containsAll(permittedFields)) throw invalid();
        allowed.keySet().retainAll(permittedFields);
        boolean endpoint = allowed.containsKey(ENDPOINT);
        String user = safeSource(userRequest, MAX_MODEL_INPUT, endpoint ? allowed.get(ENDPOINT) : null);
        String document = safeSource(selectedDocument, MAX_DOCUMENT,
                endpoint && trustedDocument ? allowed.get(ENDPOINT) : null);
        try {
            JSONArray schema = new JSONArray();
            for (Field field : allowed.values()) {
                JSONObject item = field.schema();
                if (manifest != null && field.metadata()) {
                    String current = currentText(manifest, field.name());
                    if (!current.isEmpty()) item.put("current", safeSource(current, field.maxLength(), null));
                }
                schema.put(item);
            }
            JSONObject model = new JSONObject().put("fields", schema).put("request", user).put("document", document)
                    .put("documentAuthor", trustedDocument ? "user" : "reference");
            String modelInput = model.toString();
            String visibleDocument = document;
            if (modelInput.length() > MAX_MODEL_INPUT) {
                visibleDocument = compactDocument(allowed, document, trustedDocument);
                modelInput = model.put("document", visibleDocument).toString();
            }
            while (modelInput.length() > MAX_MODEL_INPUT && removeLongestCurrent(schema)) modelInput = model.toString();
            if (modelInput.length() > MAX_MODEL_INPUT) {
                throw new IllegalArgumentException("技能编辑资料编码后超出长度限制");
            }
            return new Request(manifest, Collections.unmodifiableMap(allowed), user, visibleDocument, document,
                    trustedDocument, modelInput);
        } catch (JSONException malformed) {
            throw invalid();
        }
    }

    private static String compactDocument(Map<String, Field> allowed, String document, boolean trusted) {
        Set<String> grounded = new LinkedHashSet<>();
        for (Field field : allowed.values()) {
            if (field.metadata()) grounded.addAll(metadataEvidence(field, document));
            else if (!field.service() || trusted) {
                for (Assignment assignment : assignments(field, document)) grounded.add(assignment.clause());
            }
        }
        List<String> visible = new ArrayList<>();
        for (String clause : clauses(document)) {
            if (grounded.remove(clause)) visible.add(clause);
        }
        return String.join("\n", visible);
    }

    private static boolean removeLongestCurrent(JSONArray schema) throws JSONException {
        JSONObject longest = null;
        int length = 0;
        for (int i = 0; i < schema.length(); i++) {
            JSONObject item = schema.getJSONObject(i);
            if (!item.has("current")) continue;
            int encodedLength = JSONObject.quote(item.getString("current")).length();
            if (encodedLength > length) {
                longest = item;
                length = encodedLength;
            }
        }
        if (longest == null) return false;
        longest.remove("current");
        return true;
    }

    private static String currentText(SkillManifest manifest, String name) {
        return switch (name) {
            case "description" -> manifest.description();
            case "documentation" -> manifest.documentation();
            case "instructions" -> manifest.instructions();
            case "displayName" -> manifest.displayName();
            default -> "";
        };
    }

    public static final class Request {
        private final SkillManifest manifest;
        private final Map<String, Field> permitted;
        private final String user;
        private final String document;
        private final String sourceDocument;
        private final boolean trustedDocument;
        private final String input;

        private Request(SkillManifest manifest, Map<String, Field> permitted, String user, String document,
                        String sourceDocument, boolean trustedDocument, String input) {
            this.manifest = manifest;
            this.permitted = permitted;
            this.user = user;
            this.document = document;
            this.sourceDocument = sourceDocument;
            this.trustedDocument = trustedDocument;
            this.input = input;
        }

        public String input() { return input; }

        public Map<String, Object> read(String raw) {
            try {
                JSONObject data = SkillAuthoring.readObject(raw, 16_000, 128);
                if (data.length() != 2 || !Integer.valueOf(1).equals(data.opt("version"))
                        || !(data.opt("updates") instanceof JSONArray updates) || updates.length() > permitted.size()) {
                    throw invalid();
                }
                Map<String, Object> patch = new LinkedHashMap<>();
                JSONObject config = new JSONObject();
                for (int i = 0; i < updates.length(); i++) {
                    JSONObject item = updates.getJSONObject(i);
                    if (item.length() != 3 || !(item.opt("field") instanceof String name)
                            || !(item.opt("evidence") instanceof String evidence)) throw invalid();
                    Field field = permitted.get(name);
                    if (field == null || patch.containsKey(name)) throw invalid();
                    checkedText(evidence, MAX_EVIDENCE, true, field.endpoint());
                    Object value = validateValue(field, item.get("value"));
                    requireEvidence(field, value, evidence);
                    if (field.config() != null) config.put(field.config().key(), value);
                    patch.put(name, field.endpoint() ? endpoint((String) value) : value);
                }
                if (config.length() != 0) {
                    Map<String, Object> normalized = manifest.readValues(config);
                    for (String name : patch.keySet()) {
                        SkillManifest.ConfigField field = permitted.get(name).config();
                        if (field != null) patch.put(name, normalized.get(field.key()));
                    }
                }
                return Collections.unmodifiableMap(patch);
            } catch (JSONException | IllegalArgumentException malformed) {
                throw invalid();
            }
        }

        private void requireEvidence(Field field, Object value, String evidence) {
            if (field.metadata()) {
                if (!proves(user, evidence, metadataEvidence(field, user))
                        && !provesDocument(evidence, metadataEvidence(field, document))) throw invalid();
                return;
            }
            List<Assignment> typed = assignments(field, user);
            List<Assignment> referenced = field.service() && !trustedDocument ? List.of() : assignments(field, document);
            List<Assignment> preferred = typed.isEmpty() ? referenced : typed;
            if (preferred.isEmpty() || preferred.stream().anyMatch(item -> !literalEquals(field, item.value(), value))) {
                throw invalid();
            }
            if (!proves(user, evidence, typed.stream().map(Assignment::clause).collect(Collectors.toList()))
                    && !provesDocument(evidence, referenced.stream().filter(item -> literalEquals(field, item.value(), value))
                    .map(Assignment::clause).collect(Collectors.toList()))) throw invalid();
        }

        private boolean provesDocument(String evidence, List<String> clauses) {
            return sourceDocument.contains(evidence) && proves(document, evidence, clauses);
        }
    }

    private record Field(String name, List<String> labels, SkillManifest.ConfigField config, int maxLength,
                         boolean multiline) {
        boolean service() { return name.startsWith("service."); }
        boolean endpoint() { return ENDPOINT.equals(name); }
        boolean metadata() { return config == null && !service(); }

        String names() {
            return labels.stream().map(Pattern::quote).collect(Collectors.joining("|", "(?:", ")"));
        }

        JSONObject schema() throws JSONException {
            String type = config == null ? "text" : config.type().name().toLowerCase(Locale.ROOT);
            JSONObject result = new JSONObject().put("field", name).put("labels", new JSONArray(labels)).put("type", type);
            if (config != null && config.min() != null) result.put("min", config.min()).put("max", config.max());
            if (config != null && !config.options().isEmpty()) result.put("options", new JSONArray(config.options()));
            if (config == null || config.type() == SkillManifest.FieldType.TEXT
                    || config.type() == SkillManifest.FieldType.MULTILINE) result.put("maxLength", maxLength);
            return result;
        }
    }

    private static Map<String, Field> fields(SkillManifest manifest) {
        Map<String, Field> fields = new LinkedHashMap<>();
        add(fields, "description", 100, false, "描述", "技能描述", "简介", "技能简介");
        add(fields, "documentation", 6000, true, "文档", "说明", "说明文档", "使用说明");
        add(fields, "instructions", 1600, true, "指令", "技能指令", "提示词", "执行指令", "执行要求");
        add(fields, "displayName", 48, false, "显示名称", "显示名字", "显示名", "名称", "标题");
        if (manifest == null) return fields;
        for (SkillManifest.ConfigField config : manifest.configFields()) {
            String name = "config." + config.key();
            Set<String> labels = new LinkedHashSet<>(List.of(name, config.key(), config.label()));
            fields.put(name, new Field(name, List.copyOf(labels), config, config.maxLength(),
                    config.type() == SkillManifest.FieldType.MULTILINE));
        }
        if (manifest.capability() == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                || manifest.capability() == SkillCapabilityRegistry.Capability.IMAGE_GENERATION) {
            add(fields, ENDPOINT, MAX_ENDPOINT, false, "API 地址", "API地址", "接口地址", "服务地址", "服务端点",
                    "生成地址", "基础地址", "Base URL", "endpoint");
        }
        if (manifest.serviceRef() == SkillCapabilityRegistry.ServiceRef.IMAGE_CONNECTION) {
            add(fields, SERVICE_MODEL, MAX_MODEL, false, "模型名", "模型名称", "服务模型", "图片模型", "模型");
        }
        Map<String, Integer> labelCounts = new LinkedHashMap<>();
        for (Field field : fields.values()) {
            for (String label : field.labels()) labelCounts.merge(label.toLowerCase(Locale.ROOT), 1, Integer::sum);
        }
        fields.replaceAll((name, field) -> new Field(name, field.labels().stream().filter(label -> name.equals(label)
                || labelCounts.get(label.toLowerCase(Locale.ROOT)) == 1).collect(Collectors.toList()), field.config(),
                field.maxLength(), field.multiline()));
        return fields;
    }

    private static void add(Map<String, Field> fields, String name, int max, boolean multiline, String... aliases) {
        List<String> labels = new ArrayList<>(List.of(aliases));
        labels.add(name);
        fields.put(name, new Field(name, List.copyOf(labels), null, max, multiline));
    }

    private record Assignment(String clause, String value) { }

    private static List<Assignment> assignments(Field field, String source) {
        Pattern syntax = Pattern.compile("(?i)^" + PREFIX + field.names() + "(?:字段)?\\h*" + ASSIGN + "\\h*(.+)$");
        Pattern row = Pattern.compile("(?i)^\\|\\h*(?:" + field.names() + "|`" + field.names()
                + "`)\\h*\\|\\h*([^|]+?)\\h*\\|$");
        List<Assignment> matches = new ArrayList<>();
        for (String clause : clauses(source)) {
            Matcher named = (clause.startsWith("|") ? row : syntax).matcher(clause);
            if (named.matches()) matches.add(new Assignment(clause, stripQuotes(stripSpace(named.group(1)))));
        }
        return matches;
    }

    private static List<String> metadataEvidence(Field field, String source) {
        List<String> result = new ArrayList<>();
        Pattern request = Pattern.compile("(?i)^" + PREFIX + "(?:润色|优化|完善|重写|补充|精简|修改|改写|编辑|调整|换个)"
                + "(?:一下|一版|新的)?\\h*" + field.names() + "(?:吧|一下)?(?:\\h|$).*$");
        for (String clause : clauses(source)) {
            if (request.matcher(clause).matches()) result.add(clause);
        }
        assignments(field, source).stream().map(Assignment::clause).forEach(result::add);
        return result;
    }

    private static List<String> clauses(String source) {
        List<String> result = new ArrayList<>();
        for (String rawLine : source.split("[\\r\\n]")) {
            String line = stripSpace(rawLine);
            if (line.startsWith("|")) {
                result.add(line);
                continue;
            }
            char quote = 0;
            int start = 0;
            for (int i = 0; i <= line.length(); i++) {
                char c = i == line.length() ? '\n' : line.charAt(i);
                if (c == '\n' || quote == 0 && ",，。;；！？!?".indexOf(c) >= 0) {
                    String clause = stripSpace(line.substring(start, i));
                    if (!clause.isEmpty()) result.add(clause);
                    start = i + 1;
                } else if (quote != 0 && c == quote) {
                    quote = 0;
                } else if (quote == 0) {
                    quote = closeQuote(c);
                }
            }
        }
        return result;
    }

    private static char closeQuote(char open) {
        return switch (open) {
            case '"', '\'', '`' -> open;
            case '“' -> '”';
            case '‘' -> '’';
            case '「' -> '」';
            case '『' -> '』';
            default -> 0;
        };
    }

    private static String stripQuotes(String literal) {
        if (literal.length() >= 2 && closeQuote(literal.charAt(0)) == literal.charAt(literal.length() - 1)) {
            return literal.substring(1, literal.length() - 1);
        }
        return literal;
    }

    private static String stripSpace(String text) {
        int start = 0;
        int end = text.length();
        while (start < end && space(text.charAt(start))) start++;
        while (end > start && space(text.charAt(end - 1))) end--;
        return text.substring(start, end);
    }

    private static boolean space(char c) { return Character.isWhitespace(c) || Character.isSpaceChar(c); }

    private static boolean proves(String source, String evidence, List<String> clauses) {
        return source.contains(evidence) && clauses.stream().anyMatch(evidence::contains);
    }

    private static boolean literalEquals(Field field, String literal, Object value) {
        if (value instanceof Boolean checked) {
            return checked ? List.of("true", "开启", "启用").contains(literal)
                    : List.of("false", "关闭", "停用").contains(literal);
        }
        if (value instanceof Number number) {
            return NUMBER.matcher(literal).matches() && (field.config().type() != SkillManifest.FieldType.INTEGER
                    || literal.indexOf('.') < 0) && new BigDecimal(literal).compareTo(new BigDecimal(number.toString())) == 0;
        }
        return value.toString().equals(literal);
    }

    private static Object validateValue(Field field, Object raw) {
        if (field.config() != null) {
            return switch (field.config().type()) {
                case INTEGER -> {
                    if (!(raw instanceof Integer)) throw invalid();
                    yield raw;
                }
                case DECIMAL -> {
                    if (!(raw instanceof Number)) throw invalid();
                    yield raw;
                }
                case BOOLEAN -> {
                    if (!(raw instanceof Boolean)) throw invalid();
                    yield raw;
                }
                default -> checkedText(raw, field.maxLength(), field.multiline(), false);
            };
        }
        String text = checkedText(raw, field.maxLength(), field.multiline(), field.endpoint());
        if (SERVICE_MODEL.equals(field.name()) && (!MODEL.matcher(text).matches() || !text.equals(stripSpace(text)))) throw invalid();
        return text;
    }

    private static String safeSource(String raw, int max, Field endpointField) {
        if (raw == null || stripSpace(raw).isEmpty()) return "";
        String source = checkedText(raw, max, true, true);
        List<String> addresses = new ArrayList<>();
        if (endpointField != null) {
            for (Assignment assignment : assignments(endpointField, source)) {
                try {
                    endpoint(assignment.value());
                    addresses.add(assignment.value());
                } catch (IllegalArgumentException invalidEndpoint) { /* Remove the unsafe address from the prompt. */ }
            }
        }
        Matcher matcher = ADDRESS.matcher(source);
        StringBuffer safe = new StringBuffer();
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!addresses.contains(candidate)) matcher.appendReplacement(safe, "[地址已省略]");
        }
        return matcher.appendTail(safe).toString();
    }

    private static String checkedText(Object value, int max, boolean multiline, boolean allowAddress) {
        if (!(value instanceof String text) || text.length() > max || stripSpace(text).isEmpty()
                || !allowAddress && ADDRESS_MARKER.matcher(text).find()
                || CREDENTIAL_ASSIGNMENT.matcher(text).find()
                || text.codePoints().anyMatch(c -> (Character.isISOControl(c) || Character.getType(c) == Character.FORMAT)
                && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) throw invalid();
        SkillAuthoring.rejectEditorCredential(text);
        SkillVideoFacts.checkSecrets(text);
        return text;
    }

    private static String endpoint(String value) {
        try { return SkillCustomService.validateEndpoint(value); }
        catch (SkillCustomService.Failure malformed) { throw invalid(); }
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException("技能编辑建议包含无法验证的字段、值或来源");
    }
}
