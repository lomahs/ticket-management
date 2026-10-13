package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Timesheet: hours logged per member and day, compared with planned and expected hours. */
public final class Timesheet {
    private Timesheet() {
    }

    /** @param state weekend | off | future | empty | ok | over */
    public record TimesheetCell(String date, double loggedH, double plannedH, double expectedH, String state,
                                @Nullable String offReason) {
    }

    /** @param expectedH working hours of past days (today and future excluded) */
    public record TimesheetRow(String memberId, List<TimesheetCell> cells, double loggedH, double expectedH, int overDays) {
    }

    private static TimesheetCell cell(String memberId, String d, double logged, double planned, WorkCalendar cal, String today,
                                      double hoursPerDay, double toleranceH) {
        String reason = cal.off(d, memberId);
        double expected = hoursPerDay * cal.capacityFactor(memberId, d);
        String state;
        if (Text.hasText(reason)) {
            state = reason.equals(WorkCalendar.WEEKEND) ? "weekend" : "off";
        } else if (d.compareTo(today) > 0) {
            state = "future";
        } else if (logged == 0 && planned == 0) {
            state = "empty";
        } else {
            state = logged > planned + toleranceH ? "over" : "ok";
        }
        return new TimesheetCell(d, Numbers.round(logged, 2), Numbers.round(planned, 2), expected, state,
                reason == null || reason.equals(WorkCalendar.WEEKEND) ? null : reason);
    }

    /** @param logs worklogs by "member\u0000date" (see {@link #logKey}) */
    public static List<TimesheetRow> timesheet(List<String> memberIds, List<String> days, Map<String, List<Worklog>> logs,
                                               Capacity.Plan plan, WorkCalendar cal, String today, double hoursPerDay,
                                               double toleranceH) {
        List<TimesheetRow> rows = new ArrayList<>();
        for (String mid : memberIds) {
            List<TimesheetCell> cells = new ArrayList<>();
            for (String d : days) {
                double logged = logs.getOrDefault(logKey(mid, d), List.of()).stream().mapToDouble(Worklog::hours).sum();
                Capacity.DayPlan planned = plan.day(mid, d);
                cells.add(cell(mid, d, logged, planned != null ? planned.total : 0, cal, today, hoursPerDay, toleranceH));
            }
            double expected = cells.stream().filter(c -> c.date().compareTo(today) < 0).mapToDouble(TimesheetCell::expectedH).sum();
            rows.add(new TimesheetRow(mid, cells, Numbers.round(cells.stream().mapToDouble(TimesheetCell::loggedH).sum(), 2),
                    expected, (int) cells.stream().filter(c -> c.state().equals("over")).count()));
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
