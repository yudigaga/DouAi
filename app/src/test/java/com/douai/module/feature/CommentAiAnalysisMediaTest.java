package com.douai.module.feature;

import android.graphics.Bitmap;
import android.graphics.Color;

import com.google.gson.Gson;
import com.google.gson.annotations.SerializedName;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class CommentAiAnalysisMediaTest {
    @Test public void gallerySnapshotKeepsFirstFourCurrentImagesAfterHostChanges() throws Exception {
        Work work = work("""
                {"images":[
                  {"url_list":["https://p.byteimg.com/1.png"]},
                  {"url_list":["https://p.byteimg.com/2.png"]},
                  {"url_list":["https://p.byteimg.com/3.png"]},
                  {"url_list":["https://p.byteimg.com/4.png"]},
                  {"url_list":["https://unrelated.test/unselected.png"]}],
                 "video":{"play_addr":{"url_list":["https://v.douyinvod.com/unused.mp4"]}}}
                """);
        CommentAiAnalysisMedia.Source source = CommentAiAnalysisMedia.capture(work);
        work.images.clear();
        work.video = null;
        List<String> requested = new ArrayList<>();
        byte[] png = png();
        MediaReadCancellation read = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            requested.add(uri.toString());
            return connection(200, png);
        });

        CommentAiAnalysisMedia.Result result = CommentAiAnalysisMedia.load(
                RuntimeEnvironment.getApplication(), source, read);

        assertEquals(List.of("https://p.byteimg.com/1.png", "https://p.byteimg.com/2.png",
                "https://p.byteimg.com/3.png", "https://p.byteimg.com/4.png"), requested);
        assertEquals("gallery", result.kind);
        assertEquals(4, result.images.size());
        assertTrue(result.failure.isEmpty());
        assertTrue(result.document.contains("非全量"));
        assertTrue(result.document.contains("其他作品"));
        assertThrows(UnsupportedOperationException.class, () -> result.images.clear());
    }

    @Test public void failedVideoFallsBackToOneCoverWithoutClaimingVideoFrames() throws Exception {
        CommentAiAnalysisMedia.Source source = CommentAiAnalysisMedia.capture(work("""
                {"video":{"play_addr":{"url_list":["https://v.douyinvod.com/work.mp4"]},
                 "cover":{"url_list":["https://p.byteimg.com/cover.png"]}}}
                """));
        List<String> requested = new ArrayList<>();
        byte[] png = png();
        MediaReadCancellation read = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            requested.add(uri.toString());
            return connection(requested.size() == 1 ? 500 : 200, png);
        });

        CommentAiAnalysisMedia.Result result = CommentAiAnalysisMedia.load(
                RuntimeEnvironment.getApplication(), source, read);

        assertEquals(List.of("https://v.douyinvod.com/work.mp4", "https://p.byteimg.com/cover.png"), requested);
        assertEquals("cover", result.kind);
        assertEquals(1, result.images.size());
        assertEquals("IllegalStateException", result.failure);
        assertTrue(result.document.contains("仅提供当前作品的 1 张封面"));
        assertTrue(result.document.contains("动作"));
        assertFalse(result.document.contains("https://"));
    }

    @Test public void expiredVideoBudgetContinuesAsTextWithoutFetchingCover() throws Exception {
        AtomicLong now = new AtomicLong(1);
        AtomicInteger requests = new AtomicInteger();
        MediaReadCancellation read = new MediaReadCancellation(60_000, now::get, uri -> {
            requests.incrementAndGet();
            return new HttpURLConnection(new URL("https://v.douyinvod.com/work.mp4")) {
                @Override public int getResponseCode() throws SocketTimeoutException {
                    now.addAndGet(60_000_000_000L);
                    throw new SocketTimeoutException("private transport detail");
                }
                @Override public void disconnect() { }
                @Override public boolean usingProxy() { return false; }
                @Override public void connect() { }
            };
        });
        CommentAiAnalysisMedia.Source source = CommentAiAnalysisMedia.capture(work("""
                {"video":{"play_addr":{"url_list":["https://v.douyinvod.com/work.mp4"]},
                 "cover":{"url_list":["https://p.byteimg.com/cover.png"]}}}
                """));

        CommentAiAnalysisMedia.Result result = CommentAiAnalysisMedia.load(
                RuntimeEnvironment.getApplication(), source, read);

        assertEquals(1, requests.get());
        assertEquals("none", result.kind);
        assertTrue(result.images.isEmpty());
        assertEquals("TimeoutException", result.failure);
        assertTrue(result.document.contains("未提供"));
        assertFalse(result.document.contains("private transport detail"));
    }

    @Test public void cancellationOrThreadInterruptionNeverReturnsTextFallback() throws Exception {
        MediaReadCancellation read = new MediaReadCancellation();
        read.cancel();
        CommentAiAnalysisMedia.Source source = CommentAiAnalysisMedia.capture(null);
        assertThrows(CancellationException.class, () -> CommentAiAnalysisMedia.load(
                RuntimeEnvironment.getApplication(), source, read));
        try {
            Thread.currentThread().interrupt();
            assertThrows(CancellationException.class, () -> CommentAiAnalysisMedia.load(
                    RuntimeEnvironment.getApplication(), source, new MediaReadCancellation()));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
    }

    @Test public void invalidGallerySourceIsCapturedWithoutNetworkOrHostException() throws Exception {
        CommentAiAnalysisMedia.Source source = CommentAiAnalysisMedia.capture(work("""
                {"images":[{"url_list":["https://unrelated.test/private.png"]}],
                 "video":{"cover":{"url_list":["https://p.byteimg.com/unrelated-cover.png"]}}}
                """));
        MediaReadCancellation read = new MediaReadCancellation(60_000, System::nanoTime,
                uri -> { throw new AssertionError("An invalid gallery must not fall through to cover"); });

        CommentAiAnalysisMedia.Result result = CommentAiAnalysisMedia.load(
                RuntimeEnvironment.getApplication(), source, read);

        assertTrue(result.images.isEmpty());
        assertEquals("none", result.kind);
        assertEquals("IllegalArgumentException", result.failure);
        assertFalse(result.document.contains("private.png"));
    }

    private static Work work(String json) { return new Gson().fromJson(json, Work.class); }

    private static byte[] png() {
        Bitmap bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(Color.RED);
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try {
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes);
            return bytes.toByteArray();
        } finally { bitmap.recycle(); }
    }

    private static HttpURLConnection connection(int status, byte[] bytes) throws Exception {
        return new HttpURLConnection(new URL("https://p.byteimg.com/media")) {
            @Override public int getResponseCode() { return status; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(bytes); }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
    }

    static final class Work {
        @SerializedName("images") List<GalleryImage> images;
        @SerializedName("video") Video video;
    }
    static final class GalleryImage {
        @SerializedName("url_list") List<String> urls;
    }
    static final class Video {
        @SerializedName("play_addr") Address playback;
        @SerializedName("cover") Address cover;
    }
    static final class Address {
        @SerializedName("url_list") List<String> urls;
    }
}
