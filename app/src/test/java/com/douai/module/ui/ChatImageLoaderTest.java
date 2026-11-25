package com.douai.module.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.media.ExifInterface;

import com.douai.module.feature.ChatImage;
import com.douai.module.feature.ChatImageLoader;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class ChatImageLoaderTest {
    @Test public void transparentImageBecomesBoundedJpegWithWhiteBackground() throws Exception {
        ChatImage image = ChatImageLoader.decode(image(2400, 1200, Bitmap.CompressFormat.PNG));
        assertEquals("image/jpeg", image.mimeType);
        Bitmap decoded = decode(image);
        assertTrue(decoded.getWidth() <= 2048);
        assertTrue(decoded.getHeight() <= 2048);
        assertEquals(2, decoded.getWidth() / decoded.getHeight());
        int pixel = decoded.getPixel(0, 0);
        assertTrue(Color.red(pixel) > 250 && Color.green(pixel) > 250 && Color.blue(pixel) > 250);
        assertNotNull(ChatImageLoader.thumbnail(image));
        decoded.recycle();
    }

    @Test public void localPreviewUsesSavedPixelsAtALargerButBoundedSize() {
        ChatImage saved = new ChatImage("image/png", image(2500, 1200, Bitmap.CompressFormat.PNG));
        String originalBytes = saved.base64Data;
        Bitmap thumbnail = ChatImageLoader.thumbnail(saved);
        Bitmap preview = ChatImageLoader.localPreview(saved);
        assertNotNull(thumbnail);
        assertNotNull(preview);
        try {
            assertTrue(preview.getWidth() > thumbnail.getWidth());
            assertTrue(preview.getHeight() > thumbnail.getHeight());
            assertTrue(preview.getWidth() <= 2048);
            assertTrue(preview.getHeight() <= 2048);
            assertEquals(originalBytes, saved.base64Data);
            assertNull(ChatImageLoader.localPreview(null));
        } finally {
            thumbnail.recycle();
            preview.recycle();
        }
    }

    @Test public void exifRotationIsAppliedBeforeSending() throws Exception {
        File file = File.createTempFile("douai-chat-image", ".jpg");
        try {
            Files.write(file.toPath(), image(120, 60, Bitmap.CompressFormat.JPEG));
            ExifInterface exif = new ExifInterface(file.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
            Bitmap decoded = decode(ChatImageLoader.decode(Files.readAllBytes(file.toPath())));
            assertEquals(60, decoded.getWidth());
            assertEquals(120, decoded.getHeight());
            decoded.recycle();
        } finally { Files.deleteIfExists(file.toPath()); }
    }

    @Test public void rejectsInvalidAndOversizedDocuments() throws Exception {
        try { ChatImageLoader.decode(new byte[]{1, 2, 3}); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("格式")); }
        try { ChatImageLoader.decode(new byte[0]); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("为空")); }
        try { ChatImageLoader.read(new ByteArrayInputStream(new byte[16 * 1024 * 1024 + 1])); fail(); }
        catch (IllegalArgumentException expected) { assertTrue(expected.getMessage().contains("16 MB")); }
    }

    @Test public void everyExifOrientationPreservesImageBoundsAndCornerColors() throws Exception {
        Bitmap source = Bitmap.createBitmap(120, 80, Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas = new android.graphics.Canvas(source);
        android.graphics.Paint paint = new android.graphics.Paint();
        int[] colors = {Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW};
        for (int i = 0; i < 4; i++) {
            paint.setColor(colors[i]);
            canvas.drawRect((i % 2) * 60, (i / 2) * 40, (i % 2 + 1) * 60, (i / 2 + 1) * 40, paint);
        }
        File file = File.createTempFile("douai-all-exif", ".jpg");
        try {
            for (int orientation = 1; orientation <= 8; orientation++) {
                ByteArrayOutputStream encoded = new ByteArrayOutputStream();
                source.compress(Bitmap.CompressFormat.JPEG, 100, encoded);
                Files.write(file.toPath(), encoded.toByteArray());
                ExifInterface exif = new ExifInterface(file.getAbsolutePath());
                exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(orientation)); exif.saveAttributes();
                byte[] bytes = Files.readAllBytes(file.toPath());
                Bitmap original = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                android.graphics.Matrix matrix = new android.graphics.Matrix();
                switch (orientation) {
                    case 2: matrix.setScale(-1, 1); break;
                    case 3: matrix.setRotate(180); break;
                    case 4: matrix.setScale(1, -1); break;
                    case 5: matrix.setRotate(90); matrix.postScale(-1, 1); break;
                    case 6: matrix.setRotate(90); break;
                    case 7: matrix.setRotate(270); matrix.postScale(-1, 1); break;
                    case 8: matrix.setRotate(270); break;
                    default: break;
                }
                Bitmap expected = Bitmap.createBitmap(original, 0, 0, original.getWidth(), original.getHeight(), matrix, true);
                Bitmap actual = decode(ChatImageLoader.decode(bytes));
                try {
                    assertEquals("width orientation=" + orientation, expected.getWidth(), actual.getWidth());
                    assertEquals("height orientation=" + orientation, expected.getHeight(), actual.getHeight());
                    for (int x : new int[]{10, actual.getWidth() - 11}) for (int y : new int[]{10, actual.getHeight() - 11}) {
                        int wanted = expected.getPixel(x, y), got = actual.getPixel(x, y);
                        assertTrue(Math.abs(Color.red(wanted) - Color.red(got)) < 15);
                        assertTrue(Math.abs(Color.green(wanted) - Color.green(got)) < 15);
                        assertTrue(Math.abs(Color.blue(wanted) - Color.blue(got)) < 15);
                    }
                } finally {
                    actual.recycle(); if (expected != original) expected.recycle(); original.recycle();
                }
            }
        } finally { source.recycle(); Files.deleteIfExists(file.toPath()); }
    }

    private static byte[] image(int width, int height, Bitmap.CompressFormat format) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.TRANSPARENT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        bitmap.compress(format, 100, bytes);
        bitmap.recycle();
        return bytes.toByteArray();
    }

    private static Bitmap decode(ChatImage image) {
        byte[] bytes = Base64.getDecoder().decode(image.base64Data);
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
    }
}
