ALTER TABLE locator_projects ADD COLUMN revision integer NOT NULL DEFAULT 0 CHECK (revision >= 0);
ALTER TABLE locator_identities DROP CONSTRAINT locator_identities_role_check;
ALTER TABLE locator_identities ADD CONSTRAINT locator_identities_role_check CHECK (role IN ('project', 'owner', 'admin'));
ALTER TABLE locator_identities ALTER COLUMN token_hash DROP NOT NULL;
ALTER TABLE locator_identities ADD CONSTRAINT locator_identities_credentials CHECK (
    (role = 'owner' AND token_hash IS NULL) OR (role IN ('project', 'admin') AND token_hash IS NOT NULL)
);

CREATE TABLE locator_users (
    id uuid PRIMARY KEY REFERENCES locator_identities(id),
    username varchar(64) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9_.-]{2,63}$'),
    password_hash text,
    github_id bigint UNIQUE CHECK (github_id > 0),
    github_login text,
    role text NOT NULL CHECK (role IN ('owner', 'admin')),
    credential_version integer NOT NULL DEFAULT 0,
    CHECK (password_hash IS NOT NULL OR github_id IS NOT NULL)
);
CREATE TABLE locator_project_owners (
    project_id uuid NOT NULL REFERENCES locator_projects(id),
    user_id uuid NOT NULL REFERENCES locator_users(id),
    PRIMARY KEY (project_id, user_id)
);
CREATE INDEX locator_project_owners_user ON locator_project_owners(user_id);
CREATE TABLE locator_panel_sessions (
    token_hash char(64) PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES locator_users(id),
    credential_version integer NOT NULL,
    expires_at timestamptz NOT NULL DEFAULT now() + interval '8 hours'
);
CREATE INDEX locator_panel_sessions_expiry ON locator_panel_sessions(expires_at);
CREATE TABLE locator_oauth_states (
    state_hash char(64) PRIMARY KEY,
    verifier text NOT NULL,
    link_user_id uuid REFERENCES locator_users(id),
    expires_at timestamptz NOT NULL DEFAULT now() + interval '10 minutes'
);
CREATE TABLE locator_login_attempts (
    name_hash char(64) PRIMARY KEY,
    failures integer NOT NULL DEFAULT 0,
    window_started_at timestamptz NOT NULL DEFAULT now()
);
