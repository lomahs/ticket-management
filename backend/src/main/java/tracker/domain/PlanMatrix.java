package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tracker.domain.Capacity.DayPlan;
import tracker.domain.Capacity.PlanItem;

/**
 * The plan per function, person and day: each person's share of a sub-task's estimate spread evenly over their working days
 * ({@link Capacity#dailyPlan}), added up per function. Columns are the shown days from the first Planned Start to the last
 * Planned End; hours planned on a day that is not shown land on the next shown one.
 */
public final class PlanMatrix {
    private PlanMatrix() {
    }

    /** A person's share of one sub-task: one {@link PlanItem} per sub-task and person. */
    public record Item(String phase, String taskKey, String task, String functionKey, String function, PlanItem plan) {
    }

    /** @param hours planned on each of the matrix's days */
    public record PlanRow(String phase, String taskKey, String task, String functionKey, String function, String memberId, double totalH,
                      List<Double> hours) {
    }

    public record PlanGrid(List<String> days, List<PlanRow> rows) {
    }

    /** One row per (phase, sub-phase, function, person), in the order the items first name them. */
    public static PlanGrid compute(List<Item> items, WorkCalendar cal) {
        if (items.isEmpty()) {
            return new PlanGrid(List.of(), List.of());
        }
        String lo = items.stream().map(i -> i.plan().start()).min(String::compareTo).orElseThrow();
        String hi = items.stream().map(i -> i.plan().end()).max(String::compareTo).orElseThrow();
        List<String> days = Dates.daysBetween(lo, Dates.max(lo, hi)).stream().filter(cal::visible).toList();
        if (days.isEmpty()) {
            days = List.of(lo);
        }
        Capacity.Plan plan = Capacity.dailyPlan(items.stream().map(Item::plan).toList(), cal);
        int n = days.size();
        Map<List<String>, Item> first = new LinkedHashMap<>();
        Map<List<String>, double[]> hours = new LinkedHashMap<>();
        for (Item i : items) {
            List<String> k = List.of(i.phase(), i.taskKey(), i.functionKey(), i.plan().memberId());
            first.putIfAbsent(k, i);
            double[] h = hours.computeIfAbsent(k, x -> new double[n]);
            for (Map.Entry<String, DayPlan> e : plan.member(i.plan().memberId()).entrySet()) {
                Double part = e.getValue().byKey.get(i.plan().key());
                if (part != null) {
                    h[days.indexOf(Burndown.snap(days, e.getKey()))] += part;
                }
            }
        }
        List<PlanRow> rows = new ArrayList<>();
        first.forEach((k, i) -> {
            List<Double> h = new ArrayList<>();
            double total = 0;
            for (double x : hours.get(k)) {
                h.add(Numbers.round(x, 2));
                total += x;
            }
            rows.add(new PlanRow(i.phase(), i.taskKey(), i.task(), i.functionKey(), i.function(), i.plan().memberId(), Numbers.round(total, 2), h));
        });
        return new PlanGrid(days, rows);
    }
}
