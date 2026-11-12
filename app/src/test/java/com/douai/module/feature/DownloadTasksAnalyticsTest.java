package com.douai.module.feature;

import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.LocalAnalytics;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35)
public final class DownloadTasksAnalyticsTest {
    private LocalAnalytics store;

    @Before public void prepare() {
        DownloadTaskTestIdle.beforeTest();
        store = LocalAnalytics.get(RuntimeEnvironment.getApplication());
        store.clear(); store.setEnabled(true);
    }

    @After public void close() {
        DownloadTaskTestIdle.afterTest();
        store.setEnabled(false); store.clear(); store.close();
    }

    @Test public void confirmedCompletionCountsOncePerTaskWithoutLinkingCurrentCard() {
        DownloadTasks.Task task = task("media\nfirst");
        store.beginVisit(store.generation(), "later-card", "image", "author", "作者", List.of("话题"), false, -1, "unknown");
        task.run(transfer -> "已保存");
        task.released();
        task.released();
        AnalyticsSnapshot result = store.snapshot(0);
        assertEquals("completed", task.status);
        assertEquals(1, result.totalFeatureUses);
        assertEquals("下载任务完成", result.features.get(0).label);
        assertTrue("completion never borrows the current visit", result.portrait.typeActions.isEmpty());
    }

    @Test public void failedAndCancelledTasksNeverCountAsSaved() {
        DownloadTasks.Task failed = task("media\nfailed");
        failed.run(transfer -> { throw new java.io.IOException("fixture"); });
        failed.released();
        DownloadTasks.Task cancelled = task("media\ncancelled");
        cancelled.transfer.requestCancel(false);
        cancelled.run(transfer -> "must not run");
        cancelled.released();
        assertEquals("failed", failed.status);
        assertEquals("cancelled", cancelled.status);
        assertEquals(0, store.snapshot(0).totalFeatureUses);
    }

    @Test public void clearAndDisableInvalidatePreviouslyCreatedTaskCompletion() {
        DownloadTasks.Task cleared = task("media\ncleared");
        store.clear();
        cleared.run(transfer -> "已保存"); cleared.released();
        DownloadTasks.Task disabled = task("media\ndisabled");
        store.setEnabled(false); store.setEnabled(true);
        disabled.run(transfer -> "已保存"); disabled.released();
        assertEquals("completed", cleared.status);
        assertEquals("completed", disabled.status);
        assertEquals(0, store.snapshot(0).totalFeatureUses);
    }

    @Test public void parserCompletionIsExcludedButSelectedBundleCountsAsOneTask() {
        DownloadTasks.Task parse = task("third-party\nparse\nfixture");
        parse.run(transfer -> "解析完成，请选择保存内容"); parse.released();
        assertEquals(0, store.snapshot(0).totalFeatureUses);
        DownloadTasks.Task bundle = task("third-party\nselected\nfixture");
        bundle.run(transfer -> { transfer.totalFiles = 5; transfer.completedFiles = 5; return "已保存5个文件"; });
        bundle.released();
        assertEquals("a bundle is one task, not five feature actions", 1, store.snapshot(0).totalFeatureUses);
    }

    @Test public void outputEvidenceRequiresClosedWriteAndFreezesOriginalOperation() {
        String original = store.beginVisit(store.generation(), "original", "image", null, "", List.of(), false, 0, "unknown");
        LocalAnalytics.FlowOperation flow = store.beginOperation("original", "download");
        DownloadTasks.Task first = task("media\nflow1"); first.transfer.analytics = flow; first.transfer.analyticsTask = 1;
        DownloadTasks.Task second = task("media\nflow2"); second.transfer.analytics = flow; second.transfer.analyticsTask = 2;
        store.beginVisit(store.generation(), "later", "image", null, "", List.of(), false, 0, "unknown");
        first.run(t -> { t.saved("fixture","private-path",10); t.saved("fixture","other-path",10);return "saved"; }); first.released();
        second.run(t -> "completed without write");second.released();
        store.snapshot(0);
        java.io.File file = new java.io.File(RuntimeEnvironment.getApplication().getNoBackupFilesDir(),"douai-analytics/usage.db");
        try(android.database.sqlite.SQLiteDatabase db=android.database.sqlite.SQLiteDatabase.openDatabase(file.getPath(),null,android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
            android.database.Cursor c=db.rawQuery("SELECT token,stage,task FROM flow_events ORDER BY id",null)) {
            int outputs=0, ends=0;
            while(c.moveToNext()) { assertEquals(original,c.getString(0)); if(c.getString(1).equals("output_closed")) { outputs++;assertEquals(1,c.getInt(2)); } if(c.getString(1).equals("task_end")) ends++; }
            assertEquals(1,outputs);assertEquals(2,ends);
        }
    }

    private static DownloadTasks.Task task(String key) {
        return new DownloadTasks.Task(RuntimeEnvironment.getApplication(), key, "fixture", null, "失败");
    }
}
