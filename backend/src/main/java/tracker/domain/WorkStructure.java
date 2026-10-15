package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * The project structure an admin configures: the phases in order, and the fixed set of Jira Tasks every epic carries.
 * Without templates a Task's phase comes from a leading "[Phase]" in its Summary, as before templates existed.
 */
public record WorkStructure(List<String> phases, List<TaskTemplate> tasks) {
    private static final Pattern WHITESPACE = Pattern.compile("\\s+", Pattern.UNICODE_CHARACTER_CLASS);

    public WorkStructure {
        phases = phases == null ? List.of() : List.copyOf(phases);
        tasks = tasks == null ? List.of() : List.copyOf(tasks);
    }

    public static WorkStructure empty() {
        return new WorkStructure(List.of(), List.of());
    }

    /** Lower case, trimmed, runs of whitespace as one space: how Task names are compared. */
    public static String normalise(String s) {
        return WHITESPACE.matcher(s.strip().toLowerCase(Locale.ROOT)).replaceAll(" ");
    }

    private static Pattern pattern(String match) {
        List<String> parts = new ArrayList<>();
        for (String part : normalise(match).split("\\*", -1)) {
            parts.add(Pattern.quote(part));
        }
        return Pattern.compile(String.join(".*", parts), Pattern.DOTALL);
    }

    /** The first template whose pattern matches the whole Task Summary. */
    public @Nullable TaskTemplate taskTemplate(String summary) {
        String s = normalise(summary);
        return tasks.stream().filter(t -> pattern(t.match()).matcher(s).matches()).findFirst().orElse(null);
    }

    /** The index of {@link #taskTemplate}, or -1. */
    public int templateIndex(String summary) {
        TaskTemplate t = taskTemplate(summary);
        return t == null ? -1 : tasks.indexOf(t);
    }

    /** The phase from the matching template; otherwise a leading "[Phase]" in the Summary. */
    public String phaseOfTask(String summary) {
        TaskTemplate t = taskTemplate(summary);
        return t != null ? t.phase() : Naming.parsePhase(summary);
    }

    /** Whether the sub-tasks of a Task with this Summary can be function steps. */
    public boolean hasWorkflow(String summary) {
        TaskTemplate t = taskTemplate(summary);
        return t == null || t.workflow();
    }

    /** Configured phases first, in their order, then any other phase found on tickets, alphabetically. */
    public List<String> orderedPhases(Iterable<String> names) {
        Set<String> all = new LinkedHashSet<>();
        names.forEach(all::add);
        List<String> out = new ArrayList<>(phases.stream().filter(all::contains).toList());
        out.addAll(all.stream().filter(n -> !phases.contains(n)).sorted().toList());
        return out;
    }
}
