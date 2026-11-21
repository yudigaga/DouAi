package com.douai.module.ai.skills;

import com.douai.module.ai.mcp.McpRegistry;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Directory and dispatcher for explicit commands in manual chats. */
public final class AiSkills {
    public enum Entry { CONTACT, WORK }

    public record Command(String name, String description) {}

    public static final class CommandUnavailable extends IllegalStateException {
        CommandUnavailable(String message) { super(message); }
    }

    public record Invocation(Command command, String argument, int version, McpRegistry.Binding mcpBinding) {
        Invocation(Command command, String argument) { this(command, argument, 0, null); }
        Invocation(Command command, String argument, int version) { this(command, argument, version, null); }
        public boolean localBuiltIn() { return version == 0 && mcpBinding == null; }
    }

    private interface Action {
        String run(String argument);
    }

    private record Skill(Command command, EnumSet<Entry> entries, Action action) {}

    private static final String SPACE = "[\\s\\p{Z}]";
    private static final Pattern SPACE_PATTERN = Pattern.compile(SPACE);
    private static final Pattern COMMAND = Pattern.compile("^" + SPACE
            + "*/([^\\s\\p{Z}/]+)(?:" + SPACE + "+(.*))?$", Pattern.DOTALL);
    private static final Pattern SENTENCE_END = Pattern.compile("[。！？!?]+|[.]+(?=\\s|$)|[\\r\\n]+");
    private static final Pattern ORDER_NUMBER = Pattern.compile("[A-Za-z0-9_-]{1,48}");

    private static final List<Skill> BUILT_INS = Arrays.asList(
            new Skill(new Command("摘要", "提取纯文本中的前两句"), EnumSet.allOf(Entry.class),
                    AiSkills::summarize),
            new Skill(new Command("查询", "查询模拟订单，例如 DA-1001"), EnumSet.allOf(Entry.class),
                    AiSkills::lookupOrder)
    );

    private AiSkills() {}

    public static List<Command> commands(Entry entry, String input) {
        return commands(entry, input, null);
    }

    public static List<Command> commands(Entry entry, String input, SkillStore store) {
        if (entry == null || input == null) return Collections.emptyList();
        String draft = input.replaceFirst("^" + SPACE + "+", "");
        if (!draft.startsWith("/")) return Collections.emptyList();
        String prefix = draft.substring(1);
        if (SPACE_PATTERN.matcher(prefix).find()) return Collections.emptyList();

        if (store != null && !store.enabled()) return Collections.emptyList();
        List<Command> matches = new ArrayList<>();
        for (Skill skill : BUILT_INS) {
            if (skill.entries().contains(entry) && skill.command().name().startsWith(prefix)) {
                matches.add(skill.command());
            }
        }
        if (store != null) {
            for (SkillStore.Available skill : store.available()) {
                if (skill.command().name().startsWith(prefix)) matches.add(skill.command());
            }
            if (store.mcp() != null && ("mcp-".startsWith(prefix) || prefix.startsWith("mcp-"))) {
                try {
                    for (McpRegistry.Binding binding : store.mcp().activeBindings()) {
                        if (binding.alias().startsWith(prefix)) matches.add(mcpCommand(binding));
                    }
                } catch (Exception unavailable) {
                    // Keep local skills available when the separate MCP registry fails to load.
                }
            }
        }
        return matches;
    }

    public static Invocation match(Entry entry, String input) {
        return match(entry, input, null);
    }

    public static Invocation match(Entry entry, String input, SkillStore store) {
        if (entry == null || input == null) return null;
        Matcher matcher = COMMAND.matcher(input);
        if (!matcher.matches() || (store != null && !store.enabled())) return null;
        String argument = matcher.group(2);
        argument = argument == null ? "" : argument.replaceFirst(SPACE + "+$", "");

        for (Skill skill : BUILT_INS) {
            if (skill.entries().contains(entry) && skill.command().name().equals(matcher.group(1))) {
                return new Invocation(skill.command(), argument);
            }
        }
        if (store != null) {
            for (SkillStore.Available skill : store.available()) {
                if (skill.command().name().equals(matcher.group(1))) {
                    return new Invocation(skill.command(), argument, skill.version());
                }
            }
            if (store.mcp() != null && matcher.group(1).startsWith("mcp-")) {
                try {
                    for (McpRegistry.Binding binding : store.mcp().bindings()) {
                        if (binding.alias().equals(matcher.group(1))) {
                            return new Invocation(mcpCommand(binding), argument, 0, binding);
                        }
                    }
                } catch (Exception unavailable) {
                    throw new CommandUnavailable("MCP 绑定无法读取");
                }
                throw new CommandUnavailable("MCP 命令未登记");
            }
        }
        return null;
    }

    private static Command mcpCommand(McpRegistry.Binding binding) {
        return new Command(binding.alias(), "MCP 工具 · JSON 参数");
    }

    static boolean isBuiltIn(String name) {
        for (Skill skill : BUILT_INS) {
            if (skill.command().name().equals(name)) return true;
        }
        return false;
    }

    public static String execute(Invocation call, SkillStore store) throws Exception {
        if (store != null && !store.enabled()) throw new IllegalStateException("技能已停用");
        if (call.mcpBinding() != null) {
            if (store == null || store.mcp() == null) throw new IllegalStateException("MCP 绑定不可用");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
            return store.mcp().execute(call.mcpBinding(), call.argument(), deadline);
        }
        if (call.localBuiltIn()) return execute(call);
        if (store == null) throw new IllegalStateException("技能不可用");
        return store.execute(call.command().name(), call.version(), call.argument());
    }

    public static String execute(Invocation call) {
        if (call.argument().isEmpty() || call.argument().length() > 16000) {
            throw new IllegalArgumentException("技能参数为空或过长");
        }
        for (Skill skill : BUILT_INS) {
            if (skill.command().equals(call.command())) return skill.action().run(call.argument());
        }
        throw new IllegalArgumentException("技能不可用");
    }

    static String summarize(String argument) {
        Matcher boundaries = SENTENCE_END.matcher(argument);
        int summaryEnd = boundaries.find() && boundaries.find() ? boundaries.end() : argument.length();
        if (summaryEnd <= 240) return argument.substring(0, summaryEnd).trim();
        int end = Character.isHighSurrogate(argument.charAt(239)) ? 239 : 240;
        return argument.substring(0, end).trim() + "…";
    }

    static String lookupOrder(String argument) {
        if (!ORDER_NUMBER.matcher(argument).matches()) {
            throw new IllegalArgumentException("订单号只能包含字母、数字、短横线或下划线");
        }
        return "DA-1001".equalsIgnoreCase(argument)
                ? "模拟查询：订单 DA-1001 已发货。"
                : "模拟查询：未找到订单 " + argument + "。";
    }
}
