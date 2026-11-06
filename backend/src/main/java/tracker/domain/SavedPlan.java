package tracker.domain;

import java.util.List;

/** The detail plan Auto allocate saved: each step's placements and its hours per person and day. */
public record SavedPlan(List<DetailScheduler.Placed> placed, List<DetailScheduler.Part> parts) {
    public static final SavedPlan EMPTY = new SavedPlan(List.of(), List.of());
}
