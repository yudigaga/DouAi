package com.douai.module.ui;

import android.os.Bundle;

import com.douai.module.config.ConfigurationPublication.Reason;
import com.douai.module.config.ConfigurationPublication.Status;
import com.douai.module.config.ConfigurationPublication.Summary;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

/** Presents the last observed local configuration acknowledgement, never a business result. */
public final class ConfigurationStatus {
    private static final String PREFIX = "configuration.consumer.";
    private static final String[] NODE_FIELDS = {
            "installed", "desired_revision", "applied_revision", "status", "reason", "attempts", "failure_class"
    };
    private static final String SCOPE = "仅反映最近一次本地读取和应用，不代表刚保存的选项已应用，也不代表具体功能结果。";

    public final String title;
    public final String detail;
    public final boolean applied;
    public final boolean pending;

    private ConfigurationStatus(String title, String detail, boolean applied, boolean pending) {
        this.title = title;
        this.detail = detail + "\n" + SCOPE;
        this.applied = applied;
        this.pending = pending;
    }

    public static ConfigurationStatus unavailable() {
        return new ConfigurationStatus("最近配置状态未获取", "暂未获取最近一次本地配置的读取和应用状态。", false, false);
    }

    public static ConfigurationStatus fromDiagnostics(Bundle diagnostics) {
        if (diagnostics == null) return unavailable();
        try {
            return read(new Bundle(diagnostics));
        } catch (RuntimeException unavailable) {
            // Missing, wrong-typed or future diagnostic fields never establish an acknowledgement.
            return unavailable();
        }
    }

    private static ConfigurationStatus read(Bundle snapshot) {
        Object read = snapshot.get("configuration.read_status");
        if (!(read instanceof String)) return unavailable();
        if ("FAILED".equals(read)) {
            return new ConfigurationStatus("最近配置读取失败", "读取最近一次本地配置失败；上一次配置可能仍被保留，不能确认新设置已经应用。", false, false);
        }
        if (!Arrays.asList("NOT_READ", "DEFERRED", "READY").contains(read)) return unavailable();
        Object closed = snapshot.get("configuration.closed");
        if (snapshot.containsKey("configuration.closed") && !(closed instanceof Boolean)) return unavailable();
        if (Boolean.TRUE.equals(closed)) {
            return new ConfigurationStatus("最近配置应用已停止", "本地配置应用已停止；已记录的状态仅供参考。", false, false);
        }
        if ("NOT_READ".equals(read)) {
            return new ConfigurationStatus("最近配置尚未读取", "尚未读取最近一次本地配置。", false, true);
        }
        if ("DEFERRED".equals(read)) {
            return new ConfigurationStatus("最近配置等待导入完成", "配置导入尚未稳定，暂未读取新的本地配置。", false, true);
        }
        Object revisionValue = snapshot.get("configuration.revision");
        if (!(closed instanceof Boolean) || !(revisionValue instanceof Long) || (Long) revisionValue <= 0) {
            return unavailable();
        }
        long revision = (Long) revisionValue;
        Summary summary = token(snapshot, "configuration.summary", Summary.class);
        Set<String> receivers = receivers(snapshot);
        if (summary == null || receivers == null || receivers.isEmpty()) return unavailable();

        int applied = 0, applying = 0, waitingReceiver = 0, waitingDependency = 0, retrying = 0, failed = 0;
        for (String receiver : receivers) {
            String prefix = PREFIX + receiver + ".";
            for (String field : NODE_FIELDS) if (!snapshot.containsKey(prefix + field)) return unavailable();
            Object installedValue = snapshot.get(prefix + "installed");
            Object desiredValue = snapshot.get(prefix + "desired_revision");
            Object appliedValue = snapshot.get(prefix + "applied_revision");
            Object attemptsValue = snapshot.get(prefix + "attempts");
            Object failure = snapshot.get(prefix + "failure_class");
            if (!(installedValue instanceof Boolean) || !(desiredValue instanceof Long)
                    || !(appliedValue instanceof Long) || !(attemptsValue instanceof Integer)
                    || failure != null && !(failure instanceof String)) return unavailable();
            boolean installed = (Boolean) installedValue;
            long desired = (Long) desiredValue, acknowledged = (Long) appliedValue;
            int attempts = (Integer) attemptsValue;
            Status status = token(snapshot, prefix + "status", Status.class);
            Reason reason = token(snapshot, prefix + "reason", Reason.class);
            if (status == null || reason == null || desired <= 0 || desired > revision
                    || acknowledged < 0 || acknowledged > desired || attempts < 0) return unavailable();
            // Unrelated newer publications can leave an unfinished receiver's relevant revision behind.
            if (status == Status.APPLIED) {
                if (!installed || reason != Reason.NONE || acknowledged != desired || attempts == 0 || failure != null) {
                    return unavailable();
                }
                applied++;
                continue;
            }
            if (acknowledged == desired) return unavailable();
            switch (status) {
                case APPLYING:
                    if (!installed || reason != Reason.APPLYING || attempts == 0) return unavailable();
                    applying++;
                    break;
                case WAITING_NOT_INSTALLED:
                    if (installed || reason != Reason.NOT_INSTALLED || attempts != 0 || failure != null) return unavailable();
                    waitingReceiver++;
                    break;
                case WAITING_DEPENDENCY:
                    if (!installed || reason != Reason.DEPENDENCY) return unavailable();
                    waitingDependency++;
                    break;
                case RETRY_SCHEDULED:
                    if (!installed || attempts == 0 || !retryReason(reason) || !hasFailure(failure)) return unavailable();
                    retrying++;
                    break;
                case FAILED:
                    if (!installed || attempts == 0 || !(retryReason(reason) || reason == Reason.PERMANENT_FAILURE
                            || reason == Reason.DEPENDENCY_INVALIDATED)
                            || reason != Reason.DEPENDENCY_INVALIDATED && !hasFailure(failure)) return unavailable();
                    failed++;
                    break;
                default:
                    return unavailable();
            }
        }
        int total = receivers.size();
        Summary observed = applied == total ? Summary.SUCCESS : applied > 0 ? Summary.PARTIAL
                : failed > 0 ? Summary.FAILED : Summary.PENDING;
        if (summary != observed) return unavailable();
        boolean pending = applying + waitingReceiver + waitingDependency + retrying > 0;
        String title = applied == total ? "最近配置已完成本地应用"
                : failed == total ? "最近配置应用失败" : failed > 0 ? "最近配置部分应用失败"
                : retrying > 0 ? "最近配置正在重试" : applying > 0 ? "最近配置正在应用"
                : waitingReceiver > 0 ? "最近配置等待接收方" : "最近配置等待依赖";
        String detail = "最近一次读取的本地配置：第 " + revision + " 次更新。\n已完成 " + applied + "/" + total + " 项本地应用。";
        StringBuilder unfinished = new StringBuilder();
        count(unfinished, "正在应用", applying);
        count(unfinished, "等待接收方", waitingReceiver);
        count(unfinished, "等待依赖", waitingDependency);
        count(unfinished, "正在重试", retrying);
        count(unfinished, "应用失败", failed);
        if (unfinished.length() > 0) detail += "\n" + unfinished + "。";
        return new ConfigurationStatus(title, detail, applied == total, pending);
    }

    private static Set<String> receivers(Bundle snapshot) {
        Set<String> result = new LinkedHashSet<>();
        for (String key : snapshot.keySet()) {
            if (!key.startsWith(PREFIX)) continue;
            int separator = key.lastIndexOf('.');
            if (separator < PREFIX.length()) return null;
            String receiver = key.substring(PREFIX.length(), separator);
            if (!receiver.matches("[a-z][a-z0-9_]*(\\.[a-z][a-z0-9_]*)*")
                    || !Arrays.asList(NODE_FIELDS).contains(key.substring(separator + 1))) return null;
            result.add(receiver);
        }
        return result;
    }

    private static <T extends Enum<T>> T token(Bundle snapshot, String key, Class<T> type) {
        Object value = snapshot.get(key);
        return value instanceof String ? Enum.valueOf(type, (String) value) : null;
    }

    private static boolean retryReason(Reason reason) {
        return reason == Reason.FACTORY_FAILURE || reason == Reason.APPLY_FAILURE
                || reason == Reason.ACK_TIMEOUT || reason == Reason.SCHEDULER_FAILURE;
    }

    private static boolean hasFailure(Object failure) { return failure instanceof String && !((String) failure).isEmpty(); }

    private static void count(StringBuilder text, String label, int count) {
        if (count == 0) return;
        if (text.length() > 0) text.append("；");
        text.append(label).append(' ').append(count).append(" 项");
    }
}
