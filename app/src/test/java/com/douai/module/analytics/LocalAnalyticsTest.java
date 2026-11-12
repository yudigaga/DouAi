package com.douai.module.analytics;

import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class LocalAnalyticsTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private File directory;
    private MutableClock clock;
    private LocalAnalytics analytics;

    @Before public void setUp() throws Exception {
        directory = temporary.newFolder("no-backup-analytics");
        clock = new MutableClock("2026-10-18T04:00:00Z", "Asia/Shanghai");
        analytics = new LocalAnalytics(directory, clock);
    }

    @After public void tearDown() { analytics.close(); }

    @Test public void allIncludesFlowDayBeforeTheFirstDailySummary() {
        analytics.setEnabled(true);
        analytics.beginOperation(null,"ai").stage("generated");
        clock.set("2026-10-20T04:00:00Z");
        view("daily");
        assertEquals(1,analytics.snapshot(7).portrait.evidence.flowStages.size());
        PortraitEvidence all=analytics.snapshot(0).portrait.evidence;
        assertEquals("2026-10-18",all.startDate);
        assertEquals(1,all.flowStages.size());
    }

    @Test public void rejectedWriteKeepsVisitDayAcrossRestoreAndClearAndReadBarrierIsNotLoss() throws Exception {
        analytics.setEnabled(true);
        String token=analytics.beginVisit(analytics.generation(),"linked","image",null,"",List.of(),false,1000,"manual");
        analytics.recordAction(analytics.generation(),"linked","save_media","保存","manual","entry");
        analytics.snapshot(1);
        clock.set("2026-10-19T04:00:00Z"); // rejection on the following day still affects the original segment
        CountDownLatch release=blockWriter();
        try {
            for(int i=0;i<256;i++)analytics.recordFeature("f","功能");
            analytics.recordAction(analytics.generation(),token,"linked","copy_text","复制","manual","success");
            assertEquals(1,analytics.snapshot(7).portrait.queueDropped);
        } finally {release.countDown();}
        Field field=LocalAnalytics.class.getDeclaredField("writer");field.setAccessible(true);
        ThreadPoolExecutor writer=(ThreadPoolExecutor)field.get(analytics);
        while(writer.getQueue().remainingCapacity()==0)Thread.yield();
        org.json.JSONObject backup=analytics.exportBackup().put("context",new org.json.JSONObject().put("purpose","").put("feedback",""));
        org.json.JSONObject tables=backup.getJSONObject("tables");
        org.json.JSONArray gaps=tables.getJSONArray("queue_gaps");
        assertEquals(1,gaps.length());assertEquals("2026-10-18",gaps.getJSONArray(0).getString(0));assertEquals("events",gaps.getJSONArray(0).getString(1));
        for(int i=0;i<tables.getJSONArray("source_coverage").length();i++)assertEquals("available",tables.getJSONArray("source_coverage").getJSONArray(i).getString(1));
        var stale=analytics.beginOperation("linked","ai");
        analytics.replaceBackup(AnalyticsBackup.inspect(backup),()->{});
        stale.click("more_click");
        analytics.beginOperation(null,"ai").click("more_click");
        var restored=analytics.exportBackup().getJSONObject("tables");
        assertEquals(1,restored.getJSONArray("queue_gaps").length());assertEquals(1,restored.getJSONArray("flow_events").length());
        analytics.clear();analytics.recordFeature("after","继续");
        assertEquals(0,analytics.exportBackup().getJSONObject("tables").getJSONArray("queue_gaps").length());
        assertEquals(0,analytics.snapshot(7).portrait.queueDropped);
    }

    @Test public void queuedBurstUsesFourTransactionsWithoutLosingAnyOfItsSixtyFourEvents() throws Exception {
        analytics.setEnabled(true);
        CountDownLatch release = blockWriter();
        long before = analytics.writeTransactionCount();
        try {
            for (int i = 0; i < 64; i++) analytics.recordFeature("copy_text", "复制文案");
        } finally { release.countDown(); }
        AnalyticsSnapshot snapshot = analytics.snapshot(1);
        assertEquals(64, snapshot.totalFeatureUses);
        assertEquals(4, analytics.writeTransactionCount() - before);
    }

    @Test public void disablingDuringTheFirstMutationCancelsTheRestOfItsAlreadyDrainedBatch() throws Exception {
        analytics.setEnabled(true);
        CountDownLatch writerRelease = blockWriter();
        CountDownLatch firstMutation = new CountDownLatch(1), mutationRelease = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger mutations = new java.util.concurrent.atomic.AtomicInteger();
        Class<?> mutationType = Class.forName(LocalAnalytics.class.getName() + "$Mutation");
        java.lang.reflect.Method enqueue = LocalAnalytics.class.getDeclaredMethod("enqueueWrite", long.class, LocalDate.class, mutationType);
        enqueue.setAccessible(true);
        long epoch = analytics.generation();
        LocalDate day = LocalDate.of(2026, 10, 18);
        for (int i = 0; i < 16; i++) {
            final boolean first = i == 0;
            Object mutation = java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{mutationType}, (proxy, method, args) -> {
                if (first) { firstMutation.countDown(); assertTrue(mutationRelease.await(5, TimeUnit.SECONDS)); }
                mutations.incrementAndGet(); return null;
            });
            assertEquals(Boolean.TRUE, enqueue.invoke(analytics, epoch, day, mutation));
        }
        writerRelease.countDown();
        try {
            assertTrue(firstMutation.await(5, TimeUnit.SECONDS));
            analytics.setEnabled(false);
        } finally { mutationRelease.countDown(); }
        analytics.snapshot(1);
        assertEquals("Only the mutation already in progress may finish", 1, mutations.get());
    }

    @Test public void defaultIsOffAndEmptySnapshotDoesNotCreateDatabase() {
        assertFalse(analytics.isEnabled());
        view("a");
        analytics.recordFeature("download", "保存作品");
        AnalyticsSnapshot empty = analytics.snapshot(7);
        assertEquals(0, empty.totalViews);
        assertEquals(0, empty.totalFeatureUses);
        assertEquals(0, empty.firstSeen);
        assertTrue(empty.trend.isEmpty());
        assertTrue(empty.types.isEmpty());
        assertEquals(24, empty.hours.length);
        assertFalse(new File(directory, "usage.db").exists());
    }

    @Test public void consecutiveDuplicatesMergeButReturningToAnEarlierWorkCountsAgain() {
        analytics.setEnabled(true);
        view("a");
        view("a");
        view("b");
        view("a");
        AnalyticsSnapshot snapshot = analytics.snapshot(7);
        assertEquals(3, snapshot.totalViews);
        assertEquals(2, snapshot.uniqueWorks);
        assertEquals(3, snapshot.hours[12]);
        assertEquals(3, snapshot.types.get(0).count);
        assertEquals("短视频", snapshot.types.get(0).label);
    }

    @Test public void countsFirstSelectionOfSameWorkOnNextLocalDay() {
        analytics.setEnabled(true);
        view("same");
        assertEquals(1, analytics.snapshot(1).totalViews);
        clock.set("2026-10-18T16:00:00Z");
        view("same");
        assertEquals(1, analytics.snapshot(1).totalViews);
        AnalyticsSnapshot both = analytics.snapshot(7);
        assertEquals(2, both.totalViews);
        assertEquals(1, both.uniqueWorks);
        assertEquals(2, both.currentStreak);
    }

    @Test public void metadataDimensionsDeduplicateTopicsAndKeepLivePhotosDistinct() {
        analytics.setEnabled(true);
        analytics.recordView("photo", "live_photo", "author-id", "作者甲",
                List.of(" #风景 ", "风景", "#旅行", "", "  "), true, 0);
        analytics.recordView("image", "image", "author-id", "作者甲",
                List.of("风景"), false, -1);
        analytics.recordFeature("save", "保存作品");
        analytics.recordFeature("save", "保存作品");
        AnalyticsSnapshot snapshot = analytics.snapshot(1);
        assertEquals(2, snapshot.totalViews);
        assertEquals(1, snapshot.advertisementViews);
        assertEquals(2, snapshot.totalFeatureUses);
        assertEquals(2, snapshot.types.size());
        assertTrue(snapshot.types.stream().anyMatch(rank -> rank.label.equals("实况图集")));
        assertEquals(1, snapshot.authors.size());
        assertEquals("作者甲", snapshot.authors.get(0).label);
        assertEquals(2, snapshot.authors.get(0).count);
        assertEquals(2, snapshot.topics.size());
        assertEquals("风景", snapshot.topics.get(0).label);
        assertEquals(2, snapshot.topics.get(0).count);
        assertEquals(2, snapshot.features.get(0).count);
    }

    @Test public void rangesAreTodayInclusiveAndUniqueWorksAreDistinctAcrossDays() {
        analytics.setEnabled(true);
        clock.set("2026-10-11T16:00:00Z"); // 10/12: oldest day of the seven-day report.
        view("repeated");
        assertEquals(1, analytics.snapshot(1).totalViews);
        clock.set("2026-10-17T15:59:59Z"); // 10/17 just before midnight.
        view("yesterday");
        assertEquals(1, analytics.snapshot(1).totalViews);
        clock.set("2026-10-17T16:00:00Z"); // 10/18 local midnight.
        view("repeated");
        assertEquals(1, analytics.snapshot(1).totalViews);
        AnalyticsSnapshot week = analytics.snapshot(7);
        assertEquals(3, week.totalViews);
        assertEquals(2, week.uniqueWorks);
        assertEquals(7, week.trend.size());
        assertEquals("10/12", week.trend.get(0).label);
        assertEquals("10/18", week.trend.get(6).label);
        assertEquals(3, week.activeDays);
        assertEquals(2, week.currentStreak);
    }

    @Test public void fallBackHourCountsBothOccurrencesWithoutAddingAnExtraCalendarDay() {
        analytics.close();
        clock = new MutableClock("2026-11-01T03:59:59Z", "America/New_York");
        analytics = new LocalAnalytics(directory, clock);
        analytics.setEnabled(true);
        view("previous-day");
        assertEquals(1, analytics.snapshot(1).totalViews);
        clock.set("2026-11-01T05:30:00Z");
        view("first-0130");
        clock.set("2026-11-01T06:30:00Z");
        view("second-0130");
        AnalyticsSnapshot today = analytics.snapshot(1);
        assertEquals(2, today.totalViews);
        assertEquals(2, today.hours[1]);
        assertEquals(1, today.activeDays);
        assertEquals(3, analytics.snapshot(7).totalViews);
    }

    @Test public void preferenceAndAggregatesPersistWhileRawIdentifiersAreNeverStored() throws Exception {
        analytics.setEnabled(true);
        analytics.recordView("raw-secret-work-id", "short_video", "raw-author-id", "作者乙",
                List.of("话题"), false, 3000);
        assertEquals(1, analytics.snapshot(1).totalViews);
        analytics.close();
        analytics = new LocalAnalytics(directory, clock);
        assertTrue(analytics.isEnabled());
        assertEquals(1, analytics.snapshot(1).totalViews);
        try (SQLiteDatabase db = SQLiteDatabase.openDatabase(new File(directory, "usage.db").getPath(),
                null, SQLiteDatabase.OPEN_READONLY);
                Cursor cursor = db.rawQuery("SELECT work_hash FROM work_days", null)) {
            assertTrue(cursor.moveToFirst());
            assertEquals(64, cursor.getString(0).length());
            assertNotEquals("raw-secret-work-id", cursor.getString(0));
        }
        String diskText = new String(Files.readAllBytes(new File(directory, "usage.db").toPath()),
                java.nio.charset.StandardCharsets.ISO_8859_1);
        assertFalse(diskText.contains("raw-secret-work-id"));
        assertFalse(diskText.contains("raw-author-id"));
        assertEquals("1", Files.readString(new File(directory, "enabled").toPath()));
    }

    @Test public void disablingRetainsPriorReportAndPersistsOptOut() {
        analytics.setEnabled(true);
        view("before-disable");
        assertEquals(1, analytics.snapshot(1).totalViews);
        analytics.setEnabled(false);
        view("after-disable");
        analytics.recordFeature("save", "保存作品");
        assertEquals(1, analytics.snapshot(1).totalViews);
        analytics.close();
        analytics = new LocalAnalytics(directory, clock);
        assertFalse(analytics.isEnabled());
        assertEquals(1, analytics.snapshot(1).totalViews);
    }

    @Test public void queuedEventsCannotSurviveDisableAndReenable() throws Exception {
        analytics.setEnabled(true);
        CountDownLatch release = blockWriter();
        try {
            view("stale");
            analytics.recordFeature("stale-feature", "旧功能");
            long oldEpoch = analytics.generation();
            analytics.setEnabled(false);
            analytics.setEnabled(true);
            assertTrue(analytics.generation() > oldEpoch);
            analytics.recordView(oldEpoch, "posted-before-disable", "image", "author", "作者",
                    List.of(), false, 0);
            view("new");
        } finally { release.countDown(); }
        AnalyticsSnapshot snapshot = analytics.snapshot(1);
        assertEquals(1, snapshot.totalViews);
        assertEquals(0, snapshot.totalFeatureUses);
        assertEquals(1, snapshot.uniqueWorks);
    }

    @Test public void clearRemovesAllDataAndFencesBothQueuedAndPostedObservations() throws Exception {
        analytics.setEnabled(true);
        view("stored");
        assertEquals(1, analytics.snapshot(1).totalViews);
        CountDownLatch release = blockWriter();
        try {
            view("queued");
            analytics.recordFeature("save", "保存作品");
            long oldEpoch = analytics.generation();
            analytics.clear();
            analytics.recordView(oldEpoch, "posted-before-clear", "image", "author", "作者",
                    List.of(), false, 0);
        } finally { release.countDown(); }
        AnalyticsSnapshot empty = analytics.snapshot(0);
        assertEquals(0, empty.totalViews);
        assertEquals(0, empty.totalFeatureUses);
        assertEquals(0, empty.firstSeen);
        assertTrue(empty.recent.isEmpty());
        assertTrue(empty.authors.isEmpty());
        assertTrue(analytics.isEnabled());
        view("stored");
        assertEquals(1, analytics.snapshot(0).totalViews);
    }

    @Test public void retentionPrunesExpiredIdentitiesButKeepsFirstUseAndBoundsTrend() {
        analytics.setEnabled(true);
        LocalDate today = LocalDate.of(2026, 10, 18);
        clock.set(today.minusDays(365).atStartOfDay(clock.getZone()).toInstant().toString());
        long firstSeen = clock.millis();
        view("expired");
        assertEquals(1, analytics.snapshot(0).totalViews);
        clock.set(today.minusDays(364).atStartOfDay(clock.getZone()).toInstant().toString());
        view("oldest-retained");
        assertEquals(2, analytics.snapshot(0).totalViews);
        clock.set(today.atStartOfDay(clock.getZone()).toInstant().toString());
        view("today");
        AnalyticsSnapshot snapshot = analytics.snapshot(0);
        assertEquals(2, snapshot.totalViews);
        assertEquals(2, snapshot.uniqueWorks);
        assertEquals(firstSeen, snapshot.firstSeen);
        assertEquals(53, snapshot.trend.size());
        assertEquals(2, snapshot.trend.stream().mapToLong(bucket -> bucket.count).sum());
        assertEquals(2, snapshot.recent.size());
    }

    @Test public void observationsDuringClearCannotSuppressOrReplaceNextSelection() throws Exception {
        analytics.setEnabled(true);
        view("stored");
        assertEquals(1, analytics.snapshot(1).totalViews);
        CountDownLatch releaseWriter = blockWriter();
        Field lockField = LocalAnalytics.class.getDeclaredField("databaseLock");
        lockField.setAccessible(true);
        Object databaseLock = lockField.get(analytics);
        ExecutorService clearing = Executors.newSingleThreadExecutor();
        try {
            Future<?> clear;
            long duringEpoch;
            synchronized (databaseLock) {
                long beforeEpoch = analytics.generation();
                clear = clearing.submit(analytics::clear);
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
                while (analytics.generation() == beforeEpoch && System.nanoTime() < deadline)
                    Thread.yield();
                assertTrue("Clear must invalidate old work before waiting on the database",
                        analytics.generation() > beforeEpoch);
                duringEpoch = analytics.generation();
                analytics.recordView("same-work", "image", "author", "清空期间的旧记录",
                        List.of(), false, 0);
            }
            clear.get(5, TimeUnit.SECONDS);
            assertTrue(analytics.generation() > duringEpoch);
            analytics.recordView("same-work", "image", "author", "清空后的新记录",
                    List.of(), false, 0);
        } finally {
            releaseWriter.countDown();
            clearing.shutdownNow();
        }
        AnalyticsSnapshot snapshot = analytics.snapshot(1);
        assertEquals(1, snapshot.totalViews);
        assertEquals("清空后的新记录", snapshot.authors.get(0).label);
    }

    @Test public void recentAndRankingsAreBoundedWithExactAggregates() {
        analytics.setEnabled(true);
        for (int index = 0; index < 40; index++) {
            analytics.recordView("work-" + index, "short_video", "author-" + index, "作者" + index,
                    List.of("话题" + index), false, 0);
            analytics.recordFeature("feature-" + index, "功能" + index);
        }
        AnalyticsSnapshot snapshot = analytics.snapshot(7);
        assertEquals(40, snapshot.totalViews);
        assertEquals(40, snapshot.uniqueWorks);
        assertEquals(40, snapshot.totalFeatureUses);
        assertEquals(10, snapshot.authors.size());
        assertEquals(10, snapshot.topics.size());
        assertEquals(10, snapshot.features.size());
        assertEquals(30, snapshot.recent.size());
    }

    @Test public void unavailableStorageCannotEnableCollection() throws Exception {
        analytics.close();
        File obstructed = temporary.newFile("not-a-directory");
        analytics = new LocalAnalytics(obstructed, clock);
        analytics.setEnabled(true);
        assertFalse(analytics.isEnabled());
        view("ignored");
        assertEquals(0, analytics.snapshot(0).totalViews);
    }

    @Test public void corruptedDatabaseFailsClosedAndReportsAnErrorInsteadOfEmptyData() throws Exception {
        Files.writeString(new File(directory, "usage.db").toPath(), "not a database");
        analytics.setEnabled(true);
        try {
            analytics.snapshot(7);
            fail("Database errors must not be presented as an empty usage report");
        } catch (IllegalStateException expected) {
            assertEquals("无法读取本地统计数据", expected.getMessage());
        }
        assertFalse(analytics.isEnabled());
        analytics.close();
        analytics = new LocalAnalytics(directory, clock);
        assertFalse(analytics.isEnabled());
    }

    @Test public void explicitClearRecoversCorruptedStorageWithoutReenablingCollection() throws Exception {
        File databaseFile = new File(directory, "usage.db");
        Files.writeString(databaseFile.toPath(), "damaged usage database");
        analytics.setEnabled(true);
        try {
            analytics.snapshot(7);
            fail("Corrupted storage should report a read failure");
        } catch (IllegalStateException expected) {
            assertEquals("无法读取本地统计数据", expected.getMessage());
        }
        assertFalse(analytics.isEnabled());
        assertTrue("Read failures must preserve records until explicit clear", databaseFile.exists());
        analytics.clear();
        assertFalse(databaseFile.exists());
        assertFalse(analytics.isEnabled());
        assertEquals(0, analytics.snapshot(0).totalViews);
        analytics.setEnabled(true);
        view("recovered");
        assertEquals(1, analytics.snapshot(0).totalViews);
    }

    private void view(String id) {
        analytics.recordView(id, "short_video", "author", "作者", List.of(), false, 1000);
    }

    private CountDownLatch blockWriter() throws Exception {
        Field field = LocalAnalytics.class.getDeclaredField("writer");
        field.setAccessible(true);
        ThreadPoolExecutor writer = (ThreadPoolExecutor) field.get(analytics);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        writer.execute(() -> {
            entered.countDown();
            try { release.await(10, TimeUnit.SECONDS); }
            catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        assertTrue("Writer gate should start", entered.await(5, TimeUnit.SECONDS));
        return release;
    }

    private static final class MutableClock extends Clock {
        private volatile Instant instant;
        private final ZoneId zone;
        MutableClock(String instant, String zone) {
            this.instant = Instant.parse(instant);
            this.zone = ZoneId.of(zone);
        }
        void set(String value) { instant = Instant.parse(value); }
        @Override public ZoneId getZone() { return zone; }
        @Override public Clock withZone(ZoneId newZone) {
            return Clock.fixed(instant, newZone);
        }
        @Override public Instant instant() { return instant; }
    }
}
