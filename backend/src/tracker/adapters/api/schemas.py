"""Request bodies. Responses are the application read models (dataclasses) themselves."""

from typing import Literal

from pydantic import BaseModel, Field

from tracker.domain.model import LeaveType, Permission, Role

ISO_DATE = r"^\d{4}-\d{2}-\d{2}$"
Date = Field(pattern=ISO_DATE)


class SyncIn(BaseModel):
    full: bool = False


class StepOverride(BaseModel):
    start: str | None = Field(None, pattern=ISO_DATE)
    end: str | None = Field(None, pattern=ISO_DATE)


class FunctionDraftIn(BaseModel):
    parent_key: str
    name: str = ""
    ba: str
    bal: str
    jm: str
    create_hours: float = Field(gt=0, le=1000)
    overrides: list[StepOverride | None] = Field(default_factory=lambda: [None] * 5, max_length=5)


class FunctionCreateIn(FunctionDraftIn):
    name: str = Field(min_length=1)
    function_id: str | None = Field(None, pattern=r"^[A-Za-z]+\d+$")


class KeysOut(BaseModel):
    keys: list[str]


class PlanChangeIn(BaseModel):
    key: str
    start: str = Date
    member_id: str


class PlanningPreviewIn(BaseModel):
    start: str = Date
    changes: list[PlanChangeIn]


class PlanningPushIn(BaseModel):
    changes: list[PlanChangeIn]


class LeaveIn(BaseModel):
    member_id: str
    type: LeaveType
    date_from: str = Date
    date_to: str = Date
    note: str = ""


class DaysOut(BaseModel):
    days: int


class ImportIn(BaseModel):
    rows: list[list[str]] = Field(max_length=2000)


class TicketPatchIn(BaseModel):
    status: Literal["N", "W", "R", "C"] | None = None
    assignee_id: str | None = None
    planned_start: str | None = Field(None, pattern=ISO_DATE)
    planned_end: str | None = Field(None, pattern=ISO_DATE)


class QuestionIn(BaseModel):
    q: str = Field(min_length=1, max_length=500)


class JiraIn(BaseModel):
    url: str
    email: str
    token: str | None = None  # omitted keeps the stored token
    interval: Literal["5", "15", "60", "manual"]


class JiraTestIn(BaseModel):
    url: str
    email: str = ""
    token: str | None = None


class JiraTestOut(BaseModel):
    status: Literal["ok", "fail"]


class EpicIn(BaseModel):
    key: str


class FieldMappingIn(BaseModel):
    planned_start: str = ""
    planned_end: str = ""
    effort: str = ""


class FieldCheckIn(BaseModel):
    sample: str
    planned_start: str | None = None
    planned_end: str | None = None
    effort: str | None = None


class StatusMappingIn(BaseModel):
    mapping: dict[str, Literal["N", "W", "R", "C"] | None]


class NameRulesIn(BaseModel):
    rule: str
    aliases: dict[str, str]


class NameRulePreviewIn(BaseModel):
    rule: str
    aliases: dict[str, str] | None = None
    sample: str = ""


class MemberPatchIn(BaseModel):
    name: str | None = None
    role: Role | None = None
    clear_role: bool = False
    permission: Permission | None = None
    country: Literal["VN", "JP"] | None = None


class HolidayIn(BaseModel):
    date: str = Date
    name: str = Field(min_length=1)
    countries: list[Literal["VN", "JP"]] = Field(min_length=1)


class ExtraDayIn(BaseModel):
    date: str = Date
    note: str = ""


class WorkingHoursIn(BaseModel):
    hours_per_day: float = Field(gt=0, le=24)
    days: list[int] = Field(min_length=1)
    timesheet_tolerance_h: float = Field(ge=0)


class AlertsIn(BaseModel):
    soon_days: float = Field(ge=0)
    effort_warn_pct: float = Field(ge=0)
    held_days: float = Field(ge=0)
    review_rounds: float = Field(ge=0)
    capacity_pct: float = Field(ge=0)
