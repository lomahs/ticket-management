from tracker.domain.calendar import WorkCalendar
from tracker.domain.model import Holiday, Leave, LeaveType, Member, Role

MEMBERS = {
    "binh": Member(id="binh", name="Bình", full_name="Trần Văn Bình", role=Role.BA, country="VN", color="#000"),
    "tanaka": Member(id="tanaka", name="Tanaka", full_name="Tanaka Hiroshi", role=Role.JM, country="JP", color="#000"),
}
HOLIDAYS = {
    "2026-09-21": Holiday("2026-09-21", ("JP",), "Respect for the Aged Day (JP)"),
    "2026-09-22": Holiday("2026-09-22", ("JP",), "Citizens' Holiday (JP)"),
    "2026-09-23": Holiday("2026-09-23", ("JP",), "Autumnal Equinox Day (JP)"),
    "2027-01-01": Holiday("2027-01-01", ("VN", "JP"), "New Year's Day"),
}


def cal(leaves=(), extra=None, days=(1, 2, 3, 4, 5)):
    return WorkCalendar(working_days=days, extra_days=extra or {}, holidays=HOLIDAYS, members=MEMBERS, leaves=leaves)


def test_weekend_is_off_for_everyone():
    assert cal().off("2026-10-10", "binh") == "we"  # Saturday


def test_country_holiday_applies_only_to_members_of_that_country():
    c = cal()
    assert c.off("2026-09-21", "tanaka") == "Respect for the Aged Day (JP)"
    assert c.off("2026-09-21", "binh") is None
    assert c.off("2027-01-01", "binh") == "New Year's Day"


def test_full_leave_blocks_the_day_and_half_leave_halves_capacity():
    c = cal(leaves=[Leave(1, "binh", "2026-10-09", LeaveType.FULL, ""), Leave(2, "binh", "2026-10-20", LeaveType.HALF, "")])
    assert c.off("2026-10-09", "binh") == "Bình · All day"
    assert c.off("2026-10-20", "binh") is None
    assert c.capacity_factor("binh", "2026-10-20") == 0.5
    assert c.capacity_factor("binh", "2026-10-09") == 0
    assert c.capacity_factor("binh", "2026-10-21") == 1


def test_tentative_full_leave_still_blocks():
    c = cal(leaves=[Leave(1, "binh", "2026-10-22", LeaveType.FULL_TENTATIVE, "")])
    assert c.off("2026-10-22", "binh") == "Bình · All day (tentative)"


def test_extra_working_day_opens_a_weekend():
    c = cal(extra={"2026-10-10": "Release support"})
    assert c.visible("2026-10-10")
    assert c.off("2026-10-10", "binh") is None
    assert not c.visible("2026-10-11")


def test_span_skips_holidays_and_records_them():
    skipped: list[tuple[str, str]] = []
    assert cal().span("2026-09-18", 2, "tanaka", skipped) == ("2026-09-18", "2026-09-24")
    assert skipped == [("2026-09-21", "Respect for the Aged Day (JP)"), ("2026-09-22", "Citizens' Holiday (JP)"),
                       ("2026-09-23", "Autumnal Equinox Day (JP)")]


def test_span_starts_on_next_working_day():
    assert cal().span("2026-10-10", 1, "binh") == ("2026-10-12", "2026-10-12")
