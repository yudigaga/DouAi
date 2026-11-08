package com.douai.module.feature;

import com.douai.module.config.CommentAiSettings;
import com.douai.module.config.LocalOptions;
import com.douai.module.scan.HostAdaptation;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import org.junit.Test;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public final class CommentAiAnalysisHookTest {
    @Test public void imageRequestCanFallBackOnlyBeforeVisibleOutputAndForRequestRejections() throws Exception {
        for (int status : new int[]{400, 413, 415, 422}) {
            Exception failure = httpFailure(status);
            assertTrue(CommentAiAnalysisHook.canRetryTextOnly(failure, true, false));
            assertFalse(CommentAiAnalysisHook.canRetryTextOnly(failure, false, false));
            assertFalse(CommentAiAnalysisHook.canRetryTextOnly(failure, true, true));
        }
        for (int status : new int[]{401, 403, 408, 429, 500, 502, 503}) {
            assertFalse(CommentAiAnalysisHook.canRetryTextOnly(httpFailure(status), true, false));
        }
        assertFalse(CommentAiAnalysisHook.canRetryTextOnly(new java.net.SocketTimeoutException(), true, false));
        assertFalse(CommentAiAnalysisHook.canRetryTextOnly(new java.util.concurrent.CancellationException(), true, false));
        assertFalse(CommentAiAnalysisHook.canRetryTextOnly(new IllegalArgumentException(), true, false));
    }

    private static ChatCompletionClient.ReplyFailure httpFailure(int status) throws Exception {
        java.lang.reflect.Constructor<ChatCompletionClient.ReplyFailure> constructor =
                ChatCompletionClient.ReplyFailure.class.getDeclaredConstructor(
                        ChatCompletionClient.ReplyFailure.Kind.class, int.class, int.class);
        constructor.setAccessible(true);
        return constructor.newInstance(ChatCompletionClient.ReplyFailure.Kind.HTTP, status, -1);
    }

    @Test public void nativeSchemaGsonOverridesAPrewarmedBusinessAdapterWithoutChangingTheSharedGson() throws Exception {
        Gson global = new Gson();
        TypeAdapter<HostBusiness> sharedAdapter = global.getAdapter(HostBusiness.class);
        String json = "{\"type\":999670,\"data\":{\"card_data\":{\"query\":\"schema\"},"
                + "\"stream_data\":[{\"display\":{\"generation_spans\":[{\"type\":2,\"text\":{\"content\":\"schema\"}}]}}]}}";
        assertTrue(global.fromJson(json, HostBusiness.class).data instanceof Map);

        Gson isolated = (Gson) CommentAiAnalysisHook.createNativeSchemaGson(
                global, new HostBusinessFactory(), TypeAdapterFactory.class);
        assertNotSame(global, isolated);
        HostBusiness parsed = isolated.fromJson(json, HostBusiness.class);
        assertEquals(999670, parsed.type);
        assertTrue(parsed.data instanceof HostNativeCard);
        HostNativeCard card = (HostNativeCard) parsed.data;
        assertEquals("schema", card.cardData.query);
        assertEquals("schema", card.streamData.get(0).LIZ.LIZIZ.get(0).LJIIL.LIZ);

        assertSame(sharedAdapter, global.getAdapter(HostBusiness.class));
        assertNotSame(sharedAdapter, isolated.getAdapter(HostBusiness.class));
        assertTrue(global.fromJson(json, HostBusiness.class).data instanceof Map);
    }

    @Test public void nativeAppendPreflightAcceptsHostGsonObjectAndNestedTextContent() throws Exception {
        validateAppend("{\"display\":{\"generation_spans\":[{\"type\":2,\"text\":{\"content\":\"schema\"}}],\"is_end\":true}}");
    }

    @Test public void nativeAppendPreflightChecksNestedTextInsteadOfUnrelatedTopLevelContent() throws Exception {
        try {
            validateAppend("{\"display\":{\"generation_spans\":[{\"type\":2,\"content\":\"schema\",\"text\":{\"content\":\"wrong\"}}]}}");
            fail("The top-level content field must not hide a failed text.content decode");
        } catch (IllegalStateException expected) {
            assertEquals("The host did not decode native comment AI message text", expected.getMessage());
        }
    }

    @Test public void nativeAppendPreflightRejectsMissingDisplay() throws Exception {
        Gson gson = new Gson();
        try {
            CommentAiAnalysisHook.validateNativeAppend(new HostAppendCommand(), gson,
                    Gson.class.getMethod("fromJson", String.class, Class.class), HostMessage.class);
            fail("Missing display must preserve the host path");
        } catch (IllegalStateException expected) {
            assertEquals("The host did not decode the append display", expected.getMessage());
        }
    }

    private static void validateAppend(String json) throws Exception {
        Gson gson = new Gson();
        HostAppendCommand command = new HostAppendCommand();
        command.display = gson.fromJson(json, JsonObject.class);
        CommentAiAnalysisHook.validateNativeAppend(command, gson,
                Gson.class.getMethod("fromJson", String.class, Class.class), HostMessage.class);
    }

    private static final class HostAppendCommand { JsonObject display; }
    private static final class HostMessage { @SerializedName("display") HostDisplay LIZ; }
    private static final class HostDisplay { @SerializedName("generation_spans") List<HostSpan> LIZIZ; }
    private static final class HostSpan {
        @SerializedName("text") HostText LJIIL;
        @SerializedName("content") String LJIJI;
    }
    private static final class HostText { @SerializedName("content") String LIZ; }
    private static final class HostBusiness { int type; Object data; }
    private static final class HostNativeCard {
        @SerializedName("card_data") HostCardData cardData;
        @SerializedName("stream_data") List<HostMessage> streamData;
    }
    private static final class HostCardData { String query; }
    private static final class HostBusinessFactory implements TypeAdapterFactory {
        @SuppressWarnings("unchecked")
        @Override public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            if (type.getRawType() != HostBusiness.class) return null;
            TypeAdapter<HostNativeCard> nativeCard = gson.getAdapter(HostNativeCard.class);
            return (TypeAdapter<T>) new TypeAdapter<HostBusiness>() {
                @Override public HostBusiness read(JsonReader reader) throws IOException {
                    HostBusiness business = new HostBusiness();
                    reader.beginObject();
                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        if ("type".equals(name)) business.type = reader.nextInt();
                        else if ("data".equals(name)) business.data = nativeCard.read(reader);
                        else reader.skipValue();
                    }
                    reader.endObject();
                    return business;
                }
                @Override public void write(JsonWriter writer, HostBusiness business) throws IOException {
                    writer.beginObject();
                    writer.name("type").value(business.type);
                    writer.name("data");
                    nativeCard.write(writer, (HostNativeCard) business.data);
                    writer.endObject();
                }
            };
        }
    }

    @Test public void hostGuardUsesTheOriginalVerifiedProfileInsteadOfASpoofableDisplayVersion() {
        assertTrue(CommentAiAnalysisHook.supportsHost("com.ss.android.ugc.aweme", HostAdaptation.PROFILE_410));
        assertEquals(41009900L, HostAdaptation.PROFILE_410.updateVersionCode);
        assertFalse(CommentAiAnalysisHook.supportsHost("com.ss.android.ugc.aweme", HostAdaptation.PROFILE_408));
        assertFalse(CommentAiAnalysisHook.supportsHost("com.ss.android.ugc.aweme", HostAdaptation.PROFILE_406));
        assertFalse(CommentAiAnalysisHook.supportsHost("com.example.app", HostAdaptation.PROFILE_410));
        assertFalse(CommentAiAnalysisHook.supportsHost("com.ss.android.ugc.aweme", null));
    }
    @Test public void onlyExplicitOptInWithValidModelAndExactCommentSceneTakesOver() {
        LocalOptions enabled = new LocalOptions(Map.of(CommentAiSettings.TAKEOVER, true));
        assertTrue(CommentAiAnalysisHook.shouldTakeover(enabled, true, "comment_ai_analysis"));
        assertFalse(CommentAiAnalysisHook.shouldTakeover(enabled, false, "comment_ai_analysis"));
        assertFalse(CommentAiAnalysisHook.shouldTakeover(enabled, true, "homepage_square"));
        assertFalse(CommentAiAnalysisHook.shouldTakeover(enabled, true, "comment_ai_analysis_v2"));
        assertFalse(CommentAiAnalysisHook.shouldTakeover(enabled, true, null));
        assertFalse(CommentAiAnalysisHook.shouldTakeover(new LocalOptions(Collections.emptyMap()), true, "comment_ai_analysis"));
    }
}
