package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MasterScheduleTest {
    static final Map<String, Member> M = Map.of("an", new Member("an", "An", "an", Role.BA, "VN", "#000"),
            "binh", new Member("binh", "Binh", "binh", Role.BA, "VN", "#000"));
    static final Allocations A = new Allocations(List.of(new Allocation("an", "E", "2026-10-01", 6), new Allocation("binh", "E", "2026-10-01", 4)));
    static final WorkCalendar CAL = cal();

    static WorkCalendar cal(Leave... leaves) {
        return new WorkCalendar(List.of(1, 2, 3, 4, 5), Map.of(), Map.of(), M, List.of(leaves));
    }

    @Test
    void endWalksWorkingDaysUntilEffortIsUsed() { // 10h/day: Thu 08, Fri 09, (weekend), Mon 12
        assertThat(MasterSchedule.endOf("E", "2026-10-08", 30, List.of("an", "binh"), A, CAL)).isEqualTo(new MasterSchedule.PhaseEnd("2026-10-12", 3));
    }

    @Test
    void halfDayLeaveGivesHalfTheAllocation() {
        assertThat(MasterSchedule.hoursOn("an", "E", "2026-10-08", A, cal(new Leave(1, "an", "2026-10-08", LeaveType.HALF, "")))).isEqualTo(3.0);
        assertThat(MasterSchedule.hoursOn("an", "E", "2026-10-10", A, CAL)).isEqualTo(0.0); // Saturday
        assertThat(MasterSchedule.hoursOn("an", "E", "2026-09-30", A, CAL)).isEqualTo(0.0); // before the allocation
    }

    @Test
    void budgetSkipsLeave() {
        assertThat(MasterSchedule.budget("an", "E", "2026-10-05", "2026-10-09", A, cal(new Leave(1, "an", "2026-10-07", LeaveType.FULL, ""))))
                .isEqualTo(24.0);
    }

    @Test
    void leaveOfEveryMemberDoesNotCountAsADay() {
        WorkCalendar off = cal(new Leave(1, "an", "2026-10-09", LeaveType.FULL, ""), new Leave(2, "binh", "2026-10-09", LeaveType.FULL, ""));
        assertThat(MasterSchedule.endOf("E", "2026-10-08", 30, List.of("an", "binh"), A, off)).isEqualTo(new MasterSchedule.PhaseEnd("2026-10-13", 3));
    }

    @Test
    void workIsWhatTheBasDo() { // Create 8 + 70 % of Fix BAL 2.5 and Fix JM 1.5 (the reviews aside); a 2h JM Review spans one 8h day
        List<EstimationRow> rows = List.of(row("P", 5, 8), row("P", null, 6), row("Q", 3, 8));
        Map<String, MasterSchedule.PhaseWork> w = MasterSchedule.work(rows, StepWeights.defaults(), 8);
        assertThat(w.get("P").effortH()).isCloseTo(10.8 + 6, within(1e-9));
        assertThat(w.get("P")).extracting(MasterSchedule.PhaseWork::beforeReviewH, MasterSchedule.PhaseWork::tailDays).containsExactly(14.0, 4);
        assertThat(w.get("Q").effortH()).isCloseTo(9.75, within(1e-9));
        assertThat(w.get("Q").tailDays()).isEqualTo(2);
    }

    @Test
    void removedStepsAndEditedHoursChangeTheWork() { // row 7: Fix JM removed, Fix BAL 4h instead of 2.5h
        List<EstimationRow> rows = List.of(new EstimationRow(7, "E", 0, "P", "", "f", 5, 8, null, null, null, null, null));
        List<StepEdit> edits = List.of(new StepEdit(7, StepKind.FIX_JM, null, null, null, true), new StepEdit(7, StepKind.FIX_BAL, 4.0, null, null, false));
        MasterSchedule.PhaseWork w = MasterSchedule.work(rows, edits, StepWeights.defaults(), 8).get("P");
        assertThat(w.effortH()).isCloseTo(8 + 2.8, within(1e-9));
        assertThat(w.tailDays()).isEqualTo(3);
    }

    static EstimationRow row(String phase, Integer steps, double effort) {
        return new EstimationRow(0, "E", 0, phase, "", "f", steps, effort, null, null, null, null, null);
    }

    @Test
    void endWaitsForTheLongestReviewChainAfterTheCreates() {
        // 10h/day: the Creates (8h) end Thu 08, then BAL Review, Fix BAL, JM Review, Fix JM take Fri 09, Mon 12, Tue 13, Wed 14
        assertThat(MasterSchedule.endOf("E", "2026-10-08", new MasterSchedule.PhaseWork(12, 8, 4), List.of("an", "binh"), A, CAL))
                .isEqualTo(new MasterSchedule.PhaseEnd("2026-10-14", 5));
        // 60h of BA work end Thu 15, after the chain (Creates Thu 08 + 2 days = Mon 12)
        assertThat(MasterSchedule.endOf("E", "2026-10-08", new MasterSchedule.PhaseWork(60, 10, 2), List.of("an", "binh"), A, CAL))
                .isEqualTo(new MasterSchedule.PhaseEnd("2026-10-15", 6));
    }

    @Test
    void effortZeroEndsOnStart() {
        assertThat(MasterSchedule.endOf("E", "2026-10-08", 0, List.of("an"), A, CAL)).isEqualTo(new MasterSchedule.PhaseEnd("2026-10-08", 0));
    }

    @Test
    void memberWithoutAllocationIsAnError() {
        assertThatThrownBy(() -> MasterSchedule.endOf("E", "2026-10-08", 8, List.of("zz"), A, CAL)).isInstanceOf(RuleError.class)
                .hasMessageContaining("No member");
        assertThatThrownBy(() -> MasterSchedule.endOf("E", "2026-10-08", 8, List.of(), A, CAL)).isInstanceOf(RuleError.class);
    }
}
