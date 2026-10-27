package com.douai.module.feature;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Test-only decoder based on the MessagePack wire format, independent of the request builder. */
final class MessagePackTestReader {
    static Object decode(byte[] bytes) throws IOException {
        DataInputStream input = new DataInputStream(new ByteArrayInputStream(bytes));
        Object result = read(input);
        if (input.available() != 0) throw new IOException("Trailing MessagePack data");
        return result;
    }

    private static Object read(DataInputStream input) throws IOException {
        int code = input.readUnsignedByte();
        if (code <= 127) return code;
        if (code >= 224) return (byte) code;
        if ((code & 224) == 160) return text(input, code & 31);
        if ((code & 240) == 128) return map(input, code & 15);
        if ((code & 240) == 144) return list(input, code & 15);
        switch (code) {
            case 0xc0: return null;
            case 0xc2: return false;
            case 0xc3: return true;
            case 0xc4: return bytes(input, input.readUnsignedByte());
            case 0xc5: return bytes(input, input.readUnsignedShort());
            case 0xc6: return bytes(input, input.readInt());
            case 0xcb: return input.readDouble();
            case 0xcc: return input.readUnsignedByte();
            case 0xcd: return input.readUnsignedShort();
            case 0xce: return Integer.toUnsignedLong(input.readInt());
            case 0xcf: return input.readLong();
            case 0xd0: return input.readByte();
            case 0xd1: return input.readShort();
            case 0xd2: return input.readInt();
            case 0xd3: return input.readLong();
            case 0xd9: return text(input, input.readUnsignedByte());
            case 0xda: return text(input, input.readUnsignedShort());
            case 0xdb: return text(input, input.readInt());
            case 0xdc: return list(input, input.readUnsignedShort());
            case 0xdd: return list(input, input.readInt());
            case 0xde: return map(input, input.readUnsignedShort());
            case 0xdf: return map(input, input.readInt());
            default: throw new IOException("Unexpected MessagePack token " + code);
        }
    }

    private static byte[] bytes(DataInputStream input, int length) throws IOException {
        if (length < 0 || length > SpeechHttpClient.MAX_REFERENCE_REQUEST_BYTES) throw new IOException("Invalid MessagePack length");
        byte[] bytes = new byte[length]; input.readFully(bytes); return bytes;
    }
    private static String text(DataInputStream input, int length) throws IOException { return new String(bytes(input, length), StandardCharsets.UTF_8); }
    private static List<Object> list(DataInputStream input, int length) throws IOException {
        List<Object> result = new ArrayList<>(); for (int index = 0; index < length; index++) result.add(read(input)); return result;
    }
    private static Map<String, Object> map(DataInputStream input, int length) throws IOException {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < length; index++) result.put((String) read(input), read(input)); return result;
    }
}
