package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/** Timesheet: hours logged per member and day, compared with planned hours and the member's allocation. */
public final class Timesheet {
    private Timesheet() {
    }

    /**
     * @param targetH      allocated hours of the day on the anken in scope, scaled by the member's capacity
     * @param unallocatedH logged on anken in scope that the member has no allocation for
     * @param state        weekend | off | future | empty | ok | over | under | no_allocation
     */
    public record TimesheetCell(String date, double loggedH, double plannedH, double targetH, double unallocatedH, String state,
                                @Nullable String offReason) {
    }

    /** @param targetH allocated hours of past days (today and future excluded) */
    public record TimesheetRow(String memberId, List<TimesheetCell> cells, double loggedH, double targetH, int overDays,
                               int underDays) {
    }

    private static TimesheetCell cell(String m, String d, Map<String, Double> loggedByEpic, double planned, Allocations alloc,
                                      Set<String> scopeEpics, WorkCalendar cal, String today, double toleranceH) {
        String reason = cal.off(d, m);
        double logged = loggedByEpic.values().stream().mapToDouble(Double::doubleValue).sum();
        String offReason = reason == null || reason.equals(WorkCalendar.WEEKEND) ? null : reason;
        if (Text.hasText(reason)) {
            return cellOf(d, logged, planned, 0, 0, reason.equals(WorkCalendar.WEEKEND) ? "weekend" : "off", offReason);
        }
        if (d.compareTo(today) > 0) {
            return cellOf(d, logged, planned, 0, 0, "future", offReason);
        }
        double factor = cal.capacityFactor(m, d);
        double target = 0;
        double loggedA = 0;
        boolean allocated = false;
        for (String e : scopeEpics) {
            if (alloc.hasAny(m, e)) {
                allocated = true;
                target += Objects.requireNonNullElse(alloc.hoursOn(m, e, d), 0.0) * factor;
                loggedA += loggedByEpic.getOrDefault(e, 0.0);
            }
        }
        if (!allocated) {
            return cellOf(d, logged, planned, 0, logged, logged == 0 ? "empty" : "no_allocation", offReason);
        }
        String state;
        if (target == 0 && loggedA == 0) {
            state = "empty";
        } else if (loggedA > target + toleranceH) {
            state = "over";
        } else if (loggedA < target - toleranceH && d.compareTo(today) < 0) {
            state = "under";
        } else {
            state = "ok";
        }
        return cellOf(d, logged, planned, target, logged - loggedA, state, offReason);
    }

    private static TimesheetCell cellOf(String d, double logged, double planned, double target, double unallocated, String state,
                                        @Nullable String offReason) {
        return new TimesheetCell(d, Numbers.round(logged, 2), Numbers.round(planned, 2), Numbers.round(target, 2),
                Numbers.round(unallocated, 2), state, offReason);
    }

    /**
     * @param loggedByKeyAndEpic hours by {@link #logKey}, then by epic
     * @param scopeEpics         the anken in view; only those with an allocation of the member give a target
     */
    public static List<TimesheetRow> timesheet(List<String> memberIds, List<String> days,
                                               Map<String, Map<String, Double>> loggedByKeyAndEpic, Capacity.Plan plan,
                                               WorkCalendar cal, String today, Allocations alloc, Set<String> scopeEpics,
                                               double toleranceH) {
        List<TimesheetRow> rows = new ArrayList<>();
        for (String mid : memberIds) {
            List<TimesheetCell> cells = new ArrayList<>();
            for (String d : days) {
                Capacity.DayPlan planned = plan.day(mid, d);
                cells.add(cell(mid, d, loggedByKeyAndEpic.getOrDefault(logKey(mid, d), Map.of()), planned != null ? planned.total : 0,
                        alloc, scopeEpics, cal, today, toleranceH));
            }
            double target = cells.stream().filter(c -> c.date().compareTo(today) < 0).mapToDouble(TimesheetCell::targetH).sum();
            rows.add(new TimesheetRow(mid, cells, Numbers.round(cells.stream().mapToDouble(TimesheetCell::loggedH).sum(), 2),
                    Numbers.round(target, 2), (int) cells.stream().filter(c -> c.state().equals("over")).count(),
                    (int) cells.stream().filter(c -> c.state().equals("under")).count()));
        }
        return rows;
    }

    public static String logKey(String memberId, String date) {
        return memberId + "\u0000" + date;
    }

    public record DayEntry(String key, double loggedH, double plannedH, List<String> notes) {
    }

    public record DayDetail(String memberId, String date, double loggedH, double plannedH, List<DayEntry> entries) {
    }

    /** Worklogs of one member on one day grouped by issue, plus issues planned that day but not logged. */
    public static DayDetail dayDetail(Map<String, List<Worklog>> logsByKey, Capacity.Plan plan, String memberId, String date) {
        Capacity.DayPlan dp = plan.day(memberId, date);
        Map<String, Double> planned = dp != null ? dp.byKey : Map.of();
        List<DayEntry> entries = new ArrayList<>();
        logsByKey.forEach((key, logs) -> {
            List<String> notes = new ArrayList<>(new LinkedHashSet<>(logs.stream().map(Worklog::note).filter(Text::hasText).toList()));
            entries.add(new DayEntry(key, Numbers.round(logs.stream().mapToDouble(Worklog::hours).sum(), 2),
                    Numbers.round(planned.getOrDefault(key, 0.0), 2), notes));
        });
        planned.forEach((k, h) -> {
            if (!logsByKey.containsKey(k)) {
                entries.add(new DayEntry(k, 0, Numbers.round(h, 2), List.of()));
            }
        });
        return new DayDetail(memberId, date, Numbers.round(entries.stream().mapToDouble(DayEntry::loggedH).sum(), 2),
                Numbers.round(dp != null ? dp.total : 0, 2), entries);
    }
}
