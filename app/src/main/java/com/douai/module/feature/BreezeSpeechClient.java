package com.douai.module.feature;

import android.net.Uri;

import com.douai.module.config.BreezeSpeechSettings;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Breeze HTTP synthesis. Async delivery submits once and polls only its owned job. */
public final class BreezeSpeechClient {
    private static final int MAX_MODELS = 512;
    private static final int MAX_VOICES = 2048;
    private static final int MAX_VOICE_PAGES = 32;
    private static final int VOICE_PAGE_SIZE = 20;
    private static final long ASYNC_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(55);
    private static final long CATALOG_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(45);
    private static final Pattern HTTP_STATUS = Pattern.compile(
            "^(?:Breeze Blue 语音 (?:POST|GET)|第三方语音 GET) 返回 HTTP ([0-9]{3})$");

    private BreezeSpeechClient() { }

    public static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                       BreezeSpeechSettings settings) throws IOException {
        return synthesize(request, text, settings, null);
    }

    static SpeechHttpClient.Resource synthesize(SpeechHttpClient.Request request, String text,
                                                BreezeSpeechSettings settings, File destination) throws IOException {
        boolean complete = false;
        boolean deadlineLimited = false;
        try {
            request.ensureActive();
            long deadline = System.nanoTime() + ASYNC_BUDGET_NANOS;
            try { settings.validateForSynthesis(); }
            catch (IllegalArgumentException configuration) {
                // Configuration validators use fixed messages and never echo stored values.
                throw invalid("配置无效：" + configuration.getMessage());
            }
            if (text == null || text.trim().isEmpty()) throw invalid("合成文本为空");
            if (text.codePointCount(0, text.length()) > 2000) throw invalid("合成文本超过接口最高 2000 字符限制");
            JSONObject voices = new JSONObject().put("guidance_scale", settings.effectiveGuidanceScale())
                    .put("speed", settings.speed).put("volume", settings.volume)
                    .put("use_speaker_boost", settings.effectiveSpeakerBoost());
            JSONObject body = new JSONObject().put("text", text).put("voice_settings", voices);
            if (!BreezeSpeechSettings.AUTO_MODEL.equals(settings.model)) body.put("model_id", settings.model);
            if (!settings.instructions.isEmpty()) body.put("instructions", settings.instructions);
            if (!settings.languageCode.isEmpty()) body.put("language_code", settings.languageCode);
            String base = endpoint(settings.baseUrl, "text-to-speech/" + Uri.encode(settings.voiceId));
            String authentication = headers(settings);
            SpeechHttpClient.Resource result;
            if ("async".equals(settings.delivery)) {
                request.limitDeadline(deadline, "breeze");
                deadlineLimited = true;
                String accepted = request.postProviderJson(base + "?delivery=async&output_format=" + Uri.encode(settings.outputFormat),
                        authentication, body.toString(), "breeze", 202);
                result = awaitAudio(request, settings, authentication, new JSONObject(accepted), destination, deadline);
            } else {
                String url = "stream".equals(settings.delivery)
                        ? base + "/stream?output_format=" + Uri.encode(settings.outputFormat)
                        : base + "?delivery=sync&output_format=" + Uri.encode(settings.outputFormat);
                result = request.postAudioResource(url, authentication, body.toString(), destination);
            }
            request.ensureActive();
            if (!result.isAudio()) throw invalid("接口未返回完整音频文件");
            complete = true;
            return result;
        } catch (CancellationException cancelled) {
            throw cancelled;
        } catch (ProtocolFailure failure) {
            throw failure;
        } catch (Exception failure) {
            throw sanitized(request, failure, "合成");
        } finally {
            if (deadlineLimited) request.clearDeadline();
            if (!complete && destination != null) {
                try { java.nio.file.Files.deleteIfExists(destination.toPath()); } catch (IOException ignored) { }
            }
        }
    }

    /** Enhances only the user's instruction; callers explicitly decide whether to save the draft. */
    public static String enhanceInstruction(SpeechHttpClient.Request request, BreezeSpeechSettings settings,
                                             String instruction) throws IOException {
        try {
            request.ensureActive();
            String candidate = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.INSTRUCTIONS, instruction);
            if (candidate.isEmpty() || candidate.codePointCount(0, candidate.length()) > 1000) {
                throw invalid("指令优化需要 1 至 1000 字符的语音指令");
            }
            JSONObject body = new JSONObject().put("instruction", candidate);
            String language = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.LANGUAGE_CODE, settings.languageCode);
            if (!language.isEmpty()) body.put("language_code", language);
            JSONObject response = new JSONObject(request.postProviderJson(endpoint(settings.baseUrl, "text-to-speech/enhance"),
                    headers(settings), body.toString(), "breeze", 200));
            if (!(response.opt("instruction") instanceof String) || !(response.opt("language_code") instanceof String)) {
                throw invalid("指令优化响应格式无效");
            }
            String enhanced = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.INSTRUCTIONS, response.getString("instruction"));
            if (enhanced.isEmpty()) throw invalid("指令优化返回了空指令");
            request.ensureActive();
            return enhanced;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (ProtocolFailure failure) { throw failure; }
        catch (Exception failure) { throw sanitized(request, failure, "指令优化"); }
    }

    private static SpeechHttpClient.Resource awaitAudio(SpeechHttpClient.Request request, BreezeSpeechSettings settings,
                                                         String authentication, JSONObject accepted, File destination,
                                                         long deadline) throws Exception {
        String jobId = jobId(accepted);
        String jobUrl = endpoint(settings.baseUrl, "generation-jobs/" + Uri.encode(jobId));
        // Poll status, never resubmit synthesis, and never trust a response-provided download URL with this key.
        for (;;) {
            ensureJobActive(request, deadline);
            JSONObject job = new JSONObject(request.getProviderJson(jobUrl, authentication, "breeze"));
            ensureJobActive(request, deadline);
            if (!jobId.equals(jobId(job)) || !(job.opt("status") instanceof String)) throw invalid("异步任务响应格式无效");
            String status = job.getString("status");
            if ("ready".equals(status)) {
                try {
                    SpeechHttpClient.Resource audio = request.getProviderAudio(jobUrl + "/audio", authentication, destination, "breeze");
                    ensureJobActive(request, deadline);
                    return audio;
                } catch (SpeechHttpClient.ProviderHttpFailure notReady) {
                    if (notReady.status != 425) throw notReady;
                    pauseJob(request, deadline, Math.max(1, notReady.retryAfterSeconds));
                    continue;
                }
            }
            if ("failed".equals(status)) throw invalid("异步任务生成失败，未再次提交生成");
            if ("cancelled".equals(status)) throw invalid("异步任务已取消，未再次提交生成");
            if (status.isEmpty() || status.length() > 128 || status.codePoints().anyMatch(Character::isISOControl)) {
                throw invalid("异步任务响应格式无效");
            }
            // Nonterminal names are not enumerated in the TTS schema. Preserve forward compatibility within one deadline.
            pauseJob(request, deadline, 1);
        }
    }

    private static void pauseJob(SpeechHttpClient.Request request, long deadline, int seconds) throws IOException {
        ensureJobActive(request, deadline);
        long now = System.nanoTime();
        long until = now + Math.min(deadline - now, TimeUnit.SECONDS.toNanos(Math.max(0, seconds)));
        while (System.nanoTime() < until) {
            ensureJobActive(request, deadline);
            long remaining = until - System.nanoTime();
            try { Thread.sleep(Math.max(1, Math.min(250, TimeUnit.NANOSECONDS.toMillis(remaining)))); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                request.ensureActive();
                throw new CancellationException("Breeze Blue 语音异步等待已停止");
            }
        }
        ensureJobActive(request, deadline);
    }

    private static String jobId(JSONObject response) throws Exception {
        if (!(response.opt("generation_job_id") instanceof String)) throw invalid("异步任务缺少有效编号");
        String id = response.getString("generation_job_id");
        if (id.isEmpty() || id.length() > 4096 || id.codePoints().anyMatch(Character::isISOControl)) {
            throw invalid("异步任务缺少有效编号");
        }
        return id;
    }

    private static void ensureJobActive(SpeechHttpClient.Request request, long deadline) throws IOException {
        request.ensureActive();
        if (System.nanoTime() >= deadline) throw new IOException("Breeze Blue 语音合成请求超时");
    }

    public static List<String> fetchModels(SpeechHttpClient.Request request, BreezeSpeechSettings settings) throws IOException {
        boolean deadlineLimited = false;
        try {
            request.ensureActive();
            request.limitDeadline(System.nanoTime() + CATALOG_BUDGET_NANOS, "breeze");
            deadlineLimited = true;
            return SpeechHttpClient.observeCatalog("breeze_model_catalog", () -> {
                try {
                    request.ensureActive();
                    JSONArray values = new JSONArray(request.getProviderJson(endpoint(settings.baseUrl, "models"), headers(settings), "breeze"));
                    Set<String> ids = new java.util.LinkedHashSet<>();
                    for (int index = 0; index < values.length(); index++) {
                        request.ensureActive();
                        JSONObject item = values.optJSONObject(index);
                        if (item == null || !(item.opt("model_id") instanceof String)) continue;
                        String id = item.getString("model_id").trim();
                        if (id.isEmpty()) continue;
                        ids.add(BreezeSpeechSettings.validateValue(BreezeSpeechSettings.MODEL, id));
                        if (ids.size() > MAX_MODELS) throw invalid("模型列表超过 " + MAX_MODELS + " 项");
                    }
                    if (ids.isEmpty()) throw invalid("模型列表为空，可手动填写模型");
                    request.ensureActive();
                    return Collections.unmodifiableList(new ArrayList<>(ids));
                } catch (CancellationException cancelled) { throw cancelled; }
                catch (ProtocolFailure failure) { throw failure; }
                catch (Exception failure) { throw sanitized(request, failure, "模型列表"); }
            });
        } finally {
            if (deadlineLimited) request.clearDeadline();
        }
    }

    /** One native Breeze voice page; source progress is separate from deduplicated display entries. */
    public static final class VoicePage {
        public final List<VoiceCatalog.Entry> entries;
        public final List<String> sourceIds;
        public final int page, sourceCount, nextPage;
        public final String nextPageToken;
        public final boolean hasMore;

        private VoicePage(List<VoiceCatalog.Entry> entries, List<String> sourceIds, int page,
                          int sourceCount, int nextPage, String nextPageToken, boolean hasMore) {
            this.entries = Collections.unmodifiableList(new ArrayList<>(entries));
            this.sourceIds = Collections.unmodifiableList(new ArrayList<>(sourceIds));
            this.page = page; this.sourceCount = sourceCount; this.nextPage = nextPage;
            this.nextPageToken = nextPageToken; this.hasMore = hasMore;
        }
    }

    public static VoicePage fetchVoicePage(SpeechHttpClient.Request request, BreezeSpeechSettings settings,
                                          int page, String nextPageToken) throws IOException {
        return fetchVoicePage(request, settings, page, nextPageToken, CATALOG_BUDGET_NANOS);
    }

    /** Bounded seam used by catalog tests; production always installs the 45-second owner budget. */
    static VoicePage fetchVoicePage(SpeechHttpClient.Request request, BreezeSpeechSettings settings,
                                   int page, String nextPageToken, long budgetNanos) throws IOException {
        boolean deadlineLimited = false;
        try {
            request.ensureActive();
            if (budgetNanos <= 0 || budgetNanos > CATALOG_BUDGET_NANOS) throw invalid("音色目录等待预算无效");
            request.limitDeadline(System.nanoTime() + budgetNanos, "breeze");
            deadlineLimited = true;
            return SpeechHttpClient.observeCatalogResult("breeze_voice_catalog",
                    () -> fetchVoicePageChecked(request, settings, page, nextPageToken),
                    result -> "entries=" + result.entries.size() + "; page=" + result.page
                            + "; raw_items=" + result.sourceCount + "; has_more=" + result.hasMore
                            + "; next_page=" + result.nextPage + "; cursor_present=" + !result.nextPageToken.isEmpty()
                            + "; selection_saved=false");
        } finally {
            if (deadlineLimited) request.clearDeadline();
        }
    }

    public static List<VoiceCatalog.Entry> fetchVoices(SpeechHttpClient.Request request,
                                                       BreezeSpeechSettings settings) throws IOException {
        boolean deadlineLimited = false;
        try {
            request.ensureActive();
            request.limitDeadline(System.nanoTime() + CATALOG_BUDGET_NANOS, "breeze");
            deadlineLimited = true;
            return SpeechHttpClient.observeCatalog("breeze_voice_catalog", () -> fetchVoicesChecked(request, settings));
        } finally {
            if (deadlineLimited) request.clearDeadline();
        }
    }

    /** Compatibility helper for callers explicitly requesting a bounded complete catalog. */
    private static List<VoiceCatalog.Entry> fetchVoicesChecked(SpeechHttpClient.Request request,
                                                              BreezeSpeechSettings settings) throws IOException {
        Map<String, VoiceCatalog.Entry> voices = new LinkedHashMap<>();
        Set<String> sourceIds = new HashSet<>(), tokens = new HashSet<>();
        int number = 1;
        String token = "";
        for (int fetched = 0; fetched < MAX_VOICE_PAGES; fetched++) {
            VoicePage page = fetchVoicePageChecked(request, settings, number, token);
            int previous = sourceIds.size(); sourceIds.addAll(page.sourceIds);
            if (fetched > 0 && page.sourceCount > 0 && sourceIds.size() == previous) throw invalid("音色分页未前进");
            for (VoiceCatalog.Entry entry : page.entries) {
                if (!voices.containsKey(entry.id)) voices.put(entry.id, entry);
                if (voices.size() > MAX_VOICES) throw invalid("音色列表超过 " + MAX_VOICES + " 项，可手动填写音色 ID");
            }
            if (!page.hasMore) {
                request.ensureActive();
                return Collections.unmodifiableList(new ArrayList<>(voices.values()));
            }
            if (sourceIds.size() == previous) throw invalid("音色分页未前进");
            if (!page.nextPageToken.isEmpty() && !tokens.add(page.nextPageToken)) throw invalid("音色分页游标未前进");
            if (page.nextPageToken.isEmpty() && token.isEmpty() && page.nextPage <= number) throw invalid("音色分页页码未前进");
            number = page.nextPage; token = page.nextPageToken;
        }
        throw invalid("音色列表超过 " + MAX_VOICE_PAGES + " 页，可手动填写音色 ID");
    }

    private static VoicePage fetchVoicePageChecked(SpeechHttpClient.Request request, BreezeSpeechSettings settings,
                                                   int number, String token) throws IOException {
        request.ensureActive();
        settings.validateCatalog();
        try {
            request.ensureActive();
            if (number < 1) throw invalid("音色页码无效");
            String requestedToken = catalogToken(token);
            String type = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_TYPE, settings.voiceType);
            String search = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_SEARCH, settings.voiceSearch);
            String language = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_LANGUAGE, settings.voiceLanguage);
            String url = endpoint(settings.baseUrl, "voices") + "?voice_type=" + Uri.encode(type) + "&page_size=" + VOICE_PAGE_SIZE;
            if (!search.isEmpty()) url += "&search=" + Uri.encode(search);
            if (!language.isEmpty()) url += "&language_code=" + Uri.encode(language);
            for (String gender : settings.voiceGenders) url += "&gender=" + Uri.encode(gender);
            for (String age : settings.voiceAges) url += "&age=" + Uri.encode(age);
            for (String tone : settings.voiceTones) url += "&tone=" + Uri.encode(tone);
            for (String tag : settings.voiceTags) url += "&tags=" + Uri.encode(tag);
            if (!settings.voiceCategory.isEmpty()) url += "&primary_category_code=" + Uri.encode(settings.voiceCategory);
            if (!settings.voiceAccent.isEmpty()) url += "&accent=" + Uri.encode(settings.voiceAccent);
            url += "&accent_mode=" + Uri.encode(settings.voiceAccentMode);
            if (!settings.voiceOrigin.isEmpty()) url += "&origin=" + Uri.encode(settings.voiceOrigin);
            url += "&sort=" + Uri.encode(settings.voiceSort) + "&sort_direction=" + Uri.encode(settings.voiceSortDirection)
                    + "&favorites_only=" + settings.voiceFavoritesOnly;
            url += requestedToken.isEmpty() ? "&page=" + number : "&next_page_token=" + Uri.encode(requestedToken);
            JSONObject response = new JSONObject(request.getProviderJson(url, headers(settings), "breeze"));
            Object rawVoices = response.opt("voices"), more = response.opt("has_more");
            if (!(rawVoices instanceof JSONArray) || !(more instanceof Boolean)) throw invalid("音色列表格式无效");
            JSONArray items = (JSONArray) rawVoices;
            if (items.length() > VOICE_PAGE_SIZE) throw invalid("音色列表分页格式无效");
            Map<String, VoiceCatalog.Entry> voices = new LinkedHashMap<>();
            Set<String> sourceIds = new java.util.LinkedHashSet<>();
            for (int index = 0; index < items.length(); index++) {
                request.ensureActive();
                JSONObject item = items.optJSONObject(index);
                if (item == null || !(item.opt("voice_id") instanceof String)) continue;
                String id = item.getString("voice_id").trim();
                if (id.isEmpty()) continue;
                id = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.VOICE_ID, id);
                sourceIds.add(id);
                String name = item.opt("name") instanceof String ? item.getString("name").trim() : id;
                StringBuilder label = new StringBuilder(name.length());
                for (int character = 0; character < name.length(); character++) {
                    char value = name.charAt(character);
                    label.append(Character.isISOControl(value) ? ' ' : value);
                }
                name = label.toString().trim();
                if (name.isEmpty()) name = id;
                if (name.length() > 512) name = name.substring(0, 512);
                if (!voices.containsKey(id)) voices.put(id, new VoiceCatalog.Entry(id, name, VoiceCatalogLabels.breeze(item)));
            }
            Object rawToken = response.opt("next_page_token");
            if (rawToken != null && rawToken != JSONObject.NULL && !(rawToken instanceof String)) throw invalid("音色分页游标无效");
            String nextToken = catalogToken(rawToken instanceof String ? (String) rawToken : "");
            boolean hasMore = (Boolean) more;
            int nextPage = 0;
            if (hasMore) {
                if (!nextToken.isEmpty()) {
                    if (number == Integer.MAX_VALUE) throw invalid("音色页码无效");
                    nextPage = number + 1;
                } else {
                    Object current = response.opt("page");
                    if (!(current instanceof Number) || ((Number) current).doubleValue() != ((Number) current).intValue()
                            || ((Number) current).intValue() < 1 || ((Number) current).intValue() == Integer.MAX_VALUE) {
                        throw invalid("音色分页缺少有效页码或游标");
                    }
                    nextPage = ((Number) current).intValue() + 1;
                }
            } else nextToken = "";
            request.ensureActive();
            return new VoicePage(new ArrayList<>(voices.values()), new ArrayList<>(sourceIds), number,
                    items.length(), nextPage, nextToken, hasMore);
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (ProtocolFailure failure) { throw failure; }
        catch (Exception failure) { throw sanitized(request, failure, "音色列表"); }
    }

    private static String catalogToken(String token) {
        String value = token == null ? "" : token;
        if (value.length() > 8192 || value.codePoints().anyMatch(Character::isISOControl)) throw invalid("音色分页游标无效");
        return value;
    }

    private static String endpoint(String base, String operation) {
        try {
            String normalized = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.BASE_URL, base);
            URI uri = URI.create(normalized);
            if (uri.getHost() == null) throw new IllegalArgumentException();
            return normalized + (normalized.endsWith("/v1") ? "/" : "/v1/") + operation;
        } catch (Exception ignored) { throw invalid("API 地址无效，请检查接口设置"); }
    }

    private static String headers(BreezeSpeechSettings settings) throws Exception {
        String key = BreezeSpeechSettings.validateValue(BreezeSpeechSettings.API_KEY, settings.apiKey);
        if (key.isEmpty()) throw invalid("请设置 API Key");
        return new JSONObject().put("xi-api-key", key).toString();
    }

    private static IOException sanitized(SpeechHttpClient.Request request, Exception failure, String stage) {
        // Disconnect can race with response-header access or connection cleanup, which may throw a
        // runtime exception outside the transport's IO catch. Cancellation still owns that outcome.
        try { request.ensureActive(); }
        catch (CancellationException cancelled) { throw cancelled; }
        catch (IllegalArgumentException expired) { return sanitized(expired, stage); }
        return sanitized(failure, stage);
    }

    private static IOException sanitized(Exception failure, String stage) {
        String message = failure.getMessage();
        Matcher status = HTTP_STATUS.matcher(message == null ? "" : message);
        if (status.find()) {
            int code = Integer.parseInt(status.group(1));
            String hint = code == 401 ? "，请检查 API Key" : code == 402 ? "，请检查余额或 Key 预算"
                    : code == 403 || code == 404 ? "，请检查账户权限、音色和模型"
                    : code == 400 || code == 422 ? "，请检查模型、音色、语言及文本长度"
                    : code == 429 || code == 503 ? "，并发或容量受限，请稍后再试"
                    : code == 504 ? "，生成超时，远端结果未确认" : "";
            return new IOException("Breeze Blue 语音" + stage + "返回 HTTP " + code + hint);
        }
        if (message != null && message.contains("响应不完整")) return new IOException("Breeze Blue 语音响应不完整");
        if (message != null && message.contains("请求超时")) return new IOException("Breeze Blue 语音" + stage + "请求超时");
        return new IOException("Breeze Blue 语音" + stage + "失败，请检查配置及接口");
    }

    private static ProtocolFailure invalid(String message) { return new ProtocolFailure(message); }

    private static final class ProtocolFailure extends IllegalArgumentException {
        ProtocolFailure(String message) { super("Breeze Blue 语音" + message); }
    }
}
