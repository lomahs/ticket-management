package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import tracker.domain.Capacity.DayPlan;
import tracker.domain.Capacity.PlanItem;

/**
 * Effort burndown over the shown working days from the day before the scope's start to the latest Planned End.
 * The first day is the baseline: nothing is planned on it, so the plan still has the whole estimate left.
 * Plan: each estimate spread evenly over its worker's working days ({@link Capacity#dailyPlan}). Actual: estimate − logged hours.
 */
public final class Burndown {
    private Burndown() {
    }

    /** @param memberId the worker; "" when the sub-task has none */
    public record Item(String key, String memberId, String start, String end, double estimateH, List<Worklog> worklogs) {
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

    /** @param planRemainingH and actualRemainingH: at the end of today (the estimate when today is before the range) */
    public record BurndownChart(double estimateH, @Nullable String start, @Nullable String end, double planRemainingH, double actualRemainingH,
                         List<BurndownDay> days) {
    }

    /** The first shown day on or after d, else the last one. */
    private static String snap(List<String> days, String d) {
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
        Capacity.Plan plan = Capacity.dailyPlan(items.stream()
                .map(i -> new PlanItem(i.key(), i.memberId(), i.start(), i.end(), i.estimateH())).toList(), cal);
        Set<String> members = new LinkedHashSet<>();
        items.forEach(i -> members.add(i.memberId()));
        for (String m : members) {
            for (Map.Entry<String, DayPlan> e : plan.member(m).entrySet()) {
                burn.merge(snap(days, e.getKey()), e.getValue().total, Double::sum);
            }
        }
        for (Item i : items) {
            for (Worklog w : i.worklogs()) {
                if (w.date().compareTo(today) <= 0) {
                    logged.merge(snap(days, w.date()), w.hours(), Double::sum);
                }
            }
        }
        List<BurndownDay> out = new ArrayList<>();
        double planned = 0;
        double spent = 0;
        double planToday = est;
        double actToday = est;
        for (String d : days) {
            planned += burn.get(d);
            spent += logged.get(d);
            double planRem = Math.max(0, est - planned);
            boolean past = d.compareTo(today) <= 0;
            if (past) {
                planToday = planRem;
                actToday = est - spent;
            }
            out.add(new BurndownDay(d, Numbers.round(burn.get(d), 2), Numbers.round(logged.get(d), 2), Numbers.round(planRem, 2),
                    past ? Numbers.round(est - spent, 2) : null));
        }
        return new BurndownChart(Numbers.round(est, 2), days.get(0), days.get(days.size() - 1), Numbers.round(planToday, 2),
                Numbers.round(actToday, 2), out);
    }
}
