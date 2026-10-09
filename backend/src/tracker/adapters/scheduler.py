"""Periodic Jira sync inside the API process, following the sync interval set in Settings."""

import asyncio
import logging

from tracker.application.context import Deps

log = logging.getLogger("tracker.sync")
IDLE_CHECK_SECONDS = 60  # how often a "manual" interval is re-read


async def _loop(deps: Deps) -> None:
    while True:
        with deps.uow() as u:
            interval = u.settings.load().jira.interval
        if interval == "manual":
            await asyncio.sleep(IDLE_CHECK_SECONDS)
            continue
        await asyncio.sleep(int(interval) * 60)
        result = await asyncio.to_thread(deps.sync.run, False)
        if result.status != "ok":
            log.warning("scheduled sync failed: %s", result.error)


def start(deps: Deps) -> asyncio.Task:
    return asyncio.get_running_loop().create_task(_loop(deps))
