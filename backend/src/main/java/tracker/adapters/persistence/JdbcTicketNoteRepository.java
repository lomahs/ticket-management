package tracker.adapters.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.TicketNoteRepository;

@Repository
class JdbcTicketNoteRepository implements TicketNoteRepository {
    /** IN lists are sent in chunks, below SQLite's limit on bound parameters. */
    private static final int CHUNK = 500;

    private final JdbcClient jdbc;

    JdbcTicketNoteRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, String> find(Collection<String> issueKeys) {
        Map<String, String> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(issueKeys);
        for (int i = 0; i < keys.size(); i += CHUNK) {
            jdbc.sql("SELECT issue_key, note FROM ticket_notes WHERE issue_key IN (:keys)").param("keys", keys.subList(i, Math.min(keys.size(), i + CHUNK)))
                    .query((r, n) -> Map.entry(r.getString("issue_key"), r.getString("note"))).list()
                    .forEach(e -> out.put(e.getKey(), e.getValue()));
        }
        return out;
    }

    @Override
    public void save(String issueKey, String note, String updatedBy, String updatedAt) {
        if (note.isBlank()) {
            jdbc.sql("DELETE FROM ticket_notes WHERE issue_key = :key").param("key", issueKey).update();
            return;
        }
        jdbc.sql("""
                INSERT INTO ticket_notes (issue_key, note, updated_by, updated_at) VALUES (:key, :note, :by, :at)
                ON CONFLICT (issue_key) DO UPDATE SET note = excluded.note, updated_by = excluded.updated_by, updated_at = excluded.updated_at
                """).param("key", issueKey).param("note", note).param("by", updatedBy).param("at", updatedAt).update();
    }
}
