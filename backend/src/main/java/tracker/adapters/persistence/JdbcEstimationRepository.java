package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import tracker.application.ports.EstimationRepository;
import tracker.domain.EstimationRow;

@Repository
class JdbcEstimationRepository implements EstimationRepository {
    private final JdbcClient jdbc;

    JdbcEstimationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<EstimationRow> findByEpic(String epic) {
        return jdbc.sql("SELECT * FROM estimation_rows WHERE epic_key = :epic ORDER BY position, id").param("epic", epic)
                .query(JdbcEstimationRepository::row).list();
    }

    @Override
    @Transactional
    public void save(String epic, List<EstimationRow> rows) {
        Set<Long> kept = new HashSet<>();
        rows.forEach(r -> kept.add(r.id()));
        for (EstimationRow old : findByEpic(epic)) {
            if (!old.pushed() && !kept.contains(old.id())) {
                jdbc.sql("DELETE FROM estimation_rows WHERE id = :id").param("id", old.id()).update();
            }
        }
        for (EstimationRow r : rows) {
            if (r.id() > 0) {
                jdbc.sql("""
                        UPDATE estimation_rows SET position = :position, phase = :phase, subphase = :subphase, function_name = :fn,
                            steps = :steps, effort_h = :effort, ba_id = :ba, bal_id = :bal, jm_id = :jm, task_key = :task
                        WHERE id = :id AND epic_key = :epic AND pushed_at IS NULL
                        """).param("id", r.id()).param("epic", epic).params(values(r)).update();
            } else {
                jdbc.sql("""
                        INSERT INTO estimation_rows (epic_key, position, phase, subphase, function_name, steps, effort_h, ba_id, bal_id, jm_id, task_key)
                        VALUES (:epic, :position, :phase, :subphase, :fn, :steps, :effort, :ba, :bal, :jm, :task)
                        """).param("epic", epic).params(values(r)).update();
            }
        }
    }

    @Override
    public void markPushed(List<Long> ids, String at) {
        for (Long id : ids) {
            jdbc.sql("UPDATE estimation_rows SET pushed_at = :at WHERE id = :id").param("at", at).param("id", id).update();
        }
    }

    @Override
    public void deleteUnpushed(String epic) {
        jdbc.sql("DELETE FROM estimation_rows WHERE epic_key = :epic AND pushed_at IS NULL").param("epic", epic).update();
    }

    private static Map<String, Object> values(EstimationRow r) {
        Map<String, Object> v = new HashMap<>();
        v.put("position", r.position());
        v.put("phase", r.phase());
        v.put("subphase", r.subphase());
        v.put("fn", r.functionName());
        v.put("steps", r.steps());
        v.put("effort", r.effortH());
        v.put("ba", r.baId());
        v.put("bal", r.balId());
        v.put("jm", r.jmId());
        v.put("task", r.taskKey());
        return v;
    }

    private static EstimationRow row(ResultSet r, int n) throws SQLException {
        int steps = r.getInt("steps");
        Integer s = r.wasNull() ? null : steps;
        return new EstimationRow(r.getLong("id"), r.getString("epic_key"), r.getInt("position"), r.getString("phase"), r.getString("subphase"),
                r.getString("function_name"), s, r.getDouble("effort_h"), r.getString("ba_id"), r.getString("bal_id"), r.getString("jm_id"),
                r.getString("task_key"), r.getString("pushed_at"));
    }
}
