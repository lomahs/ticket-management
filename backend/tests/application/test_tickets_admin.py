import pytest

from tests.application.demo import demo_deps
from tracker.application.errors import CapabilityDisabled, Conflict, Invalid, NotFound
from tracker.application.use_cases import admin, tickets
from tracker.domain.model import Role, StepKind


@pytest.fixture(scope="module")
def deps(tmp_path_factory):
    return demo_deps(tmp_path_factory.mktemp("tickets"))


# --- tickets & Q&A -----------------------------------------------------------------------------------------------

def test_ticket_detail_with_warnings_history_and_worklogs(deps):
    t = tickets.detail(deps, "PAY-1213")
    assert t.card.function_id == "F03" and t.owner_id == "binh"
    assert {w.kind: w.value for w in t.warnings} == {"late": 1, "over": 3.5, "rounds": 3}
    assert [(h.from_id, h.to_id) for h in t.history][-1] == ("minh", "binh")
    assert sum(w.hours for w in t.worklogs) == 7.5
    with pytest.raises(NotFound):
        tickets.detail(deps, "PAY-9")


def test_patch_ticket_writes_and_syncs(tmp_path):
    d = demo_deps(tmp_path)
    tickets.patch(d, "PAY-1213", tickets.TicketPatch(status="R", assignee_id="minh", planned_end="2026-10-08"))
    t = tickets.detail(d, "PAY-1213")
    assert (t.card.status, t.card.assignee_id, t.card.planned_end, t.card.late) == ("R", "minh", "2026-10-08", False)
    with pytest.raises(Invalid):
        tickets.patch(d, "PAY-1213", tickets.TicketPatch(planned_start="2026-10-20"))  # after the planned end


def test_patch_needs_jira_write(tmp_path):
    with pytest.raises(CapabilityDisabled):
        tickets.patch(demo_deps(tmp_path, writable=False), "PAY-1213", tickets.TicketPatch(status="R"))


def test_qa_answers_from_synced_data(deps):
    a = tickets.ask(deps, "Which functions are late?")
    # all tracked anken: PAY-1100's 6 late sub-tasks plus late steps in PAY-1300
    assert a.text.startswith("12 sub-tasks are past their plan, across 12 functions") and len(a.chips) == 12
    assert {"PAY-1213", "PAY-1219", "PAY-1222", "PAY-1226", "PAY-1235", "PAY-1252"} <= set(a.chips)


# --- settings ----------------------------------------------------------------------------------------------------

def test_jira_settings_mask_the_token(deps):
    s = admin.jira_settings(deps)
    assert s.url == "https://acme-pay.atlassian.net" and s.token_masked.startswith("ATATT3xFfGF0") and "demo-token" not in s.token_masked
    assert [e.key for e in s.epics][:2] == ["PAY-1100", "PAY-1300"] and s.epics[0].title == "Payment flow revision"
    assert s.last_full_sync is not None


def test_connection_test_uses_unsaved_values(deps):
    assert admin.test_jira(deps, "https://acme-pay.atlassian.net", "x@acme.vn", None) == "ok"
    assert admin.test_jira(deps, "https://elsewhere.example.com", "x@acme.vn", "short") == "fail"


def test_save_jira_keeps_token_when_blank_and_validates_interval(tmp_path):
    d = demo_deps(tmp_path)
    s = admin.save_jira(d, "https://acme-pay.atlassian.net", "new@acme.vn", None, "60")
    assert (s.email, s.interval, s.status) == ("new@acme.vn", "60", "ok")
    with pytest.raises(Invalid):
        admin.save_jira(d, "https://acme-pay.atlassian.net", "new@acme.vn", None, "7")


def test_add_and_remove_epic(tmp_path):
    d = demo_deps(tmp_path)
    with pytest.raises(Conflict):
        admin.add_epic(d, "pay-1100")
    with pytest.raises(NotFound, match="was not found or is not an Epic"):
        admin.add_epic(d, "PAY-1201")
    with pytest.raises(Invalid):
        admin.add_epic(d, "hello")
    assert "PAY-1500" in [e.key for e in admin.add_epic(d, "pay-1500").epics]
    assert "CRM-400" not in [e.key for e in admin.remove_epic(d, "CRM-400").epics]


def test_field_mapping_check(deps):
    v = admin.field_mapping(deps)
    assert (v.planned_start, v.effort) == ("customfield_10020", "timeoriginalestimate") and v.catalog
    r = {x.target: x for x in admin.check_field_mapping(deps, "PAY-1203", {"planned_start": "customfield_10020",
                                                                           "planned_end": "summary", "effort": ""}).results}
    assert (r["planned_start"].status, r["planned_start"].value) == ("ok", "2026-09-17")
    assert r["planned_end"].status == "type_mismatch" and r["effort"].status == "not_mapped"
    with pytest.raises(NotFound):
        admin.check_field_mapping(deps, "PAY-9", {})


def test_status_mapping_lists_seen_statuses(deps):
    rows = {r.name: r for r in admin.status_mapping(deps)}
    assert rows["Resolved"].mapped == "R" and rows["In Progress"].effective == "W" and rows["Closed"].effective == "C"


def test_name_rule_preview_and_validation(deps):
    p = admin.preview_name_rule(deps, admin.DEFAULT_RULE, None, "[Implement_Coding][F03] Domestic card payment – Fix comment BAL")
    assert p.error is None and p.step == StepKind.FIX_BAL.value and p.values["function_id"] == "F03"
    assert 60 <= p.matched < p.total  # tasks outside the rule (e.g. "Prepare Q&A list for JM") count in the total
    bad = admin.preview_name_rule(deps, "[{phase}] {step}", None, "x")
    assert bad.error == "Unknown placeholder {phase}"


def test_member_update(tmp_path):
    d = demo_deps(tmp_path)
    m = admin.update_member(d, "an", admin.MemberPatch(role=Role.BAL, country="JP", name="Ann"))
    assert (m.role, m.country, m.name) == (Role.BAL, "JP", "Ann")
    with pytest.raises(NotFound):
        admin.update_member(d, "zz", admin.MemberPatch())


def test_holidays_extra_days_hours_alerts(tmp_path):
    d = demo_deps(tmp_path)
    admin.add_holiday(d, "2026-04-30", "Reunification Day", ("VN",))
    assert any(h.date == "2026-04-30" for h in admin.holidays(d))
    admin.delete_holiday(d, "2026-04-30")
    with pytest.raises(Invalid, match="07/10 is already a working day"):
        admin.add_extra_day(d, "2026-10-07", "")
    with pytest.raises(Conflict):
        admin.add_extra_day(d, "2026-10-10", "")
    admin.add_extra_day(d, "2026-10-17", "Cut-over")
    assert admin.extra_days(d)["2026-10-17"] == "Cut-over"
    wh = admin.save_working_hours(d, admin.WorkingHours(7.5, (1, 2, 3, 4, 5, 6), 0.5))
    assert wh.days == (1, 2, 3, 4, 5, 6)
    with pytest.raises(Invalid):
        admin.save_working_hours(d, admin.WorkingHours(0, (1,), 1))
    t = admin.save_alerts(d, admin.Thresholds(soon_days=3))
    assert t.soon_days == 3
