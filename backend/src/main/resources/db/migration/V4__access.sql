-- Who may sign in, per-user permission overrides, and access requests (see docs/superpowers/specs/2026-10-21-anken-access-design.md).
CREATE TABLE member_access (
    member_id VARCHAR PRIMARY KEY REFERENCES members (id) ON DELETE CASCADE,
    status    VARCHAR NOT NULL
);
CREATE TABLE member_permissions (
    member_id VARCHAR NOT NULL REFERENCES members (id) ON DELETE CASCADE,
    feature   VARCHAR NOT NULL,
    level     VARCHAR NOT NULL,
    PRIMARY KEY (member_id, feature)
);
CREATE TABLE access_requests (
    id           INTEGER PRIMARY KEY,
    email        VARCHAR NOT NULL,
    name         VARCHAR NOT NULL,
    note         TEXT    NOT NULL,
    requested_at VARCHAR NOT NULL,
    status       VARCHAR NOT NULL,
    decided_by   VARCHAR,
    decided_at   VARCHAR,
    member_id    VARCHAR
);
CREATE INDEX ix_access_requests_email ON access_requests (email);
