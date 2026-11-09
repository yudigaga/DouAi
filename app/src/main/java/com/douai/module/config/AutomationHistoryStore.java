package com.douai.module.config;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/** Bounded local automation history, stored independently from module settings. */
public final class AutomationHistoryStore {
    public static final String REPLY = "reply";
    public static final String COMMENT = "comment";
    public static final String COMMENT_REPLY = "comment_reply";
    public static final String DELIVERY_UNKNOWN = "unknown";
    public static final String DELIVERY_TEXT = "text";
    public static final String DELIVERY_VOICE = "voice";
    /** Diagnostic values are closed vocabularies: never store exception text, URLs or credentials. */
    public enum Reason {
        NONE, QUEUE_FULL, QUEUE_EXPIRED, INVALIDATED, CANCELLED, SETTINGS_CHANGED,
        MEDIA_FAILED, GENERATION_TIMEOUT, GENERATION_FAILED, SPEECH_FAILED,
        SEND_FAILED, SUBMITTED_UNCONFIRMED, LEDGER_WRITE_FAILED, PERSISTENCE_FAILED,
        ALLOWANCE_EXHAUSTED, DUPLICATE, AI_HTTP_ERROR, AI_RATE_LIMITED, AI_AUTH_FAILED,
        INVALID_RESPONSE, LEDGER_FULL
    }
    public enum Stage { NONE, QUEUE, MEDIA, GENERATION, SPEECH, SEND, COMPLETE }
    private static final long MAX_DIAGNOSTIC_MILLIS = 7L * 24 * 60 * 60 * 1000;
    private static final String FILE = "douai_automation_history";
    private static final String KEY = "entries_v1";
    private static final int MAX_PER_KIND = 200;
    private static final int MAX_BYTES = 2 * 1024 * 1024;
    private static final Object LOCK = new Object();
    private final SharedPreferences preferences;

    public AutomationHistoryStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Context is required");
        preferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public List<Entry> read(String kind) {
        requireKind(kind);
        synchronized (LOCK) {
            List<Entry> selected = new ArrayList<>();
            for (Entry entry : readAll()) if (matchesKind(kind, entry.kind)) selected.add(entry);
            selected.sort(Comparator.comparingLong((Entry entry) -> entry.createdAt).reversed()
                    .thenComparing(entry -> entry.id).thenComparing(entry -> entry.kind));
            if (selected.size() > MAX_PER_KIND) selected.subList(MAX_PER_KIND, selected.size()).clear();
            return Collections.unmodifiableList(selected);
        }
    }

    public void clear(String kind) {
        requireKind(kind);
        synchronized (LOCK) {
            List<Entry> entries = readAll();
            entries.removeIf(entry -> matchesKind(kind, entry.kind));
            persist(entries);
            long now = System.currentTimeMillis();
            SharedPreferences.Editor editor = preferences.edit().putLong("cleared_at_" + kind, now);
            if (REPLY.equals(kind)) editor.putLong("cleared_at_" + COMMENT_REPLY, now);
            editor.apply();
        }
    }

    public void upsert(Entry entry) {
        if (entry == null) throw new IllegalArgumentException("Entry is required");
        synchronized (LOCK) {
            if (preferences.contains("cleared_at_" + entry.kind)
                    && entry.createdAt <= preferences.getLong("cleared_at_" + entry.kind, 0)) return;
            List<Entry> entries = readAll();
            for (Entry previous : entries) {
                if (!previous.kind.equals(entry.kind) || !previous.id.equals(entry.id)) continue;
                if (previous.modelRequestSent && !entry.modelRequestSent)
                    entry = requestMetadata(entry, previous.requestImageCount);
                entry = entry.withLocation(Location.merge(entry.kind, previous.location, entry.location));
                break;
            }
            Entry updated = entry;
            entries.removeIf(previous -> previous.kind.equals(updated.kind) && previous.id.equals(updated.id));
            entries.add(entry);
            entries.sort(Comparator.comparingLong((Entry item) -> item.createdAt)
                    .thenComparing(item -> item.id));
            trimKind(entries, REPLY);
            trimKind(entries, COMMENT);
            persist(entries);
        }
    }

    public void markModelRequestSent(String kind, String id, int imageCount) {
        requireKind(kind);
        synchronized (LOCK) {
            List<Entry> entries = readAll();
            for (int index = 0; index < entries.size(); index++) {
                Entry previous = entries.get(index);
                if (kind.equals(previous.kind) && id.equals(previous.id)) {
                    entries.set(index, requestMetadata(previous, imageCount));
                    persist(entries);
                    return;
                }
            }
        }
    }

    private static Entry requestMetadata(Entry entry, int imageCount) {
        return new Entry(entry.id, entry.kind, entry.targetId, entry.targetName,
                entry.input, entry.output, entry.modelName, entry.status, entry.createdAt, entry.updatedAt,
                imageCount, true, entry.deliveryMode).withDiagnostics(entry.reason, entry.stage,
                entry.queueMillis, entry.mediaMillis, entry.generationMillis, entry.sendMillis)
                .withLocation(entry.location);
    }

    private static void trimKind(List<Entry> entries, String kind) {
        int count = 0;
        for (Entry entry : entries) if (matchesKind(kind, entry.kind)) count++;
        for (int i = 0; count > MAX_PER_KIND && i < entries.size();) {
            if (matchesKind(kind, entries.get(i).kind)) { entries.remove(i); count--; }
            else i++;
        }
    }

    private static boolean matchesKind(String requested, String stored) {
        return requested.equals(stored) || REPLY.equals(requested) && COMMENT_REPLY.equals(stored);
    }

    private List<Entry> readAll() {
        String source;
        try { source = preferences.getString(KEY, "[]"); }
        catch (ClassCastException ignored) { return new ArrayList<>(); }
        if (source == null || source.length() > MAX_BYTES) return new ArrayList<>();
        List<Entry> entries = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(source);
            for (int i = Math.max(0, array.length() - 3 * MAX_PER_KIND); i < array.length(); i++) {
                JSONObject json = array.optJSONObject(i);
                if (json == null) continue;
                try {
                    entries.add(new Entry(json.optString("id"), json.optString("kind"),
                            json.optString("targetId"), json.optString("targetName"),
                            json.optString("input"), json.optString("output"),
                            json.optString("modelName"), json.optString("status"),
                            json.optLong("createdAt"), json.optLong("updatedAt"),
                            json.optInt("requestImageCount", -1), json.optBoolean("modelRequestSent", false),
                            json.optString("deliveryMode", DELIVERY_UNKNOWN)).withDiagnostics(
                            diagnosticEnum(Reason.class, json.opt("reason"), Reason.NONE),
                            diagnosticEnum(Stage.class, json.opt("stage"), Stage.NONE),
                            diagnosticMillis(json.opt("queueMillis")), diagnosticMillis(json.opt("mediaMillis")),
                            diagnosticMillis(json.opt("generationMillis")), diagnosticMillis(json.opt("sendMillis")))
                            .withLocation(Location.read(json.optJSONObject("location"))));
                } catch (IllegalArgumentException ignored) { }
            }
        } catch (JSONException ignored) { }
        return entries;
    }

    private void persist(List<Entry> entries) {
        String serialized = serialize(entries);
        while (!entries.isEmpty() && serialized.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            entries.remove(0);
            serialized = serialize(entries);
        }
        preferences.edit().putString(KEY, serialized).apply();
    }

    private static String serialize(List<Entry> entries) {
        JSONArray array = new JSONArray();
        for (Entry entry : entries) {
            try {
                array.put(new JSONObject().put("id", entry.id).put("kind", entry.kind)
                        .put("targetId", entry.targetId).put("targetName", entry.targetName)
                        .put("input", entry.input).put("output", entry.output)
                        .put("modelName", entry.modelName).put("status", entry.status)
                        .put("createdAt", entry.createdAt).put("updatedAt", entry.updatedAt)
                        .put("requestImageCount", entry.requestImageCount).put("modelRequestSent", entry.modelRequestSent)
                        .put("deliveryMode", entry.deliveryMode)
                        .put("reason", entry.reason.name().toLowerCase(java.util.Locale.ROOT))
                        .put("stage", entry.stage.name().toLowerCase(java.util.Locale.ROOT))
                        .put("queueMillis", entry.queueMillis).put("mediaMillis", entry.mediaMillis)
                        .put("generationMillis", entry.generationMillis).put("sendMillis", entry.sendMillis)
                        .put("location", entry.location.json()));
            } catch (JSONException impossible) { throw new IllegalStateException(impossible); }
        }
        return array.toString();
    }

    private static void requireKind(String kind) {
        if (!REPLY.equals(kind) && !COMMENT.equals(kind) && !COMMENT_REPLY.equals(kind)) {
            throw new IllegalArgumentException("Unknown automation kind");
        }
    }

    private static String bounded(String text, int limit) {
        if (text == null) return "";
        if (text.length() <= limit) return text;
        int end = Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit;
        return text.substring(0, end);
    }

    private static <T extends Enum<T>> T diagnosticEnum(Class<T> type, Object value, T fallback) {
        if (!(value instanceof String)) return fallback;
        for (T candidate : type.getEnumConstants()) {
            if (candidate.name().toLowerCase(java.util.Locale.ROOT).equals(value)) return candidate;
        }
        return fallback;
    }

    private static long diagnosticMillis(Object value) {
        if (!(value instanceof Integer) && !(value instanceof Long)) return -1;
        return boundedMillis(((Number) value).longValue());
    }

    private static long boundedMillis(long value) {
        return value >= -1 && value <= MAX_DIAGNOSTIC_MILLIS ? value : -1;
    }

    /** Identifiers only; navigation constructs its own reviewed route rather than persisting arbitrary URLs. */
    public static final class Location {
        public static final Location EMPTY = new Location("", "", "", "", "", "");
        public final String accountId;
        public final String awemeId;
        public final String commentId;
        public final String rootCommentId;
        public final String conversationId;
        public final String messageId;
        public final int conversationType;
        public final String peerUid;

        public Location(String accountId, String awemeId, String commentId, String rootCommentId,
                        String conversationId, String messageId) {
            this(accountId, awemeId, commentId, rootCommentId, conversationId, messageId, -1);
        }

        public Location(String accountId, String awemeId, String commentId, String rootCommentId,
                        String conversationId, String messageId, int conversationType) {
            this(accountId, awemeId, commentId, rootCommentId, conversationId, messageId, conversationType, "");
        }

        public Location(String accountId, String awemeId, String commentId, String rootCommentId,
                        String conversationId, String messageId, int conversationType, String peerUid) {
            this.accountId = identifier(accountId, 256);
            this.awemeId = identifier(awemeId, 256);
            this.commentId = identifier(commentId, 256);
            this.rootCommentId = identifier(rootCommentId, 256);
            this.conversationId = identifier(conversationId, 512);
            this.messageId = identifier(messageId, 256);
            this.conversationType = conversationType == 0 || conversationType == 1 ? conversationType : -1;
            this.peerUid = numericIdentifier(peerUid);
        }

        private static String identifier(String value, int maximum) {
            if (value == null || value.length() > maximum) return "";
            for (int index = 0; index < value.length(); index++) {
                char character = value.charAt(index);
                if (Character.isISOControl(character) || Character.isWhitespace(character)
                        || Character.getType(character) == Character.FORMAT
                        || "/\\?#&=%".indexOf(character) >= 0) return "";
            }
            return value;
        }

        private static Location read(JSONObject value) {
            return value == null ? EMPTY : new Location(text(value, "accountId"), text(value, "awemeId"),
                    text(value, "commentId"), text(value, "rootCommentId"),
                    text(value, "conversationId"), text(value, "messageId"), conversationType(value.opt("conversationType")),
                    text(value, "peerUid"));
        }

        private static String numericIdentifier(String value) {
            String identifier = identifier(value, 256);
            for (int index = 0; index < identifier.length(); index++) {
                char digit = identifier.charAt(index);
                if (digit < '0' || digit > '9') return "";
            }
            return identifier;
        }

        private static int conversationType(Object value) {
            if (!(value instanceof Integer) && !(value instanceof Long)) return -1;
            long type = ((Number) value).longValue();
            return type == 0 || type == 1 ? (int) type : -1;
        }

        private static String text(JSONObject value, String key) {
            Object raw = value.opt(key);
            return raw instanceof String ? (String) raw : "";
        }

        private JSONObject json() throws JSONException {
            return new JSONObject().put("accountId", accountId).put("awemeId", awemeId)
                    .put("commentId", commentId).put("rootCommentId", rootCommentId)
                    .put("conversationId", conversationId).put("messageId", messageId)
                    .put("conversationType", conversationType).put("peerUid", peerUid);
        }

        private static Location merge(String kind, Location previous, Location next) {
            if (!previous.accountId.isEmpty() && !next.accountId.isEmpty()
                    && !previous.accountId.equals(next.accountId)) return next;
            boolean sameWork = compatible(next.awemeId, previous.awemeId);
            boolean sameConversation = compatible(next.conversationId, previous.conversationId);
            boolean confirmedReply = COMMENT_REPLY.equals(kind) && sameWork && !previous.messageId.isEmpty()
                    && !previous.commentId.isEmpty() && !previous.commentId.equals(previous.messageId);
            boolean incomingFallback = !next.messageId.isEmpty() && next.commentId.equals(next.messageId);
            // A dispatcher's old input location may arrive after the owned success receipt.
            String commentId = confirmedReply && incomingFallback ? previous.commentId
                    : prefer(next.commentId, sameWork ? previous.commentId : "");
            return new Location(prefer(next.accountId, previous.accountId), prefer(next.awemeId, previous.awemeId),
                    commentId,
                    prefer(next.rootCommentId, sameWork ? previous.rootCommentId : ""),
                    prefer(next.conversationId, previous.conversationId),
                    prefer(next.messageId, sameConversation ? previous.messageId : ""),
                    next.conversationType >= 0 ? next.conversationType : sameConversation ? previous.conversationType : -1,
                    prefer(next.peerUid, sameConversation ? previous.peerUid : ""));
        }

        private static String prefer(String next, String previous) { return next.isEmpty() ? previous : next; }
        private static boolean compatible(String next, String previous) {
            return next.isEmpty() || previous.isEmpty() || next.equals(previous);
        }
    }

    public static final class Entry {
        public final String id;
        public final String kind;
        public final String targetId;
        public final String targetName;
        public final String input;
        public final String output;
        public final String modelName;
        public final String status;
        public final long createdAt;
        public final long updatedAt;
        public final int requestImageCount;
        public final boolean modelRequestSent;
        public final String deliveryMode;
        public final Reason reason;
        public final Stage stage;
        public final long queueMillis;
        public final long mediaMillis;
        public final long generationMillis;
        public final long sendMillis;
        public final Location location;

        public Entry(String id, String kind, String targetId, String targetName,
                     String input, String output, String modelName, String status,
                     long createdAt, long updatedAt) {
            this(id, kind, targetId, targetName, input, output, modelName, status,
                    createdAt, updatedAt, -1, false, DELIVERY_UNKNOWN);
        }

        public Entry(String id, String kind, String targetId, String targetName,
                     String input, String output, String modelName, String status,
                     long createdAt, long updatedAt, String deliveryMode) {
            this(id, kind, targetId, targetName, input, output, modelName, status,
                    createdAt, updatedAt, -1, false, deliveryMode);
        }

        public Entry(String id, String kind, String targetId, String targetName,
                     String input, String output, String modelName, String status,
                     long createdAt, long updatedAt, int requestImageCount, boolean modelRequestSent) {
            this(id, kind, targetId, targetName, input, output, modelName, status,
                    createdAt, updatedAt, requestImageCount, modelRequestSent, DELIVERY_UNKNOWN);
        }

        public Entry(String id, String kind, String targetId, String targetName,
                     String input, String output, String modelName, String status,
                     long createdAt, long updatedAt, int requestImageCount, boolean modelRequestSent,
                     String deliveryMode) {
            this(id, kind, targetId, targetName, input, output, modelName, status, createdAt, updatedAt,
                    requestImageCount, modelRequestSent, deliveryMode, Reason.NONE, Stage.NONE, -1, -1, -1, -1,
                    Location.EMPTY);
        }

        private Entry(String id, String kind, String targetId, String targetName,
                      String input, String output, String modelName, String status,
                      long createdAt, long updatedAt, int requestImageCount, boolean modelRequestSent,
                      String deliveryMode, Reason reason, Stage stage,
                      long queueMillis, long mediaMillis, long generationMillis, long sendMillis, Location location) {
            requireKind(kind);
            if (id == null || id.isEmpty()) throw new IllegalArgumentException("Entry ID is required");
            if (!"pending".equals(status) && !"cancelled".equals(status) && !"sent".equals(status)
                    && !"submitted".equals(status) && !"failed".equals(status) && !"sending".equals(status)
                    && !"unknown".equals(status)) {
                throw new IllegalArgumentException("Unknown automation status");
            }
            this.id = bounded(id, 512);
            this.kind = kind;
            this.targetId = bounded(targetId, 512);
            this.targetName = bounded(targetName, 256);
            this.input = bounded(input, 8000);
            this.output = bounded(output, 4000);
            this.modelName = bounded(modelName, 256);
            this.status = status;
            this.createdAt = Math.max(0, createdAt);
            this.updatedAt = Math.max(this.createdAt, updatedAt);
            this.requestImageCount = Math.max(-1, Math.min(4, requestImageCount));
            this.modelRequestSent = this.requestImageCount >= 0 && modelRequestSent;
            this.deliveryMode = DELIVERY_TEXT.equals(deliveryMode) || DELIVERY_VOICE.equals(deliveryMode)
                    ? deliveryMode : DELIVERY_UNKNOWN;
            this.reason = reason == null ? Reason.NONE : reason;
            this.stage = stage == null ? Stage.NONE : stage;
            this.queueMillis = boundedMillis(queueMillis);
            this.mediaMillis = boundedMillis(mediaMillis);
            this.generationMillis = boundedMillis(generationMillis);
            this.sendMillis = boundedMillis(sendMillis);
            this.location = location == null ? Location.EMPTY : location;
        }

        /** Adds only controlled diagnostics; delivery status and existing content are unchanged. */
        public Entry withDiagnostics(Reason reason, Stage stage, long queueMillis, long mediaMillis,
                                     long generationMillis, long sendMillis) {
            return new Entry(id, kind, targetId, targetName, input, output, modelName, status,
                    createdAt, updatedAt, requestImageCount, modelRequestSent, deliveryMode,
                    reason, stage, queueMillis, mediaMillis, generationMillis, sendMillis, location);
        }

        /** Retains all delivery and diagnostic state while adding a captured navigation location. */
        public Entry withLocation(Location location) {
            return new Entry(id, kind, targetId, targetName, input, output, modelName, status,
                    createdAt, updatedAt, requestImageCount, modelRequestSent, deliveryMode,
                    reason, stage, queueMillis, mediaMillis, generationMillis, sendMillis, location);
        }
    }
}
