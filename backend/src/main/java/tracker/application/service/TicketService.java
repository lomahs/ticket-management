package tracker.application.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import tracker.application.AppError.CapabilityDisabled;
import tracker.application.AppError.Invalid;
import tracker.application.AppError.NotFound;
import tracker.application.SyncService;
import tracker.application.TaskCard;
import tracker.application.TrackerContext;
import tracker.application.TrackerContextLoader;
import tracker.application.ports.IssueRepository;
import tracker.application.ports.JiraWriter;
import tracker.application.ports.MemberRepository;
import tracker.domain.EventField;
import tracker.domain.Issue;
import tracker.domain.IssueEvent;
import tracker.domain.Member;
import tracker.domain.Numbers;
import tracker.domain.Qa;
import tracker.domain.Status;
import tracker.domain.Steps;
import tracker.domain.Text;
import tracker.domain.WorkingHours;
import tracker.domain.Worklog;

/** Ticket panel (detail, edits written to Jira) and the Task Q&A bot. */
@Service
public class TicketService {
    private final TrackerContextLoader context;
    private final IssueRepository issues;
    private final MemberRepository members;
    private final Optional<JiraWriter> writer;
    private final SyncService sync;

    public TicketService(TrackerContextLoader context, IssueRepository issues, MemberRepository members,
                         Optional<JiraWriter> writer, SyncService sync) {
        this.context = context;
        this.issues = issues;
        this.members = members;
        this.writer = writer;
        this.sync = sync;
    }

    /** @param kind late (days) | over (hours) | rounds (count) */
    public record TicketWarning(String kind, double value) {
    }

    public record Handover(String date, @Nullable String fromId, @Nullable String toId) {
    }

    /** @param jiraUrl the ticket in Jira; null when no Jira site is set */
    public record TicketDetail(TaskCard card, @Nullable String ownerId, List<TicketWarning> warnings, List<Handover> history,
                               List<Worklog> worklogs, @Nullable String jiraUrl) {
    }

    public TicketDetail detail(String key) {
        TrackerContext ctx = context.load();
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
        return new TicketDetail(c, ctx.owner.get(key), warns, history, logs, Text.hasText(site) ? site + "/browse/" + key : null);
    }

    /** Fields that are null are not changed. */
    public record TicketPatch(@Nullable Status status, @Nullable String assigneeId, @Nullable String plannedStart,
                              @Nullable String plannedEnd) {
    }

    /** Writes the changes to Jira, then re-reads the ticket from it. */
    public TicketDetail patch(String key, TicketPatch p) {
        JiraWriter jira = writer.orElseThrow(() -> new CapabilityDisabled("Writing to Jira is not available yet"));
        Issue issue = issues.findByKey(key).orElseThrow(() -> new NotFound("Ticket " + key + " was not found", "key"));
        Member member = null;
        if (Text.hasText(p.assigneeId())) {
            member = members.findById(p.assigneeId())
                    .orElseThrow(() -> new Invalid("Unknown member " + p.assigneeId(), "assignee_id"));
            if (!Text.hasText(member.jiraAccountId())) {
                throw new Invalid(member.name() + " has no Jira account", "assignee_id");
            }
        }
        String start = Text.orElse(p.plannedStart(), issue.plannedStart());
        String end = Text.orElse(p.plannedEnd(), issue.plannedEnd());
        if (Text.hasText(start) && Text.hasText(end) && end.compareTo(start) < 0) {
            throw new Invalid("Planned End is before Planned Start", Text.hasText(p.plannedEnd()) ? "planned_end" : "planned_start");
        }
        if (p.status() != null) {
            jira.transition(key, p.status());
        }
        if (Text.hasText(p.plannedStart()) || Text.hasText(p.plannedEnd()) || member != null) {
            jira.updateIssue(key, p.plannedStart(), p.plannedEnd(), member != null ? member.jiraAccountId() : null);
        }
        sync.refresh(List.of(key));
        return detail(key);
    }

    public Qa.Answer ask(String question) {
        if (question.isBlank()) {
            throw new Invalid("Ask a question", "q");
        }
        TrackerContext ctx = context.load();
        WorkingHours wh = ctx.settings.workingHours;
        List<Worklog> logs = ctx.issues.stream().flatMap(i -> i.worklogs().stream()).toList();
        return Qa.answer(new Qa.QaContext(ctx.functions, ctx.flags, ctx.members, logs, ctx.today, wh.hoursPerDay(), wh.days()),
                question);
    }
}
