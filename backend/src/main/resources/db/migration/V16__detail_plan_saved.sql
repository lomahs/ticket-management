-- The detail plan Auto allocate saved: each step's placements (one per person) and its hours per person and day. The Detail
-- plan shows it until the next Auto allocate; adjustments no longer schedule again.
CREATE TABLE estimation_plan_placed (
    row_id        INTEGER NOT NULL REFERENCES estimation_rows (id) ON DELETE CASCADE,
    ref           VARCHAR NOT NULL,
    phase         VARCHAR NOT NULL,
    step          VARCHAR NOT NULL,
    member_id     VARCHAR NOT NULL,
    hours         REAL    NOT NULL,
    planned_start VARCHAR NOT NULL,
    planned_end   VARCHAR NOT NULL,
    counted       INTEGER NOT NULL
);
CREATE INDEX estimation_plan_placed_row ON estimation_plan_placed (row_id);

CREATE TABLE estimation_plan_parts (
    row_id    INTEGER NOT NULL REFERENCES estimation_rows (id) ON DELETE CASCADE,
    ref       VARCHAR NOT NULL,
    member_id VARCHAR NOT NULL,
    day       VARCHAR NOT NULL,
    hours     REAL    NOT NULL
);
CREATE INDEX estimation_plan_parts_row ON estimation_plan_parts (row_id);

-- Anken planned before the plan was saved: the first open computes and saves it once.
CREATE TABLE estimation_plan_pending (
    epic_key VARCHAR PRIMARY KEY
);
INSERT INTO estimation_plan_pending (epic_key) SELECT DISTINCT epic_key FROM estimation_rows WHERE pushed_at IS NULL;
