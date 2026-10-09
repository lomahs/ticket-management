"""Working-day calendar per member: working weekdays + extra days − country holidays − full-day leave."""

from collections.abc import Iterable, Mapping

from .dates import add_days, dm, weekday
from .model import LEAVE_TYPES, Holiday, Leave, Member

WEEKEND = "we"


class WorkCalendar:
    def __init__(
        self,
        *,
        working_days: Iterable[int],
        extra_days: Mapping[str, str],
        holidays: Mapping[str, Holiday],
        members: Mapping[str, Member],
        leaves: Iterable[Leave],
    ):
        self.working_days = frozenset(working_days)
        self.extra_days = dict(extra_days)
        self.holidays = dict(holidays)
        self.members = dict(members)
        self._leaves = {(l.member_id, l.date): l for l in leaves}

    def visible(self, d: str) -> bool:
        """Shown on calendars: a working weekday or an extra working day."""
        return weekday(d) in self.working_days or d in self.extra_days

    def leave(self, member_id: str, d: str) -> Leave | None:
        return self._leaves.get((member_id, d))

    def holiday_for(self, member_id: str, d: str) -> Holiday | None:
        h = self.holidays.get(d)
        m = self.members.get(member_id)
        return h if h and m and m.country in h.countries else None

    def off(self, d: str, member_id: str) -> str | None:
        """None on a working day; WEEKEND for a non-working weekday; otherwise the reason (holiday or leave)."""
        if not self.visible(d):
            return WEEKEND
        h = self.holiday_for(member_id, d)
        if h:
            return h.name
        lv = self.leave(member_id, d)
        if lv and LEAVE_TYPES[lv.type].full:
            name = self.members[member_id].name if member_id in self.members else member_id
            return f"{name} · {LEAVE_TYPES[lv.type].label}"
        return None

    def capacity_factor(self, member_id: str, d: str) -> float:
        """Share of a standard working day the member is available: 0, 0.5 (half-day leave) or 1."""
        if self.off(d, member_id):
            return 0
        lv = self.leave(member_id, d)
        return 0.5 if lv and not LEAVE_TYPES[lv.type].full else 1

    def next_work(self, d: str, member_id: str, skipped: list[str] | None = None) -> str:
        while True:
            reason = self.off(d, member_id)
            if not reason:
                return d
            if reason != WEEKEND and skipped is not None:
                skipped.append(f"{dm(d)} {reason}")
            d = add_days(d, 1)

    def span(self, start: str, n: int, member_id: str, skipped: list[str] | None = None) -> tuple[str, str]:
        """First and last day of n working days for the member, starting on or after `start`."""
        s = self.next_work(start, member_id, skipped)
        e = s
        for _ in range(1, n):
            e = self.next_work(add_days(e, 1), member_id, skipped)
        return s, e
