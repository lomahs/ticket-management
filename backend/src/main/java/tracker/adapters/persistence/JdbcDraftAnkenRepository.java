package tracker.adapters.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.DraftAnkenRepository;
import tracker.domain.DraftAnken;

@Repository
class JdbcDraftAnkenRepository implements DraftAnkenRepository {
    /** The tables keyed by epic whose rows move as they are; anken_members and allocations may collide and are merged. */
    private static final List<String> TABLES = List.of("estimation_rows", "master_schedule_versions", "estimation_plan_pending",
            "plan_jira_cells", "plan_jira_edits", "plan_versions");

    private final JdbcClient jdbc;

    JdbcDraftAnkenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private int run(String sql, String draft, String epic) {
        return jdbc.sql(sql).param("draft", draft).param("epic", epic).update();
    }

    @Override
    public String nextKey() {
        String like = DraftAnken.KEY_PREFIX + "%";
        int from = DraftAnken.KEY_PREFIX.length() + 1;
        Integer max = jdbc.sql("""
                SELECT MAX(CAST(SUBSTR(epic_key, :from) AS INTEGER)) FROM (
                    SELECT epic_key FROM onboarding UNION ALL SELECT epic_key FROM estimation_rows
                    UNION ALL SELECT epic_key FROM anken_members UNION ALL SELECT epic_key FROM allocations
                ) WHERE epic_key LIKE :like
                """).param("from", from).param("like", like).query(Integer.class).optional().orElse(null);
        return DraftAnken.key(max == null ? 1 : max + 1);
    }

    @Override
    @Transactional
    public void moveTo(String draft, String epic) {
        for (String t : TABLES) {
            run("UPDATE " + t + " SET epic_key = :epic WHERE epic_key = :draft", draft, epic);
        }
        run("INSERT OR IGNORE INTO anken_members (epic_key, member_id, added_at, added_by) "
                + "SELECT :epic, member_id, added_at, added_by FROM anken_members WHERE epic_key = :draft", draft, epic);
        run("INSERT OR IGNORE INTO allocations (member_id, epic_key, effective_from, hours_per_day, set_by, set_at) "
                + "SELECT member_id, :epic, effective_from, hours_per_day, set_by, set_at FROM allocations WHERE epic_key = :draft", draft, epic);
        run("UPDATE onboarding SET epic_key = :epic, draft_title = NULL WHERE epic_key = :draft", draft, epic);
        delete(draft);
    }

    @Override
    @Transactional
    public void delete(String draft) {
        // the rows' steps, picks and cells go with them (ON DELETE CASCADE)
        run("DELETE FROM master_schedule_phases WHERE version_id IN (SELECT id FROM master_schedule_versions WHERE epic_key = :draft)", draft, "");
        for (String t : TABLES) {
            run("DELETE FROM " + t + " WHERE epic_key = :draft", draft, "");
        }
        run("DELETE FROM anken_members WHERE epic_key = :draft", draft, "");
        run("DELETE FROM allocations WHERE epic_key = :draft", draft, "");
        run("DELETE FROM onboarding WHERE epic_key = :draft", draft, "");
    }
}
