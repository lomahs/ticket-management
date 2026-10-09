from datetime import datetime, timezone

import pytest

from tracker.adapters.persistence.db import Database
from tracker.adapters.persistence.secrets import FernetSecretBox
from tracker.domain.model import (
    Anken, EventField, Holiday, Issue, IssueEvent, IssueKind, Leave, LeaveType, Member, Permission, Role, Status, StepKind, Worklog,
)

AT = datetime(2026, 10, 7, 9, 0, tzinfo=timezone.utc)


@pytest.fixture()
def db(tmp_path):
    d = Database(f"sqlite:///{tmp_path / 'tracker.db'}")
    d.migrate()
    return d


def issue(key="PAY-1201"):
    return Issue(key=key, project="PAY", epic_key="PAY-1100", parent_key="PAY-1102", kind=IssueKind.SUBTASK,
                 summary="[Implement_Coding][F01] X – Create", phase="Implement_Coding", status=Status.WIP, assignee_id="an",
                 planned_start="2026-09-14", planned_end="2026-09-15", estimate_h=10, created="2026-09-01", jira_status="In Progress",
                 function_id="F01", function_name="X", step=StepKind.CREATE,
                 worklogs=[Worklog("2026-09-15", "an", 2.5, "spec")], events=[IssueEvent("2026-09-14", EventField.ASSIGNEE, None, "an")])


def test_migrate_is_idempotent(db):
    db.migrate()


def test_members_round_trip(db):
    m = Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", "an@acme.vn", Permission.ADMIN, False, "acc-an")
    with db.uow() as u:
        u.members.save(m)
        u.members.save(Member("x", "X", "X", None, "JP", "#222"))
        u.commit()
    with db.uow() as u:
        assert u.members.get("an") == m and u.members.by_account("acc-an") == m
        assert u.members.get("x").role is None
        assert [x.id for x in u.members.list()] == ["an", "x"]


def test_issue_round_trip_replaces_children(db):
    with db.uow() as u:
        u.issues.save(issue())
        u.commit()
    with db.uow() as u:
        got = u.issues.get("PAY-1201")
        assert got == issue()
        got.worklogs = [Worklog("2026-09-16", "an", 1, "")]
        u.issues.save(got)
        u.commit()
    with db.uow() as u:
        assert [w.hours for w in u.issues.get("PAY-1201").worklogs] == [1]
        assert [i.key for i in u.issues.list(["PAY-1100"])] == ["PAY-1201"] and u.issues.list(["X"]) == []
        u.issues.delete(["PAY-1201"])
        u.commit()
    with db.uow() as u:
        assert u.issues.get("PAY-1201") is None


def test_uncommitted_work_is_rolled_back(db):
    with db.uow() as u:
        u.issues.save(issue())
    with db.uow() as u:
        assert u.issues.get("PAY-1201") is None


def test_calendar_round_trip(db):
    with db.uow() as u:
        lv = u.calendar.add_leave(Leave(0, "an", "2026-10-16", LeaveType.FULL, "trip"))
        u.calendar.save_holiday(Holiday("2026-09-02", ("VN",), "National Day"))
        u.calendar.save_extra_day("2026-10-10", "Release")
        u.commit()
    assert lv.id > 0
    with db.uow() as u:
        assert u.calendar.leaves() == [lv]
        assert u.calendar.holidays() == {"2026-09-02": Holiday("2026-09-02", ("VN",), "National Day")}
        assert u.calendar.extra_days() == {"2026-10-10": "Release"}
        u.calendar.delete_leave(lv.id)
        u.calendar.delete_holiday("2026-09-02")
        u.calendar.delete_extra_day("2026-10-10")
        u.commit()
    with db.uow() as u:
        assert (u.calendar.leaves(), u.calendar.holidays(), u.calendar.extra_days()) == ([], {}, {})


def test_settings_and_anken_and_runs(db):
    with db.uow() as u:
        s = u.settings.load()
        s.jira.epics = ["PAY-1100"]
        s.name_rules.aliases[StepKind.CREATE] = "Create, Tạo"
        s.working_hours.days = (1, 2, 3, 4, 5, 6)
        u.settings.save(s)
        u.anken.save(Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20"))
        rid = u.sync_runs.start(True, AT)
        u.sync_runs.finish(rid, AT, "ok", 12, None)
        u.commit()
    with db.uow() as u:
        s = u.settings.load()
        assert s.jira.epics == ["PAY-1100"] and s.name_rules.aliases[StepKind.CREATE] == "Create, Tạo"
        assert s.working_hours.days == (1, 2, 3, 4, 5, 6)
        assert u.anken.list() == [Anken("PAY-1100", "PAY", "Payment flow revision", "2026-11-20")]
        r = u.sync_runs.latest()
        assert (r.status, r.issues, r.full, r.started_at) == ("ok", 12, True, AT)
        assert u.sync_runs.latest_ok() == r


def test_secret_box_round_trip_and_key_file(tmp_path):
    box = FernetSecretBox.from_key_file(tmp_path / "secret.key")
    token = box.encrypt("ATATT3xFfGF0-real-token")
    assert token != "ATATT3xFfGF0-real-token"
    assert FernetSecretBox.from_key_file(tmp_path / "secret.key").decrypt(token) == "ATATT3xFfGF0-real-token"
