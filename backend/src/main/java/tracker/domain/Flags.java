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

    /** Status weights over the function's team steps (JM Review left out; a missing step weighs 0), as a percentage. */
    public static int progressPct(Function fn) {
        double weights = 0;
        int team = 0;
        for (int i = 0; i < fn.stepCount(); i++) {
            if (Steps.STEPS.get(i).kind() == StepKind.JM_REVIEW) {
                continue;
            }
            team++;
            Issue c = fn.cells().get(i);
            weights += c != null ? PROGRESS_WEIGHT.get(c.status()) : 0;
        }
        return (int) Math.round(weights / team * 100);
    }

    public static Status currentStatus(Function fn) {
        if (fn.done()) {
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
        if (fn.done()) {
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

    /**
     * The legacy worker, kept for Planning and Capacity only: the reviewer on review steps, the function owner on other steps,
     * else the assignee. Every other number reads {@link Effort#planOwner}.
     */
    public static String workerOf(Issue issue, String ownerId) {
        StepDef step = Steps.stepDef(issue);
        if (step != null && !step.review() && Text.hasText(ownerId)) {
            return ownerId;
        }
        return issue.assigneeId();
    }

    /** The tone of a card, from its status and schedule labels; the first matching rule wins. */
    private static String tone(Issue i, IssueSchedule s) {
        if (i.status() == Status.RESOLVED) {
            return "res";
        }
        if (i.status() == Status.CLOSED) {
            return "clo";
        }
        if (s.finish() == FinishState.OVERDUE) {
            return "delay";
        }
        if (s.start() == StartState.LATE_START) {
            return "latestart";
        }
        if (s.start() == StartState.EARLY_START) {
            return "early";
        }
        if (i.status() == Status.NYS) {
            return "nys";
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

    /**
     * Review rounds of a Fix comment step: 1 + the runs of consecutive worklogs by a BAL, in date order (same-day worklogs in
     * sync order); 0 while it is NYS without a worklog. 0 for any other step.
     */
    public static int rounds(Issue i, Map<String, Member> members) {
        if (!Steps.isFixStep(i)) {
            return 0;
        }
        if (i.status() == Status.NYS && i.worklogs().isEmpty()) {
            return 0;
        }
        List<Worklog> logs = i.worklogs().stream().sorted(java.util.Comparator.comparing(Worklog::date)).toList();
        int runs = 0;
        boolean inRun = false;
        for (Worklog w : logs) {
            boolean bal = Effort.roleOf(w, members) == Role.BAL;
            if (bal && !inRun) {
                runs++;
            }
            inRun = bal;
        }
        return 1 + runs;
    }

    /** Late is the schedule's {@code overdue} finish; {@code soon} applies only to an active step that is not late. */
    public static IssueFlags issueFlags(Issue issue, boolean isCurrent, Map<String, Member> members, String today, Thresholds thresholds,
                                        IssueSchedule schedule) {
        boolean active = issue.status() == Status.WIP || (issue.status() == Status.NYS && isCurrent);
        String pe = issue.plannedEnd();
        boolean late = schedule.finish() == FinishState.OVERDUE;
        boolean soon = !late && active && Text.hasText(pe) && pe.compareTo(today) >= 0
                && Dates.weekdaysAfter(today, pe) <= thresholds.soonDays();
        return new IssueFlags(
                isCurrent,
                late,
                late ? schedule.finishDays() : 0,
                soon,
                issue.loggedH() > issue.estimateH(),
                tone(issue, schedule),
                held(issue, today),
                rounds(issue, members));
    }

    private static String earlier(String a, String b) {
        return a == null || (b != null && b.compareTo(a) < 0) ? b : a;
    }

    /**
     * The day work began: when the issue first left NYS (moved to In Progress, or straight to Resolved/Closed), or its
     * first worklog when that is earlier or the changelog has no such move. null when neither exists.
     */
    public static String actualStart(Issue issue) {
        String moved = issue.events().stream()
                .filter(e -> e.field() == EventField.STATUS && e.toValue() != null && !e.toValue().equals(Status.NYS.value()))
                .map(IssueEvent::at).min(String::compareTo).orElse(null);
        String logged = issue.worklogs().stream().map(Worklog::date).min(String::compareTo).orElse(null);
        return earlier(moved, logged);
    }

    /**
     * Actual start and end. Start: {@link #actualStart}. Only a Resolved/Closed issue has an end: the day it was last
     * resolved (else closed), else its last worklog. null before work began; the end is null while the issue is not done.
     */
    public static String[] actualSpan(Issue issue) {
        String start = actualStart(issue);
        if (start == null) {
            return null;
        }
        if (issue.status() != Status.RESOLVED && issue.status() != Status.CLOSED) {
            return new String[]{start, null};
        }
        String resolved = lastEvent(issue, EventField.STATUS, Status.RESOLVED.value());
        String closed = lastEvent(issue, EventField.STATUS, Status.CLOSED.value());
        String lastLog = issue.worklogs().stream().map(Worklog::date).max(String::compareTo).orElse(null);
        String end = resolved != null ? resolved : closed != null ? closed : lastLog != null ? lastLog : start;
        return new String[]{start, end.compareTo(start) < 0 ? start : end};
    }
}
