package com.douai.module.ai.skills;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Only application-owned capabilities can be selected from a declaration. */
public final class SkillCapabilityRegistry {
    public enum ExecutionMode {
        DIRECT("直接返回"), POLLING("异步轮询");

        private final String label;
        ExecutionMode(String label) { this.label = label; }
        public String label() { return label; }
    }

    public enum ServiceRef {
        SELECTED_MODEL("selected_model", "当前 AI 模型"),
        IMAGE_CONNECTION("image_connection", "图片服务配置"),
        SKILL_HTTPS("skill_https", "技能 HTTPS 接口");

        private final String id;
        private final String label;
        ServiceRef(String id, String label) { this.id = id; this.label = label; }
        public String id() { return id; }
        public String label() { return label; }
    }

    public enum Capability {
        TEXT_MODEL("text_model", "text_model", SkillResult.Type.TEXT, ExecutionMode.DIRECT, ServiceRef.SELECTED_MODEL, 2000),
        TABLE_MODEL("table_model", "table_model", SkillResult.Type.TABLE, ExecutionMode.DIRECT, ServiceRef.SELECTED_MODEL, 2000),
        VIDEO_TASK("video_task", "video_task", SkillResult.Type.VIDEO, ExecutionMode.POLLING, ServiceRef.SKILL_HTTPS, 2000),
        CUSTOM_SERVICE("custom_service", "custom_service", SkillResult.Type.TEXT, ExecutionMode.DIRECT, ServiceRef.SKILL_HTTPS, 2000),
        IMAGE_GENERATION("image_generation", "service", SkillResult.Type.IMAGE, ExecutionMode.DIRECT, ServiceRef.IMAGE_CONNECTION, 4000);

        private final String id;
        private final String kind;
        private final SkillResult.Type outputType;
        private final ExecutionMode executionMode;
        private final ServiceRef serviceRef;
        private final int maxInput;

        Capability(String id, String kind, SkillResult.Type outputType, ExecutionMode executionMode, ServiceRef serviceRef, int maxInput) {
            this.id = id;
            this.kind = kind;
            this.outputType = outputType;
            this.executionMode = executionMode;
            this.serviceRef = serviceRef;
            this.maxInput = maxInput;
        }

        public String id() { return id; }
        public String kind() { return kind; }
        public SkillResult.Type outputType() { return outputType; }
        public ExecutionMode executionMode() { return executionMode; }
        public ServiceRef serviceRef() { return serviceRef; }
        public int maxInput() { return maxInput; }
        public boolean usesModel() { return serviceRef == ServiceRef.SELECTED_MODEL; }
    }

    private SkillCapabilityRegistry() { }

    public static Capability require(String id) {
        for (Capability capability : Capability.values()) {
            if (capability.id().equals(id)) return capability;
        }
        throw new SkillManifest.Invalid("技能清单使用了未注册的能力");
    }

    static List<SkillStore.Field> imageFields(SkillServices services, SkillServices.Service bound,
                                             boolean authorized) {
        List<SkillStore.Field> fields = new ArrayList<>();
        if (bound == null || bound.adapter() == SkillServices.Adapter.OPENAI_IMAGES) {
            fields.add(required("endpoint", "API 地址", bound != null && !bound.endpoint().isEmpty()));
            fields.add(required("model", "模型名", bound != null && !bound.modelName().isEmpty()));
            fields.add(required("credential", "密钥", bound != null && services.hasCredential(bound)));
        }
        fields.add(new SkillStore.Field("authorization", "服务授权", true, authorized
                ? SkillStore.FieldState.FILLED : SkillStore.FieldState.NEEDS_AUTHORIZATION));
        return List.copyOf(fields);
    }

    private static SkillStore.Field required(String id, String label, boolean filled) {
        return new SkillStore.Field(id, label, true,
                filled ? SkillStore.FieldState.FILLED : SkillStore.FieldState.MISSING);
    }

    static SkillResult execute(Capability capability, SkillServices services, SkillServices.Service boundService,
                               String instruction, String input, Map<String, Object> values,
                               AiSkills.TextExecutor executor) throws Exception {
        switch (capability) {
            case CUSTOM_SERVICE:
                throw new IllegalStateException("请先确认本次技能服务调用");
            case VIDEO_TASK:
                throw new IllegalStateException(SkillStore.NEEDS_VIDEO_PROVIDER);
            case TABLE_MODEL:
                if (executor == null) throw new IllegalStateException("此入口未选择文本模型");
                SkillAuthoring.textInput(instruction, input, values);
                return new SkillResult.Table(SkillTable.parse(executor.runConfigured(instruction, input, values)));
            case TEXT_MODEL:
                if (executor == null) throw new IllegalStateException("此入口未选择文本模型");
                SkillAuthoring.textInput(instruction, input, values);
                String answer = executor.runConfigured(instruction, input, values);
                if (answer == null || answer.trim().isEmpty() || answer.length() > 4000 || answer.indexOf(0) >= 0) {
                    throw new IllegalArgumentException("技能输出为空或超出长度");
                }
                SkillAuthoring.rejectCredential(answer);
                return new SkillResult.Text(answer);
            case IMAGE_GENERATION:
                SkillResult result = services.executeResult(boundService, input);
                if (result.type() != capability.outputType()) throw new IOException("服务返回的结果类型不符合技能声明");
                return result;
            default:
                throw new IllegalStateException("技能能力不可用");
        }
    }
}
