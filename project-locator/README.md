# Project locator

Service registry for `itzephir`, backed by PostgreSQL. The `project-locator`
Gradle module owns the model, identities, migrations and HTTP API; `website`
mounts it when locator configuration is present.

`/dev` is the development-projects section of the website. The hostname selects
the environment: production is `itzephir.com/dev/project/SLUG/`, stage is
`dev.itzephir.com/dev/project/SLUG/`. Underscores in slugs are supported.

## Registration and identities

Submit a service request:

```http
POST /api/project-locator/v1/projects
Content-Type: application/json

{"project":"minor-website","slug":"minor_web"}
```

The response is `201 Created`, with a persistent project ID, project identity ID,
`pending` status and a service bearer token. Save the token from this response;
it is never available through a read or list endpoint. A pending service can
list and edit its own project and rotate its credential, but cannot approve itself,
access other projects, reserve additional projects or inspect other identities.

Each name and slug is globally unique within that environment's database.
Registration and identity creation are one transaction: a duplicate name/slug
returns `409` without leaving an orphan identity. JSON cannot supply status,
identity role, review author or review time. Names/slugs use 1-64 lowercase ASCII
letters, digits, `_` or `-`, starting with a letter/digit.

The `itzephir` admin identity is bootstrapped from `LOCATOR_ADMIN_TOKEN`. Supplying
its bearer token on the same registration endpoint creates an `approved` project
immediately and records the admin as reviewer. Without admin credentials a
registration always requires approval. An invalid Authorization header returns
`401`, rather than silently creating an anonymous request.

```http
POST /api/project-locator/v1/projects/PROJECT_UUID/approve
Authorization: Bearer ADMIN_TOKEN
```

Approval or rejection records `reviewedBy` and `reviewedAt`. Repeating the same
decision is idempotent; changing a completed decision returns `409`.

## API

All routes below start with `/api/project-locator/v1`. Responses are JSON and
carry `Cache-Control: no-store`. Bearer tokens must be sent over HTTPS.

| Method and path | Who | Result |
| --- | --- | --- |
| `POST /projects` | Public | Pending request and one-time project token |
| `POST /projects` | Admin | Approved project and one-time project token |
| `GET /identity` | Project/admin | Current identity ID, role and project ID |
| `GET /projects?status=pending&limit=50&offset=0` | Owner/admin | Own projects or all projects for admin; `limit` is 1-100 |
| `GET /projects/{id}` | Owner/admin | Project state, without credentials |
| `PATCH /projects/{id}` | Owner/admin | Update `project`, `slug` with the current `revision` |
| `POST /projects/{id}/approve` | Admin | Approve pending request |
| `POST /projects/{id}/reject` | Admin | Reject pending request |
| `POST /projects/{id}/identity/rotate` | Owner/admin | New one-time token; old token stops working |
| `DELETE /projects/{id}/identity` | Admin | Revoke token and remove project from resolution |
| `GET /resolve/{slug}` | Public | Approved project and its production/stage URLs |

Unknown, pending, rejected or revoked projects resolve to `404`. Cross-project
reads also return `404`. Revoking a project credential does not release its name
or slug. An admin can restore the identity by rotating it; a revoked service
credential cannot restore itself. Admin creation/review does not disclose any
other project's service credential.

The resolver is the registry boundary for routing integration. This change does
not publish nginx snippets, start service containers or proxy arbitrary upstream
URLs. An approved registration identifies a project; actual service deployment
continues through the project's CI/CD. Adding registry-based gateway lookup can
use `/resolve/{slug}` without giving the gateway an admin credential.

## Website and management panel

- `/dev` is a public landing page with approved, non-revoked projects only.
- `/dev/panel` is the web management panel. Owners see only assigned projects;
  administrators see all projects, requests and users.
- `/dev/project/SLUG/` is the existing project namespace.

Paths are identical on production and stage; the hostname selects the stand.
The pages use server-rendered HTML/CSS and work without JavaScript.

The web panel supports **GitHub OAuth and username/password**. Web users are
separate from service API credentials. Administrators create password accounts
under **Users** and assign one or more owners in each project's settings. First
GitHub sign-in creates an owner account with no project access; an administrator
must assign projects. Logged-in users can link GitHub and set/change a password
under **Account**, allowing both methods for the same account. Linking never
merges accounts by username/email or grants admin rights. Admin GitHub access
is explicitly bound to its immutable numeric GitHub ID.

Passwords use PBKDF2-HMAC-SHA256 with 600,000 iterations and random salts. Passwords
must contain 12-256 characters. Password login is limited to 10 failed attempts
per username per 15 minutes. Authentication returns an 8-hour HS256 JWT in an
HttpOnly, Secure, SameSite=Strict, host-only cookie scoped to `/dev/panel`.
JWT issuer, audience, kind, signature and expiry are checked. Authorization reads
the current role and ownership from PostgreSQL rather than trusting role claims.

JWTs contain a random session ID whose hash is stored with the user's credential
version and expiry. Logout deletes that session; password changes invalidate old
sessions. This prevents continued use of an otherwise unexpired JWT after logout.
Raw JWTs and service tokens are never stored in browser storage or the database.
Panel cookies do not authenticate service API routes. Mutating forms, including
login, require CSRF tokens bound to browser cookies. GitHub authorization uses
PKCE and one-time, expiring state records; provider access tokens are discarded
after the server fetches the authenticated user's ID.

Owners can edit project names/slugs. A changed owner submission returns to
`pending` and clears its review, including edits of rejected requests; admin edits
preserve approval. Identity and ownership cannot be supplied in metadata updates.
A `revision` guards against overwriting concurrent edits or approving a request
changed after the admin form was rendered. Ownership changes are admin-only and
increment the revision. The public landing immediately hides pending or revoked
projects.

## PostgreSQL and secrets

`locator_identities` stores stable UUIDs, roles, SHA-256 token hashes and revocation
timestamps. Project tokens contain 256 random bits; raw tokens are returned once
and never persisted. `locator_projects` holds names, slugs, review state and
foreign keys to project/reviewer identities. Credentials are independent of slugs.
Human owners have `owner` identities with no service API credential; their
password/GitHub credentials and project memberships are stored separately.

Migrations are versioned under `src/main/resources/db/locator/`. Startup applies
them inside a transaction protected by a PostgreSQL advisory lock. A checksum
detects edits to a previously installed migration. No tables are dropped during
startup. Concurrent registrations are serialized by database unique constraints,
and review decisions use a row lock. JDBC runs on a bounded IO dispatcher with
connection, socket and statement timeouts.

Use a dedicated PostgreSQL database/role per environment. The role needs DDL
permissions in its own schema for startup migrations, without superuser access.

The first four variables enable the registry. Web sign-in additionally needs
the JWT secret and initial admin password. GitHub settings are optional as a
group; configure them to enable the second sign-in method:

```dotenv
LOCATOR_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/itzephir_locator_dev
LOCATOR_DATABASE_USER=itzephir_locator_dev
LOCATOR_DATABASE_PASSWORD=GENERATED_DATABASE_SECRET
LOCATOR_ADMIN_TOKEN=GENERATED_RANDOM_SERVICE_ADMIN_SECRET

# Enable human web sign-in; use a separate random JWT signing secret.
LOCATOR_JWT_SECRET=GENERATED_RANDOM_SECRET_AT_LEAST_32_BYTES
LOCATOR_ADMIN_USERNAME=itzephir
LOCATOR_ADMIN_PASSWORD=GENERATED_INITIAL_PASSWORD_AT_LEAST_12_CHARACTERS

# Enable GitHub sign-in with an OAuth app registered for this stand.
LOCATOR_GITHUB_CLIENT_ID=OAUTH_APP_CLIENT_ID
LOCATOR_GITHUB_CLIENT_SECRET=OAUTH_APP_CLIENT_SECRET
LOCATOR_GITHUB_CALLBACK_URL=https://dev.itzephir.com/dev/panel/github/callback
LOCATOR_ADMIN_GITHUB_ID=IMMUTABLE_NUMERIC_GITHUB_USER_ID
```

The systemd unit optionally loads `/etc/itzephir/locator-dev.env` or
`locator-prod.env`. Provision those files through the committed infrastructure
pipeline, owned by root with group `deploy` and mode `0640`. Never commit actual
credentials. PostgreSQL and the two admin tokens must remain separate between
stage and production. Changing the admin token and restarting rotates its
credential while preserving the admin identity UUID and review history.
The web admin username/password are bootstrap-only and never overwrite changes
made through the Account page. Use separate OAuth apps/callbacks and JWT secrets
for stage and production. The production callback is
`https://itzephir.com/dev/panel/github/callback`; stage uses the dev hostname.
Neither `repo` nor email scope is requested for GitHub login. An API-only registry
can omit all web-auth variables: the public landing still works, and the panel
shows an unavailable page. Partial web-auth configuration fails startup.

No locator variables means the optional API is disabled and the portfolio runs
normally. Partial or invalid configuration stops startup. A configured database
that cannot be reached also fails startup, allowing the existing deployment
health check to roll back; it never silently falls back to an in-memory registry.

## Validation

```sh
LOCATOR_TEST_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/locator_test \
LOCATOR_TEST_DATABASE_USER=locator \
LOCATOR_TEST_DATABASE_PASSWORD=local-test-password \
LOCATOR_REQUIRE_DATABASE_TESTS=true \
./gradlew check :website:installDist
```

Tests create isolated temporary schemas and drop only those schemas afterwards.
Use a test database, not a deployed registry. Tests cover the real HTTP API and
PostgreSQL transactions, including concurrency, persistence, access isolation,
approval, privilege injection, JWT verification/revocation, GitHub OAuth state/PKCE,
passwords, ownership and service token rotation. Browser tests run the packaged
application against an isolated database schema and cover login/logout, owner/admin
workflows, public visibility, mobile layouts and operation without JavaScript. CI starts PostgreSQL with a random password generated and masked per run,
binds it to loopback, removes the test container/volume afterwards, and requires those tests; a local `check` without a test database skips
the PostgreSQL suite while still running configuration and portfolio tests.

References: [Ktor bearer authentication](https://ktor.io/docs/server-bearer-auth.html),
[pgJDBC configuration](https://jdbc.postgresql.org/documentation/use/),
[PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html),
[GitHub OAuth web flow](https://docs.github.com/en/apps/oauth-apps/building-oauth-apps/authorizing-oauth-apps),
[password storage](https://cheatsheetseries.owasp.org/cheatsheets/Password_Storage_Cheat_Sheet.html).
