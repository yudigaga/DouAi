package com.douai.module.feature;

import android.util.JsonReader;
import android.util.JsonToken;

import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Device-local display aliases. Provider names and synthesis selections remain unchanged. */
public final class VoiceNameTranslationCache {
    public static final String KEY = "voice_name_translation_cache_v1";
    private static final int MAX_ENTRIES = 2048;
    private static final int MAX_BYTES = 1024 * 1024;
    private static final int MAX_ALIAS_LENGTH = 96;
    private final Map<String, String> aliases;

    private VoiceNameTranslationCache(Map<String, String> aliases) {
        this.aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aliases));
    }

    public static String scope(SpeechSettings settings) {
        if (settings == null) return "";
        if (settings.fish) return hash(SpeechSettings.FISH, settings.fishSettings.baseUrl, settings.fishSettings.apiKey);
        if (settings.breeze) return hash(SpeechSettings.BREEZE, settings.breezeSettings.baseUrl, settings.breezeSettings.apiKey);
        return "";
    }

    public static VoiceNameTranslationCache read(SettingsStore store) {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            String encoded = store.getString(KEY, "");
            if (encoded == null || encoded.isEmpty()) return new VoiceNameTranslationCache(values);
            if (encoded.length() > MAX_BYTES || encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
                return new VoiceNameTranslationCache(Collections.emptyMap());
            }
            try (JsonReader reader = new JsonReader(new StringReader(encoded))) {
                reader.setLenient(false);
                boolean version = false, entries = false;
                reader.beginObject();
                while (reader.hasNext()) {
                    String field = reader.nextName();
                    if ("version".equals(field) && !version) {
                        if (reader.peek() != JsonToken.NUMBER || !"1".equals(reader.nextString())) throw invalid();
                        version = true;
                    } else if ("entries".equals(field) && !entries) {
                        reader.beginArray();
                        while (reader.hasNext()) {
                            if (values.size() >= MAX_ENTRIES) throw invalid();
                            reader.beginObject();
                            String key = null, alias = null;
                            while (reader.hasNext()) {
                                String itemField = reader.nextName();
                                if (reader.peek() != JsonToken.STRING) throw invalid();
                                if ("key".equals(itemField) && key == null) key = reader.nextString();
                                else if ("alias".equals(itemField) && alias == null) alias = reader.nextString();
                                else throw invalid();
                            }
                            reader.endObject();
                            if (!validFingerprint(key) || !validAlias(alias) || values.containsKey(key)) throw invalid();
                            values.put(key, alias.trim());
                        }
                        reader.endArray(); entries = true;
                    } else throw invalid();
                }
                reader.endObject();
                if (!version || !entries || reader.peek() != JsonToken.END_DOCUMENT) throw invalid();
            }
        } catch (Exception ignored) {
            values.clear();
        }
        return new VoiceNameTranslationCache(values);
    }

    public String displayName(String scope, VoiceCatalog.Entry entry) {
        String alias = alias(scope, entry);
        return alias.isEmpty() ? entry.name : alias + "（" + entry.name + "）";
    }

    public String alias(String scope, VoiceCatalog.Entry entry) {
        if (!validFingerprint(scope) || entry == null) return "";
        String value = aliases.get(fingerprint(scope, entry));
        return value == null ? "" : value;
    }

    public boolean matches(String scope, VoiceCatalog.Entry entry, String query) {
        if (entry == null || query != null && query.length() > 256) return false;
        String needle = normalized(query == null ? "" : query).trim();
        if (needle.length() > 256) return false;
        if (needle.isEmpty()) return true;
        String haystack = normalized(entry.id + " " + entry.name + " " + alias(scope, entry)
                + " " + String.join(" ", entry.labels));
        int start = 0;
        for (int index = 0; index <= needle.length(); index++) {
            if (index == needle.length() || Character.isWhitespace(needle.charAt(index)) || Character.isSpaceChar(needle.charAt(index))) {
                if (start < index && !haystack.contains(needle.substring(start, index))) return false;
                start = index + 1;
            }
        }
        return true;
    }

    public List<VoiceCatalog.Entry> pending(String scope, List<VoiceCatalog.Entry> entries, int max) {
        if (!validFingerprint(scope) || entries == null || max <= 0) return Collections.emptyList();
        List<VoiceCatalog.Entry> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        int limit = Math.min(max, MAX_ENTRIES);
        for (VoiceCatalog.Entry entry : entries) {
            if (entry == null || !ids.add(entry.id) || !needsTranslation(entry) || !alias(scope, entry).isEmpty()) continue;
            result.add(entry);
            if (result.size() >= limit) break;
        }
        return Collections.unmodifiableList(result);
    }

    public static boolean needsTranslation(VoiceCatalog.Entry entry) {
        if (entry == null || entry.name.equals(entry.id)) return false;
        for (int index = 0; index < entry.name.length();) {
            int point = entry.name.codePointAt(index);
            if (Character.isLetter(point) && Character.UnicodeScript.of(point) != Character.UnicodeScript.HAN) return true;
            index += Character.charCount(point);
        }
        return false;
    }

    public static boolean validAlias(String value) {
        if (value == null || value.isEmpty() || value.length() > MAX_ALIAS_LENGTH) return false;
        boolean han = false;
        for (int index = 0; index < value.length();) {
            int point = value.codePointAt(index), type = Character.getType(point);
            if (type == Character.CONTROL || type == Character.FORMAT || type == Character.SURROGATE
                    || type == Character.LINE_SEPARATOR || type == Character.PARAGRAPH_SEPARATOR) return false;
            han |= Character.UnicodeScript.of(point) == Character.UnicodeScript.HAN;
            index += Character.charCount(point);
        }
        return han;
    }

    /** Re-read under one process-wide lock so independent translation batches retain each other. */
    public static synchronized int merge(SettingsStore store, String scope, List<VoiceCatalog.Entry> originals,
                                         Map<String, String> translationsById) {
        if (!validFingerprint(scope) || originals == null || translationsById == null || translationsById.isEmpty()) return 0;
        LinkedHashMap<String, String> values = new LinkedHashMap<>(read(store).aliases);
        Set<String> changed = new LinkedHashSet<>(), ids = new LinkedHashSet<>();
        for (VoiceCatalog.Entry entry : originals) {
            if (entry == null || !ids.add(entry.id) || !needsTranslation(entry)) continue;
            Object candidate = translationsById.get(entry.id);
            if (!(candidate instanceof String) || !validAlias((String) candidate)) continue;
            String alias = ((String) candidate).trim();
            if (alias.equals(entry.name)) continue;
            String key = fingerprint(scope, entry);
            if (alias.equals(values.get(key))) continue;
            values.remove(key); values.put(key, alias); changed.add(key);
            while (values.size() > MAX_ENTRIES) removeOldest(values);
        }
        if (changed.isEmpty()) return 0;
        try {
            String encoded = encode(values);
            while (encoded.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES && !values.isEmpty()) {
                removeOldest(values); encoded = encode(values);
            }
            changed.retainAll(values.keySet());
            if (changed.isEmpty()) return 0;
            store.putString(KEY, encoded);
            return changed.size();
        } catch (Exception ignored) {
            return 0;
        }
    }

    private static String encode(Map<String, String> values) throws Exception {
        JSONArray entries = new JSONArray();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            entries.put(new JSONObject().put("key", entry.getKey()).put("alias", entry.getValue()));
        }
        return new JSONObject().put("version", 1).put("entries", entries).toString();
    }

    private static void removeOldest(LinkedHashMap<String, String> values) {
        Iterator<String> keys = values.keySet().iterator();
        if (keys.hasNext()) { keys.next(); keys.remove(); }
    }

    private static String fingerprint(String scope, VoiceCatalog.Entry entry) {
        return hash(scope, entry.id, entry.name);
    }

    private static String normalized(String value) {
        return Normalizer.normalize(value, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
    }

    private static boolean validFingerprint(String value) {
        if (value == null || value.length() != 64) return false;
        for (int index = 0; index < value.length(); index++) {
            char point = value.charAt(index);
            if (!(point >= '0' && point <= '9' || point >= 'a' && point <= 'f')) return false;
        }
        return true;
    }

    private static String hash(String... fields) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String field : fields) {
                byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
                digest.update((byte) (bytes.length >>> 24)); digest.update((byte) (bytes.length >>> 16));
                digest.update((byte) (bytes.length >>> 8)); digest.update((byte) bytes.length); digest.update(bytes);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value & 255) >>> 4, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException("Invalid local alias cache"); }
}
