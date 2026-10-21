package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class AllocationsTest {
    private final Allocations allocations = new Allocations(List.of(
            new Allocation("b", "X", "2026-10-01", 5),
            new Allocation("b", "Y", "2026-10-01", 3),
            new Allocation("b", "X", "2026-11-02", 0)));

    @Test
    void latestRowNotAfterTheDayWins() {
        assertThat(allocations.hoursOn("b", "X", "2026-10-31")).isEqualTo(5.0);
        assertThat(allocations.hoursOn("b", "X", "2026-11-02")).isEqualTo(0.0);
        assertThat(allocations.hoursOn("b", "X", "2026-09-30")).isNull();
        assertThat(allocations.totalOn("b", "2026-10-15")).isEqualTo(8.0);
        assertThat(allocations.datesOf("b")).containsExactly("2026-10-01", "2026-11-02");
        assertThat(allocations.hasAny("b", "X")).isTrue();
        assertThat(allocations.hasAny("b", "Z")).isFalse();
        assertThat(allocations.hasAny("a", "X")).isFalse();
    }
}
