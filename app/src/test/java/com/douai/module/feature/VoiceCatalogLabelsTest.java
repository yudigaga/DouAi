package com.douai.module.feature;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.ConfigurationCatalog;
import com.douai.module.config.ConfigurationChanges;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class VoiceCatalogLabelsTest {
    @Test public void breezeUsesItsDocumentedMetadataTypesAndChineseCodeLabels() throws Exception {
        JSONObject voice = new JSONObject().put("language_code", "zh").put("gender", "female").put("age", "young")
                .put("tone", new JSONArray().put("warm").put("calm")).put("accent", "mandarin_beijing")
                .put("tags", new JSONArray().put("Original Tag")).put("name", "Unchanged original")
                .put("quality", "premium").put("origin", "designed");
        assertEquals(Arrays.asList("语言：中文", "性别：女声", "年龄：青年", "语气：温暖", "语气：平静", "口音：北京普通话", "Original Tag"),
                VoiceCatalogLabels.breeze(voice));
        assertEquals("Unchanged original", voice.getString("name"));
        assertEquals(Arrays.asList("语言：英语", "性别：男声", "年龄：中年", "语气：沉稳", "口音：美式英语"),
                VoiceCatalogLabels.breeze(new JSONObject().put("language_code", "en").put("gender", "male")
                        .put("age", "middle_aged").put("tone", new JSONArray().put("steady")).put("accent", "american")));
    }

    @Test public void fishReadsLanguagesAndFreeTagsWithoutInventingBreezeFieldsOrQualityClaims() throws Exception {
        JSONObject model = new JSONObject().put("languages", new JSONArray().put("zh").put("EN").put("zz-QZ"))
                .put("tags", new JSONArray().put("female").put("warm").put("原始标签"))
                .put("gender", "male").put("age", "old").put("tone", new JSONArray().put("energetic"))
                .put("accent", "british").put("language_code", "ja").put("quality", new JSONObject().put("score", 100));
        assertEquals(Arrays.asList("语言：中文", "语言：英语", "语言：zz-QZ", "female", "warm", "原始标签"),
                VoiceCatalogLabels.fish(model));
        assertEquals(Collections.emptyList(), VoiceCatalogLabels.fish(new JSONObject().put("gender", "female")
                .put("language_code", "zh").put("tone", "warm")));
    }

    @Test public void unknownCodesAndFreeTagsPreserveTheirOriginalText() throws Exception {
        JSONObject voice = new JSONObject().put("language_code", "ZZ-Next").put("gender", "FutureGender")
                .put("age", "next_age").put("tone", new JSONArray().put("NextTone")).put("accent", "next_accent")
                .put("tags", new JSONArray().put("warm").put("Custom Tag"));
        assertEquals(Arrays.asList("语言：ZZ-Next", "性别：FutureGender", "年龄：next_age", "语气：NextTone",
                "口音：next_accent", "warm", "Custom Tag"), VoiceCatalogLabels.breeze(voice));
        assertEquals(Collections.singletonList("语言：简体中文"), VoiceCatalogLabels.fish(new JSONObject()
                .put("languages", new JSONArray().put("zh_Hans"))));
    }

    @Test public void malformedOptionalMetadataIsIgnoredWhileValidFieldsRemainUsable() throws Exception {
        JSONObject voice = new JSONObject().put("language_code", new JSONArray().put("zh"))
                .put("gender", 1).put("age", JSONObject.NULL).put("tone", "warm").put("accent", new JSONObject())
                .put("tags", new JSONArray().put(JSONObject.NULL).put(3).put(new JSONObject()).put(new JSONArray())
                        .put("隐藏\n文字").put("中\u200b文").put("中文\ud800").put("x".repeat(49)).put("安全标签"));
        assertEquals(Collections.singletonList("安全标签"), VoiceCatalogLabels.breeze(voice));
        assertTrue(VoiceCatalogLabels.breeze(null).isEmpty()); assertTrue(VoiceCatalogLabels.fish(null).isEmpty());
        assertTrue(VoiceCatalogLabels.fish(new JSONObject().put("languages", "zh").put("tags", "free" )).isEmpty());
    }

    @Test public void metadataLabelsHaveAFixedBoundAndCannotBeMutated() throws Exception {
        JSONArray tags = new JSONArray();
        for (int i = 0; i < 100; i++) tags.put("标签" + i);
        List<String> labels = VoiceCatalogLabels.fish(new JSONObject().put("languages", new JSONArray().put("zh"))
                .put("tags", tags));
        assertEquals(8, labels.size());
        assertEquals("语言：中文", labels.get(0)); assertEquals("标签6", labels.get(7));
        for (String label : labels) assertTrue(label.length() <= 48);
        assertThrows(UnsupportedOperationException.class, labels::clear);
    }

    @Test public void cachedMetadataRoundTripsWithoutRenamingTheVoiceOrLeakingProviderObjects() throws Exception {
        VoiceCatalog.Entry original = new VoiceCatalog.Entry("actual-id", "Original English Name", Arrays.asList("语言：中文", "free tag"));
        JSONObject sample = VoiceCatalogLabels.cached(original);
        assertEquals(3, sample.length());
        assertEquals(original.id, sample.getString("id")); assertEquals(original.name, sample.getString("name"));
        List<VoiceCatalog.Entry> parsed = VoiceCatalog.parse(new JSONArray().put(sample).toString(), "", "", "");
        assertEquals(original.id, parsed.get(0).id); assertEquals(original.name, parsed.get(0).name);
        assertEquals(original.labels, parsed.get(0).labels);
        sample.getJSONArray("labels").put("Later mutation");
        assertEquals(2, original.labels.size());
        assertThrows(IllegalArgumentException.class, () -> VoiceCatalogLabels.cached(null));
    }

    @Test public void fishMarkerUsesEveryOwnCatalogFieldAndExcludesSynthesisAndOtherProviders() {
        Map<String, Object> base = settings(SpeechSettings.FISH);
        String marker = key(base);
        assertTrue(marker.matches("[0-9a-f]{64}"));
        assertFalse(marker.contains("private"));
        assertChange(base, FishSpeechSettings.BASE_URL, "https://different.example.test", marker);
        assertChange(base, FishSpeechSettings.API_KEY, "changed-key", marker);
        assertChange(base, FishSpeechSettings.VOICE_SCOPE, "self", marker);
        assertChange(base, FishSpeechSettings.VOICE_SEARCH, "new title", marker);
        assertChange(base, FishSpeechSettings.VOICE_LANGUAGE, "[\"en\"]", marker);
        assertChange(base, FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"en\"]", marker);
        assertChange(base, FishSpeechSettings.VOICE_AUTHOR_ID, "fixture-author", marker);
        assertChange(base, FishSpeechSettings.VOICE_LICENSED, "true", marker);
        assertChange(base, FishSpeechSettings.VOICE_LICENSED, "false", marker);
        assertChange(base, FishSpeechSettings.VOICE_SORT, "created_at", marker);
        assertChange(base, FishSpeechSettings.VOICE_TAGS, "[\"warm\"]", marker);
        Map<String, Object> unrelated = new LinkedHashMap<>(base);
        unrelated.put(BreezeSpeechSettings.API_KEY, "ignored-breeze-key");
        unrelated.put(BreezeSpeechSettings.VOICE_LANGUAGE, "en");
        unrelated.put(BreezeSpeechSettings.VOICE_GENDER, "male");
        unrelated.put(BreezeSpeechSettings.VOICE_AGE, "[\"old\"]");
        unrelated.put(BreezeSpeechSettings.VOICE_CATEGORY, "narration");
        unrelated.put(BreezeSpeechSettings.VOICE_ACCENT, "british");
        unrelated.put(BreezeSpeechSettings.VOICE_ORIGIN, "cloned");
        unrelated.put(BreezeSpeechSettings.VOICE_SORT, "name");
        unrelated.put(BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true");
        unrelated.put(BreezeSpeechSettings.VOICE_TAGS, "[\"ignored\"]");
        unrelated.put(FishSpeechSettings.LANGUAGE, "ja");
        unrelated.put(FishSpeechSettings.REFERENCE_ID, "changed-synthesis-id");
        unrelated.put(FishSpeechSettings.REFERENCE_NAME, "Changed selected display name");
        unrelated.put(FishSpeechSettings.ENGINE, "s1");
        unrelated.put(FishSpeechSettings.SPEED, "1.2");
        unrelated.put(FishSpeechSettings.FORMAT, "wav");
        assertEquals(marker, key(unrelated));
    }

    @Test public void breezeMarkerUsesEveryOwnCatalogFieldAndExcludesItsSynthesisLanguageAndModel() {
        Map<String, Object> base = settings(SpeechSettings.BREEZE);
        String marker = key(base);
        assertTrue(marker.matches("[0-9a-f]{64}"));
        assertChange(base, BreezeSpeechSettings.BASE_URL, "https://different.example.test", marker);
        assertChange(base, BreezeSpeechSettings.API_KEY, "changed-key", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_TYPE, "personal", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_SEARCH, "new title", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_LANGUAGE, "en", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_GENDER, "[\"female\"]", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_AGE, "[\"young\"]", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_TONE, "[\"calm\"]", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_CATEGORY, "narration", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_ACCENT, "british", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_ACCENT_MODE, "unmarked", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_ORIGIN, "cloned", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_SORT, "name", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true", marker);
        assertChange(base, BreezeSpeechSettings.VOICE_TAGS, "[\"studio\"]", marker);
        Map<String, Object> unrelated = new LinkedHashMap<>(base);
        unrelated.put(FishSpeechSettings.API_KEY, "ignored-fish-key");
        unrelated.put(FishSpeechSettings.VOICE_SCOPE, "self");
        unrelated.put(FishSpeechSettings.VOICE_LANGUAGE, "ja");
        unrelated.put(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"en\"]");
        unrelated.put(FishSpeechSettings.VOICE_AUTHOR_ID, "ignored-author");
        unrelated.put(FishSpeechSettings.VOICE_LICENSED, "true");
        unrelated.put(FishSpeechSettings.VOICE_SORT, "task_count");
        unrelated.put(BreezeSpeechSettings.LANGUAGE_CODE, "en");
        unrelated.put(BreezeSpeechSettings.MODEL, "breeze-tts-2-multilingual");
        unrelated.put(BreezeSpeechSettings.VOICE_ID, "changed-selected-id");
        unrelated.put(BreezeSpeechSettings.VOICE_NAME, "Changed display name");
        unrelated.put(BreezeSpeechSettings.SPEED, "1.2");
        unrelated.put(BreezeSpeechSettings.OUTPUT_FORMAT, "wav");
        assertEquals(marker, key(unrelated));
    }

    @Test public void fishMultiLanguageMarkersBindEveryValueBeyondTheLegacyFirstLanguage() {
        Map<String, Object> single = settings(SpeechSettings.FISH), multiple = settings(SpeechSettings.FISH);
        single.put(FishSpeechSettings.VOICE_LANGUAGE, "[\"zh\"]");
        multiple.put(FishSpeechSettings.VOICE_LANGUAGE, "[\"zh\",\"en\"]");
        assertEquals(speech(single).fishSettings.voiceLanguage, speech(multiple).fishSettings.voiceLanguage);
        assertNotEquals(key(single), key(multiple));
        single.put(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\"]");
        multiple = new LinkedHashMap<>(single);
        multiple.put(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\",\"en\"]");
        assertNotEquals(key(single), key(multiple));
    }

    @Test public void breezeMultiValueMarkersBindEveryGenderAgeAndToneRatherThanOnlyTheFirstChoice() {
        for (String[] selection : new String[][]{
                {BreezeSpeechSettings.VOICE_GENDER, "female", "male"},
                {BreezeSpeechSettings.VOICE_AGE, "young", "old"},
                {BreezeSpeechSettings.VOICE_TONE, "warm", "calm"}}) {
            Map<String, Object> single = settings(SpeechSettings.BREEZE), multiple = settings(SpeechSettings.BREEZE);
            single.put(selection[0], "[\"" + selection[1] + "\"]");
            multiple.put(selection[0], "[\"" + selection[1] + "\",\"" + selection[2] + "\"]");
            SpeechSettings first = speech(single), second = speech(multiple);
            assertEquals(first.breezeSettings.voiceGender, second.breezeSettings.voiceGender);
            assertEquals(first.breezeSettings.voiceTone, second.breezeSettings.voiceTone);
            assertNotEquals(selection[0], key(single), key(multiple));
        }
    }

    @Test public void queryFieldsAndTagBoundariesAreLengthPrefixedAndProviderSeparated() {
        Map<String, Object> first = settings(SpeechSettings.FISH), second = settings(SpeechSettings.FISH);
        first.put(FishSpeechSettings.BASE_URL, "https://voice.example.test/a"); first.put(FishSpeechSettings.API_KEY, "bc");
        second.put(FishSpeechSettings.BASE_URL, "https://voice.example.test/ab"); second.put(FishSpeechSettings.API_KEY, "c");
        assertNotEquals(key(first), key(second));
        first = settings(SpeechSettings.FISH); second = settings(SpeechSettings.FISH);
        first.put(FishSpeechSettings.VOICE_TAGS, "[\"a\",\"bc\"]");
        second.put(FishSpeechSettings.VOICE_TAGS, "[\"ab\",\"c\"]");
        assertNotEquals(key(first), key(second));
        assertNotEquals(key(settings(SpeechSettings.FISH)), key(settings(SpeechSettings.BREEZE)));
        SpeechSettings fish = speech(settings(SpeechSettings.FISH)), breeze = speech(settings(SpeechSettings.BREEZE));
        assertEquals(Collections.singletonList("zh"), fish.fishSettings.voiceLanguages);
        assertEquals("zh", breeze.breezeSettings.voiceLanguage);
    }

    @Test public void unsupportedSourcesHaveNoMarkerAndDerivedMarkersAreExcludedFromBackupAndAutomation() {
        assertEquals(VoiceCatalogLabels.FISH_QUERY_KEY, VoiceCatalogLabels.queryStorageKey(speech(settings(SpeechSettings.FISH))));
        assertEquals(VoiceCatalogLabels.BREEZE_QUERY_KEY, VoiceCatalogLabels.queryStorageKey(speech(settings(SpeechSettings.BREEZE))));
        assertEquals("", VoiceCatalogLabels.queryKey(null)); assertEquals("", VoiceCatalogLabels.queryStorageKey(null));
        for (String provider : new String[]{SpeechSettings.LOCAL, SpeechSettings.THIRD_PARTY, SpeechSettings.MINIMAX}) {
            SpeechSettings settings = speech(settings(provider));
            assertEquals("", VoiceCatalogLabels.queryKey(settings)); assertEquals("", VoiceCatalogLabels.queryStorageKey(settings));
        }
        for (String storage : new String[]{VoiceCatalogLabels.FISH_QUERY_KEY, VoiceCatalogLabels.BREEZE_QUERY_KEY}) {
            assertFalse(storage.startsWith("tts_"));
            assertFalse(ConfigurationCatalog.settings().containsKey(storage));
            assertFalse(ConfigurationCatalog.transferableTypes().containsKey(storage));
            ConfigurationChanges changes = ConfigurationChanges.between(Collections.emptyMap(), Collections.singletonMap(storage, "derived"));
            assertFalse(changes.affects(ConfigurationCatalog.Feature.REPLY));
            assertFalse(changes.affects(ConfigurationCatalog.Feature.COMMENT_AUTOMATION));
            assertFalse(changes.affects(ConfigurationCatalog.Feature.AUTOMATION_DISPATCH));
        }
    }

    private static void assertChange(Map<String, Object> base, String field, Object value, String marker) {
        Map<String, Object> changed = new LinkedHashMap<>(base); changed.put(field, value);
        assertNotEquals(field, marker, key(changed));
    }

    private static String key(Map<String, Object> values) { return VoiceCatalogLabels.queryKey(speech(values)); }
    private static SpeechSettings speech(Map<String, Object> values) { return new SpeechSettings(new LocalOptions(values)); }
    private static Map<String, Object> settings(String provider) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put(SpeechSettings.PROVIDER, provider);
        values.put(FishSpeechSettings.BASE_URL, "https://voice.example.test");
        values.put(BreezeSpeechSettings.BASE_URL, "https://voice.example.test");
        values.put(FishSpeechSettings.API_KEY, "private-key");
        values.put(BreezeSpeechSettings.API_KEY, "private-key");
        return values;
    }
}
