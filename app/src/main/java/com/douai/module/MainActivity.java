package com.douai.module;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.douai.module.config.FrameworkConfigSync;
import com.douai.module.config.ModuleConfigRuntime;
import com.douai.module.diagnostics.ScanDiagnosticsChannel;
import com.douai.module.diagnostics.HostAdaptationDiagnostics;
import com.douai.module.diagnostics.DebugLog;
import com.douai.module.ui.ModuleStatusScreen;
import com.douai.module.ui.ScanStatus;
import com.douai.module.ui.ConfigurationStatus;

import androidx.activity.ComponentActivity;

import io.github.libxposed.service.XposedService;
import java.util.concurrent.atomic.AtomicLong;

public final class MainActivity extends ComponentActivity {
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong diagnosticsRequestId = new AtomicLong();
    private final Runnable diagnosticsPoll = new Runnable() {
        @Override
        public void run() {
            if (resumed && activeService != null && !destroyed) readScanDiagnostics();
        }
    };

    private ModuleConfigRuntime configRuntime;
    private FrameworkConfigSync.Listener configStatusListener;
    private ModuleConfigRuntime.ServiceListener configServiceListener;
    private ModuleStatusScreen settingsScreen;
    private XposedService activeService;
    private boolean destroyed;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        DebugLog.initialize(getApplicationContext());

        configRuntime = ModuleConfigRuntime.get(this);
        configStatusListener = (connected, message, error) -> {
            if (!destroyed && settingsScreen != null) {
                settingsScreen.setFrameworkSyncStatus(configRuntime.sync().isConnected(), message, error);
            }
        };
        configRuntime.addStatusListener(configStatusListener);

        settingsScreen = new ModuleStatusScreen(this);
        setContentView(settingsScreen.createView());
        settingsScreen.setScanStatus(ScanStatus.pending());
        boolean serviceConnected = configRuntime.sync().isConnected();
        settingsScreen.setFrameworkSyncStatus(serviceConnected,
                serviceConnected ? "已连接 LSPosed，可读取抖音进程诊断。"
                        : "等待 LSPosed 服务连接以读取抖音进程诊断。", null);

        configServiceListener = new ModuleConfigRuntime.ServiceListener() {
            @Override
            public void onServiceBound(XposedService service) {
                runOnUiThread(() -> handleServiceBound(service));
            }

            @Override
            public void onServiceDied(XposedService service) {
                runOnUiThread(() -> handleServiceDied(service));
            }
        };
        configRuntime.addServiceListener(configServiceListener);
    }

    @Override
    protected void onResume() {
        super.onResume();
        resumed = true;
        clearConfigurationStatus();
        if (activeService != null) readScanDiagnostics();
    }

    @Override
    protected void onPause() {
        resumed = false;
        clearConfigurationStatus();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        resumed = false;
        clearConfigurationStatus();
        if (settingsScreen != null) settingsScreen.close();
        settingsScreen = null;
        if (configRuntime != null) {
            configRuntime.removeStatusListener(configStatusListener);
            configRuntime.removeServiceListener(configServiceListener);
        }
        activeService = null;
        super.onDestroy();
    }

    private void handleServiceBound(XposedService service) {
        if (destroyed || service == null || configRuntime == null
                || configRuntime.service() != service) return;
        clearConfigurationStatus();
        activeService = service;
        if (resumed) readScanDiagnostics();
    }

    private void handleServiceDied(XposedService service) {
        if (destroyed) return;
        if (service == null) {
            if (configRuntime == null || configRuntime.service() != null) return;
        } else if (service != activeService) return;
        activeService = null;
        clearConfigurationStatus();
    }

    private void clearConfigurationStatus() {
        diagnosticsRequestId.incrementAndGet();
        mainHandler.removeCallbacks(diagnosticsPoll);
        if (settingsScreen != null) settingsScreen.setConfigurationStatus(ConfigurationStatus.unavailable());
    }

    private boolean acceptScanDiagnostics(long requestId, Bundle snapshot) {
        if (destroyed || !resumed || activeService == null
                || requestId != diagnosticsRequestId.get() || settingsScreen == null) return false;
        ConfigurationStatus configuration = ConfigurationStatus.fromDiagnostics(snapshot);
        settingsScreen.setConfigurationStatus(configuration);
        if (snapshot == null || configuration.pending) scheduleDiagnosticsPoll();
        return true;
    }

    private void readScanDiagnostics() {
        if (destroyed || !resumed || activeService == null || settingsScreen == null) return;
        long requestId = diagnosticsRequestId.incrementAndGet();
        ScanDiagnosticsChannel.request(this, mainHandler, snapshot -> {
            if (!acceptScanDiagnostics(requestId, snapshot)) return;
            if (snapshot == null) {
                settingsScreen.setScanStatus(new ScanStatus("等待抖音进程",
                        "打开抖音后返回此页面，即可读取扫描状态。", false, false));
                return;
            }

            boolean complete = snapshot.getBoolean("complete", false);
            String failure = snapshot.getString("failure");
            String summary = snapshot.getString("summary");
            if (complete && summary != null && summary.startsWith("rules=0,")) {
                settingsScreen.setScanStatus(new ScanStatus("等待适配规则",
                        "已识别抖音 DEX；当前版本尚无已验证的方法规则，因此没有安装功能 Hook。",
                        false, false));
            } else if (complete) {
                String detail = summary == null ? "DEX 扫描已完成。" : summary;
                detail += "\n" + HostAdaptationDiagnostics.describe(snapshot);
                boolean adaptationVerified = snapshot.getBoolean("host_adaptation_static_verified", false);
                boolean feedInstalled = snapshot.getBoolean("feed_hook_installed", false);
                boolean downloadInstalled = snapshot.getBoolean("download_hook_installed", false);
                boolean pendingHook = false;
                boolean hasFailure = snapshot.getInt("host_adaptation_feature_rule_gaps", 0) > 0
                        || snapshot.getInt("host_adaptation_features_uninstalled", 0) > 0
                        || snapshot.getInt("feed_ui_resource_mismatches", 0) > 0;
                if (feedInstalled) {
                    boolean enabled = snapshot.getBoolean("feed_filter_enabled", false);
                    int filteredPages = snapshot.getInt("feed_filtered_pages", 0);
                    detail += enabled
                            ? "\n推荐流筛选已启用；已处理页面：" + filteredPages
                            : "\n推荐流筛选 Hook 已安装，当前设置为关闭。";
                } else {
                    String hookFailure = snapshot.getString("feed_hook_failure");
                    detail += "\n推荐流筛选 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (downloadInstalled) {
                    detail += snapshot.getBoolean("download_enabled", false)
                            ? "\n原生下载扩展菜单已启用。"
                            : "\n原生下载扩展菜单已安装，当前设置为关闭。";
                } else {
                    String hookFailure = snapshot.getString("download_hook_failure");
                    detail += "\n原生下载扩展菜单未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("version_hook_installed", false)) {
                    detail += snapshot.getBoolean("version_spoof_enabled", false)
                            ? "\n版本信息修改已启用。"
                            : "\n版本信息 Hook 已安装，当前设置为关闭。";
                } else {
                    String hookFailure = snapshot.getString("version_hook_failure");
                    detail += "\n版本信息 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("feed_ui_hook_installed", false)) {
                    if (snapshot.getBoolean("feed_ui_active", false)) {
                        detail += (snapshot.getBoolean("component_hide_configured", false)
                                ? "\n组件隐藏已启用；生命周期回调："
                                : "\n视频界面精简已启用；生命周期回调：")
                                + snapshot.getInt("feed_ui_selected_callbacks", 0)
                                + "，已扫描：" + snapshot.getInt("feed_ui_card_scans", 0)
                                + "，视图查找：" + snapshot.getInt("feed_ui_view_searches", 0)
                                + "，资源名不符：" + snapshot.getInt("feed_ui_resource_mismatches", 0)
                                + "，重绘复查：" + snapshot.getInt("feed_ui_predraw_rechecks", 0)
                                + "，重置后重应用：" + snapshot.getInt("feed_ui_reapplied_views", 0)
                                + "，命中控件：" + snapshot.getInt("feed_ui_hidden_views", 0) + "。";
                        if (snapshot.getBoolean("component_hide_configured", false)) {
                            detail += snapshot.getBoolean("component_hide_temporarily_shown", false)
                                    ? "\n当前暂停展示组件。" : "\n当前按组件设置应用隐藏。";
                        } else {
                            detail += "\n右侧透明度：" + snapshot.getInt("feed_ui_alpha_percent", 100) + "%";
                        }
                    } else {
                        detail += "\n视频界面 Hook 已安装，当前设置为关闭。";
                    }
                } else {
                    String hookFailure = snapshot.getString("feed_ui_hook_failure");
                    detail += "\n视频界面 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    }
                }
                if (snapshot.getBoolean("refresh_hook_installed", false)) {
                    detail += snapshot.getBoolean("refresh_confirm_enabled", false)
                            ? "\n首页刷新确认已启用。"
                            : "\n刷新确认 Hook 已安装，当前设置为关闭。";
                } else {
                    String hookFailure = snapshot.getString("refresh_hook_failure");
                    detail += "\n刷新确认 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_like_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_like_enabled", false)
                            ? "\n自动点赞已启用。"
                            : "\n自动点赞 Hook 已安装，当前总开关或功能开关关闭。";
                } else {
                    String hookFailure = snapshot.getString("auto_like_hook_failure");
                    detail += "\n自动点赞 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_follow_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_follow_enabled", false)
                            ? "\n自动关注已启用。"
                            : "\n自动关注 Hook 已安装，当前总开关或功能开关关闭。";
                } else {
                    String hookFailure = snapshot.getString("auto_follow_hook_failure");
                    detail += "\n自动关注 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_play_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_play_enabled", false)
                            ? "\nDouAi 自动滑动已启用，跟随上次手动方向。"
                            : "\nDouAi 自动滑动 Hook 已安装，当前设置为关闭。";
                    String status = snapshot.getString("auto_play_status");
                    if (status != null && !status.isEmpty()) detail += "\n" + status;
                } else {
                    String hookFailure = snapshot.getString("auto_play_hook_failure");
                    detail += "\nDouAi 自动滑动 Hook 未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("auto_comment_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_comment_enabled", false)
                            ? "\n普通竖屏评论草稿预填已启用；发布仍需手动确认。"
                            : "\n评论草稿 Hook 已安装，当前总开关或功能开关关闭。";
                } else {
                    String hookFailure = snapshot.getString("auto_comment_hook_failure");
                    detail += "\n评论草稿预填未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                int unknownStreakCount = snapshot.getInt("unknown_streak_count", 0);
                if (unknownStreakCount > 0) {
                    detail += "\n有 " + unknownStreakCount + " 条续火花发送结果未知；可在互动自动化的续火花设置中查看并选择重试。";
                }
                if (snapshot.getBoolean("auto_message_hook_installed", false)) {
                    detail += snapshot.getBoolean("auto_message_enabled", false)
                            ? "\n单聊草稿预填已启用；发送仍需手动确认。"
                            : "\n单聊草稿 Hook 已安装，当前总开关或功能开关关闭。";
                } else {
                    String hookFailure = snapshot.getString("auto_message_hook_failure");
                    detail += "\n单聊草稿预填未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("comment_tools_hook_installed", false)) {
                    detail += "\n评论长按工具已接入。";
                } else {
                    String hookFailure = snapshot.getString("comment_tools_hook_failure");
                    detail += "\n评论长按工具未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.getBoolean("immersive_hook_installed", false)) {
                    boolean statusHidden = snapshot.getBoolean("immersive_status_bar_enabled", false);
                    boolean navigationHidden = snapshot.getBoolean("immersive_navigation_bar_enabled", false);
                    if (statusHidden || navigationHidden) {
                        detail += "\n系统栏隐藏已启用："
                                + (statusHidden ? "状态栏 " : "")
                                + (navigationHidden ? "导航栏" : "") + "。";
                    } else {
                        detail += "\n系统栏控制器已安装，当前设置为关闭。";
                    }
                } else {
                    String hookFailure = snapshot.getString("immersive_hook_failure");
                    detail += "\n系统栏控制器未安装。";
                    if (hookFailure != null && !hookFailure.isEmpty()) {
                        detail += "\n" + hookFailure;
                        hasFailure = true;
                    } else pendingHook = true;
                }
                if (snapshot.containsKey("additional_initialized")) {
                    if (!snapshot.getBoolean("additional_initialized")) pendingHook = true;
                    else {
                        String[][] features = {
                                {"remove_splash_ads", "启动广告"}, {"remove_update_dialog", "更新弹窗"},
                                {"remove_teen_dialog", "青少年提示"}, {"hide_video_black_edges", "上下黑边"},
                                {"hide_camera_plus", "拍摄加号"}, {"disable_double_tap_like", "禁用双击点赞"},
                                {"prevent_skip_hidden", "视频跳过"}, {"prevent_insert_video", "视频插播"},
                                {"block_long_press_speed", "长按倍速拦截"}, {"auto_speed", "自动倍速"},
                                {"pause_auto_on_comments", "评论展开暂停连播"}, {"smart_swipe", "滑动方向"},
                                {"spoof_profile", "本地数量显示"}, {"prevent_message_recall", "消息防撤回"},
                                {"native_auto_reply", "回复和续火花"}, {"recall_background", "撤回背景色"},
                                {"media_model", "下载呈现"}, {"comment_enhancements", "评论后缀及颜色"},
                                {"comment_voice", "评论语音替换"}, {"comment_tts", "评论文字转语音"},
                                {"dm_speech", "私聊语音"}, {"toolbar_enabled", "悬浮条"},
                                {"long_press_action", "长按动作"}, {"double_tap_action", "双击动作"}
                        };
                        int installed = 0;
                        StringBuilder missing = new StringBuilder();
                        for (String[] feature : features) {
                            if (snapshot.getBoolean("feature." + feature[0] + ".installed")) installed++;
                            else {
                                if (missing.length() > 0) missing.append("、");
                                missing.append(feature[1]);
                            }
                        }
                        detail += "\n扩展 Hook 安装：" + installed + "/" + features.length + "。";
                        if (missing.length() > 0) {
                            detail += "\n未安装：" + missing;
                            hasFailure = true;
                        }
                    }
                    detail += "\n运行模块版本：" + snapshot.getString("module_version", "未知");
                }
                settingsScreen.setScanStatus(new ScanStatus(
                        !adaptationVerified ? "宿主适配未验证"
                                : hasFailure ? "部分功能未接入" : "Hook 已接入，行为待验证", detail,
                        adaptationVerified && !hasFailure && (feedInstalled || downloadInstalled
                                || snapshot.getBoolean("version_hook_installed", false)
                                || snapshot.getBoolean("feed_ui_hook_installed", false)
                                || snapshot.getBoolean("refresh_hook_installed", false)
                                || snapshot.getBoolean("auto_like_hook_installed", false)
                                || snapshot.getBoolean("auto_follow_hook_installed", false)
                                || snapshot.getBoolean("auto_play_hook_installed", false)
                                || snapshot.getBoolean("auto_comment_hook_installed", false)
                                || snapshot.getBoolean("auto_message_hook_installed", false)
                                || snapshot.getBoolean("comment_tools_hook_installed", false)), false));
                if (pendingHook) scheduleDiagnosticsPoll();
            } else if (failure != null && !failure.isEmpty()) {
                settingsScreen.setScanStatus(new ScanStatus("扫描失败", failure, false, false));
            } else {
                settingsScreen.setScanStatus(ScanStatus.pending());
                scheduleDiagnosticsPoll();
            }
        });
    }

    private void scheduleDiagnosticsPoll() {
        if (resumed && activeService != null && !destroyed) {
            mainHandler.removeCallbacks(diagnosticsPoll);
            mainHandler.postDelayed(diagnosticsPoll, 2000L);
        }
    }

}
