package tracker.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * A function: its sub-tasks grouped by (epic, parent Task, function id).
 *
 * @param parentKey the Jira Task the sub-tasks belong to
 * @param cells     one slot per step, null when that sub-task does not exist
 */
public record Function(String epicKey, String parentKey, String id, String name, List<Issue> cells) {

    public Function {
        cells = Collections.unmodifiableList(new ArrayList<>(cells));
    }

    /** The BA who owns the function: the Create step's assignee. */
    public String ownerId() {
        Issue create = cells.get(0);
        if (create != null) {
            return create.assigneeId();
        }
        return cells.stream().filter(Objects::nonNull).map(Issue::assigneeId).findFirst().orElse(null);
    }

    /** The BAL reviewing the function. */
    public String reviewerId() {
        Issue c = cells.get(1);
        return c != null ? c.assigneeId() : null;
    }

    /** Index of the first step that is missing or not closed; 5 when all steps are closed. */
    public int current() {
        for (int i = 0; i < cells.size(); i++) {
            Issue c = cells.get(i);
            if (c == null || c.status() != Status.CLOSED) {
                return i;
            }
        }
        return Steps.COUNT;
    }

    public List<Issue> issues() {
        return cells.stream().filter(Objects::nonNull).toList();
    }
}
