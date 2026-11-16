package com.douai.module.feature;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Looper;
import android.os.Handler;
import android.os.Message;
import android.view.View;
import com.douai.module.config.ConfigurationApplyAction;
import com.douai.module.config.LocalOptions;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

/** Real feature reconciliation tails, with failures at the Android mutation boundary. NOT-RUN. */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
@LooperMode(LooperMode.Mode.PAUSED)
public final class ConfigurationReconciliationActionTest {
    private final Object manager = new Object();
    @Test public void recallRejectedOrThrowingPostIsExceptionalBeforeAnyMutation() throws Exception {
        for (boolean throwsOnPost : new boolean[]{false, true}) {
            Context context = RuntimeEnvironment.getApplication();
            LocalOptions before = new LocalOptions(Map.of("prevent_message_recall", true));
            RecallHighlightHook hook = construct(RecallHighlightHook.class,
                    new Class<?>[]{Context.class, LocalOptions.class, Consumer.class},
                    context, before, (Consumer<String>) ignored -> { });
            Handler original = field(hook, "main");
            put(hook, "main", new Handler(Looper.getMainLooper()) {
                @Override public boolean sendMessageAtTime(Message message, long uptime) {
                    if (throwsOnPost) throw new IllegalStateException("post failed");
                    return false;
                }
            });
            CompletableFuture<Void> failed = hook.prepareOptions(new LocalOptions(Map.of()))
                    .apply(() -> true).toCompletableFuture();
            assertTrue(failed.isCompletedExceptionally());
            assertSame(before, field(hook, "options"));
            put(hook, "main", original);
            hook.close();
        }
    }

    @Test public void commentAiPreparedActionIsPureAndAcknowledgesItsCapturedCleanupOnce() throws Exception {
        LocalOptions before = new LocalOptions(Map.of());
        CommentAiAnalysisHook hook = commentAi(before);
        ConfigurationApplyAction action = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.SEARCH, true)));
        assertSame(before, field(hook, "options"));
        java.util.concurrent.atomic.AtomicLong epoch = field(hook, "optionsEpoch");
        CompletableFuture<Void> applied = action.apply(() -> true).toCompletableFuture();
        assertFalse(applied.isDone());
        ShadowLooper.idleMainLooper();
        assertTrue(applied.isDone()); assertFalse(applied.isCompletedExceptionally());
        assertEquals(1L, epoch.get());
        action.apply(() -> true); ShadowLooper.idleMainLooper();
        assertEquals(1L, epoch.get());
        hook.close();
    }

    @Test public void commentAiUnknownCancellationFailureIsPermanentAndCannotBeReplayed() throws Exception {
        CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
        Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
        Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
        Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
        Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                CommentAiNativeStream.class, String.class, int.class, long.class},
                manager, owner, 0L, null, "conversation", 1, 0L);
        put(owner, "round", session);
        Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
        ConfigurationApplyAction action = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.SEARCH, true)));
        CompletableFuture<Void> failed = action.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        try { failed.join(); fail("Unknown cancellation effect must remain failed"); }
        catch (java.util.concurrent.CompletionException expected) {
            assertTrue(expected.getCause() instanceof ConfigurationApplyAction.PermanentFailure);
        }
        long generation = ((java.util.concurrent.atomic.AtomicLong) field(owner, "generation")).get();
        CompletableFuture<Void> retry = action.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(retry.isCompletedExceptionally());
        assertEquals(generation, ((java.util.concurrent.atomic.AtomicLong) field(owner, "generation")).get());
        hook.close();
    }

    @Test public void commentAiAlreadyCancelledSessionDoesNotCancelItsWorkAgain() throws Exception {
        CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
        Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
        Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
        Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
        Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                CommentAiNativeStream.class, String.class, int.class, long.class},
                manager, owner, 0L, new CommentAiNativeStream("conversation", 1, "", "card"), "conversation", 1, 0L);
        put(session, "cancelled", true);
        java.util.concurrent.atomic.AtomicInteger cancellations = new java.util.concurrent.atomic.AtomicInteger();
        put(session, "work", new java.util.concurrent.FutureTask<Void>(() -> null) {
            @Override public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
        });
        put(owner, "round", session);
        Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
        CompletableFuture<Void> applied = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.SEARCH, true))).apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(applied.isDone()); assertFalse(applied.isCompletedExceptionally());
        assertEquals(0, cancellations.get());
        hook.close();
    }

    @Test public void commentAiDetachedCleanupSurvivesASuccessorOrCloseWithoutRepeatingStages() throws Exception {
        for (boolean closeInsteadOfSuccessor : new boolean[]{false, true}) {
            CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
            Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
            Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
            Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
            Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                    CommentAiNativeStream.class, String.class, int.class, long.class}, manager, owner, 0L,
                    new CommentAiNativeStream("conversation", 1, "", "card"), "conversation", 1, 0L);
            java.util.concurrent.atomic.AtomicInteger cancellations = new java.util.concurrent.atomic.AtomicInteger();
            java.util.concurrent.FutureTask<Void> work = new java.util.concurrent.FutureTask<>(() -> null) {
                @Override public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
            };
            put(session, "work", work);
            put(owner, "round", session);
            Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
            java.util.concurrent.atomic.AtomicLong generation = field(owner, "generation");
            CompletableFuture<Void> old = hook.prepareOptions(new LocalOptions(Map.of(
                    com.douai.module.config.CommentAiSettings.SEARCH, true)))
                    .apply(() -> generation.get() == 0L).toCompletableFuture();
            ShadowLooper.idleMainLooper();
            assertTrue(old.isCancelled());
            assertEquals(1L, generation.get());
            assertFalse(work.isCancelled());
            if (closeInsteadOfSuccessor) {
                hook.close();
            } else {
                CompletableFuture<Void> latest = hook.prepareOptions(new LocalOptions(Map.of()))
                        .apply(() -> true).toCompletableFuture();
                ShadowLooper.idleMainLooper();
                assertTrue(latest.isDone()); assertFalse(latest.isCompletedExceptionally());
                hook.close();
            }
            ShadowLooper.idleMainLooper();
            assertTrue(work.isCancelled());
            assertEquals(true, field(session, "cancelled"));
            assertEquals(1, cancellations.get());
            assertEquals("The captured owner was already detached by the old attempt", 1L, generation.get());
        }
    }

    @Test public void commentAiSuccessorCannotReplayAnUnknownCapturedCancellation() throws Exception {
        CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
        Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
        Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
        Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
        Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                CommentAiNativeStream.class, String.class, int.class, long.class},
                manager, owner, 0L, null, "conversation", 1, 0L);
        put(owner, "round", session);
        Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
        CompletableFuture<Void> old = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.SEARCH, true))).apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(old.isCompletedExceptionally());
        long generation = ((java.util.concurrent.atomic.AtomicLong) field(owner, "generation")).get();
        CompletableFuture<Void> latest = hook.prepareOptions(new LocalOptions(Map.of()))
                .apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        try { latest.join(); fail("A new revision must keep the captured uncertain effect failed"); }
        catch (java.util.concurrent.CompletionException expected) {
            assertTrue(expected.getCause() instanceof ConfigurationApplyAction.PermanentFailure);
        }
        assertEquals(generation, ((java.util.concurrent.atomic.AtomicLong) field(owner, "generation")).get());
        hook.close();
    }

    @Test public void commentAiCompletedFutureCancellationIsNotReplayedByASuccessor() throws Exception {
        CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
        Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
        Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
        Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
        Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                CommentAiNativeStream.class, String.class, int.class, long.class}, manager, owner, 0L,
                new CommentAiNativeStream("conversation", 1, "", "card"), "conversation", 1, 0L);
        put(owner, "round", session);
        Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
        AtomicBoolean current = new AtomicBoolean(true);
        java.util.concurrent.atomic.AtomicInteger cancellations = new java.util.concurrent.atomic.AtomicInteger();
        put(session, "work", new java.util.concurrent.FutureTask<Void>(() -> null) {
            @Override public boolean cancel(boolean interrupt) {
                cancellations.incrementAndGet(); current.set(false); return super.cancel(interrupt);
            }
        });
        put(session, "pageWork", new java.util.concurrent.FutureTask<Void>(() -> null) {
            @Override public boolean cancel(boolean interrupt) { cancellations.incrementAndGet(); return super.cancel(interrupt); }
        });
        CompletableFuture<Void> old = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.SEARCH, true))).apply(current::get).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(old.isCancelled());
        assertEquals(2, cancellations.get());
        CompletableFuture<Void> latest = hook.prepareOptions(new LocalOptions(Map.of()))
                .apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(latest.isDone()); assertFalse(latest.isCompletedExceptionally());
        hook.close(); ShadowLooper.idleMainLooper();
        assertEquals(2, cancellations.get());
    }

    @Test public void commentAiSuccessorRetainsTheUnreachedPrefetchEligibilityStage() throws Exception {
        CommentAiAnalysisHook hook = commentAi(new LocalOptions(Map.of()));
        java.util.concurrent.atomic.AtomicInteger readinessChecks = new java.util.concurrent.atomic.AtomicInteger();
        put(hook, "modelSettings", (java.util.function.Supplier<com.douai.module.config.AiModelSettings>) () -> {
            readinessChecks.incrementAndGet(); return null;
        });
        Class<?> ownerType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Conversation");
        Object owner = construct(ownerType, new Class<?>[]{String.class, String.class}, "source", "model");
        Class<?> sessionType = Class.forName(CommentAiAnalysisHook.class.getName() + "$Session");
        Object session = construct(sessionType, new Class<?>[]{Object.class, ownerType, long.class,
                CommentAiNativeStream.class, String.class, int.class, long.class}, manager, owner, 0L,
                new CommentAiNativeStream("conversation", 1, "", "card"), "conversation", 1, 0L);
        put(owner, "round", session);
        Map<Object, Object> owners = field(hook, "conversations"); owners.put(manager, owner);
        java.util.concurrent.atomic.AtomicLong generation = field(owner, "generation");
        CompletableFuture<Void> old = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.TAKEOVER, true)))
                .apply(() -> generation.get() == 0L).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(old.isCancelled()); assertEquals(0, readinessChecks.get());
        // Explicit false changes the raw relevant revision while preserving the semantic default.
        CompletableFuture<Void> latest = hook.prepareOptions(new LocalOptions(Map.of(
                com.douai.module.config.CommentAiSettings.TAKEOVER, true,
                com.douai.module.config.CommentAiSettings.SEARCH, false)))
                .apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(latest.isDone()); assertFalse(latest.isCompletedExceptionally());
        assertEquals(1, readinessChecks.get());
        hook.close();
    }

    private static CommentAiAnalysisHook commentAi(LocalOptions options) throws Exception {
        Class<?> bindings = Class.forName(CommentAiAnalysisHook.class.getName() + "$Bindings");
        return construct(CommentAiAnalysisHook.class, new Class<?>[]{Context.class, bindings,
                LocalOptions.class, java.util.function.Supplier.class, DouyinSearch.class, Consumer.class},
                RuntimeEnvironment.getApplication(), null, options,
                (java.util.function.Supplier<com.douai.module.config.AiModelSettings>) () -> null,
                null, (Consumer<String>) ignored -> { });
    }

    @Test public void recallRetryKeepsTheBackgroundUntilRestoreActuallyReturns() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        RecallHighlightHook hook = construct(RecallHighlightHook.class,
                new Class<?>[]{Context.class, LocalOptions.class, Consumer.class},
                context, new LocalOptions(Map.of("prevent_message_recall", true)), (Consumer<String>) ignored -> { });
        FailingBackground view = new FailingBackground(context);
        Drawable original = new ColorDrawable(0xff123456);
        Map<View, Drawable> originals = field(hook, "originals");
        originals.put(view, original);
        ConfigurationApplyAction action = hook.prepareOptions(new LocalOptions(Map.of()));
        CompletableFuture<Void> first = action.apply(() -> true).toCompletableFuture();
        assertFalse(first.isDone());
        ShadowLooper.idleMainLooper();
        assertTrue(first.isCompletedExceptionally());
        assertSame(original, originals.get(view));
        CompletableFuture<Void> retry = action.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertFalse(retry.isCompletedExceptionally());
        assertTrue(retry.isDone());
        assertSame(original, view.getBackground());
        assertTrue(originals.isEmpty());
        hook.close();
    }

    @Test public void recallCancelledAttemptRemovesItsQueuedMutation() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        LocalOptions initial = new LocalOptions(Map.of("prevent_message_recall", true));
        RecallHighlightHook hook = construct(RecallHighlightHook.class,
                new Class<?>[]{Context.class, LocalOptions.class, Consumer.class},
                context, initial, (Consumer<String>) ignored -> { });
        ConfigurationApplyAction action = hook.prepareOptions(new LocalOptions(Map.of()));
        CompletableFuture<Void> attempt = action.apply(() -> true).toCompletableFuture();
        assertSame(initial, field(hook, "options"));
        attempt.cancel(false);
        ShadowLooper.idleMainLooper();
        assertSame(initial, field(hook, "options"));
        hook.close();
    }

    @Test public void loadedSearchDismissFailureRetainsItsDialogForTheSameActionRetry() throws Exception {
        LoadedCommentSearchHook hook = searchHook();
        hook.setOptions(true, false);
        FailingDialog dialog = new FailingDialog(RuntimeEnvironment.getApplication());
        put(hook, "dialog", dialog);
        ConfigurationApplyAction action = hook.prepareOptions(false, false);
        CompletableFuture<Void> first = action.apply(() -> true).toCompletableFuture();
        assertFalse(first.isDone());
        ShadowLooper.idleMainLooper();
        assertTrue(first.isCompletedExceptionally());
        CompletableFuture<Void> retry = action.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(retry.isDone());
        assertFalse(retry.isCompletedExceptionally());
        assertEquals(2, dialog.dismissals);
        assertNull(field(hook, "dialog"));
        hook.close();
    }

    @Test public void loadedSearchStaleOrClosedActionCannotChangeCurrentOptions() throws Exception {
        LoadedCommentSearchHook hook = searchHook();
        AtomicBoolean current = new AtomicBoolean(true);
        CompletableFuture<Void> stale = hook.prepareOptions(true, true).apply(current::get).toCompletableFuture();
        current.set(false);
        ShadowLooper.idleMainLooper();
        assertTrue(stale.isCompletedExceptionally());
        assertEquals(false, field(hook, "enabled"));
        CompletableFuture<Void> closed = hook.prepareOptions(true, true).apply(() -> true).toCompletableFuture();
        hook.close();
        ShadowLooper.idleMainLooper();
        assertTrue(closed.isCompletedExceptionally());
        assertEquals(false, field(hook, "enabled"));
    }

    private static LoadedCommentSearchHook searchHook() throws Exception {
        LoadedCommentSearchHook.Types types = name -> {
            if (name.endsWith("model.Comment")) return Comment.class;
            if (name.equals("X.13Tl")) return Extractor.class;
            if (name.endsWith("AccountProxyService")) return Account.class;
            throw new ClassNotFoundException(name);
        };
        return construct(LoadedCommentSearchHook.class, new Class<?>[]{LoadedCommentSearchHook.Types.class}, types);
    }
    public static final class Comment { public String cid, awemeId, replyId, rootCommentId, text; }
    public static final class Extractor { public Comment getComment() { return null; } }
    public static final class Account { public static Account userService() { return new Account(); } public String getCurUserId() { return "100"; } }
    private static final class FailingBackground extends View {
        boolean fail = true;
        FailingBackground(Context context) { super(context); }
        @Override public void setBackground(Drawable background) {
            if (fail) { fail = false; throw new IllegalStateException("restore failed"); }
            super.setBackground(background);
        }
    }
    private static final class FailingDialog extends AlertDialog {
        int dismissals;
        FailingDialog(Context context) { super(context); }
        @Override public void dismiss() { if (++dismissals == 1) throw new IllegalStateException("dismiss failed"); super.dismiss(); }
    }
    static <T> T construct(Class<T> type, Class<?>[] parameters, Object... args) throws Exception {
        Constructor<T> constructor = type.getDeclaredConstructor(parameters); constructor.setAccessible(true); return constructor.newInstance(args);
    }
    @SuppressWarnings("unchecked") static <T> T field(Object owner, String name) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); return (T) field.get(owner);
    }
    static void put(Object owner, String name, Object value) throws Exception {
        Field field = owner.getClass().getDeclaredField(name); field.setAccessible(true); field.set(owner, value);
    }
}
