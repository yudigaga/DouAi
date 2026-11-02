package com.douai.module.feature;

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
import java.util.function.Consumer;

/** Bounded Responses SSE decoding for explicit local analysis; never reconnects or exposes events. */
final class ResponsesAnalysisStream {
    static final int MAX_BYTES = 8 * 1024 * 1024;
    // A completed snapshot may include sizeable metadata; the visible result has a separate 4032-char cap.
    static final int MAX_LINE_CHARS = 256 * 1024;
    static final int MAX_EVENT_CHARS = 512 * 1024;
    static final int MAX_FRAMES = 65_536;

    interface IoGuard { void check() throws IOException; }
    interface PartialText { void accept(String snapshot) throws IOException; }

    static final class Failure extends IOException {
        enum Kind { INCOMPLETE, EMPTY, OVERLONG, FORMAT }
        final Kind kind;
        final int chars;
        Failure(Kind kind, int chars) {
            super("Responses analysis stream " + kind.name());
            this.kind = kind; this.chars = chars;
        }
    }

    static String read(InputStream source, int maximumChars, IoGuard guard, Consumer<String> diagnostics) throws IOException {
        return read(source, maximumChars, guard, diagnostics, null);
    }

    static String read(InputStream source, int maximumChars, IoGuard guard, Consumer<String> diagnostics,
                       PartialText onPartial) throws IOException {
        return new Decoder(source, maximumChars, guard, diagnostics, onPartial).read();
    }

    private static final class Decoder {
        private final int maximumChars;
        private final IoGuard guard;
        private final Consumer<String> diagnostics;
        private final PartialText onPartial;
        private final CountingInput input;
        private final StringBuilder data = new StringBuilder();
        private final StringBuilder deltas = new StringBuilder();
        private String eventName = "";
        private int frames;
        private boolean firstText;
        private String lastPartial = "";

        Decoder(InputStream source, int maximumChars, IoGuard guard, Consumer<String> diagnostics, PartialText onPartial) {
            this.maximumChars = maximumChars;
            this.guard = guard;
            this.diagnostics = diagnostics;
            this.onPartial = onPartial;
            this.input = new CountingInput(source, guard);
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
                        String result = line(line.toString());
                        line.setLength(0);
                        if (result != null) return result;
                    } else {
                        previousCr = false;
                        if (line.length() == MAX_LINE_CHARS) throw failure(Failure.Kind.OVERLONG);
                        line.append((char) value);
                    }
                }
                if (line.length() > 0) {
                    String result = line(line.toString());
                    if (result != null) return result;
                }
                // Some compatible relays omit the final blank line; a complete JSON event is still explicit.
                String result = dispatch();
                if (result != null) return result;
                throw failure(Failure.Kind.INCOMPLETE);
            } catch (CharacterCodingException invalidUtf8) {
                throw failure(Failure.Kind.FORMAT);
            }
        }

        private String line(String line) throws IOException {
            guard.check();
            if (line.isEmpty()) return dispatch();
            if (line.charAt(0) == ':') return null;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            if ("data".equals(field)) {
                int extra = value.length() + (data.length() == 0 ? 0 : 1);
                if (extra > MAX_EVENT_CHARS - data.length()) throw failure(Failure.Kind.OVERLONG);
                if (data.length() > 0) data.append('\n');
                data.append(value);
            } else if ("event".equals(field)) eventName = knownType(value) ? value : "";
            // id/retry/unknown fields never drive reconnection or become diagnostic text.
            return null;
        }

        private String dispatch() throws IOException {
            guard.check();
            String frame = data.toString().trim();
            String declared = eventName;
            data.setLength(0); eventName = "";
            if (frame.isEmpty()) return null;
            if (++frames > MAX_FRAMES) throw failure(Failure.Kind.OVERLONG);
            if (frames == 1) diagnostic("stream_first_event");
            if ("[DONE]".equals(frame)) throw failure(Failure.Kind.INCOMPLETE);
            try {
                JSONObject event = new JSONObject(frame);
                Object rawType = event.opt("type");
                String type = rawType instanceof String ? (String) rawType : declared;
                if (!knownType(type)) return null;
                if (!declared.isEmpty() && !declared.equals(type)) throw failure(Failure.Kind.FORMAT);
                switch (type) {
                    case "response.output_text.delta": {
                        Object delta = event.opt("delta");
                        if (!(delta instanceof String)) throw failure(Failure.Kind.FORMAT);
                        append(deltas, (String) delta);
                        if (!firstText && !((String) delta).isEmpty()) { firstText = true; diagnostic("stream_first_text"); }
                        publishPartial(deltas.toString());
                        return null;
                    }
                    case "response.completed": {
                        JSONObject response = event.optJSONObject("response");
                        if (response == null || !"completed".equals(response.opt("status"))
                                || (response.has("error") && response.opt("error") != null && response.opt("error") != JSONObject.NULL)) {
                            throw failure(Failure.Kind.INCOMPLETE);
                        }
                        String completed = completedText(response);
                        String result = completed.isEmpty() ? deltas.toString() : completed;
                        if (result.trim().isEmpty()) throw failure(Failure.Kind.EMPTY);
                        guard.check();
                        diagnostic("stream_complete", result.length());
                        publishPartial(result);
                        return result;
                    }
                    default: throw failure(Failure.Kind.INCOMPLETE);
                }
            } catch (Failure failure) { throw failure; }
            catch (java.util.concurrent.CancellationException cancelled) { throw cancelled; }
            catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw failure(Failure.Kind.FORMAT); }
        }

        private String completedText(JSONObject response) throws IOException {
            Object direct = response.opt("output_text");
            if (direct instanceof String && !((String) direct).isEmpty()) {
                StringBuilder text = new StringBuilder(); append(text, (String) direct); return text.toString();
            }
            StringBuilder text = new StringBuilder();
            JSONArray output = response.optJSONArray("output");
            if (output != null) for (int index = 0; index < output.length(); index++) {
                JSONObject item = output.optJSONObject(index);
                if (item == null || !"message".equals(item.opt("type"))) continue;
                JSONArray content = item.optJSONArray("content");
                if (content == null) continue;
                for (int blockIndex = 0; blockIndex < content.length(); blockIndex++) {
                    JSONObject block = content.optJSONObject(blockIndex);
                    if (block == null || !"output_text".equals(block.opt("type"))) continue;
                    Object value = block.opt("text");
                    if (!(value instanceof String)) throw failure(Failure.Kind.FORMAT);
                    append(text, (String) value);
                }
            }
            return text.toString();
        }

        private void append(StringBuilder text, String value) throws IOException {
            if (value.length() > maximumChars - text.length()) throw new Failure(Failure.Kind.OVERLONG, text.length() + value.length());
            text.append(value);
        }
        private void publishPartial(String snapshot) throws IOException {
            if (onPartial == null || snapshot.isEmpty() || snapshot.equals(lastPartial)) return;
            guard.check();
            onPartial.accept(snapshot);
            lastPartial = snapshot;
            guard.check();
        }
        private Failure failure(Failure.Kind kind) { return new Failure(kind, deltas.length()); }
        private void diagnostic(String stage) { diagnostic(stage, deltas.length()); }
        private void diagnostic(String stage, int chars) {
            try { diagnostics.accept("transport=stream stage=" + stage + " bytes=" + input.count
                    + " frames=" + frames + " chars=" + chars); }
            catch (RuntimeException ignored) { }
        }
    }

    private static boolean knownType(String value) {
        return "response.output_text.delta".equals(value) || "response.completed".equals(value)
                || "response.failed".equals(value) || "response.incomplete".equals(value) || "error".equals(value);
    }

    private static final class CountingInput extends FilterInputStream {
        private final IoGuard guard;
        private int count;
        CountingInput(InputStream input, IoGuard guard) { super(input); this.guard = guard; }
        @Override public int read() throws IOException {
            guard.check();
            int value = in.read();
            guard.check();
            if (value >= 0 && ++count > MAX_BYTES) throw new Failure(Failure.Kind.OVERLONG, -1);
            return value;
        }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            guard.check();
            int read = in.read(buffer, offset, length);
            guard.check();
            if (read > 0) {
                if (read > MAX_BYTES - count) throw new Failure(Failure.Kind.OVERLONG, -1);
                count += read;
            }
            return read;
        }
    }
}
