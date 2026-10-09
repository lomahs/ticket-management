"""Composition root: reads the environment, picks adapters, builds the app.

  TRACKER_MODE        demo | jira (default jira)
  TRACKER_DB_URL      SQLAlchemy URL (default: sqlite at backend/data/tracker.db; demo: .tmp/demo/tracker.db, recreated)
  TRACKER_TODAY       pin "today" (demo default 2026-10-07)
  TRACKER_SECRET_KEY  Fernet key for the stored Jira token (default: backend/data/secret.key, created on first run)
  TRACKER_STATIC_DIR  built frontend to serve (default: frontend/dist when it exists)

Run: uvicorn tracker.bootstrap:create_app_from_env --factory
"""

import logging
import os
from pathlib import Path

from fastapi import FastAPI

from tracker.adapters.api.app import create_app
from tracker.adapters.clock import FixedClock, SystemClock
from tracker.adapters.fake.dataset import DEMO_TODAY
from tracker.adapters.fake.jira import FakeJira, seed_tracker
from tracker.adapters.jira.client import JiraCloudReader, JiraConfig
from tracker.adapters.persistence.db import Database
from tracker.adapters.persistence.secrets import FernetSecretBox
from tracker.adapters.scheduler import start as start_scheduler
from tracker.application.context import Deps

BACKEND = Path(__file__).resolve().parents[2]
ROOT = BACKEND.parent
DATA = BACKEND / "data"


def build_demo(db_path: Path, today: str = DEMO_TODAY) -> Deps:
    """Fresh database seeded with the demo team and synced from the simulated Jira site."""
    db_path.unlink(missing_ok=True)
    db = Database(f"sqlite:///{db_path}")
    db.migrate()
    box = FernetSecretBox.from_key_file(db_path.with_name("secret.key"))
    with db.uow() as u:
        seed_tracker(u, box)
        u.commit()
    clock = FixedClock(today)
    jira = FakeJira(clock)
    deps = Deps(db.uow, clock, jira, jira, box, lambda url, email, token: jira, "demo")
    deps.sync.run(full=True)
    return deps


def build_jira(db_url: str, today: str | None) -> Deps:
    db = Database(db_url)
    db.migrate()
    box = FernetSecretBox.from_key_file(DATA / "secret.key")

    def config() -> JiraConfig:
        with db.uow() as u:
            s = u.settings.load()
        fm = s.field_mapping
        fields = tuple(f for f in (fm.planned_start, fm.planned_end, fm.effort) if f)
        return JiraConfig(s.jira.url, s.jira.email, box.decrypt(s.jira.token_encrypted), fields)

    def reader_for(url: str, email: str, token: str) -> JiraCloudReader:
        return JiraCloudReader(lambda: JiraConfig(url, email, token, ()))

    clock = FixedClock(today) if today else SystemClock()
    # Phase 1 reads Jira only: no writer, so the UI disables Jira edits.
    return Deps(db.uow, clock, JiraCloudReader(config), None, box, reader_for, "jira")


def create_app_from_env() -> FastAPI:
    logging.basicConfig(level=logging.INFO)
    mode = os.environ.get("TRACKER_MODE", "jira")
    static = Path(os.environ.get("TRACKER_STATIC_DIR", ROOT / "frontend" / "dist"))
    if mode == "demo":
        deps = build_demo(ROOT / ".tmp" / "demo" / "tracker.db", os.environ.get("TRACKER_TODAY", DEMO_TODAY))
        return create_app(deps, static_dir=static)
    deps = build_jira(os.environ.get("TRACKER_DB_URL", f"sqlite:///{DATA / 'tracker.db'}"), os.environ.get("TRACKER_TODAY"))
    return create_app(deps, static_dir=static, background=start_scheduler)
