package tracker.adapters.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tracker.adapters.web.EstimationFiles.EstimationImportRow;

class EstimationFilesTest {
    @Test
    void flagsEachBadRowAndKeepsGoodOnes() {
        List<EstimationImportRow> rows = EstimationFiles.parse("e.csv", ("Task,Function,Steps,Effort (h)\n"
                + "BARO-13,Login,5,8\nbaro-14,Run,,16\nBARO-13,Pay,4,8\n"
                + "BARO-13,,5,8\nBARO-13,Cart,5,abc\nBARO-13,Bill,5,0\n"
                + "BARO-13,Ship,3,\"8,5\"\n,Ask,,1\nBARO-99,Ask,,1\n").getBytes(UTF_8), Set.of("BARO-13", "BARO-14"));
        assertThat(rows).extracting(EstimationImportRow::errors).containsExactly(List.of(), List.of(),
                List.of("Steps must be 3, 5 or empty"), List.of("Function is required"), List.of("Effort must be a number of hours"),
                List.of("Effort must be more than 0 hours"), List.of(), List.of("Task is required"),
                List.of("BARO-99 is not a Task of the anken that matches the Task pattern"));
        assertThat(rows.get(0)).isEqualTo(new EstimationImportRow(2, "BARO-13", "Login", 5, 8.0, List.of()));
        assertThat(rows.get(1).task()).isEqualTo("BARO-14");
        assertThat(rows.get(1).steps()).isNull();
        assertThat(rows.get(6).effortH()).isEqualTo(8.5);
    }

    @Test
    void templateHasTheFixedHeader() {
        assertThat(EstimationFiles.TEMPLATE_CSV).contains("Task,Function,Steps,Effort (h)");
        assertThat(EstimationFiles.parse("t.csv", EstimationFiles.TEMPLATE_CSV.getBytes(UTF_8), Set.of("BARO-13", "BARO-14")))
                .allMatch(r -> r.errors().isEmpty());
    }
}
