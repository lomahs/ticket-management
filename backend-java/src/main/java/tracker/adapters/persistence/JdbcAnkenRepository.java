package tracker.adapters.persistence;

import java.util.Collection;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.AnkenRepository;
import tracker.domain.Anken;

@Repository
class JdbcAnkenRepository implements AnkenRepository {
    private final JdbcClient jdbc;

    JdbcAnkenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Anken> findAll() {
        return jdbc.sql("SELECT * FROM anken ORDER BY \"key\"").query(Anken.class).list();
    }

    @Override
    public void save(Anken a) {
        jdbc.sql("""
                INSERT INTO anken ("key", project, title, due_date) VALUES (:key, :project, :title, :dueDate)
                ON CONFLICT ("key") DO UPDATE SET project = excluded.project, title = excluded.title, due_date = excluded.due_date
                """)
                .param("key", a.key()).param("project", a.project()).param("title", a.title()).param("dueDate", a.dueDate())
                .update();
    }

    @Override
    public void deleteAllExcept(Collection<String> keys) {
        if (keys.isEmpty()) {
            jdbc.sql("DELETE FROM anken").update();
        } else {
            jdbc.sql("DELETE FROM anken WHERE \"key\" NOT IN (:keys)").param("keys", keys).update();
        }
    }
}
