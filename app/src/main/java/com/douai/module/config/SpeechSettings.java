package com.douai.module.config;

/** Host-local speech settings shared by the editor and synthesis requests. */
public final class SpeechSettings {
    public static final String PROVIDER = "tts_provider";
    public static final String LOCAL = "local";
    public static final String THIRD_PARTY = "third_party";
    public static final String MINIMAX = "minimax";
    public static final String BREEZE = "breeze";
    public static final String FISH = "fish";
    public static final String MINIMAX_API_KEY = "tts_minimax_api_key";
    public static final String MINIMAX_BASE_URL = "tts_minimax_base_url";
    public static final String MINIMAX_MODEL = "tts_minimax_model";
    public static final String MINIMAX_VOICE_ID = "tts_minimax_voice_id";
    public static final String MINIMAX_VOICE_NAME = "tts_minimax_voice_name";
    public static final String MINIMAX_SPEED = "tts_minimax_speed";
    public static final String MINIMAX_VOLUME = "tts_minimax_volume";
    public static final String MINIMAX_PITCH = "tts_minimax_pitch";
    public static final String MINIMAX_VOICE_SAMPLE = "tts_minimax_voice_sample";
    public static final String MINIMAX_MODEL_SAMPLE = "tts_minimax_model_sample";
    public static final String MINIMAX_MODEL_SOURCE = "tts_minimax_model_source";
    public static final String DEFAULT_MINIMAX_BASE_URL = "https://api.minimax.cn";
    public static final String DEFAULT_MINIMAX_MODEL = "speech-2.8-hd";
    public static final String DEFAULT_MINIMAX_VOICE_ID = "male-qn-qingse";
    public static final String RATE = "tts_rate";
    public static final String PITCH = "tts_pitch";
    public static final String LOCAL_VOICE_NAME = "tts_voice_name";
    public static final String GET_TEMPLATE = "tts_get_template";
    public static final String HEADERS = "tts_headers";
    public static final String AUDIO_FIELD = "tts_audio_field";
    public static final String VOICE_GET_TEMPLATE = "tts_voice_get_template";
    public static final String VOICE_HEADERS = "tts_voice_headers";
    public static final String VOICE_LIST_PATH = "tts_voice_list_path";
    public static final String VOICE_ID_PATH = "tts_voice_id_path";
    public static final String VOICE_NAME_PATH = "tts_voice_name_path";
    public static final String SELECTED_VOICE = "tts_selected_voice";
    public static final String SELECTED_VOICE_NAME = "tts_selected_voice_name";
    public static final String DEFAULT_GET_TEMPLATE =
            "http://www.yx520.ltd/API/wzzyypromax/api.php?voice={voice}&apikey=&text={text}";
    public static final String DEFAULT_VOICE_GET_TEMPLATE = "https://www.yx520.ltd/API/wzzyypromax/ys.php";
    public static final String DEFAULT_AUDIO_FIELD = "url";
    private static final float DEFAULT_LOCAL_FACTOR = 1f;
    private static final float MIN_LOCAL_FACTOR = 0.5f;
    private static final float MAX_LOCAL_FACTOR = 2f;

    public final boolean thirdParty;
    public final boolean minimax;
    public final boolean breeze;
    public final boolean fish;
    public final boolean local;
    public final String minimaxApiKey;
    public final String minimaxBaseUrl;
    public final String minimaxModel;
    public final String minimaxVoiceId;
    public final String minimaxVoiceName;
    public final float minimaxSpeed;
    public final float minimaxVolume;
    public final int minimaxPitch;
    public final MiniMaxSpeechOptions minimaxOptions;
    public final BreezeSpeechSettings breezeSettings;
    public final FishSpeechSettings fishSettings;
    private final LocalOptions options;
    public final float rate;
    public final float pitch;
    public final String getTemplate;
    public final String headers;
    public final String audioField;
    public final String voiceGetTemplate;
    public final String voiceHeaders;
    public final String voiceListPath;
    public final String voiceIdPath;
    public final String voiceNamePath;
    public final String selectedVoice;
    public final String selectedVoiceName;

    public SpeechSettings(LocalOptions options) {
        this.options = options;
        thirdParty = THIRD_PARTY.equals(options.text(PROVIDER, LOCAL));
        minimax = MINIMAX.equals(options.text(PROVIDER, LOCAL));
        breeze = BREEZE.equals(options.text(PROVIDER, LOCAL));
        fish = FISH.equals(options.text(PROVIDER, LOCAL));
        local = !thirdParty && !minimax && !breeze && !fish;
        breezeSettings = new BreezeSpeechSettings(options);
        fishSettings = new FishSpeechSettings(options);
        minimaxApiKey = options.text(MINIMAX_API_KEY, "").trim();
        minimaxBaseUrl = options.text(MINIMAX_BASE_URL, DEFAULT_MINIMAX_BASE_URL).trim();
        minimaxModel = options.text(MINIMAX_MODEL, DEFAULT_MINIMAX_MODEL).trim();
        minimaxVoiceId = options.text(MINIMAX_VOICE_ID, DEFAULT_MINIMAX_VOICE_ID).trim();
        minimaxVoiceName = options.text(MINIMAX_VOICE_NAME, "").trim();
        minimaxSpeed = options.decimal(MINIMAX_SPEED, 1f);
        minimaxVolume = options.decimal(MINIMAX_VOLUME, 1f);
        minimaxPitch = options.integer(MINIMAX_PITCH, 0);
        minimaxOptions = new MiniMaxSpeechOptions(options);
        rate = localFactor(options, RATE);
        pitch = localFactor(options, PITCH);
        getTemplate = options.text(GET_TEMPLATE, DEFAULT_GET_TEMPLATE).trim();
        headers = options.text(HEADERS, "{}");
        audioField = options.text(AUDIO_FIELD, DEFAULT_AUDIO_FIELD).trim();
        voiceGetTemplate = options.text(VOICE_GET_TEMPLATE, DEFAULT_VOICE_GET_TEMPLATE).trim();
        voiceHeaders = options.text(VOICE_HEADERS, "{}");
        voiceListPath = options.text(VOICE_LIST_PATH, "").trim();
        voiceIdPath = options.text(VOICE_ID_PATH, "").trim();
        voiceNamePath = options.text(VOICE_NAME_PATH, "").trim();
        selectedVoice = options.text(SELECTED_VOICE, "1").trim();
        selectedVoiceName = options.text(SELECTED_VOICE_NAME, "").trim();
    }

    public String rateText() { return factorText(rate); }

    public String pitchText() { return factorText(pitch); }

    public String minimaxSpeedText() { return factorText(minimaxSpeed); }
    public String minimaxVolumeText() { return factorText(minimaxVolume); }
    public String minimaxPitchText() { return Integer.toString(minimaxPitch); }

    /** Request validation also checks original text, preventing malformed saved values from silently falling back. */
    public void validateMiniMax() {
        if (minimaxApiKey.isEmpty() || containsControl(minimaxApiKey)) throw new IllegalArgumentException("请设置有效的 MiniMax API Key");
        validateMiniMaxValue(MINIMAX_BASE_URL, minimaxBaseUrl);
        validateMiniMaxValue(MINIMAX_MODEL, minimaxModel);
        if (!minimaxOptions.hasMixedVoices()) validateMiniMaxValue(MINIMAX_VOICE_ID, minimaxVoiceId);
        validateMiniMaxValue(MINIMAX_SPEED, options.text(MINIMAX_SPEED, minimaxSpeedText()));
        validateMiniMaxValue(MINIMAX_VOLUME, options.text(MINIMAX_VOLUME, minimaxVolumeText()));
        validateMiniMaxValue(MINIMAX_PITCH, options.text(MINIMAX_PITCH, minimaxPitchText()));
        minimaxOptions.validate();
    }

    public void validateBreeze() { breezeSettings.validateForSynthesis(); }
    public void validateFish() { fishSettings.validateForSynthesis(); }

    public static String validateProvider(String value) {
        if (!LOCAL.equals(value) && !THIRD_PARTY.equals(value) && !MINIMAX.equals(value) && !BREEZE.equals(value) && !FISH.equals(value)) {
            throw new IllegalArgumentException("语音来源无效");
        }
        return value;
    }

    /** Independent MiniMax controls use the provider's ranges, separate from Android TTS. */
    public static String validateMiniMaxValue(String key, String value) {
        if (MiniMaxSpeechOptions.isKey(key)) return MiniMaxSpeechOptions.validateValue(key, value);
        String text = value == null ? "" : value.trim();
        if (MINIMAX_BASE_URL.equals(key)) {
            try {
                java.net.URI uri = java.net.URI.create(text);
                if (!("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                        || uri.getHost() == null || uri.getRawUserInfo() != null || uri.getRawQuery() != null
                        || uri.getRawFragment() != null || uri.getPort() == 0 || uri.getPort() > 65535) throw new IllegalArgumentException();
                while (text.endsWith("/")) text = text.substring(0, text.length() - 1);
                return text;
            } catch (RuntimeException failure) { throw new IllegalArgumentException("请输入有效的 MiniMax API 地址，不含认证、查询参数或片段"); }
        }
        if (MINIMAX_VOICE_ID.equals(key)) return validateMiniMaxVoiceId(text, true);
        if (MINIMAX_MODEL.equals(key)) {
            if (text.isEmpty() || text.length() > 256 || containsControl(text)) {
                throw new IllegalArgumentException(MINIMAX_MODEL.equals(key) ? "请输入有效的 MiniMax 模型" : "请输入有效的 MiniMax 音色 ID");
            }
            return text;
        }
        if (MINIMAX_VOICE_NAME.equals(key)) {
            if (text.length() > 512 || containsControl(text)) throw new IllegalArgumentException("MiniMax 音色名称无效");
            return text;
        }
        if (MINIMAX_API_KEY.equals(key)) {
            if (containsControl(text)) throw new IllegalArgumentException("MiniMax API Key 格式无效");
            return text;
        }
        if (MINIMAX_PITCH.equals(key)) {
            try {
                int pitch = Integer.parseInt(text);
                if (pitch >= -12 && pitch <= 12) return Integer.toString(pitch);
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException("请输入 -12 至 12 的整数音调");
        }
        if (MINIMAX_SPEED.equals(key) || MINIMAX_VOLUME.equals(key)) {
            boolean speed = MINIMAX_SPEED.equals(key);
            try {
                double parsed = Double.parseDouble(text);
                if (Double.isFinite(parsed) && (speed ? parsed >= 0.5 && parsed <= 2 : parsed > 0 && parsed <= 10)
                        && (float) parsed > 0) return factorText((float) parsed);
            } catch (NumberFormatException ignored) { }
            throw new IllegalArgumentException(speed ? "请输入 0.5 至 2 的语速" : "请输入大于 0 且不超过 10 的音量");
        }
        return text;
    }

    /** Mixed-voice requests keep the provider's required voice_id key empty without erasing the saved single voice. */
    public static String validateMiniMaxVoiceId(String value, boolean required) {
        String voice = value == null ? "" : value.trim();
        if (required && voice.isEmpty() || voice.length() > 256 || containsControl(value == null ? "" : value)) {
            throw new IllegalArgumentException("请输入有效的 MiniMax 音色 ID");
        }
        return voice;
    }

    private static boolean containsControl(String value) {
        for (int i = 0; i < value.length(); i++) if (Character.isISOControl(value.charAt(i))) return true;
        return false;
    }

    /** New edits must be valid; existing preferences retain their previous fallback/clamping behavior. */
    public static String validateLocalRateOrPitch(String value) {
        double parsed;
        try { parsed = Double.parseDouble(value == null ? "" : value.trim()); }
        catch (NumberFormatException invalid) { throw new IllegalArgumentException("请输入 0.5 至 2 的数字"); }
        if (!Double.isFinite(parsed) || parsed < MIN_LOCAL_FACTOR || parsed > MAX_LOCAL_FACTOR) {
            throw new IllegalArgumentException("请输入 0.5 至 2 的数字");
        }
        return factorText((float) parsed);
    }

    private static float localFactor(LocalOptions options, String key) {
        return Math.max(MIN_LOCAL_FACTOR, Math.min(MAX_LOCAL_FACTOR, options.decimal(key, DEFAULT_LOCAL_FACTOR)));
    }

    private static String factorText(float value) {
        String text = Float.toString(value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }
}
