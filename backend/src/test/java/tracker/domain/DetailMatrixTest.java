package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tracker.domain.DetailMatrix.Line;

class DetailMatrixTest {
    private static final String TODAY = "2026-10-07";

    @Test
    void newLineCountsEveryCell() {
        Line l = new Line("1:create", "row:1", 0, false, 8, 0, Map.of("2026-10-12", 3.0, "2026-10-13", 3.0));
        assertThat(DetailMatrix.plannedH(l, TODAY)).isEqualTo(6);
        assertThat(DetailMatrix.notYetH(l, TODAY)).isEqualTo(2);
        assertThat(DetailMatrix.start(l)).isEqualTo("2026-10-12");
        assertThat(DetailMatrix.end(l)).isEqualTo("2026-10-13");
    }

    @Test
    void jiraLinePlansOnlyFromToday() {
        Map<String, Double> cells = Map.of("2026-10-06", 3.0, "2026-10-07", 2.0, "2026-10-08", 3.0);
        Line l = new Line("PAY-1201", "PAY-1201", 0, true, 8, 3, cells);
        assertThat(DetailMatrix.plannedH(l, TODAY)).isEqualTo(8);
        assertThat(DetailMatrix.notYetH(l, TODAY)).isEqualTo(0);
        assertThat(DetailMatrix.start(l)).isEqualTo("2026-10-06");
        assertThat(DetailMatrix.notYetH(new Line("PAY-1201", "PAY-1201", 0, true, 10, 3, cells), TODAY)).isEqualTo(2);
        assertThat(DetailMatrix.notYetH(new Line("PAY-1201", "PAY-1201", 0, true, 6, 3, cells), TODAY)).isEqualTo(-2);
    }

    @Test
    void sameDayIsInOrder() {
        List<Line> lines = List.of(
                new Line("1:create", "row:1", 0, false, 8, 0, Map.of("2026-10-12", 4.0, "2026-10-13", 4.0)),
                new Line("1:balr", "row:1", 1, false, 1, 0, Map.of("2026-10-13", 1.0)));
        assertThat(DetailMatrix.outOfOrder(lines)).isEmpty();
    }

    @Test
    void earlierDayIsOutOfOrder() {
        List<Line> lines = List.of(
                new Line("1:create", "row:1", 0, false, 8, 0, Map.of("2026-10-12", 4.0, "2026-10-13", 4.0)),
                new Line("1:balr", "row:1", 1, false, 1, 0, Map.of("2026-10-12", 1.0)));
        assertThat(DetailMatrix.outOfOrder(lines)).isEqualTo(Set.of("1:balr"));
    }

    @Test
    void stepWithoutHoursIsSkipped() {
        List<Line> lines = List.of(
                new Line("1:create", "row:1", 0, false, 4, 0, Map.of("2026-10-12", 4.0)),
                new Line("1:balr", "row:1", 1, false, 0, 0, Map.of()),
                new Line("1:fixb", "row:1", 2, false, 1, 0, Map.of("2026-10-11", 1.0)));
        assertThat(DetailMatrix.outOfOrder(lines)).isEqualTo(Set.of("1:fixb"));
    }

    @Test
    void chainsAreSeparate() {
        List<Line> lines = List.of(
                new Line("1:create", "row:1", 0, false, 4, 0, Map.of("2026-10-12", 4.0)),
                new Line("2:create", "row:2", 0, false, 4, 0, Map.of("2026-10-14", 4.0)),
                new Line("1:balr", "row:1", 1, false, 1, 0, Map.of("2026-10-13", 1.0)),
                new Line("2:balr", "row:2", 1, false, 1, 0, Map.of("2026-10-15", 1.0)));
        assertThat(DetailMatrix.outOfOrder(lines)).isEmpty();
    }

    @Test
    void windowKeepsShownDays() {
        assertThat(DetailMatrix.window("2026-10-12", "2026-10-20", d -> !weekend(d))).containsExactly(
                "2026-10-12", "2026-10-13", "2026-10-14", "2026-10-15", "2026-10-16", "2026-10-19", "2026-10-20");
        assertThat(DetailMatrix.window(null, "2026-10-20", d -> true)).isEmpty();
    }

    private static boolean weekend(String d) {
        DayOfWeek w = LocalDate.parse(d).getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }
}
