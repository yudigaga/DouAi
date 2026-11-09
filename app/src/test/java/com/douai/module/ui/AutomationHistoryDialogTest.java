package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.view.View;
import android.widget.ListView;

import com.douai.module.config.AutomationHistoryStore;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLooper;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationHistoryDialogTest {
    private Activity activity;
    private AutomationHistoryStore store;
    private ManualExecutor worker;
    private AutomationHistoryDialog page;

    @Before public void setUp() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        activity.getSharedPreferences("douai_automation_history", Activity.MODE_PRIVATE).edit().clear().commit();
        store = new AutomationHistoryStore(activity);
        worker = new ManualExecutor();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.REPLY, false, worker);
    }

    @After public void tearDown() {
        if (page != null) page.dialog().dismiss();
        worker.shutdownNow();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void anOldCommentRecordOffersTheVerifiedWorkFallbackAction() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("old-comment", "comment", "123456", "作者",
                "作品", "评论", "模型", "sent", 1, 1));
        page.dialog().dismiss();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.COMMENT, false, worker);
        page.dialog().show();
        worker.runNextOnThread(); ShadowLooper.idleMainLooper();
        list().getAdapter().getView(0, null, list()).performClick();
        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertEquals("查看作品", detail.getButton(AlertDialog.BUTTON_POSITIVE).getText().toString());
        assertTrue(containsText(detail.getWindow().getDecorView(), "未保存评论定位"));
        detail.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        android.content.Intent launched = org.robolectric.Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals("com.ss.android.ugc.aweme", launched.getPackage());
        assertEquals("aweme://aweme/detail/123456", launched.getDataString());
        assertFalse(page.dialog().isShowing());
    }

    @Test public void openingReadsOffMainAndVirtualizesRowsWhilePreservingDetailsAndClear() throws Exception {
        for (int index = 0; index < 200; index++) {
            long createdAt = 1000 + index;
            store.upsert(new AutomationHistoryStore.Entry("history-" + index, "reply", "target-" + index,
                    "用户 " + index, "原消息 " + index, "生成回复 " + index, "模型", "sent",
                    createdAt, createdAt));
        }
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        ListView list = list();
        assertEquals(1, list.getAdapter().getCount());
        worker.runNextOnThread();
        assertEquals(1, list.getAdapter().getCount());
        ShadowLooper.idleMainLooper();

        assertEquals(200, list.getAdapter().getCount());
        list.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        list.layout(0, 0, 360, 480);
        assertTrue(list.getChildCount() > 0);
        assertTrue("Only visible records should be materialized", list.getChildCount() < 200);
        View first = list.getAdapter().getView(0, null, list);
        assertEquals("automation.history.history-199", first.getTag());
        first.performClick();
        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(containsText(detail.getWindow().getDecorView(), "原消息 199"));
        assertTrue(containsText(detail.getWindow().getDecorView(), "生成回复 199"));
        detail.dismiss();

        page.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        assertEquals(1, list.getAdapter().getCount());
        assertEquals("暂无记录", ((android.widget.TextView) list.getAdapter().getView(0, null, list))
                .getText().toString());
        assertTrue(store.read(AutomationHistoryStore.REPLY).isEmpty());
    }

    @Test public void dismissedReadCannotPopulateTheClosedPage() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("late", "reply", "target", "用户", "消息", "回复",
                "模型", "sent", 1, 1));
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        ListView list = list();
        worker.runNextOnThread();
        page.dialog().dismiss();
        ShadowLooper.idleMainLooper();
        assertEquals(1, list.getAdapter().getCount());
        assertEquals("正在读取…", ((android.widget.TextView) list.getAdapter().getView(0, null, list))
                .getText().toString());
    }

    @Test public void commentDetailsDistinguishUnknownUnsentTextOnlyAndImageRequests() throws Exception {
        int[] imageCounts = {-1, 0, 0, 1, 3};
        boolean[] requestSent = {false, false, true, true, true};
        String[] statuses = {"sent", "cancelled", "sent", "sent", "cancelled"};
        String[] labels = {"图片请求：未记录", "图片请求：未确认发送", "图片请求：未携带图片",
                "图片请求：已携带 1 张图片", "图片请求：已携带 3 张图片"};
        for (int index = 0; index < labels.length; index++) {
            store.upsert(new AutomationHistoryStore.Entry("request-" + index, "comment", "target", "作者",
                    "作品内容", "生成评论", "模型", statuses[index], index + 1, index + 1,
                    imageCounts[index], requestSent[index]));
        }
        page.dialog().dismiss();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.COMMENT, false, worker);
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();

        ListView list = list();
        assertEquals(labels.length, list.getAdapter().getCount());
        for (int position = 0; position < labels.length; position++) {
            int index = labels.length - 1 - position;
            View row = list.getAdapter().getView(position, null, list);
            row.performClick();
            AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            View content = detail.getWindow().getDecorView();
            assertTrue(labels[index], containsText(content, labels[index]));
            assertTrue(containsText(content, AutomationHistoryDialog.status(statuses[index])));
            assertTrue(containsText(content, "作品内容"));
            assertTrue(containsText(content, "生成评论"));
            for (int other = 0; other < labels.length; other++) {
                if (other != index) assertFalse(labels[other], containsText(content, labels[other]));
            }
            detail.dismiss();
        }
    }

    @Test public void replyDetailsDoNotShowCommentImageRequestMetadata() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("reply", "reply", "target", "用户", "消息", "回复",
                "模型", "sent", 1, 1, 0, true, AutomationHistoryStore.DELIVERY_VOICE));
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        list().getAdapter().getView(0, null, list()).performClick();

        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertFalse(containsText(detail.getWindow().getDecorView(), "图片请求："));
        assertFalse(containsText(detail.getWindow().getDecorView(), "发送方式："));
        assertFalse(containsText(list().getAdapter().getView(0, null, list()), "语音"));
    }

    @Test public void parentReplyHistoryIncludesCommentRepliesAndLabelsTheirSource() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("incoming", AutomationHistoryStore.COMMENT_REPLY,
                "root", "评论者", "收到的评论", "生成回复", "模型", "unknown", 1, 1,
                0, true, AutomationHistoryStore.DELIVERY_TEXT));
        store.upsert(new AutomationHistoryStore.Entry("private", AutomationHistoryStore.REPLY,
                "target", "私信用户", "私信", "私信回复", "模型", "sent", 2, 2));
        store.upsert(new AutomationHistoryStore.Entry("feed-comment", AutomationHistoryStore.COMMENT,
                "video", "作品作者", "作品", "自动评论", "模型", "sent", 3, 3));
        page.dialog().dismiss();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.REPLY, false, worker);
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(containsText(page.dialog().getWindow().getDecorView(), "回复历史记录"));
        assertEquals(2, list().getAdapter().getCount());
        View messageRow = list().getAdapter().getView(0, null, list());
        assertTrue(containsText(messageRow, "私信用户 · 发送成功"));
        assertFalse(containsText(messageRow, "评论回复"));
        View row = list().getAdapter().getView(1, null, list());
        assertTrue(containsText(row, "评论回复 · 评论者 · 结果未确认 · 文本"));
        row.performClick();
        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        assertTrue(containsText(detail.getWindow().getDecorView(), "收到的评论"));
        assertTrue(containsText(detail.getWindow().getDecorView(), "来源：评论回复"));
        assertTrue(containsText(detail.getWindow().getDecorView(), "发送方式：文本"));
        detail.dismiss();
        page.dialog().getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        assertTrue(store.read(AutomationHistoryStore.REPLY).isEmpty());
        assertTrue(store.read(AutomationHistoryStore.COMMENT_REPLY).isEmpty());
        assertEquals(1, store.read(AutomationHistoryStore.COMMENT).size());
    }

    @Test public void commentRowsAndDetailsShowPersistedDeliveryMode() throws Exception {
        String[] modes = {AutomationHistoryStore.DELIVERY_UNKNOWN, AutomationHistoryStore.DELIVERY_TEXT,
                AutomationHistoryStore.DELIVERY_VOICE};
        String[] labels = {"未记录", "文本", "语音"};
        for (int index = 0; index < modes.length; index++) {
            store.upsert(new AutomationHistoryStore.Entry("delivery-" + index, "comment", "target", "作者",
                    "作品内容", "生成评论", "模型", "sent", index + 1, index + 1, modes[index]));
        }
        page.dialog().dismiss();
        page = new AutomationHistoryDialog(activity, AutomationHistoryStore.COMMENT, false, worker);
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();

        ListView list = list();
        assertEquals(labels.length, list.getAdapter().getCount());
        for (int position = 0; position < labels.length; position++) {
            int index = labels.length - 1 - position;
            View row = list.getAdapter().getView(position, null, list);
            assertTrue(containsText(row, "作者 · 发送成功 · " + labels[index]));
            row.performClick();
            AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
            assertTrue(containsText(detail.getWindow().getDecorView(), "发送方式：" + labels[index]));
            detail.dismiss();
        }
    }

    @Test public void historyDetailShowsControlledDiagnosticsWhileKeepingSubmittedUnconfirmed() throws Exception {
        store.upsert(new AutomationHistoryStore.Entry("diagnostic", "reply", "target", "用户", "消息", "回复",
                "模型", "submitted", 1, 1).withDiagnostics(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED,
                AutomationHistoryStore.Stage.SEND, 10, -1, 20, 30000));
        page.dialog().show();
        ShadowLooper.idleMainLooper();
        worker.runNextOnThread();
        ShadowLooper.idleMainLooper();
        ListView list = list();
        list.getAdapter().getView(0, null, list).performClick();
        AlertDialog detail = (AlertDialog) org.robolectric.shadows.ShadowDialog.getLatestDialog();
        View body = detail.getWindow().getDecorView();
        assertTrue(containsText(body, "状态：已提交"));
        assertTrue(containsText(body, "原因：已提交，结果未确认"));
        assertTrue(containsText(body, "阶段：发送"));
        assertTrue(containsText(body, "排队耗时：10 ms"));
        assertTrue(containsText(body, "生成耗时：20 ms"));
        assertTrue(containsText(body, "发送确认等待耗时：30000 ms"));
        assertFalse(containsText(body, "媒体读取耗时"));
        assertFalse(containsText(body, "状态：发送成功"));
    }

    private ListView list() {
        return page.dialog().getWindow().getDecorView().findViewWithTag("automation.history");
    }

    private static boolean containsText(View view, String needle) {
        if (view instanceof android.widget.TextView
                && ((android.widget.TextView) view).getText().toString().contains(needle)) return true;
        if (view instanceof android.view.ViewGroup) {
            android.view.ViewGroup group = (android.view.ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                if (containsText(group.getChildAt(index), needle)) return true;
            }
        }
        return false;
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        private boolean shutdown;

        @Override public void execute(Runnable command) {
            if (shutdown) throw new java.util.concurrent.RejectedExecutionException();
            tasks.add(command);
        }

        void runNextOnThread() throws InterruptedException {
            Runnable task = tasks.poll();
            if (task == null) return;
            Thread thread = new Thread(task, "test-history-worker");
            thread.start();
            thread.join(5000);
            if (thread.isAlive()) throw new AssertionError("History work did not finish");
        }

        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> pending = new ArrayList<>(tasks);
            tasks.clear();
            return pending;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
    }
}
