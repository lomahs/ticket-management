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
                new Burndown.Item("PAY-2", "chi", "2026-10-08", "2026-10-09", 4, List.of())), CAL, "2026-10-07", null);
        // the baseline is the working day before Monday 10/05; the extra Saturday 10/10 that no item reaches is not shown
        assertThat(r.days()).extracting(Burndown.BurndownDay::date)
                .containsExactly("2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09");
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(0.0, 2.0, 2.0, 2.0, 6.0, 2.0); // Chi is off on 09
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(14.0, 12.0, 10.0, 8.0, 2.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(14.0, 11.0, 11.0, 11.0, null, null);
        assertThat(r.start()).isEqualTo("2026-10-02");
        assertThat(r.estimateH()).isEqualTo(14);
        assertThat(r.planRemainingH()).isEqualTo(8);
        assertThat(r.actualRemainingH()).isEqualTo(11);
    }

    @Test
    void offDaysAndLogsOutsideTheRangeLandOnShownDays() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                new Burndown.Item("PAY-3", "", "2026-10-11", "2026-10-12", 6,          // Sunday, then a working Monday
                        List.of(new Worklog("2026-10-01", "an", 1), new Worklog("2026-10-30", "an", 2)))), CAL, "2026-10-31", null);
        // the baseline before Sunday 10/11 is the extra working Saturday 10/10; the early log lands on it, the late one on 10/12
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-10", "2026-10-12");
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(0.0, 6.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(1.0, 2.0);
        assertThat(r.actualRemainingH()).isEqualTo(3);
    }

    @Test
    void beforeTheRangeEverythingRemainsAndNothingIsEmptyWithoutItems() {
        Burndown.BurndownChart r = Burndown.compute(List.of(new Burndown.Item("PAY-4", "an", "2026-10-12", "2026-10-13", 8, List.of())), CAL,
                "2026-10-07", null);
        assertThat(r.planRemainingH()).isEqualTo(8);
        assertThat(r.days()).allMatch(d -> d.actualRemainingH() == null);
        assertThat(Burndown.compute(List.of(), CAL, "2026-10-07", null).days()).isEmpty();
    }

    @Test
    void theScopeStartOpensTheChartBeforeTheItemsOwnStart() {
        // filtered to one member, the phase still started on Tuesday 10/06: the chart opens on Monday 10/05
        Burndown.BurndownChart r = Burndown.compute(List.of(new Burndown.Item("PAY-5", "an", "2026-10-08", "2026-10-08", 4, List.of())), CAL,
                "2026-10-07", "2026-10-06");
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08");
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(4.0, 4.0, 4.0, 0.0);
    }
}
