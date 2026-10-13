package tracker.adapters.fake;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.NewSubtask;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraWriter;
import tracker.application.settings.TrackerSettings;
import tracker.domain.Holiday;
import tracker.domain.Leave;
import tracker.domain.Member;
import tracker.domain.Status;

/** A simulated Jira site serving the demo dataset through the JiraReader / JiraWriter ports. */
public final class FakeJira implements JiraReader, JiraWriter {
    private static final Map<String, UserSnapshot> USERS = users();
    private static final TrackerSettings.FieldMapping FIELDS = new TrackerSettings.FieldMapping();
    public static final List<JiraField> FIELD_CATALOG = List.of(
            new JiraField("customfield_10015", "Start date", "date"), new JiraField("customfield_10016", "Planned End", "date"),
            new JiraField("customfield_10020", "Planned Start", "date"), new JiraField("duedate", "Due date", "date"),
            new JiraField("timeoriginalestimate", "Original estimate", "number"),
            new JiraField("customfield_10040", "Effort (h)", "number"), new JiraField("summary", "Summary", "string"));

    private static Map<String, UserSnapshot> users() {
        Map<String, UserSnapshot> u = new LinkedHashMap<>();
        Dataset.MEMBERS.forEach(m -> u.put(m.id(), new UserSnapshot(Dataset.accountOf(m.id()), m.fullName(), m.email())));
        return u;
    }

    private final Clock clock;
    /** In-memory site. Writes change the issues and mark them updated, like the real thing. */
    private final Map<String, FakeIssue> issues = new LinkedHashMap<>();
    private int nextKey = 1501;

    public FakeJira(Clock clock) {
        this.clock = clock;
        Dataset.buildIssues().forEach(i -> issues.put(i.key, i));
    }

    // --- JiraReader -------------------------------------------------------------------------------------------------

    private static UserSnapshot user(String memberId) {
        return memberId == null ? null : USERS.get(memberId);
    }

    private static String accountOrNull(String memberId) {
        return memberId == null || memberId.isEmpty() ? memberId : Dataset.accountOf(memberId);
    }

    private IssueSnapshot snapshot(FakeIssue i) {
        String[] status = Dataset.STATUS_NAMES.get(i.status);
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(FIELDS.plannedStart, i.ps);
        fields.put(FIELDS.plannedEnd, i.pe);
        fields.put(FIELDS.effort, i.estH != 0 ? (Object) (long) (i.estH * 3600) : null);
        List<ChangeSnapshot> changes = new ArrayList<>();
        for (FakeIssue.Change c : i.changes) {
            if (c.field().equals("assignee")) {
                changes.add(new ChangeSnapshot(c.at(), "assignee", accountOrNull(c.from()), accountOrNull(c.to()),
                        user(c.from()), user(c.to())));
            } else {
                changes.add(new ChangeSnapshot(c.at(), "status", c.from() != null ? Dataset.STATUS_NAMES.get(c.from())[0] : null,
                        c.to() != null ? Dataset.STATUS_NAMES.get(c.to())[0] : null));
            }
        }
        List<WorklogSnapshot> logs = i.worklogs.stream()
                .map(w -> new WorklogSnapshot(USERS.get(w.member()), w.date(), w.hours(), w.comment())).toList();
        return new IssueSnapshot(i.key, i.project(), i.summary, status[0], status[1], user(i.assignee), i.parent, i.created,
                i.updated, fields, logs, changes);
    }

    /** datetime.isoformat() of an aware UTC timestamp. */
    static String isoformat(Instant t) {
        OffsetDateTime o = t.atOffset(ZoneOffset.UTC);
        int micros = o.get(ChronoField.MICRO_OF_SECOND);
        return o.format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")) + (micros != 0 ? String.format(".%06d", micros) : "")
                + "+00:00";
    }

    @Override
    public synchronized List<IssueSnapshot> search(List<String> epicKeys, @Nullable Instant updatedSince) {
        Set<String> epics = new HashSet<>(epicKeys);
        Set<String> parents = new HashSet<>();
        issues.forEach((k, i) -> {
            if (epics.contains(i.parent)) {
                parents.add(k);
            }
        });
        String since = updatedSince != null ? isoformat(updatedSince) : null;
        List<IssueSnapshot> out = new ArrayList<>();
        for (FakeIssue i : issues.values()) {
            if ((epics.contains(i.parent) || parents.contains(i.parent)) && (since == null || i.updated.compareTo(since) >= 0)) {
                out.add(snapshot(i));
            }
        }
        return out;
    }

    @Override
    public Optional<EpicSnapshot> findEpic(String key) {
        return Optional.ofNullable(Dataset.EPICS.get(key)).map(e -> new EpicSnapshot(key, e.project(), e.summary(), e.dueDate()));
    }

    @Override
    public synchronized Optional<IssueSnapshot> findIssue(String key) {
        return Optional.ofNullable(issues.get(key)).map(this::snapshot);
    }

    @Override
    public List<JiraField> listFields() {
        return FIELD_CATALOG;
    }

    @Override
    public boolean testConnection() {
        return true;
    }

    // --- JiraWriter -------------------------------------------------------------------------------------------------

    private void touch(FakeIssue i) {
        i.updated = isoformat(Instant.now(clock));
    }

    private static String removeAccPrefix(String account) {
        return account.startsWith("acc-") ? account.substring(4) : account;
    }

    @Override
    public synchronized void updateIssue(String key, String plannedStart, String plannedEnd, String assigneeAccountId) {
        FakeIssue i = issues.get(key);
        if (i == null) {
            throw new IllegalArgumentException("KeyError: '" + key + "'");
        }
        if (plannedStart != null && !plannedStart.isEmpty()) {
            i.ps = plannedStart;
        }
        if (plannedEnd != null && !plannedEnd.isEmpty()) {
            i.pe = plannedEnd;
        }
        if (assigneeAccountId != null && !assigneeAccountId.isEmpty()) {
            String to = removeAccPrefix(assigneeAccountId);
            if (!to.equals(i.assignee)) {
                i.changes.add(new FakeIssue.Change(LocalDate.now(clock).toString(), "assignee", i.assignee, to));
                i.assignee = to;
            }
        }
        touch(i);
    }

    @Override
    public synchronized void transition(String key, Status status) {
        FakeIssue i = issues.get(key);
        if (i == null) {
            throw new IllegalArgumentException("KeyError: '" + key + "'");
        }
        if (!status.value().equals(i.status)) {
            i.changes.add(new FakeIssue.Change(LocalDate.now(clock).toString(), "status", i.status, status.value()));
            i.status = status.value();
        }
        touch(i);
    }

    @Override
    public synchronized List<String> createSubtasks(String parentKey, List<NewSubtask> subtasks) {
        List<String> keys = new ArrayList<>();
        String project = parentKey.split("-", -1)[0];
        for (NewSubtask s : subtasks) {
            String key = project + "-" + nextKey;
            nextKey++;
            String assignee = s.assigneeAccountId() == null ? "" : removeAccPrefix(s.assigneeAccountId());
            FakeIssue issue = new FakeIssue(key, s.summary(), parentKey, "N", assignee.isEmpty() ? null : assignee,
                    s.plannedStart(), s.plannedEnd(), s.estimateH(), LocalDate.now(clock).toString());
            issues.put(key, issue);
            touch(issue);
            keys.add(key);
        }
        return keys;
    }
}
