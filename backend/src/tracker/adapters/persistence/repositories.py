"""SQLAlchemy implementations of the repository ports."""

from collections.abc import Iterable, Sequence
from dataclasses import replace
from datetime import datetime, timezone

from sqlalchemy import delete, insert, select, update
from sqlalchemy.engine import Connection

from tracker.application.ports import SyncRun
from tracker.application.settings import SECTIONS, TrackerSettings, from_documents, to_documents
from tracker.domain.model import (
    Anken, EventField, Holiday, Issue, IssueEvent, IssueKind, Leave, LeaveType, Member, Permission, Role, Status, StepKind, Worklog,
)

from . import tables as t


class _Repo:
    def __init__(self, conn: Connection):
        self.c = conn


def _member(r) -> Member:
    return Member(r.id, r.name, r.full_name, Role(r.role) if r.role else None, r.country, r.color, r.email,
                  Permission(r.permission), bool(r.external), r.jira_account_id)


class SqlMembers(_Repo):
    def list(self) -> list[Member]:
        return [_member(r) for r in self.c.execute(select(t.members).order_by(t.members.c.id))]

    def get(self, member_id: str) -> Member | None:
        r = self.c.execute(select(t.members).where(t.members.c.id == member_id)).first()
        return _member(r) if r else None

    def by_account(self, account_id: str) -> Member | None:
        r = self.c.execute(select(t.members).where(t.members.c.jira_account_id == account_id)).first()
        return _member(r) if r else None

    def save(self, m: Member) -> None:
        row = dict(id=m.id, jira_account_id=m.jira_account_id, name=m.name, full_name=m.full_name, email=m.email,
                   role=m.role.value if m.role else None, country=m.country, color=m.color, permission=m.permission.value,
                   external=m.external)
        if self.c.execute(update(t.members).where(t.members.c.id == m.id).values(**row)).rowcount == 0:
            self.c.execute(insert(t.members).values(**row))


class SqlIssues(_Repo):
    def _load(self, rows) -> list[Issue]:
        rows = list(rows)
        keys = [r.key for r in rows]
        logs: dict[str, list[Worklog]] = {}
        events: dict[str, list[IssueEvent]] = {}
        for i in range(0, len(keys), 500):
            chunk = keys[i:i + 500]
            for w in self.c.execute(select(t.worklogs).where(t.worklogs.c.issue_key.in_(chunk)).order_by(t.worklogs.c.id)):
                logs.setdefault(w.issue_key, []).append(Worklog(w.date, w.member_id, w.hours, w.note))
            for e in self.c.execute(select(t.issue_events).where(t.issue_events.c.issue_key.in_(chunk)).order_by(t.issue_events.c.id)):
                events.setdefault(e.issue_key, []).append(IssueEvent(e.at, EventField(e.field), e.from_value, e.to_value))
        return [Issue(
            key=r.key, project=r.project, epic_key=r.epic_key, parent_key=r.parent_key, kind=IssueKind(r.kind),
            summary=r.summary, phase=r.phase, status=Status(r.status), assignee_id=r.assignee_id,
            planned_start=r.planned_start, planned_end=r.planned_end, estimate_h=r.estimate_h, created=r.created,
            jira_status=r.jira_status, function_id=r.function_id, function_name=r.function_name,
            step=StepKind(r.step) if r.step else None, worklogs=logs.get(r.key, []), events=events.get(r.key, []),
        ) for r in rows]

    def list(self, epic_keys: Sequence[str] | None = None) -> list[Issue]:
        q = select(t.issues).order_by(t.issues.c.key)
        if epic_keys is not None:
            q = q.where(t.issues.c.epic_key.in_(list(epic_keys)))
        return self._load(self.c.execute(q))

    def get(self, key: str) -> Issue | None:
        found = self._load(self.c.execute(select(t.issues).where(t.issues.c.key == key)))
        return found[0] if found else None

    def save(self, i: Issue) -> None:
        row = dict(key=i.key, project=i.project, epic_key=i.epic_key, parent_key=i.parent_key, kind=i.kind.value,
                   summary=i.summary, phase=i.phase, status=i.status.value, jira_status=i.jira_status, assignee_id=i.assignee_id,
                   planned_start=i.planned_start, planned_end=i.planned_end, estimate_h=i.estimate_h, created=i.created,
                   function_id=i.function_id, function_name=i.function_name, step=i.step.value if i.step else None)
        if self.c.execute(update(t.issues).where(t.issues.c.key == i.key).values(**row)).rowcount == 0:
            self.c.execute(insert(t.issues).values(**row))
        self.c.execute(delete(t.worklogs).where(t.worklogs.c.issue_key == i.key))
        self.c.execute(delete(t.issue_events).where(t.issue_events.c.issue_key == i.key))
        if i.worklogs:
            self.c.execute(insert(t.worklogs), [dict(issue_key=i.key, date=w.date, member_id=w.member_id, hours=w.hours, note=w.note)
                                                for w in i.worklogs])
        if i.events:
            self.c.execute(insert(t.issue_events), [dict(issue_key=i.key, at=e.at, field=e.field.value, from_value=e.from_value,
                                                         to_value=e.to_value) for e in i.events])

    def delete(self, keys: Iterable[str]) -> None:
        keys = list(keys)
        for i in range(0, len(keys), 500):
            chunk = keys[i:i + 500]
            self.c.execute(delete(t.worklogs).where(t.worklogs.c.issue_key.in_(chunk)))
            self.c.execute(delete(t.issue_events).where(t.issue_events.c.issue_key.in_(chunk)))
            self.c.execute(delete(t.issues).where(t.issues.c.key.in_(chunk)))


class SqlAnken(_Repo):
    def list(self) -> list[Anken]:
        return [Anken(r.key, r.project, r.title, r.due_date) for r in self.c.execute(select(t.anken).order_by(t.anken.c.key))]

    def save(self, a: Anken) -> None:
        row = dict(key=a.key, project=a.project, title=a.title, due_date=a.due_date)
        if self.c.execute(update(t.anken).where(t.anken.c.key == a.key).values(**row)).rowcount == 0:
            self.c.execute(insert(t.anken).values(**row))

    def delete_except(self, keys: Sequence[str]) -> None:
        self.c.execute(delete(t.anken).where(t.anken.c.key.not_in(list(keys))))


class SqlCalendar(_Repo):
    def leaves(self) -> list[Leave]:
        return [Leave(r.id, r.member_id, r.date, LeaveType(r.type), r.note)
                for r in self.c.execute(select(t.leaves).order_by(t.leaves.c.date, t.leaves.c.member_id))]

    def add_leave(self, lv: Leave) -> Leave:
        row = dict(member_id=lv.member_id, date=lv.date, type=lv.type.value, note=lv.note)
        if lv.id:
            self.c.execute(insert(t.leaves).values(id=lv.id, **row))
            return lv
        new_id = self.c.execute(insert(t.leaves).values(**row)).inserted_primary_key[0]
        return replace(lv, id=new_id)

    def delete_leave(self, leave_id: int) -> None:
        self.c.execute(delete(t.leaves).where(t.leaves.c.id == leave_id))

    def holidays(self) -> dict[str, Holiday]:
        return {r.date: Holiday(r.date, tuple(r.countries.split()), r.name) for r in self.c.execute(select(t.holidays))}

    def save_holiday(self, h: Holiday) -> None:
        self.delete_holiday(h.date)
        self.c.execute(insert(t.holidays).values(date=h.date, countries=" ".join(h.countries), name=h.name))

    def delete_holiday(self, date: str) -> None:
        self.c.execute(delete(t.holidays).where(t.holidays.c.date == date))

    def extra_days(self) -> dict[str, str]:
        return {r.date: r.note for r in self.c.execute(select(t.extra_days))}

    def save_extra_day(self, date: str, note: str) -> None:
        self.delete_extra_day(date)
        self.c.execute(insert(t.extra_days).values(date=date, note=note))

    def delete_extra_day(self, date: str) -> None:
        self.c.execute(delete(t.extra_days).where(t.extra_days.c.date == date))


class SqlSettings(_Repo):
    def load(self) -> TrackerSettings:
        return from_documents({r.key: r.value for r in self.c.execute(select(t.settings))})

    def save(self, s: TrackerSettings) -> None:
        docs = to_documents(s)
        self.c.execute(delete(t.settings).where(t.settings.c.key.in_(SECTIONS)))
        self.c.execute(insert(t.settings), [dict(key=k, value=docs[k]) for k in SECTIONS])


def _utc(dt: datetime | None) -> datetime | None:
    return dt.replace(tzinfo=timezone.utc) if dt and dt.tzinfo is None else dt


def _run(r) -> SyncRun:
    return SyncRun(r.id, _utc(r.started_at), _utc(r.finished_at), bool(r.full), r.status, r.issues, r.error)


class SqlSyncRuns(_Repo):
    def start(self, full: bool, at: datetime) -> int:
        return self.c.execute(insert(t.sync_runs).values(started_at=at, full=full, status="running", issues=0)).inserted_primary_key[0]

    def finish(self, run_id: int, at: datetime, status: str, issues: int, error: str | None) -> None:
        self.c.execute(update(t.sync_runs).where(t.sync_runs.c.id == run_id)
                       .values(finished_at=at, status=status, issues=issues, error=error))

    def latest(self) -> SyncRun | None:
        r = self.c.execute(select(t.sync_runs).order_by(t.sync_runs.c.id.desc()).limit(1)).first()
        return _run(r) if r else None

    def latest_ok(self) -> SyncRun | None:
        r = self.c.execute(select(t.sync_runs).where(t.sync_runs.c.status == "ok").order_by(t.sync_runs.c.id.desc()).limit(1)).first()
        return _run(r) if r else None
