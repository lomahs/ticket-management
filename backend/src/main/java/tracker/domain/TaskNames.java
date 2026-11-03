package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Task Summaries by the Task pattern (Settings › Name rules › Phase): {@code {anken_prefix}}, {@code {os_task}} (always
 * {@link #OS_TASK} for now) and {@code {sub_phase}} (the template's match without its leading "[...]" groups and its {@code *}).
 */
public final class TaskNames {
    private TaskNames() {
    }

    public static final String DEFAULT_RULE = "[{anken_prefix}][{os_task}] {sub_phase}";
    public static final String OS_TASK = "LocalTask";
    public static final List<String> TOKENS = List.of("anken_prefix", "os_task", "sub_phase");
    private static final Pattern TOKEN = Pattern.compile("\\{([^}]*)}");
    private static final Pattern LOCAL_TASK = Pattern.compile("\\[LocalTask]", Pattern.CASE_INSENSITIVE);
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** @throws RuleError when the rule has an unknown or repeated token, or no {@code {sub_phase}} */
    public static void check(String rule) {
        Matcher m = TOKEN.matcher(rule);
        List<String> seen = new ArrayList<>();
        while (m.find()) {
            String name = m.group(1).toLowerCase(Locale.ROOT);
            if (!TOKENS.contains(name)) {
                throw new RuleError("Unknown placeholder {" + m.group(1) + "}: use {anken_prefix}, {os_task} or {sub_phase}");
            }
            if (seen.contains(name)) {
                throw new RuleError("{" + name + "} is used twice");
            }
            seen.add(name);
        }
        if (!seen.contains("sub_phase")) {
            throw new RuleError("The Task pattern must contain {sub_phase}");
        }
    }

    public static String subPhase(TaskTemplate t) {
        return clean(PhaseGroups.taskName(t.match().replace("*", "")));
    }

    public static String render(String rule, String ankenPrefix, TaskTemplate t) {
        return clean(rule.replace("{anken_prefix}", ankenPrefix.strip()).replace("{os_task}", OS_TASK).replace("{sub_phase}", subPhase(t)));
    }

    /**
     * The template match that recognises the rule's name of {@code task} in every anken: {@code {anken_prefix}} and
     * {@code {os_task}} become {@code *}, and so does the {@code LocalTask} in {@code [LocalTask]}.
     */
    public static String match(String rule, String task) {
        String m = rule.replace("{anken_prefix}", "*").replace("{os_task}", "*").replace("{sub_phase}", task.strip());
        return clean(LOCAL_TASK.matcher(m).replaceAll("[*]"));
    }

    /** Whether the template recognises the rule's name of it. */
    public static boolean recognised(String rule, String ankenPrefix, TaskTemplate t, WorkStructure st) {
        return t.equals(st.taskTemplate(render(rule, ankenPrefix, t)));
    }

    /** Placeholders become lazy groups named t0, t1… by their place in {@link #TOKENS}; the rest matches literally. */
    private static Pattern compile(String rule) {
        StringBuilder src = new StringBuilder();
        Matcher m = TOKEN.matcher(rule);
        int at = 0;
        while (m.find()) {
            src.append(Naming.literals(rule.substring(at, m.start())));
            src.append("(?<t").append(TOKENS.indexOf(m.group(1).toLowerCase(Locale.ROOT))).append(">.+?)");
            at = m.end();
        }
        src.append(Naming.literals(rule.substring(at)));
        return Pattern.compile("^\\s*" + src + "\\s*$", Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    /** The placeholders' values in a Task Summary; null when it does not match the rule (or the rule is invalid). */
    public static @Nullable Map<String, String> parse(String rule, String summary) {
        try {
            check(rule);
        } catch (RuleError e) {
            return null;
        }
        Matcher m = compile(rule).matcher(summary);
        if (!m.matches()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < TOKENS.size(); i++) {
            if (rule.contains("{" + TOKENS.get(i) + "}")) {
                values.put(TOKENS.get(i), m.group("t" + i).strip());
            }
        }
        return values;
    }

    public static boolean matches(String rule, String summary) {
        return parse(rule, summary) != null;
    }

    /** The {@code {anken_prefix}} of a Task Summary; "" when the Summary does not match or the rule has none. */
    public static String ankenPrefix(String rule, String summary) {
        Map<String, String> v = parse(rule, summary);
        return v == null ? "" : v.getOrDefault("anken_prefix", "");
    }

    private static String clean(String s) {
        return SPACES.matcher(s.strip()).replaceAll(" ");
    }
}
