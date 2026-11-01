package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.OnboardingRepository;
import tracker.domain.Onboarding;

@Repository
class JdbcOnboardingRepository implements OnboardingRepository {
    private final JdbcClient jdbc;

    JdbcOnboardingRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Onboarding> findAll() {
        return jdbc.sql("SELECT * FROM onboarding ORDER BY saved_at DESC, epic_key").query(JdbcOnboardingRepository::row).list();
    }

    @Override
    public Optional<Onboarding> find(String epic) {
        return jdbc.sql("SELECT * FROM onboarding WHERE epic_key = :epic").param("epic", epic).query(JdbcOnboardingRepository::row).optional();
    }

    @Override
    public void save(Onboarding o) {
        jdbc.sql("""
                INSERT INTO onboarding (epic_key, anken_prefix, step, started_at, started_by, saved_at, saved_by, finished_at)
                VALUES (:epic, :prefix, :step, :startedAt, :startedBy, :savedAt, :savedBy, :finishedAt)
                ON CONFLICT (epic_key) DO UPDATE SET anken_prefix = :prefix, step = :step, saved_at = :savedAt, saved_by = :savedBy,
                    finished_at = :finishedAt
                """).param("epic", o.epicKey()).param("prefix", o.ankenPrefix()).param("step", o.step()).param("startedAt", o.startedAt())
                .param("startedBy", o.startedBy()).param("savedAt", o.savedAt()).param("savedBy", o.savedBy()).param("finishedAt", o.finishedAt())
                .update();
    }

    @Override
    public void delete(String epic) {
        jdbc.sql("DELETE FROM onboarding WHERE epic_key = :epic").param("epic", epic).update();
    }

    private static Onboarding row(ResultSet r, int n) throws SQLException {
        return new Onboarding(r.getString("epic_key"), r.getString("anken_prefix"), r.getInt("step"), r.getString("started_at"),
                r.getString("started_by"), r.getString("saved_at"), r.getString("saved_by"), r.getString("finished_at"));
    }
}
