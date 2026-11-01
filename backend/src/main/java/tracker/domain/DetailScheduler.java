package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
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
 * of a function follow each other. JM Review takes no allocation: it lasts its hours over the working hours of a day.
 */
public final class DetailScheduler {
    private DetailScheduler() {
    }

    /**
     * @param kind       the step of a function; null for a ticket without steps
     * @param fixedStart a Planned Start to keep (re-plan of a started sub-task); the hours are still placed from the chain's day on
     */
    public record Step(String ref, String phase, @Nullable StepKind kind, String memberId, double hours, @Nullable String fixedStart) {
    }

    /** One function (its steps in order) or one ticket; nothing of it is placed before {@code earliest}. */
    public record Chain(String earliest, List<Step> steps) {
    }

    public record Placed(String ref, String phase, @Nullable StepKind kind, String memberId, double hours, String start, String end) {
    }

    /** The hours given to a member in a phase (JM Review aside) above the member's budget there. */
    public record Overrun(String memberId, String phase, double plannedH, double budgetH) {
    }

    /** A day on which the member has more hours planned than the allocation gives. */
    public record DayOverrun(String memberId, String date, double plannedH, double allocationH) {
    }

    /**
     * @param ledger  member → day → hours planned, the loads included
     * @param overDay the days this run placed hours on that end up above the allocation (loads alone are not the run's)
     */
    public record Result(List<Placed> placed, Map<String, Map<String, Double>> ledger, List<DayOverrun> overDay) {
    }

    private static final double EPS = 1e-9;

    /**
     * @param loads hours already planned per member and day (work outside this run), which this run does not use again
     * @throws RuleError when a member gets no hours on the anken for {@value MasterSchedule#GIVE_UP_DAYS} days in a row
     */
    public static Result schedule(String epic, List<Chain> chains, Map<String, Map<String, Double>> loads, Allocations a, WorkCalendar cal,
                                  double jmHoursPerDay) {
        Map<String, Map<String, Double>> ledger = new HashMap<>();
        loads.forEach((m, days) -> ledger.put(m, new TreeMap<>(days)));
        Map<String, Set<String>> touched = new HashMap<>();
        List<Placed> placed = new ArrayList<>();
        for (Chain chain : chains) {
            String cursor = chain.earliest();
            for (Step st : chain.steps()) {
                String s;
                String e;
                if (st.kind() == StepKind.JM_REVIEW) {
                    int days = Math.max(1, (int) Math.ceil(st.hours() / jmHoursPerDay - EPS));
                    String[] span = cal.span(cursor, days, st.memberId());
                    s = span[0];
                    e = span[1];
                } else if (st.hours() <= EPS) {
                    s = cal.nextWork(cursor, st.memberId());
                    e = s;
                } else {
                    String[] span = fill(epic, st, cursor, ledger, touched, a, cal);
                    s = span[0];
                    e = span[1];
                }
                String start = st.fixedStart() != null ? st.fixedStart() : s;
                placed.add(new Placed(st.ref(), st.phase(), st.kind(), st.memberId(), st.hours(), start, Dates.max(e, start)));
                cursor = Dates.addDays(e, 1);
            }
        }
        List<DayOverrun> over = new ArrayList<>();
        touched.forEach((m, days) -> days.forEach(d -> {
            double h = ledger.get(m).get(d);
            double cap = MasterSchedule.hoursOn(m, epic, d, a, cal);
            if (h > cap + EPS) {
                over.add(new DayOverrun(m, d, h, cap));
            }
        }));
        over.sort(Comparator.comparing(DayOverrun::memberId).thenComparing(DayOverrun::date));
        return new Result(placed, ledger, over);
    }

    /** Takes the step's hours from the member's free hours, day by day from {@code from}; returns its first and last day. */
    private static String[] fill(String epic, Step st, String from, Map<String, Map<String, Double>> ledger,
                                 Map<String, Set<String>> touched, Allocations a, WorkCalendar cal) {
        Map<String, Double> mine = ledger.computeIfAbsent(st.memberId(), k -> new TreeMap<>());
        double left = st.hours();
        String d = from;
        String first = null;
        String last = from;
        int idle = 0;
        while (left > EPS) {
            double free = MasterSchedule.hoursOn(st.memberId(), epic, d, a, cal) - mine.getOrDefault(d, 0.0);
            if (free > EPS) {
                double take = Math.min(free, left);
                mine.merge(d, take, Double::sum);
                touched.computeIfAbsent(st.memberId(), k -> new TreeSet<>()).add(d);
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
            if (p.kind() != StepKind.JM_REVIEW) {
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
