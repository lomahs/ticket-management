from tests.domain.test_scheduling import CAL
from tracker.domain.capacity import PlanItem, daily_plan, weekly_capacity
from tracker.domain.model import Worklog
from tracker.domain.timesheet import day_detail, timesheet

ITEMS = [
    PlanItem("PAY-1", "an", "2026-10-05", "2026-10-09", 10),   # 5 working days → 2h/day
    PlanItem("PAY-2", "chi", "2026-10-08", "2026-10-09", 4),   # 09 is Chi's leave → all 4h on 08
    PlanItem("PAY-3", "chi", "2026-10-09", "2026-10-09", 3),   # only day is off → lands on its start day
]


def test_daily_plan_spreads_estimate_over_working_days():
    p = daily_plan(ITEMS, CAL)
    assert p["an"]["2026-10-07"].total == 2
    assert p["chi"]["2026-10-08"].by_key == {"PAY-2": 4}
    assert p["chi"]["2026-10-09"].by_key == {"PAY-3": 3}


def test_weekly_capacity_levels():
    weeks = weekly_capacity(ITEMS + [PlanItem("PAY-4", "an", "2026-10-12", "2026-10-16", 38)], CAL, ["an", "chi"],
                            start="2026-10-05", hours_per_day=8, cap_pct=100)
    an = weeks["an"]
    assert (an[0].planned_h, an[0].available_h) == (10, 48)  # Mon–Fri + extra Saturday 10/10
    assert an[0].pct == 21 and an[0].level == "idle"
    assert an[1].pct == 95 and an[1].level == "near"
    assert weeks["chi"][0].available_h == 40  # leave on Friday
    assert len(an) == 5


def test_timesheet_states_totals_and_tolerance():
    logs = {("an", "2026-10-05"): [Worklog("2026-10-05", "an", 4, "spec", )],
            ("an", "2026-10-06"): [Worklog("2026-10-06", "an", 2.9, "spec")],
            ("an", "2026-10-07"): [Worklog("2026-10-07", "an", 1, "x")]}
    plan = daily_plan(ITEMS, CAL)
    days = ["2026-10-05", "2026-10-06", "2026-10-07", "2026-10-08", "2026-10-10", "2026-10-09"]
    rows = timesheet(["an", "chi"], days, logs, plan, CAL, today="2026-10-07", hours_per_day=8, tolerance_h=1)
    an = {c.date: c for c in rows[0].cells}
    assert an["2026-10-05"].state == "over"          # 4h logged vs 2h plan, beyond 1h tolerance
    assert an["2026-10-06"].state == "ok"            # 2.9h vs 2h is within tolerance
    assert an["2026-10-08"].state == "future"
    assert rows[0].logged_h == 7.9 and rows[0].expected_h == 16  # today and future are not expected yet
    assert rows[0].over_days == 1
    chi = {c.date: c for c in rows[1].cells}
    assert chi["2026-10-09"].state == "off" and chi["2026-10-09"].off_reason == "Chi · All day"


def test_day_detail_groups_by_issue_and_adds_planned_issues():
    logs = [Worklog("2026-10-07", "an", 1, "spec"), Worklog("2026-10-07", "an", 0.5, "fix")]
    d = day_detail(logs_by_key={"PAY-1": logs}, plan=daily_plan(ITEMS, CAL), member_id="an", date="2026-10-07")
    assert d.logged_h == 1.5 and d.planned_h == 2
    assert [(e.key, e.logged_h, e.planned_h, e.notes) for e in d.entries] == [("PAY-1", 1.5, 2, ["spec", "fix"])]
