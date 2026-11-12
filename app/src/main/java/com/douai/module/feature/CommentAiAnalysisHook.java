package com.douai.module.feature;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.CommentAiSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostAdaptation;
import io.github.libxposed.api.XposedInterface;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Opt-in interception limited to the verified 41.0 comment AI scene. */
public final class CommentAiAnalysisHook implements AutoCloseable {
    private static final String SCENE = "comment_ai_analysis";
    private final Context applicationContext;
    private final Bindings host;
    private final BehaviorHookGroup hooks;
    private final Supplier<AiModelSettings> modelSettings;
    private final DouyinSearch search;
    private final Consumer<String> log;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Object, Conversation> conversations = new WeakHashMap<>();
    private final AtomicLong optionsEpoch = new AtomicLong();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                Thread thread = new Thread(task, "DouAi-comment-ai"); thread.setDaemon(true); return thread;
            });
    private volatile LocalOptions options;
    private volatile boolean closed;
    private CommentAiSuggestionRows suggestionRows;
    private HostCommentAiVideos videoCards;
    private HostCommentAiPaging videoPaging;

    private CommentAiAnalysisHook(Context context, Bindings host, LocalOptions options,
            Supplier<AiModelSettings> modelSettings, DouyinSearch search, Consumer<String> log) {
        Context application = context.getApplicationContext();
        this.applicationContext = application == null ? context : application;
        this.host = host;
        this.options = options;
        this.modelSettings = modelSettings;
        this.search = search;
        this.log = log == null ? ignored -> { } : log;
        hooks = new BehaviorHookGroup("comment_ai", this.log);
    }

    public static CommentAiAnalysisHook install(XposedInterface framework, ClassLoader loader,
            Context context, LocalOptions options, Supplier<AiModelSettings> modelSettings,
            DouyinSearch search, Consumer<String> log) throws Exception {
        if (framework == null || loader == null || context == null || modelSettings == null) {
            throw new IllegalArgumentException("Comment AI requires host bindings and model settings");
        }
        // PackageManager's display version can already be intercepted by VersionInfoHook.
        if (!supportsHost(context.getPackageName(), HostAdaptation.currentProfile())) {
            throw new IllegalStateException("Comment AI adapter only covers Douyin 41.0.0/410001/41009900");
        }
        CommentAiAnalysisHook feature = new CommentAiAnalysisHook(context, new Bindings(loader), options,
                modelSettings, search, log);
        try {
            feature.hooks.add(framework, feature.host.request, feature::request);
            feature.hooks.add(framework, feature.host.createEntry, feature::nativeEntry);
            feature.hooks.add(framework, feature.host.prefetch, chain -> {
                if (feature.readySettings() != null && feature.nativeSchemaReady()) return null;
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.cancel, chain -> {
                feature.cancelManager(chain.getThisObject(), false, false);
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.reset, chain -> {
                feature.cancelManager(chain.getThisObject(), true, false);
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.pageChanged, chain -> {
                if (Boolean.TRUE.equals(chain.getArgs().get(1))) feature.cancelAll(true, false);
                return chain.proceed();
            });
            feature.hooks.add(framework, feature.host.visible, chain -> {
                if (Boolean.FALSE.equals(chain.getArgs().get(0))) feature.cancelAll(false, false);
                return chain.proceed();
            });
            for (Method method : new Method[]{feature.host.dismiss, feature.host.destroy}) {
                feature.hooks.add(framework, method, chain -> {
                    feature.cancelAll(true, false);
                    return chain.proceed();
                });
            }
            try {
                CommentAiSuggestionRows rows = new CommentAiSuggestionRows(loader);
                feature.hooks.add(framework, rows.bind, chain -> {
                    Object result = chain.proceed();
                    try { rows.bind(chain.getThisObject(), chain.getArgs().get(0)); }
                    catch (Exception failure) {
                        feature.safeLog("comment_ai_followup_bind_failed; error=" + failure.getClass().getSimpleName());
                    }
                    return result;
                });
                feature.suggestionRows = rows;
            } catch (Exception unavailable) {
                feature.safeLog("comment_ai_followups_unavailable; error=" + unavailable.getClass().getSimpleName());
            }
            try { feature.videoCards = HostCommentAiVideos.create(loader); }
            catch (Exception unavailable) {
                feature.safeLog("comment_ai_video_cards_unavailable; error=" + unavailable.getClass().getSimpleName());
            }
            try {
                HostCommentAiPaging paging = HostCommentAiPaging.create(loader);
                feature.hooks.add(framework, paging.scrolled, chain -> {
                    try { paging.scrolled(chain.getThisObject(), ((Number) chain.getArgs().get(1)).intValue()); }
                    catch (Exception failure) { feature.safeLog("comment_ai_paging_scroll_failed; error=" + failure.getClass().getSimpleName()); }
                    return chain.proceed();
                });
                feature.hooks.add(framework, paging.automaticLoad, chain -> {
                    if (paging.ownsAutomaticLoader(chain.getThisObject())) return null;
                    return chain.proceed();
                });
                feature.hooks.add(framework, paging.touch, chain -> {
                    try { paging.touched(chain.getThisObject(), (android.view.MotionEvent) chain.getArgs().get(0)); }
                    catch (Exception failure) { feature.safeLog("comment_ai_paging_touch_failed; error=" + failure.getClass().getSimpleName()); }
                    return chain.proceed();
                });
                feature.videoPaging = paging;
            } catch (Exception unavailable) {
                feature.safeLog("comment_ai_paging_unavailable; error=" + unavailable.getClass().getSimpleName());
            }
            if (feature.readySettings() != null && feature.nativeSchemaReady()) feature.cancelPrefetch();
            return feature;
        } catch (Throwable failure) {
            feature.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    static boolean shouldTakeover(LocalOptions options, boolean modelReady, String scene) {
        return options != null && options.enabled(CommentAiSettings.TAKEOVER) && modelReady && SCENE.equals(scene);
    }

    static boolean supportsHost(String packageName, HostAdaptation.Profile profile) {
        return HostAdaptation.TARGET_PACKAGE.equals(packageName) && profile == HostAdaptation.PROFILE_410
                && profile.updateVersionCode == 41009900L;
    }

    public void setOptions(LocalOptions next) {
        LocalOptions previous = options;
        boolean changed = previous == null || next == null
                || previous.enabled(CommentAiSettings.TAKEOVER) != next.enabled(CommentAiSettings.TAKEOVER)
                || previous.enabled(CommentAiSettings.SEARCH) != next.enabled(CommentAiSettings.SEARCH)
                || !previous.text(AiModelSettings.KEY, "").equals(next.text(AiModelSettings.KEY, ""))
                || !previous.text(CommentAiSettings.MODEL_ID, "").equals(next.text(CommentAiSettings.MODEL_ID, ""));
        if (changed) optionsEpoch.incrementAndGet();
        options = next;
        if (changed) {
            onMain(() -> {
                cancelAll(true, true);
                if (readySettings() != null && nativeSchemaReady()) cancelPrefetch();
            });
        }
    }

    public boolean isActive() { return !closed && hooks.isActive(); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        hooks.close();
        cancelAll(true, true);
        onMain(() -> {
            if (suggestionRows != null) suggestionRows.close();
            if (videoPaging != null) videoPaging.close();
        });
        worker.shutdownNow();
    }

    private AiModelSettings readySettings() {
        LocalOptions current = options;
        if (closed || current == null || !current.enabled(CommentAiSettings.TAKEOVER)) return null;
        try {
            AiModelSettings settings = modelSettings.get();
            if (settings == null) return null;
            settings.selected();
            return settings;
        } catch (RuntimeException unavailable) { return null; }
    }

    private boolean nativeSchemaReady() {
        try { return host.validateNativeSchema(); }
        catch (Exception failure) {
            safeLog("comment_ai_native_schema_unavailable; native_path_preserved=true; stage="
                    + host.nativeSchemaStage + "; error=" + failure.getClass().getSimpleName());
            return false;
        }
    }

    private Object nativeEntry(XposedInterface.Chain chain) throws Throwable {
        if (readySettings() == null || !nativeSchemaReady() || Looper.myLooper() != Looper.getMainLooper()) {
            return chain.proceed();
        }
        try {
            Object entry = host.nativeEntry(chain.getArgs().get(0), (String) chain.getArgs().get(1));
            safeLog("comment_ai_native_entry_selected; schema_verified=true");
            return entry;
        } catch (Exception failure) {
            safeLog("comment_ai_native_entry_unavailable; native_path_preserved=true; error=" + failure.getClass().getSimpleName());
            return chain.proceed();
        }
    }

    private Object request(XposedInterface.Chain chain) throws Throwable {
        Object manager = chain.getThisObject();
        long requestEpoch = optionsEpoch.get();
        AiModelSettings settings = readySettings();
        if (!shouldTakeover(options, settings != null, host.scene(manager))) {
            String reason = options == null || !options.enabled(CommentAiSettings.TAKEOVER) ? "disabled"
                    : settings == null ? "model_unavailable" : "scene_mismatch";
            safeLog("comment_ai_request_declined; reason=" + reason + "; native_path_preserved=true");
            return chain.proceed();
        }
        if (!nativeSchemaReady()) {
            safeLog("comment_ai_request_declined; reason=native_schema_unavailable; native_path_preserved=true");
            return chain.proceed();
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            safeLog("comment_ai_request_declined; reason=off_main_thread; native_path_preserved=true");
            return chain.proceed();
        }
        if (requestEpoch != optionsEpoch.get()) {
            safeLog("comment_ai_request_declined; reason=configuration_changed; native_path_preserved=true");
            return chain.proceed();
        }
        Object parameter = chain.getArgs().get(0);
        Session session = null;
        boolean hostSessionClaimed = false;
        String claimedConversationId = "";
        int claimedRank = -1;
        try {
            Object aweme = host.aweme(manager);
            String source = text(field(aweme, "aid"));
            if (source.isEmpty()) {
                safeLog("comment_ai_request_declined; reason=source_unavailable; native_path_preserved=true");
                return chain.proceed();
            }
            String query = text(field(parameter, "LIZ"));
            if (query.trim().isEmpty()) query = "总结这条内容";
            String selectedModel = settings.selected().id;
            if (requestEpoch != optionsEpoch.get() || readySettings() == null) {
                safeLog("comment_ai_request_declined; reason=configuration_changed; native_path_preserved=true");
                return chain.proceed();
            }
            cancelManager(manager, false, false);
            cancelPrefetch();
            host.initialize.invoke(manager, parameter);
            hostSessionClaimed = true;
            // Host initialization assigns the current follow-up/retry rank.
            int rank = ((Number) field(parameter, "LJIIJ")).intValue();
            claimedRank = rank;
            if (rank < 1) throw new IllegalStateException("Host did not initialize a positive conversation rank");
            Object ledger = host.ledger.get(manager);
            Object conversationState = host.currentConversation.get(ledger);
            claimedConversationId = text(field(conversationState, "LIZ"));
            if (requestEpoch != optionsEpoch.get()) {
                if (!claimedConversationId.isEmpty()) {
                    host.receive.invoke(manager, host.cancelEvent.newInstance(claimedConversationId, rank));
                } else host.cancelOfficial.invoke(manager);
                return null;
            }
            host.cancelOfficial.invoke(manager);
            if (claimedConversationId.isEmpty()) throw new IllegalStateException("Host did not initialize a conversation id");
            Conversation owner;
            synchronized (conversations) {
                owner = conversations.get(manager);
                if (owner == null || !source.equals(owner.source) || !selectedModel.equals(owner.modelId)) {
                    if (owner != null) owner.client.clearHistory();
                    owner = new Conversation(source, selectedModel);
                    conversations.put(manager, owner);
                }
            }
            session = new Session(manager, owner, owner.generation.incrementAndGet(),
                    new CommentAiNativeStream(claimedConversationId, rank, query, "douai_comment_" + UUID.randomUUID()),
                    claimedConversationId, rank, requestEpoch);
            session.analytics = com.douai.module.analytics.LocalAnalytics.get(applicationContext).beginOperation(source, "ai");
            owner.round = session;
            host.receive.invoke(manager, host.start.newInstance(claimedConversationId, rank, parameter));
            host.frame(manager, claimedConversationId, rank, session.stream.firstFrame());
            if (videoPaging != null) {
                WeakReference<Session> reference = new WeakReference<>(session);
                try {
                    session.paging = videoPaging.attach(manager, () -> {
                        Session active = reference.get();
                        if (active != null) onMain(() -> loadMoreVideos(active));
                    });
                } catch (Exception failure) {
                    safeLog("comment_ai_paging_attach_failed; error=" + failure.getClass().getSimpleName());
                }
            }
            safeLog("comment_ai_first_frame_dispatched; native_render_result=unconfirmed");
            String document = CommentAiAnalysisSource.document(aweme);
            CommentAiAnalysisMedia.Source media = CommentAiAnalysisMedia.capture(aweme);
            String prompt = query;
            boolean useSearch = options != null && options.enabled(CommentAiSettings.SEARCH);
            Session activeSession = session;
            try {
                activeSession.work = worker.submit(() -> generate(activeSession, settings, prompt, document, media, useSearch));
            } catch (RuntimeException rejected) {
                fail(activeSession, rejected);
            }
            safeLog("comment_ai_model_started; original_ai_request_suppressed=true; source_context=preparing_media");
            return null;
        } catch (Exception failure) {
            if (!hostSessionClaimed) {
                cancelManager(manager, false, false);
                safeLog("comment_ai_preflight_failed; native_path_preserved=true; error=" + failure.getClass().getSimpleName());
                return chain.proceed();
            }
            if (session != null) fail(session, failure);
            else {
                cancelManager(manager, false, false);
                try {
                    if (claimedConversationId.isEmpty()) host.cancelOfficial.invoke(manager);
                    else host.receive.invoke(manager, host.cancelEvent.newInstance(claimedConversationId, claimedRank));
                } catch (Exception cleanupFailure) { safeLog("comment_ai_claim_cleanup_failed"); }
            }
            safeLog("comment_ai_start_failed_after_host_claim; native_fallback=false; error=" + failure.getClass().getSimpleName());
            return null;
        }
    }

    private void generate(Session session, AiModelSettings settings, String prompt, String document,
            CommentAiAnalysisMedia.Source source, boolean useSearch) {
        try {
            ensureGenerating(session);
            CommentAiAnalysisMedia.Result media = CommentAiAnalysisMedia.load(applicationContext, source, session.media);
            ensureGenerating(session);
            safeLog("comment_ai_source_loaded; kind=" + media.kind + "; images=" + media.images.size()
                    + "; failure=" + media.failure + "; audio=false; full_comments=false");
            Consumer<String> partials = partial -> {
                if (partial != null && !partial.isEmpty()) session.modelTextSeen = true;
                onMain(() -> publish(session, partial, false));
            };
            String conversation = "comment_ai:" + session.owner.source;
            DouyinSearch observedSearch = useSearch && search != null ? observeSearch(session) : null;
            String answer;
            // A cancelled older worker must not acquire the client later and start another request.
            synchronized (session.owner.client) {
                ensureGenerating(session);
                try {
                    answer = session.owner.client.replyWithSearch(conversation, prompt, media.images, settings,
                            "", true, document + "\n" + media.document, observedSearch, partials);
                } catch (Exception failure) {
                    ensureGenerating(session);
                    if (!canRetryTextOnly(failure, !media.images.isEmpty(), session.modelTextSeen)) throw failure;
                    int status = ((ChatCompletionClient.ReplyFailure) failure).httpStatus();
                    safeLog("comment_ai_text_fallback; http_status=" + status + "; image_support=unconfirmed");
                    // A fresh client prevents earlier turns' images from re-entering this text-only retry.
                    answer = session.textFallbackClient.replyWithSearch(conversation, prompt, Collections.emptyList(),
                            settings, "", false, document + "\n本次带画面的请求未被模型服务接受（HTTP " + status
                            + "），现仅依据当前作品文字资料独立重试，未提供作品画面或此前对话。不能声称已看过画面。"
                            + "仅当问题需要画面时，简短说明可改用支持图片输入的模型和接口。",
                            observedSearch, partials);
                    synchronized (session) {
                        ensureGenerating(session);
                        session.owner.client.rememberReply(conversation, prompt, Collections.emptyList(), settings, "", answer);
                    }
                }
            }
            ensureGenerating(session);
            String completedAnswer = answer;
            onMain(() -> publish(session, completedAnswer, true));
            generateRecommendations(session, settings, prompt, document, answer, useSearch);
        } catch (CancellationException cancelled) {
            onMain(() -> cancelSession(session, false));
        } catch (Exception failure) {
            onMain(() -> fail(session, failure));
        }
    }

    private DouyinSearch observeSearch(Session session) {
        return (query, limit) -> {
            DouyinSearch.Request original = search.preparePage(query, limit, 0, "");
            return new DouyinSearch.Request() {
                @Override public DouyinSearch.Result run(long deadlineNanos) throws Exception {
                    ensureGenerating(session);
                    DouyinSearch.Result result = original.run(deadlineNanos);
                    recordRecommendations(session, result);
                    return result;
                }
                @Override public void cancel() { original.cancel(); }
            };
        };
    }

    private void recordRecommendations(Session session, DouyinSearch.Result result) {
        synchronized (session) {
            ensureRecommending(session);
            if (result == null) return;
            if (!session.recommendationQuery.isEmpty() && !session.recommendationQuery.equals(result.query)) return;
            int size = Math.min(result.items.size(), result.nativeAwemes.size());
            for (int index = 0; index < size && session.recommendationWorks.size() < 50; index++) {
                DouyinSearch.Item item = result.items.get(index);
                Object work = result.nativeAwemes.get(index);
                if (item == null || work == null || item.awemeId.isEmpty() || session.owner.source.equals(item.awemeId)) continue;
                session.recommendationWorks.putIfAbsent(item.awemeId, work);
            }
            if (!session.recommendationWorks.isEmpty()) {
                session.recommendationQuery = result.query;
                session.pages.seed(result);
            }
        }
    }

    private void generateRecommendations(Session session, AiModelSettings settings, String prompt,
            String document, String answer, boolean useSearch) {
        if (suggestionRows == null && videoCards == null) return;
        try {
            ensureRecommending(session);
            CommentAiRecommendationPlan plan = CommentAiRecommendationPlan.fallback(prompt, document);
            try {
                String proposed = session.recommendationClient.replyStructuredAnalysisBounded(
                        "comment_ai_recommendations:" + session.owner.source,
                        CommentAiRecommendationPlan.request(prompt, document, answer), settings, "", "", 15);
                ensureRecommending(session);
                plan = CommentAiRecommendationPlan.parse(proposed, prompt, document);
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (Exception unavailable) {
                ensureRecommending(session);
                safeLog("comment_ai_followup_plan_fallback; error=" + unavailable.getClass().getSimpleName());
            }
            ensureRecommending(session);
            flow(session, "generated");
            CommentAiRecommendationPlan completedPlan = plan;
            onMain(() -> publishRecommendations(session, completedPlan));
            // Reuse results already fetched by the answer; only an empty set needs one extra search.
            boolean needsSearch;
            synchronized (session) { needsSearch = session.recommendationWorks.isEmpty(); }
            if (needsSearch && useSearch && search != null && videoCards != null) {
                DouyinSearch.Request operation = null;
                try {
                    operation = search.preparePage(plan.searchQuery, 5, 0, "");
                    synchronized (session) {
                        ensureRecommending(session);
                        session.recommendationSearch = operation;
                    }
                    recordRecommendations(session, operation.run(System.nanoTime() + TimeUnit.SECONDS.toNanos(15)));
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (Exception unavailable) {
                    ensureRecommending(session);
                    safeLog("comment_ai_recommendation_search_unavailable; error=" + unavailable.getClass().getSimpleName());
                } finally {
                    synchronized (session) { session.recommendationSearch = null; }
                    if (operation != null) try { operation.cancel(); } catch (RuntimeException ignored) { }
                }
            }
            ensureRecommending(session);
            List<Object> videos;
            synchronized (session) { videos = new ArrayList<>(session.recommendationWorks.values()); }
            onMain(() -> publishVideoRecommendations(session, videos));
        } catch (CancellationException cancelled) {
            onMain(() -> cancelSession(session, false));
        } catch (Exception failure) {
            safeLog("comment_ai_recommendations_failed; error=" + failure.getClass().getSimpleName());
        }
    }

    private void publishRecommendations(Session session, CommentAiRecommendationPlan plan) {
        if (!belongs(session) || !session.finished || session.recommendationsPublished) return;
        session.recommendationsPublished = true;
        Object manager = session.manager.get();
        if (suggestionRows != null) {
            WeakReference<Session> reference = new WeakReference<>(session);
            try {
                session.suggestions = suggestionRows.insert(manager, session.conversationId, session.rank, plan.questions, question -> {
                    Session active = reference.get();
                    if (active == null || !belongs(active)) return;
                    Object target = active.manager.get();
                    if (target == null) return;
                    if (active.analytics != null) active.analytics.click("followup_click");
                    try { suggestionRows.ask(target, question); }
                    catch (Exception failure) {
                        safeLog("comment_ai_followup_click_failed; error=" + failure.getClass().getSimpleName());
                    }
                });
                if (session.suggestions != null) flow(session, "dispatched");
                safeLog("comment_ai_followups_dispatched; questions=" + (session.suggestions == null ? 0 : plan.questions.size())
                        + "; ui_result=unconfirmed");
            } catch (Exception failure) {
                safeLog("comment_ai_followup_insert_failed; error=" + failure.getClass().getSimpleName());
            }
        }
    }

    private void publishVideoRecommendations(Session session, List<Object> videos) {
        if (!belongs(session) || !session.finished || session.videosPublished) return;
        session.videosPublished = true;
        Object manager = session.manager.get();
        if (videoCards != null && !videos.isEmpty()) {
            try {
                synchronized (session) {
                    session.videoIds.addAll(session.recommendationWorks.keySet());
                    session.pendingVideos.addAll(videos);
                }
                int inserted = dispatchVideoBatch(session, true);
                safeLog("comment_ai_video_cards_dispatched; inserted=" + inserted + "; ui_and_playback=unconfirmed");
            } catch (Exception failure) {
                safeLog("comment_ai_video_card_insert_failed; error=" + failure.getClass().getSimpleName());
            }
        }
        synchronized (session) { session.recommendationWorks.clear(); }
    }

    private void loadMoreVideos(Session session) {
        if (!belongs(session) || !session.finished || !session.videosPublished || session.paging == null
                || search == null || videoCards == null || options == null || !options.enabled(CommentAiSettings.SEARCH)) return;
        long now = android.os.SystemClock.elapsedRealtime();
        if (now - session.lastPageAttempt < 1_500) return;
        synchronized (session) {
            if (!session.pendingVideos.isEmpty()) {
                session.lastPageAttempt = now;
                try {
                    int inserted = dispatchVideoBatch(session, false);
                    safeLog("comment_ai_buffered_page_appended; inserted=" + inserted + "; ui_and_playback=unconfirmed");
                } catch (Exception failure) {
                    safeLog("comment_ai_buffered_page_failed; error=" + failure.getClass().getSimpleName());
                    android.widget.Toast.makeText(applicationContext, "视频加载失败，再向下滑动重试", android.widget.Toast.LENGTH_SHORT).show();
                }
                return;
            }
        }
        CommentAiVideoPageState.Next page = session.pages.begin();
        if (page == null) return;
        session.lastPageAttempt = now;
        try {
            session.pageWork = worker.submit(() -> {
                DouyinSearch.Request operation = null;
                try {
                    ensureRecommending(session);
                    operation = search.preparePage(page.query, 5, page.offset, page.searchId);
                    synchronized (session) {
                        ensureRecommending(session);
                        session.pageSearch = operation;
                    }
                    DouyinSearch.Result result = operation.run(System.nanoTime() + TimeUnit.SECONDS.toNanos(15));
                    ensureRecommending(session);
                    onMain(() -> {
                        if (!belongs(session)) return;
                        try {
                            if (!page.query.equals(result.query)) throw new IllegalStateException("Search page query changed");
                            int fresh = bufferVideoPage(session, result);
                            session.pages.complete(page, result, fresh);
                            int inserted = dispatchVideoBatch(session, false);
                            safeLog("comment_ai_page_appended; inserted=" + inserted + "; has_more=" + session.pages.hasMore()
                                    + "; ui_and_playback=unconfirmed");
                            if (!session.pages.hasMore() && session.pendingVideos.isEmpty()) android.widget.Toast.makeText(applicationContext,
                                    "没有更多相关视频", android.widget.Toast.LENGTH_SHORT).show();
                        } catch (Exception failure) { pageFailed(session, page, failure); }
                    });
                } catch (CancellationException cancelled) {
                    session.pages.failed(page);
                } catch (Exception failure) {
                    onMain(() -> pageFailed(session, page, failure));
                } finally {
                    synchronized (session) { if (session.pageSearch == operation) session.pageSearch = null; }
                    if (operation != null) try { operation.cancel(); } catch (RuntimeException ignored) { }
                }
            });
        } catch (RuntimeException busy) { pageFailed(session, page, busy); }
    }

    private int dispatchVideoBatch(Session session, boolean first) throws Exception {
        synchronized (session) {
            if (!belongs(session)) return 0;
            List<Object> batch = new ArrayList<>();
            for (Object video : session.pendingVideos) {
                batch.add(video);
                if (batch.size() == 5) break;
            }
            int inserted = first ? videoCards.insert(session.manager.get(), session.conversationId, session.rank, batch)
                    : videoCards.append(session.manager.get(), session.conversationId, session.rank, batch);
            for (int index = 0; index < batch.size() && !session.pendingVideos.isEmpty(); index++) session.pendingVideos.removeFirst();
            if (inserted > 0) flow(session, "dispatched");
            return inserted;
        }
    }

    private static int bufferVideoPage(Session session, DouyinSearch.Result page) {
        synchronized (session) {
            if (session.cancelled) return 0;
            int fresh = 0;
            int size = Math.min(50, Math.min(page.items.size(), page.nativeAwemes.size()));
            for (int index = 0; index < size; index++) {
                DouyinSearch.Item item = page.items.get(index);
                Object video = page.nativeAwemes.get(index);
                if (item == null || video == null || item.awemeId.isEmpty() || session.owner.source.equals(item.awemeId)
                        || !session.videoIds.add(item.awemeId)) continue;
                session.pendingVideos.addLast(video);
                fresh++;
            }
            return fresh;
        }
    }

    private void pageFailed(Session session, CommentAiVideoPageState.Next page, Exception failure) {
        session.pages.failed(page);
        if (!belongs(session)) return;
        safeLog("comment_ai_page_failed; error=" + failure.getClass().getSimpleName() + "; can_retry=true");
        android.widget.Toast.makeText(applicationContext, "视频加载失败，再向下滑动重试", android.widget.Toast.LENGTH_SHORT).show();
    }

    static boolean canRetryTextOnly(Exception failure, boolean hasImages, boolean textSeen) {
        if (!hasImages || textSeen || !(failure instanceof ChatCompletionClient.ReplyFailure)) return false;
        ChatCompletionClient.ReplyFailure replyFailure = (ChatCompletionClient.ReplyFailure) failure;
        if (replyFailure.kind() != ChatCompletionClient.ReplyFailure.Kind.HTTP) return false;
        int status = replyFailure.httpStatus();
        return status == 400 || status == 413 || status == 415 || status == 422;
    }

    private void ensureGenerating(Session session) {
        if (session.finished) throw new CancellationException("Comment AI answer is already complete");
        ensureRecommending(session);
    }

    private void ensureRecommending(Session session) {
        LocalOptions current = options;
        if (closed || session.cancelled || Thread.currentThread().isInterrupted()
                || current == null || !current.enabled(CommentAiSettings.TAKEOVER)
                || session.optionsEpoch != optionsEpoch.get() || session.owner.round != session
                || session.owner.generation.get() != session.generation || session.manager.get() == null) {
            throw new CancellationException("Comment AI session is no longer active");
        }
    }

    private void publish(Session session, String text, boolean complete) {
        if (!current(session)) return;
        Object manager = session.manager.get();
        try {
            host.frame(manager, session.conversationId, session.rank, session.stream.update(text, complete));
            if (complete) {
                host.receive.invoke(manager, host.finish.newInstance(session.conversationId, session.rank, null, null));
                session.finished = true;
                safeLog("comment_ai_model_completed; native_stream_dispatched=true; ui_result=unconfirmed");
            }
        } catch (Exception failure) {
            cancelSession(session, true);
            safeLog("comment_ai_render_dispatch_failed; error=" + failure.getClass().getSimpleName());
        }
    }

    private void fail(Session session, Exception failure) {
        if (!current(session)) return;
        safeLog("comment_ai_model_failed; error=" + failure.getClass().getSimpleName());
        try {
            session.stream.cancel();
            host.receive.invoke(session.manager.get(), host.error.newInstance(session.conversationId,
                    session.rank, new IllegalStateException("Module AI generation failed"), host.errorKind,
                    "module_ai_generation_failed", null));
            session.finished = true;
        } catch (Exception dispatchFailure) {
            cancelSession(session, true);
            safeLog("comment_ai_error_dispatch_failed; error=" + dispatchFailure.getClass().getSimpleName());
        }
    }

    private boolean current(Session session) {
        return !session.finished && belongs(session);
    }

    private boolean belongs(Session session) {
        if (closed || session.cancelled || options == null
                || !options.enabled(CommentAiSettings.TAKEOVER)
                || session.optionsEpoch != optionsEpoch.get()) return false;
        Object manager = session.manager.get();
        if (manager == null) return false;
        synchronized (conversations) {
            if (conversations.get(manager) != session.owner || session.owner.round != session
                    || session.generation != session.owner.generation.get()) return false;
        }
        try { return session.owner.source.equals(text(field(host.aweme(manager), "aid"))); }
        catch (Exception failure) { return false; }
    }

    private void cancelManager(Object manager, boolean clearHistory, boolean notifyHost) {
        Conversation owner;
        synchronized (conversations) { owner = conversations.get(manager); }
        if (owner != null) {
            owner.generation.incrementAndGet();
            Session round = owner.round;
            owner.round = null;
            if (round != null) cancelSession(round, notifyHost);
            if (clearHistory) owner.client.clearHistory();
        }
    }

    private void cancelSession(Session session, boolean notifyHost) {
        synchronized (session) {
            if (session.cancelled) return;
            session.cancelled = true;
        }
        session.stream.cancel();
        session.media.cancel();
        session.owner.client.cancelCurrentRequest();
        session.textFallbackClient.cancelCurrentRequest();
        session.recommendationClient.cancelCurrentRequest();
        session.pages.close();
        DouyinSearch.Request searchOperation;
        DouyinSearch.Request pageOperation;
        synchronized (session) {
            searchOperation = session.recommendationSearch;
            pageOperation = session.pageSearch;
            session.recommendationSearch = null;
            session.pageSearch = null;
            session.recommendationWorks.clear();
            session.pendingVideos.clear();
            session.videoIds.clear();
        }
        if (searchOperation != null) try { searchOperation.cancel(); } catch (RuntimeException ignored) { }
        if (pageOperation != null) try { pageOperation.cancel(); } catch (RuntimeException ignored) { }
        onMain(() -> {
            if (session.paging != null) {
                session.paging.close();
                session.paging = null;
            }
            if (session.suggestions != null) {
                session.suggestions.close();
                session.suggestions = null;
            }
        });
        Future<?> work = session.work;
        if (work != null) work.cancel(true);
        Future<?> pageWork = session.pageWork;
        if (pageWork != null) pageWork.cancel(true);
        if (notifyHost && !session.finished) onMain(() -> {
            Object manager = session.manager.get();
            if (manager != null) try {
                host.receive.invoke(manager, host.cancelEvent.newInstance(session.conversationId, session.rank));
            } catch (Exception ignored) { safeLog("comment_ai_cancel_dispatch_failed"); }
        });
        if (!session.finished) safeLog("comment_ai_model_cancelled; notify_host=" + notifyHost);
    }

    private void cancelAll(boolean clearHistory, boolean notifyHost) {
        List<Conversation> owners;
        synchronized (conversations) {
            owners = new ArrayList<>(conversations.values());
            if (clearHistory) conversations.clear();
        }
        for (Conversation owner : owners) {
            owner.generation.incrementAndGet();
            Session round = owner.round;
            owner.round = null;
            if (round != null) cancelSession(round, notifyHost);
            if (clearHistory) owner.client.clearHistory();
        }
    }

    private void cancelPrefetch() {
        try { host.prefetchCancel.invoke(host.prefetchSingleton.invoke(null)); }
        catch (Exception failure) { safeLog("comment_ai_prefetch_cancel_failed; error=" + failure.getClass().getSimpleName()); }
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else main.post(action);
    }
    private void safeLog(String value) {
        // Fixed adapter metadata needs its own event; legacy logs are hashed and shared across features.
        try {
            int separator = value.indexOf(';');
            DouAiLog.event("comment_ai", "adapter_state", separator < 0 ? value : value.substring(0, separator), value);
        } catch (RuntimeException ignored) { }
    }

    static void validateNativeAppend(Object appendCommand, Object gson, Method fromJson, Class<?> messageType)
            throws ReflectiveOperationException {
        // The host owns a Gson JsonObject here, not an org.json JSONObject from the module.
        Object displayJson = field(appendCommand, "display");
        if (displayJson == null) throw new IllegalStateException("The host did not decode the append display");
        Object message = fromJson.invoke(gson, displayJson.toString(), messageType);
        Object messageDisplay = field(message, "LIZ");
        Object rawSpans = field(messageDisplay, "LIZIZ");
        if (!(rawSpans instanceof List) || ((List<?>) rawSpans).size() != 1) {
            throw new IllegalStateException("The host did not decode native comment AI message spans");
        }
        Object text = field(((List<?>) rawSpans).get(0), "LJIIL");
        if (text == null || !"schema".equals(field(text, "LIZ"))) {
            throw new IllegalStateException("The host did not decode native comment AI message text");
        }
    }

    static Object createNativeSchemaGson(Object gson, Object businessFactory, Class<?> factoryType)
            throws ReflectiveOperationException {
        // A fresh host Gson keeps preflight adapters out of the shared cache before page registration.
        Object builder = gson.getClass().getMethod("newBuilder").invoke(gson);
        builder.getClass().getMethod("registerTypeAdapterFactory", factoryType).invoke(builder, businessFactory);
        return builder.getClass().getMethod("create").invoke(builder);
    }

    private static String text(Object value) { return value == null ? "" : String.valueOf(value); }
    private static Object field(Object receiver, String name) throws ReflectiveOperationException {
        if (receiver == null) return null;
        Class<?> type = receiver.getClass();
        while (type != null) {
            try { Field field = type.getDeclaredField(name); field.setAccessible(true); return field.get(receiver); }
            catch (NoSuchFieldException absent) { type = type.getSuperclass(); }
        }
        throw new NoSuchFieldException(name);
    }

    private static final class Conversation {
        final String source;
        final String modelId;
        final ChatCompletionClient client = new ChatCompletionClient();
        final AtomicLong generation = new AtomicLong();
        volatile Session round;
        Conversation(String source, String modelId) { this.source = source; this.modelId = modelId; }
    }
    private static void flow(Session session, String stage) {
        if (session.analytics != null) session.analytics.stage(stage);
    }
    private static final class Session {
        com.douai.module.analytics.LocalAnalytics.FlowOperation analytics;
        final WeakReference<Object> manager;
        final Conversation owner;
        final long generation;
        final CommentAiNativeStream stream;
        final String conversationId;
        final int rank;
        final long optionsEpoch;
        final MediaReadCancellation media = new MediaReadCancellation();
        final ChatCompletionClient textFallbackClient = new ChatCompletionClient();
        final ChatCompletionClient recommendationClient = new ChatCompletionClient();
        final Map<String, Object> recommendationWorks = new LinkedHashMap<>();
        final CommentAiVideoPageState pages = new CommentAiVideoPageState();
        final ArrayDeque<Object> pendingVideos = new ArrayDeque<>();
        final Set<String> videoIds = new HashSet<>();
        String recommendationQuery = "";
        long lastPageAttempt;
        volatile DouyinSearch.Request recommendationSearch;
        volatile DouyinSearch.Request pageSearch;
        CommentAiSuggestionRows.Handle suggestions;
        HostCommentAiPaging.Handle paging;
        boolean recommendationsPublished;
        boolean videosPublished;
        volatile Future<?> work;
        volatile Future<?> pageWork;
        volatile boolean cancelled;
        volatile boolean finished;
        volatile boolean modelTextSeen;
        Session(Object manager, Conversation owner, long generation, CommentAiNativeStream stream,
                String conversationId, int rank, long optionsEpoch) {
            this.manager = new WeakReference<>(manager); this.owner = owner; this.generation = generation;
            this.stream = stream; this.conversationId = conversationId; this.rank = rank;
            this.optionsEpoch = optionsEpoch;
        }
    }

    /** Exact 41.0 symbols; preflight checks the host Gson's real native-data mapping. */
    private static final class Bindings {
        final Method request, initialize, cancelOfficial, receive, cancel, reset, scene;
        final Method createEntry, prefetch, prefetchCancel, prefetchSingleton;
        final Method pageChanged, visible, dismiss, destroy, compose, getLazyValue;
        final Constructor<?> start, streamEvent, finish, error, cancelEvent, composerLambda, adapter;
        final Field ledger, currentConversation, context;
        final Class<?> awemeType, commandDataType, nativeTextType;
        final Class<?> messageType;
        final Object commentScene, aiCardType, errorKind, nativeFlag;
        final Method gsonProvider, getGson, fromJson;
        final Class<?> typeAdapterFactory;
        final Constructor<?> businessDataFactory;
        private boolean nativeSchemaValidated;
        private volatile String nativeSchemaStage = "not_started";

        Bindings(ClassLoader loader) throws Exception {
            Class<?> manager = type(loader, "X.1B3z"), param = type(loader, "X.1B3X");
            Class<?> state = type(loader, "X.1B4M"), contextType = type(loader,
                    "com.ss.android.ugc.aweme.c2page.common.newarchitecture.context.IC2ArchContext");
            request = method(manager, "LIZLLL", void.class, param);
            initialize = method(manager, "LJIIJ", void.class, param);
            cancelOfficial = method(manager, "LIZJ", void.class);
            receive = method(manager, "LJIILIIL", void.class, state);
            cancel = method(manager, "LIZIZ", void.class);
            reset = method(manager, "LJIIL", void.class);
            scene = method(type(loader, "X.1BaN"), "LIZ", String.class, contextType);
            context = manager.getDeclaredField("LIZIZ"); context.setAccessible(true);
            ledger = manager.getDeclaredField("LIZ"); ledger.setAccessible(true);
            currentConversation = type(loader, "X.1B4J").getDeclaredField("LIZIZ"); currentConversation.setAccessible(true);
            Class<?> base = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.ai.BaseSSEResponse");
            Class<?> eventType = type(loader, "X.1Ape");
            start = type(loader, "X.1BUJ").getConstructor(String.class, int.class, param);
            streamEvent = type(loader, "X.1BqP").getConstructor(String.class, int.class, eventType,
                    String.class, long.class, long.class, base);
            finish = type(loader, "X.1Brw").getConstructor(String.class, int.class, base, String.class);
            Class<?> errorType = type(loader, "X.1Brg");
            error = type(loader, "X.0end").getConstructor(String.class, int.class, Throwable.class,
                    errorType, String.class, base);
            errorKind = errorType.getField("TryCatch").get(null);
            cancelEvent = type(loader, "X.0enb").getConstructor(String.class, int.class);
            aiCardType = eventType.getField("AI_CARD").get(null);
            awemeType = type(loader, "com.ss.android.ugc.aweme.feed.model.Aweme");
            Class<?> fragment = type(loader, "androidx.fragment.app.Fragment");
            Class<?> service = type(loader, "com.ss.android.ugc.aweme.c2recommend.commentai.service.C2FeedCommentAiAnalysisService");
            createEntry = method(service, "LIZ", type(loader, "X.12L3"), fragment, String.class);
            Class<?> sceneType = type(loader, "X.1B3O");
            commentScene = sceneType.getField("COMMENT_AI_ANALYSIS").get(null);
            composerLambda = type(loader, "X.1B46").getConstructor(fragment, String.class, boolean.class);
            Class<?> composer = type(loader, "com.ss.android.ugc.aweme.c2page.common.newarchitecture.assemble.IC2Composer");
            compose = method(type(loader, "X.1AyY"), "LIZ", composer, sceneType, type(loader, "kotlin.jvm.functions.Function3"));
            adapter = type(loader, "X.1B8r").getConstructor(composer, String.class);
            nativeFlag = type(loader, "X.00lO").getField("LIZIZ").get(null);
            getLazyValue = type(loader, "kotlin.Lazy").getMethod("getValue");
            Class<?> prefetchType = type(loader, "com.ss.android.ugc.aweme.c2recommend.commentai.service.C2FeedCommentAiAnalysisSSEPrefetcherImpl");
            prefetch = method(prefetchType, "LIZ", void.class, awemeType,
                    type(loader, "com.ss.android.ugc.aweme.ability.CommentAiAnalysisSSEPrefetchParams"));
            prefetchCancel = method(prefetchType, "cancel", void.class);
            prefetchSingleton = method(prefetchType, "LIZIZ", prefetchType);
            Class<?> wrapper = type(loader, "com.ss.android.ugc.aweme.comment.aianalysis.AIAnalysisWrapperFragmentV2");
            pageChanged = method(wrapper, "b0", void.class, type(loader, "X.11ue"), boolean.class);
            visible = method(wrapper, "setUserVisibleHint", void.class, boolean.class);
            dismiss = method(wrapper, "X1", void.class, type(loader, "androidx.fragment.app.FragmentActivity"), String.class);
            destroy = method(wrapper, "onDestroyView", void.class);
            Class<?> gson = type(loader, "com.google.gson.Gson");
            Class<?> provider = type(loader, "com.ss.android.ugc.aweme.utils.GsonProvider");
            gsonProvider = method(type(loader, "com.ss.android.ugc.aweme.utils.GsonHolder"), "LIZIZ", provider);
            getGson = method(provider, "getGson", gson);
            fromJson = gson.getMethod("fromJson", String.class, Class.class);
            typeAdapterFactory = type(loader, "com.google.gson.TypeAdapterFactory");
            businessDataFactory = type(loader,
                    "com.ss.ugc.android.cachalot.common.CachalotBusinessDataItemTypeAdapterFactory").getConstructor();
            commandDataType = type(loader, "com.ss.android.ugc.aweme.search.ai.specificbiz.bizmodule.chat.data.AiStreamCommandData");
            nativeTextType = type(loader, "com.ss.android.ugc.aweme.search.ai.specificbiz.container.card.quick.text.model.AiTextModel");
            messageType = type(loader, "com.ss.android.ugc.aweme.search.ai.specificbiz.container.card.quick.text.model.Message");
        }

        String scene(Object manager) {
            try { return (String) scene.invoke(null, context.get(manager)); }
            catch (Exception unavailable) { return ""; }
        }
        Object aweme(Object manager) throws Exception {
            Object owner = context.get(manager);
            return owner.getClass().getMethod("LIZJ", Class.class).invoke(owner, awemeType);
        }
        Object nativeEntry(Object fragment, String from) throws Exception {
            boolean flag = Boolean.TRUE.equals(getLazyValue.invoke(nativeFlag));
            Object assembled = compose.invoke(null, commentScene, composerLambda.newInstance(fragment, from, flag));
            return adapter.newInstance(assembled, from);
        }
        void frame(Object manager, String conversation, int rank, JSONObject data) throws Exception {
            if (data != null) receive.invoke(manager,
                    streamEvent.newInstance(conversation, rank, aiCardType, data.toString(), 0L, 0L, null));
        }
        synchronized boolean validateNativeSchema() throws Exception {
            if (nativeSchemaValidated) return true;
            CommentAiNativeStream stream = new CommentAiNativeStream("douai_schema", 1, "schema", "douai_schema_card");
            JSONObject fixture = stream.firstFrame();
            nativeSchemaStage = "isolated_host_gson";
            Object sharedGson = getGson.invoke(gsonProvider.invoke(null));
            Object gson = createNativeSchemaGson(sharedGson, businessDataFactory.newInstance(), typeAdapterFactory);
            nativeSchemaStage = "new_card_decode";
            Object parsed = fromJson.invoke(gson, fixture.toString(), commandDataType);
            Object command = ((List<?>) field(parsed, "data")).get(0);
            Object container = field(command, "containerInfo");
            Object business = ((List<?>) field(container, "businessData")).get(0);
            Object model = field(business, "data");
            nativeSchemaStage = "native_text_model_type";
            if (!nativeTextType.isInstance(model)) {
                throw new IllegalStateException("The host did not decode native comment AI text data");
            }
            nativeSchemaStage = "native_card_data";
            if (field(model, "cardData") == null) throw new IllegalStateException("The host did not decode native card data");
            JSONObject appendFrame = stream.update("schema", true);
            nativeSchemaStage = "append_decode";
            Object appended = fromJson.invoke(gson, appendFrame.toString(), commandDataType);
            Object appendCommand = ((List<?>) field(appended, "data")).get(0);
            nativeSchemaStage = "append_message_text";
            validateNativeAppend(appendCommand, gson, fromJson, messageType);
            ClassLoader loader = nativeTextType.getClassLoader();
            nativeSchemaStage = "business_type_id";
            Class<?> dataType = Class.forName("com.ss.android.ugc.aweme.search.ai.specificbiz.container.card.quick.text.SearchAiTextDataType", false, loader);
            if (!"999670".equals(dataType.getMethod("getType").invoke(dataType.getMethod("LIZ").invoke(null)))) {
                throw new IllegalStateException("The host text data adapter type changed");
            }
            nativeSchemaStage = "renderer_type_id";
            Class<?> factory = Class.forName("com.ss.android.ugc.aweme.search.ai.specificbiz.container.card.quick.text.AiStreamTextCardFactory", false, loader);
            Object factoryInstance = factory.getMethod("LIZLLL").invoke(null);
            if (!String.valueOf(CommentAiNativeStream.CARD_TYPE).equals(factory.getMethod("getCardType").invoke(factoryInstance))) {
                throw new IllegalStateException("The host native text renderer changed");
            }
            nativeSchemaValidated = true;
            nativeSchemaStage = "verified";
            return true;
        }
        private static Class<?> type(ClassLoader loader, String name) throws ClassNotFoundException {
            return Class.forName(name, false, loader);
        }
        private static Method method(Class<?> owner, String name, Class<?> result, Class<?>... arguments) throws NoSuchMethodException {
            Method method = owner.getDeclaredMethod(name, arguments);
            if (method.getReturnType() != result) throw new NoSuchMethodException(owner.getName() + "." + name + " return type changed");
            method.setAccessible(true);
            return method;
        }
    }
}
