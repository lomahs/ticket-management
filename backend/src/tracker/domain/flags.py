"""Function grouping and per-issue flags: current step, late/soon/over, tone, held days, review rounds."""

from collections.abc import Iterable
from dataclasses import dataclass

from .dates import weekdays_after
from .model import EventField, Issue, Status, Thresholds
from .steps import STEP_BY_KIND, STEP_INDEX, STEPS

PROGRESS_WEIGHT = {Status.CLOSED: 1.0, Status.RESOLVED: 0.8, Status.WIP: 0.4, Status.NYS: 0.0}


@dataclass(frozen=True)
class Function:
    epic_key: str
    id: str
    name: str
    cells: tuple[Issue | None, ...]  # one slot per step, None when that sub-task does not exist

    @property
    def owner_id(self) -> str | None:
        """The BA who owns the function: the Create step's assignee."""
        create = self.cells[0]
        return create.assignee_id if create else next((c.assignee_id for c in self.cells if c), None)

    @property
    def reviewer_id(self) -> str | None:
        """The BAL reviewing the function."""
        c = self.cells[1]
        return c.assignee_id if c else None

    @property
    def current(self) -> int:
        """Index of the first step that is missing or not closed; 5 when all steps are closed."""
        return next((i for i, c in enumerate(self.cells) if c is None or c.status != Status.CLOSED), len(STEPS))

    def issues(self) -> list[Issue]:
        return [c for c in self.cells if c]


def build_functions(issues: Iterable[Issue]) -> list[Function]:
    """Groups function sub-tasks by (epic, function id), ordered by first appearance; non-function issues are ignored."""
    groups: dict[tuple[str, str], list[Issue | None]] = {}
    names: dict[tuple[str, str], str] = {}
    for i in issues:
        if not i.is_function_step:
            continue
        k = (i.epic_key, i.function_id)
        slots = groups.setdefault(k, [None] * len(STEPS))
        slots[STEP_INDEX[i.step]] = i
        names.setdefault(k, i.function_name or i.function_id)
    return [Function(epic, fid, names[(epic, fid)], tuple(cells)) for (epic, fid), cells in groups.items()]


def progress_pct(fn: Function) -> int:
    return round(sum(PROGRESS_WEIGHT[c.status] for c in fn.issues()) / len(STEPS) * 100)


def current_status(fn: Function) -> Status:
    if fn.current >= len(STEPS):
        return Status.CLOSED
    c = fn.cells[fn.current]
    return c.status if c else Status.NYS


def group_status(fn: Function) -> str:
    """Summary bucket: N (not started), W (in progress) or D (current step resolved/closed)."""
    s = current_status(fn)
    return "D" if s in (Status.RESOLVED, Status.CLOSED) else s.value


def function_state(fn: Function, late_keys: set[str]) -> str:
    if fn.current >= len(STEPS):
        return "done"
    if all(c.status == Status.NYS for c in fn.issues()):
        return "nys"
    if any(c.key in late_keys for c in fn.issues()):
        return "delay"
    return "ontime"


def function_progress(fn: Function, today: str, late_keys: set[str]) -> str:
    """late / early / ontime: compares finished steps with steps whose plan has already ended."""
    if any(c.key in late_keys for c in fn.issues()):
        return "late"
    due = sum(1 for c in fn.issues() if c.planned_end and c.planned_end < today)
    done = sum(1 for c in fn.issues() if c.status in (Status.CLOSED, Status.RESOLVED))
    return "early" if done > due else "ontime"


def worker_of(issue: Issue, owner_id: str | None) -> str | None:
    """Who does the work: the reviewer on review steps, the function owner on other steps, else the assignee."""
    if issue.is_function_step and not STEP_BY_KIND[issue.step].review and owner_id:
        return owner_id
    return issue.assignee_id


@dataclass(frozen=True)
class IssueFlags:
    is_current: bool
    late: bool
    late_days: int
    soon: bool
    over: bool
    tone: str
    held_days: int
    rounds: int


def _tone(i: Issue, today: str) -> str:
    if i.status == Status.NYS:
        return "nys"
    if i.status == Status.RESOLVED:
        return "res"
    if i.status == Status.CLOSED:
        return "clo"
    if i.planned_end and i.planned_end < today:
        return "delay"
    first = min((w.date for w in i.worklogs), default=None)
    if first and i.planned_start:
        if first > i.planned_start:
            return "latestart"
        if first < i.planned_start:
            return "early"
    return "ontime"


def _last_event(i: Issue, field: EventField, to: str | None = None) -> str | None:
    dates = [e.at for e in i.events if e.field == field and (to is None or e.to_value == to)]
    return max(dates) if dates else None


def _held(i: Issue, owner_id: str | None, is_current: bool, today: str) -> int:
    since = None
    step = STEP_BY_KIND.get(i.step) if i.is_function_step else None
    if i.status == Status.RESOLVED:
        since = _last_event(i, EventField.STATUS, Status.RESOLVED.value)
    elif step and step.fix and owner_id and i.assignee_id != owner_id:
        since = _last_event(i, EventField.ASSIGNEE)
    elif step and step.review and is_current and i.status in (Status.NYS, Status.WIP):
        since = _last_event(i, EventField.ASSIGNEE) or i.created
    return weekdays_after(since, today) if since else 0


def _rounds(i: Issue, owner_id: str | None) -> int:
    if not (i.is_function_step and STEP_BY_KIND[i.step].fix):
        return 0
    n = sum(1 for e in i.events
            if e.field == EventField.ASSIGNEE and e.to_value == owner_id and e.from_value not in (None, owner_id))
    return n or (0 if i.status == Status.NYS else 1)


def issue_flags(issue: Issue, *, is_current: bool, owner_id: str | None, today: str, thresholds: Thresholds) -> IssueFlags:
    active = issue.status == Status.WIP or (issue.status == Status.NYS and is_current)
    pe = issue.planned_end
    late = bool(active and pe and pe < today)
    return IssueFlags(
        is_current=is_current,
        late=late,
        late_days=weekdays_after(pe, today) if late else 0,
        soon=bool(not late and active and pe and pe >= today and weekdays_after(today, pe) <= thresholds.soon_days),
        over=issue.logged_h > issue.estimate_h,
        tone=_tone(issue, today),
        held_days=_held(issue, owner_id, is_current, today),
        rounds=_rounds(issue, owner_id),
    )
