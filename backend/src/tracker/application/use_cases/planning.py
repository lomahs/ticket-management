"""Planning: the Gantt by member, previews of pending moves (dates, reassignment, capacity) and pushing them to Jira."""

from collections.abc import Sequence
from dataclasses import dataclass

from tracker.domain.calendar import WEEKEND
from tracker.domain.capacity import PlanItem, WeekLoad, weekly_capacity
from tracker.domain.dates import add_days, monday_of
from tracker.domain.model import LEAVE_TYPES, IssueKind
from tracker.domain.scheduling import shift_plan
from tracker.domain.steps import step_hint

from ..context import Context, Deps, TaskCard, load_context
from ..errors import CapabilityDisabled, Invalid

CAPACITY_WEEKS = 5


@dataclass(frozen=True)
class DayHead:
    date: str
    is_today: bool
    extra_note: str | None


@dataclass(frozen=True)
class DayCell:
    date: str
    kind: str | None  # holiday | leave | leave_half | None
    label: str | None
    tentative: bool


@dataclass(frozen=True)
class PlanBlock:
    card: TaskCard
    color_step: str  # step kind used for the block colour, or "other"


@dataclass(frozen=True)
class MemberLane:
    member_id: str
    days: list[DayCell]
    items: list[PlanBlock]


@dataclass(frozen=True)
class MemberCapacity:
    member_id: str
    weeks: list[WeekLoad]


@dataclass(frozen=True)
class PlanningView:
    start: str
    end: str
    days: list[DayHead]
    projects: list[str]
    rows: list[MemberLane]
    capacity: list[MemberCapacity]


@dataclass(frozen=True)
class PlanChange:
    key: str
    start: str
    member_id: str


@dataclass(frozen=True)
class ChangePreview:
    key: str
    from_start: str
    from_end: str
    from_member: str | None
    to_start: str
    to_end: str
    to_member: str
    day_off: list[tuple[str, str]]


@dataclass(frozen=True)
class PlanningPreview:
    changes: list[ChangePreview]
    capacity: list[MemberCapacity]


def _ctx(d: Deps) -> Context:
    with d.uow() as u:
        return load_context(u, d.clock.today())


def _day_cell(ctx: Context, mid: str, day: str) -> DayCell:
    h = ctx.cal.holiday_for(mid, day)
    if h:
        return DayCell(day, "holiday", h.name, False)
    lv = ctx.cal.leave(mid, day)
    if lv:
        info = LEAVE_TYPES[lv.type]
        return DayCell(day, "leave" if info.full else "leave_half", info.label, info.tentative)
    return DayCell(day, None, None, False)


def _planned(ctx: Context):
    return [i for i in ctx.issues if i.kind == IssueKind.SUBTASK and i.planned_start and i.planned_end]


def _capacity(ctx: Context, start: str, moved: dict[str, ChangePreview]) -> list[MemberCapacity]:
    items = []
    for i in _planned(ctx):
        c = moved.get(i.key)
        if c:
            items.append(PlanItem(i.key, c.to_member, c.to_start, c.to_end, i.estimate_h))
        elif ctx.worker(i):
            items.append(PlanItem(i.key, ctx.worker(i), i.planned_start, i.planned_end, i.estimate_h))
    wh = ctx.settings.working_hours
    loads = weekly_capacity(items, ctx.cal, list(ctx.members), start=monday_of(start), hours_per_day=wh.hours_per_day,
                            cap_pct=ctx.settings.thresholds.capacity_pct, weeks=CAPACITY_WEEKS)
    return [MemberCapacity(m, loads[m]) for m in ctx.members]


def view(d: Deps, start: str, weeks: int) -> PlanningView:
    if weeks not in (1, 2):
        raise Invalid("weeks must be 1 or 2", "weeks")
    ctx = _ctx(d)
    first = monday_of(start)
    last = add_days(first, weeks * 7 - 1)
    days = [add_days(first, n) for n in range(weeks * 7) if ctx.cal.visible(add_days(first, n))]
    rows = []
    for mid in ctx.members:
        items = [i for i in _planned(ctx) if ctx.worker(i) == mid and i.planned_end >= first and i.planned_start <= last]
        items.sort(key=lambda i: (i.planned_start, i.planned_end, i.key))
        blocks = [PlanBlock(ctx.card(i), k.value if (k := i.step or step_hint(i.summary)) else "other") for i in items]
        rows.append(MemberLane(mid, [_day_cell(ctx, mid, x) for x in days], blocks))
    return PlanningView(first, last, [DayHead(x, x == ctx.today, ctx.cal.extra_days.get(x)) for x in days],
                        sorted({a.project for a in ctx.anken}), rows, _capacity(ctx, first, {}))


def _previews(ctx: Context, changes: Sequence[PlanChange]) -> list[ChangePreview]:
    out = []
    for ch in changes:
        i = ctx.get(ch.key)
        if not i or not i.planned_start or not i.planned_end:
            raise Invalid(f"{ch.key} has no plan to move", "key")
        if ch.member_id not in ctx.members:
            raise Invalid(f"Unknown member {ch.member_id}", "member_id")
        to_start, to_end = shift_plan(i.planned_start, i.planned_end, ch.start)
        offs = [(x, "weekend" if r == WEEKEND else r) for x in dict.fromkeys((to_start, to_end)) if (r := ctx.cal.off(x, ch.member_id))]
        out.append(ChangePreview(i.key, i.planned_start, i.planned_end, ctx.worker(i), to_start, to_end, ch.member_id, offs))
    return out


def preview(d: Deps, start: str, changes: Sequence[PlanChange]) -> PlanningPreview:
    ctx = _ctx(d)
    pv = _previews(ctx, changes)
    return PlanningPreview(pv, _capacity(ctx, start, {c.key: c for c in pv}))


def push(d: Deps, changes: Sequence[PlanChange]) -> list[str]:
    if d.writer is None:
        raise CapabilityDisabled("Writing to Jira is not available yet")
    ctx = _ctx(d)
    pv = _previews(ctx, changes)
    for c in pv:
        account = ctx.members[c.to_member].jira_account_id if c.to_member != c.from_member else None
        if c.to_member != c.from_member and not account:
            raise Invalid(f"{ctx.members[c.to_member].name} has no Jira account", "member_id")
        d.writer.update_issue(c.key, planned_start=c.to_start, planned_end=c.to_end, assignee_account_id=account)
    keys = [c.key for c in pv]
    d.sync.refresh(keys)
    return keys
