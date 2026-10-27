package com.douai.module.config;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class BreezeSpeechSettingsTest {
    @Test public void breezeHasItsOwnDefaultsAndDoesNotReuseOtherProviderSettings() {
        SpeechSettings speech = new SpeechSettings(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.BREEZE,
                SpeechSettings.MINIMAX_API_KEY, "minimax-private-fixture", SpeechSettings.MINIMAX_VOICE_ID, "mini-voice",
                SpeechSettings.MINIMAX_SPEED, "1.7", SpeechSettings.SELECTED_VOICE, "third-party-voice")));
        assertTrue(speech.breeze); assertFalse(speech.local); assertFalse(speech.minimax); assertFalse(speech.thirdParty);
        assertEquals("https://api.breeze.blue", speech.breezeSettings.baseUrl);
        assertEquals(BreezeSpeechSettings.AUTO_MODEL, speech.breezeSettings.model);
        assertEquals("sync", speech.breezeSettings.delivery);
        assertEquals("all", speech.breezeSettings.voiceType); assertEquals("", speech.breezeSettings.voiceSearch);
        assertEquals("zh", speech.breezeSettings.voiceLanguage);
        assertEquals("", speech.breezeSettings.voiceGender); assertEquals("", speech.breezeSettings.voiceTone);
        assertTrue(speech.breezeSettings.voiceTags.isEmpty()); assertTrue(speech.breezeSettings.hasCatalogFilter());
        assertEquals("", speech.breezeSettings.languageCode);
        assertEquals("mp3", speech.breezeSettings.outputFormat);
        assertEquals("", speech.breezeSettings.apiKey); assertEquals("", speech.breezeSettings.voiceId);
        assertEquals(1f, speech.breezeSettings.speed, 0); assertEquals(1f, speech.breezeSettings.volume, 0);
        assertEquals(1f, speech.breezeSettings.guidanceScale, 0); assertFalse(speech.breezeSettings.speakerBoost);
        assertEquals("minimax-private-fixture", speech.minimaxApiKey); assertEquals("mini-voice", speech.minimaxVoiceId);
        assertEquals(1.7f, speech.minimaxSpeed, 0); assertEquals("third-party-voice", speech.selectedVoice);
        assertEquals(SpeechSettings.BREEZE, SpeechSettings.validateProvider(SpeechSettings.BREEZE));
    }

    @Test public void anUnfinishedSetupCanBeSavedButSynthesisRequiresItsOwnKeyAndVoice() {
        new BreezeSpeechSettings(new LocalOptions(Map.of())).validateOptions();
        assertThrows(IllegalArgumentException.class,
                () -> new SpeechSettings(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.BREEZE))).validateBreeze());
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.API_KEY, "breeze-key")).validateForSynthesis());
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.VOICE_ID, "voc_fixture")).validateForSynthesis());
        settings(Map.of(BreezeSpeechSettings.API_KEY, "breeze-key", BreezeSpeechSettings.VOICE_ID, "voc_fixture")).validateForSynthesis();
    }

    @Test public void currentProviderRangesAreValidatedBeforeFallbackValuesCanBeUsed() {
        String[][] accepted = {{BreezeSpeechSettings.SPEED, "0.5", "0.5"}, {BreezeSpeechSettings.SPEED, "2.00", "2"},
                {BreezeSpeechSettings.VOLUME, "0.01", "0.01"}, {BreezeSpeechSettings.VOLUME, "2", "2"},
                {BreezeSpeechSettings.GUIDANCE_SCALE, "1", "1"}, {BreezeSpeechSettings.GUIDANCE_SCALE, "10.0", "10"},
                {BreezeSpeechSettings.SPEAKER_BOOST, "false", "false"}};
        for (String[] item : accepted) assertEquals(item[2], BreezeSpeechSettings.validateValue(item[0], item[1]));
        String[][] invalid = {{BreezeSpeechSettings.SPEED, "0.49"}, {BreezeSpeechSettings.SPEED, "2.01"},
                {BreezeSpeechSettings.VOLUME, "0"}, {BreezeSpeechSettings.VOLUME, "2.01"},
                {BreezeSpeechSettings.GUIDANCE_SCALE, "0.99"}, {BreezeSpeechSettings.GUIDANCE_SCALE, "10.01"},
                {BreezeSpeechSettings.SPEED, "NaN"}, {BreezeSpeechSettings.VOLUME, "Infinity"},
                {BreezeSpeechSettings.GUIDANCE_SCALE, ""}, {BreezeSpeechSettings.SPEAKER_BOOST, "yes"}};
        for (String[] item : invalid) {
            assertThrows(item[0] + ":" + item[1], IllegalArgumentException.class,
                    () -> BreezeSpeechSettings.validateValue(item[0], item[1]));
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(item[0], item[1])).validateOptions());
        }
    }

    @Test public void endpointsAndOpaqueRequestValuesRejectCredentialInjectionAndControls() {
        assertEquals("https://api.breeze.blue", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.BASE_URL, " https://api.breeze.blue/ "));
        for (String invalid : new String[]{"", "bad-url", "ftp://example.test", "https://user:key@example.test",
                "https://example.test?key=private", "https://example.test#private", "https://example.test:0", "https://example.test:65536"}) {
            assertThrows(invalid, IllegalArgumentException.class,
                    () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.BASE_URL, invalid));
        }
        assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.API_KEY, "fixture\r\nInjected: key"));
        assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, "voice\nfixture"));
        assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, "m".repeat(121)));
    }

    @Test public void instructionsRemainMultilineAndKnownModelsKeepExplicitLanguageCompatibility() {
        String instructions = "轻声朗读。\n" + "保持自然的节奏。".repeat(700);
        assertEquals(instructions, BreezeSpeechSettings.validateValue(BreezeSpeechSettings.INSTRUCTIONS, instructions));
        assertEquals(instructions, settings(Map.of(BreezeSpeechSettings.INSTRUCTIONS, instructions)).instructions);
        assertEquals("zh", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.LANGUAGE_CODE, "ZH"));
        assertEquals("", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.LANGUAGE_CODE, ""));
        for (String invalid : new String[]{"zh-CN", "english", "xx", "z"}) assertThrows(invalid,
                IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.LANGUAGE_CODE, invalid));
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.MODEL, "breeze-tts-2",
                BreezeSpeechSettings.LANGUAGE_CODE, "ja")).validateOptions());
        settings(Map.of(BreezeSpeechSettings.MODEL, "breeze-tts-2-multilingual", BreezeSpeechSettings.LANGUAGE_CODE, "ja")).validateOptions();
        // Supported request combinations are retained; the provider decides which boost takes effect.
        settings(Map.of(BreezeSpeechSettings.INSTRUCTIONS, "Read warmly", BreezeSpeechSettings.GUIDANCE_SCALE, "5",
                BreezeSpeechSettings.SPEAKER_BOOST, "true")).validateOptions();
    }

    @Test public void encodedProfilesUseDocumentedCodecRateAndBitrateCombinations() {
        for (String profile : new String[]{"mp3", "wav", "flac", "aac", "opus", "wav_48000", "flac_11025",
                "mp3_22050_32", "mp3_24000_144", "mp3_44100_192", "aac_48000_137", "opus_48000_67"}) {
            assertEquals(profile, BreezeSpeechSettings.validateOutputFormat(profile));
            settings(Map.of(BreezeSpeechSettings.OUTPUT_FORMAT, profile)).validateOptions();
        }
        assertEquals("aac_44100_137", BreezeSpeechSettings.validateOutputFormat(" AAC_044100_0137 "));
        assertEquals("aac_44100_137", settings(Map.of(BreezeSpeechSettings.OUTPUT_FORMAT, "AAC_044100_0137")).outputFormat);
        for (String profile : new String[]{"mp3_44100_144", "mp3_24000_192", "opus_44100_64", "aac_48000_7",
                "aac_48000_321", "opus_48000_5", "opus_48000_511", "wav_48000_128", "flac_24000_64",
                "mp3_96000_128", "mp3_44100_128_extra", "mp3_", "mp3__128", "url", "hex", "mp3?x=key"}) {
            assertThrows(profile, IllegalArgumentException.class, () -> BreezeSpeechSettings.validateOutputFormat(profile));
        }
        for (String raw : new String[]{"pcm", "pcm_16000", "PCM_24000"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.OUTPUT_FORMAT, raw));
            assertEquals("当前发送入口不支持裸 PCM，请选择 WAV", failure.getMessage());
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.OUTPUT_FORMAT, raw)).validateOptions());
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.OUTPUT_FORMAT, raw,
                    BreezeSpeechSettings.API_KEY, "key", BreezeSpeechSettings.VOICE_ID, "voc_fixture")).validateForSynthesis());
        }
    }

    @Test public void candidateChecksDoNotMutateExistingSettingsOrInventUnsupportedControls() {
        Map<String, Object> saved = new LinkedHashMap<>();
        saved.put(BreezeSpeechSettings.LANGUAGE_CODE, "ja");
        saved.put(BreezeSpeechSettings.MODEL, "breeze-tts-2-multilingual");
        assertFalse(BreezeSpeechSettings.candidateIssue(saved, BreezeSpeechSettings.MODEL, "breeze-tts-2").isEmpty());
        assertEquals("breeze-tts-2-multilingual", saved.get(BreezeSpeechSettings.MODEL));
        assertEquals("", BreezeSpeechSettings.candidateIssue(saved, BreezeSpeechSettings.SPEED, "2"));
        assertFalse(saved.containsKey(BreezeSpeechSettings.SPEED));
        for (String unsupported : new String[]{"tts_breeze_stability", "tts_breeze_similarity_boost", "tts_breeze_style",
                "tts_breeze_pitch", "tts_breeze_timbre_weights"}) assertFalse(BreezeSpeechSettings.isKey(unsupported));
    }

    @Test public void automaticModelAndDeliveryChoicesKeepNativeDefaultsAndValidateTheWholeCombination() {
        assertEquals(BreezeSpeechSettings.AUTO_MODEL, BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, " "));
        BreezeSpeechSettings automatic = settings(Map.of(BreezeSpeechSettings.MODEL, "", BreezeSpeechSettings.LANGUAGE_CODE, "ja"));
        automatic.validateOptions(); assertEquals(BreezeSpeechSettings.AUTO_MODEL, automatic.model);
        for (String delivery : BreezeSpeechSettings.DELIVERIES) {
            settings(Map.of(BreezeSpeechSettings.DELIVERY, delivery, BreezeSpeechSettings.OUTPUT_FORMAT, "mp3_44100_128")).validateOptions();
        }
        settings(Map.of(BreezeSpeechSettings.DELIVERY, "stream", BreezeSpeechSettings.OUTPUT_FORMAT, "wav_48000")).validateOptions();
        for (String format : new String[]{"flac", "aac_24000_128", "opus_48000_64"}) {
            Map<String, Object> saved = new LinkedHashMap<>();
            saved.put(BreezeSpeechSettings.DELIVERY, "sync"); saved.put(BreezeSpeechSettings.OUTPUT_FORMAT, format);
            assertFalse(BreezeSpeechSettings.candidateIssue(saved, BreezeSpeechSettings.DELIVERY, "stream").isEmpty());
            assertEquals("sync", saved.get(BreezeSpeechSettings.DELIVERY));
            settings(saved).validateOptions();
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(BreezeSpeechSettings.DELIVERY, "stream",
                    BreezeSpeechSettings.OUTPUT_FORMAT, format)).validateOptions());
        }
        assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.DELIVERY, "auto"));
    }

    @Test public void expressivePrecedenceReportsTheEffectiveCombinationWithoutChangingSavedChoices() {
        BreezeSpeechSettings boost = settings(Map.of(BreezeSpeechSettings.GUIDANCE_SCALE, "7", BreezeSpeechSettings.SPEAKER_BOOST, "true"));
        assertEquals(7, boost.guidanceScale, 0); assertTrue(boost.speakerBoost);
        assertTrue(boost.effectiveSpeakerBoost()); assertEquals(1, boost.effectiveGuidanceScale(), 0);
        BreezeSpeechSettings instruction = settings(Map.of(BreezeSpeechSettings.GUIDANCE_SCALE, "7", BreezeSpeechSettings.SPEAKER_BOOST, "true",
                BreezeSpeechSettings.INSTRUCTIONS, "Read calmly"));
        assertTrue(instruction.speakerBoost); assertFalse(instruction.effectiveSpeakerBoost());
        assertEquals(7, instruction.effectiveGuidanceScale(), 0); assertEquals("Read calmly", instruction.instructions);
        BreezeSpeechSettings unboosted = settings(Map.of(BreezeSpeechSettings.GUIDANCE_SCALE, "4"));
        assertFalse(unboosted.effectiveSpeakerBoost()); assertEquals(4, unboosted.effectiveGuidanceScale(), 0);
    }

    @Test public void voiceCatalogFiltersUseBreezeScopesAndDoNotConstrainSynthesisLanguage() {
        for (String scope : BreezeSpeechSettings.VOICE_TYPES) assertEquals(scope,
                BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TYPE, scope));
        for (String invalid : new String[]{"self", "public", "custom", ""}) assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TYPE, invalid));
        assertEquals("ja", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_LANGUAGE, "JA"));
        assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_LANGUAGE, "ja-JP"));
        assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_SEARCH, "bad\nsearch"));
        BreezeSpeechSettings settings = settings(Map.of(BreezeSpeechSettings.MODEL, "breeze-tts-2",
                BreezeSpeechSettings.LANGUAGE_CODE, "zh", BreezeSpeechSettings.VOICE_TYPE, "personal",
                BreezeSpeechSettings.VOICE_SEARCH, "  温柔 女声  ", BreezeSpeechSettings.VOICE_LANGUAGE, "ja"));
        settings.validateOptions();
        assertEquals("personal", settings.voiceType); assertEquals("温柔 女声", settings.voiceSearch);
        assertEquals("ja", settings.voiceLanguage); assertEquals("zh", settings.languageCode);
    }

    @Test public void catalogGenderToneAndTagsUseOnlyTheBreezeDocumentedCodes() {
        BreezeSpeechSettings filtered = settings(Map.of(BreezeSpeechSettings.VOICE_LANGUAGE, "ja", BreezeSpeechSettings.VOICE_GENDER, "female",
                BreezeSpeechSettings.VOICE_TONE, "warm", BreezeSpeechSettings.VOICE_TAGS, "[\" 温柔 \",\"Warm\",\"Warm\"]"));
        filtered.validateCatalog(); assertEquals("female", filtered.voiceGender); assertEquals("warm", filtered.voiceTone);
        assertEquals(java.util.List.of("温柔", "Warm"), filtered.voiceTags); assertEquals("", filtered.languageCode);
        assertThrows(UnsupportedOperationException.class, () -> filtered.voiceTags.clear());
        assertEquals("[\"温柔\",\"Warm\"]", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TAGS, "[\" 温柔 \",\"Warm\",\"Warm\"]"));
        assertEquals(21, BreezeSpeechSettings.VOICE_TONES.length);
        for (String gender : BreezeSpeechSettings.VOICE_GENDERS) assertEquals(gender.isEmpty() ? "[]" : "[\"" + gender + "\"]",
                BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_GENDER, gender));
        for (String tone : BreezeSpeechSettings.VOICE_TONES) assertEquals(tone.isEmpty() ? "[]" : "[\"" + tone + "\"]",
                BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TONE, tone));
        for (String invalid : new String[]{"Female", "woman", "男女声"}) assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_GENDER, invalid));
        for (String invalid : new String[]{"happy", "sad", "warm,calm"}) assertThrows(IllegalArgumentException.class,
                () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TONE, invalid));
    }

    @Test public void invalidCatalogTagsAreStrictAndCannotUseSavedFallbacksToReachTheNetwork() {
        for (String value : new String[]{"", "{}", "null", "[null]", "[1]", "[\"\"]", "[\" \t\"]",
                "[\"1\",\"2\",\"3\",\"4\",\"5\",\"6\"]", "[\"" + "x".repeat(129) + "\"]", "[\"bad\\n\"]",
                "[\"bad\u200btag\"]", "[\"bad\u202etag\"]", "[\"tag\"] trailing", "['tag']"}) {
            assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TAGS, value));
            BreezeSpeechSettings saved = settings(Map.of(BreezeSpeechSettings.VOICE_TAGS, value));
            assertThrows(IllegalArgumentException.class, saved::validateCatalog);
            assertThrows(IllegalArgumentException.class, saved::validateOptions);
        }
    }

    @Test public void anAllLanguagesAllVoicesCatalogNeedsAFilterWithoutConstrainingSynthesis() {
        Map<String, Object> values = new LinkedHashMap<>(); values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "");
        BreezeSpeechSettings unfiltered = settings(values); unfiltered.validateOptions(); assertFalse(unfiltered.hasCatalogFilter());
        assertEquals("请先设置 Breeze Blue 音色筛选条件", assertThrows(IllegalArgumentException.class, unfiltered::validateCatalog).getMessage());
        for (Map.Entry<String, String> filter : Map.of(BreezeSpeechSettings.VOICE_TYPE, "personal", BreezeSpeechSettings.VOICE_SEARCH, "温柔",
                BreezeSpeechSettings.VOICE_GENDER, "female", BreezeSpeechSettings.VOICE_TONE, "warm", BreezeSpeechSettings.VOICE_TAGS, "[\"narration\"]").entrySet()) {
            Map<String, Object> filtered = new LinkedHashMap<>(values); filtered.put(filter.getKey(), filter.getValue());
            assertTrue(settings(filtered).hasCatalogFilter()); settings(filtered).validateCatalog();
        }
        values.put(BreezeSpeechSettings.VOICE_TYPE, "default"); settings(values).validateCatalog();
        BreezeSpeechSettings synthesis = settings(Map.of(BreezeSpeechSettings.API_KEY, "fixture", BreezeSpeechSettings.VOICE_ID, "voice",
                BreezeSpeechSettings.LANGUAGE_CODE, "en", BreezeSpeechSettings.VOICE_LANGUAGE, ""));
        synthesis.validateForSynthesis(); assertFalse(synthesis.hasCatalogFilter()); assertEquals("en", synthesis.languageCode);
    }

    @Test public void allNewCatalogFilterKeysUseTheExistingBackupSchemaAndMissingLanguageDefaultsToChinese() throws Exception {
        CatalogStore source = new CatalogStore(), target = new CatalogStore();
        source.putString(BreezeSpeechSettings.VOICE_LANGUAGE, "en"); source.putString(BreezeSpeechSettings.VOICE_GENDER, "female");
        source.putString(BreezeSpeechSettings.VOICE_TONE, "warm"); source.putString(BreezeSpeechSettings.VOICE_TAGS, "[\"温柔\"]");
        source.putString(BreezeSpeechSettings.VOICE_CATEGORY, "podcast"); source.putString(BreezeSpeechSettings.VOICE_AGE, "[\"young\",\"middle_aged\"]");
        source.putString(BreezeSpeechSettings.VOICE_ACCENT, "british"); source.putString(BreezeSpeechSettings.VOICE_ACCENT_MODE, "all");
        source.putString(BreezeSpeechSettings.VOICE_ORIGIN, "cloned"); source.putString(BreezeSpeechSettings.VOICE_SORT, "name");
        source.putString(BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc"); source.putString(BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true");
        String backup = ConfigTransfer.export(source, true);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, backup)); assertEquals(source.values, target.values);
        assertEquals("female", settings(target.values).voiceGender); assertEquals(java.util.List.of("温柔"), settings(target.values).voiceTags);
        assertEquals(java.util.List.of("young", "middle_aged"), settings(target.values).voiceAges);
        assertEquals("podcast", settings(target.values).voiceCategory); assertEquals("british", settings(target.values).voiceAccent);
        assertEquals("all", settings(target.values).voiceAccentMode); assertEquals("cloned", settings(target.values).voiceOrigin);
        assertEquals("name", settings(target.values).voiceSort); assertEquals("asc", settings(target.values).voiceSortDirection);
        assertTrue(settings(target.values).voiceFavoritesOnly);
        assertEquals("zh", settings(Map.of()).voiceLanguage); assertEquals("", settings(Map.of()).languageCode);
    }

    @Test public void breezeDemographicAndToneFiltersAcceptBareCodesOrCanonicalMultipleChoices() throws Exception {
        BreezeSpeechSettings filtered = settings(Map.of(BreezeSpeechSettings.VOICE_GENDER, "[\"male\",\"female\",\"male\"]",
                BreezeSpeechSettings.VOICE_AGE, "[\"young\",\"middle_aged\"]", BreezeSpeechSettings.VOICE_TONE, "[\"warm\",\"calm\",\"gentle\",\"bright\"]"));
        filtered.validateCatalog();
        assertEquals(java.util.List.of("male", "female"), filtered.voiceGenders); assertEquals("male", filtered.voiceGender);
        assertEquals(java.util.List.of("young", "middle_aged"), filtered.voiceAges);
        assertEquals(java.util.List.of("warm", "calm", "gentle", "bright"), filtered.voiceTones); assertEquals("warm", filtered.voiceTone);
        assertEquals("[\"male\",\"female\"]", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_GENDER, "[\"male\",\"female\",\"male\"]"));
        assertEquals("[\"young\"]", BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_AGE, "young"));
        org.json.JSONArray allTones = new org.json.JSONArray();
        for (String tone : BreezeSpeechSettings.VOICE_TONES) if (!tone.isEmpty()) allTones.put(tone);
        assertEquals(20, settings(Map.of(BreezeSpeechSettings.VOICE_TONE, allTones.toString())).voiceTones.size());
        assertThrows(UnsupportedOperationException.class, filtered.voiceGenders::clear);
        assertThrows(UnsupportedOperationException.class, filtered.voiceAges::clear);
        assertThrows(UnsupportedOperationException.class, filtered.voiceTones::clear);
        for (String[] value : new String[][]{{BreezeSpeechSettings.VOICE_GENDER, "[\"female\",null]"},
                {BreezeSpeechSettings.VOICE_GENDER, "[\"male\",\"female\",\"neutral\",\"male\"]"},
                {BreezeSpeechSettings.VOICE_AGE, "[\"young\",1]"}, {BreezeSpeechSettings.VOICE_TONE, "[\"warm\",\"happy\"]"},
                {BreezeSpeechSettings.VOICE_GENDER, "[\"\"]"}, {BreezeSpeechSettings.VOICE_AGE, "null"}}) {
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(value[0], value[1])).validateCatalog());
        }
    }

    @Test public void breezeScalarCatalogEnumsAndDefaultsMatchTheDirectoryContract() {
        BreezeSpeechSettings defaults = settings(Map.of());
        assertEquals("all", defaults.voiceAccentMode); assertEquals("created_at_unix", defaults.voiceSort);
        assertEquals("desc", defaults.voiceSortDirection); assertFalse(defaults.voiceFavoritesOnly);
        assertEquals("", defaults.voiceCategory); assertEquals("", defaults.voiceAccent); assertEquals("", defaults.voiceOrigin);
        assertTrue(defaults.voiceAges.isEmpty()); assertEquals(13, BreezeSpeechSettings.VOICE_CATEGORIES.length);
        assertEquals(25, BreezeSpeechSettings.VOICE_ACCENTS.length); assertEquals(4, BreezeSpeechSettings.VOICE_AGES.length);
        String[] keys = {BreezeSpeechSettings.VOICE_CATEGORY, BreezeSpeechSettings.VOICE_ACCENT, BreezeSpeechSettings.VOICE_ACCENT_MODE,
                BreezeSpeechSettings.VOICE_ORIGIN, BreezeSpeechSettings.VOICE_SORT, BreezeSpeechSettings.VOICE_SORT_DIRECTION};
        String[][] choices = {BreezeSpeechSettings.VOICE_CATEGORIES, BreezeSpeechSettings.VOICE_ACCENTS, BreezeSpeechSettings.VOICE_ACCENT_MODES,
                BreezeSpeechSettings.VOICE_ORIGINS, BreezeSpeechSettings.VOICE_SORTS, BreezeSpeechSettings.VOICE_SORT_DIRECTIONS};
        for (int index = 0; index < keys.length; index++) {
            for (String value : choices[index]) assertEquals(value, BreezeSpeechSettings.validateValue(keys[index], value));
            final String key = keys[index];
            assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(key, "not-a-code"));
        }
        assertThrows(IllegalArgumentException.class, () -> BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "yes"));
    }

    @Test public void incompatibleBreezeAccentModesAndTrendScopesAreRejectedWhenSavingOrFetching() {
        BreezeSpeechSettings conflictingAccent = settings(Map.of(BreezeSpeechSettings.VOICE_ACCENT, "british",
                BreezeSpeechSettings.VOICE_ACCENT_MODE, "unmarked"));
        assertThrows(IllegalArgumentException.class, conflictingAccent::validateOptions);
        assertThrows(IllegalArgumentException.class, conflictingAccent::validateCatalog);
        for (String type : new String[]{"all", "personal"}) {
            BreezeSpeechSettings conflictingSort = settings(Map.of(BreezeSpeechSettings.VOICE_TYPE, type, BreezeSpeechSettings.VOICE_SORT, "trend"));
            assertThrows(IllegalArgumentException.class, conflictingSort::validateOptions);
            assertThrows(IllegalArgumentException.class, conflictingSort::validateCatalog);
        }
        Map<String, Object> saved = new LinkedHashMap<>(); saved.put(BreezeSpeechSettings.VOICE_TYPE, "default"); saved.put(BreezeSpeechSettings.VOICE_SORT, "trend");
        settings(saved).validateCatalog(); assertFalse(BreezeSpeechSettings.candidateIssue(saved, BreezeSpeechSettings.VOICE_TYPE, "all").isEmpty());
        assertEquals("default", saved.get(BreezeSpeechSettings.VOICE_TYPE));
    }

    @Test public void everyEffectiveBreezeDirectoryFilterCanNarrowAnOtherwiseUnfilteredQuery() {
        Map<String, Object> base = new LinkedHashMap<>(); base.put(BreezeSpeechSettings.VOICE_LANGUAGE, "");
        for (Map.Entry<String, String> filter : Map.of(BreezeSpeechSettings.VOICE_CATEGORY, "podcast",
                BreezeSpeechSettings.VOICE_AGE, "[\"young\"]", BreezeSpeechSettings.VOICE_ACCENT, "british",
                BreezeSpeechSettings.VOICE_ACCENT_MODE, "unmarked", BreezeSpeechSettings.VOICE_ORIGIN, "cloned",
                BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true").entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>(base); values.put(filter.getKey(), filter.getValue());
            assertTrue(settings(values).hasCatalogFilter()); settings(values).validateCatalog();
        }
        base.put(BreezeSpeechSettings.VOICE_SORT, "name"); base.put(BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc");
        assertFalse(settings(base).hasCatalogFilter()); assertThrows(IllegalArgumentException.class, () -> settings(base).validateCatalog());
    }

    private static final class CatalogStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings settings) { }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (!values.equals(expected)) throw new IllegalStateException("stale");
            values.clear(); values.putAll(replacement);
        }
    }

    private static BreezeSpeechSettings settings(Map<String, Object> changes) {
        return new BreezeSpeechSettings(new LocalOptions(new LinkedHashMap<>(changes)));
    }
}
