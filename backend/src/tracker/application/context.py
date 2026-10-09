"""Shared plumbing for use cases: dependencies, the loaded team context, and the TaskCard read model."""

from collections.abc import Callable, Sequence
from dataclasses import dataclass, field

from tracker.domain.calendar import WorkCalendar
from tracker.domain.flags import Function, IssueFlags, actual_span, build_functions, issue_flags, worker_of
from tracker.domain.model import Anken, Issue, IssueKind, Leave, Member

from .ports import Clock, JiraReader, JiraWriter, SecretBox, UnitOfWork, UnitOfWorkFactory
from .settings import TrackerSettings
from .sync import SyncFromJira


@dataclass
class Deps:
    uow: UnitOfWorkFactory
    clock: Clock
    reader: JiraReader
    writer: JiraWriter | None
    secrets: SecretBox
    reader_for: Callable[[str, str, str], JiraReader]  # url, email, token → reader, for testing unsaved credentials
    mode: str  # demo | jira

    @property
    def sync(self) -> SyncFromJira:
        return SyncFromJira(self.uow, self.reader, self.clock)


@dataclass(frozen=True)
class TaskCard:
    """One issue as screens show it: raw values plus the flags the domain derived."""

    key: str
    summary: str
    project: str
    epic_key: str
    phase: str
    function_id: str | None
    function_name: str | None
    step: str | None
    status: str
    jira_status: str
    assignee_id: str | None
    worker_id: str | None
    planned_start: str | None
    planned_end: str | None
    estimate_h: float
    logged_h: float
    remaining_h: float
    actual_start: str | None
    actual_end: str | None
    is_current: bool
    late: bool
    late_days: int
    soon: bool
    over: bool
    tone: str
    held_days: int
    rounds: int


@dataclass
class Context:
    today: str
    settings: TrackerSettings
    members: dict[str, Member]
    anken: list[Anken]
    issues: list[Issue]
    leaves: list[Leave]
    cal: WorkCalendar
    functions: list[Function] = field(default_factory=list)
    flags: dict[str, IssueFlags] = field(default_factory=dict)
    owner: dict[str, str | None] = field(default_factory=dict)

    @property
    def subtasks(self) -> list[Issue]:
        return [i for i in self.issues if i.kind == IssueKind.SUBTASK]

    @property
    def parents(self) -> list[Issue]:
        return [i for i in self.issues if i.kind == IssueKind.PARENT]

    def get(self, key: str) -> Issue | None:
        return next((i for i in self.issues if i.key == key), None)

    def worker(self, i: Issue) -> str | None:
        return worker_of(i, self.owner.get(i.key))

    def card(self, i: Issue) -> TaskCard:
        f = self.flags[i.key]
        span = actual_span(i, self.today)
        closed = i.status.value == "C"
        return TaskCard(
            key=i.key, summary=i.summary, project=i.project, epic_key=i.epic_key, phase=i.phase,
            function_id=i.function_id, function_name=i.function_name, step=i.step.value if i.step else None,
            status=i.status.value, jira_status=i.jira_status, assignee_id=i.assignee_id, worker_id=self.worker(i),
            planned_start=i.planned_start, planned_end=i.planned_end, estimate_h=i.estimate_h, logged_h=round(i.logged_h, 2),
            remaining_h=0 if closed else round(max(0.0, i.estimate_h - i.logged_h), 2),
            actual_start=span[0] if span else None, actual_end=span[1] if span else None,
            is_current=f.is_current, late=f.late, late_days=f.late_days, soon=f.soon, over=f.over, tone=f.tone,
            held_days=f.held_days, rounds=f.rounds,
        )


def make_calendar(uow: UnitOfWork, settings: TrackerSettings, members: dict[str, Member]) -> tuple[WorkCalendar, list[Leave]]:
    leaves = uow.calendar.leaves()
    cal = WorkCalendar(working_days=settings.working_hours.days, extra_days=uow.calendar.extra_days(),
                       holidays=uow.calendar.holidays(), members=members, leaves=leaves)
    return cal, leaves


def load_context(uow: UnitOfWork, today: str, epics: Sequence[str] | None = None) -> Context:
    """Loads members, calendar and the issues of the tracked epics (or `epics`), and derives their flags."""
    settings = uow.settings.load()
    members = {m.id: m for m in uow.members.list()}
    cal, leaves = make_calendar(uow, settings, members)
    tracked = list(epics) if epics is not None else settings.jira.epics
    ctx = Context(today, settings, members, [a for a in uow.anken.list() if a.key in tracked], uow.issues.list(tracked), leaves, cal)
    ctx.functions = build_functions(ctx.subtasks)
    t = settings.thresholds
    for fn in ctx.functions:
        for idx, c in enumerate(fn.cells):
            if c:
                ctx.owner[c.key] = fn.owner_id
                ctx.flags[c.key] = issue_flags(c, is_current=idx == fn.current, owner_id=fn.owner_id, today=today, thresholds=t)
    for i in ctx.issues:
        if i.key not in ctx.flags:
            ctx.owner[i.key] = None
            ctx.flags[i.key] = issue_flags(i, is_current=i.status.value != "C", owner_id=None, today=today, thresholds=t)
    return ctx
