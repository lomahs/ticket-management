-- Who Auto allocate put in each slot of an estimation row: a slot holding someone else (or set again in the detail plan) was
-- picked by hand. "Clear, keep my input" empties only the slots still holding Auto allocate's pick.
CREATE TABLE estimation_auto_picks (
    row_id INTEGER PRIMARY KEY REFERENCES estimation_rows (id) ON DELETE CASCADE,
    ba_id  VARCHAR,
    bal_id VARCHAR,
    jm_id  VARCHAR
);
