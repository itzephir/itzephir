# Deployment

The website runs as two independent Ktor/JVM services on the existing VM.

| Trigger | Release path | Service / port | Hostname |
| --- | --- | --- | --- |
| Push/merge into `main` | `/srv/itzephir/prod/current` | `itzephir@prod`, `127.0.0.1:18080` | `itzephir.com` |
| Same-repository PR targeting `main` | `/srv/itzephir/dev/current` | `itzephir@dev`, `127.0.0.1:18081` | `dev.itzephir.com` |

Develop on a separate feature branch and open a PR targeting `main`. GitHub
Actions tests the application and packaged distribution, then deploys the PR
merge commit to the shared development preview. Merging triggers production.
The last completed PR deployment wins; deployment jobs are serialized per
environment and a running deployment is never cancelled. Closing a PR leaves
the last preview available.

Fork/Dependabot PRs and manual runs are build/test only. The workflow uses
`pull_request`, not `pull_request_target`. Direct pushes to the legacy `dev`
branch do not deploy. Deployment secrets stay unchanged:
`DEPLOY_HOST`, `DEPLOY_USER` (the existing `deploy` account), `DEPLOY_SSH_KEY`,
and `DEPLOY_KNOWN_HOSTS`.

## One-time VM setup

Copy `deploy/` to the existing Ubuntu VM and run `bash deploy/setup-server.sh`
as root. It installs Java 21, the systemd service template, loopback port/cookie
settings, narrowly scoped restart/stop permissions for the `deploy` account,
and the nginx configuration. It validates sudoers/nginx before activation.

The setup does not start an application or activate a release. nginx continues
to serve an existing static `index.html` until that environment receives its
first Ktor release, and can serve it again after rollback. This lets preview
migrate before production; no application files in production change before
merging. `ConditionPathExists` prevents an unmigrated service from starting on
reboot. Legacy Wasm/static nginx locations remain solely for this transition.

`deploy/nginx/itzephir.com.conf` is the source of truth for
`/etc/nginx/sites-available/itzephir.com`. It proxies application and `/assets/`
requests to the appropriate loopback service and never serves application jars
or deployment scripts as files. Ktor renders HTML and serves its own resources.
nginx terminates TLS, redirects HTTP/www, and compresses responses. The existing
Let's Encrypt certificate and webroot renewal challenge remain in place.

DNS remains `@` and `dev` A records at `194.87.190.245`, and `www` as a CNAME
to `itzephir.com`.

## Releases and rollback

CI builds `website/build/install/website` with Gradle `installDist` and includes
`activate-release.sh` in its artifact. Browser checks launch that exact packaged
application using Java 21 and Chrome, checking all six projects, contacts,
webring, mobile overflow, real HTMX swaps, theme persistence, and JavaScript-off
navigation. Screenshots are uploaded with the workflow run.

CI uploads to a unique `/srv/itzephir/ENV/releases/RELEASE` directory, atomically
replaces `current`, and restarts only that environment's systemd service. It
waits for `/healthz` and checks HTTPS through nginx. If restart or either health
check fails, it restores the preceding symlink and restarts that version (or
stops Ktor to resume a preceding static release). This is a restart deployment;
requests can briefly fail during the restart. It does not promise zero downtime.

Releases older than seven days are cleaned up, except the current and immediately
previous release. To activate a retained release manually as `deploy`, use:

```bash
bash /srv/itzephir/dev/releases/RELEASE/deploy/activate-release.sh dev RELEASE
```

Inspect services with `systemctl status itzephir@dev` and
`journalctl -u itzephir@dev`; replace `dev` with `prod` for production.
