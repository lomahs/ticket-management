-- The estimation of an anken, one row per function or ticket; ba/bal/jm are the detail-plan draft; pushed_at once in Jira.
CREATE TABLE estimation_rows (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    epic_key      VARCHAR NOT NULL,
    position      INTEGER NOT NULL,
    phase         VARCHAR NOT NULL,
    subphase      VARCHAR NOT NULL,
    function_name VARCHAR NOT NULL,
    steps         INTEGER,
    effort_h      REAL    NOT NULL,
    ba_id         VARCHAR,
    bal_id        VARCHAR,
    jm_id         VARCHAR,
    pushed_at     VARCHAR
);
CREATE INDEX estimation_rows_epic ON estimation_rows (epic_key, position);
-- Where the sub-tasks of a subphase go: an existing Task, or a new Task with its name and assignee.
CREATE TABLE plan_task_targets (
    epic_key        VARCHAR NOT NULL,
    phase           VARCHAR NOT NULL,
    subphase        VARCHAR NOT NULL,
    existing_key    VARCHAR,
    new_name        VARCHAR,
    new_assignee_id VARCHAR,
    PRIMARY KEY (epic_key, phase, subphase)
);
-- Each save of a master schedule; the first of an epic is its baseline.
CREATE TABLE master_schedule_versions (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    epic_key VARCHAR NOT NULL,
    saved_at VARCHAR NOT NULL,
    saved_by VARCHAR NOT NULL,
    reason   TEXT    NOT NULL
);
CREATE TABLE master_schedule_phases (
    version_id     INTEGER NOT NULL REFERENCES master_schedule_versions (id) ON DELETE CASCADE,
    position       INTEGER NOT NULL,
    phase          VARCHAR NOT NULL,
    member_ids     TEXT    NOT NULL, -- JSON array
    start_date     VARCHAR NOT NULL,
    end_date       VARCHAR NOT NULL,
    plan_md        INTEGER NOT NULL,
    total_effort_h REAL    NOT NULL,
    PRIMARY KEY (version_id, phase)
);
