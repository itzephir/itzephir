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
container runtime, or frontend production toolchain are required. Node/Chrome
are used only by CI browser tests. The existing Let's Encrypt webroot challenge
at `/var/www/letsencrypt` remains in place.

DNS remains `@` and `dev` A records at `194.87.190.245`, and `www` as a CNAME
to `itzephir.com`.

## Releases and rollback

CI builds `website/build/install/website` with Gradle `installDist`, packages
its nginx templates and `activate-release.sh`, and uploads to a unique
`/srv/itzephir/ENV/releases/RELEASE` directory. It atomically replaces `current`,
restarts only that environment's service, waits for `/healthz`, applies nginx,
and checks HTTPS through nginx. If any step fails it restores the preceding
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
