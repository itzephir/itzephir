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
inspect its own request and rotate its credential, but cannot approve itself,
list other projects, reserve additional projects or inspect other identities.

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
| `GET /projects?status=pending&limit=50&offset=0` | Admin | Requests; `limit` is 1-100 |
| `GET /projects/{id}` | Owner/admin | Project state, without credentials |
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

## PostgreSQL and secrets

`locator_identities` stores stable UUIDs, roles, SHA-256 token hashes and revocation
timestamps. Project tokens contain 256 random bits; raw tokens are returned once
and never persisted. `locator_projects` holds names, slugs, review state and
foreign keys to project/reviewer identities. Credentials are independent of slugs.

Migrations are versioned under `src/main/resources/db/locator/`. Startup applies
them inside a transaction protected by a PostgreSQL advisory lock. A checksum
detects edits to a previously installed migration. No tables are dropped during
startup. Concurrent registrations are serialized by database unique constraints,
and review decisions use a row lock. JDBC runs on a bounded IO dispatcher with
connection, socket and statement timeouts.

Use a dedicated PostgreSQL database/role per environment. The role needs DDL
permissions in its own schema for startup migrations, without superuser access.

Configure all four variables:

```dotenv
LOCATOR_DATABASE_URL=jdbc:postgresql://127.0.0.1:5432/itzephir_locator_dev
LOCATOR_DATABASE_USER=itzephir_locator_dev
LOCATOR_DATABASE_PASSWORD=GENERATED_DATABASE_SECRET
LOCATOR_ADMIN_TOKEN=GENERATED_RANDOM_SECRET_AT_LEAST_32_CHARACTERS
```

The systemd unit optionally loads `/etc/itzephir/locator-dev.env` or
`locator-prod.env`. Provision those files through the committed infrastructure
pipeline, owned by root with group `deploy` and mode `0640`. Never commit actual
credentials. PostgreSQL and the two admin tokens must remain separate between
stage and production. Changing the admin token and restarting rotates its
credential while preserving the admin identity UUID and review history.

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
approval, privilege injection, token rotation and revocation. CI always starts
PostgreSQL and requires those tests; a local `check` without a test database skips
the PostgreSQL suite while still running configuration and portfolio tests.

References: [Ktor bearer authentication](https://ktor.io/docs/server-bearer-auth.html),
[pgJDBC configuration](https://jdbc.postgresql.org/documentation/use/),
[PostgreSQL locking](https://www.postgresql.org/docs/current/explicit-locking.html).
