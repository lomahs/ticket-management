package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static tracker.domain.Fixtures.MEMBERS;
import static tracker.domain.Fixtures.issue;

import java.util.List;

import org.junit.jupiter.api.Test;

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
    void planOwnerByStepRole() {
        List<Issue> cells = FlagsTest.five("F1", "WNNNN");
        Issue create = issue().key("PAY-F10").fid("F1").step(StepKind.CREATE).assignee("pim").reporter("minh")
                .events(Fixtures.assigned("2026-10-01", "an", "pim")).build();
        cells.set(0, create);
        Function fn = Flags.buildFunctions(cells).get(0);
        assertThat(cells).extracting(c -> Effort.planOwner(fn, c, MEMBERS)).containsExactly("an", "minh", "an", null, "an");
        Issue single = issue().key("PAY-9").assignee("chi").single("Study").build();
        assertThat(Effort.planOwner(null, single, MEMBERS)).isEqualTo("chi");
    }
}
