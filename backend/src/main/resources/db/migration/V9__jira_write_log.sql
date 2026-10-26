-- Every request the app sends to change Jira: who, from where, the call and Jira's answer (0: not sent or unreachable).
CREATE TABLE jira_write_log (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    at          VARCHAR NOT NULL,
    actor_email VARCHAR NOT NULL,
    actor_name  VARCHAR NOT NULL,
    source      VARCHAR NOT NULL,
    issue_key   VARCHAR NOT NULL,
    method      VARCHAR NOT NULL,
    path        VARCHAR NOT NULL,
    body        TEXT,
    status      INTEGER NOT NULL,
    error       TEXT,
    duration_ms INTEGER NOT NULL
);
CREATE INDEX jira_write_log_at ON jira_write_log (at);
