"""Ticket panel (detail, edits written to Jira) and the Task Q&A bot."""

from dataclasses import dataclass

from tracker.domain.model import EventField, Status, Worklog
from tracker.domain.qa import Answer, QaContext, answer
from tracker.domain.steps import is_fix_step

from ..context import Deps, TaskCard, load_context
from ..errors import CapabilityDisabled, Invalid, NotFound


@dataclass(frozen=True)
class TicketWarning:
    kind: str  # late (days) | over (hours) | rounds (count)
    value: float


@dataclass(frozen=True)
class Handover:
    date: str
    from_id: str | None
    to_id: str | None


@dataclass(frozen=True)
class TicketDetail:
    card: TaskCard
    owner_id: str | None
    warnings: list[TicketWarning]
    history: list[Handover]
    worklogs: list[Worklog]


def detail(d: Deps, key: str) -> TicketDetail:
    with d.uow() as u:
        ctx = load_context(u, d.clock.today())
    i = ctx.get(key)
    if not i:
        raise NotFound(f"Ticket {key} was not found", "key")
    c = ctx.card(i)
    warns = []
    if c.late:
        warns.append(TicketWarning("late", c.late_days))
    if c.over:
        warns.append(TicketWarning("over", round(c.logged_h - c.estimate_h, 2)))
    if is_fix_step(i) and c.rounds >= ctx.settings.thresholds.review_rounds:
        warns.append(TicketWarning("rounds", c.rounds))
    history = [Handover(e.at, e.from_value, e.to_value) for e in sorted(i.events, key=lambda e: e.at) if e.field == EventField.ASSIGNEE]
    logs = sorted(i.worklogs, key=lambda w: w.date, reverse=True)
    return TicketDetail(c, ctx.owner.get(key), warns, history, logs)


@dataclass(frozen=True)
class TicketPatch:
    status: str | None = None
    assignee_id: str | None = None
    planned_start: str | None = None
    planned_end: str | None = None


def patch(d: Deps, key: str, p: TicketPatch) -> TicketDetail:
    if d.writer is None:
        raise CapabilityDisabled("Writing to Jira is not available yet")
    with d.uow() as u:
        issue = u.issues.get(key)
        member = u.members.get(p.assignee_id) if p.assignee_id else None
    if not issue:
        raise NotFound(f"Ticket {key} was not found", "key")
    if p.assignee_id:
        if not member:
            raise Invalid(f"Unknown member {p.assignee_id}", "assignee_id")
        if not member.jira_account_id:
            raise Invalid(f"{member.name} has no Jira account", "assignee_id")
    start = p.planned_start or issue.planned_start
    end = p.planned_end or issue.planned_end
    if start and end and end < start:
        raise Invalid("Planned End is before Planned Start", "planned_end" if p.planned_end else "planned_start")
    if p.status:
        try:
            status = Status(p.status)
        except ValueError:
            raise Invalid(f"Unknown status {p.status}", "status") from None
        d.writer.transition(key, status)
    if p.planned_start or p.planned_end or p.assignee_id:
        d.writer.update_issue(key, planned_start=p.planned_start, planned_end=p.planned_end,
                              assignee_account_id=member.jira_account_id if member else None)
    d.sync.refresh([key])
    return detail(d, key)


def ask(d: Deps, question: str) -> Answer:
    if not question.strip():
        raise Invalid("Ask a question", "q")
    with d.uow() as u:
        ctx = load_context(u, d.clock.today())
    wh = ctx.settings.working_hours
    logs = [w for i in ctx.issues for w in i.worklogs]
    return answer(QaContext(ctx.functions, ctx.flags, ctx.members, logs, ctx.today, wh.hours_per_day, wh.days), question)
