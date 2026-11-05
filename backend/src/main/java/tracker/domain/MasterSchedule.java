package tracker.domain;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Master schedule rules: the hours a member gives an anken on a day, a phase's end, a member's budget in a phase. */
public final class MasterSchedule {
    private MasterSchedule() {
    }

    /** Days in a row without any hours after which a phase is said never to finish. */
    static final int GIVE_UP_DAYS = 366;

    /** Allocation on that day × the share of the day the member works (0 on a day off, half on a half-day leave). */
    public static double hoursOn(String memberId, String epic, String day, Allocations a, WorkCalendar cal) {
        Double h = a.hoursOn(memberId, epic, day);
        return h == null ? 0 : h * cal.capacityFactor(memberId, day);
    }

    /** @param planMd the working days walked from the start: days on which at least one member works */
    public record PhaseEnd(String end, int planMd) {
    }

    /**
     * What a phase asks of its BAs, over the steps the detail plan did not remove.
     *
     * @param effortH       hours of the tickets and the BA's share of each step (Settings › Step weights)
     * @param beforeReviewH hours of the tickets and the Creates: the work done before the last function's reviews can start
     * @param tailDays      working days the longest function still takes after its Create: one per step, JM Review its own span
     */
    public record PhaseWork(double effortH, double beforeReviewH, int tailDays) {
        static final PhaseWork NONE = new PhaseWork(0, 0, 0);

        PhaseWork plus(PhaseWork o) {
            return new PhaseWork(effortH + o.effortH, beforeReviewH + o.beforeReviewH, Math.max(tailDays, o.tailDays));
        }
    }

    /** Phase → its BA work, phases in the order of their first row. */
    public static Map<String, PhaseWork> work(List<EstimationRow> rows, StepWeights w, double jmHoursPerDay) {
        return work(rows, List.of(), w, jmHoursPerDay);
    }

    /** As above, with the steps the detail plan changed or removed. */
    public static Map<String, PhaseWork> work(List<EstimationRow> rows, List<StepEdit> edits, StepWeights w, double jmHoursPerDay) {
        Map<String, PhaseWork> out = new LinkedHashMap<>();
        for (EstimationRow r : rows) {
            out.merge(r.phase(), workOf(PlanRows.steps(r.id(), r.steps(), r.effortH(), edits, w), w, jmHoursPerDay), PhaseWork::plus);
        }
        return out;
    }

    static PhaseWork workOf(List<PlanRows.RowStep> steps, StepWeights w, double jmHoursPerDay) {
        double ba = 0;
        double before = 0;
        int tail = 0;
        for (PlanRows.RowStep st : steps) {
            if (st.removed()) {
                continue;
            }
            ba += PlanRows.split(st, w)[0];
            if (st.kind() == null || st.kind() == StepKind.CREATE) {
                before += st.hours();
            } else if (st.kind() == StepKind.JM_REVIEW) {
                tail += Math.max(1, (int) Math.ceil(st.hours() / jmHoursPerDay - 1e-9)); // as the detail plan spans it
            } else {
                tail++;
            }
        }
        return new PhaseWork(ba, before, tail);
    }

    /**
     * The End of a phase from its BAs' hours: the later of the day all its BA work is done and the day the longest review chain
     * ends when it starts after the Creates and tickets.
     *
     * @throws RuleError when the BAs give no hours for {@value #GIVE_UP_DAYS} days in a row
     */
    public static PhaseEnd endOf(String epic, String start, PhaseWork work, List<String> baIds, Allocations a, WorkCalendar cal) {
        PhaseEnd all = endOf(epic, start, work.effortH(), baIds, a, cal);
        if (work.tailDays() == 0) {
            return all;
        }
        PhaseEnd before = endOf(epic, start, work.beforeReviewH(), baIds, a, cal);
        String d = before.end();
        int md = before.planMd();
        int idle = 0;
        for (int left = work.tailDays(); left > 0; ) {
            d = Dates.addDays(d, 1);
            if (dayHours(epic, d, baIds, a, cal) > 0) {
                left--;
                md++;
                idle = 0;
            } else if (++idle >= GIVE_UP_DAYS) {
                throw noHours(epic, d, baIds, a);
            }
        }
        return d.compareTo(all.end()) > 0 ? new PhaseEnd(d, md) : all;
    }

    /**
     * Walks the days from {@code start}; each day the members give their hours until {@code effortH} is used up.
     *
     * @throws RuleError when the members give no hours for {@value #GIVE_UP_DAYS} days in a row
     */
    public static PhaseEnd endOf(String epic, String start, double effortH, List<String> memberIds, Allocations a, WorkCalendar cal) {
        if (effortH <= 0) {
            return new PhaseEnd(start, 0);
        }
        double left = effortH;
        int md = 0;
        int idle = 0;
        String d = start;
        while (true) {
            double day = dayHours(epic, d, memberIds, a, cal);
            if (day > 0) {
                md++;
                idle = 0;
                left -= day;
                if (left <= 1e-9) {
                    return new PhaseEnd(d, md);
                }
            } else if (++idle >= GIVE_UP_DAYS) {
                throw noHours(epic, d, memberIds, a);
            }
            d = Dates.addDays(d, 1);
        }
    }

    private static double dayHours(String epic, String d, List<String> memberIds, Allocations a, WorkCalendar cal) {
        double day = 0;
        for (String m : memberIds) {
            day += hoursOn(m, epic, d, a, cal);
        }
        return day;
    }

    private static RuleError noHours(String epic, String d, List<String> memberIds, Allocations a) {
        List<String> none = memberIds.stream().filter(m -> !a.hasAny(m, epic)).toList();
        return new RuleError("No member of the phase has an allocation on " + epic + " from " + Dates.addDays(d, 1 - GIVE_UP_DAYS)
                + (none.isEmpty() ? "" : " (none at all: " + String.join(", ", none) + ")"));
    }

    /** Σ hours the member gives the anken from {@code start} to {@code end}, holidays and leave excluded. */
    public static double budget(String memberId, String epic, String start, String end, Allocations a, WorkCalendar cal) {
        double sum = 0;
        for (String d : Dates.daysBetween(start, end)) {
            sum += hoursOn(memberId, epic, d, a, cal);
        }
        return sum;
    }
}
