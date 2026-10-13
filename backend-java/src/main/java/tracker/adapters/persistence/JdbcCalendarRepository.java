package tracker.adapters.persistence;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import tracker.application.ports.CalendarRepository;
import tracker.domain.Holiday;
import tracker.domain.Leave;
import tracker.domain.LeaveType;

@Repository
class JdbcCalendarRepository implements CalendarRepository {
    private final JdbcClient jdbc;

    JdbcCalendarRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public List<Leave> findLeaves() {
        return jdbc.sql("SELECT * FROM leaves ORDER BY date, member_id").query((r, n) -> new Leave(r.getInt("id"),
                r.getString("member_id"), r.getString("date"), LeaveType.of(r.getString("type")), r.getString("note"))).list();
    }

    @Override
    public Leave saveLeave(Leave lv) {
        JdbcClient.StatementSpec insert = jdbc.sql("""
                INSERT INTO leaves (id, member_id, date, type, note) VALUES (:id, :member, :date, :type, :note)
                """).param("id", lv.id() != 0 ? lv.id() : null).param("member", lv.memberId()).param("date", lv.date())
                .param("type", lv.type().value()).param("note", lv.note());
        if (lv.id() != 0) {
            insert.update();
            return lv;
        }
        KeyHolder id = new GeneratedKeyHolder();
        insert.update(id);
        return lv.withId(id.getKeyAs(Number.class).intValue());
    }

    @Override
    public void deleteLeave(int leaveId) {
        jdbc.sql("DELETE FROM leaves WHERE id = :id").param("id", leaveId).update();
    }

    @Override
    public Map<String, Holiday> findHolidays() {
        Map<String, Holiday> out = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM holidays ORDER BY date").query(r -> {
            String countries = r.getString("countries").strip();
            out.put(r.getString("date"), new Holiday(r.getString("date"),
                    countries.isEmpty() ? List.of() : List.of(countries.split("\\s+")), r.getString("name")));
        });
        return out;
    }

    @Override
    public void saveHoliday(Holiday h) {
        jdbc.sql("""
                INSERT INTO holidays (date, countries, name) VALUES (:date, :countries, :name)
                ON CONFLICT (date) DO UPDATE SET countries = excluded.countries, name = excluded.name
                """).param("date", h.date()).param("countries", String.join(" ", h.countries())).param("name", h.name()).update();
    }

    @Override
    public void deleteHoliday(String date) {
        jdbc.sql("DELETE FROM holidays WHERE date = :date").param("date", date).update();
    }

    @Override
    public Map<String, String> findExtraDays() {
        Map<String, String> out = new LinkedHashMap<>();
        jdbc.sql("SELECT * FROM extra_days ORDER BY date").query(r -> {
            out.put(r.getString("date"), r.getString("note"));
        });
        return out;
    }

    @Override
    public void saveExtraDay(String date, String note) {
        jdbc.sql("""
                INSERT INTO extra_days (date, note) VALUES (:date, :note) ON CONFLICT (date) DO UPDATE SET note = excluded.note
                """).param("date", date).param("note", note).update();
    }

    @Override
    public void deleteExtraDay(String date) {
        jdbc.sql("DELETE FROM extra_days WHERE date = :date").param("date", date).update();
    }
}
