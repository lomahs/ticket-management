"""Settings: Jira connection and tracked epics, field/status mapping, name rules, members, calendar, hours, alerts."""

import re
from collections.abc import Mapping, Sequence
from dataclasses import dataclass, replace
from datetime import datetime
from typing import Literal

from tracker.domain.dates import dm, weekday
from tracker.domain.model import Holiday, Member, Permission, Role, StepKind, Thresholds, WorkingHours
from tracker.domain.naming import DEFAULT_RULE, RuleError, compile_rule, coverage, parse_summary

from ..context import Deps
from ..dto import JiraField
from ..errors import Conflict, Invalid, NotFound
from ..ports import JiraError
from ..settings import TrackerSettings
from ..sync import CATEGORY_STATUS

INTERVALS = ("5", "15", "60", "manual")
STATUSES = ("N", "W", "R", "C")
COUNTRIES = ("VN", "JP")


def _load(d: Deps) -> TrackerSettings:
    with d.uow() as u:
        return u.settings.load()


def _save(d: Deps, s: TrackerSettings) -> None:
    with d.uow() as u:
        u.settings.save(s)
        u.commit()


# --- Jira connection & epics ----------------------------------------------------------------------------------------

@dataclass(frozen=True)
class EpicView:
    key: str
    title: str
    project: str


@dataclass(frozen=True)
class JiraSettingsView:
    url: str
    email: str
    token_masked: str
    interval: str
    status: str | None
    epics: list[EpicView]
    last_full_sync: datetime | None


def jira_settings(d: Deps) -> JiraSettingsView:
    with d.uow() as u:
        s = u.settings.load()
        anken = {a.key: a for a in u.anken.list()}
        runs_ok = u.sync_runs.latest_ok()
    token = d.secrets.decrypt(s.jira.token_encrypted)
    masked = (token[:12] + "••••••••") if token else ""
    epics = [EpicView(k, anken[k].title if k in anken else "", anken[k].project if k in anken else k.split("-")[0]) for k in s.jira.epics]
    return JiraSettingsView(s.jira.url, s.jira.email, masked, s.jira.interval, s.jira.status, epics,
                            runs_ok.finished_at if runs_ok else None)


def test_jira(d: Deps, url: str, email: str, token: str | None) -> Literal["ok", "fail"]:
    stored = d.secrets.decrypt(_load(d).jira.token_encrypted)
    try:
        return "ok" if d.reader_for(url.strip(), email.strip(), token or stored).test_connection() else "fail"
    except JiraError:
        return "fail"


def save_jira(d: Deps, url: str, email: str, token: str | None, interval: str) -> JiraSettingsView:
    if interval not in INTERVALS:
        raise Invalid(f"Sync interval must be one of {', '.join(INTERVALS)}", "interval")
    if not re.match(r"^https://", url.strip()):
        raise Invalid("Site URL must start with https://", "url")
    s = _load(d)
    s.jira.url, s.jira.email, s.jira.interval = url.strip().rstrip("/"), email.strip(), interval
    if token:
        s.jira.token_encrypted = d.secrets.encrypt(token)
    s.jira.status = test_jira(d, s.jira.url, s.jira.email, token)
    _save(d, s)
    return jira_settings(d)


def add_epic(d: Deps, key: str) -> JiraSettingsView:
    k = key.strip().upper()
    if not re.fullmatch(r"[A-Z][A-Z0-9_]*-\d+", k):
        raise Invalid("Enter an Epic key such as PAY-1100", "key")
    s = _load(d)
    if k in s.jira.epics:
        raise Conflict(f"{k} is already tracked", "key")
    epic = d.reader.get_epic(k)  # JiraError propagates: Jira being unreachable is not a problem with the key
    if not epic:
        raise NotFound(f"{k} was not found or is not an Epic", "key")
    s.jira.epics.append(k)
    _save(d, s)
    d.sync.run(full=True)
    return jira_settings(d)


def remove_epic(d: Deps, key: str) -> JiraSettingsView:
    s = _load(d)
    if key not in s.jira.epics:
        raise NotFound(f"{key} is not tracked", "key")
    s.jira.epics.remove(key)
    _save(d, s)
    d.sync.run(full=True)
    return jira_settings(d)


# --- field mapping ---------------------------------------------------------------------------------------------------

TARGET_TYPES = {"planned_start": ("date", "datetime"), "planned_end": ("date", "datetime"), "effort": ("number",)}


@dataclass(frozen=True)
class FieldMappingView:
    planned_start: str
    planned_end: str
    effort: str
    catalog: list[JiraField]


@dataclass(frozen=True)
class FieldCheck:
    target: str  # planned_start | planned_end | effort
    field_id: str
    status: str  # ok | not_mapped | not_found | type_mismatch
    value: str | float | None  # date for dates, hours for effort


@dataclass(frozen=True)
class FieldMappingCheck:
    key: str
    summary: str
    results: list[FieldCheck]


def _catalog(d: Deps) -> list[JiraField]:
    try:
        return d.reader.list_fields()
    except JiraError:
        return []


def field_mapping(d: Deps) -> FieldMappingView:
    fm = _load(d).field_mapping
    return FieldMappingView(fm.planned_start, fm.planned_end, fm.effort, _catalog(d))


def save_field_mapping(d: Deps, mapping: Mapping[str, str]) -> FieldMappingView:
    s = _load(d)
    s.field_mapping = replace(s.field_mapping, **{k: v.strip() for k, v in mapping.items() if k in TARGET_TYPES})
    _save(d, s)
    d.sync.run(full=True)
    return field_mapping(d)


def check_field_mapping(d: Deps, sample_key: str, mapping: Mapping[str, str]) -> FieldMappingCheck:
    key = sample_key.strip().upper()
    snap = d.reader.get_issue(key) if key else None
    if not snap:
        raise NotFound(f"Ticket {key or '(empty)'} was not found", "sample")
    fm = _load(d).field_mapping
    types = {f.id: f.type for f in _catalog(d)}
    out = []
    for target, accepted in TARGET_TYPES.items():
        fid = (mapping.get(target, getattr(fm, target)) or "").strip()
        if not fid:
            out.append(FieldCheck(target, fid, "not_mapped", None))
        elif fid not in types:
            out.append(FieldCheck(target, fid, "not_found", None))
        elif types[fid] not in accepted:
            out.append(FieldCheck(target, fid, "type_mismatch", None))
        else:
            fresh = d.reader.get_issue(key) if fid not in snap.fields else snap
            raw = fresh.fields.get(fid) if fresh else None
            value = (str(raw)[:10] if raw else None) if target != "effort" else (
                (float(raw) / 3600 if fid in ("timeoriginalestimate", "aggregatetimeoriginalestimate") else float(raw)) if raw else None)
            out.append(FieldCheck(target, fid, "ok", value))
    return FieldMappingCheck(key, snap.summary, out)


# --- status mapping -------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class StatusRow:
    name: str
    category: str
    mapped: str | None  # explicit mapping, if any
    effective: str  # what the tracker uses


def status_mapping(d: Deps) -> list[StatusRow]:
    sm = _load(d).status_mapping
    names = sorted(set(sm.seen) | set(sm.by_name))
    return [StatusRow(n, sm.seen.get(n, ""), sm.by_name.get(n),
                      sm.by_name.get(n) or CATEGORY_STATUS.get(sm.seen.get(n, ""), CATEGORY_STATUS["indeterminate"]).value)
            for n in names]


def save_status_mapping(d: Deps, mapping: Mapping[str, str | None]) -> list[StatusRow]:
    bad = [v for v in mapping.values() if v and v not in STATUSES]
    if bad:
        raise Invalid(f"Status must be one of {', '.join(STATUSES)}", "mapping")
    s = _load(d)
    s.status_mapping.by_name = {k: v for k, v in mapping.items() if v}
    _save(d, s)
    d.sync.run(full=True)
    return status_mapping(d)


# --- name rules -----------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class NameRulesView:
    rule: str
    aliases: dict[str, str]
    matched: int
    total: int


@dataclass(frozen=True)
class NameRulePreview:
    error: str | None
    tokens: list[str]
    values: dict[str, str]
    step: str | None
    sample_matches: bool
    matched: int
    total: int


def _summaries(d: Deps) -> list[str]:
    with d.uow() as u:
        epics = u.settings.load().jira.epics
        return [i.summary for i in u.issues.list(epics) if i.kind.value == "subtask"]


def _aliases(raw: Mapping[str, str] | None, current: Mapping[StepKind, str]) -> dict[StepKind, str]:
    if raw is None:
        return dict(current)
    try:
        return {StepKind(k): v for k, v in raw.items()}
    except ValueError as e:
        raise Invalid(str(e), "aliases") from None


def name_rules(d: Deps) -> NameRulesView:
    nr = _load(d).name_rules
    summaries = _summaries(d)
    return NameRulesView(nr.rule, {k.value: v for k, v in nr.aliases.items()},
                         coverage(compile_rule(nr.rule, nr.aliases), summaries), len(summaries))


def preview_name_rule(d: Deps, rule: str, aliases: Mapping[str, str] | None, sample: str) -> NameRulePreview:
    summaries = _summaries(d)
    al = _aliases(aliases, _load(d).name_rules.aliases)
    try:
        compiled = compile_rule(rule, al)
    except RuleError as e:
        return NameRulePreview(str(e), [], {}, None, False, 0, len(summaries))
    p = parse_summary(compiled, sample)
    return NameRulePreview(None, list(compiled.tokens), p.values if p else {}, p.step.value if p else None, p is not None,
                           coverage(compiled, summaries), len(summaries))


def save_name_rules(d: Deps, rule: str, aliases: Mapping[str, str]) -> NameRulesView:
    s = _load(d)
    al = _aliases(aliases, s.name_rules.aliases)
    try:
        compile_rule(rule or DEFAULT_RULE, al)
    except RuleError as e:
        raise Invalid(str(e), "rule") from None
    s.name_rules.rule, s.name_rules.aliases = rule or DEFAULT_RULE, al
    _save(d, s)
    d.sync.run(full=True)
    return name_rules(d)


# --- members --------------------------------------------------------------------------------------------------------

@dataclass(frozen=True)
class MemberPatch:
    name: str | None = None
    role: Role | None = None
    permission: Permission | None = None
    country: str | None = None
    clear_role: bool = False


def update_member(d: Deps, member_id: str, p: MemberPatch) -> Member:
    if p.country and p.country not in COUNTRIES:
        raise Invalid(f"Country must be one of {', '.join(COUNTRIES)}", "country")
    if p.name is not None and not p.name.strip():
        raise Invalid("Name is required", "name")
    with d.uow() as u:
        m = u.members.get(member_id)
        if not m:
            raise NotFound(f"Member {member_id} was not found")
        m = replace(m, name=p.name.strip() if p.name else m.name, role=None if p.clear_role else (p.role or m.role),
                    permission=p.permission or m.permission, country=p.country or m.country)
        u.members.save(m)
        u.commit()
    return m


# --- calendar settings ----------------------------------------------------------------------------------------------

def holidays(d: Deps) -> list[Holiday]:
    with d.uow() as u:
        return sorted(u.calendar.holidays().values(), key=lambda h: h.date)


def add_holiday(d: Deps, date: str, name: str, countries: Sequence[str]) -> list[Holiday]:
    if not name.strip():
        raise Invalid("Name is required", "name")
    if not countries or any(c not in COUNTRIES for c in countries):
        raise Invalid("Choose Vietnam, Japan or both", "countries")
    with d.uow() as u:
        u.calendar.save_holiday(Holiday(date, tuple(countries), name.strip()))
        u.commit()
    return holidays(d)


def delete_holiday(d: Deps, date: str) -> list[Holiday]:
    with d.uow() as u:
        u.calendar.delete_holiday(date)
        u.commit()
    return holidays(d)


def extra_days(d: Deps) -> dict[str, str]:
    with d.uow() as u:
        return dict(sorted(u.calendar.extra_days().items()))


def add_extra_day(d: Deps, date: str, note: str) -> dict[str, str]:
    with d.uow() as u:
        days = u.settings.load().working_hours.days
        if weekday(date) in days:
            raise Invalid(f"{dm(date)} is already a working day", "date")
        if date in u.calendar.extra_days():
            raise Conflict(f"{dm(date)} is already in the list", "date")
        u.calendar.save_extra_day(date, note.strip() or "Extra working day")
        u.commit()
    return extra_days(d)


def delete_extra_day(d: Deps, date: str) -> dict[str, str]:
    with d.uow() as u:
        u.calendar.delete_extra_day(date)
        u.commit()
    return extra_days(d)


def working_hours(d: Deps) -> WorkingHours:
    return _load(d).working_hours


def save_working_hours(d: Deps, wh: WorkingHours) -> WorkingHours:
    if not 0 < wh.hours_per_day <= 24:
        raise Invalid("Hours per working day must be between 0 and 24", "hours_per_day")
    if wh.timesheet_tolerance_h < 0:
        raise Invalid("Tolerance cannot be negative", "timesheet_tolerance_h")
    if not wh.days or any(x not in range(7) for x in wh.days):
        raise Invalid("Choose at least one working day", "days")
    s = _load(d)
    s.working_hours = replace(wh, days=tuple(sorted(set(wh.days))))
    _save(d, s)
    return s.working_hours


def alerts(d: Deps) -> Thresholds:
    return _load(d).thresholds


def save_alerts(d: Deps, t: Thresholds) -> Thresholds:
    for k, v in vars(t).items():
        if v < 0:
            raise Invalid("Thresholds cannot be negative", k)
    s = _load(d)
    s.thresholds = t
    _save(d, s)
    return t
