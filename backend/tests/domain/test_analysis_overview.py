from tests.domain.factories import TODAY, assigned, issue, status_to
from tracker.domain.analysis import estimate_vs_logged, rework_by_member
from tracker.domain.flags import build_functions, issue_flags
from tracker.domain.model import Member, Role, Status, StepKind, Thresholds
from tracker.domain.overview import attention_groups, forecast_date, phase_rollup

T = Thresholds()


def test_estimate_vs_logged_counts_started_issues_only():
    bars = estimate_vs_logged([("Create", [issue(est=8, logged=10), issue(est=5, status=Status.NYS)])])
    assert [(b.label, b.estimate_h, b.logged_h) for b in bars] == [("Create", 8, 10)]


def test_rework_rate_rounds_by_owner():
    cells = [issue("A1", fid="F1", step=StepKind.CREATE, logged=10, assignee="an"),
             issue("A3", fid="F1", step=StepKind.FIX_BAL, logged=3, assignee="an",
                   events=[assigned("2026-10-01", "minh", "an"), assigned("2026-10-02", "an", "minh"), assigned("2026-10-05", "minh", "an")])]
    fns = build_functions(cells)
    rounds = {c.key: issue_flags(c, is_current=True, owner_id="an", today=TODAY, thresholds=T).rounds for c in cells}
    [r] = rework_by_member(fns, ["an"], rounds)
    assert (r.member_id, r.functions, r.create_h, r.fix_h, r.max_rounds) == ("an", 1, 10, 3, 2)
    assert r.rate == 0.3 and r.avg_rounds == 2


def test_phase_rollup_weights_statuses():
    p = phase_rollup("Prepare", [issue(status=Status.CLOSED, ps="2026-09-01", pe="2026-09-05"),
                                 issue(status=Status.WIP, ps="2026-09-03", pe="2026-09-11")])
    assert (p.pct, p.closed, p.total, p.start, p.end) == (70, 1, 2, "2026-09-01", "2026-09-11")


def test_forecast_slips_by_worst_delay_in_working_days():
    assert forecast_date("2026-11-20", 3) == "2026-11-25"
    assert forecast_date("2026-11-20", 0) == "2026-11-20"


def test_attention_groups_use_thresholds_and_roles():
    members = {"tanaka": Member("tanaka", "Tanaka", "", Role.JM, "JP", "#000"), "an": Member("an", "An", "", Role.BA, "VN", "#000")}
    late = issue("L", pe="2026-10-01")
    stuck = issue("R", status=Status.RESOLVED, events=[status_to("2026-09-28", "R")])
    jm = issue("J", assignee="tanaka", step=StepKind.JM_REVIEW, fid="F1", events=[assigned("2026-09-29", "an", "tanaka")])
    issues = [late, stuck, jm]
    flags = {i.key: issue_flags(i, is_current=True, owner_id="an", today=TODAY, thresholds=T) for i in issues}
    g = {x.kind: [i.key for i in x.issues] for x in attention_groups(issues, flags, members, T)}
    assert g == {"overdue": ["L"], "stuck_resolved": ["R"], "many_rounds": [], "waiting_client": ["J"]}
