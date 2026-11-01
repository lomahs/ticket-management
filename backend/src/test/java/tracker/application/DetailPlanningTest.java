package tracker.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.adapters.fake.Dataset;
import tracker.application.AppError.Invalid;
import tracker.application.ports.EstimationRepository;
import tracker.application.service.EstimationService;
import tracker.application.service.EstimationService.EstimationView;
import tracker.application.service.EstimationService.PhaseEffort;
import tracker.domain.AnkenScope;
import tracker.domain.EstimationRow;

/** Estimation → master schedule → detail plan → re-plan, on fresh demo data. */
@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class DetailPlanningTest {
    static final AnkenScope ALL = new AnkenScope(Set.copyOf(Dataset.TRACKED_EPICS));
    static final String EPIC = "PAY-1100";

    @Autowired
    EstimationService estimation;
    @Autowired
    EstimationRepository estimationRows;

    static EstimationRow row(long id, int pos, String phase, String subphase, String fn, Integer steps, double effort) {
        return new EstimationRow(id, EPIC, pos, phase, subphase, fn, steps, effort, null, null, null, null);
    }

    @Test
    void estimationTotalsPerPhaseUseWeightsWithoutJmReview() {
        EstimationView v = estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 5, 8),
                row(0, 1, "ITA", "Execute", "Run", null, 16), row(0, 2, "ITB", "Create test case", "Pay", 3, 8)));
        assertThat(v.rows()).hasSize(3);
        assertThat(v.phases()).containsExactly(new PhaseEffort("ITA", 30.0), new PhaseEffort("ITB", 12.5));
        assertThat(estimation.view(ALL, EPIC).phases()).isEqualTo(v.phases());
    }

    @Test
    void estimationRefusesStepsFour() {
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 4, 8))))
                .isInstanceOf(Invalid.class).hasMessageContaining("3 or 5").extracting("field").isEqualTo("rows.0.steps");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "", "Login", 5, 8))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("rows.0.subphase");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Execute", "Run", null, 0))))
                .isInstanceOf(Invalid.class).extracting("field").isEqualTo("rows.0.effort_h");
    }

    @Test
    void estimationKeepsPushedRowsOutOfReach() {
        EstimationRow saved = estimation.save(ALL, EPIC, List.of(row(0, 0, "ITA", "Create test case", "Login", 5, 8))).rows().get(0);
        estimationRows.markPushed(List.of(saved.id()), "2026-10-07T09:00:00Z");
        assertThatThrownBy(() -> estimation.save(ALL, EPIC, List.of(row(saved.id(), 0, "ITA", "Create test case", "Login 2", 5, 8))))
                .isInstanceOf(Invalid.class).hasMessageContaining("pushed");
        EstimationView v = estimation.save(ALL, EPIC, List.of(row(0, 1, "ITA", "Execute", "Run", null, 16)));
        assertThat(v.rows()).extracting(EstimationRow::functionName).containsExactly("Login", "Run");
    }
}
