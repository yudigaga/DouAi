package com.douai.module.feature;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.ConfigTransfer;
import com.douai.module.config.ConfigurationCatalog;
import com.douai.module.config.ConfigurationChanges;
import com.douai.module.config.FeatureSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SettingsStore;
import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class VoiceNameTranslationCacheTest {
    @Test public void scopeSeparatesProviderEndpointAndOwnedCredentialsWithoutExposingThem() {
        Map<String, Object> values = values(SpeechSettings.FISH);
        String fish = scope(values);
        assertTrue(fish.matches("[0-9a-f]{64}"));
        assertFalse(fish.contains("private-key"));
        values.put(BreezeSpeechSettings.API_KEY, "another-breeze-key");
        values.put(SpeechSettings.MINIMAX_API_KEY, "another-minimax-key");
        assertEquals(fish, scope(values));
        values.put(FishSpeechSettings.API_KEY, "changed-fish-key"); assertNotEquals(fish, scope(values));
        values.put(FishSpeechSettings.API_KEY, "private-key");
        values.put(FishSpeechSettings.BASE_URL, "https://other.example.test"); assertNotEquals(fish, scope(values));
        values.put(SpeechSettings.PROVIDER, SpeechSettings.BREEZE); assertNotEquals(fish, scope(values));
        String breeze = scope(values);
        values.put(FishSpeechSettings.API_KEY, "ignored-fish-key"); assertEquals(breeze, scope(values));
        values.put(BreezeSpeechSettings.API_KEY, "changed-breeze-key"); assertNotEquals(breeze, scope(values));
        for (String provider : new String[]{SpeechSettings.LOCAL, SpeechSettings.MINIMAX, SpeechSettings.THIRD_PARTY}) {
            values.put(SpeechSettings.PROVIDER, provider); assertEquals("", scope(values));
        }
        assertEquals("", VoiceNameTranslationCache.scope(null));
    }

    @Test public void aliasesAreDerivedFromTheExactOriginalAndNeverReplaceTheEntry() {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH));
        VoiceCatalog.Entry entry = entry("voice-id", "Gentle Voice");
        assertEquals(1, VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(entry),
                Collections.singletonMap(entry.id, "温柔声音")));
        VoiceNameTranslationCache cache = VoiceNameTranslationCache.read(store);
        assertEquals("温柔声音", cache.alias(scope, entry));
        assertEquals("温柔声音（Gentle Voice）", cache.displayName(scope, entry));
        assertEquals("Gentle Voice", entry.name); assertEquals("voice-id", entry.id);
        assertEquals("", cache.alias(scope, entry(entry.id, "GENTLE Voice")));
        assertEquals("", cache.alias(scope, entry("other-id", entry.name)));
        assertEquals("", cache.alias(scope(values(SpeechSettings.BREEZE)), entry));
        assertEquals("", cache.alias("", entry));
        assertEquals(1, store.values.size()); assertTrue(store.values.containsKey(VoiceNameTranslationCache.KEY));
        String encoded = store.getString(VoiceNameTranslationCache.KEY, "");
        assertFalse(encoded.contains(entry.id)); assertFalse(encoded.contains(entry.name)); assertFalse(encoded.contains("private-key"));
    }

    @Test public void chineseDigitsAndPunctuationRemainReadableWhileOtherScriptsNeedTranslation() {
        for (String name : new String[]{"中文声音 １２３·（女声）", "123 - ·", "👩🎵", "𠮷声音"}) {
            assertFalse(name, VoiceNameTranslationCache.needsTranslation(entry("id", name)));
        }
        for (String name : new String[]{"English", "中文 English", "カナ声音", "ひらがな", "العربية", "한국어", "Élodie"}) {
            assertTrue(name, VoiceNameTranslationCache.needsTranslation(entry("id", name)));
        }
        assertFalse(VoiceNameTranslationCache.needsTranslation(entry("English", "English")));
        assertFalse(VoiceNameTranslationCache.needsTranslation(null));
    }

    @Test public void aliasesRequireHanAndRejectHiddenFormattingControlsAndOversizedText() {
        assertTrue(VoiceNameTranslationCache.validAlias("温柔女声"));
        assertTrue(VoiceNameTranslationCache.validAlias("中文 Voice 12"));
        assertTrue(VoiceNameTranslationCache.validAlias(repeat("中", 96)));
        for (String alias : new String[]{"", "English", "123", "カナ", "中文\n", "中文\t", "中文\u0000", "中\u200b文", "中\u202e文",
                "中文\u2028", "中文\ud800", repeat("中", 97)}) {
            assertFalse(VoiceNameTranslationCache.validAlias(alias));
        }
        assertFalse(VoiceNameTranslationCache.validAlias(null));
    }

    @Test public void pendingReturnsABoundedImmutableBatchWithoutAlreadyTranslatedOrReadableNames() {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH));
        VoiceCatalog.Entry translated = entry("one", "First"), next = entry("two", "Second"), last = entry("three", "Third");
        VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(translated), Collections.singletonMap(translated.id, "第一"));
        VoiceNameTranslationCache cache = VoiceNameTranslationCache.read(store);
        List<VoiceCatalog.Entry> entries = Arrays.asList(translated, entry("cn", "原中文"), entry("raw-id", "raw-id"), next, next, last);
        assertEquals(Collections.singletonList(next), cache.pending(scope, entries, 1));
        assertEquals(Arrays.asList(next, last), cache.pending(scope, entries, 10));
        assertTrue(cache.pending(scope, entries, 0).isEmpty()); assertTrue(cache.pending("", entries, 10).isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> cache.pending(scope, entries, 10).clear());
    }

    @Test public void invalidTranslatorAliasesUnknownIdsAndAlreadyChineseNamesAreNotStored() {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.BREEZE));
        List<VoiceCatalog.Entry> entries = Arrays.asList(entry("one", "First"), entry("two", "Second"), entry("cn", "中文"));
        Map<String, String> translated = new LinkedHashMap<>();
        translated.put("one", "English only"); translated.put("two", "中\u200b文"); translated.put("cn", "别的中文"); translated.put("unknown", "未知");
        assertEquals(0, VoiceNameTranslationCache.merge(store, scope, entries, translated));
        assertTrue(store.values.isEmpty());
        assertEquals(0, VoiceNameTranslationCache.merge(store, "", entries, Collections.singletonMap("one", "第一")));
        assertTrue(store.values.isEmpty());
    }

    @Test public void localSearchMatchesIdOriginalAndAliasWithNormalizedLiteralAndTokens() {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH));
        VoiceCatalog.Entry entry = new VoiceCatalog.Entry("Voice-A_12", "Gentle [Voice] Élodie", List.of("语言：法语"));
        VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(entry), Collections.singletonMap(entry.id, "温柔女声"));
        VoiceNameTranslationCache cache = VoiceNameTranslationCache.read(store);
        for (String query : new String[]{"", " \t ", "voice-a_12", "GENTLE", "ｇｅｎｔｌｅ", "温柔", "[voice]", "ÉLODIE",
                "１２　温柔 GENTLE", "voice-a_12\t女声\nGentle", "法语", "法语 Élodie"}) {
            assertTrue(query, cache.matches(scope, entry, query));
        }
        for (String query : new String[]{"missing", "Gentle 男声", "[.*]", repeat("a", 257)}) {
            assertFalse(query, cache.matches(scope, entry, query));
        }
        assertTrue(cache.matches(scope, entry, null));
        assertFalse(cache.matches(scope(values(SpeechSettings.BREEZE)), entry, "温柔"));
        assertFalse(cache.matches(scope, null, ""));
    }

    @Test public void independentBatchesMergeTheLatestStoreAndPreviouslyReadSnapshotsStayImmutable() {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH));
        VoiceCatalog.Entry a = entry("a", "First"), b = entry("b", "Second");
        VoiceNameTranslationCache previous = VoiceNameTranslationCache.read(store);
        VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(a), Collections.singletonMap(a.id, "第一"));
        VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(b), Collections.singletonMap(b.id, "第二"));
        VoiceNameTranslationCache current = VoiceNameTranslationCache.read(store);
        assertEquals("第一", current.alias(scope, a)); assertEquals("第二", current.alias(scope, b));
        assertEquals("", previous.alias(scope, a)); assertEquals("", previous.alias(scope, b));
    }

    @Test public void concurrentTranslationBatchesDoNotLoseEachOthersAliases() throws Exception {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.BREEZE));
        VoiceCatalog.Entry a = entry("a", "First"), b = entry("b", "Second");
        ExecutorService workers = Executors.newFixedThreadPool(2); CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Integer> first = workers.submit(() -> { start.await(); return VoiceNameTranslationCache.merge(store, scope,
                    Collections.singletonList(a), Collections.singletonMap(a.id, "第一")); });
            Future<Integer> second = workers.submit(() -> { start.await(); return VoiceNameTranslationCache.merge(store, scope,
                    Collections.singletonList(b), Collections.singletonMap(b.id, "第二")); });
            start.countDown(); assertEquals(Integer.valueOf(1), first.get(3, TimeUnit.SECONDS));
            assertEquals(Integer.valueOf(1), second.get(3, TimeUnit.SECONDS));
            VoiceNameTranslationCache cache = VoiceNameTranslationCache.read(store);
            assertEquals("第一", cache.alias(scope, a)); assertEquals("第二", cache.alias(scope, b));
        } finally { workers.shutdownNow(); }
    }

    @Test public void entryAndByteBudgetsEvictOldAliasesAndRetainTheNewestSuccessfulOnes() throws Exception {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH));
        List<VoiceCatalog.Entry> originals = new ArrayList<>(); Map<String, String> translations = new LinkedHashMap<>();
        for (int index = 0; index < 2200; index++) {
            VoiceCatalog.Entry entry = entry("voice-" + index, "Voice " + index); originals.add(entry);
            translations.put(entry.id, repeat("中", 96));
        }
        assertEquals(2048, VoiceNameTranslationCache.merge(store, scope, originals, translations));
        String encoded = store.getString(VoiceNameTranslationCache.KEY, "");
        assertTrue(encoded.getBytes(StandardCharsets.UTF_8).length <= 1024 * 1024);
        assertEquals(2048, new JSONObject(encoded).getJSONArray("entries").length());
        VoiceNameTranslationCache cache = VoiceNameTranslationCache.read(store);
        assertEquals("", cache.alias(scope, originals.get(0)));
        assertEquals("", cache.alias(scope, originals.get(151)));
        assertEquals(repeat("中", 96), cache.alias(scope, originals.get(152)));
        assertEquals(repeat("中", 96), cache.alias(scope, originals.get(2199)));
    }

    @Test public void corruptUnknownVersionAndOversizedCachesReadAsEmptyAndCanBeRebuilt() throws Exception {
        MemoryStore store = new MemoryStore(); String scope = scope(values(SpeechSettings.FISH)); VoiceCatalog.Entry entry = entry("id", "Name");
        String[] corrupt = {"{", "[]", "{\"version\":2,\"entries\":[]}", "{\"version\":1,\"entries\":{}}",
                "{\"version\":1,\"version\":1,\"entries\":[]}", "{\"version\":1,\"entries\":[]} trailing",
                "{\"version\":1,\"entries\":[{\"key\":\"bad\",\"alias\":\"中文\"}]}",
                "{\"version\":1,\"entries\":[{\"key\":\"" + repeat("a", 64) + "\",\"alias\":\"English\"}]}",
                repeat(" ", 1024 * 1024 + 1), repeat("中", 400000)};
        for (String encoded : corrupt) {
            store.putString(VoiceNameTranslationCache.KEY, encoded);
            assertEquals("Name", VoiceNameTranslationCache.read(store).displayName(scope, entry));
            assertEquals(1, VoiceNameTranslationCache.read(store).pending(scope, Collections.singletonList(entry), 10).size());
        }
        JSONArray tooMany = new JSONArray();
        for (int index = 0; index < 2049; index++) tooMany.put(new JSONObject().put("key", String.format("%064x", index)).put("alias", "中文"));
        store.putString(VoiceNameTranslationCache.KEY, new JSONObject().put("version",1).put("entries",tooMany).toString());
        assertEquals(1, VoiceNameTranslationCache.read(store).pending(scope, Collections.singletonList(entry), 10).size());
        assertEquals(1, VoiceNameTranslationCache.merge(store, scope, Collections.singletonList(entry), Collections.singletonMap(entry.id,"名称")));
        assertEquals("名称", VoiceNameTranslationCache.read(store).alias(scope, entry));
    }

    @Test public void derivedCacheIsNotTransferableOrAnAutomationDispatchSetting() throws Exception {
        MemoryStore store = new MemoryStore();
        Map<String, Object> before = new LinkedHashMap<>(store.values);
        VoiceCatalog.Entry entry = entry("id", "Private Voice");
        VoiceNameTranslationCache.merge(store, scope(values(SpeechSettings.BREEZE)), Collections.singletonList(entry), Collections.singletonMap(entry.id,"本机译名"));
        assertFalse(VoiceNameTranslationCache.KEY.startsWith("tts_"));
        assertFalse(ConfigurationCatalog.settings().containsKey(VoiceNameTranslationCache.KEY));
        assertFalse(ConfigurationCatalog.transferableTypes().containsKey(VoiceNameTranslationCache.KEY));
        ConfigurationChanges changes = ConfigurationChanges.between(before, store.values);
        assertFalse(changes.affects(ConfigurationCatalog.Feature.REPLY));
        assertFalse(changes.affects(ConfigurationCatalog.Feature.COMMENT_AUTOMATION));
        assertFalse(changes.affects(ConfigurationCatalog.Feature.AUTOMATION_DISPATCH));
        for (boolean includeSecrets : new boolean[]{true, false}) {
            String backup = ConfigTransfer.export(store, includeSecrets);
            assertFalse(new JSONObject(backup).getJSONObject("values").has(VoiceNameTranslationCache.KEY));
            assertFalse(backup.contains("本机译名"));
        }
    }

    private static VoiceCatalog.Entry entry(String id, String name) { return new VoiceCatalog.Entry(id, name); }
    private static String scope(Map<String, Object> values) { return VoiceNameTranslationCache.scope(new SpeechSettings(new LocalOptions(values))); }
    private static Map<String, Object> values(String provider) {
        Map<String, Object> values = new LinkedHashMap<>(); values.put(SpeechSettings.PROVIDER, provider);
        values.put(FishSpeechSettings.API_KEY, "private-key"); values.put(BreezeSpeechSettings.API_KEY, "private-key");
        values.put(FishSpeechSettings.BASE_URL, "https://voice.example.test"); values.put(BreezeSpeechSettings.BASE_URL, "https://voice.example.test");
        return values;
    }
    private static String repeat(String value, int count) { return String.join("", Collections.nCopies(count, value)); }
    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>();
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() { return new LinkedHashMap<>(values); }
    }
}
