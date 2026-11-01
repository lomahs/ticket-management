package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SchedulingTest {
    static final Map<String, Member> M = Map.of(
            "an", new Member("an", "An", "an", Role.BA, "VN", "#000"), "chi", new Member("chi", "Chi", "chi", Role.BA, "VN", "#000"),
            "minh", new Member("minh", "Minh", "minh", Role.BAL, "VN", "#000"),
            "tanaka", new Member("tanaka", "Tanaka", "tanaka", Role.JM, "JP", "#000"));
    static final WorkCalendar CAL = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of("2026-10-10", "Release"), CalendarTest.HOLIDAYS, M,
            List.of(new Leave(1, "chi", "2026-10-09", LeaveType.FULL, ""), new Leave(2, "minh", "2026-10-09", LeaveType.FULL, "")));

    static List<Scheduling.PlannedStep> plan(String ba, double hours, List<Map<String, String>> overrides) {
        return Scheduling.suggestFunctionPlan("2026-10-08", hours, List.of(ba, "minh", ba, "tanaka", ba), StepWeights.defaults(), CAL,
                overrides != null ? overrides : new ArrayList<>(Collections.nCopies(5, null)));
    }

    static List<Map<String, String>> first(Map<String, String> o) {
        return Arrays.asList(o, null, null, null, null);
    }

    @Test
    void threeStepPlanStopsAtFixBal() {
        List<Scheduling.PlannedStep> p = Scheduling.suggestFunctionPlan("2026-10-08", 8, List.of("an", "minh", "an"), StepWeights.defaults(), CAL,
                new ArrayList<>(Collections.nCopies(3, null)));
        assertThat(p).extracting(Scheduling.PlannedStep::step).containsExactly(StepKind.CREATE, StepKind.BAL_REVIEW, StepKind.FIX_BAL);
        assertThat(p).extracting(Scheduling.PlannedStep::start).isEqualTo(
                plan("an", 8, null).subList(0, 3).stream().map(Scheduling.PlannedStep::start).toList());
    }

    @Test
    void stepsFollowEachOtherWithWeightedEstimates() {
        List<Scheduling.PlannedStep> p = plan("an", 8, null);
        assertThat(p).extracting(Scheduling.PlannedStep::step).containsExactly(StepKind.values());
        assertThat(p).extracting(Scheduling.PlannedStep::estimateH).containsExactly(8.0, 2.0, 2.5, 2.0, 1.5);
        assertThat(p).extracting(s -> s.start() + "/" + s.end()).containsExactly("2026-10-08/2026-10-09", "2026-10-10/2026-10-10",
                "2026-10-12/2026-10-12", "2026-10-13/2026-10-13", "2026-10-14/2026-10-14");
        assertThat(p).allMatch(s -> s.note().equals("auto"));
    }

    @Test
    void suggestionSkipsLeaveAndReportsIt() {
        Scheduling.PlannedStep s = plan("chi", 8, null).get(0);
        assertThat(s.start() + "/" + s.end()).isEqualTo("2026-10-08/2026-10-10");
        assertThat(s.note()).isEqualTo("skips");
        assertThat(s.reasons()).containsExactly(new DateReason("2026-10-09", "Chi · All day"));
    }

    @Test
    void longCreateSpansMoreDays() {
        assertThat(plan("an", 13, null).get(0).end()).isEqualTo("2026-10-10");
    }

    @Test
    void manualOverrideLandingOnADayOffIsFlaggedNotBlocked() {
        List<Scheduling.PlannedStep> p = plan("an", 8, first(Map.of("start", "2026-10-11")));
        assertThat(p.get(0).start()).isEqualTo("2026-10-11");
        assertThat(p.get(0).note()).isEqualTo("dayoff");
        assertThat(p.get(0).reasons()).contains(new DateReason("2026-10-11", "weekend"));
        assertThat(p.get(1).start()).isGreaterThan(p.get(0).end());
    }

    @Test
    void overrideEndBeforeStartIsClamped() {
        Scheduling.PlannedStep s = plan("an", 8, first(Map.of("start", "2026-10-13", "end", "2026-10-12"))).get(0);
        assertThat(s.start() + "/" + s.end()).isEqualTo("2026-10-13/2026-10-13");
        assertThat(s.note()).isEqualTo("edited");
    }

    @Test
    void customWeightsChangeStepEstimates() {
        java.util.Map<StepKind, Double> w = new java.util.EnumMap<>(StepWeights.defaults().weights());
        w.put(StepKind.BAL_REVIEW, 0.5);
        List<Scheduling.PlannedStep> p = Scheduling.suggestFunctionPlan("2026-10-08", 8, List.of("an", "minh", "an"), new StepWeights(w), CAL,
                new ArrayList<>(Collections.nCopies(3, null)));
        assertThat(p.get(1).estimateH()).isEqualTo(4.0);
    }
}
