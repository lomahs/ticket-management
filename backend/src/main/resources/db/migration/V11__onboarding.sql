-- The Task (a Jira key) each estimation row's sub-tasks go under; replaces the Task chosen per subphase.
ALTER TABLE estimation_rows ADD COLUMN task_key VARCHAR;
DROP TABLE plan_task_targets;
-- An anken being onboarded: the furthest step reached (1-6), the anken prefix of its Task names, finished_at once done.
CREATE TABLE onboarding (
    epic_key     VARCHAR PRIMARY KEY,
    anken_prefix VARCHAR NOT NULL DEFAULT '',
    step         INTEGER NOT NULL,
    started_at   VARCHAR NOT NULL,
    started_by   VARCHAR NOT NULL,
    saved_at     VARCHAR NOT NULL,
    saved_by     VARCHAR NOT NULL,
    finished_at  VARCHAR
);
