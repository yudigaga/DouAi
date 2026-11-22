package com.douai.module.ai.skills;

import com.douai.module.feature.ChatImage;

import java.util.Objects;

/** The payload of a completed Skill invocation. */
public interface SkillResult {
    enum Type {
        TEXT("text"), IMAGE("image");

        private final String wireName;

        Type(String wireName) { this.wireName = wireName; }

        public String wireName() { return wireName; }

        public static Type fromWire(String value) {
            for (Type type : values()) if (type.wireName.equals(value)) return type;
            throw new IllegalArgumentException("技能结果类型无效");
        }
    }

    Type type();

    record Text(String text) implements SkillResult {
        public Text { Objects.requireNonNull(text); }
        @Override public Type type() { return Type.TEXT; }
    }

    record Image(ChatImage image) implements SkillResult {
        public Image { Objects.requireNonNull(image); }
        @Override public Type type() { return Type.IMAGE; }
    }
}
