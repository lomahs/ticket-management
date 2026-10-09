from fastapi import APIRouter

from tracker.application.use_cases import system
from tracker.application.use_cases.system import Meta, SyncStatus
from tracker.domain.model import Member

from ..deps import D
from ..schemas import SyncIn

router = APIRouter(tags=["system"])


@router.get("/meta")
def meta(d: D) -> Meta:
    return system.meta(d)


@router.get("/members")
def members(d: D) -> list[Member]:
    return system.members(d)


@router.post("/sync")
def sync(d: D, body: SyncIn) -> SyncStatus:
    return system.run_sync(d, body.full)


@router.get("/sync/runs/latest")
def latest_sync(d: D) -> SyncStatus:
    return system.sync_status(d)
