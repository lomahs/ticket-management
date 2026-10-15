package tracker.domain;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Function grouping and per-issue flags: current step, late/soon/over, tone, held days, review rounds. */
public final class Flags {
    private Flags() {
    }

    public static final Map<Status, Double> PROGRESS_WEIGHT = new EnumMap<>(Map.of(
            Status.CLOSED, 1.0, Status.RESOLVED, 0.8, Status.WIP, 0.4, Status.NYS, 0.0));

    /** Groups function sub-tasks by (epic, parent Task, function id), ordered by first appearance; non-function issues are ignored. */
    public static List<Function> buildFunctions(Iterable<Issue> issues) {
        Map<List<String>, Issue[]> groups = new LinkedHashMap<>();
        Map<List<String>, String> names = new LinkedHashMap<>();
        for (Issue i : issues) {
            if (i.functionId() == null || i.step() == null) {
                continue;
            }
            List<String> k = List.of(i.epicKey(), Text.orElse(i.parentKey(), ""), i.functionId());
            Issue[] slots = groups.computeIfAbsent(k, x -> new Issue[Steps.COUNT]);
            slots[Steps.index(i.step())] = i;
            names.putIfAbsent(k, Text.orElse(i.functionName(), i.functionId()));
        }
        List<Function> out = new ArrayList<>();
        groups.forEach((k, cells) -> out.add(new Function(k.get(0), k.get(1), k.get(2), names.get(k), Arrays.asList(cells))));
        return out;
    }

    public static int progressPct(Function fn) {
        double weights = fn.issues().stream().mapToDouble(c -> PROGRESS_WEIGHT.get(c.status())).sum();
        return (int) Math.round(weights / Steps.COUNT * 100);
    }

    public static Status currentStatus(Function fn) {
        if (fn.current() >= Steps.COUNT) {
            return Status.CLOSED;
        }
        Issue c = fn.cells().get(fn.current());
        return c != null ? c.status() : Status.NYS;
    }

    /** Summary bucket: N (not started), W (in progress) or D (current step resolved/closed). */
    public static String groupStatus(Function fn) {
        Status s = currentStatus(fn);
        return s == Status.RESOLVED || s == Status.CLOSED ? "D" : s.value();
    }

    public static String functionState(Function fn, Set<String> lateKeys) {
        if (fn.current() >= Steps.COUNT) {
            return "done";
        }
        if (fn.issues().stream().allMatch(c -> c.status() == Status.NYS)) {
            return "nys";
        }
        if (fn.issues().stream().anyMatch(c -> lateKeys.contains(c.key()))) {
            return "delay";
        }
        return "ontime";
    }

    /** late / early / ontime: compares finished steps with steps whose plan has already ended. */
    public static String functionProgress(Function fn, String today, Set<String> lateKeys) {
        if (fn.issues().stream().anyMatch(c -> lateKeys.contains(c.key()))) {
            return "late";
        }
        long due = fn.issues().stream().filter(c -> c.plannedEnd() != null && !c.plannedEnd().isEmpty()
                && c.plannedEnd().compareTo(today) < 0).count();
        long done = fn.issues().stream().filter(c -> c.status() == Status.CLOSED || c.status() == Status.RESOLVED).count();
        return done > due ? "early" : "ontime";
    }

    /** Who does the work: the reviewer on review steps, the function owner on other steps, else the assignee. */
    public static String workerOf(Issue issue, String ownerId) {
        StepDef step = Steps.stepDef(issue);
        if (step != null && !step.review() && Text.hasText(ownerId)) {
            return ownerId;
        }
        return issue.assigneeId();
    }

    private static String tone(Issue i, String today) {
        if (i.status() == Status.NYS) {
            return "nys";
        }
        if (i.status() == Status.RESOLVED) {
            return "res";
        }
        if (i.status() == Status.CLOSED) {
            return "clo";
        }
        if (Text.hasText(i.plannedEnd()) && i.plannedEnd().compareTo(today) < 0) {
            return "delay";
        }
        String first = i.worklogs().stream().map(Worklog::date).min(String::compareTo).orElse(null);
        if (first != null && Text.hasText(i.plannedStart())) {
            if (first.compareTo(i.plannedStart()) > 0) {
                return "latestart";
            }
            if (first.compareTo(i.plannedStart()) < 0) {
                return "early";
            }
        }
        return "ontime";
    }

    private static String lastEvent(Issue i, EventField field, String to) {
        return i.events().stream()
                .filter(e -> e.field() == field && (to == null || to.equals(e.toValue())))
                .map(IssueEvent::at).max(String::compareTo).orElse(null);
    }

    /** Working days the issue has been with its current assignee (since it was resolved, when resolved). */
    private static int held(Issue i, String today) {
        if (i.status() == Status.CLOSED) {
            return 0;
        }
        String since = i.status() == Status.RESOLVED ? lastEvent(i, EventField.STATUS, Status.RESOLVED.value()) : null;
        if (!Text.hasText(since)) {
            since = lastEvent(i, EventField.ASSIGNEE, null);
        }
        return Text.hasText(since) ? Dates.weekdaysAfter(since, today) : 0;
    }

    private static int rounds(Issue i, String ownerId) {
        if (!Steps.isFixStep(i)) {
            return 0;
        }
        int n = (int) i.events().stream()
                .filter(e -> e.field() == EventField.ASSIGNEE && java.util.Objects.equals(e.toValue(), ownerId)
                        && e.fromValue() != null && !e.fromValue().equals(ownerId))
                .count();
        if (n != 0) {
            return n;
        }
        return i.status() == Status.NYS ? 0 : 1;
    }

    public static IssueFlags issueFlags(Issue issue, boolean isCurrent, String ownerId, String today, Thresholds thresholds) {
        boolean active = issue.status() == Status.WIP || (issue.status() == Status.NYS && isCurrent);
        String pe = issue.plannedEnd();
        boolean late = active && Text.hasText(pe) && pe.compareTo(today) < 0;
        boolean soon = !late && active && Text.hasText(pe) && pe.compareTo(today) >= 0
                && Dates.weekdaysAfter(today, pe) <= thresholds.soonDays();
        return new IssueFlags(
                isCurrent,
                late,
                late ? Dates.weekdaysAfter(pe, today) : 0,
                soon,
                issue.loggedH() > issue.estimateH(),
                tone(issue, today),
                held(issue, today),
                rounds(issue, ownerId));
    }

    /** First and last worklog day; an issue still in progress is drawn up to today. null without worklogs. */
    public static String[] actualSpan(Issue issue, String today) {
        List<String> dates = issue.worklogs().stream().map(Worklog::date).sorted().toList();
        if (dates.isEmpty()) {
            return null;
        }
        String last = dates.get(dates.size() - 1);
        String end = issue.status() == Status.WIP && last.compareTo(today) < 0 ? today : last;
        return new String[]{dates.get(0), end};
    }
}
