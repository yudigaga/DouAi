package com.douai.module.feature;

import com.douai.module.config.BreezeSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class BreezeSpeechClientTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void synthesisUsesCurrentSchemaAndPreservesTextWithOnlyOwnedCredentials() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.BASE_URL, server.url("/v1/").toString());
            values.put(BreezeSpeechSettings.VOICE_ID, "voice /?&");
            values.put(BreezeSpeechSettings.MODEL, "breeze-tts-2-multilingual");
            values.put(BreezeSpeechSettings.OUTPUT_FORMAT, "mp3_44100_192");
            values.put(BreezeSpeechSettings.SPEED, "1.5"); values.put(BreezeSpeechSettings.VOLUME, "0.25");
            values.put(BreezeSpeechSettings.GUIDANCE_SCALE, "4"); values.put(BreezeSpeechSettings.SPEAKER_BOOST, "true");
            values.put(BreezeSpeechSettings.INSTRUCTIONS, "  温和地朗读。\n保留停顿。  ");
            values.put(BreezeSpeechSettings.LANGUAGE_CODE, "zh");
            values.put("tts_minimax_api_key", "other-provider-key");
            values.put("tts_minimax_pitch", "12"); values.put("tts_minimax_emotion", "happy");
            String text = "  保留正文及空格。\n不截断，也不自动分段。  ";
            server.enqueue(audio("fixture"));
            assertTrue(BreezeSpeechClient.synthesize(request, text, settings(values)).isAudio());
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent);
            assertTrue(sent.getPath().startsWith("/v1/text-to-speech/voice%20%2F%3F%26?"));
            assertEquals("sync", sent.getRequestUrl().queryParameter("delivery"));
            assertEquals("mp3_44100_192", sent.getRequestUrl().queryParameter("output_format"));
            assertEquals("POST", sent.getMethod());
            assertEquals("private-breeze-key", sent.getHeader("xi-api-key")); assertNull(sent.getHeader("Authorization"));
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(5, body.length()); assertEquals(text, body.getString("text"));
            assertEquals("breeze-tts-2-multilingual", body.getString("model_id"));
            assertEquals("zh", body.getString("language_code"));
            assertEquals("温和地朗读。\n保留停顿。", body.getString("instructions"));
            JSONObject voice = body.getJSONObject("voice_settings");
            assertEquals(4, voice.length()); assertEquals(1.5, voice.getDouble("speed"), 0.0001);
            assertEquals(0.25, voice.getDouble("volume"), 0.0001);
            assertEquals(4, voice.getDouble("guidance_scale"), 0.0001); assertFalse(voice.getBoolean("use_speaker_boost"));
            assertEquals("true", values.get(BreezeSpeechSettings.SPEAKER_BOOST));
            assertFalse(body.toString().contains("other-provider-key")); assertFalse(voice.has("pitch"));
            assertFalse(voice.has("stability")); assertFalse(voice.has("similarity_boost")); assertFalse(voice.has("style"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void automaticModelOmitsTheModelIdAndOptionalLanguageAndInstruction() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(audio("default"));
            BreezeSpeechClient.synthesize(request, "正文", settings(values(server)));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("mp3", sent.getRequestUrl().queryParameter("output_format"));
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(2, body.length()); assertFalse(body.has("model_id"));
            assertFalse(body.has("language_code")); assertFalse(body.has("instructions"));
            JSONObject voice = body.getJSONObject("voice_settings");
            assertEquals(1, voice.getDouble("guidance_scale"), 0.0001);
            assertEquals(1, voice.getDouble("speed"), 0.0001); assertEquals(1, voice.getDouble("volume"), 0.0001);
            assertFalse(voice.getBoolean("use_speaker_boost"));
        }
    }

    @Test public void effectiveSpeakerBoostUsesGuidanceOneWithoutChangingSavedValues() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.GUIDANCE_SCALE, "8");
            values.put(BreezeSpeechSettings.SPEAKER_BOOST, "true");
            server.enqueue(audio("boost"));
            BreezeSpeechClient.synthesize(request, "正文", settings(values));
            JSONObject voice = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8()).getJSONObject("voice_settings");
            assertTrue(voice.getBoolean("use_speaker_boost"));
            assertEquals(1, voice.getDouble("guidance_scale"), 0.0001);
            assertEquals("8", values.get(BreezeSpeechSettings.GUIDANCE_SCALE));
            assertEquals("true", values.get(BreezeSpeechSettings.SPEAKER_BOOST));
        }
    }

    @Test public void streamingUsesItsOwnEndpointAndExplicitCompatibleOutputProfiles() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String format : new String[]{"mp3_44100_128", "wav_48000"}) {
                Map<String, Object> values = values(server);
                values.put(BreezeSpeechSettings.DELIVERY, "stream");
                values.put(BreezeSpeechSettings.OUTPUT_FORMAT, format);
                values.put(BreezeSpeechSettings.VOICE_ID, "voice /?&");
                server.enqueue(new MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody("ID3streamed-audio", 3));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    File output = new File(temporary.getRoot(), format + ".audio");
                    assertTrue(BreezeSpeechClient.synthesize(request, "[正文] 保留", settings(values), output).isAudio());
                    assertEquals("ID3streamed-audio", java.nio.file.Files.readString(output.toPath()));
                }
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(sent); assertEquals("POST", sent.getMethod());
                assertEquals("/v1/text-to-speech/voice%20%2F%3F%26/stream", sent.getRequestUrl().encodedPath());
                assertEquals(format, sent.getRequestUrl().queryParameter("output_format"));
                assertNull(sent.getRequestUrl().queryParameter("delivery"));
                assertFalse(new JSONObject(sent.getBody().readUtf8()).has("model_id"));
            }
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void unsupportedStreamCodecsAreRejectedBeforeAnyRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String format : new String[]{"flac", "aac", "opus"}) {
                Map<String, Object> values = values(server);
                values.put(BreezeSpeechSettings.DELIVERY, "stream"); values.put(BreezeSpeechSettings.OUTPUT_FORMAT, format);
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
                }
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void asyncSubmitsOnlyOnceAndPollsAndDownloadsItsEncodedJobOnTheSameOrigin() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer external = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            String id = "job /?&";
            server.enqueue(job(202, id, "admitted"));
            server.enqueue(job(200, id, "generating"));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("generation_job_id", id).put("status", "ready")
                    .put("download_url", external.url("/must-not-receive-key").toString()).toString()));
            server.enqueue(audio("async-result"));
            File output = new File(temporary.getRoot(), "async.audio");
            assertTrue(BreezeSpeechClient.synthesize(request, "正文", settings(values), output).isAudio());
            assertEquals("ID3async-result", java.nio.file.Files.readString(output.toPath()));
            RecordedRequest submit = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(submit); assertEquals("POST", submit.getMethod());
            assertEquals("async", submit.getRequestUrl().queryParameter("delivery"));
            assertEquals("mp3", submit.getRequestUrl().queryParameter("output_format"));
            for (String path : new String[]{"/v1/generation-jobs/job%20%2F%3F%26", "/v1/generation-jobs/job%20%2F%3F%26", "/v1/generation-jobs/job%20%2F%3F%26/audio"}) {
                RecordedRequest followup = server.takeRequest(2, TimeUnit.SECONDS);
                assertNotNull(followup); assertEquals("GET", followup.getMethod());
                assertEquals(path, followup.getPath()); assertEquals("private-breeze-key", followup.getHeader("xi-api-key"));
            }
            assertEquals(4, server.getRequestCount()); assertEquals(0, external.getRequestCount());
        }
    }

    @Test public void asyncTerminalFailuresStopPollingWithoutEchoingDetailsOrSubmittingAgain() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String status : new String[]{"failed", "cancelled"}) {
                Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
                server.enqueue(job(202, "owned-job", "admitted"));
                server.enqueue(new MockResponse().setBody(new JSONObject().put("generation_job_id", "owned-job").put("status", status)
                        .put("error", new JSONObject().put("code", "private-code").put("detail", "private-breeze-key private-text")).toString()));
                File output = new File(temporary.getRoot(), status + ".audio");
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "private-text", settings(values), output));
                    assertFalse(failure.getMessage().contains("private-")); assertNull(failure.getCause()); assertFalse(output.exists());
                }
                assertEquals("POST", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
                assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            }
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void readyAudioCanWaitFor425AndRetryAfterWithoutSubmittingAnotherGeneration() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            for (String retryAfter : new String[]{"1", "0"}) {
                server.enqueue(job(202, "owned-job", "admitted")); server.enqueue(job(200, "owned-job", "ready"));
                server.enqueue(new MockResponse().setResponseCode(425).setHeader("Retry-After", retryAfter).setBody("private-provider-detail"));
                server.enqueue(job(200, "owned-job", "ready")); server.enqueue(audio("eventually-ready"));
                long started = System.nanoTime();
                assertTrue(BreezeSpeechClient.synthesize(request, "正文", settings(values)).isAudio());
                assertTrue("Audio 425 must wait at least one second, including an invalid Retry-After: 0",
                        System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(900));
                int posts = 0;
                for (String path : new String[]{"/v1/text-to-speech/voice-one?delivery=async&output_format=mp3",
                        "/v1/generation-jobs/owned-job", "/v1/generation-jobs/owned-job/audio",
                        "/v1/generation-jobs/owned-job", "/v1/generation-jobs/owned-job/audio"}) {
                    RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                    assertNotNull(sent); assertEquals(path, sent.getPath());
                    if ("POST".equals(sent.getMethod())) posts++;
                }
                assertEquals(1, posts);
            }
            assertEquals(10, server.getRequestCount());
        }
    }

    @Test public void cancellationDuring425WaitingStopsTheSameJobWithoutAnotherPost() throws Exception {
        AtomicReference<Thread> clientThread = new AtomicReference<>();
        ExecutorService worker = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "breeze-425-wait-cancellation");
            clientThread.set(thread);
            return thread;
        });
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            server.enqueue(job(202, "owned-job", "admitted")); server.enqueue(job(200, "owned-job", "ready"));
            server.enqueue(new MockResponse().setResponseCode(425).setHeader("Retry-After", "30").setBody("{}"));
            Future<?> result = worker.submit(() -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            awaitRetryAfterPause(clientThread, result);
            assertFalse("The async synthesis must still be pending when cancellation is requested", result.isDone());
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            Throwable actual = failure.getCause();
            assertTrue("Expected cancellation after audio 425; actual=" + (actual == null ? "null" : actual.getClass().getName()
                    + ": " + actual.getMessage()), actual instanceof CancellationException);
            assertEquals(3, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void asyncPollHttpErrorsAreSanitizedWithoutRetryingOrDownloading() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            server.enqueue(job(202, "owned-job", "admitted"));
            server.enqueue(new MockResponse().setResponseCode(503).setBody("private-breeze-key private-text"));
            Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
            assertTrue(failure.getMessage().contains("HTTP 503")); assertFalse(failure.getMessage().contains("private-"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void asyncInvalidOrMismatchedJobIdsNeverTriggerAnAudioDownload() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"status\":\"admitted\"}"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
            }
            assertEquals(1, server.getRequestCount());
            server.enqueue(job(202, "owned-job", "admitted")); server.enqueue(job(200, "another-job", "ready"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
            }
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void asyncCancellationDisconnectsThePollAndDoesNotLeaveAnOwnedFile() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.DELIVERY, "async");
            server.enqueue(job(202, "owned-job", "admitted")); server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            File output = new File(temporary.getRoot(), "cancelled-job.audio");
            Future<?> result = worker.submit(() -> BreezeSpeechClient.synthesize(request, "正文", settings(values), output));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertFalse(output.exists());
            assertEquals(2, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void instructionEnhancementOnlySendsTheDraftAndReturnsItWithoutPersistingOrSynthesizing() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_ID, ""); values.put(BreezeSpeechSettings.LANGUAGE_CODE, "zh");
            values.put(BreezeSpeechSettings.INSTRUCTIONS, "此前保存的指令");
            server.enqueue(new MockResponse().setBody("{\"instruction\":\"  更自然地讲述。\\n保留停顿。  \",\"language_code\":\"zh\"}"));
            assertEquals("更自然地讲述。\n保留停顿。", BreezeSpeechClient.enhanceInstruction(request, settings(values), "  温和  "));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("POST", sent.getMethod()); assertEquals("/v1/text-to-speech/enhance", sent.getPath());
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(2, body.length()); assertEquals("温和", body.getString("instruction")); assertEquals("zh", body.getString("language_code"));
            assertFalse(body.has("text")); assertFalse(body.has("voice_id")); assertFalse(body.has("instructions"));
            assertEquals("private-breeze-key", sent.getHeader("xi-api-key"));
            assertEquals("此前保存的指令", values.get(BreezeSpeechSettings.INSTRUCTIONS)); assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void enhancementInputLimitCountsCodePointsAndNeverTruncatesTheDraft() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String maximum = "🌸".repeat(1000);
            server.enqueue(new MockResponse().setBody("{\"instruction\":\"优化后的指令\",\"language_code\":\"zh\"}"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                BreezeSpeechClient.enhanceInstruction(request, settings(values(server)), maximum);
            }
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals(maximum, body.getString("instruction")); assertFalse(body.has("language_code"));
            for (String invalid : new String[]{"", "   ", maximum + "花"}) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.enhanceInstruction(request, settings(values(server)), invalid));
                    assertTrue(failure.getMessage().contains("1000"));
                }
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void enhancementErrorsAndInvalidJsonDoNotExposeResponseDetailsOrRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (MockResponse response : new MockResponse[]{
                    new MockResponse().setResponseCode(503).setBody("private-breeze-key private-instruction"),
                    new MockResponse().setBody("HTTP 504 private-breeze-key private-instruction"),
                    new MockResponse().setBody("{\"instruction\":\"private-result-without-language\"}")}) {
                server.enqueue(response);
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.enhanceInstruction(request, settings(values(server)), "温和"));
                    assertFalse(failure.getMessage().contains("private-")); assertNull(failure.getCause());
                }
            }
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void enhancementCancellationStopsItsOwnedRequest() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            Future<?> result = worker.submit(() -> BreezeSpeechClient.enhanceInstruction(request, settings(values(server)), "温和"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertEquals(1, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void binaryAudioAboveTheJsonLimitStreamsIntoTheOwnedFile() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            byte[] data = new byte[3 * 1024 * 1024];
            System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 3);
            server.enqueue(new MockResponse().setHeader("Content-Type", "audio/mpeg").setBody(new Buffer().write(data)));
            File output = new File(temporary.getRoot(), "owned.audio");
            SpeechHttpClient.Resource result = BreezeSpeechClient.synthesize(request, "正文", settings(values(server)), output);
            assertNull(result.bytes); assertEquals(output, result.file); assertTrue(result.isAudio());
            assertEquals(data.length, output.length()); assertEquals(data.length, result.sizeBytes());
        }
    }

    @Test public void aJsonJobOrAudioUrlIsNotMistakenForSynchronousAudio() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer external = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            File output = new File(temporary.getRoot(), "not-audio.audio");
            server.enqueue(new MockResponse().setResponseCode(202).setHeader("Content-Type", "application/json")
                    .setBody(new JSONObject().put("generation_job_id", "private-job")
                            .put("audio_url", external.url("/private").toString()).put("detail", "private-request-text").toString()));
            Exception failure = assertThrows(Exception.class,
                    () -> BreezeSpeechClient.synthesize(request, "private-request-text", settings(values(server)), output));
            assertFalse(output.exists()); assertTrue(failure.getMessage().contains("HTTP 202"));
            assertFalse(failure.getMessage().contains("private-job")); assertFalse(failure.getMessage().contains("private-request-text"));
            assertEquals(1, server.getRequestCount()); assertEquals(0, external.getRequestCount());
        }
    }

    @Test public void partialHttpSuccessCannotBeTreatedAsACompleteSpeechFile() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(audio("valid-looking-partial-audio").setResponseCode(206));
            File output = new File(temporary.getRoot(), "partial-success.audio");
            Exception failure = assertThrows(Exception.class,
                    () -> BreezeSpeechClient.synthesize(request, "正文", settings(values(server)), output));
            assertTrue(failure.getMessage().contains("HTTP 206")); assertFalse(output.exists());
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void httpFailuresDoNotLeakProviderBodiesOrAutomaticallyRetry() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int status : new int[]{400, 401, 402, 403, 422, 429, 503, 504}) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("private-breeze-key private-text private-url"));
                File output = new File(temporary.getRoot(), "failure-" + status + ".audio");
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class,
                            () -> BreezeSpeechClient.synthesize(request, "private-text", settings(values(server)), output));
                    assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP " + status));
                    assertFalse(failure.getMessage().contains("private-")); assertNull(failure.getCause());
                    assertFalse(output.exists());
                }
            }
            assertEquals(8, server.getRequestCount());
        }
    }

    @Test public void redirectsNeverForwardTheApiKeyToAnotherOrigin() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer external = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", external.url("/audio")));
            assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values(server))));
            assertEquals(1, server.getRequestCount()); assertEquals(0, external.getRequestCount());
        }
    }

    @Test public void incompleteOrOversizedAudioDoesNotLeaveAUsableOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(audio("short").setHeader("Content-Length", 1024).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
            server.enqueue(audio("large").setHeader("Content-Length", SpeechHttpClient.MAX_AUDIO_BYTES + 1));
            for (int index = 0; index < 2; index++) {
                File output = new File(temporary.getRoot(), "partial-" + index + ".audio");
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values(server)), output));
                }
                assertFalse(output.exists());
            }
        }
    }

    @Test public void cancellationDisconnectsABlockedSynthesisAndDeletesItsPartialFile() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            File output = new File(temporary.getRoot(), "cancelled.audio");
            BreezeSpeechSettings settings = settings(values(server));
            Future<?> result = worker.submit(() -> BreezeSpeechClient.synthesize(request, "正文", settings, output));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertFalse(output.exists());
        } finally { worker.shutdownNow(); }
    }

    @Test public void textIsNeverTruncatedAndTheMaximumUsesUnicodeCodePoints() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            String maximum = "🌸".repeat(2000);
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                server.enqueue(audio("unicode"));
                BreezeSpeechClient.synthesize(request, maximum, settings(values(server)));
            }
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals(maximum, new JSONObject(sent.getBody().readUtf8()).getString("text"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                Exception failure = assertThrows(Exception.class,
                        () -> BreezeSpeechClient.synthesize(request, maximum + "花", settings(values(server))));
                assertTrue(failure.getMessage().contains("2000"));
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void rawPcmIsRejectedBeforeSendingWithoutChangingTheSelectedFormat() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.OUTPUT_FORMAT, "pcm_24000");
            Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.synthesize(request, "正文", settings(values)));
            assertTrue(failure.getMessage().contains("不支持裸 PCM")); assertEquals(0, server.getRequestCount());
            assertEquals("pcm_24000", values.get(BreezeSpeechSettings.OUTPUT_FORMAT));
        }
    }

    @Test public void modelsUseTheAuthenticatedArrayContractWithoutRequiringASelectedVoice() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.VOICE_ID, "");
            server.enqueue(new MockResponse().setBody("[{\"model_id\":\"breeze-tts-2\"},{\"model_id\":\"breeze-tts-2-multilingual\"},{\"model_id\":\"breeze-tts-2\"}]"));
            assertEquals(List.of("breeze-tts-2", "breeze-tts-2-multilingual"), BreezeSpeechClient.fetchModels(request, settings(values)));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("/v1/models", sent.getPath()); assertEquals("GET", sent.getMethod());
            assertEquals("private-breeze-key", sent.getHeader("xi-api-key"));
        }
    }

    @Test public void voicePaginationKeepsFiltersAndEscapesTheCursorWithoutChangingTheOrigin() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_TYPE, "personal"); values.put(BreezeSpeechSettings.VOICE_SEARCH, "暖声 ?&/");
            values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "ja"); values.put(BreezeSpeechSettings.LANGUAGE_CODE, "zh");
            server.enqueue(voices(true, "cursor ?&/", 1, "first", "第一音色"));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("has_more", false).put("page", 2)
                    .put("voices", new JSONArray().put(voice("first", "不可覆盖")).put(voice("second", "第二\u0085音色"))).toString()));
            List<VoiceCatalog.Entry> result = BreezeSpeechClient.fetchVoices(request, settings(values));
            assertEquals(2, result.size()); assertEquals("第一音色", result.get(0).name); assertEquals("second", result.get(1).id);
            assertEquals("第二 音色", result.get(1).name);
            assertEquals(result.get(1).name, BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_NAME, result.get(1).name));
            RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS), second = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second);
            assertEquals("/v1/voices", first.getRequestUrl().encodedPath());
            for (RecordedRequest sent : new RecordedRequest[]{first, second}) {
                assertEquals("personal", sent.getRequestUrl().queryParameter("voice_type"));
                assertEquals("暖声 ?&/", sent.getRequestUrl().queryParameter("search"));
                assertEquals("ja", sent.getRequestUrl().queryParameter("language_code"));
            }
            assertEquals("20", second.getRequestUrl().queryParameter("page_size"));
            assertEquals("cursor ?&/", second.getRequestUrl().queryParameter("next_page_token"));
            assertEquals("private-breeze-key", second.getHeader("xi-api-key"));
        }
    }

    @Test public void voicePaginationCanUseTheDocumentedOffsetFallback() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_TYPE, "default"); values.put(BreezeSpeechSettings.VOICE_SEARCH, "播音 &/");
            values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "en");
            server.enqueue(voices(true, null, 1, "one", "一")); server.enqueue(voices(false, null, 2, "two", "二"));
            assertEquals(2, BreezeSpeechClient.fetchVoices(request, settings(values)).size());
            RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS), second = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(first); assertNotNull(second); assertEquals("2", second.getRequestUrl().queryParameter("page"));
            for (RecordedRequest sent : new RecordedRequest[]{first, second}) {
                assertEquals("default", sent.getRequestUrl().queryParameter("voice_type"));
                assertEquals("播音 &/", sent.getRequestUrl().queryParameter("search"));
                assertEquals("en", sent.getRequestUrl().queryParameter("language_code"));
            }
        }
    }

    @Test public void repeatedTerminalVoicePagesFailButAnEmptyTerminalPagePreservesValidVoices() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(voices(true, "next", 1, "one", "一")); server.enqueue(voices(false, null, 2, "one", "一"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoices(request, settings(values(server))));
                assertTrue(failure.getMessage().contains("未前进"));
            }
            server.enqueue(voices(true, "next", 1, "one", "一"));
            server.enqueue(new MockResponse().setBody("{\"voices\":[],\"has_more\":false,\"page\":2}"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                List<VoiceCatalog.Entry> entries = BreezeSpeechClient.fetchVoices(request, settings(values(server)));
                assertEquals(1, entries.size()); assertEquals("one", entries.get(0).id);
            }
        }
    }

    @Test public void breezeCatalogDoesNotFilterByFishTypeOrTrainingStateAndRejectsPartialHttpStatus() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody(new JSONObject().put("has_more", false).put("voices", new JSONArray()
                    .put(voice("breeze-voice", "有效音色").put("type", "not-fish-tts").put("state", "not-fish-trained"))).toString()));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertEquals("breeze-voice", BreezeSpeechClient.fetchVoices(request, settings(values(server))).get(0).id);
            }
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("all", sent.getRequestUrl().queryParameter("voice_type"));
            assertEquals("zh", sent.getRequestUrl().queryParameter("language_code"));
            server.enqueue(new MockResponse().setResponseCode(206).setBody("[{\"model_id\":\"partial-model\"}]"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchModels(request, settings(values(server))));
                assertTrue(failure.getMessage().contains("HTTP 206"));
            }
        }
    }

    @Test public void aCompletedEmptyPersonalVoiceSearchIsAValidCatalogAndNeverSynthesizes() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_TYPE, "personal"); values.put(BreezeSpeechSettings.VOICE_SEARCH, "无匹配");
            values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "en"); values.put(BreezeSpeechSettings.VOICE_ID, "");
            server.enqueue(new MockResponse().setBody("{\"voices\":[],\"has_more\":false,\"page\":1}"));
            assertTrue(BreezeSpeechClient.fetchVoices(request, settings(values)).isEmpty());
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("GET", sent.getMethod());
            assertEquals("personal", sent.getRequestUrl().queryParameter("voice_type"));
            assertEquals("无匹配", sent.getRequestUrl().queryParameter("search"));
            assertEquals("en", sent.getRequestUrl().queryParameter("language_code"));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void repeatedVoiceCursorsFailRatherThanReturningAPartialCatalog() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(true, "same", 1, "one", "一")); server.enqueue(voices(true, "same", 2, "two", "二"));
            Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoices(request, settings(values(server))));
            assertTrue(failure.getMessage().contains("未前进")); assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void invalidCatalogDataCannotMasqueradeAsAnHttpStatusOrExposePrivateText() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("HTTP 504 private-response-key private-text"));
            Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchModels(request, settings(values(server))));
            assertFalse(failure.getMessage().contains("504")); assertFalse(failure.getMessage().contains("private-"));
            assertNull(failure.getCause());
        }
    }

    @Test public void aBrowsablePageReturnsBeforeLoadingTheNextNativeCursor() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(true, "opaque ?&/cursor", 1, "first", "第一音色"));
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_TYPE, "personal");
            values.put(BreezeSpeechSettings.VOICE_SEARCH, "中文 &?/筛选");
            values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "zh");
            BreezeSpeechSettings settings = settings(values);
            Future<BreezeSpeechClient.VoicePage> firstResult = worker.submit(
                    () -> BreezeSpeechClient.fetchVoicePage(request, settings, 1, ""));
            BreezeSpeechClient.VoicePage first = firstResult.get(2, TimeUnit.SECONDS);
            assertEquals(1, first.page); assertEquals(1, first.sourceCount); assertTrue(first.hasMore);
            assertEquals(2, first.nextPage); assertEquals("opaque ?&/cursor", first.nextPageToken);
            assertEquals("first", first.entries.get(0).id); assertEquals(List.of("first"), first.sourceIds);
            assertThrows(UnsupportedOperationException.class, () -> first.entries.clear());
            assertThrows(UnsupportedOperationException.class, () -> first.sourceIds.clear());
            assertEquals(1, server.getRequestCount());
            RecordedRequest initial = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("1", initial.getRequestUrl().queryParameter("page"));
            assertNull(initial.getRequestUrl().queryParameter("next_page_token"));

            server.enqueue(voices(false, null, 2, "second", "第二音色"));
            BreezeSpeechClient.VoicePage second = BreezeSpeechClient.fetchVoicePage(
                    request, settings, first.nextPage, first.nextPageToken);
            assertEquals(2, second.page); assertFalse(second.hasMore); assertEquals(0, second.nextPage);
            assertEquals("", second.nextPageToken); assertEquals("second", second.entries.get(0).id);
            RecordedRequest next = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("opaque ?&/cursor", next.getRequestUrl().queryParameter("next_page_token"));
            assertNull(next.getRequestUrl().queryParameter("page"));
            for (RecordedRequest sent : List.of(initial, next)) {
                assertEquals("GET", sent.getMethod()); assertEquals("/v1/voices", sent.getRequestUrl().encodedPath());
                assertEquals("personal", sent.getRequestUrl().queryParameter("voice_type"));
                assertEquals("中文 &?/筛选", sent.getRequestUrl().queryParameter("search"));
                assertEquals("zh", sent.getRequestUrl().queryParameter("language_code"));
                assertEquals("private-breeze-key", sent.getHeader("xi-api-key"));
                assertNull(sent.getHeader("Authorization")); assertNull(sent.getRequestUrl().queryParameter("self"));
            }
            assertEquals(2, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void aBrowsablePageUsesTheNativeServerPageOnlyForOffsetFallback() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(true, null, 4, "first", "第一页"));
            BreezeSpeechClient.VoicePage first = BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, null);
            assertEquals(1, first.page); assertTrue(first.hasMore); assertEquals(5, first.nextPage);
            assertEquals("", first.nextPageToken);
            server.enqueue(voices(false, null, 5, "next", "下一页"));
            BreezeSpeechClient.VoicePage next = BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), first.nextPage, first.nextPageToken);
            assertEquals(5, next.page); assertFalse(next.hasMore);
            server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("5", server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().queryParameter("page"));
        }
    }

    @Test public void aBrowsableEmptyTerminalPageNeedsNoSelectedVoiceOrServerPage() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.VOICE_ID, "");
            values.put(BreezeSpeechSettings.VOICE_TYPE, "personal");
            server.enqueue(new MockResponse().setBody("{\"voices\":[],\"has_more\":false}"));
            BreezeSpeechClient.VoicePage result = BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, "");
            assertTrue(result.entries.isEmpty()); assertTrue(result.sourceIds.isEmpty()); assertEquals(0, result.sourceCount);
            assertFalse(result.hasMore); assertEquals(0, result.nextPage); assertEquals("", result.nextPageToken);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void aBrowsablePageRequiresItsOwnKeyAndRejectsInvalidOutboundCursorsBeforeNetwork() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Map<String, Object> noKey = values(server); noKey.put(BreezeSpeechSettings.API_KEY, "");
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(noKey), 1, ""));
                assertTrue(failure.getMessage().contains("API Key"));
            }
            for (String token : List.of("private-cursor\n", "x".repeat(8193))) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 2, token));
                    assertTrue(failure.getMessage().contains("游标")); assertFalse(failure.getMessage().contains("private-cursor"));
                }
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void aBrowsablePageRejectsMalformedContinuationWithoutDisclosingIt() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            List<Object> badTokens = List.of(new JSONArray().put("private-cursor"), "private-cursor\r", "x".repeat(8193));
            for (Object token : badTokens) {
                server.enqueue(new MockResponse().setBody(new JSONObject().put("voices", new JSONArray().put(voice("one", "音色")))
                        .put("has_more", true).put("next_page_token", token).put("page", 1).toString()));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, ""));
                    assertTrue(failure.getMessage().contains("游标")); assertFalse(failure.getMessage().contains("private-cursor"));
                }
            }
            server.enqueue(new MockResponse().setBody("{\"voices\":[],\"has_more\":true}"));
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, ""));
                assertTrue(failure.getMessage().contains("页码或游标"));
            }
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void aBrowsablePageRejectsPartialAndAuthenticationResponsesWithoutRetryOrSynthesis() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int status : List.of(206, 401, 403, 429)) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("private-response private-breeze-key"));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, ""));
                    assertTrue(failure.getMessage().contains("HTTP " + status)); assertFalse(failure.getMessage().contains("private-"));
                    assertNull(failure.getCause());
                }
                assertEquals("GET", server.takeRequest(2, TimeUnit.SECONDS).getMethod());
            }
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void aBrowsablePageDeadlineStopsDelayedHeadersAndAnUnresponsiveConnection() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(voices(false, null, 1, "late", "迟到音色").setHeadersDelay(2, TimeUnit.SECONDS));
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            for (int index = 0; index < 2; index++) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    BreezeSpeechSettings settings = settings(values(server));
                    long started = System.nanoTime();
                    Future<?> result = worker.submit(() -> BreezeSpeechClient.fetchVoicePage(
                            request, settings, 1, "", TimeUnit.MILLISECONDS.toNanos(600)));
                    assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
                    ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                    assertTrue(failure.getCause().getMessage().contains("超时")); assertNull(failure.getCause().getCause());
                    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 1800);
                }
            }
            assertEquals(2, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void cancellingABrowsablePagePreservesCancellationInsteadOfAProviderError() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            BreezeSpeechSettings settings = settings(values(server));
            Future<?> result = worker.submit(() -> BreezeSpeechClient.fetchVoicePage(request, settings, 1, ""));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertEquals(1, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void aCompletedPageClearsOnlyItsDeadlineBeforeTheRequestIsReused() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(false, null, 1, "one", "音色"));
            assertEquals(1, BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, "", TimeUnit.MILLISECONDS.toNanos(600)).entries.size());
            server.enqueue(new MockResponse().setBody("{}").setHeadersDelay(1, TimeUnit.SECONDS));
            assertEquals("{}", request.getProviderJson(server.url("/later-read").toString(), "{}", "breeze"));
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void smallerBreezeCatalogPagesKeepNativeGenderToneAndRepeatedTagsAcrossCursors() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_GENDER, "female"); values.put(BreezeSpeechSettings.VOICE_TONE, "warm");
            values.put(BreezeSpeechSettings.VOICE_TAGS, "[\"温柔 &?/\",\"narration\"]");
            server.enqueue(voices(true, "next ?&/", 1, "one", "第一")); server.enqueue(voices(false, null, 2, "two", "第二"));
            BreezeSpeechClient.VoicePage first = BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, "");
            BreezeSpeechClient.fetchVoicePage(request, settings(values), first.nextPage, first.nextPageToken);
            for (int page = 1; page <= 2; page++) {
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("20", sent.getRequestUrl().queryParameter("page_size"));
                assertEquals("zh", sent.getRequestUrl().queryParameter("language_code"));
                assertEquals("female", sent.getRequestUrl().queryParameter("gender"));
                assertEquals("warm", sent.getRequestUrl().queryParameter("tone"));
                assertEquals(List.of("温柔 &?/", "narration"), sent.getRequestUrl().queryParameterValues("tags"));
                assertNull(sent.getRequestUrl().queryParameter("tag")); assertNull(sent.getRequestUrl().queryParameter("language"));
                if (page == 2) assertEquals("next ?&/", sent.getRequestUrl().queryParameter("next_page_token"));
            }
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void unfilteredBreezeCatalogsAreRejectedBeforeAnyHttpFromBothPublicEntryPoints() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "");
            BreezeSpeechSettings unfiltered = settings(values);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> BreezeSpeechClient.fetchVoicePage(request, unfiltered, 1, "")).getMessage().contains("先设置"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> BreezeSpeechClient.fetchVoices(request, unfiltered)).getMessage().contains("先设置"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void optionalBreezeMetadataAddsRealLabelsAndWrongOptionalShapesKeepTheVoice() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            JSONObject ready = voice("ready", "English Name").put("language_code", "zh").put("gender", "female").put("age", "young")
                    .put("tone", new JSONArray().put("warm")).put("accent", "mandarin_northeastern").put("tags", new JSONArray().put("narration"));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("voices", new JSONArray().put(ready)).put("has_more", false).toString()));
            VoiceCatalog.Entry first = BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, "").entries.get(0);
            assertEquals("English Name", first.name); assertEquals("ready", first.id);
            assertTrue(first.labels.contains("语言：中文")); assertTrue(first.labels.contains("性别：女声"));
            assertTrue(first.labels.contains("语气：温暖")); assertTrue(first.labels.contains("narration"));
            ready.put("language_code", 7).put("gender", new JSONArray()).put("age", new JSONObject())
                    .put("tone", false).put("accent", JSONObject.NULL).put("tags", "unexpected");
            server.enqueue(new MockResponse().setBody(new JSONObject().put("voices", new JSONArray().put(ready)).put("has_more", false).toString()));
            VoiceCatalog.Entry next = BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 2, "").entries.get(0);
            assertEquals("ready", next.id); assertTrue(next.labels.isEmpty());
        }
    }

    @Test public void breezeRejectsResponsesLargerThanTheRequestedTwentyItems() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            JSONArray items = new JSONArray();
            for (int index = 0; index < 21; index++) items.put(voice("voice-" + index, "Voice " + index));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("voices", items).put("has_more", false).toString()));
            assertThrows(Exception.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values(server)), 1, ""));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void breezeCatalogLanguageGenderToneAndTagsNeverChangeSynthesisLanguageOrText() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(BreezeSpeechSettings.VOICE_LANGUAGE, "ja"); values.put(BreezeSpeechSettings.LANGUAGE_CODE, "en");
            values.put(BreezeSpeechSettings.VOICE_GENDER, "female"); values.put(BreezeSpeechSettings.VOICE_TONE, "warm");
            values.put(BreezeSpeechSettings.VOICE_TAGS, "[\"温柔\"]"); server.enqueue(audio("native"));
            values.put(BreezeSpeechSettings.VOICE_CATEGORY, "podcast"); values.put(BreezeSpeechSettings.VOICE_AGE, "[\"young\",\"middle_aged\"]");
            values.put(BreezeSpeechSettings.VOICE_ACCENT, "british"); values.put(BreezeSpeechSettings.VOICE_ORIGIN, "cloned");
            values.put(BreezeSpeechSettings.VOICE_SORT, "name"); values.put(BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc");
            values.put(BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true");
            BreezeSpeechClient.synthesize(request, "Exact text", settings(values));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("en", body.getString("language_code")); assertEquals("Exact text", body.getString("text"));
            assertFalse(body.has("gender")); assertFalse(body.has("tone")); assertFalse(body.has("tags"));
            assertFalse(body.has("age")); assertFalse(body.has("accent")); assertFalse(body.has("primary_category_code"));
            assertFalse(body.has("origin")); assertFalse(body.has("sort")); assertFalse(body.has("favorites_only"));
        }
    }

    @Test public void allNativeBreezeDirectoryFiltersAndMultipleChoicesSurviveCursorNavigation() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(BreezeSpeechSettings.VOICE_TYPE, "default");
            values.put(BreezeSpeechSettings.VOICE_SEARCH, "Voice ?&/"); values.put(BreezeSpeechSettings.VOICE_CATEGORY, "podcast");
            values.put(BreezeSpeechSettings.VOICE_GENDER, "[\"male\",\"female\"]"); values.put(BreezeSpeechSettings.VOICE_AGE, "[\"young\",\"middle_aged\"]");
            values.put(BreezeSpeechSettings.VOICE_TONE, "[\"warm\",\"calm\",\"gentle\",\"bright\"]");
            values.put(BreezeSpeechSettings.VOICE_TAGS, "[\"温柔 &?/\",\"narration\"]");
            values.put(BreezeSpeechSettings.VOICE_ACCENT, "mandarin_beijing"); values.put(BreezeSpeechSettings.VOICE_ACCENT_MODE, "all");
            values.put(BreezeSpeechSettings.VOICE_ORIGIN, "cloned"); values.put(BreezeSpeechSettings.VOICE_SORT, "trend");
            values.put(BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc"); values.put(BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true");
            server.enqueue(voices(true, "next ?&/", 1, "one", "第一")); server.enqueue(voices(false, null, 2, "two", "第二"));
            BreezeSpeechClient.VoicePage first = BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, "");
            BreezeSpeechClient.fetchVoicePage(request, settings(values), first.nextPage, first.nextPageToken);
            for (int page = 1; page <= 2; page++) {
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("20", sent.getRequestUrl().queryParameter("page_size")); assertEquals("default", sent.getRequestUrl().queryParameter("voice_type"));
                assertEquals("zh", sent.getRequestUrl().queryParameter("language_code")); assertEquals("Voice ?&/", sent.getRequestUrl().queryParameter("search"));
                assertEquals(List.of("male", "female"), sent.getRequestUrl().queryParameterValues("gender"));
                assertEquals(List.of("young", "middle_aged"), sent.getRequestUrl().queryParameterValues("age"));
                assertEquals(List.of("warm", "calm", "gentle", "bright"), sent.getRequestUrl().queryParameterValues("tone"));
                assertEquals(List.of("温柔 &?/", "narration"), sent.getRequestUrl().queryParameterValues("tags"));
                assertEquals("podcast", sent.getRequestUrl().queryParameter("primary_category_code"));
                assertEquals("mandarin_beijing", sent.getRequestUrl().queryParameter("accent")); assertEquals("all", sent.getRequestUrl().queryParameter("accent_mode"));
                assertEquals("cloned", sent.getRequestUrl().queryParameter("origin")); assertEquals("trend", sent.getRequestUrl().queryParameter("sort"));
                assertEquals("asc", sent.getRequestUrl().queryParameter("sort_direction")); assertEquals("true", sent.getRequestUrl().queryParameter("favorites_only"));
                assertNull(sent.getRequestUrl().queryParameter("language")); assertNull(sent.getRequestUrl().queryParameter("tag"));
                if (page == 2) assertEquals("next ?&/", sent.getRequestUrl().queryParameter("next_page_token"));
            }
        }
    }

    @Test public void unmarkedAccentAndFavoritesAreValidNarrowingChoicesWhileSortAloneIsNot() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Map<String, Object> base = values(server); base.put(BreezeSpeechSettings.VOICE_LANGUAGE, "");
            base.put(BreezeSpeechSettings.VOICE_SORT, "name"); base.put(BreezeSpeechSettings.VOICE_SORT_DIRECTION, "asc");
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertThrows(IllegalArgumentException.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(base), 1, ""));
            }
            assertEquals(0, server.getRequestCount());
            for (Map.Entry<String, String> filter : Map.of(BreezeSpeechSettings.VOICE_ACCENT_MODE, "unmarked",
                    BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "true").entrySet()) {
                Map<String, Object> values = new HashMap<>(base); values.put(filter.getKey(), filter.getValue());
                server.enqueue(voices(false, null, 1, "voice", "有效音色"));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertEquals(1, BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, "").entries.size());
                }
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertNull(sent.getRequestUrl().queryParameter("accent")); assertNull(sent.getRequestUrl().queryParameter("language_code"));
            }
            assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void conflictingAndMalformedBreezeDirectorySelectionsFailBeforeHttp() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String[] filter : new String[][]{{BreezeSpeechSettings.VOICE_CATEGORY, "unknown"}, {BreezeSpeechSettings.VOICE_AGE, "[\"young\",false]"},
                    {BreezeSpeechSettings.VOICE_ACCENT, "unknown"}, {BreezeSpeechSettings.VOICE_ACCENT_MODE, "none"},
                    {BreezeSpeechSettings.VOICE_ORIGIN, "other"}, {BreezeSpeechSettings.VOICE_SORT, "score"},
                    {BreezeSpeechSettings.VOICE_SORT_DIRECTION, "down"}, {BreezeSpeechSettings.VOICE_FAVORITES_ONLY, "yes"},
                    {BreezeSpeechSettings.VOICE_GENDER, "[\"female\",false]"}, {BreezeSpeechSettings.VOICE_TONE, "[\"happy\"]"}}) {
                Map<String, Object> values = values(server); values.put(filter[0], filter[1]);
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(IllegalArgumentException.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, ""));
                }
            }
            Map<String, Object> accentConflict = values(server); accentConflict.put(BreezeSpeechSettings.VOICE_ACCENT, "british");
            accentConflict.put(BreezeSpeechSettings.VOICE_ACCENT_MODE, "unmarked");
            Map<String, Object> trendConflict = values(server); trendConflict.put(BreezeSpeechSettings.VOICE_SORT, "trend");
            for (Map<String, Object> values : List.of(accentConflict, trendConflict)) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(IllegalArgumentException.class, () -> BreezeSpeechClient.fetchVoicePage(request, settings(values), 1, ""));
                }
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    private static BreezeSpeechSettings settings(Map<String, Object> values) { return new BreezeSpeechSettings(new LocalOptions(values)); }

    private static Map<String, Object> values(MockWebServer server) {
        Map<String, Object> values = new HashMap<>();
        values.put(SpeechSettings.PROVIDER, SpeechSettings.BREEZE);
        values.put(BreezeSpeechSettings.API_KEY, "private-breeze-key");
        values.put(BreezeSpeechSettings.BASE_URL, server.url("/").toString());
        values.put(BreezeSpeechSettings.VOICE_ID, "voice-one");
        return values;
    }

    private static MockResponse audio(String value) {
        return new MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3" + value);
    }

    private static MockResponse job(int code, String id, String status) throws Exception {
        return new MockResponse().setResponseCode(code).setBody(new JSONObject().put("generation_job_id", id)
                .put("history_item_id", id).put("status", status).toString());
    }

    private static void awaitRetryAfterPause(AtomicReference<Thread> owner, Future<?> result) throws Exception {
        // A recorded GET precedes response handling. Wait for the actual cancellable Retry-After
        // pause, rather than hiding the header/cancel race behind a fixed sleep.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (result.isDone()) {
                try {
                    result.get();
                    fail("Synthesis completed before entering the audio 425 wait");
                } catch (ExecutionException premature) {
                    throw new AssertionError("Synthesis failed before the cancellation point: " + premature.getCause(), premature.getCause());
                }
            }
            Thread thread = owner.get();
            if (thread != null && thread.getState() == Thread.State.TIMED_WAITING) {
                for (StackTraceElement frame : thread.getStackTrace()) {
                    if (BreezeSpeechClient.class.getName().equals(frame.getClassName()) && "pauseJob".equals(frame.getMethodName())) return;
                }
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(1));
        }
        Thread thread = owner.get();
        fail("No audio 425 Retry-After pause observed; pending=" + !result.isDone() + "; workerState="
                + (thread == null ? "not-started" : thread.getState()) + "; workerStack="
                + (thread == null ? "[]" : java.util.Arrays.toString(thread.getStackTrace())));
    }

    private static JSONObject voice(String id, String name) throws Exception { return new JSONObject().put("voice_id", id).put("name", name); }

    private static MockResponse voices(boolean more, String token, int page, String id, String name) throws Exception {
        return new MockResponse().setBody(new JSONObject().put("has_more", more).put("next_page_token", token == null ? JSONObject.NULL : token)
                .put("page", page).put("voices", new JSONArray().put(voice(id, name))).toString());
    }
}
