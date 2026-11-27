package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Versioned declaration for one application-managed Skill capability. */
public record SkillManifest(String name, String displayName, String description, String documentation, String instructions,
                            SkillCapabilityRegistry.Capability capability, List<ConfigField> configFields,
                            int maxInput, String trialInput, SkillResult.Type outputType,
                            SkillHttpDeclaration http, String definition) {
    public static final int SCHEMA_VERSION = 4;
    static final String DRAFT_VALUES = "draftValues";
    static final String SERVICE_PREFILL = "servicePrefill";
    private static final int MAX_FIELDS = 6;
    private static final int MAX_SETTINGS_JSON = 900;
    private static final BigDecimal MAX_NUMBER = BigDecimal.valueOf(1_000_000);
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final Pattern KEY = Pattern.compile("[a-z][a-z0-9_]{0,31}");
    private static final Pattern DECIMAL = Pattern.compile("[+-]?\\d{1,7}(?:\\.\\d{1,6})?");
    private static final Pattern ADDRESS = Pattern.compile("(?i)\\b(?:https?|file|content|ftp|data)\\s*:");
    private static final Pattern SECRET_LABEL = Pattern.compile(
            "(?i)api[ _-]*key|authorization|password|access[ _-]*token|bearer|密钥|密码|口令|访问令牌|授权头|凭据");
    private static final Set<String> RESERVED_KEYS = Set.of("id", "api_key", "apikey", "credential", "credentials",
            "token", "access_token", "authorization", "secret", "password", "cookie", "headers", "endpoint", "url",
            "provider_ref", "model_ref", "secret_ref", "api_token", "auth", "access_key", "client_secret", "refresh_token");

    public static final class Invalid extends IllegalArgumentException {
        Invalid(String message) { super(message); }
    }

    public enum FieldType { TEXT, MULTILINE, INTEGER, DECIMAL, BOOLEAN, ENUM }

    public record ServicePrefill(String endpoint, String model) {
        public boolean isEmpty() { return endpoint.isEmpty() && model.isEmpty(); }
    }

    public record ConfigField(String key, String label, String description, FieldType type, boolean required,
                              List<String> options, int maxLength, BigDecimal min, BigDecimal max, Object defaultValue) {
        private Object fromForm(String raw) {
            return switch (type) {
                case INTEGER, DECIMAL -> {
                    if (!DECIMAL.matcher(raw).matches()) throw invalid();
                    yield validate(new BigDecimal(raw));
                }
                case BOOLEAN -> {
                    if (!"true".equals(raw) && !"false".equals(raw)) throw invalid();
                    yield validate(Boolean.valueOf(raw));
                }
                default -> validate(raw);
            };
        }

        private Object validate(Object value) {
            switch (type) {
                case TEXT:
                case MULTILINE:
                    return checkedText(value, maxLength, type == FieldType.MULTILINE);
                case INTEGER:
                case DECIMAL:
                    BigDecimal number = number(value);
                    if (number.compareTo(min) < 0 || number.compareTo(max) > 0) throw invalid();
                    if (type == FieldType.INTEGER) {
                        try { return number.intValueExact(); }
                        catch (ArithmeticException fractional) { throw invalid(); }
                    }
                    return number;
                case BOOLEAN:
                    if (!(value instanceof Boolean)) throw invalid();
                    return value;
                case ENUM:
                    if (!(value instanceof String) || !options.contains(value)) throw invalid();
                    return value;
                default:
                    throw invalid();
            }
        }
    }

    public static SkillManifest parse(String json) {
        try {
            JSONObject data = SkillAuthoring.readObject(json, 8192);
            keys(data, Set.of("displayName", "http", "serviceRef", SERVICE_PREFILL, DRAFT_VALUES),
                    "schemaVersion", "name", "description", "documentation", "instructions",
                    "capabilityId", "configFields", "inputs", "outputType", "trial");
            if (!Integer.valueOf(SCHEMA_VERSION).equals(data.get("schemaVersion"))) throw invalid();
            String name = checkedText(data.get("name"), 24, false);
            if (!NAME.matcher(name).matches() || AiSkills.isBuiltIn(name) || name.startsWith("mcp-")) throw invalid();
            String displayName = data.has("displayName") ? checkedText(data.get("displayName"), 48, false) : name;
            String description = checkedText(data.get("description"), 100, false);
            String documentation = checkedText(data.get("documentation"), 6000, true);
            String instruction = checkedText(data.get("instructions"), 1600, true);
            SkillCapabilityRegistry.Capability capability = SkillCapabilityRegistry.require(
                    checkedText(data.get("capabilityId"), 32, false));
            if (data.has("serviceRef") && !capability.serviceRef().id().equals(data.get("serviceRef"))) {
                throw new Invalid("技能清单的服务引用与能力不匹配");
            }
            SkillResult.Type output = SkillResult.Type.fromWire(checkedText(data.get("outputType"), 12, false));
            boolean declaredHttp = data.has("http");
            boolean service = capability == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                    || capability == SkillCapabilityRegistry.Capability.VIDEO_TASK;
            if (declaredHttp && !service) throw invalid();
            if (output != capability.outputType() && !(declaredHttp
                    && capability == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                    && (output == SkillResult.Type.TABLE || output == SkillResult.Type.IMAGE))) throw invalid();

            JSONArray inputs = data.getJSONArray("inputs");
            if (inputs.length() != 1) throw invalid();
            JSONObject input = inputs.getJSONObject(0);
            keys(input, Set.of(), "key", "type", "required", "maxLength");
            if (!"argument".equals(input.get("key")) || !Boolean.TRUE.equals(input.get("required"))
                    || (!"text".equals(input.get("type")) && !"multiline".equals(input.get("type")))) throw invalid();
            int limit = integer(input.get("maxLength"), 1, capability.maxInput());

            JSONObject trial = data.getJSONObject("trial");
            keys(trial, Set.of(), "input", "expectedType");
            if (!output.wireName().equals(trial.get("expectedType"))) throw invalid();
            String trialInput = checkedText(trial.get("input"), limit, true);

            JSONArray configs = data.getJSONArray("configFields");
            if (configs.length() > MAX_FIELDS
                    || !capability.usesModel() && !declaredHttp && configs.length() != 0) throw invalid();
            List<ConfigField> fields = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < configs.length(); i++) {
                ConfigField field = field(configs.getJSONObject(i));
                if (!ids.add(field.key())) throw invalid();
                fields.add(field);
            }
            SkillHttpDeclaration http = declaredHttp ? SkillHttpDeclaration.parse(data.getJSONObject("http"), output, fields) : null;
            if (data.has(DRAFT_VALUES)) checkedDraftValues(data.getJSONObject(DRAFT_VALUES), fields);
            if (data.has(SERVICE_PREFILL)) checkedServicePrefill(data.getJSONObject(SERVICE_PREFILL), capability);
            return new SkillManifest(name, displayName, description, documentation, instruction, capability, List.copyOf(fields),
                    limit, trialInput, output, http, data.toString());
        } catch (JSONException malformed) {
            throw invalid();
        }
    }

    public boolean usesHttp() {
        return capability == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE || http != null;
    }

    public SkillCapabilityRegistry.ServiceRef serviceRef() { return capability.serviceRef(); }

    public boolean hasConfigurableFields() { return capability.usesModel() || http != null; }

    private static ConfigField field(JSONObject field) throws JSONException {
        String key = checkedText(field.get("key"), 32, false);
        if (!KEY.matcher(key).matches() || RESERVED_KEYS.contains(key) || SECRET_LABEL.matcher(key).find()) throw invalid();
        String label = fieldText(field.get("label"), 48);
        String description = field.has("description") ? fieldText(field.get("description"), 160) : "";
        if (!(field.get("required") instanceof Boolean required)) throw invalid();
        FieldType type;
        try {
            String wire = checkedText(field.get("type"), 16, false);
            type = FieldType.valueOf(wire.toUpperCase(Locale.ROOT));
            if (!type.name().toLowerCase(Locale.ROOT).equals(wire)) throw invalid();
        } catch (IllegalArgumentException unsupported) {
            throw new Invalid("技能清单使用了当前版本不支持的配置字段类型");
        }
        Set<String> optional = new HashSet<>(Set.of("description", "default"));
        int length = type == FieldType.MULTILINE ? 240 : 120;
        BigDecimal min = null;
        BigDecimal max = null;
        List<String> options = new ArrayList<>();
        switch (type) {
            case TEXT:
            case MULTILINE:
                optional.add("maxLength");
                if (field.has("maxLength")) length = integer(field.get("maxLength"), 1, length);
                break;
            case INTEGER:
            case DECIMAL:
                optional.add("min");
                optional.add("max");
                min = field.has("min") ? number(field.get("min")) : MAX_NUMBER.negate();
                max = field.has("max") ? number(field.get("max")) : MAX_NUMBER;
                if (min.compareTo(max) > 0 || type == FieldType.INTEGER && (min.scale() > 0 || max.scale() > 0)) throw invalid();
                break;
            case ENUM:
                optional.add("options");
                JSONArray choices = field.getJSONArray("options");
                if (choices.length() < 2 || choices.length() > 8) throw invalid();
                for (int i = 0; i < choices.length(); i++) {
                    String choice = fieldText(choices.get(i), 48);
                    if (options.contains(choice)) throw invalid();
                    options.add(choice);
                }
                break;
            default:
                break;
        }
        keys(field, optional, "key", "label", "type", "required");
        ConfigField validator = new ConfigField(key, label, description, type, required, List.copyOf(options),
                length, min, max, null);
        Object value = field.has("default") ? validator.validate(field.get("default")) : null;
        if (value instanceof String text && SECRET_LABEL.matcher(text).find()) throw invalid();
        return new ConfigField(key, label, description, type, required, validator.options(), length, min, max, value);
    }

    public JSONObject toJson() {
        try { return new JSONObject(definition); }
        catch (JSONException impossible) { throw new IllegalStateException(impossible); }
    }

    public Map<String, Object> draftValues() {
        JSONObject local = toJson().optJSONObject(DRAFT_VALUES);
        return local == null ? Collections.emptyMap() : checkedDraftValues(local, configFields);
    }

    Map<String, Object> applyDraftValues(Map<String, Object> saved) {
        Map<String, Object> supplied = draftValues();
        if (supplied.isEmpty()) return saved;
        Map<String, Object> merged = new LinkedHashMap<>(saved);
        merged.putAll(supplied);
        return withDefaults(merged);
    }

    public ServicePrefill servicePrefill() {
        JSONObject local = toJson().optJSONObject(SERVICE_PREFILL);
        return local == null ? new ServicePrefill("", "") : checkedServicePrefill(local, capability);
    }

    private static Map<String, Object> checkedDraftValues(JSONObject supplied, List<ConfigField> descriptors) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Iterator<String> keys = supplied.keys(); keys.hasNext();) {
            String key = keys.next();
            ConfigField field = descriptors.stream().filter(item -> item.key().equals(key)).findFirst()
                    .orElseThrow(SkillManifest::invalid);
            Object value = field.validate(supplied.opt(key));
            if (value instanceof String text && SECRET_LABEL.matcher(text).find()) throw invalid();
            result.put(key, value);
        }
        if (result.isEmpty() || valuesJson(result).toString().length() > MAX_SETTINGS_JSON) throw invalid();
        return Collections.unmodifiableMap(result);
    }

    private static ServicePrefill checkedServicePrefill(JSONObject supplied, SkillCapabilityRegistry.Capability capability) {
        boolean image = capability == SkillCapabilityRegistry.Capability.IMAGE_GENERATION;
        boolean http = capability == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                || capability == SkillCapabilityRegistry.Capability.VIDEO_TASK;
        int count = (supplied.has("endpoint") ? 1 : 0) + (supplied.has("model") ? 1 : 0);
        if ((!http && !image) || count == 0 || supplied.length() != count || (!image && supplied.has("model"))) throw invalid();
        String endpoint = "";
        if (supplied.has("endpoint")) {
            try {
                Object raw = supplied.opt("endpoint");
                if (!(raw instanceof String address) || address.trim().isEmpty()) throw invalid();
                endpoint = SkillCustomService.validateEndpoint(address);
                if (image) endpoint = SkillImageProviderAddress.resolve(endpoint, true);
            } catch (SkillCustomService.Failure | IllegalArgumentException unsafe) { throw invalid(); }
        }
        String model = supplied.has("model") ? checkedText(supplied.opt("model"), 256, false) : "";
        if (SECRET_LABEL.matcher(model).find()) throw invalid();
        return new ServicePrefill(endpoint, model);
    }

    public Map<String, Object> readValues(JSONObject stored) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (stored != null) {
            for (Iterator<String> it = stored.keys(); it.hasNext();) {
                String key = it.next();
                ConfigField field = configFields.stream().filter(item -> item.key().equals(key)).findFirst()
                        .orElseThrow(SkillManifest::invalid);
                values.put(key, field.validate(stored.opt(key)));
            }
        }
        return withDefaults(values);
    }

    public Map<String, Object> enteredValues(Map<String, String> input) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : input.entrySet()) {
            ConfigField field = configFields.stream().filter(item -> item.key().equals(entry.getKey())).findFirst()
                    .orElseThrow(SkillManifest::invalid);
            String raw = entry.getValue();
            if (raw != null && !raw.trim().isEmpty()) values.put(field.key(), field.fromForm(raw.trim()));
        }
        return withDefaults(values);
    }

    public Map<String, Object> retainValues(SkillManifest previous, Map<String, Object> saved) {
        Map<String, Object> values = new LinkedHashMap<>();
        if (previous != null) {
            for (ConfigField field : configFields) {
                if (!saved.containsKey(field.key())) continue;
                boolean compatible = previous.configFields().stream().anyMatch(before ->
                        before.key().equals(field.key()) && before.type() == field.type());
                if (compatible) {
                    try { values.put(field.key(), field.validate(saved.get(field.key()))); }
                    catch (IllegalArgumentException changedConstraint) { /* Require the updated field again. */ }
                }
            }
        }
        return withDefaults(values);
    }

    private Map<String, Object> withDefaults(Map<String, Object> values) {
        for (ConfigField field : configFields) {
            if (field.defaultValue() != null) values.putIfAbsent(field.key(), field.defaultValue());
        }
        if (valuesJson(values).toString().length() > MAX_SETTINGS_JSON) throw invalid();
        return Collections.unmodifiableMap(values);
    }

    static JSONObject valuesJson(Map<String, Object> values) {
        JSONObject data = new JSONObject();
        try {
            for (Map.Entry<String, Object> entry : values.entrySet()) data.put(entry.getKey(), entry.getValue());
        } catch (JSONException malformed) {
            throw invalid();
        }
        return data;
    }

    public List<String> missing(Map<String, Object> values) {
        List<String> missing = configFields.stream().filter(field -> field.required() && !values.containsKey(field.key()))
                .map(ConfigField::key).collect(Collectors.toList());
        return Collections.unmodifiableList(missing);
    }

    private static int integer(Object value, int min, int max) {
        if (!(value instanceof Integer integer) || integer < min || integer > max) throw invalid();
        return integer;
    }

    private static BigDecimal number(Object value) {
        if (!(value instanceof Number)) throw invalid();
        try {
            BigDecimal decimal = new BigDecimal(value.toString()).stripTrailingZeros();
            if (decimal.scale() > 6 || decimal.abs().compareTo(MAX_NUMBER) > 0) throw invalid();
            return decimal;
        } catch (NumberFormatException invalidNumber) { throw invalid(); }
    }

    private static String fieldText(Object value, int maximum) {
        String text = checkedText(value, maximum, false);
        if (SECRET_LABEL.matcher(text).find()) throw invalid();
        return text;
    }

    private static String checkedText(Object value, int maximum, boolean multiline) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.length() > maximum
                || ADDRESS.matcher(text).find() || text.codePoints().anyMatch(c -> Character.isISOControl(c)
                && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) throw invalid();
        SkillAuthoring.rejectCredential(text);
        return text;
    }

    private static void keys(JSONObject object, Set<String> optional, String... required) throws JSONException {
        Set<String> accepted = new HashSet<>(List.of(required));
        accepted.addAll(optional);
        for (String key : required) if (!object.has(key)) throw invalid();
        for (Iterator<String> it = object.keys(); it.hasNext();) if (!accepted.contains(it.next())) throw invalid();
    }

    private static Invalid invalid() {
        return new Invalid("技能清单字段、版本或参数无效");
    }
}
