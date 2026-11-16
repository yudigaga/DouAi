package com.douai.module.scan;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public final class HostWorkQueueTest {
    @Test public void workerIdentityIsTrueOnlyInsideItsOwnSerialLane() throws Exception {
        try (HostWorkQueue queue = new HostWorkQueue(); HostWorkQueue other = new HostWorkQueue()) {
            assertFalse(queue.isWorkerThread());
            queue.submit(() -> {
                assertTrue(queue.isWorkerThread());
                assertFalse(other.isWorkerThread());
            }).get(5, TimeUnit.SECONDS);
            assertFalse(queue.isWorkerThread());
        }
    }

    @Test public void installationAndLaterConfigurationRunInOrderWithoutBlockingTheCaller() throws Exception {
        try (HostWorkQueue queue = new HostWorkQueue()) {
            CountDownLatch installing = new CountDownLatch(1), release = new CountDownLatch(1);
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            Thread caller = Thread.currentThread();
            CompletableFuture<Void> installation = queue.submit(() -> {
                assertNotSame(caller, Thread.currentThread());
                calls.add("install-start"); installing.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
                calls.add("install-published");
            });
            try {
                assertTrue(installing.await(5, TimeUnit.SECONDS));
                CompletableFuture<Void> configuration = queue.submit(() -> calls.add("latest-configuration"));
                assertFalse(installation.isDone());
                assertFalse(configuration.isDone());
                assertEquals(Collections.singletonList("install-start"), calls);
                release.countDown();
                configuration.get(5, TimeUnit.SECONDS);
                assertEquals(Arrays.asList("install-start", "install-published", "latest-configuration"), calls);
            } finally { release.countDown(); }
        }
    }

    @Test public void failedWorkCompletesItsFutureAndDoesNotSuppressTheNextPublication() throws Exception {
        try (HostWorkQueue queue = new HostWorkQueue()) {
            CompletableFuture<Void> failed = queue.submit(() -> { throw new IllegalStateException("fixture"); });
            assertThrows(ExecutionException.class, () -> failed.get(5, TimeUnit.SECONDS));
            List<String> calls = new ArrayList<>();
            queue.submit(() -> calls.add("published")).get(5, TimeUnit.SECONDS);
            assertEquals(Collections.singletonList("published"), calls);
        }
    }

    @Test public void laterReadinessCheckIsPreservedAfterAnEarlierPendingCheck() throws Exception {
        try (HostWorkQueue queue = new HostWorkQueue()) {
            CountDownLatch checking = new CountDownLatch(1), release = new CountDownLatch(1);
            List<String> calls = Collections.synchronizedList(new ArrayList<>());
            queue.submit(() -> {
                calls.add("dependencies-pending"); checking.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException failure) { throw new AssertionError(failure); }
            });
            try {
                assertTrue(checking.await(5, TimeUnit.SECONDS));
                CompletableFuture<Void> ready = queue.submit(() -> calls.add("dependencies-ready"));
                release.countDown(); ready.get(5, TimeUnit.SECONDS);
                assertEquals(Arrays.asList("dependencies-pending", "dependencies-ready"), calls);
            } finally { release.countDown(); }
        }
    }
}
