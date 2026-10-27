package com.douai.module.feature;

import android.net.Uri;
import android.content.Context;

import com.douai.module.config.FishSpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Native Fish Audio API. Engine headers and reference voices are separate concepts. */
public final class FishSpeechClient {
    private static final int PAGE_SIZE = 20;
    private static final int MAX_VOICES = 2048;
    private static final int MAX_VOICE_PAGES = 32;
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "^(?:Fish Audio 语音 (?:POST|GET)|第三方语音 GET) 返回 HTTP ([0-9]{3})$");

    private FishSpeechClient() { }

    public static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                       FishSpeechSettings settings) throws IOException {
        return synthesize(request, text, settings, null);
    }

    static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                FishSpeechSettings settings, File destination) throws IOException {
        return synthesize(request, text, settings, destination, null);
    }

    static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                FishSpeechSettings settings, File destination, Context context) throws IOException {
        boolean complete = false;
        try {
            request.ensureActive();
            try { settings.validateForSynthesis(); }
            catch (IllegalArgumentException configuration) { throw invalid("配置无效：" + configuration.getMessage()); }
            if (text == null || text.trim().isEmpty()) throw invalid("合成文本为空");
            JSONObject prosody = new JSONObject().put("speed", settings.speed).put("volume", settings.volumeDb);
            if (!"s1".equals(settings.engine)) prosody.put("normalize_loudness", settings.normalizeLoudness);
            JSONObject body = new JSONObject().put("text", text).put("format", settings.format)
                    .put("prosody", prosody).put("temperature", settings.temperature).put("top_p", settings.topP)
                    .put("normalize", settings.normalize).put("latency", settings.latency)
                    .put("chunk_length", settings.chunkLength).put("min_chunk_length", settings.minChunkLength)
                    .put("max_new_tokens", settings.maxNewTokens).put("repetition_penalty", settings.repetitionPenalty)
                    .put("condition_on_previous_chunks", settings.conditionOnPreviousChunks)
                    .put("early_stop_threshold", settings.earlyStopThreshold);
            if ("multi".equals(settings.voiceMode)) {
                FishReferenceAudio.validateSpeakerText(text, settings.referenceIds.size(), settings.referenceIds.size() > 1);
                body.put("reference_id", new JSONArray(settings.referenceIds));
            } else if ("references".equals(settings.voiceMode)) {
                FishReferenceAudio.validateSpeakerText(text, settings.referenceGroups().size(), settings.referenceGroups().size() > 1);
            } else {
                FishReferenceAudio.validateSpeakerText(text, 1, false);
                if (!settings.referenceId.isEmpty()) body.put("reference_id", settings.referenceId);
            }
            if (settings.sampleRate != 0) body.put("sample_rate", settings.sampleRate);
            if ("mp3".equals(settings.format)) body.put("mp3_bitrate", settings.mp3Bitrate);
            if ("opus".equals(settings.format)) body.put("opus_bitrate", settings.opusBitrate);
            if (settings.normalize && !settings.language.isEmpty() && !"auto".equalsIgnoreCase(settings.language)) body.put("language", settings.language);
            if (settings.qualityGuard) body.put("features", new JSONArray().put("quality-guard"));
            if (!settings.pronunciationDictionary.isEmpty()) body.put("pronunciation_dictionary",
                    new JSONArray(FishSpeechSettings.validatePronunciationDictionary(settings.pronunciationDictionary)));
            JSONObject headers = new JSONObject(authentication(settings)).put("model", settings.engine);
            SpeechHttpClient.Resource result;
            if ("references".equals(settings.voiceMode)) {
                List<List<Map<String, Object>>> groups = FishReferenceAudio.read(context, settings, request);
                Map<String, Object> binaryBody = new LinkedHashMap<>();
                java.util.Iterator<String> keys = body.keys();
                while (keys.hasNext()) { String key = keys.next(); binaryBody.put(key, body.get(key)); }
                binaryBody.put("references", groups.size() == 1 ? groups.get(0) : groups);
                if (groups.size() > 1) {
                    List<String> identifiers = new ArrayList<>();
                    for (int index = 0; index < groups.size(); index++) identifiers.add("speaker-" + index);
                    binaryBody.put("reference_id", identifiers);
                }
                byte[] encoded = TtsMessagePack.encode(binaryBody);
                request.ensureActive();
                result = request.postReferenceAudio(root(settings.baseUrl) + "/v1/tts", headers.toString(), encoded, destination);
            } else result = request.postAudioResource(root(settings.baseUrl) + "/v1/tts",
                    headers.toString(), body.toString(), destination, "fish");
            request.ensureActive();
            if (!result.isAudio()) throw invalid("接口未返回完整音频文件");
            complete = true;
            return result;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (FishReferenceAudio.Failure failure) { throw failure; }
        catch (TtsMessagePack.Failure failure) { throw failure; }
        catch (ProtocolFailure failure) { throw failure; }
        catch (Exception failure) { throw sanitized(failure, "合成"); }
        finally {
            if (!complete && destination != null) {
                try { java.nio.file.Files.deleteIfExists(destination.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    public static List<VoiceCatalog.Entry> fetchVoices(SpeechHttpClient.Request request,
                                                       FishSpeechSettings settings) throws IOException {
        return SpeechHttpClient.observeCatalog("fish_voice_catalog", () -> fetchVoicesChecked(request, settings));
    }

    /** One browsable page. Window metadata describes search reach, not the validity of returned voices. */
    public static final class VoicePage {
        public final List<VoiceCatalog.Entry> entries;
        public final List<String> sourceIds;
        public final int page, sourceCount;
        public final long total;
        public final boolean hasMore, moreKnown, windowLimited, totalExact;

        private VoicePage(List<VoiceCatalog.Entry> entries, List<String> sourceIds, int page, int sourceCount,
                          long total, boolean hasMore, boolean moreKnown, boolean windowLimited, boolean totalExact) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.sourceIds = Collections.unmodifiableList(new ArrayList<>(sourceIds));
            this.page = page; this.sourceCount = sourceCount; this.total = total;
            this.hasMore = hasMore; this.moreKnown = moreKnown;
            this.windowLimited = windowLimited; this.totalExact = totalExact;
        }
    }

    public static VoicePage fetchVoicePage(SpeechHttpClient.Request request, FishSpeechSettings settings,
                                          int page) throws IOException {
        return SpeechHttpClient.observeCatalogResult("fish_voice_catalog",
                () -> fetchVoicePageChecked(request, settings, page),
                result -> "entries=" + result.entries.size() + "; page=" + result.page
                        + "; raw_items=" + result.sourceCount + "; more_known=" + result.moreKnown
                        + "; has_more=" + result.hasMore + "; window_limited=" + result.windowLimited
                        + "; total_exact=" + result.totalExact + "; selection_saved=false");
    }

    /** Compatibility helper: bounded complete small catalogs, or the first browsable search window. */
    private static List<VoiceCatalog.Entry> fetchVoicesChecked(SpeechHttpClient.Request request,
                                                              FishSpeechSettings settings) throws IOException {
        Map<String, VoiceCatalog.Entry> voices = new LinkedHashMap<>();
        Set<String> sourceIds = new LinkedHashSet<>();
        long visited = 0;
        for (int number = 1; number <= MAX_VOICE_PAGES; number++) {
            VoicePage page = fetchVoicePageChecked(request, settings, number);
            int previous = sourceIds.size(); sourceIds.addAll(page.sourceIds);
            if (number > 1 && page.sourceCount > 0 && sourceIds.size() == previous) {
                throw invalid("音色分页未前进，请缩小搜索范围或手动填写参考音色 ID");
            }
            for (VoiceCatalog.Entry entry : page.entries) {
                if (!voices.containsKey(entry.id)) voices.put(entry.id, entry);
                if (voices.size() >= MAX_VOICES) return voiceList(voices);
            }
            visited += page.sourceCount;
            boolean more = page.moreKnown ? page.hasMore : page.totalExact && visited < page.total;
            if (!more || page.windowLimited || !page.moreKnown && !page.totalExact) return voiceList(voices);
            if (sourceIds.size() == previous) throw invalid("音色分页未前进，请缩小搜索范围或手动填写参考音色 ID");
        }
        return voiceList(voices);
    }

    private static List<VoiceCatalog.Entry> voiceList(Map<String, VoiceCatalog.Entry> voices) {
        return Collections.unmodifiableList(new ArrayList<>(voices.values()));
    }

    private static VoicePage fetchVoicePageChecked(SpeechHttpClient.Request request,
                                                   FishSpeechSettings settings, int number) throws IOException {
        request.ensureActive();
        settings.validateCatalog();
        try {
            request.ensureActive();
            if (number < 1) throw invalid("音色页码无效");
            String scope = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_SCOPE, settings.voiceScope);
            String search = FishSpeechSettings.validateValue(FishSpeechSettings.VOICE_SEARCH, settings.voiceSearch);
            String base = root(settings.baseUrl) + "/model?page_size=" + PAGE_SIZE + "&self=" + "self".equals(scope);
            if (!search.isEmpty()) base += "&title=" + Uri.encode(search);
            for (String language : settings.voiceLanguages) base += "&language=" + Uri.encode(language);
            for (String language : settings.voiceTitleLanguages) base += "&title_language=" + Uri.encode(language);
            for (String tag : settings.voiceTags) base += "&tag=" + Uri.encode(tag);
            if (!settings.voiceAuthorId.isEmpty()) base += "&author_id=" + Uri.encode(settings.voiceAuthorId);
            if (!settings.voiceLicensed.isEmpty()) base += "&licensed=" + Uri.encode(settings.voiceLicensed);
            base += "&sort_by=" + Uri.encode(settings.voiceSort);
            String authentication = "public".equals(scope) && settings.apiKey.isEmpty() ? "{}" : authentication(settings);
            JSONObject response = new JSONObject(request.getProviderJson(base + "&page_number=" + number, authentication, "fish"));
            Object raw = response.opt("items"), totalValue = response.opt("total");
            if (!(raw instanceof JSONArray) || !nonnegativeInteger(totalValue)) throw invalid("音色列表格式无效");
            boolean limited = optionalBoolean(response, "window_limited", false);
            boolean exact = optionalBoolean(response, "total_is_exact", true);
            JSONArray items = (JSONArray) raw;
            if (items.length() > PAGE_SIZE) throw invalid("音色列表分页格式无效");
            Map<String, VoiceCatalog.Entry> voices = new LinkedHashMap<>();
            Set<String> sourceIds = new LinkedHashSet<>();
            for (int index = 0; index < items.length(); index++) {
                request.ensureActive();
                JSONObject item = items.optJSONObject(index);
                if (item == null || !(item.opt("_id") instanceof String)) throw invalid("音色列表格式无效");
                String id = FishSpeechSettings.validateValue(FishSpeechSettings.REFERENCE_ID, item.getString("_id"));
                if (id.isEmpty()) throw invalid("音色列表缺少参考音色 ID");
                sourceIds.add(id);
                if (!(item.opt("type") instanceof String) || !(item.opt("state") instanceof String)) {
                    throw invalid("音色列表缺少类型或就绪状态，请手动填写已就绪的 TTS 音色 ID");
                }
                if (!"tts".equals(item.getString("type")) || !"trained".equals(item.getString("state"))) continue;
                String name = item.opt("title") instanceof String ? item.getString("title") : id;
                StringBuilder clean = new StringBuilder(name.length());
                for (int character = 0; character < name.length(); character++) {
                    char value = name.charAt(character);
                    clean.append(Character.isISOControl(value) ? ' ' : value);
                }
                name = clean.toString().trim();
                if (name.isEmpty()) name = id;
                if (name.length() > 512) name = name.substring(0, 512);
                if (!voices.containsKey(id)) voices.put(id, new VoiceCatalog.Entry(id, name, VoiceCatalogLabels.fish(item)));
            }
            Object more = response.opt("has_more");
            boolean known = more instanceof Boolean;
            if (!known && more != null && more != JSONObject.NULL) throw invalid("音色列表分页格式无效");
            request.ensureActive();
            return new VoicePage(new ArrayList<>(voices.values()), new ArrayList<>(sourceIds), number, items.length(),
                    ((Number) totalValue).longValue(), known && (Boolean) more, known, limited, exact);
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (ProtocolFailure failure) { throw failure; }
        catch (Exception failure) { throw sanitized(failure, "音色列表"); }
    }

    private static boolean nonnegativeInteger(Object value) {
        if (!(value instanceof Number)) return false;
        double number = ((Number) value).doubleValue();
        return Double.isFinite(number) && number >= 0 && number < Long.MAX_VALUE
                && number == ((Number) value).longValue();
    }

    private static boolean optionalBoolean(JSONObject object, String key, boolean fallback) {
        Object value = object.opt(key);
        if (value == null) return fallback;
        if (!(value instanceof Boolean)) throw invalid("音色列表分页格式无效");
        return (Boolean) value;
    }

    private static String root(String base) {
        try {
            String normalized = FishSpeechSettings.validateValue(FishSpeechSettings.BASE_URL, base);
            return normalized.endsWith("/v1") ? normalized.substring(0, normalized.length() - 3) : normalized;
        } catch (Exception invalid) { throw invalid("API 地址无效，请检查接口设置"); }
    }

    private static String authentication(FishSpeechSettings settings) throws Exception {
        String key = FishSpeechSettings.validateValue(FishSpeechSettings.API_KEY, settings.apiKey);
        if (key.isEmpty()) throw invalid("请设置 API Key");
        return new JSONObject().put("Authorization", "Bearer " + key).toString();
    }

    private static IOException sanitized(Exception failure, String stage) {
        String message = failure.getMessage();
        Matcher status = HTTP_STATUS.matcher(message == null ? "" : message);
        if (status.find()) {
            int code = Integer.parseInt(status.group(1));
            String hint = code == 401 ? "，请检查 API Key" : code == 402 ? "，请检查账户余额"
                    : code == 403 || code == 404 ? "，请检查账户权限、参考音色和引擎"
                    : code == 400 || code == 422 ? "，请检查引擎、参考音色及生成参数"
                    : code == 429 || code == 503 ? "，并发或容量受限，请稍后再试"
                    : code == 504 ? "，生成超时，远端结果未确认" : "";
            return new IOException("Fish Audio 语音" + stage + "返回 HTTP " + code + hint);
        }
        if (message != null && message.contains("响应不完整")) return new IOException("Fish Audio 语音响应不完整");
        if (message != null && message.contains("请求超时")) return new IOException("Fish Audio 语音" + stage + "请求超时");
        return new IOException("Fish Audio 语音" + stage + "失败，请检查配置及接口");
    }

    private static ProtocolFailure invalid(String message) { return new ProtocolFailure(message); }

    private static final class ProtocolFailure extends IllegalArgumentException {
        ProtocolFailure(String message) { super("Fish Audio 语音" + message); }
    }
}
