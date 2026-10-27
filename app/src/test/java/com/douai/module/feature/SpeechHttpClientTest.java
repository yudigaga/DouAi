package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class SpeechHttpClientTest {
    @Test public void providerJsonUsesItsOwnMethodAndRequiresTheDeclaredSuccessStatus() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setResponseCode(202).setBody("{\"generation_job_id\":\"job\"}"));
            assertTrue(request.postProviderJson(server.url("/job").toString(),"{}","{\"text\":\"正文\"}","breeze",202).contains("job"));
            RecordedRequest sent = server.takeRequest(2,TimeUnit.SECONDS);
            assertEquals("POST",sent.getMethod()); assertTrue(sent.getHeader("Content-Type").startsWith("application/json"));
            server.enqueue(new MockResponse().setResponseCode(202).setBody("not-ready"));
            Exception failure=assertThrows(Exception.class,()->request.getProviderJson(server.url("/job").toString(),"{}","breeze"));
            assertEquals("Breeze Blue 语音 GET 返回 HTTP 202",failure.getMessage()); assertEquals(2,server.getRequestCount());
        }
    }

    @Test public void providerAudioGetRejectsPartialSuccessAndNeverKeepsAnIncompleteFile() throws Exception {
        java.io.File target=java.io.File.createTempFile("douai-job-audio", ".audio");
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setResponseCode(206).setBody("ID3partial"));
            assertThrows(Exception.class,()->request.getProviderAudio(server.url("/audio").toString(),"{}",target,"breeze"));
            assertFalse(target.exists()); assertEquals(1,server.getRequestCount());
        } finally {java.nio.file.Files.deleteIfExists(target.toPath());}
    }

    @Test public void absoluteProviderBudgetDisconnectsABlockedReadAndDeletesItsOutput() throws Exception {
        ExecutorService worker=Executors.newSingleThreadExecutor();
        java.io.File target=java.io.File.createTempFile("douai-budget-audio", ".audio");
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            String endpoint=server.url("/audio").toString();
            request.limitDeadline(System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(800),"breeze");
            Future<?> result=worker.submit(()->request.getProviderAudio(endpoint,"{}",target,"breeze"));
            assertNotNull(server.takeRequest(2,TimeUnit.SECONDS));
            ExecutionException failure=assertThrows(ExecutionException.class,()->result.get(3,TimeUnit.SECONDS));
            assertTrue(failure.getCause().getMessage().contains("超时"));assertFalse(target.exists());
            assertEquals(1,server.getRequestCount());
        } finally {worker.shutdownNow();java.nio.file.Files.deleteIfExists(target.toPath());}
    }

    @Test public void nativeNotReadyStatusKeepsOnlyTheNumericRetryDelay() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setResponseCode(425).setHeader("Retry-After","2").setBody("private-response"));
            SpeechHttpClient.ProviderHttpFailure failure=assertThrows(SpeechHttpClient.ProviderHttpFailure.class,
                    ()->request.getProviderAudio(server.url("/audio").toString(),"{}",null,"breeze"));
            assertEquals(425,failure.status);assertEquals(2,failure.retryAfterSeconds);
            assertEquals("Breeze Blue 语音 GET 返回 HTTP 425",failure.getMessage());assertNull(failure.getCause());
        }
    }

    @Test public void streamedAudioKeepsOnlyItsProbeInMemoryAndPreservesExactFileBytes() throws Exception {
        java.io.File target = java.io.File.createTempFile("douai-stream-audio", ".audio");
        byte[] audio = new byte[2 * 1024 * 1024];
        System.arraycopy("ID3".getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, audio, 0, 3);
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "audio/mpeg").setBody(new Buffer().write(audio)));
            server.start();
            SpeechHttpClient.Resource resource = request.fetchResource(server.url("/large").toString(), "{}", target);
            assertNull("streamed audio must not retain the full response array", resource.bytes);
            assertSame(target, resource.file); assertEquals(audio.length, resource.sizeBytes());
            assertTrue(resource.isAudio()); assertEquals(".mp3", resource.audioExtension());
            assertArrayEquals(audio, java.nio.file.Files.readAllBytes(target.toPath()));
        } finally { java.nio.file.Files.deleteIfExists(target.toPath()); }
    }

    @Test public void streamedJsonAndWhitespaceSniffingKeepTheExistingProviderSemantics() throws Exception {
        java.io.File target = java.io.File.createTempFile("douai-stream-json", ".audio");
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            String json = " ".repeat(20_000) + "{\"url\":\"https://example.test/audio.mp3\"}";
            server.enqueue(new MockResponse().addHeader("Content-Type", "audio/mpeg").setBody(json));
            server.start();
            SpeechHttpClient.Resource resource = request.fetchResource(server.url("/json").toString(), "{}", target);
            assertFalse(resource.isAudio()); assertEquals(json, resource.text());
        } finally { java.nio.file.Files.deleteIfExists(target.toPath()); }
    }

    @Test public void rejectedStreamRemovesItsPartialOutput() throws Exception {
        java.io.File target = java.io.File.createTempFile("douai-stream-failed", ".audio");
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("x").setHeader("Content-Length", SpeechHttpClient.MAX_AUDIO_BYTES + 1));
            server.start();
            assertThrows(IllegalArgumentException.class,
                    () -> request.fetchResource(server.url("/too-large").toString(), "{}", target));
            assertFalse(target.exists());
        } finally { java.nio.file.Files.deleteIfExists(target.toPath()); }
    }
    @Test public void arbitraryParameterNamesBindTextAndVoiceWithFullUrlEncoding() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("{\"url\":\"https://example.test/sound.mp3\"}"));
            server.start();
            String url = SpeechHttpClient.resolveTemplate(server.url("/speech/") + "{voice}?ID=7&content={text}",
                    "你好 & + /? #", "voice/1&x=2");
            request.fetchText(url, "{\"X-Key\":\"fixture\"}");
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("你好 & + /? #", sent.getRequestUrl().queryParameter("content"));
            assertEquals("7", sent.getRequestUrl().queryParameter("ID"));
            assertEquals("/speech/voice%2F1%26x%3D2", sent.getRequestUrl().encodedPath());
            assertEquals("fixture", sent.getHeader("X-Key"));
        }
    }

    @Test public void independentVoiceGetUsesItsOwnParametersAndHeaders() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("{\"sounds\":[{\"ID\":3,\"label\":\"Warm\"}]}"));
            server.start();
            SpeechSettings settings = new SpeechSettings(new LocalOptions(Map.of(
                    SpeechSettings.VOICE_GET_TEMPLATE, server.url("/voices") + "?access=voice-key",
                    SpeechSettings.VOICE_HEADERS, "{\"X-Voice\":\"list-only\"}",
                    SpeechSettings.HEADERS, "{\"Authorization\":\"main-only\"}",
                    SpeechSettings.VOICE_LIST_PATH, "sounds", SpeechSettings.VOICE_ID_PATH, "ID",
                    SpeechSettings.VOICE_NAME_PATH, "label")));
            assertEquals("3", request.fetchVoices(settings).get(0).id);
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("list-only", sent.getHeader("X-Voice"));
            assertNull(sent.getHeader("Authorization"));
            assertEquals("voice-key", sent.getRequestUrl().queryParameter("access"));
        }
    }

    @Test public void acceptsBinaryAudioOrSelectedNestedJsonAndTxtLinks() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "audio/wav")
                    .setBody(new Buffer().writeUtf8("RIFF0000WAVEfixture")));
            server.start();
            SpeechHttpClient.Resource binary = request.fetchResource(server.url("/speech").toString(), "{}");
            assertTrue(binary.isAudio());
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl(
                    "{\"data\":{\"speech\":[\"https://example.test/a.mp3\"]},\"cover\":\"https://example.test/c.jpg\"}",
                    "data.speech"));
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl(
                    "audio: https://example.test/a.mp3\nname: Warm", "audio"));
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl(
                    "{\"data\":{\"audio_url\":\"https://example.test/a.mp3\"},\"cover\":\"https://example.test/c.jpg\"}", ""));
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl("https://example.test/a.mp3", ""));
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl("https://example.test/a.mp3", "url"));
            assertEquals("https://example.test/a.mp3", SpeechHttpClient.audioUrl("\"https://example.test/a.mp3\"", "url"));
            assertEquals("https://example.test/b.mp3", SpeechHttpClient.audioUrl(
                    "{\"data\":{\"audio\":[\"https://example.test/a.mp3\",\"https://example.test/b.mp3\"]}}", "data.audio[1]"));
        }
        assertThrows(IllegalArgumentException.class, () -> SpeechHttpClient.audioUrl(
                "{\"one\":\"https://example.test/a.mp3\",\"two\":\"https://example.test/b.mp3\"}", ""));
        assertThrows(IllegalArgumentException.class, () -> SpeechHttpClient.audioUrl(
                "{\"url\":\"https://example.test/a.mp3\"}", "missing"));
    }

    @Test public void charsetIsHonoredAndMalformedTextFailsWithSafeMessages() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().addHeader("Content-Type", "text/plain; charset=ISO-8859-1")
                    .setBody(new Buffer().write(new byte[]{'c', 'a', 'f', (byte) 0xe9})));
            server.enqueue(new MockResponse().setBody(new Buffer().write(new byte[]{(byte) 0xc3, 0x28})));
            server.start();
            assertEquals("caf\u00e9", request.fetchText(server.url("/voices").toString(), "{}"));
            Exception failure = assertThrows(IllegalArgumentException.class,
                    () -> request.fetchText(server.url("/voices").toString(), "{}"));
            assertNull(failure.getCause());
        }
    }

    @Test public void rejectsRedirectsAndInvalidHeadersWithoutForwardingSecrets() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer other = new MockWebServer();
             SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.start(); other.start();
            server.enqueue(new MockResponse().setResponseCode(302).addHeader("Location", other.url("/capture")));
            Exception failure = assertThrows(IllegalArgumentException.class, () -> request.fetchText(
                    server.url("/speech") + "?apikey=private-key&text=private-text", "{\"Authorization\":\"private-token\"}"));
            assertFalse(failure.getMessage().contains("private"));
            assertNull(failure.getCause());
            assertEquals(0, other.getRequestCount());
            for (String headers : new String[]{"[]", "{\"Host\":\"fixture\"}", "{\"X-Key\":42}",
                    "{\"X-Key\":\"private-token\\r\\nInjected: value\"}"}) {
                assertThrows(IllegalArgumentException.class, () -> request.fetchText(server.url("/speech").toString(), headers));
            }
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void boundsTextAndAudioResponsesWithKnownAndChunkedLengths() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("x").setHeader("Content-Length", SpeechHttpClient.MAX_AUDIO_BYTES + 1));
            server.enqueue(new MockResponse().setChunkedBody("x".repeat(SpeechHttpClient.MAX_TEXT_BYTES + 1), 8192));
            server.start();
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> request.fetchResource(server.url("/audio").toString(), "{}")).getMessage().contains("20 MiB"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> request.fetchText(server.url("/voices").toString(), "{}")).getMessage().contains("2 MiB"));
        }
    }

    @Test public void stoppingDisconnectsBlockedRequestsAndPreventsFurtherRequests() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE));
            server.start();
            Future<String> future = worker.submit(() -> request.fetchText(server.url("/voices").toString(), "{}"));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> future.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException);
            assertThrows(CancellationException.class, () -> request.fetchText(server.url("/voices").toString(), "{}"));
            assertEquals(1, server.getRequestCount());
        } finally { worker.shutdownNow(); }
    }

    @Test public void originsIncludeSchemeHostAndEffectivePort() {
        assertTrue(SpeechHttpClient.sameOrigin("https://api.test/speech?key=abc", "https://api.test:443/audio.mp3"));
        assertFalse(SpeechHttpClient.sameOrigin("http://api.test/a", "https://api.test/b"));
        assertFalse(SpeechHttpClient.sameOrigin("https://api.test/a", "https://cdn.test/b"));
        assertFalse(SpeechHttpClient.sameOrigin("https://api.test:444/a", "https://api.test/b"));
    }
}
