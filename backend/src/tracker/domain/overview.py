"""Anken (epic) roll-ups: progress per phase, forecast and the issues that need attention."""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from .dates import add_days, weekday
from .flags import PROGRESS_WEIGHT, IssueFlags
from .model import Issue, Member, Role, Status, Thresholds

DEFAULT_PHASE_ORDER = ("Prepare", "Implement_Coding", "Function Unit Test")


@dataclass(frozen=True)
class PhaseRollup:
    name: str
    pct: int
    closed: int
    total: int
    start: str | None
    end: str | None


def phase_rollup(name: str, issues: Sequence[Issue]) -> PhaseRollup:
    starts = [i.planned_start for i in issues if i.planned_start]
    ends = [i.planned_end for i in issues if i.planned_end]
    pct = round(sum(PROGRESS_WEIGHT[i.status] for i in issues) / len(issues) * 100) if issues else 0
    return PhaseRollup(name, pct, sum(1 for i in issues if i.status == Status.CLOSED), len(issues),
                       min(starts, default=None), max(ends, default=None))


def ordered_phases(names: Sequence[str]) -> list[str]:
    known = [p for p in DEFAULT_PHASE_ORDER if p in names]
    return known + sorted(n for n in set(names) if n not in DEFAULT_PHASE_ORDER)


def forecast_date(plan: str, worst_late_days: int) -> str:
    """The remaining schedule slips by the worst current delay, counted in Mon–Fri days."""
    d, n = plan, worst_late_days
    while n > 0:
        d = add_days(d, 1)
        if weekday(d) not in (0, 6):
            n -= 1
    return d


@dataclass(frozen=True)
class AttentionGroup:
    kind: str  # overdue | stuck_resolved | many_rounds | waiting_client
    issues: list[Issue]


def attention_groups(issues: Sequence[Issue], flags: Mapping[str, IssueFlags], members: Mapping[str, Member],
                     t: Thresholds) -> list[AttentionGroup]:
    f = flags.__getitem__
    is_client = lambda mid: bool(mid and members.get(mid) and members[mid].role == Role.JM)  # noqa: E731
    return [
        AttentionGroup("overdue", sorted((i for i in issues if f(i.key).late), key=lambda i: -f(i.key).late_days)),
        AttentionGroup("stuck_resolved", [i for i in issues if i.status == Status.RESOLVED and f(i.key).held_days >= t.held_days]),
        AttentionGroup("many_rounds", [i for i in issues if f(i.key).rounds >= t.review_rounds and i.status != Status.CLOSED]),
        AttentionGroup("waiting_client", [i for i in issues if is_client(i.assignee_id) and i.status != Status.CLOSED
                                          and f(i.key).held_days >= t.held_days]),
    ]
