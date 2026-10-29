package com.douai.module.feature;

import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.Toast;

import com.douai.module.diagnostics.DouAiLog;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Reason;
import com.douai.module.config.AutomationHistoryStore.Stage;
import com.douai.module.config.SubmissionLedger;
import com.douai.module.scan.HostScanReport;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONObject;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.HttpURLConnection;
import java.net.URI;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.Duration;
import java.util.Collections;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Observes committed incoming messages and submits opt-in tasks through the native IM SDK. */
public final class NativeMessagingHooks implements AutoCloseable {
    private static final ThreadPoolExecutor RESULTS_IO = new ThreadPoolExecutor(0, 1, 30,
            TimeUnit.SECONDS, new ArrayBlockingQueue<>(64), action -> {
                Thread thread = new Thread(action, "DouAi-reply-results");
                thread.setDaemon(true);
                return thread;
            });
    private final Context context;
    private final SharedPreferences state;
    private final StreakSendLedger streakLedger;
    private final SubmissionLedger replyLedger;
    private final AutomationRateLimiter rateLimiter;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledThreadPoolExecutor worker = new ScheduledThreadPoolExecutor(1);
    private final ScheduledThreadPoolExecutor streakWorker;
    private final ExecutorService contactsWorker;
    private ScheduledFuture<?> streakTimer;
    private static volatile NativeMessagingHooks active;
    private static final Object CONTACTS_OWNER_LOCK = new Object();
    private final Object contactsLock = new Object();
    private final List<ContactRequest> contactRequests = new ArrayList<>();
    private List<ContactRequest> activeContactRequests = Collections.emptyList();
    private final ThreadLocal<Boolean> enumeratingContacts = new ThreadLocal<>();
    private boolean contactsQueued, membersLoaded;
    private long contactsRevision, loadedContactsRevision = -1;
    private volatile long loadedContactsAccountEpoch = -1;
    Supplier<ZonedDateTime> streakTime = ZonedDateTime::now;
    private boolean timeReceiverRegistered;
    private final BroadcastReceiver timeChanges = new BroadcastReceiver() {
        @Override public void onReceive(Context ignored, Intent intent) {
            if (!closed && (Intent.ACTION_TIME_CHANGED.equals(intent.getAction())
                    || Intent.ACTION_TIMEZONE_CHANGED.equals(intent.getAction()))) scheduleStreak(0);
        }
    };
    private static final long REPLY_GENERATION_TIMEOUT_MILLIS = 120_000;
    private static final long REPLY_QUEUE_TIMEOUT_MILLIS = 120_000;
    private final ReplyScheduler<ReplyTask> replyQueue = new ReplyScheduler<>(100);
    private ScheduledFuture<?> replyPump;
    private long replyPumpAt = Long.MAX_VALUE;
    private final Object replyLock = new Object();
    private ReplyTask activeReply;
    private final AtomicLong generation = new AtomicLong();
    private final Object sendLock = new Object();
    private static volatile Map<String, String> contacts = Collections.emptyMap();
    private static volatile Map<String, String> groups = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> contactProfiles = Collections.emptyMap();
    private static volatile Map<String, ProfileListEntry> groupProfiles = Collections.emptyMap();
    private static volatile Map<String, Map<String, ProfileListEntry>> groupMembers = Collections.emptyMap();
    private static volatile long contactAccount;
    private final ChatCompletionClient ai = new ChatCompletionClient();
    SpeechPreparation.Preparer speechPreparer = SpeechPreparation::prepare;
    Supplier<MediaReadCancellation> mediaReads = MediaReadCancellation::new;
    private NativeAudioMessageSender audioSender;
    private final AutomationDispatcher dispatcher;
    private final boolean ownsDispatcher;
    private final Random random = new Random();
    private final Set<String> pendingStreak = Collections.synchronizedSet(new HashSet<>());
    private final Set<ReplyTask> pendingReplies = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>(512, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> entry) { return size() > 512; }
    };
    private final BehaviorHookGroup recall;
    private final BehaviorHookGroup receive;
    private Method sdkUid;
    private Method sdkBean;
    private Method sdkConversations;
    private Method builderFactory;
    private Method builderType;
    private Method builderContent;
    private Method builderConversation;
    private Method builderBuild;
    private Method findConversation;
    private Method findConversationAsync;
    private IncomingMessageSnapshot incomingSnapshot;
    private Method allConversations;
    private Method conversationId;
    private Method conversationMembers;
    private Method conversationCore;
    private Method conversationName;
    private Method isSingle;
    private Method send;
    private Constructor<?> senderConstructor;
    private Class<?> listenerClass;
    private Class<?> gsonClass;
    private Method createText;
    private Method imProfileCache;
    private Method imProfileGet;
    private volatile LocalOptions options;
    private volatile Object sdk;
    private volatile boolean closed;
    private volatile long startedAt = System.currentTimeMillis();
    private volatile String resetToken = "";
    private final ThreadLocal<Long> localRecallActor = new ThreadLocal<>();
    private final Map<String, Long> localRecallIdentities = Collections.synchronizedMap(
            new LinkedHashMap<String, Long>(512, 0.75f, true) {
                @Override protected boolean removeEldestEntry(Map.Entry<String, Long> entry) { return size() > 512; }
            });

    private NativeMessagingHooks(Context context, LocalOptions options, Consumer<String> log,
                                 AutomationDispatcher dispatcher) {
        this(context, options, log, dispatcher, new ScheduledThreadPoolExecutor(1));
    }

    NativeMessagingHooks(Context context, LocalOptions options, Consumer<String> log,
                         AutomationDispatcher dispatcher, ScheduledThreadPoolExecutor streakWorker) {
        this(context, options, log, dispatcher, streakWorker, new ThreadPoolExecutor(0, 1, 30,
                TimeUnit.SECONDS, new ArrayBlockingQueue<>(1), action -> {
                    Thread thread = new Thread(action, "DouAi-contact-snapshot"); thread.setDaemon(true); return thread;
                }));
    }

    NativeMessagingHooks(Context context, LocalOptions options, Consumer<String> log,
                         AutomationDispatcher dispatcher, ScheduledThreadPoolExecutor streakWorker, ExecutorService contactsWorker) {
        this.context = context.getApplicationContext();
        this.streakWorker = streakWorker;
        this.contactsWorker = contactsWorker;
        this.options = options;
        this.log = log == null ? ignored -> { } : log;
        ownsDispatcher = dispatcher == null;
        this.dispatcher = ownsDispatcher ? new AutomationDispatcher(context, this.log) : dispatcher;
        this.dispatcher.setOptions(options);
        state = context.getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        streakLedger = new StreakSendLedger(state);
        replyLedger = new SubmissionLedger(context.getSharedPreferences(SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY);
        rateLimiter = new AutomationRateLimiter(context);
        resetToken = state.getString("streak.reset_token", "");
        recall = new BehaviorHookGroup("prevent_message_recall", this.log);
        receive = new BehaviorHookGroup("native_reply", this.log);
        worker.setRemoveOnCancelPolicy(true);
        streakWorker.setRemoveOnCancelPolicy(true);
        IntentFilter timeFilter = new IntentFilter();
        timeFilter.addAction(Intent.ACTION_TIME_CHANGED);
        timeFilter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        if (Build.VERSION.SDK_INT >= 33) this.context.registerReceiver(timeChanges, timeFilter, Context.RECEIVER_NOT_EXPORTED);
        else this.context.registerReceiver(timeChanges, timeFilter);
        timeReceiverRegistered = true;
    }

    public static NativeMessagingHooks install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, Context context,
                                                LocalOptions options, Consumer<String> log) {
        return install(framework, report, loader, context, options, log, null);
    }

    public static NativeMessagingHooks install(XposedInterface framework, HostScanReport report,
                                                ClassLoader loader, Context context,
                                                LocalOptions options, Consumer<String> log,
                                                AutomationDispatcher dispatcher) {
        NativeMessagingHooks hook = new NativeMessagingHooks(context, options, log, dispatcher);
        hook.installRecall(framework, report, loader);
        hook.installReceive(framework, report, loader);
        synchronized (CONTACTS_OWNER_LOCK) { active = hook; clearContacts(); contactAccount = 0; }
        hook.setOptions(options);
        return hook;
    }

    public boolean supports(String key) {
        return "prevent_message_recall".equals(key) ? recall.isActive() : receive.isActive();
    }

    public String installationFailure(String key) {
        return "prevent_message_recall".equals(key) ? recall.installationFailure() : receive.installationFailure();
    }

    public static Map<String, String> availableContacts() { checkContactAccount(); return contacts; }
    public static Map<String, String> availableGroups() { checkContactAccount(); return groups; }
    public static Map<String, ProfileListEntry> availableContactProfiles() { checkContactAccount(); return profiles(contacts, contactProfiles); }
    public static Map<String, ProfileListEntry> availableGroupProfiles() { checkContactAccount(); return profiles(groups, groupProfiles); }
    public static Map<String, ProfileListEntry> availableGroupMembers() {
        checkContactAccount();
        Map<String, ProfileListEntry> result = new LinkedHashMap<>();
        for (Map<String, ProfileListEntry> members : groupMembers.values()) result.putAll(members);
        return Collections.unmodifiableMap(result);
    }

    private static void checkContactAccount() {
        NativeMessagingHooks hook = active;
        if (hook == null) return;
        synchronized (hook.contactsLock) {
            synchronized (CONTACTS_OWNER_LOCK) {
                if (active == hook && (hook.closed || hook.ownUid() != contactAccount
                        || !NativeFollowLists.ensureAccount(Long.toString(contactAccount))
                        || hook.loadedContactsAccountEpoch != FollowListSnapshot.current().accountEpoch)) {
                    clearContacts();
                    hook.loadedContactsRevision = -1; hook.membersLoaded = false;
                }
            }
        }
    }

    /** Loads local SDK snapshots on the independent worker; cancelling only detaches this UI consumer. */
    public static AutoCloseable loadContacts(boolean includeMembers, Consumer<Boolean> completed) {
        NativeMessagingHooks hook = active;
        if (hook == null) { completed.accept(true); return () -> { }; }
        return hook.requestContacts(includeMembers, completed);
    }

    private AutoCloseable requestContacts(boolean includeMembers, Consumer<Boolean> completed) {
        long account = ownUid();
        long accountEpoch = NativeFollowLists.ensureAccount(Long.toString(account))
                ? FollowListSnapshot.current().accountEpoch : -1;
        ContactRequest request = new ContactRequest(account, accountEpoch, includeMembers, completed);
        synchronized (contactsLock) {
            if (closed || !receive.isActive() || sdk == null || account <= 0
                    || accountEpoch < 0 || accountEpoch != FollowListSnapshot.current().accountEpoch) {
                completed.accept(false);
                return request;
            }
            if (contactAccount == account && loadedContactsAccountEpoch == accountEpoch
                    && loadedContactsRevision == contactsRevision && (!includeMembers || membersLoaded)) {
                completed.accept(true);
                return request;
            }
            contactRequests.add(request);
            queueContacts();
        }
        return request;
    }

    private void queueContacts() {
        synchronized (contactsLock) {
            if (closed || contactsQueued) return;
            contactsQueued = true;
            try { contactsWorker.execute(this::loadContactSnapshot); }
            catch (RejectedExecutionException failure) {
                contactsQueued = false;
                List<ContactRequest> rejected = new ArrayList<>(contactRequests);
                contactRequests.clear();
                for (ContactRequest request : rejected) main.post(() -> {
                    if (!request.cancelled) request.completed.accept(false);
                });
            }
        }
    }

    private void loadContactSnapshot() {
        List<ContactRequest> callbacks;
        long revision;
        synchronized (contactsLock) {
            callbacks = new ArrayList<>(contactRequests);
            contactRequests.clear();
            activeContactRequests = callbacks;
            revision = contactsRevision;
        }
        boolean includeMembers = false;
        boolean wanted = callbacks.isEmpty();
        long accountEpoch = FollowListSnapshot.current().accountEpoch;
        for (ContactRequest request : callbacks) if (!request.cancelled && request.account == ownUid()
                && request.accountEpoch == accountEpoch) {
            wanted = true;
            if (request.includeMembers) includeMembers = true;
        }
        boolean success = wanted && discoverContacts(includeMembers, revision, accountEpoch, callbacks);
        for (ContactRequest request : callbacks) main.post(() -> {
            if (!request.cancelled) request.completed.accept(success && !closed && ownUid() == request.account
                    && NativeFollowLists.ensureAccount(Long.toString(request.account))
                    && FollowListSnapshot.current().accountEpoch == request.accountEpoch);
        });
        synchronized (contactsLock) {
            activeContactRequests = Collections.emptyList();
            contactsQueued = false;
            if (!contactRequests.isEmpty()) queueContacts();
        }
    }

    private static final class ContactRequest implements AutoCloseable {
        final long account;
        final long accountEpoch;
        final boolean includeMembers;
        final Consumer<Boolean> completed;
        volatile boolean cancelled;
        ContactRequest(long account, long accountEpoch, boolean includeMembers, Consumer<Boolean> completed) {
            this.account = account; this.accountEpoch = accountEpoch; this.includeMembers = includeMembers; this.completed = completed;
        }
        @Override public void close() { cancelled = true; }
    }

    /** Number of streak sends whose outcome is unknown, including unmigrated legacy pending entries. */
    public int unknownStreakCount() { return streakLedger.unknownCount(); }

    /** Read-only count for status surfaces that do not hold the installed hook instance. */
    public static int unknownStreakCount(Context context) {
        SharedPreferences state = context.getApplicationContext()
                .getSharedPreferences("douai_message_state", Context.MODE_PRIVATE);
        return StreakSendLedger.countUnknown(state);
    }

    private static Map<String, ProfileListEntry> profiles(Map<String, String> names, Map<String, ProfileListEntry> details) {
        Map<String, ProfileListEntry> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : names.entrySet()) result.put(entry.getKey(), details.getOrDefault(entry.getKey(),
                new ProfileListEntry(entry.getKey(), entry.getValue(), "")));
        return Collections.unmodifiableMap(result);
    }

    public void setOptions(LocalOptions value) {
        boolean invalidate;
        synchronized (sendLock) {
            invalidate = automationOptionsChanged(options, value);
            boolean mediaChanged = options.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                    != value.enabled(LocalOptions.REPLY_RECOGNIZE_IMAGES, true)
                    || options.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false)
                    != value.enabled(LocalOptions.REPLY_RECOGNIZE_VIDEO, false);
            options = value;
            if (invalidate) generation.incrementAndGet();
            if (mediaChanged) ai.clearHistory();
        }
        if (invalidate) {
            cancelReplies();
            dispatcher.cancelKind(AutomationHistoryStore.REPLY);
        }
        if (ownsDispatcher) dispatcher.setOptions(value);
        if (!streakLedger.updateRetryUnknownToken(value.text("streak_retry_unknown_token", ""))) {
            safeLog("Could not persist streak retry token");
        }
        String token = value.text("streak_reset_token", "");
        if (!token.equals(resetToken)) {
            synchronized (sendLock) {
                if (streakLedger.reset(token)) {
                    resetToken = token;
                    pendingStreak.clear();
                } else {
                    safeLog("Could not persist streak reset token");
                }
            }
        }
        scheduleStreak(0);
    }

    private synchronized void scheduleStreak(long minimumDelay) {
        if (streakTimer != null) streakTimer.cancel(false);
        streakTimer = null;
        LocalOptions current = options;
        if (!automatic("streak_enabled", current) || ReplyPolicy.targets(current.text("streak_targets", "")).isEmpty()) return;
        long windowDelay = nextStreakWindowDelay(current, streakTime.get());
        if (windowDelay < 0) return;
        long delay = Math.max(minimumDelay, windowDelay);
        try {
            streakTimer = streakWorker.schedule(() -> {
                synchronized (NativeMessagingHooks.this) { streakTimer = null; }
                try { streakTick(); }
                finally { scheduleStreak(nextStreakDelay()); }
            }, delay, TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException ignored) { }
    }

    static long nextStreakWindowDelay(LocalOptions options, ZonedDateTime now) {
        String start = options.text("streak_start", "").trim();
        String end = options.text("streak_end", "").trim();
        if (ReplyPolicy.inWindow(start, end, now.toLocalTime())) return 0;
        try {
            LocalTime first = LocalTime.parse(start);
            LocalTime.parse(end);
            ZonedDateTime next = now.toLocalDate().atTime(first).atZone(now.getZone());
            if (!next.isAfter(now)) next = next.plusDays(1);
            return Math.max(0, Duration.between(now, next).toMillis());
        } catch (RuntimeException invalid) { return -1; }
    }

    private long nextStreakDelay() {
        long account = ownUid();
        if (account <= 0) return 30_000;
        ZonedDateTime now = streakTime.get();
        String day = now.toLocalDate().toString();
        for (String target : ReplyPolicy.targets(options.text("streak_targets", ""))) {
            String key = "streak." + account + "." + day + "." + target;
            if (pendingStreak.contains(key) || streakLedger.canBegin(key)) return 30_000;
        }
        return Math.max(1, Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(now.getZone())).toMillis());
    }

    private static boolean automationOptionsChanged(LocalOptions previous, LocalOptions next) {
        if (!AiModelSettings.sameAutomationSelection(previous, next, AutomationHistoryStore.REPLY)) return true;
        if (!previous.samePrefix(next, "tts_")) return true;
        for (String key : new String[]{"native_auto_reply", "reply_global", "reply_targets",
                "reply_mode", "reply_voice", "reply_prompt", "reply_delay_seconds", "reply_daily_limit",
                LocalOptions.REPLY_RECOGNIZE_IMAGES, LocalOptions.REPLY_RECOGNIZE_VIDEO,
                "reply_user_cooldown_minutes",
                "auto_message_text", "reply_keywords",
                "reply_scope_mode", "reply_scope_relations", "reply_scope_fields", "reply_scope_keywords",
                "reply_filter_mode", "reply_filter_keywords", "reply_filter_fields",
                "reply_private_chats", "reply_group_chats",
                "reply_group_scope_mode", "reply_group_targets", "reply_group_mode", "reply_group_members",
                "reply_random", AiModelSettings.REPLY_MODEL_ID, "streak_enabled", "streak_targets", "streak_content",
                "streak_content_api", "streak_start", "streak_end"}) {
            boolean defaultEnabled = "reply_private_chats".equals(key) || LocalOptions.REPLY_RECOGNIZE_IMAGES.equals(key);
            if (previous.enabled(key, defaultEnabled) != next.enabled(key, defaultEnabled)
                    || !previous.text(key, "").equals(next.text(key, ""))) return true;
        }
        return false;
    }

    private void installRecall(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        try {
            Method update = HostTargets.method(report, "adv_im_db_update", loader);
            sdkUid = HostTargets.method(report, "adv_im_uid", loader);
            recall.add(framework, update, chain -> {
                captureSdk(chain.getThisObject());
                if (!"msg".equals(chain.getArg(0)) || !(chain.getArg(1) instanceof ContentValues)) return chain.proceed();
                ContentValues original = (ContentValues) chain.getArg(1);
                ContentValues identified = original;
                if (localRecallActor.get() != null && original.getAsString("ext") != null) {
                    try {
                        JSONObject extension = new JSONObject(original.getAsString("ext"));
                        if (extension.optString("s:recall_uid", "").isEmpty()) {
                            extension.put("s:recall_uid", Long.toString(localRecallActor.get()));
                            identified = new ContentValues(original);
                            identified.put("ext", extension.toString());
                        }
                    } catch (org.json.JSONException ignored) { }
                }
                ContentValues replacement = MessageRecallPolicy.protect(identified, ownUid(), options);
                if (replacement == identified) { DouAiLog.event("prevent_message_recall", "decision", "policy_passthrough", "enabled=" + options.enabled("prevent_message_recall")); return chain.proceed(); }
                DouAiLog.Operation trace = DouAiLog.begin("prevent_message_recall", "message_db_update");
                trace.stage("db_update_replaced", "policy=MessageRecallPolicy;contents_logged=false");
                Object[] arguments = chain.getArgs().toArray();
                arguments[1] = replacement;
                Object result;
                try { result = chain.proceed(arguments); } catch (Throwable failure) { trace.error("db_update", failure); trace.finish(DouAiLog.Outcome.UNCONFIRMED, "db_update_threw", "write_result_unknown=true"); throw failure; }
                trace.finish(result instanceof Number && ((Number) result).intValue() > 0 ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED, "protected_db_update_returned", "rows_positive=" + (result instanceof Number && ((Number) result).intValue() > 0) + ";scope=local_database");
                if (result instanceof Number && ((Number) result).intValue() > 0) {
                    String uuid = MessageRecallPolicy.uuid(original, chain.getArg(2), chain.getArg(3));
                    if (uuid != null) markRecall(ownUid(), uuid);
                }
                return result;
            });
            Method isRecalled = HostTargets.method(report, "adv_im_message_recalled", loader);
            Method extension = HostTargets.method(report, "adv_im_message_ext", loader);
            Method uuid = HostTargets.method(report, "adv_im_message_uuid", loader);
            recall.add(framework, isRecalled, chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(result) || !options.enabled("prevent_message_recall")) return result;
                try {
                    Object raw = extension.invoke(chain.getThisObject());
                    if (!(raw instanceof Map<?, ?>)) return result;
                    JSONObject values = new JSONObject((Map<?, ?>) raw);
                    String id = (String) uuid.invoke(chain.getThisObject());
                    Long localActor = localRecallIdentities.get(ownUid() + "." + id);
                    if (localActor == null && id != null) {
                        long recorded = state.getLong("recall.actor." + ownUid() + "." + id, 0);
                        if (recorded > 0) localActor = recorded;
                    }
                    if (values.optString("s:recall_uid", "").isEmpty() && localActor != null) {
                        values.put("s:recall_uid", Long.toString(localActor));
                    }
                    if (!MessageRecallPolicy.shouldProtect(values, ownUid(), options)) return result;
                    if (id != null) markRecall(ownUid(), id);
                    DouAiLog.event("prevent_message_recall", "result", "recalled_state_overridden", "native_result=true;module_result=false;message_ref=" + DouAiLog.reference(id));
                    return false;
                } catch (Exception failure) { recall.fail(failure); return result; }
            });
            Method localRecall = HostTargets.method(report, "adv_im_local_recall", loader);
            recall.add(framework, localRecall, chain -> {
                captureSdk(chain.getThisObject());
                long account = ownUid();
                String id = null;
                try { id = (String) uuid.invoke(chain.getArg(0)); }
                catch (ReflectiveOperationException | RuntimeException ignored) { }
                if (account > 0 && id != null) {
                    localRecallIdentities.put(account + "." + id, account);
                    state.edit().putLong("recall.actor." + account + "." + id, account).apply();
                }
                localRecallActor.set(account);
                try { return chain.proceed(); }
                finally { localRecallActor.remove(); }
            });
            log.accept("Native message recall protection registered");
        } catch (Throwable failure) { recall.fail(failure); }
    }

    private void installReceive(XposedInterface framework, HostScanReport report, ClassLoader loader) {
        try {
            sdkUid = HostTargets.method(report, "adv_im_uid", loader);
            sdkBean = HostTargets.method(report, "adv_im_bean", loader);
            sdkConversations = HostTargets.method(report, "adv_im_conversations", loader);
            builderFactory = HostTargets.method(report, "adv_im_builder_factory", loader);
            builderType = HostTargets.method(report, "adv_im_builder_type", loader);
            builderContent = HostTargets.method(report, "adv_im_builder_content", loader);
            builderConversation = HostTargets.method(report, "adv_im_builder_conversation", loader);
            builderBuild = HostTargets.method(report, "adv_im_builder_build", loader);
            findConversation = HostTargets.method(report, "adv_im_conversation", loader);
            findConversationAsync = HostTargets.method(report, "adv_im_conversation_async", loader);
            allConversations = HostTargets.method(report, "adv_im_all_conversations", loader);
            conversationId = HostTargets.method(report, "adv_im_conversation_id", loader);
            conversationMembers = HostTargets.method(report, "adv_im_conversation_members", loader);
            conversationCore = HostTargets.method(report, "adv_im_conversation_core", loader);
            conversationName = HostTargets.method(report, "adv_im_conversation_name", loader);
            isSingle = HostTargets.method(report, "adv_im_single_chat", loader);
            createText = HostTargets.method(report, "adv_im_text_content", loader);
            send = HostTargets.method(report, "adv_im_send", loader);
            listenerClass = Class.forName("com.bytedance.im.core.client.callback.IRequestListener", false, loader);
            senderConstructor = send.getDeclaringClass().getConstructor(sdkUid.getDeclaringClass(), listenerClass);
            gsonClass = Class.forName("com.google.gson.Gson", false, loader);
            incomingSnapshot = new IncomingMessageSnapshot(builderBuild.getReturnType());
            try { audioSender = new NativeAudioMessageSender(framework, report, loader); }
            catch (Exception failure) { safeLog("Native automatic audio sender unavailable: " + failure.getClass().getSimpleName()); }
            Method insert = HostTargets.method(report, "adv_im_db_insert", loader);
            receive.add(framework, insert, chain -> {
                captureSdk(chain.getThisObject());
                ContentValues incoming = "msg".equals(chain.getArg(0)) && chain.getArg(2) instanceof ContentValues
                        ? new ContentValues((ContentValues) chain.getArg(2)) : null;
                Object result = chain.proceed();
                try {
                    if (incoming != null && result instanceof Number && ((Number) result).longValue() >= 0) {
                        markContactsDirty();
                        onIncoming(incoming, "database");
                    }
                } catch (Throwable failure) { receive.fail(failure); }
                return result;
            });
            Method contextReady = HostTargets.method(report, "adv_im_conversations", loader);
            receive.add(framework, contextReady, chain -> {
                Object result = chain.proceed();
                if (!Boolean.TRUE.equals(enumeratingContacts.get())) observeSdk(chain.getThisObject());
                return result;
            });
            Method globalReceived = HostTargets.method(report, "adv_im_global_received", loader);
            receive.add(framework, globalReceived, chain -> {
                captureSdk(chain.getThisObject());
                Object raw = chain.getArg(0);
                List<?> messages = raw instanceof List<?> ? new ArrayList<>((List<?>) raw) : Collections.emptyList();
                Object result = chain.proceed();
                captureSdk(chain.getThisObject());
                if (!messages.isEmpty()) markContactsDirty();
                for (Object message : messages) {
                    try {
                        ContentValues values = incomingSnapshot.copy(message);
                        if (values != null) onIncoming(values, "global");
                    } catch (ReflectiveOperationException | RuntimeException invalid) {
                        safeLog("Automatic reply receipt skipped source=global reason=invalid_message");
                    }
                }
                return result;
            });
            try {
                imProfileCache = HostTargets.method(report, "relation_im_user_cache", loader);
                imProfileGet = Class.forName("com.bytedance.im.core.model.cache.IMMemoryCache", false, loader)
                        .getMethod("get", Object.class);
            } catch (Exception ignored) { }
            log.accept("Native IM receive and sender contracts registered; global receipts and cold conversations enabled");
        } catch (Throwable failure) { receive.fail(failure); }
    }

    private void captureSdk(Object receiver) {
        if (closed) return;
        Object value = HostTargets.read(receiver, "imSdkContext");
        if (value != null && sdkUid != null && sdkUid.getDeclaringClass().isInstance(value)) observeSdk(value);
    }

    private void markContactsDirty() {
        synchronized (contactsLock) { contactsRevision++; membersLoaded = false; }
    }

    private void observeSdk(Object value) {
        if (closed) return;
        boolean changed;
        long account;
        synchronized (contactsLock) {
            synchronized (CONTACTS_OWNER_LOCK) {
                if (closed || active != null && active != this) return;
                Object previous = sdk;
                sdk = value;
                account = ownUid();
                changed = previous != value || contactAccount != account;
                if (changed) {
                    clearContacts(); contactAccount = account;
                    loadedContactsRevision = -1; membersLoaded = false;
                }
                contactsRevision++;
            }
        }
        if (changed) {
            if (account > 0 && receive.isActive()) queueContacts();
            scheduleStreak(0);
        }
    }

    private long ownUid() {
        try { return sdk == null || sdkUid == null ? 0 : ((Number) sdkUid.invoke(sdk)).longValue(); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return 0; }
    }

    private void markRecall(long account, String uuid) {
        String key = "recall." + account + "." + uuid;
        if (!state.getBoolean(key, false)) state.edit().putBoolean(key, true).apply();
    }

    private boolean automatic(String key, LocalOptions current) {
        return !closed && receive.isActive() && current.enabled(key)
                && ("native_auto_reply".equals(key) || current.enabled("automation_enabled"));
    }

    private void onIncoming(ContentValues values, String source) {
        LocalOptions current = options;
        if (!automatic("native_auto_reply", current)) { DouAiLog.event("auto_reply", "eligibility", "disabled_or_not_ready", "source=" + source + ";enabled=" + current.enabled("native_auto_reply")); return; }
        long account = ownUid();
        if (account <= 0) {
            DouAiLog.event("auto_reply", "receipt_skipped", "account_unavailable", "source=" + source);
            safeLog("Automatic reply receipt skipped source=" + source + " reason=account_unavailable");
            return;
        }
        synchronized (contactsLock) {
            synchronized (CONTACTS_OWNER_LOCK) {
                if (closed || active != this) return;
                if (contactAccount != account) { clearContacts(); contactAccount = account; }
            }
        }
        String currentConversation = values.getAsString("conversation_id");
        Map<String, ProfileListEntry> members = groupMembers.get(currentConversation);
        Long senderUid;
        try { senderUid = values.getAsLong("sender"); }
        catch (RuntimeException ignored) { return; }
        if (members != null && senderUid != null && values.getAsString("sender_name") == null) {
            ProfileListEntry profile = members.get(Long.toString(senderUid));
            if (profile != null && !profile.name.isEmpty()) values.put("sender_name", profile.name);
        }
        if (senderUid != null && values.getAsString("sender_name") == null) {
            ProfileListEntry profile = memberProfile(senderUid.toString());
            if (!profile.name.isEmpty()) values.put("sender_name", profile.name);
        }
        if (!NativeFollowLists.ensureAccount(Long.toString(account))) {
            DouAiLog.event("auto_reply", "receipt_skipped", "account_changed", "source=" + source);
            safeLog("Automatic reply receipt skipped source=" + source + " reason=account_changed");
            return;
        }
        ReplyPolicy.Incoming incoming = ReplyPolicy.incoming(values, account, startedAt, current);
        if (incoming == null) {
            DouAiLog.event("auto_reply", "receipt_skipped", "policy", "source=" + source);
            safeLog("Automatic reply receipt skipped source=" + source + " reason=policy");
            return;
        }
        String recipient = Long.toString(incoming.sender);
        if (!rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, current)) {
            DouAiLog.event("auto_reply", "receipt_skipped", "allowance", "source=" + source);
            safeLog("Automatic reply receipt skipped source=" + source + " reason=allowance");
            return;
        }
        String conversation = incoming.conversation;
        String text = incoming.text;
        String identity = account + ":" + conversation + ":" + incoming.serverId;
        if (replyLedger.contains(SubmissionLedger.identity(identity))) {
            DouAiLog.event("auto_reply", "receipt_skipped", "submission_claimed", "source=" + source);
            safeLog("Automatic reply receipt skipped source=" + source + " reason=submission_claimed");
            return;
        }
        synchronized (seen) {
            if (seen.put(identity, true) != null) {
                DouAiLog.event("auto_reply", "receipt_skipped", "duplicate", "source=" + source);
                safeLog("Automatic reply receipt skipped source=" + source + " reason=duplicate");
                return;
            }
        }
        long epoch = generation.get();
        int delay = Math.max(2, Math.min(3600, current.integer("reply_delay_seconds", 2)));
        ReplyTask operation = new ReplyTask(account, epoch, identity, incoming, delay, values, current);
        synchronized (replyLock) {
            if (!replyQueue.offer(conversation, operation, operation.readyAt)) {
                synchronized (seen) { seen.remove(identity); }
                operation.reason = Reason.QUEUE_FULL;
                operation.reportFailure();
                DouAiLog.event("auto_reply", "receipt_skipped", "queue_full", "source=" + source);
                safeLog("Automatic reply receipt skipped source=" + source + " reason=queue_full");
                return;
            }
            pendingReplies.add(operation);
            scheduleReplyPump();
        }
        safeLog("Automatic reply receipt queued source=" + source + " kind=" + (incoming.group ? "group" : "private"));
        main.postDelayed(operation.watchdog, 1000);
    }

    /** Called with replyLock held; only one generation and one delayed wakeup can exist. */
    private void scheduleReplyPump() {
        if (closed || activeReply != null) return;
        long readyAt = replyQueue.nextReadyAt();
        if (replyPump != null && !replyPump.isDone()) {
            if (replyPumpAt <= readyAt) return;
            replyPump.cancel(false);
        }
        replyPump = null;
        replyPumpAt = readyAt;
        if (readyAt == Long.MAX_VALUE) return;
        try {
            replyPump = worker.schedule(this::drainReplyQueue,
                    Math.max(0, readyAt - SystemClock.elapsedRealtime()), TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.RejectedExecutionException rejected) {
            List<ReplyTask> abandoned;
            synchronized (pendingReplies) { abandoned = new ArrayList<>(pendingReplies); }
            for (ReplyTask task : abandoned) task.cancel(false);
        }
    }

    private void drainReplyQueue() {
        ReplyTask operation;
        synchronized (replyLock) {
            replyPump = null;
            replyPumpAt = Long.MAX_VALUE;
            operation = replyQueue.poll(SystemClock.elapsedRealtime());
            if (operation == null) { scheduleReplyPump(); return; }
            activeReply = operation;
        }
        FutureTask<Void> execution = new FutureTask<>(() -> {
            generateReply(operation);
            return null;
        });
        operation.attach(execution);
        try { execution.run(); }
        finally {
            synchronized (replyLock) {
                if (activeReply == operation) activeReply = null;
                scheduleReplyPump();
            }
        }
    }

    private void generateReply(ReplyTask operation) {
        operation.trace.wrap(() -> generateReplyTraced(operation)).run();
    }

    private void generateReplyTraced(ReplyTask operation) {
        long account = operation.account;
        long epoch = operation.epoch;
        ReplyPolicy.Incoming incoming = operation.incoming;
        String identity = operation.identity;
        String conversation = incoming.conversation;
        String recipient = Long.toString(incoming.sender);
        String text = incoming.text;
        LocalOptions current = operation.settings;
        ContentValues values = operation.values;
        if (!operation.begin()) { operation.release(); return; }
        try {
            if (!replyStillAllowed(values, account, current)) { operation.trace.stage("eligibility_rejected", "reason=receipt_policy_changed"); return; }
            if (!rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, options)) { operation.trace.stage("eligibility_rejected", "reason=allowance_or_cooldown"); return; }
            operation.conversation = resolveReplyConversation(operation);
            if (operation.conversation == null) {
                synchronized (seen) { seen.remove(identity); }
                operation.trace.stage("conversation_unavailable", "lookup_completed=true;ready=false");
                safeLog("Automatic reply skipped reason=conversation_unavailable");
                return;
            }
            if (!operation.current()) return;
            operation.outputName = incoming.group ? groups.getOrDefault(conversation, conversation)
                    : contacts.getOrDefault(Long.toString(incoming.sender), Long.toString(incoming.sender));
            BooleanSupplier valid = () -> operation.current() && replyStillAllowed(values, account, current)
                    && conversationExists(conversation, incoming.group, operation.conversation)
                    && rateLimiter.canSend(AutomationHistoryStore.REPLY, account, recipient, options);
            operation.preparation("waiting", valid);
            String modelName = "";
            AiModelSettings models = "ai".equals(current.text("reply_mode", "fixed"))
                    ? AiModelSettings.readForAutomation(current, AutomationHistoryStore.REPLY) : null;
            operation.stage = Stage.MEDIA;
            operation.preparation("media", valid);
            long mediaStarted = SystemClock.elapsedRealtime();
            operation.stageStarted = mediaStarted;
            List<ChatImage> images = models == null ? Collections.emptyList()
                    : AutomationReplyMedia.loadIncoming(context, incoming.imageUrls, incoming.videoUrl,
                            current, operation.mediaCancellation);
            operation.mediaMillis = SystemClock.elapsedRealtime() - mediaStarted;
            if (!operation.current()) return;
            String secondary = current.text("reply_prompt", "自然简洁地回复对方，只输出回复正文。");
            String response;
            operation.stage = Stage.GENERATION;
            operation.preparation("generating", valid);
            long generationStarted = SystemClock.elapsedRealtime();
            operation.stageStarted = generationStarted;
            if (models != null) {
                modelName = models.selected().modelName;
                operation.modelName = modelName;
                response = ai.reply("reply:" + account + ":" + conversation, text, images, models,
                        secondary, false, "", imageCount -> {
                            operation.requestImageCount = imageCount;
                            operation.modelRequestSent = true;
                            operation.trace.stage("model_request_sent", "images=" + imageCount + ";business_success=false");
                        });
            } else response = ReplyPolicy.reply(text, current, random);
            operation.generationMillis = SystemClock.elapsedRealtime() - generationStarted;
            if (!operation.current()) return;
            if (!replyStillAllowed(values, account, current)) return;
            if (response == null || response.trim().isEmpty()) { operation.trace.stage("generation_empty", "usable_output=false"); return; }
            SpeechPreparation.Audio audio;
            if (current.enabled("reply_voice")) {
                if (audioSender == null) throw new IllegalStateException("Native audio sender unavailable");
                operation.stage = Stage.SPEECH;
                operation.response = response;
                operation.preparation("speech", valid);
                audio = speechPreparer.prepare(context, response, current, 120_000, valid);
                if (!valid.getAsBoolean()) { audio.delete(); return; }
            } else audio = null;
            if (!operation.handoff()) { if (audio != null) audio.delete(); return; }
            java.util.concurrent.atomic.AtomicBoolean submitted = new java.util.concurrent.atomic.AtomicBoolean();
            operation.response = response;
            operation.trace.stage("generation_ready", "chars=" + response.length() + ";voice=" + (audio != null));
            operation.voice = audio != null;
            operation.stage = Stage.QUEUE;
            dispatcher.prepare(operation.entry("pending"),
                    valid,
                    done -> {
                        if (!valid.getAsBoolean()) { done.accept("cancelled"); return; }
                        AtomicReference<AutomationRateLimiter.Reservation> reservation = new AtomicReference<>();
                        try {
                            Consumer<Boolean> acknowledged = success -> {
                                operation.acknowledgmentReceived = true;
                                operation.trace.stage("native_callback", "acknowledged_success=" + success + ";native_invoked=" + operation.nativeInvoked);
                                submitReplyResult(operation, () -> {
                                    AutomationRateLimiter.Reservation active = reservation.get();
                                    String outcome = operation.nativeOutcome(success, active != null);
                                    if (!success && active != null && !"submitted".equals(outcome)) rateLimiter.release(active);
                                    if (success && models != null) {
                                        synchronized (sendLock) {
                                            // Selection changes clear history under this same lock. An older send's late
                                            // acknowledgment must not put its images back into the current context.
                                            if (!closed && generation.get() == epoch && ownUid() == account
                                                    && !automationOptionsChanged(current, options)) {
                                                try {
                                                    ai.rememberReply("reply:" + account + ":" + conversation, text, images, models, secondary, response);
                                                } catch (Exception failure) {
                                                    safeLog("Could not retain acknowledged reply context");
                                                }
                                            }
                                        }
                                    }
                                    done.accept(outcome);
                                }, () -> done.accept(operation.nativeInvoked ? "submitted" : "cancelled"));
                            };
                            BooleanSupplier reserve = () -> {
                                if (!operation.current()
                                        || !replyStillAllowed(values, account, options)) return false;
                                reservation.set(rateLimiter.tryReserve(AutomationHistoryStore.REPLY,
                                        account, recipient, options));
                                if (reservation.get() == null) { operation.reason = Reason.ALLOWANCE_EXHAUSTED; return false; }
                                SubmissionLedger.ReserveResult reserved = replyLedger.reserve(operation.ledgerIdentity,
                                        operation.attempt, System.currentTimeMillis(), SubmissionLedger.accountScope(Long.toString(account)));
                                if (reserved != SubmissionLedger.ReserveResult.RESERVED) {
                                    rateLimiter.release(reservation.get());
                                    reservation.set(null);
                                    operation.reason = reserved == SubmissionLedger.ReserveResult.FULL ? Reason.LEDGER_FULL
                                            : reserved == SubmissionLedger.ReserveResult.DUPLICATE ? Reason.DUPLICATE : Reason.LEDGER_WRITE_FAILED;
                                    safeLog("Automatic reply reservation rejected: " + reserved.name());
                                    return false;
                                }
                                operation.reserved = true;
                                operation.stage = Stage.SEND;
                                operation.sendStarted = SystemClock.elapsedRealtime();
                                return true;
                            };
                            if (audio == null) sendText(conversation, response, account, epoch,
                                    incoming.group, acknowledged, reserve, operation.conversation, operation::invokingNative);
                            else sendAudio(conversation, audio, account, epoch, incoming.group,
                                    acknowledged, reserve, submitted, operation.conversation, operation::invokingNative);
                        } catch (Exception failure) {
                            if (operation.acknowledgmentReceived) return;
                            if (operation.nativeInvoked) {
                                operation.unknown = true;
                                operation.reason = Reason.SUBMITTED_UNCONFIRMED;
                                submitReplyResult(operation, () -> {
                                    replyLedger.complete(operation.ledgerIdentity, operation.attempt, "unknown", System.currentTimeMillis());
                                    done.accept("submitted");
                                }, () -> done.accept("submitted"));
                            } else {
                                if (operation.reason == Reason.NONE) operation.reason = Reason.SEND_FAILED;
                                submitReplyResult(operation, () -> {
                                    operation.abandon();
                                    AutomationRateLimiter.Reservation active = reservation.get();
                                    if (active != null) rateLimiter.release(active);
                                    done.accept("failed");
                                }, () -> done.accept("cancelled"));
                            }
                        }
                    }, outcome -> {
                        if ("submitted".equals(outcome)) {
                            operation.unknown = true;
                            operation.reason = Reason.SUBMITTED_UNCONFIRMED;
                        }
                        submitReplyResult(operation, () -> {
                            if ("submitted".equals(outcome)) {
                                replyLedger.complete(operation.ledgerIdentity, operation.attempt, "unknown", System.currentTimeMillis());
                            } else if (!operation.nativeInvoked) operation.abandon();
                            if (audio != null && !submitted.get()) audio.delete();
                            operation.recordOutcome(outcome);
                        }, () -> {
                            if (audio != null && !submitted.get()) audio.delete();
                        });
                    });
        } catch (Exception failure) {
            operation.trace.error(operation.stage.name().toLowerCase(java.util.Locale.ROOT), failure);
            if (operation.current()) {
                operation.reason = operation.stage == Stage.MEDIA ? Reason.MEDIA_FAILED
                        : operation.stage == Stage.SPEECH ? Reason.SPEECH_FAILED : Reason.GENERATION_FAILED;
                if (operation.stage == Stage.GENERATION && failure instanceof ChatCompletionClient.ReplyFailure) {
                    ChatCompletionClient.ReplyFailure replyFailure = (ChatCompletionClient.ReplyFailure) failure;
                    int status = replyFailure.httpStatus();
                    operation.reason = replyFailure.kind() != ChatCompletionClient.ReplyFailure.Kind.HTTP ? Reason.INVALID_RESPONSE
                            : status == 401 || status == 403 ? Reason.AI_AUTH_FAILED
                            : status == 429 ? Reason.AI_RATE_LIMITED : Reason.AI_HTTP_ERROR;
                }
                operation.reportFailure();
            }
            safeLog("Automatic reply failed: " + failure.getClass().getSimpleName());
        } finally {
            operation.release();
        }
    }

    private void submitReplyResult(ReplyTask operation, Runnable result, Runnable overloaded) {
        try { RESULTS_IO.execute(operation.trace.wrap(result)); }
        catch (RejectedExecutionException full) {
            if (operation.nativeInvoked) {
                operation.unknown = true;
                operation.reason = Reason.SUBMITTED_UNCONFIRMED;
            } else if (operation.reason == Reason.NONE) operation.reason = Reason.CANCELLED;
            operation.trace.stage("result_persistence_rejected", "native_invoked=" + operation.nativeInvoked + ";outcome_conservative=true");
            operation.trace.finish(operation.nativeInvoked ? DouAiLog.Outcome.UNCONFIRMED : DouAiLog.Outcome.CANCELLED, "result_persistence_rejected", "native_invoked=" + operation.nativeInvoked + ";durable_completion=false");
            safeLog("Automatic reply result queue is full; outcome remains conservative");
            overloaded.run();
        }
    }

    private void cancelReplies() {
        List<ReplyTask> tasks;
        synchronized (pendingReplies) { tasks = new ArrayList<>(pendingReplies); }
        for (ReplyTask task : tasks) if (closed || task.epoch != generation.get()) task.cancel(false);
    }

    /** Owns cancellation until generation hands the result to the existing preview/send lifecycle. */
    private final class ReplyTask {
        final DouAiLog.Operation trace;
        final long account;
        final long epoch;
        final String identity;
        final String ledgerIdentity;
        final String attempt = UUID.randomUUID().toString();
        final ContentValues values;
        final LocalOptions settings;
        final long readyAt;
        final ReplyPolicy.Incoming incoming;
        final Object sdkContext;
        volatile Object conversation;
        final long createdAt = System.currentTimeMillis();
        final MediaReadCancellation mediaCancellation = mediaReads.get();
        final Runnable watchdog = this::watch;
        final java.util.concurrent.atomic.AtomicBoolean failureReported = new java.util.concurrent.atomic.AtomicBoolean();
        volatile java.util.concurrent.Future<?> future;
        volatile boolean cancelled;
        volatile boolean finished;
        volatile long deadline;
        volatile int requestImageCount = -1;
        volatile boolean modelRequestSent;
        volatile String modelName = "";
        volatile Reason reason = Reason.NONE;
        volatile Stage stage = Stage.QUEUE;
        volatile long queueMillis = -1, mediaMillis = -1, generationMillis = -1, sendStarted = -1, stageStarted = -1;
        volatile boolean began, reserved, nativeInvoked, voice, unknown, acknowledgmentReceived, handedOff;
        volatile String response = "", outputName = "";
        volatile String acknowledgedOutcome;
        volatile String lastGateReason = "";

        ReplyTask(long account, long epoch, String identity, ReplyPolicy.Incoming incoming, int delay, ContentValues values, LocalOptions settings) {
            this.account = account;
            this.epoch = epoch;
            this.identity = identity;
            trace = AutomationTrace.start("auto_reply", identity, incoming.group ? "group_message" : "private_message",
                    settings.diagnosticRevision());
            trace.stage("queue_admitted", "delay_seconds=" + delay + ";conversation_ref=" + DouAiLog.reference(incoming.conversation) + ";incoming_ref=" + DouAiLog.reference(incoming.serverId));
            ledgerIdentity = SubmissionLedger.identity(identity);
            this.values = new ContentValues(values);
            this.settings = settings;
            this.incoming = incoming;
            sdkContext = sdk;
            readyAt = SystemClock.elapsedRealtime() + delay * 1000L;
            deadline = readyAt + REPLY_QUEUE_TIMEOUT_MILLIS;
        }

        void attach(java.util.concurrent.Future<?> value) {
            future = value;
            if (cancelled) value.cancel(true);
        }

        boolean current() {
            String rejected = cancelled ? "task_cancelled" : sdk != sdkContext ? "sdk_changed"
                    : generation.get() != epoch ? "configuration_changed" : ownUid() != account ? "account_changed"
                    : !automatic("native_auto_reply", options) ? "disabled_or_not_ready"
                    : !NativeFollowLists.ensureAccount(Long.toString(account)) ? "relationship_account_changed" : "";
            if (!rejected.isEmpty()) {
                lastGateReason = rejected;
                trace.wrap(() -> DouAiLog.event("auto_reply", "eligibility_rejected", rejected, "stage=" + stage)).run();
            }
            return rejected.isEmpty();
        }

        boolean begin() {
            if (SystemClock.elapsedRealtime() >= deadline) { cancel(true); return false; }
            synchronized (replyLock) {
                if (!current()) return false;
                began = true;
                trace.stage("generation_started", "queue_deadline_met=true;generation_timeout_ms=" + REPLY_GENERATION_TIMEOUT_MILLIS);
                queueMillis = Math.max(0, SystemClock.elapsedRealtime() - readyAt);
                deadline = SystemClock.elapsedRealtime() + REPLY_GENERATION_TIMEOUT_MILLIS;
                activeReply = this;
                return true;
            }
        }

        boolean handoff() {
            boolean expired;
            synchronized (this) {
                if (!current()) return false;
                expired = SystemClock.elapsedRealtime() >= deadline;
                // The preview/native sender now owns completion; generation timeout cannot relabel a send.
                if (!expired) { finished = true; handedOff = true; }
            }
            if (expired) { cancel(true); return false; }
            release();
            return true;
        }

        void watch() {
            if (finished) return;
            if (!current()) { cancel(false); return; }
            if (SystemClock.elapsedRealtime() >= deadline) { cancel(true); return; }
            main.postDelayed(watchdog, 1000);
        }

        void cancel(boolean timeout) {
            synchronized (this) {
                if (finished) return;
                cancelled = true;
                finished = true;
            }
            trace.finish(timeout ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.CANCELLED, timeout ? began ? "generation_timeout" : "queue_expired" : "configuration_account_or_lifecycle_changed", "began=" + began + ";native_invoked=" + nativeInvoked + ";remote_outcome=" + (nativeInvoked ? "unknown" : "not_invoked"));
            mediaCancellation.cancel();
            synchronized (replyLock) {
                // An old task must never disconnect the next task's AI request.
                if (activeReply == this) ai.cancelCurrentRequest();
                java.util.concurrent.Future<?> value = future;
                if (value != null) value.cancel(true);
            }
            main.removeCallbacks(watchdog);
            replyQueue.remove(this);
            pendingReplies.remove(this);
            if (!handedOff) dispatcher.discardReplyPreparation(identity);
            if (timeout && generation.get() == epoch && ownUid() == account
                    && automatic("native_auto_reply", options)) {
                reason = began ? Reason.GENERATION_TIMEOUT : Reason.QUEUE_EXPIRED;
                reportFailure();
                safeLog(began ? "Automatic reply generation deadline reached" : "Automatic reply queue deadline reached");
            }
        }

        void release() {
            if (!handedOff && !cancelled && !failureReported.get()) trace.finish(DouAiLog.Outcome.SKIPPED, "generation_not_handed_off", "last_gate=" + lastGateReason + ";reason=" + reason + ";stage=" + stage + ";model_sent=" + modelRequestSent + ";native_invoked=" + nativeInvoked);
            synchronized (this) { finished = true; }
            synchronized (replyLock) { if (activeReply == this) activeReply = null; }
            main.removeCallbacks(watchdog);
            replyQueue.remove(this);
            pendingReplies.remove(this);
            if (!handedOff) dispatcher.discardReplyPreparation(identity);
        }

        void preparation(String phase, BooleanSupplier valid) {
            trace.stage(phase, "model_sent=" + modelRequestSent + ";images=" + requestImageCount);
            dispatcher.trackReply(entry("pending"), phase, valid, () -> cancel(false));
        }

        void invokingNative() {
            if (!replyLedger.isReserved(ledgerIdentity, attempt)) {
                reason = Reason.LEDGER_WRITE_FAILED;
                throw new IllegalStateException("Reply reservation unavailable");
            }
            nativeInvoked = true;
            trace.stage("native_invoking", "durable_reservation=true;business_success=false");
        }

        void abandon() {
            if (reserved && !nativeInvoked) replyLedger.abandon(ledgerIdentity, attempt);
        }

        String nativeOutcome(boolean success, boolean allowanceReserved) {
            if (!success && unknown && nativeInvoked) {
                replyLedger.complete(ledgerIdentity, attempt, "unknown", System.currentTimeMillis());
                reason = Reason.SUBMITTED_UNCONFIRMED;
                acknowledgedOutcome = "submitted";
                return acknowledgedOutcome;
            }
            if (reserved) {
                if (nativeInvoked) {
                    boolean persisted = replyLedger.complete(ledgerIdentity, attempt, success ? "sent" : "failed", System.currentTimeMillis());
                    trace.stage("submission_ledger_result", "native_ack_success=" + success + ";completion_persisted=" + persisted);
                    if (!success && !persisted) {
                        reason = Reason.SUBMITTED_UNCONFIRMED;
                        acknowledgedOutcome = "submitted";
                        return acknowledgedOutcome;
                    }
                } else abandon();
            }
            reason = success ? Reason.NONE : nativeInvoked ? Reason.SEND_FAILED
                    : reason == Reason.NONE ? Reason.INVALIDATED : reason;
            acknowledgedOutcome = success ? "sent" : allowanceReserved ? "failed" : "cancelled";
            return acknowledgedOutcome;
        }

        AutomationHistoryStore.Entry entry(String status) {
            return new AutomationHistoryStore.Entry(identity, AutomationHistoryStore.REPLY,
                    incoming.conversation, outputName.isEmpty() ? Long.toString(incoming.sender) : outputName,
                    input(incoming), response, modelName, status, createdAt, System.currentTimeMillis(),
                    requestImageCount, modelRequestSent, voice ? AutomationHistoryStore.DELIVERY_VOICE : AutomationHistoryStore.DELIVERY_TEXT)
                    .withDiagnostics(reason, stage, queueMillis, mediaMillis, generationMillis,
                            sendStarted < 0 ? -1 : Math.max(0, SystemClock.elapsedRealtime() - sendStarted));
        }

        void recordOutcome(String status) {
            AutomationTrace.outcome(trace, status, reason.name().toLowerCase(java.util.Locale.ROOT), "native_invoked=" + nativeInvoked + ";acknowledgment_received=" + acknowledgmentReceived + ";acknowledged_outcome=" + acknowledgedOutcome + ";unknown=" + unknown + ";reserved=" + reserved);
            // Dispatcher already owns normal completion, preview admission and cumulative timings.
            if (reason == Reason.NONE || reason == Reason.CANCELLED || reason == Reason.SEND_FAILED
                    || reason == Reason.SUBMITTED_UNCONFIRMED) return;
            dispatcher.recordOutcomeReason(entry(status));
        }

        void reportFailure() {
            if (!failureReported.compareAndSet(false, true)) return;
            trace.finish(reason == Reason.GENERATION_TIMEOUT || reason == Reason.QUEUE_EXPIRED ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED, reason.name().toLowerCase(java.util.Locale.ROOT), "stage=" + stage + ";model_sent=" + modelRequestSent + ";native_invoked=" + nativeInvoked);
            long now = SystemClock.elapsedRealtime();
            if (!began) queueMillis = Math.max(0, now - readyAt);
            if (stage == Stage.MEDIA && mediaMillis < 0 && stageStarted >= 0) mediaMillis = Math.max(0, now - stageStarted);
            if (stage == Stage.GENERATION && generationMillis < 0 && stageStarted >= 0) generationMillis = Math.max(0, now - stageStarted);
            dispatcher.reportFinalFailure(entry("failed"));
        }
    }

    private static String input(ReplyPolicy.Incoming incoming) {
        return (!incoming.videoUrl.isEmpty() ? "[视频] " : incoming.imageUrls.isEmpty() ? "" : "[图片] ") + incoming.text;
    }

    private boolean conversationExists(String id, boolean group) {
        return conversationExists(id, group, null);
    }

    private boolean conversationExists(String id, boolean group, Object resolved) {
        try {
            return conversationForSend(sdk, id, group, resolved) != null;
        } catch (ReflectiveOperationException | RuntimeException ignored) { return false; }
    }

    private boolean matchesConversation(Object conversation, String id, boolean group) throws ReflectiveOperationException {
        return conversation != null && conversationId.getDeclaringClass().isInstance(conversation)
                && id.equals(conversationId.invoke(conversation))
                && Boolean.TRUE.equals(isSingle.invoke(conversation)) != group;
    }

    private Object conversationForSend(Object sdkContext, String id, boolean group, Object resolved)
            throws ReflectiveOperationException {
        Object cached = findConversation.invoke(sdkConversations.invoke(sdkContext), id);
        // The fallback belongs to one reply task and was read by the SDK from local storage.
        // Never manufacture a Conversation or retain a second permanent conversation cache.
        Object candidate = cached != null ? cached : resolved;
        return matchesConversation(candidate, id, group) ? candidate : null;
    }

    private Object resolveReplyConversation(ReplyTask operation) throws InterruptedException {
        try {
            String id = operation.incoming.conversation;
            Object model = sdkConversations.invoke(operation.sdkContext);
            Object cached = findConversation.invoke(model, id);
            if (cached != null) return matchesConversation(cached, id, operation.incoming.group) ? cached : null;
            if (!operation.current()) return null;
            safeLog("Automatic reply conversation lookup started reason=cache_miss");
            AtomicReference<Object> loaded = new AtomicReference<>();
            java.util.concurrent.CountDownLatch completed = new java.util.concurrent.CountDownLatch(1);
            java.util.concurrent.atomic.AtomicBoolean terminal = new java.util.concurrent.atomic.AtomicBoolean();
            Object callback = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    (proxy, method, arguments) -> {
                        if (method.getDeclaringClass() == Object.class) {
                            if ("toString".equals(method.getName())) return "DouAiConversationLookup";
                            if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                            if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                        }
                        if (("onSuccess".equals(method.getName()) || "onFailure".equals(method.getName()))
                                && terminal.compareAndSet(false, true)) {
                            if ("onSuccess".equals(method.getName()) && arguments != null && arguments.length > 0)
                                loaded.set(arguments[0]);
                            completed.countDown();
                        }
                        return null;
                    });
            findConversationAsync.invoke(model, id, callback);
            // This is the reply worker, never the SDK/UI receipt thread. A late callback
            // only completes these local holders and cannot send or restore a cancelled task.
            if (!completed.await(10, TimeUnit.SECONDS) || !operation.current()) return null;
            Object value = loaded.get();
            boolean ready = matchesConversation(value, id, operation.incoming.group);
            safeLog("Automatic reply conversation lookup finished ready=" + ready);
            return ready ? value : null;
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            safeLog("Automatic reply conversation lookup unavailable: " + unavailable.getClass().getSimpleName());
            return null;
        }
    }

    private boolean replyStillAllowed(ContentValues values, long account, LocalOptions current) {
        return NativeFollowLists.ensureAccount(Long.toString(account))
                && ReplyPolicy.incoming(values, account, startedAt, current) != null;
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend) throws Exception {
        sendText(id, text, account, epoch, group, done, beforeNativeSend, null);
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend, Object resolved) throws Exception {
        sendText(id, text, account, epoch, group, done, beforeNativeSend, resolved, () -> { });
    }

    private void sendText(String id, String text, long account, long epoch, boolean group,
                          Consumer<Boolean> done, BooleanSupplier beforeNativeSend, Object resolved,
                          Runnable invoking) throws Exception {
        DouAiLog.Operation trace = DouAiLog.current();
        Consumer<Boolean> callbackResult = trace == null ? done : trace.wrapConsumer(done);
        if (trace != null) trace.stage("message_prepare", "media=text;conversation_ref=" + DouAiLog.reference(id) + ";chars=" + (text == null ? 0 : text.length()));
        if (closed || !receive.isActive() || ownUid() != account || generation.get() != epoch
                || sdk == null || text == null || text.trim().isEmpty()
                || text.length() > 4000) { done.accept(false); return; }
        Object activeSdk = sdk;
        Object conversation = conversationForSend(activeSdk, id, group, resolved);
        if (conversation == null) {
            done.accept(false); return;
        }
        Object builder = builderFactory.invoke(sdkBean.invoke(activeSdk));
        Object content = createText.invoke(null, text);
        Object gson = gsonClass.getConstructor().newInstance();
        String json = (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, content);
        builderType.invoke(builder, 7);
        builderContent.invoke(builder, json);
        builderConversation.invoke(builder, conversation);
        Object message = builderBuild.invoke(builder);
        java.util.concurrent.atomic.AtomicBoolean completed = new java.util.concurrent.atomic.AtomicBoolean();
        Object callback = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                (proxy, method, arguments) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("toString".equals(method.getName())) return "DouAiMessageCallback";
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return arguments != null && proxy == arguments[0];
                    }
                    if ("onSuccess".equals(method.getName()) && completed.compareAndSet(false, true)) callbackResult.accept(true);
                    if ("onFailure".equals(method.getName()) && completed.compareAndSet(false, true)) callbackResult.accept(false);
                    return null;
                });
        Object handler = senderConstructor.newInstance(activeSdk, callback);
        synchronized (sendLock) {
            if (generation.get() != epoch || ownUid() != account || !receive.isActive()
                    || closed || sdk != activeSdk) { done.accept(false); return; }
            if (!beforeNativeSend.getAsBoolean()) { done.accept(false); return; }
            invoking.run();
            if (trace != null) trace.stage("message_native_submit", "business_success=false;awaiting_sdk_callback=true");
            send.invoke(handler, message, Collections.emptyMap());
        }
    }

    private void sendAudio(String id, SpeechPreparation.Audio audio, long account, long epoch, boolean group,
                           Consumer<Boolean> done, BooleanSupplier beforeNativeSend,
                           java.util.concurrent.atomic.AtomicBoolean submitted) throws Exception {
        sendAudio(id, audio, account, epoch, group, done, beforeNativeSend, submitted, null);
    }

    private void sendAudio(String id, SpeechPreparation.Audio audio, long account, long epoch, boolean group,
                           Consumer<Boolean> done, BooleanSupplier beforeNativeSend,
                           java.util.concurrent.atomic.AtomicBoolean submitted, Object resolved) throws Exception {
        sendAudio(id, audio, account, epoch, group, done, beforeNativeSend, submitted, resolved, () -> { });
    }

    private void sendAudio(String id, SpeechPreparation.Audio audio, long account, long epoch, boolean group,
                           Consumer<Boolean> done, BooleanSupplier beforeNativeSend,
                           java.util.concurrent.atomic.AtomicBoolean submitted, Object resolved,
                           Runnable invoking) throws Exception {
        NativeAudioMessageSender sender = audioSender;
        if (closed || !receive.isActive() || sender == null || ownUid() != account
                || generation.get() != epoch || sdk == null) { done.accept(false); return; }
        Object activeSdk = sdk;
        Object conversation = conversationForSend(activeSdk, id, group, resolved);
        if (conversation == null) {
            done.accept(false); return;
        }
        NativeAudioMessageSender.Content content = sender.content(audio);
        Object builder = builderFactory.invoke(sdkBean.invoke(activeSdk));
        Object gson = gsonClass.getConstructor().newInstance();
        String json = (String) gsonClass.getMethod("toJson", Object.class).invoke(gson, content.value);
        builderType.invoke(builder, content.type);
        builderContent.invoke(builder, json);
        builderConversation.invoke(builder, conversation);
        Object message = builderBuild.invoke(builder);
        sender.attach(message, audio);
        synchronized (sendLock) {
            if (generation.get() != epoch || ownUid() != account || !receive.isActive() || closed || sdk != activeSdk) {
                done.accept(false); return;
            }
            if (!beforeNativeSend.getAsBoolean()) { done.accept(false); return; }
            invoking.run();
            submitted.set(true);
            sender.submit(message, content, audio, done,
                    () -> !closed && receive.isActive() && generation.get() == epoch && ownUid() == account
                            && sdk == activeSdk && automatic("native_auto_reply", options) && conversationExists(id, group, resolved));
        }
    }

    private void streakTick() {
        LocalOptions current = options;
        ZonedDateTime now = streakTime.get();
        Set<String> targets = ReplyPolicy.targets(current.text("streak_targets", ""));
        if (!automatic("streak_enabled", current) || targets.isEmpty()
                || !ReplyPolicy.inWindow(current.text("streak_start", ""), current.text("streak_end", ""), now.toLocalTime())) return;
        if (sdk == null || ownUid() <= 0) return;
        String day = now.toLocalDate().toString();
        long self = ownUid();
        boolean eligible = false;
        for (String target : targets) {
            String key = "streak." + self + "." + day + "." + target;
            if (!pendingStreak.contains(key) && streakLedger.canBegin(key)) { eligible = true; break; }
        }
        if (!eligible) return;
        try {
            Object raw = nativeConversations();
            if (!(raw instanceof List<?>)) return;
            long epoch = generation.get();
            for (Object conversation : (List<?>) raw) {
                if (!Boolean.TRUE.equals(isSingle.invoke(conversation))) continue;
                Object members = conversationMembers.invoke(conversation);
                if (!(members instanceof List<?>)) continue;
                String target = null;
                for (Object member : (List<?>) members) {
                    if (member instanceof Number && ((Number) member).longValue() != self && targets.contains(member.toString())) {
                        target = member.toString();
                    }
                }
                if (target == null) continue;
                String key = "streak." + self + "." + day + "." + target;
                if (!streakLedger.canBegin(key) || !pendingStreak.add(key)) { DouAiLog.event("streak", "eligibility", "ledger_blocked_or_already_pending", "target_ref=" + DouAiLog.reference(target)); continue; }
                String id = (String) conversationId.invoke(conversation);
                StreakSendLedger.Attempt[] attempt = new StreakSendLedger.Attempt[1];
                DouAiLog.Operation streakTrace = DouAiLog.begin("streak", "scheduled_window");
                streakTrace.stage("content_prepare", "target_ref=" + DouAiLog.reference(target) + ";content_api=" + !current.text("streak_content_api", "").trim().isEmpty());
                try {
                    String content = streakContent(current);
                    if (generation.get() != epoch || !automatic("streak_enabled", options)) { streakTrace.finish(DouAiLog.Outcome.CANCELLED, "settings_changed", "native_invoked=false"); pendingStreak.remove(key); continue; }
                    streakTrace.stage("content_ready", "chars=" + content.length() + ";body_logged=false");
                    boolean[] reachedPersistence = {false};
                    sendText(id, content, self, epoch, false, success -> {
                        StreakSendLedger.Attempt active = attempt[0];
                        if (active == null) {
                            streakTrace.finish(DouAiLog.Outcome.CANCELLED, "not_invoked", "durable_attempt=false;native_result=" + success);
                            pendingStreak.remove(key);
                            return;
                        }
                        boolean recorded = streakLedger.complete(key, active.token, success, System.currentTimeMillis());
                        if (recorded) {
                            streakTrace.finish(success ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.FAILED, success ? "native_sdk_acknowledged" : "native_sdk_rejected", "callback_received=true;durable_completion=true");
                            pendingStreak.remove(key);
                            safeLog(success ? "Streak send acknowledged by native SDK" : "Streak native SDK send failed");
                            if (current.enabled("streak_tip")) toast(success ? "续火花已发送" : "续火花发送失败");
                        } else {
                            pendingStreak.remove(key);
                            streakTrace.finish(DouAiLog.Outcome.UNCONFIRMED, "completion_persistence_failed", "callback_success=" + success + ";durable_completion=false");
                            safeLog("Could not persist streak send result; outcome remains unknown");
                        }
                    }, () -> {
                        ZonedDateTime sendingTime = streakTime.get();
                        if (!automatic("streak_enabled", options) || !sendingTime.toLocalDate().toString().equals(day)
                                || !ReplyPolicy.inWindow(options.text("streak_start", ""), options.text("streak_end", ""), sendingTime.toLocalTime())) return false;
                        reachedPersistence[0] = true;
                        attempt[0] = streakLedger.begin(key, System.currentTimeMillis());
                        if (attempt[0] == null) return false;
                        streakTrace.stage("submission_reserved", "durable=true;business_success=false");
                        String attemptToken = attempt[0].token;
                        streakWorker.schedule(() -> {
                            if (streakLedger.markUnknown(key, attemptToken, System.currentTimeMillis())) {
                                pendingStreak.remove(key);
                                streakTrace.finish(DouAiLog.Outcome.UNCONFIRMED, "native_callback_timeout", "deadline_ms=30000;durable_state=unknown;retry_safe=false");
                                safeLog("Streak send callback timed out; outcome is unknown");
                            }
                        }, 30, TimeUnit.SECONDS);
                        return true;
                    });
                    if (attempt[0] == null && reachedPersistence[0]) {
                        pendingStreak.remove(key);
                        streakTrace.finish(DouAiLog.Outcome.SKIPPED, "submission_persistence_failed", "native_invoked=false");
                        safeLog("Streak send prevented because its state could not be persisted");
                    }
                } catch (Exception failure) {
                    if (attempt[0] != null) {
                        streakLedger.markUnknown(key, attempt[0].token, System.currentTimeMillis());
                    }
                    pendingStreak.remove(key);
                    streakTrace.error("streak_send", failure);
                    streakTrace.finish(attempt[0] == null ? DouAiLog.Outcome.FAILED : DouAiLog.Outcome.UNCONFIRMED, "streak_send_exception", "durable_attempt=" + (attempt[0] != null) + ";remote_side_effect_unknown=" + (attempt[0] != null));
                    safeLog("Streak task failed: " + failure.getClass().getSimpleName());
                }
            }
        } catch (Exception failure) { safeLog("Streak conversation lookup failed: " + failure.getClass().getSimpleName()); }
    }

    private Object nativeConversations() throws Exception {
        enumeratingContacts.set(true);
        try { return allConversations.invoke(sdkConversations.invoke(sdk)); }
        finally { enumeratingContacts.remove(); }
    }

    private boolean discoverContacts(boolean includeMembers, long revision, long accountEpoch, List<ContactRequest> requests) {
        Object source = sdk;
        long self = ownUid();
        if (closed || source == null || self <= 0 || !receive.isActive()
                || accountEpoch != FollowListSnapshot.current().accountEpoch) return false;
        try {
            Object raw = nativeConversations();
            if (!(raw instanceof List<?>)) return false;
            Map<String, String> discovered = new LinkedHashMap<>();
            Map<String, String> discoveredGroups = new LinkedHashMap<>();
            Map<String, ProfileListEntry> discoveredProfiles = new LinkedHashMap<>();
            Map<String, ProfileListEntry> discoveredGroupProfiles = new LinkedHashMap<>();
            Map<String, Map<String, ProfileListEntry>> discoveredGroupMembers = new LinkedHashMap<>();
            Map<String, ProfileListEntry> knownProfiles = new LinkedHashMap<>();
            if (includeMembers) {
                knownProfiles.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWING));
                knownProfiles.putAll(NativeFollowLists.memberDetails(FollowListSnapshot.Kind.FOLLOWERS));
            }
            for (Object conversation : (List<?>) raw) {
                if (!contactsStillWanted(requests, self, accountEpoch) || closed || sdk != source || ownUid() != self
                        || FollowListSnapshot.current().accountEpoch != accountEpoch
                        || contactsRevisionChanged(revision)) return false;
                Object core = conversationCore.invoke(conversation);
                Object name = core == null ? null : conversationName.invoke(core);
                Object icon = HostTargets.invoke(core, "getIcon");
                if (!Boolean.TRUE.equals(isSingle.invoke(conversation))) {
                    Object id = conversationId.invoke(conversation);
                    if (!(id instanceof String) || ((String) id).isEmpty()) continue;
                    String groupName = name instanceof String && !((String) name).isEmpty() ? (String) name : (String) id;
                    discoveredGroups.put((String) id, groupName);
                    discoveredGroupProfiles.put((String) id, new ProfileListEntry((String) id, groupName,
                            icon instanceof String ? (String) icon : ""));
                    if (!includeMembers) continue;
                    Map<String, ProfileListEntry> members = new LinkedHashMap<>();
                    Object rawMembers = HostTargets.invoke(conversation, "getMembers");
                    if (rawMembers instanceof Iterable<?>) for (Object member : (Iterable<?>) rawMembers) {
                        if (!contactsStillWanted(requests, self, accountEpoch) || closed || sdk != source || ownUid() != self
                                || FollowListSnapshot.current().accountEpoch != accountEpoch
                                || contactsRevisionChanged(revision)) return false;
                        Object uid = HostTargets.invoke(member, "getUid");
                        if (!(uid instanceof Number)) uid = HostTargets.read(member, "uid");
                        if (!(uid instanceof Number) || ((Number) uid).longValue() <= 0) continue;
                        String memberUid = uid.toString();
                        ProfileListEntry profile = knownProfiles.get(memberUid);
                        if (profile == null) {
                            profile = memberProfile(memberUid, new ProfileListEntry(memberUid, "", ""));
                            knownProfiles.put(memberUid, profile);
                        }
                        Object alias = HostTargets.invoke(member, "getAlias");
                        if (!(alias instanceof String)) alias = HostTargets.read(member, "alias");
                        members.put(memberUid, new ProfileListEntry(memberUid, !profile.name.isEmpty() ? profile.name
                                : alias instanceof String ? (String) alias : "", profile.avatarUrl));
                    }
                    discoveredGroupMembers.put((String) id, Collections.unmodifiableMap(members));
                    continue;
                }
                Object rawMembers = conversationMembers.invoke(conversation);
                if (!(rawMembers instanceof List<?>)) continue;
                for (Object member : (List<?>) rawMembers) {
                    if (!(member instanceof Number) || ((Number) member).longValue() == self) continue;
                    String uid = member.toString();
                    String label = name instanceof String && !((String) name).isEmpty() ? (String) name : uid;
                    discovered.put(uid, label);
                    discoveredProfiles.put(uid, new ProfileListEntry(uid, label,
                            icon instanceof String ? (String) icon : memberProfile(uid).avatarUrl));
                }
            }
            synchronized (contactsLock) {
                synchronized (CONTACTS_OWNER_LOCK) {
                    if (closed || sdk != source || ownUid() != self || contactsRevision != revision
                            || active != this
                            || !NativeFollowLists.ensureAccount(Long.toString(self))
                            || FollowListSnapshot.current().accountEpoch != accountEpoch) return false;
                    contacts = Collections.unmodifiableMap(discovered);
                    groups = Collections.unmodifiableMap(discoveredGroups);
                    contactProfiles = Collections.unmodifiableMap(discoveredProfiles);
                    groupProfiles = Collections.unmodifiableMap(discoveredGroupProfiles);
                    groupMembers = Collections.unmodifiableMap(discoveredGroupMembers);
                    contactAccount = self;
                    loadedContactsRevision = revision;
                    loadedContactsAccountEpoch = accountEpoch;
                    membersLoaded = includeMembers;
                }
            }
            return true;
        } catch (Exception failure) {
            safeLog("Could not enumerate native contacts: " + failure.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean contactsStillWanted(List<ContactRequest> requests, long account, long accountEpoch) {
        if (requests.isEmpty()) return true;
        for (ContactRequest request : requests) if (!request.cancelled && request.account == account
                && request.accountEpoch == accountEpoch) return true;
        return false;
    }

    private boolean contactsRevisionChanged(long revision) {
        synchronized (contactsLock) { return contactsRevision != revision; }
    }

    private static String streakContent(LocalOptions options) throws Exception {
        String api = options.text("streak_content_api", "").trim();
        if (api.isEmpty()) return options.text("streak_content", "").trim();
        URI uri = URI.create(api);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null || uri.getUserInfo() != null) {
            throw new IllegalArgumentException("续火花内容接口必须使用 HTTPS");
        }
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        connection.setConnectTimeout(10_000);
        connection.setReadTimeout(10_000);
        connection.setInstanceFollowRedirects(false);
        try {
            if (connection.getResponseCode() != 200) throw new IllegalStateException("内容接口不可用");
            try (java.io.InputStream input = connection.getInputStream()) {
                return ChatCompletionClient.readBounded(input, 16_000).trim();
            }
        } finally { connection.disconnect(); }
    }

    private void toast(String text) { main.post(() -> Toast.makeText(context, text, Toast.LENGTH_SHORT).show()); }
    private void safeLog(String message) { try { DouAiLog.event(message.startsWith("Streak") ? "streak" : message.startsWith("Automatic reply") ? "auto_reply" : "native_messaging", "diagnostic", "legacy_context", "message_ref=" + DouAiLog.reference(message) + ";chars=" + message.length()); log.accept(message); } catch (Throwable ignored) { } }

    @Override public void close() {
        synchronized (sendLock) {
            closed = true;
            generation.incrementAndGet();
        }
        if (timeReceiverRegistered) {
            timeReceiverRegistered = false;
            try { context.unregisterReceiver(timeChanges); } catch (IllegalArgumentException ignored) { }
        }
        cancelReplies();
        worker.shutdownNow();
        streakWorker.shutdownNow();
        synchronized (contactsLock) {
            for (ContactRequest request : contactRequests) request.close();
            for (ContactRequest request : activeContactRequests) request.close();
            contactRequests.clear();
            contactsRevision++;
        }
        contactsWorker.shutdownNow();
        dispatcher.cancelKind(AutomationHistoryStore.REPLY);
        if (ownsDispatcher) dispatcher.close();
        receive.close();
        recall.close();
        if (audioSender != null) audioSender.close();
        sdk = null;
        synchronized (CONTACTS_OWNER_LOCK) {
            if (active == this) {
                active = null;
                clearContacts(); contactAccount = 0;
            }
        }
    }

    private static void clearContacts() {
        contacts = Collections.emptyMap(); groups = Collections.emptyMap();
        contactProfiles = Collections.emptyMap(); groupProfiles = Collections.emptyMap(); groupMembers = Collections.emptyMap();
    }

    private ProfileListEntry memberProfile(String uid) {
        return memberProfile(uid, NativeFollowLists.findMember(uid));
    }

    private ProfileListEntry memberProfile(String uid, ProfileListEntry fallback) {
        try {
            if (imProfileCache == null || imProfileGet == null) return fallback;
            Object user = imProfileGet.invoke(imProfileCache.invoke(null), uid);
            if (user == null || !uid.equals(HostTargets.invoke(user, "getUid"))) return fallback;
            Object name = HostTargets.invoke(user, "getNickname");
            String avatar = ProfileListEntry.avatar(user);
            return new ProfileListEntry(uid, name instanceof String && !((String) name).isEmpty() ? (String) name : fallback.name,
                    !avatar.isEmpty() ? avatar : fallback.avatarUrl);
        } catch (ReflectiveOperationException | RuntimeException ignored) { return fallback; }
    }
}
