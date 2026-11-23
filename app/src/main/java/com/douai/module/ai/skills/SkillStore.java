package com.douai.module.ai.skills;

import android.content.Context;
import com.douai.module.ai.mcp.McpRegistry;

import org.json.JSONArray;
import org.json.JSONException;
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
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    private static final String EDIT_STAGED = "草稿已保存，等待启用";
    private static final int LIMIT = 96 * 1024;
    private static final String RETIRED_VERSIONS = "retiredVersions";
    private static final Map<Path, List<DeletionObserver>> DELETION_OBSERVERS = new HashMap<>();
    private static final int DRAFT_LIMIT = 16000;
    private static final int AUTHORED_SCHEMA = 3;
    private static final int TEXT_MODEL_MAX_INPUT = 2000;
    private static final int TEXT_MODEL_MAX_INSTRUCTION = 1600;
    private static final String TEXT_MODEL = "text_model";
    private static final String RETAINED_SERVICE = "retainedService";

    public record Installed(String name, int version, boolean enabled, boolean canRollback,
                            SkillResult.Type outputType, String serviceLabel) {}
    public record Preview(String name, String serviceLabel, String endpoint, int costUnits,
                          boolean needsConfirmation, String serviceId, SkillResult.Type outputType,
                          String pendingReason, int previousVersion, int latestVersion) {}
    public record Pending(String name, SkillResult.Type outputType, String reason, String draftId) {}
    public record SaveResult(Installed installed, Pending pending) {}
    public record DraftPreview(String name, String description, SkillResult.Type outputType,
                               String instruction, String definition) {}
    public enum FieldState { FILLED, MISSING, NEEDS_AUTHORIZATION }
    public record Field(String id, String label, boolean required, FieldState state) {}
    public record DeleteTarget(String name, int versionCount, boolean hasDraft, String proof) {}
    public record EditTarget(DeleteTarget identity, String description, String documentation,
                             String instruction, boolean instructionEditable) {}
    public record Detail(String name, SkillResult.Type outputType, String draftId, String reason,
                         List<Field> fields, SkillServices.Service service, boolean canAuthorize,
                         boolean canReplace, int previousVersion, boolean installedEnabled, boolean canRollback,
                         DeleteTarget deleteTarget) {
        public boolean pending() { return !draftId.isEmpty(); }
    }
    private enum SaveMode { GENERATE, RECONNECT, CONVERSATION }
    private record Configuration(List<Field> fields, boolean canAuthorize) {}
    private record EditSource(EditTarget target, JSONObject definition, JSONObject pending,
                              SkillServices.Service retainedService) {}
    record Available(AiSkills.Command command, int version, String revisionId) {}
    private record Draft(String name, String description, String documentation, String kind,
                         int maxInput, SkillResult.Type outputType, String serviceId, String trialInput,
                         String expected, String instruction, int schemaVersion) {
        DraftPreview preview(String definition) {
            return new DraftPreview(name, description, outputType, instruction, definition);
        }
    }
    private record Executable(String kind, int maxInput, SkillResult.Type outputType,
                              SkillServices.Service service, String instruction, int schemaVersion) {}
    private interface StateAction<T> { T run(JSONObject state) throws Exception; }
    private final File directory;
    private final Path directoryKey;
    private final File catalog;
    private final SkillServices services;
    private final McpRegistry mcp;
    private boolean cleanupChecked;

    private static final class DeletionObserver implements AutoCloseable {
        private final Path directory;
        private final Runnable callback;
        private volatile boolean closed;

        DeletionObserver(Path directory, Runnable callback) {
            this.directory = directory;
            this.callback = callback;
        }

        @Override public void close() {
            synchronized (DELETION_OBSERVERS) {
                if (closed) return;
                closed = true;
                List<DeletionObserver> observers = DELETION_OBSERVERS.get(directory);
                if (observers != null) {
                    observers.remove(this);
                    if (observers.isEmpty()) DELETION_OBSERVERS.remove(directory);
                }
            }
        }
    }

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
        this.directoryKey = canonical(directory);
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
                        revision.getInt("version"), revision.getString("directory")));
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

    public AutoCloseable observeDeletions(Runnable listener) {
        DeletionObserver observer = new DeletionObserver(directoryKey, Objects.requireNonNull(listener));
        synchronized (DELETION_OBSERVERS) {
            DELETION_OBSERVERS.computeIfAbsent(directoryKey, ignored -> new ArrayList<>()).add(observer);
        }
        return observer;
    }

    private void notifyDeletions() {
        List<DeletionObserver> observers;
        synchronized (DELETION_OBSERVERS) {
            List<DeletionObserver> registered = DELETION_OBSERVERS.get(directoryKey);
            if (registered == null) return;
            observers = new ArrayList<>(registered);
        }
        for (DeletionObserver observer : observers) {
            if (observer.closed) continue;
            try { observer.callback.run(); }
            catch (RuntimeException | LinkageError ignored) { }
        }
    }

    public void delete(DeleteTarget expected) throws Exception {
        if (expected == null || expected.name() == null || !NAME.matcher(expected.name()).matches()
                || AiSkills.isBuiltIn(expected.name()) || expected.name().startsWith("mcp-")) {
            throw new IllegalArgumentException("只能删除自定义技能");
        }
        locked(state -> {
            JSONObject skills = state.getJSONObject("skills");
            JSONObject drafts = state.getJSONObject("drafts");
            JSONObject installed = skills.optJSONObject(expected.name());
            JSONObject draft = drafts.optJSONObject(expected.name());
            if (!expected.equals(deleteTarget(expected.name(), installed, draft))) {
                throw new IllegalStateException("技能或待配置草稿已变化，请重新确认删除");
            }

            if (installed != null) {
                Set<String> retired = retiredVersions(state);
                collectRevisionIds(installed, retired);
                state.put(RETIRED_VERSIONS, new JSONArray(retired));
            }
            skills.remove(expected.name());
            drafts.remove(expected.name());
            writeState(state);
            cleanupRetiredVersions(state);
            return null;
        });
        notifyDeletions();
    }

    private static DeleteTarget deleteTarget(String name, JSONObject installed, JSONObject draft) throws Exception {
        if (installed == null && draft == null) return null;
        int versions = installed == null ? 0 : installed.getJSONArray("versions").length();
        JSONArray records = new JSONArray().put(name)
                .put(installed == null ? JSONObject.NULL : installed)
                .put(draft == null ? JSONObject.NULL : draft);
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(records.toString().getBytes(StandardCharsets.UTF_8));
        return new DeleteTarget(name, versions, draft != null,
                Base64.getUrlEncoder().withoutPadding().encodeToString(digest));
    }

    public EditTarget editTarget(DeleteTarget expected) {
        return read(state -> editSource(state, expected).target());
    }

    public Detail saveEdit(EditTarget expected, String description, String documentation, String instruction)
            throws Exception {
        if (expected == null) throw new IllegalArgumentException("编辑目标无效");
        return locked(state -> {
            EditSource source = editSource(state, expected.identity());
            if (!expected.equals(source.target())) throw new IllegalStateException("技能内容已变化，请重新打开编辑");
            String heading = text(description, 100);
            String body = text(documentation, 6000);
            if (heading.codePoints().anyMatch(Character::isISOControl)) throw invalidDraft();
            SkillAuthoring.rejectCredential(heading);
            SkillAuthoring.rejectCredential(body);

            JSONObject definition = source.definition();
            definition.put("description", heading).put("documentation", body);
            if (expected.instructionEditable()) {
                String rule = modelInstruction(instruction);
                SkillAuthoring.rejectCredential(rule);
                definition.getJSONObject("execution").put("instruction", rule);
            } else if (instruction != null && !instruction.isEmpty()) {
                throw new IllegalArgumentException("此技能没有可编辑的模型指令");
            }

            String json = definition.toString();
            Draft draft = parse(json);
            JSONObject entry = source.pending();
            if (entry == null) {
                entry = new JSONObject();
                if (source.retainedService() != null) {
                    entry.put(RETAINED_SERVICE, serviceRecord(source.retainedService()));
                }
                state.getJSONObject("drafts").put(draft.name(), entry);
            }
            entry.put("definition", json).put("draftId", UUID.randomUUID().toString())
                    .put("outputType", draft.outputType().wireName());
            return advancePending(state, entry, draft, null, true);
        });
    }

    private EditSource editSource(JSONObject state, DeleteTarget expected) throws Exception {
        if (expected == null || expected.name() == null || !NAME.matcher(expected.name()).matches()
                || AiSkills.isBuiltIn(expected.name()) || expected.name().startsWith("mcp-")) {
            throw new IllegalArgumentException("只能编辑自定义技能");
        }
        String name = expected.name();
        JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
        JSONObject pending = state.getJSONObject("drafts").optJSONObject(name);
        if (!expected.equals(deleteTarget(name, installed, pending))) {
            throw new IllegalStateException("技能或待配置草稿已变化，请重新打开编辑");
        }
        if (pending != null) {
            String json = pending.getString("definition");
            Draft draft = parse(json);
            return new EditSource(new EditTarget(expected, draft.description(), draft.documentation(),
                    draft.instruction(), TEXT_MODEL.equals(draft.kind())), object(json, DRAFT_LIMIT), pending, null);
        }
        JSONObject revision = activeRevision(installed);
        File slot = revisionDirectory(revision);
        Executable executable = readExecution(slot);
        String description = revision.getString("description");
        String documentation = readDocumentation(slot, name, revision.getInt("version"));
        JSONObject definition = editDefinition(name, description, documentation, executable);
        return new EditSource(new EditTarget(expected, description, documentation, executable.instruction(),
                TEXT_MODEL.equals(executable.kind())), definition, null, executable.service());
    }

    private static JSONObject editDefinition(String name, String description, String documentation,
                                             Executable executable) throws Exception {
        int schema = executable.schemaVersion();
        JSONObject run = new JSONObject().put("kind", executable.kind()).put("maxInput", executable.maxInput());
        if (schema != 1) run.put("outputType", executable.outputType().wireName());
        if (TEXT_MODEL.equals(executable.kind())) run.put("instruction", executable.instruction());
        SkillServices.Service service = executable.service();
        if (schema < AUTHORED_SCHEMA && service != null && executable.outputType() == SkillResult.Type.TEXT) {
            run.put("serviceId", service.id());
        }
        boolean mockOrder = service != null && SkillServices.MOCK_ORDERS.equals(service.id());
        String sample = mockOrder ? "A" : "文";
        JSONObject trial = new JSONObject().put("input", sample);
        if (schema == AUTHORED_SCHEMA || executable.outputType() == SkillResult.Type.IMAGE) {
            trial.put("expectedType", executable.outputType().wireName());
        } else {
            String response = service == null ? AiSkills.summarize(sample)
                    : mockOrder ? AiSkills.lookupOrder(sample) : "模拟服务响应";
            trial.put("expected", response);
        }
        return new JSONObject().put("schemaVersion", schema).put("name", name).put("description", description)
                .put("documentation", documentation).put("execution", run).put("trial", trial);
    }

    private static String readDocumentation(File slot, String name, int version) throws IOException {
        String raw;
        try (FileInputStream stream = new FileInputStream(new File(slot, "SKILL.md"))) {
            raw = readText(stream);
        }
        String headerStart = "---\nname: " + name + "\nversion: " + version + "\n";
        if (!raw.startsWith(headerStart)) return raw;
        int end = raw.indexOf("\n---\n\n", headerStart.length() - 1);
        if (end < 0) return raw;
        String body = raw.substring(end + "\n---\n\n".length());
        return body.endsWith("\n") ? body.substring(0, body.length() - 1) : body;
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

    public static DraftPreview validateAuthoredDraft(String raw) {
        return parseAuthored(raw).preview(raw);
    }

    public SaveResult saveAuthoredDraft(DraftPreview preview) throws Exception {
        if (preview == null) throw invalidDraft();
        Draft draft = parseAuthored(preview.definition());
        if (!draft.preview(preview.definition()).equals(preview)) throw invalidDraft();
        return saveGenerated(draft, preview.definition(), null, SaveMode.CONVERSATION);
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
                entries.add(new Pending(name, resultType(draft), draft.getString("reason"), draftIdentity(name, draft)));
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
            if (bearerToken == null || bearerToken.isEmpty()) {
                SkillServices.Service binding = pendingService(state, draft, entry);
                if (sameConfiguration(binding, adapter, endpoint, modelName, costUnits) && authorized(binding)) {
                    if (!entry.has("serviceId") && !entry.has(RETAINED_SERVICE)) {
                        entry.put(RETAINED_SERVICE, serviceRecord(binding))
                                .put("draftId", UUID.randomUUID().toString());
                    }
                    return advancePending(state, entry, draft, null);
                }
            }
            SkillServices.Service saved = services.configureForSkill(editableServiceId(state, entry, name), name, adapter,
                    endpoint, modelName, costUnits, bearerToken);
            entry.put("serviceId", saved.id()).put("draftId", UUID.randomUUID().toString());
            entry.remove(RETAINED_SERVICE);
            return advancePending(state, entry, draft, null);
        });
    }

    private static boolean sameConfiguration(SkillServices.Service binding, SkillServices.Adapter adapter,
                                             String endpoint, String modelName, int costUnits) {
        return binding != null && binding.adapter() == adapter && binding.costUnits() == costUnits
                && binding.endpoint().equals(endpoint == null ? "" : endpoint.trim())
                && binding.modelName().equals(modelName == null ? "" : modelName.trim());
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
        return advancePending(state, entry, draft, replaceVersion, false);
    }

    private Detail advancePending(JSONObject state, JSONObject entry, Draft draft, Integer replaceVersion,
                                  boolean stageOnly) throws Exception {
        String name = draft.name();
        SkillServices.Service service = pendingService(state, draft, entry);
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
                } else if (stageOnly) {
                    reason = EDIT_STAGED;
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
        DeleteTarget deletion = deleteTarget(name, installed, entry);
        if (entry == null) {
            if (current == null) throw new IllegalStateException("技能不存在");
            Executable execution = readExecution(revisionDirectory(activeRevision(installed)));
            return new Detail(name, current.outputType(), "", enabled ? "已启用" : "已停用", Collections.emptyList(),
                    execution.service(), false, false, version, enabled, rollback, deletion);
        }
        Draft draft = parse(entry.getString("definition"));
        SkillServices.Service explicit = configured(entry, name);
        SkillServices.Service target = explicit == null ? pendingService(state, draft, entry) : explicit;
        Configuration status = configuration(draft, target);
        String reason = entry.getString("reason");
        boolean replace = version > 0 && NEEDS_REPLACEMENT.equals(reason)
                && (!"service".equals(draft.kind()) || target != null && authorized(target));
        return new Detail(name, draft.outputType(), draftIdentity(name, entry), reason, status.fields(), target,
                explicit != null && status.canAuthorize(), replace, version, enabled, rollback, deletion);
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

    private String editableServiceId(JSONObject state, JSONObject entry, String name) throws Exception {
        SkillServices.Service scoped = configured(entry, name);
        if (scoped == null) return "";
        JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
        if (installed != null) {
            JSONArray revisions = installed.getJSONArray("versions");
            for (int i = 0; i < revisions.length(); i++) {
                SkillServices.Service binding = readExecution(revisionDirectory(revisions.getJSONObject(i))).service();
                if (binding != null && scoped.id().equals(binding.id())) return "";
            }
        }
        return scoped.id();
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
            if (installed != null && !installed.getBoolean("enabled")) {
                JSONObject revision = activeRevision(installed);
                if (resultType(revision) == SkillResult.Type.IMAGE) return "图片技能已停用";
                if (TEXT_MODEL.equals(revision.optString("kind"))) return "技能已停用";
            }
            return null;
        });
    }
    public Preview previewGenerated(String json) throws Exception {
        Draft draft = parse(json);
        return locked(state -> preview(state, draft, pendingService(state, draft, matchingDraft(state, draft, json))));
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

    public SaveResult connectPending(String json, Preview confirmation, String expectedDraftId) throws Exception {
        if (expectedDraftId == null || expectedDraftId.isEmpty()) throw new IllegalArgumentException("草稿标识无效");
        return saveGenerated(parse(json), json, confirmation, SaveMode.RECONNECT, expectedDraftId);
    }

    private SaveResult saveGenerated(String json, Preview confirmation, SaveMode mode) throws Exception {
        return saveGenerated(parse(json), json, confirmation, mode);
    }

    private SaveResult saveGenerated(Draft draft, String json, Preview confirmation, SaveMode mode) throws Exception {
        return saveGenerated(draft, json, confirmation, mode, null);
    }

    private SaveResult saveGenerated(Draft draft, String json, Preview confirmation, SaveMode mode,
                                     String expectedDraftId) throws Exception {
        return locked(state -> {
            if (mode == SaveMode.CONVERSATION && !state.getBoolean("enabled")) {
                throw new IllegalStateException("Skills 已停用");
            }
            if (mode == SaveMode.RECONNECT) {
                JSONObject currentDraft = state.getJSONObject("drafts").optJSONObject(draft.name());
                if (currentDraft == null || !json.equals(currentDraft.getString("definition"))
                        || (expectedDraftId != null && !expectedDraftId.equals(draftIdentity(draft.name(), currentDraft)))) {
                    throw new IllegalStateException("待连接草稿已变化，请重新打开");
                }
            }
            SkillServices.Service service = pendingService(state, draft, matchingDraft(state, draft, json));
            Preview current = preview(state, draft, service);
            if (draft.schemaVersion() != AUTHORED_SCHEMA && !current.pendingReason().isEmpty()) {
                return savePending(state, draft, json, current.pendingReason());
            }
            try {
                verifyTrial(draft, service);
            } catch (Exception failure) {
                if (mode == SaveMode.CONVERSATION) return savePending(state, draft, json, TRIAL_FAILED);
                throw failure;
            }
            if (!current.pendingReason().isEmpty()) return savePending(state, draft, json, current.pendingReason());
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
        return new SaveResult(null, new Pending(draft.name(), draft.outputType(), reason,
                draftIdentity(draft.name(), entry)));
    }

    private static JSONObject matchingDraft(JSONObject state, Draft draft, String json) throws Exception {
        JSONObject old = state.getJSONObject("drafts").optJSONObject(draft.name());
        return old != null && json.equals(old.optString("definition")) ? old : null;
    }
    SkillResult executeResult(String name, int version, String revisionId, String argument, SkillResult.Type expectedType,
                              AiSkills.TextExecutor textExecutor) throws Exception {
        Executable definition = locked(state -> {
            JSONObject skill = skill(state, name);
            JSONObject revision = activeRevision(skill);
            if (!state.getBoolean("enabled") || !skill.getBoolean("enabled")
                    || revision.getInt("version") != version || !revision.getString("directory").equals(revisionId)) {
                throw new IllegalStateException("技能版本已变更或停用");
            }
            return readExecution(revisionDirectory(revision));
        });
        if (definition.outputType() != expectedType) throw new IllegalStateException("技能结果类型已变化");
        checkArgument(argument, definition.maxInput());
        if ("summary".equals(definition.kind())) return new SkillResult.Text(AiSkills.summarize(argument));
        if (TEXT_MODEL.equals(definition.kind())) {
            if (textExecutor == null) throw new IllegalStateException("此入口的技能模型不可用");
            return executeTextModel(definition.instruction(), argument, textExecutor);
        }
        SkillResult result = services.executeResult(definition.service(), argument);
        if (result.type() != definition.outputType()) throw new IOException("服务返回的结果类型不符合技能声明");
        return result;
    }

    private SkillServices.Service pendingService(JSONObject state, Draft draft, JSONObject entry) throws Exception {
        if (!"service".equals(draft.kind())) return null;
        if (entry != null) {
            if (entry.has("serviceId")) {
                SkillServices.Service explicit = configured(entry, draft.name());
                return authorized(explicit) ? explicit : null;
            }
            JSONObject retained = entry.optJSONObject(RETAINED_SERVICE);
            if (retained != null) return readService(retained, draft.outputType());
        }
        return resolve(state, draft);
    }

    private SkillServices.Service resolve(JSONObject state, Draft draft) throws Exception {
        if (draft.outputType() == SkillResult.Type.TEXT) {
            SkillServices.Service service = services.find(draft.serviceId());
            if (service.outputType() != SkillResult.Type.TEXT) throw new IllegalArgumentException("请选择文本服务");
            return service;
        }

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
                || (draft.expected() != null && simulated instanceof SkillResult.Text text
                && !text.text().equals(draft.expected()))) {
            throw new IllegalArgumentException("模拟试跑结果与预期不符");
        }
    }
    private static SkillResult trial(Draft draft, SkillServices.Service service) throws Exception {
        checkArgument(draft.trialInput(), draft.maxInput());
        if (draft.outputType() == SkillResult.Type.IMAGE) return SkillServices.mockImageResult();
        if (TEXT_MODEL.equals(draft.kind())) {
            return executeTextModel(draft.instruction(), draft.trialInput(), (instruction, input) -> "模拟文本响应");
        }
        if (service == null) return new SkillResult.Text(AiSkills.summarize(draft.trialInput()));
        if (SkillServices.MOCK_ORDERS.equals(service.id())) {
            return new SkillResult.Text(AiSkills.lookupOrder(draft.trialInput()));
        }
        return new SkillResult.Text("模拟服务响应");
    }

    private static SkillResult.Text executeTextModel(String instruction, String input,
                                                     AiSkills.TextExecutor executor) throws Exception {
        SkillAuthoring.textInput(instruction, input);
        String result = text(executor.run(instruction, input), 4000);
        SkillAuthoring.rejectCredential(result);
        return new SkillResult.Text(result);
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
                .put("kind", draft.kind())
                .put("serviceLabel", service == null ? "" : service.label());
        File target = revisionDirectory(revision);
        if (!target.mkdirs()) throw new IOException("无法创建技能版本");
        try {
            writeDocument(new File(target, "SKILL.md"),
                    "---\nname: " + draft.name() + "\nversion: " + number + "\noutput: " + draft.outputType().wireName() + "\n---\n\n" + draft.documentation() + "\n");
            int schema = draft.schemaVersion() == AUTHORED_SCHEMA ? AUTHORED_SCHEMA : 2;
            JSONObject definition = new JSONObject().put("schemaVersion", schema)
                    .put("version", number).put("kind", draft.kind()).put("maxInput", draft.maxInput())
                    .put("outputType", draft.outputType().wireName());
            if (TEXT_MODEL.equals(draft.kind())) definition.put("instruction", draft.instruction());
            if (service != null) definition.put("service", serviceRecord(service));
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

    private static JSONObject serviceRecord(SkillServices.Service service) throws JSONException {
        return new JSONObject().put("id", service.id()).put("label", service.label())
                .put("endpoint", service.endpoint()).put("costUnits", service.costUnits())
                .put("adapter", service.adapter().name()).put("modelName", service.modelName())
                .put("skillName", service.skillName());
    }

    private static SkillServices.Service readService(JSONObject service, SkillResult.Type output) throws JSONException {
        return new SkillServices.Service(service.getString("id"), service.getString("label"),
                service.getString("endpoint"), service.getInt("costUnits"), output,
                SkillServices.Adapter.valueOf(service.optString("adapter", "GENERIC")),
                service.optString("modelName", ""), service.optString("skillName", ""));
    }

    private static Draft parse(String json) {
        try {
            JSONObject value = object(json, DRAFT_LIMIT);
            int schema = integer(value, "schemaVersion");
            if (schema == AUTHORED_SCHEMA) return parseAuthored(json);
            if (schema != 1 && schema != 2) throw new IllegalArgumentException();
            return declaration(value, schema);
        } catch (Exception invalid) {
            throw invalidDraft();
        }
    }

    private static Draft parseAuthored(String json) {
        try {
            JSONObject value = SkillAuthoring.readObject(json, DRAFT_LIMIT);
            if (integer(value, "schemaVersion") != AUTHORED_SCHEMA) throw new IllegalArgumentException();
            return declaration(value, AUTHORED_SCHEMA);
        } catch (Exception invalid) {
            throw invalidDraft();
        }
    }

    private static IllegalArgumentException invalidDraft() {
        return new IllegalArgumentException("生成的技能定义结构或参数无效");
    }

    private static Draft declaration(JSONObject value, int schema) throws Exception {
        keys(value, "schemaVersion", "name", "description", "documentation", "execution", "trial");
        String name = text(value.get("name"), 24);
        if (!NAME.matcher(name).matches() || AiSkills.isBuiltIn(name) || name.startsWith("mcp-")) {
            throw invalidDraft();
        }
        String description = text(value.get("description"), 100);
        if (description.codePoints().anyMatch(Character::isISOControl)) throw invalidDraft();
        String documentation = text(value.get("documentation"), 6000);
        JSONObject run = value.getJSONObject("execution");
        String kind = text(run.get("kind"), 16);
        SkillResult.Type output = schema == 1 ? SkillResult.Type.TEXT
                : SkillResult.Type.fromWire(text(run.get("outputType"), 8));
        String service = null;
        String instruction = "";
        if (schema == AUTHORED_SCHEMA) {
            if (TEXT_MODEL.equals(kind) && output == SkillResult.Type.TEXT) {
                keys(run, "kind", "maxInput", "outputType", "instruction");
                instruction = modelInstruction(run.get("instruction"));
            } else if ("summary".equals(kind) && output == SkillResult.Type.TEXT
                    || "service".equals(kind) && output == SkillResult.Type.IMAGE) {
                keys(run, "kind", "maxInput", "outputType");
            } else {
                throw invalidDraft();
            }
        } else if ("unconfigured".equals(kind) && schema == 2 && output == SkillResult.Type.TEXT) {
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
            throw invalidDraft();
        }
        int maxInput = integer(run, "maxInput");
        if (maxInput < 1 || maxInput > (TEXT_MODEL.equals(kind) ? TEXT_MODEL_MAX_INPUT : 4000)) throw invalidDraft();
        JSONObject test = value.getJSONObject("trial");
        String expected = null;
        if (schema == AUTHORED_SCHEMA || output == SkillResult.Type.IMAGE) {
            keys(test, "input", "expectedType");
            if (!output.wireName().equals(test.get("expectedType"))) throw invalidDraft();
        } else {
            keys(test, "input", "expected");
            expected = text(test.get("expected"), 4000);
        }
        return new Draft(name, description, documentation, kind, maxInput, output, service,
                text(test.get("input"), maxInput), expected, instruction, schema);
    }

    private static String modelInstruction(Object value) {
        String instruction = text(value, TEXT_MODEL_MAX_INSTRUCTION);
        if (instruction.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r' && c != '\t')) {
            throw invalidDraft();
        }
        return instruction;
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

    private static String revisionId(JSONObject version) throws IOException, JSONException {
        String id = version.getString("directory");
        if (!REVISION.matcher(id).matches()) throw new IOException("技能版本标识无效");
        return id;
    }

    private File revisionDirectory(JSONObject version) throws Exception {
        return new File(new File(directory, "versions"), revisionId(version));
    }

    private static void collectRevisionIds(JSONObject installed, Set<String> ids) throws IOException, JSONException {
        JSONArray revisions = installed.getJSONArray("versions");
        for (int i = 0; i < revisions.length(); i++) ids.add(revisionId(revisions.getJSONObject(i)));
    }

    private static Set<String> retiredVersions(JSONObject state) {
        Set<String> ids = new LinkedHashSet<>();
        JSONArray versions = state.optJSONArray(RETIRED_VERSIONS);
        if (versions != null) {
            for (int i = 0; i < versions.length(); i++) {
                Object id = versions.opt(i);
                if (id instanceof String value && REVISION.matcher(value).matches()) ids.add(value);
            }
        }
        return ids;
    }

    private void cleanupRetiredVersions(JSONObject state) {
        JSONArray journal = state.optJSONArray(RETIRED_VERSIONS);
        if (journal == null) return;
        try {
            Set<String> live = new LinkedHashSet<>();
            JSONObject skills = state.getJSONObject("skills");
            for (Iterator<String> names = skills.keys(); names.hasNext();) {
                collectRevisionIds(skills.getJSONObject(names.next()), live);
            }
            JSONArray pending = new JSONArray();
            for (String id : retiredVersions(state)) {
                if (!live.contains(id) && !removeRevisionDocuments(id)) pending.put(id);
            }
            if (pending.length() == journal.length()) return;
            if (pending.length() == 0) state.remove(RETIRED_VERSIONS);
            else state.put(RETIRED_VERSIONS, pending);
            writeState(state);
        } catch (IOException | JSONException | RuntimeException ignored) {
            // The committed catalog still has the cleanup journal. A later open can retry it.
        }
    }

    private boolean removeRevisionDocuments(String id) {
        try {
            Path versions = directoryKey.resolve("versions");
            Path slot = versions.resolve(id);
            if (!Files.readAttributes(versions, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                    || !versions.equals(versions.toRealPath())) return false;
            if (!Files.readAttributes(slot, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isDirectory()
                    || !slot.equals(slot.toRealPath())) return false;
            boolean documentation = removeRevisionDocument(slot.resolve("SKILL.md"));
            boolean execution = removeRevisionDocument(slot.resolve("execution.json"));
            if (!documentation || !execution) return false;
            Files.deleteIfExists(slot);
            return true;
        } catch (NoSuchFileException removed) {
            return true;
        } catch (IOException | RuntimeException unavailable) {
            return false;
        }
    }

    private static boolean removeRevisionDocument(Path path) throws IOException {
        try {
            if (!Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).isRegularFile()) {
                return false;
            }
            Files.deleteIfExists(path);
            return true;
        } catch (NoSuchFileException removed) {
            return true;
        }
    }

    private static Path canonical(File directory) {
        try { return directory.getCanonicalFile().toPath(); }
        catch (IOException unavailable) { return directory.toPath().toAbsolutePath().normalize(); }
    }

    private static Executable readExecution(File revision) throws Exception {
        String content;
        try (FileInputStream stream = new FileInputStream(new File(revision, "execution.json"))) {
            content = readText(stream);
        }
        JSONObject definition = object(content, LIMIT);
        int schema = definition.getInt("schemaVersion");
        if (schema != 1 && schema != 2 && schema != AUTHORED_SCHEMA) throw new IOException("技能版本不兼容");
        SkillResult.Type output = schema == 1 ? SkillResult.Type.TEXT
                : SkillResult.Type.fromWire(definition.getString("outputType"));
        String kind = definition.getString("kind");
        int maxInput = definition.getInt("maxInput");
        if ("summary".equals(kind) && output == SkillResult.Type.TEXT) {
            return new Executable(kind, maxInput, output, null, "", schema);
        }
        if (schema == AUTHORED_SCHEMA && TEXT_MODEL.equals(kind) && output == SkillResult.Type.TEXT) {
            JSONObject authored = SkillAuthoring.readObject(content, LIMIT);
            keys(authored, "schemaVersion", "version", "kind", "maxInput", "outputType", "instruction");
            int validatedLimit = integer(authored, "maxInput");
            if (integer(authored, "schemaVersion") != AUTHORED_SCHEMA || validatedLimit < 1
                    || validatedLimit > TEXT_MODEL_MAX_INPUT) throw new IOException("技能版本不兼容");
            return new Executable(kind, validatedLimit, output, null, modelInstruction(authored.get("instruction")), schema);
        }
        if (!"service".equals(kind)) throw new IOException("技能类型不支持");
        return new Executable(kind, maxInput, output, readService(definition.getJSONObject("service"), output), "", schema);
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
                if (!cleanupChecked) {
                    cleanupChecked = true;
                    cleanupRetiredVersions(state);
                }
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
