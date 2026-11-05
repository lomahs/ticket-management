package tracker.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * Who owns effort. Actual effort belongs to the worklog's author, classified by the author's role; planned effort is shared
 * between the function's BA and BAL by the step's shares (Settings › Step weights). JM effort is not team effort and exists
 * in no number.
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

    /** A member's part of an issue's planned hours, 0–1. */
    public record PlanShare(String memberId, double fraction) {
    }

    /**
     * Who carries the plan of {@code i}: on a function step, the function's BA and BAL by the step's shares (a share whose
     * person is unknown is dropped; none on JM Review by default); on any other issue, its assignee unless they are a JM
     * (JM effort is in no number).
     */
    public static List<PlanShare> planShares(@Nullable Function fn, Issue i, Map<String, Member> members, StepWeights w) {
        if (fn == null || Steps.stepDef(i) == null) {
            Member m = i.assigneeId() != null ? members.get(i.assigneeId()) : null;
            return i.assigneeId() == null || m != null && m.role() == Role.JM ? List.of() : List.of(new PlanShare(i.assigneeId(), 1));
        }
        StepWeights.Share s = w.shareOf(i.step());
        String ba = fn.baId(members);
        String bal = fn.balId(members);
        List<PlanShare> out = new ArrayList<>();
        if (ba != null && s.ba() > 0) {
            out.add(new PlanShare(ba, s.ba() / 100));
        }
        if (bal != null && s.bal() > 0) {
            if (bal.equals(ba)) {
                out.set(0, new PlanShare(ba, (s.ba() + s.bal()) / 100));
            } else {
                out.add(new PlanShare(bal, s.bal() / 100));
            }
        }
        return out;
    }

    /** The one person who stands for the plan of {@code i} where a screen shows one: the larger share, the BA on a tie. */
    public static @Nullable String planOwner(@Nullable Function fn, Issue i, Map<String, Member> members, StepWeights w) {
        PlanShare best = null;
        for (PlanShare p : planShares(fn, i, members, w)) {
            if (best == null || p.fraction() > best.fraction()) {
                best = p;
            }
        }
        return best == null ? null : best.memberId();
    }

    /** {@link #planOwner(Function, Issue, Map, StepWeights)} with the default step weights. */
    public static @Nullable String planOwner(@Nullable Function fn, Issue i, Map<String, Member> members) {
        return planOwner(fn, i, members, StepWeights.defaults());
    }

    /**
     * The planned load of {@code i} for {@code owner}: its estimate over its planned dates; after a handover on {@code handoverDay}
     * (later than the planned start), the remaining estimate from that day (the planned end at the latest) to the planned end.
     * null without an owner or planned dates.
     */
    public static Capacity.@Nullable PlanItem planItem(Issue i, @Nullable String owner, @Nullable String handoverDay) {
        return planItem(i, owner, handoverDay, 1);
    }

    /** As above, for a share of {@code fraction} of the hours. */
    public static Capacity.@Nullable PlanItem planItem(Issue i, @Nullable String owner, @Nullable String handoverDay, double fraction) {
        if (!Text.hasText(owner) || !Text.hasText(i.plannedStart()) || !Text.hasText(i.plannedEnd())) {
            return null;
        }
        if (handoverDay != null && handoverDay.compareTo(i.plannedStart()) > 0) {
            String from = handoverDay.compareTo(i.plannedEnd()) > 0 ? i.plannedEnd() : handoverDay;
            return new Capacity.PlanItem(i.key(), owner, from, i.plannedEnd(), i.remainingH() * fraction);
        }
        return new Capacity.PlanItem(i.key(), owner, i.plannedStart(), i.plannedEnd(), i.estimateH() * fraction);
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
