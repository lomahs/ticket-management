package tracker.domain;

import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Start, finish and effort labels of a sub-task, each judged on the sub-task's own planned dates and estimate. */
public final class ScheduleRules {
    private ScheduleRules() {
    }

    /**
     * The effort label compares, at the end of {@code today}, Jira's remaining estimate (0 once Closed) with the plan remain
     * ({@link PlanDays#remainAt}); any difference counts: more left than planned is a delay (under-burn), less is ahead
     * (over-burn).
     *
     * @param workerId who does the work (spreads the plan over their working days); else the assignee, else nobody
     * @param saved    Detail Planning's hours per day of {@code i} ({@link PlanDays#of}); null when it has none
     */
    public static IssueSchedule of(Issue i, @Nullable String workerId, String today, WorkCalendar cal, @Nullable Map<String, Double> saved) {
        String worker = Text.hasText(workerId) ? workerId : Text.orElse(i.assigneeId(), "");
        String[] span = Flags.actualSpan(i);
        String actualStart = span != null ? span[0] : null;
        String actualEnd = span != null ? span[1] : null;
        boolean done = i.status() == Status.RESOLVED || i.status() == Status.CLOSED;
        boolean started = i.status() != Status.NYS;
        boolean hasStart = Text.hasText(i.plannedStart());
        boolean hasEnd = Text.hasText(i.plannedEnd());

        StartState start = StartState.NO_PLAN;
        int startDays = 0;
        if (hasStart) {
            String ps = i.plannedStart();
            if (actualStart != null) {
                int c = actualStart.compareTo(ps);
                start = c < 0 ? StartState.EARLY_START : c > 0 ? StartState.LATE_START : StartState.ON_TIME_START;
                startDays = c < 0 ? Dates.weekdaysAfter(actualStart, ps) : Dates.weekdaysAfter(ps, actualStart);
            } else if (started) {
                // moved on from NYS without a recorded start day: started, and not judged late
                start = StartState.ON_TIME_START;
            } else if (today.compareTo(ps) > 0) {
                start = StartState.LATE_START;
                startDays = Dates.weekdaysAfter(ps, today);
            } else {
                start = StartState.NOT_DUE;
            }
        }

        FinishState finish = FinishState.NO_PLAN;
        int finishDays = 0;
        if (hasEnd) {
            String pe = i.plannedEnd();
            if (actualEnd != null) {
                int c = actualEnd.compareTo(pe);
                finish = c < 0 ? FinishState.EARLY_FINISH : c > 0 ? FinishState.LATE_FINISH : FinishState.ON_TIME_FINISH;
                finishDays = c < 0 ? Dates.weekdaysAfter(actualEnd, pe) : Dates.weekdaysAfter(pe, actualEnd);
            } else if (done) {
                finish = FinishState.ON_TIME_FINISH;
            } else if (today.compareTo(pe) > 0) {
                finish = FinishState.OVERDUE;
                finishDays = Dates.weekdaysAfter(pe, today);
            } else {
                finish = FinishState.OPEN;
            }
        }

        if (!hasStart || !hasEnd) {
            return new IssueSchedule(start, startDays, finish, finishDays, EffortState.NO_PLAN, 0, 0, 0);
        }
        if (i.estimateH() == 0) {
            return new IssueSchedule(start, startDays, finish, finishDays, EffortState.NO_ESTIMATE, 0, 0, 0);
        }
        double plan = PlanDays.remainAt(i.estimateH(), PlanDays.of(i, worker, saved, cal), today);
        double actual = i.status() == Status.CLOSED ? 0 : Numbers.round(i.remainingH(), 2);
        double diff = Numbers.round(actual - plan, 2);
        return new IssueSchedule(start, startDays, finish, finishDays, effortOf(diff), plan, actual, diff);
    }

    /** On plan only when the remains are equal (to the hundredth); more left than planned is under-burn (a delay). */
    public static EffortState effortOf(double diffH) {
        return diffH == 0 ? EffortState.ON_PLAN : diffH > 0 ? EffortState.UNDER_BURN : EffortState.OVER_BURN;
    }
}
