package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.jspecify.annotations.Nullable;

import tracker.domain.ScheduleVersion.PhaseRow;

/**
 * Dates of a detail plan: each member's day is filled up to their allocation on the anken, task after task, and the steps
 * of a function follow each other. A step may be carried by two people (the BA and the BAL of a Fix comment step): each
 * share is placed from the same day and the step runs from the first to the last of their days. A step that counts for
 * nobody (JM Review) takes no allocation: it lasts its hours over the working hours of a day.
 */
public final class DetailScheduler {
    private DetailScheduler() {
    }

    /** A person's part of a step, in hours. */
    public record Share(String memberId, double hours) {
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
        return schedule(epic, chains, loads, hours(epic, a, cal), cal, jmHoursPerDay);
    }

    /** As above, with the hours of each member and day from {@code hours} (a cache, in the auto allocation). */
    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Hours hours, WorkCalendar cal,
                                  double jmHoursPerDay) {
        // a member's days are copied the first time this run plans hours for them: the loads are never changed
        Map<String, Map<String, Double>> ledger = new HashMap<>(loads);
        Set<String> own = new HashSet<>();
        Map<String, Set<String>> touched = new HashMap<>();
        List<Placed> placed = new ArrayList<>();
        List<Part> parts = new ArrayList<>();
        for (Chain chain : chains) {
            String cursor = chain.earliest();
            for (Step st : chain.steps()) {
                String last;
                if (st.pinned()) {
                    String s = st.fixedStart();
                    last = Dates.max(st.fixedEnd(), s);
                    if (st.shares().isEmpty()) {
                        placed.add(new Placed(st.ref(), st.phase(), st.kind(), st.who(), st.hours(), s, last, false));
                    }
                    for (Share sh : st.shares()) {
                        spread(st.ref(), sh, s, last, ledger, own, touched, parts, cal, hours);
                        placed.add(new Placed(st.ref(), st.phase(), st.kind(), sh.memberId(), sh.hours(), s, last, true));
                    }
                } else if (st.shares().isEmpty()) {
                    int days = Math.max(1, (int) Math.ceil(st.hours() / jmHoursPerDay - EPS));
                    String[] span = cal.span(cursor, days, st.who());
                    String start = st.fixedStart() != null ? st.fixedStart() : span[0];
                    last = Dates.max(span[1], start);
                    placed.add(new Placed(st.ref(), st.phase(), st.kind(), st.who(), st.hours(), start, last, false));
                } else {
                    last = cursor;
                    for (Share sh : st.shares()) {
                        String s;
                        String e;
                        if (sh.hours() <= EPS) {
                            s = cal.nextWork(cursor, sh.memberId());
                            e = s;
                        } else {
                            mine(sh.memberId(), ledger, own);
                            String[] span = fill(epic, st.ref(), sh, cursor, ledger, touched, parts, hours);
                            s = span[0];
                            e = span[1];
                        }
                        String start = st.fixedStart() != null ? st.fixedStart() : s;
                        placed.add(new Placed(st.ref(), st.phase(), st.kind(), sh.memberId(), sh.hours(), start, Dates.max(e, start), true));
                        last = Dates.max(last, e);
                    }
                }
                cursor = Dates.addDays(last, 1);
            }
        }
        List<DayOverrun> over = new ArrayList<>();
        touched.forEach((m, days) -> days.forEach(d -> {
            double h = ledger.get(m).get(d);
            double cap = hours.on(m, d);
            if (h > cap + EPS) {
                over.add(new DayOverrun(m, d, h, cap));
            }
        }));
        over.sort(Comparator.comparing(DayOverrun::memberId).thenComparing(DayOverrun::date));
        return new Result(placed, ledger, over, parts);
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
        if (sh.hours() <= EPS) {
            return;
        }
        List<String> all = Dates.daysBetween(start, end);
        List<String> days = all.stream().filter(d -> hours.on(sh.memberId(), d) > EPS).toList();
        if (days.isEmpty()) {
            days = all.stream().filter(d -> !Text.hasText(cal.off(d, sh.memberId()))).toList();
        }
        if (days.isEmpty()) {
            days = List.of(start);
        }
        Map<String, Double> m = mine(sh.memberId(), ledger, own);
        for (String d : days) {
            m.merge(d, sh.hours() / days.size(), Double::sum);
            touched.computeIfAbsent(sh.memberId(), k -> new TreeSet<>()).add(d);
            parts.add(new Part(ref, sh.memberId(), d, sh.hours() / days.size()));
        }
    }

    /** Takes the share's hours from the member's free hours, day by day from {@code from}; returns its first and last day. */
    private static String[] fill(String epic, String ref, Share st, String from, Map<String, Map<String, Double>> ledger,
                                 Map<String, Set<String>> touched, List<Part> parts, Hours hours) {
        Map<String, Double> mine = ledger.get(st.memberId());
        double left = st.hours();
        String d = from;
        String first = null;
        String last = from;
        int idle = 0;
        while (left > EPS) {
            double free = hours.on(st.memberId(), d) - mine.getOrDefault(d, 0.0);
            if (free > EPS) {
                double take = Math.min(free, left);
                mine.merge(d, take, Double::sum);
                touched.computeIfAbsent(st.memberId(), k -> new TreeSet<>()).add(d);
                parts.add(new Part(ref, st.memberId(), d, take));
                left -= take;
                first = first == null ? d : first;
                last = d;
                idle = 0;
            } else if (++idle >= MasterSchedule.GIVE_UP_DAYS) {
                throw new RuleError(st.memberId() + " has no free hours on " + epic + " from " + from);
            }
            d = Dates.addDays(d, 1);
        }
        return new String[]{first, last};
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
                given.merge(p.memberId() + "|" + p.phase(), p.hours(), Double::sum);
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
                out.add(new Overrun(member, phase, h, budget));
            }
        });
        out.sort(Comparator.comparing(Overrun::phase).thenComparing(Overrun::memberId));
        return out;
    }
}
