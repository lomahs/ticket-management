package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Anken (epic) roll-ups: progress per phase, forecast and the issues that need attention. */
public final class Overview {
    private Overview() {
    }

    /**
     * @param schedule the date and effort roll-up of the phase's sub-tasks
     * @param pct      time spent over time spent plus remaining, like Jira's progress bar
     */
    public record PhaseRollup(String name, int pct, int closed, int total, @Nullable String start, @Nullable String end,
                              double spentH, double remainingH, ScheduleRollup schedule) {
    }

    public enum Schedule { UNKNOWN, NOT_STARTED, EARLY, ON_TRACK, LATE, DONE }

    /**
     * Schedule labels of a set of sub-tasks added up. {@code planToDateH}, {@code actualToDateH} and {@code diffH} skip sub-tasks
     * with no plan or no estimate; {@code effort} is judged on their sum with the same threshold as one sub-task.
     */
    public record ScheduleRollup(double planToDateH, double actualToDateH, double diffH, EffortState effort, int overdue,
                                 int lateStart, int earlyStart, int lateFinish, int earlyFinish, Schedule state) {
    }

    /** Progress of a phase from its sub-tasks' effort: spent / (spent + remaining), plus its schedule roll-up. */
    public static PhaseRollup phaseRollup(String name, List<Issue> subtasks, Map<String, IssueSchedule> schedules, double thresholdH) {
        String start = subtasks.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo).orElse(null);
        String end = subtasks.stream().map(Issue::plannedEnd).filter(Text::hasText).max(String::compareTo).orElse(null);
        double spent = Numbers.round(subtasks.stream().mapToDouble(Issue::loggedH).sum(), 2);
        double remaining = Numbers.round(subtasks.stream().mapToDouble(Issue::remainingH).sum(), 2);
        int pct = spent + remaining > 0 ? (int) Math.round(spent / (spent + remaining) * 100) : 0;
        int closed = (int) subtasks.stream().filter(i -> i.status() == Status.CLOSED).count();
        return new PhaseRollup(name, pct, closed, subtasks.size(), start, end, spent, remaining,
                scheduleRollup(subtasks, schedules, thresholdH));
    }

    /**
     * Sums and counts of the schedule labels of {@code subtasks}, and their main state by dates only: done when all are Closed;
     * late when any is overdue or is a late start that has not started; not started when none has; early when any started or
     * finished early; else on track. "Started" means an actual start exists or the status is past NYS.
     */
    public static ScheduleRollup scheduleRollup(List<Issue> subtasks, Map<String, IssueSchedule> schedules, double thresholdH) {
        double plan = 0;
        double actual = 0;
        boolean estimated = false;
        int overdue = 0;
        int lateStart = 0;
        int earlyStart = 0;
        int lateFinish = 0;
        int earlyFinish = 0;
        boolean lateNow = false;
        boolean anyStarted = false;
        for (Issue i : subtasks) {
            IssueSchedule s = schedules.get(i.key());
            boolean started = Flags.actualStart(i) != null || i.status() != Status.NYS;
            anyStarted |= started;
            if (s.effort() != EffortState.NO_PLAN && s.effort() != EffortState.NO_ESTIMATE) {
                estimated = true;
                plan += s.planToDateH();
                actual += s.actualToDateH();
            }
            switch (s.start()) {
                case LATE_START -> {
                    lateStart++;
                    lateNow |= !started;
                }
                case EARLY_START -> earlyStart++;
                default -> { }
            }
            switch (s.finish()) {
                case OVERDUE -> {
                    overdue++;
                    lateNow = true;
                }
                case LATE_FINISH -> lateFinish++;
                case EARLY_FINISH -> earlyFinish++;
                default -> { }
            }
        }
        double diff = Numbers.round(actual - plan, 2);
        EffortState effort = !estimated ? EffortState.NO_PLAN
                : Math.abs(diff) <= thresholdH ? EffortState.ON_PLAN : diff < 0 ? EffortState.UNDER_BURN : EffortState.OVER_BURN;
        Schedule state;
        if (subtasks.isEmpty()) {
            state = Schedule.UNKNOWN;
        } else if (subtasks.stream().allMatch(i -> i.status() == Status.CLOSED)) {
            state = Schedule.DONE;
        } else if (lateNow) {
            state = Schedule.LATE;
        } else if (!anyStarted) {
            state = Schedule.NOT_STARTED;
        } else if (overdue == 0 && lateStart == 0 && lateFinish == 0 && earlyStart + earlyFinish > 0) {
            state = Schedule.EARLY;
        } else {
            state = Schedule.ON_TRACK;
        }
        return new ScheduleRollup(Numbers.round(plan, 2), Numbers.round(actual, 2), diff, effort, overdue, lateStart, earlyStart,
                lateFinish, earlyFinish, state);
    }

    /** The remaining schedule slips by the worst current delay, counted in Mon–Fri days. */
    public static String forecastDate(String plan, int worstLateDays) {
        String d = plan;
        int n = worstLateDays;
        while (n > 0) {
            d = Dates.addDays(d, 1);
            int w = Dates.weekday(d);
            if (w != 0 && w != 6) {
                n--;
            }
        }
        return d;
    }

    /** @param kind overdue | stuck_resolved | many_rounds | waiting_client */
    public record AttentionGroup(String kind, List<Issue> issues) {
    }

    public static List<AttentionGroup> attentionGroups(List<Issue> issues, Map<String, IssueFlags> flags,
                                                       Map<String, Member> members, Thresholds t) {
        java.util.function.Predicate<String> isClient = mid -> Text.hasText(mid) && members.get(mid) != null
                && members.get(mid).role() == Role.JM;
        List<Issue> overdue = new ArrayList<>(issues.stream().filter(i -> flags.get(i.key()).late()).toList());
        overdue.sort(Comparator.comparingInt(i -> -flags.get(i.key()).lateDays()));
        return List.of(
                new AttentionGroup("overdue", overdue),
                new AttentionGroup("stuck_resolved", issues.stream()
                        .filter(i -> i.status() == Status.RESOLVED && flags.get(i.key()).heldDays() >= t.heldDays()).toList()),
                new AttentionGroup("many_rounds", issues.stream()
                        .filter(i -> flags.get(i.key()).rounds() >= t.reviewRounds() && i.status() != Status.CLOSED).toList()),
                new AttentionGroup("waiting_client", issues.stream()
                        .filter(i -> isClient.test(i.assigneeId()) && i.status() != Status.CLOSED
                                && flags.get(i.key()).heldDays() >= t.heldDays()).toList()));
    }
}
