package com.douai.module.ai.skills;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.HashSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Compiles a documented relative JSON submission and MP4 polling chain without using document servers. */
public final class SkillOpenApiVideo {
    private static final int MAX_SOURCE = 300_000;
    private static final int MAX_NODES = 30_000;
    private static final int MAX_PATHS = 1200;
    private static final int MAX_REFS = 8;
    private static final Pattern OPEN_API = Pattern.compile("3\\.[0-9]+\\.[0-9]+");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_~-][A-Za-z0-9._~-]{0,63}");
    private static final Pattern TASK_SEGMENT = Pattern.compile("\\{([A-Za-z][A-Za-z0-9_-]{0,31})}");
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern PRIVATE_FIELD = Pattern.compile("(?i)api[_-]*key|authorization|password|private[_-]*key|"
            + "secret|credential|cookie|bearer|token$|^auth$");
    private static final Pattern REF = Pattern.compile("#/components/(schemas|requestBodies|responses|parameters|pathItems|securitySchemes)/([A-Za-z0-9_\\-.]{1,96})");

    private final JSONObject document;
    private final JSONObject components;
    private final JSONObject paths;

    private SkillOpenApiVideo(JSONObject document) throws JSONException {
        this.document = document;
        components = document.optJSONObject("components");
        paths = document.getJSONObject("paths");
    }

    public static SkillVideoFacts read(String json) {
        try {
            JSONObject document = SkillAuthoring.readObject(json, MAX_SOURCE, MAX_NODES);
            if (!(document.opt("openapi") instanceof String version) || !OPEN_API.matcher(version).matches()) {
                return SkillVideoFacts.empty();
            }
            return new SkillOpenApiVideo(document).extract();
        } catch (JSONException | IllegalArgumentException invalid) {
            return SkillVideoFacts.empty();
        }
    }

    private SkillVideoFacts extract() {
        SkillVideoFacts found = SkillVideoFacts.empty();
        if (paths.length() > MAX_PATHS) return found;
        for (Iterator<String> keys = paths.keys(); keys.hasNext();) {
            String submitPath = keys.next();
            SkillVideoFacts candidate = candidate(submitPath);
            if (candidate.isEmpty()) continue;
            if (!found.isEmpty()) return SkillVideoFacts.empty();
            found = candidate;
        }
        return found;
    }

    private SkillVideoFacts candidate(String submitPath) {
        if (!staticPath(submitPath)) return SkillVideoFacts.empty();
        try {
            JSONObject submit = operation(submitPath, "post", "");
            if (submit == null || submit.has("callbacks")) return SkillVideoFacts.empty();
            JSONObject body = content(resolve(submit.optJSONObject("requestBody"), "requestBodies"), "application/json");
            if (body == null) return SkillVideoFacts.empty();
            Request request = request(body);
            JSONObject submitFields = responseFields(submit, "200", "201", "202");
            SkillVideoFacts found = SkillVideoFacts.empty();
            String prefix = submitPath + "/";
            for (Iterator<String> keys = paths.keys(); keys.hasNext();) {
                String statusPath = keys.next();
                if (!statusPath.startsWith(prefix)) continue;
                Matcher parameter = TASK_SEGMENT.matcher(statusPath.substring(prefix.length()));
                if (!parameter.matches()) continue;
                SkillVideoFacts candidate = polling(submitPath, submitFields, request, statusPath, parameter.group(1));
                if (candidate.isEmpty()) continue;
                if (!found.isEmpty()) return SkillVideoFacts.empty();
                found = candidate;
            }
            return found;
        } catch (JSONException | IllegalArgumentException invalid) {
            return SkillVideoFacts.empty();
        }
    }

    private SkillVideoFacts polling(String submitPath, JSONObject submitFields, Request request, String statusPath,
                                    String parameter) throws JSONException {
        JSONObject statusOperation = operation(statusPath, "get", parameter);
        if (statusOperation == null || statusOperation.has("requestBody")) return SkillVideoFacts.empty();
        JSONObject statusFields = responseFields(statusOperation, "200");
        String submitId = idField(submitFields, parameter);
        String statusId = idField(statusFields, parameter);
        States states = states(statusFields);
        String media = mediaPath(statusPath, parameter);
        if (submitId.isEmpty() || statusId.isEmpty() || states == null || media.isEmpty()) return SkillVideoFacts.empty();
        String canonicalStatus = submitPath + "/{id}";
        SkillVideoFacts facts = SkillVideoFacts.fromDocument(new JSONObject().put("version", 1)
                .put("submitPath", submitPath).put("submitEncoding", "application/json")
                .put("submitIdField", submitId).put("statusPath", canonicalStatus).put("statusIdField", statusId)
                .put("stateField", states.field()).put("states", states.values()).put("downloadPath", canonicalStatus + media)
                .put("request", request.mapping()).put("configFields", request.fields()));
        if (!facts.isComplete()) return SkillVideoFacts.empty();
        facts.declaration();
        return facts;
    }

    private String mediaPath(String statusPath, String parameter) {
        String found = "";
        for (Iterator<String> keys = paths.keys(); keys.hasNext();) {
            String path = keys.next();
            if (!path.startsWith(statusPath + "/")) continue;
            String tail = path.substring(statusPath.length());
            if (!staticPath(tail)) continue;
            JSONObject get = operation(path, "get", parameter);
            if (get == null || get.has("requestBody") || responseContent(get, "200", "video/mp4") == null) continue;
            if (!found.isEmpty()) return "";
            found = tail;
        }
        return found;
    }

    private record Request(JSONObject mapping, JSONArray fields) {}

    private Request request(JSONObject body) throws JSONException {
        JSONObject schema = resolve(body.optJSONObject("schema"), "schemas");
        JSONObject properties = objectFields(schema);
        JSONObject prompt = properties == null ? null : resolve(properties.optJSONObject("prompt"), "schemas");
        if (!compatibleString(prompt) || prompt.optBoolean("readOnly") || prompt.has("enum")) throw invalid();
        Set<String> required = required(schema);
        required.remove("prompt");
        JSONObject example = body.optJSONObject("example");
        JSONObject mapping = new JSONObject().put("prompt", "argument");
        JSONArray fields = new JSONArray();
        Set<String> keys = new HashSet<>();
        for (Iterator<String> names = properties.keys(); names.hasNext();) {
            String name = names.next();
            if ("prompt".equals(name)) continue;
            boolean necessary = required.remove(name);
            JSONObject definition = resolve(properties.optJSONObject(name), "schemas");
            Object defaultValue = definition == null ? null : definition.opt("default");
            if (defaultValue == null && necessary && example != null) defaultValue = example.opt(name);
            if (!necessary && defaultValue == null) continue;
            JSONObject field = config(name, definition, necessary, defaultValue);
            if (field == null) {
                if (necessary) throw invalid();
                continue;
            }
            String key = field.getString("key");
            if (!keys.add(key) || fields.length() >= 6) throw invalid();
            fields.put(field);
            mapping.put(name, "config." + key);
        }
        if (!required.isEmpty()) throw invalid();
        return new Request(mapping, fields);
    }

    private static JSONObject config(String name, JSONObject schema, boolean required, Object defaultValue) throws JSONException {
        if (schema == null || !PROPERTY.matcher(name).matches() || PRIVATE_FIELD.matcher(name).find()
                || composed(schema) || schema.optBoolean("readOnly") || schema.has("format")) return null;
        String key = name.toLowerCase(Locale.ROOT).replace('-', '_');
        String type = switch (schema.optString("type")) {
            case "string" -> schema.has("enum") ? "enum" : "text";
            case "integer" -> "integer";
            case "number" -> "decimal";
            case "boolean" -> "boolean";
            default -> "";
        };
        if (type.isEmpty() || schema.has("enum") && !"enum".equals(type)) return null;
        JSONObject field = new JSONObject().put("key", key).put("label", name).put("type", type).put("required", required);
        if ("enum".equals(type)) {
            JSONArray options = schema.optJSONArray("enum");
            if (options == null || options.length() < 2 || options.length() > 8) return null;
            for (int i = 0; i < options.length(); i++) if (!(options.opt(i) instanceof String)) return null;
            field.put("options", options);
        } else if ("integer".equals(type) || "decimal".equals(type)) {
            if (schema.has("minimum")) field.put("min", schema.get("minimum"));
            if (schema.has("maximum")) field.put("max", schema.get("maximum"));
        } else if ("text".equals(type) && schema.opt("maxLength") instanceof Integer max && max > 0) {
            field.put("maxLength", Math.min(120, max));
        }
        if (defaultValue != null) {
            if (!switch (type) {
                case "text", "enum" -> defaultValue instanceof String;
                case "integer" -> defaultValue instanceof Integer;
                case "decimal" -> defaultValue instanceof Number;
                case "boolean" -> defaultValue instanceof Boolean;
                default -> false;
            }) return null;
            field.put("default", defaultValue);
        }
        return field;
    }

    private String idField(JSONObject fields, String parameter) {
        String unique = "";
        boolean ambiguous = false;
        if (fields != null) for (Iterator<String> keys = fields.keys(); keys.hasNext();) {
            String field = keys.next();
            if (!PROPERTY.matcher(field).matches() || !compatibleString(resolve(fields.optJSONObject(field), "schemas"))) continue;
            String semantic = memberRole(field);
            if (field.equals(parameter)) return field;
            if (switch (semantic) { case "id", "taskid", "jobid", "videoid", "requestid" -> true; default -> false; }) {
                ambiguous |= !unique.isEmpty();
                unique = field;
            }
        }
        return ambiguous ? "" : unique;
    }

    private record States(String field, JSONObject values) {}

    private States states(JSONObject fields) throws JSONException {
        States found = null;
        if (fields != null) for (Iterator<String> keys = fields.keys(); keys.hasNext();) {
            String field = keys.next();
            if (!PROPERTY.matcher(field).matches() || !switch (memberRole(field)) {
                case "state", "status", "taskstate", "taskstatus", "jobstate", "jobstatus" -> true;
                default -> false;
            }) continue;
            JSONObject definition = resolve(fields.optJSONObject(field), "schemas");
            JSONArray values = compatibleString(definition) ? definition.optJSONArray("enum") : null;
            if (values == null || values.length() == 0 || values.length() > 12) continue;
            JSONObject mapping = new JSONObject();
            boolean complete = false;
            for (int i = 0; i < values.length(); i++) {
                if (!(values.opt(i) instanceof String raw) || mapping.has(raw)) throw invalid();
                String normalized = switch (memberRole(raw)) {
                    case "queued", "pending", "waiting", "submitted", "notstarted" -> "queued";
                    case "inprogress", "running", "processing" -> "running";
                    case "completed", "complete", "done", "succeeded", "success" -> "completed";
                    case "failed", "failure", "error" -> "failed";
                    case "cancelled", "canceled" -> "cancelled";
                    default -> throw invalid();
                };
                mapping.put(raw, normalized);
                complete |= "completed".equals(normalized);
            }
            if (!complete) continue;
            if (found != null) throw invalid();
            found = new States(field, mapping);
        }
        return found;
    }

    private JSONObject responseFields(JSONObject operation, String... codes) {
        JSONObject found = null;
        for (String code : codes) {
            JSONObject media = responseContent(operation, code, "application/json");
            if (media == null) continue;
            JSONObject properties = objectFields(resolve(media.optJSONObject("schema"), "schemas"));
            if (properties == null || found != null && !found.toString().equals(properties.toString())) return null;
            found = properties;
        }
        return found;
    }

    private JSONObject responseContent(JSONObject operation, String code, String mime) {
        JSONObject responses = operation.optJSONObject("responses");
        return responses == null ? null : content(resolve(responses.optJSONObject(code), "responses"), mime);
    }

    private JSONObject operation(String path, String method, String parameter) {
        JSONObject item = resolve(paths.optJSONObject(path), "pathItems");
        if (item == null || !parameters(item, parameter)) return null;
        JSONObject operation = item.optJSONObject(method);
        return operation == null || !parameters(operation, parameter) || !compatibleSecurity(operation) ? null : operation;
    }

    private boolean compatibleSecurity(JSONObject operation) {
        JSONObject source = operation.has("security") ? operation : document;
        if (!source.has("security")) return true;
        JSONArray alternatives = source.optJSONArray("security");
        if (alternatives == null || alternatives.length() > 16) return false;
        if (alternatives.length() == 0) return true;
        JSONObject schemes = components == null ? null : components.optJSONObject("securitySchemes");
        for (int i = 0; i < alternatives.length(); i++) {
            JSONObject requirement = alternatives.optJSONObject(i);
            if (requirement == null) continue;
            if (requirement.length() == 0) return true;
            if (requirement.length() != 1 || schemes == null) continue;
            String name = requirement.keys().next();
            JSONArray scopes = requirement.optJSONArray(name);
            JSONObject scheme = resolve(schemes.optJSONObject(name), "securitySchemes");
            if (scheme != null && scopes != null && scopes.length() == 0
                    && "http".equals(scheme.opt("type")) && "bearer".equalsIgnoreCase(scheme.optString("scheme"))) return true;
        }
        return false;
    }

    private boolean parameters(JSONObject source, String taskParameter) {
        if (!source.has("parameters")) return true;
        JSONArray parameters = source.optJSONArray("parameters");
        if (parameters == null || parameters.length() > 64) return false;
        for (int i = 0; i < parameters.length(); i++) {
            JSONObject parameter = resolve(parameters.optJSONObject(i), "parameters");
            if (parameter == null) return false;
            if (!parameter.optBoolean("required")) continue;
            if (!"path".equals(parameter.opt("in")) || !taskParameter.equals(parameter.opt("name"))
                    || !compatibleString(resolve(parameter.optJSONObject("schema"), "schemas"))) return false;
        }
        return true;
    }

    private JSONObject resolve(JSONObject object, String group) {
        for (int depth = 0; object != null && object.has("$ref"); depth++) {
            if (depth >= MAX_REFS || object.length() != 1 || components == null
                    || !(object.opt("$ref") instanceof String ref)) return null;
            Matcher path = REF.matcher(ref);
            if (!path.matches() || !group.equals(path.group(1))) return null;
            JSONObject category = components.optJSONObject(group);
            object = category == null ? null : category.optJSONObject(path.group(2));
        }
        return object;
    }

    private static JSONObject content(JSONObject owner, String mime) {
        JSONObject content = owner == null ? null : owner.optJSONObject("content");
        return content == null ? null : content.optJSONObject(mime);
    }

    private static JSONObject objectFields(JSONObject schema) {
        return schema != null && "object".equals(schema.opt("type")) && !composed(schema)
                ? schema.optJSONObject("properties") : null;
    }

    private static Set<String> required(JSONObject schema) {
        Set<String> fields = new HashSet<>();
        if (!schema.has("required")) return fields;
        JSONArray entries = schema.optJSONArray("required");
        if (entries == null || entries.length() > 7) throw invalid();
        for (int i = 0; i < entries.length(); i++) {
            if (!(entries.opt(i) instanceof String name) || !fields.add(name)) throw invalid();
        }
        return fields;
    }

    private static boolean composed(JSONObject schema) {
        return schema.has("oneOf") || schema.has("anyOf") || schema.has("allOf") || schema.has("not") || schema.has("const");
    }

    private static boolean compatibleString(JSONObject schema) {
        return schema != null && "string".equals(schema.opt("type")) && !schema.has("format") && !composed(schema);
    }

    private static boolean staticPath(String path) {
        if (path.length() < 2 || path.length() > 200 || path.charAt(0) != '/') return false;
        for (String segment : path.substring(1).split("/", -1)) if (!SEGMENT.matcher(segment).matches()) return false;
        return true;
    }

    private static String memberRole(String text) { return text.toLowerCase(Locale.ROOT).replace("_", "").replace("-", ""); }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("视频 OpenAPI 合同不完整"); }
}
