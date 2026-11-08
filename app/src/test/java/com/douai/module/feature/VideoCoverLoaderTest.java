package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.media.ExifInterface;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class VideoCoverLoaderTest {
    private static final String FIRST = "https://p3.douyinpic.com/first.png";
    private static final String NEXT = "https://p9.byteimg.com/next.png";

    @Test public void httpFailureUsesTheNextSourceAndDecodesOnlyAThumbnail() throws Exception {
        FakeConnection rejected = connection(403, new byte[0]);
        FakeConnection accepted = connection(200, image(1025, 513, false));
        AtomicInteger opened = new AtomicInteger();
        VideoCoverLoader.Request request = request(uri -> opened.getAndIncrement() == 0 ? rejected : accepted);
        Bitmap cover = request.load(List.of(FIRST, NEXT));
        try {
            assertEquals(2, opened.get());
            assertEquals(2, request.attemptedSources());
            assertEquals(200, request.httpStatus());
            assertEquals("complete", request.stage());
            assertTrue(cover.getWidth() <= 128);
            assertTrue(cover.getHeight() <= 128);
            assertEquals(Color.WHITE, cover.getPixel(0, 0));
            assertTrue(Math.abs(2.0 - (double) cover.getWidth() / cover.getHeight()) < .05);
        } finally { cover.recycle(); }
        assertTrue(rejected.disconnected);
        assertTrue(accepted.disconnected);
    }

    @Test public void failedDecodingUsesTheNextSourceWithoutExposingItsFailureText() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        VideoCoverLoader.Request request = request(uri -> connection(200,
                opened.getAndIncrement() == 0 ? new byte[]{1, 2, 3} : image(512, 512, false)));
        Bitmap cover = request.load(List.of(FIRST, NEXT));
        try { assertEquals(128, cover.getWidth()); assertEquals(2, request.attemptedSources()); }
        finally { cover.recycle(); }

        VideoCoverLoader.Request failed = request(uri -> {
            throw new IllegalStateException("private signed URL or provider response");
        });
        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> failed.load(List.of(FIRST)));
        assertEquals("Video cover unavailable", failure.getMessage());
        assertNull(failure.getCause());
        assertEquals("http", failed.stage());
    }

    @Test public void sourcesLargerThanTwoMiBStillDecodeButSixteenMiBRemainsTheBound() throws Exception {
        byte[] large = image(1100, 700, true);
        assertTrue(large.length > 2 * 1024 * 1024);
        assertTrue(large.length <= 16 * 1024 * 1024);
        Bitmap cover = request(uri -> connection(200, large)).load(List.of(FIRST));
        try { assertTrue(cover.getWidth() <= 128); assertTrue(cover.getHeight() <= 128); }
        finally { cover.recycle(); }

        FakeConnection oversized = connection(200, new byte[0]);
        oversized.length = 16 * 1024 * 1024L + 1;
        VideoCoverLoader.Request announced = request(uri -> oversized);
        assertThrows(IllegalStateException.class, () -> announced.load(List.of(FIRST)));
        assertFalse(oversized.inputOpened);
        assertTrue(oversized.disconnected);
        assertEquals("bytes", announced.stage());

        FakeConnection unknown = new FakeConnection(200, new InputStream() {
            int remaining = 16 * 1024 * 1024 + 1;
            @Override public int read() { return remaining-- > 0 ? 0 : -1; }
            @Override public int read(byte[] bytes, int offset, int length) {
                if (remaining <= 0) return -1;
                int read = Math.min(remaining, length); remaining -= read; return read;
            }
        });
        assertThrows(IllegalStateException.class, () -> request(uri -> unknown).load(List.of(FIRST)));
        assertTrue(unknown.disconnected);
    }

    @Test public void validatesEveryInitialSourceAndRedirectBeforeOpeningIt() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        FakeConnection redirect = connection(302, new byte[0]);
        redirect.location = "https://unrelated.example/private.png";
        FakeConnection accepted = connection(200, image(64, 64, false));
        VideoCoverLoader.Request request = request(uri -> {
            assertTrue(AutomationImages.isMediaUrl(uri.toString()));
            return opened.getAndIncrement() == 0 ? redirect : accepted;
        });
        Bitmap cover = request.load(List.of("http://p3.douyinpic.com/private.png", FIRST, NEXT));
        cover.recycle();
        assertEquals(2, opened.get());
        assertTrue(redirect.disconnected);
        assertTrue(accepted.disconnected);
        assertFalse(accepted.getInstanceFollowRedirects());
    }

    @Test public void cancelsActiveReadAndNeverContinuesWithAnotherSource() throws Exception {
        AtomicReference<VideoCoverLoader.Request> active = new AtomicReference<>();
        AtomicInteger opened = new AtomicInteger();
        FakeConnection reading = new FakeConnection(200, new InputStream() {
            @Override public int read() { active.get().cancel(); return -1; }
        });
        VideoCoverLoader.Request request = request(uri -> { opened.incrementAndGet(); return reading; });
        active.set(request);
        assertThrows(CancellationException.class, () -> request.load(List.of(FIRST, NEXT)));
        assertEquals(1, opened.get());
        assertTrue(reading.disconnected);
        assertThrows(CancellationException.class, () -> request.load(List.of(FIRST, NEXT)));
    }

    @Test public void everySourceSharesOneDeadlineAndInterruptionCannotBecomeFallback() throws Exception {
        AtomicLong clock = new AtomicLong(1);
        AtomicInteger opened = new AtomicInteger();
        FakeConnection expired = new FakeConnection(500, new ByteArrayInputStream(new byte[0])) {
            @Override public int getResponseCode() { clock.addAndGet(15_000_000_000L); return status; }
        };
        VideoCoverLoader.Request request = new VideoCoverLoader.Request(new MediaReadCancellation(15_000,
                clock::get, uri -> { opened.incrementAndGet(); return expired; }));
        assertThrows(TimeoutException.class, () -> request.load(List.of(FIRST, NEXT)));
        assertEquals(1, opened.get());
        assertTrue(expired.disconnected);
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> request(uri -> connection(200, image(1, 1, false)))
                    .load(List.of(FIRST, NEXT)));
        } finally { Thread.interrupted(); }
    }

    @Test public void preservesExifAtThumbnailSizeAndLimitsDistinctCandidates() throws Exception {
        File jpeg = File.createTempFile("douai-cover", ".jpg");
        try {
            Bitmap source = Bitmap.createBitmap(120, 60, Bitmap.Config.ARGB_8888);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try { assertTrue(source.compress(Bitmap.CompressFormat.JPEG, 100, bytes)); }
            finally { source.recycle(); }
            Files.write(jpeg.toPath(), bytes.toByteArray());
            ExifInterface exif = new ExifInterface(jpeg.getAbsolutePath());
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, Integer.toString(ExifInterface.ORIENTATION_ROTATE_90));
            exif.saveAttributes();
            Bitmap cover = request(uri -> connection(200, Files.readAllBytes(jpeg.toPath()))).load(List.of(FIRST));
            try { assertEquals(60, cover.getWidth()); assertEquals(120, cover.getHeight()); }
            finally { cover.recycle(); }
        } finally { Files.deleteIfExists(jpeg.toPath()); }

        AtomicInteger opened = new AtomicInteger();
        VideoCoverLoader.Request bounded = request(uri -> { opened.incrementAndGet(); return connection(403, new byte[0]); });
        List<String> sources = new java.util.ArrayList<>();
        sources.add(FIRST); sources.add(FIRST);
        for (int index = 0; index < 12; index++) sources.add("https://p3.douyinpic.com/" + index + ".png");
        assertThrows(IllegalStateException.class, () -> bounded.load(sources));
        assertEquals(8, opened.get());
        assertEquals(8, bounded.attemptedSources());
    }

    private static VideoCoverLoader.Request request(MediaReadCancellation.ConnectionFactory connections) {
        return new VideoCoverLoader.Request(new MediaReadCancellation(15_000, System::nanoTime, connections));
    }
    private static FakeConnection connection(int status, byte[] data) throws Exception {
        FakeConnection result = new FakeConnection(status, new ByteArrayInputStream(data));
        result.length = data.length;
        return result;
    }
    private static byte[] image(int width, int height, boolean noise) {
        Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        if (noise) {
            int[] pixels = new int[width * height];
            Random random = new Random(1);
            for (int index = 0; index < pixels.length; index++) pixels[index] = 0xFF000000 | random.nextInt(0x1000000);
            image.setPixels(pixels, 0, width, 0, 0, width, height);
        } else image.eraseColor(Color.TRANSPARENT);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try { assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, bytes)); }
        finally { image.recycle(); }
        return bytes.toByteArray();
    }
    private static class FakeConnection extends HttpURLConnection {
        final int status;
        final InputStream input;
        long length = -1;
        String location;
        boolean disconnected, inputOpened;
        FakeConnection(int status, InputStream input) throws Exception { super(new URL(FIRST)); this.status = status; this.input = input; }
        @Override public int getResponseCode() { return status; }
        @Override public long getContentLengthLong() { return length; }
        @Override public String getHeaderField(String name) { return "Location".equals(name) ? location : null; }
        @Override public InputStream getInputStream() { inputOpened = true; return input; }
        @Override public void disconnect() { disconnected = true; }
        @Override public boolean usingProxy() { return false; }
        @Override public void connect() { }
    }
}
