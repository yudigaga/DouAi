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
    private static final int LIMIT = 96 * 1024;

    public record Installed(String name, int version, boolean enabled, boolean canRollback,
                            SkillResult.Type outputType, String serviceLabel) {}
    public record Preview(String name, String serviceLabel, String endpoint, int costUnits,
                          boolean needsConfirmation, String serviceId, SkillResult.Type outputType,
                          String pendingReason, int previousVersion, int latestVersion) {}
    public record Pending(String name, SkillResult.Type outputType, String reason) {}
    public record SaveResult(Installed installed, Pending pending) {}
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

    public static boolean requiresImage(String requirements) {
        if (requirements == null) return false;
        String input = requirements.trim();
        return !EXPLAINS_IMAGE.matcher(input).lookingAt() && IMAGE_INTENT.matcher(input).find();
    }

    public static String mockDraft(String requirements) throws Exception {
        String input = text(requirements == null ? "" : requirements.trim(), 4000);
        if (requiresImage(input)) {
            Matcher name = REQUESTED_NAME.matcher(input);
            String command = name.find() ? (name.group(1) == null ? name.group(2) : name.group(1)) : "生图";
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
        return locked(state -> preview(state, draft, resolve(draft)));
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
        return saveGenerated(json, confirmation, false);
    }

    public SaveResult connectPending(String json, Preview confirmation) throws Exception {
        return saveGenerated(json, confirmation, true);
    }

    private SaveResult saveGenerated(String json, Preview confirmation, boolean reconnect) throws Exception {
        Draft draft = parse(json);
        return locked(state -> {
            if (reconnect) {
                JSONObject currentDraft = state.getJSONObject("drafts").optJSONObject(draft.name());
                if (currentDraft == null || !json.equals(currentDraft.getString("definition"))) {
                    throw new IllegalStateException("待连接草稿已变化，请重新打开");
                }
            }
            SkillServices.Service service = resolve(draft);
            Preview current = preview(state, draft, service);
            if (!current.pendingReason().isEmpty()) return savePending(state, draft, json, current.pendingReason());
            SkillResult simulated = trial(draft, service);
            if (simulated.type() != draft.outputType()
                    || (simulated instanceof SkillResult.Text text && !text.text().equals(draft.expected()))) {
                throw new IllegalArgumentException("模拟试跑结果与预期不符");
            }
            if ((current.needsConfirmation() || confirmation != null) && !current.equals(confirmation)) {
                throw new IllegalStateException("技能版本或服务已变化，请重新预览并确认");
            }
            if (service != null && !services.isAuthorized(service)) {
                if (draft.outputType() == SkillResult.Type.IMAGE) return savePending(state, draft, json, NEEDS_IMAGE_SERVICE);
                throw new IllegalStateException("请先授权登记的服务");
            }
            return new SaveResult(publish(state, draft, service), null);
        });
    }

    private SaveResult savePending(JSONObject state, Draft draft, String json, String reason) throws Exception {
        state.getJSONObject("drafts").put(draft.name(), new JSONObject()
                .put("outputType", draft.outputType().wireName()).put("reason", reason).put("definition", json));
        writeState(state);
        return new SaveResult(null, new Pending(draft.name(), draft.outputType(), reason));
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

    private SkillServices.Service resolve(Draft draft) throws Exception {
        if ("summary".equals(draft.kind())) return null;
        if (draft.outputType() == SkillResult.Type.TEXT) {
            SkillServices.Service service = services.find(draft.serviceId());
            if (service.outputType() != SkillResult.Type.TEXT) throw new IllegalArgumentException("请选择文本服务");
            return service;
        }

        SkillServices.Service preferred = null;
        SkillServices.Service mock = null;
        for (SkillServices.Service service : services.list()) {
            if (service.outputType() != SkillResult.Type.IMAGE
                    || (draft.serviceId() != null && !draft.serviceId().equals(service.id()))
                    || !services.isAuthorized(service)) continue;
            if (SkillServices.MOCK_IMAGE.equals(service.id())) mock = service;
            else if (preferred == null || service.id().compareTo(preferred.id()) < 0) preferred = service;
        }
        return preferred == null ? mock : preferred;
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
        String pending = service == null && draft.outputType() == SkillResult.Type.IMAGE ? NEEDS_IMAGE_SERVICE : "";
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
                        .put("endpoint", service.endpoint()).put("costUnits", service.costUnits()));
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
            if ("summary".equals(kind) && output == SkillResult.Type.TEXT) {
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
                service.getString("label"), service.getString("endpoint"), service.getInt("costUnits"), output));
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
