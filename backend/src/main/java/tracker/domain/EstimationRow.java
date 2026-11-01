package tracker.domain;

import org.jspecify.annotations.Nullable;

/**
 * One row of an anken's estimation: a function (3 or 5 steps) or a ticket without steps ({@code steps} null).
 *
 * @param effortH hours of the Create step of a function; of the whole ticket for a row without steps
 * @param baId    the BA of a function, or the one member of a row without steps (detail-plan draft)
 * @param taskKey the Task its sub-tasks go under
 */
public record EstimationRow(long id, String epicKey, int position, String phase, String subphase, String functionName,
                            @Nullable Integer steps, double effortH, @Nullable String baId, @Nullable String balId,
                            @Nullable String jmId, @Nullable String taskKey, @Nullable String pushedAt) {

    public boolean pushed() {
        return pushedAt != null;
    }
}
