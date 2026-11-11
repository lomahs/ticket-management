package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import tracker.domain.ScheduleVersion.PhaseRow;

/**
 * Dates of a detail plan: day after day, each member's day is filled up to their allocation on the anken, and the steps of a
 * function follow each other (a step starts the day after the previous one ends). A step may be carried by two people (the
 * BA and the BAL of a Fix comment step): each share is placed from the same day and the step runs from the first to the
 * last of their days. A step that counts for nobody (JM Review) takes no allocation: it lasts its hours over the working
 * hours of a day. Steps whose dates were set by hand keep them and take their hours first; the other steps fill the hours
 * left around them.
 * <p>
 * How a member's hours of a day are shared:
 * <ul>
 * <li>Reviews (the BAL's shares, BAL Review included) take the member's free hours first, in row order.</li>
 * <li>The BA's hours left go to Create (and tickets), in row order, and to the BA's part of the Fix comment steps that can
 * start, the function whose Create ended first first, by Settings › Step weights' day split (2/3 and 1/3 by default). Hours one side does not use go to the other: with no Fix comment
 * that can start, Create takes the whole day; with only Fix comments left, they do.</li>
 * </ul>
 */
public final class DetailScheduler {
    private DetailScheduler() {
    }

    /**
     * A person's part of a step, in hours.
     *
     * @param review the BAL's part (a review, or the BAL's share of a Fix comment): it takes the BAL's whole day, not the BA's
     *               split between Create and Fix comment
     */
    public record Share(String memberId, double hours, boolean review) {
        public Share(String memberId, double hours) {
            this(memberId, hours, false);
        }
    }

    /**
     * @param kind       the step of a function; null for a ticket without steps
     * @param shares     who carries the step's hours; none: it counts for nobody and lasts {@code hours} over the working day
     * @param who        the person a step that counts for nobody follows the working days of (the JM)
     * @param hours      the step's hours
     * @param fixedStart a Planned Start to keep (re-plan of a started sub-task); the hours are still placed from the chain's day on
     * @param fixedEnd   with {@code fixedStart}: the dates were set by hand; each share is spread evenly over its working days
     *                   between them, even above the allocation
     */
    public record Step(String ref, String phase, @Nullable StepKind kind, List<Share> shares, String who, double hours,
                       @Nullable String fixedStart, @Nullable String fixedEnd) {
        public Step {
            shares = List.copyOf(shares);
        }

        /** One person carries the whole step (JM Review: counts for nobody). */
        public Step(String ref, String phase, @Nullable StepKind kind, String memberId, double hours, @Nullable String fixedStart) {
            this(ref, phase, kind, kind == StepKind.JM_REVIEW ? List.of() : List.of(new Share(memberId, hours)), memberId, hours, fixedStart, null);
        }

        boolean pinned() {
            return fixedStart != null && fixedEnd != null;
        }
    }

    /** One function (its steps in order) or one ticket; nothing of it is placed before {@code earliest}. */
    public record Chain(String earliest, List<Step> steps) {
    }

    /**
     * One person's part of a placed step; a step carried by two people is placed twice with the same ref.
     *
     * @param counted false for a step that counts for nobody (JM Review): {@code hours} are the step's, used for its dates only
     */
    public record Placed(String ref, String phase, @Nullable StepKind kind, String memberId, double hours, String start, String end,
                         boolean counted) {
        public Placed(String ref, String phase, @Nullable StepKind kind, String memberId, double hours, String start, String end) {
            this(ref, phase, kind, memberId, hours, start, end, kind != StepKind.JM_REVIEW);
        }
    }

    /** The hours given to a member in a phase (JM Review aside) above the member's budget there. */
    public record Overrun(String memberId, String phase, double plannedH, double budgetH) {
    }

    /** A day on which the member has more hours planned than the allocation gives. */
    public record DayOverrun(String memberId, String date, double plannedH, double allocationH) {
    }

    /** Hours of a step placed on one day of one member. */
    public record Part(String ref, String memberId, String date, double hours) {
    }

    /**
     * @param ledger  member → day → hours planned, the loads included
     * @param overDay the days this run placed hours on that end up above the allocation (loads alone are not the run's)
     * @param parts   where this run placed each step's hours, day by day
     */
    public record Result(List<Placed> placed, Map<String, Map<String, Double>> ledger, List<DayOverrun> overDay, List<Part> parts) {
    }

    private static final double EPS = 1e-9;

    /** Hours a member gives the anken on a day: the allocation × the share of the day they work. */
    @FunctionalInterface
    public interface Hours {
        double on(String memberId, String day);
    }

    /** {@link MasterSchedule#hoursOn}, as {@link Hours}. */
    public static Hours hours(String epic, Allocations a, WorkCalendar cal) {
        return (m, d) -> MasterSchedule.hoursOn(m, epic, d, a, cal);
    }

    /**
     * @param loads hours already planned per member and day (work outside this run), which this run does not use again
     * @throws RuleError when a member gets no hours on the anken for {@value MasterSchedule#GIVE_UP_DAYS} days in a row
     */
    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Allocations a, WorkCalendar cal,
                                  double jmHoursPerDay) {
        return schedule(epic, chains, loads, hours(epic, a, cal), cal, jmHoursPerDay, StepWeights.DaySplit.DEFAULT);
    }

    /** @param split how a BA's day is shared between Create and Fix comment (Settings › Step weights) */
    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Allocations a, WorkCalendar cal,
                                  double jmHoursPerDay, StepWeights.DaySplit split) {
        return schedule(epic, chains, loads, hours(epic, a, cal), cal, jmHoursPerDay, split);
    }

    /** As above, with the hours of each member and day from {@code hours} (a cache, in the auto allocation). */
    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Hours hours, WorkCalendar cal,
                                  double jmHoursPerDay) {
        return schedule(epic, chains, loads, hours, cal, jmHoursPerDay, StepWeights.DaySplit.DEFAULT);
    }

    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Hours hours, WorkCalendar cal,
                                  double jmHoursPerDay, StepWeights.DaySplit split) {
        // a member's days are copied the first time this run plans hours for them: the loads are never changed
        Map<String, Map<String, Double>> ledger = new HashMap<>(loads);
        Set<String> own = new HashSet<>();
        Map<String, Set<String>> touched = new HashMap<>();
        List<Placed> placed = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        // steps set by hand take their days first, whatever their row: the others are placed in the hours left around them
        for (Chain chain : chains) {
            for (Step st : chain.steps()) {
                if (!st.pinned()) {
                    continue;
                }
                String s = st.fixedStart();
                String last = Dates.max(st.fixedEnd(), s);
                if (st.shares().isEmpty()) {
                    placed.add(new Placed(st.ref(), st.phase(), st.kind(), st.who(), st.hours(), s, last, false));
                }
                for (Share sh : st.shares()) {
                    spread(st.ref(), sh, s, last, ledger, own, touched, parts, cal, hours);
                    placed.add(new Placed(st.ref(), st.phase(), st.kind(), sh.memberId(), sh.hours(), s, last, true));
                }
            }
        }
        new Sim(epic, ledger, own, touched, placed, parts, hours, cal, jmHoursPerDay, split.fixShare()).run(chains);
        List<DayOverrun> over = new ArrayList<>();
        touched.forEach((m, days) -> days.forEach(d -> {
            double h = ledger.get(m).get(d);
            double cap = hours.on(m, d);
            if (h > cap + EPS) {
                over.add(new DayOverrun(m, d, Numbers.round(h, 2), cap));
            }
        }));
        over.sort(Comparator.comparing(DayOverrun::memberId).thenComparing(DayOverrun::date));
        return new Result(placed, ledger, over, parts);
    }

    /** Which part of a member's day a share takes. */
    private enum Pool {
        CREATE, FIX, REVIEW
    }

    private static Pool pool(Step st, Share sh) {
        if (sh.review() || st.kind() == StepKind.BAL_REVIEW || st.kind() == StepKind.JM_REVIEW) {
            return Pool.REVIEW;
        }
        return st.kind() == StepKind.FIX_BAL || st.kind() == StepKind.FIX_JM ? Pool.FIX : Pool.CREATE;
    }

    /** A share of the step a chain is at: the hours it still needs, and the days it got. */
    private static final class Open {
        final Run run;
        final Share share;
        final Pool pool;
        double left;
        @Nullable String first;
        @Nullable String last;

        Open(Run run, Share share, Pool pool, double left) {
            this.run = run;
            this.share = share;
            this.pool = pool;
            this.left = left;
        }
    }

    /** A chain being placed: the step it is at, the day that step may start, and the day its Create ended. */
    private static final class Run {
        final Chain chain;
        final int index;
        int step = -1;
        String cursor;
        @Nullable String createEnd;
        final List<Open> open = new ArrayList<>();

        Run(Chain chain, int index) {
            this.chain = chain;
            this.index = index;
            this.cursor = chain.earliest();
        }

        Step current() {
            return chain.steps().get(step);
        }
    }

    /** The day-by-day placing of the steps not set by hand, every chain at once. */
    private static final class Sim {
        final String epic;
        final Map<String, Map<String, Double>> ledger;
        final Set<String> own;
        final Map<String, Set<String>> touched;
        final List<Placed> placed;
        final List<Part> parts;
        final Hours hours;
        final WorkCalendar cal;
        final double jmHoursPerDay;
        /** the Fix comments' share of a BA's day */
        final double fixShare;
        /** the chains with a step waiting for hours, in row order */
        final List<Run> runs = new ArrayList<>();
        /** member → the first of the days in a row they had work ready but no free hours */
        final Map<String, String> idleFrom = new HashMap<>();
        final Map<String, Integer> idle = new HashMap<>();

        Sim(String epic, Map<String, Map<String, Double>> ledger, Set<String> own, Map<String, Set<String>> touched, List<Placed> placed,
            List<Part> parts, Hours hours, WorkCalendar cal, double jmHoursPerDay, double fixShare) {
            this.epic = epic;
            this.ledger = ledger;
            this.own = own;
            this.touched = touched;
            this.placed = placed;
            this.parts = parts;
            this.hours = hours;
            this.cal = cal;
            this.jmHoursPerDay = jmHoursPerDay;
            this.fixShare = fixShare;
        }

        void run(List<Chain> chains) {
            for (int i = 0; i < chains.size(); i++) {
                Run r = new Run(chains.get(i), i);
                advance(r);
                if (!r.open.isEmpty()) {
                    runs.add(r);
                }
            }
            for (String day = next(null); day != null; day = next(day)) {
                place(day);
            }
        }

        /** The day after {@code day} (or the first day) on which a share may take hours; null when every chain is placed. */
        private @Nullable String next(@Nullable String day) {
            String min = null;
            for (Run r : runs) {
                if (min == null || r.cursor.compareTo(min) < 0) {
                    min = r.cursor;
                }
            }
            if (min == null || day == null) {
                return min;
            }
            return Dates.max(min, Dates.addDays(day, 1));
        }

        /** Moves the chain to its next step that needs hours, placing on the way the steps that need none. */
        private void advance(Run r) {
            while (++r.step < r.chain.steps().size()) {
                Step st = r.current();
                if (st.pinned()) {
                    close(r, Dates.max(st.fixedEnd(), st.fixedStart()));
                    continue;
                }
                if (st.shares().isEmpty()) {
                    int days = Math.max(1, (int) Math.ceil(st.hours() / jmHoursPerDay - EPS));
                    String[] span = cal.span(r.cursor, days, st.who());
                    String start = st.fixedStart() != null ? st.fixedStart() : span[0];
                    String last = Dates.max(span[1], start);
                    placed.add(new Placed(st.ref(), st.phase(), st.kind(), st.who(), st.hours(), start, last, false));
                    close(r, last);
                    continue;
                }
                for (Share sh : st.shares()) {
                    Open o = new Open(r, sh, pool(st, sh), sh.hours() <= EPS ? 0 : Numbers.hours(sh.hours()));
                    if (o.left == 0) { // no hours: its first working day
                        o.first = cal.nextWork(r.cursor, sh.memberId());
                        o.last = o.first;
                    }
                    r.open.add(o);
                }
                if (r.open.stream().anyMatch(o -> o.left > EPS)) {
                    return;
                }
                finish(r);
            }
        }

        /** The step's shares all got their hours: it is placed, and the next step starts the day after its last day. */
        private void finish(Run r) {
            Step st = r.current();
            String last = r.cursor;
            for (Open o : r.open) {
                String first = Objects.requireNonNull(o.first);
                String start = st.fixedStart() != null ? st.fixedStart() : first;
                String end = Objects.requireNonNull(o.last);
                placed.add(new Placed(st.ref(), st.phase(), st.kind(), o.share.memberId(), o.share.hours(), start, Dates.max(end, start), true));
                last = Dates.max(last, end);
            }
            r.open.clear();
            close(r, last);
        }

        private void close(Run r, String last) {
            if (r.current().kind() == StepKind.CREATE) {
                r.createEnd = last;
            }
            r.cursor = Dates.addDays(last, 1);
        }

        /** Shares each member's free hours of the day among the shares that may take hours that day. */
        private void place(String day) {
            Map<String, List<Open>> ready = new LinkedHashMap<>();
            for (Run r : runs) {
                if (r.cursor.compareTo(day) <= 0) {
                    for (Open o : r.open) {
                        if (o.left > EPS) {
                            ready.computeIfAbsent(o.share.memberId(), k -> new ArrayList<>()).add(o);
                        }
                    }
                }
            }
            ready.forEach((m, list) -> {
                double free = hours.on(m, day) - ledger.getOrDefault(m, Map.of()).getOrDefault(day, 0.0);
                if (free <= EPS) {
                    idleFrom.putIfAbsent(m, day);
                    if (idle.merge(m, 1, Integer::sum) >= MasterSchedule.GIVE_UP_DAYS) {
                        throw new RuleError(m + " has no free hours on " + epic + " from " + idleFrom.get(m));
                    }
                    return;
                }
                idle.remove(m);
                idleFrom.remove(m);
                List<Open> review = new ArrayList<>();
                List<Open> create = new ArrayList<>();
                List<Open> fix = new ArrayList<>();
                for (Open o : list) {
                    (o.pool == Pool.REVIEW ? review : o.pool == Pool.CREATE ? create : fix).add(o);
                }
                free -= give(review, free, day);
                if (fix.size() > 1) {
                    fix.sort(Comparator.comparing((Open o) -> Text.orElse(o.run.createEnd, "")).thenComparingInt(o -> o.run.index));
                }
                if (fix.isEmpty()) {
                    give(create, free, day);
                } else if (create.isEmpty()) {
                    give(fix, free, day);
                } else {
                    double f = give(fix, free * fixShare, day);
                    double c = give(create, free - f, day);
                    give(fix, free - f - c, day);
                }
            });
            List<Run> still = new ArrayList<>(runs.size());
            for (Run r : runs) {
                if (r.cursor.compareTo(day) <= 0 && done(r)) {
                    finish(r);
                    advance(r);
                }
                if (!r.open.isEmpty()) {
                    still.add(r);
                }
            }
            runs.clear();
            runs.addAll(still);
        }

        private static boolean done(Run r) {
            for (Open o : r.open) {
                if (o.left > EPS) {
                    return false;
                }
            }
            return true;
        }

        /** Gives up to {@code cap} hours of the day to the shares, in order, in whole hundredths; returns the hours given. */
        private double give(List<Open> shares, double cap, String day) {
            double given = 0;
            for (Open o : shares) {
                double take = Math.min(o.left, Numbers.floorHours(cap - given));
                if (take <= EPS) {
                    continue;
                }
                String m = o.share.memberId();
                mine(m, ledger, own).merge(day, take, Numbers::sum);
                touched.computeIfAbsent(m, k -> new TreeSet<>()).add(day);
                parts.add(new Part(o.run.current().ref(), m, day, take));
                o.left = Numbers.hours(o.left - take);
                o.first = o.first == null ? day : o.first;
                o.last = day;
                given = Numbers.hours(given + take);
            }
            return given;
        }
    }

    /** The member's days, copied the first time this run plans hours for them: the loads are never changed. */
    private static Map<String, Double> mine(String memberId, Map<String, Map<String, Double>> ledger, Set<String> own) {
        if (own.add(memberId)) {
            ledger.put(memberId, new TreeMap<>(ledger.getOrDefault(memberId, Map.of())));
        }
        return ledger.get(memberId);
    }

    /**
     * Spreads a share evenly over the days from {@code start} to {@code end} the member gives the anken hours, else over their
     * working days, else on {@code start}.
     */
    private static void spread(String ref, Share sh, String start, String end, Map<String, Map<String, Double>> ledger, Set<String> own,
                               Map<String, Set<String>> touched, List<Part> parts, WorkCalendar cal, Hours hours) {
        List<Part> spread = spread(ref, sh, start, end, cal, hours);
        if (spread.isEmpty()) {
            return;
        }
        Map<String, Double> m = mine(sh.memberId(), ledger, own);
        for (Part p : spread) {
            m.merge(p.date(), p.hours(), Numbers::sum);
            touched.computeIfAbsent(sh.memberId(), k -> new TreeSet<>()).add(p.date());
            parts.add(p);
        }
    }

    /** The parts of a share set by hand on {@code start}..{@code end}, spread as above; none for a share of 0 hours. */
    public static List<Part> spread(String ref, Share sh, String start, String end, WorkCalendar cal, Hours hours) {
        if (sh.hours() <= EPS) {
            return List.of();
        }
        List<String> all = Dates.daysBetween(start, end);
        List<String> days = all.stream().filter(d -> hours.on(sh.memberId(), d) > EPS).toList();
        if (days.isEmpty()) {
            days = all.stream().filter(d -> !Text.hasText(cal.off(d, sh.memberId()))).toList();
        }
        if (days.isEmpty()) {
            days = List.of(start);
        }
        double[] each = Numbers.split(sh.hours(), days.size());
        List<Part> out = new ArrayList<>();
        for (int k = 0; k < days.size(); k++) {
            out.add(new Part(ref, sh.memberId(), days.get(k), each[k]));
        }
        return out;
    }

    /**
     * Per member and phase: the hours of the placed steps (JM Review aside) plus {@code givenBefore} against the member's
     * budget between the phase's Start and End.
     *
     * @param givenBefore "member|phase" → hours the member already has in the phase (tickets in Jira)
     */
    public static List<Overrun> overBudget(List<Placed> placed, Map<String, Double> givenBefore, Map<String, PhaseRow> phases, String epic,
                                           Allocations a, WorkCalendar cal) {
        Map<String, Double> given = new LinkedHashMap<>(givenBefore);
        for (Placed p : placed) {
            if (p.counted()) {
                given.merge(p.memberId() + "|" + p.phase(), p.hours(), Numbers::sum);
            }
        }
        List<Overrun> out = new ArrayList<>();
        given.forEach((k, h) -> {
            String member = k.substring(0, k.indexOf('|'));
            String phase = k.substring(k.indexOf('|') + 1);
            PhaseRow row = phases.get(phase);
            if (row == null) {
                return;
            }
            double budget = MasterSchedule.budget(member, epic, row.start(), row.end(), a, cal);
            if (h > budget + EPS) {
                out.add(new Overrun(member, phase, Numbers.round(h, 2), Numbers.round(budget, 2)));
            }
        });
        out.sort(Comparator.comparing(Overrun::phase).thenComparing(Overrun::memberId));
        return out;
    }
}
