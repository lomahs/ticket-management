package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Sub-task Summary naming rule: placeholders are read as values, everything else must match literally. */
public final class Naming {
    private Naming() {
    }

    public static final Map<String, String> TOKENS = tokens();
    public static final String DEFAULT_RULE = "[{phase_name}][{function_id}] {function_name} – {step}";
    public static final Map<StepKind, String> DEFAULT_ALIASES = defaultAliases();

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{[a-z_]+\\}");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern PHASE = Pattern.compile("^\\s*\\[([^\\]]+)\\]", Pattern.UNICODE_CHARACTER_CLASS);

    private static Map<String, String> tokens() {
        Map<String, String> t = new LinkedHashMap<>();
        t.put("anken_name", "Anken name");
        t.put("os_task", "OS task");
        t.put("phase_name", "Phase");
        t.put("function_id", "Function ID");
        t.put("function_name", "Function name");
        t.put("step", "Step");
        return java.util.Collections.unmodifiableMap(t);
    }

    private static Map<StepKind, String> defaultAliases() {
        Map<StepKind, String> a = new EnumMap<>(StepKind.class);
        a.put(StepKind.CREATE, "Create");
        a.put(StepKind.BAL_REVIEW, "BAL Review");
        a.put(StepKind.FIX_BAL, "Fix comment BAL, Fix BAL");
        a.put(StepKind.JM_REVIEW, "JM Review");
        a.put(StepKind.FIX_JM, "Fix comment JM, Fix JM");
        return java.util.Collections.unmodifiableMap(a);
    }

    /**
     * @param tokens placeholders in the order they appear
     * @param steps  normalised alias → step
     */
    public record CompiledRule(Pattern regex, List<String> tokens, Map<String, StepKind> steps) {
    }

    public record Parsed(Map<String, String> values, StepKind step) {
    }

    private static boolean isSpace(int ch) {
        return Character.isWhitespace(ch) || Character.isSpaceChar(ch) || ch == 0x1c || ch == 0x1d || ch == 0x1e || ch == 0x1f;
    }

    private static String literal(int ch) {
        if (isSpace(ch)) {
            return "\\s*";
        }
        if (ch == '–' || ch == '-') {
            return "[–-]";
        }
        return Pattern.quote(new String(Character.toChars(ch)));
    }

    private static String literals(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().forEach(ch -> sb.append(literal(ch)));
        return sb.toString();
    }

    private static String norm(String s) {
        return WHITESPACE.matcher(s.toLowerCase(java.util.Locale.ROOT)).replaceAll("").replace("–", "-");
    }

    /** Group names may not contain underscores in Java regexes: tokens are named by their position in TOKENS. */
    private static String group(String token) {
        return "t" + new ArrayList<>(TOKENS.keySet()).indexOf(token);
    }

    /** Splits a rule into literal text and placeholders (kept), like re.split with a capturing group. */
    private static List<String> split(String rule) {
        List<String> parts = new ArrayList<>();
        Matcher m = PLACEHOLDER.matcher(rule);
        int at = 0;
        while (m.find()) {
            parts.add(rule.substring(at, m.start()));
            parts.add(m.group());
            at = m.end();
        }
        parts.add(rule.substring(at));
        return parts;
    }

    private static List<String> aliasNames(String names) {
        List<String> out = new ArrayList<>();
        for (String n : names.split(",", -1)) {
            if (!n.strip().isEmpty()) {
                out.add(n);
            }
        }
        return out;
    }

    /** {step} only matches a known alias, so a hyphen inside a function name cannot be mistaken for the separator. */
    public static CompiledRule compileRule(String rule, Map<StepKind, String> aliases) {
        Map<String, StepKind> steps = new LinkedHashMap<>();
        Set<String> nameSet = new LinkedHashSet<>();
        aliases.forEach((kind, names) -> {
            for (String n : aliasNames(names)) {
                steps.put(norm(n), kind);
                nameSet.add(n.strip());
            }
        });
        List<String> names = new ArrayList<>(nameSet);
        names.sort(Comparator.comparing(String::new)); // a deterministic base order before the length sort
        names.sort(Comparator.comparingInt((String n) -> n.codePointCount(0, n.length())).reversed());
        String stepSrc = names.isEmpty() ? "(?!)"
                : "(?iu:" + String.join("|", names.stream().map(Naming::literals).toList()) + ")";
        List<String> seen = new ArrayList<>();
        StringBuilder src = new StringBuilder();
        for (String part : split(rule)) {
            if (PLACEHOLDER.matcher(part).matches()) {
                String name = part.substring(1, part.length() - 1);
                if (!TOKENS.containsKey(name)) {
                    throw new RuleError("Unknown placeholder {" + name + "}");
                }
                if (seen.contains(name)) {
                    throw new RuleError("{" + name + "} is used twice");
                }
                seen.add(name);
                src.append(name.equals("step") ? "(?<" + group(name) + ">" + stepSrc + ")" : "(?<" + group(name) + ">.+?)");
            } else {
                src.append(literals(part));
            }
        }
        if (!seen.contains("step")) {
            throw new RuleError("Pattern must contain {step}");
        }
        Pattern regex = Pattern.compile("^\\s*" + src + "\\s*$", Pattern.UNICODE_CHARACTER_CLASS | Pattern.UNIX_LINES);
        return new CompiledRule(regex, List.copyOf(seen), steps);
    }

    public static Parsed parseSummary(CompiledRule rule, String summary) {
        Matcher m = rule.regex().matcher(summary);
        if (!m.lookingAt()) {
            return null;
        }
        Map<String, String> values = new LinkedHashMap<>();
        for (String token : rule.tokens()) {
            String v = m.group(group(token));
            if (v != null) {
                values.put(token, v);
            }
        }
        StepKind step = rule.steps().get(norm(values.get("step")));
        if (step == null) {
            throw new IllegalStateException("KeyError: " + norm(values.get("step")));
        }
        return new Parsed(values, step);
    }

    /** How many summaries match the rule. */
    public static int coverage(CompiledRule rule, Iterable<String> summaries) {
        int n = 0;
        for (String s : summaries) {
            if (parseSummary(rule, s) != null) {
                n++;
            }
        }
        return n;
    }

    /** Phase name from a parent ticket Summary such as "[Implement_Coding] Payment flow revision". */
    public static String parsePhase(String parentSummary) {
        Matcher m = PHASE.matcher(parentSummary);
        return m.lookingAt() ? m.group(1) : "";
    }

    /** Fills a rule with values; unknown or missing placeholders become empty. */
    public static String renderSummary(String rule, Map<String, String> values) {
        return PLACEHOLDER.matcher(rule).replaceAll(m -> Matcher.quoteReplacement(
                values.getOrDefault(m.group().substring(1, m.group().length() - 1), "")));
    }
}
