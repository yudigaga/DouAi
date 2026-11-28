package com.douai.module.ui;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.InputFilter;
import android.text.InputType;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;

import com.douai.module.R;
import com.douai.module.ai.mcp.McpClient;
import com.douai.module.ai.mcp.McpRegistry;
import com.douai.module.ui.miuix.MiuixDialogBuilder;
import com.douai.module.ui.miuix.MiuixFormControls;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

final class McpSettingsDialog implements AutoCloseable {
    private static final int MAX_IMPORT_BYTES = 128 * 1024;
    private static final String[] PROTOCOLS = {McpClient.MODERN, McpClient.LEGACY};

    private interface Operation<T> { T run() throws Exception; }

    private final Context context;
    private final boolean miuix;
    private final BiConsumer<Dialog, Runnable> presenter;
    private final McpRegistry registry;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AlertDialog dialog;
    private final TextView status;
    private final LinearLayout serviceRows;
    private final LinearLayout bindingRows;
    private final Button add;
    private final Button importFile;
    private final Button exportFile;
    private HostConfigFilePicker picker;
    private Future<?> operation;
    private boolean choosingFile;
    private boolean closed;
    private int generation;

    McpSettingsDialog(Context context, boolean miuix, BiConsumer<Dialog, Runnable> presenter) {
        this.context = context;
        this.miuix = miuix;
        this.presenter = presenter;
        registry = McpRegistry.forContext(context);
        LinearLayout body = form();
        add = button(body, "登记服务", "mcp.service.add", () -> editService(null));
        importFile = button(body, "导入 MCP JSON", "mcp.import", () -> chooseFile(null));
        exportFile = button(body, "导出 MCP JSON", "mcp.export", () ->
                run(registry::exportJson, "正在准备导出…", this::chooseFile));
        status = text(body, "");
        status.setTag("mcp.status");
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        heading(body, "服务");
        serviceRows = column();
        body.addView(serviceRows);
        heading(body, "手动命令");
        bindingRows = column();
        body.addView(bindingRows);
        dialog = builder("在线 MCP").setView(scroll(body)).setNegativeButton("关闭", null).create();
        refresh("");
    }

    AlertDialog dialog() { return dialog; }

    private void refresh(String message) {
        status.setText(message);
        try {
            List<McpRegistry.Service> services = registry.services();
            List<McpRegistry.Binding> bindings = registry.bindings();
            Set<McpRegistry.Binding> activeBindings = new HashSet<>(registry.activeBindings());
            serviceRows.removeAllViews();
            bindingRows.removeAllViews();
            if (services.isEmpty()) text(serviceRows, "暂无登记的服务");
            for (McpRegistry.Service service : services) {
                String tag = "mcp.service." + service.id();
                text(serviceRows, target(service));
                button(serviceRows, "编辑服务", tag + ".edit", () -> editService(service));
                if (registry.isAuthorized(service)) {
                    button(serviceRows, "发现工具", tag + ".discover", () -> discover(service));
                    button(serviceRows, "撤销授权", tag + ".revoke", () -> revoke(service));
                } else {
                    button(serviceRows, "授权并发现工具", tag + ".authorize", () -> authorize(service));
                }
                button(serviceRows, "删除服务", tag + ".delete", () -> reviewDeletion(service));
            }
            if (bindings.isEmpty()) text(bindingRows, "暂无绑定的手动命令");
            for (McpRegistry.Binding binding : bindings) {
                String state = activeBindings.contains(binding) ? "可用" : "待确认";
                TextView row = text(bindingRows, "/" + binding.alias() + " · " + state + "\n"
                        + binding.serviceId() + " · " + shown(binding.toolName()));
                row.setTag("mcp.binding." + binding.alias());
            }
        } catch (Exception failure) {
            status.setText(failureText(failure, "暂时无法读取在线 MCP 设置"));
        }
    }

    private void editService(McpRegistry.Service current) {
        if (!idle()) return;
        LinearLayout body = form();
        EditText id = input(body, "服务标识", "mcp.service.id", 48, false);
        EditText name = input(body, "显示名称", "mcp.service.label", 80, false);
        EditText endpoint = input(body, "HTTPS 地址", "mcp.service.endpoint", 2048, false);
        endpoint.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        text(body, "协议版本");
        Spinner protocol = miuix ? MiuixFormControls.spinner(context) : new Spinner(context);
        protocol.setTag("mcp.service.protocol");
        protocol.setContentDescription("协议版本");
        ArrayAdapter<String> protocols = new ArrayAdapter<>(context, android.R.layout.simple_spinner_item, PROTOCOLS);
        protocols.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        protocol.setAdapter(protocols);
        protocol.setSelection(0);
        body.addView(MiuixFormControls.viewFor(protocol));
        EditText token = input(body, "Bearer 密钥（可选）", "mcp.service.token", 2048, true);
        if (current != null) {
            id.setText(current.id());
            id.setEnabled(false);
            name.setText(current.label());
            endpoint.setText(current.endpoint());
            protocol.setSelection(McpClient.LEGACY.equals(current.protocol()) ? 1 : 0);
        }
        TextView error = text(body, "");
        AlertDialog editor = builder(current == null ? "登记 MCP 服务" : "编辑 MCP 服务")
                .setView(scroll(body)).setPositiveButton("保存", null).setNegativeButton("取消", null).create();
        presenter.accept(editor, () -> token.setText(""));
        if (!editor.isShowing()) return;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!idle() || !editor.isShowing()) return;
            try {
                int selected = protocol.getSelectedItemPosition();
                if (selected < 0) { error.setText("请选择协议版本"); return; }
                registry.register(new McpRegistry.Service(value(id), value(name), value(endpoint),
                        PROTOCOLS[selected]), token.getText().toString());
            } catch (Exception failure) {
                token.setText("");
                error.setText(failureText(failure, "保存失败，请检查服务标识、HTTPS 地址和协议版本"));
                return;
            }
            token.setText("");
            editor.dismiss();
            refresh("已保存服务配置");
        });
    }

    private void authorize(McpRegistry.Service service) {
        if (!idle()) return;
        boolean[] approved = {false};
        AlertDialog confirmation = builder("授权 MCP 服务")
                .setMessage(target(service) + "\n确认后将连接此地址发现工具。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认授权", (ignored, which) -> {
                    if (!idle()) return;
                    try {
                        registry.authorize(service);
                        approved[0] = true;
                    } catch (Exception failure) {
                        refresh(failureText(failure, "授权失败，请重新核对服务配置"));
                    }
                }).create();
        presenter.accept(confirmation, () -> {
            if (approved[0] && idle()) {
                refresh("");
                discover(service);
            }
        });
    }

    private void revoke(McpRegistry.Service service) {
        if (!idle()) return;
        try {
            registry.revoke(service);
            refresh("已撤销服务授权");
        } catch (Exception failure) {
            refresh(failureText(failure, "暂时无法撤销授权"));
        }
    }

    private void reviewDeletion(McpRegistry.Service service) {
        if (!idle()) return;
        McpRegistry.DeleteTarget expected;
        try {
            expected = registry.prepareDelete(service);
        } catch (McpRegistry.Failure failure) {
            refresh(deletionFailure(failure));
            return;
        }
        AlertDialog confirmation = builder("删除 MCP 服务")
                .setMessage(target(service) + "\n\n将删除此服务的本机设置、授权、密钥、工具目录和关联斜杠命令。"
                        + "\n此操作不会向远端发送请求。")
                .setNegativeButton("取消", null)
                .setPositiveButton("确认删除", (ignored, which) -> {
                    if (!idle()) return;
                    try {
                        registry.delete(expected);
                        refresh("已删除服务及本机配置");
                    } catch (McpRegistry.Failure failure) {
                        refresh(deletionFailure(failure));
                    }
                }).create();
        presenter.accept(confirmation, null);
    }

    private static String deletionFailure(McpRegistry.Failure failure) {
        return failure.code() == McpRegistry.Code.CONFIRMATION_REQUIRED
                ? "服务配置或状态已变化，请重新核对后再删除"
                : "暂时无法删除服务，请检查本机配置";
    }

    private void discover(McpRegistry.Service service) {
        run(() -> registry.discover(service, System.nanoTime() + TimeUnit.SECONDS.toNanos(30)),
                "正在发现工具…", tools -> showTools(service, tools));
    }

    private void showTools(McpRegistry.Service service, List<McpClient.Tool> tools) {
        if (tools.isEmpty()) {
            status.setText("此服务没有可展示的工具");
            return;
        }
        status.setText(ModuleUiStrings.get(context, R.string.mcp_tools_discovered, "已发现 %1$d 个工具", tools.size()));
        McpClient.Tool[] selected = {null};
        String[] labels = tools.stream().map(tool -> {
            String name = shown(tool.name());
            String title = shown(tool.title());
            return title.isEmpty() || title.equals(name) ? name : title + " · " + name;
        }).toArray(String[]::new);
        AlertDialog listing = builder("选择要绑定的工具")
                .setItems(labels, (ignored, which) -> selected[0] = tools.get(which))
                .setNegativeButton("关闭", null).create();
        presenter.accept(listing, () -> {
            if (selected[0] != null && idle()) bind(service, selected[0]);
        });
    }

    private void bind(McpRegistry.Service service, McpClient.Tool tool) {
        LinearLayout body = form();
        text(body, target(service));
        text(body, "工具 " + shown(tool.name()));
        String description = shown(tool.description());
        if (!description.isEmpty()) text(body, description);
        text(body, "调用时在命令后输入 JSON 对象，空参数可用 {}。");
        EditText alias = input(body, "命令名（以 mcp- 开头）", "mcp.binding.alias", 48, false);
        alias.setText(ModuleUiStrings.get(context, R.string.mcp_alias_prefix, "mcp-"));
        alias.setSelection(alias.length());
        TextView error = text(body, "");
        AlertDialog editor = builder("绑定手动命令").setView(scroll(body))
                .setPositiveButton("确认绑定", null).setNegativeButton("取消", null).create();
        presenter.accept(editor, null);
        if (!editor.isShowing()) return;
        editor.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> {
            if (!idle() || !editor.isShowing()) return;
            String command = value(alias);
            try { registry.bind(service, tool, command); }
            catch (Exception failure) {
                error.setText(failureText(failure, "绑定失败，请核对命令名和工具权限"));
                return;
            }
            editor.dismiss();
            refresh("已绑定 /" + command);
        });
    }

    private void chooseFile(String exportJson) {
        if (!idle()) return;
        boolean export = exportJson != null;
        choosingFile = true;
        setBusy(true);
        status.setText(export ? "请选择保存位置" : "请选择 MCP JSON 文件");
        HostConfigFilePicker selected = HostConfigFilePicker.launch(dialog.getOwnerActivity(), export,
                "DouAi-MCP.json", uri -> {
                    if (!active() || !choosingFile) return;
                    choosingFile = false;
                    picker = null;
                    setBusy(false);
                    if (uri == null) { status.setText("已取消文件选择"); return; }
                    if (export) {
                        run(() -> { write(uri, exportJson); return true; },
                                "正在写入 MCP JSON…", saved -> status.setText(
                                        ModuleUiStrings.get(context, R.string.mcp_export_completed, "已导出 MCP JSON")));
                    } else {
                        run(() -> registry.previewImport(read(uri)), "正在读取导入预览…", this::showImport);
                    }
                });
        if (!choosingFile) {
            if (selected != null) selected.close();
        } else if (selected != null) {
            picker = selected;
        } else {
            choosingFile = false;
            setBusy(false);
            status.setText("无法打开文件选择器");
        }
    }

    private void showImport(McpRegistry.ImportPreview preview) {
        LinearLayout body = form();
        text(body, "导入后服务默认停用，授权后才能发现工具。");
        for (McpRegistry.Service service : preview.services()) text(body, target(service));
        for (McpRegistry.Binding binding : preview.bindings()) {
            text(body, "/" + binding.alias() + " · " + binding.serviceId() + " · " + binding.toolName());
        }
        status.setText("");
        presenter.accept(builder("预览 MCP 导入").setView(scroll(body))
                .setNegativeButton("取消", null)
                .setPositiveButton("确认导入", (ignored, which) ->
                        run(() -> { registry.applyImport(preview); return true; },
                                "正在应用配置…", imported -> refresh("已导入，服务待授权")))
                .create(), null);
    }

    private String read(Uri uri) throws IOException {
        try (InputStream stream = context.getContentResolver().openInputStream(uri)) {
            if (stream == null) throw new IOException();
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int size;
            while ((size = stream.read(buffer)) != -1) {
                if (Thread.currentThread().isInterrupted() || size > MAX_IMPORT_BYTES - bytes.size()) throw new IOException();
                bytes.write(buffer, 0, size);
            }
            String json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString();
            return !json.isEmpty() && json.charAt(0) == '\uFEFF' ? json.substring(1) : json;
        }
    }

    private void write(Uri uri, String json) throws IOException {
        try (OutputStream stream = context.getContentResolver().openOutputStream(uri, "wt")) {
            if (stream == null) throw new IOException();
            stream.write(json.getBytes(StandardCharsets.UTF_8));
        }
    }

    private <T> void run(Operation<T> task, String working, Consumer<T> done) {
        if (!idle()) return;
        int ticket = ++generation;
        setBusy(true);
        status.setText(working);
        operation = worker.submit(() -> {
            try {
                T result = task.run();
                main.post(() -> { if (finish(ticket)) done.accept(result); });
            } catch (Exception failure) {
                String message = failureText(failure, "MCP 操作失败，请检查配置或文件");
                main.post(() -> { if (finish(ticket)) status.setText(message); });
            }
        });
    }

    private boolean finish(int ticket) {
        if (!active() || ticket != generation) return false;
        operation = null;
        setBusy(false);
        return true;
    }

    private void setBusy(boolean busy) {
        add.setEnabled(!busy);
        importFile.setEnabled(!busy);
        exportFile.setEnabled(!busy);
    }

    private boolean active() { return !closed && dialog.isShowing(); }

    private boolean idle() { return active() && operation == null && !choosingFile; }

    private static String failureText(Exception failure, String fallback) {
        return failure instanceof McpRegistry.Failure || failure instanceof McpClient.Failure
                ? failure.getMessage() : fallback;
    }

    private static String target(McpRegistry.Service service) {
        return shown(service.label()) + " · " + service.id() + "\n" + service.endpoint() + "\n协议 " + service.protocol();
    }

    private static String value(EditText field) { return field.getText().toString().trim(); }

    private static String shown(String value) {
        if (value == null) return "";
        String visible = value.replaceAll("[\\p{Cc}\\p{Cf}]", " ").trim();
        int end = Math.min(visible.length(), 300);
        if (end > 0 && end < visible.length() && Character.isHighSurrogate(visible.charAt(end - 1))) end--;
        return visible.substring(0, end);
    }

    private AlertDialog.Builder builder(String title) { return new MiuixDialogBuilder(context, miuix).setTitle(title); }

    private LinearLayout column() {
        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        return column;
    }

    private LinearLayout form() {
        LinearLayout form = column();
        int padding = dp(16);
        form.setPadding(padding, 0, padding, padding);
        return form;
    }

    private ScrollView scroll(LinearLayout body) {
        ScrollView scroll = new ScrollView(context);
        scroll.addView(body);
        return scroll;
    }

    private TextView text(LinearLayout parent, String label) {
        TextView text = new TextView(context);
        text.setText(label);
        parent.addView(text);
        return text;
    }

    private void heading(LinearLayout parent, String label) {
        TextView heading = text(parent, label);
        heading.setAccessibilityHeading(true);
        heading.setPadding(0, dp(16), 0, dp(6));
    }

    private Button button(LinearLayout parent, String label, String tag, Runnable action) {
        Button button = miuix ? MiuixFormControls.button(context) : new Button(context);
        button.setText(label);
        button.setTag(tag);
        button.setOnClickListener(ignored -> action.run());
        parent.addView(MiuixFormControls.viewFor(button));
        return button;
    }

    private EditText input(LinearLayout body, String label, String tag, int maximum, boolean secret) {
        EditText field = miuix && !secret ? MiuixFormControls.input(context, label, "", false) : new EditText(context);
        field.setTag(tag);
        field.setHint(label);
        field.setContentDescription(label);
        field.setSingleLine(true);
        field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(maximum)});
        if (secret) {
            field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
            field.setSaveEnabled(false);
            field.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
            field.setImeOptions(EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING);
        }
        body.addView(MiuixFormControls.viewFor(field));
        return field;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }

    @Override public void close() {
        if (closed) return;
        closed = true;
        if (picker != null) picker.close();
        if (operation != null) operation.cancel(true);
        worker.shutdownNow();
    }
}
