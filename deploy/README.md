# Deployment

The website runs as two independent Ktor/JVM services on the existing VM.

| Trigger | Release path | Service / port | Hostname |
| --- | --- | --- | --- |
| Push/merge into `main` | `/srv/itzephir/prod/current` | `itzephir@prod`, `127.0.0.1:18080` | `itzephir.com` |
| Same-repository PR targeting `main` | `/srv/itzephir/dev/current` | `itzephir@dev`, `127.0.0.1:18081` | `dev.itzephir.com` |

Develop on a separate feature branch and open a PR targeting `main`. CI has
three jobs: build and verification, deployment, and the PR preview comment.
The build job checks Kotlin, nginx publishing/rollback, release activation,
and the packaged application in Chrome before uploading the release artifact.
It checks all six projects, contacts, webring, mobile overflow, real HTMX swaps,
theme persistence, and JavaScript-off navigation; screenshots are also uploaded.

GitHub Actions deploys the PR merge commit to the shared development preview.
Merging triggers production. The last completed PR deployment wins; deployment
jobs are serialized per environment and a running deployment is never cancelled.

Before starting a candidate, `activate-release.sh` records its validated target
in `deploy/environment` inside that release. The website uses this marker to
install the staging HTML badge only for `dev`; `prod` bypasses the interceptor
regardless of query parameters or request host. The marker travels with the
release during rollback and needs no manual service configuration. Local badge
testing and query behavior are documented in `../website/README.md`.
Closing a PR leaves the last preview available.

Fork/Dependabot PRs and manual runs are build/test only. The workflow uses
`pull_request`, not `pull_request_target`. Direct pushes to the legacy `dev`
branch do not deploy. Deployment secrets stay unchanged:
`DEPLOY_HOST`, `DEPLOY_USER` (the existing `deploy` account), `DEPLOY_SSH_KEY`,
and `DEPLOY_KNOWN_HOSTS`.

## One-time VM setup

The existing VM needs nginx, Python 3, sudo, and the Let's Encrypt certificate.
Copy `deploy/` there and run `bash deploy/setup-server.sh` as root. It installs
Java 21 if needed, the systemd service template, loopback port/cookie settings,
and the root-owned `/usr/local/sbin/itzephir-apply-nginx` helper. The `deploy`
account can only restart/stop the two website services and invoke that helper
for `dev` or `prod`; it cannot use the bootstrap command through sudo.

Setup splits an existing combined site into `/etc/nginx/itzephir/prod.conf` and
`dev.conf`, preserving each environment's server blocks. The enabled site becomes
a wrapper that includes those two files. This preserves a preceding static
production until the first production Ktor deployment, and preserves its config
for automatic rollback during that deployment. On a fresh VM setup uses the
checked-in Ktor templates. Re-running setup preserves managed configurations.
`ConditionPathExists` prevents an unmigrated systemd service from starting.

## Project locator and password login

Locator releases require a configured PostgreSQL database and human web login.
Before the first locator deployment for each environment, run the versioned
bootstrap from a specific, checked Git commit on the VM as root:

```sh
python3 deploy/setup-locator.py dev
# Prepare production before merging the locator PR:
python3 deploy/setup-locator.py prod
```

The script installs Ubuntu's PostgreSQL package if absent and creates a dedicated,
non-superuser database owner and database for the selected environment. PUBLIC
database access is revoked so stage credentials cannot connect to production.
Database password, service admin token, JWT secret and the initial web admin
password are generated on the VM, kept in root-only
`/etc/itzephir/locator-ENV.env`, and never printed or sent through GitHub.
Setup preserves credentials/data when repeated and rejects a conflicting database
owner. An incomplete setup keeps its generated credentials in a root-only pending
file for a safe retry; the application does not load that file.

Setup installs a required, per-environment systemd drop-in, including on older
VMs whose base unit predates locator. It reloads systemd definitions without
restarting the application or changing nginx. The next normal CI deployment
activates the service and checks `/dev`, `/dev/panel`, and their stylesheet over
HTTPS. Missing configuration fails before activation; a missing route triggers
the existing application/nginx rollback. Prepare `prod` before merging the PR.

The initial web admin username is `itzephir`. As the VM owner, retrieve only its
initial password over your own SSH session, then change it under Account:

```sh
sudo sed -n 's/^LOCATOR_ADMIN_PASSWORD=//p' /etc/itzephir/locator-dev.env
```

GitHub OAuth remains optional and requires its own application credentials and
callback; see [project-locator/README.md](../project-locator/README.md).

## Versioned nginx configuration

`deploy/nginx/dev.conf` and `prod.conf` are packaged with every release. After
the candidate application's health check passes, CI publishes only the target
environment's file. A PR updates preview nginx; merging updates production nginx.
Changes to the other environment's template wait for that environment's deploy.

The root-owned helper validates the environment's hostnames/backend and rejects
includes or directives outside server blocks. It serializes nginx changes across
both environments, installs the file atomically, runs `nginx -t`, and reloads
nginx. Unchanged files skip reload. Validation/reload failures restore the
previous file; a subsequent failed HTTPS check restores both the preceding app
and nginx configuration. A release-specific rollback record prevents one
deployment from restoring another deployment's configuration. The helper itself
is installed/updated by root through the setup script.

nginx terminates TLS, redirects HTTP/www, supplies security headers, and performs
gzip compression. Ktor renders HTML, manages theme cookies/cache policy, and
serves its resources. Application jars and deployment files are never served
as static files. No Wasm locations, precompressed bundles, Brotli module,
or frontend production toolchain are required by the portfolio. Node/Chrome
are used only by CI browser tests. The existing Let's Encrypt webroot challenge
at `/var/www/letsencrypt` remains in place.

DNS remains `@` and `dev` A records at `194.87.190.245`, and `www` as a CNAME
to `itzephir.com`.

## Releases and rollback

CI builds `website/build/install/website` with Gradle `installDist`, packages
its nginx templates and `activate-release.sh`, and uploads to a unique
`/srv/itzephir/ENV/releases/RELEASE` directory. It atomically replaces `current`,
restarts only that environment's service, waits for `/healthz`, applies nginx,
and checks HTTPS through nginx, including required locator pages. If any step fails it restores the preceding
symlink and service (stopping Ktor for a preceding static release), plus nginx
when needed. Requests can briefly fail during the restart.

Releases older than seven days are cleaned up, except the current and immediately
previous release. To activate a retained release that includes nginx templates
manually as `deploy`, use:

```bash
bash /srv/itzephir/dev/releases/RELEASE/deploy/activate-release.sh dev RELEASE
```

Inspect services with `systemctl status itzephir@dev` and
`journalctl -u itzephir@dev`; replace `dev` with `prod` for production.

## Other projects on the VM

The `/dev/project/SLUG/` namespace is a section of both websites. Hostname selects
the environment: `itzephir.com/dev/project/SLUG/` is production;
`dev.itzephir.com/dev/project/SLUG/` is stage.

`deploy/nginx/project-gateway.conf` defines isolated loopback gateways:
`18100` includes `/etc/nginx/projects/*-prod.conf`; `18101` includes `*-dev.conf`.
Unknown projects return 404. Individual project deployers publish only their own
route snippets and share `/run/lock/itzephir-nginx.lock` with the portfolio publisher.

The publisher adds the correct gateway location after validating each packaged
application configuration, preserving project routes even for older releases.
`itzephir-apply-nginx --projects` migrates both existing host configurations and
removes the obsolete `/project/` namespace. Shared nginx and root helper updates
must run through committed infrastructure CI, including the initial installation.
The minor-web bootstrap workflow installs these files from the pinned commit;
normal application CI publishes only its own environment's route.

## Stage badge on project pages

`dev.conf` adds a shared interception step before the existing stage project
gateway: public `/dev/project/<slug>/...` requests are rewritten internally to
`/__staging-project/...` and sent to the stage Ktor service. That endpoint proxies
the original path/query to `127.0.0.1:18101`, preserving the project deployer's
route snippets, redirects and cookies. Complete HTML receives the same badge as
the portfolio. Other response types and downloads stream through; HTTP upgrade
requests keep their previous direct gateway route. The internal nginx location
rejects direct external requests.

The rewrite is present only in the stage release template, so normal PR CI
publishes it with the existing validated nginx publisher and rollback. No root
helper upgrade, manual VM edit or project repository change is needed.
`prod.conf` has no interception route, and the production application installs
no proxy endpoint. Query parameters cannot enable interception on production.

CI checks the real chain (public nginx → Ktor → project gateway → project app)
in an isolated container, including compressed requests/responses, query values,
cache validators, redirects, cookies, downloads and production isolation:

```sh
docker run --rm -v "$PWD:/work:ro" -w /work --entrypoint /bin/sh postgres:18-alpine -c \
  'apk add --no-cache nginx python3 openjdk21-jre-headless >/dev/null && PYTHONDONTWRITEBYTECODE=1 PROJECT_BADGE_REQUIRE_NGINX_TESTS=true python3 -m unittest discover -s deploy/tests -p project_badge_integration_test.py'
```

Build `:website:installDist` first. Ordinary Python test discovery skips this
container-only test; the explicit CI step requires it to run.

## Project subdomain routing

The panel can save Docker, proxy and external redirect destinations. Activation is
**opt-in**: normal releases keep the existing `/dev/project/SLUG/` gateway until
repository variable `PROJECT_ROUTING_ENABLED` is `true`. No DNS, certificates or
VM configuration are changed by the routing PR. Preview can show and test the new
forms before public subdomains exist.

Prepare the following separately before enabling the variable:

1. In the DNS provider, add `*.itzephir.com` and `*.dev.itzephir.com` A records
   pointing to the same VM as `itzephir.com`. Keep the existing root, www and dev
   records. Do not publish AAAA records unless IPv6 is configured on the VM.
2. Issue and configure automatic renewal of two wildcard certificates via DNS-01:
   `*.itzephir.com` in `/etc/letsencrypt/live/itzephir-projects-prod/` and
   `*.dev.itzephir.com` in `/etc/letsencrypt/live/itzephir-projects-dev/`.
   Each directory needs `fullchain.pem` and `privkey.pem`. Configure nginx reload
   after renewal. The existing root-domain certificate stays in use for the
   portfolio/panel. Timeweb DNS automation and certificate issuance are separate
   infrastructure work; this PR does not install a provider plugin or credentials.
3. Prepare the GitHub `infrastructure` environment with a dedicated
   `INFRA_SSH_KEY` for root provisioning, and the existing deployment host/known
   hosts settings. Review the merged commit, then run **Prepare project routing
   infrastructure** from `main`, once for `dev` and once for `prod`. The VM needs
   Docker, nginx with `http_auth_request`, OpenSSL and `dig` (`dnsutils`).
4. Connect each project container to **only its stand's** user-defined network:
   `itzephir-projects-dev` or `itzephir-projects-prod`. Put this network membership
   and a stable alias in that project's committed deployment configuration.
   Use the alias and internal application port in the panel, e.g. `minor-web:8080`.
   Docker ports do not need to be published. For services already on the VM,
   select proxy and use a loopback address/port.
5. After **both stands** are prepared, set repository variable
   `PROJECT_ROUTING_ENABLED=true` and run normal application CI. New releases
   package `nginx/enable-project-routing`. The activation script checks publisher
   version 2 before switching releases; missing TLS files fail nginx validation
   and trigger normal app/config rollback. Enabling the variable alone cannot
   install the privileged helper. To disable routing, remove/set the variable to
   false and deploy another release; that restores the legacy gateway.

The committed setup script verifies the wildcard certificate and starts one
restricted CoreDNS container per environment, pinned by image digest. It forwards
DNS to Docker's built-in resolver on that stand's network, exposed only on
loopback `18553` (prod) / `18554` (dev), UDP and TCP. The website keeps running as
its existing systemd service and receives no Docker socket or Docker group access.
The script installs the publisher but does not reload nginx or restart the website;
normal application CI activates changes. Project edits thereafter take effect on
the next request, without another nginx deployment. The resolver uses a five-second
DNS cache so a restarted container's new address is picked up automatically.

Configured projects use `SLUG.itzephir.com` (prod) and `SLUG.dev.itzephir.com`
(stage). The stable `/dev/project/SLUG/` URL redirects with 307 to the matching
subdomain or exact external URL; `/dev/projects/SLUG/` is an alias. Legacy projects
still use their original path: production and upgrades go directly to
`18100`/`18101`; ordinary stage requests pass through the existing Ktor HTML
interceptor before `18101`, preserving stage badges. The registry replaces the
unconditional stage rewrite so approval checks and redirects run first. Approved registry entries are
required once routing is enabled, including legacy projects. Review destinations
before approving: proxy destinations are served by the VM and must be trusted.

Gateway verification uses the generated configuration with real nginx, mock HTTP/
HTTPS backends and DNS, checking redirects, request bodies, verified TLS, legacy
routes, missing/pending/revoked projects and WebSocket upgrades. CI runs it in an
isolated container; it does not need real DNS or certificates.
