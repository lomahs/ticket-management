import pytest

from tests.application.demo import demo_deps
from tracker.application.errors import CapabilityDisabled, Conflict, Invalid, NotFound
from tracker.application.use_cases import anken


@pytest.fixture(scope="module")
def deps(tmp_path_factory):
    return demo_deps(tmp_path_factory.mktemp("anken"))


def test_overview_cards(deps):
    cards = {c.key: c for c in anken.overview(deps)}
    assert list(cards) == ["PAY-1100", "PAY-1300", "PAY-1420", "PAY-0980", "CRM-400", "OPS-200"]  # tracked order
    p = cards["PAY-1100"]
    assert (p.plan, p.forecast, p.slip_days, p.late_functions, p.has_functions) == ("2026-11-20", "2026-11-25", 3, 6, True)
    assert [ph.name for ph in p.phases] == ["Prepare", "Implement_Coding", "Function Unit Test"]
    assert p.phases[1].pct == 50 and (p.phases[1].closed, p.phases[1].total) == (25, 60)
    assert set(p.member_ids) == {"an", "binh", "chi", "dung", "minh", "tanaka"}
    assert not cards["CRM-400"].has_functions


def test_anken_detail_attention_groups(deps):
    d = anken.detail(deps, "PAY-1100")
    g = {a.kind: [c.key for c in a.items] for a in d.attention}
    assert set(g["overdue"][:3]) == {"PAY-1186", "PAY-1219", "PAY-1235"}  # the three 3-day delays, ties by key
    assert g["stuck_resolved"] == ["PAY-1248"]
    assert g["many_rounds"] == ["PAY-1213", "PAY-1235"]
    assert g["waiting_client"] == ["PAY-1219", "PAY-1235"]
    with pytest.raises(NotFound):
        anken.detail(deps, "PAY-404")


def test_matrix_rows_stats_and_parents(deps):
    m = anken.matrix(deps, "PAY-1100")
    assert [f.id for f in m.functions] == [f"F{i:02d}" for i in range(1, 13)]
    assert (m.stats.progress_pct, m.stats.functions_done, m.stats.late_functions, m.stats.late_subtasks,
            m.stats.over_subtasks, m.stats.logged_h, m.stats.estimate_h) == (50, 1, 6, 6, 6, 148, 207)
    f03 = m.functions[2]
    assert (f03.owner_id, f03.reviewer_id, f03.current, f03.state, f03.progress, f03.group, f03.pct) == (
        "binh", "minh", 2, "delay", "late", "W", 48)
    assert f03.cells[2].rounds == 3 and f03.cells[2].held_days == 2 and f03.cells[2].late
    assert m.phase == "Implement_Coding" and m.next_function_id == "F13"
    assert [p.key for p in m.parents] == ["PAY-1101", "PAY-1102", "PAY-1103"]


def test_function_preview_suggests_dates_and_summaries(deps):
    p = anken.preview_function(deps, "PAY-1100", anken.FunctionDraft("PAY-1102", "Installment", "an", "minh", "tanaka", 8, [None] * 5))
    assert (p.phase, p.function_id) == ("Implement_Coding", "F13")
    assert [(s.start, s.end) for s in p.steps] == [("2026-10-08", "2026-10-09"), ("2026-10-10", "2026-10-10"),
                                                   ("2026-10-12", "2026-10-12"), ("2026-10-13", "2026-10-13"), ("2026-10-14", "2026-10-14")]
    assert p.steps[0].summary == "[Implement_Coding][F13] Installment – Create"
    with pytest.raises(Invalid):
        anken.preview_function(deps, "PAY-1100", anken.FunctionDraft("PAY-1102", "X", "an", "minh", "tanaka", 0, [None] * 5))
    with pytest.raises(Invalid):
        anken.preview_function(deps, "PAY-1100", anken.FunctionDraft("PAY-1300", "X", "an", "minh", "tanaka", 8, [None] * 5))


def test_create_function_writes_to_jira_and_syncs_back(tmp_path):
    deps = demo_deps(tmp_path)
    draft = anken.FunctionDraft("PAY-1102", "Installment payment", "an", "minh", "tanaka", 8, [None] * 5)
    keys = anken.create_function(deps, "PAY-1100", draft)
    assert keys == ["PAY-1501", "PAY-1502", "PAY-1503", "PAY-1504", "PAY-1505"]
    m = anken.matrix(deps, "PAY-1100")
    assert m.functions[-1].id == "F13" and [c.key for c in m.functions[-1].cells] == keys
    assert m.next_function_id == "F14"
    with pytest.raises(Conflict):
        anken.create_function(deps, "PAY-1100", draft, function_id="F13")


def test_create_function_needs_jira_write(tmp_path):
    deps = demo_deps(tmp_path, writable=False)
    with pytest.raises(CapabilityDisabled):
        anken.create_function(deps, "PAY-1100", anken.FunctionDraft("PAY-1102", "X", "an", "minh", "tanaka", 8, [None] * 5))
