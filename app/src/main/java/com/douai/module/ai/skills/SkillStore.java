package com.douai.module.ai.skills;

import android.content.Context;
import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.diagnostics.DouAiLog;

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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Publishes a version only after validation, simulation and both documents reach disk. */
public final class SkillStore {
    private static final Pattern NAME = Pattern.compile("[\\p{L}\\p{N}_-]{1,24}");
    private static final Pattern REVISION = Pattern.compile("[a-f0-9-]{36}");
    private static final Pattern IMAGE_INTENT = Pattern.compile(
            "(?i)(?:生图|文生图|画图|绘图|作画|画(?:一[张幅只]|个)|(?:生成|绘制|制作|创作|设计|创建|输出|返回)(?:一[张幅]|几张)?(?:图片|图像|插画|海报)(?!(?:的)?(?:提示词|文案|教程|说明))|(?:generate|create)\\s+(?:an?\\s+)?(?:image|picture))");
    private static final Pattern EXPLAINS_IMAGE = Pattern.compile(
            "^(?:请\\s*)?(?:帮我\\s*)?(?:解释|介绍|总结|查询|整理|写一篇|告诉我|如何|不需要|不要|无需).*");
    private static final Pattern REQUESTED_NAME = Pattern.compile(
            "(?:名为|命名为|叫做)\\s*(?:[「“\"]([\\p{L}\\p{N}_-]{1,24})[」”\"]|([\\p{L}\\p{N}_-]{1,24})(?=\\s|[，。]|$))");
    private static final Pattern VIDEO_JSON_FENCE = Pattern.compile(
            "\\A[ \\t\\r\\n]*+```(?:json)?[ \\t]*+\\r?\\n(.+?)\\r?\\n```[ \\t\\r\\n]*+\\z", Pattern.DOTALL);
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
    private static final int MANIFEST_SCHEMA = 4;
    private static final int TEXT_MODEL_MAX_INPUT = 2000;
    private static final int TEXT_MODEL_MAX_INSTRUCTION = 1600;
    private static final String TEXT_MODEL = "text_model";
    private static final String CUSTOM_KIND = "custom_service";
    private static final String CUSTOM_BINDING = "customBinding";
    private static final String VIDEO_FACTS = "videoFacts";
    private static final String VIDEO_DRAFT_CONFIG = "videoDraftConfig";
    private static final String VIDEO_CONTRACT_REPLACEMENT = "videoContractReplacement";
    private static final String NEEDS_CUSTOM_SERVICE = "请在技能详情填写公网 HTTPS 接口地址";
    private static final String NEEDS_API_BASE = "请在技能详情填写公网 HTTPS API 基础地址";
    private static final String RESERVED_CREATION_NAME = "/技能 是创建入口，请使用其他技能名称";
    public static final String NEEDS_VIDEO_PROVIDER = "请补充视频任务的提交、查询、取消和下载接口声明";
    private static final String VIDEO_JSON_ENCODING = "application/json";
    private static final String NEEDS_VIDEO_ENCODING = "视频提交接口需要支持 application/json，请核对并填写提交请求编码";
    private static final String RETAINED_SERVICE = "retainedService";
    private static final String NEEDS_MANIFEST_VALUES = "请填写技能必填设置";

    public record Installed(String name, int version, boolean enabled,
                            SkillResult.Type outputType, String serviceLabel, String configurationRequired) {}
    public record Preview(String name, String serviceLabel, String endpoint, int costUnits,
                          boolean needsConfirmation, String serviceId, SkillResult.Type outputType,
                          String pendingReason, int previousVersion, DeleteTarget identity) {}
    public record Pending(String name, SkillResult.Type outputType, String reason, String draftId) {}
    public enum DraftState { NEEDS_CONFIGURATION, NEEDS_AUTHORIZATION, READY, DRAFT }
    public record SettingsRow(String name, Installed current, SkillResult.Type draftOutputType, DraftState draftState,
                              String displayName) {}
    public record SettingsSnapshot(boolean enabled, List<SettingsRow> rows) {}
    public record SaveResult(Installed installed, Pending pending) {}
    public record ConversationDraft(DraftPreview preview, SkillVideoFacts facts, String pendingId, DeleteTarget identity) {}
    public record DraftPreview(String name, String description, SkillResult.Type outputType,
                               String instruction, String definition, String capabilityId) {
        public DraftPreview(String name, String description, SkillResult.Type outputType, String instruction,
                            String definition) {
            this(name, description, outputType, instruction, definition, "");
        }

        public Map<String, Object> publicFields() {
            SkillManifest manifest = parseAuthored(definition).manifest();
            if (manifest == null) return Collections.emptyMap();
            Map<String, Object> output = new LinkedHashMap<>();
            manifest.draftValues().forEach((key, value) -> output.put("config." + key, value));
            SkillManifest.ServicePrefill prefill = manifest.servicePrefill();
            if (!prefill.endpoint().isEmpty()) output.put("service.endpoint", prefill.endpoint());
            if (!prefill.model().isEmpty()) output.put("service.model", prefill.model());
            return Collections.unmodifiableMap(output);
        }
    }
    public record CustomConfiguration(String endpoint, boolean hasCredential) {}

    private enum ReviewState { OPEN, APPROVED, CONSUMED }
    private record LegacyReview(SkillServices.Service service, String payload, String proof, long expiresAt,
                                AtomicReference<ReviewState> state) {}

    public static final class CustomReview {
        private final AiSkills.Invocation invocation;
        private final SkillCustomService.Preview preview;
        private final LegacyReview legacy;

        private CustomReview(AiSkills.Invocation invocation, SkillCustomService.Preview preview, LegacyReview legacy) {
            this.invocation = invocation;
            this.preview = preview;
            this.legacy = legacy;
        }

        public String endpoint() { return legacy == null ? preview.endpoint() : legacy.service().endpoint(); }
        public String payload() { return legacy == null ? preview.payload() : legacy.payload(); }
        public SkillHttpDeclaration.Video video() {
            return preview == null || preview.declaration() == null ? null : preview.declaration().video();
        }
    }

    public static final class CustomCall {
        private final CustomReview review;
        private final SkillCustomService.Approval approval;

        private CustomCall(CustomReview review, SkillCustomService.Approval approval) {
            this.review = review;
            this.approval = approval;
        }
    }
    public record VideoTurn(SkillVideoJobs jobs, SkillVideoJobs.Scope scope, long generation, UUID requestId) {}
    public enum FieldState { FILLED, MISSING, NEEDS_AUTHORIZATION }
    public record Field(String id, String label, boolean required, FieldState state) {}
    public record DeleteTarget(String name, int versionCount, boolean hasDraft, String proof) {}
    public record EditTarget(DeleteTarget identity, String description, String documentation,
                             String instruction, boolean instructionEditable, String manifestFieldsJson,
                             String manifestDisplayName) {}
    public record Detail(String name, SkillResult.Type outputType, String draftId, String reason,
                         List<Field> fields, SkillServices.Service service, boolean canAuthorize,
                         boolean canReplace, int previousVersion, boolean installedEnabled,
                         DeleteTarget deleteTarget, Installed current, SkillServices.Service currentService,
                         boolean skillsEnabled, DraftState draftState, SkillManifest manifest,
                         Map<String, Object> manifestValues, CustomConfiguration customConfiguration,
                         SkillVideoFacts videoFacts) {
        public boolean pending() { return !draftId.isEmpty(); }
        public List<String> videoMissing() {
            if (videoFacts != null) return videoFacts.missing();
            if (pending() && outputType == SkillResult.Type.VIDEO && manifest != null && manifest.http() == null) {
                return SkillVideoFacts.empty().missing();
            }
            return Collections.emptyList();
        }
    }
    private enum SaveMode { GENERATE, RECONNECT, CONVERSATION }
    private record Configuration(List<Field> fields, boolean canAuthorize) {}
    private record DraftStatus(SkillServices.Service service, List<Field> fields, String reason,
                               boolean canAuthorize, boolean canReplace, DraftState state) {}
    private record EditSource(EditTarget target, JSONObject definition, JSONObject pending,
                              SkillServices.Service retainedService, Map<String, Object> values) {}
    record Available(AiSkills.Command command, int version, String revisionId) {}
    private record Draft(String name, String description, String documentation, String kind,
                         int maxInput, SkillResult.Type outputType, String serviceId, String trialInput,
                         String expected, String instruction, int schemaVersion, SkillManifest manifest) {
        DraftPreview preview(String definition) {
            return new DraftPreview(name, description, outputType, instruction, definition,
                    manifest == null ? "" : manifest.capability().id());
        }
    }
    private record Executable(String kind, int maxInput, SkillResult.Type outputType,
                              SkillServices.Service service, String instruction, int schemaVersion,
                              SkillManifest manifest, Map<String, Object> values) {}
    private interface StateAction<T> { T run(JSONObject state) throws Exception; }
    @FunctionalInterface interface VideoModelQuery { List<String> fetch(String endpoint, String bearer) throws IOException; }
    private final File directory;
    private final Path directoryKey;
    private final File catalog;
    private final SkillServices services;
    private final SkillCustomService customService;
    private final McpRegistry mcp;
    private final VideoModelQuery videoModels;
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
        this(directory, services, mcp, SkillCustomService.forDevice());
    }

    SkillStore(File directory, SkillServices services, McpRegistry mcp, SkillCustomService customService) {
        this(directory, services, mcp, customService, SkillVideoModelCatalog::fetch);
    }

    SkillStore(File directory, SkillServices services, McpRegistry mcp, SkillCustomService customService,
               VideoModelQuery videoModels) {
        this.directory = directory;
        this.directoryKey = canonical(directory);
        this.catalog = new File(directory, "catalog.json");
        this.services = services;
        this.mcp = mcp;
        this.customService = customService;
        this.videoModels = videoModels;
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

    public SettingsSnapshot settingsSnapshot() {
        return read(state -> {
            JSONObject skills = state.getJSONObject("skills");
            JSONObject drafts = state.getJSONObject("drafts");
            Set<String> names = new LinkedHashSet<>();
            skills.keys().forEachRemaining(names::add);
            drafts.keys().forEachRemaining(names::add);
            List<SettingsRow> rows = new ArrayList<>();
            for (String name : names) {
                JSONObject revision = skills.optJSONObject(name);
                Installed current = revision == null ? null : installed(name, revision);
                String displayName = current == null ? name : activeRevision(revision).optString("displayName", name);
                JSONObject entry = drafts.optJSONObject(name);
                SkillResult.Type draftOutputType = null;
                DraftState pending = null;
                if (entry != null) {
                    draftOutputType = resultType(entry);
                    try {
                        Draft draft = parse(entry.getString("definition"));
                        draftOutputType = draft.outputType();
                        if (current == null && draft.manifest() != null) displayName = draft.manifest().displayName();
                        pending = draftStatus(state, draft, entry, current == null ? 0 : current.version()).state();
                    } catch (SkillServices.Failure | IllegalArgumentException unavailable) {
                        pending = DraftState.DRAFT;
                    }
                }
                rows.add(new SettingsRow(name, current, draftOutputType, pending, displayName));
            }
            return new SettingsSnapshot(state.getBoolean("enabled"), Collections.unmodifiableList(rows));
        });
    }

    List<Available> available() {
        return read(state -> {
            if (!state.getBoolean("enabled")) return Collections.emptyList();
            List<Available> entries = new ArrayList<>();
            JSONObject skills = state.getJSONObject("skills");
            for (Iterator<String> names = skills.keys(); names.hasNext();) {
                String name = names.next();
                if (AiSkills.CREATION_COMMAND_NAME.equals(name)) continue;
                JSONObject skill = skills.getJSONObject(name);
                if (!skill.getBoolean("enabled")) continue;
                JSONObject revision = activeRevision(skill);
                if (!configurationRequired(skill, revision).isEmpty()) continue;
                boolean review = usesHttp(revision) || "service".equals(revision.optString("kind"))
                        && liveService(readExecution(revisionDirectory(revision)).service());
                entries.add(new Available(new AiSkills.Command(name, revision.getString("description"), resultType(revision), review),
                        revision.getInt("version"), revision.getString("directory")));
            }
            return entries;
        });
    }

    public List<SkillSelection.Candidate> selectionCatalog() {
        return read(state -> {
            if (!state.getBoolean("enabled")) return List.of();
            List<SkillSelection.Candidate> candidates = new ArrayList<>();
            JSONObject active = state.getJSONObject("skills");
            for (Iterator<String> names = active.keys(); names.hasNext();) {
                String name = names.next();
                if (AiSkills.CREATION_COMMAND_NAME.equals(name)) continue;
                JSONObject skill = active.getJSONObject(name);
                JSONObject revision = activeRevision(skill);
                if (!skill.getBoolean("enabled") || !configurationRequired(skill, revision).isEmpty()) continue;
                try {
                    Executable executable = readExecution(revisionDirectory(revision));
                    if (executable.service() != null && !authorized(executable.service())) continue;
                    if (liveService(executable.service())) SkillHttpsTransport.validateEndpoint(executable.service().endpoint());
                    SkillManifest manifest = executable.manifest();
                    if (manifest != null && !manifest.missing(executable.values()).isEmpty()) continue;
                    List<String> labels = new ArrayList<>();
                    if (manifest != null) for (SkillManifest.ConfigField field : manifest.configFields()) labels.add(field.label());
                    candidates.add(new SkillSelection.Candidate(name, revision.optString("displayName", name),
                            revision.getString("description"), List.copyOf(labels), executable.maxInput(),
                            revision.getInt("version"), revision.getString("directory")));
                } catch (Exception unavailable) { /* Invalid, unavailable or revoked skills stay out of model input. */ }
            }
            return List.copyOf(candidates);
        });
    }

    public List<String> unavailableSelectionNames() {
        Set<String> runnable = new HashSet<>();
        for (SkillSelection.Candidate candidate : selectionCatalog()) runnable.add(candidate.name());
        return read(state -> {
            Set<String> names = new LinkedHashSet<>();
            JSONObject installed = state.getJSONObject("skills");
            JSONObject drafts = state.getJSONObject("drafts");
            installed.keys().forEachRemaining(names::add);
            drafts.keys().forEachRemaining(names::add);
            names.removeAll(runnable);
            names.remove(AiSkills.CREATION_COMMAND_NAME);
            Set<String> aliases = new LinkedHashSet<>(names);
            for (String name : names) {
                JSONObject active = installed.optJSONObject(name);
                if (active != null) aliases.add(activeRevision(active).optString("displayName", name));
                JSONObject pending = drafts.optJSONObject(name);
                if (pending != null) {
                    try {
                        SkillManifest manifest = parse(pending.getString("definition")).manifest();
                        if (manifest != null) aliases.add(manifest.displayName());
                    } catch (Exception unavailable) { /* Keep the canonical local command name. */ }
                }
            }
            return List.copyOf(aliases);
        });
    }

    public void setSkillEnabled(String name, boolean enabled) {
        if (enabled) requireRegistrableName(name);
        read(state -> {
            JSONObject record = skill(state, name);
            if (enabled) {
                String missing = configurationRequired(record, activeRevision(record));
                if (!missing.isEmpty()) throw new IllegalStateException(missing);
            }
            record.put("enabled", enabled);
            writeState(state);
            return null;
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
        int versions = installed == null ? 0 : 1;
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
        return saveEdit(expected, description, documentation, instruction, null);
    }

    public Detail saveEdit(EditTarget expected, String description, String documentation, String instruction,
                           String manifestFieldsJson) throws Exception {
        return saveEdit(expected, description, documentation, instruction, manifestFieldsJson, null);
    }

    public Detail saveEdit(EditTarget expected, String description, String documentation, String instruction,
                           String manifestFieldsJson, String displayName) throws Exception {
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
            Draft previous = parse(definition.toString());
            definition.put("description", heading).put("documentation", body);
            if (expected.instructionEditable()) {
                String rule = modelInstruction(instruction);
                SkillAuthoring.rejectCredential(rule);
                if (previous.manifest() == null) definition.getJSONObject("execution").put("instruction", rule);
                else definition.put("instructions", rule);
            } else if (instruction != null && !instruction.isEmpty()) {
                throw new IllegalArgumentException("此技能没有可编辑的模型指令");
            }
            if (manifestFieldsJson != null) {
                if (previous.manifest() == null) throw new IllegalArgumentException("此技能没有可编辑的配置声明");
                JSONObject wrapped = SkillAuthoring.readObject("{\"configFields\":" + manifestFieldsJson + "}", DRAFT_LIMIT);
                JSONArray descriptors = wrapped.optJSONArray("configFields");
                if (wrapped.length() != 1 || descriptors == null) {
                    throw new SkillManifest.Invalid("技能配置声明需要一个 JSON 数组");
                }
                definition.put("configFields", descriptors);
            }
            if (displayName != null) {
                if (previous.manifest() == null) throw new IllegalArgumentException("此技能没有可编辑的显示名称");
                String label = displayName.trim();
                if (label.isEmpty()) definition.remove("displayName");
                else definition.put("displayName", label);
            }

            String json = definition.toString();
            Draft draft = parse(json);
            Map<String, Object> values = draft.manifest() == null ? Collections.emptyMap()
                    : draft.manifest().retainValues(previous.manifest(), source.values());
            JSONObject entry = stageEdit(state, source, draft, json, values);
            return advancePending(state, entry, draft, null, true);
        });
    }

    public Detail saveManifestValues(Detail expected, Map<String, String> entered) throws Exception {
        if (expected == null || expected.manifest() == null || expected.deleteTarget() == null
                || !expected.manifest().hasConfigurableFields()) {
            throw new IllegalArgumentException("技能配置目标无效");
        }
        return locked(state -> {
            EditSource source = editSource(state, expected.deleteTarget());
            String json = source.definition().toString();
            Draft draft = parse(json);
            String draftId = source.pending() == null ? "" : draftIdentity(draft.name(), source.pending());
            if (!expected.name().equals(draft.name()) || !expected.draftId().equals(draftId)
                    || !expected.manifest().equals(draft.manifest()) || !expected.manifestValues().equals(source.values())) {
                throw new IllegalStateException("技能配置已变化，请重新打开");
            }
            Map<String, Object> values = draft.manifest().enteredValues(entered == null ? Collections.emptyMap() : entered);
            JSONObject entry = stageEdit(state, source, draft, json, values);
            return advancePending(state, entry, draft, null);
        });
    }

    public Detail saveVideoDeclaration(Detail expected, String raw) throws Exception {
        requireIncompleteVideo(expected);
        return locked(state -> {
            EditSource source = videoEditSource(state, expected);
            requireSupportedVideoEncoding(pendingVideoFacts(source.pending()));
            JSONObject definition = source.definition();
            Draft updated = attachVideoDeclaration(definition, raw);
            JSONObject retained = source.pending().optJSONObject(VIDEO_DRAFT_CONFIG);
            Map<String, Object> values = retained == null ? updated.manifest().retainValues(expected.manifest(), source.values())
                    : restoreVideoDraftConfig(updated.manifest(), retained);
            JSONObject entry = stageEdit(state, source, updated, definition.toString(), values);
            entry.put(CUSTOM_BINDING, JSONObject.NULL);
            entry.remove(VIDEO_FACTS);
            entry.remove(VIDEO_DRAFT_CONFIG);
            return advancePending(state, entry, updated, null, true);
        });
    }

    public Detail replaceVideoContract(Detail expected, SkillVideoFacts replacement) throws Exception {
        if (replacement == null || replacement.isEmpty()) throw new IllegalArgumentException("请提供新的视频接口资料");
        JSONObject publicFacts = replacement.toJson();
        if (publicFacts.optString("submitEndpoint").isEmpty() && replacement.submitPath().isEmpty()) {
            throw new IllegalArgumentException("请提供新的视频提交地址或 POST 路径");
        }
        return locked(state -> {
            EditSource source = contractEditSource(state, expected);
            Draft previous = parse(source.definition().toString());
            if (previous.outputType() != SkillResult.Type.VIDEO) throw new IllegalArgumentException("此技能没有视频接口");
            return stageVideoReplacement(state, source, previous, source.definition().toString(), replacement);
        });
    }

    private Detail stageVideoReplacement(JSONObject state, EditSource source, Draft previous,
                                         String raw, SkillVideoFacts replacement) throws Exception {
        JSONObject definition = SkillAuthoring.readObject(raw, DRAFT_LIMIT);
        definition.remove(SkillManifest.DRAFT_VALUES);
        definition.remove(SkillManifest.SERVICE_PREFILL);
        DraftPreview prepared = prepareAuthoredVideoDraft(definition.toString(), replacement);
        Draft updated = parseAuthored(prepared.definition());
        Map<String, Object> values = authoredVideoDefaults(updated);
        SkillCustomService.Binding active = readCustomBinding(state.getJSONObject("skills").optJSONObject(previous.name()));
        SkillCustomService.Binding pending = readCustomBinding(source.pending());
        verifyVideoSubmitOmitsCredential(active, replacement);
        if (!Objects.equals(active, pending)) verifyVideoSubmitOmitsCredential(pending, replacement);
        verifyContractOmitsPriorCredentials(active, pending, replacement.toJson(), SkillManifest.valuesJson(values));
        JSONObject entry = stageEdit(state, source, updated, prepared.definition(), values);
        saveGroundedVideoFacts(entry, replacement);
        entry.put(VIDEO_CONTRACT_REPLACEMENT, true);
        entry.remove(VIDEO_DRAFT_CONFIG);
        return advancePending(state, entry, updated, null, true);
    }

    public Detail replaceDirectContract(Detail expected, Map<String, String> request, String responseField) throws Exception {
        if (request == null || request.isEmpty() || request.size() > 7 || responseField == null) {
            throw new IllegalArgumentException("请填写有效的请求和响应字段");
        }
        Map<String, String> fields = new LinkedHashMap<>(request);
        return locked(state -> {
            EditSource source = contractEditSource(state, expected);
            Draft previous = parse(source.definition().toString());
            SkillManifest manifest = previous.manifest();
            if (manifest.capability() != SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                    || previous.outputType() == SkillResult.Type.VIDEO) {
                throw new IllegalArgumentException("此技能没有直接 HTTPS 接口");
            }
            SkillHttpDeclaration old = manifest.http();
            JSONObject wire = new JSONObject().put("version", SkillHttpDeclaration.VERSION)
                    .put("label", old == null ? "HTTPS 服务" : old.label())
                    .put("request", new JSONObject(fields)).put("response", responseField);
            SkillHttpDeclaration proposed = SkillHttpDeclaration.parse(wire, previous.outputType(), manifest.configFields());
            Map<String, String> oldRequest = old == null ? Map.of(SkillCustomService.REQUEST_FIELD, "argument") : old.request();
            String oldResponse = old == null ? SkillCustomService.RESPONSE_FIELD : old.responseField();
            if (oldRequest.equals(proposed.request()) && oldResponse.equals(proposed.responseField())) {
                return detail(state, previous.name());
            }
            SkillCustomService.Binding active = readCustomBinding(state.getJSONObject("skills").optJSONObject(previous.name()));
            SkillCustomService.Binding pending = readCustomBinding(source.pending());
            verifyContractOmitsPriorCredentials(active, pending, wire, SkillManifest.valuesJson(source.values()));
            JSONObject definition = source.definition().put("http", wire);
            String json = definition.toString();
            Draft updated = parseAuthored(json);
            JSONObject entry = stageEdit(state, source, updated, json, source.values());
            entry.put(CUSTOM_BINDING, JSONObject.NULL);
            return advancePending(state, entry, updated, null, true);
        });
    }

    private EditSource contractEditSource(JSONObject state, Detail expected) throws Exception {
        if (expected == null || expected.manifest() == null || expected.deleteTarget() == null
                || !Objects.equals(expected.name(), expected.deleteTarget().name())) {
            throw new IllegalArgumentException("接口配置目标无效");
        }
        EditSource source = editSource(state, expected.deleteTarget());
        Draft actual = parse(source.definition().toString());
        String currentDraft = source.pending() == null ? "" : draftIdentity(expected.name(), source.pending());
        if (!currentDraft.equals(expected.draftId()) || !expected.manifest().equals(actual.manifest())
                || !source.values().equals(expected.manifestValues())) {
            throw new IllegalStateException("技能接口配置已变化，请重新打开");
        }
        return source;
    }

    private void verifyContractOmitsPriorCredentials(SkillCustomService.Binding active, SkillCustomService.Binding pending,
                                                     JSONObject wire, JSONObject values) throws SkillCustomService.Failure {
        verifyContractOmitsCredential(active, wire, values);
        if (!Objects.equals(active, pending)) verifyContractOmitsCredential(pending, wire, values);
    }

    private void verifyContractOmitsCredential(SkillCustomService.Binding previous, JSONObject wire, JSONObject values)
            throws SkillCustomService.Failure {
        if (previous == null || previous.encryptedBearer().isEmpty()) return;
        String credential = customService.credential(previous);
        if (containsContractCredential(wire, credential) || containsContractCredential(values, credential)) {
            throw new SkillManifest.Invalid("接口资料和配置字段中不得包含旧服务凭据");
        }
    }

    private static boolean containsContractCredential(Object value, String credential) {
        if (value instanceof String text) return text.contains(credential);
        if (value instanceof JSONObject object) {
            for (Iterator<String> keys = object.keys(); keys.hasNext();) {
                String key = keys.next();
                if (key.contains(credential) || containsContractCredential(object.opt(key), credential)) return true;
            }
        } else if (value instanceof JSONArray array) {
            for (int i = 0; i < array.length(); i++) {
                if (containsContractCredential(array.opt(i), credential)) return true;
            }
        }
        return false;
    }

    public Detail addVideoFacts(Detail expected, SkillVideoFacts supplied) throws Exception {
        requireIncompleteVideo(expected);
        if (supplied == null || supplied.isEmpty()) throw new IllegalArgumentException("未找到可补充的视频接口资料");
        return locked(state -> {
            EditSource source = videoEditSource(state, expected);
            SkillVideoFacts previous = pendingVideoFacts(source.pending());
            requireMatchingVideoEncoding(false, previous, supplied);
            SkillVideoFacts combined = previous == null ? supplied : !previous.supportsSubmissionEncoding()
                    ? previous.fillMissingFrom(supplied) : previous.merge(supplied);
            DraftPreview prepared = prepareAuthoredVideoDraft(source.definition().toString(), combined);
            Draft updated = parseAuthored(prepared.definition());
            JSONObject entry = stageEdit(state, source, updated, prepared.definition(), videoValues(state, updated));
            saveGroundedVideoFacts(entry, combined);
            if (updated.manifest().http() != null) entry.remove(VIDEO_DRAFT_CONFIG);
            return advancePending(state, entry, updated, null, true);
        });
    }

    public Detail applyVideoReference(Detail expected, SkillVideoFacts supplied) throws Exception {
        if (supplied == null || supplied.isEmpty()) throw new IllegalArgumentException("未找到可引用的视频接口资料");
        return saveVideoFactsForm(expected, supplied, Collections.emptyMap(), true);
    }

    public Detail saveVideoFactsForm(Detail expected, SkillVideoFacts prefilled, Map<String, String> entered) throws Exception {
        return saveVideoFactsForm(expected, prefilled, entered, false);
    }

    private Detail saveVideoFactsForm(Detail expected, SkillVideoFacts prefilled, Map<String, String> entered,
                                      boolean requireEnabled) throws Exception {
        requirePendingVideo(expected);
        Map<String, String> changes = entered == null ? Collections.emptyMap() : new LinkedHashMap<>(entered);
        return locked(state -> {
            if (requireEnabled && !state.getBoolean("enabled")) throw new IllegalStateException("Skills 已停用");
            EditSource source = videoEditSource(state, expected);
            Draft previous = parse(source.definition().toString());
            SkillCustomService.Binding binding = pendingCustomBinding(state, previous, source.pending());
            SkillVideoFacts facts = videoFormSeed(previous, source.pending(), binding);
            requireMatchingVideoEncoding(previous.manifest().http() != null, facts, prefilled);
            SkillVideoFacts prefill = prefilled == null ? facts : facts.fillMissingFrom(prefilled);
            SkillVideoFacts updatedFacts = prefill.withForm(changes);
            if ((!facts.supportsSubmissionEncoding() && updatedFacts.submitEncoding().isEmpty())
                    || (prefilled != null && !prefilled.supportsSubmissionEncoding()
                    && !prefilled.submitEncoding().equals(updatedFacts.submitEncoding()))) {
                throw new IllegalArgumentException(NEEDS_VIDEO_ENCODING);
            }
            boolean sameEndpoint = sameVideoSubmit(facts, updatedFacts);
            if (!sameEndpoint) verifyVideoSubmitOmitsCredential(binding, updatedFacts);
            DraftPreview prepared = prepareAuthoredVideoDraft(source.definition().toString(), updatedFacts);
            Draft updated = parseAuthored(prepared.definition());
            SkillHttpDeclaration oldHttp = previous.manifest().http();
            SkillHttpDeclaration nextHttp = updated.manifest().http();
            boolean retainBinding = binding != null && sameEndpoint && sameVideoContract(previous.manifest(), updated.manifest())
                    && videoBindingMatchesDraft(state, source.pending(), previous);
            JSONObject entry = stageEdit(state, source, updated, prepared.definition(), sameEndpoint
                    ? videoValues(state, updated) : authoredVideoDefaults(updated));
            entry.put(VIDEO_FACTS, updatedFacts.toJson());
            if (!retainBinding) entry.put(CUSTOM_BINDING, JSONObject.NULL);
            if (nextHttp != null) entry.remove(VIDEO_DRAFT_CONFIG);
            else if (oldHttp != null) retainVideoDraftConfig(entry, previous.manifest(), source.values());
            return advancePending(state, entry, updated, null, true);
        });
    }

    private static SkillVideoFacts videoFormSeed(Draft previous, JSONObject entry, SkillCustomService.Binding binding)
            throws JSONException {
        SkillVideoFacts facts = pendingVideoFacts(entry);
        if (facts != null) return facts;
        SkillHttpDeclaration http = previous.manifest().http();
        if (http == null) return SkillVideoFacts.empty();
        String endpoint = http.suggestedEndpoint().isEmpty() && binding != null ? binding.endpoint() : http.suggestedEndpoint();
        return SkillVideoFacts.fromDeclaration(previous.manifest(), endpoint, SkillVideoFacts.Source.STORED);
    }

    private boolean videoBindingMatchesDraft(JSONObject state, JSONObject entry, Draft previous) throws Exception {
        if (readCustomBinding(entry) != null) return true;
        JSONObject installed = state.getJSONObject("skills").optJSONObject(previous.name());
        if (installed == null) return false;
        Executable active = readExecution(revisionDirectory(activeRevision(installed)));
        return active.manifest() != null && sameVideoWire(active.manifest().http(), previous.manifest().http());
    }

    private static boolean sameVideoWire(SkillHttpDeclaration first, SkillHttpDeclaration second) {
        return first != null && second != null && first.request().equals(second.request())
                && first.responseField().equals(second.responseField()) && sameVideoRoutes(first.video(), second.video());
    }

    private static boolean sameVideoContract(SkillManifest first, SkillManifest second) {
        return first != null && second != null && sameVideoWire(first.http(), second.http())
                && first.configFields().equals(second.configFields())
                && first.http().suggestedEndpoint().equals(second.http().suggestedEndpoint());
    }

    private static boolean sameVideoRoutes(SkillHttpDeclaration.Video first, SkillHttpDeclaration.Video second) {
        if (first == null || second == null) return first == second;
        // The local minimum polling delay does not change the approved request or its destination.
        return first.statusPath().equals(second.statusPath()) && first.statusUrlField().equals(second.statusUrlField())
                && first.statusPostPath().equals(second.statusPostPath())
                && first.statusPostIdField().equals(second.statusPostIdField()) && first.cancelPath().equals(second.cancelPath())
                && first.cancelMethod().equals(second.cancelMethod()) && first.downloadPath().equals(second.downloadPath())
                && first.resultUrlField().equals(second.resultUrlField()) && first.idField().equals(second.idField())
                && first.stateField().equals(second.stateField()) && first.states().equals(second.states());
    }

    private static boolean sameVideoSubmit(SkillVideoFacts first, SkillVideoFacts second) {
        return first.submitPath().equals(second.submitPath())
                && first.toJson().optString("submitEndpoint").equals(second.toJson().optString("submitEndpoint"));
    }

    private void verifyVideoSubmitOmitsCredential(SkillCustomService.Binding binding, SkillVideoFacts facts)
            throws SkillCustomService.Failure {
        if (binding == null) return;
        String target = facts.toJson().optString("submitEndpoint");
        if (target.isEmpty() && !facts.submitPath().isEmpty()) {
            target = "https://" + java.net.URI.create(binding.endpoint()).getRawAuthority() + facts.submitPath();
        }
        if (!target.isEmpty()) customService.verifyEndpointOmitsCredential(binding, target);
    }

    private static void retainVideoDraftConfig(JSONObject entry, SkillManifest previous, Map<String, Object> values)
            throws JSONException {
        if (values.isEmpty()) {
            entry.remove(VIDEO_DRAFT_CONFIG);
            return;
        }
        JSONObject types = new JSONObject();
        for (SkillManifest.ConfigField field : previous.configFields()) {
            if (values.containsKey(field.key())) types.put(field.key(), field.type().name());
        }
        entry.put(VIDEO_DRAFT_CONFIG, new JSONObject().put("types", types).put("values", SkillManifest.valuesJson(values)));
    }

    private static Map<String, Object> restoreVideoDraftConfig(SkillManifest current, JSONObject retained) throws Exception {
        JSONObject types = retained.getJSONObject("types");
        JSONObject values = retained.getJSONObject("values");
        JSONObject compatible = new JSONObject();
        for (SkillManifest.ConfigField field : current.configFields()) {
            String key = field.key();
            if (!field.type().name().equals(types.optString(key)) || !values.has(key)) continue;
            try {
                Object value = current.readValues(new JSONObject().put(key, values.get(key))).get(key);
                compatible.put(key, value);
            } catch (IllegalArgumentException changedConstraint) { /* Require the updated field again. */ }
        }
        return current.readValues(compatible);
    }

    private static SkillVideoFacts pendingVideoFacts(JSONObject entry) throws JSONException {
        return entry == null || !entry.has(VIDEO_FACTS) ? null : SkillVideoFacts.fromJson(entry.getJSONObject(VIDEO_FACTS));
    }

    private static SkillVideoFacts revisionVideoFacts(JSONObject revision, SkillManifest manifest,
                                                       SkillCustomService.Binding binding) throws JSONException {
        if (manifest == null || manifest.outputType() != SkillResult.Type.VIDEO || manifest.http() == null) return null;
        SkillVideoFacts facts = pendingVideoFacts(revision);
        if (facts == null) {
            String endpoint = binding == null ? manifest.http().suggestedEndpoint() : binding.endpoint();
            return SkillVideoFacts.fromDeclaration(manifest, endpoint, SkillVideoFacts.Source.STORED);
        }
        if (!facts.isComplete() || !sameVideoWire(manifest.http(),
                SkillHttpDeclaration.parse(facts.declaration(), SkillResult.Type.VIDEO, manifest.configFields()))) {
            throw new IllegalStateException("视频技能的接口资料与当前声明不一致");
        }
        if (binding != null) facts.checkResolvedEndpoint(binding.endpoint());
        return facts;
    }

    private SkillVideoFacts configurationVideoFacts(JSONObject state, Draft draft, JSONObject pending) throws Exception {
        if (draft.outputType() != SkillResult.Type.VIDEO) return null;
        SkillCustomService.Binding binding = pendingCustomBinding(state, draft, pending);
        if (pending != null) return videoFormSeed(draft, pending, binding);
        JSONObject installed = state.getJSONObject("skills").optJSONObject(draft.name());
        return installed == null ? null : revisionVideoFacts(activeRevision(installed), draft.manifest(), binding);
    }

    private static String pendingVideoReason(JSONObject entry) throws JSONException {
        SkillVideoFacts facts = pendingVideoFacts(entry);
        if (facts == null) return NEEDS_VIDEO_PROVIDER;
        List<String> missing = facts.missing();
        if (!facts.supportsSubmissionEncoding()) return NEEDS_VIDEO_ENCODING + "。还缺少 " + String.join("、", missing);
        return missing.isEmpty() ? NEEDS_VIDEO_PROVIDER : NEEDS_VIDEO_PROVIDER + "。还缺少 " + String.join("、", missing);
    }

    private static String missingServiceAddress(Draft draft, JSONObject entry) throws JSONException {
        SkillVideoFacts facts = draft.outputType() == SkillResult.Type.VIDEO ? pendingVideoFacts(entry) : null;
        return facts != null && facts.needsApiBase() ? NEEDS_API_BASE : NEEDS_CUSTOM_SERVICE;
    }

    private static void saveGroundedVideoFacts(JSONObject entry, SkillVideoFacts facts) throws JSONException {
        entry.put(VIDEO_FACTS, facts.toJson()).put(CUSTOM_BINDING, JSONObject.NULL);
    }

    private static void requireMatchingVideoEncoding(boolean hasHttpDeclaration, SkillVideoFacts saved,
                                                     SkillVideoFacts supplied) {
        if (supplied == null || supplied.submitEncoding().isEmpty()) return;
        String current = saved == null ? "" : saved.submitEncoding();
        if (current.isEmpty() && hasHttpDeclaration) current = VIDEO_JSON_ENCODING;
        if (!current.isEmpty() && !current.equals(supplied.submitEncoding())) {
            if (!hasHttpDeclaration && saved != null && saved.canEnrichSubmissionEncoding(supplied)) return;
            throw new IllegalArgumentException("引用资料的提交请求编码与当前视频草稿不同，请在接口表单核对");
        }
    }

    private static void requireSupportedVideoEncoding(SkillVideoFacts facts) {
        if (facts != null && !facts.supportsSubmissionEncoding()) throw new IllegalArgumentException(NEEDS_VIDEO_ENCODING);
    }

    private static void requireIncompleteVideo(Detail expected) {
        requirePendingVideo(expected);
        if (expected.manifest().http() != null) throw new IllegalArgumentException("请打开缺少接口声明的视频草稿");
    }

    private static void requirePendingVideo(Detail expected) {
        if (expected == null || expected.deleteTarget() == null || expected.draftId() == null || !expected.pending()
                || !Objects.equals(expected.name(), expected.deleteTarget().name()) || expected.manifest() == null
                || expected.manifest().outputType() != SkillResult.Type.VIDEO) {
            throw new IllegalArgumentException("请打开待配置的视频草稿");
        }
    }

    private EditSource videoEditSource(JSONObject state, Detail expected) throws Exception {
        EditSource source = editSource(state, expected.deleteTarget());
        if (source.pending() == null || !expected.draftId().equals(draftIdentity(expected.name(), source.pending()))
                || !source.values().equals(expected.manifestValues())
                || !expected.manifest().equals(parse(source.definition().toString()).manifest())) {
            throw new IllegalStateException("视频草稿已变化，请重新打开");
        }
        return source;
    }

    private static Draft attachVideoDeclaration(JSONObject definition, String raw) {
        JSONObject proposed;
        try {
            String json = raw;
            if (raw != null && raw.length() <= DRAFT_LIMIT) {
                Matcher fence = VIDEO_JSON_FENCE.matcher(raw);
                if (fence.matches()) json = fence.group(1);
            }
            proposed = SkillAuthoring.readObject(json, DRAFT_LIMIT);
        } catch (IllegalArgumentException invalid) {
            throw invalidVideoDeclaration();
        }
        boolean fullManifest = proposed.has("schemaVersion");
        boolean wrapped = fullManifest || proposed.has("http") || proposed.has("configFields");
        JSONObject http = proposed;
        JSONArray fields = null;
        if (wrapped) {
            requireVideoFields(proposed, "http", "configFields");
            http = proposed.optJSONObject("http");
            fields = proposed.optJSONArray("configFields");
            if (http == null || fields == null || (!fullManifest && proposed.length() != 2)) {
                throw invalidVideoDeclaration();
            }
        }
        requireVideoFields(http, "version", "label", "request", "response", "video");
        JSONObject video = http.optJSONObject("video");
        if (video == null) throw invalidVideoDeclaration();
        requireVideoFields(video, "statusPath", "idField", "stateField", "states");
        try {
            if (fullManifest && SkillManifest.parse(proposed.toString()).outputType() != SkillResult.Type.VIDEO) {
                throw invalidVideoDeclaration();
            }
            definition.put("http", http);
            if (fields != null) definition.put("configFields", fields);
            return parse(definition.toString());
        } catch (JSONException | IllegalArgumentException invalid) {
            throw invalidVideoDeclaration();
        }
    }

    private static void requireVideoFields(JSONObject object, String... keys) {
        List<String> missing = new ArrayList<>();
        for (String key : keys) if (!object.has(key)) missing.add(key);
        if (!missing.isEmpty()) throw new SkillManifest.Invalid("视频接口声明缺少 " + String.join("、", missing));
    }

    private static SkillManifest.Invalid invalidVideoDeclaration() {
        return new SkillManifest.Invalid("视频接口声明或配置字段无效");
    }

    public Detail configureCustomService(Detail expected, String endpoint, String bearer) throws Exception {
        return configureCustomService(expected, endpoint, bearer, false);
    }

    public Detail configureCustomService(Detail expected, String endpoint, String bearer, boolean clearBearer) throws Exception {
        return configureCustomService(expected, endpoint, bearer, clearBearer, null);
    }

    public Detail configureCustomService(Detail expected, String endpoint, String bearer, boolean clearBearer,
                                         Map<String, String> entered) throws Exception {
        return configureCustomService(expected, endpoint, bearer, clearBearer, entered, false);
    }

    public Detail configureCustomServiceBase(Detail expected, String apiBase, String bearer, boolean clearBearer,
                                             Map<String, String> entered) throws Exception {
        return configureCustomService(expected, apiBase, bearer, clearBearer, entered, true);
    }

    public List<String> discoverVideoModels(Detail expected, String address, String typedBearer) throws Exception {
        if (expected == null || expected.manifest() == null || expected.outputType() != SkillResult.Type.VIDEO
                || !expected.manifest().usesHttp()) throw new IllegalArgumentException("请选择已核对接口的视频技能");
        String entered = SkillCustomService.validateEndpoint(address);
        String typed = typedBearer == null ? "" : typedBearer.trim();
        VideoModelAccess target = locked(state -> {
            EditSource source = contractEditSource(state, expected);
            Draft draft = parse(source.definition().toString());
            SkillVideoFacts facts = configurationVideoFacts(state, draft, source.pending());
            String endpoint = facts != null && facts.needsApiBase()
                    && "/".equals(java.net.URI.create(entered).getRawPath()) ? facts.resolveApiBase(entered) : entered;
            if (facts != null) {
                facts.checkResolvedEndpoint(endpoint);
                if (source.pending() != null && source.pending().optBoolean(VIDEO_CONTRACT_REPLACEMENT)) {
                    verifyReplacementRouteOrigin(facts, endpoint);
                }
            }
            SkillCustomService.Binding binding = pendingCustomBinding(state, draft, source.pending());
            if (binding != null && !endpoint.equals(binding.endpoint())) {
                customService.verifyEndpointOmitsCredential(binding, endpoint);
            }
            String token = !typed.isEmpty() ? typed : binding != null && endpoint.equals(binding.endpoint())
                    ? customService.credential(binding) : "";
            return new VideoModelAccess(endpoint, token);
        });
        List<String> result = videoModels.fetch(target.endpoint, target.bearer);
        return locked(state -> {
            contractEditSource(state, expected);
            return result;
        });
    }

    private static final class VideoModelAccess {
        private final String endpoint;
        private final String bearer;

        private VideoModelAccess(String endpoint, String bearer) {
            this.endpoint = endpoint;
            this.bearer = bearer;
        }
    }

    private Detail configureCustomService(Detail expected, String address, String bearer, boolean clearBearer,
                                          Map<String, String> entered, boolean resolveBase) throws Exception {
        if (expected == null || expected.manifest() == null || expected.deleteTarget() == null
                || !expected.manifest().usesHttp()) {
            throw new IllegalArgumentException("技能服务配置目标无效");
        }
        return locked(state -> {
            EditSource source = editSource(state, expected.deleteTarget());
            String json = source.definition().toString();
            Draft draft = parse(json);
            if (!usesHttp(draft) || !expected.manifest().equals(draft.manifest())) {
                throw new IllegalStateException("技能或服务变化，请重新打开设置");
            }
            SkillVideoFacts facts = configurationVideoFacts(state, draft, source.pending());
            requireSupportedVideoEncoding(facts);
            if (resolveBase && (facts == null || !facts.needsApiBase())) throw new IllegalArgumentException(NEEDS_API_BASE);
            String target = address == null || address.trim().isEmpty() ? "" : resolveBase
                    ? facts.resolveApiBase(address) : SkillCustomService.validateEndpoint(address);
            if (target.isEmpty() && (clearBearer || bearer != null && !bearer.isEmpty())) {
                throw new IllegalArgumentException(resolveBase ? NEEDS_API_BASE : NEEDS_CUSTOM_SERVICE);
            }
            if (facts != null && !target.isEmpty()) {
                facts.checkResolvedEndpoint(target);
                if (source.pending() != null && source.pending().optBoolean(VIDEO_CONTRACT_REPLACEMENT)) {
                    verifyReplacementRouteOrigin(facts, target);
                }
            }
            if (!draft.manifest().servicePrefill().isEmpty()) {
                source.definition().remove(SkillManifest.SERVICE_PREFILL);
                json = source.definition().toString();
                draft = parse(json);
            }
            SkillCustomService.Binding existing = pendingCustomBinding(state, draft, source.pending());
            if (existing != null && !target.isEmpty() && !existing.endpoint().equals(target)) {
                customService.verifyEndpointOmitsCredential(existing, target);
            }
            Map<String, Object> values = entered == null ? source.values() : draft.manifest().enteredValues(entered);
            JSONObject entry = stageEdit(state, source, draft, json, values);
            if (target.isEmpty()) {
                entry.put(CUSTOM_BINDING, JSONObject.NULL);
                return advancePending(state, entry, draft, null);
            }
            SkillCustomService.Binding selected = !clearBearer && existing != null && existing.endpoint().equals(target)
                    && (bearer == null || bearer.isEmpty()) ? existing
                    : customService.configure(entry.getString("skillId"), target, clearBearer ? "" : bearer);
            entry.put(CUSTOM_BINDING, customRecord(selected));
            return advancePending(state, entry, draft, null);
        });
    }

    private static void verifyReplacementRouteOrigin(SkillVideoFacts facts, String target) {
        java.net.URI configured = java.net.URI.create(target);
        JSONObject documented = facts.toJson();
        for (String field : List.of("statusPath", "statusPostPath", "cancelPath", "downloadPath")) {
            String route = documented.optString(field);
            if (!route.startsWith("https://")) continue;
            java.net.URI origin = java.net.URI.create(route.substring(0, route.indexOf('/', 8)));
            if (!origin.getHost().equalsIgnoreCase(configured.getHost())
                    || effectiveHttpsPort(origin) != effectiveHttpsPort(configured)) {
                throw new IllegalArgumentException("视频接口地址必须与资料中明确注明的任务接口同源");
            }
        }
    }

    private static int effectiveHttpsPort(java.net.URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }

    private static boolean liveService(SkillServices.Service binding) {
        return binding != null && !binding.endpoint().isEmpty();
    }

    private static boolean usesHttp(Draft draft) { return draft.manifest() != null && draft.manifest().usesHttp(); }

    private static boolean usesHttp(JSONObject revision) {
        return CUSTOM_KIND.equals(revision.optString("kind")) || resultType(revision) == SkillResult.Type.VIDEO;
    }

    public CustomReview previewCustomService(AiSkills.Invocation call) throws Exception {
        return locked(state -> {
            Executable executable = reviewExecution(state, call);
            if (liveService(executable.service())) {
                SkillHttpsTransport.validateEndpoint(executable.service().endpoint());
                SkillServices.ReviewedPayload payload = services.previewReviewed(executable.service(), call.argument());
                return new CustomReview(call, null, new LegacyReview(executable.service(), payload.body(), payload.proof(),
                        System.nanoTime() + TimeUnit.MINUTES.toNanos(2), new AtomicReference<>(ReviewState.OPEN)));
            }
            return new CustomReview(call, customService.preview(installedCustomBinding(state, call, executable),
                    call.argument(), executable.manifest().http(), executable.values()), null);
        });
    }

    public CustomCall approveCustomService(CustomReview review) throws Exception {
        if (review == null) throw new IllegalArgumentException("技能服务确认无效");
        try {
            return locked(state -> {
                Executable executable = reviewExecution(state, review.invocation);
                if (review.legacy != null) {
                    if (!review.legacy.proof().equals(services.previewReviewed(executable.service(),
                            review.invocation.argument()).proof())) throw new IllegalStateException("服务凭据或授权已变化，请重新确认");
                    requireLegacyReview(review.legacy, executable, ReviewState.OPEN, ReviewState.APPROVED);
                    return new CustomCall(review, null);
                }
                SkillCustomService.Binding active = installedCustomBinding(state, review.invocation, executable);
                SkillCustomService.Preview preview = review.preview;
                if (!preview.matches(active)) {
                    throw new IllegalStateException("技能服务配置已变化，请重新确认");
                }
                return new CustomCall(review, customService.approve(preview));
            });
        } catch (Exception invalid) {
            cancelCustomService(review);
            throw invalid;
        }
    }

    public void cancelCustomService(CustomReview review) {
        if (review == null) return;
        if (review.legacy != null) review.legacy.state().set(ReviewState.CONSUMED);
        else customService.cancel(review.preview);
    }

    public SkillResult executeCustomService(AiSkills.Invocation call, CustomCall approved, long deadline, VideoTurn video)
            throws Exception {
        if (approved == null) throw new IllegalStateException("请先确认本次技能服务调用");
        CustomReview review = approved.review;
        try {
            if (!review.invocation.equals(call)) throw new IllegalStateException("技能配置已变化，请重新确认");
            if (review.legacy != null) {
                locked(state -> {
                    requireLegacyReview(review.legacy, reviewExecution(state, call), ReviewState.APPROVED, ReviewState.CONSUMED);
                    return null;
                });
                return services.executeReviewed(review.legacy.service(), call.argument(), review.legacy.proof(), () -> {
                    try {
                        locked(state -> {
                            requireLegacyReview(review.legacy, reviewExecution(state, call), ReviewState.CONSUMED, ReviewState.CONSUMED);
                            return null;
                        });
                    } catch (Exception stale) {
                        throw new IOException("服务授权或配置已变化");
                    }
                });
            }
            SkillCustomService.Binding binding = locked(state -> installedCustomBinding(state, call, reviewExecution(state, call)));
            SkillCustomService.Admission admission = () -> locked(state -> {
                if (!binding.equals(installedCustomBinding(state, call, reviewExecution(state, call)))) {
                    throw new IllegalStateException("技能服务配置已变化，请重新确认");
                }
                return null;
            });
            if (call.command().outputType() != SkillResult.Type.VIDEO) {
                return customService.executeResult(approved.approval, binding, deadline, admission);
            }
            if (video == null) throw new IllegalStateException("视频任务需要已开启的本地会话历史");
            SkillVideoJobs.Binding route = new SkillVideoJobs.Binding(binding.skillId(), call.revisionId(),
                    binding.revision(), continuationPolicy(review.preview.declaration()), binding.endpoint());
            SkillVideoJobs.Pinned pinned = new SkillVideoJobs.Pinned(route, binding.encryptedBearer());
            return customService.executeVideo(approved.approval, binding, deadline, admission,
                    (bearer, body, limit, check) -> {
                        SkillVideoJobs.Access access = new SkillVideoJobs.Access(route, bearer, check::check);
                        SkillVideoJobs.Job job = video.jobs().submit(video.scope(), video.generation(),
                                video.requestId(), access, body, limit, pinned);
                        return new SkillResult.Video(job.localId());
                    });
        } finally {
            cancelCustomService(review);
        }
    }

    private void requireLegacyReview(LegacyReview review, Executable executable, ReviewState from, ReviewState to)
            throws Exception {
        if (review.expiresAt() - System.nanoTime() <= 0 || !liveService(executable.service())
                || !SkillServices.sameBinding(review.service(), executable.service())
                || !authorized(review.service()) || !review.state().compareAndSet(from, to)) {
            throw new IllegalStateException("服务授权或配置已变化，请重新确认");
        }
    }

    private static SkillHttpDeclaration continuationPolicy(SkillHttpDeclaration declaration) throws JSONException {
        // The approved submission already has a compiled body. Keep only the task and follow-up contract.
        JSONObject policy = declaration.toJson();
        policy.remove("suggestedEndpoint");
        policy.put("request", new JSONObject().put("input", "argument"));
        return SkillHttpDeclaration.parse(policy, SkillResult.Type.VIDEO, Collections.emptyList());
    }

    public SkillVideoJobs.Access openVideoAccess(SkillVideoJobs jobs, SkillVideoJobs.Scope scope, SkillVideoJobs.Job job)
            throws Exception {
        SkillVideoJobs.Pinned pinned = jobs.pinned(scope, job);
        SkillVideoJobs.Binding policy = pinned.binding();
        checkVideoContinuation(policy.skillId());
        SkillCustomService.Binding credential = new SkillCustomService.Binding(policy.skillId(), policy.configRevision(),
                policy.endpoint(), pinned.encryptedBearer());
        return new SkillVideoJobs.Access(policy, customService.credential(credential), () -> {
            try {
                checkVideoContinuation(policy.skillId());
            } catch (Exception unavailable) {
                throw new IOException("视频任务的技能已停用或删除");
            }
        });
    }

    private void checkVideoContinuation(String skillId) throws Exception {
        locked(state -> {
            if (state.getBoolean("enabled")) {
                JSONObject skills = state.getJSONObject("skills");
                for (Iterator<String> names = skills.keys(); names.hasNext();) {
                    String name = names.next();
                    if (AiSkills.CREATION_COMMAND_NAME.equals(name)) continue;
                    JSONObject record = skills.getJSONObject(name);
                    if (skillId.equals(record.optString("skillId")) && record.getBoolean("enabled")
                            && resultType(activeRevision(record)) == SkillResult.Type.VIDEO) return null;
                }
            }
            throw new IllegalStateException("视频任务的技能已停用或删除");
        });
    }

    private Executable reviewExecution(JSONObject state, AiSkills.Invocation call) throws Exception {
        if (call == null || !call.command().requiresReview() || !state.getBoolean("enabled")) {
            throw new IllegalStateException("技能服务不可用");
        }
        requireRegistrableName(call.command().name());
        JSONObject installed = skill(state, call.command().name());
        JSONObject revision = activeRevision(installed);
        if (!installed.getBoolean("enabled") || !configurationRequired(installed, revision).isEmpty()
                || revision.getInt("version") != call.version() || !revision.getString("directory").equals(call.revisionId())) {
            throw new IllegalStateException("技能配置已变化，请重新确认");
        }
        Executable executable = readExecution(revisionDirectory(revision));
        if (executable.outputType() != call.command().outputType()) {
            throw new IllegalStateException("技能服务不可用");
        }
        checkArgument(call.argument(), executable.maxInput());
        return executable;
    }

    private SkillCustomService.Binding installedCustomBinding(JSONObject state, AiSkills.Invocation call, Executable executable)
            throws Exception {
        if (executable.manifest() == null || !executable.manifest().usesHttp()
                || !executable.manifest().missing(executable.values()).isEmpty()) {
            throw new IllegalStateException("技能服务不可用");
        }
        SkillCustomService.Binding binding = readCustomBinding(skill(state, call.command().name()));
        if (binding == null) throw new IllegalStateException(NEEDS_CUSTOM_SERVICE);
        return binding;
    }

    private static JSONObject customRecord(SkillCustomService.Binding binding) throws JSONException {
        return new JSONObject().put("endpoint", binding.endpoint()).put("revision", binding.revision())
                .put("sealed", binding.encryptedBearer());
    }

    private static SkillCustomService.Binding readCustomBinding(JSONObject owner) {
        if (owner == null || !owner.has(CUSTOM_BINDING)) return null;
        try {
            JSONObject record = owner.getJSONObject(CUSTOM_BINDING);
            if (record.has("preset")) return null;
            keys(record, "endpoint", "revision", "sealed");
            return new SkillCustomService.Binding(owner.getString("skillId"), record.getString("revision"),
                    record.getString("endpoint"), record.getString("sealed"));
        } catch (JSONException | IllegalArgumentException invalid) {
            return null;
        }
    }

    private SkillCustomService.Binding pendingCustomBinding(JSONObject state, Draft draft, JSONObject entry)
            throws Exception {
        if (!usesHttp(draft)) return null;
        if (entry != null && entry.has(CUSTOM_BINDING) && entry.isNull(CUSTOM_BINDING)) return null;
        SkillCustomService.Binding configured = readCustomBinding(entry);
        JSONObject installed = state.getJSONObject("skills").optJSONObject(draft.name());
        if (draft.outputType() == SkillResult.Type.VIDEO) {
            if (configured != null && !sameVideoContract(parse(entry.getString("definition")).manifest(), draft.manifest())) return null;
            if (configured == null && installed != null) {
                SkillManifest active = readExecution(revisionDirectory(activeRevision(installed))).manifest();
                if (!sameVideoContract(active, draft.manifest())) return null;
            }
        }
        if (configured == null) configured = readCustomBinding(installed);
        if (configured != null && !configured.skillId().equals(manifestSkillId(state, draft.name(), entry))) {
            throw new IllegalStateException("技能服务与当前技能不匹配");
        }
        String prefill = draft.manifest().servicePrefill().endpoint();
        if (configured != null && !prefill.isEmpty() && !prefill.equals(configured.endpoint())) return null;
        return configured;
    }

    private static CustomConfiguration customConfiguration(SkillCustomService.Binding binding) {
        return binding == null ? null : new CustomConfiguration(binding.endpoint(), !binding.encryptedBearer().isEmpty());
    }

    private JSONObject stageEdit(JSONObject state, EditSource source, Draft draft, String json,
                                 Map<String, Object> values) throws Exception {
        JSONObject staged = stageDraft(state, source.pending(), source.retainedService(), draft, json, values);
        if (source.pending() == null && draft.outputType() == SkillResult.Type.VIDEO) {
            Draft previous = parse(source.definition().toString());
            if (sameVideoContract(previous.manifest(), draft.manifest())) {
                SkillVideoFacts facts = configurationVideoFacts(state, previous, null);
                if (facts != null) staged.put(VIDEO_FACTS, facts.toJson());
            }
        }
        return staged;
    }

    private JSONObject stageDraft(JSONObject state, JSONObject entry, SkillServices.Service retainedService,
                                  Draft draft, String json, Map<String, Object> values) throws Exception {
        requireRegistrableName(draft.name());
        if (entry == null) {
            entry = new JSONObject();
            if (retainedService != null) entry.put(RETAINED_SERVICE, serviceRecord(retainedService));
            state.getJSONObject("drafts").put(draft.name(), entry);
        }
        entry.put("definition", persistedDraftDefinition(draft, json)).put("draftId", UUID.randomUUID().toString())
                .put("outputType", draft.outputType().wireName());
        storeManifestState(state, entry, draft, values);
        return entry;
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
            boolean editable = draft.manifest() == null ? TEXT_MODEL.equals(draft.kind())
                    : draft.manifest().capability().usesModel();
            return new EditSource(new EditTarget(expected, draft.description(), draft.documentation(),
                    editable ? draft.instruction() : "", editable, manifestFields(draft.manifest()),
                    draft.manifest() == null ? null : draft.manifest().displayName()),
                    object(json, DRAFT_LIMIT), pending, null, manifestValues(draft, pending));
        }
        JSONObject revision = activeRevision(installed);
        File slot = revisionDirectory(revision);
        Executable executable = readExecution(slot);
        String description = revision.getString("description");
        String documentation = readDocumentation(slot, name, revision.getInt("version"));
        JSONObject definition = executable.manifest() == null
                ? editDefinition(name, description, documentation, executable) : executable.manifest().toJson();
        boolean editable = executable.manifest() == null ? TEXT_MODEL.equals(executable.kind())
                : executable.manifest().capability().usesModel();
        return new EditSource(new EditTarget(expected, description, documentation,
                editable ? executable.instruction() : "", editable, manifestFields(executable.manifest()),
                executable.manifest() == null ? null : executable.manifest().displayName()),
                definition, null, executable.service(), executable.values());
    }

    private static String manifestFields(SkillManifest manifest) throws Exception {
        return manifest == null ? null : manifest.toJson().getJSONArray("configFields").toString();
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
        SkillAuthoring.rejectCredential(input);
        return new JSONObject().put("requirement", input).toString();
    }

    public static DraftPreview validateAuthoredDraft(String raw) {
        Draft draft = parseAuthored(raw);
        requireRegistrableName(draft.name());
        return draft.preview(raw);
    }

    public static DraftPreview validateImportedDraft(String raw) {
        JSONObject data = SkillAuthoring.readObject(raw, DRAFT_LIMIT);
        SkillAuthoring.rejectEditorCredential(raw);
        SkillManifest manifest = SkillManifest.parse(raw);
        requireRegistrableName(manifest.name());
        if (manifest.outputType() != SkillResult.Type.IMAGE && manifest.outputType() != SkillResult.Type.VIDEO) {
            throw new IllegalArgumentException("此入口支持生图和视频技能");
        }
        if (manifest.capability() == SkillCapabilityRegistry.Capability.CUSTOM_SERVICE
                || data.has(SkillManifest.SERVICE_PREFILL) || data.has(SkillManifest.DRAFT_VALUES)
                || manifest.http() != null && !manifest.http().suggestedEndpoint().isEmpty()) {
            throw new IllegalArgumentException("技能文件不能包含服务地址、绑定或本机设置");
        }
        return manifestDraft(manifest).preview(raw);
    }

    /** Local import never runs a trial or enables a command. */
    public Pending saveImportedDraft(DraftPreview preview) throws Exception {
        if (preview == null || !validateImportedDraft(preview.definition()).equals(preview)) throw invalidDraft();
        Draft draft = parseAuthored(preview.definition());
        return locked(state -> {
            if (state.getJSONObject("skills").has(draft.name()) || state.getJSONObject("drafts").has(draft.name())) {
                throw new IllegalStateException("已有同名技能或草稿");
            }
            String reason = draft.outputType() == SkillResult.Type.IMAGE ? NEEDS_IMAGE_SERVICE
                    : usesHttp(draft) ? NEEDS_CUSTOM_SERVICE : NEEDS_VIDEO_PROVIDER;
            return savePending(state, draft, preview.definition(), reason, draft.manifest().readValues(null)).pending();
        });
    }

    public static String sanitizeModelAuthoringDefinition(String raw) {
        JSONObject definition = SkillAuthoring.readObject(raw, DRAFT_LIMIT);
        boolean changed = definition.has(SkillManifest.DRAFT_VALUES) || definition.has(SkillManifest.SERVICE_PREFILL);
        definition.remove(SkillManifest.DRAFT_VALUES);
        definition.remove(SkillManifest.SERVICE_PREFILL);
        JSONObject http = definition.optJSONObject("http");
        if (http != null && http.has("suggestedEndpoint")) {
            http.remove("suggestedEndpoint");
            changed = true;
        }
        return changed ? definition.toString() : raw;
    }

    public static DraftPreview prepareModelAuthoredDraft(String raw) {
        return validateAuthoredDraft(sanitizeModelAuthoringDefinition(raw));
    }

    public static DraftPreview applyConversationFields(DraftPreview preview, Map<String, Object> verifiedFields) {
        if (preview == null || verifiedFields == null || verifiedFields.size() > 12) throw invalidDraft();
        Draft previous = parseAuthored(preview.definition());
        if (!previous.preview(preview.definition()).equals(preview)) throw invalidDraft();
        if (verifiedFields.isEmpty()) return preview;
        try {
            JSONObject definition = SkillAuthoring.readObject(preview.definition(), DRAFT_LIMIT);
            JSONObject values = definition.optJSONObject(SkillManifest.DRAFT_VALUES);
            JSONObject prefill = definition.optJSONObject(SkillManifest.SERVICE_PREFILL);
            if (values == null) values = new JSONObject();
            if (prefill == null) prefill = new JSONObject();
            for (Map.Entry<String, Object> entry : verifiedFields.entrySet()) {
                String field = entry.getKey();
                Object value = entry.getValue();
                if (field == null || value == null) throw invalidDraft();
                if (field.startsWith("config.") && previous.manifest() != null) {
                    if (!(value instanceof String || value instanceof Boolean || value instanceof Number)) throw invalidDraft();
                    values.put(field.substring("config.".length()), value);
                } else if (("service.endpoint".equals(field) || "service.model".equals(field)) && previous.manifest() != null) {
                    if (!(value instanceof String)) throw invalidDraft();
                    prefill.put(field.substring("service.".length()), value);
                } else if ("description".equals(field) || "documentation".equals(field)
                        || "instructions".equals(field) && previous.manifest() != null
                        || "displayName".equals(field) && previous.manifest() != null) {
                    if (!(value instanceof String)) throw invalidDraft();
                    definition.put(field, value);
                } else if ("instructions".equals(field) && TEXT_MODEL.equals(previous.kind()) && value instanceof String) {
                    definition.getJSONObject("execution").put("instruction", value);
                } else throw invalidDraft();
            }
            if (values.length() != 0) definition.put(SkillManifest.DRAFT_VALUES, values);
            if (prefill.length() != 0) definition.put(SkillManifest.SERVICE_PREFILL, prefill);
            return validateAuthoredDraft(definition.toString());
        } catch (JSONException malformed) { throw invalidDraft(); }
    }

    public SaveResult saveAuthoredDraft(DraftPreview preview) throws Exception {
        if (preview == null) throw invalidDraft();
        Draft draft = parseAuthored(preview.definition());
        if (!draft.preview(preview.definition()).equals(preview)) throw invalidDraft();
        return saveGenerated(draft, preview.definition(), null, SaveMode.CONVERSATION);
    }

    public SaveResult saveConversationDraft(DraftPreview preview, SkillVideoFacts facts, String expectedPendingId)
            throws Exception {
        return saveConversationDraft(preview, facts, expectedPendingId, null);
    }

    public SaveResult saveConversationDraft(DraftPreview preview, SkillVideoFacts facts, String expectedPendingId,
                                            DeleteTarget exactEditorSource) throws Exception {
        return saveConversationDraft(preview, facts, expectedPendingId, exactEditorSource, false);
    }

    public SaveResult saveConversationDraft(DraftPreview preview, SkillVideoFacts facts, String expectedPendingId,
                                            DeleteTarget exactEditorSource, boolean replaceVideoFacts) throws Exception {
        if (preview == null) throw invalidDraft();
        Draft draft = parseAuthored(preview.definition());
        if (!draft.preview(preview.definition()).equals(preview)) throw invalidDraft();
        if (replaceVideoFacts && (draft.outputType() != SkillResult.Type.VIDEO || facts == null || !facts.isComplete())) {
            throw new IllegalArgumentException("更换视频接口需要完整且已核对的资料");
        }
        if (expectedPendingId == null || expectedPendingId.isEmpty()) {
            if (exactEditorSource != null) return stageInstalledConversationDraft(preview, draft, facts, exactEditorSource, replaceVideoFacts);
            if (replaceVideoFacts) throw new IllegalStateException("请选择要更换接口的现有视频技能");
            return draft.outputType() == SkillResult.Type.VIDEO ? saveAuthoredVideoDraft(preview, facts, true)
                    : saveGenerated(draft, preview.definition(), null, SaveMode.CONVERSATION, null, true);
        }
        return locked(state -> {
            if (!state.getBoolean("enabled")) throw new IllegalStateException("Skills 已停用");
            String name = draft.name();
            JSONObject pending = state.getJSONObject("drafts").optJSONObject(name);
            if (pending == null || !expectedPendingId.equals(draftIdentity(name, pending))) {
                throw new IllegalStateException("待配置草稿已变化，请重新打开");
            }
            JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
            EditSource source = editSource(state, deleteTarget(name, installed, pending));
            Draft previous = parse(source.definition().toString());
            if (previous.outputType() != draft.outputType()) throw invalidDraft();
            if (!replaceVideoFacts) verifyConversationPrefill(state, draft);
            Detail staged;
            if (replaceVideoFacts) {
                staged = stageVideoReplacement(state, source, previous, preview.definition(), facts);
            } else if (draft.outputType() == SkillResult.Type.VIDEO) {
                staged = stageConversationVideo(state, source, previous, preview, facts);
            } else {
                SkillHttpDeclaration oldHttp = previous.manifest() == null ? null : previous.manifest().http();
                SkillHttpDeclaration nextHttp = draft.manifest() == null ? null : draft.manifest().http();
                JSONObject entry = stageEdit(state, source, draft, preview.definition(), proposedManifestValues(state, draft));
                if (usesHttp(previous) != usesHttp(draft) || !Objects.equals(oldHttp, nextHttp)) {
                    entry.put(CUSTOM_BINDING, JSONObject.NULL);
                }
                staged = advancePending(state, entry, draft, null, true);
            }
            return new SaveResult(null, new Pending(staged.name(), staged.outputType(), staged.reason(), staged.draftId()));
        });
    }

    private SaveResult stageInstalledConversationDraft(DraftPreview preview, Draft draft, SkillVideoFacts facts,
                                                        DeleteTarget expected, boolean replaceVideoFacts) throws Exception {
        if (!draft.name().equals(expected.name()) || expected.hasDraft()) throw invalidDraft();
        return locked(state -> {
            if (!state.getBoolean("enabled")) throw new IllegalStateException("Skills 已停用");
            EditSource source = editSource(state, expected);
            Draft previous = parse(source.definition().toString());
            if (previous.outputType() != draft.outputType()) throw invalidDraft();
            if (!replaceVideoFacts) verifyConversationPrefill(state, draft);
            Detail staged;
            if (replaceVideoFacts) {
                staged = stageVideoReplacement(state, source, previous, preview.definition(), facts);
            } else if (draft.outputType() == SkillResult.Type.VIDEO) {
                staged = stageConversationVideo(state, source, previous, preview, facts);
            } else {
                Map<String, Object> values = draft.manifest() == null ? Collections.emptyMap()
                        : draft.manifest().applyDraftValues(draft.manifest().retainValues(previous.manifest(), source.values()));
                JSONObject entry = stageEdit(state, source, draft, preview.definition(), values);
                SkillHttpDeclaration oldHttp = previous.manifest() == null ? null : previous.manifest().http();
                SkillHttpDeclaration nextHttp = draft.manifest() == null ? null : draft.manifest().http();
                if (usesHttp(previous) != usesHttp(draft) || !Objects.equals(oldHttp, nextHttp)) {
                    entry.put(CUSTOM_BINDING, JSONObject.NULL);
                }
                staged = advancePending(state, entry, draft, null, true);
            }
            return new SaveResult(null, new Pending(staged.name(), staged.outputType(), staged.reason(), staged.draftId()));
        });
    }

    private Detail stageConversationVideo(JSONObject state, EditSource source, Draft previous,
                                          DraftPreview preview, SkillVideoFacts facts) throws Exception {
        SkillCustomService.Binding binding = pendingCustomBinding(state, previous, source.pending());
        SkillVideoFacts original = configurationVideoFacts(state, previous, source.pending());
        if (original == null) original = SkillVideoFacts.empty();
        requireMatchingVideoEncoding(previous.manifest().http() != null, original, facts);
        SkillVideoFacts combined = original;
        if (facts != null) {
            Map<String, String> manual = new LinkedHashMap<>();
            for (SkillVideoFacts.FormField field : facts.formFields()) {
                if (field.source() == SkillVideoFacts.Source.MANUAL) manual.put(field.id(), field.value());
            }
            combined = original.withForm(manual).fillMissingFrom(facts);
            if (facts.minimumPollSeconds() != 0) combined = combined.withMinimumPollSeconds(facts.minimumPollSeconds());
        }
        if (!original.supportsSubmissionEncoding() && combined.supportsSubmissionEncoding()
                && !original.canEnrichSubmissionEncoding(facts)) {
            throw new IllegalArgumentException(NEEDS_VIDEO_ENCODING);
        }
        boolean sameEndpoint = sameVideoSubmit(original, combined);
        if (!sameEndpoint) verifyVideoSubmitOmitsCredential(binding, combined);
        DraftPreview prepared = prepareAuthoredVideoDraft(preview.definition(), combined);
        Draft updated = parseAuthored(prepared.definition());
        SkillHttpDeclaration oldHttp = previous.manifest().http();
        SkillHttpDeclaration nextHttp = updated.manifest().http();
        boolean retainBinding = binding != null && sameEndpoint && sameVideoContract(previous.manifest(), updated.manifest())
                && (source.pending() != null || original.verifiedDocumentContract())
                && videoBindingMatchesDraft(state, source.pending(), previous);
        JSONObject entry = stageEdit(state, source, updated, prepared.definition(), sameEndpoint
                ? videoValues(state, updated) : authoredVideoDefaults(updated));
        entry.put(VIDEO_FACTS, combined.toJson());
        if (!retainBinding) entry.put(CUSTOM_BINDING, JSONObject.NULL);
        else if (source.pending() == null) entry.put(CUSTOM_BINDING, customRecord(binding));
        if (nextHttp != null) entry.remove(VIDEO_DRAFT_CONFIG);
        else if (oldHttp != null) retainVideoDraftConfig(entry, previous.manifest(), source.values());
        return advancePending(state, entry, updated, null, true);
    }

    private void verifyConversationPrefill(JSONObject state, Draft draft) throws Exception {
        if (!usesHttp(draft)) return;
        SkillManifest manifest = draft.manifest();
        String endpoint = manifest.servicePrefill().endpoint();
        Map<String, Object> values = manifest.draftValues();
        if (endpoint.isEmpty() && values.isEmpty()) return;
        SkillCustomService.Binding active = readCustomBinding(state.getJSONObject("skills").optJSONObject(draft.name()));
        SkillCustomService.Binding pending = readCustomBinding(state.getJSONObject("drafts").optJSONObject(draft.name()));
        if (!endpoint.isEmpty()) {
            if (active != null) customService.verifyEndpointOmitsCredential(active, endpoint);
            if (pending != null && !pending.equals(active)) customService.verifyEndpointOmitsCredential(pending, endpoint);
        }
        verifyContractOmitsPriorCredentials(active, pending, new JSONObject(), SkillManifest.valuesJson(values));
    }

    public static DraftPreview prepareAuthoredVideoDraft(String raw, SkillVideoFacts supplied) {
        JSONObject definition = SkillAuthoring.readObject(raw, DRAFT_LIMIT);
        if (!Integer.valueOf(MANIFEST_SCHEMA).equals(definition.opt("schemaVersion"))
                || !SkillCapabilityRegistry.Capability.VIDEO_TASK.id().equals(definition.opt("capabilityId"))
                || !SkillResult.Type.VIDEO.wireName().equals(definition.opt("outputType"))) throw invalidDraft();
        try {
            boolean changed = definition.has("http");
            definition.remove("http");
            JSONArray ungrounded = definition.optJSONArray("configFields");
            if (definition.has("configFields") && (ungrounded == null || ungrounded.length() != 0)) {
                definition.put("configFields", new JSONArray());
                changed = true;
            }
            if (supplied != null && supplied.supportsSubmissionEncoding() && supplied.missing().isEmpty()) {
                definition.put("http", supplied.declaration()).put("configFields", supplied.configFields());
                changed = true;
            }
            return validateAuthoredDraft(changed ? definition.toString() : raw);
        } catch (JSONException malformed) {
            throw invalidDraft();
        }
    }

    public SaveResult saveAuthoredVideoDraft(DraftPreview preview, SkillVideoFacts supplied) throws Exception {
        return saveAuthoredVideoDraft(preview, supplied, false);
    }

    public SaveResult saveReferencedVideoDraft(DraftPreview preview, SkillVideoFacts supplied) throws Exception {
        if (supplied == null || supplied.isEmpty()) throw new IllegalArgumentException("未找到可引用的视频接口资料");
        return saveAuthoredVideoDraft(preview, supplied, true);
    }

    private SaveResult saveAuthoredVideoDraft(DraftPreview preview, SkillVideoFacts supplied, boolean requireNewPending)
            throws Exception {
        if (preview == null || preview.outputType() != SkillResult.Type.VIDEO) throw invalidDraft();
        SkillVideoFacts facts = supplied == null ? SkillVideoFacts.empty() : supplied;
        DraftPreview prepared = prepareAuthoredVideoDraft(preview.definition(), facts);
        if (!Objects.equals(preview.name(), prepared.name()) || !Objects.equals(preview.description(), prepared.description())
                || !Objects.equals(preview.instruction(), prepared.instruction())
                || !Objects.equals(preview.capabilityId(), prepared.capabilityId())) throw invalidDraft();
        Draft draft = parseAuthored(prepared.definition());
        return locked(state -> {
            if (!state.getBoolean("enabled")) throw new IllegalStateException("Skills 已停用");
            JSONObject drafts = state.getJSONObject("drafts");
            JSONObject old = drafts.optJSONObject(draft.name());
            if (requireNewPending && old != null) throw new IllegalStateException("已有同名草稿，请重新选择要补充的草稿");
            verifyConversationPrefill(state, draft);
            if (old != null && resultType(old) == SkillResult.Type.VIDEO) {
                SkillVideoFacts previous = pendingVideoFacts(old);
                requireMatchingVideoEncoding(usesHttp(parse(old.getString("definition"))), previous, facts);
                if (usesHttp(draft)) requireSupportedVideoEncoding(previous);
            }
            Map<String, Object> values = explicitVideoValues(state, draft, facts);
            JSONObject entry = new JSONObject().put("skillId", manifestSkillId(state, draft.name(), old));
            drafts.put(draft.name(), entry);
            stageDraft(state, entry, null, draft, prepared.definition(), values);
            saveGroundedVideoFacts(entry, facts);
            Detail staged = advancePending(state, entry, draft, null, true);
            return new SaveResult(null, new Pending(staged.name(), staged.outputType(), staged.reason(), staged.draftId()));
        });
    }

    public SkillVideoFacts videoFactsForSkill(String explicitExactName) throws Exception {
        if (explicitExactName == null || !NAME.matcher(explicitExactName).matches()) {
            throw new IllegalArgumentException("请使用已配置视频技能的准确命令名");
        }
        return locked(state -> {
            SkillVideoFacts facts = reusableVideoFacts(state, explicitExactName);
            if (facts != null) return facts;
            throw new IllegalStateException("找不到已启用且已配置的同名视频技能");
        });
    }

    public List<String> configuredVideoSourceNames() {
        return read(state -> {
            if (!state.getBoolean("enabled")) return Collections.emptyList();
            List<String> sources = new ArrayList<>();
            for (Iterator<String> names = state.getJSONObject("skills").keys(); names.hasNext();) {
                String name = names.next();
                try {
                    if (reusableVideoFacts(state, name) != null) sources.add(name);
                } catch (Exception unavailable) { /* An unreadable source cannot be reused. */ }
            }
            Collections.sort(sources);
            return Collections.unmodifiableList(sources);
        });
    }

    private SkillVideoFacts reusableVideoFacts(JSONObject state, String name) throws Exception {
        if (!state.getBoolean("enabled") || !NAME.matcher(name).matches() || AiSkills.isBuiltIn(name)
                || AiSkills.CREATION_COMMAND_NAME.equals(name) || name.startsWith("mcp-")) return null;
        JSONObject owner = state.getJSONObject("skills").optJSONObject(name);
        if (owner == null || !owner.getBoolean("enabled")) return null;
        JSONObject revision = activeRevision(owner);
        SkillCustomService.Binding binding = readCustomBinding(owner);
        if (resultType(revision) != SkillResult.Type.VIDEO || binding == null || revision.optInt("httpVersion") != 1) return null;
        Executable executable = readExecution(revisionDirectory(revision));
        SkillManifest manifest = executable.manifest();
        return manifest != null && manifest.http() != null && manifest.http().video() != null
                && manifest.missing(executable.values()).isEmpty()
                ? SkillVideoFacts.fromDeclaration(manifest, binding.endpoint()) : null;
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

    public List<Detail> pendingVideoDrafts() {
        return read(state -> {
            JSONObject drafts = state.getJSONObject("drafts");
            List<Detail> videos = new ArrayList<>();
            for (Iterator<String> names = drafts.keys(); names.hasNext();) {
                String name = names.next();
                if (resultType(drafts.getJSONObject(name)) == SkillResult.Type.VIDEO) videos.add(detail(state, name));
            }
            return Collections.unmodifiableList(videos);
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

    public ConversationDraft openConversationDraft(String name) throws Exception {
        if (name == null || !NAME.matcher(name).matches()) throw invalidDraft();
        requireRegistrableName(name);
        return locked(state -> {
            JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
            JSONObject pending = state.getJSONObject("drafts").optJSONObject(name);
            if (installed == null && pending == null) throw new IllegalStateException("技能或待配置草稿不存在");
            DeleteTarget identity = deleteTarget(name, installed, pending);
            EditSource source = editSource(state, identity);
            Draft saved = parse(source.definition().toString());
            JSONObject definition = source.definition();
            if (saved.manifest() != null && !source.values().isEmpty()) {
                definition.put(SkillManifest.DRAFT_VALUES, SkillManifest.valuesJson(source.values()));
            } else if (saved.schemaVersion() == 2 && saved.outputType() == SkillResult.Type.IMAGE) {
                definition = conversationImageManifest(saved);
            } else if (saved.schemaVersion() < AUTHORED_SCHEMA) {
                throw new IllegalStateException("此旧版技能需要在聊天中重新编写");
            }
            DraftPreview preview = validateAuthoredDraft(definition.toString());
            SkillVideoFacts facts = configurationVideoFacts(state, saved, pending);
            return new ConversationDraft(preview, facts, pending == null ? "" : draftIdentity(name, pending), identity);
        });
    }

    private static JSONObject conversationImageManifest(Draft image) throws JSONException {
        return new JSONObject().put("schemaVersion", MANIFEST_SCHEMA).put("name", image.name())
                .put("description", image.description()).put("documentation", image.documentation())
                .put("instructions", "根据本次输入的描述生成图片").put("capabilityId", "image_generation")
                .put("configFields", new JSONArray()).put("inputs", new JSONArray().put(new JSONObject()
                        .put("key", "argument").put("type", "text").put("required", true).put("maxLength", image.maxInput())))
                .put("outputType", "image").put("trial", new JSONObject().put("input", image.trialInput())
                        .put("expectedType", "image"));
    }

    public Detail saveConfiguration(String name, String draftId, SkillServices.Adapter adapter, String endpoint,
                                    String modelName, int costUnits, String bearerToken) throws Exception {
        return saveConfiguration(name, draftId, adapter, endpoint, null, modelName, costUnits, bearerToken);
    }

    public Detail saveConfiguration(String name, String draftId, SkillServices.Adapter adapter, String endpoint,
                                    String modelsEndpoint, String modelName, int costUnits, String bearerToken)
            throws Exception {
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            if (draft.outputType() != SkillResult.Type.IMAGE || usesHttp(draft)) {
                throw new IllegalStateException("此草稿没有图片配置项");
            }
            if (draft.manifest() != null && !draft.manifest().servicePrefill().isEmpty()) {
                JSONObject definition = draft.manifest().toJson();
                definition.remove(SkillManifest.SERVICE_PREFILL);
                entry.put("definition", definition.toString()).put("draftId", UUID.randomUUID().toString());
                draft = parse(definition.toString());
            }
            String token = SkillServices.normalizeScopedBearer(bearerToken);
            if (token.isEmpty()) {
                SkillServices.Service binding = discoveryMetadata(pendingService(state, draft, entry));
                if (sameGenerationConfiguration(binding, adapter, endpoint, modelName, costUnits)
                        && authorized(binding)) {
                    boolean catalogueChanged = modelsEndpoint != null
                            && !binding.modelsEndpoint().equals(modelsEndpoint.trim());
                    if (!catalogueChanged || binding.adapter() == SkillServices.Adapter.OPENAI_IMAGES
                            && name.equals(binding.skillName())) {
                        if (catalogueChanged) binding = services.updateImageModelsEndpoint(binding, modelsEndpoint);
                        boolean pinBinding = !entry.has("serviceId") && !entry.has(RETAINED_SERVICE);
                        if (pinBinding) entry.put(RETAINED_SERVICE, serviceRecord(binding));
                        if (catalogueChanged || pinBinding) entry.put("draftId", UUID.randomUUID().toString());
                        return advancePending(state, entry, draft, null);
                    }
                }
            }
            SkillServices.Service saved = services.configureForSkill(editableServiceId(state, entry, name), name, adapter,
                    endpoint, modelsEndpoint, modelName, costUnits, token);
            entry.put("serviceId", saved.id()).put("draftId", UUID.randomUUID().toString());
            entry.remove(RETAINED_SERVICE);
            return advancePending(state, entry, draft, null);
        });
    }

    private static boolean sameGenerationConfiguration(SkillServices.Service binding, SkillServices.Adapter adapter,
                                                       String endpoint, String modelName, int costUnits) {
        return binding != null && binding.adapter() == adapter && binding.costUnits() == costUnits
                && binding.endpoint().equals(endpoint == null ? "" : endpoint.trim())
                && binding.modelName().equals(modelName == null ? "" : modelName.trim());
    }

    public List<String> discoverImageModels(String name, String draftId, String generationEndpoint,
                                            String modelsEndpoint, String typedBearer) throws Exception {
        DouAiLog.Operation trace = DouAiLog.current();
        if (trace != null) trace.stage("draft_snapshot", "required_scope=pending_image");
        boolean typed = SkillServices.hasScopedBearerInput(typedBearer);
        SkillServices.Service saved = locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            if (draft.outputType() != SkillResult.Type.IMAGE || usesHttp(draft)) {
                throw new IllegalStateException("此草稿没有图片配置项");
            }
            if (typed) return null;
            if (entry.has("serviceId")) return configured(entry, name);
            return pendingService(state, draft, entry);
        });
        if (trace != null) trace.stage("scope_snapshot", typed ? "selection=typed_only"
                : saved == null ? "selection=none" : "selection=saved");
        return services.discoverImageModels(name, saved, generationEndpoint, modelsEndpoint, typedBearer);
    }

    public Detail authorizeConfiguration(String name, String draftId, SkillServices.Service expected) throws Exception {
        return locked(state -> {
            JSONObject entry = requirePending(state, name, draftId);
            Draft draft = parse(entry.getString("definition"));
            SkillServices.Service target = configured(entry, name);
            if (target == null || !target.equals(expected)
                    || !configuration(draft, target, manifestValues(draft, entry)).canAuthorize()) {
                throw new IllegalStateException("服务配置已变化或仍缺必填项");
            }
            services.authorize(target, target.costUnits());
            return advancePending(state, entry, draft, null);
        });
    }

    public Detail activateReplacement(Detail confirmed) throws Exception {
        if (confirmed == null || !confirmed.canReplace()) throw new IllegalArgumentException("请确认要替换的配置");
        return locked(state -> {
            String name = confirmed.name();
            JSONObject entry = requirePending(state, name, confirmed.draftId());
            Draft draft = parse(entry.getString("definition"));
            JSONObject active = state.getJSONObject("skills").optJSONObject(name);
            if (active == null || !Objects.equals(confirmed.deleteTarget(), deleteTarget(name, active, entry))
                    || !NEEDS_REPLACEMENT.equals(entry.getString("reason"))) {
                throw new IllegalStateException("当前技能版本或配置已变化");
            }
            SkillServices.Service service = pendingService(state, draft, entry);
            SkillServices.Service approved = confirmed.service();
            boolean sameService = approved == null ? service == null
                    : service != null && SkillServices.sameBinding(approved, service);
            if (!sameService || service != null && !authorized(service)) {
                throw new IllegalStateException("草稿的服务已变化，请重新核对后确认");
            }
            Detail updated = advancePending(state, entry, draft, confirmed.previousVersion(), false, service);
            if (updated.pending()) throw new IllegalStateException("草稿尚未启用");
            return updated;
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
        return advancePending(state, entry, draft, replaceVersion, stageOnly, pendingService(state, draft, entry));
    }

    private Detail advancePending(JSONObject state, JSONObject entry, Draft draft, Integer replaceVersion,
                                  boolean stageOnly, SkillServices.Service service) throws Exception {
        String name = draft.name();
        Map<String, Object> values = manifestValues(draft, entry);
        Preview preview = preview(state, draft, service, values);
        String reason = preview.pendingReason();
        if (reason.isEmpty() && (service == null || authorized(service))) {
            try {
                verifyTrial(draft, service, values);
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
                    publish(state, draft, service, values);
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
        boolean skillsEnabled = state.getBoolean("enabled");
        DeleteTarget deletion = deleteTarget(name, installed, entry);
        Executable active = installed == null ? null : readExecution(revisionDirectory(activeRevision(installed)));
        SkillServices.Service currentService = active == null ? null : discoveryMetadata(active.service());
        if (entry == null) {
            if (current == null) throw new IllegalStateException("技能不存在");
            String reason = current.configurationRequired().isEmpty() ? enabled ? "已启用" : "已停用"
                    : current.configurationRequired();
            SkillCustomService.Binding binding = readCustomBinding(installed);
            return new Detail(name, current.outputType(), "", reason, Collections.emptyList(),
                    currentService, false, false, version, enabled, deletion,
                    current, currentService, skillsEnabled, null, active.manifest(), active.values(),
                    customConfiguration(binding), revisionVideoFacts(activeRevision(installed), active.manifest(), binding));
        }
        Draft draft = parse(entry.getString("definition"));
        DraftStatus pending = draftStatus(state, draft, entry, version);
        return new Detail(name, draft.outputType(), draftIdentity(name, entry), pending.reason(), pending.fields(),
                pending.service(), pending.canAuthorize(), pending.canReplace(), version, enabled, deletion,
                current, currentService, skillsEnabled, pending.state(), draft.manifest(), manifestValues(draft, entry),
                customConfiguration(pendingCustomBinding(state, draft, entry)),
                draft.outputType() == SkillResult.Type.VIDEO ? pendingVideoFacts(entry) : null);
    }

    private DraftStatus draftStatus(JSONObject state, Draft draft, JSONObject entry, int version) throws Exception {
        if (AiSkills.CREATION_COMMAND_NAME.equals(draft.name())) {
            return new DraftStatus(null, Collections.emptyList(), RESERVED_CREATION_NAME, false, false, DraftState.DRAFT);
        }
        SkillServices.Service explicit = configured(entry, draft.name());
        if (!imagePrefillMatches(draft, explicit)) explicit = null;
        SkillServices.Service target = explicit == null ? discoveryMetadata(pendingService(state, draft, entry)) : explicit;
        Map<String, Object> values = manifestValues(draft, entry);
        Configuration status = configuration(draft, target, values);
        List<Field> fields = status.fields();
        String reason = entry.getString("reason");
        if (draft.outputType() == SkillResult.Type.VIDEO && !usesHttp(draft)) {
            fields = List.of(required("video_provider", "视频任务接口声明", false));
            reason = pendingVideoReason(entry);
        } else if (usesHttp(draft)) {
            boolean configured = pendingCustomBinding(state, draft, entry) != null;
            fields = new ArrayList<>(fields);
            String missingAddress = missingServiceAddress(draft, entry);
            fields.add(required("custom_service", NEEDS_API_BASE.equals(missingAddress)
                    ? "公网 HTTPS API 基础地址" : "公网 HTTPS 接口地址", configured));
            if (!configured) reason = missingAddress;
        }
        boolean replace = version > 0 && NEEDS_REPLACEMENT.equals(reason) && !missingManifestValues(draft, values)
                && (!"service".equals(draft.kind()) || target != null && authorized(target));
        DraftState stateOfDraft = draftState(reason, fields, replace);
        if (NEEDS_IMAGE_SERVICE.equals(reason) && stateOfDraft == DraftState.DRAFT && target != null) {
            reason = "图片服务已就绪，请检查配置后继续";
        } else if (NEEDS_REPLACEMENT.equals(reason) && stateOfDraft != DraftState.READY) {
            reason = "草稿配置已变化，请检查后再启用";
        }
        return new DraftStatus(target, fields, reason, explicit != null && status.canAuthorize(), replace,
                stateOfDraft);
    }

    private static DraftState draftState(String reason, List<Field> fields, boolean canReplace) {
        if (TRIAL_FAILED.equals(reason)) return DraftState.DRAFT;
        boolean needsAuthorization = false;
        for (Field field : fields) {
            if (!field.required()) continue;
            if (field.state() == FieldState.MISSING) return DraftState.NEEDS_CONFIGURATION;
            needsAuthorization |= field.state() == FieldState.NEEDS_AUTHORIZATION;
        }
        if (needsAuthorization) return DraftState.NEEDS_AUTHORIZATION;
        return canReplace || EDIT_STAGED.equals(reason) ? DraftState.READY : DraftState.DRAFT;
    }

    private Configuration configuration(Draft draft, SkillServices.Service service, Map<String, Object> values) throws Exception {
        List<Field> fields = new ArrayList<>();
        if ("unconfigured".equals(draft.kind())) {
            fields.add(required("operation", "受支持的执行方式", false));
            return new Configuration(Collections.unmodifiableList(fields), false);
        }
        boolean usable = authorized(service);
        if ("service".equals(draft.kind())) {
            if (draft.outputType() == SkillResult.Type.IMAGE) {
                fields.addAll(SkillCapabilityRegistry.imageFields(services, service, usable));
            } else {
                fields.add(new Field("authorization", "服务授权", true,
                        usable ? FieldState.FILLED : FieldState.NEEDS_AUTHORIZATION));
            }
        }
        boolean canAuthorize = service != null && !usable && fields.stream().noneMatch(field -> field.state() == FieldState.MISSING);
        if (draft.manifest() != null) for (SkillManifest.ConfigField field : draft.manifest().configFields()) {
            fields.add(new Field(field.key(), field.label(), field.required(),
                    values.containsKey(field.key()) ? FieldState.FILLED : FieldState.MISSING));
        }
        return new Configuration(Collections.unmodifiableList(fields), canAuthorize);
    }

    private static Field required(String id, String label, boolean filled) {
        return new Field(id, label, true, filled ? FieldState.FILLED : FieldState.MISSING);
    }

    private SkillServices.Service configured(JSONObject entry, String name) throws SkillServices.Failure {
        if (entry == null || !entry.has("serviceId")) return null;
        return services.scoped(entry.optString("serviceId", ""), name);
    }

    private SkillServices.Service discoveryMetadata(SkillServices.Service binding) {
        if (binding == null || binding.adapter() != SkillServices.Adapter.OPENAI_IMAGES || binding.skillName().isEmpty()) {
            return binding;
        }
        try {
            SkillServices.Service current = services.scoped(binding.id(), binding.skillName());
            if (current != null && SkillServices.sameBinding(binding, current)) return current;
        } catch (SkillServices.Failure unavailable) {
            // A saved image revision remains inspectable when optional discovery metadata is unavailable.
        }
        return binding;
    }

    private String editableServiceId(JSONObject state, JSONObject entry, String name) throws Exception {
        SkillServices.Service scoped = configured(entry, name);
        if (scoped == null) return "";
        JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
        if (installed != null) {
            SkillServices.Service binding = readExecution(revisionDirectory(activeRevision(installed))).service();
            if (binding != null && scoped.id().equals(binding.id())) return "";
        }
        return scoped.id();
    }

    private static JSONObject requirePending(JSONObject state, String name, String draftId) throws Exception {
        requireRegistrableName(name);
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
        if (AiSkills.CREATION_COMMAND_NAME.equals(name)) return RESERVED_CREATION_NAME;
        return read(state -> {
            JSONObject draft = state.getJSONObject("drafts").optJSONObject(name);
            if (draft != null) {
                Draft definition = parse(draft.getString("definition"));
                if (definition.outputType() == SkillResult.Type.VIDEO && !usesHttp(definition)) return pendingVideoReason(draft);
                if (usesHttp(definition) && pendingCustomBinding(state, definition, draft) == null) {
                    return missingServiceAddress(definition, draft);
                }
                return "技能待连接：" + draft.getString("reason");
            }
            JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
            if (installed != null) {
                JSONObject revision = activeRevision(installed);
                String missing = configurationRequired(installed, revision);
                if (!missing.isEmpty()) return missing;
                if (installed.getBoolean("enabled")) return null;
                if (resultType(revision) == SkillResult.Type.IMAGE) return "图片技能已停用";
                if (TEXT_MODEL.equals(revision.optString("kind"))
                        || CUSTOM_KIND.equals(revision.optString("kind"))
                        || resultType(revision) == SkillResult.Type.TABLE || resultType(revision) == SkillResult.Type.VIDEO) {
                    return "技能已停用";
                }
            }
            return null;
        });
    }
    public Preview previewGenerated(String json) throws Exception {
        Draft draft = parse(json);
        requireRegistrableName(draft.name());
        return locked(state -> {
            JSONObject exactDraft = matchingDraft(state, draft, json);
            boolean freshVideo = draft.outputType() == SkillResult.Type.VIDEO && exactDraft == null;
            return preview(state, draft, pendingService(state, draft, exactDraft), freshVideo
                    ? authoredVideoDefaults(draft) : proposedManifestValues(state, draft), freshVideo);
        });
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
        return saveGenerated(draft, json, confirmation, mode, expectedDraftId, false);
    }

    private SaveResult saveGenerated(Draft draft, String json, Preview confirmation, SaveMode mode,
                                     String expectedDraftId, boolean requireNewPending) throws Exception {
        requireRegistrableName(draft.name());
        return locked(state -> {
            if (mode == SaveMode.CONVERSATION && !state.getBoolean("enabled")) {
                throw new IllegalStateException("Skills 已停用");
            }
            if (requireNewPending && state.getJSONObject("drafts").has(draft.name())) {
                throw new IllegalStateException("已有同名草稿，请重新选择要修改的草稿");
            }
            if (mode == SaveMode.CONVERSATION) verifyConversationPrefill(state, draft);
            if (mode == SaveMode.RECONNECT) {
                JSONObject currentDraft = state.getJSONObject("drafts").optJSONObject(draft.name());
                if (currentDraft == null || !json.equals(currentDraft.getString("definition"))
                        || (expectedDraftId != null && !expectedDraftId.equals(draftIdentity(draft.name(), currentDraft)))) {
                    throw new IllegalStateException("待连接草稿已变化，请重新打开");
                }
            }
            if (draft.outputType() == SkillResult.Type.VIDEO && usesHttp(draft)) {
                requireSupportedVideoEncoding(pendingVideoFacts(state.getJSONObject("drafts").optJSONObject(draft.name())));
            }
            JSONObject exactDraft = matchingDraft(state, draft, json);
            boolean freshVideo = draft.outputType() == SkillResult.Type.VIDEO && exactDraft == null;
            SkillServices.Service service = pendingService(state, draft, exactDraft);
            Map<String, Object> values = freshVideo ? authoredVideoDefaults(draft) : proposedManifestValues(state, draft);
            Preview current = preview(state, draft, service, values, freshVideo);
            if (confirmation != null && !current.equals(confirmation)) {
                throw new IllegalStateException("技能版本或服务已变化，请重新预览并确认");
            }
            if (draft.schemaVersion() != AUTHORED_SCHEMA && !current.pendingReason().isEmpty()) {
                return savePending(state, draft, json, current.pendingReason(), values);
            }
            try {
                verifyTrial(draft, service, values);
            } catch (Exception failure) {
                if (mode == SaveMode.CONVERSATION) return savePending(state, draft, json, TRIAL_FAILED, values);
                throw failure;
            }
            if (!current.pendingReason().isEmpty()) return savePending(state, draft, json, current.pendingReason(), values);
            if (mode == SaveMode.CONVERSATION && current.needsConfirmation()) {
                return savePending(state, draft, json, NEEDS_REPLACEMENT, values);
            }
            if (current.needsConfirmation() && confirmation == null) {
                throw new IllegalStateException("技能版本或服务已变化，请重新预览并确认");
            }
            if (service != null && !authorized(service)) {
                if (draft.outputType() == SkillResult.Type.IMAGE) return savePending(state, draft, json, NEEDS_IMAGE_SERVICE, values);
                throw new IllegalStateException("请先授权登记的服务");
            }
            return new SaveResult(publish(state, draft, service, values), null);
        });
    }

    private SaveResult savePending(JSONObject state, Draft draft, String json, String reason,
                                   Map<String, Object> values) throws Exception {
        json = persistedDraftDefinition(draft, json);
        JSONObject drafts = state.getJSONObject("drafts");
        JSONObject entry = drafts.optJSONObject(draft.name());
        if (entry == null || !json.equals(entry.optString("definition"))) {
            String skillId = draft.manifest() == null ? null : manifestSkillId(state, draft.name(), entry);
            boolean video = draft.outputType() == SkillResult.Type.VIDEO;
            SkillCustomService.Binding retained = video ? null : pendingCustomBinding(state, draft, entry);
            entry = new JSONObject().put("definition", json).put("draftId", UUID.randomUUID().toString());
            if (skillId != null) entry.put("skillId", skillId);
            if (video) entry.put(CUSTOM_BINDING, JSONObject.NULL);
            if (retained != null) entry.put(CUSTOM_BINDING, customRecord(retained));
            drafts.put(draft.name(), entry);
        }
        entry.put("outputType", draft.outputType().wireName()).put("reason", reason);
        storeManifestState(state, entry, draft, values);
        writeState(state);
        return new SaveResult(null, new Pending(draft.name(), draft.outputType(), reason,
                draftIdentity(draft.name(), entry)));
    }

    private static String persistedDraftDefinition(Draft draft, String json) {
        if (draft.manifest() == null || draft.manifest().draftValues().isEmpty()) return json;
        JSONObject manifest = draft.manifest().toJson();
        manifest.remove(SkillManifest.DRAFT_VALUES);
        return manifest.toString();
    }

    private Map<String, Object> proposedManifestValues(JSONObject state, Draft draft) throws Exception {
        SkillManifest manifest = draft.manifest();
        if (manifest == null) return Collections.emptyMap();
        JSONObject pending = state.getJSONObject("drafts").optJSONObject(draft.name());
        if (pending != null) {
            Draft previous = parse(pending.getString("definition"));
            if (previous.manifest() != null) {
                return manifest.applyDraftValues(manifest.retainValues(previous.manifest(), manifestValues(previous, pending)));
            }
        }
        JSONObject installed = state.getJSONObject("skills").optJSONObject(draft.name());
        if (installed != null) {
            Executable previous = readExecution(revisionDirectory(activeRevision(installed)));
            if (previous.manifest() != null) return manifest.applyDraftValues(manifest.retainValues(previous.manifest(), previous.values()));
        }
        return manifest.applyDraftValues(manifest.readValues(null));
    }

    private static Map<String, Object> authoredVideoDefaults(Draft draft) {
        return draft.manifest().applyDraftValues(draft.manifest().readValues(null));
    }

    private Map<String, Object> explicitVideoValues(JSONObject state, Draft draft, SkillVideoFacts facts) throws Exception {
        if (!facts.isEmpty()) {
            SkillVideoFacts approved = reusableVideoFacts(state, draft.name());
            if (approved != null && approved.toJson().toString().equals(facts.toJson().toString())) {
                JSONObject owner = state.getJSONObject("skills").getJSONObject(draft.name());
                Executable current = readExecution(revisionDirectory(activeRevision(owner)));
                return draft.manifest().applyDraftValues(draft.manifest().retainValues(current.manifest(), current.values()));
            }
        }
        return authoredVideoDefaults(draft);
    }

    private Map<String, Object> videoValues(JSONObject state, Draft draft) throws Exception {
        SkillManifest manifest = draft.manifest();
        if (manifest.configFields().isEmpty()) return manifest.readValues(null);
        JSONObject pending = state.getJSONObject("drafts").optJSONObject(draft.name());
        if (pending != null) {
            Draft previous = parse(pending.getString("definition"));
            if (previous.outputType() == SkillResult.Type.VIDEO && usesHttp(previous)) {
                return manifest.applyDraftValues(manifest.retainValues(previous.manifest(), manifestValues(previous, pending)));
            }
            JSONObject retained = pending.optJSONObject(VIDEO_DRAFT_CONFIG);
            if (retained != null) return manifest.applyDraftValues(restoreVideoDraftConfig(manifest, retained));
            // An incomplete draft without retained values is authoritative. Falling through to the
            // installed revision would restore the old model/parameters after an interface change.
            return manifest.applyDraftValues(manifest.readValues(null));
        }
        JSONObject installed = state.getJSONObject("skills").optJSONObject(draft.name());
        if (installed != null) {
            Executable previous = readExecution(revisionDirectory(activeRevision(installed)));
            if (previous.outputType() == SkillResult.Type.VIDEO && previous.manifest() != null) {
                return manifest.applyDraftValues(manifest.retainValues(previous.manifest(), previous.values()));
            }
        }
        return manifest.applyDraftValues(manifest.readValues(null));
    }

    private static Map<String, Object> manifestValues(Draft draft, JSONObject entry) throws Exception {
        return draft.manifest() == null ? Collections.emptyMap() : readManifestValues(draft.manifest(), entry);
    }

    private static Map<String, Object> readManifestValues(SkillManifest manifest, JSONObject record) throws Exception {
        JSONObject values = record == null || !record.has("configValues") ? null : record.getJSONObject("configValues");
        return Collections.unmodifiableMap(new LinkedHashMap<>(manifest.readValues(values)));
    }

    private static boolean missingManifestValues(Draft draft, Map<String, Object> values) throws Exception {
        return draft.manifest() != null && !draft.manifest().missing(values).isEmpty();
    }

    private static void storeManifestState(JSONObject state, JSONObject entry, Draft draft, Map<String, Object> values)
            throws Exception {
        if (draft.manifest() == null) return;
        entry.put("skillId", manifestSkillId(state, draft.name(), entry))
                .put("configValues", SkillManifest.valuesJson(values));
    }

    private static String manifestSkillId(JSONObject state, String name, JSONObject pending) throws Exception {
        JSONObject installed = state.getJSONObject("skills").optJSONObject(name);
        String id = installed == null ? "" : installed.optString("skillId");
        if (!canonicalSkillId(id) && pending != null) id = pending.optString("skillId");
        return canonicalSkillId(id) ? id : UUID.randomUUID().toString();
    }

    private static boolean canonicalSkillId(String id) {
        try { return UUID.fromString(id).toString().equals(id); }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private static JSONObject matchingDraft(JSONObject state, Draft draft, String json) throws Exception {
        JSONObject old = state.getJSONObject("drafts").optJSONObject(draft.name());
        return old != null && json.equals(old.optString("definition")) ? old : null;
    }
    SkillResult executeResult(String name, int version, String revisionId, String argument, SkillResult.Type expectedType,
                              AiSkills.TextExecutor textExecutor) throws Exception {
        requireRegistrableName(name);
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
        SkillResult result;
        if (definition.manifest() != null) {
            if (!definition.manifest().missing(definition.values()).isEmpty()) {
                throw new IllegalStateException(NEEDS_MANIFEST_VALUES);
            }
            result = SkillCapabilityRegistry.execute(definition.manifest().capability(), services, definition.service(),
                    definition.instruction(), argument, definition.values(), textExecutor);
        } else if ("summary".equals(definition.kind())) {
            return new SkillResult.Text(AiSkills.summarize(argument));
        } else if (TEXT_MODEL.equals(definition.kind())) {
            if (textExecutor == null) throw new IllegalStateException("此入口的技能模型不可用");
            return executeTextModel(definition.instruction(), argument, textExecutor);
        } else if (definition.outputType() == SkillResult.Type.IMAGE) {
            result = SkillCapabilityRegistry.execute(SkillCapabilityRegistry.Capability.IMAGE_GENERATION, services,
                    definition.service(), "", argument, Collections.emptyMap(), textExecutor);
        } else {
            result = services.executeResult(definition.service(), argument);
        }
        if (result.type() != definition.outputType()) throw new IOException("服务返回的结果类型不符合技能声明");
        return result;
    }

    private SkillServices.Service pendingService(JSONObject state, Draft draft, JSONObject entry) throws Exception {
        if (!"service".equals(draft.kind())) return null;
        if (entry != null) {
            if (entry.has("serviceId")) {
                SkillServices.Service explicit = configured(entry, draft.name());
                return imagePrefillMatches(draft, explicit) && authorized(explicit) ? explicit : null;
            }
            JSONObject retained = entry.optJSONObject(RETAINED_SERVICE);
            if (retained != null) {
                SkillServices.Service service = readService(retained, draft.outputType());
                return imagePrefillMatches(draft, service) ? service : null;
            }
        }
        SkillServices.Service service = resolve(state, draft);
        return imagePrefillMatches(draft, service) ? service : null;
    }

    private static boolean imagePrefillMatches(Draft draft, SkillServices.Service service) {
        if (draft.outputType() != SkillResult.Type.IMAGE || draft.manifest() == null) return true;
        SkillManifest.ServicePrefill prefill = draft.manifest().servicePrefill();
        if (prefill.isEmpty()) return true;
        return service != null && service.adapter() == SkillServices.Adapter.OPENAI_IMAGES
                && (prefill.endpoint().isEmpty() || prefill.endpoint().equals(service.endpoint()))
                && (prefill.model().isEmpty() || prefill.model().equals(service.modelName()));
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
    private static void verifyTrial(Draft draft, SkillServices.Service service, Map<String, Object> values) throws Exception {
        if (usesHttp(draft)) {
            checkArgument(draft.trialInput(), draft.maxInput());
            SkillAuthoring.rejectCredential(draft.trialInput());
            if (draft.manifest().http() != null) draft.manifest().http().requestBody(draft.trialInput(), values);
            return;
        }
        SkillResult simulated = trial(draft, service, values);
        if (simulated.type() != draft.outputType()
                || (draft.expected() != null && simulated instanceof SkillResult.Text text
                && !text.text().equals(draft.expected()))) {
            throw new IllegalArgumentException("模拟试跑结果与预期不符");
        }
    }
    private static SkillResult trial(Draft draft, SkillServices.Service service, Map<String, Object> values) throws Exception {
        checkArgument(draft.trialInput(), draft.maxInput());
        if (draft.outputType() == SkillResult.Type.IMAGE) return SkillServices.mockImageResult();
        if (draft.outputType() == SkillResult.Type.VIDEO) throw new IllegalStateException(NEEDS_VIDEO_PROVIDER);
        if (draft.outputType() == SkillResult.Type.TABLE) {
            SkillAuthoring.textInput(draft.instruction(), draft.trialInput(), values);
            return new SkillResult.Table(SkillTable.parse("{\"columns\":[\"示例\"],\"rows\":[[\"模拟结果\"]]}"));
        }
        if (TEXT_MODEL.equals(draft.kind())) {
            if (draft.manifest() != null) {
                SkillAuthoring.textInput(draft.instruction(), draft.trialInput(), values);
                return new SkillResult.Text("模拟文本响应");
            }
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

    private Preview preview(JSONObject state, Draft draft, SkillServices.Service service, Map<String, Object> values)
            throws Exception {
        return preview(state, draft, service, values, false);
    }

    private Preview preview(JSONObject state, Draft draft, SkillServices.Service service, Map<String, Object> values,
                            boolean unboundVideo) throws Exception {
        JSONObject existing = state.getJSONObject("skills").optJSONObject(draft.name());
        int previous = existing == null ? 0 : activeRevision(existing).getInt("version");
        JSONObject existingDraft = state.getJSONObject("drafts").optJSONObject(draft.name());
        String pending = missingManifestValues(draft, values) ? NEEDS_MANIFEST_VALUES
                : "unconfigured".equals(draft.kind()) ? NEEDS_OPERATION
                : draft.outputType() == SkillResult.Type.VIDEO && !usesHttp(draft) ? pendingVideoReason(existingDraft)
                : usesHttp(draft) && (unboundVideo || pendingCustomBinding(state, draft, existingDraft) == null)
                ? missingServiceAddress(draft, unboundVideo ? null : existingDraft)
                : "service".equals(draft.kind()) && service == null && draft.outputType() == SkillResult.Type.IMAGE ? NEEDS_IMAGE_SERVICE : "";
        boolean reviewTextService = draft.outputType() == SkillResult.Type.TEXT && service != null
                && !SkillServices.MOCK_ORDERS.equals(service.id());
        return new Preview(draft.name(), service == null ? "" : service.label(), service == null ? "" : service.endpoint(),
                service == null ? 0 : service.costUnits(), pending.isEmpty() && (previous > 0 || reviewTextService),
                service == null ? "" : service.id(), draft.outputType(), pending, previous,
                deleteTarget(draft.name(), existing, existingDraft));
    }
    private Installed publish(JSONObject state, Draft draft, SkillServices.Service service, Map<String, Object> values)
            throws Exception {
        requireRegistrableName(draft.name());
        if (missingManifestValues(draft, values)) throw new IllegalStateException(NEEDS_MANIFEST_VALUES);
        if (draft.outputType() == SkillResult.Type.VIDEO && !usesHttp(draft)) throw new IllegalStateException(NEEDS_VIDEO_PROVIDER);
        SkillCustomService.Binding custom = pendingCustomBinding(state, draft,
                state.getJSONObject("drafts").optJSONObject(draft.name()));
        if (usesHttp(draft) && custom == null) throw new IllegalStateException(NEEDS_CUSTOM_SERVICE);
        JSONObject skills = state.getJSONObject("skills");
        JSONObject skill = skills.optJSONObject(draft.name());
        int highWater = skill == null ? 0 : highestVersion(skill);
        if (skill == null) {
            if (skills.length() >= 50) throw new IllegalStateException("最多可登记 50 个技能");
            skill = new JSONObject();
            skills.put(draft.name(), skill);
        }
        if (highWater == Integer.MAX_VALUE) throw new IllegalStateException("技能版本号已达到上限");
        int number = highWater + 1;
        JSONObject previousCurrent = skill.optJSONObject("current");
        String ref = UUID.randomUUID().toString();
        JSONObject revision = new JSONObject().put("version", number).put("directory", ref)
                .put("description", draft.description()).put("outputType", draft.outputType().wireName())
                .put("kind", draft.kind())
                .put("serviceLabel", service == null ? "" : service.label());
        if (draft.outputType() == SkillResult.Type.VIDEO) {
            JSONObject pending = state.getJSONObject("drafts").optJSONObject(draft.name());
            SkillVideoFacts facts = revisionVideoFacts(pending, draft.manifest(), custom);
            if (facts != null) revision.put(VIDEO_FACTS, facts.toJson());
        }
        if (draft.manifest() != null && draft.manifest().http() != null) revision.put("httpVersion", 1);
        if (draft.manifest() != null && !draft.name().equals(draft.manifest().displayName())) {
            revision.put("displayName", draft.manifest().displayName());
        }
        File target = revisionDirectory(revision);
        if (!target.mkdirs()) throw new IOException("无法创建技能版本");
        try {
            writeDocument(new File(target, "SKILL.md"),
                    "---\nname: " + draft.name() + "\nversion: " + number + "\noutput: " + draft.outputType().wireName() + "\n---\n\n" + draft.documentation() + "\n");
            int schema = draft.manifest() != null ? MANIFEST_SCHEMA
                    : draft.schemaVersion() == AUTHORED_SCHEMA ? AUTHORED_SCHEMA : 2;
            JSONObject definition = new JSONObject().put("schemaVersion", schema)
                    .put("version", number).put("kind", draft.kind()).put("maxInput", draft.maxInput())
                    .put("outputType", draft.outputType().wireName());
            if (draft.manifest() != null) {
                JSONObject activeManifest = draft.manifest().toJson();
                activeManifest.remove(SkillManifest.DRAFT_VALUES);
                activeManifest.remove(SkillManifest.SERVICE_PREFILL);
                definition.put("manifest", activeManifest).put("configValues", SkillManifest.valuesJson(values));
                JSONObject pending = state.getJSONObject("drafts").optJSONObject(draft.name());
                skill.put("skillId", manifestSkillId(state, draft.name(), pending));
            } else if (TEXT_MODEL.equals(draft.kind())) {
                definition.put("instruction", draft.instruction());
            }
            if (service != null) definition.put("service", serviceRecord(service));
            String execution = definition.toString();
            if (execution.getBytes(StandardCharsets.UTF_8).length > LIMIT) {
                throw new IOException("技能配置过长");
            }
            writeDocument(new File(target, "execution.json"), execution);
            if (previousCurrent != null) {
                Set<String> retired = retiredVersions(state);
                collectRevisionId(previousCurrent, retired);
                if (!retired.isEmpty()) state.put(RETIRED_VERSIONS, new JSONArray(retired));
            }
            skill.put("current", revision).put("enabled", true);
            if (custom == null) skill.remove(CUSTOM_BINDING);
            else skill.put(CUSTOM_BINDING, customRecord(custom));
            state.getJSONObject("drafts").remove(draft.name());
            writeState(state);
        } catch (Exception failure) {
            new File(target, "SKILL.md").delete();
            new File(target, "execution.json").delete();
            target.delete();
            throw failure;
        }
        if (previousCurrent != null) cleanupRetiredVersions(state);
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
            if (schema == AUTHORED_SCHEMA || schema == MANIFEST_SCHEMA) return parseAuthored(json);
            if (schema != 1 && schema != 2) throw new IllegalArgumentException();
            return declaration(value, schema);
        } catch (SkillManifest.Invalid invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw invalidDraft();
        }
    }

    private static Draft parseAuthored(String json) {
        try {
            JSONObject value = SkillAuthoring.readObject(json, DRAFT_LIMIT);
            int schema = integer(value, "schemaVersion");
            if (schema == MANIFEST_SCHEMA) return manifestDraft(SkillManifest.parse(json));
            if (schema != AUTHORED_SCHEMA) throw new SkillManifest.Invalid("此技能清单版本不受支持");
            return declaration(value, AUTHORED_SCHEMA);
        } catch (SkillManifest.Invalid invalid) {
            throw invalid;
        } catch (Exception invalid) {
            throw invalidDraft();
        }
    }

    private static Draft manifestDraft(SkillManifest manifest) {
        return new Draft(manifest.name(), manifest.description(), manifest.documentation(), manifest.capability().kind(),
                manifest.maxInput(), manifest.outputType(), null, manifest.trialInput(), null,
                manifest.instructions(), MANIFEST_SCHEMA, manifest);
    }

    private static IllegalArgumentException invalidDraft() {
        return new IllegalArgumentException("生成的技能定义结构或参数无效");
    }

    private static void requireRegistrableName(String name) {
        if (AiSkills.CREATION_COMMAND_NAME.equals(name)) throw new SkillManifest.Invalid(RESERVED_CREATION_NAME);
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
        if (output != SkillResult.Type.TEXT && output != SkillResult.Type.IMAGE) throw invalidDraft();
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
                text(test.get("input"), maxInput), expected, instruction, schema, null);
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
        if (skill.has("current")) return skill.getJSONObject("current");
        return skill.getJSONArray("versions").getJSONObject(skill.getInt("active"));
    }

    private static int highestVersion(JSONObject skill) throws Exception {
        int highest = activeRevision(skill).getInt("version");
        JSONArray legacy = skill.optJSONArray("versions");
        if (legacy != null) for (int i = 0; i < legacy.length(); i++) {
            JSONObject revision = legacy.optJSONObject(i);
            if (revision != null) highest = Math.max(highest, revision.optInt("version", 0));
        }
        return highest;
    }

    private static Installed installed(String name, JSONObject skill) throws Exception {
        JSONObject revision = activeRevision(skill);
        String missing = AiSkills.CREATION_COMMAND_NAME.equals(name) ? RESERVED_CREATION_NAME
                : configurationRequired(skill, revision);
        return new Installed(name, revision.getInt("version"), skill.getBoolean("enabled") && missing.isEmpty(),
                resultType(revision), revision.optString("serviceLabel", ""), missing);
    }

    private static String configurationRequired(JSONObject skill, JSONObject revision) {
        if (resultType(revision) == SkillResult.Type.VIDEO && revision.optInt("httpVersion") != 1) return NEEDS_VIDEO_PROVIDER;
        if (!usesHttp(revision) || readCustomBinding(skill) != null) return "";
        return NEEDS_CUSTOM_SERVICE;
    }

    private static String revisionId(JSONObject version) throws IOException, JSONException {
        String id = version.getString("directory");
        if (!REVISION.matcher(id).matches()) throw new IOException("技能版本标识无效");
        return id;
    }

    private File revisionDirectory(JSONObject version) throws Exception {
        return new File(new File(directory, "versions"), revisionId(version));
    }

    private static void collectRevisionIds(JSONObject installed, Set<String> ids) {
        collectRevisionId(installed.optJSONObject("current"), ids);
        JSONArray revisions = installed.optJSONArray("versions");
        if (revisions != null) for (int i = 0; i < revisions.length(); i++) {
            collectRevisionId(revisions.optJSONObject(i), ids);
        }
    }

    private static void collectRevisionId(JSONObject revision, Set<String> ids) {
        Object value = revision == null ? null : revision.opt("directory");
        if (value instanceof String && REVISION.matcher((String) value).matches()) ids.add((String) value);
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
        if (schema == MANIFEST_SCHEMA) return readManifestExecution(definition);
        if (schema != 1 && schema != 2 && schema != AUTHORED_SCHEMA) throw new IOException("技能版本不兼容");
        SkillResult.Type output = schema == 1 ? SkillResult.Type.TEXT
                : SkillResult.Type.fromWire(definition.getString("outputType"));
        if (output != SkillResult.Type.TEXT && output != SkillResult.Type.IMAGE) throw new IOException("技能版本不兼容");
        String kind = definition.getString("kind");
        int maxInput = definition.getInt("maxInput");
        if ("summary".equals(kind) && output == SkillResult.Type.TEXT) {
            return new Executable(kind, maxInput, output, null, "", schema, null, Collections.emptyMap());
        }
        if (schema == AUTHORED_SCHEMA && TEXT_MODEL.equals(kind) && output == SkillResult.Type.TEXT) {
            JSONObject authored = SkillAuthoring.readObject(content, LIMIT);
            keys(authored, "schemaVersion", "version", "kind", "maxInput", "outputType", "instruction");
            int validatedLimit = integer(authored, "maxInput");
            if (integer(authored, "schemaVersion") != AUTHORED_SCHEMA || validatedLimit < 1
                    || validatedLimit > TEXT_MODEL_MAX_INPUT) throw new IOException("技能版本不兼容");
            return new Executable(kind, validatedLimit, output, null, modelInstruction(authored.get("instruction")),
                    schema, null, Collections.emptyMap());
        }
        if (!"service".equals(kind)) throw new IOException("技能类型不支持");
        return new Executable(kind, maxInput, output, readService(definition.getJSONObject("service"), output),
                "", schema, null, Collections.emptyMap());
    }

    private static Executable readManifestExecution(JSONObject definition) throws Exception {
        SkillManifest manifest = SkillManifest.parse(definition.getJSONObject("manifest").toString());
        String kind = manifest.capability().kind();
        SkillResult.Type output = manifest.outputType();
        boolean service = "service".equals(kind);
        if (service) keys(definition, "schemaVersion", "version", "kind", "maxInput",
                "outputType", "manifest", "configValues", "service");
        else keys(definition, "schemaVersion", "version", "kind", "maxInput", "outputType", "manifest", "configValues");
        if (integer(definition, "schemaVersion") != MANIFEST_SCHEMA || integer(definition, "version") <= 0
                || !kind.equals(definition.get("kind")) || !output.wireName().equals(definition.get("outputType"))
                || manifest.maxInput() != integer(definition, "maxInput")) {
            throw new IOException("技能版本不兼容");
        }
        SkillServices.Service binding = service ? readService(definition.getJSONObject("service"), output) : null;
        return new Executable(kind, manifest.maxInput(), output, binding, manifest.instructions(), MANIFEST_SCHEMA,
                manifest, readManifestValues(manifest, definition));
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
