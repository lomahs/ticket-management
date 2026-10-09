"""SyncFromJira: reads the tracked epics from Jira and stores them as domain issues."""

import re
import threading
import unicodedata
import zlib
from collections.abc import Sequence
from dataclasses import dataclass
from datetime import timedelta

from tracker.domain.model import Anken, EventField, Issue, IssueEvent, IssueKind, Member, Status, Worklog
from tracker.domain.naming import compile_rule, parse_phase, parse_summary

from .dto import IssueSnapshot, UserSnapshot
from .ports import Clock, JiraReader, UnitOfWork, UnitOfWorkFactory
from .settings import TrackerSettings

INCREMENTAL_MARGIN = timedelta(minutes=5)
_RUN_LOCK = threading.Lock()  # one sync at a time per process (scheduler, Sync button, settings changes)
CATEGORY_STATUS = {"new": Status.NYS, "indeterminate": Status.WIP, "done": Status.CLOSED}
SECONDS_FIELDS = {"timeoriginalestimate", "aggregatetimeoriginalestimate", "timeestimate"}
MEMBER_COLORS = ["oklch(0.55 0.13 255)", "oklch(0.52 0.13 305)", "oklch(0.56 0.14 35)", "oklch(0.52 0.11 150)",
                 "oklch(0.5 0.1 210)", "oklch(0.48 0.04 260)", "oklch(0.55 0.15 0)", "oklch(0.5 0.12 100)"]


@dataclass(frozen=True)
class SyncResult:
    run_id: int
    status: str  # ok | failed
    issues: int
    error: str | None


def slugify(name: str) -> str:
    folded = "".join(c for c in unicodedata.normalize("NFD", name) if unicodedata.category(c) != "Mn")
    folded = folded.replace("đ", "d").replace("Đ", "D")
    return re.sub(r"[^a-z0-9]+", "-", folded.lower()).strip("-") or "member"


def member_color(member_id: str) -> str:
    return MEMBER_COLORS[zlib.crc32(member_id.encode()) % len(MEMBER_COLORS)]


def _date(v) -> str | None:
    return str(v)[:10] if v else None


class _Mapper:
    """Turns snapshots into domain objects for one sync, creating members for unknown accounts."""

    def __init__(self, uow: UnitOfWork, settings: TrackerSettings, epics: Sequence[str]):
        self.uow, self.s, self.epics = uow, settings, set(epics)
        self.rule = compile_rule(settings.name_rules.rule, settings.name_rules.aliases)
        self.members = {m.jira_account_id: m for m in uow.members.list() if m.jira_account_id}
        self.ids = {m.id for m in uow.members.list()}

    def member(self, user: UserSnapshot | None) -> str | None:
        if not user:
            return None
        m = self.members.get(user.account_id)
        if not m:
            base = mid = slugify(user.display_name)
            n = 2
            while mid in self.ids:
                mid, n = f"{base}-{n}", n + 1
            m = Member(mid, user.display_name, user.display_name, None, self.s.default_country, member_color(mid),
                       email=user.email, jira_account_id=user.account_id)
            self.uow.members.save(m)
            self.members[user.account_id], self.ids = m, self.ids | {mid}
        return m.id

    def status(self, name: str | None, category: str | None = None) -> Status:
        mapped = self.s.status_mapping.by_name.get(name or "")
        if mapped:
            return Status(mapped)
        cat = category or self.s.status_mapping.seen.get(name or "")
        return CATEGORY_STATUS.get(cat or "", Status.WIP)

    def hours(self, snap: IssueSnapshot) -> float:
        fid = self.s.field_mapping.effort
        v = snap.fields.get(fid)
        if v in (None, ""):
            return 0
        return float(v) / 3600 if fid in SECONDS_FIELDS else float(v)

    def issue(self, snap: IssueSnapshot, parent: Issue | None) -> Issue:
        is_parent = snap.parent_key in self.epics
        fm = self.s.field_mapping
        i = Issue(
            key=snap.key, project=snap.project,
            epic_key=snap.parent_key if is_parent else (parent.epic_key if parent else ""),
            parent_key=snap.parent_key, kind=IssueKind.PARENT if is_parent else IssueKind.SUBTASK,
            summary=snap.summary,
            phase=parse_phase(snap.summary) if is_parent else (parent.phase if parent else ""),
            status=self.status(snap.status_name, snap.status_category), jira_status=snap.status_name,
            assignee_id=self.member(snap.assignee),
            planned_start=_date(snap.fields.get(fm.planned_start)), planned_end=_date(snap.fields.get(fm.planned_end)),
            estimate_h=self.hours(snap), created=snap.created,
        )
        if not is_parent:
            p = parse_summary(self.rule, snap.summary)
            if p and p.values.get("function_id"):
                i.function_id, i.function_name, i.step = p.values["function_id"], p.values.get("function_name"), p.step
        i.worklogs = [Worklog(w.date, self.member(w.author), w.hours, w.comment) for w in snap.worklogs]
        for c in snap.changes:
            if c.field == "assignee":
                i.events.append(IssueEvent(c.at, EventField.ASSIGNEE, self.member(c.from_user), self.member(c.to_user)))
            elif c.field == "status":
                i.events.append(IssueEvent(c.at, EventField.STATUS, self.status(c.from_value).value if c.from_value else None,
                                           self.status(c.to_value).value if c.to_value else None))
        return i


class SyncFromJira:
    def __init__(self, uow_factory: UnitOfWorkFactory, reader: JiraReader, clock: Clock):
        self.uow_factory, self.reader, self.clock = uow_factory, reader, clock

    def run(self, full: bool = False) -> SyncResult:
        with _RUN_LOCK:
            return self._run(full)

    def _run(self, full: bool) -> SyncResult:
        with self.uow_factory() as uow:
            last_ok = uow.sync_runs.latest_ok()
            run_id = uow.sync_runs.start(full, self.clock.now())
            uow.commit()
        since = None if full or not last_ok else last_ok.started_at - INCREMENTAL_MARGIN
        try:
            with self.uow_factory() as uow:
                n = self._sync(uow, since, full)
                uow.sync_runs.finish(run_id, self.clock.now(), "ok", n, None)
                uow.commit()
            return SyncResult(run_id, "ok", n, None)
        except Exception as e:  # noqa: BLE001 - any failure is recorded on the run; stored data stays as it was
            with self.uow_factory() as uow:
                uow.sync_runs.finish(run_id, self.clock.now(), "failed", 0, f"{type(e).__name__}: {e}")
                uow.commit()
            return SyncResult(run_id, "failed", 0, f"{type(e).__name__}: {e}")

    def refresh(self, keys: Sequence[str]) -> SyncResult:
        """Re-reads specific issues after a write; keys Jira no longer has are skipped."""
        with _RUN_LOCK, self.uow_factory() as uow:
            settings = uow.settings.load()
            mapper = _Mapper(uow, settings, settings.jira.epics)
            n = 0
            for key in keys:
                snap = self.reader.get_issue(key)
                if not snap:
                    continue
                parent = uow.issues.get(snap.parent_key) if snap.parent_key not in mapper.epics else None
                uow.issues.save(mapper.issue(snap, parent))
                n += 1
            uow.commit()
        return SyncResult(0, "ok", n, None)

    def _sync(self, uow: UnitOfWork, since, full: bool) -> int:
        settings = uow.settings.load()
        epics = list(settings.jira.epics)
        if not epics:
            return 0
        for key in epics:
            e = self.reader.get_epic(key)
            if e:
                uow.anken.save(Anken(e.key, e.project, e.summary, e.due_date))
        uow.anken.delete_except(epics)

        snaps = list(self.reader.search(epics, since))
        for s in snaps:
            settings.status_mapping.seen[s.status_name] = s.status_category
        mapper = _Mapper(uow, settings, epics)
        parents = {i.key: i for i in uow.issues.list(epics) if i.kind == IssueKind.PARENT}
        for s in sorted(snaps, key=lambda s: s.parent_key not in mapper.epics):  # parents first
            i = mapper.issue(s, parents.get(s.parent_key or ""))
            if i.kind == IssueKind.PARENT:
                parents[i.key] = i
            uow.issues.save(i)
        if full:
            seen = {s.key for s in snaps}
            uow.issues.delete([i.key for i in uow.issues.list() if i.key not in seen])
        uow.settings.save(settings)
        return len(snaps)
