package com.douai.module.feature;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.ThirdPartyDownloadSettings;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.diagnostics.DouAiLog;

import java.lang.ref.WeakReference;
import java.util.Collections;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Runs only after a user invokes a third-party download. */
public final class ThirdPartyDownload {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ThirdPartyDownload() { }

    public static void start(Context source, Object aweme, LocalOptions options, Consumer<String> log) {
        startOwned(source, aweme, options, log, () -> true);
    }

    public static AutoCloseable startOwned(Context source, Object aweme, LocalOptions options,
                                           Consumer<String> log, BooleanSupplier valid) {
        if (source == null) return null;
        BooleanSupplier active = valid == null ? () -> true : valid;
        try { if (!active.getAsBoolean()) return null; }
        catch (RuntimeException ignored) { return null; }
        LocalOptions current = options == null ? new LocalOptions(Collections.emptyMap()) : options;
        ThirdPartyDownloadSettings config = new ThirdPartyDownloadSettings(current);
        DouAiLog.event("third_party_download", "provider_selected", "configuration", "mode=" + (config.direct ? "direct" : "selection")
                + "; tree_selected=" + !current.downloadSaveTree().isEmpty());
        if (config.direct) { startDirect(source, aweme, current, log); return () -> { }; }
        Activity activity = activity(source);
        if (activity == null || activity.isFinishing() || activity.isDestroyed()) {
            toast(source, "当前页面无法显示第三方下载选择"); return null;
        }
        String link;
        try { link = shareUrl(aweme); }
        catch (IllegalArgumentException failure) {
            if (config.getTemplate.contains("{url}")) { toast(source, failure.getMessage()); return null; }
            link = "";
        }
        Session session = new Session(activity, config, link, current.downloadSaveTree(),
                log == null ? ignored -> { } : log, active, AnalyticsActionCapture.capture(source, aweme));
        onMain(session.operation.wrap(session::begin));
        return session;
    }

    private static void startDirect(Context source, Object aweme, LocalOptions options, Consumer<String> log) {
        if (source == null) return;
        if (options.downloadSaveTree().isEmpty() && Build.VERSION.SDK_INT < 29
                && source.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED) {
            toast(source, "请先授予抖音存储权限");
            return;
        }
        ThirdPartyDownloadSettings config = new ThirdPartyDownloadSettings(options);
        String tree = options.downloadSaveTree();
        String capturedLink;
        try { capturedLink = shareUrl(aweme); }
        catch (IllegalArgumentException failure) {
            if (config.getTemplate.contains("{url}")) { toast(source, failure.getMessage()); return; }
            capturedLink = "";
        }
        final String link = capturedLink;
        Context application = source.getApplicationContext();
        Context context = application == null ? source : application;
        String key = config.getTemplate + '\n' + link + '\n' + tree;
        AnalyticsActionCapture analytics = AnalyticsActionCapture.capture(source, aweme);
        analytics.manual("download_request", "下载任务请求", "request");
        com.douai.module.analytics.LocalAnalytics.FlowOperation flow = analytics.operation("download");
        if (flow != null) { flow.stage("entry"); flow.stage("request"); }
        boolean accepted = DownloadTasks.submit(context, "third-party\n" + key, "第三方作品", transfer -> {
                transfer.phase("正在解析作品");
                String body = new ThirdPartyGetClient.Request(transfer, MediaStoreDownloader.connections).fetch(config, link);
                transfer.check();
                ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(
                        ThirdPartyResponse.parse(body), config, System.currentTimeMillis());
                MediaStoreDownloader.saveThirdPartyBundle(context, plan, tree, transfer);
                return "已保存 " + plan.assets.size() + " 个文件和作品信息到 "
                        + (tree.isEmpty() ? "Download/DouAi" : "所选目录");
        }, log, flow, 1) != null;
        if (accepted) analytics.manual("download_queued", "下载任务已加入", "accepted");
        toast(context, accepted ? "已加入下载任务，可在设置中查看进度或取消" : "该作品正在下载或队列已满");
    }

    private static final class Session implements AutoCloseable, Application.ActivityLifecycleCallbacks {
        private final WeakReference<Activity> owner;
        private final Application application;
        private final ThirdPartyDownloadSettings config;
        private final String link, tree;
        private final AnalyticsActionCapture analytics;
        private final com.douai.module.analytics.LocalAnalytics.FlowOperation flow;
        private final DouAiLog.Operation operation = DouAiLog.begin("third_party_selection", "user_download_picker");
        private Consumer<String> log;
        private BooleanSupplier valid;
        private volatile boolean closed;
        private boolean registered;
        private boolean selectionFinished;
        private DownloadTasks.Task parsing;
        private AlertDialog loading;
        private ThirdPartyDownloadDialog selection;
        private AutoCloseable taskSubscription;

        Session(Activity activity, ThirdPartyDownloadSettings config, String link, String tree,
                Consumer<String> log, BooleanSupplier valid, AnalyticsActionCapture analytics) {
            owner = new WeakReference<>(activity);
            application = activity.getApplication();
            this.config = config; this.link = link; this.tree = tree; this.log = log; this.valid = valid;
            this.analytics = analytics;
            flow = analytics.operation("download");
            if (flow != null) flow.stage("entry");
            operation.stage("ready", "template_configured=" + !config.getTemplate.isEmpty() + "; tree_selected=" + !tree.isEmpty());
        }

        private String key() { return config.getTemplate + '\n' + link + '\n' + tree; }

        void begin() {
            if (!usable()) return;
            application.registerActivityLifecycleCallbacks(this);
            registered = true;
            taskSubscription = DownloadTasks.listen(() -> onMain(() -> {
                DownloadTasks.Task current = parsing;
                if (!closed && current != null && current.terminal && !"completed".equals(current.status)) close();
            }));
            try {
                AlertDialog next = new MiuixDialogBuilder(owner.get(), true).setTitle("第三方下载")
                        .setMessage("正在解析所选下载字段…")
                        .setNegativeButton("取消", (ignored, which) -> close()).create();
                loading = next;
                next.setOnCancelListener(ignored -> close());
                next.setOnDismissListener(ignored -> { if (loading == next) close(); });
                next.show();
            } catch (RuntimeException | LinkageError failure) {
                close(); toast(application, "无法显示第三方下载选择"); return;
            }
            WeakReference<Session> reference = new WeakReference<>(this);
            ThirdPartyDownloadSettings capturedConfig = config;
            String capturedLink = link;
            DouAiLog.Operation parseOperation = operation;
            parsing = DownloadTasks.submit(application, "third-party\nparse\n" + key(), "第三方解析", transfer -> {
                try {
                    transfer.file("第三方解析", 0);
                    transfer.phase("正在解析作品");
                    String body = new ThirdPartyGetClient.Request(transfer, MediaStoreDownloader.connections)
                            .fetch(capturedConfig, capturedLink);
                    transfer.check();
                    ThirdPartyDownloadPlan plan = ThirdPartyDownloadPlan.create(
                            ThirdPartyResponse.parse(body), capturedConfig, System.currentTimeMillis());
                    transfer.complete();
                    parseOperation.stage("parse_result", "assets=" + plan.assets.size() + "; business_result=awaiting_selection");
                    MAIN.post(parseOperation.wrap(() -> {
                        Session session = reference.get();
                        if (session != null) session.parsed(plan);
                    }));
                    return "解析完成，请选择保存内容";
                } catch (Exception failure) {
                    parseOperation.error("parse", failure);
                    MAIN.post(parseOperation.wrap(() -> {
                        Session session = reference.get();
                        if (session != null) session.failed();
                    }));
                    throw failure;
                }
            }, log);
            if (parsing == null) { close(); toast(application, "该作品正在解析或下载队列已满"); }
            else if (closed) DownloadTasks.cancel(parsing.id);
        }

        private void parsed(ThirdPartyDownloadPlan plan) {
            if (!usable()) return;
            operation.stage("awaiting_selection", "available_assets=" + plan.assets.size() + "; saved=false");
            parsing = null;
            dismissLoading();
            try {
                selection = new ThirdPartyDownloadDialog(owner.get(), plan, operation.wrapConsumer(this::selected),
                        operation.wrap(this::close), this::usable);
                selection.show();
            } catch (RuntimeException | LinkageError failure) {
                close(); toast(application, "无法显示第三方下载选择");
            }
        }

        private void selected(ThirdPartyDownloadPlan plan) {
            if (!usable()) return;
            if (tree.isEmpty() && Build.VERSION.SDK_INT < 29
                    && application.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
                close(); toast(application, "请先授予抖音存储权限"); return;
            }
            Consumer<String> logger = log;
            String capturedTree = tree;
            StringBuilder selectedKey = new StringBuilder("third-party\nselected\n").append(key());
            for (ThirdPartyDownloadPlan.Asset asset : plan.assets) selectedKey.append('\n').append(asset.url).append('\n').append(asset.name);
            Application target = application;
            selectionFinished = true;
            operation.finish(DouAiLog.Outcome.SUCCESS, "selection_confirmed", "selected_assets=" + plan.assets.size()
                    + "; evidence=user_selected_assets; media_save_result=pending");
            close();
            analytics.manual("download_request", "下载任务请求", "request");
            if (flow != null) flow.stage("request");
            boolean accepted = DownloadTasks.submit(target, selectedKey.toString(), plan.baseName, transfer -> {
                MediaStoreDownloader.saveThirdPartyBundle(target, plan, capturedTree, transfer);
                return "已保存 " + plan.assets.size() + " 个文件和作品信息到 "
                        + (capturedTree.isEmpty() ? "Download/DouAi" : "所选目录");
            }, logger, flow, 1) != null;
            if (accepted) analytics.manual("download_queued", "下载任务已加入", "accepted");
            toast(target, accepted ? "已加入下载任务，可在设置中查看进度或取消" : "该选择正在下载或队列已满");
        }

        private void failed() {
            if (closed) return;
            selectionFinished = true;
            operation.finish(DouAiLog.Outcome.FAILED, "parser_failed", "selection_ready=false; saved=false");
            close(); toast(application, "第三方解析失败，请检查 GET 和下载字段");
        }

        private boolean usable() {
            if (closed) return false;
            Activity activity = owner.get();
            boolean active = activity != null && !activity.isFinishing() && !activity.isDestroyed();
            try { active = active && valid != null && valid.getAsBoolean(); }
            catch (RuntimeException failure) { active = false; }
            if (!active) close();
            return active;
        }

        private void dismissLoading() {
            AlertDialog current = loading;
            loading = null;
            if (current != null) current.dismiss();
        }

        @Override public void close() {
            if (!closed && !selectionFinished) operation.finish(DouAiLog.Outcome.CANCELLED, "picker_closed_or_owner_invalid",
                    "selection_or_save_handoff_incomplete; closed_by_lifecycle_or_user=true");
            closed = true;
            onMain(() -> {
                DownloadTasks.Task current = parsing;
                parsing = null;
                if (current != null) DownloadTasks.cancel(current.id);
                dismissLoading();
                ThirdPartyDownloadDialog picker = selection;
                selection = null;
                if (picker != null) picker.close();
                AutoCloseable subscription = taskSubscription;
                taskSubscription = null;
                if (subscription != null) try { subscription.close(); } catch (Exception ignored) { }
                if (registered) { application.unregisterActivityLifecycleCallbacks(this); registered = false; }
                owner.clear(); valid = null; log = null;
            });
        }

        @Override public void onActivityPaused(Activity activity) { if (owner.get() == activity) close(); }
        @Override public void onActivityDestroyed(Activity activity) { if (owner.get() == activity) close(); }
        @Override public void onActivityCreated(Activity activity, Bundle state) { }
        @Override public void onActivityStarted(Activity activity) { }
        @Override public void onActivityResumed(Activity activity) { }
        @Override public void onActivityStopped(Activity activity) { }
        @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) { }
    }

    private static Activity activity(Context source) {
        while (source instanceof ContextWrapper && !(source instanceof Activity)) {
            Context base = ((ContextWrapper) source).getBaseContext();
            if (base == source) return null;
            source = base;
        }
        return source instanceof Activity ? (Activity) source : null;
    }

    private static void onMain(Runnable action) {
        if (Looper.myLooper() == Looper.getMainLooper()) action.run(); else MAIN.post(action);
    }

    static String shareUrl(Object aweme) {
        if (aweme instanceof String) return ThirdPartyGetClient.extractShareUrl((String) aweme);
        Object value = DownloadActionHook.jsonValue(aweme, "share_url");
        if (value == null) value = DownloadActionHook.jsonValue(
                DownloadActionHook.jsonValue(aweme, "share_info"), "share_url");
        if (value instanceof String && !((String) value).trim().isEmpty()) {
            try { return ThirdPartyGetClient.extractShareUrl((String) value); }
            catch (IllegalArgumentException ignored) { }
        }
        Object id = DownloadActionHook.jsonValue(aweme, "aweme_id");
        if (id != null && id.toString().matches("[0-9]{5,30}")) {
            Object type = DownloadActionHook.jsonValue(aweme, "aweme_type");
            return "https://www.douyin.com/" + ("68".equals(String.valueOf(type)) ? "note/" : "video/") + id;
        }
        throw new IllegalArgumentException("当前作品没有可读取的分享链接");
    }

    private static void toast(Context context, String message) {
        MAIN.post(() -> {
            try { Toast.makeText(context, message, Toast.LENGTH_LONG).show(); }
            catch (RuntimeException ignored) { }
        });
    }
}
