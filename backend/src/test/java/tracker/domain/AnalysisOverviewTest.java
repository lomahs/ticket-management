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
        cells.forEach(c -> rounds.put(c.key(), Fixtures.flags(c, true, "an").rounds()));
        List<Analysis.Rework> r = Analysis.reworkByMember(fns, List.of("an"), rounds);
        assertThat(r).containsExactly(new Analysis.Rework("an", 1, 10, 3, 0.3, 2, 2));
    }

    @Test
    void phaseRollupUsesSpentOverSpentPlusRemaining() {
        Overview.PhaseRollup p = Overview.phaseRollup("Prepare", List.of(
                issue().status(Status.CLOSED).ps("2026-09-01").pe("2026-09-05").logged(6).remaining(0).build(),
                issue().status(Status.WIP).ps("2026-09-03").pe("2026-09-11").logged(2).remaining(2).build()));
        assertThat(p).isEqualTo(new Overview.PhaseRollup("Prepare", 80, 1, 2, "2026-09-01", "2026-09-11", 8.0, 2.0));
    }

    @Test
    void phaseRollupOfNoSubtasksIsZero() {
        assertThat(Overview.phaseRollup("X", List.of())).isEqualTo(new Overview.PhaseRollup("X", 0, 0, 0, null, null, 0, 0));
        assertThat(Overview.phaseRollup("X", List.of(issue().remaining(0).build())).pct()).isZero();
    }

    @Test
    void scheduleStateIsAStubForNow() {
        assertThat(Overview.scheduleState(List.of(issue().build()), TODAY)).isEqualTo(Overview.Schedule.UNKNOWN);
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
