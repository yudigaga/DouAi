package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structural redaction and endpoint-bound restoration; no secret values appear in diagnostics. */
final class ConfigSecrets {
    static final String OMITTED = "__DOUAI_SECRET_OMITTED__";
    private static final Pattern URL = Pattern.compile("https?://[^\\s\\\"<>]+", Pattern.CASE_INSENSITIVE);
    private static final Pattern TOKEN = Pattern.compile("(?i)^(?:Bearer\\s+.+|Basic\\s+.+|sk-[A-Za-z0-9_-]{16,}|gh[pousr]_[A-Za-z0-9]{20,})$");
    private ConfigSecrets() { }

    static String redact(String key, String value) {
        if (sensitive(key)) return OMITTED;
        if (value.isEmpty() || OMITTED.equals(value)) return value;
        if (AiModelSettings.KEY.equals(key) || key.endsWith("headers")) {
            Object parsed = ConfigJson.parse(value);
            return walk(parsed, key.endsWith("headers"), false).toString();
        }
        return isEndpointField(key) ? redactEndpoint(value) : value;
    }

    private static Object walk(Object value, boolean headers, boolean parameters) {
        try {
            if (value instanceof JSONObject) {
                JSONObject source = (JSONObject) value, result = new JSONObject();
                Iterator<String> keys = source.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); Object item = source.get(key);
                    boolean hidden = sensitive(key) || headers && !safeHeader(key)
                            || parameters && !(item instanceof JSONObject || item instanceof JSONArray)
                            && !safeParameter(key, item);
                    if (hidden && item instanceof String) result.put(key, OMITTED);
                    else if (item instanceof String && ("customHeaders".equals(key) || "customParameters".equals(key))) {
                        String text = (String) item;
                        result.put(key, walk(ConfigJson.parse(text.isEmpty() ? "{}" : text), "customHeaders".equals(key), "customParameters".equals(key)).toString());
                    } else if (hidden && !JSONObject.NULL.equals(item)) result.put(key, OMITTED);
                    else if (parameters && safeParameter(key, item)) result.put(key, item);
                    else if (item instanceof String && !headers && !parameters) result.put(key,
                            "apiEndpoint".equals(key) || "modelsEndpoint".equals(key) ? redactEndpoint((String) item) : item);
                    else result.put(key, walk(item, false, parameters));
                }
                return result;
            }
            if (value instanceof JSONArray) {
                JSONArray result = new JSONArray(), source = (JSONArray) value;
                for (int i = 0; i < source.length(); i++) result.put(walk(source.get(i), false, parameters));
                return result;
            }
            if (parameters && !JSONObject.NULL.equals(value)) return OMITTED;
            return value instanceof String ? redactText((String) value) : value;
        } catch (Exception failure) { throw ConfigJson.invalid(); }
    }

    private static boolean safeHeader(String key) {
        String normalized = key.toLowerCase(Locale.ROOT);
        return normalized.equals("content-type") || normalized.equals("accept") || normalized.equals("accept-language")
                || normalized.equals("anthropic-version");
    }

    private static boolean safeParameter(String key, Object value) {
        return value instanceof Number && (key.equals("temperature") || key.equals("top_p") || key.equals("top_k")
                || key.equals("max_tokens") || key.equals("max_output_tokens") || key.equals("seed")
                || key.equals("frequency_penalty") || key.equals("presence_penalty"));
    }

    static boolean sensitive(String key) {
        if (FishSpeechSettings.MAX_NEW_TOKENS.equals(key)) return false;
        String name = key.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        // Token budgets are generation settings. Their numeric/boolean types remain validated separately.
        if (java.util.Arrays.asList("maxtokens", "maxtokensenabled", "maxoutputtokens", "maxcompletiontokens",
                "mintokens", "contexttokens").contains(name)) return false;
        return name.equals("key") || name.contains("apikey") || name.contains("secret") || name.contains("password")
                || name.contains("passwd") || name.contains("token") || name.contains("authorization")
                || name.contains("credential") || name.contains("cookie") || name.equals("auth") || name.equals("sign")
                || name.equals("signature");
    }

    private static String redactText(String text) {
        if (TOKEN.matcher(text).matches()) return OMITTED;
        Matcher matcher = URL.matcher(text); StringBuffer result = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(result, Matcher.quoteReplacement(redactUrl(matcher.group())));
        matcher.appendTail(result); return result.toString();
    }

    private static boolean isEndpointField(String key) {
        return ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key) || SpeechSettings.GET_TEMPLATE.equals(key)
                || SpeechSettings.VOICE_GET_TEMPLATE.equals(key) || SpeechSettings.MINIMAX_BASE_URL.equals(key)
                || BreezeSpeechSettings.BASE_URL.equals(key)
                || FishSpeechSettings.BASE_URL.equals(key)
                || "streak_content_api".equals(key);
    }

    private static String redactEndpoint(String value) {
        if (value.isEmpty() || OMITTED.equals(value)) return value;
        try {
            String concrete = value.replaceAll("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}", "douai-placeholder");
            java.net.URI uri = java.net.URI.create(concrete);
            if (uri.getHost() == null || !("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))) return OMITTED;
            return redactText(value);
        } catch (RuntimeException invalid) { return OMITTED; }
    }

    private static String decoded(String text) {
        try { return URLDecoder.decode(text, StandardCharsets.UTF_8.name()); }
        catch (Exception failure) { throw ConfigJson.invalid(); }
    }

    private static String redactUrl(String url) {
        int scheme = url.indexOf("://") + 3, query = url.indexOf('?', scheme), fragment = url.indexOf('#', scheme);
        int end = query < 0 ? (fragment < 0 ? url.length() : fragment) : query;
        String head = url.substring(0, end);
        int slash = head.indexOf('/', scheme), at = head.lastIndexOf('@');
        if (at >= scheme && (slash < 0 || at < slash)) head = head.substring(0, scheme) + OMITTED + head.substring(at);
        String[] parts = head.split("/", -1);
        for (int i = 3; i < parts.length; i++) {
            if (i > 3 && sensitive(decoded(parts[i - 1])) && !placeholder(parts[i])) parts[i] = OMITTED;
            int eq = parts[i].indexOf('=');
            if (eq > 0 && sensitive(decoded(parts[i].substring(0, eq)))) parts[i] = parts[i].substring(0, eq + 1) + OMITTED;
            if (!placeholder(parts[i]) && !publicPath(decoded(parts[i]))) parts[i] = OMITTED;
        }
        StringBuilder result = new StringBuilder(String.join("/", parts));
        if (query >= 0) {
            int last = fragment >= query ? fragment : url.length();
            String[] parameters = url.substring(query + 1, last).split("&", -1);
            for (int i = 0; i < parameters.length; i++) {
                int eq = parameters[i].indexOf('=');
                if (eq < 0 && !placeholder(parameters[i])) { parameters[i] = OMITTED; continue; }
                if (eq >= 0 && !publicQuery(decoded(parameters[i].substring(0, eq)))
                        && !placeholder(parameters[i].substring(eq + 1))) parameters[i] = parameters[i].substring(0, eq + 1) + OMITTED;
            }
            result.append('?').append(String.join("&", parameters));
        }
        if (fragment >= 0) result.append('#').append(OMITTED);
        return result.toString();
    }

    private static boolean placeholder(String value) {
        String decoded = decoded(value);
        return decoded.isEmpty() || decoded.matches("\\{[a-zA-Z_][a-zA-Z0-9_]*\\}") || OMITTED.equals(value);
    }

    private static boolean publicPath(String value) {
        return value.matches("(?i)v[0-9]+(?:beta[0-9]*)?") || java.util.Arrays.asList("api", "API", "chat", "completions",
                "responses", "messages", "models", "parse", "tts", "audio", "speech", "voice", "voices", "generateContent",
                "api.php", "ys.php", "wzzyypromax", "appSecret", "app_secret", "apiKey", "apikey", "token", "key").contains(value);
    }

    private static boolean publicQuery(String value) {
        return java.util.Arrays.asList("app_id", "voice", "language", "lang", "format", "speed", "pitch", "model").contains(value);
    }

    static String endpointKey(String key) {
        if (key.startsWith("third_party_")) return ThirdPartyDownloadSettings.GET_TEMPLATE;
        if (key.startsWith("tts_minimax_")) return SpeechSettings.MINIMAX_BASE_URL;
        if (key.startsWith("tts_breeze_")) return BreezeSpeechSettings.BASE_URL;
        if (key.startsWith("tts_fish_")) return FishSpeechSettings.BASE_URL;
        if (key.startsWith("tts_voice_")) return SpeechSettings.VOICE_GET_TEMPLATE;
        if (key.startsWith("tts_")) return SpeechSettings.GET_TEMPLATE;
        if (key.equals("streak_content_api")) return key;
        return null;
    }

    static String endpoint(Map<String, ?> values, String key) {
        Object value = values.get(key);
        if (value instanceof String) return redactEndpoint((String) value);
        if (SpeechSettings.GET_TEMPLATE.equals(key)) return redactText(SpeechSettings.DEFAULT_GET_TEMPLATE);
        if (SpeechSettings.VOICE_GET_TEMPLATE.equals(key)) return redactText(SpeechSettings.DEFAULT_VOICE_GET_TEMPLATE);
        if (SpeechSettings.MINIMAX_BASE_URL.equals(key)) return SpeechSettings.DEFAULT_MINIMAX_BASE_URL;
        if (BreezeSpeechSettings.BASE_URL.equals(key)) return BreezeSpeechSettings.DEFAULT_BASE_URL;
        if (FishSpeechSettings.BASE_URL.equals(key)) return FishSpeechSettings.DEFAULT_BASE_URL;
        if (ThirdPartyDownloadSettings.GET_TEMPLATE.equals(key)) return redactText(ThirdPartyDownloadSettings.DEFAULT_GET_TEMPLATE);
        return "";
    }

    static boolean sameEndpoint(Map<String, ?> before, Map<String, ?> incoming, String key) {
        String endpoint = endpointKey(key);
        if (endpoint == null) return true;
        String left = endpoint(before, endpoint), right = endpoint(incoming, endpoint);
        if (key.startsWith("third_party_") && !Objects.equals(before.getOrDefault(ThirdPartyDownloadSettings.APP_ID, null),
                incoming.getOrDefault(ThirdPartyDownloadSettings.APP_ID, null))) return false;
        return identifiable(right) && Objects.equals(left, right);
    }

    private static boolean identifiable(String endpoint) {
        int query = endpoint.indexOf('?');
        return !(query < 0 ? endpoint : endpoint.substring(0, query)).contains(OMITTED);
    }

    static String restore(String key, String imported, String previous, boolean same, int[] missing) {
        if (AiModelSettings.KEY.equals(key)) return restoreModels(imported, previous, missing);
        if (key.endsWith("headers")) {
            Object old = previous == null || previous.isEmpty() ? new JSONObject() : ConfigJson.parse(previous);
            Object incoming = imported.isEmpty() ? new JSONObject() : ConfigJson.parse(imported);
            return restoreJson(incoming, old, same, missing, true).toString();
        }
        return restoreText(imported, previous, same, missing);
    }

    private static String restoreModels(String imported, String previous, int[] missing) {
        if (imported.isEmpty()) return imported;
        try {
            JSONObject result = (JSONObject) ConfigJson.parse(imported);
            JSONObject old = previous == null || previous.isEmpty() ? new JSONObject() : (JSONObject) ConfigJson.parse(previous);
            JSONArray configs = result.getJSONArray("configs"), existing = old.optJSONArray("configs");
            for (int i = 0; i < configs.length(); i++) {
                JSONObject entry = configs.getJSONObject(i), matched = null;
                for (int j = 0; existing != null && j < existing.length(); j++) {
                    JSONObject candidate = existing.getJSONObject(j);
                    if (entry.getString("id").equals(candidate.optString("id"))
                            && identifiable(entry.optString("apiEndpoint")) && identifiable(entry.optString("modelsEndpoint"))
                            && redactText(entry.optString("apiEndpoint")).equals(redactText(candidate.optString("apiEndpoint")))
                            && redactText(entry.optString("modelsEndpoint")).equals(redactText(candidate.optString("modelsEndpoint")))) {
                        matched = candidate; break;
                    }
                }
                configs.put(i, restoreJson(entry, matched, matched != null, missing));
            }
            return result.toString();
        } catch (Exception failure) { throw ConfigJson.invalid(); }
    }

    private static Object restoreJson(Object imported, Object old, boolean same, int[] missing) {
        return restoreJson(imported, old, same, missing, false);
    }

    private static Object restoreJson(Object imported, Object old, boolean same, int[] missing, boolean privateFields) {
        try {
            if (OMITTED.equals(imported)) {
                if (same && old != null && !JSONObject.NULL.equals(old)) return old;
                missing[0]++; return "";
            }
            if (imported instanceof JSONObject) {
                JSONObject result = new JSONObject(), source = (JSONObject) imported;
                Iterator<String> keys = source.keys();
                while (keys.hasNext()) {
                    String key = keys.next(); Object item = source.get(key);
                    Object previous = old instanceof JSONObject ? ((JSONObject) old).opt(key) : null;
                    if (item instanceof String && ("customHeaders".equals(key) || "customParameters".equals(key))) {
                        Object previousJson = previous instanceof String && !((String) previous).isEmpty()
                                ? ConfigJson.parse((String) previous) : new JSONObject();
                        result.put(key, restoreJson(ConfigJson.parse(((String) item).isEmpty() ? "{}" : (String) item), previousJson, same, missing, true).toString());
                    } else result.put(key, restoreJson(item, previous, same, missing));
                }
                // Preserve omitted existing secret header/parameter fields only for the exact same service.
                if (same && old instanceof JSONObject) {
                    Iterator<String> previousKeys = ((JSONObject) old).keys();
                    while (previousKeys.hasNext()) {
                        String key = previousKeys.next();
                        if (!result.has(key) && (sensitive(key) || privateFields)) result.put(key, ((JSONObject) old).get(key));
                    }
                }
                return result;
            }
            if (imported instanceof JSONArray) {
                JSONArray result = new JSONArray(), source = (JSONArray) imported;
                for (int i = 0; i < source.length(); i++) result.put(restoreJson(source.get(i),
                        old instanceof JSONArray ? ((JSONArray) old).opt(i) : null, same, missing));
                return result;
            }
            return imported instanceof String ? restoreText((String) imported, old instanceof String ? (String) old : null, same, missing) : imported;
        } catch (Exception failure) { throw ConfigJson.invalid(); }
    }

    private static String restoreText(String imported, String previous, boolean same, int[] missing) {
        if (!imported.contains(OMITTED)) return imported;
        if (same && previous != null) {
            if (OMITTED.equals(imported)) return previous;
            // Identical public URL structure is required; no credential crosses an endpoint boundary.
            if (imported.equals(redactText(previous))) return previous;
        }
        missing[0] += count(imported);
        return imported.replace(OMITTED, "");
    }

    static int count(String value) {
        int count = 0, position = 0;
        while ((position = value.indexOf(OMITTED, position)) >= 0) { count++; position += OMITTED.length(); }
        return count;
    }
}
