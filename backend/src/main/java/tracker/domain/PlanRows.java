package tracker.domain;

import java.util.ArrayList;
import java.util.List;

import org.jspecify.annotations.Nullable;

import tracker.domain.DetailScheduler.Chain;
import tracker.domain.DetailScheduler.Share;
import tracker.domain.DetailScheduler.Step;

/**
 * The steps of an estimation row in the detail plan: one per step of a function (one for a ticket), with what the plan
 * changed on them ({@link StepEdit}), carried by the row's BA and BAL by the shares of Settings › Step weights.
 */
public final class PlanRows {
    private PlanRows() {
    }

    /**
     * @param kind  the step; null for a ticket
     * @param hours the step's hours: its edit, else Create × its weight (the row's effort for Create and a ticket)
     * @param edit  what the plan changed on it; null when nothing
     */
    public record RowStep(@Nullable StepKind kind, String ref, double hours, @Nullable StepEdit edit) {
        public boolean removed() {
            return edit != null && edit.removed();
        }

        public boolean pinned() {
            return edit != null && edit.pinned();
        }
    }

    /** The steps of a row, removed ones included. */
    public static List<RowStep> steps(long id, @Nullable Integer steps, double effortH, List<StepEdit> edits, StepWeights w) {
        if (steps == null) {
            StepEdit e = edit(id, null, edits);
            return List.of(new RowStep(null, String.valueOf(id), effortH, e));
        }
        List<RowStep> out = new ArrayList<>();
        for (StepKind k : StepWeights.stepsOf(steps)) {
            StepEdit e = edit(id, k, edits);
            double h = e != null && e.effortH() != null ? e.effortH() : k == StepKind.CREATE ? effortH : w.stepHours(effortH, k);
            out.add(new RowStep(k, id + ":" + k.value(), h, e));
        }
        return out;
    }

    private static @Nullable StepEdit edit(long id, @Nullable StepKind k, List<StepEdit> edits) {
        return edits.stream().filter(e -> e.rowId() == id && e.step() == k).findFirst().orElse(null);
    }

    /** Hours of a step for the BA and the BAL by its shares (a ticket is all its member's, kept as the BA). */
    public static double[] split(RowStep st, StepWeights w) {
        if (st.kind() == null) {
            return new double[]{st.hours(), 0};
        }
        StepWeights.Share s = w.shareOf(st.kind());
        return new double[]{st.hours() * s.ba() / 100, st.hours() * s.bal() / 100};
    }

    /**
     * Whether a row needs someone in that role: a BA always; a BAL when a step not removed counts for the BAL or is assigned
     * to the BAL; a JM when JM Review is not removed.
     */
    public static boolean needs(List<RowStep> steps, Role role, StepWeights w) {
        List<RowStep> kept = steps.stream().filter(s -> !s.removed()).toList();
        return switch (role) {
            case BA -> true;
            case BAL -> kept.stream().anyMatch(s -> s.kind() != null && (w.shareOf(s.kind()).bal() > 0 || w.picOf(s.kind()) == Role.BAL));
            case JM -> kept.stream().anyMatch(s -> s.kind() == StepKind.JM_REVIEW);
            default -> false;
        };
    }

    /**
     * The row's chain with these people: removed steps are left out, a share whose person is missing is dropped and a step
     * nobody carries is left out. A step that counts for nobody follows the JM on JM Review, else its PIC. Null when nothing
     * is left.
     */
    public static @Nullable Chain chain(String phase, List<RowStep> steps, @Nullable String ba, @Nullable String bal, @Nullable String jm,
                                        StepWeights w, String earliest) {
        List<Step> out = new ArrayList<>();
        for (RowStep st : steps) {
            if (st.removed()) {
                continue;
            }
            String start = st.edit() == null ? null : st.edit().start();
            String end = st.edit() == null ? null : st.edit().end();
            if (st.kind() != null && !w.shareOf(st.kind()).counted()) {
                String who = st.kind() == StepKind.JM_REVIEW ? jm : w.picOf(st.kind()) == Role.BAL ? bal : ba;
                if (who != null) {
                    out.add(new Step(st.ref(), phase, st.kind(), List.of(), who, st.hours(), start, end));
                }
                continue;
            }
            double[] h = split(st, w);
            List<Share> shares = new ArrayList<>();
            if (ba != null && h[0] > 0) {
                shares.add(new Share(ba, h[0]));
            }
            if (bal != null && h[1] > 0) {
                if (bal.equals(ba)) {
                    shares.set(0, new Share(ba, h[0] + h[1]));
                } else {
                    shares.add(new Share(bal, h[1], true));
                }
            }
            if (shares.isEmpty() && st.hours() <= 0 && ba != null) { // a step of 0 hours still takes its day
                shares.add(new Share(ba, 0));
            }
            if (!shares.isEmpty()) {
                out.add(new Step(st.ref(), phase, st.kind(), shares, shares.getFirst().memberId(), st.hours(), start, end));
            }
        }
        return out.isEmpty() ? null : new Chain(earliest, out);
    }

    /** The person Jira assigns a step to at push: the BA or the BAL by its PIC; a ticket's member. */
    public static @Nullable String assignee(RowStep st, @Nullable String ba, @Nullable String bal, StepWeights w) {
        return st.kind() == null || w.picOf(st.kind()) == Role.BA ? ba : bal != null ? bal : ba;
    }
}
