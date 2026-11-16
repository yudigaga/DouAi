package com.douai.module.feature;

import android.os.Handler;
import android.os.Looper;
import com.douai.module.config.ConfigurationApplyAction;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class RetainedConfigurationActionTest {
    @Test public void unknownOnceFailureCannotBeErasedByANewActionForTheSameReceiver() {
        Handler main = new Handler(Looper.getMainLooper());
        RetainedConfigurationAction.Owner owner = new RetainedConfigurationAction.Owner();
        AtomicInteger effects = new AtomicInteger();
        RetainedConfigurationAction first = new RetainedConfigurationAction(main, () -> true, owner) {
            @Override void advance(Attempt attempt) {
                once(attempt, () -> { effects.incrementAndGet(); throw new IllegalStateException("unknown effect"); });
                attempt.success();
            }
        };
        CompletableFuture<Void> failed = first.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertPermanent(failed);
        RetainedConfigurationAction successor = new RetainedConfigurationAction(main, () -> true, owner) {
            @Override void advance(Attempt attempt) { effects.incrementAndGet(); attempt.success(); }
        };
        CompletableFuture<Void> blocked = successor.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertPermanent(blocked);
        assertEquals(1, effects.get());
        RetainedConfigurationAction replacement = new RetainedConfigurationAction(main, () -> true,
                new RetainedConfigurationAction.Owner()) {
            @Override void advance(Attempt attempt) { effects.incrementAndGet(); attempt.success(); }
        };
        CompletableFuture<Void> applied = replacement.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(applied.isDone());
        assertFalse(applied.isCompletedExceptionally());
        assertEquals(2, effects.get());
    }

    @Test public void queuedSuccessorRechecksAnUnknownFailureReportedAfterItWasCreated() {
        Handler main = new Handler(Looper.getMainLooper());
        RetainedConfigurationAction.Owner owner = new RetainedConfigurationAction.Owner();
        AtomicInteger successorEffects = new AtomicInteger();
        RetainedConfigurationAction successor = new RetainedConfigurationAction(main, () -> true, owner) {
            @Override void advance(Attempt attempt) { successorEffects.incrementAndGet(); attempt.success(); }
        };
        java.util.concurrent.atomic.AtomicReference<CompletableFuture<Void>> queued = new java.util.concurrent.atomic.AtomicReference<>();
        RetainedConfigurationAction first = new RetainedConfigurationAction(main, () -> true, owner) {
            @Override void advance(Attempt attempt) {
                once(attempt, () -> {
                    queued.set(successor.apply(() -> true).toCompletableFuture());
                    throw new IllegalStateException("old mutation reports its unknown effect late");
                });
                attempt.success();
            }
        };
        CompletableFuture<Void> old = first.apply(() -> true).toCompletableFuture();
        ShadowLooper.idleMainLooper();
        assertTrue(old.isCancelled());
        assertPermanent(queued.get());
        assertEquals(0, successorEffects.get());
    }

    private static void assertPermanent(CompletableFuture<Void> future) {
        assertTrue(future.isCompletedExceptionally());
        try { future.join(); fail("Unknown effects must stay explicitly failed"); }
        catch (CompletionException failure) { assertTrue(failure.getCause() instanceof ConfigurationApplyAction.PermanentFailure); }
    }
}
