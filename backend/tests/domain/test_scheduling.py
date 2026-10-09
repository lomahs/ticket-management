from tests.domain.test_calendar import HOLIDAYS
from tracker.domain.calendar import WorkCalendar
from tracker.domain.model import Leave, LeaveType, Member, Role, StepKind
from tracker.domain.scheduling import suggest_function_plan

M = {i: Member(id=i, name=i.title(), full_name=i, role=r, country=c, color="#000")
     for i, r, c in [("an", Role.BA, "VN"), ("chi", Role.BA, "VN"), ("minh", Role.BAL, "VN"), ("tanaka", Role.JM, "JP")]}
CAL = WorkCalendar(working_days=(1, 2, 3, 4, 5), extra_days={"2026-10-10": "Release"}, holidays=HOLIDAYS, members=M,
                   leaves=[Leave(1, "chi", "2026-10-09", LeaveType.FULL), Leave(2, "minh", "2026-10-09", LeaveType.FULL)])


def plan(ba="an", hours=8.0, overrides=None):
    return suggest_function_plan(first_day="2026-10-08", create_hours=hours, assignees=(ba, "minh", ba, "tanaka", ba),
                                 cal=CAL, overrides=overrides or [None] * 5)


def test_steps_follow_each_other_with_weighted_estimates():
    p = plan()
    assert [s.step for s in p] == list(StepKind)
    assert [s.estimate_h for s in p] == [8, 2, 2.5, 2, 1.5]
    assert [(s.start, s.end) for s in p] == [("2026-10-08", "2026-10-09"), ("2026-10-10", "2026-10-10"),
                                             ("2026-10-12", "2026-10-12"), ("2026-10-13", "2026-10-13"), ("2026-10-14", "2026-10-14")]
    assert all(s.note == "auto" for s in p)


def test_suggestion_skips_leave_and_reports_it():
    first = plan(ba="chi")[0]
    assert (first.start, first.end) == ("2026-10-08", "2026-10-10")
    assert first.note == "skips" and first.reasons == [("2026-10-09", "Chi · All day")]


def test_long_create_spans_more_days():
    assert plan(hours=13)[0].end == "2026-10-10"  # 13h → 3 working days: 08, 09, 10 (extra day)


def test_manual_override_landing_on_a_day_off_is_flagged_not_blocked():
    p = plan(overrides=[{"start": "2026-10-11"}, None, None, None, None])
    assert p[0].start == "2026-10-11" and p[0].note == "dayoff" and ("2026-10-11", "weekend") in p[0].reasons
    assert p[1].start > p[0].end


def test_override_end_before_start_is_clamped():
    p = plan(overrides=[{"start": "2026-10-13", "end": "2026-10-12"}, None, None, None, None])
    assert (p[0].start, p[0].end) == ("2026-10-13", "2026-10-13") and p[0].note == "edited"
