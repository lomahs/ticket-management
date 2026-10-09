from typing import Literal

from fastapi import APIRouter

from tracker.application.use_cases import work
from tracker.application.use_cases.work import MyWork

from ..deps import D

router = APIRouter(tags=["my work"])


@router.get("/my-work")
def my_work(d: D, member: str, backlog: Literal["week", "month"] = "week") -> MyWork:
    return work.my_work(d, member, backlog)
