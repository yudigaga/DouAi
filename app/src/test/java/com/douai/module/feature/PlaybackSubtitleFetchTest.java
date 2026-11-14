package com.douai.module.feature;

import org.junit.Test;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

public class PlaybackSubtitleFetchTest {
    @Test public void redirectsAreRevalidatedBeforeOpeningTheNextHost() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaReadCancellation cancel = new MediaReadCancellation(60_000, System::nanoTime, uri -> {
            opened.incrementAndGet(); return connection(uri, 302, "https://example.com/private", "");
        });
        try { PlaybackReadingDialog.fetch("https://www.douyinstatic.com/sub.vtt", cancel); fail(); }
        catch (IllegalArgumentException expected) { assertEquals(1, opened.get()); }
    }
    @Test public void bodyWithoutDeclaredLengthStillHasAnEnforcedLimit() throws Exception {
        MediaReadCancellation cancel = new MediaReadCancellation(60_000, System::nanoTime,
                uri -> connection(uri,200,null,"a".repeat(PlaybackReadingText.MAX_CHARS+1)));
        try { PlaybackReadingDialog.fetch("https://www.douyinstatic.com/sub.vtt", cancel); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void cancelledReadDoesNotOpenANetworkConnection() throws Exception {
        AtomicInteger opened = new AtomicInteger();
        MediaReadCancellation cancel = new MediaReadCancellation(60_000,System::nanoTime,uri -> {
            opened.incrementAndGet(); return connection(uri,200,null,"WEBVTT\n");
        });
        cancel.cancel();
        try { PlaybackReadingDialog.fetch("https://www.douyinstatic.com/sub.vtt",cancel); fail(); }
        catch (java.util.concurrent.CancellationException expected) { assertEquals(0,opened.get()); }
    }
    private static HttpURLConnection connection(URI uri,int code,String location,String body) throws Exception {
        return new HttpURLConnection(uri.toURL()) {
            public void disconnect() { }
            public boolean usingProxy() { return false; }
            public void connect() { }
            public int getResponseCode() { return code; }
            public String getHeaderField(String key) { return "Location".equals(key) ? location : null; }
            public InputStream getInputStream() { return new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)); }
            public long getContentLengthLong() { return -1; }
        };
    }
}
