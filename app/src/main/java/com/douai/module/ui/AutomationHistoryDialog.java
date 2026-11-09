package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.AutomationHistoryStore.Entry;
import com.douai.module.config.AutomationDiagnostics;
import com.douai.module.ui.miuix.HostComposeContext;
import com.douai.module.ui.miuix.MiuixAutomationHistory;
import com.douai.module.ui.miuix.MiuixDialogBuilder;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/** Read-only records independent of the model's in-memory conversation context. */
final class AutomationHistoryDialog {
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override public Thread newThread(Runnable task) {
            Thread thread = new Thread(task, "DouAi-history");
            thread.setDaemon(true);
            return thread;
        }
    });

    private final Context context;
    private final AutomationHistoryStore store;
    private final String kind;
    private final boolean miuix;
    private final Rows adapter;
    private final MiuixAutomationHistory composeRows;
    private final AlertDialog dialog;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker;
    private AlertDialog detail;
    private SubmissionRecoveryDialog recovery;
    private Future<?> work;
    private int generation;

    AutomationHistoryDialog(Context context, String kind) {
        this(context, kind, false);
    }

    AutomationHistoryDialog(Context context, String kind, boolean miuix) {
        this(context, kind, miuix, IO);
    }

    AutomationHistoryDialog(Context baseContext, String kind, boolean miuix, ExecutorService io) {
        Context context = ModuleTheme.themedContext(baseContext);
        this.context = context;
        this.kind = kind;
        if (io == null) throw new IllegalArgumentException("History executor is required");
        worker = io;
        Activity owner = ownerActivity(context);
        Context styledContext = context;
        boolean styled = false;
        if (miuix && owner != null && !owner.isFinishing() && !owner.isDestroyed()) {
            try {
                styledContext = HostComposeContext.create(owner);
                styled = true;
            } catch (RuntimeException unavailable) {
                styledContext = context;
            }
        }
        this.miuix = styled;
        store = new AutomationHistoryStore(context);
        View history;
        if (this.miuix) {
            adapter = null;
            composeRows = new MiuixAutomationHistory(styledContext, this::showDetail,
                    AutomationHistoryDialog::rowTitle);
            history = composeRows.getView();
        } else {
            composeRows = null;
            adapter = new Rows();
            ListView rows = new ListView(context);
            rows.setTag("automation.history");
            rows.setDividerHeight(dp(1));
            rows.setAdapter(adapter);
            history = rows;
        }
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, this.miuix);
        builder
                .setTitle(AutomationHistoryStore.COMMENT_REPLY.equals(kind) ? "评论回复历史记录"
                        : AutomationHistoryStore.REPLY.equals(kind) ? "回复历史记录" : "评论历史记录")
                .setView(history).setNegativeButton("关闭", null).setNeutralButton("清空记录", null);
        if (!AutomationHistoryStore.COMMENT_REPLY.equals(kind)) builder.setPositiveButton("发送状态", null);
        dialog = builder.create();
        if (owner != null) dialog.setOwnerActivity(owner);
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> clear());
            if (!AutomationHistoryStore.COMMENT_REPLY.equals(kind)) {
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
                    if (recovery != null) { recovery.close(); recovery.dialog().dismiss(); }
                    recovery = new SubmissionRecoveryDialog(context, kind, this.miuix, worker);
                    recovery.dialog().show();
                });
            }
            refresh();
        });
        dialog.setOnDismissListener(ignored -> {
            invalidateWork();
            closeDetails();
        });
    }

    AlertDialog dialog() { return dialog; }

    private void refresh() {
        int ticket = nextGeneration();
        setState("正在读取…");
        work = worker.submit(() -> {
            List<Entry> result = read();
            main.post(() -> {
                if (!isCurrent(ticket)) return;
                work = null;
                setEntries(result);
            });
        });
    }

    private void clear() {
        int ticket = nextGeneration();
        setState("正在清空…");
        worker.execute(() -> {
            try { store.clear(kind); }
            catch (RuntimeException ignored) { }
            List<Entry> result = read();
            main.post(() -> {
                if (!isCurrent(ticket)) return;
                work = null;
                setEntries(result);
            });
        });
    }

    private List<Entry> read() {
        try { return store.read(kind); }
        catch (RuntimeException failure) { return Collections.emptyList(); }
    }

    private void setState(String value) {
        if (composeRows != null) composeRows.setState(value);
        else adapter.setState(value);
    }

    private void setEntries(List<Entry> value) {
        if (composeRows != null) composeRows.setEntries(value);
        else adapter.setEntries(value);
    }

    private int nextGeneration() {
        if (work != null) work.cancel(true);
        work = null;
        return ++generation;
    }

    private boolean isCurrent(int ticket) {
        return ticket == generation && dialog.isShowing();
    }

    private void invalidateWork() {
        generation++;
        if (work != null) work.cancel(true);
        work = null;
    }

    private void showDetail(Entry entry) {
        if (detail != null) detail.dismiss();
        AutomationHistoryNavigation.Target destination = AutomationHistoryNavigation.target(context, entry);
        java.util.concurrent.atomic.AtomicReference<Entry> located = new java.util.concurrent.atomic.AtomicReference<>(entry);
        boolean resolveConversation = AutomationHistoryStore.REPLY.equals(entry.kind)
                && entry.location.conversationType < 0 && "com.ss.android.ugc.aweme".equals(context.getPackageName());
        TextView text = label(detailText(entry, destination.hint));
        text.setTextIsSelectable(true);
        text.setPadding(dp(16), dp(12), dp(16), dp(12));
        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        scroll.addView(text);
        MiuixDialogBuilder builder = new MiuixDialogBuilder(context, miuix);
        builder.setTitle("历史详情").setView(scroll).setNegativeButton("关闭", null);
        if (destination.enabled() || resolveConversation) builder.setPositiveButton(resolveConversation ? "正在读取定位…" : destination.label, null);
        AlertDialog next = builder.create();
        if (dialog.getOwnerActivity() != null) next.setOwnerActivity(dialog.getOwnerActivity());
        next.setOnDismissListener(ignored -> { if (detail == next) detail = null; });
        detail = next;
        next.show();
        if (destination.enabled() || resolveConversation) {
            next.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                if (AutomationHistoryNavigation.open(context, located.get())) dialog.dismiss();
            });
        }
        if (resolveConversation) {
            next.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            try {
                worker.execute(() -> {
                    Entry resolved = AutomationHistoryNavigation.resolveLegacyConversation(context, entry);
                    main.post(() -> {
                        if (detail != next || !next.isShowing()) return;
                        located.set(resolved);
                        AutomationHistoryNavigation.Target target = AutomationHistoryNavigation.target(context, resolved);
                        text.setText(detailText(entry, target.hint));
                        next.getButton(AlertDialog.BUTTON_POSITIVE).setText(target.label);
                        next.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(target.enabled());
                    });
                });
            } catch (RuntimeException unavailable) {
                next.getButton(AlertDialog.BUTTON_POSITIVE).setText("无法跳转");
            }
        }
    }

    private static String detailText(Entry entry, String hint) {
        return "对象：" + entry.targetName + "\n标识：" + entry.targetId + "\n状态："
                + status(entry.status) + "\n模型：" + entry.modelName + "\n时间："
                + DateFormat.getDateTimeInstance().format(new Date(entry.createdAt))
                + (AutomationHistoryStore.COMMENT_REPLY.equals(entry.kind) ? "\n来源：评论回复" : "")
                + (isComment(entry.kind)
                ? "\n发送方式：" + deliveryMode(entry) + "\n" + imageRequest(entry) : "")
                + "\n" + AutomationDiagnostics.details(entry)
                + "\n\n定位：" + hint
                + "\n\n原内容\n" + entry.input + "\n\n生成内容\n" + entry.output;
    }

    /** Also use as the parent's dismiss callback when its owner replaces the listener. */
    void closeDetails() {
        invalidateWork();
        if (recovery != null) { recovery.close(); recovery.dialog().dismiss(); recovery = null; }
        AlertDialog child = detail;
        detail = null;
        if (child != null) child.dismiss();
    }

    static String status(String status) {
        switch (status) {
            case "sent": return "发送成功";
            case "submitted": return "已提交";
            case "failed": return "失败";
            case "cancelled": return "已取消";
            case "sending": return "结果未确认";
            case "unknown": return "结果未确认";
            default: return "尚未提交";
        }
    }

    private static String rowTitle(Entry entry) {
        return (AutomationHistoryStore.COMMENT_REPLY.equals(entry.kind) ? "评论回复 · " : "")
                + entry.targetName + " · " + status(entry.status)
                + (isComment(entry.kind) ? " · " + deliveryMode(entry) : "");
    }

    private static boolean isComment(String kind) {
        return AutomationHistoryStore.COMMENT.equals(kind) || AutomationHistoryStore.COMMENT_REPLY.equals(kind);
    }

    private static String deliveryMode(Entry entry) {
        switch (entry.deliveryMode) {
            case AutomationHistoryStore.DELIVERY_TEXT: return "文本";
            case AutomationHistoryStore.DELIVERY_VOICE: return "语音";
            default: return "未记录";
        }
    }

    private static String imageRequest(Entry entry) {
        if (entry.requestImageCount < 0) return "图片请求：未记录";
        if (!entry.modelRequestSent) return "图片请求：未确认发送";
        return entry.requestImageCount == 0 ? "图片请求：未携带图片"
                : "图片请求：已携带 " + entry.requestImageCount + " 张图片";
    }

    private TextView label(String value) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(14);
        view.setTextColor(Color.rgb(31, 39, 45));
        return view;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    private static Activity ownerActivity(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) return (Activity) current;
            Context next = ((ContextWrapper) current).getBaseContext();
            if (next == current) return null;
            current = next;
        }
        return current instanceof Activity ? (Activity) current : null;
    }

    private final class Rows extends BaseAdapter {
        private List<Entry> entries = Collections.emptyList();
        private String state = "暂无记录";

        void setState(String value) {
            entries = Collections.emptyList();
            state = value;
            notifyDataSetChanged();
        }

        void setEntries(List<Entry> value) {
            entries = value == null ? Collections.emptyList() : new ArrayList<>(value);
            state = entries.isEmpty() ? "暂无记录" : null;
            notifyDataSetChanged();
        }

        @Override public int getCount() { return entries.isEmpty() ? 1 : entries.size(); }
        @Override public Entry getItem(int position) { return entries.isEmpty() ? null : entries.get(position); }
        @Override public long getItemId(int position) { return getItem(position) == null ? -1 : position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            Entry entry = getItem(position);
            if (entry == null) {
                TextView empty = recycled instanceof TextView ? (TextView) recycled : label("");
                empty.setTag("automation.history.empty");
                empty.setText(state);
                empty.setOnClickListener(null);
                empty.setPadding(dp(16), dp(20), dp(16), dp(20));
                return empty;
            }
            String title = rowTitle(entry);
            String date = DateFormat.getDateTimeInstance().format(new Date(entry.createdAt));
            TextView row = recycled instanceof TextView ? (TextView) recycled : label("");
            row.setTag("automation.history." + entry.id);
            row.setText(title + "\n" + date + "\n" + entry.output);
            row.setMaxLines(5);
            row.setEllipsize(android.text.TextUtils.TruncateAt.END);
            row.setPadding(dp(16), dp(12), dp(16), dp(12));
            row.setOnClickListener(ignored -> showDetail(entry));
            return row;
        }
    }
}
