"""Jira data as the JiraReader port delivers it: normalised, but still in Jira's vocabulary (accounts, status names)."""

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True)
class UserSnapshot:
    account_id: str
    display_name: str
    email: str = ""


@dataclass(frozen=True)
class WorklogSnapshot:
    author: UserSnapshot
    date: str  # YYYY-MM-DD the work was done
    hours: float
    comment: str = ""


@dataclass(frozen=True)
class ChangeSnapshot:
    """One changelog item. Assignee changes carry account ids; status changes carry status names."""

    at: str  # YYYY-MM-DD
    field: str  # "assignee" | "status"
    from_value: str | None
    to_value: str | None
    from_user: UserSnapshot | None = None
    to_user: UserSnapshot | None = None


@dataclass(frozen=True)
class IssueSnapshot:
    key: str
    project: str
    summary: str
    status_name: str
    status_category: str  # new | indeterminate | done
    assignee: UserSnapshot | None
    parent_key: str | None
    created: str  # YYYY-MM-DD
    updated: str  # ISO datetime
    fields: dict[str, Any] = field(default_factory=dict)  # raw values of mapped fields, by Jira field id
    worklogs: tuple[WorklogSnapshot, ...] = ()
    changes: tuple[ChangeSnapshot, ...] = ()


@dataclass(frozen=True)
class EpicSnapshot:
    key: str
    project: str
    summary: str
    due_date: str | None


@dataclass(frozen=True)
class JiraField:
    id: str
    name: str
    type: str  # Jira schema type: date, datetime, number, string, … ("" when Jira gives none)


@dataclass(frozen=True)
class NewSubtask:
    summary: str
    assignee_account_id: str | None
    planned_start: str
    planned_end: str
    estimate_h: float
