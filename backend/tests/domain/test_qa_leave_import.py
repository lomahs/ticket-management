from tests.domain.factories import TODAY, issue
from tracker.domain.flags import build_functions, issue_flags
from tracker.domain.leave_import import validate_rows
from tracker.domain.model import Leave, LeaveType, Member, Role, StepKind, Thresholds, Worklog
from tracker.domain.qa import QaContext, answer

MEMBERS = {i: Member(i, n, n, r, c, "#000") for i, n, r, c in
           [("an", "An", Role.BA, "VN"), ("binh", "Bình", Role.BA, "VN"), ("minh", "Minh", Role.BAL, "VN"), ("tanaka", "Tanaka", Role.JM, "JP")]}


def ctx():
    cells = [issue("PAY-1", fid="F01", step=StepKind.CREATE, pe="2026-10-02", assignee="an"),
             issue("PAY-2", fid="F02", step=StepKind.BAL_REVIEW, assignee="minh", created="2026-10-01"),
             issue("PAY-3", fid="F03", step=StepKind.CREATE, assignee="binh", logged=3, log_date="2026-10-06", pe="2026-10-09")]
    fns = build_functions(cells)
    flags = {c.key: issue_flags(c, is_current=True, owner_id=c.assignee_id, today=TODAY, thresholds=Thresholds()) for c in cells}
    logs = [Worklog("2026-10-05", "binh", 8), Worklog("2026-10-07", "binh", 3), Worklog("2026-09-30", "binh", 8)]
    return QaContext(functions=fns, flags=flags, members=MEMBERS, worklogs=logs, today=TODAY, hours_per_day=8, working_days=(1, 2, 3, 4, 5))


def test_late_question_lists_late_subtasks():
    a = answer(ctx(), "Which functions are late?")
    assert a.text == "1 sub-tasks are past their plan, across 1 functions. Longest delay: F01 (3 working days)."
    assert a.chips == ["PAY-1"]


def test_bal_question_uses_the_member_with_bal_role():
    a = answer(ctx(), "Which review tickets is Minh holding?")
    assert a.chips == ["PAY-2"] and a.text.startswith("Minh is holding 1 tickets")


def test_hours_question_sums_this_weeks_worklogs_for_the_named_member():
    a = answer(ctx(), "How many hours has Bình logged this week?")
    assert a.text.startswith("Bình has logged 11h / 40h this week (05/10–09/10), 3h today.")
    assert a.chips == ["PAY-3"]


def test_unknown_question():
    assert "did not understand" in answer(ctx(), "weather?").text


def test_leave_import_validation_messages():
    existing = [Leave(1, "an", "2026-10-16", LeaveType.FULL)]
    rows = [["An", "2026-11-05", "2026-11-06", "All day", "Trip"], ["Duc", "2026-11-13", "2026-11-13", "Half day", ""],
            ["Chi?", "x", "", "", ""], ["Bình", "2026/11/25", "2026/11/25", "All day", ""], ["Bình", "2026-11-12", "2026-11-11", "All day", ""],
            ["Bình", "2026-11-26", "2026-11-27", "Remote", ""], ["an", "2026-10-16", "", "half day (tentative)", ""]]
    r = validate_rows(rows, MEMBERS, existing)
    assert [x.error for x in r] == ["", 'Unknown member "Duc"', 'Unknown member "Chi?"', "Invalid date format, use YYYY-MM-DD",
                                    "To date is before From date", 'Unknown leave type "Remote"', "Overlaps existing leave on 16/10"]
    assert (r[0].row, r[0].member_id, r[0].type, r[0].date_to) == (2, "an", LeaveType.FULL, "2026-11-06")
    assert r[6].type == LeaveType.HALF_TENTATIVE and r[6].date_to == "2026-10-16"
