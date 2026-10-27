package com.douai.module.feature;

import com.douai.module.config.LocalOptions;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.util.Collections;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiConsumer;

import static org.junit.Assert.*;

public final class SpeechPreparationTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();
    private final LocalOptions options = new LocalOptions(Collections.emptyMap());

    @Test public void resultDeliveredAfterCancellationIsDeletedByTheCallbackOwner() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    callback.set(completed);
                    valid.set(false);
                    return () -> { };
                }, (source, context) -> { throw new AssertionError("Cancelled request must not encode"); }));
        File late = temporary.newFile("late.wav");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 1000), null);
        assertFalse(late.exists());
    }

    @Test public void synchronousCloseCallbackIsDeletedAfterPreparationHasStopped() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        File late = temporary.newFile("close.wav");
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    valid.set(false);
                    return () -> completed.accept(new LocalSpeechSynthesis.Result(late, 1000), null);
                }, (source, context) -> { throw new AssertionError("Cancelled request must not encode"); }));
        assertFalse(late.exists());
    }

    @Test public void cancellationDuringEncodingDeletesBothSourceAndEncodedFiles() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        assertThrows(CancellationException.class, () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                valid::get, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 1000), null);
                    return () -> { };
                }, (input, context) -> {
                    assertEquals(source, input);
                    valid.set(false);
                    return new DirectMessageSpeechHook.AudioFile(encoded, 1000);
                }));
        assertFalse(source.exists());
        assertFalse(encoded.exists());
    }

    @Test public void successfulPreparationTransfersOnlyTheEncodedFileToTheSender() throws Exception {
        File source = temporary.newFile("source.wav");
        File encoded = temporary.newFile("encoded.m4a");
        SpeechPreparation.Audio audio = SpeechPreparation.prepare(null, "hello", options, 30_000,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 1000), null);
                    return () -> { };
                }, (input, context) -> new DirectMessageSpeechHook.AudioFile(encoded, 950));
        assertFalse(source.exists());
        assertTrue(encoded.exists());
        assertEquals(encoded, audio.file);
        assertEquals(950, audio.duration);
        audio.delete();
        assertFalse(encoded.exists());
    }

    @Test public void overlongSourceIsRemovedBeforeEncoding() throws Exception {
        File source = temporary.newFile("source.wav");
        SpeechPreparation.Failure failure = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepare(null, "hello", options, 30_000,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 30_001), null);
                    return () -> { };
                }, (input, context) -> { throw new AssertionError("Overlong request must not encode"); }));
        assertTrue(failure.diagnostic().contains("reason=duration_limit"));
        assertTrue(failure.diagnostic().contains("sourceMillis=30001"));
        assertFalse(source.exists());
    }

    @Test public void unlimitedCommentKeepsTheFullSourceAndEncodedDuration() throws Exception {
        File source = temporary.newFile("long-source.wav");
        File encoded = temporary.newFile("long-encoded.m4a");
        SpeechPreparation.Audio audio = SpeechPreparation.prepare(null, "hello", options, Long.MAX_VALUE,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 180_000), null);
                    return () -> { };
                }, (input, context) -> new DirectMessageSpeechHook.AudioFile(encoded, 180_010));
        assertFalse(source.exists());
        assertEquals(180_010, audio.duration);
        assertTrue(encoded.exists());
        audio.delete();
    }

    @Test public void commentTransfersCapturedLongAudioWithoutAssumingRecorderLimitIsAnUploadLimit() throws Exception {
        File source = temporary.newFile("captured-33516ms.mp3");
        SpeechPreparation.Audio audio = SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE,
                () -> true, (context, text, settings, completed) -> {
                    completed.accept(new LocalSpeechSynthesis.Result(source, 33_516), null);
                    return () -> { };
                });
        assertEquals(33_516, audio.duration);
        assertEquals(source, audio.file);
        assertTrue(source.exists());
        audio.delete();
    }

    @Test public void commentTransfersValidOriginalFileWithoutImEncodingOrDurationClipping() throws Exception {
        File source = temporary.newFile("comment.mp3");
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        SpeechPreparation.Audio audio = SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE,
                () -> true, (context, text, settings, completed) -> {
                    callback.set(completed);
                    completed.accept(new LocalSpeechSynthesis.Result(source, 28_260), null);
                    return () -> completed.accept(new LocalSpeechSynthesis.Result(source, 28_260), null);
                });
        assertEquals(source, audio.file);
        assertEquals(28_260, audio.duration);
        assertTrue(source.exists());
        callback.get().accept(new LocalSpeechSynthesis.Result(source, 28_260), null);
        assertTrue(source.exists());
        File late = temporary.newFile("unexpected.mp3");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 1000), null);
        assertFalse(late.exists());
        audio.delete();
        assertFalse(source.exists());
    }

    @Test public void commentCancellationDeletesLateSynthesisAndNeverTransfersIt() throws Exception {
        AtomicBoolean valid = new AtomicBoolean(true);
        AtomicReference<BiConsumer<LocalSpeechSynthesis.Result, String>> callback = new AtomicReference<>();
        assertThrows(CancellationException.class,
                () -> SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE, valid::get,
                        (context, text, settings, completed) -> {
                            callback.set(completed);
                            valid.set(false);
                            return () -> { };
                        }));
        File late = temporary.newFile("late-comment.mp3");
        callback.get().accept(new LocalSpeechSynthesis.Result(late, 180_001), null);
        assertFalse(late.exists());
    }

    @Test public void speechDiagnosticsIdentifyTheStageWithoutProviderTextOrCredentials() throws Exception {
        SpeechPreparation.Failure synthesis = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepareComment(null, "hello", options, Long.MAX_VALUE, () -> true,
                        (context, text, settings, completed) -> {
                            completed.accept(null, "secret-text https://provider/?key=private-key");
                            return () -> { };
                        }));
        assertTrue(synthesis.diagnostic().contains("stage=synthesis"));
        assertFalse(synthesis.diagnostic().contains("secret"));
        assertFalse(synthesis.diagnostic().contains("provider/"));
        assertFalse(synthesis.diagnostic().contains("private-key"));
        File source = temporary.newFile("pcm-source.wav");
        SpeechPreparation.Failure encoding = assertThrows(SpeechPreparation.Failure.class,
                () -> SpeechPreparation.prepare(null, "hello", options, 120_000, () -> true,
                        (context, text, settings, completed) -> {
                            completed.accept(new LocalSpeechSynthesis.Result(source, 25_000), null);
                            return () -> { };
                        }, (input, context) -> { throw new IllegalArgumentException("解码后的 PCM 格式不受支持"); }));
        assertTrue(encoding.diagnostic().contains("stage=encoding"));
        assertTrue(encoding.diagnostic().contains("reason=unsupported_pcm"));
        assertTrue(encoding.diagnostic().contains("sourceMillis=25000"));
        assertFalse(source.exists());
    }

    @Test public void fishDiagnosticsKeepNumericStatusAndControlledFailureKinds() {
        SpeechPreparation.Failure rate = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Fish Audio 语音合成返回 HTTP 429，并发或容量受限，请稍后再试"));
        assertEquals(429, rate.httpCode()); assertTrue(rate.diagnostic().contains("reason=http_429"));
        assertFalse(rate.diagnostic().contains("请稍后"));
        SpeechPreparation.Failure timeout = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Fish Audio 语音生成超时"));
        assertTrue(timeout.diagnostic().contains("reason=timeout"));
        SpeechPreparation.Failure incomplete = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Fish Audio 语音响应不完整"));
        assertTrue(incomplete.diagnostic().contains("reason=incomplete_audio"));
    }

    @Test public void breezeDiagnosticsKeepNumericStatusAndControlledFailureKinds() {
        SpeechPreparation.Failure rate = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Breeze Blue 语音合成返回 HTTP 429，并发或容量受限，请稍后再试"));
        assertEquals(429, rate.httpCode()); assertTrue(rate.diagnostic().contains("reason=http_429"));
        assertFalse(rate.diagnostic().contains("请稍后"));
        SpeechPreparation.Failure timeout = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Breeze Blue 语音生成超时"));
        assertTrue(timeout.diagnostic().contains("reason=timeout"));
        SpeechPreparation.Failure incomplete = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("Breeze Blue 语音响应不完整"));
        assertTrue(incomplete.diagnostic().contains("reason=incomplete_audio"));
    }

    @Test public void providerAndHttpDiagnosticCodesAreNumericWithoutKeepingResponseText() {
        SpeechPreparation.Failure limited = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalArgumentException("MiniMax 语音接口返回错误码 1002，private-text https://provider/?key=private-key"));
        assertEquals(1002, limited.providerCode());
        assertEquals(-1, limited.httpCode());
        assertTrue(limited.diagnostic().contains("providerCode=1002"));
        assertFalse(limited.diagnostic().contains("private"));
        assertFalse(limited.diagnostic().contains("provider/"));
        assertFalse(limited.durationLimited());
        SpeechPreparation.Failure unavailable = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalStateException("MiniMax 语音 POST 返回 HTTP 429"));
        assertEquals(429, unavailable.httpCode());
        assertEquals(-1, unavailable.providerCode());
        assertTrue(unavailable.diagnostic().contains("httpCode=429"));
        SpeechPreparation.Failure arbitrary = new SpeechPreparation.Failure("synthesis", 0, 0,
                new IllegalStateException("provider response 1002 private-key HTTP 429"));
        assertEquals(-1, arbitrary.httpCode());
        assertEquals(-1, arbitrary.providerCode());
        assertFalse(arbitrary.diagnostic().contains("private"));
    }
}
