package com.douai.module.ai.skills;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Recognizes bounded, explicit field statements inside a user-selected video draft. */
public final class SkillVideoEdit {
    private static final int MAX_TEXT = 400;
    private static final int MAX_CLAUSES = 12;
    private static final String QUERY_MODE = "queryMode";
    private static final String ENCODING = "submitEncoding";
    private static final String STATES = "states";
    private static final String STATE_PAIR = "statePair";
    private static final String SUBMIT_ROUTE = "submitRoute";
    private static final String STATUS_ROUTE = "statusRoute";
    private static final String PREFIX = "(?:(?:请|麻烦|帮我|给我|再|然后|同时|并且|并)\\s*){0,4}";
    private static final Pattern NON_ACTION = Pattern.compile("^" + PREFIX
            + "(?:别|不要|不用|不必|无需|没必要|例如|比如|举例|引用|转述|原文|他说|她说|朋友说|同事说|有人说|请问|解释|如何|怎么)");
    private static final Pattern ASSIGN = Pattern.compile("^" + PREFIX + "(?:([把将])\\s*)?"
            + "([^:：,，;；。\\r\\n]{1,48}?)\\s*"
            + "(更改为|修改为|调整为|调整成|更换为|设置为|设置成|改为|改成|改到|换为|换成|设为|写成|填为|填成|用于|用|是|为|叫|[:：=])\\s*(.*)$",
            Pattern.DOTALL);
    private static final Pattern STATE_WORDS = Pattern.compile("^" + PREFIX + "(.{1,36}?)\\s*(?:表示|代表|对应)\\s*(.+)$");
    private static final Pattern REPLACING = Pattern.compile("^(?:改|换|更改|修改|调整|更换|设|写成|填成|填为)");
    private static final Pattern ROUTE = Pattern.compile("(?i)^(GET|POST|DELETE)\\s+(.+)$");
    private static final Pattern STATE_TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}");
    private static final Pattern CONNECTOR = Pattern.compile("并且|然后|同时|并");

    public enum Mode { FILL, REPLACE, CLARIFY, UNSUPPORTED_ENCODING, NEEDS_ENCODING_EVIDENCE, SENSITIVE }

    public record Edit(Mode mode, Map<String, String> fields, Map<String, String> stateMappings, String queryMode,
                       boolean replaceQueryMode, Set<String> replacements, String submissionEncoding) {
        public Edit {
            fields = Collections.unmodifiableMap(new LinkedHashMap<>(fields));
            stateMappings = Collections.unmodifiableMap(new LinkedHashMap<>(stateMappings));
            replacements = Collections.unmodifiableSet(new LinkedHashSet<>(replacements));
        }

        @Override public String toString() { return "SkillVideoEdit.Edit[" + mode + "]"; }
    }

    private record Clause(String target, String value, boolean replace, String state) {}

    private SkillVideoEdit() {}

    public static Edit parse(String typed) {
        if (typed == null || typed.isEmpty()) return null;
        String head = typed.substring(0, Math.min(MAX_TEXT, typed.length())).trim();
        if (head.isEmpty() || "/>`\"'「『“‘".indexOf(head.charAt(0)) >= 0 || NON_ACTION.matcher(head).find()
                || head.matches("(?s).*(?:[?？]|吗|么)[。！!]*$")) return null;
        if (typed.length() > MAX_TEXT || typed.codePoints().anyMatch(c -> Character.isISOControl(c)
                && c != '\r' && c != '\n' && c != '\t')) {
            return recognizedHead(head) ? empty(Mode.CLARIFY, "") : null;
        }
        List<String> parts = clauses(typed.trim());
        if (parts == null || parts.size() > MAX_CLAUSES) return recognizedHead(head) ? empty(Mode.CLARIFY, "") : null;

        boolean recognized = false;
        boolean valid = true;
        boolean collectingStates = false;
        Patch patch = new Patch();
        for (String part : parts) {
            Clause clause = parseClause(part);
            if (clause == null) {
                if (!collectingStates || !patch.addStatePair(part)) valid = false;
                continue;
            }
            recognized = true;
            collectingStates = STATES.equals(clause.target());
            if (!patch.add(clause)) valid = false;
        }
        if (!recognized) return null;
        try { SkillVideoFacts.checkSecrets(typed); }
        catch (IllegalArgumentException sensitive) { return empty(Mode.SENSITIVE, ""); }
        if (!valid) return empty(Mode.CLARIFY, "");
        Mode mode;
        if (patch.encoding.isEmpty()) mode = patch.replace ? Mode.REPLACE : Mode.FILL;
        else mode = SkillVideoFacts.JSON_ENCODING.equals(patch.encoding) ? Mode.NEEDS_ENCODING_EVIDENCE : Mode.UNSUPPORTED_ENCODING;
        return new Edit(mode, patch.fields, patch.states, patch.queryMode, patch.replaceQueryMode,
                patch.replacements, patch.encoding);
    }

    private static Edit empty(Mode mode, String encoding) {
        return new Edit(mode, Map.of(), Map.of(), "", false, Set.of(), encoding);
    }

    private static boolean recognizedHead(String text) {
        Matcher boundary = Pattern.compile("[,，;；。\\r\\n]").matcher(text);
        return parseClause(boundary.find() ? text.substring(0, boundary.start()) : text) != null;
    }

    private static List<String> clauses(String text) {
        List<String> sections = new ArrayList<>();
        int start = 0;
        char closing = 0;
        Matcher connector = CONNECTOR.matcher(text);
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (closing != 0) {
                if (c == '\\' && index + 1 < text.length() && (closing == '"' || closing == '\'')) index++;
                else if (c == closing) closing = 0;
                continue;
            }
            closing = closeQuote(c);
            if (closing != 0) continue;
            if (",，;；。\r\n".indexOf(c) >= 0) {
                addClause(sections, text.substring(start, index));
                start = index + 1;
            } else if (index > start && connector.region(index, text.length()).lookingAt()
                    && !text.substring(start, index).trim().isEmpty()
                    && parseClause(text.substring(connector.end())) != null) {
                addClause(sections, text.substring(start, index));
                start = connector.end();
                index = start - 1;
            }
        }
        if (closing != 0) return null;
        addClause(sections, text.substring(start));
        return sections;
    }

    private static void addClause(List<String> sections, String value) {
        String text = value.trim();
        if (!text.isEmpty()) sections.add(text);
    }

    private static Clause parseClause(String text) {
        Matcher assign = ASSIGN.matcher(text.trim());
        if (assign.matches()) {
            String label = assign.group(2).trim();
            String normalized = normalize(label).replaceFirst("^(?:当前的?|现有的?|已填写的|这个技能的|视频的|接口的)", "");
            String value = unquote(assign.group(4));
            boolean replace = assign.group(1) != null || REPLACING.matcher(assign.group(3)).find();
            String field = field(label, normalized);
            if (!field.isEmpty()) return new Clause(field, value, replace, "");
            String state = stateLabel(normalized);
            if (!state.isEmpty()) return new Clause(STATE_PAIR, value, replace, state);
        }
        Matcher description = STATE_WORDS.matcher(text.trim());
        if (description.matches()) {
            String state = canonicalState(description.group(2));
            String value = unquote(description.group(1));
            if (!state.isEmpty() && STATE_TOKEN.matcher(value).matches()) {
                return new Clause(STATE_PAIR, value, false, state);
            }
        }
        return null;
    }

    private static String field(String original, String normalized) {
        return switch (normalized) {
            case "任务id", "任务id字段", "任务id路径", "提交响应的任务id", "提交响应的任务id字段", "提交返回任务id", "创建响应任务id" ->
                    "submitIdField";
            case "查询响应任务id", "查询响应的任务id", "查询响应的任务id字段", "查询返回任务id" -> "statusIdField";
            case "post查询请求任务id字段", "post查询请求id字段", "post查询任务id字段", "查询post请求任务id字段" -> "statusPostIdField";
            case "提交接口", "提交视频接口", "创建接口" -> SUBMIT_ROUTE;
            case "查询接口", "状态查询接口", "轮询接口" -> STATUS_ROUTE;
            case "查询方式", "查询方法", "查询请求方式", "查询请求方法", "查询协议", "轮询方式", "轮询方法", "状态查询方式" -> QUERY_MODE;
            case "提交格式", "提交编码", "提交请求格式", "提交请求编码", "content-type", "contenttype", "submitencoding" -> ENCODING;
            default -> SkillVideoFacts.chatEditableField(original);
        };
    }

    private static String stateLabel(String label) {
        return switch (label) {
            case "完成状态", "完成状态值", "成功状态", "成功状态值" -> "completed";
            case "失败状态", "失败状态值" -> "failed";
            case "运行状态", "处理状态", "运行状态值", "处理状态值" -> "running";
            case "排队状态", "排队状态值" -> "queued";
            case "取消状态", "取消状态值" -> "cancelled";
            default -> "";
        };
    }

    private static String canonicalState(String text) {
        return switch (normalize(unquote(text))) {
            case "queued", "排队", "排队中", "等待中" -> "queued";
            case "running", "运行", "运行中", "处理", "处理中", "生成中" -> "running";
            case "completed", "完成", "已完成", "成功" -> "completed";
            case "failed", "失败", "已失败" -> "failed";
            case "cancelled", "canceled", "取消", "已取消" -> "cancelled";
            default -> "";
        };
    }

    private static String queryMode(String text) {
        return switch (normalize(text)) {
            case "get", "固定get", "固定路径get", "固定地址get", "固定路径", "固定get路径", "fixed_get" -> "fixed_get";
            case "返回地址get", "响应地址get", "返回的查询地址", "提交响应的查询地址", "返回地址", "响应地址", "response_url_get" ->
                    "response_url_get";
            case "post", "jsonpost", "postjson", "post查询", "json_post" -> "json_post";
            default -> "";
        };
    }

    private static String normalize(String text) {
        return text.replaceAll("[\\s\\p{Z}]", "").toLowerCase(Locale.ROOT);
    }

    private static String unquote(String raw) {
        String text = raw.trim();
        return text.length() > 1 && closeQuote(text.charAt(0)) == text.charAt(text.length() - 1)
                ? text.substring(1, text.length() - 1).trim() : text;
    }

    private static char closeQuote(char c) {
        return switch (c) {
            case '"', '\'', '`' -> c;
            case '“' -> '”';
            case '‘' -> '’';
            case '「' -> '」';
            case '『' -> '』';
            default -> 0;
        };
    }

    private static final class Patch {
        private final Map<String, String> fields = new LinkedHashMap<>();
        private final Map<String, String> states = new LinkedHashMap<>();
        private final Set<String> replacements = new LinkedHashSet<>();
        private String queryMode = "";
        private String encoding = "";
        private boolean replace;
        private boolean replaceQueryMode;

        boolean add(Clause clause) {
            if (clause.value().isEmpty()) return false;
            replace |= clause.replace();
            return switch (clause.target()) {
                case QUERY_MODE -> addQueryMode(queryMode(clause.value()), clause.replace());
                case ENCODING -> addEncoding(clause.value());
                case STATE_PAIR -> addState(clause.value(), clause.state());
                case STATES -> addStateMap(clause.value());
                case SUBMIT_ROUTE, STATUS_ROUTE -> addRoute(clause);
                default -> putField(clause.target(), clause.value(), clause.replace());
            };
        }

        boolean addRoute(Clause clause) {
            Matcher route = ROUTE.matcher(clause.value());
            if (!route.matches()) return false;
            String method = route.group(1).toUpperCase(Locale.ROOT);
            String path = unquote(route.group(2));
            if (SUBMIT_ROUTE.equals(clause.target())) {
                String field = path.startsWith("/") ? "submitPath" : "submitEndpoint";
                return "POST".equals(method) && putField(field, path, clause.replace());
            }
            return switch (method) {
                case "GET" -> addQueryMode("fixed_get", clause.replace()) && putField("statusPath", path, clause.replace());
                case "POST" -> addQueryMode("json_post", clause.replace()) && putField("statusPostPath", path, clause.replace());
                default -> false;
            };
        }

        boolean addQueryMode(String value, boolean explicit) {
            if (value.isEmpty() || !queryMode.isEmpty() && !queryMode.equals(value)) return false;
            queryMode = value;
            replaceQueryMode |= explicit;
            return true;
        }

        boolean addEncoding(String value) {
            String normalized = SkillVideoFacts.encoding(value);
            if (!encoding.isEmpty() && !encoding.equals(normalized)) return false;
            encoding = normalized;
            return true;
        }

        boolean addStateMap(String value) {
            for (String pair : value.split("[,，;；\\r\\n]", -1)) if (!addStatePair(pair)) return false;
            return true;
        }

        boolean addStatePair(String pair) {
            String[] values = pair.split("=", -1);
            return values.length == 2 && addState(unquote(values[0]), canonicalState(values[1]));
        }

        boolean addState(String value, String state) {
            return !state.isEmpty() && STATE_TOKEN.matcher(value).matches() && put(states, value, state);
        }

        boolean putField(String field, String value, boolean explicit) {
            if (value.isEmpty() || !put(fields, field, value)) return false;
            if (explicit) replacements.add(field);
            return true;
        }

        private static boolean put(Map<String, String> target, String key, String value) {
            String existing = target.putIfAbsent(key, value);
            return existing == null || existing.equals(value);
        }
    }
}
