package com.douai.module.feature;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** WebVTT only: the host's landscape subtitle presenter explicitly selects this format. */
final class PlaybackReadingText {
    static final int MAX_CHARS = 2 * 1024 * 1024;
    static final int MAX_LINES = 5000;
    private static final Pattern TIME = Pattern.compile("(?:(\\d{2,6}):)?(\\d{2}):(\\d{2})\\.(\\d{3})");
    static final class Line {
        final long millis;
        final String text;
        Line(long millis, String text) { this.millis = millis; this.text = text; }
    }
    private PlaybackReadingText() { }
    static List<Line> webvtt(String body) {
        if (body == null || body.length() > MAX_CHARS) throw new IllegalArgumentException("字幕大小超限");
        String normalized = body.replace("\r\n", "\n").replace('\r', '\n');
        if (normalized.startsWith("\uFEFF")) normalized = normalized.substring(1);
        if (!normalized.matches("(?s)^WEBVTT(?:[ \\t][^\\n]*|)\\n.*")) throw new IllegalArgumentException("字幕不是 WebVTT 格式");
        List<Line> result = new ArrayList<>();
        for (String block : normalized.split("\n[ \\t]*\n")) {
            if (result.size() >= MAX_LINES) throw new IllegalArgumentException("字幕超过 5000 句");
            String[] lines = block.split("\n");
            if (lines.length < 2 || lines[0].startsWith("WEBVTT") || lines[0].startsWith("NOTE")
                    || lines[0].equals("STYLE") || lines[0].equals("REGION")) continue;
            int at = lines[0].contains("-->") ? 0 : 1;
            if (at >= lines.length) continue;
            String[] range = lines[at].trim().split("[ \\t]+-->[ \\t]+", 2);
            if (range.length != 2) continue;
            long begin = timestamp(range[0]);
            long end = timestamp(range[1].split("[ \\t]+", 2)[0]);
            if (begin < 0 || end <= begin) begin = -1;
            StringBuilder text = new StringBuilder();
            for (int i = at + 1; i < lines.length && text.length() < 4000; i++) {
                if (text.length() > 0) text.append('\n');
                text.append(lines[i], 0, Math.min(lines[i].length(), 4000 - text.length()));
            }
            String plain = text.toString().replaceAll("<[^>]*>", "")
                    .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                    .replace("&nbsp;", " ").replace("&lrm;", "\u200e").replace("&rlm;", "\u200f").trim();
            if (plain.length() > 4000) plain = plain.substring(0, 4000);
            if (!plain.isEmpty()) result.add(new Line(begin, plain));
        }
        return Collections.unmodifiableList(result);
    }
    private static long timestamp(String value) {
        Matcher m = TIME.matcher(value); if (!m.matches()) return -1;
        long hours = m.group(1) == null ? 0 : Long.parseLong(m.group(1));
        int minutes = Integer.parseInt(m.group(2)), seconds = Integer.parseInt(m.group(3));
        if (minutes > 59 || seconds > 59) return -1;
        return ((hours * 60 + minutes) * 60 + seconds) * 1000 + Integer.parseInt(m.group(4));
    }
}
