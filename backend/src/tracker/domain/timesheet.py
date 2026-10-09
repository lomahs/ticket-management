"""Timesheet: hours logged per member and day, compared with planned and expected hours."""

from collections.abc import Mapping, Sequence
from dataclasses import dataclass

from .calendar import WEEKEND, WorkCalendar
from .capacity import Plan
from .model import Worklog


@dataclass(frozen=True)
class TimesheetCell:
    date: str
    logged_h: float
    planned_h: float
    expected_h: float
    state: str  # weekend | off | future | empty | ok | over
    off_reason: str | None


@dataclass(frozen=True)
class TimesheetRow:
    member_id: str
    cells: list[TimesheetCell]
    logged_h: float
    expected_h: float  # working hours of past days (today and future excluded)
    over_days: int


def _cell(member_id: str, d: str, logged: float, planned: float, cal: WorkCalendar, today: str,
          hours_per_day: float, tolerance_h: float) -> TimesheetCell:
    reason = cal.off(d, member_id)
    expected = hours_per_day * cal.capacity_factor(member_id, d)
    if reason:
        state = "weekend" if reason == WEEKEND else "off"
    elif d > today:
        state = "future"
    elif not logged and not planned:
        state = "empty"
    else:
        state = "over" if logged > planned + tolerance_h else "ok"
    return TimesheetCell(d, round(logged, 2), round(planned, 2), expected,
                         state, None if reason in (None, WEEKEND) else reason)


def timesheet(member_ids: Sequence[str], days: Sequence[str], logs: Mapping[tuple[str, str], Sequence[Worklog]],
              plan: Plan, cal: WorkCalendar, *, today: str, hours_per_day: float, tolerance_h: float) -> list[TimesheetRow]:
    rows = []
    for mid in member_ids:
        cells = []
        for d in days:
            logged = sum(w.hours for w in logs.get((mid, d), ()))
            planned = plan.get(mid, {}).get(d)
            cells.append(_cell(mid, d, logged, planned.total if planned else 0, cal, today, hours_per_day, tolerance_h))
        expected = sum(c.expected_h for c in cells if c.date < today)
        rows.append(TimesheetRow(mid, cells, round(sum(c.logged_h for c in cells), 2), expected,
                                 sum(1 for c in cells if c.state == "over")))
    return rows


@dataclass(frozen=True)
class DayEntry:
    key: str
    logged_h: float
    planned_h: float
    notes: list[str]


@dataclass(frozen=True)
class DayDetail:
    member_id: str
    date: str
    logged_h: float
    planned_h: float
    entries: list[DayEntry]


def day_detail(*, logs_by_key: Mapping[str, Sequence[Worklog]], plan: Plan, member_id: str, date: str) -> DayDetail:
    """Worklogs of one member on one day grouped by issue, plus issues planned that day but not logged."""
    dp = plan.get(member_id, {}).get(date)
    planned = dp.by_key if dp else {}
    entries = []
    for key, logs in logs_by_key.items():
        notes = list(dict.fromkeys(w.note for w in logs if w.note))
        entries.append(DayEntry(key, round(sum(w.hours for w in logs), 2), round(planned.get(key, 0), 2), notes))
    entries += [DayEntry(k, 0, round(h, 2), []) for k, h in planned.items() if k not in logs_by_key]
    return DayDetail(member_id, date, round(sum(e.logged_h for e in entries), 2), round(dp.total if dp else 0, 2), entries)
