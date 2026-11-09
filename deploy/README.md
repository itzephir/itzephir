# Deployment

The private OpenDesign workspace has an independent, committed deployment;
see [opendesign/README.md](opendesign/README.md) for capacity, access and recovery.

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

The portfolio keeps `@` and `dev` A records pointing to the privately configured
deployment host, and `www` as a CNAME to `itzephir.com`. Project wildcard records
are described below. Keep host addresses and provider resource IDs out of Git.

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

Projects are published at `SLUG.itzephir.com` and `SLUG.dev.itzephir.com`.
The latter is a relay through the **dev stand's Project Locator**, not a request
to launch an application's development environment. Each locator owns its own
approved project targets. The project interprets any additional hostname prefix
and owns its application builds, containers, CI and production/dev/PR stands.

Public `/dev/project/SLUG/`, `/dev/projects/SLUG/` and the old staging interceptor
are removed and return 404, without redirects. The catalog, panel and resolve API
use direct subdomain URLs. Migration `005-project-hosts.sql` transfers the verified
existing `minor-website` / `minor-web` record from `legacy` to `proxy`, on both stands.
The stable internal targets are `http://127.0.0.1:18100/dev/project/minor-web` (prod)
and `http://127.0.0.1:18101/dev/project/minor-web` (dev). These loopback gateways and
project-owned snippets stay in place: the project's CI owns its rotating backend
ports, so the migration must not pin the container currently occupying one slot.
They are internal proxy destinations, not public launch paths. The outer gateway
preserves the public host and strips the internal prefix/port from redirects and
cookie paths. Do not remove `/etc/nginx/conf.d/project-gateway.conf` or the migrated
project's snippets while its registry target still uses them.

The migration runs transactionally once during normal application startup: PR
deployment applies it to dev; a permitted merge/deployment applies it to production.
It increments only the migrated record's revision, preserving IDs, credentials,
owners, review status/metadata, revocation, description and readiness. Already
configured destinations (including planning `anyq` and `itzmoney`) and other rows
are untouched. It selects the stand from the standard database name installed by
`setup-locator.py`; with a custom database name, set `LOCATOR_ENVIRONMENT=dev|prod`
in its private environment file. An ambiguous stand fails startup and rolls back
the transaction instead of routing a development project to production.

Other `legacy` records remain editable as unconfigured records, with null launch
URLs and no launch button; select a Docker/proxy/redirect target and approve it to
publish them. Underscore slugs must be changed to DNS labels first. The `minor-web`
repository will need to use its new subdomain for future CI public health checks;
this repository does not change application deployment scripts.
The portfolio's stage badge remains on the portfolio and locator pages. Projects
own their own environment indicators.

## Project subdomain routing

The panel can save Docker, proxy and external redirect destinations. Activation is
**opt-in**: project subdomains are enabled when repository variable
`PROJECT_ROUTING_ENABLED` is `true`. Retired path routes return 404 with or without
this flag. PR checks do not change DNS, certificates or VM configuration.

Wildcard DNS records `*.itzephir.com` and `*.dev.itzephir.com` must point to the
privately configured deployment host. Keep the existing root,
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
It permits only the base challenges `_acme-challenge.itzephir.com` and
`_acme-challenge.dev.itzephir.com`, plus `_acme-challenge.SLUG[.dev].itzephir.com`
for valid project DNS labels (excluding `dev`, `www` and `opendesign`). It waits for
every authoritative server plus a recursive resolver, and removes only the exact
TXT record ID created by its auth invocation. It waits up to 20 minutes for each challenge and queries the recursive
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
   validation and rollback. Project targets are still resolved per request.
3. Verify the site, `/dev`, `/dev/panel`, and an approved running project's
   subdomain URL on each stand. Planning projects keep their card without a
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
(dev locator). Every request checks approval, readiness and revocation. Review
destinations before approving: proxy destinations are served by the VM and must
be trusted. The catalog's launch link selects the hostname matching its locator
stand; it does not proxy application content through `/dev`.

### Enable a project's hostname namespace

An additional **single DNS label** is project-owned: `dev.anyq.itzephir.com`,
`pr-42.anyq.dev.itzephir.com`, or `anything.anyq.itzephir.com` all select `anyq`
in the corresponding locator. Prefixes have no deployment meaning to itzephir.
Unknown/pending/revoked/planning projects still return 404. A prefix equal to a
second registered slug does not switch projects. Other depths are deliberately
rejected: standard wildcard TLS certificates cover exactly one label.

Base routing above must already be prepared. For each project and locator stand:

1. Register its target in that locator, configure the application to accept its
   public hostnames, set readiness to `development`, and approve the record.
   A Docker target can be the project's own router; project CI manages its
   services behind that router. No application deployment is added to itzephir.
2. In Timeweb, point **both the exact name and its wildcard** to the existing
   gateway VM, preserving mail and unrelated records:

   | Locator | Exact A record | Wildcard A record |
   | --- | --- | --- |
   | prod | `anyq` | `*.anyq` |
   | dev | `anyq.dev` | `*.anyq.dev` |

   These are names relative to the `itzephir.com` zone. Do not rely on the common
   wildcard for the exact name: adding a wildcard/TXT child creates a DNS node
   that can prevent fallback to the parent wildcard. Use the privately configured
   VM address; do not publish an AAAA record without working IPv6. Check resolution
   of both `anyq[.dev].itzephir.com` and `routing-probe.anyq[.dev].itzephir.com`
   from authoritative and recursive DNS before proceeding.
3. After the infrastructure change is merged, run **Prepare project routing
   infrastructure** from `main` with `target=dev`, `namespace=anyq`; repeat with
   `target=prod` when needed. Leaving `namespace` empty prepares base routing.
   The project wildcard is issued separately as
   `/etc/letsencrypt/live/itzephir-project-dev-anyq/` or
   `itzephir-project-prod-anyq/`. Existing common/root certificates are preserved.
   The same unattended DNS-01 renewal and validated nginx deploy hook apply.
4. Provisioning checks the new certificate and staging renewal, validates the
   complete future nginx configuration, then atomically adds only the slug to
   `/etc/itzephir/project-namespaces/dev.json` or `prod.json` (root-owned).
   Repeating enrollment is safe and adding another slug preserves earlier ones.
   A failed issuance, renewal check or nginx preflight does not enroll the slug.
   Namespace preparation does not rebuild/restart project containers, change
   targets or activate nginx. The manifests are infrastructure enrollment, not
   another service registry.
5. Run a normal website deployment for that locator stand with
   `PROJECT_ROUTING_ENABLED=true`. It reads the persistent manifest and publishes
   the namespace servers with their own certificates and a fixed resolver slug.
   For dev, rerun the PR's **Website CI/CD** run (or open a small follow-up PR);
   for production, rerun the `main` push run after the merge. Manual workflow
   dispatch is build/test only. Ordinary project target changes
   subsequently take effect on the next request without provisioning/redeploying
   nginx. Changing the project's slug requires separate enrollment for the new
   name; old certificates/enrollment do not grant access after registry removal.
6. Verify the base address, a project-defined prefix, path/query, upload,
   application redirects and WebSocket endpoint. Verify an unregistered slug and
   a revoked project cannot reach the application. A prefixed host uses the same
   locator target as the base address; it is the application's job to select a
   concrete stand.

For an operator running the committed scripts directly, the equivalent is
`sudo bash deploy/prepare-project-routing.sh dev /PRIVATE/TOKEN_FILE anyq`, followed
by normal CI deployment. Never put the token value in arguments. To disable one
namespace, remove its slug from the corresponding root-owned JSON manifest and
redeploy that stand; keep certificates until no active/rollback nginx config
references them. To stop project access immediately, revoke it in the locator.
Setting `PROJECT_ROUTING_ENABLED=false` and redeploying disables all project
subdomains for that stand; retired public path routes remain 404.

Docker destinations receive the normalized public hostname in both `Host` and
`X-Forwarded-Host`. Proxy destinations retain their configured upstream authority
in `Host` for compatibility with virtual hosts; their original public hostname is
always in the gateway-overwritten `X-Forwarded-Host`. Verified upstream TLS uses
the destination's DNS name for SNI and certificate verification, independently of
public `Host`. Both modes preserve the original escaped path, query, body and
WebSocket upgrade. Proxy base paths are prepended; internal upstream redirects
are rewritten to public paths. Redirect targets remain exact: no source path or
query is appended. Applications must trust forwarded headers only from the gateway.

Gateway verification uses the generated prod and dev configuration together with
real nginx, distinct common/namespace certificates, mock HTTP/HTTPS backends and
DNS. It checks verified SNI/hostname matching, wrong-depth rejection, stand and
slug isolation, HTTP redirects, request bodies, upstream TLS, retired entry paths,
missing/pending/revoked projects, forwarded hosts and WebSocket upgrades. CI uses
an isolated container and never requests public certificates or edits live DNS.

References: [nginx server-name selection](https://nginx.org/en/docs/http/server_names.html),
[wildcard TLS depth](https://docs.aws.amazon.com/acm/latest/userguide/acm-concepts.html),
[DNS wildcard synthesis](https://www.rfc-editor.org/rfc/rfc4592#section-2.2.2).
