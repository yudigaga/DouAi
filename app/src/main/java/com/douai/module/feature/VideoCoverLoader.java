package com.douai.module.feature;

import android.graphics.Bitmap;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

/** Bounded alternative cover sources decoded directly to a display thumbnail. */
public final class VideoCoverLoader {
    private static final int MAX_SOURCES = 8;
    private static final int MAX_SOURCE_BYTES = 16 * 1024 * 1024;

    private VideoCoverLoader() { }

    public static class Request {
        private final MediaReadCancellation cancellation;
        private volatile String stage = "source";
        private volatile int httpStatus;
        private volatile int attemptedSources;

        public Request() { this(new MediaReadCancellation(15_000)); }

        Request(MediaReadCancellation cancellation) { this.cancellation = cancellation; }

        public synchronized Bitmap load(List<String> sources) throws Exception {
            cancellation.start();
            stage = "source";
            httpStatus = 0;
            attemptedSources = 0;
            Set<String> seen = new HashSet<>();
            if (sources != null) {
                for (int index = 0; index < Math.min(64, sources.size()) && attemptedSources < MAX_SOURCES; index++) {
                    cancellation.check();
                    String source = sources.get(index);
                    if (source == null || source.isEmpty() || !seen.add(source)) continue;
                    attemptedSources++;
                    httpStatus = 0;
                    stage = "source";
                    try {
                        Bitmap cover = loadSource(source);
                        try {
                            cancellation.check();
                            stage = "complete";
                            return cover;
                        } catch (Exception failure) {
                            cover.recycle();
                            throw failure;
                        }
                    } catch (CancellationException cancelled) { throw cancelled; }
                    catch (TimeoutException timeout) { throw timeout; }
                    catch (Exception unavailable) {
                        if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
                        cancellation.check();
                    }
                }
            }
            cancellation.check();
            // Failure messages and diagnostics must not retain signed media URLs or provider response text.
            throw new IllegalStateException("Video cover unavailable");
        }

        private Bitmap loadSource(String source) throws Exception {
            String current = source;
            for (int redirect = 0; redirect < 4; redirect++) {
                cancellation.check();
                stage = "source";
                if (current.length() > 2048 || !AutomationImages.isMediaUrl(current)) {
                    throw new IllegalArgumentException("Unsupported video cover source");
                }
                stage = "http";
                HttpURLConnection connection = cancellation.open(URI.create(current));
                try {
                    connection.setConnectTimeout(cancellation.timeoutMillis(5_000));
                    connection.setReadTimeout(cancellation.timeoutMillis(5_000));
                    httpStatus = connection.getResponseCode();
                    cancellation.check();
                    if (httpStatus >= 300 && httpStatus < 400) {
                        String location = connection.getHeaderField("Location");
                        if (location == null) throw new IllegalStateException("Invalid video cover redirect");
                        current = URI.create(current).resolve(location).toString();
                        continue;
                    }
                    if (httpStatus != 200) throw new IllegalStateException("Video cover HTTP failure");
                    stage = "bytes";
                    if (connection.getContentLengthLong() > MAX_SOURCE_BYTES) {
                        throw new IllegalArgumentException("Video cover exceeds 16 MiB");
                    }
                    byte[] sourceBytes;
                    try (InputStream input = connection.getInputStream()) {
                        sourceBytes = ChatImageLoader.read(input, cancellation);
                    }
                    cancellation.check();
                    stage = "decode";
                    return AvatarImageLoader.decode(sourceBytes, cancellation);
                } finally { cancellation.close(connection); }
            }
            throw new IllegalStateException("Video cover redirect limit exceeded");
        }

        public void cancel() { cancellation.cancel(); }
        public String stage() { return stage; }
        public int httpStatus() { return httpStatus; }
        public int attemptedSources() { return attemptedSources; }
    }
}
