package com.douai.module.feature;

import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/** Bounded provider metadata and device-local markers for the exact catalog query. */
public final class VoiceCatalogLabels {
    public static final String FISH_QUERY_KEY = "voice_catalog_fish_query_v1";
    public static final String BREEZE_QUERY_KEY = "voice_catalog_breeze_query_v1";
    private static final int MAX_METADATA_ITEMS = 64;
    private static final String[] LANGUAGES = {
            "af", "南非荷兰语", "ar", "阿拉伯语", "bg", "保加利亚语", "bn", "孟加拉语",
            "bs", "波斯尼亚语", "ca", "加泰罗尼亚语", "cs", "捷克语", "da", "丹麦语",
            "de", "德语", "el", "希腊语", "en", "英语", "es", "西班牙语", "et", "爱沙尼亚语",
            "eu", "巴斯克语", "fa", "波斯语", "fi", "芬兰语", "fr", "法语", "gl", "加利西亚语",
            "he", "希伯来语", "hi", "印地语", "hu", "匈牙利语", "id", "印尼语", "is", "冰岛语",
            "it", "意大利语", "ja", "日语", "kn", "卡纳达语", "ko", "韩语", "lt", "立陶宛语",
            "mr", "马拉地语", "ms", "马来语", "ne", "尼泊尔语", "nl", "荷兰语", "no", "挪威语",
            "pl", "波兰语", "pt", "葡萄牙语", "ro", "罗马尼亚语", "ru", "俄语", "sk", "斯洛伐克语",
            "sl", "斯洛文尼亚语", "sq", "阿尔巴尼亚语", "sv", "瑞典语", "sw", "斯瓦希里语",
            "ta", "泰米尔语", "te", "泰卢固语", "th", "泰语", "tl", "他加禄语", "tr", "土耳其语",
            "uk", "乌克兰语", "ur", "乌尔都语", "vi", "越南语", "zh", "中文",
            "zh-cn", "中文（中国）", "zh-tw", "中文（台湾）", "zh-hk", "中文（香港）",
            "zh-hans", "简体中文", "zh-hant", "繁体中文"};
    private static final String[] GENDERS = {"male", "男声", "female", "女声", "neutral", "中性"};
    private static final String[] AGES = {"child", "儿童", "young", "青年", "middle_aged", "中年", "old", "老年"};
    private static final String[] TONES = {
            "warm", "温暖", "calm", "平静", "bright", "明亮", "gentle", "温柔", "energetic", "活力",
            "authoritative", "权威", "sincere", "真诚", "weary", "疲惫", "precise", "精准", "refined", "文雅",
            "urgent", "急切", "friendly", "友好", "articulate", "清晰", "steady", "沉稳", "playful", "俏皮",
            "compassionate", "关怀", "reflective", "沉思", "measured", "从容", "rhythmic", "有节奏",
            "passionate", "热情"};
    private static final String[] ACCENTS = {
            "american", "美式英语", "british", "英式英语", "scottish", "苏格兰", "irish", "爱尔兰",
            "australian", "澳大利亚", "canadian", "加拿大", "us_southern", "美国南部", "us_new_york", "纽约",
            "indian", "印度", "south_african", "南非", "russian", "俄式", "japanese", "日式",
            "korean", "韩式", "chinese", "中式", "mandarin_northeastern", "东北普通话",
            "mandarin_shaanxi", "陕西普通话", "mandarin_shanghai", "上海普通话", "mandarin_sichuan", "四川普通话",
            "mandarin_henan", "河南普通话", "mandarin_beijing", "北京普通话", "mandarin_taiwan", "台湾普通话",
            "cantonese", "粤语", "mandarin_guangdong", "广东普通话", "mandarin_yunnan", "云南普通话"};

    private VoiceCatalogLabels() { }

    /** Breeze VoiceResponse uses scalar language/gender/age/accent, tone[] and free tags[]. */
    public static List<String> breeze(JSONObject voice) {
        if (voice == null) return Collections.emptyList();
        try {
            List<String> labels = new ArrayList<>();
            code(labels, voice.opt("language_code"), "语言", LANGUAGES, true);
            code(labels, voice.opt("gender"), "性别", GENDERS, false);
            code(labels, voice.opt("age"), "年龄", AGES, false);
            codes(labels, voice.opt("tone"), "语气", TONES, false);
            code(labels, voice.opt("accent"), "口音", ACCENTS, false);
            tags(labels, voice.opt("tags"));
            return VoiceCatalog.cleanLabels(labels);
        } catch (Exception ignored) { return Collections.emptyList(); }
    }

    /** Fish ModelEntity publishes languages[] and free tags[], not Breeze's demographic fields. */
    public static List<String> fish(JSONObject model) {
        if (model == null) return Collections.emptyList();
        try {
            List<String> labels = new ArrayList<>();
            codes(labels, model.opt("languages"), "语言", LANGUAGES, true);
            tags(labels, model.opt("tags"));
            return VoiceCatalog.cleanLabels(labels);
        } catch (Exception ignored) { return Collections.emptyList(); }
    }

    public static JSONObject cached(VoiceCatalog.Entry entry) {
        if (entry == null) throw new IllegalArgumentException("音色条目无效");
        try {
            return new JSONObject().put("id", entry.id).put("name", entry.name)
                    .put("labels", new JSONArray(entry.labels));
        } catch (Exception ignored) { throw new IllegalArgumentException("音色条目无法缓存"); }
    }

    public static String queryStorageKey(SpeechSettings settings) {
        if (settings == null) return "";
        return settings.fish ? FISH_QUERY_KEY : settings.breeze ? BREEZE_QUERY_KEY : "";
    }

    /** Length-prefixed fields include credentials only inside SHA-256, never in the stored marker. */
    public static String queryKey(SpeechSettings settings) {
        if (settings == null || !settings.fish && !settings.breeze) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            part(digest, "voice-catalog-query-v1");
            if (settings.fish) {
                part(digest, SpeechSettings.FISH);
                part(digest, settings.fishSettings.baseUrl);
                part(digest, settings.fishSettings.apiKey);
                part(digest, settings.fishSettings.voiceScope);
                part(digest, settings.fishSettings.voiceSearch);
                parts(digest, settings.fishSettings.voiceLanguages);
                parts(digest, settings.fishSettings.voiceTitleLanguages);
                part(digest, settings.fishSettings.voiceAuthorId);
                part(digest, settings.fishSettings.voiceLicensed);
                part(digest, settings.fishSettings.voiceSort);
                parts(digest, settings.fishSettings.voiceTags);
            } else {
                part(digest, SpeechSettings.BREEZE);
                part(digest, settings.breezeSettings.baseUrl);
                part(digest, settings.breezeSettings.apiKey);
                part(digest, settings.breezeSettings.voiceType);
                part(digest, settings.breezeSettings.voiceSearch);
                part(digest, settings.breezeSettings.voiceLanguage);
                parts(digest, settings.breezeSettings.voiceGenders);
                parts(digest, settings.breezeSettings.voiceAges);
                parts(digest, settings.breezeSettings.voiceTones);
                part(digest, settings.breezeSettings.voiceCategory);
                part(digest, settings.breezeSettings.voiceAccent);
                part(digest, settings.breezeSettings.voiceAccentMode);
                part(digest, settings.breezeSettings.voiceOrigin);
                part(digest, settings.breezeSettings.voiceSort);
                part(digest, settings.breezeSettings.voiceSortDirection);
                part(digest, Boolean.toString(settings.breezeSettings.voiceFavoritesOnly));
                parts(digest, settings.breezeSettings.voiceTags);
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", value & 255));
            return hex.toString();
        } catch (Exception ignored) { throw new IllegalStateException("音色目录查询标记无法生成"); }
    }

    private static void parts(MessageDigest digest, List<String> values) {
        int size = values == null ? 0 : values.size();
        digest.update(ByteBuffer.allocate(4).putInt(size).array());
        if (values != null) for (String value : values) part(digest, value);
    }

    private static void part(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(4).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void codes(List<String> labels, Object raw, String field, String[] names, boolean language) {
        if (!(raw instanceof JSONArray)) return;
        JSONArray values = (JSONArray) raw;
        for (int i = 0; i < Math.min(values.length(), MAX_METADATA_ITEMS); i++) {
            code(labels, values.opt(i), field, names, language);
        }
    }

    private static void code(List<String> labels, Object raw, String field, String[] names, boolean language) {
        if (!(raw instanceof String)) return;
        String original = (String) raw;
        // Validate before trimming so embedded controls never become display text.
        if (VoiceCatalog.cleanLabels(Collections.singletonList(original)).isEmpty()) return;
        original = original.trim();
        String code = original.toLowerCase(Locale.ROOT);
        if (language) code = code.replace('_', '-');
        String translated = original;
        for (int i = 0; i < names.length; i += 2) {
            if (names[i].equals(code)) { translated = names[i + 1]; break; }
        }
        labels.add(field + "：" + translated);
    }

    private static void tags(List<String> labels, Object raw) {
        if (!(raw instanceof JSONArray)) return;
        JSONArray values = (JSONArray) raw;
        for (int i = 0; i < Math.min(values.length(), MAX_METADATA_ITEMS); i++) {
            Object value = values.opt(i);
            if (value instanceof String) labels.add((String) value);
        }
    }
}
