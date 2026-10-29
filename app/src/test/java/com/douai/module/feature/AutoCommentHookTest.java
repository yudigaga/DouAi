package com.douai.module.feature;

import android.app.Activity;
import android.content.Context;
import android.view.View;
import android.widget.EditText;
import android.widget.FrameLayout;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.LocalOptions;
import com.douai.module.config.SubmissionLedger;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.scan.HostCandidate;
import com.douai.module.scan.HostScanReport;
import com.douai.module.scan.RuleScanResult;

import io.github.libxposed.api.XposedInterface;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutoCommentHookTest {
    private Activity activity;
    private AutoCommentHook hook;
    private AutomationDispatcher dispatcher;
    private ManualExecutor dispatchWorker;
    private Framework framework;
    private Holder holder;
    private EditText input;
    private final List<String> logs = new ArrayList<>();
    private java.util.function.Supplier<MediaReadCancellation> mediaReads = MediaReadCancellation::new;

    @Before public void setup() throws Exception {
        FollowListSnapshot.reset();
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences("douai_comment_submissions", Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit().clear().commit();
        activity.getSharedPreferences("douai_automation_limits", Context.MODE_PRIVATE).edit().clear().commit();
        FrameLayout root = new FrameLayout(activity);
        input = new EditText(activity);
        root.addView(input);
        activity.setContentView(root);
        holder = new Holder();
        holder.z = root;
        dispatchWorker = new ManualExecutor();
        dispatcher = new AutomationDispatcher(activity, logs::add, dispatchWorker);
        dispatcher.onActivityResumed(activity);
        dispatcher.setOptions(options(false));
        framework = new Framework();
        Request.reset(framework);
        hook = AutoCommentHook.install(framework.api, report(), getClass().getClassLoader(), activity,
                options(false), dispatcher, logs::add, () -> mediaReads.get());
        ShadowLooper.idleMainLooper();
    }

    @After public void close() {
        hook.close();
        dispatcher.close();
        ShadowLooper.idleMainLooper();
        dispatchWorker.runAll();
        activity.finish();
        Request.framework = null;
        FollowListSnapshot.reset();
    }

    @Test public void ringAppearsAtSelectionBeforeDelayOrGeneration() throws Throwable {
        setOptions(options(true));
        selectWork();
        assertNotNull(activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"));
        assertEquals(0, Request.attempts);
        assertFalse(logs.stream().anyMatch(value -> value.contains("model request started")));
        CommentRingView ring = (CommentRingView) notice();
        assertEquals(1, ring.getQueueCount());
        assertEquals(2, ring.getRetriesLeft());
        assertEquals("waiting", ring.getPhase());
        assertFalse(ring.getContentDescription().toString().contains("固定评论"));
        assertFalse(ring.getContentDescription().toString().contains("作品描述"));
    }

    @Test public void queuedCommentKeepsItsActualOptionsRevisionWhenTheProcessRevisionAlreadyMovedOn() throws Throwable {
        com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
        boolean previousDebug = DebugLog.isEnabled();
        DebugLog.setEnabled(true);
        List<String> records = Collections.synchronizedList(new ArrayList<>());
        com.douai.module.diagnostics.DouAiLog.setFrameworkSink((priority, json) -> records.add(json));
        try {
            LocalOptions captured = options(false);
            setOptions(captured);
            com.douai.module.diagnostics.DouAiLog.configRevision("newer-process-snapshot");
            selectWork();
            com.douai.module.diagnostics.DouAiLog.configRevision("another-process-publication");
            advanceSeconds(3);
            advanceSeconds(8);
            assertEquals(1, Request.attempts);
            assertEquals("固定评论", Request.models.get(0).a);
            assertEquals("sent", entry("work").status);
            assertTrue(com.douai.module.diagnostics.DouAiLog.awaitIdle(3000));
            String started = records.stream().filter(line -> line.contains("\"feature_id\":\"auto_comment\"")
                    && line.contains("\"event\":\"operation_started\"")).findFirst().orElseThrow();
            JSONObject root = new JSONObject(started);
            String traceId = root.getString("trace_id");
            assertEquals(captured.diagnosticRevision(), root.getString("config_revision"));
            assertEquals("newer-process-snapshot", root.getString("process_config_revision"));
            int traced = 0;
            for (String line : records) {
                JSONObject event = new JSONObject(line);
                if (!traceId.equals(event.optString("trace_id"))) continue;
                traced++;
                assertEquals(line, captured.diagnosticRevision(), event.getString("config_revision"));
            }
            assertTrue("The fixture must exercise generation and the owned host callback", traced > 4);
        } finally {
            com.douai.module.diagnostics.DouAiLog.awaitIdle(3000);
            com.douai.module.diagnostics.DouAiLog.setFrameworkSink(null);
            DebugLog.setEnabled(previousDebug);
        }
    }

    @Test public void openingRingBeforeGenerationHoldsPublicationUntilManualConfirmation() throws Throwable {
        setOptions(options(true));
        selectWork();
        notice().performClick();
        android.app.AlertDialog dialog = org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog();
        assertNotNull(dialog);
        assertFalse(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled());
        advanceSeconds(3);
        assertTrue(dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).isEnabled());
        assertEquals("固定评论", ((android.widget.TextView) dialog.findViewById(android.R.id.content)
                .findViewWithTag("automation.details.output")).getText().toString());
        advanceSeconds(30);
        assertEquals(0, Request.attempts);
        dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
        flushDispatch();
        assertEquals(1, Request.attempts);
        assertEquals("sent", entry("work").status);
        assertFalse(entry("work").modelRequestSent);
        assertEquals(0, entry("work").requestImageCount);
    }

    @Test public void cancellingRingBeforeGenerationRemovesNewestWorkAndKeepsOldestFirst() throws Throwable {
        setOptions(options(true));
        selectWork();
        holder.work = work("another"); selectWork();
        assertEquals(2, ((CommentRingView) notice()).getQueueCount());
        notice().performLongClick();
        flushDispatch();
        assertEquals(1, ((CommentRingView) notice()).getQueueCount());
        assertEquals("cancelled", entry("another").status);
        advanceSeconds(3);
        advanceSeconds(8);
        assertEquals(1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
    }

    @Test public void ringRetriesTrackThreeAttemptsWithoutExposingCommentText() throws Throwable {
        setOptions(options(true));
        Request.autoCallback = false;
        generate(); advanceSeconds(8);
        assertEquals(2, ((CommentRingView) notice()).getRetriesLeft());
        Request.complete(Request.instances.get(0), false); flushDispatch();
        assertEquals(1, ((CommentRingView) notice()).getRetriesLeft());
        advanceSeconds(1);
        Request.complete(Request.instances.get(1), false); flushDispatch();
        assertEquals(0, ((CommentRingView) notice()).getRetriesLeft());
        advanceSeconds(2);
        Request.complete(Request.instances.get(2), false); flushDispatch();
        assertEquals(3, Request.attempts);
        assertEquals("failed", ((CommentRingView) notice()).getPhase());
        assertEquals(0, ((CommentRingView) notice()).getQueueCount());
    }

    @Test public void cancellingRingDuringAiRequestStopsGenerationWithoutPublishing() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            setOptions(new LocalOptions(aiOptions(server, true)));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            assertEquals("generating", ((CommentRingView) notice()).getPhase());
            notice().performLongClick();
            awaitGeneration(); flushDispatch();
            assertNull(notice());
            assertEquals(0, Request.attempts);
            assertEquals("cancelled", entry("work").status);
            assertEquals(1, server.getRequestCount());
        }
    }

    @Test public void cancellingBlockedMediaLetsNextOriginalWorkGenerate() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(false);
        mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> connection);
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"下一作品评论\"}"));
            setOptions(new LocalOptions(aiOptions(server, true)));
            holder.work.images = Collections.singletonList(new Picture("https://p.byteimg.com/image.jpg"));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertTrue(connection.blocked.await(2, TimeUnit.SECONDS));
            notice().performLongClick();
            awaitGeneration(); flushDispatch();
            assertTrue(connection.disconnected);
            assertEquals("cancelled", entry("work").status);
            assertEquals(0, server.getRequestCount());
            assertFalse(entry("work").modelRequestSent);
            assertEquals(0, entry("work").requestImageCount);
            holder.work = work("another");
            generate(); advanceSeconds(8);
            assertEquals(1, server.getRequestCount());
            assertEquals(1, Request.attempts);
            assertEquals("another", Request.models.get(0).y.work.id);
        } finally { connection.disconnect(); }
    }

    @Test public void disablingCommentsDuringBlockedMediaDisconnectsOldTask() throws Throwable {
        BlockingMediaConnection connection = new BlockingMediaConnection(true);
        mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri -> connection);
        try (MockWebServer server = new MockWebServer()) {
            Map<String, Object> config = aiOptions(server, true);
            setOptions(new LocalOptions(config));
            holder.work.images = Collections.singletonList(new Picture("https://p.byteimg.com/image.jpg"));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertTrue(connection.blocked.await(2, TimeUnit.SECONDS));
            config.put("native_auto_comment", false);
            setOptions(new LocalOptions(config));
            awaitGeneration(); flushDispatch();
            assertTrue(connection.disconnected);
            assertEquals("cancelled", entry("work").status);
            assertEquals(0, server.getRequestCount());
            assertEquals(0, Request.attempts);
        } finally { connection.disconnect(); }
    }

    @Test public void generationDeadlineCompletesEvenBeforeWorkerStarts() throws Throwable {
        java.util.concurrent.CountDownLatch blocked = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
        worker.submit(() -> {
            blocked.countDown();
            try { release.await(); } catch (InterruptedException ignored) { }
        });
        try {
            assertTrue(blocked.await(2, TimeUnit.SECONDS));
            setOptions(options(true));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            for (int attempt = 1; attempt <= 3; attempt++) {
                ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(120));
                if (attempt < 3) ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(attempt));
            }
            flushDispatch();
            assertEquals("failed", entry("work").status);
            assertEquals(0, Request.attempts);
            release.countDown();
            awaitGeneration(); flushDispatch();
            assertEquals(0, Request.attempts);
            holder.work = work("another");
            generate(); advanceSeconds(8);
            assertEquals(1, Request.attempts);
            assertEquals("another", Request.models.get(0).y.work.id);
        } finally { release.countDown(); }
    }

    @Test public void generationDeadlineDuringAiRequestRetriesAndPublishesOnlyNewResult() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            server.enqueue(modelResponse("{\"comment\":\"重试后的评论\"}"));
            setOptions(new LocalOptions(aiOptions(server, false)));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(120));
            awaitGeneration(); flushDispatch();
            assertEquals(0, Request.attempts);
            advanceSeconds(1);
            assertEquals(2, server.getRequestCount());
            assertEquals(1, Request.attempts);
            assertEquals("重试后的评论", Request.models.get(0).a);
            assertEquals("sent", entry("work").status);
            assertTrue(entry("work").modelRequestSent);
            assertEquals(0, entry("work").requestImageCount);
        }
    }

    @Test public void imageRequestIsRecordedWithActualPayloadAndRetainedAfterCommentSend() throws Throwable {
        useImageMedia();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"图片里的颜色很鲜明\"}"));
            setOptions(new LocalOptions(aiOptions(server, false)));
            generate(); flushDispatch();
            RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(recorded);
            JSONArray contents = new JSONObject(recorded.getBody().readUtf8()).getJSONArray("messages")
                    .getJSONObject(1).getJSONArray("content");
            assertEquals("image_url", contents.getJSONObject(1).getString("type"));
            assertTrue(contents.getJSONObject(1).getJSONObject("image_url").getString("url").startsWith("data:image/"));
            assertTrue(entry("work").modelRequestSent);
            assertEquals(1, entry("work").requestImageCount);
            assertEquals("sent", entry("work").status);
        }
    }

    @Test public void cancelledCommentKeepsImageRequestEvidenceWhileModelIsStillWaiting() throws Throwable {
        useImageMedia();
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setSocketPolicy(okhttp3.mockwebserver.SocketPolicy.NO_RESPONSE));
            setOptions(new LocalOptions(aiOptions(server, true)));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
            flushDispatch();
            assertTrue(entry("work").modelRequestSent);
            assertEquals(1, entry("work").requestImageCount);
            notice().performLongClick();
            awaitGeneration(); flushDispatch();
            assertEquals("cancelled", entry("work").status);
            assertTrue(entry("work").modelRequestSent);
            assertEquals(1, entry("work").requestImageCount);
            assertEquals(0, Request.attempts);
        }
    }

    private void useImageMedia() {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        android.graphics.Bitmap bitmap = android.graphics.Bitmap.createBitmap(2, 2, android.graphics.Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(android.graphics.Color.RED);
        assertTrue(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, bytes));
        bitmap.recycle();
        mediaReads = () -> new MediaReadCancellation(60_000, System::nanoTime, uri ->
                new java.net.HttpURLConnection(uri.toURL()) {
                    @Override public int getResponseCode() { return 200; }
                    @Override public java.io.InputStream getInputStream() {
                        return new java.io.ByteArrayInputStream(bytes.toByteArray());
                    }
                    @Override public void disconnect() { }
                    @Override public boolean usingProxy() { return false; }
                    @Override public void connect() { }
                });
        holder.work.images = Collections.singletonList(new Picture("https://p.byteimg.com/image.png"));
    }

    @Test public void longPressDuringPublishingStopsFurtherRetriesWithoutRepeatingTheAcceptedRequest() throws Throwable {
        setOptions(options(true));
        Request.autoCallback = false;
        generate(); advanceSeconds(8);
        assertEquals(1, Request.attempts);
        assertEquals("publishing", ((CommentRingView) notice()).getPhase());
        notice().performLongClick(); flushDispatch();
        assertEquals("submitted", entry("work").status);
        Request.complete(Request.instances.get(0), false); flushDispatch();
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
        assertNull(notice());
    }

    @Test public void unknownThenLateFailureKeepsQuotaHistoryAndReservationConservative() throws Throwable {
        Request.autoCallback = false;
        generate(); flushDispatch(); advanceSeconds(30);
        assertEquals("submitted", entry("work").status);
        Request.complete(Request.instances.get(0), false); flushDispatch();
        assertEquals("submitted", entry("work").status);
        assertEquals(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, entry("work").reason);
        assertTrue(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                .contains("comment:100:work"));
        assertTrue(new SubmissionLedger(activity.getSharedPreferences("douai_comment_submissions", Context.MODE_PRIVATE), 1000)
                .contains(SubmissionLedger.identity("comment:100:work")));
        selectWork(); advanceSeconds(30);
        assertEquals(1, Request.attempts);
    }

    @Test public void submissionReservationIsDurableBeforeHostInvocation() throws Throwable {
        Request.beforeSubmit = () -> {
            SubmissionLedger recreated = new SubmissionLedger(activity.getSharedPreferences(
                    "douai_comment_submissions", Context.MODE_PRIVATE), 1000);
            assertTrue(recreated.contains(SubmissionLedger.identity("comment:100:work")));
            // This callback runs inside the host method, before acceptedRequest can save the legacy marker.
            assertFalse(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                    .contains("comment:100:work"));
        };
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertEquals("sent", entry("work").status);
    }

    @Test public void recreatedHookKeepsUncertainSendEvenWithoutLegacyMarker() throws Throwable {
        Request.throwAfterSubmit = true;
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE).edit().clear().commit();
        hook.close(); ShadowLooper.idleMainLooper();
        hook = AutoCommentHook.install(framework.api, report(), getClass().getClassLoader(), activity,
                options(false), dispatcher, logs::add, () -> mediaReads.get());
        selectWork(); advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
    }

    @Test public void corruptDurableReservationDoesNotRepeatTheHostSend() throws Throwable {
        activity.getSharedPreferences("douai_comment_submissions", Context.MODE_PRIVATE).edit()
                .putString("send." + SubmissionLedger.identity("comment:100:work"), "corrupt").commit();
        selectWork(); advanceSeconds(10);
        assertEquals(0, Request.attempts);
    }

    @Test public void fixedCommentUsesCapturedNativeWorkAndRecordsConfirmedSuccess() throws Throwable {
        generate();
        flushDispatch();
        assertEquals(1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
        assertEquals("固定评论", Request.models.get(0).a);
        assertEquals("douai_auto", Request.models.get(0).n);
        assertEquals("sent", history().get(0).status);
        assertTrue(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                .contains("comment:100:work"));
        selectWork();
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals(1, history().size());
    }

    @Test public void nativePublishingPreservesManualDraftAndInputFocus() throws Throwable {
        input.setText("本人编辑的草稿");
        input.requestFocus();
        generate();
        flushDispatch();
        assertEquals(1, Request.attempts);
        assertEquals("本人编辑的草稿", input.getText().toString());
        assertTrue(input.hasFocus());
        assertEquals("sent", history().get(0).status);
    }

    @Test public void switchingWorksBeforeGenerationStillProcessesOriginalThenNext() throws Throwable {
        selectWork();
        Work original = holder.work;
        holder.work = work("another");
        selectWork();
        advanceSeconds(3);
        assertEquals(1, Request.models.size());
        assertSame(original, Request.models.get(0).y.work);
        assertEquals("sent", entry("work").status);
        advanceSeconds(3);
        assertEquals(2, Request.models.size());
        assertEquals("another", Request.models.get(1).y.work.id);
        assertEquals(2, history().size());
    }

    @Test public void switchingAfterPreviewKeepsOriginalTargetAndTimeoutSendsIt() throws Throwable {
        setOptions(options(true));
        generate();
        assertNotNull(notice());
        Work original = holder.work;
        holder.work = work("another");
        selectWork();
        assertNotNull(notice());
        advanceSeconds(8);
        assertEquals(1, Request.models.size());
        assertSame(original, Request.models.get(0).y.work);
        assertEquals("sent", entry("work").status);
    }

    @Test public void originalNativeRequestRemainsValidAfterHolderIsRecycled() throws Throwable {
        Request.autoCallback = false;
        generate();
        flushDispatch();
        assertEquals(1, Request.instances.size());
        Work original = holder.work;
        holder.work = work("another");
        selectWork();
        Request.complete(Request.instances.get(0), true);
        flushDispatch();
        assertSame(original, Request.models.get(0).y.work);
        assertEquals("sent", entry("work").status);
        assertEquals(1, Request.attempts);
        advanceSeconds(3);
        assertEquals("another", Request.models.get(1).y.work.id);
    }

    @Test public void nativeFailureTriesThreeTimesThenContinuesNextWork() throws Throwable {
        Request.failuresRemaining = 3;
        selectWork();
        holder.work = work("another");
        selectWork();
        advanceSeconds(3);
        assertEquals(1, Request.attempts);
        advanceSeconds(1);
        assertEquals(2, Request.attempts);
        advanceSeconds(2);
        assertEquals(3, Request.attempts);
        assertEquals("failed", entry("work").status);
        assertNull(failureNotice());
        advanceSeconds(3);
        assertEquals(4, Request.attempts);
        assertEquals("another", Request.models.get(3).y.work.id);
        assertEquals("sent", entry("another").status);
        assertTrue(logs.stream().anyMatch(value -> value.contains("attempts=3")));
    }

    @Test public void rejectedNativeRequestsAreRetriedExactlyThreeTimes() throws Throwable {
        Request.reject = true;
        generate(); flushDispatch();
        advanceSeconds(1);
        advanceSeconds(2);
        advanceSeconds(30);
        assertEquals(3, Request.attempts);
        assertEquals("failed", entry("work").status);
        assertFalse(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                .contains("comment:100:work"));
    }

    @Test public void mismatchedNativeSuccessCannotMarkOriginalWorkAsSent() throws Throwable {
        Request.autoCallback = false;
        generate(); flushDispatch();
        Response unrelated = new Response(); unrelated.comment.awemeId = "other";
        framework.invoke(Request.class.getMethod("success", Response.class), Request.instances.get(0), unrelated);
        flushDispatch();
        advanceSeconds(1);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
        assertTrue(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                .contains("comment:100:work"));
    }

    @Test public void changingConfigurationWhileNativeRequestIsPendingDoesNotSubmitItAgain() throws Throwable {
        Request.autoCallback = false;
        generate(); flushDispatch();
        Map<String, Object> next = baseOptions(false);
        next.put("auto_comment_text", "修改后的评论");
        setOptions(new LocalOptions(next));
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
        Request.complete(Request.instances.get(0), true); flushDispatch();
        assertEquals("sent", entry("work").status);
        assertEquals(1, Request.attempts);
    }

    @Test public void changingNumericDelayReplacesTheWaitingTimer() throws Throwable {
        selectWork();
        Map<String, Object> next = baseOptions(false);
        next.put("comment_delay_seconds", 30);
        setOptions(new LocalOptions(next));
        advanceSeconds(3);
        assertEquals(0, Request.attempts);
        advanceSeconds(27);
        assertEquals(1, Request.attempts);
    }

    @Test public void retriesReuseApprovedTextWithoutRepeatedPreview() throws Throwable {
        setOptions(options(true));
        Request.failuresRemaining = 2;
        generate();
        assertNotNull(notice());
        advanceSeconds(8);
        assertEquals(1, Request.attempts);
        assertEquals("retrying", ((CommentRingView) notice()).getPhase());
        advanceSeconds(1);
        assertEquals(2, Request.attempts);
        assertEquals("retrying", ((CommentRingView) notice()).getPhase());
        advanceSeconds(2);
        assertEquals(3, Request.attempts);
        assertEquals("sent", entry("work").status);
        assertEquals(1, history().size());
    }

    @Test public void thirdFailureShowsTimedNoticeOnlyWhenEnabled() throws Throwable {
        setOptions(options(true));
        Request.failuresRemaining = 3;
        generate();
        advanceSeconds(8);
        advanceSeconds(1);
        advanceSeconds(2);
        assertEquals(3, Request.attempts);
        assertEquals("failed", entry("work").status);
        assertNotNull(failureNotice());
        advanceSeconds(8);
        assertNull(failureNotice());
    }

    @Test public void longPressCancellationNeverPublishesAndKeepsCurrentQueueHead() throws Throwable {
        setOptions(options(true));
        generate();
        assertNotNull(notice());
        holder.work = work("another");
        selectWork();
        notice().performLongClick();
        flushDispatch();
        assertEquals(0, Request.attempts);
        assertEquals("pending", entry("work").status);
        assertEquals("cancelled", entry("another").status);
        advanceSeconds(2);
        assertNotNull(notice());
        advanceSeconds(8);
        assertEquals(1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
        assertEquals("sent", entry("work").status);
    }

    @Test public void returningToPendingWorkDoesNotQueueDuplicate() throws Throwable {
        Work original = holder.work;
        selectWork();
        holder.work = work("another"); selectWork();
        holder.work = original; selectWork();
        advanceSeconds(3);
        advanceSeconds(3);
        advanceSeconds(10);
        assertEquals(2, Request.attempts);
        assertEquals(2, history().size());
    }

    @Test public void cancelledWorkStaysDedupedWhenReturning() throws Throwable {
        setOptions(options(true));
        Work original = holder.work;
        generate();
        notice().performLongClick();
        flushDispatch();
        holder.work = work("another"); selectWork();
        holder.work = original; selectWork();
        advanceSeconds(3);
        advanceSeconds(8);
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("another", Request.models.get(0).y.work.id);
        assertEquals("cancelled", entry("work").status);
    }

    @Test public void returningToCancelledWorkCannotStealCancelLatestFromQueuedWork() throws Throwable {
        setOptions(options(true));
        Work original = holder.work;
        selectWork();
        notice().performLongClick();
        flushDispatch();
        assertEquals("cancelled", entry("work").status);

        holder.work = work("another");
        selectWork();
        holder.work = original;
        selectWork();
        assertEquals(1, ((CommentRingView) notice()).getQueueCount());

        notice().performLongClick();
        flushDispatch();
        assertEquals("cancelled", entry("another").status);
        assertEquals(0, Request.attempts);
        assertNull(notice());
    }

    @Test public void pauseBeforeGenerationPreservesOriginalQueuedWork() throws Throwable {
        selectWork();
        dispatcher.onActivityPaused(activity);
        hook.onActivityPaused(activity);
        advanceSeconds(3);
        assertEquals(1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
        dispatcher.onActivityResumed(activity);
        hook.onActivityResumed(activity);
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
    }

    @Test public void disablingCommentCancelsQueuedWorkAndBlocksFurtherRequests() throws Throwable {
        generate();
        Map<String, Object> disabled = baseOptions(false);
        disabled.put("native_auto_comment", false);
        setOptions(new LocalOptions(disabled));
        flushDispatch();
        advanceSeconds(10);
        assertEquals(0, Request.attempts);
        assertEquals("cancelled", entry("work").status);
    }

    @Test public void disabledSelectionKeepsHotEnableAndRelationsWithoutCommentMetadataWork() throws Throwable {
        setOptions(new LocalOptions(Collections.emptyMap()));
        holder.work.author.uid = "987654321001";
        holder.work.author.nickname = "observed while disabled";
        holder.awemeReads = 0; holder.work.aidReads = 0;
        for (int i = 0; i < 100; i++) {
            framework.invoke(Holder.class.getMethod("selected", int.class), holder, i);
        }
        assertEquals("only relation observation needs the host work", 100, holder.awemeReads);
        assertEquals("disabled comments never need the work ID", 0, holder.work.aidReads);
        assertEquals("observed while disabled", AutomationScopePolicy.cachedName("987654321001"));
        ShadowLooper.idleMainLooper();
        assertEquals("disabled selection posts no second metadata read", 100, holder.awemeReads);
        assertEquals(0, holder.work.aidReads);
        assertEquals(0, Request.attempts);
        setOptions(options(false));
        advanceSeconds(3);
        assertEquals("hot enable processes the retained current holder", 1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
    }

    @Test public void disabledPlaybackAndResumeCallbacksDoNotReadTheRetainedWork() throws Throwable {
        hook.close();
        List<RuleScanResult> rules = new ArrayList<>(report().getRuleResults());
        add(rules, "component_player_playing", Player.class.getMethod("playing", String.class));
        hook = AutoCommentHook.install(framework.api, new HostScanReport("fixture", rules, false),
                getClass().getClassLoader(), activity, new LocalOptions(Collections.emptyMap()),
                dispatcher, logs::add, () -> mediaReads.get());
        selectWork();
        holder.awemeReads = 0;
        Player player = new Player();
        for (int i = 0; i < 100; i++) {
            framework.invoke(Player.class.getMethod("playing", String.class), player, "work");
            hook.onActivityResumed(activity);
        }
        ShadowLooper.idleMainLooper();
        assertEquals(0, holder.awemeReads);
        assertEquals(0, Request.attempts);
    }

    @Test public void commentRunsWithInteractionAutomationOffAndKeepsItsOwnStopSwitch() throws Throwable {
        Map<String, Object> independent = baseOptions(false);
        independent.put("automation_enabled", false);
        setOptions(new LocalOptions(independent));
        selectWork();
        advanceSeconds(3);
        assertEquals("The comment switch must work independently", 1, Request.attempts);
        assertEquals("sent", entry("work").status);
        independent.put("native_auto_comment", false);
        setOptions(new LocalOptions(independent));
        holder.work = work("after-stop");
        selectWork();
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
    }

    @Test public void interactionMasterChangeDoesNotCancelPreparedComment() throws Throwable {
        setOptions(options(true));
        selectWork();
        advanceSeconds(3);
        Map<String, Object> independent = baseOptions(true);
        independent.put("automation_enabled", false);
        setOptions(new LocalOptions(independent));
        advanceSeconds(8);
        assertEquals(1, Request.attempts);
        assertEquals("sent", entry("work").status);
    }

    @Test public void enablingAutomationProcessesTheAlreadySelectedWork() throws Throwable {
        setOptions(new LocalOptions(Collections.emptyMap()));
        selectWork();
        advanceSeconds(3);
        assertEquals(0, Request.attempts);
        setOptions(options(false));
        advanceSeconds(3);
        assertEquals(1, Request.attempts);
        assertEquals("sent", entry("work").status);
    }

    @Test public void absentNativeCallbackRecordsSubmittedWithoutUnsafeRetryAndLateSuccessUpdatesIt() throws Throwable {
        Request.autoCallback = false;
        generate(); flushDispatch();
        advanceSeconds(30);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
        Request.complete(Request.instances.get(0), true);
        flushDispatch();
        assertEquals("sent", entry("work").status);
        assertEquals(1, Request.attempts);
    }

    @Test public void dailyLimitStopsAlreadyQueuedCommentsFromOtherAuthors() throws Throwable {
        Map<String, Object> limited = baseOptions(false);
        limited.put("comment_daily_limit", "1");
        setOptions(new LocalOptions(limited));
        selectWork();
        holder.work = work("second"); holder.work.author.uid = "300";
        selectWork();
        advanceSeconds(3);
        advanceSeconds(5);
        assertEquals(1, Request.attempts);
        assertEquals("work", Request.models.get(0).y.work.id);
        assertEquals("cancelled", entry("second").status);
    }

    @Test public void cooldownUsesAuthorUidAcrossDifferentWorksAndAllowsOtherAuthors() throws Throwable {
        Map<String, Object> limited = baseOptions(false);
        limited.put("comment_user_cooldown_minutes", "10");
        setOptions(new LocalOptions(limited));
        generate(); flushDispatch();
        holder.work = work("same-author");
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        holder.work = work("other-author"); holder.work.author.uid = "300";
        generate(); flushDispatch();
        assertEquals(2, Request.attempts);
        assertEquals("other-author", Request.models.get(1).y.work.id);
    }

    @Test public void nativeFailureReleasesDailyQuotaAndCooldownForRetry() throws Throwable {
        Map<String, Object> limited = baseOptions(false);
        limited.put("comment_daily_limit", "1");
        limited.put("comment_user_cooldown_minutes", "10");
        setOptions(new LocalOptions(limited));
        Request.failuresRemaining = 1;
        generate(); flushDispatch();
        advanceSeconds(1);
        assertEquals(2, Request.attempts);
        assertEquals("sent", entry("work").status);
        holder.work = work("after-success");
        generate(); flushDispatch();
        assertEquals(2, Request.attempts);
    }

    @Test public void unconfirmedSendKeepsQuotaWithHistoryDisabledAfterLateFailure() throws Throwable {
        Map<String, Object> limited = baseOptions(false);
        limited.put("comment_daily_limit", "1");
        limited.put("comment_save_history", false);
        setOptions(new LocalOptions(limited));
        Request.autoCallback = false;
        generate(); flushDispatch();
        advanceSeconds(30);
        holder.work = work("while-unconfirmed");
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertTrue(history().isEmpty());
        Request.complete(Request.instances.get(0), false); flushDispatch();
        Request.autoCallback = true;
        holder.work = work("after-failure");
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
    }

    @Test public void loweringLimitBeforeNativeSendCancelsPreparedComment() throws Throwable {
        generate(); flushDispatch();
        setOptions(options(true));
        holder.work = work("second");
        generate();
        assertNotNull(notice());
        Map<String, Object> limited = baseOptions(true);
        limited.put("comment_daily_limit", "1");
        setOptions(new LocalOptions(limited));
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("cancelled", entry("second").status);
    }

    @Test public void exceptionAfterHostSubmissionKeepsQuotaAndNeverRetriesUnknownRequest() throws Throwable {
        Map<String, Object> limited = baseOptions(false);
        limited.put("comment_daily_limit", "1");
        setOptions(new LocalOptions(limited));
        Request.autoCallback = false;
        Request.throwAfterSubmit = true;
        generate(); flushDispatch();
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals("submitted", entry("work").status);
        holder.work = work("after-unknown"); holder.work.author.uid = "300";
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        Request.complete(Request.instances.get(0), true); flushDispatch();
        assertEquals("sent", entry("work").status);
    }

    @Test public void automaticHashCommentsRetainTextAndOwnedModelIdentity() throws Throwable {
        Map<String, Object> configured = baseOptions(false);
        configured.put("auto_comment_text", "#文字评论");
        configured.put("comment_tts", true);
        setOptions(new LocalOptions(configured));
        generate(); flushDispatch();
        assertEquals("#文字评论", Request.models.get(0).a);
        assertTrue(hook.ownsPublish(Request.models.get(0)));
        Model unrelated = new Model(); unrelated.a = "#文字评论";
        assertFalse(hook.ownsPublish(unrelated));
        Request.models.get(0).a = "手动内容";
        assertFalse(hook.ownsPublish(Request.models.get(0)));
    }

    @Test public void ownedHashCommentBypassesSpeechConversionBeforeAnyAsyncSynthesis() throws Throwable {
        Map<String, Object> values = baseOptions(false);
        values.put("auto_comment_text", "#文字评论"); values.put("comment_tts", true);
        LocalOptions configured = new LocalOptions(values);
        setOptions(configured);
        generate(); flushDispatch();
        Model owned = Request.models.get(0); owned.A = new Attachment();
        java.lang.reflect.Constructor<NativeCommentSpeechHook> constructor = NativeCommentSpeechHook.class.getDeclaredConstructor(
                Class.class, Class.class, Class.class, Method.class, Method.class, LocalOptions.class, java.util.function.Consumer.class);
        constructor.setAccessible(true);
        NativeCommentSpeechHook speech = constructor.newInstance(Model.class, Attachment.class, Audio.class,
                Model.class.getMethod("copy"), Attachment.class.getMethod("copy"), configured,
                (java.util.function.Consumer<String>) logs::add);
        try {
            speech.setAutomaticPublishGuard(hook::ownsPublish); speech.setContext(activity);
            java.util.concurrent.atomic.AtomicInteger proceeds = new java.util.concurrent.atomic.AtomicInteger();
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                        if ("getArg".equals(method.getName())) return owned;
                        if ("proceed".equals(method.getName())) { proceeds.incrementAndGet(); return true; }
                        if ("getThisObject".equals(method.getName())) return Request.instances.get(0);
                        return Collections.emptyList();
                    });
            Method intercept = NativeCommentSpeechHook.class.getDeclaredMethod("intercept", XposedInterface.Chain.class, Method.class);
            intercept.setAccessible(true);
            assertEquals(Boolean.TRUE, intercept.invoke(speech, chain, Request.class.getMethod("checked", Model.class)));
            assertEquals(1, proceeds.get());
            assertNull(HostTargets.read(speech, "pending"));
            assertEquals("#文字评论", owned.a);
        } finally { speech.close(); }
    }

    @Test public void imagePostsProvideAtMostFourActualMediaUrlsAndRejectMissingImages() {
        Gallery gallery = new Gallery();
        for (int i = 0; i < 5; i++) gallery.images.add(new Picture("https://p3.douyinpic.com/image" + i + ".jpg"));
        List<String> selected = AutoCommentHook.workImages(gallery, options(false));
        assertEquals(4, selected.size());
        assertTrue(selected.get(0).endsWith("image0.jpg"));
        gallery.images.get(0).url_list = Collections.emptyList();
        assertThrows(IllegalArgumentException.class, () -> AutoCommentHook.workImages(gallery, options(false)));
    }

    @Test public void aiCommentSendsBothPromptsToLocalModelAndPublishesItsReply() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            String prose = "对作品的具体观察。".repeat(30) + "\uD83D\uDC4D\u2728";
            server.enqueue(modelResponse(new JSONObject().put("comment", prose).toString()));
            holder.work.title = "实际作品标题";
            setOptions(new LocalOptions(aiOptions(server, false)));
            generate(); flushDispatch();
            RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(recorded);
            assertEquals("/v1/chat/completions", recorded.getPath());
            JSONArray messages = new JSONObject(recorded.getBody().readUtf8()).getJSONArray("messages");
            assertEquals("一级提示词", messages.getJSONObject(0).getString("content"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("二级提示词"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("作品描述"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("作品标题：实际作品标题"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("作品文案：作品描述"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("作者"));
            assertTrue(messages.getJSONObject(1).getString("content").contains("\"comment\""));
            assertEquals(1, Request.attempts);
            assertEquals(prose, Request.models.get(0).a);
            assertEquals(prose, entry("work").output);
            assertEquals("作者", entry("work").targetName);
            assertEquals("sent", entry("work").status);
        }
    }

    @Test public void automaticCommentUsesItsBoundStreamingSupplierWithoutChangingTheGlobalModel() throws Throwable {
        try (MockWebServer global = new MockWebServer(); MockWebServer replies = new MockWebServer();
             MockWebServer comments = new MockWebServer()) {
            Map<String, Object> values = baseOptions(false);
            values.put("comment_mode", "ai");
            values.put(AiModelSettings.KEY, AutomationModelFixtures.profiles(global, replies, comments).serialize());
            values.put(AiModelSettings.REPLY_MODEL_ID, "reply");
            values.put(AiModelSettings.COMMENT_MODEL_ID, "comment");
            comments.enqueue(AutomationModelFixtures.streamed("{\"comment\":\"独立供应商评论\"}"));
            setOptions(new LocalOptions(values)); generate(); flushDispatch();
            RecordedRequest request = comments.takeRequest(2, TimeUnit.SECONDS);
            assertNotNull(request);
            JSONObject body = new JSONObject(request.getBody().readUtf8());
            assertEquals("comment-model", body.getString("model")); assertTrue(body.getBoolean("stream"));
            assertEquals("Bearer comment-key", request.getHeader("Authorization"));
            assertEquals("text/event-stream", request.getHeader("Accept"));
            assertEquals("独立供应商评论", Request.models.get(0).a);
            assertEquals("comment-model", entry("work").modelName); assertEquals("sent", entry("work").status);
            assertEquals(0, global.getRequestCount()); assertEquals(0, replies.getRequestCount());
            assertEquals("global", AiModelSettings.read(new LocalOptions(values)).activeId);
        }
    }

    @Test public void onlyTheCommentSupplierBindingInvalidatesTheCommentQueue() throws Exception {
        try (MockWebServer global = new MockWebServer(); MockWebServer replies = new MockWebServer();
             MockWebServer comments = new MockWebServer()) {
            Map<String, Object> values = aiOptions(global, false);
            values.put(AiModelSettings.KEY, AutomationModelFixtures.profiles(global, replies, comments).serialize());
            setOptions(new LocalOptions(values));
            java.util.concurrent.atomic.AtomicLong epoch = (java.util.concurrent.atomic.AtomicLong) HostTargets.read(hook, "generation");
            long before = epoch.get();
            values.put(AiModelSettings.REPLY_MODEL_ID, "reply"); setOptions(new LocalOptions(values));
            assertEquals("Reply supplier changes must leave comment generation valid", before, epoch.get());
            values.put(AiModelSettings.COMMENT_MODEL_ID, "comment"); setOptions(new LocalOptions(values));
            assertTrue("Comment supplier changes must invalidate earlier comment tasks", epoch.get() > before);
        }
    }

    @Test public void changingGlobalModelKeepsAnIndependentlyBoundCommentRequestAlive() throws Throwable {
        try (MockWebServer global = new MockWebServer(); MockWebServer replies = new MockWebServer();
             MockWebServer comments = new MockWebServer()) {
            java.util.concurrent.CountDownLatch releaseResponse = new java.util.concurrent.CountDownLatch(1);
            MockResponse response = AutomationModelFixtures.streamed("{\"comment\":\"独立模型继续生成\"}");
            comments.setDispatcher(new okhttp3.mockwebserver.Dispatcher() {
                @Override public MockResponse dispatch(RecordedRequest request) throws InterruptedException {
                    if (!releaseResponse.await(5, TimeUnit.SECONDS)) return new MockResponse().setResponseCode(504);
                    return response;
                }
            });
            AiModelSettings profiles = AutomationModelFixtures.profiles(global, replies, comments);
            Map<String, Object> values = baseOptions(false);
            values.put("comment_mode", "ai");
            values.put(AiModelSettings.KEY, profiles.serialize());
            values.put(AiModelSettings.REPLY_MODEL_ID, "reply");
            values.put(AiModelSettings.COMMENT_MODEL_ID, "comment");
            setOptions(new LocalOptions(values));
            selectWork();
            ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
            assertNotNull("The independent comment request must already be in flight", comments.takeRequest(2, TimeUnit.SECONDS));
            java.util.concurrent.atomic.AtomicLong epoch = (java.util.concurrent.atomic.AtomicLong) HostTargets.read(hook, "generation");
            long before = epoch.get();
            try {
                values.put(AiModelSettings.KEY, profiles.activate("reply").serialize());
                setOptions(new LocalOptions(values));
                assertEquals("Changing only the global model must not cancel a pinned comment request", before, epoch.get());
            } finally { releaseResponse.countDown(); }
            awaitGeneration(); flushDispatch();
            assertEquals(1, comments.getRequestCount());
            assertEquals(0, global.getRequestCount());
            assertEquals(0, replies.getRequestCount());
            assertEquals("独立模型继续生成", Request.models.get(0).a);
            assertEquals("sent", entry("work").status);
        }
    }

    @Test public void automaticCommentsNeverUseOrRememberPreviousComments() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"当前作品评论\"}"));
            LocalOptions configured = new LocalOptions(aiOptions(server, false));
            ChatCompletionClient client = (ChatCompletionClient) HostTargets.read(hook, "ai");
            client.rememberReply("comment:100:work", "旧作品输入", Collections.emptyList(),
                    AiModelSettings.read(configured), "旧任务要求", "旧作品评论");
            assertFalse(((Map<?, ?>) HostTargets.read(client, "history")).isEmpty());
            setOptions(configured);
            generate(); flushDispatch();
            RecordedRequest recorded = server.takeRequest(2, TimeUnit.SECONDS);
            JSONArray messages = new JSONObject(recorded.getBody().readUtf8()).getJSONArray("messages");
            assertEquals(2, messages.length());
            assertFalse(messages.toString().contains("旧作品"));
            assertEquals("当前作品评论", Request.models.get(0).a);
            assertTrue(((Map<?, ?>) HostTargets.read(client, "history")).isEmpty());
        }
    }

    @Test public void invalidOrClarifyingModelOutputsNeverReachNativePublisher() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("请提供作品标题和内容，我才能帮你写评论。"));
            server.enqueue(modelResponse(new JSONObject().put("comment", "请提供作品标题和内容。").toString()));
            server.enqueue(modelResponse("这是评论：{\"comment\":\"作品评论\"}"));
            setOptions(new LocalOptions(aiOptions(server, false)));
            generate(); flushDispatch();
            advanceSeconds(1);
            advanceSeconds(2);
            advanceSeconds(30);
            assertEquals(3, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("failed", entry("work").status);
            assertEquals("", entry("work").output);
        }
    }

    @Test public void emptyStructuredCommentSkipsWithoutRetryOrPublication() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"\"}"));
            setOptions(new LocalOptions(aiOptions(server, false)));
            generate(); flushDispatch();
            advanceSeconds(30);
            assertEquals(1, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("cancelled", entry("work").status);
        }
    }

    @Test public void workWithoutDescriptionOrRecognizedMediaSkipsModelRequest() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            holder.work.desc = " \n ";
            setOptions(new LocalOptions(aiOptions(server, false)));
            generate(); flushDispatch();
            advanceSeconds(30);
            assertEquals(0, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("cancelled", entry("work").status);
        }
    }

    @Test public void changingImageRecognitionCancelsPendingPreview() throws Throwable {
        changingRecognitionCancelsPendingPreview("comment_recognize_images", false);
    }

    @Test public void changingVideoRecognitionCancelsPendingPreview() throws Throwable {
        changingRecognitionCancelsPendingPreview("comment_recognize_video", true);
    }

    private void changingRecognitionCancelsPendingPreview(String key, boolean value) throws Throwable {
        setOptions(options(true));
        generate();
        assertNotNull(notice());
        Map<String, Object> next = baseOptions(true);
        next.put(key, value);
        setOptions(new LocalOptions(next));
        flushDispatch();
        assertEquals(0, Request.attempts);
        assertTrue(history().stream().anyMatch(item -> "work".equals(item.targetId) && "cancelled".equals(item.status)));
        advanceSeconds(3);
        advanceSeconds(8);
        assertEquals(1, Request.attempts);
    }

    @Test public void fieldOnlyHostWorkAndAuthorAreReadWithoutSyntheticGetters() {
        assertThrows(NoSuchMethodException.class, () -> Work.class.getMethod("getDesc"));
        assertThrows(NoSuchMethodException.class, () -> Work.class.getMethod("getAuthor"));
        assertEquals("作品作者：作者\n作品标题：作品描述\n作品文案：作品描述", AutoCommentHook.workInput(holder.work));
    }

    @Test public void commentWhitelistKeywordMustMatchASelectedWorkFieldBeforeQueueing() throws Throwable {
        Map<String, Object> scope = baseOptions(true);
        scope.put("comment_scope_mode", "whitelist");
        scope.put("comment_scope_fields", "caption");
        scope.put("comment_scope_keywords", "白茶,乌龙");
        setOptions(new LocalOptions(scope));
        selectWork();
        flushDispatch();
        assertNull("nonmatching works must not enter the comment queue", notice());
        assertEquals(0, Request.attempts);
    }

    @Test public void commentBlacklistMatchStopsWorkBeforeMediaOrGeneration() throws Throwable {
        Map<String, Object> scope = baseOptions(true);
        scope.put("comment_scope_mode", "blacklist");
        scope.put("comment_scope_fields", "caption,title");
        scope.put("comment_scope_keywords", "作品描述");
        setOptions(new LocalOptions(scope));
        selectWork();
        flushDispatch();
        assertNull(notice());
        assertEquals(0, Request.attempts);
    }

    @Test public void matchingCommentKeywordsCannotEscapeTheSpecifiedAuthorWhitelist() throws Throwable {
        Map<String, Object> scope = baseOptions(true);
        scope.put("comment_scope_mode", "whitelist");
        scope.put("comment_scope_users", "999");
        scope.put("comment_filter_mode", "include");
        scope.put("comment_filter_keywords", "作品描述");
        scope.put("comment_filter_fields", "caption");
        setOptions(new LocalOptions(scope));
        selectWork();
        flushDispatch();
        assertNull(notice());
        assertEquals(0, Request.attempts);
    }

    @Test public void fetchedWhitelistControlsQueueWithoutAnyAuthorRelationFields() throws Throwable {
        FollowListSnapshot.activateAccount("100");
        FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                Collections.emptySet());
        Map<String, Object> scope = baseOptions(true);
        scope.put("comment_scope_mode", "whitelist");
        scope.put("comment_scope_relations", "following");
        setOptions(new LocalOptions(scope));
        selectWork();
        flushDispatch();
        assertNull(notice());
        FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("200"));
        selectWork();
        flushDispatch();
        assertNotNull(notice());
        assertEquals(0, Request.attempts);
    }

    @Test public void refreshedWhitelistCancelsQueuedCommentBeforeNativePublish() throws Throwable {
        FollowListSnapshot.activateAccount("100");
        FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                Collections.singleton("200"));
        Map<String, Object> scope = baseOptions(false);
        scope.put("comment_scope_mode", "whitelist");
        scope.put("comment_scope_relations", "following");
        setOptions(new LocalOptions(scope));
        selectWork();
        FollowListSnapshot.publish(FollowListSnapshot.beginRefresh("100", FollowListSnapshot.Kind.FOLLOWING),
                Collections.emptySet());
        advanceSeconds(3);
        assertEquals(0, Request.attempts);
        assertEquals("cancelled", entry("work").status);
    }

    @Test public void getterOnlyModelRemainsCompatibleWhenSerializedFieldsAreUnavailable() {
        assertEquals("作品作者：旧布局作者\n作品标题：旧布局标题\n作品文案：旧布局标题",
                AutoCommentHook.workInput(new GetterOnlyWork()));
    }

    @Test public void voiceCommentPublishesAudioWithEmptyTextAndCleansOnAcknowledgment() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> {
            assertEquals("固定评论", text);
            assertEquals(Long.MAX_VALUE, maximum);
            assertTrue(valid.getAsBoolean());
            return new SpeechPreparation.Audio(audio, 1200);
        });
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertVoiceModel(Request.models.get(0), audio);
        assertEquals("sent", entry("work").status);
        assertEquals("voice", entry("work").deliveryMode);
        assertFalse(audio.exists());
    }

    @Test public void textFallbackKeepsItsOwnThreePublishAttemptsAfterVoiceFailures() throws Throwable {
        java.io.File audio = voiceFile();
        int[] preparations = {0};
        useVoicePreparer((context, text, options, maximum, valid) -> {
            preparations[0]++;
            return new SpeechPreparation.Audio(audio, 1200);
        });
        Request.failuresRemaining = 5;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        advanceSeconds(1); advanceSeconds(2); advanceSeconds(3); advanceSeconds(1); advanceSeconds(2);
        assertEquals(6, Request.attempts);
        assertEquals(1, preparations[0]);
        for (int index = 0; index < 3; index++) assertVoiceModel(Request.models.get(index), audio);
        for (int index = 3; index < 6; index++) {
            assertEquals("固定评论", Request.models.get(index).a);
            assertNull(Request.models.get(index).A);
        }
        assertEquals("sent", entry("work").status);
        assertEquals("text", entry("work").deliveryMode);
        assertFalse(audio.exists());
    }

    @Test public void longFixedVoiceCommentPublishesCompleteSpeechWithoutTextFallback() throws Throwable {
        String prose = "作品细节值得认真欣赏。".repeat(120) + "\n\uD83D\uDC4D";
        assertTrue(prose.length() > 1000);
        java.io.File audio = voiceFile();
        long duration = 120_001;
        useVoicePreparer((context, text, options, maximum, valid) -> {
            assertEquals(prose, text);
            assertEquals(Long.MAX_VALUE, maximum);
            assertTrue(valid.getAsBoolean());
            return new SpeechPreparation.Audio(audio, duration);
        });
        Map<String, Object> values = baseOptions(false);
        values.put("auto_comment_text", prose);
        values.put(LocalOptions.COMMENT_VOICE, true);
        setOptions(new LocalOptions(values));
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertVoiceModel(Request.models.get(0), audio, duration);
        assertEquals(prose, entry("work").output);
        assertEquals("sent", entry("work").status);
        assertFalse(audio.exists());
    }

    @Test public void longAiVoiceCommentKeepsFullJsonContentAndPublishesOnlyAudio() throws Throwable {
        String prose = "作品细节值得认真欣赏。".repeat(120) + "\n\uD83D\uDC4D";
        String output = new JSONObject().put("comment", prose).toString();
        assertTrue(prose.length() > 1000);
        assertTrue(output.length() <= 4000);
        java.io.File audio = voiceFile();
        long duration = 120_001;
        useVoicePreparer((context, text, options, maximum, valid) -> {
            assertEquals(prose, text);
            assertEquals(Long.MAX_VALUE, maximum);
            assertTrue(valid.getAsBoolean());
            return new SpeechPreparation.Audio(audio, duration);
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse(output));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch();
            assertEquals(1, server.getRequestCount());
            assertEquals(1, Request.attempts);
            assertVoiceModel(Request.models.get(0), audio, duration);
            assertEquals("sent", entry("work").status);
            assertFalse(audio.exists());
        }
    }

    @Test public void voiceCommentStillUsesTheOriginalAiOutputLimit() throws Throwable {
        String prose = "作品细节值得认真欣赏。".repeat(400);
        String output = new JSONObject().put("comment", prose).toString();
        assertTrue(output.length() > 4000);
        useVoicePreparer((context, text, options, maximum, valid) -> {
            throw new AssertionError("Overlong AI output must not enter synthesis");
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse(output));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch();
            assertEquals(1, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertTrue(logs.stream().anyMatch(value -> value.contains("generation failed")
                    && value.contains("stage=model_request")));
        }
    }

    @Test public void failedVoicePublishReusesPreparedAudioAcrossNativeRetries() throws Throwable {
        java.io.File audio = voiceFile();
        int[] preparations = {0};
        useVoicePreparer((context, text, options, maximum, valid) -> {
            preparations[0]++;
            return new SpeechPreparation.Audio(audio, 1200);
        });
        Request.failuresRemaining = 2;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertTrue(audio.exists());
        advanceSeconds(1);
        assertEquals(2, Request.attempts);
        assertTrue(audio.exists());
        advanceSeconds(2);
        assertEquals(3, Request.attempts);
        assertEquals(1, preparations[0]);
        for (Model model : Request.models) assertVoiceModel(model, audio);
        assertEquals("sent", entry("work").status);
        assertFalse(audio.exists());
    }

    @Test public void threeVoiceGenerationFailuresFallBackToTheSameAiCommentAsText() throws Throwable {
        List<String> synthesized = new ArrayList<>();
        useVoicePreparer((context, text, options, maximum, valid) -> {
            synthesized.add(text);
            throw new IllegalStateException("voice unavailable");
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"同一份作品评论\"}"));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch();
            assertEquals(1, synthesized.size());
            assertEquals(0, Request.attempts);
            advanceSeconds(1);
            assertEquals(2, synthesized.size());
            assertEquals(0, Request.attempts);
            advanceSeconds(2); advanceSeconds(10);
            assertEquals(Arrays.asList("同一份作品评论", "同一份作品评论", "同一份作品评论"), synthesized);
            assertEquals(1, server.getRequestCount());
            assertEquals(1, Request.attempts);
            assertEquals("同一份作品评论", Request.models.get(0).a);
            assertNull(Request.models.get(0).A);
            assertEquals("sent", entry("work").status);
            assertEquals("text", entry("work").deliveryMode);
        }
    }

    @Test public void threeConfirmedVoicePublishFailuresFallBackToTextWithoutResynthesizing() throws Throwable {
        java.io.File audio = voiceFile();
        int[] preparations = {0};
        useVoicePreparer((context, text, options, maximum, valid) -> {
            preparations[0]++;
            return new SpeechPreparation.Audio(audio, 1200);
        });
        Request.asyncAudioUpload = true;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        Request.complete(Request.instances.get(0), false); flushDispatch();
        advanceSeconds(1);
        Request.complete(Request.instances.get(1), false); flushDispatch();
        advanceSeconds(2);
        Request.complete(Request.instances.get(2), false); flushDispatch();
        advanceSeconds(10);
        assertEquals(4, Request.attempts);
        assertEquals(3, AudioUploader.calls);
        assertEquals(1, preparations[0]);
        for (int index = 0; index < 3; index++) assertVoiceModel(Request.models.get(index), audio);
        assertEquals("固定评论", Request.models.get(3).a);
        assertNull(Request.models.get(3).A);
        assertEquals("sent", entry("work").status);
        assertEquals("text", entry("work").deliveryMode);
        assertFalse(audio.exists());
    }

    @Test public void aiFailuresDoNotConsumeTheThreeSpeechAttemptsBeforeTextFallback() throws Throwable {
        List<String> synthesized = new ArrayList<>();
        useVoicePreparer((context, text, options, maximum, valid) -> {
            synthesized.add(text);
            throw new IllegalStateException("voice unavailable");
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(new MockResponse().setResponseCode(503));
            server.enqueue(modelResponse("{\"comment\":\"同一份作品评论\"}"));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch();
            advanceSeconds(1); advanceSeconds(2);
            assertEquals(1, synthesized.size());
            assertEquals(0, Request.attempts);
            advanceSeconds(3);
            assertEquals(2, synthesized.size());
            assertEquals(0, Request.attempts);
            advanceSeconds(1);
            assertEquals(3, synthesized.size());
            assertEquals(0, Request.attempts);
            advanceSeconds(2);
            assertEquals(1, Request.attempts);
            assertEquals(3, server.getRequestCount());
            assertEquals("同一份作品评论", Request.models.get(0).a);
            assertEquals("text", entry("work").deliveryMode);
            assertEquals("sent", entry("work").status);
        }
    }

    @Test public void textFallbackSubmitsTheCompleteLongSpeechTextForHostValidation() throws Throwable {
        String prose = "作品细节值得认真欣赏。".repeat(120);
        assertTrue(prose.length() > 1000);
        int[] preparations = {0};
        useVoicePreparer((context, text, options, maximum, valid) -> {
            assertEquals(prose, text);
            preparations[0]++;
            throw new IllegalStateException("voice unavailable");
        });
        Map<String, Object> values = baseOptions(false);
        values.put(LocalOptions.COMMENT_VOICE, true);
        values.put("auto_comment_text", prose);
        setOptions(new LocalOptions(values));
        generate(); flushDispatch();
        advanceSeconds(1); advanceSeconds(2); advanceSeconds(3);
        assertEquals(3, preparations[0]);
        assertEquals(1, Request.attempts);
        assertEquals(prose, Request.models.get(0).a);
        assertNull(Request.models.get(0).A);
        assertEquals("text", entry("work").deliveryMode);
        assertEquals("sent", entry("work").status);
    }

    @Test public void disabledVoiceCommentPublishesOnlyTextAndRecordsTextMode() throws Throwable {
        useVoicePreparer((context, text, options, maximum, valid) -> {
            throw new AssertionError("Disabled voice comments must not synthesize");
        });
        setOptions(options(false));
        generate(); flushDispatch();
        assertEquals(1, Request.attempts);
        assertEquals(0, AudioUploader.calls);
        assertEquals("固定评论", Request.models.get(0).a);
        assertNull(Request.models.get(0).A);
        assertEquals("sent", entry("work").status);
        assertEquals("text", entry("work").deliveryMode);
    }

    @Test public void emptyAiVoiceCommentNeverFallsBackOrSynthesizes() throws Throwable {
        useVoicePreparer((context, text, options, maximum, valid) -> {
            throw new AssertionError("Empty AI comments must not synthesize");
        });
        try (MockWebServer server = new MockWebServer()) {
            server.enqueue(modelResponse("{\"comment\":\"\"}"));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch(); advanceSeconds(10);
            assertEquals(1, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("", entry("work").output);
        }
    }

    @Test public void aiVoiceGenerationFailureWithoutCommentNeverFallsBackToText() throws Throwable {
        useVoicePreparer((context, text, options, maximum, valid) -> {
            throw new AssertionError("AI failures without text must not synthesize");
        });
        try (MockWebServer server = new MockWebServer()) {
            for (int index = 0; index < 3; index++) server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            Map<String, Object> values = aiOptions(server, false);
            values.put(LocalOptions.COMMENT_VOICE, true);
            setOptions(new LocalOptions(values));
            generate(); flushDispatch();
            advanceSeconds(1); advanceSeconds(2); advanceSeconds(10);
            assertEquals(3, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("failed", entry("work").status);
            assertEquals("", entry("work").output);
        }
    }

    @Test public void cancellingVoicePreviewDeletesAudioBeforeNativePublication() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        setOptions(voiceOptions(true));
        generate(); flushDispatch();
        assertEquals(0, Request.attempts);
        assertTrue(audio.exists());
        notice().performLongClick(); flushDispatch();
        assertEquals("cancelled", entry("work").status);
        assertFalse(audio.exists());
        assertEquals(0, Request.attempts);
    }

    @Test public void unknownVoiceSubmissionRetainsAudioUntilLateNativeCallback() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        Request.autoCallback = false;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        advanceSeconds(31);
        assertEquals("submitted", entry("work").status);
        assertTrue(audio.exists());
        assertEquals(1, Request.attempts);
        assertEquals("voice", entry("work").deliveryMode);
        advanceSeconds(20);
        assertEquals(1, Request.attempts);
        Request.complete(Request.instances.get(0), true); flushDispatch();
        assertEquals("sent", entry("work").status);
        assertEquals("voice", entry("work").deliveryMode);
        assertFalse(audio.exists());
    }

    @Test public void asynchronousVoiceUploadReturningFalsePublishesOnlyOnceAndRecordsSuccess() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        Request.asyncAudioUpload = true;
        setOptions(voiceOptions(true));
        generate(); advanceSeconds(8);
        advanceSeconds(2);
        assertEquals(1, Request.attempts);
        assertEquals(1, AudioUploader.calls);
        assertTrue(audio.exists());
        assertEquals("publishing", ((CommentRingView) notice()).getPhase());
        assertNotEquals("failed", entry("work").status);
        assertVoiceModel(Request.models.get(0), audio);
        Request.complete(Request.instances.get(0), true); flushDispatch();
        assertEquals("sent", entry("work").status);
        assertNull(failureNotice());
        assertFalse(audio.exists());
        selectWork(); advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals(1, AudioUploader.calls);
        assertEquals(1, history().size());
    }

    @Test public void asynchronousVoiceUploadTimeoutRemainsSubmittedAndLateSuccessUpdatesHistory() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        Request.asyncAudioUpload = true;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        advanceSeconds(31);
        assertEquals(1, Request.attempts);
        assertEquals(1, AudioUploader.calls);
        assertEquals("submitted", entry("work").status);
        assertTrue(audio.exists());
        assertNull(failureNotice());
        assertEquals("voice", entry("work").deliveryMode);
        advanceSeconds(20);
        assertEquals(1, Request.attempts);
        assertEquals(1, AudioUploader.calls);
        Request.complete(Request.instances.get(0), true); flushDispatch();
        assertEquals("sent", entry("work").status);
        assertEquals("voice", entry("work").deliveryMode);
        assertFalse(audio.exists());
        selectWork(); advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals(1, history().size());
    }

    @Test public void rejectedVoiceRequestThenRejectedTextExhaustsThreeAttemptsForEachMode() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        Request.reject = true;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        advanceSeconds(1); advanceSeconds(2); advanceSeconds(3); advanceSeconds(1); advanceSeconds(2);
        assertEquals(6, Request.attempts);
        assertEquals(0, AudioUploader.calls);
        for (int index = 0; index < 3; index++) assertVoiceModel(Request.models.get(index), audio);
        for (int index = 3; index < 6; index++) {
            assertEquals("固定评论", Request.models.get(index).a);
            assertNull(Request.models.get(index).A);
        }
        assertEquals("failed", entry("work").status);
        assertEquals("text", entry("work").deliveryMode);
        assertFalse(audio.exists());
        assertFalse(activity.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE)
                .contains("comment:100:work"));
    }

    @Test public void immediateSuccessDuringVoiceUploadReturningFalseIsNotRetried() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        Request.asyncAudioUpload = true;
        Request.immediateAudioSuccess = true;
        setOptions(voiceOptions(false));
        generate(); flushDispatch();
        advanceSeconds(10);
        assertEquals(1, Request.attempts);
        assertEquals(1, AudioUploader.calls);
        assertEquals("sent", entry("work").status);
        assertFalse(audio.exists());
    }

    @Test public void realAsynchronousVoiceFailureReleasesQuotaAndRetriesPreparedAudio() throws Throwable {
        java.io.File audio = voiceFile();
        int[] preparations = {0};
        useVoicePreparer((context, text, options, maximum, valid) -> {
            preparations[0]++;
            return new SpeechPreparation.Audio(audio, 1200);
        });
        Request.asyncAudioUpload = true;
        Map<String, Object> values = baseOptions(false);
        values.put(LocalOptions.COMMENT_VOICE, true);
        values.put("comment_daily_limit", "1");
        setOptions(new LocalOptions(values));
        generate(); flushDispatch();
        Request.complete(Request.instances.get(0), false); flushDispatch();
        advanceSeconds(1);
        assertEquals(2, Request.attempts);
        assertTrue(audio.exists());
        Request.complete(Request.instances.get(1), true); flushDispatch();
        advanceSeconds(10);
        assertEquals(2, Request.attempts);
        assertEquals(2, AudioUploader.calls);
        assertEquals(1, preparations[0]);
        assertEquals("sent", entry("work").status);
        assertFalse(audio.exists());
    }

    @Test public void voiceConfigurationChangeCancelsPreparedComment() throws Throwable {
        java.io.File audio = voiceFile();
        useVoicePreparer((context, text, options, maximum, valid) -> new SpeechPreparation.Audio(audio, 1200));
        setOptions(voiceOptions(true));
        generate(); flushDispatch();
        Map<String, Object> changed = baseOptions(true);
        changed.put(LocalOptions.COMMENT_VOICE, true);
        changed.put("tts_selected_voice", "2");
        setOptions(new LocalOptions(changed)); flushDispatch();
        assertFalse(audio.exists());
        assertEquals(0, Request.attempts);
    }

    private void useVoicePreparer(SpeechPreparation.Preparer preparer) throws Exception {
        HostTargets.field(AutoCommentHook.class, "speechPreparer").set(hook, preparer);
    }

    private java.io.File voiceFile() throws Exception {
        java.io.File file = java.io.File.createTempFile("comment-voice", ".m4a", activity.getCacheDir());
        java.nio.file.Files.write(file.toPath(), new byte[]{1, 2, 3});
        return file;
    }

    private static LocalOptions voiceOptions(boolean tip) {
        Map<String, Object> values = baseOptions(tip);
        values.put(LocalOptions.COMMENT_VOICE, true);
        return new LocalOptions(values);
    }

    private static void assertVoiceModel(Model model, java.io.File file) {
        assertVoiceModel(model, file, 1200);
    }

    private static void assertVoiceModel(Model model, java.io.File file, long duration) {
        assertEquals("", model.a);
        assertTrue(model.d.isEmpty());
        assertNotNull(model.A);
        assertEquals("", model.A.text.toString());
        Audio audio = (Audio) model.A.audio;
        assertEquals(file, audio.file);
        assertEquals(Long.valueOf(duration), audio.duration);
    }

    @Test public void serializedHostFieldsTakePriorityOverStaleGetterValues() {
        assertEquals("作品作者：字段作者\n作品标题：字段标题\n作品文案：字段标题",
                AutoCommentHook.workInput(new MixedWork()));
    }

    @Test public void titleFieldsHaveStablePriorityAndStaySeparateFromCopy() {
        holder.work.title = "主标题";
        holder.work.item_title = "项目标题";
        holder.work.preview_title = "预览标题";
        assertEquals("作品作者：作者\n作品标题：主标题\n作品文案：作品描述", AutoCommentHook.workInput(holder.work));
        holder.work.title = " ";
        assertEquals("作品作者：作者\n作品标题：项目标题\n作品文案：作品描述", AutoCommentHook.workInput(holder.work));
        holder.work.item_title = null;
        assertEquals("作品作者：作者\n作品标题：预览标题\n作品文案：作品描述", AutoCommentHook.workInput(holder.work));
    }

    @Test public void aiGenerationFailureRetriesThreeTimesThenRunsTheNextOriginalWork() throws Throwable {
        try (MockWebServer server = new MockWebServer()) {
            for (int i = 0; i < 3; i++) server.enqueue(new MockResponse().setResponseCode(503).setBody("unavailable"));
            server.enqueue(modelResponse("{\"comment\":\"下一作品评论\"}"));
            setOptions(new LocalOptions(aiOptions(server, false)));
            selectWork();
            holder.work = work("another"); selectWork();
            advanceSeconds(3);
            advanceSeconds(1);
            advanceSeconds(2);
            assertEquals(3, server.getRequestCount());
            assertEquals(0, Request.attempts);
            assertEquals("failed", entry("work").status);
            advanceSeconds(3);
            assertEquals(4, server.getRequestCount());
            assertEquals(1, Request.attempts);
            assertEquals("another", Request.models.get(0).y.work.id);
            assertEquals("下一作品评论", Request.models.get(0).a);
            assertEquals("sent", entry("another").status);
        }
    }

    private void generate() throws Throwable {
        selectWork();
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(3));
        awaitGeneration();
        ShadowLooper.idleMainLooper();
    }

    private void selectWork() throws Throwable {
        framework.invoke(Holder.class.getMethod("selected", int.class), holder, 0);
        ShadowLooper.idleMainLooper();
    }

    private void advanceSeconds(long seconds) throws Exception {
        ShadowLooper.getShadowMainLooper().idleFor(Duration.ofSeconds(seconds));
        awaitGeneration();
        flushDispatch();
    }

    private void awaitGeneration() throws Exception {
        ScheduledThreadPoolExecutor worker = (ScheduledThreadPoolExecutor) HostTargets.read(hook, "worker");
        worker.submit(() -> { }).get(5, TimeUnit.SECONDS);
    }

    private void flushDispatch() {
        for (int i = 0; i < 6; i++) {
            ShadowLooper.idleMainLooper();
            dispatchWorker.runAll();
            try {
                java.util.concurrent.ThreadPoolExecutor io = (java.util.concurrent.ThreadPoolExecutor)
                        HostTargets.read(hook, "RESULTS_IO");
                io.submit(() -> { }).get(5, TimeUnit.SECONDS);
            } catch (Exception failure) { throw new AssertionError(failure); }
        }
    }

    private void setOptions(LocalOptions next) {
        dispatcher.setOptions(next);
        hook.setOptions(next);
        ShadowLooper.idleMainLooper();
    }

    private List<AutomationHistoryStore.Entry> history() { return new AutomationHistoryStore(activity).read("comment"); }
    private AutomationHistoryStore.Entry entry(String aid) {
        return history().stream().filter(value -> aid.equals(value.targetId)).findFirst().orElseThrow(AssertionError::new);
    }
    private View notice() { return activity.getWindow().getDecorView().findViewWithTag("automation.comment.ring"); }
    private View failureNotice() {
        View ring = notice();
        return ring instanceof CommentRingView && "failed".equals(((CommentRingView) ring).getPhase()) ? ring : null;
    }
    private static View taggedView(View view, String prefix) {
        Object tag = view.getTag();
        if (tag instanceof String && ((String) tag).startsWith(prefix)) return view;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = taggedView(group.getChildAt(i), prefix);
                if (child != null) return child;
            }
        }
        return null;
    }
    private static Work work(String aid) { Work work = new Work(); work.id = aid; return work; }
    private static LocalOptions options(boolean tip) { return new LocalOptions(baseOptions(tip)); }
    private static Map<String, Object> baseOptions(boolean tip) {
        Map<String, Object> values = new HashMap<>();
        values.put("automation_enabled", true); values.put("native_auto_comment", true);
        values.put("comment_mode", "fixed"); values.put("auto_comment_text", "固定评论");
        values.put("comment_save_history", true); values.put("comment_tip", tip);
        return values;
    }
    private static Map<String, Object> aiOptions(MockWebServer server, boolean tip) throws Exception {
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "test")
                .put("name", "Test profile").put("provider", "test").put("protocol", AiModelConfig.CHAT)
                .put("apiEndpoint", server.url("/v1").toString()).put("modelName", "model-a")
                .put("apiKey", "test-key").put("allowInsecureHttp", true).put("systemPrompt", "一级提示词"));
        Map<String, Object> values = baseOptions(tip);
        values.put("comment_mode", "ai"); values.put("comment_prompt", "二级提示词");
        values.put(AiModelSettings.KEY, new AiModelSettings(Collections.singletonList(config), config.id).serialize());
        return values;
    }

    private static MockResponse modelResponse(String content) throws Exception {
        return new MockResponse().addHeader("Content-Type", "application/json")
                .setBody(new JSONObject().put("choices", new JSONArray().put(
                        new JSONObject().put("message", new JSONObject().put("content", content)))).toString());
    }

    public static final class Work {
        public String id = "work";
        public String desc = "作品描述";
        public String title;
        public String item_title;
        public String preview_title;
        public List<Picture> images;
        public Author author = new Author();
        public int aidReads;
        public String getAid() { aidReads++; return id; }
        public boolean getIsAdAweme() { return false; }
    }
    public static final class Author { public String uid = "200"; public String nickname = "作者"; }
    public static class GetterOnlyWork {
        public String getDesc() { return "旧布局标题"; }
        public GetterOnlyAuthor getAuthor() { return new GetterOnlyAuthor(); }
    }
    public static final class GetterOnlyAuthor { public String getNickname() { return "旧布局作者"; } }
    public static final class MixedWork extends GetterOnlyWork {
        public String desc = "字段标题";
        public Author author = new Author();
        public MixedWork() { author.nickname = "字段作者"; }
    }
    public static final class Gallery { public List<Picture> images = new ArrayList<>(); }
    public static final class Picture { public List<String> url_list; Picture(String url) { url_list = Collections.singletonList(url); } }
    public static final class Holder {
        public View z;
        public Work work = new Work();
        public int awemeReads;
        public Work getAweme() { awemeReads++; return work; }
        public void selected(int index) { }
    }
    public static final class PhotoHolder { public FrameLayout f; public Work getAweme() { return new Work(); } public void selected() { } }
    public static final class Player { public void playing(String aid) { } }
    public static final class ContextState { public Work work; }
    public static final class Resolver {
        public static Work work(ContextState state) { return state.work; }
        public static ContextState context(Work work) { ContextState state = new ContextState(); state.work = work; return state; }
    }
    public static final class Model {
        public String a; public ContextState y; public String n; public Attachment A;
        public List<Object> d = Collections.emptyList();
        public Model copy() { Model result = new Model(); result.a = a; result.y = y; result.n = n; result.A = A; return result; }
    }
    public static final class Attachment {
        public Object audio; public List<Object> imageList = Collections.emptyList();
        public CharSequence text = "";
        public Attachment copy() { return new Attachment(); }
    }
    public static final class Audio {
        public final java.io.File file;
        public final Long duration;
        public Audio(java.io.File file, List<Object> wave, Long duration) { this.file = file; this.duration = duration; }
    }
    public static final class Comment { public String cid = "comment-id"; public String awemeId; }
    public static final class Response { public int status_code; public Comment comment = new Comment(); }
    public static final class UploadCallback {
        public final Model l0;
        public final Request l1;
        public UploadCallback(Request request, Model model) { l0 = model; l1 = request; }
    }
    public static final class AudioUploader {
        static int calls;
        public static void upload(Object state, String path, UploadCallback callback, Object listener) { calls++; }
    }
    public static final class Request {
        static Framework framework;
        static int attempts;
        static int failuresRemaining;
        static boolean autoCallback;
        static boolean reject;
        static boolean throwAfterSubmit;
        static boolean asyncAudioUpload;
        static boolean immediateAudioSuccess;
        static Runnable beforeSubmit;
        static final List<Model> models = new ArrayList<>();
        static final List<Request> instances = new ArrayList<>();
        static void reset(Framework selected) {
            framework = selected; attempts = 0; failuresRemaining = 0; autoCallback = true; reject = false;
            throwAfterSubmit = false;
            asyncAudioUpload = false; AudioUploader.calls = 0;
            immediateAudioSuccess = false;
            beforeSubmit = null;
            models.clear(); instances.clear();
        }
        public boolean checked(Model model) {
            if (beforeSubmit != null) beforeSubmit.run();
            attempts++; models.add(model); instances.add(this);
            if (reject) return false;
            if (asyncAudioUpload && model.A != null && model.A.audio instanceof Audio) {
                try {
                    framework.invoke(AudioUploader.class.getMethod("upload", Object.class, String.class,
                                    UploadCallback.class, Object.class), null, model.y,
                            ((Audio) model.A.audio).file.getAbsolutePath(), new UploadCallback(this, model), null);
                } catch (Throwable failure) { throw new AssertionError(failure); }
                if (immediateAudioSuccess) complete(this, true);
                return false;
            }
            if (throwAfterSubmit) throw new IllegalStateException("Host threw after starting submission");
            if (autoCallback) {
                boolean success = failuresRemaining == 0;
                if (!success) failuresRemaining--;
                complete(this, success);
            }
            return true;
        }
        static void complete(Request request, boolean success) {
            try {
                if (success) {
                    Response response = new Response();
                    response.comment.awemeId = models.get(instances.indexOf(request)).y.work.id;
                    framework.invoke(Request.class.getMethod("success", Response.class), request, response);
                } else framework.invoke(Request.class.getMethod("failed", Exception.class), request, new Exception("fixture"));
            } catch (Throwable failure) { throw new AssertionError(failure); }
        }
        public void success(Response response) { }
        public void failed(Exception failure) { }
    }

    private static HostScanReport report() throws Exception {
        List<RuleScanResult> rules = new ArrayList<>();
        add(rules, "feed_holder_selected", Holder.class.getMethod("selected", int.class));
        add(rules, "image_holder_page_selected", PhotoHolder.class.getMethod("selected"));
        add(rules, "portrait_comment_aweme_resolver", Resolver.class.getMethod("work", ContextState.class));
        add(rules, "auto_comment_work_context", Resolver.class.getMethod("context", Work.class));
        add(rules, "auto_comment_checked_request", Request.class.getMethod("checked", Model.class));
        add(rules, "auto_comment_publish_success", Request.class.getMethod("success", Response.class));
        add(rules, "auto_comment_publish_failure", Request.class.getMethod("failed", Exception.class));
        add(rules, "auto_comment_audio_upload", AudioUploader.class.getMethod("upload", Object.class, String.class,
                UploadCallback.class, Object.class));
        rules.add(new RuleScanResult("comment_audio_model", "comment", Collections.singletonList(new HostCandidate(
                "fixture.apk", "classes.dex", HostTargets.descriptor(Audio.class), null, null))));
        return new HostScanReport("fixture", rules, false);
    }

    private static void add(List<RuleScanResult> rules, String id, Method method) {
        rules.add(new RuleScanResult(id, "comment", Collections.singletonList(new HostCandidate("fixture.apk", "classes.dex",
                HostTargets.descriptor(method.getDeclaringClass()), method.getName(), HostTargets.descriptor(method)))));
    }

    private static final class Framework {
        final Map<Executable, XposedInterface.Hooker> hooks = new HashMap<>();
        final XposedInterface api = (XposedInterface) Proxy.newProxyInstance(XposedInterface.class.getClassLoader(),
                new Class<?>[]{XposedInterface.class}, (proxy, method, arguments) -> {
                    if (!"hook".equals(method.getName())) return null;
                    Executable target = (Executable) arguments[0];
                    return Proxy.newProxyInstance(XposedInterface.HookBuilder.class.getClassLoader(),
                            new Class<?>[]{XposedInterface.HookBuilder.class}, (builder, call, values) -> {
                                if (!"intercept".equals(call.getName())) return builder;
                                hooks.put(target, (XposedInterface.Hooker) values[0]);
                                return Proxy.newProxyInstance(XposedInterface.HookHandle.class.getClassLoader(),
                                        new Class<?>[]{XposedInterface.HookHandle.class}, (handle, action, unused) -> null);
                            });
                });
        Object invoke(Method target, Object receiver, Object... input) throws Throwable {
            XposedInterface.Hooker hooker = hooks.get(target);
            if (hooker == null) return target.invoke(receiver, input);
            XposedInterface.Chain chain = (XposedInterface.Chain) Proxy.newProxyInstance(XposedInterface.Chain.class.getClassLoader(),
                    new Class<?>[]{XposedInterface.Chain.class}, (proxy, method, arguments) -> {
                        switch (method.getName()) {
                            case "getThisObject": return receiver;
                            case "getArg": return input[(Integer) arguments[0]];
                            case "getArgs": return Arrays.asList(input);
                            case "proceed": return target.invoke(receiver, arguments == null || arguments.length == 0 ? input : (Object[]) arguments[0]);
                            default: throw new UnsupportedOperationException(method.getName());
                        }
                    });
            return hooker.intercept(chain);
        }
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final Queue<Runnable> tasks = new ArrayDeque<>(); boolean stopped;
        @Override public void execute(Runnable task) { tasks.add(task); }
        void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public void shutdown() { stopped = true; }
        @Override public List<Runnable> shutdownNow() { stopped = true; return Collections.emptyList(); }
        @Override public boolean isShutdown() { return stopped; }
        @Override public boolean isTerminated() { return stopped; }
        @Override public boolean awaitTermination(long time, TimeUnit unit) { return stopped; }
    }
}
