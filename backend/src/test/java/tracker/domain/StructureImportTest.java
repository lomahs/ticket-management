package tracker.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import tracker.domain.StructureImport.StructureImportRow;

class StructureImportTest {
    @Test
    void taskNameBecomesTheRuleWithStarsForTheAnkenPrefixAndLocalTask() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("ITA", "ITA Prepare", "ITAP", "Task", "5 steps")), TaskNames.DEFAULT_RULE);
        assertThat(rows).containsExactly(new StructureImportRow(2, "ITA", "ITA Prepare", "[*][*] ITA Prepare", "ITAP", "", true, List.of()));
        TaskTemplate t = new TaskTemplate(rows.get(0).match(), "ITA", "ITAP");
        assertThat(TaskNames.subPhase(t)).isEqualTo("ITA Prepare");
        WorkStructure ws = new WorkStructure(List.of("ITA"), List.of(t));
        assertThat(ws.taskTemplate("[BookUI][LocalTask] ITA Prepare")).isEqualTo(t);
        assertThat(ws.taskTemplate("[BookUI][Client] ITA Prepare")).isEqualTo(t);
        assertThat(TaskNames.recognised(TaskNames.DEFAULT_RULE, "BookUI", t, ws)).isTrue(); // onboarding names
        assertThat(TaskNames.match("[{anken_prefix}][localtask] {sub_phase}", "ITA Execute")).isEqualTo("[*][*] ITA Execute");
        assertThat(StructureImport.rows(List.of(List.of("UC", "QnA  management", "", "QnA")), "[UC][{anken_prefix}] {sub_phase}").get(0).match())
                .isEqualTo("[UC][*] QnA management");
    }

    @Test
    void typeSetsTheWorkflowAndTheWorkflowColumnIsNotRead() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("ITA", "Execute", "", "task", "None"),
                List.of("UC", "QnA", "", "QnA", "5 steps"), List.of("UC", "Work requests", "WR", "Work Request", "")), TaskNames.DEFAULT_RULE);
        assertThat(rows).extracting(StructureImportRow::type).containsExactly("", TaskTemplate.QNA, TaskTemplate.WORK_REQUEST);
        assertThat(rows).extracting(StructureImportRow::workflow).containsExactly(true, false, false);
    }

    @Test
    void bugTypeIsReadAndHasNoWorkflow() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("ITA", "Bugs", "", "Bug")), TaskNames.DEFAULT_RULE);
        assertThat(rows).extracting(StructureImportRow::type).containsExactly("bug");
        assertThat(rows).extracting(StructureImportRow::workflow).containsExactly(false);
    }

    @Test
    void flagsRowsWithoutAPhaseOrATaskName() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("", "Execute", "", "Task"), List.of("ITA", "", "", "Task")),
                TaskNames.DEFAULT_RULE);
        assertThat(rows).extracting(StructureImportRow::errors).containsExactly(List.of("Phase is required"), List.of("Task name is required"));
        assertThat(rows).extracting(StructureImportRow::line).containsExactly(2, 3);
    }

    @Test
    void emptyOrUnknownTypeIsTaskAndPrefixMayBeEmpty() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("ITA", "Execute"), List.of("ITA", "Bugs", "", "Epic")),
                TaskNames.DEFAULT_RULE);
        assertThat(rows).extracting(StructureImportRow::errors).containsExactly(List.of(), List.of());
        assertThat(rows).extracting(StructureImportRow::type).containsExactly("", "");
        assertThat(rows).extracting(StructureImportRow::workflow).containsExactly(true, true);
        assertThat(rows).extracting(StructureImportRow::prefix).containsExactly("", "");
    }
}
