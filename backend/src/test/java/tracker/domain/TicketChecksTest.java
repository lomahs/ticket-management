package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.issue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

class TicketChecksTest {
    @Test
    void aWellFormedStepOrSingleTicketHasNoProblem() {
        assertThat(TicketChecks.of(issue().fid("F01").step(StepKind.CREATE).build(), true, false)).isEmpty();
        assertThat(TicketChecks.of(issue().single("Requirement study").build(), true, false)).isEmpty();
    }

    @Test
    void anOpenSubTaskMissingEverythingHasEveryPlanProblem() {
        Issue bare = issue().ps(null).pe(null).assignee(null).est(0).labels().build();
        assertThat(TicketChecks.of(bare, false, false)).containsExactly(TicketChecks.RULE, TicketChecks.NO_END, TicketChecks.NO_START,
                TicketChecks.NO_ASSIGNEE, TicketChecks.NO_LABELS, TicketChecks.NO_ESTIMATE);
        assertThat(TicketChecks.of(issue().single("X").build(), false, false)).containsExactly(TicketChecks.NOT_MEMBER);
    }

    @Test
    void aClosedSubTaskIsJudgedOnItsLabelsAndLoggedHoursOnly() {
        Issue closed = issue().single("X").status(Status.CLOSED).ps(null).assignee(null).est(0).build();
        assertThat(TicketChecks.of(closed, false, false)).containsExactly(TicketChecks.CLOSED_NO_LOG);
        assertThat(TicketChecks.of(issue().single("X").status(Status.CLOSED).logged(2).build(), true, false)).isEmpty();
    }

    @Test
    void aStepThatStartedBeforeAnEarlierOneWasDoneIsOutOfOrder() {
        Issue create = issue().key("PAY-1").fid("F01").step(StepKind.CREATE).status(Status.WIP).build();
        Issue review = issue().key("PAY-2").fid("F01").step(StepKind.BAL_REVIEW).status(Status.WIP).build();
        Issue fix = issue().key("PAY-3").fid("F01").step(StepKind.FIX_BAL).status(Status.NYS).build();
        Function f = new Function("PAY-1100", "PAY-10", "F01", "Fn", Arrays.asList(create, review, fix, null, null));
        assertThat(TicketChecks.outOfOrder(f)).containsExactly("PAY-2");
        Issue resolved = issue().key("PAY-1").fid("F01").step(StepKind.CREATE).status(Status.RESOLVED).build();
        assertThat(TicketChecks.outOfOrder(new Function("PAY-1100", "PAY-10", "F01", "Fn", Arrays.asList(resolved, review, fix, null, null))))
                .isEmpty();
        assertThat(TicketChecks.of(review, true, true)).isEqualTo(List.of(TicketChecks.STEP_ORDER));
    }
}
