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
        Map<TicketField, String> after = new LinkedHashMap<>();
        for (ExpectedChange e : expected) {
            if (after.put(e.field(), e.after()) != null) {
                throw new Invalid(e.field() + " is listed twice", "changes");
            }
        }
        TicketChanges want;
        try {
            String est = after.get(TicketField.ESTIMATE);
            want = new TicketChanges(after.containsKey(TicketField.STATUS) ? Status.of(after.get(TicketField.STATUS)) : null,
                    after.get(TicketField.ASSIGNEE), after.get(TicketField.REPORTER), after.get(TicketField.PLANNED_START),
                    after.get(TicketField.PLANNED_END), est == null ? null : Double.valueOf(est));
        } catch (IllegalArgumentException e) {
            throw new Invalid(e.getMessage(), "changes");
        }
        TicketChangePreview p = plan(ctx, issue, want);
        List<String> moved = new ArrayList<>();
        for (ExpectedChange e : expected) {
            FieldChange now = p.changes().stream().filter(c -> c.field() == e.field()).findFirst().orElse(null);
            if (now == null || !same(e.field(), now.before(), e.before())) {
                moved.add(e.field().value());
            }
        }
        if (moved.size() > 0 || p.changes().size() != expected.size()) {
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
