package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;
import androidx.test.core.app.ApplicationProvider;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class ConfigTransferTest {
    @Test public void fishSettingsRoundTripWithoutMistakingAudioTokenBudgetForASecret() throws Exception {
        MemoryStore source = fishStore(), target = new MemoryStore();
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, true)));
        assertEquals(source.values, target.values);
        assertFalse(ConfigSecrets.sensitive(FishSpeechSettings.MAX_NEW_TOKENS));
        assertTrue(ConfigSecrets.sensitive(FishSpeechSettings.API_KEY));
        String redacted = ConfigTransfer.export(source, false);
        assertFalse(redacted.contains("fixture-fish-key"));
        JSONObject values = new JSONObject(redacted).getJSONObject("values");
        assertEquals("2048", values.getJSONObject(FishSpeechSettings.MAX_NEW_TOKENS).getString("value"));
        assertEquals("s2.1-pro-free", new FishSpeechSettings(new LocalOptions(source.values)).engine);
    }

    @Test public void fishSpeakerIdsArePortableButImportedAudioBindingsNeverLeaveThisDevice() throws Exception {
        MemoryStore source = new MemoryStore(), portable = new MemoryStore();
        source.values.put(FishSpeechSettings.VOICE_MODE, "multi");
        source.values.put(FishSpeechSettings.REFERENCE_IDS, "[\"voice-b\",\"voice-a\",\"voice-b\"]");
        String local = "[[{\"path\":\"/module/private-reference.wav\",\"text\":\"private exact reference transcript\",\"name\":\"本机音频\"}]]";
        source.values.put(FishSpeechSettings.REFERENCE_SAMPLES, local);
        for (boolean includeSecrets : new boolean[]{false, true}) {
            String backup = ConfigTransfer.export(source, includeSecrets);
            JSONObject values = new JSONObject(backup).getJSONObject("values");
            assertFalse(values.has(FishSpeechSettings.REFERENCE_SAMPLES));
            assertFalse(backup.contains("private-reference")); assertFalse(backup.contains("private exact reference transcript"));
            ConfigTransfer.apply(portable, ConfigTransfer.inspect(portable, backup));
            assertEquals("multi", portable.values.get(FishSpeechSettings.VOICE_MODE));
            assertEquals(source.values.get(FishSpeechSettings.REFERENCE_IDS), portable.values.get(FishSpeechSettings.REFERENCE_IDS));
            assertFalse(portable.values.containsKey(FishSpeechSettings.REFERENCE_SAMPLES));
        }
        MemoryStore sameDevice = new MemoryStore();
        sameDevice.values.put(FishSpeechSettings.REFERENCE_SAMPLES, local);
        for (ConfigTransfer.Mode mode : ConfigTransfer.Mode.values()) {
            ConfigTransfer.apply(sameDevice, ConfigTransfer.inspect(sameDevice, ConfigTransfer.export(source, false), mode));
            assertEquals(local, sameDevice.values.get(FishSpeechSettings.REFERENCE_SAMPLES));
        }
    }

    @Test public void aBackupCannotInjectAReferenceAudioPathEvenWhenItClaimsToIncludeSecrets() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        target.values.put(FishSpeechSettings.REFERENCE_SAMPLES, "[[{\"path\":\"/module/local.wav\",\"text\":\"local\"}]]");
        Map<String, Object> before = new LinkedHashMap<>(target.values);
        JSONObject incoming = new JSONObject(ConfigTransfer.export(source, true));
        incoming.getJSONObject("values").put(FishSpeechSettings.REFERENCE_SAMPLES,
                new JSONObject().put("type", "string").put("value", "[[{\"path\":\"/arbitrary/private.wav\",\"text\":\"private\"}]]"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
                () -> ConfigTransfer.inspect(target, incoming.toString()));
        assertFalse(failure.getMessage().contains("arbitrary")); assertEquals(before, target.values); assertEquals(0, target.commits);
    }

    @Test public void fishKeysRestoreOnlyForTheirOwnEndpointAndNeverBorrowOtherProviderKeys() {
        MemoryStore source = fishStore(), target = new MemoryStore();
        target.values.put(FishSpeechSettings.API_KEY, "local-fish-key");
        target.values.put(BreezeSpeechSettings.API_KEY, "local-breeze-key");
        target.values.put(SpeechSettings.MINIMAX_API_KEY, "local-minimax-key");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals("local-fish-key", target.values.get(FishSpeechSettings.API_KEY));
        assertEquals("local-breeze-key", target.values.get(BreezeSpeechSettings.API_KEY));
        assertEquals("local-minimax-key", target.values.get(SpeechSettings.MINIMAX_API_KEY));
        source.values.put(FishSpeechSettings.BASE_URL, "https://different-fish.example.test");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals("", target.values.get(FishSpeechSettings.API_KEY));
        assertEquals("local-breeze-key", target.values.get(BreezeSpeechSettings.API_KEY));
        assertEquals("local-minimax-key", target.values.get(SpeechSettings.MINIMAX_API_KEY));
    }

    @Test public void fishRuntimeCatalogsAreNotBackedUpAndInvalidParametersHaveControlledDiagnostics() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(FishSpeechSettings.VOICE_SAMPLE, "runtime-fish-voices");
        source.values.put(FishSpeechSettings.MODEL_SAMPLE, "runtime-fish-models");
        source.values.put(FishSpeechSettings.MODEL_SOURCE, "api");
        String valid = ConfigTransfer.export(source, true);
        assertFalse(valid.contains("runtime-fish-voices")); assertFalse(valid.contains("runtime-fish-models"));
        assertFalse(valid.contains(FishSpeechSettings.MODEL_SOURCE));
        String[][] invalid = {{FishSpeechSettings.ENGINE, "reference-voice-id"}, {FishSpeechSettings.VOLUME_DB, "21"},
                {FishSpeechSettings.FORMAT, "pcm"}, {FishSpeechSettings.MAX_NEW_TOKENS, "0"},
                {FishSpeechSettings.LANGUAGE, "中文"}, {FishSpeechSettings.PRONUNCIATION_DICTIONARY, "[{\"id\":\"missing-version\"}]"}};
        for (String[] row : invalid) {
            JSONObject incoming = new JSONObject(valid);
            incoming.getJSONObject("values").put(row[0], new JSONObject().put("type", "string").put("value", row[1]));
            assertDiagnostic(target, incoming.toString(), BackupValidationException.Reason.FISH_OPTIONS, row[0]);
        }
        source.values.put(FishSpeechSettings.FORMAT, "opus");
        target.values.put(FishSpeechSettings.SAMPLE_RATE, "44100");
        assertDiagnostic(target, ConfigTransfer.export(source, true), BackupValidationException.Reason.FISH_OPTIONS, "");
        assertEquals("44100", target.values.get(FishSpeechSettings.SAMPLE_RATE));
    }

    @Test public void breezeSettingsRoundTripAndRedactedKeysStayBoundToTheBreezeEndpoint() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        source.values.put(BreezeSpeechSettings.API_KEY, "fixture-breeze-key");
        source.values.put(BreezeSpeechSettings.BASE_URL, BreezeSpeechSettings.DEFAULT_BASE_URL);
        source.values.put(BreezeSpeechSettings.MODEL, "breeze-tts-2-multilingual");
        source.values.put(BreezeSpeechSettings.VOICE_ID, "voc_fixture");
        source.values.put(BreezeSpeechSettings.VOICE_NAME, "测试音色");
        source.values.put(BreezeSpeechSettings.VOICE_TYPE, "personal");
        source.values.put(BreezeSpeechSettings.VOICE_SEARCH, "自然 女声");
        source.values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "ja");
        source.values.put(BreezeSpeechSettings.SPEED, "1.5");
        source.values.put(BreezeSpeechSettings.VOLUME, "0.8");
        source.values.put(BreezeSpeechSettings.GUIDANCE_SCALE, "5");
        source.values.put(BreezeSpeechSettings.SPEAKER_BOOST, "true");
        source.values.put(BreezeSpeechSettings.INSTRUCTIONS, "自然朗读，保留段间停顿。\n不要急促。");
        source.values.put(BreezeSpeechSettings.LANGUAGE_CODE, "zh");
        source.values.put(BreezeSpeechSettings.OUTPUT_FORMAT, "mp3_44100_128");
        source.values.put(BreezeSpeechSettings.DELIVERY, "stream");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, true)));
        assertEquals(source.values, target.values);
        target.values.put(BreezeSpeechSettings.API_KEY, "local-breeze-key");
        target.values.put(SpeechSettings.MINIMAX_API_KEY, "local-minimax-key");
        String redacted = ConfigTransfer.export(source, false);
        assertFalse(redacted.contains("fixture-breeze-key"));
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, redacted));
        assertEquals("local-breeze-key", target.values.get(BreezeSpeechSettings.API_KEY));
        assertEquals("local-minimax-key", target.values.get(SpeechSettings.MINIMAX_API_KEY));
        source.values.put(BreezeSpeechSettings.BASE_URL, "https://different-breeze.example.test");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals("", target.values.get(BreezeSpeechSettings.API_KEY));
        assertEquals("local-minimax-key", target.values.get(SpeechSettings.MINIMAX_API_KEY));
    }

    @Test public void breezeAutomaticModelAndAsyncDeliveryRoundTripWhileInvalidStreamCombinationsRejectAtomically() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(BreezeSpeechSettings.MODEL, BreezeSpeechSettings.AUTO_MODEL);
        source.values.put(BreezeSpeechSettings.DELIVERY, "async");
        source.values.put(BreezeSpeechSettings.OUTPUT_FORMAT, "flac_48000");
        source.values.put(BreezeSpeechSettings.LANGUAGE_CODE, "ja");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(source.values, target.values);
        MemoryStore invalidSource = new MemoryStore(); invalidSource.values.put(BreezeSpeechSettings.DELIVERY, "stream");
        assertDiagnostic(target, ConfigTransfer.export(invalidSource, false), BackupValidationException.Reason.BREEZE_OPTIONS, "");
        assertEquals(source.values, target.values);
    }

    @Test public void breezeCatalogCachesStayOutOfBackupsAndInvalidFieldsHaveProviderSpecificDiagnostics() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(BreezeSpeechSettings.VOICE_SAMPLE, "runtime-breeze-voices");
        source.values.put(BreezeSpeechSettings.MODEL_SAMPLE, "runtime-breeze-models");
        source.values.put(BreezeSpeechSettings.MODEL_SOURCE, "api");
        String valid = ConfigTransfer.export(source, true);
        assertFalse(valid.contains("runtime-breeze-voices")); assertFalse(valid.contains("runtime-breeze-models"));
        assertFalse(valid.contains(BreezeSpeechSettings.MODEL_SOURCE));
        String[][] invalid = {{BreezeSpeechSettings.SPEED, "4"}, {BreezeSpeechSettings.VOLUME, "0"},
                {BreezeSpeechSettings.GUIDANCE_SCALE, "11"}, {BreezeSpeechSettings.SPEAKER_BOOST, "yes"},
                {BreezeSpeechSettings.BASE_URL, "https://user:secret@example.test"},
                {BreezeSpeechSettings.LANGUAGE_CODE, "zh-CN"}, {BreezeSpeechSettings.MODEL, "m".repeat(121)}};
        for (String[] item : invalid) {
            JSONObject incoming = new JSONObject(valid);
            incoming.getJSONObject("values").put(item[0], new JSONObject().put("type", "string").put("value", item[1]));
            assertDiagnostic(target, incoming.toString(), BackupValidationException.Reason.BREEZE_OPTIONS, item[0]);
            assertTrue(target.values.isEmpty());
        }
        source.values.put(BreezeSpeechSettings.MODEL, "breeze-tts-2");
        target.values.put(BreezeSpeechSettings.LANGUAGE_CODE, "ja");
        assertDiagnostic(target, ConfigTransfer.export(source, true), BackupValidationException.Reason.BREEZE_OPTIONS, "");
        assertEquals("ja", target.values.get(BreezeSpeechSettings.LANGUAGE_CODE));
    }

    @Test public void localAnalysisModelAndSecondaryPromptRoundTripIndependentlyFromAutomation() throws Exception {
        for (boolean includeSecrets : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            AiModelConfig global = AiModelConfig.fromJson(new JSONObject().put("id", "global").put("name", "全局供应商")
                    .put("apiEndpoint", "https://global.example.test/v1").put("modelName", "global-model"));
            AiModelConfig analysis = AiModelConfig.fromJson(new JSONObject().put("id", "analysis").put("name", "分析供应商")
                    .put("apiEndpoint", "https://analysis.example.test/v1").put("modelName", "analysis-model")
                    .put("systemPrompt", "分析模型一级提示").put("responseMode", AiModelConfig.RESPONSE_STREAM));
            source.values.put(AiModelSettings.KEY, new AiModelSettings(java.util.List.of(global, analysis), global.id).serialize());
            source.values.put(AiModelSettings.REPLY_MODEL_ID, global.id);
            source.values.put(AiModelSettings.COMMENT_MODEL_ID, global.id);
            source.values.put("reply_prompt", "独立回复提示");
            source.values.put("comment_prompt", "独立评论提示");
            source.values.put(AnalyticsAiSettings.MODEL_ID, analysis.id);
            source.values.put(AnalyticsAiSettings.SECONDARY_PROMPT, "独立分析提示\n重点比较工作日与周末");
            ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, includeSecrets)));
            assertEquals(analysis.id, AnalyticsAiSettings.models(target).selected().id);
            assertTrue(AnalyticsAiSettings.models(target).selected().streaming());
            assertEquals("分析模型一级提示", AnalyticsAiSettings.models(target).selected().systemPrompt);
            assertEquals(source.getString(AnalyticsAiSettings.SECONDARY_PROMPT, ""), AnalyticsAiSettings.secondaryPrompt(target));
            assertEquals(global.id, AiModelSettings.read(target).activeId);
            assertEquals(global.id, target.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals(global.id, target.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertEquals("独立回复提示", target.getString("reply_prompt", ""));
            assertEquals("独立评论提示", target.getString("comment_prompt", ""));
        }
    }

    @Test public void automationModelBindingsRoundTripWithTheExistingProfileCollection() throws Exception {
        for (boolean includeSecrets : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            AiModelConfig first = AiModelConfig.fromJson(new JSONObject().put("id", "first").put("name", "回复供应商")
                    .put("apiEndpoint", "https://first.example.test/v1").put("modelName", "reply-model")
                    .put("responseMode", AiModelConfig.RESPONSE_STREAM));
            AiModelConfig second = AiModelConfig.fromJson(new JSONObject().put("id", "second").put("name", "评论供应商")
                    .put("apiEndpoint", "https://second.example.test/v1").put("modelName", "comment-model")
                    .put("responseMode", AiModelConfig.RESPONSE_SYNC));
            source.values.put(AiModelSettings.KEY, new AiModelSettings(java.util.List.of(first, second), first.id).serialize());
            source.values.put(AiModelSettings.REPLY_MODEL_ID, first.id);
            source.values.put(AiModelSettings.COMMENT_MODEL_ID, second.id);
            ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, includeSecrets)));
            assertEquals(first.id, target.getString(AiModelSettings.REPLY_MODEL_ID, ""));
            assertEquals(second.id, target.getString(AiModelSettings.COMMENT_MODEL_ID, ""));
            assertTrue(AiModelSettings.readForAutomation(target, "reply").selected().streaming());
            assertFalse(AiModelSettings.readForAutomation(target, "comment").selected().streaming());
            assertEquals(first.id, AiModelSettings.read(target).activeId);
        }
    }

    @Test public void independentCardFiltersSurviveBackupAndRestore() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("feed_filter_enabled", true);
        source.values.put("hide_product_cards", true);
        source.values.put("hide_hotspot_rank_cards", false);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(source.values, target.values);
        assertTrue(new LocalOptions(target.snapshot()).enabled("hide_product_cards"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("hide_hotspot_rank_cards"));
    }

    @Test public void miniMaxMixedTransportSettingsRoundTripAndMergedValidationRejectsBrokenCombinations() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        source.values.put(SpeechSettings.MINIMAX_VOICE_ID, "");
        source.values.put(MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "first/20\nsecond/30");
        source.values.put(MiniMaxSpeechOptions.OUTPUT_FORMAT, "hex");
        source.values.put(MiniMaxSpeechOptions.AUDIO_FORMAT, "opus");
        String encoded = ConfigTransfer.export(source, false);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, encoded));
        assertEquals(source.values, target.values);
        assertTrue(new SpeechSettings(new LocalOptions(target.snapshot())).minimaxOptions.hasMixedVoices());
        String[][] changes = {{MiniMaxSpeechOptions.TIMBRE_WEIGHTS, ""},
                {MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "first/20\nfirst/30"},
                {MiniMaxSpeechOptions.TIMBRE_WEIGHTS, "first/0"},
                {MiniMaxSpeechOptions.OUTPUT_FORMAT, "base64"},
                {MiniMaxSpeechOptions.MODIFY_PITCH, "1"}};
        for (String[] change : changes) {
            JSONObject invalid = new JSONObject(encoded);
            invalid.getJSONObject("values").put(change[0], new JSONObject().put("type", "string").put("value", change[1]));
            assertThrows(change[0], IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, invalid.toString()));
            assertEquals(source.values, target.values);
        }
    }

    @Test public void experimentalApiAacCanBeBackedUpButRawMuLawRemainsUnsupported() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(MiniMaxSpeechOptions.AUDIO_FORMAT, "aac");
        source.values.put(MiniMaxSpeechOptions.OUTPUT_FORMAT, "hex");
        String encoded = ConfigTransfer.export(source, false);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, encoded));
        assertEquals(source.values, target.values);
        JSONObject request = new JSONObject();
        new SpeechSettings(new LocalOptions(target.snapshot())).minimaxOptions.applyTo(request);
        assertEquals("aac", request.getJSONObject("audio_setting").getString("format"));
        JSONObject raw = new JSONObject(encoded);
        raw.getJSONObject("values").getJSONObject(MiniMaxSpeechOptions.AUDIO_FORMAT).put("value", "pcmu_raw");
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, raw.toString()));
        assertEquals(source.values, target.values);
    }

    @Test public void miniMaxSettingsRoundTripAndSecretFreeBackupsBindKeysToTheirOwnEndpoint() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        source.values.put(SpeechSettings.MINIMAX_API_KEY, "fixture-minimax-key");
        source.values.put(SpeechSettings.MINIMAX_BASE_URL, SpeechSettings.DEFAULT_MINIMAX_BASE_URL);
        source.values.put(SpeechSettings.MINIMAX_MODEL, "speech-2.8-hd");
        source.values.put(SpeechSettings.MINIMAX_VOICE_ID, "male-qn-qingse");
        source.values.put(SpeechSettings.MINIMAX_VOICE_NAME, "青涩青年");
        source.values.put(SpeechSettings.MINIMAX_SPEED, "1.5");
        source.values.put(SpeechSettings.MINIMAX_VOLUME, "8");
        source.values.put(SpeechSettings.MINIMAX_PITCH, "-12");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, true)));
        assertEquals(source.values, target.values);
        target.values.put(SpeechSettings.MINIMAX_API_KEY, "local-minimax-key");
        String redacted = ConfigTransfer.export(source, false);
        assertFalse(redacted.contains("fixture-minimax-key"));
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, redacted));
        assertEquals("local-minimax-key", target.values.get(SpeechSettings.MINIMAX_API_KEY));
        source.values.put(SpeechSettings.MINIMAX_BASE_URL, "https://new.example.test");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals("", target.values.get(SpeechSettings.MINIMAX_API_KEY));
        assertFalse(target.values.toString().contains("local-minimax-key"));
    }

    @Test public void miniMaxBackupRejectsInvalidFieldsAndKeepsRuntimeVoiceCatalogOutOfExport() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        source.values.put(SpeechSettings.MINIMAX_VOICE_SAMPLE, "runtime-minimax-catalog");
        source.values.put(SpeechSettings.MINIMAX_MODEL_SAMPLE, "runtime-model-catalog");
        source.values.put(SpeechSettings.MINIMAX_MODEL_SOURCE, "api");
        String encoded = ConfigTransfer.export(source, true);
        assertFalse(encoded.contains("runtime-minimax-catalog"));
        assertFalse(encoded.contains("runtime-model-catalog"));
        assertFalse(encoded.contains(SpeechSettings.MINIMAX_MODEL_SOURCE));
        String[][] invalidValues = {{SpeechSettings.PROVIDER, "unknown"}, {SpeechSettings.MINIMAX_BASE_URL, "bad-url"},
                {SpeechSettings.MINIMAX_MODEL, ""}, {SpeechSettings.MINIMAX_VOICE_ID, ""},
                {SpeechSettings.MINIMAX_SPEED, "0.49"}, {SpeechSettings.MINIMAX_VOLUME, "0"}, {SpeechSettings.MINIMAX_PITCH, "0.5"}};
        for (String[] invalid : invalidValues) {
            JSONObject backup = new JSONObject(encoded);
            backup.getJSONObject("values").put(invalid[0], new JSONObject().put("type", "string").put("value", invalid[1]));
            assertThrows(invalid[0], IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, backup.toString()));
            assertTrue(target.values.isEmpty());
        }
    }

    @Test public void miniMaxAdvancedOptionsRoundTripAsValidatedSettingsWithoutSerializingCatalogCaches() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX);
        source.values.put(SpeechSettings.MINIMAX_MODEL, "speech-2.8-turbo");
        source.values.put(MiniMaxSpeechOptions.EMOTION, "happy");
        source.values.put(MiniMaxSpeechOptions.LANGUAGE_BOOST, "Chinese");
        source.values.put(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality");
        source.values.put(MiniMaxSpeechOptions.AUDIO_FORMAT, "wav");
        source.values.put(MiniMaxSpeechOptions.SAMPLE_RATE, "44100");
        source.values.put(MiniMaxSpeechOptions.BITRATE, "256000");
        source.values.put(MiniMaxSpeechOptions.CHANNEL, "2");
        source.values.put(MiniMaxSpeechOptions.MODIFY_PITCH, "-50");
        source.values.put(MiniMaxSpeechOptions.MODIFY_INTENSITY, "0");
        source.values.put(MiniMaxSpeechOptions.MODIFY_TIMBRE, "100");
        source.values.put(MiniMaxSpeechOptions.SOUND_EFFECT, "spacious_echo");
        source.values.put(MiniMaxSpeechOptions.PRONUNCIATION_TONE, "处理/(chu3)(li3)\n世界/world");
        source.values.put(MiniMaxSpeechOptions.LATEX_READ, "true");
        source.values.put(MiniMaxSpeechOptions.AIGC_WATERMARK, "false");
        String encoded = ConfigTransfer.export(source, false);
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, encoded));
        assertEquals(source.values, target.values);
        JSONObject request = new JSONObject();
        new SpeechSettings(new LocalOptions(target.snapshot())).minimaxOptions.applyTo(request);
        assertEquals("quality", request.getString("text_normalization_mode"));
        assertEquals("wav", request.getJSONObject("audio_setting").getString("format"));
        assertFalse(request.getJSONObject("audio_setting").has("bitrate"));
        assertTrue(request.getJSONObject("voice_setting").getBoolean("latex_read"));
    }

    @Test public void miniMaxBackupsRejectIncompatibleMergedOptionsBeforeApplying() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.MINIMAX_MODEL, "speech-2.8-hd");
        source.values.put(MiniMaxSpeechOptions.TEXT_NORMALIZATION_MODE, "quality");
        JSONObject backup = new JSONObject(ConfigTransfer.export(source, true));
        backup.getJSONObject("values").getJSONObject(SpeechSettings.MINIMAX_MODEL).put("value", "speech-01-hd");
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, backup.toString()));
        assertTrue(target.values.isEmpty());
        source.values.clear();
        source.values.put(MiniMaxSpeechOptions.LATEX_READ, "true");
        target.values.put(MiniMaxSpeechOptions.LANGUAGE_BOOST, "English");
        String incoming = ConfigTransfer.export(source, true);
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, incoming));
        assertEquals(1, target.values.size());
        assertEquals("English", target.values.get(MiniMaxSpeechOptions.LANGUAGE_BOOST));
    }

    @Test public void contactProfileRoundTripsWithAvatarInRedactedBackup() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        AiModelConfig contact = AiModelConfig.fromJson(new JSONObject().put("id", "ai-contact")
                .put("name", "Configured model").put("apiEndpoint", "https://api.test/v1")
                .put("modelName", "model").put("apiKey", "fixture-secret")
                .put("responseMode", AiModelConfig.RESPONSE_STREAM)
                .put("reasoningEffort", "high")
                .put("messageContactEnabled", true).put("messageContactName", "小助手")
                .put("messageContactAvatar", AiModelContactConfigTest.AVATAR));
        source.values.put(AiModelSettings.KEY, new AiModelSettings(java.util.List.of(contact), contact.id).serialize());
        String encoded = ConfigTransfer.export(source, false);
        assertFalse(encoded.contains("fixture-secret"));
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, encoded));
        AiModelConfig restored = AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).selected();
        assertTrue(restored.messageContactEnabled);
        assertEquals("小助手", restored.messageContactDisplayName());
        assertEquals(AiModelContactConfigTest.AVATAR, restored.messageContactAvatar);
        assertTrue(restored.streaming());
        assertEquals("high", restored.reasoningEffort);
        assertEquals("", restored.apiKey);
    }

    @Test public void oldModelBackupsWithoutResponseModeRemainImportableAndDefaultToNonStreaming() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        JSONObject settings = new JSONObject(model("https://api.test/v1", "fixture-key", "{}", "{}"));
        settings.getJSONArray("configs").getJSONObject(0).remove("responseMode");
        settings.getJSONArray("configs").getJSONObject(0).remove("reasoningEffort");
        source.values.put(AiModelSettings.KEY, settings.toString());
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, true)));
        AiModelConfig restored = AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).selected();
        assertFalse(restored.streaming());
        assertFalse(restored.hasReasoningEffort());
    }

    @Test public void backupRejectsUnknownResponseModesBeforeChangingAnySettings() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-key", "{}", "{}"));
        JSONObject backup = new JSONObject(ConfigTransfer.export(source, true));
        JSONObject entry = backup.getJSONObject("values").getJSONObject(AiModelSettings.KEY);
        JSONObject settings = new JSONObject(entry.getString("value"));
        settings.getJSONArray("configs").getJSONObject(0).put("responseMode", "auto");
        entry.put("value", settings.toString());
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, backup.toString()));
        assertTrue(target.values.isEmpty());
    }

    @Test public void backupRejectsUnknownReasoningEffortBeforeChangingAnySettings() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-key", "{}", "{}"));
        JSONObject backup = new JSONObject(ConfigTransfer.export(source, true));
        JSONObject entry = backup.getJSONObject("values").getJSONObject(AiModelSettings.KEY);
        JSONObject settings = new JSONObject(entry.getString("value"));
        settings.getJSONArray("configs").getJSONObject(0).put("reasoningEffort", "ultra");
        entry.put("value", settings.toString());
        assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, backup.toString()));
        assertTrue(target.values.isEmpty());
    }

    @Test public void fullBackupRoundTripsTypesAndAllConfigurationFamilies() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("download_enabled", true);
        source.values.put("minimum_duration_seconds", 13);
        source.values.put("tts_rate", "1.4");
        source.values.put("reply_targets", "101,202");
        source.values.put("component_alpha_like", "42");
        source.values.put("component_hide_custom_views", "{\"version\":2,\"items\":[]}");
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "fixture-key", "{}", "{}"));
        MemoryStore target = new MemoryStore();
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, true));
        assertTrue(preview.includesSecrets); assertEquals(7, preview.entryCount); assertEquals(0, target.commits);
        ConfigTransfer.apply(target, preview);
        assertEquals(source.values, target.values); assertEquals(1, target.commits);
    }

    @Test public void exportNeverIncludesRuntimeHistoryConsentOrExecutionCommands() throws Exception {
        MemoryStore source = new MemoryStore(); source.values.put("download_enabled", true);
        for (String key : new String[]{"streak_reset_token", "streak_retry_unknown_token", "douai_host_settings_initialized",
                "confirmed_identity", "relation_lists_refresh_token", "third_party_response_sample", "tts_voice_sample",
                "douai_automation_history", "streak.sent.123", "host_adaptation_static_verified"}) source.values.put(key, "excluded-fixture");
        String encoded = ConfigTransfer.export(source, true);
        assertFalse(encoded.contains("excluded-fixture"));
        assertEquals(1, new JSONObject(encoded).getJSONObject("values").length());
    }

    @Test public void officialDirectDownloadBooleanRoundTripsWhileThirdPartyMethodRemainsSelected() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, enabled);
            source.values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            target.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, !enabled);
            String encoded = ConfigTransfer.export(source, false);
            JSONObject typed = new JSONObject(encoded).getJSONObject("values")
                    .getJSONObject(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT);
            assertEquals("boolean", typed.getString("type"));
            assertTrue(typed.get("value") instanceof Boolean);
            assertEquals(enabled, typed.getBoolean("value"));

            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            assertEquals(2, preview.entryCount);
            assertTrue(preview.changedKeys.contains(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
            assertEquals(0, target.commits);
            ConfigTransfer.apply(target, preview);
            assertEquals(source.values, target.values);
            assertEquals(enabled, new LocalOptions(target.snapshot()).officialDownloadDirect());
            assertTrue(new ThirdPartyDownloadSettings(new LocalOptions(target.snapshot())).thirdParty);
            assertEquals(1, target.commits);
        }
    }

    @Test public void commentReplyReceptionAndSharedReplySettingsRoundTripWithoutLosingLegacyValues() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(LocalOptions.COMMENT_REPLY_ENABLED, true);
        source.values.put("comment_reply_prompt", "结合原评论，简洁回复对方");
        source.values.put("comment_reply_daily_limit", "20");
        source.values.put("comment_reply_user_cooldown_minutes", "1");
        source.values.put("comment_reply_thread_limit", "3");
        source.values.put("comment_reply_tip_enabled", false);
        source.values.put("comment_reply_tip_seconds", "3");
        source.values.put("comment_reply_failure_notify", true);
        source.values.put("native_auto_reply", true);
        source.values.put("native_auto_comment", false);
        source.values.put("reply_mode", "ai");
        source.values.put("reply_prompt", "共享二级提示词");
        source.values.put("auto_message_text", "共享固定正文");
        source.values.put("reply_daily_limit", "7");
        source.values.put("reply_user_cooldown_minutes", "2");
        source.values.put("reply_tip", true);
        source.values.put("reply_notice_seconds", "8");
        source.values.put("reply_save_history", true);
        source.values.put("reply_voice", true);
        source.values.put("reply_private_chats", false);
        source.values.put("reply_group_chats", false);
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertEquals(source.values.size(), preview.entryCount);
        assertTrue(preview.changedKeys.contains(LocalOptions.COMMENT_REPLY_ENABLED));
        assertTrue(preview.changedKeys.contains("comment_reply_prompt"));
        ConfigTransfer.apply(target, preview);
        assertEquals(source.values, target.values);
        assertTrue(new LocalOptions(target.snapshot()).enabled(LocalOptions.COMMENT_REPLY_ENABLED));
        assertEquals(20, new LocalOptions(target.snapshot()).integer("comment_reply_daily_limit", 0));
        assertTrue(new LocalOptions(target.snapshot()).enabled("native_auto_reply"));
        assertEquals("ai", new LocalOptions(target.snapshot()).text("reply_mode", ""));
        assertEquals("共享二级提示词", new LocalOptions(target.snapshot()).text("reply_prompt", ""));
        assertEquals(7, new LocalOptions(target.snapshot()).integer("reply_daily_limit", 0));
        assertTrue(new LocalOptions(target.snapshot()).enabled("reply_save_history"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("reply_private_chats"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("reply_group_chats"));
        assertFalse(new LocalOptions(target.snapshot()).enabled("native_auto_comment"));
    }

    @Test public void replyRecognitionBooleansRoundTripIndependentlyOfWorkCommentRecognition() throws Exception {
        for (boolean images : new boolean[]{false, true}) {
            for (boolean video : new boolean[]{false, true}) {
                MemoryStore source = new MemoryStore(), target = new MemoryStore();
                source.values.put(LocalOptions.REPLY_RECOGNIZE_IMAGES, images);
                source.values.put(LocalOptions.REPLY_RECOGNIZE_VIDEO, video);
                source.values.put("comment_recognize_images", !images);
                source.values.put("comment_recognize_video", !video);
                String encoded = ConfigTransfer.export(source, false);
                JSONObject transferred = new JSONObject(encoded).getJSONObject("values");
                assertEquals("boolean", transferred.getJSONObject(LocalOptions.REPLY_RECOGNIZE_IMAGES).getString("type"));
                assertEquals("boolean", transferred.getJSONObject(LocalOptions.REPLY_RECOGNIZE_VIDEO).getString("type"));
                ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
                assertEquals(4, preview.entryCount);
                ConfigTransfer.apply(target, preview);
                assertEquals(source.values, target.values);
                LocalOptions restored = new LocalOptions(target.snapshot());
                assertEquals(images, restored.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true));
                assertEquals(video, restored.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false));
                assertEquals(!images, restored.enabled("comment_recognize_images", true));
                assertEquals(!video, restored.enabled("comment_recognize_video", false));
            }
        }
    }

    @Test public void thirdPartyDirectDownloadBooleanTransfersIndependentlyOfOfficialDefaultBehavior() throws Exception {
        for (boolean enabled : new boolean[]{false, true}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put(ThirdPartyDownloadSettings.DIRECT, enabled);
            source.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, !enabled);
            source.values.put(ThirdPartyDownloadSettings.METHOD, ThirdPartyDownloadSettings.THIRD_PARTY);
            target.values.put(ThirdPartyDownloadSettings.DIRECT, !enabled);
            target.values.put(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT, enabled);
            String encoded = ConfigTransfer.export(source, false);
            JSONObject typed = new JSONObject(encoded).getJSONObject("values")
                    .getJSONObject(ThirdPartyDownloadSettings.DIRECT);
            assertEquals("boolean", typed.getString("type"));
            assertTrue(typed.get("value") instanceof Boolean);
            assertEquals(enabled, typed.getBoolean("value"));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            assertEquals(3, preview.entryCount);
            assertTrue(preview.changedKeys.contains(ThirdPartyDownloadSettings.DIRECT));
            assertTrue(preview.changedKeys.contains(LocalOptions.OFFICIAL_DOWNLOAD_DIRECT));
            ConfigTransfer.apply(target, preview);
            assertEquals(source.values, target.values);
            LocalOptions restored = new LocalOptions(target.snapshot());
            assertEquals(enabled, new ThirdPartyDownloadSettings(restored).direct);
            assertEquals(!enabled, restored.officialDownloadDirect());
            assertTrue(new ThirdPartyDownloadSettings(restored).thirdParty);
        }
    }

    @Test public void redactionCoversModelsArbitraryHeadersParametersAndUrlCredentials() throws Exception {
        MemoryStore source = new MemoryStore();
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "fixture-model-secret",
                "{\"X-Tenant\":\"fixture-custom-header\",\"Accept\":\"application/json\"}",
                "{\"nonstandardCredential\":\"fixture-custom-param\",\"temperature\":0.3}"));
        source.values.put("third_party_get_template", "https://download.test/api/appSecret/fixture-path-secret?other=fixture-query-secret&url={url}");
        source.values.put("third_party_headers", "{\"X-Whatever\":\"fixture-download-header\",\"Content-Type\":\"application/json\"}");
        source.values.put("third_party_app_secret", "fixture-app-secret");
        source.values.put("tts_get_template", "https://speech.test/fixture-opaque-path?apikey=fixture-tts-secret&text={text}");
        source.values.put("tts_voice_headers", "{\"Cookie\":\"fixture-cookie-secret\"}");
        String encoded = ConfigTransfer.export(source, false);
        for (String secret : new String[]{"fixture-model-secret", "fixture-custom-header", "fixture-custom-param", "fixture-path-secret",
                "fixture-query-secret", "fixture-download-header", "fixture-app-secret", "fixture-opaque-path", "fixture-tts-secret", "fixture-cookie-secret"}) assertFalse(secret, encoded.contains(secret));
        JSONObject exportedValues = new JSONObject(encoded).getJSONObject("values");
        assertEquals("application/json", new JSONObject(exportedValues.getJSONObject("third_party_headers").getString("value")).getString("Content-Type"));
        assertTrue(encoded.contains("temperature"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(new MemoryStore(), encoded);
        assertFalse(preview.includesSecrets); assertTrue(preview.redactedCount >= 10); assertTrue(preview.missingSecretsCount >= 10);
    }

    @Test public void secretFreeImportKeepsLocalSecretsOnlyForTheSameModelAndEndpoint() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("ai_model_settings_v2", model("https://api.test/v1", "source-secret", "{\"X-Auth\":\"source-header\"}", "{}"));
        target.values.put("ai_model_settings_v2", model("https://api.test/v1", "local-secret", "{\"X-Auth\":\"local-header\"}", "{}"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        AiModelConfig result = AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0);
        assertEquals("local-secret", result.apiKey); assertEquals("local-header", result.headers().get("X-Auth"));
        assertEquals(0, preview.missingSecretsCount);
    }

    @Test public void modelTokenBudgetsKeepTheirTypesAndNestedOpaqueCredentialsAreRedacted() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-secret", "{}",
                "{\"max_tokens\":2048,\"temperature\":0.5,\"opaqueList\":[\"fixture-array-secret\",37,{\"tenant\":\"fixture-nested-secret\"}]}"));
        String encoded = ConfigTransfer.export(source, false);
        assertFalse(encoded.contains("fixture-array-secret")); assertFalse(encoded.contains("fixture-nested-secret"));
        JSONObject model = new JSONObject(new JSONObject(encoded).getJSONObject("values").getJSONObject(AiModelSettings.KEY)
                .getString("value")).getJSONArray("configs").getJSONObject(0);
        assertEquals(1024, model.getInt("maxTokens")); assertFalse(model.getBoolean("maxTokensEnabled"));
        assertEquals(2048, new JSONObject(model.getString("customParameters")).getInt("max_tokens"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
        ConfigTransfer.apply(target, preview);
        assertEquals(1024, AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0).maxTokens);
        assertTrue(preview.missingSecretsCount >= 4);
    }

    @Test public void differentModelEndpointNeverReceivesAnExistingKey() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://new.test/v1", "source-secret", "{\"X-Auth\":\"source-header\"}", "{}"));
        target.values.put(AiModelSettings.KEY, model("https://old.test/v1", "local-secret", "{\"X-Auth\":\"local-header\"}", "{}"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        String result = (String) target.values.get(AiModelSettings.KEY);
        assertFalse(result.contains("local-secret")); assertFalse(result.contains("local-header"));
        assertTrue(preview.missingSecretsCount >= 2);
    }

    @Test public void changedServiceClearsInheritedSecretsEvenWhenBackupHasNoHeaders() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("third_party_get_template", "https://new.test/api/parse?url={url}");
        target.values.put("third_party_get_template", "https://old.test/api/parse?url={url}");
        target.values.put("third_party_headers", "{\"X-Unknown\":\"local-secret\"}");
        target.values.put("third_party_app_secret", "local-secret");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        assertFalse(target.values.toString().contains("local-secret")); assertEquals(2, preview.missingSecretsCount);
    }

    @Test public void sameServiceRetainsHeadersNotPresentInSecretFreeBackup() {
        for (String emptyHeaders : new String[]{"{}", ""}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            String endpoint = "https://speech.test/api?text={text}";
            source.values.put("tts_get_template", endpoint); source.values.put("tts_headers", emptyHeaders);
            target.values.put("tts_get_template", endpoint); target.values.put("tts_headers", "{\"X-Custom\":\"local-secret\"}");
            ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
            assertTrue(target.values.get("tts_headers").toString().contains("local-secret"));
        }
    }

    @Test public void bareQueryCredentialsAreRedactedAndTheBackupRemainsReadable() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("tts_get_template", "https://speech.test/api?opaque-secret&text={text}");
        String encoded = ConfigTransfer.export(source, false);
        assertFalse(encoded.contains("opaque-secret"));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
        ConfigTransfer.apply(target, preview);
        assertEquals(1, preview.redactedCount); assertEquals(1, preview.missingSecretsCount);
        assertFalse(target.values.toString().contains("opaque-secret"));
    }

    @Test public void invalidEndpointFieldsAreOmittedWithoutScanningOrdinaryFreeText() {
        for (String value : new String[]{"opaque-invalid-secret", "ftp://fixture-secret@example.test/api", "https://broken host/fixture-secret"}) {
            MemoryStore source = new MemoryStore(), target = new MemoryStore();
            source.values.put("tts_get_template", value);
            String encoded = ConfigTransfer.export(source, false);
            assertFalse(encoded.contains("fixture-secret")); assertFalse(encoded.contains("opaque-invalid-secret"));
            ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, encoded);
            ConfigTransfer.apply(target, preview);
            assertEquals("", target.values.get("tts_get_template"));
            assertEquals(1, preview.redactedCount); assertEquals(1, preview.missingSecretsCount);
        }
    }

    @Test public void ordinaryReplyTextAndModelPromptsKeepTheirPublicUrls() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        String text = "请阅读 https://example.test/articles/public-information?section=news";
        source.values.put("reply_prompt", text); source.values.put("auto_message_text", text);
        JSONObject models = new JSONObject(model("https://api.test/v1", "fixture-key", "{}", "{}"));
        models.getJSONArray("configs").getJSONObject(0).put("systemPrompt", text);
        source.values.put(AiModelSettings.KEY, models.toString());
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(text, target.values.get("reply_prompt")); assertEquals(text, target.values.get("auto_message_text"));
        assertEquals(text, AiModelSettings.parse((String) target.values.get(AiModelSettings.KEY)).configs.get(0).systemPrompt);
    }

    @Test public void appIdIsPartOfThirdPartyCredentialIdentityEvenWithTheSameTemplate() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        String endpoint = "https://download.test/api/parse?app_id={app_id}&url={url}";
        source.values.put("third_party_get_template", endpoint); source.values.put("third_party_app_id", "new-app");
        source.values.put("third_party_app_secret", "source-secret");
        target.values.put("third_party_get_template", endpoint); target.values.put("third_party_app_id", "old-app");
        target.values.put("third_party_app_secret", "old-secret");
        target.values.put("third_party_headers", "{\"X-Auth\":\"old-header\"}");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        ConfigTransfer.apply(target, preview);
        assertEquals("new-app", target.values.get("third_party_app_id"));
        assertEquals("", target.values.get("third_party_app_secret"));
        assertFalse(target.values.toString().contains("old-header")); assertTrue(preview.missingSecretsCount >= 2);
    }

    @Test public void mergeKeepsOtherSettingsAndReplaceKeepsLocalRuntimeState() {
        MemoryStore source = new MemoryStore(), merge = new MemoryStore(), replace = new MemoryStore();
        source.values.put("download_enabled", true);
        for (MemoryStore target : new MemoryStore[]{merge, replace}) {
            target.values.put("auto_play", true); target.values.put("streak_reset_token", "local-command");
            target.values.put("douai_host_settings_initialized", true);
        }
        String encoded = ConfigTransfer.export(source, false);
        ConfigTransfer.apply(merge, ConfigTransfer.inspect(merge, encoded));
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(replace, encoded, ConfigTransfer.Mode.REPLACE);
        ConfigTransfer.apply(replace, preview);
        assertEquals(true, merge.values.get("auto_play")); assertFalse(replace.values.containsKey("auto_play"));
        assertEquals("local-command", replace.values.get("streak_reset_token"));
        assertEquals(true, replace.values.get("douai_host_settings_initialized")); assertEquals(1, preview.removedCount);
    }

    @Test public void unknownOrOperationalKeysAndWrongDeclaredOrActualTypesRejectWholeFile() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); source.values.put("download_enabled", true);
        for (String key : new String[]{"unknown_key", "streak_reset_token", "douai_host_settings_initialized"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put(key, new JSONObject().put("type", "string").put("value", "never-written"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        for (String type : new String[]{"string", "unknown", "boolean"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put("download_enabled", new JSONObject().put("type", type).put("value", "true"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertTrue(target.values.isEmpty()); assertEquals(0, target.commits);
    }

    @Test public void truncatedDuplicateTrailingOversizedAndWrongEnvelopeAreRejected() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); String good = ConfigTransfer.export(source, false);
        for (String invalid : new String[]{good.substring(0, good.length() - 1), good + " {}", good.replace("\"formatVersion\": 1", "\"formatVersion\": 1,\"formatVersion\":1"),
                good.replace("\"formatVersion\": 1", "\"formatVersion\": 999"), good.replace("io.github.yudigaga.douai", "other.app"),
                " ".repeat(ConfigTransfer.MAX_BYTES + 1), "{values:{}}"}) {
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, invalid));
        }
        assertEquals(0, target.commits);
    }

    @Test public void sizeLimitsHaveADistinctSafeTypeAndDoNotMislabelNullOrMalformedBackups() {
        MemoryStore target = new MemoryStore();
        BackupLimitException size = assertThrows(BackupLimitException.class,
                () -> ConfigTransfer.inspect(target, " ".repeat(ConfigTransfer.MAX_BYTES + 1)));
        assertEquals(BackupLimitException.Section.CONFIG, size.section);
        assertTrue(size.getMessage().contains("2 MiB"));
        for (String encoded : new String[]{null, "{private-malformed-fixture"}) {
            IllegalArgumentException invalid = assertThrows(IllegalArgumentException.class,
                    () -> ConfigTransfer.inspect(target, encoded));
            assertFalse(invalid instanceof BackupLimitException);
            assertFalse(invalid.getMessage().contains("private-malformed-fixture"));
        }
        assertEquals(0, target.commits);
    }

    @Test public void malformedStructuredPayloadDoesNotReachStore() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put("download_enabled", true);
        for (String key : new String[]{AiModelSettings.KEY, "tts_headers", CustomViewLocator.SETTINGS_KEY}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, false));
            root.getJSONObject("values").put(key, new JSONObject().put("type", "string").put("value", "{\"broken\":true"));
            assertThrows(IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertEquals(0, target.commits);
    }

    @Test public void overflowingModelIntegersCannotBeTruncatedByTheLegacyReader() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(AiModelSettings.KEY, model("https://api.test/v1", "fixture-key", "{}", "{}"));
        for (String field : new String[]{"contextRounds", "timeoutSeconds", "maxTokens"}) {
            JSONObject root = new JSONObject(ConfigTransfer.export(source, true));
            JSONObject typed = root.getJSONObject("values").getJSONObject(AiModelSettings.KEY);
            JSONObject models = new JSONObject(typed.getString("value"));
            models.getJSONArray("configs").getJSONObject(0).put(field, 4294967296L);
            typed.put("value", models.toString());
            assertThrows(field, IllegalArgumentException.class, () -> ConfigTransfer.inspect(target, root.toString()));
        }
        assertEquals(0, target.commits);
    }

    @Test public void previewIsReadOnlyImmutableAndCannotOverwriteInterveningEdits() {
        MemoryStore source = new MemoryStore(), target = new MemoryStore(); source.values.put("auto_play", true);
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertTrue(target.values.isEmpty()); assertEquals(0, target.commits);
        assertThrows(UnsupportedOperationException.class, () -> preview.changedKeys.add("unsafe"));
        target.values.put("keyword", "new edit");
        assertThrows(IllegalStateException.class, () -> ConfigTransfer.apply(target, preview));
        assertFalse(target.values.containsKey("auto_play"));
    }

    @Test public void failedCommitRestoresCompleteOriginalSnapshot() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences actual = context.getSharedPreferences("transfer-failure", Context.MODE_PRIVATE);
        actual.edit().clear().putBoolean("auto_play", false).putString("keyword", "original").commit();
        AtomicInteger commits = new AtomicInteger();
        SharedPreferences failing = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(), new Class<?>[]{SharedPreferences.class},
                (proxy, method, args) -> {
                    if (!method.getName().equals("edit")) return method.invoke(actual, args);
                    SharedPreferences.Editor delegate = actual.edit();
                    return Proxy.newProxyInstance(SharedPreferences.Editor.class.getClassLoader(), new Class<?>[]{SharedPreferences.Editor.class},
                            (editorProxy, editorMethod, editorArgs) -> {
                                Object result = editorMethod.invoke(delegate, editorArgs);
                                if (editorMethod.getName().equals("commit")) return commits.incrementAndGet() != 1;
                                return result == delegate ? editorProxy : result;
                            });
                });
        SettingsRepository target = new SettingsRepository(failing); MemoryStore source = new MemoryStore();
        source.values.put("auto_play", true); source.values.put("keyword", "replacement");
        ConfigTransfer.Preview preview = ConfigTransfer.inspect(target, ConfigTransfer.export(source, false));
        assertThrows(IllegalStateException.class, () -> ConfigTransfer.apply(target, preview));
        assertEquals(Map.of("auto_play", false, "keyword", "original"), actual.getAll()); assertEquals(2, commits.get());
    }

    @Test public void controlledDiagnosticsSeparateIncomingVersionTypeAndUnknownKeys() throws Exception {
        MemoryStore target = new MemoryStore();
        String valid = ConfigTransfer.export(new MemoryStore(), true);
        JSONObject version = new JSONObject(valid).put("formatVersion", 2);
        assertDiagnostic(target, version.toString(), BackupValidationException.Reason.FORMAT_VERSION, "formatVersion");

        JSONObject wrongType = new JSONObject(valid);
        wrongType.getJSONObject("values").put("native_auto_reply", new JSONObject()
                .put("type", "boolean").put("value", "private-diagnostic-fixture"));
        assertDiagnostic(target, wrongType.toString(), BackupValidationException.Reason.TYPE_MISMATCH, "native_auto_reply");

        JSONObject unknown = new JSONObject(valid);
        unknown.getJSONObject("values").put("private-unregistered-fixture", new JSONObject()
                .put("type", "string").put("value", "private-diagnostic-fixture"));
        assertDiagnostic(target, unknown.toString(), BackupValidationException.Reason.UNKNOWN_KEY, "");
        assertEquals(0, target.commits);
    }

    @Test public void structuredDiagnosticsRetainTheirSpecificCategoryWithoutNestedDataOrCauses() throws Exception {
        MemoryStore target = new MemoryStore();
        String valid = ConfigTransfer.export(new MemoryStore(), true);
        String[] keys = {AiModelSettings.KEY, "third_party_headers", CustomViewLocator.SETTINGS_KEY,
                ThirdPartyDownloadSettings.DOWNLOAD_FIELDS};
        BackupValidationException.Reason[] reasons = {BackupValidationException.Reason.STRUCTURED_MODELS,
                BackupValidationException.Reason.STRUCTURED_HEADERS, BackupValidationException.Reason.STRUCTURED_COMPONENTS,
                BackupValidationException.Reason.STRUCTURED_FIELDS};
        for (int index = 0; index < keys.length; index++) {
            JSONObject incoming = new JSONObject(valid);
            incoming.getJSONObject("values").put(keys[index], new JSONObject().put("type", "string")
                    .put("value", "{private-diagnostic-fixture"));
            assertDiagnostic(target, incoming.toString(), reasons[index], keys[index]);
        }
        assertEquals(0, target.commits);
    }

    @Test public void currentStoreReadAndRetainedTypeFailuresAreSeparatedFromIncomingData() {
        String valid = ConfigTransfer.export(new MemoryStore(), true);
        MemoryStore unreadable = new MemoryStore(); unreadable.failSnapshot = true;
        assertDiagnostic(unreadable, valid, BackupValidationException.Reason.CURRENT_STORE, "");
        MemoryStore retained = new MemoryStore();
        retained.values.put("native_auto_reply", "private-diagnostic-fixture");
        assertDiagnostic(retained, valid, BackupValidationException.Reason.MERGED_SETTINGS, "native_auto_reply");
        assertEquals(0, retained.commits);
        assertEquals(0, unreadable.commits);
        assertEquals("private-diagnostic-fixture", retained.values.get("native_auto_reply"));
    }

    @Test public void secretPolicyAndMergedMiniMaxFailuresHaveControlledDistinctReasons() throws Exception {
        MemoryStore source = new MemoryStore(), target = new MemoryStore();
        source.values.put(SpeechSettings.MINIMAX_API_KEY, "private-diagnostic-fixture");
        JSONObject falselyRedacted = new JSONObject(ConfigTransfer.export(source, true)).put("includesSecrets", false);
        assertDiagnostic(target, falselyRedacted.toString(), BackupValidationException.Reason.SECRET_POLICY,
                SpeechSettings.MINIMAX_API_KEY);

        source.values.clear();
        source.values.put(SpeechSettings.MINIMAX_MODEL, "speech-2.8-hd");
        target.values.put(MiniMaxSpeechOptions.EMOTION, "whisper");
        assertDiagnostic(target, ConfigTransfer.export(source, true), BackupValidationException.Reason.MINIMAX_OPTIONS, "");
        assertEquals("whisper", target.values.get(MiniMaxSpeechOptions.EMOTION));
        assertEquals(0, target.commits);
    }

    private static void assertDiagnostic(MemoryStore target, String encoded, BackupValidationException.Reason reason, String field) {
        BackupValidationException failure = assertThrows(BackupValidationException.class,
                () -> ConfigTransfer.inspect(target, encoded));
        assertEquals(BackupValidationException.Section.CONFIG, failure.section);
        assertEquals(reason, failure.reason);
        assertEquals(field, failure.field);
        assertEquals("CONFIG/" + reason.name(), failure.diagnosticCode());
        assertNull(failure.getCause());
        assertEquals(0, failure.getSuppressed().length);
        assertFalse(failure.getMessage().contains("private-diagnostic-fixture"));
        assertFalse(failure.getMessage().contains("private-unregistered-fixture"));
    }

    @Test public void realRepositoryPublishesTheWholeBatchInOneCommit() {
        Context context = ApplicationProvider.getApplicationContext();
        SharedPreferences preferences = context.getSharedPreferences("transfer-success", Context.MODE_PRIVATE);
        preferences.edit().clear().commit(); SettingsRepository target = new SettingsRepository(preferences);
        MemoryStore source = new MemoryStore(); source.values.put("auto_play", true); source.values.put("keyword", "complete");
        ConfigTransfer.apply(target, ConfigTransfer.inspect(target, ConfigTransfer.export(source, false)));
        assertEquals(source.values, target.snapshot());
    }

    private static MemoryStore fishStore() {
        MemoryStore source = new MemoryStore();
        source.values.put(SpeechSettings.PROVIDER, SpeechSettings.FISH);
        source.values.put(FishSpeechSettings.API_KEY, "fixture-fish-key");
        source.values.put(FishSpeechSettings.BASE_URL, FishSpeechSettings.DEFAULT_BASE_URL);
        source.values.put(FishSpeechSettings.ENGINE, "fish-audio/s2.1-pro-free");
        source.values.put(FishSpeechSettings.REFERENCE_ID, "reference-fixture");
        source.values.put(FishSpeechSettings.REFERENCE_NAME, "参考音色");
        source.values.put(FishSpeechSettings.SPEED, "1.5"); source.values.put(FishSpeechSettings.VOLUME_DB, "-6");
        source.values.put(FishSpeechSettings.NORMALIZE_LOUDNESS, "true");
        source.values.put(FishSpeechSettings.FORMAT, "opus"); source.values.put(FishSpeechSettings.SAMPLE_RATE, "48000");
        source.values.put(FishSpeechSettings.MP3_BITRATE, "128"); source.values.put(FishSpeechSettings.OPUS_BITRATE, "64000");
        source.values.put(FishSpeechSettings.TEMPERATURE, "0.8"); source.values.put(FishSpeechSettings.TOP_P, "0.9");
        source.values.put(FishSpeechSettings.NORMALIZE, "false"); source.values.put(FishSpeechSettings.LANGUAGE, "ja-JP");
        source.values.put(FishSpeechSettings.LATENCY, "balanced"); source.values.put(FishSpeechSettings.CHUNK_LENGTH, "200");
        source.values.put(FishSpeechSettings.MIN_CHUNK_LENGTH, "50"); source.values.put(FishSpeechSettings.MAX_NEW_TOKENS, "2048");
        source.values.put(FishSpeechSettings.REPETITION_PENALTY, "1.5");
        source.values.put(FishSpeechSettings.CONDITION_ON_PREVIOUS_CHUNKS, "true");
        source.values.put(FishSpeechSettings.EARLY_STOP_THRESHOLD, "0.8"); source.values.put(FishSpeechSettings.QUALITY_GUARD, "true");
        source.values.put(FishSpeechSettings.PRONUNCIATION_DICTIONARY, "[{\"id\":\"dictionary-fixture\",\"version\":\"v1\"}]");
        source.values.put(FishSpeechSettings.VOICE_SCOPE, "public"); source.values.put(FishSpeechSettings.VOICE_SEARCH, "自然");
        return source;
    }

    private static String model(String endpoint, String key, String headers, String parameters) throws Exception {
        AiModelConfig model = AiModelConfig.fromJson(new JSONObject().put("id", "stable-id").put("name", "Fixture model")
                .put("apiEndpoint", endpoint).put("apiKey", key).put("modelName", "fixture-model")
                .put("customHeaders", headers).put("customParameters", parameters));
        return new AiModelSettings(java.util.List.of(model), model.id).serialize();
    }

    private static final class MemoryStore implements SettingsStore {
        final Map<String, Object> values = new LinkedHashMap<>(); int commits; boolean failSnapshot;
        public FeatureSettings load() { return FeatureSettings.defaults(); }
        public void save(FeatureSettings value) { }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean) values.getOrDefault(key, fallback); }
        public String getString(String key, String fallback) { return (String) values.getOrDefault(key, fallback); }
        public void putBoolean(String key, boolean value) { values.put(key, value); }
        public void putString(String key, String value) { values.put(key, value); }
        public Map<String, ?> snapshot() {
            if (failSnapshot) throw new IllegalStateException("private-diagnostic-fixture");
            return new LinkedHashMap<>(values);
        }
        public void replaceSnapshot(Map<String, ?> expected, Map<String, ?> replacement) {
            if (!values.equals(expected)) throw new IllegalStateException("stale");
            values.clear(); values.putAll(replacement); commits++;
        }
    }
}
