"""App-wide reads: mode and capabilities, members, sync status; running a sync."""

from dataclasses import dataclass
from datetime import datetime

from tracker.domain.model import Member

from ..context import Deps
from ..ports import SyncRun


@dataclass(frozen=True)
class Capabilities:
    jira_write: bool


@dataclass(frozen=True)
class SyncStatus:
    status: str | None  # running | ok | failed | None (never synced)
    started_at: datetime | None
    finished_at: datetime | None
    error: str | None
    last_ok_at: datetime | None


@dataclass(frozen=True)
class Meta:
    mode: str
    today: str
    capabilities: Capabilities
    sync: SyncStatus
    projects: list[str]


def _status(latest: SyncRun | None, ok: SyncRun | None) -> SyncStatus:
    return SyncStatus(latest.status if latest else None, latest.started_at if latest else None,
                      latest.finished_at if latest else None, latest.error if latest else None,
                      ok.finished_at if ok else None)


def sync_status(d: Deps) -> SyncStatus:
    with d.uow() as u:
        return _status(u.sync_runs.latest(), u.sync_runs.latest_ok())


def meta(d: Deps) -> Meta:
    with d.uow() as u:
        projects = sorted({a.project for a in u.anken.list()})
        st = _status(u.sync_runs.latest(), u.sync_runs.latest_ok())
    return Meta(d.mode, d.clock.today(), Capabilities(d.writer is not None), st, projects)


def members(d: Deps) -> list[Member]:
    with d.uow() as u:
        return u.members.list()


def run_sync(d: Deps, full: bool) -> SyncStatus:
    d.sync.run(full=full)
    return sync_status(d)
