package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ScheduleRulesTest {
    static final String TODAY = Fixtures.TODAY; // Wednesday
    static final Map<String, Member> M = Map.of("an", new Member("an", "An", "an", Role.BA, "VN", "#000"));
    /** Mon-Fri only: no extra working Saturday. */
    static final WorkCalendar CAL = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M, List.of());

    private static IssueSchedule of(Issue i) {
        return ScheduleRules.of(i, null, TODAY, CAL, null);
    }

    @Test
    void eachSubtaskIsJudgedOnItsOwnDates() {
        IssueSchedule create = of(Fixtures.issue().status(Status.NYS).ps("2026-10-01").pe("2026-10-05").build());
        assertThat(create.start()).isEqualTo(StartState.LATE_START);
        assertThat(create.finish()).isEqualTo(FinishState.OVERDUE);

        Issue review = Fixtures.issue().status(Status.NYS).ps("2026-10-12").pe("2026-10-13").build();
        IssueSchedule early = of(review);
        assertThat(early.start()).isEqualTo(StartState.NOT_DUE);
        assertThat(early.finish()).isEqualTo(FinishState.OPEN);
        assertThat(ScheduleRules.of(review, null, "2026-10-12", CAL, null).start()).isEqualTo(StartState.NOT_DUE);
        IssueSchedule next = ScheduleRules.of(review, null, "2026-10-13", CAL, null);
        assertThat(next.start()).isEqualTo(StartState.LATE_START);
        assertThat(next.startDays()).isEqualTo(1);
    }

    @Test
    void notStartedPastStartAndEnd() {
        IssueSchedule s = of(Fixtures.issue().status(Status.NYS).ps("2026-10-01").pe("2026-10-05").build());
        assertThat(s.start()).isEqualTo(StartState.LATE_START);
        assertThat(s.startDays()).isEqualTo(4);
        assertThat(s.finish()).isEqualTo(FinishState.OVERDUE);
        assertThat(s.finishDays()).isEqualTo(2);
    }

    private static IssueSchedule startedOn(String date) {
        return of(Fixtures.issue().ps("2026-10-05").pe("2026-10-09").events(Fixtures.statusTo(date, "W")).build());
    }

    @Test
    void startComparesActualWithPlanned() {
        IssueSchedule early = startedOn("2026-10-02");
        assertThat(early.start()).isEqualTo(StartState.EARLY_START);
        assertThat(early.startDays()).isEqualTo(1);
        IssueSchedule late = startedOn("2026-10-06");
        assertThat(late.start()).isEqualTo(StartState.LATE_START);
        assertThat(late.startDays()).isEqualTo(1);
        assertThat(startedOn("2026-10-05").start()).isEqualTo(StartState.ON_TIME_START);
    }

    @Test
    void finishedEarlyWithFewerHours() {
        IssueSchedule s = of(Fixtures.issue().status(Status.RESOLVED).est(10).ps("2026-09-28").pe("2026-10-02")
                .logged(5).events(Fixtures.statusTo("2026-09-28", "W"), Fixtures.statusTo("2026-10-01", "R")).build());
        assertThat(s.finish()).isEqualTo(FinishState.EARLY_FINISH);
        assertThat(s.finishDays()).isEqualTo(1);
        assertThat(s.planRemainH()).isZero();
        assertThat(s.actualRemainH()).isEqualTo(10); // Jira's remaining estimate was never lowered
        assertThat(s.diffH()).isEqualTo(10);
        assertThat(s.effort()).isEqualTo(EffortState.UNDER_BURN);
    }

    @Test
    void effortExampleFromTheSpec() {
        // 9h planned 5h on Tuesday 10/06 and 4h on Wednesday 10/07: 4h left after Day 1, 0h after Day 2
        Map<String, Double> plan = Map.of("2026-10-06", 5.0, "2026-10-07", 4.0);
        Fixtures.I task = Fixtures.issue().est(9).ps("2026-10-06").pe("2026-10-07");
        IssueSchedule day1 = ScheduleRules.of(task.remaining(5).build(), null, "2026-10-06", CAL, plan);
        assertThat(day1.planRemainH()).isEqualTo(4);
        assertThat(day1.actualRemainH()).isEqualTo(5);
        assertThat(day1.diffH()).isEqualTo(1);
        assertThat(day1.effort()).isEqualTo(EffortState.UNDER_BURN);
        assertThat(ScheduleRules.of(task.remaining(1).build(), null, "2026-10-07", CAL, plan).effort()).isEqualTo(EffortState.UNDER_BURN);
        assertThat(ScheduleRules.of(task.remaining(0).build(), null, "2026-10-07", CAL, plan).effort()).isEqualTo(EffortState.ON_PLAN);
        assertThat(ScheduleRules.of(task.remaining(3).build(), null, "2026-10-06", CAL, plan).effort()).isEqualTo(EffortState.OVER_BURN);
    }

    @Test
    void savedHoursPerDayCountOnlyWhileTheyFitTheSubtask() {
        Fixtures.I task = Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").remaining(10);
        // all on Monday and Tuesday: nothing left by plan on Wednesday
        assertThat(ScheduleRules.of(task.build(), null, TODAY, CAL, Map.of("2026-10-05", 6.0, "2026-10-06", 4.0)).planRemainH()).isZero();
        // the estimate changed in Jira since (8h saved for 10h): spread evenly, 2h a day, 4h left
        assertThat(ScheduleRules.of(task.build(), null, TODAY, CAL, Map.of("2026-10-05", 4.0, "2026-10-06", 4.0)).planRemainH()).isEqualTo(4);
        // a day outside the planned dates: spread evenly
        assertThat(ScheduleRules.of(task.build(), null, TODAY, CAL, Map.of("2026-10-02", 6.0, "2026-10-06", 4.0)).planRemainH()).isEqualTo(4);
    }

    @Test
    void aClosedSubtaskHasNothingLeft() {
        IssueSchedule s = of(Fixtures.issue().status(Status.CLOSED).est(10).ps("2026-10-05").pe("2026-10-09").remaining(2).build());
        assertThat(s.actualRemainH()).isZero();
        assertThat(s.effort()).isEqualTo(EffortState.OVER_BURN);
    }

    @Test
    void lateFinishIsHistory() {
        IssueSchedule s = of(Fixtures.issue().status(Status.RESOLVED).ps("2026-09-30").pe("2026-10-02")
                .events(Fixtures.statusTo("2026-09-30", "W"), Fixtures.statusTo("2026-10-06", "R")).build());
        assertThat(s.finish()).isEqualTo(FinishState.LATE_FINISH);
        assertThat(s.finishDays()).isEqualTo(2);
    }

    @Test
    void noPlanAndNoEstimate() {
        IssueSchedule none = of(Fixtures.issue().status(Status.NYS).ps(null).pe(null).build());
        assertThat(none.start()).isEqualTo(StartState.NO_PLAN);
        assertThat(none.finish()).isEqualTo(FinishState.NO_PLAN);
        assertThat(none.effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(of(Fixtures.issue().est(0).build()).effort()).isEqualTo(EffortState.NO_ESTIMATE);
    }

    @Test
    void halfDayLeaveDoesNotShiftThePlan() {
        Issue i = Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").build();
        WorkCalendar half = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M,
                List.of(new Leave(1, "an", "2026-10-06", LeaveType.HALF, "")));
        assertThat(ScheduleRules.of(i, null, TODAY, half, null).planRemainH()).isEqualTo(4);
        WorkCalendar full = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M,
                List.of(new Leave(1, "an", "2026-10-06", LeaveType.FULL, "")));
        assertThat(ScheduleRules.of(i, null, TODAY, full, null).planRemainH()).isEqualTo(5);
    }

    @Test
    void effortLabelUsesTheRoundedDiff() {
        // 7h over five days: 2.8h left by plan on Wednesday
        IssueSchedule s = of(Fixtures.issue().est(7).ps("2026-10-05").pe("2026-10-09").remaining(2.801).build());
        assertThat(s.diffH()).isZero();
        assertThat(s.effort()).isEqualTo(EffortState.ON_PLAN);
    }

    @Test
    void doneIssueWithoutHistoryIsNeverLate() {
        IssueSchedule s = of(Fixtures.issue().status(Status.CLOSED).ps("2026-10-01").pe("2026-10-05").build());
        assertThat(s.start()).isEqualTo(StartState.ON_TIME_START);
        assertThat(s.startDays()).isZero();
        assertThat(s.finish()).isEqualTo(FinishState.ON_TIME_FINISH);
        assertThat(s.finishDays()).isZero();
    }

    @Test
    void boundaries() {
        assertThat(of(Fixtures.issue().ps("2026-10-05").pe(TODAY).build()).finish()).isEqualTo(FinishState.OPEN);
        IssueSchedule onDay = of(Fixtures.issue().status(Status.RESOLVED).ps("2026-10-01").pe("2026-10-05")
                .events(Fixtures.statusTo("2026-10-01", "W"), Fixtures.statusTo("2026-10-05", "R")).build());
        assertThat(onDay.finish()).isEqualTo(FinishState.ON_TIME_FINISH);
        assertThat(onDay.finishDays()).isZero();
        IssueSchedule future = of(Fixtures.issue().status(Status.NYS).est(10).ps("2026-10-12").pe("2026-10-16").build());
        assertThat(future.planRemainH()).isEqualTo(10);
        assertThat(future.effort()).isEqualTo(EffortState.ON_PLAN);
        WorkCalendar other = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M,
                List.of(new Leave(1, "chi", "2026-10-06", LeaveType.FULL, "")));
        assertThat(ScheduleRules.of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").build(), null, TODAY, other, null)
                .planRemainH()).isEqualTo(4);
        IssueSchedule onlyStart = of(Fixtures.issue().ps("2026-10-05").pe(null).build());
        assertThat(onlyStart.effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(onlyStart.finish()).isEqualTo(FinishState.NO_PLAN);
        IssueSchedule onlyEnd = of(Fixtures.issue().ps(null).pe("2026-10-09").build());
        assertThat(onlyEnd.effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(onlyEnd.start()).isEqualTo(StartState.NO_PLAN);
    }

    @Test
    void inProgressWithoutARecordedStartHasStartedOnTime() {
        // status W, no status events, no worklogs: started, but the day is unknown
        IssueSchedule s = of(Fixtures.issue().status(Status.WIP).ps("2026-10-01").pe("2026-10-05").build());
        assertThat(s.start()).isEqualTo(StartState.ON_TIME_START);
        assertThat(s.startDays()).isZero();
        assertThat(s.finish()).isEqualTo(FinishState.OVERDUE); // still unfinished past its planned end
        assertThat(s.finishDays()).isEqualTo(2);
    }
}
