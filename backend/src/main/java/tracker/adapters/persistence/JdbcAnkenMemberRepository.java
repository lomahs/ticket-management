package tracker.adapters.persistence;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.AnkenMemberRepository;
import tracker.domain.AnkenMember;

@Repository
class JdbcAnkenMemberRepository implements AnkenMemberRepository {
    private final JdbcClient jdbc;

    JdbcAnkenMemberRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<AnkenMember> findByEpic(String epic) {
        return jdbc.sql("SELECT * FROM anken_members WHERE epic_key = :epic ORDER BY member_id").param("epic", epic)
                .query((r, row) -> new AnkenMember(r.getString("epic_key"), r.getString("member_id"), r.getString("added_at"), r.getString("added_by")))
                .list();
    }

    @Override
    public Set<String> findEpicsOf(String memberId) {
        return new HashSet<>(jdbc.sql("SELECT epic_key FROM anken_members WHERE member_id = :id").param("id", memberId).query(String.class).list());
    }

    @Override
    public Map<String, Set<String>> findAll() {
        Map<String, Set<String>> out = new HashMap<>();
        jdbc.sql("SELECT epic_key, member_id FROM anken_members").query(r -> {
            out.computeIfAbsent(r.getString("epic_key"), k -> new HashSet<>()).add(r.getString("member_id"));
        });
        return out;
    }

    @Override
    public boolean exists(String epic, String memberId) {
        return jdbc.sql("SELECT count(*) FROM anken_members WHERE epic_key = :epic AND member_id = :id").param("epic", epic).param("id", memberId)
                .query(Integer.class).single() > 0;
    }

    @Override
    public void add(String epic, String memberId, String by, String at) {
        jdbc.sql("INSERT OR IGNORE INTO anken_members (epic_key, member_id, added_at, added_by) VALUES (:epic, :id, :at, :by)")
                .param("epic", epic).param("id", memberId).param("at", at).param("by", by).update();
    }

    @Override
    public void remove(String epic, String memberId) {
        jdbc.sql("DELETE FROM anken_members WHERE epic_key = :epic AND member_id = :id").param("epic", epic).param("id", memberId).update();
    }

    @Override
    public boolean isEmpty() {
        return jdbc.sql("SELECT count(*) FROM anken_members").query(Integer.class).single() == 0;
    }
}
