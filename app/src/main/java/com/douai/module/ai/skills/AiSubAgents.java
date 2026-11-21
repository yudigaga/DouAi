package com.douai.module.ai.skills;

import com.douai.module.config.AiModelSettings;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.ChatCompletionClient;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.SocketTimeoutException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

/** One explicitly selected model, one optional local tool, and one shared deadline. */
public final class AiSubAgents {
    private static final String SELECTION_KEY = "ai_subagent_selection_v1";
    private static final int MAX_TASK = 2000;
    private static final int MAX_OUTPUT_TOKENS = 600;
    public static final long TIME_LIMIT_NANOS = TimeUnit.SECONDS.toNanos(30);
    private static final String INSTRUCTION = """
            你是 DouAi 的子代理。只使用收到的任务文本和工具返回值，返回一个 JSON 对象。
            完成时返回 {"type":"result","text":"最终回答"}。
            需要工具且 allow 字段列出它时，可返回 {"type":"tool","name":"工具名","argument":"文本参数"}。
            summary 提取纯文本摘要，mock_orders 查询模拟订单号。每项任务最多调用一次工具。
            收到 toolResult 后只能返回最终回答。不要委派、调用其他工具或输出代码围栏。
            """;

    public record Selection(String modelId, boolean summaryAllowed, boolean mockOrderAllowed) {
        public Selection {
            if (modelId == null || modelId.length() > 128
                    || modelId.codePoints().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException("子代理模型标识无效");
            }
        }
        public boolean enabled() { return !modelId.isEmpty(); }
    }

    public static final class Failure extends Exception {
        Failure(String message) { super(message); }
    }

    interface Gateway {
        String complete(AiModelSettings models, String modelId, String instruction, String input,
                        long deadline, int outputTokens) throws Exception;
    }

    private AiSubAgents() {}

    public static Selection read(SettingsStore store) {
        if (store == null) return new Selection("", false, false);
        try {
            String saved = store.getString(SELECTION_KEY, "");
            if (saved.isEmpty()) return new Selection("", false, false);
            JSONObject value = new JSONObject(saved);
            return new Selection(value.getString("modelId"),
                    value.optBoolean("summary", false), value.optBoolean("mockOrders", false));
        } catch (JSONException | RuntimeException invalid) {
            return new Selection("", false, false);
        }
    }

    public static void save(SettingsStore store, Selection selection) {
        try {
            store.putString(SELECTION_KEY, new JSONObject().put("modelId", selection.modelId())
                    .put("summary", selection.enabled() && selection.summaryAllowed())
                    .put("mockOrders", selection.enabled() && selection.mockOrderAllowed()).toString());
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("子代理配置无法保存");
        }
    }

    public static String run(AiModelSettings models, Selection selection, String task, long deadline) throws Exception {
        return run(models, selection, task, deadline,
                (all, id, instruction, input, until, tokens) ->
                        new ChatCompletionClient.IsolatedTextRequest().execute(all, id, instruction, input, until, tokens));
    }

    static String run(AiModelSettings models, Selection selection, String task, long deadline,
                      Gateway gateway) throws Exception {
        checkLive(deadline);
        if (!selection.enabled() || models.configs.stream().noneMatch(model -> model.id.equals(selection.modelId()))) {
            throw new Failure("请重新选择子代理模型");
        }
        if (task == null || task.trim().isEmpty() || task.length() > MAX_TASK) {
            throw new Failure("委派任务需要 1–2000 个字符的文本");
        }

        org.json.JSONArray allow = new org.json.JSONArray();
        if (selection.summaryAllowed()) allow.put("summary");
        if (selection.mockOrderAllowed()) allow.put("mock_orders");
        JSONObject input = new JSONObject().put("task", task).put("allow", allow);
        JSONObject response = request(models, selection, input, deadline, gateway);
        if ("result".equals(response.opt("type"))) return answer(response);
        if (!"tool".equals(response.opt("type")) || response.length() != 3) {
            throw new Failure("子代理请求的操作无效");
        }

        checkLive(deadline);
        String name = text(response.opt("name"), 64);
        String argument = text(response.opt("argument"), 1000);
        String output;
        try {
            if ("summary".equals(name) && selection.summaryAllowed()) output = AiSkills.summarize(argument);
            else if ("mock_orders".equals(name) && selection.mockOrderAllowed()) output = AiSkills.lookupOrder(argument);
            else throw new Failure("子代理请求了未授权的工具");
        } catch (IllegalArgumentException invalid) {
            throw new Failure("子代理工具参数无效");
        }

        input.put("allow", new org.json.JSONArray())
                .put("toolResult", new JSONObject().put("name", name).put("text", output));
        return answer(request(models, selection, input, deadline, gateway));
    }

    private static JSONObject request(AiModelSettings models, Selection selection, JSONObject input,
                                      long deadline, Gateway gateway) throws Exception {
        checkLive(deadline);
        String raw = gateway.complete(models, selection.modelId(), INSTRUCTION, input.toString(),
                deadline, MAX_OUTPUT_TOKENS);
        checkLive(deadline);
        if (raw == null || raw.length() > 8000) throw new Failure("子代理回复超出长度");
        try {
            JSONTokener json = new JSONTokener(raw);
            Object value = json.nextValue();
            if (value instanceof JSONObject object && json.nextClean() == 0) return object;
        } catch (JSONException invalid) {
            // The provider response may contain unrestricted text. Keep the error fixed.
        }
        throw new Failure("子代理回复的格式无效");
    }

    private static String answer(JSONObject response) throws Failure {
        if (!"result".equals(response.opt("type")) || response.length() != 2) {
            throw new Failure("子代理已用完工具调用额度");
        }
        return text(response.opt("text"), 4000);
    }

    private static String text(Object value, int maximum) throws Failure {
        if (value instanceof String text && !text.trim().isEmpty() && text.length() <= maximum
                && text.indexOf(0) < 0) return text;
        throw new Failure("子代理回复的文本字段无效");
    }

    private static void checkLive(long deadline) throws SocketTimeoutException {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("子代理已停止");
        if (deadline - System.nanoTime() <= 0) throw new SocketTimeoutException("子代理已超过任务截止时间");
    }
}
