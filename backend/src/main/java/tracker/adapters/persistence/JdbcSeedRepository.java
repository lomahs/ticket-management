package tracker.adapters.persistence;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.SeedRepository;

@Repository
class JdbcSeedRepository implements SeedRepository {
    private final JdbcClient jdbc;

    JdbcSeedRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public boolean done(String name) {
        return jdbc.sql("SELECT count(*) FROM seeds WHERE name = :name").param("name", name).query(Integer.class).single() > 0;
    }

    @Override
    public void markDone(String name, String at) {
        jdbc.sql("INSERT OR IGNORE INTO seeds (name, done_at) VALUES (:name, :at)").param("name", name).param("at", at).update();
    }
}
