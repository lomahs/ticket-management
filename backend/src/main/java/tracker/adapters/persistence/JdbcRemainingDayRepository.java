package tracker.adapters.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.RemainingDayRepository;
import tracker.domain.Numbers;

@Repository
class JdbcRemainingDayRepository implements RemainingDayRepository {
    /** IN lists are sent in chunks, below SQLite's limit on bound parameters. */
    private static final int CHUNK = 500;

    private final JdbcClient jdbc;

    JdbcRemainingDayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Map<String, Double>> find(Collection<String> issueKeys) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(issueKeys);
        for (int i = 0; i < keys.size(); i += CHUNK) {
            jdbc.sql("SELECT issue_key, day, remaining_h FROM issue_remaining_days WHERE issue_key IN (:keys)")
                    .param("keys", keys.subList(i, Math.min(keys.size(), i + CHUNK)))
                    .query(r -> {
                        out.computeIfAbsent(r.getString("issue_key"), k -> new TreeMap<>()).put(r.getString("day"), r.getDouble("remaining_h"));
                    });
        }
        return out;
    }

    @Override
    @Transactional
    public void save(String date, Map<String, Double> remainingByKey) {
        remainingByKey.forEach((key, h) -> jdbc.sql("""
                INSERT INTO issue_remaining_days (issue_key, day, remaining_h) VALUES (:key, :day, :h)
                ON CONFLICT (issue_key, day) DO UPDATE SET remaining_h = excluded.remaining_h
                """).param("key", key).param("day", date).param("h", Numbers.hours(h)).update());
    }
}
