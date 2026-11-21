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
import java.util.regex.Pattern;

/** Publishes a version only after validation, simulation and both documents reach disk. */
public final class SkillStore {
    public static final String GENERATION_INSTRUCTION = """
            你在为 DouAi 生成纯文本技能。只返回一个 JSON 对象，别加代码围栏或额外字段。
            对象包含 schemaVersion:1、name（1-24 字，不含空格和斜杠）、description、
            documentation（SKILL.md 正文）、execution 和 trial。
            execution: {kind:"summary",maxInput:4000} 按前两句摘要，
            或 {kind:"service",maxInput:4000,serviceId:"已登记接口的 ID"}。
            trial: {input:"测试文本",expected:"完整的模拟输出"}。
            摘要可用同一句短文本作为输入和输出。
            mock-orders 试跑用 DA-1001，输出为 模拟查询：订单 DA-1001 已发货。
            其他已登记服务的试跑输出固定为 模拟服务响应。不得输出代码、地址、密钥或请求头。
            """;
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9-]{36}");
    private static final int LIMIT = 96 * 1024;

    public record Installed(String name, int version, boolean enabled, boolean canRollback) {}
    public record Preview(String name, String serviceLabel, String endpoint, int costUnits,
                          boolean needsConfirmation, String serviceId) {}
    record Available(AiSkills.Command command, int version) {}
    private record Draft(String name, String description, String documentation, String kind,
                         int maxInput, String serviceId, String trialInput, String expected) {}
    private record Executable(String kind, int maxInput, SkillServices.Service service) {}

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
                entries.add(new Available(new AiSkills.Command(name, revision.getString("description")),
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
            metadata.put(new JSONObject().put("id", service.id()).put("name", service.label()));
        }
        return new JSONObject().put("requirements", input).put("registeredServices", metadata).toString();
    }

    public static String mockDraft(String requirements) throws Exception {
        String input = text(requirements == null ? "" : requirements.trim(), 4000);
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

    public Preview previewGenerated(String json) throws Exception {
        Draft draft = parse(json);
        SkillServices.Service service = resolve(draft);
        return read(state -> preview(state, draft, service));
    }

    public Installed installGenerated(String json) throws Exception {
        return installGenerated(json, null);
    }

    public Installed installGenerated(String json, Preview confirmation) throws Exception {
        Draft draft = parse(json);
        SkillServices.Service service = resolve(draft);
        if (!trial(draft, service).equals(draft.expected())) {
            throw new IllegalArgumentException("模拟试跑结果与预期不符");
        }
        return locked(state -> {
            Preview current = preview(state, draft, service);
            if (current.needsConfirmation() && !current.equals(confirmation)) {
                throw new IllegalStateException("请确认技能的服务目标和费用");
            }
            if (service != null && !SkillServices.MOCK_ORDERS.equals(service.id())
                    && !services.isAuthorized(service)) {
                throw new IllegalStateException("请先授权登记的服务");
            }
            return publish(state, draft, service);
        });
    }

    String execute(String name, int version, String argument) throws Exception {
        Executable definition = locked(state -> {
            JSONObject skill = skill(state, name);
            JSONObject revision = activeRevision(skill);
            if (!state.getBoolean("enabled") || !skill.getBoolean("enabled")
                    || revision.getInt("version") != version) {
                throw new IllegalStateException("技能版本已变更或停用");
            }
            return readExecution(revisionDirectory(revision));
        });
        checkArgument(argument, definition.maxInput());
        if ("summary".equals(definition.kind())) return AiSkills.summarize(argument);
        return services.execute(definition.service(), argument);
    }

    private SkillServices.Service resolve(Draft draft) throws Exception {
        return draft.serviceId() == null ? null : services.find(draft.serviceId());
    }

    private static String trial(Draft draft, SkillServices.Service service) {
        checkArgument(draft.trialInput(), draft.maxInput());
        if (service == null) return AiSkills.summarize(draft.trialInput());
        if (SkillServices.MOCK_ORDERS.equals(service.id())) return AiSkills.lookupOrder(draft.trialInput());
        return "模拟服务响应";
    }

    private Preview preview(JSONObject state, Draft draft, SkillServices.Service service) throws Exception {
        if (service == null || SkillServices.MOCK_ORDERS.equals(service.id())) {
            return new Preview(draft.name(), "", "", 0, false, "");
        }
        JSONObject old = state.getJSONObject("skills").optJSONObject(draft.name());
        boolean changed = true;
        if (old != null) {
            Executable previous = readExecution(revisionDirectory(activeRevision(old)));
            changed = !service.equals(previous.service());
        }
        return new Preview(draft.name(), service.label(), service.endpoint(), service.costUnits(), changed, service.id());
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
                .put("description", draft.description());
        File target = revisionDirectory(revision);
        if (!target.mkdirs()) throw new IOException("无法创建技能版本");
        try {
            writeDocument(new File(target, "SKILL.md"),
                    "---\nname: " + draft.name() + "\nversion: " + number + "\n---\n\n" + draft.documentation() + "\n");
            JSONObject definition = new JSONObject().put("schemaVersion", 1)
                    .put("version", number).put("kind", draft.kind()).put("maxInput", draft.maxInput());
            if (service != null) {
                definition.put("service", new JSONObject().put("id", service.id()).put("label", service.label())
                        .put("endpoint", service.endpoint()).put("costUnits", service.costUnits()));
            }
            writeDocument(new File(target, "execution.json"), definition.toString());
            versions.put(revision);
            skill.put("active", versions.length() - 1).put("enabled", true);
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
            if (integer(value, "schemaVersion") != 1) throw new IllegalArgumentException();
            String name = text(value.get("name"), 24);
            if (!NAME.matcher(name).matches() || AiSkills.isBuiltIn(name) || name.startsWith("mcp-")) throw new IllegalArgumentException();
            String description = text(value.get("description"), 100);
            if (description.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException();
            String documentation = text(value.get("documentation"), 6000);
            JSONObject run = value.getJSONObject("execution");
            String kind = text(run.get("kind"), 16);
            String service = null;
            if ("summary".equals(kind)) {
                keys(run, "kind", "maxInput");
            } else if ("service".equals(kind)) {
                keys(run, "kind", "maxInput", "serviceId");
                service = text(run.get("serviceId"), 64);
            } else {
                throw new IllegalArgumentException();
            }
            int maxInput = integer(run, "maxInput");
            if (maxInput < 1 || maxInput > 4000) throw new IllegalArgumentException();
            JSONObject test = value.getJSONObject("trial");
            keys(test, "input", "expected");
            return new Draft(name, description, documentation, kind, maxInput, service,
                    text(test.get("input"), maxInput), text(test.get("expected"), 4000));
        } catch (Exception invalid) {
            throw new IllegalArgumentException("生成的技能定义结构或参数无效");
        }
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
        return new Installed(name, activeRevision(skill).getInt("version"),
                skill.getBoolean("enabled"), skill.getInt("active") > 0);
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
        if (definition.getInt("schemaVersion") != 1) throw new IOException("技能版本不兼容");
        String kind = definition.getString("kind");
        int maxInput = definition.getInt("maxInput");
        if ("summary".equals(kind)) return new Executable(kind, maxInput, null);
        if (!"service".equals(kind)) throw new IOException("技能类型不支持");
        JSONObject service = definition.getJSONObject("service");
        return new Executable(kind, maxInput, new SkillServices.Service(service.getString("id"),
                service.getString("label"), service.getString("endpoint"), service.getInt("costUnits")));
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
