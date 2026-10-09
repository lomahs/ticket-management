"""Anken (tracked epics): overview cards, detail with attention groups, the function matrix, and new functions."""

import re
from collections import Counter
from dataclasses import dataclass

from tracker.domain.dates import add_days
from tracker.domain.flags import Function, function_progress, function_state, group_status, progress_pct
from tracker.domain.model import Issue
from tracker.domain.naming import render_summary
from tracker.domain.overview import PhaseRollup, attention_groups, forecast_date, ordered_phases, phase_rollup
from tracker.domain.scheduling import suggest_function_plan
from tracker.domain.steps import STEPS

from ..context import Context, Deps, TaskCard, load_context
from ..dto import NewSubtask
from ..errors import CapabilityDisabled, Conflict, Invalid, NotFound


@dataclass(frozen=True)
class AnkenCard:
    key: str
    title: str
    project: str
    plan: str | None
    forecast: str | None
    late_functions: int
    late_subtasks: int
    used_h: float
    estimate_h: float
    phases: list[PhaseRollup]
    member_ids: list[str]
    has_functions: bool


@dataclass(frozen=True)
class AttentionView:
    kind: str
    count: int
    items: list[TaskCard]  # at most 6


@dataclass(frozen=True)
class AnkenDetail:
    card: AnkenCard
    attention: list[AttentionView]


def _ctx(d: Deps) -> Context:
    with d.uow() as u:
        return load_context(u, d.clock.today())


def _epic_subtasks(ctx: Context, epic: str) -> list[Issue]:
    return [i for i in ctx.subtasks if i.epic_key == epic]


def _card(ctx: Context, key: str) -> AnkenCard:
    a = next(x for x in ctx.anken if x.key == key)
    subs = _epic_subtasks(ctx, key)
    parents = [p for p in ctx.parents if p.epic_key == key]
    by_phase: dict[str, list[Issue]] = {}
    phase_of_parent = {p.key: p.phase for p in parents}
    for i in subs:
        by_phase.setdefault(phase_of_parent.get(i.parent_key or "", i.phase), []).append(i)
    phases = [phase_rollup(n, by_phase.get(n, [])) for n in ordered_phases(list({p.phase for p in parents} | set(by_phase)))]
    late = [i for i in subs if ctx.flags[i.key].late]
    fns = [f for f in ctx.functions if f.epic_key == key]
    late_keys = {i.key for i in late}
    plan = a.due_date or max((i.planned_end for i in subs if i.planned_end), default=None)
    worst = max((ctx.flags[i.key].late_days for i in late), default=0)
    assignees = {i.assignee_id for i in subs if i.assignee_id}
    return AnkenCard(
        key=a.key, title=a.title, project=a.project, plan=plan, forecast=forecast_date(plan, worst) if plan else None,
        late_functions=sum(1 for f in fns if any(c.key in late_keys for c in f.issues())), late_subtasks=len(late),
        used_h=round(sum(i.logged_h for i in subs), 2), estimate_h=round(sum(i.estimate_h for i in subs), 2),
        phases=phases, member_ids=[m for m in ctx.members if m in assignees], has_functions=bool(fns),
    )


def overview(d: Deps) -> list[AnkenCard]:
    ctx = _ctx(d)
    order = {k: n for n, k in enumerate(ctx.settings.jira.epics)}
    return [_card(ctx, a.key) for a in sorted(ctx.anken, key=lambda a: order.get(a.key, 99))]


def _require(ctx: Context, epic: str) -> None:
    if not any(a.key == epic for a in ctx.anken):
        raise NotFound(f"Anken {epic} is not tracked", "epic")


def detail(d: Deps, epic: str) -> AnkenDetail:
    ctx = _ctx(d)
    _require(ctx, epic)
    subs = _epic_subtasks(ctx, epic)
    groups = attention_groups(subs, ctx.flags, ctx.members, ctx.settings.thresholds)
    return AnkenDetail(_card(ctx, epic), [AttentionView(g.kind, len(g.issues), [ctx.card(i) for i in g.issues[:6]]) for g in groups])


# --- matrix -------------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class FunctionRow:
    id: str
    name: str
    owner_id: str | None
    reviewer_id: str | None
    current: int
    state: str  # done | nys | delay | ontime
    progress: str  # early | ontime | late
    group: str  # N | W | D
    pct: int
    cells: list[TaskCard | None]
    logged_h: float
    estimate_h: float


@dataclass(frozen=True)
class MatrixStats:
    progress_pct: int
    functions_done: int
    functions_total: int
    late_functions: int
    late_subtasks: int
    over_subtasks: int
    logged_h: float
    estimate_h: float


@dataclass(frozen=True)
class ParentOption:
    key: str
    summary: str
    phase: str


@dataclass(frozen=True)
class MatrixView:
    epic_key: str
    title: str
    phase: str
    stats: MatrixStats
    functions: list[FunctionRow]
    parents: list[ParentOption]
    next_function_id: str


def _next_function_id(fns: list[Function]) -> str:
    ids = [m.groups() for f in fns if (m := re.fullmatch(r"([A-Za-z]+)(\d+)", f.id))]
    if not ids:
        return "F01"
    prefix = Counter(p for p, _ in ids).most_common(1)[0][0]
    nums = [n for p, n in ids if p == prefix]
    width = max(len(n) for n in nums)
    return f"{prefix}{max(int(n) for n in nums) + 1:0{width}d}"


def matrix(d: Deps, epic: str) -> MatrixView:
    ctx = _ctx(d)
    _require(ctx, epic)
    fns = [f for f in ctx.functions if f.epic_key == epic]
    late = {k for k, f in ctx.flags.items() if f.late}
    rows = [FunctionRow(
        id=f.id, name=f.name, owner_id=f.owner_id, reviewer_id=f.reviewer_id, current=f.current,
        state=function_state(f, late), progress=function_progress(f, ctx.today, late), group=group_status(f), pct=progress_pct(f),
        cells=[ctx.card(c) if c else None for c in f.cells],
        logged_h=round(sum(c.logged_h for c in f.issues()), 2), estimate_h=round(sum(c.estimate_h for c in f.issues()), 2),
    ) for f in fns]
    cells = [c for f in fns for c in f.issues()]
    stats = MatrixStats(
        progress_pct=round(sum(r.pct for r in rows) / len(rows)) if rows else 0,
        functions_done=sum(1 for f in fns if f.current >= len(STEPS)), functions_total=len(fns),
        late_functions=sum(1 for r in rows if r.state == "delay"), late_subtasks=sum(1 for c in cells if c.key in late),
        over_subtasks=sum(1 for c in cells if ctx.flags[c.key].over),
        logged_h=round(sum(c.logged_h for c in cells), 2), estimate_h=round(sum(c.estimate_h for c in cells), 2),
    )
    phases = Counter(c.phase for c in cells)
    parents = sorted((p for p in ctx.parents if p.epic_key == epic), key=lambda p: p.key)
    title = next(a.title for a in ctx.anken if a.key == epic)
    return MatrixView(epic, title, phases.most_common(1)[0][0] if phases else "", stats, rows,
                      [ParentOption(p.key, p.summary, p.phase) for p in parents], _next_function_id(fns))


# --- new function -------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class FunctionDraft:
    parent_key: str
    name: str
    ba: str
    bal: str
    jm: str
    create_hours: float
    overrides: list[dict | None]  # per step: {"start": ..., "end": ...}


@dataclass(frozen=True)
class PreviewStep:
    step: str
    summary: str
    assignee_id: str
    estimate_h: float
    start: str
    end: str
    note: str  # auto | skips | edited | dayoff
    reasons: list[tuple[str, str]]


@dataclass(frozen=True)
class FunctionPreview:
    phase: str
    function_id: str
    steps: list[PreviewStep]


def _plan(ctx: Context, epic: str, draft: FunctionDraft, function_id: str | None) -> FunctionPreview:
    parent = next((p for p in ctx.parents if p.key == draft.parent_key and p.epic_key == epic), None)
    if not parent:
        raise Invalid(f"{draft.parent_key} is not a parent ticket of {epic}", "parent")
    for f, mid in (("ba", draft.ba), ("bal", draft.bal), ("jm", draft.jm)):
        if mid not in ctx.members:
            raise Invalid(f"Unknown member {mid}", f)
    if not draft.create_hours > 0:
        raise Invalid("Effort for Create must be more than 0 hours", "create_hours")
    overrides = (list(draft.overrides) + [None] * 5)[:5]
    fid = function_id or _next_function_id([f for f in ctx.functions if f.epic_key == epic])
    plan = suggest_function_plan(first_day=add_days(ctx.today, 1), create_hours=draft.create_hours,
                                 assignees=(draft.ba, draft.bal, draft.ba, draft.jm, draft.ba), cal=ctx.cal, overrides=overrides)
    rule = ctx.settings.name_rules.rule
    steps = [PreviewStep(p.step.value, render_summary(rule, phase_name=parent.phase, function_id=fid, function_name=draft.name or "(function name)",
                                                      step=STEPS[i].label, anken_name=next(a.title for a in ctx.anken if a.key == epic)),
                         p.assignee_id, p.estimate_h, p.start, p.end, p.note, p.reasons) for i, p in enumerate(plan)]
    return FunctionPreview(parent.phase, fid, steps)


def preview_function(d: Deps, epic: str, draft: FunctionDraft) -> FunctionPreview:
    ctx = _ctx(d)
    _require(ctx, epic)
    return _plan(ctx, epic, draft, None)


def create_function(d: Deps, epic: str, draft: FunctionDraft, function_id: str | None = None) -> list[str]:
    if d.writer is None:
        raise CapabilityDisabled("Writing to Jira is not available yet")
    ctx = _ctx(d)
    _require(ctx, epic)
    if not draft.name.strip():
        raise Invalid("Function name is required", "name")
    if function_id and any(f.epic_key == epic and f.id == function_id for f in ctx.functions):
        raise Conflict(f"Function {function_id} already exists in {epic}", "function_id")
    p = _plan(ctx, epic, draft, function_id)
    subs = []
    for s in p.steps:
        acc = ctx.members[s.assignee_id].jira_account_id
        if not acc:
            raise Invalid(f"{ctx.members[s.assignee_id].name} has no Jira account", "assignee")
        subs.append(NewSubtask(s.summary, acc, s.start, s.end, s.estimate_h))
    keys = d.writer.create_subtasks(draft.parent_key, subs)
    d.sync.refresh(keys)
    return keys


