package com.douai.module.feature;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class PlaybackReadingTextTest {
    @Test public void webvttKeepsRepeatedSentencesAndExactMilliseconds() {
        List<PlaybackReadingText.Line> rows = PlaybackReadingText.webvtt("\uFEFFWEBVTT\n\nfirst\n00:01.250 --> 00:02.500 align:start\n你好\n世界\n\n00:00:03.000 --> 00:00:04.000\n你好\n");
        assertEquals(2, rows.size()); assertEquals(1250, rows.get(0).millis);
        assertEquals("你好\n世界", rows.get(0).text); assertEquals(3000, rows.get(1).millis);
    }
    @Test public void metadataAndMalformedTimesNeverBecomeSeekTargets() {
        List<PlaybackReadingText.Line> rows = PlaybackReadingText.webvtt("WEBVTT\n\nNOTE private\nignore\n\n00:75.000 --> 00:76.000\nbad\n\n00:03.000 --> 00:02.000\nreverse\n\n00:01.000 --> 00:02.000\n<b>good</b> &amp; clear\n");
        assertEquals(3, rows.size()); assertEquals(-1, rows.get(0).millis);
        assertEquals(-1, rows.get(1).millis); assertEquals("good & clear", rows.get(2).text);
    }
    @Test public void unknownBodyIsRejectedRatherThanGuessedAsMilliseconds() {
        try { PlaybackReadingText.webvtt("{\"start\":3,\"text\":\"hello\"}"); fail(); }
        catch (IllegalArgumentException expected) { }
    }
    @Test public void excessiveBodyIsRejected() {
        try { PlaybackReadingText.webvtt("WEBVTT\n" + "a".repeat(PlaybackReadingText.MAX_CHARS)); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
