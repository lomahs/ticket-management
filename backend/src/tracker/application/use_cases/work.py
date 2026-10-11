"""My Work: a member's deadlines, backlog and the related steps of functions they own or review."""

from dataclasses import dataclass

from tracker.domain.dates import add_days, monday_of
from tracker.domain.model import Issue, Role, Status
from tracker.domain.steps import step_def

from ..context import Context, Deps, TaskCard, load_context
from ..errors import NotFound


@dataclass(frozen=True)
class BacklogGroup:
    kind: str  # overdue | week | month | unplanned
    start: str | None  # week start (YYYY-MM-DD) or month (YYYY-MM)
    current: bool
    items: list[TaskCard]
    remaining_h: float


@dataclass(frozen=True)
class MyWork:
    member_id: str
    today: str
    next_day: str
    delay: list[TaskCard]
    due_today: list[TaskCard]
    due_next: list[TaskCard]
    backlog: list[BacklogGroup]
    related: list[TaskCard]
    related_scope: str  # owner | reviewer
    open_count: int
    remaining_h: float


def _by_end(i: Issue) -> tuple[str, str]:
    return (i.planned_end or "9999", i.key)


def _is_review_item(ctx: Context, i: Issue) -> bool:
    step = step_def(i)
    if not step:
        return False
    f = ctx.flags[i.key]
    return (step.review and f.is_current and i.status in (Status.NYS, Status.WIP)) or (step.fix and i.status == Status.RESOLVED)


def my_work(d: Deps, member_id: str, backlog: str = "week") -> MyWork:
    today = d.clock.today()
    with d.uow() as u:
        ctx = load_context(u, today)
    me = ctx.members.get(member_id)
    if not me:
        raise NotFound(f"Member {member_id} was not found", "member")
    mine = sorted((i for i in ctx.subtasks if i.assignee_id == member_id and i.status != Status.CLOSED), key=_by_end)
    tasks = [i for i in mine if i.status != Status.RESOLVED or _is_review_item(ctx, i)]
    next_day = ctx.cal.next_work(add_days(today, 1), member_id)
    card = ctx.card

    groups: dict[tuple, list[Issue]] = {}
    this_week, this_month = monday_of(today), today[:7]
    for i in mine:
        pe = i.planned_end
        if pe is None:
            k = (3, "unplanned", None)
        elif pe < today and i.status != Status.RESOLVED:
            k = (0, "overdue", None)
        elif backlog == "month":
            k = (1, "month", pe[:7])
        else:
            k = (1, "week", monday_of(pe))
        groups.setdefault(k, []).append(i)
    backlog_groups = [
        BacklogGroup(kind, start, start in (this_week, this_month), [card(i) for i in items],
                     round(sum(card(i).remaining_h for i in items), 2))
        for (_, kind, start), items in sorted(groups.items(), key=lambda kv: (kv[0][0], kv[0][2] or ""))
    ]

    if me.role == Role.BAL:
        related = [c for f in ctx.functions if f.reviewer_id == member_id for idx in (0, 2, 3, 4) if (c := f.cells[idx])]
        scope = "reviewer"
    else:
        related = [c for f in ctx.functions if f.owner_id == member_id for c in f.cells[1:] if c]
        scope = "owner"
    related = sorted((c for c in related if c.status != Status.CLOSED), key=_by_end)

    return MyWork(
        member_id, today, next_day,
        delay=[card(i) for i in tasks if i.planned_end and i.planned_end < today],
        due_today=[card(i) for i in tasks if i.planned_end == today],
        due_next=[card(i) for i in tasks if i.planned_end == next_day],
        backlog=backlog_groups, related=[card(c) for c in related], related_scope=scope,
        open_count=len(mine), remaining_h=round(sum(card(i).remaining_h for i in mine), 2),
    )
