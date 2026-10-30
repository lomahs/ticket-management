package tracker.domain;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * A function: its sub-tasks grouped by (epic, parent Task, function id).
 *
 * @param parentKey the Jira Task the sub-tasks belong to
 * @param cells     one slot per step of the 5-step workflow, null when that sub-task does not exist
 */
public record Function(String epicKey, String parentKey, String id, String name, List<Issue> cells) {

    public Function {
        cells = Collections.unmodifiableList(new ArrayList<>(cells));
    }

    /** The Create step's assignee: the legacy worker identity, used by Planning only. */
    public String ownerId() {
        Issue create = cells.get(0);
        if (create != null) {
            return create.assigneeId();
        }
        return cells.stream().filter(Objects::nonNull).map(Issue::assigneeId).findFirst().orElse(null);
    }

    private static boolean has(Map<String, Member> members, @Nullable String id, Role role) {
        Member m = id != null ? members.get(id) : null;
        return m != null && m.role() == role;
    }

    /**
     * The function's BA: the Create step's assignee when they have the BA role; else the latest assignee with the BA role in
     * its history (the Create step goes back to PIM to be closed, or was handed over); else none.
     */
    public @Nullable String baId(Map<String, Member> members) {
        Issue create = cells.get(0);
        if (create == null) {
            return null;
        }
        if (has(members, create.assigneeId(), Role.BA)) {
            return create.assigneeId();
        }
        List<String> history = create.assigneeHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            if (has(members, history.get(i), Role.BA)) {
                return history.get(i);
            }
        }
        return null;
    }

    /**
     * The function's BAL: the Create step's reporter when they have the BAL role; else the first assignee with the BAL role in
     * the BAL Review step's history; else none.
     */
    public @Nullable String balId(Map<String, Member> members) {
        Issue create = cells.get(0);
        if (create != null && has(members, create.reporterId(), Role.BAL)) {
            return create.reporterId();
        }
        Issue review = cells.get(1);
        if (review == null) {
            return null;
        }
        return review.assigneeHistory().stream().filter(id -> has(members, id, Role.BAL)).findFirst().orElse(null);
    }

    /**
     * The day (YYYY-MM-DD) the BA took the Create step over from another BA: the latest assignment to {@link #baId} that comes
     * after another BA held it. null when no other BA held it before.
     */
    public @Nullable String handoverDay(Map<String, Member> members) {
        String ba = baId(members);
        Issue create = cells.get(0);
        if (ba == null) {
            return null;
        }
        List<IssueEvent> moves = create.events().stream().filter(e -> e.field() == EventField.ASSIGNEE)
                .sorted(Comparator.comparing(IssueEvent::at)).toList();
        boolean otherBa = has(members, create.firstAssigneeId(), Role.BA) && !ba.equals(create.firstAssigneeId());
        String day = null;
        for (IssueEvent e : moves) {
            if (ba.equals(e.toValue()) && otherBa) {
                day = e.at().substring(0, Math.min(10, e.at().length()));
            }
            if (has(members, e.toValue(), Role.BA) && !ba.equals(e.toValue())) {
                otherBa = true;
            }
        }
        return day;
    }

    /** 3 when the function has neither a JM Review nor a Fix comment JM sub-task (the 3-step variant), else 5. */
    public int stepCount() {
        return cells.get(3) == null && cells.get(4) == null ? 3 : Steps.COUNT;
    }

    /** The slots of the function's own steps: the first {@link #stepCount} cells. */
    public List<Issue> steps() {
        return cells.subList(0, stepCount());
    }

    /** Index of the first of the function's steps that is missing or not closed; {@link #stepCount} when all are closed. */
    public int current() {
        int n = stepCount();
        for (int i = 0; i < n; i++) {
            Issue c = cells.get(i);
            if (c == null || c.status() != Status.CLOSED) {
                return i;
            }
        }
        return n;
    }

    /** Every sub-task the function has is closed. */
    public boolean done() {
        return current() >= stepCount();
    }

    public List<Issue> issues() {
        return cells.stream().filter(Objects::nonNull).toList();
    }
}
