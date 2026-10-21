package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/** Planned load: each item's estimate spread over its working days; weekly load against available hours. */
public final class Capacity {
    private Capacity() {
    }

    /** @param memberId the worker */
    public record PlanItem(String key, String memberId, String start, String end, double estimateH) {
    }

    public static final class DayPlan {
        public double total = 0;
        public final Map<String, Double> byKey = new LinkedHashMap<>();
    }

    /** member → date → planned hours. */
    public static final class Plan {
        private final Map<String, Map<String, DayPlan>> byMember = new LinkedHashMap<>();

        public Map<String, DayPlan> member(String memberId) {
            return byMember.getOrDefault(memberId, Map.of());
        }

        public DayPlan day(String memberId, String date) {
            return member(memberId).get(date);
        }

        DayPlan dayForUpdate(String memberId, String date) {
            return byMember.computeIfAbsent(memberId, k -> new LinkedHashMap<>()).computeIfAbsent(date, k -> new DayPlan());
        }
    }

    /** Spreads each estimate evenly over the item's working days (its start day when it has none). */
    public static Plan dailyPlan(Iterable<PlanItem> items, WorkCalendar cal) {
        Plan plan = new Plan();
        for (PlanItem it : items) {
            if (it.estimateH() == 0) {
                continue;
            }
            List<String> days = new ArrayList<>();
            for (String d : Dates.daysBetween(it.start(), it.end())) {
                if (!Text.hasText(cal.off(d, it.memberId()))) {
                    days.add(d);
                }
            }
            if (days.isEmpty()) {
                days.add(it.start());
            }
            double per = it.estimateH() / days.size();
            for (String d : days) {
                DayPlan dp = plan.dayForUpdate(it.memberId(), d);
                dp.total += per;
                dp.byKey.put(it.key(), dp.byKey.getOrDefault(it.key(), 0.0) + per);
            }
        }
        return plan;
    }

    /** @param pct null when the member has no available hours that week; level is idle | ok | near | over | no_allocation */
    public record WeekLoad(String weekStart, double plannedH, double availableH, @Nullable Integer pct, String level) {
    }

    private static String level(Integer pct, double capPct) {
        if (pct == null) {
            return "idle";
        }
        if (pct > capPct) {
            return "over";
        }
        if (pct >= capPct * 0.85) {
            return "near";
        }
        return pct >= 40 ? "ok" : "idle";
    }

    /**
     * Available hours come from allocation: per day, the hours of every scope anken the member has an effective allocation on,
     * times the capacity factor. A week in which no scope anken has an allocation on any day is {@code no_allocation}.
     */
    public static Map<String, List<WeekLoad>> weeklyCapacity(List<PlanItem> items, WorkCalendar cal, List<String> memberIds,
                                                             String start, Allocations alloc, Set<String> scopeEpics,
                                                             double capPct, int weeks) {
        Plan plan = dailyPlan(items, cal);
        Map<String, List<WeekLoad>> out = new LinkedHashMap<>();
        for (String mid : memberIds) {
            double[] loads = new double[weeks];
            for (Map.Entry<String, DayPlan> e : plan.member(mid).entrySet()) {
                int w = Math.floorDiv(Dates.dayDiff(start, e.getKey()), 7);
                if (0 <= w && w < weeks) {
                    loads[w] += e.getValue().total;
                }
            }
            List<WeekLoad> rows = new ArrayList<>();
            for (int w = 0; w < weeks; w++) {
                String ws = Dates.addDays(start, w * 7L);
                double avail = 0;
                boolean allocated = false;
                for (String d : Dates.daysBetween(ws, Dates.addDays(ws, 6))) {
                    double factor = cal.capacityFactor(mid, d);
                    for (String e : scopeEpics) {
                        Double h = alloc.hoursOn(mid, e, d);
                        if (h != null) {
                            allocated = true;
                            avail += h * factor;
                        }
                    }
                }
                if (!allocated) {
                    rows.add(new WeekLoad(ws, Numbers.round(loads[w], 2), 0, null, "no_allocation"));
                    continue;
                }
                Integer pct = avail != 0 ? (int) Math.round(loads[w] / avail * 100) : null;
                rows.add(new WeekLoad(ws, Numbers.round(loads[w], 2), avail, pct, level(pct, capPct)));
            }
            out.put(mid, rows);
        }
        return out;
    }
}
