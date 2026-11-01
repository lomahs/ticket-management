package tracker.adapters.web;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tracker.adapters.web.EstimationFiles.ImportRow;

class EstimationFilesTest {
    @Test
    void flagsEachBadRowAndKeepsGoodOnes() {
        List<ImportRow> rows = EstimationFiles.parse("e.csv", ("Phase,Subphase,Function,Steps,Effort (h)\n"
                + "ITA,Create test case,Login,5,8\nITA,Execute,Run,,16\nITA,Create test case,Pay,4,8\n"
                + "ITA,Create test case,,5,8\nITA,Create test case,Cart,5,abc\nITA,Create test case,Bill,5,0\n"
                + "ITA,Create test case,Ship,3,\"8,5\"\n").getBytes(UTF_8));
        assertThat(rows).extracting(ImportRow::errors).containsExactly(List.of(), List.of(),
                List.of("Steps must be 3, 5 or empty"), List.of("Function is required"), List.of("Effort must be a number of hours"),
                List.of("Effort must be more than 0 hours"), List.of());
        assertThat(rows.get(0)).isEqualTo(new ImportRow(2, "ITA", "Create test case", "Login", 5, 8.0, List.of()));
        assertThat(rows.get(1).steps()).isNull();
        assertThat(rows.get(6).effortH()).isEqualTo(8.5);
    }

    @Test
    void templateHasTheFixedHeader() {
        assertThat(EstimationFiles.TEMPLATE_CSV).contains("Phase,Subphase,Function,Steps,Effort (h)");
        assertThat(EstimationFiles.parse("t.csv", EstimationFiles.TEMPLATE_CSV.getBytes(UTF_8))).allMatch(r -> r.errors().isEmpty());
    }
}
