package com.douai.module.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Reads only bounded work metadata already present in the selected host model. */
final class AnalyticsAwemeReader {
    static final long LONG_VIDEO_MILLIS = 300_000L;
    private static final int MAX_TOPICS = 20;

    private AnalyticsAwemeReader() { }

    static Metadata read(Object work) {
        if (work == null) return null;
        String id = text(value(work, "getAid", "aid", "aweme_id"), 160);
        if (id.isEmpty()) return null;
        long kind = number(value(work, "getAwemeType", "awemeType", "aweme_type"));
        // These are recommendation/merchant cards rather than browsed works.
        if (kind == 145 || kind == 140) return null;
        Object author = value(work, "getAuthor", "author");
        Object ad = value(work, "getIsAdAweme", "isAdAweme", "is_ads");
        Object fakeAd = value(author, "isAdFake", "isAdFake");
        Boolean advertisement = null;
        if (yes(ad) || yes(fakeAd)) advertisement = Boolean.TRUE;
        else if (ad instanceof Boolean || ad instanceof Number || fakeAd instanceof Boolean || fakeAd instanceof Number) advertisement = Boolean.FALSE;
        Object video = value(work, "getVideo", "video");
        long duration = number(value(work, "getDuration", "duration"));
        if (duration <= 0) duration = number(value(video, "getDuration", "videoLength", "duration"));
        if (duration <= 0) duration = -1;
        Object images = value(work, "getImages", "images");
        boolean image = kind == 68 || kind == 163 || yes(value(work, "isImage", "isImage"))
                || images instanceof List<?> && !((List<?>) images).isEmpty();
        boolean livePhoto = number(value(work, "getIsLivePhoto", "isLivePhoto", "is_live_photo")) == 1;
        if (images instanceof Iterable<?>) {
            int inspected = 0;
            for (Object page : (Iterable<?>) images) {
                // OfficialMediaModel only inspects metadata and URL presence; it never opens media.
                if (OfficialMediaModel.isLivePhoto(work, page)
                        || number(value(page, "getClipType", "clipType", "clip_type")) == 5) livePhoto = true;
                if (++inspected >= 100) break;
            }
        }
        boolean live = yes(value(work, "isLive", "isLive")) || kind == 101;
        String type = live ? "live" : livePhoto ? "live_photo" : image ? "image"
                : duration > 0 ? (duration >= LONG_VIDEO_MILLIS ? "long_video" : "short_video") : "unknown";
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        addTopics(topics, value(work, "getTextExtra", "textExtra", "text_extra"),
                "getHashTagName", "hashTagName", "hashtag_name");
        addTopics(topics, value(work, "getChallengeList", "challengeList", "cha_list", "challenges"),
                "getChallengeName", "challengeName", "cha_name");
        return new Metadata(id, type, text(value(author, "getUid", "uid"), 160),
                text(value(author, "getNickname", "nickname"), 160), new ArrayList<>(topics),
                advertisement, duration, images instanceof List<?> ? ((List<?>) images).size() : null);
    }

    private static void addTopics(Set<String> output, Object entries, String getter, String... fields) {
        if (!(entries instanceof Iterable<?>) || output.size() >= MAX_TOPICS) return;
        int inspected = 0;
        for (Object entry : (Iterable<?>) entries) {
            String topic = text(value(entry, getter, fields), 100);
            if (topic.startsWith("#")) topic = topic.substring(1).trim();
            if (!topic.isEmpty()) output.add(topic);
            if (++inspected >= 100 || output.size() >= MAX_TOPICS) break;
        }
    }

    static Object value(Object target, String getter, String... fields) {
        if (target == null) return null;
        Object result = HostTargets.invoke(target, getter);
        if (result != null) return result;
        for (String field : fields) {
            result = target instanceof Map<?, ?> ? ((Map<?, ?>) target).get(field)
                    : DownloadActionHook.jsonValue(target, field);
            if (result != null) return result;
        }
        return null;
    }

    private static boolean yes(Object value) {
        return Boolean.TRUE.equals(value) || value instanceof Number && ((Number) value).intValue() == 1;
    }

    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : -1;
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String) && !(value instanceof Number)) return "";
        String text = value.toString().trim();
        return text.substring(0, Math.min(maximum, text.length()));
    }

    static final class Metadata {
        final String id;
        final String type;
        final String authorId;
        final String authorName;
        final List<String> topics;
        final Boolean advertisement;
        final Integer imageCount;
        final long duration;

        Metadata(String id, String type, String authorId, String authorName, List<String> topics,
                 Boolean advertisement, long duration, Integer imageCount) {
            this.imageCount = imageCount;
            this.id = id;
            this.type = type;
            this.authorId = authorId;
            this.authorName = authorName;
            this.topics = Collections.unmodifiableList(topics);
            this.advertisement = advertisement;
            this.duration = duration;
        }
    }
}
