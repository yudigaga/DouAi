package com.douai.module.feature;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;
import com.douai.module.config.ThirdPartyRequestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

/** Chooses one provider and one cached voice for an individual synthesis request. */
public final class RandomSpeechVoice {
    private static final String[] SOURCES = {SpeechSettings.LOCAL, SpeechSettings.THIRD_PARTY,
            SpeechSettings.MINIMAX, SpeechSettings.BREEZE, SpeechSettings.FISH};

    private RandomSpeechVoice() { }

    public static LocalOptions select(LocalOptions options, Random random) {
        return select(options, random, !options.text("tts_engine", "").trim().isEmpty());
    }

    static LocalOptions select(LocalOptions options, Random random, boolean systemAvailable) {
        SpeechSettings current = new SpeechSettings(options);
        if (SpeechSettings.RANDOM_OFF.equals(current.randomMode)) return options;
        if (SpeechSettings.RANDOM_CURRENT.equals(current.randomMode)) {
            if (current.local) return options;
            if (incompatible(current)) throw new IllegalArgumentException("当前语音来源使用混音或多说话人模式，请先切换单音色模式");
            List<VoiceCatalog.Entry> voices = voices(options, current);
            if (voices.isEmpty()) throw new IllegalArgumentException("当前语音来源没有可用音色，请检查配置并刷新音色列表");
            return selected(options, source(current), voices.get(random.nextInt(voices.size())));
        }
        List<String> available = new ArrayList<>();
        List<List<VoiceCatalog.Entry>> catalogs = new ArrayList<>();
        for (String source : SOURCES) {
            if (SpeechSettings.LOCAL.equals(source)) {
                if (systemAvailable) { available.add(source); catalogs.add(null); }
                continue;
            }
            SpeechSettings candidate = new SpeechSettings(options.withOverrides(Map.of(SpeechSettings.PROVIDER, source)));
            if (incompatible(candidate)) continue;
            List<VoiceCatalog.Entry> voices = voices(options, candidate);
            if (!voices.isEmpty()) { available.add(source); catalogs.add(voices); }
        }
        if (available.isEmpty()) throw new IllegalArgumentException("没有可用的随机音色来源，请配置语音来源并刷新音色列表");
        int sourceIndex = random.nextInt(available.size());
        String source = available.get(sourceIndex);
        if (SpeechSettings.LOCAL.equals(source)) {
            return options.withOverrides(Map.of(SpeechSettings.PROVIDER, SpeechSettings.LOCAL,
                    SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT));
        }
        List<VoiceCatalog.Entry> voices = catalogs.get(sourceIndex);
        return selected(options, source, voices.get(random.nextInt(voices.size())));
    }

    private static String source(SpeechSettings settings) {
        return settings.fish ? SpeechSettings.FISH : settings.breeze ? SpeechSettings.BREEZE
                : settings.minimax ? SpeechSettings.MINIMAX : settings.thirdParty ? SpeechSettings.THIRD_PARTY
                : SpeechSettings.LOCAL;
    }

    private static boolean incompatible(SpeechSettings settings) {
        return settings.minimax && settings.minimaxOptions.hasMixedVoices()
                || settings.fish && !"single".equals(settings.fishSettings.voiceMode);
    }

    private static List<VoiceCatalog.Entry> voices(LocalOptions options, SpeechSettings settings) {
        try {
            if (settings.thirdParty) {
                if (!settings.getTemplate.contains("{voice}")) return List.of();
                ThirdPartyRequestTemplate.parse(settings.getTemplate, "{text}", "{voice}");
                ThirdPartyRequestTemplate.parse(settings.voiceGetTemplate);
                if (!SpeechHttpClient.validHeaders(settings.headers)
                        || !SpeechHttpClient.validHeaders(settings.voiceHeaders)) return List.of();
            } else if (settings.minimax) {
                if (settings.minimaxApiKey.isEmpty()) return List.of();
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_API_KEY, settings.minimaxApiKey);
                SpeechSettings.validateMiniMaxValue(SpeechSettings.MINIMAX_BASE_URL, settings.minimaxBaseUrl);
            } else if (settings.breeze) {
                settings.breezeSettings.validateCatalog();
                if (settings.breezeSettings.apiKey.isEmpty()) return List.of();
            } else if (settings.fish) {
                settings.fishSettings.validateCatalog();
                if (settings.fishSettings.apiKey.isEmpty()) return List.of();
            }
            String marker = VoiceCatalogLabels.queryStorageKey(settings);
            if (!VoiceCatalogLabels.queryKey(settings).equals(options.text(marker, ""))) return List.of();
            String sample = options.text(settings.fish ? FishSpeechSettings.VOICE_SAMPLE
                    : settings.breeze ? BreezeSpeechSettings.VOICE_SAMPLE
                    : settings.minimax ? SpeechSettings.MINIMAX_VOICE_SAMPLE : SpeechSettings.VOICE_SAMPLE, "");
            List<VoiceCatalog.Entry> parsed = settings.thirdParty ? VoiceCatalog.parse(sample, settings)
                    : VoiceCatalog.parse(sample, "", "", "");
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
            return valid;
        } catch (RuntimeException invalid) { return List.of(); }
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
