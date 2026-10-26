package tracker.domain;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Data problems of a sub-task that make the screens wrong; the Ticket Check page lists them so they get fixed in Jira. */
public final class TicketChecks {
    private TicketChecks() {
    }

    /** Neither a function step nor a single ticket: the Summary does not match the anken's name rule. */
    public static final String RULE = "rule";
    public static final String NO_END = "no_end";
    public static final String NO_START = "no_start";
    public static final String NO_ASSIGNEE = "no_assignee";
    public static final String NO_LABELS = "no_labels";
    public static final String NO_ESTIMATE = "no_estimate";
    /** The assignee is not a member of the sub-task's anken. */
    public static final String NOT_MEMBER = "not_member";
    public static final String CLOSED_NO_LOG = "closed_no_log";
    /** A function step started while an earlier step of the function was not done (Resolved or Closed). */
    public static final String STEP_ORDER = "step_order";
    /** Every check, in the order the page lists them. */
    public static final List<String> ALL = List.of(RULE, NO_END, NO_START, NO_ASSIGNEE, NO_LABELS, NO_ESTIMATE, NOT_MEMBER, CLOSED_NO_LOG,
            STEP_ORDER);

    /**
     * The problems of sub-task {@code i}. Plan fields (dates, assignee, estimate, membership) are judged on open sub-tasks only: a
     * closed one is history.
     *
     * @param member     whether the assignee is a member of the anken
     * @param outOfOrder whether {@code i} is a step that started before an earlier step was done ({@link #outOfOrder})
     */
    public static List<String> of(Issue i, boolean member, boolean outOfOrder) {
        List<String> out = new ArrayList<>();
        boolean closed = i.status() == Status.CLOSED;
        if (i.functionId() == null && !i.single()) {
            out.add(RULE);
        }
        if (!closed) {
            if (!Text.hasText(i.plannedEnd())) {
                out.add(NO_END);
            }
            if (!Text.hasText(i.plannedStart())) {
                out.add(NO_START);
            }
            if (!Text.hasText(i.assigneeId())) {
                out.add(NO_ASSIGNEE);
            }
        }
        if (i.labels().isEmpty()) {
            out.add(NO_LABELS);
        }
        if (!closed) {
            if (i.estimateH() <= 0) {
                out.add(NO_ESTIMATE);
            }
            if (Text.hasText(i.assigneeId()) && !member) {
                out.add(NOT_MEMBER);
            }
        }
        if (closed && i.loggedH() <= 0) {
            out.add(CLOSED_NO_LOG);
        }
        if (outOfOrder) {
            out.add(STEP_ORDER);
        }
        return out;
    }

    /** The keys of the steps of {@code f} that started while an earlier step was not done yet (missing steps are skipped). */
    public static Set<String> outOfOrder(Function f) {
        Set<String> out = new HashSet<>();
        boolean earlierOpen = false;
        for (Issue c : f.cells()) {
            if (c == null) {
                continue;
            }
            boolean started = c.status() != Status.NYS || Flags.actualStart(c) != null;
            if (started && earlierOpen) {
                out.add(c.key());
            }
            earlierOpen |= c.status() != Status.RESOLVED && c.status() != Status.CLOSED;
        }
        return out;
    }
}
