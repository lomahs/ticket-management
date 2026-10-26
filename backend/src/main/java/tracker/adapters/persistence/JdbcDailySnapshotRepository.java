package tracker.adapters.persistence;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.ports.DailySnapshotRepository;

/** Snapshots as JSON documents (the application's JSON settings: snake_case names). */
@Repository
class JdbcDailySnapshotRepository implements DailySnapshotRepository {
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcDailySnapshotRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public void save(String epicKey, String date, String capturedAt, String capturedBy, Object payload) {
        String value;
        try {
            value = json.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        jdbc.sql("""
                INSERT INTO daily_snapshots (epic_key, date, captured_at, captured_by, payload)
                VALUES (:epic, :date, :at, :by, :payload)
                ON CONFLICT (epic_key, date)
                DO UPDATE SET captured_at = excluded.captured_at, captured_by = excluded.captured_by, payload = excluded.payload
                """).param("epic", epicKey).param("date", date).param("at", capturedAt).param("by", capturedBy).param("payload", value)
                .update();
    }

    @Override
    public <T> Optional<T> find(String epicKey, String date, Class<T> type) {
        return jdbc.sql("SELECT payload FROM daily_snapshots WHERE epic_key = :epic AND date = :date").param("epic", epicKey)
                .param("date", date).query(String.class).optional().map(v -> {
                    try {
                        return json.readValue(v, type);
                    } catch (JsonProcessingException e) {
                        throw new IllegalStateException("The snapshot of " + epicKey + " on " + date + " is not valid JSON", e);
                    }
                });
    }

    @Override
    public List<SnapshotRef> list(String epicKey) {
        return jdbc.sql("SELECT date, captured_at, captured_by FROM daily_snapshots WHERE epic_key = :epic ORDER BY date DESC")
                .param("epic", epicKey)
                .query((r, n) -> new SnapshotRef(r.getString("date"), r.getString("captured_at"), r.getString("captured_by"))).list();
    }
}
