package tracker.adapters.persistence;

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
    public void add(String epic, String savedAt, String savedBy, String body) {
        jdbc.sql("INSERT INTO plan_versions (epic_key, saved_at, saved_by, body) VALUES (:epic, :at, :by, :body)")
                .param("epic", epic).param("at", savedAt).param("by", savedBy).param("body", body).update();
    }

    @Override
    public @Nullable String lastSavedAt(String epic) {
        return jdbc.sql("SELECT MAX(saved_at) FROM plan_versions WHERE epic_key = :epic").param("epic", epic)
                .query(String.class).optional().orElse(null);
    }
}
