package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.MEMBERS;
import static tracker.domain.Fixtures.issue;

import java.util.List;

import org.junit.jupiter.api.Test;

import tracker.domain.Effort.Place;
import tracker.domain.Effort.WorkKind;

class EffortTest {

    @Test
    void kindsFollowTheTable() {
        assertThat(Effort.kindOf(StepKind.CREATE, Role.BA)).isEqualTo(WorkKind.CREATE);
        assertThat(Effort.kindOf(StepKind.FIX_BAL, Role.BA)).isEqualTo(WorkKind.FIX);
        assertThat(Effort.kindOf(StepKind.FIX_JM, Role.BA)).isEqualTo(WorkKind.FIX);
        assertThat(Effort.kindOf(StepKind.FIX_JM, Role.BAL)).isEqualTo(WorkKind.REVIEW);
        assertThat(Effort.kindOf(StepKind.FIX_BAL, Role.BAL)).isEqualTo(WorkKind.REVIEW);
        assertThat(Effort.kindOf(StepKind.BAL_REVIEW, Role.BAL)).isEqualTo(WorkKind.REVIEW);
        assertThat(Effort.kindOf(StepKind.BAL_REVIEW, Role.BA)).isEqualTo(WorkKind.INVALID);
        assertThat(Effort.kindOf(StepKind.CREATE, Role.BAL)).isEqualTo(WorkKind.INVALID);
        assertThat(Effort.kindOf(StepKind.JM_REVIEW, Role.BA)).isEqualTo(WorkKind.INVALID);
        assertThat(Effort.kindOf(StepKind.JM_REVIEW, Role.BAL)).isEqualTo(WorkKind.INVALID);
        assertThat(Effort.kindOf(StepKind.CREATE, Role.PIM)).isEqualTo(WorkKind.INVALID);
        assertThat(Effort.kindOf(StepKind.CREATE, null)).isEqualTo(WorkKind.INVALID);
    }

    @Test
    void jmWorklogsAreDropped() {
        Issue i = issue().log("2026-10-01", "an", 3).log("2026-10-02", "tanaka", 2).log("2026-10-02", "ghost", 1).build();
        Issue kept = Effort.withoutJm(i, MEMBERS);
        assertThat(kept.loggedH()).isEqualTo(4); // an's and an unknown author's hours stay
        assertThat(kept.worklogs()).extracting(Worklog::memberId).containsExactly("an", "ghost");
    }

    @Test
    void handoverMovesTheRemainingPlan() {
        Issue create = issue().key("A1").fid("F1").step(StepKind.CREATE).ps("2026-10-05").pe("2026-10-09").est(8).remaining(5).build();
        assertThat(Effort.planItem(create, "binh", "2026-10-07"))
                .isEqualTo(new Capacity.PlanItem("A1", "binh", "2026-10-07", "2026-10-09", 5));
        assertThat(Effort.planItem(create, "binh", "2026-10-12"))
                .isEqualTo(new Capacity.PlanItem("A1", "binh", "2026-10-09", "2026-10-09", 5));
        assertThat(Effort.planItem(create, "binh", "2026-10-05"))
                .isEqualTo(new Capacity.PlanItem("A1", "binh", "2026-10-05", "2026-10-09", 8)); // handed over before it began
        assertThat(Effort.planItem(create, "an", null)).isEqualTo(new Capacity.PlanItem("A1", "an", "2026-10-05", "2026-10-09", 8));
        assertThat(Effort.planItem(create, null, null)).isNull();
        assertThat(Effort.planItem(issue().ps(null).build(), "an", null)).isNull();
    }

    @Test
    void planOwnerByStepRole() {
        List<Issue> cells = FlagsTest.five("F1", "WNNNN");
        Issue create = issue().key("PAY-F10").fid("F1").step(StepKind.CREATE).assignee("pim").reporter("minh")
                .events(Fixtures.assigned("2026-10-01", "an", "pim")).build();
        cells.set(0, create);
        Function fn = Flags.buildFunctions(cells).get(0);
        assertThat(cells).extracting(c -> Effort.planOwner(fn, c, MEMBERS)).containsExactly("an", "minh", "an", null, "an");
        Issue single = issue().key("PAY-9").assignee("chi").single("Study").build();
        assertThat(Effort.planOwner(null, single, MEMBERS)).isEqualTo("chi");
        Issue question = issue().key("PAY-8").assignee("tanaka").build(); // a QnA waiting for the client
        assertThat(Effort.planOwner(null, question, MEMBERS)).isNull();
    }

    @Test
    void myWorkPlacesStepsByRole() {
        // F1: BA an (Create's assignee), BAL minh (reporter); the Fix BAL sits with minh for a second review round
        List<Issue> cells = FlagsTest.five("F1", "CCWNN");
        cells.set(0, issue().key("PAY-F10").fid("F1").step(StepKind.CREATE).assignee("an").reporter("minh").status(Status.CLOSED).build());
        cells.set(2, issue().key("PAY-F12").fid("F1").step(StepKind.FIX_BAL).assignee("minh").build());
        Function fn = Flags.buildFunctions(cells).get(0);
        assertThat(cells).extracting(c -> Effort.place(fn, c, "an", MEMBERS))
                .containsExactly(Place.MINE, Place.RELATED, Place.RELATED, Place.RELATED, Place.MINE);
        assertThat(cells).extracting(c -> Effort.place(fn, c, "minh", MEMBERS))
                .containsExactly(Place.RELATED, Place.MINE, Place.MINE, Place.RELATED, Place.RELATED);
    }

    @Test
    void myWorkHoldsOnlyTheStepsOfOwnFunctions() {
        Issue create = issue().key("PAY-F10").fid("F1").step(StepKind.CREATE).assignee("an").build();
        Function fn = Flags.buildFunctions(List.of(create)).get(0);
        assertThat(Effort.place(fn, create, "binh", MEMBERS)).isNull(); // binh is not F1's BA
        assertThat(Effort.place(fn, create, "pim", MEMBERS)).isNull(); // PIM: not by step role
    }
}
