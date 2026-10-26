package tracker.adapters.fake;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoField;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import tracker.application.dto.ChangeSnapshot;
import tracker.application.dto.EpicSnapshot;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraField;
import tracker.application.dto.JiraIssueType;
import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraResponse;
import tracker.application.dto.JiraTransition;
import tracker.application.dto.UserSnapshot;
import tracker.application.dto.WorklogSnapshot;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.ports.JiraWriter;
import tracker.application.settings.TrackerSettings;

/** A simulated Jira site serving the demo dataset through the JiraReader / JiraWriter ports. */
public final class FakeJira implements JiraReader, JiraWriter {
    private static final Map<String, UserSnapshot> USERS = users();
    private static final TrackerSettings.FieldMapping FIELDS = new TrackerSettings.FieldMapping();
    public static final List<JiraField> FIELD_CATALOG = List.of(
            new JiraField("customfield_10015", "Start date", "date"), new JiraField("customfield_10016", "Planned End", "date"),
            new JiraField("customfield_10020", "Planned Start", "date"), new JiraField("duedate", "Due date", "date"),
            new JiraField("timeoriginalestimate", "Original estimate", "number"), new JiraField("timeestimate", "Remaining Estimate", "number"),
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
                i.updated, fields, logs, changes, user(i.reporter), i.labels);
    }

    /** An update time as the demo data writes it: ISO-8601 in UTC with a "+00:00" offset. */
    static String timestamp(Instant t) {
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
        String since = updatedSince != null ? timestamp(updatedSince) : null;
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

    /** Transition id → tracker status: every status can be reached from every other one. */
    private static final Map<String, String> TRANSITIONS = Map.of("11", "N", "21", "W", "31", "R", "41", "C");
    private static final Map<String, String> TRANSITION_NAMES = Map.of("N", "Reopen", "W", "Start progress", "R", "Resolve", "C", "Close");
    static final String TASK_TYPE = "10001";
    static final String SUBTASK_TYPE = "10003";

    @Override
    public synchronized List<JiraTransition> transitions(String key) {
        FakeIssue i = issues.get(key);
        if (i == null) {
            return List.of();
        }
        return TRANSITIONS.entrySet().stream().filter(e -> !e.getValue().equals(i.status)).sorted(Map.Entry.comparingByKey())
                .map(e -> {
                    String[] to = Dataset.STATUS_NAMES.get(e.getValue());
                    return new JiraTransition(e.getKey(), TRANSITION_NAMES.get(e.getValue()), to[0], to[1]);
                }).toList();
    }

    @Override
    public List<JiraIssueType> issueTypes(String project) {
        return List.of(new JiraIssueType(TASK_TYPE, "Task", false), new JiraIssueType(SUBTASK_TYPE, "Sub-task", true));
    }

    // --- JiraWriter -------------------------------------------------------------------------------------------------

    private void touch(FakeIssue i) {
        i.updated = timestamp(Instant.now(clock));
    }

    private static String removeAccPrefix(String account) {
        return account.startsWith("acc-") ? account.substring(4) : account;
    }

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern ISSUE = Pattern.compile("/rest/api/3/issue/([A-Z][A-Z0-9_]*-\\d+)");
    private static final Pattern TRANSITION = Pattern.compile("/rest/api/3/issue/([A-Z][A-Z0-9_]*-\\d+)/transitions");

    /** Applies the requests the app builds, like the REST API does: unknown fields and ids are refused with a 400. */
    @Override
    public synchronized JiraResponse send(JiraRequest request) {
        JsonNode body;
        try {
            body = request.body() == null ? JSON.createObjectNode() : JSON.readTree(request.body());
        } catch (JsonProcessingException e) {
            throw new JiraRejected(400, "Unexpected character in the request body");
        }
        Matcher m;
        if (request.method().equals("PUT") && (m = ISSUE.matcher(request.path())).matches()) {
            update(issue(m.group(1)), body.path("fields"));
            return new JiraResponse(204, "");
        }
        if (request.method().equals("POST") && (m = TRANSITION.matcher(request.path())).matches()) {
            transition(issue(m.group(1)), body.path("transition").path("id").asText());
            return new JiraResponse(204, "");
        }
        if (request.method().equals("POST") && request.path().equals("/rest/api/3/issue")) {
            String key = create(body.path("fields"));
            return new JiraResponse(201, "{\"id\":\"" + key.split("-", -1)[1] + "\",\"key\":\"" + key + "\"}");
        }
        throw new JiraRejected(404, "No such resource: " + request.method() + " " + request.path());
    }

    private FakeIssue issue(String key) {
        FakeIssue i = issues.get(key);
        if (i == null) {
            throw new JiraRejected(404, "Issue does not exist or you do not have permission to see it.");
        }
        return i;
    }

    private void update(FakeIssue i, JsonNode fields) {
        for (var it = fields.fields(); it.hasNext(); ) {
            var e = it.next();
            JsonNode v = e.getValue();
            switch (e.getKey()) {
                case "assignee" -> {
                    String to = v.isNull() ? null : removeAccPrefix(v.path("accountId").asText());
                    if (!java.util.Objects.equals(to, i.assignee)) {
                        i.changes.add(new FakeIssue.Change(LocalDate.now(clock).toString(), "assignee", i.assignee, to));
                        i.assignee = to;
                    }
                }
                case "reporter" -> i.reporter = v.isNull() ? null : removeAccPrefix(v.path("accountId").asText());
                case "timetracking" -> i.estH = minutes(v.path("originalEstimate")) / 60.0;
                default -> {
                    if (e.getKey().equals(FIELDS.plannedStart)) {
                        i.ps = v.isNull() ? null : v.asText();
                    } else if (e.getKey().equals(FIELDS.plannedEnd)) {
                        i.pe = v.isNull() ? null : v.asText();
                    } else {
                        throw new JiraRejected(400, "Field '" + e.getKey() + "' cannot be set. It is not on the appropriate screen, or unknown.");
                    }
                }
            }
        }
        touch(i);
    }

    /** "90m" → 90; null → 0. */
    private static double minutes(JsonNode v) {
        if (v.isNull() || v.isMissingNode()) {
            return 0;
        }
        String t = v.asText();
        if (!t.endsWith("m")) {
            throw new JiraRejected(400, "The original estimate specified is not valid.");
        }
        return Double.parseDouble(t.substring(0, t.length() - 1));
    }

    private void transition(FakeIssue i, String id) {
        String to = TRANSITIONS.get(id);
        if (to == null || to.equals(i.status)) {
            throw new JiraRejected(400, "Transition id '" + id + "' is not valid for this issue.");
        }
        i.changes.add(new FakeIssue.Change(LocalDate.now(clock).toString(), "status", i.status, to));
        i.status = to;
        touch(i);
    }

    private String create(JsonNode f) {
        String parentKey = f.path("parent").path("key").asText();
        FakeIssue parent = issue(parentKey);
        if (!SUBTASK_TYPE.equals(f.path("issuetype").path("id").asText())) {
            throw new JiraRejected(400, "Only sub-tasks can be created in the demo site");
        }
        String key = parent.project() + "-" + nextKey;
        nextKey++;
        String assignee = f.path("assignee").isObject() ? removeAccPrefix(f.path("assignee").path("accountId").asText()) : null;
        FakeIssue issue = new FakeIssue(key, f.path("summary").asText(), parentKey, "N", assignee,
                f.hasNonNull(FIELDS.plannedStart) ? f.get(FIELDS.plannedStart).asText() : null,
                f.hasNonNull(FIELDS.plannedEnd) ? f.get(FIELDS.plannedEnd).asText() : null,
                minutes(f.path("timetracking").path("originalEstimate")) / 60.0, LocalDate.now(clock).toString());
        issue.reporter = f.path("reporter").isObject() ? removeAccPrefix(f.path("reporter").path("accountId").asText()) : "minh";
        issues.put(key, issue);
        touch(issue);
        return key;
    }
}
