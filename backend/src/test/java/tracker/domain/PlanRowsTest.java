package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tracker.domain.Effort.PlanShare;
import tracker.domain.PlanRows.RowStep;

class PlanRowsTest {
    private static final StepWeights W = StepWeights.defaults();

    @Test
    void sharesFollowStepWeights() {
        RowStep fix = new RowStep(StepKind.FIX_BAL, "1:fixb", 4, null);
        assertThat(PlanRows.shares(fix, "an", "minh", W)).isEqualTo(List.of(new PlanShare("an", 0.7), new PlanShare("minh", 0.3)));
        assertThat(PlanRows.shares(fix, "an", "an", W)).isEqualTo(List.of(new PlanShare("an", 1.0)));
        assertThat(PlanRows.shares(new RowStep(StepKind.JM_REVIEW, "1:jmr", 1, null), "an", "minh", W)).isEmpty();
        assertThat(PlanRows.shares(new RowStep(null, "1", 4, null), "an", null, W)).isEqualTo(List.of(new PlanShare("an", 1.0)));
    }

    @Test
    void sharesOfOnePersonWhoseBaShareIsZero() {
        StepWeights w = new StepWeights(StepWeights.defaults().weights(), null, java.util.Map.of(StepKind.FIX_BAL, new StepWeights.Share(0, 100)));
        RowStep fix = new RowStep(StepKind.FIX_BAL, "1:fixb", 4, null);
        assertThat(PlanRows.shares(fix, "an", "an", w)).isEqualTo(List.of(new PlanShare("an", 1.0)));
    }

    @Test
    void chainIgnoresDatesSetByHandBeforeTheMatrix() {
        // dates an old Detail plan set on a step: the matrix's cells replaced them, so they steer nothing
        StepEdit old = new StepEdit(1, StepKind.CREATE, null, "2026-11-02", "2026-11-03", false);
        StepEdit oldJm = new StepEdit(1, StepKind.JM_REVIEW, null, "2026-11-04", "2026-11-05", false);
        DetailScheduler.Chain c = PlanRows.chain("ITA", List.of(new RowStep(StepKind.CREATE, "1:create", 8, old),
                new RowStep(StepKind.JM_REVIEW, "1:jmr", 1, oldJm)), "an", "minh", "tanaka", W, "2026-10-12");
        assertThat(c).isNotNull();
        assertThat(c.steps()).hasSize(2).allMatch(st -> st.fixedStart() == null && st.fixedEnd() == null);
    }
}
