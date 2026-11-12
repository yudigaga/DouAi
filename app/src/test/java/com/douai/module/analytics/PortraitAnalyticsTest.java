package com.douai.module.analytics;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.SystemClock;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowSystemClock;
import java.io.File;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public class PortraitAnalyticsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private MutableClock clock;
    private LocalAnalytics store;

    @Before public void before() throws Exception {
        directory = temporary.newFolder("portrait");
        clock = new MutableClock("2026-10-18T04:00:00Z");
        store = new LocalAnalytics(directory, clock);
        LocalAnalytics.cancelAutomaticAdvance();
    }

    @After public void after() { store.close(); LocalAnalytics.cancelAutomaticAdvance(); }

    @Test public void fullDistributionsPreserveTailAndMultiTopicDenominator() {
        store.setEnabled(true);
        for (int i = 0; i < 15; i++) store.recordView("w" + i, "image", "a" + i,
                "作者" + i, List.of("公共话题", "话题" + i), false, 0);
        AnalyticsSnapshot snapshot = store.snapshot(7);
        PortraitSnapshot p = snapshot.portrait;
        assertEquals(10, snapshot.authors.size());
        assertEquals(15, p.authorCount);
        assertEquals(16, p.topicCount);
        assertEquals(30, p.topicOccurrences);
        assertEquals(15, PortraitMetrics.total(p.authorPareto));
        assertEquals(30, PortraitMetrics.total(p.topicTiles));
        assertEquals(9, p.topicTiles.size());
        assertEquals(1, p.authorDiversity, 1e-10);
        assertEquals(.5, p.topTopicShare, 1e-10);
        assertEquals(1.0 / 15, p.topAuthorShare, 1e-10);
        assertEquals(1, p.browseDays);
        assertEquals(15, p.days.get(6).types.get("图集").longValue());
        assertTrue(p.insights.size() >= 5);
    }

    @Test public void unidentifiedAuthorsAreCoverageGapsInsteadOfAnotherPerson() {
        store.setEnabled(true);
        store.recordView("unknown", "image", null, null, List.of("风景"), false, 0);
        store.recordView("known", "image", "identified", "可识别作者", List.of("风景"), false, 0);
        PortraitSnapshot p = store.snapshot(7).portrait;
        assertEquals(1, p.authorCount);
        assertEquals(1, p.authorPareto.size());
        assertEquals("可识别作者", p.authorPareto.get(0).label);
        assertEquals(1, p.topAuthorShare, 0);
        assertEquals(0, p.authorDiversity, 0);
        assertTrue(p.coverage.stream().anyMatch(text -> text.contains("已识别作者的记录 1 / 总浏览 2")));
    }

    @Test public void topicMovementUsesEqualWindowsAndKeepsOffScreenTailCounts() {
        clock.set("2026-10-05T04:00:00Z");
        store.setEnabled(true);
        for (int i = 0; i < 12; i++) store.recordView("old" + i, "image", "author" + i, "作者" + i,
                List.of("旧主线", "旧话题" + i), false, 0);
        store.snapshot(7);
        clock.set("2026-10-18T04:00:00Z");
        for (int i = 0; i < 15; i++) store.recordView("new" + i, "short_video", "author" + i, "作者" + i,
                List.of("新主线", "新话题" + i), false, 0);
        PortraitSnapshot p = store.snapshot(7).portrait;
        assertEquals("新主线", p.comparison.currentTopics.get(0).label);
        assertEquals("旧主线", p.comparison.previousTopics.get(0).label);
        assertEquals(30, PortraitMetrics.total(p.comparison.currentTopics));
        assertEquals(24, PortraitMetrics.total(p.comparison.previousTopics));
        assertEquals(15, PortraitMetrics.total(p.comparison.currentAuthors));
        assertEquals(12, PortraitMetrics.total(p.comparison.previousAuthors));
        assertTrue(p.insights.stream().anyMatch(insight -> insight.id.equals("topic_change")
                && insight.body.contains("前期覆盖不完整") && insight.body.contains("新主线") && insight.body.contains("旧主线")));
    }

    @Test public void crossDayRepeatsUseDistinctWorksAndFeatureDaysDoNotBecomeBrowseDays() {
        clock.set("2026-10-16T04:00:00Z");
        store.setEnabled(true);
        store.recordView("repeat", "image", "a", "甲", List.of(), false, 0);
        store.snapshot(7);
        clock.set("2026-10-17T04:00:00Z");
        store.recordFeature("auto_like", "自动点赞请求");
        store.snapshot(7);
        clock.set("2026-10-18T04:00:00Z");
        store.recordView("repeat", "image", "a", "甲", List.of(), false, 0);
        store.recordView("new", "short_video", "b", "乙", List.of(), false, 0);
        AnalyticsSnapshot snapshot = store.snapshot(7);
        assertEquals(3, snapshot.activeDays);
        assertEquals(2, snapshot.portrait.browseDays);
        assertEquals(1, snapshot.portrait.repeatedWorks);
        assertEquals(.5, snapshot.portrait.crossDayRepeatShare, 1e-10);
        assertEquals(2, snapshot.portrait.weekdayHours[6][12]);
        assertEquals("自动触发", snapshot.portrait.featureSources.get(0).label);
    }

    @Test public void comparisonsHaveEqualCalendarWindowsAndNeverTreatPartialBaselineAsComplete() {
        clock.set("2026-10-05T04:00:00Z"); // Previous 7-day window starts Oct 5, but noon is partial.
        store.setEnabled(true);
        store.recordView("old", "image", "a", "甲", List.of(), false, 0);
        store.snapshot(7);
        clock.set("2026-10-18T04:00:00Z");
        store.recordView("current", "short_video", "a", "甲", List.of(), false, 0);
        PortraitSnapshot p = store.snapshot(7).portrait;
        assertEquals("2026-10-12", p.comparison.currentStart);
        assertEquals("2026-10-18", p.comparison.currentEnd);
        assertEquals("2026-10-05", p.comparison.previousStart);
        assertEquals("2026-10-11", p.comparison.previousEnd);
        assertEquals(1, p.comparison.previousViews);
        assertFalse(p.comparison.previousComplete);
        assertFalse(p.comparison.currentComplete);
        assertFalse(p.monthComparison.previousComplete);
        assertEquals("图集", p.comparison.previousTypes.get(0).label);
    }

    @Test public void knownOptOutGapInvalidatesPreviousWindowEvenWithOlderRecords() {
        clock.set("2026-10-01T00:00:00Z");
        store.setEnabled(true);
        store.recordView("old", "image", "a", "甲", List.of(), false, 0);
        store.snapshot(7);
        clock.set("2026-10-07T00:00:00Z");
        store.setEnabled(false);
        store.snapshot(7);
        clock.set("2026-10-10T00:00:00Z");
        store.setEnabled(true);
        store.snapshot(7);
        clock.set("2026-10-18T00:00:00Z");
        store.recordView("new", "image", "a", "甲", List.of(), false, 0);
        assertFalse(store.snapshot(7).portrait.comparison.previousComplete);
    }

    @Test public void topicHourAndTypeActionComeOnlyFromActuallyLinkedSegments() {
        store.setEnabled(true);
        store.recordView("legacy", "image", "a", "甲", List.of("不能补造"), false, 0);
        String token = visit("live", "live_photo", "风景");
        store.recordAction(store.generation(), "different", "save_media", "保存", "manual", "entry");
        store.recordAction(store.generation(), token, "live", "copy_text", "复制", "manual", "success");
        store.recordFeature("auto_reply_sent", "自动回复成功");
        clock.advance(4000);
        store.finishVisit(token, 4000, "selection_changed");
        PortraitSnapshot p = store.snapshot(7).portrait;
        assertEquals(1, p.capturedVisits);
        assertEquals(1, p.finishedVisits);
        assertEquals(4000, p.visibleMillis);
        assertEquals(1, p.topicHours.size());
        assertEquals("风景", p.topicHours.get(0).right);
        assertEquals("12时", p.topicHours.get(0).left);
        assertEquals(1, p.typeActions.size());
        assertEquals("实况图集", p.typeActions.get(0).left);
        assertTrue(p.typeActions.get(0).right.contains("复制文案"));
        assertEquals(3, store.snapshot(7).totalFeatureUses);
    }

    @Test public void oldVisitTokensCannotAttachAsyncResultsToNewVisitOfSameWork() {
        store.setEnabled(true);
        String old = visit("same", "image", "话题");
        clock.advance(1000);
        store.finishVisit(old, 1000, "background");
        String next = visit("same", "image", "话题");
        assertNotEquals(old, next);
        store.recordAction(store.generation(), old, "same", "save_media", "保存受理", "manual", "accepted");
        AnalyticsSnapshot report = store.snapshot(7);
        assertEquals(1, report.totalViews);
        assertEquals(2, report.portrait.capturedVisits);
        assertEquals(1, report.totalFeatureUses);
        assertTrue(report.portrait.typeActions.isEmpty());
    }

    @Test public void durationsAreCumulativeMonotonicBoundedAndCannotSurviveEpochChange() {
        store.setEnabled(true);
        long epoch = store.generation();
        String token = visit("one", "image", "话题");
        clock.advance(20_000);
        store.pulseVisit(token, 10_000);
        store.pulseVisit(token, 20_000);
        store.pulseVisit(token, 15_000);
        assertEquals(20_000, store.snapshot(7).portrait.visibleMillis);
        clock.advance(4_000_000);
        store.finishVisit(token, 9_000_000, "finished");
        assertEquals(1_800_000, store.snapshot(7).portrait.visibleMillis);
        store.setEnabled(false);
        store.setEnabled(true);
        store.recordAction(epoch, token, "one", "save", "旧请求", "manual", "success");
        store.pulseVisit(token, 9_000_000);
        assertEquals(0, store.snapshot(7).totalFeatureUses);
        store.clear();
        store.finishVisit(token, 9_000_000, "old");
        assertEquals(0, store.snapshot(7).portrait.capturedVisits);
        assertNull(store.beginVisit(epoch, "old", "image", "a", "甲", List.of(), false, 0, "manual"));
    }

    @Test public void perVisitRetentionExpiresWithoutDestroyingLongLivedDailyAggregates() {
        clock.set("2026-09-18T04:00:00Z");
        store.setEnabled(true);
        String old = visit("old", "image", "旧话题");
        clock.advance(1000);
        store.finishVisit(old, 1000, "end");
        store.recordAction(store.generation(), "old", "save", "保存", "manual", "entry");
        store.snapshot(0);
        clock.set("2026-10-18T04:00:00Z");
        visit("new", "image", "新话题");
        AnalyticsSnapshot report = store.snapshot(0);
        assertEquals(2, report.totalViews);
        assertEquals(1, report.portrait.capturedVisits);
        assertEquals(1, report.portrait.topicHours.size());
        assertEquals("新话题", report.portrait.topicHours.get(0).right);
    }

    @Test public void schemaOneMigrationPreservesCountsAndDoesNotInventSessionHistory() {
        store.close();
        try (SQLiteDatabase db = SQLiteDatabase.openOrCreateDatabase(new File(directory, "usage.db"), null)) {
            db.execSQL("CREATE TABLE daily(day TEXT PRIMARY KEY, views INTEGER NOT NULL, features INTEGER NOT NULL, ads INTEGER NOT NULL, content_duration INTEGER NOT NULL)");
            db.execSQL("CREATE TABLE ranks(day TEXT NOT NULL,kind TEXT NOT NULL,item_key TEXT NOT NULL,label TEXT NOT NULL,count INTEGER NOT NULL,PRIMARY KEY(day,kind,item_key))");
            db.execSQL("INSERT INTO daily VALUES ('2026-10-18',4,3,0,1000)");
            db.execSQL("INSERT INTO ranks VALUES ('2026-10-18','topic','legacy','旧话题',4)");
            db.setVersion(1);
        }
        store = new LocalAnalytics(directory, clock);
        AnalyticsSnapshot report = store.snapshot(7);
        assertEquals(4, report.totalViews);
        assertEquals(3, report.totalFeatureUses);
        assertEquals(0, report.portrait.capturedVisits);
        assertEquals(0, report.portrait.visibleMillis);
        assertTrue(report.portrait.topicHours.isEmpty());
        assertEquals("来源未知", report.portrait.featureSources.get(0).label);
        assertEquals(3, report.portrait.featureSources.get(0).count);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(directory, "usage.db").getPath(), null, SQLiteDatabase.OPEN_READONLY)) {
            assertEquals(4, db.getVersion());
        }
    }

    @Test public void queueOverflowIsVisibleAndClearingAlsoResetsDiagnostic() throws Exception {
        store.setEnabled(true);
        store.snapshot(7);
        CountDownLatch gate = new CountDownLatch(1), entered = new CountDownLatch(1);
        Field field = LocalAnalytics.class.getDeclaredField("writer");
        field.setAccessible(true);
        ThreadPoolExecutor writer = (ThreadPoolExecutor) field.get(store);
        writer.execute(() -> { entered.countDown(); try { gate.await(10, TimeUnit.SECONDS); } catch (InterruptedException ignored) { } });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        try { for (int i = 0; i < 300; i++) store.recordFeature("f", "功能"); }
        finally { gate.countDown(); }
        // Do not add a snapshot while the queue is full: its read barrier would also be rejected.
        while (writer.getQueue().remainingCapacity() == 0) Thread.yield();
        assertEquals(44, store.snapshot(7).portrait.queueDropped);
        store.clear();
        assertEquals(0, store.snapshot(7).portrait.queueDropped);
    }

    @Test public void automaticAdvanceMarkerExpiresAndIsConsumedOnlyOnce() {
        LocalAnalytics.markAutomaticAdvance();
        assertEquals("automatic", LocalAnalytics.consumeAdvanceSource());
        assertEquals("unknown", LocalAnalytics.consumeAdvanceSource());
        LocalAnalytics.markAutomaticAdvance();
        ShadowSystemClock.advanceBy(Duration.ofMillis(3001));
        assertEquals("unknown", LocalAnalytics.consumeAdvanceSource());
        LocalAnalytics.markAutomaticAdvance();
        LocalAnalytics.cancelAutomaticAdvance();
        assertEquals("unknown", LocalAnalytics.consumeAdvanceSource());
    }

    @Test public void pureMetricsAreFiniteForEmptySingleAndUnevenDistributions() {
        assertEquals(0, PortraitMetrics.diversity(List.of()), 0);
        assertEquals(0, PortraitMetrics.diversity(List.of(new AnalyticsSnapshot.Rank("one", 7))), 0);
        assertEquals(0, PortraitMetrics.ratio(5, 0), 0);
        List<AnalyticsSnapshot.Rank> uneven = List.of(new AnalyticsSnapshot.Rank("head", 99), new AnalyticsSnapshot.Rank("tail", 1));
        assertTrue(PortraitMetrics.diversity(uneven) < .1);
        assertEquals(100, PortraitMetrics.total(PortraitMetrics.headAndTail(uneven, 1)));
    }

    private String visit(String work, String type, String topic) {
        String token = store.beginVisit(store.generation(), work, type, "author", "作者", List.of(topic), false, 1000, "unknown");
        assertNotNull(token);
        return token;
    }

    private static final class MutableClock extends Clock {
        private Instant instant;
        MutableClock(String value) { set(value); }
        void set(String value) { instant = Instant.parse(value); }
        void advance(long millis) { instant = instant.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("Asia/Shanghai"); }
        @Override public Clock withZone(ZoneId zone) { return Clock.fixed(instant, zone); }
        @Override public Instant instant() { return instant; }
    }
}
