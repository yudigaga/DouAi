package com.douai.module.feature;

import android.content.Context;
import android.content.Intent;
import android.media.MediaMetadataRetriever;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.io.File;
import java.io.FileOutputStream;
import java.util.UUID;
import java.util.Random;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

/** One provider selection shared by manual speech, automatic replies and comments. */
public final class SpeechSynthesis implements AutoCloseable {
    private static final ThreadPoolExecutor WORKERS = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                Thread thread = new Thread(runnable, "douai-speech");
                thread.setDaemon(true);
                return thread;
            });
    static { WORKERS.allowCoreThreadTimeOut(true); }
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final Object completionLock = new Object();
    private final BiConsumer<LocalSpeechSynthesis.Result, String> callback;
    private final SpeechHttpClient.Request http = new SpeechHttpClient.Request();
    private volatile AutoCloseable local;
    private volatile LocalSpeechSynthesis.OfflineVoiceProbe probe;
    private volatile Future<?> worker;
    private volatile File output;
    private LocalSpeechSynthesis.Result pendingResult;
    private String pendingFailure;
    private boolean callbackDelivered;
    private final DouAiLog.Operation operation;

    private SpeechSynthesis(BiConsumer<LocalSpeechSynthesis.Result, String> callback) {
        if (callback == null) throw new IllegalArgumentException("Speech callback is missing");
        this.callback = callback;
        operation = DouAiLog.begin("speech_synthesis", "prepare_audio");
    }

    public static SpeechSynthesis synthesize(Context context, String text, LocalOptions options,
                                              BiConsumer<LocalSpeechSynthesis.Result, String> callback) {
        SpeechSynthesis request = new SpeechSynthesis(callback);
        if (options == null) {
            request.complete(null, "语音设置不可用");
            return request;
        }
        SpeechSettings original = new SpeechSettings(options);
        if (SpeechSettings.RANDOM_OFF.equals(original.randomMode) || context == null
                || text == null || text.trim().isEmpty()) {
            request.start(context, text, options, false);
            return request;
        }
        AtomicReference<String> timeoutFailure = new AtomicReference<>("随机音色选择超时");
        Runnable selectionTimeout = request.operation.wrap(() -> request.complete(null, timeoutFailure.get()));
        request.main.postDelayed(selectionTimeout, 60_000);
        try {
            request.worker = WORKERS.submit(request.operation.wrap(() -> {
                if (request.finished.get()) return;
                try {
                    boolean systemAvailable = hasSystemEngine(context, options);
                    if (systemAvailable && SpeechSettings.RANDOM_ALL.equals(original.randomMode)) {
                        LocalSpeechSynthesis.OfflineVoiceProbe inventory =
                                new LocalSpeechSynthesis.OfflineVoiceProbe(context, options);
                        request.probe = inventory;
                        try {
                            if (request.finished.get()) return;
                            systemAvailable = inventory.await();
                        } finally {
                            request.probe = null;
                            inventory.close();
                        }
                    }
                    if (request.finished.get()) return;
                    LocalOptions chosen = RandomSpeechVoice.select(options, new Random(), systemAvailable);
                    timeoutFailure.set(timeoutMessage(new SpeechSettings(chosen)));
                    if (!request.finished.get()) request.start(context, text, chosen, true);
                } catch (InterruptedException cancelled) {
                    Thread.currentThread().interrupt();
                    request.complete(null, "语音生成已停止");
                } catch (IllegalArgumentException invalid) {
                    request.complete(null, invalid.getMessage());
                } catch (RuntimeException invalid) {
                    request.complete(null, "随机音色配置无效，请检查设置并刷新音色列表");
                }
            }));
            if (request.finished.get()) request.worker.cancel(true);
        } catch (RejectedExecutionException ignored) {
            request.complete(null, "语音生成任务过多，请稍后重试");
        }
        return request;
    }

    private void start(Context context, String text, LocalOptions requestOptions, boolean onWorker) {
        if (finished.get()) return;
        SpeechSettings settings = new SpeechSettings(requestOptions);
        operation.stage("provider_selected", "provider=" + provider(settings)
                + "; text_chars=" + (text == null ? 0 : text.length()));
        if (settings.local) {
            operation.wrap(() -> {
                local = LocalSpeechSynthesis.synthesize(context, text, requestOptions, this::complete);
                if (finished.get()) try { local.close(); } catch (Exception ignored) { }
            }).run();
            return;
        }
        if (context == null || text == null || text.trim().isEmpty()) {
            complete(null, "语音内容为空");
            return;
        }
        Context application = context.getApplicationContext();
        Context use = application == null ? context : application;
        if (!onWorker) main.postDelayed(operation.wrap(() -> complete(null, timeoutMessage(settings))), 60_000);
        if (onWorker) {
            if (!finished.get()) generate(use, text, settings);
            return;
        }
        try {
            operation.stage("queued", "business_result=pending; timeout_ms=60000");
            worker = WORKERS.submit(operation.wrap(() -> generate(use, text, settings)));
            if (finished.get()) worker.cancel(true);
        } catch (RejectedExecutionException ignored) {
            complete(null, "语音生成任务过多，请稍后重试");
        }
    }

    private static String timeoutMessage(SpeechSettings settings) {
        return settings.local ? "本地语音生成超时" : settings.fish ? "Fish Audio 语音生成超时"
                : settings.breeze ? "Breeze Blue 语音生成超时" : settings.minimax
                ? "MiniMax 语音生成超时" : "第三方语音生成超时";
    }

    private static boolean hasSystemEngine(Context context, LocalOptions options) {
        if (context == null) return false;
        String configured = options.text("tts_engine", "").trim();
        try {
            for (android.content.pm.ResolveInfo service : context.getPackageManager().queryIntentServices(
                    new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)) {
                if (service.serviceInfo != null && service.serviceInfo.enabled && service.serviceInfo.exported
                        && (configured.isEmpty()
                        || configured.equals(service.serviceInfo.packageName))) return true;
            }
        } catch (RuntimeException ignored) { }
        return false;
    }

    private void generate(Context context, String text, SpeechSettings settings) {
        try {
            File cache = new File(context.getCacheDir(), "douai-tts");
            ensureCacheDirectory(cache);
            cleanOldFiles(cache);
            output = new File(cache, UUID.randomUUID() + ".audio");
            operation.stage("provider_request", "provider=" + provider(settings));
            SpeechHttpClient.Resource response;
            if (settings.fish) {
                response = FishSpeechClient.synthesize(http, text, settings.fishSettings, output, context);
            } else if (settings.breeze) {
                response = BreezeSpeechClient.synthesize(http, text, settings.breezeSettings, output);
            } else if (settings.minimax) {
                response = MiniMaxSpeechClient.synthesize(http, text, settings, output);
            } else {
                String url = SpeechHttpClient.resolveTemplate(settings.getTemplate, text, settings.selectedVoice);
                response = http.fetchResource(url, settings.headers, output);
                if (!response.isAudio()) {
                    operation.stage("audio_url_resolved", "response=non_audio; requires_audio_fetch=true");
                    String audioUrl = SpeechHttpClient.audioUrl(response.text(), settings.audioField);
                    // API authentication belongs to its origin, not an unrelated media/CDN host.
                    response = http.fetchResource(audioUrl,
                            SpeechHttpClient.sameOrigin(url, audioUrl) ? settings.headers : "{}", output);
                }
            }
            http.ensureActive();
            if (!response.isAudio()) throw new IllegalArgumentException("第三方语音链接未返回音频文件");
            operation.stage("audio_received", "bytes=" + response.sizeBytes() + "; business_result=validating");
            http.ensureActive();
            if (response.file == null) {
                try (FileOutputStream stream = new FileOutputStream(output)) { stream.write(response.bytes); }
            }
            File named = new File(cache, output.getName().replace(".audio", response.audioExtension()));
            java.nio.file.Files.move(output.toPath(), named.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            output = named;
            http.ensureActive();
            long duration = audioDuration(output);
            operation.stage("audio_validated", "bytes=" + output.length() + "; duration_ms=" + duration
                    + "; output_ref=" + DouAiLog.reference(output.getAbsolutePath()) + "; has_audio=true; has_video=false");
            http.ensureActive();
            complete(new LocalSpeechSynthesis.Result(output, duration), null);
        } catch (CancellationException ignored) {
            complete(null, "语音生成已停止");
        } catch (Exception failure) {
            operation.error("provider_or_audio_validation", failure);
            String message = failure.getMessage();
            // Parsing/IO failures must not leak text, credentials or provider response content.
            if (message == null || !(message.startsWith("第三方语音") || message.startsWith("MiniMax 语音")
                    || message.startsWith("Breeze Blue 语音") || message.startsWith("Fish Audio 语音") || message.startsWith("所选语音字段")
                    || message.equals("返回了多个语音链接，请选择音频字段")
                    || message.equals("返回了多个链接，请选择音频字段") || message.equals("无法创建语音缓存")
                    || message.equals("生成的语音文件无效"))) {
                message = settings.fish ? "Fish Audio 语音生成失败，请检查配置" : settings.breeze ? "Breeze Blue 语音生成失败，请检查配置" : settings.minimax
                        ? "MiniMax 语音生成失败，请检查配置" : "第三方语音生成失败，请检查接口及音频字段";
            }
            complete(null, message);
        } finally {
            if (finished.get()) deleteFailedOutput();
        }
    }

    private static String provider(SpeechSettings settings) {
        return settings.fish ? "fish" : settings.breeze ? "breeze" : settings.minimax ? "minimax" : settings.thirdParty ? "third_party" : "local";
    }

    static void ensureCacheDirectory(File cache) {
        // Another synthesis worker may create the directory after our first check.
        if (!cache.isDirectory() && !cache.mkdirs() && !cache.isDirectory()) {
            throw new IllegalArgumentException("无法创建语音缓存");
        }
    }

    static long audioDuration(File file) {
        MediaMetadataRetriever metadata = new MediaMetadataRetriever();
        try {
            if (!file.isFile() || file.length() == 0 || file.length() > SpeechHttpClient.MAX_AUDIO_BYTES) {
                throw new IllegalArgumentException();
            }
            metadata.setDataSource(file.getAbsolutePath());
            if (!"yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                    || "yes".equalsIgnoreCase(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))) {
                throw new IllegalArgumentException();
            }
            long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
            if (duration <= 0) throw new IllegalArgumentException();
            return duration;
        } catch (Exception failure) {
            throw new IllegalArgumentException("生成的语音文件无效");
        } finally {
            try { metadata.release(); } catch (Exception ignored) { }
        }
    }

    private static void cleanOldFiles(File cache) {
        File[] files = cache.listFiles();
        if (files == null) return;
        long cutoff = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1);
        for (File file : files) if (file.isFile()
                && file.getName().matches("[0-9a-fA-F-]{36}\\.(audio|wav|mp3|m4a|aac|ogg|flac)")
                && file.lastModified() < cutoff) {
            delete(file);
        }
    }

    private void complete(LocalSpeechSynthesis.Result result, String failure) {
        if (result != null && result.durationMillis <= 0) {
            delete(result.file);
            result = null;
            failure = "生成的语音文件无效";
        }
        synchronized (completionLock) {
            if (!finished.compareAndSet(false, true)) {
                operation.stage("duplicate_or_late_completion", "ignored=true; audio_returned=" + (result != null));
                if (result != null && result != pendingResult) delete(result.file);
                return;
            }
            pendingResult = result;
            pendingFailure = failure;
            if (result != null) output = null;
        }
        stopRequests();
        operation.stage("callback_pending", "audio_ready=" + (result != null));
        main.post(operation.wrap(this::deliver));
    }

    private void stopRequests() {
        http.cancel();
        LocalSpeechSynthesis.OfflineVoiceProbe inventory = probe;
        if (inventory != null) inventory.close();
        Future<?> active = worker;
        if (active != null) active.cancel(true);
    }

    private void deliver() {
        LocalSpeechSynthesis.Result result;
        String failure;
        synchronized (completionLock) {
            if (callbackDelivered) return;
            callbackDelivered = true;
            result = pendingResult;
            failure = pendingFailure;
            pendingResult = null;
        }
        main.removeCallbacksAndMessages(null);
        AutoCloseable engine = local;
        local = null;
        if (engine != null) try { engine.close(); } catch (Exception ignored) { }
        deleteFailedOutput();
        operation.finish(result != null ? DouAiLog.Outcome.SUCCESS : outcome(failure),
                result != null ? "validated_audio_ready" : reason(failure),
                result == null ? "audio_ready=false; sent=false" : "audio_ready=true; duration_ms=" + result.durationMillis
                        + "; bytes=" + result.file.length() + "; output_ref=" + DouAiLog.reference(result.file.getAbsolutePath())
                        + "; evidence=local_audio_metadata; sent=false");
        callback.accept(result, failure);
    }

    private void deleteFailedOutput() { File file = output; if (file != null) delete(file); }
    private static void delete(File file) {
        if (file == null) return;
        try { java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
    }

    @Override public void close() {
        LocalSpeechSynthesis.Result abandoned;
        synchronized (completionLock) {
            if (callbackDelivered) return;
            finished.set(true);
            abandoned = pendingResult;
            pendingResult = null;
            pendingFailure = "语音生成已停止";
        }
        if (abandoned != null) delete(abandoned.file);
        stopRequests();
        operation.stage("cancel_requested", "origin=owner; pending_audio_deleted=" + (abandoned != null));
        main.post(operation.wrap(this::deliver));
    }

    static DouAiLog.Outcome outcome(String failure) {
        return failure != null && failure.contains("超时") ? DouAiLog.Outcome.TIMED_OUT
                : failure != null && failure.contains("已停止") ? DouAiLog.Outcome.CANCELLED : DouAiLog.Outcome.FAILED;
    }
    static String reason(String failure) {
        if (failure == null) return "missing_audio_result";
        if (failure.contains("超时")) return "timeout";
        if (failure.contains("已停止")) return "owner_cancelled";
        if (failure.contains("任务过多")) return "queue_full";
        if (failure.contains("设置不可用")) return "configuration_unavailable";
        if (failure.contains("内容为空")) return "invalid_text";
        if (failure.contains("文件无效")) return "invalid_audio";
        return "provider_or_audio_failure";
    }
}
