package com.douai.module.feature;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Collections;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DownloadCancellationTest {
    @org.junit.Before public void requireDrainedDownloadTasks() { DownloadTaskTestIdle.beforeTest(); }

    @org.junit.After public void finishDownloadWorkers() { DownloadTaskTestIdle.afterTest(); }

    @Test public void uiCancellationReturnsWhileProviderCloseBlocksAndKeepsRetryDeduplicated() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.OutputStreams previousOutputs = MediaStoreDownloader.outputs;
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch closing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch allowClose = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException {
                writing.countDown();
                while (!closed.get()) try { Thread.sleep(5); } catch (InterruptedException ignored) { }
                throw new java.io.IOException("Closed");
            }
            @Override public void close() {
                closing.countDown();
                boolean ready = false;
                while (!ready) try { ready = allowClose.await(3, TimeUnit.SECONDS); } catch (InterruptedException ignored) { }
                closed.set(true);
            }
        };
        MediaStoreDownloader.connections = uri -> response(null);
        MediaStoreDownloader.outputs = (resolver, uri) -> output;
        try {
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/same.mp4",
                    "same.mp4", false, DownloadDocumentTreeTest.TREE, null));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            String id = DownloadTasks.snapshots().get(0).id;
            long started = System.nanoTime();
            DownloadTasks.cancel(id);
            assertTrue("Cancel must not run provider I/O on the caller", System.nanoTime() - started < TimeUnit.MILLISECONDS.toNanos(500));
            assertTrue(closing.await(2, TimeUnit.SECONDS));
            assertEquals("cancelling", snapshot(id).status);
            assertFalse("Old cleanup must retain its file identity", MediaStoreDownloader.download(RuntimeEnvironment.getApplication(),
                    "https://media.test/same.mp4", "same.mp4", false, DownloadDocumentTreeTest.TREE, null));
            allowClose.countDown();
            await(() -> "cancelled".equals(snapshot(id).status));
            assertTrue(provider.names.isEmpty());
            MediaStoreDownloader.outputs = previousOutputs;
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/same.mp4",
                    "same.mp4", false, DownloadDocumentTreeTest.TREE, null));
            String retry = DownloadTasks.snapshots().get(0).id;
            await(() -> "completed".equals(snapshot(retry).status));
            assertEquals(1, provider.names.size());
        } finally {
            allowClose.countDown(); closed.set(true);
            MediaStoreDownloader.connections = previous; MediaStoreDownloader.outputs = previousOutputs;
        }
    }

    @Test public void cancellingOfficialSaveClosesBlockedProviderOutputBeforeReleasingTask() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.OutputStreams previousOutputs = MediaStoreDownloader.outputs;
        java.util.concurrent.CountDownLatch writing = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch released = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean closed = new java.util.concurrent.atomic.AtomicBoolean();
        java.io.OutputStream output = new java.io.OutputStream() {
            @Override public void write(int value) throws java.io.IOException {
                writing.countDown();
                while (!closed.get()) {
                    try { released.await(3, TimeUnit.SECONDS); }
                    catch (InterruptedException ignored) { }
                }
                throw new java.io.IOException("Provider output closed");
            }
            @Override public void close() { closed.set(true); released.countDown(); }
        };
        MediaStoreDownloader.connections = uri -> response(null);
        MediaStoreDownloader.outputs = (resolver, uri) -> output;
        try {
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/output.mp4",
                    "output.mp4", false, DownloadDocumentTreeTest.TREE, null));
            assertTrue(writing.await(2, TimeUnit.SECONDS));
            String id = DownloadTasks.snapshots().get(0).id;
            DownloadTasks.cancel(id);
            await(() -> "cancelled".equals(snapshot(id).status));
            assertTrue(closed.get());
            assertTrue(provider.names.isEmpty());
        } finally { output.close(); MediaStoreDownloader.connections = previous; MediaStoreDownloader.outputs = previousOutputs; }
    }

    @Test public void officialSaveExposesProgressAndCancelRemovesItsPartialDocument() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        BlockingMediaConnection blocked = new BlockingMediaConnection(false);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.connections = uri -> blocked;
        try {
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/official.mp4",
                    "official.mp4", false, DownloadDocumentTreeTest.TREE, null));
            assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
            DownloadTasks.Snapshot task = DownloadTasks.snapshots().get(0);
            assertEquals("official.mp4", task.name);
            assertEquals(1, task.bytes);
            assertEquals(1024, task.totalBytes);
            assertTrue(task.cancellable);
            DownloadTasks.cancel(task.id);
            await(() -> "cancelled".equals(snapshot(task.id).status));
            assertTrue(blocked.disconnected);
            assertTrue(provider.names.isEmpty());
            MediaStoreDownloader.connections = uri -> response(null);
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/next.mp4",
                    "next.mp4", false, DownloadDocumentTreeTest.TREE, null));
            String next = DownloadTasks.snapshots().get(0).id;
            await(() -> "completed".equals(snapshot(next).status));
            assertEquals(1, provider.names.size());
        } finally { blocked.disconnect(); MediaStoreDownloader.connections = previous; }
    }

    @Test public void independentWatchdogDisconnectsBlockedOfficialDownloadAfterOverallDeadline() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        BlockingMediaConnection blocked = new BlockingMediaConnection(true);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        Supplier<DownloadTransfer> previousTransfers = DownloadTasks.transfers;
        AtomicLong elapsed = new AtomicLong(1);
        MediaStoreDownloader.connections = uri -> blocked;
        DownloadTasks.transfers = () -> new DownloadTransfer(DownloadTransfer.TIMEOUT_MILLIS, elapsed::get);
        try {
            assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/slow.mp4",
                    "slow.mp4", false, DownloadDocumentTreeTest.TREE, null));
            assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
            String id = DownloadTasks.snapshots().get(0).id;
            elapsed.addAndGet(TimeUnit.MINUTES.toNanos(31));
            await(() -> "timed_out".equals(snapshot(id).status));
            assertTrue(blocked.disconnected);
            assertTrue(provider.names.isEmpty());
        } finally {
            blocked.disconnect(); MediaStoreDownloader.connections = previous; DownloadTasks.transfers = previousTransfers;
        }
    }

    @Test public void cancellingThirdPartyParserDisconnectsBeforeAnyFileIsCreated() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        BlockingMediaConnection blocked = new BlockingMediaConnection(true);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.connections = uri -> blocked;
        java.util.Map<String, Object> values = new java.util.HashMap<>();
        values.put(ThirdPartyDownloadSettings.GET_TEMPLATE, "https://parser.test/?token=private-secret&url={url}");
        values.put(ThirdPartyDownloadSettings.DIRECT, true);
        values.put(ThirdPartyDownloadSettings.SIGNATURE_MODE, ThirdPartyDownloadSettings.SIGNATURE_NONE);
        values.put(LocalOptions.DOWNLOAD_SAVE_TREE, DownloadDocumentTreeTest.TREE);
        try {
            ThirdPartyDownload.start(RuntimeEnvironment.getApplication(), "https://www.douyin.com/video/12345",
                    new LocalOptions(values), null);
            assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
            DownloadTasks.Snapshot task = DownloadTasks.snapshots().get(0);
            assertEquals("正在解析作品", task.phase);
            assertFalse((task.name + task.phase).contains("private-secret"));
            DownloadTasks.cancel(task.id);
            await(() -> "cancelled".equals(snapshot(task.id).status));
            assertTrue(blocked.disconnected);
            assertTrue(provider.names.isEmpty());
        } finally { blocked.disconnect(); MediaStoreDownloader.connections = previous; }
    }

    @Test @Config(sdk = 28) public void cancellingSystemDownloadRemovesOnlyTheOwnedDownloadId() throws Exception {
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.connections = uri -> response(null);
        try {
        android.app.DownloadManager manager = (android.app.DownloadManager) RuntimeEnvironment.getApplication()
                .getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        long other = manager.enqueue(new android.app.DownloadManager.Request(android.net.Uri.parse("https://media.test/existing.mp4")));
        org.robolectric.shadows.ShadowDownloadManager shadow = org.robolectric.shadow.api.Shadow.extract(manager);
        assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/owned.mp4", "owned.mp4", false, null));
        String id = DownloadTasks.snapshots().get(0).id;
        await(() -> shadow.getRequestCount() == 2);
        DownloadTasks.cancel(id);
        await(() -> "cancelled".equals(snapshot(id).status));
        assertEquals(1, shadow.getRequestCount());
        assertNotNull(shadow.getRequest(other));
        } finally { MediaStoreDownloader.connections = previous; }
    }

    @Test @Config(sdk = 28) public void systemDownloadTerminalResultsPreserveUnrelatedIds() throws Exception {
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.connections = uri -> response(null);
        android.app.DownloadManager manager = (android.app.DownloadManager) RuntimeEnvironment.getApplication()
                .getSystemService(android.content.Context.DOWNLOAD_SERVICE);
        org.robolectric.shadows.ShadowDownloadManager shadow = org.robolectric.shadow.api.Shadow.extract(manager);
        long unrelated = manager.enqueue(new android.app.DownloadManager.Request(android.net.Uri.parse("https://media.test/other.mp4")));
        try {
            for (boolean success : new boolean[]{true, false}) {
                String name = success ? "success.mp4" : "failure.mp4";
                assertTrue(MediaStoreDownloader.download(RuntimeEnvironment.getApplication(), "https://media.test/" + name, name, false, null));
                String task = DownloadTasks.snapshots().get(0).id;
                await(() -> shadow.getRequestCount() == 2);
                long owned = -1;
                try (android.database.Cursor cursor = manager.query(new android.app.DownloadManager.Query())) {
                    while (cursor.moveToNext()) {
                        long id = cursor.getLong(cursor.getColumnIndexOrThrow(android.app.DownloadManager.COLUMN_ID));
                        if (id != unrelated) owned = id;
                    }
                }
                assertTrue(owned >= 0);
                org.robolectric.shadows.ShadowDownloadManager.ShadowRequest request =
                        org.robolectric.shadow.api.Shadow.extract(shadow.getRequest(owned));
                request.setTotalSize(1); request.setBytesSoFar(1);
                // Shadow cursors read live fields: publish observed progress before the terminal
                // transition, so a cursor can't combine old 0/0 bytes with the new success status.
                await(() -> { DownloadTasks.Snapshot view = snapshot(task); return view.bytes == 1 && view.totalBytes == 1; });
                request.setStatus(success ? android.app.DownloadManager.STATUS_SUCCESSFUL : android.app.DownloadManager.STATUS_FAILED);
                await(() -> (success ? "completed" : "failed").equals(snapshot(task).status));
                assertNotNull(shadow.getRequest(unrelated));
                if (success) {
                    DownloadTasks.cancel(task);
                    assertNotNull("Cancelling a completed task must preserve its download", shadow.getRequest(owned));
                    manager.remove(owned);
                } else assertNull(shadow.getRequest(owned));
            }
        } finally { MediaStoreDownloader.connections = previous; }
    }

    @Test @Config(sdk = 28) public void cancelledLegacyBundleDeletesOnlyItsNewDirectory() throws Exception {
        java.io.File parent = new java.io.File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "DouAi");
        parent.mkdirs();
        java.io.File existing = java.io.File.createTempFile("existing-", ".mp4", parent);
        java.util.Set<String> before = new java.util.HashSet<>(java.util.Arrays.asList(parent.list()));
        BlockingMediaConnection blocked = new BlockingMediaConnection(false);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        AtomicInteger calls = new AtomicInteger();
        MediaStoreDownloader.connections = uri -> calls.getAndIncrement() == 0 ? response(null) : blocked;
        DownloadTransfer transfer = new DownloadTransfer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan(true), "", transfer); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            thread.start(); assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
            transfer.cancel(); thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
            assertEquals(before, new java.util.HashSet<>(java.util.Arrays.asList(parent.list())));
            assertTrue(existing.exists());
        } finally { blocked.disconnect(); thread.join(2000); existing.delete(); MediaStoreDownloader.connections = previous; }
    }

    @Test public void cancelledBundleRemovesAllOfItsPendingMediaStoreRows() throws Exception {
        ThirdPartyDownloadTest.RecordingProvider provider = new ThirdPartyDownloadTest.RecordingProvider();
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = android.provider.MediaStore.AUTHORITY;
        provider.attachInfo(RuntimeEnvironment.getApplication(), info);
        org.robolectric.shadows.ShadowContentResolver.registerProviderInternal(info.authority, provider);
        BlockingMediaConnection blocked = new BlockingMediaConnection(false);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        AtomicInteger calls = new AtomicInteger();
        MediaStoreDownloader.connections = uri -> calls.getAndIncrement() == 0 ? response(null) : blocked;
        DownloadTransfer transfer = new DownloadTransfer();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan(true), "", transfer); }
            catch (Throwable error) { failure.set(error); }
        });
        try {
            thread.start(); assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
            transfer.cancel(); thread.join(2000);
            assertFalse(thread.isAlive());
            assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
            assertTrue(provider.values.isEmpty());
            assertTrue(provider.files.isEmpty());
            assertEquals(2, provider.deleted.size());
            assertTrue(provider.published.isEmpty());
        } finally { blocked.disconnect(); thread.join(2000); MediaStoreDownloader.connections = previous; }
    }

    @Test public void finishedHistoryIsBoundedWithoutDiscardingActiveTasks() throws Exception {
        for (int index = 0; index < 52; index++) {
            DownloadTasks.Task task = DownloadTasks.submit(RuntimeEnvironment.getApplication(), "history-" + index,
                    "file-" + index, transfer -> "已完成", null);
            assertNotNull(task);
            await(() -> task.terminal);
        }
        // terminal is published before the release callback trims completed history.
        await(() -> DownloadTasks.snapshots().size() == 50);
        assertEquals(50, DownloadTasks.snapshots().size());
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        DownloadTasks.Task first = DownloadTasks.submit(RuntimeEnvironment.getApplication(), "active-one", "one",
                transfer -> { release.await(); return "完成"; }, null);
        DownloadTasks.Task second = DownloadTasks.submit(RuntimeEnvironment.getApplication(), "active-two", "two",
                transfer -> { release.await(); return "完成"; }, null);
        try {
            assertNotNull(first); assertNotNull(second);
            assertEquals(52, DownloadTasks.snapshots().size());
            assertTrue(snapshot(first.id).cancellable);
            assertTrue(snapshot(second.id).cancellable);
        } finally {
            DownloadTasks.cancel(first.id); DownloadTasks.cancel(second.id); release.countDown();
            await(() -> first.terminal && second.terminal);
        }
        await(() -> DownloadTasks.snapshots().size() == 50);
        assertEquals(50, DownloadTasks.snapshots().size());
    }

    private static DownloadTasks.Snapshot snapshot(String id) {
        return DownloadTasks.snapshots().stream().filter(task -> task.id.equals(id)).findFirst().orElseThrow(AssertionError::new);
    }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue("Timed out waiting for download lifecycle", condition.getAsBoolean());
    }

    @Test public void cancellingBundleDisconnectsTransportAndRemovesOnlyItsOwnDocuments() throws Exception {
        for (boolean headers : new boolean[]{true, false}) {
            DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
            String existing = provider.createDocument("root", "video/mp4", "existing.mp4");
            BlockingMediaConnection blocked = new BlockingMediaConnection(headers);
            DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
            AtomicInteger opened = new AtomicInteger();
            MediaStoreDownloader.connections = uri -> opened.getAndIncrement() == 0 ? response(null) : blocked;
            DownloadTransfer transfer = new DownloadTransfer();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = new Thread(() -> {
                try {
                    transfer.start();
                    MediaStoreDownloader.saveThirdPartyBundle(RuntimeEnvironment.getApplication(), plan(true),
                            DownloadDocumentTreeTest.TREE, transfer);
                } catch (Throwable error) { failure.set(error); }
            });
            try {
                thread.start();
                assertTrue(blocked.blocked.await(2, TimeUnit.SECONDS));
                transfer.cancel();
                thread.join(1000);
                assertTrue("Cancellation must disconnect the active download", blocked.disconnected);
                assertFalse("Cancellation must release the download worker", thread.isAlive());
                assertTrue(failure.get() instanceof java.util.concurrent.CancellationException);
                assertEquals(Collections.singleton(existing), provider.names.keySet());
                assertTrue(provider.files.get(existing).exists());
            } finally {
                blocked.disconnect(); thread.join(2000);
                MediaStoreDownloader.connections = previous;
            }
        }
    }

    @Test public void continuouslyArrivingBundleBytesStillRespectTheOverallDeadline() throws Exception {
        DownloadDocumentTreeTest.RecordingDocuments provider = DownloadDocumentTreeTest.register();
        AtomicLong elapsed = new AtomicLong(1);
        DownloadTransfer transfer = new DownloadTransfer(DownloadTransfer.TIMEOUT_MILLIS, elapsed::get);
        DownloadTransfer.Connections previous = MediaStoreDownloader.connections;
        MediaStoreDownloader.connections = uri -> response(() -> elapsed.addAndGet(TimeUnit.MINUTES.toNanos(31)));
        try {
            transfer.start();
            assertThrows(TimeoutException.class, () -> MediaStoreDownloader.saveThirdPartyBundle(
                    RuntimeEnvironment.getApplication(), plan(false), DownloadDocumentTreeTest.TREE, transfer));
            assertTrue(provider.names.isEmpty());
        } finally { MediaStoreDownloader.connections = previous; }
    }

    static ThirdPartyDownloadPlan plan(boolean multiple) {
        String media = "[\"https://media.test/one.mp4\"" + (multiple ? ",\"https://media.test/two.mp4\"" : "") + "]";
        return ThirdPartyDownloadPlan.create(ThirdPartyResponse.parse("{\"data\":{\"video\":" + media + "}}"),
                new ThirdPartyDownloadSettings(new LocalOptions(Collections.emptyMap())), 100);
    }

    static HttpURLConnection response(Runnable beforeRead) throws Exception {
        return new HttpURLConnection(new URL("https://media.test/one.mp4")) {
            @Override public int getResponseCode() { return 200; }
            @Override public long getContentLengthLong() { return 1; }
            @Override public String getContentType() { return "video/mp4"; }
            @Override public InputStream getInputStream() {
                if (beforeRead != null) beforeRead.run();
                return new ByteArrayInputStream(new byte[]{1});
            }
            @Override public void disconnect() { }
            @Override public boolean usingProxy() { return false; }
            @Override public void connect() { }
        };
    }
}
