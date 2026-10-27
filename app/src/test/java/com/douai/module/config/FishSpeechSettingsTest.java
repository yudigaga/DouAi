package com.douai.module.config;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FishSpeechSettingsTest {
    @Test public void fishDefaultsAndProviderStateStayIndependentOfOtherSpeechSources() {
        SpeechSettings all = new SpeechSettings(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.FISH,
                SpeechSettings.MINIMAX_API_KEY, "minimax-fixture", BreezeSpeechSettings.API_KEY, "breeze-fixture",
                BreezeSpeechSettings.VOICE_ID, "breeze-voice", SpeechSettings.SELECTED_VOICE, "third-voice")));
        assertTrue(all.fish); assertFalse(all.local); assertFalse(all.breeze); assertFalse(all.minimax); assertFalse(all.thirdParty);
        FishSpeechSettings fish = all.fishSettings;
        assertEquals("https://api.fish.audio", fish.baseUrl); assertEquals("s2.1-pro", fish.engine);
        assertEquals("", fish.apiKey); assertEquals("", fish.referenceId); assertEquals("", fish.referenceName);
        assertEquals("single", fish.voiceMode); assertTrue(fish.referenceIds.isEmpty()); assertEquals("[]", fish.referenceSamples);
        assertEquals(1, fish.speed, 0); assertEquals(0, fish.volumeDb, 0); assertTrue(fish.normalizeLoudness);
        assertEquals("mp3", fish.format); assertEquals(0, fish.sampleRate); assertEquals(128, fish.mp3Bitrate);
        assertEquals(-1000, fish.opusBitrate); assertEquals(0.7, fish.temperature, 0); assertEquals(0.7, fish.topP, 0);
        assertTrue(fish.normalize); assertEquals("", fish.language); assertEquals("normal", fish.latency);
        assertEquals(300, fish.chunkLength); assertEquals(50, fish.minChunkLength); assertEquals(1024, fish.maxNewTokens);
        assertEquals(1.2, fish.repetitionPenalty, 0); assertTrue(fish.conditionOnPreviousChunks);
        assertEquals(1, fish.earlyStopThreshold, 0); assertFalse(fish.qualityGuard); assertEquals("public", fish.voiceScope);
        assertEquals("zh", fish.voiceLanguage); assertTrue(fish.voiceTags.isEmpty()); assertTrue(fish.hasCatalogFilter());
        assertEquals("minimax-fixture", all.minimaxApiKey); assertEquals("breeze-fixture", all.breezeSettings.apiKey);
        assertEquals("breeze-voice", all.breezeSettings.voiceId); assertEquals("third-voice", all.selectedVoice);
        assertEquals(SpeechSettings.FISH, SpeechSettings.validateProvider(SpeechSettings.FISH));
    }

    @Test public void publicBrowsingDefaultPreservesAnExplicitlySelectedAccountLibrary() {
        assertEquals("public", settings(Map.of()).voiceScope);
        assertEquals("self", settings(Map.of(FishSpeechSettings.VOICE_SCOPE, "self")).voiceScope);
    }

    @Test public void enginesAreNotVoiceReferencesAndUnknownEngineFallbackIsPrevented() {
        assertEquals("s2.1-pro-free", FishSpeechSettings.validateValue(FishSpeechSettings.ENGINE, "fish-audio/s2.1-pro-free"));
        assertEquals("s2.1-pro-free", settings(Map.of(FishSpeechSettings.ENGINE, "fish-audio/s2.1-pro-free")).engine);
        for (String engine : FishSpeechSettings.ENGINES) assertEquals(engine, FishSpeechSettings.validateValue(FishSpeechSettings.ENGINE, engine));
        for (String invalid : new String[]{"", "fish-audio/unknown", "s2.1-pro-fre", "reference-voice-id", "s2.1-pro\nprivate"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.ENGINE, invalid));
        }
        new FishSpeechSettings(new LocalOptions(Map.of())).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> new SpeechSettings(new LocalOptions(Map.of(
                SpeechSettings.PROVIDER, SpeechSettings.FISH))).validateFish());
        FishSpeechSettings defaultVoice = settings(Map.of(FishSpeechSettings.API_KEY, "fish-fixture"));
        defaultVoice.validateForSynthesis();
        assertEquals("", defaultVoice.referenceId);
    }

    @Test public void speakerReferencesKeepTheirOrderAndMayReuseOneVoiceAcrossDifferentSpeakers() {
        FishSpeechSettings voices = settings(Map.of(FishSpeechSettings.VOICE_MODE, "multi",
                FishSpeechSettings.REFERENCE_IDS, "[\"voice-b\",\"voice-a\",\"voice-b\"]", FishSpeechSettings.API_KEY, "fixture"));
        voices.validateForSynthesis();
        assertEquals(List.of("voice-b", "voice-a", "voice-b"), voices.referenceIds);
        assertThrows(UnsupportedOperationException.class, () -> voices.referenceIds.add("other"));
        settings(Map.of(FishSpeechSettings.VOICE_MODE, "multi", FishSpeechSettings.REFERENCE_IDS, "[\"one\"]",
                FishSpeechSettings.API_KEY, "fixture")).validateForSynthesis();
        JSONArray maximum = new JSONArray(); for (int index = 0; index < 8; index++) maximum.put("voice-" + index);
        assertEquals(8, settings(Map.of(FishSpeechSettings.REFERENCE_IDS, maximum.toString())).referenceIds.size());
        maximum.put("one-more");
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_IDS, maximum.toString()));
        for (String invalid : new String[]{"{}", "[null]", "[1]", "[\"\"]", "[\"bad\\nvoice\"]", "[\"voice\"] {}"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_IDS, invalid));
        }
    }

    @Test public void unfinishedVoiceDraftsCanBeStoredButUnsupportedOrEmptyActiveModesCannotSynthesize() {
        settings(Map.of(FishSpeechSettings.VOICE_MODE, "multi")).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.VOICE_MODE, "multi",
                FishSpeechSettings.API_KEY, "fixture")).validateForSynthesis());
        settings(Map.of(FishSpeechSettings.VOICE_MODE, "references")).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.VOICE_MODE, "references",
                FishSpeechSettings.API_KEY, "fixture")).validateForSynthesis());
        Map<String, Object> active = new LinkedHashMap<>(); active.put(FishSpeechSettings.VOICE_MODE, "multi");
        active.put(FishSpeechSettings.REFERENCE_IDS, "[\"voice\"]");
        assertFalse(FishSpeechSettings.candidateIssue(active, FishSpeechSettings.ENGINE, "s1").isEmpty());
        assertFalse(active.containsKey(FishSpeechSettings.ENGINE));
        settings(Map.of(FishSpeechSettings.ENGINE, "s1", FishSpeechSettings.REFERENCE_IDS, "[\"inactive-draft\"]")).validateOptions();
        for (String invalid : new String[]{"", "auto", "MULTI"}) assertThrows(IllegalArgumentException.class,
                () -> FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_MODE, invalid));
    }

    @Test public void referenceSamplesRetainExactTranscriptsAndOnlyDescribeLocalMaterial() throws Exception {
        String transcript = "  原音频中的原文。\n保留行与空格。  ";
        JSONArray sample = new JSONArray().put(new JSONObject().put("path", "/module/fish-reference.wav")
                .put("text", transcript).put("name", "参考音频"));
        String encoded = new JSONArray().put(sample).toString();
        FishSpeechSettings local = settings(Map.of(FishSpeechSettings.VOICE_MODE, "references",
                FishSpeechSettings.REFERENCE_SAMPLES, encoded, FishSpeechSettings.API_KEY, "fixture", FishSpeechSettings.ENGINE, "s1"));
        local.validateForSynthesis();
        FishSpeechSettings.ReferenceSample first = local.referenceGroups().get(0).get(0);
        assertEquals("/module/fish-reference.wav", first.path); assertEquals(transcript, first.text); assertEquals("参考音频", first.name);
        assertEquals(transcript, new JSONArray(FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_SAMPLES, encoded))
                .getJSONArray(0).getJSONObject(0).getString("text"));
        assertThrows(UnsupportedOperationException.class, () -> local.referenceGroups().add(List.of()));
        assertThrows(UnsupportedOperationException.class, () -> local.referenceGroups().get(0).clear());
        // Structural validation intentionally does not read files; file ownership and bytes are a feature-layer check.
        assertFalse(new java.io.File(first.path).exists());
    }

    @Test public void referenceSampleShapesAndModeCompatibilityAreValidatedBeforeAnyRequest() throws Exception {
        for (String invalid : new String[]{"{}", "[{}]", "[[null]]", "[[{\"path\":\"relative.wav\",\"text\":\"spoken\"}]]",
                "[[{\"path\":\"content://private/audio\",\"text\":\"spoken\"}]]",
                "[[{\"path\":\"/private.wav\",\"text\":\"   \"}]]",
                "[[{\"path\":\"/private.wav\",\"text\":\"spoken\",\"audio\":\"inline-base64\"}]]",
                "[[{\"path\":\"/private.wav\",\"path\":\"/duplicate.wav\",\"text\":\"spoken\"}]]"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_SAMPLES, invalid));
            assertFalse(failure.getMessage().contains("private")); assertFalse(failure.getMessage().contains("spoken"));
        }
        JSONObject item = new JSONObject().put("path", "/reference.wav").put("text", "spoken");
        JSONArray group = new JSONArray(); for (int index = 0; index < 4; index++) group.put(item);
        JSONArray groups = new JSONArray(); for (int index = 0; index < 8; index++) groups.put(group);
        settings(Map.of(FishSpeechSettings.REFERENCE_SAMPLES, groups.toString())).validateOptions();
        group.put(item);
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_SAMPLES, groups.toString()));
        String two = new JSONArray().put(new JSONArray().put(item)).put(new JSONArray().put(item)).toString();
        settings(Map.of(FishSpeechSettings.ENGINE, "s1", FishSpeechSettings.REFERENCE_SAMPLES, two)).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.ENGINE, "s1",
                FishSpeechSettings.VOICE_MODE, "references", FishSpeechSettings.REFERENCE_SAMPLES, two)).validateOptions());
        settings(Map.of(FishSpeechSettings.VOICE_MODE, "references", FishSpeechSettings.REFERENCE_SAMPLES, "[[]]")).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.VOICE_MODE, "references",
                FishSpeechSettings.REFERENCE_SAMPLES, "[[]]", FishSpeechSettings.API_KEY, "fixture")).validateForSynthesis());
    }

    @Test public void documentedRangesAreValidatedWithoutUnspecifiedGenerationCaps() {
        String[][] accepted = {{FishSpeechSettings.SPEED, "0.5", "0.5"}, {FishSpeechSettings.SPEED, "2.0", "2"},
                {FishSpeechSettings.VOLUME_DB, "-20", "-20"}, {FishSpeechSettings.VOLUME_DB, "20", "20"},
                {FishSpeechSettings.CHUNK_LENGTH, "100", "100"}, {FishSpeechSettings.CHUNK_LENGTH, "300", "300"},
                {FishSpeechSettings.MIN_CHUNK_LENGTH, "0", "0"}, {FishSpeechSettings.MIN_CHUNK_LENGTH, "100", "100"},
                {FishSpeechSettings.MAX_NEW_TOKENS, Integer.toString(Integer.MAX_VALUE), Integer.toString(Integer.MAX_VALUE)},
                {FishSpeechSettings.REPETITION_PENALTY, "1e100", "1" + "0".repeat(100)},
                {FishSpeechSettings.TEMPERATURE, "0", "0"}, {FishSpeechSettings.TOP_P, "1", "1"},
                {FishSpeechSettings.EARLY_STOP_THRESHOLD, "0", "0"}};
        for (String[] row : accepted) assertEquals(row[0], row[2], FishSpeechSettings.validateValue(row[0], row[1]));
        String[][] invalid = {{FishSpeechSettings.SPEED, "0.49"}, {FishSpeechSettings.SPEED, "2.01"},
                {FishSpeechSettings.VOLUME_DB, "-20.01"}, {FishSpeechSettings.VOLUME_DB, "20.01"},
                {FishSpeechSettings.TEMPERATURE, "NaN"}, {FishSpeechSettings.TOP_P, "1.01"},
                {FishSpeechSettings.EARLY_STOP_THRESHOLD, "-1"}, {FishSpeechSettings.CHUNK_LENGTH, "301"},
                {FishSpeechSettings.MIN_CHUNK_LENGTH, "101"}, {FishSpeechSettings.MAX_NEW_TOKENS, "0"},
                {FishSpeechSettings.REPETITION_PENALTY, "0"}, {FishSpeechSettings.REPETITION_PENALTY, "Infinity"},
                {FishSpeechSettings.NORMALIZE_LOUDNESS, "yes"}};
        for (String[] row : invalid) {
            assertThrows(row[0], IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(row[0], row[1]));
            assertThrows(IllegalArgumentException.class, () -> settings(Map.of(row[0], row[1])).validateOptions());
        }
    }

    @Test public void outputSettingsUseFishRatesAndUnitsAndRejectRawPcmBeforeRequests() {
        assertEquals("", FishSpeechSettings.validateValue(FishSpeechSettings.SAMPLE_RATE, ""));
        assertEquals(0, settings(Map.of(FishSpeechSettings.SAMPLE_RATE, "")).sampleRate);
        settings(Map.of(FishSpeechSettings.FORMAT, "wav", FishSpeechSettings.SAMPLE_RATE, "24000")).validateOptions();
        settings(Map.of(FishSpeechSettings.FORMAT, "mp3", FishSpeechSettings.SAMPLE_RATE, "32000")).validateOptions();
        settings(Map.of(FishSpeechSettings.FORMAT, "opus", FishSpeechSettings.SAMPLE_RATE, "48000",
                FishSpeechSettings.OPUS_BITRATE, "64000")).validateOptions();
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.FORMAT, "mp3", FishSpeechSettings.SAMPLE_RATE, "24000")).validateOptions());
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.FORMAT, "wav", FishSpeechSettings.SAMPLE_RATE, "48000")).validateOptions());
        assertThrows(IllegalArgumentException.class, () -> settings(Map.of(FishSpeechSettings.FORMAT, "opus", FishSpeechSettings.SAMPLE_RATE, "44100")).validateOptions());
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.MP3_BITRATE, "320"));
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.OPUS_BITRATE, "64"));
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.SAMPLE_RATE, "0"));
        for (String format : new String[]{"aac", "flac", "url", "hex"}) assertThrows(IllegalArgumentException.class,
                () -> FishSpeechSettings.validateValue(FishSpeechSettings.FORMAT, format));
        for (String pcm : new String[]{"pcm", "PCM", "pcm_s16le"}) {
            IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                    () -> FishSpeechSettings.validateValue(FishSpeechSettings.FORMAT, pcm));
            assertEquals("当前发送入口不支持裸 PCM，请选择 WAV", failure.getMessage());
        }
    }

    @Test public void endpointsAndNormalizationLanguageDoNotBorrowBreezeSemantics() {
        assertEquals("https://api.fish.audio", FishSpeechSettings.validateValue(FishSpeechSettings.BASE_URL, " https://api.fish.audio/ "));
        for (String url : new String[]{"", "https://user:key@example.test", "https://example.test?key=private",
                "https://example.test#private", "https://example.test:0", "https://example.test:65536", "ftp://example.test"}) {
            assertThrows(url, IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.BASE_URL, url));
        }
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.API_KEY, "private\r\nInjected: key"));
        assertEquals("es-MX", FishSpeechSettings.validateValue(FishSpeechSettings.LANGUAGE, "es_MX"));
        assertEquals("auto", FishSpeechSettings.validateValue(FishSpeechSettings.LANGUAGE, "auto"));
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.LANGUAGE, "x".repeat(36)));
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.LANGUAGE, "en US"));
        // Language here controls normalization; it does not constrain the selected engine's speech languages.
        settings(Map.of(FishSpeechSettings.ENGINE, "s1", FishSpeechSettings.LANGUAGE, "ja-JP",
                FishSpeechSettings.NORMALIZE_LOUDNESS, "true")).validateOptions();
    }

    @Test public void pronunciationDictionariesAreStrictHomogeneousAndHonorTheDocumentedItemCapacity() throws Exception {
        String references = "[{\"id\":\"dictionary-1\",\"version\":\"v1\"}]";
        assertEquals(references, FishSpeechSettings.validatePronunciationDictionary(references));
        JSONObject replacement = new JSONObject().put("key", "读音").put("value", "du2 yin1").put("case_sensitive", true);
        JSONArray entries = new JSONArray();
        for (int index = 0; index < 5000; index++) entries.put(replacement);
        JSONArray dictionaries = new JSONArray().put(new JSONObject().put("items", entries));
        assertEquals(5000, new JSONArray(FishSpeechSettings.validatePronunciationDictionary(dictionaries.toString()))
                .getJSONObject(0).getJSONArray("items").length());
        entries.put(replacement);
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validatePronunciationDictionary(dictionaries.toString()));
        for (String invalid : new String[]{"{}", "null", "[{\"id\":\"dictionary-1\"}]",
                "[{\"id\":\"dictionary-1\",\"version\":\"v1\"},{\"items\":[]}]",
                "[{\"items\":[{\"key\":\"a\",\"value\":\"b\",\"case_sensitive\":\"false\"}]}]",
                "[{\"items\":[{\"key\":\"<|phoneme_start|>\",\"value\":\"b\"}]}]",
                "[{\"items\":[{\"key\":\"a\",\"value\":\"b\",\"extra\":true}]}]",
                "[{\"items\":[],\"items\":[]} ]"}) {
            assertThrows(invalid, IllegalArgumentException.class, () -> FishSpeechSettings.validatePronunciationDictionary(invalid));
        }
        String tooMany = "[{\"items\":[]},{\"items\":[]},{\"items\":[]},{\"items\":[]}]";
        assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validatePronunciationDictionary(tooMany));
        String supplementaryKey = "\uD83D\uDE00".repeat(256);
        String validUnicode = new JSONArray().put(new JSONObject().put("items", new JSONArray().put(new JSONObject()
                .put("key", supplementaryKey).put("value", "test")))).toString();
        FishSpeechSettings.validatePronunciationDictionary(validUnicode);
    }

    @Test public void candidateChecksAndAliasesKeepOneTransferKeyForEachSetting() {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(FishSpeechSettings.FORMAT, "wav"); values.put(FishSpeechSettings.SAMPLE_RATE, "24000");
        assertFalse(FishSpeechSettings.candidateIssue(values, FishSpeechSettings.FORMAT, "opus").isEmpty());
        assertEquals("wav", values.get(FishSpeechSettings.FORMAT));
        assertEquals("", FishSpeechSettings.candidateIssue(values, FishSpeechSettings.SPEED, "2"));
        assertFalse(values.containsKey(FishSpeechSettings.SPEED));
        assertEquals(FishSpeechSettings.ENGINE, FishSpeechSettings.MODEL);
        assertEquals(FishSpeechSettings.REFERENCE_ID, FishSpeechSettings.VOICE_ID);
        assertEquals(FishSpeechSettings.REFERENCE_NAME, FishSpeechSettings.VOICE_NAME);
        assertEquals(FishSpeechSettings.VOLUME_DB, FishSpeechSettings.VOLUME);
        assertEquals(FishSpeechSettings.KEYS.length, new HashSet<>(Arrays.asList(FishSpeechSettings.KEYS)).size());
        for (String invalid : new String[]{"tts_fish_pitch", "tts_fish_instructions", "tts_fish_timbre_weights"}) assertFalse(FishSpeechSettings.isKey(invalid));
    }

    @Test public void catalogLanguageAndTagsAreIndependentCanonicalImmutableAndBounded() {
        FishSpeechSettings filtered = settings(Map.of(FishSpeechSettings.VOICE_LANGUAGE, "JA",
                FishSpeechSettings.VOICE_TAGS, "[ \" 温柔 \", \"Warm\", \"Warm\" ]", FishSpeechSettings.LANGUAGE, "en-US"));
        filtered.validateCatalog(); assertEquals("ja", filtered.voiceLanguage); assertEquals("en-US", filtered.language);
        assertEquals(List.of("温柔", "Warm"), filtered.voiceTags);
        assertThrows(UnsupportedOperationException.class, () -> filtered.voiceTags.add("new"));
        assertEquals("[\"温柔\",\"Warm\"]", FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_TAGS, "[\" 温柔 \",\"Warm\",\"Warm\"]"));
        assertEquals("[\"zh-cn\"]", FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_LANGUAGE, "ZH_CN"));
        assertEquals("[]", FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_LANGUAGE, ""));
        for (String language : new String[]{"zh CN", "private?token", "中", "a"}) {
            assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_LANGUAGE, language));
        }
    }

    @Test public void malformedCatalogTagsCannotBeHiddenByConstructorFallbacks() {
        String[] invalid = {"", "{}", "null", "[null]", "[1]", "[\"\"]", "[\" \"]", "[\"one\",\"two\",\"three\",\"four\",\"five\",\"six\"]",
                "[\"" + "x".repeat(129) + "\"]", "[\"bad\\n\"]", "[\"bad\u200btag\"]", "[\"bad\u202etag\"]",
                "[\"tag\"] trailing", "['tag']"};
        for (String value : invalid) {
            assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_TAGS, value));
            FishSpeechSettings saved = settings(Map.of(FishSpeechSettings.VOICE_TAGS, value));
            assertThrows(IllegalArgumentException.class, saved::validateCatalog);
            assertThrows(IllegalArgumentException.class, saved::validateOptions);
        }
    }

    @Test public void onlyTheCatalogRequiresAnExplicitFilterAndOwnedScopeCanNarrowIt() {
        Map<String, Object> values = new LinkedHashMap<>(); values.put(FishSpeechSettings.VOICE_LANGUAGE, "");
        FishSpeechSettings unfiltered = settings(values); unfiltered.validateOptions(); assertFalse(unfiltered.hasCatalogFilter());
        assertEquals("请先设置 Fish Audio 音色筛选条件", assertThrows(IllegalArgumentException.class, unfiltered::validateCatalog).getMessage());
        for (Map.Entry<String, String> filter : Map.of(FishSpeechSettings.VOICE_SCOPE, "self", FishSpeechSettings.VOICE_SEARCH, "女声",
                FishSpeechSettings.VOICE_TAGS, "[\"温柔\"]").entrySet()) {
            Map<String, Object> filtered = new LinkedHashMap<>(values); filtered.put(filter.getKey(), filter.getValue());
            assertTrue(settings(filtered).hasCatalogFilter()); settings(filtered).validateCatalog();
        }
    }

    @Test public void catalogFilterKeysRoundTripThroughTheExistingPortableConfigurationSchema() throws Exception {
        CatalogStore source = new CatalogStore(), target = new CatalogStore();
        source.putString(FishSpeechSettings.VOICE_LANGUAGE, "ja"); source.putString(FishSpeechSettings.VOICE_TAGS, "[\"温柔\",\"warm\"]");
        source.putString(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\",\"en\"]");
        source.putString(FishSpeechSettings.VOICE_AUTHOR_ID, "author-fixture"); source.putString(FishSpeechSettings.VOICE_LICENSED, "true");
        source.putString(FishSpeechSettings.VOICE_SORT, "task_count");
        String backup = ConfigTransfer.export(source, true);
        assertEquals("ja", new JSONObject(backup).getJSONObject("values").getJSONObject(FishSpeechSettings.VOICE_LANGUAGE).getString("value"));
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, backup)); assertEquals(source.values, target.values);
        assertEquals(List.of("温柔", "warm"), settings(target.values).voiceTags);
        assertEquals(List.of("zh", "en"), settings(target.values).voiceTitleLanguages);
        assertEquals("author-fixture", settings(target.values).voiceAuthorId); assertEquals("true", settings(target.values).voiceLicensed);
        assertEquals("task_count", settings(target.values).voiceSort);
        assertEquals("zh", settings(Map.of()).voiceLanguage);
    }

    @Test public void fishLanguageFiltersSupportMultipleCodesAndKeepTheOldFirstProjection() {
        FishSpeechSettings filtered = settings(Map.of(FishSpeechSettings.VOICE_LANGUAGE, "[\"ZH\",\"en\",\"zh\"]",
                FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"JA\",\"zh_TW\"]"));
        filtered.validateCatalog(); assertEquals(List.of("zh", "en"), filtered.voiceLanguages); assertEquals("zh", filtered.voiceLanguage);
        assertEquals(List.of("ja", "zh-tw"), filtered.voiceTitleLanguages);
        assertEquals("[\"zh\",\"en\"]", FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_LANGUAGE, "[\"ZH\",\"en\",\"zh\"]"));
        assertEquals("[\"ja\"]", FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "JA"));
        assertThrows(UnsupportedOperationException.class, filtered.voiceLanguages::clear);
        assertThrows(UnsupportedOperationException.class, filtered.voiceTitleLanguages::clear);
        for (String invalid : new String[]{"null", "[null]", "[1]", "[\"\"]", "[\"zh\",\"bad language\"]", "[\"zh\"] tail", "[[\"zh\"]]"}) {
            for (String key : new String[]{FishSpeechSettings.VOICE_LANGUAGE, FishSpeechSettings.VOICE_TITLE_LANGUAGE}) {
                assertThrows(IllegalArgumentException.class, () -> FishSpeechSettings.validateValue(key, invalid));
                assertThrows(IllegalArgumentException.class, () -> settings(Map.of(key, invalid)).validateCatalog());
            }
        }
    }

    @Test public void fishAuthorLicensedAndSortUseTheirOwnContractWithoutTurningSortIntoAFilter() {
        Map<String, Object> base = new LinkedHashMap<>(); base.put(FishSpeechSettings.VOICE_LANGUAGE, "");
        for (String sort : FishSpeechSettings.VOICE_SORTS) {
            base.put(FishSpeechSettings.VOICE_SORT, sort); base.put(FishSpeechSettings.VOICE_LICENSED, "false");
            FishSpeechSettings sorted = settings(base); sorted.validateOptions(); assertFalse(sorted.hasCatalogFilter());
            assertThrows(IllegalArgumentException.class, sorted::validateCatalog);
        }
        for (Map.Entry<String, String> filter : Map.of(FishSpeechSettings.VOICE_AUTHOR_ID, "creator ?&/",
                FishSpeechSettings.VOICE_LICENSED, "true", FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\"]").entrySet()) {
            Map<String, Object> values = new LinkedHashMap<>(base); values.put(filter.getKey(), filter.getValue());
            assertTrue(settings(values).hasCatalogFilter()); settings(values).validateCatalog();
        }
        assertEquals("score", settings(Map.of()).voiceSort); assertEquals("", settings(Map.of()).voiceLicensed);
        assertTrue(settings(Map.of()).voiceTitleLanguages.isEmpty());
        String[][] invalid = {{FishSpeechSettings.VOICE_AUTHOR_ID, "bad\u202e"}, {FishSpeechSettings.VOICE_AUTHOR_ID, "a".repeat(257)},
                {FishSpeechSettings.VOICE_SORT, "name"}, {FishSpeechSettings.VOICE_LICENSED, "yes"}, {FishSpeechSettings.VOICE_LICENSED, "TRUE"}};
        for (String[] value : invalid) assertThrows(IllegalArgumentException.class,
                () -> settings(Map.of(value[0], value[1])).validateCatalog());
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

    private static FishSpeechSettings settings(Map<String, Object> values) {
        return new FishSpeechSettings(new LocalOptions(values));
    }
}
