package com.douai.module.ai.skills;

import android.content.Context;
import com.douai.module.ai.mcp.McpRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Publishes a version only after validation, simulation and both documents reach disk. */
public final class SkillStore {
    public static final String GENERATION_INSTRUCTION = """
            你在为 DouAi 生成声明式技能。只返回一个 JSON 对象，别加代码围栏或额外字段。
            对象包含 schemaVersion:2、name（1-24 字，不含空格和斜杠）、description、
            documentation（SKILL.md 正文）、execution 和 trial。
            文本摘要 execution: {kind:"summary",maxInput:4000,outputType:"text"}。
            文本服务 execution: {kind:"service",maxInput:4000,outputType:"text",serviceId:"已登记的文本服务 ID"}。
            图片服务 execution: {kind:"service",maxInput:4000,outputType:"image"}。
            图片需求应声明 image，若指定服务则可加 serviceId，由应用找已授权的匹配服务。
            文本试跑格式为 {input:"测试文本",expected:"完整的模拟输出"}。
            摘要可用同一句短文本作为输入和输出。mock-orders 输入 DA-1001，结果为 模拟查询：订单 DA-1001 已发货。
            其他文本服务的模拟输出为 模拟服务响应。图片试跑格式为 {input:"示例提示词",expectedType:"image"}。
            不得输出代码、接口地址、密钥、请求头或图片数据。
            """;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern IMAGE_INTENT = Pattern.compile(
            "(?i)(?:生图|文生图|画图|绘图|作画|画(?:一[张幅只]|个)|(?:生成|绘制|制作|创作|设计|创建|输出|返回)(?:一[张幅]|几张)?(?:图片|图像|插画|海报)(?!(?:的)?(?:提示词|文案|教程|说明))|(?:generate|create)\\s+(?:an?\\s+)?(?:image|picture))");
    private static final Pattern EXPLAINS_IMAGE = Pattern.compile(
            "^(?:请\\s*)?(?:帮我\\s*)?(?:解释|介绍|总结|查询|整理|写一篇|告诉我|如何|不需要|不要|无需).*");
    private static final Pattern REQUESTED_NAME = Pattern.compile(
            "(?:名为|命名为|叫做)\\s*(?:[「“\"]([\\p{L}\\p{N}_-]{1,24})[」”\"]|([\\p{L}\\p{N}_-]{1,24})(?=\\s|[，。]|$))");
    private static final String NEEDS_IMAGE_SERVICE = "缺少已授权的图片服务";
    private static final String NEEDS_OPERATION = "需要可校验的执行方式，目前支持文本摘要、模拟订单和图片";
    private static final String NEEDS_REPLACEMENT = "离线校验通过，等待确认启用同名新版";
    private static final String TRIAL_FAILED = "离线校验失败，草稿待配置";
    private static final int LIMIT = 96 * 1024;

    public record Installed(String name, int version, boolean enabled, boolean canRollback,
                            SkillResult.Type outputType, String serviceLabel) {}
    public record Preview(String name, String serviceLabel, String endpoint, int costUnits,
                          boolean needsConfirmation, String serviceId, SkillResult.Type outputType,
                          String pendingReason, int previousVersion, int latestVersion) {}
    public record Pending(String name, SkillResult.Type outputType, String reason) {}
    public record SaveResult(Installed installed, Pending pending) {}
    public enum FieldState { FILLED, MISSING, NEEDS_AUTHORIZATION }
    public record Field(String id, String label, boolean required, FieldState state) {}
    public record Detail(String name, SkillResult.Type outputType, String draftId, String reason,
                         List<Field> fields, SkillServices.Service service, boolean canAuthorize,
                         boolean canReplace, int previousVersion, boolean installedEnabled, boolean canRollback) {
        public boolean pending() { return !draftId.isEmpty(); }
    }
    private enum SaveMode { GENERATE, RECONNECT, CONVERSATION }
    private record Configuration(List<Field> fields, boolean canAuthorize) {}
    record Available(AiSkills.Command command, int version) {}
    private record Draft(String name, String description, String documentation, String kind,
                         int maxInput, SkillResult.Type outputType, String serviceId, String trialInput,
                         String expected) {}
    private record Executable(String kind, int maxInput, SkillResult.Type outputType, SkillServices.Service service) {}
    private interface StateAction<T> { T run(JSONObject state) throws Exception; }
    private final File directory;
    private final File catalog;
    private final SkillServices services;
    private final McpRegistry mcp;

    public static SkillStore forContext(Context context) {
        Context app = context.getApplicationContext();
        Context owner = app == null ? context : app;
        return new SkillStore(new File(owner.getNoBackupFilesDir(), "douai-ai/skills"),
                SkillServices.forContext(owner), McpRegistry.forContext(owner));
    }

    SkillStore(File directory, SkillServices services) {
        this(directory, services, null);
    }

    private SkillStore(File directory, SkillServices services, McpRegistry mcp) {
        this.directory = directory;
        this.catalog = new File(directory, "catalog.json");
        this.services = services;
        this.mcp = mcp;
    }

    McpRegistry mcp() { return mcp; }

    public boolean enabled() {
        return read(state -> state.getBoolean("enabled"));
    }

    public void setEnabled(boolean enabled) {
        read(state -> { state.put("enabled", enabled); writeState(state); return null; });
    }

    public List<Installed> list() {
        return read(state -> {
            List<Installed> entries = new ArrayList<>();
            JSONObject skills = state.getJSONObject("skills");
            for (Iterator<String> names = skills.keys(); names.hasNext();) {
                String name = names.next();
                entries.add(installed(name, skills.getJSONObject(name)));
            }
            return entries;
        });
    }

    List<Available> available() {
        return read(state -> {
            if (!state.getBoolean("enabled")) return Collections.emptyList();
            List<Available> entries = new ArrayList<>();
            JSONObject skills = state.getJSONObject("skills");
            for (Iterator<String> names = skills.keys(); names.hasNext();) {
                String name = names.next();
                JSONObject skill = skills.getJSONObject(name);
                if (!skill.getBoolean("enabled")) continue;
                JSONObject revision = activeRevision(skill);
                entries.add(new Available(new AiSkills.Command(name, revision.getString("description"), resultType(revision)),
                        revision.getInt("version")));
            }
            return entries;
        });
    }

    public void setSkillEnabled(String name, boolean enabled) {
        read(state -> { skill(state, name).put("enabled", enabled); writeState(state); return null; });
    }

    public Installed rollback(String name) {
        return read(state -> {
            JSONObject skill = skill(state, name);
            int active = skill.getInt("active");
            if (active <= 0) throw new IllegalStateException("没有上一版本");
            skill.put("active", active - 1);
            writeState(state);
            return installed(name, skill);
        });
    }

    public String generationPrompt(String requirements) throws Exception {
        String input = text(requirements == null ? "" : requirements.trim(), 4000);
        JSONArray metadata = new JSONArray();
        for (SkillServices.Service service : services.list()) {
            metadata.put(new JSONObject().put("id", service.id()).put("name", service.label())
                    .put("outputType", service.outputType().wireName()));
        }
        return new JSONObject().put("requirements", input).put("registeredServices", metadata).toString();
    }

    public static boolean isExplicitCreation(String prompt) {
        return SkillCreationRequest.requirement(prompt) != null;
    }

    public SaveResult createFromConversation(String prompt) throws Exception {
        String requirement = SkillCreationRequest.requirement(prompt);
        if (requirement == null) throw new IllegalArgumentException("请输入明确的 Skill 创建请求");
        if (requiresImage(requirement)) return saveGenerated(mockDraft(requirement), null, SaveMode.CONVERSATION);
        if (requirement.contains("摘要") || requirement.contains("总结")) {
            return saveGenerated(localTextDraft(requirement, false), null, SaveMode.CONVERSATION);
        }
        if (requirement.contains("订单") && (requirement.contains("模拟") || requirement.contains("查询"))) {
            return saveGenerated(localTextDraft(requirement, true), null, SaveMode.CONVERSATION);
        }
        String fallback = requirement.matches("[\\p{IsHan}]{1,24}") ? requirement : "新技能";
        String json = new JSONObject().put("schemaVersion", 2).put("name", requestedName(requirement, fallback))
                .put("description", "需要可校验的执行方式").put("documentation", "待补充受支持的技能用途。")
                .put("execution", new JSONObject().put("kind", "unconfigured").put("maxInput", 4000)
                        .put("outputType", "text"))
                .put("trial", new JSONObject().put("input", "示例").put("expected", "待配置"))
                .toString();
        return saveGenerated(json, null, SaveMode.CONVERSATION);
    }

    private static String localTextDraft(String requirement, boolean orders) throws Exception {
        JSONObject draft = new JSONObject(mockDraft(orders ? "查询模拟订单" : "摘要"));
        String fallback = orders ? "模拟订单" : "文本摘要";
        String name = requestedName(requirement, fallback);
        return draft.put("name", name).toString();
    }

    private static String requestedName(String requirement, String fallback) {
        Matcher name = REQUESTED_NAME.matcher(requirement);
        return name.find() ? (name.group(1) == null ? name.group(2) : name.group(1)) : fallback;
    }
    public static boolean requiresImage(String requirements) {
        if (requirements == null) return false;
        String input = requirements.trim();
        return !EXPLAINS_IMAGE.matcher(input).lookingAt() && IMAGE_INTENT.matcher(input).find();
    }

    public static String mockDraft(String requirements) throws Exception {
        String input = text(requirements == null ? "" : requirements.trim(), 4000);
        if (requiresImage(input)) {
            String command = requestedName(input, "生图");
            return new JSONObject().put("schemaVersion", 2).put("name", command)
                    .put("description", "根据提示词生成图片")
                    .put("documentation", "输入图片提示词，使用已授权的图片服务。")
                    .put("execution", new JSONObject().put("kind", "service")
                            .put("maxInput", 4000).put("outputType", "image"))
                    .put("trial", new JSONObject().put("input", "一只猫在窗边").put("expectedType", "image"))
                    .toString();
        }
        boolean order = input.contains("订单") || input.contains("查询");
        JSONObject execution = new JSONObject().put("kind", order ? "service" : "summary").put("maxInput", 4000);
        if (order) execution.put("serviceId", SkillServices.MOCK_ORDERS);
        return new JSONObject().put("schemaVersion", 1).put("name", "模拟技能")
                .put("description", order ? "查询模拟订单" : "提取纯文本摘要")
                .put("documentation", order ? "输入模拟订单号，例如 DA-1001。" : "输入需要提取摘要的文本。")
                .put("execution", execution)
                .put("trial", new JSONObject().put("input", order ? "DA-1001" : "验收样例")
                        .put("expected", order ? "模拟查询：订单 DA-1001 已发货。" : "验收样例"))
                .toString();
    }

    public List<Pending> pending() {
        return read(state -> {
            List<Pending> entries = new ArrayList<>();
            JSONObject drafts = state.getJSONObject("drafts");
            for (Iterator<String> names = drafts.keys(); names.hasNext();) {
                String name = names.next();
                JSONObject draft = drafts.getJSONObject(name);
                entries.add(new Pending(name, resultType(draft), draft.getString("reason")));
            }
            return entries;
        });
    }

    public String pendingDefinition(String name) {
        return read(state -> {
            JSONObject draft = state.getJSONObject("drafts").optJSONObject(name);
            if (draft == null) throw new IllegalStateException("待连接草稿不存在");
            return draft.getString("definition");
        });
    }

    public Detail detail(String name) {
        return read(state -> detail(state, name));
    }

    public Detail saveConfiguration(String name, String draftId, SkillServices.Adapter adapter, String endpoint,
                                    String modelName, int costUnits, String bearerToken) throws Exception {
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            if (draft.outputType() != SkillResult.Type.IMAGE) throw new IllegalStateException("此草稿没有图片配置项");
            SkillServices.Service saved = services.configureForSkill(entry.optString("serviceId", ""), name, adapter,
                    endpoint, modelName, costUnits, bearerToken);
            entry.put("serviceId", saved.id()).put("draftId", UUID.randomUUID().toString());
            return advancePending(state, entry, draft, null);
        });
    }

    public Detail authorizeConfiguration(String name, String draftId, SkillServices.Service expected) throws Exception {
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            SkillServices.Service target = configured(entry, name);
            if (target == null || !target.equals(expected) || !configuration(draft, target).canAuthorize()) {
                throw new IllegalStateException("服务配置已变化或仍缺必填项");
            }
            services.authorize(target, target.costUnits());
            return advancePending(state, entry, draft, null);
        });
    }

    public Detail activateReplacement(String name, String draftId, int previousVersion) throws Exception {
        if (previousVersion <= 0) throw new IllegalArgumentException("请确认要替换的版本");
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            JSONObject active = state.getJSONObject("skills").optJSONObject(name);
            if (active == null || activeRevision(active).getInt("version") != previousVersion
                    || !NEEDS_REPLACEMENT.equals(entry.getString("reason"))) {
                throw new IllegalStateException("当前技能版本或配置已变化");
            }
            return advancePending(state, entry, draft, previousVersion);
        });
    }

    public Detail retryConfiguration(String name, String draftId) throws Exception {
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            return advancePending(state, entry, parse(entry.getString("definition")), null);
        });
    }

    private Detail advancePending(JSONObject state, JSONObject entry, Draft draft, Integer replaceVersion) throws Exception {
        String name = draft.name();
        SkillServices.Service service = resolve(state, draft, configured(entry, name));
        Preview preview = preview(state, draft, service);
        String reason = preview.pendingReason();
        if (reason.isEmpty() && (service == null || authorized(service))) {
            try {
                verifyTrial(draft, service);
            } catch (Exception failure) {
                reason = TRIAL_FAILED;
            }
            if (reason.isEmpty() && service != null && !authorized(service)) {
                reason = "服务待授权";
            }
            if (reason.isEmpty()) {
                if (preview.previousVersion() > 0 && replaceVersion == null) {
                    reason = NEEDS_REPLACEMENT;
                } else {
                    publish(state, draft, service);
                    return detail(state, name);
                }
            }
        }
        if (reason.isEmpty()) reason = "服务待授权";
        entry.put("reason", reason);
        writeState(state);
        return detail(state, name);
    }

    private Detail detail(JSONObject state, String name) throws Exception {
        JSONObject entry = state.getJSONObject("drafts").optJSONObject(name);
        JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
        Installed current = installed == null ? null : installed(name, installed);
        int version = current == null ? 0 : current.version();
        boolean enabled = current != null && current.enabled();
        boolean rollback = current != null && current.canRollback();
        if (entry == null) {
            if (current == null) throw new IllegalStateException("技能不存在");
            Executable execution = readExecution(revisionDirectory(activeRevision(installed)));
            return new Detail(name, current.outputType(), "", enabled ? "已启用" : "已停用", Collections.emptyList(),
                    execution.service(), false, false, version, enabled, rollback);
        }
        Draft draft = parse(entry.getString("definition"));
        SkillServices.Service explicit = configured(entry, name);
        SkillServices.Service target = explicit == null ? resolve(state, draft, null) : explicit;
        Configuration status = configuration(draft, target);
        String reason = entry.getString("reason");
        boolean replace = version > 0 && NEEDS_REPLACEMENT.equals(reason)
                && ("summary".equals(draft.kind()) || target != null && authorized(target));
        return new Detail(name, draft.outputType(), draftIdentity(name, entry), reason, status.fields(), target,
                explicit != null && status.canAuthorize(), replace, version, enabled, rollback);
    }

    private Configuration configuration(Draft draft, SkillServices.Service service) throws Exception {
        List<Field> fields = new ArrayList<>();
        if ("unconfigured".equals(draft.kind())) {
            fields.add(required("operation", "受支持的执行方式", false));
            return new Configuration(Collections.unmodifiableList(fields), false);
        }
        boolean needsImageFields = draft.outputType() == SkillResult.Type.IMAGE
                && (service == null || service.adapter() == SkillServices.Adapter.OPENAI_IMAGES);
        boolean filled = true;
        if (needsImageFields) {
            boolean endpoint = service != null && !service.endpoint().isEmpty();
            boolean model = service != null && !service.modelName().isEmpty();
            boolean key = service != null && services.hasCredential(service);
            fields.add(required("endpoint", "API 地址", endpoint));
            fields.add(required("model", "模型名", model));
            fields.add(required("credential", "密钥", key));
            filled = endpoint && model && key;
        }
        boolean usable = authorized(service);
        if ("service".equals(draft.kind())) {
            fields.add(new Field("authorization", "服务授权", true,
                    usable ? FieldState.FILLED : FieldState.NEEDS_AUTHORIZATION));
        }
        return new Configuration(Collections.unmodifiableList(fields), filled && service != null && !usable);
    }

    private static Field required(String id, String label, boolean filled) {
        return new Field(id, label, true, filled ? FieldState.FILLED : FieldState.MISSING);
    }

    private SkillServices.Service configured(JSONObject entry, String name) throws SkillServices.Failure {
        if (entry == null || !entry.has("serviceId")) return null;
        return services.scoped(entry.optString("serviceId", ""), name);
    }

    private static JSONObject requirePending(JSONObject state, String name, String draftId) throws Exception {
        JSONObject entry = state.getJSONObject("drafts").optJSONObject(name);
        if (entry == null || !draftIdentity(name, entry).equals(draftId)) {
            throw new IllegalStateException("待配置草稿已变化，请重新打开");
        }
        return entry;
    }

    private static String draftIdentity(String name, JSONObject entry) throws Exception {
        if (entry.has("draftId")) return entry.getString("draftId");
        // Older pending image definitions had no ID. Keep their identity stable when opened or re-saved.
        return UUID.nameUUIDFromBytes((name + "\n" + entry.getString("definition"))
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    String unavailableReason(String name) {
        return read(state -> {
            JSONObject draft = state.getJSONObject("drafts").optJSONObject(name);
            if (draft != null) return "技能待连接：" + draft.getString("reason");
            JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
            if (installed != null && !installed.getBoolean("enabled")
                    && resultType(activeRevision(installed)) == SkillResult.Type.IMAGE) return "图片技能已停用";
            return null;
        });
    }
    public Preview previewGenerated(String json) throws Exception {
        Draft draft = parse(json);
        return locked(state -> preview(state, draft, resolve(state, draft, configuredDraft(state, draft, json))));
    }

    public Installed installGenerated(String json) throws Exception {
        return installGenerated(json, null);
    }

    public Installed installGenerated(String json, Preview confirmation) throws Exception {
        SaveResult result = saveGenerated(json, confirmation);
        if (result.pending() != null) throw new IllegalStateException(result.pending().reason());
        return result.installed();
    }

    public SaveResult saveGenerated(String json, Preview confirmation) throws Exception {
        return saveGenerated(json, confirmation, SaveMode.GENERATE);
    }

    public SaveResult connectPending(String json, Preview confirmation) throws Exception {
        return saveGenerated(json, confirmation, SaveMode.RECONNECT);
    }

    private SaveResult saveGenerated(String json, Preview confirmation, SaveMode mode) throws Exception {
        Draft draft = parse(json);
        return locked(state -> {
            if (mode == SaveMode.CONVERSATION && !state.getBoolean("enabled")) {
                throw new IllegalStateException("Skills 已停用");
            }
            if (mode == SaveMode.RECONNECT) {
                JSONObject currentDraft = state.getJSONObject("drafts").optJSONObject(draft.name());
                if (currentDraft == null || !json.equals(currentDraft.getString("definition"))) {
                    throw new IllegalStateException("待连接草稿已变化，请重新打开");
                }
            }
            SkillServices.Service service = resolve(state, draft, configuredDraft(state, draft, json));
            Preview current = preview(state, draft, service);
            if (!current.pendingReason().isEmpty()) return savePending(state, draft, json, current.pendingReason());
            try {
                verifyTrial(draft, service);
            } catch (Exception failure) {
                if (mode == SaveMode.CONVERSATION) return savePending(state, draft, json, TRIAL_FAILED);
                throw failure;
            }
            if (mode == SaveMode.CONVERSATION && current.needsConfirmation()) {
                return savePending(state, draft, json, NEEDS_REPLACEMENT);
            }
            if ((current.needsConfirmation() || confirmation != null) && !current.equals(confirmation)) {
                throw new IllegalStateException("技能版本或服务已变化，请重新预览并确认");
            }
            if (service != null && !authorized(service)) {
                if (draft.outputType() == SkillResult.Type.IMAGE) return savePending(state, draft, json, NEEDS_IMAGE_SERVICE);
                throw new IllegalStateException("请先授权登记的服务");
            }
            return new SaveResult(publish(state, draft, service), null);
        });
    }

    private SaveResult savePending(JSONObject state, Draft draft, String json, String reason) throws Exception {
        JSONObject drafts = state.getJSONObject("drafts");
        JSONObject entry = drafts.optJSONObject(draft.name());
        if (entry == null || !json.equals(entry.optString("definition"))) {
            entry = new JSONObject().put("definition", json).put("draftId", UUID.randomUUID().toString());
            drafts.put(draft.name(), entry);
        }
        entry.put("outputType", draft.outputType().wireName()).put("reason", reason);
        writeState(state);
        return new SaveResult(null, new Pending(draft.name(), draft.outputType(), reason));
    }

    private SkillServices.Service configuredDraft(JSONObject state, Draft draft, String json) throws Exception {
        JSONObject old = state.getJSONObject("drafts").optJSONObject(draft.name());
        return old != null && json.equals(old.optString("definition")) ? configured(old, draft.name()) : null;
    }
    SkillResult executeResult(String name, int version, String argument, SkillResult.Type expectedType) throws Exception {
        Executable definition = locked(state -> {
            JSONObject skill = skill(state, name);
            JSONObject revision = activeRevision(skill);
            if (!state.getBoolean("enabled") || !skill.getBoolean("enabled")
                    || revision.getInt("version") != version) {
                throw new IllegalStateException("技能版本已变更或停用");
            }
            return readExecution(revisionDirectory(revision));
        });
        if (definition.outputType() != expectedType) throw new IllegalStateException("技能结果类型已变化");
        checkArgument(argument, definition.maxInput());
        if ("summary".equals(definition.kind())) return new SkillResult.Text(AiSkills.summarize(argument));
        SkillResult result = services.executeResult(definition.service(), argument);
        if (result.type() != definition.outputType()) throw new IOException("服务返回的结果类型不符合技能声明");
        return result;
    }

    private SkillServices.Service resolve(JSONObject state, Draft draft, SkillServices.Service explicit) throws Exception {
        if (!"service".equals(draft.kind())) return null;
        if (draft.outputType() == SkillResult.Type.TEXT) {
            SkillServices.Service service = services.find(draft.serviceId());
            if (service.outputType() != SkillResult.Type.TEXT) throw new IllegalArgumentException("请选择文本服务");
            return service;
        }

        if (explicit != null) return authorized(explicit) ? explicit : null;
        JSONObject current = state.getJSONObject("skills").optJSONObject(draft.name());
        if (current != null) {
            SkillServices.Service previous = readExecution(revisionDirectory(activeRevision(current))).service();
            if (imageBindingMatches(draft, previous) && authorized(previous)) return previous;
        }
        SkillServices.Service scoped = null;
        SkillServices.Service preferred = null;
        SkillServices.Service mock = null;
        for (SkillServices.Service service : services.listForSkill(draft.name())) {
            if (!imageBindingMatches(draft, service) || !authorized(service)) continue;
            if (!service.skillName().isEmpty()) {
                if (scoped == null || service.id().compareTo(scoped.id()) < 0) scoped = service;
            } else if (SkillServices.MOCK_IMAGE.equals(service.id())) mock = service;
            else if (preferred == null || service.id().compareTo(preferred.id()) < 0) preferred = service;
        }
        if (scoped != null) return scoped;
        return preferred == null ? mock : preferred;
    }

    private static boolean imageBindingMatches(Draft draft, SkillServices.Service service) {
        return service != null && service.outputType() == SkillResult.Type.IMAGE
                && (draft.serviceId() == null || draft.serviceId().equals(service.id()));
    }

    private boolean authorized(SkillServices.Service service) throws SkillServices.Failure {
        return service != null && services.isAuthorized(service)
                && (service.adapter() != SkillServices.Adapter.OPENAI_IMAGES || services.hasCredential(service));
    }
    private static void verifyTrial(Draft draft, SkillServices.Service service) throws Exception {
        SkillResult simulated = trial(draft, service);
        if (simulated.type() != draft.outputType()
                || (simulated instanceof SkillResult.Text text && !text.text().equals(draft.expected()))) {
            throw new IllegalArgumentException("模拟试跑结果与预期不符");
        }
    }
    private static SkillResult trial(Draft draft, SkillServices.Service service) throws Exception {
        checkArgument(draft.trialInput(), draft.maxInput());
        if (draft.outputType() == SkillResult.Type.IMAGE) return SkillServices.mockImageResult();
        if (service == null) return new SkillResult.Text(AiSkills.summarize(draft.trialInput()));
        if (SkillServices.MOCK_ORDERS.equals(service.id())) {
            return new SkillResult.Text(AiSkills.lookupOrder(draft.trialInput()));
        }
        return new SkillResult.Text("模拟服务响应");
    }

    private Preview preview(JSONObject state, Draft draft, SkillServices.Service service) throws Exception {
        JSONObject existing = state.getJSONObject("skills").optJSONObject(draft.name());
        int previous = existing == null ? 0 : activeRevision(existing).getInt("version");
        int latest = existing == null ? 0 : existing.getJSONArray("versions").length();
        String pending = "unconfigured".equals(draft.kind()) ? NEEDS_OPERATION
                : service == null && draft.outputType() == SkillResult.Type.IMAGE ? NEEDS_IMAGE_SERVICE : "";
        boolean reviewTextService = draft.outputType() == SkillResult.Type.TEXT && service != null
                && !SkillServices.MOCK_ORDERS.equals(service.id());
        return new Preview(draft.name(), service == null ? "" : service.label(), service == null ? "" : service.endpoint(),
                service == null ? 0 : service.costUnits(), pending.isEmpty() && (previous > 0 || reviewTextService),
                service == null ? "" : service.id(), draft.outputType(), pending, previous, latest);
    }
    private Installed publish(JSONObject state, Draft draft, SkillServices.Service service) throws Exception {
        JSONObject skills = state.getJSONObject("skills");
        JSONObject skill = skills.optJSONObject(draft.name());
        if (skill == null) {
            if (skills.length() >= 50) throw new IllegalStateException("最多可登记 50 个技能");
            skill = new JSONObject().put("versions", new JSONArray());
            skills.put(draft.name(), skill);
        }
        JSONArray versions = skill.getJSONArray("versions");
        int number = versions.length() + 1;
        String ref = UUID.randomUUID().toString();
        JSONObject revision = new JSONObject().put("version", number).put("directory", ref)
                .put("description", draft.description()).put("outputType", draft.outputType().wireName())
                .put("serviceLabel", service == null ? "" : service.label());
        File target = revisionDirectory(revision);
        if (!target.mkdirs()) throw new IOException("无法创建技能版本");
        try {
            writeDocument(new File(target, "SKILL.md"),
                    "---\nname: " + draft.name() + "\nversion: " + number + "\noutput: " + draft.outputType().wireName() + "\n---\n\n" + draft.documentation() + "\n");
            JSONObject definition = new JSONObject().put("schemaVersion", 2)
                    .put("version", number).put("kind", draft.kind()).put("maxInput", draft.maxInput())
                    .put("outputType", draft.outputType().wireName());
            if (service != null) {
                definition.put("service", new JSONObject().put("id", service.id()).put("label", service.label())
                        .put("endpoint", service.endpoint()).put("costUnits", service.costUnits())
                        .put("adapter", service.adapter().name()).put("modelName", service.modelName())
                        .put("skillName", service.skillName()));
            }
            writeDocument(new File(target, "execution.json"), definition.toString());
            versions.put(revision);
            skill.put("active", versions.length() - 1).put("enabled", true);
            state.getJSONObject("drafts").remove(draft.name());
            writeState(state);
        } catch (Exception failure) {
            new File(target, "SKILL.md").delete();
            new File(target, "execution.json").delete();
            target.delete();
            throw failure;
        }
        return installed(draft.name(), skill);
    }

    private static Draft parse(String json) {
        try {
            JSONObject value = object(json, 16000);
            keys(value, "schemaVersion", "name", "description", "documentation", "execution", "trial");
            int schema = integer(value, "schemaVersion");
            if (schema != 1 && schema != 2) throw new IllegalArgumentException();
            String name = text(value.get("name"), 24);
            if (!NAME.matcher(name).matches() || AiSkills.isBuiltIn(name) || name.startsWith("mcp-")) throw new IllegalArgumentException();
            String description = text(value.get("description"), 100);
            if (description.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            String documentation = text(value.get("documentation"), 6000);
            JSONObject run = value.getJSONObject("execution");
            String kind = text(run.get("kind"), 16);
            SkillResult.Type output = schema == 1 ? SkillResult.Type.TEXT
                    : SkillResult.Type.fromWire(text(run.get("outputType"), 8));
            String service = null;
            if ("unconfigured".equals(kind) && schema == 2 && output == SkillResult.Type.TEXT) {
                keys(run, "kind", "maxInput", "outputType");
            } else if ("summary".equals(kind) && output == SkillResult.Type.TEXT) {
                if (schema == 1) keys(run, "kind", "maxInput");
                else keys(run, "kind", "maxInput", "outputType");
            } else if ("service".equals(kind)) {
                if (schema == 1) keys(run, "kind", "maxInput", "serviceId");
                else if (output == SkillResult.Type.TEXT || run.has("serviceId")) {
                    keys(run, "kind", "maxInput", "outputType", "serviceId");
                } else keys(run, "kind", "maxInput", "outputType");
                if (run.has("serviceId")) service = text(run.get("serviceId"), 64);
            } else {
                throw new IllegalArgumentException();
            }
            int maxInput = integer(run, "maxInput");
            if (maxInput < 1 || maxInput > 4000) throw new IllegalArgumentException();
            JSONObject test = value.getJSONObject("trial");
            String expected = null;
            if (output == SkillResult.Type.IMAGE) {
                keys(test, "input", "expectedType");
                if (!"image".equals(test.get("expectedType"))) throw new IllegalArgumentException();
            } else {
                keys(test, "input", "expected");
                expected = text(test.get("expected"), 4000);
            }
            return new Draft(name, description, documentation, kind, maxInput, output, service,
                    text(test.get("input"), maxInput), expected);
        } catch (Exception invalid) {
            throw new IllegalArgumentException("生成的技能定义结构或参数无效");
        }
    }

    private static SkillResult.Type resultType(JSONObject value) {
        return SkillResult.Type.fromWire(value.optString("outputType", "text"));
    }
    private static JSONObject object(String input, int limit) throws Exception {
        if (input == null || input.length() > limit) throw new IllegalArgumentException();
        JSONTokener reader = new JSONTokener(input);
        Object result = reader.nextValue();
        if (!(result instanceof JSONObject) || reader.nextClean() != 0) throw new IllegalArgumentException();
        return (JSONObject) result;
    }

    private static void keys(JSONObject value, String... allowed) {
        if (value.length() != allowed.length) throw new IllegalArgumentException();
        for (String key : allowed) if (!value.has(key)) throw new IllegalArgumentException();
    }

    private static int integer(JSONObject value, String key) throws Exception {
        Object number = value.get(key);
        if (!(number instanceof Integer)) throw new IllegalArgumentException();
        return (Integer) number;
    }

    private static String text(Object value, int maximum) {
        if (!(value instanceof String text) || text.trim().isEmpty() || text.length() > maximum
                || text.indexOf(0) >= 0) throw new IllegalArgumentException("文本为空或超出长度");
        return text;
    }

    private static void checkArgument(String argument, int maxInput) {
        text(argument, maxInput);
    }

    private static JSONObject skill(JSONObject state, String name) throws Exception {
        JSONObject skill = state.getJSONObject("skills").optJSONObject(name);
        if (skill == null) throw new IllegalStateException("技能不存在");
        return skill;
    }

    private static JSONObject activeRevision(JSONObject skill) throws Exception {
        return skill.getJSONArray("versions").getJSONObject(skill.getInt("active"));
    }

    private static Installed installed(String name, JSONObject skill) throws Exception {
        JSONObject revision = activeRevision(skill);
        return new Installed(name, revision.getInt("version"), skill.getBoolean("enabled"),
                skill.getInt("active") > 0, resultType(revision), revision.optString("serviceLabel", ""));
    }

    private File revisionDirectory(JSONObject version) throws Exception {
        String id = version.getString("directory");
        if (!REVISION.matcher(id).matches()) throw new IOException("技能版本标识无效");
        return new File(new File(directory, "versions"), id);
    }

    private static Executable readExecution(File revision) throws Exception {
        JSONObject definition;
        try (FileInputStream stream = new FileInputStream(new File(revision, "execution.json"))) {
            definition = object(readText(stream), LIMIT);
        }
        int schema = definition.getInt("schemaVersion");
        if (schema != 1 && schema != 2) throw new IOException("技能版本不兼容");
        SkillResult.Type output = schema == 1 ? SkillResult.Type.TEXT
                : SkillResult.Type.fromWire(definition.getString("outputType"));
        String kind = definition.getString("kind");
        int maxInput = definition.getInt("maxInput");
        if ("summary".equals(kind) && output == SkillResult.Type.TEXT) return new Executable(kind, maxInput, output, null);
        if (!"service".equals(kind)) throw new IOException("技能类型不支持");
        JSONObject service = definition.getJSONObject("service");
        return new Executable(kind, maxInput, output, new SkillServices.Service(service.getString("id"),
                service.getString("label"), service.getString("endpoint"), service.getInt("costUnits"), output,
                SkillServices.Adapter.valueOf(service.optString("adapter", "GENERIC")),
                service.optString("modelName", ""), service.optString("skillName", "")));
    }

    private <T> T read(StateAction<T> action) {
        try { return locked(action); }
        catch (RuntimeException failure) { throw failure; }
        catch (Exception failure) { throw new IllegalStateException("技能目录无法访问"); }
    }

    private <T> T locked(StateAction<T> action) throws Exception {
        synchronized (SkillStore.class) {
            if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
                throw new IOException("无法创建技能目录");
            }
            try (RandomAccessFile file = new RandomAccessFile(new File(directory, "catalog.lock"), "rw");
                 FileLock ignored = file.getChannel().lock()) {
                JSONObject state;
                if (!catalog.exists()) {
                    state = new JSONObject().put("schemaVersion", 1).put("enabled", true).put("skills", new JSONObject());
                } else {
                    try (FileInputStream stream = new FileInputStream(catalog)) {
                        state = object(readText(stream), LIMIT);
                    }
                    if (state.getInt("schemaVersion") != 1) throw new IOException("技能目录版本不兼容");
                }
                if (!state.has("drafts")) state.put("drafts", new JSONObject());
                return action.run(state);
            }
        }
    }

    private static String readText(FileInputStream stream) throws IOException {
        java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = stream.read(buffer)) != -1) {
            if (output.size() + count > LIMIT) throw new IOException("技能定义过大");
            output.write(buffer, 0, count);
        }
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    private void writeState(JSONObject state) throws IOException {
        String serialized = state.toString();
        byte[] bytes = serialized.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > LIMIT) throw new IOException("技能目录已满");
        File staged = File.createTempFile("catalog-", ".json", directory);
        try {
            writeDocument(staged, serialized);
            Files.move(staged.toPath(), catalog.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException | RuntimeException failure) {
            staged.delete();
            throw failure;
        }
    }
    private static void writeDocument(File file, String text) throws IOException {
        try (FileOutputStream stream = new FileOutputStream(file)) {
            stream.write(text.getBytes(StandardCharsets.UTF_8));
            stream.getFD().sync();
        }
    }
}
