package tracker.adapters.persistence;

import java.util.ArrayList;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.ports.MasterScheduleRepository;
import tracker.domain.ScheduleVersion;
import tracker.domain.ScheduleVersion.PhaseRow;

@Repository
class JdbcMasterScheduleRepository implements MasterScheduleRepository {
    private static final TypeReference<List<String>> IDS = new TypeReference<>() {
    };

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    JdbcMasterScheduleRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Override
    public List<ScheduleVersion> findByEpic(String epic) {
        List<ScheduleVersion> out = new ArrayList<>();
        jdbc.sql("SELECT * FROM master_schedule_versions WHERE epic_key = :epic ORDER BY id").param("epic", epic).query((r, n) -> {
            long id = r.getLong("id");
            return new Object[]{id, r.getString("saved_at"), r.getString("saved_by"), r.getString("reason")};
        }).list().forEach(v -> {
            long id = (Long) v[0];
            List<PhaseRow> phases = jdbc.sql("SELECT * FROM master_schedule_phases WHERE version_id = :id ORDER BY position").param("id", id)
                    .query((r, n) -> new PhaseRow(r.getString("phase"), r.getDouble("total_effort_h"), ids(r.getString("member_ids")),
                            r.getString("start_date"), r.getString("end_date"), r.getInt("plan_md"))).list();
            out.add(new ScheduleVersion(id, epic, (String) v[1], (String) v[2], (String) v[3], phases));
        });
        return out;
    }

    @Override
    @Transactional
    public ScheduleVersion add(ScheduleVersion v) {
        GeneratedKeyHolder key = new GeneratedKeyHolder();
        jdbc.sql("INSERT INTO master_schedule_versions (epic_key, saved_at, saved_by, reason) VALUES (:epic, :at, :by, :reason)")
                .param("epic", v.epicKey()).param("at", v.savedAt()).param("by", v.savedBy()).param("reason", v.reason()).update(key);
        long id = key.getKey().longValue();
        for (int i = 0; i < v.phases().size(); i++) {
            PhaseRow p = v.phases().get(i);
            jdbc.sql("""
                    INSERT INTO master_schedule_phases (version_id, position, phase, member_ids, start_date, end_date, plan_md, total_effort_h)
                    VALUES (:id, :position, :phase, :members, :start, :end, :md, :effort)
                    """).param("id", id).param("position", i).param("phase", p.phase()).param("members", write(p.memberIds()))
                    .param("start", p.start()).param("end", p.end()).param("md", p.planMd()).param("effort", p.totalEffortH()).update();
        }
        return new ScheduleVersion(id, v.epicKey(), v.savedAt(), v.savedBy(), v.reason(), v.phases());
    }

    private List<String> ids(String raw) {
        try {
            return json.readValue(raw, IDS);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Stored member ids are not valid JSON", e);
        }
    }

    private String write(List<String> ids) {
        try {
            return json.writeValueAsString(ids);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void deleteByEpic(String epic) {
        jdbc.sql("DELETE FROM master_schedule_phases WHERE version_id IN (SELECT id FROM master_schedule_versions WHERE epic_key = :epic)")
                .param("epic", epic).update();
        jdbc.sql("DELETE FROM master_schedule_versions WHERE epic_key = :epic").param("epic", epic).update();
    }
}
