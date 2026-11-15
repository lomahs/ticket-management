package tracker.adapters.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.annotation.DirtiesContext;

import tracker.DemoTest;
import tracker.application.ports.EstimationRepository;
import tracker.application.ports.PlanCellRepository;
import tracker.application.ports.PlanCellRepository.Cell;
import tracker.application.ports.PlanCellRepository.JiraEdit;
import tracker.application.ports.PlanVersionRepository;
import tracker.application.service.Baselines;
import tracker.domain.Baseline;
import tracker.domain.EstimationRow;

@DemoTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class PlanCellRepositoryTest {
    static final String EPIC = "PAY-1100";

    @Autowired PlanCellRepository cells;
    @Autowired PlanVersionRepository versions;
    @Autowired EstimationRepository estimation;

    long newRow() {
        estimation.save(EPIC, List.of(new EstimationRow(0, EPIC, 0, "ITA", "Create test case", "Login", 5, 8, null, null, null, null, null)));
        return estimation.findByEpic(EPIC).get(0).id();
    }

    @Test
    void rowCellsUpsertAndZeroDeletes() {
        long id = newRow();
        cells.saveRowCells(List.of(new Cell(id + ":create", "2026-10-12", 3, false), new Cell(id + ":create", "2026-10-13", 2, true)));
        cells.saveRowCells(List.of(new Cell(id + ":create", "2026-10-12", 0, false)));
        assertThat(cells.rowCells(EPIC)).containsExactly(new Cell(id + ":create", "2026-10-13", 2.0, true));
    }

    @Test
    void jiraCellsKeepAZeroHourRow() {
        cells.saveJiraCells(EPIC, List.of(new Cell("PAY-1201", "2026-10-08", 4, false), new Cell("PAY-1201", "2026-10-09", 2, false)));
        cells.saveJiraCells(EPIC, List.of(new Cell("PAY-1201", "2026-10-08", 0, false)));
        assertThat(cells.jiraCells(EPIC).get("PAY-1201")).containsExactly(new Cell("PAY-1201", "2026-10-08", 0.0, false),
                new Cell("PAY-1201", "2026-10-09", 2.0, false));
        cells.deleteJira("PAY-1201");
        assertThat(cells.jiraCells(EPIC)).isEmpty();
    }

    @Test
    void deleteRowCellsAutoOnlyKeepsTyped() {
        long id = newRow();
        Cell typed = new Cell(id + ":create", "2026-10-12", 3, false);
        cells.saveRowCells(List.of(typed, new Cell(id + ":create", "2026-10-13", 2, true)));
        cells.deleteRowCells(EPIC, true);
        assertThat(cells.rowCells(EPIC)).containsExactly(typed);
        cells.deleteRowCells(EPIC, false);
        assertThat(cells.rowCells(EPIC)).isEmpty();
    }

    @Test
    void rowCellsGoWithTheirRow() {
        long id = newRow();
        cells.saveRowCells(List.of(new Cell(id + ":create", "2026-10-12", 3, false)));
        estimation.save(EPIC, List.of());
        assertThat(cells.rowCells(EPIC)).isEmpty();
    }

    @Test
    void jiraEditsAndCells() {
        Cell cell = new Cell("PAY-1201", "2026-10-08", 4, false);
        cells.saveJiraEdit(EPIC, new JiraEdit("PAY-1201", 7.0, null));
        cells.saveJiraCells(EPIC, List.of(cell));
        assertThat(cells.jiraEdits(EPIC)).containsExactly(new JiraEdit("PAY-1201", 7.0, null));
        assertThat(cells.jiraCells(EPIC)).isEqualTo(Map.of("PAY-1201", List.of(cell)));
        cells.deleteJira("PAY-1201");
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
        assertThat(cells.jiraCells(EPIC)).isEmpty();
        cells.saveJiraEdit(EPIC, new JiraEdit("PAY-1201", null, null));
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
    }

    @Test
    void deleteJiraAllAutoOnlyKeepsEdits() {
        cells.saveJiraEdit(EPIC, new JiraEdit("PAY-1201", 7.0, null));
        cells.saveJiraCells(EPIC, List.of(new Cell("PAY-1201", "2026-10-08", 4, false), new Cell("PAY-1201", "2026-10-09", 1, true)));
        cells.deleteJiraAll(EPIC, true);
        assertThat(cells.jiraCells(EPIC).get("PAY-1201")).containsExactly(new Cell("PAY-1201", "2026-10-08", 4.0, false));
        assertThat(cells.jiraEdits(EPIC)).hasSize(1);
        cells.deleteJiraAll(EPIC, false);
        assertThat(cells.jiraCells(EPIC)).isEmpty();
        assertThat(cells.jiraEdits(EPIC)).isEmpty();
    }

    @Test
    void versionsKeepTheLastTime() {
        versions.add(EPIC, "2026-10-07T09:00:00Z", "Minh", "{}", false);
        versions.add(EPIC, "2026-10-07T10:00:00Z", "Minh", "{}", false);
        assertThat(versions.lastSavedAt(EPIC)).isEqualTo("2026-10-07T10:00:00Z");
        assertThat(versions.lastSavedAt("PAY-9999")).isNull();
    }

    @Test
    void theBaselineIsTheFirstVersionWithEstimates() {
        versions.add(EPIC, "2026-10-07T09:00:00Z", "Minh", "{\"v\":1}", false);
        versions.add(EPIC, "2026-10-08T09:00:00Z", "Minh", "{\"v\":2}", true);
        versions.add(EPIC, "2026-10-09T09:00:00Z", "Minh", "{\"v\":3}", true);
        assertThat(versions.baseline(EPIC)).contains(new PlanVersionRepository.SavedVersion("2026-10-08T09:00:00Z", "{\"v\":2}"));
        assertThat(versions.baseline("PAY-9999")).isEmpty();
    }

    @Test
    void aBaselineReadsBackItsSubtasks() {
        List<Baseline.Item> items = List.of(new Baseline.Item("PAY-1", 8, "PAY-10", "an", Map.of("2026-10-01", 4.0, "2026-10-02", 4.0)),
                new Baseline.Item("PAY-2", 3, null, "", Map.of()));
        String body = Baselines.body(Map.of("rows", List.of()), items);
        assertThat(Baselines.of(Optional.of(new PlanVersionRepository.SavedVersion("t", body))))
                .contains(new Baseline("t", items));
        assertThat(Baselines.of(Optional.empty())).isEmpty();
    }
}
