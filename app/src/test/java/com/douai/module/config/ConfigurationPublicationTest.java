package com.douai.module.config;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

import static org.junit.Assert.*;

/** Contract tests use virtual time; no Android scheduler or business operation is involved. */
public final class ConfigurationPublicationTest {
    @Test public void failureAtFirstMiddleOrLastDoesNotSkipIndependentConsumers() {
        for (int failed = 0; failed < 3; failed++) {
            ManualScheduler scheduler = new ManualScheduler();
            ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
            int[] calls = new int[3];
            List<ConfigurationPublication.Binding<String>> bindings = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                final int position = index;
                final int failedPosition = failed;
                bindings.add(binding("node" + index, new Object(), true, payload -> current -> {
                    if (++calls[position] == 1 && position == failedPosition) {
                        throw new IllegalStateException("private failure detail");
                    }
                    return done();
                }));
            }
            publication.publish(values("first"), "first", bindings);
            assertArrayEquals(new int[]{1, 1, 1}, calls);
            assertEquals(ConfigurationPublication.Summary.PARTIAL, publication.snapshot().summary);
            publication.publish(values("first"), "same values, different payload", bindings);
            assertEquals(2, calls[failed]);
            for (int index = 0; index < 3; index++) {
                if (index != failed) assertEquals(1, calls[index]);
            }
            assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
            scheduler.advance(10000);
            assertEquals(2, calls[failed]);
        }
    }

    @Test public void retainedActionResumesTailWithoutRepeatingCompletedInvalidation() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger invalidations = new AtomicInteger();
        AtomicInteger tailCalls = new AtomicInteger();
        ConfigurationPublication.Binding<String> node = binding("retained", new Object(), true, payload -> {
            factories.incrementAndGet();
            return new ConfigurationApplyAction() {
                boolean accepted;
                @Override public CompletionStage<Void> apply(BooleanSupplier current) {
                    if (!accepted) {
                        invalidations.incrementAndGet();
                        accepted = true;
                    }
                    if (tailCalls.incrementAndGet() == 1) throw new IllegalArgumentException("tail");
                    return done();
                }
            };
        });
        publication.publish(values("a"), "a", Collections.singletonList(node));
        publication.retry();
        assertEquals(1, factories.get());
        assertEquals(1, invalidations.get());
        assertEquals(2, tailCalls.get());
        assertEquals(1, publication.snapshot().nodes.get("retained").appliedRevision);
    }

    @Test public void actionIsNotAcknowledgedUntilActualContinuationCompletes() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        CompletableFuture<Void> completion = new CompletableFuture<>();
        publication.publish(values("a"), "a", Collections.singletonList(
                binding("queued", new Object(), true, payload -> current -> completion)));
        assertEquals(ConfigurationPublication.Status.APPLYING,
                publication.snapshot().nodes.get("queued").status);
        assertEquals(0, publication.snapshot().nodes.get("queued").appliedRevision);
        publication.retry();
        assertEquals(1, publication.snapshot().nodes.get("queued").attempts);
        completion.complete(null);
        assertEquals(1, publication.snapshot().nodes.get("queued").appliedRevision);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
        assertEquals(0, scheduler.pending());
    }

    @Test public void newRelevantRevisionInvalidatesQueuedWorkAndIgnoresLateAcknowledgement() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        Object receiver = new Object();
        List<BooleanSupplier> guards = new ArrayList<>();
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        ConfigurationPublication.Binding<String> node = binding("queued", receiver, true, payload -> current -> {
            guards.add(current);
            CompletableFuture<Void> result = new UncancellableFuture();
            completions.add(result);
            return result;
        });
        publication.publish(values("a"), "a", Collections.singletonList(node));
        publication.publish(values("b"), "b", Collections.singletonList(node));
        assertFalse(guards.get(0).getAsBoolean());
        assertTrue(guards.get(1).getAsBoolean());
        completions.get(0).complete(null);
        assertEquals(0, publication.snapshot().nodes.get("queued").appliedRevision);
        completions.get(1).complete(null);
        assertEquals(2, publication.snapshot().nodes.get("queued").appliedRevision);
        scheduler.runAllIncludingCancelled();
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void supersededIncompleteActionUsesLatestPayloadAndCancelsOldFuture() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        Object receiver = new Object();
        CompletableFuture<Void> old = new CompletableFuture<>();
        List<String> accepted = new ArrayList<>();
        Function<String, ConfigurationApplyAction> factory = payload -> current -> {
            accepted.add(payload);
            return "old".equals(payload) ? old : done();
        };
        publication.publish(values("old"), "old", Collections.singletonList(binding("node", receiver, true, factory)));
        publication.publish(values("new"), "new", Collections.singletonList(binding("node", receiver, true, factory)));
        assertTrue(old.isCancelled());
        assertEquals(Arrays.asList("old", "new"), accepted);
        assertEquals(2, publication.snapshot().nodes.get("node").appliedRevision);
    }

    @Test public void initialUnchangedAndDisableEnableDeliverOnlyNeededPayloads() {
        ConfigurationPublication<Boolean> publication = new ConfigurationPublication<>(new ManualScheduler());
        Object receiver = new Object();
        List<Boolean> accepted = new ArrayList<>();
        Function<Boolean, ConfigurationApplyAction> factory = payload -> current -> {
            accepted.add(payload);
            return done();
        };
        assertEquals(0, publication.snapshot().revision);
        publication.publish(Collections.singletonMap("enabled", true), true,
                Collections.singletonList(binding("node", receiver, true, factory)));
        publication.publish(Collections.singletonMap("enabled", true), false,
                Collections.singletonList(binding("node", receiver, true, factory)));
        assertEquals(1, publication.snapshot().revision);
        publication.publish(Collections.singletonMap("enabled", false), false,
                Collections.singletonList(binding("node", receiver, true, factory)));
        publication.publish(Collections.singletonMap("enabled", true), true,
                Collections.singletonList(binding("node", receiver, true, factory)));
        assertEquals(Arrays.asList(true, false, true), accepted);
        assertEquals(3, publication.snapshot().revision);
    }

    @Test public void unrelatedChangesDoNotReplaySuccessfulConsumerOrResetFailedBudget() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        Object successReceiver = new Object();
        Object failedReceiver = new Object();
        AtomicInteger successfulCalls = new AtomicInteger();
        AtomicInteger failedFactories = new AtomicInteger();
        Function<String, ConfigurationApplyAction> success = payload -> current -> {
            successfulCalls.incrementAndGet();
            return done();
        };
        Function<String, ConfigurationApplyAction> failure = payload -> {
            failedFactories.incrementAndGet();
            return current -> failed(new IllegalStateException("tail"));
        };
        publication.publish(values("a"), "a", Arrays.asList(
                binding("good", successReceiver, true, success), binding("bad", failedReceiver, true, failure)));
        publication.publish(values("unrelated"), "unrelated", Arrays.asList(
                binding("good", successReceiver, false, success), binding("bad", failedReceiver, false, failure)));
        assertEquals(1, successfulCalls.get());
        assertEquals(1, failedFactories.get());
        assertEquals(2, publication.snapshot().nodes.get("bad").attempts);
        publication.retry();
        assertEquals(3, publication.snapshot().nodes.get("bad").attempts);
        scheduler.advance(10000);
        publication.retry();
        assertEquals(3, publication.snapshot().nodes.get("bad").attempts);
        assertEquals(1, successfulCalls.get());
    }

    @Test public void dependenciesWaitForRealAcknowledgementAndLeaveIndependentTailFree() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        CompletableFuture<Void> dispatcher = new CompletableFuture<>();
        List<String> applied = new ArrayList<>();
        publication.publish(values("a"), "a", Arrays.asList(
                new ConfigurationPublication.Binding<>("reply", new Object(), true,
                        Collections.singletonList("dispatcher"), payload -> current -> {
                    applied.add("reply");
                    return done();
                }),
                binding("dispatcher", new Object(), true, payload -> current -> dispatcher),
                binding("tail", new Object(), true, payload -> current -> {
                    applied.add("tail");
                    return done();
                })));
        assertEquals(Collections.singletonList("tail"), applied);
        assertEquals(ConfigurationPublication.Status.WAITING_DEPENDENCY,
                publication.snapshot().nodes.get("reply").status);
        dispatcher.complete(null);
        assertEquals(Arrays.asList("tail", "reply"), applied);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void failedDependencyKeepsOnlyDependentsPendingUntilRecovery() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        AtomicInteger parentCalls = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), true, payload -> current ->
                        parentCalls.incrementAndGet() == 1 ? failed(new IllegalStateException()) : done()),
                new ConfigurationPublication.Binding<>("child", new Object(), true,
                        Collections.singletonList("parent"), payload -> current -> {
                    childCalls.incrementAndGet();
                    return done();
                })));
        assertEquals(0, childCalls.get());
        publication.retry();
        assertEquals(1, childCalls.get());
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void reservedStartCannotPassReplacementDependencyBeforeItsAcknowledgement() throws Exception {
        PausingCancelScheduler scheduler = new PausingCancelScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        CompletableFuture<Void> replacementParent = new CompletableFuture<>();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> {
            factories.incrementAndGet();
            return current -> childCalls.incrementAndGet() == 1
                    ? failed(new IllegalStateException("retry tail")) : done();
        });
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), false, payload -> current -> done()), child));
        scheduler.pauseNextCancellation = true;
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread retryThread = new Thread(() -> {
            try { publication.retry(); }
            catch (Throwable failure) { workerFailure.set(failure); }
        }, "configuration-retry-test");
        retryThread.setDaemon(true);
        retryThread.start();
        try {
            assertTrue("retry must reserve its next attempt", scheduler.cancelEntered.await(5, TimeUnit.SECONDS));
            assertEquals(2, publication.snapshot().nodes.get("child").attempts);
            publication.publish(values("a"), "a", Arrays.asList(
                    binding("parent", new Object(), false, payload -> current -> replacementParent), child));
            assertEquals(ConfigurationPublication.Status.WAITING_DEPENDENCY,
                    publication.snapshot().nodes.get("child").status);
            scheduler.cancelReleased.countDown();
            retryThread.join(5000);
            assertFalse("retry thread must finish", retryThread.isAlive());
            assertNull(workerFailure.get());
            assertNull(scheduler.failure.get());
            assertEquals("the old reserved start must not call the action", 1, childCalls.get());
            replacementParent.complete(null);
            assertEquals(2, childCalls.get());
            assertEquals(1, factories.get());
            assertEquals(3, publication.snapshot().nodes.get("child").attempts);
            assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
        } finally {
            scheduler.cancelReleased.countDown();
            retryThread.join(5000);
        }
    }

    @Test public void replacementDependencyInvalidatesChildGuardAndRejectsLateAcknowledgement() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger factories = new AtomicInteger();
        List<BooleanSupplier> guards = new ArrayList<>();
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> {
            factories.incrementAndGet();
            return current -> {
                guards.add(current);
                CompletableFuture<Void> completion = new UncancellableFuture();
                completions.add(completion);
                return completion;
            };
        });
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), false, payload -> current -> done()), child));
        Runnable oldDeadline = scheduler.tasks.get(scheduler.tasks.size() - 1).runnable;
        CompletableFuture<Void> replacementParent = new CompletableFuture<>();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), false, payload -> current -> replacementParent), child));
        assertFalse(guards.get(0).getAsBoolean());
        assertEquals(ConfigurationPublication.Status.WAITING_DEPENDENCY,
                publication.snapshot().nodes.get("child").status);
        replacementParent.complete(null);
        completions.get(0).complete(null);
        oldDeadline.run();
        assertTrue(guards.get(1).getAsBoolean());
        assertEquals(ConfigurationPublication.Status.APPLYING, publication.snapshot().nodes.get("child").status);
        assertEquals(0, publication.snapshot().nodes.get("child").appliedRevision);
        completions.get(1).complete(null);
        assertEquals(1, factories.get());
        assertEquals(2, publication.snapshot().nodes.get("child").attempts);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void repeatedParentReplacementCannotResetOrReviveExhaustedChildBudget() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> {
            factories.incrementAndGet();
            return current -> {
                childCalls.incrementAndGet();
                CompletableFuture<Void> completion = new UncancellableFuture();
                completions.add(completion);
                return completion;
            };
        });
        Object parentReceiver = new Object();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", parentReceiver, false, payload -> current -> done()), child));
        for (int replacement = 0; replacement < 3; replacement++) {
            parentReceiver = new Object();
            CompletableFuture<Void> parentCompletion = new CompletableFuture<>();
            publication.publish(values("a"), "a", Arrays.asList(
                    binding("parent", parentReceiver, false, payload -> current -> parentCompletion), child));
            ConfigurationPublication.NodeSnapshot waiting = publication.snapshot().nodes.get("child");
            assertEquals(replacement + 1, waiting.attempts);
            assertEquals(1, waiting.desiredRevision);
            if (replacement < 2) {
                assertEquals(ConfigurationPublication.Status.WAITING_DEPENDENCY, waiting.status);
            } else {
                assertEquals(ConfigurationPublication.Status.FAILED, waiting.status);
                assertEquals(ConfigurationPublication.Reason.DEPENDENCY_INVALIDATED, waiting.reason);
            }
            parentCompletion.complete(null);
        }
        publication.retry();
        publication.publish(values("unrelated"), "unrelated", Arrays.asList(
                binding("parent", parentReceiver, false, payload -> current -> done()), child));
        for (CompletableFuture<Void> completion : completions) completion.complete(null);
        scheduler.advance(10000);
        assertEquals(1, factories.get());
        assertEquals(3, childCalls.get());
        assertEquals(3, publication.snapshot().nodes.get("child").attempts);
        assertEquals(0, publication.snapshot().nodes.get("child").appliedRevision);
        assertEquals(ConfigurationPublication.Status.FAILED, publication.snapshot().nodes.get("child").status);
        assertEquals(ConfigurationPublication.Reason.DEPENDENCY_INVALIDATED,
                publication.snapshot().nodes.get("child").reason);
        assertEquals(0, scheduler.pending());
    }

    @Test public void appliedUnaffectedChildIsNotReplayedWhenParentReceiverChanges() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        AtomicInteger childCalls = new AtomicInteger();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> current -> {
            childCalls.incrementAndGet();
            return done();
        });
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), false, payload -> current -> done()), child));
        CompletableFuture<Void> replacementParent = new CompletableFuture<>();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("parent", new Object(), false, payload -> current -> replacementParent), child));
        assertEquals(ConfigurationPublication.Status.APPLIED, publication.snapshot().nodes.get("child").status);
        replacementParent.complete(null);
        assertEquals(1, childCalls.get());
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void preparingFactorySurvivesDependencyReplacementWithoutConcurrentRecreation() throws Exception {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryReleased = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> {
            if (factories.incrementAndGet() == 1) {
                factoryEntered.countDown();
                try {
                    if (!factoryReleased.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Preparing factory was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return current -> {
                childCalls.incrementAndGet();
                return done();
            };
        });
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread publishThread = new Thread(() -> {
            try {
                publication.publish(values("a"), "a", Arrays.asList(
                        binding("parent", new Object(), false, payload -> current -> done()), child));
            } catch (Throwable failure) { workerFailure.set(failure); }
        }, "configuration-factory-test");
        publishThread.setDaemon(true);
        publishThread.start();
        try {
            assertTrue("first factory must be preparing", factoryEntered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> replacementParent = new CompletableFuture<>();
            publication.publish(values("a"), "a", Arrays.asList(
                    binding("parent", new Object(), false, payload -> current -> replacementParent), child));
            replacementParent.complete(null);
            assertEquals("the retained entry must have only one preparing factory", 1, factories.get());
            assertEquals(0, childCalls.get());
            assertEquals(2, publication.snapshot().nodes.get("child").attempts);
            factoryReleased.countDown();
            publishThread.join(5000);
            assertFalse("publish thread must finish", publishThread.isAlive());
            assertNull(workerFailure.get());
            assertEquals(1, factories.get());
            assertEquals(1, childCalls.get());
            assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
        } finally {
            factoryReleased.countDown();
            publishThread.join(5000);
        }
    }

    @Test public void preparedActionStartsReservedAttemptOnlyOnceWhenScheduledStartArrivesLater() throws Exception {
        PausingCancelScheduler scheduler = new PausingCancelScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch factoryReleased = new CountDownLatch(1);
        AtomicInteger factories = new AtomicInteger();
        AtomicInteger childCalls = new AtomicInteger();
        CompletableFuture<Void> childCompletion = new CompletableFuture<>();
        ConfigurationPublication.Binding<String> child = new ConfigurationPublication.Binding<>(
                "child", new Object(), false, Collections.singletonList("parent"), payload -> {
            if (factories.incrementAndGet() == 1) {
                factoryEntered.countDown();
                try {
                    if (!factoryReleased.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Preparing factory was not released");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }
            return current -> {
                childCalls.incrementAndGet();
                return childCompletion;
            };
        });
        AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        Thread publishThread = new Thread(() -> {
            try {
                publication.publish(values("a"), "a", Arrays.asList(
                        binding("parent", new Object(), false, payload -> current -> done()), child));
            } catch (Throwable failure) { workerFailure.compareAndSet(null, failure); }
        }, "configuration-preparing-test");
        CompletableFuture<Void> replacementParent = new CompletableFuture<>();
        Thread acknowledgeThread = new Thread(() -> {
            try { replacementParent.complete(null); }
            catch (Throwable failure) { workerFailure.compareAndSet(null, failure); }
        }, "configuration-parent-ack-test");
        publishThread.setDaemon(true);
        acknowledgeThread.setDaemon(true);
        publishThread.start();
        try {
            assertTrue(factoryEntered.await(5, TimeUnit.SECONDS));
            publication.publish(values("a"), "a", Arrays.asList(
                    binding("parent", new Object(), false, payload -> current -> replacementParent), child));
            scheduler.pauseDelayMillis = 5000;
            scheduler.pauseNextCancellation = true;
            acknowledgeThread.start();
            assertTrue("parent ACK must reserve the child attempt", scheduler.cancelEntered.await(5, TimeUnit.SECONDS));
            factoryReleased.countDown();
            publishThread.join(5000);
            assertFalse(publishThread.isAlive());
            assertEquals(1, childCalls.get());
            scheduler.cancelReleased.countDown();
            acknowledgeThread.join(5000);
            assertFalse(acknowledgeThread.isAlive());
            assertNull(workerFailure.get());
            assertNull(scheduler.failure.get());
            assertEquals("one attempt must execute its action only once", 1, childCalls.get());
            assertEquals(1, factories.get());
            assertEquals(2, publication.snapshot().nodes.get("child").attempts);
            childCompletion.complete(null);
            assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
        } finally {
            factoryReleased.countDown();
            scheduler.cancelReleased.countDown();
            publishThread.join(5000);
            if (acknowledgeThread.getState() != Thread.State.NEW) acknowledgeThread.join(5000);
        }
    }

    @Test public void unavailableReceiverHasNoTimerAndBecomesReadyOnSameValues() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger calls = new AtomicInteger();
        Function<String, ConfigurationApplyAction> factory = payload -> current -> {
            calls.incrementAndGet();
            return done();
        };
        publication.publish(values("a"), "a", Collections.singletonList(binding("node", null, true, factory)));
        assertEquals(ConfigurationPublication.Summary.PENDING, publication.snapshot().summary);
        assertEquals(ConfigurationPublication.Status.WAITING_NOT_INSTALLED,
                publication.snapshot().nodes.get("node").status);
        assertEquals(0, scheduler.pending());
        publication.retry();
        assertEquals(0, calls.get());
        publication.publish(values("a"), "a", Collections.singletonList(binding("node", new Object(), false, factory)));
        assertEquals(1, calls.get());
        assertEquals(1, publication.snapshot().revision);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void receiverReplacementUsesIdentityAndRejectsOldCallbacks() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        Object first = new EqualReceiver();
        Object second = new EqualReceiver();
        CompletableFuture<Void> old = new UncancellableFuture();
        AtomicInteger calls = new AtomicInteger();
        publication.publish(values("a"), "a", Collections.singletonList(binding("node", first, true, payload -> current -> old)));
        publication.publish(values("a"), "a", Collections.singletonList(binding("node", second, false, payload -> current -> {
            calls.incrementAndGet();
            return done();
        })));
        old.completeExceptionally(new IllegalStateException("old"));
        assertEquals(1, calls.get());
        assertEquals(1, publication.snapshot().nodes.get("node").attempts);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void closeInvalidatesGuardCancelsTimersAndRejectsLaterPublications() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        CompletableFuture<Void> completion = new UncancellableFuture();
        List<BooleanSupplier> guards = new ArrayList<>();
        List<ConfigurationPublication.Binding<String>> nodes = Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> {
                    guards.add(current);
                    return completion;
                }));
        publication.publish(values("a"), "a", nodes);
        publication.close();
        assertFalse(guards.get(0).getAsBoolean());
        assertEquals(0, scheduler.pending());
        completion.complete(null);
        scheduler.runAllIncludingCancelled();
        publication.retry();
        publication.publish(values("b"), "b", nodes);
        assertTrue(publication.snapshot().closed);
        assertEquals(1, publication.snapshot().revision);
        assertEquals(0, publication.snapshot().nodes.get("node").appliedRevision);
    }

    @Test public void retryDelaysAreBoundedAndRepeatedSameValuesCannotResetExhaustedBudget() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger calls = new AtomicInteger();
        List<ConfigurationPublication.Binding<String>> nodes = Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> {
                    calls.incrementAndGet();
                    return failed(new IllegalStateException("secret value"));
                }));
        publication.publish(values("secret value"), "secret value", nodes);
        scheduler.advance(99);
        assertEquals(1, calls.get());
        scheduler.advance(1);
        assertEquals(2, calls.get());
        scheduler.advance(299);
        assertEquals(2, calls.get());
        scheduler.advance(1);
        assertEquals(3, calls.get());
        for (int i = 0; i < 4; i++) publication.publish(values("secret value"), "secret value", nodes);
        scheduler.advance(10000);
        assertEquals(3, calls.get());
        assertEquals(ConfigurationPublication.Summary.FAILED, publication.snapshot().summary);
        assertEquals(IllegalStateException.class.getName(), publication.snapshot().nodes.get("node").failureClass);
        assertEquals(0, scheduler.pending());
    }

    @Test public void acknowledgementDeadlineRetriesAndLateTimedOutCompletionCannotAcknowledgeNewAttempt() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        List<CompletableFuture<Void>> completions = new ArrayList<>();
        List<BooleanSupplier> guards = new ArrayList<>();
        publication.publish(values("a"), "a", Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> {
                    guards.add(current);
                    CompletableFuture<Void> completion = new UncancellableFuture();
                    completions.add(completion);
                    return completion;
                })));
        scheduler.advance(4999);
        assertTrue(guards.get(0).getAsBoolean());
        scheduler.advance(1);
        assertFalse(guards.get(0).getAsBoolean());
        assertEquals(ConfigurationPublication.Reason.ACK_TIMEOUT, publication.snapshot().nodes.get("node").reason);
        scheduler.advance(100);
        completions.get(0).complete(null);
        assertEquals(0, publication.snapshot().nodes.get("node").appliedRevision);
        completions.get(1).complete(null);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void rejectedPostFailsAttemptAndCanRecoverWithoutBlockingTail() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        AtomicInteger calls = new AtomicInteger();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("post", new Object(), true, payload -> current ->
                        calls.incrementAndGet() == 1 ? failed(new RejectedExecutionException("post")) : done()),
                binding("tail", new Object(), true, payload -> current -> done())));
        assertEquals(1, publication.snapshot().nodes.get("tail").appliedRevision);
        publication.retry();
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void factoryFailureDoesNotPreventOtherFactoriesOrDiscardLaterRetainedAction() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler());
        AtomicInteger factories = new AtomicInteger();
        publication.publish(values("a"), "a", Arrays.asList(
                binding("factory", new Object(), true, payload -> {
                    if (factories.incrementAndGet() == 1) throw new IllegalArgumentException("factory");
                    return current -> done();
                }), binding("tail", new Object(), true, payload -> current -> done())));
        assertEquals(1, publication.snapshot().nodes.get("tail").appliedRevision);
        publication.retry();
        publication.retry();
        assertEquals(2, factories.get());
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void schedulerRejectionIsContainedAndManualRetryCanRestoreDeadlineCoverage() {
        ManualScheduler scheduler = new ManualScheduler();
        scheduler.rejections = 2;
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger calls = new AtomicInteger();
        publication.publish(values("a"), "a", Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> {
                    calls.incrementAndGet();
                    return done();
                })));
        assertEquals(0, calls.get());
        assertEquals(ConfigurationPublication.Reason.SCHEDULER_FAILURE,
                publication.snapshot().nodes.get("node").reason);
        publication.retry();
        assertEquals(1, calls.get());
        assertEquals(2, publication.snapshot().nodes.get("node").attempts);
        assertEquals(ConfigurationPublication.Summary.SUCCESS, publication.snapshot().summary);
    }

    @Test public void uncertainOneTimeEffectIsTerminalAndNeverReplayedOnRetry() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        AtomicInteger calls = new AtomicInteger();
        List<ConfigurationPublication.Binding<String>> nodes = Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> {
                    calls.incrementAndGet();
                    throw new ConfigurationApplyAction.PermanentFailure(new IllegalStateException("private"));
                }));
        publication.publish(values("a"), "a", nodes);
        publication.retry();
        publication.publish(values("a"), "a", nodes);
        scheduler.advance(10000);
        assertEquals(1, calls.get());
        assertEquals(ConfigurationPublication.Reason.PERMANENT_FAILURE,
                publication.snapshot().nodes.get("node").reason);
        assertEquals(0, scheduler.pending());
    }

    @Test public void exhaustedBudgetResetsOnlyForRelevantChangeOrDifferentReceiver() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        Object receiver = new Object();
        AtomicInteger calls = new AtomicInteger();
        Function<String, ConfigurationApplyAction> action = payload -> current -> {
            calls.incrementAndGet();
            return failed(new IllegalStateException());
        };
        publication.publish(values("a"), "a", Collections.singletonList(binding("node", receiver, true, action)));
        scheduler.advance(400);
        assertEquals(3, calls.get());
        publication.publish(values("b"), "b", Collections.singletonList(binding("node", receiver, true, action)));
        assertEquals(4, calls.get());
        assertEquals(1, publication.snapshot().nodes.get("node").attempts);
        publication.publish(values("b"), "b", Collections.singletonList(binding("node", new Object(), false, action)));
        assertEquals(5, calls.get());
        assertEquals(1, publication.snapshot().nodes.get("node").attempts);
    }

    @Test public void missingDependencyDoesNotConsumeAttemptsOrCreateRetryTimers() {
        ManualScheduler scheduler = new ManualScheduler();
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(scheduler);
        publication.publish(values("a"), "a", Collections.singletonList(
                new ConfigurationPublication.Binding<>("dependent", new Object(), true,
                        Collections.singletonList("not-installed"), payload -> current -> {
                    fail("a missing dependency must not dispatch business work");
                    return done();
                })));
        for (int i = 0; i < 5; i++) publication.retry();
        assertEquals(ConfigurationPublication.Status.WAITING_DEPENDENCY,
                publication.snapshot().nodes.get("dependent").status);
        assertEquals(0, publication.snapshot().nodes.get("dependent").attempts);
        assertEquals(ConfigurationPublication.Summary.PENDING, publication.snapshot().summary);
        assertEquals(0, scheduler.pending());
    }

    @Test public void observerExceptionsDoNotAffectApplicationAndSnapshotsRemainImmutable() {
        ConfigurationPublication<String> publication = new ConfigurationPublication<>(new ManualScheduler(), snapshot -> {
            throw new IllegalStateException("observer");
        });
        publication.publish(values("a"), "a", Collections.singletonList(
                binding("node", new Object(), true, payload -> current -> done())));
        ConfigurationPublication.Snapshot first = publication.snapshot();
        publication.publish(values("b"), "b", Collections.emptyList());
        assertEquals(ConfigurationPublication.Summary.SUCCESS, first.summary);
        assertEquals(1, first.nodes.size());
        try {
            first.nodes.clear();
            fail("snapshot nodes must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    private static <T> ConfigurationPublication.Binding<T> binding(String id, Object receiver,
            boolean affected, Function<T, ConfigurationApplyAction> prepare) {
        return new ConfigurationPublication.Binding<>(id, receiver, affected, Collections.emptyList(), prepare);
    }

    private static Map<String, ?> values(String value) {
        return Collections.singletonMap("setting", value);
    }

    private static CompletableFuture<Void> done() { return CompletableFuture.completedFuture(null); }

    private static CompletableFuture<Void> failed(Throwable failure) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        result.completeExceptionally(failure);
        return result;
    }

    private static final class EqualReceiver {
        @Override public boolean equals(Object other) { return other instanceof EqualReceiver; }
        @Override public int hashCode() { return 1; }
    }

    private static final class UncancellableFuture extends CompletableFuture<Void> {
        @Override public boolean cancel(boolean mayInterruptIfRunning) { return false; }
    }

    private static final class PausingCancelScheduler implements ConfigurationPublication.Scheduler {
        final ManualScheduler delegate = new ManualScheduler();
        final CountDownLatch cancelEntered = new CountDownLatch(1);
        final CountDownLatch cancelReleased = new CountDownLatch(1);
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        volatile boolean pauseNextCancellation;
        volatile long pauseDelayMillis = 100;

        @Override public ConfigurationPublication.Cancellable schedule(Runnable runnable, long delayMillis) {
            ConfigurationPublication.Cancellable handle = delegate.schedule(runnable, delayMillis);
            return () -> {
                handle.cancel();
                if (delayMillis != pauseDelayMillis || !pauseNextCancellation) return;
                pauseNextCancellation = false;
                cancelEntered.countDown();
                try {
                    if (!cancelReleased.await(5, TimeUnit.SECONDS)) {
                        failure.set(new AssertionError("Scheduled cancellation was not released"));
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    failure.set(interrupted);
                }
            };
        }
    }

    private static final class ManualScheduler implements ConfigurationPublication.Scheduler {
        final List<Task> tasks = new ArrayList<>();
        long now;
        int rejections;

        @Override public ConfigurationPublication.Cancellable schedule(Runnable runnable, long delayMillis) {
            if (rejections > 0) {
                rejections--;
                throw new RejectedExecutionException("scheduler");
            }
            Task task = new Task(runnable, now + delayMillis);
            tasks.add(task);
            return () -> task.cancelled = true;
        }

        void advance(long millis) {
            long end = now + millis;
            while (true) {
                Task next = null;
                for (Task task : tasks) {
                    if (!task.ran && !task.cancelled && task.when <= end
                            && (next == null || task.when < next.when)) next = task;
                }
                if (next == null) break;
                now = next.when;
                next.ran = true;
                next.runnable.run();
            }
            now = end;
        }

        int pending() {
            int count = 0;
            for (Task task : tasks) if (!task.ran && !task.cancelled) count++;
            return count;
        }

        void runAllIncludingCancelled() {
            for (Task task : new ArrayList<>(tasks)) {
                if (!task.ran) {
                    task.ran = true;
                    task.runnable.run();
                }
            }
        }

        private static final class Task {
            final Runnable runnable;
            final long when;
            boolean cancelled;
            boolean ran;
            Task(Runnable runnable, long when) { this.runnable = runnable; this.when = when; }
        }
    }
}
