-- What the detail plan changed on one step of an estimation row (step '' for a row without steps): its effort, its dates
-- (set together) and whether it was removed from the plan.
CREATE TABLE estimation_steps (
    row_id        INTEGER NOT NULL REFERENCES estimation_rows (id) ON DELETE CASCADE,
    step          VARCHAR NOT NULL,
    effort_h      REAL,
    planned_start VARCHAR,
    planned_end   VARCHAR,
    removed       INTEGER NOT NULL DEFAULT 0,
    PRIMARY KEY (row_id, step)
);
