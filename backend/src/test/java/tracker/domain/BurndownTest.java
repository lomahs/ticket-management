package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BurndownTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;

    private static Burndown.Item item(String key, String start, String end, double est, Map<String, Double> plan, List<Worklog> logs,
                                      double remaining, Map<String, Double> seen) {
        return new Burndown.Item(key, start, end, est, plan, logs, remaining, seen);
    }

    @Test
    void planCountsDownByTheHoursPerDayAndActualIsTheRemainingEstimate() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-1", "2026-10-05", "2026-10-09", 10, Map.of("2026-10-05", 4.0, "2026-10-06", 4.0, "2026-10-07", 2.0),
                        List.of(new Worklog("2026-10-05", "an", 3), new Worklog("2026-10-08", "an", 2)),   // 08 is after today
                        6, Map.of("2026-10-05", 7.0, "2026-10-06", 6.5)),
                item("PAY-2", "2026-10-08", "2026-10-09", 4, Map.of("2026-10-08", 4.0), List.of(), 4, Map.of("2026-10-05", 4.0))),
                CAL, "2026-10-07", null);
        // the baseline is the working day before Monday 10/05; the extra Saturday 10/10 that no item reaches is not shown
        assertThat(r.days()).extracting(Burndown.BurndownDay::date)
                .containsExactly("2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09");
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(0.0, 4.0, 4.0, 2.0, 4.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(14.0, 10.0, 6.0, 4.0, 0.0, 0.0);
        // 10/02: no capture, rebuilt: what remains now plus the hours logged since (PAY-1: 6 + 3, PAY-2: 4); 10/05: both
        // captured; 10/06: PAY-1 captured, PAY-2 rebuilt; 10/07: today, live
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(13.0, 11.0, 10.5, 10.0, null, null);
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(0.0, 3.0, 0.0, 0.0, 0.0, 0.0);
        assertThat(r.estimateH()).isEqualTo(14);
        assertThat(r.planRemainingH()).isEqualTo(4);
        assertThat(r.actualRemainingH()).isEqualTo(10);
    }

    @Test
    void anOffDayWithPlannedOrLoggedHoursIsShownAndLogsOutsideTheRangeLandOnItsEnds() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-3", "2026-10-11", "2026-10-12", 6, Map.of("2026-10-11", 1.0, "2026-10-12", 5.0),   // Sunday, then a working Monday
                        List.of(new Worklog("2026-10-01", "an", 1), new Worklog("2026-10-30", "an", 2)), 3, Map.of())), CAL, "2026-10-31", null);
        // the baseline before Sunday 10/11 is the extra working Saturday 10/10; Sunday has planned hours, so it is shown;
        // the early log lands on the baseline, the late one on 10/12
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-10", "2026-10-11", "2026-10-12");
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(0.0, 1.0, 5.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(1.0, 0.0, 2.0);
        // rebuilt from the logs up to today: 3 left now, 2h logged on 10/30
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(5.0, 5.0, 5.0);
        assertThat(r.actualRemainingH()).isEqualTo(3);
    }

    @Test
    void anOffDayWithoutHoursStaysHidden() {
        // Saturday 10/17 has a log, Sunday 10/18 nothing; a log after today does not open its day
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-6", "2026-10-16", "2026-10-20", 6, Map.of("2026-10-16", 2.0, "2026-10-19", 2.0, "2026-10-20", 2.0),
                        List.of(new Worklog("2026-10-17", "an", 1.5)), 4.5, Map.of())), CAL, "2026-10-17", null);
        assertThat(r.days()).extracting(Burndown.BurndownDay::date)
                .containsExactly("2026-10-15", "2026-10-16", "2026-10-17", "2026-10-19", "2026-10-20");
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(0.0, 0.0, 1.5, 0.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(6.0, 6.0, 4.5, null, null);
    }

    @Test
    void beforeTheRangeEverythingRemainsAndNothingIsEmptyWithoutItems() {
        Burndown.BurndownChart r = Burndown.compute(List.of(item("PAY-4", "2026-10-12", "2026-10-13", 8,
                Map.of("2026-10-12", 4.0, "2026-10-13", 4.0), List.of(), 8, Map.of())), CAL, "2026-10-07", null);
        assertThat(r.planRemainingH()).isEqualTo(8);
        assertThat(r.actualRemainingH()).isEqualTo(8);
        assertThat(r.days()).allMatch(d -> d.actualRemainingH() == null);
        assertThat(Burndown.compute(List.of(), CAL, "2026-10-07", null).days()).isEmpty();
    }

    @Test
    void theScopeStartOpensTheChartBeforeTheItemsOwnStart() {
        // filtered to one member, the phase still started on Tuesday 10/06: the chart opens on Monday 10/05
        Burndown.BurndownChart r = Burndown.compute(List.of(item("PAY-5", "2026-10-08", "2026-10-08", 4, Map.of("2026-10-08", 4.0),
                List.of(), 4, Map.of())), CAL, "2026-10-07", "2026-10-06");
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08");
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(4.0, 4.0, 4.0, 0.0);
    }
}
