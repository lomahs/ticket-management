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
        assertThat(TaskNames.task(t)).isEqualTo("ITA Prepare");
        WorkStructure ws = new WorkStructure(List.of("ITA"), List.of(t));
        assertThat(ws.taskTemplate("[BookUI][LocalTask] ITA Prepare")).isEqualTo(t);
        assertThat(ws.taskTemplate("[BookUI][Client] ITA Prepare")).isEqualTo(t);
        assertThat(TaskNames.propose(TaskNames.DEFAULT_RULE, "BookUI", t, ws)).isEqualTo("[BookUI][LocalTask] ITA Prepare"); // onboarding names
        assertThat(TaskNames.match("[{anken_prefix}][localtask] {task}", "ITA", "ITA Execute")).isEqualTo("[*][*] ITA Execute");
        assertThat(StructureImport.rows(List.of(List.of("UC", "QnA  management", "", "QnA")), "[{phase}][{anken_prefix}] {task}").get(0).match())
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
    void flagsEachBadRow() {
        List<StructureImportRow> rows = StructureImport.rows(List.of(List.of("", "Execute", "", "Task"), List.of("ITA", "", "", "Task"),
                List.of("ITA", "Bugs", "", "Bug"), List.of("ITA", "Execute")), TaskNames.DEFAULT_RULE);
        assertThat(rows).extracting(StructureImportRow::errors).containsExactly(List.of("Phase is required"), List.of("Task name is required"),
                List.of("Type must be Task, QnA or WorkRequest"), List.of("Type must be Task, QnA or WorkRequest"));
        assertThat(rows).extracting(StructureImportRow::line).containsExactly(2, 3, 4, 5);
    }
}
