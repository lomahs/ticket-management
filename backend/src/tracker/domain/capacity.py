"""Planned load: each item's estimate spread over its working days; weekly load against available hours."""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass, field

from .calendar import WorkCalendar
from .dates import add_days, day_diff, days_between


@dataclass(frozen=True)
class PlanItem:
    key: str
    member_id: str  # the worker
    start: str
    end: str
    estimate_h: float


@dataclass
class DayPlan:
    total: float = 0
    by_key: dict[str, float] = field(default_factory=dict)


Plan = dict[str, dict[str, DayPlan]]  # member → date → planned hours


def daily_plan(items: Iterable[PlanItem], cal: WorkCalendar) -> Plan:
    """Spreads each estimate evenly over the item's working days (its start day when it has none)."""
    plan: Plan = {}
    for it in items:
        if not it.estimate_h:
            continue
        days = [d for d in days_between(it.start, it.end) if not cal.off(d, it.member_id)] or [it.start]
        per = it.estimate_h / len(days)
        for d in days:
            dp = plan.setdefault(it.member_id, {}).setdefault(d, DayPlan())
            dp.total += per
            dp.by_key[it.key] = dp.by_key.get(it.key, 0) + per
    return plan


@dataclass(frozen=True)
class WeekLoad:
    week_start: str
    planned_h: float
    available_h: float
    pct: int | None  # None when the member has no available hours that week
    level: str  # idle | ok | near | over


def _level(pct: int | None, cap_pct: float) -> str:
    if pct is None:
        return "idle"
    if pct > cap_pct:
        return "over"
    if pct >= cap_pct * 0.85:
        return "near"
    return "ok" if pct >= 40 else "idle"


def weekly_capacity(items: Sequence[PlanItem], cal: WorkCalendar, member_ids: Sequence[str], *, start: str,
                    hours_per_day: float, cap_pct: float, weeks: int = 5) -> dict[str, list[WeekLoad]]:
    plan = daily_plan(items, cal)
    out: dict[str, list[WeekLoad]] = {}
    for mid in member_ids:
        loads = [0.0] * weeks
        for d, dp in plan.get(mid, {}).items():
            w = day_diff(start, d) // 7
            if 0 <= w < weeks:
                loads[w] += dp.total
        rows = []
        for w in range(weeks):
            ws = add_days(start, w * 7)
            avail = sum(hours_per_day * cal.capacity_factor(mid, d) for d in days_between(ws, add_days(ws, 6)))
            pct = round(loads[w] / avail * 100) if avail else None
            rows.append(WeekLoad(ws, round(loads[w], 2), avail, pct, _level(pct, cap_pct)))
        out[mid] = rows
    return out
