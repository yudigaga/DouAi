package com.douai.module.analytics;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import com.douai.module.config.BackupLimitException;
import com.douai.module.config.BackupValidationException;
import com.douai.module.diagnostics.DouAiLog;
import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Logical, bounded snapshots of owned tables. Never accepts database files, SQL or paths. */
public final class AnalyticsBackup {
    public static final int MAX_BYTES = 30 * 1024 * 1024;
    public static final int MAX_ROWS = 250_000;
    private static final long MAX_COUNT = 1_000_000_000_000L;
    private AnalyticsBackup() { }

    private static final class Table {
        final String name; final String[] columns; final int maxRows; final int[] key;
        Table(String name, String columns, int maxRows, int... key) {
            this.name = name; this.columns = columns.split(","); this.maxRows = maxRows; this.key = key;
        }
    }
    private static final List<Table> TABLES = List.of(
            new Table("daily", "day,views,features,ads,content_duration", 365, 0),
            new Table("hours", "day,hour,count", 365 * 24, 0, 1),
            new Table("work_days", "day,work_hash", 100_000, 0, 1),
            new Table("ranks", "day,kind,item_key,label,count", 150_000, 0, 1, 2),
            new Table("recent", "id,ts,label,kind", 30, 0),
            new Table("meta", "key,value", 4, 0),
            new Table("visits", "token,work_hash,type,day,hour,source,started,visible_ms,ended,end_reason,author_hash,duration_band,image_band,is_ad", 20_000, 0),
            new Table("visit_topics", "token,topic", 200_000, 0, 1),
            new Table("actions", "id,day,token,feature,source,stage,ts", 30_000, 0),
            new Table("collection_boundaries", "id,ts,enabled", 100_000, 0),
            new Table("flow_events", "id,operation_id,token,kind,stage,task,day,ts,seq", 30_000, 0),
            new Table("source_coverage", "source,status,since,trimmed_before", 16, 0),
            new Table("queue_gaps","day,source", 365*12, 0,1));

    /** Detached validated payload; no mutable JSON from the importing caller is retained. */
    public static final class Snapshot {
        public final int rowCount;
        public final String summary;
        private final Map<String, List<Object[]>> tables;
        private final LocalPortraitContext.State context;
        final long capturedAt;
        final int version;
        private Snapshot(Map<String, List<Object[]>> tables, LocalPortraitContext.State context,
                         long capturedAt, int rowCount, String summary, int version) {
            this.tables = tables; this.context = context; this.capturedAt = capturedAt;
            this.rowCount = rowCount; this.summary = summary; this.version = version;
        }
    }

    /** Worker only; queued writes drain before a transaction reads all tables. */
    public static JSONObject export(Context context) {
        DouAiLog.Operation operation = DouAiLog.begin("analytics_backup_export", "logical_snapshot");
        try {
            JSONObject payload = exportChecked(context);
            operation.finish(DouAiLog.Outcome.SUCCESS, "snapshot_validated", "bytes=" + payload.toString().getBytes(StandardCharsets.UTF_8).length
                    + "; external_file_written=false");
            return payload;
        } catch (RuntimeException failure) {
            operation.error("snapshot", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "snapshot_failed", "external_file_written=false");
            throw failure;
        }
    }

    private static JSONObject exportChecked(Context context) {
        return LocalPortraitContext.withState(context, state -> {
            JSONObject payload = LocalAnalytics.get(context).exportBackup();
            try {
                payload.put("context", new JSONObject().put("purpose", state.purpose).put("feedback", state.feedback));
                checkBytes(payload);
                inspect(payload);
                return payload;
            } catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw invalid(); }
        });
    }

    /** Pure validation. Does not open this installation's analytics database. */
    public static Snapshot inspect(JSONObject payload) {
        DouAiLog.Operation operation = DouAiLog.begin("analytics_backup_preview", "validate_logical_snapshot");
        try {
            Snapshot snapshot = inspectChecked(payload);
            operation.finish(DouAiLog.Outcome.SUCCESS, "snapshot_validated", "rows=" + snapshot.rowCount + "; applied=false");
            return snapshot;
        } catch (RuntimeException failure) {
            operation.error("validation", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "snapshot_validation_failed", "applied=false");
            throw failure;
        }
    }

    private static Snapshot inspectChecked(JSONObject payload) {
        BackupValidationException.Reason reason = BackupValidationException.Reason.HEADER_SHAPE;
        String field = "";
        try {
            checkBytes(payload);
            exact(payload, "version", "capturedAt", "tables", "context");
            reason = BackupValidationException.Reason.ANALYTICS_VERSION;
            field = "version";
            int version = (int) integer(payload.get("version"), 1, 3);
            reason = BackupValidationException.Reason.CAPTURED_AT_RANGE;
            field = "capturedAt";
            long captured = integer(payload.get("capturedAt"), 0, System.currentTimeMillis() + 300_000);
            LocalDate lastDay = Instant.ofEpochMilli(captured).atOffset(ZoneOffset.ofHours(18)).toLocalDate();
            reason = BackupValidationException.Reason.ANALYTICS_CELL;
            field = "tables";
            JSONObject tables = payload.getJSONObject("tables");
            if (tables.length() != TABLES.size() - (version == 1 ? 3 : version == 2 ? 1 : 0)) throw invalid();
            field = "context";
            JSONObject self = payload.getJSONObject("context"); exact(self, "purpose", "feedback");
            field = "context.purpose";
            String purpose = text(self.get("purpose"), 240, true);
            field = "context.feedback";
            String feedback = text(self.get("feedback"), 8, true);
            field = "context";
            LocalPortraitContext.State state = LocalPortraitContext.backupState(purpose, feedback);
            Map<String, List<Object[]>> result = new LinkedHashMap<>(); int rows = 0;
            Set<String> visits = new HashSet<>();
            Set<String> flowKeys = new HashSet<>(), flowSeq = new HashSet<>();
            Map<String,List<Object>> flowOrigins = new LinkedHashMap<>();
            long views = 0, features = 0; String first = "", last = "";
            for (Table table : TABLES) {
                field = table.name;
                if ((version < 3 && table.name.equals("queue_gaps")) || (version == 1 && (table.name.equals("flow_events") || table.name.equals("source_coverage")))) {
                    result.put(table.name, new ArrayList<>()); continue;
                }
                JSONArray incoming = tables.getJSONArray(table.name);
                if (incoming.length() > table.maxRows || incoming.length() > MAX_ROWS - rows) throw invalid();
                List<Object[]> values = new ArrayList<>(); Set<List<Object>> keys = new HashSet<>();
                for (int index = 0; index < incoming.length(); index++) {
                    field = table.name;
                    JSONArray row = incoming.getJSONArray(index);
                    int expected = table.columns.length - (version == 1 && table.name.equals("visits") ? 4 : version == 2 && table.name.equals("flow_events") ? 1 : 0);
                    if (row.length() != expected) throw invalid();
                    Object[] cells = new Object[table.columns.length];
                    for (int column = 0; column < row.length(); column++) {
                        field = table.name + "." + table.columns[column];
                        cells[column] = validateCell(table.name, table.columns[column], row.get(column), captured, lastDay);
                    }
                    List<Object> key = new ArrayList<>(); for (int column : table.key) key.add(cells[column]);
                    field = table.name;
                    if (!keys.add(key)) throw invalid();
                    if (table.name.equals("daily")) {
                        field = "daily.ads";
                        if ((Long) cells[3] > (Long) cells[1]) throw invalid();
                        field = "daily.views";
                        views = Math.addExact(views, (Long) cells[1]);
                        field = "daily.features";
                        features = Math.addExact(features, (Long) cells[2]);
                        String day = (String) cells[0];
                        if (first.isEmpty() || day.compareTo(first) < 0) first = day;
                        if (last.isEmpty() || day.compareTo(last) > 0) last = day;
                    }
                    if (table.name.equals("visits")) visits.add((String) cells[0]);
                    if (table.name.equals("flow_events")) {
                        if (!flowKeys.add(cells[1] + ":" + cells[4] + ":" + cells[5])) throw invalid();
                        if (cells[8] != null && (!"ai".equals(cells[3]) || !flowSeq.add(cells[1]+":"+cells[8]))) throw invalid();
                        if ((version < 3 || !"ai".equals(cells[3])) && (Long)cells[5] > 10000) throw invalid();
                        List<Object> origin = java.util.Arrays.asList(cells[2],cells[3],cells[8] != null);
                        List<Object> before = flowOrigins.putIfAbsent((String)cells[1],origin);
                        if (before != null && !before.equals(origin)) throw invalid();
                    }
                    if (table.name.equals("visit_topics")) {
                        field = "visit_topics.token";
                        if (!visits.contains(cells[0])) throw invalid();
                    }
                    if (table.name.equals("meta")) {
                        field = "meta.key";
                        if (!List.of("first_seen", "collection_start", "queue_dropped").contains(cells[0]) && !(version >= 3 && "legacy_drop_through".equals(cells[0]))) throw invalid();
                        field = "meta.value";
                        if (!cells[0].equals("queue_dropped") && (Long) cells[1] > captured) throw invalid();
                        if (cells[0].equals("queue_dropped") && (Long) cells[1] > MAX_COUNT) throw invalid();
                    }
                    values.add(cells);
                }
                result.put(table.name, values); rows += values.size();
            }
            if (result.get("actions").size() + result.get("flow_events").size() > 30_000) throw invalid();
            String dates = first.isEmpty() ? "无浏览日期" : first + " — " + last;
            String summary = dates + "；" + views + " 次浏览，" + features + " 次功能使用，"
                    + result.get("visits").size() + " 条可见片段，" + rows + " 条记录"
                    + ((!state.purpose.isEmpty() || !state.feedback.isEmpty()) ? "；含侧写自述/反馈" : "；无侧写自述/反馈");
            return new Snapshot(result, state, captured, rows, summary, version);
        } catch (Exception failure) {
            throw BackupValidationException.wrap(BackupValidationException.Section.ANALYTICS, reason, field, failure);
        }
    }

    /**
     * Worker only. SQLite replacement is transactional. Context/settings are compensated on failure;
     * these separate stores are not a single crash-atomic transaction.
     */
    public static void restore(Context context, Snapshot snapshot, Runnable applySettings, Runnable rollbackSettings) {
        DouAiLog.Operation operation = DouAiLog.begin("analytics_backup_restore", "restore_snapshot");
        try {
            operation.wrap(() -> restoreChecked(context, snapshot, applySettings, rollbackSettings)).run();
            operation.finish(DouAiLog.Outcome.SUCCESS, "transaction_committed", "rows=" + snapshot.rowCount
                    + "; cross_store_crash_atomic=false; read_back_verified=false");
        } catch (RuntimeException failure) {
            operation.error("restore_or_compensation", failure);
            operation.finish(DouAiLog.Outcome.FAILED, failure.getSuppressed().length > 0
                            ? "restore_and_compensation_failed" : "restore_failed", "cross_store_crash_atomic=false");
            throw failure;
        }
    }

    private static void restoreChecked(Context context, Snapshot snapshot, Runnable applySettings, Runnable rollbackSettings) {
        if (snapshot == null) throw invalid();
        LocalPortraitContext.withState(context, before -> {
            boolean[] contextSaved = {false}, settingsSaved = {false};
            try {
                LocalAnalytics.get(context).replaceBackup(snapshot, () -> {
                    LocalPortraitContext.replaceFromBackup(context, snapshot.context); contextSaved[0] = true;
                    applySettings.run(); settingsSaved[0] = true;
                });
            } catch (RuntimeException failure) {
                if (settingsSaved[0]) try { rollbackSettings.run(); }
                catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
                if (contextSaved[0]) try { LocalPortraitContext.replaceFromBackup(context, before); }
                catch (RuntimeException compensation) { failure.addSuppressed(compensation); }
                DouAiLog.event("analytics_backup_restore", "compensation", "restore_failed", "settings_had_changed=" + settingsSaved[0]
                        + "; context_had_changed=" + contextSaved[0] + "; compensation_errors=" + failure.getSuppressed().length);
                if (failure.getSuppressed().length > 0) throw new IllegalStateException(
                        "恢复未完成，部分配置或自述未能回滚；本地统计事务已保留，请检查存储后重试", failure);
                throw failure;
            }
            return null;
        });
    }

    static JSONObject capture(SQLiteDatabase db, long capturedAt, long dropped) {
        try {
            JSONObject tables = new JSONObject(); int rows = 0; long estimatedBytes = 256;
            for (Table table : TABLES) {
                JSONArray values = new JSONArray();
                if (db != null) try (Cursor cursor = db.query(table.name, table.columns, null, null, null, null,
                        String.join(",", table.columns))) {
                    while (cursor.moveToNext()) {
                        if (++rows > MAX_ROWS || values.length() >= table.maxRows) throw tooLarge();
                        JSONArray row = new JSONArray();
                        for (int column = 0; column < table.columns.length; column++) {
                            Object cell = cursor.isNull(column) ? JSONObject.NULL
                                    : cursor.getType(column) == Cursor.FIELD_TYPE_INTEGER ? cursor.getLong(column) : cursor.getString(column);
                            row.put(cell);
                        }
                        estimatedBytes += row.toString().getBytes(StandardCharsets.UTF_8).length + 1;
                        if (estimatedBytes > MAX_BYTES) throw tooLarge();
                        values.put(row);
                    }
                }
                if (table.name.equals("meta") && dropped > 0) {
                    long stored = 0; int found = -1;
                    for (int index = 0; index < values.length(); index++) if ("queue_dropped".equals(values.getJSONArray(index).getString(0))) {
                        found = index; stored = values.getJSONArray(index).getLong(1); break;
                    }
                    JSONArray row = new JSONArray().put("queue_dropped").put(Math.addExact(stored, dropped));
                    if (found < 0) {
                        if (++rows > MAX_ROWS || values.length() >= table.maxRows) throw tooLarge();
                        values.put(row);
                    } else values.put(found, row);
                }
                tables.put(table.name, values);
            }
            return new JSONObject().put("version", 3).put("capturedAt", capturedAt).put("tables", tables);
        } catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw invalid(); }
    }

    static void replaceTables(SQLiteDatabase db, Snapshot snapshot) {
        for (Table table : TABLES) db.delete(table.name, null, null);
        // A logical replacement also replaces owned auto-increment high-water marks.
        db.delete("sqlite_sequence", "name IN (?,?,?,?)", new String[]{"recent", "actions", "collection_boundaries", "flow_events"});
        for (Table table : TABLES) for (Object[] row : snapshot.tables.get(table.name)) {
            ContentValues values = new ContentValues();
            for (int column = 0; column < row.length; column++) {
                Object value = row[column];
                if (value == null) values.putNull(table.columns[column]);
                else if (value instanceof Long) values.put(table.columns[column], (Long) value);
                else values.put(table.columns[column], (String) value);
            }
            db.insertOrThrow(table.name, null, values);
        }
        if (snapshot.version<3) LocalAnalytics.markLegacyDrops(db,snapshot.capturedAt);
        // Imported live segments cannot be resumed by this process.
        ContentValues ended = new ContentValues(); ended.put("ended", 1); ended.put("end_reason", "backup_restore");
        db.update("visits", ended, "ended=0", null);
    }

    private static Object validateCell(String table, String column, Object value, long captured, LocalDate lastDay) {
        if (List.of("author_hash", "duration_band", "image_band", "is_ad", "trimmed_before").contains(column) && value == JSONObject.NULL) return null;
        if (column.equals("author_hash")) { String hash = text(value,64,false); if (!hash.matches("[a-f0-9]{64}")) throw invalid(); return hash; }
        if (column.equals("duration_band")) return enumeration(value,"under_15s","15_59s","1_5m","5m_plus");
        if (column.equals("image_band")) return enumeration(value,"1","2_5","6_10","11_plus");
        if (column.equals("is_ad")) return integer(value,0,1);
        if (column.equals("seq")) return value == JSONObject.NULL ? null : integer(value,1,MAX_COUNT);
        if (column.equals("task")) return integer(value,0,Integer.MAX_VALUE);
        if (table.equals("queue_gaps") && column.equals("source")) return enumeration(value,"summary","history","visit_history","visits","events","dwell","ai","download","playback","speed","backward");
        if (column.equals("since") || column.equals("trimmed_before")) return integer(value,0,captured);
        if (column.equals("status")) return enumeration(value,"available","unavailable","unknown");
        if (table.equals("source_coverage") && column.equals("source")) return enumeration(value,"metadata","download","ai","gesture","playback","events","visits");
        if (table.equals("flow_events") && column.equals("kind")) return enumeration(value,"download","ai","playback","speed","backward");
        if (table.equals("flow_events") && column.equals("stage")) return enumeration(value,"entry","request","accepted","task_end","output_closed","cancelled","failure","generated","dispatched","visible","followup_click","more_click","video_click","route_dispatched","manual_select","restore","inherited","requested","readback","playing","paused","ended","confirmed");
        if (column.equals("day")) {
            String day = text(value, 10, false);
            LocalDate parsed = LocalDate.parse(day);
            if (!parsed.toString().equals(day) || parsed.isBefore(LocalDate.of(1970, 1, 1)) || parsed.isAfter(lastDay)) throw invalid();
            return day;
        }
        if (column.equals("id")) return integer(value, 1, MAX_COUNT);
        if (column.equals("hour")) return integer(value, 0, 23);
        if (column.equals("enabled") || column.equals("ended")) return integer(value, 0, 1);
        if (column.equals("ts") || column.equals("started")) return integer(value, 0, captured);
        if (column.equals("visible_ms")) return integer(value, 0, 30 * 60 * 1000L);
        if (column.equals("value")) return integer(value, 0, Math.max(captured, MAX_COUNT));
        if (List.of("views", "features", "ads", "content_duration", "count").contains(column))
            return integer(value, 0, MAX_COUNT);
        if (column.equals("work_hash")) {
            String hash = text(value, 64, false); if (!hash.matches("[a-f0-9]{64}")) throw invalid(); return hash;
        }
        if (column.equals("token") || column.equals("operation_id")) {
            if ((table.equals("actions") || table.equals("flow_events")) && column.equals("token") && value == JSONObject.NULL) return null;
            String token = text(value, 36, false);
            if (!token.matches("[a-f0-9]{8}(-[a-f0-9]{4}){3}-[a-f0-9]{12}")) throw invalid(); return token;
        }
        if (column.equals("source")) return enumeration(value, "manual", "automatic", "system", "unknown");
        if (column.equals("stage")) return enumeration(value, "entry", "request", "accepted", "success", "failure", "confirmed", "unknown");
        if (column.equals("type")) return enumeration(value, "short_video", "long_video", "live_photo", "image", "live", "unknown");
        if (column.equals("kind")) return table.equals("recent") ? enumeration(value, "view", "feature")
                : enumeration(value, "type", "author", "topic", "feature", "feature_source");
        if (column.equals("end_reason")) return text(value, 80, true);
        if (column.equals("topic") || column.equals("feature")) return text(value, 100, false);
        if (column.equals("key")) return text(value, 32, false);
        if (column.equals("item_key") || column.equals("label")) return text(value, 160, false);
        throw invalid();
    }
    private static long integer(Object value, long min, long max) {
        if (!(value instanceof Long || value instanceof Integer)) throw invalid();
        long number = ((Number) value).longValue(); if (number < min || number > max) throw invalid(); return number;
    }
    private static String enumeration(Object value, String... allowed) {
        String text = text(value, 32, false); if (!List.of(allowed).contains(text)) throw invalid(); return text;
    }
    private static String text(Object value, int max, boolean empty) {
        if (!(value instanceof String)) throw invalid(); String text = (String) value;
        if (text.length() > max || (!empty && text.isEmpty())
                || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n')) throw invalid();
        return text;
    }
    private static void exact(JSONObject object, String... keys) {
        if (object.length() != keys.length) throw invalid();
        for (String key : keys) if (!object.has(key)) throw invalid();
    }
    private static void checkBytes(JSONObject object) {
        if (object == null || object.toString().getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) throw tooLarge();
    }
    private static IllegalArgumentException tooLarge() { return new BackupLimitException(BackupLimitException.Section.ANALYTICS); }
    private static IllegalArgumentException invalid() { return new IllegalArgumentException("本地分析备份格式无效、数据越界或内容不完整"); }
}
