-- Team Tracker schema: the same tables as the Alembic revision 0001 of the Python backend, so either backend can open
-- the other's database. Statements are separated by ";" at line end and must be idempotent.
CREATE TABLE IF NOT EXISTS alembic_version (
    version_num VARCHAR(32) NOT NULL,
    CONSTRAINT alembic_version_pkc PRIMARY KEY (version_num)
);
CREATE TABLE IF NOT EXISTS anken (
    "key" VARCHAR NOT NULL,
    project VARCHAR NOT NULL,
    title VARCHAR NOT NULL,
    due_date VARCHAR,
    PRIMARY KEY ("key")
);
CREATE TABLE IF NOT EXISTS extra_days (
    date VARCHAR NOT NULL,
    note VARCHAR NOT NULL,
    PRIMARY KEY (date)
);
CREATE TABLE IF NOT EXISTS holidays (
    date VARCHAR NOT NULL,
    countries VARCHAR NOT NULL,
    name VARCHAR NOT NULL,
    PRIMARY KEY (date)
);
CREATE TABLE IF NOT EXISTS issues (
    "key" VARCHAR NOT NULL,
    project VARCHAR NOT NULL,
    epic_key VARCHAR NOT NULL,
    parent_key VARCHAR,
    kind VARCHAR NOT NULL,
    summary TEXT NOT NULL,
    phase VARCHAR NOT NULL,
    status VARCHAR NOT NULL,
    jira_status VARCHAR NOT NULL,
    assignee_id VARCHAR,
    planned_start VARCHAR,
    planned_end VARCHAR,
    estimate_h FLOAT NOT NULL,
    created VARCHAR NOT NULL,
    function_id VARCHAR,
    function_name VARCHAR,
    step VARCHAR,
    PRIMARY KEY ("key")
);
CREATE INDEX IF NOT EXISTS ix_issues_epic_key ON issues (epic_key);
CREATE TABLE IF NOT EXISTS leaves (
    id INTEGER NOT NULL,
    member_id VARCHAR NOT NULL,
    date VARCHAR NOT NULL,
    type VARCHAR NOT NULL,
    note TEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_leaves_member_date UNIQUE (member_id, date)
);
CREATE TABLE IF NOT EXISTS members (
    id VARCHAR NOT NULL,
    jira_account_id VARCHAR,
    name VARCHAR NOT NULL,
    full_name VARCHAR NOT NULL,
    email VARCHAR NOT NULL,
    role VARCHAR,
    country VARCHAR NOT NULL,
    color VARCHAR NOT NULL,
    permission VARCHAR NOT NULL,
    external BOOLEAN NOT NULL,
    PRIMARY KEY (id),
    UNIQUE (jira_account_id)
);
CREATE TABLE IF NOT EXISTS settings (
    "key" VARCHAR NOT NULL,
    value JSON NOT NULL,
    PRIMARY KEY ("key")
);
CREATE TABLE IF NOT EXISTS sync_runs (
    id INTEGER NOT NULL,
    started_at DATETIME NOT NULL,
    finished_at DATETIME,
    "full" BOOLEAN NOT NULL,
    status VARCHAR NOT NULL,
    issues INTEGER NOT NULL,
    error TEXT,
    PRIMARY KEY (id)
);
CREATE TABLE IF NOT EXISTS issue_events (
    id INTEGER NOT NULL,
    issue_key VARCHAR NOT NULL,
    at VARCHAR NOT NULL,
    field VARCHAR NOT NULL,
    from_value VARCHAR,
    to_value VARCHAR,
    PRIMARY KEY (id),
    FOREIGN KEY(issue_key) REFERENCES issues ("key") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_issue_events_issue_key ON issue_events (issue_key);
CREATE TABLE IF NOT EXISTS worklogs (
    id INTEGER NOT NULL,
    issue_key VARCHAR NOT NULL,
    date VARCHAR NOT NULL,
    member_id VARCHAR,
    hours FLOAT NOT NULL,
    note TEXT NOT NULL,
    PRIMARY KEY (id),
    FOREIGN KEY(issue_key) REFERENCES issues ("key") ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS ix_worklogs_issue ON worklogs (issue_key);
CREATE INDEX IF NOT EXISTS ix_worklogs_member_date ON worklogs (member_id, date);
INSERT INTO alembic_version (version_num) SELECT '0001' WHERE NOT EXISTS (SELECT 1 FROM alembic_version);
