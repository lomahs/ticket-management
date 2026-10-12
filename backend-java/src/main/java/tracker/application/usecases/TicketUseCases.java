package tracker.application.usecases;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.Context;
import tracker.application.Deps;
import tracker.application.TaskCard;
import tracker.application.ports.UnitOfWork;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.Member;
import tracker.domain.Num;
import tracker.domain.Py;
import tracker.domain.Qa;
import tracker.domain.Status;
import tracker.domain.Steps;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Ticket panel (detail, edits written to Jira) and the Task Q&A bot. */
public final class TicketUseCases {
    private TicketUseCases() {
    }

    /** @param kind late (days) | over (hours) | rounds (count) */
    public record TicketWarning(String kind, double value) {
    }

    public record Handover(String date, String fromId, String toId) {
    }

    public record TicketDetail(TaskCard card, String ownerId, List<TicketWarning> warnings, List<Handover> history,
                               List<Worklog> worklogs) {
    }

    public static TicketDetail detail(Deps d, String key) {
        Context ctx;
        try (UnitOfWork u = d.uow().begin()) {
            ctx = Context.load(u, d.clock().today());
        }
        Issue i = ctx.get(key);
        if (i == null) {
            throw new NotFound("Ticket " + key + " was not found", "key");
        }
        TaskCard c = ctx.card(i);
        List<TicketWarning> warns = new ArrayList<>();
        if (c.late()) {
            warns.add(new TicketWarning("late", c.lateDays()));
        }
        if (c.over()) {
            warns.add(new TicketWarning("over", Num.round(c.loggedH() - c.estimateH(), 2)));
        }
        if (Steps.isFixStep(i) && c.rounds() >= ctx.settings.thresholds.reviewRounds()) {
            warns.add(new TicketWarning("rounds", c.rounds()));
        }
        List<Handover> history = i.events().stream().sorted(Comparator.comparing(IssueEvent::at))
                .filter(e -> e.field() == EventField.ASSIGNEE)
                .map(e -> new Handover(e.at(), e.fromValue(), e.toValue())).toList();
        List<Worklog> logs = i.worklogs().stream().sorted(Comparator.comparing(Worklog::date).reversed()).toList();
        return new TicketDetail(c, ctx.owner.get(key), warns, history, logs);
    }

    public record TicketPatch(String status, String assigneeId, String plannedStart, String plannedEnd) {
    }

    public static TicketDetail patch(Deps d, String key, TicketPatch p) {
        if (d.writer() == null) {
            throw new CapabilityDisabled("Writing to Jira is not available yet");
        }
        Issue issue;
        Member member;
        try (UnitOfWork u = d.uow().begin()) {
            issue = u.issues().get(key);
            member = Py.truthy(p.assigneeId()) ? u.members().get(p.assigneeId()) : null;
        }
        if (issue == null) {
            throw new NotFound("Ticket " + key + " was not found", "key");
        }
        if (Py.truthy(p.assigneeId())) {
            if (member == null) {
                throw new Invalid("Unknown member " + p.assigneeId(), "assignee_id");
            }
            if (!Py.truthy(member.jiraAccountId())) {
                throw new Invalid(member.name() + " has no Jira account", "assignee_id");
            }
        }
        String start = Py.or(p.plannedStart(), issue.plannedStart());
        String end = Py.or(p.plannedEnd(), issue.plannedEnd());
        if (Py.truthy(start) && Py.truthy(end) && end.compareTo(start) < 0) {
            throw new Invalid("Planned End is before Planned Start", Py.truthy(p.plannedEnd()) ? "planned_end" : "planned_start");
        }
        if (Py.truthy(p.status())) {
            Status status;
            try {
                status = Status.of(p.status());
            } catch (IllegalArgumentException e) {
                throw new Invalid("Unknown status " + p.status(), "status");
            }
            d.writer().transition(key, status);
        }
        if (Py.truthy(p.plannedStart()) || Py.truthy(p.plannedEnd()) || Py.truthy(p.assigneeId())) {
            d.writer().updateIssue(key, p.plannedStart(), p.plannedEnd(), member != null ? member.jiraAccountId() : null);
        }
        d.sync().refresh(List.of(key));
        return detail(d, key);
    }

    public static Qa.Answer ask(Deps d, String question) {
        if (question.strip().isEmpty()) {
            throw new Invalid("Ask a question", "q");
        }
        Context ctx;
        try (UnitOfWork u = d.uow().begin()) {
            ctx = Context.load(u, d.clock().today());
        }
        WorkingHours wh = ctx.settings.workingHours;
        List<Worklog> logs = ctx.issues.stream().flatMap(i -> i.worklogs().stream()).toList();
        return Qa.answer(new Qa.QaContext(ctx.functions, ctx.flags, ctx.members, logs, ctx.today, wh.hoursPerDay(), wh.days()),
                question);
    }
}
