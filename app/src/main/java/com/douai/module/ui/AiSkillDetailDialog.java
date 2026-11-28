package com.douai.module.ui;

import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputFilter;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.R;
import com.douai.module.ai.AiCredentialVault;
import com.douai.module.ai.skills.SkillImageModelCatalog;
import com.douai.module.ai.skills.SkillImageProviderAddress;
import com.douai.module.ai.skills.SkillManifest;
import com.douai.module.ai.skills.SkillCapabilityRegistry;
import com.douai.module.ai.skills.SkillCustomService;
import com.douai.module.ai.skills.SkillHttpDeclaration;
import com.douai.module.ai.skills.SkillResult;
import com.douai.module.ai.skills.SkillServices;
import com.douai.module.ai.skills.SkillStore;
import com.douai.module.ai.skills.SkillVideoFacts;
import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.HostSettingsStore;
import com.douai.module.diagnostics.DouAiLog;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Configuration for one exact skill, shared by manual chats and the Skills list. */
final class AiSkillDetailDialog implements AutoCloseable {
    @FunctionalInterface
    interface ModelDiscovery {
        List<String> fetch(String name, String draftId, String endpoint, String modelsEndpoint, String bearer) throws Exception;
    }

    @FunctionalInterface
    interface VideoModelDiscovery {
        List<String> fetch(SkillStore.Detail expected, String apiBase, String bearer) throws Exception;
    }

    private static final int MAX_KEY_ENTRY = AiCredentialVault.MAX_IMAGE_BEARER_CHARS + 64;
    private static final String FETCHING_MODELS = "正在查询模型列表…";
    private final Context context;
    private final boolean miuix;
    private final String name;
    private final Runnable onChanged;
    private final SkillStore store;
    private final ModelDiscovery discovery;
    private final VideoModelDiscovery videoDiscovery;
    private final ExecutorService discoveryWorker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout body;
    private final AlertDialog dialog;
    private AlertDialog confirmation;
    private TextView status;
    private EditText keyInput;
    private Button modelLookupButton;
    private TextView modelLookupStatus;
    private Future<?> modelLookup;
    private DouAiLog.Operation modelLookupTrace;
    private long modelLookupGeneration;
    private boolean closed;

    AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged) {
        this(context, miuix, exactSkillName, onChanged, null, Executors.newSingleThreadExecutor());
    }

    AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged,
                        ModelDiscovery discovery, ExecutorService discoveryWorker) {
        this(context, miuix, exactSkillName, onChanged, discovery, discoveryWorker, null);
    }

    AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged,
                        ModelDiscovery discovery, ExecutorService discoveryWorker, SkillStore localStore) {
        this(context, miuix, exactSkillName, onChanged, discovery, discoveryWorker, localStore, null);
    }

    AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged,
                        ModelDiscovery discovery, ExecutorService discoveryWorker, SkillStore localStore,
                        VideoModelDiscovery videoDiscovery) {
        this(context, miuix, exactSkillName, onChanged, discovery, discoveryWorker, null, null, localStore, videoDiscovery);
    }

    static AiSkillDetailDialog preview(Context context, boolean miuix, SkillStore.DraftPreview draft, SkillVideoFacts facts) {
        if (draft == null) throw new IllegalArgumentException("没有可查看的技能草案");
        SkillVideoFacts supplied = facts == null ? SkillVideoFacts.empty() : facts;
        SkillStore.DraftPreview validated = draft.outputType() == SkillResult.Type.VIDEO
                ? SkillStore.prepareAuthoredVideoDraft(draft.definition(), supplied)
                : SkillStore.validateAuthoredDraft(draft.definition());
        return new AiSkillDetailDialog(context, miuix, validated.name(), null, null, null, validated, supplied, null, null);
    }

    private AiSkillDetailDialog(Context context, boolean miuix, String exactSkillName, Runnable onChanged,
                                ModelDiscovery discovery, ExecutorService discoveryWorker,
                                SkillStore.DraftPreview preview, SkillVideoFacts facts, SkillStore localStore,
                                VideoModelDiscovery videoDiscovery) {
        this.context = context;
        this.miuix = miuix;
        name = exactSkillName;
        this.onChanged = onChanged;
        store = preview == null ? localStore == null ? SkillStore.forContext(context) : localStore : null;
        this.discovery = discovery != null ? discovery : store == null ? null : store::discoverImageModels;
        this.videoDiscovery = videoDiscovery != null ? videoDiscovery : store == null ? null : store::discoverVideoModels;
        this.discoveryWorker = discoveryWorker;
        body = column();
        body.setTag("skills.detail." + name);
        int padding = dp(16);
        body.setPadding(padding, 0, padding, padding);
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        dialog = builder("技能配置").setView(scroll).setNegativeButton("关闭", null).create();
        if (preview == null) reload("");
        else renderDraftPreview(preview, facts);
    }

    AlertDialog dialog() { return dialog; }

    private void reload(String feedback) {
        try {
            render(store.detail(name), feedback);
        }
        catch (Exception failure) {
            render(null, feedback.isEmpty() ? "暂时无法读取此技能的配置" : feedback);
        }
    }

    private void render(SkillStore.Detail detail, String feedback) {
        render(detail, feedback, false);
    }

    private void render(SkillStore.Detail detail, String feedback, boolean showImageConfiguration) {
        cancelModelLookup("form_rebound");
        modelLookupButton = null;
        modelLookupStatus = null;
        clearKey();
        body.removeAllViews();
        SkillManifest manifest = detail == null ? null : detail.manifest();
        renderHeading(manifest == null ? name : manifest.displayName());
        if (detail == null) {
            status = text(body, feedback.isEmpty() ? "此技能已不存在" : feedback, "skills.detail.status");
            status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
            return;
        }
        status = text(body, feedback, "skills.detail.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        SkillStore.Installed current = detail.current();
        if (current != null) {
            section("当前配置", "skills.detail.current");
            String enabled = AiSkillSettingsDialog.installedState(current, detail.skillsEnabled());
            text(body, "v" + current.version() + " · " + outputLabel(current.outputType()) + " · " + enabled,
                    "skills.detail.summary");
            if (!current.configurationRequired().isEmpty() && !detail.pending()) {
                text(body, current.configurationRequired(), "skills.detail.pending_configuration");
            }
            SkillServices.Service service = detail.currentService();
            if (service != null) {
                String label = service.modelName().isEmpty() ? service.label() : service.label() + " · " + service.modelName();
                text(body, label, "skills.detail.service");
            }
            if (current.configurationRequired().isEmpty()) installedActions(detail);
        }
        if (detail.pending()) {
            section(current == null ? "技能草稿" : "待启用修改", "skills.detail.draft");
            text(body, outputLabel(detail.outputType()) + " · " + AiSkillSettingsDialog.draftState(detail.draftState()),
                    "skills.detail.draft_summary");
            if (detail.reason() != null && !detail.reason().isEmpty()) {
                text(body, detail.reason(), "skills.detail.reason");
            }
            for (SkillStore.Field field : detail.fields()) {
                if (field.required() && field.state() != SkillStore.FieldState.FILLED) {
                    text(body, field.label() + " · " + fieldState(field), "skills.detail.field." + field.id());
                }
            }
        }
        if (manifest != null) text(body, "服务 · " + manifest.serviceRef().label(), "skills.detail.service_ref");
        manifestConfiguration(detail);
        customConfiguration(detail);
        if (detail.pending()) pendingActions(detail, showImageConfiguration);
        SkillStore.DeleteTarget deleteTarget = detail.deleteTarget();
        if (deleteTarget != null) {
            text(body, "在聊天中输入 /技能 编辑 /" + name + " 修改技能或接口。", "skills.detail.edit_in_chat");
            button(body, "删除", "skills.detail.delete", () -> reviewDelete(deleteTarget));
        }
    }

    private void renderHeading(String displayName) {
        boolean hasDisplayName = displayName != null && !displayName.isBlank() && !displayName.equals(name);
        if (hasDisplayName) {
            TextView label = text(body, displayName, "skills.detail.display_name");
            label.setTypeface(null, Typeface.BOLD);
            label.setAccessibilityHeading(true);
        }
        TextView title = text(body, "/" + name, "skills.detail.name");
        if (!hasDisplayName) {
            title.setTypeface(null, Typeface.BOLD);
            title.setAccessibilityHeading(true);
        }
    }

    private void renderDraftPreview(SkillStore.DraftPreview draft, SkillVideoFacts facts) {
        SkillManifest manifest = draft.capabilityId().isEmpty() ? null : SkillManifest.parse(draft.definition());
        renderHeading(manifest == null ? name : manifest.displayName());
        section("技能草案", "skills.detail.draft");
        text(body, outputLabel(draft.outputType()) + " · 会话预览", "skills.detail.draft_summary");
        text(body, draft.description(), "skills.detail.draft_description");
        if (manifest != null) text(body, "服务 · " + manifest.serviceRef().label(), "skills.detail.service_ref");
        if (manifest != null && !manifest.documentation().isEmpty()) {
            text(body, manifest.documentation(), "skills.detail.draft_documentation");
        }
        if (!draft.instruction().isEmpty()) text(body, draft.instruction(), "skills.detail.draft_instruction");
        if (draft.outputType() == SkillResult.Type.VIDEO) {
            text(body, "执行方式 · " + SkillCapabilityRegistry.ExecutionMode.POLLING.label(), "skills.detail.execution_mode");
            text(body, "输出类型 · " + draft.outputType().label(), "skills.detail.output_type");
            text(body, videoMissingLabel(facts), "skills.detail.video_facts.missing");
            button(body, "查看接口字段", "skills.detail.video_facts.open", () -> showReadOnlyVideoFacts(facts));
        }
        if (draft.outputType() == SkillResult.Type.VIDEO || manifest != null && manifest.usesHttp()) {
            text(body, "内置后可在技能设置填写服务地址和密钥。", "skills.detail.status");
        }
    }

    private void showReadOnlyVideoFacts(SkillVideoFacts facts) {
        if (!active()) return;
        LinearLayout form = column();
        int padding = dp(16);
        form.setPadding(padding, 0, padding, padding);
        text(form, videoMissingLabel(facts), "skills.detail.video_facts.missing");
        if (facts.minimumPollSeconds() != 0) {
            text(form, "最短轮询间隔 · " + facts.minimumPollSeconds() + " 秒", "skills.detail.video_facts.min_poll_seconds");
        }
        for (SkillVideoFacts.FormField field : facts.formFields()) {
            String tag = "skills.detail.video_facts.field." + field.id();
            text(form, videoFactLabel(field), tag + ".label");
            text(form, field.value(), tag).setTextIsSelectable(true);
            text(form, videoFactSourceLabel(field), "skills.detail.video_facts.source." + field.id());
        }
        ScrollView scroll = new ScrollView(context);
        scroll.addView(form);
        showChild(builder("视频接口字段").setView(scroll).setNegativeButton("关闭", null).create());
    }

    private void manifestConfiguration(SkillStore.Detail detail) {
        SkillManifest manifest = detail.manifest();
        if (manifest == null || !manifest.capability().usesModel() || manifest.configFields().isEmpty()) return;
        section("配置字段", "skills.detail.config");
        text(body, "配置值将提供给执行模型，仅填写非敏感内容", "skills.detail.config.guidance");
        Map<String, Supplier<String>> values = manifestInputs(detail);
        button(body, "保存配置", "skills.detail.config.save", () -> {
            saveManifestConfiguration(detail, enteredValues(values));
        });
    }

    private Map<String, Supplier<String>> manifestInputs(SkillStore.Detail detail) {
        Map<String, Supplier<String>> values = new LinkedHashMap<>();
        for (SkillManifest.ConfigField field : detail.manifest().configFields()) {
            values.put(field.key(), manifestInput(body, field, detail.manifestValues().get(field.key())));
        }
        return values;
    }

    private static Map<String, String> enteredValues(Map<String, Supplier<String>> fields) {
        Map<String, String> values = new LinkedHashMap<>();
        fields.forEach((key, field) -> values.put(key, field.get()));
        return values;
    }

    private void customConfiguration(SkillStore.Detail detail) {
        SkillManifest manifest = detail.manifest();
        if (manifest == null) return;
        if (manifest.capability().executionMode() == SkillCapabilityRegistry.ExecutionMode.POLLING) {
            videoConfiguration(detail);
        } else if (manifest.usesHttp()) {
            directConfiguration(detail);
        }
    }

    private void directConfiguration(SkillStore.Detail detail) {
        SkillHttpDeclaration declaration = detail.manifest().http();
        section(declaration == null ? "HTTPS 服务" : declaration.label(), "skills.detail.custom");
        describeContract(body, detail);
        EditText endpoint = input(body, "公网 HTTPS 完整接口地址", "skills.detail.custom.endpoint",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        SkillStore.CustomConfiguration current = detail.customConfiguration();
        if (current != null) endpoint.setText(current.endpoint());
        else {
            String proposed = proposedEndpoint(detail.manifest());
            if (!proposed.isEmpty()) {
                endpoint.setText(proposed);
                text(body, "草案建议地址，核对后保存。", "skills.detail.custom.endpoint_suggestion");
            }
        }
        EditText bearer = serviceKey(current);
        saveServiceButton(detail, endpoint, bearer, manifestInputs(detail), false, "");
        removeServiceKey(body, detail, bearer);
    }

    private void videoConfiguration(SkillStore.Detail detail) {
        SkillVideoFacts facts = detail.videoFacts();
        SkillHttpDeclaration declaration = detail.manifest().http();
        SkillStore.CustomConfiguration current = detail.customConfiguration();
        LinearLayout advanced = column();
        if (declaration != null) describeContract(advanced, detail);
        if (facts != null) {
            if (!facts.submitPath().isEmpty()) {
                text(advanced, "提交 POST " + facts.submitPath(), "skills.detail.custom.submit_path");
            }
            button(advanced, "查看接口字段", "skills.detail.video_facts.open", () -> showReadOnlyVideoFacts(facts));
        }
        boolean useApiBase = facts != null && facts.needsApiBase();
        String savedAddress = videoAddressValue(facts, detail.manifest(), current);
        if (facts == null || !facts.isComplete() || declaration == null || declaration.video() == null
                || (current != null || !useApiBase) && savedAddress.isEmpty()) {
            String message = declaration == null ? "请在聊天中补齐接口资料后保存草稿。"
                    : "此视频接口没有可复用的 API 基础地址配置。请在聊天中核对提交路径后保存草稿。";
            text(body, message, "skills.detail.video_configuration_in_chat");
            if (advanced.getChildCount() != 0) addVideoAdvanced(advanced);
            return;
        }

        section(declaration.label(), "skills.detail.custom");
        EditText address = input(body, useApiBase ? "公网 HTTPS API 基础地址" : "公网 HTTPS API 地址",
                "skills.detail.custom.endpoint", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                2048, false);
        address.setText(savedAddress);
        String exactEndpoint = useApiBase ? "" : savedAddress;
        EditText bearer = serviceKey(current);
        Map<String, Supplier<String>> values = new LinkedHashMap<>();
        SkillManifest.ConfigField model = videoModelField(detail.manifest());
        if (model != null) {
            values.put(model.key(), manifestInput(body, model, detail.manifestValues().get(model.key())));
            ModelField picker = videoModelPicker(detail, model, values.get(model.key()));
            addModelLookup(body, () -> lookupVideoModels(detail, facts, address, bearer, picker, exactEndpoint));
        }
        for (SkillManifest.ConfigField field : detail.manifest().configFields()) {
            if (field.equals(model)) continue;
            Object value = detail.manifestValues().get(field.key());
            LinearLayout parent = field.required() && value == null ? body : advanced;
            values.put(field.key(), manifestInput(parent, field, value));
        }
        String[] lastAddress = {resolvedVideoAddress(facts, savedAddress)};
        watchModelInputs(bearer, address, null, () -> {
            String candidate = resolvedVideoAddress(facts, fieldValue(address));
            if (candidate.isEmpty()) return;
            String previous = lastAddress[0];
            lastAddress[0] = candidate;
            if (!previous.isEmpty() && !candidate.equals(previous) && bearer.length() != 0) {
                bearer.getText().clear();
                (modelLookupStatus == null ? status : modelLookupStatus).setText(ModuleUiStrings.get(context,
                        R.string.skills_video_api_address_changed, "API 地址已变化，请核对密钥"));
            }
        });
        saveServiceButton(detail, address, bearer, values, useApiBase, exactEndpoint);
        removeServiceKey(advanced, detail, bearer);
        addVideoAdvanced(advanced);
    }

    private static String videoAddressValue(SkillVideoFacts facts, SkillManifest manifest,
                                            SkillStore.CustomConfiguration current) {
        if (facts == null) return "";
        if (facts.needsApiBase()) {
            return facts.apiBaseForEndpoint(current == null ? proposedEndpoint(manifest) : current.endpoint());
        }
        if (current != null) return current.endpoint();
        for (SkillVideoFacts.FormField field : facts.formFields()) {
            if (field.id().equals("submitEndpoint")) return field.value();
        }
        return "";
    }

    private static SkillManifest.ConfigField videoModelField(SkillManifest manifest) {
        String source = manifest.http().request().get("model");
        if (source == null || !source.startsWith("config.")) return null;
        for (SkillManifest.ConfigField field : manifest.configFields()) {
            if (source.equals("config." + field.key())
                    && (field.type() == SkillManifest.FieldType.TEXT || field.type() == SkillManifest.FieldType.ENUM)) return field;
        }
        return null;
    }

    private ModelField videoModelPicker(SkillStore.Detail detail, SkillManifest.ConfigField field, Supplier<String> value) {
        View control = body.findViewWithTag("skills.detail.config." + field.key());
        Consumer<String> apply = model -> {
            if (control instanceof EditText edit) edit.setText(model);
            else if (control instanceof Spinner spinner) spinner.setSelection(field.options().indexOf(model) + 1);
        };
        Predicate<String> compatible = model -> {
            try { return detail.manifest().enteredValues(Map.of(field.key(), model)).containsKey(field.key()); }
            catch (IllegalArgumentException invalid) { return false; }
        };
        String documentedDefault = field.defaultValue() instanceof String name ? name : "";
        return new ModelField(value, apply, compatible, documentedDefault, field.type() == SkillManifest.FieldType.ENUM);
    }

    private void lookupVideoModels(SkillStore.Detail detail, SkillVideoFacts facts, EditText address, EditText key,
                                   ModelField model, String exactEndpoint) {
        DouAiLog.Operation trace = DouAiLog.begin("model_settings", "video_model_catalogue");
        String entered = fieldValue(address);
        String endpoint = resolvedVideoAddress(facts, entered);
        if (endpoint.isEmpty() || !exactEndpoint.isEmpty() && !exactEndpoint.equals(endpoint)) {
            modelLookupStatus.setText(facts.needsApiBase() ? "请填写 HTTPS API 基础地址" : "请使用已核对的提交地址");
            trace.finish(DouAiLog.Outcome.FAILED, "invalid_video_address", "request_queued=false");
            return;
        }
        String bearer = key.getText().toString();
        queueModelLookup(trace, key, () -> currentDetail(detail), model,
                () -> videoDiscovery.fetch(detail, entered, bearer));
    }

    private boolean currentDetail(SkillStore.Detail expected) {
        try { return expected.deleteTarget().equals(store.detail(name).deleteTarget()); }
        catch (RuntimeException unavailable) { return false; }
    }

    private static String resolvedVideoAddress(SkillVideoFacts facts, String entered) {
        try {
            return facts != null && facts.needsApiBase() ? facts.resolveApiBase(entered)
                    : SkillCustomService.validateEndpoint(entered);
        } catch (IllegalArgumentException | SkillCustomService.Failure invalid) {
            return "";
        }
    }

    private void addVideoAdvanced(LinearLayout advanced) {
        advanced.setTag("skills.detail.video_advanced.content");
        advanced.setVisibility(View.GONE);
        button(body, "高级配置", "skills.detail.video_advanced", () -> advanced.setVisibility(
                advanced.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        body.addView(advanced, matchWrap());
    }

    private void describeContract(LinearLayout parent, SkillStore.Detail detail) {
        SkillManifest manifest = detail.manifest();
        SkillHttpDeclaration declaration = manifest.http();
        text(parent, "执行方式 · " + manifest.capability().executionMode().label(), "skills.detail.execution_mode");
        text(parent, "输出类型 · " + detail.outputType().label(), "skills.detail.output_type");
        String request = declaration == null ? SkillCustomService.REQUEST_FIELD : String.join("、", declaration.request().keySet());
        String response = declaration == null ? SkillCustomService.RESPONSE_FIELD : declaration.responseField();
        text(parent, "请求 POST JSON，发送字段 " + request + "，读取响应字段 " + response + "。",
                "skills.detail.custom.contract");
        if (declaration == null || declaration.video() == null) return;
        SkillHttpDeclaration.Video video = declaration.video();
        String routes = "查询 " + videoStatusRoute(video)
                + (video.downloadPath().isEmpty() ? "\n成片链接字段 " + video.resultUrlField() : "\n下载 GET " + video.downloadPath());
        if (video.canCancel()) routes += "\n取消 " + video.cancelMethod() + " " + video.cancelPath();
        text(parent, routes, "skills.detail.custom.video_routes");
    }

    private static String proposedEndpoint(SkillManifest manifest) {
        String proposed = manifest.servicePrefill().endpoint();
        return proposed.isEmpty() && manifest.http() != null ? manifest.http().suggestedEndpoint() : proposed;
    }

    private EditText serviceKey(SkillStore.CustomConfiguration current) {
        String label = current != null && current.hasCredential() ? "API 密钥（同一地址留空时保留）" : "API 密钥（可选）";
        keyInput = input(body, label, "skills.detail.custom.credential",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, 2048, true);
        return keyInput;
    }

    private void saveServiceButton(SkillStore.Detail detail, EditText endpoint, EditText bearer,
                                   Map<String, Supplier<String>> values, boolean useApiBase, String exactEndpoint) {
        button(body, "保存服务配置", "skills.detail.custom.save", () -> {
            cancelModelLookup("configuration_save_started");
            String entered = fieldValue(endpoint);
            if (!exactEndpoint.isEmpty() && !exactEndpoint.equals(resolvedVideoAddress(null, entered))) {
                status.setText("请使用已核对的提交地址；更换地址请在聊天中修改接口");
                return;
            }
            saveCustomConfiguration(detail, entered, bearer, false, enteredValues(values), useApiBase);
        });
    }

    private void removeServiceKey(LinearLayout parent, SkillStore.Detail detail, EditText bearer) {
        SkillStore.CustomConfiguration current = detail.customConfiguration();
        if (current == null || !current.hasCredential()) return;
        button(parent, "移除此技能密钥", "skills.detail.custom.remove_credential", () -> review("移除服务密钥",
                "确认后生成待启用配置。", "移除密钥",
                () -> saveCustomConfiguration(detail, current.endpoint(), bearer, true, null, false)));
    }

    private static String videoFactSourceLabel(SkillVideoFacts.FormField field) {
        if (field.value().isEmpty()) return field.required() ? "待填写" : "选填";
        return "来源 · " + field.source().label();
    }

    private static String videoFactLabel(SkillVideoFacts.FormField field) {
        return field.label() + (field.required() ? "（必填）" : "");
    }

    private static String videoMissingLabel(SkillVideoFacts facts) {
        List<String> missing = facts.missing();
        return missing.isEmpty() ? "接口字段已齐全" : "待补充 " + String.join("、", missing);
    }

    static String videoStatusRoute(SkillHttpDeclaration.Video video) {
        if (!video.statusUrlField().isEmpty()) return "GET 提交响应中的同源地址（" + video.statusUrlField() + "）";
        if (!video.statusPostPath().isEmpty()) {
            return "POST " + video.statusPostPath() + "（JSON 字段 " + video.statusPostIdField() + "）";
        }
        return "GET " + video.statusPath();
    }

    private void saveCustomConfiguration(SkillStore.Detail detail, String endpoint, EditText bearer, boolean clear,
                                         Map<String, String> values, boolean useApiBase) {
        try {
            String credential = clear ? "" : fieldValue(bearer);
            SkillStore.Detail saved = useApiBase
                    ? store.configureCustomServiceBase(detail, endpoint, credential, clear, values)
                    : store.configureCustomService(detail, endpoint, credential, clear, values);
            bearer.setText("");
            String status = saved.canReplace() ? "服务配置已保存，确认启用新版后生效"
                    : saved.pending() ? "草稿已保存，请补齐设置" : "服务配置已保存，可在聊天中确认调用";
            render(saved, status);
            if (onChanged != null) onChanged.run();
        } catch (SkillCustomService.Failure invalid) {
            status.setText(invalid.getMessage());
        } catch (SkillManifest.Invalid invalid) {
            status.setText(invalid.getMessage());
        } catch (IllegalArgumentException invalid) {
            status.setText(useApiBase ? "请输入与接口资料同源的公网 HTTPS 主机和可选端口"
                    : "请输入公网 HTTPS 完整接口地址，地址中请勿包含凭据、查询参数或片段");
        } catch (Exception unavailable) {
            status.setText("无法保存服务配置，请重新打开技能详情");
        }
    }

    private Supplier<String> manifestInput(LinearLayout parent, SkillManifest.ConfigField field, Object value) {
        String tag = "skills.detail.config." + field.key();
        String label = field.label() + (field.required() ? "（必填）" : "");
        String current = value == null ? "" : value instanceof BigDecimal decimal
                ? decimal.toPlainString() : value.toString();
        if (field.description() != null && !field.description().isEmpty()) {
            text(parent, field.description(), tag + ".description");
        }
        return switch (field.type()) {
            case BOOLEAN -> manifestChoice(parent, label, tag, current, List.of("true", "false"), List.of("是", "否"));
            case ENUM -> manifestChoice(parent, label, tag, current, field.options(), field.options());
            case TEXT, MULTILINE, INTEGER, DECIMAL -> {
                if (!miuix) text(parent, label, tag + ".label");
                int type = switch (field.type()) {
                    case MULTILINE -> InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE;
                    case INTEGER -> InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED;
                    case DECIMAL -> InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                            | InputType.TYPE_NUMBER_FLAG_DECIMAL;
                    default -> InputType.TYPE_CLASS_TEXT;
                };
                Integer limit = field.maxLength();
                EditText input = input(parent, label, tag, type, limit == null || limit <= 0 ? 16000 : limit, false);
                input.setText(current);
                yield () -> input.getText().toString();
            }
        };
    }

    private Supplier<String> manifestChoice(LinearLayout parent, String label, String tag, String current,
                                            List<String> values, List<String> labels) {
        text(parent, label, tag + ".label");
        Spinner spinner = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        spinner.setTag(tag);
        spinner.setContentDescription(label);
        List<String> choices = new ArrayList<>();
        choices.add("未填写");
        choices.addAll(labels);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, choices);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(values.indexOf(current) + 1);
        parent.addView(MiuixFormControls.viewFor(spinner), matchWrap());
        return () -> {
            int position = spinner.getSelectedItemPosition();
            return position <= 0 ? "" : values.get(position - 1);
        };
    }

    private void saveManifestConfiguration(SkillStore.Detail expected, Map<String, String> entered) {
        SkillStore.Detail updated;
        try { updated = store.saveManifestValues(expected, entered); }
        catch (IllegalStateException stale) {
            reload("配置未保存，请核对当前技能后再试");
            if (onChanged != null) onChanged.run();
            return;
        } catch (SkillManifest.Invalid invalid) {
            status.setText(invalid.getMessage());
            return;
        } catch (IllegalArgumentException invalid) {
            status.setText("配置未保存，请检查字段值和范围");
            return;
        } catch (Exception unavailable) {
            status.setText("暂时无法保存技能配置");
            return;
        }
        render(updated, updated.pending() ? "配置已保存为草稿" : "配置已保存");
        if (onChanged != null) onChanged.run();
    }

    private void reviewDelete(SkillStore.DeleteTarget expected) {
        String scope = expected.versionCount() == 0 ? "本机草稿"
                : expected.hasDraft() ? "本机配置和当前草稿" : "本机配置";
        review("删除技能", "将删除 /" + expected.name() + " 的" + scope + "。",
                "确认删除", () -> delete(expected));
    }

    private void delete(SkillStore.DeleteTarget expected) {
        try { store.delete(expected); }
        catch (Exception failure) {
            reload("删除未完成，请核对当前版本和草稿后再试");
            return;
        }
        render(null, "已删除 /" + expected.name());
        if (onChanged != null) onChanged.run();
    }

    private void pendingActions(SkillStore.Detail detail, boolean showImageConfiguration) {
        boolean imageAdapter = detail.outputType() == SkillResult.Type.IMAGE
                && (detail.manifest() == null || !detail.manifest().usesHttp());
        if (imageAdapter) {
            if (detail.canReplace() && !showImageConfiguration) {
                button(body, "配置图片服务", "skills.detail.configure", () -> render(detail, "", true));
            } else {
                connectionForm(detail);
            }
        }
        if (detail.canReplace()) {
            button(body, "启用新版", "skills.detail.replace", () -> review("确认更新技能",
                    "将以草稿替换 /" + name + " 的当前配置 v" + detail.previousVersion() + "。"
                            + (detail.service() == null ? "" : "\n\n" + target(detail.service()))
                            + (detail.customConfiguration() == null ? "" : "\n\nPOST " + detail.customConfiguration().endpoint()),
                    "启用新版", () -> apply(() -> store.activateReplacement(detail),
                            "技能已更新", "启用未完成，请重新打开详情核对配置")));
        } else if (imageAdapter || detail.manifest() == null) {
            button(body, "重试连接", "skills.detail.retry", () -> apply(
                    () -> store.retryConfiguration(name, detail.draftId()), "已检查连接状态", "重试失败，请核对配置后再试"));
        }
    }

    private void connectionForm(SkillStore.Detail detail) {
        SkillServices.Service saved = detail.service();
        boolean savedMock = saved != null && saved.adapter() == SkillServices.Adapter.MOCK_IMAGE;
        boolean savedOpenAi = saved != null && saved.adapter() == SkillServices.Adapter.OPENAI_IMAGES;
        Window window = dialog.getWindow();
        if (window != null) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE);
        Spinner adapter = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        adapter.setTag("skills.detail.adapter");
        adapter.setContentDescription("图片服务");
        ArrayAdapter<String> options = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item,
                new String[]{"OpenAI Images 兼容服务", "本地模拟图片"});
        options.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        adapter.setAdapter(options);
        adapter.setSelection(savedMock ? 1 : 0);
        if (!miuix) text(body, "图片服务", null);
        body.addView(MiuixFormControls.viewFor(adapter), matchWrap());

        LinearLayout fields = column();
        fields.setTag("skills.detail.openai");
        body.addView(fields, matchWrap());
        SkillManifest.ServicePrefill prefill = detail.manifest() == null ? new SkillManifest.ServicePrefill("", "")
                : detail.manifest().servicePrefill();
        String savedEndpoint = savedOpenAi && !saved.endpoint().isEmpty() ? saved.endpoint() : prefill.endpoint();
        SkillImageProviderAddress.Form savedAddress = SkillImageProviderAddress.fromSaved(savedEndpoint);
        AiProviderBase suggested = savedEndpoint.isEmpty()
                ? availableAiBases().stream().filter(AiProviderBase::selected).findFirst().orElse(null) : null;
        EditText endpoint = input(fields, "API 基础地址", "skills.detail.endpoint",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        endpoint.setText(suggested == null ? savedAddress.address() : suggested.address());
        TextView aiBaseSource = text(fields, "", "skills.detail.ai_base_source");
        showAiBaseSource(aiBaseSource, suggested);
        String completeLabel = "按完整生图接口填写";
        CompoundButton complete = miuix ? MiuixFormControls.checkbox(context, completeLabel, savedAddress.complete())
                : new CheckBox(context);
        complete.setText(completeLabel);
        complete.setTag("skills.detail.endpoint_complete");
        complete.setChecked(savedAddress.complete());
        fields.addView(MiuixFormControls.viewFor(complete), matchWrap());
        EditText modelsEndpoint = input(fields, "模型列表 HTTPS 地址（可选）", "skills.detail.models_endpoint",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, 2048, false);
        EditText model = input(fields, "模型名称", "skills.detail.model", InputType.TYPE_CLASS_TEXT, 256, false);
        EditText cost = input(fields, "每次调用费用单位", "skills.detail.cost", InputType.TYPE_CLASS_NUMBER, 7, false);
        boolean credentialSaved = detail.fields().stream().anyMatch(field ->
                "credential".equals(field.id()) && field.state() == SkillStore.FieldState.FILLED);
        String keyLabel = credentialSaved ? "API 密钥（已保存，地址不变可留空）" : "API 密钥";
        EditText key = input(fields, keyLabel, "skills.detail.key",
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD, MAX_KEY_ENTRY, true);
        key.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        keyInput = key;
        if (savedOpenAi) {
            modelsEndpoint.setText(saved.modelsEndpoint());
            model.setText(saved.modelName());
        } else if (!prefill.model().isEmpty()) {
            model.setText(prefill.model());
        }
        cost.setText(String.format(Locale.ROOT, "%d", savedOpenAi ? saved.costUnits() : 0));
        fields.setVisibility(savedMock ? View.GONE : View.VISIBLE);
        adapter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                if (keyInput != key) return;
                int visibility = position == 1 ? View.GONE : View.VISIBLE;
                if (fields.getVisibility() != visibility) cancelModelLookup("adapter_changed");
                fields.setVisibility(visibility);
                if (position == 1) key.getText().clear();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        button(fields, "从 AI 选择基础地址", "skills.detail.use_ai_base", () -> {
            if (adapter.getSelectedItemPosition() != 0 || keyInput != key) return;
            chooseAiBase(provider -> {
                if (active() && keyInput == key && adapter.getSelectedItemPosition() == 0) {
                    applyAiBase(provider, endpoint, complete, modelsEndpoint, key, aiBaseSource);
                }
            });
        });
        addModelLookup(fields, () -> lookupModels(detail, adapter, endpoint, complete, modelsEndpoint, key, model));
        String[] lastAddress = {resolvedGenerationAddress(endpoint, complete)};
        Runnable addressChanged = () -> {
            String address = resolvedGenerationAddress(endpoint, complete);
            if (address != null && !address.isEmpty()) {
                if (lastAddress[0] != null && !lastAddress[0].isEmpty() && !lastAddress[0].equals(address)
                        && key.length() != 0) {
                    key.getText().clear();
                    modelLookupStatus.setText("生图地址已变化，请填写这个地址的图片密钥");
                }
                lastAddress[0] = address;
            }
            showAiBaseSource(aiBaseSource, null);
        };
        watchModelInputs(key, endpoint, modelsEndpoint, addressChanged);
        complete.setOnCheckedChangeListener((view, checked) -> {
            if (keyInput == key) {
                cancelModelLookup("address_mode_changed");
                addressChanged.run();
            }
        });

        button(body, "保存配置", "skills.detail.save", () -> {
            cancelModelLookup("configuration_save_started");
            boolean mock = adapter.getSelectedItemPosition() == 1;
            SkillServices.Adapter selected = mock ? SkillServices.Adapter.MOCK_IMAGE : SkillServices.Adapter.OPENAI_IMAGES;
            int units;
            try { units = mock ? 0 : Integer.parseInt(fieldValue(cost)); }
            catch (NumberFormatException invalid) { status.setText("请输入有效的每次调用费用单位"); return; }
            String generation = mock ? "" : generationAddress(endpoint, complete);
            if (generation == null) return;
            String bearer = mock ? "" : key.getText().toString();
            saveConfiguration(detail, selected, generation,
                    mock ? "" : fieldValue(modelsEndpoint), mock ? "" : fieldValue(model), units, bearer);
        });
        if (detail.canAuthorize() && saved != null) {
            button(body, "授权已保存的服务", "skills.detail.authorize", () -> {
                boolean mockSelected = adapter.getSelectedItemPosition() == 1;
                String generation = mockSelected ? "" : generationAddress(endpoint, complete);
                if (generation == null) return;
                boolean changed = (savedMock != mockSelected) || (savedOpenAi && (
                        !saved.endpoint().equals(generation)
                                || !saved.modelsEndpoint().equals(fieldValue(modelsEndpoint))
                                || !saved.modelName().equals(fieldValue(model))
                                || !Integer.toString(saved.costUnits()).equals(fieldValue(cost))
                                || key.length() != 0));
                if (changed) { status.setText("请先保存当前配置"); return; }
                review("授权此技能的服务", target(saved), "确认授权", () -> apply(
                        () -> store.authorizeConfiguration(name, detail.draftId(), saved), "授权已保存",
                        "授权或启用失败，请重新核对服务和费用"));
            });
        }
    }

    private record AiProviderBase(String label, String address, boolean selected) { }

    private List<AiProviderBase> availableAiBases() {
        AiModelSettings models;
        try { models = AiModelSettings.read(new HostSettingsStore(context)); }
        catch (RuntimeException unavailable) { return List.of(); }
        List<AiProviderBase> bases = new ArrayList<>();
        for (AiModelConfig config : models.configs) {
            String base = SkillImageProviderAddress.fromAiProvider(config);
            if (!base.isEmpty()) bases.add(new AiProviderBase(config.name, base, config.id.equals(models.activeId)));
        }
        return bases;
    }

    private void chooseAiBase(Consumer<AiProviderBase> selected) {
        cancelModelLookup("ai_provider_picker_opened");
        List<AiProviderBase> bases = availableAiBases();
        if (bases.isEmpty()) {
            modelLookupStatus.setText(ModuleUiStrings.get(context, R.string.skills_image_no_ai_base,
                    "暂无可选的 HTTPS AI 基础地址，可手动填写"));
            return;
        }
        String[] labels = bases.stream().map(base -> base.label() + " · " + base.address()).toArray(String[]::new);
        showChild(builder("选择 AI 基础地址").setItems(labels, (child, which) -> {
            if (active() && confirmation == child) selected.accept(bases.get(which));
        }).setNegativeButton("取消", null).create());
    }

    private void applyAiBase(AiProviderBase provider, EditText endpoint, CompoundButton complete,
                             EditText modelsEndpoint, EditText key, TextView source) {
        String previous = resolvedGenerationAddress(endpoint, complete);
        String generation = SkillImageProviderAddress.resolve(provider.address(), false);
        if (!generation.equals(previous)) key.getText().clear();
        complete.setChecked(false);
        endpoint.setText(provider.address());
        showAiBaseSource(source, provider);
        try { SkillImageModelCatalog.validateOverride(generation, fieldValue(modelsEndpoint)); }
        catch (SkillImageModelCatalog.Failure invalid) {
            modelLookupStatus.setText(invalid.code() == SkillImageModelCatalog.Code.DIFFERENT_ORIGIN
                    ? "保留的模型列表地址与新基础地址不同源，请修改或清空后再查询或保存"
                    : invalid.getMessage());
        }
    }

    private static void showAiBaseSource(TextView source, AiProviderBase provider) {
        source.setText(provider == null ? "" : "基础地址来源 · " + provider.label());
        source.setVisibility(provider == null ? View.GONE : View.VISIBLE);
    }

    private static String resolvedGenerationAddress(EditText endpoint, CompoundButton complete) {
        try { return SkillImageProviderAddress.resolve(fieldValue(endpoint), complete.isChecked()); }
        catch (IllegalArgumentException invalid) { return null; }
    }

    private String generationAddress(EditText address, CompoundButton complete) {
        return generationAddress(address, complete, status);
    }

    private String generationAddress(EditText address, CompoundButton complete, TextView feedback) {
        try { return SkillImageProviderAddress.resolve(fieldValue(address), complete.isChecked()); }
        catch (IllegalArgumentException invalid) {
            feedback.setText(invalid.getMessage());
            return null;
        }
    }

    private void saveConfiguration(SkillStore.Detail detail, SkillServices.Adapter adapter, String endpoint,
                                   String modelsEndpoint, String modelName, int costUnits, String bearer) {
        SkillStore.Detail updated;
        try {
            updated = store.saveConfiguration(name, detail.draftId(), adapter, endpoint, modelsEndpoint,
                    modelName, costUnits, bearer);
        } catch (SkillServices.Failure rejected) {
            status.setText(rejected.getMessage());
            return;
        } catch (IllegalStateException stale) {
            status.setText("草稿状态已变化，请重新打开技能配置");
            return;
        } catch (Exception unavailable) {
            status.setText("暂时无法保存图片服务配置");
            return;
        }
        render(updated, savedConfigurationStatus(updated));
        if (onChanged != null) onChanged.run();
    }

    private static String savedConfigurationStatus(SkillStore.Detail detail) {
        for (SkillStore.Field field : detail.fields()) {
            if (field.required() && field.state() == SkillStore.FieldState.MISSING) {
                if ("model".equals(field.id())) return "配置已保存，请选择或填写模型名称";
                if ("endpoint".equals(field.id())) return "配置已保存，请填写 HTTPS API 基础地址";
                if ("credential".equals(field.id())) return "配置已保存，请填写 API 密钥";
            }
        }
        return detail.canAuthorize() ? "配置已保存，请授权已保存的服务" : "配置已保存";
    }

    private void addModelLookup(LinearLayout parent, Runnable action) {
        modelLookupButton = button(parent, "拉取模型", "skills.detail.fetch_models", action);
        modelLookupStatus = text(parent, "", "skills.detail.model_status");
        modelLookupStatus.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
    }

    private void watchModelInputs(EditText key, EditText endpoint, EditText modelsEndpoint, Runnable addressChanged) {
        for (EditText field : new EditText[]{endpoint, modelsEndpoint, key}) {
            if (field == null) continue;
            field.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                    if (keyInput != key) return;
                    cancelModelLookup("request_inputs_changed");
                    if (field == endpoint) addressChanged.run();
                }
                @Override public void afterTextChanged(Editable text) { }
            });
        }
    }

    private void lookupModels(SkillStore.Detail detail, Spinner adapter, EditText endpoint, CompoundButton complete,
                              EditText modelsEndpoint, EditText key, EditText model) {
        DouAiLog.Operation trace = DouAiLog.begin("model_settings", "image_model_catalogue");
        if (adapter.getSelectedItemPosition() != 0 || keyInput != key) {
            trace.finish(DouAiLog.Outcome.SKIPPED, "inactive_image_form", "request_queued=false");
            return;
        }
        String generation = generationAddress(endpoint, complete, modelLookupStatus);
        if (generation == null || generation.isEmpty()) {
            if (generation != null) modelLookupStatus.setText(ModuleUiStrings.get(context,
                    R.string.skills_image_enter_api_base, "请填写 HTTPS API 基础地址"));
            trace.finish(DouAiLog.Outcome.FAILED, "invalid_generation_address", "request_queued=false");
            return;
        }
        String catalogue = fieldValue(modelsEndpoint);
        String bearer = key.getText().toString();
        ModelField target = new ModelField(() -> fieldValue(model), model::setText, name -> true, "", false);
        queueModelLookup(trace, key, () -> currentDraft(detail.draftId()), target,
                () -> discovery.fetch(name, detail.draftId(), generation, catalogue, bearer));
    }

    private record ModelField(Supplier<String> value, Consumer<String> apply, Predicate<String> compatible,
                              String documentedDefault, boolean selectMissingSingle) { }

    @FunctionalInterface
    private interface ModelFetch { List<String> fetch() throws Exception; }

    private void queueModelLookup(DouAiLog.Operation trace, EditText key, BooleanSupplier current, ModelField model,
                                  ModelFetch fetch) {
        if (!active() || keyInput != key || modelLookup != null) {
            trace.finish(DouAiLog.Outcome.SKIPPED, "lookup_unavailable", "request_queued=false");
            return;
        }
        long ticket = ++modelLookupGeneration;
        modelLookupStatus.setText(FETCHING_MODELS);
        modelLookupButton.setEnabled(false);
        modelLookupTrace = trace;
        trace.stage("admission", "request_method=get;discovery_only=true");
        trace.stage("queue", "submission_attempted=true");
        try {
            modelLookup = discoveryWorker.submit(trace.wrap(() -> {
                trace.stage("worker_started", "discovery_only=true");
                try {
                    List<String> models = fetch.fetch();
                    main.post(trace.wrap(() -> finishModelLookup(trace, ticket, key, current, model, models, null)));
                } catch (Exception failure) {
                    LookupFailure rejected = lookupFailure(failure);
                    main.post(trace.wrap(() -> finishModelLookup(trace, ticket, key, current, model, List.of(), rejected)));
                }
            }));
        } catch (RejectedExecutionException unavailable) {
            modelLookupTrace = null;
            modelLookupButton.setEnabled(true);
            modelLookupStatus.setText("暂时无法启动模型查询，请重试");
            trace.finish(DouAiLog.Outcome.FAILED, "worker_rejected", "request_queued=false");
        }
    }

    private record LookupFailure(String message, DouAiLog.Outcome outcome, String reason) { }

    private static LookupFailure lookupFailure(Exception failure) {
        if (failure instanceof SkillImageModelCatalog.Failure catalogue) {
            DouAiLog.Outcome outcome = switch (catalogue.code()) {
                case CANCELLED -> DouAiLog.Outcome.CANCELLED;
                case DEADLINE -> DouAiLog.Outcome.TIMED_OUT;
                default -> DouAiLog.Outcome.FAILED;
            };
            return new LookupFailure(catalogue.getMessage(), outcome,
                    "catalogue_" + catalogue.code().name().toLowerCase(Locale.ROOT));
        }
        if (failure instanceof SkillServices.Failure service) {
            return new LookupFailure(service.getMessage(), DouAiLog.Outcome.FAILED,
                    "service_" + service.code().name().toLowerCase(Locale.ROOT));
        }
        return new LookupFailure("暂时无法读取模型列表，可以手动填写模型名称",
                DouAiLog.Outcome.FAILED, "lookup_unavailable");
    }

    private void finishModelLookup(DouAiLog.Operation trace, long ticket, EditText key, BooleanSupplier current,
                                   ModelField model, List<String> models, LookupFailure failure) {
        if (modelLookupTrace != trace) return;
        modelLookupTrace = null;
        modelLookup = null;
        if (!currentModelLookup(ticket, key)) {
            trace.finish(DouAiLog.Outcome.CANCELLED, active() ? "stale_form" : "dialog_closed", "options_presented=false");
            return;
        }
        modelLookupButton.setEnabled(true);
        if (!current.getAsBoolean()) {
            modelLookupStatus.setText("技能配置已变化，请重新打开详情");
            trace.finish(DouAiLog.Outcome.CANCELLED, "stale_draft", "options_presented=false");
            return;
        }
        if (failure != null) {
            modelLookupStatus.setText(failure.message());
            trace.finish(failure.outcome(), failure.reason(), "options_presented=false");
            return;
        }
        List<String> available = new ArrayList<>();
        for (String name : models) {
            if (model.compatible().test(name) && !available.contains(name)) available.add(name);
        }
        if (available.isEmpty()) {
            modelLookupStatus.setText("没有可选模型，可以手动填写模型名称");
            trace.finish(DouAiLog.Outcome.FAILED, "catalogue_empty", "options_presented=false");
            return;
        }
        if (model.selectMissingSingle() && available.size() == 1
                && model.documentedDefault().isEmpty() && model.value().get().isBlank()) {
            model.apply().accept(available.get(0));
            modelLookupStatus.setText("已填入可用模型，请保存配置");
            trace.finish(DouAiLog.Outcome.SUCCESS, "single_model_selected", "model_count=1");
            return;
        }
        try {
            AlertDialog choices = builder("选择模型").setItems(available.toArray(new String[0]), (ignored, which) -> {
                        if (!currentModelLookup(ticket, key) || !current.getAsBoolean()) return;
                        model.apply().accept(available.get(which));
                        modelLookupStatus.setText("已选择模型，请保存配置");
                    }).setNegativeButton("取消", null).create();
            showChild(choices);
            modelLookupStatus.setText(ModuleUiStrings.get(context, R.string.skills_models_found,
                    "已获取 %1$d 个模型", models.size()));
            trace.finish(DouAiLog.Outcome.SUCCESS, "options_presented", "model_count=" + models.size());
        } catch (RuntimeException | LinkageError unavailable) {
            modelLookupStatus.setText("无法打开模型选择，可以手动填写模型名称");
            trace.finish(DouAiLog.Outcome.FAILED, "chooser_unavailable", "options_presented=false");
        }
    }

    private boolean currentModelLookup(long ticket, EditText key) {
        return active() && ticket == modelLookupGeneration && keyInput == key;
    }

    private boolean currentDraft(String draftId) {
        try { return draftId.equals(store.detail(name).draftId()); }
        catch (RuntimeException unavailable) { return false; }
    }

    private void cancelModelLookup(String reason) {
        modelLookupGeneration++;
        boolean pending = modelLookup != null;
        if (pending) {
            modelLookup.cancel(true);
            modelLookup = null;
            if (modelLookupTrace != null) {
                modelLookupTrace.finish(DouAiLog.Outcome.CANCELLED, reason, "options_presented=false");
                modelLookupTrace = null;
            }
            if (modelLookupButton != null) modelLookupButton.setEnabled(true);
        }
        if (modelLookupStatus != null) {
            String feedback = pending ? switch (reason) {
                case "request_inputs_changed", "adapter_changed", "address_mode_changed" -> "查询条件已变化，请重新获取模型列表";
                default -> "";
            } : "";
            modelLookupStatus.setText(feedback);
        }
    }

    private static String fieldValue(EditText field) { return field.getText().toString().trim(); }

    private void installedActions(SkillStore.Detail detail) {
        button(body, detail.installedEnabled() ? "停用技能" : "启用技能", "skills.detail.toggle", () -> apply(() -> {
            store.setSkillEnabled(name, !detail.installedEnabled());
            return store.detail(name);
        }, "状态已更新", "暂时无法更新技能状态"));
    }

    @FunctionalInterface private interface Operation { SkillStore.Detail run() throws Exception; }

    private void apply(Operation operation, String feedback, String failure) {
        if (!active()) return;
        try {
            SkillStore.Detail updated = operation.run();
            render(updated, feedback);
        } catch (Exception unavailable) {
            status.setText(failure);
            return;
        }
        if (onChanged != null) onChanged.run();
    }

    private void review(String title, String message, String label, Runnable action) {
        if (!active()) return;
        cancelModelLookup("review_opened");
        AlertDialog child = builder(title).setMessage(message).setNegativeButton("取消", null)
                .setPositiveButton(label, (ignored, which) -> { if (active()) action.run(); }).create();
        showChild(child);
    }

    private void showChild(AlertDialog child) {
        if (confirmation != null) confirmation.dismiss();
        confirmation = child;
        if (dialog.getOwnerActivity() != null) child.setOwnerActivity(dialog.getOwnerActivity());
        child.setOnDismissListener(ignored -> { if (confirmation == child) confirmation = null; });
        child.show();
    }

    private boolean active() { return !closed && dialog.isShowing(); }

    private static String fieldState(SkillStore.Field field) {
        switch (field.state()) {
            case FILLED: return "已填写";
            case NEEDS_AUTHORIZATION: return "待授权";
            default: return field.required() ? "待填写" : "未填写";
        }
    }

    private static String outputLabel(SkillResult.Type output) {
        return output.label() + "输出";
    }

    private static String target(SkillServices.Service service) {
        if (service.adapter() == SkillServices.Adapter.MOCK_IMAGE) return service.label() + "\n本地模拟 · 无费用";
        return service.label() + "\n目标 " + service.endpoint() + "\n模型 " + service.modelName()
                + "\n每次调用最多 " + service.costUnits() + " 费用单位";
    }

    private void clearKey() {
        EditText previous = keyInput;
        keyInput = null;
        if (previous != null) previous.getText().clear();
    }

    private AlertDialog.Builder builder(String title) { return new MiuixDialogBuilder(context, miuix).setTitle(title); }

    private LinearLayout column() {
        LinearLayout panel = new LinearLayout(context);
        panel.setOrientation(LinearLayout.VERTICAL);
        return panel;
    }

    private TextView text(LinearLayout parent, String value, String tag) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTag(tag);
        view.setPadding(0, dp(4), 0, dp(4));
        parent.addView(view, matchWrap());
        return view;
    }

    private void section(String label, String tag) {
        TextView heading = text(body, label, tag);
        heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, dp(12), 0, dp(4));
    }

    private Button button(LinearLayout parent, String title, String tag, Runnable action) {
        Button view = miuix ? MiuixFormControls.button(context) : new Button(context);
        view.setText(title);
        view.setTag(tag);
        view.setOnClickListener(ignored -> { if (active()) action.run(); });
        parent.addView(MiuixFormControls.viewFor(view), matchWrap());
        return view;
    }

    private EditText input(LinearLayout parent, String label, String tag, int type, int limit, boolean secret) {
        EditText view = miuix ? MiuixFormControls.input(context, label, "", secret) : new EditText(context);
        view.setContentDescription(label);
        view.setHint(label);
        view.setTag(tag);
        view.setInputType(type);
        view.setMaxLines((type & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0 ? 6 : 1);
        view.setFilters(new InputFilter[]{new InputFilter.LengthFilter(limit)});
        View control = MiuixFormControls.viewFor(view);
        if (secret) {
            disableSecretStateTransfer(view);
            if (control != view) disableSecretStateTransfer(control);
        }
        parent.addView(control, matchWrap());
        return view;
    }

    private static void disableSecretStateTransfer(View view) {
        view.setSaveEnabled(false);
        view.setSaveFromParentEnabled(false);
        view.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS);
    }

    private static LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int size) { return Math.round(size * context.getResources().getDisplayMetrics().density); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelModelLookup("dialog_closed");
        if (discoveryWorker != null) discoveryWorker.shutdownNow();
        clearKey();
        if (confirmation != null) confirmation.dismiss();
        confirmation = null;
    }
}
