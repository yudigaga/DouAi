package com.douai.module.feature;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class AnalyticsAwemeReaderTest {
    @Test public void classifiesBoundaryAndKeepsUnknownSeparate() {
        assertEquals("short_video", read(Map.of("duration", 299_999)).type);
        assertEquals("long_video", read(Map.of("duration", 300_000)).type);
        assertEquals("long_video", read(Map.of("video", Map.of("videoLength", 600_000))).type);
        AnalyticsAwemeReader.Metadata unknown = read(Map.of("duration", 0));
        assertEquals("unknown", unknown.type);
        assertEquals(-1, unknown.duration);
    }

    @Test public void classifiesLivePhotosSeparatelyFromImagesAndBroadcasts() {
        assertEquals("image", read(Map.of("aweme_type", 68)).type);
        assertEquals("image", read(Map.of("images", List.of(Map.of("clip_type", 2)))).type);
        assertEquals("live_photo", read(Map.of("images", List.of(Map.of("clip_type", 5)))).type);
        assertEquals("live_photo", read(Map.of("is_live_photo", 1)).type);
        assertEquals("live", read(Map.of("isLive", true, "is_live_photo", 1)).type);
    }

    @Test public void preservesOnlyBoundedAuthorAndUniqueTopics() {
        AnalyticsAwemeReader.Metadata data = read(Map.of("author", Map.of("uid", "123", "nickname", "作者"),
                "text_extra", List.of(Map.of("hashtag_name", "#旅行"), Map.of("hashtag_name", "旅行")),
                "cha_list", List.of(Map.of("cha_name", "摄影")), "desc", "正文永远不应储存"));
        assertEquals("123", data.authorId);
        assertEquals("作者", data.authorName);
        assertEquals(List.of("旅行", "摄影"), data.topics);
        List<Map<String, String>> excessive = new ArrayList<>();
        for (int i = 0; i < 1000; i++) excessive.add(Map.of("hashtag_name", "topic" + i));
        assertEquals(20, read(Map.of("text_extra", excessive,
                "cha_list", List.of(Map.of("cha_name", "another")))).topics.size());
        assertEquals(160, read(Map.of("author", Map.of("nickname", "a".repeat(1000)))).authorName.length());
    }

    @Test public void metadataGettersWorkWithoutReadingDescription() {
        AnalyticsAwemeReader.Metadata data = AnalyticsAwemeReader.read(new GetterWork());
        assertNotNull(data);
        assertEquals("getter-id", data.id);
        assertEquals("short_video", data.type);
        assertEquals("作者", data.authorName);
        assertTrue(data.advertisement);
    }

    @Test public void ignoresRecommendationCardsAndMissingIdentity() {
        assertNull(read(Map.of("aweme_type", 145)));
        assertNull(read(Map.of("aweme_type", 140)));
        assertNull(AnalyticsAwemeReader.read(Map.of("duration", 1)));
        assertNull(AnalyticsAwemeReader.read(null));
        assertTrue(read(Map.of("author", Map.of("isAdFake", true))).advertisement);
        assertNull(read(Map.of()).advertisement);
        assertFalse(read(Map.of("author",Map.of("isAdFake",false))).advertisement);
        assertFalse(read(Map.of("is_ads", false)).advertisement);
        assertEquals(Integer.valueOf(3), read(Map.of("images",List.of(Map.of(),Map.of(),Map.of()))).imageCount);
    }

    private static AnalyticsAwemeReader.Metadata read(Map<String, Object> attributes) {
        Map<String, Object> work = new java.util.HashMap<>(attributes);
        work.put("aid", "work");
        return AnalyticsAwemeReader.read(work);
    }

    public static final class GetterWork {
        public String getAid() { return "getter-id"; }
        public long getDuration() { return 1000; }
        public boolean getIsAdAweme() { return true; }
        public Object getAuthor() { return Map.of("nickname", "作者"); }
        public String getDesc() { throw new AssertionError("Full descriptions must not be read"); }
    }
}
