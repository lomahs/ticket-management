package tracker.adapters.persistence;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import tracker.application.ports.IssueRepository;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.IssueKind;
import tracker.domain.Status;
import tracker.domain.StepKind;
import tracker.domain.Worklog;

/** Issues with their worklogs and change events (child rows are replaced on every save). */
@Repository
class JdbcIssueRepository implements IssueRepository {
    /** IN lists are sent in chunks, below SQLite's limit on bound parameters. */
    private static final int CHUNK = 500;

    private record IssueRow(String key, String project, String epicKey, String parentKey, String kind, String summary,
                            String phase, String status, String jiraStatus, String assigneeId, String plannedStart,
                            String plannedEnd, double estimateH, String created, String functionId, String functionName,
                            String step) {
    }

    private record WorklogRow(String issueKey, String date, String memberId, double hours, String note) {
    }

    private record EventRow(String issueKey, String at, String field, String fromValue, String toValue) {
    }

    private final JdbcClient jdbc;
    private final JdbcTemplate template;

    JdbcIssueRepository(JdbcClient jdbc, JdbcTemplate template) {
        this.jdbc = jdbc;
        this.template = template;
    }

    private static <T> List<List<T>> chunks(List<T> items) {
        List<List<T>> out = new ArrayList<>();
        for (int i = 0; i < items.size(); i += CHUNK) {
            out.add(items.subList(i, Math.min(items.size(), i + CHUNK)));
        }
        return out;
    }

    private List<Issue> withChildren(List<IssueRow> rows) {
        Map<String, List<Worklog>> logs = new LinkedHashMap<>();
        Map<String, List<IssueEvent>> events = new LinkedHashMap<>();
        for (List<String> keys : chunks(rows.stream().map(IssueRow::key).toList())) {
            jdbc.sql("SELECT * FROM worklogs WHERE issue_key IN (:keys) ORDER BY id").param("keys", keys).query(WorklogRow.class)
                    .list().forEach(w -> logs.computeIfAbsent(w.issueKey(), k -> new ArrayList<>())
                            .add(new Worklog(w.date(), w.memberId(), w.hours(), w.note())));
            jdbc.sql("SELECT * FROM issue_events WHERE issue_key IN (:keys) ORDER BY id").param("keys", keys).query(EventRow.class)
                    .list().forEach(e -> events.computeIfAbsent(e.issueKey(), k -> new ArrayList<>())
                            .add(new IssueEvent(e.at(), EventField.of(e.field()), e.fromValue(), e.toValue())));
        }
        return rows.stream().map(r -> new Issue(
                r.key(), r.project(), r.epicKey(), r.parentKey(), IssueKind.of(r.kind()), r.summary(), r.phase(),
                Status.of(r.status()), r.assigneeId(), r.plannedStart(), r.plannedEnd(), r.estimateH(), r.created(),
                r.jiraStatus(), r.functionId(), r.functionName(), r.step() != null ? StepKind.of(r.step()) : null,
                logs.getOrDefault(r.key(), List.of()), events.getOrDefault(r.key(), List.of()))).toList();
    }

    @Override
    public List<Issue> findAll() {
        return withChildren(jdbc.sql("SELECT * FROM issues ORDER BY \"key\"").query(IssueRow.class).list());
    }

    @Override
    public List<Issue> findByEpics(Collection<String> epicKeys) {
        if (epicKeys.isEmpty()) {
            return List.of();
        }
        return withChildren(jdbc.sql("SELECT * FROM issues WHERE epic_key IN (:epics) ORDER BY \"key\"").param("epics", epicKeys)
                .query(IssueRow.class).list());
    }

    @Override
    public Optional<Issue> findByKey(String key) {
        return withChildren(jdbc.sql("SELECT * FROM issues WHERE \"key\" = :key").param("key", key).query(IssueRow.class).list())
                .stream().findFirst();
    }

    @Override
    public void save(Issue i) {
        jdbc.sql("""
                INSERT INTO issues ("key", project, epic_key, parent_key, kind, summary, phase, status, jira_status, assignee_id,
                                    planned_start, planned_end, estimate_h, created, function_id, function_name, step)
                VALUES (:key, :project, :epicKey, :parentKey, :kind, :summary, :phase, :status, :jiraStatus, :assigneeId,
                        :plannedStart, :plannedEnd, :estimateH, :created, :functionId, :functionName, :step)
                ON CONFLICT ("key") DO UPDATE SET project = excluded.project, epic_key = excluded.epic_key,
                    parent_key = excluded.parent_key, kind = excluded.kind, summary = excluded.summary, phase = excluded.phase,
                    status = excluded.status, jira_status = excluded.jira_status, assignee_id = excluded.assignee_id,
                    planned_start = excluded.planned_start, planned_end = excluded.planned_end, estimate_h = excluded.estimate_h,
                    created = excluded.created, function_id = excluded.function_id, function_name = excluded.function_name,
                    step = excluded.step
                """)
                .param("key", i.key()).param("project", i.project()).param("epicKey", i.epicKey()).param("parentKey", i.parentKey())
                .param("kind", i.kind().value()).param("summary", i.summary()).param("phase", i.phase())
                .param("status", i.status().value()).param("jiraStatus", i.jiraStatus()).param("assigneeId", i.assigneeId())
                .param("plannedStart", i.plannedStart()).param("plannedEnd", i.plannedEnd()).param("estimateH", i.estimateH())
                .param("created", i.created()).param("functionId", i.functionId()).param("functionName", i.functionName())
                .param("step", i.step() != null ? i.step().value() : null)
                .update();
        jdbc.sql("DELETE FROM worklogs WHERE issue_key = :key").param("key", i.key()).update();
        jdbc.sql("DELETE FROM issue_events WHERE issue_key = :key").param("key", i.key()).update();
        template.batchUpdate("INSERT INTO worklogs (issue_key, date, member_id, hours, note) VALUES (?, ?, ?, ?, ?)",
                i.worklogs().stream().map(w -> new Object[]{i.key(), w.date(), w.memberId(), w.hours(), w.note()}).toList());
        template.batchUpdate("INSERT INTO issue_events (issue_key, at, field, from_value, to_value) VALUES (?, ?, ?, ?, ?)",
                i.events().stream().map(e -> new Object[]{i.key(), e.at(), e.field().value(), e.fromValue(), e.toValue()}).toList());
    }

    @Override
    public void deleteAll(Collection<String> keys) {
        for (List<String> chunk : chunks(List.copyOf(keys))) { // worklogs and events go with them (ON DELETE CASCADE)
            jdbc.sql("DELETE FROM issues WHERE \"key\" IN (:keys)").param("keys", chunk).update();
        }
    }
}
