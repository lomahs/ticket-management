package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Effort burndown over the shown working days from the day before the scope's start to the latest Planned End.
 * The first day is the baseline: nothing is planned on it, so the plan still has the whole estimate left.
 * Plan: the estimate less the hours planned up to the day ({@link PlanDays}). Actual: Jira's remaining estimate, live today;
 * on an earlier day as the Daily Overview capture saw it, else rebuilt from the worklogs as Jira keeps it: what remains now
 * plus the hours logged after that day.
 */
public final class Burndown {
    private Burndown() {
    }

    /**
     * @param planDays    the hours planned on each day ({@link PlanDays#of})
     * @param remainingH  Jira's remaining estimate now (0 once Closed)
     * @param remainingOn the remaining estimate the captures saw, by day
     */
    public record Item(String key, String start, String end, double estimateH, Map<String, Double> planDays, List<Worklog> worklogs,
                       double remainingH, Map<String, Double> remainingOn) {
    }

    /**
     * Remaining hours at the end of the day.
     *
     * @param plannedH         planned to burn on this day
     * @param loggedH          logged on this day (logs outside the range count on its first or last day)
     * @param actualRemainingH null after today
     */
    public record BurndownDay(String date, double plannedH, double loggedH, double planRemainingH, @Nullable Double actualRemainingH) {
    }

    /** @param planRemainingH and actualRemainingH: at the end of today (the plan is the estimate when today is before the range) */
    public record BurndownChart(double estimateH, @Nullable String start, @Nullable String end, double planRemainingH, double actualRemainingH,
                         List<BurndownDay> days) {
    }

    /** The first shown day on or after d, else the last one. */
    static String snap(List<String> days, String d) {
        for (String x : days) {
            if (x.compareTo(d) >= 0) {
                return x;
            }
        }
        return days.get(days.size() - 1);
    }

    /** The shown day before {@code start}: the working day before it, else the day before it. */
    static String dayBefore(String start, WorkCalendar cal) {
        String d = Dates.addDays(start, -1);
        for (int n = 0; n < 14 && !cal.visible(d); n++) {
            d = Dates.addDays(d, -1);
        }
        return cal.visible(d) ? d : Dates.addDays(start, -1);
    }

    /**
     * @param from the start of the scope (anken, phase or sub-phase), which can be before the items' own starts when they are
     *             filtered by member; null: the earliest Planned Start of the items
     */
    public static BurndownChart compute(List<Item> items, WorkCalendar cal, String today, @Nullable String from) {
        double est = items.stream().mapToDouble(Item::estimateH).sum();
        if (items.isEmpty()) {
            return new BurndownChart(0, null, null, 0, 0, List.of());
        }
        String first = items.stream().map(Item::start).min(String::compareTo).orElseThrow();
        String lo = dayBefore(from != null && from.compareTo(first) < 0 ? from : first, cal);
        String hi = items.stream().map(Item::end).max(String::compareTo).orElseThrow();
        List<String> days = Dates.daysBetween(lo, Dates.max(lo, hi)).stream().filter(d -> d.equals(lo) || cal.visible(d)).toList();
        Map<String, Double> burn = new LinkedHashMap<>();
        Map<String, Double> logged = new LinkedHashMap<>();
        days.forEach(d -> {
            burn.put(d, 0.0);
            logged.put(d, 0.0);
        });
        for (Item i : items) {
            i.planDays().forEach((d, h) -> burn.merge(snap(days, d), h, Numbers::sum));
            for (Worklog w : i.worklogs()) {
                if (w.date().compareTo(today) <= 0) {
                    logged.merge(snap(days, w.date()), w.hours(), Numbers::sum);
                }
            }
        }
        double now = items.stream().mapToDouble(Item::remainingH).sum();
        List<BurndownDay> out = new ArrayList<>();
        double planned = 0;
        double planToday = est;
        for (String d : days) {
            planned += burn.get(d);
            double planRem = Math.max(0, est - planned);
            if (d.compareTo(today) <= 0) {
                planToday = planRem;
            }
            Double actual = d.equals(today) ? Double.valueOf(now) : d.compareTo(today) < 0 ? Double.valueOf(actualOn(items, d, today)) : null;
            out.add(new BurndownDay(d, Numbers.round(burn.get(d), 2), Numbers.round(logged.get(d), 2), Numbers.round(planRem, 2),
                    actual == null ? null : Numbers.round(actual, 2)));
        }
        return new BurndownChart(Numbers.round(est, 2), days.get(0), days.get(days.size() - 1), Numbers.round(planToday, 2),
                Numbers.round(now, 2), out);
    }

    /**
     * The remaining hours at the end of {@code day} (before {@code today}): each item's as the capture of that day saw it, else
     * what remains now plus the hours logged after that day up to today.
     */
    private static double actualOn(List<Item> items, String day, String today) {
        double sum = 0;
        for (Item i : items) {
            Double seen = i.remainingOn().get(day);
            if (seen != null) {
                sum += seen;
                continue;
            }
            sum += i.remainingH();
            for (Worklog w : i.worklogs()) {
                if (w.date().compareTo(day) > 0 && w.date().compareTo(today) <= 0) {
                    sum += w.hours();
                }
            }
        }
        return sum;
    }
}
