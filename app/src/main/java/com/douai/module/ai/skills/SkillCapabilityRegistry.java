package com.douai.module.ai.skills;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Only application-owned capabilities can be selected from a declaration. */
public final class SkillCapabilityRegistry {
    public enum Capability {
        TEXT_MODEL("text_model", "text_model", SkillResult.Type.TEXT, 2000),
        IMAGE_GENERATION("image_generation", "service", SkillResult.Type.IMAGE, 4000);

        private final String id;
        private final String kind;
        private final SkillResult.Type outputType;
        private final int maxInput;

        Capability(String id, String kind, SkillResult.Type outputType, int maxInput) {
            this.id = id;
            this.kind = kind;
            this.outputType = outputType;
            this.maxInput = maxInput;
        }

        public String id() { return id; }
        public String kind() { return kind; }
        public SkillResult.Type outputType() { return outputType; }
        public int maxInput() { return maxInput; }
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
