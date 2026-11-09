package com.douai.module.config;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 35, packageName = "com.douai.module")
public final class AutomationHistoryStoreTest {
    private Context context;
    private AutomationHistoryStore store;

    @Before public void reset() {
        context = RuntimeEnvironment.getApplication();
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit().clear().commit();
        store = new AutomationHistoryStore(context);
    }

    @Test public void statusUpdatesReplaceAnEntryAndRetainItsOriginalTime() {
        store.upsert(entry("a", "reply", "pending", 10, "输入", "回复"));
        store.upsert(entry("b", "reply", "sent", 20, "另一个输入", "另一个回复"));
        store.upsert(entry("a", "reply", "sent", 10, "输入", "回复"));
        List<AutomationHistoryStore.Entry> entries = new AutomationHistoryStore(context).read("reply");
        assertEquals(2, entries.size());
        assertEquals("b", entries.get(0).id);
        assertEquals("a", entries.get(1).id);
        assertEquals("sent", entries.get(1).status);
        assertEquals(10, entries.get(1).createdAt);
        assertEquals("输入", entries.get(1).input);
        assertEquals("目标用户", entries.get(1).targetName);
    }

    @Test public void repliesAndCommentsHaveSeparateRetentionAndClearControls() {
        for (int i = 0; i < 205; i++) store.upsert(entry("r" + i, "reply", "sent", i, "输入", "回复"));
        store.upsert(entry("r0", "comment", "submitted", 500, "视频内容", "评论"));
        assertEquals(200, store.read("reply").size());
        assertEquals("r204", store.read("reply").get(0).id);
        assertEquals("r5", store.read("reply").get(199).id);
        assertEquals("submitted", store.read("comment").get(0).status);
        store.clear("reply");
        assertTrue(store.read("reply").isEmpty());
        assertEquals(1, store.read("comment").size());
    }

    @Test public void replyHistoryCombinesMessagesAndCommentRepliesUnderOneRetentionAndClearControl() {
        for (int index = 0; index < 205; index++) {
            store.upsert(entry("message-" + index, AutomationHistoryStore.REPLY, "sent", index * 2L, "私信", "回复"));
            store.upsert(entry("comment-reply-" + index, AutomationHistoryStore.COMMENT_REPLY,
                    "sent", index * 2L + 1, "评论", "回复"));
        }
        store.upsert(entry("feed-comment", AutomationHistoryStore.COMMENT, "sent", 1000, "作品", "评论"));
        AutomationHistoryStore restored = new AutomationHistoryStore(context);
        assertEquals(200, restored.read(AutomationHistoryStore.REPLY).size());
        assertEquals("comment-reply-204", restored.read(AutomationHistoryStore.REPLY).get(0).id);
        assertEquals(AutomationHistoryStore.COMMENT_REPLY, restored.read(AutomationHistoryStore.REPLY).get(0).kind);
        assertEquals("message-204", restored.read(AutomationHistoryStore.REPLY).get(1).id);
        assertEquals(100, restored.read(AutomationHistoryStore.COMMENT_REPLY).size());
        restored.clear(AutomationHistoryStore.REPLY);
        assertTrue(restored.read(AutomationHistoryStore.REPLY).isEmpty());
        assertTrue(restored.read(AutomationHistoryStore.COMMENT_REPLY).isEmpty());
        assertEquals(1, restored.read(AutomationHistoryStore.COMMENT).size());
    }

    @Test public void internalCommentReplyClearKeepsMessageAndFeedCommentHistory() {
        store.upsert(entry("same-id", AutomationHistoryStore.REPLY, "sent", 10, "私信", "回复"));
        store.upsert(entry("same-id", AutomationHistoryStore.COMMENT_REPLY, "sent", 20, "评论", "回复"));
        store.upsert(entry("same-id", AutomationHistoryStore.COMMENT, "sent", 30, "作品", "评论"));
        AutomationHistoryStore restored = new AutomationHistoryStore(context);
        restored.clear(AutomationHistoryStore.COMMENT_REPLY);
        assertTrue(restored.read(AutomationHistoryStore.COMMENT_REPLY).isEmpty());
        assertEquals(1, restored.read(AutomationHistoryStore.REPLY).size());
        assertEquals(AutomationHistoryStore.REPLY, restored.read(AutomationHistoryStore.REPLY).get(0).kind);
        assertEquals(1, restored.read(AutomationHistoryStore.COMMENT).size());
    }

    @Test public void clearingParentReplyHistoryPreventsLateMessageAndCommentReplyCallbacksRestoringIt() {
        long created = System.currentTimeMillis() - 100;
        for (String kind : new String[]{AutomationHistoryStore.REPLY, AutomationHistoryStore.COMMENT_REPLY}) {
            store.upsert(entry("old", kind, "pending", created, "输入", ""));
        }
        store.upsert(entry("feed-comment", AutomationHistoryStore.COMMENT, "sent", created, "作品", "评论"));
        store.clear(AutomationHistoryStore.REPLY);
        AutomationHistoryStore restored = new AutomationHistoryStore(context);
        for (String kind : new String[]{AutomationHistoryStore.REPLY, AutomationHistoryStore.COMMENT_REPLY}) {
            restored.markModelRequestSent(kind, "old", 0);
            restored.upsert(entry("old", kind, "sent", created, "输入", "迟到回复"));
        }
        assertTrue(restored.read(AutomationHistoryStore.REPLY).isEmpty());
        assertTrue(restored.read(AutomationHistoryStore.COMMENT_REPLY).isEmpty());
        assertEquals(1, restored.read(AutomationHistoryStore.COMMENT).size());
    }

    @Test public void unknownCommentReplyStatusAndRequestMetadataSurviveRecreation() {
        store.upsert(new AutomationHistoryStore.Entry("incoming", AutomationHistoryStore.COMMENT_REPLY,
                "root", "评论者", "评论", "回复", "model", "pending", 10, 20,
                AutomationHistoryStore.DELIVERY_TEXT));
        store.markModelRequestSent(AutomationHistoryStore.COMMENT_REPLY, "incoming", 0);
        store.upsert(new AutomationHistoryStore.Entry("incoming", AutomationHistoryStore.COMMENT_REPLY,
                "root", "评论者", "评论", "回复", "model", "unknown", 10, 30,
                AutomationHistoryStore.DELIVERY_TEXT));
        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context)
                .read(AutomationHistoryStore.COMMENT_REPLY).get(0);
        assertEquals("unknown", loaded.status);
        assertTrue(loaded.modelRequestSent);
        assertEquals(0, loaded.requestImageCount);
        assertEquals(AutomationHistoryStore.DELIVERY_TEXT, loaded.deliveryMode);
        assertEquals(1, store.read(AutomationHistoryStore.REPLY).size());
        assertEquals(AutomationHistoryStore.COMMENT_REPLY, store.read(AutomationHistoryStore.REPLY).get(0).kind);
    }

    @Test public void fieldAndUtf8StorageBoundsPruneTheOldestRecords() {
        String large = "汉".repeat(10000);
        for (int i = 0; i < 70; i++) store.upsert(entry("large" + i, "reply", "sent", i, large, large));
        List<AutomationHistoryStore.Entry> entries = store.read("reply");
        assertTrue(entries.size() < 70);
        assertEquals("large69", entries.get(0).id);
        assertEquals(8000, entries.get(0).input.length());
        assertEquals(4000, entries.get(0).output.length());
        String json = context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "");
        assertTrue(json.getBytes(StandardCharsets.UTF_8).length <= 2 * 1024 * 1024);
    }

    @Test public void malformedHistoryDoesNotBreakFutureSaves() {
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .edit().putString("entries_v1", "not-json").commit();
        assertTrue(store.read("reply").isEmpty());
        store.upsert(entry("a", "reply", "cancelled", 1, "", ""));
        assertEquals("cancelled", store.read("reply").get(0).status);
    }

    @Test public void returnedHistoryCannotBeMutatedAndInvalidStatusesAreRejected() {
        store.upsert(entry("a", "reply", "failed", 1, "", ""));
        assertThrows(UnsupportedOperationException.class, () -> store.read("reply").clear());
        assertThrows(IllegalArgumentException.class, () -> store.read("unknown"));
        assertThrows(IllegalArgumentException.class, () -> entry("a", "reply", "success", 1, "", ""));
    }

    @Test public void clearedRecordCannotReappearFromAnOlderPendingCallback() {
        long created = System.currentTimeMillis() - 100;
        store.upsert(entry("old", "reply", "pending", created, "输入", "回复"));
        store.clear("reply");
        store.upsert(entry("old", "reply", "sent", created, "输入", "回复"));
        assertTrue(store.read("reply").isEmpty());
        store.upsert(entry("new", "reply", "sent", System.currentTimeMillis() + 1, "新消息", "新回复"));
        assertEquals("new", store.read("reply").get(0).id);
    }

    @Test public void legacyRecordsKeepUnknownImageRequestMetadata() throws Exception {
        JSONObject legacy = new JSONObject().put("id", "legacy").put("kind", "comment")
                .put("targetId", "target-id").put("targetName", "目标用户")
                .put("input", "作品内容").put("output", "评论内容")
                .put("modelName", "test-model").put("status", "sent")
                .put("createdAt", 10).put("updatedAt", 20);
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit()
                .putString("entries_v1", new JSONArray().put(legacy).toString()).commit();

        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(-1, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
        assertEquals(AutomationHistoryStore.DELIVERY_UNKNOWN, loaded.deliveryMode);
        assertEquals(AutomationHistoryStore.Reason.NONE, loaded.reason);
        assertEquals(AutomationHistoryStore.Stage.NONE, loaded.stage);
        assertEquals(-1, loaded.queueMillis);
        assertEquals(-1, loaded.mediaMillis);
        assertEquals(-1, loaded.generationMillis);
        assertEquals(-1, loaded.sendMillis);
        store.upsert(entry("legacy", "comment", "sent", 10, "作品内容", "评论内容"));
        loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(-1, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
        assertEquals(AutomationHistoryStore.DELIVERY_UNKNOWN, loaded.deliveryMode);
    }

    @Test public void deliveryModesPersistAndModelRequestMetadataPreservesThem() {
        for (String mode : new String[]{AutomationHistoryStore.DELIVERY_TEXT, AutomationHistoryStore.DELIVERY_VOICE}) {
            store.upsert(new AutomationHistoryStore.Entry(mode, "comment", "target-id", "目标用户",
                    "作品内容", "评论内容", "test-model", "sent", 10, 20, mode));
            store.markModelRequestSent("comment", mode, 2);
        }

        List<AutomationHistoryStore.Entry> loaded = new AutomationHistoryStore(context).read("comment");
        assertEquals(2, loaded.size());
        for (AutomationHistoryStore.Entry entry : loaded) {
            assertEquals(entry.id, entry.deliveryMode);
            assertTrue(entry.modelRequestSent);
            assertEquals(2, entry.requestImageCount);
        }
    }

    @Test public void textFallbackReplacesVoiceModeAndRetainsModelRequestMetadata() {
        store.upsert(new AutomationHistoryStore.Entry("fallback", "comment", "target-id", "目标用户",
                "作品内容", "评论内容", "test-model", "failed", 10, 20, AutomationHistoryStore.DELIVERY_VOICE));
        store.markModelRequestSent("comment", "fallback", 1);
        store.upsert(new AutomationHistoryStore.Entry("fallback", "comment", "target-id", "目标用户",
                "作品内容", "评论内容", "test-model", "sent", 10, 30, AutomationHistoryStore.DELIVERY_TEXT));

        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(AutomationHistoryStore.DELIVERY_TEXT, loaded.deliveryMode);
        assertEquals("sent", loaded.status);
        assertTrue(loaded.modelRequestSent);
        assertEquals(1, loaded.requestImageCount);
    }

    @Test public void unrecognizedDeliveryModesRemainUnknown() {
        store.upsert(new AutomationHistoryStore.Entry("unexpected", "comment", "target-id", "目标用户",
                "作品内容", "评论内容", "test-model", "sent", 10, 20, "unsupported"));

        assertEquals(AutomationHistoryStore.DELIVERY_UNKNOWN,
                new AutomationHistoryStore(context).read("comment").get(0).deliveryMode);
    }

    @Test public void sentImageAndTextOnlyRequestsPersistAcrossStoreInstances() {
        int[] imageCounts = {0, 1, 3};
        for (int imageCount : imageCounts) {
            String id = "request-" + imageCount;
            store.upsert(entry(id, "comment", "pending", imageCount + 10, "作品内容", ""));
            store.markModelRequestSent("comment", id, imageCount);
        }

        List<AutomationHistoryStore.Entry> loaded = new AutomationHistoryStore(context).read("comment");
        assertEquals(3, loaded.size());
        for (AutomationHistoryStore.Entry entry : loaded) {
            assertTrue(entry.modelRequestSent);
            assertEquals(Integer.parseInt(entry.id.substring("request-".length())), entry.requestImageCount);
            assertEquals("pending", entry.status);
            assertEquals("作品内容", entry.input);
        }
    }

    @Test public void knownUnsentRequestStaysDistinctFromLegacyUnknownMetadata() {
        store.upsert(new AutomationHistoryStore.Entry("never-sent", "comment", "target-id", "目标用户",
                "作品内容", "", "test-model", "cancelled", 10, 20, 0, false));

        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
        assertEquals(0, loaded.requestImageCount);
        assertFalse(loaded.modelRequestSent);
        assertEquals("cancelled", loaded.status);
    }

    @Test public void statusUpdatesRetainAlreadySentRequestMetadata() {
        store.upsert(entry("request", "comment", "pending", 10, "作品内容", ""));
        store.markModelRequestSent("comment", "request", 2);
        for (String status : new String[]{"sending", "submitted", "sent", "failed", "cancelled"}) {
            store.upsert(entry("request", "comment", status, 10, "作品内容", "生成评论"));

            AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment").get(0);
            assertTrue(status, loaded.modelRequestSent);
            assertEquals(status, 2, loaded.requestImageCount);
            assertEquals(status, loaded.status);
            assertEquals("生成评论", loaded.output);
            assertEquals(10, loaded.createdAt);
        }
    }

    @Test public void lateRequestMetadataCannotRestoreClearedRecordsOrAffectAnotherKind() {
        long created = System.currentTimeMillis() - 100;
        store.upsert(entry("same-id", "comment", "pending", created, "作品内容", ""));
        store.upsert(entry("same-id", "reply", "sent", created, "消息", "回复"));
        store.markModelRequestSent("comment", "same-id", 1);
        assertFalse(store.read("reply").get(0).modelRequestSent);

        store.clear("comment");
        new AutomationHistoryStore(context).markModelRequestSent("comment", "same-id", 3);
        store.upsert(new AutomationHistoryStore.Entry("same-id", "comment", "target-id", "目标用户",
                "作品内容", "生成评论", "test-model", "sent", created, created + 10, 3, true));

        assertTrue(new AutomationHistoryStore(context).read("comment").isEmpty());
        assertEquals(1, store.read("reply").size());
        assertEquals(-1, store.read("reply").get(0).requestImageCount);
        assertFalse(store.read("reply").get(0).modelRequestSent);
    }

    @Test public void diagnosticsRoundTripWithoutChangingSubmittedOrUnknownStatus() {
        for (String status : new String[]{"pending", "sending", "submitted", "unknown", "sent", "failed", "cancelled"}) {
            AutomationHistoryStore.Entry original = entry(status, "comment", status, 10, "作品内容", "生成评论");
            AutomationHistoryStore.Entry annotated = original.withDiagnostics(
                    AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, AutomationHistoryStore.Stage.SEND,
                    12, 34, 56, 78);
            assertEquals(status, original.status);
            assertEquals(-1, original.sendMillis);
            store.upsert(annotated);
            store.markModelRequestSent("comment", status, 2);
        }
        for (AutomationHistoryStore.Entry loaded : new AutomationHistoryStore(context).read("comment")) {
            assertEquals(loaded.id, loaded.status);
            assertEquals(AutomationHistoryStore.Reason.SUBMITTED_UNCONFIRMED, loaded.reason);
            assertEquals(AutomationHistoryStore.Stage.SEND, loaded.stage);
            assertEquals(12, loaded.queueMillis);
            assertEquals(34, loaded.mediaMillis);
            assertEquals(56, loaded.generationMillis);
            assertEquals(78, loaded.sendMillis);
            assertEquals("作品内容", loaded.input);
            assertEquals("生成评论", loaded.output);
            assertEquals(2, loaded.requestImageCount);
            assertTrue(loaded.modelRequestSent);
        }
    }

    @Test public void unknownDiagnosticTextAndMalformedDurationsAreDiscardedWithoutDroppingHistory() throws Exception {
        String secret = "Bearer private-key https://secret.example/?token=hidden";
        JSONObject json = new JSONObject().put("id", "unsafe").put("kind", "reply")
                .put("status", "submitted").put("createdAt", 1).put("updatedAt", 2)
                .put("reason", secret).put("stage", new JSONObject().put("secret", secret))
                .put("queueMillis", "123").put("mediaMillis", 1.5)
                .put("generationMillis", Long.MAX_VALUE).put("sendMillis", -2);
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit()
                .putString("entries_v1", new JSONArray().put(json).toString()).commit();
        AutomationHistoryStore.Entry loaded = store.read("reply").get(0);
        assertEquals("submitted", loaded.status);
        assertEquals(AutomationHistoryStore.Reason.NONE, loaded.reason);
        assertEquals(AutomationHistoryStore.Stage.NONE, loaded.stage);
        assertEquals(-1, loaded.queueMillis);
        assertEquals(-1, loaded.mediaMillis);
        assertEquals(-1, loaded.generationMillis);
        assertEquals(-1, loaded.sendMillis);
        assertFalse(AutomationDiagnostics.details(loaded).contains(secret));
        store.upsert(loaded);
        assertFalse(context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "").contains(secret));
    }

    @Test public void diagnosticsRequireBoundedDurationsAndEmitOnlyKnownEnumNames() {
        AutomationHistoryStore.Entry annotated = entry("bounded", "reply", "failed", 10, "", "")
                .withDiagnostics(AutomationHistoryStore.Reason.AI_RATE_LIMITED, AutomationHistoryStore.Stage.GENERATION,
                        -2, Long.MAX_VALUE, 0, -1);
        store.upsert(annotated);
        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("reply").get(0);
        assertEquals(-1, loaded.queueMillis);
        assertEquals(-1, loaded.mediaMillis);
        assertEquals(0, loaded.generationMillis);
        assertEquals(-1, loaded.sendMillis);
        String source = context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "");
        assertTrue(source.contains("\"reason\":\"ai_rate_limited\""));
        assertTrue(source.contains("\"stage\":\"generation\""));
    }

    @Test public void legacyAndMalformedLocationDataKeepTheRecordWithoutInventingNavigation() throws Exception {
        JSONObject json = new JSONObject().put("id", "legacy").put("kind", "comment")
                .put("status", "sent").put("createdAt", 10).put("updatedAt", 20);
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit()
                .putString("entries_v1", new JSONArray().put(json).toString()).commit();
        assertEmptyLocation(store.read("comment").get(0).location);
        json.put("location", new JSONObject().put("accountId", 123).put("commentId", JSONObject.NULL)
                .put("awemeId", "https://example.test/video/1"));
        context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE).edit()
                .putString("entries_v1", new JSONArray().put(json).toString()).commit();
        AutomationHistoryStore.Entry loaded = store.read("comment").get(0);
        assertEquals("sent", loaded.status);
        assertEmptyLocation(loaded.location);
    }

    @Test public void navigationSurvivesPersistenceDiagnosticsAndImageMetadataUpdates() {
        AutomationHistoryStore.Location location = new AutomationHistoryStore.Location(
                "100", "200", "300", "250", "0:1:100:400", "500");
        AutomationHistoryStore.Entry original = entry("located", "comment_reply", "pending", 10, "原评论", "回复")
                .withLocation(location);
        AutomationHistoryStore.Entry diagnosed = original.withDiagnostics(AutomationHistoryStore.Reason.SEND_FAILED,
                AutomationHistoryStore.Stage.SEND, 1, 2, 3, 4);
        assertSame(location, diagnosed.location);
        assertEmptyLocation(entry("empty", "comment", "pending", 1, "", "").location);
        assertSame(AutomationHistoryStore.Location.EMPTY, original.withLocation(null).location);
        store.upsert(diagnosed);
        store.markModelRequestSent("comment_reply", "located", 2);
        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment_reply").get(0);
        assertLocation(location, loaded.location);
        assertEquals(AutomationHistoryStore.Reason.SEND_FAILED, loaded.reason);
        assertEquals(4, loaded.sendMillis);
        assertTrue(loaded.modelRequestSent);
        assertEquals(2, loaded.requestImageCount);

        store.upsert(entry("located", "comment_reply", "sent", 10, "原评论", "回复"));
        loaded = new AutomationHistoryStore(context).read("comment_reply").get(0);
        assertEquals("sent", loaded.status);
        assertLocation(location, loaded.location);
        assertTrue(loaded.modelRequestSent);
    }

    @Test public void newerReceiptReplacesIncomingCommentButEmptyFieldsPreserveKnownLocation() {
        store.upsert(entry("located", "comment_reply", "pending", 10, "原评论", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "300", "250", "", "")));
        store.upsert(entry("located", "comment_reply", "sent", 10, "原评论", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "301", "", "", "")));
        AutomationHistoryStore.Location loaded = store.read("comment_reply").get(0).location;
        assertEquals("100", loaded.accountId);
        assertEquals("200", loaded.awemeId);
        assertEquals("301", loaded.commentId);
        assertEquals("250", loaded.rootCommentId);
        store.upsert(entry("located", "comment_reply", "sent", 10, "原评论", "回复"));
        assertEquals("301", store.read("comment_reply").get(0).location.commentId);
    }

    @Test public void staleIncomingCommentStatusCannotReplaceAnAlreadyConfirmedReplyReceipt() {
        AutomationHistoryStore.Location incoming = new AutomationHistoryStore.Location(
                "100", "200", "300", "250", "", "300");
        store.upsert(entry("late-reply", "comment_reply", "submitted", 10, "原评论", "回复").withLocation(incoming));
        store.upsert(entry("late-reply", "comment_reply", "sent", 10, "原评论", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "301", "250", "", "300")));

        // The dispatcher can finish after the publisher observer persisted the actual reply CID.
        store.upsert(entry("late-reply", "comment_reply", "sent", 10, "原评论", "更新正文")
                .withLocation(incoming).withDiagnostics(AutomationHistoryStore.Reason.NONE,
                        AutomationHistoryStore.Stage.COMPLETE, 1, 2, 3, 4));
        AutomationHistoryStore.Entry loaded = new AutomationHistoryStore(context).read("comment_reply").get(0);
        assertEquals("301", loaded.location.commentId);
        assertEquals("300", loaded.location.messageId);
        assertEquals("sent", loaded.status);
        assertEquals("更新正文", loaded.output);
        assertEquals(4, loaded.sendMillis);

        store.upsert(entry("late-reply", "comment_reply", "sent", 10, "原评论", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "302", "250", "", "300")));
        assertEquals("302", store.read("comment_reply").get(0).location.commentId);

        store.upsert(entry("late-reply", "comment_reply", "sent", 10, "原评论", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "201", "400", "350", "", "400")));
        assertEquals("400", store.read("comment_reply").get(0).location.commentId);
        assertEquals("201", store.read("comment_reply").get(0).location.awemeId);
    }

    @Test public void differentAccountsNeverMixThePreviousNavigationFieldsIntoAnUpdate() {
        store.upsert(entry("same", "reply", "sent", 10, "原消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "300", "250", "conversation-1", "500")));
        store.upsert(entry("same", "reply", "sent", 10, "新消息", "新回复")
                .withLocation(new AutomationHistoryStore.Location("101", "", "", "", "conversation-2", "")));
        AutomationHistoryStore.Location loaded = store.read("reply").get(0).location;
        assertEquals("101", loaded.accountId);
        assertEquals("conversation-2", loaded.conversationId);
        assertEquals("", loaded.awemeId);
        assertEquals("", loaded.commentId);
        assertEquals("", loaded.rootCommentId);
        assertEquals("", loaded.messageId);
    }

    @Test public void conversationTypePersistsWithoutGuessingLegacyTypesOrMixingDifferentChats() {
        assertEquals(-1, new AutomationHistoryStore.Location("100", "", "", "", "conversation-1", "500").conversationType);
        store.upsert(entry("located-chat", "reply", "sent", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-1", "500", 1)));
        store.markModelRequestSent("reply", "located-chat", 0);
        store.upsert(entry("located-chat", "reply", "sent", 10, "消息", "回复"));
        AutomationHistoryStore.Location loaded = new AutomationHistoryStore(context).read("reply").get(0).location;
        assertEquals("conversation-1", loaded.conversationId);
        assertEquals("500", loaded.messageId);
        assertEquals(1, loaded.conversationType);

        store.upsert(entry("located-chat", "reply", "sent", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-2", "", 0)));
        loaded = store.read("reply").get(0).location;
        assertEquals("conversation-2", loaded.conversationId);
        assertEquals("", loaded.messageId);
        assertEquals(0, loaded.conversationType);
        assertEquals(-1, new AutomationHistoryStore.Location("", "", "", "", "", "", 2).conversationType);
    }

    @Test public void singleChatPeerSurvivesUpdatesButDoesNotCrossAnAccountOrConversationChange() {
        assertEquals("", new AutomationHistoryStore.Location("100", "", "", "", "conversation-1", "500", 0).peerUid);
        store.upsert(entry("peer-chat", "reply", "pending", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-1", "500", 0, "400")));
        store.markModelRequestSent("reply", "peer-chat", 0);
        store.upsert(entry("peer-chat", "reply", "sent", 10, "消息", "回复"));
        AutomationHistoryStore.Location loaded = new AutomationHistoryStore(context).read("reply").get(0).location;
        assertEquals("400", loaded.peerUid);
        assertEquals(0, loaded.conversationType);

        store.upsert(entry("peer-chat", "reply", "sent", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-2", "", 0)));
        assertEquals("", store.read("reply").get(0).location.peerUid);
        store.upsert(entry("peer-chat", "reply", "sent", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("100", "", "", "", "conversation-2", "", 0, "401")));
        assertEquals("401", store.read("reply").get(0).location.peerUid);
        store.upsert(entry("peer-chat", "reply", "sent", 10, "消息", "回复")
                .withLocation(new AutomationHistoryStore.Location("101", "", "", "", "conversation-2", "", 0)));
        assertEquals("", store.read("reply").get(0).location.peerUid);
        assertEquals("", new AutomationHistoryStore.Location("", "", "", "", "", "", 0, "not-a-user").peerUid);
        assertEquals("", new AutomationHistoryStore.Location("", "", "", "", "", "", 0, "400\n").peerUid);
    }

    @Test public void clearedHistoryCannotBeRestoredByALateLocatedReceipt() {
        long created = System.currentTimeMillis() - 100;
        AutomationHistoryStore.Entry original = entry("old", "comment", "pending", created, "作品", "评论")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "", "", "", ""));
        store.upsert(original);
        store.clear("comment");
        store.upsert(entry("old", "comment", "sent", created, "作品", "评论")
                .withLocation(new AutomationHistoryStore.Location("100", "200", "300", "", "", "")));
        store.markModelRequestSent("comment", "old", 1);
        assertTrue(store.read("comment").isEmpty());
    }

    @Test public void navigationIdentifiersRejectControlsUrlsAndOversizedValuesInsteadOfTruncatingIds() {
        String accepted = "x".repeat(256);
        String conversation = "x".repeat(512);
        AutomationHistoryStore.Location valid = new AutomationHistoryStore.Location(
                accepted, accepted, accepted, accepted, conversation, accepted);
        assertEquals(accepted, valid.accountId);
        assertEquals(conversation, valid.conversationId);
        AutomationHistoryStore.Location invalid = new AutomationHistoryStore.Location(
                "account\n100", "https://example.test/video/1", "x".repeat(257),
                "comment\u202e123", "x".repeat(513), "message?token=secret");
        assertEmptyLocation(invalid);
        assertEmptyLocation(new AutomationHistoryStore.Location(null, null, null, null, null, null));
        store.upsert(entry("bounded-navigation", "comment", "sent", 10, "", "").withLocation(invalid));
        String serialized = context.getSharedPreferences("douai_automation_history", Context.MODE_PRIVATE)
                .getString("entries_v1", "");
        assertFalse(serialized.contains("example.test"));
        assertFalse(serialized.contains("secret"));
    }

    private static void assertEmptyLocation(AutomationHistoryStore.Location location) {
        assertLocation(AutomationHistoryStore.Location.EMPTY, location);
    }

    private static void assertLocation(AutomationHistoryStore.Location expected, AutomationHistoryStore.Location actual) {
        assertEquals(expected.accountId, actual.accountId);
        assertEquals(expected.awemeId, actual.awemeId);
        assertEquals(expected.commentId, actual.commentId);
        assertEquals(expected.rootCommentId, actual.rootCommentId);
        assertEquals(expected.conversationId, actual.conversationId);
        assertEquals(expected.messageId, actual.messageId);
        assertEquals(expected.conversationType, actual.conversationType);
        assertEquals(expected.peerUid, actual.peerUid);
    }

    private static AutomationHistoryStore.Entry entry(String id, String kind, String status,
                                                      long createdAt, String input, String output) {
        return new AutomationHistoryStore.Entry(id, kind, "target-id", "目标用户", input, output,
                "test-model", status, createdAt, createdAt + 10);
    }
}
