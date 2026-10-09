"""Date helpers. Dates are ISO strings (YYYY-MM-DD) in the domain; they compare lexically."""

import calendar
import math
from collections.abc import Iterator
from datetime import date, timedelta

WEEKDAY_NAMES = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]


def parse(s: str) -> date:
    return date.fromisoformat(s)


def add_days(s: str, n: int) -> str:
    return (parse(s) + timedelta(days=n)).isoformat()


def weekday(s: str) -> int:
    """Sunday = 0 … Saturday = 6 (the convention the working-days setting uses)."""
    return (parse(s).weekday() + 1) % 7


def monday_of(s: str) -> str:
    return add_days(s, -parse(s).weekday())


def dm(s: str | None) -> str:
    """dd/mm label, used in human-readable messages produced by the domain."""
    return f"{s[8:10]}/{s[5:7]}" if s else ""


def days_between(a: str, b: str) -> Iterator[str]:
    """Inclusive range a..b."""
    s = a
    while s <= b:
        yield s
        s = add_days(s, 1)


def weekdays_after(a: str, b: str) -> int:
    """Number of Mon–Fri days in the half-open range (a, b]."""
    return sum(1 for s in days_between(add_days(a, 1), b) if weekday(s) not in (0, 6)) if b > a else 0


def month_days(month: str) -> list[str]:
    y, m = map(int, month.split("-"))
    return [f"{month}-{i:02d}" for i in range(1, calendar.monthrange(y, m)[1] + 1)]


def day_diff(a: str, b: str) -> int:
    return (parse(b) - parse(a)).days


def round_half_up(x: float) -> float:
    return math.floor(x + 0.5)


def one_decimal(n: float) -> float:
    return round_half_up(n * 10) / 10
