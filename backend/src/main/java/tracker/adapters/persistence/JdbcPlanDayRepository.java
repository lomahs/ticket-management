package tracker.adapters.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.PlanDayRepository;
import tracker.domain.Numbers;

@Repository
class JdbcPlanDayRepository implements PlanDayRepository {
    /** IN lists are sent in chunks, below SQLite's limit on bound parameters. */
    private static final int CHUNK = 500;

    private final JdbcClient jdbc;

    JdbcPlanDayRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Map<String, Map<String, Double>> find(Collection<String> issueKeys) {
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>(issueKeys);
        for (int i = 0; i < keys.size(); i += CHUNK) {
            jdbc.sql("SELECT issue_key, day, hours FROM issue_plan_days WHERE issue_key IN (:keys)")
                    .param("keys", keys.subList(i, Math.min(keys.size(), i + CHUNK)))
                    .query(r -> {
                        out.computeIfAbsent(r.getString("issue_key"), k -> new TreeMap<>()).put(r.getString("day"), r.getDouble("hours"));
                    });
        }
        return out;
    }

    @Override
    public Set<String> linked(Collection<String> issueKeys) {
        return new HashSet<>(find(issueKeys).keySet());
    }

    @Override
    @Transactional
    public void save(String issueKey, Map<String, Double> hoursByDay) {
        jdbc.sql("DELETE FROM issue_plan_days WHERE issue_key = :key").param("key", issueKey).update();
        hoursByDay.forEach((day, h) -> jdbc.sql("INSERT INTO issue_plan_days (issue_key, day, hours) VALUES (:key, :day, :hours)")
                .param("key", issueKey).param("day", day).param("hours", Numbers.hours(h)).update());
    }
}
