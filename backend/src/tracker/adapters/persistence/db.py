"""Engine, migrations and the unit of work."""

from pathlib import Path

from alembic import command
from alembic.config import Config
from sqlalchemy import create_engine, event
from sqlalchemy.engine import Engine

from .repositories import SqlAnken, SqlCalendar, SqlIssues, SqlMembers, SqlSettings, SqlSyncRuns

MIGRATIONS = Path(__file__).with_name("migrations")


class SqlUnitOfWork:
    """One transaction. Leaving the block without commit() rolls back."""

    def __init__(self, engine: Engine):
        self.engine = engine

    def __enter__(self) -> "SqlUnitOfWork":
        self.conn = c = self.engine.connect()
        self.tx = c.begin()
        self.members, self.issues, self.anken = SqlMembers(c), SqlIssues(c), SqlAnken(c)
        self.calendar, self.settings, self.sync_runs = SqlCalendar(c), SqlSettings(c), SqlSyncRuns(c)
        return self

    def __exit__(self, *exc: object) -> None:
        if self.tx.is_active:
            self.tx.rollback()
        self.conn.close()

    def commit(self) -> None:
        self.tx.commit()
        self.tx = self.conn.begin()


class Database:
    def __init__(self, url: str):
        self.url = url
        if url.startswith("sqlite:///"):
            Path(url.removeprefix("sqlite:///")).parent.mkdir(parents=True, exist_ok=True)
        self.engine = create_engine(url)
        if url.startswith("sqlite"):
            event.listen(self.engine, "connect", _sqlite_pragmas)

    def migrate(self) -> None:
        cfg = Config()
        cfg.set_main_option("script_location", str(MIGRATIONS))
        with self.engine.begin() as conn:
            cfg.attributes["connection"] = conn
            command.upgrade(cfg, "head")

    def uow(self) -> SqlUnitOfWork:
        return SqlUnitOfWork(self.engine)


def _sqlite_pragmas(dbapi_conn, _record) -> None:
    cur = dbapi_conn.cursor()
    cur.execute("PRAGMA foreign_keys=ON")
    cur.execute("PRAGMA journal_mode=WAL")
    cur.close()
