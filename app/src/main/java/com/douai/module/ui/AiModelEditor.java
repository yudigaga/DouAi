package com.douai.module.ui;

import com.douai.module.diagnostics.DouAiLog;

import android.app.AlertDialog;
import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.view.Gravity;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ImageView;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.config.AiModelConfig;
import com.douai.module.config.AiModelSettings;
import com.douai.module.config.AiProviderPresets;
import com.douai.module.config.SettingsStore;
import com.douai.module.feature.ChatCompletionClient;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;
import com.douai.module.ui.miuix.MiuixSettingsViews;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Callable;
import java.util.function.BiConsumer;

/** Owns one model draft, its persistence, child dialogs, and cancellable catalog request. */
final class AiModelEditor implements AutoCloseable {
    private static final int TEXT = Color.rgb(31, 39, 45);
    private static final int MUTED = Color.rgb(105, 115, 122);
    private final Context context;
    private final SettingsStore repository;
    private final boolean useMiuix;
    private final Runnable onDelete;
    private final Runnable onChanged;
    private final ExecutorService requests;
    private final List<Dialog> children = new ArrayList<>();
    private AlertDialog dialog;
    private ChatCompletionClient.ModelCatalogRequest modelRequest;
    private Future<?> modelFetch;
    private DouAiLog.Operation catalogTrace;
    private Future<?> avatarLoad;
    private HostImagePicker avatarPicker;
    private Bitmap avatarPreview;
    private boolean loadingAvatar;
    private boolean closed;
    private Runnable bindButtons;

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged) {
        this(context, repository, original, useMiuix, onChanged, Executors.newSingleThreadExecutor());
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests) {
        this(context, repository, original, useMiuix, onChanged, requests, false);
    }

    /** Compatibility entry point for contact editors; saving never selects a model. */
    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, boolean ignoredActivation) {
        this(context, repository, original, useMiuix, onChanged,
                Executors.newSingleThreadExecutor(), ignoredActivation, null);
    }

    AiModelEditor(Context context, SettingsStore repository, AiModelConfig original, boolean useMiuix,
                  Runnable onChanged, boolean ignoredActivation, Runnable onDelete) {
        this(context, repository, original, useMiuix, onChanged,
                Executors.newSingleThreadExecutor(), ignoredActivation, onDelete);
    }

    private AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests, boolean ignoredActivation) {
        this(context, repository, original, useMiuix, onChanged, requests, ignoredActivation, null);
    }

    private AiModelEditor(Context context, SettingsStore repository, AiModelConfig original,
                  boolean useMiuix, Runnable onChanged, ExecutorService requests, boolean ignoredActivation,
                  Runnable onDelete) {
        this.context = ModuleTheme.themedContext(context);
        this.repository = repository;
        this.useMiuix = useMiuix;
        this.onDelete = onDelete == null ? () -> {
            saveAiSettings(aiSettings().delete(original.id));
            onChanged.run();
        } : onDelete;
        this.onChanged = onChanged;
        this.requests = requests;
        build(original);
    }

    AlertDialog dialog() { return dialog; }

    void show(BiConsumer<Dialog, Runnable> presenter) {
        if (closed) return;
        try {
            presenter.accept(dialog, this::close);
            bindButtons.run();
        } catch (RuntimeException failure) {
            close();
            throw failure;
        }
    }

    @Override public void close() {
        if (closed) return;
        closed = true;
        cancelCatalog();
        if (avatarPicker != null) avatarPicker.close();
        avatarPicker = null;
        if (avatarLoad != null) avatarLoad.cancel(true);
        avatarLoad = null;
        for (Dialog child : new ArrayList<>(children)) child.dismiss();
        children.clear();
        requests.shutdownNow();
        if (dialog.isShowing()) dialog.dismiss();
        if (avatarPreview != null) avatarPreview.recycle();
        avatarPreview = null;
    }

    private void cancelCatalog() {
        if (catalogTrace != null) { catalogTrace.finish(DouAiLog.Outcome.CANCELLED, "model_catalog_cancelled", "dialog_closed_or_request_replaced"); catalogTrace = null; }
        ChatCompletionClient.ModelCatalogRequest pending = modelRequest;
        modelRequest = null;
        if (pending != null) pending.cancel();
        if (modelFetch != null) modelFetch.cancel(true);
        modelFetch = null;
    }

    private void showChild(Dialog child) {
        if (closed || !dialog.isShowing()) return;
        children.removeIf(existing -> !existing.isShowing());
        children.add(child);
        child.setOwnerActivity(dialog.getOwnerActivity());
        child.setOnDismissListener(ignored -> children.remove(child));
        child.show();
    }

    private AiModelSettings aiSettings() { return AiModelSettings.read(repository); }
    private void saveAiSettings(AiModelSettings settings) {
        DouAiLog.Operation trace = DouAiLog.begin("model_settings", "save_model_configuration");
        try {
            settings.save(repository);
            trace.finish(DouAiLog.Outcome.UNCONFIRMED, "settings_save_returned", "persistent_readback=not_performed;credentials_omitted=true");
        } catch (RuntimeException failure) {
            trace.error("save", failure); trace.finish(DouAiLog.Outcome.FAILED, "model_settings_failed", "credentials_omitted=true"); throw failure;
        }
    }

    private void build(AiModelConfig original) {
        LinearLayout form = form();
        String[] contactAvatar = {original.messageContactAvatar};
        ImageView avatar = new ImageView(context);
        avatar.setTag("ai.message_contact_avatar_preview");
        avatar.setContentDescription("联系人头像，点按更换");
        avatar.setScaleType(ImageView.ScaleType.CENTER_CROP);
        android.graphics.drawable.GradientDrawable avatarShape = new android.graphics.drawable.GradientDrawable();
        avatarShape.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        avatarShape.setColor(Color.rgb(246, 248, 248));
        avatar.setBackground(avatarShape);
        avatar.setClipToOutline(true);
        avatar.setFocusable(true);
        LinearLayout.LayoutParams avatarParams = new LinearLayout.LayoutParams(dp(72), dp(72));
        avatarParams.gravity = Gravity.CENTER_HORIZONTAL;
        avatarParams.topMargin = dp(8);
        avatarParams.bottomMargin = dp(12);
        form.addView(avatar, avatarParams);
        updateAvatarPreview(avatar, contactAvatar[0]);
        Button chooseAvatar = formButton();
        chooseAvatar.setText("选择联系人头像");
        chooseAvatar.setTag("ai.message_contact_avatar");
        Button resetAvatar = formButton();
        resetAvatar.setText("恢复默认头像");
        resetAvatar.setTag("ai.message_contact_avatar_reset");
        resetAvatar.setOnClickListener(ignored -> {
            if (loadingAvatar) { toast("正在处理头像，请稍候"); return; }
            contactAvatar[0] = "";
            updateAvatarPreview(avatar, "");
        });
        chooseAvatar.setOnClickListener(ignored -> {
            if (closed || loadingAvatar || avatarPicker != null) return;
            avatarPicker = HostImagePicker.launch(ownerActivity(), uri -> {
                avatarPicker = null;
                if (closed || uri == null) return;
                loadingAvatar = true;
                chooseAvatar.setEnabled(false);
                chooseAvatar.setText("正在处理头像…");
                avatarLoad = requests.submit(() -> {
                    try {
                        String selected = AiContactAvatar.fromSelection(context, uri);
                        form.post(() -> {
                            if (closed) return;
                            contactAvatar[0] = selected;
                            updateAvatarPreview(avatar, selected);
                            finishAvatarLoad(chooseAvatar);
                        });
                    } catch (Exception failure) {
                        form.post(() -> {
                            if (closed) return;
                            finishAvatarLoad(chooseAvatar);
                            toast(safeError(failure));
                        });
                    } catch (OutOfMemoryError failure) {
                        form.post(() -> {
                            if (closed) return;
                            finishAvatarLoad(chooseAvatar);
                            toast("头像图片过大，请选择较小图片");
                        });
                    }
                });
            });
            if (avatarPicker == null) toast("当前界面无法打开图片选择器");
        });
        avatar.setOnClickListener(ignored -> {
            if (loadingAvatar) { toast("正在处理头像，请稍候"); return; }
            showChild(new MiuixDialogBuilder(context, useMiuix).setTitle("联系人头像")
                    .setItems(new String[]{"选择图片", "恢复默认"}, (child, which) -> {
                        if (which == 0) chooseAvatar.performClick();
                        else resetAvatar.performClick();
                    }).setNegativeButton("取消", null).create());
        });
        EditText name = formInput(form, "配置名称", original.name, false);
        EditText contactName = formInput(form, "联系人名字（留空使用配置名称）", original.messageContactName, false);
        contactName.setSingleLine(true);
        contactName.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(80)});
        // Created now for the draft reader; placed after all configuration fields below.
        CheckBox messageContact = !useMiuix ? new CheckBox(context)
                : MiuixFormControls.checkbox(context, "加入抖音消息界面", original.messageContactEnabled);
        messageContact.setText("加入抖音消息界面");
        messageContact.setTextColor(TEXT);
        messageContact.setChecked(original.messageContactEnabled);
        messageContact.setTag("ai.message_contact_enabled");
        Spinner provider = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        List<String> providers = new ArrayList<>();
        for (AiProviderPresets.Preset preset : AiProviderPresets.ALL) providers.add(preset.name);
        provider.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, providers));
        provider.setTag("ai.provider");
        int providerIndex = 0;
        for (int i = 0; i < AiProviderPresets.ALL.size(); i++) {
            if (AiProviderPresets.ALL.get(i).id.equals(original.provider)) providerIndex = i;
        }
        provider.setSelection(providerIndex);
        addField(form, "供应商", provider);

        Spinner protocol = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        protocol.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                AiModelConfig.PROTOCOL_LABELS));
        protocol.setTag("ai.protocol");
        protocol.setSelection(protocolIndex(original.protocol));
        addField(form, "接口协议", protocol);

        Spinner responseMode = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        responseMode.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item,
                AiModelConfig.RESPONSE_LABELS));
        responseMode.setTag("ai.response_mode");
        responseMode.setSelection(responseModeIndex(original.responseMode));
        addField(form, "响应方式", responseMode);
        form.addView(text("同一模型的对话、自动回复、自动评论和本地分析共用此设置；流式接收完成后显示完整结果。",
                12, MUTED, false), matchWrap());

        Spinner reasoningEffort = !useMiuix ? new Spinner(context) : MiuixFormControls.spinner(context);
        reasoningEffort.setTag("ai.reasoning_effort");
        String[][] reasoningValues = {setReasoningChoices(reasoningEffort, original.protocol, original.reasoningEffort)};
        addField(form, "推理强度", reasoningEffort);
        form.addView(text("选项使用接口实际参数值，是否支持取决于模型；更高强度可能耗时更久、使用更多 Token。“模型默认”不附加强度参数。",
                12, MUTED, false), matchWrap());

        int[] lastProtocolSelection = {protocolIndex(original.protocol)};
        protocol.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == lastProtocolSelection[0]) return;
                lastProtocolSelection[0] = position;
                String selectedEffort = reasoningValues[0][reasoningEffort.getSelectedItemPosition()];
                reasoningValues[0] = setReasoningChoices(reasoningEffort, AiModelConfig.PROTOCOLS[position], selectedEffort);
            }
        });

        EditText endpoint = formInput(form, "API 基础地址", original.apiEndpoint, false);
        EditText key = formInput(form, "API 密钥", original.apiKey, true);
        EditText modelName = formInput(form, "模型名称", original.modelName, false);
        EditText modelsEndpoint = formInput(form, "模型列表地址（可选）", original.modelsEndpoint, false);
        Button fetchModels = formButton();
        fetchModels.setText("获取模型列表");
        fetchModels.setTag("ai.fetch_models");
        form.addView(MiuixFormControls.viewFor(fetchModels), matchWrap());

        CheckBox allowHttp = checkField(form, "允许不安全 HTTP", original.allowInsecureHttp);
        String[] systemPrompt = {original.systemPrompt};
        TextView memory = text(promptPreview(original.systemPrompt), 14, MUTED, false);
        memory.setTag("ai.prompt.primary");
        memory.setContentDescription("一级提示词");
        memory.setMaxLines(3);
        memory.setEllipsize(android.text.TextUtils.TruncateAt.END);
        memory.setPadding(0, dp(8), 0, dp(8));
        memory.setMinimumHeight(dp(48));
        memory.setClickable(true);
        memory.setFocusable(true);
        MiuixSettingsViews.Option[] promptRow = new MiuixSettingsViews.Option[1];
        Runnable editMemory = () -> showChild(new PromptEditorDialog(context, "一级提示词", systemPrompt[0], value -> {
            systemPrompt[0] = value;
            memory.setText(promptPreview(value));
            if (promptRow[0] != null) promptRow[0].setValue(promptPreview(value));
        }, useMiuix));
        if (useMiuix) {
            promptRow[0] = new MiuixSettingsViews.Option(context, "一级提示词", null,
                    promptPreview(original.systemPrompt), true, editMemory);
            promptRow[0].getView().setTag("ai.prompt.primary");
            form.addView(promptRow[0].getView(), matchWrap());
        } else {
            memory.setOnClickListener(ignored -> editMemory.run());
            addField(form, "一级提示词", memory);
        }
        EditText contextRounds = formInput(form, "上下文轮数（0–50）", Integer.toString(original.contextRounds), false);
        EditText timeout = formInput(form, "超时秒数（5–120）", Integer.toString(original.timeoutSeconds), false);
        CheckBox temperatureEnabled = checkField(form, "设置温度", original.temperatureEnabled);
        EditText temperature = formInput(form, "温度", Double.toString(original.temperature), false);
        bindOptionalInput(temperatureEnabled, temperature);
        CheckBox maxTokensEnabled = checkField(form, "限制最大输出 Token", original.maxTokensEnabled);
        EditText maxTokens = formInput(form, "最大输出 Token", Integer.toString(original.maxTokens), false);
        bindOptionalInput(maxTokensEnabled, maxTokens);
        CheckBox topPEnabled = checkField(form, "设置 Top P", original.topPEnabled);
        EditText topP = formInput(form, "Top P", Double.toString(original.topP), false);
        bindOptionalInput(topPEnabled, topP);
        EditText headers = formInput(form, "自定义请求头 JSON", original.customHeaders, false);
        EditText parameters = formInput(form, "自定义参数 JSON", original.customParameters, false);
        TextView status = text("", 12, MUTED, false);
        status.setTag("ai.status");
        status.setVisibility(View.GONE);
        form.addView(status, matchWrap());
        Callable<AiModelConfig> readDraft = () -> {
            if (loadingAvatar) throw new IllegalStateException("正在处理头像，请稍候再保存");
            return buildAiConfig(original, name, provider, protocol, responseMode,
                    reasoningValues[0][reasoningEffort.getSelectedItemPosition()], endpoint,
                    key, modelName, modelsEndpoint, systemPrompt[0], contextRounds, timeout, allowHttp,
                    temperatureEnabled, temperature, maxTokensEnabled, maxTokens,
                    topPEnabled, topP, headers, parameters, messageContact.isChecked(),
                    contactName.getText().toString(), contactAvatar[0]);
        };

        int[] lastProviderSelection = {providerIndex};
        provider.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (position == lastProviderSelection[0]) return;
                lastProviderSelection[0] = position;
                AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(position);
                protocol.setSelection(protocolIndex(preset.protocol));
                if (!preset.apiEndpoint.isEmpty()) endpoint.setText(preset.apiEndpoint);
                if (name.getText().toString().trim().isEmpty()
                        || name.getText().toString().equals(original.name)) name.setText(preset.name);
                if ("ollama".equals(preset.id) || "lmstudio".equals(preset.id)) allowHttp.setChecked(true);
            }
        });

        dialog = new MiuixDialogBuilder(context, useMiuix).setTitle("模型配置")
                .setView(scrolling(form)).setNegativeButton("取消", null)
                .setPositiveButton("保存", null).setNeutralButton("删除", null).create();
        dialog.setOnDismissListener(ignored -> close());
        bindButtons = () -> {
            boolean exists = false;
            for (AiModelConfig configured : aiSettings().configs) exists |= configured.id.equals(original.id);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setVisibility(exists ? View.VISIBLE : View.GONE);
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(ignored -> {
                try { onDelete.run(); }
                catch (RuntimeException failure) { toast(safeError(failure)); return; }
                close();
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(ignored -> {
                try {
                    AiModelConfig draft = readDraft.call();
                    draft.validate(true);
                    saveAiSettings(aiSettings().upsert(draft, false));
                    close();
                    onChanged.run();
                } catch (Exception failure) { toast(safeError(failure)); }
            });
        };

        fetchModels.setOnClickListener(ignored -> {
            try {
                if (closed || modelRequest != null) return;
                AiModelConfig draft = readDraft.call();
                draft.validate(false);
                fetchModels.setEnabled(false);
                status.setVisibility(View.VISIBLE);
                status.setText("正在获取模型列表…");
                ChatCompletionClient.ModelCatalogRequest request = new ChatCompletionClient.ModelCatalogRequest();
                modelRequest = request;
                DouAiLog.Operation trace = DouAiLog.begin("model_settings", "fetch_model_catalog");
                catalogTrace = trace;
                modelFetch = requests.submit(trace.wrap(() -> {
                    try {
                        trace.stage("catalog_request", "credentials_omitted=true");
                        List<String> fetched = request.fetch(draft);
                        status.post(trace.wrap(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) { trace.finish(DouAiLog.Outcome.CANCELLED, "model_editor_invalidated", "catalog_result_discarded=true"); return; }
                            modelRequest = null;
                            modelFetch = null;
                            catalogTrace = null;
                            trace.finish(DouAiLog.Outcome.SUCCESS, "model_catalog_parsed", "model_count=" + fetched.size());
                            fetchModels.setEnabled(true);
                            if (fetched.isEmpty()) { status.setText("接口未返回模型，请手动填写模型名称。"); return; }
                            String[] values = fetched.toArray(new String[0]);
                            showChild(new MiuixDialogBuilder(context, useMiuix).setTitle("选择模型")
                                    .setItems(values, (child, selected) -> modelName.setText(values[selected]))
                                    .setNegativeButton("取消", null).create());
                            status.setText("已获取 " + fetched.size() + " 个模型");
                        }));
                    } catch (Exception failure) {
                        trace.error("catalog_request", failure);
                        trace.finish(failure instanceof java.net.SocketTimeoutException ? DouAiLog.Outcome.TIMED_OUT : DouAiLog.Outcome.FAILED, "model_catalog_failed", "response_content_omitted=true");
                        status.post(trace.wrap(() -> {
                            if (closed || !dialog.isShowing() || modelRequest != request) return;
                            modelRequest = null;
                            modelFetch = null;
                            catalogTrace = null;
                            fetchModels.setEnabled(true);
                            status.setText("获取失败：" + safeError(failure));
                        }));
                    }
                }));
            } catch (Exception failure) {
                cancelCatalog();
                fetchModels.setEnabled(true);
                toast(safeError(failure));
            }
        });

        Button test = formButton();
        test.setText("测试对话");
        test.setTag("ai.test_chat");
        form.addView(MiuixFormControls.viewFor(test), matchWrap());
        test.setOnClickListener(ignored -> {
            try {
                AiModelConfig draft = readDraft.call();
                draft.validate(true);
                showChild(new AiTestChatDialog(context, draft, useMiuix));
            } catch (Exception failure) { toast(safeError(failure)); }
        });
        form.addView(MiuixFormControls.viewFor(messageContact), matchWrap());
    }

    private void finishAvatarLoad(Button chooseAvatar) {
        loadingAvatar = false;
        avatarLoad = null;
        chooseAvatar.setEnabled(true);
        chooseAvatar.setText("选择联系人头像");
    }

    private void updateAvatarPreview(ImageView view, String value) {
        Bitmap old = avatarPreview;
        avatarPreview = AiContactAvatar.load(value);
        if (avatarPreview == null) view.setImageResource(android.R.drawable.sym_def_app_icon);
        else view.setImageBitmap(avatarPreview);
        if (old != null) old.recycle();
    }

    private Activity ownerActivity() {
        if (dialog != null && dialog.getOwnerActivity() != null) return dialog.getOwnerActivity();
        Context candidate = context;
        while (candidate instanceof ContextWrapper) {
            if (candidate instanceof Activity) return (Activity) candidate;
            Context base = ((ContextWrapper) candidate).getBaseContext();
            if (base == candidate) break;
            candidate = base;
        }
        return null;
    }

    private void addField(LinearLayout parent, String label, View input) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams params = matchWrap();
        params.topMargin = dp(10);
        parent.addView(heading, params);
        input.setContentDescription(label);
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
    }

    private CheckBox checkField(LinearLayout parent, String label, boolean checked) {
        CheckBox box = !useMiuix ? new CheckBox(context)
                : MiuixFormControls.checkbox(context, label, checked);
        box.setText(label);
        box.setTextColor(TEXT);
        box.setChecked(checked);
        parent.addView(MiuixFormControls.viewFor(box), matchWrap());
        return box;
    }

    private static int protocolIndex(String protocol) {
        for (int i = 0; i < AiModelConfig.PROTOCOLS.length; i++) {
            if (AiModelConfig.PROTOCOLS[i].equals(protocol)) return i;
        }
        return 0;
    }

    private static int responseModeIndex(String mode) {
        for (int i = 0; i < AiModelConfig.RESPONSE_MODES.length; i++) {
            if (AiModelConfig.RESPONSE_MODES[i].equals(mode)) return i;
        }
        return 0;
    }

    private String[] setReasoningChoices(Spinner spinner, String protocol, String selected) {
        List<String> values = new ArrayList<>(java.util.Arrays.asList(AiModelConfig.reasoningEfforts(protocol)));
        List<String> labels = new ArrayList<>(java.util.Arrays.asList(AiModelConfig.reasoningLabels(protocol)));
        int index = values.indexOf(selected);
        if (index < 0) {
            index = values.size();
            values.add(selected);
            labels.add(selected + "（当前协议不适用）");
        }
        spinner.setAdapter(new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, labels));
        spinner.setSelection(index);
        return values.toArray(new String[0]);
    }

    private static void bindOptionalInput(CheckBox toggle, EditText input) {
        input.setEnabled(toggle.isChecked());
        toggle.setOnCheckedChangeListener((button, enabled) -> input.setEnabled(enabled));
    }

    private AiModelConfig buildAiConfig(AiModelConfig original, EditText name, Spinner provider, Spinner protocol,
                                       Spinner responseMode, String reasoningEffort,
                                       EditText endpoint, EditText key, EditText model,
                                       EditText modelsEndpoint, String prompt, EditText rounds,
                                       EditText timeout, CheckBox allowHttp, CheckBox temperatureEnabled,
                                       EditText temperature, CheckBox maxTokensEnabled, EditText maxTokens,
                                       CheckBox topPEnabled, EditText topP, EditText headers,
                                       EditText parameters, boolean messageContactEnabled,
                                       String messageContactName, String messageContactAvatar) throws Exception {
        AiProviderPresets.Preset preset = AiProviderPresets.ALL.get(provider.getSelectedItemPosition());
        return AiModelConfig.fromJson(new JSONObject().put("id", original.id).put("name", name.getText().toString())
                .put("provider", preset.id).put("protocol", AiModelConfig.PROTOCOLS[protocol.getSelectedItemPosition()])
                .put("responseMode", AiModelConfig.RESPONSE_MODES[responseMode.getSelectedItemPosition()])
                .put("reasoningEffort", reasoningEffort)
                .put("apiEndpoint", endpoint.getText().toString()).put("apiKey", key.getText().toString())
                .put("modelName", model.getText().toString()).put("modelsEndpoint", modelsEndpoint.getText().toString())
                .put("systemPrompt", prompt)
                .put("contextRounds", Integer.parseInt(rounds.getText().toString().trim()))
                .put("timeoutSeconds", Integer.parseInt(timeout.getText().toString().trim()))
                .put("allowInsecureHttp", allowHttp.isChecked())
                .put("temperatureEnabled", temperatureEnabled.isChecked())
                .put("temperature", temperatureEnabled.isChecked() ? Double.parseDouble(temperature.getText().toString().trim()) : original.temperature)
                .put("maxTokensEnabled", maxTokensEnabled.isChecked())
                .put("maxTokens", maxTokensEnabled.isChecked() ? Integer.parseInt(maxTokens.getText().toString().trim()) : original.maxTokens)
                .put("topPEnabled", topPEnabled.isChecked()).put("topP", topPEnabled.isChecked() ? Double.parseDouble(topP.getText().toString().trim()) : original.topP)
                .put("customHeaders", headers.getText().toString().trim().isEmpty() ? "{}" : headers.getText().toString())
                .put("customParameters", parameters.getText().toString().trim().isEmpty() ? "{}" : parameters.getText().toString())
                .put("messageContactEnabled", messageContactEnabled).put("messageContactName", messageContactName)
                .put("messageContactAvatar", messageContactAvatar));
    }

    private EditText formInput(LinearLayout parent, String label, String value, boolean secret) {
        TextView heading = text(label, 14, TEXT, false);
        LinearLayout.LayoutParams headingParams = matchWrap();
        headingParams.topMargin = dp(10);
        parent.addView(heading, headingParams);
        EditText input = !useMiuix ? new EditText(context)
                : MiuixFormControls.input(context, label, value, secret);
        input.setContentDescription(label);
        input.setText(value);
        input.setTextSize(14);
        input.setMaxLines(secret ? 1 : 4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD : InputType.TYPE_TEXT_FLAG_MULTI_LINE));
        input.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(4000)});
        parent.addView(MiuixFormControls.viewFor(input), matchWrap());
        return input;
    }

    private LinearLayout form() {
        LinearLayout form = new LinearLayout(context);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(4), dp(20), dp(8));
        return form;
    }

    private ScrollView scrolling(View child) {
        ScrollView scroll = new ScrollView(context);
        scroll.setTag("ai.model.scroll");
        scroll.addView(child);
        return scroll;
    }

    private TextView text(String value, float size, int color, boolean bold) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private static String promptPreview(String value) {
        if (value.isEmpty()) return "未设置";
        int points = value.codePointCount(0, value.length());
        return value.substring(0, value.offsetByCodePoints(0, Math.min(240, points)));
    }

    private static String safeError(Throwable failure) {
        String message = failure.getMessage();
        if (message == null) return failure.getClass().getSimpleName();
        return message.length() > 220 ? message.substring(0, 220) : message;
    }

    private void toast(String message) {
        android.widget.Toast.makeText(context, message == null ? "操作失败" : message,
                android.widget.Toast.LENGTH_SHORT).show();
    }

    private Button formButton() {
        return !useMiuix ? new Button(context) : MiuixFormControls.button(context);
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
}
