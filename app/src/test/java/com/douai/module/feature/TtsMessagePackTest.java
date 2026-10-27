package com.douai.module.feature;

import org.junit.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class TtsMessagePackTest {
    @Test public void binaryVoiceSamplesMatchTheMessagePackWireSpecification() throws Exception {
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("audio", new byte[]{0, (byte) 255}); sample.put("text", "你好");
        byte[] expected = java.util.HexFormat.of().parseHex("82a5617564696fc40200ffa474657874a6e4bda0e5a5bd");
        assertArrayEquals(expected, TtsMessagePack.encode(sample));
        Map<?, ?> decoded = (Map<?, ?>) MessagePackTestReader.decode(expected);
        assertEquals("你好", decoded.get("text")); assertArrayEquals(new byte[]{0, (byte) 255}, (byte[]) decoded.get("audio"));
    }

    @Test public void nativeNumbersUnicodeAndSpeakerArraysSurviveTheWireRoundTrip() throws Exception {
        Map<String,Object> body = new LinkedHashMap<>();
        body.put("text", "🌸 [happy] 原文"); body.put("opus_bitrate", -1000); body.put("temperature", 0.7);
        body.put("sample_rate", 48000); body.put("normalize", true); body.put("reference_id", List.of("same", "same"));
        body.put("references", List.of(List.of(Map.of("audio", new byte[256], "text", "原音"))));
        Map<?,?> decoded=(Map<?,?>) MessagePackTestReader.decode(TtsMessagePack.encode(body));
        assertEquals(body.get("text"),decoded.get("text")); assertEquals(-1000,((Number)decoded.get("opus_bitrate")).intValue());
        assertEquals(48000,((Number)decoded.get("sample_rate")).intValue()); assertEquals(0.7, (Double)decoded.get("temperature"),0);
        assertEquals(List.of("same","same"),decoded.get("reference_id"));
        Map<?,?> sample=(Map<?,?>)((List<?>)((List<?>)decoded.get("references")).get(0)).get(0);
        assertArrayEquals(new byte[256],(byte[])sample.get("audio"));
    }

    @Test public void unsafeValuesAndOversizedRequestsFailBeforeTransport() {
        assertThrows(IllegalArgumentException.class, () -> TtsMessagePack.encode(Map.of("temperature", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> TtsMessagePack.encode(new byte[SpeechHttpClient.MAX_REFERENCE_REQUEST_BYTES + 1]));
    }
}
