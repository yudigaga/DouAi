package com.douai.module.feature;

import android.content.Context;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;

import com.douai.module.config.LocalOptions;
import com.douai.module.config.SpeechSettings;
import com.douai.module.diagnostics.DouAiLog;

import java.io.File;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** A bounded offline synthesis request using an installed Android TTS engine. */
public final class LocalSpeechSynthesis implements AutoCloseable {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicBoolean finished = new AtomicBoolean();
    private final BiConsumer<Result, String> callback;
    private volatile TextToSpeech engine;
    private volatile File output;
    private final DouAiLog.Operation operation = DouAiLog.begin("local_speech_synthesis", "offline_audio");

    public static final class Result {
        public final File file;
        public final long durationMillis;
        Result(File file, long durationMillis) { this.file = file; this.durationMillis = durationMillis; }
    }

    private LocalSpeechSynthesis(BiConsumer<Result, String> callback) { this.callback = callback; }

    public static LocalSpeechSynthesis synthesize(Context context, String text, LocalOptions options,
                                                    BiConsumer<Result, String> callback) {
        LocalSpeechSynthesis request = new LocalSpeechSynthesis(callback);
        request.main.post(request.operation.wrap(() -> request.start(context, text, options)));
        return request;
    }

    private void start(Context context, String text, LocalOptions options) {
        if (finished.get()) return;
        if (context == null || text == null || text.trim().isEmpty()
                || text.length() > TextToSpeech.getMaxSpeechInputLength()) {
            complete(null, "语音内容为空或过长");
            return;
        }
        File cache = new File(context.getCacheDir(), "douai-tts");
        if (!cache.isDirectory() && !cache.mkdirs()) {
            complete(null, "无法创建语音缓存");
            return;
        }
        File[] files = cache.listFiles();
        if (files != null) {
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            for (File file : files) {
                if (file.isFile() && file.getName().endsWith(".wav") && file.lastModified() < cutoff) {
                    try { java.nio.file.Files.deleteIfExists(file.toPath()); } catch (Exception ignored) { }
                }
            }
        }
        output = new File(cache, UUID.randomUUID() + ".wav");
        String configured = options.text("tts_engine", "").trim();
        operation.stage("engine_initializing", "engine_ref=" + DouAiLog.reference(configured) + "; text_chars=" + text.length());
        try {
            engine = new TextToSpeech(context, status -> main.post(operation.wrap(() -> initialize(status, text, options))),
                    configured.isEmpty() ? null : configured);
            main.postDelayed(() -> complete(null, "本地语音生成超时"), 45_000);
        } catch (RuntimeException failure) {
            operation.error("engine_initialize", failure);
            complete(null, "无法初始化本地语音引擎");
        }
    }

    private void initialize(int status, String text, LocalOptions options) {
        try { initializeEngine(status, text, options); }
        catch (RuntimeException failure) { complete(null, "本地语音引擎初始化失败"); }
    }

    private void initializeEngine(int status, String text, LocalOptions options) {
        TextToSpeech current = engine;
        if (finished.get() || current == null) return;
        if (status != TextToSpeech.SUCCESS) {
            complete(null, "本地语音引擎不可用");
            return;
        }
        Locale language = Locale.forLanguageTag(options.text("tts_language", "zh-CN"));
        String preferredVoiceName = options.text(SpeechSettings.LOCAL_VOICE_NAME, "").trim();
        Set<Voice> installed = current.getVoices();
        boolean randomVoice = SpeechSettings.RANDOM_CURRENT.equals(new SpeechSettings(options).randomMode);
        OfflineVoiceSelection randomSelection = randomVoice
                ? selectOfflineVoice(installed, language, new Random()) : null;
        Voice selected = randomSelection == null ? null : randomSelection.voice;
        Voice languageMatch = null;
        if (selected == null && installed != null) {
            for (Voice voice : installed) {
                if (voice.isNetworkConnectionRequired() || voice.getLocale() == null
                        || !voice.getLocale().getLanguage().equals(language.getLanguage())) continue;
                if (languageMatch == null || voice.getLocale().equals(language)) languageMatch = voice;
                if (!preferredVoiceName.isEmpty() && preferredVoiceName.equals(voice.getName())
                        && voice.getLocale().equals(language)) {
                    selected = voice;
                    break;
                }
            }
        }
        if (selected == null) selected = languageMatch;
        if (selected == null) {
            if (randomVoice) DouAiLog.event("speech_synthesis", "random_voice_local_resolved", "no_valid_voices",
                    "source=local; voices=" + randomSelection.count + "; voice_ref=none; set_voice=not_attempted");
            complete(null, "请先在系统语音引擎中安装离线中文语音");
            return;
        }
        int setVoiceResult = current.setVoice(selected);
        if (randomVoice) DouAiLog.event("speech_synthesis", "random_voice_local_resolved", "set_voice_attempted",
                "source=local; voices=" + randomSelection.count + "; voice_ref=" + DouAiLog.reference(selected.getName())
                        + "; set_voice=" + (setVoiceResult == TextToSpeech.SUCCESS ? "accepted" : "rejected"));
        if (randomVoice) RandomSpeechVoice.logVoiceId(SpeechSettings.LOCAL, selected.getName());
        operation.stage("engine_ready", "offline_voice_selected=true; language=" + language.getLanguage());
        SpeechSettings settings = new SpeechSettings(options);
        current.setSpeechRate(settings.rate);
        current.setPitch(settings.pitch);
        String id = UUID.randomUUID().toString();
        current.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override public void onStart(String utteranceId) { if (id.equals(utteranceId)) operation.stage("engine_started", "business_result=pending"); }
            @Override public void onDone(String utteranceId) {
                if (!id.equals(utteranceId) || finished.get()) {
                    operation.stage("late_or_foreign_engine_callback", "ignored=true"); return;
                }
                MediaMetadataRetriever metadata = new MediaMetadataRetriever();
                try {
                    if (!output.isFile() || output.length() == 0) throw new IllegalStateException("Empty synthesis output");
                    metadata.setDataSource(output.getAbsolutePath());
                    long duration = Long.parseLong(metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION));
                    if (duration <= 0) throw new IllegalStateException("Invalid synthesis duration");
                    complete(new Result(output, duration), null);
                } catch (Exception failure) {
                    operation.error("audio_metadata", failure);
                    complete(null, "生成的语音文件无效");
                } finally {
                    try { metadata.release(); } catch (Exception ignored) { }
                }
            }
            @Override public void onError(String utteranceId) { if (id.equals(utteranceId)) complete(null, "本地语音生成失败"); }
            @Override public void onError(String utteranceId, int errorCode) { onError(utteranceId); }
        });
        if (current.synthesizeToFile(text, new Bundle(), output, id) != TextToSpeech.SUCCESS) {
            complete(null, "语音引擎拒绝了生成请求");
        } else operation.stage("engine_request_accepted", "business_result=pending");
    }

    static Voice randomOfflineVoice(Set<Voice> installed, Locale language, Random random) {
        return selectOfflineVoice(installed, language, random).voice;
    }

    private static final class OfflineVoiceSelection {
        final Voice voice;
        final int count;
        OfflineVoiceSelection(Voice voice, int count) { this.voice = voice; this.count = count; }
    }

    private static OfflineVoiceSelection selectOfflineVoice(Set<Voice> installed, Locale language, Random random) {
        List<Voice> exact = new ArrayList<>();
        List<Voice> matchingLanguage = new ArrayList<>();
        if (installed != null) for (Voice voice : installed) {
            if (voice == null || voice.isNetworkConnectionRequired() || voice.getLocale() == null
                    || !language.getLanguage().equals(voice.getLocale().getLanguage())) continue;
            matchingLanguage.add(voice);
            if (language.equals(voice.getLocale())) exact.add(voice);
        }
        List<Voice> eligible = exact.isEmpty() ? matchingLanguage : exact;
        return new OfflineVoiceSelection(eligible.isEmpty() ? null : eligible.get(random.nextInt(eligible.size())), eligible.size());
    }

    /** Short-lived inventory check for global provider selection; no audio is generated. */
    static final class OfflineVoiceProbe implements AutoCloseable {
        private final Handler main = new Handler(Looper.getMainLooper());
        private final CountDownLatch ready = new CountDownLatch(1);
        private final AtomicBoolean closed = new AtomicBoolean();
        private final Runnable launch;
        private volatile TextToSpeech engine;
        private volatile boolean eligible;

        OfflineVoiceProbe(Context context, LocalOptions options) {
            String configured = options.text("tts_engine", "").trim();
            Locale language = Locale.forLanguageTag(options.text("tts_language", "zh-CN"));
            launch = () -> {
                if (closed.get()) return;
                try {
                    engine = new TextToSpeech(context,
                            status -> main.post(() -> initialized(status, language)),
                            configured.isEmpty() ? null : configured);
                    if (closed.get()) shutdownEngine();
                } catch (RuntimeException ignored) {
                    ready.countDown();
                }
            };
            main.post(launch);
        }

        boolean await() throws InterruptedException {
            try {
                return ready.await(5, TimeUnit.SECONDS) && !closed.get() && eligible;
            } finally {
                close();
            }
        }

        private void initialized(int status, Locale language) {
            if (closed.get()) return;
            try {
                TextToSpeech current = engine;
                eligible = status == TextToSpeech.SUCCESS && current != null
                        && randomOfflineVoice(current.getVoices(), language, new Random()) != null;
            } catch (RuntimeException ignored) {
                eligible = false;
            } finally {
                shutdownEngine();
                ready.countDown();
            }
        }

        private void shutdownEngine() {
            TextToSpeech current = engine;
            engine = null;
            if (current != null) {
                try { current.shutdown(); } catch (RuntimeException ignored) { }
            }
        }

        @Override public void close() {
            if (!closed.compareAndSet(false, true)) return;
            ready.countDown();
            main.removeCallbacks(launch);
            main.post(this::shutdownEngine);
        }
    }

    private void complete(Result result, String failure) {
        if (!finished.compareAndSet(false, true)) {
            operation.stage("duplicate_or_late_completion", "ignored=true"); return;
        }
        main.post(operation.wrap(() -> {
            main.removeCallbacksAndMessages(null);
            TextToSpeech current = engine;
            engine = null;
            if (current != null) {
                try { current.stop(); current.shutdown(); } catch (RuntimeException ignored) { }
            }
            if (result == null && output != null) {
                try { java.nio.file.Files.deleteIfExists(output.toPath()); } catch (Exception ignored) { }
            }
            operation.finish(result != null ? DouAiLog.Outcome.SUCCESS : SpeechSynthesis.outcome(failure),
                    result != null ? "validated_offline_audio" : SpeechSynthesis.reason(failure),
                    result == null ? "audio_ready=false" : "duration_ms=" + result.durationMillis + "; bytes=" + result.file.length()
                            + "; output_ref=" + DouAiLog.reference(result.file.getAbsolutePath()) + "; sent=false");
            callback.accept(result, failure);
        }));
    }

    @Override public void close() { complete(null, "语音生成已停止"); }
}
