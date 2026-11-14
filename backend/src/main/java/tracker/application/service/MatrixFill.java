package tracker.application.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.jspecify.annotations.Nullable;

import tracker.application.service.DetailPlanService.MatrixLine;
import tracker.domain.Allocations;
import tracker.domain.Dates;
import tracker.domain.DetailScheduler;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.Effort;
import tracker.domain.EstimationRow;
import tracker.domain.Numbers;
import tracker.domain.PlanRows;
import tracker.domain.ScheduleVersion.PhaseRow;
import tracker.domain.StepKind;
import tracker.domain.StepWeights;
import tracker.domain.Text;
import tracker.domain.WorkCalendar;

/**
 * What Auto allocate adds to the matrix: the Not yet of each line, placed by {@link DetailScheduler} in the hours its people
 * have left around everything the matrix already holds. Typed and filled cells stay; only the remainder is placed.
 */
final class MatrixFill {
    private MatrixFill() {
    }

    /** Below this a line counts as fully planned. */
    private static final double EPS = 0.01;

    /**
     * Ref → day → hours Auto allocate adds; nothing for a line with Not yet <= 0, a removed line or a Closed sub-task.
     * <ul>
     * <li>An unpushed row is one chain of its steps not removed with Not yet, each for its Not yet; nothing of it before the
     * latest of its phase's master Start, {@code today} and the last day of the nearest earlier step of the row with cells.</li>
     * <li>A sub-task in Jira is a chain of its own, for its Not yet by {@code jiraShares}; nothing of it before the latest of
     * {@code today} and its last cell.</li>
     * </ul>
     *
     * @param jiraShares issue key → who carries a sub-task's hours from today on (a pending PIC included)
     * @param parts      everything already in the matrix, typed or not, which the fill does not use again
     * @throws tracker.domain.RuleError as {@link DetailScheduler#schedule} does
     */
    static Map<String, Map<String, Double>> fill(String epic, List<MatrixLine> lines, List<EstimationRow> rows,
                                                 Map<Long, List<PlanRows.RowStep>> stepsOf, Map<String, PhaseRow> phases,
                                                 Map<String, List<Effort.PlanShare>> jiraShares, List<DetailScheduler.Part> parts, Allocations a,
                                                 WorkCalendar cal, String today, double jmHoursPerDay, StepWeights w) {
        Map<String, MatrixLine> lineOf = new HashMap<>();
        lines.forEach(l -> lineOf.put(l.ref(), l));
        Map<String, Map<String, Double>> loads = new HashMap<>();
        parts.forEach(p -> loads.computeIfAbsent(p.memberId(), k -> new TreeMap<>()).merge(p.date(), p.hours(), Numbers::sum));

        List<DetailScheduler.Chain> chains = new ArrayList<>();
        for (EstimationRow r : rows) {
            List<PlanRows.RowStep> steps = stepsOf.getOrDefault(r.id(), List.of());
            List<PlanRows.RowStep> open = new ArrayList<>();
            String before = null; // the last day of the nearest step with cells before the first one to fill
            for (PlanRows.RowStep st : steps) {
                MatrixLine l = lineOf.get(st.ref());
                if (st.removed() || l == null) {
                    continue;
                }
                if (l.notYetH() > EPS) {
                    open.add(new PlanRows.RowStep(st.kind(), st.ref(), l.notYetH(), null));
                } else if (open.isEmpty() && l.end() != null) {
                    before = l.end();
                }
            }
            if (open.isEmpty()) {
                continue;
            }
            PhaseRow phase = phases.get(r.phase());
            String earliest = latest(today, phase == null ? null : phase.start(), before);
            DetailScheduler.Chain c = PlanRows.chain(r.phase(), open, r.baId(), r.balId(), r.jmId(), w, earliest);
            if (c != null) {
                chains.add(c);
            }
        }
        for (MatrixLine l : lines) {
            if (l.issueKey() == null || l.closed() || l.removed() || l.notYetH() <= EPS) {
                continue;
            }
            StepKind kind = l.step() == null ? null : StepKind.of(l.step());
            double h = l.notYetH();
            List<DetailScheduler.Share> shares = kind == StepKind.JM_REVIEW ? List.of() : shares(h, jiraShares.getOrDefault(l.issueKey(), List.of()));
            String who = Text.hasText(l.picId()) ? l.picId() : shares.isEmpty() ? null : shares.getFirst().memberId();
            if (who == null) { // nobody to place it for
                continue;
            }
            chains.add(new DetailScheduler.Chain(latest(today, l.end(), null),
                    List.of(new DetailScheduler.Step(l.ref(), l.phase(), kind, shares, who, h, null, null))));
        }

        DetailScheduler.Result res = DetailScheduler.schedule(epic, chains, loads, a, cal, jmHoursPerDay, w.daySplit());
        Map<String, Map<String, Double>> out = new TreeMap<>();
        res.parts().forEach(p -> out.computeIfAbsent(p.ref(), k -> new TreeMap<>()).merge(p.date(), p.hours(), Numbers::sum));
        for (Placed p : res.placed()) {
            if (!p.counted()) { // JM Review: its Not yet evenly over the JM's working days
                spread(p, cal).forEach((d, hours) -> out.computeIfAbsent(p.ref(), k -> new TreeMap<>()).merge(d, hours, Numbers::sum));
            }
        }
        return out;
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

    private static String latest(String a, @Nullable String b, @Nullable String c) {
        String out = a;
        if (b != null) {
            out = Dates.max(out, b);
        }
        if (c != null) {
            out = Dates.max(out, c);
        }
        return out;
    }
}
