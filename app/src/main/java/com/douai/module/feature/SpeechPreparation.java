package com.douai.module.feature;

import android.content.Context;

import com.douai.module.config.LocalOptions;
import com.douai.module.diagnostics.DouAiLog;

import java.io.File;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;

/** Prepares owned audio files before automation's send acknowledgment timer starts. */
final class SpeechPreparation {
    // The host's native comment entry records 1-30 second clips. IM uses its own caller limit.
    interface Preparer {
        Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                      BooleanSupplier valid) throws Exception;
    }
    interface Synthesizer {
        AutoCloseable synthesize(Context context, String text, LocalOptions options,
                                 BiConsumer<LocalSpeechSynthesis.Result, String> callback);
    }
    interface Encoder {
        DirectMessageSpeechHook.AudioFile encode(File source, Context context) throws Exception;
    }
    static final class Audio {
        final File file;
        final long duration;
        Audio(File file, long duration) { this.file = file; this.duration = duration; }
        void delete() { file.delete(); }
    }
    private SpeechPreparation() { }

    static Audio prepareComment(Context context, String text, LocalOptions options, long maximumDuration,
                                BooleanSupplier valid) throws Exception {
        return prepareComment(context, text, options, maximumDuration, valid, SpeechSynthesis::synthesize);
    }

    static Audio prepareComment(Context context, String text, LocalOptions options, long maximumDuration,
                                BooleanSupplier valid, Synthesizer synthesizer) throws Exception {
        // Comment upload accepts the synthesis file; AAC encoding belongs to native IM.
        return prepare(context, text, options, maximumDuration,
                valid, synthesizer, null);
    }

    static Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                         BooleanSupplier valid) throws Exception {
        return prepare(context, text, options, maximumDuration, valid,
                SpeechSynthesis::synthesize, DirectMessageSpeechHook::encode);
    }

    static Audio prepare(Context context, String text, LocalOptions options, long maximumDuration,
                         BooleanSupplier valid, Synthesizer synthesizer, Encoder encoder) throws Exception {
        DouAiLog.Operation operation = DouAiLog.begin("speech_preparation", encoder == null ? "comment_audio" : "im_audio");
        if (!valid.getAsBoolean()) {
            operation.finish(DouAiLog.Outcome.CANCELLED, "owner_invalid", "synthesis_started=false");
            throw new CancellationException("语音任务已取消");
        }
        CountDownLatch ready = new CountDownLatch(1);
        Completion completion = new Completion(ready, operation);
        AutoCloseable request = null;
        DirectMessageSpeechHook.AudioFile encoded = null;
        boolean delivered = false;
        String stage = "synthesis";
        long sourceDuration = 0;
        try {
            operation.stage("synthesis", "maximum_duration_ms=" + maximumDuration + "; text_chars=" + (text == null ? 0 : text.length()));
            AutoCloseable[] started = new AutoCloseable[1];
            operation.wrap(() -> started[0] = synthesizer.synthesize(context, text, options, completion::complete)).run();
            request = started[0];
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(70);
            while (!ready.await(100, TimeUnit.MILLISECONDS)) {
                if (!valid.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new CancellationException("语音任务已取消");
                if (System.nanoTime() >= deadline) throw new IllegalStateException("语音生成超时");
            }
            if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
            LocalSpeechSynthesis.Result audio = completion.result();
            if (audio == null) throw new IllegalStateException(completion.failure());
            sourceDuration = audio.durationMillis;
            if (sourceDuration <= 0) throw new IllegalArgumentException("生成的语音文件无效");
            if (audio.durationMillis > maximumDuration) throw new IllegalArgumentException("语音超过当前发送入口的时长限制");
            operation.stage("source_validated", "source_duration_ms=" + sourceDuration + "; maximum_duration_ms=" + maximumDuration);
            if (encoder == null) {
                if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
                completion.transfer();
                operation.finish(DouAiLog.Outcome.SUCCESS, "comment_audio_prepared", "duration_ms=" + audio.durationMillis
                        + "; output_ref=" + DouAiLog.reference(audio.file.getAbsolutePath()) + "; sent=false");
                return new Audio(audio.file, audio.durationMillis);
            }
            stage = "encoding";
            operation.stage("encoding", "source_duration_ms=" + sourceDuration);
            encoded = encoder.encode(audio.file, context);
            if (!valid.getAsBoolean()) throw new CancellationException("语音任务已取消");
            if (encoded.durationMillis <= 0) throw new IllegalArgumentException("生成的语音文件无效");
            if (encoded.durationMillis > maximumDuration) throw new IllegalArgumentException("语音超过当前发送入口的时长限制");
            delivered = true;
            operation.finish(DouAiLog.Outcome.SUCCESS, "im_audio_prepared", "encoded_duration_ms=" + encoded.durationMillis
                    + "; output_ref=" + DouAiLog.reference(encoded.file.getAbsolutePath()) + "; sent=false");
            return new Audio(encoded.file, encoded.durationMillis);
        } catch (CancellationException cancelled) {
            operation.finish(DouAiLog.Outcome.CANCELLED, "owner_cancelled", "stage=" + stage + "; sent=false");
            throw cancelled;
        } catch (Exception failure) {
            Failure wrapped = new Failure(stage, sourceDuration, encoded == null ? 0 : encoded.durationMillis, failure);
            operation.error(stage, failure);
            operation.finish("timeout".equals(wrapped.reason) || "encoding_timeout".equals(wrapped.reason) || wrapped.providerCode == 1001
                            ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED,
                    wrapped.reason, wrapped.diagnostic() + "; sent=false");
            throw wrapped;
        } finally {
            // A callback can arrive after cancellation; close the result owner before the request.
            completion.close();
            if (!delivered && encoded != null) encoded.file.delete();
            if (request != null) request.close();
        }
    }

    static final class Failure extends Exception {
        private final String diagnostic;
        private final String reason;
        private final long sourceDurationMillis;
        private final int providerCode;
        private final int httpCode;

        Failure(String stage, long sourceDuration, long encodedDuration, Exception cause) {
            super("语音准备失败", cause);
            sourceDurationMillis = Math.max(0, sourceDuration);
            providerCode = code(cause.getMessage(), "^MiniMax 语音接口返回错误码 ([0-9]{1,9})(?:[^0-9].*)?$", 1);
            int previousHttp = code(cause.getMessage(), "^(?:第三方语音 GET|MiniMax 语音(?: POST|音频下载)?) ?返回 HTTP ([1-5][0-9]{2})$", 1);
            httpCode = previousHttp >= 0 ? previousHttp : code(cause.getMessage(),
                    "^(?:Breeze Blue|Fish Audio) 语音(?:合成| POST)? ?返回 HTTP ([1-5][0-9]{2})(?:，.*)?$", 1);
            reason = httpCode < 0 ? reason(cause.getMessage()) : "http_" + httpCode;
            diagnostic = "stage=" + stage + "; reason=" + reason
                    + "; sourceMillis=" + sourceDuration + "; encodedMillis=" + encodedDuration
                    + "; error=" + cause.getClass().getSimpleName()
                    + (providerCode < 0 ? "" : "; providerCode=" + providerCode)
                    + (httpCode < 0 ? "" : "; httpCode=" + httpCode);
        }

        String diagnostic() { return diagnostic; }
        boolean durationLimited() { return "duration_limit".equals(reason); }
        long sourceDurationMillis() { return sourceDurationMillis; }
        int providerCode() { return providerCode; }
        int httpCode() { return httpCode; }

        private static int code(String message, String pattern, int group) {
            if (message == null) return -1;
            java.util.regex.Matcher found = java.util.regex.Pattern.compile(pattern).matcher(message);
            return found.matches() ? Integer.parseInt(found.group(group)) : -1;
        }

        private static String reason(String message) {
            if (message == null) return "unknown";
            if (message.matches("(?:第三方语音 GET|MiniMax 语音 POST) 返回 HTTP [0-9]{3}")) {
                return "http_" + message.substring(message.length() - 3);
            }
            if (message.startsWith("MiniMax 语音接口返回错误码 ")) return "provider_api";
            switch (message) {
                case "语音生成超时": case "第三方语音生成超时":
                case "第三方语音 GET 请求超时": case "本地语音生成超时":
                case "MiniMax 语音生成超时": case "MiniMax 语音 POST 请求超时":
                case "Breeze Blue 语音生成超时": case "Breeze Blue 语音合成请求超时":
                case "Fish Audio 语音生成超时": case "Fish Audio 语音合成请求超时": return "timeout";
                case "生成的语音文件无效": return "invalid_audio";
                case "语音内容为空或过长": return "engine_text_limit";
                case "语音超过当前发送入口的时长限制": return "duration_limit";
                case "解码后的 PCM 格式不受支持": return "unsupported_pcm";
                case "PCM 缓冲区大小不受支持": return "pcm_buffer";
                case "音频采样格式不受支持": return "unsupported_sample_format";
                case "文件没有音轨": return "missing_audio_track";
                case "语音转码超时或已停止": return "encoding_timeout";
                case "语音引擎拒绝了生成请求": return "engine_rejected";
                case "第三方语音响应未包含有效音频链接":
                case "第三方语音响应未包含音频链接":
                case "所选语音字段应包含一个音频链接":
                case "返回了多个语音链接，请选择音频字段":
                case "返回了多个链接，请选择音频字段": return "audio_field";
                case "第三方语音链接未返回音频文件":
                case "Breeze Blue 语音接口未返回完整音频文件":
                case "Fish Audio 语音接口未返回完整音频文件": return "not_audio";
                case "Breeze Blue 语音响应不完整": case "Fish Audio 语音响应不完整": return "incomplete_audio";
                case "Breeze Blue 语音合成文本超过接口最高 2000 字符限制": return "provider_text_limit";
                case "第三方语音 GET 请求失败": return "network";
                default: return "provider_or_audio_failure";
            }
        }
    }

    private static final class Completion {
        private final CountDownLatch ready;
        private final DouAiLog.Operation operation;
        private boolean closed;
        private boolean completed;
        private boolean transferred;
        private LocalSpeechSynthesis.Result result;
        private String failure;

        Completion(CountDownLatch ready, DouAiLog.Operation operation) { this.ready = ready; this.operation = operation; }

        synchronized void complete(LocalSpeechSynthesis.Result audio, String error) {
            if (closed || completed) {
                operation.stage("late_or_duplicate_synthesis_callback", "ignored=true; owner_closed=" + closed);
                if (audio != null && (result == null || !audio.file.equals(result.file))) audio.file.delete();
                return;
            }
            completed = true;
            result = audio;
            failure = error;
            ready.countDown();
        }

        synchronized LocalSpeechSynthesis.Result result() { return result; }
        synchronized String failure() { return failure == null ? "语音生成失败" : failure; }
        synchronized void transfer() { transferred = true; }

        synchronized void close() {
            closed = true;
            if (result != null && !transferred) result.file.delete();
        }
    }
}
