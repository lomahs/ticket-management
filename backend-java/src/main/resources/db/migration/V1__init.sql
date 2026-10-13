-- Team Tracker schema (SQLite). Flyway applies the migrations in this folder in version order.
CREATE TABLE members (
    id              VARCHAR PRIMARY KEY,
    jira_account_id VARCHAR UNIQUE,
    name            VARCHAR NOT NULL,
    full_name       VARCHAR NOT NULL,
    email           VARCHAR NOT NULL,
    role            VARCHAR,
    country         VARCHAR NOT NULL,
    color           VARCHAR NOT NULL,
    permission      VARCHAR NOT NULL,
    external        BOOLEAN NOT NULL
);

CREATE TABLE anken (
    "key"    VARCHAR PRIMARY KEY,
    project  VARCHAR NOT NULL,
    title    VARCHAR NOT NULL,
    due_date VARCHAR
);

CREATE TABLE issues (
    "key"         VARCHAR PRIMARY KEY,
    project       VARCHAR NOT NULL,
    epic_key      VARCHAR NOT NULL,
    parent_key    VARCHAR,
    kind          VARCHAR NOT NULL,
    summary       TEXT    NOT NULL,
    phase         VARCHAR NOT NULL,
    status        VARCHAR NOT NULL,
    jira_status   VARCHAR NOT NULL,
    assignee_id   VARCHAR,
    planned_start VARCHAR,
    planned_end   VARCHAR,
    estimate_h    REAL    NOT NULL,
    created       VARCHAR NOT NULL,
    function_id   VARCHAR,
    function_name VARCHAR,
    step          VARCHAR
);
CREATE INDEX ix_issues_epic_key ON issues (epic_key);

CREATE TABLE worklogs (
    id        INTEGER PRIMARY KEY,
    issue_key VARCHAR NOT NULL REFERENCES issues ("key") ON DELETE CASCADE,
    date      VARCHAR NOT NULL,
    member_id VARCHAR,
    hours     REAL    NOT NULL,
    note      TEXT    NOT NULL
);
CREATE INDEX ix_worklogs_issue ON worklogs (issue_key);
CREATE INDEX ix_worklogs_member_date ON worklogs (member_id, date);

CREATE TABLE issue_events (
    id         INTEGER PRIMARY KEY,
    issue_key  VARCHAR NOT NULL REFERENCES issues ("key") ON DELETE CASCADE,
    at         VARCHAR NOT NULL,
    field      VARCHAR NOT NULL,
    from_value VARCHAR,
    to_value   VARCHAR
);
CREATE INDEX ix_issue_events_issue_key ON issue_events (issue_key);

CREATE TABLE leaves (
    id        INTEGER PRIMARY KEY,
    member_id VARCHAR NOT NULL,
    date      VARCHAR NOT NULL,
    type      VARCHAR NOT NULL,
    note      TEXT    NOT NULL,
    CONSTRAINT uq_leaves_member_date UNIQUE (member_id, date)
);

CREATE TABLE holidays (
    date      VARCHAR PRIMARY KEY,
    countries VARCHAR NOT NULL,
    name      VARCHAR NOT NULL
);

CREATE TABLE extra_days (
    date VARCHAR PRIMARY KEY,
    note VARCHAR NOT NULL
);

-- The settings an admin configures, as one JSON document.
CREATE TABLE settings (
    id    INTEGER PRIMARY KEY CHECK (id = 1),
    value TEXT NOT NULL
);

CREATE TABLE sync_runs (
    id          INTEGER PRIMARY KEY,
    started_at  VARCHAR NOT NULL,
    finished_at VARCHAR,
    "full"      BOOLEAN NOT NULL,
    status      VARCHAR NOT NULL,
    issues      INTEGER NOT NULL,
    error       TEXT
);
