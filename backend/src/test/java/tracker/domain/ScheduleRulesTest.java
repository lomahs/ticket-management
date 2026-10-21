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
        return ScheduleRules.of(i, null, TODAY, CAL, 1.0);
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
        assertThat(ScheduleRules.of(review, null, "2026-10-12", CAL, 1.0).start()).isEqualTo(StartState.NOT_DUE);
        IssueSchedule next = ScheduleRules.of(review, null, "2026-10-13", CAL, 1.0);
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
        assertThat(s.planToDateH()).isEqualTo(10);
        assertThat(s.actualToDateH()).isEqualTo(5);
        assertThat(s.diffH()).isEqualTo(-5);
        assertThat(s.effort()).isEqualTo(EffortState.UNDER_BURN);
    }

    @Test
    void effortExampleFromTheSpec() {
        IssueSchedule under = of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").logged(4).build());
        assertThat(under.planToDateH()).isEqualTo(6);
        assertThat(under.diffH()).isEqualTo(-2);
        assertThat(under.effort()).isEqualTo(EffortState.UNDER_BURN);
        assertThat(of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").logged(8).build()).effort())
                .isEqualTo(EffortState.OVER_BURN);
        assertThat(of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").logged(6.5).build()).effort())
                .isEqualTo(EffortState.ON_PLAN);
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
        assertThat(ScheduleRules.of(i, null, TODAY, half, 1.0).planToDateH()).isEqualTo(6);
        WorkCalendar full = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M,
                List.of(new Leave(1, "an", "2026-10-06", LeaveType.FULL, "")));
        assertThat(ScheduleRules.of(i, null, TODAY, full, 1.0).planToDateH()).isEqualTo(5);
    }

    @Test
    void effortLabelUsesTheRoundedDiff() {
        IssueSchedule s = of(Fixtures.issue().est(7).ps("2026-10-05").pe("2026-10-09").logged(5.2).build());
        assertThat(s.diffH()).isEqualTo(1.0);
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
        // plan 6h, actual 5h: |diff| == t
        assertThat(of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").logged(5).build()).effort())
                .isEqualTo(EffortState.ON_PLAN);
        IssueSchedule future = of(Fixtures.issue().status(Status.NYS).est(10).ps("2026-10-12").pe("2026-10-16").build());
        assertThat(future.planToDateH()).isZero();
        WorkCalendar other = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M,
                List.of(new Leave(1, "chi", "2026-10-06", LeaveType.FULL, "")));
        assertThat(ScheduleRules.of(Fixtures.issue().est(10).ps("2026-10-05").pe("2026-10-09").build(), null, TODAY, other, 1.0)
                .planToDateH()).isEqualTo(6);
        IssueSchedule onlyStart = of(Fixtures.issue().ps("2026-10-05").pe(null).build());
        assertThat(onlyStart.effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(onlyStart.finish()).isEqualTo(FinishState.NO_PLAN);
        IssueSchedule onlyEnd = of(Fixtures.issue().ps(null).pe("2026-10-09").build());
        assertThat(onlyEnd.effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(onlyEnd.start()).isEqualTo(StartState.NO_PLAN);
    }
}
