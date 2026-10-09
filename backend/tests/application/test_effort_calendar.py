import pytest

from tests.application.demo import demo_deps
from tracker.application.errors import Invalid, NotFound
from tracker.application.use_cases import calendar, effort
from tracker.domain.model import LeaveType


@pytest.fixture(scope="module")
def deps(tmp_path_factory):
    return demo_deps(tmp_path_factory.mktemp("effort"))


def test_timesheet_week(deps):
    v = effort.timesheet(deps, "2026-10-05", "2026-10-11")
    assert [d.date for d in v.days] == ["2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10"]
    binh = next(r for r in v.rows if r.member_id == "binh")
    assert binh.expected_h == 16 and binh.logged_h >= 18.5  # Mon+Tue expected; meetings top days up to the demo pattern
    chi = {c.date: c for c in next(r for r in v.rows if r.member_id == "chi").cells}
    assert chi["2026-10-09"].state == "off" and chi["2026-10-08"].state == "future"


def test_timesheet_range_is_bounded(deps):
    with pytest.raises(Invalid):
        effort.timesheet(deps, "2026-01-01", "2026-12-31")


def test_day_detail_lists_worklogs_and_planned_issues_with_summaries(deps):
    d = effort.day_detail(deps, "binh", "2026-10-06")
    keys = {e.key: e for e in d.entries}
    assert "PAY-1213" in keys and keys["PAY-1213"].summary.endswith("Fix comment BAL")
    assert d.logged_h == round(sum(e.logged_h for e in d.entries), 2)


def test_effort_analysis_for_the_matrix_anken(deps):
    a = effort.analysis(deps, None)
    assert a.epic_key == "PAY-1100"
    assert [b.label for b in a.by_step] == ["Create", "BAL Review", "Fix comment BAL", "JM Review", "Fix comment JM"]
    assert [r.member_id for r in a.rework] == ["an", "binh", "chi", "dung"]
    assert a.kpis.over_subtasks == 6 and a.kpis.fix_tickets == 10


def test_calendar_month(deps):
    v = calendar.month(deps, "2026-10")
    assert v.days[0].date == "2026-10-01" and any(d.date == "2026-10-10" for d in v.days)
    an = next(r for r in v.rows if r.member_id == "an")
    assert an.days_off == 3  # 16/10 full + 22/10, 23/10 tentative full
    cells = {c.date: c for c in next(r for r in v.rows if r.member_id == "tanaka").cells}
    assert cells["2026-10-12"].kind == "holiday" and cells["2026-10-14"].leave.type == LeaveType.HALF_TENTATIVE


def test_leave_save_edit_delete(tmp_path):
    deps = demo_deps(tmp_path)
    n = calendar.save_leave(deps, calendar.LeaveDraft(None, "an", LeaveType.FULL, "2026-11-02", "2026-11-08", "trip"))
    assert n == 5  # weekends skipped
    lv = next(c.leave for r in calendar.month(deps, "2026-11").rows if r.member_id == "an" for c in r.cells if c.date == "2026-11-03")
    calendar.save_leave(deps, calendar.LeaveDraft(lv.id, "an", LeaveType.HALF, "2026-11-03", "2026-11-03", "half"))
    cells = {c.date: c for r in calendar.month(deps, "2026-11").rows if r.member_id == "an" for c in r.cells}
    assert cells["2026-11-03"].leave.type == LeaveType.HALF
    calendar.delete_leave(deps, cells["2026-11-03"].leave.id)
    with pytest.raises(NotFound):
        calendar.delete_leave(deps, cells["2026-11-03"].leave.id)
    with pytest.raises(Invalid):
        calendar.save_leave(deps, calendar.LeaveDraft(None, "an", LeaveType.FULL, "2026-11-08", "2026-11-02", ""))


def test_import_preview_and_confirm(tmp_path):
    deps = demo_deps(tmp_path)
    rows = [["Bình", "2026-11-10", "2026-11-10", "All day", ""], ["Duc", "2026-11-13", "2026-11-13", "Half day", ""],
            ["Minh", "2026-11-18", "2026-11-20", "All day", ""]]
    p = calendar.import_preview(deps, rows)
    assert [r.error for r in p.rows] == ["", 'Unknown member "Duc"', ""] and p.working_days == 4
    res = calendar.import_confirm(deps, rows)
    assert (res.imported, res.month) == (4, "2026-11")


def test_export_rows(deps):
    e = calendar.export(deps, "2026-10")
    assert e[0] == ("Phạm Quốc Dũng", "2026-10-02", "Half day", "")  # leave by date first, then holidays
    assert ("JP", "2026-10-12", "Holiday", "Sports Day (JP)") in e
