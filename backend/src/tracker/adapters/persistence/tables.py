"""SQLAlchemy table definitions (Core). Repositories map rows to domain objects."""

from sqlalchemy import (
    JSON, Boolean, Column, DateTime, Float, ForeignKey, Index, Integer, MetaData, String, Table, Text, UniqueConstraint,
)

metadata = MetaData()

members = Table(
    "members", metadata,
    Column("id", String, primary_key=True),
    Column("jira_account_id", String, unique=True),
    Column("name", String, nullable=False),
    Column("full_name", String, nullable=False),
    Column("email", String, nullable=False, default=""),
    Column("role", String),
    Column("country", String, nullable=False),
    Column("color", String, nullable=False),
    Column("permission", String, nullable=False, default="user"),
    Column("external", Boolean, nullable=False, default=False),
)

anken = Table(
    "anken", metadata,
    Column("key", String, primary_key=True),
    Column("project", String, nullable=False),
    Column("title", String, nullable=False),
    Column("due_date", String),
)

issues = Table(
    "issues", metadata,
    Column("key", String, primary_key=True),
    Column("project", String, nullable=False),
    Column("epic_key", String, nullable=False, index=True),
    Column("parent_key", String),
    Column("kind", String, nullable=False),
    Column("summary", Text, nullable=False),
    Column("phase", String, nullable=False),
    Column("status", String, nullable=False),
    Column("jira_status", String, nullable=False, default=""),
    Column("assignee_id", String),
    Column("planned_start", String),
    Column("planned_end", String),
    Column("estimate_h", Float, nullable=False, default=0),
    Column("created", String, nullable=False),
    Column("function_id", String),
    Column("function_name", String),
    Column("step", String),
)

worklogs = Table(
    "worklogs", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("issue_key", String, ForeignKey("issues.key", ondelete="CASCADE"), nullable=False),
    Column("date", String, nullable=False),
    Column("member_id", String),
    Column("hours", Float, nullable=False),
    Column("note", Text, nullable=False, default=""),
    Index("ix_worklogs_issue", "issue_key"),
    Index("ix_worklogs_member_date", "member_id", "date"),
)

issue_events = Table(
    "issue_events", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("issue_key", String, ForeignKey("issues.key", ondelete="CASCADE"), nullable=False, index=True),
    Column("at", String, nullable=False),
    Column("field", String, nullable=False),
    Column("from_value", String),
    Column("to_value", String),
)

leaves = Table(
    "leaves", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("member_id", String, nullable=False),
    Column("date", String, nullable=False),
    Column("type", String, nullable=False),
    Column("note", Text, nullable=False, default=""),
    UniqueConstraint("member_id", "date", name="uq_leaves_member_date"),
)

holidays = Table(
    "holidays", metadata,
    Column("date", String, primary_key=True),
    Column("countries", String, nullable=False),
    Column("name", String, nullable=False),
)

extra_days = Table(
    "extra_days", metadata,
    Column("date", String, primary_key=True),
    Column("note", String, nullable=False),
)

settings = Table(
    "settings", metadata,
    Column("key", String, primary_key=True),
    Column("value", JSON, nullable=False),
)

sync_runs = Table(
    "sync_runs", metadata,
    Column("id", Integer, primary_key=True, autoincrement=True),
    Column("started_at", DateTime(timezone=True), nullable=False),
    Column("finished_at", DateTime(timezone=True)),
    Column("full", Boolean, nullable=False),
    Column("status", String, nullable=False),
    Column("issues", Integer, nullable=False, default=0),
    Column("error", Text),
)
