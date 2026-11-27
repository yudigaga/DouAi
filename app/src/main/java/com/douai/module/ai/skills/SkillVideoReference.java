package com.douai.module.ai.skills;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Static extraction from narrowly recognized API-document headings, tables and curl examples. */
final class SkillVideoReference {
    private static final String INVALID_CURL = "curl 示例包含无法静态核对的命令、参数或变量";
    private static final Pattern SLACK_LINK = Pattern.compile("(?i)<(https://[^\\s<>|]+)(?:\\|([^<>\\r\\n]*))?>");
    private static final Pattern MD_LINK = Pattern.compile("(?i)\\[([^\\]\\r\\n]+)\\]\\((https://[^\\s()]+)\\)");
    private static final Pattern CURL = Pattern.compile("^\\s*(?:\\$\\s+)?curl(?:\\.exe)?(?:\\s+|$)");
    private static final Pattern BEARER = Pattern.compile("(?i)^Bearer\\s+\\$(?:[A-Za-z_][A-Za-z0-9_]{0,63}"
            + "|\\{[A-Za-z_][A-Za-z0-9_]{0,63}\\})$");
    private static final Pattern BEARER_FORM_EXAMPLE = Pattern.compile(
            "(?i)^Bearer\\s+你自己的(?:\\s*<?[A-Za-z][A-Za-z0-9_]{0,32}_API_KEY>?)?$");
    private static final Pattern AUTH_LINE = Pattern.compile("(?i)^\\s*(?:[-*]\\s+)?authorization\\s*[:：]\\s*(.*)$");
    private static final Pattern CONTENT_TYPE_LINE = Pattern.compile(
            "(?i)^(?:[-*+]\\s+)?\\*{0,2}Content[- ]Type\\*{0,2}\\s*[:：]\\*{0,2}\\s*(.+)$");
    private static final Pattern QUOTED = Pattern.compile("^\\s*>\\s*>\\s*");
    private static final Pattern SUBMIT_LINE = Pattern.compile(
            "(?i)^(?:提交|提交接口|提交地址|submit)\\s*[:：]\\s*`?POST\\s+");
    private static final Pattern STATUS_LINE = Pattern.compile(
            "(?i)^(?:查询|查询接口|状态查询|status|statusroute)\\s*[:：]\\s*`?(GET|POST)\\s+");
    private static final Pattern SUBMITTED_STATUS_LINE = Pattern.compile(
            "(?i)^(?:查询响应地址字段|查询地址字段|提交响应查询地址字段|提交响应的查询地址字段|statusurlfield)\\s*[:：]");
    private static final Pattern CANCEL_LINE = Pattern.compile(
            "(?i)^(?:取消|取消接口|cancel)\\s*[:：]\\s*`?(?:POST|DELETE)\\s+");
    private static final Pattern DOWNLOAD_LINE = Pattern.compile(
            "(?i)^(?:下载|下载接口|download)\\s*[:：]\\s*`?GET\\s+");
    private static final Pattern SUBMIT_ID_SENTENCE = Pattern.compile(
            "^从返回结果中保存\\s*`([A-Za-z][A-Za-z0-9_.-]{0,130})`[，,]\\s*作为后续轮询用的\\s*"
                    + "`(?:video_id|task_id|job_id)`[。.]");
    private static final Pattern TASK_ID = Pattern.compile(
            "(?i)(?<=/)(?:\\{(?:id|video_?id|task_?id|job_?id)\\}|:(?:id|video_?id|task_?id|job_?id))(?=/|$)");
    private enum Section { NONE, CREATE, STATUS, CANCEL, DOWNLOAD }
    private enum Table { NONE, PARAMETERS, STATES, FORM }

    record Notes(String text, boolean documentedSubmit) {}
    private record Route(String method, String address) {}

    private final StringBuilder normalized = new StringBuilder();
    private final EnumMap<Section, Route> tables = new EnumMap<>(Section.class);
    private boolean documentedSubmit;
    private Section section = Section.NONE;
    private Table table = Table.NONE;

    private SkillVideoReference() {}

    static boolean recognizable(String raw) {
        boolean submit = false;
        boolean polling = false;
        boolean form = false;
        int formFields = 0;
        for (String line : documentLines(raw)) {
            String text = line.trim();
            if (text.startsWith("#") && heading(text) != Section.NONE) {
                String label = key(text);
                if (label.contains("视频") || label.contains("video")) return true;
            }
            Matcher start = SUBMIT_LINE.matcher(text);
            if (start.find()) {
                String address = referenceAddress(text.substring(start.end()));
                submit |= address.regionMatches(true, 0, "https://", 0, 8)
                        || address.startsWith("/") && !address.startsWith("//");
            }
            Matcher status = STATUS_LINE.matcher(text);
            if (status.find()) {
                String address = referenceAddress(text.substring(status.end()));
                polling |= "POST".equalsIgnoreCase(status.group(1))
                        ? address.startsWith("/") || address.regionMatches(true, 0, "https://", 0, 8)
                        : TASK_ID.matcher(address).find();
            }
            polling |= SUBMITTED_STATUS_LINE.matcher(text).find();
            if (text.isEmpty() || text.startsWith("#")) form = false;
            if (text.startsWith("|")) {
                List<String> row = cells(text);
                if (row.size() >= 2 && formHeader(row)) form = true;
                else if (form && row.size() >= 2 && !placeholder(row.get(1))) {
                    String label = formLabel(row.get(0));
                    if (!label.isEmpty()) formFields++;
                    submit |= "submit".equals(label);
                    polling |= List.of("status_get", "status_url", "status_post").contains(label);
                }
            }
            if (submit && polling || formFields >= 2 && (submit || polling)) return true;
        }
        return false;
    }

    private static String referenceAddress(String raw) {
        String text = raw.trim();
        if (text.endsWith("`")) text = text.substring(0, text.length() - 1);
        if (text.startsWith("`")) text = text.substring(1);
        Matcher slack = SLACK_LINK.matcher(text);
        if (slack.matches()) return slack.group(1);
        Matcher markdown = MD_LINK.matcher(text);
        return markdown.matches() ? markdown.group(2) : text;
    }

    static Notes normalize(String raw) {
        SkillVideoReference result = new SkillVideoReference();
        String[] lines = documentLines(raw);
        if (lines.length > 128) throw invalid();
        for (int index = 0; index < lines.length; index++) {
            String line = links(lines[index]);
            Matcher curl = CURL.matcher(line);
            if (curl.find()) {
                StringBuilder statement = new StringBuilder(line.substring(curl.end()).trim());
                while (continues(statement)) {
                    statement.setLength(statement.length() - 1);
                    if (++index >= lines.length) throw invalid();
                    statement.append(' ').append(links(lines[index]).trim());
                }
                result.curl(statement.toString());
                continue;
            }
            result.documentLine(line);
        }
        result.tables.forEach((role, route) -> {
            if (!route.method().isEmpty() && !route.address().isEmpty()) {
                result.operation(role, route.method(), route.address());
            }
        });
        return new Notes(result.normalized.toString(), result.documentedSubmit);
    }

    private static String[] documentLines(String raw) {
        String[] lines = raw.split("\\R", -1);
        for (String line : lines) {
            if (!line.trim().isEmpty() && !QUOTED.matcher(line).find()) return lines;
        }
        for (int index = 0; index < lines.length; index++) {
            lines[index] = QUOTED.matcher(lines[index]).replaceFirst("");
        }
        return lines;
    }

    private void documentLine(String line) {
        String text = line.trim();
        Matcher auth = AUTH_LINE.matcher(text);
        if (auth.matches() && safeBearerExample(plain(auth.group(1)))) return;
        Matcher contentType = CONTENT_TYPE_LINE.matcher(text);
        if (section == Section.CREATE && contentType.matches()) {
            SkillVideoFacts.checkSecrets(text);
            fact("提交请求编码", SkillVideoFacts.encoding(plain(contentType.group(1))));
            return;
        }
        if (text.startsWith("#")) {
            section = heading(text);
            table = Table.NONE;
        } else if (text.isEmpty()) {
            table = Table.NONE;
        } else if (text.startsWith("|")) {
            List<String> cells = cells(text);
            if (cells.size() >= 2 && "authorization".equals(key(cells.get(0)))
                    && safeBearerExample(cells.get(1))) return;
            SkillVideoFacts.checkSecrets(line);
            if (cells.size() >= 2) {
                SkillVideoFacts.checkSecrets(cells.get(0) + ": " + cells.get(1));
                if (formHeader(cells)) table = Table.FORM;
                else if (table == Table.FORM) formRow(cells);
                else if (section != Section.NONE) table(cells);
            }
            return;
        } else {
            Section operation = plainSection(text);
            if (operation != Section.NONE) {
                section = operation;
                table = Table.NONE;
            }
        }
        if (section == Section.CREATE) {
            Matcher id = SUBMIT_ID_SENTENCE.matcher(text);
            if (id.find()) fact("提交响应ID", id.group(1));
        }
        normalized.append(line).append('\n');
    }

    private static boolean safeBearerExample(String value) {
        return BEARER.matcher(value).matches() || BEARER_FORM_EXAMPLE.matcher(value).matches();
    }

    private static boolean formHeader(List<String> row) {
        return List.of("页面字段", "配置字段", "字段", "配置项").contains(key(row.get(0)))
                && List.of("填写内容", "填入内容", "填写值", "字段值", "值").contains(key(row.get(1)));
    }

    private static boolean placeholder(String value) {
        return value.isEmpty() || value.matches("^(?:[-—]+$|(?:先|暂)?留空.*|不填写.*|上面填.*|见下.*|未知.*|未提供.*)");
    }

    private static String formLabel(String raw) {
        return switch (key(raw).replaceFirst("[（(].*$", "")) {
            case "名称", "服务名称", "服务名" -> "label";
            case "提交post的公网https地址", "提交post公网https地址", "提交post地址",
                    "提交https地址", "提交接口地址", "提交地址", "提交post路径", "提交路径" -> "submit";
            case "提交请求编码", "提交编码", "提交请求格式", "contenttype" -> "encoding";
            case "提示词字段", "提示词参数" -> "prompt";
            case "提交响应任务id字段", "提交响应id字段", "提交任务id字段", "提交响应id" -> "submit_id";
            case "查询get路径", "查询路径", "状态查询路径" -> "status_get";
            case "查询post路径", "状态post路径" -> "status_post";
            case "查询地址字段", "查询响应地址字段", "提交响应查询地址字段" -> "status_url";
            case "查询post任务id字段", "查询请求id字段", "post查询请求任务id字段" -> "status_post_id";
            case "查询响应任务id字段", "查询响应id字段", "查询任务id字段" -> "status_id";
            case "查询响应状态字段", "查询状态字段", "状态字段" -> "status";
            case "状态映射" -> "states";
            case "状态映射中的完成状态", "完成状态值", "完成状态" -> "completed";
            case "查询响应成品https地址字段", "查询响应成品地址字段", "查询成片地址字段",
                    "成片地址字段", "成品地址字段" -> "result";
            case "下载get路径", "下载路径" -> "download";
            default -> "";
        };
    }

    private void formRow(List<String> row) {
        String raw = row.get(1);
        if (placeholder(raw)) return;
        String field = formLabel(row.get(0));
        if (field.isEmpty()) {
            if (section != Section.NONE) table(row);
            return;
        }
        if ("encoding".equals(field)) {
            String lower = raw.toLowerCase(Locale.ROOT);
            String encoding = lower.contains("multipart/form-data") ? SkillVideoFacts.MULTIPART_ENCODING
                    : lower.contains("application/x-www-form-urlencoded") ? SkillVideoFacts.URLENCODED_ENCODING
                    : lower.contains("application/json") ? SkillVideoFacts.JSON_ENCODING : SkillVideoFacts.encoding(raw);
            fact("提交请求编码", encoding);
            return;
        }
        String value = formLiteral(raw);
        if (value.isEmpty()) return;
        switch (field) {
            case "label" -> fact("服务名称", value);
            case "submit" -> operation(Section.CREATE, "POST", value);
            case "prompt" -> fact("提示词字段", value);
            case "submit_id" -> fact("提交响应ID", value);
            case "status_get" -> operation(Section.STATUS, "GET", value);
            case "status_post" -> operation(Section.STATUS, "POST", value);
            case "status_url" -> fact("查询响应地址字段", value);
            case "status_post_id" -> fact("查询请求ID字段", value);
            case "status_id" -> fact("查询响应ID", value);
            case "status" -> fact("状态字段", value);
            case "states" -> fact("状态映射", value);
            case "completed" -> {
                if (value.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) fact("状态映射", value + "=completed");
            }
            case "result" -> fact("成品地址字段", value);
            case "download" -> operation(Section.DOWNLOAD, "GET", value);
            default -> { }
        }
    }

    private static String formLiteral(String value) {
        if (!value.startsWith("`")) return value;
        int end = value.indexOf('`', 1);
        if (end <= 1 || value.indexOf('`', end + 1) >= 0) return "";
        return value.substring(1, end);
    }

    private static Section plainSection(String text) {
        if (SUBMIT_LINE.matcher(text).find()) return Section.CREATE;
        if (STATUS_LINE.matcher(text).find()) return Section.STATUS;
        if (CANCEL_LINE.matcher(text).find()) return Section.CANCEL;
        if (DOWNLOAD_LINE.matcher(text).find()) return Section.DOWNLOAD;
        return Section.NONE;
    }

    private void table(List<String> cells) {
        String left = cells.get(0);
        String right = cells.get(1);
        String field = key(left);
        if (left.matches("[:-]+")) return;
        if ((field.equals("参数") || field.equals("parameter") || field.equals("field"))
                && (right.equals("说明") || key(right).equals("类型") || key(right).equals("type"))) {
            table = Table.PARAMETERS;
            return;
        }
        if (section == Section.STATUS && List.of("状态", "状态值", "state", "status").contains(field)
                && List.of("含义", "说明", "meaning", "description").contains(key(right))) {
            table = Table.STATES;
            return;
        }
        if (table == Table.PARAMETERS) {
            if (section == Section.CREATE && ("prompt".equals(left) || "text_prompt".equals(left))) {
                fact("提示词字段", left);
            }
            return;
        }
        if (table == Table.STATES) {
            String state = stateDescription(right);
            if (!state.isEmpty() && left.matches("[A-Za-z0-9][A-Za-z0-9_.-]{0,31}")) {
                fact("状态映射", left + "=" + state);
            }
            return;
        }
        switch (field) {
            case "请求", "接口", "request", "operation" -> {
                String[] parts = right.split("\\s+", 2);
                if (parts.length == 2 && parts[0].matches("(?i)POST|GET|DELETE")) {
                    operation(section, parts[0], plain(parts[1]));
                }
            }
            case "方法", "请求方法", "method" -> addTableRoute(right, "");
            case "地址", "路径", "请求地址", "接口地址", "endpoint", "url", "route", "path" -> addTableRoute("", right);
            case "contenttype", "请求编码", "提交请求编码", "请求格式", "encoding" -> {
                if (section == Section.CREATE) fact("提交请求编码", SkillVideoFacts.encoding(right));
            }
            case "提示词字段", "提示词参数", "promptfield" -> {
                if (section == Section.CREATE) fact("提示词字段", right);
            }
            case "响应任务id字段", "响应id字段", "任务id字段", "任务id", "taskidfield", "responseid", "responseidfield" -> {
                if (section == Section.CREATE) fact("提交响应ID", right);
                if (section == Section.STATUS) fact("查询响应ID", right);
            }
            case "状态字段", "statefield", "statusfield" -> {
                if (section == Section.STATUS) fact("状态字段", right);
            }
            case "查询响应地址字段", "查询地址字段", "提交响应查询地址字段", "提交响应的查询地址字段", "statusurlfield" -> {
                if (section == Section.CREATE || section == Section.STATUS) fact("查询响应地址字段", right);
            }
            case "查询请求id字段", "查询postid字段", "查询post任务id字段", "statuspostidfield" -> {
                if (section == Section.STATUS) fact("查询 POST 任务 ID 字段", right);
            }
            case "状态映射", "statemap", "statusmap" -> {
                if (section == Section.STATUS) fact("状态映射", right);
            }
            case "成品地址字段", "查询结果字段", "resulturlfield" -> {
                if (section == Section.STATUS) fact("成品地址字段", right);
            }
            default -> { }
        }
    }

    private void addTableRoute(String method, String address) {
        Route previous = tables.getOrDefault(section, new Route("", ""));
        if (!previous.method().isEmpty() && !method.isEmpty() && !method.equalsIgnoreCase(previous.method())
                || !previous.address().isEmpty() && !address.isEmpty() && !address.equals(previous.address())) throw invalid();
        tables.put(section, new Route(method.isEmpty() ? previous.method() : method,
                address.isEmpty() ? previous.address() : address));
    }

    private void curl(String command) {
        List<String> words = words(command);
        String method = "";
        String url = "";
        String encoding = "";
        String statusPostId = "";
        boolean multipart = false;
        boolean body = false;
        List<String> prompts = new ArrayList<>();
        for (int index = 0; index < words.size(); index++) {
            String token = words.get(index);
            if (token.equals("-H") || token.equals("--header")) {
                String header = argument(words, ++index);
                int marker = header.indexOf(':');
                if (marker < 1) throw invalid();
                String name = key(header.substring(0, marker));
                String value = header.substring(marker + 1).trim();
                if (name.equals("authorization") && BEARER.matcher(value).matches()) continue;
                safe(header);
                if (name.equals("contenttype")) encoding = chooseEncoding(encoding, SkillVideoFacts.encoding(value));
                else if (!name.equals("accept")) throw invalid();
            } else if (token.equals("-X") || token.equals("--request")) {
                String explicit = safe(argument(words, ++index)).toUpperCase(Locale.ROOT);
                if (!method.isEmpty() && !method.equals(explicit)) throw invalid();
                method = explicit;
            } else if (token.equals("--url")) {
                if (!url.isEmpty()) throw invalid();
                url = safe(argument(words, ++index));
            } else if (token.equals("-F") || token.equals("--form") || token.equals("--form-string")) {
                String field = safe(argument(words, ++index));
                int equal = field.indexOf('=');
                if (equal < 1 || !field.substring(0, equal).matches("[A-Za-z][A-Za-z0-9_-]{0,31}")) throw invalid();
                addPrompt(prompts, field.substring(0, equal));
                multipart = true;
                body = true;
            } else if (List.of("-d", "--data", "--data-raw", "--data-binary", "--json").contains(token)) {
                String data = safe(argument(words, ++index));
                if (data.startsWith("@")) throw invalid();
                if (section == Section.STATUS && body) statusPostId = "";
                if (token.equals("--json")) encoding = chooseEncoding(encoding, SkillVideoFacts.JSON_ENCODING);
                if (data.startsWith("{")) {
                    JSONObject example = SkillAuthoring.readObject(data, 16_000, 192);
                    if (example.opt("prompt") instanceof String) addPrompt(prompts, "prompt");
                    if (example.opt("text_prompt") instanceof String) addPrompt(prompts, "text_prompt");
                    if (section == Section.STATUS && !body) statusPostId = statusExampleField(example);
                }
                body = true;
            } else if (List.of("-s", "-S", "-sS", "-Ss", "--silent", "--show-error").contains(token)) {
                // Output presentation flags have no bearing on the documented request.
            } else {
                safe(token);
                if (token.startsWith("-") || !url.isEmpty()) throw invalid();
                url = token;
            }
        }
        if (url.isEmpty()) throw invalid();
        if (method.isEmpty()) method = body ? "POST" : "GET";
        if (!List.of("POST", "GET", "DELETE").contains(method)) throw invalid();
        Section role = section == Section.NONE && method.equals("POST") ? Section.CREATE : section;
        if (role == Section.STATUS && method.equals("POST")) {
            if (multipart || !SkillVideoFacts.JSON_ENCODING.equals(encoding)) return;
            if (!statusPostId.isEmpty()) fact("查询 POST 任务 ID 字段", statusPostId);
        }
        if (role != Section.NONE) operation(role, method, url);
        if (role == Section.CREATE) {
            if (multipart) encoding = SkillVideoFacts.MULTIPART_ENCODING;
            else if (body && encoding.isEmpty()) encoding = SkillVideoFacts.URLENCODED_ENCODING;
            if (!encoding.isEmpty()) fact("提交请求编码", encoding);
            if (prompts.size() == 1) fact("提示词字段", prompts.get(0));
        }
    }

    private void operation(Section role, String method, String address) {
        String verb = method.toUpperCase(Locale.ROOT);
        if (address.isEmpty()) return;
        if (role == Section.CREATE) {
            if (!verb.equals("POST")) throw invalid();
            fact("提交", "POST " + address);
            documentedSubmit = true;
            return;
        }
        if (role == Section.STATUS && verb.equals("POST")) {
            fact("查询", "POST " + address);
            return;
        }
        if (role == Section.STATUS || role == Section.DOWNLOAD) {
            if (!verb.equals("GET")) throw invalid();
        } else if (role == Section.CANCEL && !verb.equals("POST") && !verb.equals("DELETE")) throw invalid();
        String template = TASK_ID.matcher(address).replaceAll("{id}");
        if (!template.contains("{id}")) return;
        if (role == Section.STATUS) fact("查询", "GET " + template);
        else if (role == Section.DOWNLOAD) fact("下载", "GET " + template);
        else if (role == Section.CANCEL) fact("取消", verb + " " + template);
    }

    private static String statusExampleField(JSONObject example) {
        if (example.length() != 1) return "";
        String field = example.keys().next();
        return example.opt(field) instanceof String && field.matches("[A-Za-z][A-Za-z0-9_-]{0,31}")
                && List.of("id", "taskid", "jobid", "videoid", "requestid").contains(key(field))
                ? field : "";
    }

    private void fact(String label, String value) {
        if (!value.trim().isEmpty()) normalized.append(label).append(": ").append(value).append('\n');
    }

    private static String safe(String value) {
        SkillVideoFacts.checkSecrets(value);
        if (value.indexOf('$') >= 0 || value.indexOf('`') >= 0 || value.indexOf('\n') >= 0) throw invalid();
        return value;
    }

    private static String argument(List<String> words, int index) {
        if (index >= words.size()) throw invalid();
        return words.get(index);
    }

    private static void addPrompt(List<String> found, String name) {
        if (("prompt".equals(name) || "text_prompt".equals(name)) && !found.contains(name)) found.add(name);
    }

    private static String chooseEncoding(String first, String second) {
        return first.isEmpty() ? second : SkillVideoFacts.mergeEncoding(first, second);
    }

    private static List<String> words(String command) {
        List<String> result = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        char quote = 0;
        for (int index = 0; index < command.length(); index++) {
            char c = command.charAt(index);
            if (c == '"' || c == '\'') {
                if (quote == c) { quote = 0; continue; }
                if (quote == 0) { quote = c; continue; }
            }
            if (c == '\\' && quote == '"' && index + 1 < command.length()
                    && (command.charAt(index + 1) == '"' || command.charAt(index + 1) == '\\')) {
                word.append(command.charAt(++index));
                continue;
            }
            if (quote == 0 && Character.isWhitespace(c)) {
                if (word.length() != 0) {
                    result.add(word.toString());
                    if (result.size() > 80) throw invalid();
                    word.setLength(0);
                }
            } else {
                if (quote == 0 && ";|&<>()\\#".indexOf(c) >= 0) throw invalid();
                word.append(c);
            }
        }
        if (quote != 0) throw invalid();
        if (word.length() != 0) result.add(word.toString());
        return result;
    }

    private static boolean continues(StringBuilder value) {
        return value.length() > 0 && value.charAt(value.length() - 1) == '\\';
    }

    private static Section heading(String text) {
        String heading = text.replaceFirst("^#+\\s*", "").replaceFirst("^\\d+[.、)]\\s*", "");
        String label = key(heading);
        if (label.startsWith("创建视频") || label.startsWith("提交视频") || label.startsWith("createvideo")
                || label.startsWith("submitvideo") || label.equals("创建") || label.equals("submit")) return Section.CREATE;
        if (label.startsWith("查询视频") || label.startsWith("查询任务") || label.startsWith("查询状态")
                || label.startsWith("getvideostatus") || label.startsWith("videostatus")
                || label.equals("查询") || label.equals("status")) return Section.STATUS;
        if (label.startsWith("取消视频") || label.startsWith("取消任务") || label.startsWith("cancelvideo")
                || label.equals("取消") || label.equals("cancel")) return Section.CANCEL;
        if (label.startsWith("下载视频") || label.startsWith("下载生成的视频")
                || label.startsWith("下载成片") || label.startsWith("downloadvideo")
                || label.equals("下载") || label.equals("download")) return Section.DOWNLOAD;
        return Section.NONE;
    }

    private static List<String> cells(String line) {
        String[] values = line.split("\\|", -1);
        List<String> cells = new ArrayList<>();
        int end = values.length - (values[values.length - 1].trim().isEmpty() ? 1 : 0);
        for (int index = 1; index < end; index++) cells.add(plain(values[index]));
        return cells;
    }

    private static String plain(String value) {
        String text = value.trim();
        while (text.length() > 1 && (text.charAt(0) == '`' || text.charAt(0) == '*')
                && text.charAt(0) == text.charAt(text.length() - 1)) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    private static String key(String label) {
        return plain(label).toLowerCase(Locale.ROOT).replaceAll("[\\s_*-]", "");
    }

    private static String links(String input) {
        return replaceLinks(replaceLinks(input, SLACK_LINK, 1, 2), MD_LINK, 2, 1);
    }

    private static String replaceLinks(String input, Pattern pattern, int hrefGroup, int labelGroup) {
        Matcher links = pattern.matcher(input);
        StringBuffer output = new StringBuffer();
        while (links.find()) {
            if (links.group(labelGroup) != null) SkillVideoFacts.checkSecrets(links.group(labelGroup));
            links.appendReplacement(output, Matcher.quoteReplacement(links.group(hrefGroup)));
        }
        return links.appendTail(output).toString();
    }

    private static String stateDescription(String value) {
        return switch (key(value)) {
            case "queued", "排队", "排队中", "等待中" -> "queued";
            case "running", "运行中", "运行", "处理中", "生成中" -> "running";
            case "completed", "已完成", "完成", "成功" -> "completed";
            case "failed", "失败", "已失败" -> "failed";
            case "cancelled", "canceled", "已取消", "取消" -> "cancelled";
            default -> "";
        };
    }

    private static IllegalArgumentException invalid() { return new IllegalArgumentException(INVALID_CURL); }
}
