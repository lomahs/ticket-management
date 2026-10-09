"""Domain entities and value objects. Plain dataclasses; no framework imports."""

from dataclasses import dataclass, field
from enum import StrEnum


class Status(StrEnum):
    NYS = "N"
    WIP = "W"
    RESOLVED = "R"
    CLOSED = "C"


class Role(StrEnum):
    BA = "BA"
    BAL = "BAL"
    JM = "JM"


class Permission(StrEnum):
    ADMIN = "admin"
    USER = "user"


class StepKind(StrEnum):
    CREATE = "create"
    BAL_REVIEW = "balr"
    FIX_BAL = "fixb"
    JM_REVIEW = "jmr"
    FIX_JM = "fixj"


class LeaveType(StrEnum):
    FULL = "full"
    HALF = "half"
    FULL_TENTATIVE = "fullTent"
    HALF_TENTATIVE = "halfTent"


@dataclass(frozen=True)
class LeaveTypeInfo:
    label: str
    full: bool
    tentative: bool


LEAVE_TYPES: dict[LeaveType, LeaveTypeInfo] = {
    LeaveType.FULL: LeaveTypeInfo("All day", True, False),
    LeaveType.HALF: LeaveTypeInfo("Half day", False, False),
    LeaveType.FULL_TENTATIVE: LeaveTypeInfo("All day (tentative)", True, True),
    LeaveType.HALF_TENTATIVE: LeaveTypeInfo("Half day (tentative)", False, True),
}


class EventField(StrEnum):
    ASSIGNEE = "assignee"
    STATUS = "status"


class IssueKind(StrEnum):
    PARENT = "parent"
    SUBTASK = "subtask"


@dataclass
class Member:
    id: str
    name: str
    full_name: str
    role: Role | None
    country: str
    color: str
    email: str = ""
    permission: Permission = Permission.USER
    external: bool = False
    jira_account_id: str | None = None


@dataclass(frozen=True)
class Worklog:
    date: str
    member_id: str
    hours: float
    note: str = ""


@dataclass(frozen=True)
class IssueEvent:
    """One changelog entry. For STATUS events the values are tracker statuses (N/W/R/C)."""

    at: str
    field: EventField
    from_value: str | None
    to_value: str | None


@dataclass
class Issue:
    key: str
    project: str
    epic_key: str
    parent_key: str | None
    kind: IssueKind
    summary: str
    phase: str
    status: Status
    assignee_id: str | None
    planned_start: str | None
    planned_end: str | None
    estimate_h: float
    created: str
    jira_status: str = ""
    function_id: str | None = None
    function_name: str | None = None
    step: StepKind | None = None
    worklogs: list[Worklog] = field(default_factory=list)
    events: list[IssueEvent] = field(default_factory=list)

    @property
    def logged_h(self) -> float:
        return sum(w.hours for w in self.worklogs)

    @property
    def is_function_step(self) -> bool:
        return self.function_id is not None and self.step is not None


@dataclass
class Leave:
    id: int
    member_id: str
    date: str
    type: LeaveType
    note: str = ""


@dataclass(frozen=True)
class Holiday:
    date: str
    countries: tuple[str, ...]
    name: str


@dataclass
class Anken:
    """A tracked Jira epic."""

    key: str
    project: str
    title: str
    due_date: str | None = None


@dataclass
class Thresholds:
    soon_days: float = 2
    effort_warn_pct: float = 80
    held_days: float = 3
    review_rounds: float = 3
    capacity_pct: float = 100


@dataclass
class WorkingHours:
    hours_per_day: float = 8
    days: tuple[int, ...] = (1, 2, 3, 4, 5)
    timesheet_tolerance_h: float = 1
