package com.douai.module.feature;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;

import com.douai.module.config.ExtendedSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.HostAdaptation;
import com.douai.module.scan.HostResourceProfile;

import io.github.libxposed.api.XposedInterface;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns completion and feed navigation without enabling the host auto-next model. */
public final class AutoPlayHook implements Application.ActivityLifecycleCallbacks, AutoCloseable {
    private final Application application;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BehaviorHookGroup group;
    private final Consumer<String> log;
    private final Method awemeAccessor;
    private final Method next;
    private final Method previous;
    private final Method currentHolder;
    private final Method setCurrentItem;
    private final Method commentsShowing;
    private final Method scrollState;
    private final AtomicBoolean holderRecoveryQueued = new AtomicBoolean();
    private final Field rootField;
    private final Field paramsField;
    private final Field feedContextField;
    private final Field pagerContextField;
    private final Field pagerField;
    private final Field dialogField;
    private WeakReference<Object> selectedHolder = new WeakReference<>(null);
    private WeakReference<View> selectedRoot = new WeakReference<>(null);
    private WeakReference<Activity> resumed = new WeakReference<>(null);
    private WeakReference<Activity> pausedActivity = new WeakReference<>(null);
    private WeakReference<Object> touchPager = new WeakReference<>(null);
    private volatile ExtendedSettings settings;
    private volatile LocalOptions options;
    private volatile long generation;
    private volatile boolean closed;
    private com.douai.module.analytics.LocalAnalytics.FlowOperation playbackFlow;
    private String playbackState;
    private int playbackSequence;
    private com.douai.module.analytics.LocalAnalytics.FlowOperation backwardFlow;
    private WeakReference<Object> backwardPager = new WeakReference<>(null);
    private int backwardStart = -1;
    private long backwardUntil;
    private boolean backwardArmed;
    private volatile String selectedAid;
    private volatile boolean paused;
    private boolean attempted;
    private boolean pendingAdvance;
    private boolean touching;
    private boolean multiTouch;
    private float downX;
    private float downY;
    private int direction = 1;
    private volatile String diagnostic = "等待当前视频播放完成";
    private String lastSkip;
    private PlaybackTransitionFrame.Overlay transitionFrame;
    private PlaybackTransitionFrame.Overlay fadingTransitionFrame;
    private String transitionSourceAid;
    private String transitionPlayingAid;
    private Runnable transitionTimeout;
    private Runnable transitionRelease;
    private boolean transitionRedirecting;

    private AutoPlayHook(Application application, ExtendedSettings settings, LocalOptions options,
                         Consumer<String> log, Method awemeAccessor, Method next, Method previous,
                         Method currentHolder, Method setCurrentItem, Method commentsShowing, Method scrollState,
                         Field rootField, Field paramsField, Field feedContextField,
                         Field pagerContextField, Field pagerField, Field dialogField) {
        this.application = application;
        this.settings = settings;
        this.options = options;
        this.log = log;
        this.group = new BehaviorHookGroup("auto_slide", log);
        this.awemeAccessor = awemeAccessor;
        this.next = next;
        this.previous = previous;
        this.currentHolder = currentHolder;
        this.setCurrentItem = setCurrentItem;
        this.commentsShowing = commentsShowing;
        this.scrollState = scrollState;
        this.rootField = rootField;
        this.paramsField = paramsField;
        this.feedContextField = feedContextField;
        this.pagerContextField = pagerContextField;
        this.pagerField = pagerField;
        this.dialogField = dialogField;
    }

    public boolean isPausedFor(String aid) {
        return aid != null && aid.equals(selectedAid) && paused;
    }

    public Boolean pauseStateFor(String aid) {
        return aid == null || !aid.equals(selectedAid) ? null : paused;
    }

    public static AutoPlayHook install(XposedInterface framework, HostScanReport report,
                                       ClassLoader loader, Context context, ExtendedSettings settings,
                                       LocalOptions options, Consumer<String> log) throws Exception {
        AutoPlayHook hook = null;
        Consumer<String> sink = log == null ? ignored -> { } : log;
        try {
            Context app = context.getApplicationContext();
            if (!(app instanceof Application)) throw new IllegalStateException("Host Application unavailable");
            Method selected = HostTargets.method(report, "feed_holder_page_selected", loader);
            Method resume = HostTargets.method(report, "feed_holder_resume", loader);
            Method pause = HostTargets.method(report, "feed_holder_pause", loader);
            Method aweme = HostTargets.method(report, "feed_aweme_accessor", loader);
            Class<?> holder = selected.getDeclaringClass();
            if (holder != resume.getDeclaringClass() || holder != pause.getDeclaringClass()
                    || holder != aweme.getDeclaringClass()
                    || HostTargets.type(report, "feed_holder_main_root", loader) != holder) {
                throw new IllegalStateException("Auto-slide holder relationship changed");
            }
            Field root = HostTargets.field(holder, "w");
            Field params = HostTargets.field(holder,
                    HostResourceProfile.feedItemParamsField(HostAdaptation.currentProfile()));
            Class<?> paramsType = HostTargets.type(report, "adv_gesture_item_params", loader);
            Field panel = HostTargets.field(paramsType, "mFeedContext");
            Method panelContext = HostTargets.method(report, "auto_slide_panel_context", loader);
            Method current = HostTargets.method(report, "auto_slide_current_holder", loader);
            Field pagerContext = HostTargets.field(panelContext.getDeclaringClass(), "B3");
            Field pager = HostTargets.field(panelContext.getDeclaringClass(), "f");
            Field dialogs = HostTargets.field(panelContext.getDeclaringClass(), "L0");
            Method next = HostTargets.method(report, "adv_player_move_next", loader);
            Method previous = HostTargets.method(report, "adv_player_move_previous", loader);
            Method touch = HostTargets.method(report, "auto_slide_pager_touch", loader);
            Method scroll = HostTargets.method(report, "auto_slide_scroll_state", loader);
            Method comments = HostTargets.method(report, "auto_slide_comments_showing", loader);
            Method completed = HostTargets.method(report, "auto_slide_completed", loader);
            Method playing = HostTargets.method(report, "component_player_playing", loader);
            Method playerPause = HostTargets.method(report, "component_player_pause", loader);
            Method setCurrentItem = null;
            try {
                setCurrentItem = pager.getType().getMethod("setCurrentItem", int.class, boolean.class);
            } catch (NoSuchMethodException ignored) { }
            if (!View.class.isAssignableFrom(root.getType()) || params.getType() != paramsType
                    || !panel.getType().isAssignableFrom(panelContext.getDeclaringClass())
                    || current.getDeclaringClass() != panelContext.getDeclaringClass()
                    || pagerContext.getType() != next.getDeclaringClass()
                    || next.getDeclaringClass() != previous.getDeclaringClass()
                    || pager.getType() != touch.getDeclaringClass()
                    || touch.getDeclaringClass() != scroll.getDeclaringClass()
                    || dialogs.getType() != comments.getDeclaringClass()
                    || completed.getDeclaringClass() != playing.getDeclaringClass()
                    || completed.getDeclaringClass() != playerPause.getDeclaringClass()) {
                throw new IllegalStateException("Auto-slide player/pager relationship changed");
            }
            hook = new AutoPlayHook((Application) app, settings, options, sink, aweme, next, previous,
                    current, setCurrentItem, comments, scroll, root, params, panel, pagerContext, pager, dialogs);
            AutoPlayHook installed = hook;
            for (Method target : new Method[]{selected, resume}) {
                hook.group.add(framework, target, chain -> {
                    Object result = chain.proceed();
                    Object owner = chain.getThisObject();
                    installed.onMain(() -> installed.select(owner));
                    return result;
                });
            }
            hook.group.add(framework, pause, chain -> {
                Object result = chain.proceed();
                Object owner = chain.getThisObject();
                installed.onMain(() -> {
                    if (installed.selectedHolder.get() == owner) installed.clearSelection();
                });
                return result;
            });
            hook.group.add(framework, touch, chain -> {
                // Read raw coordinates before VerticalViewPager transforms the event axes.
                installed.recordTouch(chain.getThisObject(), (MotionEvent) chain.getArg(0));
                return chain.proceed();
            });
            if (setCurrentItem != null) {
                hook.group.add(framework, setCurrentItem, XposedInterface.PRIORITY_HIGHEST,
                        installed::interceptPagerSelection);
            }
            for (Method target : new Method[]{playing, playerPause}) {
                boolean isPause = target == playerPause;
                hook.group.add(framework, target, chain -> {
                    com.douai.module.analytics.LocalAnalytics.FlowOperation observedFlow = installed.playbackFlow;
                    Object result = chain.proceed();
                    String aid = (String) chain.getArg(0);
                    installed.onMain(() -> {
                        if (!isPause && aid != null && installed.transitionFrame != null
                                && !aid.equals(installed.transitionSourceAid)) {
                            installed.transitionPlayingAid = aid;
                            if (aid.equals(installed.selectedAid)) installed.releaseTransitionAfterPlayback(aid);
                        }
                        if (aid != null && aid.equals(installed.selectedAid)) {
                            if (observedFlow == installed.playbackFlow) installed.observePlayback(isPause ? "paused" : "playing");
                            installed.paused = isPause;
                            if (isPause) {
                                installed.generation++;
                                installed.pendingAdvance = false;
                            }
                        }
                    });
                    return result;
                });
            }
            hook.group.add(framework, completed, chain -> {
                long token = installed.generation;
                String aid = (String) chain.getArg(0);
                Object result = chain.proceed();
                // Host listeners can select another item during proceed(). Recheck after dispatch.
                installed.main.post(() -> installed.complete(aid, token));
                return result;
            });
            hook.group.add(framework, current, chain -> {
                Object recoveredHolder = chain.proceed();
                installed.recoverHolder(recoveredHolder, chain.getThisObject());
                return recoveredHolder;
            });
            hook.group.add(framework, aweme, chain -> {
                Object result = chain.proceed();
                installed.recoverHolder(chain.getThisObject(), null);
                return result;
            });
            hook.application.registerActivityLifecycleCallbacks(hook);
            hook.safeLog("DouAi auto-slide registered; native auto-next model untouched");
            return hook;
        } catch (Throwable failure) {
            if (hook != null) hook.close();
            if (failure instanceof Exception) throw (Exception) failure;
            throw failure;
        }
    }

    public boolean supports(String key) {
        return !closed && group.isActive()
                && ("auto_play".equals(key) || "smart_swipe".equals(key) || "pause_auto_on_comments".equals(key));
    }

    public String diagnostic() { return diagnostic; }

    public void setSettings(ExtendedSettings settings, LocalOptions options) {
        onMain(() -> {
            if (closed) return;
            this.settings = settings;
            this.options = options;
            generation++;
            pendingAdvance = false;
            dismissTransitionFrame(false);
            diagnostic = settings != null && settings.autoPlay ? "等待当前视频播放完成" : "自动滑动已关闭";
        });
    }

    private void select(Object holder) {
        if (closed) return;
        try {
            View root = (View) rootField.get(holder);
            Activity activity = activity(root == null ? null : root.getContext());
            String aid = aid(holder);
            if (activity == null || root == null || !root.isShown() || aid == null || aid.isEmpty()) return;
            Object params = paramsField.get(holder);
            Object panel = params == null ? null : feedContextField.get(params);
            if (panel == null || currentHolder.invoke(panel) != holder) return;
            if (selectedHolder.get() != holder || !aid.equals(selectedAid)) {
                PlaybackTransitionFrame.invalidateSurfaceCache(selectedRoot.get());
                generation++;
                attempted = false;
                pendingAdvance = false;
                lastSkip = null;
                diagnostic = "等待当前视频播放完成";
            }
            boolean sameSelection = selectedHolder.get() == holder && aid.equals(selectedAid);
            selectedHolder = new WeakReference<>(holder);
            selectedRoot = new WeakReference<>(root);
            selectedAid = aid;
            if (!sameSelection) {
                paused = false;
                playbackFlow = com.douai.module.analytics.LocalAnalytics.get(application).beginOperation(aid,"playback");
                playbackState = null; playbackSequence = 0;
                Object gesturePager = backwardPager.get();
                Object index = gesturePager == null ? null : HostTargets.invoke(gesturePager,"getCurrentItem");
                if (backwardArmed && backwardFlow != null && gesturePager == pagerField.get(panel)
                        && android.os.SystemClock.elapsedRealtime() <= backwardUntil
                        && index instanceof Number && ((Number)index).intValue() == backwardStart - 1) backwardFlow.stage("confirmed");
                backwardArmed = false;
            }
            if (aid.equals(transitionPlayingAid)) releaseTransitionAfterPlayback(aid);
            if (root.hasWindowFocus()) resumed = new WeakReference<>(activity);
        } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
    }

    /** Normalizes manual ViewPager animation to the same captured-frame handoff as auto-next. */
    private Object interceptPagerSelection(XposedInterface.Chain chain) throws Throwable {
        java.util.List<Object> args = chain.getArgs();
        if (closed || setCurrentItem == null || transitionRedirecting || args == null || args.size() < 2
                || !(args.get(0) instanceof Number) || !Boolean.TRUE.equals(args.get(1))) {
            return chain.proceed();
        }
        Object pager = chain.getThisObject();
        View root = activeRoot();
        String sourceAid = selectedAid;
        Object panel;
        try {
            panel = panel();
            if (panel == null || pagerField.get(panel) != pager || root == null || sourceAid == null) {
                return chain.proceed();
            }
        } catch (ReflectiveOperationException | RuntimeException unavailable) {
            return chain.proceed();
        }

        int target = ((Number) args.get(0)).intValue();
        long token = generation;
        DouAiLog.Operation operation = DouAiLog.begin("auto_play", "manual_page_transition");
        operation.stage("manual_transition_intercept", "target_index=" + target + ";smooth_scroll=true");
        AtomicBoolean claimed = new AtomicBoolean();
        Runnable fallback = operation.wrap(() -> {
            if (!claimed.compareAndSet(false, true)) return;
            operation.stage("transition_frame", "captured=false;reason=capture_timeout;manual=true");
            invokeManualSelection(pager, target, root, sourceAid, token, operation, null);
        });
        main.postDelayed(fallback, 120L);
        PlaybackTransitionFrame.capture(root, main,
                () -> !claimed.get() && !closed && generation == token && sourceAid.equals(selectedAid)
                        && activeRoot() == root, operation.wrapConsumer(frame -> {
            if (!claimed.compareAndSet(false, true)) {
                if (frame != null) frame.close();
                return;
            }
            main.removeCallbacks(fallback);
            operation.stage("transition_frame", "captured=" + (frame != null) + ";memory_only=true;manual=true");
            invokeManualSelection(pager, target, root, sourceAid, token, operation, frame);
        }));
        return null;
    }

    private void invokeManualSelection(Object pager, int target, View root, String sourceAid, long token,
                                       DouAiLog.Operation operation, PlaybackTransitionFrame.Overlay frame) {
        if (closed || generation != token || activeRoot() != root || !sourceAid.equals(selectedAid)
                || panelPager() != pager) {
            if (frame != null) frame.close();
            operation.finish(DouAiLog.Outcome.CANCELLED, "manual_transition_target_changed",
                    "advance_invoked=false");
            return;
        }
        if (frame != null) {
            dismissTransitionFrame(false);
            transitionFrame = frame;
            transitionSourceAid = sourceAid;
            transitionPlayingAid = null;
            transitionTimeout = () -> {
                if (transitionFrame == frame) dismissTransitionFrame(true);
            };
            main.postDelayed(transitionTimeout, 4500L);
        }
        try {
            transitionRedirecting = true;
            setCurrentItem.invoke(pager, target, false);
            operation.stage("native_advance", "direction=manual;target_index=" + target
                    + ";smooth_scroll=false;request_only=true;transition_frame=" + (frame != null));
            operation.finish(DouAiLog.Outcome.SUCCESS, "manual_selection_redirected",
                    "native_accepted=true;result_scope=selected_card;playback_started=unobserved");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (frame != null) dismissTransitionFrame(false);
            operation.error("manual_transition_redirect", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "manual_selection_redirect_failed",
                    "advance_invoked=false");
        } finally {
            transitionRedirecting = false;
        }
    }

    private Object panelPager() {
        try {
            Object panel = panel();
            return panel == null ? null : pagerField.get(panel);
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            return null;
        }
    }

    private void recoverHolder(Object holder, Object expectedPanel) {
        if (closed || !group.isActive() || !awemeAccessor.getDeclaringClass().isInstance(holder)
                || !holderRecoveryQueued.compareAndSet(false, true)) return;
        onMain(() -> {
            try {
                if (closed || !group.isActive() || !recoverableRoot(holder)) return;
                Object panel = paramsField.get(holder);
                panel = panel == null ? null : feedContextField.get(panel);
                if (panel != null && (expectedPanel == null || panel == expectedPanel)
                        && currentHolder.getDeclaringClass().isInstance(panel)
                        && currentHolder.invoke(panel) == holder) {
                    select(holder);
                }
            } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
            finally { holderRecoveryQueued.set(false); }
        });
    }

    private boolean recoverableRoot(Object holder) throws IllegalAccessException {
        Object value = rootField.get(holder);
        if (!(value instanceof View)) return false;
        View root = (View) value;
        Activity owner = activity(root.getContext());
        return root.isAttachedToWindow() && root.isShown() && root.hasWindowFocus()
                && owner != null && pausedActivity.get() != owner
                && !owner.isFinishing() && !owner.isDestroyed();
    }

    private void recordTouch(Object pager, MotionEvent event) {
        if (closed || event == null || Looper.myLooper() != Looper.getMainLooper()) return;
        try {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                pendingAdvance = false;
                dismissTransitionFrame(false);
                Object panel = panel();
                if (panel == null || pagerField.get(panel) != pager || activeRoot() == null) return;
                backwardArmed = false;
                Object startIndex = HostTargets.invoke(pager,"getCurrentItem");
                backwardStart = startIndex instanceof Number ? ((Number)startIndex).intValue() : -1;
                backwardPager = new WeakReference<>(pager);
                backwardFlow = com.douai.module.analytics.LocalAnalytics.get(application).beginOperation(selectedAid,"backward");
                touching = true;
                multiTouch = false;
                touchPager = new WeakReference<>(pager);
                downX = event.getRawX();
                downY = event.getRawY();
                generation++;
            } else if (touching && touchPager.get() == pager) {
                if (action == MotionEvent.ACTION_POINTER_DOWN || event.getPointerCount() > 1) multiTouch = true;
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    if (!multiTouch && action == MotionEvent.ACTION_UP) {
                        int slop = ViewConfiguration.get(((View) pager).getContext()).getScaledTouchSlop();
                        int swipe = manualDirection(downX, downY, event.getRawX(), event.getRawY(), slop);
                        if (swipe != 0) {
                            backwardArmed = swipe < 0 && backwardStart > 0;
                            backwardUntil = android.os.SystemClock.elapsedRealtime() + 1500;
                            direction = swipe;
                            safeLog("DouAi auto-slide manual direction=" + (swipe > 0 ? "UP" : "DOWN"));
                        }
                    }
                    touching = false;
                    multiTouch = false;
                    touchPager.clear();
                }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) { fail(failure); }
    }

    static int manualDirection(float startX, float startY, float endX, float endY, int slop) {
        if (!Float.isFinite(startX) || !Float.isFinite(startY)
                || !Float.isFinite(endX) || !Float.isFinite(endY)) return 0;
        float dx = endX - startX;
        float dy = endY - startY;
        if (Math.abs(dy) <= Math.max(0, slop) || Math.abs(dy) <= Math.abs(dx)) return 0;
        return dy < 0 ? 1 : -1;
    }

    private void observePlayback(String state) {
        if (!state.equals(playbackState)) {
            playbackState = state;
            if (playbackFlow != null) playbackFlow.stage(state, ++playbackSequence);
        }
    }

    private void complete(String aid, long token) {
        if (token == generation && aid != null && aid.equals(selectedAid)) observePlayback("ended");
        ExtendedSettings current = settings;
        View root = activeRoot();
        if (closed || !group.isActive() || current == null || !current.autoPlay) {
            DouAiLog.event("auto_play", "decision", "disabled_or_not_ready", "advance_invoked=false");
            return;
        }
        if (generation != token) { skipped("stale_session", "已忽略过期播放完成事件"); return; }
        if (paused || touching) { skipped("paused_or_touching", "暂停或手动操作中，暂不翻页"); return; }
        if (attempted || pendingAdvance) { skipped("already_attempted", "已处理当前播放完成事件"); return; }
        if (root == null || !root.hasWindowFocus()) { skipped("not_foreground", "当前视频不在前台，暂不翻页"); return; }
        if (aid == null || aid.isEmpty() || !aid.equals(selectedAid)) {
            skipped("different_video", "完成事件不属于当前视频，暂不翻页"); return;
        }

        DouAiLog.Operation operation = DouAiLog.begin("auto_play", "playback_complete");
        operation.stage("validate_current_selection", "work_ref=" + DouAiLog.reference(aid) + ";generation=" + token);
        try {
            Object holder = selectedHolder.get();
            Object panel = panel();
            if (panel == null || !aid.equals(aid(holder)) || currentHolder.invoke(panel) != holder) {
                skipped("holder_changed", "当前视频已切换，暂不翻页");
                operation.finish(DouAiLog.Outcome.SKIPPED, "validation_gate", "advance_invoked=false;reason=holder_changed");
                return;
            }
            Object pager = pagerField.get(panel);
            if (pager == null || ((Number) scrollState.invoke(pager)).intValue() != 0) {
                skipped("pager_scrolling", "视频正在滑动，暂不翻页");
                operation.finish(DouAiLog.Outcome.SKIPPED, "validation_gate", "advance_invoked=false;reason=pager_scrolling");
                return;
            }
            LocalOptions local = options;
            if (local != null && local.enabled("pause_auto_on_comments")) {
                Object dialogs = dialogField.get(panel);
                if (dialogs == null || Boolean.TRUE.equals(commentsShowing.invoke(dialogs, true))) {
                    skipped("comments_showing", "评论已展开，暂不翻页");
                    operation.finish(DouAiLog.Outcome.SKIPPED, "validation_gate", "advance_invoked=false;reason=comments_showing");
                    return;
                }
            }
            Object pagerContext = pagerContextField.get(panel);
            if (pagerContext == null) {
                skipped("pager_context_unavailable", "翻页上下文不可用");
                operation.finish(DouAiLog.Outcome.SKIPPED, "validation_gate", "advance_invoked=false;reason=pager_context_unavailable");
                return;
            }
            boolean followDirection = local == null || local.enabled("smart_swipe", true);
            int targetDirection = followDirection ? direction : 1;
            pendingAdvance = true;
            operation.stage("capture_current_video_frame", "memory_only=true;network=false");

            AtomicBoolean claimed = new AtomicBoolean();
            Runnable fallback = operation.wrap(() -> {
                if (!claimed.compareAndSet(false, true)) return;
                pendingAdvance = false;
                operation.stage("transition_frame", "captured=false;reason=capture_timeout");
                advance(aid, token, root, panel, pagerContext, targetDirection, operation, null);
            });
            main.postDelayed(fallback, 120L);
            PlaybackTransitionFrame.capture(root, main,
                    () -> !claimed.get() && !closed && generation == token && aid.equals(selectedAid)
                            && activeRoot() == root, operation.wrapConsumer(frame -> {
                if (!claimed.compareAndSet(false, true)) {
                    if (frame != null) frame.close();
                    return;
                }
                main.removeCallbacks(fallback);
                pendingAdvance = false;
                operation.stage("transition_frame", "captured=" + (frame != null) + ";memory_only=true");
                advance(aid, token, root, panel, pagerContext, targetDirection, operation, frame);
            }));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            pendingAdvance = false;
            operation.error("prepare_transition", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "transition_capture_failed", "advance_invoked=false");
            fail(failure);
        }
    }

    private void advance(String aid, long token, View root, Object panel, Object pagerContext,
                         int targetDirection, DouAiLog.Operation operation,
                         PlaybackTransitionFrame.Overlay frame) {
        boolean terminal = false;
        try {
            Object currentPager = panel == null ? null : pagerField.get(panel);
            Object currentHolder = panel == null ? null : this.currentHolder.invoke(panel);
            Object currentPagerContext = panel == null ? null : pagerContextField.get(panel);
            Object currentScrollState = currentPager == null ? null : scrollState.invoke(currentPager);
            if (closed || !group.isActive() || settings == null || !settings.autoPlay
                    || generation != token || paused || touching || attempted
                    || !aid.equals(selectedAid) || selectedHolder.get() == null
                    || activeRoot() != root || currentHolder != selectedHolder.get()
                    || currentPagerContext != pagerContext || !(currentScrollState instanceof Number)
                    || ((Number) currentScrollState).intValue() != 0) {
                if (frame != null) frame.close();
                terminal = true;
                operation.finish(DouAiLog.Outcome.CANCELLED, "transition_target_changed",
                        "advance_invoked=false;generation=" + generation);
                return;
            }
            LocalOptions local = options;
            if (local != null && local.enabled("pause_auto_on_comments")) {
                Object dialogs = dialogField.get(panel);
                if (dialogs == null || Boolean.TRUE.equals(commentsShowing.invoke(dialogs, true))) {
                    if (frame != null) frame.close();
                    terminal = true;
                    operation.finish(DouAiLog.Outcome.CANCELLED, "comments_opened_during_transition_capture",
                            "advance_invoked=false");
                    return;
                }
            }
            if (frame != null) {
                dismissTransitionFrame(false);
                transitionFrame = frame;
                transitionSourceAid = aid;
                transitionPlayingAid = null;
                transitionTimeout = () -> {
                    if (transitionFrame == frame) dismissTransitionFrame(true);
                };
                main.postDelayed(transitionTimeout, 4500L);
            }
            attempted = true;
            com.douai.module.analytics.LocalAnalytics.markAutomaticAdvance();
            operation.stage("native_advance", "direction=" + targetDirection
                    + ";smooth_scroll=false;request_only=true;transition_frame=" + (frame != null));
            Object moved;
            try {
                moved = (targetDirection > 0 ? next : previous).invoke(pagerContext, "DouAiAutoSlide", false);
            } catch (ReflectiveOperationException | RuntimeException failure) {
                com.douai.module.analytics.LocalAnalytics.cancelAutomaticAdvance();
                dismissTransitionFrame(false);
                throw failure;
            }
            if (Boolean.TRUE.equals(moved)) {
                com.douai.module.analytics.LocalAnalytics.action(aid, "auto_slide", "自动翻页", "automatic", "accepted");
            } else {
                com.douai.module.analytics.LocalAnalytics.cancelAutomaticAdvance();
                dismissTransitionFrame(false);
            }
            diagnostic = Boolean.TRUE.equals(moved) ? (targetDirection > 0 ? "已请求向上翻页" : "已请求向下翻页")
                    : "当前列表边界未能翻页";
            boolean selectionChanged = selectedAid != null && !aid.equals(selectedAid);
            terminal = true;
            operation.finish(!Boolean.TRUE.equals(moved) ? DouAiLog.Outcome.SKIPPED : selectionChanged
                            ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED,
                    !Boolean.TRUE.equals(moved) ? "pager_rejected_or_boundary" : selectionChanged
                            ? "selection_callback_changed" : "advance_accepted_selection_unobserved",
                    "native_accepted=" + Boolean.TRUE.equals(moved) + ";selection_changed=" + selectionChanged
                            + ";result_scope=selected_card;playback_started=unobserved");
            safeLog("DouAi auto-slide advance direction=" + (targetDirection > 0 ? "UP" : "DOWN")
                    + "; smooth_scroll=false; transition_frame=" + (frame != null) + "; moved=" + moved);
        } catch (ReflectiveOperationException | RuntimeException failure) {
            if (frame != null && transitionFrame != frame) frame.close();
            terminal = true;
            operation.error("native_advance", failure);
            operation.finish(DouAiLog.Outcome.FAILED, "advance_failed", "rollback_requested=true");
            fail(failure);
        } finally {
            if (!terminal) operation.finish(DouAiLog.Outcome.SKIPPED, "validation_gate",
                    "reason=" + lastSkip + ";advance_invoked=false");
        }
    }

    private void releaseTransitionAfterPlayback(String aid) {
        PlaybackTransitionFrame.Overlay frame = transitionFrame;
        if (frame == null || aid == null || aid.equals(transitionSourceAid)) return;
        if (transitionRelease != null) main.removeCallbacks(transitionRelease);
        transitionRelease = () -> {
            if (transitionFrame == frame && aid.equals(selectedAid)) dismissTransitionFrame(true);
        };
        main.postDelayed(transitionRelease, 180L);
    }

    private void dismissTransitionFrame(boolean fade) {
        if (fadingTransitionFrame != null) fadingTransitionFrame.close();
        fadingTransitionFrame = null;
        if (transitionTimeout != null) main.removeCallbacks(transitionTimeout);
        if (transitionRelease != null) main.removeCallbacks(transitionRelease);
        transitionTimeout = null;
        transitionRelease = null;
        transitionSourceAid = null;
        transitionPlayingAid = null;
        PlaybackTransitionFrame.Overlay frame = transitionFrame;
        transitionFrame = null;
        if (frame == null) return;
        if (fade) {
            fadingTransitionFrame = frame;
            frame.fadeAndClose(180L);
        }
        else frame.close();
    }

    private Object panel() throws IllegalAccessException {
        Object holder = selectedHolder.get();
        Object params = holder == null ? null : paramsField.get(holder);
        return params == null ? null : feedContextField.get(params);
    }

    private String aid(Object holder) throws ReflectiveOperationException {
        if (holder == null) return null;
        Object aweme = awemeAccessor.invoke(holder);
        if (aweme == null) return null;
        Object value = aweme.getClass().getMethod("getAid").invoke(aweme);
        return value instanceof String ? (String) value : null;
    }

    private View activeRoot() {
        View root = selectedRoot.get();
        Activity foreground = resumed.get();
        return !closed && root != null && foreground != null && root.isAttachedToWindow() && root.isShown()
                && activity(root.getContext()) == foreground && !foreground.isFinishing()
                && !foreground.isDestroyed() ? root : null;
    }

    private static Activity activity(Context context) {
        while (context instanceof ContextWrapper && !(context instanceof Activity)) {
            Context base = ((ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    private void clearSelection() {
        backwardArmed = false; backwardFlow = null; backwardPager.clear();
        playbackFlow = null; playbackState = null;
        generation++;
        pendingAdvance = false;
        dismissTransitionFrame(false);
        PlaybackTransitionFrame.invalidateSurfaceCache(selectedRoot.get());
        selectedHolder.clear();
        selectedRoot.clear();
        selectedAid = null;
        paused = true;
    }

    private void fail(Throwable failure) {
        diagnostic = "自动滑动已回滚：" + failure.getClass().getSimpleName();
        group.fail(failure);
        onMain(this::clearSelection);
    }

    private void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run();
        else main.post(action);
    }

    private void safeLog(String message) {
        try { log.accept(message); } catch (Throwable ignored) { }
    }

    private void skipped(String reason, String message) {
        DouAiLog.event("auto_play", "decision", reason, "advance_invoked=false;generation=" + generation);
        diagnostic = message;
        if (!reason.equals(lastSkip)) {
            lastSkip = reason;
            safeLog("DouAi auto-slide completion skipped: " + reason);
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        generation++;
        group.close();
        application.unregisterActivityLifecycleCallbacks(this);
        onMain(() -> { main.removeCallbacksAndMessages(null); clearSelection(); });
    }

    @Override public void onActivityResumed(Activity activity) {
        if (pausedActivity.get() == activity) pausedActivity.clear();
        resumed = new WeakReference<>(activity);
    }
    @Override public void onActivityPaused(Activity activity) {
        pausedActivity = new WeakReference<>(activity);
        if (resumed.get() == activity) {
            resumed.clear(); generation++; pendingAdvance = false; touching = false; touchPager.clear();
            dismissTransitionFrame(false);
            PlaybackTransitionFrame.invalidateSurfaceCache(selectedRoot.get());
        }
    }
    @Override public void onActivityDestroyed(Activity activity) {
        View root = selectedRoot.get();
        if (root != null && activity(root.getContext()) == activity) clearSelection();
        if (resumed.get() == activity) resumed.clear();
        if (pausedActivity.get() == activity) pausedActivity.clear();
    }
    @Override public void onActivityCreated(Activity activity, Bundle state) { }
    @Override public void onActivityStarted(Activity activity) { }
    @Override public void onActivityStopped(Activity activity) { }
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
}
