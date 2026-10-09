from datetime import timedelta

import pytest

from tests.application.fakes import FixedClock, MemStore, StubReader
from tracker.application.dto import ChangeSnapshot, EpicSnapshot, IssueSnapshot, UserSnapshot, WorklogSnapshot
from tracker.application.ports import JiraAuthError
from tracker.application.sync import SyncFromJira
from tracker.domain.model import EventField, IssueKind, Member, Role, Status, StepKind

AN = UserSnapshot("acc-an", "Nguyễn Thị An", "an@acme.vn")
MINH = UserSnapshot("acc-minh", "Hoàng Minh")
NEW = UserSnapshot("acc-new", "Lê Văn Mới")
EPIC = EpicSnapshot("PAY-1100", "PAY", "Payment flow revision", "2026-11-20")


def snap(key, summary, parent, *, status="In Progress", cat="indeterminate", assignee=AN, fields=None, worklogs=(), changes=()):
    return IssueSnapshot(key=key, project="PAY", summary=summary, status_name=status, status_category=cat, assignee=assignee,
                         parent_key=parent, created="2026-09-01", updated="2026-10-06T10:00:00+00:00",
                         fields=fields or {}, worklogs=tuple(worklogs), changes=tuple(changes))


PARENT = snap("PAY-1102", "[Implement_Coding] Payment flow revision", "PAY-1100")
CREATE = snap("PAY-1201", "[Implement_Coding][F01] Payment method selection – Create", "PAY-1102",
              fields={"customfield_10020": "2026-09-14", "customfield_10016": "2026-09-15T00:00:00.000+0700", "timeoriginalestimate": 36000},
              worklogs=[WorklogSnapshot(AN, "2026-09-15", 2.5, "spec")])
REVIEW = snap("PAY-1202", "[Implement_Coding][F01] Payment method selection – BAL Review", "PAY-1102", status="Resolved", cat="done",
              assignee=MINH, changes=[ChangeSnapshot("2026-09-16", "assignee", "acc-an", "acc-minh", AN, MINH),
                                      ChangeSnapshot("2026-09-17", "status", "In Progress", "Resolved")])
OTHER = snap("PAY-1180", "Prepare Q&A list for JM", "PAY-1102", status="To Do", cat="new", assignee=NEW)


@pytest.fixture()
def store():
    s = MemStore()
    with s() as uow:
        uow.members.save(Member("an", "An", "Nguyễn Thị An", Role.BA, "VN", "#111", jira_account_id="acc-an"))
        st = uow.settings.load()
        st.jira.epics = ["PAY-1100"]
        st.status_mapping.by_name = {"Resolved": "R"}
        uow.settings.save(st)
        uow.commit()
    return s


def run(store, reader, full=True):
    return SyncFromJira(store, reader, FixedClock()).run(full=full)


def issues(store):
    with store() as uow:
        return {i.key: i for i in uow.issues.list()}


def test_full_sync_maps_snapshots_to_domain(store):
    r = run(store, StubReader([PARENT, CREATE, REVIEW, OTHER], {"PAY-1100": EPIC}))
    assert r.status == "ok" and r.issues == 4
    got = issues(store)
    p, c, rv, o = got["PAY-1102"], got["PAY-1201"], got["PAY-1202"], got["PAY-1180"]
    assert p.kind == IssueKind.PARENT and p.phase == "Implement_Coding" and p.epic_key == "PAY-1100"
    assert (c.kind, c.epic_key, c.phase, c.function_id, c.function_name, c.step) == (
        IssueKind.SUBTASK, "PAY-1100", "Implement_Coding", "F01", "Payment method selection", StepKind.CREATE)
    assert (c.planned_start, c.planned_end, c.estimate_h, c.status) == ("2026-09-14", "2026-09-15", 10, Status.WIP)
    assert c.assignee_id == "an" and c.worklogs[0].member_id == "an" and c.worklogs[0].hours == 2.5
    assert rv.status == Status.RESOLVED  # by-name mapping wins over the "done" category
    assert [(e.field, e.to_value) for e in rv.events] == [(EventField.ASSIGNEE, rv.assignee_id), (EventField.STATUS, "R")]
    assert o.function_id is None and o.status == Status.NYS and o.phase == "Implement_Coding"


def test_unknown_account_becomes_member_without_role(store):
    run(store, StubReader([PARENT, OTHER], {"PAY-1100": EPIC}))
    with store() as uow:
        m = uow.members.by_account("acc-new")
        assert (m.id, m.name, m.role, m.country) == ("le-van-moi", "Lê Văn Mới", None, "VN")
        minh = uow.members.by_account("acc-minh")
        assert minh is None  # only accounts that appear on synced issues are created


def test_epic_becomes_anken_and_statuses_are_recorded(store):
    run(store, StubReader([PARENT, CREATE, REVIEW], {"PAY-1100": EPIC}))
    with store() as uow:
        [a] = uow.anken.list()
        assert (a.key, a.title, a.due_date) == ("PAY-1100", "Payment flow revision", "2026-11-20")
        assert uow.settings.load().status_mapping.seen == {"In Progress": "indeterminate", "Resolved": "done"}


def test_failed_read_keeps_existing_data_and_records_error(store):
    run(store, StubReader([PARENT, CREATE], {"PAY-1100": EPIC}))
    r = run(store, StubReader(fail=JiraAuthError("401 Unauthorized")))
    assert r.status == "failed" and "401" in r.error
    assert set(issues(store)) == {"PAY-1102", "PAY-1201"}


def test_full_sync_removes_issues_no_longer_in_jira_but_incremental_keeps_them(store):
    run(store, StubReader([PARENT, CREATE, OTHER], {"PAY-1100": EPIC}))
    run(store, StubReader([PARENT, CREATE], {"PAY-1100": EPIC}), full=False)
    assert "PAY-1180" in issues(store)
    run(store, StubReader([PARENT, CREATE], {"PAY-1100": EPIC}), full=True)
    assert "PAY-1180" not in issues(store)


def test_incremental_sync_asks_for_changes_since_last_success_minus_margin(store):
    run(store, StubReader([PARENT], {"PAY-1100": EPIC}))
    reader = StubReader([CREATE], {"PAY-1100": EPIC})
    run(store, reader, full=False)
    assert reader.calls[0][1] == FixedClock().now() - timedelta(minutes=5)
    assert issues(store)["PAY-1201"].phase == "Implement_Coding"  # parent came from the earlier sync


def test_no_tracked_epics_is_a_successful_empty_sync():
    s = MemStore()
    assert SyncFromJira(s, StubReader(), FixedClock()).run(full=True).status == "ok"


def test_refresh_resyncs_only_the_given_keys(store):
    reader = StubReader([PARENT, CREATE, REVIEW], {"PAY-1100": EPIC})
    run(store, reader)
    reader.issues = [PARENT, snap("PAY-1201", CREATE.summary, "PAY-1102", status="Closed", cat="done"), REVIEW]
    r = SyncFromJira(store, reader, FixedClock()).refresh(["PAY-1201", "PAY-9999"])
    assert r.status == "ok" and r.issues == 1
    got = issues(store)
    assert got["PAY-1201"].status == Status.CLOSED and got["PAY-1201"].phase == "Implement_Coding"


def test_concurrent_runs_do_not_overlap(store):
    import threading
    order = []

    class SlowReader(StubReader):
        def search(self, epic_keys, updated_since):
            order.append("start")
            threading.Event().wait(0.05)
            order.append("end")
            return [PARENT]

    sync = SyncFromJira(store, SlowReader([], {"PAY-1100": EPIC}), FixedClock())
    threads = [threading.Thread(target=sync.run, kwargs={"full": True}) for _ in range(2)]
    for t in threads:
        t.start()
    for t in threads:
        t.join()
    assert order == ["start", "end", "start", "end"]
