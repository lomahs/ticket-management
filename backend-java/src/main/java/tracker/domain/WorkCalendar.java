package tracker.domain;

import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Working-day calendar per member: working weekdays + extra days − country holidays − full-day leave. */
public final class WorkCalendar {
    public static final String WEEKEND = "we";

    private final Set<Integer> workingDays;
    private final Map<String, String> extraDays;
    private final Map<String, Holiday> holidays;
    private final Map<String, Member> members;
    private final Map<String, Leave> leaves = new HashMap<>();

    public WorkCalendar(Collection<Integer> workingDays, Map<String, String> extraDays, Map<String, Holiday> holidays,
                        Map<String, Member> members, Collection<Leave> leaves) {
        this.workingDays = new HashSet<>(workingDays);
        this.extraDays = new LinkedHashMap<>(extraDays);
        this.holidays = new LinkedHashMap<>(holidays);
        this.members = new LinkedHashMap<>(members);
        for (Leave l : leaves) {
            this.leaves.put(leaveKey(l.memberId(), l.date()), l);
        }
    }

    private static String leaveKey(String memberId, String d) {
        return memberId + "\u0000" + d;
    }

    public Map<String, String> extraDays() {
        return extraDays;
    }

    /** Shown on calendars: a working weekday or an extra working day. */
    public boolean visible(String d) {
        return workingDays.contains(Dates.weekday(d)) || extraDays.containsKey(d);
    }

    public Leave leave(String memberId, String d) {
        return leaves.get(leaveKey(memberId, d));
    }

    public Holiday holidayFor(String memberId, String d) {
        Holiday h = holidays.get(d);
        Member m = members.get(memberId);
        return h != null && m != null && h.countries().contains(m.country()) ? h : null;
    }

    /** null on a working day; WEEKEND for a non-working weekday; otherwise the reason (holiday or leave). */
    public String off(String d, String memberId) {
        if (!visible(d)) {
            return WEEKEND;
        }
        Holiday h = holidayFor(memberId, d);
        if (h != null) {
            return h.name();
        }
        Leave lv = leave(memberId, d);
        if (lv != null && LeaveTypeInfo.of(lv.type()).full()) {
            String name = members.containsKey(memberId) ? members.get(memberId).name() : memberId;
            return name + " · " + LeaveTypeInfo.of(lv.type()).label();
        }
        return null;
    }

    /** Share of a standard working day the member is available: 0, 0.5 (half-day leave) or 1. */
    public double capacityFactor(String memberId, String d) {
        if (Py.truthy(off(d, memberId))) {
            return 0;
        }
        Leave lv = leave(memberId, d);
        return lv != null && !LeaveTypeInfo.of(lv.type()).full() ? 0.5 : 1;
    }

    public String nextWork(String d, String memberId) {
        return nextWork(d, memberId, null);
    }

    public String nextWork(String d, String memberId, List<DateReason> skipped) {
        while (true) {
            String reason = off(d, memberId);
            if (!Py.truthy(reason)) {
                return d;
            }
            if (!reason.equals(WEEKEND) && skipped != null) {
                skipped.add(new DateReason(d, reason));
            }
            d = Dates.addDays(d, 1);
        }
    }

    /** First and last day of n working days for the member, starting on or after {@code start}. */
    public String[] span(String start, int n, String memberId, List<DateReason> skipped) {
        String s = nextWork(start, memberId, skipped);
        String e = s;
        for (int i = 1; i < n; i++) {
            e = nextWork(Dates.addDays(e, 1), memberId, skipped);
        }
        return new String[]{s, e};
    }

    public String[] span(String start, int n, String memberId) {
        return span(start, n, memberId, null);
    }
}
