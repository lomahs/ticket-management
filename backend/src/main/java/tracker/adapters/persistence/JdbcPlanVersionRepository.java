package tracker.adapters.persistence;

import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.PlanVersionRepository;

@Repository
class JdbcPlanVersionRepository implements PlanVersionRepository {
    private final JdbcClient jdbc;

    JdbcPlanVersionRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void add(String epic, String savedAt, String savedBy, String body, boolean withEstimates) {
        jdbc.sql("INSERT INTO plan_versions (epic_key, saved_at, saved_by, body, with_estimates) VALUES (:epic, :at, :by, :body, :est)")
                .param("epic", epic).param("at", savedAt).param("by", savedBy).param("body", body).param("est", withEstimates ? 1 : 0)
                .update();
    }

    @Override
    public @Nullable String lastSavedAt(String epic) {
        return jdbc.sql("SELECT MAX(saved_at) FROM plan_versions WHERE epic_key = :epic").param("epic", epic)
                .query(String.class).optional().orElse(null);
    }

    @Override
    public Optional<SavedVersion> baseline(String epic) {
        return jdbc.sql("SELECT saved_at, body FROM plan_versions WHERE epic_key = :epic AND with_estimates = 1 ORDER BY id LIMIT 1")
                .param("epic", epic).query((rs, n) -> new SavedVersion(rs.getString("saved_at"), rs.getString("body"))).optional();
    }
}
