package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.douai.module.feature.DownloadTasks;
import com.douai.module.feature.DownloadTasks.Snapshot;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/** A visible subscription to download state; dismissing it never cancels a task. */
final class DownloadTasksDialog implements AutoCloseable {
    interface Tasks {
        List<Snapshot> snapshots();
        AutoCloseable listen(Runnable changed);
        void cancel(String id);
    }

    private static final Tasks LIVE = new Tasks() {
        @Override public List<Snapshot> snapshots() { return DownloadTasks.snapshots(); }
        @Override public AutoCloseable listen(Runnable changed) { return DownloadTasks.listen(changed); }
        @Override public void cancel(String id) { DownloadTasks.cancel(id); }
    };

    private final Context context;
    private final boolean miuix;
    private final Tasks tasks;
    private final AlertDialog dialog;
    private final Rows adapter = new Rows();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean refreshPosted = new AtomicBoolean();
    private final Runnable refresh = this::refreshNow;
    private AutoCloseable subscription;
    private volatile boolean closed = true;

    DownloadTasksDialog(Context context, boolean miuix) { this(context, miuix, LIVE); }

    DownloadTasksDialog(Context baseContext, boolean miuix, Tasks tasks) {
        Context context = ModuleTheme.themedContext(baseContext);
        this.context = context;
        this.miuix = miuix;
        this.tasks = tasks;
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), 0, dp(12), 0);
        ListView rows = new ListView(context);
        rows.setTag("download.tasks");
        rows.setDividerHeight(dp(1));
        rows.setAdapter(adapter);
        int height = Math.max(dp(120), Math.min(dp(360),
                context.getResources().getDisplayMetrics().heightPixels - dp(240)));
        content.addView(rows, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        dialog = new MiuixDialogBuilder(context, miuix).setTitle("下载任务")
                .setView(content).setNegativeButton("关闭", null).create();
        dialog.setOnShowListener(ignored -> {
            closed = false;
            if (subscription == null) subscription = tasks.listen(this::scheduleRefresh);
            adapter.replace(tasks.snapshots());
        });
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    private void scheduleRefresh() {
        if (!closed && refreshPosted.compareAndSet(false, true)) main.post(refresh);
    }

    private void refreshNow() {
        refreshPosted.set(false);
        if (!closed && dialog.isShowing()) adapter.replace(tasks.snapshots());
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacks(refresh);
        refreshPosted.set(false);
        AutoCloseable active = subscription;
        subscription = null;
        if (active != null) {
            try { active.close(); } catch (Exception ignored) { }
        }
    }

    private void cancel(String id) {
        if (closed || !dialog.isShowing()) return;
        for (Snapshot task : tasks.snapshots()) {
            if (id.equals(task.id) && task.cancellable) {
                tasks.cancel(id);
                scheduleRefresh();
                return;
            }
        }
    }

    private static String status(String value) {
        switch (value) {
            case "queued": return "排队中";
            case "running": return "下载中";
            case "cancelling": return "正在取消";
            case "completed": return "已完成";
            case "cancelled": return "已取消";
            case "timed_out": return "已超时";
            case "failed": return "失败";
            default: return "状态未知";
        }
    }

    private String detail(Snapshot task) {
        String label = status(task.status);
        StringBuilder value = new StringBuilder(label);
        if (task.phase != null && !task.phase.isEmpty() && !task.phase.equals(label)) value.append(" · ").append(task.phase);
        if ("running".equals(task.status) || "cancelling".equals(task.status)) {
            value.append('\n').append(Formatter.formatShortFileSize(context, Math.max(0, task.bytes)));
            if (task.totalBytes > 0) value.append(" / ").append(Formatter.formatShortFileSize(context, task.totalBytes));
            else value.append(" · 总大小未知");
        }
        if (task.totalFiles > 0) value.append('\n').append(task.completedFiles)
                .append(" / ").append(task.totalFiles).append(" 个文件已完成");
        return value.toString();
    }

    private final class Rows extends BaseAdapter {
        private List<Snapshot> entries = Collections.emptyList();
        void replace(List<Snapshot> values) { entries = new ArrayList<>(values); notifyDataSetChanged(); }
        @Override public int getCount() { return Math.max(1, entries.size()); }
        @Override public Snapshot getItem(int position) { return entries.isEmpty() ? null : entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return entries.isEmpty() ? 0 : 1; }

        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Snapshot task = getItem(position);
            if (task == null) {
                TextView empty = text(15);
                empty.setTag("download.tasks.empty");
                empty.setText("暂无下载任务");
                empty.setPadding(0, dp(16), 0, dp(16));
                return empty;
            }
            Row row = recycled instanceof Row ? (Row) recycled : null;
            if (row == null) row = new Row(context);
            row.setTag("download.task." + task.id);
            row.name.setText(task.name);
            row.detail.setText(detail(task));
            boolean active = "running".equals(task.status) || "cancelling".equals(task.status);
            row.progress.setVisibility(active ? View.VISIBLE : View.GONE);
            row.progress.setIndeterminate(task.totalBytes <= 0);
            if (task.totalBytes > 0) row.progress.setProgress((int) Math.max(0,
                    Math.min(100, 100d * task.bytes / task.totalBytes)));
            row.cancel.setTag("download.cancel." + task.id);
            row.cancel.setContentDescription("取消下载 " + task.name);
            row.cancel.setEnabled(task.cancellable);
            row.cancelView.setVisibility(task.cancellable ? View.VISIBLE : View.GONE);
            row.cancel.setOnClickListener(ignored -> cancel(task.id));
            return row;
        }
    }

    private final class Row extends LinearLayout {
        final TextView name = text(16);
        final TextView detail = text(13);
        final ProgressBar progress = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        final Button cancel = miuix ? MiuixFormControls.button(context) : new Button(context);
        final View cancelView = MiuixFormControls.viewFor(cancel);
        Row(Context rowContext) {
            super(rowContext);
            setOrientation(LinearLayout.VERTICAL);
            setPadding(dp(4), dp(12), dp(4), dp(12));
            name.setMaxLines(2);
            name.setEllipsize(TextUtils.TruncateAt.END);
            detail.setPadding(0, dp(4), 0, dp(4));
            progress.setMax(100);
            cancel.setText("取消下载");
            LinearLayout heading = new LinearLayout(context);
            heading.setOrientation(LinearLayout.HORIZONTAL);
            heading.setGravity(android.view.Gravity.CENTER_VERTICAL);
            heading.addView(name, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            LinearLayout.LayoutParams action = new LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT);
            action.leftMargin = dp(8);
            heading.addView(cancelView, action);
            addView(heading, matchWrap());
            addView(detail, matchWrap());
            addView(progress, matchWrap());
        }
    }

    private TextView text(int size) {
        TextView text = new TextView(context);
        text.setTextSize(size);
        text.setTextColor(Color.rgb(36, 42, 48));
        return text;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }
}
