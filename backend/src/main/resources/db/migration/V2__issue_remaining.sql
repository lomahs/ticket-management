-- Remaining Estimate (Jira "timeestimate") in hours; until the next sync it equals the original estimate.
ALTER TABLE issues ADD COLUMN remaining_h REAL NOT NULL DEFAULT 0;
UPDATE issues SET remaining_h = estimate_h;
