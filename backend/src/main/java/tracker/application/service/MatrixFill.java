package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import tracker.application.service.DetailPlanService.DayHours;
import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.domain.Allocations;
import tracker.domain.Dates;
import tracker.domain.DetailScheduler;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.Effort;
import tracker.domain.EstimationRow;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.RuleError;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Text;
import tracker.domain.WorkCalendar;

/**
 * What Auto allocate adds to the matrix: the Not yet of each line, placed by {@link DetailScheduler} step by step in the hours
 * its people have left around everything the matrix already holds and what was placed before it. Typed and filled cells
 * stay; only the remainder is placed, never before an earlier step of its function ends.
 */
final class MatrixFill {
    private MatrixFill() {
    }

    /** Below this a line counts as fully planned. */
    private static final double EPS = 0.01;

    /**
     * Ref → day → hours Auto allocate adds; nothing for a line with Not yet <= 0, a removed line or a Closed sub-task.
     * <ul>
     * <li>The unpushed rows first, in row order, then their steps in step order: each step not removed with Not yet is placed
     * on its own, for its Not yet, from the latest of its phase's master Start, {@code today} and the last day (its cells and
     * what was placed) of every earlier step of its row.</li>
     * <li>Then each sub-task in Jira, function by function in step order, for its Not yet by {@code jiraShares}: from the
     * latest of {@code today}, its last cell and the last day from today on (cells and what was placed) of every earlier
     * step of its function. A sub-task its people cannot take ({@link RuleError}) is left out and keeps its Not yet.</li>
     * </ul>
     *
     * @param jiraShares issue key → who carries a sub-task's hours from today on (a pending PIC included)
     * @param jiraChains issue key → the function it is a step of ("fn:&lt;id&gt;"), else its own key
     * @param parts      everything already in the matrix, typed or not, which the fill does not use again
     * @throws RuleError when the people of an unpushed row's step cannot take it, as {@link DetailScheduler#schedule}
     */
    static Map<String, Map<String, Double>> fill(String epic, List<MatrixLine> lines, List<EstimationRow> rows,
                                                 Map<Long, List<PlanRows.RowStep>> stepsOf, Map<String, PhaseRow> phases,
                                                 Map<String, List<Effort.PlanShare>> jiraShares, Map<String, String> jiraChains,
                                                 List<DetailScheduler.Part> parts, Allocations a, WorkCalendar cal, String today,
                                                 double jmHoursPerDay, StepWeights w) {
        Map<String, MatrixLine> lineOf = new HashMap<>();
        lines.forEach(l -> lineOf.put(l.ref(), l));
        Map<String, Map<String, Double>> ledger = new HashMap<>();
        parts.forEach(p -> ledger.computeIfAbsent(p.memberId(), k -> new TreeMap<>()).merge(p.date(), p.hours(), Numbers::sum));
        Run run = new Run(epic, ledger, a, cal, jmHoursPerDay, w.daySplit());

        for (EstimationRow r : rows) {
            PhaseRow phase = phases.get(r.phase());
            String before = null; // the last day of the row's earlier steps: their cells and what was placed
            for (PlanRows.RowStep st : stepsOf.getOrDefault(r.id(), List.of())) {
                MatrixLine l = lineOf.get(st.ref());
                if (st.removed() || l == null) {
                    continue;
                }
                String end = l.end();
                if (l.notYetH() > EPS) {
                    String earliest = from(today, phase == null ? null : phase.start(), before);
                    DetailScheduler.Chain c = PlanRows.chain(r.phase(), List.of(new PlanRows.RowStep(st.kind(), st.ref(), l.notYetH(), null)),
                            r.baId(), r.balId(), r.jmId(), w, earliest);
                    if (c != null) {
                        end = latest(end, run.place(c));
                    }
                }
                before = latest(before, end);
            }
        }

        // the sub-tasks in Jira, function by function, each function's steps in step order
        Map<String, List<MatrixLine>> chains = new LinkedHashMap<>();
        for (MatrixLine l : lines) {
            if (l.issueKey() != null && !l.closed() && !l.removed()) {
                chains.computeIfAbsent(jiraChains.getOrDefault(l.issueKey(), l.issueKey()), k -> new ArrayList<>()).add(l);
            }
        }
        for (List<MatrixLine> chain : chains.values()) {
            List<MatrixLine> ordered = new ArrayList<>(chain);
            ordered.sort(Comparator.comparingInt(MatrixFill::order));
            String before = null; // the last day from today on of the function's earlier steps
            for (MatrixLine l : ordered) {
                String end = null;
                for (DayHours d : l.cells()) {
                    if (d.hours() > 0 && d.day().compareTo(today) >= 0) {
                        end = latest(end, d.day());
                    }
                }
                // the hours logged on past days its plan left short go on those days first: work done is not planned again
                double left = l.planNotYetH();
                Map<String, Double> planned = new HashMap<>();
                l.cells().forEach(d -> planned.merge(d.day(), d.hours(), Numbers::sum));
                for (DayHours g : l.logged()) {
                    double extra = g.hours() - planned.getOrDefault(g.day(), 0.0);
                    if (left > EPS && extra > EPS && g.day().compareTo(today) < 0) {
                        double h = Numbers.hours(Math.min(extra, left));
                        run.out.computeIfAbsent(l.ref(), k -> new TreeMap<>()).merge(g.day(), h, Numbers::sum);
                        left = Numbers.hours(left - h);
                    }
                }
                DetailScheduler.Step step = left > EPS ? jiraStep(l, left, jiraShares) : null;
                if (step != null) {
                    String earliest = from(today, l.end(), before);
                    try {
                        end = latest(end, run.place(new DetailScheduler.Chain(earliest, List.of(step))));
                    } catch (RuleError e) { // its people have no hours for it: it keeps its Not yet
                    }
                }
                before = latest(before, end);
            }
        }
        return run.out;
    }

    /** {@code h} hours of a sub-task's Not yet plan as a step from today on; null when nobody can carry it. */
    private static DetailScheduler.@Nullable Step jiraStep(MatrixLine l, double h, Map<String, List<Effort.PlanShare>> jiraShares) {
        StepKind kind = l.step() == null ? null : StepKind.of(l.step());
        String pic = Text.hasText(l.picId()) ? l.picId() : null;
        List<DetailScheduler.Share> shares;
        if (kind == StepKind.JM_REVIEW) { // counts for nobody: over its PIC's working days
            shares = List.of();
        } else {
            shares = shares(h, completed(jiraShares.getOrDefault(l.issueKey(), List.of()), pic));
            if (shares.isEmpty()) {
                return null;
            }
        }
        String who = pic != null ? pic : shares.isEmpty() ? null : shares.getFirst().memberId();
        return who == null ? null : new DetailScheduler.Step(l.ref(), l.phase(), kind, shares, who, h, null, null);
    }

    /** The fractions, the part they leave short of 100 % (a missing BAL, or nobody at all) given to {@code pic}. */
    static List<Effort.PlanShare> completed(List<Effort.PlanShare> fractions, @Nullable String pic) {
        double missing = 1 - fractions.stream().mapToDouble(Effort.PlanShare::fraction).sum();
        if (pic == null || missing < 1e-9) {
            return fractions;
        }
        Map<String, Double> out = new LinkedHashMap<>();
        fractions.forEach(f -> out.merge(f.memberId(), f.fraction(), Double::sum));
        out.merge(pic, missing, Double::sum);
        return out.entrySet().stream().map(e -> new Effort.PlanShare(e.getKey(), e.getValue())).toList();
    }

    /**
     * Ref → day → the hours left on the days Auto allocate trims of a sub-task in Jira planned over its Original Estimate (Not yet
     * plan below 0): hours come off its latest days from {@code today} on until Not yet plan is 0, today's down to the hours logged
     * today at most. Only Jira's own plan (a sub-task without pending cells) or Auto allocate's cells are trimmed, never cells typed
     * by hand; a sub-task with no Original Estimate has nothing to trim against and keeps its plan.
     *
     * @param withCells the sub-tasks that have pending cells
     */
    static Map<String, Map<String, Double>> trims(List<MatrixLine> lines, Set<String> withCells, String today) {
        Map<String, Map<String, Double>> out = new TreeMap<>();
        for (MatrixLine l : lines) {
            if (l.issueKey() == null || l.closed() || l.removed() || l.planEstimateH() <= EPS || l.planNotYetH() >= -EPS) {
                continue;
            }
            boolean jirasOwn = !withCells.contains(l.issueKey());
            double loggedToday = l.logged().stream().filter(d -> d.day().equals(today)).mapToDouble(DayHours::hours).sum();
            double over = -l.planNotYetH();
            List<DayHours> latestFirst = new ArrayList<>(l.cells().stream().filter(d -> d.day().compareTo(today) >= 0 && (jirasOwn || d.auto())).toList());
            latestFirst.sort(Comparator.comparing(DayHours::day).reversed());
            for (DayHours d : latestFirst) {
                if (over < 0.005) {
                    break;
                }
                double counts = d.day().equals(today) ? Math.max(0, d.hours() - loggedToday) : d.hours();
                double take = Numbers.hours(Math.min(counts, over));
                if (take > 0) {
                    out.computeIfAbsent(l.ref(), k -> new TreeMap<>()).put(d.day(), Numbers.hours(d.hours() - take));
                    over = Numbers.hours(over - take);
                }
            }
        }
        return out;
    }

    private static int order(MatrixLine l) {
        return l.step() == null ? 0 : StepKind.of(l.step()).ordinal();
    }

    /** One step placed at a time, each in the hours the ones before it left. */
    private static final class Run {
        final String epic;
        Map<String, Map<String, Double>> ledger;
        final Allocations a;
        final WorkCalendar cal;
        final double jmHoursPerDay;
        final StepWeights.DaySplit split;
        final Map<String, Map<String, Double>> out = new TreeMap<>();

        Run(String epic, Map<String, Map<String, Double>> ledger, Allocations a, WorkCalendar cal, double jmHoursPerDay, StepWeights.DaySplit split) {
            this.epic = epic;
            this.ledger = ledger;
            this.a = a;
            this.cal = cal;
            this.jmHoursPerDay = jmHoursPerDay;
            this.split = split;
        }

        /**
         * Places a chain, keeps its hours by ref and day and takes them out of the people's free hours; returns its last day
         * (null when nothing was placed). On {@link RuleError} nothing changes.
         */
        @Nullable String place(DetailScheduler.Chain c) {
            DetailScheduler.Result res = DetailScheduler.schedule(epic, List.of(c), ledger, a, cal, jmHoursPerDay, split);
            ledger = res.ledger();
            res.parts().forEach(p -> out.computeIfAbsent(p.ref(), k -> new TreeMap<>()).merge(p.date(), p.hours(), Numbers::sum));
            String end = null;
            for (Placed p : res.placed()) {
                if (!p.counted()) { // JM Review: its hours evenly over its person's working days
                    spread(p, cal).forEach((d, hours) -> out.computeIfAbsent(p.ref(), k -> new TreeMap<>()).merge(d, hours, Numbers::sum));
                }
                end = latest(end, p.end());
            }
            return end;
        }
    }

    /** A step that counts for nobody: its hours over the working days of its person from its start to its end, the rest on the last. */
    private static Map<String, Double> spread(Placed p, WorkCalendar cal) {
        List<String> days = Dates.daysBetween(p.start(), p.end()).stream().filter(d -> !Text.hasText(cal.off(d, p.memberId()))).toList();
        if (days.isEmpty()) {
            days = List.of(p.start());
        }
        long cents = Math.round(Numbers.hours(p.hours()) * 100);
        long each = cents / days.size();
        Map<String, Double> out = new TreeMap<>();
        for (int k = 0; k < days.size(); k++) {
            long c = k == days.size() - 1 ? cents - each * (days.size() - 1) : each;
            if (c > 0) {
                out.put(days.get(k), c / 100.0);
            }
        }
        return out;
    }

    /** {@code hours} by the fractions, in hundredths; with fractions of 100 % in all, the last takes what the others leave. */
    private static List<DetailScheduler.Share> shares(double hours, List<Effort.PlanShare> fractions) {
        double all = fractions.stream().mapToDouble(Effort.PlanShare::fraction).sum();
        List<DetailScheduler.Share> out = new ArrayList<>();
        double given = 0;
        for (int k = 0; k < fractions.size(); k++) {
            Effort.PlanShare sh = fractions.get(k);
            boolean last = k == fractions.size() - 1 && Math.abs(all - 1) < 1e-9;
            double h = last ? Numbers.hours(hours - given) : Numbers.hours(hours * sh.fraction());
            given = Numbers.sum(given, h);
            if (h > 0) {
                out.add(new DetailScheduler.Share(sh.memberId(), h));
            }
        }
        return out;
    }

    /** The latest of {@code today} and the days given. */
    private static String from(String today, @Nullable String a, @Nullable String b) {
        return Dates.max(today, Dates.max(a == null ? today : a, b == null ? today : b));
    }

    private static @Nullable String latest(@Nullable String a, @Nullable String b) {
        return a == null ? b : b == null ? a : Dates.max(a, b);
    }
}
