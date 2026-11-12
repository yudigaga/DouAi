package com.douai.module.analytics;

import static org.junit.Assert.*;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Field;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.Locale;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.SQLiteMode;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@SQLiteMode(SQLiteMode.Mode.NATIVE)
public class SnapshotBenchmark {
    private static long used() {
        Runtime rt = Runtime.getRuntime();
        return rt.totalMemory() - rt.freeMemory();
    }

    private static long number(Object obj,String name) throws ReflectiveOperationException {
        return obj.getClass().getField(name).getLong(obj);
    }

    @Test public void fiveWarmSnapshots() throws Exception {
        Clock clock = Clock.fixed(Instant.parse("2026-11-11T12:00:00Z"), ZoneOffset.UTC);
        try (LocalAnalytics store = new LocalAnalytics(new File(System.getProperty("bench.directory")), clock)) {
            Field writerField = LocalAnalytics.class.getDeclaredField("writer");
            writerField.setAccessible(true);
            ThreadPoolExecutor writer = (ThreadPoolExecutor) writerField.get(store);
            boolean current = System.getProperty("bench.revision").equals("current");
            int days = Integer.getInteger("bench.days", 365);
            for (int i = -2; i < 5; i++) {
                System.gc();
                Thread.sleep(75);
                long startHeap = used();
                AtomicLong peak = new AtomicLong(startHeap);
                AtomicBoolean running = new AtomicBoolean(true);
                Thread sampler = new Thread(() -> {
                    while (running.get()) {
                        peak.accumulateAndGet(used(), Math::max);
                        try { Thread.sleep(5); } catch (InterruptedException e) { return; }
                    }
                });
                assertEquals(0, writer.getQueue().size());
                sampler.start();
                long begin = System.nanoTime();
                AnalyticsSnapshot result;
                long elapsed;
                try {
                    result = store.snapshot(days);
                    elapsed = System.nanoTime() - begin;
                    peak.accumulateAndGet(used(), Math::max);
                } finally {
                    running.set(false);
                    sampler.join();
                }
                assertEquals(Long.getLong("bench.expected.views", 243333), Long.valueOf(result.totalViews));
                assertEquals(Long.getLong("bench.expected.features", 24000), Long.valueOf(result.totalFeatureUses));
                assertEquals(days, result.portrait.browseDays);
                assertEquals(Long.getLong("bench.expected.visits", 20000), Long.valueOf(result.portrait.capturedVisits));
                assertEquals(0, result.portrait.queueDropped);
                assertEquals(0, writer.getQueue().size());
                if (current) {
                    Object evidence = PortraitSnapshot.class.getField("evidence").get(result.portrait);
                    for (String field : new String[]{"topics","actionRates","flowStages","familiarity"}) {
                        assertFalse(field, ((Collection<?>) evidence.getClass().getField(field).get(evidence)).isEmpty());
                    }
                    // Every retained segment has a recognized topic; each of our two
                    // actions occurred in exactly 12,000 distinct synthetic segments.
                    Collection<?> rates=(Collection<?>) evidence.getClass().getField("actionRates").get(evidence);
                    assertEquals(16, rates.size());
                    for (Object rate : rates) {
                        Object with=rate.getClass().getField("withTopic").get(rate);
                        Object without=rate.getClass().getField("withoutTopic").get(rate);
                        assertEquals(Long.getLong("bench.expected.visits", 20000).longValue(), number(with,"denominator")+number(without,"denominator"));
                        String action = rate.getClass().getField("action").get(rate).toString();
                        assertEquals(Long.getLong("bench.expected."+(action.startsWith("save_media")?"saved":"copied"), 12000).longValue(), number(with,"numerator")+number(without,"numerator"));
                    }
                    long eligibleRows=0;
                    for (Object group : (Collection<?>)evidence.getClass().getField("familiarity").get(evidence)) {
                        eligibleRows+=number(group.getClass().getField("familiar").get(group),"n");
                        eligibleRows+=number(group.getClass().getField("unseen").get(group),"n");
                    }
                    assertEquals(Long.getLong("bench.expected.visits", 20000).longValue(), eligibleRows);
                } else {
                    // Fail loudly if the Gradle jar shadows the compiled baseline on the classpath.
                    assertThrows(NoSuchFieldException.class, () -> PortraitSnapshot.class.getField("evidence"));
                }
                if (i >= 0) System.out.printf(Locale.ROOT, "SAMPLE %d %.3f %.2f %.2f%n",
                        i + 1, elapsed / 1e6, peak.get() / 1048576.0, (peak.get()-startHeap) / 1048576.0);
            }
            if (current) {
                Field databaseField = LocalAnalytics.class.getDeclaredField("database");
                databaseField.setAccessible(true);
                SQLiteDatabase db = (SQLiteDatabase) databaseField.get(store);
                try (Cursor c = db.rawQuery("SELECT sqlite_version()", null)) {
                    c.moveToFirst(); System.out.println("SQLITE " + c.getString(0));
                }
                for (String sql : Files.readAllLines(Path.of(System.getProperty("bench.plans")))) {
                    System.out.println("PLAN " + sql);
                    try (Cursor c = db.rawQuery("EXPLAIN QUERY PLAN " + sql, new String[]{"2025-11-12","2026-11-11"})) {
                        while (c.moveToNext()) System.out.println("STEP " + c.getString(3));
                    }
                }
            }
            if (current && Boolean.getBoolean("bench.pressure")) {
                for (int range : new int[]{7,30,365,7}) {
                    long begin = System.nanoTime();
                    AnalyticsSnapshot result = store.snapshot(range);
                    assertEquals(range == 7 ? 4667 : range == 30 ? 20000 : 243333, result.totalViews);
                    System.out.printf(Locale.ROOT, "RANGE_SEQUENCE days=%d elapsed_ms=%.3f views=%d scope=storage_reads_not_ui_coalescing%n",
                            range, (System.nanoTime()-begin)/1e6, result.totalViews);
                }
                pressure(store, writer, days);
            }
        }
    }

    private static void pressure(LocalAnalytics store, ThreadPoolExecutor writer, int days) throws Exception {
        store.setEnabled(true);
        long before = store.snapshot(days).totalFeatureUses;
        List<Long> admissions = new ArrayList<>();
        AtomicLong readNanos = new AtomicLong();
        CountDownLatch reading = new CountDownLatch(1);
        FutureTask<AnalyticsSnapshot> read = new FutureTask<>(() -> {
            long begin = System.nanoTime();
            reading.countDown();
            try { return store.snapshot(days); }
            finally { readNanos.set(System.nanoTime()-begin); }
        });
        Thread reader = new Thread(read, "benchmark-report-reader");
        reader.setDaemon(true);
        reader.start();
        assertTrue(reading.await(5, TimeUnit.SECONDS));
        int maxDepth = 0;
        int writesWhileReportOutstanding = 0;
        long started = System.nanoTime();
        for (int i = 0; i < 80; i++) {
            long begin = System.nanoTime();
            if (!read.isDone()) writesWhileReportOutstanding++;
            store.recordFeature("benchmark_pressure", "benchmark pressure");
            admissions.add(System.nanoTime()-begin);
            maxDepth = Math.max(maxDepth, writer.getQueue().size());
            Thread.sleep(5);
        }
        // A single final marker preserves production batching of adjacent writes.
        long enqueued = System.nanoTime();
        java.util.concurrent.Future<Long> settled = writer.submit(System::nanoTime);
        read.get(30, TimeUnit.SECONDS);
        long settledAt = settled.get(30, TimeUnit.SECONDS);
        long barrierWait = settledAt-enqueued;
        assertTrue("Pressure sample needs overlapping report/write callers", writesWhileReportOutstanding > 0);
        AnalyticsSnapshot after = store.snapshot(days);
        assertEquals(before + 80, after.totalFeatureUses);
        assertEquals(0, after.portrait.queueDropped);
        Collections.sort(admissions);
        System.out.printf(Locale.ROOT, "PRESSURE writes=80 writes_while_report_outstanding=%d max_queue_before_marker=%d producer_call_p95_ms=%.3f producer_call_max_ms=%.3f loaded_report_ms=%.3f final_barrier_wait_ms=%.3f writes_settled_ms=%.3f dropped=%d%n",
                writesWhileReportOutstanding, maxDepth, admissions.get(75)/1e6, admissions.get(79)/1e6, readNanos.get()/1e6,
                barrierWait/1e6, (settledAt-started)/1e6, after.portrait.queueDropped);
        // Logical backup and clear must remain ordered after all admitted writes.
        try {
            store.exportBackup();
            fail("The full-capacity fixture exceeds the existing backup row/byte budget");
        } catch (com.douai.module.config.BackupLimitException expected) {
            assertTrue("A bounded backup rejection must not disable collection", store.isEnabled());
        }
        store.clear();
        assertEquals(0, store.snapshot(days).totalFeatureUses);
        assertTrue(store.isEnabled());
        assertTrue(store.exportBackup().length() > 0);
        System.out.println("PRESSURE oversized_backup_rejected=true clear_then_empty_backup=verified collection_still_enabled=true");
    }
}
