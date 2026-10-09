from typing import Annotated

from fastapi import APIRouter, Query

from tracker.application.use_cases import planning
from tracker.application.use_cases.planning import PlanChange, PlanningPreview, PlanningView

from ..deps import D
from ..schemas import ISO_DATE, KeysOut, PlanningPreviewIn, PlanningPushIn

router = APIRouter(prefix="/planning", tags=["planning"])


@router.get("")
def view(d: D, start: Annotated[str, Query(pattern=ISO_DATE)], weeks: Annotated[int, Query(ge=1, le=2)] = 2) -> PlanningView:
    return planning.view(d, start, weeks)


@router.post("/preview")
def preview(d: D, body: PlanningPreviewIn) -> PlanningPreview:
    return planning.preview(d, body.start, [PlanChange(c.key, c.start, c.member_id) for c in body.changes])


@router.post("/push")
def push(d: D, body: PlanningPushIn) -> KeysOut:
    return KeysOut(keys=planning.push(d, [PlanChange(c.key, c.start, c.member_id) for c in body.changes]))
