package com.douai.module.feature;

import android.content.Context;
import android.content.SharedPreferences;

import com.douai.module.diagnostics.DouAiLog;

import com.douai.module.scan.HostScanReport;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/** Resolves incomplete notices through the host's own-comment and reply-list read requests. */
final class NativeCommentThreadLookup implements NativeCommentReplyInbox.ThreadLookup {
    private static final int PAGE_SIZE = 20;
    private static final int OWN_PAGES = 5;
    private static final int THREAD_PAGES = 3;
    private static final int ROOTS_PER_ROUND = 8;
    private static final int MAX_ROOTS = 100;
    private static final long ROUND_TIMEOUT = TimeUnit.SECONDS.toNanos(60);
    private static final long ROOT_TTL = TimeUnit.SECONDS.toNanos(60);

    static final class Hint {
        final String noticeId, aid, incomingId, senderUid;
        final long createdAt;
        final Object target;
        Hint(String noticeId, String aid, String incomingId, String senderUid, long createdAt, Object target) {
            this.noticeId = noticeId; this.aid = aid; this.incomingId = incomingId;
            this.senderUid = senderUid; this.createdAt = createdAt; this.target = target;
        }
    }

    static final class Match {
        final Hint hint;
        final Object work, incoming, original;
        Match(Hint hint, Object work, Object incoming, Object original) {
            this.hint = hint; this.work = work; this.incoming = incoming; this.original = original;
        }
    }

    private final Consumer<String> log;
    private final LongSupplier cacheClock;
    private final OwnCursorStore ownCursorStore;
    private final Method ownFetch, threadFetch, contextFactory, workResolver, commentUser;
    private final Method taskWait, taskResult, taskFaulted, taskError, taskCancelled;
    private final Constructor<?> builderConstructor, requestConstructor;
    private final Class<?> ownResponseClass, threadResponseClass, commentClass, noticeClass, workClass, userClass;
    private final Field provider, ownItems, ownMore, ownMin, ownStatus;
    private final Field threadItems, threadMore, threadCursor, threadMerge, threadStatus;
    private final Field payloadComment, payloadWork, payloadInvisible;
    private final Field cid, aid, text, createdAt, rootId, directId, recipient, uid, workAid;
    private final Field builderContext, builderRoot, builderCursor, builderCount, builderMerge, builderComment, builderUid;
    private final Object stateLock = new Object();
    private final LinkedHashMap<String, Root> roots = new LinkedHashMap<>();
    private final Map<String, RootCoverage> coveredRoots = new LinkedHashMap<>();
    private long rootAccount;
    private int rootOffset;
    private boolean ownMorePending;
    private volatile boolean closed;
    private volatile Future<?> activeFuture;

    interface OwnCursorStore {
        long read(long account);
        boolean write(long account, long cursor);
    }

    NativeCommentThreadLookup(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        this(report, loader, log, System::nanoTime, new MemoryOwnCursorStore());
    }

    NativeCommentThreadLookup(HostScanReport report, ClassLoader loader, Consumer<String> log,
                              LongSupplier cacheClock) throws Exception {
        this(report, loader, log, cacheClock, new MemoryOwnCursorStore());
    }

    NativeCommentThreadLookup(HostScanReport report, ClassLoader loader, Consumer<String> log,
                              OwnCursorStore ownCursorStore) throws Exception {
        this(report, loader, log, System::nanoTime, ownCursorStore);
    }

    private NativeCommentThreadLookup(HostScanReport report, ClassLoader loader, Consumer<String> log,
                              LongSupplier cacheClock, OwnCursorStore ownCursorStore) throws Exception {
        this.log = log;
        this.cacheClock = cacheClock;
        this.ownCursorStore = ownCursorStore;
        ownFetch = HostTargets.method(report, "comment_reply_own_fetch", loader);
        Class<?> ownProvider = HostTargets.type(report, "comment_reply_own_provider", loader);
        provider = HostTargets.field(ownProvider, "b");
        if (Modifier.isStatic(ownFetch.getModifiers())
                || !java.util.Arrays.equals(ownFetch.getParameterTypes(), new Class<?>[]{long.class, int.class})
                || !Modifier.isStatic(provider.getModifiers()) || provider.getType() != ownFetch.getDeclaringClass()) {
            throw contract();
        }
        ownResponseClass = HostTargets.type(report, "comment_reply_own_response", loader);
        threadResponseClass = HostTargets.type(report, "comment_reply_thread_response", loader);
        commentClass = HostTargets.type(report, "comment_reply_comment_model", loader);
        noticeClass = HostTargets.type(report, "comment_reply_comment_notice", loader);
        workClass = HostTargets.type(report, "comment_reply_work_model", loader);
        userClass = HostTargets.type(report, "comment_reply_user_model", loader);
        ownItems = field(ownResponseClass, "items", List.class);
        ownMore = field(ownResponseClass, "hasMore", boolean.class);
        ownMin = field(ownResponseClass, "minTime", long.class);
        ownStatus = field(ownResponseClass, "status_code", int.class);
        threadItems = field(threadResponseClass, "items", List.class);
        threadMore = field(threadResponseClass, "hasMore", boolean.class);
        threadCursor = field(threadResponseClass, "cursor", long.class);
        threadMerge = field(threadResponseClass, "mergeCursor", String.class);
        threadStatus = field(threadResponseClass, "status_code", int.class);
        payloadComment = field(noticeClass, "comment", commentClass);
        payloadWork = field(noticeClass, "aweme", workClass);
        payloadInvisible = field(noticeClass, "commentUnvisible", int.class);
        cid = field(commentClass, "cid", String.class);
        aid = field(commentClass, "awemeId", String.class);
        text = field(commentClass, "text", String.class);
        createdAt = field(commentClass, "createTime", long.class);
        rootId = field(commentClass, "replyId", String.class);
        directId = field(commentClass, "replyToReplyId", String.class);
        recipient = field(commentClass, "replyToUserId", String.class);
        uid = field(userClass, "uid", String.class);
        workAid = field(workClass, "aid", String.class);
        commentUser = HostTargets.method(report, "comment_reply_comment_user", loader);
        if (Modifier.isStatic(commentUser.getModifiers()) || commentUser.getParameterCount() != 0
                || commentUser.getDeclaringClass() != commentClass || commentUser.getReturnType() != userClass) throw contract();
        contextFactory = HostTargets.method(report, "auto_comment_work_context", loader);
        workResolver = HostTargets.method(report, "portrait_comment_aweme_resolver", loader);
        Class<?> builderClass = HostTargets.type(report, "comment_reply_thread_builder", loader);
        Class<?> requestClass = HostTargets.type(report, "comment_reply_thread_request", loader);
        builderConstructor = builderClass.getConstructor();
        requestConstructor = requestClass.getConstructor(builderClass);
        threadFetch = HostTargets.method(report, "comment_reply_thread_fetch", loader);
        Class<?> contextClass = contextFactory.getReturnType();
        if (!Modifier.isStatic(contextFactory.getModifiers())
                || !java.util.Arrays.equals(contextFactory.getParameterTypes(), new Class<?>[]{workClass})
                || !Modifier.isStatic(workResolver.getModifiers()) || workResolver.getReturnType() != workClass
                || !java.util.Arrays.equals(workResolver.getParameterTypes(), new Class<?>[]{contextClass})
                || !Modifier.isStatic(threadFetch.getModifiers())
                || !java.util.Arrays.equals(threadFetch.getParameterTypes(), new Class<?>[]{requestClass})) throw contract();
        Class<?> taskClass = threadFetch.getReturnType();
        taskWait = taskMethod(report, loader, "comment_reply_task_wait", taskClass, boolean.class, long.class, TimeUnit.class);
        taskResult = taskMethod(report, loader, "comment_reply_task_result", taskClass, Object.class);
        taskFaulted = taskMethod(report, loader, "comment_reply_task_faulted", taskClass, boolean.class);
        taskError = taskMethod(report, loader, "comment_reply_task_error", taskClass, Exception.class);
        taskCancelled = taskMethod(report, loader, "comment_reply_task_cancelled", taskClass, boolean.class);
        builderContext = field(builderClass, "LIZ", contextClass);
        builderRoot = field(builderClass, "LJII", String.class);
        builderCursor = field(builderClass, "LJIIIZ", Long.class);
        builderCount = field(builderClass, "LJIIJJI", Integer.class);
        builderMerge = field(builderClass, "LJIIJ", String.class);
        builderComment = field(builderClass, "LJJII", commentClass);
        builderUid = field(builderClass, "LJJIII", String.class);
    }

    static OwnCursorStore preferencesStore(Context context) {
        if (context == null) throw new IllegalArgumentException("Application context is required");
        Context application = context.getApplicationContext();
        if (application == null) application = context;
        SharedPreferences preferences = application.getSharedPreferences("douai_comment_reply_thread_lookup", Context.MODE_PRIVATE);
        return new OwnCursorStore() {
            private String key(long account) { return "own_cursor." + account; }
            @Override public long read(long account) {
                try { return Math.max(0, preferences.getLong(key(account), 0)); }
                catch (RuntimeException corrupt) { return 0; }
            }
            @Override public boolean write(long account, long cursor) {
                return preferences.edit().putLong(key(account), Math.max(0, cursor)).commit();
            }
        };
    }

    private static final class MemoryOwnCursorStore implements OwnCursorStore {
        private final Map<Long, Long> cursors = new LinkedHashMap<>();
        @Override public synchronized long read(long account) { return cursors.getOrDefault(account, 0L); }
        @Override public synchronized boolean write(long account, long cursor) { cursors.put(account, Math.max(0, cursor)); return true; }
    }

    @Override public synchronized List<Match> resolve(long account, List<Hint> hints, BooleanSupplier valid) throws Exception {
        long started = System.nanoTime();
        try { return resolveNow(account, hints, valid, started + ROUND_TIMEOUT, true, started, new HashSet<>()); }
        catch (Exception failure) {
            synchronized (stateLock) { coveredRoots.clear(); }
            throw failure;
        } catch (LinkageError unavailable) {
            synchronized (stateLock) { coveredRoots.clear(); }
            throw new IllegalStateException("thread_lookup_linkage", unavailable);
        }
    }

    private List<Match> resolveNow(long account, List<Hint> hints, BooleanSupplier valid,
                                   long deadline, boolean allowCachedWindow, long lookupStarted,
                                   Set<String> revoked) throws Exception {
        check(valid, deadline);
        if (account <= 0 || hints == null || hints.isEmpty()) return Collections.emptyList();
        String me = Long.toString(account);
        synchronized (stateLock) {
            if (rootAccount != account) { roots.clear(); coveredRoots.clear(); rootOffset = 0; rootAccount = account; }
        }
        List<Hint> pending = new ArrayList<>();
        for (Hint hint : hints) if (validHint(hint, me)) pending.add(hint);
        if (pending.isEmpty()) return Collections.emptyList();
        safeLog("Comment reply own comments read started: hints=" + pending.size());
        long ownStarted = System.nanoTime();
        OwnLoad ownLoad = loadOwnRoots(account, me, valid, deadline, pending,
                allowCachedWindow && covers(pending, me), revoked);
        int ownPages = ownLoad.pages;
        long ownMillis = elapsedMillis(ownStarted);
        List<Root> selected = selectRoots(pending);
        safeLog("Comment reply own comments ready: pages=" + ownPages + "; cached_roots=" + roots.size()
                + "; own_more=" + (ownMorePending ? 1 : 0) + "; selected_roots=" + selected.size()
                + "; own_ms=" + ownMillis + "; cache=" + (ownLoad.cachedWindow ? "fresh_head" : "refresh"));
        long threadStarted = System.nanoTime();
        List<Match> matches = new ArrayList<>();
        Set<String> matched = new HashSet<>();
        int replyPages = 0;
        for (Root root : selected) {
            check(valid, deadline);
            if (!validRoot(root, me)) continue;
            long cursor = 0;
            String merge = null;
            Set<String> seenCursors = new HashSet<>();
            int stalled = 0;
            for (int page = 0; page < THREAD_PAGES; page++) {
                check(valid, deadline);
                String position = cursor + ":" + (merge == null ? "" : merge);
                seenCursors.add(position);
                Object context = contextFactory.invoke(null, root.work);
                Object resolved = context == null ? null : workResolver.invoke(null, context);
                if (!workClass.isInstance(resolved) || !root.aid.equals(string(workAid.get(resolved)))) throw contract();
                Object builder = builderConstructor.newInstance();
                builderContext.set(builder, context);
                builderRoot.set(builder, root.cid);
                builderCursor.set(builder, Long.valueOf(cursor));
                builderCount.set(builder, Integer.valueOf(PAGE_SIZE));
                builderMerge.set(builder, merge);
                builderComment.set(builder, root.comment);
                builderUid.set(builder, me);
                check(valid, deadline);
                Object response = await(threadFetch.invoke(null, requestConstructor.newInstance(builder)), valid, deadline);
                requireResponse(response, threadResponseClass, threadStatus);
                replyPages++;
                List<?> items = list(threadItems.get(response));
                for (Object incoming : items) {
                    check(valid, deadline);
                    if (!validIncoming(root, incoming, me)) continue;
                    String incomingCid = string(cid.get(incoming));
                    String sender = userId(incoming);
                    for (Hint hint : pending) {
                        if (matched.contains(hint.incomingId) || !incomingCid.equals(hint.incomingId)
                                || !sender.equals(hint.senderUid) || !hintMatchesWork(hint, root.aid)
                                || !validHint(hint, me) || !validRoot(root, me)) continue;
                        matches.add(new Match(hint, root.work, incoming, root.comment));
                        matched.add(hint.incomingId);
                    }
                }
                if (matched.size() == pending.size() || !threadMore.getBoolean(response)) break;
                long nextCursor = threadCursor.getLong(response);
                String nextMerge = string(threadMerge.get(response));
                String nextPosition = nextCursor + ":" + (nextMerge == null ? "" : nextMerge);
                if (nextCursor < 0) break;
                if (nextCursor == cursor && same(merge, nextMerge) || seenCursors.contains(nextPosition)) {
                    if (++stalled >= 3) break;
                } else stalled = 0;
                cursor = nextCursor; merge = nextMerge;
            }
            if (matched.size() == pending.size()) break;
        }
        check(valid, deadline);
        for (Match match : matches) {
            Root captured = new Root(match.work, match.original, string(workAid.get(match.work)),
                    string(cid.get(match.original)), createdAt.getLong(match.original));
            if (!validRoot(captured, me) || !validIncoming(captured, match.incoming, me)
                    || !validHint(match.hint, me) || !hintMatchesWork(match.hint, captured.aid)
                    || !match.hint.incomingId.equals(string(cid.get(match.incoming)))
                    || !match.hint.senderUid.equals(userId(match.incoming))) throw contract();
        }
        safeLog("Comment reply thread lookup: own_pages=" + ownPages + "; roots=" + selected.size()
                + "; reply_pages=" + replyPages + "; hints=" + pending.size() + "; matches=" + matches.size()
                + "; thread_ms=" + elapsedMillis(threadStarted) + "; lookup_ms=" + elapsedMillis(lookupStarted));
        if (ownLoad.cachedWindow && matched.size() < pending.size()) {
            // A sparse hint may describe a new root under the same work. Never let a cache miss
            // delay discovery until TTL expiry, and retain this round's original deadline.
            safeLog("Comment reply own window cache miss; refreshing within current deadline");
            return resolveNow(account, hints, valid, deadline, false, lookupStarted, revoked);
        }
        return matches;
    }

    private OwnLoad loadOwnRoots(long account, String me, BooleanSupplier valid, long deadline,
                                 List<Hint> hints, boolean canReuseWindow, Set<String> revoked) throws Exception {
        long cursor = 0;
        long historyCursor = Math.max(0, ownCursorStore.read(account));
        Set<String> confirmed = new LinkedHashSet<>();
        // Keep negative evidence across this resolve's cache-miss refresh; a later independent
        // resolve starts with a new set and can observe an original becoming visible again.
        boolean progressed = true, ended = false;
        Set<Long> seenCursors = new HashSet<>();
        int stalled = 0;
        int pages = 0;
        boolean historyResumed = false;
        Set<String> refreshedRoots = new HashSet<>();
        ownMorePending = false;
        for (int page = 0; page < OWN_PAGES; page++) {
            if (page == 1 && historyCursor > 0 && historyCursor != cursor) {
                cursor = historyCursor;
                seenCursors.clear();
                stalled = 0;
                historyResumed = true;
            }
            check(valid, deadline);
            seenCursors.add(cursor);
            Object api = provider.get(null);
            if (api == null) throw new IllegalStateException("thread_lookup_provider_unavailable");
            Object response = await(ownFetch.invoke(api, cursor, PAGE_SIZE), valid, deadline);
            requireResponse(response, ownResponseClass, ownStatus);
            ownMorePending = ownMore.getBoolean(response);
            pages++;
            List<?> items = list(ownItems.get(response));
            for (Object item : items) {
                check(valid, deadline);
                if (!noticeClass.isInstance(item)) continue;
                Object comment = payloadComment.get(item), work = payloadWork.get(item);
                if (!commentClass.isInstance(comment)) continue;
                if (!workClass.isInstance(work)) {
                    String rejected = string(cid.get(comment));
                    revoked.add(rejected); forget(rejected); continue;
                }
                Root root = new Root(work, comment, string(workAid.get(work)), string(cid.get(comment)), createdAt.getLong(comment));
                if (!revoked.contains(root.cid) && payloadInvisible.getInt(item) == 0 && validRoot(root, me)) {
                    remember(root, refreshedRoots);
                    refreshedRoots.add(root.aid + ":" + root.cid);
                    confirmed.add(root.aid + ":" + root.cid);
                } else { revoked.add(root.cid); forget(root.cid); }
            }
            if (!ownMore.getBoolean(response)) { ended = true; break; }
            long next = ownMin.getLong(response);
            if (next < 0) { progressed = false; break; }
            if (next == cursor || seenCursors.contains(next)) {
                progressed = false;
                if (++stalled >= 3) break;
            } else stalled = 0;
            if (page == 0 && canReuseWindow && progressed && covers(hints, me)) {
                // The live head can revoke cached ownership. It cannot prolong the verified window.
                return new OwnLoad(pages, true);
            }
            cursor = next;
        }
        check(valid, deadline);
        if (progressed && (ended || pages == OWN_PAGES)) {
            long now = cacheClock.getAsLong();
            synchronized (stateLock) {
                if (!closed) for (String key : confirmed) {
                    if (roots.containsKey(key)) coveredRoots.put(key, new RootCoverage(now, valid));
                }
            }
            // Wrap at the end so comments added while the historical pass ran are covered next cycle.
            long nextHistoryCursor = ended || !ownMorePending ? 0 : cursor;
            if (!ownCursorStore.write(account, nextHistoryCursor)) {
                throw new IllegalStateException("thread_lookup_cursor_persist_failed");
            }
        }
        safeLog("Comment reply own cursor: history=" + (historyResumed ? "resumed" : "head")
                + "; persisted=" + (progressed && (ended || pages == OWN_PAGES) ? "advanced" : "unchanged"));
        return new OwnLoad(pages, false);
    }

    private void remember(Root root, Set<String> refreshedRoots) {
        synchronized (stateLock) {
            if (closed) return;
            String key = root.aid + ":" + root.cid;
            roots.remove(key);
            roots.put(key, root);
            while (roots.size() > MAX_ROOTS) {
                String oldest = null;
                for (String candidate : roots.keySet()) {
                    if (!refreshedRoots.contains(candidate)) { oldest = candidate; break; }
                }
                if (oldest == null) oldest = roots.keySet().iterator().next();
                coveredRoots.remove(oldest);
                roots.remove(oldest);
            }
        }
    }

    private void forget(String commentId) {
        if (!nonzero(commentId)) return;
        synchronized (stateLock) {
            roots.entrySet().removeIf(item -> {
                if (!commentId.equals(item.getValue().cid)) return false;
                coveredRoots.remove(item.getKey());
                return true;
            });
        }
    }

    private boolean covers(List<Hint> hints, String me) throws Exception {
        List<Root> candidates;
        Map<String, RootCoverage> coverage;
        synchronized (stateLock) {
            candidates = new ArrayList<>(roots.values());
            coverage = new LinkedHashMap<>(coveredRoots);
        }
        long now = cacheClock.getAsLong();
        for (Hint hint : hints) {
            String work = nonzero(hint.aid) ? hint.aid : string(aid.get(hint.target));
            if (!nonzero(work)) return false;
            String expectedRoot = string(rootId.get(hint.target));
            boolean found = false;
            for (Root root : candidates) {
                if (!work.equals(root.aid) || nonzero(expectedRoot) && !expectedRoot.equals(root.cid)
                        || !validRoot(root, me)) continue;
                RootCoverage recorded = coverage.get(root.aid + ":" + root.cid);
                if (recorded == null || now - recorded.at < 0 || now - recorded.at >= ROOT_TTL) continue;
                // The prior guard captures the caller's account/configuration epoch. Call it outside
                // stateLock so invalidation never inverts the owning hook's lock order.
                if (!recorded.valid.getAsBoolean()) continue;
                found = true;
                break;
            }
            if (!found) return false;
        }
        return true;
    }

    private static long elapsedMillis(long started) {
        return TimeUnit.NANOSECONDS.toMillis(Math.max(0, System.nanoTime() - started));
    }

    private static final class OwnLoad {
        final int pages;
        final boolean cachedWindow;
        OwnLoad(int pages, boolean cachedWindow) { this.pages = pages; this.cachedWindow = cachedWindow; }
    }

    private static final class RootCoverage {
        final long at;
        final BooleanSupplier valid;
        RootCoverage(long at, BooleanSupplier valid) { this.at = at; this.valid = valid; }
    }

    private List<Root> selectRoots(List<Hint> hints) throws Exception {
        synchronized (stateLock) {
            List<Root> candidates = new ArrayList<>();
            for (Root root : roots.values()) {
                for (Hint hint : hints) if (hintMatchesWork(hint, root.aid)) { candidates.add(root); break; }
            }
            if (candidates.isEmpty()) return Collections.emptyList();
            int size = candidates.size(), start = rootOffset % size;
            LinkedHashSet<Root> selected = new LinkedHashSet<>();
            // Reserve half the budget for exact work hints; the remaining half rotates across the pool.
            for (int offset = 0; offset < size && selected.size() < ROOTS_PER_ROUND / 2; offset++) {
                Root root = candidates.get((start + offset) % size);
                for (Hint hint : hints) {
                    if (nonzero(hint.aid) && root.aid.equals(hint.aid)) { selected.add(root); break; }
                }
            }
            int visited = 0;
            for (; visited < size && selected.size() < ROOTS_PER_ROUND; visited++) selected.add(candidates.get((start + visited) % size));
            rootOffset = (start + Math.max(1, visited)) % size;
            return new ArrayList<>(selected);
        }
    }

    private boolean validHint(Hint hint, String me) throws Exception {
        if (hint == null || !nonzero(hint.incomingId) || !nonzero(hint.senderUid) || me.equals(hint.senderUid)
                || !commentClass.isInstance(hint.target) || !hint.incomingId.equals(string(cid.get(hint.target)))
                || !hint.senderUid.equals(userId(hint.target))) return false;
        String targetAid = string(aid.get(hint.target));
        String targetRecipient = string(recipient.get(hint.target));
        return (!nonzero(targetRecipient) || me.equals(targetRecipient))
                && (!nonzero(hint.aid) || !nonzero(targetAid) || hint.aid.equals(targetAid));
    }

    private boolean hintMatchesWork(Hint hint, String work) throws Exception {
        if (nonzero(hint.aid) && !work.equals(hint.aid)) return false;
        String targetAid = commentClass.isInstance(hint.target) ? string(aid.get(hint.target)) : null;
        return !nonzero(targetAid) || work.equals(targetAid);
    }

    private boolean validRoot(Root root, String me) throws Exception {
        if (!workClass.isInstance(root.work) || !commentClass.isInstance(root.comment)
                || !nonzero(root.aid) || !nonzero(root.cid) || !root.aid.equals(string(workAid.get(root.work)))
                || !root.cid.equals(string(cid.get(root.comment))) || !me.equals(userId(root.comment))
                || !emptyReference(rootId.get(root.comment)) || !emptyReference(directId.get(root.comment))) return false;
        String originalText = string(text.get(root.comment));
        String repeatedAid = string(aid.get(root.comment));
        return originalText != null && !originalText.trim().isEmpty()
                && (!nonzero(repeatedAid) || root.aid.equals(repeatedAid));
    }

    private boolean validIncoming(Root root, Object incoming, String me) throws Exception {
        if (!commentClass.isInstance(incoming) || !nonzero(string(cid.get(incoming)))
                || root.cid.equals(string(cid.get(incoming))) || !root.cid.equals(string(rootId.get(incoming)))) return false;
        String sender = userId(incoming);
        String direct = string(directId.get(incoming));
        String to = string(recipient.get(incoming));
        String repeatedAid = string(aid.get(incoming));
        return nonzero(sender) && !me.equals(sender) && (!nonzero(direct) || root.cid.equals(direct))
                && (!nonzero(to) || me.equals(to)) && (!nonzero(repeatedAid) || root.aid.equals(repeatedAid));
    }

    private String userId(Object comment) throws Exception {
        Object user = commentUser.invoke(comment);
        return userClass.isInstance(user) ? string(uid.get(user)) : null;
    }

    private Object await(Object operation, BooleanSupplier valid, long deadline) throws Exception {
        try { return awaitNow(operation, valid, deadline); }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw interrupted;
        } catch (InvocationTargetException invoked) {
            if (invoked.getCause() instanceof InterruptedException) Thread.currentThread().interrupt();
            throw invoked;
        }
    }

    private Object awaitNow(Object operation, BooleanSupplier valid, long deadline) throws Exception {
        check(valid, deadline);
        if (operation == null) throw new IllegalStateException("thread_lookup_operation_missing");
        Future<?> future = operation instanceof Future<?> ? (Future<?>) operation : null;
        if (future == null) {
            Method toFuture = null;
            try { toFuture = operation.getClass().getMethod("toFuture"); } catch (NoSuchMethodException ignored) { }
            if (toFuture != null) {
                Object converted = toFuture.invoke(operation);
                if (!(converted instanceof Future<?>)) throw contract();
                future = (Future<?>) converted;
            }
        }
        if (future != null) {
            activeFuture = future;
            try {
                while (true) {
                    check(valid, deadline);
                    try { return future.get(Math.min(TimeUnit.MILLISECONDS.toNanos(500), deadline - System.nanoTime()), TimeUnit.NANOSECONDS); }
                    catch (TimeoutException waiting) { check(valid, deadline); }
                }
            } catch (Exception failure) {
                future.cancel(true);
                throw failure;
            } finally { activeFuture = null; }
        }
        if (!taskWait.getDeclaringClass().isInstance(operation)) throw contract();
        while (true) {
            check(valid, deadline);
            // The host converts this duration to millis before Object.wait; zero would wait forever.
            long millis = Math.max(1, Math.min(500, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())));
            if (!Boolean.TRUE.equals(taskWait.invoke(operation, millis, TimeUnit.MILLISECONDS))) continue;
            check(valid, deadline);
            if (Boolean.TRUE.equals(taskCancelled.invoke(operation))) throw new CancellationException("thread_lookup_cancelled");
            if (Boolean.TRUE.equals(taskFaulted.invoke(operation))) {
                Object cause = taskError.invoke(operation);
                throw new IllegalStateException("thread_lookup_request_failed", cause instanceof Throwable ? (Throwable) cause : null);
            }
            return taskResult.invoke(operation);
        }
    }

    private static Method taskMethod(HostScanReport report, ClassLoader loader, String rule, Class<?> taskClass,
                                     Class<?> result, Class<?>... parameters) throws Exception {
        Method method = HostTargets.method(report, rule, loader);
        if (Modifier.isStatic(method.getModifiers()) || method.getDeclaringClass() != taskClass
                || method.getReturnType() != result || !java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
            throw contract();
        }
        return method;
    }

    private void requireResponse(Object response, Class<?> type, Field status) throws Exception {
        String stage = type == ownResponseClass ? "own" : "reply";
        if (!type.isInstance(response)) {
            safeLog("Comment reply thread response rejected: stage=" + stage + "; reason=model");
            throw new IllegalStateException("thread_lookup_response_model");
        }
        int value = status.getInt(response);
        if (value != 0) {
            safeLog("Comment reply thread response rejected: stage=" + stage + "; reason=status; status=" + value);
            throw new IllegalStateException("thread_lookup_response_status");
        }
    }

    private void check(BooleanSupplier valid, long deadline) throws TimeoutException {
        if (closed || Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) throw new CancellationException("thread_lookup_cancelled");
        if (System.nanoTime() >= deadline) throw new TimeoutException("thread_lookup_timeout");
    }

    private static List<?> list(Object value) {
        if (value == null) return Collections.emptyList();
        if (!(value instanceof List<?>)) throw new IllegalStateException("thread_lookup_response_items");
        return (List<?>) value;
    }
    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        Field value = HostTargets.field(owner, name);
        if (value.getType() != type || Modifier.isStatic(value.getModifiers())) throw contract();
        return value;
    }
    private static String string(Object value) { return value instanceof String ? (String) value : null; }
    private static boolean nonzero(String value) { return value != null && !value.trim().isEmpty() && !"0".equals(value); }
    private static boolean emptyReference(Object value) { return value == null || value instanceof String && !nonzero((String) value); }
    private static boolean same(String left, String right) { return (left == null ? "" : left).equals(right == null ? "" : right); }
    private static IllegalStateException contract() { return new IllegalStateException("thread_lookup_contract"); }
    private void safeLog(String message) { try { DouAiLog.event("comment_reply_thread_lookup", "lookup_observation", "host_lookup", message); if (log != null) log.accept(message); } catch (RuntimeException ignored) { } }

    @Override public void close() {
        closed = true;
        Future<?> future = activeFuture;
        if (future != null) future.cancel(true);
        synchronized (stateLock) { roots.clear(); coveredRoots.clear(); rootAccount = 0; rootOffset = 0; }
    }

    private static final class Root {
        final Object work, comment;
        final String aid, cid;
        final long createdAt;
        Root(Object work, Object comment, String aid, String cid, long createdAt) {
            this.work = work; this.comment = comment; this.aid = aid; this.cid = cid; this.createdAt = createdAt;
        }
    }
}
