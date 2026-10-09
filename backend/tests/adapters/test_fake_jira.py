from tracker.adapters.clock import FixedClock
from tracker.adapters.fake.dataset import DEMO_TODAY, TRACKED_EPICS
from tracker.adapters.fake.jira import FakeJira
from tracker.application.dto import NewSubtask
from tracker.domain.model import Status


def jira():
    return FakeJira(FixedClock(DEMO_TODAY))


def test_search_serves_parents_and_subtasks_of_tracked_epics_only():
    keys = {s.key for s in jira().search(["PAY-1100"], None)}
    assert {"PAY-1102", "PAY-1201", "PAY-1260", "PAY-1180"} <= keys
    assert "PAY-1342" not in keys and "CRM-412" not in keys


def test_every_tracked_issue_belongs_to_a_tracked_epic_and_keys_are_unique():
    j = jira()
    snaps = list(j.search(TRACKED_EPICS, None))
    assert len(snaps) == len({s.key for s in snaps}) == len(j.issues)


def test_unknown_epic_and_non_epic_keys():
    assert jira().get_epic("PAY-1500").summary == "Refund automation"
    assert jira().get_epic("PAY-1201") is None


def test_writes_update_issue_and_mark_it_updated():
    j = jira()
    j.update_issue("PAY-1214", planned_start="2026-10-12", planned_end="2026-10-13", assignee_account_id="acc-an")
    j.transition("PAY-1214", Status.WIP)
    s = j.get_issue("PAY-1214")
    assert (s.fields["customfield_10020"], s.fields["customfield_10016"], s.assignee.account_id, s.status_name) == (
        "2026-10-12", "2026-10-13", "acc-an", "In Progress")
    assert [c.field for c in s.changes][-2:] == ["assignee", "status"]
    assert s.updated > "2026-10-06T10:00:00+00:00"


def test_create_subtasks_allocates_new_keys():
    j = jira()
    keys = j.create_subtasks("PAY-1102", [NewSubtask("[Implement_Coding][F13] X – Create", "acc-an", "2026-10-08", "2026-10-09", 8)])
    assert keys == ["PAY-1501"]
    assert j.get_issue("PAY-1501").fields["timeoriginalestimate"] == 8 * 3600


def test_fake_field_catalog_has_the_mapped_fields():
    ids = {f.id: f.type for f in jira().list_fields()}
    assert ids["customfield_10020"] == "date" and ids["timeoriginalestimate"] == "number"
