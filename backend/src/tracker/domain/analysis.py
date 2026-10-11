"""Effort analysis: estimate vs logged on started sub-tasks, and rework by function owner."""

from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass

from .flags import Function
from .model import Issue, Status
from .steps import StepKind, is_fix_step


@dataclass(frozen=True)
class EffortBar:
    label: str
    estimate_h: float
    logged_h: float


def estimate_vs_logged(groups: Iterable[tuple[str, Sequence[Issue]]]) -> list[EffortBar]:
    out = []
    for label, issues in groups:
        started = [i for i in issues if i.status != Status.NYS]
        out.append(EffortBar(label, sum(i.estimate_h for i in started), sum(i.logged_h for i in started)))
    return out


@dataclass(frozen=True)
class Rework:
    member_id: str
    functions: int
    create_h: float
    fix_h: float
    rate: float  # fix hours ÷ create hours
    avg_rounds: float
    max_rounds: int


def rework_by_member(functions: Sequence[Function], member_ids: Sequence[str], rounds: Mapping[str, int]) -> list[Rework]:
    out = []
    for mid in member_ids:
        fns = [f for f in functions if f.owner_id == mid]
        cells = [c for f in fns for c in f.issues()]
        create = sum(c.logged_h for c in cells if c.step == StepKind.CREATE)
        fix = sum(c.logged_h for c in cells if is_fix_step(c))
        rs = [rounds.get(c.key, 0) for c in cells if is_fix_step(c) and rounds.get(c.key, 0) > 0]
        out.append(Rework(mid, len(fns), create, fix, round(fix / create, 4) if create else 0,
                          round(sum(rs) / len(rs), 2) if rs else 0, max(rs, default=0)))
    return out
