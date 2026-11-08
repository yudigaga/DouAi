package com.douai.module.feature;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Protocol writer for the 41.0 comment panel's existing native text card. */
final class CommentAiNativeStream {
    static final int CARD_TYPE = 737614;
    static final int DATA_TYPE = 999670;
    private final String conversationId;
    private final int rank;
    private final String query;
    private final String cardId;
    private final String searchId;
    private String emitted = "";
    private boolean started;
    private boolean terminal;

    CommentAiNativeStream(String conversationId, int rank, String query, String cardId) {
        if (conversationId == null || conversationId.isEmpty() || rank < 1 || cardId == null || cardId.isEmpty()) {
            throw new IllegalArgumentException("A comment AI conversation, positive rank and owned card are required");
        }
        this.conversationId = conversationId;
        this.rank = rank;
        this.query = query == null ? "" : query;
        this.cardId = cardId;
        this.searchId = "douai_" + cardId;
    }

    synchronized JSONObject firstFrame() {
        if (started || terminal) return null;
        JSONObject result = cardFrame("NewCard", "", false);
        started = true;
        return result;
    }

    synchronized JSONObject update(String cumulative, boolean complete) {
        if (terminal) return null;
        if (!started) throw new IllegalStateException("Create the native card before appending text");
        String next = cumulative == null ? "" : cumulative;
        if (next.equals(emitted) && !complete) return null;
        JSONObject result;
        if (!next.startsWith(emitted)) {
            // The validated final reply can differ from provisional provider text.
            result = cardFrame("Replace", next, complete);
        } else {
            try {
                JSONObject command = new JSONObject().put("cmd", "Append")
                        .put("args", arguments()).put("display", message(next.substring(emitted.length()), complete))
                        .put("chunk_end", complete);
                result = frame(command);
            } catch (JSONException failure) { throw new IllegalStateException("Cannot encode native answer", failure); }
        }
        emitted = next;
        terminal = complete;
        return result;
    }

    synchronized void cancel() { terminal = true; }

    private JSONObject cardFrame(String commandName, String text, boolean complete) {
        try {
            String dataId = cardId + "_data", renderId = cardId + "_render";
            JSONObject metadata = new JSONObject().put("conversation_id", conversationId)
                    .put("conversation_rank", rank).put("key_word", query)
                    .put("search_id", searchId).put("search_result_id", cardId)
                    .put("disable_typewriter_effect", true);
            JSONObject cardData = new JSONObject().put("query", query).put("doc_id", cardId)
                    .put("search_enter_from", "comment_ai_analysis")
                    .put("disable_operation", new JSONArray())
                    .put("display", new JSONObject().put("hide_action_bar", 0).put("data", metadata));
            JSONArray messages = new JSONArray();
            if (!text.isEmpty() || complete) messages.put(message(text, complete));
            JSONObject model = new JSONObject().put("conversation_id", conversationId)
                    .put("card_type_name", String.valueOf(CARD_TYPE)).put("card_data", cardData)
                    .put("raw_data", cardData.toString()).put("stream_data", messages)
                    .put("use_client_style", true);
            JSONObject business = new JSONObject().put("type", DATA_TYPE).put("data_id", dataId)
                    .put("card_id", cardId).put("data", model);
            JSONObject render = new JSONObject().put("render_id", renderId).put("render_type", "0")
                    .put("render_extra", new JSONObject().put("type", String.valueOf(CARD_TYPE))
                            .put("card_type_name", String.valueOf(CARD_TYPE)));
            JSONObject child = new JSONObject().put("id", cardId).put("data_id", dataId).put("render_id", renderId);
            JSONObject container = new JSONObject().put("status_code", 200)
                    .put("log_pb", new JSONObject().put("impr_id", searchId))
                    .put("business_data", new JSONArray().put(business))
                    .put("render_info", new JSONArray().put(render))
                    .put("struct", new JSONObject().put("id", "0").put("layout_info", new JSONObject())
                            .put("children", new JSONArray().put(child)));
            JSONObject args = arguments().put("parent_id", "0").put("is_first_card_data", true);
            if ("Replace".equals(commandName)) args.put("replace_id", cardId);
            return frame(new JSONObject().put("cmd", commandName).put("args", args)
                    .put("container_info", container).put("chunk_end", complete));
        } catch (JSONException failure) { throw new IllegalStateException("Cannot encode native answer card", failure); }
    }

    private JSONObject arguments() throws JSONException {
        return new JSONObject().put("id", cardId).put("disable_typewriter_effect", true);
    }

    private JSONObject message(String text, boolean complete) throws JSONException {
        JSONArray spans = new JSONArray();
        if (!text.isEmpty()) spans.put(new JSONObject().put("type", 2)
                .put("text", new JSONObject().put("content", text)));
        return new JSONObject().put("doc_id", cardId).put("search_id", searchId)
                .put("display", new JSONObject().put("generation_spans", spans).put("is_end", complete));
    }

    private JSONObject frame(JSONObject command) throws JSONException {
        return new JSONObject().put("status_code", 200).put("conversation_id", conversationId)
                .put("conversation_rank", rank).put("search_id", searchId).put("search_keyword", query)
                .put("should_persist_history", false).put("data", new JSONArray().put(command));
    }
}
