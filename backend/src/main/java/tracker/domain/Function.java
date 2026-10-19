package tracker.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;

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

    /**
     * The BA shown for the function: the Create step's latest assignee with the BA role, so a handover to another BA moves it
     * and lending the ticket to a BAL or JM does not; without one, the Create step's first assignee; without a Create step, the owner.
     *
     * @param bas members with the BA role
     */
    public String baId(Set<String> bas) {
        Issue create = cells.get(0);
        if (create == null) {
            return ownerId();
        }
        List<String> history = create.assigneeHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (bas.contains(history.get(i))) {
                return history.get(i);
            }
        }
        return create.firstAssigneeId();
    }

    /** The BAL shown for the function: the Create step's first reporter; without one, the reviewer. */
    public String balId() {
        Issue create = cells.get(0);
        return create != null && create.reporterId() != null ? create.reporterId() : reviewerId();
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
