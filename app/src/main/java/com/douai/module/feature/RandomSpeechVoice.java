package com.douai.module.feature;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;
import com.douai.module.diagnostics.DouAiLog;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Chooses one provider and one cached voice for an individual synthesis request. */
public final class RandomSpeechVoice {
    private RandomSpeechVoice() { }

    /** Read-only eligibility result used by settings surfaces to explain random-source status. */
    public static final class Assessment {
        public final boolean eligible;
        public final int validVoiceCount;
        public final String reason;
        public final String reasonLabel;

        private Assessment(boolean eligible, int validVoiceCount, String reason, String reasonLabel) {
            this.eligible = eligible;
            this.validVoiceCount = validVoiceCount;
            this.reason = reason;
            this.reasonLabel = reasonLabel;
        }
    }

    /**
     * Assesses one configured source without selecting a voice, writing settings, logging, or doing I/O.
     * The catalog sample and query marker are read from the supplied immutable options snapshot.
     */
    public static Assessment assess(LocalOptions options, String source) {
        if (options == null || source == null || !SpeechSettings.PROVIDERS.contains(source)) {
            return assessment(false, 0, "invalid_config_or_error");
        }
        if (SpeechSettings.LOCAL.equals(source)) {
            return new Assessment(true, -1, "deferred", "离线音色由系统引擎决定");
        }
        try {
            SpeechSettings settings = new SpeechSettings(options.withOverrides(Map.of(SpeechSettings.PROVIDER, source)));
            if (incompatible(settings)) return assessment(false, 0, "incompatible_mode");
            Catalog catalog = voices(options, settings);
            return catalog.voices.isEmpty()
                    ? assessment(false, 0, catalog.reason)
                    : new Assessment(true, catalog.voices.size(), "eligible", "可参与大随机");
        } catch (RuntimeException invalid) {
            return assessment(false, 0, "invalid_config_or_error");
        }
    }

    private static Assessment assessment(boolean eligible, int count, String reason) {
        return new Assessment(eligible, count, reason, reasonLabel(reason));
    }

    private static String reasonLabel(String reason) {
        if ("missing_key".equals(reason)) return "缺少 API Key";
        if ("stale_or_missing_catalog".equals(reason)) return "缓存缺失或筛选条件已变化";
        if ("invalid_or_empty_catalog".equals(reason) || "no_valid_voices".equals(reason)) return "无有效音色";
        if ("invalid_config".equals(reason)) return "配置无效";
        if ("incompatible_mode".equals(reason)) return "当前模式不支持随机单音色";
        if ("eligible".equals(reason)) return "可参与大随机";
        return "配置或目录无效";
    }

    public static LocalOptions select(LocalOptions options, Random random) {
        return select(options, random, !options.text("tts_engine", "").trim().isEmpty());
    }

    static LocalOptions select(LocalOptions options, Random random, boolean systemAvailable) {
        SpeechSettings current = new SpeechSettings(options);
        if (SpeechSettings.RANDOM_OFF.equals(current.randomMode)) return options;
        String currentSource = source(current);
        DouAiLog.event("speech_synthesis", "random_voice_scope", "selection_started",
                "mode=" + current.randomMode + "; sources=" + (SpeechSettings.RANDOM_CURRENT.equals(current.randomMode)
                        ? currentSource : String.join(",", current.randomSources)));
        if (SpeechSettings.RANDOM_CURRENT.equals(current.randomMode)) {
            if (current.local) {
                candidate(currentSource, "deferred", -1);
                selectedEvent(currentSource, null);
                return options;
            }
            if (incompatible(current)) {
                candidate(currentSource, "incompatible_mode", 0);
                noCandidates();
                throw new IllegalArgumentException("当前语音来源使用混音或多说话人模式，请先切换单音色模式");
            }
            Catalog catalog = voices(options, current);
            List<VoiceCatalog.Entry> voices = catalog.voices;
            candidate(currentSource, catalog.reason, voices.size());
            if (voices.isEmpty()) {
                noCandidates();
                throw new IllegalArgumentException("当前语音来源没有可用音色，请检查配置并刷新音色列表");
            }
            VoiceCatalog.Entry voice = voices.get(random.nextInt(voices.size()));
            selectedEvent(currentSource, voice);
            return selected(options, currentSource, voice);
        }
        List<String> available = new ArrayList<>();
        List<List<VoiceCatalog.Entry>> catalogs = new ArrayList<>();
        for (String source : current.randomSources) {
            if (SpeechSettings.LOCAL.equals(source)) {
                candidate(source, systemAvailable ? "eligible" : "unavailable_or_no_offline", -1);
                if (systemAvailable) { available.add(source); catalogs.add(null); }
                continue;
            }
            SpeechSettings candidate = new SpeechSettings(options.withOverrides(Map.of(SpeechSettings.PROVIDER, source)));
            if (incompatible(candidate)) { candidate(source, "incompatible_mode", 0); continue; }
            Catalog catalog = voices(options, candidate);
            List<VoiceCatalog.Entry> voices = catalog.voices;
            candidate(source, catalog.reason, voices.size());
            if (!voices.isEmpty()) { available.add(source); catalogs.add(voices); }
        }
        if (available.isEmpty()) {
            noCandidates();
            throw new IllegalArgumentException("所选来源范围内没有可用的随机音色，请检查来源范围及配置并刷新音色列表");
        }
        int sourceIndex = random.nextInt(available.size());
        String source = available.get(sourceIndex);
        if (SpeechSettings.LOCAL.equals(source)) {
            selectedEvent(source, null);
            return options.withOverrides(Map.of(SpeechSettings.PROVIDER, SpeechSettings.LOCAL,
                    SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT));
        }
        List<VoiceCatalog.Entry> voices = catalogs.get(sourceIndex);
        VoiceCatalog.Entry voice = voices.get(random.nextInt(voices.size()));
        selectedEvent(source, voice);
        return selected(options, source, voice);
    }

    private static void candidate(String source, String reason, int count) {
        DouAiLog.event("speech_synthesis", "random_voice_candidate_" + source, reason,
                "source=" + source + "; voices=" + (count < 0 ? "unknown" : count));
    }

    private static void selectedEvent(String source, VoiceCatalog.Entry voice) {
        DouAiLog.event("speech_synthesis", "random_voice_selected", "selection_complete",
                "source=" + source + "; voice_ref=" + (voice == null ? "deferred" : DouAiLog.reference(voice.id)));
        if (voice != null) logVoiceId(source, voice.id);
    }

    static void logVoiceId(String source, String id) {
        String lower = id == null ? "" : id.toLowerCase(java.util.Locale.ROOT);
        boolean safe = id != null && id.matches("[A-Za-z0-9._-]{1,256}")
                && !lower.startsWith("sk-") && !lower.startsWith("sk_")
                && !lower.contains("token") && !lower.contains("secret") && !lower.contains("password")
                && !lower.contains("passwd") && !lower.contains("apikey") && !lower.contains("api_key")
                && !lower.contains("api-key") && !lower.contains("authorization") && !lower.contains("bearer")
                && !lower.contains("cookie") && !lower.contains("privatekey") && !lower.contains("private_key")
                && !lower.contains("private-key");
        if (!safe) {
            DouAiLog.event("speech_synthesis", "random_voice_identifier", "identifier_omitted",
                    "source=" + source + "; voice_id=omitted_unsafe_or_too_long");
            return;
        }
        if (id.length() <= 96) {
            DouAiLog.event("speech_synthesis", "random_voice_identifier", "identifier_recorded",
                    "voice_id=" + id + "; source=" + source);
            return;
        }
        int partCount = (id.length() + 63) / 64;
        for (int index = 0; index < partCount; index++) {
            int start = index * 64;
            int end = Math.min(start + 64, id.length());
            DouAiLog.event("speech_synthesis", "random_voice_identifier_part_" + (index + 1), "identifier_recorded",
                    "voice_id_part=" + id.substring(start, end) + ";part_index=" + (index + 1)
                            + ";part_count=" + partCount + ";source=" + source);
        }
    }

    private static void noCandidates() {
        DouAiLog.event("speech_synthesis", "random_voice_no_candidates", "no_candidates", "eligible_sources=0");
    }

    private static final class Catalog {
        final List<VoiceCatalog.Entry> voices;
        final String reason;
        Catalog(List<VoiceCatalog.Entry> voices, String reason) { this.voices = voices; this.reason = reason; }
    }

    private static Catalog empty(String reason) { return new Catalog(List.of(), reason); }

    private static String source(SpeechSettings settings) {
        return settings.fish ? SpeechSettings.FISH : settings.breeze ? SpeechSettings.BREEZE
                : settings.minimax ? SpeechSettings.MINIMAX : settings.thirdParty ? SpeechSettings.THIRD_PARTY
                : SpeechSettings.LOCAL;
    }

    private static boolean incompatible(SpeechSettings settings) {
        return settings.minimax && settings.minimaxOptions.hasMixedVoices()
                || settings.fish && !"single".equals(settings.fishSettings.voiceMode);
    }

    private static Catalog voices(LocalOptions options, SpeechSettings settings) {
        try {
            if (settings.thirdParty) {
                if (!settings.getTemplate.contains("{voice}")) return empty("invalid_config");
                ThirdPartyRequestTemplate.parse(settings.getTemplate, "{text}", "{voice}");
                ThirdPartyRequestTemplate.parse(settings.voiceGetTemplate);
                if (!SpeechHttpClient.validHeaders(settings.headers)
                        || !SpeechHttpClient.validHeaders(settings.voiceHeaders)) return empty("invalid_config");
            } else if (settings.minimax) {
                if (settings.minimaxApiKey.isEmpty()) return empty("missing_key");
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_API_KEY, settings.minimaxApiKey);
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_BASE_URL, settings.minimaxBaseUrl);
            } else if (settings.breeze) {
                settings.breezeSettings.validateCatalog();
                if (settings.breezeSettings.apiKey.isEmpty()) return empty("missing_key");
            } else if (settings.fish) {
                settings.fishSettings.validateCatalog();
                if (settings.fishSettings.apiKey.isEmpty()) return empty("missing_key");
            }
            String marker = VoiceCatalogLabels.queryStorageKey(settings);
            if (!VoiceCatalogLabels.queryKey(settings).equals(options.text(marker, ""))) return empty("stale_or_missing_catalog");
            String sample = options.text(settings.fish ? FishSpeechSettings.VOICE_SAMPLE
                    : settings.breeze ? BreezeSpeechSettings.VOICE_SAMPLE
                    : settings.minimax ? SpeechSettings.MINIMAX_VOICE_SAMPLE : SpeechSettings.VOICE_SAMPLE, "");
            List<VoiceCatalog.Entry> parsed;
            try {
                parsed = settings.thirdParty ? VoiceCatalog.parse(sample, settings)
                        : VoiceCatalog.parse(sample, "", "", "");
            } catch (RuntimeException invalidCatalog) {
                return empty("invalid_or_empty_catalog");
            }
            List<VoiceCatalog.Entry> valid = new ArrayList<>();
            for (VoiceCatalog.Entry entry : parsed) {
                try {
                    if (settings.minimax) SpeechSettings.validateMiniMaxVoiceId(entry.id, true);
                    if (settings.breeze) BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, entry.id);
                    if (settings.fish) FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, entry.id);
                    valid.add(entry);
                } catch (IllegalArgumentException ignored) { }
            }
            if (!valid.isEmpty()) {
                SpeechSettings chosen = new SpeechSettings(selected(options, source(settings), valid.get(0)));
                if (settings.minimax) chosen.validateMiniMax();
                else if (settings.breeze) chosen.validateBreeze();
                else if (settings.fish) chosen.validateFish();
            }
            return new Catalog(valid, valid.isEmpty() ? "no_valid_voices" : "eligible");
        } catch (RuntimeException invalid) { return empty("invalid_config_or_error"); }
    }

    private static LocalOptions selected(LocalOptions options, String source, VoiceCatalog.Entry voice) {
        String id = source.equals(SpeechSettings.FISH) ? FishSpeechSettings.REFERENCE_ID
                : source.equals(SpeechSettings.BREEZE) ? BreezeSpeechSettings.VOICE_ID
                : source.equals(SpeechSettings.MINIMAX) ? SpeechSettings.MINIMAX_VOICE_ID : SpeechSettings.SELECTED_VOICE;
        String name = source.equals(SpeechSettings.FISH) ? FishSpeechSettings.REFERENCE_NAME
                : source.equals(SpeechSettings.BREEZE) ? BreezeSpeechSettings.VOICE_NAME
                : source.equals(SpeechSettings.MINIMAX) ? SpeechSettings.MINIMAX_VOICE_NAME : SpeechSettings.SELECTED_VOICE_NAME;
        return options.withOverrides(Map.of(SpeechSettings.PROVIDER, source, SpeechSettings.RANDOM_MODE,
                SpeechSettings.RANDOM_OFF, id, voice.id, name, voice.name));
    }
}
