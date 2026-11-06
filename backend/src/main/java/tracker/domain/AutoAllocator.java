package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.Hours;
import tracker.domain.DetailScheduler.Placed;
import tracker.domain.DetailScheduler.Result;

/**
 * Auto allocation of a detail plan: who fills the empty BA / BAL / JM slots of the unpushed rows (docs/auto-allocation.md).
 * <ol>
 * <li>Greedy, row after row in the rows' order: the combination of candidates that ends the row soonest, then adds the fewest
 * hours over budget, then gives its people the smallest share of their phase budget, then the JM with the fewest reviews.
 * Each row is placed alone on the days the earlier rows left; the whole plan, members' days shared by the scheduler's rules,
 * is what the improvement scores.</li>
 * <li>Improvement on the whole plan's {@link Score}: first targeted moves (rows over budget or late, to people within budget),
 * then every move and swap, pass after pass until nothing gets better or {@link Options#maxTrials} plans were scored.</li>
 * </ol>
 * People already picked are never changed. Calendars (weekends, holidays by country, full and half-day leave), allocation
 * changes and work already in Jira all come through the scheduler. The other {@link Options} are the lab's
 * (AutoAllocationLab), kept to re-run the comparison.
 */
public final class AutoAllocator {
    private AutoAllocator() {
    }

    /** An unpushed row; a slot is null while nobody is picked. */
    /** @param plan the row's steps, with the plan's edits ({@link PlanRows#steps}) */
    public record Row(long id, String phase, @Nullable Integer steps, double effortH, @Nullable String baId, @Nullable String balId,
                      @Nullable String jmId, List<PlanRows.RowStep> plan) {
        /** A row without edits, by the default step weights. */
        public Row(long id, String phase, @Nullable Integer steps, double effortH, @Nullable String baId, @Nullable String balId,
                   @Nullable String jmId) {
            this(id, phase, steps, effortH, baId, balId, jmId, PlanRows.steps(id, steps, effortH, List.of(), StepWeights.defaults()));
        }
    }

    /** A phase of the saved master schedule. */
    public record Phase(String start, String end, List<String> team) {
    }

    /**
     * @param roles       member → role (members without one are never picked)
     * @param loads       member → day → hours already planned (open sub-tasks in Jira)
     * @param givenBefore "member|phase" → hours the member already has in the phase in Jira
     */
    public record Input(String epic, List<Row> rows, Map<String, Phase> phases, Map<String, Role> roles,
                        Map<String, Map<String, Double>> loads, Map<String, Double> givenBefore, Hours hours, WorkCalendar cal,
                        StepWeights weights, double jmHoursPerDay) {
    }

    public record Pick(long rowId, @Nullable String baId, @Nullable String balId, @Nullable String jmId) {
        @Nullable String get(Slot s) {
            return s == Slot.BA ? baId : s == Slot.BAL ? balId : jmId;
        }

        Pick with(Slot s, @Nullable String id) {
            return new Pick(rowId, s == Slot.BA ? id : baId, s == Slot.BAL ? id : balId, s == Slot.JM ? id : jmId);
        }
    }

    enum Slot {
        BA, BAL, JM
    }

    /**
     * How good a whole plan is; lower is better, field by field.
     *
     * @param unfilled    slots a row needs that nobody fills
     * @param overBudgetH hours members get in a phase above what they give it between its Start and End (blocks the push)
     * @param daysLate    Σ calendar days each phase's planned end is after its End in the master schedule
     * @param phaseDays   Σ calendar days from each phase's Start to its planned end
     * @param spreadH     Σ per phase and role (and over the JMs) of the most minus the fewest hours a candidate gets
     */
    public record Score(int unfilled, double overBudgetH, int daysLate, int phaseDays, double spreadH) implements Comparable<Score> {
        private static final Comparator<Score> ORDER = Comparator.comparingInt(Score::unfilled).thenComparingDouble(Score::overBudgetH)
                .thenComparingInt(Score::daysLate).thenComparingInt(Score::phaseDays).thenComparingDouble(Score::spreadH);

        @Override
        public int compareTo(Score o) {
            return ORDER.compare(this, o);
        }
    }

    /** What the greedy pass compares first: hours over budget, the row's end, or its people's share of their budget. */
    public enum Key {
        BUDGET_END, END_BUDGET, SHARE_END
    }

    /**
     * @param key        the greedy pass's order of criteria
     * @param improve    run the improvement pass after the greedy one
     * @param swaps      the improvement pass also swaps the people of two rows of a phase
     * @param moves      which moves the improvement tries (see {@link Moves})
     * @param maxPasses  improvement passes at most (each pass tries every move once)
     * @param maxTrials  plans scored at most by the improvement (0: no limit); keeps a large anken's answer quick
     * @param kicks      after the improvement settles: times a few slots are changed at random and the plan improved again,
     *                   the better plan kept (iterated local search; the random draws are seeded, so the answer is stable)
     * @param multiStart start from the greedy plan of every {@link Key} and keep the best
     */
    public record Options(Key key, boolean improve, boolean swaps, Moves moves, int maxPasses, int maxTrials, int kicks,
                          boolean multiStart) {
        public static final Options DEFAULT = new Options(Key.END_BUDGET, true, true, Moves.TARGETED_THEN_ALL, 20, 4000, 0, false);
        public static final Options GREEDY = new Options(Key.END_BUDGET, false, false, Moves.ALL, 0, 0, 0, false);

        public Options(Key key, boolean improve, boolean swaps, Moves moves, int maxPasses) {
            this(key, improve, swaps, moves, maxPasses, 0, 0, false);
        }
    }

    /**
     * ALL: every slot to every other candidate (and every swap). TARGETED: only rows whose person is over budget in the phase
     * or that end after the phase's End, and only to candidates within their budget. TARGETED_THEN_ALL: targeted until it
     * settles, then all, within the same trials.
     */
    public enum Moves {
        ALL, TARGETED, TARGETED_THEN_ALL
    }

    /** The plans the improvement may still score. */
    private static final class Trials {
        private int left;

        Trials(int max) {
            left = max <= 0 ? Integer.MAX_VALUE : max;
        }

        boolean take() {
            return left-- > 0;
        }
    }

    private static final double EPS = 1e-9;

    public static List<Pick> allocate(Input in) {
        return allocate(in, Options.DEFAULT);
    }

    public static List<Pick> allocate(Input in, Options o) {
        Trials trials = new Trials(o.maxTrials());
        Budgets budgets = new Budgets(in);
        List<Pick> best = null;
        Score bestScore = null;
        for (Key k : o.multiStart() ? List.of(Key.values()) : List.of(o.key())) {
            List<Pick> picks = greedy(in, k);
            if (o.improve()) {
                if (o.moves() == Moves.TARGETED_THEN_ALL) {
                    picks = improve(in, picks, o, true, trials, budgets);
                }
                picks = improve(in, picks, o, o.moves() == Moves.TARGETED, trials, budgets);
                java.util.Random rnd = new java.util.Random(in.rows().size() * 31L + k.ordinal());
                Score sc = score(in, picks, budgets);
                for (int kick = 0; kick < o.kicks(); kick++) {
                    List<Pick> next = improve(in, kick(in, picks, rnd), o, o.moves() == Moves.TARGETED, trials, budgets);
                    Score ns = score(in, next, budgets);
                    if (ns.compareTo(sc) < 0) {
                        picks = next;
                        sc = ns;
                    }
                }
            }
            Score sc = score(in, picks, budgets);
            if (bestScore == null || sc.compareTo(bestScore) < 0) {
                best = picks;
                bestScore = sc;
            }
        }
        return Objects.requireNonNull(best);
    }

    /** A tenth of the filled slots (at least two) given another candidate at random. */
    private static List<Pick> kick(Input in, List<Pick> picks, java.util.Random rnd) {
        List<int[]> slots = new ArrayList<>();
        for (int i = 0; i < in.rows().size(); i++) {
            for (Slot s : open(in, in.rows().get(i))) {
                if (candidates(in, in.rows().get(i), s).size() > 1) {
                    slots.add(new int[]{i, s.ordinal()});
                }
            }
        }
        List<Pick> out = new ArrayList<>(picks);
        for (int n = Math.max(2, slots.size() / 10); n > 0 && !slots.isEmpty(); n--) {
            int[] at = slots.get(rnd.nextInt(slots.size()));
            Slot s = Slot.values()[at[1]];
            List<String> c = candidates(in, in.rows().get(at[0]), s);
            out.set(at[0], out.get(at[0]).with(s, c.get(rnd.nextInt(c.size()))));
        }
        return out;
    }

    /** Memoises {@code hours}: the scheduler asks for the same member and day many times. */
    public static Hours cached(Hours hours) {
        Map<String, Map<String, Double>> memo = new HashMap<>();
        return (m, d) -> memo.computeIfAbsent(m, k -> new HashMap<>()).computeIfAbsent(d, k -> hours.on(m, d));
    }

    // --- candidates -------------------------------------------------------------------------------------------------------

    private static boolean needs(Input in, Row r, Slot s) {
        return PlanRows.needs(r.plan(), s == Slot.BA ? Role.BA : s == Slot.BAL ? Role.BAL : Role.JM, in.weights());
    }

    private static @Nullable String picked(Row r, Slot s) {
        String id = s == Slot.BA ? r.baId() : s == Slot.BAL ? r.balId() : r.jmId();
        return Text.hasText(id) ? id : null;
    }

    /** The slots of a row the allocation fills: needed and empty, in a phase of the master schedule. */
    static List<Slot> open(Input in, Row r) {
        if (!in.phases().containsKey(r.phase())) {
            return List.of();
        }
        List<Slot> out = new ArrayList<>();
        for (Slot s : Slot.values()) {
            if (needs(in, r, s) && picked(r, s) == null) {
                out.add(s);
            }
        }
        return out;
    }

    /** BA (and the one member of a row without steps): the phase team's BAs; BAL: its BALs; JM: its JMs. */
    static List<String> candidates(Input in, Row r, Slot s) {
        Role role = s == Slot.BA ? Role.BA : s == Slot.BAL ? Role.BAL : Role.JM;
        return in.phases().get(r.phase()).team().stream().filter(id -> in.roles().get(id) == role).sorted().toList();
    }

    // --- chains and the whole-plan score ----------------------------------------------------------------------------------

    /** The row's steps with the people of {@code p} ({@link PlanRows#chain}). Null when nothing is left. */
    static @Nullable Chain chain(Input in, Row r, Pick p) {
        Phase ph = in.phases().get(r.phase());
        return ph == null ? null : PlanRows.chain(r.phase(), r.plan(), p.baId(), p.balId(), p.jmId(), in.weights(), ph.start());
    }

    static Pick initial(Row r) {
        return new Pick(r.id(), picked(r, Slot.BA), picked(r, Slot.BAL), picked(r, Slot.JM));
    }

    /** The members' budgets per phase, computed once. */
    private static final class Budgets {
        private final Input in;
        private final Map<String, Map<String, Double>> cache = new HashMap<>();

        Budgets(Input in) {
            this.in = in;
        }

        double of(String member, String phase) {
            return cache.computeIfAbsent(member, k -> new HashMap<>()).computeIfAbsent(phase, k -> {
                Phase p = in.phases().get(phase);
                double sum = 0;
                for (String d : Dates.daysBetween(p.start(), p.end())) {
                    sum += in.hours().on(member, d);
                }
                return sum;
            });
        }
    }

    public static Score score(Input in, List<Pick> picks) {
        return score(in, picks, new Budgets(in));
    }

    private static Score score(Input in, List<Pick> picks, Budgets budgets) {
        List<Chain> chains = new ArrayList<>();
        int unfilled = 0;
        for (int i = 0; i < in.rows().size(); i++) {
            Row r = in.rows().get(i);
            Pick p = picks.get(i);
            for (Slot s : Slot.values()) {
                if (needs(in, r, s) && p.get(s) == null) {
                    unfilled++;
                }
            }
            Chain c = chain(in, r, p);
            if (c != null) {
                chains.add(c);
            }
        }
        try {
            return measure(in, unfilled, DetailScheduler.schedule(in.epic(), chains, in.loads(), in.hours(), in.cal(), in.jmHoursPerDay(),
                    in.weights().daySplit())
                    .placed(), budgets);
        } catch (RuleError e) {
            return WORST.apply(unfilled);
        }
    }

    private static final java.util.function.IntFunction<Score> WORST = u -> new Score(u, Double.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
            Double.MAX_VALUE);

    private static int unfilled(Input in, List<Pick> picks) {
        int n = 0;
        for (int i = 0; i < in.rows().size(); i++) {
            for (Slot s : Slot.values()) {
                if (needs(in, in.rows().get(i), s) && picks.get(i).get(s) == null) {
                    n++;
                }
            }
        }
        return n;
    }

    /** The given hours per "member|phase" (JM Review aside), with what members had in Jira. */
    private static Map<String, Double> given(Input in, Iterable<Placed> placed) {
        Map<String, Double> given = new HashMap<>(in.givenBefore());
        for (Placed pl : placed) {
            if (pl.counted()) {
                given.merge(pl.memberId() + '|' + pl.phase(), pl.hours(), Double::sum);
            }
        }
        return given;
    }

    private static Score measure(Input in, int unfilled, Iterable<Placed> placed, Budgets budgets) {
        Map<String, Double> given = given(in, placed);
        Map<String, Double> jmHours = new HashMap<>();
        Map<String, String> ends = new HashMap<>();
        for (Placed pl : placed) {
            if (pl.kind() == StepKind.JM_REVIEW) {
                jmHours.merge(pl.memberId(), pl.hours(), Double::sum);
            }
            ends.merge(pl.phase(), pl.end(), Dates::max);
        }
        double over = 0;
        for (Map.Entry<String, Double> e : given.entrySet()) {
            String member = e.getKey().substring(0, e.getKey().indexOf('|'));
            String phase = e.getKey().substring(e.getKey().indexOf('|') + 1);
            if (in.phases().containsKey(phase)) {
                over += Math.max(0, e.getValue() - budgets.of(member, phase));
            }
        }
        int late = 0;
        int days = 0;
        double spread = 0;
        for (Map.Entry<String, Phase> e : in.phases().entrySet()) {
            String end = ends.get(e.getKey());
            if (end != null) {
                late += Math.max(0, Dates.dayDiff(e.getValue().end(), end));
                days += Dates.dayDiff(e.getValue().start(), end) + 1;
            }
            for (Role role : List.of(Role.BA, Role.BAL)) {
                spread += spread(e.getValue().team().stream().filter(id -> in.roles().get(id) == role)
                        .map(id -> given.getOrDefault(id + '|' + e.getKey(), 0.0)).toList());
            }
        }
        spread += spread(jms(in).stream().map(id -> jmHours.getOrDefault(id, 0.0)).toList());
        return new Score(unfilled, Numbers.round(over, 2), late, days, Numbers.round(spread, 2));
    }

    /** The JMs of every phase team: JM Review takes no allocation, so its hours are shared across the phases. */
    private static List<String> jms(Input in) {
        return in.phases().values().stream().flatMap(p -> p.team().stream()).filter(id -> in.roles().get(id) == Role.JM).distinct().sorted()
                .toList();
    }

    private static double spread(List<Double> hours) {
        return hours.size() < 2 ? 0 : hours.stream().mapToDouble(Double::doubleValue).max().orElse(0)
                - hours.stream().mapToDouble(Double::doubleValue).min().orElse(0);
    }

    // --- greedy ---------------------------------------------------------------------------------------------------------

    /**
     * Row after row: every combination of candidates for the row's empty slots is scheduled on what the rows before it took;
     * the one kept is the best by {@code order} (END_BUDGET: ends the row soonest, then adds the fewest hours over budget),
     * then gives its people the smallest share of their phase budget, then the fewest JM Review hours so far, then comes first
     * by member id.
     */
    static List<Pick> greedy(Input in, Key order) {
        Budgets budgets = new Budgets(in);
        Map<String, Map<String, Double>> ledger = in.loads();
        Map<String, Double> given = new HashMap<>(in.givenBefore());
        Map<String, Double> jmHours = new HashMap<>();
        List<Pick> out = new ArrayList<>();
        for (Row r : in.rows()) {
            List<Slot> open = open(in, r);
            List<List<String>> choices = new ArrayList<>();
            for (Slot s : open) {
                List<String> c = candidates(in, r, s);
                choices.add(c.isEmpty() ? java.util.Collections.singletonList(null) : c);
            }
            Pick best = null;
            Result bestRes = null;
            double[] bestKey = null;
            for (Pick p : combos(initial(r), open, choices)) {
                Chain c = chain(in, r, p);
                Result res;
                try {
                    res = c == null ? null : DetailScheduler.schedule(in.epic(), List.of(c), ledger, in.hours(), in.cal(), in.jmHoursPerDay(),
                            in.weights().daySplit());
                } catch (RuleError e) {
                    continue;
                }
                double[] key = key(in, r, p, res, given, jmHours, budgets, order);
                if (bestKey == null || compare(key, bestKey) < 0) {
                    best = p;
                    bestRes = res;
                    bestKey = key;
                }
            }
            if (best == null) {
                best = initial(r);
            } else if (bestRes != null) {
                ledger = bestRes.ledger();
                for (Placed pl : bestRes.placed()) {
                    if (pl.kind() == StepKind.JM_REVIEW) {
                        jmHours.merge(pl.memberId(), pl.hours(), Double::sum);
                    } else {
                        given.merge(pl.memberId() + '|' + pl.phase(), pl.hours(), Double::sum);
                    }
                }
            }
            out.add(best);
        }
        return out;
    }

    private static List<Pick> combos(Pick base, List<Slot> open, List<List<String>> choices) {
        List<Pick> out = new ArrayList<>(List.of(base));
        for (int i = 0; i < open.size(); i++) {
            List<Pick> next = new ArrayList<>();
            for (Pick p : out) {
                for (String id : choices.get(i)) {
                    next.add(p.with(open.get(i), id));
                }
            }
            out = next;
        }
        return out;
    }

    private static double[] key(Input in, Row r, Pick p, @Nullable Result res, Map<String, Double> given, Map<String, Double> jmHours,
                                Budgets budgets, Key order) {
        if (res == null) {
            return new double[]{Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE, Double.MAX_VALUE};
        }
        Map<String, Double> add = new HashMap<>();
        String end = null;
        for (Placed pl : res.placed()) {
            if (pl.counted()) {
                add.merge(pl.memberId(), pl.hours(), Double::sum);
            }
            end = end == null ? pl.end() : Dates.max(end, pl.end());
        }
        double overInc = 0;
        double share = 0;
        for (Map.Entry<String, Double> e : add.entrySet()) {
            double before = given.getOrDefault(e.getKey() + '|' + r.phase(), 0.0);
            double budget = budgets.of(e.getKey(), r.phase());
            overInc += Math.max(0, before + e.getValue() - budget) - Math.max(0, before - budget);
            share = Math.max(share, budget > EPS ? (before + e.getValue()) / budget : Double.MAX_VALUE);
        }
        double jm = p.jmId() == null ? 0 : jmHours.getOrDefault(p.jmId(), 0.0);
        double over = Numbers.round(overInc, 2);
        double days = Dates.dayDiff(in.phases().get(r.phase()).start(), Objects.requireNonNull(end));
        return switch (order) {
            case BUDGET_END -> new double[]{over, days, share, jm};
            case END_BUDGET -> new double[]{days, over, share, jm};
            case SHARE_END -> new double[]{share, days, jm};
        };
    }

    private static int compare(double[] a, double[] b) {
        for (int i = 0; i < a.length; i++) {
            int c = Double.compare(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return 0;
    }

    // --- improvement ----------------------------------------------------------------------------------------------------

    /**
     * The plan of the current picks and what each row placed. Rows share their members' days (a row's Fix comment takes a
     * third of the BA's day from a later row's Create), so a change to one row schedules the whole plan again.
     */
    private static final class Plan {
        final Input in;
        final List<Pick> picks;
        List<List<Placed>> placed = List.of();

        Plan(Input in, List<Pick> picks) {
            this.in = in;
            this.picks = new ArrayList<>(picks);
            rebuild();
        }

        void rebuild() {
            placed = byRow(in, picks);
        }

        /** The score with {@code trial} instead of the current picks. */
        Score trial(List<Pick> trial, Budgets budgets) {
            List<List<Placed>> rows;
            try {
                rows = byRow(in, trial);
            } catch (RuleError e) {
                return WORST.apply(unfilled(in, trial));
            }
            return measure(in, unfilled(in, trial), rows.stream().flatMap(List::stream).toList(), budgets);
        }

        List<Placed> all() {
            return placed.stream().flatMap(List::stream).toList();
        }
    }

    /** Schedules every row with these picks; what each row placed, in row order. */
    private static List<List<Placed>> byRow(Input in, List<Pick> picks) {
        List<Chain> chains = new ArrayList<>();
        Map<String, Integer> rowOf = new HashMap<>();
        List<List<Placed>> out = new ArrayList<>();
        for (int i = 0; i < in.rows().size(); i++) {
            out.add(new ArrayList<>());
            Chain c = chain(in, in.rows().get(i), picks.get(i));
            if (c != null) {
                chains.add(c);
                for (DetailScheduler.Step st : c.steps()) {
                    rowOf.put(st.ref(), i);
                }
            }
        }
        for (Placed pl : DetailScheduler.schedule(in.epic(), chains, in.loads(), in.hours(), in.cal(), in.jmHoursPerDay(),
                in.weights().daySplit()).placed()) {
            out.get(rowOf.get(pl.ref())).add(pl);
        }
        return out;
    }

    /**
     * Moves one filled slot to another candidate (and, with swaps, trades two rows' people in a phase) while the whole plan
     * scores better, pass after pass until a pass changes nothing. Targeted: only rows whose person is over budget in the phase
     * or that end after the phase's End are moved, and only to candidates still within their budget.
     */
    static List<Pick> improve(Input in, List<Pick> start, Options o, boolean targeted, Trials trials, Budgets budgets) {
        Plan plan = new Plan(in, start);
        Score best = measure(in, unfilled(in, plan.picks), plan.all(), budgets);
        for (int pass = 0; pass < o.maxPasses(); pass++) {
            boolean better = false;
            Map<String, Double> given = given(in, plan.all());
            Map<Integer, String> rowEnd = new HashMap<>();
            for (int i = 0; i < in.rows().size(); i++) {
                for (Placed pl : plan.placed.get(i)) {
                    rowEnd.merge(i, pl.end(), Dates::max);
                }
            }
            java.util.function.BiPredicate<String, String> over = (m, ph) -> m != null
                    && given.getOrDefault(m + '|' + ph, 0.0) > budgets.of(m, ph) + EPS;
            for (int i = 0; i < in.rows().size(); i++) {
                Row r = in.rows().get(i);
                boolean late = rowEnd.containsKey(i) && rowEnd.get(i).compareTo(in.phases().get(r.phase()).end()) > 0;
                for (Slot s : open(in, r)) {
                    String now = plan.picks.get(i).get(s);
                    if (targeted && s != Slot.JM && !late && !over.test(now, r.phase())) {
                        continue;
                    }
                    for (String id : candidates(in, r, s)) {
                        if (id.equals(now) || targeted && s != Slot.JM && over.test(id, r.phase())) {
                            continue;
                        }
                        if (!trials.take()) {
                            return plan.picks;
                        }
                        List<Pick> trial = new ArrayList<>(plan.picks);
                        trial.set(i, trial.get(i).with(s, id));
                        Score sc = plan.trial(trial, budgets);
                        if (sc.compareTo(best) < 0) {
                            best = sc;
                            better = true;
                            plan.picks.set(i, trial.get(i));
                            plan.rebuild();
                            now = id;
                        }
                    }
                }
            }
            if (o.swaps()) {
                for (int i = 0; i < in.rows().size(); i++) {
                    Row a = in.rows().get(i);
                    for (Slot s : open(in, a)) {
                        String ma = plan.picks.get(i).get(s);
                        if (targeted && !over.test(ma, a.phase())) {
                            continue;
                        }
                        for (int j = i + 1; j < in.rows().size(); j++) {
                            Row b = in.rows().get(j);
                            String mb = plan.picks.get(j).get(s);
                            if (!a.phase().equals(b.phase()) || !open(in, b).contains(s) || Objects.equals(ma, mb)
                                    || targeted && over.test(mb, b.phase())) {
                                continue;
                            }
                            if (!trials.take()) {
                                return plan.picks;
                            }
                            List<Pick> trial = new ArrayList<>(plan.picks);
                            trial.set(i, trial.get(i).with(s, mb));
                            trial.set(j, trial.get(j).with(s, ma));
                            Score sc = plan.trial(trial, budgets);
                            if (sc.compareTo(best) < 0) {
                                best = sc;
                                better = true;
                                plan.picks.set(i, trial.get(i));
                                plan.picks.set(j, trial.get(j));
                                plan.rebuild();
                                ma = mb;
                            }
                        }
                    }
                }
            }
            if (!better) {
                break;
            }
        }
        return plan.picks;
    }
}
