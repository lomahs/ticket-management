package tracker.domain;

import java.util.List;

import org.jspecify.annotations.Nullable;

/**
 * An anken being set up from its epic to its detail plan.
 *
 * @param step        the furthest step reached, 1 (Add epic) to 6 (Detail plan)
 * @param ankenPrefix the {anken_prefix} of the Task names it creates; "" until entered
 * @param labels      the labels of the Tasks it creates, and of the sub-tasks pushed from its detail plan
 */
public record Onboarding(String epicKey, String ankenPrefix, List<String> labels, int step, String startedAt, String startedBy, String savedAt,
                         String savedBy, @Nullable String finishedAt) {
    public static final int STEPS = 6;

    public Onboarding {
        labels = List.copyOf(labels);
    }
}
