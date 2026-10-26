-- The Daily Overview of an anken as it was on a day: one per anken per day, a new capture replaces it (payload: JSON).
CREATE TABLE daily_snapshots (
    epic_key    VARCHAR NOT NULL,
    date        VARCHAR NOT NULL,
    captured_at VARCHAR NOT NULL,
    captured_by VARCHAR NOT NULL,
    payload     TEXT    NOT NULL,
    PRIMARY KEY (epic_key, date)
);
-- A note per ticket (QnA, Bug, WorkRequest) kept in the tracker, not in Jira.
CREATE TABLE ticket_notes (
    issue_key  VARCHAR PRIMARY KEY,
    note       TEXT    NOT NULL,
    updated_by VARCHAR NOT NULL,
    updated_at VARCHAR NOT NULL
);
