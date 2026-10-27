package com.douai.module.feature;

import com.douai.module.config.FishSpeechSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import okhttp3.mockwebserver.SocketPolicy;
import okio.Buffer;

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

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class FishSpeechClientTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void nativeSchemaPinsEngineHeaderSeparatelyFromReferenceAndPreservesOriginalText() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(FishSpeechSettings.BASE_URL, server.url("/gateway/v1/").toString());
            values.put(FishSpeechSettings.ENGINE, "fish-audio/s2-pro");
            values.put(FishSpeechSettings.REFERENCE_ID, "reference /?&");
            values.put(FishSpeechSettings.SPEED, "1.5"); values.put(FishSpeechSettings.VOLUME_DB, "-8.5");
            values.put(FishSpeechSettings.NORMALIZE_LOUDNESS, "false");
            values.put(FishSpeechSettings.SAMPLE_RATE, "32000"); values.put(FishSpeechSettings.MP3_BITRATE, "192");
            values.put(FishSpeechSettings.LANGUAGE, "zh_CN"); values.put(FishSpeechSettings.QUALITY_GUARD, "true");
            values.put(FishSpeechSettings.PRONUNCIATION_DICTIONARY, "[{\"id\":\"dict-id\",\"version\":\"v2\"}]");
            values.put("tts_minimax_api_key", "other-key"); values.put("tts_breeze_instructions", "unused-instructions");
            String text = "  [happy] 原文\n保留情绪标签与空格。  ";
            server.enqueue(audio("fixture"));
            assertTrue(FishSpeechClient.synthesize(request, text, settings(values)).isAudio());
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(sent); assertEquals("/gateway/v1/tts", sent.getPath()); assertEquals("POST", sent.getMethod());
            assertEquals("Bearer private-fish-key", sent.getHeader("Authorization")); assertEquals("s2-pro", sent.getHeader("model"));
            assertNull(sent.getHeader("xi-api-key"));
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(19, body.length()); assertEquals(text, body.getString("text"));
            assertEquals("reference /?&", body.getString("reference_id")); assertEquals("zh-CN", body.getString("language"));
            assertEquals(32000, body.getInt("sample_rate")); assertEquals(192, body.getInt("mp3_bitrate"));
            JSONObject prosody = body.getJSONObject("prosody"); assertEquals(3, prosody.length());
            assertEquals(1.5, prosody.getDouble("speed"), 0); assertEquals(-8.5, prosody.getDouble("volume"), 0);
            assertFalse(prosody.getBoolean("normalize_loudness"));
            assertEquals("quality-guard", body.getJSONArray("features").getString(0));
            assertEquals("v2", body.getJSONArray("pronunciation_dictionary").getJSONObject(0).getString("version"));
            for (String field : List.of("model", "model_id", "voice_settings", "instructions", "emotion", "streaming", "opus_bitrate")) assertFalse(field, body.has(field));
            assertFalse(body.toString().contains("other-key")); assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void defaultsOmitOptionalReferenceLanguageSampleRateAndFeatures() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(audio("default")); FishSpeechClient.synthesize(request, "正文", settings(values(server)));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS); assertNotNull(sent);
            assertEquals("s2.1-pro", sent.getHeader("model")); JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertEquals(14, body.length()); assertEquals("mp3", body.getString("format")); assertEquals(128, body.getInt("mp3_bitrate"));
            assertEquals(0, body.getJSONObject("prosody").getDouble("volume"), 0);
            assertTrue(body.getJSONObject("prosody").getBoolean("normalize_loudness"));
            assertEquals(0.7, body.getDouble("temperature"), 0); assertEquals(0.7, body.getDouble("top_p"), 0);
            assertEquals(300, body.getInt("chunk_length")); assertEquals(50, body.getInt("min_chunk_length"));
            assertEquals(1024, body.getInt("max_new_tokens")); assertEquals(1.2, body.getDouble("repetition_penalty"), 0);
            assertEquals(1, body.getDouble("early_stop_threshold"), 0); assertTrue(body.getBoolean("condition_on_previous_chunks"));
            for (String field : List.of("reference_id", "language", "sample_rate", "features", "pronunciation_dictionary")) assertFalse(body.has(field));
        }
    }

    @Test public void opusUsesNativeBpsAndWavOmitsBothBitrateFields() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String format : List.of("opus", "wav")) {
                Map<String, Object> values = values(server); values.put(FishSpeechSettings.FORMAT, format);
                values.put(FishSpeechSettings.LANGUAGE, "auto");
                server.enqueue(audio(format));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) { FishSpeechClient.synthesize(request, "正文", settings(values)); }
                JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
                assertFalse(body.has("mp3_bitrate")); assertFalse(body.has("language"));
                if ("opus".equals(format)) assertEquals(-1000, body.getInt("opus_bitrate")); else assertFalse(body.has("opus_bitrate"));
            }
        }
    }

    @Test public void fishDoesNotInheritBreezeTextLimitOrRewriteInlinePronunciation() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(FishSpeechSettings.PRONUNCIATION_DICTIONARY, "[{\"items\":[{\"key\":\"DouAi\",\"value\":\"抖爱\",\"case_sensitive\":true}]}]");
            String text = "🌸".repeat(2001); server.enqueue(audio("long")); FishSpeechClient.synthesize(request, text, settings(values));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals(text, body.getString("text"));
            assertTrue(body.getJSONArray("pronunciation_dictionary").getJSONObject(0).getJSONArray("items").getJSONObject(0).getBoolean("case_sensitive"));
        }
    }

    @Test public void rawPcmAndUnknownEnginesFailBeforeAnyPaidRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (Map.Entry<String, String> entry : Map.of(FishSpeechSettings.FORMAT, "pcm", FishSpeechSettings.ENGINE, "unknown-engine").entrySet()) {
                Map<String, Object> values = values(server); values.put(entry.getKey(), entry.getValue());
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(Exception.class, () -> FishSpeechClient.synthesize(request, "正文", settings(values)));
                }
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void completeChunkedBinaryAudioStreamsIntoTheOwnedFile() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            byte[] data = new byte[3 * 1024 * 1024]; System.arraycopy("ID3".getBytes(StandardCharsets.US_ASCII), 0, data, 0, 3);
            server.enqueue(new MockResponse().setHeader("Content-Type", "audio/mpeg").setChunkedBody(new Buffer().write(data), 4096));
            File output = new File(temporary.getRoot(), "owned.audio");
            SpeechHttpClient.Resource result = FishSpeechClient.synthesize(request, "正文", settings(values(server)), output);
            assertNull(result.bytes); assertEquals(output, result.file); assertTrue(result.isAudio()); assertEquals(data.length, output.length());
        }
    }

    @Test public void onlyHttp200AudioIsAcceptedWithoutRetryOrRemoteBodyDisclosure() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (int status : new int[]{202, 206, 400, 401, 402, 403, 422, 429, 503, 504}) {
                server.enqueue(new MockResponse().setResponseCode(status).setBody("private-fish-key private-text private-reason"));
                File output = new File(temporary.getRoot(), status + ".audio");
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    Exception failure = assertThrows(Exception.class, () -> FishSpeechClient.synthesize(request, "private-text", settings(values(server)), output));
                    assertTrue(failure.getMessage(), failure.getMessage().contains("HTTP " + status));
                    assertFalse(failure.getMessage().contains("private-")); assertNull(failure.getCause()); assertFalse(output.exists());
                }
            }
            assertEquals(10, server.getRequestCount());
        }
    }

    @Test public void redirectsAndJsonAudioUrlsAreNotFollowed() throws Exception {
        try (MockWebServer server = new MockWebServer(); MockWebServer external = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(302).setHeader("Location", external.url("/audio")));
            server.enqueue(new MockResponse().setHeader("Content-Type", "application/json").setBody("{\"audio_url\":\"" + external.url("/audio") + "\"}"));
            for (int index = 0; index < 2; index++) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) { assertThrows(Exception.class, () -> FishSpeechClient.synthesize(request, "正文", settings(values(server)))); }
            }
            assertEquals(2, server.getRequestCount()); assertEquals(0, external.getRequestCount());
        }
    }

    @Test public void incompleteOrOversizedResponsesDeletePartialOutput() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(audio("short").setHeader("Content-Length", 1024).setSocketPolicy(SocketPolicy.DISCONNECT_AT_END));
            server.enqueue(audio("large").setHeader("Content-Length", SpeechHttpClient.MAX_AUDIO_BYTES + 1));
            for (int index = 0; index < 2; index++) {
                File output = new File(temporary.getRoot(), "partial-" + index + ".audio");
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) { assertThrows(Exception.class, () -> FishSpeechClient.synthesize(request, "正文", settings(values(server)), output)); }
                assertFalse(output.exists());
            }
        }
    }

    @Test public void cancellationDisconnectsSynthesisAndDeletesTheOutput() throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE)); File output = new File(temporary.getRoot(), "cancelled.audio");
            FishSpeechSettings settings = settings(values(server)); Future<?> result = worker.submit(() -> FishSpeechClient.synthesize(request, "正文", settings, output));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS)); request.cancel();
            ExecutionException failure = assertThrows(ExecutionException.class, () -> result.get(3, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof CancellationException); assertFalse(output.exists());
        } finally { worker.shutdownNow(); }
    }

    @Test public void s1OmitsIgnoredLoudnessAndDisabledNormalizationOmitsUnusedLanguage() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String,Object> values = values(server);
            values.put(FishSpeechSettings.ENGINE, "s1"); values.put(FishSpeechSettings.NORMALIZE, "false");
            values.put(FishSpeechSettings.LANGUAGE, "zh-CN"); server.enqueue(audio("native"));
            FishSpeechClient.synthesize(request, "正文", settings(values));
            RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
            JSONObject body = new JSONObject(sent.getBody().readUtf8());
            assertFalse(body.getJSONObject("prosody").has("normalize_loudness")); assertFalse(body.has("language"));
            assertEquals("s1", sent.getHeader("model"));
        }
    }

    @Test public void voicePaginationUsesNativeModelPathScopeAndEscapedTitle() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(2, true, "first", "第一音色")); server.enqueue(voices(2, false, "second", "第二\u0085音色"));
            Map<String, Object> values = values(server); values.put(FishSpeechSettings.VOICE_SCOPE, "public"); values.put(FishSpeechSettings.VOICE_SEARCH, "标题 &?/测试");
            List<VoiceCatalog.Entry> result = FishSpeechClient.fetchVoices(request, settings(values));
            assertEquals(2, result.size()); assertEquals("第二 音色", result.get(1).name);
            RecordedRequest first = server.takeRequest(2, TimeUnit.SECONDS), second = server.takeRequest(2, TimeUnit.SECONDS);
            assertEquals("/model", first.getRequestUrl().encodedPath()); assertEquals("false", second.getRequestUrl().queryParameter("self"));
            assertEquals("标题 &?/测试", second.getRequestUrl().queryParameter("title"));
            assertEquals("2", second.getRequestUrl().queryParameter("page_number")); assertEquals("20", second.getRequestUrl().queryParameter("page_size"));
            assertEquals("zh", second.getRequestUrl().queryParameter("language"));
            assertEquals("Bearer private-fish-key", second.getHeader("Authorization")); assertNull(second.getHeader("model"));
        }
    }

    @Test public void exactTotalCanFinishWithoutHasMoreAndAnEmptyOwnedLibraryIsNormal() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String,Object> own=values(server);own.put(FishSpeechSettings.VOICE_SCOPE,"self");
            server.enqueue(voices(2, null, "first", "一")); server.enqueue(voices(2, null, "second", "二"));
            assertEquals(2, FishSpeechClient.fetchVoices(request, settings(own)).size());
            assertEquals("true", server.takeRequest(2, TimeUnit.SECONDS).getRequestUrl().queryParameter("self")); server.takeRequest(2, TimeUnit.SECONDS);
            server.enqueue(new MockResponse().setBody("{\"total\":0,\"items\":[],\"has_more\":null}"));
            assertTrue(FishSpeechClient.fetchVoices(request, settings(own)).isEmpty());
        }
    }

    @Test public void windowedAndInexactResultsRemainUsableWhileRepeatedPagesFail() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setBody("{\"total\":1000,\"items\":[],\"has_more\":false,\"window_limited\":true}"));
            server.enqueue(new MockResponse().setBody("{\"total\":1000,\"items\":[],\"total_is_exact\":false}"));
            server.enqueue(voices(3, true, "same", "一")); server.enqueue(voices(3, true, "same", "一"));
            for (int index = 0; index < 2; index++) {
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) { assertTrue(FishSpeechClient.fetchVoices(request, settings(values(server))).isEmpty()); }
            }
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertThrows(Exception.class, () -> FishSpeechClient.fetchVoices(request, settings(values(server))));
            }
            assertEquals(4, server.getRequestCount());
        }
    }

    @Test public void invalidCatalogBodiesCannotSupplyFakeHttpStatusOrLeakPrivateValues() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody("HTTP 504 private-key private-text"));
            Exception failure = assertThrows(Exception.class, () -> FishSpeechClient.fetchVoices(request, settings(values(server))));
            assertFalse(failure.getMessage().contains("504")); assertFalse(failure.getMessage().contains("private-")); assertNull(failure.getCause());
        }
    }

    @Test public void aRepeatedLastPageCannotReplaceTheCachedCatalogWithPartialResults() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            server.enqueue(voices(2, true, "same", "一")); server.enqueue(voices(2, false, "same", "一"));
            Exception failure = assertThrows(Exception.class, () -> FishSpeechClient.fetchVoices(request, settings(values(server))));
            assertTrue(failure.getMessage().contains("未前进")); assertEquals(2, server.getRequestCount());
        }
    }

    @Test public void multiSpeakerIdsKeepOrderAndDoNotRewriteTheTaggedScript() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String,Object> values=values(server); values.put(FishSpeechSettings.VOICE_MODE,"multi");
            values.put(FishSpeechSettings.REFERENCE_IDS,"[\"voice-a\",\"voice-a\"]");
            String text="<|speaker:0|>[happy] 你好。<|speaker:1|>[sad] 再见。";
            server.enqueue(audio("dialogue")); FishSpeechClient.synthesize(request,text,settings(values));
            JSONObject body=new JSONObject(server.takeRequest(2,TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals(text,body.getString("text")); assertEquals(2,body.getJSONArray("reference_id").length());
            assertEquals("voice-a",body.getJSONArray("reference_id").getString(1)); assertFalse(body.has("references"));
        }
    }

    @Test public void invalidSpeakerTagsFailLocallyWithoutChargingASynthesisRequest() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for(String text:List.of("没有角色标签", "<|speaker:2|>越界", "<|speaker:bad|>格式错误")) {
                Map<String,Object> values=values(server); values.put(FishSpeechSettings.VOICE_MODE,"multi");
                values.put(FishSpeechSettings.REFERENCE_IDS,"[\"a\",\"b\"]");
                try(SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
                    Exception failure=assertThrows(Exception.class,()->FishSpeechClient.synthesize(request,text,settings(values)));
                    assertTrue(failure.getMessage().contains("说话人"));
                }
            }
            assertEquals(0,server.getRequestCount());
        }
    }

    @Test public void referenceAudioIsRealMessagePackBinaryAndKeepsTheExactTranscript() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        File root=new File(context.getFilesDir(),"douai-voice-source"); assertTrue(root.isDirectory()||root.mkdirs());
        File file=new File(root,"fish-reference.m4a"); // Importer suffix is irrelevant; validate the actual FLAC bytes.
        byte[] original="fLaCprivate-reference".getBytes(StandardCharsets.UTF_8);
        java.nio.file.Files.write(file.toPath(),original);
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            Map<String,Object> values=values(server); values.put(FishSpeechSettings.VOICE_MODE,"references");
            String transcript="  样本逐字原文\n保留停顿。  ";
            values.put(FishSpeechSettings.REFERENCE_SAMPLES,new JSONArray().put(new JSONArray().put(new JSONObject()
                    .put("path",file.getAbsolutePath()).put("text",transcript))).toString());
            server.enqueue(audio("clone")); String script="新文本[happy]";
            assertTrue(FishSpeechClient.synthesize(request,script,settings(values),null,context).isAudio());
            RecordedRequest sent=server.takeRequest(2,TimeUnit.SECONDS);
            assertEquals("application/msgpack",sent.getHeader("Content-Type")); assertEquals("Bearer private-fish-key",sent.getHeader("Authorization"));
            Map<?,?> body=(Map<?,?>)MessagePackTestReader.decode(sent.getBody().readByteArray());
            assertEquals(script,body.get("text")); assertFalse(body.containsKey("reference_id"));
            Map<?,?> reference=(Map<?,?>)((List<?>)body.get("references")).get(0);
            assertArrayEquals(original,(byte[])reference.get("audio")); assertEquals(transcript,reference.get("text"));
            assertFalse(body.toString().contains(file.getAbsolutePath())); assertEquals(1,server.getRequestCount());
        } finally { java.nio.file.Files.delete(file.toPath()); }
    }

    @Test public void multiSpeakerReferenceGroupsUseMatchingIdentifiersAndNestedSamples() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        File root=new File(context.getFilesDir(),"douai-voice-source"); assertTrue(root.isDirectory()||root.mkdirs());
        File file=new File(root,"two-speakers.mp3"); java.nio.file.Files.writeString(file.toPath(),"ID3fixture");
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            Map<String,Object> values=values(server); values.put(FishSpeechSettings.VOICE_MODE,"references");
            JSONObject sample=new JSONObject().put("path",file.getAbsolutePath()).put("text","参考原文");
            values.put(FishSpeechSettings.REFERENCE_SAMPLES,new JSONArray().put(new JSONArray().put(sample))
                    .put(new JSONArray().put(sample)).toString());
            server.enqueue(audio("two"));
            FishSpeechClient.synthesize(request,"<|speaker:0|>A<|speaker:1|>B",settings(values),null,context);
            Map<?,?> body=(Map<?,?>)MessagePackTestReader.decode(server.takeRequest(2,TimeUnit.SECONDS).getBody().readByteArray());
            assertEquals(List.of("speaker-0","speaker-1"),body.get("reference_id"));
            List<?> groups=(List<?>)body.get("references"); assertEquals(2,groups.size());
            assertTrue(((List<?>)groups.get(0)).get(0) instanceof Map);
        } finally { java.nio.file.Files.delete(file.toPath()); }
    }

    @Test public void arbitraryPrivateFilesAndUnsupportedAudioAreNeverSentAsVoiceReferences() throws Exception {
        android.content.Context context=org.robolectric.RuntimeEnvironment.getApplication();
        File outside=new File(context.getFilesDir(),"not-an-import.mp3"); java.nio.file.Files.writeString(outside.toPath(),"ID3private");
        File root=new File(context.getFilesDir(),"douai-voice-source"); assertTrue(root.isDirectory()||root.mkdirs());
        File wrongFormat=new File(root,"unsupported.mp3"); java.nio.file.Files.writeString(wrongFormat.toPath(),"OggSunsupported");
        try(MockWebServer server=new MockWebServer()) {
            for(File file:List.of(outside,wrongFormat)) {
                Map<String,Object> values=values(server); values.put(FishSpeechSettings.VOICE_MODE,"references");
                values.put(FishSpeechSettings.REFERENCE_SAMPLES,new JSONArray().put(new JSONArray().put(new JSONObject()
                        .put("path",file.getAbsolutePath()).put("text","参考原文"))).toString());
                try(SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
                    Exception failure=assertThrows(Exception.class,()->FishSpeechClient.synthesize(request,"正文",settings(values),null,context));
                    assertFalse(failure.getMessage().contains(file.getAbsolutePath()));
                }
            }
            assertEquals(0,server.getRequestCount());
        } finally {java.nio.file.Files.delete(outside.toPath());java.nio.file.Files.delete(wrongFormat.toPath());}
    }

    @Test public void nonTtsAndUnreadyVoicePagesAreFilteredWithoutBreakingSourcePagination() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody(new JSONObject().put("total",3).put("has_more",true)
                    .put("items",new JSONArray().put(new JSONObject().put("_id","svc").put("title","转换模型")
                            .put("type","svc").put("state","trained")).put(new JSONObject().put("_id","training")
                            .put("title","未就绪").put("type","tts").put("state","training"))).toString()));
            server.enqueue(voices(3,false,"ready","可用音色"));
            List<VoiceCatalog.Entry> result=FishSpeechClient.fetchVoices(request,settings(values(server)));
            assertEquals(1,result.size());assertEquals("ready",result.get(0).id);assertEquals(2,server.getRequestCount());
        }
    }

    @Test public void partialHttpVoicePagesAreNotCachedAsACompleteCatalog() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(voices(1,false,"ready","音色").setResponseCode(206));
            Exception failure=assertThrows(Exception.class,()->FishSpeechClient.fetchVoices(request,settings(values(server))));
            assertTrue(failure.getMessage().contains("HTTP 206"));assertEquals(1,server.getRequestCount());
        }
    }

    @Test public void aValidWindowLimitedPublicPageStillProvidesSelectableReadyVoices() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            JSONObject response=new JSONObject().put("total",100000).put("total_is_exact",false)
                    .put("window_limited",true).put("has_more",true).put("max_offset",1000)
                    .put("accessible_upper_bound",1000).put("items",new JSONArray().put(new JSONObject()
                            .put("_id","public-ready").put("title","现成公开音色").put("type","tts").put("state","trained")));
            server.enqueue(new MockResponse().setBody(response.toString()));
            Map<String,Object> values=values(server);values.put(FishSpeechSettings.VOICE_SCOPE,"public");
            List<VoiceCatalog.Entry> voices=FishSpeechClient.fetchVoices(request,settings(values));
            assertEquals(1,voices.size());assertEquals("public-ready",voices.get(0).id);
            assertEquals(1,server.getRequestCount());
        }
    }

    @Test public void aCapturedPublicWindowPageKeepsItsMetadataWithinTheSmallerPageBudget() throws Exception {
        String fixture;
        try(java.io.InputStream input=getClass().getResourceAsStream("/fish-public-window-page.json")) {
            assertNotNull(input);fixture=new String(input.readAllBytes(),StandardCharsets.UTF_8);
        }
        JSONObject captured = new JSONObject(fixture); JSONArray bounded = new JSONArray();
        for (int index = 0; index < 20; index++) bounded.put(captured.getJSONArray("items").get(index));
        fixture = captured.put("items", bounded).toString();
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody(fixture));
            Map<String,Object> values=values(server);values.put(FishSpeechSettings.API_KEY,"");
            values.put(SpeechSettings.MINIMAX_API_KEY,"unrelated-key");
            FishSpeechClient.VoicePage page=FishSpeechClient.fetchVoicePage(request,settings(values),1);
            assertEquals(20,page.entries.size());assertEquals(20,page.sourceCount);assertEquals(1,page.page);
            assertTrue(page.windowLimited);assertFalse(page.totalExact);assertTrue(page.moreKnown);assertTrue(page.hasMore);
            RecordedRequest sent=server.takeRequest(2,TimeUnit.SECONDS);assertEquals("false",sent.getRequestUrl().queryParameter("self"));
            assertNull(sent.getHeader("Authorization"));assertEquals(1,server.getRequestCount());
        }
    }

    @Test public void explicitPageNavigationPreservesSearchAndKeepsTheWindowBoundary() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(voices(1000,true,"one","一"));server.enqueue(new MockResponse().setBody(new JSONObject()
                    .put("total",1000).put("window_limited",true).put("total_is_exact",false).put("has_more",false)
                    .put("items",new JSONArray().put(new JSONObject().put("_id","last").put("title","最后一页")
                            .put("type","tts").put("state","trained"))).toString()));
            Map<String,Object> values=values(server);values.put(FishSpeechSettings.VOICE_SEARCH,"中文 & 音色");
            FishSpeechClient.fetchVoicePage(request,settings(values),1);
            FishSpeechClient.VoicePage last=FishSpeechClient.fetchVoicePage(request,settings(values),10);
            assertEquals("last",last.entries.get(0).id);assertTrue(last.windowLimited);assertTrue(last.moreKnown);assertFalse(last.hasMore);
            RecordedRequest first=server.takeRequest(2,TimeUnit.SECONDS),second=server.takeRequest(2,TimeUnit.SECONDS);
            assertEquals("1",first.getRequestUrl().queryParameter("page_number"));
            assertEquals("10",second.getRequestUrl().queryParameter("page_number"));
            assertEquals("中文 & 音色",second.getRequestUrl().queryParameter("title"));
            assertEquals("Bearer private-fish-key",second.getHeader("Authorization"));assertEquals(2,server.getRequestCount());
        }
    }

    @Test public void inexactUnknownContinuationDoesNotDiscardTheReturnedVoices() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            server.enqueue(new MockResponse().setBody(new JSONObject().put("total",1000).put("total_is_exact",false)
                    .put("items",new JSONArray().put(new JSONObject().put("_id","ready").put("title","可选")
                            .put("type","tts").put("state","trained"))).toString()));
            FishSpeechClient.VoicePage page=FishSpeechClient.fetchVoicePage(request,settings(values(server)),1);
            assertEquals(1,page.entries.size());assertFalse(page.moreKnown);assertFalse(page.totalExact);
            assertThrows(UnsupportedOperationException.class,()->page.entries.clear());
            assertThrows(UnsupportedOperationException.class,()->page.sourceIds.clear());
        }
    }

    @Test public void ownedVoicesStillRequireTheirOwnAccountKeyBeforeAnyRequest() throws Exception {
        try(MockWebServer server=new MockWebServer();SpeechHttpClient.Request request=new SpeechHttpClient.Request()) {
            Map<String,Object> values=values(server);values.put(FishSpeechSettings.API_KEY,"");
            values.put(FishSpeechSettings.VOICE_SCOPE,"self");
            assertThrows(Exception.class,()->FishSpeechClient.fetchVoicePage(request,settings(values),1));
            assertEquals(0,server.getRequestCount());
        }
    }

    @Test public void smallerCatalogPagesKeepFishLanguageAndRepeatedTagFiltersAcrossNavigation() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(FishSpeechSettings.VOICE_TAGS, "[\"温柔 &?/\",\"narration\"]");
            server.enqueue(voices(2, true, "one", "第一")); server.enqueue(voices(2, false, "two", "第二"));
            FishSpeechClient.fetchVoicePage(request, settings(values), 1);
            FishSpeechClient.fetchVoicePage(request, settings(values), 2);
            for (int page = 1; page <= 2; page++) {
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("20", sent.getRequestUrl().queryParameter("page_size"));
                assertEquals("zh", sent.getRequestUrl().queryParameter("language"));
                assertEquals(List.of("温柔 &?/", "narration"), sent.getRequestUrl().queryParameterValues("tag"));
                assertEquals(Integer.toString(page), sent.getRequestUrl().queryParameter("page_number"));
                assertNull(sent.getRequestUrl().queryParameter("tags")); assertNull(sent.getRequestUrl().queryParameter("language_code"));
                assertNull(sent.getRequestUrl().queryParameter("gender")); assertNull(sent.getRequestUrl().queryParameter("tone"));
            }
        }
    }

    @Test public void unfilteredCatalogCallsAreRejectedBeforeHttpEvenThroughTheLegacyEntryPoint() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server); values.put(FishSpeechSettings.VOICE_LANGUAGE, "");
            FishSpeechSettings unfiltered = settings(values);
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> FishSpeechClient.fetchVoicePage(request, unfiltered, 1)).getMessage().contains("先设置"));
            assertTrue(assertThrows(IllegalArgumentException.class,
                    () -> FishSpeechClient.fetchVoices(request, unfiltered)).getMessage().contains("先设置"));
            assertEquals(0, server.getRequestCount());
        }
    }

    @Test public void optionalFishMetadataAddsLabelsButMalformedMetadataDoesNotDiscardReadyVoices() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            JSONObject ready = new JSONObject().put("_id", "ready").put("title", "English Name").put("type", "tts").put("state", "trained")
                    .put("languages", new JSONArray().put("zh")).put("tags", new JSONArray().put("warm").put(true).put("bad\u202e"))
                    .put("gender", "female");
            server.enqueue(new MockResponse().setBody(new JSONObject().put("items", new JSONArray().put(ready)).put("total", 1).toString()));
            VoiceCatalog.Entry first = FishSpeechClient.fetchVoicePage(request, settings(values(server)), 1).entries.get(0);
            assertEquals("English Name", first.name); assertEquals("ready", first.id);
            assertTrue(first.labels.contains("语言：中文")); assertTrue(first.labels.contains("warm"));
            assertFalse(first.labels.stream().anyMatch(label -> label.startsWith("性别：") || label.contains("bad")));
            assertThrows(UnsupportedOperationException.class, first.labels::clear);
            ready.put("languages", 7).put("tags", new JSONObject().put("unexpected", true));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("items", new JSONArray().put(ready)).put("total", 1).toString()));
            VoiceCatalog.Entry next = FishSpeechClient.fetchVoicePage(request, settings(values(server)), 2).entries.get(0);
            assertEquals("ready", next.id); assertTrue(next.labels.isEmpty());
        }
    }

    @Test public void fishRejectsResponsesLargerThanItsRequestedTwentyItemBudget() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            JSONArray items = new JSONArray();
            for (int index = 0; index < 21; index++) items.put(new JSONObject().put("_id", "id-" + index).put("type", "tts").put("state", "trained"));
            server.enqueue(new MockResponse().setBody(new JSONObject().put("items", items).put("total", 21).toString()));
            assertThrows(Exception.class, () -> FishSpeechClient.fetchVoicePage(request, settings(values(server)), 1));
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void fishCatalogLanguageAndTagsNeverChangeTheSynthesisPayload() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(FishSpeechSettings.LANGUAGE, "en-US"); values.put(FishSpeechSettings.VOICE_LANGUAGE, "ja");
            values.put(FishSpeechSettings.VOICE_TAGS, "[\"温柔\"]"); server.enqueue(audio("native"));
            values.put(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"en\",\"zh\"]");
            values.put(FishSpeechSettings.VOICE_AUTHOR_ID, "author-fixture"); values.put(FishSpeechSettings.VOICE_LICENSED, "true");
            values.put(FishSpeechSettings.VOICE_SORT, "created_at");
            FishSpeechClient.synthesize(request, "Exact text", settings(values));
            JSONObject body = new JSONObject(server.takeRequest(2, TimeUnit.SECONDS).getBody().readUtf8());
            assertEquals("en-US", body.getString("language")); assertEquals("Exact text", body.getString("text"));
            assertFalse(body.has("tag")); assertFalse(body.has("tags")); assertFalse(body.has("voice_language"));
            assertFalse(body.has("title_language")); assertFalse(body.has("author_id")); assertFalse(body.has("licensed")); assertFalse(body.has("sort_by"));
        }
    }

    @Test public void allNativeFishDirectoryFiltersAreEncodedAndPreservedOnEveryExplicitPage() throws Exception {
        try (MockWebServer server = new MockWebServer(); SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
            Map<String, Object> values = values(server);
            values.put(FishSpeechSettings.VOICE_LANGUAGE, "[\"zh\",\"en\"]");
            values.put(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"en\",\"ja\"]");
            values.put(FishSpeechSettings.VOICE_TAGS, "[\"温柔 &?/\",\"narration\"]");
            values.put(FishSpeechSettings.VOICE_SEARCH, "Title ?&/"); values.put(FishSpeechSettings.VOICE_AUTHOR_ID, "creator ?&/");
            values.put(FishSpeechSettings.VOICE_LICENSED, "true"); values.put(FishSpeechSettings.VOICE_SORT, "task_count");
            server.enqueue(voices(2, true, "one", "第一")); server.enqueue(voices(2, false, "two", "第二"));
            FishSpeechClient.fetchVoicePage(request, settings(values), 1); FishSpeechClient.fetchVoicePage(request, settings(values), 2);
            for (int page = 1; page <= 2; page++) {
                RecordedRequest sent = server.takeRequest(2, TimeUnit.SECONDS);
                assertEquals("20", sent.getRequestUrl().queryParameter("page_size")); assertEquals("false", sent.getRequestUrl().queryParameter("self"));
                assertEquals(List.of("zh", "en"), sent.getRequestUrl().queryParameterValues("language"));
                assertEquals(List.of("en", "ja"), sent.getRequestUrl().queryParameterValues("title_language"));
                assertEquals(List.of("温柔 &?/", "narration"), sent.getRequestUrl().queryParameterValues("tag"));
                assertEquals("creator ?&/", sent.getRequestUrl().queryParameter("author_id"));
                assertEquals("Title ?&/", sent.getRequestUrl().queryParameter("title"));
                assertEquals("true", sent.getRequestUrl().queryParameter("licensed")); assertEquals("task_count", sent.getRequestUrl().queryParameter("sort_by"));
                assertEquals(Integer.toString(page), sent.getRequestUrl().queryParameter("page_number"));
                assertNull(sent.getRequestUrl().queryParameter("tags")); assertNull(sent.getRequestUrl().queryParameter("primary_category_code"));
            }
        }
    }

    @Test public void fishSortAndFalseLicensedCannotBypassFilteringButTitleLanguageAuthorAndTrueLicensedCanNarrow() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            Map<String, Object> base = values(server); base.put(FishSpeechSettings.VOICE_LANGUAGE, "[]");
            base.put(FishSpeechSettings.VOICE_SORT, "created_at"); base.put(FishSpeechSettings.VOICE_LICENSED, "false");
            try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                assertThrows(IllegalArgumentException.class, () -> FishSpeechClient.fetchVoicePage(request, settings(base), 1));
            }
            assertEquals(0, server.getRequestCount());
            for (Map.Entry<String, String> filter : Map.of(FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\",\"en\"]",
                    FishSpeechSettings.VOICE_AUTHOR_ID, "creator", FishSpeechSettings.VOICE_LICENSED, "true").entrySet()) {
                Map<String, Object> values = new HashMap<>(base); values.put(filter.getKey(), filter.getValue());
                server.enqueue(voices(1, false, "voice", "有效音色"));
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertEquals(1, FishSpeechClient.fetchVoicePage(request, settings(values), 1).entries.size());
                }
            }
            assertEquals(3, server.getRequestCount());
        }
    }

    @Test public void malformedNewFishFiltersAreRejectedBeforeAnyNetworkOrConstructorFallback() throws Exception {
        try (MockWebServer server = new MockWebServer()) {
            for (String[] filter : new String[][]{{FishSpeechSettings.VOICE_LANGUAGE, "[\"zh\",null]"},
                    {FishSpeechSettings.VOICE_TITLE_LANGUAGE, "[\"zh\",\"invalid language\"]"}, {FishSpeechSettings.VOICE_AUTHOR_ID, "creator\u200b"},
                    {FishSpeechSettings.VOICE_LICENSED, "yes"}, {FishSpeechSettings.VOICE_SORT, "name"}}) {
                Map<String, Object> values = values(server); values.put(filter[0], filter[1]);
                try (SpeechHttpClient.Request request = new SpeechHttpClient.Request()) {
                    assertThrows(IllegalArgumentException.class, () -> FishSpeechClient.fetchVoicePage(request, settings(values), 1));
                }
            }
            assertEquals(0, server.getRequestCount());
        }
    }

    private static FishSpeechSettings settings(Map<String, Object> values) { return new FishSpeechSettings(new LocalOptions(values)); }
    private static Map<String, Object> values(MockWebServer server) {
        Map<String, Object> values = new HashMap<>(); values.put(FishSpeechSettings.API_KEY, "private-fish-key");
        values.put(FishSpeechSettings.BASE_URL, server.url("/").toString()); return values;
    }
    private static MockResponse audio(String value) { return new MockResponse().setHeader("Content-Type", "audio/mpeg").setBody("ID3" + value); }
    private static MockResponse voices(int total, Boolean more, String id, String name) throws Exception {
        JSONObject body = new JSONObject().put("total", total).put("items", new JSONArray().put(new JSONObject().put("_id", id).put("title", name).put("type", "tts").put("state", "trained")));
        if (more != null) body.put("has_more", more); return new MockResponse().setBody(body.toString());
    }
}
