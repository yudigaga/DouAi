package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public final class CommentAiNativeStreamTest {
    @Test public void firstFrameCreatesAnOwnedNativeCardWithMatchingStructLinks() throws Exception {
        JSONObject frame = stream().firstFrame();
        assertNotNull("A fresh conversation needs a native card before appending text", frame);
        assertEquals(200, frame.getInt("status_code"));
        assertEquals("conversation-1", frame.getString("conversation_id"));
        assertEquals(3, frame.getInt("conversation_rank"));
        JSONObject command = frame.getJSONArray("data").getJSONObject(0);
        assertEquals("NewCard", command.getString("cmd"));
        assertEquals("answer-3", command.getJSONObject("args").getString("id"));
        JSONObject container = command.getJSONObject("container_info");
        JSONObject child = container.getJSONObject("struct").getJSONArray("children").getJSONObject(0);
        JSONObject business = container.getJSONArray("business_data").getJSONObject(0);
        JSONObject render = container.getJSONArray("render_info").getJSONObject(0);
        assertEquals("answer-3", child.getString("id"));
        assertEquals(child.getString("data_id"), business.getString("data_id"));
        assertEquals(child.getString("render_id"), render.getString("render_id"));
        assertEquals(999670, business.getInt("type"));
        assertEquals("0", render.getString("render_type"));
        assertEquals("737614", render.getJSONObject("render_extra").getString("type"));
        JSONObject card = business.getJSONObject("data");
        assertTrue(card.getBoolean("use_client_style"));
        assertEquals("conversation-1", card.getString("conversation_id"));
        assertEquals("哪里能学", card.getJSONObject("card_data").getString("query"));
    }

    @Test public void cumulativePartialsOnlyAppendNewTextAndTerminateExactlyOnce() throws Exception {
        CommentAiNativeStream stream = stream();
        stream.firstFrame();
        assertEquals("你", text(stream.update("你", false)));
        assertEquals("好", text(stream.update("你好", false)));
        assertNull(stream.update("你好", false));
        JSONObject terminal = stream.update("你好！", true);
        assertEquals("！", text(terminal));
        assertTrue(message(terminal).getJSONObject("display").getBoolean("is_end"));
        assertTrue(command(terminal).getBoolean("chunk_end"));
        assertNull(stream.update("你好！", true));
        assertNull(stream.update("晚到文本", false));
    }

    @Test public void authoritativeCorrectionReplacesTheOwnedCardRatherThanDuplicatingText() throws Exception {
        CommentAiNativeStream stream = stream();
        stream.firstFrame();
        stream.update("临时内容", false);
        JSONObject frame = stream.update("校正后的内容", true);
        JSONObject command = command(frame);
        assertEquals("Replace", command.getString("cmd"));
        assertEquals("answer-3", command.getJSONObject("args").getString("replace_id"));
        JSONArray messages = command.getJSONObject("container_info").getJSONArray("business_data")
                .getJSONObject(0).getJSONObject("data").getJSONArray("stream_data");
        assertEquals("校正后的内容", messages.getJSONObject(0).getJSONObject("display")
                .getJSONArray("generation_spans").getJSONObject(0).getJSONObject("text").getString("content"));
    }

    @Test public void cancellationDropsQueuedPartialsAndDoesNotRecreateTheCard() throws Exception {
        CommentAiNativeStream stream = stream();
        stream.firstFrame();
        stream.cancel();
        assertNull(stream.update("late", false));
        assertNull(stream.update("late", true));
        assertNull(stream.firstFrame());
    }

    @Test public void appendUsesTheNestedMessageDisplayExpectedByTheHostParser() throws Exception {
        CommentAiNativeStream stream = stream();
        stream.firstFrame();
        JSONObject frame = stream.update("a\n\"b\"😀", true);
        JSONObject command = command(frame);
        assertEquals("Append", command.getString("cmd"));
        assertEquals("answer-3", command.getJSONObject("args").getString("id"));
        JSONObject span = message(frame).getJSONObject("display").getJSONArray("generation_spans").getJSONObject(0);
        assertEquals(2, span.getInt("type"));
        assertEquals("a\n\"b\"😀", span.getJSONObject("text").getString("content"));
        assertEquals("answer-3", message(frame).getString("doc_id"));
    }

    private static CommentAiNativeStream stream() {
        return new CommentAiNativeStream("conversation-1", 3, "哪里能学", "answer-3");
    }
    private static JSONObject command(JSONObject frame) throws Exception { return frame.getJSONArray("data").getJSONObject(0); }
    private static JSONObject message(JSONObject frame) throws Exception { return command(frame).getJSONObject("display"); }
    private static String text(JSONObject frame) throws Exception {
        return message(frame).getJSONObject("display").getJSONArray("generation_spans")
                .getJSONObject(0).getJSONObject("text").getString("content");
    }
}
