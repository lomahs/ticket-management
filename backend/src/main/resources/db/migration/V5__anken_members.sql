-- Who works on which anken, and per-user overrides of "sees every anken".
CREATE TABLE anken_members (
    epic_key  VARCHAR NOT NULL,
    member_id VARCHAR NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    added_at  VARCHAR NOT NULL,
    added_by  VARCHAR NOT NULL,
    PRIMARY KEY (epic_key, member_id)
);
CREATE INDEX ix_anken_members_member ON anken_members (member_id);
CREATE TABLE member_scope (
    member_id   VARCHAR PRIMARY KEY REFERENCES members (id) ON DELETE CASCADE,
    anken_scope VARCHAR NOT NULL
);
-- One-time data steps already done (e.g. "anken_members_seed").
CREATE TABLE seeds (
    name    VARCHAR PRIMARY KEY,
    done_at VARCHAR NOT NULL
);
