package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** Anken (epic) roll-ups: progress per phase, forecast and the issues that need attention. */
public final class Overview {
    private Overview() {
    }

    /** @param pct time spent over time spent plus remaining, like Jira's progress bar */
    public record PhaseRollup(String name, int pct, int closed, int total, @Nullable String start, @Nullable String end,
                              double spentH, double remainingH) {
    }

    /** Progress of a phase from its sub-tasks' effort: spent / (spent + remaining). */
    public static PhaseRollup phaseRollup(String name, List<Issue> subtasks) {
        String start = subtasks.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo).orElse(null);
        String end = subtasks.stream().map(Issue::plannedEnd).filter(Text::hasText).max(String::compareTo).orElse(null);
        double spent = Numbers.round(subtasks.stream().mapToDouble(Issue::loggedH).sum(), 2);
        double remaining = Numbers.round(subtasks.stream().mapToDouble(Issue::remainingH).sum(), 2);
        int pct = spent + remaining > 0 ? (int) Math.round(spent / (spent + remaining) * 100) : 0;
        int closed = (int) subtasks.stream().filter(i -> i.status() == Status.CLOSED).count();
        return new PhaseRollup(name, pct, closed, subtasks.size(), start, end, spent, remaining);
    }

    public enum Schedule { UNKNOWN, NOT_STARTED, EARLY, ON_TRACK, LATE, DONE }

    /**
     * Early / on track / late of a phase from its sub-tasks (planned dates, estimate, remaining, logged hours, status).
     * Placeholder: the rule is not decided yet, so it always answers {@link Schedule#UNKNOWN}.
     */
    public static Schedule scheduleState(List<Issue> subtasks, String today) {
        return Schedule.UNKNOWN;
    }

    /** The remaining schedule slips by the worst current delay, counted in Mon–Fri days. */
    public static String forecastDate(String plan, int worstLateDays) {
        String d = plan;
        int n = worstLateDays;
        while (n > 0) {
            d = Dates.addDays(d, 1);
            int w = Dates.weekday(d);
            if (w != 0 && w != 6) {
                n--;
            }
        }
        return d;
    }

    /** @param kind overdue | stuck_resolved | many_rounds | waiting_client */
    public record AttentionGroup(String kind, List<Issue> issues) {
    }

    public static List<AttentionGroup> attentionGroups(List<Issue> issues, Map<String, IssueFlags> flags,
                                                       Map<String, Member> members, Thresholds t) {
        java.util.function.Predicate<String> isClient = mid -> Text.hasText(mid) && members.get(mid) != null
                && members.get(mid).role() == Role.JM;
        List<Issue> overdue = new ArrayList<>(issues.stream().filter(i -> flags.get(i.key()).late()).toList());
        overdue.sort(Comparator.comparingInt(i -> -flags.get(i.key()).lateDays()));
        return List.of(
                new AttentionGroup("overdue", overdue),
                new AttentionGroup("stuck_resolved", issues.stream()
                        .filter(i -> i.status() == Status.RESOLVED && flags.get(i.key()).heldDays() >= t.heldDays()).toList()),
                new AttentionGroup("many_rounds", issues.stream()
                        .filter(i -> flags.get(i.key()).rounds() >= t.reviewRounds() && i.status() != Status.CLOSED).toList()),
                new AttentionGroup("waiting_client", issues.stream()
                        .filter(i -> isClient.test(i.assigneeId()) && i.status() != Status.CLOSED
                                && flags.get(i.key()).heldDays() >= t.heldDays()).toList()));
    }
}
