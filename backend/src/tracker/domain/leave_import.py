"""Validation of leave rows imported from Excel/CSV (Member, From, To, Type, Note)."""

import re
from collections.abc import Iterable, Mapping, Sequence
from dataclasses import dataclass

from .dates import days_between, dm
from .model import LEAVE_TYPES, Leave, LeaveType, Member

_ISO = re.compile(r"^\d{4}-\d{2}-\d{2}$")


@dataclass(frozen=True)
class ImportRow:
    row: int  # spreadsheet row number (header is row 1)
    member: str
    date_from: str
    date_to: str
    type_text: str
    note: str
    member_id: str | None
    type: LeaveType | None
    error: str  # empty when valid


def validate_rows(rows: Iterable[Sequence[object]], members: Mapping[str, Member], existing: Iterable[Leave]) -> list[ImportRow]:
    taken = {(l.member_id, l.date) for l in existing}
    out = []
    for i, raw in enumerate(rows):
        mn, f, t, ty, note = [("" if v is None else str(v).strip()) for v in (list(raw) + [""] * 5)[:5]]
        key = mn.lower()
        mid = next((k for k, m in members.items() if m.name.lower() == key or k == key), None)
        tk = next((k for k, v in LEAVE_TYPES.items() if v.label.lower() == ty.lower() or k.value.lower() == ty.lower()), None)
        to = t or f
        if not mid:
            err = f'Unknown member "{mn}"'
        elif not _ISO.match(f) or not _ISO.match(to):
            err = "Invalid date format, use YYYY-MM-DD"
        elif to < f:
            err = "To date is before From date"
        elif not tk:
            err = f'Unknown leave type "{ty}"'
        else:
            clash = next((d for d in days_between(f, to) if (mid, d) in taken), None)
            err = f"Overlaps existing leave on {dm(clash)}" if clash else ""
        out.append(ImportRow(i + 2, mn, f, to, ty, note, mid, tk, err))
    return out
