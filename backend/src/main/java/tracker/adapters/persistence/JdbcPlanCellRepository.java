package tracker.adapters.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.PlanCellRepository;
import tracker.domain.Numbers;

@Repository
class JdbcPlanCellRepository implements PlanCellRepository {
    private static final String UNPUSHED_ROWS = "SELECT id FROM estimation_rows WHERE epic_key = :epic AND pushed_at IS NULL";

    private final JdbcClient jdbc;

    JdbcPlanCellRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** The row id of a ref like "12:create" or "12" (same as DetailPlanService.rowOf). */
    private static long rowOf(String ref) {
        int i = ref.indexOf(':');
        return Long.parseLong(i < 0 ? ref : ref.substring(0, i));
    }

    @Override
    public List<Cell> rowCells(String epic) {
        return jdbc.sql("SELECT ref, day, hours, auto FROM plan_row_cells WHERE row_id IN (" + UNPUSHED_ROWS + ") ORDER BY ref, day")
                .param("epic", epic).query((rs, n) -> new Cell(rs.getString("ref"), rs.getString("day"), rs.getDouble("hours"),
                        rs.getInt("auto") != 0)).list();
    }

    @Override
    @Transactional
    public void saveRowCells(List<Cell> cells) {
        for (Cell c : cells) {
            jdbc.sql("DELETE FROM plan_row_cells WHERE ref = :ref AND day = :day").param("ref", c.ref()).param("day", c.day()).update();
            double hours = Numbers.hours(c.hours());
            if (hours > 0) {
                jdbc.sql("INSERT INTO plan_row_cells (row_id, ref, day, hours, auto) VALUES (:row, :ref, :day, :hours, :auto)")
                        .param("row", rowOf(c.ref())).param("ref", c.ref()).param("day", c.day()).param("hours", hours)
                        .param("auto", c.auto() ? 1 : 0).update();
            }
        }
    }

    @Override
    public void deleteRowCells(String epic, boolean autoOnly) {
        jdbc.sql("DELETE FROM plan_row_cells WHERE row_id IN (" + UNPUSHED_ROWS + ")" + (autoOnly ? " AND auto = 1" : ""))
                .param("epic", epic).update();
    }

    @Override
    public Map<String, List<Cell>> jiraCells(String epic) {
        Map<String, List<Cell>> out = new LinkedHashMap<>();
        jdbc.sql("SELECT issue_key, day, hours, auto FROM plan_jira_cells WHERE epic_key = :epic ORDER BY issue_key, day")
                .param("epic", epic).query((rs, n) -> new Cell(rs.getString("issue_key"), rs.getString("day"), rs.getDouble("hours"),
                        rs.getInt("auto") != 0)).list().forEach(c -> out.computeIfAbsent(c.ref(), k -> new java.util.ArrayList<>()).add(c));
        return out;
    }

    @Override
    @Transactional
    public void saveJiraCells(String epic, List<Cell> cells) {
        for (Cell c : cells) {
            jdbc.sql("DELETE FROM plan_jira_cells WHERE issue_key = :key AND day = :day").param("key", c.ref()).param("day", c.day())
                    .update();
            double hours = Math.max(0, Numbers.hours(c.hours())); // a 0-hour row stays: the day is cleared, Jira's plan does not return
            jdbc.sql("INSERT INTO plan_jira_cells (epic_key, issue_key, day, hours, auto) VALUES (:epic, :key, :day, :hours, :auto)")
                    .param("epic", epic).param("key", c.ref()).param("day", c.day()).param("hours", hours)
                    .param("auto", c.auto() ? 1 : 0).update();
        }
    }

    @Override
    public List<JiraEdit> jiraEdits(String epic) {
        return jdbc.sql("SELECT issue_key, remaining_h, assignee_id FROM plan_jira_edits WHERE epic_key = :epic ORDER BY issue_key")
                .param("epic", epic).query((rs, n) -> {
                    double remaining = rs.getDouble("remaining_h");
                    Double remainingH = rs.wasNull() ? null : remaining;
                    return new JiraEdit(rs.getString("issue_key"), remainingH, rs.getString("assignee_id"));
                }).list();
    }

    @Override
    @Transactional
    public void saveJiraEdit(String epic, JiraEdit edit) {
        jdbc.sql("DELETE FROM plan_jira_edits WHERE issue_key = :key").param("key", edit.issueKey()).update();
        if (edit.remainingH() != null || edit.assigneeId() != null) {
            Double remaining = edit.remainingH() == null ? null : Numbers.hours(edit.remainingH());
            jdbc.sql("INSERT INTO plan_jira_edits (issue_key, epic_key, remaining_h, assignee_id) VALUES (:key, :epic, :remaining, :assignee)")
                    .param("key", edit.issueKey()).param("epic", epic).param("remaining", remaining)
                    .param("assignee", edit.assigneeId()).update();
        }
    }

    @Override
    @Transactional
    public void deleteJira(String issueKey) {
        jdbc.sql("DELETE FROM plan_jira_cells WHERE issue_key = :key").param("key", issueKey).update();
        jdbc.sql("DELETE FROM plan_jira_edits WHERE issue_key = :key").param("key", issueKey).update();
    }

    @Override
    @Transactional
    public void deleteJiraAll(String epic, boolean autoOnly) {
        jdbc.sql("DELETE FROM plan_jira_cells WHERE epic_key = :epic" + (autoOnly ? " AND auto = 1" : "")).param("epic", epic).update();
        if (!autoOnly) {
            jdbc.sql("DELETE FROM plan_jira_edits WHERE epic_key = :epic").param("epic", epic).update();
        }
    }
}
