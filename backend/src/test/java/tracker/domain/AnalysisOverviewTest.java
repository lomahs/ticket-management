package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.TODAY;
import static tracker.domain.Fixtures.assigned;
import static tracker.domain.Fixtures.issue;
import static tracker.domain.Fixtures.statusTo;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AnalysisOverviewTest {
    static final Thresholds T = Thresholds.defaults();

    @Test
    void estimateVsLoggedCountsStartedIssuesOnly() {
        List<Analysis.EffortBar> bars = Analysis.estimateVsLogged(List.of(new Analysis.Group("Create",
                List.of(issue().est(8).logged(10).build(), issue().est(5).status(Status.NYS).build()))));
        assertThat(bars).containsExactly(new Analysis.EffortBar("Create", 8, 10));
    }

    @Test
    void reworkGroupsByAuthorRole() {
        List<Issue> cells = List.of(
                issue().key("A1").fid("F1").step(StepKind.CREATE).assignee("an").reporter("minh").logged(8).logDate("2026-09-28")
                        .log("2026-09-29", "pim", 1).build(),
                issue().key("A2").fid("F1").step(StepKind.BAL_REVIEW).assignee("minh").logged(1).logDate("2026-09-30").build(),
                issue().key("A3").fid("F1").step(StepKind.FIX_BAL).assignee("an").logged(2).logDate("2026-10-01")
                        .log("2026-10-02", "minh", 0.5).build());
        List<Function> fns = Flags.buildFunctions(cells);
        Map<String, Integer> rounds = new HashMap<>();
        cells.forEach(c -> rounds.put(c.key(), Fixtures.flags(c, true, "an").rounds()));
        List<Analysis.Rework> r = Analysis.reworkByMember(fns, List.of("an", "minh"), Fixtures.MEMBERS, rounds);
        assertThat(r).containsExactly(
                new Analysis.Rework("an", Role.BA, 1, 8, 2, 0, 0.25, 2, 2),
                new Analysis.Rework("minh", Role.BAL, 1, 0, 0, 1.5, 0, 2, 2)); // pim's hour is invalid: in no row
    }

    private static Map<String, IssueSchedule> sched(Issue... issues) {
        Map<String, IssueSchedule> m = new HashMap<>();
        for (Issue i : issues) {
            m.put(i.key(), ScheduleRules.of(i, null, TODAY, Fixtures.CAL, 1.0));
        }
        return m;
    }

    private static Overview.ScheduleRollup roll(Issue... issues) {
        return Overview.scheduleRollup(List.of(issues), sched(issues), 1.0);
    }

    @Test
    void phaseRollupUsesSpentOverSpentPlusRemaining() {
        Issue a = issue().key("A").status(Status.CLOSED).ps("2026-09-01").pe("2026-09-05").logged(6).remaining(0).build();
        Issue b = issue().key("B").status(Status.WIP).ps("2026-09-03").pe("2026-09-11").logged(2).remaining(2).build();
        Overview.PhaseRollup p = Overview.phaseRollup("Prepare", List.of(a, b), sched(a, b), 1.0);
        assertThat(p).usingRecursiveComparison().ignoringFields("schedule")
                .isEqualTo(new Overview.PhaseRollup("Prepare", 80, 1, 2, "2026-09-01", "2026-09-11", 8.0, 2.0, null));
        assertThat(p.schedule().overdue()).isEqualTo(1);
    }

    @Test
    void phaseProgressIgnoresJmReview() {
        Issue a = issue().key("A").fid("F1").step(StepKind.CREATE).status(Status.CLOSED).logged(6).remaining(0).build();
        Issue jm = issue().key("B").fid("F1").step(StepKind.JM_REVIEW).status(Status.WIP).logged(1).remaining(3).build();
        Overview.PhaseRollup p = Overview.phaseRollup("Prepare", List.of(a, jm), sched(a, jm), 1.0);
        assertThat(p.pct()).isEqualTo(100);
        assertThat(List.of(p.closed(), p.total())).containsExactly(1, 1);
        assertThat(p.spentH()).isEqualTo(6.0);
        assertThat(p.schedule().state()).isNotEqualTo(Overview.Schedule.DONE); // its dates still count
    }

    @Test
    void phaseRollupOfNoSubtasksIsZero() {
        Overview.PhaseRollup p = Overview.phaseRollup("X", List.of(), Map.of(), 1.0);
        assertThat(p).usingRecursiveComparison().ignoringFields("schedule")
                .isEqualTo(new Overview.PhaseRollup("X", 0, 0, 0, null, null, 0, 0, null));
        assertThat(p.schedule().state()).isEqualTo(Overview.Schedule.UNKNOWN);
        Issue i = issue().remaining(0).build();
        assertThat(Overview.phaseRollup("X", List.of(i), sched(i), 1.0).pct()).isZero();
    }

    @Test
    void rollupSumsAndCounts() {
        Issue overdue = issue().key("A").status(Status.WIP).ps("2026-10-01").pe("2026-10-05").est(8).logged(8).build();
        Issue early = issue().key("B").status(Status.RESOLVED).ps("2026-10-05").pe("2026-10-09").est(8).logged(4)
                .events(statusTo("2026-10-05", "W"), statusTo("2026-10-06", "R")).build();
        Issue idle = issue().key("C").status(Status.NYS).ps("2026-10-12").pe("2026-10-16").est(8).logged(0).build();
        Overview.ScheduleRollup r = roll(overdue, early, idle);
        Map<String, IssueSchedule> s = sched(overdue, early, idle);
        assertThat(r.overdue()).isEqualTo(1);
        assertThat(r.earlyFinish()).isEqualTo(1);
        assertThat(r.planToDateH()).isEqualTo(Numbers.round(s.values().stream().mapToDouble(IssueSchedule::planToDateH).sum(), 2));
        assertThat(r.actualToDateH()).isEqualTo(Numbers.round(s.values().stream().mapToDouble(IssueSchedule::actualToDateH).sum(), 2));
        assertThat(r.diffH()).isEqualTo(Numbers.round(r.actualToDateH() - r.planToDateH(), 2));
        assertThat(r.state()).isEqualTo(Overview.Schedule.LATE);
    }

    @Test
    void rollupStates() {
        Issue closed = issue().key("A").status(Status.CLOSED).ps("2026-10-05").pe("2026-10-09").build();
        assertThat(roll(closed).state()).isEqualTo(Overview.Schedule.DONE);
        Issue idle = issue().key("B").status(Status.NYS).ps("2026-10-12").pe("2026-10-16").build();
        assertThat(roll(idle).state()).isEqualTo(Overview.Schedule.NOT_STARTED);
        Issue early = issue().key("C").status(Status.WIP).ps("2026-10-07").pe("2026-10-09").events(statusTo("2026-10-06", "W")).build();
        assertThat(roll(early, idle).state()).isEqualTo(Overview.Schedule.EARLY);
        Issue onTime = issue().key("D").status(Status.WIP).ps("2026-10-05").pe("2026-10-09").events(statusTo("2026-10-05", "W")).build();
        assertThat(roll(onTime, idle).state()).isEqualTo(Overview.Schedule.ON_TRACK);
        Issue lateStart = issue().key("E").status(Status.NYS).ps("2026-10-05").pe("2026-10-16").build();
        assertThat(roll(lateStart).lateStart()).isEqualTo(1);
        assertThat(roll(onTime, lateStart).state()).isEqualTo(Overview.Schedule.LATE);
        assertThat(roll().state()).isEqualTo(Overview.Schedule.UNKNOWN);
    }

    @Test
    void earlyNeedsNoLateLabelAtAll() {
        Issue early = issue().key("A").status(Status.WIP).ps("2026-10-07").pe("2026-10-09").events(statusTo("2026-10-06", "W")).build();
        Issue lateFinish = issue().key("B").status(Status.CLOSED).ps("2026-10-05").pe("2026-10-06").events(statusTo("2026-10-05", "W"),
                statusTo("2026-10-08", "C")).build();
        Overview.ScheduleRollup a = roll(early, lateFinish);
        assertThat(a.earlyStart()).isEqualTo(1);
        assertThat(a.lateFinish()).isEqualTo(1);
        assertThat(a.state()).isEqualTo(Overview.Schedule.ON_TRACK);
        Issue startedLate = issue().key("C").status(Status.WIP).ps("2026-10-05").pe("2026-10-16").events(statusTo("2026-10-06", "W")).build();
        Overview.ScheduleRollup b = roll(early, startedLate);
        assertThat(b.earlyStart()).isEqualTo(1);
        assertThat(b.lateStart()).isEqualTo(1);
        assertThat(b.state()).isEqualTo(Overview.Schedule.ON_TRACK);
        assertThat(roll(early).state()).isEqualTo(Overview.Schedule.EARLY);
    }

    @Test
    void aStartedLateStartDoesNotMakeThePhaseLate() {
        Issue lateButStarted = issue().key("A").status(Status.WIP).ps("2026-10-05").pe("2026-10-16").est(8)
                .events(statusTo("2026-10-06", "W")).build();
        Overview.ScheduleRollup r = roll(lateButStarted);
        assertThat(r.lateStart()).isEqualTo(1);
        assertThat(r.overdue()).isZero();
        assertThat(r.state()).isEqualTo(Overview.Schedule.ON_TRACK);
    }

    @Test
    void aStatusPastNysCountsAsStartedWithoutAnActualStart() {
        Issue wip = issue().key("A").status(Status.WIP).ps("2026-10-12").pe("2026-10-16").logged(0).build();
        assertThat(Flags.actualStart(wip)).isNull();
        Issue idle = issue().key("B").status(Status.NYS).ps("2026-10-12").pe("2026-10-16").build();
        assertThat(roll(wip, idle).state()).isNotEqualTo(Overview.Schedule.NOT_STARTED);
        assertThat(roll(idle).state()).isEqualTo(Overview.Schedule.NOT_STARTED);
    }

    @Test
    void rollupEffortUsesTheThresholdInclusively() {
        Issue over = issue().key("A").status(Status.WIP).ps("2026-10-05").pe("2026-10-09").est(10).logged(8).build();
        double d = roll(over).diffH();
        assertThat(d).isPositive();
        assertThat(Overview.scheduleRollup(List.of(over), sched(over), d).effort()).isEqualTo(EffortState.ON_PLAN);
        assertThat(Overview.scheduleRollup(List.of(over), sched(over), d - 0.01).effort()).isEqualTo(EffortState.OVER_BURN);
        Issue under = issue().key("B").status(Status.WIP).ps("2026-10-05").pe("2026-10-09").est(10).logged(4).build();
        double u = roll(under).diffH();
        assertThat(u).isNegative();
        assertThat(Overview.scheduleRollup(List.of(under), sched(under), -u).effort()).isEqualTo(EffortState.ON_PLAN);
        assertThat(Overview.scheduleRollup(List.of(under), sched(under), -u - 0.01).effort()).isEqualTo(EffortState.UNDER_BURN);
    }

    @Test
    void unplannedSubtasksDoNotDistortSums() {
        Issue base = issue().key("A").status(Status.WIP).ps("2026-10-05").pe("2026-10-09").est(10).logged(4).build();
        Overview.ScheduleRollup before = roll(base);
        Issue noPlan = issue().key("B").status(Status.WIP).ps(null).pe(null).est(8).logged(3).build();
        Issue noEstimate = issue().key("C").status(Status.WIP).ps("2026-10-05").pe("2026-10-09").est(0).logged(2).build();
        Overview.ScheduleRollup after = roll(base, noPlan, noEstimate);
        assertThat(sched(noPlan).get("B").effort()).isEqualTo(EffortState.NO_PLAN);
        assertThat(sched(noEstimate).get("C").effort()).isEqualTo(EffortState.NO_ESTIMATE);
        assertThat(after.planToDateH()).isEqualTo(before.planToDateH());
        assertThat(after.actualToDateH()).isEqualTo(before.actualToDateH());
    }

    @Test
    void forecastSlipsByWorstDelayInWorkingDays() {
        assertThat(Overview.forecastDate("2026-11-20", 3)).isEqualTo("2026-11-25");
        assertThat(Overview.forecastDate("2026-11-20", 0)).isEqualTo("2026-11-20");
    }

    @Test
    void attentionGroupsUseThresholdsAndRoles() {
        Map<String, Member> members = Map.of("tanaka", new Member("tanaka", "Tanaka", "", Role.JM, "JP", "#000"),
                "an", new Member("an", "An", "", Role.BA, "VN", "#000"));
        Issue late = issue().key("L").pe("2026-10-01").build();
        Issue stuck = issue().key("R").status(Status.RESOLVED).events(statusTo("2026-09-28", "R")).build();
        Issue jm = issue().key("J").assignee("tanaka").step(StepKind.JM_REVIEW).fid("F1").events(assigned("2026-09-29", "an", "tanaka")).build();
        List<Issue> issues = List.of(late, stuck, jm);
        Map<String, IssueFlags> flags = new HashMap<>();
        issues.forEach(i -> flags.put(i.key(), Fixtures.flags(i, true, "an")));
        Map<String, List<String>> g = new LinkedHashMap<>();
        Overview.attentionGroups(issues, flags, members, T).forEach(x -> g.put(x.kind(), x.issues().stream().map(Issue::key).toList()));
        assertThat(g).isEqualTo(Map.of("overdue", List.of("L"), "stuck_resolved", List.of("R"), "many_rounds", List.of(),
                "waiting_client", List.of("J")));
    }

    @Test
    void numbersRoundHalfUpAndFormatPlainly() {
        assertThat(Numbers.round(2.675, 2)).isEqualTo(2.68);
        assertThat(Numbers.round(0.125, 2)).isEqualTo(0.13);
        assertThat(Numbers.round(1.0 / 3, 4)).isEqualTo(0.3333);
        assertThat(Numbers.format(7.5)).isEqualTo("7.5");
        assertThat(Numbers.format(36000.0)).isEqualTo("36000");
        assertThat(Text.orElse("", "x")).isEqualTo("x");
        assertThat(Text.hasText(null)).isFalse();
    }
}
