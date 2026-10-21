package tracker.adapters.persistence;

import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.AllocationRepository;
import tracker.domain.Allocation;

@Repository
class JdbcAllocationRepository implements AllocationRepository {
    private final JdbcClient jdbc;

    JdbcAllocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Allocation> findAll() {
        return jdbc.sql("SELECT * FROM allocations ORDER BY member_id, epic_key, effective_from").query(JdbcAllocationRepository::map).list();
    }

    @Override
    public List<Allocation> findByMember(String memberId) {
        return jdbc.sql("SELECT * FROM allocations WHERE member_id = :id ORDER BY epic_key, effective_from").param("id", memberId)
                .query(JdbcAllocationRepository::map).list();
    }

    @Override
    public List<StampedAllocation> findStampedByMember(String memberId) {
        return jdbc.sql("SELECT * FROM allocations WHERE member_id = :id ORDER BY effective_from, epic_key").param("id", memberId)
                .query((r, row) -> new StampedAllocation(map(r, row), r.getString("set_by"), r.getString("set_at"))).list();
    }

    @Override
    public void save(Allocation a, String setBy, String setAt) {
        jdbc.sql("""
                INSERT INTO allocations (member_id, epic_key, effective_from, hours_per_day, set_by, set_at)
                VALUES (:member, :epic, :from, :hours, :by, :at)
                ON CONFLICT (member_id, epic_key, effective_from)
                DO UPDATE SET hours_per_day = excluded.hours_per_day, set_by = excluded.set_by, set_at = excluded.set_at
                """).param("member", a.memberId()).param("epic", a.epicKey()).param("from", a.effectiveFrom())
                .param("hours", a.hoursPerDay()).param("by", setBy).param("at", setAt).update();
    }

    private static Allocation map(java.sql.ResultSet r, int row) throws java.sql.SQLException {
        return new Allocation(r.getString("member_id"), r.getString("epic_key"), r.getString("effective_from"), r.getDouble("hours_per_day"));
    }
}
