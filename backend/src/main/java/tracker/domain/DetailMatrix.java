package tracker.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

import org.jspecify.annotations.Nullable;

/** Rules of the detail plan's function–step × day matrix: planned and not-yet hours, first and last day, step order. */
public final class DetailMatrix {
    private DetailMatrix() {
    }

    /**
     * @param chain the function it is a step of ("row:&lt;id&gt;", "fn:&lt;functionId&gt;"), or its own ref for a lone line
     * @param order its place in the chain (StepKind ordinal; 0 for a lone line)
     * @param jira  a sub-task in Jira: only the cells from today on are planned, the rest is spent
     * @param cells day → hours, days with hours only
     * @param loggedTodayH hours logged today (a Jira line): they are in {@code spentH} already, and Jira took them off Remaining
     */
    public record Line(String ref, String chain, int order, boolean jira, double totalH, double spentH, Map<String, Double> cells,
                       double loggedTodayH) {
        public Line(String ref, String chain, int order, boolean jira, double totalH, double spentH, Map<String, Double> cells) {
            this(ref, chain, order, jira, totalH, spentH, cells, 0);
        }
    }

    /**
     * Every cell of a new line; for a Jira line what it spent plus its cells from {@code today} on, today's net of the hours
     * logged today (never below 0).
     */
    public static double plannedH(Line l, String today) {
        double sum = 0;
        for (Map.Entry<String, Double> c : l.cells().entrySet()) {
            if (!l.jira()) {
                sum += c.getValue();
            } else if (c.getKey().equals(today)) {
                sum += Math.max(0, c.getValue() - l.loggedTodayH());
            } else if (c.getKey().compareTo(today) > 0) {
                sum += c.getValue();
            }
        }
        return Numbers.hours(l.jira() ? l.spentH() + sum : sum);
    }

    /** What the line's total has over what is planned, in hundredths (negative when planned beyond the total). */
    public static double notYetH(Line l, String today) {
        return Numbers.hours(l.totalH() - plannedH(l, today));
    }

    /** The first day with hours. */
    public static @Nullable String start(Line l) {
        String first = null;
        for (Map.Entry<String, Double> c : l.cells().entrySet()) {
            if (c.getValue() > 0 && (first == null || c.getKey().compareTo(first) < 0)) {
                first = c.getKey();
            }
        }
        return first;
    }

    /** The last day with hours. */
    public static @Nullable String end(Line l) {
        String last = null;
        for (Map.Entry<String, Double> c : l.cells().entrySet()) {
            if (c.getValue() > 0 && (last == null || c.getKey().compareTo(last) > 0)) {
                last = c.getKey();
            }
        }
        return last;
    }

    /** Refs whose first day is before the last day of the nearest earlier step of their chain that has hours. */
    public static Set<String> outOfOrder(List<Line> lines) {
        Map<String, List<Line>> chains = new LinkedHashMap<>();
        for (Line l : lines) {
            chains.computeIfAbsent(l.chain(), k -> new ArrayList<>()).add(l);
        }
        Set<String> out = new LinkedHashSet<>();
        for (List<Line> chain : chains.values()) {
            chain.sort(Comparator.comparingInt(Line::order));
            String prevEnd = null;
            for (Line l : chain) {
                String start = start(l);
                if (start == null) {
                    continue;
                }
                if (prevEnd != null && start.compareTo(prevEnd) < 0) {
                    out.add(l.ref());
                }
                prevEnd = end(l);
            }
        }
        return out;
    }

    /** Days from {@code from} to {@code to} (both inclusive) that {@code shown} keeps; empty when either is null. */
    public static List<String> window(@Nullable String from, @Nullable String to, Predicate<String> shown) {
        List<String> days = new ArrayList<>();
        if (from == null || to == null) {
            return days;
        }
        LocalDate end = LocalDate.parse(to);
        for (LocalDate d = LocalDate.parse(from); !d.isAfter(end); d = d.plusDays(1)) {
            if (shown.test(d.toString())) {
                days.add(d.toString());
            }
        }
        return days;
    }
}
