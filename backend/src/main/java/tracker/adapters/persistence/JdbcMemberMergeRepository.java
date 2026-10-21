package tracker.adapters.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.MemberMergeRepository;

@Repository
class JdbcMemberMergeRepository implements MemberMergeRepository {
    private final JdbcClient jdbc;

    JdbcMemberMergeRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private int run(String sql, String keepId, String removeId) {
        return jdbc.sql(sql).param("keep", keepId).param("remove", removeId).update();
    }

    @Override
    @Transactional
    public void merge(String keepId, String removeId) {
        run("INSERT OR IGNORE INTO anken_members (epic_key, member_id, added_at, added_by) "
                + "SELECT epic_key, :keep, added_at, added_by FROM anken_members WHERE member_id = :remove", keepId, removeId);
        run("DELETE FROM anken_members WHERE member_id = :remove", keepId, removeId);
        run("DELETE FROM leaves WHERE member_id = :remove AND date IN (SELECT date FROM leaves WHERE member_id = :keep)", keepId, removeId);
        run("UPDATE leaves SET member_id = :keep WHERE member_id = :remove", keepId, removeId);
        run("UPDATE worklogs SET member_id = :keep WHERE member_id = :remove", keepId, removeId);
        run("UPDATE issues SET assignee_id = :keep WHERE assignee_id = :remove", keepId, removeId);
        run("UPDATE issues SET reporter_id = :keep WHERE reporter_id = :remove", keepId, removeId);
        run("UPDATE issue_events SET from_value = :keep WHERE field = 'assignee' AND from_value = :remove", keepId, removeId);
        run("UPDATE issue_events SET to_value = :keep WHERE field = 'assignee' AND to_value = :remove", keepId, removeId);
        run("UPDATE access_requests SET member_id = :keep WHERE member_id = :remove", keepId, removeId);
        run("DELETE FROM member_permissions WHERE member_id = :remove", keepId, removeId);
        run("DELETE FROM member_scope WHERE member_id = :remove", keepId, removeId);
        run("UPDATE member_access SET member_id = :keep WHERE member_id = :remove "
                + "AND NOT EXISTS (SELECT 1 FROM member_access WHERE member_id = :keep)", keepId, removeId);
        run("DELETE FROM member_access WHERE member_id = :remove", keepId, removeId);
        run("""
                UPDATE members SET
                    email = CASE WHEN trim(email) = '' THEN (SELECT r.email FROM members r WHERE r.id = :remove) ELSE email END,
                    role = COALESCE(role, (SELECT r.role FROM members r WHERE r.id = :remove)),
                    permission = CASE WHEN (SELECT r.permission FROM members r WHERE r.id = :remove) = 'admin' THEN 'admin'
                                      ELSE permission END
                WHERE id = :keep AND EXISTS (SELECT 1 FROM members r WHERE r.id = :remove)
                """, keepId, removeId);
        String account = jdbc.sql("SELECT jira_account_id FROM members WHERE id = :remove").param("remove", removeId)
                .query(String.class).optional().orElse(null);
        run("UPDATE members SET jira_account_id = NULL WHERE id = :remove", keepId, removeId);
        if (account != null) {
            jdbc.sql("UPDATE members SET jira_account_id = :account WHERE id = :keep AND jira_account_id IS NULL")
                    .param("account", account).param("keep", keepId).update();
        }
        run("DELETE FROM members WHERE id = :remove", keepId, removeId);
    }
}
