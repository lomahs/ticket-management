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
}
