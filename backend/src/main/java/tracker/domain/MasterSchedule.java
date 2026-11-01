package tracker.domain;

import java.util.List;

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
            double day = 0;
            for (String m : memberIds) {
                day += hoursOn(m, epic, d, a, cal);
            }
            if (day > 0) {
                md++;
                idle = 0;
                left -= day;
                if (left <= 1e-9) {
                    return new PhaseEnd(d, md);
                }
            } else if (++idle >= GIVE_UP_DAYS) {
                List<String> none = memberIds.stream().filter(m -> !a.hasAny(m, epic)).toList();
                throw new RuleError("No member of the phase has an allocation on " + epic + " from " + Dates.addDays(d, 1 - GIVE_UP_DAYS)
                        + (none.isEmpty() ? "" : " (none at all: " + String.join(", ", none) + ")"));
            }
            d = Dates.addDays(d, 1);
        }
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
