package tracker.domain;

import org.jspecify.annotations.Nullable;

/**
 * What the detail plan changed on one step of an estimation row; null fields keep the computed value.
 *
 * @param step    the step of a function; null for a row without steps
 * @param effortH the step's hours instead of Create × its weight
 * @param start   with {@code end}: the step's dates, set by hand
 * @param removed the step is left out of the plan and of the effort
 */
public record StepEdit(long rowId, @Nullable StepKind step, @Nullable Double effortH, @Nullable String start, @Nullable String end,
                       boolean removed) {

    public boolean pinned() {
        return start != null && end != null;
    }

    public boolean empty() {
        return effortH == null && !pinned() && !removed;
    }
}
