from fastapi import APIRouter

from tracker.application.use_cases import tickets
from tracker.application.use_cases.tickets import TicketDetail, TicketPatch
from tracker.domain.qa import Answer

from ..deps import D
from ..schemas import QuestionIn, TicketPatchIn

router = APIRouter(tags=["tickets"])


@router.get("/tickets/{key}")
def detail(d: D, key: str) -> TicketDetail:
    return tickets.detail(d, key)


@router.patch("/tickets/{key}")
def patch(d: D, key: str, body: TicketPatchIn) -> TicketDetail:
    return tickets.patch(d, key, TicketPatch(**body.model_dump()))


@router.post("/qa")
def ask(d: D, body: QuestionIn) -> Answer:
    return tickets.ask(d, body.q)
