CREATE TABLE locator_identities (
    id uuid PRIMARY KEY,
    role text NOT NULL CHECK (role IN ('project', 'admin')),
    token_hash char(64) NOT NULL UNIQUE,
    created_at timestamptz NOT NULL DEFAULT now(),
    revoked_at timestamptz
);

CREATE TABLE locator_projects (
    id uuid PRIMARY KEY,
    project varchar(64) NOT NULL UNIQUE CHECK (project ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    slug varchar(64) NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9][a-z0-9_-]{0,63}$'),
    identity_id uuid NOT NULL UNIQUE REFERENCES locator_identities(id),
    status text NOT NULL CHECK (status IN ('pending', 'approved', 'rejected')),
    created_at timestamptz NOT NULL DEFAULT now(),
    reviewed_at timestamptz,
    reviewed_by uuid REFERENCES locator_identities(id),
    CHECK ((status = 'pending' AND reviewed_at IS NULL AND reviewed_by IS NULL)
        OR (status <> 'pending' AND reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL))
);
CREATE INDEX locator_projects_status_created ON locator_projects(status, created_at, id);
