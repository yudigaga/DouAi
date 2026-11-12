package com.douai.module.analytics;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.AtomicFile;
import com.douai.module.diagnostics.DouAiLog;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.AtomicMoveNotSupportedException;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Opt-in analytics in the host's no-backup directory. No network or shared preferences.
 * Daily aggregates and hashed work/day identities expire after 365 local calendar days;
 * only 30 recent event labels are kept. The bounded writer never blocks feed hooks.
 */
public final class LocalAnalytics implements AutoCloseable {
    public static final int RETENTION_DAYS = 365;
    public static final int VISIT_RETENTION_DAYS = 30;
    private static final long MAX_VISIBLE_MILLIS = 30 * 60 * 1000L;
    private static final int MAX_VISITS = 20_000;
    private static final int QUEUE_CAPACITY = 256;
    private static final DateTimeFormatter SHORT_DATE = DateTimeFormatter.ofPattern("MM/dd");
    private static final Map<String, LocalAnalytics> INSTANCES = new HashMap<>();
    private static volatile LocalAnalytics active;
    private static long automaticAdvanceAt = -1;

    private final File directory;
    private final AtomicFile enabledFile;
    private final Clock clock;
    private final Supplier<ZoneId> zone;
    private final ThreadPoolExecutor writer;
    private final Object stateLock = new Object();
    private final Object databaseLock = new Object();
    private volatile boolean enabled;
    private volatile boolean closed;
    private volatile long generation;
    private volatile long storageEpoch;
    private String lastWorkHash;
    private LocalDate lastWorkDay;
    private SQLiteDatabase database;
    private LocalDate lastPrunedDay;
    // Guarded by stateLock; bounded by distinct retained dates and finite source names.
    private Map<LocalDate,Set<String>> pendingGaps = new HashMap<>();
    private List<long[]> pendingBoundaries = new ArrayList<>();
    private final AtomicLong queueDropped = new AtomicLong();
    private final AtomicLong writeTransactions = new AtomicLong();
    private boolean trimVisitsPending, trimActionsPending;
    private Visit currentVisit;

    private static final class Visit {
        final String token, workHash;
        final long generation, started;
        long visibleMillis, persistedMillis;
        Visit(String token, String workHash, long generation, long started) {
            this.token = token; this.workHash = workHash; this.generation = generation; this.started = started;
        }
    }

    public static LocalAnalytics get(Context context) {
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        File directory = new File(application.getNoBackupFilesDir(), "douai-analytics");
        synchronized (INSTANCES) {
            String key = directory.getAbsolutePath();
            LocalAnalytics instance = INSTANCES.get(key);
            if (instance == null || instance.closed) {
                instance = new LocalAnalytics(directory, Clock.systemUTC(), ZoneId::systemDefault);
                INSTANCES.put(key, instance);
            }
            return instance;
        }
    }

    public static void initialize(Context context) { active = get(context); }

    public static synchronized void markAutomaticAdvance() { automaticAdvanceAt = android.os.SystemClock.elapsedRealtime(); }
    public static synchronized void cancelAutomaticAdvance() { automaticAdvanceAt = -1; }
    public static synchronized String consumeAdvanceSource() {
        long elapsed = android.os.SystemClock.elapsedRealtime() - automaticAdvanceAt;
        boolean automatic = automaticAdvanceAt >= 0 && elapsed >= 0 && elapsed <= 3000;
        automaticAdvanceAt = -1;
        return automatic ? "automatic" : "unknown";
    }

    /** Counts the action itself; only an explicit matching current work can establish a joint observation. */
    public static void action(String workId, String key, String label, String source, String stage) {
        LocalAnalytics instance = active;
        if (instance != null) instance.recordAction(instance.generation, workId, key, label, source, stage);
    }

    /** Immutable local attribution; carries no raw work identifiers or content. */
    public static final class FlowOperation {
        private final LocalAnalytics owner;
        private final long generation;
        private final String id = UUID.randomUUID().toString(), token, kind;
        private int nextClick;
        private long sequence;
        private final Set<String> aiMarkers = new HashSet<>();
        private FlowOperation(LocalAnalytics owner, long generation, String token, String kind) {
            this.owner = owner; this.generation = generation; this.token = token; this.kind = kind;
        }
        public void stage(String stage) { stage(stage, 0); }
        public void stage(String stage, int task) {
            synchronized (owner.stateLock) {
                if (kind.equals("ai") && !aiMarkers.add(stage)) return;
                owner.recordFlow(this, stage, task);
            }
        }
        /** One identity per UI click. Only its own successful route callback reuses it. */
        public Click click(String stage) {
            synchronized (owner.stateLock) {
                if (!kind.equals("ai") || !List.of("video_click","more_click","followup_click").contains(stage)) return null;
                int id = ++nextClick;
                owner.recordFlow(this, stage, id);
                return new Click(this, id);
            }
        }
    }
    public static final class Click {
        private final FlowOperation operation;
        private final int id;
        private boolean routed;
        private Click(FlowOperation operation, int id) { this.operation=operation; this.id=id; }
        public void dispatched() {
            synchronized (operation.owner.stateLock) {
                if (!routed) { routed=true; operation.owner.recordFlow(operation, "route_dispatched", id); }
            }
        }
    }
    public FlowOperation beginOperation(String workId, String kind) {
        if (!List.of("download", "ai", "playback", "speed", "backward").contains(kind)) return null;
        synchronized (stateLock) {
            if (!isEnabled()) return null;
            return new FlowOperation(this, generation, currentVisitToken(workId), kind);
        }
    }
    /** Deferred UI captures pass the already verified original local token and generation. */
    public FlowOperation beginOperation(long expectedGeneration, String capturedVisit, String kind) {
        if (!List.of("download", "ai", "playback", "speed", "backward").contains(kind)) return null;
        synchronized (stateLock) {
            if (!isEnabled() || generation != expectedGeneration) return null;
            return new FlowOperation(this, generation, capturedVisit, kind);
        }
    }
    private void recordFlow(FlowOperation op, String stage, int task) {
        if (task < 0 || (!op.kind.equals("ai") && task > 10000) || !List.of("entry","request","accepted","task_end","output_closed","cancelled","failure","generated","dispatched","visible","followup_click","more_click","video_click","route_dispatched","manual_select","restore","inherited","requested","readback","playing","paused","ended","confirmed").contains(stage)) return;
        synchronized (stateLock) {
            if (!isEnabled() || generation != op.generation) return;
            long timestamp = clock.millis();
            LocalDate day = Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
            Long seq = op.kind.equals("ai") ? ++op.sequence : null;
            enqueueWrite(op.generation, day, op.kind, db -> {
                db.execSQL("INSERT OR IGNORE INTO flow_events(operation_id,token,kind,stage,task,day,ts,seq) VALUES (?,?,?,?,?,?,?,?)",
                        new Object[]{op.id, op.token, op.kind, stage, task, day.toString(), timestamp, seq});
                markAvailable(db, op.kind.equals("speed") || op.kind.equals("backward") ? "gesture" : op.kind, timestamp);
                markAvailable(db, "events", timestamp);
                trimActionsPending = true;
            });
        }
    }

    /** Call only when a module operation actually runs; merely enabling a setting is not usage. */
    public static void feature(String key, String label) {
        LocalAnalytics instance = active;
        if (instance != null) instance.recordFeature(key, label);
    }

    LocalAnalytics(File directory, Clock clock) { this(directory, clock, clock::getZone); }

    private LocalAnalytics(File directory, Clock clock, Supplier<ZoneId> zone) {
        this.directory = directory;
        this.enabledFile = new AtomicFile(new File(directory, "enabled"));
        this.clock = clock;
        this.zone = zone;
        this.writer = new ThreadPoolExecutor(1, 1, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                    Thread thread = new Thread(runnable, "douai-local-analytics");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        this.writer.allowCoreThreadTimeOut(true);
        try {
            enabled = "1".equals(new String(enabledFile.readFully(), StandardCharsets.UTF_8));
        } catch (Exception ignored) { enabled = false; }
    }

    public boolean isEnabled() { return enabled && !closed; }

    /** Captured by deferred host observations; pass it to the tokenized recordView overload. */
    public long generation() { return generation; }

    /** Stops acceptance immediately; queued events from an earlier generation are discarded. */
    public void setEnabled(boolean value) {
        DouAiLog.Operation operation = DouAiLog.begin("local_analytics_collection", "set_enabled");
        synchronized (stateLock) {
            if (closed) { operation.finish(DouAiLog.Outcome.SKIPPED, "store_closed", "enabled=false"); return; }
            enabled = false;
            generation++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
            try {
                persistEnabled(value);
                enabled = value;
                long token = storageEpoch;
                long timestamp = clock.millis();
                pendingBoundaries.add(new long[]{timestamp, value ? 1 : 0});
                enqueue(() -> { synchronized (databaseLock) {
                    if (closed || storageEpoch != token) return;
                    try { flushPending(openDatabase()); } catch (Exception failure) { failClosed(); }
                }});
                operation.finish(DouAiLog.Outcome.SUCCESS, "collection_flag_persisted", "enabled=" + enabled
                        + "; generation=" + generation + "; boundary_write=pending");
            } catch (Exception ignored) {
                operation.error("persist_collection_flag", ignored);
                operation.finish(DouAiLog.Outcome.FAILED, "collection_disabled_on_storage_failure", "enabled=false");
                try { enabledFile.delete(); } catch (Exception ignoredDelete) { /* Fail closed. */ }
            }
        }
    }

    /**
     * Counts consecutive distinct visible works. A,A,B,A is three visits and two unique works.
     * durationMillis is the known content length, not watched duration (nonpositive = unknown).
     */
    public void recordView(String workId, String type, String authorId, String authorName,
            List<String> topics, boolean advertisement, long durationMillis) {
        recordView(generation, workId, type, authorId, authorName, topics, advertisement, durationMillis);
    }

    /** Rejects observations posted before a clear or enable/disable transition. */
    public void recordView(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, boolean advertisement, long durationMillis) {
        recordViewInternal(expectedGeneration, workId, type, authorId, authorName, topics, advertisement,
                durationMillis, null, null);
    }

    public String beginVisit(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, boolean advertisement, long contentDuration, String source) {
        return recordViewInternal(expectedGeneration, workId, type, authorId, authorName, topics,
                advertisement, contentDuration, normalizeSource(source), null);
    }

    public String beginVisit(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, Boolean advertisement, long contentDuration, String source, Integer imageCount) {
        return recordViewInternal(expectedGeneration, workId, type, authorId, authorName, topics,
                advertisement, contentDuration, normalizeSource(source), imageCount);
    }

    private String recordViewInternal(long expectedGeneration, String workId, String type, String authorId,
            String authorName, List<String> topics, Boolean advertisement, long durationMillis, String source, Integer imageCount) {
        if (!isEnabled() || workId == null || workId.trim().isEmpty()) {
            DouAiLog.event("local_analytics", "observation_skipped", "disabled_or_missing_work", "enabled=" + isEnabled());
            return null;
        }
        try {
            String workHash = hash(workId.trim());
            String typeKey = normalizeType(type);
            String authorLabel = clean(authorName, 120);
            if (authorLabel.isEmpty()) authorLabel = "未识别作者";
            String authorKey = hash(authorId != null && !authorId.trim().isEmpty()
                    ? "id:" + authorId.trim() : "name:" + authorLabel);
            Set<String> uniqueTopics = new LinkedHashSet<>();
            if (topics != null) {
                for (String topic : topics) {
                    String normalized = clean(topic, 100);
                    while (normalized.startsWith("#")) normalized = normalized.substring(1).trim();
                    if (!normalized.isEmpty()) uniqueTopics.add(normalized);
                    if (uniqueTopics.size() == 32) break;
                }
            }
            final String finalAuthorLabel = authorLabel;
            final String segmentAuthorHash = authorId == null || authorId.trim().isEmpty() ? null : hash("id:" + authorId.trim());
            synchronized (stateLock) {
                if (!isEnabled() || generation != expectedGeneration) {
                    DouAiLog.event("local_analytics", "observation_skipped", "stale_generation_or_disabled", "enabled=" + isEnabled()); return null;
                }
                long token = generation;
                long timestamp = clock.millis();
                ZonedDateTime local = Instant.ofEpochMilli(timestamp).atZone(zone.get());
                if (workHash.equals(lastWorkHash) && local.toLocalDate().equals(lastWorkDay)) {
                    // Repeated selection callbacks do not inflate visits. A visibility resume may
                    // reopen a bounded segment, while retaining the existing work-view dedupe.
                    if (source == null) return null;
                    if (currentVisit != null && currentVisit.workHash.equals(workHash)) return currentVisit.token;
                }
                boolean countView = !workHash.equals(lastWorkHash) || !local.toLocalDate().equals(lastWorkDay);
                String visitToken = source == null ? null : UUID.randomUUID().toString();
                boolean queued = enqueueWrite(token, local.toLocalDate(), visitToken == null ? "history" : countView ? "visit_history" : "visits", db -> {
                    if (visitToken != null) {
                        db.execSQL("INSERT INTO visits(token,work_hash,type,day,hour,source,started,visible_ms,ended,end_reason,author_hash,duration_band,image_band,is_ad) VALUES (?,?,?,?,?,?,?,0,0,'',?,?,?,?)",
                                new Object[]{visitToken, workHash, typeKey, local.toLocalDate().toString(), local.getHour(), source, timestamp, segmentAuthorHash, durationBand(durationMillis), imageBand(imageCount), advertisement == null ? null : advertisement ? 1 : 0});
                        for (String topic : uniqueTopics) db.execSQL("INSERT OR IGNORE INTO visit_topics(token,topic) VALUES (?,?)", new Object[]{visitToken, topic});
                        markAvailable(db, "metadata", timestamp);
                        markAvailable(db, "visits", timestamp);
                        trimVisitsPending = true;
                    }
                    if (!countView) return;
                    addDaily(db, local.toLocalDate(), 1, 0, Boolean.TRUE.equals(advertisement) ? 1 : 0,
                            Math.max(0, durationMillis));
                    String day = local.toLocalDate().toString();
                    db.execSQL("INSERT OR IGNORE INTO work_days(day, work_hash) VALUES (?, ?)",
                            new Object[]{day, workHash});
                    db.execSQL("INSERT OR IGNORE INTO hours(day, hour, count) VALUES (?, ?, 0)",
                            new Object[]{day, local.getHour()});
                    db.execSQL("UPDATE hours SET count=count+1 WHERE day=? AND hour=?",
                            new Object[]{day, local.getHour()});
                    addRank(db, day, "type", typeKey, typeLabel(typeKey));
                    addRank(db, day, "author", authorKey, finalAuthorLabel);
                    for (String topic : uniqueTopics) addRank(db, day, "topic", topic, topic);
                    addRecent(db, timestamp, typeLabel(typeKey) + " · " + finalAuthorLabel, "view");
                });
                if (queued) {
                    lastWorkHash = workHash;
                    lastWorkDay = local.toLocalDate();
                    if (visitToken != null) currentVisit = new Visit(visitToken, workHash, token, timestamp);
                    return visitToken;
                }
            }
        } catch (Exception ignored) { DouAiLog.failure("local_analytics", "host_metadata", ignored); }
        return null;
    }

    /** cumulativeVisibleMillis comes only from bounded foreground visibility ticks. */
    public void pulseVisit(String token, long cumulativeVisibleMillis) { updateVisit(token, cumulativeVisibleMillis, null); }

    public void finishVisit(String token, long visibleMillis, String endReason) { updateVisit(token, visibleMillis, clean(endReason, 80)); }

    private void updateVisit(String token, long cumulativeVisibleMillis, String endReason) {
        if (token == null) return;
        synchronized (stateLock) {
            Visit visit = currentVisit;
            if (!isEnabled() || visit == null || !visit.token.equals(token) || visit.generation != generation) return;
            long observed = Math.max(0, Math.min(MAX_VISIBLE_MILLIS,
                    Math.min(cumulativeVisibleMillis, Math.max(0, clock.millis() - visit.started))));
            visit.visibleMillis = Math.max(visit.visibleMillis, observed);
            if (endReason == null && visit.visibleMillis - visit.persistedMillis < 3_000) return;
            long total = visit.visibleMillis;
            boolean queued = enqueueWrite(visit.generation, Instant.ofEpochMilli(visit.started).atZone(zone.get()).toLocalDate(), "dwell", db -> db.execSQL(
                    "UPDATE visits SET visible_ms=MAX(visible_ms,?),ended=?,end_reason=? WHERE token=?",
                    new Object[]{total, endReason == null ? 0 : 1, endReason == null ? "" : endReason, token}));
            if (queued) visit.persistedMillis = total;
            if (endReason != null) currentVisit = null;
        }
    }

    public String currentVisitToken(String workId) {
        try {
            if (workId == null) return null;
            String workHash = hash(workId.trim());
            synchronized (stateLock) {
                return isEnabled() && currentVisit != null && currentVisit.generation == generation
                        && currentVisit.workHash.equals(workHash) ? currentVisit.token : null;
            }
        } catch (Exception ignored) { return null; }
    }

    public void recordAction(long expectedGeneration, String workId, String key, String label, String source, String stage) {
        recordAction(expectedGeneration, currentVisitToken(workId), workId, key, label, source, stage);
    }

    public void recordAction(long expectedGeneration, String expectedVisit, String workId, String key,
            String label, String source, String stage) {
        String featureKey = clean(key, 100), featureLabel = clean(label, 120);
        if (featureKey.isEmpty() || featureLabel.isEmpty()) return;
        String actionSource = normalizeSource(source), actionStage = normalizeStage(stage);
        synchronized (stateLock) {
            if (!isEnabled() || generation != expectedGeneration) return;
            String linked = currentVisitToken(workId);
            final String visitToken = expectedVisit != null && expectedVisit.equals(linked) ? linked : null;
            long timestamp = clock.millis();
            LocalDate day = Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
            LocalDate sampleDay = visitToken == null ? day : Instant.ofEpochMilli(currentVisit.started).atZone(zone.get()).toLocalDate();
            boolean linkedMetric = visitToken != null && "manual".equals(actionSource) &&
                    (("save_media".equals(featureKey) && "entry".equals(actionStage)) || ("copy_text".equals(featureKey) && "success".equals(actionStage)));
            enqueueWrite(expectedGeneration, sampleDay, linkedMetric ? "events" : "summary", db -> {
                addFeature(db, day, timestamp, featureKey, featureLabel, actionSource);
                db.execSQL("INSERT INTO actions(day,token,feature,source,stage,ts) VALUES (?,?,?,?,?,?)",
                        new Object[]{day.toString(), visitToken, featureKey, actionSource, actionStage, timestamp});
                markAvailable(db, "events", timestamp);
                trimActionsPending = true;
            });
        }
    }

    void recordFeature(String key, String label) {
        if (!isEnabled()) return;
        String featureKey = clean(key, 100);
        String featureLabel = clean(label, 120);
        if (featureKey.isEmpty() || featureLabel.isEmpty()) return;
        synchronized (stateLock) {
            if (!isEnabled()) return;
            long token = generation;
            long timestamp = clock.millis();
            LocalDate day = Instant.ofEpochMilli(timestamp).atZone(zone.get()).toLocalDate();
            enqueueWrite(token, day, db -> {
                addFeature(db, day, timestamp, featureKey, featureLabel, featureSource(featureKey));
            });
        }
    }

    private void addFeature(SQLiteDatabase db, LocalDate day, long timestamp, String key, String label, String source) {
        addDaily(db, day, 0, 1, 0, 0);
        addRank(db, day.toString(), "feature", key, label);
        addRank(db, day.toString(), "feature_source", source, sourceLabel(source));
        addRecent(db, timestamp, label, "feature");
    }

    /** Off-main only. Waits for previously queued writes. days=0 selects retained history. */
    public AnalyticsSnapshot snapshot(int days) {
        DouAiLog.Operation operation = DouAiLog.begin("local_analytics_report", "snapshot");
        if (closed) { operation.finish(DouAiLog.Outcome.SKIPPED, "store_closed", "empty=true"); return AnalyticsSnapshot.empty(); }
        FutureTask<AnalyticsSnapshot> task = new FutureTask<>(() -> readSnapshot(days));
        if (!enqueue(task)) {
            try {
                AnalyticsSnapshot result = readSnapshot(days);
                operation.finish(DouAiLog.Outcome.SUCCESS, "snapshot_read_synchronously", "days=" + days + "; queue_full=true; views=" + result.totalViews);
                return result;
            } catch (RuntimeException failure) { operation.error("snapshot_read", failure);
                operation.finish(DouAiLog.Outcome.FAILED, "snapshot_read_failed", "days=" + days); throw failure; }
        }
        try {
            AnalyticsSnapshot result = task.get();
            operation.finish(DouAiLog.Outcome.SUCCESS, "snapshot_read", "days=" + days + "; views=" + result.totalViews
                    + "; features=" + result.totalFeatureUses + "; queue_dropped=" + queueDropped.get());
            return result;
        }
        catch (InterruptedException ignored) {
            operation.finish(DouAiLog.Outcome.CANCELLED, "reader_interrupted", "empty=true");
            Thread.currentThread().interrupt();
            return AnalyticsSnapshot.empty();
        } catch (Exception ignored) {
            operation.error("snapshot_read", ignored);
            operation.finish(DouAiLog.Outcome.FAILED, "snapshot_read_failed", "days=" + days);
            throw new IllegalStateException("无法读取本地统计数据");
        }
    }

    /** Worker only. A logical snapshot, including settled queued writes; never copies SQLite files. */
    org.json.JSONObject exportBackup() {
        if (closed) throw new IllegalStateException("本地分析存储已关闭");
        FutureTask<org.json.JSONObject> task = new FutureTask<>(() -> {
            synchronized (databaseLock) {
                if (closed) throw new IllegalStateException("本地分析存储已关闭");
                SQLiteDatabase db = database == null && !new File(directory, "usage.db").exists()
                        ? null : openDatabase();
                if (db == null) return AnalyticsBackup.capture(null, clock.millis(), 0);
                flushPending(db);
                db.beginTransaction();
                try {
                    prune(db, today());
                    org.json.JSONObject snapshot = AnalyticsBackup.capture(db, clock.millis(), 0);
                    db.setTransactionSuccessful(); return snapshot;
                } finally { db.endTransaction(); }
            }
        });
        if (!enqueue(task)) throw new IllegalStateException("本地分析写入繁忙，请稍后重试备份");
        try { return task.get(); }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("本地分析备份已中断", failure);
        } catch (java.util.concurrent.ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw new IllegalStateException("无法备份本地分析数据", cause);
        }
    }

    /** All imported rows were validated against fixed columns before this transaction starts. */
    void replaceBackup(AnalyticsBackup.Snapshot snapshot, Runnable beforeCommit) {
        fenceBackup();
        synchronized (databaseLock) {
            boolean committed = false;
            try {
                if (closed) throw new IllegalStateException("本地分析存储已关闭");
                SQLiteDatabase db = openDatabase();
                flushPending(db);
                db.beginTransaction();
                try {
                    AnalyticsBackup.replaceTables(db, snapshot);
                    lastPrunedDay = null;
                    prune(db, today());
                    beforeCommit.run();
                    synchronized (stateLock) {
                        // The short continuation boundary also fences observations collected while
                        // replacement was being prepared. Later switch changes retain this epoch.
                        fenceBackup();
                        resetPending();
                        long restoredAt = clock.millis();
                        db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,0)",
                                new Object[]{Math.min(snapshot.capturedAt, restoredAt)});
                        db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,?)",
                                new Object[]{restoredAt, enabled ? 1 : 0});
                        db.setTransactionSuccessful();
                    }
                } finally { db.endTransaction(); }
                committed = true;
            } finally {
                // Reject every observation accepted before or while replacement was in progress.
                // The local collection flag is deliberately neither read from nor changed by a backup.
                if (!committed) fenceBackup();
                lastPrunedDay = null;
            }
        }
    }

    private void fenceBackup() {
        synchronized (stateLock) {
            generation++; storageEpoch++;
            lastWorkHash = null; lastWorkDay = null; currentVisit = null;
            cancelAutomaticAdvance();
        }
    }

    /** Off-main only. Returns after deletion; stale queued events cannot repopulate the database. */
    public void clear() {
        DouAiLog.Operation operation = DouAiLog.begin("local_analytics_clear", "user_clear");
        synchronized (stateLock) {
            generation++;
            storageEpoch++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            resetPending();
            cancelAutomaticAdvance();
        }
        synchronized (databaseLock) {
            try {
                File databaseFile = new File(directory, "usage.db");
                File ownedDirectory = directory.getCanonicalFile();
                for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
                    File ownedFile = new File(directory, "usage.db" + suffix);
                    if (!ownedDirectory.equals(ownedFile.getCanonicalFile().getParentFile()))
                        throw new IllegalStateException("Analytics database path is outside local storage");
                }
                if (database != null) {
                    database.close();
                    database = null;
                }
                // Explicit user clearing must also recover a corrupt database. Do not open it:
                // delete only this database and SQLite sidecars, preserving the opt-in flag.
                SQLiteDatabase.deleteDatabase(databaseFile);
                for (String suffix : new String[]{"", "-journal", "-wal", "-shm"}) {
                    if (new File(directory, "usage.db" + suffix).exists())
                        throw new IllegalStateException("Analytics database could not be removed");
                }
                lastPrunedDay = null;
                operation.finish(DouAiLog.Outcome.SUCCESS, "database_and_sidecars_absent", "existence_verified=true; enabled_flag_preserved=true");
            } catch (Exception ignored) {
                operation.error("database_delete", ignored);
                operation.finish(DouAiLog.Outcome.FAILED, "clear_failed", "collection=disabled");
                failClosed();
                throw new IllegalStateException("无法清除本地统计数据");
            } finally {
                // Observations accepted while deletion waited for the DB belong to the clear
                // boundary as well. Fence them and reset their consecutive-view suppression.
                synchronized (stateLock) {
                    generation++;
                    storageEpoch++;
                    lastWorkHash = null;
                    lastWorkDay = null;
                    currentVisit = null;
                    resetPending();
                }
            }
        }
    }

    @Override public void close() {
        synchronized (stateLock) {
            closed = true;
            enabled = false;
            generation++;
            storageEpoch++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
        }
        writer.shutdown();
        synchronized (databaseLock) {
            if (database != null) {
                database.close();
                database = null;
            }
        }
        if (active == this) active = null;
    }

    private boolean enqueue(Runnable runnable) {
        DouAiLog.Operation parent = DouAiLog.current();
        try { writer.execute(runnable instanceof WriteTask || parent == null ? runnable : parent.wrap(runnable)); return true; }
        catch (RejectedExecutionException ignored) {

            DouAiLog.event("local_analytics", "write_dropped", closed ? "store_closed" : "queue_full",
                    "dropped_total=" + queueDropped.get() + "; capacity=" + QUEUE_CAPACITY);
            return false;
        }
    }

    private interface Mutation { void run(SQLiteDatabase db); }

    private boolean enqueueWrite(long token, LocalDate day, Mutation mutation) {
        return enqueueWrite(token, day, "summary", mutation);
    }

    private boolean enqueueWrite(long token, LocalDate day, String source, Mutation mutation) {
        if (enqueue(new WriteTask(token, day, mutation, DouAiLog.current()))) return true;
        synchronized (stateLock) {
            if (!closed && generation == token) {
                queueDropped.incrementAndGet();
                pendingGaps.computeIfAbsent(day, k -> new HashSet<>()).add(source);
            }
        }
        return false;
    }

    long writeTransactionCount() { return writeTransactions.get(); }

    private final class WriteTask implements Runnable {
        final long token;
        final LocalDate day;
        final Mutation mutation;
        final DouAiLog.Operation parent;
        DouAiLog.Operation operation;
        boolean skipped;
        WriteTask(long token, LocalDate day, Mutation mutation, DouAiLog.Operation parent) {
            this.token = token; this.day = day; this.mutation = mutation; this.parent = parent;
        }
        void inContext(Runnable action) {
            if (parent == null) action.run(); else parent.wrap(action).run();
        }
        @Override public void run() {
            List<WriteTask> batch = new ArrayList<>(); batch.add(this);
            while (batch.size() < 16 && writer.getQueue().peek() instanceof WriteTask) {
                batch.add((WriteTask) writer.getQueue().poll());
            }
            writeBatch(batch);
        }
    }

    /** Only adjacent writes are drained; queued reads and backup tasks remain ordering barriers. */
    private void writeBatch(List<WriteTask> batch) {
        synchronized (databaseLock) {
            List<WriteTask> accepted = new ArrayList<>();
            LocalDate today = today();
            for (WriteTask task : batch) {
                task.inContext(() -> task.operation = DouAiLog.begin("local_analytics_write", "queued_mutation"));
                if (closed || !enabled || generation != task.token || task.day.isBefore(today.minusDays(RETENTION_DAYS - 1L))) {
                    task.operation.finish(DouAiLog.Outcome.SKIPPED, closed ? "store_closed" : !enabled ? "collection_disabled"
                            : generation != task.token ? "stale_generation" : "outside_retention", "transaction_started=false");
                } else accepted.add(task);
            }
            if (accepted.isEmpty()) {
                synchronized (stateLock) { if (closed || (pendingBoundaries.isEmpty() && queueDropped.get()==0)) return; }
                try { flushPending(openDatabase()); } catch (Exception failure) { failClosed(); }
                return;
            }
            try {
                SQLiteDatabase db = openDatabase();
                flushPending(db);
                db.beginTransaction();
                trimVisitsPending = false; trimActionsPending = false;
                try {
                    prune(db, today);
                    // clear() stays file-free; the first accepted normal write establishes its new origin.
                    db.execSQL("INSERT INTO collection_boundaries(ts,enabled) SELECT ?,1 WHERE NOT EXISTS (SELECT 1 FROM collection_boundaries)",new Object[]{clock.millis()});
                    for (WriteTask task : accepted) {
                        if (closed || !enabled || generation != task.token) {
                            task.skipped = true;
                            task.operation.finish(DouAiLog.Outcome.SKIPPED, closed ? "store_closed"
                                    : !enabled ? "collection_disabled" : "stale_generation", "mutation_started=false");
                            continue;
                        }
                        task.inContext(() -> task.mutation.run(db));
                    }
                    if (trimVisitsPending) trimVisits(db);
                    if (trimActionsPending) trimEvents(db);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); trimVisitsPending = false; trimActionsPending = false; }
                writeTransactions.incrementAndGet();
                for (WriteTask task : accepted) if (!task.skipped) task.operation.finish(DouAiLog.Outcome.SUCCESS,
                        "sqlite_transaction_committed", "transaction_ended=true; read_back_verified=false;batch_size=" + accepted.size());
            } catch (Exception failure) {
                for (WriteTask task : accepted) {
                    if (task.skipped) continue;
                    task.operation.error("sqlite_write", failure);
                    task.operation.finish(DouAiLog.Outcome.FAILED, "storage_failed_collection_disabled", "read_back_verified=false");
                }
                lastPrunedDay = null; failClosed();
            }
        }
    }

    private SQLiteDatabase openDatabase() {
        if (database != null && database.isOpen()) return database;
        if (!directory.isDirectory() && !directory.mkdirs())
            throw new IllegalStateException("Local analytics directory unavailable");
        // Android's default corruption handler deletes the DB and may reopen an empty one.
        // Preserve failed storage and surface the error instead of silently losing a report.
        SQLiteDatabase opened = SQLiteDatabase.openDatabase(new File(directory, "usage.db").getPath(),
                null, SQLiteDatabase.CREATE_IF_NECESSARY,
                corrupt -> { throw new IllegalStateException("Local analytics database corrupt"); });
        try {
            try (Cursor secureDelete = opened.rawQuery("PRAGMA secure_delete=ON", null)) {
                if (!secureDelete.moveToFirst() || secureDelete.getInt(0) != 1)
                    throw new IllegalStateException("Local storage cannot securely clear records");
            }
            if (opened.getVersion() > 4) throw new IllegalStateException("Unsupported analytics schema");
            opened.beginTransaction();
            try {
            opened.execSQL("CREATE TABLE IF NOT EXISTS daily(day TEXT PRIMARY KEY, views INTEGER NOT NULL, features INTEGER NOT NULL, ads INTEGER NOT NULL, content_duration INTEGER NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS hours(day TEXT NOT NULL, hour INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day,hour))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS work_days(day TEXT NOT NULL, work_hash TEXT NOT NULL, PRIMARY KEY(day,work_hash))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS ranks(day TEXT NOT NULL, kind TEXT NOT NULL, item_key TEXT NOT NULL, label TEXT NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(day,kind,item_key))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS recent(id INTEGER PRIMARY KEY AUTOINCREMENT, ts INTEGER NOT NULL, label TEXT NOT NULL, kind TEXT NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS meta(key TEXT PRIMARY KEY, value INTEGER NOT NULL)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS visits(token TEXT PRIMARY KEY,work_hash TEXT NOT NULL,type TEXT NOT NULL,day TEXT NOT NULL,hour INTEGER NOT NULL,source TEXT NOT NULL,started INTEGER NOT NULL,visible_ms INTEGER NOT NULL,ended INTEGER NOT NULL,end_reason TEXT NOT NULL)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS visit_day ON visits(day)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS visit_started ON visits(started DESC)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS visit_topics(token TEXT NOT NULL,topic TEXT NOT NULL,PRIMARY KEY(token,topic))");
            opened.execSQL("CREATE TABLE IF NOT EXISTS actions(id INTEGER PRIMARY KEY AUTOINCREMENT,day TEXT NOT NULL,token TEXT,feature TEXT NOT NULL,source TEXT NOT NULL,stage TEXT NOT NULL,ts INTEGER NOT NULL)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS work_history ON work_days(work_hash,day)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS ranks_kind_day ON ranks(kind,day,item_key,label,count)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS action_token ON actions(token,source,feature,stage)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS action_day ON actions(day)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS collection_boundaries(id INTEGER PRIMARY KEY AUTOINCREMENT,ts INTEGER NOT NULL,enabled INTEGER NOT NULL)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS boundary_time ON collection_boundaries(ts,id,enabled)");
            if (opened.getVersion() < 3) {
                opened.execSQL("ALTER TABLE visits ADD COLUMN author_hash TEXT");
                opened.execSQL("ALTER TABLE visits ADD COLUMN duration_band TEXT");
                opened.execSQL("ALTER TABLE visits ADD COLUMN image_band TEXT");
                opened.execSQL("ALTER TABLE visits ADD COLUMN is_ad INTEGER");
            }
            opened.execSQL("CREATE TABLE IF NOT EXISTS flow_events(id INTEGER PRIMARY KEY AUTOINCREMENT,operation_id TEXT NOT NULL,token TEXT,kind TEXT NOT NULL,stage TEXT NOT NULL,task INTEGER NOT NULL,day TEXT NOT NULL,ts INTEGER NOT NULL,UNIQUE(operation_id,stage,task))");
            opened.execSQL("CREATE INDEX IF NOT EXISTS flow_operation ON flow_events(operation_id,ts)");
            opened.execSQL("CREATE INDEX IF NOT EXISTS flow_day ON flow_events(day)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS source_coverage(source TEXT PRIMARY KEY,status TEXT NOT NULL,since INTEGER NOT NULL,trimmed_before INTEGER)");
            opened.execSQL("CREATE TABLE IF NOT EXISTS queue_gaps(day TEXT NOT NULL,source TEXT NOT NULL,PRIMARY KEY(day,source))");
            if (opened.getVersion() < 4) {
                opened.execSQL("ALTER TABLE flow_events ADD COLUMN seq INTEGER");
                markLegacyDrops(opened, clock.millis());
            }
            opened.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS flow_seq ON flow_events(operation_id,seq)");
            opened.setVersion(4);
            opened.setTransactionSuccessful();
            } finally { opened.endTransaction(); }
            database = opened;
            return opened;
        } catch (RuntimeException failure) { opened.close(); throw failure; }
    }

    // Old counters have no dates. Their entire prior window remains uncertain.
    static void markLegacyDrops(SQLiteDatabase db, long through) {
        db.execSQL("INSERT OR REPLACE INTO meta(key,value) SELECT 'legacy_drop_through',? WHERE EXISTS (SELECT 1 FROM meta WHERE key='queue_dropped' AND value>0)",new Object[]{through});
    }
    private static void markAvailable(SQLiteDatabase db, String source, long observedAt) {
        db.execSQL("INSERT OR IGNORE INTO source_coverage VALUES (?,'available',?,NULL)",new Object[]{source,observedAt});
        db.execSQL("UPDATE source_coverage SET status='available',since=? WHERE source=? AND status='unknown'",new Object[]{observedAt,source});
    }

    private void addDaily(SQLiteDatabase db, LocalDate day, long views, long features, long ads, long duration) {
        db.execSQL("INSERT OR IGNORE INTO daily VALUES (?,0,0,0,0)", new Object[]{day.toString()});
        db.execSQL("UPDATE daily SET views=views+?,features=features+?,ads=ads+?,content_duration=content_duration+? WHERE day=?",
                new Object[]{views, features, ads, duration, day.toString()});
    }

    private void addRank(SQLiteDatabase db, String day, String kind, String key, String label) {
        db.execSQL("INSERT OR IGNORE INTO ranks VALUES (?,?,?,?,0)", new Object[]{day, kind, key, label});
        db.execSQL("UPDATE ranks SET count=count+1,label=? WHERE day=? AND kind=? AND item_key=?",
                new Object[]{label, day, kind, key});
    }

    private void addRecent(SQLiteDatabase db, long timestamp, String label, String kind) {
        db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES ('first_seen',?)", new Object[]{timestamp});
        db.execSQL("INSERT INTO recent(ts,label,kind) VALUES (?,?,?)", new Object[]{timestamp, label, kind});
        db.execSQL("DELETE FROM recent WHERE id NOT IN (SELECT id FROM recent ORDER BY id DESC LIMIT 30)");
    }

    private void prune(SQLiteDatabase db, LocalDate today) {
        if (today.equals(lastPrunedDay)) return;
        String cutoff = today.minusDays(RETENTION_DAYS - 1L).toString();
        for (String table : new String[]{"daily", "hours", "work_days", "ranks", "queue_gaps"})
            db.delete(table, "day < ?", new String[]{cutoff});
        long cutoffTime = LocalDate.parse(cutoff).atStartOfDay(zone.get()).toInstant().toEpochMilli();
        db.delete("recent", "ts < ?", new String[]{Long.toString(cutoffTime)});
        db.delete("collection_boundaries", "ts < ?", new String[]{Long.toString(cutoffTime)});
        String visitCutoff = today.minusDays(VISIT_RETENTION_DAYS - 1L).toString();
        db.delete("visits", "day < ?", new String[]{visitCutoff});
        db.delete("actions", "day < ?", new String[]{visitCutoff});
        db.delete("flow_events", "day < ?", new String[]{visitCutoff});
        db.execSQL("DELETE FROM visit_topics WHERE token NOT IN (SELECT token FROM visits)");
        lastPrunedDay = today;
    }

    static String durationBand(long duration) {
        return duration <= 0 ? null : duration < 15000 ? "under_15s" : duration < 60000 ? "15_59s" : duration < 300000 ? "1_5m" : "5m_plus";
    }
    static String imageBand(Integer count) {
        return count == null || count <= 0 ? null : count == 1 ? "1" : count <= 5 ? "2_5" : count <= 10 ? "6_10" : "11_plus";
    }

    private static void trimEvents(SQLiteDatabase db) {
        long count = scalar(db,"SELECT (SELECT COUNT(*) FROM actions)+(SELECT COUNT(*) FROM flow_events)",null);
        if (count <= 30000) return;
        long excess=count-30000;
        db.execSQL("CREATE TEMP TABLE IF NOT EXISTS discarded_events(kind INTEGER,id INTEGER,ts INTEGER,PRIMARY KEY(kind,id))");
        db.execSQL("DELETE FROM discarded_events");
        db.execSQL("INSERT INTO discarded_events SELECT kind,id,ts FROM (SELECT 0 AS kind,id,ts FROM actions UNION ALL SELECT 1 AS kind,id,ts FROM flow_events) ORDER BY ts,kind,id LIMIT " + excess);
        db.execSQL("INSERT OR IGNORE INTO source_coverage VALUES ('events','unknown',0,NULL)");
        db.execSQL("UPDATE source_coverage SET trimmed_before=MAX(COALESCE(trimmed_before,0),(SELECT MAX(ts) FROM discarded_events)) WHERE source='events'");
        db.execSQL("DELETE FROM actions WHERE id IN (SELECT id FROM discarded_events WHERE kind=0)");
        db.execSQL("DELETE FROM flow_events WHERE id IN (SELECT id FROM discarded_events WHERE kind=1)");
        db.execSQL("DELETE FROM discarded_events");
    }

    private static void trimVisits(SQLiteDatabase db) {
        db.execSQL("INSERT OR IGNORE INTO source_coverage VALUES ('visits','unknown',0,NULL)");
        db.execSQL("UPDATE source_coverage SET trimmed_before=MAX(COALESCE(trimmed_before,0),COALESCE((SELECT MAX(started) FROM visits WHERE token NOT IN (SELECT token FROM visits ORDER BY started DESC,rowid DESC LIMIT " + MAX_VISITS + ")),0)) WHERE source='visits' AND EXISTS (SELECT 1 FROM visits WHERE token NOT IN (SELECT token FROM visits ORDER BY started DESC,rowid DESC LIMIT " + MAX_VISITS + "))");
        db.execSQL("DELETE FROM visits WHERE token NOT IN (SELECT token FROM visits ORDER BY started DESC,rowid DESC LIMIT " + MAX_VISITS + ")");
        db.execSQL("DELETE FROM visit_topics WHERE token NOT IN (SELECT token FROM visits)");
    }

    private void resetPending() {
        pendingGaps.clear();
        pendingBoundaries.clear();
        queueDropped.set(0);
    }

    /** Called with databaseLock. Readers and exports see loss even if no later mutation succeeded. */
    private void flushPending(SQLiteDatabase db) {
        Map<LocalDate,Set<String>> gaps;
        List<long[]> boundaries;
        long count, epoch;
        synchronized (stateLock) {
            if (pendingBoundaries.isEmpty() && queueDropped.get()==0) return;
            gaps=pendingGaps; pendingGaps=new HashMap<>();
            boundaries=pendingBoundaries; pendingBoundaries=new ArrayList<>();
            count=queueDropped.getAndSet(0); epoch=storageEpoch;
        }
        try {
            db.beginTransaction();
            try {
                for (long[] boundary : boundaries) {
                    db.execSQL("INSERT OR IGNORE INTO meta(key,value) VALUES ('collection_start',?)",new Object[]{boundary[0]});
                    db.execSQL("INSERT INTO collection_boundaries(ts,enabled) VALUES (?,?)",new Object[]{boundary[0],boundary[1]});
                }
                if (count>0) {
                    db.execSQL("INSERT OR IGNORE INTO meta VALUES ('queue_dropped',0)");
                    db.execSQL("UPDATE meta SET value=value+? WHERE key='queue_dropped'",new Object[]{count});
                    LocalDate cutoff=today().minusDays(RETENTION_DAYS-1L);
                    for (var day:gaps.entrySet()) if (!day.getKey().isBefore(cutoff)) for (String source:day.getValue())
                        db.execSQL("INSERT OR IGNORE INTO queue_gaps VALUES (?,?)",new Object[]{day.getKey().toString(),source});
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                if (storageEpoch==epoch) {
                    queueDropped.addAndGet(count);
                    for (var day:gaps.entrySet()) pendingGaps.computeIfAbsent(day.getKey(), k->new HashSet<>()).addAll(day.getValue());
                    pendingBoundaries.addAll(0,boundaries);
                }
            }
            throw failure;
        }
    }

    private AnalyticsSnapshot readSnapshot(int days) {
        synchronized (databaseLock) {
            if (closed || (database == null && !new File(directory, "usage.db").exists()))
                return AnalyticsSnapshot.empty();
            try {
                SQLiteDatabase db = openDatabase();
                flushPending(db);
                LocalDate end = today();
                prune(db, end);
                LocalDate start = end.minusDays(days <= 0 ? RETENTION_DAYS - 1L
                        : Math.min(days, RETENTION_DAYS) - 1L);
                String[] range = {start.toString(), end.toString()};
                long views = 0, features = 0, ads = 0;
                Set<LocalDate> activeDates = new HashSet<>();
                Map<LocalDate, Long> dailyViews = new HashMap<>();
                try (Cursor cursor = db.rawQuery("SELECT day,views,features,ads FROM daily WHERE day BETWEEN ? AND ? ORDER BY day", range)) {
                    while (cursor.moveToNext()) {
                        LocalDate date = LocalDate.parse(cursor.getString(0));
                        activeDates.add(date);
                        dailyViews.put(date, cursor.getLong(1));
                        views += cursor.getLong(1);
                        features += cursor.getLong(2);
                        ads += cursor.getLong(3);
                    }
                }
                // For long windows group directly in work order; short windows keep the day index.
                String workSource = ChronoUnit.DAYS.between(start, end) >= 90
                        ? "work_days INDEXED BY work_history" : "work_days";
                long unique, repeated;
                try (Cursor c = db.rawQuery("SELECT COUNT(*),COALESCE(SUM(n>1),0) FROM (SELECT COUNT(*) AS n FROM "
                        + workSource + " WHERE day BETWEEN ? AND ? GROUP BY work_hash)", range)) {
                    c.moveToFirst(); unique = c.getLong(0); repeated = c.getLong(1);
                }
                long first = scalar(db, "SELECT value FROM meta WHERE key='first_seen'", null);
                long[] hours = new long[24];
                try (Cursor cursor = db.rawQuery("SELECT hour,SUM(count) FROM hours WHERE day BETWEEN ? AND ? GROUP BY hour", range)) {
                    while (cursor.moveToNext()) {
                        int hour = cursor.getInt(0);
                        if (hour >= 0 && hour < hours.length) hours[hour] = cursor.getLong(1);
                    }
                }
                List<AnalyticsSnapshot.Recent> recent = new ArrayList<>();
                long from = start.atStartOfDay(zone.get()).toInstant().toEpochMilli();
                long until = end.plusDays(1).atStartOfDay(zone.get()).toInstant().toEpochMilli();
                try (Cursor cursor = db.rawQuery("SELECT ts,label,kind FROM recent WHERE ts>=? AND ts<? ORDER BY ts DESC,id DESC LIMIT 30",
                        new String[]{Long.toString(from), Long.toString(until)})) {
                    while (cursor.moveToNext()) recent.add(new AnalyticsSnapshot.Recent(cursor.getLong(0), cursor.getString(1), cursor.getString(2)));
                }
                int streak = 0;
                LocalDate streakDay = activeDates.contains(end) ? end : end.minusDays(1);
                while (activeDates.contains(streakDay)) { streak++; streakDay = streakDay.minusDays(1); }
                if (days <= 0) {
                    try (Cursor c = db.rawQuery("SELECT MIN(day) FROM (SELECT MIN(day) day FROM daily WHERE day BETWEEN ?1 AND ?2 UNION ALL SELECT MIN(day) FROM flow_events WHERE day BETWEEN ?1 AND ?2 UNION ALL SELECT MIN(day) FROM visits WHERE day BETWEEN ?1 AND ?2 UNION ALL SELECT MIN(day) FROM actions WHERE day BETWEEN ?1 AND ?2)", range)) {
                        if (c.moveToFirst() && !c.isNull(0)) start = LocalDate.parse(c.getString(0));
                    }
                }
                return new AnalyticsSnapshot(views, features, unique, ads, activeDates.size(), streak, first,
                        trend(start, end, dailyViews), ranks(db, range, "type"), ranks(db, range, "author"),
                        ranks(db, range, "topic"), ranks(db, range, "feature"), hours, recent,
                        readPortrait(db, start, end, views, features, unique, repeated, first));
            } catch (Exception ignored) {
                failClosed();
                throw new IllegalStateException("无法读取本地统计数据");
            }
        }
    }

    private PortraitSnapshot readPortrait(SQLiteDatabase db, LocalDate start, LocalDate end,
            long views, long features, long unique, long repeated, long firstSeen) {
        String[] range = {start.toString(), end.toString()};
        List<AnalyticsSnapshot.Rank> allAuthors = allRanks(db, range, "author");
        allAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        List<AnalyticsSnapshot.Rank> allTopics = allRanks(db, range, "topic");
        List<AnalyticsSnapshot.Rank> allTypes = allRanks(db, range, "type");
        List<AnalyticsSnapshot.Rank> sources = allRanks(db, range, "feature_source");
        long legacySources = features - PortraitMetrics.total(sources);
        if (legacySources > 0) {
            List<AnalyticsSnapshot.Rank> merged = new ArrayList<>();
            boolean added = false;
            for (AnalyticsSnapshot.Rank rank : sources) {
                if (rank.label.equals("来源未知")) { merged.add(new AnalyticsSnapshot.Rank(rank.label, rank.count + legacySources)); added = true; }
                else merged.add(rank);
            }
            if (!added) merged.add(new AnalyticsSnapshot.Rank("来源未知", legacySources));
            sources = merged;
        }
        Map<String, long[]> totals = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,views,features FROM daily WHERE day BETWEEN ? AND ?", range)) {
            while (cursor.moveToNext()) totals.put(cursor.getString(0), new long[]{cursor.getLong(1), cursor.getLong(2)});
        }
        Map<String, Long> dayUnique = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,COUNT(*) FROM work_days WHERE day BETWEEN ? AND ? GROUP BY day", range)) {
            while (cursor.moveToNext()) dayUnique.put(cursor.getString(0), cursor.getLong(1));
        }
        Map<String, Map<String, Long>> dayTypes = new HashMap<>();
        try (Cursor cursor = db.rawQuery("SELECT day,label,SUM(count) FROM ranks WHERE day BETWEEN ? AND ? AND kind='type' GROUP BY day,item_key ORDER BY item_key", range)) {
            while (cursor.moveToNext()) dayTypes.computeIfAbsent(cursor.getString(0), ignored -> new LinkedHashMap<>()).put(cursor.getString(1), cursor.getLong(2));
        }
        List<PortraitSnapshot.Day> days = new ArrayList<>();
        int browseDays = 0;
        for (LocalDate day = start; !day.isAfter(end); day = day.plusDays(1)) {
            String date = day.toString();
            long[] total = totals.getOrDefault(date, new long[2]);
            if (total[0] > 0) browseDays++;
            days.add(new PortraitSnapshot.Day(date, total[0], total[1], dayUnique.getOrDefault(date, 0L),
                    dayTypes.getOrDefault(date, Collections.emptyMap())));
        }
        long[][] weekdayHours = new long[7][24];
        long night = 0, weekend = 0;
        try (Cursor cursor = db.rawQuery("SELECT day,hour,count FROM hours WHERE day BETWEEN ? AND ?", range)) {
            while (cursor.moveToNext()) {
                int weekday = LocalDate.parse(cursor.getString(0)).getDayOfWeek().getValue() - 1;
                int hour = cursor.getInt(1);
                if (hour < 0 || hour >= 24) continue;
                long count = cursor.getLong(2);
                weekdayHours[weekday][hour] += count;
                if (hour < 6) night += count;
                if (weekday >= 5) weekend += count;
            }
        }
        long captured = scalar(db, "SELECT COUNT(*) FROM visits WHERE day BETWEEN ? AND ?", range);
        long finished = scalar(db, "SELECT COUNT(*) FROM visits WHERE day BETWEEN ? AND ? AND ended=1", range);
        long visible = scalar(db, "SELECT COALESCE(SUM(visible_ms),0) FROM visits WHERE day BETWEEN ? AND ?", range);
        long dropped = scalar(db, "SELECT value FROM meta WHERE key='queue_dropped'", null);
        long collectionStart = scalar(db, "SELECT value FROM meta WHERE key='collection_start'", null);
        long earliest = firstSeen > 0 && collectionStart > 0 ? Math.min(firstSeen, collectionStart) : Math.max(firstSeen, collectionStart);
        PortraitSnapshot.Comparison comparison = comparison(db, start, end, earliest);
        PortraitSnapshot.Comparison week = comparison(db, end.minusDays(6), end, earliest);
        PortraitSnapshot.Comparison month = comparison(db, end.minusDays(29), end, earliest);
        List<PortraitSnapshot.Joint> topicHours = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT v.hour,t.topic,COUNT(*) AS n FROM visits v JOIN visit_topics t ON v.token=t.token WHERE v.day BETWEEN ? AND ? GROUP BY v.hour,t.topic ORDER BY n DESC,t.topic ASC,v.hour ASC LIMIT 48", range)) {
            while (cursor.moveToNext()) topicHours.add(new PortraitSnapshot.Joint(cursor.getInt(0) + "时", cursor.getString(1), cursor.getLong(2)));
        }
        List<PortraitSnapshot.Joint> typeActions = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT v.type,a.feature,a.source,a.stage,COUNT(*) AS n FROM actions a JOIN visits v ON a.token=v.token WHERE a.day BETWEEN ? AND ? GROUP BY v.type,a.feature,a.source,a.stage ORDER BY n DESC,a.feature ASC LIMIT 48", range)) {
            while (cursor.moveToNext()) typeActions.add(new PortraitSnapshot.Joint(typeLabel(cursor.getString(0)),
                    featureLabel(cursor.getString(1)) + " · " + sourceLabel(cursor.getString(2)) + " · " + stageLabel(cursor.getString(3)), cursor.getLong(4)));
        }
        List<String> coverage = new ArrayList<>();
        coverage.add("统计窗口 " + start + " 至 " + end + "；今天尚未结束。浏览次数包括再次刷到；空白日可能未使用、关闭采集或未覆盖。");
        coverage.add("日期汇总最多保留 365 天；关联可见片段保留 30 天且最多 20,000 段，动作最多 30,000 条。旧汇总不会补造关联。");
        coverage.add("停留是前台有焦点的可见采样，不保证识别同窗口遮挡或实际注视，也不等于完整观看或喜欢。约每 3 秒保存检查点，进程终止可能丢失未落盘尾部；单片段上限 30 分钟。");
        coverage.add("话题占比以全部话题出现次数为分母，一条作品可以包含多个话题；作者与话题均匀度来自全量分布，不只 Top 10。");
        coverage.add("已识别作者的记录 " + PortraitMetrics.total(allAuthors) + " / 总浏览 " + views + "；未知作者不计入作者数量、均匀度和集中度，作者占比以已识别作者记录为分母。");
        coverage.add("主动、自动、系统与未知来源分别统计；旧版功能事件来源未知。动作阶段分别计数，不能直接当作独立任务数。");
        coverage.add("时段关联采用可见片段开始小时；关联图只展示实际同段记录中次数最多的 48 组。未关联动作仍计入功能次数，不进入作品关联图。");
        if (earliest > 0) coverage.add("可追溯记录起点：" + Instant.ofEpochMilli(earliest).atZone(zone.get()).toLocalDate() + "；没有记录的更早日期不能当作零使用。");
        coverage.add("本地写入队列自清空以来拒绝 " + dropped + " 个任务；拒绝会导致样本缺失。采集不覆盖宿主所有页面。");
        String evidenceLevel = views == 0 ? "暂无浏览样本" : views < 50 || browseDays < 3 ? "样本较少，仅作记录描述" : views < 200 || browseDays < 7 ? "已有跨日样本，仍受覆盖限制" : "跨日样本较充足，仍只代表已记录行为";
        return new PortraitSnapshot(start.toString(), end.toString(), days, weekdayHours,
                PortraitMetrics.headAndTail(allTopics, 8), PortraitMetrics.headAndTail(allAuthors, 8), sources,
                browseDays, allAuthors.size(), allTopics.size(), PortraitMetrics.total(allTopics), repeated,
                captured, finished, visible, dropped, PortraitMetrics.diversity(allAuthors), PortraitMetrics.diversity(allTopics),
                allAuthors.isEmpty() ? 0 : PortraitMetrics.ratio(allAuthors.get(0).count, PortraitMetrics.total(allAuthors)),
                allTopics.isEmpty() ? 0 : PortraitMetrics.ratio(allTopics.get(0).count, PortraitMetrics.total(allTopics)),
                PortraitMetrics.ratio(repeated, unique), PortraitMetrics.ratio(night, views), PortraitMetrics.ratio(weekend, views),
                comparison, week, month, PortraitMetrics.insights(views, features, unique, browseDays, allAuthors,
                        allTopics, allTypes, sources, repeated, PortraitMetrics.ratio(night, views), PortraitMetrics.ratio(weekend, views), captured, visible, comparison),
                topicHours, typeActions, coverage, evidenceLevel, PortraitEvidenceQuery.read(db, start, end, today()));
    }

    private PortraitSnapshot.Comparison comparison(SQLiteDatabase db, LocalDate start, LocalDate end, long first) {
        long length = ChronoUnit.DAYS.between(start, end) + 1;
        LocalDate previousEnd = start.minusDays(1), previousStart = start.minusDays(length);
        String[] current = {start.toString(), end.toString()}, previous = {previousStart.toString(), previousEnd.toString()};
        long from = previousStart.atStartOfDay(zone.get()).toInstant().toEpochMilli();
        long until = start.atStartOfDay(zone.get()).toInstant().toEpochMilli();
        boolean dateComplete = first > 0 && first <= from && !previousStart.isBefore(today().minusDays(RETENTION_DAYS - 1L));
        long stopped = scalar(db, "SELECT COUNT(*) FROM collection_boundaries WHERE enabled=0 AND ts>=? AND ts<?", new String[]{Long.toString(from), Long.toString(until)});
        // A disable before the window can also cover its beginning.
        long disabledAtStart = scalar(db, "SELECT CASE WHEN enabled=0 THEN 1 ELSE 0 END FROM collection_boundaries WHERE ts<? ORDER BY ts DESC,id DESC LIMIT 1", new String[]{Long.toString(from)});
        boolean previousComplete = dateComplete && stopped == 0 && disabledAtStart == 0;
        String limitation = "按本地自然日比较等长窗口，本期含未结束的今天。"
                + (previousComplete ? "前期日期在保留范围内，但未覆盖页面、宿主退出与采集缺口仍可能漏记。" : "前期早于记录/保留起点，或含已知关闭采集时间；不计算增长率。")
                + "次数变化不能直接归因为兴趣变化。";
        List<AnalyticsSnapshot.Rank> currentAuthors = allRanks(db, current, "author"), previousAuthors = allRanks(db, previous, "author");
        currentAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        previousAuthors.removeIf(rank -> "未识别作者".equals(rank.label));
        return new PortraitSnapshot.Comparison(start.toString(), end.toString(), previousStart.toString(), previousEnd.toString(),
                scalar(db, "SELECT COALESCE(SUM(views),0) FROM daily WHERE day BETWEEN ? AND ?", current),
                scalar(db, "SELECT COALESCE(SUM(views),0) FROM daily WHERE day BETWEEN ? AND ?", previous),
                scalar(db, "SELECT COALESCE(SUM(features),0) FROM daily WHERE day BETWEEN ? AND ?", current),
                scalar(db, "SELECT COALESCE(SUM(features),0) FROM daily WHERE day BETWEEN ? AND ?", previous),
                allRanks(db, current, "type"), allRanks(db, previous, "type"), false, previousComplete, limitation,
                PortraitMetrics.headAndTail(allRanks(db, current, "topic"), 8),
                PortraitMetrics.headAndTail(allRanks(db, previous, "topic"), 8),
                PortraitMetrics.headAndTail(currentAuthors, 8), PortraitMetrics.headAndTail(previousAuthors, 8));
    }

    private static List<AnalyticsSnapshot.Rank> allRanks(SQLiteDatabase db, String[] range, String kind) {
        List<AnalyticsSnapshot.Rank> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT MAX(label),SUM(count) AS total FROM ranks WHERE day BETWEEN ? AND ? AND kind=? GROUP BY item_key ORDER BY total DESC,MAX(label) ASC", new String[]{range[0], range[1], kind})) {
            while (cursor.moveToNext()) result.add(new AnalyticsSnapshot.Rank(cursor.getString(0), cursor.getLong(1)));
        }
        return result;
    }

    private static List<AnalyticsSnapshot.Bucket> trend(LocalDate start, LocalDate end, Map<LocalDate, Long> values) {
        if (values.isEmpty()) return List.of();
        // At most 90 daily buckets; larger ranges use consecutive seven-day buckets (<=53).
        int step = ChronoUnit.DAYS.between(start, end) + 1 > 90 ? 7 : 1;
        List<AnalyticsSnapshot.Bucket> result = new ArrayList<>();
        for (LocalDate bucket = start; !bucket.isAfter(end); bucket = bucket.plusDays(step)) {
            LocalDate finish = bucket.plusDays(step - 1L);
            if (finish.isAfter(end)) finish = end;
            long count = 0;
            for (LocalDate date = bucket; !date.isAfter(finish); date = date.plusDays(1))
                count += values.getOrDefault(date, 0L);
            String label = SHORT_DATE.format(bucket);
            if (step > 1 && !bucket.equals(finish)) label += "–" + SHORT_DATE.format(finish);
            result.add(new AnalyticsSnapshot.Bucket(label, count));
        }
        return result;
    }

    private static List<AnalyticsSnapshot.Rank> ranks(SQLiteDatabase db, String[] range, String kind) {
        List<AnalyticsSnapshot.Rank> result = new ArrayList<>();
        try (Cursor cursor = db.rawQuery("SELECT MAX(label),SUM(count) AS total FROM ranks WHERE day BETWEEN ? AND ? AND kind=? GROUP BY item_key ORDER BY total DESC,MAX(label) ASC LIMIT 10",
                new String[]{range[0], range[1], kind})) {
            while (cursor.moveToNext()) result.add(new AnalyticsSnapshot.Rank(cursor.getString(0), cursor.getLong(1)));
        }
        return result;
    }

    private static long scalar(SQLiteDatabase db, String sql, String[] args) {
        try (Cursor cursor = db.rawQuery(sql, args)) { return cursor.moveToFirst() ? cursor.getLong(0) : 0; }
    }

    private LocalDate today() { return Instant.ofEpochMilli(clock.millis()).atZone(zone.get()).toLocalDate(); }

    private void persistEnabled(boolean value) throws Exception {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IllegalStateException("Local storage unavailable");
        File pending = new File(directory, "enabled.pending");
        try {
            try (FileOutputStream stream = new FileOutputStream(pending)) {
                stream.write(value ? '1' : '0');
                stream.getFD().sync();
            }
            File target = new File(directory, "enabled");
            try {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(pending.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            // Explicit replacement works on Android and on Windows validation hosts. Keep
            // AtomicFile reads for compatibility with the original flag and legacy backups.
            String persisted = new String(enabledFile.readFully(), StandardCharsets.UTF_8);
            if (!(value ? "1" : "0").equals(persisted))
                throw new IllegalStateException("Local analytics preference was not saved");
        } finally { Files.deleteIfExists(pending.toPath()); }
    }

    private void failClosed() {
        // No caller holds stateLock while acquiring databaseLock; serialize flag/epoch here.
        synchronized (stateLock) {
            enabled = false;
            generation++;
            lastWorkHash = null;
            lastWorkDay = null;
            currentVisit = null;
            cancelAutomaticAdvance();
            try { persistEnabled(false); }
            catch (Exception ignored) { try { enabledFile.delete(); } catch (Exception ignoredDelete) { } }
        }
    }

    private static String clean(String value, int maxLength) {
        if (value == null) return "";
        String trimmed = value.trim().replace('\n', ' ').replace('\r', ' ');
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static String normalizeSource(String source) {
        if ("manual".equals(source) || "automatic".equals(source) || "system".equals(source)) return source;
        return "unknown";
    }

    private static String normalizeStage(String stage) {
        if ("entry".equals(stage) || "request".equals(stage) || "accepted".equals(stage)
                || "success".equals(stage) || "failure".equals(stage) || "confirmed".equals(stage)) return stage;
        return "unknown";
    }

    private static String sourceLabel(String source) {
        switch (normalizeSource(source)) {
            case "manual": return "主动操作";
            case "automatic": return "自动触发";
            case "system": return "系统处理";
            default: return "来源未知";
        }
    }

    private static String stageLabel(String stage) {
        switch (normalizeStage(stage)) {
            case "entry": return "入口";
            case "request": return "请求";
            case "accepted": return "已受理";
            case "success": return "成功";
            case "failure": return "失败";
            case "confirmed": return "已确认";
            default: return "阶段未知";
        }
    }

    private static String featureSource(String key) {
        switch (key) {
            case "auto_follow": case "auto_like": case "auto_comment_sent": case "auto_reply_sent": case "auto_slide":
                return "automatic";
            case "gesture_menu": case "view_finder": case "copy_text": case "save_media": case "open_comments":
            case "gesture_speed": case "comment_tts": case "third_party_download": case "analytics_share":
                return "manual";
            case "feed_filter": case "analytics_ai_analysis": return "system";
            default: return "unknown";
        }
    }

    private static String featureLabel(String key) {
        switch (key) {
            case "auto_follow": return "自动关注";
            case "auto_like": return "自动点赞";
            case "auto_comment_sent": return "自动评论";
            case "auto_reply_sent": return "自动回复";
            case "auto_slide": return "自动翻页";
            case "gesture_menu": return "快捷菜单";
            case "view_finder": return "视图查找器";
            case "copy_text": return "复制文案";
            case "save_media": return "保存媒体";
            case "open_comments": return "打开评论";
            case "gesture_speed": return "切换倍速";
            case "comment_tts": return "评论语音";
            case "download_queued": return "下载任务";
            case "third_party_download": return "第三方下载";
            case "feed_filter": return "内容过滤";
            default: return clean(key, 60);
        }
    }

    private static String hash(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte element : bytes) result.append(Character.forDigit((element >>> 4) & 15, 16)).append(Character.forDigit(element & 15, 16));
        return result.toString();
    }

    private static String normalizeType(String type) {
        if (type == null) return "unknown";
        switch (type) {
            case "short_video": case "long_video": case "live_photo": case "image": case "live": return type;
            default: return "unknown";
        }
    }

    public static String typeLabel(String type) {
        switch (normalizeType(type)) {
            case "short_video": return "短视频";
            case "long_video": return "长视频";
            case "live_photo": return "实况图集";
            case "image": return "图集";
            case "live": return "直播卡片";
            default: return "其他作品";
        }
    }
}
