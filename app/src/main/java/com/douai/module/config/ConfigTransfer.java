package com.douai.module.config;

import com.douai.module.BuildConfig;
import com.douai.module.diagnostics.DouAiLog;
import org.json.JSONArray;
import org.json.JSONObject;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import com.douai.module.config.BackupValidationException.Reason;
import com.douai.module.config.BackupValidationException.Section;

/** Versioned configuration transfer. Inspect is pure; only an accepted preview can be applied. */
public final class ConfigTransfer {
    public static final int MAX_BYTES = 2 * 1024 * 1024;
    public static final int FORMAT_VERSION = 1;
    private static final String APPLICATION_ID = "io.github.yudigaga.douai";
    public enum Mode { MERGE, REPLACE }
    private ConfigTransfer() { }

    public static final class Preview {
        public final int formatVersion = FORMAT_VERSION;
        public final int entryCount, changedCount, removedCount, redactedCount, missingSecretsCount;
        public final String moduleVersion;
        public final boolean includesSecrets;
        public final Mode mode;
        public final List<String> changedKeys;
        private final Map<String, ?> expected, replacement;
        private Preview(Map<String, ?> expected, Map<String, ?> replacement, int entries, int redacted,
                        int missing, String version, boolean secrets, Mode mode) {
            this.expected = copy(expected); this.replacement = copy(replacement);
            entryCount = entries; redactedCount = redacted; missingSecretsCount = missing;
            moduleVersion = version; includesSecrets = secrets; this.mode = mode;
            List<String> changes = new ArrayList<>(); int removed = 0;
            for (String key : ConfigKeys.TYPES.keySet()) if (!Objects.equals(expected.get(key), replacement.get(key))) {
                changes.add(key); if (expected.containsKey(key) && !replacement.containsKey(key)) removed++;
            }
            Collections.sort(changes); changedKeys = Collections.unmodifiableList(changes);
            changedCount = changes.size(); removedCount = removed;
        }
    }

    public static String export(SettingsStore store, boolean includeSecrets) {
        DouAiLog.Operation operation = DouAiLog.begin("config_export", "serialize_backup");
        try {
            String result = exportChecked(store, includeSecrets);
            operation.finish(DouAiLog.Outcome.SUCCESS, "validated_backup_serialized",
                    "bytes=" + result.getBytes(StandardCharsets.UTF_8).length + "; includes_secrets=" + includeSecrets
                            + "; external_file_written=false");
            return result;
        } catch (RuntimeException failure) {
            operation.error("serialize", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "validation_or_serialization_failed", "external_file_written=false");
            throw failure;
        }
    }

    private static String exportChecked(SettingsStore store, boolean includeSecrets) {
        try {
            Map<String, ?> source = store.snapshot(); JSONObject values = new JSONObject();
            new MiniMaxSpeechOptions(new LocalOptions(source)).validate();
            new BreezeSpeechSettings(new LocalOptions(source)).validateOptions();
            new FishSpeechSettings(new LocalOptions(source)).validateOptions();
            for (Map.Entry<String, ?> entry : new TreeMap<>(source).entrySet()) {
                String key = entry.getKey(); if (!ConfigKeys.TYPES.containsKey(key)) continue;
                Object value = entry.getValue(); ConfigKeys.validate(key, value);
                validateStructured(key, value);
                if (!includeSecrets && value instanceof String) value = ConfigSecrets.redact(key, (String) value);
                values.put(key, new JSONObject().put("type", ConfigKeys.TYPES.get(key)).put("value", value));
            }
            String encoded = new JSONObject().put("formatVersion", FORMAT_VERSION)
                    .put("module", new JSONObject().put("applicationId", APPLICATION_ID)
                            .put("versionName", BuildConfig.VERSION_NAME).put("versionCode", BuildConfig.VERSION_CODE))
                    .put("includesSecrets", includeSecrets).put("values", values).toString(2);
            if (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw new BackupLimitException(BackupLimitException.Section.CONFIG);
            return encoded;
        } catch (IllegalArgumentException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalArgumentException("配置无法导出，请检查设置内容"); }
    }

    public static Preview inspect(SettingsStore store, String encoded) { return inspect(store, encoded, Mode.MERGE); }

    public static Preview inspect(SettingsStore store, String encoded, Mode mode) {
        DouAiLog.Operation operation = DouAiLog.begin("config_import_preview", "inspect_backup");
        try {
            Preview preview = inspectChecked(store, encoded, mode);
            operation.finish(DouAiLog.Outcome.SUCCESS, "preview_validated", "entries=" + preview.entryCount
                    + "; changed=" + preview.changedCount + "; removed=" + preview.removedCount
                    + "; missing_secrets=" + preview.missingSecretsCount + "; mode=" + mode + "; applied=false");
            return preview;
        } catch (RuntimeException | LinkageError failure) {
            operation.error("validation", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "backup_validation_failed", "applied=false");
            throw failure;
        }
    }

    private static Preview inspectChecked(SettingsStore store, String encoded, Mode mode) {
        if (encoded == null) throw new BackupValidationException(Section.CONFIG, Reason.INVALID_JSON, null);
        if (encoded.length() > MAX_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES)
            throw new BackupLimitException(BackupLimitException.Section.CONFIG);
        if (mode == null) throw new BackupValidationException(Section.CONFIG, Reason.MERGED_SETTINGS, null);
        Reason reason = Reason.INVALID_JSON;
        String field = null;
        try {
            Object parsed = ConfigJson.parse(encoded);
            reason = Reason.HEADER_SHAPE;
            if (!(parsed instanceof JSONObject)) throw ConfigJson.invalid();
            JSONObject root = (JSONObject) parsed;
            exactKeys(root, "formatVersion", "module", "includesSecrets", "values");
            reason = Reason.FORMAT_VERSION; field = "formatVersion";
            if (!Long.valueOf(FORMAT_VERSION).equals(root.get("formatVersion"))) throw ConfigJson.invalid();
            reason = Reason.MODULE; field = "module";
            JSONObject metadata = root.getJSONObject("module");
            exactKeys(metadata, "applicationId", "versionName", "versionCode");
            field = "applicationId";
            if (!APPLICATION_ID.equals(metadata.get("applicationId"))) throw ConfigJson.invalid();
            field = "versionName";
            Object version = metadata.get("versionName"), code = metadata.get("versionCode");
            if (!(version instanceof String) || !((String) version).matches("[0-9]+\\.[0-9]+\\.[0-9]+")) throw ConfigJson.invalid();
            field = "versionCode";
            if (!(code instanceof Long) || (Long) code <= 0 || (Long) code > Integer.MAX_VALUE) throw ConfigJson.invalid();
            reason = Reason.TYPE_MISMATCH; field = "includesSecrets";
            if (!(root.get("includesSecrets") instanceof Boolean)) throw ConfigJson.invalid();
            boolean secrets = root.getBoolean("includesSecrets");
            field = "values";
            JSONObject values = root.getJSONObject("values");
            if (values.length() > ConfigKeys.TYPES.size()) throw ConfigJson.invalid();
            Map<String, Object> incoming = new LinkedHashMap<>(); int redacted = 0;
            Iterator<String> keys = values.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                reason = Reason.UNKNOWN_KEY; field = null;
                String type = ConfigKeys.TYPES.get(key);
                if (type == null) throw ConfigJson.invalid();
                reason = Reason.TYPE_MISMATCH; field = key;
                JSONObject typed = values.getJSONObject(key);
                exactKeys(typed, "type", "value");
                if (!type.equals(typed.get("type"))) throw ConfigJson.invalid();
                Object value = typed.get("value");
                if ("int".equals(type)) {
                    if (!(value instanceof Long) || (Long) value < Integer.MIN_VALUE || (Long) value > Integer.MAX_VALUE) throw ConfigJson.invalid();
                    value = ((Long) value).intValue();
                }
                ConfigKeys.validate(key, value); validateStructured(key, value);
                if (value instanceof String) {
                    reason = Reason.SECRET_POLICY;
                    String text = (String) value; redacted += ConfigSecrets.count(text);
                    if (!secrets && !equivalentText(key, text, ConfigSecrets.redact(key, text))) {
                        throw ConfigJson.invalid();
                    }
                }
                incoming.put(key, value);
            }
            reason = Reason.SECRET_POLICY; field = null;
            if (secrets && redacted > 0) throw ConfigJson.invalid();
            reason = Reason.CURRENT_STORE;
            Map<String, ?> before = store.snapshot(); Map<String, Object> result = new LinkedHashMap<>(before);
            reason = Reason.MERGED_SETTINGS;
            if (mode == Mode.REPLACE) for (String key : ConfigKeys.TYPES.keySet()) result.remove(key);
            result.putAll(incoming);
            int[] missing = {0};
            if (!secrets && mode == Mode.REPLACE) {
                for (Map.Entry<String, ?> entry : before.entrySet()) {
                    String key = entry.getKey(); Object value = entry.getValue();
                    field = ConfigKeys.TYPES.containsKey(key) ? key : null;
                    if (ConfigKeys.TYPES.containsKey(key) && !result.containsKey(key) && value instanceof String
                            && !AiModelSettings.KEY.equals(key) && ConfigSecrets.sameEndpoint(before, result, key)
                            && !equivalentText(key, (String) value, ConfigSecrets.redact(key, (String) value))) result.put(key, value);
                }
            }
            // New endpoints must not inherit credentials that were absent from the incoming file.
            for (String key : ConfigKeys.TYPES.keySet()) {
                field = key;
                Object current = result.get(key);
                if (!(current instanceof String)) continue;
                boolean same = ConfigSecrets.sameEndpoint(before, result, key);
                boolean inheritedAcrossServices = !same && !incoming.containsKey(key);
                if (inheritedAcrossServices) current = ConfigSecrets.redact(key, (String) current);
                String previous = before.get(key) instanceof String ? (String) before.get(key) : null;
                String restored = secrets && !inheritedAcrossServices ? (String) current
                        : ConfigSecrets.restore(key, (String) current, previous, same, missing);
                result.put(key, restored);
            }
            for (Map.Entry<String, Object> entry : result.entrySet()) if (ConfigKeys.TYPES.containsKey(entry.getKey())) {
                field = entry.getKey();
                ConfigKeys.validate(entry.getKey(), entry.getValue()); validateStructured(entry.getKey(), entry.getValue());
            }
            reason = Reason.MINIMAX_OPTIONS; field = null;
            new MiniMaxSpeechOptions(new LocalOptions(result)).validate();
            reason = Reason.BREEZE_OPTIONS;
            new BreezeSpeechSettings(new LocalOptions(result)).validateOptions();
            reason = Reason.FISH_OPTIONS;
            new FishSpeechSettings(new LocalOptions(result)).validateOptions();
            reason = Reason.MERGED_SETTINGS;
            return new Preview(before, result, incoming.size(), redacted, missing[0], (String) version, secrets, mode);
        } catch (Exception | LinkageError failure) {
            throw BackupValidationException.wrap(Section.CONFIG, reason, field, failure);
        }
    }

    public static void apply(SettingsStore store, Preview preview) {
        DouAiLog.Operation operation = DouAiLog.begin("config_import", "apply_preview");
        try {
            if (preview == null) throw new IllegalArgumentException("请先预览配置文件");
            operation.stage("compare_and_replace", "changed=" + preview.changedCount + "; mode=" + preview.mode);
            operation.wrap(() -> store.replaceSnapshot(preview.expected, preview.replacement)).run();
            boolean verified = false;
            try { verified = store.snapshot().equals(preview.replacement); }
            catch (RuntimeException unavailable) { operation.error("verification_unavailable", unavailable); }
            operation.finish(verified ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED,
                    verified ? "store_snapshot_matches" : "store_snapshot_mismatch", "read_back_verified=" + verified
                            + "; host_config_propagation_confirmed=false");
        } catch (RuntimeException failure) {
            operation.error("compare_and_replace", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "store_replace_failed", "host_config_propagation_confirmed=false");
            throw failure;
        }
    }

    /** Compensation after another selected backup component fails; never overwrites newer settings. */
    static void rollback(SettingsStore store, Preview preview) {
        store.replaceSnapshot(preview.replacement, preview.expected);
    }

    static void requireCurrent(SettingsStore store, Preview preview) {
        if (!preview.expected.equals(store.snapshot())) throw new IllegalStateException("设置已发生变化，请重新预览备份");
    }

    private static Map<String, ?> copy(Map<String, ?> input) {
        Map<String, Object> output = new LinkedHashMap<>();
        input.forEach((key, value) -> output.put(key, value instanceof java.util.Set<?>
                ? Collections.unmodifiableSet(new java.util.HashSet<>((java.util.Set<?>) value)) : value));
        return Collections.unmodifiableMap(output);
    }

    private static boolean equivalentText(String key, String left, String right) {
        if (left.equals(right)) return true;
        if (AiModelSettings.KEY.equals(key) || key.endsWith("headers")) {
            return ConfigJson.parse(left).toString().equals(ConfigJson.parse(right).toString());
        }
        return left.equals(right);
    }

    private static void exactKeys(JSONObject value, String... expected) {
        if (value.length() != expected.length) throw ConfigJson.invalid();
        for (String key : expected) if (!value.has(key)) throw ConfigJson.invalid();
    }

    private static void validateStructured(String key, Object value) {
        Reason reason = key.endsWith("headers") ? Reason.STRUCTURED_HEADERS
                : AiModelSettings.KEY.equals(key) ? Reason.STRUCTURED_MODELS
                : CustomViewLocator.SETTINGS_KEY.equals(key) ? Reason.STRUCTURED_COMPONENTS
                : key.startsWith("tts_minimax_") ? Reason.MINIMAX_OPTIONS
                : key.startsWith("tts_breeze_") ? Reason.BREEZE_OPTIONS
                : key.startsWith("tts_fish_") ? Reason.FISH_OPTIONS : Reason.STRUCTURED_FIELDS;
        try {
            if (SpeechSettings.PROVIDER.equals(key) && value instanceof String) SpeechSettings.validateProvider((String) value);
            if (key.startsWith("tts_minimax_") && value instanceof String) {
                // Empty single-voice IDs are legal only when the merged settings contain a valid mix.
                if (SpeechSettings.MINIMAX_VOICE_ID.equals(key)) SpeechSettings.validateMiniMaxVoiceId((String) value, false);
                else SpeechSettings.validateMiniMaxValue(key, (String) value);
            }
            if (key.startsWith("tts_breeze_") && value instanceof String) BreezeSpeechSettings.validateValue(key, (String) value);
            if (key.startsWith("tts_fish_") && value instanceof String) FishSpeechSettings.validateValue(key, (String) value);
            if (!(value instanceof String) || ((String) value).isEmpty()) return;
            String text = (String) value;
            if (AiModelSettings.KEY.equals(key)) {
                JSONObject root = (JSONObject) ConfigJson.parse(text);
                exactKeys(root, "version", "activeId", "configs");
                if (!Long.valueOf(2).equals(root.get("version")) || !(root.get("activeId") instanceof String)) throw ConfigJson.invalid();
                JSONArray configs = root.getJSONArray("configs");
                if (configs.length() > 50) throw ConfigJson.invalid();
                for (int i = 0; i < configs.length(); i++) {
                    JSONObject config = configs.getJSONObject(i);
                    JSONObject shape = AiModelConfig.fromJson(config).toJson();
                    Iterator<String> fields = config.keys();
                    while (fields.hasNext()) {
                        String field = fields.next(); Object candidate = config.get(field), expected = shape.opt(field);
                        if (expected == null || expected instanceof String && !(candidate instanceof String)
                                || expected instanceof Boolean && !(candidate instanceof Boolean)
                                || expected instanceof Number && !(candidate instanceof Number)) throw ConfigJson.invalid();
                        if ((field.equals("contextRounds") || field.equals("timeoutSeconds") || field.equals("maxTokens"))
                                && (!(candidate instanceof Long) || (Long) candidate < Integer.MIN_VALUE
                                || (Long) candidate > Integer.MAX_VALUE)) throw ConfigJson.invalid();
                    }
                    for (String structured : new String[]{"customHeaders", "customParameters"}) {
                        String nested = config.optString(structured, "{}");
                        if (!(ConfigJson.parse(nested.isEmpty() ? "{}" : nested) instanceof JSONObject)) throw ConfigJson.invalid();
                    }
                    AiModelConfig.fromJson(config).validate(false);
                }
                AiModelSettings.parse(text);
            } else if (CustomViewLocator.SETTINGS_KEY.equals(key)) {
                JSONObject root = (JSONObject) ConfigJson.parse(text);
                if (!Long.valueOf(2).equals(root.get("version")) || root.getJSONArray("items").length() != CustomViewLocator.parse(text).size()) throw ConfigJson.invalid();
            } else if (key.endsWith("headers")) {
                Object parsed = ConfigJson.parse(text);
                if (!(parsed instanceof JSONObject)) throw ConfigJson.invalid();
                JSONObject headers = (JSONObject) parsed; Iterator<String> names = headers.keys();
                while (names.hasNext()) if (!(headers.get(names.next()) instanceof String)) throw ConfigJson.invalid();
            } else if (ThirdPartyDownloadSettings.DOWNLOAD_FIELDS.equals(key) || ThirdPartyDownloadSettings.NAMING_FIELDS.equals(key)) {
                Object parsed = ConfigJson.parse(text);
                if (!(parsed instanceof JSONArray)) throw ConfigJson.invalid();
                JSONArray fields = (JSONArray) parsed;
                for (int i = 0; i < fields.length(); i++) if (!(fields.get(i) instanceof String)) throw ConfigJson.invalid();
            }
        } catch (Exception | LinkageError failure) {
            throw BackupValidationException.wrap(Section.CONFIG, reason, key, failure);
        }
    }
}
