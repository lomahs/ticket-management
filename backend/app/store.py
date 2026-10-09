"""In-memory state with JSON-file persistence.

Every mutation goes through `Store.mutate()`, which holds the lock, applies the change
and writes the file, so the API stays consistent under concurrent requests.
"""

import copy
import json
import os
import threading
from contextlib import contextmanager
from datetime import datetime, timedelta, timezone
from pathlib import Path

from .dates import add, parse
from .domain import LEAVE_TYPES, STEPS, annotate
from .seed import ANKEN, EPIC_CATALOG, JIRA_FIELDS, PARENTS, PROJECTS, TODAY, build_initial_state


def _now_iso() -> str:
    return datetime.now(timezone.utc).isoformat()


class Store:
    def __init__(self, path: Path | None, today: str = TODAY):
        self.path = path
        self.today = today
        self.lock = threading.RLock()
        self.state = self._load()

    def _load(self) -> dict:
        if self.path and self.path.exists():
            return json.loads(self.path.read_text("utf-8"))
        state = build_initial_state(self.today)
        state["lastSync"] = (datetime.now(timezone.utc) - timedelta(minutes=2)).isoformat()
        self._write(state)
        return state

    def _write(self, state: dict):
        if not self.path:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_suffix(".tmp")
        tmp.write_text(json.dumps(state, ensure_ascii=False), "utf-8")
        os.replace(tmp, self.path)

    @contextmanager
    def mutate(self):
        with self.lock:
            yield self.state
            self._write(self.state)

    def reset(self):
        with self.lock:
            if self.path and self.path.exists():
                self.path.unlink()
            self.state = self._load()

    def touch_sync(self):
        with self.mutate() as s:
            s["lastSync"] = _now_iso()

    def snapshot(self) -> dict:
        with self.lock:
            s = copy.deepcopy(self.state)
        annotate(s, self.today)
        jira = s["settings"]["jira"]
        tok = jira.pop("token", "")
        jira["tokenMasked"] = (tok[:12] + "••••••••") if tok else ""
        jira["epicInfo"] = {k: {"prj": EPIC_CATALOG[k][0], "title": EPIC_CATALOG[k][1]} for k in jira["epics"] if k in EPIC_CATALOG}
        nums = [int(f["id"][1:]) for f in s["functions"] if f["id"][1:].isdigit()]
        week_end = add(self.today, 6 - parse(self.today).weekday())
        return {
            "today": self.today,
            "weekEnd": week_end,
            "lastSync": s.pop("lastSync", None),
            "members": s["members"],
            "config": s["config"],
            "holidays": s["holidays"],
            "extraDays": s["extraDays"],
            "leaves": s["leaves"],
            "leaveTypes": LEAVE_TYPES,
            "projects": PROJECTS,
            "steps": STEPS,
            "functions": s["functions"],
            "tasks": s["tasks"],
            "anken": ANKEN,
            "parents": PARENTS,
            "jiraFields": JIRA_FIELDS,
            "timesheet": s["timesheet"],
            "week": s["week"],
            "settings": s["settings"],
            "nextFid": "F%02d" % ((max(nums) if nums else 0) + 1),
        }
