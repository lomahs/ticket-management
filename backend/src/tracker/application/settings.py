"""Tracker settings: what the admin configures, stored as JSON documents by key."""

from dataclasses import asdict, dataclass, field
from typing import Any

from tracker.domain.model import StepKind, Thresholds, WorkingHours
from tracker.domain.naming import DEFAULT_ALIASES, DEFAULT_RULE


@dataclass
class JiraConnection:
    url: str = ""
    email: str = ""
    token_encrypted: str = ""
    interval: str = "15"  # minutes, or "manual"
    epics: list[str] = field(default_factory=list)
    status: str | None = None  # last connection test: ok | fail | None


@dataclass
class FieldMapping:
    planned_start: str = "customfield_10020"
    planned_end: str = "customfield_10016"
    effort: str = "timeoriginalestimate"


@dataclass
class NameRules:
    rule: str = DEFAULT_RULE
    aliases: dict[StepKind, str] = field(default_factory=lambda: dict(DEFAULT_ALIASES))


@dataclass
class StatusMapping:
    by_name: dict[str, str] = field(default_factory=dict)  # Jira status name → N/W/R/C
    seen: dict[str, str] = field(default_factory=dict)  # Jira status name → category, recorded by sync


@dataclass
class TrackerSettings:
    jira: JiraConnection = field(default_factory=JiraConnection)
    field_mapping: FieldMapping = field(default_factory=FieldMapping)
    name_rules: NameRules = field(default_factory=NameRules)
    status_mapping: StatusMapping = field(default_factory=StatusMapping)
    thresholds: Thresholds = field(default_factory=Thresholds)
    working_hours: WorkingHours = field(default_factory=WorkingHours)
    default_country: str = "VN"


# --- (de)serialisation to the JSON documents the SettingsRepository stores ------------------------------------

SECTIONS = ("jira", "field_mapping", "name_rules", "status_mapping", "thresholds", "working_hours", "default_country")


def to_documents(s: TrackerSettings) -> dict[str, Any]:
    d = {k: asdict(v) if hasattr(v, "__dataclass_fields__") else v for k, v in vars(s).items()}
    d["name_rules"]["aliases"] = {k.value: v for k, v in s.name_rules.aliases.items()}
    d["working_hours"]["days"] = list(s.working_hours.days)
    return d


def from_documents(docs: dict[str, Any]) -> TrackerSettings:
    s = TrackerSettings()
    if "jira" in docs:
        s.jira = JiraConnection(**docs["jira"])
    if "field_mapping" in docs:
        s.field_mapping = FieldMapping(**docs["field_mapping"])
    if "name_rules" in docs:
        nr = docs["name_rules"]
        s.name_rules = NameRules(nr["rule"], {StepKind(k): v for k, v in nr["aliases"].items()})
    if "status_mapping" in docs:
        s.status_mapping = StatusMapping(**docs["status_mapping"])
    if "thresholds" in docs:
        s.thresholds = Thresholds(**docs["thresholds"])
    if "working_hours" in docs:
        wh = dict(docs["working_hours"])
        wh["days"] = tuple(wh["days"])
        s.working_hours = WorkingHours(**wh)
    if "default_country" in docs:
        s.default_country = docs["default_country"]
    return s
