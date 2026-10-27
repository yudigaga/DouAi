package com.douai.module.config;

import org.json.JSONException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** Controlled backup diagnostics. Never retains imported values or the original exception. */
public final class BackupValidationException extends IllegalArgumentException {
    public enum Section { BUNDLE_JSON, BUNDLE_HEADER, CONFIG, ANALYTICS }
    public enum FailureKind { EXPLICIT, JSON, ARGUMENT, TYPE, SECURITY, UNSUPPORTED, LINKAGE, OTHER }
    public enum Reason {
        INVALID_JSON, HEADER_SHAPE, FORMAT_VERSION, MODULE, UNKNOWN_KEY, TYPE_MISMATCH,
        STRUCTURED_HEADERS, STRUCTURED_MODELS, STRUCTURED_COMPONENTS, STRUCTURED_FIELDS,
        SECRET_POLICY, CURRENT_STORE, MERGED_SETTINGS, MINIMAX_OPTIONS, BREEZE_OPTIONS, FISH_OPTIONS,
        ANALYTICS_VERSION, CAPTURED_AT_RANGE, ANALYTICS_CELL, UNEXPECTED
    }

    private static final Set<String> FIXED_FIELDS = fixedFields();

    private static Set<String> fixedFields() {
        Set<String> fields = new HashSet<>(Arrays.asList(
            "backupFormatVersion", "kind", "config", "analytics", "formatVersion", "module",
            "applicationId", "versionName", "versionCode", "includesSecrets", "values",
            "version", "capturedAt", "tables", "context", "purpose", "feedback", "context.purpose", "context.feedback",
            "daily", "hours", "work_days", "ranks", "recent", "meta", "visits", "visit_topics",
            "actions", "collection_boundaries", "day", "views", "features", "ads", "content_duration",
            "hour", "count", "work_hash", "item_key", "label", "id", "ts", "key", "value",
            "token", "type", "source", "started", "visible_ms", "ended", "end_reason", "topic",
            "feature", "stage", "enabled"));
        for (String schema : new String[]{
                "daily:day,views,features,ads,content_duration", "hours:day,hour,count", "work_days:day,work_hash",
                "ranks:day,kind,item_key,label,count", "recent:id,ts,label,kind", "meta:key,value",
                "visits:token,work_hash,type,day,hour,source,started,visible_ms,ended,end_reason",
                "visit_topics:token,topic", "actions:id,day,token,feature,source,stage,ts",
                "collection_boundaries:id,ts,enabled"}) {
            String[] pieces = schema.split(":", 2);
            for (String column : pieces[1].split(",")) fields.add(pieces[0] + "." + column);
        }
        return Collections.unmodifiableSet(fields);
    }

    public final Section section;
    public final Reason reason;
    public final FailureKind failureKind;
    /** Empty when the failing field is unregistered or caller supplied no field. */
    public final String field;

    public BackupValidationException(Section section, Reason reason, String field) {
        this(section, reason, field, FailureKind.EXPLICIT);
    }

    private BackupValidationException(Section section, Reason reason, String field, FailureKind failureKind) {
        super(code(section, reason));
        this.section = section;
        this.reason = reason;
        this.field = knownField(field);
        this.failureKind = failureKind;
    }

    /** Preserve established limits and any more specific diagnostic from a lower validation layer. */
    public static IllegalArgumentException wrap(Section section, Reason reason, String field, Throwable failure) {
        if (failure instanceof BackupLimitException) return (BackupLimitException) failure;
        if (failure instanceof BackupValidationException) return (BackupValidationException) failure;
        return new BackupValidationException(section, reason, field, failureKind(failure));
    }

    public String diagnosticCode() { return code(section, reason); }

    private static String code(Section section, Reason reason) {
        if (section == null || reason == null) throw new IllegalArgumentException("Missing backup diagnostic category");
        return section.name() + "/" + reason.name();
    }

    private static String knownField(String field) {
        if (field == null || field.isEmpty()) return "";
        return FIXED_FIELDS.contains(field) || ConfigKeys.TYPES.containsKey(field) ? field : "";
    }

    private static FailureKind failureKind(Throwable failure) {
        if (failure instanceof JSONException) return FailureKind.JSON;
        if (failure instanceof ClassCastException || failure instanceof ArrayStoreException) return FailureKind.TYPE;
        if (failure instanceof SecurityException) return FailureKind.SECURITY;
        if (failure instanceof UnsupportedOperationException) return FailureKind.UNSUPPORTED;
        if (failure instanceof LinkageError) return FailureKind.LINKAGE;
        if (failure instanceof IllegalArgumentException) return FailureKind.ARGUMENT;
        return FailureKind.OTHER;
    }
}
