from typing import Annotated

from fastapi import APIRouter, File, Query, UploadFile
from fastapi.responses import Response
from pydantic import BaseModel

from tracker.application.errors import Invalid
from tracker.application.use_cases import calendar
from tracker.application.use_cases.calendar import CalendarView, ImportResult, LeaveDraft
from tracker.domain.leave_import import ImportRow

from ..deps import D
from ..files import SAMPLE_FILE, SAMPLE_ROWS, TEMPLATE_CSV, export_xls, read_rows
from ..schemas import DaysOut, ImportIn, LeaveIn

router = APIRouter(tags=["calendar"])
MONTH = r"^\d{4}-\d{2}$"


class ImportPreviewOut(BaseModel):
    file: str
    rows: list[ImportRow]
    working_days: int


@router.get("/calendar")
def month(d: D, month: Annotated[str, Query(pattern=MONTH)]) -> CalendarView:
    return calendar.month(d, month)


def _draft(edit_id: int | None, b: LeaveIn) -> LeaveDraft:
    return LeaveDraft(edit_id, b.member_id, b.type, b.date_from, b.date_to, b.note)


@router.post("/leaves", status_code=201)
def add_leave(d: D, body: LeaveIn) -> DaysOut:
    return DaysOut(days=calendar.save_leave(d, _draft(None, body)))


@router.put("/leaves/{leave_id}")
def edit_leave(d: D, leave_id: int, body: LeaveIn) -> DaysOut:
    return DaysOut(days=calendar.save_leave(d, _draft(leave_id, body)))


@router.delete("/leaves/{leave_id}", status_code=204)
def delete_leave(d: D, leave_id: int) -> None:
    calendar.delete_leave(d, leave_id)


@router.post("/leaves/import/preview")
async def import_preview(d: D, file: Annotated[UploadFile | None, File()] = None, sample: bool = False) -> ImportPreviewOut:
    if sample:
        name, rows = SAMPLE_FILE, SAMPLE_ROWS
    elif file is not None:
        name = file.filename or "upload"
        rows = read_rows(name, await file.read())
    else:
        raise Invalid("Choose a .xlsx or .csv file", "file")
    p = calendar.import_preview(d, rows)
    return ImportPreviewOut(file=name, rows=p.rows, working_days=p.working_days)


@router.post("/leaves/import")
def import_confirm(d: D, body: ImportIn) -> ImportResult:
    return calendar.import_confirm(d, body.rows)


@router.get("/leaves/template", response_class=Response)
def template() -> Response:
    return Response(TEMPLATE_CSV, media_type="text/csv; charset=utf-8",
                    headers={"Content-Disposition": 'attachment; filename="leave-import-template.csv"'})


@router.get("/leaves/export", response_class=Response)
def export(d: D, month: Annotated[str, Query(pattern=MONTH)]) -> Response:
    return Response(export_xls(calendar.export(d, month)), media_type="application/vnd.ms-excel",
                    headers={"Content-Disposition": f'attachment; filename="team-calendar-{month}.xls"'})
