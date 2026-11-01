package tracker.domain;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Names of the template Tasks an onboarding creates, by the Task name rule: {@code {anken_prefix}}, {@code {phase}} (the
 * template's phase) and {@code {task}} (the template's match without its leading "[...]" groups and its {@code *}).
 */
public final class TaskNames {
    private TaskNames() {
    }

    public static final String DEFAULT_RULE = "[{anken_prefix}][LocalTask] {task}";
    public static final List<String> TOKENS = List.of("anken_prefix", "phase", "task");
    private static final Pattern TOKEN = Pattern.compile("\\{([^}]*)}");
    private static final Pattern SPACES = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** @throws RuleError when the rule has an unknown token or no {@code {task}} */
    public static void check(String rule) {
        Matcher m = TOKEN.matcher(rule);
        while (m.find()) {
            if (!TOKENS.contains(m.group(1).toLowerCase(Locale.ROOT))) {
                throw new RuleError("Unknown placeholder {" + m.group(1) + "}: use {anken_prefix}, {phase} or {task}");
            }
        }
        if (!rule.contains("{task}")) {
            throw new RuleError("The Task name rule must contain {task}");
        }
    }

    public static String task(TaskTemplate t) {
        return clean(PhaseGroups.taskName(t.match().replace("*", "")));
    }

    public static String render(String rule, String ankenPrefix, TaskTemplate t) {
        return clean(rule.replace("{anken_prefix}", ankenPrefix.strip()).replace("{phase}", t.phase()).replace("{task}", task(t)));
    }

    /** The rule's name when the template recognises it; otherwise the template's match with each {@code *} as the prefix. */
    public static String propose(String rule, String ankenPrefix, TaskTemplate t, WorkStructure st) {
        String byRule = render(rule, ankenPrefix, t);
        return t.equals(st.taskTemplate(byRule)) ? byRule : clean(t.match().replace("*", ankenPrefix.strip()));
    }

    private static String clean(String s) {
        return SPACES.matcher(s.strip()).replaceAll(" ");
    }
}
