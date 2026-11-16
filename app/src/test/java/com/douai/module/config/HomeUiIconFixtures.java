package com.douai.module.config;

import android.graphics.Bitmap;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Small real encoded images; names and MIME strings never determine their type. */
public final class HomeUiIconFixtures {
    private HomeUiIconFixtures() { }
    public static byte[] webp(int width, int height, int color) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(color);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!bitmap.compress(Bitmap.CompressFormat.WEBP_LOSSLESS, 100, output)) {
            throw new AssertionError("WebP encoding failed");
        }
        bitmap.recycle();
        return output.toByteArray();
    }
    public static byte[] chunk(byte[] source, String type, int payloadLength) {
        int start = source.length;
        byte[] result = Arrays.copyOf(source, start + 8 + payloadLength + (payloadLength & 1));
        System.arraycopy(type.getBytes(StandardCharsets.US_ASCII), 0, result, start, 4);
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(4, result.length - 8).putInt(start + 4, payloadLength);
        return result;
    }
}
