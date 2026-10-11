"""Small builders for domain objects used across domain tests."""

from tracker.domain.model import EventField, Issue, IssueEvent, IssueKind, Status, StepKind, Worklog

TODAY = "2026-10-07"


def issue(key="PAY-1", *, status=Status.WIP, assignee="an", ps: str | None = "2026-10-05", pe: str | None = "2026-10-09", est=8.0, logged=0.0,
          log_date=None, step: StepKind | None = None, fid=None, fname="Fn", phase="Implement_Coding",
          events=(), created="2026-09-01", epic="PAY-1100", summary=None) -> Issue:
    logs = [Worklog(log_date or ps or TODAY, assignee, logged)] if logged else []
    return Issue(
        key=key, project=key.split("-")[0], epic_key=epic, parent_key=None, kind=IssueKind.SUBTASK,
        summary=summary or key, phase=phase, status=status, assignee_id=assignee, planned_start=ps, planned_end=pe,
        estimate_h=est, created=created, function_id=fid, function_name=fname if fid else None, step=step,
        worklogs=logs, events=list(events),
    )


def assigned(at, frm, to):
    return IssueEvent(at, EventField.ASSIGNEE, frm, to)


def status_to(at, to, frm="W"):
    return IssueEvent(at, EventField.STATUS, frm, to)
