from fastapi import APIRouter

from tracker.application.use_cases import admin
from tracker.application.use_cases.admin import (
    FieldMappingCheck, FieldMappingView, JiraSettingsView, MemberPatch, NameRulePreview, NameRulesView, StatusRow,
)
from tracker.domain.model import Holiday, Member, Thresholds, WorkingHours

from ..deps import D
from ..schemas import (
    AlertsIn, EpicIn, ExtraDayIn, FieldCheckIn, FieldMappingIn, HolidayIn, JiraIn, JiraTestIn, JiraTestOut, MemberPatchIn,
    NameRulePreviewIn, NameRulesIn, StatusMappingIn, WorkingHoursIn,
)

router = APIRouter(tags=["settings"])


@router.get("/settings/jira")
def jira(d: D) -> JiraSettingsView:
    return admin.jira_settings(d)


@router.put("/settings/jira")
def save_jira(d: D, body: JiraIn) -> JiraSettingsView:
    return admin.save_jira(d, body.url, body.email, body.token, body.interval)


@router.post("/settings/jira/test")
def test_jira(d: D, body: JiraTestIn) -> JiraTestOut:
    return JiraTestOut(status=admin.test_jira(d, body.url, body.email, body.token))


@router.post("/settings/epics")
def add_epic(d: D, body: EpicIn) -> JiraSettingsView:
    return admin.add_epic(d, body.key)


@router.delete("/settings/epics/{key}")
def remove_epic(d: D, key: str) -> JiraSettingsView:
    return admin.remove_epic(d, key)


@router.get("/settings/field-mapping")
def field_mapping(d: D) -> FieldMappingView:
    return admin.field_mapping(d)


@router.put("/settings/field-mapping")
def save_field_mapping(d: D, body: FieldMappingIn) -> FieldMappingView:
    return admin.save_field_mapping(d, body.model_dump())


@router.post("/settings/field-mapping/check")
def check_field_mapping(d: D, body: FieldCheckIn) -> FieldMappingCheck:
    return admin.check_field_mapping(d, body.sample, body.model_dump(exclude={"sample"}, exclude_none=True))


@router.get("/settings/status-mapping")
def status_mapping(d: D) -> list[StatusRow]:
    return admin.status_mapping(d)


@router.put("/settings/status-mapping")
def save_status_mapping(d: D, body: StatusMappingIn) -> list[StatusRow]:
    return admin.save_status_mapping(d, body.mapping)


@router.get("/settings/name-rules")
def name_rules(d: D) -> NameRulesView:
    return admin.name_rules(d)


@router.put("/settings/name-rules")
def save_name_rules(d: D, body: NameRulesIn) -> NameRulesView:
    return admin.save_name_rules(d, body.rule, body.aliases)


@router.post("/settings/name-rules/preview")
def preview_name_rule(d: D, body: NameRulePreviewIn) -> NameRulePreview:
    return admin.preview_name_rule(d, body.rule, body.aliases, body.sample)


@router.patch("/members/{member_id}")
def update_member(d: D, member_id: str, body: MemberPatchIn) -> Member:
    return admin.update_member(d, member_id, MemberPatch(**body.model_dump()))


@router.get("/holidays")
def holidays(d: D) -> list[Holiday]:
    return admin.holidays(d)


@router.post("/holidays", status_code=201)
def add_holiday(d: D, body: HolidayIn) -> list[Holiday]:
    return admin.add_holiday(d, body.date, body.name, body.countries)


@router.delete("/holidays/{date}")
def delete_holiday(d: D, date: str) -> list[Holiday]:
    return admin.delete_holiday(d, date)


@router.get("/extra-days")
def extra_days(d: D) -> dict[str, str]:
    return admin.extra_days(d)


@router.post("/extra-days", status_code=201)
def add_extra_day(d: D, body: ExtraDayIn) -> dict[str, str]:
    return admin.add_extra_day(d, body.date, body.note)


@router.delete("/extra-days/{date}")
def delete_extra_day(d: D, date: str) -> dict[str, str]:
    return admin.delete_extra_day(d, date)


@router.get("/settings/working-hours")
def working_hours(d: D) -> WorkingHours:
    return admin.working_hours(d)


@router.put("/settings/working-hours")
def save_working_hours(d: D, body: WorkingHoursIn) -> WorkingHours:
    return admin.save_working_hours(d, WorkingHours(body.hours_per_day, tuple(body.days), body.timesheet_tolerance_h))


@router.get("/settings/alerts")
def alerts(d: D) -> Thresholds:
    return admin.alerts(d)


@router.put("/settings/alerts")
def save_alerts(d: D, body: AlertsIn) -> Thresholds:
    return admin.save_alerts(d, Thresholds(**body.model_dump()))
