"""A simulated Jira site serving the demo dataset through the JiraReader / JiraWriter ports."""

from collections.abc import Sequence
from datetime import datetime

from tracker.application.dto import (
    ChangeSnapshot, EpicSnapshot, IssueSnapshot, NewSubtask, UserSnapshot, WorklogSnapshot,
)
from tracker.application.ports import Clock, SecretBox, UnitOfWork
from tracker.application.settings import FieldMapping, JiraConnection, StatusMapping, TrackerSettings
from tracker.domain.model import Status

from .dataset import (
    EPICS, EXTRA_DAYS, MEMBERS, STATUS_BY_NAME, STATUS_NAMES, TRACKED_EPICS, FakeIssue, account_of, build_issues,
    demo_holidays, demo_leaves, demo_members,
)

_USERS = {mid: UserSnapshot(account_of(mid), full, email) for mid, _n, full, _r, _c, _col, email, _p, _x in MEMBERS}
_FIELDS = FieldMapping()


class FakeJira:
    """In-memory Jira site. Writes change the issues and mark them updated, like the real thing."""

    def __init__(self, clock: Clock):
        self.clock = clock
        self.issues: dict[str, FakeIssue] = {i.key: i for i in build_issues()}
        self.next_key = 1501

    # --- JiraReader ---------------------------------------------------------------------------------------------

    def _snapshot(self, i: FakeIssue) -> IssueSnapshot:
        name, cat = STATUS_NAMES[i.status]
        fields = {_FIELDS.planned_start: i.ps, _FIELDS.planned_end: i.pe, _FIELDS.effort: int(i.est_h * 3600) if i.est_h else None}
        changes = []
        for at, fld, frm, to in i.changes:
            if fld == "assignee":
                changes.append(ChangeSnapshot(at, "assignee", frm and account_of(frm), to and account_of(to),
                                              _USERS.get(frm or ""), _USERS.get(to or "")))
            else:
                changes.append(ChangeSnapshot(at, "status", STATUS_NAMES[frm][0] if frm else None, STATUS_NAMES[to][0] if to else None))
        return IssueSnapshot(
            key=i.key, project=i.project, summary=i.summary, status_name=name, status_category=cat,
            assignee=_USERS.get(i.assignee or ""), parent_key=i.parent, created=i.created, updated=i.updated,
            fields=fields, worklogs=tuple(WorklogSnapshot(_USERS[w], d, h, c) for d, w, h, c in i.worklogs),
            changes=tuple(changes),
        )

    def search(self, epic_keys: Sequence[str], updated_since: datetime | None):
        epics = set(epic_keys)
        parents = {k for k, i in self.issues.items() if i.parent in epics}
        since = updated_since.isoformat() if updated_since else None
        for i in self.issues.values():
            if (i.parent in epics or i.parent in parents) and (since is None or i.updated >= since):
                yield self._snapshot(i)

    def get_epic(self, key: str) -> EpicSnapshot | None:
        e = EPICS.get(key)
        return EpicSnapshot(key, e[0], e[1], e[2]) if e else None

    def get_issue(self, key: str) -> IssueSnapshot | None:
        i = self.issues.get(key)
        return self._snapshot(i) if i else None

    def test_connection(self) -> bool:
        return True

    # --- JiraWriter ---------------------------------------------------------------------------------------------

    def _touch(self, i: FakeIssue) -> None:
        i.updated = self.clock.now().isoformat()

    def update_issue(self, key: str, *, planned_start: str | None = None, planned_end: str | None = None,
                     assignee_account_id: str | None = None) -> None:
        i = self.issues[key]
        if planned_start:
            i.ps = planned_start
        if planned_end:
            i.pe = planned_end
        if assignee_account_id:
            to = assignee_account_id.removeprefix("acc-")
            if to != i.assignee:
                i.changes.append((self.clock.today(), "assignee", i.assignee, to))
                i.assignee = to
        self._touch(i)

    def transition(self, key: str, status: Status) -> None:
        i = self.issues[key]
        if status.value != i.status:
            i.changes.append((self.clock.today(), "status", i.status, status.value))
            i.status = status.value
        self._touch(i)

    def create_subtasks(self, parent_key: str, subtasks: Sequence[NewSubtask]) -> list[str]:
        keys = []
        project = parent_key.split("-")[0]
        for s in subtasks:
            key = f"{project}-{self.next_key}"
            self.next_key += 1
            self.issues[key] = FakeIssue(key, s.summary, parent_key, "N", (s.assignee_account_id or "").removeprefix("acc-") or None,
                                         s.planned_start, s.planned_end, s.estimate_h, self.clock.today())
            self._touch(self.issues[key])
            keys.append(key)
        return keys


def seed_tracker(uow: UnitOfWork, box: SecretBox) -> None:
    """Tracker-local data an admin configures: members and roles, calendar, settings."""
    for m in demo_members():
        uow.members.save(m)
    for h in demo_holidays().values():
        uow.calendar.save_holiday(h)
    for lv in demo_leaves():
        uow.calendar.add_leave(lv)
    for d, note in EXTRA_DAYS.items():
        uow.calendar.save_extra_day(d, note)
    s = TrackerSettings()
    s.jira = JiraConnection("https://acme-pay.atlassian.net", "tracker-bot@acme.vn", box.encrypt("ATATT3xFfGF0-demo-token"),
                            "15", list(TRACKED_EPICS), "ok")
    s.status_mapping = StatusMapping(dict(STATUS_BY_NAME))
    uow.settings.save(s)
