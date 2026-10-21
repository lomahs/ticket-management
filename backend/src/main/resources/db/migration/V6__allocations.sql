-- Hours per day a member gives each anken, from an effective date (the latest row not after a day applies).
CREATE TABLE allocations (
    member_id      VARCHAR NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    epic_key       VARCHAR NOT NULL,
    effective_from VARCHAR NOT NULL,
    hours_per_day  REAL    NOT NULL,
    set_by         VARCHAR NOT NULL,
    set_at         VARCHAR NOT NULL,
    PRIMARY KEY (member_id, epic_key, effective_from)
);
