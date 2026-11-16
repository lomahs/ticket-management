package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Phase groups of an epic: a Task's group is the phase of its Task template in Phase & Task ({@link WorkStructure#trackedPhase}).
 * A Task that is not declared there has no group: the app does not track it.
 */
public final class PhaseGroups {
    private PhaseGroups() {
    }

    /** A leading run of bracket groups; "[^\]]" also eats unbalanced ones such as "[BookUI[LocalTask]". */
    private static final Pattern BRACKETS = Pattern.compile("^(\\s*\\[[^\\]]*\\])+", Pattern.UNICODE_CHARACTER_CLASS);

    /** The Task title without its leading brackets. */
    public static String taskName(String summary) {
        return BRACKETS.matcher(summary).replaceFirst("").strip();
    }

    /** Task key → phase group of the declared Tasks, in the order of the given Tasks. */
    public static Map<String, String> of(List<Issue> tasks, WorkStructure st) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Issue t : tasks) {
            String phase = st.trackedPhase(t.summary());
            if (phase != null) {
                out.put(t.key(), phase);
            }
        }
        return out;
    }

    /**
     * Configured phases first, in their order; then the other groups by the earliest planned start of their sub-tasks
     * (undated ones after), then by name.
     */
    public static List<String> ordered(Map<String, List<Issue>> subtasksByGroup, List<String> configuredPhases) {
        Map<String, String> start = new HashMap<>();
        subtasksByGroup.forEach((g, subs) -> subs.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo)
                .ifPresent(s -> start.put(g, s)));
        List<String> out = new ArrayList<>(subtasksByGroup.keySet());
        out.sort(Comparator.comparing((String g) -> configuredPhases.contains(g) ? configuredPhases.indexOf(g) : configuredPhases.size())
                .thenComparing(g -> !start.containsKey(g))
                .thenComparing(g -> start.getOrDefault(g, ""))
                .thenComparing(g -> g));
        return out;
    }
}
