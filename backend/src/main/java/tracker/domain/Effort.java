package tracker.domain;

import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Who owns effort. Actual effort belongs to the worklog's author, classified by the author's role; planned effort belongs to
 * the role of the step. JM effort is not team effort and exists in no number.
 */
public final class Effort {
    private Effort() {
    }

    /** What a worklog on a function step counts as; INVALID when its author's role does no work on that step. */
    public enum WorkKind { CREATE, FIX, REVIEW, INVALID }

    /** BA on Create: create; BA on a Fix comment step: fix; BAL on BAL Review or a Fix comment step: review; else invalid. */
    public static WorkKind kindOf(StepKind step, @Nullable Role author) {
        boolean fix = step == StepKind.FIX_BAL || step == StepKind.FIX_JM;
        if (author == Role.BA) {
            return step == StepKind.CREATE ? WorkKind.CREATE : fix ? WorkKind.FIX : WorkKind.INVALID;
        }
        if (author == Role.BAL) {
            return step == StepKind.BAL_REVIEW || fix ? WorkKind.REVIEW : WorkKind.INVALID;
        }
        return WorkKind.INVALID;
    }

    /** The role of a worklog's author; null for someone who is not a member or has no role. */
    public static @Nullable Role roleOf(Worklog w, Map<String, Member> members) {
        Member m = members.get(w.memberId());
        return m != null ? m.role() : null;
    }

    /** The issue without the worklogs written by a JM. */
    public static Issue withoutJm(Issue i, Map<String, Member> members) {
        if (i.worklogs().stream().noneMatch(w -> roleOf(w, members) == Role.JM)) {
            return i;
        }
        return i.withWorklogs(i.worklogs().stream().filter(w -> roleOf(w, members) != Role.JM).toList());
    }

    /**
     * Who the plan of {@code i} belongs to: on a function step, the function's BA for Create and the Fix comment steps, its BAL
     * for BAL Review and nobody for JM Review; on any other issue, its assignee unless they are a JM (JM effort is in no number).
     */
    public static @Nullable String planOwner(@Nullable Function fn, Issue i, Map<String, Member> members) {
        if (fn == null || Steps.stepDef(i) == null) {
            Member m = i.assigneeId() != null ? members.get(i.assigneeId()) : null;
            return m != null && m.role() == Role.JM ? null : i.assigneeId();
        }
        return switch (i.step()) {
            case CREATE, FIX_BAL, FIX_JM -> fn.baId(members);
            case BAL_REVIEW -> fn.balId(members);
            case JM_REVIEW -> null;
        };
    }

    /**
     * The planned load of {@code i} for {@code owner}: its estimate over its planned dates; after a handover on {@code handoverDay}
     * (later than the planned start), the remaining estimate from that day (the planned end at the latest) to the planned end.
     * null without an owner or planned dates.
     */
    public static Capacity.@Nullable PlanItem planItem(Issue i, @Nullable String owner, @Nullable String handoverDay) {
        if (!Text.hasText(owner) || !Text.hasText(i.plannedStart()) || !Text.hasText(i.plannedEnd())) {
            return null;
        }
        if (handoverDay != null && handoverDay.compareTo(i.plannedStart()) > 0) {
            String from = handoverDay.compareTo(i.plannedEnd()) > 0 ? i.plannedEnd() : handoverDay;
            return new Capacity.PlanItem(i.key(), owner, from, i.plannedEnd(), i.remainingH());
        }
        return new Capacity.PlanItem(i.key(), owner, i.plannedStart(), i.plannedEnd(), i.estimateH());
    }

    /** Where a function step stands in a member's My Work. */
    public enum Place { MINE, RELATED }

    /**
     * By the role of the step, over the functions the member is the BA or BAL of: a BA's own work is Create and the Fix comment
     * steps that are not with the BAL; a BAL's is BAL Review and the Fix comment steps assigned to them; the function's other
     * steps are related. null when the member is neither the function's BA nor its BAL.
     */
    public static @Nullable Place place(Function fn, Issue step, String memberId, Map<String, Member> members) {
        Member m = members.get(memberId);
        Role role = m != null ? m.role() : null;
        boolean fix = step.step() == StepKind.FIX_BAL || step.step() == StepKind.FIX_JM;
        if (role == Role.BA && memberId.equals(fn.baId(members))) {
            if (step.step() == StepKind.CREATE) {
                return Place.MINE;
            }
            return fix && !Objects.equals(step.assigneeId(), fn.balId(members)) ? Place.MINE : Place.RELATED;
        }
        if (role == Role.BAL && memberId.equals(fn.balId(members))) {
            if (step.step() == StepKind.BAL_REVIEW) {
                return Place.MINE;
            }
            return fix && memberId.equals(step.assigneeId()) ? Place.MINE : Place.RELATED;
        }
        return null;
    }
}
