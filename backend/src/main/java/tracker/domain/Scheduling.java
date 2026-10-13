package tracker.domain;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** Plan suggestions: dates for a new function's 5 steps, and shifting a planned item. */
public final class Scheduling {
    private Scheduling() {
    }

    public static final int HOURS_PER_PLANNED_DAY = 6; // a step needs one planned day per 6 estimated hours

    /**
     * @param note    auto | skips (suggestion skipped days off) | edited | dayoff (an edited date lands on a day off)
     * @param reasons (date, reason); reason "weekend" for weekends
     */
    public record PlannedStep(StepKind step, String assigneeId, double estimateH, String start, String end, String note,
                              List<DateReason> reasons) {
    }

    /** Create × weight, rounded to the nearest half hour. */
    public static double stepEstimate(double createHours, double weight) {
        return Dates.roundHalfUp(createHours * weight * 2) / 2;
    }

    /** The days off among {@code days} (deduplicated, in order) for the member. */
    public static List<DateReason> daysOff(WorkCalendar cal, String memberId, String... days) {
        List<DateReason> offs = new ArrayList<>();
        for (String d : new LinkedHashSet<>(List.of(days))) {
            String r = cal.off(d, memberId);
            if (Text.hasText(r)) {
                offs.add(new DateReason(d, r.equals(WorkCalendar.WEEKEND) ? "weekend" : r));
            }
        }
        return offs;
    }

    /** Each step starts on the working day after the previous one ends; {@code overrides} may fix a step's start/end. */
    public static List<PlannedStep> suggestFunctionPlan(String firstDay, double createHours, List<String> assignees,
                                                        WorkCalendar cal, List<Map<String, String>> overrides) {
        List<PlannedStep> out = new ArrayList<>();
        String cur = firstDay;
        for (int i = 0; i < Steps.COUNT; i++) {
            StepDef st = Steps.STEPS.get(i);
            String who = assignees.get(i);
            double est = stepEstimate(createHours, st.effortWeight());
            int dur = Math.max(1, (int) Math.ceil(est / HOURS_PER_PLANNED_DAY));
            List<DateReason> skipped = new ArrayList<>();
            String[] suggested = cal.span(cur, dur, who, skipped);
            Map<String, String> o = overrides.get(i) != null ? overrides.get(i) : Map.of();
            String oStart = o.get("start");
            String oEnd = o.get("end");
            String start = Text.orElse(oStart, suggested[0]);
            String end = Text.hasText(oEnd) ? oEnd : Text.hasText(oStart) ? cal.span(oStart, dur, who)[1] : suggested[1];
            end = Dates.max(end, start);
            cur = Dates.addDays(end, 1);
            if (Text.hasText(oStart) || Text.hasText(oEnd)) {
                List<DateReason> offs = daysOff(cal, who, start, end);
                out.add(new PlannedStep(st.kind(), who, est, start, end, offs.isEmpty() ? "edited" : "dayoff", offs));
            } else {
                out.add(new PlannedStep(st.kind(), who, est, start, end, skipped.isEmpty() ? "auto" : "skips", skipped));
            }
        }
        return out;
    }

    /** Moves a planned item to {@code newStart}, keeping its length in calendar days. */
    public static String[] shiftPlan(String start, String end, String newStart) {
        return new String[]{newStart, Dates.addDays(newStart, Dates.dayDiff(start, end))};
    }
}
