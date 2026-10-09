"""Plan suggestions: dates for a new function's 5 steps, and shifting a planned item."""

import math
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, field

from .calendar import WEEKEND, WorkCalendar
from .dates import add_days, day_diff, round_half_up
from .model import StepKind
from .steps import STEPS

HOURS_PER_PLANNED_DAY = 6  # a step needs one planned day per 6 estimated hours


@dataclass(frozen=True)
class PlannedStep:
    step: StepKind
    assignee_id: str
    estimate_h: float
    start: str
    end: str
    note: str  # auto | skips (suggestion skipped days off) | edited | dayoff (an edited date lands on a day off)
    reasons: list[tuple[str, str]] = field(default_factory=list)  # (date, reason); reason "weekend" for weekends


def step_estimate(create_hours: float, weight: float) -> float:
    """Create × weight, rounded to the nearest half hour."""
    return round_half_up(create_hours * weight * 2) / 2


def suggest_function_plan(
    *,
    first_day: str,
    create_hours: float,
    assignees: Sequence[str],
    cal: WorkCalendar,
    overrides: Sequence[Mapping[str, str] | None],
) -> list[PlannedStep]:
    """Each step starts on the working day after the previous one ends; `overrides` may fix a step's start/end."""
    out: list[PlannedStep] = []
    cur = first_day
    for i, st in enumerate(STEPS):
        who = assignees[i]
        est = step_estimate(create_hours, st.effort_weight)
        dur = max(1, math.ceil(est / HOURS_PER_PLANNED_DAY))
        skipped: list[tuple[str, str]] = []
        s_start, s_end = cal.span(cur, dur, who, skipped)
        o = overrides[i] or {}
        start = o.get("start") or s_start
        end = o.get("end") or (cal.span(o["start"], dur, who)[1] if o.get("start") else s_end)
        end = max(end, start)
        cur = add_days(end, 1)
        if o.get("start") or o.get("end"):
            offs = [(d, "weekend" if r == WEEKEND else r) for d in dict.fromkeys((start, end)) if (r := cal.off(d, who))]
            out.append(PlannedStep(st.kind, who, est, start, end, "dayoff" if offs else "edited", offs))
        else:
            out.append(PlannedStep(st.kind, who, est, start, end, "skips" if skipped else "auto", skipped))
    return out


def shift_plan(start: str, end: str, new_start: str) -> tuple[str, str]:
    """Moves a planned item to `new_start`, keeping its length in calendar days."""
    return new_start, add_days(new_start, day_diff(start, end))
