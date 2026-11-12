package tracker.domain;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

/**
 * The hours planned on each day of a sub-task, from which its plan remain counts down: the hours Detail Planning put on each
 * day while they still fit the sub-task (on its planned dates, adding up to its estimate), else its estimate spread evenly over
 * the worker's working days.
 */
public final class PlanDays {
    private static final double EPS = 0.01;

    private PlanDays() {
    }

    /**
     * @param worker whose working days the even spread uses; "" when nobody
     * @param saved  Detail Planning's hours per day of {@code i}, all its people together; null or empty when it has none
     * @return date → hours, by date; empty without planned dates or an estimate
     */
    public static Map<String, Double> of(Issue i, String worker, @Nullable Map<String, Double> saved, WorkCalendar cal) {
        Map<String, Double> out = new TreeMap<>();
        if (!Text.hasText(i.plannedStart()) || !Text.hasText(i.plannedEnd()) || i.estimateH() <= 0) {
            return out;
        }
        if (saved != null && fits(i, saved)) {
            out.putAll(saved);
            return out;
        }
        Capacity.Plan plan = Capacity.dailyPlan(
                List.of(new Capacity.PlanItem(i.key(), worker, i.plannedStart(), i.plannedEnd(), i.estimateH())), cal);
        plan.member(worker).forEach((d, p) -> out.put(d, p.total));
        return out;
    }

    private static boolean fits(Issue i, Map<String, Double> saved) {
        if (saved.isEmpty()) {
            return false;
        }
        double sum = 0;
        for (Map.Entry<String, Double> e : saved.entrySet()) {
            if (e.getKey().compareTo(i.plannedStart()) < 0 || e.getKey().compareTo(i.plannedEnd()) > 0) {
                return false;
            }
            sum += e.getValue();
        }
        return Math.abs(sum - i.estimateH()) <= EPS;
    }

    /** What the plan leaves at the end of {@code date}: the estimate less the hours planned up to that day, never below 0. */
    public static double remainAt(double estimateH, Map<String, Double> days, String date) {
        double planned = 0;
        for (Map.Entry<String, Double> e : days.entrySet()) {
            if (e.getKey().compareTo(date) <= 0) {
                planned += e.getValue();
            }
        }
        return Math.max(0, Numbers.round(estimateH - planned, 2));
    }
}
