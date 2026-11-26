package com.douai.module.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.os.Handler;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.douai.module.ai.skills.SkillHttpDeclaration;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.ai.skills.SkillVideoJobs;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Conversation-owned video cards. The main thread owns selection, visibility and all views. */
final class AiSkillVideoCards {
    private static final long POLL_MS = 3_000;
    private static final long REQUEST_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final int RETAINED_LIMIT = 100;
    private static final String UNAVAILABLE = "无法读取本机视频任务";
    private static final String CONFIG_CHANGED = "视频技能已停用，或原任务接口不可用";
    private static final String JOB_CHANGED = "视频任务状态已变化，请重新核对";
    private static final String PLAY_FAILED = "本机视频无法播放";

    private final Context context;
    private final Handler main;
    private final ExecutorService worker;
    private final SkillVideoJobs jobs;
    private final Supplier<SkillStore> store;
    private final Supplier<Activity> owner;
    private final Runnable notifyRows;
    private final Consumer<String> status;

    private final Runnable tick = this::refresh;
    private final Map<UUID, String> notes = new LinkedHashMap<>();
    private Map<UUID, SkillVideoJobs.Job> rows = Collections.emptyMap();
    private Watch watch;
    private Work poll;
    private Work action;
    private AlertDialog confirmation;
    private AiSkillVideoPreview player;
    private boolean loaded;
    private boolean foreground;
    private int nextIndex;

    AiSkillVideoCards(Context context, Handler main, ExecutorService worker, SkillVideoJobs jobs,
                      Supplier<SkillStore> store, Supplier<Activity> owner, Runnable notifyRows,
                      Consumer<String> status) {
        this.context = context;
        this.main = main;
        this.worker = worker;
        this.jobs = jobs;
        this.store = store;
        this.owner = owner;
        this.notifyRows = notifyRows;
        this.status = status;
    }

    void watch(SkillVideoJobs.Scope scope, List<UUID> retainedIds) {
        LinkedHashSet<UUID> distinct = new LinkedHashSet<>();
        if (retainedIds != null) {
            for (UUID id : retainedIds) {
                if (id != null) distinct.add(id);
                if (distinct.size() >= RETAINED_LIMIT) break;
            }
        }
        List<UUID> ids = Collections.unmodifiableList(new ArrayList<>(distinct));
        Watch next = scope == null || ids.isEmpty() ? null : new Watch(scope, ids);
        if (Objects.equals(watch, next)) return;
        clear();
        watch = next;
        schedule(0);
    }

    void setForeground(boolean focused) {
        if (foreground == focused) return;
        foreground = focused;
        if (!focused && poll != null && poll.remote) stopPoll();
        schedule(loaded ? POLL_MS : 0);
    }

    void clear() {
        main.removeCallbacks(tick);
        stopPoll();
        if (action != null) action.close();
        action = null;
        watch = null;
        rows = Collections.emptyMap();
        notes.clear();
        loaded = false;
        nextIndex = 0;
        if (confirmation != null) {
            AlertDialog open = confirmation;
            confirmation = null;
            open.dismiss();
        }
        if (player != null) {
            AiSkillVideoPreview open = player;
            player = null;
            open.dismiss();
        }
    }

    void bind(LinearLayout target, UUID id) {
        target.removeAllViews();
        target.setVisibility(id == null ? View.GONE : View.VISIBLE);
        target.setTag(id == null ? null : "ai.chat.skill.video.card");
        if (id == null) return;
        SkillVideoJobs.Job row = rows.get(id);
        TextView label = new TextView(context);
        label.setTag("ai.chat.skill.video.state");
        label.setTextSize(14);
        label.setTextColor(ModuleTheme.color(context, 0xFF69737A));
        label.setText(row == null ? (loaded ? UNAVAILABLE : "正在读取视频任务…") : stateText(row.state()));
        label.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        target.addView(label, wrap());
        String note = notes.get(id);
        if (note != null) {
            TextView detail = new TextView(context);
            detail.setTextSize(12);
            detail.setTextColor(ModuleTheme.color(context, 0xFF69737A));
            detail.setText(note);
            target.addView(detail, wrap());
        }
        if (row == null || watch == null) return;
        if (row.state() == SkillVideoJobs.State.COMPLETED) {
            addButton(target, "播放视频", "ai.chat.skill.video.play", () -> play(row));
        } else if (row.canCancel()) {
            addButton(target, "取消任务", "ai.chat.skill.video.cancel", () -> prepareCancel(row));
        }
    }

    private void addButton(LinearLayout target, String title, String tag, Runnable click) {
        Button button = new Button(context);
        button.setText(title);
        button.setAllCaps(false);
        button.setTag(tag);
        button.setEnabled(action == null);
        button.setOnClickListener(ignored -> click.run());
        target.addView(button, wrap());
    }

    private void schedule(long delay) {
        main.removeCallbacks(tick);
        if (watch == null || poll != null || action != null) return;
        if (!loaded || foreground && rows.values().stream().anyMatch(job -> !job.state().terminal())) {
            main.postDelayed(tick, delay);
        }
    }

    private void refresh() {
        if (watch == null || poll != null || action != null || loaded && !foreground) return;
        Work task = new Work(watch, loaded && foreground);
        poll = task;
        int start = nextIndex;
        try {
            task.future = worker.submit(() -> {
                Map<UUID, SkillVideoJobs.Job> local;
                try {
                    task.check();
                    local = readRows(task.watch);
                } catch (IOException unavailable) {
                    main.post(() -> finishPoll(task, null, null, null));
                    return;
                }
                SkillVideoJobs.Job selected = task.remote ? nextJob(task.watch, local, start) : null;
                String warning = null;
                if (selected != null) {
                    main.post(() -> publishPollRows(task, local));
                    try {
                        task.check();
                        SkillVideoJobs.Access access = openAccess(selected, task);
                        SkillVideoJobs.Job fresh = jobs.refresh(task.watch.scope(), selected.localId(), access, deadline());
                        Map<UUID, SkillVideoJobs.Job> updated = new LinkedHashMap<>(local);
                        if (fresh == null) updated.remove(selected.localId());
                        else updated.put(selected.localId(), fresh);
                        main.post(() -> finishPoll(task, updated, selected.localId(), null));
                        return;
                    } catch (SkillVideoJobs.Failure unavailable) {
                        warning = unavailable.getMessage();
                    } catch (Exception unavailable) {
                        warning = CONFIG_CHANGED;
                    }
                }
                String message = warning;
                main.post(() -> finishPoll(task, local, selected == null ? null : selected.localId(), message));
            });
        } catch (RejectedExecutionException unavailable) {
            finishPoll(task, null, null, null);
        }
    }

    private void publishPollRows(Work task, Map<UUID, SkillVideoJobs.Job> local) {
        if (poll == task && current(task)) updateRows(local, null, null);
    }

    private void finishPoll(Work task, Map<UUID, SkillVideoJobs.Job> local, UUID selected, String warning) {
        if (poll != task || !current(task)) return;
        poll = null;
        if (selected != null) nextIndex = task.watch.ids().indexOf(selected) + 1;
        updateRows(local == null ? rows : local, selected, warning);
        schedule(POLL_MS);
    }

    private void updateRows(Map<UUID, SkillVideoJobs.Job> local, UUID selected, String warning) {
        boolean changed = !loaded || !rows.equals(local);
        loaded = true;
        rows = local;
        changed |= notes.keySet().retainAll(rows.keySet());
        if (selected != null) {
            String previous = warning == null ? notes.remove(selected) : notes.put(selected, warning);
            changed |= !Objects.equals(previous, warning);
        }
        if (changed) notifyRows.run();
    }

    private Map<UUID, SkillVideoJobs.Job> readRows(Watch current) throws SkillVideoJobs.Failure {
        Map<UUID, SkillVideoJobs.Job> local = new LinkedHashMap<>();
        for (SkillVideoJobs.Job job : jobs.list(current.scope())) {
            if (current.ids().contains(job.localId())) local.put(job.localId(), job);
        }
        return local;
    }

    private static SkillVideoJobs.Job nextJob(Watch watch, Map<UUID, SkillVideoJobs.Job> local, int start) {
        List<UUID> ids = watch.ids();
        for (int offset = 0; offset < ids.size(); offset++) {
            SkillVideoJobs.Job job = local.get(ids.get((start + offset) % ids.size()));
            if (job != null && !job.state().terminal()) return job;
        }
        return null;
    }

    private SkillVideoJobs.Access openAccess(SkillVideoJobs.Job job, Work task) throws Exception {
        task.check();
        SkillVideoJobs.Access access = store.get().openVideoAccess(jobs, task.watch.scope(), job);
        task.check();
        return new SkillVideoJobs.Access(access.binding(), access.bearer(), () -> {
            task.check();
            access.admission().check();
            task.check();
        });
    }

    private void play(SkillVideoJobs.Job job) {
        perform(job, PLAY_FAILED, task -> jobs.playableFile(task.watch.scope(), job.localId()), file -> {
            Activity activity = currentOwner();
            if (file == null || activity == null) {
                status.accept(PLAY_FAILED);
                return;
            }
            if (player != null) player.dismiss();
            AiSkillVideoPreview preview = new AiSkillVideoPreview(activity, file);
            preview.setOnDismissListener(ignored -> { if (player == preview) player = null; });
            player = preview;
            try {
                preview.show();
            } catch (RuntimeException unavailable) {
                player = null;
                status.accept(PLAY_FAILED);
            }
        });
    }

    private void prepareCancel(SkillVideoJobs.Job job) {
        if (confirmation != null || !job.canCancel()) return;
        Watch expected = watch;
        perform(job, CONFIG_CHANGED, task -> {
            requireExact(task, job);
            return cancelTarget(task.watch.scope(), job.localId(), openAccess(job, task));
        }, target -> showConfirmation(expected, job, target));
    }

    private CancelTarget cancelTarget(SkillVideoJobs.Scope scope, UUID id, SkillVideoJobs.Access access) throws IOException {
        SkillHttpDeclaration.Video video = access.binding().declaration().video();
        if (!video.canCancel()) throw new StaleJob();
        return new CancelTarget(video.cancelMethod(), jobs.cancelTarget(scope, id, access));
    }

    private void showConfirmation(Watch expected, SkillVideoJobs.Job job, CancelTarget target) {
        Activity activity = currentOwner();
        if (watch != expected || activity == null || !job.equals(rows.get(job.localId()))) return;
        AlertDialog dialog = new AlertDialog.Builder(activity, ModuleTheme.alertTheme(activity))
                .setTitle("取消视频任务")
                .setMessage("将向 " + target.method() + " " + target.endpoint()
                        + " 发送取消请求。\n" + (target.method().equals("POST") ? "请求体为空对象 {}。" : "此请求没有请求体。"))
                .setNegativeButton("返回", null)
                .setPositiveButton("确认取消", (decision, which) -> {
                    if (confirmation == decision && watch == expected) cancel(job, target);
                }).create();
        dialog.setOnDismissListener(ignored -> { if (confirmation == dialog) confirmation = null; });
        confirmation = dialog;
        try {
            dialog.show();
        } catch (RuntimeException unavailable) {
            confirmation = null;
            status.accept("无法显示取消确认");
        }
    }

    private void cancel(SkillVideoJobs.Job job, CancelTarget expected) {
        perform(job, CONFIG_CHANGED, task -> {
            requireExact(task, job);
            SkillVideoJobs.Access access = openAccess(job, task);
            if (!expected.equals(cancelTarget(task.watch.scope(), job.localId(), access))) throw new StaleJob();
            return jobs.cancel(task.watch.scope(), job.localId(), access, deadline());
        }, updated -> {
            if (updated != null) {
                Map<UUID, SkillVideoJobs.Job> local = new LinkedHashMap<>(rows);
                local.put(updated.localId(), updated);
                updateRows(local, updated.localId(), null);
            }
        });
    }

    private void requireExact(Work task, SkillVideoJobs.Job expected) throws IOException {
        task.check();
        if (!expected.equals(readRows(task.watch).get(expected.localId()))) throw new StaleJob();
    }

    @FunctionalInterface private interface Action<T> { T run(Work task) throws Exception; }

    private <T> void perform(SkillVideoJobs.Job job, String fallback, Action<T> operation, Consumer<T> onSuccess) {
        if (watch == null || action != null || !job.equals(rows.get(job.localId()))) return;
        stopPoll();
        Work task = new Work(watch, false);
        action = task;
        notifyRows.run();
        try {
            task.future = worker.submit(() -> {
                try {
                    task.check();
                    T result = operation.run(task);
                    main.post(() -> finishAction(task, () -> onSuccess.accept(result)));
                } catch (SkillVideoJobs.Failure unavailable) {
                    main.post(() -> finishAction(task, () -> status.accept(unavailable.getMessage())));
                } catch (StaleJob stale) {
                    main.post(() -> finishAction(task, () -> status.accept(JOB_CHANGED)));
                } catch (Exception unavailable) {
                    main.post(() -> finishAction(task, () -> status.accept(fallback)));
                }
            });
        } catch (RejectedExecutionException unavailable) {
            finishAction(task, () -> status.accept(fallback));
        }
    }

    private void finishAction(Work task, Runnable result) {
        if (action != task || !current(task)) return;
        action = null;
        result.run();
        if (watch == task.watch) notifyRows.run();
        schedule(POLL_MS);
    }

    private boolean current(Work task) { return watch == task.watch && task.active; }

    private Activity currentOwner() {
        Activity activity = owner.get();
        return activity == null || activity.isFinishing() || activity.isDestroyed() ? null : activity;
    }

    private void stopPoll() {
        if (poll != null) poll.close();
        poll = null;
    }

    private static String stateText(SkillVideoJobs.State state) {
        return switch (state) {
            case SUBMIT_UNKNOWN -> "视频提交结果未知";
            case QUEUED -> "视频排队中";
            case RUNNING -> "正在生成视频";
            case CANCEL_PENDING -> "等待供应商确认取消";
            case DOWNLOAD_READY -> "视频已生成，等待下载";
            case COMPLETED -> "视频已保存";
            case FAILED -> "视频生成失败";
            case CANCELLED -> "视频任务已取消";
            case TIMED_OUT -> "视频任务已超时";
        };
    }

    private static long deadline() { return System.nanoTime() + REQUEST_NANOS; }

    private static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private record Watch(SkillVideoJobs.Scope scope, List<UUID> ids) {}
    private record CancelTarget(String method, String endpoint) {}
    private static final class StaleJob extends IOException {}

    private static final class Work {
        final Watch watch;
        final boolean remote;
        volatile boolean active = true;
        Future<?> future;

        Work(Watch watch, boolean remote) {
            this.watch = watch;
            this.remote = remote;
        }

        void close() {
            active = false;
            if (future != null) future.cancel(true);
        }

        void check() throws IOException {
            if (!active || Thread.currentThread().isInterrupted()) throw new IOException();
        }
    }
}
