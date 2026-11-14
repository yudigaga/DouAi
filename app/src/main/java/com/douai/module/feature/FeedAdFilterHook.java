package com.douai.module.feature;

import com.douai.module.config.FeatureSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import org.json.JSONObject;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** Filters Aweme entries from the main feed response only. */
public final class FeedAdFilterHook implements AutoCloseable {
    private final Field itemsField;
    private final List<Field> responseLists = new ArrayList<>();
    private final Class<?> feedItemListClass;
    private final Class<?> awemeClass;
    private final Method adMarker;
    private final Method nearbyAdMarker;
    private final Method lifeSpuMarker;
    private final Method liveMarker;
    private final Method imageMarker;
    private final Method awemeType;
    private final Field anchorInfoField;
    private final Field descField;
    private final Field durationField;
    private final Field statisticsField;
    private final Field diggCountField;
    private final Method responseUrl;
    private final List<XposedInterface.HookHandle> handles;
    private final Consumer<String> log;
    private final AtomicInteger filteredPages = new AtomicInteger();
    private volatile boolean enabled;
    private volatile FeatureSettings settings;
    private volatile LocalOptions options = new LocalOptions(Collections.emptyMap());

    private FeedAdFilterHook(Field itemsField, Class<?> feedItemListClass, Class<?> awemeClass,
                             Method adMarker, Method nearbyAdMarker, Method lifeSpuMarker,
                             Method liveMarker, Method imageMarker, Method awemeType,
                             Field anchorInfoField, Field descField, Field durationField,
                             Field statisticsField, Field diggCountField, Method responseUrl,
                             List<XposedInterface.HookHandle> handles, FeatureSettings settings,
                             boolean enabled, Consumer<String> log) {
        this.itemsField = itemsField;
        responseLists.add(itemsField);
        for (String name : new String[]{"preloadAds", "preloadAwemes"}) {
            try {
                Field field = feedItemListClass.getField(name);
                if (List.class.isAssignableFrom(field.getType())) responseLists.add(field);
            } catch (NoSuchFieldException ignored) {
                // Optional preload lists differ across host versions.
            }
        }
        this.feedItemListClass = feedItemListClass;
        this.awemeClass = awemeClass;
        this.adMarker = adMarker;
        this.nearbyAdMarker = nearbyAdMarker;
        this.lifeSpuMarker = lifeSpuMarker;
        this.liveMarker = liveMarker;
        this.imageMarker = imageMarker;
        this.awemeType = awemeType;
        this.anchorInfoField = anchorInfoField;
        this.descField = descField;
        this.durationField = durationField;
        this.statisticsField = statisticsField;
        this.diggCountField = diggCountField;
        this.responseUrl = responseUrl;
        this.handles = new ArrayList<>(handles);
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
    }

    public static FeedAdFilterHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, boolean enabled,
                                           Consumer<String> log) throws Exception {
        return installInternal(framework, report, loader, legacyAdSettings(), enabled, log);
    }

    public static FeedAdFilterHook install(XposedInterface framework, HostScanReport report,
                                           ClassLoader loader, FeatureSettings settings,
                                           Consumer<String> log) throws Exception {
        FeatureSettings current = settings == null ? FeatureSettings.defaults() : settings;
        return installInternal(framework, report, loader, current, current.feedFilterEnabled, log);
    }

    private static FeedAdFilterHook installInternal(XposedInterface framework, HostScanReport report,
                                                    ClassLoader loader, FeatureSettings settings,
                                                    boolean enabled, Consumer<String> log) throws Exception {
        Method mainFeedApi = resolveMethod(report, "main_feed_api_v1", loader);
        if (!"bolts.Task".equals(mainFeedApi.getReturnType().getName())) {
            throw new IllegalStateException("Main feed API return contract changed");
        }
        Method success = resolveMethod(report, "retrofit_success_response", loader);
        if (!Modifier.isStatic(success.getModifiers())) {
            throw new IllegalStateException("Retrofit success wrapper is no longer static");
        }
        Method responseUrl = resolveMethod(report, "retrofit_response_url", loader);
        Method adMarker = resolveMethod(report, "aweme_ad_flag", loader);
        Method liveMarker = resolveMethod(report, "aweme_is_live", loader);
        Method imageMarker = resolveMethod(report, "aweme_is_image", loader);
        Method awemeType = resolveMethod(report, "aweme_type", loader);
        Class<?> feedItemList = resolveClass(report, "main_feed_response_model", loader);
        Class<?> aweme = resolveClass(report, "aweme_feed_attributes", loader);
        Method nearbyAdMarker = aweme.getMethod("isAdNearbyCard");
        Method lifeSpuMarker = aweme.getMethod("isLifeSpuCard");
        Class<?> statistics = resolveClass(report, "aweme_statistics_digg_count", loader);
        Field items = feedItemList.getField("items");
        if (!List.class.isAssignableFrom(items.getType())) {
            throw new IllegalStateException("FeedItemList.items is not a List");
        }
        if (adMarker.getDeclaringClass() != aweme || liveMarker.getDeclaringClass() != aweme
                || imageMarker.getDeclaringClass() != aweme
                || adMarker.getReturnType() != boolean.class || liveMarker.getReturnType() != boolean.class
                || imageMarker.getReturnType() != boolean.class || awemeType.getReturnType() != int.class
                || nearbyAdMarker.getReturnType() != boolean.class || lifeSpuMarker.getReturnType() != boolean.class
                || Modifier.isStatic(nearbyAdMarker.getModifiers()) || Modifier.isStatic(lifeSpuMarker.getModifiers())
                || responseUrl.getReturnType() != String.class) {
            throw new IllegalStateException("Feed filtering method contract changed");
        }
        Field anchorInfo = aweme.getField("anchorInfo");
        Field desc = aweme.getField("desc");
        Field duration = aweme.getField("duration");
        Field itemStatistics = aweme.getField("statistics");
        Field diggCount = statistics.getField("d");
        if (desc.getType() != String.class || duration.getType() != Integer.class
                || itemStatistics.getType() != statistics || diggCount.getType() != long.class) {
            throw new IllegalStateException("Feed model field contract changed");
        }
        responseUrl.setAccessible(true);
        adMarker.setAccessible(true);
        nearbyAdMarker.setAccessible(true);
        lifeSpuMarker.setAccessible(true);
        liveMarker.setAccessible(true);
        imageMarker.setAccessible(true);
        awemeType.setAccessible(true);
        anchorInfo.setAccessible(true);
        desc.setAccessible(true);
        duration.setAccessible(true);
        itemStatistics.setAccessible(true);
        diggCount.setAccessible(true);

        FeedAdFilterHook hook = new FeedAdFilterHook(items, feedItemList, aweme, adMarker,
                nearbyAdMarker, lifeSpuMarker, liveMarker, imageMarker, awemeType,
                anchorInfo, desc, duration, itemStatistics, diggCount,
                responseUrl, Collections.emptyList(), settings, enabled,
                log == null ? ignored -> { } : log);
        try {
            XposedInterface.HookHandle handle = framework.hook(success)
                    .setId("DouAi.Feed.ResponseFilter")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                    .intercept(hook::intercept);
            if (handle == null) throw new IllegalStateException("Framework returned an empty hook handle");
            hook.handles.add(handle);
            return hook;
        } catch (Throwable failure) {
            unhookAll(hook.handles, failure);
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public void setSettings(FeatureSettings settings) {
        FeatureSettings current = settings == null ? FeatureSettings.defaults() : settings;
        this.settings = current;
        this.enabled = current.feedFilterEnabled;
    }

    public void setOptions(LocalOptions options) {
        this.options = options == null ? new LocalOptions(Collections.emptyMap()) : options;
    }

    public int getFilteredPages() {
        return filteredPages.get();
    }

    private Object intercept(XposedInterface.Chain chain) throws Throwable {
        FeatureSettings current = settings;
        if (!enabled || current == null || !current.feedFilterEnabled || !hasActiveFilter(current, options)) {
            DouAiLog.event("feed_filter", "decision", "disabled_or_no_active_filter", "host_response_forwarded=true");
            return chain.proceed();
        }
        Object body = chain.getArg(0);
        Object rawResponse = chain.getArg(1);
        if (body == null || rawResponse == null || !feedItemListClass.isInstance(body)) {
            DouAiLog.event("feed_filter", "decision", "response_not_applicable", "host_response_forwarded=true");
            return chain.proceed();
        }

        try {
            String url = (String) responseUrl.invoke(rawResponse);
            if (!FeedResponseFilter.isMainFeedUrl(url)) {
                DouAiLog.event("feed_filter", "decision", "not_main_feed_endpoint", "host_response_forwarded=true");
                return chain.proceed();
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            DouAiLog.failure("feed_filter", "response_endpoint_inspection", failure);
            return chain.proceed();
        }

        DouAiLog.Operation operation = DouAiLog.begin("feed_filter", "main_feed_response");
        operation.stage("classify", "list_fields=" + responseLists.size());

        ArrayList<ListChange> changes = new ArrayList<>();
        int removed = 0;
        FilterEvidence counts = new FilterEvidence();
        try {
            for (Field field : responseLists) {
                Object value = field.get(body);
                if (!(value instanceof List<?>)) continue;
                @SuppressWarnings("unchecked")
                List<Object> source = (List<Object>) value;
                int input = source.size();
                FilterEvidence listCounts = new FilterEvidence();
                listCounts.input = input;
                boolean allowEmpty = field != itemsField || !options.enabled("filter_interrupt", true);
                List<Object> filtered = FeedResponseFilter.filter(source, true,
                        item -> {
                            try {
                                FeedFilterPolicy.Reason reason = hideReason(item, current);
                                listCounts.classified++;
                                if (reason.hides()) { listCounts.matched++; listCounts.rules.merge(reason, 1, Integer::sum); }
                                return reason.hides();
                            } catch (Exception unavailable) {
                                listCounts.classificationFailures++;
                                operation.error("classify_item", unavailable);
                                throw unavailable; // FeedResponseFilter keeps its existing fail-open behavior.
                            }
                        }, allowEmpty);
                listCounts.output = source == filtered ? input : filtered.size();
                listCounts.removed = input - listCounts.output;
                if (!allowEmpty && input > 0 && source == filtered && listCounts.classificationFailures == 0
                        && listCounts.matched == input) { listCounts.emptyGuards++; listCounts.emptyGuardRetained += input; }
                counts.add(listCounts);
                if (source != filtered) {
                    changes.add(new ListChange(field, source, filtered));
                    removed += listCounts.removed;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            operation.error("classify", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "classification_failed", "host_response_forwarded=true;mutated=false;counts_complete=false;" + counts.summary(false));
            return chain.proceed();
        }
        if (changes.isEmpty()) {
            String reason = counts.classificationFailures > 0 ? "classification_unavailable_fail_open"
                    : counts.emptyGuards > 0 ? "empty_page_guard" : counts.input == 0 ? "empty_input" : "no_items_matched";
            operation.finish(counts.classificationFailures > 0 ? DouAiLog.Outcome.UNCONFIRMED : DouAiLog.Outcome.SKIPPED,
                    reason, "lists_changed=0;host_response_forwarded=true;" + counts.summary(true));
            return chain.proceed();
        }
        operation.stage("mutate_response", "lists_changed=" + changes.size() + ";counts_scope=planned_response_lists;" + counts.summary(true));
        try {
            for (ListChange change : changes) change.field.set(body, change.filtered);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            rollbackLists(body, changes, failure);
            operation.error("mutate_response", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "mutation_failed", "rollback_attempted=true;host_response_forwarded=true;" + counts.summary(false));
            return chain.proceed();
        }
        try {
            Object result = chain.proceed();
            boolean readback = readbackChanges(body, changes);
            operation.finish(readback ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED,
                    readback ? "response_list_readback" : "response_changed_after_forwarding",
                    "lists_changed=" + changes.size() + ";readback=" + readback + ";" + counts.summary(readback)
                            + ";result_scope=response_lists;readback_basis=changed_list_reference_only;post_forward_counts=unobserved;rendered_feed=unobserved");
            int page = filteredPages.incrementAndGet();
            // One successful filtered response, irrespective of how many overlapping preload lists it has.
            com.douai.module.analytics.LocalAnalytics.feature("feed_filter", "内容过滤生效批次");
            if (page <= 5) {
                safeLog("Removed " + removed + " entries from feed and preload response lists");
            }
            return result;
        } catch (Throwable failure) {
            operation.error("forward_response", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "response_rejected", "rollback_attempted=true;" + counts.summary(false));
            rollbackLists(body, changes, failure);
            throw failure;
        }
    }

    private static boolean readbackChanges(Object body, List<ListChange> changes) {
        try {
            for (ListChange change : changes) if (change.field.get(body) != change.filtered) return false;
            return true;
        } catch (Throwable unavailable) { return false; }
    }

    private FeedFilterPolicy.Reason hideReason(Object item, FeatureSettings current) throws Exception {
        if (!awemeClass.isInstance(item)) {
            throw new IllegalArgumentException("Feed list contains a non-Aweme value");
        }
        boolean ad = current.hideAds && (Boolean.TRUE.equals(adMarker.invoke(item))
                || Boolean.TRUE.equals(nearbyAdMarker.invoke(item))
                || Boolean.TRUE.equals(HostTargets.read(HostTargets.read(item, "author"), "isAdFake")));
        boolean live = current.hideLives && Boolean.TRUE.equals(liveMarker.invoke(item));
        int type = ((Number) awemeType.invoke(item)).intValue();
        boolean image = current.hideImagePosts
                && (Boolean.TRUE.equals(imageMarker.invoke(item)) || type == 68 || type == 163);
        boolean recommendationCard = type == 145;
        boolean recommendationMerchant = current.hideRecommendationMerchants && type == 140
                && Boolean.TRUE.equals(lifeSpuMarker.invoke(item));
        String desc = current.keywordFilterEnabled ? searchableText(item, (String) descField.get(item)) : "";
        long durationMillis = -1;
        if (current.minimumDurationEnabled || options.enabled("hide_long_videos")) {
            Object rawDuration = durationField.get(item);
            if (rawDuration instanceof Number) durationMillis = ((Number) rawDuration).longValue();
            if (durationMillis <= 0) {
                Object video = HostTargets.read(item, "video");
                rawDuration = HostTargets.read(video, "videoLength");
                if (!(rawDuration instanceof Number)) rawDuration = HostTargets.read(video, "duration");
                if (rawDuration instanceof Number) durationMillis = ((Number) rawDuration).longValue();
            }
        }
        long likeCount = -1;
        if (current.minimumLikesEnabled) {
            Object statistics = statisticsField.get(item);
            if (statistics != null) likeCount = diggCountField.getLong(statistics);
        }
        FeedItem attributes = new FeedItem(ad, live, image,
                recommendationCard, desc, durationMillis, likeCount);
        return FeedFilterPolicy.reason(attributes, current,
                recommendationCard, recommendationMerchant,
                isMiniGame(anchorInfoField.get(item)), type, options);
    }

    static String searchableText(Object aweme, String description) {
        StringBuilder text = new StringBuilder(description == null ? "" : description);
        appendText(text, HostTargets.read(aweme, "title"));
        appendText(text, HostTargets.read(HostTargets.read(aweme, "author"), "nickname"));
        Object music = HostTargets.read(aweme, "music");
        for (String key : new String[]{"musicName", "music_name", "title"}) {
            appendText(text, HostTargets.read(music, key));
        }
        return text.toString();
    }

    private static void appendText(StringBuilder text, Object value) {
        if (value instanceof String) {
            String part = (String) value;
            text.append('\n').append(part, 0, Math.min(4096, part.length()));
        }
    }

    static boolean isMiniGame(Object anchor) {
        if (anchor == null) return false;
        for (String key : new String[]{"extra", "logExtra", "url", "openUrl", "schema", "title"}) {
            Object value = HostTargets.read(anchor, key);
            if (!(value instanceof String)) continue;
            String text = ((String) value).toLowerCase(java.util.Locale.ROOT);
            if (text.contains("micro_game") || text.contains("mini_game")
                    || text.contains("minigame") || text.contains("小游戏")) return true;
            if ("extra".equals(key)) {
                try {
                    JSONObject extra = new JSONObject((String) value);
                    if (extra.optBoolean("is_game", false)
                            || !extra.optString("game_id", "").isEmpty()) return true;
                } catch (org.json.JSONException ignored) { }
            }
        }
        return false;
    }

    private static void rollbackLists(Object body, List<ListChange> changes, Throwable failure) {
        for (ListChange change : changes) {
            try {
                change.field.set(body, change.original);
            } catch (ReflectiveOperationException | RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static final class ListChange {
        final Field field;
        final List<Object> original;
        final List<Object> filtered;

        ListChange(Field field, List<Object> original, List<Object> filtered) {
            this.field = field;
            this.original = original;
            this.filtered = filtered;
        }
    }

    private static final class FilterEvidence {
        int input, matched, removed, output, classified, classificationFailures, emptyGuards, emptyGuardRetained, lists;
        final EnumMap<FeedFilterPolicy.Reason, Integer> rules = new EnumMap<>(FeedFilterPolicy.Reason.class);
        void add(FilterEvidence item) {
            lists++; input += item.input; matched += item.matched; removed += item.removed; output += item.output;
            classified += item.classified; classificationFailures += item.classificationFailures;
            emptyGuards += item.emptyGuards; emptyGuardRetained += item.emptyGuardRetained;
            for (java.util.Map.Entry<FeedFilterPolicy.Reason, Integer> rule : item.rules.entrySet()) rules.merge(rule.getKey(), rule.getValue(), Integer::sum);
        }
        String summary(boolean classifiedSnapshotApplies) {
            StringBuilder evidence = new StringBuilder("input=").append(input).append(";matched=").append(matched);
            if (classifiedSnapshotApplies) evidence.append(";removed=").append(removed).append(";retained=").append(input - removed).append(";output=").append(output);
            else evidence.append(";removed=unconfirmed;retained=unconfirmed;output=unconfirmed;planned_removed=").append(removed).append(";planned_output=").append(output);
            evidence.append(";empty_guard_count=").append(emptyGuards).append(";empty_guard_retained=").append(emptyGuardRetained)
                    .append(";classified=").append(classified).append(";classification_failures=").append(classificationFailures)
                    .append(";matched_basis=").append(classificationFailures == 0 ? "complete_classification" : "observed_before_fail_open")
                    .append(";lists=").append(lists).append(";count_scope=response_list_entries_not_unique_items;counts_basis=classification_snapshot;rule_matches={");
            boolean first = true;
            for (java.util.Map.Entry<FeedFilterPolicy.Reason, Integer> rule : rules.entrySet()) {
                if (!first) evidence.append(','); first = false;
                evidence.append(rule.getKey().name().toLowerCase(java.util.Locale.ROOT)).append('=').append(rule.getValue());
            }
            return evidence.append("};rule_count_basis=first_matching_rule_per_entry").toString();
        }
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable unhookFailure) {
                if (failure == null) failure = unhookFailure;
                else failure.addSuppressed(unhookFailure);
            }
        }
        if (failure != null) safeLog("Feed filter unhook failed: " + failure.getMessage());
    }

    private void safeLog(String message) {
        try {
            log.accept(message);
        } catch (Throwable ignored) {
            // Logging must never change the host response path.
        }
    }

    private static void unhookAll(List<XposedInterface.HookHandle> handles, Throwable failure) {
        for (int index = handles.size() - 1; index >= 0; index--) {
            try {
                handles.get(index).unhook();
            } catch (Throwable rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
        }
    }

    private static FeatureSettings legacyAdSettings() {
        return new FeatureSettings(false, false, true, true, false, false, false,
                false, "", false, 0, false, 0,
                false, false, false, false, false);
    }

    private static boolean hasActiveFilter(FeatureSettings settings, LocalOptions options) {
        // hide_seen_works is an active feed option at the module level, but cannot run on a
        // generic Retrofit response. SeenWorkHook handles the originating recommendation callable.
        return settings.hideAds || settings.hideLives || settings.hideImagePosts
                || settings.hideRecommendationCards || settings.hideRecommendationMerchants
                || settings.keywordFilterEnabled
                || settings.minimumDurationEnabled || settings.minimumLikesEnabled
                || options.enabled("hide_product_cards") || options.enabled("hide_hotspot_rank_cards")
                || options.enabled("hide_mini_games") || options.enabled("hide_long_videos");
    }

    private static Class<?> resolveClass(HostScanReport report, String ruleId,
                                         ClassLoader loader) throws ClassNotFoundException {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        if (!candidate.getMethodName().isEmpty()) {
            throw new IllegalStateException("Expected a class rule: " + ruleId);
        }
        return Class.forName(className(candidate.getClassDescriptor()), false, loader);
    }

    private static Method resolveMethod(HostScanReport report, String ruleId,
                                        ClassLoader loader) throws Exception {
        HostCandidate candidate = uniqueCandidate(report, ruleId);
        Class<?> owner = Class.forName(className(candidate.getClassDescriptor()), false, loader);
        Method match = null;
        for (Method method : owner.getDeclaredMethods()) {
            if (!method.getName().equals(candidate.getMethodName())
                    || !methodDescriptor(method).equals(candidate.getMethodDescriptor())) continue;
            if (match != null) throw new IllegalStateException("Reflection result is ambiguous: " + ruleId);
            match = method;
        }
        if (match == null) throw new NoSuchMethodException("Scanned method is not loadable: " + ruleId);
        return match;
    }

    private static HostCandidate uniqueCandidate(HostScanReport report, String ruleId) {
        RuleScanResult result = report == null ? null : report.getResults().get(ruleId);
        if (result == null || result.getStatus() != RuleScanResult.Status.UNIQUE) {
            throw new IllegalStateException("Host rule is not unique: " + ruleId);
        }
        return result.getCandidates().get(0);
    }

    private static String className(String descriptor) {
        if (descriptor == null || descriptor.length() < 3
                || descriptor.charAt(0) != 'L' || descriptor.charAt(descriptor.length() - 1) != ';') {
            throw new IllegalArgumentException("Invalid class descriptor: " + descriptor);
        }
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String methodDescriptor(Method method) {
        StringBuilder descriptor = new StringBuilder("(");
        for (Class<?> parameter : method.getParameterTypes()) descriptor.append(typeDescriptor(parameter));
        return descriptor.append(')').append(typeDescriptor(method.getReturnType())).toString();
    }

    private static String typeDescriptor(Class<?> type) {
        if (type.isArray()) return type.getName().replace('.', '/');
        if (!type.isPrimitive()) return "L" + type.getName().replace('.', '/') + ";";
        if (type == void.class) return "V";
        if (type == boolean.class) return "Z";
        if (type == byte.class) return "B";
        if (type == char.class) return "C";
        if (type == short.class) return "S";
        if (type == int.class) return "I";
        if (type == long.class) return "J";
        if (type == float.class) return "F";
        if (type == double.class) return "D";
        throw new IllegalArgumentException("Unsupported primitive type: " + type);
    }
}
