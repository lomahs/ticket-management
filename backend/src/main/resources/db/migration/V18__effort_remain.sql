-- The hours Detail Planning put on each day of a pushed sub-task, all its people together: its plan remain counts down by them.
CREATE TABLE issue_plan_days (
    issue_key VARCHAR NOT NULL,
    day       VARCHAR NOT NULL,
    hours     REAL    NOT NULL,
    PRIMARY KEY (issue_key, day)
);

-- Each sub-task's remaining estimate in hours, as the Daily Overview capture of its anken saw it on that day.
CREATE TABLE issue_remaining_days (
    issue_key   VARCHAR NOT NULL,
    day         VARCHAR NOT NULL,
    remaining_h REAL    NOT NULL,
    PRIMARY KEY (issue_key, day)
);
