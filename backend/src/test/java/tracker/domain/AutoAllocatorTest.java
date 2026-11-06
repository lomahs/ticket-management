package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tracker.domain.AutoAllocator.Input;
import tracker.domain.AutoAllocator.Options;
import tracker.domain.AutoAllocator.Phase;
import tracker.domain.AutoAllocator.Pick;
import tracker.domain.AutoAllocator.Row;

class AutoAllocatorTest {
    static final String MON = "2026-10-05";
    static final Map<String, Member> M = new HashMap<>();
    static final Map<String, Role> ROLES = new HashMap<>();

    static {
        for (String[] m : new String[][]{{"an", "BA"}, {"binh", "BA"}, {"minh", "BAL"}, {"sato", "JM"}, {"tanaka", "JM"}}) {
            M.put(m[0], new Member(m[0], m[0], m[0], Role.of(m[1]), "VN", "#000"));
            ROLES.put(m[0], Role.of(m[1]));
        }
    }

    static Input input(List<Row> rows, List<String> team, String end, List<String> jms, List<Allocation> alloc, List<Leave> leaves) {
        WorkCalendar cal = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M, leaves);
        List<String> all = new java.util.ArrayList<>(team);
        all.addAll(jms);
        return new Input("E", rows, Map.of("P", new Phase(MON, end, all)), ROLES, Map.of(), Map.of(),
                AutoAllocator.cached(DetailScheduler.hours("E", new Allocations(alloc), cal)), cal, StepWeights.defaults(), 8);
    }

    static List<Allocation> sixHours(String... ids) {
        return java.util.Arrays.stream(ids).map(id -> new Allocation(id, "E", "2026-10-01", 6)).toList();
    }

    static Row function(long id, double create) {
        return new Row(id, "P", 5, create, null, null, null);
    }

    @Test
    void theJmReviewsAreShared() { // JM Review takes no allocation: without balancing, the first JM would get them all
        List<Row> rows = List.of(function(1, 8), function(2, 8), function(3, 8), function(4, 8));
        List<Pick> picks = AutoAllocator.allocate(input(rows, List.of("an", "binh", "minh"), "2026-10-30", List.of("sato", "tanaka"),
                sixHours("an", "binh", "minh"), List.of()));
        assertThat(picks).extracting(Pick::jmId).containsOnly("sato", "tanaka");
        assertThat(picks.stream().filter(p -> "sato".equals(p.jmId())).count()).isEqualTo(2);
    }

    @Test
    void theBaAndBalAreFilledWhenTheAnkenHasNoJm() {
        List<Pick> picks = AutoAllocator.allocate(input(List.of(function(1, 8)), List.of("an", "minh"), "2026-10-30", List.of(),
                sixHours("an", "minh"), List.of()));
        assertThat(picks).containsExactly(new Pick(1, "an", "minh", null));
    }

    @Test
    void peopleAlreadyPickedStay() {
        List<Row> rows = List.of(new Row(1, "P", 5, 8, "binh", null, "tanaka"), new Row(2, "P", null, 8, "binh", null, null));
        List<Pick> picks = AutoAllocator.allocate(input(rows, List.of("an", "binh", "minh"), "2026-10-30", List.of("sato", "tanaka"),
                sixHours("an", "binh", "minh"), List.of()));
        assertThat(picks).containsExactly(new Pick(1, "binh", "minh", "tanaka"), new Pick(2, "binh", null, null));
    }

    @Test
    void aMemberOnLeaveGivesWayAndTheBudgetIsKept() {
        // an is on leave all week: two 12h tickets in a one-week phase go to binh first, then the plan is checked as a whole
        List<Leave> leave = new java.util.ArrayList<>();
        for (int d = 0; d < 5; d++) {
            leave.add(new Leave(d + 1, "an", Dates.addDays(MON, d), LeaveType.FULL, ""));
        }
        List<Row> rows = List.of(new Row(1, "P", null, 12, null, null, null), new Row(2, "P", null, 12, null, null, null));
        Input in = input(rows, List.of("an", "binh"), "2026-10-09", List.of(), sixHours("an", "binh"), leave);
        List<Pick> picks = AutoAllocator.allocate(in);
        assertThat(picks).extracting(Pick::baId).containsExactly("binh", "binh"); // 24h in binh's 30h week; an has none
        assertThat(AutoAllocator.score(in, picks).overBudgetH()).isZero();
    }

    @Test
    void theAnswerIsTheSameEveryTime() {
        Input in = AutoAllocationLab.scenario(AutoAllocationLab.SIZES.get(1), 11);
        assertThat(AutoAllocator.allocate(in)).isEqualTo(AutoAllocator.allocate(in));
    }

    @Test
    void theImprovementNeverScoresWorseThanTheGreedyPass() {
        for (long seed = 1; seed <= 4; seed++) {
            Input in = AutoAllocationLab.scenario(AutoAllocationLab.SIZES.get(seed % 2 == 0 ? 0 : 1), seed);
            assertThat(AutoAllocator.score(in, AutoAllocator.allocate(in)))
                    .isLessThanOrEqualTo(AutoAllocator.score(in, AutoAllocator.allocate(in, Options.GREEDY)));
        }
    }
}
