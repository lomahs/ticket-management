package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
