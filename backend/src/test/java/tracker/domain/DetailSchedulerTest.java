package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.DayOverrun;
import tracker.domain.DetailScheduler.Overrun;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.DetailScheduler.Result;
import tracker.domain.DetailScheduler.Step;
import tracker.domain.ScheduleVersion.PhaseRow;

class DetailSchedulerTest {
    static final Map<String, Member> M = Map.of("an", new Member("an", "An", "an", Role.BA, "VN", "#000"),
            "minh", new Member("minh", "Minh", "minh", Role.BAL, "VN", "#000"), "tanaka", new Member("tanaka", "Tanaka", "tanaka", Role.JM, "JP", "#000"));
    static final Allocations A = new Allocations(List.of(new Allocation("an", "E", "2026-10-01", 6), new Allocation("minh", "E", "2026-10-01", 6)));
    static final WorkCalendar CAL = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M, List.of());
    static final String MON = "2026-10-05";

    static Chain single(String ref, String member, double h) {
        return new Chain(MON, List.of(new Step(ref, "ITA", null, member, h, null)));
    }

    static Result run(Map<String, Map<String, Double>> loads, Chain... chains) {
        return DetailScheduler.schedule("E", List.of(chains), loads, A, CAL, 8);
    }

    static List<String> spans(Result r) {
        return r.placed().stream().map(p -> p.start() + "/" + p.end()).toList();
    }

    @Test
    void fillsEachDayUpToTheAllocation() { // spec example: 6h/day, task 1 = 4h, task 2 = 5h
        assertThat(spans(run(Map.of(), single("t1", "an", 4), single("t2", "an", 5)))).containsExactly("2026-10-05/2026-10-05", "2026-10-05/2026-10-06");
    }

    @Test
    void weekendsAreSkipped() {
        Chain c = new Chain("2026-10-09", List.of(new Step("t", "ITA", null, "an", 10, null)));
        assertThat(spans(run(Map.of(), c))).containsExactly("2026-10-09/2026-10-12");
    }

    @Test
    void stepsOfAFunctionFollowEachOther() { // Create ends on Tuesday: BAL Review starts Wednesday although minh is free before
        Chain f = new Chain(MON, List.of(new Step("c", "ITA", StepKind.CREATE, "an", 8, null), new Step("b", "ITA", StepKind.BAL_REVIEW, "minh", 2, null)));
        assertThat(spans(run(Map.of(), f))).containsExactly("2026-10-05/2026-10-06", "2026-10-07/2026-10-07");
    }

    @Test
    void aSharedStepPlacesEachPartFromTheSameDayAndRunsUntilTheLastOne() {
        // an has 6h on Monday already: a Fix comment of 10h, 7h for an and 3h for minh: an Tue 6h + Wed 1h, minh Tue 3h
        Step fix = new Step("f", "ITA", StepKind.FIX_BAL, List.of(new DetailScheduler.Share("an", 7), new DetailScheduler.Share("minh", 3)),
                "minh", 10, null, null);
        Chain c = new Chain("2026-10-06", List.of(fix, new Step("n", "ITA", StepKind.FIX_JM, "an", 1, null)));
        Result r = run(Map.of(), single("t", "an", 6), c);
        assertThat(r.placed()).extracting(p -> p.memberId() + " " + p.hours() + " " + p.start() + "/" + p.end()).containsExactly(
                "an 6.0 2026-10-05/2026-10-05", "an 7.0 2026-10-06/2026-10-07", "minh 3.0 2026-10-06/2026-10-06",
                "an 1.0 2026-10-08/2026-10-08"); // the next step starts after the later part
    }

    @Test
    void datesSetByHandSpreadTheHoursEvenAboveTheAllocation() {
        Step pinned = new Step("p", "ITA", StepKind.CREATE, List.of(new DetailScheduler.Share("an", 16)), "an", 16, "2026-10-08", "2026-10-09");
        Result r = run(Map.of(), new Chain(MON, List.of(pinned, new Step("b", "ITA", StepKind.BAL_REVIEW, "minh", 2, null))));
        assertThat(spans(r)).containsExactly("2026-10-08/2026-10-09", "2026-10-12/2026-10-12");
        assertThat(r.ledger().get("an")).containsEntry("2026-10-08", 8.0).containsEntry("2026-10-09", 8.0);
        assertThat(r.overDay()).extracting(o -> o.date()).containsExactly("2026-10-08", "2026-10-09"); // 8h a day of 6h
    }

    @Test
    void stepsSetByHandTakeTheirDaysFirstAndTheOthersArePlacedAroundThem() {
        // a later row's step is set by hand on Tue–Wed (6h a day): an earlier row of 12h skips those days instead of taking them
        Step pinned = new Step("p", "ITA", StepKind.CREATE, List.of(new DetailScheduler.Share("an", 12)), "an", 12, "2026-10-06", "2026-10-07");
        Result r = run(Map.of(), single("t", "an", 12), new Chain(MON, List.of(pinned)));
        assertThat(r.placed()).extracting(p -> p.ref() + " " + p.start() + "/" + p.end())
                .containsExactlyInAnyOrder("p 2026-10-06/2026-10-07", "t 2026-10-05/2026-10-08");
        assertThat(r.ledger().get("an")).containsEntry("2026-10-05", 6.0).containsEntry("2026-10-06", 6.0).containsEntry("2026-10-07", 6.0)
                .containsEntry("2026-10-08", 6.0);
        assertThat(r.overDay()).isEmpty();
    }

    @Test
    void jmReviewLastsHoursOverWorkingHoursAndTakesNoAllocation() {
        Chain f = new Chain(MON, List.of(new Step("c", "ITA", StepKind.CREATE, "an", 2, null), new Step("j", "ITA", StepKind.JM_REVIEW, "tanaka", 10, null),
                new Step("x", "ITA", StepKind.FIX_JM, "an", 1, null)));
        Result r = run(Map.of(), f);
        assertThat(spans(r)).containsExactly("2026-10-05/2026-10-05", "2026-10-06/2026-10-07", "2026-10-08/2026-10-08");
        assertThat(r.ledger()).doesNotContainKey("tanaka");
    }

    @Test
    void overlappingPhasesShareTheMembersDay() {
        Chain ita = single("ita", "an", 8);
        Chain itb = new Chain(MON, List.of(new Step("itb", "ITB", null, "an", 8, null)));
        Result r = run(Map.of(), ita, itb);
        assertThat(spans(r)).containsExactly("2026-10-05/2026-10-06", "2026-10-06/2026-10-07");
        assertThat(r.ledger().get("an").values()).allMatch(h -> h <= 6);
    }

    @Test
    void preloadedWorkLeavesLessRoom() {
        assertThat(spans(run(Map.of("an", Map.of(MON, 6.0)), single("t", "an", 4)))).containsExactly("2026-10-06/2026-10-06");
    }

    @Test
    void anOverloadAlreadyThereIsNotTheRunsAndTheRunNeverOverfillsADay() {
        Result r = run(Map.of("an", Map.of(MON, 8.0), "chi", Map.of(MON, 3.0)), single("t", "an", 4));
        assertThat(r.overDay()).isEmpty();
        assertThat(spans(r)).containsExactly("2026-10-06/2026-10-06");
        assertThat(r.overDay()).doesNotContain(new DayOverrun("an", MON, 8.0, 6.0));
    }

    @Test
    void zeroHoursTakesItsFirstWorkingDayAndAFixedStartIsKept() {
        Chain c = new Chain("2026-10-10", List.of(new Step("z", "ITA", null, "an", 0, null)));
        Chain f = new Chain("2026-10-07", List.of(new Step("s", "ITA", null, "an", 3, "2026-10-01")));
        assertThat(spans(run(Map.of(), c, f))).containsExactly("2026-10-12/2026-10-12", "2026-10-01/2026-10-07");
    }

    @Test
    void overBudgetCountsWithoutJmReview() {
        List<Placed> placed = List.of(new Placed("c", "ITA", StepKind.CREATE, "an", 34, "2026-10-05", "2026-10-12"),
                new Placed("j", "ITA", StepKind.JM_REVIEW, "tanaka", 10, "2026-10-12", "2026-10-13"));
        Map<String, PhaseRow> phases = Map.of("ITA", new PhaseRow("ITA", 40, List.of("an"), "2026-10-05", "2026-10-12", 6));
        assertThat(DetailScheduler.overBudget(placed, Map.of("an|ITA", 6.0), phases, "E", A, CAL)).containsExactly(new Overrun("an", "ITA", 40.0, 36.0));
        assertThat(DetailScheduler.overBudget(placed, Map.of(), phases, "E", A, CAL)).isEmpty();
    }

    @Test
    void memberWithoutAllocationIsAnError() {
        assertThatThrownBy(() -> run(Map.of(), single("t", "zz", 4))).isInstanceOf(RuleError.class).hasMessageContaining("zz");
    }
}
