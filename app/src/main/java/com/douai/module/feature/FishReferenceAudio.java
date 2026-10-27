package com.douai.module.feature;

import android.content.Context;

import com.douai.module.config.FishSpeechSettings;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads only files explicitly imported into the host's private audio-source directory. */
public final class FishReferenceAudio {
    public static final int MAX_REFERENCE_BYTES = 8 * 1024 * 1024;
    private static final Pattern SPEAKER = Pattern.compile("<\\|speaker:([0-9]+)\\|>");

    private FishReferenceAudio() { }

    static void validateSpeakerText(String text, int speakers, boolean requireTags) {
        Matcher match = SPEAKER.matcher(text);
        int tags = 0;
        while (match.find()) {
            tags++;
            try {
                if (Integer.parseInt(match.group(1)) >= speakers) throw new NumberFormatException();
            } catch (NumberFormatException invalid) { throw failure("说话人标签超出已配置的音色范围"); }
        }
        int prefixes = 0, from = 0;
        while ((from = text.indexOf("<|speaker:", from)) >= 0) { prefixes++; from++; }
        if (prefixes != tags) throw failure("说话人标签无效，请使用 <|speaker:0|> 形式");
        if (requireTags && tags == 0) throw failure("多说话人正文需要 <|speaker:0|> 等标签，请添加标签或切回单音色");
    }

    static List<List<Map<String, Object>>> read(Context context, FishSpeechSettings settings,
                                               SpeechHttpClient.Request request) throws IOException {
        if (context == null) throw failure("参考音频不可用，请在当前宿主中重新导入");
        List<List<Map<String, Object>>> result = new ArrayList<>();
        long total = 0;
        for (List<FishSpeechSettings.ReferenceSample> group : settings.referenceGroups()) {
            List<Map<String, Object>> samples = new ArrayList<>();
            for (FishSpeechSettings.ReferenceSample sample : group) {
                request.ensureActive();
                File file = ownedFile(context, sample.path);
                long available = MAX_REFERENCE_BYTES - total;
                if (file.length() <= 0 || file.length() > available) throw failure("参考音频合计需为非空且不超过本模块 8 MiB 限制");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (FileInputStream input = new FileInputStream(file)) {
                    byte[] buffer = new byte[8192]; int read;
                    while ((read = input.read(buffer)) != -1) {
                        request.ensureActive();
                        if (read > available - bytes.size()) throw failure("参考音频合计超过本模块 8 MiB 限制");
                        bytes.write(buffer, 0, read);
                    }
                }
                byte[] audio = bytes.toByteArray();
                if (audio.length == 0 || !supported(audio)) throw failure("参考音频仅支持 WAV、MP3 或 FLAC，请重新选择文件");
                total += audio.length;
                Map<String, Object> value = new LinkedHashMap<>();
                value.put("audio", audio); value.put("text", sample.text); samples.add(value);
            }
            result.add(samples);
        }
        request.ensureActive();
        return result;
    }

    public static File ownedFile(Context context, String path) throws IOException {
        File root = new File(context.getFilesDir(), "douai-voice-source").getCanonicalFile();
        File file = new File(path).getCanonicalFile();
        if (!root.equals(file.getParentFile()) || !file.isFile()
                || java.nio.file.Files.isSymbolicLink(new File(path).toPath())) {
            throw failure("参考音频不可用，请在当前宿主中重新导入");
        }
        return file;
    }

    public static void validateImported(Context context, String path) throws IOException {
        File file = ownedFile(context, path);
        if (file.length() <= 0 || file.length() > MAX_REFERENCE_BYTES) throw failure("参考音频需为非空且不超过本模块 8 MiB 限制");
        byte[] header = new byte[16]; int read;
        try (FileInputStream input = new FileInputStream(file)) { read = input.read(header); }
        if (read <= 0 || !supported(java.util.Arrays.copyOf(header, read))) throw failure("参考音频仅支持 WAV、MP3 或 FLAC，请重新选择文件");
    }

    private static boolean supported(byte[] bytes) {
        return starts(bytes, "fLaC", 0) || starts(bytes, "ID3", 0)
                || starts(bytes, "RIFF", 0) && starts(bytes, "WAVE", 8)
                || bytes.length >= 2 && (bytes[0] & 255) == 255 && (bytes[1] & 224) == 224
                && (bytes[1] & 6) != 0;
    }

    private static boolean starts(byte[] bytes, String value, int offset) {
        byte[] expected = value.getBytes(StandardCharsets.US_ASCII);
        if (bytes.length < offset + expected.length) return false;
        for (int index = 0; index < expected.length; index++) if (bytes[offset + index] != expected[index]) return false;
        return true;
    }

    private static Failure failure(String reason) { return new Failure(reason); }

    static final class Failure extends IllegalArgumentException {
        Failure(String reason) { super("Fish Audio 语音" + reason); }
    }
}
