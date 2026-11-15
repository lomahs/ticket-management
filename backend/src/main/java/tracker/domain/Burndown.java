package tracker.domain;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.jspecify.annotations.Nullable;

/**
 * Effort burndown over every day, off days included, from the working day before the scope's start to the latest Planned End;
 * hours planned or logged (up to today) outside that span widen it, so each day keeps its own hours. The first day is the
 * baseline: nothing is planned or logged on it, so the plan still has the whole estimate left. The Baseline plan, when the
 * anken has one, is drawn the same way from its own estimates and days (docs/glossary.md).
 * Plan: the estimate less the hours planned up to the day ({@link PlanDays}). Actual: Jira's remaining estimate, live today;
 * on an earlier day as the Daily Overview capture saw it plus the Original Estimate changes approved after it, else rebuilt
 * from the worklogs as Jira keeps it: what remains now plus the hours logged after that day.
 */
public final class Burndown {
    private Burndown() {
    }

    /**
     * @param planDays    the hours planned on each day ({@link PlanDays#of})
     * @param remainingH  Jira's remaining estimate now (0 once Closed)
     * @param remainingOn the remaining estimate the captures saw, by day
     * @param estimateOn  the Original Estimate the captures saw, by day (captures taken before it was kept have none)
     * @param estimateChanges the Original Estimate changes (approved), by day: the hours added (negative: taken off)
     */
    public record Item(String key, String start, String end, double estimateH, Map<String, Double> planDays, List<Worklog> worklogs,
                       double remainingH, Map<String, Double> remainingOn, Map<String, Double> estimateOn, Map<String, Double> estimateChanges) {
    }

    /**
     * Remaining hours at the end of the day.
     *
     * @param off              not a working day of the calendar (a weekend, unless it is an extra working day)
     * @param plannedH         planned to burn on this day
     * @param loggedH          logged on this day
     * @param actualRemainingH null after today
     * @param baselineRemainingH what the Baseline plan leaves at the end of the day; null without a baseline
     */
    public record BurndownDay(String date, boolean off, double plannedH, double loggedH, double planRemainingH, @Nullable Double actualRemainingH,
                              @Nullable Double baselineRemainingH) {
    }

    /**
     * @param planRemainingH and actualRemainingH: at the end of today (the plan is the estimate when today is before the range)
     * @param baselineH      Σ the Baseline plan's Original Estimates; null without a baseline
     */
    public record BurndownChart(double estimateH, @Nullable String start, @Nullable String end, double planRemainingH, double actualRemainingH,
                         List<BurndownDay> days, @Nullable Double baselineH) {
    }

    /** The working day before {@code start}, else the day before it. */
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
        return compute(items, cal, today, from, null);
    }

    /** @param baseline the Baseline plan's sub-tasks in the scope; null: the anken has no baseline */
    public static BurndownChart compute(List<Item> items, WorkCalendar cal, String today, @Nullable String from,
                                        @Nullable List<Baseline.Item> baseline) {
        double est = items.stream().mapToDouble(Item::estimateH).sum();
        Map<String, Double> burn = new HashMap<>();
        Map<String, Double> logged = new HashMap<>();
        for (Item i : items) {
            i.planDays().forEach((d, h) -> burn.merge(d, h, Numbers::sum));
            for (Worklog w : i.worklogs()) {
                if (w.date().compareTo(today) <= 0) {
                    logged.merge(w.date(), w.hours(), Numbers::sum);
                }
            }
        }
        Map<String, Double> base = new HashMap<>();
        double baseEst = 0;
        if (baseline != null) {
            for (Baseline.Item b : baseline) {
                baseEst += b.estimateH();
                b.days().forEach((d, h) -> base.merge(d, h, Numbers::sum));
            }
        }
        // the span: the items' dates and hours, and the Baseline plan's days (it is drawn even when no item is left)
        List<String> dates = Stream.of(items.stream().map(Item::start), items.stream().map(Item::end), burn.keySet().stream(),
                logged.keySet().stream(), base.keySet().stream()).flatMap(x -> x).toList();
        if (dates.isEmpty()) {
            return new BurndownChart(0, null, null, 0, 0, List.of(), null);
        }
        String early = Stream.concat(dates.stream(), Stream.ofNullable(from)).min(String::compareTo).orElseThrow();
        String lo = dayBefore(early, cal);
        String hi = dates.stream().max(String::compareTo).orElseThrow();
        List<String> days = Dates.daysBetween(lo, hi);
        double now = items.stream().mapToDouble(Item::remainingH).sum();
        List<BurndownDay> out = new ArrayList<>();
        double planned = 0;
        double basePlanned = 0;
        double planToday = est;
        for (String d : days) {
            planned += burn.getOrDefault(d, 0.0);
            basePlanned += base.getOrDefault(d, 0.0);
            Double baseRem = baseline == null ? null : Numbers.round(Math.max(0, baseEst - basePlanned), 2);
            double planRem = Math.max(0, est - planned);
            if (d.compareTo(today) <= 0) {
                planToday = planRem;
            }
            Double actual = d.equals(today) ? Double.valueOf(now) : d.compareTo(today) < 0 ? Double.valueOf(actualOn(items, d, today)) : null;
            out.add(new BurndownDay(d, !cal.visible(d), Numbers.round(burn.getOrDefault(d, 0.0), 2),
                    Numbers.round(logged.getOrDefault(d, 0.0), 2), Numbers.round(planRem, 2), actual == null ? null : Numbers.round(actual, 2),
                    baseRem));
        }
        return new BurndownChart(Numbers.round(est, 2), days.get(0), days.get(days.size() - 1), Numbers.round(planToday, 2),
                Numbers.round(now, 2), out, baseline == null ? null : Numbers.round(baseEst, 2));
    }

    /**
     * The remaining hours at the end of {@code day} (before {@code today}): each item's as the capture of that day saw it, plus
     * the Original Estimate changes since (never below 0); else what remains now plus the hours logged after that day up to today.
     */
    private static double actualOn(List<Item> items, String day, String today) {
        double sum = 0;
        for (Item i : items) {
            Double seen = i.remainingOn().get(day);
            if (seen != null) {
                // an Original Estimate change approved after the capture counts from the first day (docs/rules.md): what the
                // estimate moved since the capture saw it, else (an older capture) the changes dated after its day
                Double was = i.estimateOn().get(day);
                double added = was != null ? i.estimateH() - was : i.estimateChanges().entrySet().stream()
                        .filter(c -> c.getKey().compareTo(day) > 0).mapToDouble(Map.Entry::getValue).sum();
                sum += Math.max(0, seen + added);
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
