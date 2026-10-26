package tracker.application.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.Conflict;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.JiraCalls;
import tracker.application.JiraRequests;
import tracker.application.JiraValues;
import tracker.application.SyncService;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.dto.IssueSnapshot;
import tracker.application.dto.JiraRequest;
import tracker.application.dto.JiraTransition;
import tracker.application.dto.UserSnapshot;
import tracker.application.ports.JiraReader;
import tracker.application.ports.JiraRejected;
import tracker.application.settings.TrackerSettings.FieldMapping;
import tracker.domain.Anken;
import tracker.domain.AnkenScope;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.Qa;
import tracker.domain.Status;
import tracker.domain.Steps;
import tracker.domain.Text;
import tracker.domain.TicketField;
import tracker.domain.Viewer;
import tracker.domain.WorkStructure;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Ticket panel (detail, reviewed edits written to Jira) and the Task Q&A bot. */
@Service
public class TicketService {
    private final TrackerContextLoader context;
    private final JiraCalls calls;
    private final JiraReader reader;
    private final SyncService sync;

    public TicketService(TrackerContextLoader context, JiraCalls calls, JiraReader reader, SyncService sync) {
        this.context = context;
        this.calls = calls;
        this.reader = reader;
        this.sync = sync;
    }

    /** @param kind late (days) | over (hours) | rounds (count) */
    public record TicketWarning(String kind, double value) {
    }

    public record Handover(String date, @Nullable String fromId, @Nullable String toId) {
    }

    /**
     * @param reporterId the reporter as last synced (the first one, when it was changed in Jira)
     * @param jiraUrl    the ticket in Jira; null when no Jira site is set
     */
    public record TicketDetail(TaskCard card, @Nullable String ownerId, @Nullable String reporterId, List<TicketWarning> warnings,
                               List<Handover> history, List<Worklog> worklogs, @Nullable String jiraUrl) {
    }

    /** A ticket outside the scope is not found. */
    public TicketDetail detail(AnkenScope scope, String key) {
        TrackerContext ctx = context.load(scope);
        Issue i = ctx.issue(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found", "key"));
        TaskCard c = ctx.card(i);
        List<TicketWarning> warns = new ArrayList<>();
        if (c.late()) {
            warns.add(new TicketWarning("late", c.lateDays()));
        }
        if (c.over()) {
            warns.add(new TicketWarning("over", Numbers.round(c.loggedH() - c.estimateH(), 2)));
        }
        if (Steps.isFixStep(i) && c.rounds() >= ctx.settings.thresholds.reviewRounds()) {
            warns.add(new TicketWarning("rounds", c.rounds()));
        }
        List<Handover> history = i.events().stream().sorted(Comparator.comparing(IssueEvent::at))
                .filter(e -> e.field() == EventField.ASSIGNEE)
                .map(e -> new Handover(e.at(), e.fromValue(), e.toValue())).toList();
        List<Worklog> logs = i.worklogs().stream().sorted(Comparator.comparing(Worklog::date).reversed()).toList();
        String site = ctx.settings.jira.url.strip().replaceAll("/+$", "");
        return new TicketDetail(c, ctx.owner.get(key), i.reporterId(), warns, history, logs, Text.hasText(site) ? site + "/browse/" + key : null);
    }

    /** What to change; fields that are null are left as they are. */
    public record TicketChanges(@Nullable Status status, @Nullable String assigneeId, @Nullable String reporterId,
                                @Nullable String plannedStart, @Nullable String plannedEnd, @Nullable Double estimateH) {
    }

    /**
     * One field that changes. Values are raw: a status (N/W/R/C), a member id (or the Jira account id of someone who is not a
     * member), YYYY-MM-DD, or hours.
     *
     * @param jiraField the Jira field written, as the review shows it
     * @param before    what Jira has now; null when empty
     */
    public record FieldChange(TicketField field, String jiraField, @Nullable String before, String after) {
    }

    /**
     * The review of a change: the fields that will change and the requests that will be sent, nothing else.
     *
     * @param transition       the workflow transition a status change uses, null without one
     * @param jiraStatus       the Jira status the transition leads to
     * @param estimateTracking whether the estimate is written through Jira's time tracking (which may move the remaining estimate)
     */
    public record TicketChangePreview(String key, String summary, List<FieldChange> changes, List<JiraRequest> requests,
                                @Nullable String transition, @Nullable String jiraStatus, boolean estimateTracking) {
    }

    /** A change as the review showed it; apply refuses it when Jira no longer has {@code before}. */
    public record ExpectedChange(TicketField field, @Nullable String before, String after) {
    }

    private static String hours(double h) {
        return BigDecimal.valueOf(h).stripTrailingZeros().toPlainString();
    }

    /** The member with this Jira account, else the account id itself. */
    private static @Nullable String person(TrackerContext ctx, @Nullable UserSnapshot u) {
        if (u == null || !Text.hasText(u.accountId())) {
            return null;
        }
        return ctx.members.values().stream().filter(m -> u.accountId().equals(m.jiraAccountId())).map(Member::id).findFirst()
                .orElse(u.accountId());
    }

    /** What Jira has now for each field the app can change. */
    private static Map<TicketField, @Nullable String> current(TrackerContext ctx, IssueSnapshot live) {
        FieldMapping fm = ctx.settings.fieldMapping;
        Map<TicketField, @Nullable String> now = new EnumMap<>(TicketField.class);
        now.put(TicketField.STATUS, SyncService.status(ctx.settings, live.statusName(), live.statusCategory()).value());
        now.put(TicketField.ASSIGNEE, person(ctx, live.assignee()));
        now.put(TicketField.REPORTER, person(ctx, live.reporter()));
        now.put(TicketField.PLANNED_START, JiraValues.date(live.fields().get(fm.plannedStart)));
        now.put(TicketField.PLANNED_END, JiraValues.date(live.fields().get(fm.plannedEnd)));
        Double h = JiraValues.hours(live.fields().get(fm.effort), fm.effort);
        now.put(TicketField.ESTIMATE, h == null ? null : hours(h));
        return now;
    }

    private static boolean same(TicketField f, @Nullable String a, @Nullable String b) {
        if (f == TicketField.ESTIMATE && a != null && b != null) {
            return Math.abs(Double.parseDouble(a) - Double.parseDouble(b)) < 1e-6;
        }
        return Objects.equals(a, b);
    }

    private static Member member(TrackerContext ctx, String memberId, String field) {
        Member m = ctx.members.get(memberId);
        if (m == null) {
            throw new Invalid("Unknown member " + memberId, field);
        }
        return m;
    }

    private static Member account(Member m, String field) {
        if (!Text.hasText(m.jiraAccountId())) {
            throw new Invalid(m.name() + " has no Jira account", field);
        }
        return m;
    }

    /** The review of {@code want} against what Jira has now: the same code builds what apply sends. */
    private TicketChangePreview plan(TrackerContext ctx, Issue issue, TicketChanges want) {
        String key = issue.key();
        IssueSnapshot live = reader.findIssue(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found in Jira", "key"));
        FieldMapping fm = ctx.settings.fieldMapping;
        Map<TicketField, @Nullable String> now = current(ctx, live);
        Map<TicketField, String> after = new EnumMap<>(TicketField.class);
        Map<TicketField, @Nullable Object> values = new EnumMap<>(TicketField.class);
        if (want.status() != null) {
            after.put(TicketField.STATUS, want.status().value());
        }
        if (Text.hasText(want.assigneeId())) {
            Member m = member(ctx, want.assigneeId(), "assignee_id");
            if (!m.id().equals(now.get(TicketField.ASSIGNEE)) && !ctx.isMember(issue.epicKey(), m.id())) {
                throw new Invalid(ctx.notAMember(issue.epicKey(), m.id()), "assignee_id");
            }
            account(m, "assignee_id");
            after.put(TicketField.ASSIGNEE, m.id());
            values.put(TicketField.ASSIGNEE, m.jiraAccountId());
        }
        if (Text.hasText(want.reporterId())) {
            Member m = account(member(ctx, want.reporterId(), "reporter_id"), "reporter_id");
            after.put(TicketField.REPORTER, m.id());
            values.put(TicketField.REPORTER, m.jiraAccountId());
        }
        if (Text.hasText(want.plannedStart())) {
            after.put(TicketField.PLANNED_START, want.plannedStart());
            values.put(TicketField.PLANNED_START, want.plannedStart());
        }
        if (Text.hasText(want.plannedEnd())) {
            after.put(TicketField.PLANNED_END, want.plannedEnd());
            values.put(TicketField.PLANNED_END, want.plannedEnd());
        }
        if (want.estimateH() != null) {
            if (!(want.estimateH() >= 0)) {
                throw new Invalid("Estimate must be 0 hours or more", "estimate_h");
            }
            after.put(TicketField.ESTIMATE, hours(want.estimateH()));
            values.put(TicketField.ESTIMATE, want.estimateH());
        }
        String start = after.getOrDefault(TicketField.PLANNED_START, now.get(TicketField.PLANNED_START));
        String end = after.getOrDefault(TicketField.PLANNED_END, now.get(TicketField.PLANNED_END));
        if (Text.hasText(start) && Text.hasText(end) && end.compareTo(start) < 0) {
            throw new Invalid("Planned End is before Planned Start", after.containsKey(TicketField.PLANNED_END) ? "planned_end" : "planned_start");
        }
        List<FieldChange> changes = new ArrayList<>();
        after.forEach((f, v) -> {
            if (!same(f, now.get(f), v)) {
                changes.add(new FieldChange(f, JiraRequests.jiraField(f, fm), now.get(f), v));
            } else {
                values.remove(f);
            }
        });
        List<JiraRequest> requests = new ArrayList<>();
        JiraRequests.update(key, values, fm).ifPresent(requests::add);
        String transition = null;
        String jiraStatus = null;
        if (changes.stream().anyMatch(c -> c.field() == TicketField.STATUS)) {
            Status target = want.status();
            JiraTransition t = reader.transitions(key).stream()
                    .filter(x -> SyncService.status(ctx.settings, x.toName(), x.toCategory()) == target).findFirst()
                    .orElseThrow(() -> new Invalid("Jira offers no transition from " + live.statusName() + " to a status that maps to "
                            + target.name().charAt(0) + target.name().substring(1).toLowerCase(java.util.Locale.ROOT), "status"));
            requests.add(JiraRequests.transition(key, t.id()));
            transition = t.name();
            jiraStatus = t.toName();
        }
        boolean tracking = changes.stream().anyMatch(c -> c.field() == TicketField.ESTIMATE) && "timeoriginalestimate".equals(fm.effort);
        return new TicketChangePreview(key, live.summary(), changes, requests, transition, jiraStatus, tracking);
    }

    private Issue ticket(TrackerContext ctx, String key) {
        return ctx.issue(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found", "key"));
    }

    /**
     * Reads the ticket from Jira and shows what {@code want} would change and the requests that would be sent. A ticket outside
     * the scope is not found; a new assignee must be a member of the ticket's anken.
     */
    public TicketChangePreview preview(AnkenScope scope, String key, TicketChanges want) {
        calls.requireEnabled();
        TrackerContext ctx = context.load(scope);
        return plan(ctx, ticket(ctx, key), want);
    }

    /**
     * Applies changes a review showed. Jira is read again first: when a {@code before} no longer matches, nothing is sent (each
     * request is logged as not sent) and the review has to be made again. Then the ticket is re-read from Jira.
     */
    public TicketDetail apply(Viewer actor, AnkenScope scope, String key, List<ExpectedChange> expected) {
        calls.requireEnabled();
        if (expected.isEmpty()) {
            throw new Invalid("There is nothing to apply", "changes");
        }
        TrackerContext ctx = context.load(scope);
        Issue issue = ticket(ctx, key);
        TicketChangePreview p = plan(ctx, issue, wanted(expected));
        List<String> moved = moved(p, expected);
        if (!moved.isEmpty()) {
            String reason = String.join(", ", moved) + " changed in Jira after the review";
            List<JiraRequest> unsent = p.requests().isEmpty() ? List.of(new JiraRequest("PUT", JiraRequests.ISSUE + "/" + key, null)) : p.requests();
            unsent.forEach(r -> calls.notSent(actor, JiraCalls.TICKET_PANEL, key, r, reason));
            throw new Conflict(key + ": " + reason + ". Review the change again.", moved.isEmpty() ? null : moved.get(0));
        }
        int sent = 0;
        try {
            for (JiraRequest r : p.requests()) {
                calls.send(actor, JiraCalls.TICKET_PANEL, key, r);
                sent++;
            }
        } catch (JiraRejected e) {
            throw sent == 0 ? e : new JiraRejected(e.status(), "Jira applied " + sent + " of " + p.requests().size()
                    + " requests, then refused the next: " + e.getMessage());
        } finally {
            if (sent > 0) {
                sync.refresh(List.of(key));
            }
        }
        return detail(scope, key);
    }

    // --- Tickets screen: the Tasks and sub-tasks of an anken, edited and created in one reviewed batch -----------------

    /** @param reporterId the reporter as last synced (the first one, when it was changed in Jira) */
    public record BoardRow(String key, String summary, String status, @Nullable String assigneeId, @Nullable String reporterId,
                           @Nullable String plannedStart, @Nullable String plannedEnd, double estimateH, @Nullable String step) {
    }

    /** @param workflow whether its sub-tasks follow the 5-step review workflow (new ones are then created as functions) */
    public record BoardTask(BoardRow task, String phase, boolean workflow, List<BoardRow> subtasks) {
    }

    /** A Task of the anken's fixed set (Settings › Phases & tasks); {@code exists} when a Task of the epic matches it. */
    public record BoardTemplate(String match, String phase, boolean workflow, boolean exists) {
    }

    /** @param site the Jira site, for links and the JQL hand-off; null when none is set */
    public record TicketBoard(String epic, String title, String project, @Nullable String site, List<BoardTask> tasks,
                              List<BoardTemplate> templates) {
    }

    private static BoardRow row(Issue i) {
        return new BoardRow(i.key(), i.summary(), i.status().value(), i.assigneeId(), i.reporterId(), i.plannedStart(), i.plannedEnd(),
                i.estimateH(), i.step() != null ? i.step().value() : null);
    }

    private static Anken epicOf(TrackerContext ctx, String epic) {
        return ctx.anken(epic).orElseThrow(() -> new NotFound("Anken " + epic + " is not tracked", "anken"));
    }

    /** The Tasks of the epic in phase order (configured phases first), each with its sub-tasks by planned start. */
    public TicketBoard board(AnkenScope scope, String epic) {
        TrackerContext ctx = context.load(scope);
        Anken a = epicOf(ctx, epic);
        WorkStructure st = ctx.settings.structureOf(epic);
        List<Issue> tasks = ctx.parents().stream().filter(p -> p.epicKey().equals(epic)).toList();
        List<String> phases = st.orderedPhases(tasks.stream().map(Issue::phase).filter(Text::hasText).toList());
        List<BoardTask> out = tasks.stream()
                .sorted(Comparator.comparingInt((Issue t) -> phases.indexOf(t.phase()) < 0 ? Integer.MAX_VALUE : phases.indexOf(t.phase()))
                        .thenComparing(Issue::key))
                .map(t -> new BoardTask(row(t), t.phase(), st.hasWorkflow(t.summary()), ctx.subtasks().stream()
                        .filter(c -> t.key().equals(c.parentKey()))
                        .sorted(Comparator.comparing((Issue c) -> Text.orElse(c.plannedStart(), "9999")).thenComparing(Issue::key))
                        .map(TicketService::row).toList()))
                .toList();
        List<BoardTemplate> templates = st.tasks().stream().map(tt -> new BoardTemplate(tt.match(), tt.phase(), tt.workflow(),
                tasks.stream().anyMatch(t -> tt.equals(st.taskTemplate(t.summary()))))).toList();
        String site = ctx.settings.jira.url.strip().replaceAll("/+$", "");
        return new TicketBoard(epic, a.title(), a.project(), Text.hasText(site) ? site : null, out, templates);
    }

    /**
     * A Task (under the epic) or a sub-task to create.
     *
     * @param ref       the client's id for it, so a sub-task can name a new Task as {@code parentRef}
     * @param parentKey the Task of a new sub-task, when it exists already
     */
    public record NewTicket(String ref, boolean subtask, @Nullable String parentKey, @Nullable String parentRef, String summary,
                            String assigneeId, @Nullable String reporterId, @Nullable String plannedStart, @Nullable String plannedEnd,
                            @Nullable Double estimateH) {
    }

    /** @param parent the Task (or epic) key, or {@code <new:ref>} for a Task created in the same batch */
    public record NewTicketPreview(String ref, boolean subtask, String parent, String summary, JiraRequest request) {
    }

    public record TicketUpdate(String key, TicketChanges changes) {
    }

    /** The review of a batch: the tickets that change (with their requests) and the requests that create new ones. */
    public record BatchPreview(List<TicketChangePreview> updates, List<NewTicketPreview> creates) {
    }

    public record ExpectedUpdate(String key, List<ExpectedChange> changes) {
    }

    /** @param created the client's ref of each new ticket → its Jira key */
    public record BatchResult(List<String> updated, Map<String, String> created) {
    }

    private static String placeholder(String ref) {
        return "<new:" + ref + ">";
    }

    /** Checks the new tickets and builds their requests; a sub-task under a new Task names it by {@link #placeholder}. */
    private List<NewTicketPreview> creates(TrackerContext ctx, String epic, List<NewTicket> creates) {
        if (creates.isEmpty()) {
            return List.of();
        }
        Anken a = epicOf(ctx, epic);
        FieldMapping fm = ctx.settings.fieldMapping;
        List<tracker.application.dto.JiraIssueType> types = reader.issueTypes(a.project());
        String taskType = types.stream().filter(t -> !t.subtask() && t.name().equalsIgnoreCase("Task")).map(tracker.application.dto.JiraIssueType::id)
                .findFirst().orElse(null);
        String subtaskType = types.stream().filter(tracker.application.dto.JiraIssueType::subtask).map(tracker.application.dto.JiraIssueType::id)
                .findFirst().orElse(null);
        Map<String, NewTicket> newTasks = new LinkedHashMap<>();
        List<NewTicketPreview> out = new ArrayList<>();
        for (int n = 0; n < creates.size(); n++) {
            NewTicket c = creates.get(n);
            String at = "creates." + n + ".";
            if (!Text.hasText(c.summary())) {
                throw new Invalid("Summary is required", at + "summary");
            }
            String parent;
            if (c.subtask()) {
                if (Text.hasText(c.parentRef())) {
                    if (!newTasks.containsKey(c.parentRef())) {
                        throw new Invalid("The new Task of a sub-task must come before it", at + "parent_ref");
                    }
                    parent = placeholder(c.parentRef());
                } else {
                    Issue p = ctx.issue(Text.orElse(c.parentKey(), "")).filter(i -> i.kind() == tracker.domain.IssueKind.PARENT && epic.equals(i.epicKey()))
                            .orElseThrow(() -> new Invalid(c.parentKey() + " is not a Task of " + epic, at + "parent_key"));
                    parent = p.key();
                }
                if (subtaskType == null) {
                    throw new Invalid("Project " + a.project() + " has no sub-task issue type", at + "subtask");
                }
            } else {
                parent = epic;
                if (taskType == null) {
                    throw new Invalid("Project " + a.project() + " has no issue type named Task", at + "subtask");
                }
                newTasks.put(c.ref(), c);
            }
            Member assignee = member(ctx, c.assigneeId(), at + "assignee_id");
            if (!ctx.isMember(epic, assignee.id())) {
                throw new Invalid(ctx.notAMember(epic, assignee.id()), at + "assignee_id");
            }
            Map<TicketField, @Nullable Object> values = new EnumMap<>(TicketField.class);
            values.put(TicketField.ASSIGNEE, account(assignee, at + "assignee_id").jiraAccountId());
            if (Text.hasText(c.reporterId())) {
                values.put(TicketField.REPORTER, account(member(ctx, c.reporterId(), at + "reporter_id"), at + "reporter_id").jiraAccountId());
            }
            if (Text.hasText(c.plannedStart()) && Text.hasText(c.plannedEnd()) && c.plannedEnd().compareTo(c.plannedStart()) < 0) {
                throw new Invalid("Planned End is before Planned Start", at + "planned_end");
            }
            if (Text.hasText(c.plannedStart())) {
                values.put(TicketField.PLANNED_START, c.plannedStart());
            }
            if (Text.hasText(c.plannedEnd())) {
                values.put(TicketField.PLANNED_END, c.plannedEnd());
            }
            if (c.estimateH() != null) {
                if (!(c.estimateH() >= 0)) {
                    throw new Invalid("Estimate must be 0 hours or more", at + "estimate_h");
                }
                values.put(TicketField.ESTIMATE, c.estimateH());
            }
            out.add(new NewTicketPreview(c.ref(), c.subtask(), parent, c.summary().strip(),
                    JiraRequests.createIssue(a.project(), c.subtask() ? subtaskType : taskType, parent, c.summary().strip(), values, fm)));
        }
        return out;
    }

    private Issue epicTicket(TrackerContext ctx, String epic, String key) {
        Issue i = ticket(ctx, key);
        if (!epic.equals(i.epicKey())) {
            throw new Invalid(key + " is not a ticket of " + epic, "updates");
        }
        return i;
    }

    /** Reads every changed ticket from Jira and shows what would change and be created, with the requests. */
    public BatchPreview previewBatch(AnkenScope scope, String epic, List<TicketUpdate> updates, List<NewTicket> newTickets) {
        calls.requireEnabled();
        TrackerContext ctx = context.load(scope);
        epicOf(ctx, epic);
        List<TicketChangePreview> changed = new ArrayList<>();
        for (TicketUpdate u : updates) {
            TicketChangePreview p = plan(ctx, epicTicket(ctx, epic, u.key()), u.changes());
            if (!p.changes().isEmpty()) {
                changed.add(p);
            }
        }
        return new BatchPreview(changed, creates(ctx, epic, newTickets));
    }

    private static TicketChanges wanted(List<ExpectedChange> expected) {
        Map<TicketField, String> after = new LinkedHashMap<>();
        for (ExpectedChange e : expected) {
            if (after.put(e.field(), e.after()) != null) {
                throw new Invalid(e.field() + " is listed twice", "changes");
            }
        }
        try {
            String est = after.get(TicketField.ESTIMATE);
            return new TicketChanges(after.containsKey(TicketField.STATUS) ? Status.of(after.get(TicketField.STATUS)) : null,
                    after.get(TicketField.ASSIGNEE), after.get(TicketField.REPORTER), after.get(TicketField.PLANNED_START),
                    after.get(TicketField.PLANNED_END), est == null ? null : Double.valueOf(est));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage(), "changes");
        }
    }

    /** The fields whose {@code before} Jira no longer has, or that no longer change. */
    private static List<String> moved(TicketChangePreview p, List<ExpectedChange> expected) {
        List<String> moved = new ArrayList<>();
        for (ExpectedChange e : expected) {
            FieldChange now = p.changes().stream().filter(c -> c.field() == e.field()).findFirst().orElse(null);
            if (now == null || !same(e.field(), now.before(), e.before())) {
                moved.add(e.field().value());
            }
        }
        if (moved.isEmpty() && p.changes().size() != expected.size()) {
            p.changes().stream().filter(c -> expected.stream().noneMatch(e -> e.field() == c.field())).forEach(c -> moved.add(c.field().value()));
        }
        return moved;
    }

    /**
     * Applies a reviewed batch: every updated ticket is read again first and, if any changed in Jira since the review, nothing
     * at all is sent. Then the updates, the new Tasks and the new sub-tasks (under the keys their Tasks got), one logged request
     * each. Everything touched is re-read from Jira, also when Jira refuses a request half-way.
     */
    public BatchResult applyBatch(Viewer actor, AnkenScope scope, String epic, List<ExpectedUpdate> updates, List<NewTicket> newTickets) {
        calls.requireEnabled();
        if (updates.isEmpty() && newTickets.isEmpty()) {
            throw new Invalid("There is nothing to apply", "updates");
        }
        TrackerContext ctx = context.load(scope);
        epicOf(ctx, epic);
        List<TicketChangePreview> plans = new ArrayList<>();
        List<String> conflicts = new ArrayList<>();
        for (ExpectedUpdate u : updates) {
            TicketChangePreview p = plan(ctx, epicTicket(ctx, epic, u.key()), wanted(u.changes()));
            List<String> moved = moved(p, u.changes());
            if (!moved.isEmpty()) {
                conflicts.add(u.key() + " (" + String.join(", ", moved) + ")");
            }
            plans.add(p);
        }
        List<NewTicketPreview> creates = creates(ctx, epic, newTickets);
        if (!conflicts.isEmpty()) {
            String reason = "changed in Jira after the review: " + String.join(", ", conflicts);
            plans.forEach(p -> p.requests().forEach(r -> calls.notSent(actor, JiraCalls.TICKETS, p.key(), r, reason)));
            creates.forEach(c -> calls.notSent(actor, JiraCalls.TICKETS, c.parent(), c.request(), reason));
            throw new Conflict(reason.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + reason.substring(1) + ". Review the batch again.", null);
        }
        int total = plans.stream().mapToInt(p -> p.requests().size()).sum() + creates.size();
        List<String> touched = new ArrayList<>();
        Map<String, String> created = new LinkedHashMap<>();
        int sent = 0;
        try {
            for (TicketChangePreview p : plans) {
                for (JiraRequest r : p.requests()) {
                    calls.send(actor, JiraCalls.TICKETS, p.key(), r);
                    sent++;
                }
                touched.add(p.key());
            }
            for (NewTicketPreview c : creates) {
                JiraRequest r = c.request();
                String parent = c.parent();
                if (parent.startsWith("<new:")) {
                    String key = created.get(parent.substring(5, parent.length() - 1));
                    r = new JiraRequest(r.method(), r.path(), r.body().replace("\"" + parent + "\"", "\"" + key + "\""));
                    parent = key;
                }
                String key = createdKey(calls.send(actor, JiraCalls.TICKETS, parent, r));
                sent++;
                created.put(c.ref(), key);
                touched.add(key);
            }
        } catch (JiraRejected e) {
            throw sent == 0 ? e : new JiraRejected(e.status(), "Jira applied " + sent + " of " + total + " requests, then refused the next: "
                    + e.getMessage());
        } finally {
            if (!touched.isEmpty()) {
                sync.refresh(touched);
            }
        }
        return new BatchResult(plans.stream().map(TicketChangePreview::key).toList(), created);
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    /** The key in Jira's answer to a create. */
    private static String createdKey(tracker.application.dto.JiraResponse r) {
        try {
            return JSON.readTree(r.body()).path("key").asText();
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new tracker.application.ports.JiraError("Jira returned invalid JSON for a created issue: " + e.getMessage(), e);
        }
    }

    /** Answers from the anken in scope only. */
    public Qa.Answer ask(AnkenScope scope, String question) {
        if (question.isBlank()) {
            throw new Invalid("Ask a question", "q");
        }
        TrackerContext ctx = context.load(scope);
        WorkingHours wh = ctx.settings.workingHours;
        List<Worklog> logs = ctx.issues.stream().flatMap(i -> i.worklogs().stream()).toList();
        return Qa.answer(new Qa.QaContext(ctx.functions, ctx.flags, ctx.members, logs, ctx.today, wh.hoursPerDay(), wh.days()),
                question);
    }
}
