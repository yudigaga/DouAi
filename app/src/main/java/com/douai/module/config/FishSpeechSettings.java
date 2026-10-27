package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Fish Audio engine and reference voice settings are independent of other speech providers. */
public final class FishSpeechSettings {
    public static final String API_KEY = "tts_fish_api_key";
    public static final String BASE_URL = "tts_fish_base_url";
    public static final String ENGINE = "tts_fish_engine";
    public static final String MODEL = ENGINE;
    public static final String REFERENCE_ID = "tts_fish_reference_id";
    public static final String VOICE_ID = REFERENCE_ID;
    public static final String REFERENCE_NAME = "tts_fish_reference_name";
    public static final String VOICE_NAME = REFERENCE_NAME;
    public static final String VOICE_MODE = "tts_fish_voice_mode";
    public static final String REFERENCE_IDS = "tts_fish_reference_ids";
    /** Local imported material only; deliberately excluded from portable configuration. */
    public static final String REFERENCE_SAMPLES = "tts_fish_reference_samples";
    public static final String SPEED = "tts_fish_speed";
    public static final String VOLUME_DB = "tts_fish_volume_db";
    public static final String VOLUME = VOLUME_DB;
    public static final String NORMALIZE_LOUDNESS = "tts_fish_normalize_loudness";
    public static final String FORMAT = "tts_fish_format";
    public static final String SAMPLE_RATE = "tts_fish_sample_rate";
    public static final String MP3_BITRATE = "tts_fish_mp3_bitrate";
    public static final String OPUS_BITRATE = "tts_fish_opus_bitrate";
    public static final String TEMPERATURE = "tts_fish_temperature";
    public static final String TOP_P = "tts_fish_top_p";
    public static final String NORMALIZE = "tts_fish_normalize";
    public static final String LANGUAGE = "tts_fish_language";
    public static final String LATENCY = "tts_fish_latency";
    public static final String CHUNK_LENGTH = "tts_fish_chunk_length";
    public static final String MIN_CHUNK_LENGTH = "tts_fish_min_chunk_length";
    public static final String MAX_NEW_TOKENS = "tts_fish_max_new_tokens";
    public static final String REPETITION_PENALTY = "tts_fish_repetition_penalty";
    public static final String CONDITION_ON_PREVIOUS_CHUNKS = "tts_fish_condition_on_previous_chunks";
    public static final String EARLY_STOP_THRESHOLD = "tts_fish_early_stop_threshold";
    public static final String QUALITY_GUARD = "tts_fish_quality_guard";
    public static final String PRONUNCIATION_DICTIONARY = "tts_fish_pronunciation_dictionary";
    public static final String VOICE_SCOPE = "tts_fish_voice_scope";
    public static final String VOICE_SEARCH = "tts_fish_voice_search";
    public static final String VOICE_LANGUAGE = "tts_fish_voice_language";
    public static final String VOICE_TAGS = "tts_fish_voice_tags";
    public static final String VOICE_TITLE_LANGUAGE = "tts_fish_voice_title_language";
    public static final String VOICE_AUTHOR_ID = "tts_fish_voice_author_id";
    public static final String VOICE_LICENSED = "tts_fish_voice_licensed";
    public static final String VOICE_SORT = "tts_fish_voice_sort";
    public static final String VOICE_SAMPLE = "tts_fish_voice_sample";
    public static final String MODEL_SAMPLE = "tts_fish_model_sample";
    public static final String MODEL_SOURCE = "tts_fish_model_source";
    public static final String DEFAULT_BASE_URL = "https://api.fish.audio";
    public static final String DEFAULT_ENGINE = "s2.1-pro";
    public static final String DEFAULT_MODEL = DEFAULT_ENGINE;
    public static final String[] KEYS = {API_KEY, BASE_URL, ENGINE, REFERENCE_ID, REFERENCE_NAME, VOICE_MODE,
            REFERENCE_IDS, REFERENCE_SAMPLES, SPEED,
            VOLUME_DB, NORMALIZE_LOUDNESS, FORMAT, SAMPLE_RATE, MP3_BITRATE, OPUS_BITRATE,
            TEMPERATURE, TOP_P, NORMALIZE, LANGUAGE, LATENCY, CHUNK_LENGTH, MIN_CHUNK_LENGTH,
            MAX_NEW_TOKENS, REPETITION_PENALTY, CONDITION_ON_PREVIOUS_CHUNKS, EARLY_STOP_THRESHOLD,
            QUALITY_GUARD, PRONUNCIATION_DICTIONARY, VOICE_SCOPE, VOICE_SEARCH, VOICE_LANGUAGE, VOICE_TAGS,
            VOICE_TITLE_LANGUAGE, VOICE_AUTHOR_ID, VOICE_LICENSED, VOICE_SORT};
    public static final String[] ENGINES = {"s2.1-pro", "s2.1-pro-free", "s2-pro", "s1", "drama-3"};
    public static final String[] MODELS = ENGINES;
    public static final String[] FORMATS = {"mp3", "wav", "opus"};
    public static final String[] SAMPLE_RATES = {"8000", "16000", "24000", "32000", "44100", "48000"};
    public static final String[] MP3_SAMPLE_RATES = {"32000", "44100"};
    public static final String[] WAV_SAMPLE_RATES = {"8000", "16000", "24000", "32000", "44100"};
    public static final String[] OPUS_SAMPLE_RATES = {"48000"};
    public static final String[] MP3_BITRATES = {"64", "128", "192"};
    public static final String[] OPUS_BITRATES = {"-1000", "24000", "32000", "48000", "64000"};
    public static final String[] LATENCIES = {"normal", "balanced", "low"};
    public static final String[] VOICE_SCOPES = {"self", "public"};
    public static final String[] VOICE_SORTS = {"score", "task_count", "created_at"};
    public static final String[] VOICE_LICENSED_VALUES = {"", "true", "false"};
    public static final String[] VOICE_MODES = {"single", "multi", "references"};
    /** Module editor budgets, not limits advertised by the remote API. */
    public static final int MAX_REFERENCE_GROUPS = 8;
    public static final int MAX_SAMPLES_PER_GROUP = 4;

    private final LocalOptions options;
    public final String apiKey, baseUrl, engine, referenceId, referenceName, format, language, latency;
    public final String pronunciationDictionary, voiceScope, voiceSearch, voiceLanguage;
    public final String voiceAuthorId, voiceLicensed, voiceSort;
    public final List<String> voiceLanguages, voiceTitleLanguages;
    public final List<String> voiceTags;
    public final String voiceMode, referenceSamples;
    public final List<String> referenceIds;
    public final double speed, volumeDb, temperature, topP, repetitionPenalty, earlyStopThreshold;
    public final boolean normalizeLoudness, normalize, conditionOnPreviousChunks, qualityGuard;
    public final int sampleRate, mp3Bitrate, opusBitrate, chunkLength, minChunkLength, maxNewTokens;

    public FishSpeechSettings(LocalOptions options) {
        this.options = options;
        apiKey = value(API_KEY);
        baseUrl = value(BASE_URL);
        engine = stripEnginePrefix(value(ENGINE));
        referenceId = value(REFERENCE_ID);
        referenceName = value(REFERENCE_NAME);
        voiceMode = value(VOICE_MODE);
        referenceSamples = value(REFERENCE_SAMPLES);
        List<String> savedIds;
        try { savedIds = parseReferenceIds(value(REFERENCE_IDS)); }
        catch (IllegalArgumentException invalid) { savedIds = Collections.emptyList(); }
        referenceIds = savedIds;
        format = value(FORMAT).toLowerCase(Locale.ROOT);
        language = value(LANGUAGE).replace('_', '-');
        latency = value(LATENCY);
        pronunciationDictionary = value(PRONUNCIATION_DICTIONARY);
        voiceScope = value(VOICE_SCOPE);
        voiceSearch = value(VOICE_SEARCH);
        voiceLanguages = savedVoiceLanguages(value(VOICE_LANGUAGE));
        voiceLanguage = voiceLanguages.isEmpty() ? "" : voiceLanguages.get(0);
        voiceTitleLanguages = savedVoiceLanguages(value(VOICE_TITLE_LANGUAGE));
        voiceAuthorId = value(VOICE_AUTHOR_ID);
        voiceLicensed = value(VOICE_LICENSED);
        voiceSort = value(VOICE_SORT);
        List<String> savedTags;
        try { savedTags = parseVoiceTags(value(VOICE_TAGS)); }
        catch (IllegalArgumentException invalid) { savedTags = Collections.emptyList(); }
        voiceTags = savedTags;
        speed = decimal(SPEED, 1);
        volumeDb = decimal(VOLUME_DB, 0);
        temperature = decimal(TEMPERATURE, 0.7);
        topP = decimal(TOP_P, 0.7);
        repetitionPenalty = decimal(REPETITION_PENALTY, 1.2);
        earlyStopThreshold = decimal(EARLY_STOP_THRESHOLD, 1);
        normalizeLoudness = Boolean.parseBoolean(value(NORMALIZE_LOUDNESS));
        normalize = Boolean.parseBoolean(value(NORMALIZE));
        conditionOnPreviousChunks = Boolean.parseBoolean(value(CONDITION_ON_PREVIOUS_CHUNKS));
        qualityGuard = Boolean.parseBoolean(value(QUALITY_GUARD));
        sampleRate = integerValue(SAMPLE_RATE, 0);
        mp3Bitrate = integerValue(MP3_BITRATE, 128);
        opusBitrate = integerValue(OPUS_BITRATE, -1000);
        chunkLength = integerValue(CHUNK_LENGTH, 300);
        minChunkLength = integerValue(MIN_CHUNK_LENGTH, 50);
        maxNewTokens = integerValue(MAX_NEW_TOKENS, 1024);
    }

    private String value(String key) { return options.text(key, defaultValue(key)).trim(); }

    private int integerValue(String key, int fallback) {
        try { return Integer.parseInt(value(key)); }
        catch (NumberFormatException invalid) { return fallback; }
    }

    private double decimal(String key, double fallback) {
        try {
            double parsed = Double.parseDouble(value(key));
            return Double.isFinite(parsed) ? parsed : fallback;
        } catch (NumberFormatException invalid) { return fallback; }
    }

    public static String defaultValue(String key) {
        if (BASE_URL.equals(key)) return DEFAULT_BASE_URL;
        if (ENGINE.equals(key)) return DEFAULT_ENGINE;
        if (SPEED.equals(key) || EARLY_STOP_THRESHOLD.equals(key)) return "1";
        if (VOLUME_DB.equals(key)) return "0";
        if (NORMALIZE_LOUDNESS.equals(key) || NORMALIZE.equals(key) || CONDITION_ON_PREVIOUS_CHUNKS.equals(key)) return "true";
        if (FORMAT.equals(key)) return "mp3";
        if (MP3_BITRATE.equals(key)) return "128";
        if (OPUS_BITRATE.equals(key)) return "-1000";
        if (TEMPERATURE.equals(key) || TOP_P.equals(key)) return "0.7";
        if (LATENCY.equals(key)) return "normal";
        if (CHUNK_LENGTH.equals(key)) return "300";
        if (MIN_CHUNK_LENGTH.equals(key)) return "50";
        if (MAX_NEW_TOKENS.equals(key)) return "1024";
        if (REPETITION_PENALTY.equals(key)) return "1.2";
        if (QUALITY_GUARD.equals(key)) return "false";
        if (VOICE_SCOPE.equals(key)) return "public";
        if (VOICE_LANGUAGE.equals(key)) return "zh";
        if (VOICE_TITLE_LANGUAGE.equals(key)) return "[]";
        if (VOICE_SORT.equals(key)) return "score";
        if (VOICE_TAGS.equals(key)) return "[]";
        if (VOICE_MODE.equals(key)) return "single";
        if (REFERENCE_IDS.equals(key) || REFERENCE_SAMPLES.equals(key)) return "[]";
        return "";
    }

    public static boolean isKey(String key) { return Arrays.asList(KEYS).contains(key); }

    public static String candidateIssue(Map<String, ?> current, String key, String candidate) {
        try {
            if (!isKey(key)) throw new IllegalArgumentException("Fish Audio 设置字段无效");
            Map<String, Object> values = new LinkedHashMap<>(current);
            values.put(key, validateValue(key, candidate));
            new FishSpeechSettings(new LocalOptions(values)).validateOptions();
            return "";
        } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }

    public void validateOptions() {
        for (String key : KEYS) validateValue(key, options.text(key, defaultValue(key)));
        if (sampleRate != 0 && !Arrays.asList(sampleRates(format)).contains(Integer.toString(sampleRate))) {
            throw new IllegalArgumentException("Fish Audio 当前格式不支持所选采样率，请选择接口默认或兼容采样率");
        }
        if ("s1".equals(engine) && "multi".equals(voiceMode)) {
            throw new IllegalArgumentException("Fish Audio S1 不支持多说话人，请切换 S2 系列或单音色模式");
        }
        if ("s1".equals(engine) && "references".equals(voiceMode) && referenceGroups().size() > 1) {
            throw new IllegalArgumentException("Fish Audio S1 的参考音频只能使用一个说话人分组");
        }
    }

    public void validateForSynthesis() {
        validateOptions();
        if (apiKey.isEmpty()) throw new IllegalArgumentException("请设置 Fish Audio API Key");
        if ("multi".equals(voiceMode) && referenceIds.isEmpty()) {
            throw new IllegalArgumentException("请至少填写一个 Fish Audio 说话人音色 ID");
        }
        if ("references".equals(voiceMode)) {
            List<List<ReferenceSample>> groups = referenceGroups();
            if (groups.isEmpty()) throw new IllegalArgumentException("请导入 Fish Audio 参考音频并填写对应原文");
            for (List<ReferenceSample> group : groups) if (group.isEmpty()) {
                throw new IllegalArgumentException("Fish Audio 每个说话人分组都需要参考音频");
            }
        }
    }

    public boolean hasCatalogFilter() {
        return "self".equals(voiceScope) || !voiceLanguages.isEmpty() || !voiceTitleLanguages.isEmpty()
                || !voiceSearch.isEmpty() || !voiceTags.isEmpty() || !voiceAuthorId.isEmpty() || "true".equals(voiceLicensed);
    }

    public void validateCatalog() {
        for (String key : new String[]{BASE_URL, API_KEY, VOICE_SCOPE, VOICE_SEARCH, VOICE_LANGUAGE, VOICE_TAGS,
                VOICE_TITLE_LANGUAGE, VOICE_AUTHOR_ID, VOICE_LICENSED, VOICE_SORT}) {
            validateValue(key, options.text(key, defaultValue(key)));
        }
        if (!hasCatalogFilter()) throw new IllegalArgumentException("请先设置 Fish Audio 音色筛选条件");
    }

    public static String[] sampleRates(String format) {
        if ("wav".equals(format)) return WAV_SAMPLE_RATES;
        if ("opus".equals(format)) return OPUS_SAMPLE_RATES;
        return MP3_SAMPLE_RATES;
    }

    public static String validateValue(String key, String value) {
        String text = value == null ? "" : value.trim();
        if (BASE_URL.equals(key)) return validateBaseUrl(text);
        if (API_KEY.equals(key)) {
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (current <= 32 || current >= 127) throw new IllegalArgumentException("Fish Audio API Key 格式无效");
            }
            return text;
        }
        if (ENGINE.equals(key)) {
            String engine = stripEnginePrefix(text);
            if (!Arrays.asList(ENGINES).contains(engine)) {
                throw new IllegalArgumentException("请选择已支持的 Fish Audio 引擎，参考音色 ID 不能填入引擎");
            }
            return engine;
        }
        if (VOICE_MODE.equals(key)) return choice(text, VOICE_MODES, "Fish Audio 音色使用方式无效");
        if (REFERENCE_IDS.equals(key)) {
            return new JSONArray(parseReferenceIds(text)).toString();
        }
        if (VOICE_TAGS.equals(key)) return new JSONArray(parseVoiceTags(text)).toString();
        if (VOICE_LANGUAGE.equals(key) || VOICE_TITLE_LANGUAGE.equals(key)) return new JSONArray(parseVoiceLanguages(text)).toString();
        if (VOICE_AUTHOR_ID.equals(key)) {
            if (text.length() > 256 || text.codePoints().anyMatch(point -> Character.isISOControl(point)
                    || Character.getType(point) == Character.FORMAT || Character.getType(point) == Character.SURROGATE)) {
                throw new IllegalArgumentException("请输入有效的 Fish Audio 音色作者 ID");
            }
            return text;
        }
        if (VOICE_LICENSED.equals(key)) return choice(text, VOICE_LICENSED_VALUES, "Fish Audio 授权音色筛选无效");
        if (VOICE_SORT.equals(key)) return choice(text, VOICE_SORTS, "Fish Audio 音色排序无效");
        if (REFERENCE_SAMPLES.equals(key)) {
            return encodeReferenceGroups(parseReferenceGroups(text));
        }
        if (REFERENCE_ID.equals(key) || REFERENCE_NAME.equals(key) || VOICE_SEARCH.equals(key)) {
            if (containsControl(text) || REFERENCE_NAME.equals(key) && text.length() > 512) {
                throw new IllegalArgumentException("Fish Audio 参考音色设置含无效内容");
            }
            return text;
        }
        if (FORMAT.equals(key)) {
            String format = text.toLowerCase(Locale.ROOT);
            if ("pcm".equals(format) || format.startsWith("pcm_")) throw new IllegalArgumentException("当前发送入口不支持裸 PCM，请选择 WAV");
            return choice(format, FORMATS, "Fish Audio 音频格式无效");
        }
        if (SAMPLE_RATE.equals(key)) {
            if (text.isEmpty()) return "";
            String rate = integer(text, 1, Integer.MAX_VALUE, "请输入有效的 Fish Audio 采样率");
            return choice(rate, SAMPLE_RATES, "Fish Audio 采样率不受支持");
        }
        if (MP3_BITRATE.equals(key)) return choice(text, MP3_BITRATES, "请选择 64、128 或 192 kbps 的 MP3 码率");
        if (OPUS_BITRATE.equals(key)) return choice(text, OPUS_BITRATES, "请选择有效的 Opus 码率（单位 bps）或自动");
        if (SPEED.equals(key)) return number(text, 0.5, 2, false, "请输入 0.5 至 2 的 Fish Audio 语速");
        if (VOLUME_DB.equals(key)) return number(text, -20, 20, false, "请输入 -20 至 20 dB 的 Fish Audio 音量");
        if (TEMPERATURE.equals(key) || TOP_P.equals(key) || EARLY_STOP_THRESHOLD.equals(key)) {
            return number(text, 0, 1, false, "请输入 0 至 1 的 Fish Audio 生成参数");
        }
        if (REPETITION_PENALTY.equals(key)) return number(text, 0, Double.MAX_VALUE, true, "请输入正有限数值的重复惩罚");
        if (CHUNK_LENGTH.equals(key)) return integer(text, 100, 300, "请输入 100 至 300 的服务端分块长度");
        if (MIN_CHUNK_LENGTH.equals(key)) return integer(text, 0, 100, "请输入 0 至 100 的服务端最小分块长度");
        if (MAX_NEW_TOKENS.equals(key)) return integer(text, 1, Integer.MAX_VALUE, "请输入正整数的每块最大音频 Token 数");
        if (NORMALIZE_LOUDNESS.equals(key) || NORMALIZE.equals(key) || CONDITION_ON_PREVIOUS_CHUNKS.equals(key) || QUALITY_GUARD.equals(key)) {
            return choice(text, new String[]{"true", "false"}, "Fish Audio 开关选项无效");
        }
        if (LANGUAGE.equals(key)) {
            if (text.length() > 35 || !text.matches("[A-Za-z0-9_-]*")) throw new IllegalArgumentException("请输入有效的 Fish Audio 文本归一化语言标签");
            return text.replace('_', '-');
        }
        if (LATENCY.equals(key)) return choice(text, LATENCIES, "Fish Audio 延迟选项无效");
        if (VOICE_SCOPE.equals(key)) return choice(text, VOICE_SCOPES, "Fish Audio 音色列表范围无效");
        if (PRONUNCIATION_DICTIONARY.equals(key)) return validatePronunciationDictionary(text);
        throw new IllegalArgumentException("Fish Audio 设置字段无效");
    }

    private static String stripEnginePrefix(String value) {
        return value.startsWith("fish-audio/") ? value.substring("fish-audio/".length()) : value;
    }

    private static String validateBaseUrl(String value) {
        try {
            java.net.URI uri = java.net.URI.create(value);
            if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                    || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
            while (value.endsWith("/")) value = value.substring(0, value.length() - 1);
            return value;
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("请输入有效的 Fish Audio API 地址，不含认证、查询参数或片段");
        }
    }

    private static String choice(String value, String[] candidates, String message) {
        if (!Arrays.asList(candidates).contains(value)) throw new IllegalArgumentException(message);
        return value;
    }

    private static String integer(String value, int minimum, int maximum, String message) {
        try {
            if (!value.matches("[0-9]+")) throw new NumberFormatException();
            int parsed = Integer.parseInt(value);
            if (parsed >= minimum && parsed <= maximum) return Integer.toString(parsed);
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(message);
    }

    private static String number(String value, double minimum, double maximum, boolean positive, String message) {
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum && (!positive || parsed > 0)) {
                return java.math.BigDecimal.valueOf(parsed).stripTrailingZeros().toPlainString();
            }
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(message);
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }

    private static List<String> savedVoiceLanguages(String text) {
        try { return parseVoiceLanguages(text); }
        catch (IllegalArgumentException invalid) { return Collections.emptyList(); }
    }

    private static List<String> parseVoiceLanguages(String candidate) {
        String text = candidate == null ? "" : candidate.trim();
        if (text.isEmpty()) return Collections.emptyList();
        try {
            Object parsed = text.startsWith("[") ? ConfigJson.parse(text, 8192, 64) : new JSONArray().put(text);
            if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray values = (JSONArray) parsed;
            java.util.Set<String> languages = new java.util.LinkedHashSet<>();
            for (int index = 0; index < values.length(); index++) {
                Object raw = values.opt(index);
                if (!(raw instanceof String)) throw new IllegalArgumentException();
                String language = ((String) raw).replace('_', '-').toLowerCase(Locale.ROOT);
                if (language.isEmpty() || language.length() > 35 || !language.matches("[a-z]{2,3}(?:-[a-z0-9]{2,8})*")) {
                    throw new IllegalArgumentException();
                }
                languages.add(language);
            }
            return Collections.unmodifiableList(new ArrayList<>(languages));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 音色语言需为有效语言代码或最多 64 项的 JSON 字符串数组");
        }
    }

    private static List<String> parseVoiceTags(String text) {
        try {
            Object parsed = ConfigJson.parse(text, 8192, 5);
            if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray values = (JSONArray) parsed;
            java.util.Set<String> tags = new java.util.LinkedHashSet<>();
            for (int index = 0; index < values.length(); index++) {
                Object raw = values.opt(index);
                if (!(raw instanceof String)) throw new IllegalArgumentException();
                String tag = (String) raw;
                if (tag.codePoints().anyMatch(point -> Character.isISOControl(point)
                        || Character.getType(point) == Character.FORMAT || Character.getType(point) == Character.SURROGATE)) {
                    throw new IllegalArgumentException();
                }
                tag = tag.trim();
                if (tag.isEmpty() || tag.length() > 128) throw new IllegalArgumentException();
                tags.add(tag);
            }
            return Collections.unmodifiableList(new ArrayList<>(tags));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 音色标签需为最多 5 项、每项 1 至 128 字的 JSON 字符串数组");
        }
    }

    public static String validatePronunciationDictionary(String text) {
        if (text == null || text.trim().isEmpty()) return "";
        try {
            Object parsed = ConfigJson.parse(text, ConfigTransfer.MAX_BYTES, 5000);
            if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray dictionaries = (JSONArray) parsed;
            if (dictionaries.length() > 3) throw new IllegalArgumentException();
            Boolean reference = null;
            for (int index = 0; index < dictionaries.length(); index++) {
                JSONObject dictionary = dictionaries.optJSONObject(index);
                if (dictionary == null) throw new IllegalArgumentException();
                boolean isReference = dictionary.has("id") || dictionary.has("version");
                if (reference != null && reference != isReference) throw new IllegalArgumentException();
                reference = isReference;
                if (isReference) {
                    if (dictionary.length() != 2 || !(dictionary.opt("id") instanceof String)
                            || !(dictionary.opt("version") instanceof String)
                            || !dictionary.getString("id").matches("[A-Za-z0-9_-]{1,128}")
                            || !dictionary.getString("version").matches("[A-Za-z0-9_-]{1,128}")) throw new IllegalArgumentException();
                } else {
                    if (dictionary.length() != 1 || !(dictionary.opt("items") instanceof JSONArray)) throw new IllegalArgumentException();
                    JSONArray items = dictionary.getJSONArray("items");
                    if (items.length() > 5000) throw new IllegalArgumentException();
                    for (int itemIndex = 0; itemIndex < items.length(); itemIndex++) {
                        JSONObject item = items.optJSONObject(itemIndex);
                        if (item == null || !(item.opt("key") instanceof String) || !(item.opt("value") instanceof String)
                                || item.length() != (item.has("case_sensitive") ? 3 : 2)
                                || item.has("case_sensitive") && !(item.opt("case_sensitive") instanceof Boolean)) throw new IllegalArgumentException();
                        String key = item.getString("key"), value = item.getString("value");
                        int keyChars = key.codePointCount(0, key.length()), valueChars = value.codePointCount(0, value.length());
                        if (keyChars < 1 || keyChars > 256 || valueChars < 1 || valueChars > 1024
                                || reservedPhonemeMarker(key) || reservedPhonemeMarker(value)) throw new IllegalArgumentException();
                    }
                }
            }
            return dictionaries.toString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 发音词典需为最多 3 个同类词典的 JSON 数组，请核对字段与长度");
        }
    }

    private static boolean reservedPhonemeMarker(String value) {
        return value.contains("<|phoneme_start|>") || value.contains("<|phoneme_end|>");
    }

    public static final class ReferenceSample {
        public final String path, text, name;
        private ReferenceSample(String path, String text, String name) {
            this.path = path;
            this.text = text;
            this.name = name;
        }
    }

    public List<List<ReferenceSample>> referenceGroups() {
        return parseReferenceGroups(referenceSamples);
    }

    private static List<String> parseReferenceIds(String text) {
        try {
            Object parsed = ConfigJson.parse(text == null || text.trim().isEmpty() ? "[]" : text,
                    ConfigTransfer.MAX_BYTES, MAX_REFERENCE_GROUPS);
            if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray items = (JSONArray) parsed;
            List<String> ids = new ArrayList<>();
            for (int index = 0; index < items.length(); index++) {
                if (!(items.opt(index) instanceof String)) throw new IllegalArgumentException();
                String id = validateValue(REFERENCE_ID, items.getString(index));
                if (id.isEmpty()) throw new IllegalArgumentException();
                ids.add(id);
            }
            return Collections.unmodifiableList(ids);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 说话人音色需为最多 8 项非空 ID 的 JSON 数组");
        }
    }

    private static List<List<ReferenceSample>> parseReferenceGroups(String text) {
        try {
            Object parsed = ConfigJson.parse(text == null || text.trim().isEmpty() ? "[]" : text,
                    ConfigTransfer.MAX_BYTES, MAX_REFERENCE_GROUPS);
            if (!(parsed instanceof JSONArray)) throw new IllegalArgumentException();
            JSONArray groups = (JSONArray) parsed;
            List<List<ReferenceSample>> result = new ArrayList<>();
            for (int groupIndex = 0; groupIndex < groups.length(); groupIndex++) {
                JSONArray group = groups.optJSONArray(groupIndex);
                if (group == null || group.length() > MAX_SAMPLES_PER_GROUP) throw new IllegalArgumentException();
                List<ReferenceSample> samples = new ArrayList<>();
                for (int itemIndex = 0; itemIndex < group.length(); itemIndex++) {
                    JSONObject sample = group.optJSONObject(itemIndex);
                    if (sample == null || !(sample.opt("path") instanceof String) || !(sample.opt("text") instanceof String)
                            || sample.length() != (sample.has("name") ? 3 : 2)
                            || sample.has("name") && !(sample.opt("name") instanceof String)) throw new IllegalArgumentException();
                    String path = sample.getString("path"), transcript = sample.getString("text");
                    String name = sample.has("name") ? sample.getString("name") : "";
                    if (path.isEmpty() || containsControl(path)
                            || !(path.startsWith("/") || new java.io.File(path).isAbsolute())
                            || transcript.trim().isEmpty() || name.length() > 512 || containsControl(name)) {
                        throw new IllegalArgumentException();
                    }
                    samples.add(new ReferenceSample(path, transcript, name));
                }
                result.add(Collections.unmodifiableList(samples));
            }
            return Collections.unmodifiableList(result);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 本机参考音频需为最多 8 组、每组 4 项的有效文件与原文配置");
        }
    }

    private static String encodeReferenceGroups(List<List<ReferenceSample>> groups) {
        try {
            JSONArray result = new JSONArray();
            for (List<ReferenceSample> group : groups) {
                JSONArray samples = new JSONArray();
                for (ReferenceSample sample : group) {
                    JSONObject item = new JSONObject().put("path", sample.path).put("text", sample.text);
                    if (!sample.name.isEmpty()) item.put("name", sample.name);
                    samples.put(item);
                }
                result.put(samples);
            }
            return result.toString();
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Fish Audio 本机参考音频配置无效");
        }
    }
}
