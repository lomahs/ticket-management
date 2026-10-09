import pytest

from tests.application.demo import demo_deps
from tracker.application.errors import NotFound
from tracker.application.use_cases import system, work


@pytest.fixture(scope="module")
def deps(tmp_path_factory):
    return demo_deps(tmp_path_factory.mktemp("work"))


def test_meta_reports_mode_today_capabilities_and_sync(deps):
    m = system.meta(deps)
    assert (m.mode, m.today, m.capabilities.jira_write) == ("demo", "2026-10-07", True)
    assert m.sync.status == "ok" and m.projects == ["CRM", "OPS", "PAY"]


def test_members_list(deps):
    ms = system.members(deps)
    assert [m.id for m in ms][:2] == ["an", "binh"] and next(m for m in ms if m.id == "tanaka").role == "JM"


def test_my_work_focus_groups_for_binh(deps):
    w = work.my_work(deps, "binh", "week")
    assert w.next_day == "2026-10-08"
    assert [c.key for c in w.delay] == ["PAY-1012", "PAY-1186", "PAY-1184", "PAY-1185", "PAY-1213"]  # by planned end, then key
    assert {c.key for c in w.due_today} == {"PAY-1183", "PAY-1187", "PAY-1188", "PAY-1265"}
    assert "PAY-1220" in {c.key for c in w.due_next}


def test_my_work_backlog_groups_by_week_with_overdue_first(deps):
    w = work.my_work(deps, "binh", "week")
    assert w.backlog[0].kind == "overdue"
    weeks = [g for g in w.backlog if g.kind == "week"]
    assert weeks[0].start == "2026-10-05" and weeks[0].current
    assert w.open_count == sum(len(g.items) for g in w.backlog)
    assert w.remaining_h == round(sum(g.remaining_h for g in w.backlog), 2)


def test_my_work_backlog_by_month(deps):
    w = work.my_work(deps, "binh", "month")
    assert [g.kind for g in w.backlog][:2] == ["overdue", "month"] and w.backlog[1].start == "2026-10"


def test_related_tasks_for_owner_and_reviewer(deps):
    assert work.my_work(deps, "binh", "week").related_scope == "owner"
    minh = work.my_work(deps, "minh", "week")
    assert minh.related_scope == "reviewer"
    assert all(c.step != "balr" for c in minh.related)


def test_unknown_member(deps):
    with pytest.raises(NotFound):
        work.my_work(deps, "nobody", "week")
