package com.douai.module.feature;

import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeCommentPublisherResponseTest {
    @Test public void navigationReceiptRequiresTheSameVerifiedWorkAndReplyTargetWithoutChangingStatus() {
        Response response = new Response();
        response.comment = new Comment();
        response.comment.cid = "published";
        response.comment.awemeId = "origin";
        assertEquals("published", NativeCommentPublisher.publishedCommentId(response, "origin", null, null));
        response.comment.awemeId = "";
        assertEquals("sent", NativeCommentPublisher.responseStatus(response, "origin", null, null));
        assertEquals("", NativeCommentPublisher.publishedCommentId(response, "origin", null, null));
        response.comment.awemeId = "other";
        assertEquals("", NativeCommentPublisher.publishedCommentId(response, "origin", null, null));
        response.comment.awemeId = "origin";
        response.comment.replyId = "root";
        response.comment.replyToReplyId = "incoming";
        assertEquals("published", NativeCommentPublisher.publishedCommentId(response, "origin", "root", "incoming"));
        response.comment.replyToReplyId = "another";
        assertEquals("", NativeCommentPublisher.publishedCommentId(response, "origin", "root", "incoming"));
        response.comment.replyToReplyId = "incoming";
        response.status_code = 1;
        assertEquals("", NativeCommentPublisher.publishedCommentId(response, "origin", "root", "incoming"));
    }
    @Test public void successRequiresStatusAndARealCommentIdForTheOriginalWork() {
        Response response = new Response();
        response.comment = new Comment();
        response.comment.cid = "published";
        response.comment.awemeId = "origin";
        assertTrue(NativeCommentPublisher.validResponse(response, "origin"));
        response.status_code = 1;
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.status_code = 0;
        response.comment.awemeId = "another";
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.comment.awemeId = "origin";
        response.comment.cid = "";
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        response.comment = null;
        assertFalse(NativeCommentPublisher.validResponse(response, "origin"));
        assertFalse(NativeCommentPublisher.validResponse(null, "origin"));
    }

    @Test public void callbackDiagnosticsKeepNumericCodesAndExcludeResponseContent() {
        Response response = new Response();
        response.status_code = 42;
        assertEquals("; response=Response; code=42", NativeCommentPublisher.callbackDiagnostic(response));
        assertEquals("; response=CodedFailure; code=413",
                NativeCommentPublisher.callbackDiagnostic(new CodedFailure("private-key private-text")));
        assertEquals("; response=Exception", NativeCommentPublisher.callbackDiagnostic(new Exception("private-key")));
    }

    @Test public void obfuscatedApiServerExceptionKeepsItsInheritedNumericCodeWithoutPayload() {
        // 40.6.0 ApiServerException inherits errorCode:I and LIZ()I from X/1KNh,
        // rather than exposing the unobfuscated getErrorCode() used by older fixtures.
        ApiServerFailure failure = new ApiServerFailure(12345);
        assertEquals("; response=ApiServerFailure; code=12345", NativeCommentPublisher.callbackDiagnostic(failure));
    }

    @Test public void audioReceiptIsUnknownWhenTheReflectedMediaContractIsNotConfirmed() {
        assertEquals("; audio_receipt=unknown", NativeCommentPublisher.audioReceiptDiagnostic(null));
        assertEquals("; audio_receipt=unknown", NativeCommentPublisher.audioReceiptDiagnostic(new Response()));
        assertEquals("; audio_receipt=unknown", NativeCommentPublisher.audioReceiptDiagnostic(new UnverifiedAudioResponse()));
        assertEquals("; audio_receipt=unknown", NativeCommentPublisher.audioReceiptDiagnostic(new WrongDurationResponse()));
    }

    @Test public void audioHintTypeResolutionLinkageIsUnknownWithoutInvokingTheGetter() throws Exception {
        Class<?> source = com.ss.android.ugc.aweme.base.api.exceptions.server.ApiServerException.class;
        Class<?> isolated = isolatedDiagnosticFixture(source, source.getName() + "$OptionalDiagnosticType");
        Object failure = isolated.getConstructor(int.class, String.class, String.class).newInstance(5, "时长 private-hint", "");
        // getDeclaredMethod resolves the unrelated missing return type before any hint getter runs.
        assertThrows(NoClassDefFoundError.class, () -> isolated.getDeclaredMethod("LIZLLL"));
        java.lang.reflect.Method hint = NativeCommentPublisher.class.getDeclaredMethod("audioFailureHintDiagnostic", Object.class);
        hint.setAccessible(true);
        assertEquals("; audio_hint=unknown", hint.invoke(null, failure));
    }

    static Class<?> isolatedDiagnosticFixture(Class<?> source, String absentName) throws Exception {
        byte[] definition;
        try (java.io.InputStream input = source.getClassLoader().getResourceAsStream(source.getName().replace('.', '/') + ".class")) {
            assertNotNull(input);
            definition = input.readAllBytes();
        }
        ClassLoader loader = new ClassLoader(source.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (absentName.equals(name)) throw new ClassNotFoundException("Optional diagnostic type unavailable");
                if (!source.getName().equals(name)) return super.loadClass(name, resolve);
                synchronized (this) {
                    Class<?> result = findLoadedClass(name);
                    if (result == null) result = defineClass(name, definition, 0, definition.length);
                    if (resolve) resolveClass(result);
                    return result;
                }
            }
        };
        return Class.forName(source.getName(), true, loader);
    }

    @Test public void repliesRequireTheCorrectWorkAndRootWithAnExactNestedTarget() {
        Response response = new Response();
        response.comment = new Comment();
        response.comment.cid = "published";
        response.comment.awemeId = "origin";
        response.comment.replyId = "root";
        response.comment.replyToReplyId = "incoming";
        assertTrue(NativeCommentPublisher.validReplyResponse(response, "origin", "root", "incoming"));
        assertEquals("sent", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
        response.comment.replyToReplyId = "other-target";
        assertEquals("submitted", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
        response.comment.replyToReplyId = null;
        assertEquals("submitted", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
        response.comment.replyToReplyId = "incoming";
        response.comment.replyId = "another-root";
        assertEquals("submitted", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
        response.comment.replyId = "root";
        response.comment.awemeId = "";
        assertTrue(NativeCommentPublisher.validResponse(response, "origin"));
        assertEquals("submitted", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
        response.comment.awemeId = "origin";
        response.status_code = 42;
        assertEquals("failed", NativeCommentPublisher.responseStatus(response, "origin", "root", "incoming"));
    }

    @Test public void aReplyToTheRootDoesNotRequireANestedReplyIdButStillRequiresItsRoot() {
        Response response = new Response();
        response.comment = new Comment();
        response.comment.cid = "published";
        response.comment.awemeId = "origin";
        response.comment.replyId = "root";
        assertTrue(NativeCommentPublisher.validReplyResponse(response, "origin", "root", "root"));
        response.comment.replyId = null;
        assertEquals("submitted", NativeCommentPublisher.responseStatus(response, "origin", "root", "root"));
        assertEquals("submitted", NativeCommentPublisher.responseStatus(null, "origin", "root", "root"));
    }

    public static final class CodedFailure extends Exception {
        CodedFailure(String message) { super(message); }
        public int getErrorCode() { return 413; }
    }

    public static class ObfuscatedApiFailure extends Exception {
        public final int errorCode;
        ObfuscatedApiFailure(int code) { super("private-log-id private-text"); errorCode = code; }
        public final int LIZ() { return errorCode; }
    }
    public static final class ApiServerFailure extends ObfuscatedApiFailure {
        public final String mErrorMsg = "private-text";
        public final String mUrl = "https://provider/?key=private-key";
        ApiServerFailure(int code) { super(code); }
    }
    public static final class UnverifiedAudioResponse { public UnverifiedAudioComment comment = new UnverifiedAudioComment(); }
    public static final class UnverifiedAudioComment { public Object commentAudio = new Object(); }
    public static final class WrongDurationResponse { public WrongDurationComment comment = new WrongDurationComment(); }
    public static final class WrongDurationComment { public WrongDurationAudio commentAudio = new WrongDurationAudio(); }
    public static final class WrongDurationAudio { public String a = "private-vid"; public String e = "private-text"; }

    public static final class Response { public int status_code; public Comment comment; }
    public static final class Comment { public String cid; public String awemeId; public String replyId; public String replyToReplyId; }
}
