"""Date helpers. Dates are ISO strings (YYYY-MM-DD) throughout, matching the frontend."""

import calendar
import math
from datetime import date, timedelta

WDN = ["Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat"]


def parse(s: str) -> date:
    return date.fromisoformat(s)


def add(s: str, n: int) -> str:
    return (parse(s) + timedelta(days=n)).isoformat()


def js_weekday(s: str) -> int:
    """Weekday with Sunday = 0, like JavaScript's getUTCDay()."""
    return (parse(s).weekday() + 1) % 7


def dm(s: str | None) -> str:
    return f"{s[8:10]}/{s[5:7]}" if s else ""


def wd(a: str, b: str) -> int:
    """Number of Mon–Fri days in the half-open range (a, b]."""
    n, s = 0, a
    while s < b:
        s = add(s, 1)
        if js_weekday(s) not in (0, 6):
            n += 1
    return n


def days_between(a: str, b: str):
    s = a
    while s <= b:
        yield s
        s = add(s, 1)


def month_days(mon: str) -> list[str]:
    y, m = map(int, mon.split("-"))
    n = calendar.monthrange(y, m)[1]
    return [f"{mon}-{i:02d}" for i in range(1, n + 1)]


def js_round(x: float) -> float:
    """Math.round semantics (half rounds up), unlike Python's banker's rounding."""
    return math.floor(x + 0.5)


def f1(n: float) -> str:
    """One-decimal number label without a trailing .0, like String(Math.round(n*10)/10)."""
    v = js_round(n * 10) / 10
    return str(int(v)) if v == int(v) else str(v)
