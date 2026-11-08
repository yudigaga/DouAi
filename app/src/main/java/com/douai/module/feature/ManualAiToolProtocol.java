package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;

/** Opt-in native tool records. The ordinary text decoders deliberately remain text-only. */
final class ManualAiToolProtocol {
    static final String TOOL = "douyin_search";
    private static final int MAX_CALLS = 8;

    static final class Call {
        final String id, query;
        final boolean correlated;
        Call(String id, String query, boolean correlated) { this.id = id; this.query = query; this.correlated = correlated; }
    }

    static final class Turn {
        final String text;
        final JSONObject message;
        final JSONArray output;
        final List<Call> calls;
        Turn(String text, JSONObject message, JSONArray output, List<Call> calls) {
            this.text = text; this.message = message; this.output = output; this.calls = calls;
        }
    }

    static void validateParameters(AiModelConfig config) throws Exception {
        JSONObject custom = config.parameters();
        for (String key : new String[]{"tools", "tool_choice", "parallel_tool_calls", "toolConfig"}) {
            if (custom.has(key)) throw new IllegalArgumentException("抖音搜索工具与自定义参数冲突：" + key);
        }
    }

    static void configure(JSONObject body, String protocol, boolean enabled) throws Exception {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", new JSONObject()
                .put("query", new JSONObject().put("type", "string").put("minLength", 1).put("maxLength", 200)))
                .put("required", new JSONArray().put("query")).put("additionalProperties", false);
        JSONObject function = new JSONObject().put("name", TOOL)
                .put("description", "搜索抖音视频资料，返回有限的标题、作者、摘要和来源链接。");
        if (AiModelConfig.CHAT.equals(protocol)) {
            function.put("parameters", schema);
            body.put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function", function)))
                    .put("tool_choice", enabled ? "auto" : "none").put("parallel_tool_calls", false);
        } else if (AiModelConfig.RESPONSES.equals(protocol)) {
            function.put("type", "function").put("parameters", schema);
            body.put("tools", new JSONArray().put(function)).put("tool_choice", enabled ? "auto" : "none")
                    .put("parallel_tool_calls", false);
            JSONArray include = body.optJSONArray("include");
            if (body.has("include") && include == null) throw new IllegalArgumentException("自定义参数 include 必须是数组");
            if (include == null) include = new JSONArray();
            boolean present = false;
            for (int i = 0; i < include.length(); i++) present |= "reasoning.encrypted_content".equals(include.opt(i));
            if (!present) include.put("reasoning.encrypted_content");
            body.put("include", include);
        } else if (AiModelConfig.ANTHROPIC.equals(protocol)) {
            function.put("input_schema", schema);
            body.put("tools", new JSONArray().put(function)).put("tool_choice", new JSONObject().put("type", enabled ? "auto" : "none"));
        } else {
            // Gemini parameters is its Schema message; raw JSON Schema has a separate field.
            schema.remove("additionalProperties");
            function.put("parameters", schema);
            body.put("tools", new JSONArray().put(new JSONObject().put("functionDeclarations", new JSONArray().put(function))))
                    .put("toolConfig", new JSONObject().put("functionCallingConfig", new JSONObject().put("mode", enabled ? "AUTO" : "NONE")));
        }
    }

    static Turn json(String protocol, String body) throws IOException {
        try { return parse(protocol, new JSONObject(body)); }
        catch (ResponsesAnalysisStream.Failure failure) { throw failure; }
        catch (Exception invalid) { throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); }
    }

    private static Turn parse(String protocol, JSONObject body) throws Exception {
        if (nonNull(body, "error")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        List<Call> calls = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        JSONObject message = null;
        JSONArray output = null;
        if (AiModelConfig.CHAT.equals(protocol)) {
            JSONObject choice = body.getJSONArray("choices").getJSONObject(0);
            message = choice.getJSONObject("message");
            role(message, "assistant");
            if (nonNull(message, "content")) append(text, string(message, "content"));
            JSONArray tools = message.optJSONArray("tool_calls");
            if (nonNull(message, "tool_calls") && tools == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            if (tools != null) for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                if (!"function".equals(tool.optString("type"))) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                JSONObject function = tool.getJSONObject("function");
                add(calls, string(tool, "id"), string(function, "name"), new JSONObject(string(function, "arguments")));
            }
            finish(choice.optString("finish_reason"), calls.isEmpty() ? "stop" : "tool_calls");
        } else if (AiModelConfig.RESPONSES.equals(protocol)) {
            finish(body.optString("status"), "completed");
            output = body.optJSONArray("output");
            if (output == null) output = new JSONArray();
            for (int i = 0; i < output.length(); i++) {
                JSONObject item = output.getJSONObject(i);
                role(item, "assistant");
                if ("function_call".equals(item.optString("type"))) {
                    add(calls, string(item, "call_id"), string(item, "name"), new JSONObject(string(item, "arguments")));
                } else if ("message".equals(item.optString("type"))) {
                    JSONArray content = item.getJSONArray("content");
                    for (int j = 0; j < content.length(); j++) {
                        JSONObject block = content.getJSONObject(j);
                        if ("output_text".equals(block.optString("type"))) append(text, string(block, "text"));
                    }
                }
            }
            if (body.opt("output_text") instanceof String && !body.getString("output_text").isEmpty()) {
                text.setLength(0); append(text, body.getString("output_text"));
            }
        } else if (AiModelConfig.ANTHROPIC.equals(protocol)) {
            role(body, "assistant");
            JSONArray content = body.getJSONArray("content");
            message = new JSONObject().put("role", "assistant").put("content", content);
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if ("text".equals(block.optString("type"))) append(text, string(block, "text"));
                else if ("tool_use".equals(block.optString("type"))) add(calls, string(block, "id"), string(block, "name"), block.getJSONObject("input"));
            }
            String reason = body.optString("stop_reason");
            if (calls.isEmpty()) {
                if (!"end_turn".equals(reason) && !"stop_sequence".equals(reason)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            } else finish(reason, "tool_use");
        } else {
            JSONObject feedback = body.optJSONObject("promptFeedback");
            if (feedback != null && nonNull(feedback, "blockReason")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            JSONObject candidate = body.getJSONArray("candidates").getJSONObject(0);
            finish(candidate.optString("finishReason"), "STOP");
            JSONArray ratings = candidate.optJSONArray("safetyRatings");
            if (ratings != null) for (int i = 0; i < ratings.length(); i++) {
                if (ratings.getJSONObject(i).optBoolean("blocked")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            }
            message = candidate.getJSONObject("content");
            role(message, "model");
            JSONArray parts = message.getJSONArray("parts");
            for (int i = 0; i < parts.length(); i++) {
                JSONObject part = parts.getJSONObject(i);
                if (part.has("thought") && !(part.opt("thought") instanceof Boolean)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                if (nonNull(part, "functionCall")) {
                    JSONObject call = part.getJSONObject("functionCall");
                    String id = call.opt("id") instanceof String ? call.getString("id") : "gemini-" + i;
                    add(calls, id, string(call, "name"), call.getJSONObject("args"), call.opt("id") instanceof String);
                }
                if (!part.optBoolean("thought") && part.opt("text") instanceof String) append(text, part.getString("text"));
            }
        }
        if (calls.isEmpty() && text.toString().trim().isEmpty()) throw failure(ResponsesAnalysisStream.Failure.Kind.EMPTY);
        return new Turn(text.toString().trim(), message, output, calls);
    }

    private static void add(List<Call> calls, String id, String name, JSONObject args) throws Exception {
        add(calls, id, name, args, true);
    }

    private static void add(List<Call> calls, String id, String name, JSONObject args, boolean correlated) throws Exception {
        if (calls.size() == MAX_CALLS || id.isEmpty() || id.length() > 256 || !TOOL.equals(name)
                || args.length() != 1 || !(args.opt("query") instanceof String) || args.toString().length() > 4096) {
            throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
        }
        String query = args.getString("query").trim();
        if (query.isEmpty() || query.length() > 200) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
        for (Call prior : calls) if (prior.id.equals(id)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
        calls.add(new Call(id, query, correlated));
    }

    static void appendResults(JSONObject body, String protocol, Turn turn, List<String> results) throws Exception {
        if (AiModelConfig.RESPONSES.equals(protocol)) {
            JSONArray input = body.getJSONArray("input");
            for (int i = 0; i < turn.output.length(); i++) input.put(turn.output.get(i));
            for (int i = 0; i < turn.calls.size(); i++) input.put(new JSONObject().put("type", "function_call_output")
                    .put("call_id", turn.calls.get(i).id).put("output", results.get(i)));
        } else if (AiModelConfig.CHAT.equals(protocol)) {
            JSONArray messages = body.getJSONArray("messages"); messages.put(turn.message);
            for (int i = 0; i < turn.calls.size(); i++) messages.put(new JSONObject().put("role", "tool")
                    .put("tool_call_id", turn.calls.get(i).id).put("content", results.get(i)));
        } else if (AiModelConfig.ANTHROPIC.equals(protocol)) {
            JSONArray messages = body.getJSONArray("messages"); messages.put(turn.message);
            JSONArray content = new JSONArray();
            for (int i = 0; i < turn.calls.size(); i++) content.put(new JSONObject().put("type", "tool_result")
                    .put("tool_use_id", turn.calls.get(i).id).put("content", results.get(i)));
            messages.put(new JSONObject().put("role", "user").put("content", content));
        } else {
            JSONArray contents = body.getJSONArray("contents"); contents.put(turn.message);
            JSONArray parts = new JSONArray(); int callIndex = 0;
            JSONArray original = turn.message.getJSONArray("parts");
            for (int i = 0; i < original.length(); i++) {
                JSONObject originalCall = original.getJSONObject(i).optJSONObject("functionCall");
                if (originalCall == null) continue;
                JSONObject response = new JSONObject().put("name", TOOL).put("response", new JSONObject(results.get(callIndex++)));
                if (originalCall.has("id")) response.put("id", originalCall.get("id"));
                parts.put(new JSONObject().put("functionResponse", response));
            }
            contents.put(new JSONObject().put("role", "user").put("parts", parts));
        }
    }

    static Turn stream(String protocol, InputStream source, ResponsesAnalysisStream.IoGuard guard,
                       ResponsesAnalysisStream.PartialText partial) throws IOException {
        return new Decoder(protocol, source, guard, partial).read();
    }

    private static final class Decoder {
        final String protocol;
        final InputStream source;
        final ResponsesAnalysisStream.IoGuard guard;
        final ResponsesAnalysisStream.PartialText partial;
        final StringBuilder data = new StringBuilder(), text = new StringBuilder();
        final Map<Integer, JSONObject> blocks = new TreeMap<>(), tools = new TreeMap<>();
        final Map<Integer, StringBuilder> inputs = new TreeMap<>();
        final Set<Integer> open = new HashSet<>();
        final JSONArray geminiParts = new JSONArray();
        final JSONObject chat = new JSONObject();
        String eventName = "", reason = "", lastPartial = "";
        boolean started, finished, terminal;
        int frames;
        Turn result;

        Decoder(String protocol, InputStream source, ResponsesAnalysisStream.IoGuard guard, ResponsesAnalysisStream.PartialText partial) {
            this.protocol = protocol; this.source = source; this.guard = guard; this.partial = partial;
        }

        Turn read() throws IOException {
            final int[] bytes = {0};
            InputStream counted = new FilterInputStream(source) {
                @Override public int read() throws IOException { guard.check(); int n = super.read(); guard.check(); if (n >= 0) count(1); return n; }
                @Override public int read(byte[] b, int o, int n) throws IOException { guard.check(); int size = super.read(b, o, n); guard.check(); if (size > 0) count(size); return size; }
                void count(int size) throws IOException { bytes[0] += size; if (bytes[0] > ResponsesAnalysisStream.MAX_BYTES) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG); }
            };
            Reader reader = new InputStreamReader(counted, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
            StringBuilder line = new StringBuilder(); boolean cr = false, first = true;
            try {
                for (int value; (value = reader.read()) != -1;) {
                    if (first) { first = false; if (value == 0xFEFF) continue; }
                    if (value == '\n' && cr) { cr = false; continue; }
                    if (value == '\r' || value == '\n') {
                        cr = value == '\r'; line(line.toString()); line.setLength(0);
                        if (terminal) return completed();
                    } else {
                        cr = false;
                        if (line.length() >= ResponsesAnalysisStream.MAX_LINE_CHARS) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
                        line.append((char) value);
                    }
                }
                if (line.length() > 0) line(line.toString());
                dispatch();
                return completed();
            } catch (ResponsesAnalysisStream.Failure failure) { throw failure; }
            catch (CancellationException failure) { throw failure; }
            catch (java.nio.charset.CharacterCodingException failure) { throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); }
        }

        void line(String value) throws IOException {
            guard.check();
            if (value.isEmpty()) { dispatch(); return; }
            if (value.charAt(0) == ':') return;
            int colon = value.indexOf(':');
            String key = colon < 0 ? value : value.substring(0, colon);
            String content = colon < 0 ? "" : value.substring(colon + 1);
            if (content.startsWith(" ")) content = content.substring(1);
            if ("event".equals(key)) eventName = content;
            if ("data".equals(key)) {
                if (data.length() + content.length() + 1 > ResponsesAnalysisStream.MAX_EVENT_CHARS) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
                if (data.length() > 0) data.append('\n'); data.append(content);
            }
        }

        void dispatch() throws IOException {
            guard.check(); String frame = data.toString().trim(), declared = eventName;
            data.setLength(0); eventName = "";
            if (frame.isEmpty()) return;
            if (++frames > ResponsesAnalysisStream.MAX_FRAMES) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
            if ("[DONE]".equals(frame)) {
                if (!AiModelConfig.CHAT.equals(protocol) || !finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                terminal = true; return;
            }
            try {
                JSONObject event = new JSONObject(frame);
                if ("error".equals(declared) || nonNull(event, "error")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                if (AiModelConfig.CHAT.equals(protocol)) chat(event);
                else if (AiModelConfig.RESPONSES.equals(protocol)) responses(event, declared);
                else if (AiModelConfig.ANTHROPIC.equals(protocol)) claude(event, declared);
                else gemini(event);
                publish();
            } catch (ResponsesAnalysisStream.Failure failure) { throw failure; }
            catch (CancellationException failure) { throw failure; }
            catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); }
        }

        void chat(JSONObject event) throws Exception {
            JSONArray choices = event.optJSONArray("choices"); if (choices == null) return;
            for (int i = 0; i < choices.length(); i++) {
                JSONObject choice = choices.getJSONObject(i);
                if (choice.optInt("index", -1) != 0) continue;
                JSONObject delta = choice.optJSONObject("delta");
                if (delta != null) {
                    if (finished && delta.length() > 0) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                    role(delta, "assistant");
                    if (nonNull(delta, "content")) append(text, string(delta, "content"));
                    for (String key : new String[]{"reasoning_content", "refusal"}) if (delta.opt(key) instanceof String) {
                        chat.put(key, chat.optString(key) + delta.getString(key));
                    }
                    JSONArray calls = delta.optJSONArray("tool_calls");
                    if (nonNull(delta, "tool_calls") && calls == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    if (calls != null) for (int j = 0; j < calls.length(); j++) {
                        JSONObject piece = calls.getJSONObject(j); int index = index(piece);
                        if (piece.has("type") && !"function".equals(piece.opt("type"))) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        JSONObject tool = tools.get(index);
                        if (tool == null) { if (tools.size() == MAX_CALLS) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG); tool = new JSONObject().put("type", "function").put("function", new JSONObject()); tools.put(index, tool); }
                        if (piece.has("id")) { String id = string(piece, "id"); if (tool.has("id") && !id.equals(tool.getString("id"))) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); tool.put("id", id); }
                        JSONObject function = piece.optJSONObject("function");
                        if (function != null) for (String key : new String[]{"name", "arguments"}) if (function.has(key)) {
                            JSONObject target = tool.getJSONObject("function"); String value = target.optString(key) + string(function, key);
                            if (value.length() > 4096) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG); target.put(key, value);
                        }
                    }
                    if (nonNull(delta, "function_call")) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                }
                if (nonNull(choice, "finish_reason")) { if (finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE); reason = string(choice, "finish_reason"); finished = true; }
            }
        }

        void responses(JSONObject event, String declared) throws Exception {
            String type = event.optString("type", declared);
            if (!declared.isEmpty() && !declared.equals(type)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            if ("response.output_text.delta".equals(type)) append(text, string(event, "delta"));
            else if ("response.completed".equals(type)) {
                result = parse(protocol, event.getJSONObject("response")); text.setLength(0); append(text, result.text);
                finished = terminal = true;
            } else if ("response.failed".equals(type) || "response.incomplete".equals(type) || "error".equals(type)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        }

        void claude(JSONObject event, String declared) throws Exception {
            String type = event.optString("type", declared);
            if (!declared.isEmpty() && !declared.equals(type)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            if ("message_start".equals(type)) {
                if (started || finished) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                JSONObject message = event.getJSONObject("message"); if (nonNull(message, "error") || !"assistant".equals(message.optString("role"))) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                started = true;
            } else if ("content_block_start".equals(type)) {
                int index = index(event); if (!started || finished || blocks.containsKey(index) || blocks.size() >= 256) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                JSONObject block = event.getJSONObject("content_block"); blocks.put(index, block); open.add(index);
                if ("text".equals(block.optString("type"))) append(text, string(block, "text"));
            } else if ("content_block_delta".equals(type)) {
                int index = index(event); JSONObject block = blocks.get(index), delta = event.getJSONObject("delta");
                if (!started || finished || block == null || !open.contains(index)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                String deltaType = delta.optString("type");
                if ("text_delta".equals(deltaType) && "text".equals(block.optString("type"))) {
                    String value = string(delta, "text"); block.put("text", block.optString("text") + value); append(text, value);
                } else if ("input_json_delta".equals(deltaType) && "tool_use".equals(block.optString("type"))) {
                    StringBuilder input = inputs.computeIfAbsent(index, ignored -> new StringBuilder()); input.append(string(delta, "partial_json"));
                    if (input.length() > 4096) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
                } else if ("thinking_delta".equals(deltaType)) block.put("thinking", block.optString("thinking") + string(delta, "thinking"));
                else if ("signature_delta".equals(deltaType)) block.put("signature", block.optString("signature") + string(delta, "signature"));
            } else if ("content_block_stop".equals(type)) {
                int index = index(event); if (!open.remove(index)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                if (inputs.containsKey(index)) blocks.get(index).put("input", new JSONObject(inputs.get(index).toString()));
            } else if ("message_delta".equals(type)) {
                if (!started || finished || !open.isEmpty()) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                reason = string(event.getJSONObject("delta"), "stop_reason"); finished = true;
            } else if ("message_stop".equals(type)) {
                if (!started || !finished || !open.isEmpty()) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE); terminal = true;
            } else if ("error".equals(type)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
        }

        void gemini(JSONObject event) throws Exception {
            JSONObject feedback = event.optJSONObject("promptFeedback");
            if (feedback != null && nonNull(feedback, "blockReason")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            JSONArray candidates = event.optJSONArray("candidates"); if (candidates == null) return;
            for (int i = 0; i < candidates.length(); i++) {
                JSONObject candidate = candidates.getJSONObject(i);
                if (!(candidate.optInt("index", -1) == 0 || (!candidate.has("index") && candidates.length() == 1))) continue;
                JSONObject content = candidate.optJSONObject("content");
                if (content != null) {
                    if (finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                    role(content, "model");
                    JSONArray parts = content.getJSONArray("parts");
                    for (int j = 0; j < parts.length(); j++) {
                        JSONObject part = parts.getJSONObject(j); if (geminiParts.length() >= 256) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG); geminiParts.put(part);
                        if (part.has("thought") && !(part.opt("thought") instanceof Boolean)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        if (!part.optBoolean("thought") && part.opt("text") instanceof String) append(text, part.getString("text"));
                    }
                }
                JSONArray ratings = candidate.optJSONArray("safetyRatings");
                if (ratings != null) for (int j = 0; j < ratings.length(); j++) if (ratings.getJSONObject(j).optBoolean("blocked")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                if (candidate.has("finishReason")) { if (finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE); reason = string(candidate, "finishReason"); finished = true; }
            }
        }

        Turn completed() throws IOException {
            guard.check();
            if (!finished || (AiModelConfig.ANTHROPIC.equals(protocol) && !terminal)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            try {
                if (result == null) {
                    JSONObject envelope;
                    if (AiModelConfig.CHAT.equals(protocol)) {
                        chat.put("role", "assistant").put("content", text.toString());
                        JSONArray calls = new JSONArray(); for (JSONObject tool : tools.values()) calls.put(tool);
                        if (calls.length() > 0) chat.put("tool_calls", calls);
                        envelope = new JSONObject().put("choices", new JSONArray().put(new JSONObject().put("message", chat).put("finish_reason", reason)));
                    } else if (AiModelConfig.ANTHROPIC.equals(protocol)) {
                        JSONArray content = new JSONArray(); for (JSONObject block : blocks.values()) content.put(block);
                        envelope = new JSONObject().put("content", content).put("stop_reason", reason);
                    } else {
                        envelope = new JSONObject().put("candidates", new JSONArray().put(new JSONObject().put("finishReason", reason)
                                .put("content", new JSONObject().put("role", "model").put("parts", geminiParts))));
                    }
                    result = parse(protocol, envelope);
                }
                return result;
            } catch (ResponsesAnalysisStream.Failure failure) { throw failure; }
            catch (Exception invalid) { throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); }
        }

        void publish() throws IOException {
            if (partial == null || text.length() == 0 || text.toString().equals(lastPartial)) return;
            guard.check(); partial.accept(text.toString()); lastPartial = text.toString(); guard.check();
        }
    }

    private static int index(JSONObject value) throws Exception {
        Object raw = value.opt("index");
        if (!(raw instanceof Number) || ((Number) raw).doubleValue() < 0 || ((Number) raw).doubleValue() > 1024
                || ((Number) raw).doubleValue() != ((Number) raw).intValue()) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
        return ((Number) raw).intValue();
    }

    private static String string(JSONObject value, String key) throws Exception {
        if (!(value.opt(key) instanceof String)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
        return value.getString(key);
    }

    private static void role(JSONObject value, String expected) throws IOException {
        if (value.has("role") && !expected.equals(value.opt("role"))) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
    }

    private static boolean nonNull(JSONObject value, String key) { return value.has(key) && value.opt(key) != JSONObject.NULL; }
    private static void append(StringBuilder text, String value) throws IOException {
        if (value.length() > 4000 - text.length()) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
        text.append(value);
    }
    private static void finish(String actual, String expected) throws IOException {
        if (!expected.equals(actual)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
    }
    static ResponsesAnalysisStream.Failure failure(ResponsesAnalysisStream.Failure.Kind kind) { return new ResponsesAnalysisStream.Failure(kind, -1); }
}
