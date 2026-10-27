package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/** Small bounded encoder for the native Fish TTS schema, including binary reference audio. */
final class TtsMessagePack {
    private TtsMessagePack() { }

    static byte[] encode(Object value) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) { write(output, bytes, value, 0); }
        return bytes.toByteArray();
    }

    private static void write(DataOutputStream out, ByteArrayOutputStream bytes, Object value, int depth) throws IOException {
        if (depth > 32) throw new Failure("参考请求结构过深");
        if (value == null || value == JSONObject.NULL) out.writeByte(0xc0);
        else if (value instanceof Boolean) out.writeByte((Boolean) value ? 0xc3 : 0xc2);
        else if (value instanceof byte[]) {
            byte[] audio = (byte[]) value;
            length(out, audio.length, 0xc4, 0xc5, 0xc6); out.write(audio);
        } else if (value instanceof String) {
            byte[] text = ((String) value).getBytes(StandardCharsets.UTF_8);
            if (text.length < 32) out.writeByte(0xa0 | text.length); else length(out, text.length, 0xd9, 0xda, 0xdb);
            out.write(text);
        } else if (value instanceof Float || value instanceof Double) {
            double number = ((Number) value).doubleValue();
            if (!Double.isFinite(number)) throw new Failure("生成参数无效");
            out.writeByte(0xcb); out.writeDouble(number);
        } else if (value instanceof Number) {
            long number = ((Number) value).longValue();
            if (number >= -32 && number <= 127) out.writeByte((int) number);
            else if (number >= Byte.MIN_VALUE && number <= Byte.MAX_VALUE) { out.writeByte(0xd0); out.writeByte((int) number); }
            else if (number >= Short.MIN_VALUE && number <= Short.MAX_VALUE) { out.writeByte(0xd1); out.writeShort((int) number); }
            else if (number >= Integer.MIN_VALUE && number <= Integer.MAX_VALUE) { out.writeByte(0xd2); out.writeInt((int) number); }
            else { out.writeByte(0xd3); out.writeLong(number); }
        } else if (value instanceof JSONObject) {
            JSONObject object = (JSONObject) value; collection(out, object.length(), true);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) {
                String key = keys.next(); write(out, bytes, key, depth + 1); write(out, bytes, object.opt(key), depth + 1);
            }
        } else if (value instanceof Map<?, ?>) {
            Map<?, ?> map = (Map<?, ?>) value; collection(out, map.size(), true);
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String)) throw new Failure("参考字段无效");
                write(out, bytes, entry.getKey(), depth + 1); write(out, bytes, entry.getValue(), depth + 1);
            }
        } else if (value instanceof JSONArray) {
            JSONArray array = (JSONArray) value; collection(out, array.length(), false);
            for (int index = 0; index < array.length(); index++) write(out, bytes, array.opt(index), depth + 1);
        } else if (value instanceof List<?>) {
            List<?> array = (List<?>) value; collection(out, array.size(), false);
            for (Object item : array) write(out, bytes, item, depth + 1);
        } else throw new Failure("参考字段无效");
        if (bytes.size() > SpeechHttpClient.MAX_REFERENCE_REQUEST_BYTES) throw new Failure("参考请求超过本模块 10 MiB 限制");
    }

    static final class Failure extends IllegalArgumentException {
        Failure(String reason) { super("Fish Audio 语音" + reason); }
    }

    private static void collection(DataOutputStream out, int length, boolean map) throws IOException {
        if (length < 16) out.writeByte((map ? 0x80 : 0x90) | length);
        else if (length <= 65535) { out.writeByte(map ? 0xde : 0xdc); out.writeShort(length); }
        else { out.writeByte(map ? 0xdf : 0xdd); out.writeInt(length); }
    }

    private static void length(DataOutputStream out, int length, int small, int medium, int large) throws IOException {
        if (length <= 255) { out.writeByte(small); out.writeByte(length); }
        else if (length <= 65535) { out.writeByte(medium); out.writeShort(length); }
        else { out.writeByte(large); out.writeInt(length); }
    }
}
