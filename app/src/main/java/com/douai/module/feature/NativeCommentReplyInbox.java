package com.douai.module.feature;

import android.content.Context;

import com.douai.module.diagnostics.DouAiLog;

import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Read-only notice pagination. No notification read state or host UI state is changed. */
final class NativeCommentReplyInbox implements AutoCloseable {
    interface ThreadLookup extends AutoCloseable {
        List<NativeCommentThreadLookup.Match> resolve(long account, List<NativeCommentThreadLookup.Hint> hints,
                                                     BooleanSupplier valid) throws Exception;
        @Override default void close() { }
    }
    enum FailureReason {
        API_INITIALIZATION, API_NOT_READY, REQUEST_INVOKE, REQUEST_FUTURE, REQUEST_WAIT,
        RESPONSE_MODEL, RESPONSE_STATUS, RESPONSE_PAGE, NOTICE_MODEL, BASELINE_TIMESTAMPS,
        BASELINE_LIMIT, PAGINATION_NO_PROGRESS, PAGINATION_LIMIT, ACCOUNT_NOT_READY, ROUTE_RESOLUTION,
        MODEL_RESOLUTION, STATE_MIGRATION, THREAD_LOOKUP
    }

    /** Only module-owned codes and numeric status values may cross the diagnostic boundary. */
    static final class ScanFailure extends IllegalStateException {
        final FailureReason reason;
        final Integer status;
        final PageTrace page;
        ScanFailure(FailureReason reason) { this(reason, null, null, null); }
        ScanFailure(FailureReason reason, Throwable cause) { this(reason, null, cause, null); }
        ScanFailure(FailureReason reason, int status) { this(reason, Integer.valueOf(status), null, null); }
        private ScanFailure(FailureReason reason, Integer status, Throwable cause, PageTrace page) {
            super(reason.name(), cause);
            this.reason = reason;
            this.status = status;
            this.page = page;
        }
    }

    static String failureDiagnostic(Throwable failure) {
        StringBuilder detail = new StringBuilder("type=").append(failure.getClass().getSimpleName());
        Throwable current = failure;
        ScanFailure known = null;
        for (int depth = 0; current != null && depth < 8; depth++) {
            if (known == null && current instanceof ScanFailure) known = (ScanFailure) current;
            Throwable next = current.getCause();
            if (next == null || next == current) break;
            current = next;
        }
        detail.append("; reason=").append(known == null ? "unexpected"
                : known.reason.name().toLowerCase(java.util.Locale.ROOT));
        if (known != null && known.status != null) detail.append("; status=").append(known.status);
        if (known != null && known.page != null) detail.append(known.page.diagnostic());
        if (current != failure) detail.append("; cause=").append(current.getClass().getSimpleName());
        return detail.toString();
    }

    static final int PAGE_SIZE = 20;
    static final int MAX_PAGES = 50;
    static final int MAX_STALLED_PAGES = 3;
    private static final long REQUEST_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(20);
    private final Consumer<String> log;
    private final ThreadLookup threadLookup;
    private final Method fetch, accountService, currentUser, parsePush, receivedGroupResolver;
    private final Method commentPayloadResolver, incomingResolver, replyDetailResolver, generalWork, cloneComment, effectiveCommentUser;
    private final Field api, responseStatus, responseItems, responseMore, responseMax, responseMin;
    private final Field noticeComment, noticeGeneral, noticeId, noticeTime, noticeDisabled, itemDisabled;
    private final Field incoming, originalReply, levelOne, noticeWork, invisible;
    private final Field cid, commentAid, commentUser, commentText, commentTime, commentImages;
    private final Field replyId, replyToReplyId, replyToUserId;
    private final Field uid, nickname, workAid, pushType;
    private final Field detailCurrent, detailInvisible, detailLevelOne;
    private final Class<?> responseClass, noticeClass, commentNoticeClass, commentClass, userClass, workClass;
    private XposedInterface.HookHandle handle;
    private volatile boolean closed;
    private volatile long scannedThrough;
    private List<String> baselineIds = java.util.Collections.emptyList();

    NativeCommentReplyInbox(HostScanReport report, ClassLoader loader, Consumer<String> log) throws Exception {
        this(report, loader, log, null);
    }

    NativeCommentReplyInbox(HostScanReport report, ClassLoader loader, Consumer<String> log,
                            ThreadLookup threadLookup) throws Exception {
        this.log = log;
        this.threadLookup = threadLookup;
        fetch = HostTargets.method(report, "comment_reply_notice_fetch", loader);
        receivedGroupResolver = HostTargets.method(report, "comment_reply_received_group", loader);
        if (!Modifier.isStatic(receivedGroupResolver.getModifiers())
                || receivedGroupResolver.getParameterCount() != 0 || receivedGroupResolver.getReturnType() != int.class) {
            throw new IllegalStateException("Comment reply received group resolver contract changed");
        }
        Class<?> provider = HostTargets.type(report, "comment_reply_notice_provider", loader);
        api = HostTargets.field(provider, "b");
        Class<?>[] expected = {long.class, long.class, int.class, int.class, Integer.class, int.class,
                Integer.class, Integer.class, int.class, String.class, String.class, String.class,
                int.class, String.class, int.class, String.class, int.class, int.class,
                String.class, int.class, int.class, Integer.class, int.class};
        if (!java.util.Arrays.equals(expected, fetch.getParameterTypes())
                || !Future.class.isAssignableFrom(fetch.getReturnType())
                || !Modifier.isStatic(api.getModifiers()) || api.getType() != fetch.getDeclaringClass()) {
            throw new IllegalStateException("Comment reply notice API contract changed");
        }
        responseClass = HostTargets.type(report, "comment_reply_notice_response", loader);
        noticeClass = HostTargets.type(report, "comment_reply_base_notice", loader);
        commentNoticeClass = HostTargets.type(report, "comment_reply_comment_notice", loader);
        commentClass = HostTargets.type(report, "comment_reply_comment_model", loader);
        userClass = HostTargets.type(report, "comment_reply_user_model", loader);
        workClass = HostTargets.type(report, "comment_reply_work_model", loader);
        effectiveCommentUser = report.getResults().containsKey("comment_reply_comment_user")
                ? HostTargets.method(report, "comment_reply_comment_user", loader) : null;
        if (effectiveCommentUser != null && (Modifier.isStatic(effectiveCommentUser.getModifiers())
                || effectiveCommentUser.getDeclaringClass() != commentClass
                || effectiveCommentUser.getParameterCount() != 0 || effectiveCommentUser.getReturnType() != userClass)) {
            throw new IllegalStateException("Comment reply effective user contract changed");
        }
        Class<?> detailClass = HostTargets.type(report, "comment_reply_reply_detail_model", loader);
        commentPayloadResolver = modelAccessor(report, loader, "comment_reply_notice_comment_model", commentNoticeClass);
        incomingResolver = modelAccessor(report, loader, "comment_reply_notice_incoming", commentClass);
        replyDetailResolver = modelAccessor(report, loader, "comment_reply_notice_reply_detail", detailClass);
        responseStatus = field(responseClass, "status_code", int.class);
        responseItems = field(responseClass, "items", List.class);
        responseMore = field(responseClass, "hasMore", boolean.class);
        responseMax = field(responseClass, "maxTime", long.class);
        responseMin = field(responseClass, "minTime", long.class);
        noticeComment = field(noticeClass, "commentNotice", commentNoticeClass);
        noticeGeneral = HostTargets.field(noticeClass, "interactiveGeneralNoticeStruct");
        generalWork = noticeGeneral.getType().getMethod("getAweme");
        if (Modifier.isStatic(generalWork.getModifiers()) || generalWork.getReturnType() != workClass) {
            throw new IllegalStateException("Comment reply general work contract changed");
        }
        generalWork.setAccessible(true);
        noticeId = field(noticeClass, "nid", String.class);
        noticeTime = field(noticeClass, "createTime", long.class);
        noticeDisabled = field(noticeClass, "noticeDisable", boolean.class);
        itemDisabled = field(noticeClass, "itemDisable", boolean.class);
        incoming = field(commentNoticeClass, "comment", commentClass);
        originalReply = field(commentNoticeClass, "replyComment", commentClass);
        levelOne = field(commentNoticeClass, "level1Comment", commentClass);
        noticeWork = field(commentNoticeClass, "aweme", workClass);
        invisible = field(commentNoticeClass, "commentUnvisible", int.class);
        detailCurrent = field(detailClass, "a", commentClass);
        detailInvisible = field(detailClass, "b", int.class);
        detailLevelOne = field(detailClass, "e", commentClass);
        cid = field(commentClass, "cid", String.class);
        commentAid = field(commentClass, "awemeId", String.class);
        commentUser = field(commentClass, "user", userClass);
        commentText = field(commentClass, "text", String.class);
        commentTime = field(commentClass, "createTime", long.class);
        replyId = field(commentClass, "replyId", String.class);
        replyToReplyId = field(commentClass, "replyToReplyId", String.class);
        replyToUserId = field(commentClass, "replyToUserId", String.class);
        commentImages = field(commentClass, "imageList", List.class);
        cloneComment = HostTargets.method(report, "comment_reply_comment_clone", loader);
        if (Modifier.isStatic(cloneComment.getModifiers()) || cloneComment.getParameterCount() != 0
                || cloneComment.getDeclaringClass() != commentClass
                || cloneComment.getReturnType() != Object.class && cloneComment.getReturnType() != commentClass) {
            throw new IllegalStateException("Comment reply clone contract changed");
        }
        uid = field(userClass, "uid", String.class);
        nickname = field(userClass, "nickname", String.class);
        workAid = field(workClass, "aid", String.class);
        accountService = HostTargets.method(report, "media_account_service", loader);
        currentUser = HostTargets.method(report, "media_current_user", loader);
        if (!Modifier.isStatic(accountService.getModifiers()) || currentUser.getReturnType() != userClass) {
            throw new IllegalStateException("Comment reply account contract changed");
        }
        parsePush = HostTargets.method(report, "comment_reply_push_parse", loader);
        Class<?> pushClass = HostTargets.type(report, "comment_reply_push_model", loader);
        pushType = field(pushClass, "aggrNoticeType", String.class);
        if (!Modifier.isStatic(parsePush.getModifiers()) || parsePush.getReturnType() != pushClass) {
            throw new IllegalStateException("Comment reply push contract changed");
        }
    }

    static NativeCommentReplyInbox install(XposedInterface framework, HostScanReport report,
                                          ClassLoader loader, Context context, Consumer<String> log,
                                          Runnable incomingSignal) throws Exception {
        NativeCommentThreadLookup lookup = new NativeCommentThreadLookup(report, loader, log,
                NativeCommentThreadLookup.preferencesStore(context));
        NativeCommentReplyInbox inbox;
        try { inbox = new NativeCommentReplyInbox(report, loader, log, lookup); }
        catch (Exception failure) { lookup.close(); throw failure; }
        try {
            Method receive = HostTargets.method(report, "comment_reply_push_receive", loader);
            if (receive.getParameterTypes().length != 2
                    || receive.getParameterTypes()[0] != inbox.parsePush.getParameterTypes()[0]
                    || receive.getParameterTypes()[1] != boolean.class) {
                throw new IllegalStateException("Comment reply push receiver contract changed");
            }
            inbox.handle = framework.hook(receive).setId("DouAi.CommentReply.Incoming")
                    .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH).intercept(chain -> {
                        try {
                            if (!inbox.closed && inbox.isCommentPush(chain.getArg(0))) incomingSignal.run();
                        } catch (Exception failure) {
                            inbox.safeLog("Comment reply push observation failed: " + failure.getClass().getSimpleName());
                        }
                        return chain.proceed();
                    });
            if (inbox.handle == null) throw new IllegalStateException("Empty comment reply push hook");
            return inbox;
        } catch (Exception failure) { inbox.close(); throw failure; }
    }

    boolean isCommentPush(Object message) throws Exception {
        Object parsed = invoke(parsePush, null, message);
        return parsed != null && "comment".equals(pushType.get(parsed));
    }

    synchronized long baseline(long account, BooleanSupplier valid) throws Exception {
        int group = resolveReceivedGroup(account, valid);
        long newest = 0, max = 0, min = 0;
        Set<String> ids = new LinkedHashSet<>();
        PaginationProgress progress = new PaginationProgress();
        ModelCounts models = new ModelCounts();
        for (int index = 0; index < MAX_PAGES; index++) {
            Page page = page(account, group, max, min, valid);
            for (Object notice : page.items) {
                if (!noticeClass.isInstance(notice)) throw new ScanFailure(FailureReason.NOTICE_MODEL);
                NoticeModels model = readModels(notice);
                models.observe(model, account);
                newest = Math.max(newest, eventTime(notice, model.target));
                Object comment = model.target;
                if (comment != null) {
                    String value = id(cid.get(comment));
                    if (!value.isEmpty()) ids.add(value);
                }
            }
            progress.observe(page, index + 1);
            if (newest <= 0 && !page.items.isEmpty()) throw new ScanFailure(FailureReason.BASELINE_TIMESTAMPS);
            // Only finish collecting the newest second, never traverse the full historical inbox.
            if (!page.more || seconds(page.min) > 0 && seconds(page.min) < newest) {
                verifyAccount(account, valid);
                baselineIds = new ArrayList<>(ids);
                DouAiLog.event("comment_reply_inbox", "baseline_ready", "host_pages_evaluated", "pages=" + (index + 1) + ";notices=" + progress.totalItems + ";existing_comments=" + ids.size());
                safeLog("Comment reply inbox baseline ready: pages=" + (index + 1)
                        + "; notices=" + progress.totalItems + "; existing_comments=" + ids.size() + models.diagnostic());
                return newest > 0 ? newest : System.currentTimeMillis() / 1000;
            }
            progress.requireProgress();
            max = page.max;
            min = page.min;
        }
        throw new ScanFailure(FailureReason.BASELINE_LIMIT, null, null, progress.last);
    }

    synchronized List<String> baselineCommentIds() { return new ArrayList<>(baselineIds); }
    long scannedThrough() { return scannedThrough; }

    synchronized List<CommentReplyEvent> scan(long account, long sinceSeconds, BooleanSupplier valid) throws Exception {
        if (sinceSeconds <= 0) throw new IllegalArgumentException("Comment reply scan requires an established baseline");
        int group = resolveReceivedGroup(account, valid);
        Map<String, CommentReplyEvent> events = new LinkedHashMap<>();
        PaginationProgress progress = new PaginationProgress();
        ModelCounts models = new ModelCounts();
        Map<String, NativeCommentThreadLookup.Hint> hints = new LinkedHashMap<>();
        int beforeStart = 0, noticeBeforeStart = 0;
        long max = 0, min = 0, newestNotice = 0;
        for (int index = 0; index < MAX_PAGES; index++) {
            Page page = page(account, group, max, min, valid);
            for (Object notice : page.items) {
                if (!noticeClass.isInstance(notice)) throw new ScanFailure(FailureReason.NOTICE_MODEL);
                NoticeModels model = readModels(notice);
                models.observe(model, account);
                long arrival = eventTime(notice, model.target);
                newestNotice = Math.max(newestNotice, arrival);
                if (arrival > 0 && arrival < sinceSeconds) noticeBeforeStart++;
                CommentReplyEvent event = extract(notice, account, model);
                if (event != null) {
                    if (event.createdAt >= sinceSeconds) events.putIfAbsent(event.incomingId, event);
                    else beforeStart++;
                } else if (threadLookup != null && arrival >= sinceSeconds) {
                    NativeCommentThreadLookup.Hint hint = threadHint(notice, model, account, arrival);
                    if (hint != null) hints.putIfAbsent(hint.incomingId, hint);
                }
            }
            progress.observe(page, index + 1);
            // The host retains both cursors: 0dHG:339-351 -> 0dHH:318/322/540.
            if (!page.more || seconds(page.min) > 0 && seconds(page.min) < sinceSeconds) {
                verifyAccount(account, valid);
                int threadRecovered = 0;
                if (!hints.isEmpty()) {
                    List<NativeCommentThreadLookup.Match> matches = java.util.Collections.emptyList();
                    try { matches = threadLookup.resolve(account, new ArrayList<>(hints.values()), valid); }
                    catch (CancellationException failure) { throw failure; }
                    catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw failure; }
                    catch (Exception | LinkageError failure) {
                        ScanFailure known = new ScanFailure(FailureReason.THREAD_LOOKUP, failure);
                        if (events.isEmpty()) throw known;
                        safeLog("Comment reply thread lookup deferred: " + failureDiagnostic(known));
                    }
                    verifyAccount(account, valid);
                    if (matches == null) throw new ScanFailure(FailureReason.THREAD_LOOKUP);
                    for (NativeCommentThreadLookup.Match match : matches) {
                        checkValid(valid);
                        if (match == null || match.hint == null || hints.get(match.hint.incomingId) != match.hint) continue;
                        CommentReplyEvent recovered = threadEvent(match, account);
                        if (recovered == null) continue;
                        if (events.putIfAbsent(recovered.incomingId, recovered) == null) threadRecovered++;
                        hints.remove(recovered.incomingId);
                    }
                    verifyAccount(account, valid);
                }
                List<CommentReplyEvent> ordered = new ArrayList<>(events.values());
                ordered.sort(Comparator.comparingLong((CommentReplyEvent event) -> event.createdAt)
                        .thenComparing(event -> event.incomingId));
                long through = newestNotice;
                for (NativeCommentThreadLookup.Hint hint : hints.values()) through = Math.min(through, Math.max(0, hint.createdAt - 1));
                scannedThrough = through;
                DouAiLog.event("comment_reply_inbox", "scan_ready", "host_pages_evaluated", "pages=" + (index + 1) + ";notices=" + progress.totalItems + ";replies=" + ordered.size() + ";thread_recovered=" + threadRecovered + ";thread_pending=" + hints.size() + ";watermark_held=" + (through < newestNotice));
                safeLog("Comment reply inbox scan complete: pages=" + (index + 1)
                        + "; notices=" + progress.totalItems + "; replies=" + ordered.size()
                        + "; before_start=" + beforeStart + "; notice_before_start=" + noticeBeforeStart
                        + "; thread_recovered=" + threadRecovered + "; thread_pending=" + hints.size()
                        + "; watermark_held=" + (through < newestNotice ? 1 : 0) + models.diagnostic());
                return ordered;
            }
            progress.requireProgress();
            max = page.max;
            min = page.min;
        }
        throw new ScanFailure(FailureReason.PAGINATION_LIMIT, null, null, progress.last);
    }

    private int resolveReceivedGroup(long account, BooleanSupplier valid) throws Exception {
        verifyAccount(account, valid);
        Object value;
        try { value = invoke(receivedGroupResolver, null); }
        catch (Exception failure) { throw new ScanFailure(FailureReason.ROUTE_RESOLUTION, failure); }
        if (!(value instanceof Integer) || (Integer) value <= 0) {
            throw new ScanFailure(FailureReason.ROUTE_RESOLUTION);
        }
        verifyAccount(account, valid);
        int group = (Integer) value;
        DouAiLog.event("comment_reply_inbox", "route_resolved", "host_received_comments", "group=" + group);
        safeLog("Comment reply inbox route: source=host_received_comments; group=" + group);
        return group;
    }

    private Page page(long account, int group, long max, long min, BooleanSupplier valid) throws Exception {
        verifyAccount(account, valid);
        Object service;
        try { service = api.get(null); }
        catch (LinkageError unavailable) { throw new ScanFailure(FailureReason.API_INITIALIZATION, unavailable); }
        if (service == null || !fetch.getDeclaringClass().isInstance(service)) {
            throw new ScanFailure(FailureReason.API_NOT_READY);
        }
        // No subtype filter: the native comment tab combines received comments and replies.
        Object value;
        try {
            value = invoke(fetch, service, max, min, PAGE_SIZE, group, Integer.valueOf(0), 0,
                    Integer.valueOf(0), Integer.valueOf(0), 1, "", "", "", -1, "", 0, "", 0, 0, "", 0, 1, null, 0);
        } catch (Exception failure) { throw new ScanFailure(FailureReason.REQUEST_INVOKE, failure); }
        if (!(value instanceof Future<?>)) throw new ScanFailure(FailureReason.REQUEST_FUTURE);
        Future<?> future = (Future<?>) value;
        Object response;
        long deadline = System.nanoTime() + REQUEST_TIMEOUT_NANOS;
        try {
            while (true) {
                checkValid(valid);
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) throw new TimeoutException("Comment reply notice request timed out");
                try { response = future.get(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(500)), TimeUnit.NANOSECONDS); break; }
                catch (TimeoutException waiting) { if (System.nanoTime() >= deadline) throw waiting; }
            }
        } catch (Exception failure) {
            future.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            if (failure instanceof CancellationException || failure instanceof InterruptedException
                    || failure instanceof TimeoutException) throw failure;
            throw new ScanFailure(FailureReason.REQUEST_WAIT, failure);
        }
        verifyAccount(account, valid);
        if (!responseClass.isInstance(response)) throw new ScanFailure(FailureReason.RESPONSE_MODEL);
        int status = responseStatus.getInt(response);
        if (status != 0) throw new ScanFailure(FailureReason.RESPONSE_STATUS, status);
        Object list = responseItems.get(response);
        boolean more = responseMore.getBoolean(response);
        if (list == null && more || list != null && !(list instanceof List<?>)) {
            throw new ScanFailure(FailureReason.RESPONSE_PAGE);
        }
        return new Page(list == null ? java.util.Collections.emptyList() : new ArrayList<>((List<?>) list),
                more, responseMax.getLong(response), responseMin.getLong(response));
    }

    CommentReplyEvent extract(Object notice, long account) throws Exception {
        if (!noticeClass.isInstance(notice)) throw new ScanFailure(FailureReason.NOTICE_MODEL);
        return extract(notice, account, readModels(notice));
    }

    private CommentReplyEvent extract(Object notice, long account, NoticeModels models) throws Exception {
        String nid = string(noticeId.get(notice));
        Object model = models.payload;
        Object target = models.target;
        if (target == null) return skip(nid, "unsupported or missing comment notice payload");
        Object legacyTarget = model == null ? null : incoming.get(model);
        String targetId = id(cid.get(target));
        boolean legacyContext = legacyTarget != null && (legacyTarget == target
                || !targetId.isEmpty() && targetId.equals(id(cid.get(legacyTarget))));
        int hidden = models.detailSelected ? detailInvisible.getInt(models.detail)
                : legacyContext ? invisible.getInt(model) : 0;
        if (noticeDisabled.getBoolean(notice) || itemDisabled.getBoolean(notice) || hidden != 0) {
            return skip(nid, "disabled or invisible comment");
        }
        // 0dN5 uses the unified incoming resolver; its 0dN2 parent binds the general work.
        Object work = models.general == null ? null : invoke(generalWork, models.general);
        if (work == null && legacyContext) work = noticeWork.get(model);
        if (work == null) return skip(nid, "missing incoming comment or work");
        String incomingId = id(cid.get(target));
        String aid = id(workAid.get(work));
        String originAid = string(commentAid.get(target));
        Object sender = commentAuthor(target);
        String senderId = sender == null ? "" : id(uid.get(sender));
        boolean nativeWorkBound = models.detailSelected || models.payload != models.legacy;
        if (incomingId.isEmpty() || aid.isEmpty() || senderId.isEmpty()
                || (originAid.isEmpty() ? !nativeWorkBound : !aid.equals(originAid))) {
            return skip(nid, "incomplete or inconsistent incoming identity");
        }
        String me = Long.toString(account);
        if (me.equals(senderId)) return skip(nid, "incoming comment is own message");
        String recipient = id(replyToUserId.get(target));
        if (!recipient.isEmpty() && !me.equals(recipient)) {
            return skip(nid, "incoming reply explicitly addresses another account");
        }
        String parent = id(replyToReplyId.get(target));
        String root = id(replyId.get(target));
        if (parent.isEmpty()) parent = root;
        Object original = null;
        boolean restoreParent = parent.isEmpty();
        if (restoreParent) {
            original = parentlessOriginal(models, me, aid, incomingId, recipient);
            if (original == null) return skip(nid, "missing reply parent and verified own recipient context");
            root = parent = id(cid.get(original));
        }
        List<Object> originals = new ArrayList<>();
        if (models.detailSelected) originals.add(detailLevelOne.get(models.detail));
        if (legacyContext) {
            originals.add(originalReply.get(model));
            originals.add(levelOne.get(model));
        }
        for (Object candidate : restoreParent ? java.util.Collections.emptyList() : originals) {
            if (candidate == null || !parent.equals(id(cid.get(candidate)))) continue;
            Object author = commentAuthor(candidate);
            if (author == null || !me.equals(id(uid.get(author)))) {
                return skip(nid, "referenced comment author is not current account");
            }
            String candidateAid = id(commentAid.get(candidate));
            if (!candidateAid.isEmpty() && !aid.equals(candidateAid)) return skip(nid, "referenced work differs");
            original = candidate;
        }
        if (original == null) return skip(nid, "cannot prove reply references own comment");
        String text = string(commentText.get(target)).trim();
        String originalText = string(commentText.get(original)).trim();
        if (originalText.isEmpty() || text.isEmpty() && !AutomationReplyMedia.hasCommentImages(target)) {
            return skip(nid, "missing reply content or own comment context");
        }
        long created = eventTime(notice, target);
        if (created <= 0) return skip(nid, "incoming reply lacks timestamp");
        if (restoreParent) {
            target = copyWithParent(target, root);
            if (target == null) return skip(nid, "cannot copy verified reply target");
            safeLog("Comment reply parent context restored: evidence=own_recipient_and_level1");
        }
        return new CommentReplyEvent(nid, aid, incomingId, root.isEmpty() ? incomingId : root,
                senderId, string(nickname.get(sender)), text, originalText, created, work, target, nativeWorkBound);
    }

    /** Module compatibility inference, requiring both a directed recipient and an own level-one comment. */
    private Object parentlessOriginal(NoticeModels models, String me, String aid,
                                      String incomingId, String recipient) throws Exception {
        if (!models.detailSelected || !me.equals(recipient)) return null;
        Object original = detailLevelOne.get(models.detail);
        if (original == null) return null;
        String originalId = id(cid.get(original));
        Object author = commentAuthor(original);
        String originalAid = id(commentAid.get(original));
        if (originalId.isEmpty() || originalId.equals(incomingId) || author == null
                || !me.equals(id(uid.get(author))) || string(commentText.get(original)).trim().isEmpty()
                || !id(replyId.get(original)).isEmpty() || !id(replyToReplyId.get(original)).isEmpty()
                || !originalAid.isEmpty() && !aid.equals(originalAid)) return null;
        return original;
    }

    /** Native clone preserves the incoming identity and media; only the private copy gets a root. */
    private Object copyWithParent(Object target, String root) {
        try {
            Object copy = invoke(cloneComment, target);
            if (copy == target || !commentClass.isInstance(copy)
                    || !java.util.Objects.equals(cid.get(copy), cid.get(target))
                    || !java.util.Objects.equals(commentAid.get(copy), commentAid.get(target))
                    || !java.util.Objects.equals(commentText.get(copy), commentText.get(target))
                    || !java.util.Objects.equals(replyToUserId.get(copy), replyToUserId.get(target))
                    || commentTime.getLong(copy) != commentTime.getLong(target)
                    || commentImages.get(copy) != commentImages.get(target)
                    || !id(replyId.get(copy)).isEmpty() || !id(replyToReplyId.get(copy)).isEmpty()
                    || !id(replyId.get(target)).isEmpty() || !id(replyToReplyId.get(target)).isEmpty()) return null;
            // The native clone obtains user via LLLLZLL(), which may select commentUser.
            Object copiedUser = commentAuthor(copy), sourceUser = commentAuthor(target);
            if (copiedUser == null || sourceUser == null
                    || !id(uid.get(sourceUser)).equals(id(uid.get(copiedUser)))) return null;
            replyId.set(copy, root);
            replyToReplyId.set(copy, "0");
            return copy;
        } catch (Exception failure) { return null; }
    }

    private Object commentAuthor(Object comment) throws Exception {
        return comment == null ? null : effectiveCommentUser == null ? commentUser.get(comment)
                : invoke(effectiveCommentUser, comment);
    }

    /** A sparse record is only a lookup hint. It never authorizes a reply on its own. */
    private NativeCommentThreadLookup.Hint threadHint(Object notice, NoticeModels models, long account, long arrival) throws Exception {
        Object target = models.target;
        if (target == null || noticeDisabled.getBoolean(notice) || itemDisabled.getBoolean(notice)
                || models.detailSelected && detailInvisible.getInt(models.detail) != 0
                || !id(replyId.get(target)).isEmpty() || !id(replyToReplyId.get(target)).isEmpty()) return null;
        Object model = models.payload;
        Object legacy = model == null ? null : incoming.get(model);
        String incomingId = id(cid.get(target)), me = Long.toString(account);
        boolean legacyMatches = legacy == target || legacy != null && !incomingId.isEmpty() && incomingId.equals(id(cid.get(legacy)));
        if (legacyMatches && invisible.getInt(model) != 0) return null;
        Object sender = commentAuthor(target);
        String senderId = sender == null ? "" : id(uid.get(sender));
        String to = id(replyToUserId.get(target));
        if (incomingId.isEmpty() || senderId.isEmpty() || me.equals(senderId) || !to.isEmpty() && !me.equals(to)) return null;
        Object work = models.general == null ? null : invoke(generalWork, models.general);
        if (work == null && legacyMatches) work = noticeWork.get(model);
        String boundAid = work == null ? "" : id(workAid.get(work)), repeatedAid = id(commentAid.get(target));
        if (!boundAid.isEmpty() && !repeatedAid.isEmpty() && !boundAid.equals(repeatedAid)) return null;
        if (boundAid.isEmpty()) boundAid = repeatedAid;
        return new NativeCommentThreadLookup.Hint(string(noticeId.get(notice)), boundAid, incomingId, senderId, arrival, target);
    }

    /** Independently recheck the complete raw thread model before handing it to generation or publishing. */
    private CommentReplyEvent threadEvent(NativeCommentThreadLookup.Match match, long account) throws Exception {
        Object target = match.incoming, original = match.original, work = match.work;
        NativeCommentThreadLookup.Hint hint = match.hint;
        if (!commentClass.isInstance(target) || !commentClass.isInstance(original) || !workClass.isInstance(work)
                || !commentClass.isInstance(hint.target) || hint.createdAt <= 0) return null;
        String me = Long.toString(account), aid = id(workAid.get(work)), incomingId = id(cid.get(target));
        String root = id(cid.get(original));
        Object author = commentAuthor(original), sender = commentAuthor(target), hintedSender = commentAuthor(hint.target);
        if (aid.isEmpty() || incomingId.isEmpty() || root.isEmpty() || root.equals(incomingId)
                || !hint.incomingId.equals(incomingId) || !hint.incomingId.equals(id(cid.get(hint.target)))
                || !hint.aid.isEmpty() && !hint.aid.equals(aid)
                || author == null || !me.equals(id(uid.get(author))) || sender == null || hintedSender == null
                || !hint.senderUid.equals(id(uid.get(sender))) || !hint.senderUid.equals(id(uid.get(hintedSender)))
                || me.equals(hint.senderUid) || !id(replyId.get(original)).isEmpty() || !id(replyToReplyId.get(original)).isEmpty()
                || !root.equals(id(replyId.get(target)))) return null;
        String direct = id(replyToReplyId.get(target));
        if (!direct.isEmpty() && !root.equals(direct)) return null;
        for (Object comment : new Object[]{target, original, hint.target}) {
            String repeatedAid = id(commentAid.get(comment));
            if (!repeatedAid.isEmpty() && !aid.equals(repeatedAid)) return null;
        }
        for (Object comment : new Object[]{target, hint.target}) {
            String to = id(replyToUserId.get(comment));
            if (!to.isEmpty() && !me.equals(to)) return null;
        }
        // The original sparse object must still be sparse: a conflicting later binding is not ignored.
        String hintedRoot = id(replyId.get(hint.target)), hintedDirect = id(replyToReplyId.get(hint.target));
        if (!hintedRoot.isEmpty() && !root.equals(hintedRoot) || !hintedDirect.isEmpty() && !root.equals(hintedDirect)) return null;
        String ownText = string(commentText.get(original)).trim(), body = string(commentText.get(target)).trim();
        if (ownText.isEmpty() || body.isEmpty() && !AutomationReplyMedia.hasCommentImages(target)) return null;
        return new CommentReplyEvent(hint.noticeId, aid, incomingId, root, hint.senderUid,
                string(nickname.get(sender)), body, ownText, hint.createdAt, work, target, true);
    }

    /** Watermarks describe notice arrival, including replies published earlier but delivered later. */
    private long eventTime(Object notice, Object comment) throws Exception {
        long created = noticeTime.getLong(notice);
        if (created > 0) return seconds(created);
        return comment == null ? 0 : seconds(commentTime.getLong(comment));
    }

    private Method modelAccessor(HostScanReport report, ClassLoader loader, String id, Class<?> resultType) throws Exception {
        Method method = HostTargets.method(report, id, loader);
        if (!Modifier.isStatic(method.getModifiers()) || method.getReturnType() != resultType
                || !java.util.Arrays.equals(method.getParameterTypes(), new Class<?>[]{noticeClass})) {
            throw new IllegalStateException("Comment reply model accessor contract changed: " + id);
        }
        return method;
    }

    private Object resolveIncoming(Object notice) throws Exception {
        try { return invoke(incomingResolver, null, notice); }
        catch (Exception failure) { throw new ScanFailure(FailureReason.MODEL_RESOLUTION, failure); }
    }

    private NoticeModels readModels(Object notice) throws Exception {
        try {
            Object target = resolveIncoming(notice);
            Object detail = invoke(replyDetailResolver, null, notice);
            return new NoticeModels(noticeComment.get(notice), invoke(commentPayloadResolver, null, notice),
                    target, detail, noticeGeneral.get(notice),
                    detail != null && target != null && detailCurrent.get(detail) == target);
        } catch (ScanFailure failure) { throw failure; }
        catch (Exception failure) { throw new ScanFailure(FailureReason.MODEL_RESOLUTION, failure); }
    }

    private static final class NoticeModels {
        final Object legacy, payload, target, detail, general;
        final boolean detailSelected;
        NoticeModels(Object legacy, Object payload, Object target, Object detail, Object general, boolean detailSelected) {
            this.legacy = legacy; this.payload = payload; this.target = target; this.detail = detail;
            this.general = general; this.detailSelected = detailSelected;
        }
    }

    private final class ModelCounts {
        int legacy, nested, detail, noTarget;
        int parentMissing, recipientOwn, recipientOther, recipientMissing;
        int levelOneOwn, levelOneOther, levelOneMissing, parentContextReady, diagnosticErrors;
        void observe(NoticeModels model, long account) {
            if (model.target == null) noTarget++;
            else if (model.detailSelected) detail++;
            else if (model.payload != model.legacy) nested++;
            else legacy++;
            if (model.target == null) return;
            try {
                if (!id(replyId.get(model.target)).isEmpty() || !id(replyToReplyId.get(model.target)).isEmpty()) return;
                parentMissing++;
                String me = Long.toString(account), recipient = id(replyToUserId.get(model.target));
                if (recipient.isEmpty()) recipientMissing++;
                else if (me.equals(recipient)) recipientOwn++;
                else recipientOther++;
                Object original = model.detailSelected ? detailLevelOne.get(model.detail) : null;
                Object author = original == null ? null : commentAuthor(original);
                if (author == null || id(uid.get(author)).isEmpty()) levelOneMissing++;
                else if (me.equals(id(uid.get(author)))) levelOneOwn++;
                else levelOneOther++;
                Object work = model.general == null ? null : invoke(generalWork, model.general);
                if (work != null && parentlessOriginal(model, me, id(workAid.get(work)),
                        id(cid.get(model.target)), recipient) != null) parentContextReady++;
            } catch (Exception failure) { diagnosticErrors++; }
        }
        String diagnostic() {
            return "; legacy=" + legacy + "; nested=" + nested + "; detail=" + detail + "; no_target=" + noTarget
                    + "; parent_missing=" + parentMissing + "; recipient_own=" + recipientOwn
                    + "; recipient_other=" + recipientOther + "; recipient_missing=" + recipientMissing
                    + "; level1_own=" + levelOneOwn + "; level1_other=" + levelOneOther
                    + "; level1_missing=" + levelOneMissing + "; parent_context_ready=" + parentContextReady
                    + "; diagnostic_errors=" + diagnosticErrors;
        }
    }

    /** Cursor values are opaque; progress can also come from additional notice identities. */
    private final class PaginationProgress {
        final Set<String> cursors = new HashSet<>();
        final Set<String> notices = new HashSet<>();
        int stalled, totalItems;
        PageTrace last;

        PaginationProgress() { cursors.add("0:0"); }

        void observe(Page page, int number) throws Exception {
            boolean newCursor = cursors.add(page.max + ":" + page.min);
            int added = 0;
            for (Object notice : page.items) {
                String nid = id(noticeId.get(notice));
                boolean fresh = !nid.isEmpty() && notices.add("n:" + nid);
                Object target = resolveIncoming(notice);
                if (target != null) {
                    String commentId = id(cid.get(target));
                    if (!commentId.isEmpty() && notices.add("c:" + commentId)) fresh = true;
                }
                if (fresh) added++;
            }
            totalItems += page.items.size();
            stalled = newCursor || added > 0 ? 0 : stalled + 1;
            last = new PageTrace(number, page.items.size(), Long.compare(page.max, 0),
                    Long.compare(page.min, 0), newCursor, added, stalled);
        }

        void requireProgress() {
            // 0dU3:838-916 allows bounded requests after empty pages, zero cursors,
            // and repeated cursor pairs. Never turn an incomplete read into a baseline.
            if (stalled >= MAX_STALLED_PAGES) {
                throw new ScanFailure(FailureReason.PAGINATION_NO_PROGRESS, null, null, last);
            }
        }
    }

    private static final class PageTrace {
        final int number, items, maxSign, minSign, added, stalled;
        final boolean newCursor;
        PageTrace(int number, int items, int maxSign, int minSign, boolean newCursor, int added, int stalled) {
            this.number = number; this.items = items; this.maxSign = maxSign; this.minSign = minSign;
            this.newCursor = newCursor; this.added = added; this.stalled = stalled;
        }
        String diagnostic() {
            return "; page=" + number + "; items=" + items + "; max_sign=" + maxSign
                    + "; min_sign=" + minSign + "; new_cursor=" + newCursor
                    + "; new_notices=" + added + "; stalled=" + stalled;
        }
    }

    // InteractiveSessionInteract:34-77/304-317 accepts either seconds or milliseconds at this threshold.
    private static long seconds(long value) { return value >= 10_000_000_000L ? value / 1000 : value; }

    private void verifyAccount(long account, BooleanSupplier valid) throws Exception {
        checkValid(valid);
        Object service = invoke(accountService, null);
        if (account <= 0 || !currentUser.getDeclaringClass().isInstance(service)) {
            throw new ScanFailure(FailureReason.ACCOUNT_NOT_READY);
        }
        Object user = invoke(currentUser, service);
        if (!userClass.isInstance(user) || !Long.toString(account).equals(uid.get(user))) {
            throw new CancellationException("Comment reply account changed");
        }
    }

    private void checkValid(BooleanSupplier valid) {
        if (closed || Thread.currentThread().isInterrupted() || !valid.getAsBoolean()) {
            throw new CancellationException("Comment reply scan no longer valid");
        }
    }

    private CommentReplyEvent skip(String nid, String reason) { DouAiLog.event("comment_reply_inbox", "notice_skipped", reason.replace(' ', '_'), "notice_ref=" + DouAiLog.reference(nid)); safeLog("Comment reply notice skipped; nid=" + nid + "; " + reason); return null; }
    private void safeLog(String message) { try { log.accept(message); } catch (RuntimeException ignored) { } }
    private static String string(Object value) { return value instanceof String ? (String) value : ""; }
    private static String id(Object value) { String text = string(value); return "0".equals(text) ? "" : text; }
    private static Field field(Class<?> owner, String name, Class<?> expected) throws Exception {
        Field result = HostTargets.field(owner, name);
        if (result.getType() != expected) throw new IllegalStateException("Comment reply model changed: " + name);
        return result;
    }
    private static Object invoke(Method method, Object owner, Object... arguments) throws Exception {
        try { return method.invoke(owner, arguments); }
        catch (LinkageError unavailable) { throw new IllegalStateException("Host notice target is unavailable", unavailable); }
        catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof Exception) throw (Exception) cause;
            throw new IllegalStateException("Host notice invocation failed", cause);
        }
    }
    @Override public void close() {
        closed = true;
        if (threadLookup != null) { try { threadLookup.close(); } catch (RuntimeException ignored) { } }
        if (handle != null) { try { handle.unhook(); } catch (RuntimeException ignored) { } handle = null; }
    }
    private static final class Page {
        final List<?> items;
        final boolean more;
        final long max, min;
        Page(List<?> items, boolean more, long max, long min) { this.items = items; this.more = more; this.max = max; this.min = min; }
    }
}
