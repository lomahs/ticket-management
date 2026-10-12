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
    void reworkRateRoundsByOwner() {
        List<Issue> cells = List.of(issue().key("A1").fid("F1").step(StepKind.CREATE).logged(10).assignee("an").build(),
                issue().key("A3").fid("F1").step(StepKind.FIX_BAL).logged(3).assignee("an").events(assigned("2026-10-01", "minh", "an"),
                        assigned("2026-10-02", "an", "minh"), assigned("2026-10-05", "minh", "an")).build());
        List<Function> fns = Flags.buildFunctions(cells);
        Map<String, Integer> rounds = new HashMap<>();
        cells.forEach(c -> rounds.put(c.key(), Flags.issueFlags(c, true, "an", TODAY, T).rounds()));
        List<Analysis.Rework> r = Analysis.reworkByMember(fns, List.of("an"), rounds);
        assertThat(r).containsExactly(new Analysis.Rework("an", 1, 10, 3, 0.3, 2, 2));
    }

    @Test
    void phaseRollupWeightsStatuses() {
        Overview.PhaseRollup p = Overview.phaseRollup("Prepare", List.of(
                issue().status(Status.CLOSED).ps("2026-09-01").pe("2026-09-05").build(),
                issue().status(Status.WIP).ps("2026-09-03").pe("2026-09-11").build()));
        assertThat(p).isEqualTo(new Overview.PhaseRollup("Prepare", 70, 1, 2, "2026-09-01", "2026-09-11"));
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
        issues.forEach(i -> flags.put(i.key(), Flags.issueFlags(i, true, "an", TODAY, T)));
        Map<String, List<String>> g = new LinkedHashMap<>();
        Overview.attentionGroups(issues, flags, members, T).forEach(x -> g.put(x.kind(), x.issues().stream().map(Issue::key).toList()));
        assertThat(g).isEqualTo(Map.of("overdue", List.of("L"), "stuck_resolved", List.of("R"), "many_rounds", List.of(),
                "waiting_client", List.of("J")));
    }

    @Test
    void numericHelpersFollowPython() {
        assertThat(Num.round(2.5)).isEqualTo(2);
        assertThat(Num.round(3.5)).isEqualTo(4);
        assertThat(Num.round(2.675, 2)).isEqualTo(2.67); // the binary value is below the tie
        assertThat(Num.round(0.125, 2)).isEqualTo(0.12); // an exact tie rounds to even
        assertThat(Num.sum(java.util.stream.DoubleStream.of(0.1, 0.2, 0.3))).isEqualTo(0.6);
        assertThat(Py.repr(7.5)).isEqualTo("7.5");
        assertThat(Py.repr(36000.0)).isEqualTo("36000.0");
    }
}
