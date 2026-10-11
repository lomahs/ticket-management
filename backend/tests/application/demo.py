"""Builds use-case dependencies on a fresh SQLite database synced from the demo FakeJira."""

from pathlib import Path

from tracker.adapters.clock import FixedClock
from tracker.adapters.fake.dataset import DEMO_TODAY
from tracker.adapters.fake.jira import FakeJira, seed_tracker
from tracker.adapters.persistence.db import Database
from tracker.adapters.persistence.secrets import FernetSecretBox
from tracker.application.context import Deps


class _BadReader:
    """A site that rejects the credentials."""

    def search(self, epic_keys, updated_since):
        return []

    def get_epic(self, key):
        return None

    def get_issue(self, key):
        return None

    def list_fields(self):
        return []

    def test_connection(self):
        return False


def demo_deps(tmp: Path, *, writable: bool = True) -> Deps:
    db = Database(f"sqlite:///{tmp / 'demo.db'}")
    db.migrate()
    box = FernetSecretBox.from_key_file(tmp / "secret.key")
    with db.uow() as u:
        seed_tracker(u, box)
        u.commit()
    clock = FixedClock(DEMO_TODAY)
    jira = FakeJira(clock)

    def reader_for(url: str, email: str, token: str):
        return jira if "atlassian.net" in url and len(token) > 8 else _BadReader()

    deps = Deps(uow=db.uow, clock=clock, reader=jira, writer=jira if writable else None, secrets=box,
                reader_for=reader_for, mode="demo" if writable else "jira")
    assert deps.sync.run(full=True).status == "ok"
    return deps
