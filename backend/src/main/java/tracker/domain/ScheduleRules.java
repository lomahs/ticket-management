package tracker.domain;

import java.util.List;

import org.jspecify.annotations.Nullable;

/** Start, finish and effort labels of a sub-task, each judged on the sub-task's own planned dates and estimate. */
public final class ScheduleRules {
    private ScheduleRules() {
    }

    /** @param workerId who does the work (spreads the plan over their working days); else the assignee, else nobody */
    public static IssueSchedule of(Issue i, @Nullable String workerId, String today, WorkCalendar cal, double thresholdH) {
        String worker = Text.hasText(workerId) ? workerId : Text.orElse(i.assigneeId(), "");
        String[] span = Flags.actualSpan(i);
        String actualStart = span != null ? span[0] : null;
        String actualEnd = span != null ? span[1] : null;
        boolean done = i.status() == Status.RESOLVED || i.status() == Status.CLOSED;
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
            } else if (done) {
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
        double plan = Numbers.round(planToDate(i, worker, today, cal), 2);
        double actual = Numbers.round(
                i.worklogs().stream().filter(w -> w.date().compareTo(today) <= 0).mapToDouble(Worklog::hours).sum(), 2);
        double diff = Numbers.round(actual - plan, 2);
        EffortState effort = Math.abs(diff) <= thresholdH ? EffortState.ON_PLAN
                : diff < 0 ? EffortState.UNDER_BURN : EffortState.OVER_BURN;
        return new IssueSchedule(start, startDays, finish, finishDays, effort,
                plan, actual, diff);
    }

    private static double planToDate(Issue i, String worker, String today, WorkCalendar cal) {
        Capacity.Plan plan = Capacity.dailyPlan(
                List.of(new Capacity.PlanItem(i.key(), worker, i.plannedStart(), i.plannedEnd(), i.estimateH())), cal);
        return plan.member(worker).entrySet().stream().filter(e -> e.getKey().compareTo(today) <= 0)
                .mapToDouble(e -> e.getValue().total).sum();
    }
}
