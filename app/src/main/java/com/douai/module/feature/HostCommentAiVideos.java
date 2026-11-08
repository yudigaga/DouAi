package com.douai.module.feature;

import android.os.Looper;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Inserts real search works through 41.0's existing C2 video card path. */
final class HostCommentAiVideos {
    private static final int MAX_CARDS = 5;
    private final Class<?> managerType, awemeType, wrapperType, modelType;
    private final Field ledger, currentConversation, currentRound, roundConversation, roundRank;
    private final Field roundStart, roundAiCount, roundHasFeed, roundSearchId, managerRequestRank;
    private final Field context, awemeId, wrapperWorks, resultWrapper, resultSuccess, modelData;
    private final Field feedHolder, nativeFeed;
    private final Method lookup, data, insert, gsonProvider, getGson, fromJson, wrapWorks, refresh, decorate;
    private final Constructor<?> eventConstructor, resultConstructor;
    private final Object dcFeedCard;

    private HostCommentAiVideos(ClassLoader loader) throws ReflectiveOperationException {
        managerType = type(loader, "X.1B3z");
        awemeType = type(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
        wrapperType = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.ai.FeedWrapper");
        modelType = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.model.C2ArchModel");
        Class<?> ledgerType = type(loader, "X.1B4J"), conversationType = type(loader, "X.1B3a");
        Class<?> roundType = type(loader, "X.1B3p"), contextType = type(loader,
                "com.ss.android.ugc.aweme.c2page.common.newarchitecture.context.IC2ArchContext");
        Class<?> composerType = type(loader, "com.ss.android.ugc.aweme.c2page.impl.newarchitecture.C2ComposerImpl");
        Class<?> dataType = type(loader, "com.ss.android.ugc.aweme.c2page.common.context.IC2DataContext");
        if (!List.class.isAssignableFrom(dataType) || !contextType.isAssignableFrom(composerType)) throw changed();
        ledger = field(managerType, "LIZ", ledgerType);
        context = field(managerType, "LIZIZ", contextType);
        currentConversation = field(ledgerType, "LIZIZ", conversationType);
        currentRound = field(conversationType, "LIZJ", roundType);
        roundConversation = field(roundType, "LIZ", String.class);
        roundRank = field(roundType, "LIZIZ", int.class);
        roundStart = field(roundType, "LIZLLL", int.class);
        roundAiCount = field(roundType, "LJ", int.class);
        roundHasFeed = field(roundType, "LJIIIIZZ", boolean.class);
        roundSearchId = field(roundType, "LJI", String.class);
        managerRequestRank = field(managerType, "LIZJ", int.class);
        awemeId = field(awemeType, "aid", String.class);
        wrapperWorks = field(wrapperType, "LJI", List.class);
        modelData = field(modelType, "rawData", Object.class);
        lookup = method(composerType, "LIZJ", Object.class, Class.class);
        data = method(composerType, "LJIIIIZZ", dataType);
        Class<?> holderType = type(loader, "X.1AxW"), feedType = type(loader, "X.1AxX");
        feedHolder = field(composerType, "o", holderType);
        nativeFeed = field(holderType, "LIZ", type(loader, "X.1BrG"));
        refresh = method(feedType, "LJIIJJI", void.class);
        wrapWorks = method(type(loader, "X.1BZ9"), "LIZIZ", List.class, java.util.Collection.class);
        decorate = method(type(loader, "X.1B44"), "LIZ", void.class, awemeType, roundType,
                String.class, long.class, int.class, int.class, Map.class);
        if (!Modifier.isStatic(wrapWorks.getModifiers()) || !Modifier.isStatic(decorate.getModifiers())) throw changed();

        Class<?> eventType = type(loader, "X.1BqP"), eventKind = type(loader, "X.1Ape");
        Class<?> resultType = type(loader, "X.1Bbg");
        Class<?> baseType = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.ai.BaseSSEResponse");
        if (!baseType.isAssignableFrom(wrapperType)) throw changed();
        eventConstructor = eventType.getConstructor(String.class, int.class, eventKind, String.class,
                long.class, long.class, baseType);
        resultConstructor = resultType.getConstructor(eventType);
        resultWrapper = field(resultType, "LIZJ", wrapperType);
        resultSuccess = field(resultType, "LIZLLL", boolean.class);
        insert = method(managerType, "LIZ", void.class, resultType, boolean.class);
        Field kind = eventKind.getField("DC_FEED_CARD");
        if (kind.getType() != eventKind || !Modifier.isStatic(kind.getModifiers())) throw changed();
        dcFeedCard = kind.get(null);
        Class<?> provider = type(loader, "com.ss.android.ugc.aweme.utils.GsonProvider");
        Class<?> gson = type(loader, "com.google.gson.Gson");
        gsonProvider = method(type(loader, "com.ss.android.ugc.aweme.utils.GsonHolder"), "LIZIZ", provider);
        if (!Modifier.isStatic(gsonProvider.getModifiers())) throw changed();
        getGson = method(provider, "getGson", gson);
        fromJson = method(gson, "fromJson", Object.class, String.class, Class.class);
    }

    static HostCommentAiVideos create(ClassLoader loader) throws ReflectiveOperationException {
        return new HostCommentAiVideos(loader);
    }

    /** Returns inserted data-model count; final card rendering and playback remain host-owned. */
    int insert(Object manager, String conversation, int rank, List<Object> awemes) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Native video cards require the main thread");
        if (!managerType.isInstance(manager)) return 0;
        Object owner = context.get(manager);
        Object round = round(manager);
        if (round == null || !matchesSession((String) roundConversation.get(round), roundRank.getInt(round),
                conversation, rank)) return 0;
        Object nativeData = data.invoke(owner);
        if (!(nativeData instanceof List<?>)) throw changed();
        List<?> before = (List<?>) nativeData;
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> existing = new HashSet<>();
        for (Object model : before) {
            Object work = modelType.isInstance(model) ? modelData.get(model) : null;
            if (awemeType.isInstance(work)) {
                identities.add(work);
                existing.add(id(work));
            }
        }
        Object currentWork = lookup.invoke(owner, awemeType);
        List<Object> selected = select(awemes, id(currentWork), existing, this::id);
        if (selected.isEmpty()) return 0;
        Object wrapper = fromJson.invoke(getGson.invoke(gsonProvider.invoke(null)), "{}", wrapperType);
        if (!wrapperType.isInstance(wrapper)) throw changed();
        // Keep the complete host Aweme objects local; they are never serialized to a model provider.
        wrapperWorks.set(wrapper, new ArrayList<>(selected));
        Object event = eventConstructor.newInstance(conversation, rank, dcFeedCard, "{}", 0L, 0L, null);
        Object result = resultConstructor.newInstance(event);
        resultWrapper.set(result, wrapper);
        resultSuccess.setBoolean(result, true);
        insert.invoke(manager, result, false);
        Set<Object> newlyInserted = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<Object> requested = Collections.newSetFromMap(new IdentityHashMap<>());
        requested.addAll(selected);
        Object afterData = data.invoke(owner);
        if (!(afterData instanceof List<?>)) throw changed();
        for (Object model : (List<?>) afterData) {
            Object work = modelType.isInstance(model) ? modelData.get(model) : null;
            if (requested.contains(work) && !identities.contains(work)) newlyInserted.add(work);
        }
        return newlyInserted.size();
    }

    /** Appends one page through the existing C2 model wrapper without changing aiCardCount or scrolling. */
    int append(Object manager, String conversation, int rank, List<Object> awemes) throws Exception {
        if (Looper.myLooper() != Looper.getMainLooper()) throw new IllegalStateException("Native video cards require the main thread");
        if (!managerType.isInstance(manager)) return 0;
        Object owner = context.get(manager);
        Object round = round(manager);
        if (round == null || !matchesSession((String) roundConversation.get(round), roundRank.getInt(round),
                conversation, rank)) return 0;
        Object rawData = data.invoke(owner);
        if (!(rawData instanceof List<?>)) throw changed();
        @SuppressWarnings("unchecked") List<Object> nativeData = (List<Object>) rawData;
        Set<String> existing = new HashSet<>();
        int videoCount = 0;
        int start = roundStart.getInt(round), aiCount = roundAiCount.getInt(round);
        if (!validTail(start, aiCount, nativeData.size())) return 0;
        for (int index = 0; index < nativeData.size(); index++) {
            Object model = nativeData.get(index);
            Object work = modelType.isInstance(model) ? modelData.get(model) : null;
            if (awemeType.isInstance(work)) {
                existing.add(id(work));
                if (index >= start) videoCount++;
            }
        }
        List<Object> selected = select(awemes, id(lookup.invoke(owner, awemeType)), existing, this::id);
        if (selected.isEmpty()) return 0;
        Object holder = feedHolder.get(owner);
        Object feed = holder == null ? null : nativeFeed.get(holder);
        if (feed == null || !refresh.getDeclaringClass().isInstance(feed)) throw changed();
        String searchId = (String) roundSearchId.get(round);
        long now = System.currentTimeMillis();
        for (int index = 0; index < selected.size(); index++) {
            decorate.invoke(null, selected.get(index), round, searchId == null ? "" : searchId,
                    now, videoCount + index + 1, managerRequestRank.getInt(manager),
                    Collections.singletonMap("is_from_sse", 0));
        }
        Object wrapped = wrapWorks.invoke(null, selected);
        if (!(wrapped instanceof List<?>)) throw changed();
        List<?> models = (List<?>) wrapped;
        if (models.size() != selected.size()) throw changed();
        for (int index = 0; index < models.size(); index++) {
            if (!modelType.isInstance(models.get(index)) || modelData.get(models.get(index)) != selected.get(index)) throw changed();
        }
        // The active round is the last conversation round; adding at the tail preserves old item positions.
        int added = appendAtRoundEnd(nativeData, start, aiCount, models);
        if (added > 0) {
            roundHasFeed.setBoolean(round, true);
            refresh.invoke(feed);
        }
        return added;
    }

    static int appendAtRoundEnd(List<Object> data, int start, int aiCount, List<?> next) {
        if (!validTail(start, aiCount, data.size()) || next == null || next.isEmpty()) return 0;
        data.addAll(next);
        return next.size();
    }

    private static boolean validTail(int start, int aiCount, int size) {
        return start >= 0 && aiCount >= 0 && start <= size && aiCount <= size - start;
    }

    private Object round(Object manager) throws IllegalAccessException {
        Object book = ledger.get(manager);
        Object conversation = book == null ? null : currentConversation.get(book);
        return conversation == null ? null : currentRound.get(conversation);
    }

    private String id(Object work) throws IllegalAccessException {
        return awemeType.isInstance(work) ? (String) awemeId.get(work) : "";
    }

    @FunctionalInterface interface IdReader { String id(Object work) throws Exception; }

    static List<Object> select(List<Object> candidates, String currentId, Set<String> existing, IdReader ids) throws Exception {
        if (candidates == null || candidates.isEmpty()) return Collections.emptyList();
        Set<String> seen = new HashSet<>(existing);
        if (currentId != null) seen.add(currentId);
        List<Object> selected = new ArrayList<>();
        for (int index = 0; index < Math.min(50, candidates.size()); index++) {
            Object work = candidates.get(index);
            if (work == null) continue;
            String id = ids.id(work);
            if (id == null || id.trim().isEmpty() || !seen.add(id)) continue;
            selected.add(work);
            if (selected.size() == MAX_CARDS) break;
        }
        return Collections.unmodifiableList(selected);
    }

    static boolean matchesSession(String activeConversation, int activeRank, String conversation, int rank) {
        return conversation != null && !conversation.isEmpty() && rank > 0
                && conversation.equals(activeConversation) && rank == activeRank;
    }

    private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
        return Class.forName(name, false, loader);
    }
    private static Field field(Class<?> owner, String name, Class<?> expected) throws ReflectiveOperationException {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != expected || Modifier.isStatic(field.getModifiers())) throw changed();
        field.setAccessible(true);
        return field;
    }
    private static Method method(Class<?> owner, String name, Class<?> expected, Class<?>... arguments)
            throws ReflectiveOperationException {
        Method method = owner.getDeclaredMethod(name, arguments);
        if (method.getReturnType() != expected) throw changed();
        method.setAccessible(true);
        return method;
    }
    private static IllegalStateException changed() { return new IllegalStateException("Native comment video contract changed"); }
}
