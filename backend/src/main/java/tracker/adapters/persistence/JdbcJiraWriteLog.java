package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.JiraWriteLog;

@Repository
class JdbcJiraWriteLog implements JiraWriteLog {
    private final JdbcClient jdbc;

    JdbcJiraWriteLog(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static JiraLogEntry entry(ResultSet r, int row) throws SQLException {
        return new JiraLogEntry(r.getInt("id"), r.getString("at"), r.getString("actor_email"), r.getString("actor_name"), r.getString("source"),
                r.getString("issue_key"), r.getString("method"), r.getString("path"), r.getString("body"), r.getInt("status"),
                r.getString("error"), r.getLong("duration_ms"));
    }

    @Override
    public void add(JiraLogEntry e) {
        jdbc.sql("""
                INSERT INTO jira_write_log (at, actor_email, actor_name, source, issue_key, method, path, body, status, error, duration_ms)
                VALUES (:at, :email, :name, :source, :key, :method, :path, :body, :status, :error, :ms)
                """).param("at", e.at()).param("email", e.actorEmail()).param("name", e.actorName()).param("source", e.source())
                .param("key", e.key()).param("method", e.method()).param("path", e.path()).param("body", e.body())
                .param("status", e.status()).param("error", e.error()).param("ms", e.durationMs()).update();
    }

    @Override
    public List<JiraLogEntry> recent(int limit, boolean failedOnly) {
        return jdbc.sql("SELECT * FROM jira_write_log WHERE (:failed = 0 OR status = 0 OR status >= 400) ORDER BY id DESC LIMIT :limit")
                .param("failed", failedOnly ? 1 : 0).param("limit", limit).query(JdbcJiraWriteLog::entry).list();
    }
}
