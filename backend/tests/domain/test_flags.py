from tests.domain.factories import TODAY, assigned, issue, status_to
from tracker.domain.flags import (
    build_functions, function_progress, function_state, group_status, issue_flags, progress_pct, worker_of,
)
from tracker.domain.model import Status, StepKind, Thresholds

T = Thresholds()


def flags(i, *, current=True, owner="an"):
    return issue_flags(i, is_current=current, owner_id=owner, today=TODAY, thresholds=T)


def five(fid="F01", statuses="CCWNN", owner="an", **kw):
    kinds = list(StepKind)
    who = [owner, "minh", owner, "tanaka", owner]
    return [issue(f"PAY-{fid}{i}", fid=fid, step=k, status=Status(s), assignee=who[i], **kw)
            for i, (k, s) in enumerate(zip(kinds, statuses))]


# --- grouping and function-level state -------------------------------------------------------

def test_functions_group_by_epic_and_id_in_step_order():
    issues = list(reversed(five("F01"))) + five("F02") + [issue("PAY-9")]
    fns = build_functions(issues)
    assert [f.id for f in fns] == ["F01", "F02"]
    assert [c.step for c in fns[0].cells] == list(StepKind)
    assert fns[0].owner_id == "an"


def test_missing_step_leaves_a_gap_and_counts_as_not_done():
    cells = five("F01", "CCCCC")
    del cells[2]
    f = build_functions(cells)[0]
    assert f.cells[2] is None
    assert f.current == 2


def test_current_step_is_first_not_closed():
    assert build_functions(five("F01", "CCWNN"))[0].current == 2
    assert build_functions(five("F01", "CCCCC"))[0].current == 5


def test_function_state_and_group_status():
    done = build_functions(five("F1", "CCCCC"))[0]
    nys = build_functions(five("F2", "NNNNN"))[0]
    resolved = build_functions(five("F3", "CRNNN"))[0]
    assert function_state(done, set()) == "done" and group_status(done) == "D"
    assert function_state(nys, set()) == "nys" and group_status(nys) == "N"
    assert group_status(resolved) == "D"  # current step resolved counts as done for the summary
    assert function_state(resolved, {resolved.cells[1].key}) == "delay"


def test_progress_pct_weights_statuses():
    f = build_functions(five("F1", "CCRWN"))[0]
    assert progress_pct(f) == round((1 + 1 + 0.8 + 0.4) / 5 * 100)


def test_function_progress_early_when_more_done_than_due():
    f = build_functions(five("F1", "CCNNN", ps="2026-10-08", pe="2026-10-09"))[0]
    assert function_progress(f, TODAY, set()) == "early"
    assert function_progress(f, TODAY, {f.cells[2].key}) == "late"


# --- issue flags -----------------------------------------------------------------------------

def test_late_only_when_active_and_past_planned_end():
    assert flags(issue(pe="2026-10-02")).late
    assert flags(issue(pe="2026-10-02")).late_days == 3  # Mon–Fri days after 02/10 up to 07/10
    assert not flags(issue(pe="2026-10-02", status=Status.RESOLVED)).late
    assert flags(issue(pe="2026-10-02", status=Status.NYS), current=True).late
    assert not flags(issue(pe="2026-10-02", status=Status.NYS), current=False).late


def test_soon_within_threshold_working_days():
    assert flags(issue(pe="2026-10-09")).soon
    assert not flags(issue(pe="2026-10-13")).soon


def test_over_estimate():
    assert flags(issue(est=4, logged=5)).over
    assert not flags(issue(est=4, logged=4)).over


def test_tone_from_status_plan_and_first_worklog():
    assert flags(issue(status=Status.NYS)).tone == "nys"
    assert flags(issue(status=Status.RESOLVED)).tone == "res"
    assert flags(issue(status=Status.CLOSED)).tone == "clo"
    assert flags(issue(pe="2026-10-02")).tone == "delay"
    assert flags(issue(logged=1, log_date="2026-10-06")).tone == "latestart"
    assert flags(issue(logged=1, log_date="2026-10-02")).tone == "early"
    assert flags(issue(logged=1, log_date="2026-10-05")).tone == "ontime"


def test_issue_without_plan_dates_is_never_late():
    f = flags(issue(ps=None, pe=None))
    assert not f.late and f.tone == "ontime"


# --- held and rounds from the changelog --------------------------------------------------------

def test_held_since_resolved():
    i = issue(status=Status.RESOLVED, events=[status_to("2026-09-30", "R")])
    assert flags(i).held_days == 5


def test_held_on_fix_step_while_with_reviewer():
    i = issue(step=StepKind.FIX_JM, fid="F07", assignee="tanaka", events=[assigned("2026-10-01", "dung", "tanaka")])
    assert flags(i, owner="dung").held_days == 4


def test_fix_step_with_owner_is_not_held():
    i = issue(step=StepKind.FIX_BAL, fid="F03", assignee="binh", events=[assigned("2026-10-01", "minh", "binh")])
    assert flags(i, owner="binh").held_days == 0


def test_current_review_step_held_since_assignment_or_creation():
    i = issue(step=StepKind.BAL_REVIEW, fid="F05", assignee="minh", events=[assigned("2026-10-02", "chi", "minh")])
    assert flags(i, owner="chi").held_days == 3
    j = issue(step=StepKind.BAL_REVIEW, fid="F05", assignee="minh", created="2026-10-06")
    assert flags(j, owner="chi").held_days == 1
    assert flags(i, owner="chi", current=False).held_days == 0


def test_rounds_count_returns_to_owner():
    ev = [assigned("2026-09-25", "minh", "binh"), assigned("2026-09-28", "binh", "minh"),
          assigned("2026-09-29", "minh", "binh"), assigned("2026-09-30", "binh", "minh"), assigned("2026-10-01", "minh", "binh")]
    assert flags(issue(step=StepKind.FIX_BAL, fid="F03", assignee="binh", events=ev), owner="binh").rounds == 3


def test_rounds_default_to_one_once_started():
    assert flags(issue(step=StepKind.FIX_BAL, fid="F1", status=Status.WIP), owner="an").rounds == 1
    assert flags(issue(step=StepKind.FIX_BAL, fid="F1", status=Status.NYS), owner="an").rounds == 0
    assert flags(issue(step=StepKind.CREATE, fid="F1"), owner="an").rounds == 0


# --- worker -----------------------------------------------------------------------------------

def test_worker_is_reviewer_on_review_steps_and_owner_otherwise():
    assert worker_of(issue(step=StepKind.BAL_REVIEW, fid="F1", assignee="minh"), "an") == "minh"
    assert worker_of(issue(step=StepKind.FIX_JM, fid="F1", assignee="tanaka"), "an") == "an"
    assert worker_of(issue(assignee="chi"), None) == "chi"
