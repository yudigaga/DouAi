package com.douai.module.config;

/** Independent Breeze Blue synthesis settings; cached catalog responses are not transferable. */
public final class BreezeSpeechSettings {
    public static final String API_KEY = "tts_breeze_api_key";
    public static final String BASE_URL = "tts_breeze_base_url";
    public static final String MODEL = "tts_breeze_model";
    public static final String DELIVERY = "tts_breeze_delivery";
    public static final String VOICE_ID = "tts_breeze_voice_id";
    public static final String VOICE_NAME = "tts_breeze_voice_name";
    public static final String VOICE_TYPE = "tts_breeze_voice_type";
    public static final String VOICE_SEARCH = "tts_breeze_voice_search";
    public static final String VOICE_LANGUAGE = "tts_breeze_voice_language";
    public static final String VOICE_GENDER = "tts_breeze_voice_gender";
    public static final String VOICE_TONE = "tts_breeze_voice_tone";
    public static final String VOICE_TAGS = "tts_breeze_voice_tags";
    public static final String VOICE_CATEGORY = "tts_breeze_voice_category";
    public static final String VOICE_AGE = "tts_breeze_voice_age";
    public static final String VOICE_ACCENT = "tts_breeze_voice_accent";
    public static final String VOICE_ACCENT_MODE = "tts_breeze_voice_accent_mode";
    public static final String VOICE_ORIGIN = "tts_breeze_voice_origin";
    public static final String VOICE_SORT = "tts_breeze_voice_sort";
    public static final String VOICE_SORT_DIRECTION = "tts_breeze_voice_sort_direction";
    public static final String VOICE_FAVORITES_ONLY = "tts_breeze_voice_favorites_only";
    public static final String SPEED = "tts_breeze_speed";
    public static final String VOLUME = "tts_breeze_volume";
    public static final String GUIDANCE_SCALE = "tts_breeze_guidance_scale";
    public static final String SPEAKER_BOOST = "tts_breeze_speaker_boost";
    public static final String OUTPUT_FORMAT = "tts_breeze_output_format";
    public static final String INSTRUCTIONS = "tts_breeze_instructions";
    public static final String LANGUAGE_CODE = "tts_breeze_language_code";
    public static final String VOICE_SAMPLE = "tts_breeze_voice_sample";
    public static final String MODEL_SAMPLE = "tts_breeze_model_sample";
    public static final String MODEL_SOURCE = "tts_breeze_model_source";
    public static final String DEFAULT_BASE_URL = "https://api.breeze.blue";
    public static final String AUTO_MODEL = "auto";
    public static final String DEFAULT_MODEL = AUTO_MODEL;
    public static final String DEFAULT_OUTPUT_FORMAT = "mp3";
    public static final String[] KEYS = {API_KEY, BASE_URL, MODEL, DELIVERY, VOICE_ID, VOICE_NAME, VOICE_TYPE,
            VOICE_SEARCH, VOICE_LANGUAGE, VOICE_GENDER, VOICE_TONE, VOICE_TAGS, VOICE_CATEGORY, VOICE_AGE,
            VOICE_ACCENT, VOICE_ACCENT_MODE, VOICE_ORIGIN, VOICE_SORT, VOICE_SORT_DIRECTION, VOICE_FAVORITES_ONLY, SPEED, VOLUME,
            GUIDANCE_SCALE, SPEAKER_BOOST, OUTPUT_FORMAT, INSTRUCTIONS, LANGUAGE_CODE};
    public static final String[] BUILT_IN_MODELS = {AUTO_MODEL, "breeze-tts-2", "breeze-tts-2-multilingual"};
    public static final String[] DELIVERIES = {"sync", "stream", "async"};
    public static final String[] VOICE_TYPES = {"all", "default", "personal"};
    public static final String[] VOICE_GENDERS = {"", "male", "female", "neutral"};
    public static final String[] VOICE_TONES = {"", "warm", "calm", "bright", "gentle", "energetic", "authoritative", "sincere",
            "weary", "precise", "refined", "urgent", "friendly", "articulate", "steady", "playful", "compassionate",
            "reflective", "measured", "rhythmic", "passionate"};
    public static final String[] VOICE_AGES = {"child", "young", "middle_aged", "old"};
    public static final String[] VOICE_CATEGORIES = {"", "narration", "social-media", "ads-brand", "podcast", "learning",
            "support-agents", "roleplay", "gaming", "animation", "cinematic", "wellness", "stylized"};
    public static final String[] VOICE_ACCENTS = {"", "american", "british", "scottish", "irish", "australian", "canadian",
            "us_southern", "us_new_york", "indian", "south_african", "russian", "japanese", "korean", "chinese",
            "mandarin_northeastern", "mandarin_shaanxi", "mandarin_shanghai", "mandarin_sichuan", "mandarin_henan",
            "mandarin_beijing", "mandarin_taiwan", "cantonese", "mandarin_guangdong", "mandarin_yunnan"};
    public static final String[] VOICE_ACCENT_MODES = {"all", "unmarked"};
    public static final String[] VOICE_ORIGINS = {"", "designed", "cloned"};
    public static final String[] VOICE_SORTS = {"created_at_unix", "name", "trend"};
    public static final String[] VOICE_SORT_DIRECTIONS = {"asc", "desc"};
    public static final String[] FORMATS = {"mp3", "wav", "flac", "aac", "opus"};
    public static final String[] OUTPUT_FORMATS = {"mp3", "wav", "flac", "aac", "opus", "mp3_22050_32",
            "mp3_44100_128", "mp3_44100_192", "wav_48000", "opus_48000_64", "flac_48000", "aac_44100_128"};
    public static final String[] SAMPLE_RATES = {"8000", "11025", "12000", "16000", "22050", "24000", "32000", "44100", "48000"};
    public static final String[] OPUS_SAMPLE_RATES = {"8000", "12000", "16000", "24000", "48000"};
    public static final String[] MP3_HIGH_BITRATES = {"32", "40", "48", "56", "64", "80", "96", "112", "128", "160", "192", "224", "256", "320"};
    public static final String[] MP3_LOW_BITRATES = {"8", "16", "24", "32", "40", "48", "56", "64", "80", "96", "112", "128", "144", "160"};
    public static final String[] LANGUAGE_CODES = {"", "af", "ar", "bg", "bn", "bs", "ca", "cs", "da",
            "de", "el", "en", "es", "et", "eu", "fa", "fi", "fr", "gl", "he", "hi", "hu", "id",
            "is", "it", "ja", "kn", "ko", "lt", "mr", "ms", "ne", "nl", "no", "pl", "pt", "ro",
            "ru", "sk", "sl", "sq", "sv", "sw", "ta", "te", "th", "tl", "tr", "uk", "ur", "vi", "zh"};

    private final LocalOptions options;
    public final String apiKey, baseUrl, model, delivery, voiceId, voiceName, outputFormat, instructions, languageCode;
    public final String voiceType, voiceSearch, voiceLanguage, voiceGender, voiceTone;
    public final String voiceCategory, voiceAccent, voiceAccentMode, voiceOrigin, voiceSort, voiceSortDirection;
    public final java.util.List<String> voiceGenders, voiceAges, voiceTones;
    public final boolean voiceFavoritesOnly;
    public final java.util.List<String> voiceTags;
    public final float speed, volume, guidanceScale;
    public final boolean speakerBoost;

    public BreezeSpeechSettings(LocalOptions options) {
        this.options = options;
        apiKey = value(API_KEY);
        baseUrl = value(BASE_URL);
        String savedModel = value(MODEL);
        model = savedModel.isEmpty() ? AUTO_MODEL : savedModel;
        delivery = value(DELIVERY);
        voiceId = value(VOICE_ID);
        voiceName = value(VOICE_NAME);
        voiceType = value(VOICE_TYPE);
        voiceSearch = value(VOICE_SEARCH);
        voiceLanguage = value(VOICE_LANGUAGE).toLowerCase(java.util.Locale.ROOT);
        voiceGenders = savedCatalogChoices(value(VOICE_GENDER), VOICE_GENDERS, 3, "性别");
        voiceAges = savedCatalogChoices(value(VOICE_AGE), VOICE_AGES, 4, "年龄");
        voiceTones = savedCatalogChoices(value(VOICE_TONE), VOICE_TONES, 20, "语气");
        voiceGender = voiceGenders.isEmpty() ? "" : voiceGenders.get(0);
        voiceTone = voiceTones.isEmpty() ? "" : voiceTones.get(0);
        voiceCategory = value(VOICE_CATEGORY);
        voiceAccent = value(VOICE_ACCENT);
        voiceAccentMode = value(VOICE_ACCENT_MODE);
        voiceOrigin = value(VOICE_ORIGIN);
        voiceSort = value(VOICE_SORT);
        voiceSortDirection = value(VOICE_SORT_DIRECTION);
        voiceFavoritesOnly = Boolean.parseBoolean(value(VOICE_FAVORITES_ONLY));
        java.util.List<String> savedTags;
        try { savedTags = parseVoiceTags(value(VOICE_TAGS)); }
        catch (IllegalArgumentException invalid) { savedTags = java.util.Collections.emptyList(); }
        voiceTags = savedTags;
        String savedFormat = value(OUTPUT_FORMAT);
        String normalizedFormat;
        try { normalizedFormat = validateOutputFormat(savedFormat); }
        catch (IllegalArgumentException invalid) { normalizedFormat = savedFormat; }
        outputFormat = normalizedFormat;
        instructions = value(INSTRUCTIONS);
        languageCode = value(LANGUAGE_CODE).toLowerCase(java.util.Locale.ROOT);
        speed = options.decimal(SPEED, 1f);
        volume = options.decimal(VOLUME, 1f);
        guidanceScale = options.decimal(GUIDANCE_SCALE, 1f);
        speakerBoost = Boolean.parseBoolean(value(SPEAKER_BOOST));
    }

    private String value(String key) { return options.text(key, defaultValue(key)).trim(); }

    public static String defaultValue(String key) {
        if (BASE_URL.equals(key)) return DEFAULT_BASE_URL;
        if (MODEL.equals(key)) return DEFAULT_MODEL;
        if (DELIVERY.equals(key)) return "sync";
        if (VOICE_TYPE.equals(key)) return "all";
        if (VOICE_LANGUAGE.equals(key)) return "zh";
        if (VOICE_GENDER.equals(key) || VOICE_AGE.equals(key) || VOICE_TONE.equals(key)) return "[]";
        if (VOICE_ACCENT_MODE.equals(key)) return "all";
        if (VOICE_SORT.equals(key)) return "created_at_unix";
        if (VOICE_SORT_DIRECTION.equals(key)) return "desc";
        if (VOICE_FAVORITES_ONLY.equals(key)) return "false";
        if (VOICE_TAGS.equals(key)) return "[]";
        if (OUTPUT_FORMAT.equals(key)) return DEFAULT_OUTPUT_FORMAT;
        if (SPEED.equals(key) || VOLUME.equals(key) || GUIDANCE_SCALE.equals(key)) return "1";
        if (SPEAKER_BOOST.equals(key)) return "false";
        return "";
    }

    public static boolean isKey(String key) { return java.util.Arrays.asList(KEYS).contains(key); }

    public static String candidateIssue(java.util.Map<String, ?> current, String key, String candidate) {
        try {
            if (!isKey(key)) throw new IllegalArgumentException("Breeze Blue 设置字段无效");
            java.util.Map<String, Object> values = new java.util.LinkedHashMap<>(current);
            values.put(key, validateValue(key, candidate));
            new BreezeSpeechSettings(new LocalOptions(values)).validateOptions();
            return "";
        } catch (IllegalArgumentException invalid) { return invalid.getMessage(); }
    }

    /** Backups may preserve an unfinished provider setup, but never malformed saved parameters. */
    public void validateOptions() {
        for (String key : KEYS) validateValue(key, options.text(key, defaultValue(key)));
        validateCatalogCombination();
        if ("breeze-tts-2".equals(model) && !languageCode.isEmpty()
                && !"en".equals(languageCode) && !"zh".equals(languageCode)) {
            throw new IllegalArgumentException("Breeze TTS 2 只接受 en 或 zh，请先选择多语言模型");
        }
        String codec = outputFormat.split("_", -1)[0];
        if ("stream".equals(delivery) && !"mp3".equals(codec) && !"wav".equals(codec)) {
            throw new IllegalArgumentException("Breeze Blue 流式输出仅支持 MP3 或 WAV，请先调整音频格式");
        }
    }

    public void validateForSynthesis() {
        validateOptions();
        if (apiKey.isEmpty()) throw new IllegalArgumentException("请设置 Breeze Blue API Key");
        if (voiceId.isEmpty()) throw new IllegalArgumentException("请选择 Breeze Blue 音色或填写音色 ID");
    }

    public boolean hasCatalogFilter() {
        return "personal".equals(voiceType) || "default".equals(voiceType) || !voiceLanguage.isEmpty() || !voiceSearch.isEmpty()
                || !voiceGenders.isEmpty() || !voiceAges.isEmpty() || !voiceTones.isEmpty() || !voiceTags.isEmpty()
                || !voiceCategory.isEmpty() || !voiceAccent.isEmpty() || "unmarked".equals(voiceAccentMode)
                || !voiceOrigin.isEmpty() || voiceFavoritesOnly;
    }

    public void validateCatalog() {
        for (String key : new String[]{BASE_URL, API_KEY, VOICE_TYPE, VOICE_SEARCH, VOICE_LANGUAGE, VOICE_GENDER, VOICE_TONE,
                VOICE_TAGS, VOICE_CATEGORY, VOICE_AGE, VOICE_ACCENT, VOICE_ACCENT_MODE, VOICE_ORIGIN, VOICE_SORT,
                VOICE_SORT_DIRECTION, VOICE_FAVORITES_ONLY}) {
            validateValue(key, options.text(key, defaultValue(key)));
        }
        validateCatalogCombination();
        if (!hasCatalogFilter()) throw new IllegalArgumentException("请先设置 Breeze Blue 音色筛选条件");
    }

    private void validateCatalogCombination() {
        if (!voiceAccent.isEmpty() && "unmarked".equals(voiceAccentMode)) {
            throw new IllegalArgumentException("Breeze Blue 指定口音不能与仅无口音标记同时使用");
        }
        if ("trend".equals(voiceSort) && !"default".equals(voiceType)) {
            throw new IllegalArgumentException("Breeze Blue 热门排序仅适用于系统音色范围");
        }
    }

    /** The provider gives instruction guidance precedence over speaker enhancement. */
    public boolean effectiveSpeakerBoost() {
        return speakerBoost && (instructions.isEmpty() || guidanceScale <= 1f);
    }

    public float effectiveGuidanceScale() {
        return effectiveSpeakerBoost() ? 1f : guidanceScale;
    }

    public static String validateValue(String key, String value) {
        String text = value == null ? "" : value.trim();
        if (BASE_URL.equals(key)) {
            try {
                java.net.URI uri = java.net.URI.create(text);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) {
                    throw new IllegalArgumentException();
                }
                while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
                return text;
            } catch (RuntimeException invalid) {
                throw new IllegalArgumentException("请输入有效的 Breeze Blue API 地址，不含认证、查询参数或片段");
            }
        }
        if (API_KEY.equals(key)) {
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (current <= 32 || current >= 127) throw new IllegalArgumentException("Breeze Blue API Key 格式无效");
            }
            return text;
        }
        if (MODEL.equals(key) || VOICE_ID.equals(key)) {
            if (MODEL.equals(key) && text.isEmpty()) return AUTO_MODEL;
            if (text.length() > (MODEL.equals(key) ? 120 : 256) || containsControl(text)) {
                throw new IllegalArgumentException(MODEL.equals(key) ? "请输入有效的 Breeze Blue 模型" : "请输入有效的 Breeze Blue 音色 ID");
            }
            return text;
        }
        if (DELIVERY.equals(key)) {
            if (!java.util.Arrays.asList(DELIVERIES).contains(text)) throw new IllegalArgumentException("Breeze Blue 音频交付方式无效");
            return text;
        }
        if (VOICE_NAME.equals(key) || VOICE_SEARCH.equals(key)) {
            if (VOICE_NAME.equals(key) && text.length() > 512 || containsControl(text)) {
                throw new IllegalArgumentException(VOICE_NAME.equals(key) ? "Breeze Blue 音色名称无效" : "Breeze Blue 音色搜索内容无效");
            }
            return text;
        }
        if (VOICE_TYPE.equals(key)) {
            if (!java.util.Arrays.asList(VOICE_TYPES).contains(text)) throw new IllegalArgumentException("请选择全部、系统或个人 Breeze Blue 音色范围");
            return text;
        }
        if (VOICE_GENDER.equals(key)) {
            return new org.json.JSONArray(parseCatalogChoices(text, VOICE_GENDERS, 3, "性别")).toString();
        }
        if (VOICE_TONE.equals(key)) {
            return new org.json.JSONArray(parseCatalogChoices(text, VOICE_TONES, 20, "语气")).toString();
        }
        if (VOICE_AGE.equals(key)) return new org.json.JSONArray(parseCatalogChoices(text, VOICE_AGES, 4, "年龄")).toString();
        if (VOICE_CATEGORY.equals(key)) return catalogChoice(text, VOICE_CATEGORIES, "分类");
        if (VOICE_ACCENT.equals(key)) return catalogChoice(text, VOICE_ACCENTS, "口音");
        if (VOICE_ACCENT_MODE.equals(key)) return catalogChoice(text, VOICE_ACCENT_MODES, "口音范围");
        if (VOICE_ORIGIN.equals(key)) return catalogChoice(text, VOICE_ORIGINS, "来源");
        if (VOICE_SORT.equals(key)) return catalogChoice(text, VOICE_SORTS, "排序");
        if (VOICE_SORT_DIRECTION.equals(key)) return catalogChoice(text, VOICE_SORT_DIRECTIONS, "排序方向");
        if (VOICE_FAVORITES_ONLY.equals(key)) {
            if (!"true".equals(text) && !"false".equals(text)) throw new IllegalArgumentException("Breeze Blue 收藏音色筛选无效");
            return text;
        }
        if (VOICE_TAGS.equals(key)) return new org.json.JSONArray(parseVoiceTags(text)).toString();
        if (SPEAKER_BOOST.equals(key)) {
            if (!"true".equals(text) && !"false".equals(text)) throw new IllegalArgumentException("Breeze Blue 说话人增强选项无效");
            return text;
        }
        if (INSTRUCTIONS.equals(key)) {
            for (int index = 0; index < text.length(); index++) {
                char current = text.charAt(index);
                if (Character.isISOControl(current) && current != '\n' && current != '\r' && current != '\t') {
                    throw new IllegalArgumentException("Breeze Blue 语音指令含无效控制字符");
                }
            }
            return text;
        }
        if (LANGUAGE_CODE.equals(key) || VOICE_LANGUAGE.equals(key)) {
            text = text.toLowerCase(java.util.Locale.ROOT);
            if (!java.util.Arrays.asList(LANGUAGE_CODES).contains(text)) {
                throw new IllegalArgumentException("请选择有效的 Breeze Blue 两字母语言代码");
            }
            return text;
        }
        if (OUTPUT_FORMAT.equals(key)) {
            return validateOutputFormat(text);
        }
        if (SPEED.equals(key)) return number(text, 0.5, 2, "请输入 0.5 至 2 的 Breeze Blue 语速");
        if (VOLUME.equals(key)) return number(text, 0.01, 2, "请输入 0.01 至 2 的 Breeze Blue 音量");
        if (GUIDANCE_SCALE.equals(key)) return number(text, 1, 10, "请输入 1 至 10 的 Breeze Blue 指令强度");
        throw new IllegalArgumentException("Breeze Blue 设置字段无效");
    }

    public static String validateOutputFormat(String candidate) {
        String text = candidate == null ? "" : candidate.trim().toLowerCase(java.util.Locale.ROOT);
        if (text.length() > 128) throw new IllegalArgumentException("Breeze Blue 音频格式无效");
        String[] parts = text.split("_", -1);
        if (parts[0].equals("pcm")) throw new IllegalArgumentException("当前发送入口不支持裸 PCM，请选择 WAV");
        if (parts.length > 3 || !java.util.Arrays.asList(FORMATS).contains(parts[0])) {
            throw new IllegalArgumentException("Breeze Blue 音频格式无效");
        }
        if (parts.length == 1) return parts[0];
        try {
            if (!parts[1].matches("[0-9]+")) throw new NumberFormatException();
            int rate = Integer.parseInt(parts[1]);
            String rateText = Integer.toString(rate);
            String[] allowedRates = "opus".equals(parts[0]) ? OPUS_SAMPLE_RATES : SAMPLE_RATES;
            if (!java.util.Arrays.asList(allowedRates).contains(rateText)) throw new NumberFormatException();
            String normalized = parts[0] + "_" + rateText;
            if (parts.length == 2) return normalized;
            if (!parts[2].matches("[0-9]+")) throw new NumberFormatException();
            int bitrate = Integer.parseInt(parts[2]);
            boolean allowed = "mp3".equals(parts[0])
                    ? java.util.Arrays.asList(rate >= 32000 ? MP3_HIGH_BITRATES : MP3_LOW_BITRATES).contains(Integer.toString(bitrate))
                    : "aac".equals(parts[0]) ? bitrate >= 8 && bitrate <= 320
                    : "opus".equals(parts[0]) && bitrate >= 6 && bitrate <= 510;
            if (!allowed) throw new NumberFormatException();
            return normalized + "_" + bitrate;
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Breeze Blue 格式的采样率或码率不受支持");
        }
    }

    private static String number(String value, double minimum, double maximum, String message) {
        try {
            double parsed = Double.parseDouble(value);
            if (Double.isFinite(parsed) && parsed >= minimum && parsed <= maximum) {
                return java.math.BigDecimal.valueOf(parsed).stripTrailingZeros().toPlainString();
            }
        } catch (NumberFormatException ignored) { }
        throw new IllegalArgumentException(message);
    }

    private static String catalogChoice(String text, String[] choices, String label) {
        if (!java.util.Arrays.asList(choices).contains(text)) throw new IllegalArgumentException("请选择有效的 Breeze Blue 音色" + label);
        return text;
    }

    private static java.util.List<String> savedCatalogChoices(String text, String[] choices, int max, String label) {
        try { return parseCatalogChoices(text, choices, max, label); }
        catch (IllegalArgumentException invalid) { return java.util.Collections.emptyList(); }
    }

    private static java.util.List<String> parseCatalogChoices(String candidate, String[] choices, int max, String label) {
        String text = candidate == null ? "" : candidate.trim();
        if (text.isEmpty()) return java.util.Collections.emptyList();
        try {
            Object parsed = text.startsWith("[") ? ConfigJson.parse(text, 8192, max) : new org.json.JSONArray().put(text);
            if (!(parsed instanceof org.json.JSONArray)) throw new IllegalArgumentException();
            org.json.JSONArray values = (org.json.JSONArray) parsed;
            java.util.Set<String> result = new java.util.LinkedHashSet<>();
            for (int index = 0; index < values.length(); index++) {
                Object raw = values.opt(index);
                if (!(raw instanceof String) || ((String) raw).isEmpty() || !java.util.Arrays.asList(choices).contains(raw)) {
                    throw new IllegalArgumentException();
                }
                result.add((String) raw);
            }
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(result));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Breeze Blue 音色" + label + "需为有效代码或最多 " + max + " 项的 JSON 字符串数组");
        }
    }

    private static java.util.List<String> parseVoiceTags(String text) {
        try {
            Object parsed = ConfigJson.parse(text, 8192, 5);
            if (!(parsed instanceof org.json.JSONArray)) throw new IllegalArgumentException();
            org.json.JSONArray values = (org.json.JSONArray) parsed;
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
            return java.util.Collections.unmodifiableList(new java.util.ArrayList<>(tags));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("Breeze Blue 音色标签需为最多 5 项、每项 1 至 128 字的 JSON 字符串数组");
        }
    }

    private static boolean containsControl(String value) {
        for (int index = 0; index < value.length(); index++) if (Character.isISOControl(value.charAt(index))) return true;
        return false;
    }
}
