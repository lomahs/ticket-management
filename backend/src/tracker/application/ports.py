"""Ports: what the application needs from the outside world. Adapters implement these."""

from collections.abc import Iterable, Sequence
from dataclasses import dataclass
from datetime import datetime
from typing import Protocol

from tracker.domain.model import Anken, Holiday, Issue, Leave, Member, Status

from .dto import EpicSnapshot, IssueSnapshot, JiraField, NewSubtask
from .settings import TrackerSettings


class JiraError(Exception):
    """Jira could not be read or written."""


class JiraAuthError(JiraError):
    """Credentials were rejected."""


class JiraReader(Protocol):
    def search(self, epic_keys: Sequence[str], updated_since: datetime | None) -> Iterable[IssueSnapshot]:
        """Parent tickets of the epics and their sub-tasks, optionally only those updated since a moment."""
        ...

    def get_epic(self, key: str) -> EpicSnapshot | None: ...

    def get_issue(self, key: str) -> IssueSnapshot | None: ...

    def list_fields(self) -> list[JiraField]: ...

    def test_connection(self) -> bool: ...


class JiraWriter(Protocol):
    def update_issue(self, key: str, *, planned_start: str | None = None, planned_end: str | None = None,
                     assignee_account_id: str | None = None) -> None: ...

    def transition(self, key: str, status: Status) -> None: ...

    def create_subtasks(self, parent_key: str, subtasks: Sequence[NewSubtask]) -> list[str]: ...


class Clock(Protocol):
    def today(self) -> str: ...

    def now(self) -> datetime: ...


class SecretBox(Protocol):
    def encrypt(self, plain: str) -> str: ...

    def decrypt(self, token: str) -> str: ...


# --- repositories -------------------------------------------------------------------------------------------

class MemberRepository(Protocol):
    def list(self) -> list[Member]: ...

    def get(self, member_id: str, /) -> Member | None: ...

    def by_account(self, account_id: str, /) -> Member | None: ...

    def save(self, member: Member, /) -> None: ...


class IssueRepository(Protocol):
    def list(self, epic_keys: Sequence[str] | None = None, /) -> list[Issue]: ...

    def get(self, key: str, /) -> Issue | None: ...

    def save(self, issue: Issue, /) -> None: ...

    def delete(self, keys: Iterable[str], /) -> None: ...


class AnkenRepository(Protocol):
    def list(self) -> list[Anken]: ...

    def save(self, anken: Anken, /) -> None: ...

    def delete_except(self, keys: Sequence[str], /) -> None: ...


class CalendarRepository(Protocol):
    def leaves(self) -> list[Leave]: ...

    def add_leave(self, leave: Leave, /) -> Leave:
        """Stores a leave; a leave with id 0 gets a new id."""
        ...

    def delete_leave(self, leave_id: int, /) -> None: ...

    def holidays(self) -> dict[str, Holiday]: ...

    def save_holiday(self, holiday: Holiday, /) -> None: ...

    def delete_holiday(self, date: str, /) -> None: ...

    def extra_days(self) -> dict[str, str]: ...

    def save_extra_day(self, date: str, note: str, /) -> None: ...

    def delete_extra_day(self, date: str, /) -> None: ...


class SettingsRepository(Protocol):
    def load(self) -> TrackerSettings: ...

    def save(self, settings: TrackerSettings, /) -> None: ...


@dataclass(frozen=True)
class SyncRun:
    id: int
    started_at: datetime
    finished_at: datetime | None
    full: bool
    status: str  # running | ok | failed
    issues: int
    error: str | None


class SyncRunRepository(Protocol):
    def start(self, full: bool, at: datetime, /) -> int: ...

    def finish(self, run_id: int, at: datetime, status: str, issues: int, error: str | None, /) -> None: ...

    def latest(self) -> SyncRun | None: ...

    def latest_ok(self) -> SyncRun | None: ...


class UnitOfWork(Protocol):
    @property
    def members(self) -> MemberRepository: ...

    @property
    def issues(self) -> IssueRepository: ...

    @property
    def anken(self) -> AnkenRepository: ...

    @property
    def calendar(self) -> CalendarRepository: ...

    @property
    def settings(self) -> SettingsRepository: ...

    @property
    def sync_runs(self) -> SyncRunRepository: ...

    def __enter__(self) -> "UnitOfWork": ...

    def __exit__(self, *exc: object) -> None:
        """Rolls back anything not committed."""

    def commit(self) -> None: ...


class UnitOfWorkFactory(Protocol):
    def __call__(self) -> UnitOfWork: ...
