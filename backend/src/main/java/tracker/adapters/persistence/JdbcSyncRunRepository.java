package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import tracker.application.ports.SyncRunRepository;

/** Sync runs; timestamps are stored as ISO-8601 text. */
@Repository
class JdbcSyncRunRepository implements SyncRunRepository {
    private final JdbcClient jdbc;

    JdbcSyncRunRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    private static SyncRun run(ResultSet r, int row) throws SQLException {
        String finished = r.getString("finished_at");
        return new SyncRun(r.getInt("id"), Instant.parse(r.getString("started_at")), finished != null ? Instant.parse(finished) : null,
                r.getBoolean("full"), r.getString("status"), r.getInt("issues"), r.getString("error"));
    }

    @Override
    public int start(boolean full, Instant at) {
        KeyHolder id = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO sync_runs (started_at, \"full\", status, issues) VALUES (:at, :full, 'running', 0)")
                .param("at", at.toString()).param("full", full).update(id);
        return id.getKeyAs(Number.class).intValue();
    }

    @Override
    public void finish(int runId, Instant at, String status, int issues, @Nullable String error) {
        jdbc.sql("UPDATE sync_runs SET finished_at = :at, status = :status, issues = :issues, error = :error WHERE id = :id")
                .param("at", at.toString()).param("status", status).param("issues", issues).param("error", error).param("id", runId)
                .update();
    }

    @Override
    public Optional<SyncRun> findLatest() {
        return jdbc.sql("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1").query(JdbcSyncRunRepository::run).optional();
    }

    @Override
    public Optional<SyncRun> findLatestOk() {
        return jdbc.sql("SELECT * FROM sync_runs WHERE status = 'ok' ORDER BY id DESC LIMIT 1").query(JdbcSyncRunRepository::run)
                .optional();
    }
}
