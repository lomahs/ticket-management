-- The Detail plan's hours per step and day of the rows not yet pushed; auto = filled by Auto allocate.
CREATE TABLE plan_row_cells (
    row_id INTEGER NOT NULL REFERENCES estimation_rows (id) ON DELETE CASCADE,
    ref    VARCHAR NOT NULL,
    day    VARCHAR NOT NULL,
    hours  REAL    NOT NULL,
    auto   INTEGER NOT NULL,
    PRIMARY KEY (ref, day)
);
CREATE INDEX plan_row_cells_row ON plan_row_cells (row_id);

-- A sub-task's hours per day from today on, changed on the Detail plan and not pushed yet.
CREATE TABLE plan_jira_cells (
    epic_key  VARCHAR NOT NULL,
    issue_key VARCHAR NOT NULL,
    day       VARCHAR NOT NULL,
    hours     REAL    NOT NULL,
    auto      INTEGER NOT NULL,
    PRIMARY KEY (issue_key, day)
);
-- A sub-task's Remaining and assignee changed on the Detail plan and not pushed yet; null keeps Jira's.
CREATE TABLE plan_jira_edits (
    issue_key   VARCHAR PRIMARY KEY,
    epic_key    VARCHAR NOT NULL,
    remaining_h REAL,
    assignee_id VARCHAR
);
-- Snapshots of an anken's detail plan, saved by hand (Save version).
CREATE TABLE plan_versions (
    id       INTEGER PRIMARY KEY AUTOINCREMENT,
    epic_key VARCHAR NOT NULL,
    saved_at VARCHAR NOT NULL,
    saved_by VARCHAR NOT NULL,
    body     TEXT    NOT NULL
);

-- The plan Auto allocate saved becomes Auto allocate's cells: all people of a step together.
INSERT INTO plan_row_cells (row_id, ref, day, hours, auto)
SELECT p.row_id, p.ref, p.day, ROUND(SUM(p.hours), 2), 1 FROM estimation_plan_parts p
JOIN estimation_rows r ON r.id = p.row_id WHERE r.pushed_at IS NULL GROUP BY p.row_id, p.ref, p.day;
-- a step that counts for nobody (JM Review) had no parts: its hours go on its first day
INSERT OR IGNORE INTO plan_row_cells (row_id, ref, day, hours, auto)
SELECT p.row_id, p.ref, MIN(p.planned_start), MAX(p.hours), 1 FROM estimation_plan_placed p
JOIN estimation_rows r ON r.id = p.row_id WHERE r.pushed_at IS NULL AND p.counted = 0 GROUP BY p.row_id, p.ref;
DELETE FROM estimation_plan_pending;
