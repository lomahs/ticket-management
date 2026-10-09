from fastapi import APIRouter

from tracker.application.use_cases import anken
from tracker.application.use_cases.anken import AnkenCard, AnkenDetail, FunctionDraft, FunctionPreview, MatrixView

from ..deps import D
from ..schemas import FunctionCreateIn, FunctionDraftIn, KeysOut

router = APIRouter(prefix="/anken", tags=["anken"])


def _draft(b: FunctionDraftIn) -> FunctionDraft:
    return FunctionDraft(b.parent_key, b.name, b.ba, b.bal, b.jm, b.create_hours,
                         [o.model_dump(exclude_none=True) if o else None for o in b.overrides])


@router.get("")
def overview(d: D) -> list[AnkenCard]:
    return anken.overview(d)


@router.get("/{epic}")
def detail(d: D, epic: str) -> AnkenDetail:
    return anken.detail(d, epic)


@router.get("/{epic}/matrix")
def matrix(d: D, epic: str) -> MatrixView:
    return anken.matrix(d, epic)


@router.post("/{epic}/functions/preview")
def preview_function(d: D, epic: str, body: FunctionDraftIn) -> FunctionPreview:
    return anken.preview_function(d, epic, _draft(body))


@router.post("/{epic}/functions", status_code=201)
def create_function(d: D, epic: str, body: FunctionCreateIn) -> KeysOut:
    return KeysOut(keys=anken.create_function(d, epic, _draft(body), body.function_id))
