package com.douai.module.feature;

import android.speech.tts.Voice;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.MiniMaxSpeechOptions;
import com.douai.module.config.SpeechSettings;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class RandomSpeechVoiceTest {
    @Test public void globalRangeChoosesOnlySelectedSourceWhenEverySourceIsAvailable() {
        LocalOptions saved = allSourcesOptions();
        for (String source : List.of("local", "third_party", "minimax", "breeze", "fish")) {
            LocalOptions scoped = saved.withOverrides(Map.of("tts_random_sources", "[\"" + source + "\"]"));
            LocalOptions chosen = RandomSpeechVoice.select(scoped, pick(0), true);
            assertEquals(source, chosen.text(SpeechSettings.PROVIDER, ""));
        }
    }

    @Test public void missingRangeKeepsEveryUsableSourceInTheGlobalPool() {
        LocalOptions saved = allSourcesOptions();
        String[] sources = {"local", "third_party", "minimax", "breeze", "fish"};
        for (int i = 0; i < sources.length; i++) {
            assertEquals(sources[i], RandomSpeechVoice.select(saved, pick(i), true)
                    .text(SpeechSettings.PROVIDER, ""));
        }
    }

    @Test public void unavailableSelectedSourceCannotFallBackToUncheckedAvailableSources() {
        LocalOptions saved = allSourcesOptions().withOverrides(Map.of("tts_random_sources", "[\"minimax\"]",
                SpeechSettings.MINIMAX_API_KEY, ""));
        String failure = assertThrows(IllegalArgumentException.class,
                () -> RandomSpeechVoice.select(saved, pick(0), true)).getMessage();
        assertTrue(failure.contains("来源范围"));
        assertTrue(failure.contains("配置"));
    }

    @Test public void malformedOrEmptySavedRangesFailClosedEvenWhenAllSourcesAreAvailable() {
        LocalOptions saved = allSourcesOptions();
        for (Object range : new Object[]{"[]", "", "broken", "{}", "[\"unknown\"]", "[\"fish\",4]", true, null}) {
            LocalOptions scoped = saved.withOverrides(java.util.Collections.singletonMap("tts_random_sources", range));
            assertThrows(String.valueOf(range), IllegalArgumentException.class,
                    () -> RandomSpeechVoice.select(scoped, pick(0), true));
        }
    }

    @Test public void currentAndOffModesIgnoreGlobalRange() {
        LocalOptions saved = allSourcesOptions().withOverrides(Map.of("tts_random_sources", "[]",
                SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY));
        LocalOptions current = RandomSpeechVoice.select(saved.withOverrides(Map.of(
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT)), pick(0), true);
        assertEquals("third-one", current.text(SpeechSettings.SELECTED_VOICE, ""));
        LocalOptions off = saved.withOverrides(Map.of(SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_OFF));
        assertSame(off, RandomSpeechVoice.select(off, pick(0), true));
    }

    @Test public void offAndUnknownLegacyModeKeepManualProviderAndVoice() {
        LocalOptions manual = new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                SpeechSettings.SELECTED_VOICE, "manual", SpeechSettings.RANDOM_MODE, "legacy"));
        assertSame(manual, RandomSpeechVoice.select(manual, new Random(1)));
        assertEquals(SpeechSettings.RANDOM_OFF, new SpeechSettings(manual).randomMode);
    }

    @Test public void currentModeSelectsOnlyCurrentCatalogAndDoesNotChangeSavedManualVoice() {
        LocalOptions saved = withMarker(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT,
                SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}&voice={voice}",
                SpeechSettings.VOICE_SAMPLE, "[{\"id\":\"first\",\"name\":\"First\"},{\"id\":\"second\",\"name\":\"Second\"}]",
                SpeechSettings.SELECTED_VOICE, "manual", SpeechSettings.SELECTED_VOICE_NAME, "Manual")), SpeechSettings.THIRD_PARTY);
        LocalOptions first = RandomSpeechVoice.select(saved, pick(0));
        LocalOptions second = RandomSpeechVoice.select(saved, pick(1));
        assertEquals("first", first.text(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals("second", second.text(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals("Second", second.text(SpeechSettings.SELECTED_VOICE_NAME, ""));
        assertEquals("manual", saved.text(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals(SpeechSettings.THIRD_PARTY, second.text(SpeechSettings.PROVIDER, ""));
    }

    @Test public void allModeDrawsProviderBeforeVoiceAndSkipsUnconfiguredSystemEngine() {
        Map<String, Object> values = new HashMap<>();
        values.put(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        values.put(SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_ALL);
        values.put(SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}&voice={voice}");
        values.put(SpeechSettings.VOICE_SAMPLE, "[{\"id\":\"third-one\",\"name\":\"Third One\"},"
                + "{\"id\":\"third-two\",\"name\":\"Third Two\"}]");
        values.put(SpeechSettings.MINIMAX_API_KEY, "minimax-key");
        values.put(SpeechSettings.MINIMAX_VOICE_SAMPLE, "[{\"id\":\"mini-one\",\"name\":\"Mini One\"}]");
        values.put(BreezeSpeechSettings.API_KEY, "breeze-key");
        values.put(BreezeSpeechSettings.VOICE_TYPE, "default");
        values.put(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"breeze-one\",\"name\":\"Breeze One\"}]");
        values.put(FishSpeechSettings.API_KEY, "fish-key");
        values.put(FishSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"fish-one\",\"name\":\"Fish One\"}]");
        LocalOptions saved = new LocalOptions(values);
        for (String source : new String[]{SpeechSettings.THIRD_PARTY, SpeechSettings.MINIMAX,
                SpeechSettings.BREEZE, SpeechSettings.FISH}) saved = withMarker(saved, source);
        String[] sources = {SpeechSettings.THIRD_PARTY, SpeechSettings.MINIMAX, SpeechSettings.BREEZE, SpeechSettings.FISH};
        String[] keys = {SpeechSettings.SELECTED_VOICE, SpeechSettings.MINIMAX_VOICE_ID,
                BreezeSpeechSettings.VOICE_ID, FishSpeechSettings.REFERENCE_ID};
        String[] voices = {"third-one", "mini-one", "breeze-one", "fish-one"};
        for (int i = 0; i < sources.length; i++) {
            LocalOptions chosen = RandomSpeechVoice.select(saved, pick(i));
            assertEquals(sources[i], chosen.text(SpeechSettings.PROVIDER, ""));
            assertEquals(voices[i], chosen.text(keys[i], ""));
        }
        LocalOptions secondThirdPartyVoice = RandomSpeechVoice.select(saved, new Random() {
            private int draws;
            @Override public int nextInt(int bound) {
                if (draws++ == 0) { assertEquals(4, bound); return 0; }
                assertEquals(2, bound); return 1;
            }
        });
        assertEquals(SpeechSettings.THIRD_PARTY, secondThirdPartyVoice.text(SpeechSettings.PROVIDER, ""));
        assertEquals("third-two", secondThirdPartyVoice.text(SpeechSettings.SELECTED_VOICE, ""));
        assertEquals(SpeechSettings.FISH, saved.text(SpeechSettings.PROVIDER, ""));
    }

    @Test public void staleBindingsAndMissingVoicePlaceholderExcludeNetworkCatalogs() {
        LocalOptions valid = withMarker(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT,
                SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}&voice={voice}",
                SpeechSettings.VOICE_SAMPLE, "[{\"id\":\"one\",\"name\":\"One\"}]")), SpeechSettings.THIRD_PARTY);
        assertEquals("one", RandomSpeechVoice.select(valid, pick(0)).text(SpeechSettings.SELECTED_VOICE, ""));
        for (Map<String, String> changed : List.of(Map.of(SpeechSettings.GET_TEMPLATE, "https://changed.test/tts?text={text}&voice={voice}"),
                Map.of(SpeechSettings.HEADERS, "{\"Authorization\":\"changed\"}"),
                Map.of(SpeechSettings.VOICE_GET_TEMPLATE, "https://changed.test/voices"),
                Map.of(SpeechSettings.VOICE_LIST_PATH, "data.list"),
                Map.of(SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}"))) {
            assertThrows(IllegalArgumentException.class, () -> RandomSpeechVoice.select(valid.withOverrides(changed), pick(0)));
        }
    }

    @Test public void mixedAndMultiSpeakerModesCannotMasqueradeAsSingleVoiceRandom() {
        LocalOptions minimax = new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT,
                MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "one/50\ntwo/50"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RandomSpeechVoice.select(minimax, pick(0))).getMessage().contains("单音色"));
        LocalOptions fish = new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.FISH,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT, FishSpeechSettings.VOICE_MODE, "references"));
        assertTrue(assertThrows(IllegalArgumentException.class,
                () -> RandomSpeechVoice.select(fish, pick(0))).getMessage().contains("单音色"));
    }

    @Test public void invalidSynthesisOptionsAndHeadersDoNotEnterRandomPools() {
        LocalOptions mini = withMarker(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT,
                SpeechSettings.MINIMAX_API_KEY, "minimax-key", SpeechSettings.MINIMAX_SPEED, "99",
                SpeechSettings.MINIMAX_VOICE_SAMPLE, "[{\"id\":\"one\",\"name\":\"One\"}]")), SpeechSettings.MINIMAX);
        assertThrows(IllegalArgumentException.class, () -> RandomSpeechVoice.select(mini, pick(0)));

        LocalOptions third = withMarker(new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_CURRENT,
                SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}&voice={voice}",
                SpeechSettings.HEADERS, "{\"Authorization\":42}",
                SpeechSettings.VOICE_SAMPLE, "[{\"id\":\"one\",\"name\":\"One\"}]")), SpeechSettings.THIRD_PARTY);
        assertThrows(IllegalArgumentException.class, () -> RandomSpeechVoice.select(third, pick(0)));
    }

    @Test public void localRandomPrefersExactLocaleAndRejectsNetworkVoices() {
        Voice enUs = new Voice("us", Locale.US, 300, 300, false, Set.of());
        Voice enGb = new Voice("gb", Locale.UK, 300, 300, false, Set.of());
        Voice network = new Voice("network", Locale.US, 300, 300, true, Set.of());
        Voice chinese = new Voice("zh", Locale.CHINA, 300, 300, false, Set.of());
        Set<Voice> installed = new LinkedHashSet<>(Arrays.asList(enGb, network, chinese, enUs));
        assertEquals(enUs, LocalSpeechSynthesis.randomOfflineVoice(installed, Locale.US, pick(0)));
        assertEquals(enGb, LocalSpeechSynthesis.randomOfflineVoice(installed, Locale.CANADA, pick(0)));
        assertNull(LocalSpeechSynthesis.randomOfflineVoice(installed, Locale.FRANCE, pick(0)));
    }

    private static LocalOptions withMarker(LocalOptions options, String source) {
        SpeechSettings settings = new SpeechSettings(options.withOverrides(Map.of(SpeechSettings.PROVIDER, source)));
        return options.withOverrides(Map.of(VoiceCatalogLabels.queryStorageKey(settings), VoiceCatalogLabels.queryKey(settings)));
    }

    private static LocalOptions allSourcesOptions() {
        Map<String, Object> values = new HashMap<>();
        values.put(SpeechSettings.RANDOM_MODE, SpeechSettings.RANDOM_ALL);
        values.put(SpeechSettings.GET_TEMPLATE, "https://example.test/tts?text={text}&voice={voice}");
        values.put(SpeechSettings.VOICE_SAMPLE, "[{\"id\":\"third-one\",\"name\":\"Third One\"}]");
        values.put(SpeechSettings.MINIMAX_API_KEY, "minimax-key");
        values.put(SpeechSettings.MINIMAX_VOICE_SAMPLE, "[{\"id\":\"mini-one\",\"name\":\"Mini One\"}]");
        values.put(BreezeSpeechSettings.API_KEY, "breeze-key");
        values.put(BreezeSpeechSettings.VOICE_TYPE, "default");
        values.put(BreezeSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"breeze-one\",\"name\":\"Breeze One\"}]");
        values.put(FishSpeechSettings.API_KEY, "fish-key");
        values.put(FishSpeechSettings.VOICE_SAMPLE, "[{\"id\":\"fish-one\",\"name\":\"Fish One\"}]");
        LocalOptions saved = new LocalOptions(values);
        for (String source : List.of("third_party", "minimax", "breeze", "fish")) saved = withMarker(saved, source);
        return saved;
    }

    private static Random pick(int index) {
        return new Random() {
            private int calls;
            @Override public int nextInt(int bound) {
                int selected = calls++ == 0 ? index : 0;
                assertTrue("requested index must be in the eligible pool", selected < bound);
                return selected;
            }
        };
    }
}
