package com.douai.module.feature;

import android.content.Context;

import com.douai.module.analytics.LocalAnalytics;

/** Captures only local attribution, so delayed actions cannot borrow a later card or recording session. */
final class AnalyticsActionCapture {
    private static final AnalyticsActionCapture NONE = new AnalyticsActionCapture();
    private final LocalAnalytics store;
    private final long generation;
    private final String workId;
    private final String visitToken;

    private AnalyticsActionCapture() {
        store = null; generation = -1; workId = null; visitToken = null;
    }

    private AnalyticsActionCapture(Context context, Object work) {
        store = LocalAnalytics.get(context);
        generation = store.generation();
        Object id = AnalyticsAwemeReader.value(work, "getAid", "aid", "aweme_id");
        workId = id instanceof String ? (String) id : null;
        visitToken = store.currentVisitToken(workId);
    }

    static AnalyticsActionCapture capture(Context context, Object work) {
        try { return new AnalyticsActionCapture(context, work); }
        catch (RuntimeException ignored) { return NONE; }
    }

    com.douai.module.analytics.LocalAnalytics.FlowOperation operation(String kind) {
        if (store == null || generation != store.generation()) return null;
        return store.beginOperation(generation, visitToken, kind);
    }

    void manual(String key, String label, String stage) {
        if (store == null) return;
        try { store.recordAction(generation, visitToken, workId, key, label, "manual", stage); }
        catch (RuntimeException ignored) { /* Observation failure must not cancel the actual operation. */ }
    }
}
