"""Clock adapters: a real clock in the team's time zone, and a fixed "today" for the demo."""

from datetime import datetime, timezone
from zoneinfo import ZoneInfo


class SystemClock:
    def __init__(self, tz: str = "Asia/Ho_Chi_Minh"):
        self.tz = ZoneInfo(tz)

    def today(self) -> str:
        return datetime.now(self.tz).date().isoformat()

    def now(self) -> datetime:
        return datetime.now(timezone.utc)


class FixedClock:
    """Pins "today" (so demo data stays meaningful) while timestamps stay real."""

    def __init__(self, today: str):
        self._today = today

    def today(self) -> str:
        return self._today

    def now(self) -> datetime:
        return datetime.now(timezone.utc)
