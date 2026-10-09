"""Alembic environment: migrations run on the connection Database.migrate() passes in."""

from alembic import context

from tracker.adapters.persistence.tables import metadata

connection = context.config.attributes["connection"]
context.configure(connection=connection, target_metadata=metadata, render_as_batch=True)
with context.begin_transaction():
    context.run_migrations()
