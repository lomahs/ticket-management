"""In-memory implementations of the application ports, for use-case tests."""

import copy
from dataclasses import replace
from datetime import datetime, timezone

from tracker.application.ports import JiraError, SyncRun
from tracker.application.settings import TrackerSettings


class _Data:
    def __init__(self):
        self.members, self.issues, self.anken = {}, {}, {}
        self.leaves, self.holidays, self.extra_days = {}, {}, {}
        self.settings = TrackerSettings()
        self.runs: list[SyncRun] = []


class MemRepo:
    def __init__(self, store: dict):
        self.s = store


class Members(MemRepo):
    def list(self):
        return list(self.s.values())

    def get(self, mid):
        return self.s.get(mid)

    def by_account(self, acc):
        return next((m for m in self.s.values() if m.jira_account_id == acc), None)

    def save(self, m):
        self.s[m.id] = m


class Issues(MemRepo):
    def list(self, epic_keys=None):
        return [i for i in self.s.values() if epic_keys is None or i.epic_key in epic_keys]

    def get(self, key):
        return self.s.get(key)

    def save(self, i):
        self.s[i.key] = i

    def delete(self, keys):
        for k in list(keys):
            self.s.pop(k, None)


class AnkenRepo(MemRepo):
    def list(self):
        return list(self.s.values())

    def save(self, a):
        self.s[a.key] = a

    def delete_except(self, keys):
        for k in [k for k in self.s if k not in keys]:
            del self.s[k]


class Calendar:
    def __init__(self, d: _Data):
        self.d = d

    def leaves(self):
        return list(self.d.leaves.values())

    def add_leave(self, lv):
        if not lv.id:
            lv = replace(lv, id=max(self.d.leaves, default=0) + 1)
        self.d.leaves[lv.id] = lv
        return lv

    def delete_leave(self, lid):
        self.d.leaves.pop(lid, None)

    def holidays(self):
        return dict(self.d.holidays)

    def save_holiday(self, h):
        self.d.holidays[h.date] = h

    def delete_holiday(self, date):
        self.d.holidays.pop(date, None)

    def extra_days(self):
        return dict(self.d.extra_days)

    def save_extra_day(self, date, note):
        self.d.extra_days[date] = note

    def delete_extra_day(self, date):
        self.d.extra_days.pop(date, None)


class Settings:
    def __init__(self, d: _Data):
        self.d = d

    def load(self):
        return copy.deepcopy(self.d.settings)

    def save(self, s):
        self.d.settings = copy.deepcopy(s)


class Runs:
    def __init__(self, d: _Data):
        self.d = d

    def start(self, full, at):
        rid = len(self.d.runs) + 1
        self.d.runs.append(SyncRun(rid, at, None, full, "running", 0, None))
        return rid

    def finish(self, rid, at, status, issues, error):
        self.d.runs[rid - 1] = replace(self.d.runs[rid - 1], finished_at=at, status=status, issues=issues, error=error)

    def latest(self):
        return self.d.runs[-1] if self.d.runs else None

    def latest_ok(self):
        return next((r for r in reversed(self.d.runs) if r.status == "ok"), None)


class MemUoW:
    """Commit copies the working data back to the shared store; leaving without commit discards changes."""

    def __init__(self, shared: _Data):
        self.shared = shared

    def __enter__(self):
        self.work = copy.deepcopy(self.shared)
        w = self.work
        self.members, self.issues, self.anken = Members(w.members), Issues(w.issues), AnkenRepo(w.anken)
        self.calendar, self.settings, self.sync_runs = Calendar(w), Settings(w), Runs(w)
        return self

    def __exit__(self, *exc):
        self.work = None

    def commit(self):
        self.shared.__dict__.update(copy.deepcopy(self.work).__dict__)


class MemStore:
    def __init__(self):
        self.data = _Data()

    def __call__(self):
        return MemUoW(self.data)


class FixedClock:
    def __init__(self, today="2026-10-07"):
        self._today = today

    def today(self):
        return self._today

    def now(self):
        return datetime(2026, 10, 7, 9, 0, tzinfo=timezone.utc)


class StubReader:
    def __init__(self, issues=(), epics=None, fail: Exception | None = None):
        self.issues, self.epics, self.fail = list(issues), epics or {}, fail
        self.calls = []

    def search(self, epic_keys, updated_since):
        self.calls.append((tuple(epic_keys), updated_since))
        if self.fail:
            raise self.fail
        return [i for i in self.issues]

    def get_epic(self, key):
        return self.epics.get(key)

    def get_issue(self, key):
        return next((i for i in self.issues if i.key == key), None)

    def test_connection(self):
        return not self.fail


__all__ = ["MemStore", "FixedClock", "StubReader", "JiraError"]
