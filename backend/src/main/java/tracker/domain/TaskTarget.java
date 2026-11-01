package tracker.domain;

import org.jspecify.annotations.Nullable;

/** Where the sub-tasks of a subphase go when the plan is pushed: an existing Task, or a new Task with its name and assignee. */
public record TaskTarget(String phase, String subphase, @Nullable String existingKey, @Nullable String newName,
                         @Nullable String newAssigneeId) {
}
