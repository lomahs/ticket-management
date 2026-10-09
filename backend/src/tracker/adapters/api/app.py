"""FastAPI application: /api/v1 routers, problem+json errors, optional static frontend and sync scheduler."""

from collections.abc import Callable
from contextlib import asynccontextmanager
from pathlib import Path

from fastapi import FastAPI
from fastapi.responses import FileResponse
from fastapi.staticfiles import StaticFiles

from tracker.application.context import Deps

from . import errors
from .routers import anken, calendar, effort, planning, settings, system, tickets, work

API = "/api/v1"


def create_app(deps: Deps, *, static_dir: Path | None = None, background: Callable[[Deps], object] | None = None) -> FastAPI:
    @asynccontextmanager
    async def lifespan(app: FastAPI):
        task = background(deps) if background else None
        yield
        if task is not None:
            task.cancel()

    app = FastAPI(title="Team Tracker API", version="1", lifespan=lifespan)
    app.state.deps = deps
    errors.install(app)
    for r in (system, work, anken, planning, effort, calendar, tickets, settings):
        app.include_router(r.router, prefix=API)

    if static_dir and (static_dir / "index.html").exists():
        app.mount("/assets", StaticFiles(directory=static_dir / "assets"), name="assets")

        @app.get("/{path:path}", include_in_schema=False)
        def spa(path: str):
            f = static_dir / path
            return FileResponse(f if path and f.is_file() else static_dir / "index.html")

    return app
