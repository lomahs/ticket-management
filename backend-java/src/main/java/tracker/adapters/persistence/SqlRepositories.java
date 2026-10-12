package tracker.adapters.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeFormatterBuilder;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.ports.Repositories.AnkenRepository;
import tracker.application.ports.Repositories.CalendarRepository;
import tracker.application.ports.Repositories.IssueRepository;
import tracker.application.ports.Repositories.MemberRepository;
import tracker.application.ports.Repositories.SettingsRepository;
import tracker.application.ports.Repositories.SyncRun;
import tracker.application.ports.Repositories.SyncRunRepository;
import tracker.application.settings.SettingsDocuments;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Anken;
import tracker.domain.EventField;
import tracker.domain.Holiday;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Leave;
import tracker.domain.LeaveType;
import tracker.domain.Member;
import tracker.domain.Permission;
import tracker.domain.Role;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Worklog;

/** JDBC implementations of the repository ports (SQLite; the tables of schema.sql). */
final class SqlRepositories {
    private SqlRepositories() {
    }

    private static final int CHUNK = 500;

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += CHUNK) {
            out.add(items.subList(i, Math.min(items.size(), i + CHUNK)));
        }
        return out;
    }

    // --- members ----------------------------------------------------------------------------------------------------

    static final class Members implements MemberRepository {
        private final Jdbc db;

        Members(Jdbc db) {
            this.db = db;
        }

        private static Member member(ResultSet r) throws SQLException {
            String role = r.getString("role");
            return new Member(r.getString("id"), r.getString("name"), r.getString("full_name"),
                    role != null && !role.isEmpty() ? Role.of(role) : null, r.getString("country"), r.getString("color"),
                    r.getString("email"), Permission.of(r.getString("permission")), r.getInt("external") != 0,
                    r.getString("jira_account_id"));
        }

        @Override
        public List<Member> list() {
            return db.query("SELECT * FROM members ORDER BY id", Members::member);
        }

        @Override
        public Member get(String memberId) {
            return db.first("SELECT * FROM members WHERE id = ?", Members::member, memberId);
        }

        @Override
        public Member byAccount(String accountId) {
            return db.first("SELECT * FROM members WHERE jira_account_id = ?", Members::member, accountId);
        }

        @Override
        public void save(Member m) {
            Object[] values = {m.jiraAccountId(), m.name(), m.fullName(), m.email(), m.role() != null ? m.role().value() : null,
                    m.country(), m.color(), m.permission().value(), m.external(), m.id()};
            int n = db.update("UPDATE members SET jira_account_id = ?, name = ?, full_name = ?, email = ?, role = ?, country = ?, "
                    + "color = ?, permission = ?, external = ? WHERE id = ?", values);
            if (n == 0) {
                db.update("INSERT INTO members (jira_account_id, name, full_name, email, role, country, color, permission, "
                        + "external, id) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
            }
        }
    }

    // --- issues -----------------------------------------------------------------------------------------------------

    static final class Issues implements IssueRepository {
        private final Jdbc db;

        Issues(Jdbc db) {
            this.db = db;
        }

        private record IssueRow(String key, String project, String epicKey, String parentKey, String kind, String summary,
                                String phase, String status, String jiraStatus, String assigneeId, String plannedStart,
                                String plannedEnd, double estimateH, String created, String functionId, String functionName,
                                String step) {
        }

        private static IssueRow row(ResultSet r) throws SQLException {
            return new IssueRow(r.getString("key"), r.getString("project"), r.getString("epic_key"), r.getString("parent_key"),
                    r.getString("kind"), r.getString("summary"), r.getString("phase"), r.getString("status"),
                    r.getString("jira_status"), r.getString("assignee_id"), r.getString("planned_start"),
                    r.getString("planned_end"), r.getDouble("estimate_h"), r.getString("created"), r.getString("function_id"),
                    r.getString("function_name"), r.getString("step"));
        }

        private List<Issue> load(List<IssueRow> rows) {
            Map<String, List<Worklog>> logs = new LinkedHashMap<>();
            Map<String, List<IssueEvent>> events = new LinkedHashMap<>();
            for (List<String> chunk : chunks(rows.stream().map(IssueRow::key).toList())) {
                Object[] keys = chunk.toArray();
                db.query("SELECT * FROM worklogs WHERE issue_key IN (" + Jdbc.marks(chunk) + ") ORDER BY id", r -> {
                    logs.computeIfAbsent(r.getString("issue_key"), k -> new ArrayList<>()).add(new Worklog(
                            r.getString("date"), r.getString("member_id"), r.getDouble("hours"), r.getString("note")));
                    return null;
                }, keys);
                db.query("SELECT * FROM issue_events WHERE issue_key IN (" + Jdbc.marks(chunk) + ") ORDER BY id", r -> {
                    events.computeIfAbsent(r.getString("issue_key"), k -> new ArrayList<>()).add(new IssueEvent(
                            r.getString("at"), EventField.of(r.getString("field")), r.getString("from_value"),
                            r.getString("to_value")));
                    return null;
                }, keys);
            }
            return rows.stream().map(r -> new Issue(
                    r.key(), r.project(), r.epicKey(), r.parentKey(), IssueKind.of(r.kind()), r.summary(), r.phase(),
                    Status.of(r.status()), r.assigneeId(), r.plannedStart(), r.plannedEnd(), r.estimateH(), r.created(),
                    r.jiraStatus(), r.functionId(), r.functionName(),
                    r.step() != null && !r.step().isEmpty() ? StepKind.of(r.step()) : null,
                    logs.getOrDefault(r.key(), List.of()), events.getOrDefault(r.key(), List.of()))).toList();
        }

        @Override
        public List<Issue> list() {
            return load(db.query("SELECT * FROM issues ORDER BY \"key\"", Issues::row));
        }

        @Override
        public List<Issue> list(Collection<String> epicKeys) {
            if (epicKeys.isEmpty()) {
                return List.of();
            }
            return load(db.query("SELECT * FROM issues WHERE epic_key IN (" + Jdbc.marks(epicKeys) + ") ORDER BY \"key\"",
                    Issues::row, epicKeys.toArray()));
        }

        @Override
        public Issue get(String key) {
            List<Issue> found = load(db.query("SELECT * FROM issues WHERE \"key\" = ?", Issues::row, key));
            return found.isEmpty() ? null : found.get(0);
        }

        @Override
        public void save(Issue i) {
            Object[] values = {i.project(), i.epicKey(), i.parentKey(), i.kind().value(), i.summary(), i.phase(),
                    i.status().value(), i.jiraStatus(), i.assigneeId(), i.plannedStart(), i.plannedEnd(), i.estimateH(),
                    i.created(), i.functionId(), i.functionName(), i.step() != null ? i.step().value() : null, i.key()};
            int n = db.update("UPDATE issues SET project = ?, epic_key = ?, parent_key = ?, kind = ?, summary = ?, phase = ?, "
                    + "status = ?, jira_status = ?, assignee_id = ?, planned_start = ?, planned_end = ?, estimate_h = ?, created = ?, "
                    + "function_id = ?, function_name = ?, step = ? WHERE \"key\" = ?", values);
            if (n == 0) {
                db.update("INSERT INTO issues (project, epic_key, parent_key, kind, summary, phase, status, jira_status, "
                        + "assignee_id, planned_start, planned_end, estimate_h, created, function_id, function_name, step, \"key\") "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", values);
            }
            db.update("DELETE FROM worklogs WHERE issue_key = ?", i.key());
            db.update("DELETE FROM issue_events WHERE issue_key = ?", i.key());
            db.batch("INSERT INTO worklogs (issue_key, date, member_id, hours, note) VALUES (?, ?, ?, ?, ?)",
                    i.worklogs().stream().map(w -> new Object[]{i.key(), w.date(), w.memberId(), w.hours(), w.note()}).toList());
            db.batch("INSERT INTO issue_events (issue_key, at, field, from_value, to_value) VALUES (?, ?, ?, ?, ?)",
                    i.events().stream().map(e -> new Object[]{i.key(), e.at(), e.field().value(), e.fromValue(), e.toValue()})
                            .toList());
        }

        @Override
        public void delete(Collection<String> keys) {
            for (List<String> chunk : chunks(new ArrayList<>(keys))) {
                Object[] k = chunk.toArray();
                db.update("DELETE FROM worklogs WHERE issue_key IN (" + Jdbc.marks(chunk) + ")", k);
                db.update("DELETE FROM issue_events WHERE issue_key IN (" + Jdbc.marks(chunk) + ")", k);
                db.update("DELETE FROM issues WHERE \"key\" IN (" + Jdbc.marks(chunk) + ")", k);
            }
        }
    }

    // --- anken ------------------------------------------------------------------------------------------------------

    static final class AnkenRepo implements AnkenRepository {
        private final Jdbc db;

        AnkenRepo(Jdbc db) {
            this.db = db;
        }

        @Override
        public List<Anken> list() {
            return db.query("SELECT * FROM anken ORDER BY \"key\"", r -> new Anken(r.getString("key"), r.getString("project"),
                    r.getString("title"), r.getString("due_date")));
        }

        @Override
        public void save(Anken a) {
            Object[] values = {a.project(), a.title(), a.dueDate(), a.key()};
            if (db.update("UPDATE anken SET project = ?, title = ?, due_date = ? WHERE \"key\" = ?", values) == 0) {
                db.update("INSERT INTO anken (project, title, due_date, \"key\") VALUES (?, ?, ?, ?)", values);
            }
        }

        @Override
        public void deleteExcept(Collection<String> keys) {
            if (keys.isEmpty()) {
                db.update("DELETE FROM anken");
            } else {
                db.update("DELETE FROM anken WHERE \"key\" NOT IN (" + Jdbc.marks(keys) + ")", keys.toArray());
            }
        }
    }

    // --- calendar ---------------------------------------------------------------------------------------------------

    static final class Calendar implements CalendarRepository {
        private final Jdbc db;

        Calendar(Jdbc db) {
            this.db = db;
        }

        @Override
        public List<Leave> leaves() {
            return db.query("SELECT * FROM leaves ORDER BY date, member_id", r -> new Leave(r.getInt("id"), r.getString("member_id"),
                    r.getString("date"), LeaveType.of(r.getString("type")), r.getString("note")));
        }

        @Override
        public Leave addLeave(Leave lv) {
            if (lv.id() != 0) {
                db.update("INSERT INTO leaves (id, member_id, date, type, note) VALUES (?, ?, ?, ?, ?)",
                        lv.id(), lv.memberId(), lv.date(), lv.type().value(), lv.note());
                return lv;
            }
            int id = db.insertReturningId("INSERT INTO leaves (member_id, date, type, note) VALUES (?, ?, ?, ?)",
                    lv.memberId(), lv.date(), lv.type().value(), lv.note());
            return lv.withId(id);
        }

        @Override
        public void deleteLeave(int leaveId) {
            db.update("DELETE FROM leaves WHERE id = ?", leaveId);
        }

        @Override
        public Map<String, Holiday> holidays() {
            Map<String, Holiday> out = new LinkedHashMap<>();
            db.query("SELECT * FROM holidays", r -> {
                String countries = r.getString("countries").strip();
                out.put(r.getString("date"), new Holiday(r.getString("date"),
                        countries.isEmpty() ? List.of() : List.of(countries.split("\\s+")), r.getString("name")));
                return null;
            });
            return out;
        }

        @Override
        public void saveHoliday(Holiday h) {
            deleteHoliday(h.date());
            db.update("INSERT INTO holidays (date, countries, name) VALUES (?, ?, ?)", h.date(), String.join(" ", h.countries()),
                    h.name());
        }

        @Override
        public void deleteHoliday(String date) {
            db.update("DELETE FROM holidays WHERE date = ?", date);
        }

        @Override
        public Map<String, String> extraDays() {
            Map<String, String> out = new LinkedHashMap<>();
            db.query("SELECT * FROM extra_days", r -> out.put(r.getString("date"), r.getString("note")));
            return out;
        }

        @Override
        public void saveExtraDay(String date, String note) {
            deleteExtraDay(date);
            db.update("INSERT INTO extra_days (date, note) VALUES (?, ?)", date, note);
        }

        @Override
        public void deleteExtraDay(String date) {
            db.update("DELETE FROM extra_days WHERE date = ?", date);
        }
    }

    // --- settings ---------------------------------------------------------------------------------------------------

    static final class Settings implements SettingsRepository {
        private static final ObjectMapper JSON = new ObjectMapper();
        private final Jdbc db;

        Settings(Jdbc db) {
            this.db = db;
        }

        @Override
        public TrackerSettings load() {
            Map<String, Object> docs = new LinkedHashMap<>();
            db.query("SELECT \"key\", value FROM settings", r -> {
                try {
                    docs.put(r.getString("key"), JSON.readValue(r.getString("value"), new TypeReference<Object>() {
                    }));
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException("settings " + r.getString("key") + " is not valid JSON", e);
                }
                return null;
            });
            return SettingsDocuments.fromDocuments(docs);
        }

        @Override
        public void save(TrackerSettings s) {
            Map<String, Object> docs = SettingsDocuments.toDocuments(s);
            List<String> sections = SettingsDocuments.SECTIONS;
            db.update("DELETE FROM settings WHERE \"key\" IN (" + Jdbc.marks(sections) + ")", sections.toArray());
            List<Object[]> rows = new ArrayList<>();
            for (String k : sections) {
                try {
                    rows.add(new Object[]{k, JSON.writeValueAsString(docs.get(k))});
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }
            db.batch("INSERT INTO settings (\"key\", value) VALUES (?, ?)", rows);
        }
    }

    // --- sync runs --------------------------------------------------------------------------------------------------

    /** SQLAlchemy's SQLite DateTime format, in UTC: "2026-10-07 09:00:00.000000". */
    private static final DateTimeFormatter WRITE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSSSSS");
    private static final DateTimeFormatter READ = new DateTimeFormatterBuilder()
            .appendPattern("yyyy-MM-dd[ ]['T']HH:mm[:ss]")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND, 0, 9, true).optionalEnd()
            .toFormatter();

    static String formatInstant(Instant at) {
        return LocalDateTime.ofInstant(at, ZoneOffset.UTC).format(WRITE);
    }

    static Instant parseInstant(String s) {
        if (s == null) {
            return null;
        }
        String t = s.strip();
        int plus = t.indexOf('+', 10);
        if (plus > 0) {
            t = t.substring(0, plus); // stored values are UTC; an offset suffix is dropped
        }
        if (t.endsWith("Z")) {
            t = t.substring(0, t.length() - 1);
        }
        return LocalDateTime.parse(t, READ).toInstant(ZoneOffset.UTC);
    }

    static final class SyncRuns implements SyncRunRepository {
        private final Jdbc db;

        SyncRuns(Jdbc db) {
            this.db = db;
        }

        private static SyncRun run(ResultSet r) throws SQLException {
            return new SyncRun(r.getInt("id"), parseInstant(r.getString("started_at")), parseInstant(r.getString("finished_at")),
                    r.getInt("full") != 0, r.getString("status"), r.getInt("issues"), r.getString("error"));
        }

        @Override
        public int start(boolean full, Instant at) {
            return db.insertReturningId("INSERT INTO sync_runs (started_at, \"full\", status, issues) VALUES (?, ?, ?, ?)",
                    formatInstant(at), full, "running", 0);
        }

        @Override
        public void finish(int runId, Instant at, String status, int issues, String error) {
            db.update("UPDATE sync_runs SET finished_at = ?, status = ?, issues = ?, error = ? WHERE id = ?",
                    formatInstant(at), status, issues, error, runId);
        }

        @Override
        public SyncRun latest() {
            return db.first("SELECT * FROM sync_runs ORDER BY id DESC LIMIT 1", SyncRuns::run);
        }

        @Override
        public SyncRun latestOk() {
            return db.first("SELECT * FROM sync_runs WHERE status = 'ok' ORDER BY id DESC LIMIT 1", SyncRuns::run);
        }
    }
}
