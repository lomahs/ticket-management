package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

class BurndownTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;

    private static Burndown.Item item(String key, String start, String end, double est, Map<String, Double> plan, List<Worklog> logs,
                                      double remaining, Map<String, Double> seen) {
        return item(key, start, end, est, plan, logs, remaining, seen, Map.of());
    }

    private static Burndown.Item item(String key, String start, String end, double est, Map<String, Double> plan, List<Worklog> logs,
                                      double remaining, Map<String, Double> seen, Map<String, Double> estimateChanges) {
        return new Burndown.Item(key, start, end, est, plan, logs, remaining, seen, Map.of(), estimateChanges);
    }

    /** The spec's example: Mon 10/19 – Fri 10/23, 70h after +20h approved on Wed; today Wed. */
    private static Map<String, Burndown.BurndownDay> approved(Map<String, Double> estimateChanges) {
        Burndown.BurndownChart r = Burndown.compute(List.of(item("PAY-7", "2026-10-19", "2026-10-23", 70,
                Map.of("2026-10-19", 10.0, "2026-10-20", 10.0, "2026-10-21", 16.0, "2026-10-22", 17.0, "2026-10-23", 17.0),
                List.of(new Worklog("2026-10-19", "an", 10), new Worklog("2026-10-20", "an", 10), new Worklog("2026-10-21", "an", 16)),
                34, Map.of("2026-10-19", 40.0, "2026-10-20", 30.0), estimateChanges)), CAL, "2026-10-21", null);
        return r.days().stream().collect(Collectors.toMap(Burndown.BurndownDay::date, d -> d));
    }

    @Test
    void anApprovedEstimateChangeIsCarriedBackOverTheCapturedDays() {
        Map<String, Burndown.BurndownDay> on = approved(Map.of("2026-10-21", 20.0));
        assertThat(List.of("2026-10-16", "2026-10-19", "2026-10-20", "2026-10-21", "2026-10-22", "2026-10-23"))
                .extracting(d -> on.get(d).planRemainingH()).containsExactly(70.0, 60.0, 50.0, 34.0, 17.0, 0.0);
        // 10/16 rebuilt (34 + 36 logged since); Mon and Tue captured (40, 30) + the 20h approved after them; Wed live
        assertThat(List.of("2026-10-16", "2026-10-19", "2026-10-20", "2026-10-21"))
                .extracting(d -> on.get(d).actualRemainingH()).containsExactly(70.0, 60.0, 50.0, 34.0);
    }

    @Test
    void aDecreaseIsTakenOffAndAChangeCountsOnlyAfterItsDay() {
        assertThat(approved(Map.of("2026-10-21", -5.0)).get("2026-10-19").actualRemainingH()).isEqualTo(35);
        // approved on Tue: Tue's capture already has it, Mon's does not
        Map<String, Burndown.BurndownDay> tue = approved(Map.of("2026-10-20", 20.0));
        assertThat(tue.get("2026-10-20").actualRemainingH()).isEqualTo(30);
        assertThat(tue.get("2026-10-19").actualRemainingH()).isEqualTo(60);
    }

    @Test
    void planCountsDownByTheHoursPerDayAndActualIsTheRemainingEstimate() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-1", "2026-10-05", "2026-10-09", 10, Map.of("2026-10-05", 4.0, "2026-10-06", 4.0, "2026-10-07", 2.0),
                        List.of(new Worklog("2026-10-05", "an", 3), new Worklog("2026-10-08", "an", 2)),   // 08 is after today
                        6, Map.of("2026-10-05", 7.0, "2026-10-06", 6.5)),
                item("PAY-2", "2026-10-08", "2026-10-09", 4, Map.of("2026-10-08", 4.0), List.of(), 4, Map.of("2026-10-05", 4.0))),
                CAL, "2026-10-07", null);
        // the baseline is the working day before Monday 10/05, then the weekend; the extra Saturday 10/10 that no item reaches is
        // not shown
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-02", "2026-10-03", "2026-10-04",
                "2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09");
        assertThat(r.days()).extracting(Burndown.BurndownDay::off).containsExactly(false, true, true, false, false, false, false, false);
        assertThat(r.days()).extracting(Burndown.BurndownDay::plannedH).containsExactly(0.0, 0.0, 0.0, 4.0, 4.0, 2.0, 4.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::planRemainingH).containsExactly(14.0, 14.0, 14.0, 10.0, 6.0, 4.0, 0.0, 0.0);
        // 10/02-04: no capture, rebuilt: what remains now plus the hours logged since (PAY-1: 6 + 3, PAY-2: 4); 10/05: both
        // captured; 10/06: PAY-1 captured, PAY-2 rebuilt; 10/07: today, live
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH)
                .containsExactly(13.0, 13.0, 13.0, 11.0, 10.5, 10.0, null, null);
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(0.0, 0.0, 0.0, 3.0, 0.0, 0.0, 0.0, 0.0);
        assertThat(r.estimateH()).isEqualTo(14);
        assertThat(r.planRemainingH()).isEqualTo(4);
        assertThat(r.actualRemainingH()).isEqualTo(10);
    }

    @Test
    void eachItemOfADaySumsToTheChartsPointAndNamesWhereItsActualComesFrom() {
        List<Burndown.Item> items = List.of(
                item("PAY-1", "2026-10-05", "2026-10-09", 10, Map.of("2026-10-05", 4.0, "2026-10-06", 4.0, "2026-10-07", 2.0),
                        List.of(new Worklog("2026-10-05", "an", 3), new Worklog("2026-10-08", "an", 2)), 6, Map.of("2026-10-05", 7.0, "2026-10-06", 6.5)),
                item("PAY-2", "2026-10-08", "2026-10-09", 4, Map.of("2026-10-08", 4.0), List.of(), 4, Map.of("2026-10-05", 4.0)));
        Map<String, Burndown.BurndownDay> chart = Burndown.compute(items, CAL, "2026-10-07", null).days().stream()
                .collect(Collectors.toMap(Burndown.BurndownDay::date, d -> d));
        for (String day : List.of("2026-10-02", "2026-10-05", "2026-10-06", "2026-10-07")) {
            List<Burndown.ItemOn> on = Burndown.on(items, day, "2026-10-07");
            assertThat(on.stream().mapToDouble(Burndown.ItemOn::planRemainingH).sum()).isEqualTo(chart.get(day).planRemainingH());
            assertThat(on.stream().mapToDouble(Burndown.ItemOn::actualRemainingH).sum()).isEqualTo(chart.get(day).actualRemainingH());
        }
        // 10/06: PAY-1 planned 8 of 10, logged 3, captured 6.5; PAY-2 not captured that day: rebuilt (4 now, nothing logged since)
        assertThat(Burndown.on(items, "2026-10-06", "2026-10-07")).containsExactly(
                new Burndown.ItemOn("PAY-1", 10, 8, 3.0, 2, 6.5, Burndown.RemainSource.CAPTURE),
                new Burndown.ItemOn("PAY-2", 4, 0, 0.0, 4, 4.0, Burndown.RemainSource.WORKLOGS));
        assertThat(Burndown.on(items, "2026-10-07", "2026-10-07")).extracting(Burndown.ItemOn::source)
                .containsOnly(Burndown.RemainSource.JIRA);
        // after today: the plan only
        assertThat(Burndown.on(items, "2026-10-08", "2026-10-07")).containsExactly(
                new Burndown.ItemOn("PAY-1", 10, 10, null, 0, null, null),
                new Burndown.ItemOn("PAY-2", 4, 4, null, 0, null, null));
    }

    @Test
    void logsBeforeTheStartOrAfterTheEndWidenTheChart() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-3", "2026-10-11", "2026-10-12", 6, Map.of("2026-10-11", 1.0, "2026-10-12", 5.0),   // Sunday, then a working Monday
                        List.of(new Worklog("2026-10-01", "an", 1), new Worklog("2026-10-30", "an", 2)), 3, Map.of())), CAL, "2026-10-31", null);
        // the baseline is the working day before the early log of Thursday 10/01; the late log of 10/30 ends the chart
        assertThat(r.start()).isEqualTo("2026-09-30");
        assertThat(r.end()).isEqualTo("2026-10-30");
        assertThat(r.days()).hasSize(31);
        Map<String, Burndown.BurndownDay> on = r.days().stream().collect(Collectors.toMap(Burndown.BurndownDay::date, d -> d));
        assertThat(on.get("2026-10-01").loggedH()).isEqualTo(1);
        assertThat(on.get("2026-10-30").loggedH()).isEqualTo(2);
        // Sunday keeps its own planned hour; the extra working Saturday 10/10 is not an off day
        assertThat(on.get("2026-10-11").plannedH()).isEqualTo(1);
        assertThat(on.get("2026-10-11").off()).isTrue();
        assertThat(on.get("2026-10-10").off()).isFalse();
        // rebuilt from the logs up to today: 3 left now, 2h logged on 10/30, 1h on 10/01
        assertThat(on.get("2026-09-30").actualRemainingH()).isEqualTo(6);
        assertThat(on.get("2026-10-29").actualRemainingH()).isEqualTo(5);
        assertThat(on.get("2026-10-30").actualRemainingH()).isEqualTo(3);
        assertThat(r.actualRemainingH()).isEqualTo(3);
    }

    @Test
    void theWeekendIsShownAndALogAfterTodayIsNot() {
        Burndown.BurndownChart r = Burndown.compute(List.of(
                item("PAY-6", "2026-10-16", "2026-10-20", 6, Map.of("2026-10-16", 2.0, "2026-10-19", 2.0, "2026-10-20", 2.0),
                        List.of(new Worklog("2026-10-17", "an", 1.5), new Worklog("2026-10-18", "an", 1)), 4.5, Map.of())), CAL, "2026-10-17", null);
        assertThat(r.days()).extracting(Burndown.BurndownDay::date)
                .containsExactly("2026-10-15", "2026-10-16", "2026-10-17", "2026-10-18", "2026-10-19", "2026-10-20");
        assertThat(r.days()).extracting(Burndown.BurndownDay::off).containsExactly(false, false, true, true, false, false);
        assertThat(r.days()).extracting(Burndown.BurndownDay::loggedH).containsExactly(0.0, 0.0, 1.5, 0.0, 0.0, 0.0);
        assertThat(r.days()).extracting(Burndown.BurndownDay::actualRemainingH).containsExactly(6.0, 6.0, 4.5, null, null, null);
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

    @Test
    void theBaselineCountsDownItsOwnDaysAndWidensTheChart() {
        List<Burndown.Item> items = List.of(item("PAY-1", "2026-10-05", "2026-10-09", 10,
                Map.of("2026-10-05", 4.0, "2026-10-06", 4.0, "2026-10-07", 2.0), List.of(), 10, Map.of()));
        Burndown.BurndownChart r = Burndown.compute(items, CAL, "2026-10-07", null,
                List.of(new Baseline.Item("PAY-1", 8, "T-1", "an", Map.of("2026-10-01", 4.0, "2026-10-02", 4.0))));
        assertThat(r.start()).isEqualTo("2026-09-30");
        Map<String, Burndown.BurndownDay> on = r.days().stream().collect(Collectors.toMap(Burndown.BurndownDay::date, d -> d));
        assertThat(List.of("2026-09-30", "2026-10-01", "2026-10-02", "2026-10-09"))
                .extracting(d -> on.get(d).baselineRemainingH()).containsExactly(8.0, 4.0, 0.0, 0.0);
        assertThat(r.baselineH()).isEqualTo(8);
        Burndown.BurndownChart none = Burndown.compute(items, CAL, "2026-10-07", null);
        assertThat(none.days()).allMatch(d -> d.baselineRemainingH() == null);
        assertThat(none.baselineH()).isNull();
    }

    @Test
    void anEstimateChangeLaterOnTheCaptureDayIsCarriedBack() {
        // the Wed 18:00 capture saw 50h and 14h left; the +20h was approved at 19:00 the same day; today Thu
        Burndown.Item i = new Burndown.Item("PAY-7", "2026-10-19", "2026-10-23", 70,
                Map.of("2026-10-19", 10.0, "2026-10-20", 10.0, "2026-10-21", 16.0, "2026-10-22", 17.0, "2026-10-23", 17.0), List.of(), 34,
                Map.of("2026-10-21", 14.0), Map.of("2026-10-21", 50.0), Map.of("2026-10-21", 20.0));
        Burndown.BurndownChart r = Burndown.compute(List.of(i), CAL, "2026-10-22", null);
        assertThat(r.days().stream().filter(d -> d.date().equals("2026-10-21")).findFirst().orElseThrow().actualRemainingH()).isEqualTo(34);
    }

    @Test
    void aCarriedBackDecreaseNeverTakesAnItemBelowZero() {
        // Monday's capture saw 20h and 3h left; the estimate is 8h now: 3 − 12 stays 0
        Burndown.Item i = new Burndown.Item("PAY-8", "2026-10-19", "2026-10-23", 8, Map.of("2026-10-19", 8.0), List.of(), 1,
                Map.of("2026-10-19", 3.0), Map.of("2026-10-19", 20.0), Map.of("2026-10-20", -12.0));
        Burndown.BurndownChart r = Burndown.compute(List.of(i), CAL, "2026-10-21", null);
        assertThat(r.days().stream().filter(d -> d.date().equals("2026-10-19")).findFirst().orElseThrow().actualRemainingH()).isZero();
    }

    private static Map<String, Double> actualOn(Burndown.Item i, String today) {
        Map<String, Double> out = new java.util.HashMap<>();
        Burndown.compute(List.of(i), CAL, today, null).days().forEach(d -> out.put(d.date(), d.actualRemainingH()));
        return out;
    }

    @Test
    void theChangelogGivesTheRemainingOfADayWithoutCapture() {
        // 4h, overrun: 2h logged Mon (4 → 2), 5.5h Tue (2 → 0); worklogs alone would rebuild Friday as 0 + 7.5
        Burndown.Item i = new Burndown.Item("PAY-1213", "2026-10-19", "2026-10-20", 4, Map.of("2026-10-19", 2.0, "2026-10-20", 2.0),
                List.of(new Worklog("2026-10-19", "an", 2), new Worklog("2026-10-20", "an", 5.5)), 0, Map.of(), Map.of(), Map.of(),
                List.of(new Burndown.RemainingChange("2026-10-19", 4.0, 2.0), new Burndown.RemainingChange("2026-10-20", 2.0, 0.0)));
        Map<String, Double> on = actualOn(i, "2026-10-21");
        assertThat(List.of(on.get("2026-10-16"), on.get("2026-10-19"), on.get("2026-10-20"))).containsExactly(4.0, 2.0, 0.0);
    }

    @Test
    void aReEstimateByTheTeamShowsOnItsDay() {
        Burndown.Item i = new Burndown.Item("PAY-9", "2026-10-19", "2026-10-20", 8, Map.of("2026-10-19", 4.0, "2026-10-20", 4.0), List.of(), 12,
                Map.of(), Map.of(), Map.of(), List.of(new Burndown.RemainingChange("2026-10-20", 8.0, 12.0)));
        Map<String, Double> on = actualOn(i, "2026-10-21");
        assertThat(List.of(on.get("2026-10-16"), on.get("2026-10-19"), on.get("2026-10-20"))).containsExactly(8.0, 8.0, 12.0);
    }

    @Test
    void anEstimateSetLaterCountsFromTheFirstDayInTheChangelogToo() {
        // Remaining and Original Estimate set on Tue (none → 8h): approved, so Monday shows the 8h too
        Burndown.Item i = new Burndown.Item("PAY-10", "2026-10-19", "2026-10-20", 8, Map.of("2026-10-19", 4.0, "2026-10-20", 4.0), List.of(), 8,
                Map.of(), Map.of(), Map.of("2026-10-20", 8.0), List.of(new Burndown.RemainingChange("2026-10-20", null, 8.0)));
        Map<String, Double> on = actualOn(i, "2026-10-21");
        assertThat(List.of(on.get("2026-10-16"), on.get("2026-10-19"), on.get("2026-10-20"))).containsExactly(8.0, 8.0, 8.0);
    }

    @Test
    void theBaselineIsDrawnWhenNoItemIsLeft() {
        Burndown.BurndownChart r = Burndown.compute(List.of(), CAL, "2026-10-07", null,
                List.of(new Baseline.Item("PAY-9", 6, "T-1", "an", Map.of("2026-10-05", 2.0, "2026-10-06", 4.0))));
        assertThat(r.baselineH()).isEqualTo(6);
        assertThat(r.days()).extracting(Burndown.BurndownDay::date).containsExactly("2026-10-02", "2026-10-03", "2026-10-04", "2026-10-05", "2026-10-06");
        assertThat(r.days()).extracting(Burndown.BurndownDay::baselineRemainingH).containsExactly(6.0, 6.0, 6.0, 4.0, 0.0);
        assertThat(Burndown.compute(List.of(), CAL, "2026-10-07", null, List.of()).days()).isEmpty();
    }
}
