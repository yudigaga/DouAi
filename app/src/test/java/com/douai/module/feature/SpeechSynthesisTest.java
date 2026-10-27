package com.douai.module.feature;

import android.content.Context;
import android.content.ContextWrapper;
import android.media.MediaMetadataRetriever;
import android.speech.tts.TextToSpeech;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.SpeechSettings;
import com.douai.module.diagnostics.DebugLog;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowMediaMetadataRetriever;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, shadows = SpeechSynthesisTest.AudioMetadata.class)
public final class SpeechSynthesisTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Implements(MediaMetadataRetriever.class)
    public static final class AudioMetadata extends ShadowMediaMetadataRetriever {
        static volatile String duration = "1000";
        static volatile String hasVideo = "no";
        @Implementation @Override protected String extractMetadata(int key) {
            if (key == MediaMetadataRetriever.METADATA_KEY_DURATION) return duration;
            if (key == MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) return hasVideo;
            if (key == MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) return "yes";
            return null;
        }
    }

    @Before public void resetMetadata() { AudioMetadata.duration = "1000"; AudioMetadata.hasVideo = "no"; }

    @Test public void fishSpeechUsesItsOwnPostAndTransfersOneValidatedOriginalFile() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(wav());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = fishSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    other.url("/unused") + "?text={text}").withDefault(SpeechSettings.MINIMAX_BASE_URL, other.url("/").toString());
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "Fish Audio 正文", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals(1, calls.get()); assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000, result.get().durationMillis); assertTrue(result.get().file.getName().endsWith(".wav"));
            assertEquals("RIFF0000WAVEfixture", Files.readString(result.get().file.toPath()));
            okhttp3.mockwebserver.RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("/v1/tts", sent.getPath());
            assertEquals("Bearer private-fish-key", sent.getHeader("Authorization"));
            assertEquals(0, other.getRequestCount());
            request.close(); ShadowLooper.idleMainLooper();
            assertTrue(result.get().file.isFile()); Files.delete(result.get().file.toPath());
        }
    }

    @Test public void fishFailureDoesNotSwitchProvidersOrLeaveAnOutput() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("private-fish-key private-text"));
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            LocalOptions options = fishSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    other.url("/unused") + "?text={text}");
            SpeechSynthesis.synthesize(context(), "private-text", options,
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertTrue(failure.get().startsWith("Fish Audio 语音")); assertTrue(failure.get().contains("HTTP 429"));
            assertFalse(failure.get().contains("private-"));
            assertEquals(1, server.getRequestCount()); assertEquals(0, other.getRequestCount());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void cancellingFishSpeechDisconnectsAndCompletesOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "正文", fishSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void fishBinaryStillRequiresAudioMetadataBeforeSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.hasVideo = "yes"; server.enqueue(wav());
            AtomicInteger calls = new AtomicInteger(); AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis.synthesize(context(), "正文", fishSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls); assertEquals("生成的语音文件无效", failure.get()); assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void fishReferenceModeUsesTheHostContextAndTheExistingValidatedAudioHandoff() throws Exception {
        Context context=context(); File folder=new File(context.getFilesDir(),"douai-voice-source");
        assertTrue(folder.isDirectory()||folder.mkdirs()); File sample=new File(folder,"tts-reference.flac");
        Files.writeString(sample.toPath(),"fLaCfixture");
        try(MockWebServer server=new MockWebServer()) {
            server.enqueue(wav());
            Map<String,Object> values=new java.util.HashMap<>();
            values.put(SpeechSettings.PROVIDER,SpeechSettings.FISH); values.put(FishSpeechSettings.API_KEY,"key");
            values.put(FishSpeechSettings.BASE_URL,server.url("/").toString()); values.put(FishSpeechSettings.VOICE_MODE,"references");
            values.put(FishSpeechSettings.REFERENCE_SAMPLES,new org.json.JSONArray().put(new org.json.JSONArray().put(
                    new org.json.JSONObject().put("path",sample.getAbsolutePath()).put("text","参考原文"))).toString());
            AtomicReference<LocalSpeechSynthesis.Result> result=new AtomicReference<>(); AtomicReference<String> error=new AtomicReference<>();
            AtomicInteger calls=new AtomicInteger();
            SpeechSynthesis.synthesize(context,"合成正文",new LocalOptions(values),(audio,failure)->{result.set(audio);error.set(failure);calls.incrementAndGet();});
            await(calls); assertEquals(1,calls.get()); assertNull(error.get()); assertNotNull(result.get());
            assertEquals("RIFF0000WAVEfixture",Files.readString(result.get().file.toPath()));
            assertEquals("application/msgpack",server.takeRequest(2,TimeUnit.SECONDS).getHeader("Content-Type"));
            Files.delete(result.get().file.toPath());
        } finally {Files.delete(sample.toPath());}
    }

    @Test public void breezeSpeechUsesItsOwnPostAndTransfersOneValidatedOriginalFile() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(wav());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = breezeSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    other.url("/unused") + "?text={text}").withDefault(SpeechSettings.MINIMAX_BASE_URL, other.url("/").toString());
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "Breeze 正文", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals(1, calls.get()); assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000, result.get().durationMillis); assertTrue(result.get().file.getName().endsWith(".wav"));
            assertEquals("RIFF0000WAVEfixture", Files.readString(result.get().file.toPath()));
            okhttp3.mockwebserver.RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertTrue(sent.getPath().startsWith("/v1/text-to-speech/voice-one?"));
            assertEquals("private-breeze-key", sent.getHeader("xi-api-key"));
            assertEquals(0, other.getRequestCount());
            request.close(); ShadowLooper.idleMainLooper();
            assertTrue(result.get().file.isFile()); Files.delete(result.get().file.toPath());
        }
    }

    @Test public void breezeFailureDoesNotSwitchProvidersOrLeaveAnOutput() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(429).setBody("private-breeze-key private-text"));
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            LocalOptions options = breezeSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    other.url("/unused") + "?text={text}");
            SpeechSynthesis.synthesize(context(), "private-text", options,
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertTrue(failure.get().startsWith("Breeze Blue 语音")); assertTrue(failure.get().contains("HTTP 429"));
            assertFalse(failure.get().contains("private-"));
            assertEquals(1, server.getRequestCount()); assertEquals(0, other.getRequestCount());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void cancellingBreezeSpeechDisconnectsAndCompletesOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "正文", breezeSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void breezeBinaryStillRequiresAudioMetadataBeforeSuccess() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.hasVideo = "yes"; server.enqueue(wav());
            AtomicInteger calls = new AtomicInteger(); AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis.synthesize(context(), "正文", breezeSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls); assertEquals("生成的语音文件无效", failure.get()); assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void http200AudioBytesRequireMetadataEvidenceBeforeBusinessSuccess() throws Exception {
        java.util.List<String> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        // A prior request's identical HTTP status must not hide this operation's evidence.
        com.douai.module.diagnostics.DouAiLog.event("speech_transport", "http_response", "transport_only", "status=200; result=validating");
        java.util.concurrent.CountDownLatch priorDrained = new java.util.concurrent.CountDownLatch(1);
        com.douai.module.diagnostics.DouAiLog.flush(priorDrained::countDown);
        assertTrue(priorDrained.await(5, TimeUnit.SECONDS));
        boolean previousDebug = DebugLog.isEnabled(); DebugLog.setEnabled(true);
        com.douai.module.diagnostics.DouAiLog.setFrameworkSink((level, json) -> records.add(json));
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(wav()); server.start(); AudioMetadata.hasVideo = "yes";
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), "private-text", settings(server.url("/tts") + "?apikey=private-key"),
                    (audio, error) -> { assertNull(audio); calls.incrementAndGet(); });
            await(calls);
            java.util.concurrent.CountDownLatch drained = new java.util.concurrent.CountDownLatch(1);
            com.douai.module.diagnostics.DouAiLog.flush(drained::countDown);
            assertTrue(drained.await(5, TimeUnit.SECONDS));
            org.json.JSONObject terminal = null;
            for (String line : records) {
                org.json.JSONObject event = new org.json.JSONObject(line);
                if ("speech_synthesis".equals(event.optString("feature_id")) && "failed".equals(event.optString("outcome"))) terminal = event;
                assertFalse("invalid audio cannot be a synthesis success", "speech_synthesis".equals(event.optString("feature_id"))
                        && "success".equals(event.optString("outcome")));
            }
            assertNotNull(terminal); assertEquals("invalid_audio", terminal.getString("reason_code"));
            String trace = terminal.getString("trace_id");
            assertTrue(records.stream().anyMatch(line -> line.contains(trace) && line.contains("http_response") && line.contains("200")));
            String text = String.join("\n", records);
            assertFalse(text.contains("private-text")); assertFalse(text.contains("private-key"));
            assertEquals(0, cachedAudioCount());
        } finally {
            com.douai.module.diagnostics.DouAiLog.awaitIdle(5000);
            com.douai.module.diagnostics.DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void directBinarySpeechReturnsAnOwnedValidatedFileExactlyOnce() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(wav()); server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts") + "?text={text}"),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals(1, calls.get()); assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000L, result.get().durationMillis); assertTrue(result.get().file.isFile());
            request.close(); ShadowLooper.getShadowMainLooper().idle();
            assertEquals(1, calls.get()); assertTrue(result.get().file.isFile());
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void linkedAudioDownloadsDoNotForwardApiCredentialsAcrossOrigins() throws Exception {
        try (MockWebServer api = new MockWebServer(); MockWebServer cdn = new MockWebServer()) {
            api.start(); cdn.start();
            api.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"data\":{\"audio\":\"" + cdn.url("/sound") + "\"}}"));
            cdn.enqueue(wav());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY,
                    SpeechSettings.GET_TEMPLATE, api.url("/tts") + "?text={text}&speaker={voice}",
                    SpeechSettings.SELECTED_VOICE, "v2", SpeechSettings.AUDIO_FIELD, "data.audio",
                    SpeechSettings.HEADERS, "{\"Authorization\":\"private-key\"}"));
            SpeechSynthesis.synthesize(context(), "hello", options, (audio, error) -> { result.set(audio); calls.incrementAndGet(); });
            await(calls);
            assertNotNull(result.get());
            assertEquals("private-key", api.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            assertNull(cdn.takeRequest(2, TimeUnit.SECONDS).getHeader("Authorization"));
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void miniMaxSpeechRoutesToNativePostAndReturnsAnOwnedValidatedMp3() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer thirdParty = new MockWebServer()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"49443366697874757265\"}}"));
            server.start(); thirdParty.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = miniMaxSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    thirdParty.url("/unused") + "?text={text}");
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "你好 MiniMax", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(1000L, result.get().durationMillis); assertTrue(result.get().file.isFile());
            assertTrue(result.get().file.getName().endsWith(".mp3"));
            assertArrayEquals(new byte[]{'I', 'D', '3', 'f', 'i', 'x', 't', 'u', 'r', 'e'},
                    Files.readAllBytes(result.get().file.toPath()));
            assertEquals("/v1/t2a_v2", server.takeRequest(2, TimeUnit.SECONDS).getPath());
            assertEquals(0, thirdParty.getRequestCount());
            request.close(); ShadowLooper.getShadowMainLooper().idle();
            assertEquals(1, calls.get()); assertTrue(result.get().file.isFile());
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void miniMaxFailureDoesNotFallBackToTheGenericProviderOrAndroidEngine() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer thirdParty = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(401).setBody("private-key private-text"));
            thirdParty.enqueue(wav()); server.start(); thirdParty.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            LocalOptions options = miniMaxSettings(server).withDefault(SpeechSettings.GET_TEMPLATE,
                    thirdParty.url("/unused") + "?text={text}");
            SpeechSynthesis.synthesize(context(), "private-text", options,
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(result.get()); assertNotNull(failure.get());
            assertTrue(failure.get().startsWith("MiniMax 语音")); assertFalse(failure.get().contains("private"));
            assertEquals(1, server.getRequestCount()); assertEquals(0, thirdParty.getRequestCount());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void invalidMiniMaxAudioIsRemovedWithoutDeliveringAnOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.duration = "0";
            server.enqueue(new MockResponse().addHeader("Content-Type", "application/json")
                    .setBody("{\"base_resp\":{\"status_code\":0},\"data\":{\"status\":2,\"audio\":\"49443366697874757265\"}}"));
            server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), "hello", miniMaxSettings(server),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(result.get()); assertEquals("生成的语音文件无效", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeCancelsMiniMaxPostAndCompletesOnceWithoutOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", miniMaxSettings(server),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void thirdPartySpeechDoesNotUseTheAndroidEngineTextLimit() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(wav()); server.start();
            String text = "voice ".repeat(TextToSpeech.getMaxSpeechInputLength() / 6 + 1);
            assertTrue(text.length() > TextToSpeech.getMaxSpeechInputLength());
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), text, settings(server.url("/tts") + "?text={text}"),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(text, server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().queryParameter("text"));
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void thirdPartyAudioOverTwoMinutesIsAcceptedWithItsActualDuration() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            AudioMetadata.duration = "180001";
            server.enqueue(wav()); server.start();
            AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
            AtomicReference<String> failure = new AtomicReference<>();
            AtomicInteger calls = new AtomicInteger();
            SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                    (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertNull(failure.get()); assertNotNull(result.get());
            assertEquals(180001L, result.get().durationMillis);
            Files.deleteIfExists(result.get().file.toPath());
        }
    }

    @Test public void invalidAudioIsRejectedAndRemoved() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start();
            for (String duration : new String[]{null, "0", "-1"}) {
                AudioMetadata.duration = duration;
                server.enqueue(wav());
                AtomicReference<LocalSpeechSynthesis.Result> result = new AtomicReference<>();
                AtomicReference<String> failure = new AtomicReference<>();
                AtomicInteger calls = new AtomicInteger();
                SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                        (audio, error) -> { result.set(audio); failure.set(error); calls.incrementAndGet(); });
                await(calls);
                assertNull(result.get()); assertNotNull(failure.get()); assertEquals(0, cachedAudioCount());
            }
        }
    }

    @Test public void videosAndNonAudioProviderErrorsAreRejected() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.start(); server.enqueue(wav());
            AudioMetadata.hasVideo = "yes";
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis.synthesize(context(), "private-text", settings(server.url("/tts") + "?apikey=private-key"),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertEquals("生成的语音文件无效", failure.get()); assertEquals(0, cachedAudioCount());
            server.enqueue(new MockResponse().setBody("{\"error\":\"private-key private-text\"}"));
            calls.set(0);
            SpeechSynthesis.synthesize(context(), "private-text", settings(server.url("/tts") + "?apikey=private-key"),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            await(calls);
            assertFalse(failure.get().contains("private")); assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeCancelsBlockedProviderAndCompletesOnceWithNoCachedOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); server.start();
            AtomicInteger calls = new AtomicInteger();
            AtomicReference<String> failure = new AtomicReference<>();
            SpeechSynthesis request = SpeechSynthesis.synthesize(context(), "hello", settings(server.url("/tts").toString()),
                    (audio, error) -> { assertNull(audio); failure.set(error); calls.incrementAndGet(); });
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.close(); request.close(); await(calls);
            assertEquals(1, calls.get()); assertEquals("语音生成已停止", failure.get());
            assertEquals(0, cachedAudioCount());
        }
    }

    @Test public void closeAfterWorkerCompletionBeforeDeliveryDeletesThePendingResult() throws Exception {
        AtomicReference<LocalSpeechSynthesis.Result> delivered = new AtomicReference<>();
        AtomicReference<String> failure = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        java.lang.reflect.Constructor<SpeechSynthesis> constructor =
                SpeechSynthesis.class.getDeclaredConstructor(BiConsumer.class);
        constructor.setAccessible(true);
        SpeechSynthesis request = constructor.newInstance((BiConsumer<LocalSpeechSynthesis.Result, String>) (audio, error) -> {
            delivered.set(audio); failure.set(error); calls.incrementAndGet();
        });
        java.lang.reflect.Method complete = SpeechSynthesis.class.getDeclaredMethod("complete",
                LocalSpeechSynthesis.Result.class, String.class);
        complete.setAccessible(true);
        File pending = temporary.newFile("pending.wav");
        complete.invoke(request, new LocalSpeechSynthesis.Result(pending, 1000), null);
        assertEquals(0, calls.get());
        request.close();
        ShadowLooper.getShadowMainLooper().idle();
        assertEquals(1, calls.get());
        assertNull(delivered.get());
        assertEquals("语音生成已停止", failure.get());
        assertFalse(pending.exists());
    }

    private Context context() {
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public Context getApplicationContext() { return this; }
            @Override public File getCacheDir() { return temporary.getRoot(); }
        };
    }

    private int cachedAudioCount() {
        File[] files = new File(temporary.getRoot(), "douai-tts").listFiles(File::isFile);
        return files == null ? 0 : files.length;
    }

    private static LocalOptions settings(String template) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.THIRD_PARTY, SpeechSettings.GET_TEMPLATE, template));
    }

    private static LocalOptions miniMaxSettings(MockWebServer server) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.MINIMAX,
                SpeechSettings.MINIMAX_BASE_URL, server.url("/").toString(), SpeechSettings.MINIMAX_API_KEY, "private-key"));
    }

    private static LocalOptions fishSettings(MockWebServer server) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.FISH,
                FishSpeechSettings.BASE_URL, server.url("/").toString(), FishSpeechSettings.API_KEY, "private-fish-key"));
    }

    private static LocalOptions breezeSettings(MockWebServer server) {
        return new LocalOptions(Map.of(SpeechSettings.PROVIDER, SpeechSettings.BREEZE,
                BreezeSpeechSettings.BASE_URL, server.url("/").toString(), BreezeSpeechSettings.API_KEY, "private-breeze-key",
                BreezeSpeechSettings.VOICE_ID, "voice-one"));
    }

    private static MockResponse wav() {
        return new MockResponse().addHeader("Content-Type", "audio/wav")
                .setBody(new Buffer().writeUtf8("RIFF0000WAVEfixture"));
    }

    private static void await(AtomicInteger calls) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (calls.get() == 0 && System.nanoTime() < until) {
            ShadowLooper.getShadowMainLooper().idle();
            Thread.sleep(10);
        }
        assertEquals("Speech callback should complete", 1, calls.get());
    }
}
