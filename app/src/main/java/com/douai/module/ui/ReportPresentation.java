package com.douai.module.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.analytics.AnalyticsAiAnalysis;
import com.douai.module.analytics.AnalyticsSnapshot;
import com.douai.module.analytics.AnalyticsSnapshot.Rank;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/** Independent paper layout. It owns no Activity, controls, requests, or on-screen Views. */
final class ReportPresentation {
    static final int LOGICAL_WIDTH = 540;
    private static final int INK = 0xFF182C3A, MUTED = 0xFF61727E, TEAL = 0xFF087F80;
    private final Context context;
    private final LinearLayout paper;
    private int sectionNumber;

    static LinearLayout build(Context application, AnalyticsSnapshot data, String range, long generatedAt,
            AnalyticsAiAnalysis.Result ai, String model, String analyzedRange, long analyzedAt) {
        Context owner = application.getApplicationContext();
        Configuration configuration = new Configuration(owner.getResources().getConfiguration());
        configuration.densityDpi = 160; configuration.fontScale = 1f;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK) | Configuration.UI_MODE_NIGHT_NO;
        ReportPresentation report = new ReportPresentation(owner.createConfigurationContext(configuration));
        boolean journal = data.portrait.evidence.hasStories();
        if (!journal) report.cover(range, generatedAt);
        if (journal) report.small(report.paper, range + "  ·  生成于 " + date(generatedAt, "yyyy-MM-dd HH:mm"));
        if (journal) report.portrait(data);
        report.metrics(data);
        LinearLayout scope = report.section("记录概览", "已记录事件");
        report.body(scope, "刷到次数包括再次遇见同一作品；独立作品按标识去重。模块功能次数按已接入的触发统计。");
        if (data.advertisementViews > 0) report.body(scope, "其中广告接触 " + number(data.advertisementViews) + " 次。");
        if (!journal && data.totalViews == 0 && data.totalFeatureUses == 0) report.body(scope, "当前范围暂无保留记录。空白不代表没有使用。");
        if (!journal) report.portrait(data);
        if (!journal && data.portrait.days.isEmpty()) {
            LinearLayout trend = report.section("浏览趋势", "本地统计 · 按日期汇总");
            if (data.trend.isEmpty()) report.body(trend, "暂无趋势记录。");
            else {
                trend.addView(new Trend(report.context, data.trend), new LinearLayout.LayoutParams(-1, 150));
                for (AnalyticsSnapshot.Bucket point : data.trend) report.small(trend, point.label + "  ·  " + number(point.count) + " 次");
            }
            LinearLayout types = report.ranking("作品类型", data.types, "暂无作品类型记录。");
            report.small(types, "Live 指实况照片，混合图集中含实况照片时计为一次实况图集。长视频为时长 ≥ 5 分钟的视频；无法识别的内容列为未知或其他。直播卡片不代表进入直播间或观看时长。");
            LinearLayout hours = report.section("一天中的接触时段", "本地统计 · 当地时间");
            for (int hour = 0; hour < data.hours.length; hour++) {
                if (data.hours[hour] > 0) report.bar(hours, String.format(Locale.ROOT, "%02d:00—%02d:00", hour, (hour + 1) % 24),
                        data.hours[hour], maximum(data.hours));
            }
            report.small(hours, "按事件发生时的本地时间计数，不代表睡眠、实际观看时长或生活作息。");
        }
        if (!coveredBy(data.authors, data.portrait.authorPareto)) report.ranking("作者排行", data.authors, "暂无可识别作者。");
        if (!coveredBy(data.topics, data.portrait.topicTiles)) report.ranking("话题排行", data.topics, "暂无可识别话题。");
        report.ranking("模块功能排行", data.features, "暂无模块功能记录。");
        LinearLayout recent = report.section("最近记录", "本地统计 · 逐条记录摘要");
        if (data.recent.isEmpty()) report.body(recent, "暂无最近记录。");
        for (AnalyticsSnapshot.Recent item : data.recent) {
            report.body(recent, item.label);
            report.small(recent, date(item.timestamp, "MM-dd HH:mm") + "  ·  " + ("feature".equals(item.kind) ? "模块功能" : "浏览作品"));
        }
        LinearLayout history = report.section("使用历程", "本地统计 · 保留记录范围");
        report.body(history, "活跃 " + number(data.activeDays) + " 天  /  当前连续 " + number(data.currentStreak) + " 天");
        report.small(history, data.firstSeen > 0 ? "首次保留记录：" + date(data.firstSeen, "yyyy-MM-dd HH:mm") : "暂无首次记录时间。");
        report.small(history, "活跃天数与连续天数基于所选范围内保留的记录；空白日期可能是采集关闭或未覆盖。连续活跃允许今天尚未产生记录。首次记录从上次清空后计算，可早于 365 天。");
        report.analysis(ai, model, analyzedRange, analyzedAt);
        LinearLayout boundary = report.section("来源与阅读边界", "关于这份报告");
        report.body(boundary, "本地统计来自此设备已接入且开启采集的记录。采集空白不等于没有使用，浏览次数不是实际观看时长，功能触发不等于请求成功。");
        report.small(boundary, "图表是本地统计；AI 解读来自报告中注明的那次请求，不是重新分析。接触内容、自动行为与主动偏好应分开理解。这不是心理测评，不推断职业、健康、政治或稳定人格。");
        report.small(boundary, "分享前请核对作者、话题、最近记录和 AI 文字；AI 可能引用已提供的使用目的或自述。报告不附加数据库、接口配置或密钥。");
        report.small(report.paper, "DOUAI  /  LOCAL REPORT     ·     " + range);
        return report.paper;
    }

    private void portrait(AnalyticsSnapshot data) {
        PortraitReportViews.appendPaper(context, data.portrait, new PortraitReportViews.Host() {
            @Override public LinearLayout section(String title) {
                if ("这段时间的发现".equals(title)) {
                    LinearLayout cover = column();
                    paper.addView(cover, wrap());
                    return cover;
                }
                return ReportPresentation.this.section(title, "本地数据");
            }
            @Override public void analysis(LinearLayout section, String key) { /* Render once in the AI chapter. */ }
        });
    }

    private ReportPresentation(Context context) {
        this.context = context; paper = column(); paper.setTag("report.paper");
        paper.setBackgroundColor(0xFFFFFFFF); paper.setPadding(34, 36, 34, 30);
        paper.setLayoutParams(new LinearLayout.LayoutParams(LOGICAL_WIDTH, -2));
    }

    private void cover(String range, long generatedAt) {
        TextView eyebrow = text("DOUAI  /  LOCAL INSIGHTS", 11, TEAL, true);
        eyebrow.setLetterSpacing(.12f); paper.addView(eyebrow, wrap());
        TextView title = text("我的本地使用报告", 29, INK, true);
        title.setPadding(0, 14, 0, 9); paper.addView(title, wrap());
        TextView subtitle = text("看见记录，也保留未知", 14, MUTED, false); paper.addView(subtitle, wrap());
        TextView metadata = text(range + "\n报告生成  " + date(generatedAt, "yyyy-MM-dd HH:mm"), 12, MUTED, false);
        metadata.setPadding(0, 18, 0, 20); paper.addView(metadata, wrap());
        View rule = new View(context); rule.setBackgroundColor(TEAL); paper.addView(rule, new LinearLayout.LayoutParams(52, 3));
    }

    private void metrics(AnalyticsSnapshot data) {
        LinearLayout grid = column(); LinearLayout.LayoutParams margin = wrap(); margin.topMargin = 25; paper.addView(grid, margin);
        String[] labels = {"浏览接触", "独立作品", "功能触发", "活跃天数"};
        long[] values = {data.totalViews, data.uniqueWorks, data.totalFeatureUses, data.activeDays};
        for (int row = 0; row < 2; row++) {
            LinearLayout line = new LinearLayout(context);
            for (int col = 0; col < 2; col++) {
                int index = row * 2 + col;
                LinearLayout card = column(); card.setPadding(18, 15, 18, 15); card.setBackground(background(0xFFF1F7F8, 0, 10));
                card.addView(text(labels[index], 12, MUTED, false), wrap());
                TextView value = text(number(values[index]), 28, INK, true); value.setPadding(0, 5, 0, 0); card.addView(value, wrap());
                LinearLayout.LayoutParams box = new LinearLayout.LayoutParams(0, -2, 1); box.bottomMargin = 10;
                if (col == 0) box.rightMargin = 10; line.addView(card, box);
            }
            grid.addView(line, wrap());
        }
    }

    private LinearLayout section(String title, String source) {
        LinearLayout block = column(); LinearLayout.LayoutParams params = wrap(); params.topMargin = 25;
        paper.addView(block, params);
        LinearLayout heading = column(); heading.setTag("report.keepWithNext");
        TextView index = text(String.format(Locale.ROOT, "%02d  /  %s", ++sectionNumber, source), 10, TEAL, true);
        index.setLetterSpacing(.04f); heading.addView(index, wrap());
        TextView name = text(title, 20, INK, true); name.setPadding(0, 5, 0, 10); heading.addView(name, wrap());
        View divider = new View(context); divider.setBackgroundColor(0xFFDDE8EB); heading.addView(divider, new LinearLayout.LayoutParams(-1, 1));
        block.addView(heading, wrap());
        return block;
    }

    private LinearLayout ranking(String title, List<Rank> entries, String empty) {
        LinearLayout block = section(title, "本地统计 · 前 10 项");
        if (entries.isEmpty()) { body(block, empty); return block; }
        long max = 1; for (Rank rank : entries) max = Math.max(max, rank.count);
        for (int index = 0; index < Math.min(10, entries.size()); index++) {
            Rank rank = entries.get(index); bar(block, (index + 1) + "  " + rank.label, rank.count, max);
        }
        return block;
    }

    private void bar(LinearLayout block, String label, long count, long max) {
        LinearLayout row = new LinearLayout(context); row.setGravity(Gravity.TOP); row.setPadding(0, 12, 0, 5);
        row.addView(text(label, 13, INK, false), new LinearLayout.LayoutParams(0, -2, 1));
        TextView value = text(number(count), 13, MUTED, true); value.setPadding(10, 0, 0, 0); row.addView(value, new LinearLayout.LayoutParams(-2, -2));
        block.addView(row, wrap());
        View chart = new View(context) {
            final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            @Override protected void onDraw(Canvas canvas) {
                paint.setColor(0xFFEEF3F5); canvas.drawRoundRect(0, 0, getWidth(), 4, 2, 2, paint);
                paint.setColor(TEAL); canvas.drawRoundRect(0, 0, getWidth() * count / Math.max(1f, max), 4, 2, 2, paint);
            }
        };
        block.addView(chart, new LinearLayout.LayoutParams(-1, 5));
    }

    private void analysis(AnalyticsAiAnalysis.Result ai, String model, String range, long at) {
        LinearLayout section = section("AI 解读", "AI 生成 · 与本地统计分开阅读");
        if (ai == null) { body(section, "此报告尚无 AI 分析结果。导出没有请求 AI。"); return; }
        small(section, range + "  ·  " + model + "\n分析生成  " + date(at, "yyyy-MM-dd HH:mm") + "\n以下仅属于当时请求的统计快照，仅供参考。");
        if (ai.hasPortrait()) {
            aiText(section, ai.portraitTitle, ai.portrait);
            aiText(section, "前后对照", ai.contrasts); aiText(section, "待确认的问题", ai.questions);
        }
        String[] titles = {"整体观察", "浏览趋势", "内容构成", "作者接触", "话题接触", "功能使用", "时段节奏"};
        String[] values = {ai.overview, ai.trend, ai.types, ai.authors, ai.topics, ai.features, ai.hours};
        for (int index = 0; index < titles.length; index++) aiText(section, titles[index], values[index]);
    }
    private void aiText(LinearLayout section, String title, String value) {
        TextView heading = text(title, 15, TEAL, true); heading.setTag("report.keepWithNext");
        heading.setPadding(0, 16, 0, 3); section.addView(heading, wrap());
        body(section, value);
    }
    private void body(LinearLayout parent, String value) { TextView t = text(value, 13, INK, false); t.setPadding(0, 10, 0, 2); parent.addView(t, wrap()); }
    private void small(LinearLayout parent, String value) { TextView t = text(value, 11, MUTED, false); t.setPadding(0, 9, 0, 2); parent.addView(t, wrap()); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false); text.setLineSpacing(3, 1.08f);
        text.setTypeface(Typeface.create(bold ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL));
        return text;
    }
    private LinearLayout column() { LinearLayout column = new LinearLayout(context); column.setOrientation(LinearLayout.VERTICAL); return column; }
    private static LinearLayout.LayoutParams wrap() { return new LinearLayout.LayoutParams(-1, -2); }
    private static GradientDrawable background(int color, int stroke, int radius) {
        GradientDrawable background = new GradientDrawable(); background.setColor(color); background.setCornerRadius(radius);
        if (stroke != 0) background.setStroke(1, stroke); return background;
    }
    private static String number(long value) { return String.format(Locale.US, "%,d", value); }
    private static long maximum(long[] values) { long maximum = 1; for (long value : values) maximum = Math.max(maximum, value); return maximum; }
    private static boolean coveredBy(List<Rank> ranks, List<Rank> shown) {
        if (ranks.isEmpty()) return false;
        for (int i = 0; i < Math.min(10, ranks.size()); i++) {
            Rank rank = ranks.get(i); boolean found = false;
            for (Rank item : shown) if (rank.label.equals(item.label) && rank.count == item.count) { found = true; break; }
            if (!found) return false;
        }
        return true;
    }
    private static String date(long value, String format) { return new SimpleDateFormat(format, Locale.getDefault()).format(new Date(value)); }

    private static final class Trend extends View {
        private final List<AnalyticsSnapshot.Bucket> points;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path line = new Path();
        Trend(Context context, List<AnalyticsSnapshot.Bucket> points) { super(context); this.points = points; }
        @Override protected void onDraw(Canvas canvas) {
            float left = 8, top = 14, bottom = getHeight() - 22, width = getWidth() - 16;
            long max = 1; for (AnalyticsSnapshot.Bucket point : points) max = Math.max(max, point.count);
            paint.setStrokeWidth(1); paint.setColor(0xFFE2EBEF);
            for (int line = 0; line < 4; line++) { float y = top + (bottom - top) * line / 3; canvas.drawLine(left, y, left + width, y, paint); }
            line.reset();
            for (int i = 0; i < points.size(); i++) {
                float x = left + width * i / Math.max(1, points.size() - 1), y = bottom - (bottom - top) * points.get(i).count / max;
                if (i == 0) line.moveTo(x, y); else line.lineTo(x, y);
            }
            paint.setColor(TEAL); paint.setStrokeWidth(2.5f); paint.setStyle(Paint.Style.STROKE); canvas.drawPath(line, paint); paint.setStyle(Paint.Style.FILL);
        }
    }
}
