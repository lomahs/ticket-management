"""Effort: timesheet (logged vs planned vs expected), one member-day in detail, and estimate/rework analysis."""

from collections import defaultdict
from dataclasses import dataclass

from tracker.domain.analysis import EffortBar, Rework, estimate_vs_logged, rework_by_member
from tracker.domain.capacity import PlanItem, Plan, daily_plan
from tracker.domain.dates import day_diff, days_between
from tracker.domain.model import Role, Status, Worklog
from tracker.domain.overview import ordered_phases
from tracker.domain.steps import STEP_BY_KIND, STEPS
from tracker.domain.timesheet import TimesheetRow, day_detail as domain_day_detail, timesheet as domain_timesheet

from ..context import Context, Deps, load_context
from ..errors import Invalid, NotFound
from .planning import DayHead

MAX_DAYS = 62


@dataclass(frozen=True)
class TimesheetView:
    start: str
    end: str
    days: list[DayHead]
    rows: list[TimesheetRow]


@dataclass(frozen=True)
class DayEntryView:
    key: str
    summary: str
    function_id: str | None
    step: str | None
    logged_h: float
    planned_h: float
    notes: list[str]


@dataclass(frozen=True)
class DayDetailView:
    member_id: str
    date: str
    logged_h: float
    planned_h: float
    entries: list[DayEntryView]


def _ctx(d: Deps) -> Context:
    with d.uow() as u:
        return load_context(u, d.clock.today())


def _plan(ctx: Context) -> Plan:
    return daily_plan([PlanItem(i.key, w, i.planned_start, i.planned_end, i.estimate_h) for i in ctx.subtasks
                       if i.planned_start and i.planned_end and (w := ctx.worker(i))], ctx.cal)


def timesheet(d: Deps, start: str, end: str) -> TimesheetView:
    if end < start or day_diff(start, end) >= MAX_DAYS:
        raise Invalid(f"Choose a range of 1 to {MAX_DAYS} days", "to")
    ctx = _ctx(d)
    days = [x for x in days_between(start, end) if ctx.cal.visible(x)]
    logs: dict[tuple[str, str], list[Worklog]] = defaultdict(list)
    for i in ctx.issues:
        for w in i.worklogs:
            if start <= w.date <= end:
                logs[(w.member_id, w.date)].append(w)
    wh = ctx.settings.working_hours
    rows = domain_timesheet(list(ctx.members), days, logs, _plan(ctx), ctx.cal, today=ctx.today,
                            hours_per_day=wh.hours_per_day, tolerance_h=wh.timesheet_tolerance_h)
    return TimesheetView(start, end, [DayHead(x, x == ctx.today, ctx.cal.extra_days.get(x)) for x in days], rows)


def day_detail(d: Deps, member_id: str, date: str) -> DayDetailView:
    ctx = _ctx(d)
    if member_id not in ctx.members:
        raise NotFound(f"Member {member_id} was not found", "member")
    by_key: dict[str, list[Worklog]] = {}
    for i in ctx.issues:
        logs = [w for w in i.worklogs if w.member_id == member_id and w.date == date]
        if logs:
            by_key[i.key] = logs
    dd = domain_day_detail(logs_by_key=by_key, plan=_plan(ctx), member_id=member_id, date=date)
    issues = {i.key: i for i in ctx.issues}
    entries = [DayEntryView(e.key, issues[e.key].summary if e.key in issues else e.key, issues[e.key].function_id if e.key in issues else None,
                            issues[e.key].step.value if e.key in issues and issues[e.key].step else None, e.logged_h, e.planned_h, e.notes)
               for e in dd.entries]
    return DayDetailView(member_id, date, dd.logged_h, dd.planned_h, entries)


@dataclass(frozen=True)
class EffortKpis:
    logged_h: float  # on started function sub-tasks
    estimate_h: float
    rework_rate: float | None  # fix hours ÷ create hours
    avg_rounds: float | None
    fix_tickets: int  # fix sub-tasks with at least one review round
    over_subtasks: int


@dataclass(frozen=True)
class EffortAnalysis:
    epic_key: str | None
    title: str
    kpis: EffortKpis
    by_phase: list[EffortBar]
    by_step: list[EffortBar]
    rework: list[Rework]


def analysis(d: Deps, epic: str | None) -> EffortAnalysis:
    ctx = _ctx(d)
    with_fns = [k for k in ctx.settings.jira.epics if any(f.epic_key == k for f in ctx.functions)]
    epic = epic or (with_fns[0] if with_fns else None)
    if epic and not any(a.key == epic for a in ctx.anken):
        raise NotFound(f"Anken {epic} is not tracked", "anken")
    title = next((a.title for a in ctx.anken if a.key == epic), "")
    subs = [i for i in ctx.subtasks if i.epic_key == epic]
    fns = [f for f in ctx.functions if f.epic_key == epic]
    cells = [c for f in fns for c in f.issues()]
    phase_of_parent = {p.key: p.phase for p in ctx.parents if p.epic_key == epic}
    by_phase: dict[str, list] = defaultdict(list)
    for i in subs:
        by_phase[phase_of_parent.get(i.parent_key or "", i.phase)].append(i)
    rounds = {k: f.rounds for k, f in ctx.flags.items()}
    bas = [m for m, mem in ctx.members.items() if mem.role == Role.BA and any(f.owner_id == m for f in fns)]
    rework = rework_by_member(fns, bas, rounds)
    started = [c for c in cells if c.status != Status.NYS]
    fixes = [c for c in cells if STEP_BY_KIND[c.step].fix and rounds.get(c.key, 0) > 0]
    create_h, fix_h = sum(r.create_h for r in rework), sum(r.fix_h for r in rework)
    kpis = EffortKpis(
        logged_h=round(sum(c.logged_h for c in started), 2), estimate_h=round(sum(c.estimate_h for c in started), 2),
        rework_rate=round(fix_h / create_h, 4) if create_h else None,
        avg_rounds=round(sum(rounds[c.key] for c in fixes) / len(fixes), 2) if fixes else None,
        fix_tickets=len(fixes), over_subtasks=sum(1 for c in started if ctx.flags[c.key].over),
    )
    return EffortAnalysis(
        epic, title, kpis,
        estimate_vs_logged([(p, by_phase[p]) for p in ordered_phases(list(by_phase))]),
        estimate_vs_logged([(s.label, [c for c in cells if c.step == s.kind]) for s in STEPS]),
        rework,
    )
