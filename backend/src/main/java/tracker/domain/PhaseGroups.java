package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * Phase groups of an epic, read from its Task titles "[anken][prefix] ITA Prepare": the first word of the name after the
 * brackets ("ITA") is a phase when two or more Tasks share it; every other Task is in {@link #OTHER}.
 * What the admin configured comes first: a matching Task template's phase, then a leading "[Phase]" naming a configured phase.
 */
public final class PhaseGroups {
    private PhaseGroups() {
    }

    public static final String OTHER = "Other";

    /** A leading run of bracket groups; "[^\]]" also eats unbalanced ones such as "[BookUI[LocalTask]". */
    private static final Pattern BRACKETS = Pattern.compile("^(\\s*\\[[^\\]]*\\])+", Pattern.UNICODE_CHARACTER_CLASS);
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    /** The Task title without its leading brackets. */
    public static String taskName(String summary) {
        return BRACKETS.matcher(summary).replaceFirst("").strip();
    }

    private static String keyWord(String summary) {
        String name = taskName(summary);
        return name.isEmpty() ? "" : WHITESPACE.split(name, 2)[0];
    }

    /** The phase the admin configured for this Task, or null. */
    private static @Nullable String configured(String summary, WorkStructure st) {
        TaskTemplate tpl = st.taskTemplate(summary);
        if (tpl != null) {
            return tpl.phase();
        }
        String bracket = Naming.parsePhase(summary);
        return st.phases().contains(bracket) ? bracket : null;
    }

    /** Task key → phase group, in the order of the given Tasks. */
    public static Map<String, String> of(List<Issue> tasks, WorkStructure st) {
        Map<String, Integer> count = new HashMap<>();
        Map<String, String> spelling = new HashMap<>();
        for (Issue t : tasks) {
            String word = keyWord(t.summary());
            if (configured(t.summary(), st) == null && !word.isEmpty()) {
                String k = word.toLowerCase(Locale.ROOT);
                count.merge(k, 1, Integer::sum);
                spelling.putIfAbsent(k, word);
            }
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Issue t : tasks) {
            String phase = configured(t.summary(), st);
            String k = keyWord(t.summary()).toLowerCase(Locale.ROOT);
            out.put(t.key(), phase != null ? phase : count.getOrDefault(k, 0) >= 2 ? spelling.get(k) : OTHER);
        }
        return out;
    }

    /**
     * Configured phases first, in their order; then the other groups by the earliest planned start of their sub-tasks
     * (undated ones after), then by name; {@link #OTHER} last.
     */
    public static List<String> ordered(Map<String, List<Issue>> subtasksByGroup, List<String> configuredPhases) {
        Map<String, String> start = new HashMap<>();
        subtasksByGroup.forEach((g, subs) -> subs.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo)
                .ifPresent(s -> start.put(g, s)));
        List<String> out = new ArrayList<>(subtasksByGroup.keySet());
        out.sort(Comparator.comparing((String g) -> g.equals(OTHER))
                .thenComparing(g -> configuredPhases.contains(g) ? configuredPhases.indexOf(g) : configuredPhases.size())
                .thenComparing(g -> !start.containsKey(g))
                .thenComparing(g -> start.getOrDefault(g, ""))
                .thenComparing(g -> g));
        return out;
    }
}
