package com.douai.module.ui;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Picture;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.analytics.AnalyticsSnapshot.Rank;
import com.douai.module.analytics.PortraitSnapshot;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** All geometry and summaries are local; AI text is attached by the owning dialog. */
final class PortraitReportViews {
    private static final int TEXT = 0xFF1F272D, MUTED = 0xFF69737A, ACCENT = 0xFF008579;
    private static final int[] COLORS = {0xFF254763, 0xFFA74429, 0xFF5F5180, 0xFF286361,
            0xFF85522C, 0xFF435B78, 0xFF87405E, 0xFF58603F, 0xFF586168};
    interface Host {
        LinearLayout section(String title);
        void analysis(LinearLayout section, String key);
        default Button button(Context context) { return new Button(context); }
        default View controlView(View control) { return control; }
    }
    private final Context context;
    private final PortraitSnapshot data;
    private final Host host;
    private final boolean paper;
    private final JournalTopicColors topicColors;
    PortraitReportViews(Context context, PortraitSnapshot data, Host host) {
        this(context, data, host, false);
    }
    private PortraitReportViews(Context context, PortraitSnapshot data, Host host, boolean paper) {
        this.context = context; this.data = data; this.host = host;
        this.paper = paper;
        topicColors = new JournalTopicColors(data.evidence, data.topicTiles, data.comparison);
    }
    /** A fresh, noninteractive report tree; never shares a View with the dialog. */
    static void appendPaper(Context context, PortraitSnapshot data, Host host) {
        PortraitReportViews report = new PortraitReportViews(context, data, host, true);
        if (data.evidence != null && data.evidence.hasStories()) {
            report.journal();
            return;
        }
        report.overview();
        if (!data.days.isEmpty()) { report.calendar(); report.typeBands(); }
        if (!data.topicTiles.isEmpty() || data.topicCount > 0) report.topics();
        if (!data.authorPareto.isEmpty() || data.authorCount > 0) report.authors();
        boolean rhythm = false;
        for (long[] day : data.weekdayHours) for (long count : day) if (count > 0) rhythm = true;
        if (rhythm) report.rhythm();
        if (data.comparison != null && !data.comparison.currentStart.isEmpty()) report.comparison();
        if (!data.featureSources.isEmpty() || !data.topicHours.isEmpty() || !data.typeActions.isEmpty()
                || data.capturedVisits > 0 || data.finishedVisits > 0 || data.visibleMillis > 0) report.associations();
    }

    /** Keep chapter order and measured series identical on the phone and on paper. */
    void journal() {
        PortraitStoryViews stories = new PortraitStoryViews(context, data.evidence, host, paper, topicColors);
        stories.overview();
        LinearLayout content = host.section("你的内容版图");
        if (!data.topicTiles.isEmpty()) { heading(content, "话题面积 · 标签出现次数"); topics(content, true); }
        if (!data.authorPareto.isEmpty()) { heading(content, "接触来源 · 作者累计占比"); authors(content, true); }
        stories.content(content);
        LinearLayout clock = host.section("一天里的节奏");
        rhythm(clock, true);
        JournalComparisons.rhythm(context, clock, data.weekdayHours, paper);
        LinearLayout actions = host.section("刷到之后，你做了什么");
        stories.actions(actions);
        LinearLayout changes = host.section("最近为什么变了");
        JournalComparisons.ranks(context, changes, data.comparison, paper, topicColors);
        stories.changes(changes);
    }

    void overview() {
        LinearLayout card = host.section("这段时间的你");
        card.setTag("analytics.portrait.overview");
        add(card, "由本地行为记录拼出的阶段侧写", 13, MUTED, false);
        add(card, data.evidenceLevel + " · " + data.startDate + " — " + data.endDate, 13, MUTED, false);
        if (data.insights.isEmpty()) {
            add(card, "还没有足够的记录来描述你。继续使用后，内容、节奏与工具习惯会逐渐清晰。", 14, TEXT, false);
        }
        for (PortraitSnapshot.Insight insight : data.insights) {
            heading(card, insight.title);
            add(card, insight.body, 14, TEXT, false);
            disclosure(card, "查看依据与边界", insight.evidence + "\n" + insight.limitations,
                    "analytics.portrait.evidence." + insight.id);
        }
        disclosure(card, "这份侧写知道什么、还不知道什么", String.join("\n\n", data.coverage)
                + "\n\n刷到描述内容接触，不能单独证明喜欢。记录空白不等于没有使用；自动触发不能当作主动选择。"
                + "\n这不是心理测评，不推断职业、健康、政治或稳定人格。", "analytics.portrait.coverage");
        host.analysis(card, "portrait");
    }

    void charts() {
        calendar(); typeBands(); topics(); authors(); rhythm(); comparison(); associations();
    }

    private void calendar() {
        LinearLayout card = host.section("浏览足迹 · 日期热力图");
        List<PortraitSnapshot.Day> shown = data.days.subList(Math.max(0, data.days.size() - 84), data.days.size());
        TextView selected = add(card, paper ? "按日期排列的本地浏览记录" : "点选日期，查看那一天的浏览与功能次数", 13, MUTED, false);
        selected.setTag("analytics.portrait.calendar.selection");
        chart(card, new CalendarChart(context, shown, value -> select(selected, value)),
                "calendar", Math.max(108, 38 + (int) Math.ceil((shown.size() + 6) / 7.0) * 22));
        add(card, "色彩表示浏览次数；空心格表示无保留事件，可能是采集关闭或未覆盖。"
                + (data.days.size() > 84 ? "当前展示范围内最近 84 天。" : ""), 13, MUTED, false);
        host.analysis(card, "trend");
    }

    private void typeBands() {
        LinearLayout card = host.section("内容构成 · 随时间变化");
        List<Band> bands = new ArrayList<>();
        Map<String, Integer> colors = new LinkedHashMap<>();
        int step = Math.max(1, (int) Math.ceil(data.days.size() / 6.0));
        for (int start = 0; start < data.days.size(); start += step) {
            int end = Math.min(start + step, data.days.size());
            Map<String, Long> counts = new LinkedHashMap<>();
            for (int index = start; index < end; index++) {
                for (Map.Entry<String, Long> type : data.days.get(index).types.entrySet()) {
                    counts.merge(type.getKey(), type.getValue(), Long::sum);
                    if (!colors.containsKey(type.getKey())) colors.put(type.getKey(), COLORS[colors.size() % COLORS.length]);
                }
            }
            String from = shortDate(data.days.get(start).date), to = shortDate(data.days.get(end - 1).date);
            bands.add(new Band(from.equals(to) ? from : from + "—" + to, counts));
        }
        TextView selected = add(card, paper ? "每行归一为 100%，色带表示该时段的内容构成" : "每行归一为 100%，点选色带查看原始次数", 13, MUTED, false);
        selected.setTag("analytics.portrait.types.selection");
        chart(card, new TypeChart(context, bands, colors, value -> select(selected, value)),
                "types", Math.max(76, bands.size() * 52));
        for (Map.Entry<String, Integer> item : colors.entrySet()) legend(card, item.getKey(), item.getValue());
        add(card, "比较的是已记录内容构成；空行表示缺少记录。Live 为实况照片；视频时长 ≥ 5 分钟计为长视频。", 13, MUTED, false);
        host.analysis(card, "types");
    }

    private void topics() {
        topics(host.section(paper ? "话题排行 · 你的内容地图" : "你的内容世界 · 话题地图"), false);
    }
    private void topics(LinearLayout card, boolean journal) {
        if (data.topicTiles.isEmpty()) add(card, "暂无可识别话题，不能据此判断兴趣是否广泛。", 14, MUTED, false);
        else {
            LinearLayout figure = paperFigure(card);
            TextView selected = add(figure, paper ? "面积代表话题出现次数，完整名称与次数列于图下" : "面积代表话题出现次数，点选色块查看完整名称", 13, MUTED, false);
            selected.setTag(journal ? "analytics.journal.topics.selection" : "analytics.portrait.topics.selection");
            chart(figure, new TopicChart(context, data.topicTiles, topicColors, value -> select(selected, value)), journal ? "journal.topics" : "topics", 175);
            for (int index = 0; index < data.topicTiles.size(); index++) {
                Rank rank = data.topicTiles.get(index);
                legend(index == 0 ? figure : card, rank.label + " · " + number(rank.count) + " 次", topicColors.color(rank.label, !paper && ModuleTheme.isDark(context)));
            }
        }
        add(card, "识别到 " + number(data.topicCount) + " 个话题，共 " + number(data.topicOccurrences)
                + " 次标签出现。一个作品可能有多个话题；标签不是独立作品，也不是使用者自述兴趣。", 13, MUTED, false);
        if (!journal) host.analysis(card, "topics");
    }

    private void authors() {
        authors(host.section(paper ? "作者排行 · 接触来源曲线" : "接触来源 · 作者集中曲线"), false);
    }
    private void authors(LinearLayout card, boolean journal) {
        if (data.authorPareto.isEmpty()) add(card, "暂无可识别作者，暂不能描述接触来源。", 14, MUTED, false);
        else {
            LinearLayout figure = paperFigure(card);
            TextView selected = add(figure, "按出现次数排序，曲线表示累计占比", 13, MUTED, false);
            selected.setTag(journal ? "analytics.journal.authors.selection" : "analytics.portrait.authors.selection");
            chart(figure, new AuthorChart(context, data.authorPareto, value -> select(selected, value)), journal ? "journal.authors" : "authors", 165);
            for (int index = 0; index < data.authorPareto.size(); index++) {
                Rank rank = data.authorPareto.get(index);
                add(index == 0 ? figure : card, (index + 1) + "  " + rank.label + " · " + number(rank.count) + " 次", 13, MUTED, false);
            }
        }
        add(card, "共接触 " + number(data.authorCount) + " 位可识别作者。跨日再次遇见的作品占比 "
                + pct(data.crossDayRepeatShare) + "。反复刷到可能来自推荐，不等于主动关注或重看。", 13, MUTED, false);
        host.analysis(card, "authors");
    }

    private void rhythm() {
        rhythm(host.section("一周节奏 · 星期 × 小时"), false);
    }
    private void rhythm(LinearLayout card, boolean journal) {
        TextView selected = add(card, paper ? "颜色深浅表示各星期、小时的记录次数" : "点选格子查看次数；辅助功能点击可依次查看", 13, MUTED, false);
        selected.setTag(journal ? "analytics.journal.rhythm.selection" : "analytics.portrait.rhythm.selection");
        chart(card, new RhythmChart(context, data.weekdayHours, value -> select(selected, value)), journal ? "journal.rhythm" : "rhythm", 220);
        add(card, "按事件发生时的本地时间统计。夜间（0—5 时）占 " + pct(data.nightShare)
                + "，周末占 " + pct(data.weekendShare)
                + "。所选范围的工作日和周末天数可能不同；不代表睡眠或生活作息。", 13, MUTED, false);
        host.analysis(card, "hours");
    }

    private void comparison() {
        LinearLayout card = host.section("最近的变化 · 与自己对照");
        PortraitSnapshot.Comparison comparison = data.comparison;
        if (comparison == null) { add(card, "暂无可比较的前一时段。", 14, MUTED, false); return; }
        add(card, "本期 " + comparison.currentStart + " — " + comparison.currentEnd
                + "\n前期 " + comparison.previousStart + " — " + comparison.previousEnd, 13, MUTED, false);
        if (!comparison.currentComplete || !comparison.previousComplete) {
            add(card, "记录覆盖尚不完整，暂不判断上升或下降。", 14, TEXT, true);
        }
        TextView selected = add(card, "灰点为前期，蓝点为本期；每项使用独立刻度", 13, MUTED, false);
        selected.setTag("analytics.portrait.comparison.selection");
        chart(card, new CompareChart(context, comparison, value -> select(selected, value)), "comparison", 155);
        add(card, comparison.limitation, 13, MUTED, false);
        topicChanges(card, comparison);
        if (data.weekComparison != null) periodEvidence(card, "最近 7 天", data.weekComparison);
        if (data.monthComparison != null) periodEvidence(card, "最近 30 天", data.monthComparison);
        host.analysis(card, "changes");
    }

    private void topicChanges(LinearLayout card, PortraitSnapshot.Comparison comparison) {
        heading(card, "话题迁移 · 前期 → 本期");
        List<Rank> current = namedTopics(comparison.currentTopics);
        List<Rank> previous = namedTopics(comparison.previousTopics);
        if (current.isEmpty() || previous.isEmpty()) {
            String missing = current.isEmpty() && previous.isEmpty() ? "两期" : current.isEmpty() ? "本期" : "前期";
            TextView empty = add(card, missing + "缺少可比话题记录，暂不判断主题变化。采集空白或未识别话题不代表没有兴趣。", 13, MUTED, false);
            empty.setTag("analytics.portrait.comparison.topics.empty");
            return;
        }
        List<String> labels = new ArrayList<>();
        for (int index = 0; index < Math.min(3, current.size()); index++) labels.add(current.get(index).label);
        for (int index = 0; index < Math.min(3, previous.size()) && labels.size() < 5; index++) {
            String label = previous.get(index).label;
            if (!labels.contains(label)) labels.add(label);
        }
        for (int index = 0; index < labels.size(); index++) {
            String name = labels.get(index);
            TextView row = add(card, name + "\n" + rankDescription(previous, name) + "  →  "
                    + rankDescription(current, name), 13, MUTED, false);
            row.setTag("analytics.portrait.comparison.topics.row." + index);
        }
        add(card, "展示两期主要话题的名次和记录次数。未列入摘要不等于零；今天尚未结束，次数受采集覆盖影响。榜单迁移不直接等于兴趣改变。", 13, MUTED, false);
    }

    private static List<Rank> namedTopics(List<Rank> ranks) {
        List<Rank> result = new ArrayList<>();
        for (Rank rank : ranks) if (!rank.label.startsWith("其他（")) result.add(rank);
        return result;
    }

    private static String rankDescription(List<Rank> ranks, String name) {
        for (int index = 0; index < ranks.size(); index++) if (ranks.get(index).label.equals(name)) {
            return "第 " + (index + 1) + " · " + number(ranks.get(index).count) + " 次";
        }
        return "未列入摘要";
    }

    private void periodEvidence(LinearLayout card, String label, PortraitSnapshot.Comparison item) {
        disclosure(card, label + "对照依据", "本期 " + item.currentStart + " — " + item.currentEnd + "："
                + number(item.currentViews) + " 次浏览，" + number(item.currentFeatures) + " 次功能触发。"
                + "\n前期 " + item.previousStart + " — " + item.previousEnd + "：" + number(item.previousViews)
                + " 次浏览，" + number(item.previousFeatures) + " 次功能触发。\n" + item.limitation,
                "analytics.portrait.period." + label);
    }

    private void associations() {
        LinearLayout card = host.section("从接触到行动 · 关联线索");
        if (!data.featureSources.isEmpty()) {
            heading(card, "功能触发来源");
            for (Rank source : data.featureSources) add(card, source.label + " · " + number(source.count) + " 次", 13, MUTED, false);
        }
        if (data.topicHours.isEmpty() && data.typeActions.isEmpty()) {
            add(card, "尚无可关联的记录。不能把独立的夜间次数、话题次数与保存次数拼成同一次行为。", 14, MUTED, false);
        }
        if (!data.topicHours.isEmpty()) {
            heading(card, "同一次接触中的话题与时段");
            joint(card, data.topicHours, "topicHours");
            add(card, "时段指可见片段开始的小时；每段可含多个话题。这里不是停留时长分布。", 13, MUTED, false);
        }
        if (!data.typeActions.isEmpty()) {
            heading(card, "同一次接触中的内容与动作");
            joint(card, data.typeActions, "typeActions");
        }
        add(card, "可见片段 " + number(data.capturedVisits) + " 段；已结束 " + number(data.finishedVisits)
                + " 段，确认可见停留合计 " + duration(data.visibleMillis) + "。停留不等于完整观看或喜欢；自动行为单独标注。", 13, MUTED, false);
        host.analysis(card, "associations");
    }

    private void joint(LinearLayout card, List<PortraitSnapshot.Joint> joints, String key) {
        int count = Math.min(8, joints.size());
        for (int index = 0; index < count; index++) {
            PortraitSnapshot.Joint item = joints.get(index);
            TextView row = add(card, item.left + "  →  " + item.right + "\n" + number(item.count) + " 次关联", 13, TEXT, false);
            row.setTag("analytics.portrait." + key + "." + index);
        }
    }

    private LinearLayout paperFigure(LinearLayout card) {
        if (!paper) return card;
        LinearLayout figure = new LinearLayout(context); figure.setOrientation(LinearLayout.VERTICAL);
        figure.setTag("report.keepTogether"); card.addView(figure, -1, -2); return figure;
    }
    private void legend(LinearLayout card, String text, int color) {
        LinearLayout row = new LinearLayout(context);
        row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        View swatch = new View(context); swatch.setBackgroundColor(color);
        ModuleTheme.preserveColors(swatch);
        row.addView(swatch, new LinearLayout.LayoutParams(dp(10), dp(10)));
        TextView label = new TextView(context); label.setText(text); label.setTextSize(13); label.setTextColor(TEXT);
        label.setIncludeFontPadding(false);
        label.setPadding(dp(8), dp(5), 0, dp(5));
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(row, new LinearLayout.LayoutParams(-1, -2));
    }
    private void chart(LinearLayout parent, View view, String key, int height) {
        if (paper && view instanceof Chart) {
            ((Chart) view).paper = true;
            view.setClickable(false); view.setFocusable(false);
        }
        view.setTag("analytics.portrait." + key + ".chart");
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, dp(height));
        params.topMargin = dp(8);
        params.bottomMargin = dp(4);
        parent.addView(view, params);
    }
    private void heading(LinearLayout parent, String value) {
        TextView text = add(parent, value, 16, TEXT, true);
        if (paper) text.setTag("report.keepWithNext");
        text.setPadding(0, dp(12), 0, dp(4));
    }
    private TextView add(LinearLayout parent, String value, int size, int color, boolean bold) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(size); text.setTextColor(color);
        text.setIncludeFontPadding(false);
        text.setPadding(0, dp(4), 0, dp(4)); text.setLineSpacing(dp(2), 1);
        if (bold) text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        parent.addView(text, new LinearLayout.LayoutParams(-1, -2)); return text;
    }
    private void disclosure(LinearLayout card, String label, String content, String tag) {
        if (paper) {
            heading(card, label.replace("查看", ""));
            TextView evidence = add(card, content, 13, MUTED, false);
            evidence.setTag(tag + ".text");
            return;
        }
        Button button = host.button(context); button.setText(label); button.setTextSize(14); button.setTextColor(ACCENT);
        button.setAllCaps(false); button.setMinHeight(dp(48)); button.setMinimumHeight(dp(48));
        button.setPadding(dp(12), dp(8), dp(12), dp(8)); button.setBackgroundColor(Color.TRANSPARENT); button.setTag(tag);
        card.addView(host.controlView(button), new LinearLayout.LayoutParams(-1, -2));
        TextView evidence = add(card, content, 13, MUTED, false); evidence.setVisibility(View.GONE);
        evidence.setTag(tag + ".text"); evidence.setTextIsSelectable(true);
        button.setContentDescription(label + "，已收起");
        button.setOnClickListener(view -> {
            boolean expand = evidence.getVisibility() != View.VISIBLE;
            evidence.setVisibility(expand ? View.VISIBLE : View.GONE);
            button.setContentDescription(label + (expand ? "，已展开" : "，已收起"));
        });
    }
    private static void select(TextView label, String value) { label.setText(value); label.announceForAccessibility(value); }
    private int dp(float value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static String number(long value) { return String.format(Locale.US, "%,d", value); }
    private static String pct(double value) { return String.format(Locale.ROOT, "%.1f%%", value * 100); }
    private static String shortDate(String value) { return value.length() >= 10 ? value.substring(5).replace('-', '/') : value; }
    private static String duration(long millis) { return millis < 60_000 ? (millis / 1000) + " 秒" : (millis / 60_000) + " 分钟"; }
    private static long total(List<Rank> ranks) { long total = 0; for (Rank rank : ranks) total += rank.count; return total; }

    private static final class Band {
        final String label; final Map<String, Long> counts;
        Band(String label, Map<String, Long> counts) { this.label = label; this.counts = counts; }
    }
    private static final class Hit {
        final RectF rect; final String label;
        Hit(RectF rect, String label) { this.rect = rect; this.label = label; }
    }
    private abstract static class Chart extends View {
        boolean paper;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final List<Hit> hits = new ArrayList<>(); final Consumer<String> selection;
        int chosen = -1; float downX, downY;
        private Picture drawing;
        private int drawingWidth, drawingHeight;
        private float drawingDensity, drawingFontScale;
        private boolean drawingDark;

        // Data is immutable for this chart. Cache geometry, labels and accessibility hit targets
        // across selection redraws; record again on size, density, font scale or theme changes.
        @Override protected final void onDraw(Canvas canvas) {
            float density = getResources().getDisplayMetrics().density;
            float fontScale = getResources().getDisplayMetrics().scaledDensity;
            boolean dark = !paper && ModuleTheme.isDark(getContext());
            if (getWidth() <= 0 || getHeight() <= 0) return;
            if (drawing == null || drawingWidth != getWidth() || drawingHeight != getHeight()
                    || drawingDensity != density || drawingFontScale != fontScale || drawingDark != dark) {
                recordChart(density, fontScale, dark);
            }
            canvas.drawPicture(drawing);
            selection(canvas);
        }

        private void recordChart(float density, float fontScale, boolean dark) {
            Picture next = new Picture();
            Canvas recorded = next.beginRecording(getWidth(), getHeight());
            try { drawChart(recorded); }
            finally { next.endRecording(); }
            drawingWidth = getWidth(); drawingHeight = getHeight();
            drawingDensity = density; drawingFontScale = fontScale; drawingDark = dark;
            drawing = next;
        }

        abstract void drawChart(Canvas canvas);

        @Override protected void onDetachedFromWindow() {
            drawing = null;
            super.onDetachedFromWindow();
        }
        Chart(Context context, Consumer<String> selection) {
            super(context); this.selection = selection; setClickable(true); setFocusable(true);
            setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        }
        float d(float value) { return value * getResources().getDisplayMetrics().density; }
        int color(int value) { return paper ? value : ModuleTheme.color(getContext(), value); }
        void label(Canvas canvas, String text, float x, float y, int color, float size, Paint.Align align) {
            paint.setStyle(Paint.Style.FILL); paint.setColor(color == Color.WHITE ? Color.WHITE : color(color)); paint.setTextAlign(align);
            paint.setTypeface(Typeface.DEFAULT); paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText(text, x, y, paint);
        }
        void box(Canvas canvas, RectF bounds, int color) { paint.setStyle(Paint.Style.FILL); paint.setColor(color); canvas.drawRoundRect(bounds, d(3), d(3), paint); }
        void hit(RectF bounds, String label) { hits.add(new Hit(new RectF(bounds), label)); }
        void describe(String title) {
            StringBuilder text = new StringBuilder(title).append("。点击选择，辅助功能点击依次查看。");
            for (Hit hit : hits) text.append(hit.label).append("；");
            setContentDescription(text.toString());
        }
        void selection(Canvas canvas) {
            if (chosen < 0 || chosen >= hits.size()) return;
            paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(2)); paint.setColor(color(TEXT));
            canvas.drawRoundRect(hits.get(chosen).rect, d(3), d(3), paint); paint.setStyle(Paint.Style.FILL);
        }
        @Override public boolean performClick() {
            super.performClick(); if (hits.isEmpty()) return false;
            chosen = (chosen + 1) % hits.size(); selection.accept(hits.get(chosen).label); invalidate(); return true;
        }
        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) { downX = event.getX(); downY = event.getY(); return true; }
            if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                if (Math.abs(downX - event.getX()) + Math.abs(downY - event.getY()) < d(16)) {
                    for (int index = 0; index < hits.size(); index++) if (hits.get(index).rect.contains(event.getX(), event.getY())) {
                        chosen = index; selection.accept(hits.get(index).label); invalidate(); super.performClick(); break;
                    }
                }
                return true;
            }
            return event.getActionMasked() != MotionEvent.ACTION_CANCEL;
        }
        @Override protected void onConfigurationChanged(Configuration config) { super.onConfigurationChanged(config); invalidate(); }
        int intensity(long value, long max) {
            if (value <= 0) return color(0xFFEEEFF3);
            float strength = .18f + .82f * (float) Math.sqrt(value / (double) Math.max(1, max));
            int background = !paper && ModuleTheme.isDark(getContext()) ? 0xFF252B31 : 0xFFEAF4FE;
            return Color.rgb(Math.round(Color.red(background) * (1 - strength) + 22 * strength),
                    Math.round(Color.green(background) * (1 - strength) + 131 * strength),
                    Math.round(Color.blue(background) * (1 - strength) + 238 * strength));
        }
    }

    private static final class CalendarChart extends Chart {
        final List<PortraitSnapshot.Day> days;
        CalendarChart(Context context, List<PortraitSnapshot.Day> days, Consumer<String> selection) { super(context, selection); this.days = days; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); String[] labels = {"一", "二", "三", "四", "五", "六", "日"};
            float cell = getWidth() / 7f; long max = 1; for (PortraitSnapshot.Day day : days) max = Math.max(max, day.views);
            for (int column = 0; column < 7; column++) label(canvas, labels[column], (column + .5f) * cell, d(15), MUTED, 11, Paint.Align.CENTER);
            int offset = days.isEmpty() ? 0 : LocalDate.parse(days.get(0).date).getDayOfWeek().getValue() - 1;
            for (int index = 0; index < days.size(); index++) {
                PortraitSnapshot.Day day = days.get(index); int slot = index + offset;
                float x = slot % 7 * cell, y = d(26 + slot / 7 * 22);
                RectF bounds = new RectF(x + d(3), y, x + cell - d(3), y + d(18));
                box(canvas, bounds, intensity(day.views, max));
                if (day.views == 0 && day.features == 0) {
                    paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(d(1)); paint.setColor(color(0xFFE2E7E8)); canvas.drawRoundRect(bounds, d(3), d(3), paint);
                }
                label(canvas, day.date.substring(8), x + cell / 2, y + d(13), day.views > max / 2 ? Color.WHITE : TEXT, 10, Paint.Align.CENTER);
                hit(bounds, day.date + " · 浏览 " + number(day.views) + " 次 · 功能 " + number(day.features) + " 次"
                        + (day.views == 0 && day.features == 0 ? " · 无保留事件，使用情况未知" : ""));
            }
            describe("日期热力图");
        }
    }

    private static final class RhythmChart extends Chart {
        final long[][] values;
        RhythmChart(Context context, long[][] values, Consumer<String> selection) { super(context, selection); this.values = values; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long max = 1; for (long[] day : values) for (long count : day) max = Math.max(max, count);
            String[] weekdays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            float left = d(29), unit = Math.max(1, getWidth() - left) / 24;
            for (int day = 0; day < 7; day++) {
                label(canvas, weekdays[day], 0, d(41 + day * 25), MUTED, 10, Paint.Align.LEFT);
                for (int hour = 0; hour < 24; hour++) {
                    long count = day < values.length && hour < values[day].length ? values[day][hour] : 0;
                    RectF bounds = new RectF(left + hour * unit + d(.6f), d(27 + day * 25), left + (hour + 1) * unit - d(.6f), d(47 + day * 25));
                    box(canvas, bounds, intensity(count, max)); hit(bounds, weekdays[day] + " " + hour + " 时 · " + number(count) + " 次记录");
                }
            }
            for (int hour = 0; hour < 24; hour += 6) label(canvas, hour + "时", left + hour * unit, d(15), MUTED, 10, Paint.Align.LEFT);
            describe("星期与小时热力图");
        }
    }

    private static final class TypeChart extends Chart {
        final List<Band> bands; final Map<String, Integer> colors;
        TypeChart(Context context, List<Band> bands, Map<String, Integer> colors, Consumer<String> selection) { super(context, selection); this.bands = bands; this.colors = colors; }
        @Override void drawChart(Canvas canvas) {
            hits.clear();
            for (int index = 0; index < bands.size(); index++) {
                Band band = bands.get(index); long total = 0; for (long count : band.counts.values()) total += count;
                float y = d(index * 52 + 17);
                label(canvas, band.label + " · " + number(total) + " 次", 0, y, MUTED, 11, Paint.Align.LEFT);
                box(canvas, new RectF(0, y + d(6), getWidth(), y + d(24)), color(0xFFEEEFF3));
                float x = 0;
                for (Map.Entry<String, Long> item : band.counts.entrySet()) {
                    if (item.getValue() <= 0 || total <= 0) continue;
                    float next = x + getWidth() * item.getValue() / (float) total;
                    RectF bounds = new RectF(x, y + d(6), next, y + d(24)); box(canvas, bounds, colors.get(item.getKey()));
                    hit(bounds, band.label + " · " + item.getKey() + " " + number(item.getValue()) + " 次 · " + pct(item.getValue() / (double) total)); x = next;
                }
            }
            describe("内容构成百分比色带");
        }
    }

    private static final class TopicChart extends Chart {
        final List<Rank> ranks;
        final JournalTopicColors colors;
        TopicChart(Context context, List<Rank> ranks, JournalTopicColors colors, Consumer<String> selection) { super(context, selection); this.ranks = ranks; this.colors = colors; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long remaining = total(ranks); RectF available = new RectF(0, d(6), getWidth(), getHeight() - d(6));
            for (int index = 0; index < ranks.size(); index++) {
                Rank rank = ranks.get(index); if (rank.count <= 0 || remaining <= 0) continue;
                RectF tile = new RectF(available); float fraction = rank.count / (float) remaining;
                if (available.width() >= available.height()) { tile.right = tile.left + available.width() * fraction; available.left = tile.right; }
                else { tile.bottom = tile.top + available.height() * fraction; available.top = tile.bottom; }
                remaining -= rank.count; RectF paintBounds = new RectF(tile); paintBounds.inset(d(1.5f), d(1.5f));
                boolean dark = !paper && ModuleTheme.isDark(getContext());
                int tileInk = dark ? 0xFF14243B : Color.WHITE;
                if (paintBounds.width() > 0 && paintBounds.height() > 0) box(canvas, paintBounds, colors.color(rank.label, dark));
                canvas.save(); canvas.clipRect(tile);
                paint.setTextSize(11 * getResources().getDisplayMetrics().scaledDensity);
                String label = rank.label; while (label.length() > 1 && paint.measureText(label) > tile.width() - d(10)) label = label.substring(0, label.length() - 1);
                if (!label.equals(rank.label) && label.length() > 1) label = label.substring(0, label.length() - 1) + "…";
                if (tile.width() > d(28) && tile.height() > d(24)) label(canvas, label, tile.centerX(), tile.centerY(), tileInk, 11, Paint.Align.CENTER);
                if (tile.height() > d(46) && tile.width() > d(34)) label(canvas, number(rank.count), tile.centerX(), tile.centerY() + d(16), tileInk, 11, Paint.Align.CENTER);
                canvas.restore(); hit(tile, rank.label + " · " + number(rank.count) + " 次标签出现");
            }
            describe("话题矩形树图");
        }
    }

    private static final class AuthorChart extends Chart {
        final List<Rank> ranks;
        AuthorChart(Context context, List<Rank> ranks, Consumer<String> selection) { super(context, selection); this.ranks = ranks; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); long total = total(ranks), accumulated = 0; float left = d(34), bottom = getHeight() - d(28), top = d(22);
            float width = getWidth() - left - d(10), lastX = left, lastY = bottom;
            for (int line = 0; line <= 2; line++) {
                float y = bottom - (bottom - top) * line / 2;
                paint.setColor(color(0xFFE2E7E8)); paint.setStrokeWidth(d(1)); canvas.drawLine(left, y, getWidth(), y, paint);
                label(canvas, line * 50 + "%", left - d(4), y + d(4), MUTED, 10, Paint.Align.RIGHT);
            }
            for (int index = 0; index < ranks.size(); index++) {
                Rank rank = ranks.get(index); accumulated += rank.count;
                float x = left + width * (index + 1) / Math.max(1, ranks.size()), y = bottom - (bottom - top) * accumulated / Math.max(1, total);
                paint.setColor(COLORS[0]); paint.setStrokeWidth(d(2.5f)); canvas.drawLine(lastX, lastY, x, y, paint); canvas.drawCircle(x, y, d(4), paint);
                label(canvas, (index == 8 && rank.label.startsWith("其他（")) ? "其余" : String.valueOf(index + 1), x, bottom + d(18), MUTED, 10, Paint.Align.CENTER);
                hit(new RectF(x - d(10), top, x + d(10), bottom), rank.label + " · " + number(rank.count) + " 次 · 累计 " + pct(accumulated / (double) Math.max(1, total)));
                lastX = x; lastY = y;
            }
            describe("作者累计占比，其他作者为合并分组");
        }
    }

    private static final class CompareChart extends Chart {
        final PortraitSnapshot.Comparison data;
        CompareChart(Context context, PortraitSnapshot.Comparison data, Consumer<String> selection) { super(context, selection); this.data = data; }
        @Override void drawChart(Canvas canvas) {
            hits.clear(); String[] labels = {"浏览次数", "功能触发"};
            long[] current = {data.currentViews, data.currentFeatures}, previous = {data.previousViews, data.previousFeatures};
            for (int index = 0; index < 2; index++) {
                float y = d(20 + index * 76), left = d(8), right = getWidth() - d(8);
                label(canvas, labels[index] + " · 前期 " + number(previous[index]) + " / 本期 " + number(current[index]), 0, y, MUTED, 11, Paint.Align.LEFT);
                long max = Math.max(1, Math.max(current[index], previous[index]));
                float oldX = left + (right - left) * previous[index] / max, newX = left + (right - left) * current[index] / max;
                paint.setStrokeWidth(d(3)); paint.setColor(color(0xFFE2E7E8)); canvas.drawLine(left, y + d(23), right, y + d(23), paint);
                paint.setColor(COLORS[0]); canvas.drawLine(oldX, y + d(23), newX, y + d(23), paint);
                paint.setColor(COLORS[5]); canvas.drawCircle(oldX, y + d(23), d(6), paint);
                paint.setColor(COLORS[0]); canvas.drawCircle(newX, y + d(23), d(4), paint);
                hit(new RectF(0, y - d(14), getWidth(), y + d(40)), labels[index] + " · 前期 " + number(previous[index]) + " · 本期 " + number(current[index])
                        + (!data.currentComplete || !data.previousComplete ? " · 覆盖不完整，不能判定趋势" : ""));
            }
            describe("前后期对照点图，每行独立刻度");
        }
    }
}
