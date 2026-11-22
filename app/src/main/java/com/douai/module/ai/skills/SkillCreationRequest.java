package com.douai.module.ai.skills;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes a direct, single-message request to create a Skill in the manual composer. */
final class SkillCreationRequest {
    private static final Pattern CREATE = Pattern.compile(
            "(?i)^(?:请\\s*)?(?:(?:你|帮我|给我|为我)\\s*){0,2}"
                    + "(?:创建|新建|做|制作|生成|设计)\\s*(?:一个新的|一个|一条|一项|个)?\\s*"
                    + "([^\\r\\n。！？?!：:；;，,\\x60]{0,160}?)\\s*(?:skill|技能)"
                    + "(?:[，,]\\s*(?:用于|用来|要能|能|可以|需求是)\\s*"
                    + "([^\\r\\n。！？?!：:；;，,\\x60]{1,120}))?"
                    + "\\s*(?:吧|好吗|可以吗)?[。！？!?]?\\s*$");

    private SkillCreationRequest() {}

    static String requirement(String prompt) {
        if (prompt == null || prompt.length() > 320) return null;
        String sentence = prompt.trim();
        if (sentence.codePoints().anyMatch(Character::isISOControl)) return null;
        Matcher request = CREATE.matcher(sentence);
        if (!request.matches()) return null;
        String leading = request.group(1).trim();
        if (leading.startsWith("关于") || leading.contains("如何") || leading.contains("怎么")) return null;
        String trailing = request.group(2);
        String combined = trailing == null ? leading : (leading + " " + trailing).trim();
        return combined.isEmpty() ? "新技能" : combined;
    }
}
