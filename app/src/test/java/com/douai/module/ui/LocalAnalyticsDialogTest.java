package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.ScrollView;
import android.widget.TextView;

import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.AnalyticsSnapshot.Bucket;
import com.douai.module.analytics.AnalyticsSnapshot.Rank;
import com.douai.module.analytics.AnalyticsSnapshot.Recent;
import com.douai.module.analytics.JournalExample;
import com.douai.module.analytics.PortraitSnapshot;
import com.douai.module.analytics.AnalyticsAiAnalysis;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.json.JSONObject;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowLooper;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, qualifiers = "notnight")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public final class LocalAnalyticsDialogTest {
    private static final String[] AI_KEYS = {"overview", "trend", "types", "authors", "topics", "features", "hours"};
    private static final String[] AI_TEXTS = {
            "当前范围累计刷到 1,572 次、涉及 1,419 个不同作品，模块功能使用共 628 次。浏览次数不等于实际观看时长。",
            "10/15 记录了 315 次浏览，是当前图表最高值。单日变化不能直接用于判断长期习惯。",
            "短视频共有 1,000 次，占比约 63.61%；实况图集共有 200 次。类型占比描述本次样本构成。",
            "作者排行仅覆盖前十位作者。反复刷到同一作者不代表关注或完整观看了对应作品。",
            "一个作品可以包含多个话题，因此话题次数之和可能超过浏览次数，不能据此推断个人身份。",
            "保存媒体触发 248 次、作品总结触发 183 次。功能触发次数不等于每次请求或下载均成功。",
            "19 时记录了 71 次浏览，属于当前样本中较活跃的时段。缺少实际观看时长，无法推算使用时长。"
    };
    private Activity activity;
    private LocalAnalyticsDialog page;
    private FakeSource source;
    private ManualExecutor worker;
    private ManualExecutor analysisWorker;
    private FakeAnalysisService analysis;
    private final List<String> diagnostics = new ArrayList<>();

    @Before public void setup() {
        activity = Robolectric.buildActivity(Activity.class).setup().get();
        source = new FakeSource();
        worker = new ManualExecutor();
        analysisWorker = new ManualExecutor();
        analysis = new FakeAnalysisService();
        page = new LocalAnalyticsDialog(activity, source, worker, analysis, analysisWorker, false, diagnostics::add);
        page.dialog().show();
        ShadowLooper.idleMainLooper(); // Dialog dispatches onShow through its Handler.
    }

    @After public void cleanup() {
        page.dialog().dismiss();
        page.close();
        activity.finish();
        ShadowLooper.idleMainLooper();
    }

    @Test public void reportUsesTheSharedDialogChromeWithOneScrollableBody() {
        drain();
        assertTrue(page.dialog() instanceof AlertDialog);
        AlertDialog shown = (AlertDialog) page.dialog();
        assertEquals("关闭", shown.getButton(AlertDialog.BUTTON_NEGATIVE).getText().toString());
        assertNull(find("analytics.back"));
        ViewGroup root = find("analytics.root");
        assertNull("The shared dialog owns the outside surface", root.getBackground());
        assertEquals(1, root.getChildCount());
        assertSame(find("analytics.scroll"), root.getChildAt(0));
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.AT_MOST));
        root.layout(0, 0, root.getMeasuredWidth(), root.getMeasuredHeight());
        assertTrue("Scroll content must occupy the dialog viewport", find("analytics.scroll").getHeight() > 0);
        shown.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertFalse(shown.isShowing());
        assertTrue(worker.isShutdown());
    }

    @Test public void flowOnlyEvidenceOpensSameStoryOnScreenAndPaper() {
        com.douai.module.analytics.PortraitEvidence e = new com.douai.module.analytics.PortraitEvidence("2026-11-01","2026-11-07",0,
            List.of(),List.of(),com.douai.module.analytics.PortraitEvidence.Decomposition.empty("无可比浏览"),
            List.of(new com.douai.module.analytics.PortraitEvidence.Observation("exploration","exploration","主动追问记录","记录到2个会话。","会话计数","会话","2026-11-01","2026-11-07",2L,null,null,"flow","sample",List.of("路由不代表播放"))),List.of("仅有AI操作记录"),
            List.of(new com.douai.module.analytics.PortraitEvidence.StageCount("followup_click",2)),List.of(),List.of());
        PortraitSnapshot b = PortraitSnapshot.empty();
        PortraitSnapshot p = new PortraitSnapshot(e.startDate,e.endDate,b.days,b.weekdayHours,b.topicTiles,b.authorPareto,b.featureSources,
            b.browseDays,b.authorCount,b.topicCount,b.topicOccurrences,b.repeatedWorks,b.capturedVisits,b.finishedVisits,b.visibleMillis,b.queueDropped,
            b.authorDiversity,b.topicDiversity,b.topAuthorShare,b.topTopicShare,b.crossDayRepeatShare,b.nightShare,b.weekendShare,
            b.comparison,b.weekComparison,b.monthComparison,b.insights,b.topicHours,b.typeActions,b.coverage,b.evidenceLevel,e);
        source.data = new AnalyticsSnapshot(0,0,0,0,0,0,0,List.of(),List.of(),List.of(),List.of(),List.of(),new long[24],List.of(),p);
        drain();
        assertNotNull(find("analytics.story.headline"));
        View paper = ReportPresentation.build(activity,source.data,"7天",0,null,"","",0);
        assertNotNull(paper.findViewWithTag("analytics.story.headline"));
        assertFalse("A report containing two AI sessions cannot say it has no retained records", paperText(paper).contains("当前范围暂无保留记录"));
        ViewGroup body = find("analytics.body");
        assertNotNull("the first report surface is the discovery, not the recording settings", body.getChildAt(0).findViewWithTag("analytics.story.headline"));
        assertNotNull(find("analytics.story.all"));
        assertTrue(find("analytics.analysis.request").isEnabled());
    }

    @Test public void personalJournalKeepsExactNumbersAcrossFourChaptersAndExport() throws Exception {
        java.io.File directory = java.nio.file.Files.createTempDirectory(activity.getCacheDir().toPath(), "journal-example").toFile();
        source.data = com.douai.module.analytics.JournalExample.twoWeeks(directory);
        drain();
        assertEquals(24, source.data.totalViews); assertEquals(7, source.data.portrait.browseDays);
        for(String key : List.of("story.topics","story.rhythm","story.actions","story.changes")) expandSection(key);
        View phone = find("analytics.body");
        View actionsChapter=find("analytics.section.body.story.actions");
        assertNotNull(actionsChapter.findViewWithTag("analytics.analysis.section.features"));
        assertNull(find("analytics.section.body.features"));
        View paper = ReportPresentation.build(activity,source.data,"最近 7 天",0,null,"","",0);
        String exportedText = paperText(paper);
        for (String label : List.of("模块功能排行", "最近记录", source.data.recent.get(0).label, "使用历程")) {
            assertTrue("Collapsed statistics still export " + label, exportedText.contains(label));
        }
        for(View tree : List.of(phone,paper)) {
            String text=paperText(tree);
            assertTrue(text, text.contains("10/10 次（100.0%）"));
            assertTrue(text, text.contains("12/14 次（85.7%）"));
            assertTrue(text, text.contains("摄影   #2（7 次）  →  #1（12 次）"));
            assertNotNull(tree.findViewWithTag("analytics.journal.hour-profiles"));
            assertNotNull(tree.findViewWithTag("analytics.journal.rank-lines"));
            assertNotNull(tree.findViewWithTag("analytics.portrait.journal.topics.chart"));
            assertNotNull(tree.findViewWithTag("analytics.portrait.journal.authors.chart"));
        }
        View screen=find("analytics.root");
        screen.measure(View.MeasureSpec.makeMeasureSpec(360,View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(740,View.MeasureSpec.EXACTLY));
        screen.layout(0,0,360,740);
        java.io.File previewDir=new java.io.File("build/reports/portrait-preview");
        assertTrue(previewDir.isDirectory() || previewDir.mkdirs());
        Bitmap preview=Bitmap.createBitmap(360,740,Bitmap.Config.ARGB_8888);
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(new java.io.File(previewDir,"journal-dialog-360.png"))) {
            Canvas canvas=new Canvas(preview);canvas.drawColor(android.graphics.Color.WHITE);screen.draw(canvas);
            assertTrue(preview.compress(Bitmap.CompressFormat.PNG,100,output));
        } finally { preview.recycle(); }
        ReportImageExport.Document doc=page.captureReportImage(1080,4096);
        assertNotNull(doc); assertTrue(doc.pdfPageCount()>1); writeReportFiles(doc,"personal-journal-synthetic");
        assertEquals(0,analysis.requests.size());
    }

    @Test public void journalBuildsStatisticsOncePerSnapshotOnlyWhenExpanded() throws Exception {
        File cache = activity.getCacheDir();
        source.data = JournalExample.twoWeeks(java.nio.file.Files.createTempDirectory(cache.toPath(), "week").toFile());
        drain();
        ViewGroup body = find("analytics.body");
        int initialNodes = nodeCount(body);
        System.out.println("Journal opening view nodes (Android Views, 7 days): " + initialNodes);
        assertNotNull(find("analytics.story.headline"));
        for (String tag : List.of("analytics.portrait.types.chart", "analytics.section.body.authors",
                "analytics.section.body.features", "analytics.section.body.recent")) assertNull(tag, find(tag));
        find("analytics.story.all").performClick();
        View chart = find("analytics.portrait.types.chart");
        assertNotNull(chart);
        int expandedNodes = nodeCount(body);
        assertTrue(expandedNodes > initialNodes);
        System.out.println("Journal expanded view nodes (Android Views, 7 days): " + expandedNodes);
        assertEquals("24 次浏览 · 7 天有记录", text("analytics.section.summary.trend"));
        assertTrue(paperText(find("analytics.section.body.recent")).contains(source.data.recent.get(0).label));
        assertNotNull(find("analytics.section.body.story.actions").findViewWithTag("analytics.analysis.section.features"));
        assertNull(find("analytics.section.body.features").findViewWithTag("analytics.analysis.section.features"));
        expandSection("types");
        find("analytics.story.all").performClick();
        assertFalse(chart.isShown());
        find("analytics.story.all").performClick();
        assertSame(chart, find("analytics.portrait.types.chart"));
        assertEquals(expandedNodes, nodeCount(body));
        assertEquals("Toggling statistics reads no additional snapshots", 1, source.reads);

        source.data = JournalExample.twoWeeks(java.nio.file.Files.createTempDirectory(cache.toPath(), "month").toFile(), 30);
        find("analytics.range.30").performClick();
        drain();
        assertEquals(30, source.lastDays);
        assertEquals("45 次浏览 · 14 天有记录", text("analytics.section.summary.trend"));
        assertNotSame(chart, find("analytics.portrait.types.chart"));
        assertTrue(find("analytics.section.body.types").isShown());
        find("analytics.story.all").performClick();
        find("analytics.refresh").performClick();
        drain();
        assertNull("Refreshing a collapsed report defers statistics again", find("analytics.portrait.types.chart"));
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertEquals(AI_TEXTS[5], text("analytics.analysis.text.features"));
        assertNull(find("analytics.analysis.section.overview"));
        find("analytics.story.all").performClick();
        assertEquals(AI_TEXTS[0], text("analytics.analysis.text.overview"));
        assertNull("The story keeps ownership of its AI text after opening statistics",
                find("analytics.section.body.features").findViewWithTag("analytics.analysis.text.features"));
        assertEquals(1, analysis.requests.size());
    }

    private static int nodeCount(View view) {
        int count = 1;
        if (view instanceof ViewGroup group)
            for (int i = 0; i < group.getChildCount(); i++) count += nodeCount(group.getChildAt(i));
        return count;
    }

    @Test public void reportUsesSettingsTypographyAndCompactUnoutlinedCards() {
        source.data = fixture();
        drain();
        ViewGroup overview = find("analytics.chartcard.overview");
        TextView heading = find("analytics.section.heading.overview");
        float scaledDensity = activity.getResources().getDisplayMetrics().scaledDensity;
        assertEquals(16f, heading.getTextSize() / scaledDensity, 0.01f);
        assertEquals(0xFF1F272D, heading.getCurrentTextColor());
        assertEquals(12f * activity.getResources().getDisplayMetrics().density,
                ((GradientDrawable) overview.getBackground()).getCornerRadius(), 0.01f);
        TextView purpose = find("analytics.purpose");
        assertEquals(0xFF008579, purpose.getCurrentTextColor());
        assertEquals(0xFFE7F5F2,
                ((GradientDrawable) purpose.getBackground()).getColor().getDefaultColor());
        assertNotNull(find("analytics.types.chart"));
    }

    @Test public void reportStartsCompactWithDiscoverableSummariesAndOnlyRequestedDetailsExpand() throws Exception {
        source.data = portraitFixture();
        drain();
        assertEquals(View.GONE, find("analytics.section.body.settings").getVisibility());
        assertEquals(View.VISIBLE, find("analytics.section.body.overview").getVisibility());
        assertEquals(View.VISIBLE, find("analytics.section.body.analysis").getVisibility());
        for (String key : List.of("portrait", "trend", "types", "topics", "authors", "hours",
                "changes", "associations", "features", "recent", "history")) {
            assertEquals(key, View.GONE, find("analytics.section.body." + key).getVisibility());
            assertFalse(text("analytics.section.summary." + key).isEmpty());
            assertEquals("展开", text("analytics.section.toggle." + key));
        }
        renderArtifact(find("analytics.body"), 360, "portrait-360-collapsed-light.png", false);
        find("analytics.section.toggle.types").performClick();
        assertEquals(View.VISIBLE, find("analytics.section.body.types").getVisibility());
        assertEquals(View.GONE, find("analytics.section.body.topics").getVisibility());
        assertTrue(find("analytics.section.toggle.types").isSelected());
        assertTrue(find("analytics.section.toggle.types").getContentDescription().toString().contains("已展开"));
        find("analytics.section.toggle.types").performClick();
        assertEquals(View.GONE, find("analytics.section.body.types").getVisibility());
    }

    @Test public void expandedSectionsSurviveRefreshAndRangeChanges() {
        source.data = fixture();
        drain();
        expandSection("types");
        expandSection("features");
        find("analytics.refresh").performClick();
        drain();
        assertEquals(View.VISIBLE, find("analytics.section.body.types").getVisibility());
        assertEquals(View.VISIBLE, find("analytics.section.body.features").getVisibility());
        find("analytics.section.toggle.features").performClick();
        find("analytics.range.30").performClick();
        drain();
        assertEquals(View.VISIBLE, find("analytics.section.body.types").getVisibility());
        assertEquals(View.GONE, find("analytics.section.body.features").getVisibility());
        assertEquals(View.GONE, find("analytics.section.body.settings").getVisibility());
    }

    @Test public void aiResultsKeepCardsCompactAndOfferExplicitExpansion() {
        source.data = fixture();
        drain();
        View chart = find("analytics.types.chart");
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertSame(chart, find("analytics.types.chart"));
        assertEquals(View.GONE, find("analytics.section.body.types").getVisibility());
        assertTrue(text("analytics.section.summary.types").contains("含 AI 解读"));
        assertNotNull(find("analytics.analysis.share"));
        find("analytics.analysis.expand").performClick();
        for (String key : AI_KEYS) assertEquals(key, View.VISIBLE, find("analytics.section.body." + key).getVisibility());
        assertEquals(AI_TEXTS[2], text("analytics.analysis.text.types"));
    }

    @Test public void completedAiResultIsReadableInsideTheReportWithoutExpandingEveryChart() {
        source.data = fixture();
        drain();
        View chart = find("analytics.types.chart");
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        TextView result = find("analytics.analysis.result");
        assertTrue("The report must directly show the completed analysis", result.isShown());
        for (String section : AI_TEXTS) assertTrue("The visible report must include every existing AI section",
                result.getText().toString().contains(section));
        assertTrue(result.isTextSelectable());
        assertEquals(View.GONE, find("analytics.section.body.types").getVisibility());
        assertSame(chart, find("analytics.types.chart"));
        assertEquals(1, analysis.requests.size());
        assertEquals(1, analysis.requests.get(0).runs);
    }

    @Test public void fullReportImageShareIsAvailableWithoutAiAndDoesNotRequestAiAgain() {
        source.data = fixture();
        drain();
        View imageShare = find("analytics.report.share.image");
        assertNotNull("The complete report must be shareable as an image before requesting AI", imageShare);
        assertEquals("Export guidance belongs in Help; this view is reserved for runtime status",
                "", text("analytics.report.share.note"));
        assertTrue(imageShare.isEnabled());
        imageShare.performClick();
        ((AlertDialog) ShadowDialog.getLatestDialog()).dismiss();
        drain();
        assertTrue(analysis.requests.isEmpty());
        assertTrue(imageShare.isEnabled());
    }

    @Test public void reportExportOffersOneLongPngOrPdfWithoutStartingAnotherAiRequest() {
        source.data = fixture(); drain();
        TextView export = find("analytics.report.share.image");
        assertEquals("导出/分享报告", export.getText().toString());
        export.performClick(); ShadowLooper.idleMainLooper();
        AlertDialog formats = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(page.dialog(), formats);
        assertNotNull(formats.getListView());
        assertEquals("长图 PNG", formats.getListView().getAdapter().getItem(0));
        assertEquals("PDF 文档", formats.getListView().getAdapter().getItem(1));
        formats.dismiss();
        assertTrue(analysis.requests.isEmpty());
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
    }

    @Test public void paperReportCaptureDoesNotRelayoutTheOnscreenChartsOrChangeTheirFoldState() {
        source.data = fixture(); drain(); expandSection("types");
        View root = find("analytics.root");
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 600);
        View chart = find("analytics.types.chart");
        java.util.concurrent.atomic.AtomicInteger relayouts = new java.util.concurrent.atomic.AtomicInteger();
        chart.addOnLayoutChangeListener((view, l, t, r, b, ol, ot, or, ob) -> relayouts.incrementAndGet());
        ScrollView scroll = find("analytics.scroll"); scroll.scrollTo(0, 120);
        int previousScroll = scroll.getScrollY();
        page.captureReportImage(1080, 4096);
        assertEquals("Export must build a detached report rather than resize the live UI", 0, relayouts.get());
        assertEquals(View.VISIBLE, find("analytics.section.body.types").getVisibility());
        assertEquals(previousScroll, scroll.getScrollY());
        assertSame(chart, find("analytics.types.chart"));
    }

    @Test public void paperReportArtifactsContainCompleteLongAiInOnePngAndOnePdf() throws Exception {
        source.data = portraitFixture();
        JSONObject answer = new JSONObject(analysisResponse());
        String detail = "只依据本地样本解释内容接触，次数不等于实际观看时长，未记录的时段保持未知。".repeat(5);
        for (String key : AI_KEYS) answer.put(key, detail);
        answer.put("portraitTitle", "一份完整的阶段报告");
        answer.put("portrait", "本地记录覆盖 42 位作者和 17 个话题，采集边界必须与侧写同时展示。".repeat(4));
        answer.put("contrasts", "与前期对照存在采集缺口，不能由计数变化直接推断动机。");
        answer.put("questions", "哪些内容来自主动搜索，哪些来自首页推荐？");
        answer.put("hours", detail + "报告尾部完整性标记：最后一段时段分析仍完整保留。");
        analysis.response = answer.toString();
        drain(); find("analytics.analysis.request").performClick(); finishAnalysis();
        assertNull(find("analytics.analysis.error"));
        assertEquals(View.GONE, find("analytics.section.body.types").getVisibility());
        View chart = find("analytics.portrait.rhythm.chart");
        ReportImageExport.Document light = page.captureReportImage(1080, 4096);
        writeReportFiles(light, "paper-report-light");
        RuntimeEnvironment.setQualifiers("+night"); ModuleTheme.bind(find("analytics.root"));
        ReportImageExport.Document dark = page.captureReportImage(1080, 4096);
        writeReportFiles(dark, "paper-report-dark-host");
        assertEquals(light.longPngHeight(), dark.longPngHeight());
        assertEquals(light.pdfPageCount(), dark.pdfPageCount());
        assertEquals(View.GONE, find("analytics.section.body.types").getVisibility());
        assertSame(chart, find("analytics.portrait.rhythm.chart"));
        assertEquals(1, analysis.requests.size());
    }

    @Test public void paperRendererKeepsAllUniqueSectionsAndOneFullAiResultWithoutControlsOrActivity() throws Exception {
        AnalyticsAiAnalysis.Result result = AnalyticsAiAnalysis.parseResult(analysisResponse());
        View paper = ReportPresentation.build(activity, portraitFixture(), "最近 7 天", 1_792_550_000_000L,
                result, "测试模型", "最近 7 天", 1_792_549_000_000L);
        assertNull(paper.getParent());
        assertFalse(paper.getContext() instanceof Activity);
        assertEquals(160, paper.getResources().getConfiguration().densityDpi);
        assertEquals(1f, paper.getResources().getConfiguration().fontScale, 0);
        String content = paperText(paper);
        for (String title : List.of("我的本地使用报告", "浏览足迹", "内容构成", "话题排行", "接触来源曲线",
                "星期 × 小时", "话题迁移", "从接触到行动", "作者排行", "话题排行", "模块功能排行",
                "最近记录", "使用历程", "来源与阅读边界")) assertTrue(title, content.contains(title));
        for (String value : AI_TEXTS) assertEquals("AI paragraphs must appear once", 1, occurrences(content, value));
        assertFalse(content.contains("点选")); assertFalse(content.contains("请求 AI 分析"));
        assertNoPaperControls(paper);
        paper.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        paper.layout(0, 0, 540, paper.getMeasuredHeight());
        assertNoClippedText(paper);
    }

    @Test public void paperRendererUsesFixedLightColorsEvenWhenHostIsDarkAndLargeFont() throws Exception {
        AnalyticsSnapshot snapshot = portraitFixture();
        long now = 1_792_550_000_000L;
        View light = ReportPresentation.build(activity, snapshot, "最近 7 天", now, null, null, "", 0);
        Bitmap before = paperBitmap(light);
        RuntimeEnvironment.setQualifiers("+night");
        activity.getResources().getConfiguration().fontScale = 1.6f;
        View darkHost = ReportPresentation.build(activity, snapshot, "最近 7 天", now, null, null, "", 0);
        Bitmap after = paperBitmap(darkHost);
        try {
            assertEquals(before.getHeight(), after.getHeight());
            assertTrue("Paper export must be independent from host theme/font", before.sameAs(after));
            assertEquals(android.graphics.Color.WHITE, after.getPixel(0, 0));
            assertTrue(paperText(darkHost).contains("尚无 AI 分析结果"));
        } finally { before.recycle(); after.recycle(); }
    }

    @Test public void paperRendererPreservesCoverageWithoutDayBucketsAndAvoidsEmptyPortraitCharts() {
        View paper = ReportPresentation.build(activity, AnalyticsSnapshot.empty(), "最近 7 天", 1_792_550_000_000L,
                null, null, "", 0);
        String content = paperText(paper);
        assertTrue(content.contains("尚无可用记录；采集默认关闭。"));
        assertTrue(content.contains("这份侧写知道什么、还不知道什么"));
        assertTrue(content.contains("尚无 AI 分析结果"));
        assertNull(paper.findViewWithTag("analytics.portrait.calendar.chart"));
        assertNull(paper.findViewWithTag("analytics.portrait.comparison.chart"));
    }

    private static Bitmap paperBitmap(View paper) {
        paper.measure(View.MeasureSpec.makeMeasureSpec(540, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        paper.layout(0, 0, 540, paper.getMeasuredHeight());
        Bitmap bitmap = Bitmap.createBitmap(540, paper.getHeight(), Bitmap.Config.ARGB_8888);
        paper.draw(new Canvas(bitmap)); return bitmap;
    }

    private static String paperText(View view) {
        StringBuilder text = new StringBuilder();
        if (view instanceof TextView) text.append(((TextView) view).getText()).append('\n');
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            text.append(paperText(((ViewGroup) view).getChildAt(i)));
        return text.toString();
    }
    private static int occurrences(String value, String needle) {
        int count = 0; for (int from = 0; (from = value.indexOf(needle, from)) >= 0; from += needle.length()) count++;
        return count;
    }
    private static void assertNoPaperControls(View view) {
        assertFalse(view instanceof android.widget.Button); assertFalse(view instanceof android.widget.EditText);
        assertFalse(view instanceof android.widget.Spinner); assertFalse(view instanceof android.widget.CompoundButton);
        assertFalse(view.isClickable());
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
            assertNoPaperControls(((ViewGroup) view).getChildAt(i));
    }

    @Test public void closingBeforeImageCallbackDeletesSavedPagesAndCannotOpenTheChooser() throws Exception {
        source.data = fixture(); drain();
        ReportImageExportTest.MediaProvider provider = ReportImageExportTest.provider(activity);
        chooseReportFormat(0);
        worker.runNext(); // PNGs are saved; the chooser callback has not reached the main thread.
        assertTrue(provider.inserts > 0);
        page.close();
        while (!worker.tasks.isEmpty()) worker.runNext();
        ShadowLooper.idleMainLooper();
        provider.awaitDeleted(provider.inserts);
        assertTrue(provider.files.isEmpty()); assertEquals(provider.inserts, provider.deletes);
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
        assertTrue(analysis.requests.isEmpty());
    }

    @Test public void changingRangeRejectsThePreviousImageCallbackAndClearsItsAiResult() throws Exception {
        source.data = fixture(); drain();
        find("analytics.analysis.request").performClick(); finishAnalysis();
        ReportImageExportTest.MediaProvider provider = ReportImageExportTest.provider(activity);
        chooseReportFormat(0); worker.runNext();
        find("analytics.range.30").performClick(); drain();
        provider.awaitDeleted(provider.inserts);
        assertTrue(provider.files.isEmpty()); assertEquals(provider.inserts, provider.deletes);
        assertNull(((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity());
        assertNull(find("analytics.analysis.result")); assertEquals(1, analysis.requests.size());
    }

    @Test public void closingAfterChooserHandoffKeepsAllReportImagesReadable() {
        source.data = fixture(); drain();
        ReportImageExportTest.MediaProvider provider = ReportImageExportTest.provider(activity);
        chooseReportFormat(0); drain();
        Intent chooser = ((ShadowActivity) Shadow.extract(activity)).getNextStartedActivity();
        assertNotNull(chooser); assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        page.close(); while (!worker.tasks.isEmpty()) worker.runNext();
        assertEquals(0, provider.deletes); assertEquals(provider.inserts, provider.files.size());
        for (File file : provider.files.values()) assertTrue(file.length() > 0);
        assertTrue(analysis.requests.isEmpty());
    }

    private void chooseReportFormat(int index) {
        find("analytics.report.share.image").performClick();
        AlertDialog formats = (AlertDialog) ShadowDialog.getLatestDialog();
        formats.getListView().performItemClick(null, index, index); ShadowLooper.idleMainLooper();
    }

    private static void writeReportFiles(ReportImageExport.Document document, String prefix) throws Exception {
        File root = new File(".").getCanonicalFile();
        while (root != null && !new File(root, "gradlew.bat").isFile()) root = root.getParentFile();
        assertNotNull("Project root for export artifacts", root);
        File directory = new File(root, "artifacts/diagnostics-0.2.4/report-exports");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        File png = new File(directory, prefix + ".png"), pdf = new File(directory, prefix + ".pdf");
        try (FileOutputStream output = new FileOutputStream(png)) { document.write(ReportImageExport.Format.LONG_PNG, output, () -> true); }
        Bitmap decoded = android.graphics.BitmapFactory.decodeFile(png.getAbsolutePath());
        assertNotNull(decoded); assertEquals(1080, decoded.getWidth()); assertEquals(document.longPngHeight(), decoded.getHeight());
        assertTrue(decoded.getHeight() > 4096);
        decoded.recycle();
        try (FileOutputStream output = new FileOutputStream(pdf)) { document.write(ReportImageExport.Format.PDF, output, () -> true); }
        assertTrue(pdf.length() > 1000); assertTrue(document.pdfPageCount() > 1);
        JSONObject index = new JSONObject().put("png", png.getName()).put("pdf", pdf.getName())
                .put("pngWidth", 1080).put("pngHeight", document.longPngHeight()).put("pdfPages", document.pdfPageCount());
        try (FileOutputStream output = new FileOutputStream(new File(directory, prefix + "-index.json"))) {
            output.write(index.toString(2).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    @Test public void collapsingPortraitPreservesInlineEvidenceAndChartSelection() {
        source.data = portraitFixture();
        drain();
        expandSection("portrait");
        expandSection("trend");
        View evidence = find("analytics.portrait.evidence.sources.text");
        find("analytics.portrait.evidence.sources").performClick();
        assertEquals(View.VISIBLE, evidence.getVisibility());
        View chart = find("analytics.portrait.calendar.chart");
        chart.performClick();
        String selected = text("analytics.portrait.calendar.selection");
        find("analytics.section.toggle.portrait").performClick();
        expandSection("portrait");
        assertSame(evidence, find("analytics.portrait.evidence.sources.text"));
        assertEquals(View.VISIBLE, evidence.getVisibility());
        assertSame(chart, find("analytics.portrait.calendar.chart"));
        assertEquals(selected, text("analytics.portrait.calendar.selection"));
    }

    @Test public void recordingDefaultsOffAndUsesBackgroundMutation() {
        assertEquals(0, source.reads);
        assertTrue(text("analytics.status").contains("正在读取"));
        drain();
        Switch recording = find("analytics.recording");
        assertFalse(recording.isChecked());
        assertNotNull(find("analytics.empty"));
        recording.setChecked(true);
        assertFalse(source.enabled);
        assertFalse(recording.isEnabled());
        drain();
        assertTrue(source.enabled);
        assertTrue(recording.isEnabled());
        assertTrue(text("analytics.status").contains("正在本地记录"));
    }

    @Test public void staleRangeAndDismissedResultsCannotReplaceCurrentReport() {
        find("analytics.range.30").performClick();
        assertEquals(2, worker.tasks.size());
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertTrue(text("analytics.status").contains("正在读取"));
        assertEquals(7, source.lastDays);
        worker.runNext();
        ShadowLooper.idleMainLooper();
        assertEquals(30, source.lastDays);
        assertTrue(text("analytics.status").contains("最近 30 天"));
        find("analytics.range.0").performClick();
        String pendingStatus = text("analytics.status");
        page.dialog().dismiss();
        drain();
        assertEquals(pendingStatus, text("analytics.status"));
    }

    @Test public void clearRequiresConfirmationAndKeepsRecordingChoice() {
        source.enabled = true;
        source.data = fixture();
        drain();
        find("analytics.clear").performClick();
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals(0, source.clears);
        confirmation.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertEquals(0, source.clears);
        find("analytics.clear").performClick();
        confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        assertEquals(0, source.clears);
        drain();
        assertEquals(1, source.clears);
        assertTrue(source.enabled);
        assertTrue(((Switch) find("analytics.recording")).isChecked());
        assertNotNull(find("analytics.empty"));
    }

    @Test public void failedReadCanBeRetriedWithoutChangingData() {
        source.failure = true;
        drain();
        assertTrue(text("analytics.status").contains("读取失败"));
        source.failure = false;
        source.data = fixture();
        find("analytics.refresh").performClick();
        drain();
        assertNotNull(find("analytics.types.chart"));
        assertEquals(0, source.clears);
    }

    @Test public void confirmedClearFinishesEvenWhenReportClosesBeforeWorkerStarts() {
        source.enabled = true;
        source.data = fixture();
        drain();
        find("analytics.clear").performClick();
        AlertDialog confirmation = (AlertDialog) ShadowDialog.getLatestDialog();
        confirmation.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper(); // Dispatch the confirmed action before closing its report.
        String pendingStatus = text("analytics.status");
        page.dialog().dismiss();
        page.close();
        assertTrue(worker.isShutdown());
        assertEquals(0, source.clears);
        drain();
        assertEquals(1, source.clears);
        assertEquals(pendingStatus, text("analytics.status"));
    }

    @Test public void chartAccessibilityCanSelectAnExactTrendValue() {
        source.data = fixture();
        drain();
        View chart = find("analytics.trend.chart");
        assertTrue(chart.getContentDescription().toString().contains("10/12 250 次"));
        chart.performClick();
        assertEquals("10/12 · 250 次", text("analytics.trend.selection"));
        chart.performClick();
        assertEquals("10/13 · 246 次", text("analytics.trend.selection"));
        View donut = find("analytics.types.chart");
        assertTrue(donut.getContentDescription().toString().contains("实况图集 200 次"));
        assertTrue(donut.getContentDescription().toString().contains("12.72%"));
    }

    @Test public void aiRequestIsExplicitAndSharesOnlyAnalysisTextAfterSuccess() {
        source.data = fixture();
        drain();
        assertEquals(0, analysis.requests.size());
        ViewGroup report = find("analytics.report");
        assertSame(find("analytics.analysis.card"), report.getChildAt(report.getChildCount() - 1));
        assertNull(find("analytics.analysis.share"));
        find("analytics.analysis.request").performClick();
        assertEquals(1, analysis.requests.size());
        assertSame(source.data, analysis.snapshot);
        assertEquals(7, analysis.days);
        assertEquals(0, analysis.requests.get(0).runs);
        assertNotNull(find("analytics.analysis.progress"));
        assertNull(find("analytics.analysis.share"));
        finishAnalysis();
        assertEquals(1, analysis.requests.get(0).runs);
        for (String section : AI_TEXTS) assertTrue(text("analytics.analysis.result").contains(section));
        for (int index = 0; index < AI_KEYS.length; index++) {
            TextView sectionText = find("analytics.analysis.text." + AI_KEYS[index]);
            assertEquals(AI_TEXTS[index], sectionText.getText().toString());
            assertTrue(sectionText.isTextSelectable());
            ViewGroup slot = find("analytics.analysis.section." + AI_KEYS[index]);
            ViewGroup chartCard = find("analytics.chartcard." + AI_KEYS[index]);
            assertSame(slot, sectionText.getParent());
            ViewGroup details = find("analytics.section.body." + AI_KEYS[index]);
            assertSame(details, slot.getParent());
            assertSame(chartCard, details.getParent());
            assertSame(slot, details.getChildAt(details.getChildCount() - 1));
        }
        assertNull(find("analytics.analysis.request"));
        ShadowActivity shadowActivity = Shadow.extract(activity);
        assertNull(shadowActivity.getNextStartedActivity());
        find("analytics.analysis.share").performClick();
        Intent chooser = shadowActivity.getNextStartedActivity();
        assertEquals(Intent.ACTION_CHOOSER, chooser.getAction());
        Intent shared = chooser.getParcelableExtra(Intent.EXTRA_INTENT);
        assertEquals(Intent.ACTION_SEND, shared.getAction());
        assertEquals("text/plain", shared.getType());
        String text = shared.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(text.contains("最近 7 天"));
        assertTrue(text.contains("模块功能使用共 628 次"));
        for (String section : AI_TEXTS) assertTrue(text.contains(section));
        assertTrue(text.contains("浏览趋势"));
        assertTrue(text.contains("模块功能"));
        assertFalse(text.contains("生活记录"));
        assertFalse(text.contains("山间行走的人"));
    }

    @Test public void refreshedChartsInvalidateAnalysisAndRangeChangeAlsoResetsIt() {
        source.data = fixture();
        drain();
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        find("analytics.refresh").performClick();
        drain();
        assertNull(find("analytics.analysis.result"));
        assertNotNull(find("analytics.analysis.request"));
        for (String key : AI_KEYS) assertNull(find("analytics.analysis.text." + key));
        assertEquals(1, analysis.requests.size());
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertNotNull(find("analytics.analysis.result"));
        find("analytics.range.30").performClick();
        drain();
        assertNull(find("analytics.analysis.result"));
        assertNotNull(find("analytics.analysis.request"));
        assertEquals(2, analysis.requests.size());
    }

    @Test public void refreshingChartsCancelsPendingAnalysisWithoutSendingAgain() {
        source.data = fixture();
        drain();
        find("analytics.analysis.request").performClick();
        FakeAnalysisService.FakeRequest request = analysis.requests.get(0);
        find("analytics.refresh").performClick();
        assertTrue(request.cancelled);
        drain();
        finishAnalysis();
        assertEquals(0, request.runs);
        assertEquals(1, analysis.requests.size());
        assertNull(find("analytics.analysis.result"));
        assertNotNull(find("analytics.analysis.request"));
    }

    @Test public void aiFailureOffersRetryWithoutLeakingProviderErrors() {
        source.data = fixture();
        analysis.fail = true;
        drain();
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertNotNull(find("analytics.analysis.error"));
        assertFalse(text("analytics.analysis.error").contains("secret-test"));
        assertFalse(String.join("\n", diagnostics).contains("secret-test"));
        assertNull(find("analytics.analysis.share"));
        analysis.fail = false;
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertEquals(2, analysis.requests.size());
        assertNotNull(find("analytics.analysis.result"));
    }

    @Test public void typedFormatAndTimeoutFailuresKeepTheirSafeMessagesAndDiagnosticKinds() {
        source.data = fixture(); drain();
        for (AnalyticsAiAnalysis.AnalysisFailure.Kind kind : List.of(
                AnalyticsAiAnalysis.AnalysisFailure.Kind.FORMAT, AnalyticsAiAnalysis.AnalysisFailure.Kind.TIMEOUT)) {
            AnalyticsAiAnalysis.AnalysisFailure failure = new AnalyticsAiAnalysis.AnalysisFailure(kind);
            analysis.requestFailure = failure;
            find("analytics.analysis.request").performClick(); finishAnalysis();
            assertEquals(failure.safeMessage(), text("analytics.analysis.error"));
            assertNull(find("analytics.analysis.share"));
            assertNotNull(find("analytics.analysis.request"));
            assertTrue(diagnostics.stream().anyMatch(value -> value.contains("stage=ui_failed kind=" + kind.name())));
        }
    }

    @Test public void arbitraryProviderExceptionsCannotLeakBodiesCredentialsAddressesOrLabels() {
        source.data = fixture();
        String sensitive = "secret-ui-key https://private.invalid/v1 Bearer authorization 测试作者 私密话题 request-body";
        analysis.requestFailure = new IllegalStateException(sensitive);
        drain(); find("analytics.analysis.request").performClick(); finishAnalysis();
        assertEquals("本次分析未完成，请稍后重试。", text("analytics.analysis.error"));
        String logged = String.join("\n", diagnostics);
        for (String fragment : sensitive.split(" ")) {
            assertFalse(text("analytics.analysis.error").contains(fragment));
            assertFalse(logged.contains(fragment));
        }
        assertTrue(logged.contains("stage=ui_failed kind=TRANSPORT status=0 chars=0"));
    }

    @Test public void typedPrepareFailuresAndUnexpectedPrepareExceptionsStaySafe() {
        source.data = fixture(); drain();
        analysis.prepareFailure = new AnalyticsAiAnalysis.AnalysisFailure(AnalyticsAiAnalysis.AnalysisFailure.Kind.PREPARE);
        find("analytics.analysis.request").performClick();
        assertEquals(analysis.prepareFailure.getMessage(), text("analytics.analysis.error"));
        analysis.prepareFailure = new IllegalStateException("secret-prepare-key https://private.invalid/request");
        find("analytics.analysis.request").performClick();
        assertEquals(new AnalyticsAiAnalysis.AnalysisFailure(AnalyticsAiAnalysis.AnalysisFailure.Kind.PREPARE).safeMessage(),
                text("analytics.analysis.error"));
        assertFalse(String.join("\n", diagnostics).contains("secret-prepare-key"));
        assertTrue(analysis.requests.isEmpty());
    }

    @Test public void staleTypedFailureCannotRepopulateAChangedRangeOrItsUiFailureLog() {
        source.data = fixture(); drain();
        analysis.requestFailure = new AnalyticsAiAnalysis.AnalysisFailure(AnalyticsAiAnalysis.AnalysisFailure.Kind.TIMEOUT);
        find("analytics.analysis.request").performClick();
        analysisWorker.runNext(); // The failure callback is posted but has not reached the UI.
        find("analytics.range.30").performClick(); drain();
        assertNull(find("analytics.analysis.error"));
        assertNull(find("analytics.analysis.result"));
        assertFalse(diagnostics.stream().anyMatch(value -> value.contains("stage=ui_failed kind=TIMEOUT")));
    }

    @Test public void productionAnalysisAdapterForwardsOnlySafePreparationDiagnosticsWithoutSendingARequest() throws Exception {
        ConfigTransferDialogTest.MemoryStore store = new ConfigTransferDialogTest.MemoryStore();
        AiModelConfig config = AiModelConfig.fromJson(new JSONObject().put("id", "ui-diagnostics")
                .put("name", "private-model-label").put("provider", "openai")
                .put("apiEndpoint", "https://private.invalid/v1").put("apiKey", "secret-ui-adapter-key")
                .put("modelName", "private-model-name"));
        new AiModelSettings(List.of(config), config.id).save(store);
        LocalAnalyticsDialog.AnalysisService adapter = LocalAnalyticsDialog.analysisService(store, diagnostics::add);
        assertTrue(adapter.isConfigured());
        LocalAnalyticsDialog.AnalysisService.Request request = adapter.prepare(fixture(), 7, "private-purpose-text");
        assertNotNull(request);
        request.cancel(); // Preparing and cancelling does not execute the network request.
        String logged = String.join("\n", diagnostics);
        assertTrue(logged.contains("[analytics] stage=prepare"));
        for (String value : List.of("private.invalid", "secret-ui-adapter-key", "private-model-label",
                "private-model-name", "private-purpose-text")) assertFalse(logged.contains(value));
        LocalAnalyticsDialog.analysisService(store, ignored -> { throw new IllegalStateException("broken logger"); })
                .prepare(fixture(), 7).cancel();
    }

    @Test public void aiRequiresAllSevenSectionsBeforeRenderingAnyAnalysis() {
        source.data = fixture();
        analysis.response = "{\"overview\":\"只有总览\"}";
        drain();
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertNotNull(find("analytics.analysis.error"));
        assertEquals(new AnalyticsAiAnalysis.AnalysisFailure(AnalyticsAiAnalysis.AnalysisFailure.Kind.FORMAT).safeMessage(),
                text("analytics.analysis.error"));
        assertTrue(diagnostics.stream().anyMatch(value -> value.contains("stage=ui_failed kind=FORMAT")));
        assertNull(find("analytics.analysis.share"));
        for (String key : AI_KEYS) assertNull(find("analytics.analysis.text." + key));
        assertNotNull(find("analytics.analysis.request"));
    }

    @Test public void aiCompletionPreservesExistingChartsSelectionAndScroll() {
        source.data = fixture();
        drain();
        expandSection("trend");
        View root = find("analytics.root");
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 600);
        View chart = find("analytics.trend.chart");
        chart.performClick();
        ScrollView scroll = find("analytics.scroll");
        scroll.scrollTo(0, 200);
        int position = scroll.getScrollY();
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertSame(chart, find("analytics.trend.chart"));
        assertEquals("10/12 · 250 次", text("analytics.trend.selection"));
        assertEquals(position, scroll.getScrollY());
    }

    @Test public void aiRangeAndCloseCancelRequestsAndIgnoreLateResponses() {
        source.data = fixture();
        drain();
        find("analytics.analysis.request").performClick();
        FakeAnalysisService.FakeRequest first = analysis.requests.get(0);
        analysisWorker.runNext(); // Response is waiting on main; switching ranges invalidates it.
        find("analytics.range.30").performClick();
        assertTrue(first.cancelled);
        drain();
        assertNull(find("analytics.analysis.result"));
        find("analytics.analysis.request").performClick();
        FakeAnalysisService.FakeRequest second = analysis.requests.get(1);
        page.dialog().dismiss();
        page.close();
        assertTrue(second.cancelled);
        assertTrue(analysisWorker.isShutdown());
        assertEquals(0, second.runs);
        assertTrue(analysisWorker.tasks.isEmpty());
        ShadowLooper.idleMainLooper();
        assertNull(find("analytics.analysis.result"));
    }

    @Test public void aiEmptyAndUnconfiguredReportsDoNotSendRequests() {
        drain();
        assertFalse(find("analytics.analysis.request").isEnabled());
        find("analytics.analysis.request").performClick();
        assertTrue(analysis.requests.isEmpty());
        source.data = fixture();
        analysis.configured = false;
        find("analytics.refresh").performClick();
        drain();
        assertFalse(find("analytics.analysis.request").isEnabled());
        find("analytics.analysis.request").performClick();
        assertTrue(analysis.requests.isEmpty());
    }

    @Test public void aiResultsUnderEachChartRenderLightAndDarkWithoutClipping() throws Exception {
        source.data = fixture();
        drain();
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        render(find("analytics.body"), 360, "analytics-360-ai-success-light.png");
        View card = find("analytics.analysis.card");
        renderArtifact(card, 360, "analytics-ai-result-light.png", false);
        renderArtifact(find("analytics.chartcard.features"), 360, "analytics-ai-features-light.png", false);
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(find("analytics.root"));
        renderArtifact(card, 360, "analytics-ai-result-dark.png", false);
        renderArtifact(find("analytics.chartcard.features"), 360, "analytics-ai-features-dark.png", false);
        render(find("analytics.body"), 360, "analytics-360-ai-success-dark.png");
    }

    @Test public void populatedReportRendersNarrowLightDarkAndWideWithReadableLabels() throws Exception {
        source.enabled = true;
        source.data = fixture();
        drain();
        View body = find("analytics.body");
        render(body, 360, "analytics-360-light.png");
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(find("analytics.root"));
        assertEquals(0xFFF2F2F2, ((Switch) find("analytics.recording")).getCurrentTextColor());
        render(body, 360, "analytics-360-dark.png");
        RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(find("analytics.root"));
        assertEquals(0xFF1F272D, ((Switch) find("analytics.recording")).getCurrentTextColor());
        render(body, 560, "analytics-560-light.png");
    }

    @Test public void portraitChartsRenderAndExposeExactEvidenceAndAccessibilityValues() throws Exception {
        source.data = portraitFixture();
        drain();
        render(find("analytics.body"), 360, "portrait-360-light.png");
        for (String key : List.of("calendar", "types", "topics", "authors", "rhythm", "comparison")) {
            View chart = find("analytics.portrait." + key + ".chart");
            assertNotNull(chart);
            assertTrue(chart.getContentDescription().length() > 30);
            assertTrue(chart.performClick());
        }
        assertTrue(text("analytics.portrait.calendar.selection").contains("2026-10-12"));
        assertTrue(text("analytics.portrait.calendar.selection").contains("250"));
        assertTrue(text("analytics.portrait.rhythm.selection").contains("周一 0 时"));
        assertTrue(text("analytics.portrait.comparison.selection").contains("覆盖不完整"));
        View evidence = find("analytics.portrait.evidence.sources.text");
        assertEquals(View.GONE, evidence.getVisibility());
        find("analytics.portrait.evidence.sources").performClick();
        assertEquals(View.VISIBLE, evidence.getVisibility());
        assertTrue(((TextView) evidence).getText().toString().contains("42 位"));
        find("analytics.portrait.coverage").performClick();
        assertTrue(text("analytics.portrait.coverage.text").contains("记录空白不等于没有使用"));
        assertNull(find("analytics.profile"));
        RuntimeEnvironment.setQualifiers("+night");
        ModuleTheme.bind(find("analytics.root"));
        render(find("analytics.body"), 360, "portrait-360-dark.png");
        RuntimeEnvironment.setQualifiers("+notnight");
        ModuleTheme.bind(find("analytics.root"));
        render(find("analytics.body"), 560, "portrait-560-light.png");
    }

    @Test public void portraitAiRemainsUnderCorrespondingChartsAndSharesAllInterpretations() throws Exception {
        source.data = portraitFixture();
        JSONObject answer = new JSONObject(analysisResponse());
        answer.put("portraitTitle", "主线稳定的内容漫游者");
        answer.put("portrait", "这段记录中接触 42 位作者，旅行话题经常出现；反映内容接触，不能直接推断偏好。");
        answer.put("contrasts", "本期 1572 次浏览，前期 1210 次；采集覆盖不完整，暂不判断长期变化。");
        answer.put("questions", "这些内容是主动搜索还是首页推荐？使用目的可以帮助解释动机。");
        analysis.response = answer.toString();
        drain();
        assertTrue(analysis.requests.isEmpty());
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        assertTrue(text("analytics.analysis.text.portrait").contains("主线稳定"));
        assertTrue(text("analytics.analysis.text.changes").contains("覆盖不完整"));
        assertTrue(text("analytics.analysis.text.associations").contains("主动搜索"));
        for (String key : AI_KEYS) assertNotNull(find("analytics.analysis.text." + key));
        assertNull(find("analytics.analysis.request"));
        render(find("analytics.body"), 360, "portrait-360-ai-light.png");
        find("analytics.analysis.share").performClick();
        ShadowActivity shadowActivity = Shadow.extract(activity);
        Intent shared = shadowActivity.getNextStartedActivity().getParcelableExtra(Intent.EXTRA_INTENT);
        String sharedText = shared.getStringExtra(Intent.EXTRA_TEXT);
        assertTrue(sharedText.contains("主线稳定"));
        assertTrue(sharedText.contains("采集覆盖不完整"));
        assertTrue(sharedText.contains("主动搜索"));
        find("analytics.refresh").performClick();
        drain();
        assertNull(find("analytics.analysis.text.portrait"));
    }

    @Test public void selfReportedPurposeSavesOnlyOnWorkerAndCancelsOlderAnalysis() {
        source.data = portraitFixture();
        drain();
        expandSection("settings");
        find("analytics.analysis.request").performClick();
        find("analytics.purpose").performClick();
        AlertDialog editor = (AlertDialog) ShadowDialog.getLatestDialog();
        android.widget.EditText input = findEditText(editor.getWindow().getDecorView());
        assertNotNull(input);
        input.setText("学习摄影，寻找旅行灵感");
        editor.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        ShadowLooper.idleMainLooper();
        assertNull(source.savedPurpose);
        assertTrue(analysis.requests.get(0).cancelled);
        drain();
        assertEquals("学习摄影，寻找旅行灵感", source.savedPurpose);
        assertEquals(1, analysis.requests.size());
    }

    @Test public void ratingExistingPortraitPreservesChartsAnalysisAndSharingWithoutReload() throws Exception {
        source.data = portraitFixture();
        JSONObject answer = new JSONObject(analysisResponse());
        answer.put("portraitTitle", "内容漫游者");
        answer.put("portrait", "接触来源多样，主要话题保持相近；这是本次记录中的观察。");
        answer.put("contrasts", "前后期记录覆盖不同，不能据此判断生活方式变化。");
        answer.put("questions", "这段时间你主要是放松还是寻找灵感？");
        analysis.response = answer.toString();
        drain();
        expandSection("settings");
        expandSection("trend");
        find("analytics.analysis.request").performClick();
        finishAnalysis();
        View portrait = find("analytics.analysis.text.portrait");
        View chart = find("analytics.portrait.calendar.chart");
        View share = find("analytics.analysis.share");
        View root = find("analytics.root");
        root.measure(View.MeasureSpec.makeMeasureSpec(360, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, 360, 600);
        Bitmap chartPixels = Bitmap.createBitmap(chart.getWidth(), chart.getHeight(), Bitmap.Config.ARGB_8888);
        chart.draw(new Canvas(chartPixels));
        chartPixels.recycle();
        chart.performClick();
        String selection = text("analytics.portrait.calendar.selection");
        ScrollView scroll = find("analytics.scroll");
        scroll.scrollTo(0, 200);
        int scrollPosition = scroll.getScrollY();
        int snapshotReads = source.reads;
        find("analytics.feedback").performClick();
        AlertDialog feedback = (AlertDialog) ShadowDialog.getLatestDialog();
        feedback.getListView().performItemClick(null, 0, 0);
        ShadowLooper.idleMainLooper();
        assertNull(source.savedFeedback);
        assertFalse(find("analytics.feedback").isEnabled());
        assertSame(portrait, find("analytics.analysis.text.portrait"));
        drain();
        assertEquals("符合", source.savedFeedback);
        assertSame(portrait, find("analytics.analysis.text.portrait"));
        assertSame(chart, find("analytics.portrait.calendar.chart"));
        assertSame(share, find("analytics.analysis.share"));
        assertEquals(selection, text("analytics.portrait.calendar.selection"));
        assertEquals(scrollPosition, scroll.getScrollY());
        assertTrue(text("analytics.feedback").contains("符合"));
        assertEquals(snapshotReads, source.reads);
        assertEquals(1, analysis.requests.size());
        assertFalse(analysis.requests.get(0).cancelled);
    }

    @Test public void topicMigrationShowsMatchedRanksAndMissingPeriodWithoutInventingZeroInterest() throws Exception {
        source.data = portraitFixture();
        drain();
        assertTrue(text("analytics.portrait.comparison.topics.row.0").contains("#生活记录"));
        assertTrue(text("analytics.portrait.comparison.topics.row.0").contains("第 2 · 130 次  →  第 1 · 238 次"));
        assertTrue(text("analytics.portrait.comparison.topics.row.1").contains("未列入摘要"));
        assertTrue(text("analytics.portrait.comparison.topics.row.3").contains("#往日专题"));
        expandSection("changes");
        renderArtifact(find("analytics.chartcard.changes"), 280, "portrait-topic-migration-280.png", false);
        source.data = portraitFixture(false);
        find("analytics.range.30").performClick();
        drain();
        assertEquals(30, source.lastDays);
        assertNull(find("analytics.portrait.comparison.topics.row.0"));
        String missing = text("analytics.portrait.comparison.topics.empty");
        assertTrue(missing.contains("前期缺少可比话题记录"));
        assertTrue(missing.contains("不代表没有兴趣"));
        assertFalse(missing.contains("0 次"));
    }

    private static android.widget.EditText findEditText(View view) {
        if (view instanceof android.widget.EditText) return (android.widget.EditText) view;
        if (view instanceof ViewGroup) for (int index = 0; index < ((ViewGroup) view).getChildCount(); index++) {
            android.widget.EditText found = findEditText(((ViewGroup) view).getChildAt(index));
            if (found != null) return found;
        }
        return null;
    }

    private void render(View view, int width, String name) throws Exception {
        renderArtifact(view, width, name, true);
    }

    private void renderArtifact(View view, int width, String name, boolean fullReport) throws Exception {
        if (fullReport) expandAllSections();
        // Individual-card captures change attached child bounds. Every nested layout must
        // rerun, including the report column whose own measure specs can remain unchanged.
        forceLayoutTree(view);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = view.getMeasuredHeight();
        assertTrue(height > (fullReport ? 1600 : 100));
        view.layout(0, 0, width, height);
        assertNoClippedText(view);
        if (fullReport) assertReportCardsDoNotOverlap(view);
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        canvas.drawColor(ModuleTheme.isDark(activity) ? 0xFF141414 : 0xFFF8FAFA);
        view.draw(canvas);
        File directory = new File("build/reports/local-analytics-screenshots");
        assertTrue(directory.isDirectory() || directory.mkdirs());
        try (FileOutputStream stream = new FileOutputStream(new File(directory, name))) {
            assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream));
        }
        bitmap.recycle();
    }

    private static void forceLayoutTree(View view) {
        view.forceLayout();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) forceLayoutTree(group.getChildAt(index));
        }
    }

    private static void assertReportCardsDoNotOverlap(View body) {
        ViewGroup report = body.findViewWithTag("analytics.report");
        assertNotNull(report);
        int previousBottom = 0;
        for (int index = 0; index < report.getChildCount(); index++) {
            View card = report.getChildAt(index);
            if (card.getVisibility() == View.GONE) continue;
            String label = String.valueOf(card.getTag());
            assertTrue("Report card overlaps its predecessor: " + label, card.getTop() >= previousBottom);
            assertTrue("Report card has no visible height: " + label, card.getBottom() > card.getTop());
            assertTrue("Report card exceeds report bounds: " + label, card.getBottom() <= report.getHeight());
            assertTrue("Report card exceeds report width: " + label, card.getLeft() >= 0 && card.getRight() <= report.getWidth());
            previousBottom = card.getBottom();
        }
    }

    private static void assertNoClippedText(View root) {
        if (root instanceof TextView) {
            TextView text = (TextView) root;
            if (text.getVisibility() == View.VISIBLE && text.getLayout() != null
                    && !String.valueOf(text.getTag()).startsWith("analytics.section.summary.")) {
                for (int line = 0; line < text.getLayout().getLineCount(); line++) {
                    assertEquals(text.getText().toString(), 0, text.getLayout().getEllipsisCount(line));
                }
                assertTrue(text.getText().toString(), text.getLayout().getHeight()
                        <= text.getHeight() - text.getCompoundPaddingTop() - text.getCompoundPaddingBottom());
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int index = 0; index < group.getChildCount(); index++) assertNoClippedText(group.getChildAt(index));
        }
    }

    @SuppressWarnings("unchecked") private <T extends View> T find(String tag) {
        return page.dialog().getWindow().getDecorView().findViewWithTag(tag);
    }
    private String text(String tag) { return ((TextView) find(tag)).getText().toString(); }
    private void expandSection(String key) {
        View toggle = find("analytics.section.toggle." + key);
        if (toggle != null && !toggle.isSelected()) toggle.performClick();
    }
    private void expandAllSections() {
        for (String key : List.of("settings", "portrait", "trend", "types", "topics", "authors", "hours",
                "changes", "associations", "features", "recent", "history")) expandSection(key);
    }
    private void drain() {
        while (true) {
            ShadowLooper.idleMainLooper();
            if (worker.tasks.isEmpty()) return;
            worker.runNext();
        }
    }
    private void finishAnalysis() {
        while (!analysisWorker.tasks.isEmpty()) analysisWorker.runNext();
        ShadowLooper.idleMainLooper();
    }

    private static String analysisResponse() {
        try {
            JSONObject result = new JSONObject();
            for (int index = 0; index < AI_KEYS.length; index++) result.put(AI_KEYS[index], AI_TEXTS[index]);
            return result.toString();
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static AnalyticsSnapshot fixture() {
        long[] hours = {7, 3, 0, 0, 7, 1, 0, 12, 25, 42, 33, 51, 58, 29, 47, 29, 52, 64, 18, 71, 6, 23, 43, 21};
        return new AnalyticsSnapshot(1572, 628, 1419, 9, 7, 7, 1791763200000L,
                Arrays.asList(new Bucket("10/12", 250), new Bucket("10/13", 246), new Bucket("10/14", 226),
                        new Bucket("10/15", 315), new Bucket("10/16", 277), new Bucket("10/17", 187), new Bucket("10/18", 71)),
                Arrays.asList(new Rank("短视频", 1000), new Rank("图集", 300), new Rank("实况图集", 200),
                        new Rank("长视频", 50), new Rank("直播卡片", 20), new Rank("未知", 2)),
                Arrays.asList(new Rank("山间行走的人", 128), new Rank("这是一个较长的作者名称，用于验证窄屏自动换行", 86), new Rank("城市漫游", 62)),
                Arrays.asList(new Rank("#生活记录", 238), new Rank("#旅途中的风景", 177), new Rank("#摄影", 123)),
                Arrays.asList(new Rank("保存媒体", 248), new Rank("AI 作品总结", 183), new Rank("复制文案", 121), new Rank("自动评论", 76)),
                hours, List.of(new Recent(1792281960000L, "保存媒体", "feature"),
                        new Recent(1792281600000L, "实况图集 · 山间行走的人", "view")));
    }

    private static AnalyticsSnapshot portraitFixture() {
        return portraitFixture(true);
    }

    private static AnalyticsSnapshot portraitFixture(boolean hasPreviousTopics) {
        AnalyticsSnapshot base = fixture();
        List<PortraitSnapshot.Day> days = new ArrayList<>();
        long[][] weekdayHours = new long[7][24];
        for (int index = 0; index < 7; index++) {
            long count = base.trend.get(index).count;
            days.add(new PortraitSnapshot.Day("2026-10-" + (12 + index), count, 30 + index * 10, count - 20,
                    Map.of("短视频", count * 3 / 5, "实况图集", count / 5, "图集", count - count * 3 / 5 - count / 5)));
            weekdayHours[index][19] = count / 2;
            weekdayHours[index][13] = count - count / 2;
        }
        PortraitSnapshot.Comparison comparison = new PortraitSnapshot.Comparison("2026-10-12", "2026-10-18",
                "2026-10-05", "2026-10-11", 1572, 1210, 628, 810, base.types, base.types,
                false, false, "今天尚未结束；前期部分日期未开启采集。记录计数只能描述已观察样本。",
                base.topics, hasPreviousTopics ? List.of(new Rank("#摄影", 200), new Rank("#生活记录", 130),
                        new Rank("#往日专题", 100), new Rank("其他（11 类）", 80)) : List.of(), base.authors, base.authors);
        PortraitSnapshot portrait = new PortraitSnapshot("2026-10-12", "2026-10-18", days, weekdayHours,
                base.topics, base.authors, List.of(new Rank("主动操作", 301), new Rank("自动触发", 327)),
                7, 42, 17, 538, 153, 45, 40, 123_000, 0,
                .79, .55, .23, .44, .108, .12, .32, comparison, comparison, comparison,
                List.of(new PortraitSnapshot.Insight("sources", "主线稳定，接触来源多样", "作者来源分散，主要话题仍围绕生活与旅行。",
                        "本期识别到 42 位作者、17 个话题。作者来源多样性 0.79，话题多样性 0.55。", "多样性是内容分布的指标，不是心理分数。"),
                        new PortraitSnapshot.Insight("tools", "工具使用有自己的习惯", "保存、总结与复制进入了你的使用流程。",
                                "功能共触发 628 次；主动操作 301 次，自动触发 327 次。", "触发不能证明请求成功，自动行为不是主动认可。")),
                List.of(new PortraitSnapshot.Joint("#旅行", "19 时", 20)),
                List.of(new PortraitSnapshot.Joint("图集", "保存媒体 · 主动操作", 12)),
                List.of("仅覆盖已接入的宿主页面，今天尚未结束。", "停留记录从新版本开始采集，历史汇总不能补齐。"), "初步样本");
        return new AnalyticsSnapshot(base.totalViews, base.totalFeatureUses, base.uniqueWorks, base.advertisementViews,
                base.activeDays, base.currentStreak, base.firstSeen, base.trend, base.types, base.authors, base.topics,
                base.features, base.hours, base.recent, portrait);
    }

    private static final class ManualExecutor extends AbstractExecutorService {
        final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        boolean shutdown;
        @Override public void execute(Runnable command) {
            if (shutdown) throw new RejectedExecutionException();
            tasks.add(command);
        }
        @Override public void shutdown() { shutdown = true; }
        @Override public List<Runnable> shutdownNow() {
            shutdown = true;
            List<Runnable> cancelled = new ArrayList<>(tasks);
            tasks.clear();
            return cancelled;
        }
        @Override public boolean isShutdown() { return shutdown; }
        @Override public boolean isTerminated() { return shutdown && tasks.isEmpty(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return isTerminated(); }
        void runNext() { tasks.remove().run(); }
    }

    private static final class FakeSource implements LocalAnalyticsDialog.Source {
        boolean enabled;
        boolean failure;
        int reads;
        int clears;
        int lastDays;
        String savedPurpose;
        String savedFeedback;
        AnalyticsSnapshot data = AnalyticsSnapshot.empty();
        @Override public boolean isEnabled() { return enabled; }
        @Override public void setEnabled(boolean enabled) { this.enabled = enabled; }
        @Override public AnalyticsSnapshot snapshot(int days) {
            reads++;
            lastDays = days;
            if (failure) throw new IllegalStateException("Test failure");
            return data;
        }
        @Override public void clear() { clears++; data = AnalyticsSnapshot.empty(); }
        @Override public void purpose(String value) { savedPurpose = value; }
        @Override public void feedback(String value) { savedFeedback = value; }
    }

    private static final class FakeAnalysisService implements LocalAnalyticsDialog.AnalysisService {
        boolean configured = true;
        boolean fail;
        RuntimeException prepareFailure;
        Exception requestFailure;
        String response = analysisResponse();
        AnalyticsSnapshot snapshot;
        int days;
        final List<FakeRequest> requests = new ArrayList<>();
        @Override public boolean isConfigured() { return configured; }
        @Override public String modelLabel() { return "测试模型 · test-only"; }
        @Override public Request prepare(AnalyticsSnapshot snapshot, int days) {
            if (prepareFailure != null) throw prepareFailure;
            this.snapshot = snapshot;
            this.days = days;
            FakeRequest request = new FakeRequest(fail, response, requestFailure);
            requests.add(request);
            return request;
        }
        private static final class FakeRequest implements Request {
            final boolean fail;
            final String response;
            final Exception failure;
            int runs;
            boolean cancelled;
            FakeRequest(boolean fail, String response, Exception failure) {
                this.fail = fail; this.response = response; this.failure = failure;
            }
            @Override public String modelLabel() { return "测试模型 · test-only"; }
            @Override public String run() throws Exception {
                runs++;
                if (failure != null) throw failure;
                if (fail) throw new IllegalStateException("secret-test-provider-response");
                return response;
            }
            @Override public void cancel() { cancelled = true; }
        }
    }
}
