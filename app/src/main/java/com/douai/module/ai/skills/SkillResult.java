package com.douai.module.ai.skills;

import com.douai.module.feature.ChatImage;

import java.util.Objects;

/** A typed Skill payload. */
public interface SkillResult {
    enum Type {
        TEXT("text", "文本"), IMAGE("image", "图片"), TABLE("table", "表格");

        private final String wireName;
        private final String label;

        Type(String wireName, String label) {
            this.wireName = wireName;
            this.label = label;
        }

        public String wireName() { return wireName; }
        public String label() { return label; }

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

    record Table(SkillTable table) implements SkillResult {
        public Table { Objects.requireNonNull(table); }
        @Override public Type type() { return Type.TABLE; }
    }
}
