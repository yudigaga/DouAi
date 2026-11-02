package com.douai.module.feature;

import com.douai.module.config.AiModelConfig;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

/** Bounded text-only SSE decoding. A finish marker is required before text can be committed. */
final class ProtocolResponseStream {
    static String read(String protocol, InputStream source, int maximumChars,
                       ResponsesAnalysisStream.IoGuard guard, Consumer<String> diagnostics) throws IOException {
        return read(protocol, source, maximumChars, guard, diagnostics, null);
    }

    static String read(String protocol, InputStream source, int maximumChars,
                       ResponsesAnalysisStream.IoGuard guard, Consumer<String> diagnostics,
                       ResponsesAnalysisStream.PartialText onPartial) throws IOException {
        if (AiModelConfig.RESPONSES.equals(protocol)) {
            return ResponsesAnalysisStream.read(source, maximumChars, guard, diagnostics, onPartial);
        }
        if (!AiModelConfig.CHAT.equals(protocol) && !AiModelConfig.ANTHROPIC.equals(protocol)
                && !AiModelConfig.GEMINI.equals(protocol)) {
            throw new ResponsesAnalysisStream.Failure(ResponsesAnalysisStream.Failure.Kind.FORMAT, 0);
        }
        return new Decoder(protocol, source, maximumChars, guard, diagnostics, onPartial).read();
    }

    private static final class Decoder {
        private final String protocol;
        private final int maximumChars;
        private final ResponsesAnalysisStream.IoGuard guard;
        private final Consumer<String> diagnostics;
        private final ResponsesAnalysisStream.PartialText onPartial;
        private final CountingInput input;
        private final StringBuilder data = new StringBuilder();
        private final StringBuilder text = new StringBuilder();
        private final Map<Integer, String> blocks = new HashMap<>();
        private String eventName = "";
        private int frames;
        private boolean firstText, finished, messageStarted, toolCall;
        private String lastPartial = "";

        Decoder(String protocol, InputStream source, int maximumChars,
                ResponsesAnalysisStream.IoGuard guard, Consumer<String> diagnostics,
                ResponsesAnalysisStream.PartialText onPartial) {
            this.protocol = protocol;
            this.maximumChars = maximumChars;
            this.guard = guard;
            this.diagnostics = diagnostics;
            this.onPartial = onPartial;
            input = new CountingInput(source, guard);
        }

        String read() throws IOException {
            Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT));
            StringBuilder line = new StringBuilder();
            boolean previousCr = false, first = true;
            try {
                for (int value; (value = reader.read()) != -1;) {
                    if (first) { first = false; if (value == 0xFEFF) continue; }
                    if (value == '\n' && previousCr) { previousCr = false; continue; }
                    if (value == '\n' || value == '\r') {
                        previousCr = value == '\r';
                        boolean terminal = line(line.toString());
                        line.setLength(0);
                        if (terminal) return result();
                    } else {
                        previousCr = false;
                        if (line.length() == ResponsesAnalysisStream.MAX_LINE_CHARS) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
                        line.append((char) value);
                    }
                }
                if (line.length() > 0 && line(line.toString())) return result();
                if (dispatch()) return result();
                // Chat finish_reason and Gemini finishReason precede optional usage frames, so read to EOF.
                if (!finished || AiModelConfig.ANTHROPIC.equals(protocol)) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                return result();
            } catch (CharacterCodingException invalidUtf8) {
                throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            }
        }

        private boolean line(String line) throws IOException {
            guard.check();
            if (line.isEmpty()) return dispatch();
            if (line.charAt(0) == ':') return false;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("data".equals(field)) {
                int extra = value.length() + (data.length() == 0 ? 0 : 1);
                if (extra > ResponsesAnalysisStream.MAX_EVENT_CHARS - data.length()) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
                if (data.length() > 0) data.append('\n');
                data.append(value);
            } else if ("event".equals(field)) {
                eventName = knownClaudeEvent(value) || "error".equals(value) ? value : "";
            }
            return false;
        }

        private boolean dispatch() throws IOException {
            guard.check();
            String frame = data.toString().trim(), declared = eventName;
            data.setLength(0); eventName = "";
            if (frame.isEmpty()) return false;
            if (++frames > ResponsesAnalysisStream.MAX_FRAMES) throw failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG);
            if (frames == 1) diagnostic("stream_first_event");
            if ("[DONE]".equals(frame)) {
                if (!AiModelConfig.CHAT.equals(protocol) || !finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                return true;
            }
            try {
                JSONObject event = new JSONObject(frame);
                if ("error".equals(declared) || nonNull(event, "error")) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                boolean terminal = false;
                if (AiModelConfig.CHAT.equals(protocol)) chat(event);
                else if (AiModelConfig.ANTHROPIC.equals(protocol)) terminal = claude(event, declared);
                else gemini(event);
                publishPartial();
                return terminal;
            } catch (ResponsesAnalysisStream.Failure failure) { throw failure; }
            catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT); }
        }

        private void chat(JSONObject event) throws IOException {
            JSONArray choices = event.optJSONArray("choices");
            if (choices == null) return; // Forward-compatible metadata is not answer text.
            boolean sawZero = false;
            for (int index = 0; index < choices.length(); index++) {
                JSONObject choice = choices.optJSONObject(index);
                if (choice == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                if (!isIndex(choice.opt("index"), 0)) continue;
                if (sawZero) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                sawZero = true;
                JSONObject delta = choice.optJSONObject("delta");
                if (nonNull(choice, "delta") && delta == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                if (delta != null) {
                    Object role = delta.opt("role");
                    if (role != null && role != JSONObject.NULL && !"assistant".equals(role)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    if (nonNull(delta, "tool_calls")) {
                        JSONArray calls = delta.optJSONArray("tool_calls");
                        if (calls == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        if (calls.length() > 0) toolCall = true;
                    }
                    if (nonNull(delta, "function_call")) toolCall = true;
                    Object content = delta.opt("content");
                    if (content != null && content != JSONObject.NULL) {
                        if (!(content instanceof String)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        append((String) content);
                    }
                }
                Object reason = choice.opt("finish_reason");
                if (reason != null && reason != JSONObject.NULL) {
                    if (finished || !"stop".equals(reason) || toolCall) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                    finished = true;
                }
            }
        }

        private boolean claude(JSONObject event, String declared) throws IOException {
            Object raw = event.opt("type");
            String type = raw instanceof String ? (String) raw : declared;
            if (!declared.isEmpty() && !declared.equals(type)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            switch (type) {
                case "error": throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                case "message_start": {
                    if (messageStarted || finished) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    JSONObject message = event.optJSONObject("message");
                    if (message == null || !"assistant".equals(message.opt("role")) || nonNull(message, "error")) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    messageStarted = true;
                    break;
                }
                case "content_block_start": {
                    if (!messageStarted || finished) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    int index = blockIndex(event);
                    JSONObject block = event.optJSONObject("content_block");
                    if (block == null || blocks.containsKey(index)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    String blockType = block.optString("type", "");
                    blocks.put(index, blockType);
                    if ("text".equals(blockType)) {
                        Object value = block.opt("text");
                        if (!(value instanceof String)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        append((String) value);
                    }
                    break;
                }
                case "content_block_delta": {
                    int index = blockIndex(event);
                    String blockType = blocks.get(index);
                    if (!messageStarted || finished || blockType == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    if ("text".equals(blockType) && "text_delta".equals(delta.opt("type"))) {
                        Object value = delta.opt("text");
                        if (!(value instanceof String)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        append((String) value);
                    }
                    break;
                }
                case "content_block_stop": {
                    int index = blockIndex(event);
                    if (!messageStarted || finished || blocks.remove(index) == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    break;
                }
                case "message_delta": {
                    if (!messageStarted) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    JSONObject delta = event.optJSONObject("delta");
                    if (delta == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    Object reason = delta.opt("stop_reason");
                    if (reason != null && reason != JSONObject.NULL) {
                        if (finished || !blocks.isEmpty() || !("end_turn".equals(reason) || "stop_sequence".equals(reason))) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                        finished = true;
                    }
                    break;
                }
                case "message_stop":
                    if (!messageStarted || !finished || !blocks.isEmpty()) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                    return true;
                default: break; // ping, thinking and future event types cannot become answer text.
            }
            return false;
        }

        private void gemini(JSONObject event) throws IOException {
            JSONObject feedback = event.optJSONObject("promptFeedback");
            if (feedback != null && (nonNull(feedback, "blockReason") || blocked(feedback.optJSONArray("safetyRatings")))) {
                throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            }
            JSONArray candidates = event.optJSONArray("candidates");
            if (candidates == null) return;
            boolean sawZero = false;
            for (int index = 0; index < candidates.length(); index++) {
                JSONObject candidate = candidates.optJSONObject(index);
                if (candidate == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                // Google may omit a default-valued index in protobuf JSON when there is one candidate.
                boolean selected = isIndex(candidate.opt("index"), 0) || (!candidate.has("index") && candidates.length() == 1);
                if (!selected) continue;
                if (sawZero) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                sawZero = true;
                if (blocked(candidate.optJSONArray("safetyRatings"))) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                JSONObject content = candidate.optJSONObject("content");
                if (content != null) {
                    Object role = content.opt("role");
                    if (role != null && role != JSONObject.NULL && !"model".equals(role)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                    JSONArray parts = content.optJSONArray("parts");
                    if (parts != null) for (int partIndex = 0; partIndex < parts.length(); partIndex++) {
                        JSONObject part = parts.optJSONObject(partIndex);
                        if (part == null) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        if (nonNull(part, "thought") && !(part.opt("thought") instanceof Boolean)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                        if (Boolean.TRUE.equals(part.opt("thought"))) continue;
                        if (nonNull(part, "functionCall")) toolCall = true;
                        Object value = part.opt("text");
                        if (value != null && value != JSONObject.NULL) {
                            if (!(value instanceof String)) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
                            append((String) value);
                        }
                    }
                }
                Object reason = candidate.opt("finishReason");
                if (reason != null && reason != JSONObject.NULL) {
                    if (finished || !"STOP".equals(reason) || toolCall) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
                    finished = true;
                }
            }
        }

        private int blockIndex(JSONObject event) throws IOException {
            Object raw = event.opt("index");
            if (!(raw instanceof Number) || ((Number) raw).doubleValue() < 0
                    || ((Number) raw).doubleValue() > Integer.MAX_VALUE
                    || ((Number) raw).doubleValue() != ((Number) raw).intValue()) throw failure(ResponsesAnalysisStream.Failure.Kind.FORMAT);
            return ((Number) raw).intValue();
        }

        private void append(String value) throws IOException {
            if (value.isEmpty()) return;
            if (finished) throw failure(ResponsesAnalysisStream.Failure.Kind.INCOMPLETE);
            if (value.length() > maximumChars - text.length()) throw new ResponsesAnalysisStream.Failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG, text.length() + value.length());
            text.append(value);
            if (!firstText) { firstText = true; diagnostic("stream_first_text"); }
        }

        private String result() throws IOException {
            guard.check();
            if (text.toString().trim().isEmpty()) throw failure(ResponsesAnalysisStream.Failure.Kind.EMPTY);
            diagnostic("stream_complete");
            guard.check();
            return text.toString();
        }

        private void publishPartial() throws IOException {
            if (onPartial == null || text.length() == 0) return;
            String snapshot = text.toString();
            if (snapshot.equals(lastPartial)) return;
            guard.check();
            onPartial.accept(snapshot);
            lastPartial = snapshot;
            guard.check();
        }

        private ResponsesAnalysisStream.Failure failure(ResponsesAnalysisStream.Failure.Kind kind) {
            return new ResponsesAnalysisStream.Failure(kind, text.length());
        }

        private void diagnostic(String stage) {
            try { diagnostics.accept("transport=stream stage=" + stage + " bytes=" + input.count
                    + " frames=" + frames + " chars=" + text.length()); }
            catch (RuntimeException ignored) { }
        }
    }

    private static boolean nonNull(JSONObject value, String key) {
        return value.has(key) && value.opt(key) != null && value.opt(key) != JSONObject.NULL;
    }

    private static boolean isIndex(Object value, int expected) {
        return value instanceof Number && ((Number) value).doubleValue() == expected;
    }

    private static boolean blocked(JSONArray ratings) {
        if (ratings != null) for (int index = 0; index < ratings.length(); index++) {
            JSONObject rating = ratings.optJSONObject(index);
            if (rating != null && Boolean.TRUE.equals(rating.opt("blocked"))) return true;
        }
        return false;
    }

    private static boolean knownClaudeEvent(String type) {
        return "message_start".equals(type) || "content_block_start".equals(type)
                || "content_block_delta".equals(type) || "content_block_stop".equals(type)
                || "message_delta".equals(type) || "message_stop".equals(type);
    }

    private static final class CountingInput extends FilterInputStream {
        private final ResponsesAnalysisStream.IoGuard guard;
        private int count;
        CountingInput(InputStream input, ResponsesAnalysisStream.IoGuard guard) { super(input); this.guard = guard; }
        @Override public int read() throws IOException {
            guard.check();
            int value = in.read();
            guard.check();
            if (value >= 0 && ++count > ResponsesAnalysisStream.MAX_BYTES) throw new ResponsesAnalysisStream.Failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG, -1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            guard.check();
            int read = in.read(buffer, offset, length);
            guard.check();
            if (read > 0) {
                if (read > ResponsesAnalysisStream.MAX_BYTES - count) throw new ResponsesAnalysisStream.Failure(ResponsesAnalysisStream.Failure.Kind.OVERLONG, -1);
                count += read;
            }
            return read;
        }
    }
}
