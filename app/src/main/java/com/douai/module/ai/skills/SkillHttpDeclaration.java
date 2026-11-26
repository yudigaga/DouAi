package com.douai.module.ai.skills;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Declarative HTTPS operation shape. The local binding supplies the actual endpoint and authorization. */
public final class SkillHttpDeclaration {
    public static final int VERSION = 1;
    private static final int MAX_DECLARATION = 4096;
    private static final int MAX_REQUEST_FIELDS = 7;
    private static final int MAX_ARGUMENT_CHARS = 4000;
    private static final int MAX_REQUEST_BYTES = 64 * 1024;
    private static final int MAX_VIDEO_STATES = 12;
    private static final int MAX_RESPONSE_DEPTH = 4;
    private static final String ARGUMENT = "argument";
    private static final String CONFIG = "config.";
    private static final String TASK_SEGMENT = "{id}";
    private static final Pattern MEMBER = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,31}");
    private static final Pattern PROVIDER_STATE = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    private static final Pattern TASK_ID = Pattern.compile("[A-Za-z0-9_-]{1,128}");
    private static final Pattern STATIC_SEGMENT = Pattern.compile("[A-Za-z0-9._~-]{1,64}");
    private static final Set<String> BODY_SECRET_FIELDS = Set.of(
            "auth", "authorization", "authentication", "apitoken", "accesskey", "accesstoken",
            "refreshtoken", "bearer", "token", "secret", "secretref", "password", "privatekey", "clientsecret",
            "credential", "credentials", "cookie", "setcookie", "headers", "proxyauthorization", "xauthtoken");

    public static final class Invalid extends IllegalArgumentException {
        private Invalid() { super("技能 HTTP 声明、映射或任务路径无效"); }
    }

    public enum VideoState {
        QUEUED, RUNNING, COMPLETED, FAILED, CANCELLED;

        private String wireName() { return name().toLowerCase(Locale.ROOT); }

        private static VideoState fromWire(String wire) {
            for (VideoState state : values()) if (state.wireName().equals(wire)) return state;
            throw new Invalid();
        }
    }

    public enum StatusMethod { GET, POST }

    public record StatusRequest(StatusMethod method, String endpoint, byte[] body) {
        public StatusRequest { body = body == null ? null : body.clone(); }
        @Override public byte[] body() { return body == null ? null : body.clone(); }
        @Override public String toString() { return "Video.StatusRequest"; }
    }

    public record Video(String statusPath, String cancelPath, String cancelMethod, String downloadPath, String resultUrlField,
                        String idField, String stateField, Map<String, VideoState> states, int minPollSeconds,
                        String statusUrlField, String statusPostPath, String statusPostIdField) {
        public Video(String statusPath, String cancelPath, String cancelMethod, String downloadPath, String resultUrlField,
                     String idField, String stateField, Map<String, VideoState> states, int minPollSeconds) {
            this(statusPath, cancelPath, cancelMethod, downloadPath, resultUrlField, idField, stateField, states,
                    minPollSeconds, "", "", "");
        }

        public Video(String statusPath, String cancelPath, String cancelMethod, String downloadPath, String resultUrlField,
                     String idField, String stateField, Map<String, VideoState> states) {
            this(statusPath, cancelPath, cancelMethod, downloadPath, resultUrlField, idField, stateField, states, 0);
        }

        public Video(String statusPath, String cancelPath, String cancelMethod, String downloadPath,
                     String idField, String stateField, Map<String, VideoState> states) {
            this(statusPath, cancelPath, cancelMethod, downloadPath, "", idField, stateField, states, 0);
        }

        public Video {
            if (minPollSeconds != 0 && (minPollSeconds < 3 || minPollSeconds > 60)) throw new Invalid();
            if (statusPath == null || statusUrlField == null || statusPostPath == null || statusPostIdField == null
                    || statusPostPath.isEmpty() != statusPostIdField.isEmpty()) throw new Invalid();
            int modes = (statusPath.isEmpty() ? 0 : 1) + (statusUrlField.isEmpty() ? 0 : 1)
                    + (statusPostPath.isEmpty() ? 0 : 1);
            if (modes != 1) throw new Invalid();
            if (!statusPath.isEmpty()) validatePath(statusPath);
            if (!statusUrlField.isEmpty()) responsePath(statusUrlField);
            if (!statusPostPath.isEmpty()) {
                validatePath(statusPostPath, 0);
                member(statusPostIdField);
            }
            if (cancelPath == null || cancelMethod == null || downloadPath == null || resultUrlField == null
                    || cancelPath.isEmpty() != cancelMethod.isEmpty() || downloadPath.isEmpty() == resultUrlField.isEmpty()) {
                throw new Invalid();
            }
            if (!cancelPath.isEmpty()) {
                validatePath(cancelPath);
                if (!"POST".equals(cancelMethod) && !"DELETE".equals(cancelMethod)) throw new Invalid();
            }
            if (!downloadPath.isEmpty()) validatePath(downloadPath);
            else responsePath(resultUrlField);
            responsePath(idField);
            responsePath(stateField);
            if (idField.equals(stateField) || resultUrlField.equals(idField) || resultUrlField.equals(stateField)
                    || states == null || !states.containsValue(VideoState.COMPLETED)
                    || states.size() > MAX_VIDEO_STATES) throw new Invalid();
            for (Map.Entry<String, VideoState> state : states.entrySet()) {
                if (state.getKey() == null || !PROVIDER_STATE.matcher(state.getKey()).matches()
                        || state.getValue() == null) throw new Invalid();
                checkCredential(state.getKey());
            }
            states = immutableMap(states);
        }

        public VideoState state(String providerState) {
            VideoState state = states.get(providerState);
            if (state == null) throw new Invalid();
            return state;
        }

        public VideoState readStatus(JSONObject response, String expectedId) {
            String original = validateTaskId(expectedId);
            if (!original.equals(readResponseString(response, idField))) throw new Invalid();
            return state(readResponseString(response, stateField));
        }

        public String statusEndpoint(String configuredEndpoint, String id) {
            return statusRequest(configuredEndpoint, id, "").endpoint();
        }

        public String readSubmittedStatusPath(JSONObject response, String configuredEndpoint) {
            if (statusUrlField.isEmpty()) return "";
            return resolveProviderUrl(URI.create(endpoint(configuredEndpoint)),
                    readResponseString(response, statusUrlField)).getRawPath();
        }

        public StatusRequest statusRequest(String configuredEndpoint, String id, String savedStatusPath) {
            String original = validateTaskId(id);
            if (!statusUrlField.isEmpty()) {
                return new StatusRequest(StatusMethod.GET, savedPathEndpoint(configuredEndpoint, savedStatusPath), null);
            }
            if (!"".equals(savedStatusPath)) throw new Invalid();
            if (!statusPath.isEmpty()) return new StatusRequest(StatusMethod.GET,
                    expand(configuredEndpoint, statusPath, original), null);
            try {
                byte[] body = new JSONObject().put(statusPostIdField, original).toString().getBytes(StandardCharsets.UTF_8);
                return new StatusRequest(StatusMethod.POST, expand(configuredEndpoint, statusPostPath, original), body);
            } catch (JSONException malformed) { throw new Invalid(); }
        }

        public boolean canCancel() { return !cancelPath.isEmpty(); }

        public String cancelEndpoint(String configuredEndpoint, String id) {
            if (!canCancel()) throw new Invalid();
            return expand(configuredEndpoint, cancelPath, id);
        }

        public String downloadEndpoint(String configuredEndpoint, String id) {
            if (downloadPath.isEmpty()) throw new Invalid();
            return expand(configuredEndpoint, downloadPath, id);
        }

        String completedDownloadEndpoint(JSONObject response, String configuredEndpoint, String id) {
            return savedDownloadEndpoint(configuredEndpoint, id,
                    completedDownloadPath(response, configuredEndpoint, id, ""));
        }

        public String completedDownloadPath(JSONObject response, String configuredEndpoint, String id, String savedStatusPath) {
            if (readStatus(response, id) != VideoState.COMPLETED) throw new Invalid();
            URI status = URI.create(statusRequest(configuredEndpoint, id, savedStatusPath).endpoint());
            if (resultUrlField.isEmpty()) return URI.create(downloadEndpoint(configuredEndpoint, id)).getRawPath();
            return resolveProviderUrl(status, readResponseString(response, resultUrlField)).getRawPath();
        }

        public String savedDownloadEndpoint(String configuredEndpoint, String id, String savedResultPath) {
            validateTaskId(id);
            if (resultUrlField.isEmpty()) {
                String expected = downloadEndpoint(configuredEndpoint, id);
                if (!"".equals(savedResultPath) && !URI.create(expected).getRawPath().equals(savedResultPath)) {
                    throw new Invalid();
                }
                return expected;
            }
            return savedPathEndpoint(configuredEndpoint, savedResultPath);
        }

        private JSONObject toJson() throws JSONException {
            JSONObject mapping = new JSONObject();
            for (Map.Entry<String, VideoState> state : states.entrySet()) {
                mapping.put(state.getKey(), state.getValue().wireName());
            }
            JSONObject data = new JSONObject();
            if (!statusPath.isEmpty()) data.put("statusPath", statusPath);
            else if (!statusUrlField.isEmpty()) data.put("statusUrlField", statusUrlField);
            else data.put("statusPostPath", statusPostPath).put("statusPostIdField", statusPostIdField);
            if (canCancel()) data.put("cancelPath", cancelPath).put("cancelMethod", cancelMethod);
            if (resultUrlField.isEmpty()) data.put("downloadPath", downloadPath);
            else data.put("resultUrlField", resultUrlField);
            data.put("idField", idField).put("stateField", stateField).put("states", mapping);
            if (minPollSeconds != 0) data.put("minPollSeconds", minPollSeconds);
            return data;
        }
    }

    private final String label;
    private final String suggestedEndpoint;
    private final Map<String, String> request;
    private final String responseField;
    private final Video video;
    private final SkillResult.Type outputType;
    private final Map<String, SkillManifest.ConfigField> mappedFields;

    private SkillHttpDeclaration(String label, String suggestedEndpoint, Map<String, String> request,
                                 String responseField, Video video, SkillResult.Type outputType,
                                 Map<String, SkillManifest.ConfigField> mappedFields) {
        this.label = label;
        this.suggestedEndpoint = suggestedEndpoint;
        this.request = immutableMap(request);
        this.responseField = responseField;
        this.video = video;
        this.outputType = outputType;
        this.mappedFields = immutableMap(mappedFields);
    }

    public String label() { return label; }
    public String suggestedEndpoint() { return suggestedEndpoint; }
    public Map<String, String> request() { return request; }
    public String responseField() { return responseField; }
    public Video video() { return video; }
    public SkillResult.Type outputType() { return outputType; }

    public String readSubmittedTaskId(JSONObject response) {
        if (video == null) throw new Invalid();
        return validateTaskId(readResponseString(response, responseField));
    }

    public static SkillHttpDeclaration parse(String raw, SkillResult.Type outputType,
                                             List<SkillManifest.ConfigField> fields) {
        JSONObject object;
        try {
            object = SkillAuthoring.readObject(raw, MAX_DECLARATION, 128);
        } catch (IllegalArgumentException malformed) {
            throw new Invalid();
        }
        return parse(object, outputType, fields);
    }

    /** The enclosing manifest reader has already rejected duplicate keys and non-JSON tokens. */
    public static SkillHttpDeclaration parse(JSONObject object, SkillResult.Type outputType,
                                             List<SkillManifest.ConfigField> fields) {
        if (object == null || outputType == null || fields == null || object.toString().length() > MAX_DECLARATION) {
            throw new Invalid();
        }
        try {
            keys(object, Set.of("suggestedEndpoint", "video"), "version", "label", "request", "response");
            if (!Integer.valueOf(VERSION).equals(object.get("version"))) throw new Invalid();
            boolean videoOutput = outputType == SkillResult.Type.VIDEO;
            if (videoOutput != object.has("video")) throw new Invalid();
            String label = text(object.get("label"), 48, false);
            if (label.contains("://")) throw new Invalid();
            String suggestion = object.has("suggestedEndpoint") ? suggestion(object.get("suggestedEndpoint")) : "";
            Object responseField = object.get("response");
            String response = videoOutput ? responsePath(responseField) : member(responseField);

            Map<String, SkillManifest.ConfigField> declared = new LinkedHashMap<>();
            for (SkillManifest.ConfigField field : fields) {
                if (field == null || declared.put(field.key(), field) != null) throw new Invalid();
            }
            JSONObject mappings = object.getJSONObject("request");
            if (mappings.length() == 0 || mappings.length() > MAX_REQUEST_FIELDS) throw new Invalid();
            Map<String, String> request = new LinkedHashMap<>();
            Map<String, SkillManifest.ConfigField> used = new LinkedHashMap<>();
            for (Iterator<String> names = mappings.keys(); names.hasNext();) {
                String name = member(names.next());
                String source = text(mappings.get(name), 39, false);
                if (!ARGUMENT.equals(source)) {
                    if (!source.startsWith(CONFIG)) throw new Invalid();
                    String key = member(source.substring(CONFIG.length()));
                    SkillManifest.ConfigField field = declared.get(key);
                    if (field == null) throw new Invalid();
                    used.put(key, field);
                }
                request.put(name, source);
            }
            if (!request.containsValue(ARGUMENT)) throw new Invalid();
            Video video = videoOutput ? readVideo(object.getJSONObject("video")) : null;
            if (video != null && overlapsResponsePath(response, video.statusUrlField())) throw new Invalid();
            return new SkillHttpDeclaration(label, suggestion, request, response, video, outputType, used);
        } catch (JSONException malformed) {
            throw new Invalid();
        }
    }

    public JSONObject toJson() {
        try {
            JSONObject data = new JSONObject().put("version", VERSION).put("label", label)
                    .put("request", new JSONObject(request)).put("response", responseField);
            if (!suggestedEndpoint.isEmpty()) data.put("suggestedEndpoint", suggestedEndpoint);
            if (video != null) data.put("video", video.toJson());
            return data;
        } catch (JSONException impossible) {
            throw new Invalid();
        }
    }

    public byte[] requestBody(String argument, Map<String, Object> config) {
        String input = text(argument, MAX_ARGUMENT_CHARS, true);
        Map<String, Object> values = config == null ? Collections.emptyMap() : config;
        JSONObject json = new JSONObject();
        try {
            for (Map.Entry<String, String> mapping : request.entrySet()) {
                String source = mapping.getValue();
                if (ARGUMENT.equals(source)) {
                    json.put(mapping.getKey(), input);
                    continue;
                }
                SkillManifest.ConfigField field = mappedFields.get(source.substring(CONFIG.length()));
                Object value = values.get(field.key());
                if (value == null) value = field.defaultValue();
                if (value != null) json.put(mapping.getKey(), typedValue(field, value));
                else if (field.required()) throw new Invalid();
            }
            byte[] bytes = json.toString().getBytes(StandardCharsets.UTF_8);
            if (bytes.length > MAX_REQUEST_BYTES) throw new Invalid();
            return bytes;
        } catch (JSONException malformed) {
            throw new Invalid();
        }
    }

    public static String validateTaskId(String id) {
        if (id == null || !TASK_ID.matcher(id).matches()) throw new Invalid();
        checkCredential(id);
        return id;
    }

    @Override public boolean equals(Object other) {
        if (!(other instanceof SkillHttpDeclaration declaration)) return false;
        return outputType == declaration.outputType && label.equals(declaration.label)
                && suggestedEndpoint.equals(declaration.suggestedEndpoint) && request.equals(declaration.request)
                && responseField.equals(declaration.responseField) && Objects.equals(video, declaration.video)
                && mappedFields.equals(declaration.mappedFields);
    }

    @Override public int hashCode() {
        return Objects.hash(label, suggestedEndpoint, request, responseField, video, outputType, mappedFields);
    }

    private static Video readVideo(JSONObject value) throws JSONException {
        keys(value, Set.of("statusPath", "statusUrlField", "statusPostPath", "statusPostIdField", "cancelPath",
                "cancelMethod", "downloadPath", "resultUrlField", "minPollSeconds"), "idField", "stateField", "states");
        int minPollSeconds = 0;
        if (value.has("minPollSeconds")) {
            if (!(value.opt("minPollSeconds") instanceof Integer seconds) || seconds == 0) throw new Invalid();
            minPollSeconds = seconds;
        }
        JSONObject mapping = value.getJSONObject("states");
        if (mapping.length() == 0 || mapping.length() > MAX_VIDEO_STATES) throw new Invalid();
        Map<String, VideoState> states = new LinkedHashMap<>();
        for (Iterator<String> names = mapping.keys(); names.hasNext();) {
            String state = names.next();
            states.put(state, VideoState.fromWire(text(mapping.get(state), 10, false)));
        }
        return new Video(value.has("statusPath") ? text(value.get("statusPath"), 320, false) : "",
                value.has("cancelPath") ? text(value.get("cancelPath"), 320, false) : "",
                value.has("cancelMethod") ? text(value.get("cancelMethod"), 6, false) : "",
                value.has("downloadPath") ? text(value.get("downloadPath"), 320, false) : "",
                value.has("resultUrlField") ? responsePath(value.get("resultUrlField")) : "",
                responsePath(value.get("idField")), responsePath(value.get("stateField")), states, minPollSeconds,
                value.has("statusUrlField") ? responsePath(value.get("statusUrlField")) : "",
                value.has("statusPostPath") ? text(value.get("statusPostPath"), 320, false) : "",
                value.has("statusPostIdField") ? member(value.get("statusPostIdField")) : "");
    }

    private static int httpsPort(URI endpoint) { return endpoint.getPort() < 0 ? 443 : endpoint.getPort(); }

    private static boolean overlapsResponsePath(String first, String second) {
        return !second.isEmpty() && (first.equals(second) || first.startsWith(second + ".") || second.startsWith(first + "."));
    }

    private static URI resolveProviderUrl(URI base, String raw) {
        String link = text(raw, 2048, false);
        URI candidate;
        try { candidate = URI.create(link); } catch (IllegalArgumentException invalid) { throw new Invalid(); }
        if (link.startsWith("//") || candidate.isOpaque() || candidate.getRawUserInfo() != null
                || candidate.getRawQuery() != null || candidate.getRawFragment() != null || !candidate.equals(candidate.normalize())
                || !candidate.isAbsolute() && candidate.getRawAuthority() != null) throw new Invalid();
        String path = candidate.getPath();
        String rawPath = candidate.getRawPath().toLowerCase(Locale.ROOT);
        if (rawPath.contains("%2f") || rawPath.contains("%5c") || rawPath.contains("%25")
                || path.codePoints().anyMatch(c -> c == '\\' || Character.isISOControl(c))) throw new Invalid();
        for (String segment : path.split("/", -1)) {
            if (".".equals(segment) || "..".equals(segment)) throw new Invalid();
        }
        URI target = URI.create(endpoint(base.resolve(candidate).toString()));
        if (!base.getHost().equalsIgnoreCase(target.getHost()) || httpsPort(base) != httpsPort(target)) throw new Invalid();
        checkCredential(target.getPath());
        return target;
    }

    private static String savedPathEndpoint(String configuredEndpoint, String path) {
        if (path == null || !path.startsWith("/")) throw new Invalid();
        URI target = resolveProviderUrl(URI.create(endpoint(configuredEndpoint)), path);
        if (!path.equals(target.getRawPath())) throw new Invalid();
        return target.toString();
    }

    private static String expand(String configuredEndpoint, String template, String id) {
        URI configured = URI.create(endpoint(configuredEndpoint));
        String path = template.replace(TASK_SEGMENT, validateTaskId(id));
        return "https://" + configured.getRawAuthority() + path;
    }

    private static void validatePath(String path) {
        validatePath(path, 1);
    }

    private static void validatePath(String path, int expectedPlaceholders) {
        if (path == null || path.length() > 320 || !path.startsWith("/") || path.startsWith("//")) throw new Invalid();
        checkCredential(path);
        String[] segments = path.substring(1).split("/", -1);
        int placeholders = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            if (TASK_SEGMENT.equals(segment)) {
                placeholders++;
            } else if (!(segment.isEmpty() && i == segments.length - 1)
                    && (!STATIC_SEGMENT.matcher(segment).matches() || ".".equals(segment) || "..".equals(segment))) {
                throw new Invalid();
            }
        }
        if (placeholders != expectedPlaceholders) throw new Invalid();
    }

    private static String suggestion(Object raw) {
        String canonical = endpoint(text(raw, 2048, false));
        checkCredential(URI.create(canonical).getPath());
        return canonical;
    }

    private static String endpoint(String raw) {
        try {
            return SkillHttpsTransport.validateEndpoint(raw);
        } catch (SkillHttpsTransport.Failure invalid) {
            throw new Invalid();
        }
    }

    private static Object typedValue(SkillManifest.ConfigField field, Object value) {
        return switch (field.type()) {
            case TEXT, MULTILINE -> text(value, field.maxLength(), field.type() == SkillManifest.FieldType.MULTILINE);
            case ENUM -> {
                if (!(value instanceof String selection) || !field.options().contains(selection)) throw new Invalid();
                yield text(selection, 48, false);
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) throw new Invalid();
                yield value;
            }
            case INTEGER, DECIMAL -> {
                BigDecimal number;
                if (value instanceof Integer integer) number = BigDecimal.valueOf(integer);
                else if (field.type() == SkillManifest.FieldType.DECIMAL && value instanceof BigDecimal decimal) {
                    number = decimal;
                } else throw new Invalid();
                if (field.min() == null || field.max() == null || number.compareTo(field.min()) < 0
                        || number.compareTo(field.max()) > 0 || number.stripTrailingZeros().scale() > 6) {
                    throw new Invalid();
                }
                yield value;
            }
        };
    }

    private static void keys(JSONObject object, Set<String> optional, String... required) {
        for (String key : required) if (!object.has(key)) throw new Invalid();
        List<String> mandatory = java.util.Arrays.asList(required);
        for (Iterator<String> names = object.keys(); names.hasNext();) {
            String key = names.next();
            if (!optional.contains(key) && !mandatory.contains(key)) throw new Invalid();
        }
    }

    private static String member(Object value) {
        String name = text(value, 32, false);
        String folded = name.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        if (!MEMBER.matcher(name).matches() || folded.endsWith("apikey") || BODY_SECRET_FIELDS.contains(folded)) {
            throw new Invalid();
        }
        return name;
    }

    private static String responsePath(Object value) {
        String path = text(value, MAX_RESPONSE_DEPTH * 33 - 1, false);
        String[] segments = path.split("\\.", -1);
        if (segments.length > MAX_RESPONSE_DEPTH) throw new Invalid();
        for (String segment : segments) member(segment);
        return path;
    }

    private static String readResponseString(JSONObject response, String path) {
        Object value = response;
        for (String segment : path.split("\\.", -1)) {
            if (!(value instanceof JSONObject object)) throw new Invalid();
            value = object.opt(segment);
        }
        if (!(value instanceof String text)) throw new Invalid();
        return text;
    }

    private static String text(Object value, int max, boolean multiline) {
        if (!(value instanceof String text) || text.length() > max || text.trim().isEmpty()
                || text.codePoints().anyMatch(c -> c >= 0xd800 && c <= 0xdfff || Character.getType(c) == Character.FORMAT
                || Character.isISOControl(c) && !(multiline && (c == '\n' || c == '\r' || c == '\t')))) {
            throw new Invalid();
        }
        checkCredential(text);
        return text;
    }

    private static void checkCredential(String value) {
        try {
            SkillAuthoring.rejectCredential(value);
        } catch (IllegalArgumentException unsafe) {
            throw new Invalid();
        }
    }

    private static <K, V> Map<K, V> immutableMap(Map<K, V> values) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }
}
