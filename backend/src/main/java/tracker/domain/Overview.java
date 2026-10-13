package tracker.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

/** Anken (epic) roll-ups: progress per phase, forecast and the issues that need attention. */
public final class Overview {
    private Overview() {
    }

    public static final List<String> DEFAULT_PHASE_ORDER = List.of("Prepare", "Implement_Coding", "Function Unit Test");

    public record PhaseRollup(String name, int pct, int closed, int total, @Nullable String start, @Nullable String end) {
    }

    public static PhaseRollup phaseRollup(String name, List<Issue> issues) {
        String start = issues.stream().map(Issue::plannedStart).filter(Text::hasText).min(String::compareTo).orElse(null);
        String end = issues.stream().map(Issue::plannedEnd).filter(Text::hasText).max(String::compareTo).orElse(null);
        int pct = issues.isEmpty() ? 0
                : (int) Math.round(issues.stream().mapToDouble(i -> Flags.PROGRESS_WEIGHT.get(i.status())).sum() / issues.size() * 100);
        int closed = (int) issues.stream().filter(i -> i.status() == Status.CLOSED).count();
        return new PhaseRollup(name, pct, closed, issues.size(), start, end);
    }

    public static List<String> orderedPhases(Iterable<String> names) {
        Set<String> all = new LinkedHashSet<>();
        names.forEach(all::add);
        List<String> out = new ArrayList<>(DEFAULT_PHASE_ORDER.stream().filter(all::contains).toList());
        out.addAll(all.stream().filter(n -> !DEFAULT_PHASE_ORDER.contains(n)).sorted().toList());
        return out;
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
