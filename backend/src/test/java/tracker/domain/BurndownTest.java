package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class BurndownTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;

    @Test
    void planSpreadsEstimatesAndActualSubtractsLoggedUpToToday() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                new Burndown.Item("PAY-1", "an", "2026-10-05", "2026-10-09", 10,
                        List.of(new Worklog("2026-10-05", "an", 3), new Worklog("2026-10-08", "an", 2))),   // 08 is after today
                new Burndown.Item("PAY-2", "chi", "2026-10-08", "2026-10-09", 4, List.of())), CAL, "2026-10-07");
        // Mon–Fri plus the extra Saturday 10/10 that no item reaches: the range ends on the latest Planned End
        assertThat(r.days()).extracting(Burndown.BurndownDay::date)
                .containsExactly("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09");
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(2.0, 2.0, 2.0, 6.0, 2.0); // Chi is off on 09
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(12.0, 10.0, 8.0, 2.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(11.0, 11.0, 11.0, null, null);
        assertThat(r.estimateH()).isEqualTo(14);
        assertThat(r.planRemainingH()).isEqualTo(8);
        assertThat(r.actualRemainingH()).isEqualTo(11);
    }

    @Test
    void offDaysAndLogsOutsideTheRangeLandOnShownDays() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                new Burndown.Item("PAY-3", "", "2026-10-11", "2026-10-12", 6,          // Sunday, then a working Monday
                        List.of(new Worklog("2026-10-01", "an", 1), new Worklog("2026-10-30", "an", 2)))), CAL, "2026-10-31");
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-12");
        assertThat(r.days().get(0).plannedH()).isEqualTo(6);
        assertThat(r.days().get(0).loggedH()).isEqualTo(3);
        assertThat(r.actualRemainingH()).isEqualTo(3);
    }

    @Test
    void beforeTheRangeEverythingRemainsAndNothingIsEmptyWithoutItems() {
        Burndown.BurndownChart r = Burndown.compute(List.of(new Burndown.Item("PAY-4", "an", "2026-10-12", "2026-10-13", 8, List.of())), CAL,
                "2026-10-07");
        assertThat(r.planRemainingH()).isEqualTo(8);
        assertThat(r.days()).allMatch(d -> d.actualRemainingH() == null);
        assertThat(Burndown.compute(List.of(), CAL, "2026-10-07").days()).isEmpty();
    }
}
