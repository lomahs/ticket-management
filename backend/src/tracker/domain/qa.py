"""Rule-based answers for the Task Q&A panel."""

import unicodedata
from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from .dates import add_days, dm, monday_of, one_decimal, weekday
from .flags import Function, IssueFlags
from .model import Issue, Member, Role, Status, Worklog


@dataclass(frozen=True)
class QaContext:
    functions: Sequence[Function]
    flags: Mapping[str, IssueFlags]
    members: Mapping[str, Member]
    worklogs: Sequence[Worklog]
    today: str
    hours_per_day: float
    working_days: Sequence[int]


@dataclass(frozen=True)
class Answer:
    text: str
    chips: list[str]


def _fold(s: str) -> str:
    return "".join(c for c in unicodedata.normalize("NFD", s.lower()) if unicodedata.category(c) != "Mn")


def _mentioned(q: str, members: Mapping[str, Member], role: Role | None = None) -> Member | None:
    words = set(_fold(q).replace("?", " ").split())
    for m in members.values():
        if (role is None or m.role == role) and (_fold(m.name) in words or m.id in words):
            return m
    return None


def _first(members: Mapping[str, Member], role: Role) -> Member | None:
    return next((m for m in members.values() if m.role == role), None)


def answer(ctx: QaContext, q: str) -> Answer:
    t = _fold(q)
    cells: list[Issue] = [c for f in ctx.functions for c in f.issues()]
    fid = {c.key: f.id for f in ctx.functions for c in f.issues()}
    fl = ctx.flags
    keys = lambda xs: [c.key for c in xs]  # noqa: E731

    if "late" in t:
        late = sorted((c for c in cells if fl[c.key].late), key=lambda c: -fl[c.key].late_days)
        if not late:
            return Answer("No sub-tasks are past their plan.", [])
        top = late[0]
        return Answer(f"{len(late)} sub-tasks are past their plan, across {len({fid[c.key] for c in late})} functions. "
                      f"Longest delay: {fid[top.key]} ({fl[top.key].late_days} working days).", keys(late))

    bal = _mentioned(q, ctx.members, Role.BAL) or ("bal" in t.split() and _first(ctx.members, Role.BAL)) or None
    if bal:
        held = [c for c in cells if c.assignee_id == bal.id and fl[c.key].is_current and c.status != Status.CLOSED]
        longest = max((fl[c.key].held_days for c in held), default=0)
        return Answer(f"{bal.name} is holding {len(held)} tickets. The longest has been held {longest} days.", keys(held))

    if "round" in t or "ping" in t:
        many = sorted((c for c in cells if fl[c.key].rounds >= 2), key=lambda c: -fl[c.key].rounds)
        n3 = sum(1 for c in many if fl[c.key].rounds >= 3)
        return Answer(f"{n3} Fix comment tickets have gone 3 rounds or more. Sorted by rounds:", keys(many))

    jm = _mentioned(q, ctx.members, Role.JM) or (("jm" in t.split() or "client" in t) and _first(ctx.members, Role.JM)) or None
    if jm:
        waiting = [c for c in cells if c.assignee_id == jm.id and c.status != Status.CLOSED and fl[c.key].held_days > 0]
        longest = max((fl[c.key].held_days for c in waiting), default=0)
        return Answer(f"{len(waiting)} tickets are with {jm.name} (JM). Longest wait: {longest} days.", keys(waiting))

    if "log" in t or "hour" in t:
        m = _mentioned(q, ctx.members)
        if not m:
            return Answer("Whose hours? Mention a member, e.g. “How many hours has An logged this week?”", [])
        mon = monday_of(ctx.today)
        week = [add_days(mon, i) for i in range(7)]
        workdays = [d for d in week if weekday(d) in ctx.working_days]
        logs = [w for w in ctx.worklogs if w.member_id == m.id and mon <= w.date <= ctx.today]
        total = sum(w.hours for w in logs)
        today_h = sum(w.hours for w in logs if w.date == ctx.today)
        expected = ctx.hours_per_day * len(workdays)
        open_cells = [c for c in cells if c.assignee_id == m.id and c.logged_h > 0 and c.status != Status.CLOSED]
        return Answer(f"{m.name} has logged {_h(total)}h / {_h(expected)}h this week ({dm(workdays[0])}–{dm(workdays[-1])}), "
                      f"{_h(today_h)}h today.", keys(open_cells))

    return Answer("I did not understand that. Try asking about late tasks, review tickets, rounds, waiting on JM or logged hours.", [])


def _h(x: float) -> str:
    v = one_decimal(x)
    return str(int(v)) if v == int(v) else str(v)
