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

The portfolio keeps `@` and `dev` A records at `194.87.190.245`, and `www` as a
CNAME to `itzephir.com`. Project wildcard records are described below.

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

Wildcard DNS is configured at Timeweb: `*.itzephir.com` and
`*.dev.itzephir.com` point to `194.87.190.245` (TTL 300). Keep the existing root,
www, dev and mail records; do not publish AAAA unless the VM supports IPv6.

### Prepare TLS, Docker and nginx through CI

After this change is merged, run **Prepare project routing infrastructure** from
`main`, first for `dev`, then for `prod`. It is an explicit infrastructure job;
ordinary PR checks and project approval never run provisioning. The job prepares
the chosen environment and leaves route activation to normal website deployment.

Configure the GitHub environment `infrastructure` with deployment restricted to
`main` (and the desired reviewer protection):

- `INFRA_SSH_KEY`: a dedicated root SSH key for provisioning, separate from the
  restricted `deploy` account key. Install its public key through the VM owner.
- `TIMEWEB_DNS_TOKEN`: a Timeweb Cloud API token permitted to list/create/delete
  DNS records for `itzephir.com`. Use the narrowest permissions Timeweb supports.
  Configure it as an environment secret; do not paste it into issues or commits.
- Existing repository secrets `DEPLOY_HOST` and `DEPLOY_KNOWN_HOSTS` are reused.
- Optional environment variable `ACME_EMAIL`: contact for ACME registration on a
  fresh VM. On this VM the script reuses the account from the existing
  `/etc/letsencrypt/renewal/itzephir.com.conf`, so no new account is needed.

The VM must already have Certbot, Python 3, OpenSSL, `dig` (`dnsutils`), `flock`,
Docker and systemd, plus nginx with `http_auth_request`. The workflow fails on
missing settings before connecting. It uploads the scripts from that exact main
commit and a private token file into a root-only temporary directory; an exit
trap removes the remote and runner copies after success or failure.

On the VM, `deploy/prepare-project-routing.sh` serializes provisioning and:

1. Checks the existing nginx configuration and Docker daemon before changes.
2. Installs the committed DNS-01 auth/cleanup hooks, then obtains the selected
   `*.itzephir.com` / `*.dev.itzephir.com` certificate under
   `/etc/letsencrypt/live/itzephir-projects-prod/` / `itzephir-projects-dev/`.
   It uses `certbot certonly --keep-until-expiring`; nginx configuration and the
   existing root-domain certificate are not rewritten by Certbot.
3. Tests the saved renewal configuration with `certbot renew --cert-name …
   --dry-run --no-random-sleep-on-renew`, then enables the existing `certbot.timer`.
   The explicit CI check skips Certbot's random startup delay; normal timer-driven
   renewals keep their default scheduling. Renewals run on the VM;
   they do not depend on a scheduled GitHub workflow. A deploy hook takes the
   shared nginx lock, runs `nginx -t`, and reloads nginx only after success.
4. Prepares the stand's managed Docker network and restricted CoreDNS container,
   checks Docker name resolution over UDP and TCP, and atomically installs the
   version 2 nginx publisher. A failed DNS replacement restores its previous
   container and Corefile. An existing unlabelled/foreign network or container is
   rejected instead of being adopted or removed.
5. Runs the publisher's `--check-routing dev|prod`: renders the future wildcard
   nginx servers into a temporary standalone configuration and checks it with
   `nginx -t`. Active files stay intact, including on validation failure. It then
   checks the current nginx configuration again; the website is not restarted
   and routing is not enabled.

The root-only `/etc/itzephir/timeweb-dns.token` (mode `0600`) remains on the VM for
unattended renewal. Updating the environment secret and rerunning provisioning
rotates that credential; failed issuance/renewal checks restore the previous token.
The Python hook uses Timeweb API v1 directly, matching
`twc`'s TXT operation; it needs no CLI profile or third-party Python packages.
It permits only `_acme-challenge.itzephir.com` and
`_acme-challenge.dev.itzephir.com`, waits for every authoritative server plus a
recursive resolver, and removes only the exact TXT record ID created by its auth
invocation. It waits up to 20 minutes for each challenge and queries the recursive
TXT cache only after every authoritative server has the new validation. This
avoids caching an old wildcard TXT while Timeweb is still synchronizing its DNS
servers, and allows an already cached response to expire. The workflow has a
60-minute budget for issuance and the renewal check. Existing TXT/SPF/MX/A records
and concurrent validations are preserved.
An interrupted process before cleanup can leave a stale challenge TXT; remove
only that recorded validation after checking that no issuance is in progress.

The isolated provisioning tests use fake ACME, Docker and systemd commands with
real root filesystem permissions, shell scripts and OpenSSL. A separate test runs
the actual pinned CoreDNS image with the same launch function/Corefile and checks
Docker name resolution over both UDP and TCP. The container retains only
`NET_BIND_SERVICE` in its capability bounding set because the official binary
carries that file capability; non-root execution, read-only filesystem and
`no-new-privileges` remain enabled. PR CI never requests
real certificates or changes DNS. The existing nginx/TLS/WebSocket integration
suite tests generated routing separately.

References: [Certbot manual hooks and renewal](https://eff-certbot.readthedocs.io/en/stable/using.html#pre-and-post-validation-hooks),
[Timeweb API](https://timeweb.cloud/api-docs).

### Activate project routing

1. Connect each project container to **only its stand's** network:
   `itzephir-projects-dev` or `itzephir-projects-prod`. Commit the membership and
   stable alias in the project's deployment configuration. Use that alias and
   internal port in the panel, e.g. `minor-web:8080`; no published Docker port is
   needed. For host services, select proxy with a loopback address/port.
2. After both infrastructure runs succeed, set repository variable
   `PROJECT_ROUTING_ENABLED=true`, then perform normal website deployments for
   preview and production. This packages `nginx/enable-project-routing`; the
   publisher applies each environment's nginx configuration atomically with
   validation and rollback. It does not create a separate nginx file per project.
3. Verify the site, `/dev`, `/dev/panel`, and an approved running project's
   subdomain/entry URL on each stand. Planning projects keep their card without a
   launch link. Roll back by setting the variable to false and redeploying; TLS
   files and Docker networks may remain available for a later activation.

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
