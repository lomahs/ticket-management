package tracker.domain;

import org.jspecify.annotations.Nullable;

/**
 * What the detail plan changed on one step of an estimation row; null fields keep the computed value.
 *
 * @param step    the step of a function; null for a row without steps
 * @param effortH the step's hours instead of Create × its weight
 * @param start   with {@code end}: the step's dates, set by hand
 * @param removed the step is left out of the plan and of the effort
 * @param deleted removed for good: the step is no longer one of the row's, and clearing the plan keeps it out
 */
public record StepEdit(long rowId, @Nullable StepKind step, @Nullable Double effortH, @Nullable String start, @Nullable String end,
                       boolean removed, boolean deleted) {

    public StepEdit(long rowId, @Nullable StepKind step, @Nullable Double effortH, @Nullable String start, @Nullable String end, boolean removed) {
        this(rowId, step, effortH, start, end, removed, false);
    }

    public boolean pinned() {
        return start != null && end != null;
    }

    public boolean empty() {
        return effortH == null && !pinned() && !removed && !deleted;
    }
}
