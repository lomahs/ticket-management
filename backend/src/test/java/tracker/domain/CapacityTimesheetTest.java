package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tracker.domain.Capacity.PlanItem;

class CapacityTimesheetTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;
    static final List<PlanItem> ITEMS = List.of(
            new PlanItem("PAY-1", "an", "2026-10-05", "2026-10-09", 10),   // 5 working days → 2h/day
            new PlanItem("PAY-2", "chi", "2026-10-08", "2026-10-09", 4),   // 09 is Chi's leave → all 4h on 08
            new PlanItem("PAY-3", "chi", "2026-10-09", "2026-10-09", 3));  // only day is off → lands on its start day

    static final Allocations EIGHT_X = new Allocations(List.of(new Allocation("an", "X", "2026-09-01", 8),
            new Allocation("chi", "X", "2026-09-01", 8)));

    @Test
    void capacityUsesAllocation() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 4)));
        List<PlanItem> items = List.of(new PlanItem("PAY-1", "an", "2026-10-19", "2026-10-23", 20));
        Capacity.WeekLoad w = Capacity.weeklyCapacity(items, CAL, List.of("an"), "2026-10-19", alloc, Set.of("X"), 100, 1).get("an").get(0);
        assertThat(w.availableH()).isEqualTo(20); // Mon-Fri at 4h
        assertThat(w.pct()).isEqualTo(100);
        assertThat(w.level()).isEqualTo("near");
        assertThat(Capacity.weeklyCapacity(items, CAL, List.of("an"), "2026-10-19", alloc, Set.of("X"), 90, 1).get("an").get(0).level())
                .isEqualTo("over");
    }

    @Test
    void capacityWithoutAllocationIsNotOver() {
        List<PlanItem> items = List.of(new PlanItem("PAY-1", "an", "2026-10-05", "2026-10-09", 20));
        Capacity.WeekLoad w = Capacity.weeklyCapacity(items, CAL, List.of("an"), "2026-10-05", new Allocations(List.of()), Set.of("X"), 100, 1)
                .get("an").get(0);
        assertThat(w.pct()).isNull();
        assertThat(w.level()).isEqualTo("no_allocation");
        assertThat(w.plannedH()).isEqualTo(20);
    }

    @Test
    void dailyPlanSpreadsEstimateOverWorkingDays() {
        Capacity.Plan p = Capacity.dailyPlan(ITEMS, CAL);
        assertThat(p.day("an", "2026-10-07").total).isEqualTo(2);
        assertThat(p.day("chi", "2026-10-08").byKey).isEqualTo(Map.of("PAY-2", 4.0));
        assertThat(p.day("chi", "2026-10-09").byKey).isEqualTo(Map.of("PAY-3", 3.0));
    }

    @Test
    void weeklyCapacityLevels() {
        List<PlanItem> items = new ArrayList<>(ITEMS);
        items.add(new PlanItem("PAY-4", "an", "2026-10-12", "2026-10-16", 38));
        Map<String, List<Capacity.WeekLoad>> weeks = Capacity.weeklyCapacity(items, CAL, List.of("an", "chi"), "2026-10-05", EIGHT_X, Set.of("X"), 100, 5);
        List<Capacity.WeekLoad> an = weeks.get("an");
        assertThat(an.get(0).plannedH()).isEqualTo(10);
        assertThat(an.get(0).availableH()).isEqualTo(48); // Mon–Fri + extra Saturday 10/10
        assertThat(an.get(0).pct()).isEqualTo(21);
        assertThat(an.get(0).level()).isEqualTo("idle");
        assertThat(an.get(1).pct()).isEqualTo(95);
        assertThat(an.get(1).level()).isEqualTo("near");
        assertThat(weeks.get("chi").get(0).availableH()).isEqualTo(40); // leave on Friday
        assertThat(an).hasSize(5);
    }

    @Test
    void itemsBeforeTheWindowAreNotCounted() {
        List<PlanItem> items = List.of(new PlanItem("OLD", "an", "2026-09-30", "2026-10-02", 6));
        Map<String, List<Capacity.WeekLoad>> weeks = Capacity.weeklyCapacity(items, CAL, List.of("an"), "2026-10-05", EIGHT_X, Set.of("X"), 100, 5);
        assertThat(weeks.get("an")).allMatch(w -> w.plannedH() == 0);
    }

    static Map<String, Map<String, Double>> logged(Object... keyDateEpicHours) {
        Map<String, Map<String, Double>> out = new java.util.HashMap<>();
        for (int i = 0; i < keyDateEpicHours.length; i += 4) {
            out.computeIfAbsent(Timesheet.logKey((String) keyDateEpicHours[i], (String) keyDateEpicHours[i + 1]), k -> new java.util.HashMap<>())
                    .merge((String) keyDateEpicHours[i + 2], ((Number) keyDateEpicHours[i + 3]).doubleValue(), Double::sum);
        }
        return out;
    }

    static Timesheet.TimesheetCell cellOf(WorkCalendar cal, Map<String, Map<String, Double>> logs, Allocations alloc, Set<String> scope,
                                          String date, String today) {
        return Timesheet.timesheet(List.of("an"), List.of(date), logs, Capacity.dailyPlan(List.of(), cal), cal, today, alloc, scope, 1)
                .get(0).cells().get(0);
    }

    @Test
    void timesheetStatesTotalsAndTolerance() {
        Map<String, Map<String, Double>> logs = logged("an", "2026-10-05", "X", 4, "an", "2026-10-06", "X", 2.9, "an", "2026-10-07", "X", 1);
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 2), new Allocation("chi", "X", "2026-10-01", 2)));
        List<String> days = List.of("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-10", "2026-10-09");
        List<Timesheet.TimesheetRow> rows = Timesheet.timesheet(List.of("an", "chi"), days, logs, Capacity.dailyPlan(ITEMS, CAL), CAL,
                "2026-10-07", alloc, Set.of("X"), 1);
        Map<String, Timesheet.TimesheetCell> an = byDate(rows.get(0));
        assertThat(an.get("2026-10-05").state()).isEqualTo("over");
        assertThat(an.get("2026-10-06").state()).isEqualTo("ok");
        assertThat(an.get("2026-10-07").state()).isEqualTo("ok");
        assertThat(an.get("2026-10-08").state()).isEqualTo("future");
        assertThat(rows.get(0).loggedH()).isEqualTo(7.9);
        assertThat(rows.get(0).targetH()).isEqualTo(4);
        assertThat(rows.get(0).overDays()).isEqualTo(1);
        assertThat(rows.get(0).underDays()).isEqualTo(0);
        Map<String, Timesheet.TimesheetCell> chi = byDate(rows.get(1));
        assertThat(chi.get("2026-10-09").state()).isEqualTo("off");
        assertThat(chi.get("2026-10-09").offReason()).isEqualTo("Chi · All day");
    }

    @Test
    void multiAnkenMemberIsOkEverywhere() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 5), new Allocation("an", "Y", "2026-10-01", 3)));
        Map<String, Map<String, Double>> logs = logged("an", "2026-10-06", "X", 5, "an", "2026-10-06", "Y", 3);
        var x = cellOf(CAL, logs, alloc, Set.of("X"), "2026-10-06", "2026-10-07");
        var y = cellOf(CAL, logs, alloc, Set.of("Y"), "2026-10-06", "2026-10-07");
        var both = cellOf(CAL, logs, alloc, Set.of("X", "Y"), "2026-10-06", "2026-10-07");
        assertThat(x.state()).isEqualTo("ok");
        assertThat(x.targetH()).isEqualTo(5);
        assertThat(y.state()).isEqualTo("ok");
        assertThat(y.targetH()).isEqualTo(3);
        assertThat(both.state()).isEqualTo("ok");
        assertThat(both.targetH()).isEqualTo(8);
    }

    @Test
    void targetFollowsTheEffectiveDate() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 5), new Allocation("an", "X", "2026-11-02", 8)));
        assertThat(cellOf(CAL, Map.of(), alloc, Set.of("X"), "2026-10-30", "2026-12-01").targetH()).isEqualTo(5);
        assertThat(cellOf(CAL, Map.of(), alloc, Set.of("X"), "2026-11-02", "2026-12-01").targetH()).isEqualTo(8);
    }

    @Test
    void leaveScalesTheTarget() {
        WorkCalendar cal = new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), CalendarTest.HOLIDAYS, SchedulingTest.M,
                List.of(new Leave(1, "an", "2026-10-06", LeaveType.HALF, ""), new Leave(2, "an", "2026-10-08", LeaveType.FULL, "")));
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 8)));
        assertThat(cellOf(cal, Map.of(), alloc, Set.of("X"), "2026-10-06", "2026-10-20").targetH()).isEqualTo(4);
        assertThat(cellOf(cal, Map.of(), alloc, Set.of("X"), "2026-10-08", "2026-10-20").state()).isEqualTo("off");
    }

    @Test
    void unallocatedAnkenNeverRaisesOver() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 8)));
        Map<String, Map<String, Double>> logs = logged("an", "2026-10-06", "Y", 8);
        var onlyY = cellOf(CAL, logs, alloc, Set.of("Y"), "2026-10-06", "2026-10-07");
        assertThat(onlyY.state()).isEqualTo("no_allocation");
        assertThat(onlyY.unallocatedH()).isEqualTo(8);
        var both = cellOf(CAL, logs, alloc, Set.of("X", "Y"), "2026-10-06", "2026-10-07");
        assertThat(both.targetH()).isEqualTo(8);
        assertThat(both.unallocatedH()).isEqualTo(8);
        assertThat(both.state()).isEqualTo("under");
    }

    @Test
    void noAllocationAndNothingLoggedIsEmpty() {
        assertThat(cellOf(CAL, Map.of(), new Allocations(List.of()), Set.of("Y"), "2026-10-06", "2026-10-07").state()).isEqualTo("empty");
    }

    @Test
    void allocationOnlyCountsFromItsEffectiveDate() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-11-02", 8)));
        Map<String, Map<String, Double>> logs = logged("an", "2026-10-15", "X", 8, "an", "2026-11-03", "X", 8);
        var before = cellOf(CAL, logs, alloc, Set.of("X"), "2026-10-15", "2026-12-01");
        assertThat(before.state()).isEqualTo("no_allocation");
        assertThat(before.unallocatedH()).isEqualTo(8);
        assertThat(cellOf(CAL, logs, alloc, Set.of("X"), "2026-11-03", "2026-12-01").state()).isEqualTo("ok");
    }

    @Test
    void underOnlyBeforeToday() {
        Allocations alloc = new Allocations(List.of(new Allocation("an", "X", "2026-10-01", 5)));
        Map<String, Map<String, Double>> logs = logged("an", "2026-10-06", "X", 2, "an", "2026-10-07", "X", 2);
        assertThat(cellOf(CAL, logs, alloc, Set.of("X"), "2026-10-07", "2026-10-07").state()).isEqualTo("ok");
        assertThat(cellOf(CAL, logs, alloc, Set.of("X"), "2026-10-06", "2026-10-07").state()).isEqualTo("under");
    }

    private static Map<String, Timesheet.TimesheetCell> byDate(Timesheet.TimesheetRow row) {
        Map<String, Timesheet.TimesheetCell> out = new java.util.HashMap<>();
        row.cells().forEach(c -> out.put(c.date(), c));
        return out;
    }

    @Test
    void dayDetailGroupsByIssueAndAddsPlannedIssues() {
        List<Worklog> logs = List.of(new Worklog("2026-10-07", "an", 1, "spec"), new Worklog("2026-10-07", "an", 0.5, "fix"));
        Timesheet.DayDetail d = Timesheet.dayDetail(Map.of("PAY-1", logs), Capacity.dailyPlan(ITEMS, CAL), "an", "2026-10-07");
        assertThat(d.loggedH()).isEqualTo(1.5);
        assertThat(d.plannedH()).isEqualTo(2);
        assertThat(d.entries()).containsExactly(new Timesheet.DayEntry("PAY-1", 1.5, 2, List.of("spec", "fix")));
    }
}
