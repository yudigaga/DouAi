package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import com.douai.module.config.AutomationHistoryStore;
import com.douai.module.config.SubmissionLedger;
import com.douai.module.feature.NativeFollowLists;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import java.text.DateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.function.Supplier;

/** Account-scoped human reconciliation; opening this page never sends or retries anything. */
final class SubmissionRecoveryDialog implements AutoCloseable {
    private final Context context;
    private final boolean miuix;
    private final String kind;
    private final SubmissionLedger ledger;
    private final ExecutorService worker;
    private final Supplier<String> account;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final TextView summary;
    private final Rows rows = new Rows();
    private final AlertDialog dialog;
    private AlertDialog confirmation;
    private Future<?> work;
    private int generation;
    private volatile boolean closed;
    private String loadedAccount = "";
    private Map<String, String> names = Collections.emptyMap();

    SubmissionRecoveryDialog(Context context, String kind, boolean miuix, ExecutorService worker) {
        this(context, kind, miuix, worker, NativeFollowLists::currentAccountUid);
    }

    SubmissionRecoveryDialog(Context context, String kind, boolean miuix, ExecutorService worker, Supplier<String> account) {
        if (!AutomationHistoryStore.COMMENT.equals(kind) && !AutomationHistoryStore.REPLY.equals(kind))
            throw new IllegalArgumentException("Unsupported submission kind");
        this.context = context; this.kind = kind; this.miuix = miuix; this.worker = worker; this.account = account;
        ledger = new SubmissionLedger(context.getSharedPreferences(AutomationHistoryStore.COMMENT.equals(kind)
                ? SubmissionLedger.COMMENT_FILE : SubmissionLedger.REPLY_FILE, Context.MODE_PRIVATE), SubmissionLedger.DEFAULT_CAPACITY);
        LinearLayout content = new LinearLayout(context); content.setOrientation(LinearLayout.VERTICAL);
        summary = label("正在读取发送状态…"); summary.setTag("submission.summary"); content.addView(summary);
        ListView list = new ListView(context); list.setTag("submission.records"); list.setAdapter(rows);
        content.addView(list, new LinearLayout.LayoutParams(-1, dp(330)));
        dialog = new MiuixDialogBuilder(context, miuix)
                .setTitle(AutomationHistoryStore.COMMENT.equals(kind) ? "自动评论发送状态" : "私信回复发送状态")
                .setView(content).setNegativeButton("关闭", null).setNeutralButton("刷新", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> refresh("")); refresh("");
        });
        dialog.setOnDismissListener(ignored -> close());
    }

    AlertDialog dialog() { return dialog; }

    private void refresh(String outcome) {
        if (closed) return;
        int ticket = ++generation;
        if (work != null) work.cancel(true);
        summary.setText(outcome.isEmpty() ? "正在读取发送状态…" : outcome);
        rows.setEntries(Collections.emptyList());
        work = worker.submit(() -> {
            String uid;
            try { uid = account.get(); } catch (RuntimeException failure) { uid = ""; }
            if (uid == null || !uid.matches("[1-9][0-9]{0,19}")) {
                main.post(() -> { if (current(ticket)) summary.setText("请在已登录的抖音中打开，等待账号适配完成。"); }); return;
            }
            String scope = SubmissionLedger.accountScope(uid);
            Map<String, String> labels = historyNames(new AutomationHistoryStore(context).read(kind), kind, uid);
            SubmissionLedger.Snapshot state = ledger.inspect(scope, labels.keySet(), System.currentTimeMillis());
            String selectedAccount = uid;
            main.post(() -> {
                if (!current(ticket)) return;
                if (!selectedAccount.equals(currentAccount())) { summary.setText("账号已切换，请刷新。"); return; }
                loadedAccount = selectedAccount; names = labels;
                // The injected host context does not expose module string resources.
                summary.setText(String.format(java.util.Locale.getDefault(),
                        "%1$s当前账号 %2$s\n全部账号已用 %3$d/%4$d，待核对 %5$d，损坏 %6$d，旧记录未归属 %7$d%8$s",
                        outcome.isEmpty() ? "" : outcome + "\n", selectedAccount, state.total, state.capacity,
                        state.uncertain, state.corrupt, state.unassigned,
                        state.storageFailed ? "\n存储不可用，请检查空间并完全重启抖音。" : ""));
                rows.setEntries(state.entries);
            });
        });
    }

    static Map<String, String> historyNames(List<AutomationHistoryStore.Entry> history, String kind, String uid) {
        Map<String, String> labels = new HashMap<>();
        for (AutomationHistoryStore.Entry entry : history) {
            if (!kind.equals(entry.kind)) continue;
            String raw;
            if (AutomationHistoryStore.COMMENT.equals(kind)) {
                raw = "comment:" + uid + ":" + entry.targetId;
                if (entry.targetId.isEmpty() || !entry.id.startsWith(raw + ":")) continue;
            } else {
                if (entry.targetId.isEmpty() || !entry.id.startsWith(uid + ":" + entry.targetId + ":")) continue;
                raw = entry.id;
            }
            labels.put(SubmissionLedger.identity(raw), entry.targetName);
        }
        return labels;
    }

    private void choose(SubmissionLedger.Entry entry) {
        if (closed || !entry.recoverable || !loadedAccount.equals(currentAccount())) return;
        if (confirmation != null) confirmation.dismiss();
        AlertDialog choice = new MiuixDialogBuilder(context, miuix).setTitle("核对发送结果")
                .setMessage("请先在当前账号的抖音记录中核对这次发送。标记成功会保留防重；解除防重会允许后续任务再次发送，本页不会补发。")
                .setNegativeButton("取消", null)
                .setPositiveButton("已核对发送成功", (ignored, which) -> confirm(entry, true))
                .setNeutralButton("已核对未发送", (ignored, which) -> confirm(entry, false)).create();
        confirmation = choice; choice.show();
    }

    private void confirm(SubmissionLedger.Entry entry, boolean sent) {
        if (closed || !loadedAccount.equals(currentAccount())) return;
        AlertDialog next = new MiuixDialogBuilder(context, miuix).setTitle(sent ? "确认标记成功" : "确认解除防重")
                .setMessage(sent ? "只更新本地发送状态，不会再次提交。" : "仅在已确认没有发送时解除。未知或已发送的记录解除后可能重复发送。")
                .setNegativeButton("取消", null).setPositiveButton("确认", (ignored, which) -> reconcile(entry, sent)).create();
        confirmation = next; next.show();
    }

    private void reconcile(SubmissionLedger.Entry entry, boolean sent) {
        if (closed) return;
        DouAiLog.Operation trace = DouAiLog.begin("history", "manual_reconcile");
        trace.stage("ledger_write", "user_declared_sent=" + sent + ";entry_ref=" + DouAiLog.reference(entry.identity));
        String uid = loadedAccount;
        int ticket = ++generation;
        summary.setText("正在保存核对结果…");
        work = worker.submit(trace.wrap(() -> {
            if (closed || !uid.equals(currentAccount())) { trace.finish(DouAiLog.Outcome.CANCELLED, "account_or_dialog_changed", ""); return; }
            boolean saved = ledger.reconcile(entry, SubmissionLedger.accountScope(uid), sent, System.currentTimeMillis(),
                    () -> !closed && uid.equals(currentAccount()) && (sent || releaseLegacyGuard(uid, entry.identity)));
            trace.finish(saved ? DouAiLog.Outcome.SUCCESS : DouAiLog.Outcome.UNCONFIRMED, "ledger_reconcile_result", "saved=" + saved + ";remote_delivery=user_asserted;resend=false");
            main.post(trace.wrap(() -> { if (current(ticket)) refresh(saved ? "核对结果已保存，未补发。" : "记录已变化、不可恢复或存储失败，请刷新后重新核对。"); }));
        }));
    }

    private String currentAccount() {
        try { String uid = account.get(); return uid == null ? "" : uid; }
        catch (RuntimeException failure) { return ""; }
    }

    private boolean releaseLegacyGuard(String uid, String identity) {
        if (!AutomationHistoryStore.COMMENT.equals(kind)) return true;
        android.content.SharedPreferences legacy = context.getSharedPreferences("douai_comment_state", Context.MODE_PRIVATE);
        android.content.SharedPreferences.Editor change = legacy.edit();
        boolean found = false;
        for (String key : legacy.getAll().keySet()) {
            if (key.startsWith("comment:" + uid + ":") && SubmissionLedger.identity(key).equals(identity)) {
                change.remove(key); found = true;
            }
        }
        return !found || change.commit();
    }

    private boolean current(int ticket) { return !closed && ticket == generation && dialog.isShowing(); }
    private TextView label(String value) {
        TextView text = new TextView(context); text.setText(value); text.setTextSize(14);
        text.setTextColor(android.graphics.Color.rgb(31, 39, 45));
        ModuleTheme.bind(text);
        text.setPadding(dp(16), dp(10), dp(16), dp(10)); return text;
    }
    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    @Override public void close() {
        closed = true; generation++; if (work != null) work.cancel(true);
        if (confirmation != null) { confirmation.dismiss(); confirmation = null; }
    }
    private final class Rows extends BaseAdapter {
        private List<SubmissionLedger.Entry> entries = Collections.emptyList();
        void setEntries(List<SubmissionLedger.Entry> entries) { this.entries = entries; notifyDataSetChanged(); }
        @Override public int getCount() { return entries.size(); }
        @Override public SubmissionLedger.Entry getItem(int position) { return entries.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public View getView(int position, View recycled, ViewGroup parent) {
            SubmissionLedger.Entry entry = getItem(position);
            TextView row = recycled instanceof TextView ? (TextView) recycled : label("");
            row.setText(String.format(java.util.Locale.getDefault(), "%1$s · %2$s\n%3$s\n%4$s",
                    names.getOrDefault(entry.identity, "记录 " + entry.identity.substring(0, 12)),
                    AutomationHistoryDialog.status(entry.status), DateFormat.getDateTimeInstance().format(new Date(entry.timestamp)),
                    entry.recoverable ? "点此核对" : "保留防重"));
            row.setOnClickListener(ignored -> choose(entry)); return row;
        }
    }
}
