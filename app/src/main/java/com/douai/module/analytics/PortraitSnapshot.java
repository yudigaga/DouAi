package com.douai.module.analytics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Local evidence for a time-bounded behavioural portrait, never a psychological score. */
public final class PortraitSnapshot {
    public final String startDate, endDate;
    public final List<Day> days;
    /** Monday = 0; each cell counts observed work selections, including repeated visits. */
    public final long[][] weekdayHours;
    public final List<AnalyticsSnapshot.Rank> topicTiles, authorPareto, featureSources;
    public final int browseDays, authorCount, topicCount;
    public final long topicOccurrences, repeatedWorks, capturedVisits, finishedVisits, visibleMillis, queueDropped;
    /** Normalized full-distribution Shannon diversity; 0 for fewer than two categories. */
    public final double authorDiversity, topicDiversity, topAuthorShare, topTopicShare;
    /** repeatedWorks / uniqueWorks; night is 00:00–05:59, weekend is Sat/Sun. */
    public final double crossDayRepeatShare, nightShare, weekendShare;
    public final Comparison comparison, weekComparison, monthComparison;
    public final List<Insight> insights;
    public final List<Joint> topicHours, typeActions;
    public final List<String> coverage;
    public final String evidenceLevel;
    public final PortraitEvidence evidence;

    public PortraitSnapshot(String startDate, String endDate, List<Day> days, long[][] weekdayHours,
            List<AnalyticsSnapshot.Rank> topicTiles, List<AnalyticsSnapshot.Rank> authorPareto,
            List<AnalyticsSnapshot.Rank> featureSources, int browseDays, int authorCount, int topicCount,
            long topicOccurrences, long repeatedWorks, long capturedVisits, long finishedVisits,
            long visibleMillis, long queueDropped, double authorDiversity, double topicDiversity,
            double topAuthorShare, double topTopicShare, double crossDayRepeatShare, double nightShare,
            double weekendShare, Comparison comparison, Comparison weekComparison, Comparison monthComparison,
            List<Insight> insights, List<Joint> topicHours, List<Joint> typeActions, List<String> coverage,
            String evidenceLevel) {
        this(startDate, endDate, days, weekdayHours, topicTiles, authorPareto, featureSources, browseDays, authorCount, topicCount, topicOccurrences, repeatedWorks, capturedVisits, finishedVisits, visibleMillis, queueDropped, authorDiversity, topicDiversity, topAuthorShare, topTopicShare, crossDayRepeatShare, nightShare, weekendShare, comparison, weekComparison, monthComparison, insights, topicHours, typeActions, coverage, evidenceLevel, PortraitEvidence.empty(startDate, endDate));
    }

    public PortraitSnapshot(String startDate, String endDate, List<Day> days, long[][] weekdayHours,
            List<AnalyticsSnapshot.Rank> topicTiles, List<AnalyticsSnapshot.Rank> authorPareto,
            List<AnalyticsSnapshot.Rank> featureSources, int browseDays, int authorCount, int topicCount,
            long topicOccurrences, long repeatedWorks, long capturedVisits, long finishedVisits,
            long visibleMillis, long queueDropped, double authorDiversity, double topicDiversity,
            double topAuthorShare, double topTopicShare, double crossDayRepeatShare, double nightShare,
            double weekendShare, Comparison comparison, Comparison weekComparison, Comparison monthComparison,
            List<Insight> insights, List<Joint> topicHours, List<Joint> typeActions, List<String> coverage,
            String evidenceLevel, PortraitEvidence evidence) {
        this.startDate = startDate; this.endDate = endDate; this.days = immutable(days);
        this.weekdayHours = new long[7][24];
        for (int d = 0; d < Math.min(7, weekdayHours.length); d++)
            System.arraycopy(weekdayHours[d], 0, this.weekdayHours[d], 0, Math.min(24, weekdayHours[d].length));
        this.topicTiles = immutable(topicTiles); this.authorPareto = immutable(authorPareto);
        this.featureSources = immutable(featureSources); this.browseDays = browseDays;
        this.authorCount = authorCount; this.topicCount = topicCount; this.topicOccurrences = topicOccurrences;
        this.repeatedWorks = repeatedWorks; this.capturedVisits = capturedVisits;
        this.finishedVisits = finishedVisits; this.visibleMillis = visibleMillis; this.queueDropped = queueDropped;
        this.authorDiversity = authorDiversity; this.topicDiversity = topicDiversity;
        this.topAuthorShare = topAuthorShare; this.topTopicShare = topTopicShare;
        this.crossDayRepeatShare = crossDayRepeatShare; this.nightShare = nightShare; this.weekendShare = weekendShare;
        this.comparison = comparison; this.weekComparison = weekComparison; this.monthComparison = monthComparison;
        this.insights = immutable(insights); this.topicHours = immutable(topicHours); this.typeActions = immutable(typeActions);
        this.coverage = immutable(coverage); this.evidenceLevel = evidenceLevel;
        this.evidence = evidence;
    }

    public static PortraitSnapshot empty() {
        return new PortraitSnapshot("", "", List.of(), new long[7][24], List.of(), List.of(), List.of(),
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                Comparison.empty(), Comparison.empty(), Comparison.empty(), List.of(), List.of(), List.of(),
                List.of("尚无可用记录；采集默认关闭。"), "暂无样本");
    }

    private static <T> List<T> immutable(List<T> list) {
        return Collections.unmodifiableList(new ArrayList<>(list));
    }

    public static final class Day {
        public final String date;
        public final long views, features, uniqueWorks;
        public final Map<String, Long> types;
        public Day(String date, long views, long features, long uniqueWorks, Map<String, Long> types) {
            this.date = date; this.views = views; this.features = features; this.uniqueWorks = uniqueWorks;
            this.types = Collections.unmodifiableMap(new LinkedHashMap<>(types));
        }
    }

    /** Equal calendar-length windows ending today; today is still in progress. */
    public static final class Comparison {
        public final String currentStart, currentEnd, previousStart, previousEnd, limitation;
        public final long currentViews, previousViews, currentFeatures, previousFeatures;
        public final List<AnalyticsSnapshot.Rank> currentTypes, previousTypes;
        public final List<AnalyticsSnapshot.Rank> currentTopics, previousTopics, currentAuthors, previousAuthors;
        /** Date coverage only. Even true cannot establish complete host observation. */
        public final boolean currentComplete, previousComplete;
        public Comparison(String currentStart, String currentEnd, String previousStart, String previousEnd,
                long currentViews, long previousViews, long currentFeatures, long previousFeatures,
                List<AnalyticsSnapshot.Rank> currentTypes, List<AnalyticsSnapshot.Rank> previousTypes,
                boolean currentComplete, boolean previousComplete, String limitation) {
            this(currentStart, currentEnd, previousStart, previousEnd, currentViews, previousViews,
                    currentFeatures, previousFeatures, currentTypes, previousTypes, currentComplete,
                    previousComplete, limitation, List.of(), List.of(), List.of(), List.of());
        }
        public Comparison(String currentStart, String currentEnd, String previousStart, String previousEnd,
                long currentViews, long previousViews, long currentFeatures, long previousFeatures,
                List<AnalyticsSnapshot.Rank> currentTypes, List<AnalyticsSnapshot.Rank> previousTypes,
                boolean currentComplete, boolean previousComplete, String limitation,
                List<AnalyticsSnapshot.Rank> currentTopics, List<AnalyticsSnapshot.Rank> previousTopics,
                List<AnalyticsSnapshot.Rank> currentAuthors, List<AnalyticsSnapshot.Rank> previousAuthors) {
            this.currentStart = currentStart; this.currentEnd = currentEnd;
            this.previousStart = previousStart; this.previousEnd = previousEnd;
            this.currentViews = currentViews; this.previousViews = previousViews;
            this.currentFeatures = currentFeatures; this.previousFeatures = previousFeatures;
            this.currentTypes = immutable(currentTypes); this.previousTypes = immutable(previousTypes);
            this.currentTopics = immutable(currentTopics); this.previousTopics = immutable(previousTopics);
            this.currentAuthors = immutable(currentAuthors); this.previousAuthors = immutable(previousAuthors);
            this.currentComplete = currentComplete; this.previousComplete = previousComplete; this.limitation = limitation;
        }
        static Comparison empty() {
            return new Comparison("", "", "", "", 0, 0, 0, 0, List.of(), List.of(), false, false, "尚无记录");
        }
    }

    public static final class Insight {
        public final String id, title, body, evidence, limitations;
        public Insight(String id, String title, String body, String evidence, String limitations) {
            this.id = id; this.title = title; this.body = body; this.evidence = evidence; this.limitations = limitations;
        }
    }

    /** Actually co-observed dimensions. Never derived by multiplying independent marginal totals. */
    public static final class Joint {
        public final String left, right;
        public final long count;
        public Joint(String left, String right, long count) { this.left = left; this.right = right; this.count = count; }
    }
}
