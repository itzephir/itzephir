# Deployment

The website has two independent environments on one virtual machine:

| Trigger | Server path | Hostname |
| --- | --- | --- |
| Push/merge into `main` | `/srv/itzephir/prod/current` | `itzephir.com` |
| Open/update/reopen a same-repository PR targeting `main` | `/srv/itzephir/dev/current` | `dev.itzephir.com` |

Develop changes in a separate branch and open a PR targeting `main`. The
`.github/workflows/build-website.yml` workflow checks and builds the PR merge
commit, then deploys it to development. It publishes the preview URL in the
GitHub environment and updates a single comment in the PR. Merging the PR into
`main` triggers production deployment; opening a PR never changes production.

Development is one shared preview, not one instance per PR: the last completed
deployment wins. Uploads and activation are serialized per environment, and a
running deployment is never canceled by another deployment. Each run gets its
own release directory, activated by atomically replacing the `current` symlink.
Closing a PR leaves the last preview available until another PR deploys.

Fork and Dependabot PRs run build checks only and never receive deployment
secrets. The workflow intentionally uses `pull_request`, not
`pull_request_target`. Manual workflow runs are build-only. Direct pushes to
the legacy `dev` branch no longer trigger deployment.

The GitHub repository requires these Actions secrets:

- `DEPLOY_HOST` — SSH host or IP;
- `DEPLOY_USER` — restricted deployment user;
- `DEPLOY_SSH_KEY` — private key used only by GitHub Actions;
- `DEPLOY_KNOWN_HOSTS` — pinned SSH host key line.

The nginx configuration is stored at `deploy/nginx/itzephir.com.conf` and is
installed as `/etc/nginx/sites-available/itzephir.com`.

## DNS

The required records are:

```text
@    A      deployment-host.example.invalid
dev  A      deployment-host.example.invalid
www  CNAME  itzephir.com.
```

## HTTPS

One Let's Encrypt certificate covers `itzephir.com`, `www.itzephir.com`, and
`dev.itzephir.com`. Certbot renews it automatically with the webroot challenge
stored in `/var/www/letsencrypt`; nginx keeps that challenge path available over
HTTP and redirects all other requests to HTTPS. `www.itzephir.com` redirects to
the canonical `itzephir.com` hostname.

The production artifact is prepared by `deploy/prepare-web-release.sh`, using
Node.js 24+ and its built-in Brotli/gzip support (no extra compression package).
It injects preload hints for generated Wasm filenames, inlines the small startup
stylesheet, and names the entry script by its SHA-256 content hash. Both the
script preload and the deferred script loader use that same versioned URL.
nginx serves precompressed assets; the existing static-asset policy can safely
cache the versioned script between visits. The legacy `itzephir.js` endpoint
remains `no-store` for compatibility; hashed Wasm stays immutable.

Run `node --test deploy/tests/*.test.mjs` to check CSS inlining, consistent
preloads, cache invalidation, idempotent packaging, and compression round trips.
These checks also run in CI before the Kotlin build.

The HTML shell and Compose load the same bundled Roboto and Roboto Mono variable
fonts, so text metrics do not depend on the host operating system's generic font
families. The CI runs a real-browser parity test in Chrome on Linux after
preparing the production distribution. The test holds Wasm loading to capture
the HTML startup shell,
releases Compose, then compares
the geometry of the brand, theme control, hero copy, button labels, and portrait at
desktop and mobile viewport sizes. Each landmark may move by at most 8 px, and
the mobile buttons must remain on one row. Run it locally with:

```bash
npm ci --prefix deploy --ignore-scripts
CHROME_EXECUTABLE="/path/to/Chrome" npm run test:visual --prefix deploy
```

For a repeatable browser comparison, keep two prepared distribution directories
and run `node deploy/benchmark-startup.mjs /path/to/before /path/to/after` with
Playwright and Chromium installed. Optional `PLAYWRIGHT_MODULE` and
`CHROME_EXECUTABLE` environment variables select existing installations. The
benchmark alternates three cold/warm runs per build at 10 Mbps, 100 ms latency,
and 4x CPU slowdown; it prints JSON lines with paint, Compose readiness, resource
timings, and bytes transferred. It uses isolated browser contexts and local
servers, with the same caching policy as nginx. These are comparative lab
measurements, not a guarantee of a visitor's loading time.

The measured before/after results and remaining limitations are recorded in
[PERFORMANCE.md](PERFORMANCE.md).
