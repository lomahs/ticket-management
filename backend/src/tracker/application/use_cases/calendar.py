"""Team calendar: month view, leave editing, Excel/CSV import and export rows."""

from collections.abc import Sequence
from dataclasses import dataclass

from tracker.domain.dates import days_between, month_days, weekday
from tracker.domain.leave_import import ImportRow, validate_rows
from tracker.domain.model import LEAVE_TYPES, Leave, LeaveType

from ..context import Deps, make_calendar
from ..errors import Invalid, NotFound
from .planning import DayHead

MAX_LEAVE_DAYS = 60


@dataclass(frozen=True)
class CalendarCell:
    date: str
    kind: str | None  # holiday | leave | None
    label: str | None
    leave: Leave | None


@dataclass(frozen=True)
class CalendarRow:
    member_id: str
    cells: list[CalendarCell]
    days_off: float


@dataclass(frozen=True)
class CalendarView:
    month: str
    days: list[DayHead]
    rows: list[CalendarRow]


def month(d: Deps, mon: str) -> CalendarView:
    today = d.clock.today()
    with d.uow() as u:
        settings = u.settings.load()
        members = {m.id: m for m in u.members.list()}
        cal, _ = make_calendar(u, settings, members)
    days = [x for x in month_days(mon) if cal.visible(x)]
    rows = []
    for mid in members:
        cells, off = [], 0.0
        for x in days:
            h = cal.holiday_for(mid, x)
            lv = cal.leave(mid, x)
            if h:
                cells.append(CalendarCell(x, "holiday", h.name, None))
            elif lv:
                cells.append(CalendarCell(x, "leave", LEAVE_TYPES[lv.type].label, lv))
                off += 1 if LEAVE_TYPES[lv.type].full else 0.5
            else:
                cells.append(CalendarCell(x, None, None, None))
        rows.append(CalendarRow(mid, cells, off))
    return CalendarView(mon, [DayHead(x, x == today, cal.extra_days.get(x)) for x in days], rows)


@dataclass(frozen=True)
class LeaveDraft:
    edit_id: int | None
    member_id: str
    type: LeaveType
    date_from: str
    date_to: str
    note: str


def save_leave(d: Deps, draft: LeaveDraft) -> int:
    """Stores leave on each working day of the range (non-working days are skipped). Returns the number of days."""
    if draft.date_to < draft.date_from:
        raise Invalid("To date is before From date", "to")
    with d.uow() as u:
        settings = u.settings.load()
        members = {m.id: m for m in u.members.list()}
        if draft.member_id not in members:
            raise Invalid(f"Unknown member {draft.member_id}", "member")
        cal, leaves = make_calendar(u, settings, members)
        if draft.edit_id is not None:
            if not any(lv.id == draft.edit_id for lv in leaves):
                raise NotFound(f"Leave {draft.edit_id} was not found")
            u.calendar.delete_leave(draft.edit_id)
        existing = {(lv.member_id, lv.date): lv for lv in leaves if lv.id != draft.edit_id}
        n = 0
        for x in days_between(draft.date_from, draft.date_to):
            if n >= MAX_LEAVE_DAYS:
                break
            if not cal.visible(x):
                continue
            old = existing.get((draft.member_id, x))
            if old:
                u.calendar.delete_leave(old.id)
            u.calendar.add_leave(Leave(old.id if old else (draft.edit_id if n == 0 and draft.edit_id else 0),
                                       draft.member_id, x, draft.type, draft.note))
            n += 1
        u.commit()
    return n


def delete_leave(d: Deps, leave_id: int) -> None:
    with d.uow() as u:
        if not any(lv.id == leave_id for lv in u.calendar.leaves()):
            raise NotFound(f"Leave {leave_id} was not found")
        u.calendar.delete_leave(leave_id)
        u.commit()


@dataclass(frozen=True)
class ImportPreview:
    rows: list[ImportRow]
    working_days: int  # days the valid rows will add


@dataclass(frozen=True)
class ImportResult:
    imported: int
    month: str | None


def _validate(d: Deps, rows: Sequence[Sequence[object]]):
    with d.uow() as u:
        settings = u.settings.load()
        checked = validate_rows(rows, {m.id: m for m in u.members.list()}, u.calendar.leaves())
    return settings, checked


def import_preview(d: Deps, rows: Sequence[Sequence[object]]) -> ImportPreview:
    settings, checked = _validate(d, rows)
    days = sum(1 for r in checked if not r.error for x in days_between(r.date_from, r.date_to)
               if weekday(x) in settings.working_hours.days)
    return ImportPreview(checked, days)


def import_confirm(d: Deps, rows: Sequence[Sequence[object]]) -> ImportResult:
    settings, checked = _validate(d, rows)
    ok = [r for r in checked if not r.error]
    n = 0
    with d.uow() as u:
        for r in ok:
            for x in days_between(r.date_from, r.date_to):
                if weekday(x) in settings.working_hours.days:
                    u.calendar.add_leave(Leave(0, r.member_id, x, r.type, r.note))
                    n += 1
        u.commit()
    return ImportResult(n, ok[0].date_from[:7] if ok else None)


def export(d: Deps, mon: str) -> list[tuple[str, str, str, str]]:
    """(who, date, type, note) rows for the month: leave by date, then holidays (who = countries)."""
    with d.uow() as u:
        members = {m.id: m for m in u.members.list()}
        leaves = sorted((lv for lv in u.calendar.leaves() if lv.date.startswith(mon)), key=lambda lv: (lv.date, lv.member_id))
        hols = sorted((h for h in u.calendar.holidays().values() if h.date.startswith(mon)), key=lambda h: h.date)
    return ([(members[lv.member_id].full_name if lv.member_id in members else lv.member_id, lv.date, LEAVE_TYPES[lv.type].label, lv.note)
             for lv in leaves] + [(" ".join(h.countries), h.date, "Holiday", h.name) for h in hols])
