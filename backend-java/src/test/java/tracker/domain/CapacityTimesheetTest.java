package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tracker.domain.Capacity.PlanItem;

class CapacityTimesheetTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;
    static final List<PlanItem> ITEMS = List.of(
            new PlanItem("PAY-1", "an", "2026-10-05", "2026-10-09", 10),   // 5 working days → 2h/day
            new PlanItem("PAY-2", "chi", "2026-10-08", "2026-10-09", 4),   // 09 is Chi's leave → all 4h on 08
            new PlanItem("PAY-3", "chi", "2026-10-09", "2026-10-09", 3));  // only day is off → lands on its start day

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
        Map<String, List<Capacity.WeekLoad>> weeks = Capacity.weeklyCapacity(items, CAL, List.of("an", "chi"), "2026-10-05", 8, 100, 5);
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
        Map<String, List<Capacity.WeekLoad>> weeks = Capacity.weeklyCapacity(items, CAL, List.of("an"), "2026-10-05", 8, 100, 5);
        assertThat(weeks.get("an")).allMatch(w -> w.plannedH() == 0);
    }

    @Test
    void timesheetStatesTotalsAndTolerance() {
        Map<String, List<Worklog>> logs = Map.of(
                Timesheet.logKey("an", "2026-10-05"), List.of(new Worklog("2026-10-05", "an", 4, "spec")),
                Timesheet.logKey("an", "2026-10-06"), List.of(new Worklog("2026-10-06", "an", 2.9, "spec")),
                Timesheet.logKey("an", "2026-10-07"), List.of(new Worklog("2026-10-07", "an", 1, "x")));
        List<String> days = List.of("2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-10", "2026-10-09");
        List<Timesheet.TimesheetRow> rows = Timesheet.timesheet(List.of("an", "chi"), days, logs, Capacity.dailyPlan(ITEMS, CAL), CAL,
                "2026-10-07", 8, 1);
        Map<String, Timesheet.TimesheetCell> an = byDate(rows.get(0));
        assertThat(an.get("2026-10-05").state()).isEqualTo("over");
        assertThat(an.get("2026-10-06").state()).isEqualTo("ok");
        assertThat(an.get("2026-10-08").state()).isEqualTo("future");
        assertThat(rows.get(0).loggedH()).isEqualTo(7.9);
        assertThat(rows.get(0).expectedH()).isEqualTo(16);
        assertThat(rows.get(0).overDays()).isEqualTo(1);
        Map<String, Timesheet.TimesheetCell> chi = byDate(rows.get(1));
        assertThat(chi.get("2026-10-09").state()).isEqualTo("off");
        assertThat(chi.get("2026-10-09").offReason()).isEqualTo("Chi · All day");
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
