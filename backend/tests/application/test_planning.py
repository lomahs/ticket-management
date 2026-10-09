import pytest

from tests.application.demo import demo_deps
from tracker.application.errors import CapabilityDisabled, Invalid
from tracker.application.use_cases import planning
from tracker.application.use_cases.planning import PlanChange


@pytest.fixture(scope="module")
def deps(tmp_path_factory):
    return demo_deps(tmp_path_factory.mktemp("planning"))


def test_planning_window_days_and_member_day_cells(deps):
    v = planning.view(deps, "2026-10-07", 2)
    assert (v.start, v.end) == ("2026-10-05", "2026-10-18")
    assert [d.date for d in v.days][:6] == ["2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-09", "2026-10-10"]
    assert len(v.days) == 11 and v.days[5].extra_note and v.days[2].is_today
    rows = {r.member_id: r for r in v.rows}
    tanaka = {c.date: c for c in rows["tanaka"].days}
    assert tanaka["2026-10-12"].kind == "holiday" and tanaka["2026-10-14"].kind == "leave_half" and tanaka["2026-10-14"].tentative
    assert {c.date: c.kind for c in rows["chi"].days}["2026-10-09"] == "leave"


def test_planning_items_overlap_the_window_with_step_colour_hints(deps):
    v = planning.view(deps, "2026-10-05", 2)
    binh = {b.card.key: b for b in next(r for r in v.rows if r.member_id == "binh").items}
    assert binh["PAY-1213"].color_step == "fixb"
    assert binh["CRM-430"].color_step == "create"  # "Campaign export – Create" is not a function step but looks like one
    assert "PAY-1201" not in binh  # September, outside the window
    assert {p for p in v.projects} == {"PAY", "CRM", "OPS"}


def test_capacity_is_five_weeks_per_member(deps):
    v = planning.view(deps, "2026-10-05", 1)
    cap = {c.member_id: c.weeks for c in v.capacity}
    assert len(cap["an"]) == 5 and cap["an"][0].week_start == "2026-10-05"
    assert cap["chi"][0].available_h == 40  # Friday 09/10 on leave; Saturday 10/10 is an extra working day


def test_preview_moves_items_and_flags_days_off(deps):
    p = planning.preview(deps, "2026-10-05", [PlanChange("PAY-1262", "2026-10-11", "binh")])
    [c] = p.changes
    assert (c.from_start, c.from_end, c.from_member, c.to_start, c.to_end, c.to_member) == (
        "2026-10-09", "2026-10-09", "an", "2026-10-11", "2026-10-11", "binh")
    assert c.day_off == [("2026-10-11", "weekend")]
    base = {x.member_id: x.weeks[0].planned_h for x in planning.view(deps, "2026-10-05", 1).capacity}
    moved = {x.member_id: x.weeks[0].planned_h for x in p.capacity}
    assert moved["an"] == base["an"] - 6 and moved["binh"] == base["binh"] + 6


def test_preview_rejects_unknown_issue(deps):
    with pytest.raises(Invalid):
        planning.preview(deps, "2026-10-05", [PlanChange("PAY-9", "2026-10-11", "binh")])


def test_push_writes_dates_and_assignee_then_syncs(tmp_path):
    deps = demo_deps(tmp_path)
    planning.push(deps, [PlanChange("PAY-1262", "2026-10-12", "binh")])
    v = planning.view(deps, "2026-10-12", 1)
    item = next(b for r in v.rows if r.member_id == "binh" for b in r.items if b.card.key == "PAY-1262")
    assert (item.card.planned_start, item.card.planned_end, item.card.assignee_id) == ("2026-10-12", "2026-10-12", "binh")


def test_push_needs_jira_write(tmp_path):
    with pytest.raises(CapabilityDisabled):
        planning.push(demo_deps(tmp_path, writable=False), [PlanChange("PAY-1262", "2026-10-12", "binh")])
