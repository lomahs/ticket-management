package tracker.application.ports;

import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/** The Detail plan's hours per step and day, and the Jira changes not pushed yet. */
public interface PlanCellRepository {
    /** @param auto filled by Auto allocate, not typed */
    record Cell(String ref, String day, double hours, boolean auto) {}

    /** A Jira row's changes not pushed yet; a null field keeps Jira's value. */
    record JiraEdit(String issueKey, @Nullable Double remainingH, @Nullable String assigneeId) {}

    /** Cells of the epic's unpushed rows. */
    List<Cell> rowCells(String epic);

    /** Upsert by (ref, day); hours <= 0 deletes. The row id is the part of the ref before ':'. */
    void saveRowCells(List<Cell> cells);

    void deleteRowCells(String epic, boolean autoOnly);

    /** Issue key → cells (Cell.ref = the key). */
    Map<String, List<Cell>> jiraCells(String epic);

    /** Upsert by (key, day); hours <= 0 deletes. */
    void saveJiraCells(String epic, List<Cell> cells);

    List<JiraEdit> jiraEdits(String epic);

    /** Replaces the edit; an edit with both fields null is deleted. */
    void saveJiraEdit(String epic, JiraEdit edit);

    /** Its cells and edit (after a push). */
    void deleteJira(String issueKey);

    /** @param autoOnly only the auto cells go, edits stay */
    void deleteJiraAll(String epic, boolean autoOnly);
}
