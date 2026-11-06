package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import java.util.List;

import org.junit.jupiter.api.Test;

import tracker.domain.Capacity.PlanItem;

class PlanMatrixTest {
    static final WorkCalendar CAL = SchedulingTest.CAL;

    static PlanMatrix.Item item(String task, String fn, String key, String member, String start, String end, double h) {
        return new PlanMatrix.Item("ITA", task, "ITA " + task, fn, "Fn " + fn, new PlanItem(key, member, start, end, h));
    }

    @Test
    void oneRowPerFunctionAndPersonAddsUpTheirStepsDayByDay() {
        PlanMatrix.PlanGrid m = PlanMatrix.compute(List.of(
                item("PAY-10", "F1", "PAY-1", "an", "2026-10-05", "2026-10-06", 4),       // Create, the BA's share
                item("PAY-10", "F1", "PAY-1", "minh", "2026-10-05", "2026-10-06", 2),     // Create, the BAL's share
                item("PAY-10", "F1", "PAY-2", "an", "2026-10-06", "2026-10-07", 6),       // Fix comment, the BA again
                item("PAY-10", "F2", "PAY-3", "an", "2026-10-07", "2026-10-07", 1)), CAL);
        assertThat(m.days()).containsExactly("2026-10-05", "2026-10-06", "2026-10-07");
        assertThat(m.rows()).extracting(PlanMatrix.PlanRow::function, PlanMatrix.PlanRow::memberId)
                .containsExactly(tuple("Fn F1", "an"), tuple("Fn F1", "minh"),
                        tuple("Fn F2", "an"));
        assertThat(m.rows().get(0).hours()).containsExactly(2.0, 5.0, 3.0);
        assertThat(m.rows().get(0).totalH()).isEqualTo(10);
        assertThat(m.rows().get(1).hours()).containsExactly(1.0, 1.0, 0.0);
        assertThat(m.rows().get(2).hours()).containsExactly(0.0, 0.0, 1.0);
    }

    @Test
    void eachPersonsShareSkipsTheirOwnDaysOffAndOnlyShownDaysAreColumns() {
        // Friday 10/09 chi is on leave, an is not; Saturday 10/10 is an extra working day, Sunday 10/11 is not shown
        PlanMatrix.PlanGrid m = PlanMatrix.compute(List.of(
                item("PAY-10", "F1", "PAY-1", "an", "2026-10-08", "2026-10-12", 8),
                item("PAY-10", "F1", "PAY-1", "chi", "2026-10-08", "2026-10-12", 6)), CAL);
        assertThat(m.days()).containsExactly("2026-10-08", "2026-10-09", "2026-10-10", "2026-10-12");
        assertThat(m.rows().get(0).hours()).containsExactly(2.0, 2.0, 2.0, 2.0);
        assertThat(m.rows().get(1).hours()).containsExactly(2.0, 0.0, 2.0, 2.0);
    }

    @Test
    void nothingPlannedIsAnEmptyMatrix() {
        assertThat(PlanMatrix.compute(List.of(), CAL)).isEqualTo(new PlanMatrix.PlanGrid(List.of(), List.of()));
    }
}
