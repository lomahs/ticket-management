from typing import Annotated

from fastapi import APIRouter, Path, Query

from tracker.application.use_cases import effort
from tracker.application.use_cases.effort import DayDetailView, EffortAnalysis, TimesheetView

from ..deps import D
from ..schemas import ISO_DATE

router = APIRouter(prefix="/effort", tags=["effort"])


@router.get("/timesheet")
def timesheet(d: D, start: Annotated[str, Query(alias="from", pattern=ISO_DATE)],
              end: Annotated[str, Query(alias="to", pattern=ISO_DATE)]) -> TimesheetView:
    return effort.timesheet(d, start, end)


@router.get("/timesheet/{member}/{date}")
def day(d: D, member: str, date: Annotated[str, Path(pattern=ISO_DATE)]) -> DayDetailView:
    return effort.day_detail(d, member, date)


@router.get("/analysis")
def analysis(d: D, anken: str | None = None) -> EffortAnalysis:
    return effort.analysis(d, anken)
